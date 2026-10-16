package com.marleyboss.incursion.client;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.LootChestBlock;
import com.marleyboss.incursion.block.LootChestBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the loot chest with the vanilla chest model (lid, lock, body) and its own texture, including the lid
 * animation. The texture lives in the chest atlas: {@code textures/entity/chest/loot_chest.png}.
 */
public class LootChestRenderer implements BlockEntityRenderer<LootChestBlockEntity> {
    private static final Material MATERIAL = new Material(Sheets.CHEST_SHEET,
            ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "entity/chest/loot_chest"));

    private final ModelPart lid;
    private final ModelPart lock;
    private final ModelPart bottom;

    public LootChestRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart root = context.bakeLayer(ModelLayers.CHEST);
        this.bottom = root.getChild("bottom");
        this.lid = root.getChild("lid");
        this.lock = root.getChild("lock");
    }

    @Override
    public void render(LootChestBlockEntity chest, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        BlockState state = chest.getBlockState();
        Direction facing = state.hasProperty(LootChestBlock.FACING) ? state.getValue(LootChestBlock.FACING) : Direction.SOUTH;
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        float open = chest.getOpenNess(partialTick);
        open = 1.0F - open;
        open = 1.0F - open * open * open;

        VertexConsumer consumer = MATERIAL.buffer(buffer, RenderType::entityCutout);
        lid.xRot = -(open * ((float) Math.PI / 2.0F));
        lock.xRot = lid.xRot;
        lid.render(poseStack, consumer, packedLight, packedOverlay);
        lock.render(poseStack, consumer, packedLight, packedOverlay);
        bottom.render(poseStack, consumer, packedLight, packedOverlay);
        poseStack.popPose();
    }
}
