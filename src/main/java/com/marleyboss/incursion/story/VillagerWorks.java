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

    private VillagerWorks() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(VillagerWorks::onServerTick);
        NeoForge.EVENT_BUS.addListener(VillagerWorks::onServerStopping);
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

    // ------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------

    private static void onServerTick(ServerTickEvent.Post event) {
        if (!READY.isEmpty()) {
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
                if (other != job && other.villager == villager && other.age >= 0) {
                    return false; // one thing after the other (e.g. two potions bought at once)
                }
            }
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
        BlockPos station = findStation(job.level, villager, job.kind.station);
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
        ItemEntity item = new ItemEntity(level, p.x, p.y + 0.05, p.z, job.result.copy());
        item.setDeltaMovement(0.0, 0.18, 0.0);
        item.setUnlimitedLifetime();
        item.setInvulnerable(true);
        item.setTarget(job.player); // only the player it was made for can pick it up
        item.setPickUpDelay(10);
        item.setGlowingTag(true); // outline, visible through walls: it is not overlooked
        level.addFreshEntity(item);
        READY.add(item);
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(job.player);
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.incursion.villager.work_done",
                    job.result.getHoverName()).withStyle(ChatFormatting.GOLD), true);
        }
    }

    /** Every half second: a finished item that is still waiting on its job site sends up sparks. */
    private static void sparkleReady() {
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

    private static void onServerStopping(ServerStoppingEvent event) {
        for (Job job : JOBS) {
            if (!job.takeBack) {
                finish(job, false);
            }
        }
        JOBS.clear();
        READY.clear();
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
