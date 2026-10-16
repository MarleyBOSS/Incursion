package com.marleyboss.incursion.block;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Ward totem: no siege mobs spawn within its square (shown by the borders). The siege can attack it; when its
 * health is gone it is switched off ({@link #DISABLED}): the block stays, but its safe zone is gone until a player
 * repairs it (right-click with the repair item).
 */
public class WardBlock extends BaseEntityBlock {
    public static final MapCodec<WardBlock> CODEC = simpleCodec(WardBlock::new);
    public static final BooleanProperty DISABLED = BooleanProperty.create("disabled");
    private static final VoxelShape SHAPE = box(4.0, 0.0, 4.0, 12.0, 16.0, 12.0);

    public WardBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(DISABLED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DISABLED);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WardBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.WARD.get(), WardBlockEntity::serverTick);
    }

    // ------------------------------------------------------------------
    // Interaction: status and repair
    // ------------------------------------------------------------------

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!state.getValue(DISABLED) || !stack.is(Config.wardRepairItem())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        int cost = Config.WARD_REPAIR_COST.get();
        if (!player.getAbilities().instabuild && stack.getCount() < cost) {
            player.displayClientMessage(Component.translatable("message.incursion.ward.not_enough", cost,
                    stack.getHoverName()).withStyle(ChatFormatting.RED), true);
            return ItemInteractionResult.CONSUME;
        }
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof WardBlockEntity ward) {
            if (!player.getAbilities().instabuild) {
                stack.shrink(cost);
            }
            ward.repair(serverLevel);
            player.displayClientMessage(Component.translatable("message.incursion.ward.repaired")
                    .withStyle(ChatFormatting.GREEN), false);
        }
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof WardBlockEntity ward) {
            if (state.getValue(DISABLED)) {
                player.displayClientMessage(Component.translatable("message.incursion.ward.status_off",
                        Config.WARD_REPAIR_COST.get(), Config.wardRepairItem().getDescription())
                        .withStyle(ChatFormatting.GRAY), false);
            } else {
                player.displayClientMessage(Component.translatable("message.incursion.ward.status_on",
                        (int) Math.ceil(ward.getHealth()), ward.getMaxHealth()).withStyle(ChatFormatting.GREEN), false);
            }
        }
        return InteractionResult.CONSUME;
    }
}
