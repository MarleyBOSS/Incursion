package com.marleyboss.incursion.block;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.data.CoreWorldData;
import com.marleyboss.incursion.fallout.Fallout;
import com.marleyboss.incursion.item.UpgradeToolItem;
import com.marleyboss.incursion.menu.CoreMenu;
import com.marleyboss.incursion.registry.ModBlockEntities;
import com.marleyboss.incursion.registry.ModItems;
import com.marleyboss.incursion.siege.Rewards;
import com.marleyboss.incursion.siege.SiegeManager;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
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
import org.jetbrains.annotations.Nullable;

/**
 * The Incursion Core.
 * <ul>
 *     <li>Level 0 / inactive: peaceful, can be mined and moved.</li>
 *     <li>Right-click with the activation item: becomes active at level 1 (one active core per world).</li>
 *     <li>Right-click with the upgrade item or an Upgrade Tool while active: pays the cost and gains a level
 *     (an Upgrade Tool of a high enough tier must be in the inventory, see {@code upgradeToolRequired}).</li>
 *     <li>Right-click with anything else: opens the Core GUI (status, upgrade button).</li>
 *     <li>While active, the block entity runs the siege (see SiegeManager).</li>
 *     <li>Active cores cannot be mined in survival. If an active core is removed in any other way
 *     (creative mode, commands, other mods) or its health drops to 0, it explodes (see Fallout).</li>
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

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.CORE.get(), CoreBlockEntity::serverTick);
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.is(ModItems.CORE_SHARD.get()) && player.isShiftKeyDown()) {
            // Sneak + right-click: shards go into the Core's balance (they can be taken out again in the GUI).
            // A plain right-click opens the GUI as usual.
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof CoreBlockEntity core) {
                Rewards.deposit(player, core, stack);
            }
            return level.isClientSide ? ItemInteractionResult.SUCCESS : ItemInteractionResult.CONSUME;
        }
        boolean active = state.getValue(ACTIVE);
        boolean isActivation = !active && stack.is(Config.activationItem());
        boolean isUpgrade = active && (stack.is(Config.upgradeItem()) || stack.getItem() instanceof UpgradeToolItem);
        if (!isActivation && !isUpgrade) {
            // Falls through to useWithoutItem (opens the GUI).
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (isActivation) {
            CoreActions.tryActivate((ServerLevel) level, pos, player);
        } else {
            CoreActions.tryUpgrade((ServerLevel) level, pos, player);
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
            Item upgradeItem = Config.upgradeItem();
            Item activationItem = Config.activationItem();
            player.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, p) -> new CoreMenu(containerId, inventory, core),
                    Component.translatable("block.incursion.core")), buf -> {
                buf.writeBlockPos(pos);
                buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(upgradeItem));
                buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(activationItem));
            });
        }
        return InteractionResult.CONSUME;
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
            // An active core that is removed in any way explodes.
            int coreLevel = 1;
            if (level.getBlockEntity(pos) instanceof CoreBlockEntity core) {
                coreLevel = core.getCoreLevel();
                SiegeManager.stopSiege(serverLevel, pos, core);
                SiegeManager.clearSiegeMobs(serverLevel, pos, coreLevel);
            } else {
                CoreWorldData.get(serverLevel.getServer()).clearIfMatches(level.dimension(), pos);
            }
            Fallout.detonate(serverLevel, pos, coreLevel);
        } else if (!state.is(newState.getBlock()) && !level.isClientSide && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof CoreBlockEntity core) {
            Rewards.dropShards(serverLevel, pos, core); // an inactive Core was picked up: keep the shards
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
