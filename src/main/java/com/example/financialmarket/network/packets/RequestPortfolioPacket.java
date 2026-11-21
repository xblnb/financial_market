package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.PlayerEconomy;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestPortfolioPacket() implements CustomPacketPayload {
    public static final Type<RequestPortfolioPacket> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_portfolio"));

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestPortfolioPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestPortfolioPacket());

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(RequestPortfolioPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                PlayerEconomy eco = PlayerEconomy.get(sp);
                sp.connection.send(new PortfolioSyncPacket(eco.getHoldings(), eco.getCash()));
            }
        });
    }
}