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
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.sounds.SoundEvents;
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
    private static final ResourceLocation RUNE_ITEM = ResourceLocation.fromNamespaceAndPath(NS, "item/core_rune");
    private static final ResourceLocation RING = effect("rune_circle");

    /** Time the activation takes (ticks). */
    private static final float OPEN_TICKS = 80.0F;
    /** Time the closing after a defuse takes (ticks). */
    private static final float CLOSE_TICKS = 60.0F;
    /** Half width of the gold frame of the lower half and of the gold outline of the slab. */
    private static final float GOLD_HALF = 0.022F;
    private static final float OUTLINE_HALF = 0.013F;
    /** How far the frame stands out of the surface (so that it wins against the wall behind it). */
    private static final float OUT = 0.004F;
    /** How deep the frame reaches into the block. */
    private static final float DEPTH = 0.04F;
    /** How far the upper half rises. */
    private static final float LIFT = 0.8F;
    /** How far the upper half is turned against the lower one (degrees). */
    private static final float TWIST = 35.0F;
    /** Thickness of the upper slab and the gap to the wall plane. */
    private static final float SLAB = 0.05F;
    /**
     * The slab reaches a hair past the block (negative inset): no slot between it and the glass, and it ends flush with the
     * gold frame of the lower half, whose outer face lies OUT outside the block. The gold on the slab lies in that same plane.
     */
    private static final float INSET = -0.0025F;
    /** Height of the orb's centre, relative to the block. */
    private static final float ORB_Y = 1.12F;
    /** Height of the four corner tips of the lower half: exactly where the slab begins, so the frame lines meet there. */
    private static final float TIP = 1.0F - SLAB;
    /** Rise of the lower half's surface per block from the notch to the corner. */
    private static final float FLOOR_SLOPE = (TIP - 0.5F) / 0.5F;
    /** Width, along the wall, of the outer gold line of the glass frame where it meets the corner (the line is 0.010 thick across). */
    /** Width of the corner posts: the horizontal width of the slanted gold bars at their tip, so the posts are flush with them. */
    private static final float POST_W = GOLD_HALF * (float) Math.sqrt(1.0F + (0.5F / (TIP - 0.5F)) * (0.5F / (TIP - 0.5F)));
    private static final float CORNER_W = 0.010F * (float) Math.sqrt(1.0F + (0.5F / (TIP - 0.5F)) * (0.5F / (TIP - 0.5F)));
    private static final float ORB_RADIUS = 0.15F;

    private static final float[] C = {0.5F, 0.5F, 0.5F};
    private static final float[] N = {0.5F, 0.5F, 0.0F};
    private static final float[] S = {0.5F, 0.5F, 1.0F};
    private static final float[] W = {0.0F, 0.5F, 0.5F};
    private static final float[] E = {1.0F, 0.5F, 0.5F};
    private static final float[] NW = {0.0F, TIP, 0.0F};
    private static final float[] NE = {1.0F, TIP, 0.0F};
    private static final float[] SW = {0.0F, TIP, 1.0F};
    private static final float[] SE = {1.0F, TIP, 1.0F};

    /** The eight triangles of the lower half's top surface: (corner, wall middle, centre). */
    private static final float[][][] FLOOR = {
            {NW, N, C}, {NW, W, C}, {NE, N, C}, {NE, E, C}, {SW, S, C}, {SW, W, C}, {SE, S, C}, {SE, E, C}
    };

    /** Gold bars of the lower half, as {x1, y1, z1, x2, y2, z2}: bottom, vertical edges, the V notches. */
    private static final float[][] LOWER_BARS = {
            {0, 0, 0, 1, 0, 0}, {1, 0, 0, 1, 0, 1}, {1, 0, 1, 0, 0, 1}, {0, 0, 1, 0, 0, 0},
            {0, 0, 0, 0, TIP, 0}, {1, 0, 0, 1, TIP, 0}, {1, 0, 1, 1, TIP, 1}, {0, 0, 1, 0, TIP, 1},
            {0, TIP, 0, 0.5F, 0.5F, 0}, {0.5F, 0.5F, 0, 1, TIP, 0}, {0, TIP, 1, 0.5F, 0.5F, 1}, {0.5F, 0.5F, 1, 1, TIP, 1},
            {0, TIP, 0, 0, 0.5F, 0.5F}, {0, 0.5F, 0.5F, 0, TIP, 1}, {1, TIP, 0, 1, 0.5F, 0.5F}, {1, 0.5F, 0.5F, 1, TIP, 1}
    };
    /** The slab's top edges (the glimmer uses only these). */
    private static final float[][] UPPER_BARS = {
            {0, 1, 0, 1, 1, 0}, {1, 1, 0, 1, 1, 1}, {1, 1, 1, 0, 1, 1}, {0, 1, 1, 0, 1, 0}
    };

    private static final DustParticleOptions SPARK = new DustParticleOptions(new Vector3f(1.0F, 0.8F, 0.25F), 0.7F);
    private static final Map<CoreBlockEntity, State> STATES = new WeakHashMap<>();

    private static final class State {
        boolean seen;
        boolean wasActive;
        float open;
        boolean rising;
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
        float progress = core.getClientDefuseProgress(level.getGameTime());
        // The Sarcophagus is open (1) while active; during a defuse countdown it closes slowly so that it is shut when the
        // countdown ends; inactive it is shut (0).
        float target = active ? (progress >= 0.0F ? progress : 1.0F) : 0.0F;
        if (!state.seen) {
            state.seen = true;
            state.open = target; // a Core that was already active when it came into view stays open
        } else if (active && !state.wasActive) {
            state.open = 0.0F;
            level.playLocalSound(px, py, pz, ModSounds.CORE_OPEN.get(), SoundSource.BLOCKS, 1.6F, 1.0F, false);
        } else if (!active && state.wasActive) {
            level.playLocalSound(px, py, pz, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.2F, 0.8F, false);
        }
        state.wasActive = active;
        float before = state.open;
        float step = dt / OPEN_TICKS;
        if (target >= state.open) {
            state.open = Math.min(target, state.open + step);
        } else {
            state.open = Math.max(target, state.open - (active ? step * 1.5F : dt / CLOSE_TICKS));
        }
        state.rising = state.open > before;
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
        if (t > 0.0F) {
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
            if (active && t < 0.85F && time >= state.nextSpark) {
                state.nextSpark = time + 1.0F; // a few small gold sparks while the Core ignites, none afterwards
                double ang = level.random.nextDouble() * Math.PI * 2.0;
                level.addParticle(SPARK, px + Math.cos(ang) * 0.5, py + 0.4 + level.random.nextDouble() * 0.7,
                        pz + Math.sin(ang) * 0.5, 0.0, 0.02, 0.0);
            }
        }
        // The ritual items are consumed while the Core ignites and come back (the rune) when it is defused.
        float contents = t > 0.0F ? 1.0F - ease(t / 0.45F) : 1.0F;
        // The powders are destroyed by a defuse: while the countdown runs (and the block closes) only the rune comes back.
        boolean showHeaps = !(active && progress >= 0.0F);

        // 1. Solid parts.
        VertexConsumer solid = buffer.getBuffer(Sheets.solidBlockSheet());
        drawLower(pose.last(), solid, light);
        drawLowerFrame(pose.last(), solid, light);
        if (contents > 0.0F) {
            if (showHeaps) {
                drawHeaps(pose.last(), solid, light, hasGunpowder, hasBlaze);
            }
            if (hasRune) {
                pose.pushPose();
                pose.translate(0.5F, 0.77F + 0.025F * Mth.sin(time * 0.08F), 0.5F);
                pose.mulPose(Axis.YP.rotationDegrees(time * 2.4F));
                pose.scale(contents, contents, contents);
                drawTablet(pose.last(), solid);
                pose.popPose();
            }
        }
        pose.pushPose();
        pose.translate(0.5F + shake, lift, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(twist));
        pose.translate(-0.5F, 0.0F, -0.5F);
        drawSlab(pose.last(), solid, light);
        drawUpperFrame(pose.last(), solid, light);
        pose.popPose();
        flushSolid(buffer);

        // 2. Additive glow: ritual items, the orb, rays.
        if (contents > 0.0F) {
            drawItemGlow(pose, buffer, time, contents, hasRune, hasBlaze && showHeaps, ready);
        }
        if (active && state.rising && t < 0.18F) {
            drawBolt(pose, buffer, time, 1.0F - t / 0.18F);
        }
        if (orb > 0.0F) {
            drawInnerGlow(pose, buffer, time, orb, lift, twist, shake);
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
        if (t <= 0.0F) {
            // A shut Sarcophagus glimmers along its gold when the ritual is ready.
            drawGlimmer(pose, buffer, ready ? glow : 0.0F);
        }
    }

    /** The block as an item: shut, empty. */
    static void renderItem(PoseStack pose, MultiBufferSource buffer, int light) {
        VertexConsumer solid = buffer.getBuffer(Sheets.solidBlockSheet());
        drawLower(pose.last(), solid, light);
        drawLowerFrame(pose.last(), solid, light);
        drawSlab(pose.last(), solid, light);
        drawUpperFrame(pose.last(), solid, light);
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
        // The same walls seen from inside (dark): otherwise one can look through them from the open top.
        TextureAtlasSprite dark = sprite(INNER);
        wall(vc, pose, light, dark, 0.0F, true, 0, 0, 1);
        wall(vc, pose, light, dark, 1.0F, true, 0, 0, -1);
        wall(vc, pose, light, dark, 0.0F, false, 1, 0, 0);
        wall(vc, pose, light, dark, 1.0F, false, -1, 0, 0);
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
        poly(vc, pose, light, sprite, mode, nx, ny, nz, wp(0, 0.5F, at, alongX), wp(0.5F, 0.5F, at, alongX), wp(0, TIP, at, alongX));
        poly(vc, pose, light, sprite, mode, nx, ny, nz, wp(0.5F, 0.5F, at, alongX), wp(1, 0.5F, at, alongX), wp(1, TIP, at, alongX));
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
        // The sides seen from inside (dark), so that one cannot look through the slab from below.
        poly(vc, pose, light, inner, 1, 0, 0, 1,
                new float[]{a, y0, a}, new float[]{b, y0, a}, new float[]{b, 1, a}, new float[]{a, 1, a});
        poly(vc, pose, light, inner, 1, 0, 0, -1,
                new float[]{a, y0, b}, new float[]{b, y0, b}, new float[]{b, 1, b}, new float[]{a, 1, b});
        poly(vc, pose, light, inner, 2, 1, 0, 0,
                new float[]{a, y0, a}, new float[]{a, y0, b}, new float[]{a, 1, b}, new float[]{a, 1, a});
        poly(vc, pose, light, inner, 2, -1, 0, 0,
                new float[]{b, y0, a}, new float[]{b, y0, b}, new float[]{b, 1, b}, new float[]{b, 1, a});
    }

    /** The frame of the lower half: gold, clean. Bottom edges, corner posts and the V notches. */
    private static void drawLowerFrame(PoseStack.Pose pose, VertexConsumer vc, int light) {
        TextureAtlasSprite gold = sprite(GOLD);
        int bright = LightTexture.pack(Math.max(LightTexture.block(light), 9), LightTexture.sky(light));
        float w = GOLD_HALF * 2.0F;
        float lo = -OUT;
        float hi = 1.0F + OUT;
        // bottom edges
        box(vc, pose, bright, gold, 4, lo, hi, lo, lo + w, lo, lo + w);
        box(vc, pose, bright, gold, 4, lo, hi, lo, lo + w, hi - w, hi);
        box(vc, pose, bright, gold, 4, lo, lo + w, lo, lo + w, lo, hi);
        box(vc, pose, bright, gold, 4, hi - w, hi, lo, lo + w, lo, hi);
        // corner posts, up to the tips
        float pw = POST_W;
        box(vc, pose, bright, gold, 4, lo, pw, lo, TIP, lo, pw);
        box(vc, pose, bright, gold, 4, hi - pw, hi, lo, TIP, lo, pw);
        box(vc, pose, bright, gold, 4, lo, pw, lo, TIP, hi - pw, hi);
        box(vc, pose, bright, gold, 4, hi - pw, hi, lo, TIP, hi - pw, hi);
        notches(vc, pose, bright, gold, 4, GOLD_HALF, OUT);
    }

    /** The frame of the upper half: a thin gold outline round the slab, an obsidian frame round the glass. */
    private static void drawUpperFrame(PoseStack.Pose pose, VertexConsumer vc, int light) {
        TextureAtlasSprite gold = sprite(GOLD);
        TextureAtlasSprite dark = sprite(INNER);
        int bright = LightTexture.pack(Math.max(LightTexture.block(light), 9), LightTexture.sky(light));
        // The slab's top edge: one thin gold line (the black side of the slab below it is the obsidian of the frame). It and
        // the posts end in the plane of the lower frame and stand out of the slab by a hair, so that they win against its side faces.
        float lo = -OUT;
        float hi = 1.0F + OUT;
        float top = 1.0F + OUT;
        float low = 1.0F - 0.012F;
        float w = CORNER_W; // as wide as the tip of the frame lines below, so that the posts are flush with them
        box(vc, pose, bright, gold, 4, lo, hi, low, top, lo, w);
        box(vc, pose, bright, gold, 4, lo, hi, low, top, 1.0F - w, hi);
        box(vc, pose, bright, gold, 4, lo, w, low, top, w, 1.0F - w);
        box(vc, pose, bright, gold, 4, 1.0F - w, hi, low, top, w, 1.0F - w);
        // A gold post on each vertical edge of the slab: it joins the tip where the frame lines of two walls meet with the
        // gold line along the top.
        float pw = POST_W;
        box(vc, pose, bright, gold, 4, lo, pw, TIP, top, lo, pw);
        box(vc, pose, bright, gold, 4, 1.0F - pw, hi, TIP, top, lo, pw);
        box(vc, pose, bright, gold, 4, lo, pw, TIP, top, 1.0F - pw, hi);
        box(vc, pose, bright, gold, 4, 1.0F - pw, hi, TIP, top, 1.0F - pw, hi);
        glassFrames(vc, pose, bright, gold, dark);
    }

    /** The two V strips on each of the four walls, centred on the edge of the notch. */
    private static void notches(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite sprite, int mode,
                                float half, float out) {
        for (int wall = 0; wall < 4; wall++) {
            boolean alongX = wall < 2;
            float at = wall % 2;
            vStrip(vc, pose, light, sprite, mode, alongX, at, 0.0F, TIP, 0.5F, 0.5F, half, 0.0F, TIP, out);
            vStrip(vc, pose, light, sprite, mode, alongX, at, 1.0F, TIP, 0.5F, 0.5F, half, 0.0F, TIP, out);
        }
    }

    /**
     * The frame round each glass triangle, as in the artwork, from the outside in: gold, obsidian, gold, glass. On the
     * two slanted sides these are three strips inside the triangle that run up to the slab. On the top edge the black
     * side of the slab is the obsidian (the gold outer line is the slab's own thin gold edge), so only one gold line
     * runs under the slab.
     */
    private static void glassFrames(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite gold,
                                    TextureAtlasSprite dark) {
        float[][] layers = {{0.0F, 0.010F, 0}, {0.010F, 0.040F, 1}, {0.040F, 0.050F, 0}};
        float top = 1.0F - SLAB;
        float out = OUT * 0.5F;
        // where the inner edge of the frame meets the top edge, and how much the edge moves per unit of height
        float slant = 0.5F / (TIP - 0.5F);
        float x0 = 0.050F * Mth.sqrt(1.0F + slant * slant);
        for (int wall = 0; wall < 4; wall++) {
            boolean alongX = wall < 2;
            float at = wall % 2;
            for (float[] layer : layers) {
                float half = (layer[1] - layer[0]) * 0.5F;
                float shift = (layer[1] + layer[0]) * 0.5F;
                TextureAtlasSprite sprite = layer[2] > 0.5F ? dark : gold;
                int mode = layer[2] > 0.5F ? 3 : 4;
                vStrip(vc, pose, light, sprite, mode, alongX, at, 0.0F, TIP, 0.5F, 0.5F, half, shift, top, out);
                vStrip(vc, pose, light, sprite, mode, alongX, at, 1.0F, TIP, 0.5F, 0.5F, half, shift, top, out);
            }
            float line = 0.010F;
            float near = at < 0.5F ? -out : 1.0F - DEPTH;
            float far = at < 0.5F ? DEPTH : 1.0F + out;
            if (alongX) {
                box(vc, pose, light, gold, 4, x0, 1.0F - x0, top - line, top, near, far);
            } else {
                box(vc, pose, light, gold, 4, near, far, top - line, top, x0, 1.0F - x0);
            }
        }
    }

    private static float dist(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        return Mth.sqrt(dx * dx + dy * dy);
    }

    /**
     * A flat strip along one side of a V notch, lying on the wall at {@code at} (alongX: the wall z = at, else x = at).
     * Its ends are cut clean: at the block's edge, at the top ({@code ymax}) and in the middle of the notch, so nothing
     * sticks out. (sa, ya) is the end at the corner, (sb, yb) the end in the middle. {@code shift} moves the strip from
     * the edge into the triangle above it.
     */
    private static void vStrip(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite sprite, int mode,
                               boolean alongX, float at, float sa, float ya, float sb, float yb, float half, float shift,
                               float ymax, float out) {
        float dx = sb - sa;
        float dy = yb - ya;
        float len = Mth.sqrt(dx * dx + dy * dy);
        dx /= len;
        dy /= len;
        boolean left = sa < sb;
        float inward = left ? 1.0F : -1.0F;
        float ox = -dy * shift * inward;
        float oy = dx * shift * inward;
        float px = -dy * half;
        float py = dx * half;
        float ext = half * 3.0F + shift;
        float ax = sa - dx * ext + ox;
        float ay = ya - dy * ext + oy;
        float bx = sb + dx * ext + ox;
        float by = yb + dy * ext + oy;
        java.util.List<float[]> poly = new java.util.ArrayList<>();
        poly.add(new float[]{ax + px, ay + py});
        poly.add(new float[]{bx + px, by + py});
        poly.add(new float[]{bx - px, by - py});
        poly.add(new float[]{ax - px, ay - py});
        poly = clip(poly, -1.0F, 0.0F, left ? out : -0.5F); // s >= -out (left half, so the strips of two walls meet in the corner) or s >= 0.5
        poly = clip(poly, 1.0F, 0.0F, left ? 0.5F : 1.0F + out);   // s <= 0.5 or s <= 1 + out
        poly = clip(poly, 0.0F, 1.0F, ymax);                 // y <= top
        if (poly.size() < 3) {
            return;
        }
        float sign = at < 0.5F ? -1.0F : 1.0F;
        float front = at + sign * out;
        float back = at - sign * DEPTH;
        float cs = 0.0F;
        float cy = 0.0F;
        for (float[] q : poly) {
            cs += q[0] / poly.size();
            cy += q[1] / poly.size();
        }
        float[] nrm = alongX ? new float[]{0, 0, sign} : new float[]{sign, 0, 0};
        // front face: a fan of triangles
        for (int i = 1; i + 1 < poly.size(); i++) {
            polyAt(vc, pose, light, sprite, mode, 0.5F, 0.5F, nrm[0], nrm[1], nrm[2],
                    wallPoint(alongX, poly.get(0), front), wallPoint(alongX, poly.get(i), front),
                    wallPoint(alongX, poly.get(i + 1), front));
        }
        // the back face (towards the inside of the block), so that the frame can be seen from inside as well
        for (int i = 1; i + 1 < poly.size(); i++) {
            polyAt(vc, pose, light, sprite, mode, 0.5F, 0.5F, -nrm[0], -nrm[1], -nrm[2],
                    wallPoint(alongX, poly.get(0), back), wallPoint(alongX, poly.get(i), back),
                    wallPoint(alongX, poly.get(i + 1), back));
        }
        // sides (not those on the block's own boundary)
        for (int i = 0; i < poly.size(); i++) {
            float[] a = poly.get(i);
            float[] b = poly.get((i + 1) % poly.size());
            if (onBoundary(a, b, ymax)) {
                continue;
            }
            float mx = (a[0] + b[0]) * 0.5F - cs;
            float my = (a[1] + b[1]) * 0.5F - cy;
            float hx = alongX ? mx : 0.0F;
            float hz = alongX ? 0.0F : mx;
            polyAt(vc, pose, light, sprite, mode, 0.5F, 0.5F, hx, my, hz,
                    wallPoint(alongX, a, front), wallPoint(alongX, b, front), wallPoint(alongX, b, back), wallPoint(alongX, a, back));
        }
    }

    /** Sides on the block's own boundary or in the seam between two strips stay out; the top (under the slab) is drawn so nothing can be seen through. */
    private static boolean onBoundary(float[] a, float[] b, float ymax) {
        float e = 1.0E-4F;
        return (a[0] < e && b[0] < e) || (a[0] > 1.0F - e && b[0] > 1.0F - e)
                || (Math.abs(a[0] - 0.5F) < e && Math.abs(b[0] - 0.5F) < e);
    }

    private static float[] wallPoint(boolean alongX, float[] q, float depth) {
        return alongX ? new float[]{q[0], q[1], depth} : new float[]{depth, q[1], q[0]};
    }

    /** Clips a convex polygon to the half plane {@code nx * s + ny * y <= c}. */
    private static java.util.List<float[]> clip(java.util.List<float[]> in, float nx, float ny, float c) {
        java.util.List<float[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            float[] a = in.get(i);
            float[] b = in.get((i + 1) % in.size());
            float da = nx * a[0] + ny * a[1] - c;
            float db = nx * b[0] + ny * b[1] - c;
            if (da <= 0.0F) {
                out.add(a);
            }
            if ((da < 0.0F && db > 0.0F) || (da > 0.0F && db < 0.0F)) {
                float t = da / (da - db);
                out.add(new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
            }
        }
        return out;
    }

    /** An axis-aligned box (uv mode as in polyAt). */
    private static void box(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite sprite, int mode, float x0, float x1,
                            float y0, float y1, float z0, float z1) {
        polyAt(vc, pose, light, sprite, mode, 0.5F, 0.5F, 0, 0, -1,
                new float[]{x0, y0, z0}, new float[]{x1, y0, z0}, new float[]{x1, y1, z0}, new float[]{x0, y1, z0});
        polyAt(vc, pose, light, sprite, mode, 0.5F, 0.5F, 0, 0, 1,
                new float[]{x0, y0, z1}, new float[]{x1, y0, z1}, new float[]{x1, y1, z1}, new float[]{x0, y1, z1});
        polyAt(vc, pose, light, sprite, mode, 0.5F, 0.5F, -1, 0, 0,
                new float[]{x0, y0, z0}, new float[]{x0, y0, z1}, new float[]{x0, y1, z1}, new float[]{x0, y1, z0});
        polyAt(vc, pose, light, sprite, mode, 0.5F, 0.5F, 1, 0, 0,
                new float[]{x1, y0, z0}, new float[]{x1, y0, z1}, new float[]{x1, y1, z1}, new float[]{x1, y1, z0});
        polyAt(vc, pose, light, sprite, mode, 0.5F, 0.5F, 0, -1, 0,
                new float[]{x0, y0, z0}, new float[]{x1, y0, z0}, new float[]{x1, y0, z1}, new float[]{x0, y0, z1});
        polyAt(vc, pose, light, sprite, mode, 0.5F, 0.5F, 0, 1, 0,
                new float[]{x0, y1, z0}, new float[]{x1, y1, z0}, new float[]{x1, y1, z1}, new float[]{x0, y1, z1});
    }

    /**
     * The powders fall to the lowest point of the cavity, the middle where the two valleys cross, and lie there as a
     * mound that runs out along the valleys. The mound is a fan of triangles from the peak to a ring of points that lie
     * exactly on the floor (the floor is {@code 0.5 + min(|x - 0.5|, |z - 0.5|)}), so nothing floats. Blaze powder
     * heaps up on top of the gunpowder when both are in.
     */
    private static void drawHeaps(PoseStack.Pose pose, VertexConsumer vc, int light, boolean gunpowder, boolean blaze) {
        if (gunpowder) {
            pile(vc, pose, light, sprite(GUNPOWDER), 0.78F, 0.55F, 1);
        }
        if (blaze) {
            int bright = LightTexture.pack(Math.max(LightTexture.block(light), 13), LightTexture.sky(light));
            if (gunpowder) {
                pile(vc, pose, bright, sprite(BLAZE), 0.90F, 0.9F, 2);
            } else {
                pile(vc, pose, bright, sprite(BLAZE), 0.82F, 0.55F, 2);
            }
        }
    }

    private static void pile(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite sprite, float peak,
                             float slope, int seed) {
        final int n = 16;
        float[][] ring = new float[n][];
        for (int k = 0; k < n; k++) {
            double ang = Math.PI * 2.0 * k / n;
            float c = (float) Math.cos(ang);
            float sn = (float) Math.sin(ang);
            float m = Math.min(Math.abs(c), Math.abs(sn));
            // Where the cone y = peak - slope * r meets the floor y = 0.5 + r * m, a little uneven.
            float r = (peak - 0.5F) / (slope + FLOOR_SLOPE * m);
            r = Math.min(0.46F, r * (0.9F + 0.2F * (((k * 7 + seed * 5) % 5) / 4.0F))); // never beyond the walls
            float x = 0.5F + c * r;
            float z = 0.5F + sn * r;
            ring[k] = new float[]{x, 0.5F + FLOOR_SLOPE * Math.min(Math.abs(x - 0.5F), Math.abs(z - 0.5F)), z};
        }
        float[] top = {0.5F, peak, 0.5F};
        for (int k = 0; k < n; k++) {
            poly(vc, pose, light, sprite, 0, 0, 1, 0, top, ring[k], ring[(k + 1) % n]);
        }
    }

    /**
     * The Core Rune as a golden tablet (a thin box, drawn in the pose it is given: centre at the origin). The front and
     * back show the animated item texture.
     */
    private static void drawTablet(PoseStack.Pose pose, VertexConsumer vc) {
        TextureAtlasSprite rune = sprite(RUNE_ITEM);
        TextureAtlasSprite edge = sprite(MARBLE); // the edge of the tablet is obsidian
        int full = LightTexture.FULL_BRIGHT;
        float hw = 0.115F;
        float hh = 0.16F;
        float hd = 0.03F;
        // The tablet fills the texture from x = 3..13, y = 1..15 (of 16).
        float u0 = rune.getU0() + (rune.getU1() - rune.getU0()) * (3.0F / 16.0F);
        float u1 = rune.getU0() + (rune.getU1() - rune.getU0()) * (13.0F / 16.0F);
        float v0 = rune.getV0() + (rune.getV1() - rune.getV0()) * (1.0F / 16.0F);
        float v1 = rune.getV0() + (rune.getV1() - rune.getV0()) * (15.0F / 16.0F);
        face(vc, pose, full, -hw, -hh, hd, hw, -hh, hd, hw, hh, hd, -hw, hh, hd, u0, v1, u1, v1, u1, v0, u0, v0, 0, 0, 1);
        face(vc, pose, full, hw, -hh, -hd, -hw, -hh, -hd, -hw, hh, -hd, hw, hh, -hd, u0, v1, u1, v1, u1, v0, u0, v0, 0, 0, -1);
        poly(vc, pose, full, edge, 3, 1, 0, 0, new float[]{hw, -hh, -hd}, new float[]{hw, hh, -hd}, new float[]{hw, hh, hd}, new float[]{hw, -hh, hd});
        poly(vc, pose, full, edge, 3, -1, 0, 0, new float[]{-hw, -hh, -hd}, new float[]{-hw, hh, -hd}, new float[]{-hw, hh, hd}, new float[]{-hw, -hh, hd});
        poly(vc, pose, full, edge, 3, 0, 1, 0, new float[]{-hw, hh, -hd}, new float[]{hw, hh, -hd}, new float[]{hw, hh, hd}, new float[]{-hw, hh, hd});
        poly(vc, pose, full, edge, 3, 0, -1, 0, new float[]{-hw, -hh, -hd}, new float[]{hw, -hh, -hd}, new float[]{hw, -hh, hd}, new float[]{-hw, -hh, hd});
    }

    private static void face(VertexConsumer vc, PoseStack.Pose pose, int light, float x0, float y0, float z0, float x1,
                             float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float ua,
                             float va, float ub, float vb, float uc, float vc2, float ud, float vd, float nx, float ny,
                             float nz) {
        quadVertex(vc, pose, light, x0, y0, z0, ua, va, nx, ny, nz);
        quadVertex(vc, pose, light, x1, y1, z1, ub, vb, nx, ny, nz);
        quadVertex(vc, pose, light, x2, y2, z2, uc, vc2, nx, ny, nz);
        quadVertex(vc, pose, light, x3, y3, z3, ud, vd, nx, ny, nz);
    }

    private static void quadVertex(VertexConsumer vc, PoseStack.Pose pose, int light, float x, float y, float z, float u,
                                   float v, float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, nx, ny, nz);
    }

    // ------------------------------------------------------------------
    // Glass (drawn last)
    // ------------------------------------------------------------------

    private static void drawGlass(PoseStack.Pose pose, MultiBufferSource buffer, int light) {
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(GLASS_TEXTURE));
        int bright = LightTexture.pack(Math.max(LightTexture.block(light), 10), LightTexture.sky(light));
        // The glass stops under the slab (its top edge is where the slab ends), so it does not cover the slab's side.
        float g = 1.0F - SLAB;
        float a = 0.0F;
        float b = 1.0F;
        glassTri(vc, pose, bright, a, g, 0, b, g, 0, N, 0, 0, -1);
        glassTri(vc, pose, bright, a, g, 1, b, g, 1, S, 0, 0, 1);
        glassTri(vc, pose, bright, 0, g, a, 0, g, b, W, -1, 0, 0);
        glassTri(vc, pose, bright, 1, g, a, 1, g, b, E, 1, 0, 0);
    }

    /** Thickness of the glass (into the block). */
    private static final float GLASS_DEPTH = 0.03F;

    /**
     * A glass triangle with thickness: the top edge from (ax, ay, az) to (bx, by, bz), the tip c, (nx, ny, nz) pointing
     * out of the block. A back sheet, three side faces and a front sheet. u runs along the top edge, v down to the tip.
     */
    private static void glassTri(VertexConsumer vc, PoseStack.Pose pose, int light, float ax, float ay, float az, float bx,
                                 float by, float bz, float[] c, float nx, float ny, float nz) {
        float d = GLASS_DEPTH;
        float[][] front = {{ax, ay, az}, {bx, by, bz}, {c[0], c[1], c[2]}};
        float[][] back = new float[3][];
        for (int i = 0; i < 3; i++) {
            back[i] = new float[]{front[i][0] - nx * d, front[i][1] - ny * d, front[i][2] - nz * d};
        }
        float[][] uv = {{0.0F, 0.0F}, {1.0F, 0.0F}, {0.5F, 1.0F}};
        // the back sheet first, so that the front sheet blends over it when seen from outside
        for (int i = 0; i < 4; i++) {
            int k = Math.min(i, 2);
            glassVertex(vc, pose, light, back[k][0], back[k][1], back[k][2], uv[k][0], uv[k][1], nx, ny, nz);
        }
        for (int e = 0; e < 3; e++) {
            int f = (e + 1) % 3;
            glassVertex(vc, pose, light, front[e][0], front[e][1], front[e][2], 0.5F, 0.5F, nx, ny, nz);
            glassVertex(vc, pose, light, front[f][0], front[f][1], front[f][2], 0.5F, 0.5F, nx, ny, nz);
            glassVertex(vc, pose, light, back[f][0], back[f][1], back[f][2], 0.5F, 0.5F, nx, ny, nz);
            glassVertex(vc, pose, light, back[e][0], back[e][1], back[e][2], 0.5F, 0.5F, nx, ny, nz);
        }
        for (int i = 0; i < 4; i++) {
            int k = Math.min(i, 2);
            glassVertex(vc, pose, light, front[k][0], front[k][1], front[k][2], uv[k][0], uv[k][1], nx, ny, nz);
        }
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

    /** Glow around the Core Rune and a flicker over the blaze powder. */
    private static void drawItemGlow(PoseStack pose, MultiBufferSource buffer, float time, float scale, boolean rune,
                                     boolean blaze, boolean ready) {
        VertexConsumer glow = buffer.getBuffer(RenderType.eyes(HALO));
        if (rune) {
            float cy = 0.77F + 0.025F * Mth.sin(time * 0.08F);
            float k = (0.45F + 0.1F * Mth.sin(time * 0.15F)) * scale;
            billboard(glow, pose.last(), 0.5F, cy, 0.5F, 0.30F * scale, 0.0F, 1.0F * k, 0.62F * k, 0.15F * k);
            if (ready) {
                billboard(glow, pose.last(), 0.5F, cy, 0.5F, 0.45F * scale, 0.0F, 1.0F * k, 0.75F * k, 0.25F * k);
            }
        }
        if (blaze) {
            float flicker = 0.75F + 0.25F * Mth.sin(time * 0.7F) * Mth.sin(time * 0.31F + 1.0F);
            billboard(glow, pose.last(), 0.5F, 0.80F, 0.5F, 0.34F * scale, 0.0F, 0.9F * flicker * scale, 0.45F * flicker * scale, 0.08F * scale);
        }
    }

    /** The lightning strike on the shut Sarcophagus: a jagged bolt from high above, flickering. {@code k}: 1 .. 0 fading. */
    private static void drawBolt(PoseStack pose, MultiBufferSource buffer, float time, float k) {
        VertexConsumer vc = buffer.getBuffer(RenderType.eyes(TRIM));
        PoseStack.Pose p = pose.last();
        java.util.Random rnd = new java.util.Random(((long) (time * 0.5F)) * 7919L + 13L);
        float flick = 0.7F + 0.3F * rnd.nextFloat();
        float power = k * flick;
        final int segs = 18;
        final float topY = 60.0F;
        float x = 0.5F;
        float y = topY;
        float z = 0.5F;
        for (int i = 1; i <= segs; i++) {
            float ny = topY + (1.0F - topY) * i / segs;
            float spread = i == segs ? 0.0F : 1.1F * (0.4F + 0.6F * (float) i / segs);
            float nx = i == segs ? 0.5F : 0.5F + (rnd.nextFloat() - 0.5F) * spread;
            float nz = i == segs ? 0.5F : 0.5F + (rnd.nextFloat() - 0.5F) * spread;
            laser(vc, p, x, y, z, nx, ny, nz, 0.045F, 1.0F * power, 0.97F * power, 0.9F * power);
            laser(vc, p, x, y, z, nx, ny, nz, 0.16F, 0.55F * power, 0.45F * power, 0.15F * power);
            if (i == segs - 6) { // one side branch
                float bx = nx + (rnd.nextFloat() - 0.5F) * 3.0F;
                float bz = nz + (rnd.nextFloat() - 0.5F) * 3.0F;
                laser(vc, p, nx, ny, nz, bx, ny - 6.0F, bz, 0.03F, 0.9F * power, 0.85F * power, 0.7F * power);
            }
            x = nx;
            y = ny;
            z = nz;
        }
    }

    /** A faint pulse along the gold of a shut Sarcophagus whose ritual is complete. */
    private static void drawGlimmer(PoseStack pose, MultiBufferSource buffer, float glow) {
        if (glow <= 0.0F) {
            return;
        }
        VertexConsumer vc = buffer.getBuffer(RenderType.eyes(TRIM));
        PoseStack.Pose p = pose.last();
        float k = 0.12F * glow;
        for (float[] e : LOWER_BARS) {
            laser(vc, p, e[0], e[1], e[2], e[3], e[4], e[5], 0.02F, 1.0F * k, 0.7F * k, 0.15F * k);
        }
        for (int i = 0; i < 4; i++) { // the gold outline of the slab
            float[] e = UPPER_BARS[i];
            laser(vc, p, e[0], e[1], e[2], e[3], e[4], e[5], 0.02F, 1.0F * k, 0.7F * k, 0.15F * k);
        }
    }

    /** The firelight of the Core on the floor of the lower half and on the underside of the slab. */
    private static void drawInnerGlow(PoseStack pose, MultiBufferSource buffer, float time, float scale, float lift,
                                      float twist, float shake) {
        VertexConsumer vc = buffer.getBuffer(RenderType.eyes(TRIM));
        float k = scale * (0.85F + 0.15F * Mth.sin(time * 0.19F));
        PoseStack.Pose p = pose.last();
        for (float[][] tri : FLOOR) {
            float[] a = {tri[0][0], tri[0][1] + 0.003F, tri[0][2]};
            float[] b = {tri[1][0], tri[1][1] + 0.003F, tri[1][2]};
            float[] c = {tri[2][0], tri[2][1] + 0.003F, tri[2][2]};
            glowTri(vc, p, a, b, c, 0.38F * k, 0.25F * k, 0.55F * k, true);
        }
        // The glow on the underside of the slab. It stays inside the frame (the strips and the gold line under the slab are
        // about 0.075 wide) and fades to almost nothing at its rim, so no bright edge or line shows round it or at the tips.
        pose.pushPose();
        pose.translate(0.5F + shake, lift, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(twist));
        pose.translate(-0.5F, 0.0F, -0.5F);
        PoseStack.Pose q = pose.last();
        float y = 1.0F - SLAB - 0.003F;
        float e = 0.09F;
        float[] m = {0.5F, y, 0.5F};
        float[][] corners = {{e, y, e}, {1 - e, y, e}, {1 - e, y, 1 - e}, {e, y, 1 - e}};
        for (int i = 0; i < 4; i++) {
            glowTri(vc, q, corners[i], corners[(i + 1) % 4], m, 0.03F * k, 0.03F * k, 0.4F * k, false);
        }
        pose.popPose();
    }

    /** A triangle with a glow colour (orange) per corner, intensities ka, kb, kc; faces up or down. */
    private static void glowTri(VertexConsumer vc, PoseStack.Pose pose, float[] a, float[] b, float[] c, float ka, float kb,
                                float kc, boolean up) {
        float[] n = normal(a, b, c);
        boolean swap = (n[1] > 0.0F) != up;
        float[][] pts = swap ? new float[][]{a, c, b} : new float[][]{a, b, c};
        float[] ks = swap ? new float[]{ka, kc, kb} : new float[]{ka, kb, kc};
        for (int i = 0; i < 4; i++) {
            int j = Math.min(i, 2);
            float k = ks[j];
            glowVertex(vc, pose, pts[j][0], pts[j][1], pts[j][2], 0.5F, 0.5F,
                    Mth.clamp((int) (k * 255.0F), 0, 255), Mth.clamp((int) (k * 0.42F * 255.0F), 0, 255),
                    Mth.clamp((int) (k * 0.08F * 255.0F), 0, 255));
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
        sphere(fire, pose.last(), ORB_RADIUS * scale * flick, time * 0.006F, -time * 0.012F, 1.0F * pulse, 0.78F * pulse, 0.5F * pulse);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-time * 2.6F));
        pose.mulPose(Axis.ZP.rotationDegrees(25.0F));
        sphere(fire, pose.last(), ORB_RADIUS * 1.28F * scale * flick, 0.37F - time * 0.008F, -time * 0.02F, 0.6F * pulse, 0.36F * pulse, 0.18F * pulse);
        pose.popPose();
        // Flames licking out of it: a wide, faint shell that turns slowly the other way.
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 1.1F));
        pose.mulPose(Axis.XP.rotationDegrees(-30.0F));
        sphere(fire, pose.last(), ORB_RADIUS * 1.7F * scale * flick, 0.71F + time * 0.004F, -time * 0.03F, 0.34F * pulse, 0.16F * pulse, 0.05F * pulse);
        pose.popPose();
        VertexConsumer heart = buffer.getBuffer(RenderType.eyes(TRIM));
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        sphere(heart, pose.last(), ORB_RADIUS * 0.36F * scale, 0.0F, 0.0F, 1.0F, 0.68F, 0.26F);
        pose.popPose();
        // Light around it.
        VertexConsumer glow = buffer.getBuffer(RenderType.eyes(HALO));
        PoseStack.Pose p = pose.last();
        float s = scale;
        billboard(glow, p, 0.5F, cy, 0.5F, 0.75F * s, 0.0F, 0.4F * pulse * s, 0.17F * pulse * s, 0.03F * s);
        billboard(glow, p, 0.5F, cy, 0.5F, 0.27F * s, 0.0F, 0.75F * pulse * s, 0.38F * pulse * s, 0.08F * s);
        if (flash > 0.0F) {
            billboard(glow, p, 0.5F, cy, 0.5F, (0.8F + 1.4F * flash) * s, 0.0F, flash, 0.55F * flash, 0.15F * flash);
        }
        // Star of light: two turning stars of different size.
        VertexConsumer flare = buffer.getBuffer(RenderType.eyes(FLARE));
        float fk = (0.8F + 0.2F * Mth.sin(time * 0.31F)) * s;
        billboard(flare, p, 0.5F, cy, 0.5F, (0.6F + 0.5F * flash) * s, time * 0.012F, 0.6F * fk, 0.36F * fk, 0.12F * fk);
        billboard(flare, p, 0.5F, cy, 0.5F, 0.4F * s, 0.4F - time * 0.02F, 0.45F * fk, 0.26F * fk, 0.08F * fk);
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
        float k = 0.8F * scale * flicker;
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
        polyAt(vc, pose, light, sprite, uvMode, 0.5F, 0.5F, hx, hy, hz, pts);
    }

    /** Like {@link #poly}; with {@code uvMode} 3 the polygon shows the point (fixedU, fixedV) of the sprite; with 4 the sprite column follows the position (x + y + z) and the row is fixedV. */
    private static void polyAt(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite sprite, int uvMode,
                               float fixedU, float fixedV, float hx, float hy, float hz, float[]... pts) {
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
                fu = fixedU;
                fv = fixedV;
            } else if (uvMode == 4) {
                // position along the diagonal of the block: lets a glint run over the frame
                fu = Mth.clamp((p[0] + p[1] + p[2]) / 3.1F, 0.03F, 0.97F);
                fv = fixedV;
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
