package com.marleyboss.incursion.item;

import com.marleyboss.incursion.Config;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The Upgrade Tool: right-click the Core with it in the hand to upgrade it. Tier I is crafted and does level 1-4;
 * a Master Toolsmith forges tool N with the insignia of general N into tool N+1, which completes the waiting upgrade
 * (level 5, 10, 15, 20) and does the levels up to the next tablet. The tool is not used up.
 */
public class UpgradeToolItem extends Item {
    private final int tier;

    public UpgradeToolItem(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public int getTier() {
        return tier;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        int perTier = 5;
        int maxLevel = 20;
        try {
            perTier = Config.UPGRADE_TOOL_LEVELS.get();
            maxLevel = Config.MAX_LEVEL.get();
        } catch (IllegalStateException e) {
            // The config is not loaded yet (e.g. creative tab on the title screen): show the defaults.
        }
        // Tier I does the levels below the first tablet; every higher tier is forged with a general's insignia,
        // completes that tablet's level and does the levels up to the next tablet.
        int from = tier == 1 ? 1 : (tier - 1) * perTier;
        int to = tier >= Config.UPGRADE_TOOL_MAX_TIER ? Math.max(from, maxLevel) : tier * perTier - 1;
        tooltip.add((from == to ? Component.translatable("item.incursion.upgrade_tool.level_single", from)
                : Component.translatable("item.incursion.upgrade_tool.levels", from, to)).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.incursion.upgrade_tool.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
