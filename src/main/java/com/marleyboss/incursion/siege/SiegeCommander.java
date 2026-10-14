package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.siege.MobRoles.Role;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The siege's "commander". Instead of every mob looking for its own way, the siege first analyses the
 * situation and makes one plan:
 * <ol>
 *     <li>Where can the Core be hit (a free spot right next to it; the wall there is dug open)?</li>
 *     <li>What is the cheapest way from the ground to that spot (walk, dig, build stairs up to the Core's
 *     height)? See {@link RoutePlanner}.</li>
 *     <li>Which workers does that need (builders for every block to place, miners for every block to dig)?</li>
 * </ol>
 * The attackers of a front spawn at its assembly point (the foot of the way). While the way is being built,
 * the siege sends the workers it needs first and only a few escorts; once the way is (almost) open, the
 * attackers follow. If a front lacks a builder or miner, an idle zombie of the front is retrained.
 * A second front is planned on another side once the first one is open (more mobs reach the Core at once).
 */
public final class SiegeCommander {
    private static final int NODES_PER_SECOND = 20000;
    /** Planning failed: try again after this many ticks. */
    private static final long RETRY_TICKS = 600;
    /** An unfinished way without any progress for this long is planned again. */
    private static final long STALE_TICKS = 3600;
    private static final long PROMOTE_TICKS = 100;
    /** A further front is planned at the earliest this long after the newest one. */
    private static final long NEXT_FRONT_DELAY = 200;
    /** Each front needs at least this many mobs of the siege's limit (otherwise fewer fronts are built). */
    private static final int MOBS_PER_FRONT = 5;
    /** With this many ways already planned, another one waits until the landings and platforms of the others are built. */
    private static final int EXPAND_FIRST_FROM = 3;
    /** ... but it waits at most this long after the newest way was planned. */
    private static final long EXPAND_MAX_WAIT = 2400;

    /** What the siege should spawn next: a mob for this front, with this role (null = an attacker). */
    public record SpawnOrder(SiegeFront front, @Nullable Role role) {
    }

    private static final List<SiegeFront> FRONTS = new ArrayList<>();
    @Nullable
    private static RoutePlanner job;
    @Nullable
    private static BlockPos corePos;
    private static long nextAttempt;
    private static boolean failed;
    private static int nextId = 1;

    private SiegeCommander() {
    }

    public static void clear() {
        FRONTS.clear();
        job = null;
        corePos = null;
        nextAttempt = 0;
        failed = false;
    }

    static List<SiegeFront> fronts() {
        return FRONTS;
    }

    /** True if no way to the Core could be found (the siege falls back to scattered spawns and air drops). */
    static boolean planFailed() {
        return failed && FRONTS.isEmpty();
    }

    static boolean isPlanning() {
        return job != null;
    }

    static boolean contains(@Nullable SiegeFront front) {
        return front != null && FRONTS.contains(front);
    }

    // ------------------------------------------------------------------
    // Once per second
    // ------------------------------------------------------------------

