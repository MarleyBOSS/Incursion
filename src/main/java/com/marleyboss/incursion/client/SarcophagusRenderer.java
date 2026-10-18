package com.marleyboss.incursion.client;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlock;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.registry.ModSounds;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

/**
 * Draws the Core block: the Sarcophagus and, once the ritual is done, the burning Core. Modelled on the artwork
 * (docs/sarkophag_referenz.png): black marble with a beaded gold border, a lower half with a V notch in every wall, an
 * upper half that is a thin slab with four glass triangles hanging from it, and a fire orb floating between the halves.
 *
 * <p><b>Shape</b>: the lower half is solid up to the surface {@code y = 0.5 + min(|x - 0.5|, |z - 0.5|)}, so every wall
 * has a V notch down to half height and the four corners rise to a point. The upper half completes the cube: a flat slab
 * on top and four glass triangles hanging in the notches. Shut, the ritual items lie in the cavity behind the glass: the
 * Core Rune floats in the middle as a violet crystal, gunpowder fills the north-south valley of the floor, blaze powder
 * the east-west valley (the eight combinations are the stages of the block).</p>
 *
 * <p><b>Active</b>: the upper half rises by {@link #LIFT} and floats, turned by {@link #TWIST} degrees against the lower
 * one; between them burns the Core, a ball of fire with a white-hot heart, glow, a star of light and thin golden rays to
 * the tips and corners. The activation (the block state turns {@code active}) is animated.</p>
 *
 * <p><b>Order matters</b>: the solid parts are drawn first and flushed, then everything additive (glow, orb, rays), and
 * the glass last. Glass writes depth; drawn earlier it would hide whatever lies behind it (the items, the orb).</p>
 *
 * <p>All geometry is built from triangles because a block model can only have axis-aligned boxes. Faces are oriented
 * automatically against a given outward direction, so the winding order cannot be wrong.</p>
 */
final class SarcophagusRenderer {
    private static final String NS = Incursion.MODID;
    private static final ResourceLocation WALL = block("sarkophag_wall");
    private static final ResourceLocation TOP = block("sarkophag_top");
    private static final ResourceLocation MARBLE = block("sarkophag_marble");
    private static final ResourceLocation INNER = block("sarkophag_inner");
    private static final ResourceLocation GOLD = block("sarkophag_gold");
    private static final ResourceLocation GUNPOWDER = block("sarkophag_gunpowder");
    private static final ResourceLocation BLAZE = block("sarkophag_blaze");
    private static final ResourceLocation TRIM = effect("trim");
    private static final ResourceLocation ORB = effect("orb");
    private static final ResourceLocation HALO = effect("halo");
    private static final ResourceLocation FLARE = effect("flare");
    private static final ResourceLocation CRYSTAL = effect("crystal");
    private static final ResourceLocation RING = effect("rune_circle");

    /** Time the activation takes (ticks). */
    private static final float OPEN_TICKS = 80.0F;
    /** How far the upper half rises. */
    private static final float LIFT = 0.8F;
    /** How far the upper half is turned against the lower one (degrees). */
    private static final float TWIST = 35.0F;
    /** Thickness of the upper slab and the gap to the wall plane. */
    private static final float SLAB = 0.05F;
    private static final float INSET = 0.004F;
    /** Height of the orb's centre, relative to the block. */
    private static final float ORB_Y = 1.12F;
    private static final float ORB_RADIUS = 0.15F;

    private static final float[] C = {0.5F, 0.5F, 0.5F};
    private static final float[] N = {0.5F, 0.5F, 0.0F};
    private static final float[] S = {0.5F, 0.5F, 1.0F};
    private static final float[] W = {0.0F, 0.5F, 0.5F};
    private static final float[] E = {1.0F, 0.5F, 0.5F};
    private static final float[] NW = {0.0F, 1.0F, 0.0F};
    private static final float[] NE = {1.0F, 1.0F, 0.0F};
    private static final float[] SW = {0.0F, 1.0F, 1.0F};
    private static final float[] SE = {1.0F, 1.0F, 1.0F};

    /** The eight triangles of the lower half's top surface: (corner, wall middle, centre). */
    private static final float[][][] FLOOR = {
            {NW, N, C}, {NW, W, C}, {NE, N, C}, {NE, E, C}, {SW, S, C}, {SW, W, C}, {SE, S, C}, {SE, E, C}
    };

