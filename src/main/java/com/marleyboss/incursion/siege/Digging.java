package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.SpellcasterIllager;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Lets mobs that are stuck on their way to the Core break through obstacles:
 * <ul>
 *     <li>Melee mobs dig. Speed depends on block hardness and on the mob: a fitting tool (pickaxe on stone)
 *     works like a player's tool, otherwise the mob's attack damage decides.</li>
 *     <li>Creepers blow themselves up at the obstacle (breach explosion, destroys blocks).</li>
 *     <li>Endermen pick the block up.</li>
 *     <li>Ranged mobs, spiders and flying mobs never dig.</li>
 * </ul>
 * Several mobs digging at the same block add up their progress.
 */
public final class Digging {
    /** Scoreboard tag on creepers that ignited to blow a hole into a wall. */
    public static final String BREACH_TAG = "incursion_breach";
    /** Blocks mobs can never break. */
    public static final TagKey<Block> SIEGE_PROOF = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "siege_proof"));

    /** The logic runs every INTERVAL ticks per mob; progress is scaled accordingly. */
    private static final int INTERVAL = 5;
    /** How far (from the eyes) a mob can reach a block to dig it. */
    private static final double REACH = 2.8;
    /** Farther obstacles make the mob walk up to them first. */
    private static final double APPROACH_RANGE = 24.0;

    private enum Kind { NONE, MELEE, CREEPER, ENDERMAN }

    private static final class MobState {
        double bestDistance = Double.MAX_VALUE;
        long lastProgress;
        @Nullable
        BlockPos digPos;
        int takeTicks;

        MobState(long now) {
            this.lastProgress = now;
        }
    }

    private static final class BlockProgress {
        final BlockPos pos;
        final int crackId;
        float progress;
        long lastTouched;
        int lastStage = -1;

        BlockProgress(BlockPos pos) {
            this.pos = pos;
            // Negative ids never clash with entity ids (the client tracks cracks per id).
            this.crackId = -1 - (pos.hashCode() & 0x3FFFFFFF);
        }
    }

    private static final Map<Mob, MobState> MOBS = new WeakHashMap<>();
    private static final Map<Long, BlockProgress> BLOCKS = new HashMap<>();
    private static long lastCleanup = 0;

    private Digging() {
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if ((mob.tickCount + mob.getId()) % INTERVAL != 0 || !mob.isAlive()) {
            return;
        }
        ActiveSiege siege = ActiveSiege.get(level);
        if (siege == null || mob.getTarget() != siege.target || !Config.DIGGING_ENABLED.get()) {
            MOBS.remove(mob);
            return;
        }
        long now = level.getGameTime();
        cleanup(level, now);

        Kind kind = kindOf(mob);
        if (kind == Kind.NONE) {
            return;
        }
        MobState state = MOBS.computeIfAbsent(mob, m -> new MobState(now));

        double distance = mob.distanceTo(siege.target);
        boolean canAttack = kind != Kind.CREEPER
                && mob.isWithinMeleeAttackRange(siege.target)
                && mob.getSensing().hasLineOfSight(siege.target);
        if (canAttack || distance < state.bestDistance - 1.0) {
            state.bestDistance = distance;
            state.lastProgress = now;
            state.digPos = null;
            state.takeTicks = 0;
            return;
        }
        long stuckTicks = Config.STUCK_SECONDS.get() * 20L;
        if (now - state.lastProgress < stuckTicks) {
            return;
        }

        // Stuck: find the block in the way.
        BlockPos obstacle = state.digPos;
        if (obstacle == null || !isValidObstacle(level, mob, obstacle, siege)) {
            obstacle = findObstacle(level, mob, siege);
            if (obstacle == null || !obstacle.equals(state.digPos)) {
                state.takeTicks = 0;
            }
            state.digPos = obstacle;
        }
        if (obstacle == null) {
            return;
        }

        mob.getLookControl().setLookAt(obstacle.getX() + 0.5, obstacle.getY() + 0.5, obstacle.getZ() + 0.5);
        boolean done = switch (kind) {
            case CREEPER -> breach((Creeper) mob);
            case ENDERMAN -> take(level, (EnderMan) mob, obstacle, state);
            default -> dig(level, mob, obstacle, now);
        };
        if (done) {
            // Give the mob a second to walk on before it counts as stuck again.
            state.digPos = null;
            state.takeTicks = 0;
            state.bestDistance = distance;
            state.lastProgress = now - stuckTicks + 20;
            mob.getNavigation().moveTo(siege.target, 1.0);
        }
    }

    private static Kind kindOf(Mob mob) {
        if (!SiegeRules.isAttacker(mob) || SiegeRules.isNonDigger(mob)) {
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
    // Finding the obstacle
    // ------------------------------------------------------------------

    @Nullable
    private static BlockPos findObstacle(ServerLevel level, Mob mob, ActiveSiege siege) {
        Vec3 eyes = mob.getEyePosition();
        Vec3 aim = siege.target.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eyes, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            double distance = eyes.distanceTo(hit.getLocation());
            if (distance <= REACH && isValidObstacle(level, mob, pos, siege)) {
                return pos;
            }
            if (distance > REACH && distance <= APPROACH_RANGE) {
                // The wall is farther away: walk up to it first.
                mob.getNavigation().moveTo(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, 1.0);
                return null;
            }
        }

        // Nothing between the eyes and the Core: check the blocks right in front (head first, then feet).
        double dx = siege.corePos.getX() + 0.5 - mob.getX();
        double dz = siege.corePos.getZ() + 0.5 - mob.getZ();
        Direction direction = Direction.getNearest(dx, 0.0, dz);
        BlockPos front = mob.blockPosition().relative(direction);
        for (BlockPos candidate : new BlockPos[]{front.above(), front}) {
            if (isValidObstacle(level, mob, candidate, siege)) {
                return candidate;
            }
        }
        mob.getNavigation().moveTo(siege.target, 1.0);
        return null;
    }

    private static boolean isValidObstacle(ServerLevel level, Mob mob, BlockPos pos, ActiveSiege siege) {
        if (pos.equals(siege.corePos) || pos.equals(mob.blockPosition().below())) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof CoreBlock || state.is(SIEGE_PROOF)
                || state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        if (state.getDestroySpeed(level, pos) < 0) {
            return false; // bedrock, barriers ...
        }
        return mob.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= REACH + 1.0;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Melee digging. Returns true when the block broke. */
    private static boolean dig(ServerLevel level, Mob mob, BlockPos pos, long now) {
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        float perTick = hardness <= 0 ? 1.0F : digSpeed(mob, state) / hardness / (canHarvest(mob, state) ? 30.0F : 100.0F);

        BlockProgress block = BLOCKS.computeIfAbsent(pos.asLong(), key -> new BlockProgress(pos.immutable()));
        block.progress += perTick * INTERVAL;
        block.lastTouched = now;

        mob.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.HOSTILE, 0.6F, 0.75F);

        if (block.progress >= 1.0F) {
            BLOCKS.remove(pos.asLong());
            level.destroyBlockProgress(block.crackId, pos, -1);
            level.destroyBlock(pos, Config.DUG_BLOCKS_DROP.get(), mob);
            return true;
        }
        int stage = Math.min(9, (int) (block.progress * 10.0F));
        if (stage != block.lastStage) {
            block.lastStage = stage;
            level.destroyBlockProgress(block.crackId, pos, stage);
        }
        return false;
    }

    /** Effective "tool speed" of the mob for this block, like a player's mining speed. */
    private static float digSpeed(Mob mob, BlockState state) {
        ItemStack tool = mob.getMainHandItem();
        float toolSpeed = tool.isEmpty() ? 1.0F : tool.getDestroySpeed(state);
        float withTool = toolSpeed > 1.0F ? toolSpeed * Config.TOOL_DIG_SPEED_MULTIPLIER.get().floatValue() : 0.0F;
        AttributeInstance damage = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        float attack = damage != null ? (float) damage.getValue() : 1.0F;
        float withHands = attack / 3.0F * Config.MELEE_DIG_SPEED_MULTIPLIER.get().floatValue();
        return Math.max(withTool, withHands);
    }

    private static boolean canHarvest(Mob mob, BlockState state) {
        return !state.requiresCorrectToolForDrops() || mob.getMainHandItem().isCorrectToolForDrops(state);
    }

    /** Creeper sacrifice: ignite next to the wall. The explosion handler lets it destroy blocks. */
    private static boolean breach(Creeper creeper) {
        if (!creeper.isIgnited()) {
            creeper.addTag(BREACH_TAG);
            creeper.ignite();
        }
        return false;
    }

    /** Enderman: after a short moment the block is taken away (blocks with an inventory are dug instead). */
    private static boolean take(ServerLevel level, EnderMan enderman, BlockPos pos, MobState state) {
        BlockState blockState = level.getBlockState(pos);
        if (blockState.hasBlockEntity()) {
            return dig(level, enderman, pos, level.getGameTime());
        }
        float hardness = blockState.getDestroySpeed(level, pos);
        int required = Math.min(200, 20 + (int) (hardness * 10.0F));
        state.takeTicks += INTERVAL;
        if (state.takeTicks < required) {
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
    // Housekeeping
    // ------------------------------------------------------------------

    /** Forgets dig progress on blocks nobody worked on for 3 seconds (and removes their cracks). */
    private static void cleanup(ServerLevel level, long now) {
        if (now - lastCleanup < 20) {
            return;
        }
        lastCleanup = now;
        Iterator<BlockProgress> iterator = BLOCKS.values().iterator();
        while (iterator.hasNext()) {
            BlockProgress block = iterator.next();
            if (now - block.lastTouched > 60) {
                level.destroyBlockProgress(block.crackId, block.pos, -1);
                iterator.remove();
            }
        }
    }

    public static void clear() {
        MOBS.clear();
        BLOCKS.clear();
        lastCleanup = 0;
    }
}
