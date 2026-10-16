package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.siege.MobRoles.Role;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.jetbrains.annotations.Nullable;

/**
 * Who attacks what. The fighters (warriors and archers) are split into two teams:
 * <ul>
 *     <li><b>Hunters</b> go for players within the hunter range, and for every player close to the Core
 *     (they defend the Core's attackers). Hunter archers hold posts on the landings of the stairs.</li>
 *     <li>The <b>Core team</b> goes for the Core: workers (miners, breakers, builders, sappers) never fight players;
 *     the other fighters are their bodyguards and only attack players that come close.</li>
 *     <li>Archers of the Core team shoot the Core (they search a firing position). While they find none, they give
 *     cover fire on players.</li>
 *     <li>Everybody else: Core zone = Core only, siege area = nearby players, otherwise the Core.</li>
 * </ul>
 */
public final class Targeting {
    private static final double SELF_DEFENCE_RANGE = 8.0;
    private static final double WARRIOR_BLIND_RANGE = 12.0;
    /** Hunters also go for players this close to the Core (beyond the Core zone), from this far away. */
    private static final double DEFEND_MARGIN = 12.0;
    private static final double DEFEND_RANGE = 96.0;

    private Targeting() {
    }

    public static boolean isRanged(Mob mob) {
        return MobRoles.of(mob).isArcher() || mob instanceof RangedAttackMob
                || mob.getMainHandItem().getItem() instanceof ProjectileWeaponItem;
    }

    public static boolean isValidPlayer(Player player) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator();
    }

    /** Is the player close to the Core (within the Core zone plus a margin)? */
    private static boolean nearCore(Player player, ActiveSiege siege) {
        // The same area in which the hunt builds ways to players (see PlayerHunt).
        double reach = Math.max(siege.coreZoneRadius + DEFEND_MARGIN,
                Math.max(16.0, Math.min(Config.HUNT_RADIUS.get(), siege.siegeRadius - 12.0)));
        double dx = player.getX() - (siege.corePos.getX() + 0.5);
        double dz = player.getZ() - (siege.corePos.getZ() + 0.5);
        double dy = player.getY() - siege.corePos.getY();
        return dx * dx + dz * dz + dy * dy <= reach * reach;
    }

    /** Should this mob fight the player instead of attacking the Core? */
    public static boolean prefersPlayer(Mob mob, Player player, ActiveSiege siege, int zone) {
        if (!isValidPlayer(player) || player.level() != mob.level()) {
            return false;
        }
        Role role = MobRoles.of(mob);
        if (role.isWorker()) {
            return false;
        }
        double distance = mob.distanceTo(player);
        int guardRange = Config.GUARD_RANGE.get();
        boolean fighter = MobRoles.isFighter(role);
        if (fighter && MobRoles.isHunter(mob)) {
            return distance <= Config.HUNTER_RANGE.get() || (distance <= DEFEND_RANGE && nearCore(player, siege));
        }
        if (role == Role.WARRIOR) {
            return distance <= Config.BODYGUARD_RANGE.get(); // bodyguard of the Core team
        }
        if (isRanged(mob)) {
            if (distance <= Math.max(SELF_DEFENCE_RANGE, fighter ? Config.BODYGUARD_RANGE.get() : 0)) {
                return true;
            }
            if (distance > guardRange) {
                return false;
            }
            // Cover fire only while the archer has found no way to shoot at the Core.
            return SiegeArchers.mayGiveCoverFire(mob, mob.level().getGameTime());
        }
        if (zone == ActiveSiege.ZONE_CORE) {
            return false;
        }
        return distance <= siege.playerAggroRange;
    }

    /** The closest player this mob should fight right now, or null. */
    @Nullable
    public static Player findPlayer(Mob mob, ActiveSiege siege, int zone) {
        double range = Math.max(Math.max(Config.GUARD_RANGE.get(), siege.playerAggroRange), Config.HUNTER_RANGE.get());
        if (MobRoles.isHunter(mob)) {
            range = Math.max(range, DEFEND_RANGE);
        }
        boolean ranged = isRanged(mob);
        boolean hunter = MobRoles.isHunter(mob);
        boolean warrior = MobRoles.of(mob) == Role.WARRIOR;
        Player best = null;
        double bestDistance = range * range;
        for (Player player : mob.level().players()) {
            double distance = player.distanceToSqr(mob);
            if (distance > bestDistance || !prefersPlayer(mob, player, siege, zone)) {
                continue;
            }
            boolean visible = mob.getSensing().hasLineOfSight(player);
            if (ranged && !visible && !hunter) {
                continue; // hunters hunt through walls (towers, bunkers): their builders make the way
            }
            if (warrior && !hunter && !visible && distance > WARRIOR_BLIND_RANGE * WARRIOR_BLIND_RANGE) {
                continue; // do not chase players hiding behind walls far away
            }
            best = player;
            bestDistance = distance;
        }
        return best;
    }

    /**
     * True for a non-player target the mob may keep fighting: something that attacks it (an iron golem, a
     * tamed wolf ...). Villagers, animals and the like are ignored: the siege is about the Core.
     */
    public static boolean fightsBack(Mob mob, LivingEntity other) {
        if (other instanceof AbstractGolem || other == mob.getLastHurtByMob()) {
            return true;
        }
        return other instanceof Mob attacker && attacker.getTarget() == mob;
    }

    /** Once per second: point the mob at the right target. */
    public static void update(Mob mob, ActiveSiege siege, int zone) {
        LivingEntity current = mob.getTarget();
        if (current instanceof Player player && prefersPlayer(mob, player, siege, zone)) {
            return;
        }
        LivingEntity focus = SiegeFocus.target(mob, siege);
        if (current != null && current != focus && !(current instanceof Player) && current.isAlive()
                && zone != ActiveSiege.ZONE_CORE && fightsBack(mob, current)) {
            return; // fighting an iron golem or similar
        }
        Player player = findPlayer(mob, siege, zone);
        LivingEntity desired = player != null ? player : focus;
        if (current != desired) {
            mob.setTarget(desired);
        }
    }
}
