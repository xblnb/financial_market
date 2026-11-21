package com.example.financialmarket.engine.calibration;

/**
 * P2-4 校准注入参数包（离线先验，纯 Java，无随机，无 Minecraft 运行时依赖）。
 *
 * <p>把真实市场（LOBSTER / 公开文献聚合统计）的<b>统计分布参数</b>硬编码/打包为离线先验，
 * 供 {@code OrderFlowModel} / {@code MarketImpact} 等 P2 模块按真实统计先验生成订单流、价差、
 * 深度与收益率分布，而非纯手调参数。离线时按先验生成，<b>无网络依赖</b>（A2-5）。
 *
 * <p>参数来源性质：公开文献 / LOBSTER 聚合统计（非运行依赖，仅先验）；各字段落在真实市场
 * 目标区间内（价差中位 ~0.002、深度衰减指数 >0.5、尾部 μ_tail ∈(2.5,3.5)、波动聚集 α+β≈0.95）。
 *
 * <p><b>可复现</b>：本类为静态先验常量（无随机、无状态），确定性消费；不引入任何裸随机。
 */
public final class CalibrationParams {

    // ---------- 价差分布 ----------
    /** 相对价差中位（真实市场先验），默认 0.002。 */
    public final double spreadMedian;
    // ---------- 深度分布 ----------
    /** 最优档深度中位（相对当量），默认 500。 */
    public final double depthAtBestMedian;
    /** 档位深度衰减幂律指数，默认 0.7（真实 LOB 深度衰减常 >0.5）。 */
    public final double depthDecayExponent;
    // ---------- 订单到达 ----------
    /** 基础到达率（每资产每 tick），默认 0.02。 */
    public final double orderArrivalRate;
    /** 大单概率，默认 0.05。 */
    public final double largeOrderProb;
    // ---------- 收益率分布 ----------
    /** 尾部幂律指数 μ_tail（肥尾），默认 3.0（目标区间 (2.5,3.5)）。 */
    public final double returnTailMu;
    // ---------- 波动聚集 ----------
    /** GARCH 持久性目标 α+β，默认 0.95。 */
    public final double volClusteringPersistence;
    // ---------- MarS 市场影响模型（MP-2，MarS ICLR'25 / arxiv 2409.07486） ----------
    /**
     * MarS 平方根冲击系数 α（Δ = α·σ·√(Q/V)），默认 0.01。
     * 先验依据：平方根冲击律文献区间（Almgren et al. 2005 的 X 值 0.1~1.0 为「成交量冲击」尺度，
     * 折算到「价格冲击」的 α 典型量级 1e-2~1e-1；本实现取保守先验 0.01，不拟合、不校准）。
     */
    public final double marSAlpha;
    /** ADV（平均日成交量）滚动窗口（交易日），默认 5。窗口为代码常量/先验，不调参。 */
    public final int advWindowDays;

    // ---------- DG-2 Almgren-Chriss 冲击成本模型先验（DATA_GUARD_SPEC §3.1/§3.3，冻结接口） ----------
    /** AC 流动性乘子基准 λ_base，默认 0.05（§3.1 标定反解；允许区间 [0.02, 0.20]）。 */
    public final double acLambdaBase;
    /** AC 冲击指数下界 γ_min（流动性充裕 / 无 ADV 信息时的兜底），默认 0.5。 */
    public final double acGammaMin;
    /** AC 冲击指数上界 γ_max（薄市 / 巨单占比高时逼近），默认 0.7。 */
    public final double acGammaMax;
    /** γ 饱和参考量占比 Q/ADV（γ 达到上界处的 liquidityRatio），默认 2.0。 */
    public final double acGammaRatioRef;
    /** AC 归一化尺度地板 Q0_FLOOR（股），默认 1.0e4（与冷启动单侧显示深度同量级，防「股数直接取幂」量纲错）。 */
    public final double acQ0Floor;
    /** AC 参考深度 REF_DEPTH_SHARES（股），默认 1.0e6（λ_liq = D_ref/REF_DEPTH_SHARES）。 */
    public final double acRefDepthShares;
    /** AC 订单自身深度探针封顶 Q_MAX_REF（股），默认 2.0e6（> 此量 λ_liq 饱和）。 */
    public final double acQMaxRef;
    /** AC 流动性乘子下界 LIQ_MULT_MIN，默认 0.05。 */
    public final double acLiqMultMin;
    /** AC 流动性乘子上界 LIQ_MULT_MAX，默认 20.0。 */
    public final double acLiqMultMax;
    /** AC 合成补充档预算系数（×ADV），默认 0.5（ADV=0 时回落 AC_SYNTH_QTY_BUDGET 兜底）。 */
    public final double acSynthBudgetAdvScale;
    /** AC 合成补充档深度总预算上限（股），默认 5.0e6（ADV=0/首日兜底，§3.2）。 */
    public final double acSynthQtyBudget;

