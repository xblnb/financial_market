package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.MarketEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 储蓄卡开卡请求（Client→Server，冒烟阻断项修复：真实玩家发卡入口）。
 *
 * <p>此前 {@code BankCardService.issueCard} 全仓库零调用方，真实玩家永远拿不到储蓄卡 →
 * A股/期货/商品/公司/方块售货全部被「请先办理储蓄卡」门控卡死（仅 AI 合成流可成交）。
 * 本包由银行平板「办卡」按钮发送，<b>服务端为唯一权威</b>：校验 banking 开关、已持卡、
 * 未知银行后调用 {@code BankCardService.issueCard}（确定性、Luhn 合法、无随机偏差），
 * 成功/失败经聊天消息反馈。与 HTTP POST /api/bank/card 同语义（双入口一致）。
 */
public record CardIssuePacket(String bankId) implements CustomPacketPayload {
    public static final Type<CardIssuePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "card_issue"));

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }

    public void send() { PacketDistributor.sendToServer(this); }

    public static final StreamCodec<FriendlyByteBuf, CardIssuePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CardIssuePacket::bankId,
            CardIssuePacket::new
    );

    public static void handle(CardIssuePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                MarketEngine engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine == null) return;
                if (!engine.isBankingEnabled()) {
                    sp.displayClientMessage(Component.literal("⛔ 银行系统未开启，无法办卡"), false);
                    return;
                }
                if (engine.hasBankCard(sp.getUUID())) {
                    sp.displayClientMessage(Component.literal("⛔ 您已持有储蓄卡（每玩家限 1 张）"), false);
                    return;
                }
                if (packet.bankId() == null || !engine.getBankCardService().isKnownBank(packet.bankId())) {
                    sp.displayClientMessage(Component.literal("⛔ 未知银行，无法办卡"), false);
                    return;
                }
                String number = engine.getBankCardService().issueCard(sp.getUUID(), packet.bankId(), engine.getTickCounter());
                if (number != null) {
                    sp.displayClientMessage(Component.literal("✅ 储蓄卡开卡成功：卡号 " + number
                            + "（开户行 " + packet.bankId() + "），可开始交易"), false);
                } else {
                    sp.displayClientMessage(Component.literal("⛔ 开卡失败（卡号碰撞重试耗尽）"), false);
                }
            }
        });
    }
}
