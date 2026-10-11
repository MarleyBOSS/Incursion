package com.marleyboss.incursion.registry;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.menu.CoreMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Incursion.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<CoreMenu>> CORE = MENUS.register("core",
            () -> IMenuTypeExtension.create((containerId, inventory, buf) -> new CoreMenu(containerId, inventory, buf)));

    private ModMenus() {
    }
}
