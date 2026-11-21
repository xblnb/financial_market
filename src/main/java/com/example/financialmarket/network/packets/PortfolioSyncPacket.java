package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.Holding;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public record PortfolioSyncPacket(Map<String, Holding> holdings, double cash) implements CustomPacketPayload {
    public static final Type<PortfolioSyncPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "portfolio_sync"));

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, PortfolioSyncPacket> STREAM_CODEC = StreamCodec.of(
            PortfolioSyncPacket::write, PortfolioSyncPacket::read
    );

    private static void write(FriendlyByteBuf buf, PortfolioSyncPacket packet) {
        buf.writeDouble(packet.cash);
        buf.writeVarInt(packet.holdings.size());
        for (var e : packet.holdings.entrySet()) {
            buf.writeUtf(e.getKey());
            Holding.writeToPacket(buf, e.getValue());
        }
    }

    private static PortfolioSyncPacket read(FriendlyByteBuf buf) {
        double cash = buf.readDouble();
        int size = buf.readVarInt();
        Map<String, Holding> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(buf.readUtf(), Holding.readFromPacket(buf));
        }
        return new PortfolioSyncPacket(map, cash);
    }
}