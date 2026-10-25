package com.marleyboss.incursion.client;

import com.marleyboss.incursion.menu.StoryPickupMenu;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The pickup window: the villager (his model, name) tells the player he is done with the item; the finished items lie
 * in the slots below the text and are taken like from a chest.
 */
public class StoryPickupScreen extends AbstractContainerScreen<StoryPickupMenu> {
    private static final int COLOR_FRAME = 0xFFC9A040;
    private static final int COLOR_FRAME_DARK = 0xFF6A4A18;
    private static final int COLOR_BG_TOP = 0xF62A1638;
    private static final int COLOR_BG_BOTTOM = 0xF6140A1E;
    private static final int COLOR_SLOT = 0xFF0C0612;
    private static final int COLOR_SLOT_EDGE = 0xFF4A2A66;
    private static final int COLOR_NAME = 0xFFFFC040;
    private static final int COLOR_TEXT = 0xFFE8E0F0;

    public StoryPickupScreen(StoryPickupMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 166;
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
        g.fill(left - 3, top - 3, left + imageWidth + 3, top + imageHeight + 3, ((int) (0x30 + 0x50 * pulse) << 24) | 0xFFB040);
        g.fill(left - 2, top - 2, left + imageWidth + 2, top + imageHeight + 2, COLOR_FRAME_DARK);
        g.fill(left - 1, top - 1, left + imageWidth + 1, top + imageHeight + 1, COLOR_FRAME);
        g.fillGradient(left, top, left + imageWidth, top + imageHeight, COLOR_BG_TOP, COLOR_BG_BOTTOM);
        g.fill(left + 4, top + 76, left + imageWidth - 4, top + 77, COLOR_FRAME_DARK);

        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.level != null ? mc.level.getEntity(menu.villagerId()) : null;
        Component name = entity != null ? entity.getDisplayName() : Component.empty();
        if (entity instanceof LivingEntity living) {
            Component customName = living.getCustomName();
            boolean shown = living.isCustomNameVisible();
            living.setCustomName(null);
            living.setCustomNameVisible(false);
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, left + 6, top + 6, left + 52, top + 70, 28, 0.0625F,
                        mouseX, mouseY, living);
            } finally {
                living.setCustomName(customName);
                living.setCustomNameVisible(shown);
            }
        }
        int textWidth = imageWidth - 64 - 6;
        int y = top + 8;
        for (FormattedCharSequence line : font.split(name.copy().withStyle(net.minecraft.ChatFormatting.BOLD), textWidth)) {
            g.drawString(font, line, left + 60, y, COLOR_NAME, false);
            y += 10;
        }
        y += 3;
        ItemStack first = menu.slots.get(0).getItem();
        Component item = first.isEmpty() ? Component.empty() : first.getHoverName();
        List<FormattedCharSequence> text = font.split(Component.translatable(menu.lineKey(), item), textWidth);
        for (FormattedCharSequence line : text) {
            g.drawString(font, line, left + 60, y, COLOR_TEXT, false);
            y += 10;
        }
        for (Slot slot : menu.slots) {
            int x = left + slot.x;
            int sy = top + slot.y;
            g.fill(x - 1, sy - 1, x + 17, sy + 17, slot.index < StoryPickupMenu.SLOTS && slot.hasItem() ? COLOR_FRAME : COLOR_SLOT_EDGE);
            g.fill(x, sy, x + 16, sy + 16, COLOR_SLOT);
        }
    }
}
