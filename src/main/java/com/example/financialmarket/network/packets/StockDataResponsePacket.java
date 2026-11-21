package com.example.financialmarket.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

public record StockDataResponsePacket(String assetId, List<Map<String, Object>> klines, List<Map<String, Object>> tradePoints)
        implements CustomPacketPayload {

    public static final Type<StockDataResponsePacket> ID =
            new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "stock_data_response"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public static final StreamCodec<FriendlyByteBuf, StockDataResponsePacket> STREAM_CODEC = StreamCodec.of(
            StockDataResponsePacket::write, StockDataResponsePacket::read
    );

    private static void write(FriendlyByteBuf buf, StockDataResponsePacket packet) {
        // assetId
        buf.writeUtf(packet.assetId() == null ? "" : packet.assetId());

        // klines: List<Map>
        List<Map<String, Object>> klines = packet.klines();
        if (klines == null) {
            buf.writeVarInt(0);
        } else {
            buf.writeVarInt(klines.size());
            for (Map<String, Object> map : klines) {
                writeMap(buf, map);
            }
        }

        // tradePoints: List<Map>
        List<Map<String, Object>> points = packet.tradePoints();
        if (points == null) {
            buf.writeVarInt(0);
        } else {
            buf.writeVarInt(points.size());
            for (Map<String, Object> map : points) {
                writeMap(buf, map);
            }
        }
    }

    private static void writeMap(FriendlyByteBuf buf, Map<String, Object> map) {
        // 确保所有值非 null
        double open = asDouble(map.get("open"));
        double high = asDouble(map.get("high"));
        double low = asDouble(map.get("low"));
        double close = asDouble(map.get("close"));
        long volume = asLong(map.get("volume"));
        buf.writeDouble(open);
        buf.writeDouble(high);
        buf.writeDouble(low);
        buf.writeDouble(close);
        buf.writeVarLong(volume);
        // 如果有其他字段可以扩展
    }

    private static StockDataResponsePacket read(FriendlyByteBuf buf) {
        String assetId = buf.readUtf();

        int kSize = buf.readVarInt();
        List<Map<String, Object>> klines = new ArrayList<>(kSize);
        for (int i = 0; i < kSize; i++) {
            klines.add(readMap(buf));
        }

        int pSize = buf.readVarInt();
        List<Map<String, Object>> points = new ArrayList<>(pSize);
        for (int i = 0; i < pSize; i++) {
            points.add(readMap(buf));
        }

        return new StockDataResponsePacket(assetId, klines, points);
    }

    private static Map<String, Object> readMap(FriendlyByteBuf buf) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("open", buf.readDouble());
        map.put("high", buf.readDouble());
        map.put("low", buf.readDouble());
        map.put("close", buf.readDouble());
        map.put("volume", buf.readVarLong());
        return map;
    }

    private static double asDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return 0.0;
    }

    private static long asLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        return 0L;
    }
}