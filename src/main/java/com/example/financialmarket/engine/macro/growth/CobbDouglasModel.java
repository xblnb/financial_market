package com.example.financialmarket.engine.macro.growth;

/**
 * Cobb–Douglas production function: {@code Y = A·K^α·L^(1−α)}.
 *
 * <p>AK is the special case {@code α = 1}. This class computes output, implied TFP, marginal
 * products and a capital-gap signal used for investment allocation.</p>
 */
public final class CobbDouglasModel {

    public record Result(double output, double tfp, double mpk, double mpl,
                         double capitalShare, double laborShare,
                         double optimalCapital, double capitalGap, double returnRate) {
    }

    private CobbDouglasModel() {
    }

    public static double output(double tfp, double capital, double labor, double alpha) {
        double a = clamp(tfp, 0.0, 2.0);
        double k = Math.max(1.0, capital);
        double l = Math.max(0.01, labor);
        double al = clamp(alpha, 0.05, 0.95);
        return a * Math.pow(k, al) * Math.pow(l, 1.0 - al);
    }

    public static double impliedTfp(double output, double capital, double labor, double alpha) {
        double y = Math.max(0.0, output);
        double k = Math.max(1.0, capital);
        double l = Math.max(0.01, labor);
        double al = clamp(alpha, 0.05, 0.95);
        return clamp(y / (Math.pow(k, al) * Math.pow(l, 1.0 - al)), 0.0, 2.0);
    }

    /**
     * @param targetReturn required return used for the optimal-capital benchmark
     */
    public static Result evaluate(double output, double capital, double labor, double alpha,
                                  double depreciation, double targetReturn) {
        double y = Math.max(0.0, output);
        double k = Math.max(1.0, capital);
        double l = Math.max(0.01, labor);
        double al = clamp(alpha, 0.05, 0.95);
        double delta = clamp(depreciation, 0.0, 0.5);
        double rTarget = clamp(targetReturn, 0.01, 0.30);
        double tfp = impliedTfp(y, k, l, al);
        double mpk = al * y / k;
        double mpl = (1.0 - al) * y / l;
        double optimalK = al * y / Math.max(0.01, rTarget + delta);
        double gap = (optimalK - k) / k;
        return new Result(y, tfp, mpk, mpl, al, 1.0 - al, optimalK, gap, mpk - delta);
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
