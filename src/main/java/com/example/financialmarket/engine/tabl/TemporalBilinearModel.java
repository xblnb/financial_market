package com.example.financialmarket.engine.tabl;

import java.util.Random;

/**
 * TABL: <b>T</b>emporal <b>A</b>ttention <b>B</b>i<b>l</b>inear layer for multivariate forecasting.
 *
 * <p>Two parallel branches over a window of {@code L} daily observations with {@code V} variates:</p>
 * <ol>
 *   <li><b>Temporal attention</b> — causal dot-product attention over the days of the window,
 *       {@code heads} heads with different (fixed, seed-derived) projections. The attention weights
 *       are a deterministic function of the data, so no training pipeline is required.</li>
 *   <li><b>Bilinear interaction</b> — a low-rank bilinear form {@code u^T B_v v} per variate,
 *       capturing cross-variate structure (asset × macro factor).</li>
 * </ol>
 *
 * <p>Only the read-out is fitted, by ridge regression in closed form ({@link #fit}). The output is
 * an {@code H x V} matrix of future daily returns, which the anchor service turns into an
 * {@link AnchorPath}. Everything is plain Java and deterministic for a given seed.</p>
 */
public final class TemporalBilinearModel {

    private final int lookback;
    private final int variates;
    private final int horizon;
    private final int heads;
    private final int headDim;
    private final int rank;
    private final double ridgeLambda;

    private final double[][][] qProj;   // [head][headDim][V]
    private final double[][][] kProj;
    private final double[][][] vProj;
    private final double[][][] bU;      // [variate][rank][V]
    private final double[][][] bV;
    private final double[][][] bW;      // [variate][rank][rank]

    private double[][] readout;         // [(H*V)][features]
    private int featureDim;
    private boolean fitted;

    public TemporalBilinearModel(long seed, int lookback, int variates, int horizon, double ridgeLambda) {
        this(seed, lookback, variates, horizon, 2, 4, 4, ridgeLambda);
    }

    public TemporalBilinearModel(long seed, int lookback, int variates, int horizon,
                                 int heads, int headDim, int rank, double ridgeLambda) {
        this.lookback = Math.max(4, lookback);
        this.variates = Math.max(1, variates);
        this.horizon = Math.max(1, horizon);
        this.heads = Math.max(1, heads);
        this.headDim = Math.max(1, headDim);
        this.rank = Math.max(1, rank);
        this.ridgeLambda = ridgeLambda;

        Random rng = new Random(seed);
        this.qProj = randomProjections(rng);
        this.kProj = randomProjections(rng);
        this.vProj = randomProjections(rng);
        this.bU = randomBilinear(rng);
        this.bV = randomBilinear(rng);
        this.bW = new double[variates][rank][rank];
        for (int v = 0; v < variates; v++) {
            for (int i = 0; i < rank; i++) {
                for (int j = 0; j < rank; j++) {
                    bW[v][i][j] = (rng.nextDouble() * 2 - 1) / Math.sqrt(rank);
                }
            }
        }
        this.featureDim = heads * headDim + variates * 3;
        this.readout = new double[horizon * variates][featureDim];
    }

    private double[][][] randomProjections(Random rng) {
        double[][][] out = new double[heads][headDim][variates];
        for (int h = 0; h < heads; h++) {
            for (int d = 0; d < headDim; d++) {
                for (int v = 0; v < variates; v++) {
                    out[h][d][v] = rng.nextGaussian() / Math.sqrt(variates);
                }
            }
        }
        return out;
    }

    private double[][][] randomBilinear(Random rng) {
        double[][][] out = new double[variates][rank][variates];
        for (int v = 0; v < variates; v++) {
            for (int r = 0; r < rank; r++) {
                for (int j = 0; j < variates; j++) {
                    out[v][r][j] = rng.nextGaussian() / Math.sqrt(variates);
                }
            }
        }
        return out;
    }

    public int horizon() {
        return horizon;
    }

    public int variates() {
        return variates;
    }

