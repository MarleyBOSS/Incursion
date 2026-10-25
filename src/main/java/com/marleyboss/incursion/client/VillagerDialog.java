package com.marleyboss.incursion.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import com.marleyboss.incursion.story.OpenTradePayload;
import com.marleyboss.incursion.story.ReopenPayload;
import com.marleyboss.incursion.story.StoryOffer;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/**
 * The dialog panel of a keeper villager: beside his trading screen it shows the villager himself (his model, looking at
 * the mouse like in the inventory), his name and what he says. Several lines are clicked through with "Next".
 * The lines come from the server (DialogPayload) right before the trading screen opens.
 */
public final class VillagerDialog {
    private static final int COLOR_PANEL = 0xF0181020;
    private static final int COLOR_BORDER = 0xFF7A3FB0;
    private static final int COLOR_NAME = 0xFFFFC040;
    private static final int COLOR_TEXT = 0xFFE8E0F0;
    private static final int MODEL_HEIGHT = 74;
    private static final int GAP = 6;
    private static final int MAX_WIDTH = 130;
    private static final int MIN_WIDTH = 90;
    /** A dialog only belongs to a trading screen that opens within this time after it arrived. */
    private static final long FRESH_MILLIS = 3000L;

    private static int entityId = -1;
    private static List<String> lines = List.of();
    private static int page;
    private static long receivedAt;
    private static boolean pending;
    /** The trading screen the dialog belongs to (it stays while the screen is open, also when it is resized). */
    private static Screen screen;
    private static Button next;
    private static List<StoryOffer> offers = List.of();
    /** The icons beside the vanilla trading screen (added as listeners only, so they are drawn here). */
    private static final List<IconButton> icons = new java.util.ArrayList<>();

