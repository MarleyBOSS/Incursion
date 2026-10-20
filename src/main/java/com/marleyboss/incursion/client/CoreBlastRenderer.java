package com.marleyboss.incursion.client;

import static com.marleyboss.incursion.client.SarcophagusRenderer.FLARE;
import static com.marleyboss.incursion.client.SarcophagusRenderer.HALO;
import static com.marleyboss.incursion.client.SarcophagusRenderer.ORB_RADIUS;
import static com.marleyboss.incursion.client.SarcophagusRenderer.ORB_Y;
import static com.marleyboss.incursion.client.SarcophagusRenderer.TRIM;
import static com.marleyboss.incursion.client.SarcophagusRenderer.billboard;
import static com.marleyboss.incursion.client.SarcophagusRenderer.drawBolt;
import static com.marleyboss.incursion.client.SarcophagusRenderer.drawCorona;
import static com.marleyboss.incursion.client.SarcophagusRenderer.drawOrb;
import static com.marleyboss.incursion.client.SarcophagusRenderer.drawStar;
import static com.marleyboss.incursion.client.SarcophagusRenderer.ease;
import static com.marleyboss.incursion.client.SarcophagusRenderer.flushAdditive;
import static com.marleyboss.incursion.client.SarcophagusRenderer.fxFire;
import static com.marleyboss.incursion.client.SarcophagusRenderer.glow;
import static com.marleyboss.incursion.client.SarcophagusRenderer.laser;
import static com.marleyboss.incursion.client.SarcophagusRenderer.plane;
import static com.marleyboss.incursion.client.SarcophagusRenderer.rnd;
import static com.marleyboss.incursion.client.SarcophagusRenderer.sphere;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreEvolution;
import com.marleyboss.incursion.entity.CoreBlastEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The meltdown of a Core, drawn in the late (see-through) pass of the level together with the rest of the Sarcophagus glow.
 * <ul>
 *     <li><b>Charge</b> (the fuse, 5.5 s): the Core gets brighter, bigger and whiter; rings run in towards it, sparks are sucked
 *     in, lightning arcs jump out of it and bolts strike from the sky, faster and faster; at the end a column of light rises.</li>
 *     <li><b>Blast</b>: a white flash, a fireball with a hot core and a rising cap, ring waves over the ground, a shock shell,
 *     a pillar of light, streaks of fire and a cloud of embers that fall back in an arc.</li>
 * </ul>
 * Everything is a function of the age of the {@link CoreBlastEntity}, which the server synchronises (start tick, stage, radius).
 */
