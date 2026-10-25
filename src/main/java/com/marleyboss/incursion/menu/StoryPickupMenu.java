package com.marleyboss.incursion.menu;

import com.marleyboss.incursion.registry.ModMenus;
import com.marleyboss.incursion.story.StoryEvents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The window of an order: while the villager is still working it shows a progress bar (permille and seconds left are
 * synced from the server every tick), and once he is done the finished item lies in the one slot. A click on it (or a
 * shift click) takes everything the order made into the inventory (what does not fit stays with him).
 */
public class StoryPickupMenu extends AbstractContainerMenu {
    private static final int MAX = 16;

    private final int villagerId;
    private final String workingKey;
    private final String readyKey;
    private final Player player;
    /** What the order made (server: read from the villager once he is done; client: the one slot's synced stack). */
    private final SimpleContainer container = new SimpleContainer(MAX);
    private boolean filled;
    private final DataSlot permille = DataSlot.standalone();
    private final DataSlot seconds = DataSlot.standalone();

    public StoryPickupMenu(int containerId, Inventory inventory, int villagerId, String workingKey, String readyKey) {
        super(ModMenus.STORY_PICKUP.get(), containerId);
        this.villagerId = villagerId;
        this.workingKey = workingKey;
        this.readyKey = readyKey;
        this.player = inventory.player;
        addDataSlot(permille);
        addDataSlot(seconds);
        refresh();
        addSlot(new Slot(container, 0, 214, 88) {
            @Override
            public ItemStack getItem() {
                // the one slot shows the whole order: the first item, as many times as the order made
                ItemStack first = ItemStack.EMPTY;
                int total = 0;
                for (int i = 0; i < MAX; i++) {
                    ItemStack stack = container.getItem(i);
                    if (!stack.isEmpty()) {
                        if (first.isEmpty()) {
                            first = stack;
                        }
                        total += stack.getCount();
                    }
                }
                return first.isEmpty() ? ItemStack.EMPTY : first.copyWithCount(total);
            }

            @Override
            public boolean hasItem() {
                return !getItem().isEmpty();
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return false;
            }

            @Override
            public boolean isActive() {
                return isDone();
            }
        });
    }

    public static StoryPickupMenu fromBuffer(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        return new StoryPickupMenu(containerId, inventory, buf.readVarInt(), buf.readUtf(), buf.readUtf());
    }

    public static void write(RegistryFriendlyByteBuf buf, int villagerId, String workingKey, String readyKey) {
        buf.writeVarInt(villagerId);
        buf.writeUtf(workingKey);
        buf.writeUtf(readyKey);
    }

    public int villagerId() {
        return villagerId;
    }

    public String workingKey() {
        return workingKey;
    }

    public String readyKey() {
        return readyKey;
    }

    public boolean isDone() {
        return permille.get() >= 1000;
    }

    public int permille() {
        return permille.get();
    }

    public int secondsLeft() {
        return seconds.get();
    }

    /** Server: the numbers for the bar; once he is done the finished items are put into the slot. */
    private void refresh() {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        int[] progress = StoryEvents.workProgress(player, villagerId);
        seconds.set(progress[0]);
        permille.set(progress[1]);
        if (progress[1] >= 1000 && !filled) {
            filled = true;
            List<ItemStack> items = StoryEvents.finishedItems(serverPlayer, villagerId);
            for (int i = 0; i < items.size() && i < MAX; i++) {
                container.setItem(i, items.get(i).copy());
            }
        }
    }

    @Override
    public void broadcastChanges() {
        refresh();
        super.broadcastChanges();
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player clicker) {
        if (slotId == 0) {
            if (isDone() && clicker instanceof ServerPlayer serverPlayer
                    && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)
                    && StoryEvents.takeFinished(serverPlayer, villagerId)) {
                for (int i = 0; i < MAX; i++) {
                    container.setItem(i, ItemStack.EMPTY);
                }
                StoryEvents.closeNextTick(serverPlayer, this);
            }
            return;
        }
        super.clicked(slotId, button, clickType, clicker);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return StoryEvents.stillAround(player, villagerId);
    }
}
