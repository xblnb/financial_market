package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestGovernmentDataPacket() implements CustomPacketPayload {
    public static final Type<RequestGovernmentDataPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_government_data"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    public static final StreamCodec<FriendlyByteBuf, RequestGovernmentDataPacket> STREAM_CODEC = StreamCodec.unit(new RequestGovernmentDataPacket());

    public void send() {
        PacketDistributor.sendToServer(this);
    }
}

    /*public static void handle(RequestGovernmentDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    var gov = engine.getGovernment();
                    var cb = engine.getCentralBank();
                    var inf = engine.getInflationSystem();
                    sp.connection.send(new GovernmentDataResponsePacket(
                            gov.getTreasury(), gov.getTotalDebt(), inf.getInflationRate(),
                            cb.getBaseMoney(), cb.getDiscountRate()
                    ));
                }
            }
        });
    }
}*/