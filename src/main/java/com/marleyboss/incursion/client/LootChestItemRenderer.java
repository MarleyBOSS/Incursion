package com.marleyboss.incursion.client;

import com.marleyboss.incursion.block.LootChestBlockEntity;
import com.marleyboss.incursion.registry.ModBlocks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Draws the loot chest item (inventory, hand, ground) with the same chest renderer as the block. */
public class LootChestItemRenderer extends BlockEntityWithoutLevelRenderer {
    private LootChestBlockEntity dummy;

    public LootChestItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (dummy == null) {
            dummy = new LootChestBlockEntity(BlockPos.ZERO, ModBlocks.LOOT_CHEST.get().defaultBlockState());
        }
        Minecraft.getInstance().getBlockEntityRenderDispatcher()
                .renderItem(dummy, poseStack, buffer, packedLight, packedOverlay);
    }
}
