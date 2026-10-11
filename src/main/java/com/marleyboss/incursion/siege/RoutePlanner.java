package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.block.CoreBlock;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
 * walk, step up one block (a stair step), step down one block, drop down 2-4 blocks. There are no
 * straight vertical moves, so the siege builds stairs and bridges instead of pillars.
 * <p>
 * If the Core cannot be reached within the search budget, the route leads to the closest position found;
 * mobs follow it and plan again from there.
 */
public final class RoutePlanner {
    static final float INF = Float.POSITIVE_INFINITY;
    static final float WALK = 5.0F;
    static final float UP_EXTRA = 3.0F;
    static final float DIG_OVERHEAD = 6.0F;
    static final float PLACE_OVERHEAD = 15.0F;
    static final float HEURISTIC_WEIGHT = 1.5F;

    static final byte MOVE_START = 0;
    static final byte MOVE_FLAT = 1;
    static final byte MOVE_UP = 2;
    static final byte MOVE_DOWN = 3;
    static final byte MOVE_DROP = 4;

    private static final byte SUPPORT_SOLID = 0;
    private static final byte SUPPORT_PLACEABLE = 1;
    private static final byte SUPPORT_NONE = 2;

    private record Cell(float clear, byte support) {
    }

    private static final Cell BLOCKED = new Cell(INF, SUPPORT_NONE);

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
    private final int minY;
    private final int maxY;
    private final boolean canBuild;
    private final float placeCost;
    private final ItemStack pick;
    private final double attack;
    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Float.compare(a.f(), b.f()));

    private RoutePlanner(ServerLevel level, BlockPos core, int coreLevel, int bound) {
        this.level = level;
        this.core = core;
        this.bound = bound;
        this.minY = Math.max(level.getMinBuildHeight() + 1, core.getY() - 96);
        this.maxY = Math.min(level.getMaxBuildHeight() - 3, core.getY() + 96);
        this.canBuild = Config.BUILDING_ENABLED.get() && Config.BUILDER_WEIGHT.get() > 0 && Config.ZOMBIE_ROLES.get();
        this.placeCost = Config.BUILD_TICKS.get() + PLACE_OVERHEAD;
        this.pick = MobRoles.referencePickaxe(coreLevel);
        this.attack = 4.0 * (1.0 + Config.MOB_DAMAGE_BONUS_PER_LEVEL.get() * coreLevel);
    }

    /** Plans a route from {@code start} (feet position) to a spot next to the Core. Null if there is no way at all. */
    @Nullable
    public static Route plan(ServerLevel level, BlockPos start, BlockPos core, int coreLevel, int bound, long now) {
        return new RoutePlanner(level, core, coreLevel, bound).run(start, now);
    }

    @Nullable
    private Route run(BlockPos start, long now) {
        Node first = node(start.getX(), start.getY(), start.getZ());
        first.g = 0;
        first.h = heuristic(first.x, first.y, first.z);
        first.move = MOVE_START;
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
            if (goal == null) {
                return null;
            }
            // Already next to the Core but cannot hit it: only the block on top of the Core is in the way.
            BlockPos here = start.immutable();
            return new Route(core, new BlockPos[]{here, here}, new BlockPos[][]{new BlockPos[0], new BlockPos[]{core.above()}},
                    new BlockPos[]{null, null}, true, now);
        }
        return build(end, goal != null, now);
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    private boolean isGoal(int x, int y, int z) {
        int dx = x - core.getX();
        int dy = y - core.getY();
        int dz = z - core.getZ();
        if (dx == 0 && dz == 0) {
            return dy == 1; // standing on top of the Core
        }
        return Math.abs(dx) + Math.abs(dz) == 1 && dy == 0;
    }

    private float heuristic(int x, int y, int z) {
        int manhattan = Math.abs(x - core.getX()) + Math.abs(z - core.getZ());
        float horizontal = Math.max(0, manhattan - 1) * WALK;
        int dy = y - core.getY();
        float vertical = dy < 0
                ? Math.max(0, -dy - 1) * (WALK + UP_EXTRA) // has to climb
                : Math.max(0, dy - 1) * 2.0F;               // has to go down
        return Math.max(horizontal, vertical);
    }

    private void expand(Node n) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int nx = n.x + direction.getStepX();
            int nz = n.z + direction.getStepZ();
            if (Math.abs(nx - core.getX()) > bound || Math.abs(nz - core.getZ()) > bound) {
                continue;
            }
            int y = n.y;

            // Walk (dig the way free, place a floor if needed).
            float clearFlat = cell(nx, y + 1, nz).clear() + cell(nx, y, nz).clear();
            Cell below = cell(nx, y - 1, nz);
            if (clearFlat < INF) {
                float support = supportCost(below);
                if (support < INF) {
                    push(n, nx, y, nz, WALK + clearFlat + support, MOVE_FLAT);
                }
                // Drop down 2-4 blocks if the way down is open.
                if (clearFlat == 0 && below.clear() == 0 && below.support() != SUPPORT_SOLID) {
                    for (int k = 2; k <= 4; k++) {
                        Cell feet = cell(nx, y - k, nz);
                        if (feet.clear() != 0) {
                            break;
                        }
                        if (cell(nx, y - k - 1, nz).support() == SUPPORT_SOLID) {
                            push(n, nx, y - k, nz, WALK + k, MOVE_DROP);
                            break;
                        }
                    }
                }
            }

            // Step up (a stair step: the block in front becomes the floor).
            float clearUp = cell(n.x, y + 2, n.z).clear() + cell(nx, y + 2, nz).clear() + cell(nx, y + 1, nz).clear();
            if (clearUp < INF) {
                float support = supportCost(cell(nx, y, nz));
                if (support < INF) {
                    push(n, nx, y + 1, nz, WALK + UP_EXTRA + clearUp + support, MOVE_UP);
                }
            }

            // Step down (digging a staircase down if needed).
            float clearDown = cell(nx, y + 1, nz).clear() + cell(nx, y, nz).clear() + cell(nx, y - 1, nz).clear();
            if (clearDown < INF) {
                float support = supportCost(cell(nx, y - 2, nz));
                if (support < INF) {
                    push(n, nx, y - 1, nz, WALK + clearDown + support, MOVE_DOWN);
                }
            }
        }
    }

    private float supportCost(Cell cell) {
        return switch (cell.support()) {
            case SUPPORT_SOLID -> 0.0F;
            case SUPPORT_PLACEABLE -> placeCost;
            default -> INF;
        };
    }

    private void push(Node parent, int x, int y, int z, float cost, byte move) {
        if (y < minY || y > maxY) {
            return;
        }
        Node node = node(x, y, z);
        float g = parent.g + cost;
        if (node.closed || g >= node.g) {
            return;
        }
        node.g = g;
        node.h = heuristic(x, y, z);
        node.parent = parent;
        node.move = move;
        open.add(new Entry(node, g + node.h * HEURISTIC_WEIGHT));
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
        if (pos.getY() < minY - 2 || pos.getY() > maxY + 3 || !level.isLoaded(pos)) {
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
        return new Cell(clear, support);
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
        for (int i = 0; i < size; i++) {
            Node n = path.get(i);
            positions[i] = new BlockPos(n.x, n.y, n.z);
            supports[i] = new BlockPos(n.x, n.y - 1, n.z);
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
                default -> new BlockPos[0];
            };
            if (n.move == MOVE_START) {
                supports[i] = null;
            }
        }
        if (complete) {
            // Mobs aim at the Core's upper half: the block on top of the Core must not block their view.
            BlockPos[] lastClears = clears[size - 1];
            BlockPos[] withTop = java.util.Arrays.copyOf(lastClears, lastClears.length + 1);
            withTop[lastClears.length] = core.above();
            clears[size - 1] = withTop;
        }
        return new Route(core, positions, clears, supports, complete, now);
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
        if (!Config.BUILDING_ENABLED.get() || pos.equals(core)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return !(state.getBlock() instanceof CoreBlock) && !state.is(Digging.SIEGE_PROOF)
                && state.getDestroySpeed(level, pos) >= 0;
    }
}
