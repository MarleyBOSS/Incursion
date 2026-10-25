package com.marleyboss.incursion.story;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The keepers among the villagers (the librarian who keeps the Core Rune, the cleric who brews the Mythic Potion, the
 * toolsmith of the order) become characters:
 * <ul>
 *     <li>Beside his trading screen, a panel shows him and what he says (see DialogPayload): the first time a player
 *     meets him a few lines to click through, later one line that fits his trading level.</li>
 *     <li>Then he carries a name from the lore with his title, always visible, so he is easy to find again.</li>
 *     <li>A named keeper does not die from monsters, falls, fire and the like: he collapses (lies on the ground) and
 *     gets up again the next morning. Only a player can really kill him.</li>
 * </ul>
 */
public final class StoryVillagers {
    private static final String TAG_NAMED = "IncursionStoryName";
    private static final String TAG_MET = "IncursionMetPlayers";
    private static final String TAG_DOWN_DAY = "IncursionDownDay";
    private static final String TAG_KEEPER = "IncursionKeeper";

    private static final String[] RUNE_NAMES = {"Orwin", "Ysolde", "Cael", "Edrin", "Mirelle", "Thalos", "Ansgar", "Liora"};
    private static final String[] GLOW_NAMES = {"Vela", "Morwen", "Halric", "Isen", "Brannoc", "Selka", "Odran", "Ilsa"};
    private static final String[] SMITH_NAMES = {"Borin", "Hald", "Garrek", "Torvald", "Ruona", "Dagmar", "Kestrel", "Bram"};

