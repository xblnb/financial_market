package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.Order;
import com.example.financialmarket.web.LocalHttpServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 下单请求（Client→Server，A 股交易链路）。
 * 服务端为唯一权威：复用 {@link MarketEngine#submitOrder} 的 A 股校验（一手整数倍/T+1/涨跌停/休市），
 * 与 HTTP POST /api/order 共用 {@link LocalHttpServer#orderValidationMessage} 校验（路径一致）。
 * 成功/失败经 TradingResultPacket 反馈，并回推 OrdersSync/PositionsSync/TradesSync 刷新交易面板。
 */
public record PlaceOrderPacket(String assetId, boolean isBuy, int quantity, double price) implements CustomPacketPayload {
    public static final Type<PlaceOrderPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "place_order"));

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }

    public void send() { PacketDistributor.sendToServer(this); }

    public static final StreamCodec<FriendlyByteBuf, PlaceOrderPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PlaceOrderPacket::assetId,
            ByteBufCodecs.BOOL, PlaceOrderPacket::isBuy,
            ByteBufCodecs.INT, PlaceOrderPacket::quantity,
            ByteBufCodecs.DOUBLE, PlaceOrderPacket::price,
            PlaceOrderPacket::new
    );

    public static void handle(PlaceOrderPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            MarketEngine engine = MarketAttachment.getEngine(player.serverLevel());
            if (engine == null) return;

            var snap = engine.getState().getAsset(packet.assetId());
            if (snap == null) {
                player.connection.send(new TradingResultPacket(false, "资产不存在", null));
                return;
            }
            // 市价（price<=0）回落现价限价（对齐 HTTP /api/order）
            double limitPrice = packet.price() > 0 ? packet.price() : snap.getLastPrice();
            // 复用 A 股预检（与 HTTP 路径一致）；最终权威仍为 engine.submitOrder
            String vmsg = LocalHttpServer.orderValidationMessage(engine, player, packet.assetId(), packet.isBuy(), packet.quantity(), limitPrice);
            if (vmsg != null) {
                player.connection.send(new TradingResultPacket(false, vmsg, null));
                return;
            }

            Order.Type orderType = packet.isBuy() ? Order.Type.LIMIT_BUY : Order.Type.LIMIT_SELL;
            Order order = new Order(player.getUUID(), packet.assetId(), orderType, packet.quantity(), limitPrice);
            engine.submitOrder(order);
            player.connection.send(new TradingResultPacket(true, "下单已提交", order.getOrderId().toString()));

            // 刷新交易面板（委托/持仓/成交）
            player.connection.send(OrdersSyncPacket.create(engine, player));
            player.connection.send(PositionsSyncPacket.create(engine, player));
            player.connection.send(TradesSyncPacket.create(engine, player));
        });
    }
}
