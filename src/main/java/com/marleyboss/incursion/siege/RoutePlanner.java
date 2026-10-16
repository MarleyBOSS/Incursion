package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.block.CoreBlock;
import com.marleyboss.incursion.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The siege commander's planner: finds the cheapest way for a whole siege front to reach the Core.
 * <p>
 * The search starts at the Core and works outwards (A* backwards): from the attack positions right next to
 * the Core (feet or head at the Core's height, the wall there is dug open) to an <b>assembly point</b> on
 * natural ground at the surface, far enough from the Core. The attackers spawn at the assembly point and
 * follow the way to the Core. Everything in between is built the cheapest way: walk, dig through walls
 * (soft blocks cheap, obsidian expensive), and place floor blocks (stairs going up one block per step,
 * bridges). There are no ladders or pillars: to reach a Core high up, the siege builds a staircase.
 * <p>
 * A route never builds into its own way (or that of another front), and never digs away a floor it stands on.
 * The search runs in slices ({@link #step}) so a long search does not freeze the server.
 */
public final class RoutePlanner {
    static final float INF = Float.POSITIVE_INFINITY;
    static final float WALK = 5.0F;
    static final float UP_EXTRA = 3.0F;
    /** Changing direction costs a little: straight stairs are easier to walk. */
    static final float TURN = 2.0F;
    /**
     * A turn right after a short straight run costs this much per missing step of the run (see {@code stairFlight}):
     * the stairs get long straight flights with a turn (and a landing) only after some steps, not a tight corkscrew
     * where every flight lands on the one below.
     */
    static final float SHORT_FLIGHT_PENALTY = 25.0F;
    /** Per side of a stair step where the extra column (width 3) cannot be built: wide stairs are worth a detour. */
    static final float WING_LOSS_PENALTY = 14.0F;
    static final float DIG_OVERHEAD = 6.0F;
    static final float PLACE_OVERHEAD = 15.0F;
    /** Steps on another front's way cost this much extra (a second front builds its own way). */
    static final float OTHER_FRONT_PENALTY = 25.0F;
    /** Attacking from the same side of the Core as another front costs this much extra. */
    static final float SAME_SIDE_PENALTY = 400.0F;
    static final float HEURISTIC_WEIGHT = 1.5F;
    /** How many later steps are checked so a route does not block itself (switchback stairs). */
    private static final int CONFLICT_DEPTH = 16;
    private static final int MAX_DROP = 3;
    /** Underground Core: the assembly point may be in a cave this close to the Core's height. */
    static final int CAVE_BAND = 6;

    private static final byte SUPPORT_SOLID = 0;
    private static final byte SUPPORT_PLACEABLE = 1;
    private static final byte SUPPORT_NONE = 2;

    /**
     * @param clear   cost to make the cell passable (0 = already free, INF = impossible)
     * @param support can a mob stand on it (solid), could a block be placed there, or neither
     */
    private record Cell(float clear, byte support, boolean siegeStone) {
    }

    private static final Cell BLOCKED = new Cell(INF, SUPPORT_NONE, false);
    private static final Cell OPEN_SKY = new Cell(0.0F, SUPPORT_NONE, false);
    private static final long[] NO_CELLS = new long[0];

    private static final class Node {
        final int x;
        final int y;
        final int z;
        float g = INF;
        float h;
        /** The next step towards the Core (null for an attack position). */
        @Nullable
        Node next;
        /** Direction of the move from this node to {@link #next} (2D data value), -1 for attack positions. */
        int dir = -1;
        /** Side of the Core this way attacks from (2D data value). */
        int side;
        /** How many moves in a row (from this node on, towards the Core) go in the direction {@link #dir}. */
        int run;
        /** Is the move from this node to {@link #next} on the level (a walk, no step up or down)? */
        boolean flat;
        boolean closed;
        /** Cells the move from this node to {@link #next} needs free. */
        long[] moveClears = NO_CELLS;
        /** The cell this node stands on. */
        long support;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private record Entry(Node node, float f) {
    }

    /**
     * A finished plan: the way (assembly point first), the extra platform cells next to the attack position and the
     * landings (wide platforms) along the stairs.
     */
    public record Result(Route route, Direction side, List<BlockPos> platform, List<SiegeFront.Landing> landings) {
    }

    /** Cells of a route that other things (platforms, landings, wings) must not take. */
    private record RouteSets(LongOpenHashSet clear, LongOpenHashSet support, LongOpenHashSet nodes) {
    }

    private final ServerLevel level;
    private final BlockPos core;
    private final int bound;
    private final double assemblyDistance;
    private final List<BlockPos> wards;
    private final int wardRadius;
    /** Square around the real Core in which nothing may assemble (the safe zone); also set for hunts. */
    private BlockPos safeCenter;
    private int safeHalf = -1;
    /** Square (the siege area) in which the assembly point must lie; null = no limit. */
    private BlockPos areaCenter;
    private int areaHalf;
    private final boolean underground;
    private final boolean canBuild;
    private final float placeCost;
    private final float stepDownCost;
    private final ItemStack pick;
    private final double attack;
    private final LongOpenHashSet avoidClear;
    private final LongOpenHashSet avoidSupport;
    private final LongOpenHashSet otherNodes;
    private final int budget;
    private final long started;
    private final int minY;
    private final int maxY;
    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap tops = new Long2IntOpenHashMap();
    private final PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Float.compare(a.f(), b.f()));
    private int expanded;
    private boolean done;
    @Nullable
    private Result result;

    /**
     * @param avoidClear   cells other fronts need free (this front must not build there)
     * @param avoidSupport floor blocks of other fronts (this front must not dig them away)
     * @param otherNodes   feet positions of other fronts (using them costs extra)
     * @param usedSides    sides of the Core other fronts already attack from
     */
    RoutePlanner(ServerLevel level, BlockPos core, int coreLevel, int bound, double assemblyDistance, int elevation,
                 boolean underground, List<BlockPos> wards, int wardRadius, LongOpenHashSet avoidClear,
                 LongOpenHashSet avoidSupport, LongOpenHashSet otherNodes, Set<Direction> usedSides, long now) {
        this.level = level;
        this.core = core.immutable();
        this.bound = bound;
        this.assemblyDistance = assemblyDistance;
        this.underground = underground;
        this.wards = wards;
        this.wardRadius = wardRadius;
        this.canBuild = Config.BUILDING_ENABLED.get();
        this.placeCost = Config.BUILD_TICKS.get() + PLACE_OVERHEAD;
        this.stepDownCost = WALK + UP_EXTRA + (canBuild ? placeCost * 0.8F : 0.0F);
        this.pick = MobRoles.referencePickaxe(coreLevel);
        this.attack = 4.0 * (1.0 + Config.MOB_DAMAGE_BONUS_PER_LEVEL.get() * coreLevel);
        this.avoidClear = avoidClear;
        this.avoidSupport = avoidSupport;
        this.otherNodes = otherNodes;
        this.budget = Math.max(60000, Config.PLANNER_MAX_NODES.get());
        this.started = now;
        this.tops.defaultReturnValue(Integer.MIN_VALUE);

        int surfaceAtCore = columnTop(core.getX(), core.getZ());
        this.minY = Math.max(level.getMinBuildHeight() + 1, core.getY() - elevation - 32);
        int top = core.getY() + 24;
        if (surfaceAtCore != Integer.MAX_VALUE) {
            top = Math.max(top, surfaceAtCore + 6);
        }
        this.maxY = Math.min(level.getMaxBuildHeight() - 2, top);

        // Seeds: every spot next to the Core from which a mob can hit it (feet or head at the Core's height).
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 0; dy++) {
                BlockPos spot = core.relative(side).above(dy);
                if (cell(spot.getX(), spot.getY(), spot.getZ()).clear() == INF
                        || cell(spot.getX(), spot.getY() + 1, spot.getZ()).clear() == INF) {
                    continue;
                }
                Cell below = cell(spot.getX(), spot.getY() - 1, spot.getZ());
                float support = supportCost(below);
                if (support == INF) {
                    continue;
                }
                long supportKey = BlockPos.asLong(spot.getX(), spot.getY() - 1, spot.getZ());
                if (below.support() == SUPPORT_PLACEABLE && avoidClear.contains(supportKey)) {
                    continue;
                }
                if (avoidSupport.contains(spot.asLong()) || avoidSupport.contains(spot.above().asLong())) {
                    continue;
                }
                Node node = node(spot.getX(), spot.getY(), spot.getZ());
                node.g = support + (usedSides.contains(side) ? SAME_SIDE_PENALTY : 0.0F);
                node.h = heuristic(node.x, node.y, node.z);
                node.side = side.get2DDataValue();
                node.support = supportKey;
                open.add(new Entry(node, node.g + node.h * HEURISTIC_WEIGHT));
            }
        }
        if (open.isEmpty()) {
            done = true;
        }
    }

    public boolean isDone() {
        return done;
    }

    @Nullable
    public Result result() {
        return result;
    }

    public long started() {
        return started;
    }

    /** Runs up to {@code maxExpansions} search steps. Returns true when the search is finished (found or failed). */
    public boolean step(int maxExpansions, long now) {
        int count = 0;
        while (!done && !open.isEmpty() && count < maxExpansions) {
            Entry entry = open.poll();
            Node current = entry.node();
            if (current.closed || entry.f() != current.g + current.h * HEURISTIC_WEIGHT) {
                continue;
            }
            current.closed = true;
            count++;
            expanded++;
            if (isAssembly(current)) {
                finish(current, now);
                return true;
            }
            expand(current);
            if (expanded >= budget) {
                done = true;
            }
        }
        if (open.isEmpty()) {
            done = true;
        }
        return done;
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /** The assembly point must lie inside the square of the given half side around {@code center} (the siege area). */
    public RoutePlanner withArea(BlockPos center, int half) {
        this.areaCenter = center;
        this.areaHalf = half;
        return this;
    }

    /** Nothing assembles (so nothing spawns) inside the square of the given half side around {@code center}. */
    public RoutePlanner withSafeSquare(BlockPos center, int half) {
        this.safeCenter = center;
        this.safeHalf = half;
        return this;
    }

    /** Natural ground at the surface (or in a cave at about the height of an underground Core), far enough out. */
    private boolean isAssembly(Node n) {
        if (n.next == null) {
            return false;
        }
        double dx = n.x - core.getX();
        double dz = n.z - core.getZ();
        // The no-assembly area around the Core is a square (like the border shown in the Core GUI).
        double distance = Math.max(Math.abs(dx), Math.abs(dz));
        if (distance < assemblyDistance || Math.abs(dx) > bound - 2 || Math.abs(dz) > bound - 2) {
            return false;
        }
        if (areaCenter != null
                && Math.max(Math.abs(n.x - areaCenter.getX()), Math.abs(n.z - areaCenter.getZ())) > areaHalf) {
            return false; // outside the siege area: mobs there would be sent away again
        }
        // One block of margin, because mobs may spawn one block next to the assembly point.
        if (safeCenter != null && Math.max(Math.abs(n.x - safeCenter.getX()), Math.abs(n.z - safeCenter.getZ())) <= safeHalf + 1) {
            return false;
        }
        if (cell(n.x, n.y, n.z).clear() != 0.0F || cell(n.x, n.y + 1, n.z).clear() != 0.0F) {
            return false;
        }
        Cell below = cell(n.x, n.y - 1, n.z);
        if (below.support() != SUPPORT_SOLID || below.siegeStone()) {
            return false;
        }
        BlockPos pos = new BlockPos(n.x, n.y, n.z);
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()
                || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
            return false; // mobs must be able to spawn here (not on leaves, not in water)
        }
        boolean surface = n.y >= columnTop(n.x, n.z);
        boolean cave = underground && Math.abs(n.y - core.getY()) <= CAVE_BAND;
        if (!surface && !cave) {
            return false;
        }
        if (!cave && isPit(n.x, n.y, n.z)) {
            return false; // bottom of a ravine or pit: the attackers would gather (and get stuck) down there
        }
        for (BlockPos ward : wards) {
            double wx = n.x - ward.getX();
            double wz = n.z - ward.getZ();
            if (Math.abs(wx) <= wardRadius + 1 && Math.abs(wz) <= wardRadius + 1) { // square, like the border shown
                return false;
            }
        }
        return true;
    }

    /** Most of the ground a few blocks around is much higher: a ravine, a pit or a cave opening. */
    private boolean isPit(int x, int y, int z) {
        int higher = 0;
        for (int dx = -PIT_RANGE; dx <= PIT_RANGE; dx += PIT_RANGE) {
            for (int dz = -PIT_RANGE; dz <= PIT_RANGE; dz += PIT_RANGE) {
                if ((dx != 0 || dz != 0) && columnTop(x + dx, z + dz) > y + PIT_DEPTH) {
                    higher++;
                }
            }
        }
        return higher >= 5;
    }

    private static final int PIT_RANGE = 3;
    private static final int PIT_DEPTH = 3;

    private float heuristic(int x, int y, int z) {
        double dx = x - core.getX();
        double dz = z - core.getZ();
        float horizontal = (float) Math.max(0.0, assemblyDistance - Math.max(Math.abs(dx), Math.abs(dz))) * WALK;
        float vertical = 0.0F;
        int top = columnTop(x, z);
        if (top != Integer.MAX_VALUE) {
            if (y > top + MAX_DROP) {
                vertical = (y - top - MAX_DROP) * stepDownCost; // has to build stairs down to the ground
            } else if (y < top - 1 && !(underground && Math.abs(y - core.getY()) <= CAVE_BAND)) {
                vertical = (top - 1 - y) * (WALK + UP_EXTRA); // has to get up to the surface
            }
        }
        return Math.max(horizontal, vertical);
    }

    /**
     * Expands backwards: finds the positions P from which a mob can move onto {@code c}
     * (walk, step up, step down, drop down).
     */
    private void expand(Node c) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int px = c.x - direction.getStepX();
            int pz = c.z - direction.getStepZ();
            if (Math.abs(px - core.getX()) > bound || Math.abs(pz - core.getZ()) > bound) {
                continue;
            }
            int dir = direction.get2DDataValue();
            float turn = 0.0F;
            boolean turning = c.dir >= 0 && c.dir != dir;
            // Turns on built stairs happen on a level landing (before and after the corner no step up or down), like
            // the landing of a stairwell: the corner and the cells before and after it are one flat platform.
            boolean landingOnly = turning && cell(c.x, c.y - 1, c.z).support() == SUPPORT_PLACEABLE;
            if (landingOnly && !c.flat) {
                continue;
            }
            if (turning) {
                turn = TURN;
                int flight = Config.STAIR_FLIGHT.get();
                if (c.run < flight) {
                    turn += (flight - c.run) * SHORT_FLIGHT_PENALTY; // a turn after a short flight: corkscrew
                }
            }

            // Walk: P at the same height.
            long[] flat = {key(c.x, c.y + 1, c.z), key(c.x, c.y, c.z)};
            float clearFlat = clearCost(flat);
            if (clearFlat < INF) {
                tryPush(c, px, c.y, pz, WALK + turn + clearFlat, flat, dir, true);
            }

            // Step up (P one lower): the mob jumps from P onto c, whose floor is the block in front of P.
            long[] up = {key(px, c.y + 1, pz), key(c.x, c.y + 1, c.z), key(c.x, c.y, c.z)};
            float clearUp = clearCost(up);
            if (clearUp < INF && !landingOnly) {
                tryPush(c, px, c.y - 1, pz, WALK + UP_EXTRA + turn + clearUp, up, dir, false);
            }

            // Step down (P one higher).
            long[] down = {key(c.x, c.y + 2, c.z), key(c.x, c.y + 1, c.z), key(c.x, c.y, c.z)};
            float clearDown = clearCost(down);
            if (clearDown < INF && !landingOnly) {
                tryPush(c, px, c.y + 1, pz, WALK + turn + clearDown, down, dir, false);
            }

            // Drop down 2-3 blocks onto natural ground (nothing to dig or build).
            if (!landingOnly && cell(c.x, c.y - 1, c.z).support() == SUPPORT_SOLID) {
                for (int k = 2; k <= MAX_DROP; k++) {
                    long[] column = new long[k + 2];
                    for (int j = 0; j < k + 2; j++) {
                        column[j] = key(c.x, c.y + k + 1 - j, c.z);
                    }
                    if (clearCost(column) != 0.0F) {
                        break;
                    }
                    tryPush(c, px, c.y + k, pz, WALK + k + turn, column, dir, false);
                }
            }
        }
    }

    private float clearCost(long[] cellKeys) {
        float sum = 0.0F;
        for (long k : cellKeys) {
            float clear = cell(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k)).clear();
            if (clear == INF) {
                return INF;
            }
            sum += clear;
        }
        return sum;
    }

    private float supportCost(Cell cell) {
        return switch (cell.support()) {
            case SUPPORT_SOLID -> 0.0F;
            case SUPPORT_PLACEABLE -> placeCost;
            default -> INF;
        };
    }

    /** Adds P (the step before {@code c}) unless the move would block this route or another front. */
    private void tryPush(Node c, int px, int py, int pz, float cost, long[] moveClears, int dir, boolean flat) {
        if (py < minY || py > maxY) {
            return;
        }
        Cell below = cell(px, py - 1, pz);
        float support = supportCost(below);
        if (support == INF) {
            return;
        }
        long nodeKey = key(px, py, pz);
        float total = c.g + cost + support;
        if (otherNodes.contains(nodeKey)) {
            total += OTHER_FRONT_PENALTY;
        }
        total += lostWings(c, px, py, pz, dir, moveClears) * WING_LOSS_PENALTY;
        Node existing = nodes.get(nodeKey);
        if (existing != null && (existing.closed || total >= existing.g)) {
            return;
        }
        long supportKey = key(px, py - 1, pz);
        // Other fronts: never build into their way, never dig away their floor.
        if (below.support() == SUPPORT_PLACEABLE && avoidClear.contains(supportKey)) {
            return;
        }
        for (long cell : moveClears) {
            if (cell == supportKey) {
                return;
            }
            if (avoidSupport.contains(cell) && cellNeedsDigging(cell)) {
                return;
            }
        }
        // This route: the floor of P must not be in the way of a later step, and neither the move nor P itself
        // (where the mob stands) may need the floor of a later step dug away.
        long feetKey = nodeKey;
        long headKey = key(px, py + 1, pz);
        int depth = 0;
        for (Node a = c; a != null && depth < CONFLICT_DEPTH; a = a.next, depth++) {
            if (a.support == feetKey || a.support == headKey) {
                return;
            }
            for (long cell : a.moveClears) {
                if (cell == supportKey) {
                    return;
                }
            }
            for (long cell : moveClears) {
                if (cell == a.support) {
                    return;
                }
            }
        }
        Node node = existing != null ? existing : node(px, py, pz);
        node.g = total;
        node.h = heuristic(px, py, pz);
        node.next = c;
        node.dir = dir;
        node.run = c.dir == dir ? c.run + 1 : 1;
        node.flat = flat;
        node.side = c.side;
        node.moveClears = moveClears;
        node.support = supportKey;
        open.add(new Entry(node, node.g + node.h * HEURISTIC_WEIGHT));
    }

    /**
     * For the stairs to be wide the builders place an extra column on each side of a built step (see {@link #wings}).
     * How many of the two sides could not get one here: no free standing room beside the step, a cell the route
     * itself needs later (the stairs turn above or beside it), or another front's way. The planner pays extra for
     * that, so it prefers open, roomy places and well-spaced flights to cramped ones: the way should be as good for
     * many mobs as possible, not only as cheap as possible.
     */
    private int lostWings(Node c, int px, int py, int pz, int dir, long[] moveClears) {
        if (!canBuild || Config.STAIR_WIDTH.get() < 2) {
            return 0;
        }
        int lost = 0;
        Direction move = Direction.from2DDataValue(dir);
        // 1. The extra columns of this step.
        if (cell(px, py - 1, pz).support() == SUPPORT_PLACEABLE) {
            for (Direction perp : new Direction[]{move.getClockWise(), move.getCounterClockWise()}) {
                int wx = px + perp.getStepX();
                int wz = pz + perp.getStepZ();
                int wy = py - 1;
                long floor = key(wx, wy, wz);
                long stand = key(wx, wy + 1, wz);
                long head = key(wx, wy + 2, wz);
                boolean ok = cell(wx, wy, wz).support() != SUPPORT_NONE && !avoidClear.contains(floor)
                        && cell(wx, wy + 1, wz).clear() == 0.0F && cell(wx, wy + 2, wz).clear() == 0.0F;
                for (long cell : moveClears) {
                    if (cell == floor) {
                        ok = false;
                    }
                }
                int depth = 0;
                for (Node a = c; ok && a != null && depth < WING_CHECK_DEPTH; a = a.next, depth++) {
                    if (a.support == stand || a.support == head
                            || key(a.x, a.y, a.z) == floor || key(a.x, a.y + 1, a.z) == floor) {
                        ok = false;
                        break;
                    }
                    for (long cell : a.moveClears) {
                        if (cell == floor) {
                            ok = false;
                            break;
                        }
                    }
                }
                if (!ok) {
                    lost++;
                }
            }
        }
        // 2. The extra columns of the later steps that this step takes the room of (it stands or needs free space there).
        long feet = key(px, py, pz);
        long above = key(px, py + 1, pz);
        int inDir = dir;
        int depth = 0;
        for (Node a = c; a != null && depth < WING_CHECK_DEPTH; a = a.next, depth++) {
            if (cell(a.x, a.y - 1, a.z).support() == SUPPORT_PLACEABLE) {
                Direction inMove = Direction.from2DDataValue(inDir);
                for (Direction side : new Direction[]{inMove.getClockWise(), inMove.getCounterClockWise()}) {
                    long wing = key(a.x + side.getStepX(), a.y - 1, a.z + side.getStepZ());
                    boolean taken = wing == feet || wing == above;
                    for (long cell : moveClears) {
                        taken |= cell == wing;
                    }
                    if (taken) {
                        lost++;
                    }
                }
            }
            if (a.dir < 0) {
                break;
            }
            inDir = a.dir;
        }
        return lost;
    }

    private static final int WING_CHECK_DEPTH = 24;

    private boolean cellNeedsDigging(long key) {
        return cell(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key)).clear() > 0.0F;
    }

    private static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private Node node(int x, int y, int z) {
        long key = key(x, y, z);
        Node node = nodes.get(key);
        if (node == null) {
            node = new Node(x, y, z);
            nodes.put(key, node);
        }
        return node;
    }

    /** First free height above the ground of this column (leaves ignored); MAX_VALUE if not loaded. */
    private int columnTop(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        int top = tops.get(key);
        if (top == Integer.MIN_VALUE) {
            BlockPos probe = new BlockPos(x, core.getY(), z);
            top = level.isLoaded(probe) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : Integer.MAX_VALUE;
            tops.put(key, top);
        }
        return top;
    }

    private Cell cell(int x, int y, int z) {
        long key = key(x, y, z);
        Cell cell = cells.get(key);
        if (cell == null) {
            cell = computeCell(new BlockPos(x, y, z));
            cells.put(key, cell);
        }
        return cell;
    }

    private Cell computeCell(BlockPos pos) {
        if (pos.getY() >= level.getMaxBuildHeight()) {
            return OPEN_SKY; // above the world: free air, nothing can be built there
        }
        if (pos.getY() < level.getMinBuildHeight() || !level.isLoaded(pos)) {
            return BLOCKED;
        }
        BlockState state = level.getBlockState(pos);
        boolean fluid = !state.getFluidState().isEmpty();
        boolean hazard = Digging.isHazard(state);
        boolean collision = !state.getCollisionShape(level, pos).isEmpty();
        float hardness = state.getDestroySpeed(level, pos);
        boolean protectedBlock = pos.equals(core) || state.getBlock() instanceof CoreBlock
                || state.is(Digging.SIEGE_PROOF) || hardness < 0;
        float clear;
        if (!collision) {
            if (fluid) {
                // Water and lava are filled with siege stone first (a placed block displaces the fluid), the
                // stone is dug out afterwards. Needs builders.
                clear = canBuild && !protectedBlock ? fillCost(pos) : INF;
            } else if (hazard) {
                clear = protectedBlock ? INF : Digging.digTicks(pick, attack, state, hardness) + DIG_OVERHEAD;
            } else {
                clear = 0.0F;
            }
        } else if (protectedBlock) {
            clear = INF;
        } else {
            clear = Digging.digTicks(pick, attack, state, hardness) + DIG_OVERHEAD;
        }
        byte support;
        if (collision && !hazard) {
            support = SUPPORT_SOLID;
        } else if (canBuild && !protectedBlock) {
            support = SUPPORT_PLACEABLE; // air, water, lava, plants ... a siege stone goes there
        } else {
            support = SUPPORT_NONE;
        }
        return new Cell(clear, support, state.is(ModBlocks.SIEGE_STONE.get()));
    }

    /**
     * Cost of crossing a fluid cell: place a siege stone (and one for every fluid neighbour, so nothing flows back
     * in), then dig the stone out.
     */
    private float fillCost(BlockPos pos) {
        BlockState stone = ModBlocks.SIEGE_STONE.get().defaultBlockState();
        float dig = Digging.digTicks(pick, attack, stone, stone.getDestroySpeed(level, pos)) + DIG_OVERHEAD;
        int fluidNeighbours = 0;
        for (BlockPos neighbour : sealNeighbours(pos)) {
            if (level.isLoaded(neighbour) && !level.getFluidState(neighbour).isEmpty()) {
                fluidNeighbours++;
            }
        }
        return PLACE_OVERHEAD + dig + fluidNeighbours * (PLACE_OVERHEAD * 0.5F);
    }

    /** The cells fluid could flow into {@code pos} from: the four sides and above. */
    private static BlockPos[] sealNeighbours(BlockPos pos) {
        return new BlockPos[]{pos.north(), pos.south(), pos.east(), pos.west(), pos.above()};
    }

    /**
     * Cells next to the cells a step needs free: if they hold fluid, the builders fill them first (a dam), so the
     * dug-out way does not flood again.
     */
    private BlockPos[] seals(BlockPos node, BlockPos[] clears) {
        LongOpenHashSet inside = new LongOpenHashSet();
        inside.add(node.asLong());
        inside.add(node.above().asLong());
        for (BlockPos cell : clears) {
            inside.add(cell.asLong());
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        List<BlockPos> result = new ArrayList<>();
        for (long key : inside) {
            for (BlockPos neighbour : sealNeighbours(BlockPos.of(key))) {
                long neighbourKey = neighbour.asLong();
                if (inside.contains(neighbourKey) || neighbour.equals(core) || !seen.add(neighbourKey)) {
                    continue;
                }
                result.add(neighbour.immutable());
            }
        }
        return result.toArray(new BlockPos[0]);
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    private void finish(Node assembly, long now) {
        List<Node> path = new ArrayList<>();
        for (Node n = assembly; n != null; n = n.next) {
            path.add(n);
        }
        int size = path.size();
        BlockPos[] positions = new BlockPos[size];
        BlockPos[][] clears = new BlockPos[size][];
        BlockPos[] supports = new BlockPos[size];
        for (int i = 0; i < size; i++) {
            Node n = path.get(i);
            positions[i] = new BlockPos(n.x, n.y, n.z);
            if (i == 0) {
                clears[i] = new BlockPos[0];
                supports[i] = null; // natural ground
            } else {
                long[] move = path.get(i - 1).moveClears;
                clears[i] = new BlockPos[move.length];
                for (int j = 0; j < move.length; j++) {
                    clears[i][j] = BlockPos.of(move[j]);
                }
                supports[i] = BlockPos.of(n.support);
            }
        }
        BlockPos[][] seals = new BlockPos[size][];
        for (int i = 0; i < size; i++) {
            seals[i] = i == 0 ? new BlockPos[0] : seals(positions[i], clears[i]);
        }
        Route route = new Route(core, positions, clears, supports, seals, now);
        Direction side = Direction.from2DDataValue(path.get(size - 1).side);
        RouteSets sets = routeSets(route);
        List<BlockPos> platform = platform(route, side, sets);
        List<SiegeFront.Landing> landings = landings(route, sets, platform);
        route.setWings(wings(route, platform, landings));
        result = new Result(route, side, platform, landings);
        done = true;
    }

    private static RouteSets routeSets(Route route) {
        LongOpenHashSet clear = new LongOpenHashSet();
        LongOpenHashSet support = new LongOpenHashSet();
        LongOpenHashSet nodes = new LongOpenHashSet();
        for (int i = 0; i < route.size(); i++) {
            BlockPos node = route.node(i);
            nodes.add(node.asLong());
            clear.add(node.asLong());
            clear.add(node.above().asLong());
            for (BlockPos cell : route.clears(i)) {
                clear.add(cell.asLong());
            }
            BlockPos floor = route.support(i);
            if (floor != null) {
                support.add(floor.asLong());
            }
        }
        return new RouteSets(clear, support, nodes);
    }

    /**
     * Extra columns next to every stair block the siege has to build, so the stairs are two or three blocks wide
     * ({@code stairWidth}; more room, mobs fall off less and can pass each other). The builder places them together
     * with the stair block. Cells this route, its platform or another front need free are left out. With width 2
     * the extra column stays on one side where possible; with width 3 both sides get one.
     */
    private BlockPos[][] wings(Route route, List<BlockPos> platform, List<SiegeFront.Landing> landings) {
        LongOpenHashSet keepFree = new LongOpenHashSet();
        keepFree.add(core.asLong());
        for (int i = 0; i < route.size(); i++) {
            keepFree.add(route.node(i).asLong());
            keepFree.add(route.node(i).above().asLong());
            for (BlockPos cell : route.clears(i)) {
                keepFree.add(cell.asLong());
            }
        }
        for (BlockPos spot : platform) {
            keepFree.add(spot.asLong());
            keepFree.add(spot.above().asLong());
        }
        for (SiegeFront.Landing landing : landings) {
            for (BlockPos spot : landing.spots()) {
                keepFree.add(spot.asLong());
                keepFree.add(spot.above().asLong());
            }
        }
        BlockPos[][] wings = new BlockPos[route.size()][];
        int width = Config.STAIR_WIDTH.get();
        if (width <= 1) {
            return wings;
        }
        BlockPos[][] pads = cornerPads(route, keepFree);
        Direction preferred = null;
        for (int i = 1; i < route.size(); i++) {
            BlockPos support = route.support(i);
            if (support == null || cell(support.getX(), support.getY(), support.getZ()).support() != SUPPORT_PLACEABLE) {
                continue; // natural ground there: nothing to build
            }
            Direction move = horizontal(route.node(i - 1), route.node(i));
            if (move == null) {
                continue;
            }
            Direction first = move.getClockWise();
            Direction second = move.getCounterClockWise();
            if (width >= 3) {
                java.util.List<BlockPos> both = new java.util.ArrayList<>(2);
                for (Direction perp : new Direction[]{first, second}) {
                    BlockPos wing = support.relative(perp);
                    long key = wing.asLong();
                    if (keepFree.contains(key) || avoidClear.contains(key)) {
                        continue;
                    }
                    if (cell(wing.getX(), wing.getY(), wing.getZ()).support() == SUPPORT_PLACEABLE) {
                        both.add(wing.immutable());
                    }
                }
                if (!both.isEmpty()) {
                    wings[i] = both.toArray(new BlockPos[0]);
                }
                continue;
            }
            if (preferred == second) {
                first = second;
                second = move.getClockWise();
            }
            for (Direction perp : new Direction[]{first, second}) {
                BlockPos wing = support.relative(perp);
                long key = wing.asLong();
                if (keepFree.contains(key) || avoidClear.contains(key)) {
                    continue;
                }
                byte wingSupport = cell(wing.getX(), wing.getY(), wing.getZ()).support();
                if (wingSupport == SUPPORT_SOLID) {
                    preferred = perp; // already wide there
                    break;
                }
                if (wingSupport == SUPPORT_PLACEABLE) {
                    wings[i] = new BlockPos[]{wing.immutable()};
                    preferred = perp;
                    break;
                }
            }
        }
        for (int i = 0; i < wings.length; i++) {
            if (pads[i] != null) {
                BlockPos[] own = wings[i] == null ? new BlockPos[0] : wings[i];
                BlockPos[] all = java.util.Arrays.copyOf(own, own.length + pads[i].length);
                System.arraycopy(pads[i], 0, all, own.length, pads[i].length);
                wings[i] = all;
            }
        }
        return withoutBlockedWings(route, wings);
    }

    /**
     * Landing at every turn of the stairs (like the landing of a stairwell): the cells beside and behind the corner,
     * at the height of the corner's floor, so several mobs fit on the turn and the next flight starts from a
     * platform. Only free, placeable cells that nothing of the route needs.
     */
    private BlockPos[][] cornerPads(Route route, LongOpenHashSet keepFree) {
        BlockPos[][] pads = new BlockPos[route.size()][];
        for (int i = 1; i < route.size() - 1; i++) {
            BlockPos support = route.support(i);
            if (support == null || cell(support.getX(), support.getY(), support.getZ()).support() != SUPPORT_PLACEABLE) {
                continue;
            }
            Direction in = horizontal(route.node(i - 1), route.node(i));
            Direction out = horizontal(route.node(i), route.node(i + 1));
            if (in == null || out == null || in == out) {
                continue;
            }
            List<BlockPos> cells = new ArrayList<>(5);
            // Ahead of the corner (the way the mob came from is behind it): the cell ahead and the cells beside it.
            for (int along = 0; along <= 1; along++) {
                for (int across = -1; across <= 1; across++) {
                    if (along == 0 && across == 0) {
                        continue;
                    }
                    BlockPos pad = support.relative(in, along).relative(in.getClockWise(), across);
                    long key = pad.asLong();
                    if (keepFree.contains(key) || avoidClear.contains(key)
                            || cell(pad.getX(), pad.getY(), pad.getZ()).support() != SUPPORT_PLACEABLE) {
                        continue;
                    }
                    cells.add(pad.immutable());
                }
            }
            if (!cells.isEmpty()) {
                pads[i] = cells.toArray(new BlockPos[0]);
            }
        }
        return pads;
    }

    /**
     * Drops extra columns that would stand in the way: a mob on the column needs the two cells above it free, so a
     * column under a stair block of the route (the stairs turn above it) or under another extra column is no walkable
     * floor but a wall, and one that sits in another column's headroom would block that one. Such stacked "bars"
     * only get in each other's way, so they are not built.
     */
    private static BlockPos[][] withoutBlockedWings(Route route, BlockPos[][] wings) {
        LongOpenHashSet solid = new LongOpenHashSet();
        for (int i = 0; i < route.size(); i++) {
            BlockPos support = route.support(i);
            if (support != null) {
                solid.add(support.asLong());
            }
        }
        LongOpenHashSet placed = new LongOpenHashSet();
        for (int i = 0; i < wings.length; i++) {
            if (wings[i] == null) {
                continue;
            }
            List<BlockPos> kept = new ArrayList<>(2);
            for (BlockPos wing : wings[i]) {
                if (solid.contains(wing.asLong())) {
                    continue; // already a block of the route (or another extra column)
                }
                if (solid.contains(wing.above().asLong()) || solid.contains(wing.above(2).asLong())
                        || placed.contains(wing.below().asLong()) || placed.contains(wing.below(2).asLong())) {
                    continue;
                }
                kept.add(wing);
                solid.add(wing.asLong());
                placed.add(wing.asLong());
            }
            wings[i] = kept.isEmpty() ? null : kept.toArray(new BlockPos[0]);
        }
        return wings;
    }

    @Nullable
    private static Direction horizontal(BlockPos from, BlockPos to) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        if (dx != 0 && dz == 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0 && dx == 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }

    /**
     * Can a mob stand here once a floor is placed: the cell and the one above are free (nothing to dig), the floor
     * below is solid or can be placed, and the cell is in nobody's way (this route, other fronts)?
     */
    private boolean usableSpot(BlockPos spot, RouteSets sets) {
        return usableSpot(spot, sets, false);
    }

    /**
     * Like {@link #usableSpot(BlockPos, RouteSets)}; with {@code allowDig} the spot may also be solid wall that the
     * siege can dig out (not fluid, not protected): extra access next to the attack position.
     */
    private boolean usableSpot(BlockPos spot, RouteSets sets, boolean allowDig) {
        long below = spot.below().asLong();
        if (sets.clear().contains(below) || sets.support().contains(spot.asLong())
                || sets.support().contains(spot.above().asLong()) || sets.nodes().contains(spot.asLong())) {
            return false;
        }
        if (avoidClear.contains(below) || avoidSupport.contains(spot.asLong())
                || avoidSupport.contains(spot.above().asLong())) {
            return false; // another front's way
        }
        float own = cell(spot.getX(), spot.getY(), spot.getZ()).clear();
        float upper = cell(spot.getX(), spot.getY() + 1, spot.getZ()).clear();
        if (allowDig) {
            if (own == INF || upper == INF
                    || !level.getFluidState(spot).isEmpty() || !level.getFluidState(spot.above()).isEmpty()) {
                return false;
            }
        } else if (own != 0.0F || upper != 0.0F) {
            return false;
        }
        return cell(spot.getX(), spot.getY() - 1, spot.getZ()).support() != SUPPORT_NONE;
    }

    /**
     * Extra standing room in front of the attack position (up to 5 wide, 3 deep), so more attackers can hit the
     * Core at once and cannot be knocked off the stairs. Only free cells count: nothing is dug for the platform
     * (a Core walled in obsidian gets its platform in front of the hole instead, see {@link #landings}).
     */
    private List<BlockPos> platform(Route route, Direction side, RouteSets sets) {
        BlockPos attackPos = route.node(route.last());
        Direction left = side.getClockWise();
        List<BlockPos> platform = new ArrayList<>();
        int widened = 0;
        for (int along = 0; along <= 2; along++) {
            for (int across : new int[]{0, -1, 1, -2, 2}) {
                if (along == 0 && across == 0) {
                    continue;
                }
                BlockPos spot = attackPos.relative(side, along).relative(left, across);
                if (usableSpot(spot, sets)) {
                    platform.add(spot.immutable());
                } else if (Config.WIDEN_ACCESS.get() && widened < MAX_WIDE_SPOTS && along <= 1 && Math.abs(across) <= 1
                        && usableSpot(spot, sets, true)) {
                    // Wall right next to the attack position (the Core's shell): waiting mobs dig it out for more access.
                    platform.add(spot.immutable());
                    widened++;
                }
            }
        }
        return platform;
    }

    private static final int MAX_LANDINGS = 6;
    /** At most this many wall cells next to the attack position are dug out as extra access. */
    private static final int MAX_WIDE_SPOTS = 4;

    /**
     * Landings: wide platforms beside the stairs (up to 2 cells on each side), every few steps, the last one close
     * below the attack position. They give attackers room, and archers firing positions and posts. Only on steps
     * that are built (not on natural ground or in a hole in a wall); steps where nothing fits are skipped.
     */
    private List<SiegeFront.Landing> landings(Route route, RouteSets sets, List<BlockPos> platform) {
        List<SiegeFront.Landing> result = new ArrayList<>();
        if (!canBuild || !Config.LANDINGS_ENABLED.get()) {
            return result;
        }
        LongOpenHashSet taken = new LongOpenHashSet();
        for (BlockPos spot : platform) {
            taken.add(spot.asLong());
        }
        int spacing = Config.LANDING_SPACING.get();
        int index = route.last() - 2;
        while (index >= 2 && result.size() < MAX_LANDINGS) {
            SiegeFront.Landing landing = landingAt(route, index, sets, taken);
            if (landing != null) {
                result.add(landing);
                for (BlockPos spot : landing.spots()) {
                    taken.add(spot.asLong());
                }
                index -= spacing;
            } else {
                index--;
            }
        }
        java.util.Collections.reverse(result); // lowest first: that is the order they get built in
        return result;
    }

    @Nullable
    private SiegeFront.Landing landingAt(Route route, int index, RouteSets sets, LongOpenHashSet taken) {
        BlockPos floor = route.support(index);
        if (floor == null || cell(floor.getX(), floor.getY(), floor.getZ()).support() != SUPPORT_PLACEABLE) {
            return null; // natural ground (or a wall) there: nothing to build on
        }
        Direction move = horizontal(route.node(index - 1), route.node(index));
        if (move == null) {
            return null;
        }
        Direction across = move.getClockWise();
        BlockPos node = route.node(index);
        List<BlockPos> spots = new ArrayList<>();
        for (int k : new int[]{1, -1, 2, -2}) {
            BlockPos spot = node.relative(across, k);
            if (!taken.contains(spot.asLong()) && usableSpot(spot, sets)) {
                spots.add(spot.immutable());
            }
        }
        return spots.size() >= 2 ? new SiegeFront.Landing(index, spots) : null;
    }

    // ------------------------------------------------------------------
    // Checks against the live world (used while mobs follow a route)
    // ------------------------------------------------------------------

    static boolean isPassable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && !state.getFluidState().is(FluidTags.LAVA)
                && !Digging.isHazard(state);
    }

    /** Is there any fluid (water, lava ...) in this cell? Such cells are filled with siege stone before they are dug. */
    static boolean hasFluid(Level level, BlockPos pos) {
        return !level.getFluidState(pos).isEmpty();
    }

    static boolean isSupport(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.getCollisionShape(level, pos).isEmpty() && !Digging.isHazard(state);
    }

    /** Can the siege remove what blocks this position? */
    static boolean isDiggable(Level level, BlockPos pos, BlockPos core) {
        if (pos.equals(core)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getCollisionShape(level, pos).isEmpty()) {
            return !state.getFluidState().is(FluidTags.LAVA);
        }
        return !(state.getBlock() instanceof CoreBlock) && !state.is(Digging.SIEGE_PROOF)
                && state.getDestroySpeed(level, pos) >= 0;
    }

    /** Can a builder put a floor block here? */
    static boolean canPlace(Level level, BlockPos pos, BlockPos core) {
        if (!Config.BUILDING_ENABLED.get() || pos.equals(core) || level.isOutsideBuildHeight(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return !(state.getBlock() instanceof CoreBlock) && !state.is(Digging.SIEGE_PROOF)
                && state.getDestroySpeed(level, pos) >= 0;
    }
}
