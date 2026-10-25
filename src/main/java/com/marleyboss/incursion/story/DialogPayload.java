package com.marleyboss.incursion.story;

import com.marleyboss.incursion.Incursion;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Server to client: what a keeper villager says, shown in a panel beside his trading screen (the villager's model and
 * a text box). {@code lines} are translation keys, shown one after the other with a "Next" button.
 */
public record DialogPayload(int entityId, List<String> lines) implements CustomPacketPayload {
    public static final Type<DialogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "villager_dialog"));

    public static final StreamCodec<ByteBuf, DialogPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DialogPayload::entityId,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), DialogPayload::lines,
            DialogPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Mod bus: registers the payload (the client side shows it, the server only sends it). */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(TYPE, STREAM_CODEC, DialogPayload::handle);
    }

    private static void handle(DialogPayload payload, IPayloadContext context) {
        // The lambda body is only linked when it runs, which is on the client: the client class is never loaded on a server.
        context.enqueueWork(() -> com.marleyboss.incursion.client.VillagerDialog.receive(payload.entityId(), payload.lines()));
    }

    public static void send(ServerPlayer player, int entityId, List<String> lines) {
        PacketDistributor.sendToPlayer(player, new DialogPayload(entityId, lines));
    }
}
