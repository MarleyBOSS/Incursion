package com.marleyboss.incursion.registry;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.block.LootChestBlockEntity;
import com.marleyboss.incursion.block.WardBlockEntity;
import com.marleyboss.incursion.table.CoreTableBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Incursion.MODID);

    @SuppressWarnings("DataFlowIssue") // the data fixer type may be null for mod block entities
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CoreBlockEntity>> CORE =
            BLOCK_ENTITY_TYPES.register("core", () -> BlockEntityType.Builder
                    .of(CoreBlockEntity::new, ModBlocks.CORE.get())
                    .build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WardBlockEntity>> WARD =
            BLOCK_ENTITY_TYPES.register("ward", () -> BlockEntityType.Builder
                    .of(WardBlockEntity::new, ModBlocks.WARD.get())
                    .build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LootChestBlockEntity>> LOOT_CHEST =
            BLOCK_ENTITY_TYPES.register("loot_chest", () -> BlockEntityType.Builder
                    .of(LootChestBlockEntity::new, ModBlocks.LOOT_CHEST.get())
                    .build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CoreTableBlockEntity>> CORE_TABLE =
            BLOCK_ENTITY_TYPES.register("core_table", () -> BlockEntityType.Builder
                    .of(CoreTableBlockEntity::new, ModBlocks.CORE_TABLE.get())
                    .build(null));

    private ModBlockEntities() {
    }
}
