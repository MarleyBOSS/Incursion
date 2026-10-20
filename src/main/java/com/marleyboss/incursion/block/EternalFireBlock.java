package com.marleyboss.incursion.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The fire the explosion of a Core leaves behind. It looks like normal fire but it never dies on its own (it does not age,
 * rain does not touch it and it does not spread). It can be put out, but then it flares up again somewhere nearby (see
 * {@link EternalFire}), so the burnt land stays uninhabitable. It is in the fire tag, so it is extinguished by a punch like
 * any fire.
 */
public class EternalFireBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0);

    public EternalFireBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos,
                                     BlockPos neighborPos) {
        return canSurvive(state, level, pos) ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (entity.fireImmune()) {
            return;
        }
        entity.setRemainingFireTicks(Math.max(entity.getRemainingFireTicks(), 160));
        if (!level.isClientSide) {
            entity.hurt(level.damageSources().inFire(), 1.0F);
        }
    }

    /** Whatever removes the fire (a punch, water, the ground breaking away), it comes back elsewhere. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && level instanceof ServerLevel serverLevel) {
            EternalFire.schedule(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        double x = pos.getX() + 0.5;
        double y = pos.getY();
        double z = pos.getZ() + 0.5;
        if (random.nextInt(24) == 0) {
            level.playLocalSound(x, y, z, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 1.0F + random.nextFloat(),
                    random.nextFloat() * 0.7F + 0.3F, false);
        }
        if (random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.LARGE_SMOKE, x + (random.nextDouble() - 0.5) * 0.6, y + 0.6 + random.nextDouble() * 0.5,
                    z + (random.nextDouble() - 0.5) * 0.6, 0.0, 0.04, 0.0);
        }
        if (random.nextInt(6) == 0) {
            level.addParticle(ParticleTypes.LAVA, x + (random.nextDouble() - 0.5) * 0.6, y + 0.5, z + (random.nextDouble() - 0.5) * 0.6,
                    0.0, 0.0, 0.0);
        }
    }
}
