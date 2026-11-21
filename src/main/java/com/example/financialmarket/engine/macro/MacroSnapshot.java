package com.example.financialmarket.engine.macro;

import com.example.financialmarket.engine.crisis.EconomicCycle;

/**
 * MarS 移植（MP-1）宏观政策层——确定性只读快照。
 *
 * <p>MarS 论文（arxiv 2409.07486）的 Market Condition / Environment Model 对应物：把分散在
 * 央行/政府/经济周期/危机引擎/宏观环境中的政策状态折叠为一张只读快照，供中观层（板块基金/
 * 机构投资者）与微观层（做市商/方向性/流动性）按同一确定性视图消费，形成
 * 「政策 → 机构 → 做市商 → 盘口」三层传导的输入面。
 *
 * <p>确定性硬约束（对齐 {@code MacroEnvironment.applyMacroTransmission} 的「衍生量不持久化」约定，
 * {@code MacroEnvironment.java:62-71} 注释）：快照为<strong>纯函数</strong>——全部字段来源为引擎
 * 既有确定性量（政策利率/准备金率/财政立场/周期相位/危机等级/信贷增速/M2 增速/通胀/流动性乘子/
 * 风险偏好/折现率/动态漂移/动态波动），无随机；构造即组装，不持久化——读档后以当期输入确定性重建。
 *
 * @param policyRate         央行政策利率（贴现率）
 * @param reserveRequirement 法定准备金率
 * @param fiscalStance       政府财政立场（0 保守 ~ 1 扩张）
 * @param cyclePhase         经济周期相位（EXPANSION/PEAK/CONTRACTION/TROUGH）
 * @param crisisLevel        危机等级（≥0）
 * @param creditGrowth       信贷增速
 * @param m2Growth           M2 增速（prevM2 无效时回落 0）
 * @param inflationRate      通胀率
 * @param liquidityMultiplier 流动性乘子 [0.1,1]
 * @param riskAppetite       风险偏好 [0.05,1]
 * @param discountRate       折现率（基本面估值用）
 * @param dynamicDrift       宏观动态漂移率 μ(t)
 * @param dynamicVolatility  宏观动态波动率 σ(t)
 */
public record MacroSnapshot(
        double policyRate,
        double reserveRequirement,
        double fiscalStance,
        EconomicCycle.Phase cyclePhase,
        double crisisLevel,
        double creditGrowth,
        double m2Growth,
        double inflationRate,
        double liquidityMultiplier,
        double riskAppetite,
        double discountRate,
        double dynamicDrift,
        double dynamicVolatility
) {
    /**
     * 静态工厂（MP-1 §3.2-1 约定的 {@code MacroSnapshot build()}）：全部输入为引擎既有确定性量，
     * 纯函数、无随机、无持久化。由 {@code MarketEngine.buildMacroSnapshot()} 每宏观步组装一次。
     */
    public static MacroSnapshot build(
            double policyRate,
            double reserveRequirement,
            double fiscalStance,
            EconomicCycle.Phase cyclePhase,
            double crisisLevel,
            double creditGrowth,
            double m2Growth,
            double inflationRate,
            double liquidityMultiplier,
            double riskAppetite,
            double discountRate,
            double dynamicDrift,
            double dynamicVolatility) {
        return new MacroSnapshot(
                policyRate,
                reserveRequirement,
                fiscalStance,
                cyclePhase,
                crisisLevel,
                creditGrowth,
                m2Growth,
                inflationRate,
                liquidityMultiplier,
                riskAppetite,
                discountRate,
                dynamicDrift,
                dynamicVolatility);
    }
}
