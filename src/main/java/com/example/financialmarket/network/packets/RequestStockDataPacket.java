package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.OHLCData;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;

public record RequestStockDataPacket(String assetId) implements CustomPacketPayload {
    public static final Type<RequestStockDataPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_stock"));

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, RequestStockDataPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RequestStockDataPacket::assetId,
            RequestStockDataPacket::new
    );

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(RequestStockDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                String assetId = packet.assetId();
                if (assetId == null || assetId.isEmpty()) {
                    System.err.println("❌ 请求股票数据时 assetId 为空，忽略");
                    return;
                }
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine == null) return;
                PriceDiscoveryEngine pe = engine.getPriceEngine();
                List<OHLCData> klines = pe.getAggregatedKlines(assetId, PriceDiscoveryEngine.KlineType.DAILY, 60);
                if (klines == null) klines = new ArrayList<>();
                List<Map<String, Object>> klinesMap = new ArrayList<>();
                for (OHLCData k : klines) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("open", k.getOpen());
                    map.put("high", k.getHigh());
                    map.put("low", k.getLow());
                    map.put("close", k.getClose());
                    map.put("volume", k.getVolume());
                    klinesMap.add(map);
                }
                sp.connection.send(new StockDataResponsePacket(assetId, klinesMap, new ArrayList<>()));
                System.out.println("📤 发送股票数据响应，资产: " + assetId);
            }
        });
    }
}