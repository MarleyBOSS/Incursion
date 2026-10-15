package com.marleyboss.incursion.client;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.siege.Shields;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws what belongs to an active Incursion Core on the client:
 * <ul>
 *     <li>the shields as glowing, see-through plasma domes with a hexagon texture (one sphere per intact shield),
 *     with a shockwave when a shield breaks;</li>
 *     <li>the borders (switch "Borders" in the Core GUI): a square around the Core and around every ward totem,
 *     with a beacon-like beam at each corner.</li>
 * </ul>
 * Everything is driven by the synced block entity data (level, broken shields, ward positions).
 */
public class CoreRenderer implements BlockEntityRenderer<CoreBlockEntity> {
    private static final ResourceLocation HEX = ResourceLocation.fromNamespaceAndPath(Incursion.MODID,
            "textures/effect/shield_hex.png");
    private static final ResourceLocation PLASMA = ResourceLocation.fromNamespaceAndPath(Incursion.MODID,
            "textures/effect/shield_plasma.png");

    /** Sphere resolution (same for every dome; the geometry is precomputed). */
    private static final int LON = 48;
    private static final int LAT = 24;
    private static final int VERTS = (LON + 1) * (LAT + 1);
    private static final float[] UX = new float[VERTS];
    private static final float[] UY = new float[VERTS];
    private static final float[] UZ = new float[VERTS];

    static {
        for (int lat = 0; lat <= LAT; lat++) {
            double latAngle = Math.PI * (-0.5 + lat / (double) LAT);
            double ring = Math.cos(latAngle);
            double y = Math.sin(latAngle);
            for (int lon = 0; lon <= LON; lon++) {
                double lonAngle = 2.0 * Math.PI * lon / LON;
                int i = lat * (LON + 1) + lon;
                UX[i] = (float) (ring * Math.cos(lonAngle));
                UY[i] = (float) y;
                UZ[i] = (float) (ring * Math.sin(lonAngle));
            }
        }
    }

    /** Colour of each shield (outermost first). */
    private static final float[][] TINTS = {
            {0.25F, 0.85F, 1.00F},
            {0.40F, 0.65F, 1.00F},
            {0.70F, 0.95F, 1.00F}
    };
    private static final float PULSE_TICKS = 16.0F;
    private static final int BEAM_HEIGHT = 1024;
    private static final int COLOR_CORE_BEAM = 0xFFB84DFF;
    private static final int COLOR_WARD_BEAM = 0xFF4DFF66;

    /** Per-frame scratch values of one sphere. */
    private final float[] alphas = new float[VERTS];
    private final Map<CoreBlockEntity, Anim> anims = new WeakHashMap<>();

    /** Client-only animation state of one Core. */
    private static final class Anim {
        boolean known = false;
        final boolean[] intact = new boolean[Config.SHIELD_COUNT];
        final float[] alpha = new float[Config.SHIELD_COUNT];
        final float[] pulseStart = {-1.0F, -1.0F, -1.0F};
        float last = 0.0F;
        final Long2IntOpenHashMap ground = new Long2IntOpenHashMap();
        long groundStamp = Long.MIN_VALUE / 2;
    }

    public CoreRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public boolean shouldRenderOffScreen(CoreBlockEntity core) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public void render(CoreBlockEntity core, float partialTick, PoseStack pose, MultiBufferSource buffer,
                       int light, int overlay) {
        Level level = core.getLevel();
        if (level == null || !core.isActive()) {
            anims.remove(core);
            return;
        }
        Anim anim = anims.computeIfAbsent(core, key -> new Anim());
        float time = level.getGameTime() + partialTick;
        if (Config.SHIELDS_ENABLED.get()) {
            renderShields(core, anim, time, pose, buffer);
        }
        if (core.isShowBorders()) {
            renderBorders(core, anim, level, partialTick, pose, buffer);
        }
    }

    // ------------------------------------------------------------------
    // Shields
    // ------------------------------------------------------------------

