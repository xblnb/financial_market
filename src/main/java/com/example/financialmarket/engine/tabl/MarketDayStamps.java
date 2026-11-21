package com.example.financialmarket.engine.tabl;

/**
 * Calendar mapping for the anchor layer.
 *
 * <p>Kronos expects the five temporal features used by its predictor
 * ({@code minute, hour, weekday, day-of-month, month}). The mod has no wall calendar, only a
 * monotonic trading-day counter, so the mapping is defined explicitly and deterministically:</p>
 *
 * <pre>
 *   day D  ->  minute = 0, hour = 9, weekday = (D-1) mod 7,
 *              day-of-month = 1 + (D-1) mod 28, month = 1 + ((D-1)/28) mod 12
 * </pre>
 *
 * <p>A 28-day month keeps every month identical and makes the mapping a pure function of {@code D};
 * the exact calendar semantics do not matter to the model, only that the mapping is stable and
 * monotone across runs and save files.</p>
 */
public final class MarketDayStamps {

    public static final int MINUTE = 0;
    public static final int HOUR = 9;

    private MarketDayStamps() {
    }

    /** Stamp for trading day {@code day} (1-based), as {@code {minute, hour, weekday, day, month}}. */
    public static int[] forDay(long day) {
        long d = Math.max(1L, day);
        int weekday = (int) ((d - 1) % 7);
        int dayOfMonth = (int) (1 + (d - 1) % 28);
        int month = (int) (1 + ((d - 1) / 28) % 12);
        return new int[]{MINUTE, HOUR, weekday, dayOfMonth, month};
    }

    /** Stamps for the {@code count} trading days ending at {@code lastDay} (inclusive, ascending). */
    public static int[][] history(long lastDay, int count) {
        int n = Math.max(1, count);
        int[][] out = new int[n][];
        long first = Math.max(1L, lastDay - n + 1);
        for (int i = 0; i < n; i++) {
            out[i] = forDay(first + i);
        }
        return out;
    }
}
