package com.example.financialmarket.engine.microstructure;

import com.example.financialmarket.engine.calibration.CalibrationParams;
import com.example.financialmarket.engine.random.SeedableRandom;

/**
 * P2-3 价格冲击 / 市场深度曲线模型（F3 修复：按 P2_SPEC §4 抽离独立类，参数接校准先验）。
 *
 * <p>价格冲击函数（平方根法则）：{@code I = sign·(k_sqrt·√(s/d₀) + k_linear·(s/d₀))·mid}，
 * 其中 s 为累计吃单量、d₀ 为最优档被动深度。小单冲击小（s/d₀→0）、大单冲击随 √(s/d₀) 次线性
 * 增长——大单 vs 小单冲击量级差异显著（A2-4）。
 *
 * <p>深度曲线：{@code depth_k = depth0 · (k+1)^(−depthDecayExponent)}，供 walk-the-book 与深度
 * 分布验证对齐 {@code CalibrationParams.depthDecayExponent}；波动聚集目标
 * {@code volClusteringPersistence}（α+β≈0.95）供验证器对齐 GARCH 持久性。
 *
 * <p>参数全部取自 {@link CalibrationParams} 校准先验（不硬编码）；{@code residualNoise=0} 时
 * 纯确定性（不消耗随机），同 engineSeed 完全可复现。
 */
public class MarketImpact {
    private final double kSqrt;              // 平方根冲击系数（校准先验，默认 0.0015）
    private final double kLinear;            // 线性冲击系数（校准先验，默认 0.0005）
    private final int maxDepthLevels;        // 深度曲线上限档位（保护，非硬性，默认 5）
    private final double depthDecayExponent; // 深度衰减幂律指数（CalibrationParams，默认 0.7）
    private final double volClusteringPersistence; // GARCH 波动聚集目标（CalibrationParams，默认 0.95）
    private final double residualNoise;      // 残余可复现噪声比例（默认 0 → 纯确定性）
    // MarS 移植（MP-2）：平方根冲击系数 α（Δ = α·σ·√(Q/V)），取自 CalibrationParams 先验（默认 0.01）。
    private final double marSAlpha;
    private final SeedableRandom rng;        // seed = mix64(engineSeed ^ "MarketImpact".hashCode())

    // ===== DG-2 Almgren-Chriss 冲击成本模型先验（DATA_GUARD_SPEC §3.1，全部取自 CalibrationParams） =====
    // ΔP_AC(Q) = λ_eff·(|Q|/Q0)^γ·mid（无方向，调用方按 sign(isBuy) 加符号）
    // λ_eff = λ_base·λ_liq(D_ref(Q))、λ_liq = clamp(D_ref/REF_DEPTH_SHARES, LIQ_MULT_MIN, LIQ_MULT_MAX)、
    // D_ref = max(min(|Q|, Q_MAX_REF), Q0_FLOOR)、γ = GAMMA_MIN+(GAMMA_MAX−GAMMA_MIN)·sat((Q/ADV)/GAMMA_RATIO_REF)。
    // 纯函数、零随机：本组参数不消费任何随机源；dataGuard.almgrenChriss=false 时调用方零调用（因子隔离）。
    private final double acLambdaBase;      // λ_base（先验 0.05）
    private final double acGammaMin;        // γ_min（先验 0.5）
    private final double acGammaMax;        // γ_max（先验 0.7）
    private final double acGammaRatioRef;   // γ 饱和参考量占比 Q/ADV（先验 2.0）
    private final double acQ0Floor;         // Q0_FLOOR（先验 1.0e4 股）
    private final double acRefDepthShares;  // REF_DEPTH_SHARES（先验 1.0e6 股）
    private final double acQMaxRef;         // Q_MAX_REF（先验 2.0e6 股）
    private final double acLiqMultMin;      // LIQ_MULT_MIN（先验 0.05）
    private final double acLiqMultMax;      // LIQ_MULT_MAX（先验 20.0）
    private final long engineSeed;          // 引擎种子（只读留档，便于探针复现）

    public MarketImpact(long engineSeed) {
        this(engineSeed, null);
    }

    public MarketImpact(long engineSeed, CalibrationParams cal) {
        this.engineSeed = engineSeed;
        this.rng = new SeedableRandom(SeedableRandom.mix64(engineSeed ^ "MarketImpact".hashCode()));
        CalibrationParams c = cal != null ? cal : CalibrationParams.DEFAULT;
        this.kSqrt = 0.0015;
        this.kLinear = 0.0005;
        this.maxDepthLevels = 5;
        this.depthDecayExponent = c.getDepthDecayExponent();
        this.volClusteringPersistence = c.getVolClusteringPersistence();
        this.residualNoise = 0.0;
        this.marSAlpha = c.getMarSAlpha();
        this.acLambdaBase = c.getAcLambdaBase();
        this.acGammaMin = c.getAcGammaMin();
        this.acGammaMax = c.getAcGammaMax();
        this.acGammaRatioRef = c.getAcGammaRatioRef();
        this.acQ0Floor = c.getAcQ0Floor();
        this.acRefDepthShares = c.getAcRefDepthShares();
        this.acQMaxRef = c.getAcQMaxRef();
        this.acLiqMultMin = c.getAcLiqMultMin();
        this.acLiqMultMax = c.getAcLiqMultMax();
    }

