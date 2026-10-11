package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.siege.MobRoles.Role;
import com.marleyboss.incursion.siege.SiegeAI.MobState;
import com.marleyboss.incursion.siege.SiegeAI.Mode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * Archers (and other ranged mobs) attacking the Core:
 * <ol>
 *     <li>If the Core can be hit from where the archer stands, it stays there and shoots.</li>
 *     <li>Otherwise it searches a firing position around the Core from which an exposed face can be hit,
 *     walks there and shoots from there.</li>
 *     <li>If the Core is walled in on all sides, blast archers look for a position from which they can hit the
 *     wall around the Core and blow it open. The others wait at a distance (and give cover fire on players).</li>
 * </ol>
 */
final class SiegeArchers {
    /** Archers shoot at the Core from at most this far away. */
    static final double SHOOT_RANGE = 18.0;
    private static final double MIN_SHOOT_RANGE = 1.0;
    /** Wall blocks at most this far from the Core are worth a blast arrow. */
    private static final double BLAST_WALL_RANGE = 4.5;
    private static final double MIN_BLAST_DISTANCE = 4.0;
    private static final int SEARCH_COLUMNS = 48;
    private static final long SPOT_TIMEOUT = 600;
    private static final long BAD_SPOT_TICKS = 1200;
    private static final int MAX_SEARCHES_PER_TICK = 2;
    private static final int MAX_PATH_CHECKS = 3;
    private static final double[] SPEEDS = {2.4, 3.0};
    private static final float BLAST_MAX_RESISTANCE = 100.0F;
    /** A hunter holds its post for this long, then looks around again. */
    private static final long POST_TICKS = 1200;
    /** Landings of the stairs are preferred firing positions (they count as this much closer). */
    private static final double LANDING_BONUS = 8.0;

    /** Firing positions that turned out useless (pos -> game time until which they are skipped). */
    private static final Map<Long, Long> BAD = new HashMap<>();
    /** Firing positions in use (pos -> number of archers that chose them, refreshed when searching). */
    private static final List<BlockPos> TAKEN = new ArrayList<>();
    private static long searchTick = -1;
    private static int searchesThisTick;

    private SiegeArchers() {
    }

    static void clear() {
        BAD.clear();
        TAKEN.clear();
        searchTick = -1;
        searchesThisTick = 0;
    }

