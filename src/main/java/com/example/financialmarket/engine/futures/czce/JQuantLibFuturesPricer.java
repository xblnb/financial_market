package com.example.financialmarket.engine.futures.czce;

import com.example.financialmarket.engine.ai.JQuantLibBridge;

/**
 * Cost-of-carry fair value for CZCE commodity futures:
 * {@code F = S * exp((storage - convenience) * T) / discountFactor(r, T)}.
 *
 * <p>JQuantLib supplies the discount factor when present; otherwise the bridge's internal
 * exponential curve is used.</p>
 */
public final class JQuantLibFuturesPricer {

    private JQuantLibFuturesPricer() {
    }

    public static double fairValue(double spot, double annualRate, double storagePerYear,
                                   double conveniencePerYear, double yearsToDelivery) {
        if (!(spot > 0) || !Double.isFinite(spot)) {
            return 0.0;
        }
        double t = Math.max(0.0, yearsToDelivery);
        double carry = (storagePerYear - conveniencePerYear) * t;
        double df = JQuantLibBridge.discountFactor(Math.max(0.0, annualRate), t);
        if (!(df > 0) || !Double.isFinite(df)) {
            return spot;
        }
        return spot / df * Math.exp(carry);
    }
}
