package com.example.financialmarket.engine.macro;

import com.example.financialmarket.engine.crisis.EconomicCycle;
import com.example.financialmarket.engine.random.SeedableRandom;

/**
 * L0 宏观环境引擎
 * 驱动利率(r)、政策不确定性(p)、恐慌指数(v) 的 OU 过程
 * 并提供环境向量 E(t) = {利率, 政策冲击, 恐慌, 流动性乘子}
 * 支持历史播种（冷启动）
 */
public class MacroEnvironment {
    // ---------- 泰勒规则约定（t3/SR-3，确定性常量） ----------
    public static final double RATE_MIN = 0.001;
    public static final double RATE_MAX = 0.20;
    public static final double INFLATION_TARGET = 0.02;
    public static final double NEUTRAL_RATE = 0.035;

    // 状态变量
    private double r;        // 利率
    private double p;        // 政策不确定性
    private double v;        // 恐慌指数 (VIX)
    private long lastUpdateTick;
    // P0-7：可播种 PRNG，使宏观过程可复现
    private final SeedableRandom random;

    // OU 参数
    private final double kappa_r = 0.15;
    private double r_bar = 0.035;       // 长期中性利率 3.5%（可由央行政策利率驱动）
    private final double sigma_r = 0.005;
    private double policyRate = 0.035;  // 央行政策利率（贴现率），驱动 r_bar
    private double reserveRequirement = 0.10; // 央行法定准备金率，影响流动性乘子

    private final double theta_p = 0.2;
    private final double sigma_p = 0.02;
    private final double lambda_p = 0.02;   // 跳跃强度 (每月约2次)
    private final double J_p_mean = 0.03;   // 政策冲击幅度

    private final double kappa_v = 0.3;
    private final double v_bar = 15.0;      // 长期 VIX 均值
    private final double sigma_v = 0.5;

    // 宏观因子对 GBM 的影响系数
    private final double alpha_0 = 0.08;
    private final double alpha_1 = -1.2;    // 利率对估值的影响 (负)
    private final double alpha_2 = -0.5;    // 政策不确定性的影响
    private final double alpha_3 = 0.02;    // ERP (股权风险溢价)
    private final double beta_0 = 0.20;
    private final double beta_1 = 0.008;    // VIX 对波动率的影响

    // 流动性乘子参数
    private final double r_neutral = 0.035;
    private final double kappa_m = 0.3;

    // ---------- 周期/危机 → 宏观环境向量 传导权重（确定性常量） ----------
    private static final double CYCLE_DRIFT_WEIGHT = 0.20;       // 相位 → 漂移偏置
    private static final double CRISIS_RISK_WEIGHT = 0.50;       // 危机 → 风险偏好收缩
    private static final double CRISIS_VOL_WEIGHT = 0.40;        // 危机 → 波动放大
    private static final double CRISIS_LIQUIDITY_WEIGHT = 0.40;  // 危机 → 流动性收紧
    private static final double CRISIS_DISCOUNT_WEIGHT = 0.25;   // 危机 → 折现率（股权风险溢价）抬升

    // 周期/危机传导状态（衍生量：每宏观步由 applyMacroTransmission 以确定性输入重算，无随机、不持久化）
    private EconomicCycle.Phase cyclePhase = EconomicCycle.Phase.EXPANSION;
    private double cycleRiskAppetiteBias = 0.0;
    private double cycleLeverageMultiplier = 1.0;   // >1 杠杆堆积，<1 强制去杠杆
    private double cycleVolatilityMultiplier = 1.0;
    private double cycleSpreadMultiplier = 1.0;
    private double cycleDepthMultiplier = 1.0;
    private double cycleRiskPremium = 0.0;
    private double creditOverheat = 0.0;            // 信贷过热（0..1，杠杆堆积倾向）
    private double crisisTension = 0.0;             // 危机张力（0..1）

    // 时间步长 (以 tick 为单位，假设 20 tick = 1 个宏观步)
    private static final double DT_MACRO = 20.0 / 24000.0; // 约 0.000833 年 (20 ticks)

    // ----- 冷启动种子数据 -----
    private boolean seeded = false;
    private double seed_r = 0.035;
    private double seed_p = 0.02;
    private double seed_v = 18.0;

    public MacroEnvironment() {
        this(0L);
    }

    /** P0-7：以指定种子构造（engineSeed 派生子流），使宏观 OU 过程可复现。 */
    public MacroEnvironment(long seed) {
        // 初始化中性状态
        this.r = r_bar;
        this.p = 0.0;
        this.v = v_bar;
        this.lastUpdateTick = 0;
        this.random = new SeedableRandom(SeedableRandom.mix64(seed));
    }