    /**
     * One archer step. Returns true if the archer is busy (shooting, blasting, walking to a firing position);
     * false if it has nothing to shoot at and no firing position it can walk to. Then it follows the siege's
     * routes like a melee mob (a longer way, but better than standing around) and keeps checking.
     */
    static boolean tick(ServerLevel level, Mob mob, MobState state, ActiveSiege siege, long now) {
        BlockPos core = siege.corePos;
        boolean blast = MobRoles.of(mob) == Role.BLAST_ARCHER;

        // 1. Shoot from where it stands.
        if (shootFromHere(level, mob, state, siege, now)) {
            return true;
        }
        if (state.blockedShots >= 3) {
            markBad(mob.blockPosition(), now);
            if (state.spot != null) {
                markBad(state.spot, now);
                state.spot = null;
            }
        }
        state.blockedShots = 0;

        // 2. Blast archer at its wall spot: blow the wall open.
        if (blast && state.spot != null && state.spotWall != null && isAt(mob, state.spot)) {
            BlockPos wall = currentWall(level, mob, core);
            if (wall != null) {
                SiegeAI.control(state, Mode.HOLD, now);
                state.lookAt = Vec3.atCenterOf(wall);
                state.status("blast");
                if (now >= state.nextShot) {
                    Vec3 aim = Vec3.atCenterOf(wall);
                    if (fire(level, mob, aim, wall)) {
                        state.nextShot = now + Config.BLAST_SHOT_SECONDS.get() * 20L;
                        state.effective(now);
                    } else {
                        state.nextShot = now + 20;
                        markBad(state.spot, now);
                        state.spot = null;
                    }
                }
                return true;
            }
            state.spot = null; // the wall is gone; look again
            state.nextSpotSearch = now;
        }

        // 3a. At a post (a landing of the stairs): hold it, watch the way, shoot what comes (hunters).
        if (state.spot != null && state.spotPost && isAt(mob, state.spot)) {
            SiegeAI.control(state, Mode.HOLD, now);
            state.gotoPos = null;
            state.lookAt = Vec3.atCenterOf(core);
            state.status("guard");
            if (!SiegeProgress.isStalled(now)) {
                state.lastUseful = now; // guarding is a job: no self-destruct while the siege is making progress
            }
            if (now - state.spotSince > POST_TICKS) {
                state.spot = null; // look around again now and then
                state.nextSpotSearch = now;
            }
            return true;
        }

        // 3. Walk to a firing position.
        if (state.spot != null) {
            boolean arrived = isAt(mob, state.spot);
            boolean timedOut = now - state.spotSince > SPOT_TIMEOUT || state.pathFailures > 3
                    || (now - state.spotSince > 40 && mob.getNavigation().isDone());
            if (arrived || timedOut) {
                // Arrived but still cannot shoot (or never got there): this spot is no good.
                markBad(state.spot, now);
                state.spot = null;
                state.spotWall = null;
            } else {
                SiegeAI.control(state, Mode.GOTO, now);
                state.gotoPos = state.spot;
                state.lookAt = null;
                state.status(state.spotPost ? "goto_post" : state.spotWall != null ? "goto_blast" : "goto_spot");
                return true;
            }
        }

        // 4. Find a firing position it can walk to.
        if (now >= state.nextSpotSearch && searchAllowed(now)) {
            state.nextSpotSearch = now + 60;
            Spot found = search(level, mob, core, blast, MobRoles.isHunter(mob), now);
            if (found != null) {
                state.spot = found.pos;
                state.spotPost = found.post();
                state.spotWall = found.wall;
                state.spotSince = now;
                state.pathFailures = 0;
                SiegeAI.control(state, Mode.GOTO, now);
                state.gotoPos = found.pos;
                state.status(found.post() ? "goto_post" : found.wall != null ? "goto_blast" : "goto_spot");
                return true;
            }
            state.noSpotUntil = now + 300;
        }

        // 5. Nothing to shoot at: follow the routes (SiegeAI).
        return false;
    }

    /** Shoots at the Core if it can be hit from where the archer stands (also while riding). */
    static boolean shootFromHere(ServerLevel level, Mob mob, MobState state, ActiveSiege siege, long now) {
        BlockPos core = siege.corePos;
        boolean blast = MobRoles.of(mob) == Role.BLAST_ARCHER;
        Vec3 eyes = mob.getEyePosition();
        double distance = SiegeAim.distanceToCore(eyes, core);
        boolean badHere = BAD.containsKey(mob.blockPosition().asLong());
        if (distance > SHOOT_RANGE || distance < MIN_SHOOT_RANGE || badHere || state.blockedShots >= 3) {
            return false;
        }
        Vec3 point = SiegeAim.visibleCorePoint(level, eyes, core, mob);
        if (point == null) {
            return false;
        }
        SiegeAI.control(state, Mode.HOLD, now);
        state.lookAt = point;
        state.status("shoot");
        if (now >= state.nextShot) {
            if (fire(level, mob, point, core)) {
                state.nextShot = now + (blast ? 50 : 30) + mob.getRandom().nextInt(10);
                state.effective(now);
                state.blockedShots = 0;
            } else {
                state.nextShot = now + 10;
                state.blockedShots++; // the arc is blocked even though the line is free
            }
        }
        return true;
    }

    /**
     * Archers only give cover fire on players while they have nothing to shoot at on the Core
     * (no firing position found recently).
     */
    static boolean mayGiveCoverFire(Mob mob, long now) {
        MobState state = SiegeAI.stateOf(mob);
        return state != null && now < state.noSpotUntil && state.spot == null;
    }

    private static boolean searchAllowed(long now) {
        if (now != searchTick) {
            searchTick = now;
            searchesThisTick = 0;
        }
        if (searchesThisTick >= MAX_SEARCHES_PER_TICK) {
            return false;
        }
        searchesThisTick++;
        return true;
    }

    private static boolean isAt(Mob mob, BlockPos spot) {
        double dx = spot.getX() + 0.5 - mob.getX();
        double dz = spot.getZ() + 0.5 - mob.getZ();
        return dx * dx + dz * dz < 1.0 && Math.abs(spot.getY() - mob.getY()) < 1.5;
    }

