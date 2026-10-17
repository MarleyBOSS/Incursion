package com.marleyboss.incursion.menu;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.registry.ModBlocks;
import com.marleyboss.incursion.registry.ModMenus;
import com.marleyboss.incursion.table.CoreTableBlockEntity;
import com.marleyboss.incursion.table.CoreTableLogic;
import com.marleyboss.incursion.table.CoreTableLogic.Entry;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
 * Menu of the Core Table: one item slot (the item stays in the table while it works), the player inventory, a list of
 * the item's enchantments (selected by menu buttons 0..99), a confirm button (100) and a reset button (101). All rules
 * run on the server; the numbers the screen shows are synced through the data slots. The list itself is derived from
 * the item in the slot on both sides, and a bit mask tells the client which entries can be raised right now.
 */
public class CoreTableMenu extends AbstractContainerMenu {
    public static final int BUTTON_CONFIRM = 100;
    public static final int BUTTON_RESET = 101;

    public static final int DATA_HAS_CORE = 0;
    public static final int DATA_CORE_LEVEL = 1;
    public static final int DATA_SHARDS = 2;
    /** Levels above the vanilla maximum that the Core level allows (before single limits). */
    public static final int DATA_EXTRA = 3;
    /** Index of the selected enchantment in the list plus one (0 = nothing selected). */
    public static final int DATA_SELECTED = 4;
    public static final int DATA_SEL_SHARD_COST = 5;
    public static final int DATA_SEL_XP_COST = 6;
    public static final int DATA_SEL_TARGET = 7;
    public static final int DATA_SEL_SECONDS = 8;
    public static final int DATA_PLAYER_XP = 9;
    public static final int DATA_CREATIVE = 10;
    /** 0 = idle, 1 = working, 2 = done. */
    public static final int DATA_JOB_STATE = 11;
    public static final int DATA_JOB_SECONDS_LEFT = 12;
    public static final int DATA_JOB_SECONDS_TOTAL = 13;
    /** Index of the enchantment of the job in the list plus one. */
    public static final int DATA_JOB_INDEX = 14;
    public static final int DATA_JOB_LEVEL = 15;
    /** 1 if enchanting may be started now (evening, or creative mode). */
    public static final int DATA_TIME_OK = 16;
    /** Bit i = entry i (0..14) can be raised now. */
    public static final int DATA_READY_LO = 17;
    /** Bit i = entry 15 + i can be raised now. */
    public static final int DATA_READY_HI = 18;
    public static final int DATA_COUNT = 19;

    public static final int ITEM_SLOT_X = 8;
    public static final int ITEM_SLOT_Y = 42;
    public static final int INVENTORY_X = 67;
    public static final int INVENTORY_Y = 230;

    private final Container container;
    @Nullable
    private final CoreTableBlockEntity table;
    private final Player player;
    private final SimpleContainerData data = new SimpleContainerData(DATA_COUNT);
    @Nullable
    private String selectedKey;

