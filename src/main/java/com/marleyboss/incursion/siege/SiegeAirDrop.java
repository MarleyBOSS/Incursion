package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Air drops: a phantom carries a siege mob to a free spot right next to the Core and drops it there.
 * Used when the Core is far above the ground, when the siege is stuck, and when no spawn spot on the ground
 * is found. The phantom is only a carrier: it has no AI of its own, flies a fixed course (up and over
 * obstacles), drops its passenger and flies away.
 */
public final class SiegeAirDrop {
    /** Scoreboard tag on carrier phantoms (they are no attackers themselves). */
    public static final String CARRIER_TAG = "incursion_carrier";
    private static final String DROP_KEY = "incursion_drop";

    private static final double CRUISE_SPEED = 0.55;
    private static final double DESCEND_SPEED = 0.35;
    private static final double LEAVE_SPEED = 0.45;
    /** Cruising height above the drop spot. */
    private static final int CRUISE_HEIGHT = 8;
    /** This much free air is needed above a drop spot (cruising height + phantom + passenger). */
    private static final int FREE_ABOVE = CRUISE_HEIGHT + 4;
    private static final long MAX_FLIGHT_TICKS = 20 * 45;
    private static final long LEAVE_TICKS = 60;

    private static final class Flight {
        Vec3 pos;
        final long start;
        boolean leaving;
        long leaveStart;
        Vec3 heading = new Vec3(1, 0, 0);

        Flight(Vec3 pos, long start) {
            this.pos = pos;
            this.start = start;
        }
    }

    private static final Map<Phantom, Flight> FLIGHTS = new WeakHashMap<>();

    private SiegeAirDrop() {
    }

    // ------------------------------------------------------------------
    // When to use air drops
    // ------------------------------------------------------------------

    /**
     * How far the Core is above the ground around it: the median ground height at eight points on a ring
     * around the Core (where mobs spawn) compared to the Core's height.
     */
    public static int elevation(ServerLevel level, BlockPos core, int ringRadius) {
        int[] heights = new int[8];
        int count = 0;
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0 * i / 8.0;
            int x = core.getX() + Mth.floor(Math.cos(angle) * ringRadius);
            int z = core.getZ() + Mth.floor(Math.sin(angle) * ringRadius);
            if (!level.isLoaded(new BlockPos(x, core.getY(), z))) {
                continue;
            }
            heights[count++] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        if (count == 0) {
            return 0;
        }
        int[] sorted = Arrays.copyOf(heights, count);
        Arrays.sort(sorted);
        return core.getY() - sorted[count / 2];
    }

    /** Small enough to be carried (zombies, skeletons, creepers ...). */
    public static boolean canCarry(EntityType<?> type) {
        return type != EntityType.PHANTOM && type.getWidth() <= 1.0F && type.getHeight() <= 2.2F;
    }

    // ------------------------------------------------------------------
    // Spawning
    // ------------------------------------------------------------------

