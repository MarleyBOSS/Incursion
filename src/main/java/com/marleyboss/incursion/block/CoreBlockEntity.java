package com.marleyboss.incursion.block;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.entity.CoreTargetEntity;
import com.marleyboss.incursion.registry.ModBlockEntities;
import com.marleyboss.incursion.registry.ModEntities;
import com.marleyboss.incursion.siege.Shields;
import com.marleyboss.incursion.siege.SiegeManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stores the state of an Incursion Core. Whether the core is active is kept in the
 * block state ({@link CoreBlock#ACTIVE}); level, health and siege bookkeeping live here.
 */
public class CoreBlockEntity extends BlockEntity {
    private static final String TAG_LEVEL = "CoreLevel";
    private static final String TAG_HEALTH = "CoreHealth";
    private static final String TAG_LOADED_RADIUS = "LoadedChunkRadius";
    private static final String TAG_SHOW_BORDERS = "ShowBorders";
    private static final String TAG_SHOW_AI = "ShowAI";
    private static final String TAG_BROKEN_SHIELDS = "BrokenShields";
    private static final String TAG_SHARDS = "Shards";
    private static final String TAG_NIGHTS = "DefendedNights";
    private static final String TAG_KILLS = "KilledMobs";
    private static final String TAG_NIGHT_DAY = "NightDay";
    /** Only in the client update tag (never saved to disk): ward totems whose border the client draws. */
    private static final String TAG_WARDS = "Wards";

    private int coreLevel = 0;
    /** Core shards kept in the Core (the balance). */
    private int shards = 0;
    /** Nights this Core has survived while active (statistic). */
    private int defendedNights = 0;
    /** Siege mobs that died around this Core (statistic). */
    private int killedMobs = 0;
    /** Day number of the night this Core saw begin while active (-1 = none); paid out at the next morning. */
    private long nightDay = -1;
    private float health = 0;
    /** Chunk radius currently force-loaded for this core, or -1 if none. */
    private int loadedChunkRadius = -1;
    /** Draw the spawn borders (Core and ward totems) as particle rings. */
    private boolean showBorders = false;
    /** AI view: name tags with what every attacker is doing, routes as particles. */
    private boolean showAi = false;
    /** Bit i is set if shield i + 1 (0 = outermost) is broken. Cleared by repairing, defusing or reactivating. */
    private int brokenShields = 0;
    /** Ward totem positions (packed) sent to clients for the border display. */
    private long[] syncedWards = new long[0];
    private int syncSignature = Integer.MIN_VALUE;
    private int idleCounter = 0;
    /** Loaded Cores, so a changed ward totem (switched off, repaired) can have the borders sent again at once. */
    private static final java.util.Set<CoreBlockEntity> LOADED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    // Runtime only (not saved)
    @Nullable
    private CoreTargetEntity target;
    private int tickCounter = 0;
    private long lastWarningTick = Long.MIN_VALUE / 2;
    private int siegeMobCount = 0;
    /** Remaining ticks of a running defusal (0 = not defusing). */
    private int defuseTicksLeft = 0;
    private int defuseTicksTotal = 0;
    @Nullable
    private UUID defuser;

    public CoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CORE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CoreBlockEntity core) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        LOADED.add(core);
        if (!state.getValue(CoreBlock.ACTIVE)) {
            // An inactive Core can show the borders too (switch in the GUI): the totems around it are synced.
            if (core.showBorders && ++core.idleCounter % 20 == 0) {
                core.syncToClient(WardBlockEntity.wardsNear(level, pos,
                        Config.siegeRadiusBlocks(Math.max(1, core.coreLevel))));
            }
            return;
        }
        core.tickCounter++;
        if (core.defuseTicksLeft > 0) {
            core.tickDefuse(serverLevel);
            if (!core.isActive()) {
                return;
            }
        }
        Shields.tick(serverLevel, pos, core, core.tickCounter);
        if (!core.isActive()) {
            return;
        }
        if (core.tickCounter % 20 == 0) {
            SiegeManager.tickCore(serverLevel, pos, core, core.tickCounter);
        }
    }

    // ------------------------------------------------------------------
    // Getters / setters
    // ------------------------------------------------------------------

    public boolean isActive() {
        BlockState state = getBlockState();
        return state.getBlock() instanceof CoreBlock && state.getValue(CoreBlock.ACTIVE);
    }

    public int getCoreLevel() {
        return coreLevel;
    }

    public void setCoreLevel(int coreLevel) {
        this.coreLevel = Math.max(0, coreLevel);
        setChanged();
    }

    public float getHealth() {
        return health;
    }

    public void setHealth(float health) {
        this.health = Math.max(0, Math.min(health, getMaxHealth()));
        setChanged();
    }

    public int getMaxHealth() {
        return Config.maxCoreHealth(coreLevel);
    }

    public int getLoadedChunkRadius() {
        return loadedChunkRadius;
    }

    public void setLoadedChunkRadius(int loadedChunkRadius) {
        this.loadedChunkRadius = loadedChunkRadius;
        setChanged();
    }

    // ------------------------------------------------------------------
    // Client sync (shields, borders and level are drawn by the client renderer)
    // ------------------------------------------------------------------

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        tag.putLongArray(TAG_WARDS, syncedWards);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Sends the state the client needs if it changed (called once per second by the siege). */
    public void syncToClient(List<BlockPos> wards) {
        long[] packed = new long[wards.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = wards.get(i).asLong();
        }
        Arrays.sort(packed);
        int signature = Objects.hash(coreLevel, showBorders, brokenShields, Arrays.hashCode(packed));
        if (signature != syncSignature) {
            syncSignature = signature;
            syncedWards = packed;
            forceSync();
        }
    }

    /** Makes every loaded Core send its borders to the clients again within a second (a ward totem changed). */
    public static void resyncAll() {
        for (CoreBlockEntity core : LOADED) {
            core.syncSignature = Integer.MIN_VALUE;
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED.remove(this);
    }

    /** Sends the current state to all clients that track this block right away. */
    public void forceSync() {
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Client side: positions of the ward totems around this Core. */
    public List<BlockPos> getSyncedWards() {
        List<BlockPos> result = new ArrayList<>(syncedWards.length);
        for (long packed : syncedWards) {
            result.add(BlockPos.of(packed));
        }
        return result;
    }

    public boolean isShieldBroken(int index) {
        return (brokenShields & (1 << index)) != 0;
    }

    public void setShieldBroken(int index, boolean broken) {
        brokenShields = broken ? brokenShields | (1 << index) : brokenShields & ~(1 << index);
        setChanged();
    }

    public void resetShields() {
        brokenShields = 0;
        setChanged();
    }

    public int getShards() {
        return shards;
    }

    public void addShards(int amount) {
        shards = (int) Math.max(0, Math.min((long) shards + amount, Integer.MAX_VALUE));
        setChanged();
    }

    /** Removes up to {@code amount} shards from the balance and returns how many were really taken. */
    public int takeShards(int amount) {
        int taken = Math.max(0, Math.min(amount, shards));
        shards -= taken;
        if (taken > 0) {
            setChanged();
        }
        return taken;
    }

    public int getDefendedNights() {
        return defendedNights;
    }

    public void addDefendedNight() {
        defendedNights = (int) Math.min((long) defendedNights + 1, Integer.MAX_VALUE);
        setChanged();
    }

    public int getKilledMobs() {
        return killedMobs;
    }

    public void addKilledMob() {
        killedMobs = (int) Math.min((long) killedMobs + 1, Integer.MAX_VALUE);
        setChanged();
    }

    public long getNightDay() {
        return nightDay;
    }

    public void setNightDay(long nightDay) {
        if (this.nightDay != nightDay) {
            this.nightDay = nightDay;
            setChanged();
        }
    }

    public int getSiegeMobCount() {
        return siegeMobCount;
    }

    public void setSiegeMobCount(int siegeMobCount) {
        this.siegeMobCount = siegeMobCount;
    }

    // ------------------------------------------------------------------
    // Defusing
    // ------------------------------------------------------------------

    public boolean isDefusing() {
        return defuseTicksLeft > 0;
    }

    public int getDefuseTicksLeft() {
        return defuseTicksLeft;
    }

    public int getDefuseTicksTotal() {
        return defuseTicksTotal;
    }

    /** Starts defusing, or cancels a running defusal. */
    public void toggleDefuse(ServerLevel level, Player player) {
        if (!isActive()) {
            return;
        }
        if (isDefusing()) {
            stopDefuse();
            player.displayClientMessage(Component.translatable("message.incursion.core.defuse_cancelled")
                    .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        defuseTicksTotal = Math.max(1, Config.defuseTicks(coreLevel));
        defuseTicksLeft = defuseTicksTotal;
        defuser = player.getUUID();
        BlockPos pos = getBlockPos();
        level.playSound(null, pos, SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS, 1.0F, 0.5F);
        player.displayClientMessage(Component.translatable("message.incursion.core.defuse_started",
                defuseTicksTotal / 20, Config.DEFUSE_MAX_DISTANCE.get()).withStyle(ChatFormatting.YELLOW), false);
    }

    private void stopDefuse() {
        defuseTicksLeft = 0;
        defuseTicksTotal = 0;
        defuser = null;
    }

    private void tickDefuse(ServerLevel level) {
        BlockPos pos = getBlockPos();
        ServerPlayer player = defuser == null ? null : level.getServer().getPlayerList().getPlayer(defuser);
        double maxDistance = Config.DEFUSE_MAX_DISTANCE.get();
        if (player == null || player.level() != level
                || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > maxDistance * maxDistance) {
            stopDefuse();
            if (player != null) {
                player.displayClientMessage(Component.translatable("message.incursion.core.defuse_too_far")
                        .withStyle(ChatFormatting.RED), false);
            }
            level.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 0.6F, 1.5F);
            return;
        }
        defuseTicksLeft--;
        if (defuseTicksLeft <= 0) {
            stopDefuse();
            SiegeManager.defuse(level, pos, this);
            return;
        }
        if (defuseTicksLeft % 20 == 0) {
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 0.8F, 1.6F);
            player.displayClientMessage(Component.translatable("message.incursion.core.defuse_progress",
                    defuseTicksLeft / 20).withStyle(ChatFormatting.YELLOW), true);
        }
    }

    // ------------------------------------------------------------------
    // Target entity
    // ------------------------------------------------------------------

    /** Returns the invisible target entity for this core, spawning it if necessary. */
    @Nullable
    public CoreTargetEntity ensureTarget(ServerLevel level) {
        if (target != null && target.isAlive() && target.level() == level) {
            return target;
        }
        target = null;
        BlockPos pos = getBlockPos();
        List<CoreTargetEntity> existing = level.getEntitiesOfClass(CoreTargetEntity.class, new AABB(pos).inflate(1.0));
        for (CoreTargetEntity entity : existing) {
            if (!entity.blockPosition().equals(pos)) {
                continue; // a ward totem next door
            }
            if (target == null) {
                target = entity;
            } else {
                entity.discard();
            }
        }
        if (target == null) {
            CoreTargetEntity entity = ModEntities.CORE_TARGET.get().create(level);
            if (entity == null) {
                return null;
            }
            entity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
            level.addFreshEntity(entity);
            target = entity;
        }
        return target;
    }

    @Nullable
    public CoreTargetEntity getTarget() {
        return target;
    }

    public void removeTarget() {
        if (target != null) {
            target.discard();
            target = null;
        }
        if (level != null) {
            for (CoreTargetEntity entity : level.getEntitiesOfClass(CoreTargetEntity.class,
                    new AABB(getBlockPos()).inflate(1.0))) {
                if (entity.blockPosition().equals(getBlockPos())) {
                    entity.discard(); // not the stand-in of a ward totem next door
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Damage
    // ------------------------------------------------------------------

    public void damageCore(ServerLevel level, float amount) {
        if (!isActive() || amount <= 0) {
            return;
        }
        if (Shields.absorbs(this)) {
            Shields.spark(level, getBlockPos());
            return;
        }
        health = Math.max(0, health - amount);
        setChanged();

        BlockPos pos = getBlockPos();
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                3, 0.3, 0.1, 0.3, 0.1);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.BLOCKS, 1.0F, 0.6F);

        int maxHealth = getMaxHealth();
        long now = level.getGameTime();
        if (now - lastWarningTick >= Config.WARNING_COOLDOWN_SECONDS.get() * 20L) {
            lastWarningTick = now;
            boolean critical = health <= maxHealth * 0.25F;
            level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                    critical ? "message.incursion.core.under_attack_critical" : "message.incursion.core.under_attack",
                    (int) Math.ceil(health), maxHealth, pos.getX(), pos.getY(), pos.getZ())
                    .withStyle(critical ? ChatFormatting.DARK_RED : ChatFormatting.RED), false);
        }

        if (health <= 0) {
            SiegeManager.destroyCore(level, pos, this);
        }
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_LEVEL, coreLevel);
        tag.putFloat(TAG_HEALTH, health);
        tag.putInt(TAG_LOADED_RADIUS, loadedChunkRadius);
        tag.putBoolean(TAG_SHOW_BORDERS, showBorders);
        tag.putBoolean(TAG_SHOW_AI, showAi);
        tag.putInt(TAG_BROKEN_SHIELDS, brokenShields);
        tag.putInt(TAG_SHARDS, shards);
        tag.putInt(TAG_NIGHTS, defendedNights);
        tag.putInt(TAG_KILLS, killedMobs);
        tag.putLong(TAG_NIGHT_DAY, nightDay);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        coreLevel = tag.getInt(TAG_LEVEL);
        // Cores from v0.1 have no health yet: start them at full health.
        health = tag.contains(TAG_HEALTH) ? tag.getFloat(TAG_HEALTH) : -1;
        loadedChunkRadius = tag.contains(TAG_LOADED_RADIUS) ? tag.getInt(TAG_LOADED_RADIUS) : -1;
        showBorders = tag.getBoolean(TAG_SHOW_BORDERS);
        showAi = tag.getBoolean(TAG_SHOW_AI);
        brokenShields = tag.getInt(TAG_BROKEN_SHIELDS);
        shards = tag.getInt(TAG_SHARDS);
        defendedNights = tag.getInt(TAG_NIGHTS);
        killedMobs = tag.getInt(TAG_KILLS);
        nightDay = tag.contains(TAG_NIGHT_DAY) ? tag.getLong(TAG_NIGHT_DAY) : -1;
        syncedWards = tag.contains(TAG_WARDS) ? tag.getLongArray(TAG_WARDS) : new long[0];
    }

    public boolean isShowBorders() {
        return showBorders;
    }

    public void toggleShowBorders() {
        showBorders = !showBorders;
        setChanged();
        forceSync();
    }

    public boolean isShowAi() {
        return showAi;
    }

    public void toggleShowAi() {
        showAi = !showAi;
        setChanged();
    }

    /** Called by the siege manager once per second; fixes up values that could not be set while loading. */
    public void validateHealth() {
        if (health < 0) {
            health = getMaxHealth();
            setChanged();
        } else if (health > getMaxHealth()) {
            health = getMaxHealth();
            setChanged();
        }
    }
}
