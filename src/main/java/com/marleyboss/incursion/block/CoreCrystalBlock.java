package com.marleyboss.incursion.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/** The space of an energy crystal on top of a pillar; the crystal itself is drawn by the Core's renderer. */
public class CoreCrystalBlock extends Block {
    public CoreCrystalBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}
