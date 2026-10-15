package com.marleyboss.incursion.siege;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Sends particles with "long distance" so they are visible from far away (used by the AI view). */
public final class SpawnRings {
    /** Players farther away than this from a particle do not get it. */
    private static final double SEND_RANGE = 160.0;

    private SpawnRings() {
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
