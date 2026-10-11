package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.block.CoreBlock;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Finds the cheapest way for the siege to reach the Core (A* search on block positions).
 * <p>
 * Every step costs walking time, plus the time to dig the blocks in the way (soft blocks are cheap,
 * obsidian is very expensive) and, if there is no floor, the time to place a block. Allowed moves:
 * walk, step up one block (a stair step), step down one block, drop down 2-4 blocks, and climb straight up
 * on a siege ladder (builders place it). Everybody can follow every move, so the route is a lasting way for
 * the whole siege, not only for the builder.
 * <p>
 * A route never builds into its own way: a floor block is never placed where an earlier step of the same
 * route (or another active route) needs free space, and no block is dug that an earlier step stands on.
 * <p>
 * The goal is any spot from which a mob can hit an exposed face of the Core (next to it, on top, below).
 * If the Core cannot be reached within the search budget, the route leads to the closest position found;
 * mobs follow it and plan again from there.
 */
public final class RoutePlanner {
    static final float INF = Float.POSITIVE_INFINITY;
    static final float WALK = 5.0F;
    static final float UP_EXTRA = 3.0F;
    /** Climbing one block on a ladder (a bit more than a stair step, so stairs win when they also get closer). */
    static final float CLIMB = 9.0F;
    static final float DIG_OVERHEAD = 6.0F;
    static final float PLACE_OVERHEAD = 15.0F;
    static final float HEURISTIC_WEIGHT = 1.5F;
    /** How many earlier steps are checked so a route does not block itself (spiral stairs, zig-zags). */
    private static final int CONFLICT_DEPTH = 24;

    static final byte MOVE_START = 0;
    static final byte MOVE_FLAT = 1;
    static final byte MOVE_UP = 2;
    static final byte MOVE_DOWN = 3;
    static final byte MOVE_DROP = 4;
    static final byte MOVE_CLIMB = 5;

    private static final byte SUPPORT_SOLID = 0;
    private static final byte SUPPORT_PLACEABLE = 1;
    private static final byte SUPPORT_NONE = 2;

    /**
     * @param clear     cost to make the cell passable (0 = already free, INF = impossible)
     * @param support   can a mob stand on it (solid), could a block be placed there, or neither
     * @param climbable a ladder (or vine ...) without collision is already there
     * @param ladderOk  a siege ladder may be placed here
     */
    private record Cell(float clear, byte support, boolean climbable, boolean ladderOk) {
    }

    private static final Cell BLOCKED = new Cell(INF, SUPPORT_NONE, false, false);
    private static final Cell OPEN_SKY = new Cell(0.0F, SUPPORT_NONE, false, false);
    private static final long NO_CELL = Long.MIN_VALUE;
    private static final long[] NO_CELLS = new long[0];

    private static final class Node {
        final int x;
        final int y;
        final int z;
        float g = INF;
        float h;
        @Nullable
        Node parent;
        byte move;
        boolean closed;
        /** Cells this step needs free (feet, head and everything dug for the move). */
        long[] clears = NO_CELLS;
        /** The cell this step stands on, if it is a block (not a ladder). */
        long support = NO_CELL;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private record Entry(Node node, float f) {
    }

    private final ServerLevel level;
    private final BlockPos core;
    private final int bound;
    private final boolean canBuild;
    private final boolean canLadder;
    private final float placeCost;
    private final float climbHeuristic;
    private final ItemStack pick;
    private final double attack;
    private final LongOpenHashSet reservedClear;
    private final LongOpenHashSet reservedSupport;
    private int minY;
    private int maxY;
    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Float.compare(a.f(), b.f()));

    private RoutePlanner(ServerLevel level, BlockPos core, int coreLevel, int bound, long now) {
        this.level = level;
        this.core = core;
        this.bound = bound;
        this.canBuild = Config.BUILDING_ENABLED.get() && Config.BUILDER_WEIGHT.get() > 0 && Config.ZOMBIE_ROLES.get();
        this.canLadder = canBuild && Config.LADDERS_ENABLED.get();
        this.placeCost = Config.BUILD_TICKS.get() + PLACE_OVERHEAD;
        // Climbing usually needs building; counting part of it keeps the search from crawling around on the
        // ground under a high Core until the budget runs out.
        this.climbHeuristic = WALK + UP_EXTRA + (canBuild ? placeCost * 0.5F : 0.0F);
        this.pick = MobRoles.referencePickaxe(coreLevel);
        this.attack = 4.0 * (1.0 + Config.MOB_DAMAGE_BONUS_PER_LEVEL.get() * coreLevel);
        this.reservedClear = Route.reservedClear(core, now);
        this.reservedSupport = Route.reservedSupport(core, now);
    }

