package com.marleyboss.incursion.menu;

import com.marleyboss.incursion.registry.ModMenus;
import com.marleyboss.incursion.story.StoryEvents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The pickup window: the villager says he is done and the finished item lies in one slot. Taking it (click or shift
 * click) clears it from the villager. If the window is closed without taking it, it stays with him.
 */
public class StoryPickupMenu extends AbstractContainerMenu {
    private final int villagerId;
    private final String lineKey;
    private final SimpleContainer container = new SimpleContainer(SLOTS);
    public static final int SLOTS = 9;

    public StoryPickupMenu(int containerId, Inventory inventory, int villagerId, List<ItemStack> items, String lineKey) {
        super(ModMenus.STORY_PICKUP.get(), containerId);
        this.villagerId = villagerId;
        this.lineKey = lineKey;
        for (int i = 0; i < items.size() && i < SLOTS; i++) {
            container.setItem(i, items.get(i).copy());
        }
        for (int i = 0; i < SLOTS; i++) {
            addSlot(new Slot(container, i, 8 + i * 18, 54) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }
        // what is taken is gone from the villager at once (not only when the window closes)
        container.addListener(changed -> {
            if (inventory.player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                List<ItemStack> rest = new java.util.ArrayList<>();
                for (int i = 0; i < SLOTS; i++) {
                    rest.add(container.getItem(i).copy());
                }
                StoryEvents.syncReady(serverPlayer, villagerId, rest);
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 142));
        }
    }

    public static StoryPickupMenu fromBuffer(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        int villager = buf.readVarInt();
        String line = buf.readUtf();
        List<ItemStack> items = ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf);
        return new StoryPickupMenu(containerId, inventory, villager, items, line);
    }

    public static void write(RegistryFriendlyByteBuf buf, int villagerId, String lineKey, List<ItemStack> items) {
        buf.writeVarInt(villagerId);
        buf.writeUtf(lineKey);
        ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, items);
    }

    public int villagerId() {
        return villagerId;
    }

    public String lineKey() {
        return lineKey;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < SLOTS) {
            if (!moveItemStackTo(stack, SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY; // nothing can be laid into the window
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return StoryEvents.stillAround(player, villagerId);
    }
}
