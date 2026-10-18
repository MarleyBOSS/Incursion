package com.marleyboss.incursion.client;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlock;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.registry.ModItems;
import com.marleyboss.incursion.registry.ModSounds;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.Map;
import java.util.WeakHashMap;
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
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the Core block: the Sarcophagus and, once the ritual is done, the burning Core.
 *
 * <p><b>Shape</b> (a cube of edge 1, exact geometry of docs/sarkophag_referenz.png): the lower half is solid up to the
 * surface {@code y = 0.5 + min(|x - 0.5|, |z - 0.5|)}, so every side has a V-notch down to half height and the four
 * corners rise to a point. The upper half is the piece that completes the cube: a flat top plate with four triangular
 * glass panes hanging in the notches (it is hollow, drawn as a thin skin). Shut, the two halves form the cube and the
 * ritual items lie in the cavity behind the glass: the Core Rune floats in the middle, gunpowder fills the north-south
 * valley of the floor, blaze powder the east-west valley (the eight combinations are the stages of the block).</p>
 *
 * <p><b>Active</b>: the upper half rises out of the lower one and floats, turned by 35 degrees against it; the Core, a
 * golden fire orb with a halo and thin golden rays, burns in the gap. The activation (the block state turns
 * {@code active}) is animated: the glow builds up, the upper half lifts and twists, the orb ignites.</p>
 *
 * <p>All geometry is built from triangles here because a block model can only have axis-aligned boxes. Every face is
 * oriented automatically against a given outward direction, so the winding order cannot be wrong.</p>
 */
final class SarcophagusRenderer {
    private static final String NS = Incursion.MODID;
    private static final ResourceLocation MARBLE = block("sarkophag_marble");
    private static final ResourceLocation INNER = block("sarkophag_inner");
    private static final ResourceLocation GUNPOWDER = block("sarkophag_gunpowder");
    private static final ResourceLocation BLAZE = block("sarkophag_blaze");
    private static final ResourceLocation GLASS = ResourceLocation.fromNamespaceAndPath(NS, "textures/block/sarkophag_glass.png");
    private static final ResourceLocation TRIM = effect("trim");
    private static final ResourceLocation ORB = effect("orb");
    private static final ResourceLocation HALO = effect("halo");

    /** Time the activation takes (ticks). */
    private static final float OPEN_TICKS = 70.0F;
    /** How far the upper half rises. */
    private static final float LIFT = 0.5F;
    /** How far the upper half is turned against the lower one (degrees). */
    private static final float TWIST = 35.0F;
    private static final float ORB_Y = 0.95F;
    private static final float ORB_RADIUS = 0.15F;

    // Corner points of the cavity floor (see the class comment).
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

