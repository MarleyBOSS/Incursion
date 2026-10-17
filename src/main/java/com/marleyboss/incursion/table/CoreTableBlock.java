package com.marleyboss.incursion.table;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.menu.CoreTableMenu;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The Core Table (forge of the Core): enchant equipment beyond the vanilla maximum. Needs the active Core. */
public class CoreTableBlock extends Block {
    public static final MapCodec<CoreTableBlock> CODEC = simpleCodec(CoreTableBlock::new);

    public CoreTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
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
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, p) -> new CoreTableMenu(containerId, inventory, ContainerLevelAccess.create(level, pos)),
                Component.translatable("block.incursion.core_table")));
        return InteractionResult.CONSUME;
    }
}
