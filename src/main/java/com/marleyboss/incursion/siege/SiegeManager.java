package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlock;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.block.WardBlockEntity;
import com.marleyboss.incursion.data.CoreWorldData;
import com.marleyboss.incursion.entity.CoreTargetEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All siege logic that runs once per second for the active Core. */
public final class SiegeManager {
    /** Scoreboard tag on every mob spawned by the siege. */
    public static final String SIEGE_TAG = "incursion_siege";

    private static final ResourceLocation FOLLOW_RANGE_ID = id("siege_follow_range");
    private static final ResourceLocation HEALTH_BONUS_ID = id("siege_health_bonus");
    private static final ResourceLocation DAMAGE_BONUS_ID = id("siege_damage_bonus");
    private static final double MAX_FOLLOW_RANGE = 128.0;
    private static boolean stallAnnounced = false;

    private SiegeManager() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Incursion.MODID, path);
    }

    // ------------------------------------------------------------------
    // Main tick (once per second)
    // ------------------------------------------------------------------

    public static void tickCore(ServerLevel level, BlockPos pos, CoreBlockEntity core, int tickCounter) {
        core.validateHealth();
        int coreLevel = core.getCoreLevel();

        // 1. Keep the siege area loaded.
        int desiredRadius = Config.siegeChunkRadius(coreLevel);
        if (core.getLoadedChunkRadius() != desiredRadius) {
            ChunkLoading.update(level, pos, core.getLoadedChunkRadius(), desiredRadius);
            core.setLoadedChunkRadius(desiredRadius);
        }

        // 2. Make sure the invisible target exists.
        CoreTargetEntity target = core.ensureTarget(level);
        if (target == null) {
            return;
        }

        // 3. Regeneration.
        double regen = Config.CORE_REGEN_PER_MINUTE.get() / 60.0;
        if (regen > 0 && core.getHealth() < core.getMaxHealth()) {
            core.setHealth((float) (core.getHealth() + regen));
        }

        // 4. Publish the zones for the event handlers.
        SiegeRules.refresh();
        long now = level.getGameTime();
        SiegeProgress.ensure(pos, now);
        int coreZone = Config.coreZoneRadius(coreLevel);
        int siegeRadius = Config.siegeRadiusBlocks(coreLevel);
        int aggroRange = Config.PLAYER_AGGRO_RANGE.get();
        int elevation = Math.max(0, SiegeAirDrop.elevation(level, pos, coreZone + 6));
        boolean underground = isUnderground(level, pos);
        List<BlockPos> wards = WardBlockEntity.wardsNear(level, pos, siegeRadius);
        ActiveSiege.update(level, pos, target, coreZone, siegeRadius, aggroRange, coreLevel,
                core.isShowBorders(), core.isShowAi(), elevation, underground, wards);
        ActiveSiege siege = ActiveSiege.get(level);
        if (siege == null) {
            return;
        }

        // 5. Point every hostile mob in the area at the Core or at a player (depending on its role).
        AABB area = new AABB(pos).inflate(siegeRadius, 64, siegeRadius).expandTowards(0, -elevation, 0);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, area, Entity::isAlive);
        List<Mob> fireArchers = new ArrayList<>();
        List<Mob> attackers = new ArrayList<>();
        int siegeMobs = 0;
        MobRoles.resetCounts();
        for (Mob mob : mobs) {
            if (mob.getTags().contains(SIEGE_TAG)) {
                siegeMobs++;
                MobRoles.count(mob);
            }
            if (!SiegeRules.isAttacker(mob)) {
                continue;
            }
            int zone = siege.zoneOf(mob);
            if (zone == ActiveSiege.ZONE_OUTSIDE) {
                continue;
            }
            if (inCave(level, siege, mob, zone)) {
                // Deep in a cave below a Core on the surface: no use to the siege. Natural monsters just stay
                // normal cave monsters; siege mobs disappear (the siege sends a new one to a front).
                if (mob.getTags().contains(SIEGE_TAG)) {
                    SiegeAI.recall(level, mob);
                    siegeMobs--;
                } else {
                    SiegeAI.releaseFromSiege(mob);
                }
                continue;
            }
            attackers.add(mob);
            if (MobRoles.of(mob) == MobRoles.Role.FIRE_ARCHER) {
                fireArchers.add(mob);
            }
            SiegeTeam.join(mob);
            Targeting.update(mob, siege, zone);
            if (mob.getTarget() == target) {
                ensureFollowRange(mob, target);
            } else if (mob.getTarget() instanceof net.minecraft.world.entity.player.Player hunted
                    && MobRoles.isHunter(mob)) {
                ensureFollowRange(mob, hunted); // hunters can see the player they chase from far away
            }
        }
        core.setSiegeMobCount(siegeMobs);

        // 6. Fire archers ignite TNT placed by sappers.
        SiegeExplosives.tickTnt(level, fireArchers, now);

        // 7. The commander analyses the situation, plans the fronts and decides which roles are needed.
        boolean stalled = SiegeProgress.isStalled(now);
        int max = Config.maxSiegeMobs(coreLevel) + wards.size() * Config.WARD_EXTRA_MOBS.get();
        if (stalled) {
            max += max * Config.STALL_EXTRA_MOBS_PERCENT.get() / 100;
        }
        SiegeCommander.tick(level, siege, attackers, wards, max, now);
        PlayerHunt.tick(level, siege, attackers, wards, max, now);

        // 8. Show the borders and the AI view, if switched on in the Core GUI.
        // The borders (squares with beams at the corners) are drawn by the client renderer from synced data.
        core.syncToClient(wards);
        if (siege.showDebug) {
            SiegeDebug.drawRoutes(level, siege, now);
        }

        // 9. Spawn new attackers. If the siege is stuck, reinforcements may exceed the normal limit.
        int interval = Config.SPAWN_INTERVAL_SECONDS.get();
        if (stalled && !stallAnnounced) {
            stallAnnounced = true;
            level.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.incursion.siege.reinforcements").withStyle(ChatFormatting.GOLD), false);
        } else if (!stalled) {
            stallAnnounced = false;
        }
        if ((tickCounter / 20) % interval == 0 && level.getDifficulty() != Difficulty.PEACEFUL) {
            int free = max - siegeMobs;
            boolean urgent = SiegeCommander.urgentWorkerNeeded(max);
            if (free <= 0 && urgent && siegeMobs < max + EXTRA_WORKERS) {
                free = 1; // a front has nobody who can do its work: one worker may come on top
            }
            int toSpawn = Math.min(free, 1 + coreLevel / 4 + (stalled || urgent ? 1 : 0));
            if (toSpawn <= 0) {
                spawnDebug(level, "limit", "Kein Platz im Limit: " + siegeMobs + " Belagerer, Maximum " + max);
            }
            if (toSpawn > 0) {
                int spawned;
                if (SiegeCommander.planFailed()) {
                    // No way to the Core found: scattered spawns, and more come by air.
                    double airChance = Math.max(Config.AIR_DROP_STALL_CHANCE.get(),
                            Config.AIR_DROP_BASE_CHANCE.get() + Config.AIR_DROP_CHANCE_PER_LEVEL.get() * coreLevel);
                    spawned = spawnScattered(level, pos, coreLevel, toSpawn, target, wards, now, airChance, elevation);
                } else {
                    // With a plan, the siege takes its way; only a few attackers come by air.
                    double airChance = Config.AIR_DROP_BASE_CHANCE.get() + Config.AIR_DROP_CHANCE_PER_LEVEL.get() * coreLevel;
                    spawned = spawnForFronts(level, pos, coreLevel, toSpawn, target, now, airChance, max);
                }
                if (spawned == 0) {
                    spawnDebug(level, "none", "Runde ohne Spawn (Fronten: " + SiegeCommander.frontCount()
                            + ", Plan fehlgeschlagen: " + SiegeCommander.planFailed() + ")");
                }
                core.setSiegeMobCount(siegeMobs + spawned);
            }
            // The hunter team's own builders and miners (they come on top of the siege's mob limit).
            spawnHuntWorkers(level, coreLevel, target, now, max);
        }
    }

    /**
     * Spawns the builders and miners the hunt needs at the assembly point of its way (a few blocks from the hunted
     * player). They carry {@link PlayerHunt#WORKER_TAG} and belong to the hunter team.
     */
    private static void spawnHuntWorkers(ServerLevel level, int coreLevel, CoreTargetEntity target, long now,
                                         int max) {
        List<EntityType<?>> pool = SiegeRules.spawnPool(coreLevel);
        RandomSource random = level.getRandom();
        for (int i = 0; i < 2; i++) {
            PlayerHunt.SpawnOrder order = PlayerHunt.nextSpawn(max);
            if (order == null) {
                return;
            }
            BlockPos spawnPos = assemblySpot(level, order.front().assembly(), random);
            if (spawnPos == null) {
                return;
            }
            Mob mob = createSiegeMob(level, zombieType(pool, random), spawnPos, coreLevel, random, now, order.role(), true);
            if (mob == null) {
                return;
            }
            if (!level.noCollision(mob) || level.containsAnyLiquid(mob.getBoundingBox())) {
                mob.discard();
                return;
            }
            mob.addTag(PlayerHunt.WORKER_TAG);
            level.addFreshEntityWithPassengers(mob);
            aimAtCore(mob, target);
            SiegeAI.assign(mob, order.front(), now);
            level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1.0, mob.getZ(), 30, 0.4, 0.8, 0.4, 0.2);
            PlayerHunt.noteOrdered(order);
        }
    }

    /** A front without its builder / miner may get one even if the siege is at its limit (at most this many over). */
    private static final int EXTRA_WORKERS = 2;

    /** Removes the long sight range again (the mob left the siege). */
    public static void clearFollowRange(Mob mob) {
        AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null && followRange.getModifier(FOLLOW_RANGE_ID) != null) {
            followRange.removeModifier(FOLLOW_RANGE_ID);
        }
    }

    /** Lets the mob "see" the Core from far away so it can path towards it. */
    private static void ensureFollowRange(Mob mob, net.minecraft.world.entity.LivingEntity target) {
        AttributeInstance followRange = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange == null) {
            return;
        }
        double needed = Math.min(MAX_FOLLOW_RANGE, Math.sqrt(mob.distanceToSqr(target)) + 16.0);
        AttributeModifier existing = followRange.getModifier(FOLLOW_RANGE_ID);
        double currentBonus = existing != null ? existing.amount() : 0.0;
        double withoutBonus = followRange.getValue() - currentBonus;
        double bonus = Math.max(0.0, needed - withoutBonus);
        if (bonus <= 0.0) {
            if (existing != null) {
                followRange.removeModifier(FOLLOW_RANGE_ID);
            }
        } else if (existing == null || Math.abs(currentBonus - bonus) > 4.0) {
            followRange.addOrUpdateTransientModifier(
                    new AttributeModifier(FOLLOW_RANGE_ID, bonus, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    // ------------------------------------------------------------------
    // Spawning
    // ------------------------------------------------------------------

    private static final Map<String, Long> DEBUG_LAST = new HashMap<>();

    /** With the AI view switched on: tells (at most every 10 s per key) why nothing spawned. */
    private static void spawnDebug(ServerLevel level, String key, String text) {
        ActiveSiege siege = ActiveSiege.get(level);
        if (siege == null || !siege.showDebug) {
            return;
        }
        long now = level.getGameTime();
        Long last = DEBUG_LAST.get(key);
        if (last != null && now - last < 200) {
            return;
        }
        DEBUG_LAST.put(key, now);
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("[Spawn] " + text).withStyle(ChatFormatting.DARK_GRAY), false);
    }

    private static int spawnScattered(ServerLevel level, BlockPos core, int coreLevel, int count,
                                      CoreTargetEntity target, List<BlockPos> wards, long now, double airChance,
                                      int elevation) {
        List<EntityType<?>> pool = SiegeRules.spawnPool(coreLevel);
        if (pool.isEmpty()) {
            return 0;
        }
        ActiveSiege siege = ActiveSiege.get(level);
        RandomSource random = level.getRandom();
        int minDistance = Config.coreZoneRadius(coreLevel) + 6;
        int maxDistance = Math.min(Config.SPAWN_MAX_DISTANCE.get(), Config.siegeRadiusBlocks(coreLevel) - 4);
        maxDistance = Math.max(maxDistance, minDistance + 4);
        int wardRadius = Config.WARD_RADIUS.get();
        // With wards, mobs may also spawn a bit farther out (otherwise a big base leaves no room).
        int wardMaxDistance = Math.max(maxDistance, Config.siegeRadiusBlocks(coreLevel) - 4);
        boolean airAllowed = SiegeAirDrop.isEnabled();
        boolean underground = isUnderground(level, core);
        // The attackers of one wave arrive together, from one side (they form a group and share a route);
        // the next wave comes from somewhere else.
        double groupAngle = random.nextDouble() * Math.PI * 2.0;
        double groupDistance = minDistance + random.nextDouble() * (maxDistance - minDistance);

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            EntityType<?> type = pickType(pool, random, now);
            boolean carry = airAllowed && SiegeAirDrop.canCarry(type);
            // A few attackers come by air (more at high levels, many while the siege is stuck).
            if (carry && random.nextDouble() < airChance) {
                if (airDrop(level, core, coreLevel, type, target, minDistance, maxDistance, random, now)) {
                    spawned++;
                    continue;
                }
            }
            boolean done = false;
            for (int attempt = 0; attempt < 16 && !done; attempt++) {
                double angle;
                double distance;
                if (attempt < 6) {
                    // Close to the rest of the group.
                    angle = groupAngle + (random.nextDouble() - 0.5) * 6.0 / Math.max(8.0, groupDistance);
                    distance = Mth.clamp(groupDistance + (random.nextDouble() - 0.5) * 6.0, minDistance, maxDistance);
                } else {
                    angle = random.nextDouble() * Math.PI * 2.0;
                    int limit = attempt < 11 ? maxDistance : wardMaxDistance;
                    distance = minDistance + random.nextDouble() * (limit - minDistance);
                }
                int x = core.getX() + Mth.floor(Math.cos(angle) * distance);
                int z = core.getZ() + Mth.floor(Math.sin(angle) * distance);
                if (insideWard(wards, x, z, wardRadius) || (siege != null && siege.isSafe(x, z))) {
                    continue; // never inside the safe zone (square around the Core / ward totems)
                }
                BlockPos spawnPos = findStandingPos(level, x, z, core.getY(), underground, elevation);
                if (spawnPos == null) {
                    continue;
                }
                Mob mob = createSiegeMob(level, type, spawnPos, coreLevel, random, now);
                if (mob == null) {
                    continue;
                }
                if (!level.noCollision(mob) || level.containsAnyLiquid(mob.getBoundingBox())) {
                    mob.discard();
                    continue;
                }
                if (mob instanceof Spider spider && random.nextDouble() < Config.SPIDER_RIDER_CHANCE.get()) {
                    addRider(level, spider, coreLevel, random, now);
                }
                level.addFreshEntityWithPassengers(mob);
                aimAtCore(mob, target);
                for (Entity passenger : mob.getPassengers()) {
                    if (passenger instanceof Mob rider) {
                        aimAtCore(rider, target);
                    }
                }
                level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                        30, 0.4, 0.8, 0.4, 0.2);
                spawned++;
                done = true;
            }
            // No room on the ground (e.g. the Core floats high above the void): come by air.
            if (!done && carry && airDrop(level, core, coreLevel, type, target, minDistance, maxDistance, random, now)) {
                spawned++;
            }
        }
        return spawned;
    }

    /**
     * Spawns what the commander orders, at the assembly point of the front (the foot of its way): workers first
     * (builders, miners), attackers once the way is (almost) open. A few attackers come by air instead.
     */
    private static int spawnForFronts(ServerLevel level, BlockPos core, int coreLevel, int count,
                                      CoreTargetEntity target, long now, double airChance, int max) {
        List<EntityType<?>> pool = SiegeRules.spawnPool(coreLevel);
        if (pool.isEmpty()) {
            return 0;
        }
        RandomSource random = level.getRandom();
        int minDistance = Config.coreZoneRadius(coreLevel) + 6;
        int maxDistance = Math.max(minDistance + 4,
                Math.min(Config.SPAWN_MAX_DISTANCE.get(), Config.siegeRadiusBlocks(coreLevel) - 4));
        int spawned = 0;
        boolean airDropped = false;
        for (int i = 0; i < count; i++) {
            SiegeCommander.SpawnOrder order = SiegeCommander.nextSpawn(max);
            if (order == null) {
                spawnDebug(level, "order", "Kein Bedarf: keine Front oder nichts bestellt");
                break; // nothing needed right now (the workers are busy, the attackers come when the way is open)
            }
            MobRoles.Role role = order.role();
            EntityType<?> type = role != null ? zombieType(pool, random) : pool.get(random.nextInt(pool.size()));
            // At most one attacker per spawn round comes by air (the siege takes its ways, air drops stay rare).
            if (role == null && !airDropped && SiegeAirDrop.isEnabled() && SiegeAirDrop.canCarry(type)
                    && random.nextDouble() < airChance
                    && airDrop(level, core, coreLevel, type, target, minDistance, maxDistance, random, now)) {
                airDropped = true;
                SiegeCommander.noteOrdered(order);
                spawned++;
                continue;
            }
            BlockPos spawnPos = assemblySpot(level, order.front().assembly(), random);
            if (spawnPos == null) {
                BlockPos at = order.front().assembly();
                spawnDebug(level, "spot", "Kein freier Platz am Sammelplatz " + at.getX() + " " + at.getY() + " "
                        + at.getZ() + " (in Safezone: " + (ActiveSiege.get(level) != null
                        && ActiveSiege.get(level).isSafe(at.getX(), at.getZ())) + ")");
                continue; // somebody stands on every spot of this front's assembly point: maybe another front
            }
            Mob mob = createSiegeMob(level, type, spawnPos, coreLevel, random, now, role, true);
            if (mob == null) {
                spawnDebug(level, "create", "Mob konnte nicht erzeugt werden: " + type);
                continue;
            }
            if (!level.noCollision(mob) || level.containsAnyLiquid(mob.getBoundingBox())) {
                spawnDebug(level, "collision", "Sammelplatz blockiert (Kollision/Flüssigkeit) bei " + spawnPos.toShortString());
                mob.discard();
                continue;
            }
            if (role == null && mob instanceof Spider spider && random.nextDouble() < Config.SPIDER_RIDER_CHANCE.get()) {
                addRider(level, spider, coreLevel, random, now);
            }
            level.addFreshEntityWithPassengers(mob);
            aimAtCore(mob, target);
            SiegeAI.assign(mob, order.front(), now);
            for (Entity passenger : mob.getPassengers()) {
                if (passenger instanceof Mob rider) {
                    aimAtCore(rider, target);
                }
            }
            level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1.0, mob.getZ(), 30, 0.4, 0.8, 0.4, 0.2);
            SiegeCommander.noteOrdered(order);
            spawned++;
        }
        return spawned;
    }

    /** A zombie type for a worker (from the pool if possible). */
    private static EntityType<?> zombieType(List<EntityType<?>> pool, RandomSource random) {
        List<EntityType<?>> zombies = new ArrayList<>();
        for (EntityType<?> type : pool) {
            if (ZOMBIE_TYPES.contains(type)) {
                zombies.add(type);
            }
        }
        return zombies.isEmpty() ? EntityType.ZOMBIE : zombies.get(random.nextInt(zombies.size()));
    }

    /** The assembly point itself or a free spot right next to it (on the same natural ground). */
    @Nullable
    private static BlockPos assemblySpot(ServerLevel level, BlockPos assembly, RandomSource random) {
        ActiveSiege siege = ActiveSiege.get(level);
        List<BlockPos> spots = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = assembly.offset(dx, 0, dz);
                if (siege != null && siege.isSafe(pos.getX(), pos.getZ())) {
                    continue; // nothing spawns inside the safe zone, not even one block into it
                }
                if (isStandable(level, pos)) {
                    spots.add(pos);
                }
            }
        }
        if (spots.isEmpty()) {
            return null;
        }
        return spots.get(random.nextInt(spots.size()));
    }

    @Nullable
    private static Mob createSiegeMob(ServerLevel level, EntityType<?> type, BlockPos pos, int coreLevel,
                                      RandomSource random, long now) {
        return createSiegeMob(level, type, pos, coreLevel, random, now, null, false);
    }

    /** @param ordered with {@code commanded}: the role the commander ordered (null = an attacker) */
    @Nullable
    private static Mob createSiegeMob(ServerLevel level, EntityType<?> type, BlockPos pos, int coreLevel,
                                      RandomSource random, long now, @Nullable MobRoles.Role ordered,
                                      boolean commanded) {
        Entity entity = type.create(level);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            return null;
        }
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        prepareSiegeMob(mob, coreLevel);
        if (commanded) {
            // No vanilla jockeys: a worker riding a chicken could not work.
            mob.ejectPassengers();
            Entity vehicle = mob.getVehicle();
            if (vehicle != null) {
                mob.stopRiding();
                vehicle.discard();
            }
            MobRoles.applyOrdered(mob, ordered, coreLevel, random, now);
        } else {
            MobRoles.apply(mob, coreLevel, random, now);
        }
        return mob;
    }

    private static boolean airDrop(ServerLevel level, BlockPos core, int coreLevel, EntityType<?> type,
                                   CoreTargetEntity target, int minDistance, int maxDistance, RandomSource random,
                                   long now) {
        Mob mob = createSiegeMob(level, type, core, coreLevel, random, now, null, true);
        if (mob == null) {
            return false;
        }
        mob.ejectPassengers(); // e.g. a vanilla chicken jockey
        if (!SiegeAirDrop.launch(level, core, mob, minDistance, maxDistance, random)) {
            mob.discard();
            return false;
        }
        aimAtCore(mob, target);
        return true;
    }

    /** Spider jockey: a zombie (with a role) or a skeleton rides the spider up the walls. */
    private static void addRider(ServerLevel level, Spider spider, int coreLevel, RandomSource random, long now) {
        if (spider.isVehicle()) {
            return; // vanilla already made it a jockey
        }
        EntityType<?> type = random.nextBoolean() ? EntityType.ZOMBIE : EntityType.SKELETON;
        Mob rider = createSiegeMob(level, type, spider.blockPosition(), coreLevel, random, now);
        if (rider == null) {
            return;
        }
        rider.ejectPassengers();
        if (rider.isBaby() || !rider.startRiding(spider, true)) {
            rider.discard();
        }
    }

    private static void aimAtCore(Mob mob, CoreTargetEntity target) {
        mob.setTarget(target);
        ensureFollowRange(mob, target);
    }

    private static final Set<EntityType<?>> ZOMBIE_TYPES = Set.of(EntityType.ZOMBIE, EntityType.HUSK);
    private static final Set<EntityType<?>> SKELETON_TYPES = Set.of(EntityType.SKELETON, EntityType.STRAY, EntityType.BOGGED);

    /** Usually random; if the siege urgently needs a role, a type that can take it. */
    private static EntityType<?> pickType(List<EntityType<?>> pool, RandomSource random, long now) {
        Set<EntityType<?>> wanted = null;
        if (SiegeProgress.isNeeded(SiegeProgress.Need.FIRE_ARCHER, now) && MobRoles.count(MobRoles.Role.FIRE_ARCHER) == 0) {
            wanted = SKELETON_TYPES;
        } else if ((SiegeProgress.isNeeded(SiegeProgress.Need.BUILDER, now) && MobRoles.count(MobRoles.Role.BUILDER) == 0)
                || (SiegeProgress.isNeeded(SiegeProgress.Need.DIGGER, now)
                && MobRoles.count(MobRoles.Role.MINER) + MobRoles.count(MobRoles.Role.BREAKER) == 0)) {
            wanted = ZOMBIE_TYPES;
        }
        if (wanted != null) {
            List<EntityType<?>> matching = new ArrayList<>();
            for (EntityType<?> type : pool) {
                if (wanted.contains(type)) {
                    matching.add(type);
                }
            }
            if (!matching.isEmpty()) {
                return matching.get(random.nextInt(matching.size()));
            }
        }
        return pool.get(random.nextInt(pool.size()));
    }

    private static boolean insideWard(List<BlockPos> wards, int x, int z, int radius) {
        for (BlockPos ward : wards) {
            if (Math.abs(x - ward.getX()) <= radius && Math.abs(z - ward.getZ()) <= radius) { // square area
                return true;
            }
        }
        return false;
    }

    /**
     * The Core is underground (e.g. a cave base): below sea level, rock above it, far below the surface.
     * Only then may the siege use caves; a Core higher up is always attacked from the surface.
     */
    static boolean isUnderground(ServerLevel level, BlockPos core) {
        if (core.getY() >= level.getSeaLevel() - UNDERGROUND_BELOW_SEA) {
            return false;
        }
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, core.getX(), core.getZ());
        return top - core.getY() > UNDERGROUND_DEPTH && !level.canSeeSky(core.above());
    }

    private static final int UNDERGROUND_DEPTH = 12;
    /** A Core counts as underground only this far below sea level (sea level 63 -> below y 55). */
    private static final int UNDERGROUND_BELOW_SEA = 8;
    /** A mob this far below the surface (outside the core zone) is in a cave. */
    private static final int CAVE_DEPTH = 5;
    private static final long CAVE_GRACE_TICKS = 900;

    /**
     * True if the mob is deep in a cave (below the surface) while the Core is not underground. Siege mobs on a
     * front's way (e.g. a tunnel the miners dug) do not count.
     */
    private static boolean inCave(ServerLevel level, ActiveSiege siege, Mob mob, int zone) {
        if (siege.underground || zone == ActiveSiege.ZONE_CORE || mob.isPassenger() || PlayerHunt.isWorker(mob)) {
            return false;
        }
        BlockPos pos = mob.blockPosition();
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        if (pos.getY() >= top - CAVE_DEPTH || pos.getY() >= siege.corePos.getY() - 2) {
            return false; // on the surface, or not below the Core (then it is in some building, not in a cave)
        }
        SiegeAI.MobState state = SiegeAI.stateOf(mob);
        if (state == null) {
            return true;
        }
        if (state.routeIndex >= 0) {
            state.caveSince = -1;
            return false;
        }
        // Not at once: a mob knocked off a bridge lies below the "surface" (the bridge counts as one) for a moment
        // and gets the chance to climb back (it was recalled instantly before 0.7.15).
        long now = level.getGameTime();
        if (state.caveSince < 0) {
            state.caveSince = now;
        }
        return now - state.caveSince > CAVE_GRACE_TICKS;
    }
    /** Underground Core: mobs may spawn in caves and tunnels this close to the Core's height. */
    private static final int CAVE_BAND = 5;

    /**
     * Where a mob may spawn in this column. Normally only on the surface (never in caves: mobs there could not
     * get out and would rot there), and only if the surface is not far above the Core. If the Core itself is
     * underground (a cave base), also in caves at about the Core's height.
     */
    @Nullable
    private static BlockPos findStandingPos(ServerLevel level, int x, int z, int coreY, boolean underground,
                                            int elevation) {
        if (!level.isLoaded(new BlockPos(x, coreY, z))) {
            return null;
        }
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos surface = new BlockPos(x, top, z);
        // A Core high up: the ground below is fine (the mobs build ladders and stairs, spiders climb).
        int maxBelow = Math.max(24, elevation + 12);
        if (top <= coreY + 16 && top >= coreY - maxBelow && isStandable(level, surface)) {
            return surface;
        }
        if (underground) {
            for (int y = coreY + CAVE_BAND; y >= coreY - CAVE_BAND; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (isStandable(level, pos)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        return belowState.isFaceSturdy(level, below, Direction.UP)
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getFluidState(pos).isEmpty();
    }

    private static void prepareSiegeMob(Mob mob, int coreLevel) {
        mob.addTag(SIEGE_TAG);
        mob.setPersistenceRequired();
        addBonus(mob, Attributes.MAX_HEALTH, HEALTH_BONUS_ID, Config.MOB_HEALTH_BONUS_PER_LEVEL.get() * coreLevel);
        addBonus(mob, Attributes.ATTACK_DAMAGE, DAMAGE_BONUS_ID, Config.MOB_DAMAGE_BONUS_PER_LEVEL.get() * coreLevel);
        mob.setHealth(mob.getMaxHealth());
        // No fire resistance: lava and fire hurt siege mobs. Sun protection is done in SiegeEvents.
    }

    private static void addBonus(Mob mob, Holder<Attribute> attribute, ResourceLocation id, double amount) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null && amount > 0 && instance.getModifier(id) == null) {
            instance.addPermanentModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    // ------------------------------------------------------------------
    // Activation, removal, destruction
    // ------------------------------------------------------------------

    /** Stops the siege for this core: removes target entity, chunk tickets and the world record. */
    public static void stopSiege(ServerLevel level, BlockPos pos, CoreBlockEntity core) {
        core.removeTarget();
        if (core.getLoadedChunkRadius() >= 0) {
            ChunkLoading.update(level, pos, core.getLoadedChunkRadius(), -1);
            core.setLoadedChunkRadius(-1);
        }
        CoreWorldData.get(level.getServer()).clearIfMatches(level.dimension(), pos);
        ActiveSiege.clear();
        SiegeAI.clear();
    }

    /** Removes all mobs spawned by the siege around the Core (the incursion is over). */
    public static void clearSiegeMobs(ServerLevel level, BlockPos pos, int coreLevel) {
        int radius = Config.siegeRadiusBlocks(Math.max(1, coreLevel)) + 16;
        AABB area = new AABB(pos).inflate(radius, 96, radius);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area, m -> m.getTags().contains(SIEGE_TAG))) {
            level.sendParticles(ParticleTypes.POOF, mob.getX(), mob.getY() + 0.5, mob.getZ(), 10, 0.3, 0.5, 0.3, 0.02);
            mob.discard();
        }
    }

    /** The attackers brought the Core's health to 0: remove the block, which triggers the big explosion. */
    public static void destroyCore(ServerLevel level, BlockPos pos, CoreBlockEntity core) {
        level.removeBlock(pos, false);
    }

    /** Defusing finished: the Core falls back to level 0 without an explosion. */
    public static void defuse(ServerLevel level, BlockPos pos, CoreBlockEntity core) {
        stopSiege(level, pos, core);
        clearSiegeMobs(level, pos, core.getCoreLevel());
        core.setCoreLevel(0);
        core.resetShields();
        core.setHealth(0);
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof CoreBlock) {
            level.setBlock(pos, state.setValue(CoreBlock.ACTIVE, false), Block.UPDATE_ALL);
        }
        level.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.0F, 0.8F);
        level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable("message.incursion.core.defused",
                pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), false);
    }
}
