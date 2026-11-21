package com.example.financialmarket.engine.behavior;

/**
 * P2-1 异质交易者内生定价参数包（纯 Java，无随机，无 Minecraft 运行时依赖）。
 *
 * <p>集中管理三类交易者（fundamentalist / chartist / noise / liquidity）的占比与
 * 内生定价锚权重，并作为 <b>P2 开/关开关</b>：{@code endogenousEnabled=false} 时，
 * 内生定价锚退化为 P0-4 的 pure fairValue 混合锚、chartist 动量信号回落到 pure
 * priceChange、agent 占比回落到 P1 原值，从而与 P1 行为逐位一致（因子隔离，A2-2）。
 *
 * <p><b>硬约束</b>：{@code anchorWeightMid + anchorWeightFV ≈ 1.0}（避免 mid 反馈振荡），
 * 四种占比之和 = 1.0（由构造钳制归一化）。
 */
public class BehaviorParams {

    /** P2 内生定价开关（false → 行为与 P1 一致）。 */
    public final boolean endogenousEnabled;
    /** 订单簿 mid 锚权重（默认 0.5）。 */
    public final double anchorWeightMid;
    /** fairValue 混合锚权重（默认 0.5；需与 mid 权重之和 ≈ 1）。 */
    public final double anchorWeightFV;
    /** fundamentalist（VALUE）占比，默认 0.35。 */
    public final double fundamentalShare;
    /** chartist（MOMENTUM）占比，默认 0.30。 */
    public final double chartistShare;
    /** noise（RETAIL）占比，默认 0.25。 */
    public final double noiseShare;
    /** liquidity provider（MARKET_MAKER）占比，默认 0.10。 */
    public final double liquidityShare;

    /** P1 原占比（P2 关闭时恢复，保证行为逐位一致）。 */
    private static final double P1_VALUE_SHARE = 0.3;
    private static final double P1_RETAIL_SHARE = 0.3;
    private static final double P1_MOMENTUM_SHARE = 0.2;

    public BehaviorParams() {
        this(true, 0.5, 0.5, 0.35, 0.30, 0.25, 0.10);
    }

    /**
     * 全参数构造；占比之和不为 1 时按比例归一化，mid/FV 权重之和钳制到约 1。
     */
    public BehaviorParams(boolean endogenousEnabled, double anchorWeightMid, double anchorWeightFV,
                          double fundamentalShare, double chartistShare, double noiseShare,
                          double liquidityShare) {
        this.endogenousEnabled = endogenousEnabled;
        // 锚权重：mid + fv 归一化到近似 1（避免反馈振荡）
        double wSum = anchorWeightMid + anchorWeightFV;
        if (wSum <= 0) { anchorWeightMid = 0.5; anchorWeightFV = 0.5; wSum = 1.0; }
        this.anchorWeightMid = anchorWeightMid / wSum;
        this.anchorWeightFV = anchorWeightFV / wSum;
        // 占比归一化
        double sSum = fundamentalShare + chartistShare + noiseShare + liquidityShare;
        if (sSum <= 0) { fundamentalShare = 0.35; chartistShare = 0.30; noiseShare = 0.25; liquidityShare = 0.10; sSum = 1.0; }
        this.fundamentalShare = fundamentalShare / sSum;
        this.chartistShare = chartistShare / sSum;
        this.noiseShare = noiseShare / sSum;
        this.liquidityShare = liquidityShare / sSum;
    }

    /** 全默认参数（打包先验）。 */
    public static final BehaviorParams DEFAULT = new BehaviorParams();

    public double getFundamentalShare() { return fundamentalShare; }
    public double getChartistShare() { return chartistShare; }
    public double getNoiseShare() { return noiseShare; }
    public double getLiquidityShare() { return liquidityShare; }
    public boolean isEndogenousEnabled() { return endogenousEnabled; }
    public double getAnchorWeightMid() { return anchorWeightMid; }
    public double getAnchorWeightFV() { return anchorWeightFV; }
}
