package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parsed config lists: which mobs attack the Core and which mobs the siege spawns. */
public final class SiegeRules {
    private record PoolEntry(EntityType<?> type, int minLevel) {
    }

    private static Set<EntityType<?>> exempt = Set.of();
    private static Set<EntityType<?>> nonDiggers = Set.of();
    private static List<PoolEntry> pool = List.of();

    private SiegeRules() {
    }

    /** Re-reads the config lists. Called once per second by the active core. */
    public static void refresh() {
        Set<EntityType<?>> newExempt = parseTypes(Config.EXEMPT_MOBS.get());
        Set<EntityType<?>> newNonDiggers = parseTypes(Config.NON_DIGGING_MOBS.get());
        List<PoolEntry> newPool = new ArrayList<>();
        for (String entry : Config.MOB_POOL.get()) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            ResourceLocation location = ResourceLocation.tryParse(entry.substring(0, eq).trim());
            int minLevel;
            try {
                minLevel = Integer.parseInt(entry.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (location != null) {
                BuiltInRegistries.ENTITY_TYPE.getOptional(location)
                        .ifPresent(type -> newPool.add(new PoolEntry(type, minLevel)));
            }
        }
        exempt = newExempt;
        nonDiggers = newNonDiggers;
        pool = newPool;
    }

    private static Set<EntityType<?>> parseTypes(List<? extends String> ids) {
        Set<EntityType<?>> result = new HashSet<>();
        for (String id : ids) {
            ResourceLocation location = ResourceLocation.tryParse(id.trim());
            if (location != null) {
                BuiltInRegistries.ENTITY_TYPE.getOptional(location).ifPresent(result::add);
            }
        }
        return result;
    }

    /** True for mob types listed in the config as "never dig". */
    public static boolean isNonDigger(Mob mob) {
        return nonDiggers.contains(mob.getType());
    }

    /** True for hostile mobs that take part in the siege (attack the Core). */
    public static boolean isAttacker(Mob mob) {
        return mob instanceof Enemy
                && mob.isAlive()
                && !mob.getType().is(Tags.EntityTypes.BOSSES)
                && !exempt.contains(mob.getType());
    }

    /** Entity types the siege may spawn at the given core level. */
    public static List<EntityType<?>> spawnPool(int coreLevel) {
        List<EntityType<?>> result = new ArrayList<>();
        for (PoolEntry entry : pool) {
            if (coreLevel >= entry.minLevel()) {
                result.add(entry.type());
            }
        }
        return result;
    }
}
