package com.marleyboss.incursion.data;

import com.marleyboss.incursion.block.CoreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * World-wide record of the single active Incursion Core.
 * Stored in the overworld's data folder as {@code data/incursion_core.dat}.
 */
public class CoreWorldData extends SavedData {
    private static final String NAME = "incursion_core";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_POS = "Pos";

    @Nullable
    private ResourceKey<Level> dimension;
    @Nullable
    private BlockPos pos;

    public static CoreWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CoreWorldData::new, CoreWorldData::load, null), NAME);
    }

    public CoreWorldData() {
    }

    private static CoreWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        CoreWorldData data = new CoreWorldData();
        if (tag.contains(TAG_DIMENSION) && tag.contains(TAG_POS)) {
            ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
            if (dimensionId != null) {
                data.dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
                data.pos = BlockPos.of(tag.getLong(TAG_POS));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (dimension != null && pos != null) {
            tag.putString(TAG_DIMENSION, dimension.location().toString());
            tag.putLong(TAG_POS, pos.asLong());
        }
        return tag;
    }

    /**
     * Returns the position of another active core, or {@code null} if the given position
     * may become the world's active core. Stale entries (the block is gone) are cleaned up.
     */
    @Nullable
    public BlockPos findOtherActiveCore(MinecraftServer server, ResourceKey<Level> levelKey, BlockPos candidate) {
        if (dimension == null || pos == null) {
            return null;
        }
        if (dimension.equals(levelKey) && pos.equals(candidate)) {
            return null;
        }
        ServerLevel recordedLevel = server.getLevel(dimension);
        if (recordedLevel == null) {
            clear();
            return null;
        }
        // Only verify if the chunk is already loaded; never force-load just for this check.
        if (recordedLevel.isLoaded(pos)) {
            BlockState state = recordedLevel.getBlockState(pos);
            if (!(state.getBlock() instanceof CoreBlock) || !state.getValue(CoreBlock.ACTIVE)) {
                clear();
                return null;
            }
        }
        return pos;
    }

    @Nullable
    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public void setActiveCore(ResourceKey<Level> levelKey, BlockPos corePos) {
        this.dimension = levelKey;
        this.pos = corePos.immutable();
        setDirty();
    }

    public void clearIfMatches(ResourceKey<Level> levelKey, BlockPos corePos) {
        if (levelKey.equals(dimension) && corePos.equals(pos)) {
            clear();
        }
    }

    private void clear() {
        this.dimension = null;
        this.pos = null;
        setDirty();
    }
}
