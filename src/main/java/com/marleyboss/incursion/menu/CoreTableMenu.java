package com.marleyboss.incursion.menu;

import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.registry.ModBlocks;
import com.marleyboss.incursion.registry.ModMenus;
import com.marleyboss.incursion.table.CoreTableData;
import com.marleyboss.incursion.table.CoreTableLogic;
import com.marleyboss.incursion.table.CoreTableLogic.Entry;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Menu of the Core Table: one item slot, the player inventory, a list of enchantments (selected by menu buttons 0..99)
 * and two actions (unlock / raise). All rules run on the server; the numbers the screen shows are synced through the
 * data slots (the list itself is derived from the item in the slot on both sides).
 */
public class CoreTableMenu extends AbstractContainerMenu {
    public static final int BUTTON_UNLOCK = 100;
    public static final int BUTTON_RAISE = 101;

    public static final int DATA_HAS_CORE = 0;
    public static final int DATA_CORE_LEVEL = 1;
    public static final int DATA_SHARDS = 2;
    /** Levels above the vanilla maximum that the Core level allows. */
    public static final int DATA_EXTRA = 3;
    /** Index of the selected enchantment in the list plus one (0 = nothing selected). */
    public static final int DATA_SELECTED = 4;
    public static final int DATA_SEL_STATE = 5;
    public static final int DATA_SEL_LEVEL = 6;
    public static final int DATA_SEL_VANILLA_MAX = 7;
    public static final int DATA_SEL_CAP = 8;
    public static final int DATA_SEL_SHARD_COST = 9;
    public static final int DATA_SEL_XP_COST = 10;
    public static final int DATA_PLAYER_XP = 11;
    public static final int DATA_CREATIVE = 12;
    public static final int DATA_COUNT = 13;

    public static final int ITEM_SLOT_X = 8;
    public static final int ITEM_SLOT_Y = 44;
    public static final int INVENTORY_Y = 164;

    private final Container container;
    private final ContainerLevelAccess access;
    private final Player player;
    private final SimpleContainerData data = new SimpleContainerData(DATA_COUNT);
    @Nullable
    private String selectedKey;

    /** Client side. */
    public CoreTableMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, ContainerLevelAccess.NULL);
    }

    /** Server side (the client constructor goes through here with a null access). */
    public CoreTableMenu(int containerId, Inventory inventory, ContainerLevelAccess access) {
        super(ModMenus.CORE_TABLE.get(), containerId);
        this.access = access;
        this.player = inventory.player;
        this.container = new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                CoreTableMenu.this.slotsChanged(this);
            }
        };
        addSlot(new Slot(container, 0, ITEM_SLOT_X, ITEM_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.isEnchanted();
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
        refresh();
    }

    public int get(int index) {
        return data.get(index);
    }

    /** The enchantments of the item in the table slot, in list order. */
    public List<Entry> candidates() {
        return CoreTableLogic.candidates(container.getItem(0));
    }

    /** Recomputes the synced numbers (server only). */
    private void refresh() {
        if (player.level().isClientSide || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        CoreBlockEntity core = CoreTableLogic.activeCore(server);
        CoreTableData unlocks = CoreTableData.get(server);
        List<Entry> list = candidates();
        int selected = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key().equals(selectedKey)) {
                selected = i;
            }
        }
        if (selected < 0) {
            selectedKey = null;
        }
        int coreLevel = core == null ? 0 : core.getCoreLevel();
        put(DATA_HAS_CORE, core == null ? 0 : 1);
        put(DATA_CORE_LEVEL, coreLevel);
        put(DATA_SHARDS, core == null ? 0 : core.getShards());
        put(DATA_EXTRA, core == null ? 0 : com.marleyboss.incursion.Config.tableExtraLevels(coreLevel));
        put(DATA_SELECTED, selected + 1);
        put(DATA_PLAYER_XP, player.experienceLevel);
        put(DATA_CREATIVE, player.getAbilities().instabuild ? 1 : 0);
        if (selected >= 0) {
            Entry entry = list.get(selected);
            int state = CoreTableLogic.state(entry, core, unlocks);
            put(DATA_SEL_STATE, state);
            put(DATA_SEL_LEVEL, entry.level());
            put(DATA_SEL_VANILLA_MAX, entry.vanillaMax());
            put(DATA_SEL_CAP, core == null ? entry.vanillaMax() : CoreTableLogic.cap(entry, coreLevel));
            put(DATA_SEL_SHARD_COST, CoreTableLogic.shardCost(entry, state));
            put(DATA_SEL_XP_COST, CoreTableLogic.xpCost(entry, state));
        } else {
            put(DATA_SEL_STATE, CoreTableLogic.STATE_NONE);
            put(DATA_SEL_LEVEL, 0);
            put(DATA_SEL_VANILLA_MAX, 0);
            put(DATA_SEL_CAP, 0);
            put(DATA_SEL_SHARD_COST, 0);
            put(DATA_SEL_XP_COST, 0);
        }
    }

    /** Data slots are synced as 16-bit numbers. */
    private void put(int index, int value) {
        data.set(index, Math.max(0, Math.min(value, Short.MAX_VALUE)));
    }

    @Override
    public void broadcastChanges() {
        refresh();
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player clicker, int id) {
        if (!(clicker.level() instanceof ServerLevel level)) {
            return false;
        }
        List<Entry> list = candidates();
        if (id >= 0 && id < 100) {
            if (id < list.size()) {
                selectedKey = list.get(id).key();
                refresh();
            }
            return true;
        }
        if (id != BUTTON_UNLOCK && id != BUTTON_RAISE) {
            return false;
        }
        Entry selected = null;
        for (Entry entry : list) {
            if (entry.key().equals(selectedKey)) {
                selected = entry;
            }
        }
        if (selected == null) {
            return false;
        }
        MinecraftServer server = level.getServer();
        CoreBlockEntity core = CoreTableLogic.activeCore(server);
        CoreTableData unlocks = CoreTableData.get(server);
        boolean done;
        if (id == BUTTON_UNLOCK) {
            done = CoreTableLogic.tryUnlock(clicker, selected, core, unlocks);
        } else {
            done = CoreTableLogic.tryRaise(clicker, container.getItem(0), selected, core, unlocks);
            if (done) {
                container.setChanged();
            }
        }
        if (done) {
            access.execute((l, pos) -> l.playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS,
                    1.0F, l.random.nextFloat() * 0.1F + 0.9F));
        }
        refresh();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index == 0) {
                if (!moveItemStackTo(stack, 1, slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }

    @Override
    public void removed(Player closing) {
        super.removed(closing);
        access.execute((l, pos) -> clearContainer(closing, container));
    }

    @Override
    public boolean stillValid(Player viewer) {
        return stillValid(access, viewer, ModBlocks.CORE_TABLE.get());
    }
}
