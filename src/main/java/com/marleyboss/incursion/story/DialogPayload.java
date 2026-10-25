package com.marleyboss.incursion.story;

import com.marleyboss.incursion.Incursion;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
 * Server to client, right before a keeper's trading screen opens: what he says ({@code lines}, translation keys, shown
 * in the panel beside the screen) and the commissions he takes ({@code offers}, shown as icons to the right of the
 * screen; a click opens the mod's own trading window).
 */
public record DialogPayload(int entityId, List<String> lines, List<StoryOffer> offers) implements CustomPacketPayload {
    public static final Type<DialogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Incursion.MODID, "villager_dialog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DialogPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DialogPayload::entityId,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), DialogPayload::lines,
            StoryOffer.STREAM_CODEC.apply(ByteBufCodecs.list()), DialogPayload::offers,
            DialogPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Mod bus: registers the story payloads (dialog to the client, orders and "back" to the server). */
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2").optional();
        registrar.playToClient(TYPE, STREAM_CODEC, DialogPayload::handle);
        registrar.playToServer(OrderPayload.TYPE, OrderPayload.STREAM_CODEC, OrderPayload::handle);
        registrar.playToServer(ReopenPayload.TYPE, ReopenPayload.STREAM_CODEC, ReopenPayload::handle);
    }

    private static void handle(DialogPayload payload, IPayloadContext context) {
        // The lambda body is only linked when it runs, which is on the client: the client class is never loaded on a server.
        context.enqueueWork(() -> com.marleyboss.incursion.client.VillagerDialog.receive(payload.entityId(),
                payload.lines(), payload.offers()));
    }

    public static void send(ServerPlayer player, int entityId, List<String> lines, List<StoryOffer> offers) {
        PacketDistributor.sendToPlayer(player, new DialogPayload(entityId, lines, offers));
    }
}
