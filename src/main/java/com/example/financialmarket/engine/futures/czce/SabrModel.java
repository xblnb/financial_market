package com.example.financialmarket.engine.futures.czce;

import net.finmath.functions.AnalyticFormulas;

import java.util.Random;

/**
 * SABR (Hagan 2002) implied-volatility model for CZCE futures options.
 *
 * <p>SABR produces the IV smile; Black-76 (finmath) converts IV to price. The state
 * {@code alpha, rho, nu} is perturbed daily, and the perturbation is correlated with the
 * underlying return: negative returns raise alpha and push rho more negative (leverage effect).</p>
 */
public final class SabrModel {

    public static final class Params {
        public double alpha;
        public double beta;
        public double rho;
        public double nu;
        public double baseAlpha;
        public double baseRho;
        public double baseNu;

        public Params(double alpha, double beta, double rho, double nu) {
            this.alpha = alpha;
            this.beta = beta;
            this.rho = rho;
            this.nu = nu;
            this.baseAlpha = alpha;
            this.baseRho = rho;
            this.baseNu = nu;
        }

        public Params copy() {
            Params p = new Params(alpha, beta, rho, nu);
            p.baseAlpha = baseAlpha;
            p.baseRho = baseRho;
            p.baseNu = baseNu;
            return p;
        }
    }

    public record Greeks(double price, double iv, double delta, double gamma, double vega,
                         double theta) {
    }

    private SabrModel() {
    }

    /** Hagan lognormal SABR implied volatility. */
    public static double impliedVol(double forward, double strike, double years, Params p) {
        double f = Math.max(1e-9, forward);
        double k = Math.max(1e-9, strike);
        double t = Math.max(1e-9, years);
        double beta = p.beta;
        double oneMinusBeta = 1.0 - beta;
        double logFK = Math.log(f / k);
        double fk = Math.pow(f * k, oneMinusBeta / 2.0);
        boolean atm = Math.abs(logFK) < 1e-8;
        double alpha = Math.max(1e-9, p.alpha);
        double nu = Math.max(1e-6, p.nu);
        double rho = clamp(p.rho, -0.999, 0.999);
        double term1 = 1.0 + oneMinusBeta * oneMinusBeta / 24.0 * logFK * logFK
                + Math.pow(oneMinusBeta, 4) / 1920.0 * Math.pow(logFK, 4);
        double a = alpha / (fk * term1);
        double z = nu / alpha * fk * logFK;
        double x;
        if (atm) {
            x = 1.0;
        } else {
            double root = Math.sqrt(Math.max(1e-12, 1.0 - 2.0 * rho * z + z * z));
            double num = root + z - rho;
            double den = 1.0 - rho;
            x = z / Math.log(Math.max(1e-12, num / den));
            if (!Double.isFinite(x)) {
                x = 1.0;
            }
        }
        double correction = 1.0 + (
                oneMinusBeta * oneMinusBeta / 24.0 * alpha * alpha / Math.pow(f * k, oneMinusBeta)
                        + 0.25 * rho * beta * nu * alpha / Math.pow(f * k, oneMinusBeta / 2.0)
                        + (2.0 - 3.0 * rho * rho) / 24.0 * nu * nu
        ) * t;
        double vol = a * x * correction;
        return clamp(vol, 0.005, 3.0);
    }

    /** Solves alpha so that the ATM SABR vol equals the target. */
    public static Params fromAtmVol(double forward, double targetVol, double years, double beta,
                                    double rho, double nu) {
        double f = Math.max(1e-9, forward);
        double lo = 1e-8;
        double hi = Math.max(1e-4, targetVol * Math.pow(f, 1.0 - beta) * 4.0);
        Params p = new Params(1.0, beta, rho, nu);
        for (int i = 0; i < 80; i++) {
            double mid = 0.5 * (lo + hi);
            p.alpha = mid;
            double v = impliedVol(f, f, years, p);
            if (v < targetVol) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        p.alpha = 0.5 * (lo + hi);
        p.baseAlpha = p.alpha;
        p.baseRho = rho;
        p.baseNu = nu;
        return p;
    }

    /** One-day stochastic perturbation with leverage effect. */
    public static void evolve(Params p, double underlyingReturn, double dt, Random rng) {
        double leverage = 4.0;
        double alphaShock = rng.nextGaussian() * 0.22 * Math.sqrt(dt);
        double alphaLev = -leverage * underlyingReturn * Math.sqrt(dt);
        double meanRev = 0.04 * (Math.log(Math.max(1e-9, p.baseAlpha))
                - Math.log(Math.max(1e-9, p.alpha))) * dt;
        p.alpha = clamp(p.alpha * Math.exp(alphaShock + alphaLev + meanRev),
                0.25 * p.baseAlpha, 4.0 * p.baseAlpha);

        double rhoShock = rng.nextGaussian() * 0.10 * Math.sqrt(dt);
        double rhoLev = -0.55 * underlyingReturn;
        p.rho = clamp(p.rho + rhoShock + rhoLev, -0.95, 0.6);

        double nuShock = rng.nextGaussian() * 0.18 * Math.sqrt(dt);
        p.nu = clamp(p.nu * Math.exp(nuShock), 0.05, 1.5);
    }

    public static double price(double forward, double strike, double years, Params p,
                               double rate, CzceOptionMarket.Type type) {
        double t = Math.max(0.0, years);
        if (t <= 0.0) {
            return type == CzceOptionMarket.Type.CALL ? Math.max(0.0, forward - strike)
                    : Math.max(0.0, strike - forward);
        }
        double iv = impliedVol(forward, strike, t, p);
        double df = Math.exp(-rate * t);
        double call = AnalyticFormulas.blackScholesGeneralizedOptionValue(
                Math.max(1e-9, forward), iv, t, Math.max(1e-9, strike), df);
        if (type == CzceOptionMarket.Type.CALL) {
            return Math.max(0.0, call);
        }
        return Math.max(0.0, call - df * (forward - strike));
    }

    public static Greeks greeks(double forward, double strike, double years, Params p,
                                double rate, CzceOptionMarket.Type type) {
        double t = Math.max(1.0 / 365.0, years);
        double f = Math.max(1e-9, forward);
        double df = Math.max(1e-4, f * 1e-4);
        double p0 = price(f, strike, t, p, rate, type);
        double pUp = price(f + df, strike, t, p, rate, type);
        double pDn = price(f - df, strike, t, p, rate, type);
        double delta = (pUp - pDn) / (2.0 * df);
        double gamma = (pUp - 2.0 * p0 + pDn) / (df * df);
        double da = Math.max(1e-8, p.alpha * 0.01);
        Params up = p.copy();
        Params dn = p.copy();
        up.alpha += da;
        dn.alpha -= da;
        double vega = (price(f, strike, t, up, rate, type)
                - price(f, strike, t, dn, rate, type)) / (2.0 * da) * 0.01;
        double theta = (price(f, strike, Math.max(1.0 / 365.0, t - 1.0 / 365.0), p, rate, type)
                - p0) / 365.0;
        double iv = impliedVol(f, strike, t, p);
        return new Greeks(p0, iv, delta, gamma, vega, theta);
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
