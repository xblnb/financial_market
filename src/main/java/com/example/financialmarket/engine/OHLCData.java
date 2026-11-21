package com.example.financialmarket.engine;

import net.minecraft.nbt.CompoundTag;

public class OHLCData {
    private double open, high, low, close;
    private long volume;

    public OHLCData(double open, double high, double low, double close, long volume) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public long getVolume() { return volume; }

    public void update(double price, long vol) {
        if (price > high) high = price;
        if (price < low) low = price;
        close = price;
        volume += vol;
    }

    // NBT 读写
    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("o", open);
        tag.putDouble("h", high);
        tag.putDouble("l", low);
        tag.putDouble("c", close);
        tag.putLong("v", volume);
        return tag;
    }

    public static OHLCData fromNbt(CompoundTag tag) {
        return new OHLCData(
                tag.getDouble("o"),
                tag.getDouble("h"),
                tag.getDouble("l"),
                tag.getDouble("c"),
                tag.getLong("v")
        );
    }
}