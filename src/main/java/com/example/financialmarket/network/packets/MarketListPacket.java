package com.example.financialmarket.network.packets;

import com.example.financialmarket.engine.AssetSnapshot;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.ai.AICompanyData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public record MarketListPacket(List<StockInfo> stocks) implements CustomPacketPayload {
    public static final Type<MarketListPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "market_list"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public record StockInfo(String stockId, String usCode, String cnCode, String name, double price, double change, long volume) {}

    public static MarketListPacket create(MarketEngine engine) {
        List<StockInfo> list = new ArrayList<>();
        var state = engine.getState();
        var aiMgr = engine.getAiCompanyManager();
        for (String id : state.getAssetIds()) {
            AssetSnapshot snap = state.getAsset(id);
            if (snap == null) continue;
            // 查找对应的 AICompanyData 获取名称
            String name = id;
            for (AICompanyData c : aiMgr.getAll()) {
                if (c.getStockId().equals(id)) {
                    name = c.getName();
                    break;
                }
            }
            list.add(new StockInfo(id, "", "", name, snap.getLastPrice(), snap.getDailyChange(), snap.getVolume()));
        }
        return new MarketListPacket(list);
    }

    public static final StreamCodec<FriendlyByteBuf, MarketListPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.stocks.size());
                for (StockInfo info : packet.stocks) {
                    buf.writeUtf(info.stockId);
                    buf.writeUtf(info.usCode);
                    buf.writeUtf(info.cnCode);
                    buf.writeUtf(info.name);
                    buf.writeDouble(info.price);
                    buf.writeDouble(info.change);
                    buf.writeVarLong(info.volume);
                }
            },
            buf -> {
                int size = buf.readVarInt();
                List<StockInfo> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(new StockInfo(
                            buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                            buf.readDouble(), buf.readDouble(), buf.readVarLong()
                    ));
                }
                return new MarketListPacket(list);
            }
    );
}