package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.HashMap;
import java.util.Map;

public record RequestCommodityPricePacket() implements CustomPacketPayload {
    public static final Type<RequestCommodityPricePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_commodity_price"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, RequestCommodityPricePacket> STREAM_CODEC = StreamCodec.unit(new RequestCommodityPricePacket());

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(RequestCommodityPricePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    Map<Item, Double> priceMap = new HashMap<>();
                    for (var entry : engine.getCommodityMarket().getAllCommodities()) {
                        priceMap.put(entry.getItem(), entry.getPrice());
                    }
                    sp.connection.send(new CommodityPriceSyncPacket(priceMap));
                }
            }
        });
    }
}