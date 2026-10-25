package com.marleyboss.incursion.client;

import com.marleyboss.incursion.story.OrderPayload;
import com.marleyboss.incursion.story.StoryOffer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The mod's trading screen of a keeper villager. It takes the place of his vanilla trading screen (same size and place,
 * the dialog panel stays beside it), but in the mod's colours: dark violet with a gold frame. Left the commissions he
 * takes, right what one costs and what he makes; below the player's inventory (only shown). "Commission" sends the
 * order to the server: the costs are taken and the villager makes the item at his job site. "Back" (top right) switches
 * to his vanilla trading screen again.
 */
public class StoryTradeScreen extends Screen {
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
    private static final int COST_Y = 30;
    private static final int RESULT_X = 236;

    private final int villagerId;
    private final List<StoryOffer> offers;
    private int selected;
    private int left;
    private int top;
    private Button order;

    public StoryTradeScreen(int villagerId, List<StoryOffer> offers, int selected) {
        super(Component.translatable("gui.incursion.story_trade.title"));
        this.villagerId = villagerId;
        this.offers = List.copyOf(offers);
        this.selected = Mth.clamp(selected, 0, Math.max(0, offers.size() - 1));
    }

    public int villagerId() {
        return villagerId;
    }

    public int getGuiLeft() {
        return left;
    }

    public int getGuiTop() {
        return top;
    }

    @Override
    protected void init() {
        // centred like every container screen, so it is exactly where the vanilla trading screen was
        left = (width - WIDTH) / 2;
        top = (height - HEIGHT) / 2;
        order = addRenderableWidget(Button.builder(Component.translatable("gui.incursion.story_trade.order"), button -> {
                    StoryOffer offer = current();
                    if (offer != null && affordable(offer)) {
                        OrderPayload.send(villagerId, offer.key());
                        onClose();
                    }
                })
                .bounds(left + 112, top + 54, 150, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.incursion.story_trade.back"),
                        button -> VillagerDialog.back(villagerId))
                .bounds(left + WIDTH - 50, top + 3, 46, 13).build());
        updateOrder();
    }

    private StoryOffer current() {
        return offers.isEmpty() ? null : offers.get(selected);
    }

