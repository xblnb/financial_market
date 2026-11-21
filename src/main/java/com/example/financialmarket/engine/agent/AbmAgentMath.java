package com.example.financialmarket.engine.agent;

/** Pure TABL-ABM agent sizing math (no Minecraft/model dependencies). */
public final class AbmAgentMath {

    private AbmAgentMath() {
    }

    public static double valueTarget(double fairValue, double lastPrice, double equity,
                                     double leverage, double valueK, double deadband) {
        if (fairValue <= 0 || lastPrice <= 0 || !Double.isFinite(fairValue)
                || !Double.isFinite(lastPrice)) {
            return 0.0;
        }
        double dev = Math.log(lastPrice / fairValue);
        double eff = Math.copySign(Math.max(0.0, Math.abs(dev) - deadband), dev);
        double maxPos = Math.max(0.0, leverage * equity / lastPrice);
        return clamp(-valueK * eff, -maxPos, maxPos);
    }

    public static int stepQty(double target, double holdings, double steps) {
        double delta = (target - holdings) / Math.max(1.0, steps);
        int qty = (int) Math.round(delta);
        if (qty == 0 && Math.abs(target - holdings) > 1.0) {
            qty = target > holdings ? 1 : -1;
        }
        return qty;
    }

    public static double momentumTarget(double equity, double lastPrice, double leverage,
                                        double momK, boolean up) {
        if (lastPrice <= 0 || !Double.isFinite(lastPrice)) {
            return 0.0;
        }
        double maxPos = Math.max(0.0, leverage * equity / lastPrice);
        return clamp(momK * (up ? 1.0 : -1.0), -maxPos, maxPos);
    }

    public static int retailQty(int base, int span, java.util.Random rng) {
        return base + rng.nextInt(Math.max(1, span + 1));
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
