package com.marleyboss.incursion.block;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.data.CoreWorldData;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The Incursion Core.
 * <ul>
 *     <li>Level 0 / inactive: peaceful, can be mined and moved.</li>
 *     <li>Right-click with the activation item: becomes active at level 1 (one active core per world).</li>
 *     <li>Right-click with the upgrade item while active: pays the cost and gains a level.</li>
 *     <li>Right-click with anything else: shows the status.</li>
 *     <li>Active cores cannot be mined in survival (the explosion on removal follows in v0.3).</li>
 * </ul>
 */
public class CoreBlock extends BaseEntityBlock {
    public static final MapCodec<CoreBlock> CODEC = simpleCodec(CoreBlock::new);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public CoreBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CoreBlockEntity(pos, state);
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        boolean active = state.getValue(ACTIVE);
        Item activationItem = Config.activationItem();
        Item upgradeItem = Config.upgradeItem();

        boolean isActivation = !active && stack.is(activationItem);
        boolean isUpgrade = active && stack.is(upgradeItem);
        if (!isActivation && !isUpgrade) {
            // Falls through to useWithoutItem (status display) for the main hand.
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof CoreBlockEntity core)) {
            return ItemInteractionResult.FAIL;
        }

        if (isActivation) {
            tryActivate((ServerLevel) level, pos, state, core, player, stack);
        } else {
            tryUpgrade(level, pos, core, player, stack);
        }
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof CoreBlockEntity core) {
            showStatus(player, state.getValue(ACTIVE), core.getCoreLevel());
        }
        return InteractionResult.CONSUME;
    }

    private void tryActivate(ServerLevel level, BlockPos pos, BlockState state, CoreBlockEntity core,
                             Player player, ItemStack stack) {
        CoreWorldData data = CoreWorldData.get(level.getServer());
        BlockPos other = data.findOtherActiveCore(level.getServer(), level.dimension(), pos);
        if (other != null) {
            player.displayClientMessage(Component.translatable("message.incursion.core.another_active",
                    other.getX(), other.getY(), other.getZ()).withStyle(ChatFormatting.RED), false);
            return;
        }

        consume(player, stack, 1);
        data.setActiveCore(level.dimension(), pos);
        core.setCoreLevel(1);
        level.setBlock(pos, state.setValue(ACTIVE, true), Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 0.6F);
        player.displayClientMessage(Component.translatable("message.incursion.core.activated")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
    }

    private void tryUpgrade(Level level, BlockPos pos, CoreBlockEntity core, Player player, ItemStack stack) {
        int currentLevel = core.getCoreLevel();
        int maxLevel = Config.MAX_LEVEL.get();
        if (currentLevel >= maxLevel) {
            player.displayClientMessage(Component.translatable("message.incursion.core.max_level", maxLevel)
                    .withStyle(ChatFormatting.GOLD), true);
            return;
        }

        int targetLevel = currentLevel + 1;
        int cost = Config.upgradeCost(targetLevel);
        if (!player.getAbilities().instabuild && stack.getCount() < cost) {
            player.displayClientMessage(Component.translatable("message.incursion.core.not_enough",
                    cost, stack.getHoverName()).withStyle(ChatFormatting.RED), true);
            return;
        }

        consume(player, stack, cost);
        core.setCoreLevel(targetLevel);
        level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0F, 0.8F);
        player.displayClientMessage(Component.translatable("message.incursion.core.upgraded", targetLevel, maxLevel)
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
    }

    private static void showStatus(Player player, boolean active, int coreLevel) {
        int maxLevel = Config.MAX_LEVEL.get();
        Component stateText = active
                ? Component.translatable("message.incursion.core.state.active").withStyle(ChatFormatting.RED)
                : Component.translatable("message.incursion.core.state.inactive").withStyle(ChatFormatting.GREEN);
        player.displayClientMessage(Component.translatable("message.incursion.core.status",
                coreLevel, maxLevel, stateText).withStyle(ChatFormatting.LIGHT_PURPLE), false);

        if (!active) {
            player.displayClientMessage(Component.translatable("message.incursion.core.hint_activate",
                    Config.activationItem().getDescription()).withStyle(ChatFormatting.GRAY), false);
        } else if (coreLevel < maxLevel) {
            player.displayClientMessage(Component.translatable("message.incursion.core.next_cost",
                    Config.upgradeCost(coreLevel + 1), Config.upgradeItem().getDescription())
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static void consume(Player player, ItemStack stack, int amount) {
        if (!player.getAbilities().instabuild) {
            stack.shrink(amount);
        }
    }

    // ------------------------------------------------------------------
    // Removal
    // ------------------------------------------------------------------

    /** Active cores cannot be mined by players in survival. */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (state.getValue(ACTIVE)) {
            return 0.0F;
        }
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && state.getValue(ACTIVE)
                && level instanceof ServerLevel serverLevel) {
            CoreWorldData.get(serverLevel.getServer()).clearIfMatches(level.dimension(), pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
