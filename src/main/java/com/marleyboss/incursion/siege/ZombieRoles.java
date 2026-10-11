package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Siege zombies come in three roles, recognisable by the item in their hand:
 * <ul>
 *     <li>Warrior (sword): more damage against players.</li>
 *     <li>Miner (pickaxe): digs through walls quickly (see {@link Digging}).</li>
 *     <li>Breaker (axe): more damage against the Core.</li>
 * </ul>
 * The tool material (stone, iron, diamond, netherite) rises with the core level.
 * The role is stored as a scoreboard tag, so it survives saving.
 */
public final class ZombieRoles {
    public static final String WARRIOR_TAG = "incursion_warrior";
    public static final String MINER_TAG = "incursion_miner";
    public static final String BREAKER_TAG = "incursion_breaker";

    private static final Item[] SWORDS = {Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD};
    private static final Item[] PICKAXES = {Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE};
    private static final Item[] AXES = {Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE};

    private ZombieRoles() {
    }

    /** Gives a freshly spawned siege zombie its role and weapon. Other mobs are left alone. */
    public static void apply(Entity entity, int coreLevel, RandomSource random) {
        if (!Config.ZOMBIE_ROLES.get() || !(entity instanceof Zombie zombie)
                || zombie instanceof Drowned || zombie instanceof ZombifiedPiglin) {
            return;
        }
        int warrior = Config.WARRIOR_WEIGHT.get();
        int miner = Config.MINER_WEIGHT.get();
        int breaker = Config.BREAKER_WEIGHT.get();
        int total = warrior + miner + breaker;
        if (total <= 0) {
            return;
        }
        int roll = random.nextInt(total);
        int tier = tier(coreLevel);
        Item item;
        if (roll < warrior) {
            zombie.addTag(WARRIOR_TAG);
            item = SWORDS[tier];
        } else if (roll < warrior + miner) {
            zombie.addTag(MINER_TAG);
            item = PICKAXES[tier];
        } else {
            zombie.addTag(BREAKER_TAG);
            item = AXES[tier];
        }
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
        // Players should not farm netherite tools from the siege, and zombies keep their role tool.
        zombie.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        zombie.setCanPickUpLoot(false);
    }

    /** 0 = stone, 1 = iron, 2 = diamond, 3 = netherite. */
    private static int tier(int coreLevel) {
        if (coreLevel >= Config.NETHERITE_GEAR_FROM_LEVEL.get()) {
            return 3;
        }
        if (coreLevel >= Config.DIAMOND_GEAR_FROM_LEVEL.get()) {
            return 2;
        }
        if (coreLevel >= Config.IRON_GEAR_FROM_LEVEL.get()) {
            return 1;
        }
        return 0;
    }

    /** Damage multiplier when the attacker hits a player. */
    public static float playerDamageMultiplier(Entity attacker) {
        if (attacker.getTags().contains(WARRIOR_TAG)) {
            return Config.WARRIOR_PLAYER_DAMAGE.get().floatValue();
        }
        if (attacker.getTags().contains(BREAKER_TAG)) {
            return Config.BREAKER_PLAYER_DAMAGE.get().floatValue();
        }
        return 1.0F;
    }

    /** Damage multiplier when the attacker hits the Core. */
    public static float coreDamageMultiplier(Entity attacker) {
        if (attacker.getTags().contains(BREAKER_TAG)) {
            return Config.BREAKER_CORE_DAMAGE.get().floatValue();
        }
        return 1.0F;
    }
}
