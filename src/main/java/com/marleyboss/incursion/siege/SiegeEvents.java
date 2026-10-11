package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.entity.CoreTargetEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Game-bus event handlers that enforce the siege rules between the once-per-second sweeps. */
public final class SiegeEvents {
    private SiegeEvents() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SiegeEvents::onChangeTarget);
        NeoForge.EVENT_BUS.addListener(SiegeEvents::onExplosion);
        NeoForge.EVENT_BUS.addListener(SiegeEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(SiegeEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(SiegeAI::onEntityTick);
        NeoForge.EVENT_BUS.addListener(SiegeAI::onJoin);
        NeoForge.EVENT_BUS.addListener(SiegeExplosives::onJoin);
        NeoForge.EVENT_BUS.addListener(SiegeExplosives::onImpact);
        NeoForge.EVENT_BUS.addListener(SiegeAirDrop::onEntityTick);
        NeoForge.EVENT_BUS.addListener(SiegeEvents::onEnderTeleport);
        NeoForge.EVENT_BUS.addListener(SiegeEvents::onSpawnCheck);
        NeoForge.EVENT_BUS.addListener(SiegeTeam::onLeaveLevel);
    }

    /** Underground Core: caves this close to the Core's height belong to the siege. */
    private static final int CAVE_BAND = 10;

    /**
     * No natural monster spawns in caves inside the siege area: they cannot reach the Core, cost performance and
     * fill the area. Only if the Core itself is underground (a cave base), caves at about its height are left alone.
     */
    private static void onSpawnCheck(MobSpawnEvent.PositionCheck event) {
        if (event.getSpawnType() != MobSpawnType.NATURAL || !Config.BLOCK_CAVE_SPAWNS.get()) {
            return;
        }
        Mob mob = event.getEntity();
        if (!(mob instanceof Enemy)) {
            return;
        }
        ActiveSiege siege = ActiveSiege.get(mob.level());
        if (siege == null || siege.zoneOf(mob) == ActiveSiege.ZONE_OUTSIDE) {
            return;
        }
        BlockPos pos = BlockPos.containing(event.getX(), event.getY(), event.getZ());
        int surface = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        if (pos.getY() >= surface - 1) {
            return; // on the surface
        }
        if (siege.underground && Math.abs(pos.getY() - siege.corePos.getY()) <= CAVE_BAND) {
            return;
        }
        event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
    }

    /**
     * Siege endermen do not teleport away from the Core by themselves (vanilla endermen teleport randomly in
     * daylight and when hit), otherwise they wander off and are never seen at the Core. In water or rain they
     * may still escape.
     */
    private static void onEnderTeleport(EntityTeleportEvent.EnderEntity event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide) {
            return;
        }
        ActiveSiege siege = ActiveSiege.get(mob.level());
        if (siege == null || !SiegeRules.isAttacker(mob) || siege.zoneOf(mob) == ActiveSiege.ZONE_OUTSIDE
                || mob.isInWaterRainOrBubble()) {
            return;
        }
        Vec3 core = Vec3.atCenterOf(siege.corePos);
        double before = mob.position().distanceTo(core);
        double after = new Vec3(event.getTargetX(), event.getTargetY(), event.getTargetZ()).distanceTo(core);
        if (after > before + 2.0) {
            event.setCanceled(true);
        }
    }

    /** Role damage against players; a siege mob hitting a player counts as progress. */
    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player) || event.getEntity().level().isClientSide) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        if (attacker instanceof Mob mob) {
            float multiplier = MobRoles.playerDamageMultiplier(attacker);
            if (multiplier != 1.0F) {
                event.setAmount(event.getAmount() * multiplier);
            }
            ActiveSiege siege = ActiveSiege.get(mob.level());
            if (siege != null && SiegeRules.isAttacker(mob)) {
                long now = mob.level().getGameTime();
                SiegeProgress.progress(null, now);
                SiegeAI.markUseful(mob, now);
            }
        }
    }

    /**
     * Keeps every attacker on the target its role wants (see {@link Targeting}); a lost target falls back
     * to the Core.
     */
    private static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide) {
            return;
        }
        ActiveSiege siege = ActiveSiege.get(mob.level());
        if (siege == null || !SiegeRules.isAttacker(mob)) {
            return;
        }
        int zone = siege.zoneOf(mob);
        if (zone == ActiveSiege.ZONE_OUTSIDE) {
            return;
        }
        LivingEntity newTarget = event.getNewAboutToBeSetTarget();
        if (newTarget == siege.target) {
            return;
        }
        boolean redirect;
        if (newTarget instanceof Player player) {
            redirect = !Targeting.prefersPlayer(mob, player, siege, zone);
        } else {
            redirect = newTarget == null || (zone == ActiveSiege.ZONE_CORE && MobRoles.of(mob).isWorker());
        }
        if (redirect) {
            event.setNewAboutToBeSetTarget(siege.target);
        }
    }

    /**
     * <ul>
     *     <li>Creepers inside the core zone do not destroy blocks (unless enabled in the config).
     *     Breach creepers always do, so they clear the way.</li>
     *     <li>Explosions caused by the siege (creepers, explosive arrows, their TNT, self-destruct)
     *     do not hurt other siege mobs, and count as progress when they break blocks.</li>
     *     <li>No explosion removes the Core block or siege-proof blocks.</li>
     * </ul>
     */
    private static void onExplosion(ExplosionEvent.Detonate event) {
        ActiveSiege siege = ActiveSiege.get(event.getLevel());
        if (siege == null) {
            return;
        }
        Entity source = event.getExplosion().getDirectSourceEntity();
        if (source instanceof Creeper) {
            boolean breach = source.getTags().contains(Digging.BREACH_TAG);
            if (!breach && !Config.CREEPER_BLOCK_DAMAGE.get() && siege.zoneOf(source) == ActiveSiege.ZONE_CORE) {
                event.getAffectedBlocks().clear();
            }
        }
        if (source instanceof Creeper creeper && creeper.getTags().contains(SiegeAI.CORE_BOMBER_TAG)) {
            // A creeper that blew itself up right next to the Core: full damage, even if the Core is half covered.
            event.getAffectedEntities().removeIf(entity -> entity instanceof CoreTargetEntity);
            float radius = creeper.isPowered() ? 6.0F : 3.0F;
            double diameter = radius * 2.0;
            double distance = creeper.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(siege.corePos));
            double impact = Math.max(0.0, 1.0 - distance / diameter);
            float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0);
            siege.target.hurt(creeper.damageSources().mobAttack(creeper), damage);
        }
        Level level = event.getLevel();
        event.getAffectedBlocks().removeIf(pos -> pos.equals(siege.corePos) || level.getBlockState(pos).is(Digging.SIEGE_PROOF));

        if (isSiegeExplosion(source)) {
            if (Config.SIEGE_EXPLOSIONS_SPARE_MOBS.get()) {
                event.getAffectedEntities().removeIf(entity -> entity instanceof Mob mob && !(entity instanceof CoreTargetEntity)
                        && SiegeRules.isAttacker(mob));
            }
            if (!event.getAffectedBlocks().isEmpty()) {
                SiegeProgress.progress(event.getAffectedBlocks().get(0), level.getGameTime());
            }
        }
    }

    private static boolean isSiegeExplosion(Entity source) {
        if (source == null) {
            return false;
        }
        Entity cause = source;
        if (source instanceof PrimedTnt tnt) {
            cause = tnt.getOwner();
        } else if (source instanceof Projectile projectile) {
            cause = projectile.getOwner();
        }
        return cause instanceof Mob mob && (SiegeRules.isAttacker(mob) || mob.getTags().contains(SiegeManager.SIEGE_TAG));
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ActiveSiege.clear();
        SiegeAI.clear();
    }
}
