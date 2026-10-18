package com.marleyboss.incursion.client;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.siege.Shields;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws what belongs to an active Incursion Core on the client:
 * <ul>
 *     <li>the shields as glowing, see-through plasma domes with a hexagon texture (one sphere per intact shield),
 *     with a shockwave when a shield breaks;</li>
 *     <li>the borders (switch "Borders" in the Core GUI): a square around the Core and around every ward totem,
 *     with a green beacon-like beam at each corner and a light green tint on the ground inside.</li>
 * </ul>
 * Everything is driven by the synced block entity data (level, broken shields, ward positions).
 */
public class CoreRenderer implements BlockEntityRenderer<CoreBlockEntity> {
    private static final ResourceLocation HEX = ResourceLocation.fromNamespaceAndPath(Incursion.MODID,
            "textures/effect/shield_hex.png");
    private static final ResourceLocation PLASMA = ResourceLocation.fromNamespaceAndPath(Incursion.MODID,
            "textures/effect/shield_plasma.png");

    private static final ResourceLocation GROUND_TINT = ResourceLocation.fromNamespaceAndPath(Incursion.MODID,
            "textures/effect/ground_tint.png");

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
    /** Heights above the ground at which the plasma wall is cut into slices (the last one reaches the sky). */
    private static final float[] WALL_SLICES = {6.0F, 16.0F, 32.0F, 64.0F, 128.0F, 100000.0F};
    /** The ground inside the squares is tinted only this far around the camera (blocks) and this strongly. */
    private static final int TINT_RANGE = 40;
    private static final float TINT_ALPHA = 0.50F;
    /** The shield domes are only faint while the Core's "Borders" view is off, so the view from the Core stays free. */
    private static final float SHIELD_IDLE_VIEW = 0.15F;

    /** Per-frame scratch values of one sphere. */
    private final float[] alphas = new float[VERTS];
    private final LongOpenHashSet tinted = new LongOpenHashSet();
    private final Map<CoreBlockEntity, Anim> anims = new WeakHashMap<>();

    /** Client-only animation state of one Core. */
    private static final class Anim {
        boolean known = false;
        final boolean[] intact = new boolean[Config.SHIELD_COUNT];
        final float[] alpha = new float[Config.SHIELD_COUNT];
        final float[] pulseStart = {-1.0F, -1.0F, -1.0F};
        float last = 0.0F;
        /** How strongly the domes are drawn right now: faint normally, full while the borders are shown. */
        float view = SHIELD_IDLE_VIEW;
        final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        int[] edges;
        int[] heights;
        long heightsTime;
        long outlineKey;
    }

    public CoreRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public boolean shouldRenderOffScreen(CoreBlockEntity core) {
        return true;
    }

