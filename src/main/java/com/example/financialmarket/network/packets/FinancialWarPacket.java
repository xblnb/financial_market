package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.war.FinancialWarSystem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record FinancialWarPacket(String action, String targetAsset, double amount) implements CustomPacketPayload {
    public static final Type<FinancialWarPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "financial_war"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, FinancialWarPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, FinancialWarPacket::action,
            ByteBufCodecs.STRING_UTF8, FinancialWarPacket::targetAsset,
            ByteBufCodecs.DOUBLE, FinancialWarPacket::amount,
            FinancialWarPacket::new
    );

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(FinancialWarPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp && sp.hasPermissions(2)) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    FinancialWarSystem warSystem = engine.getFinancialWarSystem();
                    warSystem.execute(sp, packet.action(), packet.targetAsset(), packet.amount());
                }
            }
        });
    }
}