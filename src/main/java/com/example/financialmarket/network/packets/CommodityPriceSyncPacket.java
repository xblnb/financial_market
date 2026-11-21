package com.example.financialmarket.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.*;

public record CommodityPriceSyncPacket(Map<Item, Double> prices) implements CustomPacketPayload {
    public static final Type<CommodityPriceSyncPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "commodity_price_sync"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, CommodityPriceSyncPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.prices.size());
                for (Map.Entry<Item, Double> e : packet.prices.entrySet()) {
                    buf.writeUtf(BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
                    buf.writeDouble(e.getValue());
                }
            },
            buf -> {
                int size = buf.readVarInt();
                Map<Item, Double> map = new HashMap<>();
                for (int i = 0; i < size; i++) {
                    Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(buf.readUtf()));
                    if (item != null) map.put(item, buf.readDouble());
                }
                return new CommodityPriceSyncPacket(map);
            }
    );
}