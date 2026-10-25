package com.marleyboss.incursion.registry;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.item.InsigniaItem;
import com.marleyboss.incursion.item.MythicPotionItem;
import com.marleyboss.incursion.item.TabletItem;
import com.marleyboss.incursion.item.UpgradeToolItem;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.alchemy.PotionContents;
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

    /** Laid into the Sarcophagus (right-click), the foundation of the Core. */
    public static final DeferredItem<Item> CORE_RUNE = ITEMS.registerSimpleItem(
            "core_rune", new Item.Properties().rarity(Rarity.EPIC).stacksTo(16));

    /**
     * Thrown onto the ready Sarcophagus it creates and activates the Core; thrown at living things it burns them.
     * A splash potion without effects (only a colour), so nothing but our own impact handler reacts to it.
     */
    public static final DeferredItem<MythicPotionItem> MYTHIC_POTION = ITEMS.register("mythic_potion",
            () -> new MythicPotionItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)
                    .component(DataComponents.POTION_CONTENTS,
                            new PotionContents(Optional.empty(), Optional.of(0xFF8A1E), List.of()))
                    .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
                    .component(DataComponents.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE)));

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
    /** Tier V: forged from tool IV and the fourth general's insignia, completes Core level 20. */
    public static final DeferredItem<UpgradeToolItem> UPGRADE_TOOL_5 = ITEMS.register("upgrade_tool_5",
            () -> new UpgradeToolItem(5, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));

    /** Tablets of the guardians (stage 1..4): needed for the upgrades to Core level 5, 10, 15 and 20. */
    public static final DeferredItem<TabletItem> TABLET_ELDER_GUARDIAN = tabletItem("tablet_elder_guardian", 1);
    public static final DeferredItem<TabletItem> TABLET_WARDEN = tabletItem("tablet_warden", 2);
    public static final DeferredItem<TabletItem> TABLET_ENDER_DRAGON = tabletItem("tablet_ender_dragon", 3);
    public static final DeferredItem<TabletItem> TABLET_WITHER = tabletItem("tablet_wither", 4);

    /** Insignias of the generals (stage 1..4): complete the waiting upgrade. */
    public static final DeferredItem<InsigniaItem> GENERAL_INSIGNIA_1 = insigniaItem("general_insignia_1", 1);
    public static final DeferredItem<InsigniaItem> GENERAL_INSIGNIA_2 = insigniaItem("general_insignia_2", 2);
    public static final DeferredItem<InsigniaItem> GENERAL_INSIGNIA_3 = insigniaItem("general_insignia_3", 3);
    public static final DeferredItem<InsigniaItem> GENERAL_INSIGNIA_4 = insigniaItem("general_insignia_4", 4);

    private static DeferredItem<TabletItem> tabletItem(String name, int stage) {
        return ITEMS.register(name, () -> new TabletItem(stage,
                new Item.Properties().stacksTo(16).rarity(Rarity.EPIC).fireResistant()));
    }

    private static DeferredItem<InsigniaItem> insigniaItem(String name, int stage) {
        return ITEMS.register(name, () -> new InsigniaItem(stage,
                new Item.Properties().stacksTo(16).rarity(Rarity.EPIC).fireResistant()
                        .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)));
    }

    /** The tablet of the given stage (clamped to 1..4). */
    public static DeferredItem<TabletItem> tablet(int stage) {
        return switch (Math.max(1, Math.min(4, stage))) {
            case 1 -> TABLET_ELDER_GUARDIAN;
            case 2 -> TABLET_WARDEN;
            case 3 -> TABLET_ENDER_DRAGON;
            default -> TABLET_WITHER;
        };
    }

    /** The insignia of the given stage (clamped to 1..4). */
    public static DeferredItem<InsigniaItem> insignia(int stage) {
        return switch (Math.max(1, Math.min(4, stage))) {
            case 1 -> GENERAL_INSIGNIA_1;
            case 2 -> GENERAL_INSIGNIA_2;
            case 3 -> GENERAL_INSIGNIA_3;
            default -> GENERAL_INSIGNIA_4;
        };
    }

    /** The Upgrade Tool of the given tier (clamped to 1..5). */
    public static DeferredItem<UpgradeToolItem> upgradeTool(int tier) {
        return switch (Math.max(1, Math.min(5, tier))) {
            case 1 -> UPGRADE_TOOL_1;
            case 2 -> UPGRADE_TOOL_2;
            case 3 -> UPGRADE_TOOL_3;
            case 4 -> UPGRADE_TOOL_4;
            default -> UPGRADE_TOOL_5;
        };
    }

    private ModItems() {
    }
}
