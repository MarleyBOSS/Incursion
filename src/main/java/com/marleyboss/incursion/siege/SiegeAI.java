package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.entity.CoreTargetEntity;
import com.marleyboss.incursion.registry.ModBlocks;
import com.marleyboss.incursion.siege.MobRoles.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.SpellcasterIllager;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-mob siege behaviour, evaluated every few ticks for every attacker in the siege area:
 * <ul>
 *     <li>Mobs next to the Core hit it directly (no vanilla line of sight needed, see {@link SiegeAim}).</li>
 *     <li>Archers look for a firing position and shoot from there ({@link SiegeArchers}).</li>
 *     <li>Stuck mobs (no progress towards the Core for a few seconds) join or plan a {@link Route}.
 *     Along the route, diggers dig, builders place siege stone, creepers blow up walls,
 *     endermen take blocks away. Mobs that cannot do the next job wait a few steps behind.
 *     A mob that does not get from one route step to the next hops there.</li>
 *     <li>Sapper endermen place their TNT next to the Core.</li>
 *     <li>A mob that achieved nothing for a long time blows itself up.</li>
 * </ul>
 */
public final class SiegeAI {
    static final int INTERVAL = 5;
    private static final double REACH = 4.5;
    private static final double FAR_REACH = 6.0;
    private static final double MELEE_REACH = 2.3;
    private static final double CREEPER_REACH = 2.5;
    private static final int LOOKAHEAD = 24;
    private static final double ESCORT_RADIUS = 12.0;
    private static final int FUSE_TICKS = 40;
    private static final double SAPPER_RANGE = 20.0;
    /** How far a stuck mob walks to help on another route. */
    private static final double HELP_RANGE = 40.0;
    /** Give up walking to another route after this many ticks. */
    private static final long APPROACH_TICKS = 400;
    /** A mob that should walk but has not reached the next route step for this long hops there. */
    private static final long HOP_TICKS = 40;
    /** Creepers do not try to blow up blocks this explosion resistant (obsidian, siege stone ...). */
    private static final float BREACH_MAX_RESISTANCE = 100.0F;
    /** Mobs stuck at least this far from the Core are recalled (see config recallSeconds). */
    private static final double RECALL_DISTANCE = 24.0;
    /** Only work this close to the Core makes the siege spawn missing roles. */
    private static final double NEED_RANGE = 40.0;
    /** A spider that got no closer for this long tries a detour. */
    private static final long CLIMBER_STUCK_TICKS = 100;
    /** At most this many workers work at the front of one route at the same time. */
    private static final int MAX_FRONT_WORKERS = 2;
    /** Riders get off a spider that is this close to the Core, or stuck. */
    private static final double DISMOUNT_RANGE = 4.0;

    /** Scoreboard tag on creepers that blow themselves up right next to the Core. */
    public static final String CORE_BOMBER_TAG = "incursion_core_bomber";

    enum Mode { NONE, ROUTE, HOLD, GOTO, DIRECT }

    enum Kind {
        NONE(false, false),
        MELEE(true, false),
        CREEPER(true, false),
        ENDERMAN(true, false),
        BUILDER(true, true);

        final boolean canDig;
        final boolean canBuild;

        Kind(boolean canDig, boolean canBuild) {
            this.canDig = canDig;
            this.canBuild = canBuild;
        }
    }

    static final class MobState {
        double bestDistance = Double.MAX_VALUE;
        long lastCloser;
        long lastUseful;
        /** Closest distance to the Core so far, and when it was reached. */
        double nearest = Double.MAX_VALUE;
        /** Last time the mob really achieved something (got closer than ever, hit, shot, dug, built). */
        long lastEffective;
        @Nullable
        Route route;
        int routeIndex;
        int bestIndex;
        int walkLimit;
        int lastIndex = -1;
        long lastAdvance;
        @Nullable
        BlockPos actionPos;
        int actionTicks;
        @Nullable
        Vec3 lookAt;
        Mode mode = Mode.NONE;
        long controlUntil;
        @Nullable
        BlockPos gotoPos;
        @Nullable
        Route approach;
        long approachStart;
        long noApproachUntil;
        long fuseStart = -1;
        long nextShot;
        long nextAttack;
        long nextTeleport;
        // Archers
        @Nullable
        BlockPos spot;
        @Nullable
        BlockPos spotWall;
        long spotSince;
        long nextSpotSearch;
        int pathFailures;
        long noSpotUntil;
        int blockedShots;
        // Spiders
        @Nullable
        Vec3 directTarget;
        long detourUntil;
        // Debug display
        String status = "idle";
        Object[] statusArgs = new Object[0];

        MobState(long now) {
            this.lastCloser = now;
            this.lastUseful = now;
            this.lastAdvance = now;
            this.lastEffective = now;
        }

        void effective(long now) {
            this.lastUseful = now;
            this.lastEffective = now;
        }

        void status(String key, Object... args) {
            this.status = key;
            this.statusArgs = args;
        }
    }

    private static final Map<Mob, MobState> MOBS = new WeakHashMap<>();