    /**
     * Spawns a carrier phantom with {@code passenger} (already prepared, not yet in the world) somewhere on the
     * spawn ring, high up. Returns false (and spawns nothing) if there is no free spot next to the Core.
     */
    public static boolean launch(ServerLevel level, BlockPos core, Mob passenger, int minDistance, int maxDistance,
                                 RandomSource random) {
        BlockPos drop = findDropSpot(level, core, random);
        if (drop == null) {
            return false;
        }
        Phantom phantom = EntityType.PHANTOM.create(level);
        if (phantom == null) {
            return false;
        }
        ActiveSiege siege = ActiveSiege.get(level);
        double angle = 0.0;
        double x = 0.0;
        double z = 0.0;
        boolean found = false;
        for (int attempt = 0; attempt < 12 && !found; attempt++) {
            angle = random.nextDouble() * Math.PI * 2.0;
            double distance = minDistance + random.nextDouble() * Math.max(1, maxDistance - minDistance);
            x = core.getX() + 0.5 + Math.cos(angle) * distance;
            z = core.getZ() + 0.5 + Math.sin(angle) * distance;
            found = siege == null || !siege.isSafe(Mth.floor(x), Mth.floor(z)); // starts outside the safe zone
        }
        if (!found) {
            phantom.discard();
            return false;
        }
        if (!level.isLoaded(BlockPos.containing(x, core.getY(), z))) {
            phantom.discard();
            return false;
        }
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
        double y = Math.max(drop.getY() + CRUISE_HEIGHT, ground + 6);
        y = Math.min(y, level.getMaxBuildHeight() + 8);
        phantom.moveTo(x, y, z, (float) (angle * Mth.RAD_TO_DEG) + 90.0F, 0.0F);
        phantom.addTag(CARRIER_TAG);
        phantom.setPersistenceRequired();
        phantom.getPersistentData().putLong(DROP_KEY, drop.asLong());
        stripGoals(phantom);

        passenger.moveTo(x, y, z, phantom.getYRot(), 0.0F);
        if (!passenger.startRiding(phantom, true)) {
            phantom.discard();
            return false;
        }
        level.addFreshEntityWithPassengers(phantom);
        level.sendParticles(ParticleTypes.PORTAL, x, y, z, 30, 0.6, 0.6, 0.6, 0.2);
        return true;
    }

