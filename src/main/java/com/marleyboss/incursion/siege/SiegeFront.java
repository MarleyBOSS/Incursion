package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.siege.MobRoles.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One line of attack planned by the {@link SiegeCommander}: a way from the assembly point (where the
 * attackers of this front spawn and gather) up to the attack position next to the Core, plus a small platform
 * there. The commander keeps track of how much work is left and which roles the front still needs.
 */
final class SiegeFront {
    final int id;
    final Route route;
    final Direction side;
    final List<BlockPos> platform;
    final long created;

    // Refreshed once per second by the commander.
    int pendingBuild;
    int pendingDig;
    int hardDig;
    int platformWork;
    /** No work left on the way itself (the platform may still be under construction). */
    boolean open;
    final int[] roleCounts = new int[Role.values().length];
    int members;
    /** Members that are not builders or miners. */
    int escorts;
    /** Last time a member with a fitting tool for the current dig job was on this front. */
    long lastFastDigger = Long.MIN_VALUE / 2;
    long lastPromotion = Long.MIN_VALUE / 2;
    /** Spawns ordered this second (counted before the mobs show up in the next count). */
    int orderedBuilders;
    int orderedMiners;
    int orderedOthers;

    SiegeFront(int id, Route route, Direction side, List<BlockPos> platform, long created) {
        this.id = id;
        this.route = route;
        this.side = side;
        this.platform = platform;
        this.created = created;
    }

    BlockPos assembly() {
        return route.node(0);
    }

    BlockPos attackPos() {
        return route.node(route.last());
    }

    int builders() {
        return roleCounts[Role.BUILDER.ordinal()] + orderedBuilders;
    }

    int miners() {
        return roleCounts[Role.MINER.ordinal()] + orderedMiners;
    }

    int others() {
        return escorts + orderedOthers;
    }

    int totalWork() {
        return pendingBuild + pendingDig;
    }

    /** The next piece of work on the platform (only once the way is open), or null. */
    @Nullable
    Route.Action platformAction(Level level, boolean canDig, boolean canBuild) {
        for (BlockPos spot : platform) {
            for (BlockPos cell : new BlockPos[]{spot.above(), spot}) {
                if (!RoutePlanner.isPassable(level, cell)) {
                    if (RoutePlanner.isDiggable(level, cell, route.corePos)) {
                        if (canDig) {
                            return new Route.Action(cell, Route.Work.DIG);
                        }
                    }
                    break; // cannot clear this spot (or not this mob's job): next spot
                }
            }
            BlockPos floor = spot.below();
            if (canBuild && RoutePlanner.isPassable(level, spot) && RoutePlanner.isPassable(level, spot.above())
                    && !RoutePlanner.isSupport(level, floor) && RoutePlanner.canPlace(level, floor, route.corePos)) {
                return new Route.Action(floor, Route.Work.BLOCK);
            }
        }
        return null;
    }

    /** Counts the platform work left (blocks to dig or place). */
    int countPlatformWork(Level level) {
        int work = 0;
        for (BlockPos spot : platform) {
            for (BlockPos cell : new BlockPos[]{spot.above(), spot}) {
                if (!RoutePlanner.isPassable(level, cell) && RoutePlanner.isDiggable(level, cell, route.corePos)) {
                    work++;
                }
            }
            BlockPos floor = spot.below();
            if (!RoutePlanner.isSupport(level, floor) && RoutePlanner.canPlace(level, floor, route.corePos)) {
                work++;
            }
        }
        return work;
    }
}
