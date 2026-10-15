package com.marleyboss.incursion.siege;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.block.CoreBlockEntity;
import com.marleyboss.incursion.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

import java.util.List;

/**
 * The Core's shields: up to three particle domes (index 0 = outermost, see {@link Config#shieldRadius}).
 * <ul>
 *     <li>A shield exists from its minimum Core level on and is intact until it breaks.</li>
 *     <li>A hostile mob that enters the outermost intact dome breaks it. The pulse then kills the attackers
 *     around the Core and removes the builders' siege stone and ladders outside the dome. No other block is touched.</li>
 *     <li>While a shield is intact, the Core takes no damage (arrows from afar do nothing).</li>
 *     <li>Repairing the outermost broken shield costs Core levels.</li>
 * </ul>
 * The broken state is stored in the Core block entity.
 */
public final class Shields {
    private static final DustParticleOptions[] COLORS = {
            new DustParticleOptions(new Vector3f(0.3F, 0.7F, 1.0F), 1.3F),
            new DustParticleOptions(new Vector3f(0.3F, 1.0F, 0.8F), 1.3F),
            new DustParticleOptions(new Vector3f(1.0F, 0.9F, 0.4F), 1.3F)
    };
    /** Breach check every this many ticks; the domes are redrawn every {@link #DRAW_TICKS} ticks. */
    private static final int CHECK_TICKS = 5;
    private static final int DRAW_TICKS = 10;

