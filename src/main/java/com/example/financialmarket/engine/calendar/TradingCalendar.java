package com.example.financialmarket.engine.calendar;

import net.minecraft.nbt.CompoundTag;

/**
 * A 股交易日历 / 时段模型（AS 规格 §2）。
 *
 * <p>把「一个交易日 = {@code gameTime/24000}（Minecraft 昼夜）」改为 A 股风格时间模型：
 * <ul>
 *   <li>一个交易日 = {@link #TRADING_DAY_TICKS}（6000）tick = 5 分钟实时 @20tps（300s×20）；</li>
 *   <li>交易日内<strong>全天连续可交易（无午休）</strong>：一个开市期内每个 tick 均可挂单/撮合；</li>
 *   <li>每 {@link #TRADING_DAYS_PER_WEEK}（5）个交易日休市 {@link #CLOSED_DAYS_PER_WEEK}（2）个交易日
 *       （7 期周期：期 0-4 开市、期 5-6 休市）；</li>
 *   <li>{@link #tradingDayNumber} 只在开市期边界 +1（单调递增），用于 T+1 结转与 prevClose 锚定。</li>
 * </ul>
 *
 * <p><strong>时间权威 = 引擎 tick 计数器</strong>：本类是一个关于 tick 的确定性纯函数演化，无任何随机源。
 * 同 tick 数 → 同时段 → 同序号，保证同种子可复现（A3-1/A3-2）。
 * 不用 {@code gameTime}，因为 {@code gameTime} 可被 {@code /time} 改动而破坏时段确定性。
 *
 * <p>状态（{@code period}/{@code tradingDayNumber}/{@code marketOpen}/{@code tick}）随 {@link #save()}
 * 持久化、{@link #load(CompoundTag)} 恢复（旧存档缺字段用 {@code contains} 兜底，读档不失败，A3-8）。
 */
public class TradingCalendar {

    // ================= 时段参数（固定常量，可复现） =================
    /** 一个交易日 = 5 分钟实时 @ 20tps（300s×20）。 */
    public static final long TRADING_DAY_TICKS = 6000;
    /** 每周期开市交易日数。 */
    public static final int TRADING_DAYS_PER_WEEK = 5;
    /** 每周期休市日数。 */
    public static final int CLOSED_DAYS_PER_WEEK = 2;
    /** 一个周期总期数（开市 + 休市）= 7。 */
    public static final int WEEK_PERIODS = TRADING_DAYS_PER_WEEK + CLOSED_DAYS_PER_WEEK;

    // ================= 状态 =================
    private long period;           // 当前期序号 = tick / TRADING_DAY_TICKS（单调）
    private long tradingDayNumber; // 已开市的交易日序号（只在开市期边界 +1，单调）
    private boolean marketOpen;    // 本期是否开市
    private long tick;             // 最近一次 advance 的时间权威 tick

    // 边界瞬态标志（由最近一次 advance() 计算，供同 tick 内的日切换/休市处理读取）
    private boolean sessionEndFlag;
    private boolean tradingDayStartFlag;

    /** 期 0 即开市（首个交易日），tradingDayNumber 从 1 起。 */
    public TradingCalendar() {
        this.period = 0;
        this.tradingDayNumber = 1;
        this.marketOpen = true;
        this.tick = 0;
        this.sessionEndFlag = false;
        this.tradingDayStartFlag = false;
    }

    /**
     * 每 tick 推进：由外部以引擎 tick 计数器调用。纯函数演化，无随机。
     * 跨期边界时计算 sessionEnd / tradingDayStart 标志供本 tick 内处理。
     */
    public void advance(long tick) {
        this.tick = tick;
        this.sessionEndFlag = false;
        this.tradingDayStartFlag = false;
        long newPeriod = tick / TRADING_DAY_TICKS;
        if (newPeriod != period) {
            this.period = newPeriod;
            // 上一期会话已结束（用于 prevClose 落定）
            this.sessionEndFlag = true;
            this.marketOpen = (period % WEEK_PERIODS) < TRADING_DAYS_PER_WEEK;
            if (marketOpen) {
                // 进入新的开市期 → 新交易日
                this.tradingDayNumber++;
                this.tradingDayStartFlag = true;
            }
        }
    }

