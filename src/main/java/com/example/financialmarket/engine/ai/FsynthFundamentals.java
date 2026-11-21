package com.example.financialmarket.engine.ai;

import com.example.financialmarket.engine.random.SeedableRandom;

/**
 * fsynth-inspired fundamental generator.
 *
 * <p>fsynth synthesizes price paths with Heston stochastic volatility + jump diffusion. Here the
 * same ideas drive quarterly fundamental shocks: a mean-reverting variance state, a jump component,
 * and regime-scaled growth/margin/turnover. Deterministic for a given seed; no wall clock.</p>
 */
public final class FsynthFundamentals {

    public record Shock(double revenueGrowth, double netMargin, double turnover,
                        double equityMultiplier, double jump) {
    }

    private final SeedableRandom rng;
    private double variance = 0.0004;
    private double growthLevel = 0.07;

    public FsynthFundamentals(long seed) {
        this.rng = new SeedableRandom(SeedableRandom.mix64(seed));
    }

    public Shock next() {
        double dt = 0.25;
        double kappa = 2.0;
        double theta = 0.0004;
        double xi = 0.15;
        double z1 = rng.nextGaussian();
        double z2 = rng.nextGaussian();
        variance = Math.max(1e-8, variance
                + kappa * (theta - variance) * dt
                + xi * Math.sqrt(variance * dt) * z2);
        double jump = rng.nextDouble() < 0.03 ? rng.nextGaussian() * 0.04 : 0.0;
        double growth = 0.06 + 0.5 * Math.sqrt(variance) * z1 + jump;
        growth = clamp(growth, -0.10, 0.25);
        growthLevel = 0.8 * growthLevel + 0.2 * growth;
        double margin = clamp(0.12 + 0.03 * z1 + 0.5 * jump, 0.02, 0.35);
        double turnover = clamp(0.65 + 0.20 * z1, 0.15, 1.10);
        double equity = clamp(1.60 + 0.25 * z1, 1.20, 2.00);
        return new Shock(growth, margin, turnover, equity, jump);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
