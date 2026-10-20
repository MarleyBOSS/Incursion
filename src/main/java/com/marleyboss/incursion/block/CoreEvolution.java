package com.marleyboss.incursion.block;

import com.marleyboss.incursion.registry.ModBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The evolution of the Sarcophagus: every 5 Core levels it grows and its surroundings are built (all positions are
 * relative to the Core block).
 * <ul>
 *     <li>stage 0 (level 1-4): the Sarcophagus, 1x2 blocks;</li>
 *     <li>stage 1 (level 5-9): bigger, on a 3x3 plaza of obsidian with gold lines;</li>
 *     <li>stage 2 (level 10-14): bigger, a round plaza with gold rings and glass panes, four pillars with red energy crystals,
 *     and under the plaza a hollow inverted cone of obsidian and gold, the prison of the Warden;</li>
 *     <li>stage 3 (level 15-19): bigger, wider plaza, taller pillars, energy beams from the crystals to the Core;</li>
 *     <li>stage 4 (level 20): the full size: the widest plaza, the tallest pillars, strong beams.</li>
 * </ul>
 */
public final class CoreEvolution {
    public static final int MAX_STAGE = 4;
    /** Ticks the blocks in the way shake before they blow up. */
    public static final int SHAKE = 40;
    private static final float[] SCALE = {1.0F, 1.4F, 1.8F, 2.4F, 3.0F};
    private static final int[] PLAZA = {0, 3, 5, 6, 7};
    private static final int[] PILLAR = {0, 0, 6, 8, 10};
    /** Radius of the plaza disc (blocks from the middle): stage 1 is a 3x3 square, then a circle that holds the pillars. */
    private static final float[] DISC = {0.0F, 4.6F, 8.0F, 9.4F, 10.8F};
    /** Where the pillars stand (diagonal offset); they do not exist before stage 2. */
    private static final float[] PILLAR_R = {4.0F, 4.0F, 5.0F, 6.0F, 7.0F};
    /** Strength of the energy beams per stage. */
    private static final float[] BEAMS = {0.0F, 0.0F, 0.5F, 0.75F, 1.0F};

    /** One block of the structure, relative to the Core block. */
    public record Cell(int x, int y, int z, BlockState state) {
    }

    /** The largest radius of the protected area (stage 4). */
    public static final int MAX_AREA_RADIUS = 11;

    /** Thickness of the hull of the old pyramid (see legacyPyramid). */
    private static final float HULL = 1.3F;

    private CoreEvolution() {
    }

    /** The lowest layer (relative to the Core) of the protected area: nothing is built under the plaza any more. */
    public static int areaBottom(int stage) {
        return 0;
    }

    /** Height of the invisible space of the Sarcophagus in blocks. */
    public static int regionHeight(int stage) {
        return (int) Math.ceil(1.9 * SCALE[clamp(stage)] - 0.001);
    }

    public static int areaRadius(int stage) {
        return stage >= 1 ? (int) Math.ceil(DISC[clamp(stage)]) : 0;
    }

    public static int areaHeight(int stage) {
        return Math.max(regionHeight(stage), PILLAR[clamp(stage)] + 1);
    }

    public static float discRadius(int stage) {
        return DISC[clamp(stage)];
    }

    /** Linear blend between the values of two stages, for a stage that is on its way (0..4). */
    private static float blend(float[] values, float stage) {
        float v = Math.max(0.0F, Math.min(MAX_STAGE, stage));
        int i = Math.min(MAX_STAGE - 1, (int) Math.floor(v));
        return values[i] + (values[i + 1] - values[i]) * (v - i);
    }

    private static float[] floats(int[] values) {
        float[] out = new float[values.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = values[i];
        }
        return out;
    }

    private static final float[] PLAZA_F = floats(PLAZA);
    private static final float[] PILLAR_F = floats(PILLAR);

    /** The values below are for the client: the animation runs through the stages continuously. */
    public static float scaleAt(float stage) {
        return blend(SCALE, stage);
    }

