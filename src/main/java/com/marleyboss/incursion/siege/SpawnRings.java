package com.marleyboss.incursion.siege;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Vector3f;

/** Particle rings that show where siege mobs cannot spawn. */
public final class SpawnRings {
    private static final DustParticleOptions WARD = new DustParticleOptions(new Vector3f(0.25F, 1.0F, 0.35F), 1.6F);
    private static final DustParticleOptions CORE = new DustParticleOptions(new Vector3f(0.75F, 0.25F, 1.0F), 1.6F);

    private SpawnRings() {
    }

    public static void drawWard(ServerLevel level, BlockPos center, int radius) {
        ring(level, center, radius, WARD);
    }

    public static void drawCore(ServerLevel level, BlockPos center, int radius) {
        ring(level, center, radius, CORE);
    }

    private static void ring(ServerLevel level, BlockPos center, int radius, DustParticleOptions dust) {
        int points = Math.max(16, (int) (radius * 2.0 * Math.PI / 1.5));
        for (int i = 0; i < points; i++) {
            double angle = 2.0 * Math.PI * i / points;
            double x = center.getX() + 0.5 + Math.cos(angle) * radius;
            double z = center.getZ() + 0.5 + Math.sin(angle) * radius;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
            double y = Math.abs(top - center.getY()) <= 12 ? top + 0.3 : center.getY() + 0.3;
            level.sendParticles(dust, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
