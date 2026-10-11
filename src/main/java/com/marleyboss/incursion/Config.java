package com.marleyboss.incursion;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side configuration. Values are per world and synced to clients.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

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

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    private static boolean isValidItem(Object value) {
        if (!(value instanceof String s)) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(s);
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }

    private static Item resolveItem(String id, Item fallback) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
            return fallback;
        }
        return BuiltInRegistries.ITEM.get(location);
    }

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
}
