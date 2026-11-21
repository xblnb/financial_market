package com.example.financialmarket.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public record BankDataResponsePacket(Map<String, BankInfo> banks, double cash, List<LoanInfo> loans,
                                     CentralBankInfo centralBank, boolean bankingEnabled) implements CustomPacketPayload {
    /** 既有 4 参构造兼容（本次新增 bankingEnabled 信号前，既有组装方默认 banking.enabled=true）。 */
    public BankDataResponsePacket(Map<String, BankInfo> banks, double cash, List<LoanInfo> loans,
                                  CentralBankInfo centralBank) {
        this(banks, cash, loans, centralBank, true);
    }

    /** 单家银行对账视图（服务端一次性组装；客户端只显示，不做本地推导）。AS-1。 */
    public record BankInfo(String bankId, String name, double primeRate, double depositRate,
                           String ownerUuid, boolean isMine, double equity, double vaultCash,
                           double reserves, double totalDeposits, double totalLoans, double depositBalance) {}

    public record LoanInfo(String bankId, double principal, double remaining) {}

    /** 央行政策对账视图（AS-1/AS-3/AS-4）。playerOwned/ownerUuid 揭示央行是否玩家所有。 */
    public record CentralBankInfo(double discountRate, double reserveRequirement, double baseMoney,
                                  double theoreticalM2Ceiling, boolean playerOwned, String ownerUuid) {}

    public static final Type<BankDataResponsePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "bank_data_response"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, BankDataResponsePacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.banks.size());
                for (var e : p.banks.entrySet()) {
                    buf.writeUtf(e.getKey());
                    BankInfo b = e.getValue();
                    buf.writeUtf(b.bankId); buf.writeUtf(b.name); buf.writeDouble(b.primeRate); buf.writeDouble(b.depositRate);
                    // AS-1 新字段（追加于既有字段之后，旧字段序保持可解）
                    buf.writeUtf(b.ownerUuid != null ? b.ownerUuid : "");
                    buf.writeBoolean(b.isMine); buf.writeDouble(b.equity); buf.writeDouble(b.vaultCash);
                    buf.writeDouble(b.reserves); buf.writeDouble(b.totalDeposits); buf.writeDouble(b.totalLoans);
                    buf.writeDouble(b.depositBalance);
                }
                buf.writeDouble(p.cash);
                buf.writeVarInt(p.loans.size());
                for (var l : p.loans) {
                    buf.writeUtf(l.bankId); buf.writeDouble(l.principal); buf.writeDouble(l.remaining);
                }
                CentralBankInfo cb = p.centralBank;
                if (cb == null) {
                    buf.writeBoolean(false);
                } else {
                    buf.writeBoolean(true);
                    buf.writeDouble(cb.discountRate()); buf.writeDouble(cb.reserveRequirement());
                    buf.writeDouble(cb.baseMoney()); buf.writeDouble(cb.theoreticalM2Ceiling());
                    buf.writeBoolean(cb.playerOwned()); buf.writeUtf(cb.ownerUuid() != null ? cb.ownerUuid() : "");
                }
                // BT-9/BT-10：banking.enabled 信号（服务端权威下发；客户端据此回落只读看板）
                buf.writeBoolean(p.bankingEnabled);
            },
            buf -> {
                int size = buf.readVarInt();
                Map<String, BankInfo> banks = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    String key = buf.readUtf();
                    String bankId = buf.readUtf(); String name = buf.readUtf();
                    double prime = buf.readDouble(); double dep = buf.readDouble();
                    String owner = buf.readUtf(); if (owner.isEmpty()) owner = null;
                    boolean isMine = buf.readBoolean();
                    double equity = buf.readDouble(); double vault = buf.readDouble();
                    double reserves = buf.readDouble(); double totalDep = buf.readDouble();
                    double totalLoan = buf.readDouble(); double depBal = buf.readDouble();
                    banks.put(key, new BankInfo(bankId, name, prime, dep, owner, isMine, equity,
                            vault, reserves, totalDep, totalLoan, depBal));
                }
                double cash = buf.readDouble();
                int loanSize = buf.readVarInt();
                List<LoanInfo> loans = new ArrayList<>();
                for (int i = 0; i < loanSize; i++) loans.add(new LoanInfo(buf.readUtf(), buf.readDouble(), buf.readDouble()));
                CentralBankInfo cb = null;
                if (buf.readBoolean()) {
                    double discount = buf.readDouble(); double rr = buf.readDouble();
                    double base = buf.readDouble(); double ceiling = buf.readDouble();
                    boolean owned = buf.readBoolean();
                    String ownerUuid = buf.readUtf(); if (ownerUuid.isEmpty()) ownerUuid = null;
                    cb = new CentralBankInfo(discount, rr, base, ceiling, owned, ownerUuid);
                }
                boolean bankingEnabled = buf.readBoolean();
                return new BankDataResponsePacket(banks, cash, loans, cb, bankingEnabled);
            }
    );
}
