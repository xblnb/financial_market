package com.example.financialmarket.engine.futures;

import net.minecraft.nbt.CompoundTag;

/**
 * 期货合约（CZCE 规则，CF-5/CF-6）。
 *
 * <p>合约代码 {@code <品种>_<YYMM>}（如 {@code IRON_2605}）；交割月/最后交易日由引擎 tick
 * 确定性派生（{@link FuturesMarket#CONTRACT_MONTH_TICKS}），无随机、同种子可复现。
 * 涨跌停板锚<b>昨结算价</b> {@code prevSettlePrice}（默认 ±4%，连续涨跌停扩大）；保证金比例分档
 * （一般/次月/交割月 8%/12%/20%，连续涨跌停提高）；逐日无负债结算（当日成交加权平均价作结算价）。
 */
public class FuturesContract {
    public enum Status { LISTED, DELIVERING, EXPIRED }

    public String code;             // IRON_2605
    public String underlying;       // 品种代码（如 IRON）
    public String itemId;           // 标的 item 注册键（ResourceLocation 字符串，持久化用）
    public transient net.minecraft.world.item.Item item; // 标的物品（非持久化，读档时按 itemId 还原）
    public int multiplier;          // 每手单位数（默认 10）
    public long monthIndex;         // 合约月序号（单调）
    public long deliveryMonthTicks; // 交割月期首 tick
    public long lastTradingTicks;   // 最后交易日 tick
    public double prevSettlePrice;  // 昨结算价（锚涨跌停）
    public double lastPrice;        // 最新参考/成交价
    public double limitUp;          // 当日涨停（锚昨结算价）
    public double limitDown;        // 当日跌停（锚昨结算价）
    public double marginRatio;      // 当日保证金比例（分档 + 连续涨跌停扩大）
    public Status status = Status.LISTED;
    public int consecutiveLimitDays;  // 连续涨跌停交易日计数（CZCE 风险控制）
    public long openInterest;         // 未平仓量（持仓手数）
    // 当日成交累计（逐日无负债结算：成交加权平均价）
    public double dayTradeNotional;
    public long dayTradeQuantity;

    public FuturesContract() { }

    public boolean isTradable() { return status == Status.LISTED; }

    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putString("code", code);
        t.putString("underlying", underlying);
        t.putString("itemId", itemId);
        t.putInt("multiplier", multiplier);
        t.putLong("monthIndex", monthIndex);
        t.putLong("deliveryMonthTicks", deliveryMonthTicks);
        t.putLong("lastTradingTicks", lastTradingTicks);
        t.putDouble("prevSettlePrice", prevSettlePrice);
        t.putDouble("lastPrice", lastPrice);
        t.putDouble("limitUp", limitUp);
        t.putDouble("limitDown", limitDown);
        t.putDouble("marginRatio", marginRatio);
        t.putByte("status", (byte) status.ordinal());
        t.putInt("consecutiveLimitDays", consecutiveLimitDays);
        t.putLong("openInterest", openInterest);
        t.putDouble("dayTradeNotional", dayTradeNotional);
        t.putLong("dayTradeQuantity", dayTradeQuantity);
        return t;
    }

    public static FuturesContract fromTag(CompoundTag t) {
        FuturesContract c = new FuturesContract();
        c.code = t.getString("code");
        c.underlying = t.getString("underlying");
        c.itemId = t.getString("itemId");
        c.multiplier = t.getInt("multiplier");
        c.monthIndex = t.getLong("monthIndex");
        c.deliveryMonthTicks = t.getLong("deliveryMonthTicks");
        c.lastTradingTicks = t.getLong("lastTradingTicks");
        c.prevSettlePrice = t.getDouble("prevSettlePrice");
        c.lastPrice = t.getDouble("lastPrice");
        c.limitUp = t.getDouble("limitUp");
        c.limitDown = t.getDouble("limitDown");
        c.marginRatio = t.getDouble("marginRatio");
        int ord = t.getByte("status") & 0xFF;
        c.status = ord < Status.values().length ? Status.values()[ord] : Status.EXPIRED;
        c.consecutiveLimitDays = t.getInt("consecutiveLimitDays");
        c.openInterest = t.getLong("openInterest");
        c.dayTradeNotional = t.getDouble("dayTradeNotional");
        c.dayTradeQuantity = t.getLong("dayTradeQuantity");
        return c;
    }
}