    static void tick(ServerLevel level, ActiveSiege siege, List<Mob> attackers, List<BlockPos> wards, int maxMobs,
                     long now) {
        if (!siege.corePos.equals(corePos)) {
            clear();
            corePos = siege.corePos;
        }

        // 1. Check every front: work left, still possible, still making progress?
        Iterator<SiegeFront> iterator = FRONTS.iterator();
        while (iterator.hasNext()) {
            SiegeFront front = iterator.next();
            int[] work = front.route.countWork(level);
            front.pendingBuild = work[0];
            front.pendingDig = work[1];
            front.hardDig = work[2];
            front.open = work[0] + work[1] == 0;
            front.platformWork = front.open ? front.countPlatformWork(level) : 0;
            front.landingWork = front.countLandingWork(level);
            boolean stale = !front.open && now - front.route.lastProgress() > STALE_TICKS
                    && now - front.created > STALE_TICKS;
            if (front.route.isInvalid() || stale) {
                iterator.remove();
                announce(level, siege, Component.translatable("message.incursion.plan.replan"));
            }
        }

        // 2. Who is on which front?
        for (SiegeFront front : FRONTS) {
            java.util.Arrays.fill(front.roleCounts, 0);
            front.members = 0;
            front.escorts = 0;
            front.orderedBuilders = 0;
            front.orderedMiners = 0;
            front.orderedOthers = 0;
        }
        for (Mob mob : attackers) {
            SiegeFront front = SiegeAI.frontOf(mob);
            if (front == null || !FRONTS.contains(front) || !isSiegeMob(mob)) {
                continue; // naturally spawned monsters may help, but the siege does not count on them
            }
            Role role = MobRoles.of(mob);
            front.roleCounts[role.ordinal()]++;
            front.members++;
            if (role != Role.BUILDER && role != Role.MINER) {
                front.escorts++;
            }
        }

        // 3. A front without the worker it needs retrains an idle zombie.
        for (SiegeFront front : FRONTS) {
            if (now - front.lastPromotion >= PROMOTE_TICKS) {
                promote(front, attackers, siege.coreLevel, maxMobs, now);
            }
        }

        // 4. Plan (another) front: several ways are built at the same time (more at higher levels).
        long newest = Long.MIN_VALUE / 2;
        for (SiegeFront front : FRONTS) {
            newest = Math.max(newest, front.created);
        }
        int frontsWanted = Math.min(Config.frontsFor(siege.coreLevel), Math.max(1, maxMobs / MOBS_PER_FRONT));
        // Weighing it up: from the third way on, the builders first expand the existing ways (landings, platforms)
        // before the siege starts yet another staircase.
        boolean expanding = false;
        if (FRONTS.size() >= EXPAND_FIRST_FROM && now - newest < EXPAND_MAX_WAIT) {
            for (SiegeFront front : FRONTS) {
                if (front.landingWork > 0 || front.platformWork > 0) {
                    expanding = true;
                    break;
                }
            }
        }
        boolean wanted = FRONTS.isEmpty()
                || (FRONTS.size() < frontsWanted && now - newest > NEXT_FRONT_DELAY && !expanding);
        if (job == null && wanted && now >= nextAttempt) {
            job = newPlanner(level, siege, wards, now);
        }
        if (job != null && job.step(NODES_PER_SECOND, now)) {
            RoutePlanner.Result result = job.result();
            job = null;
            if (result != null) {
                SiegeFront front = new SiegeFront(nextId++, result.route(), result.side(), result.platform(),
                        result.landings(), now);
                int[] work = front.route.countWork(level);
                front.pendingBuild = work[0];
                front.pendingDig = work[1];
                front.hardDig = work[2];
                front.open = work[0] + work[1] == 0;
                FRONTS.add(front);
                failed = false;
                BlockPos at = front.assembly();
                announce(level, siege, Component.translatable("message.incursion.plan.front",
                        Component.translatable("debug.incursion.side." + front.side.getName().toLowerCase(Locale.ROOT)),
                        work[0], work[1], work[2], at.getX(), at.getY(), at.getZ()));
                int landingSpots = 0;
                for (SiegeFront.Landing landing : front.landings) {
                    landingSpots += landing.spots().size();
                }
                announce(level, siege, Component.translatable("message.incursion.plan.extras",
                        front.platform.size(), front.landings.size(), landingSpots));
            } else {
                failed = true;
                nextAttempt = now + RETRY_TICKS;
                if (FRONTS.isEmpty()) {
                    announce(level, siege, Component.translatable("message.incursion.plan.failed"));
                }
            }
        }
    }

    private static RoutePlanner newPlanner(ServerLevel level, ActiveSiege siege, List<BlockPos> wards, long now) {
        LongOpenHashSet avoidClear = new LongOpenHashSet();
        LongOpenHashSet avoidSupport = new LongOpenHashSet();
        LongOpenHashSet otherNodes = new LongOpenHashSet();
        Set<Direction> usedSides = EnumSet.noneOf(Direction.class);
        collectAvoid(avoidClear, avoidSupport, otherNodes, usedSides);
        double assemblyDistance = Config.coreZoneRadius(siege.coreLevel) + 6.0;
        int bound = Math.max(12, siege.siegeRadius - 2);
        assemblyDistance = Math.min(assemblyDistance, bound - 4.0);
        return new RoutePlanner(level, siege.corePos, siege.coreLevel, bound, assemblyDistance, siege.elevation,
                siege.underground, wards, Config.WARD_RADIUS.get(), avoidClear, avoidSupport, otherNodes, usedSides,
                now);
    }

