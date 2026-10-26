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
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The window of an order. On top the villager (framed picture, name, what he says); below, in the middle of the
 * window, the thing the window is about: while he is still working a progress bar, and once he is done the finished
 * item, big, floating on a glowing pedestal with its name below. A click on the pedestal takes it.
 */
public class StoryPickupScreen extends AbstractContainerScreen<StoryPickupMenu> {
    private static final int W = 216;
    private static final int H = 190;
    private static final int COLOR_FRAME = 0xFFC9A040;
    private static final int COLOR_FRAME_DARK = 0xFF6A4A18;
    private static final int COLOR_BG_TOP = 0xF62A1638;
    private static final int COLOR_BG_BOTTOM = 0xF6140A1E;
    private static final int COLOR_SLOT = 0xFF0C0612;
    private static final int COLOR_EDGE = 0xFF4A2A66;
    private static final int COLOR_NAME = 0xFFFFC040;
    private static final int COLOR_TEXT = 0xFFE8E0F0;
    private static final int COLOR_DIM = 0xFF9A90A8;
    private static final int TEXT_X = 72;
    private static final int TEXT_W = W - TEXT_X - 10;
    /** The pedestal (56 x 56) in the middle of the lower half. */
    private static final int HERO_Y = 104;
    private static final int HERO_SIZE = 56;

    public StoryPickupScreen(StoryPickupMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = W;
        imageHeight = H;
        titleLabelY = 10000;
        inventoryLabelY = 10000;
    }

