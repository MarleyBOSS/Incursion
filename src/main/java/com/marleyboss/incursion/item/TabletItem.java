package com.marleyboss.incursion.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The tablet of a guardian (stage 1 Elder Guardian, 2 Warden, 3 Ender Dragon, 4 Wither): the boss drops it, and the
 * upgrade to Core level 5, 10, 15 or 20 needs it. It is set in with the Upgrade Tool in one hand and the tablet in the
 * other (right-click the Core). Its engraving cannot be read yet (it is written in the old script); the lore tab of the
 * Core will show the text once the tablet is set in (later version).
 */
public class TabletItem extends Item {
    /** The vanilla "alt" font: the script of the enchanting table. */
    private static final ResourceLocation ALT_FONT = ResourceLocation.withDefaultNamespace("alt");
    /** What stands on the tablets, unreadable in the old script (different on every tablet). */
    private static final String[] ENGRAVING = {
            "the eye keeps the deep",
            "the ear keeps the stone",
            "the wings keep the gate",
            "the heads keep the ash"
    };

    private final int stage;

    public TabletItem(int stage, Properties properties) {
        super(properties);
        this.stage = stage;
    }

    /** 1 to 4: the Core stage this tablet opens (level 5, 10, 15, 20). */
    public int getStage() {
        return stage;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.literal(ENGRAVING[Math.max(0, Math.min(3, stage - 1))])
                .withStyle(style -> style.withFont(ALT_FONT).withColor(ChatFormatting.DARK_PURPLE)));
        tooltip.add(Component.translatable("item.incursion.tablet.level", stage * 5).withStyle(ChatFormatting.GRAY));
    }
}
