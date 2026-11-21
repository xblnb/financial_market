package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record CommoditySellPacket(String itemId, int quantity) implements CustomPacketPayload {
    public static final Type<CommoditySellPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "commodity_sell"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, CommoditySellPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CommoditySellPacket::itemId,
            ByteBufCodecs.INT, CommoditySellPacket::quantity,
            CommoditySellPacket::new
    );

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(CommoditySellPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(packet.itemId()));
                if (item == null) return;
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    engine.getCommodityMarket().sell(sp, item, packet.quantity());
                }
            }
        });
    }
}