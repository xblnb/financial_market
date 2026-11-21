package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.company.CompanyData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestCompanyDataPacket() implements CustomPacketPayload {
    public static final Type<RequestCompanyDataPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_company_data"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, RequestCompanyDataPacket> STREAM_CODEC = StreamCodec.unit(new RequestCompanyDataPacket());

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(RequestCompanyDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    CompanyData company = engine.getCompanyManager().getByOwner(sp.getUUID());
                    sp.connection.send(new CompanyDataResponsePacket(company));
                }
            }
        });
    }
}