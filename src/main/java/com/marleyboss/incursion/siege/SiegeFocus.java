package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.block.WardBlockEntity;
import com.marleyboss.incursion.entity.CoreTargetEntity;
import com.marleyboss.incursion.siege.MobRoles.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * What a siege mob attacks: normally the Core, but the mobs of a front that has a ward totem as its focus
 * (see {@link SiegeFront#ward}) attack that totem as long as it is switched on. Sappers always bomb the Core.
 */
final class SiegeFocus {
    private SiegeFocus() {
    }

    /** The switched-on ward totem this mob's front is attacking, or null (then the Core is the target). */
    @Nullable
    static BlockPos wardOf(Mob mob, ActiveSiege siege) {
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
