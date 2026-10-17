package com.marleyboss.incursion.registry;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.item.UpgradeToolItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Incursion.MODID);

    public static final DeferredItem<BlockItem> CORE = ITEMS.registerSimpleBlockItem(
            "core", ModBlocks.CORE, new Item.Properties().rarity(Rarity.EPIC).stacksTo(1));

    public static final DeferredItem<BlockItem> WARD = ITEMS.registerSimpleBlockItem(
            "ward", ModBlocks.WARD, new Item.Properties().rarity(Rarity.UNCOMMON));

    public static final DeferredItem<BlockItem> CORE_TABLE = ITEMS.registerSimpleBlockItem(
            "core_table", ModBlocks.CORE_TABLE, new Item.Properties().rarity(Rarity.RARE));

    public static final DeferredItem<BlockItem> LOOT_CHEST = ITEMS.registerSimpleBlockItem(
            "loot_chest", ModBlocks.LOOT_CHEST, new Item.Properties());

    /** The currency of the mod: earned for every night the Core survives, kept in the Core, can be taken out. */
    public static final DeferredItem<Item> CORE_SHARD = ITEMS.registerSimpleItem(
            "core_shard", new Item.Properties().rarity(Rarity.RARE));

    public static final DeferredItem<BlockItem> SIEGE_STONE = ITEMS.registerSimpleBlockItem(
            "siege_stone", ModBlocks.SIEGE_STONE, new Item.Properties());

    /** Upgrade Tools, tier I to IV (index 0 = tier I): needed in the inventory to upgrade the Core. */
    public static final DeferredItem<UpgradeToolItem> UPGRADE_TOOL_1 = ITEMS.register("upgrade_tool_1",
            () -> new UpgradeToolItem(1, new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
    public static final DeferredItem<UpgradeToolItem> UPGRADE_TOOL_2 = ITEMS.register("upgrade_tool_2",
            () -> new UpgradeToolItem(2, new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
    public static final DeferredItem<UpgradeToolItem> UPGRADE_TOOL_3 = ITEMS.register("upgrade_tool_3",
            () -> new UpgradeToolItem(3, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));
    public static final DeferredItem<UpgradeToolItem> UPGRADE_TOOL_4 = ITEMS.register("upgrade_tool_4",
            () -> new UpgradeToolItem(4, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    /** The Upgrade Tool of the given tier (clamped to 1..4). */
    public static DeferredItem<UpgradeToolItem> upgradeTool(int tier) {
        return switch (Math.max(1, Math.min(4, tier))) {
            case 1 -> UPGRADE_TOOL_1;
            case 2 -> UPGRADE_TOOL_2;
            case 3 -> UPGRADE_TOOL_3;
            default -> UPGRADE_TOOL_4;
        };
    }

    private ModItems() {
    }
}
