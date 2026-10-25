package com.marleyboss.incursion.client;

import com.marleyboss.incursion.menu.StoryTradeMenu;
import com.marleyboss.incursion.story.StoryOffer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The mod's trading screen of a keeper villager. It takes the place of his vanilla trading screen (same size and place,
 * the dialog panel stays beside it), but in the mod's colours: dark violet with a gold frame. Left the commissions he
 * takes, right the payment slots (the item that belongs into a slot is shown dimmed in it), the result slot and below
 * the player's inventory. Clicking the result orders; the villager makes it and keeps it for the player. "Back" (top
 * right) returns to his vanilla trading screen.
 */
public class StoryTradeScreen extends AbstractContainerScreen<StoryTradeMenu> {
    public static final int WIDTH = 276;
    public static final int HEIGHT = 166;
    private static final int COLOR_FRAME = 0xFFC9A040;
    private static final int COLOR_FRAME_DARK = 0xFF6A4A18;
    private static final int COLOR_BG_TOP = 0xF62A1638;
    private static final int COLOR_BG_BOTTOM = 0xF6140A1E;
    private static final int COLOR_SLOT = 0xFF0C0612;
    private static final int COLOR_SLOT_EDGE = 0xFF4A2A66;
    private static final int COLOR_TITLE = 0xFFFFD27A;
    private static final int COLOR_TEXT = 0xFFE8E0F0;
    private static final int COLOR_DIM = 0xFF9A90A8;
    private static final ResourceLocation ARROW = ResourceLocation.withDefaultNamespace("container/villager/trade_arrow");
    private static final int ROW_X = 5;
    private static final int ROW_Y = 18;
    private static final int ROW_W = 88;
    private static final int ROW_H = 20;

    public StoryTradeScreen(StoryTradeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        inventoryLabelY = 10000; // the labels are drawn by hand
        titleLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.translatable("gui.incursion.story_trade.back"),
                        button -> VillagerDialog.back(menu.villagerId()))
                .bounds(leftPos + WIDTH - 50, topPos + 3, 46, 13).build());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<StoryOffer> offers = menu.offers();
        for (int i = 0; i < offers.size() && i < 7; i++) {
            int x = leftPos + ROW_X;
            int y = topPos + ROW_Y + i * ROW_H;
            if (mouseX >= x && mouseX < x + ROW_W && mouseY >= y && mouseY < y + ROW_H) {
                if (minecraft != null && minecraft.gameMode != null) {
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, i);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
        long time = System.currentTimeMillis();
        // the frame breathes a little: it is no ordinary trade
        float pulse = 0.5F + 0.5F * Mth.sin(time / 600.0F);
        int glow = ((int) (0x30 + 0x50 * pulse) << 24) | 0xFFB040;
        g.fill(left - 3, top - 3, left + WIDTH + 3, top + HEIGHT + 3, glow);
        g.fill(left - 2, top - 2, left + WIDTH + 2, top + HEIGHT + 2, COLOR_FRAME_DARK);
        g.fill(left - 1, top - 1, left + WIDTH + 1, top + HEIGHT + 1, COLOR_FRAME);
        g.fillGradient(left, top, left + WIDTH, top + HEIGHT, COLOR_BG_TOP, COLOR_BG_BOTTOM);
        corner(g, left, top);
        corner(g, left + WIDTH - 5, top);
        corner(g, left, top + HEIGHT - 5);
        corner(g, left + WIDTH - 5, top + HEIGHT - 5);
        // the list on the left
        g.fill(left + 4, top + 16, left + 4 + ROW_W + 2, top + HEIGHT - 6, 0x80000000);
        g.fill(left + 4 + ROW_W + 4, top + 8, left + 4 + ROW_W + 5, top + HEIGHT - 6, COLOR_FRAME_DARK);

        g.drawString(font, Component.translatable("gui.incursion.story_trade.list"), left + 8, top + 6, COLOR_TITLE, false);
        g.drawString(font, title, left + 112, top + 6, COLOR_TITLE, false);

        List<StoryOffer> offers = menu.offers();
        for (int i = 0; i < offers.size() && i < 7; i++) {
            StoryOffer offer = offers.get(i);
            int x = left + ROW_X;
            int y = top + ROW_Y + i * ROW_H;
            boolean over = mouseX >= x && mouseX < x + ROW_W && mouseY >= y && mouseY < y + ROW_H;
            if (i == menu.selectedIndex()) {
                g.fill(x, y, x + ROW_W, y + ROW_H, COLOR_FRAME);
                g.fill(x + 1, y + 1, x + ROW_W - 1, y + ROW_H - 1, 0xFF3A2050);
            } else if (over) {
                g.fill(x, y, x + ROW_W, y + ROW_H, 0x40FFFFFF);
            }
            g.renderItem(offer.result(), x + 2, y + 2);
            String name = font.plainSubstrByWidth(offer.result().getHoverName().getString(), ROW_W - 24);
            g.drawString(font, name, x + 21, y + 6, i == menu.selectedIndex() ? COLOR_TITLE : COLOR_TEXT, false);
        }

        // slot backgrounds; the item that belongs into an empty payment slot is shown dimmed in it
        for (Slot slot : menu.slots) {
            int x = left + slot.x;
            int y = top + slot.y;
            boolean result = slot.index == StoryTradeMenu.RESULT;
            if (result) {
                g.fill(x - 4, y - 4, x + 20, y + 20, COLOR_FRAME);
            }
            g.fill(x - 1, y - 1, x + 17, y + 17, COLOR_SLOT_EDGE);
            g.fill(x, y, x + 16, y + 16, COLOR_SLOT);
            if (slot.index < StoryTradeMenu.INPUTS && !slot.hasItem()) {
                ItemStack want = menu.wanted(slot.index);
                if (!want.isEmpty()) {
                    g.renderItem(want, x, y);
                    g.fill(x, y, x + 16, y + 16, 0xB00C0612); // veil: the item looks faint
                    if (want.getCount() > 1) {
                        String count = String.valueOf(want.getCount());
                        g.pose().pushPose();
                        g.pose().translate(0.0F, 0.0F, 200.0F);
                        g.drawString(font, count, x + 17 - font.width(count), y + 9, 0xFF8A7AA0, true);
                        g.pose().popPose();
                    }
                }
            }
        }
        g.blitSprite(ARROW, left + 214, top + 34, 10, 9);
        // a hint line and the inventory label
        List<FormattedCharSequence> hint = font.split(Component.translatable(
                menu.preview().isEmpty() ? "gui.incursion.story_trade.put" : "gui.incursion.story_trade.click")
                .withStyle(ChatFormatting.ITALIC), 160);
        int hy = top + 52;
        for (FormattedCharSequence line : hint) {
            g.drawString(font, line, left + 112, hy, COLOR_DIM, false);
            hy += 10;
        }
        g.drawString(font, Component.translatable("container.inventory"), left + 108, top + 74, COLOR_TITLE, false);
    }

    private static void corner(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 5, y + 5, COLOR_FRAME);
        g.fill(x + 1, y + 1, x + 4, y + 4, COLOR_FRAME_DARK);
    }
}
