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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
import java.util.List;
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
        ActiveSiege.update(level, pos, target, coreZone, siegeRadius, aggroRange, coreLevel,
                core.isShowBorders(), core.isShowAi(), elevation);
        ActiveSiege siege = ActiveSiege.get(level);
        if (siege == null) {
            return;
        }

        // 5. Point every hostile mob in the area at the Core or at a player (depending on its role).
        AABB area = new AABB(pos).inflate(siegeRadius, 64, siegeRadius).expandTowards(0, -elevation, 0);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, area, Entity::isAlive);
        List<Mob> fireArchers = new ArrayList<>();
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
            if (MobRoles.of(mob) == MobRoles.Role.FIRE_ARCHER) {
                fireArchers.add(mob);
            }
            Targeting.update(mob, siege, zone);
            if (mob.getTarget() == target) {
                ensureFollowRange(mob, target);
            }
        }
        core.setSiegeMobCount(siegeMobs);

        // 6. Fire archers ignite TNT placed by sappers.
        SiegeExplosives.tickTnt(level, fireArchers, now);

        // 7. Show where mobs cannot spawn (and the AI view), if switched on in the Core GUI.
        List<BlockPos> wards = WardBlockEntity.wardsNear(level, pos, siegeRadius);
        int minDistance = Config.coreZoneRadius(coreLevel) + 6;
        if (siege.showBorders) {
            SpawnRings.drawCore(level, pos, minDistance);
            for (BlockPos ward : wards) {
                SpawnRings.drawWard(level, ward, Config.WARD_RADIUS.get());
            }
        }
        if (siege.showDebug) {
            SiegeDebug.drawRoutes(level, siege, now);
        }

        // 8. Spawn new attackers. If the siege is stuck, reinforcements may exceed the normal limit.
        int interval = Config.SPAWN_INTERVAL_SECONDS.get();
        boolean stalled = SiegeProgress.isStalled(now);
        if (stalled && !stallAnnounced) {
            stallAnnounced = true;
            level.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.incursion.siege.reinforcements").withStyle(ChatFormatting.GOLD), false);
        } else if (!stalled) {
            stallAnnounced = false;
        }
        if ((tickCounter / 20) % interval == 0 && level.getDifficulty() != Difficulty.PEACEFUL) {
            int max = Config.maxSiegeMobs(coreLevel) + wards.size() * Config.WARD_EXTRA_MOBS.get();
            if (stalled) {
                max += max * Config.STALL_EXTRA_MOBS_PERCENT.get() / 100;
                SiegeProgress.need(SiegeProgress.Need.DIGGER, now);
                SiegeProgress.need(SiegeProgress.Need.BUILDER, now);
            }
            int free = max - siegeMobs;
            int toSpawn = Math.min(free, 1 + coreLevel / 4 + (stalled ? 1 : 0));
            if (toSpawn > 0) {
                boolean airDrops = stalled || elevation >= Config.AIR_DROP_ELEVATION.get();
                int spawned = spawnAttackers(level, pos, coreLevel, toSpawn, target, wards, now, airDrops);
                core.setSiegeMobCount(siegeMobs + spawned);
            }
        }
    }

    /** Lets the mob "see" the Core from far away so it can path towards it. */
    private static void ensureFollowRange(Mob mob, CoreTargetEntity target) {
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

    private static int spawnAttackers(ServerLevel level, BlockPos core, int coreLevel, int count,
                                      CoreTargetEntity target, List<BlockPos> wards, long now, boolean airDrops) {
        List<EntityType<?>> pool = SiegeRules.spawnPool(coreLevel);
        if (pool.isEmpty()) {
            return 0;
        }
        RandomSource random = level.getRandom();
        int minDistance = Config.coreZoneRadius(coreLevel) + 6;
        int maxDistance = Math.min(Config.SPAWN_MAX_DISTANCE.get(), Config.siegeRadiusBlocks(coreLevel) - 4);
        maxDistance = Math.max(maxDistance, minDistance + 4);
        int wardRadius = Config.WARD_RADIUS.get();
        // With wards, mobs may also spawn a bit farther out (otherwise a big base leaves no room).
        int wardMaxDistance = Math.max(maxDistance, Config.siegeRadiusBlocks(coreLevel) - 4);
        boolean airAllowed = SiegeAirDrop.isEnabled();

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            EntityType<?> type = pickType(pool, random, now);
            boolean carry = airAllowed && SiegeAirDrop.canCarry(type);
            // High Core or stuck siege: some attackers come by air, right next to the Core.
            if (carry && airDrops && random.nextDouble() < Config.AIR_DROP_CHANCE.get()) {
                if (airDrop(level, core, coreLevel, type, target, minDistance, maxDistance, random, now)) {
                    spawned++;
                    continue;
                }
            }
            boolean done = false;
            for (int attempt = 0; attempt < 12 && !done; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                int limit = attempt < 6 ? maxDistance : wardMaxDistance;
                double distance = minDistance + random.nextDouble() * (limit - minDistance);
                int x = core.getX() + Mth.floor(Math.cos(angle) * distance);
                int z = core.getZ() + Mth.floor(Math.sin(angle) * distance);
                if (insideWard(wards, x, z, wardRadius)) {
                    continue;
                }
                BlockPos spawnPos = findStandingPos(level, x, z, core.getY());
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

    @Nullable
    private static Mob createSiegeMob(ServerLevel level, EntityType<?> type, BlockPos pos, int coreLevel,
                                      RandomSource random, long now) {
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
        MobRoles.apply(mob, coreLevel, random, now);
        return mob;
    }

    private static boolean airDrop(ServerLevel level, BlockPos core, int coreLevel, EntityType<?> type,
                                   CoreTargetEntity target, int minDistance, int maxDistance, RandomSource random,
                                   long now) {
        Mob mob = createSiegeMob(level, type, core, coreLevel, random, now);
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
            double dx = x + 0.5 - (ward.getX() + 0.5);
            double dz = z + 0.5 - (ward.getZ() + 0.5);
            if (dx * dx + dz * dz <= (double) radius * radius) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static BlockPos findStandingPos(ServerLevel level, int x, int z, int coreY) {
        if (!level.isLoaded(new BlockPos(x, coreY, z))) {
            return null;
        }
        // Prefer the surface, so mobs do not start inside caves.
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos surface = new BlockPos(x, top, z);
        if (Math.abs(top - coreY) <= 24 && isStandable(level, surface)) {
            return surface;
        }
        for (int y = coreY + 12; y >= coreY - 12; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isStandable(level, pos)) {
                return pos;
            }
        }
        // The Core is far above (or below) the ground: spawn on the ground anyway. The mobs build their way
        // up (ladders, stairs), spiders climb, and the rest comes by air.
        if (isStandable(level, surface)) {
            return surface;
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
        if (Config.SIEGE_MOBS_FIRE_RESISTANT.get()) {
            mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION,
                    0, false, false));
        }
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
