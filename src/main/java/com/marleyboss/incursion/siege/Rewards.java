package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.block.LootChestBlockEntity;
import com.marleyboss.incursion.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.Iterator;
import java.util.List;

/**
 * Rewards of the siege (v0.9):
 * <ul>
 *     <li>Core shards: every night the Core survives while active pays shards into the Core's balance
 *     (more per Core level). They can be taken out as items and put back by right-clicking the Core.</li>
 *     <li>Statistics: defended nights and killed siege mobs are counted in the Core.</li>
 *     <li>Loot chest: the normal drops of siege mobs go into loot chests near the Core instead of the ground.</li>
 * </ul>
 */
public final class Rewards {
    /** Time of day (ticks) at which the night begins; the same moment monsters start to spawn. */
    private static final long NIGHT_START = 13000L;
    /**
     * A night only counts if the Core saw it begin: it must be active within this many ticks after nightfall
     * (so activating the Core in the middle of the night does not pay out in the morning).
     */
    private static final long ARM_WINDOW = 2400L;
    /** Most shards taken out with one click (a stack is 64). */
    private static final int WITHDRAW_MAX = 64 * 9;

    private Rewards() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(Rewards::onDeath);
        NeoForge.EVENT_BUS.addListener(Rewards::onDrops);
    }

    // ------------------------------------------------------------------
    // Nights and shards
    // ------------------------------------------------------------------

    /**
     * Called once per second for the active Core. At nightfall the night is "armed"; the next morning (or after
     * sleeping through it) the night counts as defended and pays out.
     */
    public static void tickNight(ServerLevel level, BlockPos pos, CoreBlockEntity core) {
        if (!Config.SHARDS_ENABLED.get() || core.getHealth() <= 0) {
            return;
        }
        // Dimensions without a day of their own (Nether, End) use the Overworld clock.
        long time = level.getDayTime();
        long day = Math.floorDiv(time, 24000L);
        long timeOfDay = Math.floorMod(time, 24000L);
        if (timeOfDay >= NIGHT_START) {
            if (core.getNightDay() != day && timeOfDay < NIGHT_START + ARM_WINDOW) {
                core.setNightDay(day);
            }
            return;
        }
        long armed = core.getNightDay();
        if (armed >= 0 && day > armed) {
            core.setNightDay(-1);
            int amount = Config.shardsPerNight(core.getCoreLevel());
            core.addDefendedNight();
            core.addShards(amount);
            level.playSound(null, pos, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.BLOCKS, 1.0F, 1.2F);
            level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                    "message.incursion.core.night_defended", amount, core.getShards(), core.getDefendedNights())
                    .withStyle(ChatFormatting.AQUA), false);
        }
    }

    /** Takes shards out of the Core as items (up to {@link #WITHDRAW_MAX} per click). */
    public static void withdraw(Player player, CoreBlockEntity core) {
        int taken = core.takeShards(WITHDRAW_MAX);
        if (taken <= 0) {
            player.displayClientMessage(Component.translatable("message.incursion.core.no_shards")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        int remaining = taken;
        while (remaining > 0) {
            ItemStack stack = new ItemStack(ModItems.CORE_SHARD.get(), Math.min(remaining, 64));
            remaining -= stack.getCount();
            player.getInventory().add(stack);
            if (!stack.isEmpty()) {
                player.drop(stack, false);
            }
        }
        player.displayClientMessage(Component.translatable("message.incursion.core.shards_taken", taken, core.getShards())
                .withStyle(ChatFormatting.AQUA), true);
    }

    /** Puts the held shards into the Core's balance. */
    public static void deposit(Player player, CoreBlockEntity core, ItemStack stack) {
        int amount = stack.getCount();
        if (amount <= 0) {
            return;
        }
        stack.shrink(amount);
        core.addShards(amount);
        player.displayClientMessage(Component.translatable("message.incursion.core.shards_stored", amount, core.getShards())
                .withStyle(ChatFormatting.AQUA), true);
    }

    /** An inactive Core that is removed gives its shards back as items (an active one explodes and loses them). */
    public static void dropShards(ServerLevel level, BlockPos pos, CoreBlockEntity core) {
        int remaining = core.takeShards(Integer.MAX_VALUE);
        while (remaining > 0) {
            ItemStack stack = new ItemStack(ModItems.CORE_SHARD.get(), Math.min(remaining, 64));
            remaining -= stack.getCount();
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        }
    }

    // ------------------------------------------------------------------
    // Siege mob deaths: statistics and loot chest
    // ------------------------------------------------------------------

    private static void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || !entity.getTags().contains(SiegeManager.SIEGE_TAG)) {
            return;
        }
        ActiveSiege siege = ActiveSiege.get(level);
        if (siege != null && level.getBlockEntity(siege.corePos) instanceof CoreBlockEntity core) {
            core.addKilledMob();
        }
    }

    /** The normal drops of a siege mob go into the loot chests near the Core; what does not fit falls as usual. */
    private static void onDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (!Config.LOOT_CHEST_ENABLED.get() || !(entity.level() instanceof ServerLevel level)
                || !entity.getTags().contains(SiegeManager.SIEGE_TAG)) {
            return;
        }
        ActiveSiege siege = ActiveSiege.get(level);
        if (siege == null) {
            return;
        }
        List<LootChestBlockEntity> chests = LootChestBlockEntity.near(level, siege.corePos, Config.LOOT_CHEST_RANGE.get());
        if (chests.isEmpty()) {
            lootDebug(level, siege, entity, event.getDrops().size(), 0,
                    "no loot chest within " + Config.LOOT_CHEST_RANGE.get() + " blocks of the Core");
            return;
        }
        int total = event.getDrops().size();
        int stored = 0;
        Iterator<ItemEntity> drops = event.getDrops().iterator();
        while (drops.hasNext()) {
            ItemEntity drop = drops.next();
            ItemStack rest = drop.getItem().copy();
            for (LootChestBlockEntity chest : chests) {
                rest = chest.insert(rest);
                if (rest.isEmpty()) {
                    break;
                }
            }
            if (rest.isEmpty()) {
                drops.remove();
                stored++;
            } else {
                drop.setItem(rest);
            }
        }
        lootDebug(level, siege, entity, total, stored, stored < total ? "chest full, the rest falls to the ground" : "");
    }

    /** With the AI view on, every siege mob death says what happened to its drops (to find out why a chest stays empty). */
    private static void lootDebug(ServerLevel level, ActiveSiege siege, LivingEntity entity, int total, int stored,
                                  String note) {
        if (!siege.showDebug) {
            return;
        }
        String text = "[Loot] " + entity.getType().getDescription().getString() + ": " + total + " drop stacks, "
                + stored + " into the chest" + (note.isEmpty() ? "" : " (" + note + ")");
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal(text).withStyle(ChatFormatting.DARK_GRAY), false);
    }
}
