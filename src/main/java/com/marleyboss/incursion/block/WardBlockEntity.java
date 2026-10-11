package com.marleyboss.incursion.block;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.registry.ModBlockEntities;
import com.marleyboss.incursion.siege.SpawnRings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Keeps track of loaded ward totems and draws their ring every two seconds. */
public class WardBlockEntity extends BlockEntity {
    private static final Set<WardBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    public WardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WARD.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, WardBlockEntity ward) {
        LOADED.add(ward);
        if (level instanceof ServerLevel serverLevel && (level.getGameTime() + pos.hashCode()) % 40 == 0
                && Config.SHOW_SPAWN_RINGS.get()) {
            SpawnRings.drawWard(serverLevel, pos, Config.WARD_RADIUS.get());
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED.remove(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        LOADED.remove(this);
    }

    /** Positions of loaded ward totems in this level within a square around {@code center}. */
    public static List<BlockPos> wardsNear(Level level, BlockPos center, int radius) {
        List<BlockPos> result = new ArrayList<>();
        for (WardBlockEntity ward : LOADED) {
            if (ward.isRemoved() || ward.getLevel() != level) {
                continue;
            }
            BlockPos pos = ward.getBlockPos();
            if (Math.abs(pos.getX() - center.getX()) <= radius && Math.abs(pos.getZ() - center.getZ()) <= radius) {
                result.add(pos);
            }
        }
        return result;
    }
}