    private static void markBad(BlockPos spot, long now) {
        BAD.put(spot.asLong(), now + BAD_SPOT_TICKS);
        TAKEN.remove(spot);
    }

    // ------------------------------------------------------------------
    // Shooting
    // ------------------------------------------------------------------

    /** Shoots one arrow so that it lands on {@code aim}. False if every possible arc is blocked. */
    private static boolean fire(ServerLevel level, Mob mob, Vec3 aim, BlockPos goalBlock) {
        if (!(mob instanceof AbstractSkeleton) && mob instanceof RangedAttackMob ranged && !(mob instanceof CrossbowAttackMob)
                && !(mob.getMainHandItem().getItem() instanceof ProjectileWeaponItem)) {
            // Witches and modded ranged mobs use their own attack.
            mob.getLookControl().setLookAt(aim.x, aim.y, aim.z);
            net.minecraft.world.entity.LivingEntity target = mob.getTarget();
            if (target == null) {
                return false;
            }
            ranged.performRangedAttack(target, 1.0F);
            return true;
        }
        Vec3 from = new Vec3(mob.getX(), mob.getEyeY() - 0.1, mob.getZ());
        for (double speed : SPEEDS) {
            Vec3 direction = SiegeAim.solve(from, aim, speed);
            if (direction != null && SiegeAim.flightReaches(level, mob, from, direction, speed, aim, goalBlock)) {
                SiegeExplosives.shoot(level, mob, direction, speed, aim);
                return true;
            }
        }
        return false;
    }

    /** The wall block between this mob and the Core that a blast arrow should hit, or null. */
    @Nullable
    private static BlockPos currentWall(Level level, Mob mob, BlockPos core) {
        return wallFrom(level, mob.getEyePosition(), mob, core);
    }

