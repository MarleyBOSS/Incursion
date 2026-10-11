package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.data.CoreWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import java.util.ArrayList;
import java.util.List;

/** Keeps the siege area of the active Core loaded, even when no player is nearby. */
public final class ChunkLoading {
    public static final TicketController TICKETS = new TicketController(
            ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "core"),
            (level, helper) -> {
                // On world load: drop tickets of cores that are no longer the world's active core.
                try {
                    CoreWorldData data = CoreWorldData.get(level.getServer());
                    List<BlockPos> owners = new ArrayList<>(helper.getBlockTickets().keySet());
                    for (BlockPos owner : owners) {
                        if (!level.dimension().equals(data.getDimension()) || !owner.equals(data.getPos())) {
                            helper.removeAllTickets(owner);
                        }
                    }
                } catch (RuntimeException e) {
                    Incursion.LOGGER.warn("Could not validate Incursion chunk tickets", e);
                }
            });

    private ChunkLoading() {
    }

    public static void register(RegisterTicketControllersEvent event) {
        event.register(TICKETS);
    }

    /** Changes the force-loaded square around {@code corePos} from {@code oldRadius} to {@code newRadius} chunks. */
    public static void update(ServerLevel level, BlockPos corePos, int oldRadius, int newRadius) {
        if (oldRadius >= 0) {
            setSquare(level, corePos, oldRadius, false);
        }
        if (newRadius >= 0) {
            setSquare(level, corePos, newRadius, true);
        }
    }

    private static void setSquare(ServerLevel level, BlockPos corePos, int radius, boolean add) {
        int centerX = corePos.getX() >> 4;
        int centerZ = corePos.getZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                TICKETS.forceChunk(level, corePos, centerX + dx, centerZ + dz, add, true);
            }
        }
    }
}
