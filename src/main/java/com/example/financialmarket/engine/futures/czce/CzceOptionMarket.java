package com.example.financialmarket.engine.futures.czce;

import net.finmath.functions.AnalyticFormulas;

import java.util.ArrayList;
import java.util.List;

/**
 * CZCE futures-option chain and Greeks, powered by finmath-lib's Black-76 / Black-Scholes
 * analytic formulas. Prices are in the same quote units as the underlying futures.
 */
public final class CzceOptionMarket {

    public enum Type { CALL, PUT }

    public record Greeks(double price, double delta, double gamma, double vega, double theta,
                         double impliedVol) {
    }

    public record Quote(String underlying, Type type, double strike, double expiryDays,
                        double years, Greeks greeks) {
    }

    private CzceOptionMarket() {
    }

    public static double price(double forward, double strike, double vol, double years,
                               double rate, Type type) {
        double t = Math.max(0.0, years);
        if (t <= 0.0) {
            return type == Type.CALL ? Math.max(0.0, forward - strike)
                    : Math.max(0.0, strike - forward);
        }
        double df = Math.exp(-rate * t);
        double call = AnalyticFormulas.blackScholesGeneralizedOptionValue(
                Math.max(1e-9, forward), Math.max(1e-9, vol), t, Math.max(1e-9, strike), df);
        if (type == Type.CALL) {
            return Math.max(0.0, call);
        }
        return Math.max(0.0, call - df * (forward - strike));
    }

    public static Greeks greeks(double forward, double strike, double vol, double years,
                                double rate, Type type) {
        double t = Math.max(1.0 / 365.0, years);
        double p = price(forward, strike, vol, t, rate, type);
        double callDelta = AnalyticFormulas.blackScholesOptionDelta(
                Math.max(1e-9, forward), rate, Math.max(1e-9, vol), t, Math.max(1e-9, strike));
        double delta = type == Type.CALL ? callDelta : callDelta - 1.0;
        double gamma = AnalyticFormulas.blackScholesOptionGamma(
                Math.max(1e-9, forward), rate, Math.max(1e-9, vol), t, Math.max(1e-9, strike));
        double vega = AnalyticFormulas.blackScholesOptionVega(
                Math.max(1e-9, forward), rate, Math.max(1e-9, vol), t, Math.max(1e-9, strike)) / 100.0;
        double t1 = Math.max(1.0 / 365.0, t - 1.0 / 365.0);
        double theta = (price(forward, strike, vol, t1, rate, type) - p) / 365.0;
        double iv = Math.max(1e-4, vol);
        return new Greeks(p, delta, gamma, vega, theta, iv);
    }

    /** Volatility smile: ATM base plus skew and curvature in log-moneyness. */
    public static double smileVol(double baseVol, double forward, double strike) {
        double x = Math.log(Math.max(1e-9, strike) / Math.max(1e-9, forward));
        double v = baseVol * (1.0 + 0.55 * x - 1.8 * x * x);
        return Math.max(0.35 * baseVol, Math.min(2.5 * baseVol, v));
    }

    public static List<Quote> chain(String underlying, double forward, double baseVol, double rate,
                                     double[] expiryDays, double[] strikes) {
        List<Quote> out = new ArrayList<>();
        for (double days : expiryDays) {
            double years = Math.max(1.0, days) / 365.0;
            for (double strike : strikes) {
                double vol = smileVol(baseVol, forward, strike);
                for (Type type : Type.values()) {
                    out.add(new Quote(underlying, type, strike, days, years,
                            greeks(forward, strike, vol, years, rate, type)));
                }
            }
        }
        return out;
    }

    /** SABR-smile option chain; Black-76 is still used to convert IV to price. */
    public static List<Quote> chainSabr(String underlying, double forward, SabrModel.Params sabr,
                                        double rate, double[] expiryDays, double[] strikes) {
        List<Quote> out = new ArrayList<>();
        for (double days : expiryDays) {
            double years = Math.max(1.0, days) / 365.0;
            for (double strike : strikes) {
                for (Type type : Type.values()) {
                    SabrModel.Greeks g = SabrModel.greeks(forward, strike, years, sabr, rate, type);
                    out.add(new Quote(underlying, type, strike, days, years,
                            new Greeks(g.price(), g.delta(), g.gamma(), g.vega(), g.theta(),
                                    g.iv())));
                }
            }
        }
        return out;
    }
}
