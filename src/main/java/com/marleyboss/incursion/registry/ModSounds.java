package com.marleyboss.incursion.registry;

import com.marleyboss.incursion.Incursion;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Own sounds (synthesized by tools/gen_table_sounds.py, listed in assets/incursion/sounds.json). */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, Incursion.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> TABLE_LOOP = register("table_loop");
    public static final DeferredHolder<SoundEvent, SoundEvent> TABLE_START = register("table_start");
    public static final DeferredHolder<SoundEvent, SoundEvent> TABLE_FINISH = register("table_finish");
    public static final DeferredHolder<SoundEvent, SoundEvent> TABLE_ZAP = register("table_zap");
    public static final DeferredHolder<SoundEvent, SoundEvent> TABLE_AMBIENT = register("table_ambient");

    private ModSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(Incursion.MODID, name)));
    }
}
