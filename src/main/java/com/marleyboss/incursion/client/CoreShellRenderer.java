package com.marleyboss.incursion.client;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlockEntity;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * The shell of the Incursion Core. Inactive, the Core is a closed block (static model: bottom shell, four plates, lid).
 * While it is active, the static model is only the bottom shell; this class draws the rest: the lid rises on four
 * golden rods, the four plates fold outwards like a transformer, and the crystal (an octahedron) appears between the
 * halves, held by lasers, turning and floating. A rune circle glows on the ground.
 */
final class CoreShellRenderer {
    private static final String NS = Incursion.MODID;
    private static final ResourceLocation SIDE = block("core_shell_side");
    private static final ResourceLocation TOP = block("core_shell_top");
    private static final ResourceLocation INNER = block("core_shell_inner");
    private static final ResourceLocation BOTTOM = block("core_shell_bottom");
    private static final ResourceLocation ROD = block("core_shell_rod");
    private static final ResourceLocation CRYSTAL = ResourceLocation.fromNamespaceAndPath(NS, "textures/block/core_crystal.png");
    private static final ResourceLocation LASER = effect("laser");
    private static final ResourceLocation HALO = effect("halo");
    private static final ResourceLocation RUNE_CIRCLE = effect("rune_circle");
    private static final ResourceLocation RUNE_INNER = effect("rune_inner");

    /** Time the unfolding takes (ticks). */
    private static final float OPEN_TICKS = 50.0F;
    private static final float LID_LIFT = 1.0F;
    private static final float PLATE_ANGLE = 78.0F;
    private static final float PLATE_HEIGHT = 6.0F / 16.0F;
    private static final float PLATE_THICK = 2.0F / 16.0F;
    private static final float LID_THICK = 2.0F / 16.0F;
    private static final float DISH = 0.5F;

    private static final Map<CoreBlockEntity, State> STATES = new WeakHashMap<>();

    private static final class State {
        boolean seen;
        boolean wasActive;
        float open;
        float last;
        float nextHum;
    }

