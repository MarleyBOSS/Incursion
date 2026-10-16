package com.marleyboss.incursion.block;

import com.marleyboss.incursion.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Inventory of the loot chest: 54 slots (six rows). */
public class LootChestBlockEntity extends RandomizableContainerBlockEntity {
    public static final int SIZE = 54;

    /** Loaded loot chests (server side), so the siege finds them without scanning blocks. */
    private static final Set<LootChestBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

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

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED.remove(this);
    }

    /**
     * The loaded loot chests in the given level within {@code range} blocks (square) of {@code center},
     * nearest first.
     */
    public static List<LootChestBlockEntity> near(net.minecraft.world.level.Level level, BlockPos center, int range) {
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