    private static void stripGoals(Mob mob) {
        List<Goal> goals = new ArrayList<>();
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            goals.add(wrapped.getGoal());
        }
        goals.forEach(mob.goalSelector::removeGoal);
        List<Goal> targets = new ArrayList<>();
        for (WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
            targets.add(wrapped.getGoal());
        }
        targets.forEach(mob.targetSelector::removeGoal);
    }

    /**
     * A free spot as close to the Core as possible: solid floor, room for a mob, and open air above it, so the
     * phantom can come straight down.
     */
    @Nullable
    static BlockPos findDropSpot(ServerLevel level, BlockPos core, RandomSource random) {
        BlockPos best = findDropSpot(level, core, random, 6, 6, 8);
        return best != null ? best : findDropSpot(level, core, random, 14, 14, 16);
    }

    @Nullable
    private static BlockPos findDropSpot(ServerLevel level, BlockPos core, RandomSource random, int radius, int up,
                                         int down) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = up; dy >= -down; dy--) {
                    BlockPos pos = core.offset(dx, dy, dz);
                    if (pos.equals(core) || !SiegeArchers.isStandable(level, pos) || !freeAbove(level, pos)) {
                        continue;
                    }
                    double score = pos.distSqr(core) + random.nextDouble() * 3.0;
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                    break; // only the highest floor in this column can be reached from the sky
                }
            }
        }
        return best;
    }

    private static boolean freeAbove(ServerLevel level, BlockPos pos) {
        int top = Math.min(pos.getY() + FREE_ABOVE, level.getMaxBuildHeight() - 1);
        for (int y = pos.getY() + 2; y <= top; y++) {
            if (!RoutePlanner.isPassable(level, new BlockPos(pos.getX(), y, pos.getZ()))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Flight
    // ------------------------------------------------------------------

    public static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof Phantom phantom && phantom.level() instanceof ServerLevel level
                && phantom.getTags().contains(CARRIER_TAG) && phantom.isAlive()) {
            tick(level, phantom);
        }
    }

    private static void tick(ServerLevel level, Phantom phantom) {
        long now = level.getGameTime();
        Flight flight = FLIGHTS.computeIfAbsent(phantom, p -> new Flight(p.position(), now));
        phantom.clearFire();
        Entity passenger = phantom.getFirstPassenger();
        if (passenger != null) {
            passenger.clearFire();
        }
        ActiveSiege siege = ActiveSiege.get(level);
        BlockPos drop = phantom.getPersistentData().contains(DROP_KEY)
                ? BlockPos.of(phantom.getPersistentData().getLong(DROP_KEY)) : null;

        if (!flight.leaving && (passenger == null || siege == null || drop == null
                || now - flight.start > MAX_FLIGHT_TICKS)) {
            if (passenger != null) {
                passenger.stopRiding(); // could not make it: drop it here (it gets slow falling)
                slowFall(passenger);
            }
            leave(flight, now);
        }

        Vec3 next;
        if (flight.leaving) {
            if (now - flight.leaveStart > LEAVE_TICKS) {
                FLIGHTS.remove(phantom);
                level.sendParticles(ParticleTypes.PORTAL, phantom.getX(), phantom.getY(), phantom.getZ(), 20, 0.5, 0.5, 0.5, 0.2);
                phantom.discard();
                return;
            }
            next = flight.pos.add(flight.heading.x * LEAVE_SPEED, 0.3, flight.heading.z * LEAVE_SPEED);
        } else {
            Vec3 above = new Vec3(drop.getX() + 0.5, Math.max(flight.pos.y, drop.getY() + CRUISE_HEIGHT),
                    drop.getZ() + 0.5);
            double hx = above.x - flight.pos.x;
            double hz = above.z - flight.pos.z;
            double horizontal = Math.sqrt(hx * hx + hz * hz);
            if (horizontal > 0.25) {
                // Cruise to the point above the drop spot (and up to cruising height on the way).
                double step = Math.min(CRUISE_SPEED, horizontal);
                double rise = Mth.clamp(drop.getY() + CRUISE_HEIGHT - flight.pos.y, -0.15, 0.3);
                next = flight.pos.add(hx / horizontal * step, rise, hz / horizontal * step);
                flight.heading = new Vec3(hx / horizontal, 0, hz / horizontal);
            } else {
                // Straight down onto the spot, then let go.
                double low = drop.getY() + 1.1;
                if (flight.pos.y - low <= 0.1) {
                    release(level, passenger, drop);
                    leave(flight, now);
                    next = flight.pos;
                } else {
                    next = new Vec3(above.x, Math.max(low, flight.pos.y - DESCEND_SPEED), above.z);
                }
            }
            if (!isFree(level, phantom, passenger, next.subtract(flight.pos))) {
                Vec3 up = flight.pos.add(0, 0.4, 0);
                next = isFree(level, phantom, passenger, up.subtract(flight.pos)) ? up : flight.pos;
            }
        }
        Vec3 motion = next.subtract(flight.pos);
        flight.pos = next;
        phantom.setDeltaMovement(motion);
        phantom.setPos(next.x, next.y, next.z);
        if (motion.horizontalDistanceSqr() > 1.0E-4) {
            float yaw = (float) (Mth.atan2(motion.z, motion.x) * Mth.RAD_TO_DEG) - 90.0F;
            phantom.setYRot(yaw);
            phantom.setYBodyRot(yaw);
            phantom.setYHeadRot(yaw);
        }
    }

    private static boolean isFree(ServerLevel level, Phantom phantom, @Nullable Entity passenger, Vec3 delta) {
        AABB box = phantom.getBoundingBox().move(delta);
        if (!level.noCollision(phantom, box)) {
            return false;
        }
        return passenger == null || level.noCollision(passenger, passenger.getBoundingBox().move(delta));
    }

    private static void release(ServerLevel level, @Nullable Entity passenger, BlockPos drop) {
        if (passenger == null) {
            return;
        }
        passenger.stopRiding();
        passenger.setDeltaMovement(Vec3.ZERO);
        slowFall(passenger); // lets go right above the spot and floats down onto it (no teleport)
        level.sendParticles(ParticleTypes.CLOUD, drop.getX() + 0.5, drop.getY() + 0.2, drop.getZ() + 0.5, 8, 0.3, 0.1, 0.3, 0.02);
    }

    private static void slowFall(Entity passenger) {
        if (passenger instanceof net.minecraft.world.entity.LivingEntity living) {
            living.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.SLOW_FALLING, 100, 0, false, false));
        }
    }

    private static void leave(Flight flight, long now) {
        flight.leaving = true;
        flight.leaveStart = now;
    }

    public static boolean isEnabled() {
        return Config.AIR_DROPS.get();
    }

    public static void clear() {
        FLIGHTS.clear();
    }
}
