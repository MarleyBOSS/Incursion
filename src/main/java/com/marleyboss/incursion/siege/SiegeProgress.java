package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Siege-wide bookkeeping: when did the attackers last achieve something (damage on the Core, a block dug
 * or built, a player hit), where did that happen, and which roles are missing right now.
 */
public final class SiegeProgress {
    public enum Need { DIGGER, BUILDER, FIRE_ARCHER }

    /** A need counts for this many ticks after it was last reported. */
    private static final long NEED_TICKS = 400;
    /** Siege actions count as "recent" for this many ticks (mobs nearby are busy, not idle). */
    private static final long RECENT_TICKS = 600;
    private static final int MAX_RECENT = 64;

    @Nullable
    private static BlockPos corePos;
    private static long lastProgress;
    private static final long[] LAST_NEED = new long[Need.values().length];
    private static final ArrayDeque<long[]> RECENT = new ArrayDeque<>();

    static {
        Arrays.fill(LAST_NEED, Long.MIN_VALUE / 2);
    }

    private SiegeProgress() {
    }

    /** Called every second by the active core. Resets everything when a different core runs the siege. */
    public static void ensure(BlockPos pos, long now) {
        if (!pos.equals(corePos) || now < lastProgress) {
            clear();
            corePos = pos.immutable();
            lastProgress = now;
        }
    }

    /** The attackers achieved something (optionally at a position). */
    public static void progress(@Nullable BlockPos pos, long now) {
        lastProgress = now;
        if (pos != null) {
            RECENT.addLast(new long[]{pos.asLong(), now});
            while (RECENT.size() > MAX_RECENT) {
                RECENT.removeFirst();
            }
        }
    }

    public static long ticksSinceProgress(long now) {
        return now - lastProgress;
    }

    public static boolean isStalled(long now) {
        int seconds = Config.STALL_SECONDS.get();
        return seconds > 0 && ticksSinceProgress(now) > seconds * 20L;
    }

    public static void need(Need need, long now) {
        LAST_NEED[need.ordinal()] = now;
    }

    public static boolean isNeeded(Need need, long now) {
        return now - LAST_NEED[need.ordinal()] < NEED_TICKS;
    }

    /** True if the siege dug, built or blew something up near this position recently. */
    public static boolean recentActionNear(BlockPos pos, double radius, long now) {
        double radiusSqr = radius * radius;
        Iterator<long[]> iterator = RECENT.descendingIterator();
        while (iterator.hasNext()) {
            long[] entry = iterator.next();
            if (now - entry[1] > RECENT_TICKS) {
                break;
            }
            if (BlockPos.of(entry[0]).distSqr(pos) <= radiusSqr) {
                return true;
            }
        }
        return false;
    }

    public static void clear() {
        corePos = null;
        lastProgress = 0;
        Arrays.fill(LAST_NEED, Long.MIN_VALUE / 2);
        RECENT.clear();
    }
}
