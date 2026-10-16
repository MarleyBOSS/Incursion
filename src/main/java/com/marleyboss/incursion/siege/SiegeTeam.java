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
 * Siege mobs must not block each other: a crowd at the foot of a tower or on a bridge would otherwise jam the climb
 * and the builders. All siege mobs are therefore put on a scoreboard team whose collision rule is "never": nobody is
 * pushed by them and they push nobody, so they simply walk through each other.
 */
public final class SiegeTeam {
    /** The team all siege mobs are on (collision rule "never"). */
    public static final String NAME = "incursion_siege";
    /** Team of v0.9.0.3 (builders only). Members are moved to {@link #NAME}; the team itself stays empty. */
    private static final String OLD_BUILDERS = "incursion_builders";

    private SiegeTeam() {
    }

    private static boolean isOurs(@Nullable Team team) {
        return team != null && (NAME.equals(team.getName()) || OLD_BUILDERS.equals(team.getName()));
    }

    /** Puts the attacker on the "never" team (called every second). A mob that belongs to a team of another mod is left alone. */
    public static void join(Mob mob) {
        if (!Config.NO_MOB_COLLISION.get()) {
            leave(mob);
            return;
        }
        Team current = mob.getTeam();
        if (current != null && !isOurs(current)) {
            return;
        }
        if (current != null && NAME.equals(current.getName())) {
            return;
        }
        Scoreboard scoreboard = mob.level().getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(NAME);
        if (team == null) {
            team = scoreboard.addPlayerTeam(NAME);
        }
        if (team.getCollisionRule() != Team.CollisionRule.NEVER) {
            team.setCollisionRule(Team.CollisionRule.NEVER);
        }
        scoreboard.addPlayerToTeam(mob.getScoreboardName(), team); // also moves it off the old builders team
    }

    /** Takes the mob off our teams again (it left the siege). */
    public static void leave(Entity entity) {
        PlayerTeam current = entity.getTeam();
        if (isOurs(current)) {
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