    /** Plans a route from {@code start} (feet position) to a spot next to the Core. Null if there is no way at all. */
    @Nullable
    public static Route plan(ServerLevel level, BlockPos start, BlockPos core, int coreLevel, int bound, long now) {
        return new RoutePlanner(level, core, coreLevel, bound, now).run(start, now);
    }

    @Nullable
    private Route run(BlockPos start, long now) {
        minY = Math.max(level.getMinBuildHeight() + 1, Math.min(core.getY(), start.getY()) - 48);
        maxY = Math.min(level.getMaxBuildHeight() - 1, Math.max(core.getY(), start.getY()) + 24);

        Node first = node(start.getX(), start.getY(), start.getZ());
        first.g = 0;
        first.h = heuristic(first.x, first.y, first.z);
        first.move = MOVE_START;
        first.clears = new long[]{BlockPos.asLong(first.x, first.y, first.z), BlockPos.asLong(first.x, first.y + 1, first.z)};
        open.add(new Entry(first, first.h * HEURISTIC_WEIGHT));

        Node best = first;
        Node goal = null;
        int budget = Config.PLANNER_MAX_NODES.get();
        int expanded = 0;
        while (!open.isEmpty() && expanded < budget) {
            Entry entry = open.poll();
            Node current = entry.node();
            if (current.closed || entry.f() != current.g + current.h * HEURISTIC_WEIGHT) {
                continue;
            }
            current.closed = true;
            expanded++;
            if (isGoal(current.x, current.y, current.z)) {
                goal = current;
                break;
            }
            if (current.h < best.h || (current.h == best.h && current.g < best.g)) {
                best = current;
            }
            expand(current);
        }
        Node end = goal != null ? goal : best;
        if (end == first) {
            return null; // no step gets closer (or already there)
        }
        return build(end, goal != null, now);
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /**
     * Feet positions from which a mob can hit the Core: next to it (feet or head at the Core's height),
     * on top of it, or right below it.
     */
    private boolean isGoal(int x, int y, int z) {
        int dx = x - core.getX();
        int dy = y - core.getY();
        int dz = z - core.getZ();
        if (dx == 0 && dz == 0) {
            return dy == 1 || dy == -2;
        }
        return Math.abs(dx) + Math.abs(dz) == 1 && (dy == 0 || dy == -1);
    }

    private float heuristic(int x, int y, int z) {
        int manhattan = Math.abs(x - core.getX()) + Math.abs(z - core.getZ());
        float horizontal = Math.max(0, manhattan - 1) * WALK;
        int dy = y - core.getY();
        float vertical = dy < 0
                ? Math.max(0, -dy - 1) * climbHeuristic // has to climb
                : Math.max(0, dy - 1) * 2.0F;          // has to go down
        return Math.max(horizontal, vertical);
    }

    private void expand(Node n) {
        int y = n.y;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int nx = n.x + direction.getStepX();
            int nz = n.z + direction.getStepZ();
            if (Math.abs(nx - core.getX()) > bound || Math.abs(nz - core.getZ()) > bound) {
                continue;
            }

            // Walk (dig the way free, place a floor if needed).
            Cell head = cell(nx, y + 1, nz);
            Cell feet = cell(nx, y, nz);
            Cell below = cell(nx, y - 1, nz);
            float clearFlat = head.clear() + feet.clear();
            if (clearFlat < INF) {
                float support = supportCost(below);
                if (support < INF) {
                    tryPush(n, nx, y, nz, WALK + clearFlat + support, MOVE_FLAT,
                            new int[]{nx, y + 1, nz, nx, y, nz}, below, nx, y - 1, nz, false);
                }
                // Drop down 2-4 blocks if the way down is open.
                if (clearFlat == 0 && below.clear() == 0 && below.support() != SUPPORT_SOLID) {
                    for (int k = 2; k <= 4; k++) {
                        Cell landing = cell(nx, y - k, nz);
                        if (landing.clear() != 0) {
                            break;
                        }
                        Cell ground = cell(nx, y - k - 1, nz);
                        if (ground.support() == SUPPORT_SOLID) {
                            int[] column = new int[(k + 2) * 3];
                            for (int j = 0; j < k + 2; j++) {
                                column[j * 3] = nx;
                                column[j * 3 + 1] = y + 1 - j;
                                column[j * 3 + 2] = nz;
                            }
                            tryPush(n, nx, y - k, nz, WALK + k, MOVE_DROP, column, ground, nx, y - k - 1, nz, false);
                            break;
                        }
                    }
                }
            }

            // Step up (a stair step: the block in front becomes the floor). Not from the top of a ladder:
            // there is no floor to jump from.
            float clearUp = n.move == MOVE_CLIMB ? INF
                    : cell(n.x, y + 2, n.z).clear() + cell(nx, y + 2, nz).clear() + head.clear();
            if (clearUp < INF) {
                float support = supportCost(feet);
                if (support < INF) {
                    tryPush(n, nx, y + 1, nz, WALK + UP_EXTRA + clearUp + support, MOVE_UP,
                            new int[]{n.x, y + 2, n.z, nx, y + 2, nz, nx, y + 1, nz}, feet, nx, y, nz, false);
                }
            }

            // Step down (digging a staircase down if needed).
            float clearDown = head.clear() + feet.clear() + below.clear();
            if (clearDown < INF) {
                Cell ground = cell(nx, y - 2, nz);
                float support = supportCost(ground);
                if (support < INF) {
                    tryPush(n, nx, y - 1, nz, WALK + clearDown + support, MOVE_DOWN,
                            new int[]{nx, y + 1, nz, nx, y, nz, nx, y - 1, nz}, ground, nx, y - 2, nz, false);
                }
            }
        }

        // Climb straight up on a ladder in the cell the mob stands in.
        Cell here = cell(n.x, y, n.z);
        float ladder = here.climbable() ? 0.0F : (canLadder && here.ladderOk() ? placeCost : INF);
        float clearAbove = cell(n.x, y + 2, n.z).clear();
        if (ladder < INF && clearAbove < INF) {
            tryPush(n, n.x, y + 1, n.z, CLIMB + clearAbove + ladder, MOVE_CLIMB,
                    new int[]{n.x, y + 2, n.z}, here, n.x, y, n.z, true);
        }
    }

