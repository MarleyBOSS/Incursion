package com.marleyboss.incursion.story;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Villagers make the story items in front of the player, at their job site:
 * <ul>
 *     <li>{@link Kind#FORGE}: the Master Toolsmith lays the Upgrade Tool and the insignia on his smithing table, strikes
 *     them with a hammer (sparks, anvil sound) and the stronger tool lies there.</li>
 *     <li>{@link Kind#BREW}: the cleric brews the Mythic Potion at his brewing stand (bubbles, flames).</li>
 *     <li>{@link Kind#SCRIBE}: the librarian writes the Core Rune at his lectern (glyphs flow into the book).</li>
 * </ul>
 * The finished item lies on the job site and only the player it is for can pick it up (it cannot burn or despawn).
 * Bought items (rune, potion) are taken back from the player right after the trade and handed out at the end of the
 * work. If the work cannot finish (the villager dies or is unloaded, the server stops), the item is handed out at once.
 */
public final class VillagerWorks {
    public enum Kind {
        FORGE(Blocks.SMITHING_TABLE, 90),
        BREW(Blocks.BREWING_STAND, 80),
        SCRIBE(Blocks.LECTERN, 80);

        final Block station;
        final int duration;

        Kind(Block station, int duration) {
            this.station = station;
            this.duration = duration;
        }
    }

    /** How long the work waits for the player to close the trading screen (bought items). */
    private static final int MAX_WAIT_TICKS = 20 * 60;

    private static final class Job {
        final ServerLevel level;
        final Villager villager;
        final UUID player;
        final Kind kind;
        final List<ItemStack> props;
        final ItemStack result;
        /** The bought item still has to be taken back from the player (it went into his inventory with the trade). */
        boolean takeBack;
        boolean waiting;
        int waited;
        int age = -1;
        /** His job site block (null = none near: he works where he stands). */
        @Nullable
        BlockPos station;
        boolean located;
        int walked;
        Vec3 top;
        final List<ItemEntity> shown = new ArrayList<>();
        ItemStack heldBefore = ItemStack.EMPTY;

        Job(ServerLevel level, Villager villager, UUID player, Kind kind, List<ItemStack> props, ItemStack result,
            boolean bought) {
            this.level = level;
            this.villager = villager;
            this.player = player;
            this.kind = kind;
            this.props = props;
            this.result = result;
            this.takeBack = bought;
            this.waiting = bought;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();
    /** Finished items still lying on a job site: they sparkle until they are picked up. */
    private static final List<ItemEntity> READY = new ArrayList<>();
    /** Finished potions in a brewing stand: the stand sparkles until the potion is taken out. */
    private record ReadyStand(ServerLevel level, BlockPos pos, Item item) {
    }

    private static final List<ReadyStand> READY_STANDS = new ArrayList<>();
    /** Ticks a villager may need to walk to his job site before he works where he is. */
    private static final int MAX_WALK_TICKS = 20 * 12;
    private static final String TAG_STATION_ITEM = "incursion_station_item";
    private static final String DATA_OWNER = "IncursionOwner";
    private static final String DATA_STATION = "IncursionStation";

    private VillagerWorks() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(VillagerWorks::onServerTick);
        NeoForge.EVENT_BUS.addListener(VillagerWorks::onServerStopping);
        NeoForge.EVENT_BUS.addListener(VillagerWorks::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(VillagerWorks::onBreak);
    }

    public static boolean isBusy(Villager villager) {
        for (Job job : JOBS) {
            if (job.villager == villager) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts a work. {@code props} are laid on the job site while the villager works (shown only), {@code result} lies
     * there at the end. With {@code bought}, the result was just bought: it is taken back from the player first and the
     * work starts when the trading screen is closed.
     */
    public static void start(ServerLevel level, Villager villager, Player player, Kind kind, List<ItemStack> props,
                             ItemStack result, boolean bought) {
        JOBS.add(new Job(level, villager, player.getUUID(), kind, props, result, bought));
    }

    /**
     * The trade cannot be done after all (e.g. the material is missing): next tick the bought item is taken back and
     * what was paid in the screen is given back.
     */
    public static void refund(ServerLevel level, Player player, ItemStack bought, List<ItemStack> paid) {
        REFUNDS.add(new Refund(level, player.getUUID(), bought, paid));
    }

    private record Refund(ServerLevel level, UUID player, ItemStack bought, List<ItemStack> paid) {
    }

    private static final List<Refund> REFUNDS = new ArrayList<>();

    private static void runRefunds() {
        for (Refund refund : REFUNDS) {
            ServerPlayer player = refund.level().getServer().getPlayerList().getPlayer(refund.player());
            if (player == null || !takeBack(player, refund.bought().getItem())) {
                continue; // nothing to take back: nothing to give back
            }
            for (ItemStack stack : refund.paid()) {
                ItemStack copy = stack.copy();
                if (!player.getInventory().add(copy)) {
                    player.drop(copy, false);
                }
            }
        }
        REFUNDS.clear();
    }

    // ------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------

    private static void onServerTick(ServerTickEvent.Post event) {
        if (!REFUNDS.isEmpty()) {
            runRefunds();
        }
        if (!READY.isEmpty() || !READY_STANDS.isEmpty()) {
            sparkleReady();
        }
        if (JOBS.isEmpty()) {
            return;
        }
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            if (tick(it.next())) {
                it.remove();
            }
        }
    }

    /** Returns true when the job is over. */
    private static boolean tick(Job job) {
        Villager villager = job.villager;
        ServerPlayer player = job.level.getServer().getPlayerList().getPlayer(job.player);
        if (job.takeBack) {
            job.takeBack = false;
            if (player == null || !takeBack(player, job.result.getItem())) {
                return true; // the bought item is not there to take back: the player keeps it, no show
            }
        }
        if (!villager.isAlive() || villager.isRemoved() || villager.level() != job.level) {
            finish(job, false);
            return true;
        }
        if (job.waiting) {
            if (villager.isTrading() && ++job.waited < MAX_WAIT_TICKS) {
                return false;
            }
            job.waiting = false;
        }
        if (job.age < 0) {
            for (Job other : JOBS) {
                if (other != job && other.villager == villager && (other.age >= 0 || other.located)) {
                    return false; // one thing after the other (e.g. two potions bought at once)
                }
            }
            if (!job.located) {
                job.located = true;
                job.station = findStation(job.level, villager, job.kind.station);
            }
            // First he walks to his job site.
            if (job.station != null && job.walked < MAX_WALK_TICKS
                    && villager.distanceToSqr(Vec3.atBottomCenterOf(job.station)) > 2.3 * 2.3) {
                job.walked++;
                villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                        new net.minecraft.world.entity.ai.memory.WalkTarget(job.station, 0.6F, 1));
                villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET,
                        new net.minecraft.world.entity.ai.behavior.BlockPosTracker(job.station));
                return false;
            }
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            begin(job);
        }
        job.age++;
        villager.getNavigation().stop();
        villager.getLookControl().setLookAt(job.top.x, job.top.y, job.top.z);
        step(job);
        if (job.age >= job.kind.duration) {
            finish(job, true);
            return true;
        }
        return false;
    }

    private static void begin(Job job) {
        Villager villager = job.villager;
        BlockPos station = job.station;
        if (station != null && villager.distanceToSqr(Vec3.atBottomCenterOf(station)) > 4.0 * 4.0) {
            station = null; // he could not get there
            job.station = null;
        }
        if (station != null) {
            double height = job.kind == Kind.BREW ? 0.9 : 1.02;
            job.top = new Vec3(station.getX() + 0.5, station.getY() + height, station.getZ() + 0.5);
        } else {
            // No job site nearby: the villager works on the ground in front of him.
            Vec3 look = villager.getLookAngle().multiply(1.0, 0.0, 1.0);
            look = look.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : look.normalize();
            job.top = villager.position().add(look.scale(1.2)).add(0.0, 0.1, 0.0);
        }
        job.heldBefore = villager.getItemBySlot(EquipmentSlot.MAINHAND).copy();
        Item tool = switch (job.kind) {
            case FORGE -> Items.MACE;
            case BREW -> Items.BLAZE_ROD;
            case SCRIBE -> Items.FEATHER;
        };
        villager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(tool));
        int n = job.props.size();
        for (int i = 0; i < n; i++) {
            double offset = n == 1 ? 0.0 : (i - (n - 1) / 2.0) * 0.32;
            ItemEntity shown = new ItemEntity(job.level, job.top.x + offset, job.top.y, job.top.z, job.props.get(i).copy());
            shown.setDeltaMovement(Vec3.ZERO);
            shown.setNoGravity(true);
            shown.setNeverPickUp();
            shown.setUnlimitedLifetime();
            shown.setInvulnerable(true);
            shown.addTag("incursion_work_prop");
            job.level.addFreshEntity(shown);
            job.shown.add(shown);
        }
        job.level.playSound(null, BlockPos.containing(job.top), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.6F, 0.8F);
    }

    /** The show while the villager works. */
    private static void step(Job job) {
        ServerLevel level = job.level;
        Vec3 p = job.top;
        BlockPos at = BlockPos.containing(p);
        int a = job.age;
        switch (job.kind) {
            case FORGE -> {
                if (a == 20 || a == 36 || a == 52 || a == 68) {
                    // a hammer blow
                    level.playSound(null, at, SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.6F, 0.9F + 0.1F * (a / 16));
                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y + 0.1, p.z, 14, 0.15, 0.05, 0.15, 0.25);
                    level.sendParticles(ParticleTypes.CRIT, p.x, p.y + 0.1, p.z, 6, 0.1, 0.05, 0.1, 0.2);
                    for (ItemEntity shown : job.shown) {
                        shown.setPos(shown.getX(), p.y - 0.06, shown.getZ()); // the pieces jump under the blow
                    }
                } else if (a % 16 == 26 % 16) {
                    for (ItemEntity shown : job.shown) {
                        shown.setPos(shown.getX(), p.y, shown.getZ());
                    }
                }
                if (a == 60) {
                    // the two pieces come together
                    for (ItemEntity shown : job.shown) {
                        shown.setPos(p.x + (shown.getX() - p.x) * 0.3, p.y, p.z);
                    }
                }
            }
            case BREW -> {
                if (a % 6 == 0) {
                    level.sendParticles(ParticleTypes.BUBBLE_POP, p.x, p.y + 0.2, p.z, 3, 0.12, 0.1, 0.12, 0.02);
                }
                if (a % 10 == 0) {
                    level.sendParticles(ParticleTypes.SMALL_FLAME, p.x, p.y - 0.6, p.z, 2, 0.1, 0.02, 0.1, 0.01);
                }
                if (a == 10 || a == 45) {
                    level.playSound(null, at, SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.8F, 1.0F);
                }
                if (a == 30 || a == 60) {
                    level.playSound(null, at, SoundEvents.BLAZE_SHOOT, SoundSource.NEUTRAL, 0.25F, 1.6F);
                    level.sendParticles(ParticleTypes.FLAME, p.x, p.y + 0.1, p.z, 8, 0.1, 0.1, 0.1, 0.03);
                }
            }
            case SCRIBE -> {
                if (a % 3 == 0) {
                    // glyphs of the old script flow from the villager into the book
                    level.sendParticles(ParticleTypes.ENCHANT, p.x, p.y + 0.4, p.z, 6, 0.35, 0.3, 0.35, 0.8);
                }
                if (a == 12 || a == 32 || a == 52) {
                    level.playSound(null, at, SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1.0F, 1.0F);
                }
                if (a == 66) {
                    level.playSound(null, at, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.NEUTRAL, 0.8F, 0.8F);
                }
            }
        }
    }

    /** The work is done (or cut short): the props go, the item lies on the job site for its player. */
    private static void finish(Job job, boolean show) {
        for (ItemEntity shown : job.shown) {
            shown.discard();
        }
        job.shown.clear();
        Villager villager = job.villager;
        if (job.age >= 0 && villager.isAlive()) {
            villager.setItemSlot(EquipmentSlot.MAINHAND, job.heldBefore);
        }
        ServerLevel level = job.level;
        Vec3 p = job.top != null ? job.top : villager.position().add(0.0, 1.0, 0.0);
        if (show) {
            switch (job.kind) {
                case FORGE -> {
                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y + 0.2, p.z, 40, 0.2, 0.2, 0.2, 0.4);
                    level.sendParticles(ParticleTypes.END_ROD, p.x, p.y + 0.2, p.z, 8, 0.1, 0.1, 0.1, 0.08);
                    level.playSound(null, BlockPos.containing(p), SoundEvents.SMITHING_TABLE_USE, SoundSource.NEUTRAL, 1.0F, 0.8F);
                    level.playSound(null, BlockPos.containing(p), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1.0F, 0.6F);
                }
                case BREW -> {
                    level.sendParticles(ParticleTypes.WITCH, p.x, p.y + 0.3, p.z, 20, 0.15, 0.2, 0.15, 0.05);
                    level.playSound(null, BlockPos.containing(p), SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 1.0F, 0.8F);
                }
                case SCRIBE -> {
                    level.sendParticles(ParticleTypes.ENCHANT, p.x, p.y + 0.3, p.z, 40, 0.3, 0.3, 0.3, 1.2);
                    level.sendParticles(ParticleTypes.END_ROD, p.x, p.y + 0.3, p.z, 6, 0.1, 0.1, 0.1, 0.05);
                    level.playSound(null, BlockPos.containing(p), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1.0F, 0.9F);
                }
            }
        }
        if (!storeInStation(job, level, p)) {
            // no job site: it lies in front of him, only for its player
            ItemEntity item = new ItemEntity(level, p.x, p.y + 0.05, p.z, job.result.copy());
            item.setDeltaMovement(0.0, 0.18, 0.0);
            item.setUnlimitedLifetime();
            item.setInvulnerable(true);
            item.setTarget(job.player);
            item.setPickUpDelay(10);
            item.setGlowingTag(true);
            level.addFreshEntity(item);
            READY.add(item);
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(job.player);
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.incursion.villager.work_done",
                    job.result.getHoverName()).withStyle(ChatFormatting.GOLD), true);
        }
    }

    /** Every half second: a finished item that is still waiting on its job site sends up sparks. */
    private static void sparkleReady() {
        Iterator<ReadyStand> stands = READY_STANDS.iterator();
        while (stands.hasNext()) {
            ReadyStand ready = stands.next();
            if (ready.level().getGameTime() % 10 != 0) {
                continue;
            }
            boolean still = false;
            if (ready.level().isLoaded(ready.pos())
                    && ready.level().getBlockEntity(ready.pos()) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand) {
                for (int slot = 0; slot < 3; slot++) {
                    still |= stand.getItem(slot).is(ready.item());
                }
            }
            if (!still) {
                stands.remove();
                continue;
            }
            BlockPos at = ready.pos();
            ready.level().sendParticles(ParticleTypes.GLOW, at.getX() + 0.5, at.getY() + 0.8, at.getZ() + 0.5, 3, 0.25, 0.15, 0.25, 0.01);
            ready.level().sendParticles(ParticleTypes.END_ROD, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 1, 0.05, 0.05, 0.05, 0.03);
        }
        Iterator<ItemEntity> it = READY.iterator();
        while (it.hasNext()) {
            ItemEntity item = it.next();
            if (!item.isAlive() || item.isRemoved() || !(item.level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }
            if (level.getGameTime() % 10 != 0) {
                continue;
            }
            level.sendParticles(ParticleTypes.GLOW, item.getX(), item.getY() + 0.3, item.getZ(), 3, 0.25, 0.1, 0.25, 0.01);
            level.sendParticles(ParticleTypes.END_ROD, item.getX(), item.getY() + 0.4, item.getZ(), 1, 0.05, 0.05, 0.05, 0.03);
            if (level.getGameTime() % 100 == 0) {
                level.playSound(null, item.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.6F, 1.4F);
            }
        }
    }

    /**
     * The finished item goes into his job site: the potion into a free bottle place of the brewing stand (taken out
     * through its screen), the rune and the tool lie on the lectern or smithing table (right-click the block to take
     * them). Returns false if there is no job site to put it in.
     */
    private static boolean storeInStation(Job job, ServerLevel level, Vec3 p) {
        BlockPos station = job.station;
        if (station == null || !level.getBlockState(station).is(job.kind.station)) {
            return false;
        }
        if (job.kind == Kind.BREW) {
            if (level.getBlockEntity(station) instanceof net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand) {
                for (int slot = 0; slot < 3; slot++) {
                    if (stand.getItem(slot).isEmpty()) {
                        stand.setItem(slot, job.result.copy());
                        stand.setChanged();
                        READY_STANDS.add(new ReadyStand(level, station, job.result.getItem()));
                        return true;
                    }
                }
            }
            // all three bottle places are taken: it stands on top of the stand
        }
        ItemEntity shown = new ItemEntity(level, p.x, p.y, p.z, job.result.copy());
        shown.setDeltaMovement(Vec3.ZERO);
        shown.setNoGravity(true);
        shown.setNeverPickUp();
        shown.setUnlimitedLifetime();
        shown.setInvulnerable(true);
        shown.setGlowingTag(true);
        shown.addTag(TAG_STATION_ITEM);
        shown.getPersistentData().putUUID(DATA_OWNER, job.player);
        shown.getPersistentData().putLong(DATA_STATION, station.asLong());
        level.addFreshEntity(shown);
        READY.add(shown);
        return true;
    }

    /** The items lying on the job site at {@code pos}. */
    private static List<ItemEntity> stationItems(net.minecraft.world.level.Level level, BlockPos pos) {
        long key = pos.asLong();
        return level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(0.5, 1.0, 0.5),
                e -> e.getTags().contains(TAG_STATION_ITEM) && e.getPersistentData().getLong(DATA_STATION) == key);
    }

    /** Right-click on a lectern or smithing table with a finished item on it: the player takes it (no screen opens). */
    private static void onRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return;
        }
        List<ItemEntity> items = stationItems(event.getLevel(), event.getPos());
        if (items.isEmpty()) {
            return;
        }
        Player player = event.getEntity();
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        for (ItemEntity shown : items) {
            UUID owner = shown.getPersistentData().hasUUID(DATA_OWNER) ? shown.getPersistentData().getUUID(DATA_OWNER) : null;
            if (owner != null && !owner.equals(player.getUUID())) {
                player.displayClientMessage(Component.translatable("message.incursion.villager.not_yours")
                        .withStyle(ChatFormatting.GRAY), true);
                continue;
            }
            ItemStack stack = shown.getItem().copy();
            shown.discard();
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            event.getLevel().playSound(null, event.getPos(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 1.0F);
        }
    }

    /** The job site is broken: what lay on it falls down as a normal item. */
    private static void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        for (ItemEntity shown : stationItems(level, event.getPos())) {
            ItemEntity dropped = new ItemEntity(level, shown.getX(), shown.getY(), shown.getZ(), shown.getItem().copy());
            dropped.setUnlimitedLifetime();
            level.addFreshEntity(dropped);
            shown.discard();
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        for (Job job : JOBS) {
            if (!job.takeBack) {
                finish(job, false);
            }
        }
        JOBS.clear();
        READY.clear();
        READY_STANDS.clear();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The villager's job site of this kind if it is near, otherwise such a block right around him, or null. */
    @Nullable
    private static BlockPos findStation(ServerLevel level, Villager villager, Block block) {
        java.util.Optional<GlobalPos> site = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        if (site.isPresent() && site.get().dimension() == level.dimension()) {
            BlockPos pos = site.get().pos();
            if (pos.closerToCenterThan(villager.position(), 8.0) && level.getBlockState(pos).is(block)) {
                return pos;
            }
        }
        BlockPos center = villager.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -2, -4), center.offset(4, 2, 4))) {
            if (level.getBlockState(pos).is(block)) {
                double distance = pos.distToCenterSqr(villager.position());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    /**
     * Takes one {@code item} back from the player right after the trade: from a payment slot of the open menu, the
     * stack on the mouse cursor or the inventory. Returns false if it was not found.
     */
    private static boolean takeBack(ServerPlayer player, Item item) {
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.is(item)) {
            carried.shrink(1);
            player.containerMenu.broadcastChanges();
            return true;
        }
        for (Slot slot : player.containerMenu.slots) {
            if (slot instanceof MerchantResultSlot || !slot.hasItem() || !slot.getItem().is(item)) {
                continue;
            }
            slot.getItem().shrink(1);
            slot.setChanged();
            player.containerMenu.broadcastChanges();
            return true;
        }
        int index = player.getInventory().findSlotMatchingItem(new ItemStack(item));
        if (index >= 0) {
            player.getInventory().getItem(index).shrink(1);
            player.getInventory().setChanged();
            return true;
        }
        return false;
    }
}
