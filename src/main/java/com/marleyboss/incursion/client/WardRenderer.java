package com.marleyboss.incursion.client;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.WardBlock;
import com.marleyboss.incursion.block.WardBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/** Draws a health bar above a switched-on ward totem (always turned towards the camera). */
public class WardRenderer implements BlockEntityRenderer<WardBlockEntity> {
    /** A plain white texture; the bar colour comes from the vertex colour. */
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(Incursion.MODID,
            "textures/effect/ground_tint.png");
    private static final float HALF_WIDTH = 0.45F;
    private static final float HEIGHT = 0.09F;

    public WardRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(WardBlockEntity ward, float partialTick, PoseStack pose, MultiBufferSource buffer, int light,
                       int overlay) {
        if (ward.getLevel() == null) {
            return;
        }
        BlockState state = ward.getBlockState();
        if (!(state.getBlock() instanceof WardBlock) || state.getValue(WardBlock.DISABLED)) {
            return; // a switched-off totem has no health bar (its dark look says it all)
        }
        float fraction = Mth.clamp(ward.getHealth() / Math.max(1, ward.getMaxHealth()), 0.0F, 1.0F);

        pose.pushPose();
        pose.translate(0.5, 1.4, 0.5);
        pose.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucentEmissive(WHITE));
        PoseStack.Pose last = pose.last();
        // Background, then the filled part (slightly closer to the camera). Green when full, red when empty.
        quad(consumer, last, -HALF_WIDTH - 0.02F, -0.02F, HALF_WIDTH + 0.02F, HEIGHT + 0.02F, 0.0F,
                0.05F, 0.05F, 0.05F, 0.85F);
        if (fraction > 0.0F) {
            float r = Mth.clamp(2.0F * (1.0F - fraction), 0.0F, 1.0F);
            float g = Mth.clamp(2.0F * fraction, 0.0F, 1.0F);
            quad(consumer, last, -HALF_WIDTH, 0.0F, -HALF_WIDTH + 2.0F * HALF_WIDTH * fraction, HEIGHT, 0.004F,
                    r, g, 0.1F, 1.0F);
        }
        pose.popPose();
    }

    /** A quad in the plane facing the camera, drawn from both sides. */
    private static void quad(VertexConsumer consumer, PoseStack.Pose pose, float x0, float y0, float x1, float y1,
                             float z, float r, float g, float b, float a) {
        vertex(consumer, pose, x0, y0, z, r, g, b, a, 0.0F, 1.0F);
        vertex(consumer, pose, x1, y0, z, r, g, b, a, 1.0F, 1.0F);
        vertex(consumer, pose, x1, y1, z, r, g, b, a, 1.0F, 0.0F);
        vertex(consumer, pose, x0, y1, z, r, g, b, a, 0.0F, 0.0F);
        vertex(consumer, pose, x0, y1, z, r, g, b, a, 0.0F, 0.0F);
        vertex(consumer, pose, x1, y1, z, r, g, b, a, 1.0F, 0.0F);
        vertex(consumer, pose, x1, y0, z, r, g, b, a, 1.0F, 1.0F);
        vertex(consumer, pose, x0, y0, z, r, g, b, a, 0.0F, 1.0F);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float r,
                               float g, float b, float a, float u, float v) {
        consumer.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }
}
