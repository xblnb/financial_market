package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.OHLCData;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;

public record RequestKlinePacket(String assetId, int klineType) implements CustomPacketPayload {
    public static final Type<RequestKlinePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_kline"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestKlinePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RequestKlinePacket::assetId,
            ByteBufCodecs.INT, RequestKlinePacket::klineType,
            RequestKlinePacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public void send() {
        PacketDistributor.sendToServer(this);
    }

    public static void handle(RequestKlinePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (packet.assetId() == null || packet.assetId().isEmpty()) {
                System.out.println("⚠️ RequestKlinePacket 收到无效 assetId，忽略");
                return;
            }
            ServerPlayer player = (ServerPlayer) context.player();
            MarketEngine engine = MarketAttachment.getEngine(player.serverLevel());
            if (engine == null) return;

            PriceDiscoveryEngine pe = engine.getPriceEngine();
            PriceDiscoveryEngine.KlineType[] types = PriceDiscoveryEngine.KlineType.values();
            PriceDiscoveryEngine.KlineType ktype = (packet.klineType() >= 0 && packet.klineType() < types.length) ?
                    types[packet.klineType()] : PriceDiscoveryEngine.KlineType.DAILY;

            List<OHLCData> klines = pe.getKlines(packet.assetId(), ktype, 60);
            List<PriceDiscoveryEngine.TradePoint> points = pe.getTradePoints(packet.assetId());

            // 转换为 Map
            List<Map<String, Object>> klinesMap = new ArrayList<>();
            List<OHLCData> klinesList = (klines != null) ? klines : new ArrayList<>();
            for (OHLCData k : klinesList) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("open", k.getOpen());
                map.put("high", k.getHigh());
                map.put("low", k.getLow());
                map.put("close", k.getClose());
                map.put("volume", k.getVolume());
                klinesMap.add(map);
            }

            List<Map<String, Object>> pointsMap = new ArrayList<>();
            List<PriceDiscoveryEngine.TradePoint> pointsList = (points != null) ? points : new ArrayList<>();
            for (PriceDiscoveryEngine.TradePoint p : pointsList) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("price", p.price());
                map.put("quantity", p.quantity());
                map.put("isBuy", p.isBuy());
                pointsMap.add(map);
            }

            player.connection.send(new StockDataResponsePacket(packet.assetId(), klinesMap, pointsMap));
        });
    }
}