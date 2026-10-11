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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
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
