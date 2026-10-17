package com.marleyboss.incursion.client;

import com.marleyboss.incursion.menu.CoreTableMenu;
import com.marleyboss.incursion.table.CoreTableLogic;
import com.marleyboss.incursion.table.CoreTableLogic.Entry;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** The Core Table GUI: item slot, list of the item's enchantments, cost and one action button. Drawn without textures. */
public class CoreTableScreen extends AbstractContainerScreen<CoreTableMenu> {
    private static final int COLOR_PANEL = 0xF0181020;
    private static final int COLOR_BORDER = 0xFF7A3FB0;
    private static final int COLOR_SLOT = 0xFF0B0712;
    private static final int COLOR_LIST = 0xFF241830;
    private static final int COLOR_ROW_SELECTED = 0xFF5A2E88;
    private static final int COLOR_ROW_HOVER = 0xFF3A2352;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_MUTED = 0xFF9A90A8;
    private static final int COLOR_TITLE = 0xFFFFC040;
    private static final int COLOR_SHARD = 0xFF7FFFD4;
    private static final int COLOR_BAD = 0xFFFF6060;
    private static final int COLOR_EXTRA = 0xFFE0A0FF;

    private static final int LIST_X = 8;
    private static final int LIST_Y = 62;
    private static final int LIST_WIDTH = 160;
    private static final int ROW_HEIGHT = 12;
    private static final int ROWS = 4;

    private Button actionButton;
    private int scroll;

    public CoreTableScreen(CoreTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 244;
    }

    @Override
    protected void init() {
        super.init();
        actionButton = addRenderableWidget(Button.builder(Component.empty(), button -> onAction())
                .bounds(leftPos + 8, topPos + 136, 160, 20)
                .build());
        updateButton();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateButton();
    }

    private void onAction() {
        int state = menu.get(CoreTableMenu.DATA_SEL_STATE);
        if (state == CoreTableLogic.STATE_LOCKED) {
            click(CoreTableMenu.BUTTON_UNLOCK);
        } else if (state == CoreTableLogic.STATE_READY) {
            click(CoreTableMenu.BUTTON_RAISE);
        }
    }

