package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Siege mobs swim like a decent swimmer instead of like a vanilla zombie (which swims at about half a block per
 * second, a quarter of its walking speed, and is swept away by every current). They get the vanilla attribute
 * "water movement efficiency" (the effect of Depth Strider): the higher it is, the closer their swimming speed
 * comes to their walking speed, and the better they swim against a current. No special physics, the game does
 * the rest. Set by {@code swimEfficiencyPercent}; flowing water still slows them down clearly.
 */
final class SiegeCurrents {
    private static final ResourceLocation SWIM_ID = ResourceLocation.fromNamespaceAndPath("incursion", "siege_swimmer");

    private SiegeCurrents() {
    }

    /**
     * Makes sure a siege mob has the swimming bonus from the config (also mobs from before a config change or an
     * update). Cheap: one modifier lookup.
     */
    static void ensureSwimmer(Mob mob) {
        AttributeInstance instance = mob.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (instance == null) {
            return;
        }
        double wanted = Config.SWIM_EFFICIENCY_PERCENT.get() / 100.0;
        AttributeModifier current = instance.getModifier(SWIM_ID);
        if (current != null && Math.abs(current.amount() - wanted) < 1.0E-6) {
            return;
        }
        if (current != null) {
            instance.removeModifier(SWIM_ID);
        }
        if (wanted > 0.0) {
            instance.addPermanentModifier(new AttributeModifier(SWIM_ID, wanted, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /**
     * Before 0.7.7 siege mobs got infinite, invisible fire resistance (so lava could not hurt them). Mobs that
     * still carry it from an older save lose it.
     */
    static void dropOldFireResistance(Mob mob) {
        MobEffectInstance effect = mob.getEffect(MobEffects.FIRE_RESISTANCE);
        if (effect != null && effect.isInfiniteDuration() && !effect.isVisible()
                && mob.getTags().contains(SiegeManager.SIEGE_TAG)) {
            mob.removeEffect(MobEffects.FIRE_RESISTANCE);
        }
    }
}
