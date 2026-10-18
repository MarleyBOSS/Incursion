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
 * The Incursion Core, in its two lives.
 * <ul>
 *     <li><b>Sarcophagus</b> (inactive): peaceful, can be mined and moved. The Core Rune, a stack of gunpowder and a
 *     stack of blaze powder are laid in with a right-click, in any order; every one of them is a stage of the block
 *     ({@link #RUNE}, {@link #GUNPOWDER}, {@link #BLAZE}, drawn by the renderer). With all three in, a Mythic Potion
 *     thrown onto the block creates the Core and activates it at level 1 (see {@link Ritual}, one active Core per
 *     world). Mining it gives back what was laid in.</li>
 *     <li><b>Core</b> (active): right-click with an Upgrade Tool in the hand pays the cost (from the inventory) and
 *     gains a level; the tool's tier must reach the target level. With {@code upgradeToolRequired} off, the upgrade
 *     item in the hand works too. Right-click with anything else opens the Core GUI. The block entity runs the siege
 *     (see SiegeManager). Active cores cannot be mined in survival; if one is removed in any other way (creative
 *     mode, commands, other mods) or its health drops to 0, it explodes (see Fallout).</li>
 * </ul>
 * The whole block is drawn by {@code SarcophagusRenderer} (triangular faces cannot be a block model).
 */
public class CoreBlock extends BaseEntityBlock {
    public static final MapCodec<CoreBlock> CODEC = simpleCodec(CoreBlock::new);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    /** The Core Rune lies in the Sarcophagus. */
    public static final BooleanProperty RUNE = BooleanProperty.create("rune");
    /** The Sarcophagus holds its full amount of gunpowder. */
    public static final BooleanProperty GUNPOWDER = BooleanProperty.create("gunpowder");
    /** The Sarcophagus holds its full amount of blaze powder. */
    public static final BooleanProperty BLAZE = BooleanProperty.create("blaze");

    public CoreBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false).setValue(RUNE, false)
                .setValue(GUNPOWDER, false).setValue(BLAZE, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE, RUNE, GUNPOWDER, BLAZE);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
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
            return createTickerHelper(type, ModBlockEntities.CORE.get(), CoreBlockEntity::clientTick);
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
        if (!active) {
            // The ritual items (rune, gunpowder, blaze powder) go into the Sarcophagus.
            ItemInteractionResult ritual = Ritual.tryInsert(level, pos, state, player, stack);
            if (ritual != null) {
                return ritual;
            }
        }
        // With the tool requirement on, only an Upgrade Tool in the hand upgrades (the items are paid from the
        // inventory); with it off, the upgrade item itself works as before.
        boolean isUpgrade = active && (stack.getItem() instanceof UpgradeToolItem
                || (!Config.UPGRADE_TOOL_REQUIRED.get() && stack.is(Config.upgradeItem())));
        if (!isUpgrade) {
            // Falls through to useWithoutItem (opens the GUI or shows the ritual status).
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        CoreActions.tryUpgrade((ServerLevel) level, pos, player, stack);
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!state.getValue(ACTIVE) && !Ritual.isReady(state)
                && level.getBlockEntity(pos) instanceof CoreBlockEntity unfinished) {
            // An unfinished Sarcophagus only tells what it still needs.
            return Ritual.showStatus(level, state, player, unfinished);
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof CoreBlockEntity core) {
            Item upgradeItem = Config.upgradeItem();
            Item activationItem = ModItems.MYTHIC_POTION.get();
            player.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, p) -> new CoreMenu(containerId, inventory, core),
                    Component.translatable("gui.incursion.core.title")), buf -> {
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
            Ritual.dropContents(serverLevel, pos, state, core); // and what was laid into the Sarcophagus
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
