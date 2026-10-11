package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Classes of siege mobs. The role is stored as a scoreboard tag, so it survives saving.
 * <ul>
 *     <li>Zombies: warrior (sword, hunts players, never digs), miner (pickaxe, digs fast),
 *     breaker (axe, hits the Core hard), builder (holds a block, builds stairs and bridges).</li>
 *     <li>Skeletons: blast archer (red leather armour, explosive arrows), fire archer (red leather cap,
 *     burning arrows, ignites TNT), archer (chainmail).</li>
 *     <li>Endermen: sapper (carries TNT and places it next to the Core).</li>
 * </ul>
 * New mobs get the role that is most missing compared to the configured weights, and roles the siege
 * urgently needs (no digger / builder / fire archer left) come first.
 */
public final class MobRoles {
    public enum Role {
        NONE(""),
        WARRIOR("incursion_warrior"),
        MINER("incursion_miner"),
        BREAKER("incursion_breaker"),
        BUILDER("incursion_builder"),
        BLAST_ARCHER("incursion_blast_archer"),
        FIRE_ARCHER("incursion_fire_archer"),
        ARCHER("incursion_archer"),
        SAPPER("incursion_sapper");

        public final String tag;

        Role(String tag) {
            this.tag = tag;
        }

        public boolean isArcher() {
            return this == BLAST_ARCHER || this == FIRE_ARCHER || this == ARCHER;
        }

        /** Workers ignore players and only care about reaching the Core. */
        public boolean isWorker() {
            return this == MINER || this == BREAKER || this == BUILDER || this == SAPPER;
        }
    }

    private static final Role[] ROLES = Role.values();
    private static final Role[] ZOMBIE_ROLES = {Role.WARRIOR, Role.MINER, Role.BREAKER, Role.BUILDER};
    private static final Role[] ARCHER_ROLES = {Role.BLAST_ARCHER, Role.FIRE_ARCHER, Role.ARCHER};

    private static final Item[] SWORDS = {Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD};
    private static final Item[] PICKAXES = {Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE};
    private static final Item[] AXES = {Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE};
    private static final int RED = 0xB02E26;

    /** Living siege mobs per role, counted once per second by the SiegeManager. */
    private static final int[] COUNTS = new int[ROLES.length];

    private MobRoles() {
    }

    public static Role of(Entity entity) {
        for (Role role : ROLES) {
            if (role != Role.NONE && entity.getTags().contains(role.tag)) {
                return role;
            }
        }
        return Role.NONE;
    }

    // ------------------------------------------------------------------
    // Counting
    // ------------------------------------------------------------------

    public static void resetCounts() {
        java.util.Arrays.fill(COUNTS, 0);
    }

    public static void count(Mob mob) {
        COUNTS[of(mob).ordinal()]++;
    }

    public static int count(Role role) {
        return COUNTS[role.ordinal()];
    }

    // ------------------------------------------------------------------
    // Assignment
    // ------------------------------------------------------------------

    /** True if a mob of this type would become a zombie role (used to pick the type for urgent needs). */
    public static boolean isZombieRoleType(Mob mob) {
        return mob instanceof Zombie && !(mob instanceof Drowned) && !(mob instanceof ZombifiedPiglin);
    }

    /** Gives a freshly spawned siege mob its role and equipment. Mobs without classes are left alone. */
    public static void apply(Mob mob, int coreLevel, RandomSource random, long now) {
        Role role = Role.NONE;
        if (isZombieRoleType(mob) && Config.ZOMBIE_ROLES.get()) {
            role = pickZombieRole(random, now);
            if (role != Role.NONE) {
                equipZombie((Zombie) mob, role, coreLevel);
            }
        } else if (mob instanceof AbstractSkeleton skeleton && Config.SKELETON_CLASSES.get()
                && skeleton.getMainHandItem().getItem() instanceof BowItem) {
            role = pickArcherRole(random, now);
            if (role != Role.NONE) {
                equipArcher(skeleton, role);
            }
        } else if (mob instanceof EnderMan enderman && random.nextDouble() < Config.SAPPER_CHANCE.get()) {
            role = Role.SAPPER;
            enderman.setCarriedBlock(Blocks.TNT.defaultBlockState());
        }
        if (role != Role.NONE) {
            mob.addTag(role.tag);
            COUNTS[role.ordinal()]++;
            prepareGoals(mob);
        }
    }