    /** 全默认参数（打包先验）。 */
    public static final CalibrationParams DEFAULT = new CalibrationParams();

    public CalibrationParams() {
        this(0.002, 500.0, 0.7, 0.02, 0.05, 3.0, 0.95, 0.01, 5,
                0.05, 0.5, 0.7, 2.0, 1.0e4, 1.0e6, 2.0e6, 0.05, 20.0, 0.5, 5.0e6);
    }

    /**
     * 全参数构造（钳制非法值到默认，保证确定性）。
     *
     * @param spreadMedian          相对价差中位
     * @param depthAtBestMedian     最优档深度中位
     * @param depthDecayExponent    深度衰减幂律指数
     * @param orderArrivalRate      基础到达率
     * @param largeOrderProb        大单概率
     * @param returnTailMu          尾部幂律指数 μ_tail
     * @param volClusteringPersistence GARCH 持久性 α+β
     */
    public CalibrationParams(double spreadMedian, double depthAtBestMedian, double depthDecayExponent,
                             double orderArrivalRate, double largeOrderProb, double returnTailMu,
                             double volClusteringPersistence) {
        this(spreadMedian, depthAtBestMedian, depthDecayExponent, orderArrivalRate, largeOrderProb,
                returnTailMu, volClusteringPersistence, 0.01, 5,
                0.05, 0.5, 0.7, 2.0, 1.0e4, 1.0e6, 2.0e6, 0.05, 20.0, 0.5, 5.0e6);
    }

    /**
     * 全参数构造（钳制非法值到默认，保证确定性）。
     *
     * @param spreadMedian          相对价差中位
     * @param depthAtBestMedian     最优档深度中位
     * @param depthDecayExponent    深度衰减幂律指数
     * @param orderArrivalRate      基础到达率
     * @param largeOrderProb        大单概率
     * @param returnTailMu          尾部幂律指数 μ_tail
     * @param volClusteringPersistence GARCH 持久性 α+β
     * @param marSAlpha             MarS 平方根冲击系数 α（Δ=α·σ·√(Q/V)）
     * @param advWindowDays         ADV 滚动窗口（交易日）
     */
    public CalibrationParams(double spreadMedian, double depthAtBestMedian, double depthDecayExponent,
                             double orderArrivalRate, double largeOrderProb, double returnTailMu,
                             double volClusteringPersistence, double marSAlpha, int advWindowDays) {
        this(spreadMedian, depthAtBestMedian, depthDecayExponent, orderArrivalRate, largeOrderProb,
                returnTailMu, volClusteringPersistence, marSAlpha, advWindowDays,
                0.05, 0.5, 0.7, 2.0, 1.0e4, 1.0e6, 2.0e6, 0.05, 20.0, 0.5, 5.0e6);
    }