    public static float radiusAt(float stage) {
        return blend(PLAZA_F, stage);
    }

    public static float pillarAt(float stage) {
        return blend(PILLAR_F, stage);
    }

    public static float pillarRadiusAt(float stage) {
        return blend(PILLAR_R, stage);
    }

    public static float discAt(float stage) {
        return blend(DISC, stage);
    }

    public static float beamAt(float stage) {
        return blend(BEAMS, stage);
    }

    /**
     * The protected area of a stage (relative to the Core): the whole square of the plaza up to the top of the pillars.
     * Nothing but the structure and the blocks of the Core itself may stand there.
     */
    public static boolean inArea(int stage, int dx, int dy, int dz) {
        if (stage < 1) {
            return false;
        }
        if (dy < 0) {
            return false;
        }
        float disc = DISC[clamp(stage)] + 0.5F;
        return dx * dx + dz * dz <= disc * disc && dy < areaHeight(stage);
    }

    public static int stageFor(int level) {
        if (level < 5) {
            return 0;
        }
        return Math.min(MAX_STAGE, level / 5);
    }

    public static float scaleFor(int stage) {
        return SCALE[clamp(stage)];
    }

    public static int plazaRadius(int stage) {
        return PLAZA[clamp(stage)];
    }

    /** Height of the pillars in blocks (the top two are the red crystal), 0 = none. */
    public static int pillarHeight(int stage) {
        return PILLAR[clamp(stage)];
    }

    public static float beamStrength(int stage) {
        return BEAMS[clamp(stage)];
    }

    private static int clamp(int stage) {
        return Math.max(0, Math.min(MAX_STAGE, stage));
    }

