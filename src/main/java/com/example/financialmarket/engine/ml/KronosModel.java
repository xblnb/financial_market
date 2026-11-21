package com.example.financialmarket.engine.ml;

/**
 * Pure-Java port of the Kronos K-line foundation model decoder ({@code NeoQuasar/Kronos-mini}).
 *
 * <p>Architecture (mirrors {@code model.kronos.Kronos}):</p>
 * <ul>
 *   <li>hierarchical token embedding: {@code emb_s1[s1]}, {@code emb_s2[s2]} scaled by
 *       {@code sqrt(d_model)} and fused through a linear projection;</li>
 *   <li>learned temporal embeddings (minute / hour / weekday / day / month) added to every token;</li>
 *   <li>{@code n_layers} pre-norm transformer blocks with RoPE and SwiGLU;</li>
 *   <li>final RMS norm and the {@code s1} head;</li>
 *   <li>a dependency-aware cross-attention layer that conditions the {@code s2} head on the sampled
 *       {@code s1} sibling token.</li>
 * </ul>
 *
 * <p>All tensors stay in memory as {@code float[]}; a forward pass allocates only activation
 * buffers, which keeps the class thread-safe as long as each caller owns its own buffers.</p>
 */
public final class KronosModel {

    /** Result of the first decoding stage: s1 logits plus the transformer context. */
    public static final class S1Output {
        public final float[] logits;   // [rows][vocabS1]
        public final float[] context;  // [rows][dModel]
        public final int rows;

        S1Output(float[] logits, float[] context, int rows) {
            this.logits = logits;
            this.context = context;
            this.rows = rows;
        }
    }

    private static final int[] TIME_SIZES = {60, 24, 7, 32, 13};

    private final int dModel;
    private final int heads;
    private final int headDim;
    private final int vocabS1;
    private final int vocabS2;
    private final int layers;

    private final float[] embS1;
    private final float[] embS2;
    private final float[] fusionW;
    private final float[] fusionB;
    private final float[][] timeTables = new float[5][];

    private final KronosTransformerBlock[] blocks;
    private final float[] normW;

    private final float[] dqW;
    private final float[] dqB;
    private final float[] dkW;
    private final float[] dkB;
    private final float[] dvW;
    private final float[] dvB;
    private final float[] doW;
    private final float[] doB;
    private final float[] dInvFreq;
    private final float[] dNormW;

    private final float[] s1W;
    private final float[] s1B;
    private final float[] s2W;
    private final float[] s2B;

    public KronosModel(Safetensors st, int s1Bits, int s2Bits, int layers, int dModel, int heads, int ffDim) {
        this.dModel = dModel;
        this.heads = heads;
        this.headDim = dModel / heads;
        this.vocabS1 = 1 << s1Bits;
        this.vocabS2 = 1 << s2Bits;
        this.layers = layers;

        this.embS1 = st.tensor("embedding.emb_s1.weight");
        this.embS2 = st.tensor("embedding.emb_s2.weight");
        this.fusionW = st.tensor("embedding.fusion_proj.weight");
        this.fusionB = st.tensor("embedding.fusion_proj.bias");

        timeTables[0] = st.tensor("time_emb.minute_embed.weight");
        timeTables[1] = st.tensor("time_emb.hour_embed.weight");
        timeTables[2] = st.tensor("time_emb.weekday_embed.weight");
        timeTables[3] = st.tensor("time_emb.day_embed.weight");
        timeTables[4] = st.tensor("time_emb.month_embed.weight");

        this.blocks = new KronosTransformerBlock[layers];
        for (int i = 0; i < layers; i++) {
            blocks[i] = new KronosTransformerBlock(st, "transformer." + i, dModel, heads, ffDim);
        }
        this.normW = st.tensor("norm.weight");

        this.dqW = st.tensor("dep_layer.cross_attn.q_proj.weight");
        this.dqB = st.tensor("dep_layer.cross_attn.q_proj.bias");
        this.dkW = st.tensor("dep_layer.cross_attn.k_proj.weight");
        this.dkB = st.tensor("dep_layer.cross_attn.k_proj.bias");
        this.dvW = st.tensor("dep_layer.cross_attn.v_proj.weight");
        this.dvB = st.tensor("dep_layer.cross_attn.v_proj.bias");
        this.doW = st.tensor("dep_layer.cross_attn.out_proj.weight");
        this.doB = st.tensor("dep_layer.cross_attn.out_proj.bias");
        this.dInvFreq = st.tensor("dep_layer.cross_attn.rotary.inv_freq");
        this.dNormW = st.tensor("dep_layer.norm.weight");

        this.s1W = st.tensor("head.proj_s1.weight");
        this.s1B = st.tensor("head.proj_s1.bias");
        this.s2W = st.tensor("head.proj_s2.weight");
        this.s2B = st.tensor("head.proj_s2.bias");
    }

