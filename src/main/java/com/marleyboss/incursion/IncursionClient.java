package com.marleyboss.incursion;

import com.marleyboss.incursion.client.CoreRenderer;
import com.marleyboss.incursion.client.CoreScreen;
import com.marleyboss.incursion.registry.ModBlockEntities;
import com.marleyboss.incursion.registry.ModEntities;
import com.marleyboss.incursion.registry.ModMenus;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
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
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.CORE.get(), CoreScreen::new);
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // The Core target is invisible.
        event.registerEntityRenderer(ModEntities.CORE_TARGET.get(), NoopRenderer::new);
        // Shield domes and border beams.
        event.registerBlockEntityRenderer(ModBlockEntities.CORE.get(), CoreRenderer::new);
    }
}