    public int featureDim() {
        return featureDim;
    }

    /** True once {@link #fit} has been called with at least one training sample. */
    public boolean isFitted() {
        return fitted;
    }

    /**
     * Fits the read-out using every sample whose targets stay within {@code series.length - 1}.
     */
    public int fit(double[][] series) {
        return fit(series, series.length - 1);
    }

    /**
     * Fits the read-out by ridge regression on {@code series} (row-major {@code [D][V]}, levels not
     * returns). Samples ending at {@code t} use only rows {@code <= t}; targets use rows
     * {@code t+1 .. t+H}, and {@code t+H <= cutoff} is enforced. A fit with {@code cutoff = D - 1}
     * therefore never looks past the last known day.
     *
     * @return number of training samples used
     */
    public int fit(double[][] series, int cutoff) {
        int rows = Math.min(series.length, cutoff + 1);
        int lastTrainable = rows - horizon - 1;
        if (lastTrainable < lookback) {
            fitted = false;
            return 0;
        }
        int samples = lastTrainable - lookback + 1;
        double[][] features = new double[samples][];
        double[][] targets = new double[samples][];
        for (int s = 0; s < samples; s++) {
            int t = lookback + s - 1;
            features[s] = features(series, t);
            targets[s] = returns(series, t, horizon);
        }
        featureDim = features[0].length;
        double[][] xtx = new double[featureDim][featureDim];
        double[][] xty = new double[featureDim][horizon * variates];
        for (int s = 0; s < samples; s++) {
            double[] f = features[s];
            for (int i = 0; i < featureDim; i++) {
                for (int j = 0; j < featureDim; j++) {
                    xtx[i][j] += f[i] * f[j];
                }
                for (int k = 0; k < horizon * variates; k++) {
                    xty[i][k] += f[i] * targets[s][k];
                }
            }
        }
        for (int i = 0; i < featureDim; i++) {
            xtx[i][i] += ridgeLambda;
        }
        double[][] inv = invert(xtx);
        readout = new double[horizon * variates][featureDim];
        if (inv != null) {
            for (int k = 0; k < horizon * variates; k++) {
                for (int i = 0; i < featureDim; i++) {
                    double acc = 0;
                    for (int j = 0; j < featureDim; j++) {
                        acc += inv[i][j] * xty[j][k];
                    }
                    readout[k][i] = acc;
                }
            }
        }
        fitted = samples > 0;
        return samples;
    }

    /**
     * Forecasts {@code H} future daily returns per variate from the window ending at
     * {@code lastIndex} (inclusive).
     *
     * @return {@code [H][V]} returns, or zeros when the model is not fitted
     */
    public double[][] forecast(double[][] series, int lastIndex) {
        double[][] out = new double[horizon][variates];
        if (!isFitted() || lastIndex < lookback - 1) {
            return out;
        }
        double[] f = features(series, lastIndex);
        for (int h = 0; h < horizon; h++) {
            for (int v = 0; v < variates; v++) {
                double acc = 0;
                double[] w = readout[h * variates + v];
                for (int i = 0; i < f.length; i++) {
                    acc += w[i] * f[i];
                }
                out[h][v] = acc;
            }
        }
        return out;
    }

    /** Feature vector for the window ending at {@code t}: attention context + bilinear + stats. */
    public double[] features(double[][] series, int t) {
        int start = Math.max(0, t - lookback + 1);
        int len = t - start + 1;
        double[][] window = new double[len][variates];
        for (int i = 0; i < len; i++) {
            for (int v = 0; v < variates; v++) {
                window[i][v] = safe(series[start + i][v]);
            }
        }
        double[] context = attention(window);
        double[] bilinear = bilinear(window[len - 1]);
        double[] mean = new double[variates];
        double[] std = new double[variates];
        for (int v = 0; v < variates; v++) {
            double m = 0;
            for (double[] row : window) {
                m += row[v];
            }
            m /= len;
            double s = 0;
            for (double[] row : window) {
                double d = row[v] - m;
                s += d * d;
            }
            mean[v] = m;
            std[v] = Math.sqrt(s / len);
        }
        double[] out = new double[featureDim];
        int pos = 0;
        System.arraycopy(context, 0, out, pos, context.length);
        pos += context.length;
        System.arraycopy(bilinear, 0, out, pos, variates);
        pos += variates;
        System.arraycopy(mean, 0, out, pos, variates);
        pos += variates;
        System.arraycopy(std, 0, out, pos, variates);
        return out;
    }

