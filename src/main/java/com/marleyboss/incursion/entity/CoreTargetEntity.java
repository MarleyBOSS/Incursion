package com.marleyboss.incursion.entity;

import com.marleyboss.incursion.block.CoreBlock;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.siege.MobRoles;
import com.marleyboss.incursion.siege.SiegeAI;
import com.marleyboss.incursion.siege.SiegeProgress;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Invisible, immobile stand-in for the Incursion Core. Hostile mobs target this entity, so every mob
 * attacks the Core in its own way (melee, arrows, explosions ...). Damage it receives from hostile mobs
 * is forwarded to the Core's health; the entity itself never loses health.
 * <p>
 * It is never saved to disk; the Core block entity respawns it when needed.
 */
public class CoreTargetEntity extends LivingEntity {
    private final NonNullList<ItemStack> armor = NonNullList.withSize(4, ItemStack.EMPTY);
    private int checkTimer = 0;

    public CoreTargetEntity(EntityType<? extends CoreTargetEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false; // players may still place blocks right next to / on top of the Core
        this.setNoGravity(true);
        this.setSilent(true);
    }

    @Override
    public void tick() {
        // Deliberately no super.tick(): this entity has no physics, AI or status effects.
        if (!level().isClientSide && ++checkTimer >= 20) {
            checkTimer = 0;
            if (findCore() == null) {
                discard();
            }
        }
    }

    private CoreBlockEntity findCore() {
        BlockState state = level().getBlockState(blockPosition());
        if (!(state.getBlock() instanceof CoreBlock) || !state.getValue(CoreBlock.ACTIVE)) {
            return null;
        }
        return level().getBlockEntity(blockPosition()) instanceof CoreBlockEntity core ? core : null;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide) {
            return false;
        }
        if (source.is(DamageTypes.GENERIC_KILL)) {
            discard();
            return true;
        }
        Entity attacker = source.getEntity();
        if (!(attacker instanceof Enemy) || attacker instanceof Player || amount <= 0) {
            return false;
        }
        CoreBlockEntity core = findCore();
        if (core == null || !(level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        long now = serverLevel.getGameTime();
        SiegeProgress.progress(blockPosition(), now);
        SiegeAI.markUseful(attacker, now);
        core.damageCore(serverLevel, amount * MobRoles.coreDamageMultiplier(attacker));
        return true;
    }

    // ------------------------------------------------------------------
    // No physics, no pushing, not selectable by the player's crosshair
    // ------------------------------------------------------------------

    @Override
    public boolean isPickable() {
        return false; // the player's crosshair goes through to the Core block
    }

    @Override
    public boolean canBeHitByProjectile() {
        return true; // arrows and other projectiles still hit the Core
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public void knockback(double strength, double x, double z) {
    }

    @Override
    public boolean isAffectedByPotions() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean shouldShowName() {
        return false;
    }

    // ------------------------------------------------------------------
    // LivingEntity boilerplate
    // ------------------------------------------------------------------

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return armor;
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }
}
