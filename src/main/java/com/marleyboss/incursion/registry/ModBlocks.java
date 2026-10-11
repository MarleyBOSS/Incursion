package com.marleyboss.incursion.registry;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlock;
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

    private ModBlocks() {
    }
}
