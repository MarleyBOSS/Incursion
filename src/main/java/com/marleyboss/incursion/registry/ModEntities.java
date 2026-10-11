package com.marleyboss.incursion.registry;

import com.marleyboss.incursion.Incursion;
import com.marleyboss.incursion.entity.CoreTargetEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Incursion.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<CoreTargetEntity>> CORE_TARGET =
            ENTITY_TYPES.register("core_target", () -> EntityType.Builder
                    .<CoreTargetEntity>of(CoreTargetEntity::new, MobCategory.MISC)
                    .sized(1.2F, 1.6F)
                    .eyeHeight(1.3F)
                    .noSummon()
                    .fireImmune()
                    .clientTrackingRange(8)
                    .updateInterval(20)
                    .build("core_target"));

    private ModEntities() {
    }

    /** Every LivingEntity type needs attributes. Registered on the mod event bus. */
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(CORE_TARGET.get(), LivingEntity.createLivingAttributes().build());
    }
}
