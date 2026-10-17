package com.marleyboss.incursion.client;

import com.marleyboss.incursion.menu.CoreTableMenu;
import com.marleyboss.incursion.table.CoreTableLogic;
import com.marleyboss.incursion.table.CoreTableLogic.Entry;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

/**
 * The Core Table GUI: item slot, every enchantment of the item as a button (only those that can be raised are
 * selectable), a green check to confirm and a red cross to reset the selection. Drawn without textures.
 */
public class CoreTableScreen extends AbstractContainerScreen<CoreTableMenu> {
    private static final int COLOR_PANEL = 0xF0181020;
    private static final int COLOR_BORDER = 0xFF7A3FB0;
    private static final int COLOR_SLOT = 0xFF0B0712;
    private static final int COLOR_LIST = 0xFF140D1C;
    private static final int COLOR_BUTTON = 0xFF3A2352;
    private static final int COLOR_BUTTON_HOVER = 0xFF5A3A80;
    private static final int COLOR_BUTTON_OFF = 0xFF1E1628;
    private static final int COLOR_SELECTED = 0xFF55FF77;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_MUTED = 0xFF7A708A;
    private static final int COLOR_INFO = 0xFFB8A8D0;
    private static final int COLOR_TITLE = 0xFFFFC040;
    private static final int COLOR_SHARD = 0xFF7FFFD4;
    private static final int COLOR_BAD = 0xFFFF7070;
    private static final int COLOR_EXTRA = 0xFFE0A0FF;
    private static final int COLOR_GOOD = 0xFF70E080;
    private static final int COLOR_GREEN = 0xFF2E8B3E;
    private static final int COLOR_GREEN_OFF = 0xFF24302A;
    private static final int COLOR_RED = 0xFFA83232;
    private static final int COLOR_RED_OFF = 0xFF332424;

    private static final int LIST_X = 8;
    private static final int LIST_Y = 68;
    private static final int LIST_WIDTH = 160;
    private static final int ROW_HEIGHT = 12;
    private static final int ROWS = 5;
    private static final int BUTTON_Y = 156;
    private static final int BUTTON_WIDTH = 78;
    private static final int BUTTON_HEIGHT = 20;
    private static final int CONFIRM_X = 8;
    private static final int RESET_X = 90;

    private int scroll;

    public CoreTableScreen(CoreTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 266;
    }