    /** Client side. */
    public CoreTableMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, null);
    }

    /** Server side (the client constructor goes through here with {@code null}). */
    public CoreTableMenu(int containerId, Inventory inventory, @Nullable CoreTableBlockEntity table) {
        super(ModMenus.CORE_TABLE.get(), containerId);
        this.table = table;
        this.player = inventory.player;
        this.container = table != null ? table.getContainer() : new SimpleContainer(1);
        addSlot(new Slot(container, 0, ITEM_SLOT_X, ITEM_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return jobState() == CoreTableLogic.JOB_IDLE && (stack.isEnchanted() || stack.isEnchantable());
            }

            @Override
            public boolean mayPickup(Player picker) {
                return jobState() != CoreTableLogic.JOB_WORKING;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, INVENTORY_X + col * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
        refresh();
    }

    public int get(int index) {
        return data.get(index);
    }

    private int jobState() {
        return table != null ? table.getJobState() : data.get(DATA_JOB_STATE);
    }

    /** The list of the item in the slot (same on both sides). */
    public List<Entry> entries() {
        return CoreTableLogic.entries(container.getItem(0), player.level().registryAccess());
    }

    /** Recomputes the synced numbers (server only). */
    private void refresh() {
        if (table == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        CoreBlockEntity core = CoreTableLogic.activeCore(server);
        boolean creative = player.getAbilities().instabuild;
        boolean evening = creative || CoreTableLogic.isEvening(server);
        boolean busy = table.getJobState() != CoreTableLogic.JOB_IDLE;
        List<Entry> list = entries();
        Entry over = CoreTableLogic.overEntry(list);
        int maskLo = 0;
        int maskHi = 0;
        int selected = -1;
        int jobIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            Entry entry = list.get(i);
            boolean ready = CoreTableLogic.state(entry, over, busy, core, evening) == CoreTableLogic.OK;
            if (ready && i < 15) {
                maskLo |= 1 << i;
            } else if (ready && i < 30) {
                maskHi |= 1 << (i - 15);
            }
            if (entry.key().equals(selectedKey)) {
                selected = ready ? i : -1;
            }
            if (entry.key().equals(table.getJobKey())) {
                jobIndex = i;
            }
        }
        if (selected < 0) {
            selectedKey = null;
        }
        int coreLevel = core == null ? 0 : core.getCoreLevel();
        put(DATA_HAS_CORE, core == null ? 0 : 1);
        put(DATA_CORE_LEVEL, coreLevel);
        put(DATA_SHARDS, core == null ? 0 : core.getShards());
        put(DATA_EXTRA, core == null ? 0 : Config.tableExtraLevels(coreLevel));
        put(DATA_SELECTED, selected + 1);
        if (selected >= 0) {
            Entry entry = list.get(selected);
            put(DATA_SEL_SHARD_COST, CoreTableLogic.shardCost(entry));
            put(DATA_SEL_XP_COST, CoreTableLogic.xpCost(entry));
            put(DATA_SEL_TARGET, entry.level() + 1);
            put(DATA_SEL_SECONDS, CoreTableLogic.durationTicks(entry) / 20);
        } else {
            put(DATA_SEL_SHARD_COST, 0);
            put(DATA_SEL_XP_COST, 0);
            put(DATA_SEL_TARGET, 0);
            put(DATA_SEL_SECONDS, 0);
        }
        put(DATA_PLAYER_XP, player.experienceLevel);
        put(DATA_CREATIVE, creative ? 1 : 0);
        put(DATA_JOB_STATE, table.getJobState());
        put(DATA_JOB_SECONDS_LEFT, (table.getJobLeft() + 19) / 20);
        put(DATA_JOB_SECONDS_TOTAL, (table.getJobTotal() + 19) / 20);
        put(DATA_JOB_INDEX, jobIndex + 1);
        put(DATA_JOB_LEVEL, table.getJobLevel());
        put(DATA_TIME_OK, evening ? 1 : 0);
        put(DATA_READY_LO, maskLo);
        put(DATA_READY_HI, maskHi);
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
        if (table == null || !(clicker.level() instanceof ServerLevel level)) {
            return false;
        }
        MinecraftServer server = level.getServer();
        CoreBlockEntity core = CoreTableLogic.activeCore(server);
        boolean evening = clicker.getAbilities().instabuild || CoreTableLogic.isEvening(server);
        boolean busy = table.getJobState() != CoreTableLogic.JOB_IDLE;
        List<Entry> list = entries();
        Entry over = CoreTableLogic.overEntry(list);
        if (id >= 0 && id < 100) {
            if (id < list.size() && CoreTableLogic.state(list.get(id), over, busy, core, evening) == CoreTableLogic.OK) {
                selectedKey = list.get(id).key();
            }
            refresh();
            return true;
        }
        if (id == BUTTON_RESET) {
            selectedKey = null;
            refresh();
            return true;
        }
        if (id != BUTTON_CONFIRM || selectedKey == null) {
            return false;
        }
        for (Entry entry : list) {
            if (entry.key().equals(selectedKey)
                    && CoreTableLogic.state(entry, over, busy, core, evening) == CoreTableLogic.OK && core != null) {
                CoreTableLogic.tryStart(clicker, entry, core, table);
                break;
            }
        }
        selectedKey = null;
        refresh();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot.hasItem()) {
            if (index == 0 && !slot.mayPickup(clicker)) {
                return ItemStack.EMPTY;
            }
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
    public boolean stillValid(Player viewer) {
        if (table == null || table.getLevel() == null) {
            return true;
        }
        return stillValid(ContainerLevelAccess.create(table.getLevel(), table.getBlockPos()), viewer,
                ModBlocks.CORE_TABLE.get());
    }
}
