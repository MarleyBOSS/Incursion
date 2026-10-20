package com.marleyboss.incursion.entity;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.fallout.Fallout;
import com.marleyboss.incursion.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * The meltdown of a Core: invisible entity that stands where the Core stood. For {@link #FUSE} ticks the Core charges (the
 * client draws it brighter and bigger, see CoreBlastRenderer), then the real explosion starts ({@link Fallout#detonate}) and
 * the entity stays another {@link #AFTER} ticks for the animation of the blast. It is saved, so a restart during the fuse does
 * not swallow the explosion. It is kept loaded by nobody: if the chunk is unloaded, the fuse goes on when it loads again.
 */
public class CoreBlastEntity extends Entity {
    /** Ticks of charging before the explosion. The sound (core_meltdown) is timed to it. */
    public static final int FUSE = 110;
    /** Ticks the blast is animated after the explosion. */
    public static final int AFTER = 150;

    private static final EntityDataAccessor<Long> START = SynchedEntityData.defineId(CoreBlastEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> STAGE = SynchedEntityData.defineId(CoreBlastEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> RADIUS = SynchedEntityData.defineId(CoreBlastEntity.class, EntityDataSerializers.INT);

    private int coreLevel = 1;
    private boolean exploded;

    public CoreBlastEntity(EntityType<? extends CoreBlastEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /** Starts the meltdown of the Core that stood at {@code pos}. */
    public static void start(ServerLevel level, BlockPos pos, int coreLevel, int stage) {
        CoreBlastEntity blast = new CoreBlastEntity(com.marleyboss.incursion.registry.ModEntities.CORE_BLAST.get(), level);
        blast.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        blast.coreLevel = Math.max(1, coreLevel);
        blast.entityData.set(START, level.getGameTime());
        blast.entityData.set(STAGE, stage);
        blast.entityData.set(RADIUS, Config.craterRadius(blast.coreLevel));
        level.addFreshEntity(blast);
        level.playSound(null, pos, ModSounds.CORE_MELTDOWN.get(), SoundSource.BLOCKS, 8.0F, 1.0F);
        Component warning = Component.translatable("message.incursion.core.meltdown").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(blast) < 160.0 * 160.0) {
                player.displayClientMessage(warning, true);
            }
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(START, 0L);
        builder.define(STAGE, 0);
        builder.define(RADIUS, 10);
    }

    public long getStart() {
        return entityData.get(START);
    }

    /** The stage of the Sarcophagus the Core had (sets the size of the glow). */
    public int getStage() {
        return entityData.get(STAGE);
    }

    /** The crater radius, which sets the size of the blast. */
    public int getBlastRadius() {
        return entityData.get(RADIUS);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        long age = server.getGameTime() - getStart();
        if (age > 0 && age < FUSE) {
            // thunder cracks that get closer together
            if (age == 30 || age == 55 || age == 75 || age == 90 || age == 100 || age == 106) {
                server.playSound(null, blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 6.0F,
                        0.9F + age * 0.004F);
            }
        }
        if (!exploded && age >= FUSE) {
            exploded = true;
            Fallout.detonate(server, BlockPos.containing(getX(), getY() + 0.1, getZ()), coreLevel);
        }
        if (exploded && age < FUSE + 40 && age % 3 == 0) {
            // heavy smoke and sparks on top of the animation
            double reach = 1.0 + (age - FUSE) * 0.4;
            server.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1.0, getZ(), 25, reach, reach * 0.5, reach, 0.06);
            server.sendParticles(ParticleTypes.LAVA, getX(), getY() + 1.0, getZ(), 6, reach, 0.5, reach, 0.0);
        }
        if (age >= FUSE + AFTER) {
            discard();
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(START, tag.getLong("Start"));
        entityData.set(STAGE, tag.getInt("Stage"));
        entityData.set(RADIUS, Math.max(2, tag.getInt("Radius")));
        coreLevel = Math.max(1, tag.getInt("CoreLevel"));
        exploded = tag.getBoolean("Exploded");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putLong("Start", entityData.get(START));
        tag.putInt("Stage", entityData.get(STAGE));
        tag.putInt("Radius", entityData.get(RADIUS));
        tag.putInt("CoreLevel", coreLevel);
        tag.putBoolean("Exploded", exploded);
    }
}
