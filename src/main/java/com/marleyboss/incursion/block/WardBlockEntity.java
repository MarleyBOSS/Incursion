package com.marleyboss.incursion.block;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.entity.CoreTargetEntity;
import com.marleyboss.incursion.registry.ModBlockEntities;
import com.marleyboss.incursion.registry.ModEntities;
import com.marleyboss.incursion.siege.SiegeProgress;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A ward totem. Besides marking the safe zone it has health: the siege attacks it (through an invisible
 * {@link CoreTargetEntity} standing on it) and, at 0 health, switches it off. A switched-off totem stays
 * where it is but its safe zone is gone until it is repaired (see {@link WardBlock}). Whether it is switched off
 * lives in the block state ({@link WardBlock#DISABLED}); the health lives here.
 */
public class WardBlockEntity extends BlockEntity {
    private static final String TAG_HEALTH = "WardHealth";
    private static final Set<WardBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    /** Negative = not set yet (full health once the config is available). */
    private float health = -1.0F;
    private long lastWarningTick = Long.MIN_VALUE / 2;
    /** Health last sent to the clients (they draw the health bar above the totem). */
    private float syncedHealth = -1.0F;
    private long lastSyncTick = Long.MIN_VALUE / 2;
    @Nullable
    private CoreTargetEntity target;

    public WardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WARD.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, WardBlockEntity ward) {
        // The borders are drawn by the client from data synced by the Core.
        LOADED.add(ward);
        if (level.getGameTime() % 20 == 0 && !state.getValue(WardBlock.DISABLED)) {
            double regen = Config.CORE_REGEN_PER_MINUTE.get() / 60.0;
            if (regen > 0 && ward.getHealth() < ward.getMaxHealth()) {
                ward.setHealth((float) (ward.getHealth() + regen));
            }
            if (Math.abs(ward.getHealth() - ward.syncedHealth) >= 1.0F) {
                ward.syncHealth(level.getGameTime());
            }
        }
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    public int getMaxHealth() {
        return Config.WARD_HEALTH.get();
    }

    public float getHealth() {
        if (health < 0.0F || health > getMaxHealth()) {
            health = getMaxHealth();
        }
        return health;
    }

    /** Sends the health to the clients (the bar above the totem). */
    private void syncHealth(long now) {
        if (level != null && !level.isClientSide) {
            syncedHealth = getHealth();
            lastSyncTick = now;
            BlockState state = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public void setHealth(float value) {
        this.health = Math.max(0.0F, Math.min(value, getMaxHealth()));
        setChanged();
    }

    /** True if the siege switched this totem off (read from the world, so it is never stale). */
    public boolean isDisabled() {
        if (level == null) {
            return false;
        }
        BlockState state = level.getBlockState(worldPosition);
        return state.getBlock() instanceof WardBlock && state.getValue(WardBlock.DISABLED);
    }

    // ------------------------------------------------------------------
    // Damage and repair
    // ------------------------------------------------------------------

    /** Damage dealt by siege mobs to the invisible target on this totem. */
    public void damageWard(ServerLevel serverLevel, float amount) {
        if (isDisabled() || amount <= 0) {
            return;
        }
        health = Math.max(0.0F, getHealth() - amount);
        setChanged();

        BlockPos pos = getBlockPos();
        serverLevel.sendParticles(ParticleTypes.DAMAGE_INDICATOR, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                3, 0.3, 0.1, 0.3, 0.1);
        serverLevel.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.BLOCKS, 1.0F, 0.9F);

        long now = serverLevel.getGameTime();
        if (now - lastSyncTick >= 5) {
            syncHealth(now);
        }
        if (health > 0.0F && now - lastWarningTick >= Config.WARNING_COOLDOWN_SECONDS.get() * 20L) {
            lastWarningTick = now;
            serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.incursion.ward.under_attack", (int) Math.ceil(health),
                            getMaxHealth(), pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.RED), false);
        }
        if (health <= 0.0F) {
            disable(serverLevel);
        }
    }

    private void disable(ServerLevel serverLevel) {
        BlockPos pos = getBlockPos();
        BlockState state = serverLevel.getBlockState(pos);
        if (!(state.getBlock() instanceof WardBlock)) {
            return;
        }
        removeTarget();
        serverLevel.setBlock(pos, state.setValue(WardBlock.DISABLED, true), Block.UPDATE_ALL);
        serverLevel.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.5F, 0.8F);
        serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5,
                20, 0.3, 0.5, 0.3, 0.02);
        serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("message.incursion.ward.disabled", pos.getX(), pos.getY(), pos.getZ())
                        .withStyle(ChatFormatting.DARK_RED), false);
        SiegeProgress.progress(pos, serverLevel.getGameTime());
        CoreBlockEntity.resyncAll(); // the safe zone changed: the Core sends the borders again
    }

    /** Switches the totem on again with full health. */
    public void repair(ServerLevel serverLevel) {
        BlockPos pos = getBlockPos();
        BlockState state = serverLevel.getBlockState(pos);
        if (!(state.getBlock() instanceof WardBlock)) {
            return;
        }
        health = getMaxHealth();
        setChanged();
        serverLevel.setBlock(pos, state.setValue(WardBlock.DISABLED, false), Block.UPDATE_ALL);
        serverLevel.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.2F);
        syncHealth(serverLevel.getGameTime());
        CoreBlockEntity.resyncAll();
    }

    // ------------------------------------------------------------------
    // Target entity (what the siege mobs attack)
    // ------------------------------------------------------------------

    /** Returns the invisible target entity of this totem, spawning it if necessary (null if switched off). */
    @Nullable
    public CoreTargetEntity ensureTarget(ServerLevel serverLevel) {
        if (isDisabled()) {
            removeTarget();
            return null;
        }
        if (target != null && target.isAlive() && target.level() == serverLevel) {
            return target;
        }
        target = null;
        BlockPos pos = getBlockPos();
        List<CoreTargetEntity> existing = serverLevel.getEntitiesOfClass(CoreTargetEntity.class,
                new AABB(pos).inflate(1.0));
        for (CoreTargetEntity entity : existing) {
            if (!entity.blockPosition().equals(pos)) {
                continue; // belongs to the Core or another totem next door
            }
            if (target == null) {
                target = entity;
            } else {
                entity.discard();
            }
        }
        if (target == null) {
            CoreTargetEntity entity = ModEntities.CORE_TARGET.get().create(serverLevel);
            if (entity == null) {
                return null;
            }
            entity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
            serverLevel.addFreshEntity(entity);
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
        if (level != null && !level.isClientSide) {
            for (CoreTargetEntity entity : level.getEntitiesOfClass(CoreTargetEntity.class,
                    new AABB(getBlockPos()).inflate(1.0))) {
                if (entity.blockPosition().equals(getBlockPos())) {
                    entity.discard();
                }
            }
        }
    }

    /** The target entity of the active ward totem at {@code pos}, or null (no totem, switched off, not loaded). */
    @Nullable
    public static CoreTargetEntity targetAt(Level level, BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof WardBlockEntity ward
                && !ward.isDisabled() && ward.target != null && ward.target.isAlive()) {
            return ward.target;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putFloat(TAG_HEALTH, health);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        health = tag.contains(TAG_HEALTH) ? tag.getFloat(TAG_HEALTH) : -1.0F;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED.remove(this);
        // The entity is not searched for here (the chunk may be unloading); a stray one removes itself.
        if (target != null) {
            target.discard();
            target = null;
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        LOADED.remove(this);
        if (target != null) {
            target.discard();
            target = null;
        }
    }

    /**
     * Positions of the loaded, switched-on ward totems in this level within a square around {@code center}.
     * Switched-off totems have no safe zone, so they are left out.
     */
    public static List<BlockPos> wardsNear(Level level, BlockPos center, int radius) {
        List<BlockPos> result = new ArrayList<>();
        for (WardBlockEntity ward : LOADED) {
            if (ward.isRemoved() || ward.getLevel() != level || ward.isDisabled()) {
                continue;
            }
            BlockPos pos = ward.getBlockPos();
            if (Math.abs(pos.getX() - center.getX()) <= radius && Math.abs(pos.getZ() - center.getZ()) <= radius) {
                result.add(pos);
            }
        }
        return result;
    }
}
