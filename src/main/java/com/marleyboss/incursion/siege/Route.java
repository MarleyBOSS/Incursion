package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The way of one siege front: a list of feet positions from the assembly point (index 0, natural ground,
 * where the attackers spawn and gather) to the attack position right next to the Core (last index).
 * Each step may need blocks dug out (the cells the mob walks through) and a floor block (siege stone) placed
 * under the new position. Whether a step still needs work is always read from the world, so it does not
 * matter who did the work (or if a player undid it).
 */
public final class Route {
    public enum Work { DIG, BLOCK }

    public record Action(BlockPos pos, Work work) {
        public boolean dig() {
            return work == Work.DIG;
        }
    }

    /** Blocks at least this hard count as "hard" (obsidian ...): the siege sends more miners. */
    static final float HARD_BLOCK = 10.0F;

    public final BlockPos corePos;
    public final long created;
    private final BlockPos[] nodes;
    private final BlockPos[][] clears;
    private final BlockPos[] supports;
    private boolean invalid;
    private long lastProgress;
    /** Mobs on this route: entity id -> {route index, 1 if working, last seen}. */
    private final Map<Integer, long[]> members = new HashMap<>();
    private static final long MEMBER_TICKS = 30;

    Route(BlockPos corePos, BlockPos[] nodes, BlockPos[][] clears, BlockPos[] supports, long created) {
        this.corePos = corePos.immutable();
        this.nodes = nodes;
        this.clears = clears;
        this.supports = supports;
        this.created = created;
        this.lastProgress = created;
    }

    // ------------------------------------------------------------------
    // Members (who is on this route, and where)
    // ------------------------------------------------------------------

    /** A mob on this route reports where it is (every few ticks). */
    public void noteMember(int id, int index, boolean worker, long now) {
        members.put(id, new long[]{index, worker ? 1 : 0, now});
    }

    public void removeMember(int id) {
        members.remove(id);
    }

    private void pruneMembers(long now) {
        Iterator<long[]> iterator = members.values().iterator();
        while (iterator.hasNext()) {
            long[] member = iterator.next();
            if (now - member[2] > MEMBER_TICKS || now < member[2]) {
                iterator.remove();
            }
        }
    }

    /** How many other mobs on this route are further ahead (only workers, if asked). */
    public int countAhead(int id, int index, boolean workersOnly, long now) {
        pruneMembers(now);
        int count = 0;
        for (Map.Entry<Integer, long[]> entry : members.entrySet()) {
            long[] member = entry.getValue();
            if (entry.getKey() == id || (workersOnly && member[1] == 0)) {
                continue;
            }
            if (member[0] > index || (member[0] == index && entry.getKey() < id)) {
                count++;
            }
        }
        return count;
    }

    /** Work on this route got done (a block dug or placed). */
    public void noteProgress(long now) {
        lastProgress = now;
    }

    /** True if work on this route got done since then. */
    public boolean progressedSince(long since) {
        return lastProgress >= since;
    }

