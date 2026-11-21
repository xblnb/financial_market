package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.banking.CurrencySystem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record WithdrawPacket(int itemCount) implements CustomPacketPayload {
    public static final Type<WithdrawPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "withdraw"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, WithdrawPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, WithdrawPacket::itemCount, WithdrawPacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(WithdrawPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                CurrencySystem.withdraw(sp, packet.itemCount());
                PlayerEconomy eco = PlayerEconomy.get(sp);
                sp.connection.send(new PortfolioSyncPacket(eco.getHoldings(), eco.getCash()));
            }
        });
    }
}