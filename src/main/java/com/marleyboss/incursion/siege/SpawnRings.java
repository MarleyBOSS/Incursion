package com.marleyboss.incursion.siege;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Vector3f;

/**
 * Particle rings that show where siege mobs cannot spawn (switched on and off in the Core GUI).
 * Drawn as a low fence with posts, sent with "long distance" so they are visible from far away.
 */
public final class SpawnRings {
    private static final DustParticleOptions WARD = new DustParticleOptions(new Vector3f(0.2F, 1.0F, 0.3F), 2.5F);
    private static final DustParticleOptions CORE = new DustParticleOptions(new Vector3f(0.8F, 0.2F, 1.0F), 2.5F);
    /** Players farther away than this from a particle do not get it. */
    private static final double SEND_RANGE = 160.0;

    private SpawnRings() {
    }

    public static void drawWard(ServerLevel level, BlockPos center, int radius) {
        ring(level, center, radius, WARD);
    }

    public static void drawCore(ServerLevel level, BlockPos center, int radius) {
        ring(level, center, radius, CORE);
    }

    private static void ring(ServerLevel level, BlockPos center, int radius, DustParticleOptions dust) {
        if (level.players().isEmpty()) {
            return;
        }
        int points = Math.max(24, (int) (radius * 2.0 * Math.PI));
        for (int i = 0; i < points; i++) {
            double angle = 2.0 * Math.PI * i / points;
            double x = center.getX() + 0.5 + Math.cos(angle) * radius;
            double z = center.getZ() + 0.5 + Math.sin(angle) * radius;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
            double y = Math.abs(top - center.getY()) <= 16 ? top : center.getY();
            send(level, dust, x, y + 0.4, z);
            if (i % 4 == 0) {
                // Posts, so the ring can be seen over grass and from a distance.
                send(level, dust, x, y + 1.2, z);
                send(level, dust, x, y + 2.0, z);
            }
        }
    }

    /** Sends one particle to every player near it, also beyond the normal 32-block particle range. */
    public static void send(ServerLevel level, ParticleOptions particle, double x, double y, double z) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) <= SEND_RANGE * SEND_RANGE) {
                level.sendParticles(player, particle, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }
}
