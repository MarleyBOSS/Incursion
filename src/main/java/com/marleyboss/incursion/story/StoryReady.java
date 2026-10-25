package com.marleyboss.incursion.story;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A finished commission waits in the villager (saved in his persistent data) until the player it was made for talks to
 * him again and takes it in the pickup window. One finished order (up to 9 stacks) per villager.
 */
public final class StoryReady {
    private static final String KEY_ITEMS = "IncursionReadyItems";
    private static final String KEY_OWNER = "IncursionReadyOwner";

    private StoryReady() {
    }

    public static boolean has(Villager villager) {
        return villager.getPersistentData().contains(KEY_ITEMS);
    }

    public static boolean isOwner(Villager villager, UUID player) {
        CompoundTag data = villager.getPersistentData();
        return data.hasUUID(KEY_OWNER) && data.getUUID(KEY_OWNER).equals(player);
    }

    public static List<ItemStack> items(Villager villager) {
        List<ItemStack> list = new ArrayList<>();
        ListTag tag = villager.getPersistentData().getList(KEY_ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < tag.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(villager.registryAccess(), tag.getCompound(i));
            if (!stack.isEmpty()) {
                list.add(stack);
            }
        }
        return list;
    }

    /** Stores {@code total} (split into stacks of the item's maximum size, at most 9 stacks) for {@code owner}. */
    public static void set(Villager villager, UUID owner, ItemStack total) {
        List<ItemStack> stacks = new ArrayList<>();
        int left = total.getCount();
        while (left > 0 && stacks.size() < 9) {
            int n = Math.min(left, total.getMaxStackSize());
            stacks.add(total.copyWithCount(n));
            left -= n;
        }
        setAll(villager, owner, stacks);
    }

    /** Replaces what is waiting (an empty list clears it). */
    public static void setAll(Villager villager, UUID owner, List<ItemStack> stacks) {
        List<ItemStack> kept = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                kept.add(stack);
            }
        }
        CompoundTag data = villager.getPersistentData();
        if (kept.isEmpty()) {
            data.remove(KEY_ITEMS);
            data.remove(KEY_OWNER);
            return;
        }
        ListTag tag = new ListTag();
        for (ItemStack stack : kept) {
            tag.add(stack.save(villager.registryAccess()));
        }
        data.put(KEY_ITEMS, tag);
        data.putUUID(KEY_OWNER, owner);
    }
}
