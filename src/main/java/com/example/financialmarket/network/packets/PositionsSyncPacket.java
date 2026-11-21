package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.Holding;
import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.AssetSnapshot;
import com.example.financialmarket.engine.MarketEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 个人持仓列表（Server→Client，AS-3 §5.4 / §6 持仓 DTO）。
 * 服务端为唯一权威：读 PlayerEconomy.holdings + SettlementSystem（available/frozen）与现价。
 * 字段与 HTTP GET /api/positions 一致。
 */
public record PositionsSyncPacket(List<PositionEntry> positions) implements CustomPacketPayload {
    public static final Type<PositionsSyncPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "positions_sync"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public record PositionEntry(String assetId, String name, int quantity, int available, int frozen,
                                double avgCost, double lastPrice, double marketValue, double profit, double profitPct) {}

    /** 从引擎 + PlayerEconomy 构建指定玩家持仓（只读、确定性、无随机）。 */
    public static PositionsSyncPacket create(MarketEngine engine, ServerPlayer player) {
        List<PositionEntry> list = new ArrayList<>();
        if (engine == null || player == null) return new PositionsSyncPacket(list);
        PlayerEconomy eco = PlayerEconomy.get(player);
        if (eco == null) return new PositionsSyncPacket(list);
        java.util.UUID pid = player.getUUID();
        for (Holding h : eco.getHoldings().values()) {
            String assetId = h.getAssetId();
            AssetSnapshot snap = engine.getState().getAsset(assetId);
            double last = snap != null ? snap.getLastPrice() : h.getAvgCost();
            int available = engine.getSettlement().getAvailable(pid, assetId);
            int frozen = engine.getSettlement().getFrozen(pid, assetId);
            double marketValue = h.getQuantity() * last;
            double cost = h.getAvgCost() * h.getQuantity();
            double profit = marketValue - cost;
            double profitPct = cost > 0 ? profit / cost * 100.0 : 0.0;
            list.add(new PositionEntry(
                    assetId, assetId, h.getQuantity(), available, frozen,
                    h.getAvgCost(), last, marketValue, profit, profitPct
            ));
        }
        return new PositionsSyncPacket(list);
    }

    public static final StreamCodec<FriendlyByteBuf, PositionsSyncPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.positions.size());
                for (PositionEntry p : packet.positions) {
                    buf.writeUtf(p.assetId);
                    buf.writeUtf(p.name);
                    buf.writeInt(p.quantity);
                    buf.writeInt(p.available);
                    buf.writeInt(p.frozen);
                    buf.writeDouble(p.avgCost);
                    buf.writeDouble(p.lastPrice);
                    buf.writeDouble(p.marketValue);
                    buf.writeDouble(p.profit);
                    buf.writeDouble(p.profitPct);
                }
            },
            buf -> {
                int size = buf.readVarInt();
                List<PositionEntry> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(new PositionEntry(
                            buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readInt(), buf.readInt(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()
                    ));
                }
                return new PositionsSyncPacket(list);
            }
    );
}
