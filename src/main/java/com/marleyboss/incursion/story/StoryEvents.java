package com.marleyboss.incursion.story;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.data.CoreWorldData;
import com.marleyboss.incursion.registry.ModItems;
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
        VillagerProfession profession = villager.getVillagerData().getProfession();
        int level = villager.getVillagerData().getLevel();
        MerchantOffers offers = villager.getOffers(); // creates the vanilla offers first if there are none yet
        if (profession == VillagerProfession.LIBRARIAN) {
            if (CoreWorldData.get(villager.getServer()).isRuneTraded()) {
                // Sold once in this world already: no librarian offers it again until a Core is destroyed.
                offers.removeIf(offer -> offer.getResult().is(ModItems.CORE_RUNE.get()));
            } else if (level >= Config.RUNE_TRADE_LEVEL.get()
                    && rolled(villager, ROLL_RUNE, Config.RUNE_TRADE_CHANCE.get())) {
                ensure(offers, new MerchantOffer(new ItemCost(Items.EMERALD, Config.RUNE_TRADE_EMERALDS.get()),
                        Optional.of(new ItemCost(Items.BOOK, 1)), new ItemStack(ModItems.CORE_RUNE.get()),
                        1, 30, 0.05F));
            }
        } else if (profession == VillagerProfession.CLERIC) {
            if (level >= Config.POTION_TRADE_LEVEL.get() && rolled(villager, ROLL_POTION, Config.POTION_TRADE_CHANCE.get())) {
                ensure(offers, new MerchantOffer(new ItemCost(Items.EMERALD, Config.POTION_TRADE_EMERALDS.get()),
                        Optional.of(new ItemCost(Items.BLAZE_ROD, 1)), new ItemStack(ModItems.MYTHIC_POTION.get()),
                        3, 30, 0.05F));
            }
        } else if (profession == VillagerProfession.TOOLSMITH) {
            // Journeyman (3): I -> II, Expert (4): II -> III, Master (5): III -> IV.
            for (int tier = 1; tier <= 3; tier++) {
                if (level < tier + 2) {
                    break;
                }
                ItemStack material = Config.toolTradeMaterial(tier - 1);
                if (material == null) {
                    continue;
                }
                Item tool = ModItems.upgradeTool(tier).get();
                ensure(offers, new MerchantOffer(new ItemCost(tool, 1),
                        Optional.of(new ItemCost(material.getItem(), material.getCount())),
                        new ItemStack(ModItems.upgradeTool(tier + 1).get()), 3, 20, 0.0F));
            }
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
                || !event.getMerchantOffer().getResult().is(ModItems.CORE_RUNE.get())) {
            return;
        }
        event.getMerchantOffer().setToOutOfStock();
        if (event.getEntity().getServer() != null) {
            CoreWorldData.get(event.getEntity().getServer()).setRuneTraded(true);
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
