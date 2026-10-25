package com.marleyboss.incursion.block;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.data.CoreWorldData;
import com.marleyboss.incursion.item.InsigniaItem;
import com.marleyboss.incursion.item.TabletItem;
import com.marleyboss.incursion.item.UpgradeToolItem;
import com.marleyboss.incursion.registry.ModItems;
import com.marleyboss.incursion.siege.ChunkLoading;
import com.marleyboss.incursion.siege.Generals;
import com.marleyboss.incursion.siege.Shields;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Activation and upgrades, shared by right-clicking with an item and by the Core GUI. */
public final class CoreActions {
    private CoreActions() {
    }

    /**
     * The ritual succeeded (a Mythic Potion landed on a ready Sarcophagus): the Core is created and activated at
     * level 1. Returns false if it could not be activated (another Core is active in this world); the caller then
     * gives the potion back. {@code player} (the thrower) may be null.
     */
    public static boolean activateByRitual(ServerLevel level, BlockPos pos, @Nullable Player player) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CoreBlock) || state.getValue(CoreBlock.ACTIVE) || !Ritual.isReady(state)
                || !(level.getBlockEntity(pos) instanceof CoreBlockEntity core)) {
            return false;
        }
        CoreWorldData data = CoreWorldData.get(level.getServer());
        BlockPos other = data.findOtherActiveCore(level.getServer(), level.dimension(), pos);
        if (other != null) {
            if (player != null) {
                player.displayClientMessage(Component.translatable("message.incursion.core.another_active",
                        other.getX(), other.getY(), other.getZ()).withStyle(ChatFormatting.RED), false);
            }
            return false;
        }

        data.setActiveCore(level.dimension(), pos);
        core.setCoreLevel(1);
        core.resetShields();
        core.setNightDay(-1);
        core.clearPendingUpgrade();
        core.setHealth(core.getMaxHealth());
        level.setBlock(pos, state.setValue(CoreBlock.ACTIVE, true), Block.UPDATE_ALL);
        core.evolve(level, CoreEvolution.stageFor(1));
        int radius = Config.siegeChunkRadius(1);
        ChunkLoading.update(level, pos, core.getLoadedChunkRadius(), radius);
        core.setLoadedChunkRadius(radius);
        level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 0.6F);
        // The chain reaction: lightning strikes the Sarcophagus (drawn by the block renderer; the vanilla bolt entity
        // would hide the block behind its depth for a moment).
        level.playSound(null, pos, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 6.0F, 0.9F);
        level.playSound(null, pos, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 2.0F, 1.0F);
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.incursion.core.activated")
                    .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
        }
        return true;
    }

    /**
     * Upgrades the Core by one level. {@code heldTool} is the stack the player right-clicked with; when an Upgrade
     * Tool is required it must be an Upgrade Tool of a high enough tier (the GUI button passes an empty stack, so
     * it cannot upgrade in that case).
     */
    public static void tryUpgrade(ServerLevel level, BlockPos pos, Player player, ItemStack heldTool) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CoreBlock) || !state.getValue(CoreBlock.ACTIVE)
                || !(level.getBlockEntity(pos) instanceof CoreBlockEntity core)) {
            return;
        }
        int currentLevel = core.getCoreLevel();
        int maxLevel = Config.MAX_LEVEL.get();
        if (currentLevel >= maxLevel) {
            player.displayClientMessage(Component.translatable("message.incursion.core.max_level", maxLevel)
                    .withStyle(ChatFormatting.GOLD), true);
            return;
        }

        if (core.hasPendingUpgrade()) {
            // A tablet is set in already: the upgrade waits for the general's insignia.
            player.displayClientMessage(Generals.pendingMessage(core).withStyle(ChatFormatting.GOLD), true);
            return;
        }

        int targetLevel = currentLevel + 1;
        int neededTier = Config.requiredToolTier(targetLevel);
        if (neededTier > 0 && toolTier(heldTool) < neededTier) {
            player.displayClientMessage(Component.translatable("message.incursion.core.need_tool",
                    ModItems.upgradeTool(neededTier).get().getDescription(), targetLevel)
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        // Level 5, 10, 15, 20: the tablet of the stage's guardian goes in with the upgrade (in the other hand).
        int gate = tabletStage(currentLevel, targetLevel);
        ItemStack tablet = ItemStack.EMPTY;
        if (gate > 0) {
            tablet = findTablet(player, gate);
            if (tablet.isEmpty()) {
                player.displayClientMessage(Component.translatable("message.incursion.core.need_tablet",
                        ModItems.tablet(gate).get().getDescription(), targetLevel).withStyle(ChatFormatting.RED), true);
                return;
            }
        }
        int cost = Config.upgradeCost(targetLevel);
        Item upgradeItem = Config.upgradeItem();
        if (countItem(player, upgradeItem) < cost) {
            player.displayClientMessage(Component.translatable("message.incursion.core.not_enough",
                    cost, upgradeItem.getDescription()).withStyle(ChatFormatting.RED), true);
            return;
        }

        removeItems(player, upgradeItem, cost);
        if (gate > 0) {
            if (!player.getAbilities().instabuild) {
                tablet.shrink(1);
            }
            if (Config.BOSS_NIGHT.get()) {
                Generals.startBossNight(level, pos, core, player, targetLevel);
                return;
            }
            level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 0.7F);
        }
        levelUp(level, pos, core, player, currentLevel, targetLevel);
    }

    /**
     * The tablet stage (1 to 4) an upgrade from {@code currentLevel} to {@code targetLevel} needs, or 0 if it needs
     * none: the upgrades that start a new stage of the Core (level 5, 10, 15, 20).
     */
    public static int tabletStage(int currentLevel, int targetLevel) {
        if (!Config.TABLETS_REQUIRED.get()) {
            return 0;
        }
        int from = CoreEvolution.stageFor(currentLevel);
        int to = CoreEvolution.stageFor(targetLevel);
        return to > from ? to : 0;
    }

    /** The tablet of {@code stage} in one of the player's hands (the one that is not the tool), or EMPTY. */
    private static ItemStack findTablet(Player player, int stage) {
        for (ItemStack stack : new ItemStack[]{player.getOffhandItem(), player.getMainHandItem()}) {
            if (stack.getItem() instanceof TabletItem tablet && tablet.getStage() == stage) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Right-click with a general's insignia: completes the waiting upgrade (the tablet is in, the general fell).
     */
    public static void insertInsignia(ServerLevel level, BlockPos pos, Player player, ItemStack stack) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CoreBlock) || !state.getValue(CoreBlock.ACTIVE)
                || !(level.getBlockEntity(pos) instanceof CoreBlockEntity core)
                || !(stack.getItem() instanceof InsigniaItem insignia)) {
            return;
        }
        if (!core.hasPendingUpgrade()) {
            player.displayClientMessage(Component.translatable("message.incursion.general.no_pending")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        int pending = core.getPendingLevel();
        int stage = CoreEvolution.stageFor(pending);
        if (insignia.getStage() != stage) {
            player.displayClientMessage(Component.translatable("message.incursion.general.wrong_insignia",
                    ModItems.insignia(stage).get().getDescription()).withStyle(ChatFormatting.RED), true);
            return;
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        int current = core.getCoreLevel();
        Generals.finish(level, pos, core);
        level.playSound(null, pos, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 0.8F, 1.1F);
        level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.incursion.general.insignia_set", insignia.getDescription(), pending)
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        if (pending > current) {
            levelUp(level, pos, core, player, current, pending);
        }
    }

    /** What a real upgrade does once it is paid: the new level, health, sound, message, shields (and the evolution follows from the level). */
    private static void levelUp(ServerLevel level, BlockPos pos, CoreBlockEntity core, Player player, int currentLevel,
                                int targetLevel) {
        int oldMax = core.getMaxHealth();
        core.setCoreLevel(targetLevel);
        // The extra max health of the new level is added on top of the current health.
        core.setHealth(core.getHealth() + (core.getMaxHealth() - oldMax));
        level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0F, 0.8F);
        player.displayClientMessage(Component.translatable("message.incursion.core.upgraded", targetLevel,
                Config.MAX_LEVEL.get()).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        Shields.onLevelUp(level, pos, core, currentLevel, targetLevel, player);
    }

    /**
     * TEST TAB: raises the Core to {@code target} one level at a time through exactly the same code as a real upgrade, only
     * without the costs and the tool. Only for operators and creative players.
     */
    public static void testUpgradeTo(ServerLevel level, BlockPos pos, Player player, int target) {
        if (!(player.hasPermissions(2) || player.getAbilities().instabuild)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CoreBlock) || !state.getValue(CoreBlock.ACTIVE)
                || !(level.getBlockEntity(pos) instanceof CoreBlockEntity core)) {
            return;
        }
        int max = Config.MAX_LEVEL.get();
        target = Math.min(target, max);
        while (core.getCoreLevel() < target) {
            int current = core.getCoreLevel();
            levelUp(level, pos, core, player, current, current + 1);
        }
        if (core.hasPendingUpgrade() && core.getCoreLevel() >= core.getPendingLevel()) {
            Generals.finish(level, pos, core); // the test skipped the waiting upgrade: its general goes too
        }
    }

    // ------------------------------------------------------------------
    // Inventory helpers (creative players pay nothing)
    // ------------------------------------------------------------------

    /** Tier of the Upgrade Tool in {@code stack} (0 = not an Upgrade Tool). */
    public static int toolTier(ItemStack stack) {
        return stack.getItem() instanceof UpgradeToolItem tool ? tool.getTier() : 0;
    }

    public static int countItem(Player player, Item item) {
        if (player.getAbilities().instabuild) {
            return Short.MAX_VALUE;
        }
        return player.getInventory().countItem(item);
    }

    private static void removeItems(Player player, Item item, int amount) {
        if (player.getAbilities().instabuild) {
            return;
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && amount > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) {
                int take = Math.min(amount, stack.getCount());
                stack.shrink(take);
                amount -= take;
            }
        }
        inventory.setChanged();
    }
}
