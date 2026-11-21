package com.example.financialmarket.attachment;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;

public class Holding {
    private final String assetId;
    private final int quantity;
    private final double avgCost;

    public Holding(String assetId, int quantity, double avgCost) {
        this.assetId = assetId;
        this.quantity = quantity;
        this.avgCost = avgCost;
    }

    public String getAssetId() { return assetId; }
    public int getQuantity() { return quantity; }
    public double getAvgCost() { return avgCost; }

    public static final Codec<Holding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("assetId").forGetter(Holding::getAssetId),
            Codec.INT.fieldOf("quantity").forGetter(Holding::getQuantity),
            Codec.DOUBLE.fieldOf("avgCost").forGetter(Holding::getAvgCost)
    ).apply(instance, Holding::new));

    public static void writeToPacket(FriendlyByteBuf buf, Holding h) {
        buf.writeUtf(h.assetId);
        buf.writeInt(h.quantity);
        buf.writeDouble(h.avgCost);
    }

    public static Holding readFromPacket(FriendlyByteBuf buf) {
        return new Holding(buf.readUtf(), buf.readInt(), buf.readDouble());
    }
}