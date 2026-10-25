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
 * A commission the villager is working on or has finished, kept in the villager himself (persistent data, so it
 * survives unloading and restarts): the order (up to 16 stacks) for its owner, when it was begun and when it is done
 * (game time), what kind of work it is and what lies on his station while he works. Once it is done the owner takes it
 * in the pickup window. One order per villager.
 */
public final class StoryReady {
    private static final String KEY_ITEMS = "IncursionReadyItems";
    private static final String KEY_OWNER = "IncursionReadyOwner";
    private static final String KEY_START = "IncursionWorkStart";
    private static final String KEY_END = "IncursionWorkEnd";
    private static final String KEY_KIND = "IncursionWorkKind";
    private static final String KEY_PROPS = "IncursionWorkProps";
    private static final String KEY_MADE = "IncursionMadeInARow";
    private static final String KEY_REST = "IncursionRestUntil";
    /** Items a keeper makes in a row before he has to rest. */
    public static final int CAPACITY = 16;

    private StoryReady() {
    }

    /** He holds an order (still in work or done). */
    public static boolean has(Villager villager) {
        return villager.getPersistentData().contains(KEY_ITEMS);
    }

    /** The work on it is over (game time of his level). */
    public static boolean done(Villager villager) {
        return has(villager) && villager.level().getGameTime() >= villager.getPersistentData().getLong(KEY_END);
    }

    public static boolean isOwner(Villager villager, UUID player) {
        CompoundTag data = villager.getPersistentData();
        return data.hasUUID(KEY_OWNER) && data.getUUID(KEY_OWNER).equals(player);
    }

    public static long start(Villager villager) {
        return villager.getPersistentData().getLong(KEY_START);
    }

    public static long end(Villager villager) {
        return villager.getPersistentData().getLong(KEY_END);
    }

    public static String kind(Villager villager) {
        return villager.getPersistentData().getString(KEY_KIND);
    }

    public static UUID owner(Villager villager) {
        CompoundTag data = villager.getPersistentData();
        return data.hasUUID(KEY_OWNER) ? data.getUUID(KEY_OWNER) : null;
    }

    public static List<ItemStack> items(Villager villager) {
        return read(villager, KEY_ITEMS);
    }

    public static List<ItemStack> props(Villager villager) {
        return read(villager, KEY_PROPS);
    }

    private static List<ItemStack> read(Villager villager, String key) {
        List<ItemStack> list = new ArrayList<>();
        ListTag tag = villager.getPersistentData().getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < tag.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(villager.registryAccess(), tag.getCompound(i));
            if (!stack.isEmpty()) {
                list.add(stack);
            }
        }
        return list;
    }

    private static ListTag write(Villager villager, List<ItemStack> stacks) {
        ListTag tag = new ListTag();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                tag.add(stack.save(villager.registryAccess()));
            }
        }
        return tag;
    }

    /**
     * A new order: {@code total} (split into stacks of the item's maximum size, at most 16 stacks) for {@code owner}, done
     * at game time {@code end}.
     */
    public static void begin(Villager villager, UUID owner, ItemStack total, List<ItemStack> props, String kind, long end) {
        List<ItemStack> stacks = new ArrayList<>();
        int left = total.getCount();
        while (left > 0 && stacks.size() < 16) {
            int n = Math.min(left, total.getMaxStackSize());
            stacks.add(total.copyWithCount(n));
            left -= n;
        }
        CompoundTag data = villager.getPersistentData();
        data.put(KEY_ITEMS, write(villager, stacks));
        data.put(KEY_PROPS, write(villager, props));
        data.putUUID(KEY_OWNER, owner);
        data.putString(KEY_KIND, kind);
        data.putLong(KEY_START, villager.level().getGameTime());
        data.putLong(KEY_END, end);
    }

    /** What is left after the owner took some (nothing left clears the order). */
    public static void setRest(Villager villager, List<ItemStack> rest) {
        ListTag tag = write(villager, rest);
        CompoundTag data = villager.getPersistentData();
        if (tag.isEmpty()) {
            clear(villager);
        } else {
            data.put(KEY_ITEMS, tag);
        }
    }

    /** How many more items he can make before he has to rest (after a rest he can make {@link #CAPACITY} again). */
    public static int capacityLeft(Villager villager) {
        CompoundTag data = villager.getPersistentData();
        if (data.getLong(KEY_REST) > 0 && villager.level().getGameTime() >= data.getLong(KEY_REST)) {
            data.remove(KEY_REST); // rested
            data.remove(KEY_MADE);
        }
        return Math.max(0, CAPACITY - data.getInt(KEY_MADE));
    }

    /** Seconds he still has to rest (0 = he can work). */
    public static int restSeconds(Villager villager) {
        capacityLeft(villager); // ends a finished rest
        long until = villager.getPersistentData().getLong(KEY_REST);
        long now = villager.level().getGameTime();
        return until > now ? (int) ((until - now + 19) / 20) : 0;
    }

    /**
     * {@code quantity} more items were ordered; the work ends at game time {@code end}. When that fills his capacity he
     * rests for {@code restTicks} after the work.
     */
    public static void addMade(Villager villager, int quantity, long end, long restTicks) {
        CompoundTag data = villager.getPersistentData();
        int made = data.getInt(KEY_MADE) + quantity;
        data.putInt(KEY_MADE, made);
        if (made >= CAPACITY) {
            data.putLong(KEY_REST, end + restTicks);
        }
    }

    public static void clear(Villager villager) {
        CompoundTag data = villager.getPersistentData();
        data.remove(KEY_ITEMS);
        data.remove(KEY_OWNER);
        data.remove(KEY_START);
        data.remove(KEY_END);
        data.remove(KEY_KIND);
        data.remove(KEY_PROPS);
    }
}
