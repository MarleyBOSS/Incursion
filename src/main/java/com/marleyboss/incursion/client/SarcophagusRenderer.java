package com.marleyboss.incursion.client;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlock;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.registry.ModSounds;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
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
import com.marleyboss.incursion.block.CoreEvolution;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4fStack;
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
    private static final ResourceLocation WALL_AWAKE = block("sarkophag_wall_awake");
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
    private static final ResourceLocation HALO_A = effect("halo_a");
    private static final ResourceLocation FLARE_A = effect("flare_a");
    private static final ResourceLocation RING_A = effect("rune_circle_a");
    private static final ResourceLocation ORB_A = effect("orb_a");
    private static final ResourceLocation VORTEX = effect("vortex");

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
        float scale = -1.0F;
        float window = 0.0F;
        float scaleFrom = 1.0F;
        float scaleTo = 1.0F;
        float scaleStart = 0.0F;
        /** The stage as the player sees it, continuous: it runs up after an upgrade and back down during a defuse. */
        float vs = -1.0F;
        float vp = -1.0F;
        final java.util.Set<Long> booms = new java.util.HashSet<>();
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

        // The visual stage: it follows the stage of the Core slowly (so everything grows or moves to its next form) and,
        // while a defuse runs, falls back with the countdown (a cancelled defuse builds it up again).
        float stageGoal = 0.0F;
        if (active) {
            int goalStage = core.getStage();
            if (progress >= 0.0F) {
                stageGoal = goalStage * progress;
            } else if (level.getGameTime() < core.getEvoStart() + CoreEvolution.SHAKE && core.getBuiltStage() < goalStage
                    && state.vs >= 0.0F) {
                stageGoal = state.vs; // the blocks in the way are still blowing up
            } else {
                stageGoal = goalStage;
            }
        }
        if (state.vs < 0.0F || !active) {
            state.vs = stageGoal;
        } else if (progress >= 0.0F) {
            state.vs += (stageGoal - state.vs) * Math.min(1.0F, 0.2F * dt);
        } else {
            state.vs += Mth.clamp(stageGoal - state.vs, -0.014F * dt, 0.014F * dt);
        }
        float vs = state.vs;
        if (state.vp < 0.0F || !active || state.vp > vs) {
            state.vp = vs;
        } else if (progress < 0.0F && level.getGameTime() < core.getEvoStart() + CoreEvolution.SHAKE + 34L) {
            // the plaza is laid first: the pillars wait for it
            state.vp = state.vp;
        } else {
            state.vp += Mth.clamp(vs - state.vp, 0.0F, 0.014F * dt);
        }
        float vp = state.vp;
        float sc = CoreEvolution.scaleAt(vs);
        // From stage 2 on the glass windows swing open (slowly, and they breathe a little afterwards).
        float windowTarget = active ? 62.0F * Mth.clamp(vs - 1.0F, 0.0F, 1.0F) : 0.0F;
        state.window += (windowTarget - state.window) * Math.min(1.0F, 0.035F * dt);
        float windowDeg = state.window > 1.0F ? state.window + 2.5F * Mth.sin(time * 0.05F) : state.window;
        // The blocks that stood in the way shake and blow up.
        BlockPos corePos = core.getBlockPos();
        for (CoreBlockEntity.Ghost ghost : core.getGhosts()) {
            long key = ghost.pos().asLong() * 31L + ghost.boom();
            if (state.booms.contains(key)) {
                continue;
            }
            if (time >= ghost.boom()) {
                state.booms.add(key);
                if (time - ghost.boom() < 40.0F) {
                    Minecraft.getInstance().particleEngine.destroy(ghost.pos(), ghost.state());
                    level.addParticle(ParticleTypes.EXPLOSION, ghost.pos().getX() + 0.5, ghost.pos().getY() + 0.5,
                            ghost.pos().getZ() + 0.5, 0.0, 0.0, 0.0);
                }
                continue;
            }
            if (ghost.state().getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
                continue;
            }
            float left = ghost.boom() - time;
            float amp = 0.006F + 0.03F * Mth.clamp(1.0F - left / CoreEvolution.SHAKE, 0.0F, 1.0F);
            long seed = ghost.pos().asLong();
            float ph = (seed & 15L) * 0.7F;
            pose.pushPose();
            pose.translate(ghost.pos().getX() - corePos.getX() + Mth.sin(time * 3.1F + ph) * amp,
                    ghost.pos().getY() - corePos.getY() + Mth.sin(time * 2.3F + ph * 1.3F) * amp * 0.5F,
                    ghost.pos().getZ() - corePos.getZ() + Mth.sin(time * 2.7F + ph * 0.6F) * amp);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(ghost.state(), pose, buffer, light,
                    OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        if (state.booms.size() > 3000) {
            state.booms.clear();
        }

        // 1. Solid parts.
        pose.pushPose();
        pose.translate(0.5F, 0.0F, 0.5F);
        pose.scale(sc, sc, sc);
        pose.translate(-0.5F, 0.0F, -0.5F);
        VertexConsumer solid = buffer.getBuffer(Sheets.solidBlockSheet());
        drawLower(pose.last(), solid, light, active && vs >= 2.5F);
        drawLowerFrame(pose.last(), solid, light);
        drawPlaques(pose.last(), solid, light, vs);
        if (vs > 1.02F) {
            float g = ease(Mth.clamp(vs - 1.0F, 0.0F, 1.0F));
            pose.pushPose();
            pose.translate(0.5F, 0.0F, 0.5F);
            pose.scale(g, g, g);
            pose.translate(-0.5F, 0.0F, -0.5F);
            drawPlinth(pose.last(), solid, light);
            pose.popPose();
        }

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
        drawUpperFrame(pose, solid, light, windowDeg);
        if (vs > 0.02F) {
            drawLidTiers(pose, solid, light, vs);
        }
        pose.popPose();
        flushSolid(buffer);
        pose.popPose();
        if (vs > 0.02F) {
            VertexConsumer fresh = buffer.getBuffer(Sheets.solidBlockSheet()); // a fresh consumer: the old one was flushed
            drawChains(pose, fresh, time, sc, vs);
            drawSentinels(pose, fresh, sc, vs);
            drawPillars(pose, fresh, time, vp);
            drawLanterns(pose, fresh, time, vs);
            drawWyrm(pose, fresh, time, vs);
            flushSolid(buffer);
        }

        // 2. Everything see-through (additive glow, orb, rays, glass) is drawn later, after the water and the other
        // translucent blocks: drawn before them, it would be painted over by water behind it and its depth would cut holes in it.
        float flash = t < 1.0F ? Mth.sin(Mth.clamp((t - 0.45F) / 0.2F, 0.0F, 1.0F) * (float) Math.PI) : 0.0F;
        Late late = new Late();
        late.pos = core.getBlockPos();
        late.time = time;
        late.t = t;
        late.light = light;
        late.lift = lift;
        late.twist = twist;
        late.shake = shake;
        late.orb = orb;
        late.flash = flash;
        late.glow = glow;
        late.contents = contents;
        late.hasRune = hasRune;
        late.blazeHeap = hasBlaze && showHeaps;
        late.ready = ready;
        late.scale = sc;
        late.window = windowDeg;
        late.vs = active ? vs : 0.0F;
        late.vp = active ? vp : 0.0F;
        late.vr = CoreEvolution.pillarRadiusAt(vp);
        late.vh = active ? CoreEvolution.pillarAt(vp) : 0.0F;
        late.bolt = active && state.rising && t < 0.18F ? 1.0F - t / 0.18F : 0.0F;
        if (LATE.size() > 48) {
            LATE.clear(); // the late pass did not run (another render stage): do not collect without end
        }
        LATE.add(late);
    }

    /** What the block needs for the see-through part of its drawing, collected while the block entities are drawn. */
    private static final class Late {
        BlockPos pos;
        float time;
        float t;
        int light;
        float lift;
        float twist;
        float shake;
        float orb;
        float flash;
        float glow;
        float contents;
        boolean hasRune;
        boolean blazeHeap;
        boolean ready;
        float bolt;
        float scale;
        float vs;
        float vp;
        float vr;
        float vh;
        float window;
    }

    private static final List<Late> LATE = new ArrayList<>();

    /** Draws the collected see-through parts once the translucent blocks (water, ice, glass) are in the picture. */
    static void renderLate(RenderLevelStageEvent event) {
        // After the weather: the clouds are drawn before it, and what is drawn earlier fades behind them against the sky.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || LATE.isEmpty()) {
            return;
        }
        MultiBufferSource.BufferSource buffer = Minecraft.getInstance().renderBuffers().bufferSource();
        Vec3 cam = event.getCamera().getPosition();
        Matrix4fStack view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.identity();
        view.mul(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        alphaMode = true;
        try {
            for (Late d : LATE) {
                PoseStack pose = new PoseStack();
                pose.translate(d.pos.getX() - cam.x, d.pos.getY() - cam.y, d.pos.getZ() - cam.z);
                drawLate(d, pose, buffer);
            }
            buffer.endBatch();
        } finally {
            alphaMode = false;
            addK = 1.0F;
            LATE.clear();
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void drawLate(Late d, PoseStack pose, MultiBufferSource buffer) {
        float t = d.t;
        float time = d.time;
        pose.pushPose();
        pose.translate(0.5F, 0.0F, 0.5F);
        pose.scale(d.scale, d.scale, d.scale);
        pose.translate(-0.5F, 0.0F, -0.5F);
        if (d.contents > 0.0F) {
            drawItemGlow(pose, buffer, time, d.contents, d.hasRune, d.blazeHeap, d.ready);
        }
        if (d.bolt > 0.0F) {
            drawBolt(pose, buffer, time, d.bolt);
        }
        if (d.orb > 0.0F) {
            drawInnerGlow(pose, buffer, time, d.orb, d.lift, d.twist, d.shake);
            float cf = coreFactor(d.vs);
            pose.pushPose();
            pose.translate(0.5F, ORB_Y, 0.5F);
            pose.scale(cf, cf, cf);
            pose.translate(-0.5F, -ORB_Y, -0.5F);
            drawOrb(pose, buffer, time, d.orb, d.flash);
            pose.popPose();
            drawCorona(pose, buffer, d);
        }
        if (d.vs > 0.02F) {
            drawPlaqueGlyphs(pose, buffer, d);
            drawPlinthGlow(pose, buffer, d);
        }
        pose.popPose();
        drawEvolution(pose, buffer, d);

        // All glowing parts are drawn now, before the glass: the glass writes depth, and drawn earlier it would hide
        // whatever glows behind it (the order the buffers are drawn in is not fixed unless it is done by hand).
        flushAdditive(buffer);

        // Glass, last.
        pose.pushPose();
        pose.translate(0.5F, 0.0F, 0.5F);
        pose.scale(d.scale, d.scale, d.scale);
        pose.translate(-0.5F, 0.0F, -0.5F);
        pose.pushPose();
        pose.translate(0.5F + d.shake, d.lift, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(d.twist));
        pose.translate(-0.5F, 0.0F, -0.5F);
        drawGlass(pose.last(), buffer, d.light, d.window);
        pose.popPose();
        if (buffer instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(RenderType.entityTranslucent(GLASS_TEXTURE));
        }
        if (t <= 0.0F) {
            // A shut Sarcophagus glimmers along its gold when the ritual is ready.
            drawGlimmer(pose, buffer, d.ready ? d.glow : 0.0F);
        }
        pose.popPose();
    }

    private static void flushAdditive(MultiBufferSource buffer) {
        if (buffer instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(fx());
            source.endBatch(fxFire());
            source.endBatch(glow(TRIM));
            source.endBatch(glow(ORB));
            source.endBatch(glow(HALO));
            source.endBatch(glow(FLARE));
            source.endBatch(glow(RING));
            source.endBatch(vortexType());
        }
    }

    // ------------------------------------------------------------------
    // The evolution: a feature of its own for every stage
    // ------------------------------------------------------------------

    // --- the pillars change their form with the stage: forge (2) -> soul cage (3) -> rift shards (4) ---

    private static float toSoul(float vp) {
        return ease(Mth.clamp(vp - 2.0F, 0.0F, 1.0F));
    }

    private static float toRift(float vp) {
        return ease(Mth.clamp(vp - 3.0F, 0.0F, 1.0F));
    }

    /** Size factor of the pillars. */
    private static float pillarSize(float vp) {
        return 1.25F + 0.06F * Mth.clamp(vp - 1.0F, 0.0F, 3.0F);
    }

    /** Half width of the stone shaft: it gets slimmer and sharper with every form. */
    private static float shaftHalf(float vp) {
        return (0.30F - 0.05F * toSoul(vp) - 0.05F * toRift(vp)) * pillarSize(vp);
    }

    /** From stage 4 on the crystals leave their holders and float above them. */
    private static float crystalLift(float vp) {
        return 0.9F * toRift(vp);
    }

    private static float crystalH(float vp) {
        return Mth.lerp(toRift(vp), Mth.lerp(toSoul(vp), 1.0F, 1.35F), 1.15F);
    }

    private static float crystalR(float vp) {
        return Mth.lerp(toRift(vp), Mth.lerp(toSoul(vp), 0.55F, 0.38F), 0.34F);
    }

    /** Height of the middle of the crystal (above the hammer head in the forge form, inside the cage in the soul form). */
    private static float crystalCenter(float vp) {
        float top = CoreEvolution.pillarAt(vp) - 1.7F;
        return top + 0.62F * pillarSize(vp) * (1.0F - toSoul(vp)) + crystalH(vp) - 0.05F + crystalLift(vp);
    }

    /** Glow colour of the crystals: forge-hot red, soul violet, then the amber of the rift. */
    private static float[] crystalColor(float vp) {
        float b = toSoul(vp);
        float c = toRift(vp);
        return new float[]{Mth.lerp(c, Mth.lerp(b, 1.0F, 0.72F), 1.0F), Mth.lerp(c, Mth.lerp(b, 0.38F, 0.35F), 0.7F),
                Mth.lerp(c, Mth.lerp(b, 0.1F, 1.0F), 0.3F)};
    }

    /** Four thin gold strips round the edge of a plate. */
    private static void goldFrame(VertexConsumer vc, PoseStack.Pose p, int light, TextureAtlasSprite gold, float x0, float x1,
                                  float y, float z0, float z1, float w) {
        float e = OUT;
        box(vc, p, light, gold, 4, x0 - e, x1 + e, y - 0.012F, y + 0.012F, z0 - e, z0 + w);
        box(vc, p, light, gold, 4, x0 - e, x1 + e, y - 0.012F, y + 0.012F, z1 - w, z1 + e);
        box(vc, p, light, gold, 4, x0 - e, x0 + w, y - 0.012F, y + 0.012F, z0 + w, z1 - w);
        box(vc, p, light, gold, 4, x1 - w, x1 + e, y - 0.012F, y + 0.012F, z0 + w, z1 - w);
    }

    /** A closed loop of gold bars (a ring seen as a polygon), tilted about the x axis. */
    private static void ring(VertexConsumer vc, PoseStack.Pose p, int light, TextureAtlasSprite gold, float cx, float cy,
                             float cz, float radius, float tilt, float rot, int n, float w) {
        float[] prev = null;
        for (int k = 0; k <= n; k++) {
            float a = rot + k * (float) (Math.PI * 2.0) / n;
            float lx = Mth.cos(a) * radius;
            float lz = Mth.sin(a) * radius;
            float[] cur = {cx + lx, cy + lz * Mth.sin(tilt), cz + lz * Mth.cos(tilt)};
            if (prev != null) {
                beam(vc, p, light, gold, prev[0], prev[1], prev[2], cur[0], cur[1], cur[2], w);
            }
            prev = cur;
        }
    }

    /** An elongated octahedron (an energy crystal); every facet shows the whole texture once. */
    private static void crystal(VertexConsumer vc, PoseStack.Pose p, TextureAtlasSprite sprite, float cx, float y, float cz,
                                float r, float h, float rot) {
        float[] top = {cx, y + h, cz};
        float[] bottom = {cx, y - h, cz};
        float[][] eq = new float[4][];
        for (int e = 0; e < 4; e++) {
            float ang = rot + e * (float) (Math.PI / 2.0);
            eq[e] = new float[]{cx + Mth.cos(ang) * r, y, cz + Mth.sin(ang) * r};
        }
        for (int e = 0; e < 4; e++) {
            float[] a = eq[e];
            float[] b = eq[(e + 1) % 4];
            float mx = (a[0] + b[0]) * 0.5F - cx;
            float mz = (a[2] + b[2]) * 0.5F - cz;
            polyUV(vc, p, LightTexture.FULL_BRIGHT, sprite, mx, 0.6F, mz, new float[][]{a, b, top}, FACET_UV);
            polyUV(vc, p, LightTexture.FULL_BRIGHT, sprite, mx, -0.6F, mz, new float[][]{b, a, bottom}, FACET_UV);
        }
    }

    /**
     * The four pillars (solid). Stage 2 (the Breaker, a smith): a thick shaft with a hammer head on top and a forge-hot red
     * crystal over it. Stage 3 (the Soul Weaver): the hammer head draws in, a slim cage of gold arches rises round a
     * violet crystal that three rings turn about. Stage 4 (the rift): the cage breaks into black shards, the crystal floats
     * above them. Radius and height follow the (lagged) animated stage, so they grow after the plaza is laid.
     */
    private static void drawPillars(PoseStack pose, VertexConsumer vc, float time, float vp) {
        float height = CoreEvolution.pillarAt(vp);
        if (height < 0.3F) {
            return;
        }
        float vr = CoreEvolution.pillarRadiusAt(vp);
        float s = pillarSize(vp);
        float toB = toSoul(vp);
        float toC = toRift(vp);
        float wA = 1.0F - toB;
        float wB = toB * (1.0F - toC);
        float wC = toC;
        PoseStack.Pose p = pose.last();
        TextureAtlasSprite wall = sprite(vp >= 2.5F ? WALL_AWAKE : WALL);
        TextureAtlasSprite rock = sprite(WALL);
        TextureAtlasSprite marble = sprite(MARBLE);
        TextureAtlasSprite gold = sprite(GOLD);
        TextureAtlasSprite crystal = sprite(block(wC > 0.5F ? "core_crystal_amber" : wB > 0.5F ? "core_crystal_violet" : "core_crystal"));
        int light = LightTexture.pack(13, 15);
        float top = height - 1.7F; // top of the stone part
        float yc = crystalCenter(vp);
        float hw = shaftHalf(vp);
        float cr = crystalR(vp);
        float ch = crystalH(vp);
        for (int i = 0; i < 4; i++) {
            float cx = 0.5F + (i % 2 == 0 ? -vr : vr);
            float cz = 0.5F + (i < 2 ? -vr : vr);
            float ang = (float) Math.atan2(cz - 0.5F, cx - 0.5F);
            // two steps
            float h1 = Math.min(top, 0.22F);
            if (h1 > 0.02F) {
                fbox(vc, p, light, marble, wall, cx - 0.58F * s, cx + 0.58F * s, 0.0F, h1, cz - 0.58F * s, cz + 0.58F * s);
                goldFrame(vc, p, light, gold, cx - 0.58F * s, cx + 0.58F * s, h1, cz - 0.58F * s, cz + 0.58F * s, 0.016F);
            }
            float h2 = Math.min(top, 0.42F);
            if (h2 > 0.24F) {
                fbox(vc, p, light, marble, wall, cx - 0.45F * s, cx + 0.45F * s, 0.22F, h2, cz - 0.45F * s, cz + 0.45F * s);
                goldFrame(vc, p, light, gold, cx - 0.45F * s, cx + 0.45F * s, h2, cz - 0.45F * s, cz + 0.45F * s, 0.014F);
            }
            // the shaft
            float shaftBottom = 0.42F;
            float shaftTop = top - 0.42F;
            if (shaftTop > shaftBottom + 0.1F) {
                fbox(vc, p, light, marble, wall, cx - hw, cx + hw, shaftBottom, shaftTop, cz - hw, cz + hw);
                float pw = 0.045F;
                for (int c = 0; c < 4; c++) {
                    float px = cx + (c % 2 == 0 ? -hw : hw);
                    float pz = cz + (c < 2 ? -hw : hw);
                    box(vc, p, light, gold, 4, px - pw, px + pw, shaftBottom, shaftTop, pz - pw, pz + pw);
                }
                int bands = Math.max(1, Math.round((shaftTop - shaftBottom) / 1.4F));
                for (int b = 0; b < bands; b++) {
                    float y = shaftBottom + (shaftTop - shaftBottom) * (b + 0.5F) / bands;
                    box(vc, p, light, gold, 4, cx - hw - 0.05F, cx + hw + 0.05F, y - 0.05F, y + 0.05F, cz - hw - 0.05F, cz + hw + 0.05F);
                }
            }
            // the capital
            if (top > 0.5F) {
                float cb = Math.max(0.0F, top - 0.42F);
                fbox(vc, p, light, marble, wall, cx - 0.42F * s, cx + 0.42F * s, cb, cb + 0.16F, cz - 0.42F * s, cz + 0.42F * s);
                goldFrame(vc, p, light, gold, cx - 0.42F * s, cx + 0.42F * s, cb + 0.16F, cz - 0.42F * s, cz + 0.42F * s, 0.02F);
                fbox(vc, p, light, marble, wall, cx - 0.33F * s, cx + 0.33F * s, cb + 0.16F, top, cz - 0.33F * s, cz + 0.33F * s);
                goldFrame(vc, p, light, gold, cx - 0.33F * s, cx + 0.33F * s, top, cz - 0.33F * s, cz + 0.33F * s, 0.018F);
            }
            // forge form: a hammer head across the top (its long side turned to the sides)
            if (wA > 0.04F) {
                pose.pushPose();
                pose.translate(cx, top, cz);
                pose.mulPose(Axis.YP.rotationDegrees(-ang * 57.29578F));
                pose.scale(wA, wA, wA);
                PoseStack.Pose q = pose.last();
                float hh = 0.58F * s;
                fbox(vc, q, light, marble, wall, -0.50F * s, 0.50F * s, 0.0F, hh, -0.95F * s, 0.95F * s);
                goldFrame(vc, q, light, gold, -0.50F * s, 0.50F * s, hh, -0.95F * s, 0.95F * s, 0.02F);
                box(vc, q, light, gold, 4, -0.55F * s, 0.55F * s, -0.02F, hh + 0.04F, 0.95F * s, 1.05F * s);
                box(vc, q, light, gold, 4, -0.55F * s, 0.55F * s, -0.02F, hh + 0.04F, -1.05F * s, -0.95F * s);
                for (int c = 0; c < 4; c++) {
                    float sx = c % 2 == 0 ? -1.0F : 1.0F;
                    float sz = c < 2 ? -1.0F : 1.0F;
                    beam(vc, q, light, gold, sx * 0.30F * s, hh, sz * 0.60F * s, sx * 0.2F * cr, hh + 0.5F, sz * 0.2F * cr, 0.034F);
                }
                pose.popPose();
            }
            // soul form: a slim cage of four gold arches over the crystal
            if (wB > 0.04F) {
                float apex = top + (yc + ch * 0.95F + 0.3F - top) * wB;
                for (int c = 0; c < 4; c++) {
                    float a = c * (float) (Math.PI / 2.0) + (float) (Math.PI / 4.0);
                    float[] prev = null;
                    for (int sg = 0; sg <= 8; sg++) {
                        float t = sg / 8.0F;
                        float rr = 0.26F * s * (1.0F - t) + 0.62F * Mth.sin(t * (float) Math.PI) * wB;
                        float[] q = {cx + Mth.cos(a) * rr, top + (apex - top) * t, cz + Mth.sin(a) * rr};
                        if (prev != null) {
                            beam(vc, p, light, gold, prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.03F);
                        }
                        prev = q;
                    }
                }
                float rot2 = time * 0.03F + i;
                ring(vc, p, light, gold, cx, yc, cz, (cr + 0.5F) * wB, 0.0F, rot2, 12, 0.016F);
                ring(vc, p, light, gold, cx, yc, cz, (cr + 0.62F) * wB, 1.05F, -rot2, 12, 0.014F);
                ring(vc, p, light, gold, cx, yc, cz, (cr + 0.74F) * wB, -1.05F, rot2 * 0.7F, 12, 0.012F);
            }
            // rift form: three black shards lean out of the top
            if (wC > 0.04F) {
                for (int q = 0; q < 3; q++) {
                    float a = q * 2.094F + i * 0.7F;
                    float hgt = (2.5F - 0.4F * q) * wC;
                    float bs = 0.2F * s;
                    float[][] base = {{cx - bs, top, cz - bs}, {cx + bs, top, cz - bs}, {cx + bs, top, cz + bs}, {cx - bs, top, cz + bs}};
                    float[] tip = {cx + Mth.cos(a) * 0.85F * wC, top + hgt, cz + Mth.sin(a) * 0.85F * wC};
                    for (int e = 0; e < 4; e++) {
                        float[] a0 = base[e];
                        float[] a1 = base[(e + 1) % 4];
                        float mx = (a0[0] + a1[0]) * 0.5F - cx;
                        float mz = (a0[2] + a1[2]) * 0.5F - cz;
                        polyUV(vc, p, light, rock, mx, 0.3F, mz, new float[][]{a0, a1, tip}, FACET_UV);
                    }
                    beam(vc, p, light, gold, base[0][0], top + 0.02F, base[0][2], tip[0], tip[1] - 0.05F, tip[2], 0.014F);
                }
                for (int q = 0; q < 3; q++) {
                    float a = time * 0.05F + q * 2.094F + i;
                    float sy = yc + 0.5F * Mth.sin(time * 0.07F + q * 2.0F);
                    crystal(vc, p, rock, cx + Mth.cos(a) * 1.3F, sy, cz + Mth.sin(a) * 1.3F, 0.1F * wC, 0.24F * wC, time * 0.1F);
                }
            }
            // the crystal
            float y = yc + 0.07F * Mth.sin(time * 0.08F + i * 1.7F);
            crystal(vc, p, crystal, cx, y, cz, cr, ch, time * 0.045F + i * 0.8F);
        }
    }

    // --- the Core itself: a fiery mass that changes with every stage ---

    private static final float[] CORE_F = {1.0F, 1.2F, 1.4F, 1.65F, 1.9F};

    /** How much bigger the fire is than at the start; follows the animated stage. */
    private static float coreFactor(float vs) {
        float v = Mth.clamp(vs, 0.0F, 4.0F);
        int i = Math.min(3, (int) Math.floor(v));
        return Mth.lerp(v - i, CORE_F[i], CORE_F[i + 1]);
    }

    private static float[] dirOf(float phi, float theta) {
        return new float[]{Mth.cos(phi) * Mth.cos(theta), Mth.sin(theta), Mth.sin(phi) * Mth.cos(theta)};
    }

    /** Lines to draw later in one go: a solid strand (visible against a bright sky) inside a soft additive glow. */
    private static final class Lines {
        private final List<float[]> items = new ArrayList<>();

        void add(float ax, float ay, float az, float bx, float by, float bz, float w, float r, float g, float b) {
            items.add(new float[]{ax, ay, az, bx, by, bz, w, r, g, b});
        }

        void flush(MultiBufferSource buffer, PoseStack.Pose p) {
            if (items.isEmpty()) {
                return;
            }
            float keep = addK;
            addK = 1.0F; // the strand is blended, not added: it stays in full colour
            for (float[] l : items) {
                laser(buffer.getBuffer(fx()), p, l[0], l[1], l[2], l[3], l[4], l[5], l[6], l[7], l[8], l[9]);
            }
            addK = keep;
            items.clear();
        }
    }

    private static void circle(Lines lines, float cx, float cy, float cz, float r, int n, float w, float cr, float cg, float cb) {
        float px = cx + r;
        float pz = cz;
        for (int k = 1; k <= n; k++) {
            float a = k * (float) (Math.PI * 2.0) / n;
            float x = cx + Mth.cos(a) * r;
            float z = cz + Mth.sin(a) * r;
            lines.add(px, cy, pz, x, cy, z, w, cr, cg, cb);
            px = x;
            pz = z;
        }
    }

    /**
     * What the fire of the Core does on top of the orb (scaled pose): tongues of flame grow out of its surface, bend up, writhe
     * and die down again, every one on its own rhythm (six at stage 1, one more per stage). They are rooted on the orb and
     * move all the time, so they read as fire and not as rings. At stage 4 a soft glow burns on the tip of the crown.
     */
    private static void drawCorona(PoseStack pose, MultiBufferSource buffer, Late d) {
        float vs = d.vs;
        if (vs < 0.02F) {
            return;
        }
        final float time = d.time;
        final float k = d.orb;
        final float f = coreFactor(vs);
        PoseStack.Pose p = pose.last();
        float cx = 0.5F;
        float cy = ORB_Y;
        float cz = 0.5F;
        Lines lines = new Lines();
        int tongues = 6 + Math.min(3, (int) vs);
        float first = ease(Mth.clamp(vs, 0.0F, 1.0F));
        float r0 = ORB_RADIUS * 1.12F * f;
        for (int i = 0; i < tongues; i++) {
            float appear = i < 6 ? first : ease(Mth.clamp(vs - (i - 5), 0.0F, 1.0F));
            if (appear < 0.02F) {
                continue;
            }
            float phi = i * 2.399F + time * 0.005F * (i % 2 == 0 ? 1.0F : -1.0F);
            float theta = 1.05F * Mth.sin(i * 1.7F + time * 0.007F);
            float[] dd = dirOf(phi, theta);
            float sl = Mth.sqrt(dd[0] * dd[0] + dd[2] * dd[2]);
            float s1x = -dd[2] / sl;
            float s1z = dd[0] / sl;
            float s2x = dd[1] * s1z;
            float s2y = dd[2] * s1x - dd[0] * s1z;
            float s2z = -dd[1] * s1x;
            float life = 0.5F + 0.5F * Mth.sin(time * 0.13F + i * 2.9F) * Mth.sin(time * 0.071F + i * 1.3F + 1.0F);
            float len = (0.2F + 0.05F * Math.min(vs, 4.0F)) * f * (0.4F + 0.6F * life) * appear;
            for (int layer = 0; layer < 2; layer++) {
                float ll = len * (layer == 0 ? 1.0F : 0.62F);
                float[] prev = null;
                for (int sg = 0; sg <= 7; sg++) {
                    float u = sg / 7.0F;
                    float run = u * ll;
                    float swayA = Mth.sin(time * 0.21F + i * 1.3F + u * 4.5F + layer) * 0.2F * u * ll;
                    float swayB = Mth.cos(time * 0.17F + i + u * 3.8F) * 0.16F * u * ll;
                    float rr = r0 + run;
                    float[] pos = {
                            cx + dd[0] * rr + s1x * swayA + s2x * swayB,
                            cy + dd[1] * rr + 0.45F * u * u * ll + s2y * swayB,
                            cz + dd[2] * rr + s1z * swayA + s2z * swayB};
                    if (prev != null) {
                        float heat = (layer == 0 ? 1.0F : 1.2F) * k * appear * (0.55F + 0.45F * life);
                        float w = (layer == 0 ? 0.018F : 0.01F) * (1.0F - 0.8F * u);
                        float g = layer == 0 ? 0.8F - 0.5F * u : 0.95F - 0.35F * u;
                        float b = layer == 0 ? 0.32F - 0.28F * u : 0.6F - 0.5F * u;
                        lines.add(prev[0], prev[1], prev[2], pos[0], pos[1], pos[2], w, 1.0F * heat * (1.0F - 0.3F * u), g * heat, b * heat);
                    }
                    prev = pos;
                }
            }
        }
        lines.flush(buffer, p);
        float crown = ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F));
        if (crown > 0.02F) {
            float gy = CROWN_TOP + 0.62F + d.lift;
            float pulse = (0.8F + 0.2F * Mth.sin(time * 0.2F)) * crown * k;
            billboard(buffer.getBuffer(glow(HALO)), p, 0.5F, gy, 0.5F, 0.34F, 0.0F, 0.6F * pulse, 0.3F * pulse, 0.07F * pulse);
        }
    }

    /** Height of the tip of the crown (stage 4), relative to the lid. */
    private static final float CROWN_TOP = 1.75F;
    private static final float[][] FACET_UV = {{0.0F, 1.0F}, {1.0F, 1.0F}, {0.5F, 0.0F}};

    /**
     * The Sarcophagus is held down by four gold chains from the corners of its lower half to anchor rings in the plaza
     * (solid; world coordinates). They reach out of the corners while the animated stage passes stage 1.
     */
    /** Where chain i ends on the plaza (x, z) and how far it has reached out (0..1). */
    private static float[] anchorAt(int i, float scale, float vs) {
        float ext = ease(Mth.clamp(vs, 0.0F, 1.0F));
        float radius = Math.max(1.0F, CoreEvolution.radiusAt(vs));
        float reach = Math.min(0.5F * scale + 0.9F, radius + 0.4F);
        float sx = i % 2 == 0 ? -1.0F : 1.0F;
        float sz = i < 2 ? -1.0F : 1.0F;
        float tx = 0.5F + sx * 0.5F * scale;
        float tz = 0.5F + sz * 0.5F * scale;
        return new float[]{tx + (0.5F + sx * reach - tx) * ext, tz + (0.5F + sz * reach - tz) * ext, ext};
    }

    private static void drawChains(PoseStack pose, VertexConsumer vc, float time, float scale, float vs) {
        float ext = ease(Mth.clamp(vs, 0.0F, 1.0F));
        if (ext < 0.05F) {
            return;
        }
        TextureAtlasSprite gold = sprite(GOLD);
        int light = LightTexture.pack(13, 15);
        PoseStack.Pose p = pose.last();
        for (int i = 0; i < 4; i++) {
            float sx = i % 2 == 0 ? -1.0F : 1.0F;
            float sz = i < 2 ? -1.0F : 1.0F;
            float tx = 0.5F + sx * 0.5F * scale;
            float ty = TIP * scale;
            float tz = 0.5F + sz * 0.5F * scale;
            float[] an = anchorAt(i, scale, vs);
            float ax = an[0];
            float az = an[1];
            final int links = 14;
            for (int k = 1; k < links; k++) {
                float f = (float) k / links;
                float x = tx + (ax - tx) * f;
                float y = ty + (0.06F - ty) * f - 0.10F * Mth.sin(f * (float) Math.PI) + 0.012F * Mth.sin(time * 0.07F + k + i);
                float z = tz + (az - tz) * f;
                if (k % 2 == 0) {
                    box(vc, p, light, gold, 4, x - 0.07F, x + 0.07F, y - 0.04F, y + 0.04F, z - 0.03F, z + 0.03F);
                } else {
                    box(vc, p, light, gold, 4, x - 0.03F, x + 0.03F, y - 0.04F, y + 0.04F, z - 0.07F, z + 0.07F);
                }
            }
            // the anchor ring on the plaza
            float w = 0.42F * ext;
            float t = 0.07F;
            box(vc, p, light, gold, 4, ax - w, ax + w, 0.0F, 0.09F, az - w, az - w + t);
            box(vc, p, light, gold, 4, ax - w, ax + w, 0.0F, 0.09F, az + w - t, az + w);
            box(vc, p, light, gold, 4, ax - w, ax - w + t, 0.0F, 0.09F, az - w, az + w);
            box(vc, p, light, gold, 4, ax + w - t, ax + w, 0.0F, 0.09F, az - w, az + w);
        }
    }

    // --- the Sarcophagus itself changes its shape (in the style of the original: obsidian, gold, beads) ---

    /** A box with its own texture on the top and the sides, every face shows the whole texture once (no bleeding). */
    private static void fbox(VertexConsumer vc, PoseStack.Pose p, int light, TextureAtlasSprite top, TextureAtlasSprite side,
                             float x0, float x1, float y0, float y1, float z0, float z1) {
        float[][] uv = {{0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}, {0.0F, 0.0F}};
        polyUV(vc, p, light, side, 0, 0, -1, new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}}, uv);
        polyUV(vc, p, light, side, 0, 0, 1, new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}}, uv);
        polyUV(vc, p, light, side, -1, 0, 0, new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}}, uv);
        polyUV(vc, p, light, side, 1, 0, 0, new float[][]{{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}}, uv);
        polyUV(vc, p, light, top, 0, 1, 0, new float[][]{{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}}, uv);
        polyUV(vc, p, light, side, 0, -1, 0, new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}}, uv);
    }

    /** A bar of square section between two points (all faces, any direction). */
    private static void beam(VertexConsumer vc, PoseStack.Pose p, int light, TextureAtlasSprite sprite, float ax, float ay,
                             float az, float bx, float by, float bz, float w) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4F) {
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        float ux = -dz;
        float uz = dx;
        float ul = Mth.sqrt(ux * ux + uz * uz);
        if (ul < 1.0E-3F) {
            ux = 1.0F;
            uz = 0.0F;
            ul = 1.0F;
        }
        ux = ux / ul;
        uz = uz / ul;
        float uy = 0.0F;
        float vx = dy * uz - dz * uy;
        float vy = dz * ux - dx * uz;
        float vz = dx * uy - dy * ux;
        float[][] off = new float[4][];
        int[][] sg = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            off[i] = new float[]{(sg[i][0] * ux + sg[i][1] * vx) * w, (sg[i][0] * uy + sg[i][1] * vy) * w,
                    (sg[i][0] * uz + sg[i][1] * vz) * w};
        }
        float[][] uv = {{0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}, {0.0F, 0.0F}};
        for (int i = 0; i < 4; i++) {
            float[] o0 = off[i];
            float[] o1 = off[(i + 1) % 4];
            polyUV(vc, p, light, sprite, o0[0] + o1[0], o0[1] + o1[1], o0[2] + o1[2], new float[][]{
                    {ax + o0[0], ay + o0[1], az + o0[2]}, {ax + o1[0], ay + o1[1], az + o1[2]},
                    {bx + o1[0], by + o1[1], bz + o1[2]}, {bx + o0[0], by + o0[1], bz + o0[2]}}, uv);
        }
        polyUV(vc, p, light, sprite, -dx, -dy, -dz, new float[][]{{ax + off[0][0], ay + off[0][1], az + off[0][2]},
                {ax + off[1][0], ay + off[1][1], az + off[1][2]}, {ax + off[2][0], ay + off[2][1], az + off[2][2]},
                {ax + off[3][0], ay + off[3][1], az + off[3][2]}}, uv);
        polyUV(vc, p, light, sprite, dx, dy, dz, new float[][]{{bx + off[0][0], by + off[0][1], bz + off[0][2]},
                {bx + off[1][0], by + off[1][1], bz + off[1][2]}, {bx + off[2][0], by + off[2][1], bz + off[2][2]},
                {bx + off[3][0], by + off[3][1], bz + off[3][2]}}, uv);
    }

    /** A quad or triangle with its own texture coordinates (0..1 over the whole sprite); faces are oriented against (hx, hy, hz). */
    private static void polyUV(VertexConsumer vc, PoseStack.Pose pose, int light, TextureAtlasSprite sprite, float hx, float hy,
                               float hz, float[][] pts, float[][] uvs) {
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
            int i = Math.min(v, count - 1);
            int idx = flip ? count - 1 - i : i;
            float[] q = pts[idx];
            float u = sprite.getU0() + (sprite.getU1() - sprite.getU0()) * uvs[idx][0];
            float vv = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * uvs[idx][1];
            vc.addVertex(pose, q[0], q[1], q[2]).setColor(255, 255, 255, 255).setUv(u, vv)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
        }
    }

    /** Stage 2 (the Breaker, a smith): a two-step plinth like the foot of an anvil, with four horns at the corners (lower pose). */
    private static void drawPlinth(PoseStack.Pose p, VertexConsumer vc, int light) {
        TextureAtlasSprite wall = sprite(WALL);
        TextureAtlasSprite marble = sprite(MARBLE);
        TextureAtlasSprite gold = sprite(GOLD);
        int bright = LightTexture.pack(Math.max(LightTexture.block(light), 9), LightTexture.sky(light));
        float o1 = 0.22F;
        float h1 = 0.05F;
        float o2 = 0.12F;
        float h2 = 0.10F;
        fbox(vc, p, light, marble, wall, -o1, 1.0F + o1, 0.0F, h1, -o1, 1.0F + o1);
        fbox(vc, p, light, marble, wall, -o2, 1.0F + o2, h1, h2, -o2, 1.0F + o2);
        float w = 0.02F;
        float[][] steps = {{-o1, 1.0F + o1, 0.0F}, {-o2, 1.0F + o2, h1 - 0.012F}, {-o2, 1.0F + o2, h2 - 0.012F}};
        for (float[] st : steps) {
            float lo = st[0] - OUT;
            float hi = st[1] + OUT;
            float y = st[2];
            box(vc, p, bright, gold, 4, lo, hi, y, y + 0.024F, lo, lo + w);
            box(vc, p, bright, gold, 4, lo, hi, y, y + 0.024F, hi - w, hi);
            box(vc, p, bright, gold, 4, lo, lo + w, y, y + 0.024F, lo + w, hi - w);
            box(vc, p, bright, gold, 4, hi - w, hi, y, y + 0.024F, lo + w, hi - w);
        }
        // the horns of the anvil: they reach out of the corners
        for (int c = 0; c < 4; c++) {
            float sx = c % 2 == 0 ? -1.0F : 1.0F;
            float sz = c < 2 ? -1.0F : 1.0F;
            float cx = c % 2 == 0 ? -o1 + 0.04F : 1.0F + o1 - 0.04F;
            float cz = c < 2 ? -o1 + 0.04F : 1.0F + o1 - 0.04F;
            beam(vc, p, bright, gold, cx, h1 + 0.02F, cz, cx + sx * 0.34F, 0.025F, cz + sz * 0.34F, 0.05F);
        }
    }

    /**
     * The lid grows with the stages (lid pose): two steps from stage 1 on (like a stepped pyramid), from stage 3 on a
     * crown of four ribs that grow from the corners of the lid to a point above its centre, at stage 4 four more ribs
     * from the middle of the edges and a spire.
     */
    private static void drawLidTiers(PoseStack pose, VertexConsumer vc, int light, float vs) {
        TextureAtlasSprite top = sprite(TOP);
        TextureAtlasSprite marble = sprite(MARBLE);
        TextureAtlasSprite wall = sprite(WALL);
        TextureAtlasSprite gold = sprite(GOLD);
        int bright = LightTexture.pack(Math.max(LightTexture.block(light), 9), LightTexture.sky(light));
        float g1 = ease(Mth.clamp(vs, 0.0F, 1.0F));
        pose.pushPose();
        pose.translate(0.5F, 1.0F, 0.5F);
        pose.scale(g1, g1, g1);
        pose.translate(-0.5F, -1.0F, -0.5F);
        PoseStack.Pose p = pose.last();
        fbox(vc, p, light, top, marble, 0.17F, 0.83F, 0.998F, 1.07F, 0.17F, 0.83F);
        fbox(vc, p, light, top, marble, 0.33F, 0.67F, 1.068F, 1.14F, 0.33F, 0.67F);
        float w = 0.016F;
        float[][] steps = {{0.17F, 0.83F, 1.07F}, {0.33F, 0.67F, 1.14F}};
        for (float[] st : steps) {
            float a = st[0] - OUT;
            float b = st[1] + OUT;
            float y = st[2] - 0.008F;
            box(vc, p, bright, gold, 4, a, b, y, y + 0.016F, a, a + w);
            box(vc, p, bright, gold, 4, a, b, y, y + 0.016F, b - w, b);
            box(vc, p, bright, gold, 4, a, a + w, y, y + 0.016F, a + w, b - w);
            box(vc, p, bright, gold, 4, b - w, b, y, y + 0.016F, a + w, b - w);
        }
        pose.popPose();
        float g2 = ease(Mth.clamp(vs - 2.0F, 0.0F, 1.0F));
        if (g2 < 0.02F) {
            return;
        }
        float g3 = ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F));
        p = pose.last();
        float apex = 1.55F + 0.2F * g3;
        for (int r = 0; r < 8; r++) {
            float grow = r < 4 ? g2 : g3;
            if (grow < 0.02F) {
                continue;
            }
            float sx;
            float sz;
            if (r < 4) {
                sx = r % 2 == 0 ? 0.0F : 1.0F;
                sz = r < 2 ? 0.0F : 1.0F;
            } else {
                int q = r - 4;
                sx = q == 0 ? 0.5F : q == 1 ? 1.0F : q == 2 ? 0.5F : 0.0F;
                sz = q == 0 ? 0.0F : q == 1 ? 0.5F : q == 2 ? 1.0F : 0.5F;
            }
            float height = r < 4 ? apex - 1.0F : (apex - 1.0F) * 0.7F;
            final int segs = 10;
            int visible = Math.max(1, Math.round(segs * grow));
            float[] prev = null;
            for (int sg = 0; sg <= visible; sg++) {
                float t = sg / (float) segs;
                float x = sx + (0.5F - sx) * t;
                float z = sz + (0.5F - sz) * t;
                float y = 1.0F + height * Mth.sin(t * (float) (Math.PI / 2.0));
                if (prev != null) {
                    beam(vc, p, light, wall, prev[0], prev[1], prev[2], x, y, z, 0.026F);
                    beam(vc, p, bright, gold, prev[0], prev[1] + 0.022F, prev[2], x, y + 0.022F, z, 0.011F);
                }
                prev = new float[]{x, y, z};
            }
        }
        if (g3 > 0.02F) {
            fbox(vc, p, light, top, marble, 0.5F - 0.045F * g3, 0.5F + 0.045F * g3, apex - 0.05F * g3, apex + 0.05F * g3, 0.5F - 0.045F * g3, 0.5F + 0.045F * g3);
            beam(vc, p, bright, gold, 0.5F, apex + 0.05F, 0.5F, 0.5F, apex + 0.05F + 0.5F * g3, 0.5F, 0.022F);
        }
    }

    // --- the sigil plaques: the tablets are set into the walls of the Sarcophagus, one on each side ---

    /** A box on the outside of wall {@code side} (0 north, 1 east, 2 south, 3 west): s along the wall, y up, d out. */
    private static void plaqueBox(VertexConsumer vc, PoseStack.Pose p, int light, TextureAtlasSprite sprite, int side, float s0,
                                  float s1, float y0, float y1, float d0, float d1) {
        switch (side) {
            case 0 -> box(vc, p, light, sprite, 4, s0, s1, y0, y1, -d1, -d0);
            case 1 -> box(vc, p, light, sprite, 4, 1.0F + d0, 1.0F + d1, y0, y1, s0, s1);
            case 2 -> box(vc, p, light, sprite, 4, s0, s1, y0, y1, 1.0F + d0, 1.0F + d1);
            default -> box(vc, p, light, sprite, 4, -d1, -d0, y0, y1, s0, s1);
        }
    }

    /**
     * Four sockets (solid, lower pose) are cut into the walls with the first stage. A socket is empty until the tablet of its
     * guardian has been placed: then the tablet slides in from above. Level 5 holds one tablet, the other three sockets stay
     * visibly empty.
     */
    private static void drawPlaques(PoseStack.Pose p, VertexConsumer vc, int light, float vs) {
        float g = ease(Mth.clamp(vs * 1.4F, 0.0F, 1.0F));
        if (g < 0.05F) {
            return;
        }
        TextureAtlasSprite gold = sprite(GOLD);
        TextureAtlasSprite dark = sprite(INNER);
        int bright = LightTexture.pack(Math.max(LightTexture.block(light), 9), LightTexture.sky(light));
        for (int side = 0; side < 4; side++) {
            float fill = ease(Mth.clamp(vs - side, 0.0F, 1.0F));
            float s0 = 0.5F - 0.15F * g;
            float s1 = 0.5F + 0.15F * g;
            float y0 = 0.30F - 0.17F * g;
            float y1 = 0.30F + 0.17F * g;
            float fw = 0.03F;
            // the socket: a dark recess, a heavy gold frame and four studs
            plaqueBox(vc, p, bright, dark, side, s0, s1, y0, y1, 0.0F, 0.006F);
            plaqueBox(vc, p, bright, gold, side, s0 - fw, s1 + fw, y0 - fw, y0, 0.0F, 0.028F);
            plaqueBox(vc, p, bright, gold, side, s0 - fw, s1 + fw, y1, y1 + fw, 0.0F, 0.028F);
            plaqueBox(vc, p, bright, gold, side, s0 - fw, s0, y0, y1, 0.0F, 0.028F);
            plaqueBox(vc, p, bright, gold, side, s1, s1 + fw, y0, y1, 0.0F, 0.028F);
            for (int c = 0; c < 4; c++) {
                float sa = c % 2 == 0 ? s0 - fw : s1 - 0.005F;
                float ya = c < 2 ? y0 - fw : y1 - 0.005F;
                plaqueBox(vc, p, bright, gold, side, sa, sa + fw + 0.005F, ya, ya + fw + 0.005F, 0.0F, 0.042F);
            }
            if (fill > 0.02F) {
                // the tablet slides down into the socket
                TextureAtlasSprite plate = sprite(WALL_AWAKE);
                float top = y1 - 0.012F;
                float bottom = Mth.lerp(fill, top, y0 + 0.012F);
                plaqueBox(vc, p, bright, plate, side, s0 + 0.012F, s1 - 0.012F, bottom, top, 0.004F, 0.03F);
                plaqueBox(vc, p, bright, gold, side, s0 + 0.012F, s1 - 0.012F, bottom, bottom + 0.008F, 0.03F, 0.034F);
            }
        }
    }

    /** A point of a glyph on plaque {@code side}, d in front of the wall (lower pose). */
    private static float[] plaquePoint(int side, float gx, float gy, float d) {
        float y = 0.30F + gy;
        return switch (side) {
            case 0 -> new float[]{0.5F - gx, y, -d};
            case 1 -> new float[]{1.0F + d, y, 0.5F - gx};
            case 2 -> new float[]{0.5F + gx, y, 1.0F + d};
            default -> new float[]{-d, y, 0.5F + gx};
        };
    }

    private static void addPoly(List<float[]> out, float... xy) {
        for (int i = 0; i + 3 < xy.length; i += 2) {
            out.add(new float[]{xy[i], xy[i + 1], xy[i + 2], xy[i + 3]});
        }
    }

    private static void addCircle(List<float[]> out, float cx, float cy, float r, int n) {
        for (int k = 0; k < n; k++) {
            float a0 = k * (float) (Math.PI * 2.0) / n;
            float a1 = (k + 1) * (float) (Math.PI * 2.0) / n;
            out.add(new float[]{cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r, cx + (float) Math.cos(a1) * r,
                    cy + (float) Math.sin(a1) * r});
        }
    }

    /**
     * The glyph of every tablet is the sign of the guardian it was taken from: the eye of the Elder Guardian, the ear of the
     * Warden, the spiral of the Ender Dragon and the three skulls of the Wither. Segments as (x1, y1, x2, y2).
     */
    private static final float[][][] GLYPHS = buildGlyphs();

    private static float[][][] buildGlyphs() {
        float[][][] all = new float[4][][];
        List<float[]> g = new ArrayList<>();
        // 1: the eye (a lens, a pupil, lashes, waves)
        for (int u = 0; u < 10; u++) {
            float x0 = -0.3F + 0.6F * u / 10.0F;
            float x1 = -0.3F + 0.6F * (u + 1) / 10.0F;
            float y0 = 0.22F * (float) Math.sin(Math.PI * u / 10.0);
            float y1 = 0.22F * (float) Math.sin(Math.PI * (u + 1) / 10.0);
            g.add(new float[]{x0, y0, x1, y1});
            g.add(new float[]{x0, -y0, x1, -y1});
        }
        addCircle(g, 0.0F, 0.0F, 0.09F, 8);
        for (float x : new float[]{-0.15F, 0.0F, 0.15F}) {
            addPoly(g, x, 0.27F, x, 0.42F);
        }
        addPoly(g, -0.3F, -0.38F, -0.15F, -0.32F, 0.0F, -0.38F, 0.15F, -0.32F, 0.3F, -0.38F);
        addPoly(g, -0.3F, -0.5F, -0.15F, -0.44F, 0.0F, -0.5F, 0.15F, -0.44F, 0.3F, -0.5F);
        all[0] = g.toArray(new float[0][]);
        // 2: the ear (two arcs round a listening line)
        g = new ArrayList<>();
        for (int u = 0; u < 8; u++) {
            float a0 = -1.0F + 2.0F * u / 8.0F;
            float a1 = -1.0F + 2.0F * (u + 1) / 8.0F;
            g.add(new float[]{-0.12F - 0.16F * (float) Math.cos(a0), 0.32F * (float) Math.sin(a0), -0.12F - 0.16F * (float) Math.cos(a1), 0.32F * (float) Math.sin(a1)});
            g.add(new float[]{0.12F + 0.16F * (float) Math.cos(a0), 0.32F * (float) Math.sin(a0), 0.12F + 0.16F * (float) Math.cos(a1), 0.32F * (float) Math.sin(a1)});
        }
        addPoly(g, 0.0F, -0.4F, 0.0F, 0.38F);
        addCircle(g, 0.0F, 0.47F, 0.06F, 6);
        addPoly(g, -0.1F, -0.2F, 0.1F, -0.2F);
        addPoly(g, -0.07F, 0.0F, 0.07F, 0.0F);
        all[1] = g.toArray(new float[0][]);
        // 3: the spiral and two wings
        g = new ArrayList<>();
        float px = 0.04F;
        float py = 0.15F;
        for (int u = 1; u <= 24; u++) {
            float f = u / 24.0F;
            float r = 0.04F + 0.27F * f;
            float a = f * 9.0F;
            float x = (float) Math.cos(a) * r;
            float y = 0.15F + (float) Math.sin(a) * r;
            g.add(new float[]{px, py, x, y});
            px = x;
            py = y;
        }
        addPoly(g, -0.3F, -0.35F, 0.0F, -0.2F, 0.3F, -0.35F);
        addPoly(g, -0.2F, -0.5F, 0.0F, -0.4F, 0.2F, -0.5F);
        all[2] = g.toArray(new float[0][]);
        // 4: three skulls and the stake they hang on
        g = new ArrayList<>();
        addCircle(g, 0.0F, 0.32F, 0.1F, 8);
        addCircle(g, -0.22F, -0.02F, 0.1F, 8);
        addCircle(g, 0.22F, -0.02F, 0.1F, 8);
        addPoly(g, 0.0F, 0.22F, -0.22F, 0.08F);
        addPoly(g, 0.0F, 0.22F, 0.22F, 0.08F);
        addPoly(g, 0.0F, -0.1F, 0.0F, -0.52F);
        addPoly(g, -0.2F, -0.32F, 0.2F, -0.32F);
        all[3] = g.toArray(new float[0][]);
        return all;
    }

    /** Glow colour of the tablets: Elder Guardian (teal), Warden (cold blue), Ender Dragon (violet), Wither (ash). */
    private static final float[][] GUARD = {{0.25F, 0.95F, 0.85F}, {0.3F, 0.72F, 1.0F}, {0.78F, 0.42F, 1.0F}, {0.8F, 0.8F, 0.9F}};

    /**
     * The glyphs on the plaques (lower pose): a socket whose tablet has been placed glows in the colour of its guardian; an
     * empty socket shows only the faint ghost of the sign that is missing.
     */
    private static void drawPlaqueGlyphs(PoseStack pose, MultiBufferSource buffer, Late d) {
        float ext = ease(Mth.clamp(d.vs * 1.4F, 0.0F, 1.0F));
        if (ext < 0.1F) {
            return;
        }
        Lines lines = new Lines();
        float gs = 0.36F * ext;
        float[] lit = new float[4];
        for (int side = 0; side < 4; side++) {
            lit[side] = ease(Mth.clamp(d.vs - side, 0.0F, 1.0F));
            float[] c = GUARD[side];
            float k = 0.5F + 0.5F * lit[side] * (0.85F + 0.15F * Mth.sin(d.time * 0.09F + side * 1.3F));
            float ghost = 0.2F * (1.0F - lit[side]);
            for (float[] seg : GLYPHS[side]) {
                float[] a = plaquePoint(side, seg[0] * gs, seg[1] * gs, 0.036F);
                float[] b = plaquePoint(side, seg[2] * gs, seg[3] * gs, 0.036F);
                float kk = lit[side] * k;
                lines.add(a[0], a[1], a[2], b[0], b[1], b[2], 0.006F, c[0] * kk + 0.55F * ghost, c[1] * kk + 0.48F * ghost, c[2] * kk + 0.36F * ghost);
            }
        }
        PoseStack.Pose p = pose.last();
        lines.flush(buffer, p);
        for (int side = 0; side < 4; side++) {
            if (lit[side] > 0.05F) {
                float[] c = plaquePoint(side, 0.0F, 0.0F, 0.07F);
                float[] col = GUARD[side];
                float k = 0.4F * lit[side];
                billboard(buffer.getBuffer(glow(HALO)), p, c[0], c[1], c[2], 0.32F, 0.0F, col[0] * k, col[1] * k, col[2] * k);
            }
        }
    }

    /** The molten seam between the two steps of the anvil plinth (stage 2 on); lower pose, scaled like the plinth. */
    private static void drawPlinthGlow(PoseStack pose, MultiBufferSource buffer, Late d) {
        float g = ease(Mth.clamp(d.vs - 1.0F, 0.0F, 1.0F));
        if (g < 0.05F) {
            return;
        }
        Lines lines = new Lines();
        float k = g * (0.6F + 0.4F * Mth.sin(d.time * 0.08F));
        float lo = 0.5F + (-0.17F - 0.5F) * g;
        float hi = 0.5F + (1.17F - 0.5F) * g;
        float y = 0.056F * g;
        lines.add(lo, y, lo, hi, y, lo, 0.012F, 1.0F * k, 0.45F * k, 0.1F * k);
        lines.add(lo, y, hi, hi, y, hi, 0.012F, 1.0F * k, 0.45F * k, 0.1F * k);
        lines.add(lo, y, lo, lo, y, hi, 0.012F, 1.0F * k, 0.45F * k, 0.1F * k);
        lines.add(hi, y, lo, hi, y, hi, 0.012F, 1.0F * k, 0.45F * k, 0.1F * k);
        lines.flush(buffer, pose.last());
    }

    // ------------------------------------------------------------------
    // The stage objects (world coordinates). Every stage brings something of its own that belongs to its tablet and to the
    // general who guards it: 5 the Scout (shadow sentinels), 10 the Breaker (the forge), 15 the Soul Weaver (soul lanterns and
    // a spirit wyrm), 20 Varkhul (the rift with its three eyes).
    // ------------------------------------------------------------------

    private static void blade(VertexConsumer vc, PoseStack.Pose q, int light, TextureAtlasSprite gold, float x0, float y0,
                              float z0, float x1, float y1, float z1, float bulge) {
        float px = x0;
        float py = y0;
        float pz = z0;
        for (int k = 1; k <= 6; k++) {
            float u = k / 6.0F;
            float x = x0 + (x1 - x0) * u;
            float y = y0 + (y1 - y0) * u;
            float z = z0 + (z1 - z0) * u + bulge * Mth.sin(u * (float) Math.PI);
            beam(vc, q, light, gold, px, py, pz, x, y, z, 0.03F * (1.0F - 0.75F * u) + 0.006F);
            px = x;
            py = y;
            pz = z;
        }
    }

    /**
     * Stage 1, the Scout: on the dark pad at the end of every chain stands a hooded shadow sentinel (solid), a cloak that
     * tapers up into a hood with an empty face and two curved blades crossed on the chest. They step out of the floor.
     */
    private static void drawSentinels(PoseStack pose, VertexConsumer vc, float scale, float vs) {
        float g = ease(Mth.clamp((vs - 0.1F) / 0.9F, 0.0F, 1.0F));
        if (g < 0.03F) {
            return;
        }
        TextureAtlasSprite wall = sprite(WALL);
        TextureAtlasSprite marble = sprite(MARBLE);
        TextureAtlasSprite dark = sprite(INNER);
        TextureAtlasSprite gold = sprite(GOLD);
        int light = LightTexture.pack(11, 15);
        for (int i = 0; i < 4; i++) {
            float[] an = anchorAt(i, scale, vs);
            float yaw = (float) Math.atan2(0.5F - an[0], 0.5F - an[1]);
            pose.pushPose();
            pose.translate(an[0], -2.7F * (1.0F - g), an[1]);
            pose.mulPose(Axis.YP.rotation(yaw));
            PoseStack.Pose q = pose.last();
            fbox(vc, q, light, marble, wall, -0.32F, 0.32F, 0.0F, 0.10F, -0.32F, 0.32F);
            goldFrame(vc, q, light, gold, -0.32F, 0.32F, 0.10F, -0.32F, 0.32F, 0.016F);
            fbox(vc, q, light, wall, wall, -0.25F, 0.25F, 0.10F, 0.75F, -0.21F, 0.21F);
            fbox(vc, q, light, wall, wall, -0.21F, 0.21F, 0.75F, 1.30F, -0.17F, 0.17F);
            fbox(vc, q, light, wall, wall, -0.34F, 0.34F, 1.30F, 1.50F, -0.16F, 0.16F);
            fbox(vc, q, light, wall, wall, -0.19F, 0.19F, 1.50F, 1.74F, -0.17F, 0.19F);
            fbox(vc, q, light, wall, wall, -0.17F, 0.17F, 1.74F, 2.05F, -0.17F, 0.20F);
            float[] tip = {0.0F, 2.42F, -0.14F};
            float[][] bs = {{-0.17F, 2.05F, -0.17F}, {0.17F, 2.05F, -0.17F}, {0.17F, 2.05F, 0.20F}, {-0.17F, 2.05F, 0.20F}};
            for (int e = 0; e < 4; e++) {
                float[] a0 = bs[e];
                float[] a1 = bs[(e + 1) % 4];
                polyUV(vc, q, light, wall, (a0[0] + a1[0]) * 0.5F, 0.3F, (a0[2] + a1[2]) * 0.5F, new float[][]{a0, a1, tip}, FACET_UV);
            }
            fbox(vc, q, light, dark, dark, -0.11F, 0.11F, 1.80F, 2.0F, 0.195F, 0.215F);
            fbox(vc, q, light, dark, wall, -0.20F, 0.20F, 0.30F, 1.28F, -0.27F, -0.17F);
            beam(vc, q, light, wall, -0.34F, 1.38F, 0.0F, 0.12F, 1.05F, 0.24F, 0.075F);
            beam(vc, q, light, wall, 0.34F, 1.38F, 0.0F, -0.12F, 1.05F, 0.24F, 0.075F);
            blade(vc, q, light, gold, 0.14F, 1.0F, 0.26F, -0.34F, 1.95F, 0.30F, 0.16F);
            blade(vc, q, light, gold, -0.14F, 1.0F, 0.26F, 0.34F, 1.95F, 0.30F, 0.16F);
            pose.popPose();
        }
    }

    // --- stage 3: soul lanterns (the Soul Weaver) and the spirit wyrm (the Ender Dragon) ---

    private static final int LANTERNS = 5;

    /** Position of lantern i (x, y, z) and its size (0..1). */
    private static float[] lanternAt(int i, float time, float vs) {
        float g = ease(Mth.clamp(vs - 2.0F, 0.0F, 1.0F));
        float g4 = ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F));
        float ang = time * 0.012F + i * (float) (Math.PI * 2.0 / LANTERNS);
        float rad = (3.4F + 0.6F * g4 + 0.15F * Mth.sin(i * 2.1F + time * 0.02F)) * g;
        float y = Mth.lerp(g, 1.4F, 3.1F + 1.0F * g4 + 0.35F * Mth.sin(time * 0.03F + i * 1.9F));
        return new float[]{0.5F + Mth.cos(ang) * rad, y, 0.5F + Mth.sin(ang) * rad, g};
    }

    private static void drawLanterns(PoseStack pose, VertexConsumer vc, float time, float vs) {
        if (vs < 2.03F) {
            return;
        }
        PoseStack.Pose p = pose.last();
        TextureAtlasSprite gold = sprite(GOLD);
        TextureAtlasSprite dark = sprite(INNER);
        TextureAtlasSprite marble = sprite(MARBLE);
        int light = LightTexture.pack(12, 15);
        for (int i = 0; i < LANTERNS; i++) {
            float[] l = lanternAt(i, time, vs);
            float s = 0.17F * l[3];
            if (s < 0.01F) {
                continue;
            }
            float h = s * 1.4F;
            for (int c = 0; c < 4; c++) {
                float px = l[0] + (c % 2 == 0 ? -s : s);
                float pz = l[2] + (c < 2 ? -s : s);
                beam(vc, p, light, gold, px, l[1] - h, pz, px, l[1] + h, pz, 0.018F * l[3]);
            }
            fbox(vc, p, light, marble, dark, l[0] - s * 1.2F, l[0] + s * 1.2F, l[1] - h - 0.05F * l[3], l[1] - h, l[2] - s * 1.2F, l[2] + s * 1.2F);
            fbox(vc, p, light, marble, dark, l[0] - s * 1.2F, l[0] + s * 1.2F, l[1] + h, l[1] + h + 0.05F * l[3], l[2] - s * 1.2F, l[2] + s * 1.2F);
            beam(vc, p, light, gold, l[0], l[1] + h + 0.05F * l[3], l[2], l[0], l[1] + h + 0.3F * l[3], l[2], 0.012F * l[3]);
        }
    }

    /** The point of the spirit wyrm {@code s} behind its head (s negative: ahead of it). */
    private static float[] wyrmAt(float s, float time, float vs) {
        float g = ease(Mth.clamp(vs - 2.0F, 0.0F, 1.0F));
        float ang = time * 0.0075F - s * 0.085F;
        float radius = 9.0F + (1.0F - g) * 45.0F;
        float y = 12.5F + 1.2F * Mth.sin(ang * 2.0F + 1.0F) + 0.3F * Mth.sin(s * 0.8F - time * 0.12F);
        return new float[]{0.5F + Mth.cos(ang) * radius, y, 0.5F + Mth.sin(ang) * radius};
    }

    /** How present the wyrm is: it comes with stage 3 and the rift of stage 4 devours it. */
    private static float wyrmK(float vs) {
        return ease(Mth.clamp(vs - 2.0F, 0.0F, 1.0F)) * (1.0F - ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F)));
    }

    /** An original spirit wyrm (not the Ender Dragon itself): a tail of segments with crystal spines, horns, two flapping wings. */
    private static void drawWyrm(PoseStack pose, VertexConsumer vc, float time, float vs) {
        float k = wyrmK(vs);
        if (k < 0.03F) {
            return;
        }
        PoseStack.Pose p = pose.last();
        TextureAtlasSprite body = sprite(WALL);
        TextureAtlasSprite wing = sprite(WALL_AWAKE);
        TextureAtlasSprite gold = sprite(GOLD);
        TextureAtlasSprite violet = sprite(block("core_crystal_violet"));
        int light = LightTexture.pack(10, 15);
        final int n = 15;
        float[] head = wyrmAt(0.0F, time, vs);
        float[] prev = head;
        for (int sgm = 1; sgm <= n; sgm++) {
            float[] cur = wyrmAt(sgm * 0.62F, time, vs);
            float w = (0.20F * (float) Math.pow(1.0 - sgm / (n + 2.0), 0.8) + 0.03F) * k;
            beam(vc, p, light, body, prev[0], prev[1], prev[2], cur[0], cur[1], cur[2], w);
            if (sgm % 2 == 0 && sgm < n - 1) {
                crystal(vc, p, violet, cur[0], cur[1] + w + 0.1F * k, cur[2], 0.05F * k, 0.13F * k, time * 0.1F + sgm);
            }
            prev = cur;
        }
        float[] ahead = wyrmAt(-0.45F, time, vs);
        float dx = ahead[0] - head[0];
        float dy = ahead[1] - head[1];
        float dz = ahead[2] - head[2];
        float dl = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1.0E-4F) {
            return;
        }
        dx /= dl;
        dy /= dl;
        dz /= dl;
        float sx = -dz;
        float sz = dx;
        float sl = Mth.sqrt(sx * sx + sz * sz);
        if (sl < 1.0E-3F) {
            return;
        }
        sx /= sl;
        sz /= sl;
        beam(vc, p, light, body, head[0], head[1], head[2], head[0] + dx * 0.75F * k, head[1] + dy * 0.75F * k - 0.05F * k, head[2] + dz * 0.75F * k, 0.17F * k);
        beam(vc, p, light, body, head[0] + dx * 0.1F * k, head[1] - 0.15F * k, head[2] + dz * 0.1F * k,
                head[0] + dx * 0.6F * k, head[1] - 0.2F * k, head[2] + dz * 0.6F * k, 0.07F * k);
        for (int sd = -1; sd <= 1; sd += 2) {
            beam(vc, p, light, gold, head[0] + sx * 0.12F * sd * k, head[1] + 0.12F * k, head[2] + sz * 0.12F * sd * k,
                    head[0] + sx * 0.2F * sd * k - dx * 0.6F * k, head[1] + 0.5F * k, head[2] + sz * 0.2F * sd * k - dz * 0.6F * k, 0.02F * k);
        }
        float[] sh = wyrmAt(1.9F, time, vs);
        float flap = Mth.sin(time * 0.1F) * 0.75F;
        float cf = Mth.cos(flap);
        float sf = Mth.sin(flap);
        for (int sd = -1; sd <= 1; sd += 2) {
            float[] mid = {sh[0] + sx * sd * 1.1F * cf * k + dx * 0.3F * k, sh[1] + (1.1F * sf + 0.5F) * k, sh[2] + sz * sd * 1.1F * cf * k + dz * 0.3F * k};
            float[] tip = {sh[0] + sx * sd * 2.7F * cf * k - dx * 0.2F * k, sh[1] + (2.7F * sf + 0.2F) * k, sh[2] + sz * sd * 2.7F * cf * k - dz * 0.2F * k};
            float[] back = {sh[0] + sx * sd * 0.6F * k - dx * 1.7F * k, sh[1] - 0.1F * k, sh[2] + sz * sd * 0.6F * k - dz * 1.7F * k};
            float[] tipBack = {tip[0] - dx * 1.4F * k, tip[1] - 0.5F * k, tip[2] - dz * 1.4F * k};
            beam(vc, p, light, body, sh[0], sh[1], sh[2], mid[0], mid[1], mid[2], 0.045F * k);
            beam(vc, p, light, body, mid[0], mid[1], mid[2], tip[0], tip[1], tip[2], 0.032F * k);
            beam(vc, p, light, gold, mid[0], mid[1], mid[2], tipBack[0], tipBack[1], tipBack[2], 0.014F * k);
            float[][][] tris = {{sh, mid, tip}, {sh, tip, tipBack}, {sh, tipBack, back}};
            for (float[][] t : tris) {
                polyUV(vc, p, light, wing, 0.0F, 1.0F, 0.0F, t, FACET_UV);
                polyUV(vc, p, light, wing, 0.0F, -1.0F, 0.0F, t, FACET_UV);
            }
        }
    }

    /** A point on the ribbon that drains energy from the Core into lantern {@code l}, u = 0 (Core) .. 1 (lantern). */
    private static float[] ribbonPoint(float[] l, float coreY, float time, int i, float u) {
        float wob = Mth.sin(u * (float) Math.PI) * 0.35F;
        return new float[]{
                0.5F + (l[0] - 0.5F) * u + Mth.sin(time * 0.11F + u * 6.0F + i * 1.7F) * wob,
                coreY + (l[1] - coreY) * u + Mth.cos(time * 0.09F + u * 5.0F + i) * wob * 0.8F,
                0.5F + (l[2] - 0.5F) * u + Mth.sin(time * 0.1F + u * 5.5F + i * 2.3F + 1.0F) * wob};
    }

    /** Molten cracks run out over the plaza from the Core (stage 2, the Breaker's forge); they cool in the later stages. */
    private static void drawCracks(Lines lines, float time, float vp, float disc) {
        float wv = ease(Mth.clamp(vp - 1.0F, 0.0F, 1.0F));
        if (wv < 0.02F) {
            return;
        }
        float heat = (1.0F - 0.55F * toSoul(vp)) * wv;
        float reach = (disc - 0.9F) * wv;
        for (int j = 0; j < 9; j++) {
            float a = j * 0.698F + 0.25F * Mth.sin(j * 2.7F);
            float r = 1.15F;
            float px = 0.5F + Mth.cos(a) * r;
            float pz = 0.5F + Mth.sin(a) * r;
            int step = 0;
            while (r < reach) {
                float nr = Math.min(reach, r + 0.55F + 0.2F * Mth.sin(j + step * 1.7F));
                a += 0.2F * Mth.sin(j * 5.1F + nr * 2.3F);
                float nx = 0.5F + Mth.cos(a) * nr;
                float nz = 0.5F + Mth.sin(a) * nr;
                float pulse = 0.55F + 0.45F * Mth.sin(time * 0.06F - nr * 0.8F + j);
                float w = 0.05F * (1.0F - 0.5F * nr / Math.max(1.0F, reach));
                lines.add(px, 0.035F, pz, nx, 0.035F, nz, w, 1.0F * pulse * heat, 0.42F * pulse * heat, 0.07F * pulse * heat);
                if (step % 3 == 1) {
                    float ba = a + (step % 2 == 0 ? 0.8F : -0.8F);
                    lines.add(nx, 0.035F, nz, nx + Mth.cos(ba) * 0.55F, 0.035F, nz + Mth.sin(ba) * 0.55F, w * 0.6F,
                            0.9F * pulse * heat, 0.35F * pulse * heat, 0.05F * pulse * heat);
                }
                px = nx;
                pz = nz;
                r = nr;
                step++;
            }
        }
    }

    /** A thin beam with energy packets running along it, from a to b. */
    private static void beamTo(Lines lines, List<float[]> packets, float time, float ax, float ay, float az, float bx, float by,
                               float bz, float k, float r, float g, float b, int seed) {
        lines.add(ax, ay, az, bx, by, bz, 0.016F, r * k, g * k, b * k);
        lines.add(ax, ay, az, bx, by, bz, 0.05F, r * k * 0.22F, g * k * 0.22F, b * k * 0.22F);
        for (int m = 0; m < 2; m++) {
            float u = (time * 0.014F + seed * 0.23F + m * 0.5F) % 1.0F;
            packets.add(new float[]{ax + (bx - ax) * u, ay + (by - ay) * u, az + (bz - az) * u, k * Mth.sin(u * (float) Math.PI), r, g, b});
        }
    }

    /** The glow of an energy crystal: its own shape, drawn a little larger and blended over it. */
    private static void gemGlow(MultiBufferSource buffer, PoseStack.Pose id, float cx, float y, float cz, float r, float h, float rot, float cr,
                                float cg, float cb) {
        VertexConsumer vc = buffer.getBuffer(glow(TRIM));
        float[] top = {cx, y + h, cz};
        float[] bottom = {cx, y - h, cz};
        float[][] eq = new float[4][];
        for (int e = 0; e < 4; e++) {
            float ang = rot + e * (float) (Math.PI / 2.0);
            eq[e] = new float[]{cx + Mth.cos(ang) * r, y, cz + Mth.sin(ang) * r};
        }
        for (int e = 0; e < 4; e++) {
            float[] a = eq[e];
            float[] b = eq[(e + 1) % 4];
            float[][] up = {a, b, top};
            float[][] down = {b, a, bottom};
            for (float[][] t : new float[][][]{up, down}) {
                for (int v = 0; v < 4; v++) {
                    float[] q = t[Math.min(v, 2)];
                    glowVertex(vc, id, q[0], q[1], q[2], 0.5F, 0.5F, cr, cg, cb);
                }
            }
        }
    }

    private static final float RIFT_Y = 16.0F;

    private static float[] rp(Vector3f left, Vector3f up, float cx, float cy, float cz, float x, float y) {
        return new float[]{cx - left.x() * x + up.x() * y, cy - left.y() * x + up.y() * y, cz - left.z() * x + up.z() * y};
    }

    /**
     * Stage 4, Varkhul: a rift opens high above the plaza and three huge eyes with slit pupils look down (the Wither's three
     * heads, seen as eyes). They always face the player. Cracks run out of the middle one.
     */
    private static void drawRift(PoseStack.Pose p, MultiBufferSource buffer, Late d, Lines lines) {
        float g = ease(Mth.clamp(d.vs - 3.0F, 0.0F, 1.0F));
        if (g < 0.03F) {
            return;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        float time = d.time;
        float[][] eyes = {{0.0F, 0.0F, 1.0F}, {-5.2F, -1.8F, 0.55F}, {5.2F, -1.8F, 0.55F}};
        for (int e = 0; e < 3; e++) {
            float es = eyes[e][2];
            float cx = 0.5F - left.x() * eyes[e][0] + up.x() * eyes[e][1];
            float cy = RIFT_Y - left.y() * eyes[e][0] + up.y() * eyes[e][1];
            float cz = 0.5F - left.z() * eyes[e][0] + up.z() * eyes[e][1];
            float open = ease(Mth.clamp(g * 1.5F - e * 0.2F, 0.0F, 1.0F)) * (0.93F + 0.07F * Mth.sin(time * 0.04F + e));
            if (open < 0.03F) {
                continue;
            }
            float w = 2.6F * es;
            float h = 1.05F * es * open;
            float k = g;
            float[] pa = null;
            float[] pb = null;
            for (int m = 0; m <= 20; m++) {
                float x = -w + 2.0F * w * m / 20.0F;
                float t = x / w;
                float[] ua = rp(left, up, cx, cy, cz, x, h * (1.0F - t * t));
                float[] ub = rp(left, up, cx, cy, cz, x, -0.7F * h * (1.0F - t * t));
                if (pa != null) {
                    lines.add(pa[0], pa[1], pa[2], ua[0], ua[1], ua[2], 0.11F * es, 1.0F * k, 0.52F * k, 0.12F * k);
                    lines.add(pb[0], pb[1], pb[2], ub[0], ub[1], ub[2], 0.11F * es, 1.0F * k, 0.52F * k, 0.12F * k);
                }
                pa = ua;
                pb = ub;
            }
            float ir = 0.78F * h;
            float wander = 0.12F * es * Mth.sin(time * 0.02F + e * 2.0F);
            for (int ring = 0; ring < 2; ring++) {
                float rr = ir * (ring == 0 ? 1.0F : 0.8F);
                float[] prev = null;
                for (int m = 0; m <= 24; m++) {
                    float a = m * (float) (Math.PI * 2.0) / 24.0F;
                    float[] q = rp(left, up, cx, cy, cz, wander + Mth.cos(a) * rr, Mth.sin(a) * rr);
                    if (prev != null) {
                        lines.add(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.07F * es, (ring == 0 ? 1.0F : 0.9F) * k, (ring == 0 ? 0.7F : 0.38F) * k, (ring == 0 ? 0.2F : 0.08F) * k);
                    }
                    prev = q;
                }
            }
            for (int m = 0; m < 14; m++) {
                float a = m * (float) (Math.PI * 2.0) / 14.0F + 0.2F;
                if (Math.abs(Mth.cos(a)) > 0.8F) {
                    continue;
                }
                float[] q0 = rp(left, up, cx, cy, cz, wander + Mth.cos(a) * ir * 0.4F, Mth.sin(a) * ir * 0.4F);
                float[] q1 = rp(left, up, cx, cy, cz, wander + Mth.cos(a) * ir * 0.78F, Mth.sin(a) * ir * 0.78F);
                lines.add(q0[0], q0[1], q0[2], q1[0], q1[1], q1[2], 0.035F * es, 0.9F * k * 0.5F, 0.45F * k * 0.5F, 0.1F * k * 0.5F);
            }
            float pw = 0.14F * ir * (0.75F + 0.25F * Mth.sin(time * 0.03F + e));
            float[] sp = null;
            float[] sn = null;
            for (int m = 0; m <= 12; m++) {
                float y = -ir * 0.97F + 2.0F * ir * 0.97F * m / 12.0F;
                float t = y / (ir * 0.97F);
                float x = pw * (1.0F - t * t);
                float[] qa = rp(left, up, cx, cy, cz, wander + x, y);
                float[] qb = rp(left, up, cx, cy, cz, wander - x, y);
                if (sp != null) {
                    lines.add(sp[0], sp[1], sp[2], qa[0], qa[1], qa[2], 0.05F * es, 1.0F * k, 0.95F * k, 0.65F * k);
                    lines.add(sn[0], sn[1], sn[2], qb[0], qb[1], qb[2], 0.05F * es, 1.0F * k, 0.95F * k, 0.65F * k);
                }
                sp = qa;
                sn = qb;
            }
            if (e == 0) {
                for (int j = 0; j < 8; j++) {
                    float a = j * (float) (Math.PI / 4.0) + 0.3F;
                    float px = Mth.cos(a) * (w + 0.4F);
                    float py = Mth.sin(a) * (h + 0.7F);
                    for (int m = 0; m < 4; m++) {
                        float off = 0.28F * Mth.sin(j * 3.0F + m * 5.0F);
                        float nx = px + Mth.cos(a) * 0.95F + (-Mth.sin(a)) * off;
                        float ny = py + Mth.sin(a) * 0.95F + Mth.cos(a) * off;
                        float[] q0 = rp(left, up, cx, cy, cz, px, py);
                        float[] q1 = rp(left, up, cx, cy, cz, nx, ny);
                        float fade = (1.0F - m / 4.0F) * k;
                        lines.add(q0[0], q0[1], q0[2], q1[0], q1[1], q1[2], 0.05F * (1.0F - m / 5.0F), 0.6F * fade, 0.25F * fade, 0.95F * fade);
                        px = nx;
                        py = ny;
                    }
                }
            }
            billboard(buffer.getBuffer(glow(HALO)), p, cx, cy, cz, 6.0F * es, 0.0F, 0.2F * k, 0.08F * k, 0.32F * k);
            billboard(buffer.getBuffer(glow(HALO)), p, cx, cy, cz, 3.4F * es, 0.0F, 0.28F * k, 0.14F * k, 0.03F * k);
        }
    }

    /**
     * Everything that glows outside the Sarcophagus (blended, never added): resonance waves, the molten cracks, the beams and
     * the glow of the crystals, the shadow sentinels' eyes, the soul lanterns and ribbons, the eyes of ender, and the rift.
     */
    private static void drawEvolution(PoseStack pose, MultiBufferSource buffer, Late d) {
        float vs = d.vs;
        if (vs < 0.02F) {
            return;
        }
        float vp = d.vp;
        float time = d.time;
        PoseStack.Pose p = pose.last();
        Lines lines = new Lines();
        List<float[]> packets = new ArrayList<>();
        float coreY = ORB_Y * d.scale;
        float disc = CoreEvolution.discAt(vp);
        float wv = ease(Mth.clamp(vp - 1.0F, 0.0F, 1.0F));
        if (wv > 0.02F) {
            for (int k = 0; k < 3; k++) {
                float f = (time * 0.009F + k / 3.0F) % 1.0F;
                float r = 0.7F + f * (disc - 0.7F);
                float fin = Mth.clamp(f / 0.12F, 0.0F, 1.0F);
                float a = fin * fin * (3.0F - 2.0F * fin) * (1.0F - f) * (1.0F - f) * wv * 0.7F;
                circle(lines, 0.5F, 0.04F, 0.5F, r, 40, 0.03F, 1.0F * a, 0.55F * a, 0.14F * a);
            }
            drawCracks(lines, time, vp, disc);
        }
        float wB = toSoul(vp) * (1.0F - toRift(vp));
        float wC = toRift(vp);
        float wA = 1.0F - toSoul(vp);
        float height = CoreEvolution.pillarAt(vp);
        float[][] gems = new float[4][];
        float[] col = crystalColor(vp);
        if (height > 0.3F) {
            float vr = CoreEvolution.pillarRadiusAt(vp);
            float yc = crystalCenter(vp);
            float pf = ease(Mth.clamp(height / 3.0F, 0.0F, 1.0F));
            float top = height - 1.7F;
            for (int i = 0; i < 4; i++) {
                float cx = 0.5F + (i % 2 == 0 ? -vr : vr);
                float cz = 0.5F + (i < 2 ? -vr : vr);
                float y = yc + 0.07F * Mth.sin(time * 0.08F + i * 1.7F);
                gems[i] = new float[]{cx, y, cz};
                float u = (time * 0.018F + i * 0.25F) % 1.0F;
                float fade = (1.0F - u) * (1.0F - u) * Mth.clamp(u / 0.15F, 0.0F, 1.0F) * pf * 0.8F;
                circle(lines, cx, 0.03F, cz, 0.45F + u * 1.5F, 16, 0.025F, col[0] * fade, col[1] * fade, col[2] * fade);
                float eA = pf * wA;
                float eB = pf * wB * wyrmK(vs);
                float eC = pf * wC * ease(Mth.clamp(d.vs - 3.0F, 0.0F, 1.0F));
                if (eA > 0.03F) {
                    beamTo(lines, packets, time, cx, y, cz, 0.5F, coreY, 0.5F, eA, 1.0F, 0.5F, 0.15F, i);
                    // spark rain from the hammer head
                    float ox = cx - 0.5F;
                    float oz = cz - 0.5F;
                    float ol = Mth.sqrt(ox * ox + oz * oz);
                    for (int sk = 0; sk < 4; sk++) {
                        float su = (time * 0.035F + i * 0.31F + sk * 0.25F) % 1.0F;
                        float fy = top + 0.8F;
                        float x0 = cx + ox / ol * 0.9F * su + (sk - 1.5F) * 0.16F * su;
                        float z0 = cz + oz / ol * 0.9F * su - (sk - 1.5F) * 0.12F * su;
                        float y0 = Math.max(0.05F, fy - su * su * (fy - 0.05F));
                        float su2 = Math.max(0.0F, su - 0.05F);
                        float y1 = Math.max(0.05F, fy - su2 * su2 * (fy - 0.05F));
                        float fk = (1.0F - 0.6F * su) * eA;
                        lines.add(x0, y0, z0, x0 - ox / ol * 0.05F, y1, z0 - oz / ol * 0.05F, 0.013F, 1.0F * fk, 0.62F * fk, 0.2F * fk);
                    }
                }
                if (eB > 0.03F) {
                    float[] wy = wyrmAt(2.0F + i * 2.2F, time, vs);
                    beamTo(lines, packets, time, cx, y, cz, wy[0], wy[1], wy[2], eB, 0.75F, 0.4F, 1.0F, i);
                }
                if (eC > 0.03F) {
                    beamTo(lines, packets, time, cx, y, cz, 0.5F, RIFT_Y, 0.5F, eC, 1.0F, 0.62F, 0.2F, i);
                }
            }
        }
        // the shadow sentinels (stage 1): amber eyes, a drifting mist at their feet
        float gs = ease(Mth.clamp((vs - 0.1F) / 0.9F, 0.0F, 1.0F));
        if (gs > 0.05F) {
            for (int i = 0; i < 4; i++) {
                float[] an = anchorAt(i, d.scale, vs);
                float yaw = (float) Math.atan2(0.5F - an[0], 0.5F - an[1]);
                float sy = Mth.sin(yaw);
                float cyw = Mth.cos(yaw);
                float ey = 1.90F - 2.7F * (1.0F - gs);
                float blink = 0.8F + 0.2F * Mth.sin(time * 0.07F + i * 2.0F);
                for (int sd = -1; sd <= 1; sd += 2) {
                    float lx = 0.055F * sd;
                    float lz = 0.225F;
                    float wx = an[0] + lx * cyw + lz * sy;
                    float wz = an[1] - lx * sy + lz * cyw;
                    billboard(buffer.getBuffer(glow(HALO)), p, wx, ey, wz, 0.085F, 0.0F, 1.0F * blink * gs, 0.5F * blink * gs, 0.12F * blink * gs);
                }
                for (int m = 0; m < 2; m++) {
                    float mx = an[0] + Mth.sin(time * 0.013F + i * 2.0F + m * 3.0F) * 0.55F;
                    float mz = an[1] + Mth.cos(time * 0.011F + i * 1.5F + m * 2.0F) * 0.55F;
                    billboard(buffer.getBuffer(glow(HALO)), p, mx, 0.35F + 0.1F * m, mz, 0.8F, 0.0F, 0.26F * gs, 0.15F * gs, 0.34F * gs);
                }
            }
        }
        // soul lanterns and the ribbons that drain the Core into them (stage 3)
        float gl = ease(Mth.clamp(vs - 2.0F, 0.0F, 1.0F));
        if (gl > 0.03F) {
            for (int i = 0; i < LANTERNS; i++) {
                float[] l = lanternAt(i, time, vs);
                float[] prev = {0.5F, coreY, 0.5F};
                for (int sg = 1; sg <= 10; sg++) {
                    float u = sg / 10.0F;
                    float[] q = ribbonPoint(l, coreY, time, i, u);
                    float kk = (0.25F + 0.55F * u) * l[3];
                    lines.add(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.012F + 0.008F * u, 0.3F * kk, 0.9F * kk, 1.0F * kk);
                    prev = q;
                }
                float u = (time * 0.012F + i * 0.2F) % 1.0F;
                float[] pk = ribbonPoint(l, coreY, time, i, u);
                packets.add(new float[]{pk[0], pk[1], pk[2], Mth.sin(u * (float) Math.PI) * l[3], 0.4F, 0.95F, 1.0F});
            }
        }
        // the rift of stage 4
        drawRift(p, buffer, d, lines);
        lines.flush(buffer, p);
        for (float[] pk : packets) {
            billboard(buffer.getBuffer(glow(HALO)), p, pk[0], pk[1], pk[2], 0.2F, 0.0F, pk[4] * pk[3], pk[5] * pk[3], pk[6] * pk[3]);
        }
        for (int i = 0; gl > 0.03F && i < LANTERNS; i++) {
            float[] l = lanternAt(i, time, vs);
            float k = l[3] * (0.85F + 0.15F * Mth.sin(time * 0.12F + i));
            pose.pushPose();
            pose.translate(l[0], l[1], l[2]);
            sphere(buffer.getBuffer(glow(ORB)), pose.last(), 0.12F * l[3], time * 0.004F, -time * 0.01F, 0.45F * k, 1.0F * k, 1.0F * k);
            pose.popPose();
            billboard(buffer.getBuffer(glow(HALO)), p, l[0], l[1], l[2], 0.55F * l[3], 0.0F, 0.2F * k, 0.55F * k, 0.65F * k);
        }
        // the crystals: they glow in their own shape, with a soft halo, and (stage 3) an eye of ender floats over each
        for (int i = 0; i < 4; i++) {
            if (gems[i] == null) {
                continue;
            }
            float pf = ease(Mth.clamp(height / 3.0F, 0.0F, 1.0F));
            float pulse = (0.8F + 0.2F * Mth.sin(time * 0.15F + i)) * pf;
            float rot = time * 0.045F + i * 0.8F;
            gemGlow(buffer, p, gems[i][0], gems[i][1], gems[i][2], crystalR(vp) * 1.12F, crystalH(vp) * 1.08F, rot,
                    col[0] * 0.5F * pulse, col[1] * 0.5F * pulse, col[2] * 0.5F * pulse);
            billboard(buffer.getBuffer(glow(HALO)), p, gems[i][0], gems[i][1], gems[i][2], 1.7F, time * 0.02F,
                    col[0] * 0.42F * pulse, col[1] * 0.42F * pulse, col[2] * 0.42F * pulse);
            float ek = wB * pf;
            if (ek > 0.05F) {
                float ex = gems[i][0];
                float ez = gems[i][2];
                float ey = gems[i][1] + crystalH(vp) + 0.95F + 0.1F * Mth.sin(time * 0.06F + i);
                float tx = 0.5F - ex;
                float tz = 0.5F - ez;
                float tl = Mth.sqrt(tx * tx + tz * tz);
                pose.pushPose();
                pose.translate(ex, ey, ez);
                sphere(buffer.getBuffer(glow(ORB)), pose.last(), 0.27F, time * 0.003F, 0.0F, 0.15F * ek, 0.85F * ek, 0.55F * ek);
                pose.popPose();
                pose.pushPose();
                pose.translate(ex + tx / tl * 0.2F, ey, ez + tz / tl * 0.2F);
                sphere(buffer.getBuffer(glow(TRIM)), pose.last(), 0.09F, 0.0F, 0.0F, 0.9F * ek, 1.0F * ek, 0.9F * ek);
                pose.popPose();
                billboard(buffer.getBuffer(glow(HALO)), p, ex, ey, ez, 0.8F, 0.0F, 0.1F * ek, 0.55F * ek, 0.38F * ek);
            }
        }
        // the wyrm's eyes and the glow of its crystal spines
        float wk = wyrmK(vs);
        if (wk > 0.05F) {
            float[] head = wyrmAt(0.0F, time, vs);
            billboard(buffer.getBuffer(glow(HALO)), p, head[0], head[1] + 0.1F, head[2], 0.5F * wk, 0.0F, 0.9F * wk, 0.35F * wk, 0.9F * wk);
            for (int sgm = 2; sgm < 14; sgm += 3) {
                float[] cur = wyrmAt(sgm * 0.62F, time, vs);
                billboard(buffer.getBuffer(glow(HALO)), p, cur[0], cur[1] + 0.2F, cur[2], 0.45F * wk, 0.0F, 0.4F * wk, 0.15F * wk, 0.55F * wk);
            }
        }
    }

    /** The block as an item: shut, empty. */
    static void renderItem(PoseStack pose, MultiBufferSource buffer, int light) {
        VertexConsumer solid = buffer.getBuffer(Sheets.solidBlockSheet());
        drawLower(pose.last(), solid, light, false);
        drawLowerFrame(pose.last(), solid, light);
        drawSlab(pose.last(), solid, light);
        drawUpperFrame(pose, solid, light, 0.0F);
        flushSolid(buffer);
        drawGlass(pose.last(), buffer, light, 0.0F);
    }

    // ------------------------------------------------------------------
    // Solid parts
    // ------------------------------------------------------------------

    private static void drawLower(PoseStack.Pose pose, VertexConsumer vc, int light, boolean awake) {
        TextureAtlasSprite wall = sprite(awake ? WALL_AWAKE : WALL);
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
    private static void drawUpperFrame(PoseStack ps, VertexConsumer vc, int light, float windowDeg) {
        PoseStack.Pose pose = ps.last();
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
        glassFrames(ps, vc, bright, gold, dark, windowDeg);
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
    private static void glassFrames(PoseStack ps, VertexConsumer vc, int light, TextureAtlasSprite gold,
                                    TextureAtlasSprite dark, float windowDeg) {
        float[][] layers = {{0.0F, 0.010F, 0}, {0.010F, 0.040F, 1}, {0.040F, 0.050F, 0}};
        float top = 1.0F - SLAB;
        float out = OUT * 0.5F;
        // where the inner edge of the frame meets the top edge, and how much the edge moves per unit of height
        float slant = 0.5F / (TIP - 0.5F);
        float x0 = 0.050F * Mth.sqrt(1.0F + slant * slant);
        for (int wall = 0; wall < 4; wall++) {
            boolean alongX = wall < 2;
            float at = wall % 2;
            ps.pushPose();
            if (windowDeg > 0.5F) {
                hinge(ps, wall, windowDeg);
            }
            PoseStack.Pose pose = ps.last();
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
            ps.popPose();
        }
    }

    /** Turns the frame of a window about its hinge (the top edge of the glass) together with the glass. */
    private static void hinge(PoseStack ps, int wall, float deg) {
        float g = 1.0F - SLAB;
        switch (wall) {
            case 0 -> {
                ps.translate(0.0F, g, 0.0F);
                ps.mulPose(Axis.XP.rotationDegrees(deg));
                ps.translate(0.0F, -g, 0.0F);
            }
            case 1 -> {
                ps.translate(0.0F, g, 1.0F);
                ps.mulPose(Axis.XP.rotationDegrees(-deg));
                ps.translate(0.0F, -g, -1.0F);
            }
            case 2 -> {
                ps.translate(0.0F, g, 0.0F);
                ps.mulPose(Axis.ZP.rotationDegrees(-deg));
                ps.translate(0.0F, -g, 0.0F);
            }
            default -> {
                ps.translate(1.0F, g, 0.0F);
                ps.mulPose(Axis.ZP.rotationDegrees(deg));
                ps.translate(-1.0F, -g, 0.0F);
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

    private static void drawGlass(PoseStack.Pose pose, MultiBufferSource buffer, int light, float windowDeg) {
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(GLASS_TEXTURE));
        int bright = LightTexture.pack(Math.max(LightTexture.block(light), 10), LightTexture.sky(light));
        // The glass stops under the slab (its top edge is where the slab ends), so it does not cover the slab's side.
        float g = 1.0F - SLAB;
        float a = 0.0F;
        float b = 1.0F;
        if (windowDeg < 0.5F) {
            glassTri(vc, pose, bright, a, g, 0, b, g, 0, N, 0, 0, -1);
            glassTri(vc, pose, bright, a, g, 1, b, g, 1, S, 0, 0, 1);
            glassTri(vc, pose, bright, 0, g, a, 0, g, b, W, -1, 0, 0);
            glassTri(vc, pose, bright, 1, g, a, 1, g, b, E, 1, 0, 0);
            return;
        }
        // The windows hinge open at the slab, the bottom tip swings outwards.
        float th = (float) Math.toRadians(windowDeg);
        float cs = Mth.cos(th);
        float sn = Mth.sin(th);
        glassTri(vc, pose, bright, a, g, 0, b, g, 0, petal(N, g, 0, 0, -1, cs, sn), 0, sn, -cs);
        glassTri(vc, pose, bright, a, g, 1, b, g, 1, petal(S, g, 0, 0, 1, cs, sn), 0, sn, cs);
        glassTri(vc, pose, bright, 0, g, a, 0, g, b, petal(W, g, -1, 0, 0, cs, sn), -cs, sn, 0);
        glassTri(vc, pose, bright, 1, g, a, 1, g, b, petal(E, g, 1, 0, 0, cs, sn), cs, sn, 0);
    }

    /** The tip of a window turned out about the hinge: normal (nx, 0, nz) points out of the block. */
    private static float[] petal(float[] tip, float hingeY, float nx, float ny, float nz, float cs, float sn) {
        float len = hingeY - tip[1];
        return new float[]{tip[0] + nx * len * sn, hingeY - len * cs, tip[2] + nz * len * sn};
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
        VertexConsumer glow = buffer.getBuffer(glow(HALO));
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
        VertexConsumer vc = buffer.getBuffer(glow(TRIM));
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
        VertexConsumer vc = buffer.getBuffer(glow(TRIM));
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
        VertexConsumer vc = buffer.getBuffer(glow(TRIM));
        float k = scale * (0.85F + 0.15F * Mth.sin(time * 0.19F));
        PoseStack.Pose p = pose.last();
        for (float[][] tri : FLOOR) {
            float[] a = {tri[0][0], tri[0][1] + 0.003F, tri[0][2]};
            float[] b = {tri[1][0], tri[1][1] + 0.003F, tri[1][2]};
            float[] c = {tri[2][0], tri[2][1] + 0.003F, tri[2][2]};
            glowTri(vc, p, a, b, c, 0.12F * k, 0.08F * k, 0.18F * k, true);
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
            glowTri(vc, q, corners[i], corners[(i + 1) % 4], m, 0.02F * k, 0.02F * k, 0.16F * k, false);
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
            glowVertex(vc, pose, pts[j][0], pts[j][1], pts[j][2], 0.5F, 0.5F, k, k * 0.5F, k * 0.16F);
        }
    }

    private static void drawOrb(PoseStack pose, MultiBufferSource buffer, float time, float scale, float flash) {
        float pulse = 0.93F + 0.07F * Mth.sin(time * 0.19F);
        float cy = ORB_Y + 0.02F * Mth.sin(time * 0.08F);
        float flick = 0.95F + 0.05F * Mth.sin(time * 0.43F) * Mth.sin(time * 0.27F + 2.0F);
        // A body of fire that is solid (blended): it stays visible against a bright sky and cannot burn out to white.
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 1.7F));
        sphere(buffer.getBuffer(fxFire()), pose.last(), ORB_RADIUS * scale * flick, time * 0.006F, -time * 0.012F, 1.0F * pulse, 0.8F * pulse, 0.46F * pulse);
        pose.popPose();
        // A shell of flames that turns the other way, and a faint one further out.
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-time * 2.6F));
        pose.mulPose(Axis.ZP.rotationDegrees(25.0F));
        sphere(buffer.getBuffer(glow(ORB)), pose.last(), ORB_RADIUS * 1.22F * scale * flick, 0.37F - time * 0.008F, -time * 0.02F, 1.0F * pulse, 0.74F * pulse, 0.42F * pulse);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 1.1F));
        pose.mulPose(Axis.XP.rotationDegrees(-30.0F));
        sphere(buffer.getBuffer(glow(ORB)), pose.last(), ORB_RADIUS * 1.55F * scale * flick, 0.71F + time * 0.004F, -time * 0.03F, 0.55F * pulse, 0.4F * pulse, 0.22F * pulse);
        pose.popPose();
        // The one hot spot: a small bright heart.
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        sphere(buffer.getBuffer(glow(TRIM)), pose.last(), ORB_RADIUS * 0.42F * scale, 0.0F, 0.0F, 1.0F, 0.9F, 0.62F);
        pose.popPose();
        // The Core breathes: a soft shell of fire swells out of it and fades, twice per breath.
        for (int b = 0; b < 2; b++) {
            float ph = (time * 0.013F + b * 0.5F) % 1.0F;
            float a = (1.0F - ph) * (1.0F - ph) * Mth.clamp(ph / 0.12F, 0.0F, 1.0F) * 0.55F * scale;
            if (a > 0.02F) {
                pose.pushPose();
                pose.translate(0.5F, cy, 0.5F);
                pose.mulPose(Axis.YP.rotationDegrees(time * (b == 0 ? 1.3F : -0.9F)));
                sphere(buffer.getBuffer(glow(ORB)), pose.last(), ORB_RADIUS * scale * (1.4F + 2.2F * ph), 0.2F * b + time * 0.003F, -time * 0.01F, 1.0F * a, 0.62F * a, 0.3F * a);
                pose.popPose();
            }
        }
        // Light around it.
        PoseStack.Pose p = pose.last();
        float s = scale;
        billboard(buffer.getBuffer(glow(HALO)), p, 0.5F, cy, 0.5F, 0.85F * s, 0.0F, 0.62F * pulse * s, 0.3F * pulse * s, 0.06F * s);
        if (flash > 0.0F) {
            billboard(buffer.getBuffer(glow(HALO)), p, 0.5F, cy, 0.5F, (0.8F + 1.2F * flash) * s, 0.0F, 0.8F * flash, 0.45F * flash, 0.12F * flash);
        }
        // Embers rise out of the fire and go out.
        for (int e = 0; e < 7; e++) {
            float u = (time * 0.011F + e * 0.143F + (e % 3) * 0.05F) % 1.0F;
            float ang = e * 2.4F + u * 2.2F;
            float rr = ORB_RADIUS * 1.15F * s * (1.0F - 0.35F * u);
            float a = Mth.sin(u * (float) Math.PI) * s;
            billboard(buffer.getBuffer(glow(HALO)), p, 0.5F + Mth.cos(ang) * rr, cy + 0.1F + u * 0.95F * s, 0.5F + Mth.sin(ang) * rr,
                    0.032F * s, 0.0F, 1.0F * a, 0.66F * a, 0.24F * a);
        }
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
                    glowVertex(vc, pose, pts[idx][0], pts[idx][1], pts[idx][2], uvs[idx][0], uvs[idx][1], r, g, b);
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
        glowVertex(vc, pose, cx - ax - bx, cy - ay - by, cz - az - bz, 0, 0, r, g, b);
        glowVertex(vc, pose, cx + ax - bx, cy + ay - by, cz + az - bz, 0, 1, r, g, b);
        glowVertex(vc, pose, cx + ax + bx, cy + ay + by, cz + az + bz, 1, 1, r, g, b);
        glowVertex(vc, pose, cx - ax + bx, cy - ay + by, cz - az + bz, 1, 0, r, g, b);
        glowVertex(vc, pose, cx - ax + bx, cy - ay + by, cz - az + bz, 1, 0, r, g, b);
        glowVertex(vc, pose, cx + ax + bx, cy + ay + by, cz + az + bz, 1, 1, r, g, b);
        glowVertex(vc, pose, cx + ax - bx, cy + ay - by, cz + az - bz, 0, 1, r, g, b);
        glowVertex(vc, pose, cx - ax - bx, cy - ay - by, cz - az - bz, 0, 0, r, g, b);
    }

    private static void glowVertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v,
                                   float r, float g, float b) {
        int a = 255;
        int ir;
        int ig;
        int ib;
        if (alphaMode) {
            // brightness becomes opacity: the same picture when added, but a solid colour when blended (see fx()).
            // addK dims what is added (by day it would burn out against the sky); the hue is worked out in float so
            // that a fading effect keeps its colour to the end.
            float m = Math.max(r, Math.max(g, b)) * addK;
            if (m <= 0.002F) {
                ir = 255;
                ig = 140;
                ib = 30;
                a = 0;
            } else {
                float mx = Math.max(r, Math.max(g, b));
                ir = Mth.clamp((int) (r / mx * 255.0F + 0.5F), 0, 255);
                ig = Mth.clamp((int) (g / mx * 255.0F + 0.5F), 0, 255);
                ib = Mth.clamp((int) (b / mx * 255.0F + 0.5F), 0, 255);
                a = Mth.clamp((int) (m * 255.0F + 0.5F), 0, 255);
            }
        } else {
            ir = Mth.clamp((int) (r * 255.0F), 0, 255);
            ig = Mth.clamp((int) (g * 255.0F), 0, 255);
            ib = Mth.clamp((int) (b * 255.0F), 0, 255);
        }
        vc.addVertex(pose, x, y, z).setColor(ir, ig, ib, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    /** How much of its brightness an added effect keeps (1 at night, less by day: the sky is bright already). */
    private static float addK = 1.0F;

    /** True while the late pass draws: colours are then sent as colour plus opacity (see {@link #glowVertex}). */
    private static boolean alphaMode;

    /**
     * A glow layer. While the late pass draws it is blended (colour with opacity from the soft textures with an alpha channel),
     * never added: added light on a bright sky burns out to white, blended light keeps its colour.
     */
    private static RenderType glow(ResourceLocation texture) {
        if (!alphaMode) {
            return RenderType.eyes(texture);
        }
        ResourceLocation t = texture == HALO ? HALO_A : texture == FLARE ? FLARE_A : texture == RING ? RING_A
                : texture == ORB ? ORB_A : texture;
        return RenderType.entityTranslucentEmissive(t);
    }

    private static RenderType vortexType() {
        return RenderType.entityTranslucentEmissive(VORTEX);
    }

    /** Blended (not added) white: effects that must stay visible against a bright sky. */
    private static RenderType fx() {
        return RenderType.entityTranslucentEmissive(TRIM);
    }

    /** Blended fire texture: the solid body of the Core. */
    private static RenderType fxFire() {
        return RenderType.entityTranslucentEmissive(ORB);
    }
}
