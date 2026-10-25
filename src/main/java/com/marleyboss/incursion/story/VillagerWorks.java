package com.marleyboss.incursion.story;

import com.mojang.math.Transformation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Villagers make the story items in front of the player, at their job site. They walk there first, then:
 * <ul>
 *     <li>{@link Kind#FORGE}: the Master Toolsmith lays the Upgrade Tool and the insignia flat on his smithing table and
 *     strikes them with his hammer until they are one; the new tool lies on the table.</li>
 *     <li>{@link Kind#BREW}: the cleric puts a bottle into his brewing stand and brews; the Mythic Potion then stands in
 *     the brewing stand (taken out through its screen).</li>
 *     <li>{@link Kind#SCRIBE}: the librarian lays a book on his lectern and writes with a quill; the glyphs of the old
 *     script rise from the pages, circle the lectern and fall back into it; the Core Rune lies on the lectern like a
 *     book.</li>
 * </ul>
 * Items on a block are display entities (they lie still, flat on the block). Right-clicking the lectern or smithing
 * table gives them to the player they were made for. If the work cannot finish (the villager dies or is unloaded, the
 * server stops), the item is handed out at once.
 */
public final class VillagerWorks {
    public enum Kind {
        FORGE(Blocks.SMITHING_TABLE, 100),
        BREW(Blocks.BREWING_STAND, 110),
        SCRIBE(Blocks.LECTERN, 160);

        final Block station;
        /** Length of the finishing show (ticks): the last seconds of every job. */
        final int duration;

        Kind(Block station, int duration) {
            this.station = station;
            this.duration = duration;
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Kind fromKey(String key) {
            for (Kind kind : values()) {
                if (kind.key().equals(key)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** Ticks a villager may need to walk to his job site before he works where he is. */
    private static final int MAX_WALK_TICKS = 20 * 12;
    private static final String TAG_STATION_ITEM = "incursion_station_item";
    private static final String TAG_PROP = "incursion_work_prop";
    private static final String DATA_OWNER = "IncursionOwner";
    private static final String DATA_STATION = "IncursionStation";
    private static final String DATA_ITEM = "IncursionItem";

    private static final class Job {
        final ServerLevel level;
        final Villager villager;
        final Kind kind;
        final List<ItemStack> props;
        int age = -1;
        @Nullable
        BlockPos station;
        boolean located;
        int walked;
        Vec3 top;
        /** Rotation of the items lying on the block (degrees). */
        float yaw;
        float pitch;
        final List<Display.ItemDisplay> shown = new ArrayList<>();
        /** Brewing: the bottle place the villager uses (-1 = none). */
        int bottleSlot = -1;
        /** Scribing: the villager laid a book on the lectern (taken away again at the end). */
        boolean bookLaid;
        ItemStack heldBefore = ItemStack.EMPTY;

        Job(ServerLevel level, Villager villager, Kind kind, List<ItemStack> props) {
            this.level = level;
            this.villager = villager;
            this.kind = kind;
            this.props = props;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();
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
     * The villager works on the order he holds (see StoryReady): he walks to his job site and works there until the
     * time of the order is over. {@code props} lie on the site while he works (shown only). Also used to go on after
     * the villager was unloaded.
     */
    public static void start(ServerLevel level, Villager villager, Kind kind, List<ItemStack> props) {
        if (!isBusy(villager)) {
            JOBS.add(new Job(level, villager, kind, props));
        }
    }

    // ------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------

    private static void onServerTick(ServerTickEvent.Post event) {
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
        if (!villager.isAlive() || villager.isRemoved() || villager.level() != job.level || !StoryReady.has(villager)) {
            finish(job, false); // gone, unloaded, or the order was taken away: the order itself stays as it is
            return true;
        }
        if (job.age < 0) {
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
                return StoryReady.done(villager) && fin(job);
            }
            villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            begin(job);
        }
        job.age++;
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getLookControl().setLookAt(job.top.x, job.top.y, job.top.z);
        // his tool in the hand (other behaviours of the villager would take it away)
        villager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(handTool(job.kind)));
        long remaining = StoryReady.end(villager) - job.level.getGameTime();
        if (remaining <= job.kind.duration) {
            step(job, (int) (job.kind.duration - Math.max(0, remaining))); // the finishing show
        } else {
            idle(job, job.age);
        }
        if (remaining <= 0) {
            finish(job, true);
            return true;
        }
        return false;
    }

    private static boolean fin(Job job) {
        finish(job, false);
        return true;
    }

    private static Item handTool(Kind kind) {
        return switch (kind) {
            case FORGE -> Items.MACE;
            case BREW -> Items.BLAZE_ROD;
            case SCRIBE -> Items.FEATHER;
        };
    }

    private static void begin(Job job) {
        Villager villager = job.villager;
        BlockPos station = job.station;
        if (station != null && villager.distanceToSqr(Vec3.atBottomCenterOf(station)) > 4.0 * 4.0) {
            station = null; // he could not get there
            job.station = null;
        }
        ServerLevel level = job.level;
        job.pitch = -90.0F; // lying flat
        if (station != null) {
            BlockState state = level.getBlockState(station);
            job.top = new Vec3(station.getX() + 0.5, station.getY() + 1.01, station.getZ() + 0.5);
            if (job.kind == Kind.SCRIBE && state.hasProperty(LecternBlock.FACING)) {
                // on the sloped desk of the lectern, turned to the one who reads
                Direction facing = state.getValue(LecternBlock.FACING);
                job.yaw = 180.0F - facing.toYRot();
                job.pitch = -67.5F;
                job.top = new Vec3(station.getX() + 0.5, station.getY() + 0.97, station.getZ() + 0.5);
            } else if (job.kind == Kind.BREW) {
                job.top = new Vec3(station.getX() + 0.5, station.getY() + 0.55, station.getZ() + 0.5);
            } else {
                job.yaw = 180.0F - villager.getYRot();
            }
        } else {
            // No job site nearby: the villager works on the ground in front of him.
            Vec3 look = villager.getLookAngle().multiply(1.0, 0.0, 1.0);
            look = look.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : look.normalize();
            job.top = villager.position().add(look.scale(1.2)).add(0.0, 0.02, 0.0);
        }
        job.heldBefore = villager.getItemBySlot(EquipmentSlot.MAINHAND).copy();
        switch (job.kind) {
            case BREW -> {
                // a bottle into the brewing stand: it is brewed into the potion there
                if (station != null && level.getBlockEntity(station) instanceof BrewingStandBlockEntity stand) {
                    for (int slot = 0; slot < 3; slot++) {
                        if (stand.getItem(slot).isEmpty()) {
                            stand.setItem(slot, new ItemStack(Items.GLASS_BOTTLE));
                            stand.setChanged();
                            job.bottleSlot = slot;
                            break;
                        }
                    }
                }
                if (job.bottleSlot < 0) {
                    showProps(job);
                }
            }
            case SCRIBE -> {
                // a book on the lectern (the vanilla lectern shows it)
                if (station != null) {
                    BlockState state = level.getBlockState(station);
                    if (state.getBlock() instanceof LecternBlock && !state.getValue(LecternBlock.HAS_BOOK)) {
                        job.bookLaid = LecternBlock.tryPlaceBook(villager, level, station, state,
                                new ItemStack(Items.WRITABLE_BOOK));
                    }
                }
                if (!job.bookLaid) {
                    showProps(job);
                }
            }
            case FORGE -> showProps(job);
        }
        level.playSound(null, BlockPos.containing(job.top), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.6F, 0.8F);
    }

    /** The pieces lie flat on the block, side by side. */
    private static void showProps(Job job) {
        int n = job.props.size();
        for (int i = 0; i < n; i++) {
            double offset = n == 1 ? 0.0 : (i - (n - 1) / 2.0) * 0.28;
            double yawRad = Math.toRadians(job.yaw);
            Vec3 at = job.top.add(Math.cos(yawRad) * offset, 0.0, Math.sin(yawRad) * offset);
            Display.ItemDisplay shown = display(job.level, at, job.props.get(i), job.yaw + i * 25.0F, job.pitch, 0.5F);
            if (shown != null) {
                shown.addTag(TAG_PROP);
                job.shown.add(shown);
            }
        }
    }

    /** The show while the villager works. */
    private static void step(Job job, int a) {
        ServerLevel level = job.level;
        Vec3 p = job.top;
        BlockPos at = BlockPos.containing(p);
        switch (job.kind) {
            case FORGE -> {
                if (a == 20 || a == 38 || a == 56 || a == 74) {
                    // a hammer blow: sparks, the pieces jump
                    level.playSound(null, at, SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.6F, 0.85F + 0.05F * (a / 18));
                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y + 0.08, p.z, 16, 0.15, 0.04, 0.15, 0.25);
                    level.sendParticles(ParticleTypes.CRIT, p.x, p.y + 0.08, p.z, 6, 0.1, 0.04, 0.1, 0.2);
                    lift(job, 0.06);
                } else if (a == 23 || a == 41 || a == 59 || a == 77) {
                    lift(job, 0.0);
                }
                if (a > 60 && a < 85) {
                    // the two pieces slide together
                    float k = (a - 60) / 25.0F;
                    int n = job.shown.size();
                    for (int i = 0; i < n; i++) {
                        double offset = n == 1 ? 0.0 : (i - (n - 1) / 2.0) * 0.28 * (1.0 - k);
                        double yawRad = Math.toRadians(job.yaw);
                        Display.ItemDisplay shown = job.shown.get(i);
                        shown.setPos(p.x + Math.cos(yawRad) * offset, shown.getY(), p.z + Math.sin(yawRad) * offset);
                    }
                }
            }
            case BREW -> {
                Vec3 neck = new Vec3(p.x, at.getY() + 0.85, p.z);
                if (a % 5 == 0) {
                    level.sendParticles(ParticleTypes.BUBBLE_POP, neck.x, neck.y, neck.z, 2, 0.15, 0.05, 0.15, 0.02);
                }
                if (a % 8 == 0) {
                    level.sendParticles(ParticleTypes.SMALL_FLAME, p.x, at.getY() + 0.15, p.z, 2, 0.08, 0.02, 0.08, 0.01);
                }
                if (a == 10 || a == 50) {
                    level.playSound(null, at, SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.9F, 0.9F);
                }
                if (a == 30 || a == 70) {
                    // he feeds the fire with the rod
                    level.playSound(null, at, SoundEvents.BLAZE_SHOOT, SoundSource.NEUTRAL, 0.25F, 1.6F);
                    level.sendParticles(ParticleTypes.FLAME, p.x, at.getY() + 0.2, p.z, 10, 0.1, 0.05, 0.1, 0.03);
                }
                if (a > 60 && a % 3 == 0) {
                    level.sendParticles(ParticleTypes.WITCH, neck.x, neck.y + 0.2, neck.z, 2, 0.15, 0.15, 0.15, 0.02);
                }
            }
            case SCRIBE -> scribe(job, level, p, at, a);
        }
    }

    /** The long part of the work: the same small movements again and again until the finishing show begins. */
    private static void idle(Job job, int a) {
        ServerLevel level = job.level;
        Vec3 p = job.top;
        BlockPos at = BlockPos.containing(p);
        switch (job.kind) {
            case FORGE -> {
                int t = a % 30;
                if (t == 0) {
                    level.playSound(null, at, SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.5F, 0.85F);
                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y + 0.08, p.z, 10, 0.15, 0.04, 0.15, 0.2);
                    lift(job, 0.06);
                } else if (t == 3) {
                    lift(job, 0.0);
                }
            }
            case BREW -> {
                if (a % 10 == 0) {
                    level.sendParticles(ParticleTypes.BUBBLE_POP, p.x, at.getY() + 0.85, p.z, 2, 0.15, 0.05, 0.15, 0.02);
                }
                if (a % 16 == 0) {
                    level.sendParticles(ParticleTypes.SMALL_FLAME, p.x, at.getY() + 0.15, p.z, 2, 0.08, 0.02, 0.08, 0.01);
                }
                if (a % 70 == 10) {
                    level.playSound(null, at, SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.7F, 0.9F);
                }
            }
            case SCRIBE -> {
                Vec3 head = job.villager.getEyePosition();
                if (a % 4 == 0) {
                    Vec3 d = p.subtract(head);
                    level.sendParticles(ParticleTypes.ENCHANT, head.x + d.x * 0.3, head.y + d.y * 0.3, head.z + d.z * 0.3, 3,
                            0.15, 0.15, 0.15, 0.6);
                }
                if (a % 50 == 5) {
                    level.playSound(null, at, SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.8F, 0.9F);
                }
            }
        }
    }

    /**
     * The rune is written: the librarian writes (quill, pages), then the glyphs rise from the book, circle the lectern
     * in a slowly closing ring and fall back into the book, which becomes the rune.
     */
    private static void scribe(Job job, ServerLevel level, Vec3 p, BlockPos at, int a) {
        Vec3 head = job.villager.getEyePosition();
        if (a < 60) {
            if (a % 3 == 0) {
                // the glyphs flow from the writer into the pages
                Vec3 d = p.subtract(head);
                level.sendParticles(ParticleTypes.ENCHANT, head.x + d.x * 0.3, head.y + d.y * 0.3, head.z + d.z * 0.3, 4,
                        0.15, 0.15, 0.15, 0.6);
            }
            if (a % 20 == 5) {
                level.playSound(null, at, SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1.0F, 0.9F + 0.1F * (a / 20));
            }
        } else if (a < 130) {
            // the ring of glyphs
            float k = (a - 60) / 70.0F;
            double radius = 1.3 * (1.0 - k) + 0.2;
            double height = 0.3 + 1.2 * Math.sin(k * Math.PI);
            for (int i = 0; i < 3; i++) {
                double ang = a * 0.25 + i * Math.PI * 2.0 / 3.0;
                level.sendParticles(ParticleTypes.ENCHANT, p.x + Math.cos(ang) * radius, p.y + height,
                        p.z + Math.sin(ang) * radius, 1, 0.0, 0.0, 0.0, 0.0);
                if (a % 4 == 0) {
                    level.sendParticles(ParticleTypes.END_ROD, p.x + Math.cos(ang) * radius, p.y + height,
                            p.z + Math.sin(ang) * radius, 1, 0.0, 0.0, 0.0, 0.0);
                }
            }
            if (a == 62) {
                level.playSound(null, at, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.NEUTRAL, 1.0F, 0.6F);
            }
            if (a % 25 == 0) {
                level.playSound(null, at, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.0F, 0.5F + k);
            }
        } else if (a == 135) {
            // everything falls back into the book: it is the rune now
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, p.x, p.y + 0.2, p.z, 60, 0.3, 0.3, 0.3, 0.05);
            level.playSound(null, at, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1.2F, 0.6F);
            level.playSound(null, at, SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, 0.6F, 1.4F);
        }
    }

    private static void lift(Job job, double dy) {
        for (Display.ItemDisplay shown : job.shown) {
            shown.setPos(shown.getX(), job.top.y + dy, shown.getZ());
        }
    }

    /** The work is done (or cut short): the props go, the villager keeps the item for its player (or it lies there). */
    private static void finish(Job job, boolean show) {
        for (Display.ItemDisplay shown : job.shown) {
            shown.discard();
        }
        job.shown.clear();
        ServerLevel level = job.level;
        Villager villager = job.villager;
        if (job.age >= 0 && villager.isAlive()) {
            villager.setItemSlot(EquipmentSlot.MAINHAND, job.heldBefore);
        }
        if (job.bookLaid && job.station != null && level.getBlockEntity(job.station) instanceof LecternBlockEntity lectern) {
            lectern.clearContent();
            BlockState state = level.getBlockState(job.station);
            if (state.getBlock() instanceof LecternBlock) {
                LecternBlock.resetBookState(null, level, job.station, state, false);
            }
        }
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
                    level.sendParticles(ParticleTypes.WITCH, p.x, p.y + 0.5, p.z, 20, 0.15, 0.2, 0.15, 0.05);
                    level.playSound(null, BlockPos.containing(p), SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 1.0F, 0.8F);
                }
                case SCRIBE -> level.sendParticles(ParticleTypes.END_ROD, p.x, p.y + 0.3, p.z, 6, 0.1, 0.1, 0.1, 0.05);
            }
        }
        // the bottle in the brewing stand goes again (the finished potions are kept by the villager)
        if (job.bottleSlot >= 0 && job.station != null
                && level.getBlockEntity(job.station) instanceof BrewingStandBlockEntity stand
                && stand.getItem(job.bottleSlot).is(Items.GLASS_BOTTLE)) {
            stand.setItem(job.bottleSlot, ItemStack.EMPTY);
            stand.setChanged();
        }
        if (show) {
            UUID owner = StoryReady.owner(villager);
            ServerPlayer player = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
            List<ItemStack> items = StoryReady.items(villager);
            if (player != null && !items.isEmpty()) {
                player.displayClientMessage(Component.translatable("message.incursion.villager.work_done",
                        items.get(0).getHoverName()).withStyle(ChatFormatting.GOLD), true);
            }
        }
    }

    /**
     * An item display entity (shown like in an item frame, does not move, cannot be picked up) at {@code at}, turned by
     * {@code yaw} and tilted by {@code pitch} (-90 = lying flat). Set up through its saved data: the setters are private.
     */
    @Nullable
    private static Display.ItemDisplay display(ServerLevel level, Vec3 at, ItemStack stack, float yaw, float pitch,
                                               float scale) {
        Display.ItemDisplay shown = EntityType.ITEM_DISPLAY.create(level);
        if (shown == null || stack.isEmpty()) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        shown.saveWithoutId(tag);
        tag.put("item", stack.copyWithCount(1).save(level.registryAccess()));
        tag.putString("item_display", "fixed");
        Quaternionf rotation = new Quaternionf().rotationYXZ((float) Math.toRadians(yaw), (float) Math.toRadians(pitch), 0.0F);
        Transformation transform = new Transformation(new Vector3f(), rotation, new Vector3f(scale, scale, scale),
                new Quaternionf());
        Transformation.EXTENDED_CODEC.encodeStart(NbtOps.INSTANCE, transform).result()
                .ifPresent(t -> tag.put("transformation", t));
        shown.load(tag);
        shown.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        level.addFreshEntity(shown);
        return shown;
    }

    /** The items lying on the job site at {@code pos}. */
    private static List<Display.ItemDisplay> stationItems(net.minecraft.world.level.Level level, BlockPos pos) {
        long key = pos.asLong();
        return level.getEntitiesOfClass(Display.ItemDisplay.class, new AABB(pos).inflate(0.5, 1.0, 0.5),
                e -> e.getTags().contains(TAG_STATION_ITEM) && e.getPersistentData().getLong(DATA_STATION) == key);
    }

    private static ItemStack storedItem(Display.ItemDisplay shown) {
        return ItemStack.parseOptional(shown.registryAccess(), shown.getPersistentData().getCompound(DATA_ITEM));
    }

    /** Right-click on a lectern or smithing table with a finished item on it: the player takes it (no screen opens). */
    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return;
        }
        List<Display.ItemDisplay> items = stationItems(event.getLevel(), event.getPos());
        if (items.isEmpty()) {
            return;
        }
        Player player = event.getEntity();
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        for (Display.ItemDisplay shown : items) {
            UUID owner = shown.getPersistentData().hasUUID(DATA_OWNER) ? shown.getPersistentData().getUUID(DATA_OWNER) : null;
            if (owner != null && !owner.equals(player.getUUID())) {
                player.displayClientMessage(Component.translatable("message.incursion.villager.not_yours")
                        .withStyle(ChatFormatting.GRAY), true);
                continue;
            }
            ItemStack stack = storedItem(shown);
            shown.discard();
            if (stack.isEmpty()) {
                continue;
            }
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            event.getLevel().playSound(null, event.getPos(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 1.0F);
        }
    }

    /** The job site is broken: what lay on it falls down as a normal item. */
    private static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        for (Display.ItemDisplay shown : stationItems(level, event.getPos())) {
            ItemStack stack = storedItem(shown);
            if (!stack.isEmpty()) {
                ItemEntity dropped = new ItemEntity(level, shown.getX(), shown.getY(), shown.getZ(), stack);
                dropped.setUnlimitedLifetime();
                level.addFreshEntity(dropped);
            }
            shown.discard();
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        for (Job job : JOBS) {
            finish(job, false);
        }
        JOBS.clear();
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
            if (pos.closerToCenterThan(villager.position(), 16.0) && level.getBlockState(pos).is(block)) {
                return pos;
            }
        }
        BlockPos center = villager.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-6, -2, -6), center.offset(6, 2, 6))) {
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
}
