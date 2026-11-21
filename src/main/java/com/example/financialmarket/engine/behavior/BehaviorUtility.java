package com.example.financialmarket.engine.behavior;

/**
 * P1-2 前景理论 / 损失厌恶 / 处置效应效用函数（纯 Java，无随机）。
 *
 * <p>分段幂价值函数（Kahneman &amp; Tversky 前景理论）：
 * <pre>
 *   V(x) =  x^α            (x ≥ 0)
 *   V(x) =  −λ·(−x)^β      (x &lt; 0)
 * </pre>
 * 参数默认 {@code λ=2.0}（损失厌恶）、{@code α=0.88}、{@code β=0.88}。
 *
 * <p>处置效应（disposition effect）：以参考点（AgentState.avgCost）判断盈亏，
 * 盈利（gain>0）时卖出倾向增强（赢家拿不住），亏损（gain<0）时卖出倾向减弱（亏家扛着）。
 */
public class BehaviorUtility {

    /** 损失厌恶系数 λ ≈ 2。 */
    private final double lambda;
    /** 收益段幂指数 α，默认 0.88。 */
    private final double alpha;
    /** 损失段幂指数 β，默认 0.88。 */
    private final double beta;
    /** 处置效应强度 k_dispo，默认 1.2。 */
    private final double kDispo;

    public BehaviorUtility() {
        this(2.0, 0.88, 0.88, 1.2);
    }

    /**
     * 全参数构造。
     *
     * @param lambda 损失厌恶系数（≈2）。
     * @param alpha  收益段幂指数（0,1]，默认 0.88。
     * @param beta   损失段幂指数（0,1]，默认 0.88。
     * @param kDispo 处置效应强度，默认 1.2。
     */
    public BehaviorUtility(double lambda, double alpha, double beta, double kDispo) {
        this.lambda = lambda > 0 ? lambda : 2.0;
        this.alpha = (alpha > 0 && alpha <= 1.0) ? alpha : 0.88;
        this.beta = (beta > 0 && beta <= 1.0) ? beta : 0.88;
        this.kDispo = kDispo > 0 ? kDispo : 1.2;
    }

    /**
     * 前景理论分段幂价值：V(x) = x^α (x≥0)，V(x) = −λ·(−x)^β (x<0)。
     *
     * @param x 决策参考收益（可为当前价与参考点之差或收益率）。
     * @return 行为价值（亏损端因 λ 放大而更陡峭）。
     */
    public double prospectValue(double x) {
        if (x >= 0.0) {
            return Math.pow(x, alpha);
        }
        return -lambda * Math.pow(-x, beta);
    }

    /**
     * 处置效应卖出倾向乘数。
     *
     * <p>{@code gain = (lastPrice − avgCost)/avgCost}，以参考点（AgentState.avgCost）判断盈亏：
     * <ul>
     *   <li>gain &gt; 0（盈利）→ 乘数 &gt; 1（赢家更倾向卖出，处置效应）；</li>
     *   <li>gain &lt; 0（亏损）→ 乘数 &lt; 1（亏家更倾向持有）。</li>
     * </ul>
     * 计算 {@code mult = 1 + k_dispo·gain}（gain 钳制到 ±0.5 防发散），再钳制到 [0.5, 2.0]。
     *
     * @param gain 收益率（(lastPrice − avgCost)/avgCost）。
     * @return 卖出倾向乘数 ∈ [0.5, 2.0]。
     */
    public double dispositionSellMultiplier(double gain) {
        double clamped = Math.max(-0.5, Math.min(0.5, gain));
        double mult = 1.0 + kDispo * clamped;
        return Math.max(0.5, Math.min(2.0, mult));
    }

    /**
     * 用前景理论价值缩放下单倾向（可选增强）。
     *
     * @param gain 收益率（相对参考点 avgCost）。
     * @return 行为权重：亏损端因损失厌恶陡峭而降低买入/持有意愿。若 gain 接近 0 返回 ~0。
     */
    public double prospectAdjusted(double gain) {
        return prospectValue(gain);
    }

    /** 损失厌恶系数 λ。 */
    public double getLambda() { return lambda; }
    /** 收益段幂指数 α。 */
    public double getAlpha() { return alpha; }
    /** 损失段幂指数 β。 */
    public double getBeta() { return beta; }
    /** 处置效应强度 k_dispo。 */
    public double getDispositionK() { return kDispo; }
}
