package com.marleyboss.incursion.registry;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlock;
import com.marleyboss.incursion.block.CorePartBlock;
import com.marleyboss.incursion.block.CoreTopBlock;
import com.marleyboss.incursion.block.LootChestBlock;
import com.marleyboss.incursion.block.WardBlock;
import com.marleyboss.incursion.table.CoreTableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Incursion.MODID);

    public static final DeferredBlock<CoreBlock> CORE = BLOCKS.register("core", () -> new CoreBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(5.0F, 1200.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.NETHERITE_BLOCK)
                    .lightLevel(state -> state.getValue(CoreBlock.ACTIVE) ? 12 : 3)
                    .noOcclusion() // the active Core is open above its bottom half, neighbours must still draw their faces
                    .pushReaction(PushReaction.BLOCK)));

    /** The invisible upper half of an active Core (see CoreTopBlock): unbreakable, immune to explosions, no drops. */
    public static final DeferredBlock<CoreTopBlock> CORE_TOP = BLOCKS.register("core_top", () -> new CoreTopBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(-1.0F, 3600000.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .lightLevel(state -> 12)
                    .noOcclusion()
                    .noLootTable()
                    .pushReaction(PushReaction.BLOCK)));

    /** Invisible space of the grown Sarcophagus. */
    public static final DeferredBlock<CorePartBlock> CORE_PART = BLOCKS.register("core_part", () -> new CorePartBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(-1.0F, 3600000.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .lightLevel(state -> 12)
                    .noOcclusion()
                    .noLootTable()
                    .pushReaction(PushReaction.BLOCK)));

    /** The evolution of the Core: plaza floor, gold lines and pillars, crystals (unbreakable, no drops). */
    public static final DeferredBlock<Block> CORE_FLOOR = BLOCKS.registerSimpleBlock("core_floor", structure(MapColor.COLOR_BLACK, 0));
    public static final DeferredBlock<Block> CORE_GOLD = BLOCKS.registerSimpleBlock("core_gold", structure(MapColor.GOLD, 6));
    public static final DeferredBlock<com.marleyboss.incursion.block.CoreCrystalBlock> CORE_CRYSTAL = BLOCKS.register("core_crystal",
            () -> new com.marleyboss.incursion.block.CoreCrystalBlock(structure(MapColor.COLOR_RED, 11).noOcclusion()));

    private static BlockBehaviour.Properties structure(MapColor color, int light) {
        return BlockBehaviour.Properties.of()
                .mapColor(color)
                .strength(-1.0F, 3600000.0F)
                .sound(SoundType.NETHERITE_BLOCK)
                .lightLevel(state -> light)
                .noLootTable()
                .pushReaction(PushReaction.BLOCK);
    }

    public static final DeferredBlock<WardBlock> WARD = BLOCKS.register("ward", () -> new WardBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE)
                    .lightLevel(state -> 7)
                    .noOcclusion()));

    /** Collects the drops of siege mobs near the active Core (see Rewards). */
    public static final DeferredBlock<LootChestBlock> LOOT_CHEST = BLOCKS.register("loot_chest", () -> new LootChestBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(3.0F, 1200.0F)
                    .sound(SoundType.WOOD)));

    /** The insignia a fallen general leaves behind (see GeneralRelicBlock): mined by hand, immune to explosions. */
    public static final DeferredBlock<com.marleyboss.incursion.block.GeneralRelicBlock> GENERAL_RELIC = BLOCKS.register(
            "general_relic", () -> new com.marleyboss.incursion.block.GeneralRelicBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(1.5F, 3600000.0F)
                            .sound(SoundType.AMETHYST)
                            .lightLevel(state -> 10)
                            .noOcclusion()
                            .noLootTable()
                            .pushReaction(PushReaction.BLOCK)));

    /** The Core Table: enchantments above the vanilla maximum (needs the active Core, pays with its shards). */
    public static final DeferredBlock<CoreTableBlock> CORE_TABLE = BLOCKS.register("core_table", () -> new CoreTableBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(5.0F, 1200.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE)
                    .noOcclusion()
                    .lightLevel(state -> state.getValue(CoreTableBlock.JOB) == 1 ? 13 : 7)));

    /**
     * Placed by builder zombies (stairs and bridges). Explosions cannot break it, so creepers and blast arrows
     * do not destroy the siege's own stairs. Drops nothing, so the siege is no free stone farm.
     */
    public static final DeferredBlock<Block> SIEGE_STONE = BLOCKS.registerSimpleBlock("siege_stone",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(1.5F, 1200.0F)
                    .sound(SoundType.STONE)
                    .noLootTable());

    /**
     * Placed by builder zombies so the whole siege can climb straight up. No collision (mobs climb inside it,
     * like inside a ladder; it is in the minecraft:climbable tag), immune to explosions, drops nothing.
     */
    public static final DeferredBlock<Block> SIEGE_LADDER = BLOCKS.registerSimpleBlock("siege_ladder",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(0.4F, 1200.0F)
                    .sound(SoundType.LADDER)
                    .noCollission()
                    .noOcclusion()
                    .pushReaction(PushReaction.DESTROY)
                    .noLootTable());

    private ModBlocks() {
    }
}
