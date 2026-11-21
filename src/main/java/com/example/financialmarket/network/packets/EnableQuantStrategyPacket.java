package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record EnableQuantStrategyPacket(String strategyName, int fastPeriod, int slowPeriod) implements CustomPacketPayload {
    public static final Type<EnableQuantStrategyPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "enable_quant"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, EnableQuantStrategyPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, EnableQuantStrategyPacket::strategyName,
            ByteBufCodecs.INT, EnableQuantStrategyPacket::fastPeriod,
            ByteBufCodecs.INT, EnableQuantStrategyPacket::slowPeriod,
            EnableQuantStrategyPacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(EnableQuantStrategyPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                // t2：从 MarketEngine 取量化引擎单例，注册双均线策略（不再 new QuantAPI()）
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    engine.getQuantAPI().enableStrategy(sp.getUUID(), packet.strategyName(), packet.fastPeriod(), packet.slowPeriod());
                    System.out.println("📈 玩家 " + sp.getName().getString() + " 启用量化策略: " + packet.strategyName());
                }
            }
        });
    }
}