    /** Gold bars of the lower half, as {x1, y1, z1, x2, y2, z2}: bottom, vertical edges, the V notches. */
    private static final float[][] LOWER_BARS = {
            {0, 0, 0, 1, 0, 0}, {1, 0, 0, 1, 0, 1}, {1, 0, 1, 0, 0, 1}, {0, 0, 1, 0, 0, 0},
            {0, 0, 0, 0, 1, 0}, {1, 0, 0, 1, 1, 0}, {1, 0, 1, 1, 1, 1}, {0, 0, 1, 0, 1, 1},
            {0, 1, 0, 0.5F, 0.5F, 0}, {0.5F, 0.5F, 0, 1, 1, 0}, {0, 1, 1, 0.5F, 0.5F, 1}, {0.5F, 0.5F, 1, 1, 1, 1},
            {0, 1, 0, 0, 0.5F, 0.5F}, {0, 0.5F, 0.5F, 0, 1, 1}, {1, 1, 0, 1, 0.5F, 0.5F}, {1, 0.5F, 0.5F, 1, 1, 1}
    };
    /** Gold bars of the upper half: the slab's top edge and the edges of the four glass triangles. */
    private static final float[][] UPPER_BARS = {
            {0, 1, 0, 1, 1, 0}, {1, 1, 0, 1, 1, 1}, {1, 1, 1, 0, 1, 1}, {0, 1, 1, 0, 1, 0},
            {0, 1, 0, 0.5F, 0.5F, 0}, {0.5F, 0.5F, 0, 1, 1, 0}, {0, 1, 1, 0.5F, 0.5F, 1}, {0.5F, 0.5F, 1, 1, 1, 1},
            {0, 1, 0, 0, 0.5F, 0.5F}, {0, 0.5F, 0.5F, 0, 1, 1}, {1, 1, 0, 1, 0.5F, 0.5F}, {1, 0.5F, 0.5F, 1, 1, 1}
    };

    private static final Map<CoreBlockEntity, State> STATES = new WeakHashMap<>();

    private static final class State {
        boolean seen;
        boolean wasActive;
        float open;
        float last;
        float nextHum;
        float nextSpark;
    }

    private SarcophagusRenderer() {
    }

    private static ResourceLocation block(String name) {
        return ResourceLocation.fromNamespaceAndPath(NS, "block/" + name);
    }

    private static ResourceLocation effect(String name) {
        return ResourceLocation.fromNamespaceAndPath(NS, "textures/effect/" + name + ".png");
    }

