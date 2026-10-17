package com.marleyboss.incursion.client;

import com.marleyboss.incursion.table.CoreTableBlockEntity;
import com.marleyboss.incursion.table.CoreTableLogic;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Draws the item of the Core Table floating and turning above it; it spins faster and glows while the table works. */
public class CoreTableRenderer implements BlockEntityRenderer<CoreTableBlockEntity> {
    private final BlockEntityRendererProvider.Context context;

    public CoreTableRenderer(BlockEntityRendererProvider.Context context) {
        this.context = context;
    }

    @Override
    public void render(CoreTableBlockEntity table, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        ItemStack stack = table.getDisplayStack();
        if (stack.isEmpty() || table.getLevel() == null) {
            return;
        }
        int job = table.getJobState();
        float time = table.getLevel().getGameTime() + partialTick;
        boolean working = job == CoreTableLogic.JOB_WORKING;
        float bob = (float) Math.sin(time * (working ? 0.16 : 0.08)) * (working ? 0.07F : 0.04F);
        float height = working ? 1.45F : 1.2F;
        int light = job == CoreTableLogic.JOB_IDLE ? packedLight : LightTexture.pack(15, 15);

        poseStack.pushPose();
        poseStack.translate(0.5F, height + bob, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(time * (working ? 9.0F : 2.5F)));
        poseStack.scale(0.8F, 0.8F, 0.8F);
        context.getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY,
                poseStack, buffer, table.getLevel(), (int) table.getBlockPos().asLong());
        poseStack.popPose();
    }
}
