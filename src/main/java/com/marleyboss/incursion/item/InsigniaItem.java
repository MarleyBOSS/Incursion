package com.marleyboss.incursion.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The insignia of a general (stage 1 to 4): a general leaves it behind when he is killed in his night (as a block that
 * is mined). Right-clicking the Core with it completes the waiting upgrade to level 5, 10, 15 or 20.
 */
public class InsigniaItem extends Item {
    private final int stage;

    public InsigniaItem(int stage, Properties properties) {
        super(properties);
        this.stage = stage;
    }

    /** 1 to 4: the general this insignia belongs to. */
    public int getStage() {
        return stage;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.incursion.general_insignia.use", stage * 5)
                .withStyle(ChatFormatting.GRAY));
    }
}
