package com.marleyboss.incursion.fallout;

import com.marleyboss.incursion.Config;
import com.marleyboss.incursion.Incursion;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** The big explosion of an active Core and the contaminated zone it leaves behind. */
public final class Fallout {
    /** Keeps the explosion area loaded while the crater is carved. Tickets are re-added after a restart. */
    public static final TicketController TICKETS = new TicketController(
            ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "explosion"),
            (level, helper) -> {
                List<BlockPos> owners = new ArrayList<>(helper.getBlockTickets().keySet());
                for (BlockPos owner : owners) {
                    helper.removeAllTickets(owner);
                }
            });

    private Fallout() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(Fallout::onLevelTick);
    }

    public static void registerTickets(RegisterTicketControllersEvent event) {
        event.register(TICKETS);
    }

    /** Starts the big explosion of a Core with the given level at {@code pos}. */
    public static void detonate(ServerLevel level, BlockPos pos, int coreLevel) {
        int crater = Config.craterRadius(Math.max(1, coreLevel));
        boolean contaminate = Config.CONTAMINATION_ENABLED.get();
        int contamination = contaminate ? Config.contaminationRadius(crater) : 0;
        FalloutData data = FalloutData.get(level.getServer());
        data.addJob(new CraterJob(level.dimension(), pos, crater, contamination, level.getRandom().nextLong()));
        if (contaminate) {
            data.addZone(new FalloutData.Zone(level.dimension(), pos.immutable(), contamination, level.getGameTime()));
        }
        Incursion.LOGGER.info("Incursion Core exploded at {} in {} (crater radius {}, contamination radius {})",
                pos, level.dimension().location(), crater, contamination);
        level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.incursion.core.exploded", pos.getX(), pos.getY(), pos.getZ())
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
    }

    static void setTickets(ServerLevel level, CraterJob job, boolean add) {
        int r = job.areaRadius();
        BlockPos center = job.center;
        for (int cx = (center.getX() - r) >> 4; cx <= (center.getX() + r) >> 4; cx++) {
            for (int cz = (center.getZ() - r) >> 4; cz <= (center.getZ() + r) >> 4; cz++) {
                TICKETS.forceChunk(level, center, cx, cz, add, true);
            }
        }
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FalloutData data = FalloutData.get(level.getServer());

        if (!data.jobs().isEmpty()) {
            boolean worked = false;
            // Copy: carving blocks could in theory start another explosion while we iterate.
            for (CraterJob job : new ArrayList<>(data.jobs())) {
                if (!job.dimension.equals(level.dimension())) {
                    continue;
                }
                worked = true;
                if (job.tick(level)) {
                    data.jobs().remove(job);
                }
            }
            if (worked) {
                data.setDirty();
            }
        }

        if (level.getGameTime() % 20 == 0 && !data.zones().isEmpty()) {
            tickZones(level, data);
        }
    }

    /** Once per second: harm creatures in contaminated zones and let ash fall around players. */
    private static void tickZones(ServerLevel level, FalloutData data) {
        int minutes = Config.CONTAMINATION_MINUTES.get();
        int witherLevel = Config.CONTAMINATION_WITHER_LEVEL.get();
        long now = level.getGameTime();
        Iterator<FalloutData.Zone> zones = data.zones().iterator();
        while (zones.hasNext()) {
            FalloutData.Zone zone = zones.next();
            if (!zone.dimension().equals(level.dimension())) {
                continue;
            }
            if (minutes >= 0 && now - zone.createdAt() > minutes * 1200L) {
                zones.remove();
                data.setDirty();
                continue;
            }
            if (!Config.CONTAMINATION_ENABLED.get()) {
                continue;
            }
            BlockPos c = zone.center();
            int r = zone.radius();
            AABB box = new AABB(c.getX() - r, level.getMinBuildHeight(), c.getZ() - r,
                    c.getX() + r + 1, level.getMaxBuildHeight(), c.getZ() + r + 1);
            List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, box,
                    entity -> entity.isAlive() && zone.contains(entity.getX(), entity.getZ()));
            for (LivingEntity entity : victims) {
                boolean immunePlayer = entity instanceof Player player && (player.isCreative() || player.isSpectator());
                if (witherLevel > 0 && !immunePlayer) {
                    entity.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, witherLevel - 1, false, true));
                }
                if (entity instanceof ServerPlayer player) {
                    level.sendParticles(player, ParticleTypes.WHITE_ASH, false, player.getX(), player.getY() + 2.0,
                            player.getZ(), 60, 6.0, 3.0, 6.0, 0.0);
                    if (now % 60 == 0) {
                        player.displayClientMessage(Component.translatable("message.incursion.contaminated")
                                .withStyle(ChatFormatting.DARK_GREEN), true);
                    }
                }
            }
        }
    }
}
