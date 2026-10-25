package com.marleyboss.incursion.block;

import com.marleyboss.incursion.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What a general leaves behind when he falls: his insignia on a small dark plinth. It cannot despawn, burn or be blown
 * up (immune to explosions, siege mobs do not dig it, pistons cannot move it). Mining it (also in creative mode) sets
 * the insignia free with a little show; the insignia then completes the waiting upgrade in the Core.
 */
public class GeneralRelicBlock extends Block {
    /** Which general (1 to 4) this insignia belongs to. */
    public static final IntegerProperty GENERAL = IntegerProperty.create("general", 1, 4);
    private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 14.0, 13.0);

    public GeneralRelicBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(GENERAL, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(GENERAL);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel serverLevel) {
            release(serverLevel, pos, state.getValue(GENERAL));
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** The insignia jumps out of the plinth (no loot table: it drops in creative mode too, exactly once). */
    private static void release(ServerLevel level, BlockPos pos, int general) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.6;
        double z = pos.getZ() + 0.5;
        ItemEntity item = new ItemEntity(level, x, y, z, new ItemStack(ModItems.insignia(general).get()));
        item.setDeltaMovement(0.0, 0.32, 0.0);
        item.setUnlimitedLifetime();
        item.setInvulnerable(true);
        item.setGlowingTag(true);
        item.setPickUpDelay(15);
        level.addFreshEntity(item);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y + 0.3, z, 60, 0.3, 0.5, 0.3, 0.35);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 20, 0.25, 0.25, 0.25, 0.04);
        level.playSound(null, pos, SoundEvents.TOTEM_USE, SoundSource.BLOCKS, 0.7F, 1.2F);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.0F, 0.7F);
    }
}
