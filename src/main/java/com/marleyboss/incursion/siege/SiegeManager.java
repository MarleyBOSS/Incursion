package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlock;
import com.marleyboss.incursion.block.CoreBlockEntity;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** All siege logic that runs once per second for the active Core. */
public final class SiegeManager {
    /** Scoreboard tag on every mob spawned by the siege. */
    public static final String SIEGE_TAG = "incursion_siege";

    private static final ResourceLocation FOLLOW_RANGE_ID = id("siege_follow_range");
    private static final ResourceLocation HEALTH_BONUS_ID = id("siege_health_bonus");
    private static final ResourceLocation DAMAGE_BONUS_ID = id("siege_damage_bonus");
    private static final double MAX_FOLLOW_RANGE = 128.0;

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
        int coreZone = Config.coreZoneRadius(coreLevel);
        int siegeRadius = Config.siegeRadiusBlocks(coreLevel);
        int aggroRange = Config.PLAYER_AGGRO_RANGE.get();
        ActiveSiege.update(level, pos, target, coreZone, siegeRadius, aggroRange);
        ActiveSiege siege = ActiveSiege.get(level);
        if (siege == null) {
            return;
        }

        // 5. Point every hostile mob in the area at the Core (or at a nearby player).
        AABB area = new AABB(pos).inflate(siegeRadius, 64, siegeRadius);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, area, Entity::isAlive);
        int siegeMobs = 0;
        for (Mob mob : mobs) {
            if (mob.getTags().contains(SIEGE_TAG)) {
                siegeMobs++;
            }
            if (!SiegeRules.isAttacker(mob)) {
                continue;
            }
            int zone = siege.zoneOf(mob);
            if (zone == ActiveSiege.ZONE_OUTSIDE) {
                continue;
            }
            LivingEntity current = mob.getTarget();
            boolean retarget;
            if (zone == ActiveSiege.ZONE_CORE) {
                retarget = current != target;
            } else {
                retarget = current == null || !current.isAlive()
                        || (current instanceof Player player && player.distanceToSqr(mob) > (double) aggroRange * aggroRange);
            }
            if (retarget) {
                mob.setTarget(target);
            }
            if (mob.getTarget() == target) {
                ensureFollowRange(mob, target);
            }
        }
        core.setSiegeMobCount(siegeMobs);

        // 6. Spawn new attackers.
        int interval = Config.SPAWN_INTERVAL_SECONDS.get();
        if ((tickCounter / 20) % interval == 0 && level.getDifficulty() != Difficulty.PEACEFUL) {
            int free = Config.maxSiegeMobs(coreLevel) - siegeMobs;
            int toSpawn = Math.min(free, 1 + coreLevel / 4);
            if (toSpawn > 0) {
                int spawned = spawnAttackers(level, pos, coreLevel, toSpawn, target);
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
                                      CoreTargetEntity target) {
        List<EntityType<?>> pool = SiegeRules.spawnPool(coreLevel);
        if (pool.isEmpty()) {
            return 0;
        }
        RandomSource random = level.getRandom();
        int minDistance = Config.coreZoneRadius(coreLevel) + 6;
        int maxDistance = Math.min(Config.SPAWN_MAX_DISTANCE.get(), Config.siegeRadiusBlocks(coreLevel) - 4);
        maxDistance = Math.max(maxDistance, minDistance + 4);

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            for (int attempt = 0; attempt < 8; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
                int x = core.getX() + Mth.floor(Math.cos(angle) * distance);
                int z = core.getZ() + Mth.floor(Math.sin(angle) * distance);
                BlockPos spawnPos = findStandingPos(level, x, z, core.getY());
                if (spawnPos == null) {
                    continue;
                }
                EntityType<?> type = pool.get(random.nextInt(pool.size()));
                Entity entity = type.create(level);
                if (!(entity instanceof Mob mob)) {
                    if (entity != null) {
                        entity.discard();
                    }
                    continue;
                }
                mob.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                        random.nextFloat() * 360.0F, 0.0F);
                if (!level.noCollision(mob) || level.containsAnyLiquid(mob.getBoundingBox())) {
                    mob.discard();
                    continue;
                }
                EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(spawnPos), MobSpawnType.EVENT, null);
                prepareSiegeMob(mob, coreLevel);
                ZombieRoles.apply(mob, coreLevel, random);
                level.addFreshEntityWithPassengers(mob);
                mob.setTarget(target);
                ensureFollowRange(mob, target);
                level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1.0, mob.getZ(),
                        30, 0.4, 0.8, 0.4, 0.2);
                spawned++;
                break;
            }
        }
        return spawned;
    }

    @Nullable
    private static BlockPos findStandingPos(ServerLevel level, int x, int z, int coreY) {
        for (int y = coreY + 12; y >= coreY - 12; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isStandable(level, pos)) {
                return pos;
            }
        }
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos surface = new BlockPos(x, top, z);
        if (Math.abs(top - coreY) <= 40 && isStandable(level, surface)) {
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