    private StoryVillagers() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, StoryVillagers::onDeath);
        NeoForge.EVENT_BUS.addListener(StoryVillagers::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(StoryVillagers::onConversion);
        NeoForge.EVENT_BUS.addListener(StoryVillagers::onContinueSleeping);
    }

    /** Which keeper he is: "rune", "glow", "smith". */
    enum Keeper {
        RUNE("rune", RUNE_NAMES),
        GLOW("glow", GLOW_NAMES),
        SMITH("smith", SMITH_NAMES);

        final String key;
        final String[] names;

        Keeper(String key, String[] names) {
            this.key = key;
            this.names = names;
        }
    }

    // ------------------------------------------------------------------
    // First meeting: dialog and name
    // ------------------------------------------------------------------

    /**
     * Called when a player right-clicks a keeper. Returns true if this player meets him for the first time (the
     * dialog panel then shows the first-meeting lines); the villager gets his name if he has none yet.
     */
    static boolean meet(ServerLevel level, Villager villager, Player player, Keeper keeper, boolean trusted) {
        CompoundTag data = villager.getPersistentData();
        ListTag met = data.getList(TAG_MET, 8);
        String id = player.getUUID().toString();
        for (int i = 0; i < met.size(); i++) {
            if (met.getString(i).equals(id)) {
                return false;
            }
        }
        met.add(StringTag.valueOf(id));
        data.put(TAG_MET, met);
        if (!data.getBoolean(TAG_NAMED)) {
            data.putBoolean(TAG_NAMED, true);
            data.putString(TAG_KEEPER, keeper.key);
            villager.setCustomName(nameOf(villager, keeper, villager.getVillagerData().getLevel()));
            villager.setCustomNameVisible(true);
            villager.setPersistenceRequired();
        }
        villager.getLookControl().setLookAt(player);
        level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 1.0F, 0.9F);
        return true;
    }

    static boolean isNamed(Villager villager) {
        return villager.getPersistentData().getBoolean(TAG_NAMED);
    }

    /**
     * "Orwin, Archivist": a name from the lore (always the same for this villager) and a title. Until the villager has
     * the level where he trades his story item the title gives nothing away (no spoiler); from that level on it says
     * what he is ("Rune Keeper").
     */
    private static MutableComponent nameOf(Villager villager, Keeper keeper, int level) {
        UUID id = villager.getUUID();
        int index = Math.floorMod((int) (id.getMostSignificantBits() ^ id.getLeastSignificantBits()), keeper.names.length);
        String key = "name.incursion.keeper." + keeper.key + (level >= StoryEvents.neededLevel(keeper) ? ".max" : "");
        return Component.translatable(key, keeper.names[index]).withStyle(ChatFormatting.GOLD);
    }

    /** A named keeper's title follows his level (called now and then and when he is spoken to). */
    static void refreshName(Villager villager, Keeper keeper) {
        if (!isNamed(villager) || isDown(villager)) {
            return;
        }
        MutableComponent wanted = nameOf(villager, keeper, villager.getVillagerData().getLevel());
        if (!wanted.equals(villager.getCustomName())) {
            villager.setCustomName(wanted);
        }
    }

    // ------------------------------------------------------------------
    // Collapsed instead of dying
    // ------------------------------------------------------------------

    static boolean isDown(Villager villager) {
        return villager.getPersistentData().contains(TAG_DOWN_DAY);
    }

    /** Monsters, falls, fire and the like make a named keeper collapse; a player kills him; the void still takes him. */
    private static boolean spares(Villager villager, DamageSource source) {
        return isNamed(villager) && !(source.getEntity() instanceof Player)
                && !source.is(DamageTypes.FELL_OUT_OF_WORLD) && !source.is(DamageTypes.GENERIC_KILL)
                && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    private static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Villager villager)
                || !(villager.level() instanceof ServerLevel level) || !spares(villager, event.getSource())) {
            return;
        }
        event.setCanceled(true);
        villager.setHealth(villager.getMaxHealth());
        collapse(level, villager);
    }

    /** A collapsed keeper takes no harm, except from a player (a hit would also wake a lying entity). */
    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Villager villager && !villager.level().isClientSide && isDown(villager)
                && spares(villager, event.getSource())) {
            event.setCanceled(true);
        }
    }

    /** He lies without a bed: the game would wake him every tick, he stays down until the morning. */
    private static void onContinueSleeping(net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent event) {
        if (event.getEntity() instanceof Villager villager && isDown(villager)) {
            event.setContinueSleeping(true);
        }
    }

    /** Named keepers never turn into a witch or a zombie villager. */
    private static void onConversion(LivingConversionEvent.Pre event) {
        if (event.getEntity() instanceof Villager villager && isNamed(villager)) {
            event.setCanceled(true);
        }
    }

    private static void collapse(ServerLevel level, Villager villager) {
        villager.getPersistentData().putLong(TAG_DOWN_DAY, Math.floorDiv(level.getDayTime(), 24000L));
        villager.setTradingPlayer(null);
        villager.getNavigation().stop();
        villager.clearFire();
        villager.setNoAi(true); // his brain would make him get up (or walk away lying down)
        villager.setSleepingPos(villager.blockPosition()); // lies on the ground where he fell (the sleeping pose)
        villager.setPose(net.minecraft.world.entity.Pose.SLEEPING);
        villager.setCustomName(Component.translatable("name.incursion.keeper.down",
                villager.getCustomName() != null ? villager.getCustomName() : villager.getDisplayName())
                .withStyle(ChatFormatting.GRAY));
        level.sendParticles(ParticleTypes.POOF, villager.getX(), villager.getY() + 0.4, villager.getZ(), 12, 0.3, 0.2, 0.3, 0.02);
        level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_HURT, SoundSource.NEUTRAL, 1.0F, 0.8F);
        level.playSound(null, villager.blockPosition(), SoundEvents.PLAYER_SMALL_FALL, SoundSource.NEUTRAL, 1.0F, 0.8F);
    }

    /** Every 2.5 seconds (from the villager tick): a collapsed keeper gets up again the next morning. */
    static void tickDown(ServerLevel level, Villager villager) {
        String key = villager.getPersistentData().getString(TAG_KEEPER);
        Keeper keeper = Keeper.SMITH;
        for (Keeper k : Keeper.values()) {
            if (k.key.equals(key)) {
                keeper = k;
            }
        }
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        long timeOfDay = Math.floorMod(level.getDayTime(), 24000L);
        if (day > villager.getPersistentData().getLong(TAG_DOWN_DAY) && timeOfDay < 12000L) {
            villager.getPersistentData().remove(TAG_DOWN_DAY);
            villager.stopSleeping();
            villager.setPose(net.minecraft.world.entity.Pose.STANDING);
            villager.setNoAi(false);
            villager.setCustomName(nameOf(villager, keeper, villager.getVillagerData().getLevel()));
            villager.setHealth(villager.getMaxHealth());
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 1.6, villager.getZ(),
                    8, 0.3, 0.3, 0.3, 0.0);
            level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0F, 1.0F);
            return;
        }
        if (!villager.isSleeping()) {
            // something stood him up (e.g. a reload): back to the ground
            villager.setSleepingPos(villager.blockPosition());
            villager.setPose(net.minecraft.world.entity.Pose.SLEEPING);
        }
    }

    /** The keeper this villager is, or null (decided by the same rolls as the trades). */
    static Keeper keeperOf(Villager villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession == VillagerProfession.LIBRARIAN && StoryEvents.isRuneKeeper(villager)) {
            return Keeper.RUNE;
        }
        if (profession == VillagerProfession.CLERIC && StoryEvents.isPotionKeeper(villager)) {
            return Keeper.GLOW;
        }
        if (profession == VillagerProfession.TOOLSMITH) {
            return Keeper.SMITH;
        }
        return null;
    }
}
