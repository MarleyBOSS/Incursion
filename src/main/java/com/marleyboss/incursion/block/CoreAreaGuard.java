package com.marleyboss.incursion.block;

import com.marleyboss.incursion.registry.ModBlocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The area round a grown Sarcophagus (see CoreEvolution.inArea) is kept free: the Core removes what gets in there (the
 * block shakes and blows up, see CoreBlockEntity). The blocks that belong to the Core may stand there.
 */
public final class CoreAreaGuard {
    private CoreAreaGuard() {
    }

    public static void register() {
        // nothing to listen to: the Core itself clears the area
    }

    /** Blocks that belong to the Core and may stand in the area. */
    public static boolean isExempt(BlockState state) {
        return state.getBlock() instanceof CoreBlock || state.is(ModBlocks.CORE_TABLE.get())
                || state.is(ModBlocks.LOOT_CHEST.get()) || state.is(ModBlocks.WARD.get())
                || state.is(ModBlocks.SIEGE_STONE.get()) || state.is(ModBlocks.SIEGE_LADDER.get());
    }
}
