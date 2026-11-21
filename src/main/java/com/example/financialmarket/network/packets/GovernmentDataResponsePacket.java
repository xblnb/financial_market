package com.example.financialmarket.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GovernmentDataResponsePacket(double treasury, double totalDebt, double inflation, double baseMoney, double discountRate) implements CustomPacketPayload {
    public static final Type<GovernmentDataResponsePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "government_data_response"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, GovernmentDataResponsePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, GovernmentDataResponsePacket::treasury,
            ByteBufCodecs.DOUBLE, GovernmentDataResponsePacket::totalDebt,
            ByteBufCodecs.DOUBLE, GovernmentDataResponsePacket::inflation,
            ByteBufCodecs.DOUBLE, GovernmentDataResponsePacket::baseMoney,
            ByteBufCodecs.DOUBLE, GovernmentDataResponsePacket::discountRate,
            GovernmentDataResponsePacket::new
    );

    public double reserveRequirement() {
        return 0;
    }
}