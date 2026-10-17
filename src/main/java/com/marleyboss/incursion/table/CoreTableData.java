package com.marleyboss.incursion.table;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-wide record of the enchantments unlocked at the Core Table (by enchantment id).
 * Stored in the overworld's data folder as {@code data/incursion_table.dat}; it survives the Core.
 */
public class CoreTableData extends SavedData {
    private static final String NAME = "incursion_table";
    private static final String TAG_UNLOCKED = "Unlocked";

    private final Set<String> unlocked = new HashSet<>();

    public static CoreTableData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CoreTableData::new, CoreTableData::load, null), NAME);
    }

    public CoreTableData() {
    }

    private static CoreTableData load(CompoundTag tag, HolderLookup.Provider registries) {
        CoreTableData data = new CoreTableData();
        ListTag list = tag.getList(TAG_UNLOCKED, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            data.unlocked.add(list.getString(i));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (String key : unlocked) {
            list.add(StringTag.valueOf(key));
        }
        tag.put(TAG_UNLOCKED, list);
        return tag;
    }

    public boolean isUnlocked(String key) {
        return unlocked.contains(key);
    }

    public void unlock(String key) {
        if (unlocked.add(key)) {
            setDirty();
        }
    }
}