    private float supportCost(Cell cell) {
        return switch (cell.support()) {
            case SUPPORT_SOLID -> 0.0F;
            case SUPPORT_PLACEABLE -> placeCost;
            default -> INF;
        };
    }

    /**
     * Pushes the step unless it would block the route itself or another route.
     *
     * @param clearCells cells (x, y, z triples) the move needs free
     * @param supportCell the cell under the new position (or the ladder cell for a climb)
     */
    private void tryPush(Node parent, int x, int y, int z, float cost, byte move, int[] clearCells,
                         Cell supportCell, int sx, int sy, int sz, boolean ladder) {
        if (y < minY || y > maxY) {
            return;
        }
        Node existing = nodes.get(BlockPos.asLong(x, y, z));
        if (existing != null && (existing.closed || parent.g + cost >= existing.g)) {
            return;
        }
        long[] clears = new long[clearCells.length / 3 + 2];
        for (int i = 0; i < clearCells.length / 3; i++) {
            clears[i] = BlockPos.asLong(clearCells[i * 3], clearCells[i * 3 + 1], clearCells[i * 3 + 2]);
        }
        clears[clears.length - 2] = BlockPos.asLong(x, y, z);
        clears[clears.length - 1] = BlockPos.asLong(x, y + 1, z);
        long support = ladder ? NO_CELL : BlockPos.asLong(sx, sy, sz);
        boolean placing = !ladder && supportCell.support() == SUPPORT_PLACEABLE;

        // Other routes: never build into their way, never dig away their floor.
        if (placing && reservedClear.contains(support)) {
            return;
        }
        for (long cell : clears) {
            if (reservedSupport.contains(cell) && cellNeedsDigging(cell)) {
                return;
            }
        }
        // This route: the same rules against its own earlier steps.
        int depth = 0;
        for (Node a = parent; a != null && depth < CONFLICT_DEPTH; a = a.parent, depth++) {
            if (support != NO_CELL) {
                for (long cell : a.clears) {
                    if (cell == support) {
                        return;
                    }
                }
            }
            if (a.support != NO_CELL) {
                for (long cell : clears) {
                    if (cell == a.support) {
                        return;
                    }
                }
            }
        }

        Node node = existing != null ? existing : node(x, y, z);
        node.g = parent.g + cost;
        node.h = heuristic(x, y, z);
        node.parent = parent;
        node.move = move;
        node.clears = clears;
        node.support = support;
        open.add(new Entry(node, node.g + node.h * HEURISTIC_WEIGHT));
    }