    private Shields() {
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** The shield exists at the Core's current level (broken or not). */
    public static boolean isAvailable(CoreBlockEntity core, int index) {
        return Config.SHIELDS_ENABLED.get() && core.getCoreLevel() >= Config.shieldMinLevel(index);
    }

    public static boolean isIntact(CoreBlockEntity core, int index) {
        return isAvailable(core, index) && !core.isShieldBroken(index);
    }

    public static int countAvailable(CoreBlockEntity core) {
        int count = 0;
        for (int i = 0; i < Config.SHIELD_COUNT; i++) {
            if (isAvailable(core, i)) {
                count++;
            }
        }
        return count;
    }

    public static int countIntact(CoreBlockEntity core) {
        int count = 0;
        for (int i = 0; i < Config.SHIELD_COUNT; i++) {
            if (isIntact(core, i)) {
                count++;
            }
        }
        return count;
    }

    /** Index of the intact shield with the largest radius, or -1 if none is intact. */
    public static int outermostIntact(CoreBlockEntity core) {
        int best = -1;
        for (int i = 0; i < Config.SHIELD_COUNT; i++) {
            if (isIntact(core, i) && (best < 0 || Config.shieldRadius(i) > Config.shieldRadius(best))) {
                best = i;
            }
        }
        return best;
    }

    /** Index of the broken (but existing) shield with the largest radius, or -1 if none is broken. */
    public static int outermostBroken(CoreBlockEntity core) {
        int best = -1;
        for (int i = 0; i < Config.SHIELD_COUNT; i++) {
            if (isAvailable(core, i) && core.isShieldBroken(i)
                    && (best < 0 || Config.shieldRadius(i) > Config.shieldRadius(best))) {
                best = i;
            }
        }
        return best;
    }

    /** True while the Core is protected from damage by an intact shield. */
    public static boolean absorbs(CoreBlockEntity core) {
        return Config.SHIELD_ABSORBS_DAMAGE.get() && outermostIntact(core) >= 0;
    }

    /** Core level needed to repair the given shield (it must still exist afterwards). */
    public static int repairNeededLevel(int index) {
        return Math.max(Config.SHIELD_REPAIR_MIN_LEVEL.get(),
                Config.shieldMinLevel(index) + Config.SHIELD_REPAIR_LEVEL_COST.get());
    }

    /** 0 = nothing to repair, 1 = repair possible, 2 = Core level too low. */
    public static int repairState(CoreBlockEntity core) {
        int index = outermostBroken(core);
        if (index < 0) {
            return 0;
        }
        return core.getCoreLevel() >= repairNeededLevel(index) ? 1 : 2;
    }

    /** Level the Core needs for the next repair (0 if nothing is broken). */
    public static int nextRepairLevel(CoreBlockEntity core) {
        int index = outermostBroken(core);
        return index < 0 ? 0 : repairNeededLevel(index);
    }

    // ------------------------------------------------------------------
    // Tick: draw the domes, look for mobs that enter
    // ------------------------------------------------------------------

    public static void tick(ServerLevel level, BlockPos pos, CoreBlockEntity core, int tickCounter) {
        if (!Config.SHIELDS_ENABLED.get()) {
            return;
        }
        if (tickCounter % DRAW_TICKS == 0) {
            draw(level, pos, core);
        }
        if (tickCounter % CHECK_TICKS == 0) {
            checkBreach(level, pos, core);
        }
    }

    private static void draw(ServerLevel level, BlockPos pos, CoreBlockEntity core) {
        if (level.players().isEmpty()) {
            return;
        }
        RandomSource random = level.getRandom();
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        for (int i = 0; i < Config.SHIELD_COUNT; i++) {
            if (!isIntact(core, i)) {
                continue;
            }
            double radius = Config.shieldRadius(i);
            int points = Mth.clamp((int) (radius * radius * 1.5), 30, 150);
            for (int k = 0; k < points; k++) {
                // Random point on the sphere (uniform).
                double y = random.nextDouble() * 2.0 - 1.0;
                double phi = random.nextDouble() * Math.PI * 2.0;
                double ring = Math.sqrt(1.0 - y * y);
                SpawnRings.send(level, COLORS[i],
                        cx + Math.cos(phi) * ring * radius, cy + y * radius, cz + Math.sin(phi) * ring * radius);
            }
        }
    }

    private static void checkBreach(ServerLevel level, BlockPos pos, CoreBlockEntity core) {
        int index = outermostIntact(core);
        if (index < 0) {
            return;
        }
        double radius = Config.shieldRadius(index);
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        AABB box = new AABB(cx - radius, cy - radius, cz - radius, cx + radius, cy + radius, cz + radius);
        List<Mob> inside = level.getEntitiesOfClass(Mob.class, box,
                mob -> SiegeRules.isAttacker(mob) && mob.distanceToSqr(cx, cy, cz) <= radius * radius);
        if (!inside.isEmpty()) {
            breach(level, pos, core, index);
        }
    }

    // ------------------------------------------------------------------
    // Breach and pulse
    // ------------------------------------------------------------------

    private static void breach(ServerLevel level, BlockPos pos, CoreBlockEntity core, int index) {
        core.setShieldBroken(index, true);
        double radius = Config.shieldRadius(index);
        double pulse = radius + Config.SHIELD_PULSE_EXTRA_RADIUS.get();
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        // 1. Kill the attackers around the Core (no explosion, no block damage).
        AABB box = new AABB(cx - pulse, cy - pulse, cz - pulse, cx + pulse, cy + pulse, cz + pulse);
        List<Mob> victims = level.getEntitiesOfClass(Mob.class, box,
                mob -> SiegeRules.isAttacker(mob) && mob.distanceToSqr(cx, cy, cz) <= pulse * pulse);
        int killed = 0;
        for (Mob mob : victims) {
            if (mob.isAlive() && mob.hurt(level.damageSources().genericKill(), Float.MAX_VALUE)) {
                killed++;
            }
        }

        // 2. Remove what the builders put up outside the dome (stairs, bridges, ladders). Never player blocks.
        int wiped = 0;
        if (Config.SHIELD_WIPE_BUILDER_BLOCKS.get()) {
            wiped = wipeBuilderBlocks(level, pos, radius, pulse);
        }

        // 3. Show and tell.
        effects(level, pos, pulse);
        int left = countIntact(core);
        level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.incursion.shield.broken", index + 1, killed, wiped, left)
                .withStyle(ChatFormatting.GOLD), false);
        if (left == 0) {
            level.getServer().getPlayerList().broadcastSystemMessage(
                    Component.translatable("message.incursion.shield.all_down")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD), false);
        }
    }

    private static int wipeBuilderBlocks(ServerLevel level, BlockPos center, double inner, double outer) {
        int range = (int) Math.ceil(outer);
        double innerSq = inner * inner;
        double outerSq = outer * outer;
        Block stone = ModBlocks.SIEGE_STONE.get();
        Block ladder = ModBlocks.SIEGE_LADDER.get();
        int wiped = 0;
        BlockPos min = center.offset(-range, -range, -range);
        BlockPos max = center.offset(range, range, range);
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            double dx = p.getX() - center.getX();
            double dy = p.getY() - center.getY();
            double dz = p.getZ() - center.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= innerSq || distSq > outerSq || !level.isLoaded(p)) {
                continue;
            }
            BlockState state = level.getBlockState(p);
            if (state.is(stone) || state.is(ladder)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                wiped++;
            }
        }
        return wiped;
    }

    private static void effects(ServerLevel level, BlockPos pos, double pulse) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 4.0F, 0.6F);
        level.playSound(null, pos, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, 3.0F, 1.4F);
        level.sendParticles(ParticleTypes.FLASH, cx, cy + 0.5, cz, 1, 0.0, 0.0, 0.0, 0.0);
        // The pulse as a sphere of sparks at its full radius.
        RandomSource random = level.getRandom();
        int points = Mth.clamp((int) (pulse * pulse * 0.6), 80, 400);
        for (int k = 0; k < points; k++) {
            double y = random.nextDouble() * 2.0 - 1.0;
            double phi = random.nextDouble() * Math.PI * 2.0;
            double ring = Math.sqrt(1.0 - y * y);
            SpawnRings.send(level, ParticleTypes.END_ROD,
                    cx + Math.cos(phi) * ring * pulse, cy + y * pulse, cz + Math.sin(phi) * ring * pulse);
        }
    }

    /** Small spark when the shield swallows a hit that would have damaged the Core. */
    public static void spark(ServerLevel level, BlockPos pos) {
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                6, 0.5, 0.5, 0.5, 0.1);
    }

    // ------------------------------------------------------------------
    // Level-up message and repair
    // ------------------------------------------------------------------

    /** Tells the player when an upgrade makes a new shield appear. */
    public static void onLevelUp(ServerLevel level, BlockPos pos, CoreBlockEntity core, int oldLevel, int newLevel,
                                 Player player) {
        if (!Config.SHIELDS_ENABLED.get()) {
            return;
        }
        for (int i = 0; i < Config.SHIELD_COUNT; i++) {
            int minLevel = Config.shieldMinLevel(i);
            if (oldLevel < minLevel && newLevel >= minLevel) {
                level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.6F);
                player.displayClientMessage(Component.translatable("message.incursion.shield.gained",
                        i + 1, Config.shieldRadius(i)).withStyle(ChatFormatting.AQUA), false);
            }
        }
    }

    /** Repairs the outermost broken shield; costs Core levels (button in the Core GUI). */
    public static void tryRepair(ServerLevel level, BlockPos pos, CoreBlockEntity core, Player player) {
        if (!core.isActive()) {
            return;
        }
        int index = outermostBroken(core);
        if (index < 0) {
            player.displayClientMessage(Component.translatable("message.incursion.shield.nothing_to_repair")
                    .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        int needed = repairNeededLevel(index);
        if (core.getCoreLevel() < needed) {
            player.displayClientMessage(Component.translatable("message.incursion.shield.repair_level",
                    needed, Config.SHIELD_REPAIR_LEVEL_COST.get()).withStyle(ChatFormatting.RED), true);
            return;
        }
        int newLevel = core.getCoreLevel() - Config.SHIELD_REPAIR_LEVEL_COST.get();
        core.setShieldBroken(index, false);
        core.setCoreLevel(newLevel);
        core.setHealth(core.getHealth()); // clamps to the lower maximum
        level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.0F, 1.4F);
        level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                40, 0.6, 0.6, 0.6, 0.05);
        player.displayClientMessage(Component.translatable("message.incursion.shield.repaired",
                index + 1, newLevel).withStyle(ChatFormatting.AQUA), false);
    }
}
