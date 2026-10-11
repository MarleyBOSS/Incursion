package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.siege.MobRoles.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Explosive and fire arrows, and the TNT that sapper endermen put next to the Core
 * (fire archers shoot burning arrows at it to ignite it).
 */
public final class SiegeExplosives {
    public static final String EXPLOSIVE_ARROW_TAG = "incursion_explosive_arrow";
    private static final double TNT_SHOT_RANGE = 32.0;

    /** Placed TNT blocks (position -> game time placed). */
    private static final Map<Long, Long> TNT = new HashMap<>();

    private SiegeExplosives() {
    }

    // ------------------------------------------------------------------
    // Arrows
    // ------------------------------------------------------------------

    /** Arrows of fire archers burn, arrows of blast archers explode. */
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractArrow arrow)) {
            return;
        }
        Entity owner = arrow.getOwner();
        if (owner == null) {
            return;
        }
        Role role = MobRoles.of(owner);
        if (role == Role.FIRE_ARCHER) {
            arrow.igniteForSeconds(100.0F);
        } else if (role == Role.BLAST_ARCHER) {
            arrow.addTag(EXPLOSIVE_ARROW_TAG);
        }
    }

    public static void onImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (!(projectile.level() instanceof ServerLevel level)) {
            return;
        }
        HitResult hit = event.getRayTraceResult();
        // Siege arrows fly through other siege mobs (no friendly fire, no blocked shots in a crowd).
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof Mob victim
                && projectile.getOwner() instanceof Mob owner && SiegeRules.isAttacker(owner) && SiegeRules.isAttacker(victim)) {
            event.setCanceled(true);
            return;
        }
        if (projectile.getTags().contains(EXPLOSIVE_ARROW_TAG)) {
            projectile.removeTag(EXPLOSIVE_ARROW_TAG);
            Vec3 at = hit.getLocation();
            level.explode(projectile, at.x, at.y, at.z, Config.BLAST_ARROW_POWER.get().floatValue(),
                    Level.ExplosionInteraction.TNT);
            projectile.discard();
            return;
        }
        if (projectile.isOnFire() && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            Entity owner = projectile.getOwner();
            if (owner instanceof Mob mob && SiegeRules.isAttacker(mob) && level.getBlockState(pos).is(Blocks.TNT)) {
                prime(level, pos, mob);
            }
        }
    }

    /** Shoots one arrow from the mob at a point (the arrow gets its class effect when it joins the level). */
    public static void shoot(ServerLevel level, Mob shooter, Vec3 aim) {
        Vec3 from = new Vec3(shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ());
        double speed = 2.4;
        Vec3 direction = SiegeAim.solve(from, aim, speed);
        if (direction == null) {
            double dx = aim.x - from.x;
            double dz = aim.z - from.z;
            direction = new Vec3(dx, aim.y - from.y + Math.sqrt(dx * dx + dz * dz) * 0.2, dz);
        }
        shoot(level, shooter, direction, speed, aim);
    }

    /** Shoots one arrow in a given direction (see {@link SiegeAim#solve}). */
    public static void shoot(ServerLevel level, Mob shooter, Vec3 direction, double speed, Vec3 lookAt) {
        ItemStack weapon = shooter.getMainHandItem();
        Arrow arrow = new Arrow(level, shooter, new ItemStack(Items.ARROW), weapon.isEmpty() ? null : weapon.copy());
        arrow.shoot(direction.x, direction.y, direction.z, (float) speed, 1.0F);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        shooter.getLookControl().setLookAt(lookAt.x, lookAt.y, lookAt.z);
        shooter.swing(InteractionHand.MAIN_HAND);
        level.addFreshEntity(arrow);
        level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.SKELETON_SHOOT,
                SoundSource.HOSTILE, 1.0F, 1.0F / (level.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    /** Is there a clear line from the mob's eyes to this block? */
    public static boolean canSeeBlock(Level level, Mob mob, BlockPos pos) {
        Vec3 eyes = mob.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eyes, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    // ------------------------------------------------------------------
    // TNT
    // ------------------------------------------------------------------

    public static void trackTnt(BlockPos pos, long now) {
        TNT.put(pos.asLong(), now);
    }

    /** Once per second: fire archers shoot at placed TNT; old TNT ignites by itself. */
    public static void tickTnt(ServerLevel level, List<Mob> fireArchers, long now) {
        Iterator<Map.Entry<Long, Long>> iterator = TNT.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Long> entry = iterator.next();
            BlockPos pos = BlockPos.of(entry.getKey());
            if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.TNT)) {
                iterator.remove();
                continue;
            }
            int fallback = Config.TNT_FUSE_FALLBACK_SECONDS.get();
            if (fallback >= 0 && now - entry.getValue() > fallback * 20L) {
                iterator.remove();
                prime(level, pos, fireArchers.isEmpty() ? null : fireArchers.get(0));
                continue;
            }
            if (fireArchers.isEmpty()) {
                SiegeProgress.need(SiegeProgress.Need.FIRE_ARCHER, now);
                continue;
            }
            Vec3 aim = Vec3.atCenterOf(pos);
            for (Mob archer : fireArchers) {
                if (archer.distanceToSqr(aim) > TNT_SHOT_RANGE * TNT_SHOT_RANGE || !SiegeAI.readyToShoot(archer, now)
                        || !canSeeBlock(level, archer, pos)) {
                    continue;
                }
                shoot(level, archer, aim);
                SiegeAI.shotFired(archer, now + 40);
                break;
            }
        }
    }

    /** Turns a TNT block into lit TNT. The owner decides whether the blast counts as a siege explosion. */
    public static void prime(ServerLevel level, BlockPos pos, @Nullable LivingEntity owner) {
        TNT.remove(pos.asLong());
        level.removeBlock(pos, false);
        PrimedTnt tnt = new PrimedTnt(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, owner);
        level.addFreshEntity(tnt);
        level.playSound(null, tnt.getX(), tnt.getY(), tnt.getZ(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    public static void clear() {
        TNT.clear();
    }
}
