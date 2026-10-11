package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.siege.MobRoles.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The hunter team's own builders and miners. While a player is close to the Core, the hunt plans a way to him with
 * the same {@link RoutePlanner} as the siege uses for the Core (the "Core" of the search is the player's position):
 * a tower, a hole in the ground, a bunker ... Hunter builders and miners (zombies tagged {@link #WORKER_TAG}) build and
 * dig that way, so the hunters, who chase the player the normal way, can finally reach him (vanilla pathfinding finds
 * the finished stairs). If the player stands somewhere reachable, the plan needs no work and nobody is sent.
 * The plan is made again when the player moves; the hunt ends when he leaves the area near the Core.
 */
public final class PlayerHunt {
    /** Scoreboard tag of the hunter team's builders and miners. */
    public static final String WORKER_TAG = "incursion_hunt_worker";

    private static final int NODES_PER_SECOND = 20000;
    private static final long REPLAN_TICKS = 100;
    private static final double REPLAN_DISTANCE = 3.0;
    private static final long RETRY_TICKS = 200;
    private static final long STALE_TICKS = 2400;
    private static final long LOST_PLAYER_TICKS = 200;
    private static final long NO_FRONT_TICKS = 200;
    private static final long IDLE_TICKS = 100;
    private static final int BOUND = 32;
    private static final double ASSEMBLY_DISTANCE = 9.0;
    private static final int VERTICAL_RANGE = 48;

    /** What the hunt wants spawned next: a worker with this role for this way. */
    public record SpawnOrder(SiegeFront front, Role role) {
    }

    private static final class Hunt {
        final UUID player;
        Component name = Component.empty();
        @Nullable
        SiegeFront front;
        @Nullable
        BlockPos target;
        long lastSeen;
        long nextAttempt;
        long openSince = -1;
        boolean announced;

        Hunt(UUID player) {
            this.player = player;
        }
    }

    private static final Map<UUID, Hunt> HUNTS = new LinkedHashMap<>();
    @Nullable
    private static RoutePlanner job;
    @Nullable
    private static Hunt jobHunt;
    @Nullable
    private static BlockPos jobTarget;
    private static long noFrontSince = -1;
    private static int nextId = 1000;

    private PlayerHunt() {
    }

    public static void clear() {
        HUNTS.clear();
        job = null;
        jobHunt = null;
        jobTarget = null;
        noFrontSince = -1;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** True for the hunter team's builders and miners. */
    public static boolean isWorker(Mob mob) {
        return mob.getTags().contains(WORKER_TAG);
    }

    /** The planned ways to hunted players (for the AI view). */
    static List<SiegeFront> fronts() {
        List<SiegeFront> result = new ArrayList<>();
        for (Hunt hunt : HUNTS.values()) {
            if (hunt.front != null) {
                result.add(hunt.front);
            }
        }
        return result;
    }

    static boolean contains(@Nullable SiegeFront front) {
        if (front == null) {
            return false;
        }
        for (Hunt hunt : HUNTS.values()) {
            if (hunt.front == front) {
                return true;
            }
        }
        return false;
    }

    /** The way a hunter worker without one should join: the closest assembly point (crowded ways count as farther). */
    @Nullable
    static SiegeFront assign(Mob mob) {
        SiegeFront best = null;
        double bestScore = Double.MAX_VALUE;
        for (Hunt hunt : HUNTS.values()) {
            SiegeFront front = hunt.front;
            if (front == null || front.open) {
                continue;
            }
            double score = Math.sqrt(front.assembly().distToCenterSqr(mob.position())) + front.members * 4.0;
            if (score < bestScore) {
                bestScore = score;
                best = front;
            }
        }
        return best;
    }

    @Nullable
    private static Hunt huntOf(@Nullable SiegeFront front) {
        if (front == null) {
            return null;
        }
        for (Hunt hunt : HUNTS.values()) {
            if (hunt.front == front) {
                return hunt;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Once per second
    // ------------------------------------------------------------------

    static void tick(ServerLevel level, ActiveSiege siege, List<Mob> attackers, List<BlockPos> wards, int maxMobs,
                     long now) {
        if (!Config.HUNT_ENABLED.get() || !Config.BUILDING_ENABLED.get() || !Config.ZOMBIE_ROLES.get()) {
            if (!HUNTS.isEmpty()) {
                clear();
            }
            recallAll(level, attackers);
            return;
        }

        // 1. Which players are hunted: close to the Core, closest first.
        double radius = Math.max(16.0, Math.min(Config.HUNT_RADIUS.get(), siege.siegeRadius - 12.0));
        List<Player> candidates = new ArrayList<>();
        for (Player player : level.players()) {
            if (!Targeting.isValidPlayer(player)) {
                continue;
            }
            double dx = player.getX() - (siege.corePos.getX() + 0.5);
            double dz = player.getZ() - (siege.corePos.getZ() + 0.5);
            if (dx * dx + dz * dz <= radius * radius && Math.abs(player.getY() - siege.corePos.getY()) <= VERTICAL_RANGE) {
                candidates.add(player);
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distanceToSqr(
                siege.corePos.getX() + 0.5, siege.corePos.getY(), siege.corePos.getZ() + 0.5)));
        int limit = Config.HUNT_MAX_PLAYERS.get();
        Map<UUID, Player> hunted = new LinkedHashMap<>();
        for (Player player : candidates) {
            if (hunted.size() >= limit) {
                break;
            }
            hunted.put(player.getUUID(), player);
        }
        // Teams: with players near the Core a share of the fighters hunts them, otherwise all join the Core team.
        MobRoles.rebalanceTeams(attackers, candidates);
        if (MobRoles.hunterCount() == 0) {
            hunted.keySet().removeIf(id -> !HUNTS.containsKey(id)); // no hunters: no new way for them
        }
        for (Player player : hunted.values()) {
            Hunt hunt = HUNTS.computeIfAbsent(player.getUUID(), Hunt::new);
            hunt.name = player.getDisplayName();
            hunt.lastSeen = now;
        }
        Iterator<Hunt> iterator = HUNTS.values().iterator();
        while (iterator.hasNext()) {
            Hunt hunt = iterator.next();
            if (!hunted.containsKey(hunt.player) && now - hunt.lastSeen > LOST_PLAYER_TICKS) {
                if (jobHunt == hunt) {
                    job = null;
                    jobHunt = null;
                }
                iterator.remove();
            }
        }

        // 2. Check every way: work left, still possible, still making progress?
        for (Hunt hunt : HUNTS.values()) {
            SiegeFront front = hunt.front;
            if (front == null) {
                continue;
            }
            int[] work = front.route.countWork(level);
            front.pendingBuild = work[0];
            front.pendingDig = work[1];
            front.hardDig = work[2];
            front.open = work[0] + work[1] == 0;
            boolean stale = !front.open && now - front.route.lastProgress() > STALE_TICKS
                    && now - front.created > STALE_TICKS;
            if (front.route.isInvalid() || stale) {
                hunt.front = null;
                hunt.openSince = -1;
            } else if (front.open) {
                if (hunt.openSince < 0) {
                    hunt.openSince = now;
                    if (hunt.announced) {
                        SiegeCommander.announce(level, siege,
                                Component.translatable("message.incursion.plan.hunt_open", hunt.name));
                    }
                    hunt.announced = false;
                }
            } else {
                hunt.openSince = -1;
            }
        }

        // 3. Who works on which way?
        for (Hunt hunt : HUNTS.values()) {
            SiegeFront front = hunt.front;
            if (front != null) {
                java.util.Arrays.fill(front.roleCounts, 0);
                front.members = 0;
                front.escorts = 0;
                front.orderedBuilders = 0;
                front.orderedMiners = 0;
                front.orderedOthers = 0;
            }
        }
        // A way that still needs work: only then are workers of the hunt useful.
        boolean anyFront = false;
        for (Hunt hunt : HUNTS.values()) {
            anyFront |= hunt.front != null && !hunt.front.open;
        }
        if (anyFront) {
            noFrontSince = -1;
        } else if (noFrontSince < 0) {
            noFrontSince = now;
        }
        for (Mob mob : attackers) {
            if (!isWorker(mob)) {
                continue;
            }
            Hunt hunt = huntOf(SiegeAI.frontOf(mob));
            if (hunt == null) {
                if (!anyFront && now - noFrontSince > NO_FRONT_TICKS) {
                    join(mob); // nobody to hunt any more: back to the Core team
                }
                continue;
            }
            SiegeFront front = hunt.front;
            front.roleCounts[MobRoles.of(mob).ordinal()]++;
            front.members++;
            if (hunt.openSince >= 0 && now - hunt.openSince > IDLE_TICKS) {
                join(mob); // the way is finished and nothing is left to do: back to the Core team
            }
        }

        // 4. Plan (again): when there is no way yet, or the player has moved on.
        if (job == null) {
            for (Hunt hunt : HUNTS.values()) {
                Player player = hunted.get(hunt.player);
                if (player == null || now < hunt.nextAttempt) {
                    continue;
                }
                BlockPos at = player.blockPosition();
                SiegeFront front = hunt.front;
                boolean moved = hunt.target == null || movedAway(hunt.target, at);
                if (front == null || (moved && now - front.created >= REPLAN_TICKS)) {
                    job = SiegeCommander.newHuntPlanner(level, at, siege.coreLevel, BOUND, ASSEMBLY_DISTANCE,
                            SiegeManager.isUnderground(level, at), wards, now);
                    jobHunt = hunt;
                    jobTarget = at;
                    break;
                }
            }
        }
        if (job != null && job.step(NODES_PER_SECOND, now)) {
            RoutePlanner.Result result = job.result();
            Hunt hunt = jobHunt;
            BlockPos target = jobTarget;
            job = null;
            jobHunt = null;
            jobTarget = null;
            if (hunt != null && target != null && HUNTS.containsValue(hunt)) {
                hunt.nextAttempt = now + RETRY_TICKS;
                if (result != null && insideArea(siege, result.route().node(0))) {
                    accept(level, siege, hunt, target, result, now);
                }
            }
        }
    }

    private static boolean movedAway(BlockPos planned, BlockPos now) {
        double dx = planned.getX() - now.getX();
        double dz = planned.getZ() - now.getZ();
        return dx * dx + dz * dz > REPLAN_DISTANCE * REPLAN_DISTANCE || Math.abs(planned.getY() - now.getY()) > 2;
    }

    /** The assembly point must lie inside the siege area (mobs outside of it are called back). */
    private static boolean insideArea(ActiveSiege siege, BlockPos pos) {
        double limit = siege.siegeRadius - 2.0;
        return Math.abs(pos.getX() + 0.5 - (siege.corePos.getX() + 0.5)) <= limit
                && Math.abs(pos.getZ() + 0.5 - (siege.corePos.getZ() + 0.5)) <= limit;
    }

    private static void accept(ServerLevel level, ActiveSiege siege, Hunt hunt, BlockPos target,
                               RoutePlanner.Result result, long now) {
        SiegeFront front = new SiegeFront(nextId++, result.route(), result.side(), List.of(), List.of(), now);
        int[] work = front.route.countWork(level);
        front.pendingBuild = work[0];
        front.pendingDig = work[1];
        front.hardDig = work[2];
        front.open = work[0] + work[1] == 0;
        hunt.front = front;
        hunt.target = target;
        hunt.nextAttempt = now + REPLAN_TICKS;
        hunt.openSince = front.open ? now : -1;
        if (!front.open && !hunt.announced) {
            hunt.announced = true;
            BlockPos at = front.assembly();
            SiegeCommander.announce(level, siege, Component.translatable("message.incursion.plan.hunt", hunt.name,
                    work[0], work[1], at.getX(), at.getY(), at.getZ()));
        }
    }

    private static void recallAll(ServerLevel level, List<Mob> attackers) {
        for (Mob mob : attackers) {
            if (isWorker(mob)) {
                join(mob);
            }
        }
    }

    /**
     * A hunter builder or miner with nothing to do goes over to the Core team: it loses the hunt tag and the siege
     * commander gives it a way to the Core (the builder builds there, the miner digs there).
     */
    private static void join(Mob mob) {
        mob.removeTag(WORKER_TAG);
    }

    // ------------------------------------------------------------------
    // Spawning (called by the SiegeManager)
    // ------------------------------------------------------------------

    /** The hunter worker the hunt needs next (a builder or miner for a way that still needs work), or null. */
    @Nullable
    static SpawnOrder nextSpawn(int maxMobs) {
        int cap = Config.HUNT_WORKERS.get();
        SiegeFront best = null;
        Role bestRole = null;
        for (Hunt hunt : HUNTS.values()) {
            SiegeFront front = hunt.front;
            if (front == null || front.open || front.builders() + front.miners() >= cap) {
                continue;
            }
            Role role = SiegeCommander.neededWorker(front, Math.max(maxMobs, 6));
            if (role != null && (best == null || front.members < best.members)) {
                best = front;
                bestRole = role;
            }
        }
        return best != null ? new SpawnOrder(best, bestRole) : null;
    }

    static void noteOrdered(SpawnOrder order) {
        SiegeFront front = order.front();
        if (order.role() == Role.BUILDER) {
            front.orderedBuilders++;
        } else {
            front.orderedMiners++;
        }
        front.members++;
    }
}
