package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Siege mobs in flowing water: slower, and they resist part of the current, so they make slow headway against it
 * instead of being carried away helplessly. Flowing water stays a good defence for players (it slows the siege
 * down a lot), it just no longer stops swimming mobs completely. Both strengths are in the config.
 */
final class SiegeCurrents {
    /** The push vanilla gives every non-player entity in flowing water, per tick. */
    private static final double VANILLA_PUSH = 0.014;

    private SiegeCurrents() {
    }

    /** Called every tick for a tracked mob that is in water. Still water (no current) is left alone. */
    static void tick(ServerLevel level, Mob mob) {
        double resistance = Config.CURRENT_RESISTANCE.get();
        double slowdown = Config.CURRENT_SLOWDOWN.get();
        if (resistance <= 0.0 && slowdown >= 1.0) {
            return;
        }
        Vec3 flow = flowAround(level, mob);
        if (flow.lengthSqr() < 1.0E-6) {
            return;
        }
        Vec3 counter = flow.normalize().scale(VANILLA_PUSH * resistance);
        Vec3 motion = mob.getDeltaMovement();
        mob.setDeltaMovement(motion.x * slowdown - counter.x, motion.y - counter.y, motion.z * slowdown - counter.z);
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
