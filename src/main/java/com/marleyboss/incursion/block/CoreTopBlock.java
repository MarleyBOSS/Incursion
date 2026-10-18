package com.marleyboss.incursion.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The upper half of an active Core: an invisible block above the Sarcophagus (the open Sarcophagus is two blocks
 * high, its upper half hovers in this block). It cannot be mined and nothing breaks it; it exists exactly as long as
 * the active Core below it. Using it works like using the Core.
 */
public class CoreTopBlock extends Block {
    public CoreTopBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /** Gone as soon as there is no active Core below. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.DOWN
                && !(neighborState.getBlock() instanceof CoreBlock && neighborState.getValue(CoreBlock.ACTIVE))) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        BlockPos below = pos.below();
        BlockHitResult forCore = new BlockHitResult(hit.getLocation(), hit.getDirection(), below, hit.isInside());
        return level.getBlockState(below).useItemOn(stack, level, player, hand, forCore);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        BlockPos below = pos.below();
        BlockHitResult forCore = new BlockHitResult(hit.getLocation(), hit.getDirection(), below, hit.isInside());
        return level.getBlockState(below).useWithoutItem(level, player, forCore);
    }
}
