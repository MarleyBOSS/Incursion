package com.marleyboss.incursion.story;

import com.marleyboss.incursion.Incursion;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: the icon of commission {@code index} beside the vanilla trading screen was clicked. */
public record OpenTradePayload(int entityId, int index) implements CustomPacketPayload {
    public static final Type<OpenTradePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "open_story_trade"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTradePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenTradePayload::entityId,
            ByteBufCodecs.VAR_INT, OpenTradePayload::index,
            OpenTradePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void handle(OpenTradePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StoryEvents.openTrade(player, payload.entityId(), payload.index());
            }
        });
    }

    /** Client side. */
    public static void send(int entityId, int index) {
        PacketDistributor.sendToServer(new OpenTradePayload(entityId, index));
    }
}
