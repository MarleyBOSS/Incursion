package com.marleyboss.incursion.fallout;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.block.EternalFire;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * One big explosion, worked off over many ticks so the game does not freeze:
 * <ol>
 *     <li>BLAST: flash, sound, damage to creatures.</li>
 *     <li>CARVE: remove the crater blocks from the centre outwards.</li>
 *     <li>SCORCH: turn the crater floor and the contaminated surroundings into wasteland.</li>
 *     <li>BURN: the ground burns with eternal fire (see EternalFire).</li>
 *     <li>FINISH: remove dropped items in the crater, release the chunks.</li>
 * </ol>
 */
public class CraterJob {
    private static final int PHASE_BLAST = 0;
    private static final int PHASE_CARVE = 1;
    private static final int PHASE_SCORCH = 2;
    private static final int PHASE_FINISH = 3;
    /** Numbered last so that jobs saved by older versions keep their phase. */
    private static final int PHASE_BURN = 4;
    private static final int MAX_CHUNK_WAIT_TICKS = 200;

    final ResourceKey<Level> dimension;
    final BlockPos center;
    final int craterRadius;
    /** 0 = no contamination (no scorch phase). */
    final int contaminationRadius;
    private final long seed;
    private int phase;
    private int cursor;

    // Runtime only
    @Nullable
    private int[] offsets;
    private boolean ticketsAdded;
    private boolean chunksReady;
    private int waitTicks;