    // DG-2 ★可观测面（DATA_GUARD_SPEC §3.1）：最近一次 AC 计算的自变量/中间量，供探针独立复算。
    // 运行期瞬态、不落盘；纯记录（不参与任何价格计算）→ 零行为影响、零随机。
    private double lastGamma = 0.0;
    private double lastLambda = 0.0;
    private double lastAcRatio = 0.0;

    /** 价格冲击：相对 mid 的冲击量 = sign·(k_sqrt·√(s/d₀) + k_linear·(s/d₀))·mid。 */
    public double impact(double cumulativeSize, double depth0, double midPrice, boolean isBuy) {
        double ratio = depth0 > 0 ? cumulativeSize / depth0 : 0.0;
        double shock = kSqrt * Math.sqrt(ratio) + kLinear * ratio;
        double noise = 0.0;
        if (residualNoise > 0) {
            noise = (rng.nextDouble() - 0.5) * residualNoise;
        }
        double amount = (shock + noise) * midPrice;
        return isBuy ? amount : -amount;
    }

    /**
     * MarS 市场影响模型（MP-2，MarS ICLR'25 / arxiv 2409.07486）：{@code Δ = α·σ·√(Q/V)}。
     *
     * <p>下单量 Q 对价格的影响与<b>资产波动率 σ</b>（GARCH 条件波动，PriceDiscoveryEngine.getAssetVolatility）
     * 及<b>市场成交量 V</b>（滚动 ADV，VolumeTracker）相关：Q↑ → 冲击↑（√ 次线性，qty×4 → Δ×2）；
     * σ↑ → 冲击↑；V↑ → 冲击↓（深度越厚越能吸收）。residualNoise=0 时纯确定性（零随机，不消耗任何随机源）。
     *
     * <p>保留既有 {@link #impact} 向后兼容：本方法是<b>叠加</b>项（消费方把两者相加），不修改深度冲击公式。
     *
     * @param qty      下单量 Q
     * @param sigma    资产波动率 σ（GARCH 条件波动，>0 才有影响）
     * @param volume   市场成交量 V（滚动 ADV，>0 才有影响）
     * @param midPrice 锚价（mid）
     * @param isBuy    是否买单（买→正冲击推高价格，卖→负冲击压低价格）
     * @return 价格冲击量（已乘 midPrice 的绝对金额，带方向符号）
     */
    public double impactMarS(double qty, double sigma, double volume, double midPrice, boolean isBuy) {
        double ratio = volume > 0 ? qty / volume : 0.0;
        double shock = marSAlpha * sigma * Math.sqrt(ratio);   // Δ = α·σ·√(Q/V)
        double amount = shock * midPrice;
        return isBuy ? amount : -amount;
    }

    /** MarS 平方根冲击系数 α（CalibrationParams 先验，默认 0.01）。 */
    public double getMarSAlpha() {
        return marSAlpha;
    }

    // ================= DG-2 Almgren-Chriss 冲击成本模型（DATA_GUARD_SPEC §3.1/§3.2，纯函数、零随机） =================

    /**
     * DG-2 AC 冲击指数 γ ∈ [0.5, 0.7]（确定性、零随机）：
     * {@code γ = γ_min + (γ_max−γ_min)·sat((Q/ADV)/GAMMA_RATIO_REF)}，{@code sat(x)=min(1,max(0,x))}。
     *
     * <p>语义 = 「越薄越陡」：订单相对流动性（Q/ADV）越大 → γ 越接近上界 0.7（冲击更超线性）。
     *
     * <p><b>ADV≤0 回落 γ_max（t15 修正，原为实现回落 γ_min=0.5）</b>：{@code adv≤0}
     * （冷启动 / 新存档首日无成交量历史）时 {@code Q/ADV → +∞ ⇒ sat → 1 ⇒ γ → γ_max}，
     * 这正是本公式在 {@code ADV→0} 的**数学极限**，也与「市场越薄 → 冲击越陡」的意图一致
     * （与用户诉求"冷启动巨额卖单必须能砸动价格"同向）。此前回落 γ_min=0.5 反而给最薄的市场
     * 配了**最软**的指数，与规格意图相反。
     * 注：{@code λ} 的 F-AC-3 兜底路径**不受本次修正影响**（见 {@link #lambdaAC}）。
     *
     * @param qty 主动单量 Q（股，取绝对值）
     * @param adv 市场滚动 ADV（股；≤0 = 无 ADV 信息 → 回落 γ_max）
     * @return 冲击指数 γ ∈ [acGammaMin, acGammaMax]
     */
    public double gammaAC(double qty, double adv) {
        if (!(adv > 0) || !Double.isFinite(adv)) return acGammaMax;
        double liquidityRatio = Math.abs(qty) / adv;
        double sat = Math.min(1.0, Math.max(0.0, liquidityRatio / acGammaRatioRef));
        return acGammaMin + (acGammaMax - acGammaMin) * sat;
    }

