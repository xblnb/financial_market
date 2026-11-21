package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.banking.Bank;
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
 * 银行利率/参数管理包（BT-6/AS-6，服务端权威）。
 * <p>{@code field} 为 "prime"（贷款利率）或 "deposit"（存款利率），{@code value} 为目标利率。
 * 服务端校验<b>仅该银行拥有者（ownerUuid 匹配）</b>可改；非拥有者<b>原子拒绝且不改状态</b>。
 * 委托 {@link Bank#setPrimeRate}/{@link Bank#setDepositRate} 钳制合法区间，确定性、无随机。
 * 成功后回传中文结果并重新下发 {@link BankDataResponsePacket} 刷新平板。
 */
public record BankRatePacket(String bankId, String field, double value) implements CustomPacketPayload {
    public static final Type<BankRatePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "bank_rate"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, BankRatePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, BankRatePacket::bankId,
            ByteBufCodecs.STRING_UTF8, BankRatePacket::field,
            ByteBufCodecs.DOUBLE, BankRatePacket::value,
            BankRatePacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(BankRatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var engine = MarketAttachment.getEngine(sp.serverLevel());
            if (engine == null) {
                reject(sp, "引擎未就绪");
                return;
            }
            Bank bank = engine.getBankManager().getBank(packet.bankId());
            if (bank == null) {
                reject(sp, "银行不存在: " + packet.bankId());
                return;
            }
            // 权限隔离：仅银行拥有者可改（AI 银行 owner==null 恒拒绝）
            if (bank.getOwnerUuid() == null || !bank.getOwnerUuid().equals(sp.getUUID())) {
                reject(sp, "权限不足：仅银行拥有者可设定利率");
                return;
            }
            if ("prime".equals(packet.field())) {
                double effective = bank.setPrimeRate(packet.value());
                accept(sp, bank.getName() + " 贷款利率已设为 " + pct(effective));
            } else if ("deposit".equals(packet.field())) {
                double effective = bank.setDepositRate(packet.value());
                accept(sp, bank.getName() + " 存款利率已设为 " + pct(effective));
            } else {
                reject(sp, "未知利率字段: " + packet.field());
                return;
            }
            sendBankData(sp);
        });
    }

    private static String pct(double v) { return String.format(java.util.Locale.ROOT, "%.2f%%", v * 100); }
}
