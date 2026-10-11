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

    /** A finished plan: the way (assembly point first) and the extra platform cells next to the attack position. */
    public record Result(Route route, Direction side, List<BlockPos> platform) {
    }

    private final ServerLevel level;
    private final BlockPos core;
    private final int bound;
    private final double assemblyDistance;
    private final List<BlockPos> wards;
    private final int wardRadius;
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

    /** Natural ground at the surface (or in a cave at about the height of an underground Core), far enough out. */
    private boolean isAssembly(Node n) {
        if (n.next == null) {
            return false;
        }
        double dx = n.x - core.getX();
        double dz = n.z - core.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < assemblyDistance || Math.abs(dx) > bound - 2 || Math.abs(dz) > bound - 2) {
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
            if (wx * wx + wz * wz <= (double) wardRadius * wardRadius) {
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
        float horizontal = (float) Math.max(0.0, assemblyDistance - Math.sqrt(dx * dx + dz * dz)) * WALK;
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
            float turn = c.dir >= 0 && c.dir != dir ? TURN : 0.0F;

            // Walk: P at the same height.
            long[] flat = {key(c.x, c.y + 1, c.z), key(c.x, c.y, c.z)};
            float clearFlat = clearCost(flat);
            if (clearFlat < INF) {
                tryPush(c, px, c.y, pz, WALK + turn + clearFlat, flat, dir);
            }

            // Step up (P one lower): the mob jumps from P onto c, whose floor is the block in front of P.
            long[] up = {key(px, c.y + 1, pz), key(c.x, c.y + 1, c.z), key(c.x, c.y, c.z)};
            float clearUp = clearCost(up);
            if (clearUp < INF) {
                tryPush(c, px, c.y - 1, pz, WALK + UP_EXTRA + turn + clearUp, up, dir);
            }

            // Step down (P one higher).
            long[] down = {key(c.x, c.y + 2, c.z), key(c.x, c.y + 1, c.z), key(c.x, c.y, c.z)};
            float clearDown = clearCost(down);
            if (clearDown < INF) {
                tryPush(c, px, c.y + 1, pz, WALK + turn + clearDown, down, dir);
            }

            // Drop down 2-3 blocks onto natural ground (nothing to dig or build).
            if (cell(c.x, c.y - 1, c.z).support() == SUPPORT_SOLID) {
                for (int k = 2; k <= MAX_DROP; k++) {
                    long[] column = new long[k + 2];
                    for (int j = 0; j < k + 2; j++) {
                        column[j] = key(c.x, c.y + k + 1 - j, c.z);
                    }
                    if (clearCost(column) != 0.0F) {
                        break;
                    }
                    tryPush(c, px, c.y + k, pz, WALK + k + turn, column, dir);
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
    private void tryPush(Node c, int px, int py, int pz, float cost, long[] moveClears, int dir) {
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
        node.side = c.side;
        node.moveClears = moveClears;
        node.support = supportKey;
        open.add(new Entry(node, node.g + node.h * HEURISTIC_WEIGHT));
    }

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
        boolean lava = state.getFluidState().is(FluidTags.LAVA);
        boolean collision = !state.getCollisionShape(level, pos).isEmpty();
        float hardness = state.getDestroySpeed(level, pos);
        boolean protectedBlock = pos.equals(core) || state.getBlock() instanceof CoreBlock
                || state.is(Digging.SIEGE_PROOF) || hardness < 0;
        float clear;
        if (!collision) {
            clear = lava ? INF : (state.getFluidState().isEmpty() ? 0.0F : 3.0F);
        } else if (protectedBlock) {
            clear = INF;
        } else {
            clear = Digging.digTicks(pick, attack, state, hardness) + DIG_OVERHEAD;
        }
        byte support;
        if (collision) {
            support = SUPPORT_SOLID;
        } else if (canBuild && !protectedBlock) {
            support = SUPPORT_PLACEABLE; // air, water, lava, plants ... a siege stone goes there
        } else {
            support = SUPPORT_NONE;
        }
        return new Cell(clear, support, state.is(ModBlocks.SIEGE_STONE.get()));
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
        Route route = new Route(core, positions, clears, supports, now);
        Direction side = Direction.from2DDataValue(path.get(size - 1).side);
        List<BlockPos> platform = platform(route, side);
        route.setWings(wings(route, platform));
        result = new Result(route, side, platform);
        done = true;
    }

    /**
     * A second column next to every stair block the siege has to build, so the stairs are two blocks wide
     * (more room, mobs fall off less). The builder places it together with the stair block. Cells this route,
     * its platform or another front need free are left out, and the second column stays on one side where possible.
     */
    private BlockPos[] wings(Route route, List<BlockPos> platform) {
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
        BlockPos[] wings = new BlockPos[route.size()];
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
                    wings[i] = wing.immutable();
                    preferred = perp;
                    break;
                }
            }
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
     * Extra standing room next to the attack position (left, right and behind it), so more attackers can hit
     * the Core at once. Cells that would get in the way of the route are left out.
     */
    private List<BlockPos> platform(Route route, Direction side) {
        LongOpenHashSet routeClear = new LongOpenHashSet();
        LongOpenHashSet routeSupport = new LongOpenHashSet();
        for (int i = 0; i < route.size(); i++) {
            routeClear.add(route.node(i).asLong());
            routeClear.add(route.node(i).above().asLong());
            for (BlockPos cell : route.clears(i)) {
                routeClear.add(cell.asLong());
            }
            BlockPos support = route.support(i);
            if (support != null) {
                routeSupport.add(support.asLong());
            }
        }
        BlockPos attackPos = route.node(route.last());
        Direction left = side.getClockWise();
        // A 3 x 3 area in front of the Core (the attack position in the middle of its first row).
        List<BlockPos> candidates = new ArrayList<>();
        for (int along = 0; along <= 2; along++) {
            for (int across : new int[]{0, -1, 1}) {
                if (along != 0 || across != 0) {
                    candidates.add(attackPos.relative(side, along).relative(left, across));
                }
            }
        }
        List<BlockPos> platform = new ArrayList<>();
        for (BlockPos spot : candidates) {
            if (routeClear.contains(spot.below().asLong()) || routeSupport.contains(spot.asLong())
                    || routeSupport.contains(spot.above().asLong())) {
                continue;
            }
            boolean onRoute = false;
            for (int i = 0; i < route.size(); i++) {
                if (route.node(i).equals(spot)) {
                    onRoute = true;
                    break;
                }
            }
            if (onRoute) {
                continue;
            }
            if (avoidClear.contains(spot.below().asLong()) || avoidSupport.contains(spot.asLong())
                    || avoidSupport.contains(spot.above().asLong())) {
                continue; // another front's way
            }
            if (cell(spot.getX(), spot.getY(), spot.getZ()).clear() == INF
                    || cell(spot.getX(), spot.getY() + 1, spot.getZ()).clear() == INF
                    || cell(spot.getX(), spot.getY() - 1, spot.getZ()).support() == SUPPORT_NONE) {
                continue;
            }
            platform.add(spot.immutable());
        }
        return platform;
    }

    // ------------------------------------------------------------------
    // Checks against the live world (used while mobs follow a route)
    // ------------------------------------------------------------------

    static boolean isPassable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && !state.getFluidState().is(FluidTags.LAVA);
    }

    static boolean isSupport(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.getCollisionShape(level, pos).isEmpty();
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
