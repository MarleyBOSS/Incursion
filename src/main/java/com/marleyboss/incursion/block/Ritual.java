package com.marleyboss.incursion.block;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Creating the Core. The Sarcophagus (the Core block while it is inactive) takes three things in any order: the Core
 * Rune, a stack of gunpowder and a stack of blaze powder (right-click with the item). Each shows up as its own
 * stage of the block (block state flags {@link CoreBlock#RUNE}, {@link CoreBlock#GUNPOWDER}, {@link CoreBlock#BLAZE}).
 * When all three are in, a Mythic Potion thrown onto the block activates the Core (see {@code activateByRitual}).
 */
public final class Ritual {
    private Ritual() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(Ritual::onImpact);
    }

    public static boolean isReady(BlockState state) {
        return state.getValue(CoreBlock.RUNE) && state.getValue(CoreBlock.GUNPOWDER) && state.getValue(CoreBlock.BLAZE);
    }

    // ------------------------------------------------------------------
    // Laying things in
    // ------------------------------------------------------------------

    /** Handles a right-click with a ritual item on an inactive Sarcophagus; null = the item is not a ritual item. */
    @Nullable
    static ItemInteractionResult tryInsert(Level level, BlockPos pos, BlockState state, Player player, ItemStack stack) {
        boolean rune = stack.is(ModItems.CORE_RUNE.get());
        boolean gunpowder = stack.is(Items.GUNPOWDER);
        boolean blaze = stack.is(Items.BLAZE_POWDER);
        if (!rune && !gunpowder && !blaze) {
            return null;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof CoreBlockEntity core)) {
            return ItemInteractionResult.CONSUME;
        }
        boolean creative = player.getAbilities().instabuild;
        BlockState next = state;
        if (rune) {
            if (state.getValue(CoreBlock.RUNE)) {
                player.displayClientMessage(Component.translatable("message.incursion.ritual.full")
                        .withStyle(ChatFormatting.YELLOW), true);
                return ItemInteractionResult.CONSUME;
            }
            if (!creative) {
                stack.shrink(1);
            }
            next = state.setValue(CoreBlock.RUNE, true);
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.2F, 0.7F);
        } else {
            boolean isGun = gunpowder;
            if (state.getValue(isGun ? CoreBlock.GUNPOWDER : CoreBlock.BLAZE)) {
                player.displayClientMessage(Component.translatable("message.incursion.ritual.full")
                        .withStyle(ChatFormatting.YELLOW), true);
                return ItemInteractionResult.CONSUME;
            }
            int need = Config.SARCOPHAGUS_POWDER.get();
            int have = isGun ? core.getGunpowder() : core.getBlazePowder();
            int add = creative ? need - have : Math.min(stack.getCount(), need - have);
            if (add <= 0) {
                return ItemInteractionResult.CONSUME;
            }
            if (!creative) {
                stack.shrink(add);
            }
            have += add;
            if (isGun) {
                core.setGunpowder(have);
            } else {
                core.setBlazePowder(have);
            }
            if (have >= need) {
                next = state.setValue(isGun ? CoreBlock.GUNPOWDER : CoreBlock.BLAZE, true);
            }
            level.playSound(null, pos, SoundEvents.SAND_PLACE, SoundSource.BLOCKS, 1.0F, isGun ? 0.8F : 1.2F);
        }
        if (next != state) {
            level.setBlock(pos, next, Block.UPDATE_ALL);
        }
        player.displayClientMessage(statusMessage(next, core), true);
        return ItemInteractionResult.CONSUME;
    }

    /** Right-click on an inactive Sarcophagus with an empty hand: shows what is still missing. */
    static InteractionResult showStatus(Level level, BlockState state, Player player, CoreBlockEntity core) {
        if (!level.isClientSide) {
            player.displayClientMessage(statusMessage(state, core), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static Component statusMessage(BlockState state, CoreBlockEntity core) {
        if (isReady(state)) {
            return Component.translatable("message.incursion.ritual.ready").withStyle(ChatFormatting.GOLD);
        }
        int need = Config.SARCOPHAGUS_POWDER.get();
        return Component.translatable("message.incursion.ritual.status",
                mark(state.getValue(CoreBlock.RUNE)),
                state.getValue(CoreBlock.GUNPOWDER) ? mark(true) : core.getGunpowder() + "/" + need,
                state.getValue(CoreBlock.BLAZE) ? mark(true) : core.getBlazePowder() + "/" + need)
                .withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    private static String mark(boolean done) {
        return done ? "✔" : "✘";
    }

    /** What is still missing, as text for the player whose potion hit an unfinished Sarcophagus. */
    private static Component missing(BlockState state) {
        MutableComponent text = Component.empty();
        boolean first = true;
        if (!state.getValue(CoreBlock.RUNE)) {
            text.append(ModItems.CORE_RUNE.get().getDescription());
            first = false;
        }
        if (!state.getValue(CoreBlock.GUNPOWDER)) {
            text.append(first ? Component.empty() : Component.literal(", ")).append(Items.GUNPOWDER.getDescription());
            first = false;
        }
        if (!state.getValue(CoreBlock.BLAZE)) {
            text.append(first ? Component.empty() : Component.literal(", ")).append(Items.BLAZE_POWDER.getDescription());
        }
        return text;
    }

    /** An inactive Sarcophagus that is picked up gives back everything that was laid into it. */
    static void dropContents(ServerLevel level, BlockPos pos, BlockState state, CoreBlockEntity core) {
        if (state.getValue(CoreBlock.RUNE)) {
            Block.popResource(level, pos, new ItemStack(ModItems.CORE_RUNE.get()));
        }
        popStacks(level, pos, Items.GUNPOWDER, core.getGunpowder());
        popStacks(level, pos, Items.BLAZE_POWDER, core.getBlazePowder());
    }

    private static void popStacks(ServerLevel level, BlockPos pos, net.minecraft.world.item.Item item, int count) {
        while (count > 0) {
            int part = Math.min(count, 64);
            Block.popResource(level, pos, new ItemStack(item, part));
            count -= part;
        }
    }

    // ------------------------------------------------------------------
    // The Mythic Potion lands
    // ------------------------------------------------------------------

    private static void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownPotion potion) || !(potion.level() instanceof ServerLevel level)) {
            return;
        }
        if (!potion.getItem().is(ModItems.MYTHIC_POTION.get())) {
            return;
        }
        HitResult hit = event.getRayTraceResult();
        Vec3 at = hit.getLocation();
        Entity owner = potion.getOwner();
        ServerPlayer thrower = owner instanceof ServerPlayer sp ? sp : null;
        BlockPos corePos = findSarcophagus(level, hit, at);
        if (corePos != null) {
            BlockState state = level.getBlockState(corePos);
            if (state.getValue(CoreBlock.ACTIVE)) {
                refund(level, thrower, at);
                if (thrower != null) {
                    thrower.displayClientMessage(Component.translatable("message.incursion.ritual.already_active")
                            .withStyle(ChatFormatting.YELLOW), true);
                }
            } else if (!isReady(state)) {
                refund(level, thrower, at);
                if (thrower != null) {
                    thrower.displayClientMessage(Component.translatable("message.incursion.ritual.not_ready", missing(state))
                            .withStyle(ChatFormatting.YELLOW), true);
                }
            } else if (!CoreActions.activateByRitual(level, corePos, thrower)) {
                refund(level, thrower, at); // e.g. another Core is already active in this world
            }
            return;
        }
        burn(level, potion, owner, hit, at);
    }

    private static BlockPos findSarcophagus(Level level, HitResult hit, Vec3 at) {
        if (hit instanceof BlockHitResult blockHit
                && level.getBlockState(blockHit.getBlockPos()).getBlock() instanceof CoreBlock) {
            return blockHit.getBlockPos();
        }
        BlockPos center = BlockPos.containing(at);
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            if (level.getBlockState(p).getBlock() instanceof CoreBlock) {
                return p.immutable();
            }
        }
        return null;
    }

    /** A potion that did not do its job is not lost. */
    private static void refund(ServerLevel level, @Nullable ServerPlayer player, Vec3 at) {
        if (player != null && player.getAbilities().instabuild) {
            return; // creative players do not use it up
        }
        ItemStack back = new ItemStack(ModItems.MYTHIC_POTION.get());
        if (player != null) {
            if (!player.getInventory().add(back)) {
                player.drop(back, false);
            }
        } else {
            Block.popResource(level, BlockPos.containing(at), back);
        }
    }

    /** Placeholder values (balancing later): a direct hit hurts a lot, everything nearby burns for 10 seconds. */
    private static void burn(ServerLevel level, ThrownPotion potion, @Nullable Entity owner, HitResult hit, Vec3 at) {
        DamageSource source = level.damageSources().indirectMagic(potion, owner);
        AABB area = new AABB(at, at).inflate(4.0, 2.0, 4.0);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, area)) {
            double distSqr = target.distanceToSqr(at);
            if (distSqr > 16.0) {
                continue;
            }
            boolean direct = hit instanceof EntityHitResult entityHit && entityHit.getEntity() == target;
            float damage = direct ? 14.0F : (float) Math.max(2.0, 8.0 * (1.0 - Math.sqrt(distSqr) / 4.0));
            target.hurt(source, damage);
            target.igniteForSeconds(10.0F);
        }
    }
}
