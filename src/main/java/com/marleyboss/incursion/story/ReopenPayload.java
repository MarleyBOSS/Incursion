package com.marleyboss.incursion.story;

import com.marleyboss.incursion.Incursion;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: "back" from the mod's trading window to the villager's normal trading screen. */
public record ReopenPayload(int entityId) implements CustomPacketPayload {
    public static final Type<ReopenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "villager_reopen"));

    public static final StreamCodec<ByteBuf, ReopenPayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(ReopenPayload::new, ReopenPayload::entityId);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void handle(ReopenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StoryEvents.reopen(player, payload.entityId());
            }
        });
    }

    /** Client side. */
    public static void send(int entityId) {
        PacketDistributor.sendToServer(new ReopenPayload(entityId));
    }
}
