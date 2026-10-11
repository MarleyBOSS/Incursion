package com.marleyboss.incursion.menu;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.block.CoreActions;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.registry.ModBlocks;
import com.marleyboss.incursion.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Slot-less menu behind the Core GUI. The numbers shown in the GUI are synced through {@link ContainerData};
 * the buttons use the vanilla "menu button click" packet.
 */
public class CoreMenu extends AbstractContainerMenu {
    public static final int BUTTON_ACTIVATE = 0;
    public static final int BUTTON_UPGRADE = 1;
    public static final int BUTTON_DEFUSE = 2;

    public static final int DATA_ACTIVE = 0;
    public static final int DATA_LEVEL = 1;
    public static final int DATA_MAX_LEVEL = 2;
    public static final int DATA_HEALTH = 3;
    public static final int DATA_MAX_HEALTH = 4;
    public static final int DATA_NEXT_COST = 5;
    public static final int DATA_UPGRADE_ITEMS_OWNED = 6;
    public static final int DATA_ACTIVATION_ITEMS_OWNED = 7;
    public static final int DATA_SIEGE_MOBS = 8;
    public static final int DATA_MAX_SIEGE_MOBS = 9;
    public static final int DATA_CORE_ZONE = 10;
    public static final int DATA_SIEGE_RADIUS = 11;
    public static final int DATA_DEFUSE_LEFT = 12;
    public static final int DATA_DEFUSE_TOTAL = 13;
    public static final int DATA_EXPLOSION_RADIUS = 14;
    public static final int DATA_DEFUSE_SECONDS = 15;
    public static final int DATA_COUNT = 16;

    private final ContainerData data;
    private final ContainerLevelAccess access;
    @Nullable
    private final CoreBlockEntity core;
    private final BlockPos pos;
    private final Item upgradeItem;
    private final Item activationItem;

    /** Client side: values arrive through the data slots. */
    public CoreMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.CORE.get(), containerId);
        this.pos = buf.readBlockPos();
        this.upgradeItem = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
        this.activationItem = BuiltInRegistries.ITEM.get(buf.readResourceLocation());
        this.core = null;
        this.access = ContainerLevelAccess.NULL;
        this.data = new SimpleContainerData(DATA_COUNT);
        addDataSlots(data);
    }

    /** Server side: reads live values from the block entity and the player's inventory. */
    public CoreMenu(int containerId, Inventory inventory, CoreBlockEntity core) {
        super(ModMenus.CORE.get(), containerId);
        this.core = core;
        this.pos = core.getBlockPos();
        this.upgradeItem = Config.upgradeItem();
        this.activationItem = Config.activationItem();
        this.access = ContainerLevelAccess.create(core.getLevel(), core.getBlockPos());
        Player player = inventory.player;
        this.data = new ContainerData() {
            @Override
            public int get(int index) {
                int level = core.getCoreLevel();
                int value = switch (index) {
                    case DATA_ACTIVE -> core.isActive() ? 1 : 0;
                    case DATA_LEVEL -> level;
                    case DATA_MAX_LEVEL -> Config.MAX_LEVEL.get();
                    case DATA_HEALTH -> (int) Math.ceil(core.getHealth());
                    case DATA_MAX_HEALTH -> core.getMaxHealth();
                    case DATA_NEXT_COST -> Config.upgradeCost(level + 1);
                    case DATA_UPGRADE_ITEMS_OWNED -> CoreActions.countItem(player, Config.upgradeItem());
                    case DATA_ACTIVATION_ITEMS_OWNED -> CoreActions.countItem(player, Config.activationItem());
                    case DATA_SIEGE_MOBS -> core.getSiegeMobCount();
                    case DATA_MAX_SIEGE_MOBS -> Config.maxSiegeMobs(Math.max(1, level));
                    case DATA_CORE_ZONE -> Config.coreZoneRadius(Math.max(1, level));
                    case DATA_SIEGE_RADIUS -> Config.siegeRadiusBlocks(Math.max(1, level));
                    case DATA_DEFUSE_LEFT -> core.getDefuseTicksLeft();
                    case DATA_DEFUSE_TOTAL -> core.getDefuseTicksTotal();
                    case DATA_EXPLOSION_RADIUS -> Config.craterRadius(Math.max(1, level));
                    case DATA_DEFUSE_SECONDS -> Config.defuseTicks(level) / 20;
                    default -> 0;
                };
                // Data slots are synced as 16-bit numbers.
                return Math.max(0, Math.min(value, Short.MAX_VALUE));
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
        addDataSlots(data);
    }

    public int get(int index) {
        return data.get(index);
    }

    public boolean isActive() {
        return data.get(DATA_ACTIVE) != 0;
    }

    public BlockPos getPos() {
        return pos;
    }

    public Item getUpgradeItem() {
        return upgradeItem;
    }

    public Item getActivationItem() {
        return activationItem;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (core == null || !(player.level() instanceof ServerLevel level)) {
            return false;
        }
        if (id == BUTTON_ACTIVATE) {
            CoreActions.tryActivate(level, pos, player);
            return true;
        }
        if (id == BUTTON_UPGRADE) {
            CoreActions.tryUpgrade(level, pos, player);
            return true;
        }
        if (id == BUTTON_DEFUSE) {
            core.toggleDefuse(level, player);
            return true;
        }
        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.CORE.get());
    }
}
