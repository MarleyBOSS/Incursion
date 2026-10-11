package com.marleyboss.incursion.siege;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Added to every hostile mob. While the siege AI has an order for the mob (follow a route, stand still to
 * dig or build, fuse before self-destruct), this goal takes over movement and looking from the vanilla
 * goals. Without an order it does nothing.
 */
public class SiegeDriveGoal extends Goal {
    public static final int PRIORITY = 1;

    private final Mob mob;

    public SiegeDriveGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return SiegeAI.wantsControl(mob);
    }

    @Override
    public boolean canContinueToUse() {
        return SiegeAI.wantsControl(mob);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        SiegeAI.drive(mob);
    }
}
