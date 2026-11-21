package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.Order;
import com.example.financialmarket.engine.OrderBook;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;

/**
 * 撤单请求（Client→Server，AS-3 §5.4）。
 * 服务端为唯一权威：仅撤销指定玩家自己的 resting 订单（OrderBook.cancelOrder 确定性移除），
 * 并回推 TradingResultPacket + OrdersSync/PositionsSync/TradesSync 刷新交易面板。
 * 语义与 HTTP POST /api/cancelOrder 一致。
 */
public record CancelOrderPacket(String orderId) implements CustomPacketPayload {
    public static final Type<CancelOrderPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "cancel_order"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public void send() { PacketDistributor.sendToServer(this); }

    public static final StreamCodec<FriendlyByteBuf, CancelOrderPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CancelOrderPacket::orderId,
            CancelOrderPacket::new
    );

    public static void handle(CancelOrderPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            MarketEngine engine = MarketAttachment.getEngine(player.serverLevel());
            if (engine == null) return;
            UUID orderUuid;
            try {
                orderUuid = UUID.fromString(packet.orderId);
            } catch (IllegalArgumentException e) {
                player.connection.send(new TradingResultPacket(false, "无效的订单号", null));
                return;
            }
            UUID pid = player.getUUID();
            boolean found = false;
            boolean cancelled = false;
            for (Map.Entry<String, OrderBook> e : engine.getOrderBooks().entrySet()) {
                for (Order o : e.getValue().getOrdersByPlayer(pid)) {
                    if (o.getOrderId().equals(orderUuid)) {
                        found = true;
                        if (e.getValue().cancelOrder(orderUuid)) cancelled = true;
                        break;
                    }
                }
            }
            if (found && cancelled) {
                player.connection.send(new TradingResultPacket(true, "撤单成功", packet.orderId));
            } else if (!found) {
                player.connection.send(new TradingResultPacket(false, "未找到该委托（可能已成交或非本人）", null));
            } else {
                player.connection.send(new TradingResultPacket(false, "撤单失败", null));
            }
            // 刷新交易面板（委托/持仓/成交）
            player.connection.send(OrdersSyncPacket.create(engine, player));
            player.connection.send(PositionsSyncPacket.create(engine, player));
            player.connection.send(TradesSyncPacket.create(engine, player));
        });
    }
}
