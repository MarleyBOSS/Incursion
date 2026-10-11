package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.siege.MobRoles.Role;
import com.marleyboss.incursion.siege.SiegeAI.MobState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * Optional "AI view" (toggled in the Core GUI): every attacker shows its role and what it is doing above its
 * head, and the planned ways are drawn with particles (yellow = assembly point, white = way, purple = platform,
 * red = next block to dig, blue = next block to build).
 */
public final class SiegeDebug {
    private static final String TAG = "incursion_debug_name";
    private static final DustParticleOptions PATH = new DustParticleOptions(new Vector3f(0.9F, 0.9F, 0.9F), 0.9F);
    private static final DustParticleOptions DIG = new DustParticleOptions(new Vector3f(1.0F, 0.15F, 0.1F), 2.0F);
    private static final DustParticleOptions BUILD = new DustParticleOptions(new Vector3f(0.2F, 0.45F, 1.0F), 2.0F);
    private static final DustParticleOptions ASSEMBLY = new DustParticleOptions(new Vector3f(1.0F, 0.85F, 0.1F), 2.5F);
    private static final DustParticleOptions PLATFORM = new DustParticleOptions(new Vector3f(0.75F, 0.3F, 1.0F), 1.5F);

    private SiegeDebug() {
    }

    static void showName(Mob mob, MobState state) {
        Role role = MobRoles.of(mob);
        MutableComponent name = role == Role.NONE
                ? mob.getType().getDescription().copy()
                : Component.translatable("debug.incursion.role." + role.name().toLowerCase(Locale.ROOT));
        name.withStyle(ChatFormatting.GOLD).append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("debug.incursion.status." + state.status, state.statusArgs)
                        .withStyle(ChatFormatting.WHITE));
        mob.setCustomName(name);
        mob.setCustomNameVisible(true);
        mob.addTag(TAG);
    }

    static void clearName(Mob mob) {
        if (mob.getTags().contains(TAG)) {
            mob.removeTag(TAG);
            mob.setCustomName(null);
            mob.setCustomNameVisible(false);
        }
    }

    static boolean hasDebugName(Mob mob) {
        return mob.getTags().contains(TAG);
    }

    /** Once per second: draw the planned way of every front, the next job on it and the platform. */
    static void drawRoutes(ServerLevel level, ActiveSiege siege, long now) {
        for (SiegeFront front : SiegeCommander.fronts()) {
            Route route = front.route;
            for (int i = 0; i < route.size(); i++) {
                BlockPos node = route.node(i);
                SpawnRings.send(level, i == 0 ? ASSEMBLY : PATH, node.getX() + 0.5, node.getY() + 0.15, node.getZ() + 0.5);
            }
            for (BlockPos spot : front.platform) {
                SpawnRings.send(level, PLATFORM, spot.getX() + 0.5, spot.getY() + 0.15, spot.getZ() + 0.5);
            }
            int pending = route.firstPending(level, 1, route.last());
            Route.Action action = pending > 0 ? route.action(level, pending) : front.platformAction(level, true, true);
            if (action != null) {
                BlockPos pos = action.pos();
                SpawnRings.send(level, action.dig() ? DIG : BUILD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            }
        }
    }
}
