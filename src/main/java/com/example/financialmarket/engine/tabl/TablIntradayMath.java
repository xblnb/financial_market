package com.example.financialmarket.engine.tabl;

/** Pure intraday anchor interpolation math; no Minecraft/model dependencies, unit-testable. */
public final class TablIntradayMath {

    private TablIntradayMath() {
    }

    /**
     * Interpolate from the previous close to today's TABL anchor, add a Brownian-bridge-shaped
     * noise term, then clamp to the A-share +/-10% band.
     */
    public static double target(double prevClose, double lastMark, double dayAnchor,
                                double sigma, double dayFrac, double z) {
        double f = Math.max(0.0, Math.min(1.0, dayFrac));
        double base = prevClose > 0 ? prevClose : lastMark;
        double target = base + (dayAnchor - base) * f;
        double bridge = Math.sqrt(Math.max(0.0, f * (1.0 - f))) * Math.max(0.001, sigma) * 0.35;
        target *= Math.exp(bridge * z);
        if (prevClose > 0) {
            double lo = prevClose * 0.90;
            double hi = prevClose * 1.10;
            target = Math.max(lo, Math.min(hi, target));
        }
        return Math.max(0.01, target);
    }
}
