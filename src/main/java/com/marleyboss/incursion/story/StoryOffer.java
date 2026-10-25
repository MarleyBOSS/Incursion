package com.marleyboss.incursion.story;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A commission a keeper villager takes (not a vanilla trade): what he wants and what he makes. Shown in the mod's own
 * trading window (StoryTradeScreen); {@code key} names it when the player orders it ("rune", "potion", "forge").
 */
public record StoryOffer(String key, List<ItemStack> costs, ItemStack result) {
    public static final StreamCodec<RegistryFriendlyByteBuf, StoryOffer> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StoryOffer::key,
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), StoryOffer::costs,
            ItemStack.OPTIONAL_STREAM_CODEC, StoryOffer::result,
            StoryOffer::new);
}
