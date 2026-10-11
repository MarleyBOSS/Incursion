package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Game-bus event handlers that enforce the zone rules between the once-per-second sweeps. */
public final class SiegeEvents {
    private SiegeEvents() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SiegeEvents::onChangeTarget);
        NeoForge.EVENT_BUS.addListener(SiegeEvents::onExplosion);
        NeoForge.EVENT_BUS.addListener(SiegeEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(SiegeEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(Digging::onEntityTick);
    }

    /** Zombie roles: warriors hit players harder, breakers softer. */
    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player) || event.getEntity().level().isClientSide) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        if (attacker instanceof Mob) {
            float multiplier = ZombieRoles.playerDamageMultiplier(attacker);
            if (multiplier != 1.0F) {
                event.setAmount(event.getAmount() * multiplier);
            }
        }
    }

    /**
     * Core zone: mobs never switch to a player. Siege area: mobs only switch to players that are close,
     * and fall back to the Core instead of having no target.
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
        if (zone == ActiveSiege.ZONE_CORE) {
            redirect = newTarget == null || newTarget instanceof Player;
        } else {
            double range = siege.playerAggroRange;
            redirect = newTarget == null
                    || (newTarget instanceof Player player && player.distanceToSqr(mob) > range * range);
        }
        if (redirect) {
            event.setNewAboutToBeSetTarget(siege.target);
        }
    }

    /**
     * Creepers inside the core zone do not destroy blocks (unless enabled in the config).
     * Breach creepers (stuck at a wall) always do, so they clear the way. No explosion removes the Core block.
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
        event.getAffectedBlocks().removeIf(pos -> pos.equals(siege.corePos));
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ActiveSiege.clear();
        Digging.clear();
    }
}
