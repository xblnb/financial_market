package com.example.financialmarket.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;

public record OrderBookSnapshotPacket(String assetId, List<Level> bids, List<Level> asks) implements CustomPacketPayload {
    public record Level(double price, int quantity) {}

    public static final Type<OrderBookSnapshotPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "order_book_snapshot"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, OrderBookSnapshotPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.assetId);
                buf.writeVarInt(packet.bids.size());
                for (var level : packet.bids) {
                    buf.writeDouble(level.price());
                    buf.writeInt(level.quantity());
                }
                buf.writeVarInt(packet.asks.size());
                for (var level : packet.asks) {
                    buf.writeDouble(level.price());
                    buf.writeInt(level.quantity());
                }
            },
            buf -> {
                String assetId = buf.readUtf();
                int bidSize = buf.readVarInt();
                List<Level> bids = new ArrayList<>(bidSize);
                for (int i = 0; i < bidSize; i++) {
                    bids.add(new Level(buf.readDouble(), buf.readInt()));
                }
                int askSize = buf.readVarInt();
                List<Level> asks = new ArrayList<>(askSize);
                for (int i = 0; i < askSize; i++) {
                    asks.add(new Level(buf.readDouble(), buf.readInt()));
                }
                return new OrderBookSnapshotPacket(assetId, bids, asks);
            }
    );
}