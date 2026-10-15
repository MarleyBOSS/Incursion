package com.marleyboss.incursion.client;

import com.marleyboss.incursion.menu.CoreMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** The Core GUI: status, health, zones, upgrade/activation button. Drawn without textures. */
public class CoreScreen extends AbstractContainerScreen<CoreMenu> {
    private static final int COLOR_PANEL = 0xF0181020;
    private static final int COLOR_BORDER = 0xFF7A3FB0;
    private static final int COLOR_BAR_BG = 0xFF2A2030;
    private static final int COLOR_LEVEL = 0xFFB066FF;
    private static final int COLOR_HEALTH = 0xFFE03030;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_MUTED = 0xFF9A90A8;
    private static final int COLOR_TITLE = 0xFFFFC040;
    private static final int COLOR_DEFUSE = 0xFFFFD040;
    private static final int COLOR_SHIELD = 0xFF60C8FF;

    private static final int BAR_X = 10;
    private static final int BAR_WIDTH = 196;

    private Button actionButton;
    private Button defuseButton;
    private Button bordersButton;
    private Button aiButton;
    private Button repairButton;

    public CoreScreen(CoreMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 216;
        this.imageHeight = 264;
    }

    @Override
    protected void init() {
        super.init();
        actionButton = addRenderableWidget(Button.builder(Component.empty(), button -> onAction())
                .bounds(leftPos + 10, topPos + imageHeight - 48, imageWidth - 20, 20)
                .build());
        defuseButton = addRenderableWidget(Button.builder(Component.empty(), button -> click(CoreMenu.BUTTON_DEFUSE))
                .bounds(leftPos + 10, topPos + imageHeight - 24, imageWidth - 20, 20)
                .build());
        int half = (imageWidth - 24) / 2;
        bordersButton = addRenderableWidget(Button.builder(Component.empty(), button -> click(CoreMenu.BUTTON_BORDERS))
                .bounds(leftPos + 10, topPos + imageHeight - 72, half, 20)
                .tooltip(Tooltip.create(Component.translatable("gui.incursion.core.borders_tooltip")))
                .build());
        aiButton = addRenderableWidget(Button.builder(Component.empty(), button -> click(CoreMenu.BUTTON_AI_VIEW))
                .bounds(leftPos + 14 + half, topPos + imageHeight - 72, half, 20)
                .tooltip(Tooltip.create(Component.translatable("gui.incursion.core.ai_view_tooltip")))
                .build());
        repairButton = addRenderableWidget(Button.builder(Component.empty(), button -> click(CoreMenu.BUTTON_REPAIR_SHIELD))
                .bounds(leftPos + 10, topPos + imageHeight - 96, imageWidth - 20, 20)
                .build());
        updateButton();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateButton();
    }

    private void onAction() {
        click(menu.isActive() ? CoreMenu.BUTTON_UPGRADE : CoreMenu.BUTTON_ACTIVATE);
    }

