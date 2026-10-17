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
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
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
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Rules of the Core Table. The table lists every enchantment that the item in the slot can have (vanilla maximum at
 * least II). An enchantment that is on the item at its vanilla maximum can be raised one level at a time above it.
 * Only one enchantment per item may be above its vanilla maximum. A raise costs shards and experience levels, takes
 * time (the item stays in the table) and can only be started in the evening; the limit grows with the Core level.
 */
public final class CoreTableLogic {
    /** Enchantments in this tag cannot be raised at the table (datapack-configurable). */
    public static final TagKey<Enchantment> BLOCKED = TagKey.create(Registries.ENCHANTMENT,
            ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "table_blocked"));

    /** The enchantment can be raised now. */
    public static final int OK = 0;
    // Reasons that only depend on the item (the client works them out itself).
    public static final int NOT_ON_ITEM = 1;
    public static final int BELOW_MAX = 2;
    public static final int OTHER_OVER = 3;
    // Reasons that depend on the table, the Core and the time.
    public static final int BUSY = 4;
    public static final int NO_CORE = 5;
    public static final int NOT_EVENING = 6;
    public static final int AT_CAP = 7;

    public static final int JOB_IDLE = 0;
    public static final int JOB_WORKING = 1;
    public static final int JOB_DONE = 2;

    /** One enchantment of the list; {@code level} is 0 if it is not on the item. */
    public record Entry(Holder<Enchantment> holder, String key, int level, int vanillaMax) {
    }

    private CoreTableLogic() {
    }

    /** All enchantments the item can have (or has) with a vanilla maximum of at least II, sorted by id. */
    public static List<Entry> entries(ItemStack stack, RegistryAccess access) {
        List<Entry> list = new ArrayList<>();
        if (stack.isEmpty()) {
            return list;
        }
        ItemEnchantments onItem = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        Registry<Enchantment> registry = access.registryOrThrow(Registries.ENCHANTMENT);
        registry.holders().forEach(holder -> {
            Enchantment enchantment = holder.value();
            int vanillaMax = enchantment.getMaxLevel();
            if (vanillaMax < 2 || holder.is(BLOCKED)) {
                return;
            }
            int level = onItem.getLevel(holder);
            if (level <= 0 && !enchantment.isSupportedItem(stack)) {
                return;
            }
            list.add(new Entry(holder, holder.key().location().toString(), level, vanillaMax));
        });
        list.sort(Comparator.comparing(Entry::key));
        return list;
    }

    /** The enchantment that is already above its vanilla maximum, or {@code null}. */
    @Nullable
    public static Entry overEntry(List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry.level() > entry.vanillaMax()) {
                return entry;
            }
        }
        return null;
    }

    /** What the item itself says about this enchantment: {@link #OK}, NOT_ON_ITEM, BELOW_MAX or OTHER_OVER. */
    public static int itemState(Entry entry, @Nullable Entry over) {
        if (entry.level() <= 0) {
            return NOT_ON_ITEM;
        }
        if (entry.level() < entry.vanillaMax()) {
            return BELOW_MAX;
        }
        if (over != null && !over.key().equals(entry.key())) {
            return OTHER_OVER;
        }
        return OK;
    }

    /** Highest level the table may give this enchantment at the given Core level. */
    public static int cap(Entry entry, int coreLevel) {
        int extra = Math.min(Config.tableExtraLevels(coreLevel), Config.tableLimitFor(entry.key()));
        return entry.vanillaMax() + extra;
    }

    /** The full state (server side). */
    public static int state(Entry entry, @Nullable Entry over, boolean busy, @Nullable CoreBlockEntity core,
                            boolean evening) {
        int itemState = itemState(entry, over);
        if (itemState != OK) {
            return itemState;
        }
        if (busy) {
            return BUSY;
        }
        if (core == null) {
            return NO_CORE;
        }
        if (!evening) {
            return NOT_EVENING;
        }
        if (entry.level() >= cap(entry, core.getCoreLevel())) {
            return AT_CAP;
        }
        return OK;
    }

    /** Which level above the vanilla maximum the next raise reaches (1 = the first one). */
    public static int overLevel(Entry entry) {
        return entry.level() + 1 - entry.vanillaMax();
    }

    public static int shardCost(Entry entry) {
        return Config.tableShardCost(overLevel(entry));
    }

    public static int xpCost(Entry entry) {
        return Config.tableXpCost(overLevel(entry));
    }

    public static int durationTicks(Entry entry) {
        return Config.tableDurationTicks(overLevel(entry));
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

    /** Is it evening (by the Overworld clock, like the shard nights)? */
    public static boolean isEvening(MinecraftServer server) {
        return Config.isTableTime(server.overworld().getDayTime());
    }

    /**
     * Pays for the raise (shards from the Core, experience levels from the player; free in creative mode) and starts
     * the job in the table. The caller has checked that the state is {@link #OK}.
     */
    public static boolean tryStart(Player player, Entry entry, CoreBlockEntity core, CoreTableBlockEntity table) {
        boolean free = player.getAbilities().instabuild;
        int shards = shardCost(entry);
        int xp = xpCost(entry);
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
        table.startJob(entry.key(), entry.level() + 1, free ? 200 : durationTicks(entry));
        player.displayClientMessage(Component.translatable("message.incursion.table.started",
                entry.holder().value().description(), roman(entry.level() + 1)).withStyle(ChatFormatting.LIGHT_PURPLE), true);
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
