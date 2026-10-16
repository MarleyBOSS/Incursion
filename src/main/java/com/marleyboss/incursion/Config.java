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

    public static final ModConfigSpec.BooleanValue UPGRADE_TOOL_REQUIRED = BUILDER
            .comment("If true, the Core is upgraded by right-clicking it with an Upgrade Tool in the hand (the upgrade items",
                    "are taken from the inventory, the tool is not used up, the GUI button does not upgrade).",
                    "If false, the upgrade item in the hand or the GUI button upgrades, no tool needed (old behaviour).")
            .define("upgradeToolRequired", true);

    public static final ModConfigSpec.IntValue UPGRADE_TOOL_LEVELS = BUILDER
            .comment("Core levels covered by one Upgrade Tool tier. With 5: tier I reaches level 5, tier II level 10,",
                    "tier III level 15, tier IV every level above. Stronger tools are crafted from the weaker one.")
            .defineInRange("upgradeToolLevels", 5, 1, 100);

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
            .comment("Core zone radius in blocks, the same on every Core level. The safe zone around the Core is this",
                    "plus 6 blocks. Inside the core zone, hostile mobs ignore players and attack only the Core.")
            .defineInRange("coreZoneRadiusBase", 8.0, 2.0, 64.0);

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

    public static final ModConfigSpec.BooleanValue NIGHT_ONLY_SPAWNS = BUILDER
            .comment("New siege attackers only spawn at night (see nightSpawnStart / nightSpawnEnd). By day the Core waits",
                    "for dusk; attackers that are already there keep going. Turn off for a siege around the clock.")
            .define("nightOnlySpawns", true);

    public static final ModConfigSpec.IntValue NIGHT_SPAWN_START = BUILDER
            .comment("Time of day (ticks, 0-23999) at which attackers start to spawn. 13000 = dusk.")
            .defineInRange("nightSpawnStart", 13000, 0, 23999);

    public static final ModConfigSpec.IntValue NIGHT_SPAWN_END = BUILDER
            .comment("Time of day (ticks, 0-23999) at which attackers stop to spawn. 23000 = shortly before sunrise.",
                    "A value below nightSpawnStart means the window wraps around midnight of the clock; equal = always.")
            .defineInRange("nightSpawnEnd", 23000, 0, 23999);

    public static final ModConfigSpec.DoubleValue MOB_HEALTH_BONUS_PER_LEVEL = BUILDER
            .comment("Siege mobs get +X max health per core level (0.05 = +5% per level).")
            .defineInRange("mobHealthBonusPerLevel", 0.05, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue MOB_DAMAGE_BONUS_PER_LEVEL = BUILDER
            .comment("Siege mobs get +X attack damage per core level (0.03 = +3% per level).")
            .defineInRange("mobDamageBonusPerLevel", 0.03, 0.0, 10.0);

    public static final ModConfigSpec.BooleanValue SIEGE_MOBS_SUN_PROOF = BUILDER
            .comment("Siege mobs do not burn in daylight. Lava and fire still hurt them (no fire resistance since 0.7.7).")
            .define("siegeMobsSunProof", true);

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
                    "minecraft:enderman=6",
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

    // ------------------------------------------------------------------
    // Digging
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("Mobs that cannot reach the Core dig through blocks in their way").push("digging");
    }

    public static final ModConfigSpec.BooleanValue DIGGING_ENABLED = BUILDER
            .comment("If true, melee mobs that are stuck on their way to the Core break the blocks in their way.",
                    "Ranged mobs (skeletons, pillagers, witches ...) and spiders never dig.")
            .define("diggingEnabled", true);

    public static final ModConfigSpec.IntValue STUCK_SECONDS = BUILDER
            .comment("A mob counts as stuck when it has not come closer to the Core for this many seconds.")
            .defineInRange("stuckSeconds", 3, 1, 60);

    public static final ModConfigSpec.DoubleValue TOOL_DIG_SPEED_MULTIPLIER = BUILDER
            .comment("Dig speed of mobs holding a fitting tool (e.g. a pickaxe on stone), relative to a player with that tool.",
                    "1.0 = as fast as a player. Block hardness counts like for players (deepslate takes longer than dirt).")
            .defineInRange("toolDigSpeedMultiplier", 0.35, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue MELEE_DIG_SPEED_MULTIPLIER = BUILDER
            .comment("Dig speed of mobs without a fitting tool. Their speed grows with their attack damage:",
                    "a mob with 3 attack damage (zombie) and multiplier 1.0 digs like a player with bare hands.")
            .defineInRange("meleeDigSpeedMultiplier", 0.5, 0.0, 10.0);

    public static final ModConfigSpec.BooleanValue DUG_BLOCKS_DROP = BUILDER
            .comment("If true, blocks broken by mobs drop as items.")
            .define("dugBlocksDrop", true);

    public static final ModConfigSpec.BooleanValue CREEPER_BREACH = BUILDER
            .comment("If true, stuck creepers blow themselves up at the obstacle to clear the way for other mobs.",
                    "These breach explosions always destroy blocks (but never the Core).")
            .define("creeperBreach", true);

    public static final ModConfigSpec.BooleanValue ENDERMEN_TAKE_BLOCKS = BUILDER
            .comment("If true, stuck endermen pick up the block in their way (blocks with an inventory excluded).")
            .define("endermenTakeBlocks", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> NON_DIGGING_MOBS = BUILDER
            .comment("Melee-looking mobs that should never dig (entity ids).")
            .defineListAllowEmpty("nonDiggingMobs", List.of(
                    "minecraft:blaze",
                    "minecraft:breeze",
                    "minecraft:vex",
                    "minecraft:shulker",
                    "minecraft:guardian",
                    "minecraft:elder_guardian",
                    "minecraft:silverfish",
                    "minecraft:endermite"
            ), () -> "minecraft:blaze", Config::isResourceLocation);

    static {
        BUILDER.pop();
    }

    // ------------------------------------------------------------------
    // Zombie roles
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("Siege zombies get a role: warrior (sword), miner (pickaxe), breaker (axe), builder (block)").push("zombies");
    }

    public static final ModConfigSpec.BooleanValue ZOMBIE_ROLES = BUILDER
            .comment("If true, zombies (and husks) spawned by the siege get a role and a matching weapon.")
            .define("zombieRoles", true);

    public static final ModConfigSpec.IntValue WARRIOR_WEIGHT = BUILDER
            .comment("Chance weight for warriors (sword). Warriors hunt players everywhere (also next to the Core)",
                    "and never break blocks. New zombies get the role that is most missing right now.")
            .defineInRange("warriorWeight", 30, 0, 1000);

    public static final ModConfigSpec.IntValue MINER_WEIGHT = BUILDER
            .comment("Chance weight for miners (pickaxe, dig through blocks quickly).")
            .defineInRange("minerWeight", 25, 0, 1000);

    public static final ModConfigSpec.IntValue BREAKER_WEIGHT = BUILDER
            .comment("Chance weight for breakers (axe, more damage against the Core).")
            .defineInRange("breakerWeight", 20, 0, 1000);

    public static final ModConfigSpec.IntValue BUILDER_WEIGHT = BUILDER
            .comment("Chance weight for builders (hold a block). Builders place blocks to make stairs and bridges",
                    "towards the Core. They never build straight pillars.")
            .defineInRange("builderWeight", 25, 0, 1000);

    public static final ModConfigSpec.IntValue BUILD_TICKS = BUILDER
            .comment("Ticks a builder needs to place one block (20 ticks = 1 second).")
            .defineInRange("buildTicks", 15, 1, 200);

    public static final ModConfigSpec.IntValue IRON_GEAR_FROM_LEVEL = BUILDER
            .comment("Core level from which role zombies carry iron tools (below: stone).")
            .defineInRange("ironGearFromLevel", 5, 1, 100);

    public static final ModConfigSpec.IntValue DIAMOND_GEAR_FROM_LEVEL = BUILDER
            .comment("Core level from which role zombies carry diamond tools.")
            .defineInRange("diamondGearFromLevel", 10, 1, 100);

    public static final ModConfigSpec.IntValue NETHERITE_GEAR_FROM_LEVEL = BUILDER
            .comment("Core level from which role zombies carry netherite tools.")
            .defineInRange("netheriteGearFromLevel", 16, 1, 100);

    public static final ModConfigSpec.DoubleValue WARRIOR_PLAYER_DAMAGE = BUILDER
            .comment("Damage multiplier of warriors against players.")
            .defineInRange("warriorPlayerDamageMultiplier", 1.5, 0.0, 100.0);

    public static final ModConfigSpec.DoubleValue BREAKER_PLAYER_DAMAGE = BUILDER
            .comment("Damage multiplier of breakers against players (axes hit hard, so this keeps warriors the player killers).")
            .defineInRange("breakerPlayerDamageMultiplier", 0.6, 0.0, 100.0);

    public static final ModConfigSpec.DoubleValue BREAKER_CORE_DAMAGE = BUILDER
            .comment("Damage multiplier of breakers against the Core.")
            .defineInRange("breakerCoreDamageMultiplier", 2.5, 0.0, 100.0);

    static {
        BUILDER.pop();
    }

    // ------------------------------------------------------------------
    // Explosion and contamination
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("The big explosion when an active Core is destroyed or removed").push("explosion");
    }

    public static final ModConfigSpec.DoubleValue EXPLOSION_RADIUS_BASE = BUILDER
            .comment("Crater radius in blocks = explosionRadiusBase + explosionRadiusPerLevel * level.")
            .defineInRange("explosionRadiusBase", 10.0, 2.0, 64.0);

    public static final ModConfigSpec.DoubleValue EXPLOSION_RADIUS_PER_LEVEL = BUILDER
            .comment("Additional crater radius per level (see explosionRadiusBase).")
            .defineInRange("explosionRadiusPerLevel", 1.5, 0.0, 16.0);

    public static final ModConfigSpec.IntValue EXPLOSION_RADIUS_MAX = BUILDER
            .comment("Upper limit for the crater radius in blocks.")
            .defineInRange("explosionRadiusMax", 48, 2, 64);

    public static final ModConfigSpec.IntValue EXPLOSION_BLOCKS_PER_TICK = BUILDER
            .comment("How many blocks the crater removes per tick. Lower = smoother but slower explosion.")
            .defineInRange("explosionBlocksPerTick", 2000, 100, 20000);

    public static final ModConfigSpec.DoubleValue EXPLOSION_DAMAGE = BUILDER
            .comment("Damage dealt to creatures at the centre of the explosion (falls off towards the edge).")
            .defineInRange("explosionDamage", 80.0, 0.0, 10000.0);

    public static final ModConfigSpec.BooleanValue CONTAMINATION_ENABLED = BUILDER
            .comment("If true, the explosion leaves a contaminated wasteland around the crater.")
            .define("contaminationEnabled", true);

    public static final ModConfigSpec.DoubleValue CONTAMINATION_RADIUS_FACTOR = BUILDER
            .comment("Contaminated zone radius = crater radius * this factor (max 96 blocks).")
            .defineInRange("contaminationRadiusFactor", 1.5, 1.0, 4.0);

    public static final ModConfigSpec.IntValue CONTAMINATION_MINUTES = BUILDER
            .comment("How long (in-game minutes of world time) the zone harms creatures. -1 = forever.",
                    "The wasteland blocks stay either way.")
            .defineInRange("contaminationMinutes", -1, -1, 10_000_000);

    public static final ModConfigSpec.IntValue CONTAMINATION_WITHER_LEVEL = BUILDER
            .comment("Wither effect level for creatures in the contaminated zone (0 = no damage).",
                    "Undead mobs are immune to wither.")
            .defineInRange("contaminationWitherLevel", 1, 0, 5);

    static {
        BUILDER.pop();
    }

    // ------------------------------------------------------------------
    // Defusing
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("Defusing the Core (resets it to level 0 without an explosion)").push("defuse");
    }

    public static final ModConfigSpec.IntValue DEFUSE_BASE_SECONDS = BUILDER
            .comment("Defusing takes defuseBaseSeconds + defusePerLevelSeconds * level seconds.")
            .defineInRange("defuseBaseSeconds", 10, 0, 3600);

    public static final ModConfigSpec.IntValue DEFUSE_PER_LEVEL_SECONDS = BUILDER
            .comment("Additional defusing time per level (see defuseBaseSeconds).")
            .defineInRange("defusePerLevelSeconds", 2, 0, 600);

    public static final ModConfigSpec.IntValue DEFUSE_MAX_DISTANCE = BUILDER
            .comment("The defusing player must stay within this many blocks of the Core, or defusing stops.")
            .defineInRange("defuseMaxDistance", 8, 2, 64);

    static {
        BUILDER.pop();
    }

    // ------------------------------------------------------------------
    // Skeleton classes
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("Siege skeletons get an arrow class: blast (red leather armour), fire (red leather cap), normal (chainmail)")
                .push("skeletons");
    }

    public static final ModConfigSpec.BooleanValue SKELETON_CLASSES = BUILDER
            .comment("If true, skeletons (and strays, bogged) spawned by the siege get an arrow class.")
            .define("skeletonClasses", true);

    public static final ModConfigSpec.IntValue BLAST_ARCHER_WEIGHT = BUILDER
            .comment("Chance weight for blast archers: explosive arrows that blow a way to the Core.")
            .defineInRange("blastArcherWeight", 25, 0, 1000);

    public static final ModConfigSpec.IntValue FIRE_ARCHER_WEIGHT = BUILDER
            .comment("Chance weight for fire archers: burning arrows, they also ignite TNT placed by endermen.")
            .defineInRange("fireArcherWeight", 30, 0, 1000);

    public static final ModConfigSpec.IntValue ARCHER_WEIGHT = BUILDER
            .comment("Chance weight for normal archers.")
            .defineInRange("archerWeight", 45, 0, 1000);

    public static final ModConfigSpec.DoubleValue BLAST_ARROW_POWER = BUILDER
            .comment("Explosion power of explosive arrows (creeper = 3, TNT = 4).")
            .defineInRange("blastArrowPower", 2.5, 0.5, 6.0);

    public static final ModConfigSpec.IntValue BLAST_SHOT_SECONDS = BUILDER
            .comment("Blast archers that cannot see the Core shoot an explosive arrow at the wall in the way every X seconds.")
            .defineInRange("blastShotSeconds", 4, 1, 120);

    static {
        BUILDER.pop();
    }

    // ------------------------------------------------------------------
    // Endermen
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("Siege endermen").push("endermen");
    }

    public static final ModConfigSpec.DoubleValue SAPPER_CHANCE = BUILDER
            .comment("Chance that a siege enderman is a sapper: it carries TNT and places it next to the Core.",
                    "Fire archers ignite the TNT.")
            .defineInRange("sapperChance", 0.6, 0.0, 1.0);

    public static final ModConfigSpec.IntValue TNT_FUSE_FALLBACK_SECONDS = BUILDER
            .comment("If no fire archer ignites placed TNT within this many seconds, it ignites by itself. -1 = never.")
            .defineInRange("tntFallbackSeconds", 90, -1, 3600);

    static {
        BUILDER.pop();
    }

    // ------------------------------------------------------------------
    // Tactics
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("How the siege mobs work together").push("tactics");
    }

    public static final ModConfigSpec.BooleanValue BUILDING_ENABLED = BUILDER
            .comment("If true, builders place blocks (stairs, bridges) on the way to the Core.")
            .define("buildingEnabled", true);

    public static final ModConfigSpec.BooleanValue DRAIN_SOURCES = BUILDER
            .comment("If true, builders remove the source block of water or lava in their way (one block, the whole flow",
                    "runs dry for everybody). If a body of fluid is too big or out of reach, they fill it cell by cell instead.")
            .define("drainSources", true);

    public static final ModConfigSpec.IntValue DRAIN_MAX_SOURCES = BUILDER
            .comment("Builders only remove sources if at most this many source blocks feed the fluid in their way",
                    "(a pond yes, a lake or an ocean no).")
            .defineInRange("drainMaxSources", 16, 1, 128);

    public static final ModConfigSpec.IntValue SWIM_EFFICIENCY_PERCENT = BUILDER
            .comment("How well siege mobs swim: the vanilla attribute \"water movement efficiency\" (the effect of Depth Strider)",
                    "in percent. 0 = like vanilla mobs (very slow, swept away by every current), 100 = they swim as fast as they walk",
                    "and a current hardly bothers them. Roughly: 30 = fast enough to get out of a pond, a current still slows them;",
                    "60 = a good swimmer (default), flowing water is only a small obstacle.")
            .defineInRange("swimEfficiencyPercent", 60, 0, 100);

    public static final ModConfigSpec.BooleanValue LADDERS_ENABLED = BUILDER
            .comment("No longer used (since 0.7.1 the siege builds staircases instead of ladders).")
            .define("laddersEnabled", true);

    public static final ModConfigSpec.IntValue PLANNER_MAX_NODES = BUILDER
            .comment("How many positions the siege planner may check for one plan (at least 60000 are used; spread over several seconds).")
            .defineInRange("plannerMaxNodes", 12000, 500, 100000);

    public static final ModConfigSpec.BooleanValue AIR_DROPS = BUILDER
            .comment("If true, phantoms sometimes carry siege mobs to a free spot next to the Core (rarely at low levels,",
                    "more often at high levels), often when the siege is stuck, and always when no spawn spot on the ground is found.")
            .define("airDrops", true);

    public static final ModConfigSpec.DoubleValue AIR_DROP_BASE_CHANCE = BUILDER
            .comment("Chance that a new attacker comes by air (at Core level 0).")
            .defineInRange("airDropBaseChance", 0.03, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue AIR_DROP_CHANCE_PER_LEVEL = BUILDER
            .comment("Added to the air drop chance per Core level (e.g. 0.03 + 20 x 0.01 = 23 % at level 20).")
            .defineInRange("airDropChancePerLevel", 0.01, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue AIR_DROP_STALL_CHANCE = BUILDER
            .comment("Chance that a new attacker comes by air while the siege is stuck (no other way to the Core).")
            .defineInRange("airDropStallChance", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue SPIDER_RIDER_CHANCE = BUILDER
            .comment("Chance that a siege spider carries a zombie or skeleton up the walls.")
            .defineInRange("spiderRiderChance", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.IntValue LOST_SECONDS = BUILDER
            .comment("A mob more than 24 blocks from the Core that got no closer for this many seconds (and whose route made",
                    "no progress) counts as lost, e.g. stuck in a cave: siege mobs disappear (a new one spawns in a better place),",
                    "naturally spawned mobs leave the siege and behave normally again (so they can despawn). 0 = off.")
            .defineInRange("lostSeconds", 60, 0, 36000);

    public static final ModConfigSpec.IntValue ROUTE_CAPACITY = BUILDER
            .comment("At most this many mobs use one route (staircase, ladder ...). Further mobs plan their own route next to it,",
                    "so the siege builds several ways up and splits up.")
            .defineInRange("routeCapacity", 8, 2, 64);

    public static final ModConfigSpec.IntValue FRONTS = BUILDER
            .comment("How many lines of attack (each with its own way / staircase to the Core) the siege builds at the same time,",
                    "at most. Level 0-4: 1, level 5-9: 2, level 10-14: 3, level 15+: 4 (never more than this value).")
            .defineInRange("fronts", 4, 1, 6);

    public static final ModConfigSpec.IntValue FRONT_WAITERS = BUILDER
            .comment("While a route is still being built, only this many mobs wait right behind the workers.",
                    "All others gather at the foot of the route and storm up together once the way is open.")
            .defineInRange("frontWaiters", 2, 0, 32);

    public static final ModConfigSpec.BooleanValue BUILDER_NO_PUSH = BUILDER
            .comment("If true, builder zombies cannot be pushed and do not push others (they are on the scoreboard team",
                    "\"incursion_builders\" with collision rule \"never\"), so they always get through the crowd on narrow stairs",
                    "and bridges. All other siege mobs push each other like normal mobs.")
            .define("builderNoPush", true);

    public static final ModConfigSpec.BooleanValue BLOCK_CAVE_SPAWNS = BUILDER
            .comment("If true, no monsters spawn naturally in caves inside the siege area, unless the cave is at about the",
                    "Core's height (e.g. a cave base). Saves performance; such mobs could not reach the Core anyway.")
            .define("blockCaveSpawns", true);

    public static final ModConfigSpec.BooleanValue SAFE_ZONE_BLOCKS_NATURAL_SPAWNS = BUILDER
            .comment("If true, no hostile monster spawns naturally inside the safe zone (the squares around the Core",
                    "and around every ward totem, see the \"Borders\" switch in the Core GUI).",
                    "Siege mobs never spawn there, whatever this is set to.")
            .define("safeZoneBlocksNaturalSpawns", true);

    public static final ModConfigSpec.IntValue GUARD_RANGE = BUILDER
            .comment("Warriors attack players within this many blocks, in every zone (also next to the Core).",
                    "Archers give cover fire on players within this range while they cannot shoot the Core.")
            .defineInRange("guardRange", 24, 0, 64);

    public static final ModConfigSpec.IntValue HUNTER_PERCENT = BUILDER
            .comment("Teams: this share (in percent) of the fighters (warriors and archers) are hunters. Hunters go for players",
                    "(see hunterRange) and hold posts on the platforms of the stairs. All other fighters are the Core team's",
                    "bodyguards (see bodyguardRange). Builders, miners, breakers and sappers always belong to the Core team.")
            .defineInRange("hunterPercent", 40, 0, 100);

    public static final ModConfigSpec.IntValue HUNTER_RANGE = BUILDER
            .comment("Hunters attack players within this many blocks of themselves, and any player close to the Core.")
            .defineInRange("hunterRange", 32, 0, 96);

    public static final ModConfigSpec.IntValue BODYGUARD_RANGE = BUILDER
            .comment("The Core team's bodyguards (fighters that are not hunters) only attack players this close to themselves.")
            .defineInRange("bodyguardRange", 10, 0, 64);

    public static final ModConfigSpec.BooleanValue LANDINGS_ENABLED = BUILDER
            .comment("If true, builders also build landings (wide platforms) along the stairs. Archers use them as firing",
                    "positions and posts, and mobs have room on the way up.")
            .define("landingsEnabled", true);

    public static final ModConfigSpec.IntValue STAIR_FLIGHT = BUILDER
            .comment("Stairs are planned in straight flights of at least this many steps before they turn (a landing is built",
                    "at every turn), like a stairwell. 1 = turn as often as it is cheapest (tight corkscrew stairs).")
            .defineInRange("stairFlight", 4, 1, 12);

    public static final ModConfigSpec.IntValue STAIR_WIDTH = BUILDER
            .comment("Width of the stairs and ladder towers the builders make (in blocks, where there is room).",
                    "3 = one extra column on each side of the way, 2 = one extra column, 1 = only the way itself.",
                    "Wider ways jam less, because mobs can pass each other.")
            .defineInRange("stairWidth", 3, 1, 3);

    public static final ModConfigSpec.BooleanValue WIDEN_ACCESS = BUILDER
            .comment("If true, waiting miners and fighters dig extra access cells into the wall right next to the attack",
                    "position at the Core (a Core walled in obsidian gets more room to be attacked from).")
            .define("widenAccess", true);

    public static final ModConfigSpec.IntValue HARD_DIG_WORKERS = BUILDER
            .comment("At most this many mobs dig at the same very hard block (obsidian ...) at once. Their progress adds up.",
                    "For ordinary blocks the limit is 3.")
            .defineInRange("hardDigWorkers", 6, 3, 12);

    public static final ModConfigSpec.IntValue LANDING_SPACING = BUILDER
            .comment("A landing every this many steps of a staircase (the last one is right below the platform at the Core).")
            .defineInRange("landingSpacing", 6, 3, 30);

    public static final ModConfigSpec.BooleanValue HUNT_ENABLED = BUILDER
            .comment("If true, the hunter team has its own builders and miners: while a player is close to the Core",
                    "(see huntRadius), they build or dig a way to the player (a tower, a hole, a bunker ...) so the hunters can reach him.")
            .define("huntEnabled", true);

    public static final ModConfigSpec.IntValue HUNT_RADIUS = BUILDER
            .comment("Players within this many blocks of the Core are hunted with their own way (never farther than the",
                    "siege area allows). Players on tour farther away are left alone.")
            .defineInRange("huntRadius", 48, 8, 128);

    public static final ModConfigSpec.IntValue HUNT_MAX_PLAYERS = BUILDER
            .comment("How many players are hunted with their own way at the same time (closest to the Core first).")
            .defineInRange("huntMaxPlayers", 2, 1, 8);

    public static final ModConfigSpec.IntValue HUNT_WORKERS = BUILDER
            .comment("At most this many hunter builders and miners per hunted player (on top of the siege's mob limit).")
            .defineInRange("huntWorkers", 3, 1, 8);

    public static final ModConfigSpec.BooleanValue SIEGE_EXPLOSIONS_SPARE_MOBS = BUILDER
            .comment("If true, explosions caused by siege mobs (creepers, explosive arrows, their TNT, self-destruct)",
                    "do not hurt other siege mobs.")
            .define("siegeExplosionsSpareMobs", true);

    public static final ModConfigSpec.IntValue STALL_SECONDS = BUILDER
            .comment("If the siege makes no progress for this many seconds (no Core damage, no block broken or built),",
                    "reinforcements arrive: more mobs may be alive, and missing roles are spawned first. 0 = off.")
            .defineInRange("stallSeconds", 120, 0, 3600);

    public static final ModConfigSpec.IntValue STALL_EXTRA_MOBS_PERCENT = BUILDER
            .comment("How many more mobs (in percent of the normal maximum) may be alive during a stall.")
            .defineInRange("stallExtraMobsPercent", 50, 0, 500);

    public static final ModConfigSpec.IntValue SELF_DESTRUCT_SECONDS = BUILDER
            .comment("A siege mob that achieved nothing for this many seconds blows itself up (frees the slot for a new mob). 0 = off.")
            .defineInRange("selfDestructSeconds", 300, 0, 36000);

    public static final ModConfigSpec.DoubleValue SELF_DESTRUCT_POWER = BUILDER
            .comment("Explosion power of a self-destructing mob.")
            .defineInRange("selfDestructPower", 2.5, 0.0, 6.0);

    public static final ModConfigSpec.BooleanValue SELF_DESTRUCT_BREAKS_BLOCKS = BUILDER
            .comment("If true, the self-destruct explosion breaks blocks (never the Core).")
            .define("selfDestructBreaksBlocks", true);

    static {
        BUILDER.pop();
    }

    // ------------------------------------------------------------------
    // Ward totems
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("Ward totems: no siege mobs spawn around them (marks your base)").push("wards");
    }

    public static final ModConfigSpec.IntValue WARD_RADIUS = BUILDER
            .comment("Radius in blocks around a ward totem in which siege mobs do not spawn.")
            .defineInRange("wardRadius", 16, 1, 64);

    public static final ModConfigSpec.IntValue WARD_EXTRA_MOBS = BUILDER
            .comment("Every ward totem in the siege area raises the maximum number of siege mobs by this much.")
            .defineInRange("wardExtraMobs", 2, 0, 100);

    public static final ModConfigSpec.IntValue WARD_HEALTH = BUILDER
            .comment("Health of a ward totem. Siege mobs attack it; at 0 it is switched off (the block stays, its safe zone is gone",
                    "until it is repaired).")
            .defineInRange("wardHealth", 100, 1, 30000);

    public static final ModConfigSpec.BooleanValue WARD_FOCUS = BUILDER
            .comment("Focus: a front attacks the outermost ward totem that no other front is attacking first.",
                    "When that totem is switched off, the front goes straight for the Core. Off: fronts only attack the Core.")
            .define("wardFocus", true);

    public static final ModConfigSpec.ConfigValue<String> WARD_REPAIR_ITEM = BUILDER
            .comment("Item that repairs a switched-off ward totem (right-click). Placeholder until Core shards exist.")
            .define("wardRepairItem", "minecraft:diamond", Config::isValidItem);

    public static final ModConfigSpec.IntValue WARD_REPAIR_COST = BUILDER
            .comment("How many repair items one repair costs.")
            .defineInRange("wardRepairCost", 3, 1, 64 * 36);

    static {
        BUILDER.pop();
    }

    // ------------------------------------------------------------------
    // Shields
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("Shields: particle domes around the Core, outermost first.",
                "A hostile mob that enters a dome breaks it and triggers a pulse that kills the attackers nearby.")
                .push("shields");
    }

    public static final ModConfigSpec.BooleanValue SHIELDS_ENABLED = BUILDER
            .comment("Master switch for all shields.")
            .define("shieldsEnabled", true);

    public static final ModConfigSpec.IntValue SHIELD1_MIN_LEVEL = BUILDER
            .comment("Core level from which shield 1 (the outermost) exists.")
            .defineInRange("shield1MinLevel", 5, 1, 100);

    public static final ModConfigSpec.IntValue SHIELD1_RADIUS = BUILDER
            .comment("Radius of shield 1 in blocks.")
            .defineInRange("shield1Radius", 10, 1, 64);

    public static final ModConfigSpec.IntValue SHIELD2_MIN_LEVEL = BUILDER
            .comment("Core level from which shield 2 exists.")
            .defineInRange("shield2MinLevel", 10, 1, 100);

    public static final ModConfigSpec.IntValue SHIELD2_RADIUS = BUILDER
            .comment("Radius of shield 2 in blocks.")
            .defineInRange("shield2Radius", 5, 1, 64);

    public static final ModConfigSpec.IntValue SHIELD3_MIN_LEVEL = BUILDER
            .comment("Core level from which shield 3 (the innermost) exists.")
            .defineInRange("shield3MinLevel", 15, 1, 100);

    public static final ModConfigSpec.IntValue SHIELD3_RADIUS = BUILDER
            .comment("Radius of shield 3 in blocks.")
            .defineInRange("shield3Radius", 2, 1, 64);

    public static final ModConfigSpec.IntValue SHIELD_PULSE_EXTRA_RADIUS = BUILDER
            .comment("The pulse of a breaking shield reaches this many blocks beyond the shield's own radius.")
            .defineInRange("shieldPulseExtraRadius", 12, 0, 64);

    public static final ModConfigSpec.BooleanValue SHIELD_WIPE_BUILDER_BLOCKS = BUILDER
            .comment("If true, the pulse also removes siege stone and siege ladders (the attackers' stairs and bridges)",
                    "outside the shield. Blocks placed by players are never touched.")
            .define("shieldWipeBuilderBlocks", true);

    public static final ModConfigSpec.BooleanValue SHIELD_ABSORBS_DAMAGE = BUILDER
            .comment("If true, the Core takes no damage while at least one shield is intact (arrows from afar do nothing).")
            .define("shieldAbsorbsDamage", true);

    public static final ModConfigSpec.IntValue SHIELD_REPAIR_MIN_LEVEL = BUILDER
            .comment("A broken shield can only be repaired from this Core level.",
                    "Repairing also needs enough level left so that the shield still exists afterwards.")
            .defineInRange("shieldRepairMinLevel", 5, 1, 100);

    public static final ModConfigSpec.IntValue SHIELD_REPAIR_LEVEL_COST = BUILDER
            .comment("Repairing a shield costs this many Core levels.")
            .defineInRange("shieldRepairLevelCost", 1, 0, 20);

    static {
        BUILDER.pop();
    }

    // ------------------------------------------------------------------
    // Rewards (Core shards and the loot chest)
    // ------------------------------------------------------------------

    static {
        BUILDER.comment("Rewards: Core shards for every night the Core survives, and the loot chest for mob drops.")
                .push("rewards");
    }

    public static final ModConfigSpec.BooleanValue SHARDS_ENABLED = BUILDER
            .comment("If false, no Core shards are earned.")
            .define("shardsEnabled", true);

    public static final ModConfigSpec.IntValue SHARDS_PER_NIGHT_BASE = BUILDER
            .comment("Core shards earned for a defended night at Core level 1.")
            .defineInRange("shardsPerNightBase", 1, 0, 1000);

    public static final ModConfigSpec.DoubleValue SHARDS_PER_NIGHT_PER_LEVEL = BUILDER
            .comment("Extra shards per night for every Core level above 1 (rounded down in total).")
            .defineInRange("shardsPerNightPerLevel", 1.0, 0.0, 1000.0);

    public static final ModConfigSpec.BooleanValue LOOT_CHEST_ENABLED = BUILDER
            .comment("If true, the normal drops of siege mobs go into a loot chest near the active Core",
                    "(experience still drops normally). Without a chest, or when all chests are full, drops fall to the ground.")
            .define("lootChestEnabled", true);

    public static final ModConfigSpec.IntValue LOOT_CHEST_RANGE = BUILDER
            .comment("A loot chest collects the drops if it stands within this many blocks of the active Core.")
            .defineInRange("lootChestRange", 16, 1, 128);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * May the siege spawn new attackers at this world time? Always true if night-only spawns are off.
     * Dimensions without a day of their own (Nether, End) use the Overworld clock, like the shard nights.
     */
    public static boolean isSpawnTime(long dayTime) {
        if (!NIGHT_ONLY_SPAWNS.get()) {
            return true;
        }
        long time = Math.floorMod(dayTime, 24000L);
        int start = NIGHT_SPAWN_START.get();
        int end = NIGHT_SPAWN_END.get();
        if (start == end) {
            return true;
        }
        return start < end ? time >= start && time < end : time >= start || time < end;
    }

    /** Core shards earned for one defended night at this Core level. */
    public static int shardsPerNight(int level) {
        return (int) Math.floor(SHARDS_PER_NIGHT_BASE.get()
                + SHARDS_PER_NIGHT_PER_LEVEL.get() * Math.max(0, level - 1));
    }

    /** Number of shields (index 0 = outermost). */
    public static final int SHIELD_COUNT = 3;

    private Config() {
    }

    public static int shieldMinLevel(int index) {
        return switch (index) {
            case 0 -> SHIELD1_MIN_LEVEL.get();
            case 1 -> SHIELD2_MIN_LEVEL.get();
            default -> SHIELD3_MIN_LEVEL.get();
        };
    }

    public static int shieldRadius(int index) {
        return switch (index) {
            case 0 -> SHIELD1_RADIUS.get();
            case 1 -> SHIELD2_RADIUS.get();
            default -> SHIELD3_RADIUS.get();
        };
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

    private static boolean isValidBlock(Object value) {
        if (!(value instanceof String s)) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(s);
        return id != null && BuiltInRegistries.BLOCK.containsKey(id);
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

    public static Item wardRepairItem() {
        return resolveItem(WARD_REPAIR_ITEM.get(), Items.DIAMOND);
    }

    /** Number of upgrade items needed to reach {@code targetLevel} (targetLevel >= 2). */
    public static int upgradeCost(int targetLevel) {
        return UPGRADE_BASE_COST.get() + UPGRADE_COST_PER_LEVEL.get() * Math.max(0, targetLevel - 2);
    }

    /** Highest Upgrade Tool tier; it covers every Core level above the lower tiers. */
    public static final int UPGRADE_TOOL_MAX_TIER = 4;

    /** Upgrade Tool tier needed to reach {@code targetLevel}; 0 if no tool is required. */
    public static int requiredToolTier(int targetLevel) {
        if (!UPGRADE_TOOL_REQUIRED.get()) {
            return 0;
        }
        int perTier = UPGRADE_TOOL_LEVELS.get();
        int tier = (Math.max(1, targetLevel) + perTier - 1) / perTier;
        return Math.max(1, Math.min(UPGRADE_TOOL_MAX_TIER, tier));
    }

    public static int maxCoreHealth(int level) {
        return CORE_HEALTH_BASE.get() + CORE_HEALTH_PER_LEVEL.get() * Math.max(0, level);
    }

    public static int coreZoneRadius(int level) {
        return (int) Math.floor(CORE_ZONE_RADIUS_BASE.get()); // the same on every level
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

    /** How many fronts (ways to the Core) the siege builds at the same time at this Core level. */
    public static int frontsFor(int level) {
        return Math.max(1, Math.min(FRONTS.get(), 1 + Math.max(0, level) / 5));
    }

    public static int craterRadius(int level) {
        int radius = (int) Math.round(EXPLOSION_RADIUS_BASE.get() + EXPLOSION_RADIUS_PER_LEVEL.get() * level);
        return Math.max(2, Math.min(radius, EXPLOSION_RADIUS_MAX.get()));
    }

    public static int contaminationRadius(int craterRadius) {
        return Math.min(96, (int) Math.round(craterRadius * CONTAMINATION_RADIUS_FACTOR.get()));
    }

    public static int defuseTicks(int level) {
        return 20 * (DEFUSE_BASE_SECONDS.get() + DEFUSE_PER_LEVEL_SECONDS.get() * Math.max(0, level));
    }
}
