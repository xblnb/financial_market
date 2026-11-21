package com.example.financialmarket.engine.ml;

/**
 * Pure-Java port of the Kronos BSQ tokenizer ({@code Kronos-Tokenizer-base}).
 *
 * <p>The tokenizer turns a window of normalised OHLCV bars into two integer token streams
 * ({@code s1} = high 10 bits, {@code s2} = low 10 bits of a 20-bit binary code) and back. It is a
 * small causal transformer (4 + 4 layers, d_model 256) followed by a binary spherical quantizer:
 * the quantizer only needs the sign of each of the 20 projection outputs, which is why no codebook
 * lookup table is required.</p>
 *
 * <p>Mirrors {@code KronosTokenizer.encode/decode} with {@code half=True}.</p>
 */
public final class KronosTokenizer {

    private final int dIn;
    private final int dModel;
    private final int s1Bits;
    private final int s2Bits;
    private final int codebookDim;

    private final float[] embedW;
    private final float[] embedB;
    private final KronosTransformerBlock[] encoder;
    private final float[] quantEmbedW;
    private final float[] quantEmbedB;
    private final float[] postQuantW;
    private final float[] postQuantB;
    private final KronosTransformerBlock[] decoder;
    private final float[] headW;
    private final float[] headB;

    public KronosTokenizer(Safetensors st, int dIn, int dModel, int heads, int ffDim,
                           int encLayers, int decLayers, int s1Bits, int s2Bits) {
        this.dIn = dIn;
        this.dModel = dModel;
        this.s1Bits = s1Bits;
        this.s2Bits = s2Bits;
        this.codebookDim = s1Bits + s2Bits;

        this.embedW = st.tensor("embed.weight");
        this.embedB = st.tensor("embed.bias");
        this.encoder = new KronosTransformerBlock[encLayers - 1];
        for (int i = 0; i < encoder.length; i++) {
            encoder[i] = new KronosTransformerBlock(st, "encoder." + i, dModel, heads, ffDim);
        }
        this.quantEmbedW = st.tensor("quant_embed.weight");
        this.quantEmbedB = st.tensor("quant_embed.bias");
        this.postQuantW = st.tensor("post_quant_embed.weight");
        this.postQuantB = st.tensor("post_quant_embed.bias");
        this.decoder = new KronosTransformerBlock[decLayers - 1];
        for (int i = 0; i < decoder.length; i++) {
            decoder[i] = new KronosTransformerBlock(st, "decoder." + i, dModel, heads, ffDim);
        }
        this.headW = st.tensor("head.weight");
        this.headB = st.tensor("head.bias");
    }

    public int inputDim() {
        return dIn;
    }

    public int s1Bits() {
        return s1Bits;
    }

    public int s2Bits() {
        return s2Bits;
    }

    /**
     * Encodes {@code rows} bars of normalised features into two token streams.
     *
     * @param x row-major {@code [rows][dIn]} normalised OHLCV(+amount) values
     * @return {@code int[2][rows]} with the s1 and s2 token ids
     */
    public int[][] encode(float[] x, int rows) {
        float[] z = new float[rows * dModel];
        MlMath.linear(x, rows, dIn, embedW, embedB, dModel, z);
        for (KronosTransformerBlock block : encoder) {
            block.forward(z, rows);
        }
        float[] projected = new float[rows * codebookDim];
        MlMath.linear(z, rows, dModel, quantEmbedW, quantEmbedB, codebookDim, projected);

        int[] s1 = new int[rows];
        int[] s2 = new int[rows];
        for (int r = 0; r < rows; r++) {
            int off = r * codebookDim;
            double norm = 0.0;
            for (int i = 0; i < codebookDim; i++) {
                norm += (double) projected[off + i] * projected[off + i];
            }
            double invNorm = 1.0 / Math.max(Math.sqrt(norm), 1e-12);
            int first = 0;
            int second = 0;
            for (int i = 0; i < s1Bits; i++) {
                float v = (float) (projected[off + i] * invNorm);
                if (v >= 0f) {
                    first |= 1 << i;
                }
            }
            for (int i = 0; i < s2Bits; i++) {
                float v = (float) (projected[off + s1Bits + i] * invNorm);
                if (v >= 0f) {
                    second |= 1 << i;
                }
            }
            s1[r] = first;
            s2[r] = second;
        }
        return new int[][]{s1, s2};
    }

    /**
     * Decodes two token streams back into {@code rows} bars of features.
     *
     * @return row-major {@code [rows][dIn]}
     */
    public float[] decode(int[] s1, int[] s2, int rows) {
        float[] bits = new float[rows * codebookDim];
        float qScale = (float) (1.0 / Math.sqrt(codebookDim));
        for (int r = 0; r < rows; r++) {
            int off = r * codebookDim;
            int a = s1[r];
            int b = s2[r];
            for (int k = 0; k < s1Bits; k++) {
                bits[off + k] = (((a >> k) & 1) == 1 ? 1f : -1f) * qScale;
            }
            for (int k = 0; k < s2Bits; k++) {
                bits[off + s1Bits + k] = (((b >> k) & 1) == 1 ? 1f : -1f) * qScale;
            }
        }
        float[] z = new float[rows * dModel];
        MlMath.linear(bits, rows, codebookDim, postQuantW, postQuantB, dModel, z);
        for (KronosTransformerBlock block : decoder) {
            block.forward(z, rows);
        }
        float[] out = new float[rows * dIn];
        MlMath.linear(z, rows, dModel, headW, headB, dIn, out);
        return out;
    }
}