    @Nullable
    private static BlockPos wallFrom(Level level, Vec3 eyes, Mob mob, BlockPos core) {
        Vec3 center = Vec3.atCenterOf(core);
        BlockHitResult hit = level.clip(new ClipContext(eyes, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos wall = hit.getBlockPos();
        if (wall.equals(core) || Vec3.atCenterOf(wall).distanceTo(center) > BLAST_WALL_RANGE
                || eyes.distanceTo(hit.getLocation()) < MIN_BLAST_DISTANCE) {
            return null;
        }
        BlockState state = level.getBlockState(wall);
        if (state.is(Digging.SIEGE_PROOF) || state.getDestroySpeed(level, wall) < 0
                || state.getBlock().getExplosionResistance() >= BLAST_MAX_RESISTANCE) {
            return null;
        }
        return wall;
    }

    // ------------------------------------------------------------------
    // Finding a firing position
    // ------------------------------------------------------------------

    private record Spot(BlockPos pos, @Nullable BlockPos wall, double score, boolean post) {
    }

    /**
     * Tries random columns around the Core. A position is good if the Core can be hit from there (for blast
     * archers also: if a wall block right next to the Core can be hit). Prefers positions close to the archer,
     * about 10 blocks from the Core, and not already taken by other archers. The landings of the stairs are
     * preferred. Hunters without a firing position take a landing as a post. Only positions the archer can
     * actually walk to count; the others are remembered as useless for a while.
     */
    @Nullable
    private static Spot search(ServerLevel level, Mob mob, BlockPos core, boolean blast, boolean hunter, long now) {
        cleanup(now);
        RandomSource random = mob.getRandom();
        double eyeHeight = mob.getEyeHeight();
        List<Spot> shots = new ArrayList<>();
        List<Spot> walls = new ArrayList<>();
        for (int i = 0; i < SEARCH_COLUMNS; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double radius = 3.0 + random.nextDouble() * (SHOOT_RANGE - 4.0);
            int x = core.getX() + Mth.floor(Math.cos(angle) * radius + 0.5);
            int z = core.getZ() + Mth.floor(Math.sin(angle) * radius + 0.5);
            int found = 0;
            for (int y = core.getY() + 10; y >= core.getY() - 12 && found < 3; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!isStandable(level, pos)) {
                    continue;
                }
                found++;
                if (BAD.containsKey(pos.asLong())) {
                    continue;
                }
                Vec3 eyes = new Vec3(x + 0.5, y + eyeHeight, z + 0.5);
                double toCore = SiegeAim.distanceToCore(eyes, core);
                if (toCore > SHOOT_RANGE - 1.0 || toCore < MIN_SHOOT_RANGE + 1.0) {
                    continue;
                }
                double score = Math.sqrt(mob.blockPosition().distSqr(pos)) + Math.abs(toCore - 10.0) * 0.4
                        + taken(pos) * 6.0;
                if (SiegeAim.visibleCorePoint(level, eyes, core, mob) != null) {
                    shots.add(new Spot(pos, null, score, false));
                } else if (blast && shots.isEmpty()) {
                    BlockPos wall = wallFrom(level, eyes, mob, core);
                    if (wall != null) {
                        walls.add(new Spot(pos, wall, score, false));
                    }
                }
            }
        }
        List<Spot> posts = new ArrayList<>();
        addLandingSpots(level, mob, core, hunter, shots, posts);
        Spot result = reachable(mob, shots, now);
        if (result == null) {
            result = reachable(mob, walls, now);
        }
        if (result == null && hunter) {
            result = reachable(mob, posts, now);
        }
        if (result != null) {
            TAKEN.add(result.pos);
            while (TAKEN.size() > 64) {
                TAKEN.remove(0);
            }
        }
        return result;
    }

    /**
     * The landings of the stairs as firing positions (if the Core can be hit from there) or, for hunters, as posts
     * (from where they cover the way). The planners' landings are built by the siege's builders.
     */
    private static void addLandingSpots(ServerLevel level, Mob mob, BlockPos core, boolean hunter, List<Spot> shots,
                                        List<Spot> posts) {
        double eyeHeight = mob.getEyeHeight();
        for (SiegeFront front : SiegeCommander.fronts()) {
            for (SiegeFront.Landing landing : front.landings) {
                for (BlockPos pos : landing.spots()) {
                    if (BAD.containsKey(pos.asLong()) || !isStandable(level, pos)) {
                        continue;
                    }
                    Vec3 eyes = new Vec3(pos.getX() + 0.5, pos.getY() + eyeHeight, pos.getZ() + 0.5);
                    double toCore = SiegeAim.distanceToCore(eyes, core);
                    if (toCore > SHOOT_RANGE - 1.0) {
                        continue;
                    }
                    double score = Math.sqrt(mob.blockPosition().distSqr(pos)) + taken(pos) * 6.0 - LANDING_BONUS;
                    if (toCore >= MIN_SHOOT_RANGE && SiegeAim.visibleCorePoint(level, eyes, core, mob) != null) {
                        shots.add(new Spot(pos, null, score, false));
                    } else if (hunter) {
                        posts.add(new Spot(pos, null, score, true));
                    }
                }
            }
        }
    }

    /** The best spot the mob can walk to (checks at most a few paths); unreachable ones are marked useless. */
    @Nullable
    private static Spot reachable(Mob mob, List<Spot> spots, long now) {
        spots.sort(java.util.Comparator.comparingDouble(Spot::score));
        int checks = 0;
        for (Spot spot : spots) {
            if (checks >= MAX_PATH_CHECKS) {
                break;
            }
            if (isAt(mob, spot.pos)) {
                return spot;
            }
            checks++;
            Path path = mob.getNavigation().createPath(spot.pos, 0);
            if (path != null && path.canReach()) {
                return spot;
            }
            markBad(spot.pos, now);
        }
        return null;
    }

    private static int taken(BlockPos pos) {
        int count = 0;
        for (BlockPos other : TAKEN) {
            if (other.distManhattan(pos) <= 1) {
                count++;
            }
        }
        return count;
    }

    private static void cleanup(long now) {
        Iterator<Map.Entry<Long, Long>> iterator = BAD.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() < now) {
                iterator.remove();
            }
        }
    }

    static boolean isStandable(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState ground = level.getBlockState(below);
        if (ground.getCollisionShape(level, below).isEmpty() || !ground.isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        return RoutePlanner.isPassable(level, pos) && RoutePlanner.isPassable(level, pos.above())
                && level.getFluidState(pos).isEmpty();
    }
}
