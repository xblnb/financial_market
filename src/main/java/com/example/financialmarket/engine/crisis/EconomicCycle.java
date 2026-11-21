package com.example.financialmarket.engine.crisis;

/**
 * 经济周期相位模型（crisis 包，t4 落地，替代原 4 行空壳）。
 *
 * <p>与已接线的 {@link com.example.financialmarket.engine.macro.EconomicCycleEngine} 分工：
 * {@code EconomicCycleEngine} 负责计算 {@code economicHealth}（Kitchin/Juglar/Kuznets + 信贷/商品
 * 正弦波），本类只负责把 {@code economicHealth} 映射为四相（纯函数，无随机），供
 * {@link CrisisEngine} 消费（收缩/萧条时提高危机倾向）。
 *
 * <p>阈值与 {@code EconomicCycleEngine.getPhaseName()} 对齐：&gt;0.4 扩张、&gt;0.1 顶部/复苏、
 * &gt;-0.3 收缩、否则萧条。
 */
public class EconomicCycle {
    public enum Phase { EXPANSION, PEAK, CONTRACTION, TROUGH }

    private EconomicCycle() {
    }

    /** 由经济健康度确定性映射相位（纯函数）。 */
    public static Phase phaseOf(double economicHealth) {
        if (economicHealth > 0.4) return Phase.EXPANSION;
        if (economicHealth > 0.1) return Phase.PEAK;
        if (economicHealth > -0.3) return Phase.CONTRACTION;
        return Phase.TROUGH;
    }

    /** 相位 → 危机倾向加项（确定性常量，供 CrisisEngine 提升 crisisLevel）。 */
    public static double crisisPressure(Phase phase) {
        switch (phase) {
            case TROUGH:      return 2.0;
            case CONTRACTION: return 1.0;
            default:          return 0.0;
        }
    }

    // ========== 周期 → 微观行为 / 宏观环境 传导（MS-AI-6：显式相位 → agent 行为，纯函数无随机） ==========

    /** 相位 → 风险偏好偏置（加到风险偏好）。繁荣/顶部风险偏好高，收缩/萧条显著降险。 */
    public static double riskAppetiteBias(Phase phase) {
        switch (phase) {
            case EXPANSION:   return 0.10;
            case PEAK:        return 0.02;
            case CONTRACTION: return -0.15;
            default:          return -0.30; // TROUGH
        }
    }

    /** 相位 → 杠杆偏好乘子（&gt;1 繁荣/顶部杠杆堆积；&lt;1 收缩/萧条强制去杠杆）。明斯基杠杆堆积→塌陷闭环。 */
    public static double leverageMultiplier(Phase phase) {
        switch (phase) {
            case EXPANSION:   return 1.20;
            case PEAK:        return 1.35; // 信贷过热顶部，杠杆堆到峰值
            case CONTRACTION: return 0.75;
            default:          return 0.50; // 萧条强制去杠杆
        }
    }

    /** 相位 → 市场波动乘子（繁荣平稳、萧条高波动）。 */
    public static double volatilityMultiplier(Phase phase) {
        switch (phase) {
            case EXPANSION:   return 0.75;
            case PEAK:        return 1.05;
            case CONTRACTION: return 1.35;
            default:          return 1.60;
        }
    }

    /** 相位 → 价差乘子（&gt;1 价差扩大；萧条期买卖价差显著走阔）。 */
    public static double spreadMultiplier(Phase phase) {
        switch (phase) {
            case EXPANSION:   return 0.85;
            case PEAK:        return 1.10;
            case CONTRACTION: return 1.35;
            default:          return 1.70;
        }
    }

    /** 相位 → 盘口深度乘子（&lt;1 深度变薄；萧条期盘口骤降）。 */
    public static double depthMultiplier(Phase phase) {
        switch (phase) {
            case EXPANSION:   return 1.30;
            case PEAK:        return 1.00;
            case CONTRACTION: return 0.70;
            default:          return 0.40;
        }
    }

    /** 相位 → 股权风险溢价加项（用于折现率；危机/萧条抬升 → 估值下压 → 抛售）。 */
    public static double riskPremium(Phase phase) {
        switch (phase) {
            case EXPANSION:   return 0.00;
            case PEAK:        return 0.005;
            case CONTRACTION: return 0.03;
            default:          return 0.06;
        }
    }

    /** 信贷过热 → 杠杆堆积倾向（明斯基：经济繁荣 + 信贷高速扩张时投机性加杠杆）。结果钳到 [0,1]。 */
    public static double creditOverheat(double economicHealth, double creditGrowth) {
        double boomBias = Math.max(0.0, (economicHealth - 0.1) / 0.6);      // 0.1~0.7 → 0~1
        double creditBias = Math.max(0.0, Math.min(1.0, creditGrowth));
        return Math.min(1.0, 0.5 * boomBias + 0.5 * creditBias);
    }

    public static String phaseName(Phase phase) {
        switch (phase) {
            case EXPANSION:   return "扩张";
            case PEAK:        return "顶部";
            case CONTRACTION: return "收缩";
            default:          return "萧条";
        }
    }
}