    private void click(int id) {
        if (minecraft == null || minecraft.gameMode == null) {
            return;
        }
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private boolean readyBit(int index) {
        if (index < 0 || index >= 30) {
            return false;
        }
        int bits = menu.get(index < 15 ? CoreTableMenu.DATA_READY_LO : CoreTableMenu.DATA_READY_HI);
        return (bits & (1 << (index % 15))) != 0;
    }

    /** Why an entry can or cannot be raised (the same order as the server uses). */
    private int stateOf(Entry entry, @Nullable Entry over, int index) {
        int itemState = CoreTableLogic.itemState(entry, over);
        if (itemState != CoreTableLogic.OK) {
            return itemState;
        }
        if (menu.get(CoreTableMenu.DATA_JOB_STATE) != CoreTableLogic.JOB_IDLE) {
            return CoreTableLogic.BUSY;
        }
        if (menu.get(CoreTableMenu.DATA_HAS_CORE) == 0) {
            return CoreTableLogic.NO_CORE;
        }
        if (menu.get(CoreTableMenu.DATA_TIME_OK) == 0) {
            return CoreTableLogic.NOT_EVENING;
        }
        return readyBit(index) ? CoreTableLogic.OK : CoreTableLogic.AT_CAP;
    }

    private Component reason(Entry entry, @Nullable Entry over, int state) {
        return switch (state) {
            case CoreTableLogic.NOT_ON_ITEM -> Component.translatable("gui.incursion.table.reason.not_on_item");
            case CoreTableLogic.BELOW_MAX -> Component.translatable("gui.incursion.table.reason.below_max",
                    CoreTableLogic.roman(entry.vanillaMax()), CoreTableLogic.roman(entry.level()));
            case CoreTableLogic.OTHER_OVER -> Component.translatable("gui.incursion.table.reason.other_over",
                    over == null ? "?" : over.holder().value().description());
            case CoreTableLogic.BUSY -> Component.translatable("gui.incursion.table.reason.busy");
            case CoreTableLogic.NO_CORE -> Component.translatable("gui.incursion.table.reason.no_core");
            case CoreTableLogic.NOT_EVENING -> Component.translatable("gui.incursion.table.reason.not_evening");
            case CoreTableLogic.AT_CAP -> Component.translatable("gui.incursion.table.reason.at_cap",
                    CoreTableLogic.roman(entry.level()));
            default -> Component.translatable("gui.incursion.table.reason.ready");
        };
    }

    private boolean isOver(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= leftPos + x && mouseX < leftPos + x + width && mouseY >= topPos + y && mouseY < topPos + y + height;
    }

    private int rowAt(double mouseX, double mouseY) {
        if (!isOver(mouseX, mouseY, LIST_X, LIST_Y, LIST_WIDTH, ROWS * ROW_HEIGHT)) {
            return -1;
        }
        return scroll + (int) ((mouseY - topPos - LIST_Y) / ROW_HEIGHT);
    }

    private boolean canConfirm() {
        int selected = menu.get(CoreTableMenu.DATA_SELECTED);
        if (selected <= 0 || menu.get(CoreTableMenu.DATA_JOB_STATE) != CoreTableLogic.JOB_IDLE) {
            return false;
        }
        if (menu.get(CoreTableMenu.DATA_CREATIVE) != 0) {
            return true;
        }
        return menu.get(CoreTableMenu.DATA_SHARDS) >= menu.get(CoreTableMenu.DATA_SEL_SHARD_COST)
                && menu.get(CoreTableMenu.DATA_PLAYER_XP) >= menu.get(CoreTableMenu.DATA_SEL_XP_COST);
    }

    private boolean canReset() {
        return menu.get(CoreTableMenu.DATA_SELECTED) > 0;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isOver(mouseX, mouseY, LIST_X, LIST_Y, LIST_WIDTH, ROWS * ROW_HEIGHT)) {
            int max = Math.max(0, menu.entries().size() - ROWS);
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int row = rowAt(mouseX, mouseY);
            if (row >= 0) {
                List<Entry> list = menu.entries();
                if (row < list.size() && stateOf(list.get(row), CoreTableLogic.overEntry(list), row) == CoreTableLogic.OK) {
                    click(row);
                }
                return true;
            }
            if (canConfirm() && isOver(mouseX, mouseY, CONFIRM_X, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                click(CoreTableMenu.BUTTON_CONFIRM);
                return true;
            }
            if (canReset() && isOver(mouseX, mouseY, RESET_X, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                click(CoreTableMenu.BUTTON_RESET);
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
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Everything is drawn in renderBg.
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

        drawHeader(graphics, x, y);
        List<Entry> list = menu.entries();
        Entry over = CoreTableLogic.overEntry(list);
        int jobState = menu.get(CoreTableMenu.DATA_JOB_STATE);
        drawItemStatus(graphics, x, y, list, jobState);
        Entry hovered = drawList(graphics, x, y, list, over, mouseX, mouseY);
        drawInfo(graphics, x, y, list, over, hovered, mouseX, mouseY);
        drawButtons(graphics, x, y, mouseX, mouseY);
    }

    private void drawHeader(GuiGraphics graphics, int x, int y) {
        if (menu.get(CoreTableMenu.DATA_HAS_CORE) != 0) {
            graphics.drawString(font, Component.translatable("gui.incursion.table.core",
                    menu.get(CoreTableMenu.DATA_CORE_LEVEL)), x + 8, y + 21, COLOR_TEXT, false);
            graphics.drawString(font, Component.translatable("gui.incursion.table.shards",
                    menu.get(CoreTableMenu.DATA_SHARDS)), x + 96, y + 21, COLOR_SHARD, false);
            graphics.drawString(font, Component.translatable("gui.incursion.table.extra",
                    menu.get(CoreTableMenu.DATA_EXTRA)), x + 8, y + 32, COLOR_EXTRA, false);
        } else {
            graphics.drawString(font, Component.translatable("gui.incursion.table.no_core"), x + 8, y + 21, COLOR_BAD, false);
        }
        boolean timeOk = menu.get(CoreTableMenu.DATA_TIME_OK) != 0;
        graphics.drawString(font, Component.translatable(timeOk ? "gui.incursion.table.evening_ok" : "gui.incursion.table.evening_no"),
                x + (menu.get(CoreTableMenu.DATA_HAS_CORE) != 0 ? 96 : 8), y + (menu.get(CoreTableMenu.DATA_HAS_CORE) != 0 ? 32 : 32),
                timeOk ? COLOR_GOOD : COLOR_BAD, false);
    }

    private void drawItemStatus(GuiGraphics graphics, int x, int y, List<Entry> list, int jobState) {
        int textX = x + CoreTableMenu.ITEM_SLOT_X + 24;
        int textY = y + CoreTableMenu.ITEM_SLOT_Y - 2;
        boolean slotEmpty = menu.getSlot(0).getItem().isEmpty();
        if (jobState == CoreTableLogic.JOB_WORKING) {
            int index = menu.get(CoreTableMenu.DATA_JOB_INDEX) - 1;
            Component name = index >= 0 && index < list.size() ? list.get(index).holder().value().description() : Component.empty();
            graphics.drawString(font, Component.translatable("gui.incursion.table.working", name,
                    CoreTableLogic.roman(menu.get(CoreTableMenu.DATA_JOB_LEVEL))), textX, textY, COLOR_EXTRA, false);
            int left = menu.get(CoreTableMenu.DATA_JOB_SECONDS_LEFT);
            int total = Math.max(1, menu.get(CoreTableMenu.DATA_JOB_SECONDS_TOTAL));
            graphics.drawString(font, Component.translatable("gui.incursion.table.time_left",
                    String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)), textX, textY + 11, COLOR_TEXT, false);
            graphics.fill(textX, textY + 22, textX + 138, textY + 26, COLOR_SLOT);
            graphics.fill(textX, textY + 22, textX + Math.round(138 * (1.0F - left / (float) total)), textY + 26, COLOR_EXTRA);
        } else if (jobState == CoreTableLogic.JOB_DONE) {
            graphics.drawString(font, Component.translatable("gui.incursion.table.done"), textX, textY, COLOR_GOOD, false);
            graphics.drawString(font, Component.translatable("gui.incursion.table.done_hint"), textX, textY + 11, COLOR_INFO, false);
        } else if (slotEmpty) {
            drawWrapped(graphics, Component.translatable("gui.incursion.table.hint_empty"), textX, textY, 138, COLOR_INFO);
        } else if (list.isEmpty()) {
            drawWrapped(graphics, Component.translatable("gui.incursion.table.hint_none"), textX, textY, 138, COLOR_BAD);
        } else {
            drawWrapped(graphics, Component.translatable("gui.incursion.table.hint_pick"), textX, textY, 138, COLOR_INFO);
        }
    }

    private void drawWrapped(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        int line = 0;
        for (FormattedCharSequence sequence : font.split(text, width)) {
            graphics.drawString(font, sequence, x, y + line * 10, color, false);
            line++;
            if (line >= 3) {
                break;
            }
        }
    }

    /** Draws the enchantment buttons; returns the entry under the mouse (or null). */
    @Nullable
    private Entry drawList(GuiGraphics graphics, int x, int y, List<Entry> list, @Nullable Entry over, int mouseX, int mouseY) {
        graphics.fill(x + LIST_X - 1, y + LIST_Y - 1, x + LIST_X + LIST_WIDTH + 1, y + LIST_Y + ROWS * ROW_HEIGHT + 1, COLOR_BORDER);
        graphics.fill(x + LIST_X, y + LIST_Y, x + LIST_X + LIST_WIDTH, y + LIST_Y + ROWS * ROW_HEIGHT, COLOR_LIST);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - ROWS)));
        int selected = menu.get(CoreTableMenu.DATA_SELECTED) - 1;
        int hoveredRow = rowAt(mouseX, mouseY);
        Entry hovered = null;
        for (int row = 0; row < ROWS; row++) {
            int index = scroll + row;
            if (index >= list.size()) {
                break;
            }
            Entry entry = list.get(index);
            int state = stateOf(entry, over, index);
            boolean ready = state == CoreTableLogic.OK;
            int rowY = y + LIST_Y + row * ROW_HEIGHT;
            int left = x + LIST_X + 1;
            int right = x + LIST_X + LIST_WIDTH - 1;
            int fill = !ready ? COLOR_BUTTON_OFF : index == hoveredRow ? COLOR_BUTTON_HOVER : COLOR_BUTTON;
            graphics.fill(left, rowY + 1, right, rowY + ROW_HEIGHT - 1, fill);
            if (index == selected) {
                graphics.fill(left, rowY + 1, right, rowY + 2, COLOR_SELECTED);
                graphics.fill(left, rowY + ROW_HEIGHT - 2, right, rowY + ROW_HEIGHT - 1, COLOR_SELECTED);
                graphics.fill(left, rowY + 1, left + 1, rowY + ROW_HEIGHT - 1, COLOR_SELECTED);
                graphics.fill(right - 1, rowY + 1, right, rowY + ROW_HEIGHT - 1, COLOR_SELECTED);
            }
            Component name = entry.holder().value().description();
            int nameColor = ready ? COLOR_TEXT : COLOR_MUTED;
            graphics.drawString(font, name, left + 3, rowY + 2, nameColor, false);
            String level = entry.level() <= 0 ? "-"
                    : ready ? CoreTableLogic.roman(entry.level()) + " > " + CoreTableLogic.roman(entry.level() + 1)
                    : CoreTableLogic.roman(entry.level());
            int levelColor = entry.level() > entry.vanillaMax() ? COLOR_EXTRA : ready ? COLOR_GOOD : COLOR_MUTED;
            graphics.drawString(font, level, right - 3 - font.width(level), rowY + 2, levelColor, false);
            if (index == hoveredRow) {
                hovered = entry;
            }
        }
        if (list.size() > ROWS) {
            int barHeight = Math.max(6, ROWS * ROW_HEIGHT * ROWS / list.size());
            int barY = y + LIST_Y + (ROWS * ROW_HEIGHT - barHeight) * scroll / (list.size() - ROWS);
            graphics.fill(x + LIST_X + LIST_WIDTH - 2, barY, x + LIST_X + LIST_WIDTH, barY + barHeight, COLOR_BORDER);
        }
        return hovered;
    }

