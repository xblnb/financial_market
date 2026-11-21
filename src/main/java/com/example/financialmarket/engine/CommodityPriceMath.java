package com.example.financialmarket.engine;

/**
 * Pure commodity price dynamics used by {@link CommodityEntry}.
 *
 * <p>Real-world stylised facts: mean reversion to a cost/supply-demand fair value, short-horizon
 * momentum, seasonality for crops/energy/construction, and volatility clustering with rare
 * deterministic supply shocks. No Math.random, so the server is reproducible.</p>
 */
public final class CommodityPriceMath {

    public record Step(double price, double fairValue, double momentum, double volatility,
                       double seasonality, double lastReturn) {
    }

    private CommodityPriceMath() {
    }

    public static double fairValue(double costAnchor, double supply, double demand,
                                   double macroFactor, double driftToCost) {
        double s = Math.max(0.0, supply) + 1.0;
        double d = Math.max(0.0, demand) + 1.0;
        double excess = (d - s) / (s + d);
        return costAnchor * (1.0 + driftToCost * excess) * macroFactor;
    }

    public static Step step(double price, double fairValue, double momentum, double volatility,
                            String key, long day, double floor, double cap) {
        double seasonal = seasonalFactor(key, day);
        double shock = deterministicShock(key, day) * (0.004 + 0.55 * volatility);
        double reversion = 0.12 * (fairValue - price);
        double mom = 0.18 * momentum;
        double next = price + reversion + mom + price * (0.25 * seasonal + shock);
        next = Math.max(floor, Math.min(cap, next));
        double ret = price > 0 ? (next - price) / price : 0.0;
        double newMomentum = clamp(0.78 * momentum + 0.22 * ret, -0.15, 0.15);
        double newVol = clamp(0.92 * volatility + 0.08 * Math.abs(ret)
                + 0.004 * Math.abs(shock), 0.003, 0.20);
        return new Step(roundPrice(next), round2(fairValue), newMomentum, newVol, seasonal, ret);
    }

    public static double seasonalFactor(String key, long day) {
        String k = key == null ? "" : key.toLowerCase();
        double phase = 2.0 * Math.PI * (Math.floorMod(day, 365)) / 365.0;
        if (k.contains("wheat") || k.contains("carrot") || k.contains("potato")
                || k.contains("apple") || k.contains("berry") || k.contains("beetroot")
                || k.contains("melon") || k.contains("crop") || k.contains("seed")) {
            return 0.045 * Math.sin(phase - 0.6);
        }
        if (k.contains("coal") || k.contains("oil") || k.contains("fuel")
                || k.contains("lava") || k.contains("energy")) {
            return 0.035 * Math.cos(phase);
        }
        if (k.contains("log") || k.contains("plank") || k.contains("stone")
                || k.contains("brick") || k.contains("concrete")) {
            return 0.020 * Math.sin(phase + 0.4);
        }
        return 0.0;
    }

    public static double deterministicShock(String key, long day) {
        long h = 1125899906842597L;
        String k = key == null ? "" : key;
        for (int i = 0; i < k.length(); i++) h = 31L * h + k.charAt(i);
        h = 31L * h + day;
        double u = ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
        double noise = u * 2.0 - 1.0;
        long jumpSeed = Math.floorMod(h ^ (h >>> 29), 47L);
        if (jumpSeed == 0) noise += (u > 0.5 ? 1.0 : -1.0) * 2.5;
        return noise;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Low-priced commodities need sub-cent precision or their charts become staircases. */
    private static double roundPrice(double v) {
        if (Math.abs(v) < 10.0) return Math.round(v * 10000.0) / 10000.0;
        return round2(v);
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
