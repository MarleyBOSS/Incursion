package com.marleyboss.incursion.block;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.entity.CoreTargetEntity;
import com.marleyboss.incursion.registry.ModBlockEntities;
import com.marleyboss.incursion.registry.ModEntities;
import com.marleyboss.incursion.siege.SiegeManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Stores the state of an Incursion Core. Whether the core is active is kept in the
 * block state ({@link CoreBlock#ACTIVE}); level, health and siege bookkeeping live here.
 */
public class CoreBlockEntity extends BlockEntity {
    private static final String TAG_LEVEL = "CoreLevel";
    private static final String TAG_HEALTH = "CoreHealth";
    private static final String TAG_LOADED_RADIUS = "LoadedChunkRadius";

    private int coreLevel = 0;
    private float health = 0;
    /** Chunk radius currently force-loaded for this core, or -1 if none. */
    private int loadedChunkRadius = -1;

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
        if (!(level instanceof ServerLevel serverLevel) || !state.getValue(CoreBlock.ACTIVE)) {
            return;
        }
        core.tickCounter++;
        if (core.defuseTicksLeft > 0) {
            core.tickDefuse(serverLevel);
            if (!core.isActive()) {
                return;
            }
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
            if (target == null && entity.blockPosition().equals(pos)) {
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
                entity.discard();
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
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        coreLevel = tag.getInt(TAG_LEVEL);
        // Cores from v0.1 have no health yet: start them at full health.
        health = tag.contains(TAG_HEALTH) ? tag.getFloat(TAG_HEALTH) : -1;
        loadedChunkRadius = tag.contains(TAG_LOADED_RADIUS) ? tag.getInt(TAG_LOADED_RADIUS) : -1;
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
