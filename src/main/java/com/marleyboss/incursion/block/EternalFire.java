package com.marleyboss.incursion.block;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The fire that the explosion of a Core leaves: the normal fire of the game, standing on netherrack, which burns for ever
 * (netherrack is infinite fuel: the fire neither ages away nor is put out by rain). Its positions are remembered; when one
 * is put out (a punch, water, a block placed on it) it is lit again a moment later on a free spot nearby, so that the
 * burnt land stays uninhabitable.
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

    /**
     * Lights an eternal fire in the air block {@code pos} (the ground under it becomes netherrack) and remembers it.
     * Returns false when the spot does not fit (not air, water or no solid ground under it).
     */
    public static boolean light(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState ground = level.getBlockState(below);
        if (!ground.getFluidState().isEmpty() || !ground.isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        if (!ground.is(Blocks.NETHERRACK)) {
            if (ground.hasBlockEntity() || ground.getDestroySpeed(level, below) < 0.0F) {
                return false;
            }
            level.setBlock(below, Blocks.NETHERRACK.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(pos, Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
        EternalFireData data = EternalFireData.get(level.getServer());
        data.of(level.dimension()).add(pos.asLong());
        data.setDirty();
        return true;
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 10 == 0) {
            checkFires(server);
        }
        if (!QUEUE.isEmpty()) {
            runQueue(server);
        }
    }

    /** Fires that are gone are taken off the list and queued to be lit again. */
    private static void checkFires(MinecraftServer server) {
        EternalFireData data = EternalFireData.get(server);
        for (Map.Entry<ResourceKey<Level>, LongOpenHashSet> entry : data.all().entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null || entry.getValue().isEmpty()) {
                continue;
            }
            LongArrayList gone = new LongArrayList();
            for (long packed : entry.getValue()) {
                BlockPos pos = BlockPos.of(packed);
                if (level.isLoaded(pos) && !level.getBlockState(pos).is(Blocks.FIRE)) {
                    gone.add(packed);
                }
            }
            for (long packed : gone) {
                entry.getValue().remove(packed);
                QUEUE.add(new Pending(entry.getKey(), BlockPos.of(packed), level.getGameTime() + 20 + level.getRandom().nextInt(30), 0));
            }
            if (!gone.isEmpty()) {
                data.setDirty();
            }
        }
    }

    private static void runQueue(MinecraftServer server) {
        List<Pending> retry = new ArrayList<>();
        Iterator<Pending> it = QUEUE.iterator();
        while (it.hasNext()) {
            Pending job = it.next();
            ServerLevel level = server.getLevel(job.dimension());
            if (level == null) {
                it.remove();
                continue;
            }
            if (level.getGameTime() < job.due()) {
                continue;
            }
            it.remove();
            if (!level.isLoaded(job.origin())) {
                continue;
            }
            if (!relight(level, job) && job.tries() + 1 < MAX_TRIES) {
                retry.add(new Pending(job.dimension(), job.origin(), level.getGameTime() + 20, job.tries() + 1));
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
                if (level.getBlockState(pos).isAir() && light(level, pos)) {
                    level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 12, 0.3, 0.2, 0.3, 0.03);
                    level.playSound(null, pos, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 0.8F, 1.0F);
                    return true;
                }
            }
        }
        return false;
    }
}