    /**
     * The frustum check for always-drawn block entities uses this box. The default is the Core block itself, so
     * everything vanished when the Core was behind the player. A box around the whole siege area is always
     * "in view" while the player is inside it.
     */
    @Override
    public AABB getRenderBoundingBox(CoreBlockEntity core) {
        return new AABB(core.getBlockPos()).inflate(300.0, 400.0, 300.0);
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public void render(CoreBlockEntity core, float partialTick, PoseStack pose, MultiBufferSource buffer,
                       int light, int overlay) {
        Level level = core.getLevel();
        if (level == null) {
            return;
        }
        SarcophagusRenderer.render(core, partialTick, pose, buffer, level);
        Anim anim = anims.computeIfAbsent(core, key -> new Anim());
        float time = level.getGameTime() + partialTick;
        if (core.isActive()) {
            if (Config.SHIELDS_ENABLED.get()) {
                renderShields(core, anim, time, pose, buffer);
            }
        } else {
            anim.known = false; // shields start without a fade when the Core is switched on
        }
        // The borders can be shown before the Core is switched on, too (the Core syncs the totems while idle).
        if (core.isShowBorders()) {
            renderBorders(core, anim, level, time, pose, buffer);
        }
    }

    // ------------------------------------------------------------------
    // Shields
    // ------------------------------------------------------------------

    private void renderShields(CoreBlockEntity core, Anim anim, float time, PoseStack pose, MultiBufferSource buffer) {
        float dt = Mth.clamp(time - anim.last, 0.0F, 4.0F);
        anim.last = time;
        float viewTarget = core.isShowBorders() ? 1.0F : SHIELD_IDLE_VIEW;
        if (!anim.known) {
            anim.view = viewTarget;
        } else {
            float viewRate = 0.06F * dt;
            anim.view += Mth.clamp(viewTarget - anim.view, -viewRate, viewRate);
        }
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
                drawSphere(last, hex, radius, camX, camY, camZ, tint, 1.0F * anim.alpha[i] * flicker * anim.view,
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
            drawSphere(last, plasma, radius, camX, camY, camZ, TINTS[i], 0.95F * anim.alpha[i] * flicker * anim.view,
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
                alphas[i] = alpha * (0.55F + 0.45F * rim) * poleFade;
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

    private void renderBorders(CoreBlockEntity core, Anim anim, Level level, float time, PoseStack pose,
                               MultiBufferSource buffer) {
        BlockPos corePos = core.getBlockPos();
        int coreHalf = Config.coreZoneRadius(Math.max(1, core.getCoreLevel())) + 6;
        int wardHalf = Config.WARD_RADIUS.get();
        List<BlockPos> wards = core.getSyncedWards();

        // One safe zone: the union of the Core square and all ward squares (switched-off totems are not synced).
        long key = ((long) coreHalf * 31 + wardHalf) * 31 + wards.hashCode();
        long gameTime = level.getGameTime();
        if (anim.edges == null || anim.outlineKey != key) {
            anim.edges = outlineEdges(corePos, coreHalf, wards, wardHalf);
            anim.outlineKey = key;
            anim.heights = null; // forces the heights to be read below
        }
        if (anim.heights == null || gameTime - anim.heightsTime >= 40 || gameTime < anim.heightsTime) {
            int count = anim.edges.length / 3;
            if (anim.heights == null || anim.heights.length != count) {
                anim.heights = new int[count];
            }
            for (int i = 0; i < count; i++) {
                anim.heights[i] = groundY(level, anim, anim.edges[i * 3], anim.edges[i * 3 + 1], corePos.getY());
            }
            anim.heightsTime = gameTime;
        }
        renderWall(corePos, anim, level, time, pose, buffer);

        // Light tint on the ground of the whole zone (each column once, even where squares overlap).
        tinted.clear();
        VertexConsumer tint = buffer.getBuffer(RenderType.entityTranslucentEmissive(GROUND_TINT));
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        tintSquare(core, anim, level, corePos, coreHalf, camera, pose, tint);
        for (BlockPos ward : wards) {
            tintSquare(core, anim, level, ward, wardHalf, camera, pose, tint);
        }
    }

    /**
     * The plasma wall along the outline of the safe zone: a see-through, glowing, green curtain from the ground up
     * to the build limit. It is strongest at the ground and fades with height, with bands of light running upwards,
     * so it can be seen from far away. No texture: only vertex colours (the tint texture is a plain white square).
     */
    private void renderWall(BlockPos corePos, Anim anim, Level level, float time, PoseStack pose,
                            MultiBufferSource buffer) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucentEmissive(GROUND_TINT));
        PoseStack.Pose last = pose.last();
        int top = level.getMaxBuildHeight();
        int count = anim.edges.length / 3;
        for (int i = 0; i < count; i++) {
            int ex = anim.edges[i * 3];
            int ez = anim.edges[i * 3 + 1];
            boolean alongX = anim.edges[i * 3 + 2] == 0;
            float x0 = ex - corePos.getX();
            float z0 = ez - corePos.getZ();
            float x1 = alongX ? x0 + 1.0F : x0;
            float z1 = alongX ? z0 : z0 + 1.0F;
            float nx = alongX ? 0.0F : 1.0F;
            float nz = alongX ? 1.0F : 0.0F;
            int ground = anim.heights[i];
            float phase = (ex + ez) * 0.12F;
            float lowY = ground - 3;
            float lowH = -3.0F;
            for (int s = 0; s < WALL_SLICES.length; s++) {
                float highH = WALL_SLICES[s];
                float highY = Math.min(ground + highH, top);
                if (s == WALL_SLICES.length - 1) {
                    highY = top;
                }
                if (highY <= lowY) {
                    continue;
                }
                wallVertex(consumer, last, x0, lowY - corePos.getY(), z0, nx, nz, lowY, lowH, time, phase);
                wallVertex(consumer, last, x1, lowY - corePos.getY(), z1, nx, nz, lowY, lowH, time, phase);
                wallVertex(consumer, last, x1, highY - corePos.getY(), z1, nx, nz, highY, highH, time, phase);
                wallVertex(consumer, last, x0, highY - corePos.getY(), z0, nx, nz, highY, highH, time, phase);
                lowY = highY;
                lowH = highH;
            }
        }
    }

    private static void wallVertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float nx,
                                   float nz, float absY, float h, float time, float phase) {
        float height = Math.max(0.0F, h);
        // Strong at the ground, weak but still visible far up; light bands run upwards.
        float base = 0.28F + 0.62F * (float) Math.exp(-height / 70.0F);
        float band = 0.85F + 0.15F * Mth.sin(absY * 0.11F - time * 0.22F + phase);
        float flicker = 0.92F + 0.08F * Mth.sin(time * 0.31F + phase * 3.0F);
        float glow = (float) Math.exp(-height / 10.0F); // whiter near the ground
        consumer.addVertex(pose, x, y, z)
                .setColor(0.25F + 0.55F * glow, 1.00F, 0.40F + 0.45F * glow, Mth.clamp(base * band * flicker, 0.0F, 1.0F))
                .setUv(0.5F, 0.5F)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, 0.0F, nz);
    }

