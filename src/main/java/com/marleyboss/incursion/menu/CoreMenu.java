package com.marleyboss.incursion.menu;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.block.CoreActions;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.registry.ModBlocks;
import com.marleyboss.incursion.registry.ModMenus;
import com.marleyboss.incursion.siege.Rewards;
import com.marleyboss.incursion.siege.Shields;
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
    public static final int BUTTON_BORDERS = 3;
    public static final int BUTTON_AI_VIEW = 4;
    public static final int BUTTON_REPAIR_SHIELD = 5;
    public static final int BUTTON_WITHDRAW = 6;

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
    public static final int DATA_SHOW_BORDERS = 16;
    public static final int DATA_SHOW_AI = 17;
    public static final int DATA_SHIELDS_AVAILABLE = 18;
    public static final int DATA_SHIELDS_INTACT = 19;
    /** 0 = nothing broken, 1 = repair possible, 2 = Core level too low. */
    public static final int DATA_REPAIR_STATE = 20;
    public static final int DATA_REPAIR_LEVEL = 21;
    public static final int DATA_REPAIR_COST = 22;
    public static final int DATA_FIRST_SHIELD_LEVEL = 23;
    public static final int DATA_SHARDS = 24;
    public static final int DATA_NIGHTS = 25;
    public static final int DATA_KILLS = 26;
    public static final int DATA_SHARDS_PER_NIGHT = 27;
    public static final int DATA_COUNT = 28;

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
                    case DATA_SHOW_BORDERS -> core.isShowBorders() ? 1 : 0;
                    case DATA_SHOW_AI -> core.isShowAi() ? 1 : 0;
                    case DATA_SHIELDS_AVAILABLE -> Shields.countAvailable(core);
                    case DATA_SHIELDS_INTACT -> Shields.countIntact(core);
                    case DATA_REPAIR_STATE -> Shields.repairState(core);
                    case DATA_REPAIR_LEVEL -> Shields.nextRepairLevel(core);
                    case DATA_REPAIR_COST -> Config.SHIELD_REPAIR_LEVEL_COST.get();
                    case DATA_FIRST_SHIELD_LEVEL -> Config.SHIELDS_ENABLED.get() ? firstShieldLevel() : 0;
                    case DATA_SHARDS -> core.getShards();
                    case DATA_NIGHTS -> core.getDefendedNights();
                    case DATA_KILLS -> core.getKilledMobs();
                    case DATA_SHARDS_PER_NIGHT -> Config.SHARDS_ENABLED.get() ? Config.shardsPerNight(Math.max(1, level)) : 0;
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

    /** Lowest Core level at which any shield exists. */
    private static int firstShieldLevel() {
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i < Config.SHIELD_COUNT; i++) {
            lowest = Math.min(lowest, Config.shieldMinLevel(i));
        }
        return lowest;
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
        if (id == BUTTON_BORDERS) {
            core.toggleShowBorders();
            return true;
        }
        if (id == BUTTON_AI_VIEW) {
            core.toggleShowAi();
            return true;
        }
        if (id == BUTTON_REPAIR_SHIELD) {
            Shields.tryRepair(level, pos, core, player);
            return true;
        }
        if (id == BUTTON_WITHDRAW) {
            Rewards.withdraw(player, core);
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
