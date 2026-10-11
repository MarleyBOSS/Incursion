package com.marleyboss.incursion.block;

import com.marleyboss.incursion.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Keeps track of loaded ward totems (their ring is drawn by the siege). */
public class WardBlockEntity extends BlockEntity {
    private static final Set<WardBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    public WardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WARD.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, WardBlockEntity ward) {
        // The rings are drawn by the siege (switch in the Core GUI).
        LOADED.add(ward);
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
