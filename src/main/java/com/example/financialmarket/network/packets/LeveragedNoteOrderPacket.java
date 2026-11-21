package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.leverage.LeveragedNoteMarket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端→服务端：杠杆票据下单（买入/卖出，AS-L1 §6.5）。
 * 服务端绑定玩家身份（context.player()）调用 {@code engine.getLeverageMarket().buy/sell(...)}。
 * 确定性记账、无随机。
 */
public record LeveragedNoteOrderPacket(String noteId, String action, int units) implements CustomPacketPayload {
    public static final Type<LeveragedNoteOrderPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "leverage_order"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, LeveragedNoteOrderPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LeveragedNoteOrderPacket::noteId,
            ByteBufCodecs.STRING_UTF8, LeveragedNoteOrderPacket::action,
            ByteBufCodecs.VAR_INT, LeveragedNoteOrderPacket::units,
            LeveragedNoteOrderPacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(LeveragedNoteOrderPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine == null || !engine.isLeverageEnabled()) return;
                LeveragedNoteMarket lm = engine.getLeverageMarket();
                // 兼容 open→buy / close→sell（评审 F1/F2）
                boolean isBuy = "buy".equalsIgnoreCase(packet.action()) || "open".equalsIgnoreCase(packet.action());
                LeveragedNoteMarket.IssueResult res = isBuy
                        ? lm.buy(sp, packet.noteId(), packet.units())
                        : lm.sell(sp, packet.noteId(), packet.units());
                System.out.println("📈 杠杆票据下单 玩家=" + sp.getName().getString()
                        + " note=" + packet.noteId() + " " + (isBuy ? "买" : "卖") + " x" + packet.units()
                        + " → " + res);
            }
        });
    }
}
