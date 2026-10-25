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
    /** Menu button: order (the "Commission" button of the screen). */
    public static final int BUTTON_ORDER = 100;
    /** Menu buttons: one more / one less of the item (only for items that can be ordered several times). */
    public static final int BUTTON_PLUS = 200;
    public static final int BUTTON_MINUS = 201;
    private static final int PLAYER_START = INPUTS + 1;

    private final int villagerId;
    private final List<StoryOffer> offers;
    /** The payment slots hold any number of items (not only one stack). */
    private static final int SLOT_LIMIT = 100000;
    private final SimpleContainer input = new SimpleContainer(INPUTS) {
        @Override
        public int getMaxStackSize() {
            return SLOT_LIMIT;
        }
    };
    /** Seconds the selected commission takes in this amount (set by the server every tick, shown by the screen). */
    private final DataSlot time = DataSlot.standalone();
    private final DataSlot selected = DataSlot.standalone();
    private final DataSlot amount = DataSlot.standalone();
    private final Inventory playerInventory;

    /** Server and client constructor (the client reads what the server wrote in the extra data). */
    public StoryTradeMenu(int containerId, Inventory inventory, int villagerId, List<StoryOffer> offers, int select) {
        super(ModMenus.STORY_TRADE.get(), containerId);
        this.villagerId = villagerId;
        this.offers = List.copyOf(offers);
        this.playerInventory = inventory;
        selected.set(Math.max(0, Math.min(select, Math.max(0, offers.size() - 1))));
        amount.set(1);
        addDataSlot(selected);
        addDataSlot(amount);
        addDataSlot(time);
        // payment slots (x/y as the screen draws them) and the result slot
        for (int i = 0; i < INPUTS; i++) {
            final int slotIndex = i;
            addSlot(new Slot(input, i, 112 + i * 24, 30) {
                @Override
                public int getMaxStackSize() {
                    return SLOT_LIMIT;
                }

                @Override
                public int getMaxStackSize(ItemStack stack) {
                    return SLOT_LIMIT;
                }

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

            @Override
            public boolean isHighlightable() {
                return false; // only shows what is made: it is not a place to click
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

    /** Most items one order makes (the villager holds at most 16 stacks). */
    public static final int MAX_QUANTITY = 16;

    /** Whether the selected commission can be ordered several times at once (the potion). */
    public boolean isMulti() {
        StoryOffer offer = selectedOffer();
        return offer != null && "potion".equals(offer.key());
    }

    /** How many the selected commission can be ordered at once. */
    public int maxAmount() {
        return isMulti() ? MAX_QUANTITY : 1;
    }

    /** How many are ordered (set with the plus and minus buttons). */
    public int amount() {
        return Math.max(1, Math.min(amount.get(), maxAmount()));
    }

    /** The amount the payment in the slots covers: {@link #amount()} if every slot holds enough, otherwise 0. */
    public int quantity() {
        StoryOffer offer = selectedOffer();
        if (offer == null) {
            return 0;
        }
        int times = amount();
        for (int c = 0; c < offer.costs().size(); c++) {
            ItemStack cost = offer.costs().get(c);
            ItemStack have = input.getItem(c);
            if (!have.is(cost.getItem()) || have.getCount() < cost.getCount() * times) {
                return 0;
            }
        }
        return times;
    }

    /** What the result slot shows: the item of the selected commission, as often as is ordered. */
    public int seconds() {
        return time.get();
    }

    @Override
    public void broadcastChanges() {
        if (playerInventory.player instanceof ServerPlayer serverPlayer && selectedOffer() != null) {
            time.set(StoryEvents.craftSeconds(selectedOffer().key(), amount(), serverPlayer.getAbilities().instabuild));
        }
        super.broadcastChanges();
    }

    public ItemStack preview() {
        StoryOffer offer = selectedOffer();
        int times = quantity();
        return offer != null && times > 0 ? offer.result().copyWithCount(times) : ItemStack.EMPTY;
    }

    /** The item that belongs into payment slot {@code index} for the selected commission (empty if none). */
    public ItemStack wanted(int index) {
        StoryOffer offer = selectedOffer();
        return offer != null && index < offer.costs().size()
                ? offer.costs().get(index).copyWithCount(offer.costs().get(index).getCount() * amount()) : ItemStack.EMPTY;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == BUTTON_ORDER) {
            if (player instanceof ServerPlayer serverPlayer) {
                order(serverPlayer);
            }
            return true;
        }
        if (id == BUTTON_PLUS || id == BUTTON_MINUS) {
            amount.set(Math.max(1, Math.min(maxAmount(), amount() + (id == BUTTON_PLUS ? 1 : -1))));
            return true;
        }
        if (id >= 0 && id < offers.size()) {
            selected.set(id);
            amount.set(1);
            return true;
        }
        return false;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId == RESULT) {
            return; // the result slot only shows what is made
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
