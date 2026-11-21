package com.example.financialmarket.engine.ml;

/**
 * One pre-norm transformer block as used by Kronos (self attention with RoPE + SwiGLU feed forward),
 * matching {@code model.module.TransformerBlock} of the reference implementation.
 *
 * <p>Weights are pulled straight out of a {@link Safetensors} checkpoint by name prefix, so the
 * class doubles as the loader for both the tokenizer (encoder/decoder stacks) and the main
 * forecaster (the {@code transformer.N} stack).</p>
 */
final class KronosTransformerBlock {

    final int dModel;
    final int heads;
    final int headDim;
    final int ffDim;

    private final float[] norm1;
    private final float[] wq;
    private final float[] bq;
    private final float[] wk;
    private final float[] bk;
    private final float[] wv;
    private final float[] bv;
    private final float[] wo;
    private final float[] bo;
    private final float[] invFreq;
    private final float[] norm2;
    private final float[] w1;
    private final float[] w2;
    private final float[] w3;

    KronosTransformerBlock(Safetensors st, String prefix, int dModel, int heads, int ffDim) {
        this.dModel = dModel;
        this.heads = heads;
        this.headDim = dModel / heads;
        this.ffDim = ffDim;

        this.norm1 = st.tensor(prefix + ".norm1.weight");
        this.wq = st.tensor(prefix + ".self_attn.q_proj.weight");
        this.bq = st.tensor(prefix + ".self_attn.q_proj.bias");
        this.wk = st.tensor(prefix + ".self_attn.k_proj.weight");
        this.bk = st.tensor(prefix + ".self_attn.k_proj.bias");
        this.wv = st.tensor(prefix + ".self_attn.v_proj.weight");
        this.bv = st.tensor(prefix + ".self_attn.v_proj.bias");
        this.wo = st.tensor(prefix + ".self_attn.out_proj.weight");
        this.bo = st.tensor(prefix + ".self_attn.out_proj.bias");
        this.invFreq = st.tensor(prefix + ".self_attn.rotary.inv_freq");
        this.norm2 = st.tensor(prefix + ".norm2.weight");
        this.w1 = st.tensor(prefix + ".ffn.w1.weight");
        this.w2 = st.tensor(prefix + ".ffn.w2.weight");
        this.w3 = st.tensor(prefix + ".ffn.w3.weight");

        if (norm1.length != dModel || wq.length != dModel * dModel || w1.length != ffDim * dModel) {
            throw new IllegalArgumentException("Kronos: tensor shapes do not match config at " + prefix);
        }
    }

    /** In-place forward pass over {@code rows} tokens laid out as {@code [rows][dModel]}. */
    void forward(float[] x, int rows) {
        int n = rows * dModel;
        float[] h = x.clone();
        MlMath.rmsNorm(h, rows, dModel, norm1, 1e-5f);

        float[] q = new float[n];
        float[] k = new float[n];
        float[] v = new float[n];
        MlMath.linear(h, rows, dModel, wq, bq, dModel, q);
        MlMath.linear(h, rows, dModel, wk, bk, dModel, k);
        MlMath.linear(h, rows, dModel, wv, bv, dModel, v);

        MlMath.ropeInPlace(q, rows, heads, headDim, invFreq);
        MlMath.ropeInPlace(k, rows, heads, headDim, invFreq);

        float[] attn = new float[n];
        MlMath.causalAttention(q, k, v, rows, heads, headDim, attn);

        float[] projected = new float[n];
        MlMath.linear(attn, rows, dModel, wo, bo, dModel, projected);
        MlMath.addInPlace(x, projected);

        h = x.clone();
        MlMath.rmsNorm(h, rows, dModel, norm2, 1e-5f);

        float[] gate = new float[rows * ffDim];
        float[] up = new float[rows * ffDim];
        MlMath.linear(h, rows, dModel, w1, null, ffDim, gate);
        MlMath.linear(h, rows, dModel, w3, null, ffDim, up);
        MlMath.siluInPlace(gate);
        MlMath.mul(gate, up, rows * ffDim, gate);

        float[] ffnOut = new float[n];
        MlMath.linear(gate, rows, ffDim, w2, null, dModel, ffnOut);
        MlMath.addInPlace(x, ffnOut);
    }

    // ---- incremental (KV cache) decoding ----
    private float[] kCache = new float[0];
    private float[] vCache = new float[0];
    private int cached;

    void resetCache(int capacity) {
        int size = Math.max(1, capacity) * dModel;
        if (kCache.length < size) {
            kCache = new float[size];
            vCache = new float[size];
        }
        cached = 0;
    }

    int cached() {
        return cached;
    }

    /** Processes one token, appends its K/V to the cache, returns the updated row. */
    float[] forwardOne(float[] xRow) {
        int d = dModel;
        float[] h = xRow.clone();
        MlMath.rmsNorm(h, 1, d, norm1, 1e-5f);
        float[] q = new float[d];
        float[] k = new float[d];
        float[] v = new float[d];
        MlMath.linear(h, 1, d, wq, bq, d, q);
        MlMath.linear(h, 1, d, wk, bk, d, k);
        MlMath.linear(h, 1, d, wv, bv, d, v);
        MlMath.ropeInPlaceAt(q, 1, heads, headDim, invFreq, cached);
        MlMath.ropeInPlaceAt(k, 1, heads, headDim, invFreq, cached);
        System.arraycopy(k, 0, kCache, cached * d, d);
        System.arraycopy(v, 0, vCache, cached * d, d);
        int total = cached + 1;
        float[] attn = new float[d];
        MlMath.attendOne(q, kCache, vCache, total, heads, headDim, attn);
        float[] proj = new float[d];
        MlMath.linear(attn, 1, d, wo, bo, d, proj);
        for (int i = 0; i < d; i++) {
            xRow[i] += proj[i];
        }
        h = xRow.clone();
        MlMath.rmsNorm(h, 1, d, norm2, 1e-5f);
        float[] gate = new float[ffDim];
        float[] up = new float[ffDim];
        MlMath.linear(h, 1, d, w1, null, ffDim, gate);
        MlMath.linear(h, 1, d, w3, null, ffDim, up);
        MlMath.siluInPlace(gate);
        for (int i = 0; i < ffDim; i++) {
            gate[i] *= up[i];
        }
        float[] ffn = new float[d];
        MlMath.linear(gate, 1, ffDim, w2, null, d, ffn);
        for (int i = 0; i < d; i++) {
            xRow[i] += ffn[i];
        }
        cached = total;
        return xRow;
    }
}