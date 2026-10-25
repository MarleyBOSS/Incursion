package com.marleyboss.incursion.menu;

import com.marleyboss.incursion.registry.ModMenus;
import com.marleyboss.incursion.story.StoryEvents;
import com.marleyboss.incursion.story.StoryOffer;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The mod's trading menu of a keeper villager: three payment slots, a result slot that only shows what is made, and
 * the player inventory. The selected commission (menu button = its index) decides which items it asks for. Clicking
 * the result slot with the payment in place sends the order (server): the payment is used up and the villager makes the
 * item at his job site; it is not given here, the player fetches it later from the villager.
 */
public class StoryTradeMenu extends AbstractContainerMenu {
    public static final int INPUTS = 3;
    public static final int RESULT = INPUTS;
    private static final int PLAYER_START = INPUTS + 1;

    private final int villagerId;
    private final List<StoryOffer> offers;
    private final SimpleContainer input = new SimpleContainer(INPUTS);
    private final DataSlot selected = DataSlot.standalone();
    private final Inventory playerInventory;

    /** Server and client constructor (the client reads what the server wrote in the extra data). */
    public StoryTradeMenu(int containerId, Inventory inventory, int villagerId, List<StoryOffer> offers, int select) {
        super(ModMenus.STORY_TRADE.get(), containerId);
        this.villagerId = villagerId;
        this.offers = List.copyOf(offers);
        this.playerInventory = inventory;
        selected.set(Math.max(0, Math.min(select, Math.max(0, offers.size() - 1))));
        addDataSlot(selected);
        // payment slots (x/y as the screen draws them) and the result slot
        for (int i = 0; i < INPUTS; i++) {
            final int slotIndex = i;
            addSlot(new Slot(input, i, 112 + i * 24, 30) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    ItemStack want = wanted(slotIndex);
                    return !want.isEmpty() && stack.is(want.getItem());
                }
            });
        }
        addSlot(new Slot(new SimpleContainer(1), 0, 236, 30) {
            @Override
            public ItemStack getItem() {
                return preview();
            }

            @Override
            public boolean hasItem() {
                return !preview().isEmpty();
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return false;
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 108 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 108 + col * 18, 142));
        }
    }

    public static StoryTradeMenu fromBuffer(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        int villager = buf.readVarInt();
        int select = buf.readVarInt();
        List<StoryOffer> offers = StoryOffer.STREAM_CODEC.apply(net.minecraft.network.codec.ByteBufCodecs.list()).decode(buf);
        return new StoryTradeMenu(containerId, inventory, villager, offers, select);
    }

    public static void write(RegistryFriendlyByteBuf buf, int villagerId, int select, List<StoryOffer> offers) {
        buf.writeVarInt(villagerId);
        buf.writeVarInt(select);
        StoryOffer.STREAM_CODEC.apply(net.minecraft.network.codec.ByteBufCodecs.list()).encode(buf, offers);
    }

    public int villagerId() {
        return villagerId;
    }

    public List<StoryOffer> offers() {
        return offers;
    }

    public int selectedIndex() {
        return selected.get();
    }

    public StoryOffer selectedOffer() {
        return offers.isEmpty() ? null : offers.get(Math.min(selected.get(), offers.size() - 1));
    }

    /** Most items one order makes (the pickup window has 9 slots). */
    public static final int MAX_QUANTITY = 9;

    /**
     * How many times the payment in the slots covers the selected commission (cost i lies in payment slot i); 0 if
     * something is missing or wrong. The rune can be ordered only once.
     */
    public int quantity() {
        StoryOffer offer = selectedOffer();
        if (offer == null) {
            return 0;
        }
        int times = MAX_QUANTITY;
        for (int c = 0; c < offer.costs().size(); c++) {
            ItemStack cost = offer.costs().get(c);
            ItemStack have = input.getItem(c);
            if (!have.is(cost.getItem())) {
                return 0;
            }
            times = Math.min(times, have.getCount() / Math.max(1, cost.getCount()));
        }
        return "rune".equals(offer.key()) ? Math.min(1, times) : times;
    }

    /** What the result slot shows: the item of the selected commission, as often as the payment covers. */
    public ItemStack preview() {
        StoryOffer offer = selectedOffer();
        int times = quantity();
        return offer != null && times > 0 ? offer.result().copyWithCount(times) : ItemStack.EMPTY;
    }

    /** The item that belongs into payment slot {@code index} for the selected commission (empty if none). */
    public ItemStack wanted(int index) {
        StoryOffer offer = selectedOffer();
        return offer != null && index < offer.costs().size() ? offer.costs().get(index) : ItemStack.EMPTY;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id >= 0 && id < offers.size()) {
            selected.set(id);
            return true;
        }
        return false;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId == RESULT) {
            // the result slot is never moved: a click orders (server), the item comes later from the villager
            if (player instanceof ServerPlayer serverPlayer && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
                order(serverPlayer);
            }
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    private void order(ServerPlayer player) {
        StoryOffer offer = selectedOffer();
        int times = quantity();
        if (offer == null || times <= 0) {
            return;
        }
        if (!StoryEvents.beginOrder(player, villagerId, offer, times)) {
            return;
        }
        for (int c = 0; c < offer.costs().size(); c++) {
            input.getItem(c).shrink(offer.costs().get(c).getCount() * times);
        }
        input.setChanged();
        broadcastChanges();
        StoryEvents.closeNextTick(player, this);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (index != RESULT && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            moved = stack.copy();
            boolean ok;
            if (index < INPUTS) {
                ok = moveItemStackTo(stack, PLAYER_START, slots.size(), true);
            } else {
                ok = false;
                for (int i = 0; i < INPUTS && !ok; i++) {
                    ItemStack want = wanted(i);
                    if (!want.isEmpty() && stack.is(want.getItem())) {
                        ok = moveItemStackTo(stack, i, i + 1, false);
                    }
                }
            }
            if (!ok) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return moved;
    }

    @Override
    public boolean stillValid(Player player) {
        return StoryEvents.stillAround(player, villagerId);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // whatever was laid into the payment slots goes back to the player
        List<ItemStack> back = new ArrayList<>();
        for (int i = 0; i < INPUTS; i++) {
            ItemStack stack = input.removeItemNoUpdate(i);
            if (!stack.isEmpty()) {
                back.add(stack);
            }
        }
        for (ItemStack stack : back) {
            if (player.isAlive() && !(player instanceof ServerPlayer sp && sp.hasDisconnected())) {
                playerInventory.placeItemBackInInventory(stack);
            } else {
                player.drop(stack, false);
            }
        }
    }
}
