package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Attackers are put on a scoreboard team whose members do not push each other. Vanilla mobs constantly shove
 * each other around, which on a one block wide staircase or ladder pushes the builder off or blocks the way.
 * Players (not on the team) still push and are pushed as usual.
 */
public final class SiegeTeam {
    public static final String NAME = "incursion_siege";

    private SiegeTeam() {
    }

    @Nullable
    private static PlayerTeam team(Scoreboard scoreboard, boolean create) {
        PlayerTeam team = scoreboard.getPlayerTeam(NAME);
        if (team == null && create) {
            team = scoreboard.addPlayerTeam(NAME);
        }
        if (team != null && team.getCollisionRule() != Team.CollisionRule.NEVER) {
            team.setCollisionRule(Team.CollisionRule.NEVER);
        }
        return team;
    }

    /** Puts an attacker on the team (unless it already belongs to another team, e.g. from another mod). */
    public static void join(Mob mob) {
        if (!Config.NO_MOB_COLLISION.get() || mob.getTeam() != null) {
            return;
        }
        Scoreboard scoreboard = mob.level().getScoreboard();
        PlayerTeam team = team(scoreboard, true);
        if (team != null) {
            scoreboard.addPlayerToTeam(mob.getScoreboardName(), team);
        }
    }

    /** Takes the mob off the team again (it left the siege). */
    public static void leave(Entity entity) {
        PlayerTeam current = entity.getTeam();
        if (current != null && NAME.equals(current.getName())) {
            entity.level().getScoreboard().removePlayerFromTeam(entity.getScoreboardName(), current);
        }
    }

    /** Dead or removed mobs are taken off the team, so the scoreboard does not fill up with old entries. */
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason != null && reason.shouldDestroy()) {
            leave(entity);
        }
    }
}
