package com.marleyboss.incursion.table;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/**
 * Client-side particles of the Core Table, thrown every tick from the block entity (the block's animateTick is far
 * too rare for this). Idle: a quiet aura; working: a spiral rising to the item, glyphs rushing in, sparks, and a
 * crescendo near the end; done: a calm glitter. The enchant and portal particles start at the given position plus the
 * given offset and fly to the given position.
 */
public final class CoreTableEffects {
    private CoreTableEffects() {
    }

    public static void spawn(Level level, BlockPos pos, int job, float progress, int ticksLeft) {
        RandomSource random = level.random;
        long time = level.getGameTime();
        double cx = pos.getX() + 0.5;
        double cz = pos.getZ() + 0.5;
        double top = pos.getY() + 0.75; // the cloth of the table
        if (job == CoreTableLogic.JOB_IDLE) {
            idle(level, random, time, cx, top, cz);
        } else if (job == CoreTableLogic.JOB_WORKING) {
            working(level, random, time, cx, top, cz, progress, ticksLeft);
        } else {
            done(level, random, time, cx, top, cz);
        }
    }

    private static void idle(Level level, RandomSource random, long time, double cx, double top, double cz) {
        // Two sparks circle the table at the height of the rune circle and leave a short fading trail.
        if (time % 2 == 0) {
            for (int k = 0; k < 2; k++) {
                double angle = time * 0.10 + k * Math.PI;
                level.addParticle(ParticleTypes.END_ROD, cx + Math.cos(angle) * 0.85, top + 0.32 + Math.sin(time * 0.05 + k) * 0.04,
                        cz + Math.sin(angle) * 0.85, 0.0, 0.0, 0.0);
            }
        }
        // Glyphs drift in from the sides and sink onto the table, like at an enchanting table with bookshelves.
        if (time % 4 == 0) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 1.0 + random.nextDouble() * 1.2;
            level.addParticle(ParticleTypes.ENCHANT, cx, top + 1.1, cz,
                    Math.cos(angle) * radius, 0.3 + random.nextDouble() * 0.8, Math.sin(angle) * radius);
        }
        // Violet swirls rise from the middle.
        if (time % 5 == 0) {
            level.addParticle(ParticleTypes.PORTAL, cx + (random.nextDouble() - 0.5) * 0.5, top + 0.2 + random.nextDouble() * 0.3,
                    cz + (random.nextDouble() - 0.5) * 0.5, (random.nextDouble() - 0.5) * 0.4, 0.5 + random.nextDouble() * 0.4,
                    (random.nextDouble() - 0.5) * 0.4);
        }
        if (random.nextInt(24) == 0) {
            level.addParticle(ParticleTypes.END_ROD, cx + (random.nextDouble() - 0.5) * 0.9, top + 0.1,
                    cz + (random.nextDouble() - 0.5) * 0.9, 0.0, 0.03 + random.nextDouble() * 0.02, 0.0);
        }
    }

    private static void working(Level level, RandomSource random, long time, double cx, double top, double cz,
                                float progress, int ticksLeft) {
        double itemY = top + 0.7;
        double speed = 0.30 + 0.45 * progress;
        double radius = 0.95 - 0.40 * progress;
        // Three strands of a spiral that rises towards the item and tightens as the job goes on.
        for (int k = 0; k < 3; k++) {
            double angle = time * speed + k * Math.PI * 2.0 / 3.0;
            double rise = ((time * 0.045 + k / 3.0) % 1.0);
            double y = top + 0.15 + rise * 0.95;
            double r = radius * (1.0 - 0.45 * rise);
            level.addParticle(ParticleTypes.END_ROD, cx + Math.cos(angle) * r, y, cz + Math.sin(angle) * r, 0.0, 0.0, 0.0);
            if (k == 0) {
                level.addParticle(ParticleTypes.WITCH, cx + Math.cos(angle) * r, y, cz + Math.sin(angle) * r, 0.0, 0.0, 0.0);
            }
        }
        // Glyphs rush in from far away and end at the item.
        int glyphs = 3 + (int) (progress * 4.0F);
        for (int i = 0; i < glyphs; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dist = 1.4 + random.nextDouble() * 1.6;
            level.addParticle(ParticleTypes.ENCHANT, cx, itemY + 0.5, cz,
                    Math.cos(angle) * dist, random.nextDouble() * 1.6 - 0.3, Math.sin(angle) * dist);
        }
        // Purple vortex from the corners of the table into the middle.
        if (time % 2 == 0) {
            double sx = random.nextBoolean() ? 0.55 : -0.55;
            double sz = random.nextBoolean() ? 0.55 : -0.55;
            level.addParticle(ParticleTypes.PORTAL, cx, top + 0.3, cz, sx * 2.0, 0.2, sz * 2.0);
        }
        // Crackling sparks around the item, more often the closer the end is.
        int sparkChance = Math.max(2, 9 - (int) (progress * 7.0F));
        if (random.nextInt(sparkChance) == 0) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, cx + (random.nextDouble() - 0.5) * 0.4, itemY + (random.nextDouble() - 0.5) * 0.4,
                    cz + (random.nextDouble() - 0.5) * 0.4, (random.nextDouble() - 0.5) * 0.8, (random.nextDouble() - 0.3) * 0.8,
                    (random.nextDouble() - 0.5) * 0.8);
        }
        // Crescendo: in the last three seconds rings of light burst out and the item sheds sparks.
        if (ticksLeft < 60) {
            if (time % 6 == 0) {
                for (int i = 0; i < 10; i++) {
                    double angle = i * Math.PI * 2.0 / 10.0 + time * 0.2;
                    level.addParticle(ParticleTypes.END_ROD, cx, top + 0.35, cz, Math.cos(angle) * 0.12, 0.01, Math.sin(angle) * 0.12);
                }
            }
            level.addParticle(ParticleTypes.FIREWORK, cx + (random.nextDouble() - 0.5) * 0.5, itemY + (random.nextDouble() - 0.5) * 0.5,
                    cz + (random.nextDouble() - 0.5) * 0.5, (random.nextDouble() - 0.5) * 0.2, 0.05, (random.nextDouble() - 0.5) * 0.2);
        }
    }

    private static void done(Level level, RandomSource random, long time, double cx, double top, double cz) {
        double angle = time * 0.15;
        if (time % 2 == 0) {
            level.addParticle(ParticleTypes.END_ROD, cx + Math.cos(angle) * 0.6, top + 0.2 + (time % 40) / 40.0 * 0.9,
                    cz + Math.sin(angle) * 0.6, 0.0, 0.0, 0.0);
            level.addParticle(ParticleTypes.END_ROD, cx - Math.cos(angle) * 0.6, top + 0.2 + (time % 40) / 40.0 * 0.9,
                    cz - Math.sin(angle) * 0.6, 0.0, 0.0, 0.0);
        }
        if (time % 3 == 0) {
            level.addParticle(ParticleTypes.HAPPY_VILLAGER, cx + (random.nextDouble() - 0.5) * 0.9, top + 0.3 + random.nextDouble() * 0.8,
                    cz + (random.nextDouble() - 0.5) * 0.9, 0.0, 0.0, 0.0);
        }
    }
}
