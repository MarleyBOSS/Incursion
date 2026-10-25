package com.marleyboss.incursion.story;

import com.marleyboss.incursion.Incursion;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import io.netty.buffer.ByteBuf;

/** Client to server: the player commissions offer {@code key} from the villager {@code entityId}. */
public record OrderPayload(int entityId, String key) implements CustomPacketPayload {
    public static final Type<OrderPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "villager_order"));

    public static final StreamCodec<ByteBuf, OrderPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OrderPayload::entityId,
            ByteBufCodecs.STRING_UTF8, OrderPayload::key,
            OrderPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void handle(OrderPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StoryEvents.order(player, payload.entityId(), payload.key());
            }
        });
    }

    /** Client side. */
    public static void send(int entityId, String key) {
        PacketDistributor.sendToServer(new OrderPayload(entityId, key));
    }
}