    /**
     * Unit edges {x, z, axis, ...} along the outline of the union of the squares. A coordinate grid is built from
     * all square edges; every filled grid cell contributes the sides that touch an empty cell. Axis 0 runs along X
     * (at the z line {@code z}), axis 1 along Z (at the x line {@code x}); each edge is one block long.
     */
    private static int[] outlineEdges(BlockPos core, int coreHalf, List<BlockPos> wards, int wardHalf) {
        int n = 1 + wards.size();
        int[] minX = new int[n];
        int[] maxX = new int[n];
        int[] minZ = new int[n];
        int[] maxZ = new int[n];
        minX[0] = core.getX() - coreHalf;
        maxX[0] = core.getX() + coreHalf + 1;
        minZ[0] = core.getZ() - coreHalf;
        maxZ[0] = core.getZ() + coreHalf + 1;
        for (int i = 0; i < wards.size(); i++) {
            BlockPos ward = wards.get(i);
            minX[i + 1] = ward.getX() - wardHalf;
            maxX[i + 1] = ward.getX() + wardHalf + 1;
            minZ[i + 1] = ward.getZ() - wardHalf;
            maxZ[i + 1] = ward.getZ() + wardHalf + 1;
        }
        int[] xs = sortedUnique(minX, maxX);
        int[] zs = sortedUnique(minZ, maxZ);
        boolean[][] filled = new boolean[xs.length][zs.length];
        for (int i = 0; i < xs.length - 1; i++) {
            for (int j = 0; j < zs.length - 1; j++) {
                for (int k = 0; k < n; k++) {
                    if (xs[i] >= minX[k] && xs[i] < maxX[k] && zs[j] >= minZ[k] && zs[j] < maxZ[k]) {
                        filled[i][j] = true;
                        break;
                    }
                }
            }
        }
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < xs.length - 1; i++) {
            for (int j = 0; j < zs.length - 1; j++) {
                if (!filled[i][j]) {
                    continue;
                }
                if (!isFilled(filled, i, j - 1)) { // north side
                    addRun(result, xs[i], xs[i + 1], zs[j], 0);
                }
                if (!isFilled(filled, i, j + 1)) { // south side
                    addRun(result, xs[i], xs[i + 1], zs[j + 1], 0);
                }
                if (!isFilled(filled, i - 1, j)) { // west side
                    addRun(result, zs[j], zs[j + 1], xs[i], 1);
                }
                if (!isFilled(filled, i + 1, j)) { // east side
                    addRun(result, zs[j], zs[j + 1], xs[i + 1], 1);
                }
            }
        }
        int[] out = new int[result.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = result.get(i);
        }
        return out;
    }

    /** Adds the unit edges from {@code from} (inclusive) to {@code to} (exclusive) on the line {@code fixed}. */
    private static void addRun(List<Integer> out, int from, int to, int fixed, int axis) {
        for (int v = from; v < to; v++) {
            out.add(axis == 0 ? v : fixed);
            out.add(axis == 0 ? fixed : v);
            out.add(axis);
        }
    }

    private static boolean isFilled(boolean[][] filled, int i, int j) {
        return i >= 0 && j >= 0 && i < filled.length - 1 && j < filled[0].length - 1 && filled[i][j];
    }

    private static int[] sortedUnique(int[] lows, int[] highs) {
        int[] all = new int[lows.length + highs.length];
        System.arraycopy(lows, 0, all, 0, lows.length);
        System.arraycopy(highs, 0, all, lows.length, highs.length);
        Arrays.sort(all);
        int count = 0;
        for (int i = 0; i < all.length; i++) {
            if (i == 0 || all[i] != all[i - 1]) {
                all[count++] = all[i];
            }
        }
        return Arrays.copyOf(all, count);
    }

    private void tintSquare(CoreBlockEntity core, Anim anim, Level level, BlockPos center, int half, Vec3 camera,
                            PoseStack pose, VertexConsumer consumer) {
        BlockPos corePos = core.getBlockPos();
        int camX = Mth.floor(camera.x);
        int camZ = Mth.floor(camera.z);
        int x0 = Math.max(center.getX() - half, camX - TINT_RANGE);
        int x1 = Math.min(center.getX() + half, camX + TINT_RANGE);
        int z0 = Math.max(center.getZ() - half, camZ - TINT_RANGE);
        int z1 = Math.min(center.getZ() + half, camZ + TINT_RANGE);
        PoseStack.Pose last = pose.last();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!tinted.add(BlockPos.asLong(x, 0, z)) || !level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                float y = groundY(level, anim, x, z, corePos.getY()) - corePos.getY() + 0.05F;
                float px = x - corePos.getX();
                float pz = z - corePos.getZ();
                groundVertex(consumer, last, px, y, pz, 0.0F, 0.0F);
                groundVertex(consumer, last, px, y, pz + 1.0F, 0.0F, 1.0F);
                groundVertex(consumer, last, px + 1.0F, y, pz + 1.0F, 1.0F, 1.0F);
                groundVertex(consumer, last, px + 1.0F, y, pz, 1.0F, 0.0F);
            }
        }
    }

    private static void groundVertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z,
                                     float u, float v) {
        consumer.addVertex(pose, x, y, z)
                .setColor(0.10F, 0.90F, 1.00F, TINT_ALPHA) // aqua: stands out on grass, sand, stone and snow
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    /**
     * Height of the surface at a block column: the top of the highest block that stops movement, looking past leaves
     * (so trees do not lift the tint into the canopy). Uses the MOTION_BLOCKING heightmap, which the server sends to
     * the client and keeps up to date when blocks change (the NO_LEAVES one is not sent and was wrong on the
     * client). Falls back to the Core's height where the chunk is not loaded.
     */
    private static int groundY(Level level, Anim anim, int x, int z, int fallback) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return fallback;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos.MutableBlockPos pos = anim.scratch;
        for (int i = 0; i < 24 && y > level.getMinBuildHeight(); i++) {
            pos.set(x, y - 1, z);
            if (!level.getBlockState(pos).is(BlockTags.LEAVES)) {
                break;
            }
            y--;
        }
        return y;
    }
}
