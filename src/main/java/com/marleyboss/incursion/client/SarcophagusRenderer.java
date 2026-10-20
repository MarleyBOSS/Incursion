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
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
    static final ResourceLocation TRIM = effect("trim");
    static final ResourceLocation ORB = effect("orb");
    static final ResourceLocation HALO = effect("halo");
    static final ResourceLocation FLARE = effect("flare");
    private static final ResourceLocation RUNE_ITEM = ResourceLocation.fromNamespaceAndPath(NS, "item/core_rune");
    static final ResourceLocation RING = effect("rune_circle");
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
    static final float ORB_Y = 1.12F;
    /** Height of the four corner tips of the lower half: exactly where the slab begins, so the frame lines meet there. */
    private static final float TIP = 1.0F - SLAB;
    /** Rise of the lower half's surface per block from the notch to the corner. */
    private static final float FLOOR_SLOPE = (TIP - 0.5F) / 0.5F;
    /** Width, along the wall, of the outer gold line of the glass frame where it meets the corner (the line is 0.010 thick across). */
    /** Width of the corner posts: the horizontal width of the slanted gold bars at their tip, so the posts are flush with them. */
    private static final float POST_W = GOLD_HALF * (float) Math.sqrt(1.0F + (0.5F / (TIP - 0.5F)) * (0.5F / (TIP - 0.5F)));
    private static final float CORNER_W = 0.010F * (float) Math.sqrt(1.0F + (0.5F / (TIP - 0.5F)) * (0.5F / (TIP - 0.5F)));
    static final float ORB_RADIUS = 0.15F;

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
        float nextBurn;
        float nextFire;
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

    static float ease(float t) {
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
            level.playLocalSound(px, py, pz, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.4F, 0.8F, false); // the powders catch fire
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
            level.playLocalSound(px, py, pz, ModSounds.CORE_HUM.get(), SoundSource.BLOCKS, 1.0F, 1.0F, false);
            state.nextHum = time + 1277.0F; // the piece is 64 s (1280 ticks) and fades at both ends: the next round starts just before its end
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
        // The powders do not vanish: when the potion has landed they catch fire, burn down (the heaps sink, see below) and
        // leave smoke.
        if (active && showHeaps && (hasGunpowder || hasBlaze) && contents > 0.02F && contents < 1.0F + 1.0E-3F && t > 0.0F
                && time >= state.nextBurn) {
            state.nextBurn = time + 1.0F;
            float h = 0.5F + 0.3F * contents;
            for (int k = 0; k < 4; k++) {
                level.addParticle(ParticleTypes.FLAME, px + (level.random.nextDouble() - 0.5) * 0.7 * contents - 0.0,
                        core.getBlockPos().getY() + h + level.random.nextDouble() * 0.2,
                        pz + (level.random.nextDouble() - 0.5) * 0.7 * contents, 0.0, 0.03, 0.0);
            }
            level.addParticle(ParticleTypes.LARGE_SMOKE, px + (level.random.nextDouble() - 0.5) * 0.4,
                    core.getBlockPos().getY() + h + 0.1, pz + (level.random.nextDouble() - 0.5) * 0.4, 0.0, 0.04, 0.0);
        }

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
        if (active && time >= state.nextFire) {
            state.nextFire = time + 2.0F;
            float[] dragonAt = bossAt(2, time, vs, CoreEvolution.pillarRadiusAt(vp), CoreEvolution.pillarAt(vp));
            if (dragonAt[5] > 0.5F && dragonAt[4] > 0.1F) {
                float[] mouth = dragonMouth(dragonAt);
                level.addParticle(ParticleTypes.DRAGON_BREATH, core.getBlockPos().getX() + mouth[0], core.getBlockPos().getY() + mouth[1],
                        core.getBlockPos().getZ() + mouth[2], (level.random.nextDouble() - 0.5) * 0.01, 0.008, (level.random.nextDouble() - 0.5) * 0.01);
            }
        }
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

        Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float yawCam = (float) Math.atan2(camPos.x - px, camPos.z - pz);

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
                // burning down: the heaps sink to the floor of the cavity
                pose.pushPose();
                pose.translate(0.5F, 0.5F, 0.5F);
                pose.scale(0.7F + 0.3F * contents, Math.max(0.02F, contents), 0.7F + 0.3F * contents);
                pose.translate(-0.5F, -0.5F, -0.5F);
                drawHeaps(pose.last(), solid, light, hasGunpowder, hasBlaze);
                pose.popPose();
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
            drawLava(pose, fresh, time, vp, sc);
            drawChains(pose, fresh, time, sc, vs);
            drawCages(pose, fresh, time, vs, vp);
            drawCageChains(pose, fresh, time, vs, vp);
            drawPillars(pose, fresh, time, vp);
            flushSolid(buffer);
            drawBosses(pose, buffer, level, core, time, partialTick, vs, vp, yawCam);
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
        float evoAge = -1.0F;
        float evoBoost = 1.0F;
        if (active && core.isEvoShow()) {
            evoAge = (level.getGameTime() - core.getEvoStart()) + partialTick;
            float fuse = CoreEvolution.EVOLVE_TICKS;
            if (evoAge < 0.0F) {
                evoAge = -1.0F;
            } else if (evoAge < fuse) {
                evoBoost = 1.0F + 2.2F * (float) Math.pow(evoAge / fuse, 2.2);
            } else {
                float fall = Math.max(0.0F, 1.0F - (evoAge - fuse) / 25.0F);
                evoBoost = 1.0F + 2.2F * fall * fall;
            }
        }
        if (evoAge >= 0.0F) {
            CoreBlastRenderer.noteUpgradeShake(level, core.getBlockPos(), evoAge);
        }
        late.evoAge = evoAge;
        late.orb = orb * evoBoost;
        late.flash = flash;
        late.glow = glow;
        late.contents = contents;
        late.hasRune = hasRune;
        late.blazeHeap = hasBlaze && showHeaps;
        late.ready = ready;
        late.scale = sc;
        late.window = windowDeg;
        late.yaw = yawCam;
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
    static final class Late {
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
        float yaw;
        /** Ticks since a new stage began to rise (the upgrade animation), -1 = none. */
        float evoAge = -1.0F;
    }

    private static final List<Late> LATE = new ArrayList<>();

    /** Draws the collected see-through parts once the translucent blocks (water, ice, glass) are in the picture. */
    static void renderLate(RenderLevelStageEvent event) {
        // After the weather: the clouds are drawn before it, and what is drawn earlier fades behind them against the sky.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        boolean blasts = CoreBlastRenderer.collect();
        if (LATE.isEmpty() && !blasts) {
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
            if (blasts) {
                CoreBlastRenderer.draw(buffer, cam, event.getPartialTick().getGameTimeDeltaPartialTick(false));
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
            drawStar(pose, buffer, d);
            float cf = coreFactor(d.vs);
            pose.pushPose();
            pose.translate(0.5F, ORB_Y, 0.5F);
            pose.scale(cf, cf, cf);
            pose.translate(-0.5F, -ORB_Y, -0.5F);
            drawOrb(pose, buffer, time, d.orb, d.flash, d.vs);
            pose.popPose();
            drawCorona(pose, buffer, d);
        }
        if (d.vs > 0.02F) {
            drawPlaqueGlyphs(pose, buffer, d);
            drawPlinthGlow(pose, buffer, d);
        }
        pose.popPose();
        drawEvolution(pose, buffer, d);
        if (d.evoAge >= 0.0F) {
            CoreBlastRenderer.drawUpgrade(pose, buffer, d);
        }

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

    static void flushAdditive(MultiBufferSource buffer) {
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

    // --- the pillars change their form with the stage: the Breaker's forge (2) -> the Soul Weaver's cage (3) -> the Wither's ribs (4) ---

    private static final ResourceLocation LAVA = ResourceLocation.withDefaultNamespace("block/lava_still");
    private static final ResourceLocation DEEP = ResourceLocation.withDefaultNamespace("block/deepslate_tiles");
    private static final ResourceLocation REINF_TOP = ResourceLocation.withDefaultNamespace("block/reinforced_deepslate_top");
    private static final ResourceLocation REINF_SIDE = ResourceLocation.withDefaultNamespace("block/reinforced_deepslate_side");
    private static final ResourceLocation SCULK = ResourceLocation.withDefaultNamespace("block/sculk");

    private static float toSoul(float vp) {
        return ease(Mth.clamp(vp - 2.0F, 0.0F, 1.0F));
    }

    private static float toRift(float vp) {
        return ease(Mth.clamp(vp - 3.0F, 0.0F, 1.0F));
    }

    /** Size factor of the pillars. */
    private static float pillarSize(float vp) {
        return 1.25F + 0.04F * Mth.clamp(vp - 1.0F, 0.0F, 3.0F) + 0.10F * Math.max(0.0F, CoreEvolution.pillarAt(vp) - 6.0F);
    }

    /** Half width of the stone shaft: it gets slimmer with the later forms. */
    private static float shaftHalf(float vp) {
        return (0.34F - 0.015F * toSoul(vp) - 0.01F * toRift(vp)) * pillarSize(vp) + 0.03F * Math.max(0.0F, CoreEvolution.pillarAt(vp) - 4.0F);
    }

    /** From stage 4 on the crystals leave their holders and float above them. */
    private static float crystalLift(float vp) {
        return 0.0F;
    }

    private static float crystalH(float vp) {
        return Mth.lerp(toSoul(vp), 1.0F, 1.35F);
    }

    private static float crystalR(float vp) {
        return Mth.lerp(toSoul(vp), 0.55F, 0.38F);
    }

    /** Height of the middle of the crystal (above the hammer head in the forge form, inside the cage in the soul form). */
    private static float crystalCenter(float vp) {
        float top = CoreEvolution.pillarAt(vp) - 1.7F;
        return top + 0.14F * pillarSize(vp) * (1.0F - toSoul(vp)) + crystalH(vp) - 0.05F + crystalLift(vp);
    }

    /** Glow colour of the crystals: forge-hot red, then the violet of the dragon. */
    private static float[] crystalColor(float vp) {
        float b = toSoul(vp);
        return new float[]{Mth.lerp(b, 1.0F, 0.72F), Mth.lerp(b, 0.38F, 0.35F), Mth.lerp(b, 0.1F, 1.0F)};
    }

    /** A repeatable pseudo random number in [0, 1) from two integers. */
    static float rnd(int a, int b) {
        double v = Math.sin((a % 9973) * 12.9898 + (b % 9973) * 78.233) * 43758.5453;
        return (float) (v - Math.floor(v));
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
     * The four pillars (solid). Stage 2 (the Breaker, a smith): a thick shaft with a crystal over it and lava running
     * down the faces that look at the Core. Stage 3 (the Soul Weaver and the Ender Dragon): a slim cage of gold arches rises round a violet crystal that three rings turn about, and an eye is set into the
     * shaft. Stage 4 (the Wither): the cage gives way to the ribs. Radius and height follow the (lagged)
     * animated stage, so they grow after the plaza is laid.
     */
    private static void drawPillars(PoseStack pose, VertexConsumer vc, float time, float vp) {
        float height = CoreEvolution.pillarAt(vp);
        if (height < 0.3F) {
            return;
        }
        float vr = CoreEvolution.pillarRadiusAt(vp);
        float s = pillarSize(vp);
        float toB = toSoul(vp);
        float wA = 1.0F - toB;
        float wB = toB; // the cage over the crystal stays in stage 4
        PoseStack.Pose p = pose.last();
        TextureAtlasSprite wall = sprite(vp >= 2.5F ? WALL_AWAKE : WALL);
        TextureAtlasSprite marble = sprite(MARBLE);
        TextureAtlasSprite gold = sprite(GOLD);
        TextureAtlasSprite dark = sprite(INNER);
        TextureAtlasSprite lava = sprite(LAVA);
        TextureAtlasSprite crystal = sprite(block(toB > 0.5F ? "core_crystal_violet" : "core_crystal"));
        int light = LightTexture.pack(13, 15);
        int full = LightTexture.FULL_BRIGHT;
        float top = height - 1.7F; // top of the stone part
        float yc = crystalCenter(vp);
        float hw = shaftHalf(vp);
        float sp = Math.max(s, hw / 0.36F); // plinth and collars follow the thickness of the shaft
        float cr = crystalR(vp);
        float ch = crystalH(vp);
        for (int i = 0; i < 4; i++) {
            float cx = 0.5F + (i % 2 == 0 ? -vr : vr);
            float cz = 0.5F + (i < 2 ? -vr : vr);
            float ang = (float) Math.atan2(cz - 0.5F, cx - 0.5F);
            float h1 = Math.min(top, 0.22F);
            if (h1 > 0.02F) {
                fbox(vc, p, light, marble, wall, cx - 0.58F * sp, cx + 0.58F * sp, 0.0F, h1, cz - 0.58F * sp, cz + 0.58F * sp);
                goldFrame(vc, p, light, gold, cx - 0.58F * sp, cx + 0.58F * sp, h1, cz - 0.58F * sp, cz + 0.58F * sp, 0.016F);
            }
            float h2 = Math.min(top, 0.42F);
            if (h2 > 0.24F) {
                fbox(vc, p, light, marble, wall, cx - 0.45F * sp, cx + 0.45F * sp, 0.22F, h2, cz - 0.45F * sp, cz + 0.45F * sp);
                goldFrame(vc, p, light, gold, cx - 0.45F * sp, cx + 0.45F * sp, h2, cz - 0.45F * sp, cz + 0.45F * sp, 0.014F);
            }
            float shaftBottom = 0.42F;
            float shaftTop = top - 0.42F;
            if (shaftTop > shaftBottom + 0.1F) {
                int tiles = Math.max(1, Math.round((shaftTop - shaftBottom) / (2.0F * hw)));
                float tileH = (shaftTop - shaftBottom) / tiles;
                for (int t = 0; t < tiles; t++) {
                    fbox(vc, p, light, marble, wall, cx - hw, cx + hw, shaftBottom + t * tileH, shaftBottom + (t + 1) * tileH, cz - hw, cz + hw);
                }
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
                // forge: lava runs down the two faces that look at the Core and pools at the foot
                if (wA > 0.3F) {
                    float sxg = cx > 0.5F ? 1.0F : -1.0F;
                    float szg = cz > 0.5F ? 1.0F : -1.0F;
                    int segs = Math.max(2, Math.round((shaftTop - shaftBottom) / 0.55F));
                    float segH = (shaftTop - shaftBottom) / segs;
                    for (int k = 0; k < segs; k++) {
                        float y0 = shaftTop - (k + 1) * segH;
                        float y1 = shaftTop - k * segH;
                        float wd = 0.05F + 0.08F * rnd(i * 17 + k, 3);
                        float wd2 = 0.05F + 0.08F * rnd(i * 17 + k, 9);
                        float xa = cx - sxg * (hw + 0.016F);
                        float xb = cx - sxg * (hw - 0.002F);
                        fbox(vc, p, full, lava, lava, Math.min(xa, xb), Math.max(xa, xb), y0, y1, cz - wd, cz + wd);
                        float za = cz - szg * (hw + 0.016F);
                        float zb = cz - szg * (hw - 0.002F);
                        fbox(vc, p, full, lava, lava, cx - wd2, cx + wd2, y0, y1, Math.min(za, zb), Math.max(za, zb));
                    }
                }
            }
            if (top > 0.5F) {
                float cb = Math.max(0.0F, top - 0.42F);
                fbox(vc, p, light, marble, wall, cx - 0.42F * sp, cx + 0.42F * sp, cb, cb + 0.16F, cz - 0.42F * sp, cz + 0.42F * sp);
                goldFrame(vc, p, light, gold, cx - 0.42F * sp, cx + 0.42F * sp, cb + 0.16F, cz - 0.42F * sp, cz + 0.42F * sp, 0.02F);
                fbox(vc, p, light, marble, wall, cx - 0.33F * sp, cx + 0.33F * sp, cb + 0.16F, top, cz - 0.33F * sp, cz + 0.33F * sp);
                goldFrame(vc, p, light, gold, cx - 0.33F * sp, cx + 0.33F * sp, top, cz - 0.33F * sp, cz + 0.33F * sp, 0.018F);
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
     * How far from the middle of the orb fire may reach in direction dd (a unit vector) without touching the walls of the
     * cavity, the lid above it or the floor below it. {@code wall}: the half width of the cavity.
     */
    private static float fireLimit(float[] dd, float lift, float wall) {
        float lim = 10.0F;
        float h = Mth.sqrt(dd[0] * dd[0] + dd[2] * dd[2]);
        if (h > 0.01F) {
            lim = Math.min(lim, wall / h);
        }
        if (dd[1] > 0.01F) {
            lim = Math.min(lim, (0.95F + lift - 0.03F - ORB_Y) / dd[1]);
        }
        if (dd[1] < -0.01F) {
            lim = Math.min(lim, (ORB_Y - 0.56F) / -dd[1]);
        }
        return lim;
    }

    /**
     * Stage 0 (the shut ritual just lit): the Core is a star. Thin golden rays run from it to the frame in every direction,
     * a flare burns in its middle and a ring of runes turns round it. They give way to the flames of the later stages.
     */
    static void drawStar(PoseStack pose, MultiBufferSource buffer, Late d) {
        float k = d.orb * (1.0F - ease(Mth.clamp(d.vs, 0.0F, 1.0F)));
        if (k < 0.02F) {
            return;
        }
        float time = d.time;
        PoseStack.Pose p = pose.last();
        Lines lines = new Lines();
        float pulse = 0.85F + 0.15F * Mth.sin(time * 0.17F);
        for (int i = 0; i < 14; i++) {
            float phi = i * 2.399F + time * 0.004F;
            float theta = 0.75F * Mth.sin(i * 1.9F + 0.6F);
            float[] dd = dirOf(phi, theta);
            float end = fireLimit(dd, d.lift, 0.46F);
            float start = 0.13F;
            float kk = k * (0.65F + 0.35F * Mth.sin(time * 0.09F + i * 1.7F));
            for (int sg = 0; sg < 3; sg++) {
                float a0 = start + (end - start) * sg / 3.0F;
                float a1 = start + (end - start) * (sg + 1) / 3.0F;
                float fade = 1.0F - 0.3F * sg;
                lines.add(0.5F + dd[0] * a0, ORB_Y + dd[1] * a0, 0.5F + dd[2] * a0, 0.5F + dd[0] * a1, ORB_Y + dd[1] * a1, 0.5F + dd[2] * a1,
                        0.02F, 1.0F * kk * fade * 0.2F, 0.7F * kk * fade * 0.2F, 0.25F * kk * fade * 0.2F);
                lines.add(0.5F + dd[0] * a0, ORB_Y + dd[1] * a0, 0.5F + dd[2] * a0, 0.5F + dd[0] * a1, ORB_Y + dd[1] * a1, 0.5F + dd[2] * a1,
                        0.0055F, 1.0F * kk * fade, 0.84F * kk * fade, 0.45F * kk * fade);
            }
        }
        lines.flush(buffer, p);
        // a ring of runes, flat round the Core, and one that always faces the player
        float rot = time * 0.012F;
        float ra = 0.42F;
        plane(buffer.getBuffer(glow(RING)), p, 0.5F, ORB_Y - 0.03F, 0.5F, Mth.cos(rot) * ra, 0.0F, Mth.sin(rot) * ra,
                -Mth.sin(rot) * ra, 0.0F, Mth.cos(rot) * ra, 0.95F * k * pulse, 0.7F * k * pulse, 0.28F * k * pulse);
        billboard(buffer.getBuffer(glow(RING)), p, 0.5F, ORB_Y, 0.5F, 0.33F, -time * 0.01F, 1.0F * k * 0.55F, 0.78F * k * 0.55F, 0.35F * k * 0.55F);
    }

    /**
     * What the fire of the Core does on top of the orb (scaled pose). From stage 1 on, prominences like those of the sun
     * rise from its surface: a flame leaves the Core, arches over, sinks back into it and goes out, then a new one rises
     * elsewhere (3 at stage 1, one more per stage). At stage 4 the Core also grows purple tongues that lick upward, the souls
     * it drinks. All fire is kept inside the cavity, so it never passes through the lid or the walls.
     */
    static void drawCorona(PoseStack pose, MultiBufferSource buffer, Late d) {
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
        float r0 = ORB_RADIUS * 0.9F * f;
        int arcs = 5;
        for (int i = 0; i < arcs; i++) {
            float appear = i < 3 ? ease(Mth.clamp(vs, 0.0F, 1.0F)) : ease(Mth.clamp(vs - (i - 2), 0.0F, 1.0F));
            if (appear < 0.02F) {
                continue;
            }
            float period = 200.0F + 23.0F * i;
            float q = time / period + i * 0.37F;
            int cyc = (int) Math.floor(q);
            float t = q - cyc;
            int sd = cyc * 5 + i;
            float phiA = rnd(sd, 1) * 6.2832F;
            float thA = -0.15F + 0.95F * rnd(sd, 2);
            float spread = (1.2F + 1.3F * rnd(sd, 3)) * (rnd(sd, 4) < 0.5F ? 1.0F : -1.0F);
            float thB = -0.15F + 0.95F * rnd(sd, 6);
            float[] dA = dirOf(phiA, thA);
            float[] dB = dirOf(phiA + spread, thB);
            float hmax = (0.13F + 0.12F * rnd(sd, 5)) * f;
            float uA;
            float uB;
            if (t < 0.3F) {
                uA = 0.0F;
                uB = ease(t / 0.3F);
            } else if (t < 0.62F) {
                uA = 0.0F;
                uB = 1.0F;
            } else {
                uA = ease((t - 0.62F) / 0.38F);
                uB = 1.0F;
            }
            if (uB - uA < 0.02F) {
                continue;
            }
            float live = (1.0F - ease((t - 0.93F) / 0.07F)) * appear * k;
            float[] prev = null;
            final int segs = 16;
            for (int sg = 0; sg <= segs; sg++) {
                float u = uA + (uB - uA) * sg / segs;
                float[] dd = {dA[0] + (dB[0] - dA[0]) * u, dA[1] + (dB[1] - dA[1]) * u, dA[2] + (dB[2] - dA[2]) * u};
                float dl = Mth.sqrt(dd[0] * dd[0] + dd[1] * dd[1] + dd[2] * dd[2]);
                if (dl < 0.05F) {
                    prev = null;
                    continue;
                }
                dd[0] /= dl;
                dd[1] /= dl;
                dd[2] /= dl;
                float hold = t >= 0.3F && t < 0.62F ? 1.0F + 0.1F * Mth.sin(time * 0.3F + i) : 1.0F;
                float rr = r0 + hmax * Mth.sin(u * (float) Math.PI) * hold;
                rr = Math.min(rr, Math.max(r0, fireLimit(dd, d.lift, 0.44F) - 0.01F));
                float[] pos = {cx + dd[0] * rr, cy + dd[1] * rr, cz + dd[2] * rr};
                if (prev != null) {
                    float body = Mth.sin(u * (float) Math.PI);
                    float flick = 0.75F + 0.25F * Mth.sin(time * 0.37F + sg * 1.3F + i);
                    float kk = live * (0.45F + 0.55F * body) * flick;
                    lines.add(prev[0], prev[1], prev[2], pos[0], pos[1], pos[2], 0.034F * (0.4F + 0.6F * body), 0.9F * kk * 0.5F, 0.3F * kk * 0.5F, 0.05F * kk * 0.5F);
                    lines.add(prev[0], prev[1], prev[2], pos[0], pos[1], pos[2], 0.017F * (0.4F + 0.6F * body), 1.0F * kk, 0.58F * kk, 0.16F * kk);
                    lines.add(prev[0], prev[1], prev[2], pos[0], pos[1], pos[2], 0.0068F * (0.4F + 0.6F * body), 1.0F * kk, 0.92F * kk, 0.6F * kk);
                }
                prev = pos;
            }
        }
        lines.flush(buffer, p);
        float crown = ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F));
        if (crown > 0.02F) {
            float gy = CROWN_TOP + 0.62F + d.lift;
            float pulse = (0.8F + 0.2F * Mth.sin(time * 0.2F)) * crown * k;
            billboard(buffer.getBuffer(glow(HALO)), p, 0.5F, gy, 0.5F, 0.34F, 0.0F, 0.45F * pulse, 0.2F * pulse, 0.7F * pulse);
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
        float reach = Math.min(0.5F * scale + 1.6F, radius + 0.4F);
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
            chainBetween(vc, p, light, gold, tx, ty, tz, ax, 0.06F, az, 0.10F, time + i * 7.0F, 1.0F);
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
    /** Height of the tip of the antenna in the pose of the lid (blocks of the unscaled Sarcophagus). */
    private static float antennaHeight(float vs) {
        return 1.7F + 0.1275F * Mth.clamp(vs, 0.0F, 4.0F);
    }

    /** Height of the tip of the antenna in the world (it stands on the axis of the Sarcophagus). */
    private static float antennaTipY(float vs, float scale, float lift) {
        float g1 = ease(Mth.clamp(vs, 0.0F, 1.0F));
        return scale * (1.0F + (antennaHeight(vs) - 1.0F) * g1 + lift);
    }

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
        // the antenna: the energy of every boss runs into its tip and down into the Core
        float ah = antennaHeight(vs);
        fbox(vc, p, light, top, marble, 0.44F, 0.56F, 1.138F, 1.21F, 0.44F, 0.56F);
        box(vc, p, bright, gold, 4, 0.43F, 0.57F, 1.205F, 1.225F, 0.43F, 0.57F);
        beam(vc, p, bright, gold, 0.5F, 1.21F, 0.5F, 0.5F, ah - 0.2F, 0.5F, 0.02F);
        for (int c = 0; c < 3; c++) {
            float cy = 1.21F + (ah - 1.41F) * (c + 1) / 4.0F;
            box(vc, p, bright, gold, 4, 0.465F, 0.535F, cy - 0.016F, cy + 0.016F, 0.465F, 0.535F);
        }
        for (int c = 0; c < 4; c++) {
            float ang = c * (float) (Math.PI / 2.0) + 0.4F;
            float[] prong = {0.5F, ah - 0.34F, 0.5F};
            for (int sg = 1; sg <= 4; sg++) {
                float t = sg / 4.0F;
                float[] nx = {0.5F + Mth.cos(ang) * 0.2F * Mth.sin(t * (float) (Math.PI / 2.0)), ah - 0.34F + 0.32F * t,
                        0.5F + Mth.sin(ang) * 0.2F * Mth.sin(t * (float) (Math.PI / 2.0))};
                beam(vc, p, bright, gold, prong[0], prong[1], prong[2], nx[0], nx[1], nx[2], 0.009F);
                prong = nx;
            }
        }
        crystal(vc, p, sprite(block("core_crystal_violet")), 0.5F, ah - 0.04F, 0.5F, 0.05F, 0.11F, 0.6F);
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
                TextureAtlasSprite plate = sprite(MARBLE);
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
     * The glyphs on the plaques (lower pose): a socket whose tablet has been placed glows in the colour of its guardian. An
     * empty socket shows nothing at all (the signs must not give away what is missing). The tablet of the Elder Guardian also
     * sends its beam, twisted like the beam the guardian fires, from its eye into the Core.
     */
    private static void drawPlaqueGlyphs(PoseStack pose, MultiBufferSource buffer, Late d) {
        float ext = ease(Mth.clamp(d.vs * 1.4F, 0.0F, 1.0F));
        if (ext < 0.1F) {
            return;
        }
        Lines lines = new Lines();
        List<float[]> packets = new ArrayList<>();
        float gs = 0.36F * ext;
        float[] lit = new float[4];
        for (int side = 0; side < 4; side++) {
            lit[side] = ease(Mth.clamp(d.vs - side, 0.0F, 1.0F));
            if (lit[side] < 0.02F) {
                continue;
            }
            float[] c = GUARD[side];
            float k = lit[side] * (0.55F + 0.45F * (0.85F + 0.15F * Mth.sin(d.time * 0.09F + side * 1.3F)));
            for (float[] seg : GLYPHS[side]) {
                float[] a = plaquePoint(side, seg[0] * gs, seg[1] * gs, 0.036F);
                float[] b = plaquePoint(side, seg[2] * gs, seg[3] * gs, 0.036F);
                lines.add(a[0], a[1], a[2], b[0], b[1], b[2], 0.006F, c[0] * k, c[1] * k, c[2] * k);
            }
        }
        PoseStack.Pose p = pose.last();
        lines.flush(buffer, p);
        for (float[] pk : packets) {
            billboard(buffer.getBuffer(glow(HALO)), p, pk[0], pk[1], pk[2], pk[7], 0.0F, pk[4] * pk[3], pk[5] * pk[3], pk[6] * pk[3]);
        }
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
    // The stage objects (world coordinates). Every stage brings something that belongs to its tablet and to the boss and general
    // it stands for, and shows how their energy flows into the Core: 5 the Scout and the Elder Guardian (shadow sentinels on
    // the chains, the guardian's beam), 10 the Breaker and the Warden (the forge with its lava, the shrines with their
    // heartbeat), 15 the Soul Weaver and the Ender Dragon (a spirit wyrm, lightning, Eyes of Ender, a woven net), 20 the
    // Wither (ribs, three heads, souls, soul lanterns).
    // ------------------------------------------------------------------

    /** A flat strip on the floor from (ax, az) to (bx, bz), half width w, at height y; the sprite is stretched along it. */
    private static void floorStrip(VertexConsumer vc, PoseStack.Pose p, int light, TextureAtlasSprite sprite, float ax, float az,
                                   float bx, float bz, float w, float y) {
        float dx = bx - ax;
        float dz = bz - az;
        float l = Mth.sqrt(dx * dx + dz * dz);
        if (l < 1.0E-3F) {
            return;
        }
        float nx = -dz / l * w;
        float nz = dx / l * w;
        float[][] uv = {{0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}, {0.0F, 0.0F}};
        polyUV(vc, p, light, sprite, 0.0F, 1.0F, 0.0F, new float[][]{{ax - nx, y, az - nz}, {bx - nx, y, bz - nz}, {bx + nx, y, bz + nz},
                {ax + nx, y, az + nz}}, uv);
    }

    /** A flat square on the floor. */
    private static void floorPatch(VertexConsumer vc, PoseStack.Pose p, int light, TextureAtlasSprite sprite, float cx, float cz, float h,
                                   float y) {
        float[][] uv = {{0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}, {0.0F, 0.0F}};
        polyUV(vc, p, light, sprite, 0.0F, 1.0F, 0.0F, new float[][]{{cx - h, y, cz - h}, {cx + h, y, cz - h}, {cx + h, y, cz + h},
                {cx - h, y, cz + h}}, uv);
    }

    /**
     * The Breaker's lava (solid, with the animated lava texture of the game): it pools round the feet of the pillars, where it
     * has run down from the hammers, and runs through charred cracks over the plaza into the Core. The cracks close up a little
     * in the later forms.
     */
    private static void drawLava(PoseStack pose, VertexConsumer vc, float time, float vp, float sc) {
        float wv = ease(Mth.clamp(vp - 1.0F, 0.0F, 1.0F));
        if (wv < 0.02F) {
            return;
        }
        PoseStack.Pose p = pose.last();
        TextureAtlasSprite lava = sprite(LAVA);
        TextureAtlasSprite char_ = sprite(INNER);
        int full = LightTexture.FULL_BRIGHT;
        int dim = LightTexture.pack(6, 15);
        float cool = 1.0F - toSoul(vp);
        if (cool < 0.05F) {
            return;
        }
        float disc = CoreEvolution.discAt(vp);
        float reach = (disc - 0.9F) * wv;
        float start = 0.72F * sc + 0.1F;
        for (int j = 0; j < 9; j++) {
            float a = j * 0.698F + 0.35F + 0.25F * Mth.sin(j * 2.7F);
            float r = start;
            float px = 0.5F + Mth.cos(a) * r;
            float pz = 0.5F + Mth.sin(a) * r;
            int step = 0;
            while (r < reach) {
                float nr = Math.min(reach, r + 0.6F + 0.2F * Mth.sin(j + step * 1.7F));
                a += 0.2F * Mth.sin(j * 5.1F + nr * 2.3F);
                float nx = 0.5F + Mth.cos(a) * nr;
                float nz = 0.5F + Mth.sin(a) * nr;
                float w = (0.075F - 0.035F * nr / Math.max(1.0F, reach)) * cool;
                floorStrip(vc, p, dim, char_, px, pz, nx, nz, w + 0.045F, 0.022F);
                floorStrip(vc, p, full, lava, px, pz, nx, nz, w, 0.028F);
                if (step % 3 == 1) {
                    float ba = a + (step % 2 == 0 ? 0.8F : -0.8F);
                    float bx = nx + Mth.cos(ba) * 0.6F;
                    float bz = nz + Mth.sin(ba) * 0.6F;
                    floorStrip(vc, p, dim, char_, nx, nz, bx, bz, w * 0.55F + 0.035F, 0.022F);
                    floorStrip(vc, p, full, lava, nx, nz, bx, bz, w * 0.55F, 0.028F);
                }
                if (step % 2 == 0) {
                    floorPatch(vc, p, full, lava, nx, nz, w * 1.25F, 0.031F);
                }
                px = nx;
                pz = nz;
                r = nr;
                step++;
            }
            if (step > 0) {
                floorPatch(vc, p, dim, char_, px, pz, 0.22F * cool, 0.021F);
                floorPatch(vc, p, full, lava, px, pz, 0.14F * cool, 0.03F);
            }
        }
        // pools round the feet of the pillars: the lava of the hammers runs down the shaft and collects here
        float height = CoreEvolution.pillarAt(vp);
        if (height > 1.0F) {
            float vr = CoreEvolution.pillarRadiusAt(vp);
            float s = pillarSize(vp);
            float wA = 1.0F - toSoul(vp);
            if (wA > 0.1F) {
                for (int i = 0; i < 4; i++) {
                    float cx = 0.5F + (i % 2 == 0 ? -vr : vr);
                    float cz = 0.5F + (i < 2 ? -vr : vr);
                    float in = 0.62F * s;
                    float out = in + 0.34F * s * wA;
                    float[][] boxes = {{cx - out, cx - in, cz - out, cz + out}, {cx + in, cx + out, cz - out, cz + out},
                            {cx - in, cx + in, cz - out, cz - in}, {cx - in, cx + in, cz + in, cz + out}};
                    for (float[] bx : boxes) {
                        float[][] uv = {{0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}, {0.0F, 0.0F}};
                        polyUV(vc, p, full, lava, 0.0F, 1.0F, 0.0F, new float[][]{{bx[0], 0.03F, bx[2]}, {bx[1], 0.03F, bx[2]}, {bx[1], 0.03F, bx[3]},
                                {bx[0], 0.03F, bx[3]}}, uv);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The bosses. Only the boss that was defeated last is active (it does its thing: the Elder Guardian floats and fires its
    // beam, the Warden climbs out of the ground, the Ender Dragon circles, the Wither hangs over the plaza). With the next
    // upgrade it shrinks and takes its place between two pillars, looks at the Core and sends its energy into the lid.
    // They are drawn by the game's own renderers (real models, textures and animations): the mod only places them.
    // ------------------------------------------------------------------

    private static final ResourceLocation ENDER_EYE = ResourceLocation.withDefaultNamespace("item/ender_eye");
    /** Colour of the beam of every boss: Elder Guardian (beige like its skin), Warden (its blue), Ender Dragon (violet), Wither (pale bone white: black cannot be drawn additively). */
    private static final float[][] BOSS_COL = {{0.82F, 0.74F, 0.56F}, {0.2F, 0.85F, 1.0F}, {0.78F, 0.42F, 1.0F}, {0.9F, 0.9F, 1.0F}};
    private static final float[] ACTIVE_SCALE = {0.9F, 0.9F, 0.55F, 1.2F};
    private static final float[] TROPHY_SCALE = {0.75F, 0.8F, 0.38F, 0.8F};
    /** Height of the middle of a boss above its feet, in units of its scale. */
    private static final float[] BOSS_MID = {1.0F, 1.45F, 1.0F, 1.7F};
    /** Radius of the cage of rings round a boss, in units of its scale. */
    private static final float[] CAGE_R = {1.8F, 1.5F, 3.3F, 1.75F};

    /** Height of the middle of the trophies that hang between pillars of the given height (above head height, the ways stay free). */
    private static float trophyMid(float height) {
        return Math.max(4.4F, 0.64F * height);
    }

    private static final class Bosses {
        Level level;
        final Entity[] e = new Entity[4];
        final boolean[] failed = new boolean[4];
        int walkTick = -1;
    }

    private static final Map<CoreBlockEntity, Bosses> BOSSES = new WeakHashMap<>();

    /** The place between two pillars where the trophy of boss b stands (x, z): north, east, south, west. */
    private static float[] gapAt(int b, float vr) {
        float sx = b == 1 ? 1.0F : b == 3 ? -1.0F : 0.0F;
        float sz = b == 2 ? 1.0F : b == 0 ? -1.0F : 0.0F;
        return new float[]{0.5F + sx * vr, 0.5F + sz * vr};
    }

    /** The two pillars (indices as in drawPillars) on either side of the gap of boss b. */
    private static int[] gapPillars(int b) {
        return b == 0 ? new int[]{0, 1} : b == 1 ? new int[]{1, 3} : b == 2 ? new int[]{2, 3} : new int[]{0, 2};
    }

    private static float lerpAngle(float t, float a, float b) {
        float d = Mth.wrapDegrees((b - a) * 57.29578F) / 57.29578F;
        return a + d * t;
    }

    /**
     * Where boss b stands: {x, y (feet), z, yaw, scale, appear, settle}. Every boss stands at once at its place between two
     * pillars (north Elder Guardian, east Warden, south Ender Dragon, west Wither), in a cage of rings, looking at the Core:
     * there is no roaming about, that would need far too much room. The scale is already multiplied by how far it has
     * appeared. {@code appear} runs 0..1 while the stage rises to the stage of the boss, {@code settle} 0..1 while the next
     * stage rises (the boss is no longer the active one: it only feeds the antenna).
     */
    private static float[] bossAt(int b, float time, float vs, float vr, float height) {
        float ap = ease(Mth.clamp(vs - b, 0.0F, 1.0F));
        float st = b == 3 ? 0.0F : ease(Mth.clamp(vs - (b + 1), 0.0F, 1.0F));
        float[] g = gapAt(b, vr);
        float tsc = TROPHY_SCALE[b];
        float ty = trophyMid(height) - BOSS_MID[b] * tsc + 0.12F * Mth.sin(time * 0.05F + b);
        float tyaw = (float) Math.atan2(0.5F - g[0], 0.5F - g[1]);
        return new float[]{g[0], ty, g[1], tyaw, tsc * Mth.clamp(ap * 2.0F, 0.0F, 1.0F), ap, st};
    }

    /** A point of a boss that faces along its yaw (own frame: x to the side, y up, z forwards) in the world. */
    private static float[] bossPoint(float[] a, float lx, float ly, float lz) {
        float cs = Mth.cos(a[3]);
        float sn = Mth.sin(a[3]);
        float sc = a[4];
        return new float[]{a[0] + (lx * cs + lz * sn) * sc, a[1] + ly * sc, a[2] + (-lx * sn + lz * cs) * sc};
    }

    /** A curly ribbon of energy from a to b (as the ribbons of the soul lanterns), with packets running along it. */
    private static void curl(Lines lines, List<float[]> packets, float time, float[] a, float[] b, float k, int seed, float r, float g, float bl) {
        if (k < 0.02F) {
            return;
        }
        final int n = 18;
        float[][] pts = new float[n + 1][];
        for (int m = 0; m <= n; m++) {
            float u = m / (float) n;
            float wob = Mth.sin(u * (float) Math.PI) * 0.55F;
            pts[m] = new float[]{a[0] + (b[0] - a[0]) * u + Mth.sin(time * 0.11F + u * 7.0F + seed * 1.7F) * wob,
                    a[1] + (b[1] - a[1]) * u + Mth.cos(time * 0.09F + u * 6.0F + seed) * wob * 0.8F,
                    a[2] + (b[2] - a[2]) * u + Mth.sin(time * 0.1F + u * 6.5F + seed * 2.3F + 1.0F) * wob};
        }
        for (int m = 0; m < n; m++) {
            float u = (m + 0.5F) / n;
            float kk = k * (0.45F + 0.55F * u);
            lines.add(pts[m][0], pts[m][1], pts[m][2], pts[m + 1][0], pts[m + 1][1], pts[m + 1][2], 0.08F, r * kk * 0.22F, g * kk * 0.22F, bl * kk * 0.22F);
            lines.add(pts[m][0], pts[m][1], pts[m][2], pts[m + 1][0], pts[m + 1][1], pts[m + 1][2], 0.024F + 0.012F * u, r * kk, g * kk, bl * kk);
        }
        for (int q = 0; q < 3; q++) {
            float u = (time * 0.012F + seed * 0.2F + q / 3.0F) % 1.0F;
            float[] at = pointAt(pts, u);
            packets.add(new float[]{at[0], at[1], at[2], k * Mth.sin(u * (float) Math.PI), r, g, bl, 0.2F});
        }
    }

    /** A point of the Ender Dragon (given in its own frame: x to its right, y up, z backwards) in the world. */
    private static float[] dragonPoint(float[] a, float lx, float ly, float lz) {
        float th = a[3] + (float) Math.PI;
        float cs = Mth.cos(th);
        float sn = Mth.sin(th);
        float sc = a[4];
        return new float[]{a[0] + (lx * cs + lz * sn) * sc, a[1] + ly * sc, a[2] + (-lx * sn + lz * cs) * sc};
    }

    /** The Warden's sonic boom: 0 most of the time, a swell between tick 30 and 75 of every 200 (the animation starts at 0). */
    private static float wardenPulse(float time) {
        float t = (time % 200.0F - 30.0F) / 45.0F;
        return t > 0.0F && t < 1.0F ? Mth.sin(t * (float) Math.PI) : 0.0F;
    }

    /** The Ender Dragon rears its head: the whole body is turned this far (degrees) about its middle. */
    private static final float DRAGON_PITCH = 38.0F;
    /** Its body's middle and the distance from there to the mouth, in the model's own units (from the vanilla model). */
    private static final float DRAGON_PIVOT_Y = 0.5F;
    private static final float DRAGON_PIVOT_Z = 2.0F;
    private static final float DRAGON_MOUTH = 6.0F;

    /** Where the mouth of the (reared) Ender Dragon is. */
    private static float[] dragonMouth(float[] a) {
        float th = DRAGON_PITCH * 0.017453292F;
        // measured against a screenshot: the snout lies a bit higher and farther out than the plain estimate
        return dragonPoint(a, 0.0F, DRAGON_PIVOT_Y + DRAGON_MOUTH * Mth.sin(th) + 0.8F, DRAGON_PIVOT_Z - DRAGON_MOUTH * Mth.cos(th) - 1.6F);
    }

    /** The beat of the Warden's heart: 1 as it beats (every 40 ticks, as in the game), then it fades within 10 ticks. */
    private static float heartBeat(float time) {
        float x = Mth.clamp(1.0F - (time % 40.0F) / 10.0F, 0.0F, 1.0F);
        return x * x;
    }

    private static java.lang.reflect.Field[] heartFields;

    /**
     * The heart of the Warden lights up when {@code heartAnimation} jumps to 10 and runs down to 0 (the game's own tick does that,
     * not on a dummy): set it from the time, so the glowing heart in its belly beats like in the game.
     */
    private static void beatWardenHeart(Warden warden, float time) {
        try {
            if (heartFields == null) {
                java.lang.reflect.Field now = Warden.class.getDeclaredField("heartAnimation");
                java.lang.reflect.Field old = Warden.class.getDeclaredField("heartAnimationO");
                now.setAccessible(true);
                old.setAccessible(true);
                heartFields = new java.lang.reflect.Field[]{old, now};
            }
            if (heartFields.length == 2) {
                int tick = (int) Math.floor(time);
                heartFields[0].setFloat(warden, Math.max(0.0F, 10.0F - tick % 40));
                heartFields[1].setFloat(warden, Math.max(0.0F, 10.0F - (tick + 1) % 40));
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            heartFields = new java.lang.reflect.Field[0];
        }
    }

    /** Where the energy of boss b leaves it (its eye, its head), given its placement. */
    private static float[] bossHead(int b, float[] a) {
        float s = a[4];
        float sn = Mth.sin(a[3]);
        float cs = Mth.cos(a[3]);
        if (b == 0) {
            return new float[]{a[0] + sn * 1.22F * s, a[1] + 1.175F * s, a[2] + cs * 1.22F * s};
        }
        if (b == 1) {
            // the Warden's heart: between the ribs on its belly, just in front of the chest
            return new float[]{a[0] + sn * 0.34F * s, a[1] + 1.4F * s, a[2] + cs * 0.34F * s};
        }
        if (b == 2) {
            return dragonMouth(a);
        }
        return new float[]{a[0], a[1] + 2.95F * s, a[2]}; // the middle head of the Wither
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void renderEntity(Entity entity, float partial, PoseStack pose, MultiBufferSource buffer, int light) {
        EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        renderer.render(entity, 0.0F, partial, pose, buffer, light);
    }

    private static Entity createBoss(Level level, int b) {
        EntityType<?> type = b == 0 ? EntityType.ELDER_GUARDIAN : b == 1 ? EntityType.WARDEN : b == 2 ? EntityType.ENDER_DRAGON : EntityType.WITHER;
        return type.create(level);
    }

    /** The bosses themselves, drawn by the renderers of the game. Nothing else may be held open while this runs. */
    private static void drawBosses(PoseStack pose, MultiBufferSource buffer, Level level, CoreBlockEntity core, float time, float partial,
                                   float vs, float vp, float yawCam) {
        Bosses bs = BOSSES.computeIfAbsent(core, k -> new Bosses());
        if (bs.level != level) {
            bs.level = level;
            for (int i = 0; i < 4; i++) {
                bs.e[i] = null;
                bs.failed[i] = false;
            }
        }
        float vr = CoreEvolution.pillarRadiusAt(vp);
        int light = LightTexture.pack(13, 15);
        for (int b = 0; b < 4; b++) {
            float[] a = bossAt(b, time, vs, vr, CoreEvolution.pillarAt(vp));
            if (a[5] < 0.02F || a[4] < 0.02F || bs.failed[b]) {
                continue;
            }
            Entity e = bs.e[b];
            pose.pushPose();
            try {
                if (e == null) {
                    e = createBoss(level, b);
                    bs.e[b] = e;
                    if (e == null) {
                        bs.failed[b] = true;
                        continue;
                    }
                }
                e.tickCount = (int) time;
                if (e instanceof LivingEntity living) {
                    float deg = -a[3] * 57.29578F;
                    living.yBodyRot = deg;
                    living.yBodyRotO = deg;
                    living.yHeadRot = deg;
                    living.yHeadRotO = deg;
                    living.setYRot(deg);
                    living.yRotO = deg;
                    living.setXRot(0.0F);
                    living.xRotO = 0.0F;
                    if (e instanceof WitherBoss wither) {
                        faceWitherHeads(wither, deg);
                    }
                }
                if (e instanceof Warden warden) {
                    // every 200 ticks it fires its sonic boom (the animation of the game itself) while it is the active boss
                    int phase = (int) time % 200;
                    boolean calm = a[6] > 0.5F || a[5] < 0.95F;
                    if ((int) time != bs.walkTick) {
                        bs.walkTick = (int) time;
                        warden.walkAnimation.update(0.0F, 0.4F); // it stands still in its cage
                    }
                    if (!calm && phase < 12 && !warden.sonicBoomAnimationState.isStarted()) {
                        warden.sonicBoomAnimationState.start((int) time);
                    } else if ((calm || phase >= 75) && warden.sonicBoomAnimationState.isStarted()) {
                        warden.sonicBoomAnimationState.stop();
                    }
                    // the roar (its chest opens, the heart shows) fills the second half of the round
                    if (!calm && phase >= 100 && phase < 108 && !warden.roarAnimationState.isStarted()) {
                        warden.roarAnimationState.start((int) time);
                    } else if ((calm || phase < 100 || phase >= 190) && warden.roarAnimationState.isStarted()) {
                        warden.roarAnimationState.stop();
                    }
                    beatWardenHeart(warden, time);
                }
                if (e instanceof EnderDragon dragon) {
                    float flap = time * 0.12F;
                    dragon.oFlapTime = flap;
                    dragon.flapTime = flap;
                }
                pose.translate(a[0], a[1], a[2]);
                if (b == 2) {
                    pose.mulPose(Axis.YP.rotation(a[3] + (float) Math.PI)); // the dragon's head points the other way
                }
                pose.scale(a[4], a[4], a[4]);
                if (b == 2) {
                    // the Dragon rears its head: it breathes its energy into the antenna
                    pose.translate(0.0F, DRAGON_PIVOT_Y, DRAGON_PIVOT_Z);
                    pose.mulPose(Axis.XP.rotationDegrees(DRAGON_PITCH));
                    pose.translate(0.0F, -DRAGON_PIVOT_Y, -DRAGON_PIVOT_Z);
                }
                renderEntity(e, partial, pose, buffer, light);
            } catch (RuntimeException ex) {
                bs.failed[b] = true; // never take the game down because of a decoration
            } finally {
                pose.popPose();
            }
        }
    }

    private static java.lang.reflect.Field[] witherFields;

    /**
     * The two outer heads of the Wither turn by (head rotation - body rotation); on a dummy nobody updates the head rotation,
     * so they would look to the side. They are set to the body's own direction (the fields are private: reflection, and if
     * it does not work the heads simply stay as they are).
     */
    private static void faceWitherHeads(WitherBoss wither, float deg) {
        try {
            if (witherFields == null) {
                String[] names = {"yRotHeads", "yRotOHeads", "xRotHeads", "xRotOHeads"};
                java.lang.reflect.Field[] fields = new java.lang.reflect.Field[names.length];
                for (int i = 0; i < names.length; i++) {
                    fields[i] = WitherBoss.class.getDeclaredField(names[i]);
                    fields[i].setAccessible(true);
                }
                witherFields = fields;
            }
            for (int i = 0; i < witherFields.length; i++) {
                float[] array = (float[]) witherFields[i].get(wither);
                for (int k = 0; k < array.length; k++) {
                    array[k] = i < 2 ? deg : 0.0F;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            witherFields = new java.lang.reflect.Field[0]; // not possible here: leave the heads alone
        }
    }

    /**
     * The cages (solid, gold): three rings turn about every boss, active or defeated. They are the prison of the active
     * bosses; the defeated ones between the pillars are also held by gold chains (see drawCageChains).
     */
    private static void drawCages(PoseStack pose, VertexConsumer vc, float time, float vs, float vp) {
        float vr = CoreEvolution.pillarRadiusAt(vp);
        float height = CoreEvolution.pillarAt(vp);
        TextureAtlasSprite gold = sprite(GOLD);
        int light = LightTexture.pack(12, 15);
        PoseStack.Pose p = pose.last();
        for (int b = 0; b < 4; b++) {
            float[] a = bossAt(b, time, vs, vr, height);
            float r = CAGE_R[b] * a[4] * Mth.clamp(a[5] * 1.5F, 0.0F, 1.0F);
            if (r < 0.1F) {
                continue;
            }
            float cy = a[1] + BOSS_MID[b] * a[4];
            float rot = time * 0.03F + b;
            float w = 0.018F + 0.012F * r;
            int n = r > 2.5F ? 28 : 18;
            ring(vc, p, light, gold, a[0], cy, a[2], r, 0.45F, rot, n, w);
            ring(vc, p, light, gold, a[0], cy, a[2], r * 1.12F, -0.45F, -rot * 0.8F, n, w);
            ring(vc, p, light, gold, a[0], cy, a[2], r * 1.24F, 1.5708F, rot * 0.6F, n, w);
        }
    }

    /**
     * The gold chains that hold the bosses: every pillar next to a boss carries a gold bracket with an eyelet on the face that
     * looks at it, and two taut chains run from there to the nearest points of the rings of the cage. They grow out of the
     * brackets while the boss appears.
     */
    private static void drawCageChains(PoseStack pose, VertexConsumer vc, float time, float vs, float vp) {
        float vr = CoreEvolution.pillarRadiusAt(vp);
        float height = CoreEvolution.pillarAt(vp);
        if (height < 1.0F) {
            return;
        }
        TextureAtlasSprite gold = sprite(GOLD);
        int light = LightTexture.pack(13, 15);
        PoseStack.Pose p = pose.last();
        float hw = shaftHalf(vp);
        float yb = Math.max(0.9F, height - 1.7F - 0.72F);
        for (int b = 0; b < 4; b++) {
            float[] a = bossAt(b, time, vs, vr, height);
            if (a[5] < 0.03F || a[4] < 0.05F) {
                continue;
            }
            float k = Mth.clamp(a[5] * 1.5F, 0.0F, 1.0F);
            float r = CAGE_R[b] * a[4] * k;
            float mx = a[0];
            float my = a[1] + BOSS_MID[b] * a[4];
            float mz = a[2];
            int rn = r > 2.5F ? 28 : 18;
            float fit = 0.5F * (1.0F + Mth.cos((float) Math.PI / rn)); // the rings are polygons: a little inside the circle
            float[] radii = {r * fit, r * 1.12F * fit, r * 1.24F * fit};
            float[] tilts = {0.45F, -0.45F, 1.5708F};
            for (int pil : gapPillars(b)) {
                float cx = 0.5F + (pil % 2 == 0 ? -vr : vr);
                float cz = 0.5F + (pil < 2 ? -vr : vr);
                // the bracket sits on the face of the shaft that looks at the boss
                float ox = mx - cx;
                float oz = mz - cz;
                float ux = Math.abs(ox) >= Math.abs(oz) ? Math.signum(ox) : 0.0F;
                float uz = ux == 0.0F ? Math.signum(oz) : 0.0F;
                float sc = Mth.clamp(a[5] * 3.0F, 0.0F, 1.0F);
                float f0 = hw - 0.01F;
                float f1 = hw + 0.075F * sc;
                float tw = 0.17F * sc;
                float th = 0.2F * sc;
                float xa = cx + ux * f0;
                float xb = cx + ux * f1;
                float za = cz + uz * f0;
                float zb = cz + uz * f1;
                float tx0 = ux != 0.0F ? Math.min(xa, xb) : cx - tw;
                float tx1 = ux != 0.0F ? Math.max(xa, xb) : cx + tw;
                float tz0 = uz != 0.0F ? Math.min(za, zb) : cz - tw;
                float tz1 = uz != 0.0F ? Math.max(za, zb) : cz + tw;
                box(vc, p, light, gold, 4, tx0, tx1, yb - th, yb + th, tz0, tz1);
                // a collar round the shaft, so the bracket is held like a clamp
                box(vc, p, light, gold, 4, cx - hw - 0.03F, cx + hw + 0.03F, yb + th - 0.03F, yb + th + 0.04F, cz - hw - 0.03F, cz + hw + 0.03F);
                box(vc, p, light, gold, 4, cx - hw - 0.03F, cx + hw + 0.03F, yb - th - 0.04F, yb - th + 0.03F, cz - hw - 0.03F, cz + hw + 0.03F);
                // the eyelet the chains run through
                float er = 0.1F * sc;
                float ex = cx + ux * (f1 + er);
                float ez = cz + uz * (f1 + er);
                float[] prev = null;
                for (int m = 0; m <= 12; m++) {
                    float ang = m * (float) (Math.PI * 2.0) / 12.0F;
                    float lo = Mth.cos(ang) * er;
                    float[] q = {ex + ux * lo, yb + Mth.sin(ang) * er, ez + uz * lo};
                    if (prev != null) {
                        beam(vc, p, light, gold, prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.024F);
                    }
                    prev = q;
                }
                // the two nearest rings of the cage; the chain ends right on them
                float[][] ends = new float[3][];
                float[] dist = new float[3];
                for (int m = 0; m < 3; m++) {
                    ends[m] = ringNearest(mx, my, mz, radii[m], tilts[m], ex, yb, ez);
                    float dx = ends[m][0] - ex;
                    float dy = ends[m][1] - yb;
                    float dz = ends[m][2] - ez;
                    dist[m] = dx * dx + dy * dy + dz * dz;
                }
                int first = 0;
                for (int m = 1; m < 3; m++) {
                    if (dist[m] < dist[first]) {
                        first = m;
                    }
                }
                int second = first == 0 ? 1 : 0;
                for (int m = 0; m < 3; m++) {
                    if (m != first && dist[m] < dist[second]) {
                        second = m;
                    }
                }
                int[] pick = {first, second};
                for (int c = 0; c < 2; c++) {
                    float[] e = ends[pick[c]];
                    float sy = yb + (c == 0 ? 0.045F : -0.045F) * sc;
                    float span = Mth.sqrt((e[0] - ex) * (e[0] - ex) + (e[1] - sy) * (e[1] - sy) + (e[2] - ez) * (e[2] - ez));
                    chainBetween(vc, p, light, gold, ex, sy, ez, e[0], e[1], e[2], 0.012F * span, time, a[5]);
                }
            }
        }
    }

    /** The point of a (tilted about the x axis) ring that is nearest to (px, py, pz). */
    private static float[] ringNearest(float cx, float cy, float cz, float radius, float tilt, float px, float py, float pz) {
        float sn = Mth.sin(tilt);
        float cs = Mth.cos(tilt);
        float u = px - cx;
        float v = (py - cy) * sn + (pz - cz) * cs;
        float n = Mth.sqrt(u * u + v * v);
        if (n < 1.0E-4F) {
            u = 1.0F;
            v = 0.0F;
            n = 1.0F;
        }
        float lx = u / n * radius;
        float lz = v / n * radius;
        return new float[]{cx + lx, cy + lz * sn, cz + lz * cs};
    }

    private static final float LINK_LENGTH = 0.22F;
    private static final float LINK_WIDTH = 0.11F;

    /** A hanging chain of real links from a to b (it sags in the middle); {@code frac} is how much of it is there, from a on. */
    private static void chainBetween(VertexConsumer vc, PoseStack.Pose p, int light, TextureAtlasSprite gold, float ax, float ay,
                                     float az, float bx, float by, float bz, float sag, float time, float frac) {
        float span = Mth.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        int n = Math.max(4, (int) (span / 0.15F));
        int shown = (int) (n * Mth.clamp(frac, 0.0F, 1.0F));
        for (int k = 0; k < shown; k++) {
            float[] c = chainPoint(ax, ay, az, bx, by, bz, (k + 0.5F) / n, sag, time, k);
            float[] c0 = chainPoint(ax, ay, az, bx, by, bz, k / (float) n, sag, time, k);
            float[] c1 = chainPoint(ax, ay, az, bx, by, bz, (k + 1.0F) / n, sag, time, k);
            chainLink(vc, p, light, gold, c[0], c[1], c[2], c1[0] - c0[0], c1[1] - c0[1], c1[2] - c0[2], k % 2 == 0);
        }
    }

    /**
     * One link of a chain: a flat oval ring of four thin bars round (cx, cy, cz), its long side along (tx, ty, tz). The links
     * alternate: one lies flat, the next stands on its edge, as in a real chain.
     */
    private static void chainLink(VertexConsumer vc, PoseStack.Pose p, int light, TextureAtlasSprite gold, float cx, float cy, float cz,
                                  float tx, float ty, float tz, boolean flat) {
        float tl = Mth.sqrt(tx * tx + ty * ty + tz * tz);
        if (tl < 1.0E-4F) {
            return;
        }
        tx /= tl;
        ty /= tl;
        tz /= tl;
        float n1x = -tz;
        float n1z = tx;
        float nl = Mth.sqrt(n1x * n1x + n1z * n1z);
        if (nl < 1.0E-3F) {
            n1x = 1.0F;
            n1z = 0.0F;
            nl = 1.0F;
        }
        n1x /= nl;
        n1z /= nl;
        // n2 = t x n1 (n1 has no y part)
        float n2x = -tz * 0.0F + ty * n1z;
        float n2y = tz * n1x - tx * n1z;
        float n2z = -ty * n1x;
        float ux = flat ? n1x : n2x;
        float uy = flat ? 0.0F : n2y;
        float uz = flat ? n1z : n2z;
        float hl = LINK_LENGTH * 0.5F;
        float hw = LINK_WIDTH * 0.5F;
        float bar = 0.014F;
        for (int side = -1; side <= 1; side += 2) {
            beam(vc, p, light, gold, cx - tx * hl + ux * hw * side, cy - ty * hl + uy * hw * side, cz - tz * hl + uz * hw * side,
                    cx + tx * hl + ux * hw * side, cy + ty * hl + uy * hw * side, cz + tz * hl + uz * hw * side, bar);
            beam(vc, p, light, gold, cx + tx * hl * side - ux * hw, cy + ty * hl * side - uy * hw, cz + tz * hl * side - uz * hw,
                    cx + tx * hl * side + ux * hw, cy + ty * hl * side + uy * hw, cz + tz * hl * side + uz * hw, bar);
        }
    }

    /** A point of a sagging chain from (ax, ay, az) to (bx, by, bz), u = 0..1. */
    private static float[] chainPoint(float ax, float ay, float az, float bx, float by, float bz, float u, float sag, float time, int k) {
        return new float[]{ax + (bx - ax) * u, ay + (by - ay) * u - sag * 4.0F * u * (1.0F - u) + 0.01F * Mth.sin(time * 0.07F + k), az + (bz - az) * u};
    }

    /** u0 (0), u1 (1), v0 (2), v1 (3) of a sprite. */
    private static float sprite0(TextureAtlasSprite s, int which) {
        return which == 0 ? s.getU0() : which == 1 ? s.getU1() : which == 2 ? s.getV0() : s.getV1();
    }

    // --- restored helpers ---



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


    // --- energy lines: arcs, flows, a woven net, a heartbeat ---

    /** Height of the middle of the Wither's ribs above the plaza (stage 4). */
    private static final float WITHER_Y = 13.0F;

    private static float witherBob(float time) {
        return 0.18F * Mth.sin(time * 0.03F);
    }

    private static float beatPhase(float time, int seed) {
        return (time * 0.021F + seed * 0.25F) % 1.0F;
    }

    /** A double heartbeat: a strong thump and, shortly after, a weaker one. */
    private static float beatPulse(float ph) {
        float a = (float) Math.exp(-ph * 16.0F);
        float b = ph > 0.2F ? 0.6F * (float) Math.exp(-(ph - 0.2F) * 16.0F) : 0.0F;
        return Math.max(a, b);
    }

    /** A point at (lx, lz) in the frame of something that stands at (ax, az) and looks along yaw. */
    private static float[] yawPoint(float ax, float az, float yaw, float lx, float lz) {
        float s = Mth.sin(yaw);
        float c = Mth.cos(yaw);
        return new float[]{ax + lx * c + lz * s, az - lx * s + lz * c};
    }

    /** Point on chain i, f = 0 at the corner of the Sarcophagus, 1 at the anchor (same curve as drawChains). */
    private static float[] chainAt(int i, float scale, float vs, float time, float f) {
        float sx = i % 2 == 0 ? -1.0F : 1.0F;
        float sz = i < 2 ? -1.0F : 1.0F;
        float tx = 0.5F + sx * 0.5F * scale;
        float ty = TIP * scale;
        float tz = 0.5F + sz * 0.5F * scale;
        float[] an = anchorAt(i, scale, vs);
        return new float[]{tx + (an[0] - tx) * f,
                ty + (0.06F - ty) * f - 0.10F * Mth.sin(f * (float) Math.PI) + 0.012F * Mth.sin(time * 0.07F + f * 14.0F + i),
                tz + (an[1] - tz) * f};
    }

    /** A strand with a soft glow round it: a wide dim line, a coloured one and a thin white-hot core. */
    private static void strandSeg(Lines lines, float[] a, float[] b, float w, float k, float r, float g, float bl) {
        lines.add(a[0], a[1], a[2], b[0], b[1], b[2], w * 3.4F, r * k * 0.24F, g * k * 0.24F, bl * k * 0.24F);
        lines.add(a[0], a[1], a[2], b[0], b[1], b[2], w * 1.5F, r * k * 0.8F, g * k * 0.8F, bl * k * 0.8F);
        lines.add(a[0], a[1], a[2], b[0], b[1], b[2], w * 0.55F, Math.min(1.0F, 0.55F + r * 0.45F) * k, Math.min(1.0F, 0.55F + g * 0.45F) * k,
                Math.min(1.0F, 0.55F + bl * 0.45F) * k);
    }

    private static float dist3(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        float dz = a[2] - b[2];
        return Mth.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A point at fraction u of the length of a polyline. */
    private static float[] pointAt(float[][] pts, float u) {
        float total = 0.0F;
        for (int m = 0; m + 1 < pts.length; m++) {
            total += dist3(pts[m], pts[m + 1]);
        }
        float want = Mth.clamp(u, 0.0F, 1.0F) * total;
        for (int m = 0; m + 1 < pts.length; m++) {
            float seg = dist3(pts[m], pts[m + 1]);
            if (want <= seg || m + 2 == pts.length) {
                float f = seg < 1.0E-4F ? 0.0F : Mth.clamp(want / seg, 0.0F, 1.0F);
                return new float[]{pts[m][0] + (pts[m + 1][0] - pts[m][0]) * f, pts[m][1] + (pts[m + 1][1] - pts[m][1]) * f,
                        pts[m][2] + (pts[m + 1][2] - pts[m][2]) * f};
            }
            want -= seg;
        }
        return pts[pts.length - 1];
    }

    /** A polyline of energy with two packets running along it from its first to its last point. */
    private static void flow(Lines lines, List<float[]> packets, float time, float[][] pts, float k, float r, float g, float b, int seed) {
        for (int m = 0; m + 1 < pts.length; m++) {
            strandSeg(lines, pts[m], pts[m + 1], 0.007F, k, r, g, b);
        }
        for (int q = 0; q < 2; q++) {
            float u = (time * 0.016F + seed * 0.21F + q * 0.5F) % 1.0F;
            float[] at = pointAt(pts, u);
            packets.add(new float[]{at[0], at[1], at[2], k * Mth.sin(u * (float) Math.PI), r, g, b, 0.17F});
        }
    }

    /**
     * An electric arc from a to b: a jagged, wriggling line that jumps to a new shape every three ticks (amp: how far it
     * leaves the straight line in the middle).
     */
    private static void zap(Lines lines, List<float[]> packets, float time, float ax, float ay, float az, float bx, float by,
                            float bz, float amp, float k, float r, float g, float b, int seed) {
        float dx = bx - ax;
        float dy = by - ay;
        float dz = bz - az;
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.05F) {
            return;
        }
        int n = Mth.clamp((int) (len * 2.5F), 6, 30);
        float ux = -dz;
        float uz = dx;
        float ul = Mth.sqrt(ux * ux + uz * uz);
        if (ul < 1.0E-3F) {
            ux = 1.0F;
            uz = 0.0F;
            ul = 1.0F;
        }
        ux /= ul;
        uz /= ul;
        float tx = dx / len;
        float ty = dy / len;
        float tz = dz / len;
        float vx = ty * uz;
        float vy = tz * ux - tx * uz;
        float vz = -ty * ux;
        int frame = (int) (time / 3.0F);
        float[] prev = {ax, ay, az};
        float[][] pts = new float[n + 1][];
        pts[0] = prev;
        for (int m = 1; m <= n; m++) {
            float u = m / (float) n;
            float env = Mth.sin(u * (float) Math.PI);
            float j1 = (rnd(frame * 7 + seed, m) - 0.5F) * 2.0F * amp * env;
            float j2 = (rnd(frame * 7 + seed, m + 50) - 0.5F) * 2.0F * amp * env;
            float w1 = Mth.sin(time * 0.2F + u * 9.0F + seed) * amp * 0.4F * env;
            float[] q = m == n ? new float[]{bx, by, bz}
                    : new float[]{ax + dx * u + ux * (j1 + w1) + vx * j2, ay + dy * u + vy * j2, az + dz * u + uz * (j1 + w1) + vz * j2};
            strandSeg(lines, prev, q, 0.0062F, k, r, g, b);
            pts[m] = q;
            prev = q;
        }
        float u = (time * 0.03F + seed * 0.31F) % 1.0F;
        float[] at = pointAt(pts, u);
        packets.add(new float[]{at[0], at[1], at[2], k * 0.9F, r, g, b, 0.2F});
    }

    /** Two strands that braid round each other between a and b and sag in the middle (the Soul Weaver's threads). */
    private static void weave(Lines lines, List<float[]> packets, float time, float[] a, float[] b, float k, int seed) {
        final int n = 18;
        float dx = b[0] - a[0];
        float dz = b[2] - a[2];
        float dl = Math.max(0.01F, Mth.sqrt(dx * dx + dz * dz));
        float px = -dz / dl;
        float pz = dx / dl;
        float[][][] strands = new float[2][n + 1][];
        for (int s = 0; s < 2; s++) {
            for (int m = 0; m <= n; m++) {
                float u = m / (float) n;
                float env = Mth.sin(u * (float) Math.PI);
                float ph = u * 20.0F + time * 0.08F + s * (float) Math.PI;
                float v = Mth.sin(ph) * 0.15F * env;
                float h = Mth.cos(ph) * 0.15F * env;
                strands[s][m] = new float[]{a[0] + dx * u + px * h, a[1] + (b[1] - a[1]) * u - 0.7F * env + v, a[2] + dz * u + pz * h};
            }
        }
        for (int s = 0; s < 2; s++) {
            for (int m = 0; m < n; m++) {
                lines.add(strands[s][m][0], strands[s][m][1], strands[s][m][2], strands[s][m + 1][0], strands[s][m + 1][1], strands[s][m + 1][2],
                        0.011F, 0.55F * k, 0.32F * k, 1.0F * k);
                lines.add(strands[s][m][0], strands[s][m][1], strands[s][m][2], strands[s][m + 1][0], strands[s][m + 1][1], strands[s][m + 1][2],
                        0.0035F, 0.95F * k, 0.85F * k, 1.0F * k);
            }
        }
        float u = (time * 0.012F + seed * 0.27F) % 1.0F;
        float[] at = pointAt(strands[0], u);
        packets.add(new float[]{at[0], at[1], at[2], k * Mth.sin(u * (float) Math.PI), 0.8F, 0.55F, 1.0F, 0.18F});
    }

    /** z of the front of the sentinel's hood and cloak at height y (local to the statue), for things that sit on its face. */
    private static float hoodZ(float y) {
        return y >= 1.80F && y <= 2.0F ? 0.222F : y >= 1.74F ? 0.206F : y >= 1.50F ? 0.196F : 0.18F;
    }

    /** A curved beam from a to b (the middle is raised by {@code lift}) with packets that run along it, a thick, soft strand. */
    private static void arcBeam(Lines lines, List<float[]> packets, float time, float[] a, float[] b, float lift, float w, float k, float r,
                                float g, float bl, int seed) {
        if (k < 0.02F) {
            return;
        }
        final int n = 16;
        float[][] pts = new float[n + 1][];
        for (int m = 0; m <= n; m++) {
            float u = m / (float) n;
            pts[m] = new float[]{a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u + lift * 4.0F * u * (1.0F - u), a[2] + (b[2] - a[2]) * u};
        }
        for (int m = 0; m < n; m++) {
            strandSeg(lines, pts[m], pts[m + 1], w, k, r, g, bl);
        }
        for (int q = 0; q < 3; q++) {
            float u = (time * 0.014F + seed * 0.21F + q / 3.0F) % 1.0F;
            float[] at = pointAt(pts, u);
            packets.add(new float[]{at[0], at[1], at[2], k * Mth.sin(u * (float) Math.PI), r, g, bl, 0.22F});
        }
    }

    /**
     * A thick hose of energy from a to b that pumps: bulges run along it from a to b, the way the energy flows. {@code mouth}:
     * how much wider it is at a than at b.
     */
    private static void hose(Lines lines, List<float[]> packets, float time, float[] a, float[] b, float lift, float w, float mouth,
                             float k, float r, float g, float bl, int seed) {
        float[] col = {r, g, bl};
        hoseColor(lines, packets, time, a, b, lift, w, mouth, k, u -> col, seed);
    }

    /** Like {@link #hose}, but the colour changes along the hose: {@code col} gives it at u (0 at a, 1 at b). */
    private static void hoseColor(Lines lines, List<float[]> packets, float time, float[] a, float[] b, float lift, float w, float mouth,
                                  float k, java.util.function.Function<Float, float[]> col, int seed) {
        if (k < 0.02F) {
            return;
        }
        final int n = 24;
        float[][] pts = new float[n + 1][];
        for (int m = 0; m <= n; m++) {
            float u = m / (float) n;
            pts[m] = new float[]{a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u + lift * 4.0F * u * (1.0F - u), a[2] + (b[2] - a[2]) * u};
        }
        for (int m = 0; m < n; m++) {
            float u = (m + 0.5F) / n;
            float[] c = col.apply(u);
            float r = c[0];
            float g = c[1];
            float bl = c[2];
            float wide = 1.0F + (mouth - 1.0F) * (1.0F - u) * (1.0F - u);
            float ph = Mth.sin((u * 3.0F - time * 0.03F + seed * 0.13F) * (float) (Math.PI * 2.0));
            float ww = w * wide * (0.78F + 0.32F * ph);
            float kk = k * (0.6F + 0.4F * (0.5F + 0.5F * ph));
            float[] p0 = pts[m];
            float[] p1 = pts[m + 1];
            lines.add(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], ww * 2.1F, r * kk * 0.2F, g * kk * 0.2F, bl * kk * 0.2F);
            lines.add(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], ww * 1.25F, r * kk * 0.6F, g * kk * 0.6F, bl * kk * 0.6F);
            lines.add(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], ww * 0.5F, Math.min(1.0F, 0.6F + 0.4F * r) * kk,
                    Math.min(1.0F, 0.6F + 0.4F * g) * kk, Math.min(1.0F, 0.6F + 0.4F * bl) * kk);
        }
        for (int c = 0; c < 3; c++) {
            float u = ((0.25F + c + time * 0.03F - seed * 0.13F) / 3.0F) % 1.0F;
            if (u < 0.0F) {
                u += 1.0F;
            }
            float[] at = pointAt(pts, u);
            float[] cc = col.apply(u);
            packets.add(new float[]{at[0], at[1], at[2], k * 0.8F * Mth.sin(u * (float) Math.PI), cc[0], cc[1], cc[2], 0.5F * w + 0.1F});
        }
        float[] c0 = col.apply(0.0F);
        float[] c1 = col.apply(1.0F);
        packets.add(new float[]{a[0], a[1], a[2], 0.5F * k, c0[0], c0[1], c0[2], 0.9F * w + 0.2F});
        packets.add(new float[]{b[0], b[1], b[2], 0.6F * k, c1[0], c1[1], c1[2], 0.9F * w + 0.2F});
    }

    /** Purple fire in the mouth of the Dragon: tongues of flame that lick along the beam, and a glow. */
    private static void dragonFire(Lines lines, List<float[]> packets, float time, float[] head, float[] tip, float k) {
        if (k < 0.03F) {
            return;
        }
        float dx = tip[0] - head[0];
        float dy = tip[1] - head[1];
        float dz = tip[2] - head[2];
        float len = Math.max(0.01F, Mth.sqrt(dx * dx + dy * dy + dz * dz));
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;
        for (int i = 0; i < 7; i++) {
            float ph = time * 0.33F + i * 1.7F;
            float l = 0.3F + 0.4F * (0.5F + 0.5F * Mth.sin(ph * 1.3F));
            float ox = Mth.sin(ph) * 0.16F;
            float oy = Mth.cos(ph * 1.1F + i) * 0.16F;
            float oz = Mth.sin(ph * 0.9F + 2.0F * i) * 0.16F;
            float kk = k * (0.55F + 0.45F * Mth.sin(ph * 2.1F + i));
            float ex = head[0] + nx * l + ox;
            float ey = head[1] + ny * l + oy + 0.08F;
            float ez = head[2] + nz * l + oz;
            lines.add(head[0], head[1], head[2], ex, ey, ez, 0.16F, 0.5F * kk * 0.25F, 0.15F * kk * 0.25F, 0.9F * kk * 0.25F);
            lines.add(head[0], head[1], head[2], ex, ey, ez, 0.06F, 0.85F * kk, 0.4F * kk, 1.0F * kk);
        }
        packets.add(new float[]{head[0], head[1], head[2], k * (0.75F + 0.25F * Mth.sin(time * 0.4F)), 0.85F, 0.35F, 1.0F, 0.5F});
    }

    /** The two outer heads of the Wither send smaller beams that run into the main beam. */
    private static void witherSides(Lines lines, List<float[]> packets, float time, float[] a, float[] head, float[] tip, float k,
                                    float[] bc, int seed) {
        float s = a[4];
        float sn = Mth.sin(a[3]);
        float cs = Mth.cos(a[3]);
        // the main hose arcs upwards (lift 0.5): its height at 35 % is the straight line plus 0.5 * 4 * u * (1 - u)
        float u = 0.35F;
        float[] join = {head[0] + (tip[0] - head[0]) * u, head[1] + (tip[1] - head[1]) * u + 0.5F * 4.0F * u * (1.0F - u),
                head[2] + (tip[2] - head[2]) * u};
        for (int side = -1; side <= 1; side += 2) {
            // the outer heads of the model sit at 10 px (its left) and 8 px (its right) from the middle, at twice the size
            float off = (side > 0 ? 1.25F : 1.0F) * s;
            float[] from = {a[0] + cs * off * side, a[1] + 2.5F * s, a[2] - sn * off * side};
            hose(lines, packets, time, from, join, 0.0F, 0.085F, 1.3F, k * 0.9F, bc[0], bc[1], bc[2], seed + side);
        }
    }

    /** Energy that is sucked out of a (the dragon) into b (a crystal): a thick pumping hose, wide at the dragon. */
    private static void siphon(Lines lines, List<float[]> packets, float time, float[] a, float[] b, float k, float r, float g, float bl, int seed) {
        hose(lines, packets, time, a, b, 1.5F, 0.34F, 1.8F, k, r, g, bl, seed);
    }

    /** A beam that twists about itself (the beam of the Elder Guardian), from a to b. */
    private static void twistBeam(Lines lines, List<float[]> packets, float time, float[] a, float[] b, float k, float r, float g, float bl) {
        if (k < 0.02F) {
            return;
        }
        final int n = 26;
        float[][] pts = new float[n + 1][];
        for (int m = 0; m <= n; m++) {
            float u = m / (float) n;
            pts[m] = new float[]{a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u + 0.35F * 4.0F * u * (1.0F - u), a[2] + (b[2] - a[2]) * u};
        }
        for (int m = 0; m < n; m++) {
            lines.add(pts[m][0], pts[m][1], pts[m][2], pts[m + 1][0], pts[m + 1][1], pts[m + 1][2], 0.16F, r * k * 0.18F, g * k * 0.18F, bl * k * 0.18F);
            lines.add(pts[m][0], pts[m][1], pts[m][2], pts[m + 1][0], pts[m + 1][1], pts[m + 1][2], 0.035F, (0.55F + 0.45F * r) * k, (0.55F + 0.45F * g) * k, (0.55F + 0.45F * bl) * k);
        }
        for (int strand = 0; strand < 2; strand++) {
            float[] prev = pts[0];
            for (int m = 1; m <= n; m++) {
                float u = m / (float) n;
                float tx = pts[m][0] - pts[m - 1][0];
                float ty = pts[m][1] - pts[m - 1][1];
                float tz = pts[m][2] - pts[m - 1][2];
                float tl = Math.max(1.0E-4F, Mth.sqrt(tx * tx + ty * ty + tz * tz));
                tx /= tl;
                ty /= tl;
                tz /= tl;
                float e1x = ty * 0.0F - tz * 1.0F;
                float e1y = tz * 0.0F - tx * 0.0F;
                float e1z = tx * 1.0F - ty * 0.0F;
                float e1l = Math.max(1.0E-4F, Mth.sqrt(e1x * e1x + e1y * e1y + e1z * e1z));
                e1x /= e1l;
                e1y /= e1l;
                e1z /= e1l;
                float e2x = ty * e1z - tz * e1y;
                float e2y = tz * e1x - tx * e1z;
                float e2z = tx * e1y - ty * e1x;
                float ph = u * 22.0F - time * 0.3F + strand * (float) Math.PI;
                float rad = 0.2F * Mth.sin(u * (float) Math.PI);
                float[] q = {pts[m][0] + (e1x * Mth.cos(ph) + e2x * Mth.sin(ph)) * rad, pts[m][1] + (e1y * Mth.cos(ph) + e2y * Mth.sin(ph)) * rad,
                        pts[m][2] + (e1z * Mth.cos(ph) + e2z * Mth.sin(ph)) * rad};
                lines.add(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.03F, r * k, g * k, bl * k);
                prev = q;
            }
        }
        for (int q = 0; q < 3; q++) {
            float u = (time * 0.02F + q * 0.34F) % 1.0F;
            float[] at = pointAt(pts, u);
            packets.add(new float[]{at[0], at[1], at[2], k * Mth.sin(u * (float) Math.PI), r, g, bl, 0.2F});
        }
    }

    /** Rings of sound (the Warden's sonic boom) that run from a to b and shrink on the way. */
    private static void sonicRings(Lines lines, float time, float[] a, float[] b, float k, float r, float g, float bl) {
        if (k < 0.02F) {
            return;
        }
        float dx = b[0] - a[0];
        float dy = b[1] - a[1];
        float dz = b[2] - a[2];
        float len = Math.max(0.01F, Mth.sqrt(dx * dx + dy * dy + dz * dz));
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;
        float e1x = -nz;
        float e1z = nx;
        float e1l = Math.max(1.0E-3F, Mth.sqrt(e1x * e1x + e1z * e1z));
        e1x /= e1l;
        e1z /= e1l;
        float e2x = ny * e1z;
        float e2y = nz * e1x - nx * e1z;
        float e2z = -ny * e1x;
        for (int ringNo = 0; ringNo < 4; ringNo++) {
            float u = (time * 0.017F + ringNo * 0.25F) % 1.0F;
            float rad = 0.9F - 0.7F * u;
            float kk = k * Mth.sin(u * (float) Math.PI);
            float cx = a[0] + dx * u;
            float cy = a[1] + dy * u;
            float cz = a[2] + dz * u;
            float[] prev = null;
            for (int m = 0; m <= 18; m++) {
                float ang = m * (float) (Math.PI * 2.0) / 18.0F;
                float cs = Mth.cos(ang) * rad;
                float sn = Mth.sin(ang) * rad;
                float[] q = {cx + e1x * cs + e2x * sn, cy + e2y * sn, cz + e1z * cs + e2z * sn};
                if (prev != null) {
                    lines.add(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.05F, r * kk * 0.35F, g * kk * 0.35F, bl * kk * 0.35F);
                    lines.add(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.016F, r * kk, g * kk, bl * kk);
                }
                prev = q;
            }
        }
    }

    /**
     * Everything that glows outside the Sarcophagus (blended, never added): the energy of the bosses on its way into the Core.
     * The boss that was defeated last is active: 5 the Elder Guardian fires its twisted beam, 10 the Warden sends rings of
     * sound, 15 the Ender Dragon is drained into the crystals, whose energy runs down the pillars and out of the Eyes of
     * Ender, 20 the Wither draws up souls and a funnel leads them in. Every boss before it stands between two pillars and
     * sends a beam into the lid of the Sarcophagus.
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
        float[] core = {0.5F, coreY, 0.5F};
        float tipY = antennaTipY(vs, d.scale, d.lift);
        float[] tip = {0.5F, tipY, 0.5F};
        float feed = 0.0F;
        float disc = CoreEvolution.discAt(vp);
        float wv = ease(Mth.clamp(vp - 1.0F, 0.0F, 1.0F));
        if (wv > 0.02F) {
            for (int k = 0; k < 3; k++) {
                float f = (time * 0.009F + k / 3.0F) % 1.0F;
                float r = 0.7F + f * (disc - 0.7F);
                float fin = Mth.clamp(f / 0.12F, 0.0F, 1.0F);
                float a = fin * fin * (3.0F - 2.0F * fin) * (1.0F - f) * (1.0F - f) * wv * 0.5F;
                circle(lines, 0.5F, 0.04F, 0.5F, r, 40, 0.03F, 1.0F * a, 0.55F * a, 0.14F * a);
            }
        }
        float vr = CoreEvolution.pillarRadiusAt(vp);
        float[][] boss = new float[4][];
        for (int b = 0; b < 4; b++) {
            boss[b] = bossAt(b, time, vs, vr, CoreEvolution.pillarAt(vp));
        }
        float toB = toSoul(vp);
        float wA = 1.0F - toB;
        float height = CoreEvolution.pillarAt(vp);
        float[][] gems = new float[4][];
        float[] col = crystalColor(vp);
        float pf = ease(Mth.clamp(height / 3.0F, 0.0F, 1.0F));
        // how active the Ender Dragon is: it drains into the crystals only while it is the boss of the stage
        float dragon = boss[2][5] * (1.0F - boss[2][6]);
        if (height > 0.3F) {
            float yc = crystalCenter(vp);
            float top = height - 1.7F;
            for (int i = 0; i < 4; i++) {
                float cx = 0.5F + (i % 2 == 0 ? -vr : vr);
                float cz = 0.5F + (i < 2 ? -vr : vr);
                float y = yc + 0.07F * Mth.sin(time * 0.08F + i * 1.7F);
                gems[i] = new float[]{cx, y, cz};
                float u = (time * 0.018F + i * 0.25F) % 1.0F;
                float fade = (1.0F - u) * (1.0F - u) * Mth.clamp(u / 0.15F, 0.0F, 1.0F) * pf * 0.6F;
                circle(lines, cx, 0.03F, cz, 0.45F + u * 1.5F, 16, 0.025F, col[0] * fade, col[1] * fade, col[2] * fade);
                // forge: the crystal above the hammer sends its heat straight into the Core
                float eA = pf * wA;
                if (eA > 0.03F) {
                    beamTo(lines, packets, time, cx, y, cz, 0.5F, coreY, 0.5F, eA, 1.0F, 0.5F, 0.15F, i);
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
            }
        }
        // every boss sends exactly ONE beam into the antenna, in its own colour: the active one in its own kind (the twisted beam
        // of the Elder Guardian, the rings of sound of the Warden, a hose for the Dragon and the Wither), the defeated ones pump a
        // hose of the same colour. No other beams: the cages are only held by chains (see drawCageChains).
        for (int b = 0; b < 4; b++) {
            float[] a = boss[b];
            if (a[5] < 0.03F || a[4] < 0.05F) {
                continue;
            }
            float[] head = bossHead(b, a);
            float[] bc = BOSS_COL[b];
            float act = a[5] * (1.0F - a[6]);
            float pulse = 0.85F + 0.15F * Mth.sin(time * 0.1F + b * 2.0F);
            if (act > 0.03F) {
                float pz = heartBeat(time); // the beam of the Warden beats with its heart
                if (b == 0) {
                    twistBeam(lines, packets, time, head, tip, act, bc[0], bc[1], bc[2]);
                } else if (b == 1) {
                    sonicRings(lines, time, head, tip, act * (0.2F + 0.8F * pz), bc[0], bc[1], bc[2]);
                    packets.add(new float[]{head[0], head[1], head[2], act * (0.15F + 0.7F * pz), bc[0], bc[1], bc[2], 0.4F + 0.4F * pz});
                } else {
                    hose(lines, packets, time, head, tip, 0.5F, 0.18F, 1.5F, act * pulse, bc[0], bc[1], bc[2], b + 30);
                    if (b == 2) {
                        dragonFire(lines, packets, time, head, tip, act);
                    } else {
                        witherSides(lines, packets, time, a, head, tip, act * pulse, bc, b + 40);
                    }
                }
                feed += act * (b == 1 ? 0.4F + 0.5F * pz : 0.8F);
            }
            if (a[6] > 0.03F) {
                float kd = b == 1 ? a[6] * (0.35F + 0.65F * heartBeat(time)) : a[6] * pulse;
                hose(lines, packets, time, head, tip, 0.5F, 0.15F, 1.3F, kd, bc[0], bc[1], bc[2], b + 20);
                if (b == 2) {
                    dragonFire(lines, packets, time, head, tip, a[6] * 0.7F);
                } else if (b == 3) {
                    witherSides(lines, packets, time, a, head, tip, kd, bc, b + 50);
                }
                feed += a[6] * 0.5F;
            }
        }
        // the Wither (stage 4): a beacon rises from the tip of the antenna into the sky
        float gw = ease(Mth.clamp(vs - 3.0F, 0.0F, 1.0F));
        if (gw > 0.03F) {
            // in the colours of the Core: fire. The column boils (its width breathes, flames lick up it, embers rise) and the
            // spiral round it stays.
            float pulse = 0.85F + 0.15F * Mth.sin(time * 0.11F);
            float y0 = tipY + 0.1F;
            for (int sg = 0; sg < 8; sg++) {
                float ya = y0 + sg * 40.0F;
                float yb = ya + 40.0F;
                float fade = gw * pulse * (1.0F - 0.09F * sg);
                float boil = 0.88F + 0.12F * Mth.sin(time * 0.23F + sg * 1.3F);
                lines.add(0.5F, ya, 0.5F, 0.5F, yb, 0.5F, 0.7F * boil, 1.0F * fade * 0.2F, 0.3F * fade * 0.2F, 0.04F * fade * 0.2F);
                lines.add(0.5F, ya, 0.5F, 0.5F, yb, 0.5F, 0.36F * boil, 1.0F * fade * 0.5F, 0.55F * fade * 0.5F, 0.12F * fade * 0.5F);
                lines.add(0.5F, ya, 0.5F, 0.5F, yb, 0.5F, 0.12F, 1.0F * fade, 0.9F * fade, 0.62F * fade);
            }
            // flames that lick up the column
            for (int st = 0; st < 5; st++) {
                float[] prev = null;
                for (int m = 0; m <= 64; m++) {
                    float y = y0 + m * 1.3F;
                    float amp = 0.1F + 0.32F * (m / 64.0F) * (0.7F + 0.3F * Mth.sin(time * 0.17F + st));
                    float[] q = {0.5F + Mth.sin(y * 0.8F - time * 0.31F + st * 1.9F) * amp, y,
                            0.5F + Mth.cos(y * 0.65F - time * 0.27F + st * 2.7F) * amp};
                    if (prev != null) {
                        float kk = gw * pulse * (1.0F - m / 70.0F) * (0.55F + 0.45F * Mth.sin(time * 0.4F + m * 0.9F + st));
                        lines.add(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.2F, 1.0F * kk * 0.3F, 0.32F * kk * 0.3F, 0.05F * kk * 0.3F);
                        lines.add(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.07F, 1.0F * kk, 0.6F * kk, 0.15F * kk);
                    }
                    prev = q;
                }
            }
            for (int st = 0; st < 2; st++) {
                float[] prev = null;
                for (int m = 0; m <= 60; m++) {
                    float y = y0 + m * 0.5F;
                    float th = y * 1.3F - time * 0.15F + st * 3.1416F;
                    float[] q = {0.5F + Mth.cos(th) * 0.4F, y, 0.5F + Mth.sin(th) * 0.4F};
                    if (prev != null) {
                        float kk = gw * pulse * (1.0F - m / 75.0F);
                        lines.add(prev[0], prev[1], prev[2], q[0], q[1], q[2], 0.03F, 1.0F * kk, 0.62F * kk, 0.2F * kk);
                    }
                    prev = q;
                }
            }
            // embers rise out of the fire
            for (int i = 0; i < 18; i++) {
                float u = (time * 0.011F + i * 0.0556F) % 1.0F;
                float ang = i * 2.4F + time * 0.02F;
                float rad = 0.12F + 0.3F * rnd(i, 5) * (0.4F + u);
                float kk = gw * (1.0F - u) * (0.6F + 0.4F * Mth.sin(time * 0.3F + i));
                packets.add(new float[]{0.5F + Mth.cos(ang) * rad, y0 + u * 60.0F, 0.5F + Mth.sin(ang) * rad, kk, 1.0F, 0.55F, 0.15F, 0.24F});
            }
        }
        // the antenna passes everything on: a thick, pumping hose from its tip down into the Core
        float flowK = Mth.clamp(feed, 0.0F, 1.0F);
        if (flowK > 0.03F) {
            // the colours of the bosses that feed the antenna flow together (violet, blue, white, beige) and turn into the
            // orange of the Core on the way down
            List<float[]> mix = new ArrayList<>();
            for (int b = 0; b < 4; b++) {
                if (boss[b][5] > 0.03F && boss[b][4] > 0.05F) {
                    mix.add(BOSS_COL[b]);
                }
            }
            final float timeF = time;
            hoseColor(lines, packets, time, tip, new float[]{0.5F, coreY + 0.25F, 0.5F}, 0.0F, 0.2F, 1.0F, flowK, u -> {
                float e = Mth.clamp((u - 0.3F) / 0.6F, 0.0F, 1.0F);
                e = e * e * (3.0F - 2.0F * e);
                float cr = 1.0F;
                float cg = 0.72F;
                float cb = 0.38F;
                if (!mix.isEmpty()) {
                    float pos = ((u * 1.4F - timeF * 0.012F) % 1.0F + 1.0F) % 1.0F * mix.size();
                    int i0 = (int) Math.floor(pos) % mix.size();
                    int i1 = (i0 + 1) % mix.size();
                    float f = pos - (float) Math.floor(pos);
                    float[] c0 = mix.get(i0);
                    float[] c1 = mix.get(i1);
                    float mr = c0[0] + (c1[0] - c0[0]) * f;
                    float mg = c0[1] + (c1[1] - c0[1]) * f;
                    float mb = c0[2] + (c1[2] - c0[2]) * f;
                    cr = mr + (cr - mr) * e;
                    cg = mg + (cg - mg) * e;
                    cb = mb + (cb - mb) * e;
                }
                return new float[]{cr, cg, cb};
            }, 99);
            packets.add(new float[]{0.5F, tipY, 0.5F, 0.8F * flowK, 1.0F, 0.75F, 0.4F, 0.6F + 0.2F * flowK});
        }
        lines.flush(buffer, p);
        for (float[] pk : packets) {
            float half = pk.length > 7 ? pk[7] : 0.2F;
            billboard(buffer.getBuffer(glow(HALO)), p, pk[0], pk[1], pk[2], half, 0.0F, pk[4] * pk[3], pk[5] * pk[3], pk[6] * pk[3]);
        }
        // the crystals: they glow in their own shape, with a soft halo
        for (int i = 0; i < 4; i++) {
            if (gems[i] == null) {
                continue;
            }
            float pulse = (0.8F + 0.2F * Mth.sin(time * 0.15F + i)) * pf;
            float rot = time * 0.045F + i * 0.8F;
            gemGlow(buffer, p, gems[i][0], gems[i][1], gems[i][2], crystalR(vp) * 1.12F, crystalH(vp) * 1.08F, rot,
                    col[0] * 0.5F * pulse, col[1] * 0.5F * pulse, col[2] * 0.5F * pulse);
            billboard(buffer.getBuffer(glow(HALO)), p, gems[i][0], gems[i][1], gems[i][2], 1.5F, time * 0.02F,
                    col[0] * 0.36F * pulse, col[1] * 0.36F * pulse, col[2] * 0.36F * pulse);
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
    static void drawBolt(PoseStack pose, MultiBufferSource buffer, float time, float k) {
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

    static void drawOrb(PoseStack pose, MultiBufferSource buffer, float time, float scale, float flash, float vs) {
        float pulse = 0.93F + 0.07F * Mth.sin(time * 0.19F);
        float cy = ORB_Y + 0.02F * Mth.sin(time * 0.08F);
        float flick = 0.95F + 0.05F * Mth.sin(time * 0.43F) * Mth.sin(time * 0.27F + 2.0F);
        float s = scale;
        PoseStack.Pose p = pose.last();
        // Light around it, soft and wide.
        billboard(buffer.getBuffer(glow(HALO)), p, 0.5F, cy, 0.5F, 0.85F * s, 0.0F, 0.62F * pulse * s, 0.3F * pulse * s, 0.06F * s);
        if (flash > 0.0F) {
            billboard(buffer.getBuffer(glow(HALO)), p, 0.5F, cy, 0.5F, (0.8F + 1.2F * flash) * s, 0.0F, 0.8F * flash, 0.45F * flash, 0.12F * flash);
        }
        // The Core breathes: a soft shell of fire swells out of it and fades, twice per breath.
        for (int b = 0; b < 2; b++) {
            float ph = (time * 0.013F + b * 0.5F) % 1.0F;
            float a = (1.0F - ph) * (1.0F - ph) * Mth.clamp(ph / 0.12F, 0.0F, 1.0F) * 0.55F * s;
            if (a > 0.02F) {
                pose.pushPose();
                pose.translate(0.5F, cy, 0.5F);
                pose.mulPose(Axis.YP.rotationDegrees(time * (b == 0 ? 1.3F : -0.9F)));
                sphere(buffer.getBuffer(glow(ORB)), pose.last(), ORB_RADIUS * s * (1.4F + 2.2F * ph), 0.2F * b + time * 0.003F, -time * 0.01F, 1.0F * a, 0.62F * a, 0.3F * a);
                pose.popPose();
            }
        }
        // Two faint shells of flames turning against each other.
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 1.1F));
        pose.mulPose(Axis.XP.rotationDegrees(-30.0F));
        sphere(buffer.getBuffer(glow(ORB)), pose.last(), ORB_RADIUS * 1.55F * s * flick, 0.71F + time * 0.004F, -time * 0.03F, 0.55F * pulse, 0.4F * pulse, 0.22F * pulse);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-time * 2.6F));
        pose.mulPose(Axis.ZP.rotationDegrees(25.0F));
        sphere(buffer.getBuffer(glow(ORB)), pose.last(), ORB_RADIUS * 1.22F * s * flick, 0.37F - time * 0.008F, -time * 0.02F, 1.0F * pulse, 0.74F * pulse, 0.42F * pulse);
        pose.popPose();
        // The body: orange fire, solid (blended), it stays visible against a bright sky.
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 1.7F));
        sphere(buffer.getBuffer(fxFire()), pose.last(), ORB_RADIUS * s * flick, time * 0.006F, -time * 0.012F, 1.0F * pulse, 0.66F * pulse, 0.26F * pulse);
        pose.popPose();
        // Inside it burns hot matter: two shells of pale gold that turn against each other, then a white-hot heart.
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-time * 2.1F));
        pose.mulPose(Axis.XP.rotationDegrees(20.0F));
        sphere(buffer.getBuffer(glow(ORB)), pose.last(), ORB_RADIUS * 0.88F * s, 0.13F + time * 0.007F, time * 0.016F, 1.0F, 0.82F * pulse, 0.42F * pulse);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 2.9F));
        pose.mulPose(Axis.ZP.rotationDegrees(-35.0F));
        sphere(buffer.getBuffer(glow(ORB)), pose.last(), ORB_RADIUS * 0.7F * s, 0.5F - time * 0.01F, time * 0.02F, 1.0F, 0.93F * pulse, 0.62F * pulse);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.5F, cy, 0.5F);
        sphere(buffer.getBuffer(glow(TRIM)), pose.last(), ORB_RADIUS * 0.5F * s, 0.0F, 0.0F, 1.0F, 0.97F, 0.84F);
        pose.popPose();
        // The flare of the hot centre: biggest while the Core is still a star.
        float star = 1.0F - ease(Mth.clamp(vs, 0.0F, 1.0F));
        billboard(buffer.getBuffer(glow(FLARE)), p, 0.5F, cy, 0.5F, (0.34F + 0.3F * star) * s, time * 0.004F, 1.0F * pulse * s * 0.85F, 0.9F * pulse * s * 0.85F, 0.62F * pulse * s * 0.85F);
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
    static void billboard(VertexConsumer vc, PoseStack.Pose pose, float cx, float cy, float cz, float half,
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
    static void sphere(VertexConsumer vc, PoseStack.Pose pose, float radius, float uShift, float vShift, float r,
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
    static void laser(VertexConsumer vc, PoseStack.Pose pose, float ax, float ay, float az, float bx, float by,
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
    static void plane(VertexConsumer vc, PoseStack.Pose pose, float cx, float cy, float cz, float ax, float ay,
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
    static float addK = 1.0F;

    /** True while the late pass draws: colours are then sent as colour plus opacity (see {@link #glowVertex}). */
    static boolean alphaMode;

    /**
     * A glow layer. While the late pass draws it is blended (colour with opacity from the soft textures with an alpha channel),
     * never added: added light on a bright sky burns out to white, blended light keeps its colour.
     */
    static RenderType glow(ResourceLocation texture) {
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
    static RenderType fx() {
        return RenderType.entityTranslucentEmissive(TRIM);
    }

    /** Blended fire texture: the solid body of the Core. */
    static RenderType fxFire() {
        return RenderType.entityTranslucentEmissive(ORB);
    }
}
