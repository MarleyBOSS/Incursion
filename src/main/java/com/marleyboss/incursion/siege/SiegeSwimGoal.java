package com.marleyboss.incursion.siege;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;

import java.util.EnumSet;

/**
 * Siege mobs swim instead of sinking to the bottom (like the vanilla float goal, but only for mobs the siege
 * tracks, so mobs elsewhere behave as before). Runs next to the drive goal: it only uses the jump control.
 */
public class SiegeSwimGoal extends Goal {
    public static final int PRIORITY = 0;

    private final Mob mob;

    public SiegeSwimGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!SiegeAI.isTracked(mob)) {
            return false;
        }
        boolean inWater = mob.isInWater() && mob.getFluidHeight(FluidTags.WATER) > mob.getFluidJumpThreshold();
        if (inWater && mob.getNavigation() instanceof GroundPathNavigation navigation) {
            navigation.setCanFloat(true); // paths may lead through water (back to shore)
        }
        return inWater || mob.isInLava();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (mob.getRandom().nextFloat() < 0.8F) {
            mob.getJumpControl().jump();
        }
    }
}
