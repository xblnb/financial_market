package com.example.financialmarket.engine.ml;

import java.io.IOException;
import java.util.Random;

/**
 * External-asset Kronos forecaster: loads fp16 checkpoints from a configurable directory and runs
 * the whole pre-processing / autoregressive sampling / decoding pipeline in pure Java.
 *
 * <p>This is a deployment layer, not a trading strategy. It exposes one deterministic entry point
 * ({@link #predict}) that takes a window of OHLCV bars plus their calendar stamps and returns the
 * predicted next {@code predLen} bars. Nothing in the mod calls it yet; wiring it into the market
 * engine is a separate step.</p>
 *
 * <h2>Distribution notes</h2>
 * <ul>
 *   <li>Lean release: weights are external; place them under
 *       {@code config/financialmarket/models/kronos/} or set
 *       {@code -Dfinancialmarket.models.dir=...}. Full release: MIT fp16 weights are bundled as
 *       classpath resources, with external paths taking precedence. See {@link KronosModelStore}.</li>
 *   <li>No native code, no Python, no runtime download. The checkpoints are MIT licensed
 *       (NeoQuasar/Kronos-mini, NeoQuasar/Kronos-Tokenizer-base); see third-party notices.</li>
 *   <li>Loading is lazy and thread-safe; inference is serialised because the model reuses activation
 *       scratch buffers.</li>
 * </ul>
 */
public final class KronosForecaster {

    /** Kronos was trained with a 512 bar context window. */
    public static final int MAX_CONTEXT = 512;
    /** Input clip used by the reference predictor. */
    public static final float CLIP = 5f;

    /** Model hyper-parameters of Kronos-mini (config.json of the checkpoint). */
    private static final int S1_BITS = 10;
    private static final int S2_BITS = 10;
    private static final int D_MODEL = 256;
    private static final int HEADS = 4;
    private static final int FF_DIM = 512;
    private static final int LAYERS = 4;

    /** Tokenizer hyper-parameters of Kronos-Tokenizer-base (config.json of the checkpoint). */
    private static final int TOK_D_IN = 6;
    private static final int TOK_D_MODEL = 256;
    private static final int TOK_HEADS = 4;
    private static final int TOK_FF_DIM = 512;
    private static final int TOK_ENC_LAYERS = 4;
    private static final int TOK_DEC_LAYERS = 4;

    /** Sampling parameters, mirroring {@code KronosPredictor.predict}. */
    public static final class Params {
        public float temperature = 1.0f;
        public int topK = 0;
        public float topP = 0.9f;
        public int sampleCount = 1;
        public long seed = 42L;
        public int context = MAX_CONTEXT;
        /** Incremental (KV cache) decoding; falls back to the full path when false. */
        public boolean kvCache = true;

        public Params() {
        }

        public Params(float temperature, int topK, float topP, int sampleCount, long seed, int context) {
            this.temperature = temperature;
            this.topK = topK;
            this.topP = topP;
            this.sampleCount = sampleCount;
            this.seed = seed;
            this.context = Math.max(1, Math.min(context, MAX_CONTEXT));
        }
    }

    private static volatile KronosForecaster instance;

    private final KronosTokenizer tokenizer;
    private final KronosModel model;

    private KronosForecaster(KronosTokenizer tokenizer, KronosModel model) {
        this.tokenizer = tokenizer;
        this.model = model;
    }

