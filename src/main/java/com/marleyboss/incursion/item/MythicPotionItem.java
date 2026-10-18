package com.marleyboss.incursion.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.TooltipFlag;

/**
 * The Mythic Potion: a splash potion that is thrown like any other. What it does is decided where it lands (see
 * {@code Ritual.onImpact}): thrown onto a ready Sarcophagus it creates and activates the Core, thrown at living
 * things it burns them. It has no potion effects of its own, so the vanilla potion tooltip is replaced.
 */
public class MythicPotionItem extends SplashPotionItem {
    public MythicPotionItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.incursion.mythic_potion.tooltip.1").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.incursion.mythic_potion.tooltip.2").withStyle(ChatFormatting.GRAY));
    }
}
