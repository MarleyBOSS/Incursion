package com.marleyboss.incursion.registry;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.menu.CoreMenu;
import com.marleyboss.incursion.menu.CoreTableMenu;
import com.marleyboss.incursion.menu.StoryPickupMenu;
import com.marleyboss.incursion.menu.StoryTradeMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Incursion.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<CoreMenu>> CORE = MENUS.register("core",
            () -> IMenuTypeExtension.create((containerId, inventory, buf) -> new CoreMenu(containerId, inventory, buf)));

    public static final DeferredHolder<MenuType<?>, MenuType<CoreTableMenu>> CORE_TABLE = MENUS.register("core_table",
            () -> IMenuTypeExtension.create((containerId, inventory, buf) -> new CoreTableMenu(containerId, inventory)));

    public static final DeferredHolder<MenuType<?>, MenuType<StoryTradeMenu>> STORY_TRADE = MENUS.register("story_trade",
            () -> IMenuTypeExtension.create((containerId, inventory, buf) ->
                    StoryTradeMenu.fromBuffer(containerId, inventory, buf)));

    public static final DeferredHolder<MenuType<?>, MenuType<StoryPickupMenu>> STORY_PICKUP = MENUS.register("story_pickup",
            () -> IMenuTypeExtension.create((containerId, inventory, buf) ->
                    StoryPickupMenu.fromBuffer(containerId, inventory, buf)));

    private ModMenus() {
    }
}