    private void click(int id) {
        if (minecraft == null || minecraft.gameMode == null) {
            return;
        }
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private void updateButton() {
        if (actionButton == null) {
            return;
        }
        int state = menu.get(CoreTableMenu.DATA_SEL_STATE);
        boolean creative = menu.get(CoreTableMenu.DATA_CREATIVE) != 0;
        int shardCost = menu.get(CoreTableMenu.DATA_SEL_SHARD_COST);
        int xpCost = menu.get(CoreTableMenu.DATA_SEL_XP_COST);
        boolean enoughShards = creative || menu.get(CoreTableMenu.DATA_SHARDS) >= shardCost;
        boolean enoughXp = creative || menu.get(CoreTableMenu.DATA_PLAYER_XP) >= xpCost;
        actionButton.setTooltip(null);
        switch (state) {
            case CoreTableLogic.STATE_LOCKED -> {
                actionButton.setMessage(creative
                        ? Component.translatable("gui.incursion.table.button.unlock_free")
                        : Component.translatable("gui.incursion.table.button.unlock", shardCost));
                actionButton.active = enoughShards;
                actionButton.setTooltip(Tooltip.create(Component.translatable("gui.incursion.table.unlock_tooltip")));
            }
            case CoreTableLogic.STATE_READY -> {
                String next = CoreTableLogic.roman(menu.get(CoreTableMenu.DATA_SEL_LEVEL) + 1);
                actionButton.setMessage(creative
                        ? Component.translatable("gui.incursion.table.button.raise_free", next)
                        : Component.translatable("gui.incursion.table.button.raise", next, shardCost, xpCost));
                actionButton.active = enoughShards && enoughXp;
            }
            case CoreTableLogic.STATE_AT_CAP -> {
                actionButton.setMessage(Component.translatable("gui.incursion.table.button.cap"));
                actionButton.active = false;
                actionButton.setTooltip(Tooltip.create(Component.translatable("gui.incursion.table.cap_tooltip")));
            }
            case CoreTableLogic.STATE_BELOW_MAX -> {
                actionButton.setMessage(Component.translatable("gui.incursion.table.button.below"));
                actionButton.active = false;
                actionButton.setTooltip(Tooltip.create(Component.translatable("gui.incursion.table.below_tooltip",
                        CoreTableLogic.roman(menu.get(CoreTableMenu.DATA_SEL_VANILLA_MAX)))));
            }
            case CoreTableLogic.STATE_NO_CORE -> {
                actionButton.setMessage(Component.translatable("gui.incursion.table.button.no_core"));
                actionButton.active = false;
            }
            default -> {
                actionButton.setMessage(Component.translatable("gui.incursion.table.button.select"));
                actionButton.active = false;
            }
        }
    }

    private int maxScroll(int size) {
        return Math.max(0, size - ROWS);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isOverList(mouseX, mouseY)) {
            int max = maxScroll(menu.candidates().size());
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isOverList(double mouseX, double mouseY) {
        return mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_X + LIST_WIDTH
                && mouseY >= topPos + LIST_Y && mouseY < topPos + LIST_Y + ROWS * ROW_HEIGHT;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverList(mouseX, mouseY)) {
            int index = scroll + (int) ((mouseY - topPos - LIST_Y) / ROW_HEIGHT);
            if (index >= 0 && index < menu.candidates().size()) {
                click(index);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, COLOR_BORDER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, COLOR_PANEL);
        graphics.fill(x + 6, y + 16, x + imageWidth - 6, y + 17, COLOR_BORDER);
        for (Slot slot : menu.slots) {
            graphics.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, COLOR_BORDER);
            graphics.fill(x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16, COLOR_SLOT);
        }

        graphics.drawString(font, title, x + (imageWidth - font.width(title)) / 2, y + 5, COLOR_TITLE, true);

        boolean hasCore = menu.get(CoreTableMenu.DATA_HAS_CORE) != 0;
        if (hasCore) {
            graphics.drawString(font, Component.translatable("gui.incursion.table.core",
                    menu.get(CoreTableMenu.DATA_CORE_LEVEL)), x + 8, y + 21, COLOR_TEXT, false);
            graphics.drawString(font, Component.translatable("gui.incursion.table.shards",
                    menu.get(CoreTableMenu.DATA_SHARDS)), x + 100, y + 21, COLOR_SHARD, false);
            graphics.drawString(font, Component.translatable("gui.incursion.table.extra",
                    menu.get(CoreTableMenu.DATA_EXTRA)), x + 8, y + 32, COLOR_EXTRA, false);
        } else {
            graphics.drawString(font, Component.translatable("gui.incursion.table.no_core"), x + 8, y + 21, COLOR_BAD, false);
        }

        List<Entry> list = menu.candidates();
        boolean slotEmpty = menu.getSlot(0).getItem().isEmpty();
        if (slotEmpty) {
            graphics.drawString(font, Component.translatable("gui.incursion.table.hint_empty"),
                    x + CoreTableMenu.ITEM_SLOT_X + 24, y + CoreTableMenu.ITEM_SLOT_Y + 4, COLOR_MUTED, false);
        } else if (list.isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.incursion.table.hint_none"),
                    x + CoreTableMenu.ITEM_SLOT_X + 24, y + CoreTableMenu.ITEM_SLOT_Y + 4, COLOR_MUTED, false);
        }

        // The enchantment list.
        graphics.fill(x + LIST_X, y + LIST_Y, x + LIST_X + LIST_WIDTH, y + LIST_Y + ROWS * ROW_HEIGHT, COLOR_LIST);
        scroll = Math.max(0, Math.min(scroll, maxScroll(list.size())));
        int selected = menu.get(CoreTableMenu.DATA_SELECTED) - 1;
        for (int row = 0; row < ROWS; row++) {
            int index = scroll + row;
            if (index >= list.size()) {
                break;
            }
            Entry entry = list.get(index);
            int rowY = y + LIST_Y + row * ROW_HEIGHT;
            boolean hover = mouseX >= x + LIST_X && mouseX < x + LIST_X + LIST_WIDTH && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (index == selected) {
                graphics.fill(x + LIST_X, rowY, x + LIST_X + LIST_WIDTH, rowY + ROW_HEIGHT, COLOR_ROW_SELECTED);
            } else if (hover) {
                graphics.fill(x + LIST_X, rowY, x + LIST_X + LIST_WIDTH, rowY + ROW_HEIGHT, COLOR_ROW_HOVER);
            }
            Component text = Component.empty().append(entry.holder().value().description())
                    .append(" " + CoreTableLogic.roman(entry.level()));
            int color = entry.level() > entry.vanillaMax() ? COLOR_EXTRA
                    : entry.level() >= entry.vanillaMax() ? COLOR_TEXT : COLOR_MUTED;
            graphics.drawString(font, text, x + LIST_X + 3, rowY + 2, color, false);
        }
        if (list.size() > ROWS) {
            int barHeight = Math.max(6, ROWS * ROW_HEIGHT * ROWS / list.size());
            int barY = y + LIST_Y + (ROWS * ROW_HEIGHT - barHeight) * scroll / maxScroll(list.size());
            graphics.fill(x + LIST_X + LIST_WIDTH - 3, barY, x + LIST_X + LIST_WIDTH - 1, barY + barHeight, COLOR_BORDER);
        }

        // Details of the selected enchantment.
        if (selected >= 0 && selected < list.size()) {
            int state = menu.get(CoreTableMenu.DATA_SEL_STATE);
            int cap = menu.get(CoreTableMenu.DATA_SEL_CAP);
            if (state != CoreTableLogic.STATE_NO_CORE) {
                graphics.drawString(font, Component.translatable("gui.incursion.table.detail",
                        CoreTableLogic.roman(menu.get(CoreTableMenu.DATA_SEL_LEVEL)), CoreTableLogic.roman(cap),
                        CoreTableLogic.roman(menu.get(CoreTableMenu.DATA_SEL_VANILLA_MAX))), x + 8, y + 114, COLOR_TEXT, false);
            }
            graphics.drawString(font, Component.translatable("gui.incursion.table.xp_owned",
                    menu.get(CoreTableMenu.DATA_PLAYER_XP)), x + 8, y + 125, COLOR_MUTED, false);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Everything is drawn in renderBg.
    }
}
