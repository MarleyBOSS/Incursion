package com.marleyboss.incursion.block;

import com.marleyboss.incursion.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChestLidController;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Inventory of the loot chest: 54 slots (six rows). Like a vanilla chest it counts who has it open, plays the open and
 * close sounds and drives the lid animation (see the client renderer).
 */
public class LootChestBlockEntity extends RandomizableContainerBlockEntity implements LidBlockEntity {
    public static final int SIZE = 54;

    /** Loaded loot chests (server side), so the siege finds them without scanning blocks. */
    private static final Set<LootChestBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
    private final ChestLidController lidController = new ChestLidController();
    private final ContainerOpenersCounter openersCounter = new ContainerOpenersCounter() {
        @Override
        protected void onOpen(Level level, BlockPos pos, BlockState state) {
            playSound(level, pos, SoundEvents.CHEST_OPEN);
        }

        @Override
        protected void onClose(Level level, BlockPos pos, BlockState state) {
            playSound(level, pos, SoundEvents.CHEST_CLOSE);
        }

        @Override
        protected void openerCountChanged(Level level, BlockPos pos, BlockState state, int oldCount, int newCount) {
            // Tells the clients (block event 1) whether the lid is open.
            level.blockEvent(pos, state.getBlock(), 1, newCount);
        }

        @Override
        protected boolean isOwnContainer(Player player) {
            return player.containerMenu instanceof ChestMenu menu && menu.getContainer() == LootChestBlockEntity.this;
        }
    };

    public LootChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LOOT_CHEST.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            LOADED.add(this);
        }
    }

    private static void playSound(Level level, BlockPos pos, SoundEvent sound) {
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, sound, SoundSource.BLOCKS,
                0.5F, level.random.nextFloat() * 0.1F + 0.9F);
    }

    /** Client tick: moves the lid. */
    public static void lidAnimateTick(Level level, BlockPos pos, BlockState state, LootChestBlockEntity chest) {
        chest.lidController.tickLid();
    }

    @Override
    public boolean triggerEvent(int id, int type) {
        if (id == 1) {
            lidController.shouldBeOpen(type > 0);
            return true;
        }
        return super.triggerEvent(id, type);
    }

    @Override
    public void startOpen(Player player) {
        if (!remove && !player.isSpectator() && level != null) {
            openersCounter.incrementOpeners(player, level, getBlockPos(), getBlockState());
        }
    }

    @Override
    public void stopOpen(Player player) {
        if (!remove && !player.isSpectator() && level != null) {
            openersCounter.decrementOpeners(player, level, getBlockPos(), getBlockState());
        }
    }

    /** Called by the block's scheduled tick: closes the lid when nobody has the chest open any more. */
    public void recheckOpen() {
        if (!remove && level != null) {
            openersCounter.recheckOpeners(level, getBlockPos(), getBlockState());
        }
    }

    @Override
    public float getOpenNess(float partialTick) {
        return lidController.getOpenness(partialTick);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED.remove(this);
    }

    /**
     * The loaded loot chests in the given level within {@code range} blocks (square) of {@code center},
     * nearest first.
     */
    public static List<LootChestBlockEntity> near(Level level, BlockPos center, int range) {
        List<LootChestBlockEntity> result = new ArrayList<>();
        for (LootChestBlockEntity chest : LOADED) {
            if (chest.level != level || chest.isRemoved()) {
                continue;
            }
            BlockPos pos = chest.getBlockPos();
            if (Math.abs(pos.getX() - center.getX()) <= range && Math.abs(pos.getY() - center.getY()) <= range
                    && Math.abs(pos.getZ() - center.getZ()) <= range) {
                result.add(chest);
            }
        }
        result.sort((a, b) -> Double.compare(a.getBlockPos().distSqr(center), b.getBlockPos().distSqr(center)));
        return result;
    }

    /**
     * Puts as much of the stack into the chest as fits (merging with equal stacks first).
     *
     * @return what did not fit (empty if everything was stored)
     */
    public ItemStack insert(ItemStack stack) {
        ItemStack rest = stack.copy();
        boolean changed = false;
        for (int i = 0; i < items.size() && !rest.isEmpty(); i++) {
            ItemStack slot = items.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, rest)) {
                int room = Math.min(slot.getMaxStackSize(), getMaxStackSize()) - slot.getCount();
                int move = Math.min(room, rest.getCount());
                if (move > 0) {
                    slot.grow(move);
                    rest.shrink(move);
                    changed = true;
                }
            }
        }
        for (int i = 0; i < items.size() && !rest.isEmpty(); i++) {
            if (items.get(i).isEmpty()) {
                int move = Math.min(Math.min(rest.getMaxStackSize(), getMaxStackSize()), rest.getCount());
                items.set(i, rest.split(move));
                changed = true;
            }
        }
        if (changed) {
            setChanged();
        }
        return rest;
    }

    // ------------------------------------------------------------------
    // Container
    // ------------------------------------------------------------------

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.incursion.loot_chest");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return ChestMenu.sixRows(containerId, inventory, this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, items, registries);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
        if (!tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, items, registries);
        }
    }
}
