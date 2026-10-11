package com.marleyboss.incursion;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Server-side configuration. Values are per world and synced to clients.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ------------------------------------------------------------------
    // Core
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("Incursion Core settings").push("core");
    }

    public static final ModConfigSpec.IntValue MAX_LEVEL = BUILDER
            .comment("Highest level the Incursion Core can reach.")
            .defineInRange("maxLevel", 20, 1, 100);

    public static final ModConfigSpec.ConfigValue<String> ACTIVATION_ITEM = BUILDER
            .comment("Item that activates the Core (consumes one). Activation starts the siege at level 1.")
            .define("activationItem", "minecraft:blaze_rod", Config::isValidItem);

    public static final ModConfigSpec.ConfigValue<String> UPGRADE_ITEM = BUILDER
            .comment("Item used to pay for Core upgrades.")
            .define("upgradeItem", "minecraft:diamond", Config::isValidItem);

    public static final ModConfigSpec.IntValue UPGRADE_BASE_COST = BUILDER
            .comment("Upgrade cost = upgradeBaseCost + upgradeCostPerLevel * (targetLevel - 2).",
                    "With the defaults, upgrading to level 2 costs 1 item and to level 20 costs 19 items.")
            .defineInRange("upgradeBaseCost", 1, 1, 64 * 36);

    public static final ModConfigSpec.IntValue UPGRADE_COST_PER_LEVEL = BUILDER
            .comment("Additional items per level (see upgradeBaseCost).")
            .defineInRange("upgradeCostPerLevel", 1, 0, 64 * 36);

    public static final ModConfigSpec.IntValue CORE_HEALTH_BASE = BUILDER
            .comment("Core health = coreHealthBase + coreHealthPerLevel * level.")
            .defineInRange("coreHealthBase", 100, 1, 30000);

    public static final ModConfigSpec.IntValue CORE_HEALTH_PER_LEVEL = BUILDER
            .comment("Additional core health per level (see coreHealthBase).")
            .defineInRange("coreHealthPerLevel", 50, 0, 1500);

    public static final ModConfigSpec.DoubleValue CORE_REGEN_PER_MINUTE = BUILDER
            .comment("Health the Core regenerates per minute.")
            .defineInRange("coreRegenPerMinute", 6.0, 0.0, 10000.0);

    public static final ModConfigSpec.IntValue WARNING_COOLDOWN_SECONDS = BUILDER
            .comment("Minimum seconds between two chat warnings about the Core taking damage.")
            .defineInRange("warningCooldownSeconds", 30, 1, 3600);

    static {
        BUILDER.pop();
    }

    // ------------------------------------------------------------------
    // Siege
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("Siege settings").push("siege");
    }

    public static final ModConfigSpec.DoubleValue CORE_ZONE_RADIUS_BASE = BUILDER
            .comment("Core zone radius in blocks = coreZoneRadiusBase + coreZoneRadiusPerLevel * level.",
                    "Inside the core zone, hostile mobs ignore players and attack only the Core.")
            .defineInRange("coreZoneRadiusBase", 8.0, 2.0, 64.0);

    public static final ModConfigSpec.DoubleValue CORE_ZONE_RADIUS_PER_LEVEL = BUILDER
            .comment("Additional core zone radius per level (see coreZoneRadiusBase).")
            .defineInRange("coreZoneRadiusPerLevel", 0.5, 0.0, 8.0);

    public static final ModConfigSpec.DoubleValue SIEGE_CHUNK_RADIUS_BASE = BUILDER
            .comment("Siege area radius in chunks = siegeChunkRadiusBase + siegeChunkRadiusPerLevel * level.",
                    "The Core keeps this area loaded. Hostile mobs in it attack nearby players, otherwise the Core.")
            .defineInRange("siegeChunkRadiusBase", 2.0, 1.0, 16.0);

    public static final ModConfigSpec.DoubleValue SIEGE_CHUNK_RADIUS_PER_LEVEL = BUILDER
            .comment("Additional siege area radius (in chunks) per level (see siegeChunkRadiusBase).")
            .defineInRange("siegeChunkRadiusPerLevel", 0.2, 0.0, 2.0);

    public static final ModConfigSpec.IntValue SIEGE_CHUNK_RADIUS_MAX = BUILDER
            .comment("Upper limit for the siege area radius in chunks (keeps the number of loaded chunks in check).")
            .defineInRange("siegeChunkRadiusMax", 8, 1, 16);

    public static final ModConfigSpec.IntValue PLAYER_AGGRO_RANGE = BUILDER
            .comment("In the siege area, mobs attack players within this many blocks; otherwise they go for the Core.")
            .defineInRange("playerAggroRange", 16, 0, 64);

    public static final ModConfigSpec.IntValue MAX_SIEGE_MOBS_BASE = BUILDER
            .comment("Maximum number of siege mobs alive = maxSiegeMobsBase + maxSiegeMobsPerLevel * level.")
            .defineInRange("maxSiegeMobsBase", 2, 0, 500);

    public static final ModConfigSpec.IntValue MAX_SIEGE_MOBS_PER_LEVEL = BUILDER
            .comment("Additional siege mobs per level (see maxSiegeMobsBase).")
            .defineInRange("maxSiegeMobsPerLevel", 2, 0, 50);

    public static final ModConfigSpec.IntValue SPAWN_INTERVAL_SECONDS = BUILDER
            .comment("Seconds between two siege spawn attempts.")
            .defineInRange("spawnIntervalSeconds", 6, 1, 600);

    public static final ModConfigSpec.IntValue SPAWN_MAX_DISTANCE = BUILDER
            .comment("Siege mobs spawn at most this many blocks away from the Core (and always outside the core zone).")
            .defineInRange("spawnMaxDistance", 40, 8, 256);

    public static final ModConfigSpec.DoubleValue MOB_HEALTH_BONUS_PER_LEVEL = BUILDER
            .comment("Siege mobs get +X max health per core level (0.05 = +5% per level).")
            .defineInRange("mobHealthBonusPerLevel", 0.05, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue MOB_DAMAGE_BONUS_PER_LEVEL = BUILDER
            .comment("Siege mobs get +X attack damage per core level (0.03 = +3% per level).")
            .defineInRange("mobDamageBonusPerLevel", 0.03, 0.0, 10.0);

    public static final ModConfigSpec.BooleanValue SIEGE_MOBS_FIRE_RESISTANT = BUILDER
            .comment("Siege mobs get permanent fire resistance, so zombies and skeletons do not burn in daylight.")
            .define("siegeMobsFireResistant", true);

    public static final ModConfigSpec.BooleanValue CREEPER_BLOCK_DAMAGE = BUILDER
            .comment("If false, creeper explosions inside the core zone do not destroy blocks.")
            .define("creeperBlockDamage", false);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> MOB_POOL = BUILDER
            .comment("Mobs the siege spawns, as \"entity_id=minimum core level\".")
            .defineListAllowEmpty("mobPool", List.of(
                    "minecraft:zombie=1",
                    "minecraft:skeleton=2",
                    "minecraft:spider=3",
                    "minecraft:creeper=5",
                    "minecraft:husk=7",
                    "minecraft:stray=9",
                    "minecraft:pillager=11",
                    "minecraft:vindicator=14"
            ), () -> "minecraft:zombie=1", Config::isValidPoolEntry);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXEMPT_MOBS = BUILDER
            .comment("Hostile mobs that ignore the Core (entity ids). Everything in the tag c:bosses is always exempt.")
            .defineListAllowEmpty("exemptMobs", List.of(
                    "minecraft:wither",
                    "minecraft:ender_dragon",
                    "minecraft:warden",
                    "minecraft:elder_guardian"
            ), () -> "minecraft:wither", Config::isResourceLocation);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    private static boolean isValidItem(Object value) {
        if (!(value instanceof String s)) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(s);
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }

    private static boolean isResourceLocation(Object value) {
        return value instanceof String s && ResourceLocation.tryParse(s) != null;
    }

    private static boolean isValidPoolEntry(Object value) {
        if (!(value instanceof String s)) {
            return false;
        }
        int eq = s.indexOf('=');
        if (eq <= 0 || ResourceLocation.tryParse(s.substring(0, eq).trim()) == null) {
            return false;
        }
        try {
            Integer.parseInt(s.substring(eq + 1).trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Item resolveItem(String id, Item fallback) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
            return fallback;
        }
        return BuiltInRegistries.ITEM.get(location);
    }

    // ------------------------------------------------------------------
    // Derived values
    // ------------------------------------------------------------------

    public static Item activationItem() {
        return resolveItem(ACTIVATION_ITEM.get(), Items.BLAZE_ROD);
    }

    public static Item upgradeItem() {
        return resolveItem(UPGRADE_ITEM.get(), Items.DIAMOND);
    }

    /** Number of upgrade items needed to reach {@code targetLevel} (targetLevel >= 2). */
    public static int upgradeCost(int targetLevel) {
        return UPGRADE_BASE_COST.get() + UPGRADE_COST_PER_LEVEL.get() * Math.max(0, targetLevel - 2);
    }

    public static int maxCoreHealth(int level) {
        return CORE_HEALTH_BASE.get() + CORE_HEALTH_PER_LEVEL.get() * Math.max(0, level);
    }

    public static int coreZoneRadius(int level) {
        return (int) Math.floor(CORE_ZONE_RADIUS_BASE.get() + CORE_ZONE_RADIUS_PER_LEVEL.get() * level);
    }

    public static int siegeChunkRadius(int level) {
        int radius = (int) Math.floor(SIEGE_CHUNK_RADIUS_BASE.get() + SIEGE_CHUNK_RADIUS_PER_LEVEL.get() * level);
        return Math.max(1, Math.min(radius, SIEGE_CHUNK_RADIUS_MAX.get()));
    }

    /** Siege area radius in blocks (square around the Core). */
    public static int siegeRadiusBlocks(int level) {
        return siegeChunkRadius(level) * 16;
    }

    public static int maxSiegeMobs(int level) {
        return MAX_SIEGE_MOBS_BASE.get() + MAX_SIEGE_MOBS_PER_LEVEL.get() * level;
    }
}
