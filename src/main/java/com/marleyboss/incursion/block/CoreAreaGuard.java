package com.marleyboss.incursion.block;

import com.marleyboss.incursion.data.CoreWorldData;
import com.marleyboss.incursion.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Keeps the area of the grown Sarcophagus free: nothing can be placed there (the Core also clears strays, see CoreBlockEntity). */
public final class CoreAreaGuard {
    private CoreAreaGuard() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(CoreAreaGuard::onPlace);
        NeoForge.EVENT_BUS.addListener(CoreAreaGuard::onUse);
    }

    /** Blocks that belong to the Core and may stand in the area. */
    public static boolean isExempt(BlockState state) {
        return state.getBlock() instanceof CoreBlock || state.is(ModBlocks.CORE_TABLE.get())
                || state.is(ModBlocks.LOOT_CHEST.get()) || state.is(ModBlocks.WARD.get())
                || state.is(ModBlocks.SIEGE_STONE.get()) || state.is(ModBlocks.SIEGE_LADDER.get());
    }

    /** Is {@code pos} inside the protected area of the active Core? */
    public static boolean isProtected(LevelAccessor accessor, BlockPos pos) {
        if (!(accessor instanceof ServerLevel level)) {
            return false;
        }
        CoreWorldData data = CoreWorldData.get(level.getServer());
        BlockPos corePos = data.getPos();
        if (corePos == null || !level.dimension().equals(data.getDimension())) {
            return false;
        }
        if (Math.abs(pos.getX() - corePos.getX()) > CoreEvolution.MAX_AREA_RADIUS
                || Math.abs(pos.getZ() - corePos.getZ()) > CoreEvolution.MAX_AREA_RADIUS
                || pos.getY() < corePos.getY() - 1 || pos.getY() > corePos.getY() + 12) {
            return false; // far away: skip loading anything
        }
        if (!(level.getBlockEntity(corePos) instanceof CoreBlockEntity core) || !core.isActive()) {
            return false;
        }
        int stage = Math.max(core.getStage(), core.getBuiltStage());
        return CoreEvolution.inArea(stage, pos.getX() - corePos.getX(), pos.getY() - corePos.getY(),
                pos.getZ() - corePos.getZ());
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (isProtected(event.getLevel(), event.getPos()) && !isExempt(event.getPlacedBlock())) {
            event.setCanceled(true);
        }
    }

    private static void onUse(PlayerInteractEvent.RightClickBlock event) {
        Item item = event.getItemStack().getItem();
        if (!(item instanceof BlockItem) && !(item instanceof BucketItem)) {
            return;
        }
        BlockState clicked = event.getLevel().getBlockState(event.getPos());
        if (CoreEvolution.isStructure(clicked) || isExempt(clicked)) {
            return; // using the Core itself
        }
        if (item instanceof BlockItem block && isExempt(block.getBlock().defaultBlockState())) {
            return;
        }
        BlockPos target = event.getPos().relative(event.getFace());
        if (isProtected(event.getLevel(), target) || isProtected(event.getLevel(), event.getPos())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }
}
