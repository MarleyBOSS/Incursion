package com.marleyboss.incursion.siege;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Line of sight and arrow aiming against the Core <b>block</b>.
 * <p>
 * Vanilla line of sight looks at the eyes of the invisible target entity, which sit above the Core. As soon as
 * the block on top of the Core is solid, vanilla mobs believe they can never see the Core. These checks look at
 * the Core block itself instead: a mob can hit the Core if a straight line from its eyes reaches any exposed face.
 */
public final class SiegeAim {
    /** Points just inside each face of the Core (offset from the centre). */
    private static final double FACE_OFFSET = 0.45;
    private static final double GRAVITY = 0.05;
    private static final double DRAG = 0.99;

    private SiegeAim() {
    }

    /**
     * A point on the Core that can be seen from {@code eyes} (centre first, then the faces turned towards
     * the viewer), or null if every line is blocked.
     */
    @Nullable
    public static Vec3 visibleCorePoint(Level level, Vec3 eyes, BlockPos core, Entity viewer) {
        Vec3 center = Vec3.atCenterOf(core);
        if (reaches(level, eyes, center, core, viewer)) {
            return center;
        }
        Vec3 toViewer = eyes.subtract(center);
        for (Direction direction : Direction.values()) {
            double facing = toViewer.x * direction.getStepX() + toViewer.y * direction.getStepY()
                    + toViewer.z * direction.getStepZ();
            if (facing <= 0.0) {
                continue; // this face looks away from the viewer
            }
            Vec3 point = center.add(direction.getStepX() * FACE_OFFSET, direction.getStepY() * FACE_OFFSET,
                    direction.getStepZ() * FACE_OFFSET);
            if (reaches(level, eyes, point, core, viewer)) {
                return point;
            }
        }
        return null;
    }

    private static boolean reaches(Level level, Vec3 from, Vec3 to, BlockPos core, Entity viewer) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.BLOCK && (hit.getBlockPos().equals(core)
                || com.marleyboss.incursion.block.CoreEvolution.isStructure(level.getBlockState(hit.getBlockPos())));
    }

    /** Distance from a point to the closest point of the Core block. */
    public static double distanceToCore(Vec3 point, BlockPos core) {
        AABB box = new AABB(core);
        double dx = Math.max(0.0, Math.max(box.minX - point.x, point.x - box.maxX));
        double dy = Math.max(0.0, Math.max(box.minY - point.y, point.y - box.maxY));
        double dz = Math.max(0.0, Math.max(box.minZ - point.z, point.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Is there a straight line from {@code eyes} to this block (the block itself may be hit)? */
    public static boolean canSeeBlock(Level level, Vec3 eyes, BlockPos pos, Entity viewer) {
        BlockHitResult hit = level.clip(new ClipContext(eyes, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)
                || com.marleyboss.incursion.block.CoreEvolution.isStructure(level.getBlockState(hit.getBlockPos()));
    }

    // ------------------------------------------------------------------
    // Arrows
    // ------------------------------------------------------------------

    /**
     * Launch direction for an arrow (arrow physics: drag 0.99, gravity 0.05 per tick) so that it flies from
     * {@code from} through {@code to}. Null if the target is out of range.
     */
    @Nullable
    public static Vec3 solve(Vec3 from, Vec3 to, double speed) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = to.y - from.y;
        if (horizontal < 0.3) {
            return new Vec3(0.0, Math.signum(dy == 0 ? -1 : dy), 0.0);
        }
        double low = -1.45;
        double high = 1.5;
        for (int i = 0; i < 26; i++) {
            double mid = (low + high) * 0.5;
            double height = heightAt(mid, horizontal, speed);
            if (Double.isNaN(height) || height < dy) {
                low = mid;
            } else {
                high = mid;
            }
        }
        double angle = (low + high) * 0.5;
        double height = heightAt(angle, horizontal, speed);
        if (Double.isNaN(height) || Math.abs(height - dy) > 0.35) {
            return null;
        }
        double cos = Math.cos(angle);
        return new Vec3(dx / horizontal * cos, Math.sin(angle), dz / horizontal * cos);
    }

    /** Height of the arrow when it has travelled {@code distance} blocks horizontally (NaN if it falls short). */
    private static double heightAt(double angle, double distance, double speed) {
        double vh = Math.cos(angle) * speed;
        double vy = Math.sin(angle) * speed;
        double x = 0.0;
        double y = 0.0;
        for (int tick = 0; tick < 120; tick++) {
            double nx = x + vh;
            double ny = y + vy;
            if (nx >= distance) {
                double f = (distance - x) / (nx - x);
                return y + (ny - y) * f;
            }
            x = nx;
            y = ny;
            vh *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (vh < 0.01) {
                break;
            }
        }
        return Double.NaN;
    }

    /**
     * Follows the arrow's flight tick by tick. True if the first block it touches is {@code goal}
     * (or, with {@code goal == null}, if it gets within one block of {@code to} without hitting anything).
     */
    public static boolean flightReaches(Level level, Entity shooter, Vec3 from, Vec3 direction, double speed, Vec3 to,
                                        @Nullable BlockPos goal) {
        Vec3 position = from;
        Vec3 velocity = direction.normalize().scale(speed);
        double maxDistance = from.distanceTo(to) + 2.0;
        for (int tick = 0; tick < 120; tick++) {
            Vec3 next = position.add(velocity);
            BlockHitResult hit = level.clip(new ClipContext(position, next, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, shooter));
            if (hit.getType() == HitResult.Type.BLOCK) {
                if (goal != null) {
                    return hit.getBlockPos().equals(goal);
                }
                return hit.getLocation().distanceTo(to) <= 1.0;
            }
            if (goal == null && segmentDistance(position, next, to) <= 0.6) {
                return true;
            }
            position = next;
            velocity = new Vec3(velocity.x * DRAG, velocity.y * DRAG - GRAVITY, velocity.z * DRAG);
            if (position.distanceTo(from) > maxDistance) {
                return false;
            }
        }
        return false;
    }

    private static double segmentDistance(Vec3 a, Vec3 b, Vec3 point) {
        Vec3 ab = b.subtract(a);
        double length = ab.lengthSqr();
        double t = length < 1.0E-7 ? 0.0 : Math.max(0.0, Math.min(1.0, point.subtract(a).dot(ab) / length));
        return a.add(ab.scale(t)).distanceTo(point);
    }
}