    private boolean cellNeedsDigging(long key) {
        return cell(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key)).clear() > 0.0F;
    }

    private Node node(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Node node = nodes.get(key);
        if (node == null) {
            node = new Node(x, y, z);
            nodes.put(key, node);
        }
        return node;
    }

    private Cell cell(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
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
        if (pos.getY() < minY - 2 || pos.getY() < level.getMinBuildHeight() || !level.isLoaded(pos)) {
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
        if (collision || state.getFluidState().is(FluidTags.WATER)) {
            support = SUPPORT_SOLID;
        } else if (canBuild && !protectedBlock) {
            support = SUPPORT_PLACEABLE;
        } else {
            support = SUPPORT_NONE;
        }
        boolean climbable = !collision && state.is(BlockTags.CLIMBABLE);
        boolean ladderOk = !protectedBlock && !lava;
        return new Cell(clear, support, climbable, ladderOk);
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    private Route build(Node end, boolean complete, long now) {
        List<Node> path = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            path.add(n);
        }
        Collections.reverse(path);
        int size = path.size();
        BlockPos[] positions = new BlockPos[size];
        BlockPos[][] clears = new BlockPos[size][];
        BlockPos[] supports = new BlockPos[size];
        byte[] kinds = new byte[size];
        for (int i = 0; i < size; i++) {
            Node n = path.get(i);
            positions[i] = new BlockPos(n.x, n.y, n.z);
            supports[i] = new BlockPos(n.x, n.y - 1, n.z);
            kinds[i] = n.move == MOVE_CLIMB ? Route.SUPPORT_LADDER : Route.SUPPORT_BLOCK;
            Node p = n.parent;
            clears[i] = switch (n.move) {
                case MOVE_FLAT -> new BlockPos[]{new BlockPos(n.x, n.y + 1, n.z), new BlockPos(n.x, n.y, n.z)};
                case MOVE_UP -> new BlockPos[]{new BlockPos(p.x, p.y + 2, p.z),
                        new BlockPos(n.x, n.y + 1, n.z), new BlockPos(n.x, n.y, n.z)};
                case MOVE_DOWN -> new BlockPos[]{new BlockPos(n.x, n.y + 2, n.z),
                        new BlockPos(n.x, n.y + 1, n.z), new BlockPos(n.x, n.y, n.z)};
                case MOVE_DROP -> {
                    BlockPos[] column = new BlockPos[p.y + 1 - n.y + 1];
                    for (int j = 0; j < column.length; j++) {
                        column[j] = new BlockPos(n.x, p.y + 1 - j, n.z);
                    }
                    yield column;
                }
                case MOVE_CLIMB -> new BlockPos[]{new BlockPos(n.x, n.y + 1, n.z)};
                default -> new BlockPos[0];
            };
            if (n.move == MOVE_START) {
                supports[i] = null;
                kinds[i] = Route.SUPPORT_NONE;
            }
        }
        return new Route(core, positions, clears, supports, kinds, complete, now);
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
        return !state.getCollisionShape(level, pos).isEmpty() || state.getFluidState().is(FluidTags.WATER);
    }

    /** A ladder (or vine ...) a mob can climb, without collision. */
    static boolean isClimbable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.CLIMBABLE) && state.getCollisionShape(level, pos).isEmpty();
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

    /** Can a builder put a floor block (or a ladder) here? */
    static boolean canPlace(Level level, BlockPos pos, BlockPos core) {
        if (!Config.BUILDING_ENABLED.get() || pos.equals(core) || level.isOutsideBuildHeight(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return !(state.getBlock() instanceof CoreBlock) && !state.is(Digging.SIEGE_PROOF)
                && state.getDestroySpeed(level, pos) >= 0;
    }
}
