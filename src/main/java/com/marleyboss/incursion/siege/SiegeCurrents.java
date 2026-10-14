package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Siege mobs in flowing water. In vanilla the current pushes an entity with 0.014 per tick, while a zombie swims
 * with only about 0.005 per tick: it can never get against the current. Here the push is cancelled down to a
 * fraction of the mob's own swimming thrust, so it makes slow but steady headway upstream (and holds its place
 * instead of being swept away). The fraction is the config {@code currentHeadwayPercent}: the mob swims upstream
 * at that percentage of its normal swimming speed. Flowing water is still a good defence for players.
 */
final class SiegeCurrents {
    /** The push vanilla gives every non-player entity in flowing water, per tick (the flow direction is normalised). */
    private static final double VANILLA_PUSH = 0.014;
    /** A swimming mob's thrust per tick is this times its movement speed (LivingEntity.travel in water). */
    private static final double WATER_THRUST = 0.02;
    /** Water drag per tick (the push of one tick is scaled by this before the next tick). */
    private static final double WATER_DRAG = 0.8;

    private SiegeCurrents() {
    }

    /** Called every tick for a tracked mob that is in water. Still water (no current) is left alone. */
    static void tick(ServerLevel level, Mob mob) {
        Vec3 flow = flowAround(level, mob);
        double horizontal = Math.sqrt(flow.x * flow.x + flow.z * flow.z);
        if (horizontal < 1.0E-3) {
            return; // no current (or only falling water: the jump control keeps the mob up)
        }
        // The push along the flow, as vanilla applies it (3D direction normalised, so only its horizontal share).
        double push = VANILLA_PUSH * horizontal / flow.length();
        // What the mob can swim against: its thrust. At x percent headway the push left over is (1 - x) of the thrust.
        double thrust = WATER_THRUST * mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        double headway = Config.CURRENT_HEADWAY_PERCENT.get() / 100.0;
        double cancel = Math.max(0.0, push - (1.0 - headway) * thrust) * WATER_DRAG;
        if (cancel <= 0.0) {
            return;
        }
        Vec3 motion = mob.getDeltaMovement();
        mob.setDeltaMovement(motion.x - flow.x / horizontal * cancel, motion.y, motion.z - flow.z / horizontal * cancel);
    }

    /** The water current at the mob (all water cells it overlaps, like the vanilla push). */
    private static Vec3 flowAround(ServerLevel level, Mob mob) {
        AABB box = mob.getBoundingBox().deflate(0.001);
        Vec3 flow = Vec3.ZERO;
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    FluidState fluid = level.getFluidState(pos);
                    if (fluid.is(FluidTags.WATER)) {
                        flow = flow.add(fluid.getFlow(level, pos));
                    }
                }
            }
        }
        return flow;
    }

    /**
     * Before 0.7.7 siege mobs got infinite, invisible fire resistance (so lava could not hurt them). Mobs that
     * still carry it from an older save lose it.
     */
    static void dropOldFireResistance(Mob mob) {
        MobEffectInstance effect = mob.getEffect(MobEffects.FIRE_RESISTANCE);
        if (effect != null && effect.isInfiniteDuration() && !effect.isVisible()
                && mob.getTags().contains(SiegeManager.SIEGE_TAG)) {
            mob.removeEffect(MobEffects.FIRE_RESISTANCE);
        }
    }
}
