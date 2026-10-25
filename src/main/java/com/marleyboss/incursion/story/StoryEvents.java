package com.marleyboss.incursion.story;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.data.CoreWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import com.marleyboss.incursion.item.InsigniaItem;
import com.marleyboss.incursion.item.UpgradeToolItem;
import com.marleyboss.incursion.registry.ModItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Optional;

/**
 * Where the story items come from (Story-Updates 1 and 3):
 * <ul>
 *     <li>Tablets: the Elder Guardian, the Warden, the Ender Dragon and the Wither drop the tablet of their stage when
 *     they die (always, one per kill). The item cannot burn, despawn or be blown up and glows.</li>
 *     <li>Villager trades, added when a player opens the trading screen (villagers that existed before get them too):
 *     half of the Master librarians (decided once per villager) sell the Core Rune, but only once per world until a Core is
 *     destroyed; half of the clerics from Apprentice on sell the Mythic Potion; every Toolsmith trades an Upgrade Tool and a material
 *     for the next tier (Journeyman, Expert, Master).</li>
 * </ul>
 */
public final class StoryEvents {
    private StoryEvents() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, StoryEvents::onBossDeath);
        NeoForge.EVENT_BUS.addListener(StoryEvents::onLeaveLevel);
        NeoForge.EVENT_BUS.addListener(StoryEvents::onVillagerInteract);
        NeoForge.EVENT_BUS.addListener(StoryEvents::onVillagerTick);
    }

    // ------------------------------------------------------------------
    // Tablets from the four bosses
    // ------------------------------------------------------------------

    private static int tabletStage(EntityType<?> type) {
        if (type == EntityType.ELDER_GUARDIAN) {
            return 1;
        }
        if (type == EntityType.WARDEN) {
            return 2;
        }
        if (type == EntityType.ENDER_DRAGON) {
            return 3;
        }
        if (type == EntityType.WITHER) {
            return 4;
        }
        return 0;
    }

    /** Set on a boss whose tablet has dropped, so it drops only once. */
    private static final String DROPPED_TAG = "incursion_tablet_dropped";

    private static void onBossDeath(LivingDeathEvent event) {
        if (!event.isCanceled()) {
            dropTablet(event.getEntity());
        }
    }

    /**
     * The Ender Dragon does not always die through the normal death: {@code /kill} and the end of its death animation
     * remove it directly ("killed"). The tablet then drops when it leaves the world.
     */
    private static void onLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof LivingEntity boss && boss.getType() == EntityType.ENDER_DRAGON
                && boss.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.KILLED) {
            dropTablet(boss);
        }
    }

    private static void dropTablet(LivingEntity boss) {
        if (!(boss.level() instanceof ServerLevel level) || !Config.TABLET_DROPS.get()
                || boss.getTags().contains(DROPPED_TAG)) {
            return;
        }
        int stage = tabletStage(boss.getType());
        if (stage == 0) {
            return;
        }
        boss.addTag(DROPPED_TAG);
        double x = boss.getX();
        double y = boss.getY() + boss.getBbHeight() * 0.5;
        double z = boss.getZ();
        if (boss.getType() == EntityType.ENDER_DRAGON && level.dimension() == Level.END) {
            // The dragon dies high above the island: the tablet appears on top of the exit portal instead.
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, BlockPos.ZERO);
            x = 0.5;
            y = top.getY() + 1.0;
            z = 0.5;
        }
        ItemEntity item = new ItemEntity(level, x, y, z, new ItemStack(ModItems.tablet(stage).get()));
        item.setUnlimitedLifetime();
        item.setInvulnerable(true);
        item.setGlowingTag(true);
        level.addFreshEntity(item);
        level.sendParticles(ParticleTypes.END_ROD, x, y, z, 30, 0.4, 0.4, 0.4, 0.05);
        level.playSound(null, BlockPos.containing(x, y, z), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL,
                1.0F, 0.6F);
    }

    // ------------------------------------------------------------------
    // Villager trades
    // ------------------------------------------------------------------

    private static void onVillagerInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !Config.TRADES_ENABLED.get()
                || !(event.getTarget() instanceof Villager villager) || villager.isBaby()) {
            return;
        }
        if (VillagerWorks.isBusy(villager)) {
            // He is making something right now: no trading until he is done.
            cancel(event);
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
                event.getEntity().displayClientMessage(Component.translatable("message.incursion.villager.busy")
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return;
        }
        if (StoryVillagers.isDown(villager)) {
            cancel(event);
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
                event.getEntity().displayClientMessage(Component.translatable("message.incursion.villager.down")
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return;
        }
        // The story items are no vanilla trades (older versions added rows): they are commissioned in the mod's window.
        villager.getOffers().removeIf(offer -> isStoryItem(offer.getResult()));
        if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            sendPanel(player, villager, true);
        }
    }

    private static boolean isStoryItem(ItemStack stack) {
        return stack.is(ModItems.CORE_RUNE.get()) || stack.is(ModItems.MYTHIC_POTION.get())
                || stack.getItem() instanceof UpgradeToolItem;
    }

    /** Trading level a keeper needs for his commission. */
    private static int neededLevel(StoryVillagers.Keeper keeper) {
        return switch (keeper) {
            case RUNE -> Config.RUNE_TRADE_LEVEL.get();
            case GLOW -> Config.POTION_TRADE_LEVEL.get();
            case SMITH -> Config.TOOL_FORGE_LEVEL.get();
        };
    }

    /**
     * Sends what a keeper says and which commissions he takes (panel and icons beside his trading screen). With
     * {@code meet}, a first meeting counts (he gets his name, the first-meeting lines are shown).
     */
    private static void sendPanel(net.minecraft.server.level.ServerPlayer player, Villager villager, boolean meet) {
        StoryVillagers.Keeper keeper = StoryVillagers.keeperOf(villager);
        if (keeper == null) {
            return;
        }
        int level = villager.getVillagerData().getLevel();
        boolean trusted = level >= neededLevel(keeper);
        boolean runeGone = keeper == StoryVillagers.Keeper.RUNE && CoreWorldData.get(villager.getServer()).isRuneTraded();
        boolean first = meet && StoryVillagers.meet((ServerLevel) villager.level(), villager, player, keeper, trusted);
        DialogPayload.send(player, villager.getId(),
                dialogLines(keeper, level, trusted, first, runeGone, villager.getRandom()), storyOffers(villager, player));
    }

    /** The commissions this villager takes from this player right now (only from a keeper on the needed level). */
    static java.util.List<StoryOffer> storyOffers(Villager villager, Player player) {
        java.util.List<StoryOffer> offers = new java.util.ArrayList<>();
        StoryVillagers.Keeper keeper = StoryVillagers.keeperOf(villager);
        if (keeper == null || villager.getVillagerData().getLevel() < neededLevel(keeper)) {
            return offers;
        }
        switch (keeper) {
            case RUNE -> {
                if (!CoreWorldData.get(villager.getServer()).isRuneTraded()) {
                    offers.add(new StoryOffer("rune", java.util.List.of(
                            new ItemStack(Items.EMERALD, Config.RUNE_TRADE_EMERALDS.get()), new ItemStack(Items.BOOK)),
                            new ItemStack(ModItems.CORE_RUNE.get())));
                }
            }
            case GLOW -> offers.add(new StoryOffer("potion", java.util.List.of(
                    new ItemStack(Items.EMERALD, Config.POTION_TRADE_EMERALDS.get()), new ItemStack(Items.BLAZE_ROD)),
                    new ItemStack(ModItems.MYTHIC_POTION.get())));
            case SMITH -> {
                // one commission, for the player's own best tool: it + the insignia of that stage + material = the next
                int tier = bestTool(player);
                if (tier < Config.UPGRADE_TOOL_MAX_TIER) {
                    java.util.List<ItemStack> costs = new java.util.ArrayList<>();
                    costs.add(new ItemStack(ModItems.upgradeTool(tier).get()));
                    costs.add(new ItemStack(ModItems.insignia(tier).get()));
                    ItemStack material = Config.toolForgeMaterial(tier - 1);
                    if (material != null) {
                        costs.add(material);
                    }
                    offers.add(new StoryOffer("forge", costs, new ItemStack(ModItems.upgradeTool(tier + 1).get())));
                }
            }
        }
        return offers;
    }

    /**
     * The player commissions {@code key} in the mod's trading window: the costs are taken from the inventory and the
     * villager makes it at his job site (VillagerWorks). Checked again here: he must still offer it.
     */
    static void order(net.minecraft.server.level.ServerPlayer player, int villagerId, String key) {
        if (!(player.level().getEntity(villagerId) instanceof Villager villager) || !villager.isAlive()
                || villager.distanceToSqr(player) > 10.0 * 10.0 || !(villager.level() instanceof ServerLevel level)) {
            return;
        }
        if (VillagerWorks.isBusy(villager) || StoryVillagers.isDown(villager)) {
            player.displayClientMessage(Component.translatable("message.incursion.villager.busy")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        StoryOffer offer = null;
        for (StoryOffer candidate : storyOffers(villager, player)) {
            if (candidate.key().equals(key)) {
                offer = candidate;
            }
        }
        if (offer == null) {
            return;
        }
        boolean creative = player.getAbilities().instabuild;
        if (!creative) {
            for (ItemStack cost : offer.costs()) {
                if (player.getInventory().countItem(cost.getItem()) < cost.getCount()) {
                    player.displayClientMessage(Component.translatable("message.incursion.villager.forge_cost",
                            cost.getCount(), cost.getHoverName()).withStyle(ChatFormatting.RED), true);
                    return;
                }
            }
            for (ItemStack cost : offer.costs()) {
                removeItems(player, cost.getItem(), cost.getCount());
            }
        }
        ItemStack result = offer.result().copy();
        switch (key) {
            case "rune" -> {
                CoreWorldData.get(level.getServer()).setRuneTraded(true); // once per world (until a Core is destroyed)
                VillagerWorks.start(level, villager, player, VillagerWorks.Kind.SCRIBE,
                        java.util.List.of(new ItemStack(Items.BOOK)), result, false);
            }
            case "potion" -> VillagerWorks.start(level, villager, player, VillagerWorks.Kind.BREW,
                    java.util.List.of(new ItemStack(Items.GLASS_BOTTLE)), result, false);
            default -> VillagerWorks.start(level, villager, player, VillagerWorks.Kind.FORGE,
                    java.util.List.of(offer.costs().get(0).copyWithCount(1), offer.costs().get(1).copyWithCount(1)),
                    result, false);
        }
        villager.playSound(SoundEvents.VILLAGER_YES, 1.0F, 1.0F);
        player.displayClientMessage(Component.translatable("message.incursion.villager.order_started")
                .withStyle(ChatFormatting.GOLD), true);
    }

    /** "Back" from the mod's trading window: his normal trading screen opens again (with panel and icons). */
    static void reopen(net.minecraft.server.level.ServerPlayer player, int villagerId) {
        if (!(player.level().getEntity(villagerId) instanceof Villager villager) || !villager.isAlive()
                || villager.distanceToSqr(player) > 10.0 * 10.0 || villager.isTrading()
                || VillagerWorks.isBusy(villager) || StoryVillagers.isDown(villager)) {
            return;
        }
        villager.getOffers().removeIf(offer -> isStoryItem(offer.getResult()));
        sendPanel(player, villager, false);
        villager.setTradingPlayer(player);
        villager.openTradingScreen(player, villager.getDisplayName(), villager.getVillagerData().getLevel());
    }

    /** Alternative lines per trading level (1 to 5) and keeper; one of them is picked at random. */
    private static final int TALK_VARIANTS = 3;

    /**
     * The lines of the dialog panel: at the first meeting three lines (the last one depends on his trust), later one
     * line that fits his trading level (or, for the rune keeper, that the rune is gone).
     */
    private static java.util.List<String> dialogLines(StoryVillagers.Keeper keeper, int level, boolean trusted,
                                                      boolean first, boolean runeGone,
                                                      net.minecraft.util.RandomSource random) {
        String key = keeper.key;
        if (first && !runeGone) {
            String base = "message.incursion.dialog." + key + ".";
            return java.util.List.of(base + "1", base + "2", base + (trusted ? "trusted" : "wait"));
        }
        if (runeGone) {
            return java.util.List.of("message.incursion.talk.rune.sold." + (1 + random.nextInt(2)));
        }
        int stage = Math.max(1, Math.min(5, level));
        return java.util.List.of("message.incursion.talk." + key + "." + stage + "." + (1 + random.nextInt(TALK_VARIANTS)));
    }

    private static void cancel(PlayerInteractEvent.EntityInteract event) {
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
    }

    /** The highest Upgrade Tool tier in the player's inventory (1 if he has none, so the first step is shown). */
    private static int bestTool(Player player) {
        int best = 1;
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).getItem() instanceof UpgradeToolItem tool) {
                best = Math.max(best, tool.getTier());
            }
        }
        return best;
    }

    private static void removeItems(Player player, Item item, int amount) {
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
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

    /** Librarian that sells the Core Rune once he is a Master (decided once per villager, as soon as he is a librarian). */
    static boolean isRuneKeeper(Villager villager) {
        return rolled(villager, ROLL_RUNE, Config.RUNE_TRADE_CHANCE.get());
    }

    /** Cleric that sells the Mythic Potion once he has the level (decided once per villager). */
    static boolean isPotionKeeper(Villager villager) {
        return rolled(villager, ROLL_POTION, Config.POTION_TRADE_CHANCE.get());
    }

    /**
     * Every 2.5 seconds: villagers that keep something for the story show it, from the moment they have the
     * profession on: the rune keeper is surrounded by the glyphs of the old script (only while the Core Rune can still
     * be bought in this world), the potion keeper by little flames, the toolsmith by sparks.
     */
    private static void onVillagerTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)
                || (villager.tickCount + villager.getId()) % 50 != 0 || villager.isBaby()) {
            return;
        }
        if (StoryVillagers.isDown(villager)) {
            StoryVillagers.tickDown(level, villager);
            return;
        }
        if (!Config.TRADES_ENABLED.get()) {
            return;
        }
        VillagerProfession profession = villager.getVillagerData().getProfession();
        double x = villager.getX();
        double y = villager.getY() + villager.getBbHeight() + 0.2;
        double z = villager.getZ();
        if (profession == VillagerProfession.LIBRARIAN) {
            if (isRuneKeeper(villager) && !CoreWorldData.get(level.getServer()).isRuneTraded()) {
                level.sendParticles(ParticleTypes.ENCHANT, x, y, z, 8, 0.5, 0.4, 0.5, 0.6);
            }
        } else if (profession == VillagerProfession.CLERIC) {
            if (isPotionKeeper(villager)) {
                level.sendParticles(ParticleTypes.SMALL_FLAME, x, y - 0.4, z, 3, 0.35, 0.3, 0.35, 0.01);
            }
        } else if (profession == VillagerProfession.TOOLSMITH) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y - 0.6, z, 3, 0.35, 0.3, 0.35, 0.05);
        }
    }

    private static final String ROLL_RUNE = "IncursionRuneRoll";
    private static final String ROLL_POTION = "IncursionPotionRoll";

    /**
     * Whether this villager offers the story item: decided once per villager with {@code chance} and kept in the
     * villager (1 = yes, 2 = no), so opening the trade screen again does not roll again.
     */
    private static boolean rolled(Villager villager, String key, double chance) {
        net.minecraft.nbt.CompoundTag data = villager.getPersistentData();
        if (!data.contains(key)) {
            data.putInt(key, villager.getRandom().nextDouble() < chance ? 1 : 2);
        }
        return data.getInt(key) == 1;
    }
}
