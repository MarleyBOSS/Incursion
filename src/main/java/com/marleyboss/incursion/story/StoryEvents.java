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
        NeoForge.EVENT_BUS.addListener(StoryEvents::onTrade);
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
        VillagerProfession profession = villager.getVillagerData().getProfession();
        int level = villager.getVillagerData().getLevel();
        if (StoryVillagers.isStone(villager)) {
            cancel(event);
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
                event.getEntity().displayClientMessage(Component.translatable("message.incursion.villager.stone")
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return;
        }
        StoryVillagers.Keeper keeper = StoryVillagers.keeperOf(villager);
        if (keeper != null) {
            int needed = switch (keeper) {
                case RUNE -> Config.RUNE_TRADE_LEVEL.get();
                case GLOW -> Config.POTION_TRADE_LEVEL.get();
                case SMITH -> Config.TOOL_FORGE_LEVEL.get();
            };
            boolean runeGone = keeper == StoryVillagers.Keeper.RUNE
                    && CoreWorldData.get(villager.getServer()).isRuneTraded();
            if (!runeGone && StoryVillagers.meet((ServerLevel) villager.level(), villager, event.getEntity(), keeper,
                    level >= needed)) {
                cancel(event); // the first meeting: he speaks instead of trading
                return;
            }
        }
        if (profession == VillagerProfession.TOOLSMITH && level >= Config.TOOL_FORGE_LEVEL.get()
                && tryForge(event, villager)) {
            return;
        }
        MerchantOffers offers = villager.getOffers(); // creates the vanilla offers first if there are none yet
        String hint = null;
        if (profession == VillagerProfession.LIBRARIAN) {
            if (CoreWorldData.get(villager.getServer()).isRuneTraded()) {
                // Sold once in this world already: no librarian offers it again until a Core is destroyed.
                offers.removeIf(offer -> offer.getResult().is(ModItems.CORE_RUNE.get()));
            } else if (isRuneKeeper(villager)) {
                if (level >= Config.RUNE_TRADE_LEVEL.get()) {
                    ensure(offers, new MerchantOffer(new ItemCost(Items.EMERALD, Config.RUNE_TRADE_EMERALDS.get()),
                            Optional.of(new ItemCost(Items.BOOK, 1)), new ItemStack(ModItems.CORE_RUNE.get()),
                            1, 30, 0.05F));
                } else {
                    hint = "message.incursion.villager.hint.librarian";
                }
            }
        } else if (profession == VillagerProfession.CLERIC) {
            if (isPotionKeeper(villager)) {
                if (level >= Config.POTION_TRADE_LEVEL.get()) {
                    ensure(offers, new MerchantOffer(new ItemCost(Items.EMERALD, Config.POTION_TRADE_EMERALDS.get()),
                            Optional.of(new ItemCost(Items.BLAZE_ROD, 1)), new ItemStack(ModItems.MYTHIC_POTION.get()),
                            3, 30, 0.05F));
                } else {
                    hint = "message.incursion.villager.hint.cleric";
                }
            }
        } else if (profession == VillagerProfession.TOOLSMITH) {
            // The tools are not traded any more (v0.17.0-v0.17.4 added rows): he forges them, see tryForge.
            offers.removeIf(offer -> offer.getResult().getItem() instanceof UpgradeToolItem);
            boolean carries = holdsStoryItem(event.getEntity());
            if (level < Config.TOOL_FORGE_LEVEL.get()) {
                hint = "message.incursion.villager.hint.toolsmith";
            } else if (carries) {
                hint = "message.incursion.villager.hint.forge";
            }
        }
        if (hint != null && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
            // Not yet trusted enough: a hint that it is worth to level this villager up.
            event.getEntity().displayClientMessage(Component.translatable(hint)
                    .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC), true);
        }
    }

    private static void cancel(PlayerInteractEvent.EntityInteract event) {
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
    }

    private static boolean holdsStoryItem(Player player) {
        for (ItemStack stack : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
            if (stack.getItem() instanceof UpgradeToolItem || stack.getItem() instanceof InsigniaItem) {
                return true;
            }
        }
        return false;
    }

    /**
     * Upgrade Tool in one hand and the insignia of the same stage in the other (tool I + insignia I and so on):
     * the Master Toolsmith forges them into the next tool on his smithing table. The material for the tier is taken
     * from the inventory. Returns true if the interaction was used for this (no trading screen).
     */
    private static boolean tryForge(PlayerInteractEvent.EntityInteract event, Villager villager) {
        Player player = event.getEntity();
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        ItemStack toolStack = main.getItem() instanceof UpgradeToolItem ? main
                : off.getItem() instanceof UpgradeToolItem ? off : ItemStack.EMPTY;
        ItemStack insigniaStack = main.getItem() instanceof InsigniaItem ? main
                : off.getItem() instanceof InsigniaItem ? off : ItemStack.EMPTY;
        if (toolStack.isEmpty() || insigniaStack.isEmpty()) {
            return false; // normal trading (a hint follows if he holds one of the two)
        }
        cancel(event);
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return true;
        }
        int tier = ((UpgradeToolItem) toolStack.getItem()).getTier();
        int stage = ((InsigniaItem) insigniaStack.getItem()).getStage();
        if (tier >= Config.UPGRADE_TOOL_MAX_TIER) {
            player.displayClientMessage(Component.translatable("message.incursion.villager.forge_max")
                    .withStyle(ChatFormatting.GRAY), true);
            return true;
        }
        if (stage != tier) {
            player.displayClientMessage(Component.translatable("message.incursion.villager.forge_mismatch",
                    ModItems.insignia(tier).get().getDescription()).withStyle(ChatFormatting.RED), true);
            return true;
        }
        ItemStack material = Config.toolForgeMaterial(tier - 1);
        boolean creative = player.getAbilities().instabuild;
        if (material != null && !creative && player.getInventory().countItem(material.getItem()) < material.getCount()) {
            player.displayClientMessage(Component.translatable("message.incursion.villager.forge_cost",
                    material.getCount(), material.getHoverName()).withStyle(ChatFormatting.RED), true);
            return true;
        }
        ItemStack toolCopy = toolStack.copyWithCount(1);
        ItemStack insigniaCopy = insigniaStack.copyWithCount(1);
        if (!creative) {
            if (material != null) {
                removeItems(player, material.getItem(), material.getCount());
            }
            toolStack.shrink(1);
            insigniaStack.shrink(1);
        }
        VillagerWorks.start((ServerLevel) villager.level(), villager, player, VillagerWorks.Kind.FORGE,
                java.util.List.of(toolCopy, insigniaCopy), new ItemStack(ModItems.upgradeTool(tier + 1).get()), false);
        player.displayClientMessage(Component.translatable("message.incursion.villager.forge_start")
                .withStyle(ChatFormatting.GOLD), true);
        return true;
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
        if (StoryVillagers.isStone(villager)) {
            StoryVillagers.tickStone(level, villager);
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

    /** The Core Rune was bought: from now on no villager in this world sells it (until a Core is destroyed). */
    private static void onTrade(net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent event) {
        if (event.getEntity().level().isClientSide()
                || !(event.getAbstractVillager() instanceof Villager villager)
                || !(villager.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack bought = event.getMerchantOffer().getResult();
        if (bought.is(ModItems.CORE_RUNE.get())) {
            event.getMerchantOffer().setToOutOfStock();
            CoreWorldData.get(level.getServer()).setRuneTraded(true);
            // He writes the rune at his lectern once the trading screen is closed.
            VillagerWorks.start(level, villager, event.getEntity(), VillagerWorks.Kind.SCRIBE,
                    java.util.List.of(new ItemStack(Items.BOOK)), bought.copyWithCount(1), true);
        } else if (bought.is(ModItems.MYTHIC_POTION.get())) {
            // He brews the potion at his brewing stand.
            VillagerWorks.start(level, villager, event.getEntity(), VillagerWorks.Kind.BREW,
                    java.util.List.of(new ItemStack(Items.GLASS_BOTTLE)), bought.copyWithCount(1), true);
        }
    }

    /** Adds {@code offer} unless the villager already sells its result. */
    private static void ensure(MerchantOffers offers, MerchantOffer offer) {
        Item result = offer.getResult().getItem();
        for (MerchantOffer existing : offers) {
            if (existing.getResult().is(result)) {
                return;
            }
        }
        offers.add(offer);
    }
}
