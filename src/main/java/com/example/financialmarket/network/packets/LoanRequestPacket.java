package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.banking.Loan;
import com.example.financialmarket.engine.banking.Bank;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LoanRequestPacket(String bankId, double amount) implements CustomPacketPayload {
    public static final Type<LoanRequestPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "loan_request"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, LoanRequestPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LoanRequestPacket::bankId,
            ByteBufCodecs.DOUBLE, LoanRequestPacket::amount,
            LoanRequestPacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(LoanRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    Bank bank = engine.getBankManager().getBank(packet.bankId());
                    if (bank != null) {
                        Loan loan = bank.issueLoan(sp.getUUID(), "PLAYER", packet.amount(), bank.getPrimeRate(), 30, 0);
                        // 同步贷款数据（可通过新包或直接回复）
                    }
                }
            }
        });
    }
}