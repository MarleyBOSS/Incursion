package com.marleyboss.incursion.client;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.table.CoreTableBlockEntity;
import com.marleyboss.incursion.table.CoreTableLogic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * The Core Table's magic: two glowing rune circles turning above the cloth (always, so the table looks magical even
 * without an item), a light pillar while it works or is done, and the item floating and turning above it all.
 * The glowing parts use the additive "eyes" render type (always fully lit) with white textures tinted by vertex colour.
 */
public class CoreTableRenderer implements BlockEntityRenderer<CoreTableBlockEntity> {
    private static final ResourceLocation RUNE_CIRCLE = effect("rune_circle");
    private static final ResourceLocation RUNE_INNER = effect("rune_inner");
    private static final ResourceLocation HALO = effect("halo");
    private static final ResourceLocation BEAM = effect("beam");
    private static final float CLOTH = 0.75F;

    private final BlockEntityRendererProvider.Context context;

    public CoreTableRenderer(BlockEntityRendererProvider.Context context) {
        this.context = context;
    }

    private static ResourceLocation effect(String name) {
        return ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "textures/effect/" + name + ".png");
    }

    @Override
    public void render(CoreTableBlockEntity table, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        if (table.getLevel() == null) {
            return;
        }
        int job = table.getJobState();
        float time = table.getLevel().getGameTime() + partialTick;
        float progress = table.getProgress();
        boolean working = job == CoreTableLogic.JOB_WORKING;
        boolean done = job == CoreTableLogic.JOB_DONE;

        // Colour of the magic: violet at rest, towards pink-white while working, green when done.
        float r;
        float g;
        float b;
        if (done) {
            r = 0.45F;
            g = 1.0F;
            b = 0.62F;
        } else if (working) {
            r = 0.75F + 0.25F * progress;
            g = 0.40F + 0.45F * progress;
            b = 1.0F;
        } else {
            r = 0.68F;
            g = 0.44F;
            b = 1.0F;
        }
        float pulse = 0.5F + 0.5F * (float) Math.sin(time * (working ? 0.35 : 0.08));
        float alpha = working ? 0.65F + 0.2F * pulse : done ? 0.6F : 0.4F + 0.15F * pulse;

        float outerSpin = time * (working ? 2.6F + 3.0F * progress : done ? 1.2F : 0.8F);
        float innerSpin = -time * (working ? 4.2F + 4.0F * progress : done ? 1.8F : 1.3F);
        float outerSize = (working ? 1.15F + 0.06F * pulse : 1.05F + 0.03F * pulse);
        float innerSize = working ? 0.72F + 0.05F * pulse : 0.62F + 0.02F * pulse;

        // Soft halo on the cloth, outer ring, inner figure (each slightly higher to avoid flicker).
        glowQuad(buffer, poseStack, HALO, 0.5F, CLOTH + 0.015F, 0.5F, 0.0F, 0.95F + 0.1F * pulse, r, g, b, alpha * 0.45F);
        glowQuad(buffer, poseStack, RUNE_CIRCLE, 0.5F, CLOTH + 0.03F, 0.5F, outerSpin, outerSize, r, g, b, alpha);
        glowQuad(buffer, poseStack, RUNE_INNER, 0.5F, CLOTH + 0.05F, 0.5F, innerSpin, innerSize, r, g, b, alpha);
        if (working || done) {
            float beamAlpha = done ? 0.30F : 0.30F + 0.30F * progress;
            pillar(buffer, poseStack, 0.5F, CLOTH + 0.05F, 0.5F, time * 3.0F, done ? 0.30F : 0.26F + 0.12F * progress,
                    done ? 1.7F : 1.6F + 0.9F * progress, r, g, b, beamAlpha);
            // A second, rising ring makes the energy look like it climbs towards the item.
            float rise = (time * 0.025F) % 1.0F;
            glowQuad(buffer, poseStack, RUNE_CIRCLE, 0.5F, CLOTH + 0.1F + rise * 0.85F, 0.5F, -outerSpin * 1.4F,
                    0.9F - 0.5F * rise, r, g, b, alpha * (1.0F - rise) * 0.6F);
        }

        ItemStack stack = table.getDisplayStack();
        if (stack.isEmpty()) {
            return;
        }
        float bob = (float) Math.sin(time * (working ? 0.16 : 0.08)) * (working ? 0.07F : 0.04F);
        float height = working ? 1.45F + 0.15F * progress : 1.2F;
        float scale = working ? 0.8F + 0.07F * (float) Math.sin(time * 0.5) : 0.8F;
        int light = job == CoreTableLogic.JOB_IDLE ? packedLight : LightTexture.FULL_BRIGHT;
        poseStack.pushPose();
        poseStack.translate(0.5F, height + bob, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(time * (working ? 9.0F + 14.0F * progress : 2.5F)));
        if (working) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(12.0F * (float) Math.sin(time * 0.1)));
        }
        poseStack.scale(scale, scale, scale);
        context.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY,
                poseStack, buffer, table.getLevel(), (int) table.getBlockPos().asLong());
        poseStack.popPose();
    }

    /** A flat, glowing, horizontal square of the given half size, turned by {@code degrees}. */
    private void glowQuad(MultiBufferSource buffer, PoseStack poseStack, ResourceLocation texture, float x, float y, float z,
                          float degrees, float half, float r, float g, float b, float a) {
        VertexConsumer vc = buffer.getBuffer(RenderType.eyes(texture));
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.mulPose(Axis.YP.rotationDegrees(degrees));
        PoseStack.Pose pose = poseStack.last();
        int[] c = color(r, g, b, a);
        // Both windings, because the render type culls back faces.
        vertex(vc, pose, -half, 0, -half, 0, 0, c, 0, 1, 0);
        vertex(vc, pose, -half, 0, half, 0, 1, c, 0, 1, 0);
        vertex(vc, pose, half, 0, half, 1, 1, c, 0, 1, 0);
        vertex(vc, pose, half, 0, -half, 1, 0, c, 0, 1, 0);
        vertex(vc, pose, half, 0, -half, 1, 0, c, 0, -1, 0);
        vertex(vc, pose, half, 0, half, 1, 1, c, 0, -1, 0);
        vertex(vc, pose, -half, 0, half, 0, 1, c, 0, -1, 0);
        vertex(vc, pose, -half, 0, -half, 0, 0, c, 0, -1, 0);
        poseStack.popPose();
    }

    /** Two crossed vertical strips (a light pillar) that fade out towards the top. */
    private void pillar(MultiBufferSource buffer, PoseStack poseStack, float x, float y, float z, float degrees,
                        float half, float height, float r, float g, float b, float a) {
        VertexConsumer vc = buffer.getBuffer(RenderType.eyes(BEAM));
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.mulPose(Axis.YP.rotationDegrees(degrees));
        PoseStack.Pose pose = poseStack.last();
        int[] c = color(r, g, b, a);
        for (int i = 0; i < 2; i++) {
            if (i == 1) {
                poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
                pose = poseStack.last();
            }
            vertex(vc, pose, -half, 0, 0, 0, 1, c, 0, 0, 1);
            vertex(vc, pose, half, 0, 0, 1, 1, c, 0, 0, 1);
            vertex(vc, pose, half, height, 0, 1, 0, c, 0, 0, 1);
            vertex(vc, pose, -half, height, 0, 0, 0, c, 0, 0, 1);
            vertex(vc, pose, -half, height, 0, 0, 0, c, 0, 0, -1);
            vertex(vc, pose, half, height, 0, 1, 0, c, 0, 0, -1);
            vertex(vc, pose, half, 0, 0, 1, 1, c, 0, 0, -1);
            vertex(vc, pose, -half, 0, 0, 0, 1, c, 0, 0, -1);
        }
        poseStack.popPose();
    }

    /** The additive render type adds the RGB values and ignores alpha, so the intensity goes into the colour. */
    private static int[] color(float r, float g, float b, float a) {
        return new int[]{clamp(r * a), clamp(g * a), clamp(b * a), 255};
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, (int) (v * 255.0F)));
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v, int[] c,
                               float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z).setColor(c[0], c[1], c[2], c[3]).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
    }

    public AABB getRenderBoundingBox(CoreTableBlockEntity table) {
        return new AABB(table.getBlockPos().getX() - 1.5, table.getBlockPos().getY(), table.getBlockPos().getZ() - 1.5,
                table.getBlockPos().getX() + 2.5, table.getBlockPos().getY() + 3.5, table.getBlockPos().getZ() + 2.5);
    }
}