    private static float ease(float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static TextureAtlasSprite sprite(ResourceLocation location) {
        return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(location);
    }

    /** Draws everything solid that was asked for so far, so that what comes next is tested against its depth. */
    private static void flushSolid(MultiBufferSource buffer) {
        if (buffer instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(Sheets.solidBlockSheet());
        }
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** The block in the world. */
    static void render(CoreBlockEntity core, float partialTick, PoseStack pose, MultiBufferSource buffer, Level level) {
        State state = STATES.computeIfAbsent(core, key -> new State());
        float time = level.getGameTime() + partialTick;
        float dt = Mth.clamp(time - state.last, 0.0F, 4.0F);
        state.last = time;
        BlockState blockState = core.getBlockState();
        boolean active = core.isActive();
        boolean hasRune = blockState.getValue(CoreBlock.RUNE);
        boolean hasGunpowder = blockState.getValue(CoreBlock.GUNPOWDER);
        boolean hasBlaze = blockState.getValue(CoreBlock.BLAZE);
        boolean ready = hasRune && hasGunpowder && hasBlaze;
        double px = core.getBlockPos().getX() + 0.5;
        double py = core.getBlockPos().getY() + 0.5;
        double pz = core.getBlockPos().getZ() + 0.5;
        if (!state.seen) {
            state.seen = true;
            state.open = active ? 1.0F : 0.0F; // a Core that was already active when it came into view stays open
        } else if (active && !state.wasActive) {
            state.open = 0.0F;
            level.playLocalSound(px, py, pz, ModSounds.CORE_OPEN.get(), SoundSource.BLOCKS, 1.6F, 1.0F, false);
        }
        state.wasActive = active;
        if (active) {
            state.open = Math.min(1.0F, state.open + dt / OPEN_TICKS);
        } else {
            state.open = 0.0F;
        }
        float t = state.open;
        if (active && t >= 1.0F && time >= state.nextHum) {
            level.playLocalSound(px, py, pz, ModSounds.CORE_HUM.get(), SoundSource.BLOCKS, 0.45F, 1.0F, false);
            state.nextHum = time + 78.0F;
        }

        // Light: from above the block (its own position is solid).
        int above = LevelRenderer.getLightColor(level, core.getBlockPos().above());
        int light = LightTexture.pack(Math.max(LightTexture.block(above), active ? 10 : 6), LightTexture.sky(above));

        float lift = 0.0F;
        float twist = 0.0F;
        float shake = 0.0F;
        float orb = 0.0F;
        float glow = ready ? 0.7F + 0.3F * Mth.sin(time * 0.12F) : 0.45F;
        if (active) {
            float rise = ease((t - 0.2F) / 0.55F);
            lift = rise * LIFT;
            twist = rise * TWIST;
            shake = t < 0.3F ? Mth.sin(time * 2.9F) * 0.012F * (t / 0.3F) : 0.0F;
            orb = ease((t - 0.45F) / 0.55F);
            glow = 0.7F + 0.3F * ease(t / 0.5F);
            if (t >= 1.0F) {
                lift += 0.025F * Mth.sin(time * 0.06F);
                twist += 3.0F * Mth.sin(time * 0.045F);
            }
            if (t < 0.85F && time >= state.nextSpark) {
                state.nextSpark = time + 1.0F;
                double ang = level.random.nextDouble() * Math.PI * 2.0;
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, px + Math.cos(ang) * 0.5, py + 0.4 + level.random.nextDouble() * 0.7,
                        pz + Math.sin(ang) * 0.5, 0.0, 0.02, 0.0);
            }
        }
        float contents = active ? 1.0F - ease(t / 0.45F) : 1.0F; // the ritual items are consumed while the Core ignites

        // 1. Solid parts.
        VertexConsumer solid = buffer.getBuffer(Sheets.solidBlockSheet());
        drawLower(pose.last(), solid, light);
        drawBars(pose.last(), solid, LOWER_BARS, 0.011F, light);
        if (contents > 0.0F) {
            drawHeaps(pose.last(), solid, light, hasGunpowder, hasBlaze);
        }
        pose.pushPose();
        pose.translate(0.5F + shake, lift, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(twist));
        pose.translate(-0.5F, 0.0F, -0.5F);
        drawSlab(pose.last(), solid, light);
        drawBars(pose.last(), solid, UPPER_BARS, 0.0125F, light);
        pose.popPose();
        flushSolid(buffer);

        // 2. Additive glow: ritual items, the orb, rays.
        if (contents > 0.0F) {
            drawItemGlow(pose, buffer, time, contents, hasRune, hasBlaze, ready);
        }
        if (orb > 0.0F) {
            float flash = t < 1.0F ? Mth.sin(Mth.clamp((t - 0.45F) / 0.2F, 0.0F, 1.0F) * (float) Math.PI) : 0.0F;
            drawOrb(pose, buffer, time, orb, flash);
            drawRays(pose, buffer, time, orb, lift, twist);
        }

        // 3. Glass, last.
        pose.pushPose();
        pose.translate(0.5F + shake, lift, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(twist));
        pose.translate(-0.5F, 0.0F, -0.5F);
        drawGlass(pose.last(), buffer, light);
        pose.popPose();
        if (glow > 0.0F && !active) {
            // A shut Sarcophagus glimmers along its gold when the ritual is ready.
            drawGlimmer(pose, buffer, ready ? glow : 0.0F);
        }
    }

    /** The block as an item: shut, empty. */
    static void renderItem(PoseStack pose, MultiBufferSource buffer, int light) {
        VertexConsumer solid = buffer.getBuffer(Sheets.solidBlockSheet());
        drawLower(pose.last(), solid, light);
        drawBars(pose.last(), solid, LOWER_BARS, 0.011F, light);
        drawSlab(pose.last(), solid, light);
        drawBars(pose.last(), solid, UPPER_BARS, 0.0125F, light);
        flushSolid(buffer);
        drawGlass(pose.last(), buffer, light);
    }

    // ------------------------------------------------------------------
    // Solid parts
    // ------------------------------------------------------------------

    private static void drawLower(PoseStack.Pose pose, VertexConsumer vc, int light) {
        TextureAtlasSprite wall = sprite(WALL);
        TextureAtlasSprite marble = sprite(MARBLE);
        poly(vc, pose, light, marble, 0, 0, -1, 0,
                new float[]{0, 0, 0}, new float[]{1, 0, 0}, new float[]{1, 0, 1}, new float[]{0, 0, 1});
        wall(vc, pose, light, wall, 0.0F, true, 0, 0, -1);
        wall(vc, pose, light, wall, 1.0F, true, 0, 0, 1);
        wall(vc, pose, light, wall, 0.0F, false, -1, 0, 0);
        wall(vc, pose, light, wall, 1.0F, false, 1, 0, 0);
        for (float[][] tri : FLOOR) {
            poly(vc, pose, light, marble, 0, 0, 1, 0, tri[0], tri[1], tri[2]);
        }
    }

    /** One wall of the lower half. {@code alongX}: the wall lies in the x-y plane at z = {@code at}, else in the z-y plane at x. */
    private static void wall(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite sprite, float at,
                             boolean alongX, float nx, float ny, float nz) {
        int mode = alongX ? 1 : 2;
        poly(vc, pose, light, sprite, mode, nx, ny, nz,
                wp(0, 0, at, alongX), wp(1, 0, at, alongX), wp(1, 0.5F, at, alongX), wp(0, 0.5F, at, alongX));
        poly(vc, pose, light, sprite, mode, nx, ny, nz, wp(0, 0.5F, at, alongX), wp(0.5F, 0.5F, at, alongX), wp(0, 1, at, alongX));
        poly(vc, pose, light, sprite, mode, nx, ny, nz, wp(0.5F, 0.5F, at, alongX), wp(1, 0.5F, at, alongX), wp(1, 1, at, alongX));
    }

    private static float[] wp(float s, float y, float at, boolean alongX) {
        return alongX ? new float[]{s, y, at} : new float[]{at, y, s};
    }

    /** The upper slab: a thin plate, marble on top, dark stone underneath. Drawn in the pose of the upper half. */
    private static void drawSlab(PoseStack.Pose pose, VertexConsumer vc, int light) {
        TextureAtlasSprite top = sprite(TOP);
        TextureAtlasSprite marble = sprite(MARBLE);
        TextureAtlasSprite inner = sprite(INNER);
        float a = INSET;
        float b = 1.0F - INSET;
        float y0 = 1.0F - SLAB;
        poly(vc, pose, light, top, 0, 0, 1, 0,
                new float[]{a, 1, a}, new float[]{b, 1, a}, new float[]{b, 1, b}, new float[]{a, 1, b});
        poly(vc, pose, light, inner, 0, 0, -1, 0,
                new float[]{a, y0, a}, new float[]{b, y0, a}, new float[]{b, y0, b}, new float[]{a, y0, b});
        poly(vc, pose, light, marble, 1, 0, 0, -1,
                new float[]{a, y0, a}, new float[]{b, y0, a}, new float[]{b, 1, a}, new float[]{a, 1, a});
        poly(vc, pose, light, marble, 1, 0, 0, 1,
                new float[]{a, y0, b}, new float[]{b, y0, b}, new float[]{b, 1, b}, new float[]{a, 1, b});
        poly(vc, pose, light, marble, 2, -1, 0, 0,
                new float[]{a, y0, a}, new float[]{a, y0, b}, new float[]{a, 1, b}, new float[]{a, 1, a});
        poly(vc, pose, light, marble, 2, 1, 0, 0,
                new float[]{b, y0, a}, new float[]{b, y0, b}, new float[]{b, 1, b}, new float[]{b, 1, a});
    }

    /** Gold bars (square prisms), a little brighter than the surroundings so the gold glows. */
    private static void drawBars(PoseStack.Pose pose, VertexConsumer vc, float[][] bars, float half, int light) {
        TextureAtlasSprite gold = sprite(GOLD);
        int bright = LightTexture.pack(Math.max(LightTexture.block(light), 12), LightTexture.sky(light));
        for (float[] e : bars) {
            bar(vc, pose, bright, gold, e[0], e[1], e[2], e[3], e[4], e[5], half);
        }
    }

    private static void bar(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite gold, float ax, float ay,
                            float az, float bx, float by, float bz, float h) {
        float tx = bx - ax;
        float ty = by - ay;
        float tz = bz - az;
        float len = Mth.sqrt(tx * tx + ty * ty + tz * tz);
        if (len < 1.0E-4F) {
            return;
        }
        tx /= len;
        ty /= len;
        tz /= len;
        // Extend both ends so that bars meeting in a corner overlap.
        ax -= tx * h;
        ay -= ty * h;
        az -= tz * h;
        bx += tx * h;
        by += ty * h;
        bz += tz * h;
        // p = t x up (or x when vertical), q = t x p.
        float px = -tz;
        float py = 0.0F;
        float pz = tx;
        float pl = Mth.sqrt(px * px + pz * pz);
        if (pl < 1.0E-3F) {
            px = 1.0F;
            pz = 0.0F;
            pl = 1.0F;
        }
        px = px / pl * h;
        pz = pz / pl * h;
        float qx = ty * pz - tz * py;
        float qy = tz * px - tx * pz;
        float qz = tx * py - ty * px;
        float ql = Mth.sqrt(qx * qx + qy * qy + qz * qz);
        qx = qx / ql * h;
        qy = qy / ql * h;
        qz = qz / ql * h;
        side(vc, pose, light, gold, ax, ay, az, bx, by, bz, px, py, pz, qx, qy, qz);
        side(vc, pose, light, gold, ax, ay, az, bx, by, bz, qx, qy, qz, -px, -py, -pz);
        side(vc, pose, light, gold, ax, ay, az, bx, by, bz, -px, -py, -pz, -qx, -qy, -qz);
        side(vc, pose, light, gold, ax, ay, az, bx, by, bz, -qx, -qy, -qz, px, py, pz);
    }

    /** One side of a bar: the strip at offset o (the outward direction) between o + w and o - w. */
    private static void side(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite gold, float ax, float ay,
                             float az, float bx, float by, float bz, float ox, float oy, float oz, float wx, float wy,
                             float wz) {
        poly(vc, pose, light, gold, 3, ox, oy, oz,
                new float[]{ax + ox + wx, ay + oy + wy, az + oz + wz}, new float[]{bx + ox + wx, by + oy + wy, bz + oz + wz},
                new float[]{bx + ox - wx, by + oy - wy, bz + oz - wz}, new float[]{ax + ox - wx, ay + oy - wy, az + oz - wz});
    }

    /** Heaps of gunpowder (north-south valley) and blaze powder (east-west valley), filling the valleys up to a ridge. */
    private static void drawHeaps(PoseStack.Pose pose, VertexConsumer vc, int light, boolean gunpowder, boolean blaze) {
        if (gunpowder) {
            heap(vc, pose, light, sprite(GUNPOWDER), true, 0.77F);
        }
        if (blaze) {
            int bright = LightTexture.pack(Math.max(LightTexture.block(light), 13), LightTexture.sky(light));
            heap(vc, pose, bright, sprite(BLAZE), false, 0.80F);
        }
    }

    private static void heap(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite sprite, boolean alongZ,
                             float ridge) {
        float base = 0.69F; // valley width at this height: the floor there is 0.19 from the middle line
        float lo = 0.5F - 0.19F;
        float hi = 0.5F + 0.19F;
        float e0 = 0.035F;
        float e1 = 0.965F;
        // Slopes, ends. Coordinates are (across, along); mapped to (x, z) or (z, x).
        float[][] left = {h(lo, e0, base, alongZ), h(0.5F, e0, ridge, alongZ), h(0.5F, e1, ridge, alongZ), h(lo, e1, base, alongZ)};
        float[][] right = {h(hi, e0, base, alongZ), h(0.5F, e0, ridge, alongZ), h(0.5F, e1, ridge, alongZ), h(hi, e1, base, alongZ)};
        float ux = alongZ ? 1 : 0;
        float uz = alongZ ? 0 : 1;
        poly(vc, pose, light, sprite, 0, -ux * 0.7F, 1, -uz * 0.7F, left[0], left[1], left[2], left[3]);
        poly(vc, pose, light, sprite, 0, ux * 0.7F, 1, uz * 0.7F, right[0], right[1], right[2], right[3]);
        poly(vc, pose, light, sprite, 0, alongZ ? 0 : -1, 0.3F, alongZ ? -1 : 0,
                h(lo, e0, base, alongZ), h(hi, e0, base, alongZ), h(0.5F, e0, ridge, alongZ));
        poly(vc, pose, light, sprite, 0, alongZ ? 0 : 1, 0.3F, alongZ ? 1 : 0,
                h(lo, e1, base, alongZ), h(hi, e1, base, alongZ), h(0.5F, e1, ridge, alongZ));
    }

    private static float[] h(float across, float along, float y, boolean alongZ) {
        return alongZ ? new float[]{across, y, along} : new float[]{along, y, across};
    }

    // ------------------------------------------------------------------
    // Glass (drawn last)
    // ------------------------------------------------------------------

    private static void drawGlass(PoseStack.Pose pose, MultiBufferSource buffer, int light) {
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(GLASS_TEXTURE));
        int bright = LightTexture.pack(Math.max(LightTexture.block(light), 10), LightTexture.sky(light));
        glassTri(vc, pose, bright, 0, 1, 0, 1, 1, 0, N, 0, 0, -1);
        glassTri(vc, pose, bright, 0, 1, 1, 1, 1, 1, S, 0, 0, 1);
        glassTri(vc, pose, bright, 0, 1, 0, 0, 1, 1, W, -1, 0, 0);
        glassTri(vc, pose, bright, 1, 1, 0, 1, 1, 1, E, 1, 0, 0);
    }

