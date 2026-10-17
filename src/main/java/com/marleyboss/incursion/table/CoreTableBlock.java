package com.marleyboss.incursion.table;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.menu.CoreTableMenu;
import com.marleyboss.incursion.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The Core Table (forge of the Core): enchant equipment beyond the vanilla maximum. It looks like an enchanting table;
 * the block entity throws the particles (CoreTableEffects) and the renderer draws the item and the rune circles.
 */
public class CoreTableBlock extends BaseEntityBlock {
    public static final MapCodec<CoreTableBlock> CODEC = simpleCodec(CoreTableBlock::new);
    /** 0 = idle, 1 = enchanting, 2 = finished (the item waits to be taken). */
    public static final IntegerProperty JOB = IntegerProperty.create("job", 0, 2);
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 12, 16);

    public CoreTableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(JOB, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(JOB);
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
        return new CoreTableBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, ModBlockEntities.CORE_TABLE.get(), CoreTableBlockEntity::clientTick);
        }
        return createTickerHelper(type, ModBlockEntities.CORE_TABLE.get(), CoreTableBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!Config.TABLE_ENABLED.get()) {
            player.displayClientMessage(Component.translatable("message.incursion.table.disabled")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        if (level.getBlockEntity(pos) instanceof CoreTableBlockEntity table) {
            player.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, p) -> new CoreTableMenu(containerId, inventory, table),
                    Component.translatable("block.incursion.core_table")));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof CoreTableBlockEntity table) {
            Containers.dropContents(level, pos, table.getContainer());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