    private SiegeAI() {
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    /** Every hostile ground mob gets the (normally idle) drive goal. */
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PathfinderMob mob) || !(mob instanceof Enemy)) {
            return;
        }
        boolean present = false;
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof SiegeDriveGoal) {
                present = true;
                break;
            }
        }
        if (!present) {
            mob.goalSelector.addGoal(SiegeDriveGoal.PRIORITY, new SiegeDriveGoal(mob));
        }
        MobRoles.prepareGoals(mob);
    }

    // ------------------------------------------------------------------
    // Hooks used by other classes
    // ------------------------------------------------------------------

    static boolean wantsControl(Mob mob) {
        MobState state = MOBS.get(mob);
        return state != null && state.mode != Mode.NONE && mob.level().getGameTime() <= state.controlUntil;
    }

    @Nullable
    static MobState stateOf(Mob mob) {
        return MOBS.get(mob);
    }

    /** The attacker hit the Core or a player. */
    public static void markUseful(@Nullable Entity attacker, long now) {
        if (attacker instanceof Mob mob) {
            MobState state = MOBS.get(mob);
            if (state != null) {
                state.effective(now);
            }
        }
    }

    static boolean readyToShoot(Mob mob, long now) {
        MobState state = MOBS.get(mob);
        return state == null || state.nextShot <= now;
    }

    static void shotFired(Mob mob, long nextShot) {
        MobState state = MOBS.get(mob);
        if (state != null) {
            state.nextShot = nextShot;
        }
    }

    public static void clear() {
        MOBS.clear();
        Route.clear();
        Digging.clear();
        SiegeExplosives.clear();
        SiegeProgress.clear();
        SiegeArchers.clear();
    }

    // ------------------------------------------------------------------
    // Main tick
    // ------------------------------------------------------------------

    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if ((mob.tickCount + mob.getId()) % INTERVAL != 0 || !mob.isAlive()) {
            return;
        }
        ActiveSiege siege = ActiveSiege.get(level);
        if (siege == null || !SiegeRules.isAttacker(mob) || siege.zoneOf(mob) == ActiveSiege.ZONE_OUTSIDE) {
            forget(mob);
            SiegeDebug.clearName(mob);
            if (siege != null && mob.getTags().contains(SiegeManager.SIEGE_TAG) && !mob.isPassenger()
                    && siege.zoneOf(mob) == ActiveSiege.ZONE_OUTSIDE) {
                recall(level, mob); // wandered out of the siege area: it would stay there forever
            }
            return;
        }
        long now = level.getGameTime();
        Digging.cleanup(level, now);
        MobState state = MOBS.computeIfAbsent(mob, m -> new MobState(now));
        tickMob(level, mob, state, siege, now);
        if (siege.showDebug) {
            SiegeDebug.showName(mob, state);
        } else {
            SiegeDebug.clearName(mob);
        }
    }

    private static void tickMob(ServerLevel level, Mob mob, MobState state, ActiveSiege siege, long now) {
        Role role = MobRoles.of(mob);

        if (state.fuseStart >= 0) {
            tickFuse(level, mob, state, now);
            return;
        }

        // Carried by a phantom or riding a spider: the vehicle does the walking.
        if (mob.isPassenger()) {
            tickRider(level, mob, state, siege, now);
            return;
        }

        // Is this mob doing anything useful?
        LivingEntity target = mob.getTarget();
        if (target instanceof Player player && mob.distanceToSqr(player) < 16 * 16) {
            state.lastUseful = now;
        }
        if (SiegeProgress.recentActionNear(mob.blockPosition(), ESCORT_RADIUS, now)) {
            state.lastUseful = now; // escorting the workers
        }
        double distance = mob.distanceTo(siege.target);
        if (isLost(mob, state, distance, now)) {
            if (mob.getTags().contains(SiegeManager.SIEGE_TAG)) {
                recall(level, mob); // stuck far away (e.g. in a cave): make room for a new attacker
            } else {
                release(mob, siege); // a natural mob: let it go, it may despawn normally
            }
            return;
        }
        int selfDestruct = Config.SELF_DESTRUCT_SECONDS.get();
        if (selfDestruct > 0 && now - state.lastUseful > selfDestruct * 20L) {
            startFuse(level, mob, state, now);
            return;
        }

        if (role == Role.SAPPER && mob instanceof EnderMan enderman && carriesTnt(enderman)) {
            tickSapper(level, enderman, state, siege, now);
            return;
        }

        if (target != siege.target) {
            dropRoute(state, now, distance);
            state.status(target instanceof Player ? "player" : "other_target");
            return;
        }

        boolean ranged = Targeting.isRanged(mob);
        if (ranged) {
            if (SiegeArchers.tick(level, mob, state, siege, now)) {
                leaveRoute(mob, state);
                return; // shooting, or walking to a firing position
            }
            // No firing position it can walk to: follow the siege's routes like everybody else.
            if (state.mode == Mode.GOTO && state.approach == null) {
                state.mode = Mode.NONE;
            }
        } else if (tryAttackCore(level, mob, state, siege, now, distance)) {
            return;
        }

        if (mob instanceof Spider) {
            tickClimber(level, mob, state, siege, now, distance);
            return;
        }
        if (mob instanceof EnderMan enderman && state.route == null
                && tryTeleportToCore(level, enderman, state, siege, now, distance)) {
            return;
        }

        Kind kind = ranged ? Kind.NONE : kindOf(mob, role);
        if (state.route == null) {
            if (state.approach != null) {
                if (!tickApproach(mob, state, siege, now)) {
                    return;
                }
            } else {
                if (state.mode == Mode.HOLD) {
                    state.mode = Mode.NONE; // was attacking the Core, which is no longer in reach
                }
                if (distance < state.bestDistance - 1.0) {
                    state.bestDistance = distance;
                    state.lastCloser = now;
                    state.lastUseful = now;
                    state.status("approach");
                    return;
                }
                long stuck = now - state.lastCloser;
                if (stuck < Config.STUCK_SECONDS.get() * 20L || !canUseRoutes(mob)) {
                    state.status(stuck > 40 ? "stuck" : "approach", stuck / 20);
                    return;
                }
                Route route = acquireRoute(level, mob, state, kind, siege, now);
                if (route == null) {
                    return;
                }
                state.route = route;
            }
            state.bestIndex = state.routeIndex;
            state.lastIndex = state.routeIndex;
            state.lastAdvance = now;
            state.actionPos = null;
            state.actionTicks = 0;
        }
        followRoute(level, mob, state, kind, siege, now, distance);
    }

    // ------------------------------------------------------------------
    // Hitting the Core
    // ------------------------------------------------------------------

    /**
     * A melee mob (or creeper) close enough to an exposed face of the Core attacks it directly.
     * This does not depend on vanilla line of sight, so a Core with a block on top can still be hit.
     */
    private static boolean tryAttackCore(ServerLevel level, Mob mob, MobState state, ActiveSiege siege, long now,
                                         double distance) {
        Vec3 eyes = mob.getEyePosition();
        double reach = (mob instanceof Creeper ? CREEPER_REACH : MELEE_REACH) + mob.getBbWidth() * 0.5;
        if (SiegeAim.distanceToCore(eyes, siege.corePos) > reach) {
            return false;
        }
        Vec3 point = SiegeAim.visibleCorePoint(level, eyes, siege.corePos, mob);
        if (point == null) {
            return false;
        }
        dropRoute(state, now, distance);
        control(state, Mode.HOLD, now);
        state.lookAt = point;
        state.bestDistance = Math.min(state.bestDistance, distance);
        state.lastCloser = now;
        state.effective(now);
        state.status("attack");
        if (mob instanceof Creeper creeper) {
            creeper.addTag(CORE_BOMBER_TAG);
            if (!creeper.isIgnited()) {
                creeper.ignite();
            }
            return true;
        }
        AttributeInstance damage = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage == null || damage.getValue() <= 0) {
            return true; // cannot hurt anything, just stands there
        }
        if (now >= state.nextAttack) {
            mob.swing(InteractionHand.MAIN_HAND);
            mob.doHurtTarget(siege.target);
            state.nextAttack = now + 20;
        }
        return true;
    }

    private static boolean canUseRoutes(Mob mob) {
        return mob instanceof PathfinderMob && !(mob instanceof FlyingMob) && !(mob instanceof Spider);
    }

    static Kind kindOf(Mob mob, Role role) {
        if (role == Role.BUILDER) {
            return Config.BUILDING_ENABLED.get() ? Kind.BUILDER : Kind.NONE;
        }
        // Every zombie digs (warriors too, while no player is around), so nobody waits for a digger.
        if (role.isArcher() || !Config.DIGGING_ENABLED.get() || SiegeRules.isNonDigger(mob)) {
            return Kind.NONE;
        }
        if (mob instanceof Creeper) {
            return Config.CREEPER_BREACH.get() ? Kind.CREEPER : Kind.NONE;
        }
        if (mob instanceof EnderMan) {
            return Config.ENDERMEN_TAKE_BLOCKS.get() ? Kind.ENDERMAN : Kind.MELEE;
        }
        if (!(mob instanceof PathfinderMob) || mob instanceof RangedAttackMob || mob instanceof Spider
                || mob instanceof FlyingMob || mob instanceof SpellcasterIllager
                || mob.getMainHandItem().getItem() instanceof ProjectileWeaponItem) {
            return Kind.NONE;
        }
        AttributeInstance damage = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage == null || damage.getValue() <= 0) {
            return Kind.NONE;
        }
        return Kind.MELEE;
    }

    // ------------------------------------------------------------------
    // Routes
    // ------------------------------------------------------------------

    @Nullable
    private static Route acquireRoute(ServerLevel level, Mob mob, MobState state, Kind kind, ActiveSiege siege, long now) {
        Route shared = Route.share(mob.position(), siege.corePos, now);
        if (shared != null) {
            state.routeIndex = Math.max(0, shared.joinIndex(mob.position()));
            return shared;
        }
        if (now >= state.noApproachUntil) {
            // Go and help where help is wanted, instead of starting a new hole somewhere else.
            Route wanted = Route.findHelpWanted(mob.position(), siege.corePos, kind.canDig, kind.canBuild, HELP_RANGE,
                    now, null);
            if (wanted != null) {
                state.approach = wanted;
                state.approachStart = now;
                state.status("help");
                return null;
            }
        }
        if (!Route.canPlan(now)) {
            state.status("planning");
            return null; // the planner is busy, try again in a moment
        }
        // The route here is full: build a second way next to it, so the siege splits up.
        boolean newLane = Route.crowdedNear(mob.position(), siege.corePos, now);
        Route route = Route.plan(level, mob.blockPosition(), siege.corePos, siege.coreLevel, siege.siegeRadius + 8, now,
                newLane);
        if (route == null) {
            state.lastCloser = now; // no way at all from here; try again later
            state.status("no_route");
            return null;
        }
        state.routeIndex = 0;
        return route;
    }

    /** Walking over to another route. Returns true once the mob has joined it. */
    private static boolean tickApproach(Mob mob, MobState state, ActiveSiege siege, long now) {
        Route route = state.approach;
        if (route == null) {
            return false;
        }
        int join = route.joinIndex(mob.position());
        if (join >= 0 && route.isUsable(siege.corePos, now)) {
            state.approach = null;
            state.route = route;
            state.routeIndex = join;
            state.lastUseful = now;
            return true;
        }
        if (!route.isUsable(siege.corePos, now) || now - state.approachStart > APPROACH_TICKS) {
            // Could not get there: plan an own route next time.
            state.approach = null;
            state.noApproachUntil = now + 1200;
            state.mode = Mode.NONE;
            state.lastCloser = now - Config.STUCK_SECONDS.get() * 20L;
            return false;
        }
        control(state, Mode.GOTO, now);
        state.gotoPos = route.meetingPoint();
        state.lookAt = null;
        state.status("help");
        return false;
    }

    /** Forget the route without touching the movement order (an archer found something better to do). */
    private static void leaveRoute(Mob mob, MobState state) {
        state.approach = null;
        if (state.route != null) {
            state.route.removeMember(mob.getId());
        }
        state.route = null;
    }

    private static void dropRoute(MobState state, long now, double distance) {
        state.approach = null;
        if (state.route != null) {
            state.route = null;
            // Plan again soon if the mob is still stuck.
            state.lastCloser = now - Config.STUCK_SECONDS.get() * 20L + 40;
            state.bestDistance = distance;
        }
        state.mode = Mode.NONE;
        state.lookAt = null;
        state.gotoPos = null;
    }

    private static void followRoute(ServerLevel level, Mob mob, MobState state, Kind kind, ActiveSiege siege,
                                    long now, double distance) {
        Route route = state.route;
        if (route == null || !route.isUsable(siege.corePos, now)) {
            dropRoute(state, now, distance);
            return;
        }
        int index = route.nearestIndex(mob.position(), state.routeIndex);
        if (index < 0) {
            dropRoute(state, now, distance); // pushed off the route
            return;
        }
        state.routeIndex = index;
        if (index != state.lastIndex) {
            state.lastIndex = index;
            state.lastAdvance = now;
        }
        if (index > state.bestIndex) {
            state.bestIndex = index;
            state.lastUseful = now;
        }
        int last = route.size() - 1;
        int pending = route.firstPending(level, index + 1, index + LOOKAHEAD);
        if (route.isInvalid()) {
            dropRoute(state, now, distance);
            return;
        }
        if (pending < 0) {
            // The way is open: everybody storms up together.
            route.noteMember(mob.getId(), index, false, now);
            if (index >= last) {
                dropRoute(state, now, distance); // arrived; plan again if the Core is still out of reach
                return;
            }
            control(state, Mode.ROUTE, now);
            state.walkLimit = Math.min(last, index + LOOKAHEAD);
            state.lookAt = null;
            state.status("walk", index, last);
            hopIfStuck(level, mob, state, route, now);
            return;
        }
        Route.Action action = route.action(level, pending);
        if (action == null) {
            return;
        }
        boolean capable = action.dig() ? canDig(level, kind, action.pos()) : kind.canBuild;
        // Only a few workers at the front; more would only stand in each other's way.
        if (capable && route.countAhead(mob.getId(), index, true, now) >= MAX_FRONT_WORKERS) {
            capable = false;
            if (kind.canBuild && now >= state.noApproachUntil) {
                // A spare builder starts a second way (another route that waits for a builder).
                Route other = Route.findHelpWanted(mob.position(), siege.corePos, false, true, HELP_RANGE, now, route);
                if (other != null) {
                    leaveRoute(mob, state);
                    state.approach = other;
                    state.approachStart = now;
                    state.status("help");
                    return;
                }
            }
        }
        route.noteMember(mob.getId(), index, capable, now);
        control(state, Mode.ROUTE, now);
        if (!capable) {
            // Somebody else has to do this; let the siege know what is missing.
            // Only work near the Core makes the siege send new workers (not a mob lost in a cave far away).
            if (Vec3.atCenterOf(action.pos()).distanceTo(Vec3.atCenterOf(siege.corePos)) <= NEED_RANGE) {
                SiegeProgress.need(action.dig() ? SiegeProgress.Need.DIGGER : SiegeProgress.Need.BUILDER, now);
            }
            route.markNeed(pending, action.dig(), now);
            // A few wait right behind the work, the rest gathers at the foot of the route (the group waits
            // together and storms up once the way is open). Nobody walks back down.
            int front = Math.max(0, pending - 4);
            boolean nearFront = route.countAhead(mob.getId(), index, false, now) < Config.FRONT_WAITERS.get();
            int wait = nearFront ? front : Math.min(front, route.stagingIndex(level, front));
            state.walkLimit = nearFront ? Math.max(index, front) : Math.max(Math.min(index, front), wait);
            state.lookAt = null;
            boolean gathering = !nearFront && index <= wait;
            state.status(gathering ? "gather" : action.dig() ? "wait_digger" : "wait_builder");
            hopIfStuck(level, mob, state, route, now);
            return;
        }
        state.walkLimit = pending - 1;
        state.lookAt = Vec3.atCenterOf(action.pos());
        route.markWork(pending, now);
        double reach = mob.getEyePosition().distanceTo(Vec3.atCenterOf(action.pos()));
        boolean waitedLong = index >= state.walkLimit && now - state.lastAdvance > HOP_TICKS;
        if (reach > REACH && !(waitedLong && reach <= FAR_REACH)) {
            state.status("walk", index, last);
            hopIfStuck(level, mob, state, route, now);
            return; // still walking up to the work spot
        }
        if (!action.pos().equals(state.actionPos)) {
            state.actionPos = action.pos();
            state.actionTicks = 0;
        }
        state.status(switch (action.work()) {
            case DIG -> "dig";
            case BLOCK -> "build";
            case LADDER -> "ladder";
        });
        if (perform(level, mob, kind, action, state, now)) {
            state.effective(now);
            state.actionPos = null;
            state.actionTicks = 0;
            route.noteProgress(now, !action.dig());
            SiegeProgress.progress(action.pos(), now);
        }
    }

    private static boolean canDig(Level level, Kind kind, BlockPos pos) {
        if (!kind.canDig) {
            return false;
        }
        if (kind == Kind.CREEPER) {
            BlockState state = level.getBlockState(pos);
            return state.getBlock().getExplosionResistance() < BREACH_MAX_RESISTANCE;
        }
        return true;
    }

    /**
     * Vanilla movement sometimes gets caught on block edges or other mobs. A mob that should walk along the
     * route but has not reached the next step for two seconds hops onto it.
     */
    private static void hopIfStuck(ServerLevel level, Mob mob, MobState state, Route route, long now) {
        int index = state.routeIndex;
        if (index >= state.walkLimit || index >= route.size() - 1 || now - state.lastAdvance < HOP_TICKS) {
            return;
        }
        BlockPos next = route.node(index + 1);
        Vec3 target = Vec3.atBottomCenterOf(next);
        if (mob.position().distanceTo(target) > 2.5) {
            state.lastAdvance = now;
            return;
        }
        AABB box = mob.getBoundingBox().move(target.subtract(mob.position()));
        if (!level.noCollision(mob, box)) {
            state.lastAdvance = now - HOP_TICKS / 2; // try again a bit later
            return;
        }
        mob.getNavigation().stop();
        mob.teleportTo(target.x, target.y, target.z);
        state.routeIndex = index + 1;
        state.lastIndex = index + 1;
        state.lastAdvance = now;
    }

    static void control(MobState state, Mode mode, long now) {
        state.mode = mode;
        state.controlUntil = now + INTERVAL * 2L;
    }

    private static boolean perform(ServerLevel level, Mob mob, Kind kind, Route.Action action, MobState state, long now) {
        if (action.work() == Route.Work.LADDER) {
            return placeLadder(level, mob, action.pos(), state);
        }
        if (!action.dig()) {
            return build(level, mob, action.pos(), state);
        }
        return switch (kind) {
            case CREEPER -> breach((Creeper) mob);
            case ENDERMAN -> take(level, (EnderMan) mob, action.pos(), state);
            default -> Digging.dig(level, mob, action.pos(), INTERVAL, now);
        };
    }

    /** Builder: after a short moment a siege stone appears. Mobs standing in the way are lifted onto it. */
    private static boolean build(ServerLevel level, Mob mob, BlockPos pos, MobState state) {
        state.actionTicks += INTERVAL;
        mob.swing(InteractionHand.MAIN_HAND);
        if (state.actionTicks < Config.BUILD_TICKS.get()) {
            return false;
        }
        BlockState block = ModBlocks.SIEGE_STONE.get().defaultBlockState();
        if (!level.isUnobstructed(block, pos, CollisionContext.empty()) && !clearSpot(level, pos)) {
            return false; // somebody is standing there and cannot be moved
        }
        BlockState current = level.getBlockState(pos);
        if (!current.isAir() && current.getFluidState().isEmpty() && !current.canBeReplaced()) {
            level.destroyBlock(pos, true, mob);
        }
        level.setBlockAndUpdate(pos, block);
        level.playSound(null, pos, block.getSoundType().getPlaceSound(), SoundSource.HOSTILE, 1.0F, 0.9F);
        return true;
    }

    /**
     * Builder: a siege ladder in the cell a mob climbs up from (usually where the builder itself stands).
     * Ladders have no collision, so nobody has to move out of the way.
     */
    private static boolean placeLadder(ServerLevel level, Mob mob, BlockPos pos, MobState state) {
        state.actionTicks += INTERVAL;
        mob.swing(InteractionHand.MAIN_HAND);
        if (state.actionTicks < Config.BUILD_TICKS.get()) {
            return false;
        }
        BlockState current = level.getBlockState(pos);
        if (!current.getCollisionShape(level, pos).isEmpty()) {
            return false; // somebody put a block here; the route will ask for digging
        }
        if (!current.isAir() && current.getFluidState().isEmpty() && !current.canBeReplaced()) {
            level.destroyBlock(pos, true, mob); // a torch, a flower ...
        }
        BlockState ladder = ModBlocks.SIEGE_LADDER.get().defaultBlockState();
        level.setBlockAndUpdate(pos, ladder);
        level.playSound(null, pos, SoundEvents.LADDER_PLACE, SoundSource.HOSTILE, 1.0F, 0.9F);
        return true;
    }

    /** Lifts everybody standing in this block onto it (like pillaring up). True if the block is free afterwards. */
    private static boolean clearSpot(ServerLevel level, BlockPos pos) {
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos),
                e -> e.isAlive() && !(e instanceof CoreTargetEntity))) {
            double lift = pos.getY() + 1.0 - entity.getY();
            if (lift > 0.0 && lift <= 1.25 && level.noCollision(entity, entity.getBoundingBox().move(0.0, lift, 0.0))) {
                entity.teleportTo(entity.getX(), pos.getY() + 1.0, entity.getZ());
            } else {
                // Cannot lift (ceiling): push it out sideways.
                double dx = entity.getX() - (pos.getX() + 0.5);
                double dz = entity.getZ() - (pos.getZ() + 0.5);
                double length = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
                entity.push(dx / length * 0.4, 0.1, dz / length * 0.4);
            }
        }
        return level.isUnobstructed(ModBlocks.SIEGE_STONE.get().defaultBlockState(), pos, CollisionContext.empty());
    }

    /** Creeper sacrifice: ignite next to the wall. The explosion handler lets it destroy blocks. */
    private static boolean breach(Creeper creeper) {
        if (!creeper.isIgnited()) {
            creeper.addTag(Digging.BREACH_TAG);
            creeper.ignite();
        }
        return false;
    }

    /** Enderman: after a short moment the block is taken away (blocks with an inventory are dug instead). */
    private static boolean take(ServerLevel level, EnderMan enderman, BlockPos pos, MobState state) {
        BlockState blockState = level.getBlockState(pos);
        if (blockState.hasBlockEntity()) {
            return Digging.dig(level, enderman, pos, INTERVAL, level.getGameTime());
        }
        float hardness = blockState.getDestroySpeed(level, pos);
        int required = Math.min(200, 20 + (int) (hardness * 10.0F));
        state.actionTicks += INTERVAL;
        if (state.actionTicks < required) {
            return false;
        }
        BlockState carried = enderman.getCarriedBlock();
        if (carried != null) {
            // Drop what it carried so the player gets it back.
            Block.popResource(level, enderman.blockPosition(), new ItemStack(carried.getBlock()));
        }
        level.removeBlock(pos, false);
        enderman.setCarriedBlock(blockState.is(ModBlocks.SIEGE_STONE.get()) ? null : blockState);
        enderman.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, pos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.8F, 1.2F);
        return true;
    }

    // ------------------------------------------------------------------
    // Driving (called every tick by SiegeDriveGoal)
    // ------------------------------------------------------------------

    static void drive(Mob mob) {
        MobState state = MOBS.get(mob);
        if (state == null) {
            return;
        }
        if (state.lookAt != null) {
            mob.getLookControl().setLookAt(state.lookAt.x, state.lookAt.y, state.lookAt.z);
        }
        if (state.mode == Mode.GOTO && state.gotoPos != null) {
            if (mob.getNavigation().isDone() || mob.tickCount % 20 == 0) {
                BlockPos pos = state.gotoPos;
                if (!mob.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0)) {
                    state.pathFailures++;
                }
            }
            return;
        }
        if (state.mode == Mode.DIRECT && state.directTarget != null) {
            // Spiders: straight towards the target, climbing every wall on the way.
            mob.getNavigation().stop();
            Vec3 t = state.directTarget;
            mob.getMoveControl().setWantedPosition(t.x, t.y, t.z, 1.0);
            return;
        }
        if (state.mode != Mode.ROUTE || state.route == null) {
            mob.getNavigation().stop();
            holdOnLadder(mob);
            return;
        }
        Route route = state.route;
        int limit = Math.min(state.walkLimit, route.size() - 1);
        int index = Math.min(state.routeIndex, route.size() - 1);
        BlockPos goal;
        if (index < limit) {
            BlockPos next = route.node(index + 1);
            if (isAt(mob, next)) {
                state.routeIndex = index + 1;
                next = route.node(Math.min(index + 2, limit));
            }
            goal = next;
        } else {
            goal = route.node(limit);
        }
        mob.getNavigation().stop();
        double dx = goal.getX() + 0.5 - mob.getX();
        double dz = goal.getZ() + 0.5 - mob.getZ();
        boolean sameColumn = goal.getX() == mob.getBlockX() && goal.getZ() == mob.getBlockZ();
        if (sameColumn && goal.getY() > mob.getY() + 0.1) {
            // Ladder: stay in the shaft and keep "jumping", which makes a mob climb.
            if (dx * dx + dz * dz > 0.03) {
                mob.getMoveControl().setWantedPosition(goal.getX() + 0.5, mob.getY(), goal.getZ() + 0.5, 0.5);
            } else {
                mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0);
            }
            mob.getJumpControl().jump();
            return;
        }
        if (!isAt(mob, goal)) {
            mob.getMoveControl().setWantedPosition(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.0);
            if (goal.getY() > mob.getY() + 0.5 && dx * dx + dz * dz < 1.6 * 1.6 && mob.onGround()) {
                mob.getJumpControl().jump(); // step up the stairs
            }
        }
        holdOnLadder(mob);
    }

    /** A mob on a ladder that should not climb does not slide down (it waits, digs, builds or attacks). */
    private static void holdOnLadder(Mob mob) {
        if (mob.onClimbable() && !mob.onGround()) {
            Vec3 motion = mob.getDeltaMovement();
            if (motion.y < 0.0) {
                mob.setDeltaMovement(motion.x, 0.0, motion.z);
            }
        }
    }

    private static boolean isAt(Mob mob, BlockPos node) {
        double dx = node.getX() + 0.5 - mob.getX();
        double dz = node.getZ() + 0.5 - mob.getZ();
        return dx * dx + dz * dz < 0.2 && Math.abs(node.getY() - mob.getY()) < 0.8;
    }

    // ------------------------------------------------------------------
    // Riders, spiders, endermen, recall
    // ------------------------------------------------------------------

    /** A mob carried by a phantom or riding a spider: attack the Core if it is in reach, otherwise wait. */
    private static void tickRider(ServerLevel level, Mob mob, MobState state, ActiveSiege siege, long now) {
        state.route = null;
        state.approach = null;
        state.mode = Mode.NONE;
        state.effective(now);
        state.lastCloser = now;
        state.bestDistance = Double.MAX_VALUE;
        Entity vehicle = mob.getVehicle();
        boolean carried = vehicle != null && vehicle.getTags().contains(SiegeAirDrop.CARRIER_TAG);
        state.status(carried ? "carried" : "riding");
        if (carried || mob.getTarget() != siege.target) {
            return;
        }
        if (Targeting.isRanged(mob)) {
            SiegeArchers.shootFromHere(level, mob, state, siege, now);
        } else {
            tryAttackCore(level, mob, state, siege, now, mob.distanceTo(siege.target));
        }
    }

    /**
     * Spiders walk straight at the Core and climb every wall on the way (vanilla spiders climb when they
     * bump into a wall). Under an overhang they try a detour. Riders get off near the Core or when the spider
     * is stuck, and continue on foot (digging, building ...).
     */
    private static void tickClimber(ServerLevel level, Mob mob, MobState state, ActiveSiege siege, long now,
                                    double distance) {
        state.route = null;
        state.approach = null;
        if (distance < state.bestDistance - 0.5) {
            state.bestDistance = distance;
            state.lastCloser = now;
            state.lastUseful = now;
        }
        long stuck = now - state.lastCloser;
        Vec3 core = Vec3.atCenterOf(siege.corePos);
        if (mob.isVehicle() && (mob.position().distanceTo(core) <= DISMOUNT_RANGE || stuck > CLIMBER_STUCK_TICKS * 2)) {
            mob.ejectPassengers();
        }
        if (now < state.detourUntil && state.directTarget != null) {
            state.status("detour");
        } else if (stuck > CLIMBER_STUCK_TICKS) {
            // Stuck (overhang, corner): walk sideways for a moment, then try again.
            double angle = Math.atan2(mob.getZ() - core.z, mob.getX() - core.x)
                    + (mob.getRandom().nextBoolean() ? 1.0 : -1.0) * (Math.PI / 2.0);
            double length = 6.0 + mob.getRandom().nextDouble() * 4.0;
            state.directTarget = new Vec3(mob.getX() + Math.cos(angle) * length, mob.getY(),
                    mob.getZ() + Math.sin(angle) * length);
            state.detourUntil = now + 60;
            state.lastCloser = now;
            state.bestDistance = distance;
            state.status("detour");
        } else {
            state.directTarget = core;
            state.status("climb");
        }
        control(state, Mode.DIRECT, now);
        state.lookAt = null;
    }

    /** Endermen that got stuck teleport right next to the Core (they are endermen after all). */
    private static boolean tryTeleportToCore(ServerLevel level, EnderMan enderman, MobState state, ActiveSiege siege,
                                             long now, double distance) {
        if (distance < state.bestDistance - 1.0) {
            return false; // still getting closer the normal way
        }
        if (now - state.lastCloser < Config.STUCK_SECONDS.get() * 20L || now < state.nextTeleport) {
            return false;
        }
        state.nextTeleport = now + 100;
        BlockPos core = siege.corePos;
        java.util.List<BlockPos> spots = new java.util.ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = core.offset(dx, dy, dz);
                    if (!pos.equals(core) && SiegeArchers.isStandable(level, pos)
                            && RoutePlanner.isPassable(level, pos.above(2))) {
                        spots.add(pos);
                    }
                }
            }
        }
        spots.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(core)));
        for (int i = 0; i < Math.min(4, spots.size()); i++) {
            BlockPos spot = spots.get(i);
            if (enderman.randomTeleport(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, true)) {
                level.playSound(null, spot, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.0F);
                state.lastCloser = now;
                state.effective(now);
                state.bestDistance = enderman.distanceTo(siege.target);
                state.status("teleport");
                return true;
            }
        }
        return false;
    }

    /**
     * Lost: more than {@link #RECALL_DISTANCE} from the Core and for {@code lostSeconds} it neither got closer
     * than ever before, nor hit or shot anything, nor did its route make progress (e.g. stuck in a cave, a
     * spider under an overhang, waiting on a route nobody works on).
     */
    private static boolean isLost(Mob mob, MobState state, double distance, long now) {
        if (distance < state.nearest - 1.5) {
            state.nearest = distance;
            state.lastEffective = now;
        }
        LivingEntity target = mob.getTarget();
        if (distance <= RECALL_DISTANCE || (target instanceof Player && mob.distanceToSqr(target) < 16 * 16)) {
            state.lastEffective = now;
            return false;
        }
        int seconds = Config.LOST_SECONDS.get();
        if (seconds <= 0) {
            return false;
        }
        long since = now - seconds * 20L;
        if (state.lastEffective >= since) {
            return false;
        }
        Route route = state.route != null ? state.route : state.approach;
        return route == null || (!route.progressedSince(since) && !route.hasWorker(now));
    }

    /** A naturally spawned mob that got lost leaves the siege and behaves normally again (it may despawn). */
    private static void release(Mob mob, ActiveSiege siege) {
        forget(mob);
        mob.addTag(SiegeRules.RELEASED_TAG);
        SiegeTeam.leave(mob);
        SiegeManager.clearFollowRange(mob);
        SiegeDebug.clearName(mob);
        if (mob.getTarget() == siege.target) {
            mob.setTarget(null);
        }
        mob.getNavigation().stop();
    }

    private static void forget(Mob mob) {
        MobState state = MOBS.remove(mob);
        if (state != null && state.route != null) {
            state.route.removeMember(mob.getId());
        }
    }

    /** A siege mob stuck far away disappears; the siege spawns a new one in a better place. */
    private static void recall(ServerLevel level, Mob mob) {
        forget(mob);
        level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + 1.0, mob.getZ(), 30, 0.4, 0.8, 0.4, 0.2);
        mob.ejectPassengers();
        mob.discard();
    }

    // ------------------------------------------------------------------
    // Sapper endermen
    // ------------------------------------------------------------------

    private static boolean carriesTnt(EnderMan enderman) {
        BlockState carried = enderman.getCarriedBlock();
        return carried != null && carried.is(Blocks.TNT);
    }

    private static void tickSapper(ServerLevel level, EnderMan enderman, MobState state, ActiveSiege siege, long now) {
        state.status("sapper");
        double distance = enderman.distanceTo(siege.target);
        if (distance < state.bestDistance - 1.0) {
            state.bestDistance = distance;
            state.lastCloser = now;
            state.lastUseful = now;
        }
        if (distance > SAPPER_RANGE) {
            state.mode = Mode.NONE; // walk closer the normal way
            state.actionTicks = 0;
            if (enderman.getTarget() != siege.target) {
                enderman.setTarget(siege.target);
            }
            if (now - state.lastCloser > Config.STUCK_SECONDS.get() * 20L && now >= state.nextTeleport) {
                // Stuck far away: teleport towards the Core.
                state.nextTeleport = now + 60;
                BlockPos core = siege.corePos;
                double x = core.getX() + 0.5 + (enderman.getRandom().nextDouble() - 0.5) * 16.0;
                double z = core.getZ() + 0.5 + (enderman.getRandom().nextDouble() - 0.5) * 16.0;
                enderman.randomTeleport(x, core.getY() + 4, z, true);
            }
            return;
        }
        state.actionTicks += INTERVAL;
        if (state.actionTicks > 600) {
            // Could not get the TNT next to the Core for 30 seconds: give up and fight like a normal enderman.
            enderman.setCarriedBlock(null);
            state.actionTicks = 0;
            state.actionPos = null;
            state.mode = Mode.NONE;
            return;
        }
        BlockPos spot = state.actionPos;
        if (spot == null || !isTntSpot(level, spot, siege)) {
            spot = findTntSpot(level, enderman, siege);
            state.actionPos = spot;
        }
        if (spot == null) {
            state.mode = Mode.NONE;
            return;
        }
        control(state, Mode.HOLD, now);
        state.lookAt = Vec3.atCenterOf(spot);
        if (enderman.getEyePosition().distanceTo(Vec3.atCenterOf(spot)) <= REACH + 0.5) {
            BlockState old = level.getBlockState(spot);
            if (!old.isAir() && old.getFluidState().isEmpty() && !old.canBeReplaced()) {
                level.destroyBlock(spot, true, enderman);
            }
            level.setBlockAndUpdate(spot, Blocks.TNT.defaultBlockState());
            enderman.setCarriedBlock(null);
            enderman.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, spot, SoundEvents.GRASS_PLACE, SoundSource.HOSTILE, 1.0F, 0.8F);
            SiegeExplosives.trackTnt(spot, now);
            SiegeProgress.progress(spot, now);
            state.effective(now);
            state.actionPos = null;
            state.mode = Mode.NONE;
            state.lookAt = null;
            return;
        }
        if (now >= state.nextTeleport) {
            state.nextTeleport = now + 40;
            teleportNear(enderman, spot);
        }
    }

    private static boolean isTntSpot(Level level, BlockPos pos, ActiveSiege siege) {
        if (pos.equals(siege.corePos) || level.getBlockState(pos).is(Blocks.TNT)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        boolean free = (state.isAir() || state.canBeReplaced()) && state.getFluidState().isEmpty()
                && RoutePlanner.isSupport(level, pos.below());
        boolean takeable = !state.hasBlockEntity() && RoutePlanner.isDiggable(level, pos, siege.corePos)
                && !state.getCollisionShape(level, pos).isEmpty();
        return free || takeable;
    }

    /** A free spot right next to the Core; if the Core is walled in, a wall block next to it. */
    @Nullable
    private static BlockPos findTntSpot(Level level, EnderMan enderman, ActiveSiege siege) {
        BlockPos core = siege.corePos;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = core.offset(dx, dy, dz);
                    if (pos.equals(core)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    boolean free = (state.isAir() || state.canBeReplaced()) && state.getFluidState().isEmpty()
                            && RoutePlanner.isSupport(level, pos.below());
                    if (!free) {
                        continue;
                    }
                    double score = pos.distSqr(core) * 4.0 + enderman.blockPosition().distSqr(pos);
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }
        for (Direction direction : Direction.values()) {
            BlockPos pos = core.relative(direction);
            if (direction != Direction.DOWN && isTntSpot(level, pos, siege)) {
                return pos;
            }
        }
        return null;
    }

    private static void teleportNear(EnderMan enderman, BlockPos spot) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int distance = 1; distance <= 2; distance++) {
                BlockPos stand = spot.relative(direction, distance);
                if (enderman.randomTeleport(stand.getX() + 0.5, stand.getY() + 1, stand.getZ() + 0.5, true)) {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Self-destruct
    // ------------------------------------------------------------------

    private static void startFuse(ServerLevel level, Mob mob, MobState state, long now) {
        state.fuseStart = now;
        state.route = null;
        state.lookAt = null;
        control(state, Mode.HOLD, now);
        mob.getNavigation().stop();
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 1.0F, 0.7F);
    }

    private static void tickFuse(ServerLevel level, Mob mob, MobState state, long now) {
        control(state, Mode.HOLD, now);
        state.status("fuse");
        level.sendParticles(ParticleTypes.SMOKE, mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                6, 0.3, 0.4, 0.3, 0.02);
        if (now - state.fuseStart < FUSE_TICKS) {
            return;
        }
        MOBS.remove(mob);
        float power = Config.SELF_DESTRUCT_POWER.get().floatValue();
        if (power > 0) {
            level.explode(mob, mob.getX(), mob.getY() + 0.5, mob.getZ(), power,
                    Config.SELF_DESTRUCT_BREAKS_BLOCKS.get() ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
        }
        mob.kill();
    }
}
