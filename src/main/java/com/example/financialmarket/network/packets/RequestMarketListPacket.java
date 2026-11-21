package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestMarketListPacket() implements CustomPacketPayload {
    public static final Type<RequestMarketListPacket> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_market_list"));

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestMarketListPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestMarketListPacket());

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(RequestMarketListPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    sp.connection.send(MarketListPacket.create(engine));
                }
            }
        });
    }
}