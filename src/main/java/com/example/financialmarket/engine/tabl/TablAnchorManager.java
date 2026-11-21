package com.example.financialmarket.engine.tabl;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Owns the anchor lifecycle: one daily Kronos anchor per asset per trading day, plus an intraday
 * TABL correction re-estimated every {@code reestimateSteps} steps.
 *
 * <p>Time contract (see {@code TABL_ABM_SPEC.md} �?/�?.3): the daily anchor only changes on a
 * {@link TabClock#isDayStart()} tick; the correction may change only when
 * {@code stepIndex % reestimateSteps == 0}; both are pure functions of the engine tick, the daily
 * bars up to the previous day and the current mark. No wall clock, no look-ahead.</p>
 *
 * <p>Production code should call {@link #anchor} from the server thread; the Kronos call itself is
 * expensive (~0.4 s), so callers may pre-warm the daily anchor off-thread through
 * {@link #refreshDaily} and let the tick path hit the cache.</p>
 */
public final class TablAnchorManager {

    private final KronosAnchorProvider provider;
    private final TemporalBilinearModel model;
    private final int lookback;
    private final int reestimateSteps;
    private final double clampFactor;

    private final Map<String, AnchorPath> daily = new LinkedHashMap<>();
    private final Map<String, Long> dailyDay = new LinkedHashMap<>();
    private final Map<String, AnchorPath> current = new LinkedHashMap<>();
    private final Map<String, Long> lastReestimateStep = new LinkedHashMap<>();
    private int reestimates;
    private int staleDays;

    public TablAnchorManager(long seed, int horizon, int lookback, int reestimateSteps, double clampFactor) {
        this.provider = new KronosAnchorProvider(horizon, 2.0);
        this.model = new TemporalBilinearModel(seed, lookback, 1, horizon, 1e-3);
        this.lookback = Math.max(8, lookback);
        this.reestimateSteps = Math.max(1, reestimateSteps);
        this.clampFactor = Math.max(0.0, clampFactor);
    }

    /** Forces a daily-anchor refresh (safe to call off-thread before the day starts). */
    public synchronized AnchorPath refreshDaily(String assetId, double[][] bars, long day) {
        AnchorPath path;
        try {
            path = provider.anchor(assetId, bars, day);
        } catch (IOException | RuntimeException e) {
            path = provider.flat(assetId, day, bars.length == 0 ? 1.0 : bars[bars.length - 1][3]);
        }
        if (path.stale()) {
            staleDays++;
        }
        daily.put(assetId, path);
        dailyDay.put(assetId, day);
        current.put(assetId, path);
        lastReestimateStep.put(assetId, -1L);
        if (bars.length >= lookback + 2) {
            model.fit(bars, bars.length - 1);
        }
        return path;
    }

    /**
     * Async daily prewarm: Kronos runs on the supplied executor, then the result is installed with
     * a short synchronized write. The server thread never blocks on the model.
     */
    public CompletableFuture<AnchorPath> refreshDailyAsync(String assetId, double[][] bars, long day,
                                                           Executor executor) {
        final double[][] snapshot = bars;
        return CompletableFuture.supplyAsync(() -> {
            AnchorPath path;
            try {
                path = provider.anchor(assetId, snapshot, day);
            } catch (IOException | RuntimeException e) {
                path = provider.flat(assetId, day,
                        snapshot.length == 0 ? 1.0 : snapshot[snapshot.length - 1][3]);
            }
            synchronized (this) {
                if (path.stale()) {
                    staleDays++;
                }
                daily.put(assetId, path);
                dailyDay.put(assetId, day);
                current.put(assetId, path);
                lastReestimateStep.put(assetId, -1L);
            }
            return path;
        }, executor);
    }

    /** Anchor only if the daily path is already available (never blocks / never refreshes). */
    public synchronized AnchorPath anchorIfReady(String assetId, long tick, double lastMark) {
        TabClock clock = TabClock.at(tick);
        AnchorPath base = daily.get(assetId);
        if (base == null || !Long.valueOf(clock.day()).equals(dailyDay.get(assetId))) {
            return null;
        }
        if (!clock.open()) {
            return base;
        }
        return current.getOrDefault(assetId, base);
    }

    /**
     * Anchor visible at {@code tick}.
     *
     * @param bars     daily bars up to the day before {@code tick}'s trading day
     * @param lastMark last traded/mid price used by the correction term
     */
    public synchronized AnchorPath anchor(String assetId, long tick, double[][] bars, double lastMark) {
        TabClock clock = TabClock.at(tick);
        AnchorPath base = daily.get(assetId);
        if (base == null || !Long.valueOf(clock.day()).equals(dailyDay.get(assetId))) {
            base = refreshDaily(assetId, bars, clock.day());
        }
        if (!clock.open()) {
            return base;
        }
        long globalStep = clock.globalTick() / TabClock.STEP_TICKS;
        long last = lastReestimateStep.getOrDefault(assetId, -1L);
        boolean due = last < 0 || (globalStep - last) >= reestimateSteps;
        if (!due) {
            return current.getOrDefault(assetId, base);
        }
        lastReestimateStep.put(assetId, globalStep);
        reestimates++;
        AnchorPath corrected = correct(assetId, base, bars, clock, lastMark);
        current.put(assetId, corrected);
        return corrected;
    }

    private AnchorPath correct(String assetId, AnchorPath base, double[][] bars, TabClock clock, double lastMark) {
        int h = base.horizon();
        double[] close = base.close().clone();
        double[] band = base.band().clone();
        double ref = base.close()[0];
        double markDelta = lastMark > 0 ? lastMark - base.at(0.0) : 0.0;
        double[][] forecast = bars.length > lookback ? model.forecast(bars, bars.length - 1) : null;
        double decay = 0.7;
        for (int i = 0; i < h; i++) {
            double modelDelta = 0.0;
            if (forecast != null && forecast.length > i && forecast[i].length > 0) {
                modelDelta = ref * forecast[i][0];
            }
            double corr = 0.5 * (modelDelta + markDelta * Math.pow(decay, i + 1));
            double limit = clampFactor * band[i];
            corr = Math.max(-limit, Math.min(limit, corr));
            close[i] = Math.max(1e-6, close[i] + corr);
        }
        return new AnchorPath(assetId, base.day(), close, band, base.sigma(), base.stale());
    }

    public int reestimates() {
        return reestimates;
    }

    /** The uncorrected daily anchor (diagnostics); may be null before the first refresh. */
    public synchronized AnchorPath dailyAnchor(String assetId) {
        return daily.get(assetId);
    }

    public int staleDays() {
        return staleDays;
    }

    public synchronized void reset() {
        daily.clear();
        dailyDay.clear();
        current.clear();
        lastReestimateStep.clear();
        reestimates = 0;
        staleDays = 0;
    }
}
