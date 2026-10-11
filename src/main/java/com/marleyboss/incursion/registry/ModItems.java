package com.marleyboss.incursion.registry;

import com.marleyboss.incursion.Incursion;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Incursion.MODID);

    public static final DeferredItem<BlockItem> CORE = ITEMS.registerSimpleBlockItem(
            "core", ModBlocks.CORE, new Item.Properties().rarity(Rarity.EPIC).stacksTo(1));

    private ModItems() {
    }
}
