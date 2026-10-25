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
        if (StoryVillagers.isDown(villager)) {
            cancel(event);
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
                event.getEntity().displayClientMessage(Component.translatable("message.incursion.villager.down")
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
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                    && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                // What he says, in the panel beside his trading screen.
                boolean runeGone = keeper == StoryVillagers.Keeper.RUNE
                        && CoreWorldData.get(villager.getServer()).isRuneTraded();
                boolean first = StoryVillagers.meet((ServerLevel) villager.level(), villager, player, keeper,
                        level >= needed);
                DialogPayload.send(player, villager.getId(),
                        dialogLines(keeper, level, level >= needed, first, runeGone, villager.getRandom()));
            }
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
                }
            }
        } else if (profession == VillagerProfession.CLERIC) {
            if (isPotionKeeper(villager)) {
                if (level >= Config.POTION_TRADE_LEVEL.get()) {
                    ensure(offers, new MerchantOffer(new ItemCost(Items.EMERALD, Config.POTION_TRADE_EMERALDS.get()),
                            Optional.of(new ItemCost(Items.BLAZE_ROD, 1)), new ItemStack(ModItems.MYTHIC_POTION.get()),
                            3, 30, 0.05F));
                }
            }
        } else if (profession == VillagerProfession.TOOLSMITH) {
            // One row only, for the player's own tool: his best Upgrade Tool + the insignia of that stage = the next
            // tool (rebuilt every time the screen is opened; the material is taken from the inventory, see onTrade).
            offers.removeIf(offer -> offer.getResult().getItem() instanceof UpgradeToolItem);
            if (level >= Config.TOOL_FORGE_LEVEL.get()) {
                int tier = bestTool(event.getEntity());
                if (tier < Config.UPGRADE_TOOL_MAX_TIER) {
                    offers.add(new MerchantOffer(new ItemCost(ModItems.upgradeTool(tier).get(), 1),
                            Optional.of(new ItemCost(ModItems.insignia(tier).get(), 1)),
                            new ItemStack(ModItems.upgradeTool(tier + 1).get()), 1, 0, 0.0F));
                }
            }
        }
        if (hint != null && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
            event.getEntity().displayClientMessage(Component.translatable(hint)
                    .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC), true);
        }
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
        } else if (bought.getItem() instanceof UpgradeToolItem forged) {
            // Tool + insignia were paid in the screen; the material comes from the inventory. Without it he gives
            // both back (and the new tool is taken back).
            int tier = forged.getTier() - 1;
            Player player = event.getEntity();
            ItemStack material = Config.toolForgeMaterial(tier - 1);
            boolean creative = player.getAbilities().instabuild;
            ItemStack tool = new ItemStack(ModItems.upgradeTool(tier).get());
            ItemStack insignia = new ItemStack(ModItems.insignia(tier).get());
            if (material != null && !creative && player.getInventory().countItem(material.getItem()) < material.getCount()) {
                VillagerWorks.refund(level, player, bought.copyWithCount(1), java.util.List.of(tool, insignia));
                player.displayClientMessage(Component.translatable("message.incursion.villager.forge_cost",
                        material.getCount(), material.getHoverName()).withStyle(ChatFormatting.RED), true);
                return;
            }
            if (material != null && !creative) {
                removeItems(player, material.getItem(), material.getCount());
            }
            // He forges it at his smithing table.
            VillagerWorks.start(level, villager, player, VillagerWorks.Kind.FORGE,
                    java.util.List.of(tool, insignia), bought.copyWithCount(1), true);
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
