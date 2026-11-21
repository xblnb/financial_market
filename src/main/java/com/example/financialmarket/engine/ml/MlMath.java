package com.example.financialmarket.engine.ml;

/**
 * Small, allocation-light float kernels used by the in-jar forecasters.
 *
 * <p>Everything here is plain Java: no BLAS, no native code, no external library. The models that
 * ship with the mod are deliberately tiny (4-5 M parameters) so that a straightforward blocked
 * implementation is fast enough for an off-thread prediction call inside a game tick budget.</p>
 *
 * <p>Layout convention: tensors are row-major {@code float[]} arrays, a batch of tokens is
 * {@code [rows][dim]}. Linear weights follow PyTorch {@code nn.Linear} conventions, i.e. shape
 * {@code [out][in]} and {@code y = x * W^T + b}.</p>
 */
public final class MlMath {

    private MlMath() {
    }

    /** {@code dst[r][o] = b[o] + sum_i x[r][i] * w[o][i]}. */
    public static void linear(float[] x, int rows, int inDim, float[] w, float[] b, int outDim, float[] dst) {
        for (int r = 0; r < rows; r++) {
            int xOff = r * inDim;
            int dOff = r * outDim;
            for (int o = 0; o < outDim; o++) {
                int wOff = o * inDim;
                float sum = b == null ? 0f : b[o];
                for (int i = 0; i < inDim; i++) {
                    sum += x[xOff + i] * w[wOff + i];
                }
                dst[dOff + o] = sum;
            }
        }
    }

    /** SiLU / swish, in place. */
    public static void siluInPlace(float[] x) {
        for (int i = 0; i < x.length; i++) {
            float v = x[i];
            x[i] = v / (1f + (float) Math.exp(-v));
        }
    }

    /** {@code dst = a * b} element-wise. */
    public static void mul(float[] a, float[] b, int n, float[] dst) {
        for (int i = 0; i < n; i++) {
            dst[i] = a[i] * b[i];
        }
    }

    /** {@code dst = a + b} element-wise. */
    public static void add(float[] a, float[] b, int n, float[] dst) {
        for (int i = 0; i < n; i++) {
            dst[i] = a[i] + b[i];
        }
    }

    /** {@code dst += src} element-wise. */
    public static void addInPlace(float[] dst, float[] src) {
        for (int i = 0; i < dst.length; i++) {
            dst[i] += src[i];
        }
    }

    /**
     * RMS normalisation (Llama style), matching {@code Kronos RMSNorm}:
     * {@code y = x * rsqrt(mean(x^2) + eps) * weight}, epsilon 1e-5.
     */
    public static void rmsNorm(float[] x, int rows, int dim, float[] weight, float eps) {
        for (int r = 0; r < rows; r++) {
            int off = r * dim;
            double acc = 0.0;
            for (int i = 0; i < dim; i++) {
                float v = x[off + i];
                acc += (double) v * v;
            }
            float scale = (float) (1.0 / Math.sqrt(acc / dim + eps));
            for (int i = 0; i < dim; i++) {
                x[off + i] = x[off + i] * scale * weight[i];
            }
        }
    }

