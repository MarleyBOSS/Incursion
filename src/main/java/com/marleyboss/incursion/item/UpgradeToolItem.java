package com.marleyboss.incursion.item;

import com.marleyboss.incursion.Config;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The Upgrade Tool: needed (in the inventory) to upgrade the Core. One tier covers
 * {@link Config#UPGRADE_TOOL_LEVELS} Core levels (default 5: tier I up to level 5, II up to 10, III up to 15,
 * IV everything above). The tool is not used up; stronger tiers are crafted from the weaker one.
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
        int from = (tier - 1) * perTier + 1;
        int to = tier >= Config.UPGRADE_TOOL_MAX_TIER ? Math.max(from, maxLevel) : tier * perTier;
        tooltip.add(Component.translatable("item.incursion.upgrade_tool.levels", from, to)
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.incursion.upgrade_tool.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