    /**
     * DG-2 有效流动性乘子 {@code λ_eff = λ_base·λ_liq(D_ref(Q))}（确定性、零随机）。
     *
     * <p><b>F-AC-3（DATA_GUARD_SPEC v2 §3.2，V 缺失兜底）</b>：{@code ADV≤0}（冷启动/新存档首日无成交量
     * 历史）时 {@code D_ref = max(min(|Q|, Q_MAX_REF), Q0_FLOOR)}——**订单自身即深度探针**
     * （λ_liq 随订单量放大），保证此时刻 AC 项**非零**（基线在该时刻恰好整项为 0）。
     * {@code ADV>0} 时 {@code D_ref = max(min(|Q|, Q_MAX_REF), Q0_FLOOR)}。
     *
     * <p>两种情形下 D_ref 同式，故 {@code λ_eff} 只依赖订单量 |Q|；本方法签名以 {@code (qty, adv)} 表述，
     * {@code adv} 语义 = 「是否为有 ADV 信息的市况」（决定 {@link #gammaAC} 取值：
     * {@code ADV>0} 走自适应 [γ_min, γ_max]，{@code ADV≤0} 回落 γ_max —— t15 修正）。
     *
     * <p>副作用（★可观测面，§3.1）：记录 {@code lastLambda/lastGamma}（纯记录，不参与价格计算）。
     *
     * @param qty 主动单量 Q（股）
     * @param adv 市场滚动 ADV（股；≤0 = 无信息）
     * @return λ_eff（无量纲比例系数）
     */
    public double lambdaAC(double qty, double adv) {
        double dRef = Math.max(Math.min(Math.abs(qty), acQMaxRef), acQ0Floor);
        double liqMult = Math.min(acLiqMultMax, Math.max(acLiqMultMin, dRef / acRefDepthShares));
        double gamma = gammaAC(qty, adv);
        this.lastGamma = gamma;
        this.lastLambda = acLambdaBase * liqMult;
        return this.lastLambda;
    }

    /** DG-2 兼容重载（v1 三参签名）：displayedDepth 不参与 λ 推导（v2 起），仅为调用方兼容保留。 */
    public double lambdaAC(double qty, double adv, double displayedDepth) {
        return lambdaAC(qty, adv);
    }

    /**
     * DG-2 相对冲击比例 {@code λ_eff·(|Q|/Q0)^γ}（无量纲，供验证探针直接断言单调性/次线性，§8 DG-2a）。
     *
     * <p>归一化 {@code Q0 = max(Q0_FLOOR, displayedDepth)}（保证 γ 非整数时不对「股数」直接取幂的量纲正确）。
     *
     * <p>副作用（★可观测面）：记录 {@code lastAcRatio}（纯记录）。
     *
     * @param qty            主动单量 Q（股）
     * @param adv            市场滚动 ADV（股）
     * @param displayedDepth 被动侧显示深度 d₀（股）
     * @return 相对价格冲击比例（≥0，无方向）
     */
    public double acImpactRatio(double qty, double adv, double displayedDepth) {
        double d0 = displayedDepth > 0 && Double.isFinite(displayedDepth) ? displayedDepth : 0.0;
        double q0 = Math.max(acQ0Floor, d0);
        double ratio = Math.max(0.0, Math.abs(qty)) / q0;
        if (ratio <= 0) {
            this.lastAcRatio = 0.0;
            return 0.0;
        }
        double r = lambdaAC(qty, adv) * Math.pow(ratio, this.lastGamma);
        this.lastAcRatio = r;
        return r;
    }