    /**
     * 全参数构造（含 DG-2 AC 先验；钳制非法值到默认，保证确定性）。
     *
     * @param spreadMedian             相对价差中位
     * @param depthAtBestMedian        最优档深度中位
     * @param depthDecayExponent       深度衰减幂律指数
     * @param orderArrivalRate         基础到达率
     * @param largeOrderProb           大单概率
     * @param returnTailMu             尾部幂律指数 μ_tail
     * @param volClusteringPersistence GARCH 持久性 α+β
     * @param marSAlpha                MarS 平方根冲击系数 α（Δ=α·σ·√(Q/V)）
     * @param advWindowDays            ADV 滚动窗口（交易日）
     * @param acLambdaBase             DG-2 AC 流动性乘子基准 λ_base（§3.1，先验 0.05）
     * @param acGammaMin               DG-2 AC 冲击指数下界 γ_min（先验 0.5）
     * @param acGammaMax               DG-2 AC 冲击指数上界 γ_max（先验 0.7）
     * @param acGammaRatioRef          DG-2 γ 饱和参考量占比 Q/ADV（先验 2.0）
     * @param acQ0Floor                DG-2 归一化尺度地板 Q0_FLOOR（先验 1.0e4 股）
     * @param acRefDepthShares         DG-2 参考深度 REF_DEPTH_SHARES（先验 1.0e6 股）
     * @param acQMaxRef                DG-2 订单深度探针封顶 Q_MAX_REF（先验 2.0e6 股）
     * @param acLiqMultMin             DG-2 流动性乘子下界（先验 0.05）
     * @param acLiqMultMax             DG-2 流动性乘子上界（先验 20.0）
     * @param acSynthBudgetAdvScale    DG-2 合成补充档预算系数（×ADV，先验 0.5）
     * @param acSynthQtyBudget         DG-2 合成补充档预算上限（先验 5.0e6 股）
     */
    public CalibrationParams(double spreadMedian, double depthAtBestMedian, double depthDecayExponent,
                             double orderArrivalRate, double largeOrderProb, double returnTailMu,
                             double volClusteringPersistence, double marSAlpha, int advWindowDays,
                             double acLambdaBase, double acGammaMin, double acGammaMax,
                             double acGammaRatioRef, double acQ0Floor, double acRefDepthShares,
                             double acQMaxRef, double acLiqMultMin, double acLiqMultMax,
                             double acSynthBudgetAdvScale, double acSynthQtyBudget) {
        this.spreadMedian = spreadMedian > 0 ? spreadMedian : 0.002;
        this.depthAtBestMedian = depthAtBestMedian > 0 ? depthAtBestMedian : 500.0;
        this.depthDecayExponent = (depthDecayExponent > 0 && depthDecayExponent <= 1.0)
                ? depthDecayExponent : 0.7;
        this.orderArrivalRate = (orderArrivalRate > 0 && orderArrivalRate <= 1.0)
                ? orderArrivalRate : 0.02;
        this.largeOrderProb = (largeOrderProb > 0 && largeOrderProb <= 1.0)
                ? largeOrderProb : 0.05;
        this.returnTailMu = returnTailMu > 0 ? returnTailMu : 3.0;
        this.volClusteringPersistence = (volClusteringPersistence > 0 && volClusteringPersistence < 1.0)
                ? volClusteringPersistence : 0.95;
        // MarS 先验钳制（确定性）：α ∈ (0, 0.5)，窗口 ∈ [1, 30] 交易日
        this.marSAlpha = (marSAlpha > 0 && marSAlpha < 0.5) ? marSAlpha : 0.01;
        this.advWindowDays = (advWindowDays >= 1 && advWindowDays <= 30) ? advWindowDays : 5;
        // DG-2 AC 先验钳制（确定性）：λ_base ∈ (0, 0.5)，γ ∈ [0.5, 0.7] 且 min<max，
        // 其余深度尺度/预算参数取正数（非法值回落规格默认）
        this.acLambdaBase = (acLambdaBase > 0 && acLambdaBase < 0.5) ? acLambdaBase : 0.05;
        double gMin = (acGammaMin >= 0.1 && acGammaMin <= 0.9) ? acGammaMin : 0.5;
        double gMax = (acGammaMax >= 0.1 && acGammaMax <= 0.9) ? acGammaMax : 0.7;
        if (gMax < gMin) { double t = gMin; gMin = gMax; gMax = t; }
        this.acGammaMin = gMin;
        this.acGammaMax = gMax;
        this.acGammaRatioRef = acGammaRatioRef > 0 ? acGammaRatioRef : 2.0;
        this.acQ0Floor = acQ0Floor > 0 ? acQ0Floor : 1.0e4;
        this.acRefDepthShares = acRefDepthShares > 0 ? acRefDepthShares : 1.0e6;
        this.acQMaxRef = acQMaxRef > 0 ? acQMaxRef : 2.0e6;
        this.acLiqMultMin = acLiqMultMin > 0 ? acLiqMultMin : 0.05;
        this.acLiqMultMax = acLiqMultMax >= this.acLiqMultMin ? acLiqMultMax : 20.0;
        this.acSynthBudgetAdvScale = acSynthBudgetAdvScale > 0 ? acSynthBudgetAdvScale : 0.5;
        this.acSynthQtyBudget = acSynthQtyBudget > 0 ? acSynthQtyBudget : 5.0e6;
    }