    /** A glass triangle: the top edge from (ax, ay, az) to (bx, by, bz), the tip c. u runs along the top edge, v down to the tip. */
    private static void glassTri(VertexConsumer vc, PoseStack.Pose pose, int light, float ax, float ay, float az, float bx,
                                 float by, float bz, float[] c, float nx, float ny, float nz) {
        glassVertex(vc, pose, light, ax, ay, az, 0.0F, 0.0F, nx, ny, nz);
        glassVertex(vc, pose, light, bx, by, bz, 1.0F, 0.0F, nx, ny, nz);
        glassVertex(vc, pose, light, c[0], c[1], c[2], 0.5F, 1.0F, nx, ny, nz);
        glassVertex(vc, pose, light, c[0], c[1], c[2], 0.5F, 1.0F, nx, ny, nz);
    }

    private static void glassVertex(VertexConsumer vc, PoseStack.Pose pose, int light, float x, float y, float z, float u,
                                    float v, float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }

    private static final ResourceLocation GLASS_TEXTURE = ResourceLocation.fromNamespaceAndPath(NS, "textures/block/sarkophag_glass.png");

    // ------------------------------------------------------------------
    // Additive glow: ritual items, orb, rays
    // ------------------------------------------------------------------

    /** The Core Rune as a floating violet crystal and a flicker over the blaze powder. */
    private static void drawItemGlow(PoseStack pose, MultiBufferSource buffer, float time, float scale, boolean rune,
                                     boolean blaze, boolean ready) {
        if (rune) {
            float cy = 0.80F + 0.03F * Mth.sin(time * 0.08F);
            pose.pushPose();
            pose.translate(0.5F, cy, 0.5F);
            pose.mulPose(Axis.YP.rotationDegrees(time * 2.4F));
            octahedron(buffer.getBuffer(RenderType.eyes(CRYSTAL)), pose.last(), 0.09F * scale, 0.17F * scale,
                    0.8F, 0.6F, 1.0F);
            pose.popPose();
            VertexConsumer glow = buffer.getBuffer(RenderType.eyes(HALO));
            float k = (0.55F + 0.1F * Mth.sin(time * 0.15F)) * scale;
            billboard(glow, pose.last(), 0.5F, cy, 0.5F, 0.30F * scale, 0.0F, 0.6F * k, 0.35F * k, 1.0F * k);
            if (ready) {
                billboard(glow, pose.last(), 0.5F, cy, 0.5F, 0.42F * scale, 0.0F, 1.0F * k, 0.75F * k, 0.25F * k);
            }
        }
        if (blaze) {
            VertexConsumer glow = buffer.getBuffer(RenderType.eyes(HALO));
            float flicker = 0.75F + 0.25F * Mth.sin(time * 0.7F) * Mth.sin(time * 0.31F + 1.0F);
            billboard(glow, pose.last(), 0.5F, 0.84F, 0.5F, 0.36F * scale, 0.0F, 0.9F * flicker * scale, 0.45F * flicker * scale, 0.08F * scale);
        }
    }