    /** 本期是否开市（期 0-4 开、期 5-6 休）。 */
    public boolean isMarketOpen() {
        return marketOpen;
    }

    /** 已开市的交易日序号（单调递增，用于 T+1 结转与 prevClose 锚定）。 */
    public long getTradingDayNumber() {
        return tradingDayNumber;
    }

    /** 当前期序号 = tick / TRADING_DAY_TICKS（单调）。 */
    public long getPeriod() {
        return period;
    }

    /** 当前时间权威 tick。 */
    public long getTick() {
        return tick;
    }

    /**
     * 是否为「本期期首」边界（本 tick 内已进入一个新的开市期），用于日重置与 T+1 结转。
     * 由最近一次 {@link #advance(long)} 计算。
     */
    public boolean isTradingDayStart() {
        return tradingDayStartFlag;
    }

    /**
     * 是否为「本期期末 / 进入新期」边界（上一期会话已结束），用于 prevClose 落定。
     * 由最近一次 {@link #advance(long)} 计算。
     */
    public boolean isSessionEnd() {
        return sessionEndFlag;
    }

    // ================= 确定性纯函数辅助 =================

    /** 给定任意 tick，判定该 tick 是否处于开市交易日（纯函数，可复现）。 */
    public static boolean isTradingDay(long tick) {
        long p = tick / TRADING_DAY_TICKS;
        return (p % WEEK_PERIODS) < TRADING_DAYS_PER_WEEK;
    }

    /** 给定任意 tick，返回该 tick 所在期的开市交易日序号（期 0-4 计 1-5，休市期沿用上一开市序号）。 */
    public static long tradingDayNumberAt(long tick) {
        long p = tick / TRADING_DAY_TICKS;
        long openPeriods = 0;
        for (long i = 0; i <= p; i++) {
            if ((i % WEEK_PERIODS) < TRADING_DAYS_PER_WEEK) openPeriods++;
        }
        return openPeriods;
    }

    /** 返回自当前 tick 之后的下一个开市期首 tick（下一个交易日开盘 tick）。 */
    public long nextOpenTick() {
        long nextPeriodStart = (tick / TRADING_DAY_TICKS + 1) * TRADING_DAY_TICKS;
        long p = nextPeriodStart / TRADING_DAY_TICKS;
        while ((p % WEEK_PERIODS) >= TRADING_DAYS_PER_WEEK) {
            p++;
        }
        return p * TRADING_DAY_TICKS;
    }

    // ================= 持久化（A3-8） =================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("period", period);
        tag.putLong("tradingDayNumber", tradingDayNumber);
        tag.putBoolean("marketOpen", marketOpen);
        tag.putLong("tick", tick);
        return tag;
    }

    /** 恢复日历状态；旧存档缺字段用 contains 兜底，读档不失败。 */
    public void load(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("period")) period = tag.getLong("period");
        if (tag.contains("tradingDayNumber")) tradingDayNumber = tag.getLong("tradingDayNumber");
        if (tag.contains("marketOpen")) marketOpen = tag.getBoolean("marketOpen");
        if (tag.contains("tick")) tick = tag.getLong("tick");
        // 瞬态边界标志不需要恢复（下一个 tick 的 advance 重新计算）
        this.sessionEndFlag = false;
        this.tradingDayStartFlag = false;
    }

    @Override
    public String toString() {
        return "TradingCalendar{period=" + period + ", tradingDayNumber=" + tradingDayNumber
                + ", marketOpen=" + marketOpen + ", tick=" + tick + "}";
    }
}
