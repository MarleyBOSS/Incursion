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
 * Per-mob siege behaviour, evaluated every few ticks for every attacker in the siege area. The plan comes from
 * the {@link SiegeCommander}; every mob belongs to one {@link SiegeFront} and does its part:
 * <ul>
 *     <li>Mobs next to the Core hit it directly (no vanilla line of sight needed, see {@link SiegeAim}).</li>
 *     <li>Archers look for a firing position and shoot from there ({@link SiegeArchers}).</li>
 *     <li>Everybody else goes to the assembly point of its front (the foot of the way) and follows the way.
 *     Where the way still needs work, up to two workers dig (miners) or build (builders); a few mobs wait
 *     right behind them, the rest gathers at the foot and storms up once the way is open.
 *     Once it is open, workers also build the platform at the Core.</li>
 *     <li>A mob that cannot even reach the foot of the way (e.g. stuck in a cave) disappears.</li>
 *     <li>Sapper endermen place their TNT next to the Core; spiders climb straight up.</li>
 *     <li>A mob that achieved nothing for a long time blows itself up.</li>
 * </ul>
 */
public final class SiegeAI {
    static final int INTERVAL = 5;
    private static final double REACH = 4.5;
    private static final double FAR_REACH = 6.0;
    private static final double MELEE_REACH = 2.3;
    private static final double CREEPER_REACH = 2.5;
    private static final int FUSE_TICKS = 40;
    private static final double SAPPER_RANGE = 20.0;
    /** A mob that should walk but has not reached the next route step for this long hops there. */
    private static final long HOP_TICKS = 40;
    /** Creepers do not try to blow up blocks this explosion resistant (obsidian, siege stone ...). */
    private static final float BREACH_MAX_RESISTANCE = 100.0F;
    /** Mobs further than this from the Core can get lost. */
    private static final double LOST_DISTANCE = 24.0;
    /** A mob that cannot get to the foot of its front for this long (at most) is lost. */
    private static final int FOOT_TIMEOUT_SECONDS = 30;
    /** A spider that got no closer for this long tries a detour. */
    private static final long CLIMBER_STUCK_TICKS = 100;
    /** At most this many workers build / dig at the front of one way at the same time. */
    private static final int MAX_BUILD_WORKERS = 2;
    private static final int MAX_DIG_WORKERS = 3;
    /** A mob that would need longer than this to dig the block leaves it to a better digger (if one is there). */
    private static final float SLOW_DIG_TICKS = 400.0F;
    /** Riders get off a spider that is this close to the Core, or stuck. */
    private static final double DISMOUNT_RANGE = 4.0;
    /** Waiting on a way where work got done this recently counts as useful (no self-destruct). */
    private static final long BUSY_FRONT_TICKS = 1200;

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
        /** Closest distance to the Core so far, and the last time the mob achieved anything. */
        double nearest = Double.MAX_VALUE;
        long lastEffective;
        // The front and where on its way the mob is
        @Nullable
        SiegeFront front;
        int routeIndex = -1;
        int bestIndex = -1;
        int walkLimit;
        int lastIndex = -1;
        long lastAdvance;
        double footBest = Double.MAX_VALUE;
        long footSince;
        // Work and movement orders
        @Nullable
        BlockPos actionPos;
        int actionTicks;
        @Nullable
        Vec3 lookAt;
        Mode mode = Mode.NONE;
        long controlUntil;
        @Nullable
        BlockPos gotoPos;
        long fuseStart = -1;
        long nextShot;
        long nextAttack;
        long nextTeleport;
        // Archers
        @Nullable
        BlockPos spot;
        /** The spot is a post (a landing to hold, no shot at the Core from there). */
        boolean spotPost;
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
            this.footSince = now;
        }

        void effective(long now) {
            this.lastUseful = now;
            this.lastEffective = now;
        }

        void status(String key, Object... args) {
            this.status = key;
            this.statusArgs = args;
        }

        @Nullable
        Route route() {
            return front != null ? front.route : null;
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
            mob.goalSelector.addGoal(SiegeSwimGoal.PRIORITY, new SiegeSwimGoal(mob));
        }
        MobRoles.prepareGoals(mob);
    }

    // ------------------------------------------------------------------
    // Hooks used by other classes
    // ------------------------------------------------------------------

    /** Is this mob known to the siege AI (so it should swim, follow routes ...)? */
    static boolean isTracked(Mob mob) {
        return MOBS.containsKey(mob);
    }

    static boolean wantsControl(Mob mob) {
        MobState state = MOBS.get(mob);
        return state != null && state.mode != Mode.NONE && mob.level().getGameTime() <= state.controlUntil;
    }

    @Nullable
    static MobState stateOf(Mob mob) {
        return MOBS.get(mob);
    }

    @Nullable
    static SiegeFront frontOf(Mob mob) {
        MobState state = MOBS.get(mob);
        return state != null ? state.front : null;
    }

    /** A freshly spawned mob starts at the foot of this front. */
    static void assign(Mob mob, SiegeFront front, long now) {
        MobState state = MOBS.computeIfAbsent(mob, m -> new MobState(now));
        state.front = front;
        state.routeIndex = -1;
        state.footBest = Double.MAX_VALUE;
        state.footSince = now;
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
        Digging.clear();
        SiegeExplosives.clear();
        SiegeProgress.clear();
        SiegeArchers.clear();
        SiegeCommander.clear();
        PlayerHunt.clear();
    }

    // ------------------------------------------------------------------
    // Main tick
    // ------------------------------------------------------------------

    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (mob.isInWater() && MOBS.containsKey(mob)) {
            SiegeCurrents.tick(level, mob); // every tick: the current pushes every tick
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
        SiegeCurrents.dropOldFireResistance(mob);
        MobState state = MOBS.computeIfAbsent(mob, m -> new MobState(now));
        tickMob(level, mob, state, siege, now);
        if (!mob.isAlive()) {
            return;
        }
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

        LivingEntity target = mob.getTarget();
        if (target instanceof Player player && mob.distanceToSqr(player) < 16 * 16) {
            state.lastUseful = now;
        }
        double distance = mob.distanceTo(siege.target);
        if (distance < state.bestDistance - 1.0) {
            state.bestDistance = distance;
            state.lastCloser = now;
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
            release(state);
            state.lastUseful = Math.max(state.lastUseful, now - 20); // fighting is not idling
            state.status(target instanceof Player ? "player" : "other_target");
            return;
        }

        boolean ranged = Targeting.isRanged(mob);
        if (ranged) {
            if (SiegeArchers.tick(level, mob, state, siege, now)) {
                state.lastEffective = now;
                return; // shooting, or walking to a firing position
            }
        } else if (!buildsPlatform(role, state) && tryAttackCore(level, mob, state, siege, now)) {
            return;
        }

        if (mob instanceof Spider) {
            if (isLostByDistance(state, distance, now)) {
                removeLost(level, mob);
                return;
            }
            tickClimber(level, mob, state, siege, now, distance);
            return;
        }
        if (!(mob instanceof PathfinderMob) || mob instanceof FlyingMob) {
            release(state); // slimes, flying mobs ...: their own way
            state.status("approach");
            return;
        }
        if (mob instanceof EnderMan enderman && tryTeleportToCore(level, enderman, state, siege, now)) {
            return;
        }

        SiegeFront front = state.front;
        boolean huntWorker = PlayerHunt.isWorker(mob);
        if (huntWorker ? !PlayerHunt.contains(front) : !SiegeCommander.contains(front)) {
            Route old = state.route();
            if (old != null) {
                old.removeMember(mob.getId());
            }
            front = huntWorker ? PlayerHunt.assign(mob) : SiegeCommander.assign(mob);
            state.front = front;
            state.routeIndex = -1;
            state.footBest = Double.MAX_VALUE;
            state.footSince = now;
        }
        if (front == null) {
            // No plan yet (or no way at all): the vanilla AI walks towards the Core meanwhile.
            release(state);
            state.status(SiegeCommander.isPlanning() ? "no_plan" : "approach");
            if (isLostByDistance(state, distance, now)) {
                removeLost(level, mob);
            }
            return;
        }
        Kind kind = ranged ? Kind.NONE : kindOf(mob, role);
        followFront(level, mob, state, kind, front, siege, now);
    }

    /**
     * Builders always build first (platform and landings) and only hit the Core when nothing is left to build.
     * The attack of the others goes on at the same time.
     */
    private static boolean buildsPlatform(Role role, MobState state) {
        SiegeFront front = state.front;
        return role == Role.BUILDER && SiegeCommander.contains(front)
                && (front.platformWork > 0 || front.landingWork > 0);
    }

    /** Gives movement back to the vanilla AI. */
    private static void release(MobState state) {
        state.mode = Mode.NONE;
        state.lookAt = null;
        state.gotoPos = null;
    }

    // ------------------------------------------------------------------
    // Hitting the Core
    // ------------------------------------------------------------------

    /**
     * A melee mob (or creeper) close enough to an exposed face of the Core attacks it directly.
     * This does not depend on vanilla line of sight, so a Core with a block on top can still be hit.
     */
    private static boolean tryAttackCore(ServerLevel level, Mob mob, MobState state, ActiveSiege siege, long now) {
        Vec3 eyes = mob.getEyePosition();
        double reach = (mob instanceof Creeper ? CREEPER_REACH : MELEE_REACH) + mob.getBbWidth() * 0.5;
        if (SiegeAim.distanceToCore(eyes, siege.corePos) > reach) {
            return false;
        }
        Vec3 point = SiegeAim.visibleCorePoint(level, eyes, siege.corePos, mob);
        if (point == null) {
            return false;
        }
        control(state, Mode.HOLD, now);
        state.gotoPos = null;
        state.lookAt = point;
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

    static Kind kindOf(Mob mob, Role role) {
        if (role == Role.BUILDER) {
            return Config.BUILDING_ENABLED.get() ? Kind.BUILDER : Kind.MELEE;
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
    // Following the front's way
    // ------------------------------------------------------------------

    private static void followFront(ServerLevel level, Mob mob, MobState state, Kind kind, SiegeFront front,
                                    ActiveSiege siege, long now) {
        Route route = front.route;
        if (route.isInvalid()) {
            release(state);
            state.status("no_plan");
            return;
        }
        int index = state.routeIndex >= 0 ? route.nearestIndex(mob.position(), state.routeIndex) : -1;
        if (index < 0) {
            index = route.findIndex(mob.position());
        }
        if (index < 0) {
            if (state.routeIndex >= 0) {
                // Fell off or got pushed away: back to the foot.
                route.removeMember(mob.getId());
                state.routeIndex = -1;
                state.footBest = Double.MAX_VALUE;
                state.footSince = now;
            }
            goToFoot(level, mob, state, route, now);
            return;
        }
        state.routeIndex = index;
        state.lastEffective = now; // on the way: not lost
        if (index != state.lastIndex) {
            state.lastIndex = index;
            state.lastAdvance = now;
        }
        if (index > state.bestIndex || route.progressedSince(now - BUSY_FRONT_TICKS)) {
            state.bestIndex = Math.max(state.bestIndex, index);
            state.lastUseful = now;
        }
        int last = route.last();
        int pending = route.firstPending(level, index + 1, last);
        if (route.isInvalid()) {
            release(state);
            return;
        }

        if (pending < 0) {
            // The way is open: everybody storms up together. Workers build the platform at the top.
            route.noteMember(mob.getId(), index, false, now);
            if ((kind.canBuild || kind.canDig) && platformWork(level, mob, state, kind, front, now)) {
                return;
            }
            if (kind.canBuild && front.platformWork == 0 && landingWork(level, mob, state, front, index, now)) {
                return;
            }
            if (index >= last) {
                if (kind.canBuild && tryAttackCore(level, mob, state, siege, now)) {
                    return; // nothing left to build here: hit the Core
                }
                control(state, Mode.HOLD, now);
                state.lookAt = Vec3.atCenterOf(route.corePos); // the Core, or the hunted player
                state.status("attack_wait");
                return;
            }
            control(state, Mode.ROUTE, now);
            state.walkLimit = last;
            state.lookAt = null;
            state.status("walk", index, last);
            hopIfStuck(level, mob, state, route, now);
            return;
        }

        Route.Action action = route.action(level, pending);
        if (action == null) {
            return;
        }
        boolean waiting = action.work() == Route.Work.WAIT; // a fluid is running dry: nothing to do but wait
        boolean capable = !waiting && (action.dig() ? canDig(level, mob, kind, action.pos(), front, now) : kind.canBuild);
        int maxWorkers = action.dig() ? MAX_DIG_WORKERS : MAX_BUILD_WORKERS;
        if (capable && route.countAhead(mob.getId(), index, true, now) >= maxWorkers) {
            capable = false; // more would only stand in each other's way
        }
        route.noteMember(mob.getId(), index, capable, now);
        control(state, Mode.ROUTE, now);
        if (!capable) {
            // A builder that is not needed at the stairs expands the way: landings (platforms beside the stairs).
            if (kind.canBuild && landingWork(level, mob, state, front, index, now)) {
                return;
            }
            // A few wait right behind the work, the rest gathers at the foot (the group waits together and
            // storms up once the way is open). Nobody walks back down.
            int waitFront = Math.max(0, pending - 4);
            boolean nearFront = route.countAhead(mob.getId(), index, false, now) < Config.FRONT_WAITERS.get();
            int wait = nearFront ? waitFront : Math.min(waitFront, route.stagingIndex(level, waitFront));
            state.walkLimit = nearFront ? Math.max(index, waitFront) : Math.max(Math.min(index, waitFront), wait);
            state.lookAt = null;
            boolean gathering = !nearFront && index <= wait;
            if (gathering && !MobRoles.of(mob).isWorker()) {
                // Another way is already open: go there instead of waiting here.
                SiegeFront open = SiegeCommander.openFront();
                if (open != null && open != front) {
                    route.removeMember(mob.getId());
                    assign(mob, open, now);
                    state.status("to_foot");
                    return;
                }
            }
            state.status(gathering ? "gather" : waiting ? "wait_drain" : action.dig() ? "wait_digger" : "wait_builder");
            hopIfStuck(level, mob, state, route, now);
            return;
        }
        state.walkLimit = pending - 1;
        state.lookAt = Vec3.atCenterOf(action.pos());
        double reach = mob.getEyePosition().distanceTo(Vec3.atCenterOf(action.pos()));
        boolean waitedLong = index >= state.walkLimit && now - state.lastAdvance > HOP_TICKS;
        if (reach > REACH && !(waitedLong && reach <= FAR_REACH)) {
            state.status("walk", index, last);
            hopIfStuck(level, mob, state, route, now);
            return; // still walking up to the work spot
        }
        work(level, mob, state, kind, action, route, now);
    }

    /** Does one step of the work (digging or building); counts as progress when the block is done. */
    private static void work(ServerLevel level, Mob mob, MobState state, Kind kind, Route.Action action, Route route,
                             long now) {
        if (!action.pos().equals(state.actionPos)) {
            state.actionPos = action.pos();
            state.actionTicks = 0;
        }
        state.status(action.dig() ? "dig" : "build");
        if (perform(level, mob, kind, action, state, now)) {
            state.effective(now);
            state.actionPos = null;
            state.actionTicks = 0;
            route.noteProgress(now);
            SiegeProgress.progress(action.pos(), now);
            if (!action.dig()) {
                BlockPos wing = route.wingOf(action.pos());
                if (wing != null) {
                    placeWing(level, wing, route);
                }
            }
        }
    }

    /** The second column of the stairs, placed together with the stair block (only into free space). */
    private static void placeWing(ServerLevel level, BlockPos pos, Route route) {
        BlockState current = level.getBlockState(pos);
        if (!current.canBeReplaced() || current.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
                || !RoutePlanner.canPlace(level, pos, route.corePos)) {
            return;
        }
        BlockState block = ModBlocks.SIEGE_STONE.get().defaultBlockState();
        if (!level.isUnobstructed(block, pos, CollisionContext.empty())) {
            return; // somebody is standing there
        }
        level.setBlockAndUpdate(pos, block);
    }

    /**
     * A builder builds the next landing (a wide platform beside the stairs): it walks up to it along the way and
     * places the floor blocks. False if there is no landing work for it right now.
     */
    private static boolean landingWork(ServerLevel level, Mob mob, MobState state, SiegeFront front, int index,
                                       long now) {
        SiegeFront.LandingJob job = front.landingAction(level, true, index);
        if (job == null) {
            return false;
        }
        Route route = front.route;
        Vec3 at = Vec3.atCenterOf(job.action().pos());
        if (mob.getEyePosition().distanceTo(at) <= REACH) {
            control(state, Mode.HOLD, now);
            state.lookAt = at;
            work(level, mob, state, Kind.BUILDER, job.action(), route, now);
            state.status("landing");
            return true;
        }
        if (index == job.landing().index() && now - state.lastAdvance > HOP_TICKS * 3) {
            return false; // standing at the landing for a long time but cannot reach the block: leave it
        }
        // Walk up or down the stairs to the landing (the builder stops at its step and builds from there).
        control(state, Mode.ROUTE, now);
        state.walkLimit = Math.min(job.landing().index(), route.last());
        state.lookAt = null;
        state.status("to_landing");
        hopIfStuck(level, mob, state, route, now);
        return true;
    }

    /** The way is open: builders and miners make the platform next to the Core (more room to attack). */
    private static boolean platformWork(ServerLevel level, Mob mob, MobState state, Kind kind, SiegeFront front,
                                        long now) {
        Route.Action action = front.platformAction(level, kind.canDig, kind.canBuild);
        if (action == null) {
            return false;
        }
        if (mob.getEyePosition().distanceTo(Vec3.atCenterOf(action.pos())) > REACH) {
            return false; // walk on up the way first
        }
        control(state, Mode.HOLD, now);
        state.lookAt = Vec3.atCenterOf(action.pos());
        work(level, mob, state, kind, action, front.route, now);
        state.status("platform");
        return true;
    }

    /** Not on the way: walk to its foot (the assembly point). A mob that cannot get there is lost. */
    private static void goToFoot(ServerLevel level, Mob mob, MobState state, Route route, long now) {
        BlockPos foot = route.node(0);
        Vec3 target = Vec3.atBottomCenterOf(foot);
        double distance = mob.position().distanceTo(target);
        if (distance < state.footBest - 0.5) {
            state.footBest = distance;
            state.footSince = now;
            state.lastUseful = now;
            state.lastEffective = now;
        }
        long stuck = now - state.footSince;
        if (distance <= 4.0 && stuck >= HOP_TICKS) {
            // Right there but cannot step onto it (an edge, a fence ...): hop over.
            AABB box = mob.getBoundingBox().move(target.subtract(mob.position()));
            if (level.noCollision(mob, box)) {
                mob.getNavigation().stop();
                mob.teleportTo(target.x, target.y, target.z);
                state.routeIndex = 0;
                state.lastIndex = 0;
                state.lastAdvance = now;
                state.footBest = 0.0;
                state.footSince = now;
                return;
            }
        }
        int lostSeconds = Config.LOST_SECONDS.get();
        if (lostSeconds > 0 && stuck > Math.min(lostSeconds, FOOT_TIMEOUT_SECONDS) * 20L) {
            removeLost(level, mob);
            return;
        }
        control(state, Mode.GOTO, now);
        state.gotoPos = foot;
        state.lookAt = null;
        state.status("to_foot");
    }

    private static boolean canDig(Level level, Mob mob, Kind kind, BlockPos pos, SiegeFront front, long now) {
        if (!kind.canDig) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (kind == Kind.CREEPER) {
            return state.getBlock().getExplosionResistance() < BREACH_MAX_RESISTANCE;
        }
        if (kind == Kind.ENDERMAN || state.getCollisionShape(level, pos).isEmpty()) {
            return true;
        }
        AttributeInstance damage = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        double attack = damage != null ? damage.getValue() : 1.0;
        float ticks = Digging.digTicks(mob.getMainHandItem(), attack, state, state.getDestroySpeed(level, pos));
        if (ticks <= SLOW_DIG_TICKS) {
            front.lastFastDigger = now;
            return true;
        }
        // Slow here (e.g. a builder on obsidian): only if no better digger is on this front.
        return now - front.lastFastDigger > 200;
    }

    /**
     * Vanilla movement sometimes gets caught on block edges. A mob that should walk along the route but has not
     * reached the next step for two seconds hops onto it.
     */
    private static void hopIfStuck(ServerLevel level, Mob mob, MobState state, Route route, long now) {
        int index = state.routeIndex;
        if (index >= state.walkLimit || index >= route.last() || now - state.lastAdvance < HOP_TICKS) {
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
        if (!action.dig()) {
            return build(level, mob, action.pos(), state);
        }
        if (RoutePlanner.isPassable(level, action.pos())) {
            return true; // somebody else was faster (or it was a plant ...)
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
        Route route = state.route();
        if (state.mode != Mode.ROUTE || route == null || state.routeIndex < 0) {
            mob.getNavigation().stop();
            holdOnLadder(mob);
            return;
        }
        int limit = Math.min(state.walkLimit, route.last());
        int index = Math.min(state.routeIndex, route.last());
        BlockPos goal;
        if (index < limit) {
            BlockPos next = route.node(index + 1);
            if (isAt(mob, next)) {
                state.routeIndex = index + 1;
                next = route.node(Math.min(index + 2, limit));
            }
            goal = next;
        } else if (index > limit) {
            // Back down the stairs, one step at a time (a straight line to a lower step could leave the stairs).
            BlockPos previous = route.node(index - 1);
            if (isAt(mob, previous)) {
                state.routeIndex = index - 1;
                previous = route.node(Math.max(limit, index - 2));
            }
            goal = previous;
        } else {
            goal = route.node(limit);
        }
        mob.getNavigation().stop();
        double dx = goal.getX() + 0.5 - mob.getX();
        double dz = goal.getZ() + 0.5 - mob.getZ();
        if (!isAt(mob, goal)) {
            mob.getMoveControl().setWantedPosition(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.0);
            if (goal.getY() > mob.getY() + 0.5 && dx * dx + dz * dz < 1.6 * 1.6 && mob.onGround()) {
                mob.getJumpControl().jump(); // step up the stairs
            }
        }
        holdOnLadder(mob);
    }

    /** A mob on a ladder (e.g. an old siege ladder) that should not climb does not slide down. */
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
    // Riders, spiders, endermen, lost mobs
    // ------------------------------------------------------------------

    /** A mob carried by a phantom or riding a spider: attack the Core if it is in reach, otherwise wait. */
    private static void tickRider(ServerLevel level, Mob mob, MobState state, ActiveSiege siege, long now) {
        state.mode = Mode.NONE;
        state.routeIndex = -1;
        state.effective(now);
        state.lastCloser = now;
        state.bestDistance = Double.MAX_VALUE;
        state.footSince = now;
        Entity vehicle = mob.getVehicle();
        boolean carried = vehicle != null && vehicle.getTags().contains(SiegeAirDrop.CARRIER_TAG);
        state.status(carried ? "carried" : "riding");
        if (carried || mob.getTarget() != siege.target) {
            return;
        }
        if (Targeting.isRanged(mob)) {
            SiegeArchers.shootFromHere(level, mob, state, siege, now);
        } else {
            tryAttackCore(level, mob, state, siege, now);
        }
    }

    /**
     * Spiders walk straight at the Core and climb every wall on the way (vanilla spiders climb when they
     * bump into a wall). Under an overhang they try a detour. Riders get off near the Core or when the spider
     * is stuck, and continue on foot.
     */
    private static void tickClimber(ServerLevel level, Mob mob, MobState state, ActiveSiege siege, long now,
                                    double distance) {
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
                                             long now) {
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
                state.routeIndex = -1;
                state.status("teleport");
                return true;
            }
        }
        return false;
    }

    /** Far from the Core and for a while neither got closer than ever before nor achieved anything. */
    private static boolean isLostByDistance(MobState state, double distance, long now) {
        if (distance < state.nearest - 1.5) {
            state.nearest = distance;
            state.lastEffective = now;
        }
        if (distance <= LOST_DISTANCE) {
            state.lastEffective = now;
            return false;
        }
        int seconds = Config.LOST_SECONDS.get();
        return seconds > 0 && now - state.lastEffective > seconds * 20L;
    }

    /**
     * A lost mob (e.g. stuck in a cave) is no use to the siege: siege mobs disappear (a new one spawns at the
     * front), naturally spawned monsters too, unless they were named or are kept for some reason
     * (then they only leave the siege and behave normally again).
     */
    private static void removeLost(ServerLevel level, Mob mob) {
        boolean named = mob.hasCustomName() && !SiegeDebug.hasDebugName(mob);
        if (mob.getTags().contains(SiegeManager.SIEGE_TAG) || (!named && !mob.isPersistenceRequired())) {
            recall(level, mob);
            return;
        }
        releaseFromSiege(mob);
    }

    /** The mob leaves the siege for good and behaves like a normal monster again. */
    static void releaseFromSiege(Mob mob) {
        forget(mob);
        mob.addTag(SiegeRules.RELEASED_TAG);
        SiegeTeam.leave(mob);
        SiegeManager.clearFollowRange(mob);
        SiegeDebug.clearName(mob);
        mob.setTarget(null);
        mob.getNavigation().stop();
    }

    private static void forget(Mob mob) {
        MobState state = MOBS.remove(mob);
        if (state != null && state.route() != null) {
            state.route().removeMember(mob.getId());
        }
    }

    /** A siege mob that is no use where it is disappears; the siege spawns a new one at a front. */
    static void recall(ServerLevel level, Mob mob) {
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
        forget(mob);
        float power = Config.SELF_DESTRUCT_POWER.get().floatValue();
        if (power > 0) {
            level.explode(mob, mob.getX(), mob.getY() + 0.5, mob.getZ(), power,
                    Config.SELF_DESTRUCT_BREAKS_BLOCKS.get() ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
        }
        mob.kill();
    }
}
