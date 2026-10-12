package com.marleyboss.incursion.siege;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Finds the source blocks that feed flowing water or lava ("upstream" search). Replacing every source with a
 * block makes the whole flow disappear, so builders do not have to fight the fluid cell by cell.
 */
final class FluidSources {
    /** The sources found, and whether the search saw the whole body of fluid (false: too big, or not loaded). */
    record Result(List<BlockPos> sources, boolean complete) {
    }

    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private FluidSources() {
    }

    /**
     * Walks against the flow from {@code starts}: a flowing cell is fed by the same fluid above it and by
     * side neighbours with a higher level; a source ends the chain.
     *
     * @param maxSources more sources than this: the body is too big to remove (result not complete)
     * @param maxCells   search budget (cells visited)
     */
    static Result upstream(Level level, Collection<BlockPos> starts, int maxSources, int maxCells) {
        LongOpenHashSet seen = new LongOpenHashSet();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> sources = new ArrayList<>();
        for (BlockPos start : starts) {
            if (seen.add(start.asLong())) {
                queue.add(start.immutable());
            }
        }
        boolean complete = true;
        while (!queue.isEmpty()) {
            if (seen.size() > maxCells) {
                complete = false;
                break;
            }
            BlockPos pos = queue.poll();
            if (!level.isLoaded(pos)) {
                complete = false;
                continue;
            }
            FluidState fluid = level.getFluidState(pos);
            if (fluid.isEmpty()) {
                continue;
            }
            if (fluid.isSource()) {
                sources.add(pos);
                if (sources.size() > maxSources) {
                    complete = false;
                    break;
                }
                continue;
            }
            BlockPos above = pos.above();
            if (feeds(level, above, fluid, false) && seen.add(above.asLong())) {
                queue.add(above);
            }
            for (Direction side : SIDES) {
                BlockPos neighbour = pos.relative(side);
                if (feeds(level, neighbour, fluid, true) && seen.add(neighbour.asLong())) {
                    queue.add(neighbour);
                }
            }
        }
        return new Result(sources, complete);
    }

    /** Does the fluid at {@code from} feed {@code into}? (Same fluid; from the side only if it is higher.) */
    private static boolean feeds(Level level, BlockPos from, FluidState into, boolean sideways) {
        if (!level.isLoaded(from)) {
            return false;
        }
        FluidState other = level.getFluidState(from);
        if (other.isEmpty() || !other.getType().isSame(into.getType())) {
            return false;
        }
        return !sideways || other.getAmount() > into.getAmount();
    }
}
