package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
 *     <li>Stuck mobs (no progress towards the Core for a few seconds) join or plan a {@link Route}.
 *     Along the route, diggers dig, builders place floor blocks, creepers blow up walls,
 *     endermen take blocks away. Mobs that cannot do the next job wait a few steps behind.</li>
 *     <li>Sapper endermen place their TNT next to the Core; blast archers shoot explosive arrows at walls.</li>
 *     <li>A mob that achieved nothing for a long time blows itself up.</li>
 * </ul>
 */
public final class SiegeAI {
    private static final int INTERVAL = 5;
    private static final double REACH = 4.0;
    private static final int LOOKAHEAD = 24;
    private static final double ESCORT_RADIUS = 12.0;
    private static final int FUSE_TICKS = 40;
    private static final double SAPPER_RANGE = 20.0;
    /** How far a stuck mob walks to help on another route. */
    private static final double HELP_RANGE = 40.0;
    /** Give up walking to another route after this many ticks. */
    private static final long APPROACH_TICKS = 400;

    enum Mode { NONE, ROUTE, HOLD, GOTO }

    enum Kind {
        NONE(false, false),
        MELEE(true, false),
        CREEPER(true, false),
        ENDERMAN(true, false),
        BUILDER(false, true);

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
        @Nullable
        Route route;
        int routeIndex;
        int bestIndex;
        int walkLimit;
        @Nullable
        BlockPos actionPos;
        int actionTicks;
        @Nullable
        BlockPos lookPos;
        Mode mode = Mode.NONE;
        long controlUntil;
        @Nullable
        Route approach;
        long approachStart;
        long noApproachUntil;
        long fuseStart = -1;
        long nextShot;
        long nextTeleport;

