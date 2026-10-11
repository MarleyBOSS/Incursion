package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.Incursion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Block breaking by siege mobs. Speed depends on block hardness and on the mob: a fitting tool
 * (pickaxe on stone) works like a player's tool, otherwise the mob's attack damage decides.
 * Several mobs digging at the same block add up their progress; cracks are visible.
 */
public final class Digging {
    /** Scoreboard tag on creepers that ignited to blow a hole into a wall. */
    public static final String BREACH_TAG = "incursion_breach";
    /** Blocks mobs can never break. */
    public static final TagKey<Block> SIEGE_PROOF = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "siege_proof"));

    private static final class BlockProgress {
        final BlockPos pos;
        final int crackId;
        float progress;
        long lastTouched;
        int lastStage = -1;

        BlockProgress(BlockPos pos) {
            this.pos = pos;
            // Negative ids never clash with entity ids (the client tracks cracks per id).
            this.crackId = -1 - (pos.hashCode() & 0x3FFFFFFF);
        }
    }

    private static final Map<Long, BlockProgress> BLOCKS = new HashMap<>();
    private static long lastCleanup = 0;

    private Digging() {
    }

    /** One digging step of {@code ticks} ticks. Returns true when the block broke. */
    public static boolean dig(ServerLevel level, Mob mob, BlockPos pos, int ticks, long now) {
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        AttributeInstance damage = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        double attack = damage != null ? damage.getValue() : 1.0;
        float perTick = 1.0F / digTicks(mob.getMainHandItem(), attack, state, hardness);

        BlockProgress block = BLOCKS.computeIfAbsent(pos.asLong(), key -> new BlockProgress(pos.immutable()));
        block.progress += perTick * ticks;
        block.lastTouched = now;

        mob.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.HOSTILE, 0.6F, 0.75F);

        if (block.progress >= 1.0F) {
            BLOCKS.remove(pos.asLong());
            level.destroyBlockProgress(block.crackId, pos, -1);
            level.destroyBlock(pos, Config.DUG_BLOCKS_DROP.get(), mob);
            return true;
        }
        int stage = Math.min(9, (int) (block.progress * 10.0F));
        if (stage != block.lastStage) {
            block.lastStage = stage;
            level.destroyBlockProgress(block.crackId, pos, stage);
        }
        return false;
    }

    /** Ticks a mob with this tool and attack damage needs to break the block. */
    public static float digTicks(ItemStack tool, double attack, BlockState state, float hardness) {
        if (hardness <= 0) {
            return 1.0F;
        }
        float speed = Math.max(0.01F, digSpeed(tool, attack, state));
        boolean harvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        return hardness * (harvest ? 30.0F : 100.0F) / speed;
    }

    /** Effective "tool speed" of the mob for this block, like a player's mining speed. */
    private static float digSpeed(ItemStack tool, double attack, BlockState state) {
        float toolSpeed = tool.isEmpty() ? 1.0F : tool.getDestroySpeed(state);
        float withTool = toolSpeed > 1.0F ? toolSpeed * Config.TOOL_DIG_SPEED_MULTIPLIER.get().floatValue() : 0.0F;
        float withHands = (float) (attack / 3.0 * Config.MELEE_DIG_SPEED_MULTIPLIER.get());
        return Math.max(withTool, withHands);
    }

    /** Forgets dig progress on blocks nobody worked on for 3 seconds (and removes their cracks). */
    public static void cleanup(ServerLevel level, long now) {
        if (now - lastCleanup < 20 && now >= lastCleanup) {
            return;
        }
        lastCleanup = now;
        Iterator<BlockProgress> iterator = BLOCKS.values().iterator();
        while (iterator.hasNext()) {
            BlockProgress block = iterator.next();
            if (now - block.lastTouched > 60 || now < block.lastTouched) {
                level.destroyBlockProgress(block.crackId, block.pos, -1);
                iterator.remove();
            }
        }
    }

    public static void clear() {
        BLOCKS.clear();
        lastCleanup = 0;
    }
}