    /** Gold edge lines, as {x1, y1, z1, x2, y2, z2}. */
    private static final float[][] LOWER_EDGES = {
            {0, 0, 0, 1, 0, 0}, {1, 0, 0, 1, 0, 1}, {1, 0, 1, 0, 0, 1}, {0, 0, 1, 0, 0, 0},
            {0, 0, 0, 0, 1, 0}, {1, 0, 0, 1, 1, 0}, {1, 0, 1, 1, 1, 1}, {0, 0, 1, 0, 1, 1},
            {0, 1, 0, 0.5F, 0.5F, 0}, {0.5F, 0.5F, 0, 1, 1, 0}, {0, 1, 1, 0.5F, 0.5F, 1}, {0.5F, 0.5F, 1, 1, 1, 1},
            {0, 1, 0, 0, 0.5F, 0.5F}, {0, 0.5F, 0.5F, 0, 1, 1}, {1, 1, 0, 1, 0.5F, 0.5F}, {1, 0.5F, 0.5F, 1, 1, 1},
            {0.5F, 0.5F, 0, 0.5F, 0.5F, 0.5F}, {0.5F, 0.5F, 1, 0.5F, 0.5F, 0.5F},
            {0, 0.5F, 0.5F, 0.5F, 0.5F, 0.5F}, {1, 0.5F, 0.5F, 0.5F, 0.5F, 0.5F},
            {0, 1, 0, 0.5F, 0.5F, 0.5F}, {1, 1, 0, 0.5F, 0.5F, 0.5F}, {1, 1, 1, 0.5F, 0.5F, 0.5F}, {0, 1, 1, 0.5F, 0.5F, 0.5F}
    };
    private static final float[][] UPPER_EDGES = {
            {0, 1, 0, 1, 1, 0}, {1, 1, 0, 1, 1, 1}, {1, 1, 1, 0, 1, 1}, {0, 1, 1, 0, 1, 0},
            {0, 1, 0, 0.5F, 0.5F, 0}, {0.5F, 0.5F, 0, 1, 1, 0}, {0, 1, 1, 0.5F, 0.5F, 1}, {0.5F, 0.5F, 1, 1, 1, 1},
            {0, 1, 0, 0, 0.5F, 0.5F}, {0, 0.5F, 0.5F, 0, 1, 1}, {1, 1, 0, 1, 0.5F, 0.5F}, {1, 0.5F, 0.5F, 1, 1, 1}
    };

    private static final ItemStack RUNE_STACK = new ItemStack(ModItems.CORE_RUNE.get());
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

        // Light: from above the block (its own position is solid), at least a little bright.
        int above = LevelRenderer.getLightColor(level, core.getBlockPos().above());
        int light = LightTexture.pack(Math.max(LightTexture.block(above), 6), LightTexture.sky(above));

