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
        NeoForge.EVENT_BUS.addListener(StoryEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(StoryEvents::onVillagerDrops);
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
        if (StoryReady.has(villager) && StoryReady.isOwner(villager, event.getEntity().getUUID())) {
            // he made something for this player: the pickup window instead of trading
            cancel(event);
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                    && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                openPickup(player, villager);
            }
            return;
        }
        if (VillagerWorks.isBusy(villager)) {
            // He is making something right now (for somebody else): no trading until he is done.
            cancel(event);
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
                event.getEntity().displayClientMessage(Component.translatable("message.incursion.villager.busy")
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return;
        }
        StoryVillagers.Keeper named = StoryVillagers.keeperOf(villager);
        if (named != null) {
            StoryVillagers.refreshName(villager, named);
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
    static int neededLevel(StoryVillagers.Keeper keeper) {
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
        if (keeper == null || villager.getVillagerData().getLevel() < neededLevel(keeper) || StoryReady.has(villager)) {
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

    /** Whether {@code player} is still close to his keeper and the keeper is in a state to talk (menus ask this). */
    public static boolean stillAround(net.minecraft.world.entity.player.Player player, int villagerId) {
        return player.level().getEntity(villagerId) instanceof Villager villager && villager.isAlive()
                && villager.distanceToSqr(player) <= 10.0 * 10.0;
    }

    private static final java.util.List<Runnable> LATER = new java.util.ArrayList<>();

    /** Closes {@code menu} for the player after the click that ordered has been handled completely. */
    public static void closeNextTick(net.minecraft.server.level.ServerPlayer player,
                                     net.minecraft.world.inventory.AbstractContainerMenu menu) {
        LATER.add(() -> {
            if (player.containerMenu == menu) {
                player.closeContainer();
            }
        });
    }

    private static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!LATER.isEmpty()) {
            java.util.List<Runnable> now = new java.util.ArrayList<>(LATER);
            LATER.clear();
            now.forEach(Runnable::run);
        }
    }

    /**
     * The player orders {@code quantity} times {@code offer} in the mod's trading menu (the payment is taken by the menu
     * afterwards when this returns true). Checked again here: the keeper must be near, not busy or down, hold nothing
     * finished, and still offer it.
     */
    public static boolean beginOrder(net.minecraft.server.level.ServerPlayer player, int villagerId, StoryOffer offer, int quantity) {
        if (!(player.level().getEntity(villagerId) instanceof Villager villager) || !villager.isAlive()
                || villager.distanceToSqr(player) > 10.0 * 10.0 || !(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (VillagerWorks.isBusy(villager) || StoryVillagers.isDown(villager) || StoryReady.has(villager)) {
            player.displayClientMessage(Component.translatable("message.incursion.villager.busy")
                    .withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        boolean offered = false;
        for (StoryOffer candidate : storyOffers(villager, player)) {
            offered |= candidate.key().equals(offer.key());
        }
        if (!offered) {
            return false;
        }
        ItemStack result = offer.result().copyWithCount(quantity);
        VillagerWorks.Kind kind;
        java.util.List<ItemStack> props;
        switch (offer.key()) {
            case "rune" -> {
                CoreWorldData.get(level.getServer()).setRuneTraded(true); // once per world (until a Core is destroyed)
                kind = VillagerWorks.Kind.SCRIBE;
                props = java.util.List.of(new ItemStack(Items.BOOK));
            }
            case "potion" -> {
                kind = VillagerWorks.Kind.BREW;
                props = java.util.List.of(new ItemStack(Items.GLASS_BOTTLE));
            }
            default -> {
                kind = VillagerWorks.Kind.FORGE;
                props = java.util.List.of(offer.costs().get(0).copyWithCount(1), offer.costs().get(1).copyWithCount(1));
            }
        }
        long seconds = craftSeconds(offer.key(), quantity, player.getAbilities().instabuild);
        StoryReady.begin(villager, player.getUUID(), result, props, kind.key(), level.getGameTime() + seconds * 20L);
        VillagerWorks.start(level, villager, kind, props);
        villager.playSound(SoundEvents.VILLAGER_YES, 1.0F, 1.0F);
        player.displayClientMessage(Component.translatable("message.incursion.villager.order_started")
                .withStyle(ChatFormatting.GOLD), true);
        return true;
    }

    /**
     * How long the villager works on {@code quantity} of the commission {@code key}: the first takes the full time, every
     * further one {@code batchTimeFactor} of it (a little less than ordering them one by one). Creative: quick.
     */
    public static int craftSeconds(String key, int quantity, boolean creative) {
        int base = switch (key) {
            case "rune" -> Config.RUNE_CRAFT_SECONDS.get();
            case "potion" -> Config.POTION_CRAFT_SECONDS.get();
            default -> Config.FORGE_CRAFT_SECONDS.get();
        };
        int seconds = (int) Math.round(base + Math.max(0, quantity - 1) * base * Config.BATCH_TIME_FACTOR.get());
        return creative ? Math.min(seconds, 8) : seconds;
    }

    /** The icon beside the vanilla trading screen was clicked: the mod's trading menu replaces that screen. */
    static void openTrade(net.minecraft.server.level.ServerPlayer player, int villagerId, int select) {
        if (!(player.level().getEntity(villagerId) instanceof Villager villager) || !villager.isAlive()
                || villager.distanceToSqr(player) > 10.0 * 10.0 || VillagerWorks.isBusy(villager)
                || StoryVillagers.isDown(villager) || StoryReady.has(villager)) {
            return;
        }
        java.util.List<StoryOffer> offers = storyOffers(villager, player);
        if (offers.isEmpty()) {
            return;
        }
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inventory, p) -> new com.marleyboss.incursion.menu.StoryTradeMenu(id, inventory, villagerId, offers,
                        select),
                Component.translatable("gui.incursion.story_trade.title")),
                buf -> com.marleyboss.incursion.menu.StoryTradeMenu.write(buf, villagerId, select, offers));
    }

    /** The player talks to a keeper who works on / has finished something for him: the pickup window opens. */
    private static void openPickup(net.minecraft.server.level.ServerPlayer player, Villager villager) {
        StoryVillagers.Keeper keeper = StoryVillagers.keeperOf(villager);
        if (keeper == null || StoryReady.items(villager).isEmpty()) {
            StoryReady.clear(villager); // nothing readable: forget it
            return;
        }
        int id = villager.getId();
        String working = "message.incursion.working." + keeper.key + "." + (1 + villager.getRandom().nextInt(WORKING_VARIANTS));
        String ready = "message.incursion.ready." + keeper.key + "." + (1 + villager.getRandom().nextInt(READY_VARIANTS));
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (containerId, inventory, p) -> new com.marleyboss.incursion.menu.StoryPickupMenu(containerId, inventory, id,
                        working, ready),
                villager.getDisplayName()),
                buf -> com.marleyboss.incursion.menu.StoryPickupMenu.write(buf, id, working, ready));
    }

    private static final int WORKING_VARIANTS = 4;
    private static final int READY_VARIANTS = 5;

    /** Seconds and permille of the work on the order (for the progress bar), {0, 0} if there is none. */
    public static int[] workProgress(net.minecraft.world.entity.player.Player player, int villagerId) {
        if (!(player.level().getEntity(villagerId) instanceof Villager villager) || !StoryReady.has(villager)) {
            return new int[] {0, 0};
        }
        long now = villager.level().getGameTime();
        long start = StoryReady.start(villager);
        long end = StoryReady.end(villager);
        if (now >= end) {
            return new int[] {0, 1000};
        }
        int permille = (int) Math.max(0, Math.min(999, (now - start) * 1000 / Math.max(1, end - start)));
        return new int[] {(int) ((end - now + 19) / 20), permille};
    }

    /** The finished items of the order, for the pickup window (empty while the work is not over). */
    public static java.util.List<ItemStack> finishedItems(net.minecraft.server.level.ServerPlayer player, int villagerId) {
        if (player.level().getEntity(villagerId) instanceof Villager villager && StoryReady.done(villager)
                && StoryReady.isOwner(villager, player.getUUID())) {
            return StoryReady.items(villager);
        }
        return java.util.List.of();
    }

    /** The owner took the items: what does not fit into the inventory stays with the villager. */
    public static boolean takeFinished(net.minecraft.server.level.ServerPlayer player, int villagerId) {
        if (!(player.level().getEntity(villagerId) instanceof Villager villager) || !StoryReady.done(villager)
                || !StoryReady.isOwner(villager, player.getUUID())) {
            return false;
        }
        java.util.List<ItemStack> rest = new java.util.ArrayList<>();
        for (ItemStack stack : StoryReady.items(villager)) {
            ItemStack copy = stack.copy();
            player.getInventory().add(copy);
            if (!copy.isEmpty()) {
                rest.add(copy);
            }
        }
        StoryReady.setRest(villager, rest);
        if (rest.isEmpty()) {
            villager.playSound(SoundEvents.VILLAGER_CELEBRATE, 1.0F, 1.0F);
            player.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.7F, 1.0F);
            return true;
        }
        player.displayClientMessage(Component.translatable("message.incursion.villager.inventory_full")
                .withStyle(ChatFormatting.RED), true);
        return false;
    }

    /** A keeper who dies drops what he was making for his player. */
    private static void onVillagerDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)
                || !StoryReady.has(villager)) {
            return;
        }
        java.util.UUID owner = StoryReady.owner(villager);
        for (ItemStack stack : StoryReady.items(villager)) {
            ItemEntity item = new ItemEntity(level, villager.getX(), villager.getY() + 0.5, villager.getZ(), stack.copy());
            item.setUnlimitedLifetime();
            item.setInvulnerable(true);
            item.setGlowingTag(true);
            if (owner != null) {
                item.setTarget(owner);
            }
            event.getDrops().add(item);
        }
        StoryReady.clear(villager);
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
        StoryVillagers.Keeper keeper = StoryVillagers.keeperOf(villager);
        if (keeper != null) {
            StoryVillagers.refreshName(villager, keeper);
        }
        if (StoryReady.has(villager)) {
            if (StoryReady.done(villager)) {
                // something is finished for the player: this keeper shines until it is taken
                level.sendParticles(ParticleTypes.GLOW, villager.getX(), villager.getY() + villager.getBbHeight() + 0.4,
                        villager.getZ(), 6, 0.3, 0.2, 0.3, 0.02);
                level.sendParticles(ParticleTypes.END_ROD, villager.getX(), villager.getY() + villager.getBbHeight() + 0.6,
                        villager.getZ(), 2, 0.1, 0.1, 0.1, 0.03);
            } else if (!VillagerWorks.isBusy(villager)) {
                // he was unloaded while working: he takes up the work again
                VillagerWorks.Kind kind = VillagerWorks.Kind.fromKey(StoryReady.kind(villager));
                if (kind != null) {
                    VillagerWorks.start(level, villager, kind, StoryReady.props(villager));
                }
            }
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
