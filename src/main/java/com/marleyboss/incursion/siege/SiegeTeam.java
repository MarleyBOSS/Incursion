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
 * Builders are essential: if one is pushed off the stairs or cannot pass the crowd waiting in front of it, the whole
 * siege stands still. They are therefore put on a scoreboard team whose collision rule is "never": a builder is not
 * pushed by anybody and pushes nobody, so it simply walks through the others. All other siege mobs have no team and
 * push each other like normal mobs (a crowd of zombies should not be able to squeeze through everything).
 */
public final class SiegeTeam {
    /** The team builders are on (collision rule "never"). */
    public static final String BUILDERS = "incursion_builders";
    /** Old team of v0.7.8 to v0.9.0.1 (all siege mobs were on it). Members are taken off it, its rule is made normal. */
    public static final String LEGACY = "incursion_siege";

    private SiegeTeam() {
    }

    @Nullable
    private static PlayerTeam team(Scoreboard scoreboard, String name, Team.CollisionRule rule, boolean create) {
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null && create) {
            team = scoreboard.addPlayerTeam(name);
        }
        if (team != null && team.getCollisionRule() != rule) {
            team.setCollisionRule(rule);
        }
        return team;
    }

    private static boolean isOurs(@Nullable Team team) {
        return team != null && (BUILDERS.equals(team.getName()) || LEGACY.equals(team.getName()));
    }

    /**
     * Keeps an attacker on the right team (called every second): builders on the "never" team, everybody else on no
     * team. A mob that belongs to a team of another mod is left alone.
     */
    public static void join(Mob mob) {
        Team current = mob.getTeam();
        if (current != null && !isOurs(current)) {
            return;
        }
        Scoreboard scoreboard = mob.level().getScoreboard();
        boolean builder = Config.BUILDER_NO_PUSH.get() && MobRoles.of(mob) == MobRoles.Role.BUILDER;
        if (builder) {
            if (current != null && BUILDERS.equals(current.getName())) {
                return;
            }
            PlayerTeam team = team(scoreboard, BUILDERS, Team.CollisionRule.NEVER, true);
            if (team != null) {
                scoreboard.addPlayerToTeam(mob.getScoreboardName(), team); // moves it off the legacy team
            }
        } else if (current != null) {
            leave(mob);
        }
        // The legacy team no longer stops pushing (its old members are taken off it one by one).
        PlayerTeam legacy = scoreboard.getPlayerTeam(LEGACY);
        if (legacy != null && legacy.getCollisionRule() != Team.CollisionRule.ALWAYS) {
            legacy.setCollisionRule(Team.CollisionRule.ALWAYS);
        }
    }

    /** Takes the mob off our teams again (it left the siege or is no builder). */
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
