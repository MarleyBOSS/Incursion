package com.marleyboss.incursion.registry;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlock;
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
                    .pushReaction(PushReaction.BLOCK)));

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

    /** The Core Table: enchantments above the vanilla maximum (needs the active Core, pays with its shards). */
    public static final DeferredBlock<CoreTableBlock> CORE_TABLE = BLOCKS.register("core_table", () -> new CoreTableBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(5.0F, 1200.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE)
                    .lightLevel(state -> 7)));

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
