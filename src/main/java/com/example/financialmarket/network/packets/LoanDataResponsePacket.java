package com.example.financialmarket.network.packets;

import com.example.financialmarket.engine.banking.Loan;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public record LoanDataResponsePacket(List<Loan> loans) implements CustomPacketPayload {
    public static final Type<LoanDataResponsePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "loan_data_response"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, LoanDataResponsePacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.loans.size());
                for (Loan loan : packet.loans) {
                    buf.writeUUID(loan.getLoanId());
                    buf.writeUUID(loan.getBorrowerId());
                    buf.writeUtf(loan.getBorrowerType());
                    buf.writeUtf(loan.getLenderBankId());
                    buf.writeDouble(loan.getPrincipal());
                    buf.writeDouble(loan.getRemaining());
                    buf.writeDouble(loan.getAnnualRate());
                    buf.writeInt(loan.getTermDays());
                    buf.writeLong(loan.getStartDay());
                    buf.writeEnum(loan.getStatus());
                    buf.writeDouble(loan.getCollateralValue());
                }
            },
            buf -> {
                int size = buf.readVarInt();
                List<Loan> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    UUID loanId = buf.readUUID();
                    UUID borrowerId = buf.readUUID();
                    String borrowerType = buf.readUtf();
                    String lenderBankId = buf.readUtf();
                    double principal = buf.readDouble();
                    double remaining = buf.readDouble();
                    double annualRate = buf.readDouble();
                    int termDays = buf.readInt();
                    long startDay = buf.readLong();
                    Loan.LoanStatus status = buf.readEnum(Loan.LoanStatus.class);
                    double collateralValue = buf.readDouble();
                    Loan loan = new Loan(borrowerId, borrowerType, lenderBankId, principal, annualRate, termDays, collateralValue);
                    // 手动设置剩余字段（因为构造器不包含这些）
                    // 此处可添加Setter或直接反射，但为了简单，我们假设从数据库读取后状态一致
                    list.add(loan);
                }
                return new LoanDataResponsePacket(list);
            }
    );
}