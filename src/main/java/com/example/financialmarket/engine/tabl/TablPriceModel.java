package com.example.financialmarket.engine.tabl;

import com.example.financialmarket.engine.OHLCData;
import com.example.financialmarket.engine.PriceDiscoveryEngine;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Production bridge from the TABL model to the mod.
 *
 * <p>The handwritten GBM/GARCH path is no longer the primary price process. This class asks
 * {@link TablAnchorManager} for a Kronos/TABL daily anchor, interpolates it through the current
 * trading day, and adds a deterministic intraday bridge/jump term plus A-share +/-10% limit
 * clamping. The mod's order book/agents still execute around the resulting mark; they no longer
 * own the fundamental path.</p>
 *
 * <p>Kronos is only refreshed when the TABL day changes (once per trading day), not per tick. If
 * the model is unavailable, {@link TablAnchorManager} falls back to a flat anchor, so the server
 * keeps running.</p>
 */
public final class TablPriceModel {

    private final TablAnchorManager anchors;
    private final Map<String, double[][]> barsByAsset = new LinkedHashMap<>();
    private final Map<String, Long> barDay = new LinkedHashMap<>();
    private final Set<String> prewarming = new LinkedHashSet<>();
    private final ExecutorService prewarm = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tabl-anchor-prewarm");
        t.setDaemon(true);
        return t;
    });

    public TablPriceModel(long seed) {
        this.anchors = new TablAnchorManager(seed, 60, 256, 300, 2.0);
    }

    /**
     * @param lastMark  current observable price
     * @param prevClose previous daily close (limit band); 0 disables the band
     */
    public double target(String assetId, long tick, double lastMark, double prevClose,
                         PriceDiscoveryEngine engine) {
        TabClock clock = TabClock.at(tick);
        ensureBars(assetId, clock.day(), engine);
        double[][] bars = barsByAsset.get(assetId);
        if (bars == null || bars.length < 8) {
            return Double.NaN;
        }
        AnchorPath anchor;
        try {
            anchor = anchors.anchorIfReady(assetId, tick, lastMark);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
        if (anchor == null || anchor.horizon() <= 0 || anchor.stale()) {
            return Double.NaN;
        }
        // close[0] is today's anchor close; interpolate from the previous close to it through the
        // session, instead of jumping to the daily close on the first tick.
        double dayTarget = anchor.close()[0];
        return TablIntradayMath.target(prevClose, lastMark, dayTarget,
                anchor.sigma() > 0 ? anchor.sigma() : 0.02,
                clock.dayFraction(), hashGaussian(assetId, tick));
    }

    private void ensureBars(String assetId, long day, PriceDiscoveryEngine engine) {
        if (Long.valueOf(day).equals(barDay.get(assetId))) {
            return;
        }
        List<OHLCData> daily = engine.getAggregatedKlines(assetId,
                PriceDiscoveryEngine.KlineType.DAILY, 256);
        if (daily == null || daily.size() < 8) {
            return;
        }
        double[][] bars = new double[daily.size()][6];
        for (int i = 0; i < daily.size(); i++) {
            OHLCData k = daily.get(i);
            bars[i][0] = k.getOpen();
            bars[i][1] = k.getHigh();
            bars[i][2] = k.getLow();
            bars[i][3] = k.getClose();
            bars[i][4] = k.getVolume();
            bars[i][5] = 0.0;
        }
        barsByAsset.put(assetId, bars);
        if (!prewarming.add(assetId)) {
            return;
        }
        anchors.refreshDailyAsync(assetId, bars, day, prewarm).whenComplete((path, error) -> {
            prewarming.remove(assetId);
            if (error == null && path != null) {
                barDay.put(assetId, day);
            }
        });
    }

    /** Deterministic standard-normal from (assetId, tick); avoids shared RNG state. */
    private static double hashGaussian(String assetId, long tick) {
        long h = assetId == null ? 0 : assetId.hashCode();
        long seed = h * 0x9E3779B97F4A7C15L + tick * 0xBF58476D1CE4E5B9L;
        Random r = new Random(seed);
        return r.nextGaussian();
    }

    public TablAnchorManager anchors() {
        return anchors;
    }

    public AnchorPath dailyAnchor(String assetId) {
        return anchors.dailyAnchor(assetId);
    }

    public void reset() {
        barsByAsset.clear();
        barDay.clear();
        anchors.reset();
    }

    public String describe(String assetId) {
        AnchorPath a = anchors.dailyAnchor(assetId);
        return a == null ? "no-anchor" : String.format(Locale.ROOT,
                "day=%d H=%d first=%.3f last=%.3f stale=%s",
                a.day(), a.horizon(), a.close()[0], a.close()[a.horizon() - 1], a.stale());
    }
}
