package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.block.CoreEvolution;
import com.marleyboss.incursion.block.GeneralRelicBlock;
import com.marleyboss.incursion.entity.CoreTargetEntity;
import com.marleyboss.incursion.registry.ModBlocks;
import com.marleyboss.incursion.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The boss night (Story-Update 6). The upgrades to Core level 5, 10, 15 and 20 need the tablet of a guardian. Once it
 * is set in, the Core waits on its old level; in the following night the general of that stage attacks (he comes
 * from a front's assembly point like the other attackers). A general still alive at dawn vanishes and comes back the
 * next night. When he falls he leaves his insignia as a block ({@link GeneralRelicBlock}); mined and set into the Core
 * it completes the upgrade, and only then does the Core grow (evolution).
 * <p>
 * Until the real generals exist (their own models, attacks and phases), a general is a strong zombie warrior with a
 * name, armour and a boss bar. He belongs to the siege (takes its ways, attacks the Core or nearby players), but never
 * vanishes as a "lost" mob and never self-destructs.
 */
public final class Generals {
    public static final String TAG = "incursion_general";
    private static final String DATA_CORE = "IncursionGeneralCore";
    private static final String DATA_STAGE = "IncursionGeneralStage";
    /** Players within this many blocks of a general see his boss bar. */
    private static final int BAR_RANGE = 64;
    private static final Map<UUID, ServerBossEvent> BARS = new HashMap<>();

    private Generals() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, Generals::onDeath);
        NeoForge.EVENT_BUS.addListener(Generals::onConversion);
        NeoForge.EVENT_BUS.addListener(Generals::onDamaged);
        NeoForge.EVENT_BUS.addListener(Generals::onServerStopping);
    }

    public static boolean isGeneral(Entity entity) {
        return entity.getTags().contains(TAG);
    }

    private static int stageOf(Entity entity) {
        return Math.max(1, Math.min(4, entity.getPersistentData().getInt(DATA_STAGE)));
    }

    /** "General I: The Scout" and so on. */
    public static MutableComponent name(int stage) {
        return Component.translatable("entity.incursion.general_" + Math.max(1, Math.min(4, stage)));
    }

    private static long nightStart() {
        return Config.NIGHT_SPAWN_START.get();
    }

    // ------------------------------------------------------------------
    // Starting, finishing, cancelling
    // ------------------------------------------------------------------

    /** The tablet is set in: the upgrade to {@code targetLevel} waits for the general of its stage. */
    public static void startBossNight(ServerLevel level, BlockPos pos, CoreBlockEntity core, Player player,
                                      int targetLevel) {
        long time = level.getDayTime();
        long day = Math.floorDiv(time, 24000L);
        long timeOfDay = Math.floorMod(time, 24000L);
        // Set in by day: he comes tonight. Set in at night: he comes the night after.
        long bossDay = timeOfDay >= nightStart() ? day + 1 : day;
        core.startPendingUpgrade(targetLevel, bossDay);
        int stage = CoreEvolution.stageFor(targetLevel);
        level.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 0.7F);
        level.playSound(null, pos, SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 1.0F, 0.6F);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                60, 0.6, 0.6, 0.6, 0.05);
        level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.incursion.general.tablet_set", ModItems.tablet(stage).get().getDescription(), targetLevel,
                name(stage)).withStyle(ChatFormatting.GOLD), false);
        core.forceSync();
    }

    /** The waiting upgrade is done (insignia set in, or skipped by the test tab): its general goes. */
    public static void finish(ServerLevel level, BlockPos pos, CoreBlockEntity core) {
        for (Mob general : find(level, pos, core.getCoreLevel())) {
            vanish(level, general);
        }
        core.clearPendingUpgrade();
    }

    /** The Core is defused: a waiting upgrade ends, the tablet comes back, its general goes. */
    public static void cancel(ServerLevel level, BlockPos pos, CoreBlockEntity core) {
        if (!core.hasPendingUpgrade()) {
            return;
        }
        int stage = CoreEvolution.stageFor(core.getPendingLevel());
        if (stage > 0) {
            dropSafe(level, pos.above(2), new ItemStack(ModItems.tablet(stage).get()));
        }
        finish(level, pos, core);
    }

    /** What the waiting upgrade needs next (shown when somebody tries to upgrade again). */
    public static MutableComponent pendingMessage(CoreBlockEntity core) {
        int pending = core.getPendingLevel();
        int stage = CoreEvolution.stageFor(pending);
        if (core.isGeneralDefeated()) {
            return Component.translatable("message.incursion.general.pending_insignia",
                    ModItems.insignia(stage).get().getDescription(), pending,
                    ModItems.upgradeTool(stage + 1).get().getDescription());
        }
        if (core.isGeneralPresent()) {
            return Component.translatable("message.incursion.general.pending_present", name(stage), pending);
        }
        return Component.translatable("message.incursion.general.pending_night", name(stage), pending);
    }

    // ------------------------------------------------------------------
    // Once per second (from SiegeManager.tickCore)
    // ------------------------------------------------------------------

    static void tick(ServerLevel level, BlockPos pos, CoreBlockEntity core, CoreTargetEntity target, long now) {
        List<Mob> found = find(level, pos, core.getCoreLevel());
        pruneBars(found);
        if (core.hasPendingUpgrade()) {
            seethe(level, pos, now);
        }
        if (!core.hasPendingUpgrade() || core.isGeneralDefeated()) {
            for (Mob stray : found) {
                vanish(level, stray); // no general is needed (any more)
            }
            core.setGeneralPresent(false);
            return;
        }
        int stage = Math.max(1, CoreEvolution.stageFor(core.getPendingLevel()));
        Mob general = null;
        for (Mob mob : found) {
            if (general == null && stageOf(mob) == stage) {
                general = mob;
            } else {
                vanish(level, mob); // never two at once (e.g. an old one was loaded again with its chunk)
            }
        }
        long time = level.getDayTime();
        long day = Math.floorDiv(time, 24000L);
        long timeOfDay = Math.floorMod(time, 24000L);
        if (general != null) {
            if (day > core.getBossDay()) {
                // Dawn: he did not fall this night. He comes back the next one.
                vanish(level, general);
                core.setBossDay(day);
                core.setGeneralPresent(false);
                level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                        "message.incursion.general.vanished", name(stage)).withStyle(ChatFormatting.GRAY), false);
                return;
            }
            core.setGeneralPresent(true);
            updateBar(level, general);
            return;
        }
        core.setGeneralPresent(false);
        if (day > core.getBossDay()) {
            core.setBossDay(day); // a night went by without him (server off, Core unloaded): he comes tonight
        }
        if (day == core.getBossDay() && timeOfDay >= nightStart() && level.getDifficulty() != Difficulty.PEACEFUL) {
            spawn(level, pos, core, target, stage, now);
        }
    }

    /**
     * While an upgrade waits (tablet in, until the upgrade is completed) the Core seethes: it bubbles and smokes,
     * now and then a glowing drop jumps out.
     */
    private static void seethe(ServerLevel level, BlockPos pos, long now) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.2;
        double z = pos.getZ() + 0.5;
        level.sendParticles(ParticleTypes.SMOKE, x, y, z, 4, 0.3, 0.15, 0.3, 0.01);
        level.sendParticles(ParticleTypes.BUBBLE_POP, x, y, z, 3, 0.25, 0.1, 0.25, 0.01);
        if (now % 60 < 20) {
            level.sendParticles(ParticleTypes.LAVA, x, y, z, 2, 0.2, 0.05, 0.2, 0.0);
            level.playSound(null, pos, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 0.7F, 0.7F);
        } else if (now % 60 < 40) {
            level.playSound(null, pos, SoundEvents.LAVA_AMBIENT, SoundSource.BLOCKS, 0.4F, 0.6F);
        }
    }

    /** TEST TAB: the general of the waiting upgrade comes at once (he stays until the next dawn). */
    public static void testSpawn(ServerLevel level, BlockPos pos, CoreBlockEntity core, Player player) {
        if (!(player.hasPermissions(2) || player.getAbilities().instabuild)) {
            return;
        }
        if (!core.hasPendingUpgrade()) {
            player.displayClientMessage(Component.translatable("message.incursion.general.test_no_pending")
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        if (core.isGeneralDefeated() || !find(level, pos, core.getCoreLevel()).isEmpty()) {
            player.displayClientMessage(pendingMessage(core).withStyle(ChatFormatting.GOLD), false);
            return;
        }
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            player.displayClientMessage(Component.translatable("message.incursion.general.peaceful")
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        CoreTargetEntity target = core.ensureTarget(level);
        if (target == null) {
            return;
        }
        core.setBossDay(Math.floorDiv(level.getDayTime(), 24000L));
        int stage = Math.max(1, CoreEvolution.stageFor(core.getPendingLevel()));
        if (!spawn(level, pos, core, target, stage, level.getGameTime())) {
            player.displayClientMessage(Component.translatable("message.incursion.general.no_spot")
                    .withStyle(ChatFormatting.RED), false);
        }
    }

    private static boolean spawn(ServerLevel level, BlockPos pos, CoreBlockEntity core, CoreTargetEntity target,
                                 int stage, long now) {
        Mob general = SiegeManager.spawnGeneral(level, pos, core.getCoreLevel(), target, now,
                mob -> prepare(mob, stage, pos));
        if (general == null) {
            return false; // no free spot: tried again in a second
        }
        core.setGeneralPresent(true);
        updateBar(level, general);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, general.getX(), general.getY() + 1.0, general.getZ(),
                60, 0.5, 1.0, 0.5, 0.05);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, general.getX(), general.getY() + 1.0, general.getZ(),
                30, 0.5, 1.0, 0.5, 0.02);
        level.playSound(null, pos, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 0.7F);
        level.playSound(null, general.blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.8F, 0.7F);
        level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.incursion.general.arrived", name(stage), general.getBlockX(), general.getBlockY(),
                general.getBlockZ()).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
        return true;
    }

    /** All generals of the Core at {@code pos} in its siege area. */
    private static List<Mob> find(ServerLevel level, BlockPos pos, int coreLevel) {
        int radius = Config.siegeRadiusBlocks(Math.max(1, coreLevel)) + 32;
        AABB area = new AABB(pos).inflate(radius, 160, radius);
        long key = pos.asLong();
        return level.getEntitiesOfClass(Mob.class, area, mob -> mob.isAlive() && isGeneral(mob)
                && mob.getPersistentData().getLong(DATA_CORE) == key);
    }

    private static void vanish(ServerLevel level, Mob general) {
        removeBar(general.getUUID());
        level.sendParticles(ParticleTypes.LARGE_SMOKE, general.getX(), general.getY() + 1.0, general.getZ(),
                40, 0.4, 0.9, 0.4, 0.03);
        level.sendParticles(ParticleTypes.SOUL, general.getX(), general.getY() + 1.0, general.getZ(),
                20, 0.4, 0.9, 0.4, 0.03);
        level.playSound(null, general.blockPosition(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.HOSTILE, 1.0F, 0.6F);
        SiegeAI.dismiss(general);
    }

    // ------------------------------------------------------------------
    // The general himself (placeholder: a strong zombie warrior)
    // ------------------------------------------------------------------

    private static void prepare(Mob mob, int stage, BlockPos core) {
        mob.addTag(TAG);
        mob.getPersistentData().putLong(DATA_CORE, core.asLong());
        mob.getPersistentData().putInt(DATA_STAGE, stage);
        mob.setPersistenceRequired();
        if (mob instanceof Zombie zombie) {
            zombie.setBaby(false);
        }
        mob.setCanPickUpLoot(false);
        mob.setCustomName(name(stage).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        mob.setCustomNameVisible(true);

        // Fixed values per general instead of the siege bonus per Core level.
        removeModifier(mob, Attributes.MAX_HEALTH, SiegeManager.HEALTH_BONUS_ID);
        removeModifier(mob, Attributes.ATTACK_DAMAGE, SiegeManager.DAMAGE_BONUS_ID);
        int index = stage - 1;
        setBase(mob, Attributes.MAX_HEALTH, Config.listValue(Config.GENERAL_HEALTH.get(), index, 150 + 150 * index));
        setBase(mob, Attributes.ATTACK_DAMAGE, Config.listValue(Config.GENERAL_DAMAGE.get(), index, 6 + 2 * index));
        setBase(mob, Attributes.ARMOR, Config.listValue(Config.GENERAL_ARMOR.get(), index, 4 + 2 * index));
        setBase(mob, Attributes.MOVEMENT_SPEED, Config.GENERAL_SPEED.get());
        setBase(mob, Attributes.KNOCKBACK_RESISTANCE, Math.min(1.0, 0.5 + 0.15 * index));
        setBase(mob, Attributes.FOLLOW_RANGE, 48.0);
        setBase(mob, Attributes.SPAWN_REINFORCEMENTS_CHANCE, 0.0); // he does not call zombies for help
        mob.setHealth(mob.getMaxHealth());

        Item[][] gear = {
                {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.IRON_SWORD},
                {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Items.DIAMOND_AXE},
                {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Items.NETHERITE_SWORD},
                {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
                        Items.NETHERITE_AXE}};
        Item[] set = gear[Math.max(0, Math.min(3, index))];
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.MAINHAND};
        for (int i = 0; i < slots.length; i++) {
            mob.setItemSlot(slots[i], new ItemStack(set[i]));
            mob.setDropChance(slots[i], 0.0F); // the reward is the insignia, not his gear
        }
        mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    private static void setBase(Mob mob, Holder<Attribute> attribute, double value) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static void removeModifier(Mob mob, Holder<Attribute> attribute, net.minecraft.resources.ResourceLocation id) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }

    // ------------------------------------------------------------------
    // Boss bar
    // ------------------------------------------------------------------

    private static void updateBar(ServerLevel level, Mob general) {
        ServerBossEvent bar = BARS.computeIfAbsent(general.getUUID(), id -> {
            ServerBossEvent created = new ServerBossEvent(name(stageOf(general)).withStyle(ChatFormatting.DARK_RED),
                    BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
            created.setDarkenScreen(true);
            return created;
        });
        bar.setProgress(general.getHealth() / Math.max(1.0F, general.getMaxHealth()));
        double range = BAR_RANGE * (double) BAR_RANGE;
        for (ServerPlayer player : new ArrayList<>(bar.getPlayers())) {
            if (player.level() != level || player.distanceToSqr(general) > range || player.isRemoved()) {
                bar.removePlayer(player);
            }
        }
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(general) <= range) {
                bar.addPlayer(player);
            }
        }
    }

    /** Boss bars of generals that are gone in some other way (unloaded, removed by a command) disappear too. */
    private static void pruneBars(List<Mob> alive) {
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (Mob mob : alive) {
            ids.add(mob.getUUID());
        }
        for (UUID id : new ArrayList<>(BARS.keySet())) {
            if (!ids.contains(id)) {
                removeBar(id);
            }
        }
    }

    /** A general was removed by the siege (Core destroyed or defused): his boss bar goes too. */
    static void onRemoved(Mob mob) {
        if (isGeneral(mob)) {
            removeBar(mob.getUUID());
        }
    }

    private static void removeBar(UUID id) {
        ServerBossEvent bar = BARS.remove(id);
        if (bar != null) {
            bar.removeAllPlayers();
        }
    }

    private static void onDamaged(LivingDamageEvent.Post event) {
        LivingEntity entity = event.getEntity();
        if (!entity.level().isClientSide && isGeneral(entity)) {
            ServerBossEvent bar = BARS.get(entity.getUUID());
            if (bar != null) {
                bar.setProgress(entity.getHealth() / Math.max(1.0F, entity.getMaxHealth()));
            }
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        for (ServerBossEvent bar : BARS.values()) {
            bar.removeAllPlayers();
        }
        BARS.clear();
    }

    /** A general never turns into a drowned (he would lose his name, his armour and his insignia). */
    private static void onConversion(LivingConversionEvent.Pre event) {
        if (isGeneral(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------
    // Death: the insignia stays behind
    // ------------------------------------------------------------------

    private static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Mob general) || !isGeneral(general)
                || !(general.level() instanceof ServerLevel level)) {
            return;
        }
        removeBar(general.getUUID());
        int stage = stageOf(general);
        BlockPos corePos = BlockPos.of(general.getPersistentData().getLong(DATA_CORE));
        general.removeTag(TAG); // counted once, even if something calls die() twice

        BlockPos relic = placeRelic(level, general.blockPosition(), stage);
        if (relic == null) {
            dropSafe(level, general.blockPosition().above(), new ItemStack(ModItems.insignia(stage).get()));
            relic = general.blockPosition();
        }
        if (level.isLoaded(corePos) && level.getBlockEntity(corePos) instanceof CoreBlockEntity core
                && core.hasPendingUpgrade() && CoreEvolution.stageFor(core.getPendingLevel()) == stage) {
            core.setGeneralDefeated(true);
            core.setGeneralPresent(false);
        }
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, general.getX(), general.getY() + 1.0, general.getZ(),
                80, 0.5, 1.0, 0.5, 0.08);
        level.playSound(null, relic, SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 0.5F, 1.2F);
        level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.incursion.general.fallen", name(stage), ModItems.insignia(stage).get().getDescription(),
                relic.getX(), relic.getY(), relic.getZ()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), false);
    }

    /**
     * Puts the insignia block where the general fell: down to the ground if he died in the air, up out of whatever he
     * died in. Returns null if there was no place for it.
     */
    private static BlockPos placeRelic(ServerLevel level, BlockPos start, int stage) {
        BlockPos pos = start;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        if (pos.getY() < minY || pos.getY() > maxY) {
            return null;
        }
        for (int i = 0; i < 48 && pos.getY() > minY && level.getBlockState(pos.below()).canBeReplaced(); i++) {
            pos = pos.below();
        }
        for (int i = 0; i < 16 && pos.getY() < maxY && !level.getBlockState(pos).canBeReplaced(); i++) {
            pos = pos.above();
        }
        if (!level.getBlockState(pos).canBeReplaced()) {
            return null;
        }
        BlockState relic = ModBlocks.GENERAL_RELIC.get().defaultBlockState().setValue(GeneralRelicBlock.GENERAL, stage);
        level.setBlockAndUpdate(pos, relic);
        return pos;
    }

    /** An item that cannot burn, despawn or be blown up (tablets and insignias are not to be lost). */
    private static void dropSafe(ServerLevel level, BlockPos pos, ItemStack stack) {
        ItemEntity item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        item.setUnlimitedLifetime();
        item.setInvulnerable(true);
        item.setGlowingTag(true);
        level.addFreshEntity(item);
    }
}