    private VillagerDialog() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(VillagerDialog::onInit);
        NeoForge.EVENT_BUS.addListener(VillagerDialog::onRender);
        NeoForge.EVENT_BUS.addListener(VillagerDialog::onClosing);
    }

    /** From the payload handler (main thread). */
    public static void receive(int id, List<String> keys, List<StoryOffer> commissions) {
        entityId = id;
        lines = List.copyOf(keys);
        offers = List.copyOf(commissions);
        page = 0;
        receivedAt = System.currentTimeMillis();
        pending = true;
    }

    private static boolean active(Screen current) {
        return current != null && current == screen && !lines.isEmpty();
    }

    /** Where the screen the panel sits beside is: the vanilla trading screen or the mod's own trading screen. */
    private record Host(int left, int top, int height) {
    }

    private static Host host(Screen s) {
        if (s instanceof MerchantScreen merchant) {
            return new Host(merchant.getGuiLeft(), merchant.getGuiTop(), merchant.getYSize());
        }
        if (s instanceof StoryTradeScreen story) {
            return new Host(story.getGuiLeft(), story.getGuiTop(), StoryTradeScreen.HEIGHT);
        }
        return null;
    }

    /** From the mod's trading screen: back to the vanilla one (the server opens it again). */
    static void back(int villagerId) {
        ReopenPayload.send(villagerId); // the server opens his vanilla trading screen, replacing this one
    }

    private static void onInit(ScreenEvent.Init.Post event) {
        Screen current = event.getScreen();
        if (!(current instanceof MerchantScreen) && !(current instanceof StoryTradeScreen)) {
            return;
        }
        if (current instanceof StoryTradeScreen) {
            screen = current; // the dialog goes on beside the mod's trading screen
        } else if (pending && System.currentTimeMillis() - receivedAt <= FRESH_MILLIS) {
            screen = current; // a new trading screen right after the dialog arrived: it is his
            pending = false;
        }
        if (!active(current)) {
            return;
        }
        Panel panel = layout(host(current));
        icons.clear();
        Host host = host(current);
        int x = host.left() + StoryTradeScreen.WIDTH;
        int y = host.top() + 4;
        if (current instanceof MerchantScreen) {
            // one icon per commission, right of the trading screen (the book is closed): a click opens the mod's screen
            for (int i = 0; i < offers.size(); i++) {
                final int index = i;
                IconButton icon = new IconButton(x + 4, y + i * 26, offers.get(i).result(), false, () -> {
                    // the server opens the mod's trading menu, which replaces the vanilla screen: one screen at a time
                    OpenTradePayload.send(entityId, index);
                });
                icons.add(icon);
                event.addListener(icon);
            }
        } else if (current instanceof StoryTradeScreen story) {
            // the same icons: the one of the open commission is lit and joined to the window (the book is open)
            List<StoryOffer> shown = story.getMenu().offers();
            for (int i = 0; i < shown.size(); i++) {
                final int index = i;
                boolean open = index == story.getMenu().selectedIndex();
                IconButton icon = new IconButton(open ? x - 1 : x + 4, y + i * 26, shown.get(i).result(), open, () -> {
                    if (open) {
                        back(entityId); // click the open one: back to the normal trading screen
                    } else {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.gameMode != null) {
                            mc.gameMode.handleInventoryButtonClick(story.getMenu().containerId, index);
                        }
                    }
                });
                icons.add(icon);
                event.addListener(icon);
            }
        }
        next = Button.builder(Component.translatable("gui.incursion.dialog.next"), button -> {
                    if (page < lines.size() - 1) {
                        page++;
                    }
                    updateButton();
                })
                .bounds(panel.x + panel.width - 52, panel.y + panel.height - 20, 48, 16).build();
        event.addListener(next);
        updateButton();
    }

    private static void updateButton() {
        if (next != null) {
            next.visible = page < lines.size() - 1;
        }
    }

    private static void onClosing(ScreenEvent.Closing event) {
        if (event.getScreen() == screen) {
            icons.clear();
            screen = null; // the data stays: the mod's trading screen (and back) shows the same dialog
            next = null;
        }
    }

    /**
     * The icon of a commission beside the trading screen. Closed: dark, a little away from the window. Open (the mod's
     * screen is showing it): lit, glowing, joined to the window.
     */
    private static final class IconButton extends Button {
        private final ItemStack stack;
        private final boolean open;

        IconButton(int x, int y, ItemStack stack, boolean open, Runnable action) {
            super(x, y, open ? 27 : 22, 22, stack.getHoverName(), button -> action.run(), DEFAULT_NARRATION);
            this.stack = stack;
            this.open = open;
            setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                    open ? "gui.incursion.story_trade.icon_back" : "gui.incursion.story_trade.icon", stack.getHoverName())));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            boolean hover = isHoveredOrFocused();
            if (open) {
                float pulse = 0.5F + 0.5F * net.minecraft.util.Mth.sin(System.currentTimeMillis() / 500.0F);
                g.fill(x - 2, y - 2, x + width + 2, y + height + 2, ((int) (0x30 + 0x50 * pulse) << 24) | 0xFFC040);
                g.fill(x, y - 1, x + width + 1, y + height + 1, 0xFFFFD27A);
                g.fill(x, y, x + width, y + height, hover ? 0xFF6A3A90 : 0xFF4A2A66);
                g.renderItem(stack, x + 7, y + 3);
            } else {
                g.fill(x - 1, y - 1, x + width + 1, y + height + 1, hover ? 0xFFC9A040 : 0xFF6A4A18);
                g.fill(x, y, x + width, y + height, hover ? 0xFF3A2050 : 0xFF1A0E26);
                g.renderItem(stack, x + 3, y + 3);
                if (!hover) {
                    g.fill(x, y, x + width, y + height, 0x55000000); // dim: not opened
                }
            }
        }
    }

    private record Panel(int x, int y, int width, int height, List<FormattedCharSequence> name,
                         List<FormattedCharSequence> text) {
    }

    /** Left of the trading screen if there is room, otherwise narrower (down to MIN_WIDTH) over the screen's edge. */
    private static Panel layout(Host merchant) {
        Font font = Minecraft.getInstance().font;
        int left = merchant.left();
        int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, left - GAP - 4));
        int x = Math.max(4, left - GAP - width);
        String key = lines.isEmpty() ? "" : lines.get(Math.min(page, lines.size() - 1));
        List<FormattedCharSequence> text = font.split(Component.translatable(key), width - 12);
        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.level != null ? mc.level.getEntity(entityId) : null;
        Component title = entity != null ? entity.getDisplayName() : Component.empty();
        List<FormattedCharSequence> name = font.split(title.copy().withStyle(ChatFormatting.BOLD), width - 12);
        if (name.size() > 2) {
            name = name.subList(0, 2);
        }
        // as high as the trading screen (it grows only if the text needs more room)
        int needed = MODEL_HEIGHT + 8 + name.size() * 10 + 4 + text.size() * 10 + 24;
        int height = Math.max(merchant.height(), needed);
        int y = merchant.top();
        return new Panel(x, y, width, height, name, text);
    }

    private static void onRender(ScreenEvent.Render.Post event) {
        Host host = host(event.getScreen());
        if (host == null || !active(event.getScreen())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Panel panel = layout(host);
        if (next != null) {
            // the text length changes with the page: keep the button at the bottom of the panel
            next.setX(panel.x + panel.width - 52);
            next.setY(panel.y + panel.height - 20);
        }
        GuiGraphics g = event.getGuiGraphics();
        // the background on the screen's own level (the model is drawn in front of it, the text in front of both)
        g.fill(panel.x - 1, panel.y - 1, panel.x + panel.width + 1, panel.y + panel.height + 1, COLOR_BORDER);
        g.fill(panel.x, panel.y, panel.x + panel.width, panel.y + panel.height, COLOR_PANEL);
        g.fill(panel.x + 4, panel.y + MODEL_HEIGHT + 2, panel.x + panel.width - 4, panel.y + MODEL_HEIGHT + 3, COLOR_BORDER);

        Entity entity = mc.level != null ? mc.level.getEntity(entityId) : null;
        if (entity instanceof LivingEntity living) {
            // His name tag would be drawn over his head and cut off (it shows because he is under the crosshair, even
            // when it is not always visible): without a name for the picture, the panel shows the name below.
            Component customName = living.getCustomName();
            boolean nameShown = living.isCustomNameVisible();
            living.setCustomName(null);
            living.setCustomNameVisible(false);
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, panel.x + 4, panel.y + 6, panel.x + panel.width - 4,
                        panel.y + MODEL_HEIGHT, 30, 0.0625F, event.getMouseX(), event.getMouseY(), living);
            } finally {
                living.setCustomName(customName);
                living.setCustomNameVisible(nameShown);
            }
        }

        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 300.0F);
        int y = panel.y + MODEL_HEIGHT + 6;
        for (FormattedCharSequence line : panel.name) {
            g.drawString(mc.font, line, panel.x + 6, y, COLOR_NAME, false);
            y += 10;
        }
        y += 4;
        for (FormattedCharSequence line : panel.text) {
            g.drawString(mc.font, line, panel.x + 6, y, COLOR_TEXT, false);
            y += 10;
        }
        if (lines.size() > 1) {
            g.drawString(mc.font, (page + 1) + "/" + lines.size(), panel.x + 6, panel.y + panel.height - 15, 0xFF9A90A8, false);
        }
        g.pose().popPose();
        if (next != null && next.visible) {
            next.render(g, event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
        {
            for (IconButton icon : icons) {
                icon.render(g, event.getMouseX(), event.getMouseY(), event.getPartialTick());
            }
        }
    }
}
