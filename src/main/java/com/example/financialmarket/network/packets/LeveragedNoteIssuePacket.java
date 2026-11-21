package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.leverage.LeveragedNote;
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
 * 客户端→服务端：杠杆票据发行请求（AS-L1 §6.3）。
 * 服务端绑定玩家身份（context.player()）调用 {@code engine.getLeverageMarket().issue(...)}。
 * 自定标的类型/标的 key/杠杆倍数/到期日（tick）/份数；冻结发行方保证金。确定性、无随机。
 */
public record LeveragedNoteIssuePacket(String underlyingType, String underlyingKey, double leverage,
                                       long maturityTicks, int units) implements CustomPacketPayload {
    public static final Type<LeveragedNoteIssuePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "leverage_issue"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, LeveragedNoteIssuePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LeveragedNoteIssuePacket::underlyingType,
            ByteBufCodecs.STRING_UTF8, LeveragedNoteIssuePacket::underlyingKey,
            ByteBufCodecs.DOUBLE, LeveragedNoteIssuePacket::leverage,
            ByteBufCodecs.VAR_LONG, LeveragedNoteIssuePacket::maturityTicks,
            ByteBufCodecs.VAR_INT, LeveragedNoteIssuePacket::units,
            LeveragedNoteIssuePacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(LeveragedNoteIssuePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine == null || !engine.isLeverageEnabled()) return;
                LeveragedNote.UnderlyingType type;
                try {
                    type = LeveragedNote.UnderlyingType.valueOf(packet.underlyingType());
                } catch (IllegalArgumentException e) {
                    return;
                }
                LeveragedNoteMarket lm = engine.getLeverageMarket();
                LeveragedNoteMarket.IssueResult res = lm.issue(sp, type, packet.underlyingKey(),
                        packet.leverage(), packet.maturityTicks(), packet.units());
                System.out.println("🆕 杠杆票据发行 玩家=" + sp.getName().getString()
                        + " type=" + packet.underlyingType() + " key=" + packet.underlyingKey()
                        + " 杠杆=" + packet.leverage() + " 到期=" + packet.maturityTicks() + " x" + packet.units()
                        + " → " + res);
            }
        });
    }
}