    CraterJob(ResourceKey<Level> dimension, BlockPos center, int craterRadius, int contaminationRadius, long seed) {
        this.dimension = dimension;
        this.center = center.immutable();
        this.craterRadius = craterRadius;
        this.contaminationRadius = contaminationRadius;
        this.seed = seed;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.location().toString());
        tag.putLong("Pos", center.asLong());
        tag.putInt("Crater", craterRadius);
        tag.putInt("Contamination", contaminationRadius);
        tag.putLong("Seed", seed);
        tag.putInt("Phase", phase);
        tag.putInt("Cursor", cursor);
        return tag;
    }

    @Nullable
    static CraterJob load(CompoundTag tag) {
        ResourceKey<Level> dimension = FalloutData.parseDimension(tag.getString("Dimension"));
        if (dimension == null) {
            return null;
        }
        CraterJob job = new CraterJob(dimension, BlockPos.of(tag.getLong("Pos")), tag.getInt("Crater"),
                tag.getInt("Contamination"), tag.getLong("Seed"));
        job.phase = tag.getInt("Phase");
        job.cursor = tag.getInt("Cursor");
        return job;
    }

    /** Radius (in blocks) of the area the job works on. */
    int areaRadius() {
        return Math.max(craterRadius + 2, contaminationRadius + 2);
    }

    /** Runs one tick of work. Returns true when the job is finished. */
    boolean tick(ServerLevel level) {
        if (!ticketsAdded) {
            Fallout.setTickets(level, this, true);
            ticketsAdded = true;
            chunksReady = false;
            waitTicks = 0;
        }
        if (!chunksReady) {
            chunksReady = allChunksLoaded(level) || ++waitTicks > MAX_CHUNK_WAIT_TICKS;
            if (!chunksReady) {
                return false;
            }
        }
        switch (phase) {
            case PHASE_BLAST -> {
                blast(level);
                phase = PHASE_CARVE;
                cursor = 0;
            }
            case PHASE_CARVE -> carve(level);
            case PHASE_SCORCH -> scorch(level);
            case PHASE_BURN -> burn(level);
            default -> {
                finish(level);
                return true;
            }
        }
        return false;
    }

    private boolean allChunksLoaded(ServerLevel level) {
        int r = areaRadius();
        for (int cx = (center.getX() - r) >> 4; cx <= (center.getX() + r) >> 4; cx++) {
            for (int cz = (center.getZ() - r) >> 4; cz <= (center.getZ() + r) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Phases
    // ------------------------------------------------------------------

    private void blast(ServerLevel level) {
        double x = center.getX() + 0.5;
        double y = center.getY() + 0.5;
        double z = center.getZ() + 0.5;
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 16.0F, 0.5F);
        level.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 16.0F, 0.6F);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 6, craterRadius * 0.3, craterRadius * 0.2,
                craterRadius * 0.3, 0.0);
        level.sendParticles(ParticleTypes.FLASH, x, y, z, 3, 0.0, 0.0, 0.0, 0.0);

        double reach = craterRadius * 1.25;
        float maxDamage = Config.EXPLOSION_DAMAGE.get().floatValue();
        if (maxDamage <= 0) {
            return;
        }
        DamageSource source = level.damageSources().explosion(null, null);
        Vec3 origin = new Vec3(x, y, z);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(center).inflate(reach))) {
            if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) {
                continue;
            }
            double distance = entity.position().distanceTo(origin);
            double factor = 1.0 - distance / reach;
            if (factor > 0) {
                entity.hurt(source, (float) (maxDamage * factor));
            }
        }
    }

    private void carve(ServerLevel level) {
        if (offsets == null) {
            offsets = CraterShape.carve(craterRadius, seed);
        }
        int budget = Config.EXPLOSION_BLOCKS_PER_TICK.get();
        int checks = budget * 4;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (cursor < offsets.length && budget > 0 && checks-- > 0) {
            int packed = offsets[cursor++];
            pos.set(center.getX() + CraterShape.dx(packed), center.getY() + CraterShape.dy(packed),
                    center.getZ() + CraterShape.dz(packed));
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getDestroySpeed(level, pos) < 0) {
                continue;
            }
            level.setBlock(pos, air, Block.UPDATE_CLIENTS);
            budget--;
        }

        // Explosions along the expanding edge.
        if (level.getGameTime() % 3 == 0 && cursor > 0) {
            RandomSource random = level.getRandom();
            for (int i = 0; i < 3; i++) {
                int packed = offsets[Math.max(0, cursor - 1 - random.nextInt(Math.min(cursor, 2000)))];
                level.sendParticles(ParticleTypes.EXPLOSION, center.getX() + 0.5 + CraterShape.dx(packed),
                        center.getY() + 0.5 + CraterShape.dy(packed), center.getZ() + 0.5 + CraterShape.dz(packed),
                        1, 0.0, 0.0, 0.0, 0.0);
            }
            if (level.getGameTime() % 12 == 0) {
                level.playSound(null, center, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 8.0F,
                        0.6F + random.nextFloat() * 0.3F);
            }
        }

        if (cursor >= offsets.length) {
            offsets = null;
            cursor = 0;
            phase = contaminationRadius > 0 ? PHASE_SCORCH : nextAfterScorch();
        }
    }

    private void scorch(ServerLevel level) {
        if (offsets == null) {
            offsets = CraterShape.columns(contaminationRadius, seed);
        }
        int budget = Math.max(50, Config.EXPLOSION_BLOCKS_PER_TICK.get() / 6);
        RandomSource random = level.getRandom();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (cursor < offsets.length && budget-- > 0) {
            int packed = offsets[cursor++];
            int dx = CraterShape.dx(packed);
            int dz = CraterShape.dz(packed);
            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            double distance = Math.sqrt(dx * dx + dz * dz);
            scorchColumn(level, pos, x, z, distance, random);
        }
        if (cursor >= offsets.length) {
            offsets = null;
            cursor = 0;
            phase = nextAfterScorch();
        }
    }

    private int nextAfterScorch() {
        return Config.BURN_ENABLED.get() ? PHASE_BURN : PHASE_FINISH;
    }

    /** Lights the ground: dense in the crater, thinner and thinner towards the edge of the zone. */
    private void burn(ServerLevel level) {
        int radius = Math.max(craterRadius, contaminationRadius);
        if (offsets == null) {
            offsets = CraterShape.columns(radius, seed ^ 0x5DEECE66DL);
        }
        int budget = Math.max(50, Config.EXPLOSION_BLOCKS_PER_TICK.get() / 6);
        double density = Config.FIRE_DENSITY.get() / 100.0;
        RandomSource random = level.getRandom();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (cursor < offsets.length && budget-- > 0) {
            int packed = offsets[cursor++];
            int dx = CraterShape.dx(packed);
            int dz = CraterShape.dz(packed);
            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            double distance = Math.sqrt(dx * dx + dz * dz);
            double chance = distance <= craterRadius + 0.5 ? density
                    : density * (0.85 - 0.7 * (distance - craterRadius) / Math.max(1.0, radius - craterRadius));
            if (random.nextDouble() > chance) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= level.getMinBuildHeight()) {
                continue;
            }
            pos.set(x, y, z);
            EternalFire.light(level, pos);
        }
        if (cursor >= offsets.length) {
            offsets = null;
            cursor = 0;
            phase = PHASE_FINISH;
        }
    }

    private void scorchColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z, double distance,
                              RandomSource random) {
        // Strip the leaves: only dead trees remain.
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (ground < level.getMinBuildHeight()) {
            return;
        }
        for (int y = top; y > ground; y--) {
            pos.set(x, y, z);
            if (level.getBlockState(pos).is(BlockTags.LEAVES)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        pos.set(x, ground, z);
        BlockState groundState = level.getBlockState(pos);
        if (!groundState.getFluidState().isEmpty() || groundState.hasBlockEntity()
                || groundState.getDestroySpeed(level, pos) < 0) {
            return; // water, chests, bedrock ...
        }

        boolean inCrater = distance <= craterRadius + 0.5;
        // Towards the outer edge, fewer blocks are affected.
        double edgeStart = contaminationRadius * 0.7;
        double chance = distance <= edgeStart ? 1.0
                : 1.0 - 0.7 * (distance - edgeStart) / Math.max(1.0, contaminationRadius - edgeStart);
        if (!inCrater && random.nextDouble() > chance) {
            return;
        }

        // Plants and snow on top disappear (a few dead bushes remain).
        BlockPos above = pos.above();
        BlockState aboveState = level.getBlockState(above);
        boolean plantOnTop = !aboveState.isAir() && aboveState.canBeReplaced() && aboveState.getFluidState().isEmpty();
        if (plantOnTop) {
            level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        BlockState replacement;
        if (inCrater) {
            replacement = craterFloor(random);
        } else if (isSoil(groundState)) {
            replacement = wasteland(random);
        } else {
            return;
        }
        level.setBlock(pos, replacement, Block.UPDATE_CLIENTS);
        if (!inCrater && replacement.is(Blocks.COARSE_DIRT) && random.nextFloat() < 0.04F
                && level.getBlockState(above).isAir()) {
            level.setBlock(above, Blocks.DEAD_BUSH.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static boolean isSoil(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.SNOW_BLOCK);
    }

    private static BlockState wasteland(RandomSource random) {
        float roll = random.nextFloat();
        if (roll < 0.6F) {
            return Blocks.COARSE_DIRT.defaultBlockState();
        }
        if (roll < 0.85F) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        return Blocks.DIRT.defaultBlockState();
    }

    private static BlockState craterFloor(RandomSource random) {
        float roll = random.nextFloat();
        if (roll < 0.35F) {
            return Blocks.BLACKSTONE.defaultBlockState();
        }
        if (roll < 0.6F) {
            return Blocks.BASALT.defaultBlockState();
        }
        if (roll < 0.8F) {
            return Blocks.COARSE_DIRT.defaultBlockState();
        }
        if (roll < 0.92F) {
            return Blocks.GRAVEL.defaultBlockState();
        }
        return Blocks.MAGMA_BLOCK.defaultBlockState();
    }

    private void finish(ServerLevel level) {
        // Items dropped by destroyed containers and killed creatures are vaporised.
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(craterRadius + 2))) {
            item.discard();
        }
        Fallout.setTickets(level, this, false);
        ticketsAdded = false;
    }
}
