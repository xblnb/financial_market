package com.example.financialmarket.engine.random;

/**
 * 轻量可播种 PRNG，基于 SplitMix64（P0-7）。
 *
 * <p>用途：让行情序列可复现。同一 seed 构造的实例产生完全一致的随机序列，
 * 从而「同世界种子 → 同行情」。各模块/资产用 {@link #derive(String)} 或
 * {@link #mix64(long)} 派生子流，保证随机流隔离且确定。
 *
 * <p>SplitMix64 是高速、质量良好的 64 位伪随机生成器，无需线程安全（每实例独立状态）。
 */
public class SeedableRandom {
    private long state;
    private double spareGaussian = Double.NaN; // Box-Muller 缓存第二个样本

    public SeedableRandom(long seed) {
        this.state = seed;
    }

    /** SplitMix64 终混散列：把任意 long 派生为独立流种子。 */
    public static long mix64(long x) {
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    /** 从本实例派生子流（seed = mix64(当前派生基准 ^ id)），隔离且确定。 */
    public long derive(String id) {
        return mix64(state ^ id.hashCode());
    }

    public long nextLong() {
        long z = (state += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public double nextDouble() {
        return (nextLong() >>> 11) * (1.0 / (1L << 53));
    }

    /** 返回 [0, bound) 的整数。 */
    public int nextInt(int bound) {
        if (bound <= 0) return 0;
        // 拒绝采样避免模偏差
        long r = nextLong();
        long m = bound;
        if ((bound & -bound) == bound) { // power of two
            return (int) ((bound * (r >>> 33)) >> 31);
        }
        long limit = (Long.MAX_VALUE / m) * m;
        while ((r & Long.MAX_VALUE) >= limit) {
            r = nextLong();
        }
        return (int) ((r & Long.MAX_VALUE) % m);
    }

    public double nextGaussian() {
        // Box-Muller（Marsaglia 极坐标法），缓存第二个样本
        if (!Double.isNaN(spareGaussian)) {
            double v = spareGaussian;
            spareGaussian = Double.NaN;
            return v;
        }
        double u1, u2, s;
        do {
            // 极坐标法要求 u1,u2 ∈ [-1,1)；nextDouble()∈[0,1)，须映射为 2*u-1，
            // 否则恒非负（半正态），GBM/GARCH ε 只能上行导致路径单调漂移。
            u1 = 2.0 * nextDouble() - 1.0;
            u2 = 2.0 * nextDouble() - 1.0;
            s = u1 * u1 + u2 * u2;
        } while (s >= 1.0 || s <= 0.0);
        double mul = Math.sqrt(-2.0 * Math.log(s) / s);
        spareGaussian = u2 * mul;
        return u1 * mul;
    }

    public boolean nextBoolean() {
        return (nextLong() & 1L) == 1L;
    }

    /** 返回 [min, max] 的整数（含端点）。 */
    public int nextIntInclusive(int min, int max) {
        return min + nextInt(max - min + 1);
    }

    // ---------- 状态持久化（读档后行情可继续复现） ----------
    public long getState() { return state; }
    public void setState(long state) { this.state = state; }
    public double getSpareGaussian() { return spareGaussian; }
    public void setSpareGaussian(double spareGaussian) { this.spareGaussian = spareGaussian; }
}
