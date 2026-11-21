package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.margin.MarginManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MarginOrderPacket(String assetId, int quantity, int leverage, boolean isShort) implements CustomPacketPayload {
    public static final Type<MarginOrderPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "margin_order"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, MarginOrderPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MarginOrderPacket::assetId,
            ByteBufCodecs.INT, MarginOrderPacket::quantity,
            ByteBufCodecs.INT, MarginOrderPacket::leverage,
            ByteBufCodecs.BOOL, MarginOrderPacket::isShort,
            MarginOrderPacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(MarginOrderPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                // t3：统一走 MarginManager.openPosition/closePosition（引擎单例，不再直接操作临时账户）
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    MarginManager mm = engine.getMarginManager();
                    if (packet.quantity() < 0) {
                        // 负数量 = 平仓 abs(quantity) 股（同一资产同方向）
                        double pnl = mm.closePosition(sp.getUUID(), packet.assetId(),
                                Math.abs(packet.quantity()), packet.isShort(), engine);
                        System.out.println("🔓 平仓 玩家=" + sp.getName().getString()
                                + " 资产=" + packet.assetId() + " pnl=" + String.format("%.2f", pnl));
                    } else {
                        MarginManager.OpenResult result = mm.openPosition(sp.getUUID(), packet.assetId(),
                                packet.quantity(), packet.leverage(), packet.isShort(), engine);
                        System.out.println("🔓 融资融券 玩家=" + sp.getName().getString()
                                + " 资产=" + packet.assetId() + " 开仓 result=" + result);
                    }
                }
            }
        });
    }
}