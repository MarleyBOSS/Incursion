package com.marleyboss.incursion;

import com.marleyboss.incursion.fallout.Fallout;
import com.marleyboss.incursion.registry.ModBlockEntities;
import com.marleyboss.incursion.registry.ModBlocks;
import com.marleyboss.incursion.registry.ModEntities;
import com.marleyboss.incursion.registry.ModItems;
import com.marleyboss.incursion.registry.ModMenus;
import com.marleyboss.incursion.siege.ChunkLoading;
import com.marleyboss.incursion.siege.Rewards;
import com.marleyboss.incursion.siege.SiegeEvents;
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
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);

        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(ModEntities::registerAttributes);
        modEventBus.addListener(ChunkLoading::register);
        modEventBus.addListener(Fallout::registerTickets);
        SiegeEvents.register();
        Rewards.register();
        Fallout.register();
        if (System.getenv("INCURSION_PROBE") != null) {
            com.marleyboss.incursion.dev.PhysicsProbe.register(); // development measurement, see the Probe workflow
        }

        // Server config: stored per world in <world>/serverconfig/incursion-server.toml
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.CORE);
            event.accept(ModItems.WARD);
            event.accept(ModItems.CORE_TABLE);
            event.accept(ModItems.LOOT_CHEST);
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(ModItems.CORE_SHARD);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.UPGRADE_TOOL_1);
            event.accept(ModItems.UPGRADE_TOOL_2);
            event.accept(ModItems.UPGRADE_TOOL_3);
            event.accept(ModItems.UPGRADE_TOOL_4);
        }
    }
}
