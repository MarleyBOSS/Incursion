package com.marleyboss.incursion.block;

import com.marleyboss.incursion.registry.ModBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The evolution of the Sarcophagus: every 5 Core levels it grows and its surroundings are built (all positions are
 * relative to the Core block).
 * <ul>
 *     <li>stage 0 (level 1-4): the Sarcophagus, 1x2 blocks;</li>
 *     <li>stage 1 (level 5-9): bigger, on a 3x3 plaza of obsidian with gold lines;</li>
 *     <li>stage 2 (level 10-14): bigger, 7x7 plaza, four small pillars with red energy crystals;</li>
 *     <li>stage 3 (level 15-19): bigger, 9x9 plaza, taller pillars, energy beams from the crystals to the Core;</li>
 *     <li>stage 4 (level 20): the full size: 11x11 plaza, tall pillars, strong beams.</li>
 * </ul>
 */
public final class CoreEvolution {
    public static final int MAX_STAGE = 4;
    /** Ticks the blocks in the way shake before they blow up. */
    public static final int SHAKE = 40;
    private static final float[] SCALE = {1.0F, 1.4F, 1.8F, 2.4F, 3.0F};
    private static final int[] PLAZA = {0, 1, 3, 4, 5};
    private static final int[] PILLAR = {0, 0, 3, 5, 7};
    /** Strength of the energy beams per stage. */
    private static final float[] BEAMS = {0.0F, 0.0F, 0.5F, 0.75F, 1.0F};

    /** One block of the structure, relative to the Core block. */
    public record Cell(int x, int y, int z, BlockState state) {
    }

    /** The largest radius of the protected area (stage 4). */
    public static final int MAX_AREA_RADIUS = 5;

    private CoreEvolution() {
    }

    /** Height of the invisible space of the Sarcophagus in blocks. */
    public static int regionHeight(int stage) {
        return (int) Math.ceil(1.9 * SCALE[clamp(stage)] - 0.001);
    }

    public static int areaRadius(int stage) {
        return stage >= 1 ? Math.max(1, PLAZA[clamp(stage)]) : 0;
    }

    public static int areaHeight(int stage) {
        return Math.max(regionHeight(stage), PILLAR[clamp(stage)]);
    }

    /**
     * The protected area of a stage (relative to the Core): the whole square of the plaza up to the top of the pillars.
     * Nothing but the structure and the blocks of the Core itself may stand there.
     */
    public static boolean inArea(int stage, int dx, int dy, int dz) {
        if (stage < 1) {
            return false;
        }
        int r = areaRadius(stage);
        return Math.abs(dx) <= r && Math.abs(dz) <= r && dy >= 0 && dy < areaHeight(stage);
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
        int radius = PLAZA[stage];
        if (radius > 0) {
            BlockState floor = ModBlocks.CORE_FLOOR.get().defaultBlockState();
            BlockState gold = ModBlocks.CORE_GOLD.get().defaultBlockState();
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    cells.add(new Cell(x, -1, z, Math.abs(x) == Math.abs(z) ? gold : floor));
                }
            }
        }
        int pillar = PILLAR[stage];
        if (pillar > 0) {
            BlockState gold = ModBlocks.CORE_GOLD.get().defaultBlockState();
            BlockState crystal = ModBlocks.CORE_CRYSTAL.get().defaultBlockState();
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    for (int y = 0; y < pillar; y++) {
                        cells.add(new Cell(sx * radius, y, sz * radius, y >= pillar - 2 ? crystal : gold));
                    }
                }
            }
        }
        return cells;
    }

    /** All cells any stage can contain (to tear the whole thing down). */
    public static List<Cell> everything() {
        List<Cell> all = new ArrayList<>();
        for (int stage = 0; stage <= MAX_STAGE; stage++) {
            all.addAll(plan(stage));
        }
        return all;
    }

    public static boolean isStructure(BlockState state) {
        return state.is(ModBlocks.CORE_PART.get()) || state.is(ModBlocks.CORE_TOP.get())
                || state.is(ModBlocks.CORE_FLOOR.get()) || state.is(ModBlocks.CORE_GOLD.get())
                || state.is(ModBlocks.CORE_CRYSTAL.get());
    }
}
