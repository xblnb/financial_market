package com.example.financialmarket.network.packets;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.Trade;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 个人成交回报（Server→Client，AS-3 §5.4 / §6 成交 DTO）。
 * 服务端为唯一权威：仅返回买/卖方为指定玩家的真实成交（合成流跳过）。
 * 字段与 HTTP GET /api/myTrades 一致。
 */
public record TradesSyncPacket(List<TradeEntry> trades) implements CustomPacketPayload {
    public static final Type<TradesSyncPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "trades_sync"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public record TradeEntry(long time, String assetId, String name, String side, double price, int quantity) {}

    /** 从引擎成交历史构建指定玩家的成交明细（只读、确定性、无随机）。 */
    public static TradesSyncPacket create(MarketEngine engine, ServerPlayer player) {
        List<TradeEntry> list = new ArrayList<>();
        if (engine == null || player == null) return new TradesSyncPacket(list);
        UUID pid = player.getUUID();
        for (Map.Entry<String, List<Trade>> e : engine.getTradeHistory().entrySet()) {
            for (Trade t : e.getValue()) {
                boolean isBuy = pid.equals(t.getBuyerPlayerId());
                if (!isBuy && !pid.equals(t.getSellerPlayerId())) continue;
                list.add(new TradeEntry(
                        t.getTimestamp(),
                        e.getKey(),
                        e.getKey(),
                        isBuy ? "buy" : "sell",
                        t.getPrice(),
                        t.getQuantity()
                ));
            }
        }
        return new TradesSyncPacket(list);
    }

    public static final StreamCodec<FriendlyByteBuf, TradesSyncPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.trades.size());
                for (TradeEntry t : packet.trades) {
                    buf.writeVarLong(t.time);
                    buf.writeUtf(t.assetId);
                    buf.writeUtf(t.name);
                    buf.writeUtf(t.side);
                    buf.writeDouble(t.price);
                    buf.writeInt(t.quantity);
                }
            },
            buf -> {
                int size = buf.readVarInt();
                List<TradeEntry> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(new TradeEntry(
                            buf.readVarLong(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                            buf.readDouble(), buf.readInt()
                    ));
                }
                return new TradesSyncPacket(list);
            }
    );
}
