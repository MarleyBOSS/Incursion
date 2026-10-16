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
 * Siege mobs must not block each other on the way (a crowd at the foot of a tower or on a bridge would jam the climb
 * and the builders), but they must not stand inside each other when they fight either (the player would hit only one
 * of them). So they are put on a scoreboard team with collision rule "never" while no player is near, and taken off it
 * (normal pushing) as soon as a player is within {@link Config#COLLISION_RADIUS} blocks. Builders are essential and
 * are always on the "never" team.
 */
public final class SiegeTeam {
    /** The team of mobs that walk through each other (collision rule "never"). */
    public static final String NAME = "incursion_siege";
    /** Team of v0.9.0.3 (builders only). Members are moved to {@link #NAME}; the team itself stays empty. */
    private static final String OLD_BUILDERS = "incursion_builders";
    /** Extra distance a mob must be away from the players before it turns into a "walk-through" mob again (no flicker). */
    private static final int HYSTERESIS = 3;

    private SiegeTeam() {
    }

    private static boolean isOurs(@Nullable Team team) {
        return team != null && (NAME.equals(team.getName()) || OLD_BUILDERS.equals(team.getName()));
    }

    /**
     * Puts the attacker on or off the "never" team (called every second): builders always on it, all others only
     * while no player is near. A mob that belongs to a team of another mod is left alone.
     */
    public static void join(Mob mob) {
        Team current = mob.getTeam();
        if (current != null && !isOurs(current)) {
            return;
        }
        boolean onTeam = current != null && NAME.equals(current.getName());
        boolean ghost = MobRoles.of(mob) == MobRoles.Role.BUILDER;
        if (!ghost && Config.NO_MOB_COLLISION.get()) {
            int radius = Config.COLLISION_RADIUS.get();
            // Players inside the radius make the mob solid; a mob that is already solid needs a bit more distance first.
            ghost = radius <= 0 || mob.level().getNearestPlayer(mob, onTeam ? radius : radius + HYSTERESIS) == null;
        }
        if (!ghost) {
            leave(mob);
            return;
        }
        if (onTeam) {
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