    private CoreShellRenderer() {
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

    static void render(CoreBlockEntity core, float partialTick, PoseStack pose, MultiBufferSource buffer, Level level) {
        State state = STATES.computeIfAbsent(core, key -> new State());
        float time = level.getGameTime() + partialTick;
        float dt = Mth.clamp(time - state.last, 0.0F, 4.0F);
        state.last = time;
        boolean active = core.isActive();
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
        if (!active) {
            state.open = 0.0F;
            return;
        }
        state.open = Math.min(1.0F, state.open + dt / OPEN_TICKS);
        float t = state.open;
        if (t >= 1.0F && time >= state.nextHum) {
            level.playLocalSound(px, py, pz, ModSounds.CORE_HUM.get(), SoundSource.BLOCKS, 0.45F, 1.0F, false);
            state.nextHum = time + 78.0F;
        }

        float lift = ease(t / 0.6F) * LID_LIFT;
        float angle = ease((t - 0.25F) / 0.6F) * PLATE_ANGLE;
        float crystal = ease((t - 0.55F) / 0.45F);
        float fx = ease((t - 0.75F) / 0.25F);

        // Light: from above the block (its own position is solid), at least as bright as the Core itself.
        int above = LevelRenderer.getLightColor(level, core.getBlockPos().above());
        int light = LightTexture.pack(Math.max(LightTexture.block(above), 12), LightTexture.sky(above));

        drawShell(pose, buffer, light, lift, angle);
        float lidBottomY = 1.0F - LID_THICK + lift;
        float centerY = DISH + (lidBottomY - DISH) * 0.5F + 0.03F * Mth.sin(time * 0.07F) * crystal;
        if (crystal > 0.0F) {
            drawCrystal(pose, buffer, time, centerY, crystal);
        }
        if (fx > 0.0F) {
            drawLasers(pose, buffer, time, centerY, lidBottomY, fx);
            drawGround(pose, buffer, time, fx);
        }
    }

    // ------------------------------------------------------------------
    // Shell parts
    // ------------------------------------------------------------------

    private static TextureAtlasSprite sprite(ResourceLocation location) {
        return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(location);
    }

    /** A region (in texels of 16) of a sprite. */
    private record Tex(TextureAtlasSprite sprite, float u0, float v0, float u1, float v1) {
        static Tex full(TextureAtlasSprite sprite) {
            return new Tex(sprite, 0, 0, 16, 16);
        }
    }

    private static void drawShell(PoseStack pose, MultiBufferSource buffer, int light, float lift, float angle) {
        VertexConsumer vc = buffer.getBuffer(Sheets.solidBlockSheet());
        TextureAtlasSprite side = sprite(SIDE);
        Tex top = Tex.full(sprite(TOP));
        Tex inner = Tex.full(sprite(INNER));
        Tex bottom = Tex.full(sprite(BOTTOM));
        Tex rod = Tex.full(sprite(ROD));

        // Lid: seal on top, the dish pattern underneath, the top two texels of the side texture around.
        float y0 = 1.0F - LID_THICK + lift;
        Tex lidSide = new Tex(side, 0, 0, 16, 2);
        box(vc, pose.last(), light, 0, y0, 0, 1, y0 + LID_THICK, 1, top, inner, lidSide, lidSide, lidSide, lidSide);
        // Four golden rods from the dish to the lid.
        for (int i = 0; i < 4; i++) {
            float rx = (i & 1) == 0 ? 0.03F : 0.90F;
            float rz = (i & 2) == 0 ? 0.03F : 0.90F;
            box(vc, pose.last(), light, rx, DISH, rz, rx + 0.07F, y0, rz + 0.07F, rod, rod, rod, rod, rod, rod);
        }
        // Four plates folding outwards around their lower outer edge (north, west, south, east).
        Tex plateInner = new Tex(sprite(INNER), 0, 0, 16, 6);
        for (int k = 0; k < 4; k++) {
            pose.pushPose();
            pose.translate(0.5F, DISH, 0.5F);
            pose.mulPose(Axis.YP.rotationDegrees(k * 90.0F));
            pose.translate(0.0F, 0.0F, -0.5F);
            pose.mulPose(Axis.XP.rotationDegrees(-angle));
            float half = (k % 2 == 0) ? 0.5F : 0.5F - PLATE_THICK;
            Tex outer = (k % 2 == 0) ? new Tex(side, 0, 2, 16, 8) : new Tex(side, 2, 2, 14, 8);
            box(vc, pose.last(), light, -half, 0.0F, 0.0F, half, PLATE_HEIGHT, PLATE_THICK, bottom, bottom, outer,
                    plateInner, bottom, bottom);
            pose.popPose();
        }
    }

    /** An axis-aligned box in the current pose; every face has its own texture region. */
    private static void box(VertexConsumer vc, PoseStack.Pose pose, int light, float x0, float y0, float z0, float x1,
                            float y1, float z1, Tex up, Tex down, Tex north, Tex south, Tex west, Tex east) {
        quad(vc, pose, light, up, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(vc, pose, light, down, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(vc, pose, light, north, 0, 0, -1, x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0);
        quad(vc, pose, light, south, 0, 0, 1, x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1);
        quad(vc, pose, light, west, -1, 0, 0, x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1);
        quad(vc, pose, light, east, 1, 0, 0, x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0);
    }

    /** Corners are given top-left, bottom-left, bottom-right, top-right as seen from outside. */
    private static void quad(VertexConsumer vc, PoseStack.Pose pose, int light, Tex tex, float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                             float dx, float dy, float dz) {
        TextureAtlasSprite sprite = tex.sprite();
        float du = sprite.getU1() - sprite.getU0();
        float dv = sprite.getV1() - sprite.getV0();
        float u0 = sprite.getU0() + du * (tex.u0() / 16.0F);
        float u1 = sprite.getU0() + du * (tex.u1() / 16.0F);
        float v0 = sprite.getV0() + dv * (tex.v0() / 16.0F);
        float v1 = sprite.getV0() + dv * (tex.v1() / 16.0F);
        vertex(vc, pose, light, ax, ay, az, u0, v0, nx, ny, nz);
        vertex(vc, pose, light, bx, by, bz, u0, v1, nx, ny, nz);
        vertex(vc, pose, light, cx, cy, cz, u1, v1, nx, ny, nz);
        vertex(vc, pose, light, dx, dy, dz, u1, v0, nx, ny, nz);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, int light, float x, float y, float z, float u, float v,
                               float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, nx, ny, nz);
    }

    // ------------------------------------------------------------------
    // The crystal
    // ------------------------------------------------------------------

    private static final float[][] OCTA = {
            {0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}
    };
    // Faces as indices into OCTA: apex, then two equator corners (all eight faces).
    private static final int[][] FACES = {
            {0, 4, 2}, {0, 2, 5}, {0, 5, 3}, {0, 3, 4},
            {1, 2, 4}, {1, 5, 2}, {1, 3, 5}, {1, 4, 3}
    };

    private static void drawCrystal(PoseStack pose, MultiBufferSource buffer, float time, float centerY, float scale) {
        float yaw = time * 3.2F;
        // Outer crystal: translucent facets that light up as they turn, inner core counter-rotating and brighter.
        pose.pushPose();
        pose.translate(0.5F, centerY, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.mulPose(Axis.XP.rotationDegrees(7.0F * Mth.sin(time * 0.05F)));
        octahedron(buffer.getBuffer(RenderType.entityTranslucentEmissive(CRYSTAL)), pose.last(), 0.30F * scale, 0.48F * scale,
                yaw, 0.85F, 0.75F);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.5F, centerY, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw * 1.7F));
        pose.mulPose(Axis.ZP.rotationDegrees(9.0F * Mth.sin(time * 0.07F)));
        octahedron(buffer.getBuffer(RenderType.entityTranslucentEmissive(CRYSTAL)), pose.last(), 0.16F * scale, 0.25F * scale,
                -yaw * 1.7F, 1.25F, 0.95F);
        pose.popPose();
        // Soft glow around it (three crossed planes, additive).
        float pulse = 0.75F + 0.25F * Mth.sin(time * 0.12F);
        VertexConsumer glow = buffer.getBuffer(RenderType.eyes(HALO));
        float h = 0.85F * scale;
        float k = 0.55F * pulse * scale;
        pose.pushPose();
        pose.translate(0.5F, centerY, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(yaw * 0.5F));
        PoseStack.Pose p = pose.last();
        plane(glow, p, 0, 0, 0, h, 0, 0, 0, 0, h, 0.70F * k, 0.40F * k, 1.00F * k);
        plane(glow, p, 0, 0, 0, h, 0, 0, 0, h, 0, 0.70F * k, 0.40F * k, 1.00F * k);
        plane(glow, p, 0, 0, 0, 0, 0, h, 0, h, 0, 0.70F * k, 0.40F * k, 1.00F * k);
        pose.popPose();
    }

    private static void octahedron(VertexConsumer vc, PoseStack.Pose pose, float radius, float height, float yawDegrees,
                                   float brightness, float alpha) {
        // The light stays in the world while the crystal turns, so the facets flash as they pass it.
        double yaw = Math.toRadians(yawDegrees);
        float lx = (float) (0.5 * Math.cos(yaw) - 0.3 * Math.sin(yaw));
        float lz = (float) (0.5 * Math.sin(yaw) + 0.3 * Math.cos(yaw));
        float ly = 0.8F;
        float len = Mth.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= len;
        ly /= len;
        lz /= len;
        for (int[] face : FACES) {
            float[][] v = new float[3][];
            for (int i = 0; i < 3; i++) {
                float[] o = OCTA[face[i]];
                v[i] = new float[]{o[0] * radius, o[1] * height, o[2] * radius};
            }
            float ux = v[1][0] - v[0][0];
            float uy = v[1][1] - v[0][1];
            float uz = v[1][2] - v[0][2];
            float wx = v[2][0] - v[0][0];
            float wy = v[2][1] - v[0][1];
            float wz = v[2][2] - v[0][2];
            float nx = uy * wz - uz * wy;
            float ny = uz * wx - ux * wz;
            float nz = ux * wy - uy * wx;
            float cxm = v[0][0] + v[1][0] + v[2][0];
            float cym = v[0][1] + v[1][1] + v[2][1];
            float czm = v[0][2] + v[1][2] + v[2][2];
            if (nx * cxm + ny * cym + nz * czm < 0) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
            float nlen = Mth.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= nlen;
            ny /= nlen;
            nz /= nlen;
            float shade = Mth.clamp((0.45F + 0.75F * Math.max(0.0F, nx * lx + ny * ly + nz * lz)) * brightness, 0.0F, 1.6F);
            int r = Mth.clamp((int) (255 * Math.min(1.0F, shade * 0.95F)), 0, 255);
            int g = Mth.clamp((int) (255 * Math.min(1.0F, shade * 0.80F)), 0, 255);
            int b = 255;
            int a = Mth.clamp((int) (255 * alpha), 0, 255);
            crystalVertex(vc, pose, v[0], 0.5F, 0.0F, r, g, b, a, nx, ny, nz);
            crystalVertex(vc, pose, v[1], 0.0F, 1.0F, r, g, b, a, nx, ny, nz);
            crystalVertex(vc, pose, v[2], 1.0F, 1.0F, r, g, b, a, nx, ny, nz);
            crystalVertex(vc, pose, v[2], 1.0F, 1.0F, r, g, b, a, nx, ny, nz);
        }
    }

    private static void crystalVertex(VertexConsumer vc, PoseStack.Pose pose, float[] p, float u, float v, int r, int g, int b,
                                      int a, float nx, float ny, float nz) {
        vc.addVertex(pose, p[0], p[1], p[2]).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
    }

    // ------------------------------------------------------------------
    // Lasers and ground circle (additive: the intensity goes into the colour)
    // ------------------------------------------------------------------

    private static void drawLasers(PoseStack pose, MultiBufferSource buffer, float time, float centerY, float lidBottomY, float fx) {
        VertexConsumer vc = buffer.getBuffer(RenderType.eyes(LASER));
        PoseStack.Pose p = pose.last();
        float flicker = 0.8F + 0.2F * Mth.sin(time * 0.9F);
        float k = 0.9F * fx * flicker;
        for (int i = 0; i < 4; i++) {
            float ex = 0.5F + ((i & 1) == 0 ? -0.36F : 0.36F);
            float ez = 0.5F + ((i & 2) == 0 ? -0.36F : 0.36F);
            laser(vc, p, ex, DISH + 0.03F, ez, 0.5F, centerY, 0.5F, 0.035F, 0.70F * k, 0.40F * k, 1.00F * k);
            laser(vc, p, ex, lidBottomY - 0.03F, ez, 0.5F, centerY, 0.5F, 0.035F, 0.55F * k, 0.45F * k, 1.00F * k);
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
        // First perpendicular: tangent x up (or x when the laser is vertical), second: tangent x first.
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

    private static void drawGround(PoseStack pose, MultiBufferSource buffer, float time, float fx) {
        float pulse = 0.5F + 0.5F * Mth.sin(time * 0.1F);
        float k = (0.45F + 0.2F * pulse) * fx;
        pose.pushPose();
        pose.translate(0.5F, 0.025F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(time * 0.9F));
        plane(buffer.getBuffer(RenderType.eyes(RUNE_CIRCLE)), pose.last(), 0, 0, 0, 1.7F, 0, 0, 0, 0, 1.7F,
                0.70F * k, 0.40F * k, 1.00F * k);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.5F, 0.03F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-time * 1.4F));
        plane(buffer.getBuffer(RenderType.eyes(RUNE_INNER)), pose.last(), 0, 0, 0, 1.05F, 0, 0, 0, 0, 1.05F,
                0.60F * k, 0.35F * k, 1.00F * k);
        pose.popPose();
    }
}
