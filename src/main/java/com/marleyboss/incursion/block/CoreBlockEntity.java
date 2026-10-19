package com.marleyboss.incursion.block;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.entity.CoreTargetEntity;
import com.marleyboss.incursion.registry.ModBlockEntities;
import com.marleyboss.incursion.registry.ModBlocks;
import com.marleyboss.incursion.registry.ModEntities;
import com.marleyboss.incursion.siege.Shields;
import com.marleyboss.incursion.siege.SiegeManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stores the state of an Incursion Core. Whether the core is active is kept in the
 * block state ({@link CoreBlock#ACTIVE}); level, health and siege bookkeeping live here.
 */
public class CoreBlockEntity extends BlockEntity {
    private static final String TAG_LEVEL = "CoreLevel";
    private static final String TAG_HEALTH = "CoreHealth";
    private static final String TAG_LOADED_RADIUS = "LoadedChunkRadius";
    private static final String TAG_SHOW_BORDERS = "ShowBorders";
    private static final String TAG_SHOW_AI = "ShowAI";
    private static final String TAG_BROKEN_SHIELDS = "BrokenShields";
    private static final String TAG_SHARDS = "Shards";
    private static final String TAG_NIGHTS = "DefendedNights";
    private static final String TAG_KILLS = "KilledMobs";
    private static final String TAG_NIGHT_DAY = "NightDay";
    private static final String TAG_GUNPOWDER = "RitualGunpowder";
    private static final String TAG_BLAZE_POWDER = "RitualBlazePowder";
    /** Only in the client update tag (never saved to disk): ward totems whose border the client draws. */
    private static final String TAG_WARDS = "Wards";

    /** Gunpowder laid into the Sarcophagus so far (the block state flag is set when the full amount is in). */
    private int gunpowder = 0;
    /** Blaze powder laid into the Sarcophagus so far. */
    private int blazePowder = 0;
    private int coreLevel = 0;
    /** Core shards kept in the Core (the balance). */
    private int shards = 0;
    /** Nights this Core has survived while active (statistic). */
    private int defendedNights = 0;
    /** Siege mobs that died around this Core (statistic). */
    private int killedMobs = 0;
    /** Day number of the night this Core saw begin while active (-1 = none); paid out at the next morning. */
    private long nightDay = -1;
    private float health = 0;
    /** Chunk radius currently force-loaded for this core, or -1 if none. */
    private int loadedChunkRadius = -1;
    /** Draw the spawn borders (Core and ward totems) as particle rings. */
    private boolean showBorders = false;
    /** AI view: name tags with what every attacker is doing, routes as particles. */
    private boolean showAi = false;
    /** Bit i is set if shield i + 1 (0 = outermost) is broken. Cleared by repairing, defusing or reactivating. */
    private int brokenShields = 0;
    /** Ward totem positions (packed) sent to clients for the border display. */
    private long[] syncedWards = new long[0];
    private int syncSignature = Integer.MIN_VALUE;
    private int idleCounter = 0;
    /** Loaded Cores, so a changed ward totem (switched off, repaired) can have the borders sent again at once. */
    private static final java.util.Set<CoreBlockEntity> LOADED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    // Runtime only (not saved)
    @Nullable
    private CoreTargetEntity target;
    private int tickCounter = 0;
    private long lastWarningTick = Long.MIN_VALUE / 2;
    private int siegeMobCount = 0;
    /** Remaining ticks of a running defusal (0 = not defusing). */
    /** Client side: when the running defuse ends (game time, 0 = none) and how long it takes. See {@link #getClientDefuseProgress}. */
    private long clientDefuseEnd = 0L;
    private int clientDefuseTotal = 0;
    /** Client side: how long this Core has been active (ticks), for the fading particles. */
    private int clientActiveTicks = 0;
    private int defuseTicksLeft = 0;
    private int defuseTicksTotal = 0;
    @Nullable
    private UUID defuser;
    /** Evolution stage the visuals show (see CoreEvolution); saved and synced. */
    private int stage = 0;
    /** The stage whose structure is completely built (-1 = nothing built); saved and synced. */
    private int built = -1;
    /** Game time the last evolution started (synced, not saved). */
    private long evoStart = 0L;
    /** Blocks that were in the way, shown shaking until they blow up (client side copy, synced, not saved). */
    private final List<Ghost> ghosts = new ArrayList<>();
    /** Blocks still to place (server side). */
    private final java.util.PriorityQueue<Step> steps =
            new java.util.PriorityQueue<>(java.util.Comparator.comparingLong(Step::due));

    /** A block that was in the way: shown at {@code pos} shaking until game time {@code boom}. */
    /** The plaza blocks that vanish one after the other after a defuse (not saved; see {@code fading}). */
    private final java.util.PriorityQueue<Fade> fadeQueue = new java.util.PriorityQueue<>(java.util.Comparator.comparingLong(Fade::due));
    private boolean fading = false;

    private record Fade(BlockPos pos, long due) {
    }

    public record Ghost(BlockPos pos, BlockState state, long boom) {
    }

    /** A block to place (or only to blow up) at game time {@code due}. */
    private record Step(BlockPos pos, @Nullable BlockState target, @Nullable BlockState old, long due, int kind) {
    }

    public CoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CORE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CoreBlockEntity core) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        LOADED.add(core);
        if (!state.getValue(CoreBlock.ACTIVE)) {
            if (core.built != -1 || core.stage != 0) {
                core.beginFade(serverLevel);
            }
            if (!core.fadeQueue.isEmpty()) {
                core.tickFade(serverLevel);
            } else if (core.fading) {
                core.dismantle(serverLevel); // a fade that was cut short by a reload: remove what is left
            }
            // An inactive Core can show the borders too (switch in the GUI): the totems around it are synced.
            if (core.showBorders && ++core.idleCounter % 20 == 0) {
                core.syncToClient(WardBlockEntity.wardsNear(level, pos,
                        Config.siegeRadiusBlocks(Math.max(1, core.coreLevel))));
            }
            return;
        }
        core.tickCounter++;
        core.tickEvolution(serverLevel);
        if (core.defuseTicksLeft > 0) {
            core.tickDefuse(serverLevel);
            if (!core.isActive()) {
                return;
            }
        }
        Shields.tick(serverLevel, pos, core, core.tickCounter);
        if (!core.isActive()) {
            return;
        }
        if (core.tickCounter % 20 == 0) {
            SiegeManager.tickCore(serverLevel, pos, core, core.tickCounter);
        }
    }

    // ------------------------------------------------------------------
    // Getters / setters
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Evolution (see CoreEvolution)
    // ------------------------------------------------------------------

    private static final int MAX_GHOSTS = 300;

    /** Server: starts the evolution when the stage the level asks for is not the stage that is built. */
    private void tickEvolution(ServerLevel level) {
        long now = level.getGameTime();
        int desired = CoreEvolution.stageFor(coreLevel);
        boolean topMissing = built == stage && built >= 0 && tickCounter % 20 == 0
                && !level.getBlockState(worldPosition.above()).is(ModBlocks.CORE_TOP.get());
        if (desired != stage || (built != stage && steps.isEmpty()) || topMissing) {
            evolve(level, desired);
        }
        boolean worked = false;
        while (!steps.isEmpty() && steps.peek().due() <= now) {
            place(level, steps.poll());
            worked = true;
        }
        if (worked && steps.isEmpty() && built != stage) {
            built = stage;
            setChanged();
            forceSync();
            if (stage >= 1) {
                finale(level);
            }
        }
        if (built == stage && stage >= 1 && tickCounter % 4 == 0) {
            clearStrays(level);
        }
        if (!ghosts.isEmpty() && steps.isEmpty()) {
            ghosts.removeIf(g -> g.boom() + 40 < now);
        }
    }

    /**
     * Plans the structure of a stage: whatever stands in its place is removed without a drop (visible blocks are sent to
     * the clients as {@link Ghost}s that shake and then blow up), then it is built up from the middle outwards, the
     * pillars last.
     */
    public void evolve(ServerLevel level, int newStage) {
        long now = level.getGameTime();
        stage = newStage;
        built = -1;
        evoStart = now;
        steps.clear();
        fadeQueue.clear();
        fading = false;
        ghosts.clear();
        java.util.List<CoreEvolution.Cell> plan = CoreEvolution.plan(newStage);
        java.util.Set<Long> wanted = new java.util.HashSet<>();
        for (CoreEvolution.Cell cell : plan) {
            wanted.add(worldPosition.offset(cell.x(), cell.y(), cell.z()).asLong());
        }
        // what an earlier stage built and this one does not have
        for (CoreEvolution.Cell cell : CoreEvolution.everything()) {
            BlockPos pos = worldPosition.offset(cell.x(), cell.y(), cell.z());
            if (!wanted.contains(pos.asLong()) && CoreEvolution.isStructure(level.getBlockState(pos))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        level.playSound(null, worldPosition, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, 4.0F, 0.55F);
        int pillar = CoreEvolution.pillarHeight(newStage);
        int radius = CoreEvolution.plazaRadius(newStage);
        for (CoreEvolution.Cell cell : plan) {
            BlockPos pos = worldPosition.offset(cell.x(), cell.y(), cell.z());
            BlockState cur = level.getBlockState(pos);
            if (cur == cell.state()) {
                continue;
            }
            if (!CoreEvolution.isStructure(cur) && cur.getDestroySpeed(level, pos) < 0.0F) {
                continue; // bedrock and the like stay
            }
            boolean reserved = cell.state().is(ModBlocks.CORE_PART.get()) || cell.state().is(ModBlocks.CORE_TOP.get());
            double dist = Math.sqrt(cell.x() * cell.x() + cell.z() * cell.z());
            long due;
            int kind = 0;
            if (reserved) {
                due = now + CoreEvolution.SHAKE + (long) (dist * 3.0) + cell.y();
            } else if (cell.y() >= 0 && pillar > 0) {
                due = now + CoreEvolution.SHAKE + 20 + radius * 3L + cell.y() * 4L; // the pillars rise last
                kind = cell.y() == pillar - 1 ? 2 : 1;
            } else {
                due = now + CoreEvolution.SHAKE + 4 + (long) (dist * 3.0); // plaza: a wave from the middle
            }
            boolean visible = !cur.isAir() && cur.getFluidState().isEmpty() && !CoreEvolution.isStructure(cur)
                    && cur.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL;
            BlockState old = null;
            if (visible && ghosts.size() < MAX_GHOSTS) {
                ghosts.add(new Ghost(pos, cur, due));
                old = cur;
            }
            level.removeBlockEntity(pos); // no contents fall out
            if (reserved) {
                level.setBlock(pos, cell.state(), Block.UPDATE_ALL);
                if (old != null) {
                    steps.add(new Step(pos, null, old, due, 0));
                }
            } else {
                if (!cur.isAir()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                if (!cell.state().isAir()) {
                    steps.add(new Step(pos, cell.state(), old, due, kind));
                } else if (old != null) {
                    steps.add(new Step(pos, null, old, due, 0)); // only the blast
                }
            }
        }
        // Everything else in the area goes too (the area stays free), whatever it is.
        int r = CoreEvolution.areaRadius(newStage);
        int h = CoreEvolution.areaHeight(newStage);
        if (newStage >= 1) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = CoreEvolution.areaBottom(newStage); dy < h; dy++) {
                        if (!CoreEvolution.inArea(newStage, dx, dy, dz)) {
                            continue;
                        }
                        BlockPos pos = worldPosition.offset(dx, dy, dz);
                        if (wanted.contains(pos.asLong())) {
                            continue;
                        }
                        BlockState cur = level.getBlockState(pos);
                        if (cur.isAir() || CoreEvolution.isStructure(cur) || CoreAreaGuard.isExempt(cur)
                                || cur.getDestroySpeed(level, pos) < 0.0F) {
                            continue;
                        }
                        boolean visible = cur.getFluidState().isEmpty()
                                && cur.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL;
                        long due = now + CoreEvolution.SHAKE + (long) (Math.sqrt(dx * dx + dz * dz) * 3.0) + dy;
                        BlockState old = null;
                        if (visible && ghosts.size() < MAX_GHOSTS) {
                            ghosts.add(new Ghost(pos, cur, due));
                            old = cur;
                        }
                        level.removeBlockEntity(pos);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        if (old != null) {
                            steps.add(new Step(pos, null, old, due, 0));
                        }
                    }
                }
            }
        }
        if (steps.isEmpty()) {
            built = stage;
        }
        setChanged();
        forceSync();
    }

    /** Removes whatever got into the protected area (flowing water, a piston, ...) without a drop. */
    private void clearStrays(ServerLevel level) {
        int doomed = 0;
        int r = CoreEvolution.areaRadius(stage);
        int h = CoreEvolution.areaHeight(stage);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = CoreEvolution.areaBottom(stage); dy < h; dy++) {
                    if (!CoreEvolution.inArea(stage, dx, dy, dz)) {
                        continue;
                    }
                    BlockPos pos = worldPosition.offset(dx, dy, dz);
                    BlockState cur = level.getBlockState(pos);
                    if (cur.isAir() || CoreEvolution.isStructure(cur) || CoreAreaGuard.isExempt(cur)
                            || cur.getDestroySpeed(level, pos) < 0.0F) {
                        continue;
                    }
                    level.removeBlockEntity(pos);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    boolean visible = cur.getFluidState().isEmpty()
                            && cur.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL;
                    if (visible && ghosts.size() < MAX_GHOSTS && doomed < 24) {
                        // what is placed in the area shakes for a moment and blows up
                        long due = level.getGameTime() + 30L;
                        ghosts.add(new Ghost(pos, cur, due));
                        steps.add(new Step(pos, null, cur, due, 0));
                        doomed++;
                    } else {
                        level.levelEvent(2001, pos, Block.getId(cur));
                        level.sendParticles(ParticleTypes.POOF, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3,
                                0.2, 0.2, 0.2, 0.01);
                    }
                }
            }
        }
        if (doomed > 0) {
            forceSync();
        }
    }

    private void place(ServerLevel level, Step step) {
        BlockPos pos = step.pos();
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        if (step.old() != null && (pos.getX() + pos.getZ() + pos.getY()) % 5 == 0) {
            level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.4F, 0.9F);
        }
        if (step.target() != null) {
            level.setBlock(pos, step.target(), Block.UPDATE_ALL);
            if (step.kind() == 2) {
                level.sendParticles(ParticleTypes.END_ROD, x, y, z, 6, 0.3, 0.3, 0.3, 0.05);
                level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0F, 0.6F + level.random.nextFloat() * 0.8F);
            } else if (step.kind() == 1) {
                level.sendParticles(ParticleTypes.WAX_ON, x, y, z, 4, 0.3, 0.3, 0.3, 0.0);
                level.playSound(null, pos, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.5F, 1.4F);
            } else if ((pos.getX() + pos.getZ()) % 2 == 0) {
                level.sendParticles(ParticleTypes.POOF, x, y + 0.5, z, 2, 0.3, 0.1, 0.3, 0.01);
            }
        }
    }

    /** A new stage stands: a strike of lightning and a shower of sparks. */
    private void finale(ServerLevel level) {
        double x = worldPosition.getX() + 0.5;
        double y = worldPosition.getY() + 1.0;
        double z = worldPosition.getZ() + 0.5;
        level.playSound(null, worldPosition, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 6.0F, 0.8F);
        level.playSound(null, worldPosition, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 1.2F, 1.4F);
        level.sendParticles(ParticleTypes.FLASH, x, y + 0.5, z, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.END_ROD, x, y + 0.5, z, 120, 1.5, 1.5, 1.5, 0.25);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        for (ServerPlayer near : level.players()) {
            if (near.distanceToSqr(x, y, z) < 80.0 * 80.0) {
                near.displayClientMessage(Component.translatable("message.incursion.core.stage." + stage)
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
            }
        }
    }

    /**
     * The Core was defused: the invisible parts go at once, the plaza vanishes ring by ring from the outside in (the
     * visible parts have already folded away on the client, driven by the countdown).
     */
    private void beginFade(ServerLevel level) {
        steps.clear();
        ghosts.clear();
        fadeQueue.clear();
        long now = level.getGameTime();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (CoreEvolution.Cell cell : CoreEvolution.everything()) {
            BlockPos pos = worldPosition.offset(cell.x(), cell.y(), cell.z());
            if (!seen.add(pos.asLong())) {
                continue;
            }
            BlockState cur = level.getBlockState(pos);
            if (!CoreEvolution.isStructure(cur)) {
                continue;
            }
            if (cur.is(ModBlocks.CORE_FLOOR.get()) || cur.is(ModBlocks.CORE_GOLD.get())) {
                double dist = Math.sqrt(cell.x() * cell.x() + cell.z() * cell.z());
                fadeQueue.add(new Fade(pos, now + 6L + (long) ((11.5 - dist) * 3.0)));
            } else {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        stage = 0;
        built = -1;
        fading = !fadeQueue.isEmpty();
        setChanged();
        forceSync();
    }

    private void tickFade(ServerLevel level) {
        long now = level.getGameTime();
        int count = 0;
        while (!fadeQueue.isEmpty() && fadeQueue.peek().due() <= now) {
            Fade fade = fadeQueue.poll();
            BlockState cur = level.getBlockState(fade.pos());
            if (!CoreEvolution.isStructure(cur)) {
                continue;
            }
            level.setBlock(fade.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, cur),
                    fade.pos().getX() + 0.5, fade.pos().getY() + 0.9, fade.pos().getZ() + 0.5, 4, 0.3, 0.1, 0.3, 0.02);
            if (count++ % 6 == 0) {
                level.playSound(null, fade.pos(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.BLOCKS, 0.7F, 0.7F);
            }
        }
        if (fadeQueue.isEmpty()) {
            fading = false;
            setChanged();
        }
    }

    /** Removes the whole structure at once (the Core is gone). */
    public void dismantle(ServerLevel level) {
        steps.clear();
        ghosts.clear();
        fadeQueue.clear();
        fading = false;
        for (CoreEvolution.Cell cell : CoreEvolution.everything()) {
            BlockPos pos = worldPosition.offset(cell.x(), cell.y(), cell.z());
            if (CoreEvolution.isStructure(level.getBlockState(pos))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        stage = 0;
        built = -1;
        setChanged();
        forceSync();
    }

    public int getStage() {
        return stage;
    }

    public int getBuiltStage() {
        return built;
    }

    public long getEvoStart() {
        return evoStart;
    }

    public List<Ghost> getGhosts() {
        return ghosts;
    }

    /** Client: the particles of an active Core (see CoreEffects). */
    public static void clientTick(Level level, BlockPos pos, BlockState state, CoreBlockEntity core) {
        if (state.getValue(CoreBlock.ACTIVE)) {
            core.clientActiveTicks++;
            CoreEffects.spawn(level, pos, core.clientActiveTicks);
            core.evolutionParticles(level, pos);
        } else {
            core.clientActiveTicks = 0;
        }
    }

    /** Client: embers from the energy crystals, sparks at stage 3, a rising stream of light at stage 4. */
    private void evolutionParticles(Level level, BlockPos pos) {
        if (built < 2) {
            return;
        }
        int radius = CoreEvolution.plazaRadius(built);
        int height = CoreEvolution.pillarHeight(built);
        // stage 2 the forge (flames and lava drips), 3 the souls, 4 the rift (ash)
        double top = built >= 4 ? height + 0.3 : built == 3 ? height - 0.4 : height + 0.1;
        if (level.getGameTime() % 3 == 0) {
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    double x = pos.getX() + 0.5 + sx * radius + (level.random.nextDouble() - 0.5) * 0.6;
                    double z = pos.getZ() + 0.5 + sz * radius + (level.random.nextDouble() - 0.5) * 0.6;
                    double y = pos.getY() + top + level.random.nextDouble() * 0.9;
                    if (built == 2) {
                        level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.05, 0.0);
                        if (level.random.nextInt(3) == 0) {
                            level.addParticle(ParticleTypes.LAVA, x, pos.getY() + height - 1.2, z, 0.0, 0.0, 0.0);
                        }
                    } else if (built == 3) {
                        level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 0.0, 0.04, 0.0);
                        if (level.random.nextInt(3) == 0) {
                            level.addParticle(ParticleTypes.ENCHANT, x, y, z, 0.0, 0.1, 0.0);
                        }
                    } else {
                        level.addParticle(ParticleTypes.ASH, x, y, z, 0.0, 0.03, 0.0);
                        if (level.random.nextInt(3) == 0) {
                            level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, (level.random.nextDouble() - 0.5) * 0.3,
                                    (level.random.nextDouble() - 0.5) * 0.3, (level.random.nextDouble() - 0.5) * 0.3);
                        }
                    }
                }
            }
        }
    }

    /** Client side: how far the running defuse has come, 1 (just started) .. 0 (done); -1 when none is running. */
    public float getClientDefuseProgress(long gameTime) {
        if (clientDefuseEnd <= 0L || clientDefuseTotal <= 0) {
            return -1.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, (clientDefuseEnd - gameTime) / (float) clientDefuseTotal));
    }

    public boolean isActive() {
        BlockState state = getBlockState();
        return state.getBlock() instanceof CoreBlock && state.getValue(CoreBlock.ACTIVE);
    }

    public int getCoreLevel() {
        return coreLevel;
    }

    public void setCoreLevel(int coreLevel) {
        this.coreLevel = Math.max(0, coreLevel);
        setChanged();
    }

    public float getHealth() {
        return health;
    }

    public void setHealth(float health) {
        this.health = Math.max(0, Math.min(health, getMaxHealth()));
        setChanged();
    }

    public int getMaxHealth() {
        return Config.maxCoreHealth(coreLevel);
    }

    public int getLoadedChunkRadius() {
        return loadedChunkRadius;
    }

    public void setLoadedChunkRadius(int loadedChunkRadius) {
        this.loadedChunkRadius = loadedChunkRadius;
        setChanged();
    }

    // ------------------------------------------------------------------
    // Client sync (shields, borders and level are drawn by the client renderer)
    // ------------------------------------------------------------------

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        tag.putLongArray(TAG_WARDS, syncedWards);
        tag.putLong("EvoStart", evoStart);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (Ghost g : ghosts) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("P", g.pos().asLong());
            entry.putLong("B", g.boom());
            entry.put("S", NbtUtils.writeBlockState(g.state()));
            list.add(entry);
        }
        tag.put("Ghosts", list);
        if (defuseTicksLeft > 0 && level != null) {
            tag.putLong("DefuseEnd", level.getGameTime() + defuseTicksLeft);
            tag.putInt("DefuseTotal", defuseTicksTotal);
        }
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Sends the state the client needs if it changed (called once per second by the siege). */
    public void syncToClient(List<BlockPos> wards) {
        long[] packed = new long[wards.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = wards.get(i).asLong();
        }
        Arrays.sort(packed);
        int signature = Objects.hash(coreLevel, showBorders, brokenShields, Arrays.hashCode(packed));
        if (signature != syncSignature) {
            syncSignature = signature;
            syncedWards = packed;
            forceSync();
        }
    }

    /** Makes every loaded Core send its borders to the clients again within a second (a ward totem changed). */
    public static void resyncAll() {
        for (CoreBlockEntity core : LOADED) {
            core.syncSignature = Integer.MIN_VALUE;
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED.remove(this);
    }

    /** Sends the current state to all clients that track this block right away. */
    public void forceSync() {
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    /** Client side: positions of the ward totems around this Core. */
    public List<BlockPos> getSyncedWards() {
        List<BlockPos> result = new ArrayList<>(syncedWards.length);
        for (long packed : syncedWards) {
            result.add(BlockPos.of(packed));
        }
        return result;
    }

    public boolean isShieldBroken(int index) {
        return (brokenShields & (1 << index)) != 0;
    }

    public void setShieldBroken(int index, boolean broken) {
        brokenShields = broken ? brokenShields | (1 << index) : brokenShields & ~(1 << index);
        setChanged();
    }

    public void resetShields() {
        brokenShields = 0;
        setChanged();
    }

    public int getShards() {
        return shards;
    }

    public void addShards(int amount) {
        shards = (int) Math.max(0, Math.min((long) shards + amount, Integer.MAX_VALUE));
        setChanged();
    }

    /** Removes up to {@code amount} shards from the balance and returns how many were really taken. */
    public int takeShards(int amount) {
        int taken = Math.max(0, Math.min(amount, shards));
        shards -= taken;
        if (taken > 0) {
            setChanged();
        }
        return taken;
    }

    public int getDefendedNights() {
        return defendedNights;
    }

    public void addDefendedNight() {
        defendedNights = (int) Math.min((long) defendedNights + 1, Integer.MAX_VALUE);
        setChanged();
    }

    public int getKilledMobs() {
        return killedMobs;
    }

    public void addKilledMob() {
        killedMobs = (int) Math.min((long) killedMobs + 1, Integer.MAX_VALUE);
        setChanged();
    }

    public long getNightDay() {
        return nightDay;
    }

    public void setNightDay(long nightDay) {
        if (this.nightDay != nightDay) {
            this.nightDay = nightDay;
            setChanged();
        }
    }

    public int getSiegeMobCount() {
        return siegeMobCount;
    }

    public void setSiegeMobCount(int siegeMobCount) {
        this.siegeMobCount = siegeMobCount;
    }

    // ------------------------------------------------------------------
    // Defusing
    // ------------------------------------------------------------------

    public boolean isDefusing() {
        return defuseTicksLeft > 0;
    }

    public int getDefuseTicksLeft() {
        return defuseTicksLeft;
    }

    public int getDefuseTicksTotal() {
        return defuseTicksTotal;
    }

    /** Starts defusing, or cancels a running defusal. */
    public void toggleDefuse(ServerLevel level, Player player) {
        if (!isActive()) {
            return;
        }
        if (isDefusing()) {
            stopDefuse();
            player.displayClientMessage(Component.translatable("message.incursion.core.defuse_cancelled")
                    .withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        defuseTicksTotal = Math.max(1, Config.defuseTicks(coreLevel));
        defuseTicksLeft = defuseTicksTotal;
        forceSync(); // the clients close the Sarcophagus slowly while the countdown runs
        defuser = player.getUUID();
        BlockPos pos = getBlockPos();
        level.playSound(null, pos, SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS, 1.0F, 0.5F);
        player.displayClientMessage(Component.translatable("message.incursion.core.defuse_started",
                defuseTicksTotal / 20, Config.DEFUSE_MAX_DISTANCE.get()).withStyle(ChatFormatting.YELLOW), false);
    }

    private void stopDefuse() {
        defuseTicksLeft = 0;
        defuseTicksTotal = 0;
        defuser = null;
        forceSync();
    }

    private void tickDefuse(ServerLevel level) {
        BlockPos pos = getBlockPos();
        ServerPlayer player = defuser == null ? null : level.getServer().getPlayerList().getPlayer(defuser);
        double maxDistance = Config.DEFUSE_MAX_DISTANCE.get();
        if (player == null || player.level() != level
                || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > maxDistance * maxDistance) {
            stopDefuse();
            if (player != null) {
                player.displayClientMessage(Component.translatable("message.incursion.core.defuse_too_far")
                        .withStyle(ChatFormatting.RED), false);
            }
            level.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 0.6F, 1.5F);
            return;
        }
        defuseTicksLeft--;
        if (defuseTicksLeft <= 0) {
            stopDefuse();
            SiegeManager.defuse(level, pos, this);
            return;
        }
        if (defuseTicksLeft % 20 == 0) {
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 0.8F, 1.6F);
            player.displayClientMessage(Component.translatable("message.incursion.core.defuse_progress",
                    defuseTicksLeft / 20).withStyle(ChatFormatting.YELLOW), true);
        }
    }

    // ------------------------------------------------------------------
    // Target entity
    // ------------------------------------------------------------------

    /** Returns the invisible target entity for this core, spawning it if necessary. */
    @Nullable
    public CoreTargetEntity ensureTarget(ServerLevel level) {
        if (target != null && target.isAlive() && target.level() == level) {
            return target;
        }
        target = null;
        BlockPos pos = getBlockPos();
        List<CoreTargetEntity> existing = level.getEntitiesOfClass(CoreTargetEntity.class, new AABB(pos).inflate(1.0));
        for (CoreTargetEntity entity : existing) {
            if (!entity.blockPosition().equals(pos)) {
                continue; // a ward totem next door
            }
            if (target == null) {
                target = entity;
            } else {
                entity.discard();
            }
        }
        if (target == null) {
            CoreTargetEntity entity = ModEntities.CORE_TARGET.get().create(level);
            if (entity == null) {
                return null;
            }
            entity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
            level.addFreshEntity(entity);
            target = entity;
        }
        return target;
    }

    @Nullable
    public CoreTargetEntity getTarget() {
        return target;
    }

    public void removeTarget() {
        if (target != null) {
            target.discard();
            target = null;
        }
        if (level != null) {
            for (CoreTargetEntity entity : level.getEntitiesOfClass(CoreTargetEntity.class,
                    new AABB(getBlockPos()).inflate(1.0))) {
                if (entity.blockPosition().equals(getBlockPos())) {
                    entity.discard(); // not the stand-in of a ward totem next door
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Damage
    // ------------------------------------------------------------------

    public void damageCore(ServerLevel level, float amount) {
        if (!isActive() || amount <= 0) {
            return;
        }
        if (Shields.absorbs(this)) {
            Shields.spark(level, getBlockPos());
            return;
        }
        health = Math.max(0, health - amount);
        setChanged();

        BlockPos pos = getBlockPos();
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
                3, 0.3, 0.1, 0.3, 0.1);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.BLOCKS, 1.0F, 0.6F);

        int maxHealth = getMaxHealth();
        long now = level.getGameTime();
        if (now - lastWarningTick >= Config.WARNING_COOLDOWN_SECONDS.get() * 20L) {
            lastWarningTick = now;
            boolean critical = health <= maxHealth * 0.25F;
            level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                    critical ? "message.incursion.core.under_attack_critical" : "message.incursion.core.under_attack",
                    (int) Math.ceil(health), maxHealth, pos.getX(), pos.getY(), pos.getZ())
                    .withStyle(critical ? ChatFormatting.DARK_RED : ChatFormatting.RED), false);
        }

        if (health <= 0) {
            SiegeManager.destroyCore(level, pos, this);
        }
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(TAG_LEVEL, coreLevel);
        tag.putInt("Stage", stage);
        tag.putInt("Built", built);
        tag.putBoolean("Fading", fading);
        tag.putFloat(TAG_HEALTH, health);
        tag.putInt(TAG_LOADED_RADIUS, loadedChunkRadius);
        tag.putBoolean(TAG_SHOW_BORDERS, showBorders);
        tag.putBoolean(TAG_SHOW_AI, showAi);
        tag.putInt(TAG_BROKEN_SHIELDS, brokenShields);
        tag.putInt(TAG_SHARDS, shards);
        tag.putInt(TAG_NIGHTS, defendedNights);
        tag.putInt(TAG_KILLS, killedMobs);
        tag.putLong(TAG_NIGHT_DAY, nightDay);
        tag.putInt(TAG_GUNPOWDER, gunpowder);
        tag.putInt(TAG_BLAZE_POWDER, blazePowder);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        coreLevel = tag.getInt(TAG_LEVEL);
        stage = tag.getInt("Stage");
        built = tag.contains("Built") ? tag.getInt("Built") : -1;
        fading = tag.getBoolean("Fading");
        evoStart = tag.getLong("EvoStart");
        ghosts.clear();
        if (tag.contains("Ghosts")) {
            net.minecraft.nbt.ListTag list = tag.getList("Ghosts", 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                ghosts.add(new Ghost(BlockPos.of(entry.getLong("P")),
                        NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), entry.getCompound("S")),
                        entry.getLong("B")));
            }
        }
        clientDefuseEnd = tag.contains("DefuseEnd") ? tag.getLong("DefuseEnd") : 0L;
        clientDefuseTotal = tag.getInt("DefuseTotal");
        // Cores from v0.1 have no health yet: start them at full health.
        health = tag.contains(TAG_HEALTH) ? tag.getFloat(TAG_HEALTH) : -1;
        loadedChunkRadius = tag.contains(TAG_LOADED_RADIUS) ? tag.getInt(TAG_LOADED_RADIUS) : -1;
        showBorders = tag.getBoolean(TAG_SHOW_BORDERS);
        showAi = tag.getBoolean(TAG_SHOW_AI);
        brokenShields = tag.getInt(TAG_BROKEN_SHIELDS);
        shards = tag.getInt(TAG_SHARDS);
        defendedNights = tag.getInt(TAG_NIGHTS);
        killedMobs = tag.getInt(TAG_KILLS);
        nightDay = tag.contains(TAG_NIGHT_DAY) ? tag.getLong(TAG_NIGHT_DAY) : -1;
        gunpowder = tag.getInt(TAG_GUNPOWDER);
        blazePowder = tag.getInt(TAG_BLAZE_POWDER);
        syncedWards = tag.contains(TAG_WARDS) ? tag.getLongArray(TAG_WARDS) : new long[0];
    }

    public int getGunpowder() {
        return gunpowder;
    }

    public void setGunpowder(int gunpowder) {
        this.gunpowder = Math.max(0, gunpowder);
        setChanged();
    }

    public int getBlazePowder() {
        return blazePowder;
    }

    public void setBlazePowder(int blazePowder) {
        this.blazePowder = Math.max(0, blazePowder);
        setChanged();
    }

    public boolean isShowBorders() {
        return showBorders;
    }

    public void toggleShowBorders() {
        showBorders = !showBorders;
        setChanged();
        forceSync();
    }

    public boolean isShowAi() {
        return showAi;
    }

    public void toggleShowAi() {
        showAi = !showAi;
        setChanged();
    }

    /** Called by the siege manager once per second; fixes up values that could not be set while loading. */
    public void validateHealth() {
        if (health < 0) {
            health = getMaxHealth();
            setChanged();
        } else if (health > getMaxHealth()) {
            health = getMaxHealth();
            setChanged();
        }
    }
}
