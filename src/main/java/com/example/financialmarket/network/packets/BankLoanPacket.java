package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.banking.Bank;
import com.example.financialmarket.engine.banking.Loan;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

import static com.example.financialmarket.network.packets.BankDepositPacket.accept;
import static com.example.financialmarket.network.packets.BankDepositPacket.reject;
import static com.example.financialmarket.network.packets.BankDepositPacket.sendBankData;

/**
 * 银行平板贷款/还款交互包（AS-5/BT-5，服务端权威）。
 * <p>{@code action} 为 "issue"（申请贷款，信用派生 M2 内生扩张）或 "repay"（偿还贷款）。
 * 客户端仅提交意图，服务端校验（金额>0；放贷受 {@link Bank#issueLoan} 准备金/权益上限约束；
 * 还款 {@code loanId}+{@code amount}）后调用既有引擎方法；成功回传中文结果并重新下发
 * {@link BankDataResponsePacket} 刷新平板，非法/失败则<b>原子拒绝</b>且不改状态。
 */
public record BankLoanPacket(String bankId, String action, double amount, int termDays, double rate, UUID loanId) implements CustomPacketPayload {
    public static final Type<BankLoanPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "bank_loan"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, BankLoanPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.bankId);
                buf.writeUtf(p.action);
                buf.writeDouble(p.amount);
                buf.writeInt(p.termDays);
                buf.writeDouble(p.rate);
                buf.writeUUID(p.loanId);
            },
            buf -> new BankLoanPacket(buf.readUtf(), buf.readUtf(), buf.readDouble(), buf.readInt(), buf.readDouble(), buf.readUUID()));

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(BankLoanPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            if (packet.amount() <= 0) {
                reject(sp, "金额必须为正");
                return;
            }
            var engine = MarketAttachment.getEngine(sp.serverLevel());
            if (engine == null) {
                reject(sp, "引擎未就绪");
                return;
            }
            if ("issue".equals(packet.action())) {
                Bank bank = engine.getBankManager().getBank(packet.bankId());
                if (bank == null) {
                    reject(sp, "银行不存在: " + packet.bankId());
                    return;
                }
                int term = packet.termDays() > 0 ? packet.termDays() : 30;
                double r = packet.rate() > 0 ? packet.rate() : bank.getPrimeRate();
                Loan loan = bank.issueLoan(sp.getUUID(), "PLAYER", packet.amount(), r, term, 0);
                if (loan == null) {
                    reject(sp, "贷款失败：受准备金/权益上限约束");
                    return;
                }
                accept(sp, "贷款成功：" + bank.getName() + " 放贷 " + fmt(packet.amount())
                        + "，期限 " + term + " 日，利率 " + String.format(java.util.Locale.ROOT, "%.1f%%", r * 100));
            } else if ("repay".equals(packet.action())) {
                if (packet.loanId() == null) {
                    reject(sp, "还款缺少贷款编号");
                    return;
                }
                // 找到持有该贷款的行（loanId 全局唯一），确定服务端权威还款
                Bank target = null;
                for (Bank bank : engine.getBankManager().getAllBanks()) {
                    for (Loan loan : bank.getLoans()) {
                        if (loan.getLoanId().equals(packet.loanId()) && loan.getBorrowerId().equals(sp.getUUID())) {
                            target = bank;
                            break;
                        }
                    }
                    if (target != null) break;
                }
                if (target == null) {
                    reject(sp, "未找到您的贷款");
                    return;
                }
                double repaid = target.repayLoan(packet.loanId(), packet.amount());
                if (repaid <= 0) {
                    reject(sp, "还款失败：金额或余额无效");
                    return;
                }
                accept(sp, "还款成功：已还 " + fmt(repaid));
            } else {
                reject(sp, "未知贷款操作: " + packet.action());
                return;
            }
            sendBankData(sp);
        });
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
}