    public double getSpreadMedian() { return spreadMedian; }
    public double getDepthAtBestMedian() { return depthAtBestMedian; }
    public double getDepthDecayExponent() { return depthDecayExponent; }
    public double getOrderArrivalRate() { return orderArrivalRate; }
    public double getLargeOrderProb() { return largeOrderProb; }
    public double getReturnTailMu() { return returnTailMu; }
    public double getVolClusteringPersistence() { return volClusteringPersistence; }
    /** MarS 平方根冲击系数 α（Δ=α·σ·√(Q/V)，先验 0.01）。 */
    public double getMarSAlpha() { return marSAlpha; }
    /** ADV 滚动窗口（交易日，先验 5）。 */
    public int getAdvWindowDays() { return advWindowDays; }

    // ---------- DG-2 AC（DATA_GUARD_SPEC §3.1）只读访问器 ----------
    /** AC 流动性乘子基准 λ_base（先验 0.05，允许区间 [0.02, 0.20]）。 */
    public double getAcLambdaBase() { return acLambdaBase; }
    /** AC 冲击指数下界 γ_min（先验 0.5）。 */
    public double getAcGammaMin() { return acGammaMin; }
    /** AC 冲击指数上界 γ_max（先验 0.7）。 */
    public double getAcGammaMax() { return acGammaMax; }
    /** γ 饱和参考量占比 Q/ADV（先验 2.0）。 */
    public double getAcGammaRatioRef() { return acGammaRatioRef; }
    /** 归一化尺度地板 Q0_FLOOR（股，先验 1.0e4）。 */
    public double getAcQ0Floor() { return acQ0Floor; }
    /** 参考深度 REF_DEPTH_SHARES（股，先验 1.0e6）。 */
    public double getAcRefDepthShares() { return acRefDepthShares; }
    /** 订单深度探针封顶 Q_MAX_REF（股，先验 2.0e6）。 */
    public double getAcQMaxRef() { return acQMaxRef; }
    /** 流动性乘子下界（先验 0.05）。 */
    public double getAcLiqMultMin() { return acLiqMultMin; }
    /** 流动性乘子上界（先验 20.0）。 */
    public double getAcLiqMultMax() { return acLiqMultMax; }
    /** 合成补充档预算系数（×ADV，先验 0.5）。 */
    public double getAcSynthBudgetAdvScale() { return acSynthBudgetAdvScale; }
    /** 合成补充档深度总预算上限（股，先验 5.0e6）。 */
    public double getAcSynthQtyBudget() { return acSynthQtyBudget; }
}
