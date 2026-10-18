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
        float sc = CoreEvolution.scaleAt(vs);
        // From stage 2 on the glass windows swing open (slowly, and they breathe a little afterwards).
        float windowTarget = active ? 62.0F * Mth.clamp(vs - 1.0F, 0.0F, 1.0F) : 0.0F;
        state.window += (windowTarget - state.window) * Math.min(1.0F, 0.035F * dt);
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
        drawUpperFrame(pose.last(), solid, light);
        if (vs > 0.02F) {
            drawLidTiers(pose, solid, light, vs);
        }
        pose.popPose();
        flushSolid(buffer);
        pose.popPose();
        if (vs > 0.02F) {
            VertexConsumer fresh = buffer.getBuffer(Sheets.solidBlockSheet()); // a fresh consumer: the old one was flushed
            drawChains(pose, fresh, time, sc, vs);
            drawPillars(pose, fresh, time, vs);
            drawArches(pose, fresh, time, vs);
            drawTablets(pose, fresh, time, sc, vs);
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
        late.window = state.window > 1.0F ? state.window + 2.5F * Mth.sin(time * 0.05F) : state.window;
        late.vs = active ? vs : 0.0F;
        late.vr = CoreEvolution.pillarRadiusAt(vs);
        late.vh = active ? CoreEvolution.pillarAt(vs) : 0.0F;
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
        addK = dayDim();
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

    /** 1 at night, about a quarter by day: added light on a bright sky burns out to white. */
    private static float dayDim() {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return 1.0F;
        }
        float sun = Mth.cos(level.getTimeOfDay(1.0F) * (float) (Math.PI * 2.0));
        float day = Mth.clamp(sun * 1.6F + 0.45F, 0.0F, 1.0F);
        return Mth.lerp(day, 1.0F, 0.26F);
    }

    private static void drawLate(Late d, PoseStack pose, MultiBufferSource buffer) {
        float t = d.t;
        float time = d.time;
        drawBeams(pose, buffer, d);
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
            drawRays(pose, buffer, time, d.orb, d.lift, d.twist);
            drawCorona(pose, buffer, d);
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
            source.endBatch(RenderType.eyes(TRIM));
            source.endBatch(RenderType.eyes(ORB));
            source.endBatch(RenderType.eyes(HALO));
            source.endBatch(RenderType.eyes(FLARE));
            source.endBatch(RenderType.eyes(RING));
        }
    }

    // ------------------------------------------------------------------
    // The evolution: a feature of its own for every stage
    // ------------------------------------------------------------------

    // --- the pillars: structures of their own (the blocks under them are invisible placeholders) ---

    /** Size factor of the pillars: they get stouter with every stage. */
    private static float pillarSize(float vs) {
        return 0.95F + 0.1F * Mth.clamp(vs - 1.0F, 0.0F, 3.0F);
    }

    /** Height of the middle of the crystal above the ground, for a pillar of the given height. */
    private static float crystalY(float height) {
        return height - 0.75F;
    }

    /** From stage 4 on the crystals leave their prongs and float above them. */
    private static float crystalLift(float vs) {
        return 0.9F * ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F));
    }

    private static float crystalScale(float vs) {
        return 0.9F + 0.06F * Mth.clamp(vs - 1.0F, 0.0F, 3.0F);
    }

    /** The crystals get slimmer and longer with every stage. */
    private static float crystalH(float vs) {
        return (0.95F + 0.3F * Mth.clamp(vs - 2.0F, 0.0F, 2.0F)) * crystalScale(vs);
    }

    private static float crystalR(float vs) {
        return (0.5F - 0.05F * Mth.clamp(vs - 2.0F, 0.0F, 2.0F)) * crystalScale(vs);
    }

    // --- stage 3: arches between the pillars with a hanging lantern each ---

    private static final int[][] ARCHES = {{0, 1}, {1, 3}, {3, 2}, {2, 0}};

    /** Spring height, rise, pillar radius and size of the arches at the animated stage. */
    private static float[] archBase(float vs) {
        float vr = CoreEvolution.pillarRadiusAt(vs);
        return new float[]{CoreEvolution.pillarAt(vs) - 1.7F - 0.55F, 0.22F * 2.0F * vr, vr, pillarSize(vs)};
    }

    /** A point on the arch from pillar a to pillar b (u from 0 to 1). */
    private static float[] archPoint(int a, int b, float[] base, float u) {
        float vr = base[2];
        float ax = 0.5F + (a % 2 == 0 ? -vr : vr);
        float az = 0.5F + (a < 2 ? -vr : vr);
        float bx = 0.5F + (b % 2 == 0 ? -vr : vr);
        float bz = 0.5F + (b < 2 ? -vr : vr);
        float inset = 0.27F * base[3] / (2.0F * vr);
        float u2 = inset + (1.0F - 2.0F * inset) * u;
        float h = base[1] * (float) Math.pow(Math.sin(Math.PI * u), 0.8);
        return new float[]{ax + (bx - ax) * u2, base[0] + h, az + (bz - az) * u2};
    }

    /** Position of the lantern under the top of arch n and how far it has grown (x, y, z, k). */
    private static float[] lanternAt(int n, float[] base, float vs, float time) {
        float k = ease(Mth.clamp(vs - 2.7F, 0.0F, 1.0F));
        float[] top = archPoint(ARCHES[n][0], ARCHES[n][1], base, 0.5F);
        return new float[]{top[0], top[1] - 0.15F - 0.75F * k + 0.05F * Mth.sin(time * 0.07F + n * 1.6F), top[2], k};
    }

    /**
     * From stage 3 on gold-and-obsidian arches grow out of the pillars until they meet over the middle of each side; a
     * lantern of fire hangs under every top. They follow the pillars when these move out and grow in the next stage.
     */
    private static void drawArches(PoseStack pose, VertexConsumer vc, float time, float vs) {
        float g3 = ease(Mth.clamp(vs - 2.0F, 0.0F, 1.0F));
        if (g3 < 0.03F || CoreEvolution.pillarAt(vs) < 3.0F) {
            return;
        }
        float[] base = archBase(vs);
        PoseStack.Pose p = pose.last();
        TextureAtlasSprite wall = sprite(WALL_AWAKE);
        TextureAtlasSprite gold = sprite(GOLD);
        TextureAtlasSprite crystal = sprite(block("core_crystal"));
        int light = LightTexture.pack(13, 15);
        final int steps = 9;
        float gh = 0.5F * g3;
        float dis = ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F));
        float aw = 0.11F * (1.0F - dis);
        for (int n = 0; n < 4; n++) {
            int a = ARCHES[n][0];
            int b = ARCHES[n][1];
            for (int half = 0; half < 2; half++) {
                float[] prev = null;
                for (int j = 0; j <= steps; j++) {
                    float u = gh * j / steps;
                    float[] q = archPoint(a, b, base, half == 0 ? u : 1.0F - u);
                    if (prev != null && aw > 0.012F) {
                        beam(vc, p, light, wall, prev[0], prev[1], prev[2], q[0], q[1], q[2], aw);
                        beam(vc, p, light, gold, prev[0], prev[1] + 0.12F * (1.0F - dis), prev[2], q[0], q[1] + 0.12F * (1.0F - dis), q[2], 0.032F * (1.0F - dis));
                    }
                    if (j % 3 == 0 && dis < 0.9F) {
                        float bs = 0.085F * (1.0F - dis);
                        box(vc, p, light, gold, 4, q[0] - bs, q[0] + bs, q[1] - bs, q[1] + bs, q[2] - bs, q[2] + bs);
                    }
                    prev = q;
                }
            }
            float[] ln = lanternAt(n, base, vs, time);
            if (ln[3] > 0.03F) {
                float[] top = archPoint(a, b, base, 0.5F);
                box(vc, p, light, gold, 4, top[0] - 0.12F, top[0] + 0.12F, top[1] - 0.06F, top[1] + 0.16F, top[2] - 0.12F, top[2] + 0.12F);
                beam(vc, p, light, gold, top[0], top[1] - 0.06F, top[2], ln[0], ln[1] + 0.18F * ln[3], ln[2], 0.018F);
                crystal(vc, p, crystal, ln[0], ln[1], ln[2], 0.13F * ln[3], 0.26F * ln[3], time * 0.07F + n);
            }
        }
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
     * The four pillars (solid): a stepped base, a shaft of obsidian with gold corner posts and bands, a capital whose
     * prongs hold the red energy crystal, two gold rings that turn round the crystal and, from stage 3 on, shards that
     * circle it. Radius and height follow the animated stage, so a pillar grows and moves outwards on its own.
     */
    private static void drawPillars(PoseStack pose, VertexConsumer vc, float time, float vs) {
        float height = CoreEvolution.pillarAt(vs);
        if (height < 0.3F) {
            return;
        }
        float vr = CoreEvolution.pillarRadiusAt(vs);
        float s = pillarSize(vs);
        float grade = Mth.clamp(vs - 1.0F, 0.0F, 3.0F);
        PoseStack.Pose p = pose.last();
        TextureAtlasSprite wall = sprite(vs >= 2.5F ? WALL_AWAKE : WALL);
        TextureAtlasSprite marble = sprite(MARBLE);
        TextureAtlasSprite gold = sprite(GOLD);
        TextureAtlasSprite crystal = sprite(block("core_crystal"));
        int light = LightTexture.pack(13, 15);
        float top = height - 1.7F; // top of the stone part
        float yc = crystalY(height) + crystalLift(vs);
        float cs = crystalScale(vs);
        for (int i = 0; i < 4; i++) {
            float cx = 0.5F + (i % 2 == 0 ? -vr : vr);
            float cz = 0.5F + (i < 2 ? -vr : vr);
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
                // stage 4: the stone of the shaft melts away, the energy inside it is held by gold rings that float and turn
                float dis = ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F));
                float hw = 0.27F * s * (1.0F - dis);
                if (hw > 0.03F) {
                    fbox(vc, p, light, marble, wall, cx - hw, cx + hw, shaftBottom, shaftTop, cz - hw, cz + hw);
                    float pw = 0.045F;
                    if (dis < 0.5F) {
                        for (int c = 0; c < 4; c++) {
                            float px = cx + (c % 2 == 0 ? -hw : hw);
                            float pz = cz + (c < 2 ? -hw : hw);
                            box(vc, p, light, gold, 4, px - pw, px + pw, shaftBottom, shaftTop, pz - pw, pz + pw);
                        }
                    }
                }
                int bands = Math.max(1, Math.round((shaftTop - shaftBottom) / 1.2F));
                float stride = (shaftTop - shaftBottom) / bands;
                for (int b = 0; b < bands; b++) {
                    float y = shaftBottom + stride * (b + 0.5F);
                    float bw = 0.27F * s;
                    if (dis < 0.12F) {
                        box(vc, p, light, gold, 4, cx - bw - 0.05F, cx + bw + 0.05F, y - 0.05F, y + 0.05F, cz - bw - 0.05F, cz + bw + 0.05F);
                    }
                    if (dis > 0.04F) {
                        float yy = y + 0.1F * dis * Mth.sin(time * 0.05F + b + i);
                        ring(vc, p, light, gold, cx, yy, cz, (0.36F + 0.12F * dis) * s, 0.0F, time * 0.03F * (b % 2 == 0 ? 1.0F : -1.0F) + b, 8, 0.035F);
                    }
                }
                if (dis > 0.04F) {
                    for (int b = 1; b < bands; b++) {
                        float yy = shaftBottom + stride * b + 0.08F * dis * Mth.sin(time * 0.06F + b * 2.0F + i);
                        ring(vc, p, light, gold, cx, yy, cz, 0.3F * s * dis, 0.0F, -time * 0.04F * (b % 2 == 0 ? 1.0F : -1.0F) + b, 8, 0.022F);
                    }
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
            // four gold prongs hold the crystal
            for (int c = 0; c < 4; c++) {
                float sx = c % 2 == 0 ? -1.0F : 1.0F;
                float sz = c < 2 ? -1.0F : 1.0F;
                beam(vc, p, light, gold, cx + sx * 0.30F * s, top, cz + sz * 0.30F * s, cx + sx * 0.17F, top + 0.52F, cz + sz * 0.17F, 0.034F);
            }
            // the crystal, two rings and (stage 3 on) shards round it
            float rot = time * 0.045F + i * 0.8F;
            float y = yc + 0.07F * Mth.sin(time * 0.08F + i * 1.7F);
            crystal(vc, p, crystal, cx, y, cz, crystalR(vs), crystalH(vs), rot);
            float shardGrow = ease(Mth.clamp(grade - 2.0F, 0.0F, 1.0F));
            ring(vc, p, light, gold, cx, y, cz, 0.85F * cs, 0.0F, time * 0.03F + i, 10, 0.016F);
            if (shardGrow < 0.95F) {
                // the second ring draws in as the shards take its place
                ring(vc, p, light, gold, cx, y, cz, 1.0F * cs * (1.0F - shardGrow), 1.08F, -time * 0.025F + i, 10, 0.014F);
            }
            if (shardGrow > 0.05F) {
                for (int q = 0; q < 3; q++) {
                    float a = time * 0.05F + q * 2.094F + i;
                    float sy = y + 0.45F * Mth.sin(time * 0.07F + q * 2.0F);
                    crystal(vc, p, crystal, cx + Mth.cos(a) * 1.2F * cs, sy, cz + Mth.sin(a) * 1.2F * cs, 0.09F * shardGrow,
                            0.2F * shardGrow, time * 0.1F);
                }
            }
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
            for (float[] l : items) {
                laser(buffer.getBuffer(RenderType.eyes(TRIM)), p, l[0], l[1], l[2], l[3], l[4], l[5], l[6] * 3.0F, l[7] * 0.45F,
                        l[8] * 0.45F, l[9] * 0.45F);
            }
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
     * What the fire of the Core does on top of the bigger orb (scaled pose): flame arcs rise from its surface and return
     * to it (four at stage 1, two more per stage), plasma balls with trails circle it (stage 2 and up), rings of glowing
     * sparks turn round it (stage 3 and 4) and a flare burns on the tip of the crown (stage 4). Every part grows in while
     * the animated stage passes its own stage, so it is also taken back while a defuse runs.
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
        // flame arcs (prominences)
        final int loops = 10;
        float r0 = ORB_RADIUS * 1.35F * f;
        float[][] tops = new float[loops][];
        float[] topK = new float[loops];
        for (int i = 0; i < loops; i++) {
            float appear = ease(Mth.clamp(vs - Math.max(0, (i - 2) / 2), 0.0F, 1.0F));
            if (appear < 0.02F) {
                continue;
            }
            float sign = i % 2 == 0 ? 1.0F : -1.0F;
            float phi = time * 0.012F * sign + i * (float) (Math.PI * 2.0) / loops;
            float theta = 0.85F * Mth.sin(i * 2.1F + time * 0.004F);
            float span = 0.85F;
            float growth = (0.15F + 0.85F * (0.5F + 0.5F * Mth.sin(time * 0.045F + i * 1.7F))) * appear;
            float h = (0.14F + 0.05F * Math.min(vs, 4.0F)) * f * growth;
            float[] prev = null;
            for (int sg = 0; sg <= 12; sg++) {
                float u = sg / 12.0F;
                float arc = Mth.sin(u * (float) Math.PI);
                float[] dd = dirOf(phi + span * u, theta + 0.3F * arc);
                float rr = r0 + h * arc;
                float[] pos = {cx + dd[0] * rr, cy + dd[1] * rr, cz + dd[2] * rr};
                if (prev != null) {
                    lines.add(prev[0], prev[1], prev[2], pos[0], pos[1], pos[2], 0.018F, 1.0F * k * appear,
                            (0.9F - 0.5F * arc) * k * appear, (0.5F - 0.4F * arc) * k * appear);
                }
                if (sg == 6) {
                    tops[i] = pos;
                    topK[i] = growth;
                }
                prev = pos;
            }
        }
        // plasma balls with trails
        float[][] balls = new float[4][];
        float[] ballK = new float[4];
        for (int i = 0; i < 4; i++) {
            float appear = ease(Mth.clamp(vs - Math.max(1, i), 0.0F, 1.0F));
            if (appear < 0.02F) {
                continue;
            }
            float radius = (0.44F + 0.05F * i) * f;
            float tilt = 0.45F + 0.62F * i;
            float speed = 0.045F * (1.0F + 0.2F * i) * (i % 2 == 0 ? 1.0F : -1.0F);
            float a0 = time * speed + i * (float) (Math.PI / 2.0);
            float[] prev = null;
            for (int sg = 0; sg <= 10; sg++) {
                float a = a0 - sg * 0.09F * Math.signum(speed);
                float lx = Mth.cos(a) * radius;
                float lz = Mth.sin(a) * radius;
                float[] pos = {cx + lx, cy + lz * Mth.sin(tilt), cz + lz * Mth.cos(tilt)};
                if (sg == 0) {
                    balls[i] = pos;
                    ballK[i] = appear;
                }
                if (prev != null) {
                    float fade = (1.0F - sg / 11.0F) * appear;
                    lines.add(prev[0], prev[1], prev[2], pos[0], pos[1], pos[2], 0.03F * fade, 1.0F * fade * k, 0.55F * fade * k, 0.15F * fade * k);
                }
                prev = pos;
            }
        }
        // rings of sparks
        float[][][] ringPts = new float[2][][];
        float[][] ringK = new float[2][];
        for (int ring = 0; ring < 2; ring++) {
            float appear = ease(Mth.clamp(vs - 2.0F - ring, 0.0F, 1.0F));
            if (appear < 0.02F) {
                continue;
            }
            float radius = (0.52F + 0.14F * ring) * f;
            float tilt = ring == 0 ? 0.32F : -0.7F;
            float dir = ring == 0 ? 1.0F : -1.0F;
            int n = 32;
            float[][] pts = new float[n][];
            float[] kk = new float[n];
            for (int i = 0; i < n; i++) {
                float a = i * (float) (Math.PI * 2.0) / n + time * 0.03F * dir;
                float lx = Mth.cos(a) * radius;
                float lz = Mth.sin(a) * radius;
                pts[i] = new float[]{cx + lx, cy + lz * Mth.sin(tilt), cz + lz * Mth.cos(tilt)};
                kk[i] = (0.5F + 0.5F * Mth.sin(i * 0.9F - time * 0.2F * dir)) * appear;
            }
            for (int i = 0; i < n; i++) {
                float[] a = pts[i];
                float[] b = pts[(i + 1) % n];
                float fl = (0.45F + 0.55F * kk[i]) * appear;
                lines.add(a[0], a[1], a[2], b[0], b[1], b[2], 0.013F, 1.0F * fl * k, 0.6F * fl * k, 0.18F * fl * k);
            }
            ringPts[ring] = pts;
            ringK[ring] = kk;
        }
        lines.flush(buffer, p);
        for (int i = 0; i < 4; i++) {
            if (balls[i] == null) {
                continue;
            }
            pose.pushPose();
            pose.translate(balls[i][0], balls[i][1], balls[i][2]);
            float keepK = addK;
            addK = 1.0F;
            sphere(buffer.getBuffer(fxFire()), pose.last(), 0.07F * f * k * ballK[i], time * 0.01F, -time * 0.02F, 1.0F, 0.75F, 0.4F);
            addK = keepK;
            pose.popPose();
        }
        for (int i = 0; i < loops; i++) {
            if (tops[i] != null) {
                float g = topK[i] * k;
                billboard(buffer.getBuffer(RenderType.eyes(HALO)), p, tops[i][0], tops[i][1], tops[i][2], 0.10F * f, 0.0F,
                        0.85F * g, 0.35F * g, 0.07F * g);
            }
        }
        for (int i = 0; i < 4; i++) {
            if (balls[i] != null) {
                float g = k * ballK[i];
                billboard(buffer.getBuffer(RenderType.eyes(HALO)), p, balls[i][0], balls[i][1], balls[i][2], 0.16F * f, 0.0F,
                        0.9F * g, 0.45F * g, 0.1F * g);
            }
        }
        for (int ring = 0; ring < 2; ring++) {
            if (ringPts[ring] == null) {
                continue;
            }
            for (int i = 0; i < ringPts[ring].length; i += 2) {
                float g = ringK[ring][i] * k;
                billboard(buffer.getBuffer(RenderType.eyes(HALO)), p, ringPts[ring][i][0], ringPts[ring][i][1], ringPts[ring][i][2],
                        0.06F * f, 0.0F, 1.0F * g, 0.55F * g, 0.12F * g);
            }
        }
        // the flare on the tip of the crown
        float crown = ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F));
        if (crown > 0.02F) {
            float gy = CROWN_TOP + 0.62F + d.lift;
            float pulse = (0.75F + 0.25F * Mth.sin(time * 0.2F)) * crown * k;
            billboard(buffer.getBuffer(RenderType.eyes(HALO)), p, 0.5F, gy, 0.5F, 0.34F, 0.0F, 1.0F * pulse, 0.5F * pulse, 0.12F * pulse);
            billboard(buffer.getBuffer(RenderType.eyes(FLARE)), p, 0.5F, gy, 0.5F, 0.5F, time * 0.03F, 1.0F * pulse, 0.65F * pulse, 0.25F * pulse);
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
            float w = 0.32F * ext;
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

    /** Stage 2: a stepped plinth round the foot of the lower half (lower pose). */
    private static void drawPlinth(PoseStack.Pose p, VertexConsumer vc, int light) {
        TextureAtlasSprite wall = sprite(WALL);
        TextureAtlasSprite marble = sprite(MARBLE);
        TextureAtlasSprite gold = sprite(GOLD);
        int bright = LightTexture.pack(Math.max(LightTexture.block(light), 9), LightTexture.sky(light));
        float o = 0.12F;
        float h = 0.10F;
        fbox(vc, p, light, marble, wall, -o, 1.0F + o, 0.0F, h, -o, 1.0F + o);
        // gold along the top edge and the foot of the plinth
        float w = 0.022F;
        float lo = -o - OUT;
        float hi = 1.0F + o + OUT;
        for (float y : new float[]{h - 0.012F, 0.0F}) {
            box(vc, p, bright, gold, 4, lo, hi, y, y + 0.024F, lo, lo + w);
            box(vc, p, bright, gold, 4, lo, hi, y, y + 0.024F, hi - w, hi);
            box(vc, p, bright, gold, 4, lo, lo + w, y, y + 0.024F, lo + w, hi - w);
            box(vc, p, bright, gold, 4, hi - w, hi, y, y + 0.024F, lo + w, hi - w);
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

    // --- the circle of tablets: one for every tablet the player has put into the Core ---

    /** Size of a stele compared with the tablet model below. */
    private static final float STELE = 0.62F;

    /**
     * The tablets are no longer flying about: every chain ends at an anchor stone, and on each of them stands a stele of the
     * Core. The four steles are there from the first stage on, cold and dark; the tablet of every stage lights its stele
     * (x, y, z, direction away from the Core).
     */
    private static float[] tabletAt(float scale, float vs, int i) {
        float[] an = anchorAt(i, scale, vs);
        return new float[]{an[0], 0.10F + 0.84F * STELE * an[2], an[1], (float) Math.atan2(an[1] - 0.5F, an[0] - 0.5F)};
    }

    /** The steles (solid): an obsidian slab in a gold frame on the anchor of every chain, facing the Core. */
    private static void drawTablets(PoseStack pose, VertexConsumer vc, float time, float scale, float vs) {
        TextureAtlasSprite gold = sprite(GOLD);
        int light = LightTexture.pack(12, 15);
        float ext = ease(Mth.clamp(vs, 0.0F, 1.0F));
        if (ext < 0.05F) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            float lit = ease(Mth.clamp(vs - i, 0.0F, 1.0F));
            TextureAtlasSprite wall = sprite(lit > 0.4F ? WALL_AWAKE : WALL);
            float[] t = tabletAt(scale, vs, i);
            pose.pushPose();
            pose.translate(t[0], t[1], t[2]);
            pose.mulPose(Axis.YP.rotationDegrees(90.0F - t[3] * 57.29578F));
            pose.scale(ext * STELE, ext * STELE, ext * STELE);
            PoseStack.Pose p = pose.last();
            box(vc, p, light, wall, 4, -0.40F, 0.40F, -0.70F, 0.70F, -0.07F, 0.07F);
            box(vc, p, light, gold, 4, -0.50F, 0.50F, 0.70F, 0.82F, -0.12F, 0.12F);
            box(vc, p, light, gold, 4, -0.50F, 0.50F, -0.82F, -0.70F, -0.12F, 0.12F);
            box(vc, p, light, gold, 4, -0.50F, -0.40F, -0.70F, 0.70F, -0.12F, 0.12F);
            box(vc, p, light, gold, 4, 0.40F, 0.50F, -0.70F, 0.70F, -0.12F, 0.12F);
            // a gold peak on top
            box(vc, p, light, gold, 4, -0.10F, 0.10F, 0.82F, 1.05F, -0.07F, 0.07F);
            pose.popPose();
        }
    }

    /** A point on a tablet (local x across, y up, z out) in world coordinates. */
    private static float[] onTablet(float[] t, float lx, float ly, float lz) {
        float phi = (float) (Math.PI / 2.0) - t[3];
        float c = Mth.cos(phi);
        float s = Mth.sin(phi);
        return new float[]{t[0] + lx * c + lz * s, t[1] + ly, t[2] - lx * s + lz * c};
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

    /** The glyphs of the steles (dark until lit) and the threads of light from the lit ones to the Core (additive). */
    private static void drawTabletGlyphs(PoseStack.Pose p, MultiBufferSource buffer, Late d) {
        float ext = ease(Mth.clamp(d.vs, 0.0F, 1.0F));
        if (ext < 0.1F) {
            return;
        }
        float ty = ORB_Y * d.scale;
        Lines lines = new Lines();
        float[][] hearts = new float[4][];
        float[] heartK = new float[4];
        float gs = ext * STELE;
        for (int i = 0; i < 4; i++) {
            float lit = ease(Mth.clamp(d.vs - i, 0.0F, 1.0F));
            float[] t = tabletAt(d.scale, d.vs, i);
            float k = 0.1F + 0.9F * lit * (0.75F + 0.25F * Mth.sin(d.time * 0.11F + i * 1.3F));
            for (float side = -1.0F; side <= 1.0F; side += 2.0F) {
                float lz = 0.085F * side * gs;
                for (float[] seg : GLYPHS[i]) {
                    float[] a = onTablet(t, seg[0] * side * gs, seg[1] * gs, lz);
                    float[] b = onTablet(t, seg[2] * side * gs, seg[3] * gs, lz);
                    lines.add(a[0], a[1], a[2], b[0], b[1], b[2], 0.009F, 1.0F * k, 0.6F * k, 0.15F * k);
                }
            }
            if (lit > 0.03F) {
                float[] heart = onTablet(t, 0.0F, 0.0F, -0.1F * gs);
                hearts[i] = heart;
                heartK[i] = k * lit;
                // the thread runs from the stele to the Core: the lit tablets feed it
                lines.add(heart[0], heart[1], heart[2], 0.5F, ty, 0.5F, 0.006F, 0.6F * k * lit, 0.3F * k * lit, 0.08F * k * lit);
                float pulse = (d.time * 0.02F + i * 0.25F) % 1.0F;
                float qa = pulse;
                float qb = Math.min(1.0F, pulse + 0.06F);
                lines.add(heart[0] + (0.5F - heart[0]) * qa, heart[1] + (ty - heart[1]) * qa, heart[2] + (0.5F - heart[2]) * qa,
                        heart[0] + (0.5F - heart[0]) * qb, heart[1] + (ty - heart[1]) * qb, heart[2] + (0.5F - heart[2]) * qb,
                        0.02F, 1.0F * lit, 0.62F * lit, 0.2F * lit);
            }
        }
        lines.flush(buffer, p);
        for (int i = 0; i < 4; i++) {
            if (hearts[i] != null) {
                float k = heartK[i];
                billboard(buffer.getBuffer(RenderType.eyes(HALO)), p, hearts[i][0], hearts[i][1], hearts[i][2], 0.7F, 0.0F,
                        0.5F * k, 0.25F * k, 0.06F * k);
            }
        }
    }

    /**
     * Features outside the Sarcophagus (world coordinates): round resonance waves and pulses on the plaza, the energy that
     * climbs the pillars from the ground, the glow of the crystals, the glyphs of the tablets, lightning from the crystals,
     * the sealing gate and the column of light into the sky (stage 4). Every part grows with the animated stage.
     */
    private static void drawEvolution(PoseStack pose, MultiBufferSource buffer, Late d) {
        float vs = d.vs;
        if (vs < 0.02F) {
            return;
        }
        float time = d.time;
        float sc = d.scale;
        PoseStack.Pose p = pose.last();
        if (vs > 0.02F) {
            drawTabletGlyphs(p, buffer, d);
        }
        Lines lines = new Lines();
        float wv = ease(Mth.clamp(vs - 1.0F, 0.0F, 1.0F));
        float disc = CoreEvolution.discAt(vs);
        float vr = d.vr;
        float vh = d.vh;
        if (wv > 0.02F) {
            // resonance: round waves run out from the Core, pulses run along the gold lines to the pillars
            for (int k = 0; k < 3; k++) {
                float f = (time * 0.011F + k / 3.0F) % 1.0F;
                float r = 0.7F + f * (disc - 0.7F);
                float fin = Mth.clamp(f / 0.12F, 0.0F, 1.0F);
                float a = fin * fin * (3.0F - 2.0F * fin) * (1.0F - f) * (1.0F - f) * wv;
                circle(lines, 0.5F, 0.04F, 0.5F, r, 40, 0.035F, 1.0F * a, 0.55F * a, 0.14F * a);
            }
            for (int i = 0; i < 4; i++) {
                float sx = i % 2 == 0 ? -1.0F : 1.0F;
                float sz = i < 2 ? -1.0F : 1.0F;
                for (int j = 0; j < 3; j++) {
                    float t = (time * 0.02F + j / 3.0F) % 1.0F;
                    float u = Math.min(1.0F, t + 0.1F);
                    float fade = (1.0F - 0.4F * t) * wv;
                    lines.add(0.5F + sx * vr * t, 0.05F, 0.5F + sz * vr * t, 0.5F + sx * vr * u, 0.05F, 0.5F + sz * vr * u,
                            0.07F, 1.0F * wv, 0.8F * fade, 0.4F * fade);
                }
            }
        }
        float pf = ease(Mth.clamp(vh / 3.0F, 0.0F, 1.0F));
        float yc = crystalY(vh) + crystalLift(vs);
        float[] archB = archBase(vs);
        float g3 = ease(Mth.clamp(vs - 2.0F, 0.0F, 1.0F));
        float dis = ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F));
        if (dis > 0.03F && vh > 3.0F) {
            // the arches have turned into bridges of light
            for (int n = 0; n < 4; n++) {
                for (int half = 0; half < 2; half++) {
                    float[] prev = null;
                    for (int j = 0; j <= 14; j++) {
                        float u = 0.5F * g3 * j / 14.0F;
                        float[] q = archPoint(ARCHES[n][0], ARCHES[n][1], archB, half == 0 ? u : 1.0F - u);
                        if (prev != null) {
                            lines.add(prev[0], prev[1] + 0.06F, prev[2], q[0], q[1] + 0.06F, q[2], 0.035F * dis, 1.0F * dis, 0.55F * dis, 0.14F * dis);
                        }
                        prev = q;
                    }
                }
            }
        }
        if (g3 > 0.05F && vh > 3.0F) {
            // energy runs along the arches to the lanterns
            for (int n = 0; n < 4; n++) {
                for (int half = 0; half < 2; half++) {
                    for (int j = 0; j < 2; j++) {
                        float u0 = ((time * 0.010F + j * 0.5F + n * 0.23F) % 1.0F) * 0.5F * g3;
                        float u1 = Math.min(0.5F * g3, u0 + 0.045F);
                        float[] a = archPoint(ARCHES[n][0], ARCHES[n][1], archB, half == 0 ? u0 : 1.0F - u0);
                        float[] b = archPoint(ARCHES[n][0], ARCHES[n][1], archB, half == 0 ? u1 : 1.0F - u1);
                        lines.add(a[0], a[1] + 0.2F, a[2], b[0], b[1] + 0.2F, b[2], 0.03F, 1.0F * g3, 0.6F * g3, 0.16F * g3);
                    }
                }
            }
        }
        if (vh > 0.3F) {
            // the pillars draw energy out of the ground: it climbs the four faces of the shaft, ripples run out over the floor
            float s = pillarSize(vs);
            float shaftBottom = 0.42F;
            float shaftTop = vh - 1.7F - 0.42F;
            float hw = 0.285F * s * (1.0F - dis);
            float[][] face = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
            for (int i = 0; i < 4; i++) {
                float cx = 0.5F + (i % 2 == 0 ? -vr : vr);
                float cz = 0.5F + (i < 2 ? -vr : vr);
                if (shaftTop > shaftBottom + 0.1F) {
                    if (dis > 0.03F) {
                        // the column of energy that the rings hold
                        float dk = dis * pf;
                        lines.add(cx, shaftBottom, cz, cx, shaftTop, cz, 0.05F, 1.0F * dk, 0.5F * dk, 0.1F * dk);
                        for (int j = 0; j < 3; j++) {
                            float u = (time * 0.03F + j / 3.0F + i * 0.19F) % 1.0F;
                            float y0 = shaftBottom + (shaftTop - shaftBottom) * u;
                            lines.add(cx, y0, cz, cx, Math.min(shaftTop, y0 + 0.5F), cz, 0.085F, 1.0F * dk, 0.8F * dk, 0.45F * dk);
                        }
                    }
                    for (int fc = 0; fc < 4; fc++) {
                        float fx = cx + face[fc][0] * hw;
                        float fz = cz + face[fc][1] * hw;
                        lines.add(fx, shaftBottom + 0.02F, fz, fx, shaftTop - 0.02F, fz, 0.012F, 0.8F * pf, 0.35F * pf, 0.08F * pf);
                        for (int j = 0; j < 3; j++) {
                            float u = (time * 0.025F + j / 3.0F + i * 0.19F + fc * 0.08F) % 1.0F;
                            float y0 = shaftBottom + (shaftTop - shaftBottom) * u;
                            float y1 = Math.min(shaftTop, y0 + 0.4F);
                            lines.add(fx, y0, fz, fx, y1, fz, 0.03F, 1.0F * pf, 0.8F * pf, 0.4F * pf);
                        }
                    }
                }
                float lk = crystalLift(vs) / 0.9F;
                if (lk > 0.05F) {
                    // the crystal floats: a thread of fire holds it over the prongs
                    lines.add(cx, vh - 1.7F + 0.5F, cz, cx, yc - crystalH(vs) * 0.95F, cz, 0.03F, 1.0F * lk * pf, 0.6F * lk * pf, 0.15F * lk * pf);
                }
                float u = (time * 0.02F + i * 0.25F) % 1.0F;
                float fade = (1.0F - u) * (1.0F - u) * Mth.clamp(u / 0.15F, 0.0F, 1.0F) * pf;
                circle(lines, cx, 0.03F, cz, 0.35F + u * 1.2F, 14, 0.03F, 1.0F * fade, 0.55F * fade, 0.14F * fade);
            }
        }
        float g4 = ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F));
        float gy = CoreEvolution.pillarAt(4.0F) + 3.5F;
        float gr = Math.max(2.7F, CoreEvolution.pillarRadiusAt(4.0F) * 0.62F) * g4;
        float pulse = (0.8F + 0.2F * Mth.sin(time * 0.1F)) * g4;
        if (g4 > 0.02F) {
            // lightning from the crystals into the Core
            java.util.Random rnd = new java.util.Random(((long) (time / 2.0F)) * 104729L + 7L);
            float ty = ORB_Y * sc;
            for (int i = 0; i < 4; i++) {
                if (rnd.nextFloat() < 0.6F) {
                    continue;
                }
                float ax = 0.5F + (i % 2 == 0 ? -vr : vr);
                float az = 0.5F + (i < 2 ? -vr : vr);
                float[] last = {ax, yc, az};
                final int segs = 8;
                for (int sIdx = 1; sIdx <= segs; sIdx++) {
                    float f = (float) sIdx / segs;
                    float spread = sIdx == segs ? 0.0F : 0.5F * Mth.sin(f * (float) Math.PI);
                    float[] cur = {ax + (0.5F - ax) * f + (rnd.nextFloat() - 0.5F) * spread * 2.0F,
                            yc + (ty - yc) * f + (rnd.nextFloat() - 0.5F) * spread * 2.0F,
                            az + (0.5F - az) * f + (rnd.nextFloat() - 0.5F) * spread * 2.0F};
                    lines.add(last[0], last[1], last[2], cur[0], cur[1], cur[2], 0.02F, 1.0F * g4, 0.72F * g4, 0.35F * g4);
                    last = cur;
                }
            }
            // the sealing gate high above: the Core's anchor holds the rift shut
            circle(lines, 0.5F, gy, 0.5F, gr, 56, 0.04F, 1.0F * pulse, 0.62F * pulse, 0.2F * pulse);
            for (int tick = 0; tick < 12; tick++) {
                float phi = tick * (float) (Math.PI * 2.0) / 12.0F + time * 0.004F;
                lines.add(0.5F + Mth.cos(phi) * gr, gy, 0.5F + Mth.sin(phi) * gr, 0.5F + Mth.cos(phi) * (gr + 0.4F), gy,
                        0.5F + Mth.sin(phi) * (gr + 0.4F), 0.03F, 1.0F * pulse, 0.55F * pulse, 0.18F * pulse);
            }
            for (int arm = 0; arm < 2; arm++) {
                float[] prev = null;
                for (int sIdx = 0; sIdx <= 36; sIdx++) {
                    float rr = 0.25F * g4 + (gr * 0.95F - 0.25F * g4) * sIdx / 36.0F;
                    float phi = rr * 1.5F + time * 0.07F + arm * 3.1416F;
                    float[] cur = {0.5F + Mth.cos(phi) * rr, gy, 0.5F + Mth.sin(phi) * rr};
                    if (prev != null) {
                        lines.add(prev[0], prev[1], prev[2], cur[0], cur[1], cur[2], 0.03F, 1.0F * pulse, 0.5F * pulse, 0.2F * pulse);
                    }
                    prev = cur;
                }
            }
        }
        lines.flush(buffer, p);
        // glows (additive): round the crystals, the disc of the gate, the column of light
        for (int i = 0; i < 4 && vh > 0.3F; i++) {
            float ax = 0.5F + (i % 2 == 0 ? -vr : vr);
            float az = 0.5F + (i < 2 ? -vr : vr);
            float k = (0.8F + 0.2F * Mth.sin(time * 0.15F + i)) * pf;
            billboard(buffer.getBuffer(RenderType.eyes(HALO)), p, ax, yc, az, 1.3F, time * 0.02F, 0.8F * k, 0.26F * k, 0.06F * k);
            billboard(buffer.getBuffer(RenderType.eyes(HALO)), p, ax, yc, az, 0.6F, 0.0F, 0.9F * k, 0.5F * k, 0.2F * k);
        }
        if (g3 > 0.05F && vh > 3.0F) {
            for (int n = 0; n < 4; n++) {
                float[] ln = lanternAt(n, archB, vs, time);
                if (ln[3] > 0.05F) {
                    float k = (0.8F + 0.2F * Mth.sin(time * 0.2F + n * 1.3F)) * ln[3];
                    billboard(buffer.getBuffer(RenderType.eyes(HALO)), p, ln[0], ln[1], ln[2], 0.6F, 0.0F, 0.9F * k, 0.42F * k, 0.1F * k);
                    billboard(buffer.getBuffer(RenderType.eyes(FLARE)), p, ln[0], ln[1], ln[2], 0.3F, time * 0.03F + n, 0.8F * k, 0.5F * k, 0.15F * k);
                }
            }
        }
        if (g4 > 0.02F) {
            plane(buffer.getBuffer(RenderType.eyes(HALO)), p, 0.5F, gy, 0.5F, gr, 0.0F, 0.0F, 0.0F, 0.0F, gr, 0.45F * pulse, 0.18F * pulse, 0.5F * pulse);
            float ty = ORB_Y * sc;
            float y0 = ty + 0.5F;
            for (int i = 0; i < 20; i++) {
                float yc2 = y0 + i * 6.0F + 3.0F;
                float k = 0.38F * (float) Math.pow(1.0F - i / 20.0F, 1.6F) * (0.85F + 0.15F * Mth.sin(time * 0.12F + i * 0.3F)) * g4;
                float w1 = 0.07F * sc;
                float w2 = 0.2F * sc;
                plane(buffer.getBuffer(RenderType.eyes(TRIM)), p, 0.5F, yc2, 0.5F, w1, 0.0F, 0.0F, 0.0F, 3.0F, 0.0F, 1.0F * k, 0.6F * k, 0.22F * k);
                plane(buffer.getBuffer(RenderType.eyes(TRIM)), p, 0.5F, yc2, 0.5F, 0.0F, 0.0F, w1, 0.0F, 3.0F, 0.0F, 1.0F * k, 0.6F * k, 0.22F * k);
                plane(buffer.getBuffer(RenderType.eyes(TRIM)), p, 0.5F, yc2, 0.5F, w2, 0.0F, 0.0F, 0.0F, 3.0F, 0.0F, 0.6F * k, 0.28F * k, 0.08F * k);
                plane(buffer.getBuffer(RenderType.eyes(TRIM)), p, 0.5F, yc2, 0.5F, 0.0F, 0.0F, w2, 0.0F, 3.0F, 0.0F, 0.6F * k, 0.28F * k, 0.08F * k);
            }
        }
    }

    /** Energy beams from the red crystals on the pillars to the Core, with bright pulses running along them. */
    private static void drawBeams(PoseStack pose, MultiBufferSource buffer, Late d) {
        float vh = d.vh;
        if (vh < 1.0F) {
            return;
        }
        float strength = CoreEvolution.beamAt(d.vs);
        float pf = ease(Mth.clamp(vh / 3.0F, 0.0F, 1.0F));
        float vr = d.vr;
        float top = crystalY(vh) + crystalLift(d.vs);
        float flick = 0.85F + 0.15F * Mth.sin(d.time * 0.7F);
        float k = strength * flick * pf;
        Lines lines = new Lines();
        float tx = 0.5F;
        float ty = ORB_Y * d.scale;
        float tz = 0.5F;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float ax = 0.5F + sx * vr;
                float az = 0.5F + sz * vr;
                lines.add(ax, top, az, tx, ty, tz, 0.022F * (0.5F + strength), 1.0F * k, 0.55F * k, 0.12F * k);
                // pulses that run to the Core
                for (int i = 0; i < 3; i++) {
                    float f = (d.time * 0.045F + i / 3.0F) % 1.0F;
                    float g = Math.min(1.0F, f + 0.08F);
                    lines.add(ax + (tx - ax) * f, top + (ty - top) * f, az + (tz - az) * f,
                            ax + (tx - ax) * g, top + (ty - top) * g, az + (tz - az) * g,
                            0.04F * (0.5F + strength), 1.0F * k, 0.62F * k, 0.2F * k);
                }
            }
        }
        lines.flush(buffer, pose.last());
    }

    /** The block as an item: shut, empty. */
    static void renderItem(PoseStack pose, MultiBufferSource buffer, int light) {
        VertexConsumer solid = buffer.getBuffer(Sheets.solidBlockSheet());
        drawLower(pose.last(), solid, light, false);
        drawLowerFrame(pose.last(), solid, light);
        drawSlab(pose.last(), solid, light);
        drawUpperFrame(pose.last(), solid, light);
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
            glowVertex(vc, pose, pts[j][0], pts[j][1], pts[j][2], 0.5F, 0.5F, k, k * 0.42F, k * 0.08F);
        }
    }

    private static void drawOrb(PoseStack pose, MultiBufferSource buffer, float time, float scale, float flash) {
        float pulse = 0.88F + 0.12F * Mth.sin(time * 0.19F);
        float cy = ORB_Y + 0.02F * Mth.sin(time * 0.08F);
        float flick = 0.94F + 0.06F * Mth.sin(time * 0.43F) * Mth.sin(time * 0.27F + 2.0F);
        // A body of fire that is not see-through: the additive layers alone fade out against a bright sky.
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 1.7F));
        float keepK = addK;
        addK = 1.0F;
        sphere(buffer.getBuffer(fxFire()), pose.last(), ORB_RADIUS * 0.98F * scale * flick, time * 0.006F, -time * 0.012F, 1.0F * pulse, 0.74F * pulse, 0.42F * pulse);
        addK = keepK;
        pose.popPose();
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
        billboard(flare, p, 0.5F, cy, 0.5F, (0.55F + 0.5F * flash) * s, time * 0.012F, 0.45F * fk, 0.27F * fk, 0.09F * fk);
        billboard(flare, p, 0.5F, cy, 0.5F, 0.35F * s, 0.4F - time * 0.02F, 0.33F * fk, 0.19F * fk, 0.06F * fk);
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
        PoseStack.Pose p = pose.last();
        float flicker = 0.8F + 0.2F * Mth.sin(time * 0.9F);
        float k = 0.8F * scale * flicker;
        float cy = ORB_Y;
        Lines rays = new Lines();
        float[][] lowerEnds = {NW, NE, SE, SW, N, S, W, E};
        for (float[] end : lowerEnds) {
            rays.add(0.5F, cy, 0.5F, end[0], end[1], end[2], 0.0055F, 1.0F * k, 0.78F * k, 0.3F * k);
        }
        // The upper half moves: its end points are turned and lifted the same way as the half itself.
        double rad = Math.toRadians(twist);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        rays.add(0.5F, cy, 0.5F, 0.5F, 1.0F - SLAB + lift, 0.5F, 0.0055F, 1.0F * k, 0.78F * k, 0.3F * k);
        float[][] tips = {N, S, W, E};
        for (float[] tip : tips) {
            float dx = tip[0] - 0.5F;
            float dz = tip[2] - 0.5F;
            rays.add(0.5F, cy, 0.5F, 0.5F + dx * cos + dz * sin, tip[1] + lift, 0.5F - dx * sin + dz * cos, 0.0055F, 1.0F * k, 0.78F * k, 0.3F * k);
        }
        rays.flush(buffer, p);
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

    /** Blended (not added) white: effects that must stay visible against a bright sky. */
    private static RenderType fx() {
        return RenderType.entityTranslucentEmissive(TRIM);
    }

    /** Blended fire texture: the solid body of the Core. */
    private static RenderType fxFire() {
        return RenderType.entityTranslucentEmissive(ORB);
    }
}