    private static int have(ItemStack cost) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? 0 : mc.player.getInventory().countItem(cost.getItem());
    }

    private static boolean affordable(StoryOffer offer) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getAbilities().instabuild) {
            return true;
        }
        for (ItemStack cost : offer.costs()) {
            if (have(cost) < cost.getCount()) {
                return false;
            }
        }
        return true;
    }

    private void updateOrder() {
        StoryOffer offer = current();
        if (order != null) {
            order.active = offer != null && affordable(offer);
        }
    }

    @Override
    public void tick() {
        super.tick();
        updateOrder(); // the inventory can change while the screen is open
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = 0; i < offers.size() && i < 7; i++) {
            int x = left + ROW_X;
            int y = top + ROW_Y + i * ROW_H;
            if (mouseX >= x && mouseX < x + ROW_W && mouseY >= y && mouseY < y + ROW_H) {
                selected = i;
                updateOrder();
                Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                        .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        long time = System.currentTimeMillis();
        // the frame breathes a little: it is no ordinary trade
        float pulse = 0.5F + 0.5F * Mth.sin(time / 600.0F);
        int glow = ((int) (0x30 + 0x50 * pulse) << 24) | 0xFFB040;
        g.fill(left - 3, top - 3, left + WIDTH + 3, top + HEIGHT + 3, glow);
        g.fill(left - 2, top - 2, left + WIDTH + 2, top + HEIGHT + 2, COLOR_FRAME_DARK);
        g.fill(left - 1, top - 1, left + WIDTH + 1, top + HEIGHT + 1, COLOR_FRAME);
        g.fillGradient(left, top, left + WIDTH, top + HEIGHT, COLOR_BG_TOP, COLOR_BG_BOTTOM);
        // gold corners
        corner(g, left, top);
        corner(g, left + WIDTH - 5, top);
        corner(g, left, top + HEIGHT - 5);
        corner(g, left + WIDTH - 5, top + HEIGHT - 5);
        // the list on the left
        g.fill(left + 4, top + 16, left + 4 + ROW_W + 2, top + HEIGHT - 6, 0x80000000);
        g.fill(left + 4 + ROW_W + 4, top + 8, left + 4 + ROW_W + 5, top + HEIGHT - 6, COLOR_FRAME_DARK);
    }

    private static void corner(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 5, y + 5, COLOR_FRAME);
        g.fill(x + 1, y + 1, x + 4, y + 4, COLOR_FRAME_DARK);
    }

    private static void slot(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, COLOR_SLOT_EDGE);
        g.fill(x, y, x + 16, y + 16, COLOR_SLOT);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick); // background, panel and the buttons
        ItemStack hovered = ItemStack.EMPTY;
        g.drawString(font, Component.translatable("gui.incursion.story_trade.list"), left + 8, top + 6, COLOR_TITLE, false);
        g.drawString(font, title, left + 112, top + 6, COLOR_TITLE, false);

        // the commissions
        for (int i = 0; i < offers.size() && i < 7; i++) {
            StoryOffer offer = offers.get(i);
            int x = left + ROW_X;
            int y = top + ROW_Y + i * ROW_H;
            boolean over = mouseX >= x && mouseX < x + ROW_W && mouseY >= y && mouseY < y + ROW_H;
            if (i == selected) {
                g.fill(x, y, x + ROW_W, y + ROW_H, COLOR_FRAME);
                g.fill(x + 1, y + 1, x + ROW_W - 1, y + ROW_H - 1, 0xFF3A2050);
            } else if (over) {
                g.fill(x, y, x + ROW_W, y + ROW_H, 0x40FFFFFF);
            }
            g.renderItem(offer.result(), x + 2, y + 2);
            String name = font.plainSubstrByWidth(offer.result().getHoverName().getString(), ROW_W - 24);
            g.drawString(font, name, x + 21, y + 6, i == selected ? COLOR_TITLE : COLOR_TEXT, false);
            if (over && mouseX < x + 20) {
                hovered = offer.result();
            }
        }

        StoryOffer offer = current();
        if (offer != null) {
            // costs → result (red underlay: not enough in the inventory)
            boolean creative = minecraft != null && minecraft.player != null && minecraft.player.getAbilities().instabuild;
            int x = left + 112;
            for (ItemStack cost : offer.costs()) {
                slot(g, x, top + COST_Y);
                if (!creative && have(cost) < cost.getCount()) {
                    g.fill(x, top + COST_Y, x + 16, top + COST_Y + 16, 0xA0B02020);
                }
                g.renderItem(cost, x, top + COST_Y);
                g.renderItemDecorations(font, cost, x, top + COST_Y);
                if (inside(mouseX, mouseY, x, top + COST_Y)) {
                    hovered = cost;
                }
                x += 24;
            }
            g.blitSprite(ARROW, left + RESULT_X - 22, top + COST_Y + 4, 10, 9);
            int rx = left + RESULT_X;
            g.fill(rx - 3, top + COST_Y - 3, rx + 19, top + COST_Y + 19, COLOR_FRAME);
            slot(g, rx, top + COST_Y);
            g.renderItem(offer.result(), rx, top + COST_Y);
            if (inside(mouseX, mouseY, rx, top + COST_Y)) {
                hovered = offer.result();
            }
            List<FormattedCharSequence> hint = font.split(Component.translatable("gui.incursion.story_trade.workshop")
                    .withStyle(ChatFormatting.ITALIC), 150);
            if (!hint.isEmpty()) {
                g.drawString(font, hint.get(0), left + 112, top + 19, COLOR_DIM, false);
            }
        }

        // the player's inventory (only shown: here nothing is laid in, the villager takes the costs himself)
        g.drawString(font, Component.translatable("container.inventory"), left + 108, top + 74, COLOR_TITLE, false);
        if (minecraft != null && minecraft.player != null) {
            Inventory inventory = minecraft.player.getInventory();
            for (int i = 0; i < 36; i++) {
                int col = i % 9;
                int row = i < 9 ? 3 : i / 9 - 1; // hotbar at the bottom
                int sx = left + 108 + col * 18;
                int sy = top + 84 + row * 18 + (i < 9 ? 4 : 0);
                slot(g, sx, sy);
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    g.renderItem(stack, sx, sy);
                    g.renderItemDecorations(font, stack, sx, sy);
                    if (inside(mouseX, mouseY, sx, sy)) {
                        hovered = stack;
                    }
                }
            }
        }
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mouseX, mouseY);
        }
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
    }
}
