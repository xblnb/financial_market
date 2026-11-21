package com.example.financialmarket.engine.macro;

/**
 * Pure macro-to-stock transmission math. Kept separate so policy/world state can be unit-tested
 * without Minecraft classes and so the same bounds are used by AI companies and the TABL path.
 */
public final class MacroStockTransmission {

    private MacroStockTransmission() {
    }

    /** Sector demand/margin/risk/R&D -> bounded fair-value multiplier. */
    public static double fairValueFactor(double outputIndex, double margin, double risk,
                                         double rnd, double techTier) {
        double demand = 1.0 + 0.5 * (outputIndex - 1.0) + 0.3 * (margin - 0.2);
        demand = Math.max(0.85, Math.min(1.20, demand));
        double riskHaircut = 1.0 - 0.15 * Math.tanh(Math.max(0.0, risk) / 5.0);
        double tech = 1.0 + 0.02 * Math.log1p(Math.max(0.0, rnd))
                + 0.01 * Math.max(0.0, techTier);
        return Math.max(0.70, Math.min(1.30, demand * riskHaircut * tech));
    }

    /** Top-down drift on the TABL anchor: risk appetite + annual drift / 252, bounded +/-3%. */
    public static double priceTilt(double riskAppetite, double annualDrift) {
        double appetite = (riskAppetite - 0.5) / 0.5;
        double tilt = 0.02 * appetite + annualDrift / 252.0;
        return Math.max(-0.03, Math.min(0.03, tilt));
    }

    /** Spread multiplier stays in a sane range; crisis widens, boom narrows. */
    public static double spreadMultiplier(double raw) {
        return Math.max(0.5, Math.min(2.5, raw));
    }

    /** Depth multiplier stays positive; crisis thins, boom deepens. */
    public static double depthMultiplier(double raw) {
        return Math.max(0.2, Math.min(2.0, raw));
    }
}
