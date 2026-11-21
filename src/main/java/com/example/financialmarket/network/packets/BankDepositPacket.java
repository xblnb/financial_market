package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.banking.Bank;
import com.example.financialmarket.engine.banking.CentralBank;
import com.example.financialmarket.engine.banking.Loan;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;

/**
 * 银行平板存/取款交互包（AS-5/BT-5，服务端权威）。
 * <p>{@code action} 为 "deposit"（现金→活期存款）或 "withdraw"（活期存款→现金）。
 * 客户端仅提交意图，服务端校验金额/余额/库存现金后调用既有
 * {@link Bank#deposit}/{@link Bank#withdraw} 并同步 {@link PlayerEconomy} 现金；
 * 成功回传中文结果并重新下发 {@link BankDataResponsePacket} 刷新平板，非法/不足则<b>原子拒绝</b>且不改状态。
 */
public record BankDepositPacket(String bankId, String action, double amount) implements CustomPacketPayload {
    public static final Type<BankDepositPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "bank_deposit"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, BankDepositPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, BankDepositPacket::bankId,
            ByteBufCodecs.STRING_UTF8, BankDepositPacket::action,
            ByteBufCodecs.DOUBLE, BankDepositPacket::amount,
            BankDepositPacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(BankDepositPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            if (packet.amount() <= 0) {
                reject(sp, "存取金额必须为正");
                return;
            }
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
            PlayerEconomy eco = PlayerEconomy.get(sp);
            if (eco == null) {
                reject(sp, "玩家经济账户不可用");
                return;
            }
            // 服务端权威校验后一次性生效（原子性、确定性、无随机）
            if ("deposit".equals(packet.action())) {
                if (eco.getCash() < packet.amount()) {
                    reject(sp, "现金不足");
                    return;
                }
                bank.deposit(sp.getUUID(), packet.amount());
                eco.setCash(eco.getCash() - packet.amount());
                accept(sp, "存款成功：" + bank.getName() + " 存入 " + fmt(packet.amount()));
            } else if ("withdraw".equals(packet.action())) {
                if (!bank.withdraw(sp.getUUID(), packet.amount())) {
                    reject(sp, "取款失败：余额或库存现金不足");
                    return;
                }
                eco.setCash(eco.getCash() + packet.amount());
                accept(sp, "取款成功：" + bank.getName() + " 取出 " + fmt(packet.amount()));
            } else {
                reject(sp, "未知存取操作: " + packet.action());
                return;
            }
            sendBankData(sp);
        });
    }

    /** 操作成功：中文结果提示 + 重新下发对账数据刷新平板。 */
    static void accept(ServerPlayer sp, String msg) {
        sp.displayClientMessage(Component.literal("✅ " + msg), false);
    }

    /** 操作失败：中文拒绝提示（不改状态）。 */
    static void reject(ServerPlayer sp, String reason) {
        sp.displayClientMessage(Component.literal("⛔ " + reason), false);
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }

    /**
     * 服务端按当前引擎状态重新组装并下发 {@link BankDataResponsePacket}（AS-1 对账视图），
     * 供平板刷新「我的银行/存贷款/央行政策」。确定性与 {@link RequestBankDataPacket} 一致、无随机。
     */
    public static void sendBankData(ServerPlayer sp) {
        var engine = MarketAttachment.getEngine(sp.serverLevel());
        if (engine == null) return;
        var bankManager = engine.getBankManager();
        UUID playerId = sp.getUUID();
        Map<String, BankDataResponsePacket.BankInfo> bankInfos = new HashMap<>();
        for (Bank bank : bankManager.getAllBanks()) {
            boolean isMine = bank.getOwnerUuid() != null && bank.getOwnerUuid().equals(playerId);
            bankInfos.put(bank.getBankId(), new BankDataResponsePacket.BankInfo(
                    bank.getBankId(), bank.getName(), bank.getPrimeRate(), bank.getDepositRate(),
                    bank.getOwnerUuid() != null ? bank.getOwnerUuid().toString() : null,
                    isMine, bank.getEquity(), bank.getVaultCash(), bank.getReserves(),
                    bank.getTotalDeposits(), bank.getTotalLoans(), bank.getDepositBalance(playerId)));
        }
        PlayerEconomy eco = PlayerEconomy.get(sp);
        double cash = eco != null ? eco.getCash() : 0.0;
        List<BankDataResponsePacket.LoanInfo> loanInfos = new ArrayList<>();
        for (Bank bank : bankManager.getAllBanks()) {
            for (Loan loan : bank.getLoans()) {
                if (loan.getBorrowerId().equals(playerId)) {
                    loanInfos.add(new BankDataResponsePacket.LoanInfo(
                            loan.getLenderBankId(), loan.getPrincipal(), loan.getRemaining()));
                }
            }
        }
        CentralBank cb = bankManager.getCentralBank();
        BankDataResponsePacket.CentralBankInfo cbInfo = new BankDataResponsePacket.CentralBankInfo(
                cb.getDiscountRate(), cb.getReserveRequirement(), cb.getBaseMoney(),
                cb.theoreticalM2Ceiling(), cb.isPlayerOwned(),
                cb.getPlayerOwner() != null ? cb.getPlayerOwner().toString() : null);
        sp.connection.send(new BankDataResponsePacket(bankInfos, cash, loanInfos, cbInfo, engine.isBankingEnabled()));
    }
}
