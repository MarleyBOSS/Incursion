package com.marleyboss.incursion.client;

import com.marleyboss.incursion.menu.StoryPickupMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The window of an order. Left the villager in a framed picture, right his name and what he says; below, while he is
 * still working, a progress bar, and once he is done the one slot with the finished item (a click takes it).
 */
public class StoryPickupScreen extends AbstractContainerScreen<StoryPickupMenu> {
    private static final int W = 240;
    private static final int H = 112;
    private static final int COLOR_FRAME = 0xFFC9A040;
    private static final int COLOR_FRAME_DARK = 0xFF6A4A18;
    private static final int COLOR_BG_TOP = 0xF62A1638;
    private static final int COLOR_BG_BOTTOM = 0xF6140A1E;
    private static final int COLOR_SLOT = 0xFF0C0612;
    private static final int COLOR_EDGE = 0xFF4A2A66;
    private static final int COLOR_NAME = 0xFFFFC040;
    private static final int COLOR_TEXT = 0xFFE8E0F0;
    private static final int COLOR_DIM = 0xFF9A90A8;
    private static final int TEXT_X = 76;
    private static final int TEXT_W = W - TEXT_X - 10;

    public StoryPickupScreen(StoryPickupMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = W;
        imageHeight = H;
        titleLabelY = 10000;
        inventoryLabelY = 10000;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int left = leftPos;
        int top = topPos;
        float pulse = 0.5F + 0.5F * Mth.sin(System.currentTimeMillis() / 600.0F);
        g.fill(left - 3, top - 3, left + W + 3, top + H + 3, ((int) (0x30 + 0x50 * pulse) << 24) | 0xFFB040);
        g.fill(left - 2, top - 2, left + W + 2, top + H + 2, COLOR_FRAME_DARK);
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, COLOR_FRAME);
        g.fillGradient(left, top, left + W, top + H, COLOR_BG_TOP, COLOR_BG_BOTTOM);

        // the villager in a framed picture
        g.fill(left + 7, top + 7, left + 69, top + H - 7, COLOR_EDGE);
        g.fillGradient(left + 8, top + 8, left + 68, top + H - 8, 0xFF1A0E26, 0xFF0C0612);
        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.level != null ? mc.level.getEntity(menu.villagerId()) : null;
        Component name = entity != null ? entity.getDisplayName() : Component.empty();
        if (entity instanceof LivingEntity living) {
            Component customName = living.getCustomName();
            boolean shown = living.isCustomNameVisible();
            living.setCustomName(null);
            living.setCustomNameVisible(false);
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, left + 8, top + 8, left + 68, top + H - 8, 38,
                        0.0625F, mouseX, mouseY, living);
            } finally {
                living.setCustomName(customName);
                living.setCustomNameVisible(shown);
            }
        }

        // name, line, text
        int y = top + 10;
        for (FormattedCharSequence line : font.split(name.copy().withStyle(ChatFormatting.BOLD), TEXT_W)) {
            g.drawString(font, line, left + TEXT_X, y, COLOR_NAME, false);
            y += 10;
        }
        g.fill(left + TEXT_X, y + 1, left + W - 10, y + 2, COLOR_FRAME_DARK);
        y += 6;
        boolean done = menu.isDone();
        Component say;
        if (done) {
            ItemStack stack = menu.slots.get(0).getItem();
            Component item = stack.isEmpty() ? Component.empty()
                    : stack.getCount() > 1 ? Component.literal(stack.getCount() + "× ").append(stack.getHoverName())
                    : stack.getHoverName();
            say = Component.translatable(menu.readyKey(), item);
        } else {
            say = Component.translatable(menu.workingKey());
        }
        List<FormattedCharSequence> lines = font.split(say, TEXT_W);
        for (int i = 0; i < lines.size() && i < 5; i++) {
            g.drawString(font, lines.get(i), left + TEXT_X, y, COLOR_TEXT, false);
            y += 10;
        }

        if (done) {
            // the item waits in its slot: the frame glows, so it is clear that it can be taken
            int x = left + menu.slots.get(0).x;
            int sy = top + menu.slots.get(0).y;
            g.fill(x - 2, sy - 2, x + 18, sy + 18, ((int) (0x60 + 0x80 * pulse) << 24) | 0xFFC040);
            g.fill(x - 1, sy - 1, x + 17, sy + 17, COLOR_FRAME);
            g.fill(x, sy, x + 16, sy + 16, COLOR_SLOT);
        } else {
            // progress bar
            int x1 = left + TEXT_X;
            int x2 = left + W - 10;
            int y1 = top + H - 24;
            int y2 = y1 + 12;
            g.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, COLOR_EDGE);
            g.fill(x1, y1, x2, y2, COLOR_SLOT);
            int filled = (x2 - x1) * menu.permille() / 1000;
            if (filled > 0) {
                g.fillGradient(x1, y1, x1 + filled, y2, 0xFFE0B050, 0xFF9A6A20);
                g.fill(x1, y1, x1 + filled, y1 + 1, 0x66FFFFFF);
            }
            int seconds = menu.secondsLeft();
            String label = menu.permille() / 10 + " %   ·   " + (seconds / 60) + ":" + String.format("%02d", seconds % 60);
            g.drawString(font, label, (x1 + x2) / 2 - font.width(label) / 2, y1 + 2, COLOR_TEXT, true);
        }
    }
}
