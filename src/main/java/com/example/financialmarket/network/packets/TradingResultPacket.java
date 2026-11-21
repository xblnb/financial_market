package com.example.financialmarket.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 下单/撤单结果反馈（Server→Client，AS-3 §5.2 updateTradingResult 语义）。
 * success + 中文 message（休市/涨跌停/T+1/一手等），orderId 可为 null。
 */
public record TradingResultPacket(boolean success, String message, String orderId) implements CustomPacketPayload {
    public static final Type<TradingResultPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "trading_result"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, TradingResultPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.success);
                buf.writeUtf(packet.message == null ? "" : packet.message);
                boolean hasOrder = packet.orderId != null;
                buf.writeBoolean(hasOrder);
                if (hasOrder) buf.writeUtf(packet.orderId);
            },
            buf -> {
                boolean success = buf.readBoolean();
                String message = buf.readUtf();
                String orderId = buf.readBoolean() ? buf.readUtf() : null;
                return new TradingResultPacket(success, message, orderId);
            }
    );
}
