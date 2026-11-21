package com.example.financialmarket.engine.futures;

import net.minecraft.nbt.CompoundTag;

/**
 * 期货持仓（CF-5）：单玩家单合约的多/空双向持仓与开仓均价、开仓 tick（强平排序用）。
 * 逐日无负债结算时开仓均价被盯市到当日结算价。
 */
public class FuturesPosition {
    public String contractCode;
    public int longLots;       // 多头手数
    public int shortLots;      // 空头手数
    public double avgLongPrice;   // 多头开仓均价（盯市后=最近结算价）
    public double avgShortPrice;  // 空头开仓均价
    public long longOpenTick;     // 多头最早开仓 tick
    public long shortOpenTick;    // 空头最早开仓 tick

    public FuturesPosition() { }

    public boolean isEmpty() { return longLots <= 0 && shortLots <= 0; }
    public int netLots() { return longLots + shortLots; }

    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putString("contractCode", contractCode);
        t.putInt("longLots", longLots);
        t.putInt("shortLots", shortLots);
        t.putDouble("avgLongPrice", avgLongPrice);
        t.putDouble("avgShortPrice", avgShortPrice);
        t.putLong("longOpenTick", longOpenTick);
        t.putLong("shortOpenTick", shortOpenTick);
        return t;
    }

    public static FuturesPosition fromTag(CompoundTag t) {
        FuturesPosition p = new FuturesPosition();
        p.contractCode = t.getString("contractCode");
        p.longLots = t.getInt("longLots");
        p.shortLots = t.getInt("shortLots");
        p.avgLongPrice = t.getDouble("avgLongPrice");
        p.avgShortPrice = t.getDouble("avgShortPrice");
        p.longOpenTick = t.getLong("longOpenTick");
        p.shortOpenTick = t.getLong("shortOpenTick");
        return p;
    }
}
