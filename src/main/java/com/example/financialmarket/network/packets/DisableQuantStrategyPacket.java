package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record DisableQuantStrategyPacket() implements CustomPacketPayload {
    public static final Type<DisableQuantStrategyPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "disable_quant"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, DisableQuantStrategyPacket> STREAM_CODEC = StreamCodec.unit(new DisableQuantStrategyPacket());

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(DisableQuantStrategyPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                // t8：从 MarketEngine 取量化引擎单例，清空该玩家策略（与 EnableQuantStrategyPacket 对称，null 兜底）
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    engine.getQuantAPI().disableStrategy(sp.getUUID());
                    System.out.println("🛑 玩家 " + sp.getName().getString() + " 停止量化策略");
                }
            }
        });
    }
}
