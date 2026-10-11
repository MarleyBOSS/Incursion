package com.marleyboss.incursion.fallout;

import java.util.Arrays;

/**
 * Precomputes the crater as a list of block offsets, sorted from the centre outwards, so the crater
 * can be carved a few thousand blocks per tick and looks like an expanding blast.
 * Offsets are packed as {@code (dx+128)<<16 | (dy+128)<<8 | (dz+128)}. The shape is deterministic
 * for a given seed, so an unfinished crater continues correctly after a server restart.
 */
final class CraterShape {
    private CraterShape() {
    }

    static int dx(int packed) {
        return ((packed >> 16) & 0xFF) - 128;
    }

    static int dy(int packed) {
        return ((packed >> 8) & 0xFF) - 128;
    }

    static int dz(int packed) {
        return (packed & 0xFF) - 128;
    }

    /** Block offsets of a bowl-shaped crater: radius r sideways and upwards, 0.6 r downwards, with rough edges. */
    static int[] carve(int radius, long seed) {
        double down = Math.max(2.0, radius * 0.6);
        double up = radius;
        int maxDown = (int) Math.ceil(down * 1.2);
        int maxUp = (int) Math.ceil(up * 1.2);
        int maxSide = (int) Math.ceil(radius * 1.2);
        double r2 = (double) radius * radius;

        long[] keys = new long[1024];
        int count = 0;
        for (int dx = -maxSide; dx <= maxSide; dx++) {
            for (int dz = -maxSide; dz <= maxSide; dz++) {
                double horizontal = (dx * dx + dz * dz) / r2;
                if (horizontal > 1.2) {
                    continue;
                }
                double columnNoise = 0.12 * noise(seed, dx, 0, dz);
                for (int dy = -maxDown; dy <= maxUp; dy++) {
                    double vertical = dy < 0 ? (dy / down) * (dy / down) : (dy / up) * (dy / up);
                    double value = horizontal + vertical;
                    double limit = 1.0 + columnNoise + 0.06 * noise(seed, dx, dy, dz);
                    if (value > limit) {
                        continue;
                    }
                    if (count == keys.length) {
                        keys = Arrays.copyOf(keys, count * 2);
                    }
                    long order = (long) (value / limit * 1_000_000.0);
                    keys[count++] = (order << 24) | pack(dx, dy, dz);
                }
            }
        }
        Arrays.sort(keys, 0, count);
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = (int) (keys[i] & 0xFFFFFF);
        }
        return result;
    }

    /** Column offsets (dy = 0) of a rough disc with the given radius, sorted from the centre outwards. */
    static int[] columns(int radius, long seed) {
        int max = (int) Math.ceil(radius * 1.1);
        long[] keys = new long[(2 * max + 1) * (2 * max + 1)];
        int count = 0;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                double limit = radius * (1.0 + 0.08 * noise(seed ^ 0x5DEECE66DL, dx, 0, dz));
                if (distance > limit) {
                    continue;
                }
                keys[count++] = ((long) (distance * 1000.0) << 24) | pack(dx, 0, dz);
            }
        }
        Arrays.sort(keys, 0, count);
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = (int) (keys[i] & 0xFFFFFF);
        }
        return result;
    }

    private static int pack(int dx, int dy, int dz) {
        return ((dx + 128) << 16) | ((dy + 128) << 8) | (dz + 128);
    }

    /** Deterministic pseudo-random value in [-1, 1]. */
    static double noise(long seed, int x, int y, int z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h = h ^ (h >>> 31);
        return ((h >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }
}
