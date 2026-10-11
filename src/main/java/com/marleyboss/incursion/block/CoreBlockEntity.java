package com.marleyboss.incursion.block;

import com.marleyboss.incursion.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stores the state of an Incursion Core. Whether the core is active is kept in the
 * block state ({@link CoreBlock#ACTIVE}); the level lives here.
 */
public class CoreBlockEntity extends BlockEntity {
    private static final String TAG_LEVEL = "CoreLevel";

    private int coreLevel = 0;

    public CoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CORE.get(), pos, state);
    }

    public int getCoreLevel() {
        return coreLevel;
    }

    public void setCoreLevel(int coreLevel) {
        this.coreLevel = Math.max(0, coreLevel);
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_LEVEL, coreLevel);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        coreLevel = tag.getInt(TAG_LEVEL);
    }
}
