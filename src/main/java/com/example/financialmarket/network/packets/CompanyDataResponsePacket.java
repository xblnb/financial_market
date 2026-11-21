package com.example.financialmarket.network.packets;

import com.example.financialmarket.company.CompanyData;
import com.example.financialmarket.company.CompanyLevel;
import com.example.financialmarket.company.CompanyStatus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record CompanyDataResponsePacket(CompanyData company) implements CustomPacketPayload {
    public static final Type<CompanyDataResponsePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "company_data_response"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, CompanyDataResponsePacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                if (packet.company != null) {
                    buf.writeBoolean(true);
                    buf.writeNbt(packet.company.write());
                } else {
                    buf.writeBoolean(false);
                }
            },
            buf -> {
                if (buf.readBoolean()) {
                    return new CompanyDataResponsePacket(CompanyData.read(buf.readNbt()));
                }
                return new CompanyDataResponsePacket(null);
            }
    );
}