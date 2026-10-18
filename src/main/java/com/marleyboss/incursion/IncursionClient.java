package com.marleyboss.incursion;

import com.marleyboss.incursion.client.CoreItemRenderer;
import com.marleyboss.incursion.client.CoreRenderer;
import com.marleyboss.incursion.client.LootChestItemRenderer;
import com.marleyboss.incursion.client.LootChestRenderer;
import com.marleyboss.incursion.client.WardRenderer;
import com.marleyboss.incursion.client.CoreScreen;
import com.marleyboss.incursion.client.CoreTableRenderer;
import com.marleyboss.incursion.client.CoreTableScreen;
import com.marleyboss.incursion.registry.ModBlockEntities;
import com.marleyboss.incursion.registry.ModEntities;
import com.marleyboss.incursion.registry.ModItems;
import com.marleyboss.incursion.registry.ModMenus;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only entry point. Not loaded on dedicated servers. */
@Mod(value = Incursion.MODID, dist = Dist.CLIENT)
public class IncursionClient {
    public IncursionClient(IEventBus modEventBus, ModContainer container) {
        // Adds a "Config" button for this mod in the Mods screen.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modEventBus.addListener(IncursionClient::registerScreens);
        modEventBus.addListener(IncursionClient::registerRenderers);
        modEventBus.addListener(IncursionClient::registerItemExtensions);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(CoreRenderer::onRenderLevel);
    }

    /** The loot chest item is drawn like a chest (with the chest renderer), not as a flat cube. */
    private static void registerItemExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private LootChestItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new LootChestItemRenderer();
                }
                return renderer;
            }
        }, ModItems.LOOT_CHEST.get());
        // The Sarcophagus item is the shut block, drawn by the same code as the block.
        event.registerItem(new IClientItemExtensions() {
            private CoreItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new CoreItemRenderer();
                }
                return renderer;
            }
        }, ModItems.CORE.get());
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.CORE.get(), CoreScreen::new);
        event.register(ModMenus.CORE_TABLE.get(), CoreTableScreen::new);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // The Core target is invisible.
        event.registerEntityRenderer(ModEntities.CORE_TARGET.get(), NoopRenderer::new);
        // Shield domes and border beams.
        event.registerBlockEntityRenderer(ModBlockEntities.CORE.get(), CoreRenderer::new);
        // Health bar above ward totems.
        event.registerBlockEntityRenderer(ModBlockEntities.WARD.get(), WardRenderer::new);
        // The item floating above the Core Table.
        event.registerBlockEntityRenderer(ModBlockEntities.CORE_TABLE.get(), CoreTableRenderer::new);
        // The loot chest with its animated lid.
        event.registerBlockEntityRenderer(ModBlockEntities.LOOT_CHEST.get(), LootChestRenderer::new);
    }
}
