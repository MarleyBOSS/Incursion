package com.marleyboss.incursion.siege;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A planned way to the Core: a list of feet positions. Each step may need blocks dug out (the cells the mob
 * walks through) and a floor block placed (the cell below the new position). Whether a step still needs
 * work is always read from the world, so it does not matter who did the work (or if a player undid it).
 * <p>
 * Routes are shared: a mob that gets stuck close to an existing route joins it instead of planning its own,
 * so mobs coming from the same side work on the same wall.
 */
public final class Route {
    /** A route is offered to newly stuck mobs for this long. */
    private static final long SHARE_TICKS = 1200;
    /** After this long, mobs drop the route and plan again (the base may have changed). */
    public static final long MAX_AGE_TICKS = 2400;
    /** At most one new route is planned every this many ticks. */
    private static final long PLAN_GAP_TICKS = 4;
    private static final int MAX_ROUTES = 24;

    private static final List<Route> ACTIVE = new ArrayList<>();
    private static long lastPlan = Long.MIN_VALUE / 2;

    public record Action(BlockPos pos, boolean dig) {
    }

    public final BlockPos corePos;
    public final boolean complete;
    public final long created;
    private final BlockPos[] nodes;
    private final BlockPos[][] clears;
    private final BlockPos[] supports;
    private boolean invalid;
    /** Step where work is going on (or waiting to be done). */
    private int frontier;
    private long lastWork = Long.MIN_VALUE / 2;
    private long lastNeedDig = Long.MIN_VALUE / 2;
    private long lastNeedBuild = Long.MIN_VALUE / 2;

    Route(BlockPos corePos, BlockPos[] nodes, BlockPos[][] clears, BlockPos[] supports, boolean complete, long created) {
        this.corePos = corePos.immutable();
        this.nodes = nodes;
        this.clears = clears;
        this.supports = supports;
        this.complete = complete;
        this.created = created;
    }

    public int size() {
        return nodes.length;
    }

    public BlockPos node(int index) {
        return nodes[Math.max(0, Math.min(index, nodes.length - 1))];
    }

    public boolean isUsable(BlockPos core, long now) {
        return !invalid && corePos.equals(core) && now - created < MAX_AGE_TICKS;
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

    /** The furthest route position close to the mob (to join a shared route); -1 if none. */
    public int joinIndex(Vec3 position) {
        int best = -1;
        for (int i = 0; i < nodes.length; i++) {
            Vec3 node = Vec3.atBottomCenterOf(nodes[i]);
            if (Math.abs(node.y - position.y) <= 1.1 && node.distanceToSqr(position) <= 4.0) {
                best = i;
            }
        }
        return best;
    }

    /**
     * First step in [from, to] that still needs work, or -1 if the way is open. Marks the route invalid
     * (and returns -1) if a step became impossible (e.g. a player placed an unbreakable block).
     */
    public int firstPending(Level level, int from, int to) {
        int last = Math.min(to, nodes.length - 1);
        for (int i = Math.max(1, from); i <= last; i++) {
            for (BlockPos cell : clears[i]) {
                if (!RoutePlanner.isPassable(level, cell)) {
                    if (!RoutePlanner.isDiggable(level, cell, corePos)) {
                        invalid = true;
                        return -1;
                    }
                    return i;
                }
            }
            BlockPos support = supports[i];
            if (support != null && !RoutePlanner.isSupport(level, support)) {
                if (!RoutePlanner.canPlace(level, support, corePos)) {
                    invalid = true;
                    return -1;
                }
                return i;
            }
        }
        return -1;
    }

    /** The next piece of work at step {@code index}: a block to dig (top first) or a floor to place. */
    @Nullable
    public Action action(Level level, int index) {
        for (BlockPos cell : clears[index]) {
            if (!RoutePlanner.isPassable(level, cell)) {
                return new Action(cell, true);
            }
        }
        BlockPos support = supports[index];
        if (support != null && !RoutePlanner.isSupport(level, support)) {
            return new Action(support, false);
        }
        return null;
    }

    public boolean isInvalid() {
        return invalid;
    }

    /** A worker is busy at this step. */
    public void markWork(int index, long now) {
        frontier = index;
        lastWork = now;
    }

    /** Mobs are waiting at this step for somebody who can dig (or build). */
    public void markNeed(int index, boolean dig, long now) {
        frontier = index;
        if (dig) {
            lastNeedDig = now;
        } else {
            lastNeedBuild = now;
        }
    }

    /** Where mobs that come to help should go: a few steps before the work. */
    public BlockPos meetingPoint() {
        return node(Math.max(0, frontier - 2));
    }

    /**
     * A route nearby that could use this mob: diggers go where somebody waits for a digger, builders where
     * somebody waits for a builder, everybody else (escorts) goes where workers are busy.
     */
    @Nullable
    public static Route findHelpWanted(Vec3 position, BlockPos core, boolean canDig, boolean canBuild, double range,
                                       long now) {
        Route best = null;
        double bestDistance = range * range;
        for (Route route : ACTIVE) {
            if (!route.isUsable(core, now)) {
                continue;
            }
            boolean wanted;
            if (canDig || canBuild) {
                wanted = (canDig && now - route.lastNeedDig < 200) || (canBuild && now - route.lastNeedBuild < 200);
            } else {
                wanted = now - route.lastWork < 600;
            }
            if (!wanted) {
                continue;
            }
            double distance = Vec3.atBottomCenterOf(route.meetingPoint()).distanceToSqr(position);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = route;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Shared registry
    // ------------------------------------------------------------------

    /** An existing route that passes right by the mob, or null. */
    @Nullable
    public static Route share(Vec3 position, BlockPos core, long now) {
        ACTIVE.removeIf(route -> !route.isUsable(core, now));
        Route best = null;
        int bestIndex = -1;
        for (Route route : ACTIVE) {
            if (now - route.created > SHARE_TICKS) {
                continue;
            }
            int index = route.joinIndex(position);
            if (index > bestIndex) {
                bestIndex = index;
                best = route;
            }
        }
        return best;
    }

    /** All routes the siege currently knows (for the AI view). */
    public static List<Route> active() {
        return java.util.Collections.unmodifiableList(ACTIVE);
    }

    public static boolean canPlan(long now) {
        return now - lastPlan >= PLAN_GAP_TICKS || now < lastPlan;
    }

    @Nullable
    public static Route plan(ServerLevel level, BlockPos start, BlockPos core, int coreLevel, int bound, long now) {
        lastPlan = now;
        Route route = RoutePlanner.plan(level, start, core, coreLevel, bound, now);
        if (route != null) {
            ACTIVE.add(route);
            while (ACTIVE.size() > MAX_ROUTES) {
                ACTIVE.remove(0);
            }
        }
        return route;
    }

    public static void clear() {
        ACTIVE.clear();
        lastPlan = Long.MIN_VALUE / 2;
    }
}