    /**
     * 加载种子数据（在模组初始化时调用）
     * @param seedR 初始利率
     * @param seedP 初始政策不确定性
     * @param seedV 初始恐慌指数
     */
    public void loadSeedData(double seedR, double seedP, double seedV) {
        this.seed_r = seedR;
        this.seed_p = seedP;
        this.seed_v = seedV;
        this.r = seedR;
        this.p = seedP;
        this.v = seedV;
        this.seeded = true;
        System.out.println("🌱 宏观环境种子数据加载: r=" + seedR + ", p=" + seedP + ", v=" + seedV);
    }

    public boolean isSeeded() { return seeded; }

    /** 长期中性利率 r*（泰勒规则用）。 */
    public double getNeutralRate() { return r_neutral; }

    /**
     * 简化泰勒规则建议政策利率（t3/SR-3）：{@code i = r* + π + 0.5·(π − π*) + 0.5·y_gap}。
     * {@code y_gap} 用 {@code economicHealth − 1} 代理；结果钳制到硬域 {@code [RATE_MIN, RATE_MAX]}。
     * 纯确定性函数，无随机，同种子同输入同输出。
     */
    public static double taylorSuggestedRate(double inflationRate, double economicHealth) {
        return taylorSuggestedRate(NEUTRAL_RATE, inflationRate, economicHealth);
    }

    /** 同上，但允许显式指定中性利率 r*（默认用于央行政策建议）。 */
    public static double taylorSuggestedRate(double neutralRate, double inflationRate, double economicHealth) {
        double yGap = economicHealth - 1.0;
        double i = neutralRate + inflationRate + 0.5 * (inflationRate - INFLATION_TARGET) + 0.5 * yGap;
        return Math.max(RATE_MIN, Math.min(RATE_MAX, i));
    }

    /**
     * 每 20 tick 更新一次宏观状态 (L0)
     * @param currentTick 当前游戏 tick
     */
    public void step(long currentTick) {
        if (currentTick - lastUpdateTick < 20) return;
        lastUpdateTick = currentTick;

        // 1. 利率 OU（均值回归目标 = 央行政策利率，实现货币政策 → 宏观利率传导）
        double epsR = random.nextGaussian();
        double dr = kappa_r * (policyRate - r) * DT_MACRO + sigma_r * Math.sqrt(DT_MACRO) * epsR;
        r += dr;
        r = Math.max(0.001, Math.min(0.10, r)); // 限制 0.1% ~ 10%

        // 2. 政策不确定性 (跳跃扩散)
        double epsP = random.nextGaussian();
        double dp = -theta_p * p * DT_MACRO + sigma_p * Math.sqrt(DT_MACRO) * epsP;
        // 跳跃项
        if (random.nextDouble() < lambda_p * DT_MACRO) {
            double jump = random.nextGaussian() * J_p_mean;
            dp += jump;
        }
        p += dp;
        p = Math.max(0.0, Math.min(0.5, p)); // 政策不确定性 0~50%

        // 3. 恐慌指数 (CIR 过程, 平方根扩散)
        double epsV = random.nextGaussian();
        double dv = kappa_v * (v_bar - v) * DT_MACRO + sigma_v * Math.sqrt(Math.max(v, 0.1)) * Math.sqrt(DT_MACRO) * epsV;
        v += dv;
        v = Math.max(5.0, Math.min(80.0, v)); // VIX 5~80
    }

    // ========== 环境向量 E(t) ==========
    public double getInterestRate() { return r; }
    public double getPolicyUncertainty() { return p; }
    public double getPanicIndex() { return v; }

    /**
     * 计算动态漂移率 μ(t)
     *
     * <p>宏观基态之上叠加周期相位偏置与危机阻尼：繁荣期 drift 抬升、萧条/危机 drift 回落（甚至转负），
     * 使宏观共同模随时间产生「涨跌交替」的周期化路径（配合 agent 端 fairValue 差异化，市场有涨有跌）。
     */
    public double getDynamicDrift() {
        double base = alpha_0 + alpha_1 * r + alpha_2 * p + alpha_3;
        double cycleAdjusted = base + cycleRiskAppetiteBias * CYCLE_DRIFT_WEIGHT
                - crisisTension * CYCLE_DRIFT_WEIGHT;
        // 硬边界防发散（与既有钳制一致）
        return Math.max(-0.20, Math.min(0.20, cycleAdjusted));
    }

    /**
     * 计算动态波动率 σ(t)
     */
    public double getDynamicVolatility() {
        double sigma = beta_0 + beta_1 * v;
        sigma *= cycleVolatilityMultiplier;
        sigma *= (1.0 + crisisTension * CRISIS_VOL_WEIGHT);
        return Math.max(0.05, Math.min(0.85, sigma)); // 限制 5%~85%
    }

