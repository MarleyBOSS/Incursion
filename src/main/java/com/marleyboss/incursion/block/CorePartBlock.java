package com.marleyboss.incursion.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Invisible space of the grown Sarcophagus (the big block is drawn by the Core's renderer). Unbreakable; it is removed
 * together with the rest of the structure when the Core goes. Using it works like using the Core.
 */
public class CorePartBlock extends Block {
    public CorePartBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    private static BlockPos findCore(Level level, BlockPos pos) {
        for (int dy = 0; dy >= -7; dy--) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos p = pos.offset(dx, dy, dz);
                    if (level.getBlockState(p).getBlock() instanceof CoreBlock) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        BlockPos core = findCore(level, pos);
        if (core == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        BlockHitResult forCore = new BlockHitResult(hit.getLocation(), hit.getDirection(), core, hit.isInside());
        return level.getBlockState(core).useItemOn(stack, level, player, hand, forCore);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        BlockPos core = findCore(level, pos);
        if (core == null) {
            return InteractionResult.PASS;
        }
        BlockHitResult forCore = new BlockHitResult(hit.getLocation(), hit.getDirection(), core, hit.isInside());
        return level.getBlockState(core).useWithoutItem(level, player, forCore);
    }
}