    private double[] attention(double[][] window) {
        int len = window.length;
        double[] out = new double[heads * headDim];
        for (int h = 0; h < heads; h++) {
            double[] q = project(qProj[h], window[len - 1]);
            double[] scores = new double[len];
            double max = Double.NEGATIVE_INFINITY;
            for (int s = 0; s < len; s++) {            // causal: only s <= last
                scores[s] = dot(q, project(kProj[h], window[s])) / Math.sqrt(headDim);
                max = Math.max(max, scores[s]);
            }
            double sum = 0;
            for (int s = 0; s < len; s++) {
                scores[s] = Math.exp(scores[s] - max);
                sum += scores[s];
            }
            double[] acc = new double[headDim];
            for (int s = 0; s < len; s++) {
                double a = scores[s] / sum;
                if (a == 0) {
                    continue;
                }
                double[] v = project(vProj[h], window[s]);
                for (int d = 0; d < headDim; d++) {
                    acc[d] += a * v[d];
                }
            }
            System.arraycopy(acc, 0, out, h * headDim, headDim);
        }
        return out;
    }

    private double[] bilinear(double[] x) {
        double[] out = new double[variates];
        double[] u = new double[rank];
        double[] v = new double[rank];
        for (int r = 0; r < rank; r++) {
            double au = 0;
            double av = 0;
            for (int j = 0; j < variates; j++) {
                au += bU[0][r][j] * x[j];
                av += bV[0][r][j] * x[j];
            }
            u[r] = Math.tanh(au);
            v[r] = Math.tanh(av);
        }
        for (int k = 0; k < variates; k++) {
            double acc = 0;
            for (int i = 0; i < rank; i++) {
                for (int j = 0; j < rank; j++) {
                    acc += u[i] * bW[k][i][j] * v[j];
                }
            }
            out[k] = acc;
        }
        return out;
    }

    private double[] project(double[][] p, double[] x) {
        double[] out = new double[p.length];
        for (int d = 0; d < p.length; d++) {
            double acc = 0;
            for (int v = 0; v < variates; v++) {
                acc += p[d][v] * x[v];
            }
            out[d] = acc;
        }
        return out;
    }

    /** Flattened future returns for training sample ending at {@code t}. */
    private double[] returns(double[][] series, int t, int h) {
        double[] out = new double[h * variates];
        for (int step = 0; step < h; step++) {
            int idx = t + step + 1;
            for (int v = 0; v < variates; v++) {
                double prev = safe(series[idx - 1][v]);
                double now = safe(series[idx][v]);
                out[step * variates + v] = prev <= 0 ? 0 : now / prev - 1;
            }
        }
        return out;
    }

    private static double safe(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }

    private static double dot(double[] a, double[] b) {
        double acc = 0;
        for (int i = 0; i < a.length; i++) {
            acc += a[i] * b[i];
        }
        return acc;
    }

    /** Gauss-Jordan inversion with partial pivoting; returns null for a singular matrix. */
    public static double[][] invert(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(m[pivot][col]) < 1e-12) {
                return null;
            }
            double[] tmp = m[col];
            m[col] = m[pivot];
            m[pivot] = tmp;
            double div = m[col][col];
            for (int j = 0; j < 2 * n; j++) {
                m[col][j] /= div;
            }
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double factor = m[r][col];
                if (factor == 0) {
                    continue;
                }
                for (int j = 0; j < 2 * n; j++) {
                    m[r][j] -= factor * m[col][j];
                }
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], n, inv[i], 0, n);
        }
        return inv;
    }
}
