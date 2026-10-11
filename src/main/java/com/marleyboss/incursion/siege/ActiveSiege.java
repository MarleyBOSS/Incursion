package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.entity.CoreTargetEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Snapshot of the currently running siege, refreshed every second by the active core.
 * Event handlers use it to decide quickly whether a mob is inside one of the zones.
 * A snapshot expires on its own if the core stops ticking.
 */
public final class ActiveSiege {
    public static final int ZONE_CORE = 0;
    public static final int ZONE_SIEGE = 1;
    public static final int ZONE_OUTSIDE = 2;

    private static final int VERTICAL_RANGE = 64;

    @Nullable
    private static ActiveSiege current;

    public final ServerLevel level;
    public final BlockPos corePos;
    public final CoreTargetEntity target;
    public final int coreZoneRadius;
    public final int siegeRadius;
    public final int playerAggroRange;
    public final int coreLevel;
    private final long validUntil;

    private ActiveSiege(ServerLevel level, BlockPos corePos, CoreTargetEntity target, int coreZoneRadius,
                        int siegeRadius, int playerAggroRange, int coreLevel) {
        this.level = level;
        this.corePos = corePos.immutable();
        this.target = target;
        this.coreZoneRadius = coreZoneRadius;
        this.siegeRadius = siegeRadius;
        this.playerAggroRange = playerAggroRange;
        this.coreLevel = coreLevel;
        this.validUntil = level.getGameTime() + 60;
    }

    public static void update(ServerLevel level, BlockPos corePos, CoreTargetEntity target, int coreZoneRadius,
                              int siegeRadius, int playerAggroRange, int coreLevel) {
        current = new ActiveSiege(level, corePos, target, coreZoneRadius, siegeRadius, playerAggroRange, coreLevel);
    }

    public static void clear() {
        current = null;
    }

    /** The running siege in the given level, or null. */
    @Nullable
    public static ActiveSiege get(Level level) {
        ActiveSiege siege = current;
        if (siege == null || siege.level != level || level.getGameTime() > siege.validUntil
                || !siege.target.isAlive()) {
            return null;
        }
        return siege;
    }

    public int zoneOf(Entity entity) {
        double dx = entity.getX() - (corePos.getX() + 0.5);
        double dy = entity.getY() - corePos.getY();
        double dz = entity.getZ() - (corePos.getZ() + 0.5);
        if (dx * dx + dz * dz <= (double) coreZoneRadius * coreZoneRadius && Math.abs(dy) <= coreZoneRadius) {
            return ZONE_CORE;
        }
        if (Math.abs(dx) <= siegeRadius && Math.abs(dz) <= siegeRadius && Math.abs(dy) <= VERTICAL_RANGE) {
            return ZONE_SIEGE;
        }
        return ZONE_OUTSIDE;
    }
}
