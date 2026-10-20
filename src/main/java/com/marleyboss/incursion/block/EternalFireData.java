package com.marleyboss.incursion.block;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/** The positions of the eternal fires of all dimensions ({@code data/incursion_eternal_fire.dat} in the overworld). */
public class EternalFireData extends SavedData {
    private static final String NAME = "incursion_eternal_fire";
    private final Map<ResourceKey<Level>, LongOpenHashSet> fires = new HashMap<>();

    public static EternalFireData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(EternalFireData::new, EternalFireData::load, null), NAME);
    }

    public EternalFireData() {
    }

    public Map<ResourceKey<Level>, LongOpenHashSet> all() {
        return fires;
    }

    public LongOpenHashSet of(ResourceKey<Level> dimension) {
        return fires.computeIfAbsent(dimension, k -> new LongOpenHashSet());
    }

    private static EternalFireData load(CompoundTag tag, HolderLookup.Provider registries) {
        EternalFireData data = new EternalFireData();
        ListTag list = tag.getList("Dimensions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("Dimension"));
            if (id != null) {
                data.of(ResourceKey.create(Registries.DIMENSION, id)).addAll(java.util.Arrays.stream(entry.getLongArray("Pos")).boxed().toList());
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<ResourceKey<Level>, LongOpenHashSet> e : fires.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Dimension", e.getKey().location().toString());
            entry.put("Pos", new LongArrayTag(e.getValue().toLongArray()));
            list.add(entry);
        }
        tag.put("Dimensions", list);
        return tag;
    }
}