public final class CoreBlastRenderer {
    private static final List<CoreBlastEntity> BLASTS = new ArrayList<>();
    /** A soft, wide ring: the shock waves (a thin textured line would shimmer when it is huge). */
    private static final ResourceLocation SHOCK = ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "textures/effect/shock_ring.png");
    /** A reactor circle (rings, ticks, two triangles): not the rune circle of the Core. */
    private static final ResourceLocation CIRCLE = ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "textures/effect/blast_circle.png");

    private static RenderType tex(ResourceLocation texture) {
        return RenderType.entityTranslucentEmissive(texture);
    }

    private CoreBlastRenderer() {
    }

    /** Collects the blasts of the level; true when there is something to draw. */
    static boolean collect() {
        BLASTS.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof CoreBlastEntity blast && !blast.isRemoved()) {
                BLASTS.add(blast);
            }
        }
        return !BLASTS.isEmpty();
    }

    static void draw(MultiBufferSource.BufferSource buffer, Vec3 cam, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float time = mc.level.getGameTime() + partial;
        for (CoreBlastEntity blast : BLASTS) {
            float age = (mc.level.getGameTime() - blast.getStart()) + partial;
            if (age < 0.0F || age > CoreBlastEntity.FUSE + CoreBlastEntity.AFTER) {
                continue;
            }
            float sc = CoreEvolution.scaleFor(blast.getStage());
            PoseStack pose = new PoseStack();
            pose.translate(blast.getX() - 0.5 - cam.x, blast.getY() - cam.y, blast.getZ() - 0.5 - cam.z);
            if (age < CoreBlastEntity.FUSE) {
                charge(pose, buffer, age, CoreBlastEntity.FUSE, time, sc, blast.getStage(), true, 1.0F);
            } else {
                blast(pose, buffer, age - CoreBlastEntity.FUSE, time, sc, Mth.clamp(blast.getBlastRadius(), 10, 48), false);
            }
            flushAdditive(buffer);
        }
    }

    /**
     * The upgrade of a Core to a new stage: the same kind of animation as the meltdown, but gentler and without the fire:
     * the Core charges up for {@link CoreEvolution#EVOLVE_TICKS} (the music builds up to the same moment), then a wave of
     * light goes out of it. {@code d.evoAge} is the time since the upgrade began.
     */
    static void drawUpgrade(PoseStack pose, MultiBufferSource buffer, SarcophagusRenderer.Late d) {
        float fuse = CoreEvolution.EVOLVE_TICKS;
        float stage = Mth.clamp(d.vs, 0.0F, 4.0F);
        if (d.evoAge < fuse) {
            charge(pose, buffer, d.evoAge, fuse, d.time, d.scale, stage, false, 0.55F);
        } else if (d.evoAge < fuse + 110.0F) {
            blast(pose, buffer, d.evoAge - fuse, d.time, d.scale, 6.8F + 1.0F * Math.max(1.0F, stage), true);
        }
    }

    private static float easeOut(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        float inv = 1.0F - x;
        return 1.0F - inv * inv * inv;
    }

    private static float[] dir(int seed, int salt, float upBias) {
        float phi = rnd(seed, salt) * 6.2832F;
        float y = Mth.clamp(rnd(seed, salt + 50) * 2.0F - 1.0F + upBias, -1.0F, 1.0F);
        float h = Mth.sqrt(Math.max(0.0F, 1.0F - y * y));
        return new float[]{Mth.cos(phi) * h, y, Mth.sin(phi) * h};
    }

    // ------------------------------------------------------------------
    // The charge
    // ------------------------------------------------------------------

    private static void charge(PoseStack pose, MultiBufferSource buffer, float age, float fuse, float time, float sc, float stage,
                               boolean core, float m) {
        float c = age / fuse;
        float c2 = c * c;
        float c3 = c2 * c;
        float orb = 1.0F + 7.0F * (float) Math.pow(c, 2.2);
        SarcophagusRenderer.Late d = new SarcophagusRenderer.Late();
        d.time = time;
        d.orb = orb;
        d.vs = stage;
        d.lift = 0.0F;
        d.flash = 0.0F;

        // --- the Core itself, in the scale of its Sarcophagus
        pose.pushPose();
        pose.translate(0.5F, 0.0F, 0.5F);
        pose.scale(sc, sc, sc);
        pose.translate(-0.5F, 0.0F, -0.5F);
        if (core) {
            // (an upgrade does not draw the Core again: the block renderer does that, and makes it bigger)
            pose.pushPose();
            float jit = 0.004F + 0.03F * c2;
            pose.translate(jit * Mth.sin(time * 1.7F), jit * Mth.sin(time * 2.3F + 1.0F), jit * Mth.sin(time * 1.9F + 2.0F));
            if (stage < 1.0F) {
                drawStar(pose, buffer, d);
            }
            drawOrb(pose, buffer, time, orb, 0.0F, stage);
            float g = 1.0F + 3.5F * c2;
            pose.pushPose();
            pose.translate(0.5F, ORB_Y, 0.5F);
            pose.scale(g, g, g);
            pose.translate(-0.5F, -ORB_Y, -0.5F);
            drawCorona(pose, buffer, d);
            pose.popPose();
            pose.popPose();
        }
        // it turns white-hot
        pose.pushPose();
        pose.translate(0.5F, ORB_Y, 0.5F);
        float w = (float) Math.pow(c, 2.5) * m;
        sphere(buffer.getBuffer(glow(TRIM)), pose.last(), ORB_RADIUS * orb * 1.04F, 0.0F, 0.0F, w, w * 0.96F, w * 0.82F);
        pose.popPose();
        billboard(buffer.getBuffer(glow(HALO)), pose.last(), 0.5F, ORB_Y, 0.5F, 0.9F + 7.0F * c2, 0.0F,
                m * (0.25F + 0.75F * c) * 1.0F, m * (0.25F + 0.75F * c) * 0.58F, m * (0.25F + 0.75F * c) * 0.2F);
        billboard(buffer.getBuffer(glow(FLARE)), pose.last(), 0.5F, ORB_Y, 0.5F, 0.4F + 5.0F * c2, time * 0.012F,
                m * c * 1.0F, m * c * 0.9F, m * c * 0.65F);
        // bolts from the sky strike it
        for (int strike : new int[]{22, 48, 70, 88, 99}) {
            float local = age - strike;
            if (local >= 0.0F && local < 7.0F) {
                drawBolt(pose, buffer, time, (1.0F - local / 7.0F) * Math.min(1.0F, m));
            }
        }
        pose.popPose();

        // --- the surroundings, in blocks (not scaled), round the middle of the orb
        float oy = ORB_Y * sc;
        pose.pushPose();
        pose.translate(0.5F, oy, 0.5F);
        float reach = 4.0F + 2.0F * sc;
        // rings that run in
        for (int i = 0; i < 3; i++) {
            float q = time * (0.012F + 0.03F * c) + i / 3.0F;
            float ph = q - (float) Math.floor(q);
            float r = 0.4F + reach * (1.0F - ph);
            float a = Mth.sin(ph * (float) Math.PI) * (0.25F + 0.75F * c) * m;
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(time * (2.0F + i) + i * 40.0F));
            pose.mulPose(Axis.XP.rotationDegrees(i == 0 ? 0.0F : i == 1 ? 62.0F : -62.0F));
            plane(buffer.getBuffer(tex(SHOCK)), pose.last(), 0.0F, 0.0F, 0.0F, r, 0.0F, 0.0F, 0.0F, 0.0F, r,
                    1.0F * a, 0.62F * a, 0.22F * a);
            pose.popPose();
        }
        // sparks that are sucked in
        int sparks = 14 + (int) (26 * c);
        for (int i = 0; i < sparks; i++) {
            float len = 36.0F - 22.0F * c;
            float q = time / len + rnd(i, 1);
            int cyc = (int) Math.floor(q);
            float ph = q - cyc;
            float[] dd = dir(cyc * 61 + i, 3, 0.0F);
            float dist = (reach + 3.0F * rnd(i, cyc + 7)) * (1.0F - ph) * (1.0F - ph);
            float a = Mth.sqrt(Mth.sin(ph * (float) Math.PI)) * (0.3F + 0.7F * c) * m;
            billboard(buffer.getBuffer(glow(FLARE)), pose.last(), dd[0] * dist, dd[1] * dist, dd[2] * dist,
                    0.1F + 0.12F * rnd(i, 9), time * 0.02F, 1.0F * a, 0.7F * a, 0.3F * a);
        }
        // lightning arcs
        int bucket = (int) (time * 0.5F);
        int arcs = 1 + (int) (9 * c);
        for (int i = 0; i < arcs; i++) {
            int sd = bucket * 31 + i;
            float[] dd = dir(sd, 11, 0.0F);
            float len = (reach * 0.5F + reach * rnd(sd, 5)) * (0.5F + 0.5F * c);
            float flick = 0.6F + 0.4F * rnd(sd, 6);
            float px = 0.0F;
            float py = 0.0F;
            float pz = 0.0F;
            int segs = 6;
            for (int k = 1; k <= segs; k++) {
                float u = (float) k / segs;
                float jag = 0.35F * len / segs;
                float nx = dd[0] * len * u + (rnd(sd, 20 + k) - 0.5F) * 2.0F * jag;
                float ny = dd[1] * len * u + (rnd(sd, 30 + k) - 0.5F) * 2.0F * jag;
                float nz = dd[2] * len * u + (rnd(sd, 40 + k) - 0.5F) * 2.0F * jag;
                float fade = (1.0F - 0.55F * u) * flick * (0.45F + 0.55F * c) * m;
                laser(buffer.getBuffer(glow(TRIM)), pose.last(), px, py, pz, nx, ny, nz, 0.14F * sc, 0.55F * fade, 0.32F * fade, 0.08F * fade);
                laser(buffer.getBuffer(glow(TRIM)), pose.last(), px, py, pz, nx, ny, nz, 0.035F * sc, 1.0F * fade, 0.96F * fade, 0.82F * fade);
                px = nx;
                py = ny;
                pz = nz;
            }
        }
        pose.popPose();

        // a glowing disc on the ground
        pose.pushPose();
        pose.translate(0.5F, 0.06F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-time * 3.0F));
        float dr = (2.0F + 7.0F * c) * sc;
        plane(buffer.getBuffer(tex(CIRCLE)), pose.last(), 0.0F, 0.0F, 0.0F, dr, 0.0F, 0.0F, 0.0F, 0.0F, dr,
                0.9F * c * m, 0.55F * c * m, 0.16F * c * m);
        pose.popPose();

        // at the end a column of light climbs into the sky and the whole thing turns white
        if (c > 0.72F) {
            float u = (c - 0.72F) / 0.28F * m;
            float width = (0.05F + 0.7F * u * u) * sc;
            laser(buffer.getBuffer(glow(TRIM)), pose.last(), 0.5F, oy, 0.5F, 0.5F, 240.0F, 0.5F, width * 3.0F, 0.4F * u, 0.22F * u, 0.06F * u);
            laser(buffer.getBuffer(glow(TRIM)), pose.last(), 0.5F, oy, 0.5F, 0.5F, 240.0F, 0.5F, width, 1.0F * u, 0.92F * u, 0.72F * u);
        }
        float pre = (age - (fuse - 9.0F)) / 9.0F;
        if (pre > 0.0F) {
            float p2 = pre * pre * m;
            billboard(buffer.getBuffer(glow(HALO)), pose.last(), 0.5F, oy, 0.5F, 3.0F * sc + 30.0F * p2, 0.0F, 1.2F * p2, 1.1F * p2, 0.9F * p2);
        }
    }

    // ------------------------------------------------------------------
    // The blast
    // ------------------------------------------------------------------

    private static void blast(PoseStack pose, MultiBufferSource buffer, float e, float time, float sc, float radius, boolean upgrade) {
        float soft = upgrade ? 0.6F : 1.0F;
        float oy = ORB_Y * sc;
        pose.pushPose();
        pose.translate(0.5F, oy, 0.5F);

        // 1. the flash
        if (e < 26.0F) {
            float f = 1.0F - e / 26.0F;
            f *= f;
            billboard(buffer.getBuffer(glow(HALO)), pose.last(), 0.0F, 0.0F, 0.0F, radius * (1.0F + 0.06F * e), 0.0F, 1.4F * f * soft, 1.25F * f * soft, 1.0F * f * soft);
            billboard(buffer.getBuffer(glow(FLARE)), pose.last(), 0.0F, 0.0F, 0.0F, radius * 0.8F, e * 0.02F, 1.0F * f, 1.0F * f, 0.95F * f);
        }

        // 2. the fireball: hot white core, a body of fire, a glow round it
        float grow = easeOut(e / 30.0F);
        float rf = radius * 0.6F * grow + 0.5F;
        float fb = (float) Math.pow(Mth.clamp(1.0F - (e - 25.0F) / 85.0F, 0.0F, 1.0F), 1.2);
        if (!upgrade && fb > 0.01F) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(e * 2.0F));
            sphere(buffer.getBuffer(fxFire()), pose.last(), rf, e * 0.01F, -e * 0.02F, 1.0F * fb, 0.5F * fb, 0.12F * fb);
            pose.popPose();
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(-e * 3.0F));
            pose.mulPose(Axis.XP.rotationDegrees(25.0F));
            sphere(buffer.getBuffer(glow(com.marleyboss.incursion.client.SarcophagusRenderer.ORB)), pose.last(), rf * 0.85F,
                    0.3F - e * 0.01F, e * 0.015F, 1.0F * fb, 0.8F * fb, 0.4F * fb);
            sphere(buffer.getBuffer(glow(com.marleyboss.incursion.client.SarcophagusRenderer.ORB)), pose.last(), rf * 1.22F,
                    0.6F, e * 0.01F, 0.5F * fb, 0.22F * fb, 0.05F * fb);
            pose.popPose();
        }
        float hot = 1.0F - Mth.clamp(e / 48.0F, 0.0F, 1.0F);
        if (!upgrade && hot > 0.01F) {
            sphere(buffer.getBuffer(glow(TRIM)), pose.last(), rf * 0.62F, 0.0F, 0.0F, 1.0F * hot, 0.96F * hot, 0.82F * hot);
        }

        // 2b. the cap of fire that rises on a stem
        float rise = easeOut(e / 75.0F) * radius * 0.9F;
        float cap = (float) Math.pow(Mth.clamp(1.0F - (e - 20.0F) / 95.0F, 0.0F, 1.0F), 1.1);
        if (!upgrade && cap > 0.01F && e > 6.0F) {
            laser(buffer.getBuffer(fxFire()), pose.last(), 0.0F, 0.0F, 0.0F, 0.0F, rise, 0.0F, radius * 0.1F * cap + 0.2F,
                    0.8F * cap, 0.34F * cap, 0.07F * cap);
            pose.pushPose();
            pose.translate(0.0F, rise, 0.0F);
            pose.mulPose(Axis.YP.rotationDegrees(-e * 1.5F));
            sphere(buffer.getBuffer(fxFire()), pose.last(), radius * 0.36F * easeOut(e / 50.0F) + 0.3F, e * 0.007F, e * 0.012F,
                    0.9F * cap, 0.4F * cap, 0.08F * cap);
            pose.popPose();
        }

        // 3. the shock shell
        if (e < 50.0F) {
            float a = (float) Math.pow(1.0F - e / 50.0F, 1.5) * 0.35F * soft;
            sphere(buffer.getBuffer(glow(TRIM)), pose.last(), radius * 2.8F * easeOut(e / 50.0F) + 0.5F, 0.0F, 0.0F, a, a * 0.9F, a * 0.75F);
        }

        // 4. ring waves over the ground (flat, at the foot of the Core)
        for (int j = 0; j < 2; j++) {
            float ee = e - 8.0F * j;
            if (ee < 0.0F || ee > 90.0F) {
                continue;
            }
            float rr = radius * (2.6F - 0.5F * j) * easeOut(ee / 90.0F) + 0.5F;
            float a = (float) Math.pow(1.0F - ee / 90.0F, 1.3);
            float k = j == 0 ? 1.0F : 0.7F;
            plane(buffer.getBuffer(tex(SHOCK)), pose.last(), 0.0F, 0.1F - oy, 0.0F, rr, 0.0F, 0.0F, 0.0F, 0.0F, rr,
                    k * a * soft, k * a * soft * (j == 0 ? 0.55F : 0.85F), k * a * soft * (j == 0 ? 0.15F : 0.6F));
        }
        // a reactor circle opens on the ground and fades
        if (e < 70.0F) {
            float a = (float) Math.pow(1.0F - e / 70.0F, 1.2) * soft;
            float rc = radius * 1.1F * easeOut(e / 40.0F) + 0.5F;
            pose.pushPose();
            pose.translate(0.0F, 0.12F - oy, 0.0F);
            pose.mulPose(Axis.YP.rotationDegrees(e * 1.5F));
            plane(buffer.getBuffer(tex(CIRCLE)), pose.last(), 0.0F, 0.0F, 0.0F, rc, 0.0F, 0.0F, 0.0F, 0.0F, rc, 1.0F * a, 0.75F * a, 0.3F * a);
            pose.popPose();
        }

        // 5. the pillar of light
        if (e < 110.0F) {
            float f = 1.0F - e / 110.0F;
            float width = radius * 0.1F * (float) Math.pow(f, 0.7) + 0.3F;
            laser(buffer.getBuffer(glow(TRIM)), pose.last(), 0.0F, 0.0F, 0.0F, 0.0F, 240.0F, 0.0F, width, 1.0F * f, 0.6F * f, 0.22F * f);
            float wf = 1.0F - Mth.clamp(e / 60.0F, 0.0F, 1.0F);
            if (wf > 0.0F) {
                laser(buffer.getBuffer(glow(TRIM)), pose.last(), 0.0F, 0.0F, 0.0F, 0.0F, 240.0F, 0.0F, width * 0.4F, 1.0F * wf, 1.0F * wf, 0.9F * wf);
            }
        }

        // 6. secondary blasts inside the fireball
        for (int k = 0; k < (upgrade ? 0 : 5); k++) {
            float local = e - (6.0F + 9.0F * k);
            if (local < 0.0F || local >= 18.0F) {
                continue;
            }
            float a = 1.0F - local / 18.0F;
            float ox = (rnd(k, 71) * 2.0F - 1.0F) * radius * 0.45F;
            float oyy = (rnd(k, 72) * 1.2F - 0.2F) * radius * 0.4F;
            float oz = (rnd(k, 73) * 2.0F - 1.0F) * radius * 0.45F;
            billboard(buffer.getBuffer(glow(HALO)), pose.last(), ox, oyy, oz, radius * 0.35F * (0.6F + 0.4F * a), 0.0F, 1.0F * a, 0.6F * a, 0.2F * a);
            billboard(buffer.getBuffer(glow(FLARE)), pose.last(), ox, oyy, oz, radius * 0.2F, local * 0.1F, 1.0F * a, 0.95F * a, 0.8F * a);
        }

        // 7. streaks of fire
        if (e < 60.0F) {
            float f = (float) Math.pow(1.0F - e / 60.0F, 1.2);
            float reachS = easeOut(e / 26.0F);
            for (int i = 0; i < (upgrade ? 24 : 40); i++) {
                float[] dd = dir(i, 1, 0.25F);
                float len = radius * (0.9F + 1.2F * rnd(i, 4)) * reachS;
                float from = radius * 0.15F * reachS;
                float width = 0.18F * (1.0F - e / 60.0F) * (0.6F + 0.8F * rnd(i, 5));
                laser(buffer.getBuffer(glow(TRIM)), pose.last(), dd[0] * from, dd[1] * from, dd[2] * from, dd[0] * len, dd[1] * len, dd[2] * len,
                        width * 2.4F, 0.9F * f * 0.5F, 0.4F * f * 0.5F, 0.08F * f * 0.5F);
                laser(buffer.getBuffer(glow(TRIM)), pose.last(), dd[0] * from, dd[1] * from, dd[2] * from, dd[0] * len, dd[1] * len, dd[2] * len,
                        width, 1.0F * f, 0.9F * f, 0.7F * f);
            }
        }

        // 8. embers that fly out and fall back
        for (int i = 0; i < (upgrade ? 70 : 150); i++) {
            float life = 70.0F + 70.0F * rnd(i, 8);
            if (e >= life) {
                continue;
            }
            float[] dd = dir(i + 400, 2, 0.35F);
            float v = radius * (0.006F + 0.014F * rnd(i, 9));
            float x = dd[0] * v * e;
            float y = dd[1] * v * e - 0.5F * 0.025F * e * e;
            float z = dd[2] * v * e;
            float a = (float) Math.pow(1.0F - e / life, 0.8);
            float heat = 1.0F - e / life;
            billboard(buffer.getBuffer(glow(FLARE)), pose.last(), x, y, z, 0.18F + 0.3F * rnd(i, 10), e * 0.05F + i,
                    1.0F * a, (0.35F + 0.4F * heat) * a, 0.08F * a);
        }
        pose.popPose();
    }

    // ------------------------------------------------------------------
    // The camera shakes with the blast
    // ------------------------------------------------------------------

    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float partial = (float) event.getPartialTick();
        Vec3 cam = event.getCamera().getPosition();
        float strongest = 0.0F;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof CoreBlastEntity blast)) {
                continue;
            }
            float age = (mc.level.getGameTime() - blast.getStart()) + partial;
            float falloff = (float) Mth.clamp(1.0 - cam.distanceTo(blast.position()) / 110.0, 0.0, 1.0);
            float amp;
            if (age < 0.0F) {
                continue;
            } else if (age < CoreBlastEntity.FUSE) {
                float c = age / CoreBlastEntity.FUSE;
                amp = 0.12F + 1.1F * c * c;
            } else {
                float e = age - CoreBlastEntity.FUSE;
                float f = Mth.clamp(1.0F - e / 80.0F, 0.0F, 1.0F);
                amp = 7.0F * f * f;
            }
            strongest = Math.max(strongest, amp * falloff);
        }
        if (strongest < 0.01F) {
            return;
        }
        float t = mc.level.getGameTime() + partial;
        event.setPitch(event.getPitch() + strongest * 0.6F * (Mth.sin(t * 1.9F) + 0.6F * Mth.sin(t * 3.7F + 1.0F)));
        event.setYaw(event.getYaw() + strongest * 0.6F * (Mth.sin(t * 2.3F + 0.5F) + 0.6F * Mth.sin(t * 4.1F)));
        event.setRoll(event.getRoll() + strongest * 0.4F * Mth.sin(t * 2.9F + 2.0F));
    }
}