    /** Lazily loads the external checkpoints (about 16 MB of heap once loaded). */
    public static KronosForecaster getInstance() throws IOException {
        KronosForecaster local = instance;
        if (local == null) {
            synchronized (KronosForecaster.class) {
                local = instance;
                if (local == null) {
                    if (!KronosModelStore.available()) {
                        throw new IOException(KronosModelStore.describe());
                    }
                    Safetensors tok;
                    Safetensors mdl;
                    if (KronosModelStore.directory() != null) {
                        tok = Safetensors.load(KronosModelStore.tokenizerFile());
                        mdl = Safetensors.load(KronosModelStore.modelFile());
                    } else {
                        // Full-release jar: weights are classpath resources (MIT, with NOTICE).
                        tok = Safetensors.loadResource(KronosModelStore.BUNDLED_TOKENIZER_RESOURCE);
                        mdl = Safetensors.loadResource(KronosModelStore.BUNDLED_MODEL_RESOURCE);
                    }
                    local = new KronosForecaster(
                            new KronosTokenizer(tok, TOK_D_IN, TOK_D_MODEL, TOK_HEADS, TOK_FF_DIM,
                                    TOK_ENC_LAYERS, TOK_DEC_LAYERS, S1_BITS, S2_BITS),
                            new KronosModel(mdl, S1_BITS, S2_BITS, LAYERS, D_MODEL, HEADS, FF_DIM));
                    instance = local;
                }
            }
        }
        return local;
    }

    /** True when external or bundled Kronos checkpoints are present. */
    public static boolean isAvailable() {
        return KronosModelStore.available();
    }

    /**
     * Predicts the next {@code predLen} bars.
     *
     * @param ohlcv    {@code [n][6]} normalised-scale raw values: open, high, low, close, volume, amount
     * @param xStamps  {@code [n][5]} calendar stamps of the history: minute, hour, weekday, day, month
     * @param yStamps  {@code [predLen][5]} calendar stamps of the future bars
     * @param predLen  number of bars to generate
     * @param params   sampler configuration
     * @return {@code [predLen][6]} predicted raw values in the same units as {@code ohlcv}
     */
    public synchronized float[][] predict(float[][] ohlcv, int[][] xStamps, int[][] yStamps,
                                          int predLen, Params params) {
        if (ohlcv == null || ohlcv.length == 0) {
            throw new IllegalArgumentException("ohlcv history must not be empty");
        }
        if (predLen <= 0) {
            throw new IllegalArgumentException("predLen must be positive");
        }
        if (xStamps.length != ohlcv.length) {
            throw new IllegalArgumentException("history stamps must match ohlcv length");
        }
        if (yStamps.length != predLen) {
            throw new IllegalArgumentException("future stamps must match predLen");
        }
        Params p = params == null ? new Params() : params;
        int context = Math.max(predLen, Math.max(1, Math.min(p.context, MAX_CONTEXT)));
        int samples = Math.max(1, Math.min(p.sampleCount, 32));

        int n = ohlcv.length;
        float[] mean = new float[TOK_D_IN];
        float[] std = new float[TOK_D_IN];
        for (int c = 0; c < TOK_D_IN; c++) {
            double sum = 0.0;
            for (int r = 0; r < n; r++) {
                sum += ohlcv[r][c];
            }
            double mu = sum / n;
            double var = 0.0;
            for (int r = 0; r < n; r++) {
                double d = ohlcv[r][c] - mu;
                var += d * d;
            }
            mean[c] = (float) mu;
            std[c] = (float) Math.sqrt(var / n);
        }

        int keep = Math.min(n, context);
        int offset = n - keep;
        float[] norm = new float[keep * TOK_D_IN];
        int[][] xStamp = new int[keep][];
        for (int r = 0; r < keep; r++) {
            for (int c = 0; c < TOK_D_IN; c++) {
                float v = (ohlcv[offset + r][c] - mean[c]) / (std[c] + 1e-5f);
                norm[r * TOK_D_IN + c] = Math.max(-CLIP, Math.min(CLIP, v));
            }
            xStamp[r] = xStamps[offset + r];
        }

        int[][] encoded = tokenizer.encode(norm, keep);
        int[] pre = new int[keep + predLen];
        int[] post = new int[keep + predLen];
        System.arraycopy(encoded[0], 0, pre, 0, keep);
        System.arraycopy(encoded[1], 0, post, 0, keep);

        int[][] fullStamp = new int[keep + predLen][];
        System.arraycopy(xStamp, 0, fullStamp, 0, keep);
        System.arraycopy(yStamps, 0, fullStamp, keep, predLen);

        long seed = p.seed;
        for (int s = 0; s < samples; s++) {
            Random rng = new Random(seed + 0x9E3779B97F4A7C15L * s);
            generate(pre, post, fullStamp, keep, predLen, context, p, rng);
        }

        int total = keep + predLen;
        int window = Math.min(context, total);
        int from = total - window;
        int[] winPre = new int[window];
        int[] winPost = new int[window];
        System.arraycopy(pre, from, winPre, 0, window);
        System.arraycopy(post, from, winPost, 0, window);

        // Per-sample decoding must happen before averaging, matching the reference implementation.
        float[][] decoded = new float[window * TOK_D_IN][];
        decoded[0] = tokenizer.decode(winPre, winPost, window);
        float[] acc = decoded[0].clone();
        for (int s = 1; s < samples; s++) {
            // Re-run the generator for each sample so that buffers do not need to be kept per sample.
            int[] p2 = new int[keep + predLen];
            int[] q2 = new int[keep + predLen];
            System.arraycopy(encoded[0], 0, p2, 0, keep);
            System.arraycopy(encoded[1], 0, q2, 0, keep);
            generate(p2, q2, fullStamp, keep, predLen, context, p, new Random(seed + 0x9E3779B97F4A7C15L * s));
            int[] w2p = new int[window];
            int[] w2q = new int[window];
            System.arraycopy(p2, from, w2p, 0, window);
            System.arraycopy(q2, from, w2q, 0, window);
            float[] dec = tokenizer.decode(w2p, w2q, window);
            for (int i = 0; i < acc.length; i++) {
                acc[i] += dec[i];
            }
        }
        if (samples > 1) {
            float inv = 1f / samples;
            for (int i = 0; i < acc.length; i++) {
                acc[i] *= inv;
            }
        }

        float[][] out = new float[predLen][TOK_D_IN];
        for (int i = 0; i < predLen; i++) {
            int row = (window - predLen + i) * TOK_D_IN;
            for (int c = 0; c < TOK_D_IN; c++) {
                out[i][c] = acc[row + c] * (std[c] + 1e-5f) + mean[c];
            }
        }
        return out;
    }