    /**
     * 流动性乘子 M(t)：利率偏离中性时收缩流动性；央行准备金率越高，信贷派生能力越低，流动性越紧。
     * 额外叠加周期（繁荣宽松、收缩/萧条收紧）与危机（去杠杆 → 收紧），形成明斯基「杠杆堆积→强制去杠杆」的流动性来源。
     */
    public double getLiquidityMultiplier() {
        double deviation = (r - r_neutral) / r_neutral;
        double rateLiquidity = Math.max(0.2, Math.min(1.0, 1.0 - kappa_m * deviation));
        // 准备金率传导：越高 → 存款派生越弱 → 流动性乘子越小
        double reserveFactor = Math.max(0.5, Math.min(1.0, 1.0 - reserveRequirement));
        double base = Math.max(0.2, Math.min(1.0, rateLiquidity * reserveFactor));
        // 周期：繁荣期信贷宽松（乘子抬升）、收缩/萧条期信贷收紧（乘子下压）
        double cycleLiquidity = cycleLeverageMultiplier; // >1 宽松，<1 收紧
        // 危机：强制去杠杆 → 进一步收紧流动性
        double crisisFactor = 1.0 - crisisTension * CRISIS_LIQUIDITY_WEIGHT;
        return Math.max(0.10, Math.min(1.0, base * cycleLiquidity * crisisFactor));
    }

    // ========== 货币政策传导入口（F5） ==========

    /** 央行政策利率（贴现率）入口：驱动宏观利率均值 r_bar，进而传导到流动性乘子/折现率/GBM 漂移。 */
    public void setPolicyRate(double rate) {
        // ★CB-t5（CENTRAL_BANK_SPEC §8.2 口径统一）：上界由 0.10 改为**不截断**（下界 RATE_MIN 保留）。
        // 理由：政策上限由央行自身 [POLICY_RATE_MIN, POLICY_RATE_MAX] 管（= [0.001, 0.20]）；
        // 旧实现把宏观 r 上限钳在 10% ⇒ 「政策利率 15% 但宏观 r 上限 10%」的口径打架。
        // 影响面登记：默认档 policyRate = discountRate 默认 0.03 < 0.10 ⇒ **默认行为逐位不变**（零回归）；
        // 仅当玩家/规则把政策利率提到 >10% 时，宏观侧不再截断（登记为口径统一，非数值回归）。
        this.policyRate = Math.max(RATE_MIN, rate);
        this.r_bar = this.policyRate;
    }

    /** 央行法定准备金率入口：影响流动性乘子（信贷派生能力）。 */
    public void setReserveRequirement(double req) {
        this.reserveRequirement = Math.max(0.01, Math.min(0.25, req));
    }

    public double getPolicyRate() { return policyRate; }
    public double getReserveRequirement() { return reserveRequirement; }

    /**
     * ★CB-t5（§8.2 / §7.2 C3）：供 R_f 选择使用的政策利率（不被宏观侧截断）。
     *
     * <p>与 {@link #getPolicyRate()} 同值，但**语义**是"央行政策利率的宏观侧视图"：
     * {@code MarketEngine.rfForValuation()} 在 {@code centralBank.rfPolicyRate=true} 时取
     * {@code max(CentralBank.getGovernmentBondYield(), CentralBank.getPolicyRate())} ——
     * 该式**优先取央行权威值**，本 getter 供探针/对照复算与宏观侧口径核对使用。
     */
    public double getPolicyRateForRf() { return policyRate; }

    /**
     * ★CB-t5（§9.5 C7 / §8.3⑨）：政策公告 → 宏观不确定性跳跃注入（确定性查表，无随机）。
     *
     * <p>跳跃幅度由 {@code CentralBank.policyEventJump(kind)} 查表给出（表归央行侧持有，引擎只投递）；
     * 与 {@code step()} 中既有的 OU 泊松跳跃项**并存**（规格要求"既有跳跃项保留"）。
     * {@code centralBank.events=false} 时引擎侧零投递 ⇒ {@code p} 序列与基线逐位一致（N2 扩展）。
     */
    public void injectPolicyEventJump(double jump) {
        if (!Double.isFinite(jump) || jump == 0.0) return;
        this.p += jump;
        this.p = Math.max(0.0, Math.min(0.5, this.p));   // 与 step() 的既有钳制一致
    }

    /**
     * 折现率 (用于基本面估值)
     *
     * <p>基态 + 周期股权风险溢价抬升（收缩/萧条 → 估值下压）+ 危机溢价，使「过热/衰退/危机」传导到 AI 估值与交易。
     */
    public double getDiscountRate() {
        return 0.04 + r + p * 0.2 + cycleRiskPremium + crisisTension * CRISIS_DISCOUNT_WEIGHT;
    }