        MobState(long now) {
            this.lastCloser = now;
            this.lastUseful = now;
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

    /** The attacker hit the Core or a player. */
    public static void markUseful(@Nullable Entity attacker, long now) {
        if (attacker instanceof Mob mob) {
            MobState state = MOBS.get(mob);
            if (state != null) {
                state.lastUseful = now;
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
            MOBS.remove(mob);
            return;
        }
        long now = level.getGameTime();
        Digging.cleanup(level, now);
        MobState state = MOBS.computeIfAbsent(mob, m -> new MobState(now));
        Role role = MobRoles.of(mob);

        if (state.fuseStart >= 0) {
            tickFuse(level, mob, state, now);
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
        int selfDestruct = Config.SELF_DESTRUCT_SECONDS.get();
        if (selfDestruct > 0 && now - state.lastUseful > selfDestruct * 20L) {
            startFuse(level, mob, state, now);
            return;
        }

        if (role == Role.SAPPER && mob instanceof EnderMan enderman && carriesTnt(enderman)) {
            tickSapper(level, enderman, state, siege, now);
            return;
        }

        double distance = mob.distanceTo(siege.target);
        if (target != siege.target) {
            dropRoute(state, now, distance);
            return;
        }
        if (role == Role.BLAST_ARCHER) {
            blastShot(level, mob, state, siege, now);
        }

        if (canHitCore(mob, siege, distance)) {
            dropRoute(state, now, distance);
            state.bestDistance = distance;
            state.lastCloser = now;
            state.lastUseful = now;
            return;
        }

        Kind kind = kindOf(mob, role);
        if (state.route == null) {
            if (state.approach != null) {
                if (!tickApproach(mob, state, siege, now)) {
                    return;
                }
            } else {
                if (distance < state.bestDistance - 1.0) {
                    state.bestDistance = distance;
                    state.lastCloser = now;
                    state.lastUseful = now;
                    return;
                }
                if (now - state.lastCloser < Config.STUCK_SECONDS.get() * 20L || !canUseRoutes(mob)) {
                    return;
                }
                Route route = acquireRoute(level, mob, state, kind, siege, now);
                if (route == null) {
                    return;
                }
                state.route = route;
            }
            state.bestIndex = state.routeIndex;
            state.actionPos = null;
            state.actionTicks = 0;
        }
        followRoute(level, mob, state, kind, siege, now, distance);
    }

    private static boolean canHitCore(Mob mob, ActiveSiege siege, double distance) {
        if (!mob.getSensing().hasLineOfSight(siege.target)) {
            return false;
        }
        if (Targeting.isRanged(mob)) {
            return distance <= 20.0;
        }
        if (mob instanceof Creeper) {
            return distance <= 3.0;
        }
        return mob.isWithinMeleeAttackRange(siege.target);
    }

    private static boolean canUseRoutes(Mob mob) {
        return mob instanceof PathfinderMob && !(mob instanceof FlyingMob) && !(mob instanceof Spider);
    }

    static Kind kindOf(Mob mob, Role role) {
        if (role == Role.BUILDER) {
            return Config.BUILDING_ENABLED.get() ? Kind.BUILDER : Kind.NONE;
        }
        if (role == Role.WARRIOR || role.isArcher() || !Config.DIGGING_ENABLED.get() || SiegeRules.isNonDigger(mob)) {
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
            Route wanted = Route.findHelpWanted(mob.position(), siege.corePos, kind.canDig, kind.canBuild, HELP_RANGE, now);
            if (wanted != null) {
                state.approach = wanted;
                state.approachStart = now;
                return null;
            }
        }
        if (!Route.canPlan(now)) {
            return null; // the planner is busy, try again in a moment
        }
        Route route = Route.plan(level, mob.blockPosition(), siege.corePos, siege.coreLevel, siege.siegeRadius + 8, now);
        if (route == null) {
            state.lastCloser = now; // no way at all from here; try again later
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
        state.lookPos = null;
        return false;
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
        state.lookPos = null;
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
            if (index >= last) {
                dropRoute(state, now, distance); // arrived; the normal attack takes over
                return;
            }
            control(state, Mode.ROUTE, now);
            state.walkLimit = Math.min(last, index + LOOKAHEAD);
            state.lookPos = null;
            return;
        }
        Route.Action action = route.action(level, pending);
        if (action == null) {
            return;
        }
        boolean capable = action.dig() ? kind.canDig : kind.canBuild;
        control(state, Mode.ROUTE, now);
        if (!capable) {
            // Somebody else has to do this; wait a few steps behind and let the siege know what is missing.
            SiegeProgress.need(action.dig() ? SiegeProgress.Need.DIGGER : SiegeProgress.Need.BUILDER, now);
            route.markNeed(pending, action.dig(), now);
            state.walkLimit = Math.max(index, pending - 3);
            state.lookPos = null;
            return;
        }
        state.walkLimit = pending - 1;
        state.lookPos = action.pos();
        route.markWork(pending, now);
        double reach = mob.getEyePosition().distanceTo(Vec3.atCenterOf(action.pos()));
        if (reach > REACH) {
            return; // still walking up to the work spot
        }
        if (!action.pos().equals(state.actionPos)) {
            state.actionPos = action.pos();
            state.actionTicks = 0;
        }
        if (perform(level, mob, kind, action, state, now)) {
            state.lastUseful = now;
            state.actionPos = null;
            state.actionTicks = 0;
            SiegeProgress.progress(action.pos(), now);
        }
    }

    private static void control(MobState state, Mode mode, long now) {
        state.mode = mode;
        state.controlUntil = now + INTERVAL * 2L;
    }

    private static boolean perform(ServerLevel level, Mob mob, Kind kind, Route.Action action, MobState state, long now) {
        if (!action.dig()) {
            return build(level, mob, action.pos(), state);
        }
        return switch (kind) {
            case CREEPER -> breach((Creeper) mob);
            case ENDERMAN -> take(level, (EnderMan) mob, action.pos(), state);
            default -> Digging.dig(level, mob, action.pos(), INTERVAL, now);
        };
    }

    /** Builder: after a short moment the floor block appears. */
    private static boolean build(ServerLevel level, Mob mob, BlockPos pos, MobState state) {
        state.actionTicks += INTERVAL;
        mob.swing(InteractionHand.MAIN_HAND);
        if (state.actionTicks < Config.BUILD_TICKS.get()) {
            return false;
        }
        BlockState block = Config.builderBlock();
        if (!level.isUnobstructed(block, pos, CollisionContext.empty())) {
            return false; // somebody is standing there
        }
        BlockState current = level.getBlockState(pos);
        if (!current.isAir() && current.getFluidState().isEmpty() && !current.canBeReplaced()) {
            level.destroyBlock(pos, true, mob);
        }
        level.setBlockAndUpdate(pos, block);
        level.playSound(null, pos, block.getSoundType().getPlaceSound(), SoundSource.HOSTILE, 1.0F, 0.9F);
        return true;
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
        enderman.setCarriedBlock(blockState);
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
        if (state.lookPos != null) {
            mob.getLookControl().setLookAt(state.lookPos.getX() + 0.5, state.lookPos.getY() + 0.5, state.lookPos.getZ() + 0.5);
        }
        if (state.mode == Mode.GOTO && state.approach != null) {
            if (mob.getNavigation().isDone() || mob.tickCount % 20 == 0) {
                BlockPos meet = state.approach.meetingPoint();
                mob.getNavigation().moveTo(meet.getX() + 0.5, meet.getY(), meet.getZ() + 0.5, 1.0);
            }
            return;
        }
        if (state.mode != Mode.ROUTE || state.route == null) {
            mob.getNavigation().stop();
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
        if (!isAt(mob, goal)) {
            mob.getMoveControl().setWantedPosition(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.0);
        }
    }

    private static boolean isAt(Mob mob, BlockPos node) {
        double dx = node.getX() + 0.5 - mob.getX();
        double dz = node.getZ() + 0.5 - mob.getZ();
        return dx * dx + dz * dz < 0.2 && Math.abs(node.getY() - mob.getY()) < 0.8;
    }

    // ------------------------------------------------------------------
    // Sapper endermen
    // ------------------------------------------------------------------

    private static boolean carriesTnt(EnderMan enderman) {
        BlockState carried = enderman.getCarriedBlock();
        return carried != null && carried.is(Blocks.TNT);
    }

    private static void tickSapper(ServerLevel level, EnderMan enderman, MobState state, ActiveSiege siege, long now) {
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
        state.lookPos = spot;
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
            state.lastUseful = now;
            state.actionPos = null;
            state.mode = Mode.NONE;
            state.lookPos = null;
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
    // Blast archers
    // ------------------------------------------------------------------

    /** A blast archer that cannot see the Core shoots an explosive arrow at the wall in the way. */
    private static void blastShot(ServerLevel level, Mob mob, MobState state, ActiveSiege siege, long now) {
        if (now < state.nextShot || mob.distanceTo(siege.target) > 24.0 || mob.getSensing().hasLineOfSight(siege.target)) {
            return;
        }
        Vec3 eyes = mob.getEyePosition();
        Vec3 aim = Vec3.atCenterOf(siege.corePos);
        BlockHitResult hit = level.clip(new ClipContext(eyes, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        if (hit.getType() == HitResult.Type.BLOCK && eyes.distanceTo(hit.getLocation()) >= 3.5) {
            SiegeExplosives.shoot(level, mob, aim);
            state.nextShot = now + Config.BLAST_SHOT_SECONDS.get() * 20L;
        } else {
            state.nextShot = now + 20;
        }
    }

    // ------------------------------------------------------------------
    // Self-destruct
    // ------------------------------------------------------------------

    private static void startFuse(ServerLevel level, Mob mob, MobState state, long now) {
        state.fuseStart = now;
        state.route = null;
        state.lookPos = null;
        control(state, Mode.HOLD, now);
        mob.getNavigation().stop();
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 1.0F, 0.7F);
    }

    private static void tickFuse(ServerLevel level, Mob mob, MobState state, long now) {
        control(state, Mode.HOLD, now);
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
