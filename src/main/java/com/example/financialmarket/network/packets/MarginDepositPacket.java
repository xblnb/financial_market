package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.attachment.PlayerEconomy;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端→服务端：融资融券入金（t7）。服务端从玩家现金 {@code PlayerEconomy.getCash()}
 * 扣减 {@code amount} 并转入 {@code engine.getMarginManager().deposit(...)}；现金不足则拒绝。
 * 确定性记账、无随机。
 */
public record MarginDepositPacket(double amount) implements CustomPacketPayload {
    public static final Type<MarginDepositPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "margin_deposit"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, MarginDepositPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, MarginDepositPacket::amount,
            MarginDepositPacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(MarginDepositPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                if (packet.amount() <= 0) return;
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine == null) return;
                PlayerEconomy eco = PlayerEconomy.get(sp);
                if (eco == null || eco.getCash() < packet.amount()) {
                    System.out.println("⛔ 融资融券入金失败（现金不足）: " + sp.getName().getString());
                    return;
                }
                // 确定性记账：扣现金 → 入保证金
                eco.setCash(eco.getCash() - packet.amount());
                engine.getMarginManager().deposit(sp.getUUID(), packet.amount());
                System.out.println("💵 融资融券入金 玩家=" + sp.getName().getString()
                        + " 金额=" + String.format("%.0f", packet.amount()));
            }
        });
    }
}
