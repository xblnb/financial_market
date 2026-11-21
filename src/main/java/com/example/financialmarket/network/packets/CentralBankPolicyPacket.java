package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.banking.CentralBank;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.example.financialmarket.network.packets.BankDepositPacket.accept;
import static com.example.financialmarket.network.packets.BankDepositPacket.reject;
import static com.example.financialmarket.network.packets.BankDepositPacket.sendBankData;

/**
 * 央行政策设定交互包（AS-4/BT-4，服务端权威）。
 * <p>客户端仅提交 {@code action}/{@code value} 意图；服务端<b>仅允许央行拥有者</b>执行，
 * 并委托既有 {@link CentralBank} 方法（贴现率走 Guardrail 钳制、准备金率/基础货币钳制、
 * 发钞仅 {@code injectBaseMoney}）。越权/非法<b>原子拒绝</b>且不改状态；成功回传中文结果并刷新平板。
 */
public record CentralBankPolicyPacket(String action, double value) implements CustomPacketPayload {
    public static final Type<CentralBankPolicyPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "central_bank_policy"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, CentralBankPolicyPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CentralBankPolicyPacket::action,
            ByteBufCodecs.DOUBLE, CentralBankPolicyPacket::value,
            CentralBankPolicyPacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(CentralBankPolicyPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var engine = MarketAttachment.getEngine(sp.serverLevel());
            if (engine == null) {
                reject(sp, "引擎未就绪");
                return;
            }
            CentralBank cb = engine.getBankManager().getCentralBank();
            if (!cb.isPlayerOwned() || !cb.getPlayerOwner().equals(sp.getUUID())) {
                reject(sp, "权限不足：仅央行拥有者可设定政策");
                return;
            }
            switch (packet.action()) {
                case "set_discount" -> {
                    var result = cb.setDiscountRate(packet.value());
                    accept(sp, "贴现率已设为 " + pct(result.effectiveRate()));
                }
                case "set_reserve" -> {
                    if (packet.value() <= 0) {
                        reject(sp, "准备金率必须为正");
                        return;
                    }
                    cb.setReserveRequirement(packet.value());
                    accept(sp, "准备金率已设为 " + pct(cb.getReserveRequirement()));
                }
                case "inject_base" -> {
                    if (packet.value() <= 0) {
                        reject(sp, "发钞量必须为正");
                        return;
                    }
                    cb.injectBaseMoney(packet.value());
                    accept(sp, "发钞成功：基础货币注入 " + fmt(packet.value()) + "，当前 M0 = " + fmt(cb.getBaseMoney()));
                }
                case "set_base" -> {
                    if (packet.value() < 0) {
                        reject(sp, "基础货币基数不能为负");
                        return;
                    }
                    cb.setBaseMoney(packet.value());
                    accept(sp, "基础货币基数已设为 " + fmt(cb.getBaseMoney()));
                }
                default -> {
                    reject(sp, "未知政策操作: " + packet.action());
                    return;
                }
            }
            sendBankData(sp);
        });
    }

    private static String pct(double v) { return String.format(java.util.Locale.ROOT, "%.2f%%", v * 100); }
    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
}
