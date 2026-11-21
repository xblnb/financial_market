package com.example.financialmarket.engine.macro.growth;

import java.util.List;

/**
 * AK endogenous-growth model.
 *
 * <p>Production is linear in capital: {@code Y = A·K}. Capital evolves with investment and
 * depreciation: {@code K' = (1−δ)K + I}. Savings/investment comes from operating cash flow and
 * retained earnings. The model is deliberately pure so growth and return arithmetic can be tested
 * without Minecraft.</p>
 *
 * <p>This is a supply-side capital-formation layer, not a replacement for the order book or the
 * company asset scanner. It turns productive assets into capital stock, output, investment and a
 * growth/return signal that can be shown in fundamentals and macro.</p>
 */
public final class AkGrowthModel {

    public record Params(double tfp, double depreciation, double savingsRate,
                         double adjustmentCost) {
        public Params {
            tfp = clamp(tfp, 0.01, 2.0);
            depreciation = clamp(depreciation, 0.0, 0.50);
            savingsRate = clamp(savingsRate, 0.0, 0.90);
            adjustmentCost = clamp(adjustmentCost, 0.0, 2.0);
        }
    }

    public record State(double capital, double output, double investment, double consumption,
                        double growth, double returnRate, double depreciation) {
    }

    public record Aggregate(double capital, double output, double investment,
                            double consumption, double growth, double returnRate,
                            double tfp, double savingsRate) {
    }

    private AkGrowthModel() {
    }

    /**
     * @param desiredInvestment explicit investment; negative means "use savingsRate × output"
     */
    public static State step(State prev, Params p, double desiredInvestment) {
        double k = Math.max(1.0, prev == null ? 1.0 : prev.capital());
        double a = p.tfp();
        double y = a * k;
        double desired = desiredInvestment >= 0 ? desiredInvestment : p.savingsRate() * y;
        // Installation/adjustment cost: large projects are less capital-efficient.
        double rawI = clamp(desired, 0.0, 0.60 * k);
        double i = rawI / (1.0 + p.adjustmentCost() * rawI / k);
        i = clamp(i, 0.0, 0.60 * k);
        double kNext = Math.max(1.0, (1.0 - p.depreciation()) * k + i);
        double growth = kNext / k - 1.0;
        double c = Math.max(0.0, y - i);
        double r = a - p.depreciation();
        return new State(kNext, y, i, c, growth, r, p.depreciation());
    }

    /** Firm-level TFP inferred from observed daily output and replacement-capital stock. */
    public static double impliedTfp(double outputValue, double capital, double fallback) {
        if (capital <= 1.0 || outputValue <= 0) return clamp(fallback, 0.01, 2.0);
        return clamp(outputValue / capital, 0.01, 2.0);
    }

    /** Simple Tobin's q proxy: growth relative to required return. */
    public static double tobinQ(double growth, double returnRate, double depreciation) {
        double required = Math.max(0.02, returnRate + depreciation);
        return clamp(1.0 + growth / required, 0.50, 3.0);
    }

    public static Aggregate aggregate(List<State> states) {
        double k = 0.0;
        double y = 0.0;
        double i = 0.0;
        double c = 0.0;
        double depWeighted = 0.0;
        if (states != null) {
            for (State s : states) {
                if (s == null) continue;
                k += Math.max(0.0, s.capital());
                y += Math.max(0.0, s.output());
                i += Math.max(0.0, s.investment());
                c += Math.max(0.0, s.consumption());
                depWeighted += Math.max(0.0, s.depreciation()) * Math.max(0.0, s.capital());
            }
        }
        double dep = k > 0 ? depWeighted / k : 0.05;
        double growth = k > 0 ? i / k - dep : 0.0;
        double tfp = k > 0 ? y / k : 0.0;
        double r = tfp - dep;
        double s = y > 0 ? i / y : 0.0;
        return new Aggregate(k, y, i, c, growth, r, tfp, clamp(s, 0.0, 0.9));
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
