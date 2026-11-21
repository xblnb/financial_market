package com.example.financialmarket.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record RepayLoanPacket(UUID loanId, double amount) implements CustomPacketPayload {
    public static final Type<RepayLoanPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "repay_loan"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, RepayLoanPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeUUID(packet.loanId);
                buf.writeDouble(packet.amount);
            },
            buf -> new RepayLoanPacket(buf.readUUID(), buf.readDouble())
    );

    public void send() { PacketDistributor.sendToServer(this); }

    public void handle(IPayloadContext iPayloadContext) {
    }

    // handle 方法同前，省略
}