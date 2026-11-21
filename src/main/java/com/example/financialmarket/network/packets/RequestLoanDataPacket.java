package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.banking.Bank;
import com.example.financialmarket.engine.banking.Loan;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.*;

public record RequestLoanDataPacket() implements CustomPacketPayload {
    public static final Type<RequestLoanDataPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_loan_data"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, RequestLoanDataPacket> STREAM_CODEC = StreamCodec.unit(new RequestLoanDataPacket());

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(RequestLoanDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    List<Loan> allLoans = new ArrayList<>();
                    for (Bank bank : engine.getBankManager().getAllBanks()) {
                        // 需要银行提供查询玩家贷款的方法，此处简化返回空列表
                    }
                    sp.connection.send(new LoanDataResponsePacket(allLoans));
                }
            }
        });
    }
}