package com.marleyboss.incursion;

import com.marleyboss.incursion.registry.ModBlockEntities;
import com.marleyboss.incursion.registry.ModBlocks;
import com.marleyboss.incursion.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;

@Mod(Incursion.MODID)
public class Incursion {
    public static final String MODID = "incursion";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Incursion(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);

        modEventBus.addListener(this::addCreative);

        // Server config: stored per world in <world>/serverconfig/incursion-server.toml
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.CORE);
        }
    }
}