    /**
     * Autoregressive generation in place: fills {@code pre[keep .. keep+predLen)} and
     * {@code post[keep .. keep+predLen)}.
     */
    private void generate(int[] pre, int[] post, int[][] fullStamp, int keep, int predLen,
                          int context, Params p, Random rng) {
        if (p.kvCache) {
            generateKv(pre, post, fullStamp, keep, predLen, p, rng);
            return;
        }
        int[] bufferPre = new int[context];
        int[] bufferPost = new int[context];
        int buffered = Math.min(keep, context);
        int start = Math.max(0, keep - context);
        System.arraycopy(pre, start, bufferPre, 0, buffered);
        System.arraycopy(post, start, bufferPost, 0, buffered);

        for (int i = 0; i < predLen; i++) {
            int currentSeqLen = keep + i;
            int windowLen = Math.min(currentSeqLen, context);

            int[] inPre;
            int[] inPost;
            if (currentSeqLen <= context) {
                inPre = java.util.Arrays.copyOf(bufferPre, windowLen);
                inPost = java.util.Arrays.copyOf(bufferPost, windowLen);
            } else {
                inPre = bufferPre;
                inPost = bufferPost;
            }

            int contextStart = Math.max(0, currentSeqLen - context);
            int[][] stamp = new int[windowLen][];
            System.arraycopy(fullStamp, contextStart, stamp, 0, windowLen);

            KronosModel.S1Output s1 = model.decodeS1(inPre, inPost, stamp, windowLen);
            float[] lastS1 = new float[model.vocabS1()];
            System.arraycopy(s1.logits, (windowLen - 1) * model.vocabS1(), lastS1, 0, lastS1.length);
            int s1Token = sample(lastS1, p.temperature, p.topK, p.topP, rng);

            float[] s2Logits = model.decodeS2(s1.context, windowLen, s1Token);
            int s2Token = sample(s2Logits, p.temperature, p.topK, p.topP, rng);

            pre[keep + i] = s1Token;
            post[keep + i] = s2Token;

            if (currentSeqLen < context) {
                bufferPre[currentSeqLen] = s1Token;
                bufferPost[currentSeqLen] = s2Token;
            } else {
                System.arraycopy(bufferPre, 1, bufferPre, 0, context - 1);
                System.arraycopy(bufferPost, 1, bufferPost, 0, context - 1);
                bufferPre[context - 1] = s1Token;
                bufferPost[context - 1] = s2Token;
            }
        }
    }