    /**
     * A planner for a way to a player (the hunt): the "Core" of the search is the player's position. It keeps away
     * from the ways to the Core (never builds into them, never digs their floors away).
     */
    static RoutePlanner newHuntPlanner(ServerLevel level, BlockPos target, int coreLevel, int bound,
                                       double assemblyDistance, boolean underground, List<BlockPos> wards, long now) {
        LongOpenHashSet avoidClear = new LongOpenHashSet();
        LongOpenHashSet avoidSupport = new LongOpenHashSet();
        LongOpenHashSet otherNodes = new LongOpenHashSet();
        collectAvoid(avoidClear, avoidSupport, otherNodes, EnumSet.noneOf(Direction.class));
        return new RoutePlanner(level, target, coreLevel, bound, assemblyDistance, 0, underground, wards,
                Config.WARD_RADIUS.get(), avoidClear, avoidSupport, otherNodes, EnumSet.noneOf(Direction.class), now);
    }

    /** Cells of the existing ways to the Core that a new plan must not use (see {@link RoutePlanner}). */
    private static void collectAvoid(LongOpenHashSet avoidClear, LongOpenHashSet avoidSupport,
                                     LongOpenHashSet otherNodes, Set<Direction> usedSides) {
        for (SiegeFront front : FRONTS) {
            usedSides.add(front.side);
            Route route = front.route;
            for (int i = 0; i < route.size(); i++) {
                BlockPos node = route.node(i);
                otherNodes.add(node.asLong());
                avoidClear.add(node.asLong());
                avoidClear.add(node.above().asLong());
                for (BlockPos cell : route.clears(i)) {
                    avoidClear.add(cell.asLong());
                }
                BlockPos support = route.support(i);
                if (support != null) {
                    avoidSupport.add(support.asLong());
                }
            }
            for (BlockPos spot : front.platform) {
                avoidClear.add(spot.asLong());
                avoidClear.add(spot.above().asLong());
                avoidSupport.add(spot.below().asLong());
            }
            for (SiegeFront.Landing landing : front.landings) {
                for (BlockPos spot : landing.spots()) {
                    avoidClear.add(spot.asLong());
                    avoidClear.add(spot.above().asLong());
                    avoidSupport.add(spot.below().asLong());
                }
            }
        }
    }

