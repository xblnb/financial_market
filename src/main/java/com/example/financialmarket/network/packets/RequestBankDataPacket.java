package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.banking.Bank;
import com.example.financialmarket.engine.banking.CentralBank;
import com.example.financialmarket.engine.banking.Loan;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.*;

public record RequestBankDataPacket() implements CustomPacketPayload {
    public static final Type<RequestBankDataPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_bank_data"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, RequestBankDataPacket> STREAM_CODEC = StreamCodec.unit(new RequestBankDataPacket());
    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(RequestBankDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    var bankManager = engine.getBankManager();
                    UUID playerId = sp.getUUID();
                    Map<String, BankDataResponsePacket.BankInfo> bankInfos = new HashMap<>();
                    for (Bank bank : bankManager.getAllBanks()) {
                        boolean isMine = bank.getOwnerUuid() != null && bank.getOwnerUuid().equals(playerId);
                        bankInfos.put(bank.getBankId(), new BankDataResponsePacket.BankInfo(
                                bank.getBankId(), bank.getName(), bank.getPrimeRate(), bank.getDepositRate(),
                                bank.getOwnerUuid() != null ? bank.getOwnerUuid().toString() : null,
                                isMine, bank.getEquity(), bank.getVaultCash(), bank.getReserves(),
                                bank.getTotalDeposits(), bank.getTotalLoans(), bank.getDepositBalance(playerId)
                        ));
                    }
                    PlayerEconomy eco = PlayerEconomy.get(sp);
                    List<BankDataResponsePacket.LoanInfo> loanInfos = new ArrayList<>();
                    for (Bank bank : bankManager.getAllBanks()) {
                        for (Loan loan : bank.getLoans()) {
                            if (loan.getBorrowerId().equals(playerId)) {
                                loanInfos.add(new BankDataResponsePacket.LoanInfo(
                                        loan.getLenderBankId(), loan.getPrincipal(), loan.getRemaining()
                                ));
                            }
                        }
                    }
                    CentralBank cb = bankManager.getCentralBank();
                    BankDataResponsePacket.CentralBankInfo cbInfo = new BankDataResponsePacket.CentralBankInfo(
                            cb.getDiscountRate(), cb.getReserveRequirement(), cb.getBaseMoney(),
                            cb.theoreticalM2Ceiling(), cb.isPlayerOwned(),
                            cb.getPlayerOwner() != null ? cb.getPlayerOwner().toString() : null
                    );
                    sp.connection.send(new BankDataResponsePacket(bankInfos, eco.getCash(), loanInfos, cbInfo, engine.isBankingEnabled()));
                }
            }
        });
    }
}
