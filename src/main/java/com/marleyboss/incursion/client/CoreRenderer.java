package com.marleyboss.incursion.client;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.siege.Shields;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
    private static final int BEAM_HEIGHT = 1024;
    /** All safe-zone beams are green. */
    private static final int COLOR_BEAM = 0xFF4DFF66;
    /** The ground inside the squares is tinted only this far around the camera (blocks) and this strongly. */
    private static final int TINT_RANGE = 40;
    private static final float TINT_ALPHA = 0.40F;

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
        final Long2IntOpenHashMap ground = new Long2IntOpenHashMap();
        long groundStamp = Long.MIN_VALUE / 2;
        int[] outline;
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
                drawSphere(last, hex, radius, camX, camY, camZ, tint, 1.0F * anim.alpha[i] * flicker,
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
            drawSphere(last, plasma, radius, camX, camY, camZ, TINTS[i], 0.95F * anim.alpha[i] * flicker,
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

    private void renderBorders(CoreBlockEntity core, Anim anim, Level level, float partialTick, PoseStack pose,
                               MultiBufferSource buffer) {
        long gameTime = level.getGameTime();
        if (gameTime - anim.groundStamp > 100) {
            anim.ground.clear();
            anim.groundStamp = gameTime;
        }
        BlockPos corePos = core.getBlockPos();
        int coreHalf = Config.coreZoneRadius(Math.max(1, core.getCoreLevel())) + 6;
        int wardHalf = Config.WARD_RADIUS.get();
        List<BlockPos> wards = core.getSyncedWards();

        // One safe zone: the union of the Core square and all ward squares. Beams only at the corners of the union.
        long key = ((long) coreHalf * 31 + wardHalf) * 31 + wards.hashCode();
        if (anim.outline == null || anim.outlineKey != key) {
            anim.outline = outlineCorners(corePos, coreHalf, wards, wardHalf);
            anim.outlineKey = key;
        }
        for (int i = 0; i < anim.outline.length; i += 2) {
            int x = anim.outline[i];
            int z = anim.outline[i + 1];
            int y = groundY(level, anim, x, z, corePos.getY());
            pose.pushPose();
            pose.translate(x - corePos.getX(), y - corePos.getY(), z - corePos.getZ());
            BeaconRenderer.renderBeaconBeam(pose, buffer, BeaconRenderer.BEAM_LOCATION, partialTick, 1.0F,
                    gameTime, 0, BEAM_HEIGHT, COLOR_BEAM, 0.2F, 0.25F);
            pose.popPose();
        }

        // Light green tint on the ground of the whole zone (each column once, even where squares overlap).
        tinted.clear();
        VertexConsumer tint = buffer.getBuffer(RenderType.entityTranslucentEmissive(GROUND_TINT));
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        tintSquare(core, anim, level, corePos, coreHalf, camera, pose, tint);
        for (BlockPos ward : wards) {
            tintSquare(core, anim, level, ward, wardHalf, camera, pose, tint);
        }
    }

    /**
     * Block columns {x0, z0, x1, z1, ...} at the corners of the outline of the union of the squares (outer corners and
     * inner corners where the squares meet). A coordinate grid is built from all square edges; a grid vertex is a
     * corner if one or three of the four cells around it are inside (or two diagonal ones).
     */
    private static int[] outlineCorners(BlockPos core, int coreHalf, List<BlockPos> wards, int wardHalf) {
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
        LongOpenHashSet seen = new LongOpenHashSet();
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < zs.length; j++) {
                boolean a = isFilled(filled, i - 1, j - 1); // north-west of the vertex
                boolean b = isFilled(filled, i, j - 1);     // north-east
                boolean c = isFilled(filled, i - 1, j);     // south-west
                boolean d = isFilled(filled, i, j);         // south-east
                int count = (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0) + (d ? 1 : 0);
                boolean saddle = count == 2 && ((a && d) || (b && c));
                if (count != 1 && count != 3 && !saddle) {
                    continue;
                }
                // The beam stands on the block of an inside cell next to the vertex (for an inner corner, the
                // cell opposite the empty one).
                boolean[] use = new boolean[4];
                if (count == 1 || saddle) {
                    use[0] = a;
                    use[1] = b;
                    use[2] = c;
                    use[3] = d;
                } else {
                    use[0] = !d;
                    use[1] = !c;
                    use[2] = !b;
                    use[3] = !a;
                }
                for (int cell = 0; cell < 4; cell++) {
                    if (!use[cell]) {
                        continue;
                    }
                    int x = xs[i] - (cell % 2 == 0 ? 1 : 0);
                    int z = zs[j] - (cell < 2 ? 1 : 0);
                    if (seen.add(BlockPos.asLong(x, 0, z))) {
                        result.add(x);
                        result.add(z);
                    }
                }
            }
        }
        int[] out = new int[result.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = result.get(i);
        }
        return out;
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
                .setColor(0.30F, 1.0F, 0.40F, TINT_ALPHA)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
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
