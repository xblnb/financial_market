package com.example.financialmarket.network.packets;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.Order;
import com.example.financialmarket.engine.OrderBook;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 个人委托列表（Server→Client，AS-3 §5.4 / §6 委托 DTO）。
 * 服务端为唯一权威：仅返回该玩家当前 resting（未成交/部分成交）真实订单。
 * 前端展示字段与 HTTP GET /api/orders 一致（前后端共用同一 DTO 语义）。
 */
public record OrdersSyncPacket(List<OrderEntry> orders) implements CustomPacketPayload {
    public static final Type<OrdersSyncPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "orders_sync"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public record OrderEntry(String orderId, String assetId, String name, String side,
                             String type, double price, int quantity, int remaining,
                             String status, long time) {}

    /** 从引擎订单簿构建指定玩家的委托列表（只读、确定性、无随机）。 */
    public static OrdersSyncPacket create(MarketEngine engine, ServerPlayer player) {
        List<OrderEntry> list = new ArrayList<>();
        if (engine == null || player == null) return new OrdersSyncPacket(list);
        java.util.UUID pid = player.getUUID();
        for (Map.Entry<String, OrderBook> e : engine.getOrderBooks().entrySet()) {
            for (Order o : e.getValue().getOrdersByPlayer(pid)) {
                String status = o.isFilled() ? "filled"
                        : (o.getRemainingQuantity() < o.getTotalQuantity() ? "partial" : "open");
                list.add(new OrderEntry(
                        o.getOrderId().toString(),
                        o.getAssetId(),
                        o.getAssetId(),
                        o.isBuy() ? "buy" : "sell",
                        o.isMarketOrder() ? "market" : "limit",
                        o.getLimitPrice(),
                        o.getTotalQuantity(),
                        o.getRemainingQuantity(),
                        status,
                        o.getTimestamp()
                ));
            }
        }
        return new OrdersSyncPacket(list);
    }

    public static final StreamCodec<FriendlyByteBuf, OrdersSyncPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.orders.size());
                for (OrderEntry o : packet.orders) {
                    buf.writeUtf(o.orderId);
                    buf.writeUtf(o.assetId);
                    buf.writeUtf(o.name);
                    buf.writeUtf(o.side);
                    buf.writeUtf(o.type);
                    buf.writeDouble(o.price);
                    buf.writeInt(o.quantity);
                    buf.writeInt(o.remaining);
                    buf.writeUtf(o.status);
                    buf.writeVarLong(o.time);
                }
            },
            buf -> {
                int size = buf.readVarInt();
                List<OrderEntry> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(new OrderEntry(
                            buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                            buf.readDouble(), buf.readInt(), buf.readInt(), buf.readUtf(), buf.readVarLong()
                    ));
                }
                return new OrdersSyncPacket(list);
            }
    );
}