    public int dModel() {
        return dModel;
    }

    public int vocabS1() {
        return vocabS1;
    }

    public int vocabS2() {
        return vocabS2;
    }

    public int layers() {
        return layers;
    }

    /** Embeds the two token streams plus the temporal stamps into {@code [rows][dModel]}. */
    private float[] embed(int[] s1, int[] s2, int[][] stamp, int rows) {
        int n = rows * dModel;
        float[] emb = new float[n];
        float[] time = new float[n];
        float[] cat = new float[rows * 2 * dModel];
        float scale = (float) Math.sqrt(dModel);
        for (int r = 0; r < rows; r++) {
            int src1 = Math.floorMod(s1[r], vocabS1) * dModel;
            int src2 = Math.floorMod(s2[r], vocabS2) * dModel;
            int dst = r * 2 * dModel;
            for (int i = 0; i < dModel; i++) {
                cat[dst + i] = embS1[src1 + i] * scale;
                cat[dst + dModel + i] = embS2[src2 + i] * scale;
            }
        }
        MlMath.linear(cat, rows, 2 * dModel, fusionW, fusionB, dModel, emb);

        if (stamp != null) {
            for (int r = 0; r < rows; r++) {
                int base = r * dModel;
                for (int f = 0; f < 5; f++) {
                    int idx = stamp[r][f];
                    int size = TIME_SIZES[f];
                    idx = idx < 0 ? 0 : Math.min(idx, size - 1);
                    int off = idx * dModel;
                    float[] table = timeTables[f];
                    for (int i = 0; i < dModel; i++) {
                        time[base + i] += table[off + i];
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                emb[i] += time[i];
            }
        }
        return emb;
    }

    /**
     * Stage 1: embed the context, run the transformer and produce s1 logits plus the context that
     * the dependency-aware layer will consume.
     */
    public S1Output decodeS1(int[] s1, int[] s2, int[][] stamp, int rows) {
        float[] x = embed(s1, s2, stamp, rows);
        for (KronosTransformerBlock block : blocks) {
            block.forward(x, rows);
        }
        MlMath.rmsNorm(x, rows, dModel, normW, 1e-5f);

        float[] logits = new float[rows * vocabS1];
        MlMath.linear(x, rows, dModel, s1W, s1B, vocabS1, logits);
        return new S1Output(logits, x, rows);
    }

    /**
     * Stage 2: condition on the sampled s1 token through the dependency-aware cross attention layer
     * and return the s2 logits of the last position (the only one the autoregressive loop needs).
     */
    public float[] decodeS2(float[] context, int rows, int s1Token) {
        float scale = (float) Math.sqrt(dModel);
        float[] sibling = new float[dModel];
        int src = Math.floorMod(s1Token, vocabS1) * dModel;
        for (int i = 0; i < dModel; i++) {
            sibling[i] = embS1[src + i] * scale;
        }

        float[] q = new float[dModel];
        MlMath.linear(sibling, 1, dModel, dqW, dqB, dModel, q);

        float[] k = new float[rows * dModel];
        float[] v = new float[rows * dModel];
        MlMath.linear(context, rows, dModel, dkW, dkB, dModel, k);
        MlMath.linear(context, rows, dModel, dvW, dvB, dModel, v);

        MlMath.ropeInPlace(q, 1, heads, headDim, dInvFreq);
        MlMath.ropeInPlace(k, rows, heads, headDim, dInvFreq);

        float[] attn = new float[dModel];
        MlMath.crossAttention(q, 1, k, v, rows, heads, headDim, attn);
        float[] projected = new float[dModel];
        MlMath.linear(attn, 1, dModel, doW, doB, dModel, projected);

        // hidden_states [rows] + attn_out [1] broadcasts in torch; only the last row is needed.
        float[] x2 = new float[dModel];
        int last = (rows - 1) * dModel;
        for (int i = 0; i < dModel; i++) {
            x2[i] = context[last + i] + projected[i];
        }
        MlMath.rmsNorm(x2, 1, dModel, dNormW, 1e-5f);

        float[] logits = new float[vocabS2];
        MlMath.linear(x2, 1, dModel, s2W, s2B, vocabS2, logits);
        return logits;
    }

    // ---- incremental (KV cache) decoding ----
    private float[] lastContextRow;
    private int incrementalPos;
    private float[] ctxBuf = new float[0];
    private int ctxRows;

    public void beginIncremental(int[] s1, int[] s2, int[][] stamp, int rows) {
        for (KronosTransformerBlock b : blocks) {
            b.resetCache(rows + 64);
        }
        incrementalPos = 0;
        ctxRows = 0;
        if (ctxBuf.length < (rows + 64) * dModel) {
            ctxBuf = new float[(rows + 64) * dModel];
        }
        for (int t = 0; t < rows; t++) {
            float[] x = embedOne(s1[t], s2[t], stamp == null ? null : stamp[t]);
            for (KronosTransformerBlock b : blocks) {
                b.forwardOne(x);
            }
            lastContextRow = x;
            incrementalPos++;
            appendNormalizedContext(x);
        }
    }

    /** Appends one token and returns the s1 logits predicting the following token. */
    public float[] stepIncremental(int s1Token, int s2Token, int[] stamp) {
        float[] x = embedOne(s1Token, s2Token, stamp);
        for (KronosTransformerBlock b : blocks) {
            b.forwardOne(x);
        }
        lastContextRow = x;
        incrementalPos++;
        appendNormalizedContext(x);
        float[] h = x.clone();
        MlMath.rmsNorm(h, 1, dModel, normW, 1e-5f);
        float[] logits = new float[vocabS1];
        MlMath.linear(h, 1, dModel, s1W, s1B, vocabS1, logits);
        return logits;
    }

    public float[] lastContextRow() {
        return lastContextRow == null ? null : lastContextRow.clone();
    }

    public int incrementalPos() {
        return incrementalPos;
    }

    private float[] embedOne(int s1Token, int s2Token, int[] stamp) {
        float scale = (float) Math.sqrt(dModel);
        float[] cat = new float[2 * dModel];
        int a = Math.floorMod(s1Token, vocabS1) * dModel;
        int b = Math.floorMod(s2Token, vocabS2) * dModel;
        for (int i = 0; i < dModel; i++) {
            cat[i] = embS1[a + i] * scale;
            cat[dModel + i] = embS2[b + i] * scale;
        }
        float[] x = new float[dModel];
        MlMath.linear(cat, 1, 2 * dModel, fusionW, fusionB, dModel, x);
        if (stamp != null) {
            int[] sizes = {60, 24, 7, 32, 13};
            for (int f = 0; f < 5; f++) {
                int idx = Math.max(0, Math.min(stamp[f], sizes[f] - 1));
                float[] table = timeTables[f];
                int off = idx * dModel;
                for (int i = 0; i < dModel; i++) {
                    x[i] += table[off + i];
                }
            }
        }
        return x;
    }

    /** s1 logits for the token after the last consumed position. */
    public float[] logitsFromLastContext() {
        float[] h = lastContextRow.clone();
        MlMath.rmsNorm(h, 1, dModel, normW, 1e-5f);
        float[] logits = new float[vocabS1];
        MlMath.linear(h, 1, dModel, s1W, s1B, vocabS1, logits);
        return logits;
    }

    /** Flat [rows][dModel] context accumulated by the incremental path (for decodeS2). */
    public float[] contextMatrix() {
        return java.util.Arrays.copyOf(ctxBuf, ctxRows * dModel);
    }

    public int contextRows() {
        return ctxRows;
    }

    /** decodeS2 expects the post-RMSNorm hidden state, exactly what decodeS1 returns. */
    private void appendNormalizedContext(float[] x) {
        if (ctxBuf.length < (ctxRows + 2) * dModel) {
            ctxBuf = java.util.Arrays.copyOf(ctxBuf, (ctxRows + 64) * dModel);
        }
        float[] n = x.clone();
        MlMath.rmsNorm(n, 1, dModel, normW, 1e-5f);
        System.arraycopy(n, 0, ctxBuf, ctxRows * dModel, dModel);
        ctxRows++;
    }
}