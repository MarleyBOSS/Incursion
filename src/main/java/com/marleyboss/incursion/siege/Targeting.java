package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.siege.MobRoles.Role;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.jetbrains.annotations.Nullable;

/**
 * Who attacks what:
 * <ul>
 *     <li>Workers (miners, breakers, builders, sappers) always go for the Core.</li>
 *     <li>Warriors hunt players within the guard range in every zone, also right next to the Core.</li>
 *     <li>Archers shoot the Core when they can see it. While they cannot, they give cover fire on players.
 *     Players that come very close are always shot. Blast archers keep shooting at the Core.</li>
 *     <li>Everybody else: Core zone = Core only, siege area = nearby players, otherwise the Core.</li>
 * </ul>
 */
public final class Targeting {
    private static final double SELF_DEFENCE_RANGE = 8.0;
    private static final double CORE_SHOT_RANGE = 24.0;
    private static final double WARRIOR_BLIND_RANGE = 12.0;

    private Targeting() {
    }

    public static boolean isRanged(Mob mob) {
        return MobRoles.of(mob).isArcher() || mob instanceof RangedAttackMob
                || mob.getMainHandItem().getItem() instanceof ProjectileWeaponItem;
    }

    public static boolean isValidPlayer(Player player) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator();
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
        if (role == Role.WARRIOR) {
            return distance <= guardRange;
        }
        if (isRanged(mob)) {
            if (distance <= SELF_DEFENCE_RANGE) {
                return true;
            }
            if (role == Role.BLAST_ARCHER || distance > guardRange) {
                return false;
            }
            boolean coreInSight = mob.distanceToSqr(siege.target) <= CORE_SHOT_RANGE * CORE_SHOT_RANGE
                    && mob.getSensing().hasLineOfSight(siege.target);
            return !coreInSight;
        }
        if (zone == ActiveSiege.ZONE_CORE) {
            return false;
        }
        return distance <= siege.playerAggroRange;
    }

    /** The closest player this mob should fight right now, or null. */
    @Nullable
    public static Player findPlayer(Mob mob, ActiveSiege siege, int zone) {
        double range = Math.max(Config.GUARD_RANGE.get(), siege.playerAggroRange);
        boolean ranged = isRanged(mob);
        boolean warrior = MobRoles.of(mob) == Role.WARRIOR;
        Player best = null;
        double bestDistance = range * range;
        for (Player player : mob.level().players()) {
            double distance = player.distanceToSqr(mob);
            if (distance > bestDistance || !prefersPlayer(mob, player, siege, zone)) {
                continue;
            }
            boolean visible = mob.getSensing().hasLineOfSight(player);
            if (ranged && !visible) {
                continue;
            }
            if (warrior && !visible && distance > WARRIOR_BLIND_RANGE * WARRIOR_BLIND_RANGE) {
                continue; // do not chase players hiding behind walls far away
            }
            best = player;
            bestDistance = distance;
        }
        return best;
    }

    /** Once per second: point the mob at the right target. */
    public static void update(Mob mob, ActiveSiege siege, int zone) {
        LivingEntity current = mob.getTarget();
        if (current instanceof Player player && prefersPlayer(mob, player, siege, zone)) {
            return;
        }
        if (current != null && current != siege.target && !(current instanceof Player) && current.isAlive()
                && zone != ActiveSiege.ZONE_CORE) {
            return; // fighting an iron golem or similar
        }
        Player player = findPlayer(mob, siege, zone);
        LivingEntity desired = player != null ? player : siege.target;
        if (current != desired) {
            mob.setTarget(desired);
        }
    }
}
