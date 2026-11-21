package com.example.financialmarket.engine.tabl;

import java.util.Arrays;

/**
 * The macro anchor produced by the TABL layer: an H-step daily close path plus a volatility band.
 *
 * <p>Anchors are immutable value objects. The {@link #at(double)} interpolation is used by the
 * agents to obtain an intraday reference price from a daily path: the anchor is defined on daily
 * knots and interpolated linearly inside the day, so every tick sees a continuous reference without
 * any extra forecasting work.</p>
 */
public record AnchorPath(String assetId, long day, double[] close, double[] band, double sigma, boolean stale) {

    public AnchorPath {
        if (close.length == 0) {
            throw new IllegalArgumentException("anchor path must not be empty");
        }
        if (band.length != close.length) {
            throw new IllegalArgumentException("band length must match close length");
        }
        close = close.clone();
        band = band.clone();
    }

    public int horizon() {
        return close.length;
    }

    /** Linear interpolation of the daily anchor at {@code fraction in [0,1]}. */
    public double at(double fraction) {
        if (close.length == 1) {
            return close[0];
        }
        double x = Math.max(0.0, Math.min(1.0, fraction)) * (close.length - 1);
        int i = (int) Math.floor(x);
        int j = Math.min(close.length - 1, i + 1);
        double w = x - i;
        return close[i] * (1.0 - w) + close[j] * w;
    }

    /** Interpolated one-sigma band half-width, used for the anchor-deviation rules. */
    public double bandAt(double fraction) {
        if (band.length == 1) {
            return band[0];
        }
        double x = Math.max(0.0, Math.min(1.0, fraction)) * (band.length - 1);
        int i = (int) Math.floor(x);
        int j = Math.min(band.length - 1, i + 1);
        double w = x - i;
        return band[i] * (1.0 - w) + band[j] * w;
    }

    /** Daily returns implied by the path, relative to {@code lastClose}. */
    public double[] impliedReturns(double lastClose) {
        double[] out = new double[close.length];
        double prev = lastClose;
        for (int i = 0; i < close.length; i++) {
            out[i] = prev <= 0 ? 0.0 : close[i] / prev - 1.0;
            prev = close[i];
        }
        return out;
    }

    @Override
    public String toString() {
        return "AnchorPath{" + assetId + ", day=" + day + ", H=" + close.length
                + ", first=" + String.format(java.util.Locale.ROOT, "%.4f", close[0])
                + ", last=" + String.format(java.util.Locale.ROOT, "%.4f", close[close.length - 1])
                + ", stale=" + stale + '}';
    }

    /** Debug helper. */
    public String describe() {
        return toString() + " band=" + Arrays.toString(band);
    }
}
