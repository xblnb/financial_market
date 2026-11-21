package com.example.financialmarket.engine.tabl;

import com.example.financialmarket.engine.ml.KronosForecaster;

import java.io.IOException;

/**
 * Generates the daily macro anchor with Kronos.
 *
 * <p>Contract (see {@code TABL_ABM_SPEC.md} 鎼?.3): called with the daily bars of the days
 * <em>strictly before</em> {@code day}, it returns an {@link AnchorPath} for the next
 * {@code horizon} days. The call is deterministic: the sampling seed is a pure function of
 * {@code (assetId, day)}, and the calendar mapping is frozen in {@link MarketDayStamps}.</p>
 *
 * <p>Inference is expensive (hundreds of milliseconds), so production code must call this off the
 * server thread and cache the result for the whole trading day. The provider itself only does the
 * stateless work.</p>
 */
public final class KronosAnchorProvider {

    private final int horizon;
    private final double bandZ;
    private final int maxContext;

    public KronosAnchorProvider(int horizon, double bandZ) {
        this(horizon, bandZ, 256);
    }

    /**
     * @param maxContext maximum Kronos context (1..512). The default constructor keeps the
     *                   historical 256-bar cap; long-history evaluation can pass 512 and still
     *                   uses the KV-cache incremental decoder.
     */
    public KronosAnchorProvider(int horizon, double bandZ, int maxContext) {
        this.horizon = Math.max(1, horizon);
        this.bandZ = Math.max(0.1, bandZ);
        this.maxContext = Math.max(1, Math.min(maxContext, KronosForecaster.MAX_CONTEXT));
    }

    public int horizon() {
        return horizon;
    }

    /**
     * @param assetId    asset identifier (part of the seed)
     * @param dailyBars  {@code [n][6]} raw OHLCV, days {@code day-n .. day-1}, ascending
     * @param day        the trading day the anchor is generated for (1-based)
     */
    public AnchorPath anchor(String assetId, double[][] dailyBars, long day) throws IOException {
        int n = dailyBars.length;
        if (n < 2) {
            return flat(assetId, day, n == 1 ? dailyBars[0][3] : 1.0);
        }
        int lookback = Math.min(n, KronosForecaster.MAX_CONTEXT);
        double[][] bars = new double[lookback][];
        System.arraycopy(dailyBars, n - lookback, bars, 0, lookback);
        float[][] barsF = new float[lookback][6];
        for (int i = 0; i < lookback; i++) {
            for (int c = 0; c < 6; c++) {
                barsF[i][c] = (float) bars[i][c];
            }
        }
        int[][] xStamp = MarketDayStamps.history(day - 1, lookback);
        int[][] yStamp = MarketDayStamps.history(day + horizon - 1, horizon);
        long seed = mix(assetId.hashCode(), day);
        KronosForecaster.Params params = new KronosForecaster.Params(
                1.0f, 0, 0.9f, 1, seed, Math.min(Math.max(lookback, horizon), maxContext));

        float[][] pred = KronosForecaster.getInstance().predict(barsF, xStamp, yStamp, horizon, params);
        double[] close = new double[horizon];
        double[] band = new double[horizon];
        double sigma = dailySigma(bars);
        double last = bars[lookback - 1][3];
        for (int i = 0; i < horizon; i++) {
            close[i] = pred[i][3];
            band[i] = bandZ * sigma * Math.sqrt(i + 1) * close[i];
        }
        if (!allFinite(close) || close[0] <= 0) {
            return flat(assetId, day, last);
        }
        return new AnchorPath(assetId, day, close, band, sigma, false);
    }

    /** Fallback used when history is too short or the model is unavailable. */
    public AnchorPath flat(String assetId, long day, double lastClose) {
        double base = lastClose > 0 ? lastClose : 1.0;
        double[] close = new double[horizon];
        double[] band = new double[horizon];
        for (int i = 0; i < horizon; i++) {
            close[i] = base;
            band[i] = 0.0;
        }
        return new AnchorPath(assetId, day, close, band, 0.0, true);
    }

    private static double dailySigma(double[][] bars) {
        int n = bars.length;
        double sum = 0;
        int count = 0;
        for (int i = 1; i < n; i++) {
            double prev = bars[i - 1][3];
            double now = bars[i][3];
            if (prev > 0 && now > 0) {
                sum += Math.abs(Math.log(now / prev));
                count++;
            }
        }
        double mean = count == 0 ? 0.0 : sum / count;
        double var = 0;
        for (int i = 1; i < n; i++) {
            double prev = bars[i - 1][3];
            double now = bars[i][3];
            if (prev > 0 && now > 0) {
                double d = Math.abs(Math.log(now / prev)) - mean;
                var += d * d;
            }
        }
        return count == 0 ? 0.02 : Math.sqrt(var / count);
    }

    private static boolean allFinite(double[] values) {
        for (double v : values) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    /** Deterministic seed: asset hash 鑴?day, avalanche-mixed. */
    static long mix(long assetHash, long day) {
        long z = assetHash * 0x9E3779B97F4A7C15L + day * 0xC2B2AE3D27D4EB4FL;
        z ^= z >>> 30;
        z *= 0xBF58476D1CE4E5B9L;
        z ^= z >>> 27;
        z *= 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