    private void click(int id) {
        if (minecraft == null || minecraft.gameMode == null) {
            return;
        }
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private void updateButton() {
        if (actionButton == null || defuseButton == null || bordersButton == null || aiButton == null
                || repairButton == null) {
            return;
        }
        boolean active = menu.isActive();
        defuseButton.visible = active;
        bordersButton.visible = active;
        aiButton.visible = active;
        int repairState = menu.get(CoreMenu.DATA_REPAIR_STATE);
        repairButton.visible = active && repairState != 0;
        if (repairButton.visible) {
            int repairCost = menu.get(CoreMenu.DATA_REPAIR_COST);
            repairButton.setMessage(Component.translatable("gui.incursion.core.button.repair_shield", repairCost));
            repairButton.active = repairState == 1;
            repairButton.setTooltip(Tooltip.create(repairState == 1
                    ? Component.translatable("gui.incursion.core.repair_tooltip", repairCost)
                    : Component.translatable("gui.incursion.core.repair_need_level", menu.get(CoreMenu.DATA_REPAIR_LEVEL))));
        }
        bordersButton.setMessage(Component.translatable(menu.get(CoreMenu.DATA_SHOW_BORDERS) != 0
                ? "gui.incursion.core.button.borders_on" : "gui.incursion.core.button.borders_off"));
        aiButton.setMessage(Component.translatable(menu.get(CoreMenu.DATA_SHOW_AI) != 0
                ? "gui.incursion.core.button.ai_on" : "gui.incursion.core.button.ai_off"));
        // Inactive: the single button sits at the bottom.
        actionButton.setY(topPos + imageHeight - (active ? 48 : 24));
        if (active) {
            boolean defusing = menu.get(CoreMenu.DATA_DEFUSE_LEFT) > 0;
            defuseButton.setMessage(Component.translatable(defusing
                    ? "gui.incursion.core.button.defuse_cancel" : "gui.incursion.core.button.defuse"));
            defuseButton.setTooltip(defusing ? null : Tooltip.create(Component.translatable(
                    "gui.incursion.core.defuse_tooltip", menu.get(CoreMenu.DATA_DEFUSE_SECONDS))));
        }
        if (!active) {
            boolean canPay = menu.get(CoreMenu.DATA_ACTIVATION_ITEMS_OWNED) >= 1;
            actionButton.setMessage(Component.translatable("gui.incursion.core.button.activate"));
            actionButton.active = canPay;
            actionButton.setTooltip(canPay ? null : Tooltip.create(Component.translatable(
                    "message.incursion.core.need_activation_item", menu.getActivationItem().getDescription())));
            return;
        }
        int level = menu.get(CoreMenu.DATA_LEVEL);
        int maxLevel = menu.get(CoreMenu.DATA_MAX_LEVEL);
        if (level >= maxLevel) {
            actionButton.setMessage(Component.translatable("gui.incursion.core.button.max"));
            actionButton.active = false;
            actionButton.setTooltip(null);
            return;
        }
        int cost = menu.get(CoreMenu.DATA_NEXT_COST);
        boolean canPay = menu.get(CoreMenu.DATA_UPGRADE_ITEMS_OWNED) >= cost;
        actionButton.setMessage(Component.translatable("gui.incursion.core.button.upgrade", level + 1));
        actionButton.active = canPay;
        actionButton.setTooltip(canPay ? null : Tooltip.create(Component.translatable(
                "message.incursion.core.not_enough", cost, menu.getUpgradeItem().getDescription())));
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
        graphics.fill(x + 6, y + 18, x + imageWidth - 6, y + 19, COLOR_BORDER);

        int level = menu.get(CoreMenu.DATA_LEVEL);
        int maxLevel = Math.max(1, menu.get(CoreMenu.DATA_MAX_LEVEL));
        drawBar(graphics, x + BAR_X, y + 44, level / (float) maxLevel, COLOR_LEVEL);

        int maxHealth = Math.max(1, menu.get(CoreMenu.DATA_MAX_HEALTH));
        float health = menu.isActive() ? menu.get(CoreMenu.DATA_HEALTH) / (float) maxHealth : 0.0F;
        drawBar(graphics, x + BAR_X, y + 68, health, COLOR_HEALTH);

        int defuseTotal = menu.get(CoreMenu.DATA_DEFUSE_TOTAL);
        int defuseLeft = menu.get(CoreMenu.DATA_DEFUSE_LEFT);
        if (menu.isActive() && defuseLeft > 0 && defuseTotal > 0) {
            drawBar(graphics, x + BAR_X, y + 159, 1.0F - defuseLeft / (float) defuseTotal, COLOR_DEFUSE);
        }
    }

    private static void drawBar(GuiGraphics graphics, int x, int y, float fraction, int color) {
        fraction = Math.max(0.0F, Math.min(1.0F, fraction));
        graphics.fill(x, y, x + BAR_WIDTH, y + 6, COLOR_BAR_BG);
        graphics.fill(x, y, x + Math.round(BAR_WIDTH * fraction), y + 6, color);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Title
        graphics.drawString(font, title, (imageWidth - font.width(title)) / 2, 6, COLOR_TITLE, true);

        boolean active = menu.isActive();
        Component state = active
                ? Component.translatable("message.incursion.core.state.active").withStyle(ChatFormatting.RED)
                : Component.translatable("message.incursion.core.state.inactive").withStyle(ChatFormatting.GREEN);
        graphics.drawString(font, Component.translatable("gui.incursion.core.status", state), 10, 23, COLOR_TEXT, false);

        int level = menu.get(CoreMenu.DATA_LEVEL);
        int maxLevel = menu.get(CoreMenu.DATA_MAX_LEVEL);
        graphics.drawString(font, Component.translatable("gui.incursion.core.level", level, maxLevel), 10, 34, COLOR_TEXT, false);

        if (active) {
            graphics.drawString(font, Component.translatable("gui.incursion.core.health",
                    menu.get(CoreMenu.DATA_HEALTH), menu.get(CoreMenu.DATA_MAX_HEALTH)), 10, 58, COLOR_TEXT, false);
        } else {
            graphics.drawString(font, Component.translatable("gui.incursion.core.health_inactive"), 10, 58, COLOR_MUTED, false);
        }

        int infoColor = active ? COLOR_TEXT : COLOR_MUTED;
        graphics.drawString(font, Component.translatable("gui.incursion.core.core_zone",
                menu.get(CoreMenu.DATA_CORE_ZONE)), 10, 82, infoColor, false);
        graphics.drawString(font, Component.translatable("gui.incursion.core.siege_area",
                menu.get(CoreMenu.DATA_SIEGE_RADIUS)), 10, 93, infoColor, false);
        graphics.drawString(font, Component.translatable("gui.incursion.core.attackers",
                menu.get(CoreMenu.DATA_SIEGE_MOBS), menu.get(CoreMenu.DATA_MAX_SIEGE_MOBS)), 10, 104, infoColor, false);
        graphics.drawString(font, Component.translatable("gui.incursion.core.explosion_radius",
                menu.get(CoreMenu.DATA_EXPLOSION_RADIUS)), 10, 115, infoColor, false);

        // Cost line with item icon
        int costY = 128;
        if (!active) {
            Component label = Component.translatable("gui.incursion.core.activation_cost");
            graphics.drawString(font, label, 10, costY + 4, COLOR_TEXT, false);
            int iconX = 12 + font.width(label);
            graphics.renderItem(new ItemStack(menu.getActivationItem()), iconX, costY);
            graphics.drawString(font, Component.translatable("gui.incursion.core.cost_owned", 1,
                    Math.min(menu.get(CoreMenu.DATA_ACTIVATION_ITEMS_OWNED), 9999)), iconX + 18, costY + 4, COLOR_TEXT, false);
        } else if (level < maxLevel) {
            Component label = Component.translatable("gui.incursion.core.upgrade_cost");
            graphics.drawString(font, label, 10, costY + 4, COLOR_TEXT, false);
            int iconX = 12 + font.width(label);
            graphics.renderItem(new ItemStack(menu.getUpgradeItem()), iconX, costY);
            graphics.drawString(font, Component.translatable("gui.incursion.core.cost_owned",
                    menu.get(CoreMenu.DATA_NEXT_COST), Math.min(menu.get(CoreMenu.DATA_UPGRADE_ITEMS_OWNED), 9999)),
                    iconX + 18, costY + 4, COLOR_TEXT, false);
        }

        int defuseLeft = menu.get(CoreMenu.DATA_DEFUSE_LEFT);
        if (active && defuseLeft > 0) {
            graphics.drawString(font, Component.translatable("gui.incursion.core.defusing",
                    (defuseLeft + 19) / 20), 10, 148, COLOR_DEFUSE, false);
        } else if (active) {
            int available = menu.get(CoreMenu.DATA_SHIELDS_AVAILABLE);
            int firstLevel = menu.get(CoreMenu.DATA_FIRST_SHIELD_LEVEL);
            if (available > 0) {
                int intact = menu.get(CoreMenu.DATA_SHIELDS_INTACT);
                graphics.drawString(font, Component.translatable("gui.incursion.core.shields", intact, available),
                        10, 148, intact > 0 ? COLOR_SHIELD : COLOR_HEALTH, false);
            } else if (firstLevel > 0) {
                graphics.drawString(font, Component.translatable("gui.incursion.core.shields_none", firstLevel),
                        10, 148, COLOR_MUTED, false);
            } else {
                graphics.drawString(font, Component.translatable("gui.incursion.core.rewards"), 10, 148, COLOR_MUTED, false);
            }
        } else {
            graphics.drawString(font, Component.translatable("gui.incursion.core.inactive_hint"), 10, 148, COLOR_MUTED, false);
        }
    }
}