    private void renderShields(CoreBlockEntity core, Anim anim, float time, PoseStack pose, MultiBufferSource buffer) {
        float dt = Mth.clamp(time - anim.last, 0.0F, 4.0F);
        anim.last = time;
        float pulseRadiusExtra = Config.SHIELD_PULSE_EXTRA_RADIUS.get();
        boolean any = false;
        for (int i = 0; i < Config.SHIELD_COUNT; i++) {
            boolean intact = Shields.isIntact(core, i);
            if (!anim.known) {
                anim.alpha[i] = intact ? 1.0F : 0.0F;
            } else {
                if (anim.intact[i] && !intact && Shields.isAvailable(core, i)) {
                    anim.pulseStart[i] = time; // it just broke: shockwave
                }
                float rate = (intact ? 0.05F : 0.30F) * dt;
                anim.alpha[i] += Mth.clamp((intact ? 1.0F : 0.0F) - anim.alpha[i], -rate, rate);
            }
            anim.intact[i] = intact;
            if (anim.pulseStart[i] >= 0.0F && (time - anim.pulseStart[i]) / PULSE_TICKS >= 1.0F) {
                anim.pulseStart[i] = -1.0F;
            }
            any |= anim.alpha[i] > 0.01F || anim.pulseStart[i] >= 0.0F;
        }
        anim.known = true;
        if (!any) {
            return;
        }

        BlockPos pos = core.getBlockPos();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double camX = camera.x - (pos.getX() + 0.5);
        double camY = camera.y - (pos.getY() + 0.5);
        double camZ = camera.z - (pos.getZ() + 0.5);

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        PoseStack.Pose last = pose.last();

        // Layer 1: the hexagon cells (and the break shockwaves).
        VertexConsumer hex = buffer.getBuffer(RenderType.entityTranslucentEmissive(HEX));
        for (int i = 0; i < Config.SHIELD_COUNT; i++) {
            float radius = Config.shieldRadius(i);
            float[] tint = TINTS[i];
            float flicker = 0.88F + 0.12F * Mth.sin(time * 0.12F + i * 2.0F);
            if (anim.alpha[i] > 0.01F) {
                float uRepeat = Math.max(3, Math.round(radius * 0.6F));
                float vRepeat = uRepeat * 0.2886F;
                float scroll = (i % 2 == 0 ? 1.0F : -1.0F) * time * 0.0006F;
                drawSphere(last, hex, radius, camX, camY, camZ, tint, 0.85F * anim.alpha[i] * flicker,
                        uRepeat, vRepeat, scroll, 0.0F);
            }
            if (anim.pulseStart[i] >= 0.0F) {
                float p = (time - anim.pulseStart[i]) / PULSE_TICKS;
                float eased = 1.0F - (1.0F - p) * (1.0F - p);
                float waveRadius = radius + pulseRadiusExtra * eased;
                float uRepeat = Math.max(3, Math.round(waveRadius * 0.6F));
                drawSphere(last, hex, waveRadius, camX, camY, camZ, new float[]{1.0F, 1.0F, 1.0F},
                        1.0F * (1.0F - p), uRepeat, uRepeat * 0.2886F, 0.0F, 0.0F);
            }
        }

        // Layer 2: flowing plasma veins on top.
        VertexConsumer plasma = buffer.getBuffer(RenderType.entityTranslucentEmissive(PLASMA));
        for (int i = 0; i < Config.SHIELD_COUNT; i++) {
            if (anim.alpha[i] <= 0.01F) {
                continue;
            }
            float radius = Config.shieldRadius(i);
            float flicker = 0.80F + 0.20F * Mth.sin(time * 0.17F + i * 1.3F);
            drawSphere(last, plasma, radius, camX, camY, camZ, TINTS[i], 0.70F * anim.alpha[i] * flicker,
                    3.0F, 1.5F, time * 0.0020F, -time * 0.0014F);
        }
        pose.popPose();
    }