    private void drawInfo(GuiGraphics graphics, int x, int y, List<Entry> list, @Nullable Entry over,
                          @Nullable Entry hovered, int mouseX, int mouseY) {
        int infoX = x + 8;
        int infoY = y + 132;
        int selected = menu.get(CoreTableMenu.DATA_SELECTED) - 1;
        if (hovered != null) {
            int state = stateOf(hovered, over, list.indexOf(hovered));
            int color = state == CoreTableLogic.OK ? COLOR_GOOD : COLOR_BAD;
            drawWrapped(graphics, reason(hovered, over, state), infoX, infoY, 160, color);
            return;
        }
        if (selected >= 0 && selected < list.size()) {
            Entry entry = list.get(selected);
            graphics.drawString(font, Component.translatable("gui.incursion.table.selected",
                    entry.holder().value().description(), CoreTableLogic.roman(menu.get(CoreTableMenu.DATA_SEL_TARGET))),
                    infoX, infoY, COLOR_TEXT, false);
            int seconds = menu.get(CoreTableMenu.DATA_SEL_SECONDS);
            Component cost = menu.get(CoreTableMenu.DATA_CREATIVE) != 0
                    ? Component.translatable("gui.incursion.table.cost_free")
                    : Component.translatable("gui.incursion.table.cost", menu.get(CoreTableMenu.DATA_SEL_SHARD_COST),
                    menu.get(CoreTableMenu.DATA_SEL_XP_COST), String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
            graphics.drawString(font, cost, infoX, infoY + 11, COLOR_SHARD, false);
            return;
        }
        if (!list.isEmpty() && menu.get(CoreTableMenu.DATA_JOB_STATE) == CoreTableLogic.JOB_IDLE) {
            drawWrapped(graphics, Component.translatable("gui.incursion.table.rules"), infoX, infoY, 160, COLOR_INFO);
        }
    }

    private void drawButtons(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        boolean confirm = canConfirm();
        boolean reset = canReset();
        int confirmX = x + CONFIRM_X;
        int resetX = x + RESET_X;
        int top = y + BUTTON_Y;
        boolean confirmHover = confirm && isOver(mouseX, mouseY, CONFIRM_X, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT);
        boolean resetHover = reset && isOver(mouseX, mouseY, RESET_X, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT);
        graphics.fill(confirmX - 1, top - 1, confirmX + BUTTON_WIDTH + 1, top + BUTTON_HEIGHT + 1, confirm ? COLOR_SELECTED : COLOR_BORDER);
        graphics.fill(confirmX, top, confirmX + BUTTON_WIDTH, top + BUTTON_HEIGHT, confirm ? (confirmHover ? 0xFF3FB556 : COLOR_GREEN) : COLOR_GREEN_OFF);
        graphics.fill(resetX - 1, top - 1, resetX + BUTTON_WIDTH + 1, top + BUTTON_HEIGHT + 1, reset ? 0xFFFF6060 : COLOR_BORDER);
        graphics.fill(resetX, top, resetX + BUTTON_WIDTH, top + BUTTON_HEIGHT, reset ? (resetHover ? 0xFFC84444 : COLOR_RED) : COLOR_RED_OFF);

        int iconColor = confirm ? 0xFFFFFFFF : 0xFF5A6A5E;
        int checkX = confirmX + 6;
        int checkY = top + 5;
        int[][] check = {{0, 4}, {1, 5}, {2, 6}, {3, 7}, {4, 6}, {5, 5}, {6, 4}, {7, 3}, {8, 2}, {9, 1}};
        for (int[] p : check) {
            graphics.fill(checkX + p[0], checkY + p[1], checkX + p[0] + 2, checkY + p[1] + 2, iconColor);
        }
        int crossColor = reset ? 0xFFFFFFFF : 0xFF6A5A5A;
        int crossX = resetX + 6;
        int crossY = top + 6;
        for (int i = 0; i <= 8; i++) {
            graphics.fill(crossX + i, crossY + i, crossX + i + 2, crossY + i + 2, crossColor);
            graphics.fill(crossX + 8 - i, crossY + i, crossX + 10 - i, crossY + i + 2, crossColor);
        }
        Component confirmText = Component.translatable("gui.incursion.table.button.confirm");
        Component resetText = Component.translatable("gui.incursion.table.button.reset");
        graphics.drawString(font, confirmText, confirmX + 22, top + 6, confirm ? 0xFFFFFFFF : 0xFF7A8A7E, false);
        graphics.drawString(font, resetText, resetX + 22, top + 6, reset ? 0xFFFFFFFF : 0xFF8A7A7A, false);
    }
}