    public long lastProgress() {
        return lastProgress;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    public int size() {
        return nodes.length;
    }

    public int last() {
        return nodes.length - 1;
    }

    public BlockPos node(int index) {
        return nodes[Math.max(0, Math.min(index, nodes.length - 1))];
    }

    @Nullable
    BlockPos support(int index) {
        return supports[index];
    }

    BlockPos[] clears(int index) {
        return clears[index];
    }

    /**
     * Where mobs gather while the way is still being built: the last step before {@code limit} that stands on
     * natural ground (the foot of the stairs). Mobs on the built part only get in each other's way.
     */
    public int stagingIndex(Level level, int limit) {
        int i = Math.min(limit, nodes.length - 1);
        while (i > 0 && isBuilt(level, i)) {
            i--;
        }
        return Math.max(0, i);
    }

    private boolean isBuilt(Level level, int index) {
        BlockPos support = supports[index];
        if (support == null) {
            return false;
        }
        BlockState state = level.getBlockState(support);
        return state.is(ModBlocks.SIEGE_STONE.get()) || !RoutePlanner.isSupport(level, support);
    }

    /** Index of the route position closest to {@code position}, searched around {@code around}; -1 if the mob is off the route. */
    public int nearestIndex(Vec3 position, int around) {
        int from = Math.max(0, around - 4);
        int to = Math.min(nodes.length - 1, around + 8);
        int best = -1;
        double bestDistance = 2.5 * 2.5 + 0.001;
        for (int i = from; i <= to; i++) {
            double distance = Vec3.atBottomCenterOf(nodes[i]).distanceToSqr(position);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /** The route position the mob is standing at (anywhere on the route, the closest one); -1 if none. */
    public int findIndex(Vec3 position) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < nodes.length; i++) {
            Vec3 node = Vec3.atBottomCenterOf(nodes[i]);
            double dx = node.x - position.x;
            double dz = node.z - position.z;
            double dy = node.y - position.y;
            if (Math.abs(dy) <= 1.1 && dx * dx + dz * dz <= 1.5 * 1.5) {
                double distance = dx * dx + dz * dz + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Work
    // ------------------------------------------------------------------

    /**
     * First step in [from, to] that still needs work, or -1 if the way is open. Marks the route invalid
     * (and returns -1) if a step became impossible (e.g. a player placed an unbreakable block).
     */
    public int firstPending(Level level, int from, int to) {
        int last = Math.min(to, nodes.length - 1);
        for (int i = Math.max(1, from); i <= last; i++) {
            Action action = actionAt(level, i);
            if (action == INVALID) {
                invalid = true;
                return -1;
            }
            if (action != null) {
                return i;
            }
        }
        return -1;
    }

    /** The next piece of work at step {@code index}: a block to dig (top first) or a floor to place. */
    @Nullable
    public Action action(Level level, int index) {
        Action action = actionAt(level, index);
        return action == INVALID ? null : action;
    }

    private static final Action INVALID = new Action(BlockPos.ZERO, Work.DIG);

    @Nullable
    private Action actionAt(Level level, int index) {
        for (BlockPos cell : clears[index]) {
            if (!RoutePlanner.isPassable(level, cell)) {
                return RoutePlanner.isDiggable(level, cell, corePos) ? new Action(cell, Work.DIG) : INVALID;
            }
        }
        BlockPos support = supports[index];
        if (support == null) {
            return null;
        }
        if (!RoutePlanner.isSupport(level, support)) {
            return RoutePlanner.canPlace(level, support, corePos) ? new Action(support, Work.BLOCK) : INVALID;
        }
        return null;
    }

    /**
     * Counts the work still to do on the whole route: {blocks to place, blocks to dig, hard blocks to dig}.
     * Also notices if the route became impossible.
     */
    int[] countWork(Level level) {
        int build = 0;
        int dig = 0;
        int hard = 0;
        for (int i = 1; i < nodes.length; i++) {
            for (BlockPos cell : clears[i]) {
                if (!RoutePlanner.isPassable(level, cell)) {
                    if (!RoutePlanner.isDiggable(level, cell, corePos)) {
                        invalid = true;
                        continue;
                    }
                    dig++;
                    if (level.getBlockState(cell).getDestroySpeed(level, cell) >= HARD_BLOCK) {
                        hard++;
                    }
                }
            }
            BlockPos support = supports[i];
            if (support != null && !RoutePlanner.isSupport(level, support)) {
                if (!RoutePlanner.canPlace(level, support, corePos)) {
                    invalid = true;
                } else {
                    build++;
                }
            }
        }
        // The assembly point itself must stay usable (natural ground, free).
        BlockPos start = nodes[0];
        if (!RoutePlanner.isPassable(level, start) || !RoutePlanner.isPassable(level, start.above())
                || !RoutePlanner.isSupport(level, start.below())) {
            invalid = true;
        }
        return new int[]{build, dig, hard};
    }

    public boolean isInvalid() {
        return invalid;
    }

    void invalidate() {
        invalid = true;
    }
}