    /** Every block of a stage: the invisible space of the Sarcophagus, the plaza and the pillars. */
    public static List<Cell> plan(int stage) {
        stage = clamp(stage);
        List<Cell> cells = new ArrayList<>();
        BlockState part = ModBlocks.CORE_PART.get().defaultBlockState();
        BlockState top = ModBlocks.CORE_TOP.get().defaultBlockState();
        float s = SCALE[stage];
        int lo = (int) Math.floor(0.5 - s / 2.0 + 0.001);
        int hi = (int) Math.floor(0.5 + s / 2.0 - 0.001);
        // only the body of the lower half is solid; the air above it (where the upper half floats) stays free to walk in
        int height = Math.max(2, (int) Math.ceil(0.95 * s - 0.001));
        for (int x = lo; x <= hi; x++) {
            for (int z = lo; z <= hi; z++) {
                for (int y = 0; y < height; y++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue; // the Core itself
                    }
                    cells.add(new Cell(x, y, z, x == 0 && z == 0 && y == 1 ? top : part));
                }
            }
        }
        float disc = DISC[stage];
        if (stage >= 1) {
            BlockState floor = ModBlocks.CORE_FLOOR.get().defaultBlockState();
            BlockState gold = ModBlocks.CORE_GOLD.get().defaultBlockState();
            int r = (int) Math.ceil(disc);
            int anchor = anchorCell(stage);
            int foot = PLAZA[stage];
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d > disc) {
                        continue;
                    }
                    int ra = Math.max(Math.abs(Math.abs(x) - anchor), Math.abs(Math.abs(z) - anchor));
                    int rp = stage >= 2 && foot > 0 ? Math.max(Math.abs(Math.abs(x) - foot), Math.abs(Math.abs(z) - foot)) : 9;
                    boolean pad = ra <= 1 || rp <= 1; // dark pads under the chain anchors and the pillars
                    boolean frame = ra == 2 || rp == 2; // framed in gold
                    boolean cross = stage == 1 && (x == 0 || z == 0);
                    boolean isGold = !pad && (frame || cross || Math.abs(x) == Math.abs(z) || onRing(d, stage, disc));
                    boolean glass = false;
                    cells.add(new Cell(x, -1, z, glass ? Blocks.GLASS.defaultBlockState() : isGold ? gold : floor));
                }
            }
        }
        int pillar = PILLAR[stage];
        if (pillar > 0) {
            int radius = PLAZA[stage];
            BlockState column = ModBlocks.CORE_CRYSTAL.get().defaultBlockState(); // invisible; the pillar is drawn by the renderer
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    for (int y = 0; y < pillar; y++) {
                        cells.add(new Cell(sx * radius, y, sz * radius, column));
                    }
                }
            }
        }
        return cells;
    }

    /**
     * The hollow pyramid under the plaza that versions 0.14.0 to 0.14.2 built (and the glass of its window). It is gone from
     * the plan, but everything() still lists it so that an old base is cleaned up when it is torn down or rebuilt.
     */
    private static List<Cell> legacyPyramid(int stage) {
        List<Cell> cells = new ArrayList<>();
        if (stage >= 1) {
            // the hollow under the plaza: an inverted pyramid of obsidian and gold that ends in a point (the prison of the Warden)
            BlockState air = Blocks.AIR.defaultBlockState();
            BlockState hullFloor = ModBlocks.CORE_FLOOR.get().defaultBlockState();
            BlockState hullGold = ModBlocks.CORE_GOLD.get().defaultBlockState();
            int floorY = legacyFloor(stage);
            for (int y = -2; legacyHalf(stage, y) >= -0.3F; y--) {
                float ro = legacyHalf(stage, y);
                int rr = (int) Math.ceil(ro + 0.3F);
                int k = -1 - y;
                for (int x = -rr; x <= rr; x++) {
                    for (int z = -rr; z <= rr; z++) {
                        int d = Math.max(Math.abs(x), Math.abs(z));
                        if (y > floorY && d <= ro - HULL) {
                            cells.add(new Cell(x, y, z, air));
                        } else if (d <= ro + 0.3) {
                            boolean isGold = y == floorY ? d == 3 || Math.abs(x) == Math.abs(z) : k % 3 == 0 || Math.abs(x) == Math.abs(z);
                            cells.add(new Cell(x, y, z, isGold ? hullGold : hullFloor));
                        }
                    }
                }
            }
        }
        return cells;
    }

    private static float legacyHalf(int stage, int y) {
        return new float[]{0.0F, 3.6F, 6.6F, 7.6F, 8.6F}[clamp(stage)] - 0.6F * (-2 - y);
    }

    private static int legacyFloor(int stage) {
        return clamp(stage) >= 2 ? -7 : -5;
    }

    /** The cell (as an offset on both axes) the chains of a stage are fixed on; the renderer uses the same position. */
    public static int anchorCell(int stage) {
        stage = clamp(stage);
        float reach = Math.min(0.5F * SCALE[stage] + 2.3F, PLAZA[stage] + 0.4F);
        return Math.max(1, (int) Math.floor(reach + 0.5F));
    }

    /** Radii of the gold rings inlaid in the plaza. */
    private static final float[] RINGS = {3.2F, 5.6F, 8.0F};

    private static boolean onRing(double d, int stage, float disc) {
        if (stage < 2) {
            return false;
        }
        for (float ring : RINGS) {
            if (ring <= disc - 0.9F && Math.abs(d - ring) < 0.52) {
                return true;
            }
        }
        return false;
    }

    /** All cells any stage can contain (to tear the whole thing down). */
    public static List<Cell> everything() {
        List<Cell> all = new ArrayList<>();
        for (int stage = 0; stage <= MAX_STAGE; stage++) {
            all.addAll(plan(stage));
            all.addAll(legacyPyramid(stage));
        }
        return all;
    }

    public static boolean isStructure(BlockState state) {
        return state.is(ModBlocks.CORE_PART.get()) || state.is(ModBlocks.CORE_TOP.get())
                || state.is(ModBlocks.CORE_FLOOR.get()) || state.is(ModBlocks.CORE_GOLD.get())
                || state.is(ModBlocks.CORE_CRYSTAL.get()) || state.is(Blocks.GLASS);
    }
}
