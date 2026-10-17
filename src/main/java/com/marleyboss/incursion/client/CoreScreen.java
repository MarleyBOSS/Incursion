package com.marleyboss.incursion.client;

import com.marleyboss.incursion.menu.CoreMenu;
import com.marleyboss.incursion.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The Core GUI, landscape like the Core Table: status and bars on the left, rewards and costs on the right, the
 * buttons in a grid at the bottom. Panel and bars are drawn without textures.
 */
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
    private static final int COLOR_SHARD = 0xFF7FFFD4;

    private static final int WIDTH = 296;
    private static final int HEIGHT = 208;
    private static final int LEFT_X = 8;
    private static final int LEFT_WIDTH = 136;
    private static final int RIGHT_X = 156;
    private static final int RIGHT_WIDTH = 132;
    private static final int BUTTON_HEIGHT = 20;
    private static final int[] ROW_Y = {134, 157, 180};

    private Button actionButton;
    private Button defuseButton;
    private Button bordersButton;
    private Button aiButton;
    private Button repairButton;
    private Button withdrawButton;

    public CoreScreen(CoreMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        actionButton = addRenderableWidget(Button.builder(Component.empty(), button -> onAction())
                .bounds(leftPos + LEFT_X, topPos + ROW_Y[0], LEFT_WIDTH, BUTTON_HEIGHT).build());
        defuseButton = addRenderableWidget(Button.builder(Component.empty(), button -> click(CoreMenu.BUTTON_DEFUSE))
                .bounds(leftPos + RIGHT_X, topPos + ROW_Y[0], RIGHT_WIDTH, BUTTON_HEIGHT).build());
        bordersButton = addRenderableWidget(Button.builder(Component.empty(), button -> click(CoreMenu.BUTTON_BORDERS))
                .bounds(leftPos + LEFT_X, topPos + ROW_Y[1], LEFT_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("gui.incursion.core.borders_tooltip"))).build());
        aiButton = addRenderableWidget(Button.builder(Component.empty(), button -> click(CoreMenu.BUTTON_AI_VIEW))
                .bounds(leftPos + RIGHT_X, topPos + ROW_Y[1], RIGHT_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("gui.incursion.core.ai_view_tooltip"))).build());
        repairButton = addRenderableWidget(Button.builder(Component.empty(), button -> click(CoreMenu.BUTTON_REPAIR_SHIELD))
                .bounds(leftPos + LEFT_X, topPos + ROW_Y[2], LEFT_WIDTH, BUTTON_HEIGHT).build());
        withdrawButton = addRenderableWidget(Button.builder(Component.empty(), button -> click(CoreMenu.BUTTON_WITHDRAW))
                .bounds(leftPos + RIGHT_X, topPos + ROW_Y[2], RIGHT_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("gui.incursion.core.withdraw_tooltip"))).build());
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

    private void place(Button button, int x, int row, int width) {
        button.setX(leftPos + x);
        button.setY(topPos + ROW_Y[row]);
        button.setWidth(width);
    }

    private void updateButton() {
        if (actionButton == null || defuseButton == null || bordersButton == null || aiButton == null
                || repairButton == null || withdrawButton == null) {
            return;
        }
        boolean active = menu.isActive();
        int shards = menu.get(CoreMenu.DATA_SHARDS);
        int fullWidth = RIGHT_X + RIGHT_WIDTH - LEFT_X;
        int repairState = menu.get(CoreMenu.DATA_REPAIR_STATE);
        boolean showRepair = active && repairState != 0;

        // Grid: the activation button is full width while the Core is off; the borders can be shown before activation.
        defuseButton.visible = active;
        aiButton.visible = active;
        repairButton.visible = showRepair;
        place(actionButton, LEFT_X, 0, active ? LEFT_WIDTH : fullWidth);
        if (active) {
            place(defuseButton, RIGHT_X, 0, RIGHT_WIDTH);
            place(bordersButton, LEFT_X, 1, LEFT_WIDTH);
            place(aiButton, RIGHT_X, 1, RIGHT_WIDTH);
            if (showRepair) {
                place(repairButton, LEFT_X, 2, LEFT_WIDTH);
                place(withdrawButton, RIGHT_X, 2, RIGHT_WIDTH);
            } else {
                place(withdrawButton, LEFT_X, 2, fullWidth);
            }
        } else {
            place(bordersButton, LEFT_X, 1, LEFT_WIDTH);
            place(withdrawButton, RIGHT_X, 1, RIGHT_WIDTH);
        }
        withdrawButton.visible = true;
        withdrawButton.active = shards > 0;
        withdrawButton.setMessage(Component.translatable("gui.incursion.core.button.withdraw", shards));
        bordersButton.visible = true;

        if (showRepair) {
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
        int neededTier = menu.get(CoreMenu.DATA_NEXT_TOOL_TIER);
        if (neededTier > 0) {
            // The Core is upgraded by right-clicking it with the Upgrade Tool; the button only explains that.
            actionButton.setMessage(Component.translatable("gui.incursion.core.button.upgrade_tool"));
            actionButton.active = false;
            actionButton.setTooltip(Tooltip.create(Component.translatable("message.incursion.core.need_tool",
                    ModItems.upgradeTool(neededTier).get().getDescription(), level + 1)));
            return;
        }
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
        graphics.fill(x + 6, y + 17, x + imageWidth - 6, y + 18, COLOR_BORDER);
        graphics.fill(x + 149, y + 22, x + 150, y + 124, COLOR_BORDER);
        graphics.fill(x + 6, y + 127, x + imageWidth - 6, y + 128, COLOR_BORDER);

        int level = menu.get(CoreMenu.DATA_LEVEL);
        int maxLevel = Math.max(1, menu.get(CoreMenu.DATA_MAX_LEVEL));
        drawBar(graphics, x + LEFT_X, y + 44, LEFT_WIDTH, level / (float) maxLevel, COLOR_LEVEL);

        int maxHealth = Math.max(1, menu.get(CoreMenu.DATA_MAX_HEALTH));
        float health = menu.isActive() ? menu.get(CoreMenu.DATA_HEALTH) / (float) maxHealth : 0.0F;
        drawBar(graphics, x + LEFT_X, y + 66, LEFT_WIDTH, health, COLOR_HEALTH);

        int defuseTotal = menu.get(CoreMenu.DATA_DEFUSE_TOTAL);
        int defuseLeft = menu.get(CoreMenu.DATA_DEFUSE_LEFT);
        if (menu.isActive() && defuseLeft > 0 && defuseTotal > 0) {
            drawBar(graphics, x + RIGHT_X, y + 116, RIGHT_WIDTH, 1.0F - defuseLeft / (float) defuseTotal, COLOR_DEFUSE);
        }
    }

    private static void drawBar(GuiGraphics graphics, int x, int y, int width, float fraction, int color) {
        fraction = Math.max(0.0F, Math.min(1.0F, fraction));
        graphics.fill(x, y, x + width, y + 6, COLOR_BAR_BG);
        graphics.fill(x, y, x + Math.round(width * fraction), y + 6, color);
    }

    /** Draws one line, cut off at the given width instead of running into the next column. */
    private void drawFit(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
        graphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(text, width)), x, y, color, false);
    }

    private void drawWrapped(GuiGraphics graphics, Component text, int x, int y, int width, int color, int maxLines) {
        int line = 0;
        for (FormattedCharSequence sequence : font.split(text, width)) {
            if (line >= maxLines) {
                break;
            }
            graphics.drawString(font, sequence, x, y + line * 10, color, false);
            line++;
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, (imageWidth - font.width(title)) / 2, 5, COLOR_TITLE, true);

        boolean active = menu.isActive();
        Component state = active
                ? Component.translatable("message.incursion.core.state.active").withStyle(ChatFormatting.RED)
                : Component.translatable("message.incursion.core.state.inactive").withStyle(ChatFormatting.GREEN);
        drawFit(graphics, Component.translatable("gui.incursion.core.status", state), LEFT_X, 22, LEFT_WIDTH, COLOR_TEXT);

        int level = menu.get(CoreMenu.DATA_LEVEL);
        int maxLevel = menu.get(CoreMenu.DATA_MAX_LEVEL);
        drawFit(graphics, Component.translatable("gui.incursion.core.level", level, maxLevel), LEFT_X, 33, LEFT_WIDTH, COLOR_TEXT);

        if (active) {
            drawFit(graphics, Component.translatable("gui.incursion.core.health",
                    menu.get(CoreMenu.DATA_HEALTH), menu.get(CoreMenu.DATA_MAX_HEALTH)), LEFT_X, 55, LEFT_WIDTH, COLOR_TEXT);
        } else {
            drawFit(graphics, Component.translatable("gui.incursion.core.health_inactive"), LEFT_X, 55, LEFT_WIDTH, COLOR_MUTED);
        }

        int infoColor = active ? COLOR_TEXT : COLOR_MUTED;
        drawFit(graphics, Component.translatable("gui.incursion.core.core_zone",
                menu.get(CoreMenu.DATA_CORE_ZONE)), LEFT_X, 78, LEFT_WIDTH, infoColor);
        drawFit(graphics, Component.translatable("gui.incursion.core.siege_area",
                menu.get(CoreMenu.DATA_SIEGE_RADIUS)), LEFT_X, 89, LEFT_WIDTH, infoColor);
        drawFit(graphics, Component.translatable("gui.incursion.core.attackers",
                menu.get(CoreMenu.DATA_SIEGE_MOBS), menu.get(CoreMenu.DATA_MAX_SIEGE_MOBS)), LEFT_X, 100, LEFT_WIDTH, infoColor);
        drawFit(graphics, Component.translatable("gui.incursion.core.explosion_radius",
                menu.get(CoreMenu.DATA_EXPLOSION_RADIUS)), LEFT_X, 111, LEFT_WIDTH, infoColor);

        // Right column: cost with item icon on top, then the rewards.
        if (!active) {
            drawFit(graphics, Component.translatable("gui.incursion.core.activation_cost"), RIGHT_X, 22, RIGHT_WIDTH, COLOR_TEXT);
            graphics.renderItem(new ItemStack(menu.getActivationItem()), RIGHT_X, 31);
            drawFit(graphics, Component.translatable("gui.incursion.core.cost_owned", 1,
                    Math.min(menu.get(CoreMenu.DATA_ACTIVATION_ITEMS_OWNED), 9999)), RIGHT_X + 20, 36, RIGHT_WIDTH - 20, COLOR_TEXT);
        } else if (level < maxLevel) {
            drawFit(graphics, Component.translatable("gui.incursion.core.upgrade_cost"), RIGHT_X, 22, RIGHT_WIDTH, COLOR_TEXT);
            graphics.renderItem(new ItemStack(menu.getUpgradeItem()), RIGHT_X, 31);
            drawFit(graphics, Component.translatable("gui.incursion.core.cost_owned",
                    menu.get(CoreMenu.DATA_NEXT_COST), Math.min(menu.get(CoreMenu.DATA_UPGRADE_ITEMS_OWNED), 9999)),
                    RIGHT_X + 20, 36, RIGHT_WIDTH - 20, COLOR_TEXT);
        }

        drawFit(graphics, Component.translatable("gui.incursion.core.shards", menu.get(CoreMenu.DATA_SHARDS)),
                RIGHT_X, 55, RIGHT_WIDTH, COLOR_SHARD);
        drawFit(graphics, Component.translatable("gui.incursion.core.nights",
                menu.get(CoreMenu.DATA_NIGHTS), menu.get(CoreMenu.DATA_SHARDS_PER_NIGHT)), RIGHT_X, 66, RIGHT_WIDTH, infoColor);
        drawFit(graphics, Component.translatable("gui.incursion.core.kills", menu.get(CoreMenu.DATA_KILLS)),
                RIGHT_X, 77, RIGHT_WIDTH, infoColor);

        int defuseLeft = menu.get(CoreMenu.DATA_DEFUSE_LEFT);
        if (active && defuseLeft > 0) {
            drawFit(graphics, Component.translatable("gui.incursion.core.defusing", (defuseLeft + 19) / 20),
                    RIGHT_X, 100, RIGHT_WIDTH, COLOR_DEFUSE);
        } else if (active) {
            int available = menu.get(CoreMenu.DATA_SHIELDS_AVAILABLE);
            int firstLevel = menu.get(CoreMenu.DATA_FIRST_SHIELD_LEVEL);
            if (available > 0) {
                int intact = menu.get(CoreMenu.DATA_SHIELDS_INTACT);
                drawFit(graphics, Component.translatable("gui.incursion.core.shields", intact, available),
                        RIGHT_X, 100, RIGHT_WIDTH, intact > 0 ? COLOR_SHIELD : COLOR_HEALTH);
            } else if (firstLevel > 0) {
                drawWrapped(graphics, Component.translatable("gui.incursion.core.shields_none", firstLevel),
                        RIGHT_X, 100, RIGHT_WIDTH, COLOR_MUTED, 2);
            } else {
                drawWrapped(graphics, Component.translatable("gui.incursion.core.rewards"), RIGHT_X, 100, RIGHT_WIDTH, COLOR_MUTED, 2);
            }
        } else {
            drawWrapped(graphics, Component.translatable("gui.incursion.core.inactive_hint"), RIGHT_X, 92, RIGHT_WIDTH, COLOR_MUTED, 3);
        }
    }
}