    /**
     * Sampling identical in semantics to {@code sample_from_logits}: temperature, then top-k
     * (which, as in the reference, takes precedence over top-p), then multinomial draw.
     */
    static int sample(float[] logits, float temperature, int topK, float topP, Random rng) {
        int n = logits.length;
        float temp = temperature <= 0f ? 1f : temperature;
        float[] p = new float[n];
        for (int i = 0; i < n; i++) {
            p[i] = logits[i] / temp;
        }
        if (topK > 0) {
            int k = Math.max(1, Math.min(topK, n));
            float threshold = kthLargest(p, k);
            for (int i = 0; i < n; i++) {
                if (p[i] < threshold) {
                    p[i] = Float.NEGATIVE_INFINITY;
                }
            }
        } else if (topP < 1f) {
            p = nucleus(p, topP);
        }
        MlMath.softmaxInPlace(p, 0, n);
        double r = rng.nextDouble();
        double accum = 0.0;
        for (int i = 0; i < n; i++) {
            accum += p[i];
            if (r < accum) {
                return i;
            }
        }
        return n - 1;
    }

    private static float kthLargest(float[] values, int k) {
        float[] copy = values.clone();
        java.util.Arrays.sort(copy);
        return copy[copy.length - k];
    }

    private static float[] nucleus(float[] logits, float topP) {
        int n = logits.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(logits[b], logits[a]));
        double[] probs = new double[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            probs[i] = Math.exp(logits[order[i]] - logits[order[0]]);
            sum += probs[i];
        }
        int cut = n - 1;
        double cumulative = 0.0;
        for (int i = 0; i < n; i++) {
            cumulative += probs[i] / sum;
            if (cumulative > topP) {
                cut = i;
                break;
            }
        }
        float[] out = new float[n];
        java.util.Arrays.fill(out, Float.NEGATIVE_INFINITY);
        for (int i = 0; i <= cut; i++) {
            out[order[i]] = logits[order[i]];
        }
        return out;
    }

    /** KV-cache generation: prefill the visible history once, then decode one token per step. */
    private void generateKv(int[] pre, int[] post, int[][] fullStamp, int keep, int predLen,
                            Params p, Random rng) {
        int[] hp = java.util.Arrays.copyOf(pre, keep);
        int[] hq = java.util.Arrays.copyOf(post, keep);
        int[][] hs = new int[keep][];
        System.arraycopy(fullStamp, 0, hs, 0, keep);
        model.beginIncremental(hp, hq, hs, keep);
        float[] logits = model.logitsFromLastContext();
        for (int i = 0; i < predLen; i++) {
            int s1 = sample(logits, p.temperature, p.topK, p.topP, rng);
            float[] s2Logits = model.decodeS2(model.contextMatrix(), model.contextRows(), s1);
            int s2 = sample(s2Logits, p.temperature, p.topK, p.topP, rng);
            pre[keep + i] = s1;
            post[keep + i] = s2;
            if (i < predLen - 1) {
                logits = model.stepIncremental(s1, s2, fullStamp[keep + i]);
            }
        }
    }
}