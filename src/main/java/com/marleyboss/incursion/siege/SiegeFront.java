package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.siege.MobRoles.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One line of attack planned by the {@link SiegeCommander}: a way from the assembly point (where the
 * attackers of this front spawn and gather) up to the attack position next to the Core, plus a platform
 * there and landings (wide platforms) along the stairs. The commander keeps track of how much work is left and
 * which roles the front still needs.
 */
final class SiegeFront {
    /** A platform beside the way, level with route step {@code index} (standing cells; their floors are built). */
    record Landing(int index, List<BlockPos> spots) {
    }

    /** The next piece of landing work: which landing, and the block to dig or place. */
    record LandingJob(Landing landing, Route.Action action) {
    }

    final int id;
    final Route route;
    final Direction side;
    final List<BlockPos> platform;
    final List<Landing> landings;
    final long created;
    /**
     * The ward totem this front attacks (the focus), or null if it attacks the Core. When the totem is switched off
     * (or gone) the commander drops the front and plans one that goes straight for the Core.
     */
    @Nullable
    final BlockPos ward;

    // Refreshed once per second by the commander.
    int pendingBuild;
    int pendingDig;
    int hardDig;
    int platformWork;
    /** Work left on the landings whose way up is already finished. */
    int landingWork;
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

    SiegeFront(int id, Route route, Direction side, List<BlockPos> platform, List<Landing> landings, long created,
               @Nullable BlockPos ward) {
        this.ward = ward != null ? ward.immutable() : null;
        this.id = id;
        this.route = route;
        this.side = side;
        this.platform = platform;
        this.landings = landings;
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
        return spotsAction(level, platform, canDig, canBuild);
    }

    /** Counts the platform work left (blocks to dig or place). */
    int countPlatformWork(Level level) {
        return countSpotsWork(level, platform, true);
    }

    /**
     * The next piece of landing work for a builder standing at route step {@code fromIndex}. Only landings whose way
     * up is finished count (a builder has to be able to walk there), lowest first; landings far below the builder
     * are left out.
     */
    @Nullable
    LandingJob landingAction(Level level, boolean canBuild, int fromIndex) {
        if (!Config.LANDINGS_ENABLED.get()) {
            return null;
        }
        // The landing closest to the builder (along the way) that still needs work. Builders walk down the
        // stairs to a landing, so landings behind them count too.
        LandingJob best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Landing landing : landings) {
            if (route.firstPending(level, 1, landing.index()) >= 0) {
                break; // the way up to this landing (and so to all further ones) is not finished
            }
            int distance = Math.abs(landing.index() - fromIndex);
            if (distance >= bestDistance) {
                continue;
            }
            Route.Action action = spotsAction(level, landing.spots(), false, canBuild);
            if (action != null) {
                best = new LandingJob(landing, action);
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Counts the landing work left on landings that can be reached. */
    int countLandingWork(Level level) {
        if (!Config.LANDINGS_ENABLED.get()) {
            return 0;
        }
        int work = 0;
        for (Landing landing : landings) {
            if (route.firstPending(level, 1, landing.index()) >= 0) {
                break;
            }
            work += countSpotsWork(level, landing.spots(), false);
        }
        return work;
    }

    @Nullable
    private Route.Action spotsAction(Level level, List<BlockPos> spots, boolean canDig, boolean canBuild) {
        for (BlockPos spot : spots) {
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

    private int countSpotsWork(Level level, List<BlockPos> spots, boolean countDigging) {
        int work = 0;
        for (BlockPos spot : spots) {
            for (BlockPos cell : new BlockPos[]{spot.above(), spot}) {
                if (countDigging && !RoutePlanner.isPassable(level, cell)
                        && RoutePlanner.isDiggable(level, cell, route.corePos)) {
                    work++;
                }
            }
            BlockPos floor = spot.below();
            if (RoutePlanner.isPassable(level, spot) && RoutePlanner.isPassable(level, spot.above())
                    && !RoutePlanner.isSupport(level, floor) && RoutePlanner.canPlace(level, floor, route.corePos)) {
                work++;
            }
        }
        return work;
    }
}
