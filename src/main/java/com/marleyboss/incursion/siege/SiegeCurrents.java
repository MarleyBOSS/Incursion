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
import org.jetbrains.annotations.Nullable;

/**
 * Siege mobs in flowing water. In vanilla the current pushes every mob with 0.014 per tick, while a zombie swims
 * with only about 0.005 per tick, so it is swept away. A siege mob that has an order to move (see
 * {@link SiegeAI#drive}) therefore does not feel the current: while it is in flowing water, its horizontal
 * movement is set directly to a slow swim towards its goal ({@code currentHeadwayPercent} of its normal swimming
 * speed), in any direction. A mob that holds its place (working, waiting) is held in the current. Flowing water
 * stays a good defence for players: it slows the siege down a lot, but it no longer stops it.
 */
final class SiegeCurrents {
    /**
     * Normal swimming speed in still water per tick: thrust (0.02 x movement speed) divided by the water drag
     * (1 - 0.8) = 4 x 0.02 x movement speed (LivingEntity.travel in water).
     */
    private static final double SWIM_SPEED_FACTOR = 0.08;
    /** Closer to the goal than this (blocks): the mob stays where it is. */
    private static final double ARRIVED = 0.15;

    private SiegeCurrents() {
    }

    /**
     * Called every tick for a mob that is driven by the siege AI. If it is in flowing water, its horizontal movement
     * is set to a slow swim towards {@code target} (null: hold the place). Returns true if it did that; still water
     * and falling water are left to vanilla.
     */
    static boolean swim(ServerLevel level, Mob mob, @Nullable Vec3 target) {
        if (!mob.isInWater()) {
            return false;
        }
        Vec3 flow = flowAround(level, mob);
        if (flow.x * flow.x + flow.z * flow.z < 1.0E-6) {
            return false; // no current (or only falling water: the jump control keeps the mob up)
        }
        double speed = SWIM_SPEED_FACTOR * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)
                * Config.CURRENT_HEADWAY_PERCENT.get() / 100.0;
        double vx = 0.0;
        double vz = 0.0;
        if (target != null) {
            double dx = target.x - mob.getX();
            double dz = target.z - mob.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > ARRIVED) {
                vx = dx / length * speed;
                vz = dz / length * speed;
            }
        }
        Vec3 motion = mob.getDeltaMovement();
        mob.setDeltaMovement(vx, motion.y, vz);
        return true;
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
