package com.example.financialmarket.engine.tabl;

/**
 * Read-only projection of the mod's trading calendar for the TABL-ABM stack.
 *
 * <p>The engine tick counter owned by {@code TradingCalendar} is the single time authority. Nothing
 * in TABL may read the wall clock or {@link Math#random()}, so this class exposes exactly the
 * quantities the anchor service, the agents and the venue need, all derived from one {@code tick}
 * argument.</p>
 *
 * <p>The layout mirrors {@code TradingCalendar}: {@link #DAY_TICKS} ticks per period, five open
 * periods followed by two closed ones. The constants are duplicated here on purpose so the TABL
 * layer stays free of Minecraft types; {@code TablT1Probe} cross-checks the derived day numbers
 * against the calendar constants.</p>
 */
public record TabClock(long globalTick, long day, int tickInDay, boolean open, int stepIndex) {

    public static final int DAY_TICKS = 6000;
    public static final int TRADING_DAYS_PER_WEEK = 5;
    public static final int WEEK_PERIODS = 7;
    /** One TABL matching step every 20 ticks => 300 steps per trading day. */
    public static final int STEP_TICKS = 20;

    /** Builds the clock snapshot for an arbitrary engine tick (pure function, no state). */
    public static TabClock at(long tick) {
        long t = Math.max(0L, tick);
        long period = t / DAY_TICKS;
        long weeks = period / WEEK_PERIODS;
        int rem = (int) (period % WEEK_PERIODS);
        boolean open = rem < TRADING_DAYS_PER_WEEK;
        long day = weeks * TRADING_DAYS_PER_WEEK + (open ? rem + 1L : TRADING_DAYS_PER_WEEK);
        int tickInDay = (int) (t % DAY_TICKS);
        int stepIndex = tickInDay / STEP_TICKS;
        return new TabClock(t, day, tickInDay, open, stepIndex);
    }

    /** Fraction of the trading day elapsed, in {@code [0,1)}. */
    public double dayFraction() {
        return tickInDay / (double) DAY_TICKS;
    }

    /** True when this tick is the first tick of a new open day. */
    public boolean isDayStart() {
        return open && tickInDay == 0;
    }

    /** True when the trading day just ended (first tick of a new period). */
    public boolean isDayEnd() {
        return !open && tickInDay == 0;
    }

    @Override
    public String toString() {
        return "TabClock{tick=" + globalTick + ", day=" + day + ", tickInDay=" + tickInDay
                + ", open=" + open + ", step=" + stepIndex + '}';
    }
}
