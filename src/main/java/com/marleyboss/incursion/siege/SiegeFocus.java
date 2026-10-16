package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.block.WardBlockEntity;
import com.marleyboss.incursion.entity.CoreTargetEntity;
import com.marleyboss.incursion.siege.MobRoles.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * What a siege mob attacks: normally the Core, but the mobs of a front that has a ward totem as its focus
 * (see {@link SiegeFront#ward}) attack that totem as long as it is switched on. Sappers always bomb the Core.
 */
final class SiegeFocus {
    private SiegeFocus() {
    }

    /** Fighters re-check the nearest totem this often (ticks). */
    private static final int RECHECK_TICKS = 10;
    /** A totem only draws a fighter away from its current target when it is this much closer (blocks). */
    private static final double SWITCH_MARGIN = 6.0;
    /** ... and only when it is at most this far away (blocks). */
    private static final double SHORTCUT_RANGE = 32.0;

    /** The totem a fighter chose last time, and until when that choice is kept. */
    private record Pick(long until, @Nullable BlockPos ward) {
    }

    private static final Map<Mob, Pick> PICKS = new WeakHashMap<>();

    /**
     * The switched-on ward totem this mob attacks, or null (then the Core is the target). Normally that is the
     * totem of its front (see {@link SiegeFront#ward}); but a fighter keeps looking for the nearest totem: when
     * another switched-on totem is clearly closer than its current target, it attacks that one instead.
     */
    @Nullable
    static BlockPos wardOf(Mob mob, ActiveSiege siege) {
        BlockPos frontWard = frontWardOf(mob, siege);
        if (!Config.WARD_FOCUS.get() || !MobRoles.isFighter(MobRoles.of(mob)) || siege.wards.isEmpty()) {
            return frontWard;
        }
        long now = siege.level.getGameTime();
        Pick pick = PICKS.get(mob);
        if (pick != null && now < pick.until && (pick.ward == null || WardBlockEntity.targetAt(siege.level, pick.ward) != null)) {
            return pick.ward;
        }
        BlockPos current = pick != null && pick.ward != null && WardBlockEntity.targetAt(siege.level, pick.ward) != null
                ? pick.ward : frontWard;
        BlockPos chosen = current;
        double currentDistance = horizontalDistance(mob, current != null ? current : siege.corePos);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos ward : siege.wards) {
            double distance = horizontalDistance(mob, ward);
            if (distance < nearestDistance && WardBlockEntity.targetAt(siege.level, ward) != null) {
                nearestDistance = distance;
                nearest = ward;
            }
        }
        if (nearest != null && !nearest.equals(current) && nearestDistance <= SHORTCUT_RANGE
                && nearestDistance < currentDistance - SWITCH_MARGIN) {
            chosen = nearest;
        }
        PICKS.put(mob, new Pick(now + RECHECK_TICKS, chosen));
        return chosen;
    }

    private static double horizontalDistance(Mob mob, BlockPos pos) {
        double dx = mob.getX() - (pos.getX() + 0.5);
        double dz = mob.getZ() - (pos.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The totem this mob's front is attacking (switched on), or null. */
    @Nullable
    private static BlockPos frontWardOf(Mob mob, ActiveSiege siege) {
        SiegeFront front = SiegeAI.frontOf(mob);
        if (front == null || front.ward == null || MobRoles.of(mob) == Role.SAPPER) {
            return null;
        }
        return WardBlockEntity.targetAt(siege.level, front.ward) != null ? front.ward : null;
    }

    /** The entity this mob should attack (the invisible stand-in of the Core or of the focus totem). */
    static LivingEntity target(Mob mob, ActiveSiege siege) {
        BlockPos ward = wardOf(mob, siege);
        if (ward != null) {
            CoreTargetEntity target = WardBlockEntity.targetAt(siege.level, ward);
            if (target != null) {
                return target;
            }
        }
        return siege.target;
    }

    /** The block this mob attacks (the Core or the focus totem). */
    static BlockPos pos(Mob mob, ActiveSiege siege) {
        BlockPos ward = wardOf(mob, siege);
        return ward != null ? ward : siege.corePos;
    }
}
