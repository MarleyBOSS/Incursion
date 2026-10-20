package com.marleyboss.incursion.block;

import com.marleyboss.incursion.registry.ModBlocks;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The comeback of the eternal fire: a fire that is put out is queued here and lights up again a moment later on a free spot
 * nearby (the search area grows with every failed try). The queue is not saved; a fire that is put out in the second before
 * a restart is simply gone.
 */
public final class EternalFire {
    private static final int MAX_TRIES = 8;
    private static final List<Pending> QUEUE = new ArrayList<>();

    private record Pending(ResourceKey<Level> dimension, BlockPos origin, long due, int tries) {
    }

    private EternalFire() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EternalFire::onServerTick);
    }

    /** Called when an eternal fire at {@code origin} has been removed. */
    public static void schedule(ServerLevel level, BlockPos origin) {
        QUEUE.add(new Pending(level.dimension(), origin.immutable(), level.getGameTime() + 15 + level.getRandom().nextInt(30), 0));
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (QUEUE.isEmpty()) {
            return;
        }
        List<Pending> retry = new ArrayList<>();
        Iterator<Pending> it = QUEUE.iterator();
        while (it.hasNext()) {
            Pending job = it.next();
            ServerLevel level = event.getServer().getLevel(job.dimension());
            if (level == null) {
                it.remove();
                continue;
            }
            if (level.getGameTime() < job.due()) {
                continue;
            }
            it.remove();
            if (!level.isLoaded(job.origin())) {
                continue; // nobody is near: it is not missed
            }
            if (!relight(level, job)) {
                if (job.tries() + 1 < MAX_TRIES) {
                    retry.add(new Pending(job.dimension(), job.origin(), level.getGameTime() + 20, job.tries() + 1));
                }
            }
        }
        QUEUE.addAll(retry);
    }

    private static boolean relight(ServerLevel level, Pending job) {
        RandomSource random = level.getRandom();
        int radius = 3 + job.tries() * 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int attempt = 0; attempt < 40; attempt++) {
            int x = job.origin().getX() + random.nextInt(radius * 2 + 1) - radius;
            int z = job.origin().getZ() + random.nextInt(radius * 2 + 1) - radius;
            for (int y = job.origin().getY() + 3; y >= job.origin().getY() - 4; y--) {
                pos.set(x, y, z);
                if (pos.equals(job.origin()) || !level.isLoaded(pos)) {
                    break;
                }
                BlockState here = level.getBlockState(pos);
                if (!here.isAir()) {
                    continue;
                }
                BlockState below = level.getBlockState(pos.below());
                if (!below.getFluidState().isEmpty() || !below.isFaceSturdy(level, pos.below(), Direction.UP)) {
                    continue;
                }
                level.setBlock(pos, ModBlocks.ETERNAL_FIRE.get().defaultBlockState(), Block.UPDATE_ALL);
                level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 12, 0.3, 0.2, 0.3, 0.03);
                level.playSound(null, pos, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 0.8F, 1.0F);
                return true;
            }
        }
        return false;
    }
}
