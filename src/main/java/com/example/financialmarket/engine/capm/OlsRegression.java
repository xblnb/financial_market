package com.example.financialmarket.engine.capm;

/**
 * 确定性最小二乘（纯函数、无随机）。
 *
 * <p>CAPM_SPEC §5.1（CM-4a/CM-4d）：给定 N 对 (x, y)（x = 市场组合/指数对数收益、y = 资产对数收益），
 * 返回 (β̂, α̂, R², n)。核心公式 β̂ = Σ(x−x̄)(y−ȳ) / Σ(x−x̄)²（均值中心化，含截距的 OLS 斜率）。
 * 纯 Java 静态方法，供 {@link CapmModel} 与验证探针共用（探针可交叉复算，|Δ|&lt;1e-6）。
 *
 * <p>确定性纪律：本类不消费任何随机源；同一输入恒得同一输出（IEEE double 确定性算术）。
 *
 * <p>失效语义（返回 {@code null}，调用方回落行业先验，CAPM_SPEC §5.1「方差为 0 或样本不足 → 不回归」）：
 * <ul>
 *   <li>x/y 长度不等或样本数 &lt; 2；</li>
 *   <li>x 方差为零（Sxx == 0：指数收益无波动 → 无法估计市场 β）；</li>
 *   <li>任一输入非有限（NaN/Inf，理论不可达，防御性护栏）。</li>
 * </ul>
 */
public final class OlsRegression {

    /** OLS 回归结果（不可变纯数据载体）。 */
    public static final class Result {
        /** 斜率 β̂（市场 β 的 OLS 估计）。 */
        public final double beta;
        /** 截距 α̂。 */
        public final double alpha;
        /** 决定系数 R²（x 对 y 的解释比例；Syy==0 时记 0）。 */
        public final double r2;
        /** 样本数 n（参与回归的 (x,y) 对数）。 */
        public final int n;

        public Result(double beta, double alpha, double r2, int n) {
            this.beta = beta;
            this.alpha = alpha;
            this.r2 = r2;
            this.n = n;
        }
    }

    private OlsRegression() {
    }

    /**
     * 对 (x,y) 做确定性 OLS。均值为样本均值；斜率 = Sxy/Sxx；截距 = ȳ − β̂·x̄。
     *
     * @param x 自变量（指数对数收益）数组
     * @param y 因变量（资产对数收益）数组
     * @return {@link Result}；样本不足/长度不等/x 零方差/非有限输入时返回 {@code null}
     */
    public static Result ols(double[] x, double[] y) {
        if (x == null || y == null || x.length != y.length || x.length < 2) {
            return null;
        }
        int n = x.length;
        double mx = 0.0;
        double my = 0.0;
        for (int i = 0; i < n; i++) {
            double xi = x[i];
            double yi = y[i];
            if (!Double.isFinite(xi) || !Double.isFinite(yi)) {
                return null;
            }
            mx += xi;
            my += yi;
        }
        mx /= n;
        my /= n;

        double sxx = 0.0;
        double sxy = 0.0;
        double syy = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
        }
        // 指数收益零方差 → 无法估计市场 β → 调用方回落行业先验（CAPM_SPEC §5.1）。
        // 判定用微小确定性阈值而非 ==0.0：常数 x 因浮点舍入会留下 ~1e-32 量级残差（0.05 等非精确二进制
        // 常量），仍应视为零方差；真实引擎收益序列 sxx 通常在 1e-5 以上，阈值不影响正常回归（确定性）。
        if (sxx <= 1e-15 || !Double.isFinite(sxx) || !Double.isFinite(sxy)) {
            return null;
        }
        double beta = sxy / sxx;
        double alpha = my - beta * mx;
        double r2 = syy > 0.0 ? (sxy * sxy) / (sxx * syy) : 0.0;
        return new Result(beta, alpha, r2, n);
    }
}