    /** Sappers must not drop their TNT somewhere random (vanilla endermen put blocks down by chance). */
    public static void prepareGoals(Mob mob) {
        if (mob instanceof EnderMan && mob.getTags().contains(Role.SAPPER.tag)) {
            List<Goal> remove = new ArrayList<>();
            for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
                if (wrapped.getGoal().getClass().getSimpleName().contains("LeaveBlock")) {
                    remove.add(wrapped.getGoal());
                }
            }
            remove.forEach(mob.goalSelector::removeGoal);
        }
    }

    private static Role pickZombieRole(RandomSource random, long now) {
        int[] weights = {Config.WARRIOR_WEIGHT.get(), Config.MINER_WEIGHT.get(), Config.BREAKER_WEIGHT.get(),
                Config.BUILDING_ENABLED.get() ? Config.BUILDER_WEIGHT.get() : 0};
        // Urgent needs first: a wall nobody can dig through, a gap nobody can bridge.
        if (weights[3] > 0 && count(Role.BUILDER) == 0 && SiegeProgress.isNeeded(SiegeProgress.Need.BUILDER, now)) {
            return Role.BUILDER;
        }
        if (count(Role.MINER) + count(Role.BREAKER) == 0 && SiegeProgress.isNeeded(SiegeProgress.Need.DIGGER, now)) {
            if (weights[1] > 0) {
                return Role.MINER;
            }
            if (weights[2] > 0) {
                return Role.BREAKER;
            }
        }
        return balanced(ZOMBIE_ROLES, weights, random);
    }

    private static Role pickArcherRole(RandomSource random, long now) {
        int[] weights = {Config.BLAST_ARCHER_WEIGHT.get(), Config.FIRE_ARCHER_WEIGHT.get(), Config.ARCHER_WEIGHT.get()};
        if (weights[1] > 0 && count(Role.FIRE_ARCHER) == 0 && SiegeProgress.isNeeded(SiegeProgress.Need.FIRE_ARCHER, now)) {
            return Role.FIRE_ARCHER;
        }
        return balanced(ARCHER_ROLES, weights, random);
    }

    /** Picks the role that is most under-represented compared to its weight. */
    private static Role balanced(Role[] roles, int[] weights, RandomSource random) {
        int totalWeight = 0;
        int alive = 0;
        for (int i = 0; i < roles.length; i++) {
            totalWeight += weights[i];
            alive += count(roles[i]);
        }
        if (totalWeight <= 0) {
            return Role.NONE;
        }
        Role best = Role.NONE;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < roles.length; i++) {
            if (weights[i] <= 0) {
                continue;
            }
            double wanted = (double) weights[i] * (alive + 1) / totalWeight;
            double score = wanted - count(roles[i]) + random.nextDouble() * 0.5;
            if (score > bestScore) {
                bestScore = score;
                best = roles[i];
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Equipment
    // ------------------------------------------------------------------

    private static void equipZombie(Zombie zombie, Role role, int coreLevel) {
        int tier = tier(coreLevel);
        ItemStack stack = switch (role) {
            case WARRIOR -> new ItemStack(SWORDS[tier]);
            case MINER -> new ItemStack(PICKAXES[tier]);
            case BREAKER -> new ItemStack(AXES[tier]);
            case BUILDER -> {
                Item block = Config.builderBlock().getBlock().asItem();
                yield new ItemStack(block == Items.AIR ? Items.COBBLESTONE : block);
            }
            default -> ItemStack.EMPTY;
        };
        zombie.setItemSlot(EquipmentSlot.MAINHAND, stack);
        // Players should not farm netherite tools from the siege, and zombies keep their role item.
        zombie.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        zombie.setCanPickUpLoot(false);
    }

    private static void equipArcher(AbstractSkeleton skeleton, Role role) {
        ItemStack head = ItemStack.EMPTY;
        ItemStack chest = ItemStack.EMPTY;
        ItemStack legs = ItemStack.EMPTY;
        ItemStack feet = ItemStack.EMPTY;
        switch (role) {
            case BLAST_ARCHER -> {
                head = red(Items.LEATHER_HELMET);
                chest = red(Items.LEATHER_CHESTPLATE);
                legs = red(Items.LEATHER_LEGGINGS);
                feet = red(Items.LEATHER_BOOTS);
            }
            case FIRE_ARCHER -> head = red(Items.LEATHER_HELMET);
            default -> chest = new ItemStack(Items.CHAINMAIL_CHESTPLATE);
        }
        setArmor(skeleton, EquipmentSlot.HEAD, head);
        setArmor(skeleton, EquipmentSlot.CHEST, chest);
        setArmor(skeleton, EquipmentSlot.LEGS, legs);
        setArmor(skeleton, EquipmentSlot.FEET, feet);
        skeleton.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        skeleton.setCanPickUpLoot(false);
    }

    private static void setArmor(Mob mob, EquipmentSlot slot, ItemStack stack) {
        mob.setItemSlot(slot, stack);
        mob.setDropChance(slot, 0.0F);
    }

    private static ItemStack red(Item item) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(RED, false));
        return stack;
    }

    /** 0 = stone, 1 = iron, 2 = diamond, 3 = netherite. */
    public static int tier(int coreLevel) {
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

    public static ItemStack referencePickaxe(int coreLevel) {
        return new ItemStack(PICKAXES[tier(coreLevel)]);
    }

    // ------------------------------------------------------------------
    // Damage multipliers
    // ------------------------------------------------------------------

    /** Damage multiplier when the attacker hits a player. */
    public static float playerDamageMultiplier(Entity attacker) {
        Role role = of(attacker);
        if (role == Role.WARRIOR) {
            return Config.WARRIOR_PLAYER_DAMAGE.get().floatValue();
        }
        if (role == Role.BREAKER) {
            return Config.BREAKER_PLAYER_DAMAGE.get().floatValue();
        }
        return 1.0F;
    }

    /** Damage multiplier when the attacker hits the Core. */
    public static float coreDamageMultiplier(Entity attacker) {
        if (of(attacker) == Role.BREAKER) {
            return Config.BREAKER_CORE_DAMAGE.get().floatValue();
        }
        return 1.0F;
    }
}