    /** In-place softmax over {@code x[off .. off+n)}. */
    public static void softmaxInPlace(float[] x, int off, int n) {
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, x[off + i]);
        }
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double e = Math.exp(x[off + i] - max);
            x[off + i] = (float) e;
            sum += e;
        }
        float inv = (float) (1.0 / sum);
        for (int i = 0; i < n; i++) {
            x[off + i] *= inv;
        }
    }

    /**
     * Rotary position embedding applied in place to a {@code [rows][heads*headDim]} tensor.
     * Matches {@code RotaryPositionalEmbedding}: freqs = pos * invFreq, emb = cat(freqs, freqs),
     * rotation of (x1, x2) halves exactly like {@code _rotate_half}.
     */
    public static void ropeInPlace(float[] x, int rows, int heads, int headDim, float[] invFreq) {
        int half = headDim / 2;
        int dim = heads * headDim;
        float[] cos = new float[half];
        float[] sin = new float[half];
        for (int t = 0; t < rows; t++) {
            for (int j = 0; j < half; j++) {
                float angle = t * invFreq[j];
                cos[j] = (float) Math.cos(angle);
                sin[j] = (float) Math.sin(angle);
            }
            int rowOff = t * dim;
            for (int h = 0; h < heads; h++) {
                int base = rowOff + h * headDim;
                for (int j = 0; j < half; j++) {
                    float x1 = x[base + j];
                    float x2 = x[base + half + j];
                    x[base + j] = x1 * cos[j] - x2 * sin[j];
                    x[base + half + j] = x2 * cos[j] + x1 * sin[j];
                }
            }
        }
    }

    /**
     * Causal multi-head self attention, matching PyTorch
     * {@code F.scaled_dot_product_attention(..., is_causal=True)} with scale {@code 1/sqrt(headDim)}.
     *
     * @param q query, {@code [rows][heads*headDim]}
     * @param k key, same layout
     * @param v value, same layout
     * @param dst output, same layout
     */
    public static void causalAttention(float[] q, float[] k, float[] v, int rows, int heads, int headDim, float[] dst) {
        int dim = heads * headDim;
        float scale = (float) (1.0 / Math.sqrt(headDim));
        float[] scores = new float[rows];
        for (int h = 0; h < heads; h++) {
            int hOff = h * headDim;
            for (int t = 0; t < rows; t++) {
                int qRow = t * dim + hOff;
                float max = Float.NEGATIVE_INFINITY;
                for (int s = 0; s <= t; s++) {
                    int kRow = s * dim + hOff;
                    float dot = 0f;
                    for (int i = 0; i < headDim; i++) {
                        dot += q[qRow + i] * k[kRow + i];
                    }
                    dot *= scale;
                    scores[s] = dot;
                    max = Math.max(max, dot);
                }
                double sum = 0.0;
                for (int s = 0; s <= t; s++) {
                    double e = Math.exp(scores[s] - max);
                    scores[s] = (float) e;
                    sum += e;
                }
                float inv = (float) (1.0 / sum);
                int outRow = t * dim + hOff;
                for (int i = 0; i < headDim; i++) {
                    dst[outRow + i] = 0f;
                }
                for (int s = 0; s <= t; s++) {
                    float p = scores[s] * inv;
                    if (p == 0f) {
                        continue;
                    }
                    int vRow = s * dim + hOff;
                    for (int i = 0; i < headDim; i++) {
                        dst[outRow + i] += p * v[vRow + i];
                    }
                }
            }
        }
    }

    /**
     * Full (non-causal) multi-head attention where the queries attend to every key/value position.
     * Used by Kronos' {@code DependencyAwareLayer} cross attention in inference mode
     * ({@code is_causal = self.training} is false outside training).
     */
    public static void crossAttention(float[] q, int qRows, float[] k, float[] v, int kvRows,
                                      int heads, int headDim, float[] dst) {
        int dim = heads * headDim;
        float scale = (float) (1.0 / Math.sqrt(headDim));
        float[] scores = new float[kvRows];
        for (int h = 0; h < heads; h++) {
            int hOff = h * headDim;
            for (int t = 0; t < qRows; t++) {
                int qRow = t * dim + hOff;
                float max = Float.NEGATIVE_INFINITY;
                for (int s = 0; s < kvRows; s++) {
                    int kRow = s * dim + hOff;
                    float dot = 0f;
                    for (int i = 0; i < headDim; i++) {
                        dot += q[qRow + i] * k[kRow + i];
                    }
                    dot *= scale;
                    scores[s] = dot;
                    max = Math.max(max, dot);
                }
                double sum = 0.0;
                for (int s = 0; s < kvRows; s++) {
                    double e = Math.exp(scores[s] - max);
                    scores[s] = (float) e;
                    sum += e;
                }
                float inv = (float) (1.0 / sum);
                int outRow = t * dim + hOff;
                for (int i = 0; i < headDim; i++) {
                    dst[outRow + i] = 0f;
                }
                for (int s = 0; s < kvRows; s++) {
                    float p = scores[s] * inv;
                    if (p == 0f) {
                        continue;
                    }
                    int vRow = s * dim + hOff;
                    for (int i = 0; i < headDim; i++) {
                        dst[outRow + i] += p * v[vRow + i];
                    }
                }
            }
        }
    }

    /** Numerically stable softmax of a whole vector, returning a fresh array. */
    public static float[] softmax(float[] logits) {
        float[] out = logits.clone();
        softmaxInPlace(out, 0, out.length);
        return out;
    }

    public static int argmax(float[] values, int off, int n) {
        int best = 0;
        float bestVal = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (values[off + i] > bestVal) {
                bestVal = values[off + i];
                best = i;
            }
        }
        return best;
    }

    /** Rotary embedding for a single row starting at absolute position {@code startPos}. */
    public static void ropeInPlaceAt(float[] x, int rows, int heads, int headDim, float[] invFreq, int startPos) {
        int half = headDim / 2;
        int dim = heads * headDim;
        for (int t = 0; t < rows; t++) {
            int p = startPos + t;
            int rowOff = t * dim;
            for (int h = 0; h < heads; h++) {
                int base = rowOff + h * headDim;
                for (int j = 0; j < half; j++) {
                    float angle = p * invFreq[j];
                    float c = (float) Math.cos(angle);
                    float s = (float) Math.sin(angle);
                    float x1 = x[base + j];
                    float x2 = x[base + half + j];
                    x[base + j] = x1 * c - x2 * s;
                    x[base + half + j] = x2 * c + x1 * s;
                }
            }
        }
    }

    /** Attention of one query row against {@code total} cached keys/values (no causal mask needed). */
    public static void attendOne(float[] q, float[] k, float[] v, int total, int heads, int headDim, float[] dst) {
        int dim = heads * headDim;
        float scale = (float) (1.0 / Math.sqrt(headDim));
        float[] scores = new float[total];
        for (int h = 0; h < heads; h++) {
            int hOff = h * headDim;
            float max = Float.NEGATIVE_INFINITY;
            for (int s = 0; s < total; s++) {
                int kRow = s * dim + hOff;
                float dot = 0f;
                for (int i = 0; i < headDim; i++) {
                    dot += q[hOff + i] * k[kRow + i];
                }
                dot *= scale;
                scores[s] = dot;
                max = Math.max(max, dot);
            }
            double sum = 0.0;
            for (int s = 0; s < total; s++) {
                double e = Math.exp(scores[s] - max);
                scores[s] = (float) e;
                sum += e;
            }
            float inv = (float) (1.0 / sum);
            for (int i = 0; i < headDim; i++) {
                dst[hOff + i] = 0f;
            }
            for (int s = 0; s < total; s++) {
                float p = scores[s] * inv;
                int vRow = s * dim + hOff;
                for (int i = 0; i < headDim; i++) {
                    dst[hOff + i] += p * v[vRow + i];
                }
            }
        }
    }
}