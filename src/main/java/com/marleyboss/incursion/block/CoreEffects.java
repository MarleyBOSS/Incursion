package com.marleyboss.incursion.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

/**
 * Client particles of an active Incursion Core: violet energy spirals around the crystal, glyphs are drawn into it and
 * the emitters on the dish spit sparks. Thrown every tick from the block entity's client ticker. Portal and enchant
 * particles start at the given position plus the given offset and fly to the given position.
 */
final class CoreEffects {
    private static final DustParticleOptions GOLD_DUST = new DustParticleOptions(new Vector3f(1.0F, 0.78F, 0.2F), 0.9F);

    private CoreEffects() {
    }

    static void spawn(Level level, BlockPos pos) {
        RandomSource random = level.random;
        long time = level.getGameTime();
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 1.12; // the fire orb floats in the opened Sarcophagus
        double cz = pos.getZ() + 0.5;
        if (time % 2 == 0) {
            for (int k = 0; k < 2; k++) {
                double angle = time * 0.16 + k * Math.PI;
                double wave = Math.sin(time * 0.05 + k * 2.0) * 0.45;
                level.addParticle(GOLD_DUST, cx + Math.cos(angle) * 0.6, cy + wave, cz + Math.sin(angle) * 0.6,
                        0.0, 0.02, 0.0);
            }
        }
        if (time % 3 == 0) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dist = 1.6 + random.nextDouble() * 1.4;
            level.addParticle(ParticleTypes.ENCHANT, cx, cy + 0.1, cz,
                    Math.cos(angle) * dist, random.nextDouble() * 1.4 - 0.4, Math.sin(angle) * dist);
        }
        if (random.nextInt(6) == 0) {
            level.addParticle(ParticleTypes.FLAME, cx + (random.nextDouble() - 0.5) * 0.3, cy + 0.1, cz + (random.nextDouble() - 0.5) * 0.3,
                    (random.nextDouble() - 0.5) * 0.02, 0.03 + random.nextDouble() * 0.03, (random.nextDouble() - 0.5) * 0.02);
        }
        if (random.nextInt(14) == 0) {
            double ex = cx + (random.nextBoolean() ? 0.36 : -0.36);
            double ez = cz + (random.nextBoolean() ? 0.36 : -0.36);
            level.addParticle(ParticleTypes.SMALL_FLAME, ex, pos.getY() + 0.9, ez,
                    (random.nextDouble() - 0.5) * 0.3, 0.1 + random.nextDouble() * 0.2, (random.nextDouble() - 0.5) * 0.3);
        }
        if (time % 7 == 0) {
            level.addParticle(ParticleTypes.END_ROD, cx + (random.nextDouble() - 0.5) * 0.8, pos.getY() + 1.0,
                    cz + (random.nextDouble() - 0.5) * 0.8, 0.0, 0.03, 0.0);
        }
    }
}