    /**
     * 风险偏好因子 (0~1)
     *
     * <p>既有恐慌(V)驱动之上叠加周期相位（繁荣偏好高、萧条低）与危机（收缩），使风险偏好随金融周期/危机演化。
     */
    public double getRiskAppetite() {
        // 恐慌越高，风险偏好越低
        double raw = 1.0 - (v - 5.0) / 75.0;
        raw += cycleRiskAppetiteBias;
        raw -= crisisTension * CRISIS_RISK_WEIGHT;
        return Math.max(0.05, Math.min(1.0, raw));
    }

    // ========== 周期/危机 → 宏观环境向量 传导（MS-AI-6） ==========

    /**
     * 宏观层配合：把经济周期相位、信贷过热、危机等级折叠进环境向量 E(t)。
     * 每宏观步由 MarketEngine 调用（gameTime % 20），输入均为确定性量（economicHealth/creditGrowth/crisisLevel），
     * 纯函数、无随机，因此该状态可确定性重算、无需持久化（读档后自动以当期周期/危机重建）。
     *
     * <p>效果：繁荣/顶部（含信贷过热）agent 风险偏好抬升、杠杆堆积、价差收窄、深度增厚；
     * 收缩/萧条/危机 agent 强制去杠杆、风险偏好收缩、价差扩大、深度骤降、估值下压（明斯基闭环）。
     */
    public void applyMacroTransmission(double economicHealth, double creditGrowth, double crisisLevel) {
        EconomicCycle.Phase phase = EconomicCycle.phaseOf(economicHealth);
        this.cyclePhase = phase;
        this.cycleRiskAppetiteBias = EconomicCycle.riskAppetiteBias(phase);
        this.cycleLeverageMultiplier = EconomicCycle.leverageMultiplier(phase);
        this.cycleVolatilityMultiplier = EconomicCycle.volatilityMultiplier(phase);
        this.cycleSpreadMultiplier = EconomicCycle.spreadMultiplier(phase);
        this.cycleDepthMultiplier = EconomicCycle.depthMultiplier(phase);
        this.cycleRiskPremium = EconomicCycle.riskPremium(phase);
        this.creditOverheat = EconomicCycle.creditOverheat(economicHealth, creditGrowth);
        this.crisisTension = normalizeCrisis(crisisLevel);
    }

    /** 危机等级 → [0,1] 张力（确定性线性归一）。 */
    private static double normalizeCrisis(double crisisLevel) {
        if (crisisLevel <= 0) return 0.0;
        if (crisisLevel >= 12.0) return 1.0;
        return crisisLevel / 12.0;
    }

    // ---- 周期/危机 信号读取（供 agent 端/盘口明确消费：杠杆堆积→去杠杆、价差/深度、风险偏好） ----
    public EconomicCycle.Phase getCyclePhase() { return cyclePhase; }
    public double getCreditOverheat() { return creditOverheat; }
    public double getCrisisTension() { return crisisTension; }
    /** 周期杠杆偏好乘子（&gt;1 繁荣/顶部杠杆堆积）。 */
    public double getCycleLeverageMultiplier() { return cycleLeverageMultiplier; }
    /** 危机+去杠杆 → 强制降杠杆乘子（&lt;1）。 */
    public double getCrisisDeleverageMultiplier() {
        return Math.max(0.20, 1.0 - crisisTension * 0.60);
    }
    /** 危机/周期 → 价差扩大乘子（&gt;1）。 */
    public double getMarketSpreadMultiplier() {
        return cycleSpreadMultiplier * (1.0 + crisisTension * 0.60);
    }
    /** 危机/周期 → 盘口深度乘子（&lt;1 变薄）。 */
    public double getMarketDepthMultiplier() {
        return cycleDepthMultiplier * (1.0 - crisisTension * 0.40);
    }
    /** 周期叠加危机的综合风险偏好偏置（已折叠进 getRiskAppetite，此处供盘口/波动消费）。 */
    public double getCycleRiskAppetiteBias() { return cycleRiskAppetiteBias; }

    /**
     * 涨跌停限制 (根据宏观环境动态调整)
     */
    public double getLimitDownThreshold() {
        if (v > 40) return -0.05;  // 极端恐慌，跌停 5%
        if (v > 30) return -0.07;
        return -0.10; // 正常 10%
    }

    public double getLimitUpThreshold() {
        if (v > 40) return 0.05;
        if (v > 30) return 0.07;
        return 0.10;
    }

    // 熔断触发判定 (价格波动超过阈值)
    public boolean isCircuitBreakerTripped(double priceChange, double threshold) {
        return Math.abs(priceChange) > Math.abs(threshold) * 1.2;
    }
}