    private boolean overHero(double mouseX, double mouseY) {
        int x = leftPos + W / 2 - HERO_SIZE / 2;
        int y = topPos + HERO_Y;
        return mouseX >= x && mouseX < x + HERO_SIZE && mouseY >= y && mouseY < y + HERO_SIZE;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && menu.isDone() && overHero(mouseX, mouseY) && minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
            slotClicked(menu.slots.get(0), 0, 0, ClickType.PICKUP);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        ItemStack stack = menu.slots.get(0).getItem();
        if (menu.isDone() && !stack.isEmpty() && overHero(mouseX, mouseY)) {
            g.renderTooltip(font, stack, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int left = leftPos;
        int top = topPos;
        long now = System.currentTimeMillis();
        float pulse = 0.5F + 0.5F * Mth.sin(now / 600.0F);
        g.fill(left - 3, top - 3, left + W + 3, top + H + 3, ((int) (0x30 + 0x50 * pulse) << 24) | 0xFFB040);
        g.fill(left - 2, top - 2, left + W + 2, top + H + 2, COLOR_FRAME_DARK);
        g.fill(left - 1, top - 1, left + W + 1, top + H + 1, COLOR_FRAME);
        g.fillGradient(left, top, left + W, top + H, COLOR_BG_TOP, COLOR_BG_BOTTOM);

        // ---- top: the villager and what he says
        g.fill(left + 7, top + 7, left + 65, top + 93, COLOR_EDGE);
        g.fillGradient(left + 8, top + 8, left + 64, top + 92, 0xFF1A0E26, 0xFF0C0612);
        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.level != null ? mc.level.getEntity(menu.villagerId()) : null;
        Component name = entity != null ? entity.getDisplayName() : Component.empty();
        if (entity instanceof LivingEntity living) {
            Component customName = living.getCustomName();
            boolean shown = living.isCustomNameVisible();
            living.setCustomName(null);
            living.setCustomNameVisible(false);
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, left + 8, top + 8, left + 64, top + 92, 36,
                        0.0625F, mouseX, mouseY, living);
            } finally {
                living.setCustomName(customName);
                living.setCustomNameVisible(shown);
            }
        }
        int y = top + 10;
        for (FormattedCharSequence line : font.split(name.copy().withStyle(ChatFormatting.BOLD), TEXT_W)) {
            g.drawString(font, line, left + TEXT_X, y, COLOR_NAME, false);
            y += 10;
        }
        g.fill(left + TEXT_X, y + 1, left + W - 10, y + 2, COLOR_FRAME_DARK);
        y += 6;
        boolean done = menu.isDone();
        ItemStack stack = menu.slots.get(0).getItem();
        Component say;
        if (done) {
            Component item = stack.isEmpty() ? Component.empty()
                    : stack.getCount() > 1 ? Component.literal(stack.getCount() + "× ").append(stack.getHoverName())
                    : stack.getHoverName();
            say = Component.translatable(menu.readyKey(), item);
        } else {
            say = Component.translatable(menu.workingKey());
        }
        List<FormattedCharSequence> lines = font.split(say, TEXT_W);
        for (int i = 0; i < lines.size() && i < 7; i++) {
            g.drawString(font, lines.get(i), left + TEXT_X, y, COLOR_TEXT, false);
            y += 10;
        }
        g.fill(left + 8, top + 98, left + W - 8, top + 99, COLOR_FRAME_DARK);

        // ---- below: the thing this window is about
        int cx = left + W / 2;
        if (done && !stack.isEmpty()) {
            boolean hover = overHero(mouseX, mouseY);
            int bx = cx - HERO_SIZE / 2;
            int by = top + HERO_Y;
            // glow, pedestal
            g.fill(bx - 4, by - 4, bx + HERO_SIZE + 4, by + HERO_SIZE + 4, ((int) (0x20 + 0x40 * pulse) << 24) | 0xFFC040);
            g.fill(bx - 1, by - 1, bx + HERO_SIZE + 1, by + HERO_SIZE + 1, hover ? 0xFFFFE08A : COLOR_FRAME);
            g.fillGradient(bx, by, bx + HERO_SIZE, by + HERO_SIZE, hover ? 0xFF3A2050 : 0xFF2A1638, 0xFF0C0612);
            // twinkling sparks around the item
            for (int i = 0; i < 6; i++) {
                double ang = now / 1400.0 * (1.0 + i * 0.15) + i * 1.05;
                int sx = (int) (cx + Math.cos(ang) * 22);
                int sy = (int) (by + HERO_SIZE / 2 + Math.sin(ang * 1.3) * 20);
                int alpha = (int) (0x50 + 0xA0 * (0.5 + 0.5 * Math.sin(now / 250.0 + i * 2.0)));
                g.fill(sx, sy, sx + 2, sy + 2, (alpha << 24) | 0xFFE080);
            }
            // the item itself, big, floating a little
            float bob = Mth.sin(now / 500.0F) * 1.5F;
            g.pose().pushPose();
            g.pose().translate(cx, by + HERO_SIZE / 2.0F + bob, 0.0F);
            g.pose().scale(2.75F, 2.75F, 1.0F);
            g.renderItem(stack, -8, -8);
            g.pose().popPose();
            // its name (and how many) and the hint
            Component label = stack.getCount() > 1
                    ? Component.literal(stack.getCount() + "× ").append(stack.getHoverName()) : stack.getHoverName();
            Component bold = label.copy().withStyle(ChatFormatting.BOLD);
            g.drawString(font, bold, cx - font.width(bold) / 2, top + HERO_Y + HERO_SIZE + 8, COLOR_NAME, false);
            Component hint = Component.translatable("gui.incursion.story_pickup.take");
            int hintAlpha = (int) (0x70 + 0x8F * pulse);
            g.drawString(font, hint, cx - font.width(hint) / 2, top + HERO_Y + HERO_SIZE + 20,
                    (hintAlpha << 24) | 0xE8E0F0, false);
        } else {
            // still working: the progress bar takes the place of the item
            int x1 = left + 20;
            int x2 = left + W - 20;
            int y1 = top + HERO_Y + HERO_SIZE / 2 - 8;
            int y2 = y1 + 16;
            g.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, COLOR_EDGE);
            g.fill(x1, y1, x2, y2, COLOR_SLOT);
            int filled = (x2 - x1) * menu.permille() / 1000;
            if (filled > 0) {
                g.fillGradient(x1, y1, x1 + filled, y2, 0xFFE0B050, 0xFF9A6A20);
                g.fill(x1, y1, x1 + filled, y1 + 1, 0x66FFFFFF);
            }
            int seconds = menu.secondsLeft();
            String label = menu.permille() / 10 + " %   ·   " + (seconds / 60) + ":" + String.format("%02d", seconds % 60);
            g.drawString(font, label, cx - font.width(label) / 2, y1 + 4, COLOR_TEXT, true);
            Component sub = Component.translatable("gui.incursion.story_pickup.working");
            g.drawString(font, sub, cx - font.width(sub) / 2, y2 + 8, COLOR_DIM, false);
        }
    }
}
