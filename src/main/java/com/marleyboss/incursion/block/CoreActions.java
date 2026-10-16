package com.marleyboss.incursion.block;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.data.CoreWorldData;
import com.marleyboss.incursion.siege.ChunkLoading;
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

/** Activation and upgrades, shared by right-clicking with an item and by the Core GUI. */
public final class CoreActions {
    private CoreActions() {
    }

    public static void tryActivate(ServerLevel level, BlockPos pos, Player player) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CoreBlock) || state.getValue(CoreBlock.ACTIVE)
                || !(level.getBlockEntity(pos) instanceof CoreBlockEntity core)) {
            return;
        }
        Item activationItem = Config.activationItem();
        if (countItem(player, activationItem) < 1) {
            player.displayClientMessage(Component.translatable("message.incursion.core.need_activation_item",
                    activationItem.getDescription()).withStyle(ChatFormatting.RED), true);
            return;
        }
        CoreWorldData data = CoreWorldData.get(level.getServer());
        BlockPos other = data.findOtherActiveCore(level.getServer(), level.dimension(), pos);
        if (other != null) {
            player.displayClientMessage(Component.translatable("message.incursion.core.another_active",
                    other.getX(), other.getY(), other.getZ()).withStyle(ChatFormatting.RED), false);
            return;
        }

        removeItems(player, activationItem, 1);
        data.setActiveCore(level.dimension(), pos);
        core.setCoreLevel(1);
        core.resetShields();
        core.setNightDay(-1);
        core.setHealth(core.getMaxHealth());
        level.setBlock(pos, state.setValue(CoreBlock.ACTIVE, true), Block.UPDATE_ALL);
        int radius = Config.siegeChunkRadius(1);
        ChunkLoading.update(level, pos, core.getLoadedChunkRadius(), radius);
        core.setLoadedChunkRadius(radius);
        level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 0.6F);
        player.displayClientMessage(Component.translatable("message.incursion.core.activated")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
    }

    public static void tryUpgrade(ServerLevel level, BlockPos pos, Player player) {
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

        int targetLevel = currentLevel + 1;
        int cost = Config.upgradeCost(targetLevel);
        Item upgradeItem = Config.upgradeItem();
        if (countItem(player, upgradeItem) < cost) {
            player.displayClientMessage(Component.translatable("message.incursion.core.not_enough",
                    cost, upgradeItem.getDescription()).withStyle(ChatFormatting.RED), true);
            return;
        }

        removeItems(player, upgradeItem, cost);
        int oldMax = core.getMaxHealth();
        core.setCoreLevel(targetLevel);
        // The extra max health of the new level is added on top of the current health.
        core.setHealth(core.getHealth() + (core.getMaxHealth() - oldMax));
        level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0F, 0.8F);
        player.displayClientMessage(Component.translatable("message.incursion.core.upgraded", targetLevel, maxLevel)
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        Shields.onLevelUp(level, pos, core, currentLevel, targetLevel, player);
    }

    // ------------------------------------------------------------------
    // Inventory helpers (creative players pay nothing)
    // ------------------------------------------------------------------

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