    static void announce(ServerLevel level, ActiveSiege siege, Component message) {
        if (!siege.showDebug) {
            return;
        }
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("[Incursion] ").withStyle(ChatFormatting.DARK_PURPLE)
                        .append(message.copy().withStyle(ChatFormatting.LIGHT_PURPLE)), false);
    }

    // ------------------------------------------------------------------
    // Roles
    // ------------------------------------------------------------------

    private static boolean buildersPossible() {
        return Config.BUILDING_ENABLED.get() && Config.ZOMBIE_ROLES.get();
    }

    /** The worker this front is missing right now (builder or miner), or null. */
    @Nullable
    static Role neededWorker(SiegeFront front, int maxMobs) {
        if (!Config.ZOMBIE_ROLES.get()) {
            return null;
        }
        int workersCap = Math.max(2, maxMobs * 2 / 3);
        int builders = 0;
        if (buildersPossible() && (front.pendingBuild > 0 || front.platformWork > 0 || front.landingWork > 0)) {
            builders = front.pendingBuild >= 10 && maxMobs >= 6 ? 2 : 1;
            if (front.landingWork > 0 && front.pendingBuild > 0 && maxMobs >= 6) {
                builders++; // one more builder expands the way (landings) while the others build the stairs
            }
            if (front.pendingBuild == 0 && front.platformWork + front.landingWork >= 8 && maxMobs >= 6) {
                builders = 2; // the way is open: two builders finish the platforms while the attack goes on
            }
        }
        int miners = 0;
        if (front.pendingDig > 0) {
            // Very hard blocks (obsidian): their dig progress adds up, so more miners are worth it.
            int wanted = front.hardDig > 0 ? Math.min(5, Config.HARD_DIG_WORKERS.get())
                    : (front.pendingDig >= 6 ? 2 : 1);
            miners = Math.max(1, Math.min(wanted, workersCap - builders));
        }
        if (front.builders() < builders) {
            return Role.BUILDER;
        }
        if (front.miners() < miners) {
            return Role.MINER;
        }
        return null;
    }

    /** An open front (way finished) with the fewest members, or null. */
    @Nullable
    static SiegeFront openFront() {
        SiegeFront best = null;
        for (SiegeFront front : FRONTS) {
            if (front.open && (best == null || front.members < best.members)) {
                best = front;
            }
        }
        return best;
    }

    static boolean isSiegeMob(Mob mob) {
        return mob.getTags().contains(SiegeManager.SIEGE_TAG);
    }

    /** The next mob the siege should spawn, or null if nothing is wanted right now. */
    @Nullable
    static SpawnOrder nextSpawn(int maxMobs) {
        if (FRONTS.isEmpty()) {
            return null;
        }
        // Workers first: the front that misses one most urgently.
        SiegeFront best = null;
        Role bestRole = null;
        for (SiegeFront front : FRONTS) {
            Role role = neededWorker(front, maxMobs);
            if (role != null && (best == null || front.members < best.members)) {
                best = front;
                bestRole = role;
            }
        }
        if (best != null) {
            return new SpawnOrder(best, bestRole);
        }
        // Then attackers (up to the siege's limit), to the front with the fewest members; open ways first.
        int bestScore = Integer.MAX_VALUE;
        for (SiegeFront front : FRONTS) {
            int score = front.members + (front.open ? 0 : 4);
            if (score < bestScore) {
                bestScore = score;
                best = front;
            }
        }
        return best != null ? new SpawnOrder(best, null) : null;
    }

    /** The next spawn is ordered: count it right away, so the next order of the same round is different. */
    static void noteOrdered(SpawnOrder order) {
        SiegeFront front = order.front();
        if (order.role() == Role.BUILDER) {
            front.orderedBuilders++;
        } else if (order.role() == Role.MINER) {
            front.orderedMiners++;
        } else {
            front.orderedOthers++;
        }
        front.members++;
    }

    /** True if a front has no builder (or no miner) at all although it needs one: the siege may exceed its limit. */
    static boolean urgentWorkerNeeded(int maxMobs) {
        for (SiegeFront front : FRONTS) {
            Role role = neededWorker(front, maxMobs);
            if (role == Role.BUILDER && front.builders() == 0) {
                return true;
            }
            if (role == Role.MINER && front.miners() == 0 && front.hardDig > 0) {
                return true;
            }
        }
        return false;
    }

    /** An idle zombie of this front becomes the builder or miner it is missing. */
    private static void promote(SiegeFront front, List<Mob> attackers, int coreLevel, int maxMobs, long now) {
        Role needed = neededWorker(front, maxMobs);
        if (needed == null) {
            return;
        }
        boolean missing = needed == Role.BUILDER ? front.builders() == 0 : front.miners() == 0;
        if (!missing) {
            return;
        }
        Mob candidate = null;
        int candidateScore = Integer.MAX_VALUE;
        for (Mob mob : attackers) {
            if (!(mob instanceof Zombie zombie) || !MobRoles.isZombieRoleType(zombie) || zombie.isBaby()
                    || SiegeAI.frontOf(mob) != front || mob.isPassenger() || !isSiegeMob(mob)) {
                continue;
            }
            // Only a zombie that is already on the way or at its foot (not one still stuck somewhere).
            SiegeAI.MobState state = SiegeAI.stateOf(mob);
            boolean onWay = state != null && state.routeIndex >= 0;
            if (!onWay && mob.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(front.assembly())) > 6.0) {
                continue;
            }
            Role role = MobRoles.of(mob);
            int score = switch (role) {
                case NONE -> 0;
                case WARRIOR -> 1;
                case BREAKER -> 2;
                case MINER -> needed == Role.BUILDER && front.miners() >= 2 ? 3 : Integer.MAX_VALUE;
                default -> Integer.MAX_VALUE;
            };
            if (score < candidateScore) {
                candidateScore = score;
                candidate = mob;
            }
        }
        if (candidate != null) {
            Role old = MobRoles.of(candidate);
            MobRoles.setRole((Zombie) candidate, needed, coreLevel);
            front.lastPromotion = now;
            front.roleCounts[old.ordinal()] = Math.max(0, front.roleCounts[old.ordinal()] - 1);
            front.roleCounts[needed.ordinal()]++;
            if (old != Role.BUILDER && old != Role.MINER) {
                front.escorts = Math.max(0, front.escorts - 1);
            }
        }
    }

    // ------------------------------------------------------------------
    // Assignment
    // ------------------------------------------------------------------

    /** The front a mob without one should join: the closest assembly point (crowded fronts count as farther). */
    @Nullable
    static SiegeFront assign(Mob mob) {
        SiegeFront best = null;
        double bestScore = Double.MAX_VALUE;
        for (SiegeFront front : FRONTS) {
            double distance = Math.sqrt(front.assembly().distToCenterSqr(mob.position()));
            double score = distance + front.members * 2.0;
            if (score < bestScore) {
                bestScore = score;
                best = front;
            }
        }
        return best;
    }
}