        float lift = 0.0F;
        float twist = 0.0F;
        float shake = 0.0F;
        float orb = 0.0F;
        float glow = ready ? 0.55F + 0.2F * Mth.sin(time * 0.12F) : 0.5F;
        if (active) {
            float rise = ease((t - 0.2F) / 0.6F);
            lift = rise * LIFT;
            twist = rise * TWIST;
            shake = t < 0.3F ? Mth.sin(time * 2.9F) * 0.012F * (t / 0.3F) : 0.0F;
            orb = ease((t - 0.5F) / 0.5F);
            glow = 0.55F + 0.45F * ease(t / 0.5F);
            if (t >= 1.0F) {
                lift += 0.03F * Mth.sin(time * 0.06F);
                twist += 3.0F * Mth.sin(time * 0.045F);
            }
            if (t < 0.85F && time >= state.nextSpark) {
                state.nextSpark = time + 1.0F;
                double ang = level.random.nextDouble() * Math.PI * 2.0;
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, px + Math.cos(ang) * 0.5, py + 0.4 + level.random.nextDouble() * 0.7,
                        pz + Math.sin(ang) * 0.5, 0.0, 0.02, 0.0);
            }
        }

        drawLower(pose, buffer, light);
        // The ritual items are used up in the activation: they sink into the Core while it ignites.
        float contents = active ? 1.0F - ease(t / 0.45F) : 1.0F;
        if (contents > 0.0F) {
            drawContents(pose, buffer, light, hasRune, hasGunpowder, hasBlaze, time, contents, level);
        }
        pose.pushPose();
        pose.translate(0.5F + shake, lift, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(twist));
        pose.translate(-0.5F, 0.0F, -0.5F);
        drawUpper(pose, buffer, light, glow);
        pose.popPose();
        drawTrim(pose, buffer, LOWER_EDGES, glow);
        if (orb > 0.0F) {
            float flash = t < 1.0F ? Mth.sin(Mth.clamp((t - 0.5F) / 0.2F, 0.0F, 1.0F) * (float) Math.PI) : 0.0F;
            drawOrb(pose, buffer, time, orb, flash);
            drawRays(pose, buffer, time, orb, lift, twist);
        }
    }

    /** The block as an item: shut, empty. */
    static void renderItem(PoseStack pose, MultiBufferSource buffer, int light) {
        drawLower(pose, buffer, light);
        drawUpper(pose, buffer, light, 0.5F);
        drawTrim(pose, buffer, LOWER_EDGES, 0.5F);
    }

    // ------------------------------------------------------------------
    // Lower half
    // ------------------------------------------------------------------

    private static void drawLower(PoseStack pose, MultiBufferSource buffer, int light) {
        VertexConsumer vc = buffer.getBuffer(Sheets.solidBlockSheet());
        PoseStack.Pose p = pose.last();
        TextureAtlasSprite marble = sprite(MARBLE);
        // Bottom.
        poly(vc, p, light, marble, 0, 0, -1, 0,
                new float[]{0, 0, 0}, new float[]{1, 0, 0}, new float[]{1, 0, 1}, new float[]{0, 0, 1});
        // The four walls: a rectangle up to half height plus the two triangles beside the notch.
        wall(vc, p, light, marble, 0.0F, true, 0, 0, -1);
        wall(vc, p, light, marble, 1.0F, true, 0, 0, 1);
        wall(vc, p, light, marble, 0.0F, false, -1, 0, 0);
        wall(vc, p, light, marble, 1.0F, false, 1, 0, 0);
        // The top surface: eight triangles, facing up.
        for (float[][] tri : FLOOR) {
            poly(vc, p, light, marble, 0, 0, 1, 0, tri[0], tri[1], tri[2]);
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

    // ------------------------------------------------------------------
    // Upper half (drawn in the current pose, which carries its lift and turn)
    // ------------------------------------------------------------------

    private static void drawUpper(PoseStack pose, MultiBufferSource buffer, int light, float glow) {
        PoseStack.Pose p = pose.last();
        VertexConsumer solid = buffer.getBuffer(Sheets.solidBlockSheet());
        TextureAtlasSprite marble = sprite(MARBLE);
        TextureAtlasSprite inner = sprite(INNER);
        // Top plate: marble outside, dark stone underneath.
        poly(solid, p, light, marble, 0, 0, 1, 0,
                new float[]{0, 1, 0}, new float[]{1, 1, 0}, new float[]{1, 1, 1}, new float[]{0, 1, 1});
        poly(solid, p, light, inner, 0, 0, -1, 0,
                new float[]{0, 1, 0}, new float[]{1, 1, 0}, new float[]{1, 1, 1}, new float[]{0, 1, 1});
        // Glass: four triangles hanging in the notches (translucent, seen from both sides).
        VertexConsumer glass = buffer.getBuffer(RenderType.entityTranslucent(GLASS));
        int glassLight = LightTexture.pack(Math.max(LightTexture.block(light), 8), LightTexture.sky(light));
        glassTri(glass, p, glassLight, new float[]{0, 1, 0}, new float[]{1, 1, 0}, N, 0, 0, -1);
        glassTri(glass, p, glassLight, new float[]{0, 1, 1}, new float[]{1, 1, 1}, S, 0, 0, 1);
        glassTri(glass, p, glassLight, new float[]{0, 1, 0}, new float[]{0, 1, 1}, W, -1, 0, 0);
        glassTri(glass, p, glassLight, new float[]{1, 1, 0}, new float[]{1, 1, 1}, E, 1, 0, 0);
        drawTrim(pose, buffer, UPPER_EDGES, glow);
    }

    private static void glassTri(VertexConsumer vc, PoseStack.Pose pose, int light, float[] a, float[] b, float[] c,
                                 float nx, float ny, float nz) {
        // Outward-facing winding is not needed (the type does not cull), the normal only steers the shading.
        glassVertex(vc, pose, light, a, 0.0F, 0.0F, nx, ny, nz);
        glassVertex(vc, pose, light, b, 1.0F, 0.0F, nx, ny, nz);
        glassVertex(vc, pose, light, c, 0.5F, 1.0F, nx, ny, nz);
        glassVertex(vc, pose, light, c, 0.5F, 1.0F, nx, ny, nz);
    }

    private static void glassVertex(VertexConsumer vc, PoseStack.Pose pose, int light, float[] p, float u, float v,
                                    float nx, float ny, float nz) {
        vc.addVertex(pose, p[0], p[1], p[2]).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }

    // ------------------------------------------------------------------
    // The ritual items inside
    // ------------------------------------------------------------------

    private static void drawContents(PoseStack pose, MultiBufferSource buffer, int light, boolean rune, boolean gunpowder,
                                     boolean blaze, float time, float scale, Level level) {
        if (gunpowder || blaze) {
            VertexConsumer vc = buffer.getBuffer(Sheets.solidBlockSheet());
            PoseStack.Pose p = pose.last();
            if (gunpowder) {
                // North-south valley, filled up to y = 0.62 (the width where the sloping floor reaches that height).
                poly(vc, p, light, sprite(GUNPOWDER), 0, 0, 1, 0,
                        new float[]{0.38F, 0.62F, 0.04F}, new float[]{0.62F, 0.62F, 0.04F},
                        new float[]{0.62F, 0.62F, 0.96F}, new float[]{0.38F, 0.62F, 0.96F});
            }
            if (blaze) {
                int bright = LightTexture.pack(Math.max(LightTexture.block(light), 11), LightTexture.sky(light));
                poly(vc, p, bright, sprite(BLAZE), 0, 0, 1, 0,
                        new float[]{0.04F, 0.625F, 0.38F}, new float[]{0.96F, 0.625F, 0.38F},
                        new float[]{0.96F, 0.625F, 0.62F}, new float[]{0.04F, 0.625F, 0.62F});
            }
        }
        if (rune) {
            pose.pushPose();
            pose.translate(0.5F, 0.8F + 0.03F * Mth.sin(time * 0.08F), 0.5F);
            pose.mulPose(Axis.YP.rotationDegrees(time * 2.2F));
            float size = 0.5F * scale;
            pose.scale(size, size, size);
            int bright = LightTexture.pack(Math.max(LightTexture.block(light), 12), LightTexture.sky(light));
            Minecraft.getInstance().getItemRenderer().renderStatic(RUNE_STACK, ItemDisplayContext.FIXED, bright,
                    OverlayTexture.NO_OVERLAY, pose, buffer, level, 0);
            pose.popPose();
        }
    }

    // ------------------------------------------------------------------
    // Gold edge lines, the orb, rays (additive: black = nothing, the intensity goes into the colour)
    // ------------------------------------------------------------------

    private static void drawTrim(PoseStack pose, MultiBufferSource buffer, float[][] edges, float k) {
        VertexConsumer vc = buffer.getBuffer(RenderType.eyes(TRIM));
        PoseStack.Pose p = pose.last();
        for (float[] e : edges) {
            laser(vc, p, e[0], e[1], e[2], e[3], e[4], e[5], 0.007F, 1.0F * k, 0.72F * k, 0.18F * k);
        }
    }

    private static void drawOrb(PoseStack pose, MultiBufferSource buffer, float time, float scale, float flash) {
        float pulse = 0.85F + 0.15F * Mth.sin(time * 0.17F);
        float cy = ORB_Y + 0.02F * Mth.sin(time * 0.08F);
        // Outer flames: a big sphere with the fire texture, turning slowly.
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 1.6F));
        pose.mulPose(Axis.XP.rotationDegrees(12.0F * Mth.sin(time * 0.05F)));
        sphere(buffer.getBuffer(RenderType.eyes(ORB)), pose.last(), ORB_RADIUS * scale * (1.0F + 0.06F * Mth.sin(time * 0.3F)),
                0.0F, 1.0F * pulse, 1.0F * pulse, 1.0F * pulse);
        pose.popPose();
        // Inner heart: smaller, brighter, turning the other way.
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-time * 3.1F));
        sphere(buffer.getBuffer(RenderType.eyes(ORB)), pose.last(), ORB_RADIUS * 0.62F * scale, 0.37F,
                1.3F * pulse, 1.2F * pulse, 1.0F * pulse);
        pose.popPose();
        // Halo: three crossed planes, orange, brighter during the flash of the ignition.
        float h = (0.62F + 0.5F * flash) * scale;
        float k = (0.55F * pulse + 0.9F * flash) * scale;
        VertexConsumer glow = buffer.getBuffer(RenderType.eyes(HALO));
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 0.7F));
        PoseStack.Pose p = pose.last();
        plane(glow, p, 0, 0, 0, h, 0, 0, 0, 0, h, 1.0F * k, 0.55F * k, 0.12F * k);
        plane(glow, p, 0, 0, 0, h, 0, 0, 0, h, 0, 1.0F * k, 0.55F * k, 0.12F * k);
        plane(glow, p, 0, 0, 0, 0, 0, h, 0, h, 0, 1.0F * k, 0.55F * k, 0.12F * k);
        pose.popPose();
    }

    /** Thin golden rays from the orb to the corners of the lower half, up to the plate and to the tips of the glass. */
    private static void drawRays(PoseStack pose, MultiBufferSource buffer, float time, float scale, float lift, float twist) {
        VertexConsumer vc = buffer.getBuffer(RenderType.eyes(TRIM));
        PoseStack.Pose p = pose.last();
        float flicker = 0.75F + 0.25F * Mth.sin(time * 0.9F);
        float k = 0.9F * scale * flicker;
        float cy = ORB_Y;
        float[][] corners = {NW, NE, SE, SW};
        for (float[] corner : corners) {
            laser(vc, p, 0.5F, cy, 0.5F, corner[0], corner[1], corner[2], 0.006F, 1.0F * k, 0.7F * k, 0.15F * k);
        }
        // The upper half moves: its end points are turned and lifted the same way as the half itself.
        double rad = Math.toRadians(twist);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        laser(vc, p, 0.5F, cy, 0.5F, 0.5F, 1.0F + lift, 0.5F, 0.006F, 1.0F * k, 0.7F * k, 0.15F * k);
        float[][] tips = {N, S, W, E};
        for (float[] tip : tips) {
            float dx = tip[0] - 0.5F;
            float dz = tip[2] - 0.5F;
            float tx = 0.5F + dx * cos + dz * sin;
            float tz = 0.5F - dx * sin + dz * cos;
            laser(vc, p, 0.5F, cy, 0.5F, tx, tip[1] + lift, tz, 0.006F, 0.9F * k, 0.6F * k, 0.12F * k);
        }
    }

    /** A sphere (outside faces only), additive; {@code uShift} turns the texture around it. */
    private static void sphere(VertexConsumer vc, PoseStack.Pose pose, float radius, float uShift, float r, float g, float b) {
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
                        {uShift + (float) j / seg, (float) i / stacks}, {uShift + (float) j / seg, (float) (i + 1) / stacks},
                        {uShift + (float) (j + 1) / seg, (float) (i + 1) / stacks}, {uShift + (float) (j + 1) / seg, (float) i / stacks}
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
     * {@code uvMode}: 0 = texture from (x, z), 1 = from (x, 1 - y), 2 = from (z, 1 - y), in texture fractions.
     */
    private static void poly(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite sprite, int uvMode,
                             float hx, float hy, float hz, float[]... pts) {
        float[] n = normal(pts[0], pts[1], pts[2]);
        float len = Mth.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (len < 1.0E-6F) {
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
            float fu = uvMode == 2 ? p[2] : p[0];
            float fv = uvMode == 0 ? p[2] : 1.0F - p[1];
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
        // First perpendicular: tangent x up (or x when the line is vertical), second: tangent x first.
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
