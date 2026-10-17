package com.marleyboss.incursion.table;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.data.CoreWorldData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Rules of the Core Table. The table lists the enchantments that are on the item and have a vanilla maximum of at
 * least II. An enchantment at its vanilla maximum can be unlocked once per world with shards; after that every level
 * above the maximum costs shards and experience levels, up to a limit that grows with the Core level.
 */
public final class CoreTableLogic {
    /** Enchantments in this tag cannot be raised at the table (datapack-configurable). */
    public static final TagKey<Enchantment> BLOCKED = TagKey.create(Registries.ENCHANTMENT,
            ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "table_blocked"));

    public static final int STATE_NONE = 0;
    /** At the vanilla maximum, not yet unlocked: the button unlocks it. */
    public static final int STATE_LOCKED = 1;
    /** Unlocked and below the limit: the button raises it by one level. */
    public static final int STATE_READY = 2;
    /** At the limit that the current Core level allows. */
    public static final int STATE_AT_CAP = 3;
    /** Below the vanilla maximum: use a normal enchanting table or anvil first. */
    public static final int STATE_BELOW_MAX = 4;
    /** There is no active Core. */
    public static final int STATE_NO_CORE = 5;

    /** One enchantment on the item that the table can work with. */
    public record Entry(Holder<Enchantment> holder, String key, int level, int vanillaMax) {
    }

    private CoreTableLogic() {
    }

    /** The enchantments on the stack that the table lists, sorted by id (the same order on both sides). */
    public static List<Entry> candidates(ItemStack stack) {
        List<Entry> list = new ArrayList<>();
        if (stack.isEmpty()) {
            return list;
        }
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            int vanillaMax = holder.value().getMaxLevel();
            if (vanillaMax < 2 || holder.is(BLOCKED)) {
                continue;
            }
            String key = holder.unwrapKey().map(k -> k.location().toString()).orElse("");
            if (key.isEmpty()) {
                continue;
            }
            list.add(new Entry(holder, key, enchantments.getLevel(holder), vanillaMax));
        }
        list.sort(Comparator.comparing(Entry::key));
        return list;
    }

    /** The active Core of the world, or {@code null} if there is none (or it is not loaded). */
    @Nullable
    public static CoreBlockEntity activeCore(MinecraftServer server) {
        CoreWorldData data = CoreWorldData.get(server);
        ResourceKey<Level> dimension = data.getDimension();
        BlockPos pos = data.getPos();
        if (dimension == null || pos == null) {
            return null;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.isLoaded(pos)) {
            return null;
        }
        if (level.getBlockEntity(pos) instanceof CoreBlockEntity core && core.isActive()) {
            return core;
        }
        return null;
    }

    /** Highest level that the table may give this enchantment at the given Core level. */
    public static int cap(Entry entry, int coreLevel) {
        return entry.vanillaMax() + Config.tableExtraLevels(coreLevel);
    }

    public static int state(Entry entry, @Nullable CoreBlockEntity core, CoreTableData unlocks) {
        if (core == null) {
            return STATE_NO_CORE;
        }
        if (entry.level() < entry.vanillaMax()) {
            return STATE_BELOW_MAX;
        }
        if (!unlocks.isUnlocked(entry.key())) {
            return STATE_LOCKED;
        }
        if (entry.level() >= cap(entry, core.getCoreLevel())) {
            return STATE_AT_CAP;
        }
        return STATE_READY;
    }

    /** Shard cost of the next step (unlock or raise); 0 if there is none. */
    public static int shardCost(Entry entry, int state) {
        if (state == STATE_LOCKED) {
            return Config.TABLE_UNLOCK_COST.get();
        }
        if (state == STATE_READY) {
            return Config.tableShardCost(entry.level() + 1 - entry.vanillaMax());
        }
        return 0;
    }

    /** Experience level cost of the next step; only raising costs experience. */
    public static int xpCost(Entry entry, int state) {
        return state == STATE_READY ? Config.tableXpCost(entry.level() + 1 - entry.vanillaMax()) : 0;
    }

    /** Unlocks the enchantment for the table (shards from the Core, free in creative mode). */
    public static boolean tryUnlock(Player player, Entry entry, @Nullable CoreBlockEntity core, CoreTableData unlocks) {
        if (state(entry, core, unlocks) != STATE_LOCKED) {
            return false;
        }
        boolean free = player.getAbilities().instabuild;
        int cost = shardCost(entry, STATE_LOCKED);
        if (!free && core.getShards() < cost) {
            player.displayClientMessage(Component.translatable("message.incursion.table.need_shards", cost,
                    core.getShards()).withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!free) {
            core.takeShards(cost);
        }
        unlocks.unlock(entry.key());
        player.displayClientMessage(Component.translatable("message.incursion.table.unlocked",
                entry.holder().value().description()).withStyle(ChatFormatting.AQUA), true);
        return true;
    }

    /** Raises the enchantment on the stack by one level (shards from the Core, experience levels from the player). */
    public static boolean tryRaise(Player player, ItemStack stack, Entry entry, @Nullable CoreBlockEntity core,
                                   CoreTableData unlocks) {
        if (state(entry, core, unlocks) != STATE_READY || stack.isEmpty()) {
            return false;
        }
        boolean free = player.getAbilities().instabuild;
        int shards = shardCost(entry, STATE_READY);
        int xp = xpCost(entry, STATE_READY);
        if (!free && core.getShards() < shards) {
            player.displayClientMessage(Component.translatable("message.incursion.table.need_shards", shards,
                    core.getShards()).withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!free && player.experienceLevel < xp) {
            player.displayClientMessage(Component.translatable("message.incursion.table.need_xp", xp)
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!free) {
            core.takeShards(shards);
            player.giveExperienceLevels(-xp);
        }
        int newLevel = entry.level() + 1;
        EnchantmentHelper.updateEnchantments(stack, mutable -> mutable.set(entry.holder(), newLevel));
        player.displayClientMessage(Component.translatable("message.incursion.table.raised",
                entry.holder().value().description(), roman(newLevel)).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        return true;
    }

    /** Roman numeral (vanilla only has names up to X); plain digits outside 1..3999. */
    public static String roman(int number) {
        if (number <= 0 || number >= 4000) {
            return String.valueOf(number);
        }
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int rest = number;
        for (int i = 0; i < values.length; i++) {
            while (rest >= values[i]) {
                out.append(symbols[i]);
                rest -= values[i];
            }
        }
        return out.toString();
    }
}
