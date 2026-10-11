package com.marleyboss.incursion.fallout;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * World-wide record of contaminated zones and craters still being carved.
 * Stored in the overworld's data folder as {@code data/incursion_fallout.dat}.
 */
public class FalloutData extends SavedData {
    private static final String NAME = "incursion_fallout";

    /** A contaminated area left behind by an explosion. */
    public record Zone(ResourceKey<Level> dimension, BlockPos center, int radius, long createdAt) {
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dimension", dimension.location().toString());
            tag.putLong("Pos", center.asLong());
            tag.putInt("Radius", radius);
            tag.putLong("Created", createdAt);
            return tag;
        }

        @Nullable
        static Zone load(CompoundTag tag) {
            ResourceKey<Level> dimension = parseDimension(tag.getString("Dimension"));
            if (dimension == null) {
                return null;
            }
            return new Zone(dimension, BlockPos.of(tag.getLong("Pos")), tag.getInt("Radius"), tag.getLong("Created"));
        }

        public boolean contains(double x, double z) {
            double dx = x - (center.getX() + 0.5);
            double dz = z - (center.getZ() + 0.5);
            return dx * dx + dz * dz <= (double) radius * radius;
        }
    }

    private final List<Zone> zones = new ArrayList<>();
    private final List<CraterJob> jobs = new ArrayList<>();

    public static FalloutData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FalloutData::new, FalloutData::load, null), NAME);
    }

    public FalloutData() {
    }

    private static FalloutData load(CompoundTag tag, HolderLookup.Provider registries) {
        FalloutData data = new FalloutData();
        ListTag zoneList = tag.getList("Zones", Tag.TAG_COMPOUND);
        for (int i = 0; i < zoneList.size(); i++) {
            Zone zone = Zone.load(zoneList.getCompound(i));
            if (zone != null) {
                data.zones.add(zone);
            }
        }
        ListTag jobList = tag.getList("Jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < jobList.size(); i++) {
            CraterJob job = CraterJob.load(jobList.getCompound(i));
            if (job != null) {
                data.jobs.add(job);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag zoneList = new ListTag();
        for (Zone zone : zones) {
            zoneList.add(zone.save());
        }
        tag.put("Zones", zoneList);
        ListTag jobList = new ListTag();
        for (CraterJob job : jobs) {
            jobList.add(job.save());
        }
        tag.put("Jobs", jobList);
        return tag;
    }

    @Nullable
    static ResourceKey<Level> parseDimension(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : ResourceKey.create(Registries.DIMENSION, location);
    }

    public List<Zone> zones() {
        return zones;
    }

    public List<CraterJob> jobs() {
        return jobs;
    }

    public void addZone(Zone zone) {
        zones.add(zone);
        setDirty();
    }

    public void addJob(CraterJob job) {
        jobs.add(job);
        setDirty();
    }
}