    /** A faint pulse along the gold of a shut Sarcophagus whose ritual is complete. */
    private static void drawGlimmer(PoseStack pose, MultiBufferSource buffer, float glow) {
        if (glow <= 0.0F) {
            return;
        }
        VertexConsumer vc = buffer.getBuffer(RenderType.eyes(TRIM));
        PoseStack.Pose p = pose.last();
        float k = 0.22F * glow;
        for (float[] e : LOWER_BARS) {
            laser(vc, p, e[0], e[1], e[2], e[3], e[4], e[5], 0.02F, 1.0F * k, 0.7F * k, 0.15F * k);
        }
        for (float[] e : UPPER_BARS) {
            laser(vc, p, e[0], e[1], e[2], e[3], e[4], e[5], 0.02F, 1.0F * k, 0.7F * k, 0.15F * k);
        }
    }

    private static void drawOrb(PoseStack pose, MultiBufferSource buffer, float time, float scale, float flash) {
        float pulse = 0.88F + 0.12F * Mth.sin(time * 0.19F);
        float cy = ORB_Y + 0.02F * Mth.sin(time * 0.08F);
        float flick = 0.94F + 0.06F * Mth.sin(time * 0.43F) * Mth.sin(time * 0.27F + 2.0F);
        // Fire: two shells of fire texture that flow and turn against each other, and a white-hot heart.
        VertexConsumer fire = buffer.getBuffer(RenderType.eyes(ORB));
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 1.7F));
        pose.mulPose(Axis.XP.rotationDegrees(14.0F * Mth.sin(time * 0.05F)));
        sphere(fire, pose.last(), ORB_RADIUS * scale * flick, time * 0.006F, -time * 0.012F, 1.0F * pulse, 1.0F * pulse, 1.0F * pulse);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-time * 2.6F));
        pose.mulPose(Axis.ZP.rotationDegrees(25.0F));
        sphere(fire, pose.last(), ORB_RADIUS * 1.28F * scale * flick, 0.37F - time * 0.008F, -time * 0.02F, 0.55F * pulse, 0.5F * pulse, 0.45F * pulse);
        pose.popPose();
        VertexConsumer heart = buffer.getBuffer(RenderType.eyes(TRIM));
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        sphere(heart, pose.last(), ORB_RADIUS * 0.55F * scale, 0.0F, 0.0F, 1.0F, 0.95F, 0.72F);
        pose.popPose();
        // Light around it.
        VertexConsumer glow = buffer.getBuffer(RenderType.eyes(HALO));
        PoseStack.Pose p = pose.last();
        float s = scale;
        billboard(glow, p, 0.5F, cy, 0.5F, 0.75F * s, 0.0F, 0.55F * pulse * s, 0.28F * pulse * s, 0.05F * s);
        billboard(glow, p, 0.5F, cy, 0.5F, 0.32F * s, 0.0F, 1.0F * pulse * s, 0.8F * pulse * s, 0.35F * s);
        if (flash > 0.0F) {
            billboard(glow, p, 0.5F, cy, 0.5F, (0.8F + 1.4F * flash) * s, 0.0F, flash, 0.8F * flash, 0.35F * flash);
        }
        // Star of light: two turning stars of different size.
        VertexConsumer flare = buffer.getBuffer(RenderType.eyes(FLARE));
        float fk = (0.8F + 0.2F * Mth.sin(time * 0.31F)) * s;
        billboard(flare, p, 0.5F, cy, 0.5F, (0.85F + 0.5F * flash) * s, time * 0.012F, 1.0F * fk, 0.8F * fk, 0.35F * fk);
        billboard(flare, p, 0.5F, cy, 0.5F, 0.55F * s, 0.4F - time * 0.02F, 0.8F * fk, 0.6F * fk, 0.25F * fk);
        // A ring of runes turning around the orb.
        VertexConsumer ring = buffer.getBuffer(RenderType.eyes(RING));
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 2.0F));
        float rk = 0.5F * s * pulse;
        plane(ring, pose.last(), 0, 0, 0, 0.42F * s, 0, 0, 0, 0, 0.42F * s, 1.0F * rk, 0.72F * rk, 0.22F * rk);
        pose.popPose();
    }

    /** Thin golden rays from the orb to the corners and valley points of the lower half and to the tips and middle of the upper half. */
    private static void drawRays(PoseStack pose, MultiBufferSource buffer, float time, float scale, float lift, float twist) {
        VertexConsumer vc = buffer.getBuffer(RenderType.eyes(TRIM));
        PoseStack.Pose p = pose.last();
        float flicker = 0.8F + 0.2F * Mth.sin(time * 0.9F);
        float k = 0.95F * scale * flicker;
        float cy = ORB_Y;
        float[][] lowerEnds = {NW, NE, SE, SW, N, S, W, E};
        for (float[] end : lowerEnds) {
            ray(vc, p, 0.5F, cy, 0.5F, end[0], end[1], end[2], k);
        }
        // The upper half moves: its end points are turned and lifted the same way as the half itself.
        double rad = Math.toRadians(twist);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        ray(vc, p, 0.5F, cy, 0.5F, 0.5F, 1.0F - SLAB + lift, 0.5F, k);
        float[][] tips = {N, S, W, E};
        for (float[] tip : tips) {
            float dx = tip[0] - 0.5F;
            float dz = tip[2] - 0.5F;
            ray(vc, p, 0.5F, cy, 0.5F, 0.5F + dx * cos + dz * sin, tip[1] + lift, 0.5F - dx * sin + dz * cos, k);
        }
    }

    /** A thin bright ray with a wider, fainter glow around it. */
    private static void ray(VertexConsumer vc, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by, float bz,
                            float k) {
        laser(vc, pose, ax, ay, az, bx, by, bz, 0.004F, 1.0F * k, 0.78F * k, 0.3F * k);
        laser(vc, pose, ax, ay, az, bx, by, bz, 0.016F, 0.5F * k, 0.28F * k, 0.05F * k);
    }

    // ------------------------------------------------------------------
    // Additive helpers
    // ------------------------------------------------------------------

    /** A square facing the camera (centre, half size, turned by rot radians). */
    private static void billboard(VertexConsumer vc, PoseStack.Pose pose, float cx, float cy, float cz, float half,
                                  float rot, float r, float g, float b) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        float cs = Mth.cos(rot) * half;
        float sn = Mth.sin(rot) * half;
        plane(vc, pose, cx, cy, cz,
                left.x() * cs + up.x() * sn, left.y() * cs + up.y() * sn, left.z() * cs + up.z() * sn,
                -left.x() * sn + up.x() * cs, -left.y() * sn + up.y() * cs, -left.z() * sn + up.z() * cs, r, g, b);
    }

    /** An octahedron (double sided): tip up and down, four points around the middle. */
    private static void octahedron(VertexConsumer vc, PoseStack.Pose pose, float radius, float height, float r, float g, float b) {
        float[] top = {0, height, 0};
        float[] bottom = {0, -height, 0};
        float[][] ring = {{radius, 0, 0}, {0, 0, radius}, {-radius, 0, 0}, {0, 0, -radius}};
        int ir = Mth.clamp((int) (r * 255.0F), 0, 255);
        int ig = Mth.clamp((int) (g * 255.0F), 0, 255);
        int ib = Mth.clamp((int) (b * 255.0F), 0, 255);
        for (int i = 0; i < 4; i++) {
            float[] a = ring[i];
            float[] c = ring[(i + 1) % 4];
            glowTri(vc, pose, top, a, c, i * 0.25F, ir, ig, ib);
            glowTri(vc, pose, bottom, a, c, i * 0.25F, ir, ig, ib);
        }
    }

    private static void glowTri(VertexConsumer vc, PoseStack.Pose pose, float[] a, float[] b, float[] c, float u, int r, int g, int bl) {
        glowVertex(vc, pose, a[0], a[1], a[2], u + 0.125F, 0.0F, r, g, bl);
        glowVertex(vc, pose, b[0], b[1], b[2], u, 1.0F, r, g, bl);
        glowVertex(vc, pose, c[0], c[1], c[2], u + 0.25F, 1.0F, r, g, bl);
        glowVertex(vc, pose, c[0], c[1], c[2], u + 0.25F, 1.0F, r, g, bl);
        glowVertex(vc, pose, c[0], c[1], c[2], u + 0.25F, 1.0F, r, g, bl);
        glowVertex(vc, pose, b[0], b[1], b[2], u, 1.0F, r, g, bl);
        glowVertex(vc, pose, a[0], a[1], a[2], u + 0.125F, 0.0F, r, g, bl);
        glowVertex(vc, pose, a[0], a[1], a[2], u + 0.125F, 0.0F, r, g, bl);
    }

    /** A sphere (outside faces only), additive; {@code uShift}/{@code vShift} move the texture over it. */
    private static void sphere(VertexConsumer vc, PoseStack.Pose pose, float radius, float uShift, float vShift, float r,
                               float g, float b) {
        final int seg = 20;
        final int stacks = 12;
        int ir = Mth.clamp((int) (r * 255.0F), 0, 255);
        int ig = Mth.clamp((int) (g * 255.0F), 0, 255);
        int ib = Mth.clamp((int) (b * 255.0F), 0, 255);
        for (int i = 0; i < stacks; i++) {
            float phi0 = (float) Math.PI * i / stacks;
            float phi1 = (float) Math.PI * (i + 1) / stacks;
            for (int j = 0; j < seg; j++) {
                float th0 = (float) (2.0 * Math.PI * j / seg);
                float th1 = (float) (2.0 * Math.PI * (j + 1) / seg);
                float[][] pts = {
                        point(radius, phi0, th0), point(radius, phi1, th0), point(radius, phi1, th1), point(radius, phi0, th1)
                };
                float[][] uvs = {
                        {uShift + (float) j / seg, vShift + (float) i / stacks}, {uShift + (float) j / seg, vShift + (float) (i + 1) / stacks},
                        {uShift + (float) (j + 1) / seg, vShift + (float) (i + 1) / stacks}, {uShift + (float) (j + 1) / seg, vShift + (float) i / stacks}
                };
                // Outward direction = from the centre through the middle of the quad.
                float mx = (pts[0][0] + pts[2][0]) * 0.5F;
                float my = (pts[0][1] + pts[2][1]) * 0.5F;
                float mz = (pts[0][2] + pts[2][2]) * 0.5F;
                float[] n = normal(pts[0], pts[1], pts[2]);
                boolean flip = n[0] * mx + n[1] * my + n[2] * mz < 0.0F;
                for (int v = 0; v < 4; v++) {
                    int idx = flip ? 3 - v : v;
                    glowVertex(vc, pose, pts[idx][0], pts[idx][1], pts[idx][2], uvs[idx][0], uvs[idx][1], ir, ig, ib);
                }
            }
        }
    }

    private static float[] point(float radius, float phi, float theta) {
        float sp = Mth.sin(phi);
        return new float[]{radius * sp * Mth.cos(theta), radius * Mth.cos(phi), radius * sp * Mth.sin(theta)};
    }

    // ------------------------------------------------------------------
    // Polygon helpers
    // ------------------------------------------------------------------

    private static float[] normal(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0];
        float uy = b[1] - a[1];
        float uz = b[2] - a[2];
        float vx = c[0] - a[0];
        float vy = c[1] - a[1];
        float vz = c[2] - a[2];
        return new float[]{uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
    }

    /**
     * A flat polygon of three or four points with its own normal, wound so that it faces {@code (hx, hy, hz)}.
     * {@code uvMode}: 0 = texture from (x, z), 1 = from (x, 1 - y), 2 = from (z, 1 - y), 3 = the middle of the sprite.
     */
    private static void poly(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite sprite, int uvMode,
                             float hx, float hy, float hz, float[]... pts) {
        float[] n = normal(pts[0], pts[1], pts[2]);
        float len = Mth.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (len < 1.0E-8F) {
            return;
        }
        float nx = n[0] / len;
        float ny = n[1] / len;
        float nz = n[2] / len;
        boolean flip = nx * hx + ny * hy + nz * hz < 0.0F;
        if (flip) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        int count = pts.length;
        for (int v = 0; v < 4; v++) {
            int i = Math.min(v, count - 1); // a triangle repeats its last point
            int idx = flip ? count - 1 - i : i;
            float[] p = pts[idx];
            float fu;
            float fv;
            if (uvMode == 3) {
                fu = 0.5F;
                fv = 0.5F;
            } else {
                fu = uvMode == 2 ? p[2] : p[0];
                fv = uvMode == 0 ? p[2] : 1.0F - p[1];
            }
            float u = sprite.getU0() + (sprite.getU1() - sprite.getU0()) * fu;
            float vv = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * fv;
            vc.addVertex(pose, p[0], p[1], p[2]).setColor(255, 255, 255, 255).setUv(u, vv)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
        }
    }

    /** A ribbon (two crossed quads) from a to b, drawn from both sides. */
    private static void laser(VertexConsumer vc, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by,
                              float bz, float width, float r, float g, float b) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4F) {
            return;
        }
        float tx = dx / len;
        float ty = dy / len;
        float tz = dz / len;
        float ux = -tz;
        float uy = 0.0F;
        float uz = tx;
        float ul = Mth.sqrt(ux * ux + uy * uy + uz * uz);
        if (ul < 1.0E-3F) {
            ux = 1.0F;
            uy = 0.0F;
            uz = 0.0F;
            ul = 1.0F;
        }
        ux = ux / ul * width;
        uy = uy / ul * width;
        uz = uz / ul * width;
        float vx = ty * uz - tz * uy;
        float vy = tz * ux - tx * uz;
        float vz = tx * uy - ty * ux;
        float mx = (ax + bx) * 0.5F;
        float my = (ay + by) * 0.5F;
        float mz = (az + bz) * 0.5F;
        plane(vc, pose, mx, my, mz, dx * 0.5F, dy * 0.5F, dz * 0.5F, ux, uy, uz, r, g, b);
        plane(vc, pose, mx, my, mz, dx * 0.5F, dy * 0.5F, dz * 0.5F, vx, vy, vz, r, g, b);
    }

    /**
     * A parallelogram around (cx, cy, cz) spanned by the half vectors a and b, drawn from both sides (the additive
     * type culls back faces). u runs along b, v along a.
     */
    private static void plane(VertexConsumer vc, PoseStack.Pose pose, float cx, float cy, float cz, float ax, float ay,
                              float az, float bx, float by, float bz, float r, float g, float b) {
        int ir = Mth.clamp((int) (r * 255.0F), 0, 255);
        int ig = Mth.clamp((int) (g * 255.0F), 0, 255);
        int ib = Mth.clamp((int) (b * 255.0F), 0, 255);
        glowVertex(vc, pose, cx - ax - bx, cy - ay - by, cz - az - bz, 0, 0, ir, ig, ib);
        glowVertex(vc, pose, cx + ax - bx, cy + ay - by, cz + az - bz, 0, 1, ir, ig, ib);
        glowVertex(vc, pose, cx + ax + bx, cy + ay + by, cz + az + bz, 1, 1, ir, ig, ib);
        glowVertex(vc, pose, cx - ax + bx, cy - ay + by, cz - az + bz, 1, 0, ir, ig, ib);
        glowVertex(vc, pose, cx - ax + bx, cy - ay + by, cz - az + bz, 1, 0, ir, ig, ib);
        glowVertex(vc, pose, cx + ax + bx, cy + ay + by, cz + az + bz, 1, 1, ir, ig, ib);
        glowVertex(vc, pose, cx + ax - bx, cy + ay - by, cz + az - bz, 0, 1, ir, ig, ib);
        glowVertex(vc, pose, cx - ax - bx, cy - ay - by, cz - az - bz, 0, 0, ir, ig, ib);
    }

    private static void glowVertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                                   int r, int g, int b) {
        vc.addVertex(pose, x, y, z).setColor(r, g, b, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