    /**
     * One sphere around the current origin. The alpha is highest at the rim (seen from the camera), so the dome
     * looks like a glowing bubble; it fades out towards the poles where the texture would pinch.
     */
    private void drawSphere(PoseStack.Pose pose, VertexConsumer consumer, float radius, double camX, double camY,
                            double camZ, float[] tint, float alpha, float uRepeat, float vRepeat, float uOffset,
                            float vOffset) {
        for (int lat = 0; lat <= LAT; lat++) {
            float poleFade = Mth.clamp(Math.min(lat, LAT - lat) / 3.0F, 0.0F, 1.0F);
            for (int lon = 0; lon <= LON; lon++) {
                int i = lat * (LON + 1) + lon;
                double vx = camX - UX[i] * radius;
                double vy = camY - UY[i] * radius;
                double vz = camZ - UZ[i] * radius;
                double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
                double facing = length < 1.0E-4 ? 0.0 : Math.abs((UX[i] * vx + UY[i] * vy + UZ[i] * vz) / length);
                float rim = (float) Math.pow(1.0 - Math.min(1.0, facing), 1.5);
                alphas[i] = alpha * (0.30F + 0.70F * rim) * poleFade;
            }
        }
        for (int lat = 0; lat < LAT; lat++) {
            float v0 = lat / (float) LAT * vRepeat + vOffset;
            float v1 = (lat + 1) / (float) LAT * vRepeat + vOffset;
            for (int lon = 0; lon < LON; lon++) {
                float u0 = lon / (float) LON * uRepeat + uOffset;
                float u1 = (lon + 1) / (float) LON * uRepeat + uOffset;
                int a = lat * (LON + 1) + lon;
                int b = (lat + 1) * (LON + 1) + lon;
                int c = (lat + 1) * (LON + 1) + lon + 1;
                int d = lat * (LON + 1) + lon + 1;
                if (alphas[a] <= 0.004F && alphas[b] <= 0.004F && alphas[c] <= 0.004F && alphas[d] <= 0.004F) {
                    continue;
                }
                vertex(consumer, pose, a, radius, tint, u0, v0);
                vertex(consumer, pose, b, radius, tint, u0, v1);
                vertex(consumer, pose, c, radius, tint, u1, v1);
                vertex(consumer, pose, d, radius, tint, u1, v0);
            }
        }
    }

    private void vertex(VertexConsumer consumer, PoseStack.Pose pose, int index, float radius, float[] tint,
                        float u, float v) {
        consumer.addVertex(pose, UX[index] * radius, UY[index] * radius, UZ[index] * radius)
                .setColor(tint[0], tint[1], tint[2], Mth.clamp(alphas[index], 0.0F, 1.0F))
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, UX[index], UY[index], UZ[index]);
    }

    // ------------------------------------------------------------------
    // Borders
    // ------------------------------------------------------------------

    private void renderBorders(CoreBlockEntity core, Anim anim, Level level, float partialTick, PoseStack pose,
                               MultiBufferSource buffer) {
        long gameTime = level.getGameTime();
        if (gameTime - anim.groundStamp > 100) {
            anim.ground.clear();
            anim.groundStamp = gameTime;
        }
        BlockPos corePos = core.getBlockPos();
        int coreHalf = Config.coreZoneRadius(Math.max(1, core.getCoreLevel())) + 6;
        cornerBeams(core, anim, level, corePos, coreHalf, COLOR_CORE_BEAM, partialTick, pose, buffer);
        int wardHalf = Config.WARD_RADIUS.get();
        for (BlockPos ward : core.getSyncedWards()) {
            cornerBeams(core, anim, level, ward, wardHalf, COLOR_WARD_BEAM, partialTick, pose, buffer);
        }
    }

    /** A beacon-like beam at each of the four corners of the square of the given half-size around {@code center}. */
    private void cornerBeams(CoreBlockEntity core, Anim anim, Level level, BlockPos center, int half, int color,
                             float partialTick, PoseStack pose, MultiBufferSource buffer) {
        BlockPos corePos = core.getBlockPos();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int x = center.getX() + sx * half;
                int z = center.getZ() + sz * half;
                int y = groundY(level, anim, x, z, corePos.getY());
                pose.pushPose();
                pose.translate(x - corePos.getX(), y - corePos.getY(), z - corePos.getZ());
                BeaconRenderer.renderBeaconBeam(pose, buffer, BeaconRenderer.BEAM_LOCATION, partialTick, 1.0F,
                        level.getGameTime(), 0, BEAM_HEIGHT, color, 0.2F, 0.25F);
                pose.popPose();
            }
        }
    }

    /** Height of the ground at a corner; falls back to the Core's height where the chunk is not loaded. */
    private static int groundY(Level level, Anim anim, int x, int z, int fallback) {
        long key = BlockPos.asLong(x, 0, z);
        if (anim.ground.containsKey(key)) {
            return anim.ground.get(key);
        }
        int y = fallback;
        if (level.hasChunk(x >> 4, z >> 4)) {
            y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        anim.ground.put(key, y);
        return y;
    }
}
