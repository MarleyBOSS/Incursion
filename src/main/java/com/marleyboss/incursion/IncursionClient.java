package com.marleyboss.incursion;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only entry point. Not loaded on dedicated servers. */
@Mod(value = Incursion.MODID, dist = Dist.CLIENT)
public class IncursionClient {
    public IncursionClient(ModContainer container) {
        // Adds a "Config" button for this mod in the Mods screen.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
