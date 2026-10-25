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

    private VillagerDialog() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(VillagerDialog::onInit);
        NeoForge.EVENT_BUS.addListener(VillagerDialog::onRender);
        NeoForge.EVENT_BUS.addListener(VillagerDialog::onClosing);
    }

    /** From the payload handler (main thread). */
    public static void receive(int id, List<String> keys) {
        entityId = id;
        lines = List.copyOf(keys);
        page = 0;
        receivedAt = System.currentTimeMillis();
        pending = true;
    }

    private static boolean active(Screen current) {
        return current != null && current == screen && !lines.isEmpty();
    }

    private static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof MerchantScreen merchant)) {
            return;
        }
        if (pending && System.currentTimeMillis() - receivedAt <= FRESH_MILLIS) {
            screen = merchant; // a new trading screen right after the dialog arrived: it is his
            pending = false;
        }
        if (!active(merchant)) {
            return;
        }
        Panel panel = layout(merchant);
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
            screen = null;
            next = null;
            lines = List.of();
        }
    }

    private record Panel(int x, int y, int width, int height, List<FormattedCharSequence> name,
                         List<FormattedCharSequence> text) {
    }

    /** Left of the trading screen if there is room, otherwise narrower (down to MIN_WIDTH) over the screen's edge. */
    private static Panel layout(MerchantScreen merchant) {
        Font font = Minecraft.getInstance().font;
        int left = merchant.getGuiLeft();
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
        int height = Math.max(merchant.getYSize(), needed);
        int y = merchant.getGuiTop();
        return new Panel(x, y, width, height, name, text);
    }

    private static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof MerchantScreen merchant) || !active(merchant)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Panel panel = layout(merchant);
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
            // his name tag would be drawn over his head (and cut off): hidden for the picture, the panel shows the name
            boolean nameShown = living.isCustomNameVisible();
            living.setCustomNameVisible(false);
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, panel.x + 4, panel.y + 6, panel.x + panel.width - 4,
                    panel.y + MODEL_HEIGHT, 30, 0.0625F, event.getMouseX(), event.getMouseY(), living);
            living.setCustomNameVisible(nameShown);
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
    }
}