    /**
     * DG-2 AC 冲击项（金额，带方向符号）：{@code ΔP_AC = sign·λ_eff·(|Q|/Q0)^γ·mid}。
     *
     * <p>与既有 {@link #impact}（深度曲线冲击）与 {@link #impactMarS}（Δ=α·σ·√(Q/V)）**同符号相加**
     * （融合方式：调用方把三项相加，本方法不改既有两项的公式与数值）。
     *
     * <p><b>关键语义（根因修复 F-AC-1）</b>：调用方必须传入**未成交的主动单剩余量**（aggRemaining）
     * 而非已成交量（cumQty）——剩余巨量一直在驱动 AC 项把价格一路推下去，直到被涨跌停带钳住，
     * 从而根除「巨额卖单压顶但价格死锁」。
     *
     * @param qty            主动单剩余量 Q（股）
     * @param adv            市场滚动 ADV（股；≤0 走 F-AC-3 兜底使本项非零）
     * @param displayedDepth 被动侧显示深度 d₀（股）
     * @param midPrice       锚价 mid（>0）
     * @param isBuy          买主动 → 正冲击（推高）；卖主动 → 负冲击（压低）
     * @return 冲击金额（已乘 midPrice，带方向符号）；入参非法时返回 0.0
     */
    public double impactAC(double qty, double adv, double displayedDepth, double midPrice, boolean isBuy) {
        if (!(midPrice > 0) || !Double.isFinite(midPrice)) return 0.0;
        double amount = acImpactRatio(qty, adv, displayedDepth) * midPrice;
        if (!Double.isFinite(amount)) return 0.0;
        return isBuy ? amount : -amount;
    }

    /** DG-2：λ_base（CalibrationParams 先验，默认 0.05；允许区间 [0.02, 0.20]）。 */
    public double getAcLambdaBase() { return acLambdaBase; }
    /** DG-2：γ 下界（先验 0.5）。 */
    public double getAcGammaMin() { return acGammaMin; }
    /** DG-2：γ 上界（先验 0.7）。 */
    public double getAcGammaMax() { return acGammaMax; }
    /** DG-2：γ 饱和参考量占比（先验 2.0）。 */
    public double getAcGammaRatioRef() { return acGammaRatioRef; }
    /** DG-2：归一化尺度地板 Q0_FLOOR（股，先验 1.0e4）。 */
    public double getAcQ0Floor() { return acQ0Floor; }
    /** DG-2：参考深度 REF_DEPTH_SHARES（股，先验 1.0e6）。 */
    public double getAcRefDepthShares() { return acRefDepthShares; }
    /** DG-2：订单深度探针封顶 Q_MAX_REF（股，先验 2.0e6）。 */
    public double getAcQMaxRef() { return acQMaxRef; }
    /** DG-2：流动性乘子下界（先验 0.05）。 */
    public double getAcLiqMultMin() { return acLiqMultMin; }
    /** DG-2：流动性乘子上界（先验 20.0）。 */
    public double getAcLiqMultMax() { return acLiqMultMax; }

    // ---- DG-2 既有分量只读对照（§6.2-B 不回归锚点：常量类型/取值不得变动） ----
    /** 既有深度冲击平方根系数 k_sqrt（0.0015，零改动）。 */
    public double getKSqrt() { return kSqrt; }
    /** 既有深度冲击线性系数 k_linear（0.0005，零改动）。 */
    public double getKLinear() { return kLinear; }
    /** 既有残余可复现噪声比例（0.0 → 纯确定性，零改动）。 */
    public double getResidualNoise() { return residualNoise; }
    /** 既有深度曲线上限档位数（5，零改动；AC 开启时 walkBook 用 AC_MAX_LEVELS=20）。 */
    public int getMaxDepthLevels() { return maxDepthLevels; }
    /** 引擎种子（只读留档）。 */
    public long getEngineSeed() { return engineSeed; }

    // ---- DG-2 ★可观测面：最近一次 AC 计算的自变量/中间量（供探针独立复算） ----
    /** 最近一次 {@link #gammaAC} 调用得出的冲击指数 γ（∈[0.5,0.7]）。 */
    public double getLastGamma() { return lastGamma; }
    /** 最近一次 {@link #lambdaAC} 调用得出的有效流动性乘子 λ_eff。 */
    public double getLastLambda() { return lastLambda; }
    /** 最近一次 {@link #acImpactRatio} 调用得出的相对冲击比例 ΔP_AC/mid。 */
    public double getLastAcRatio() { return lastAcRatio; }

    /** 深度曲线第 levelIndex 档（0-based）的深度：depth0 · (levelIndex+1)^(−depthDecayExponent)。 */
    public double levelDepth(int levelIndex, double depth0) {
        return depth0 * Math.pow(levelIndex + 1.0, -depthDecayExponent);
    }

    /** 波动聚集目标（α+β），供验证器对齐 GARCH 持久性。 */
    public double getVolClusteringPersistence() {
        return volClusteringPersistence;
    }

    /** 深度衰减幂律指数（校准先验）。 */
    public double getDepthDecayExponent() {
        return depthDecayExponent;
    }
}
