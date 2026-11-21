package com.example.financialmarket.engine.capm;

import com.example.financialmarket.engine.crisis.EconomicCycle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * CAPM 资产定价模型（CAPM_SPEC §5 P-B，agent-dev，开关 cm.capm）。
 *
 * <p>模型内容（全部确定性纯函数、零随机——CAPM_SPEC §6）：
 * <ul>
 *   <li><b>每日收盘对齐环</b>：每资产交易日收盘追加 (指数收盘, 资产收盘) 对齐对，FIFO 上限
 *       {@link #CAPM_MAX_OBS}（90）。缺测日不追加，保证窗口内对齐对一一对应（§5.1/§5.3）。</li>
 *   <li><b>β 回归</b>：资产对数收益 ~ 指数对数收益（OLS，见 {@link OlsRegression}）。样本（收益对数）
 *       不足 {@link #CAPM_MIN_OBS}（20）或指数收益零方差 → 回落行业先验 {@link #industryBetaOf}；
 *       否则 β = w·β̂ + (1−w)·β_prior，w = min(1, n/MIN_BETA_OBS)；β 钳制 [0.05, 4]（§5.1）。</li>
 *   <li><b>R_f / ERP / ke</b>：R_f = 宏观利率（{@code MacroEnvironment.getInterestRate()}，由引擎侧读入）；
 *       ERP = clamp(0.045 + 周期 riskPremium + 危机张力×0.25, 0.02, 0.12)（§5.1 常量表）；
 *       ke = clamp(R_f + β·ERP, KE_MIN=0.02, KE_MAX=0.50)。</li>
 * </ul>
 *
 * <p>消费端（CM-5）：MarketEngine 季度 updateAll 之后、读档恢复之后，对每个 AI 公司以
 * {@code ke = costOfEquity(...)} 调 {@code AICompanyData.updateFairValue(ke)}（DCF 折现率 = ke），
 * 高 β 公司要求更高预期收益、估值对宏观利率/风险溢价敏感。
 *
 * <p>持久化（§5.3/§7.1）：每资产收盘对随 "capm" blob 保存；load 每层 contains 兜底，旧档缺字段 →
 * 空环 + 行业先验自举，读档不失败。H2 整块 blob，无表变更。
 */
public class CapmModel {
    /** 对齐环最大长度（交易日数；FIFO，超限丢最旧）。 */
    public static final int CAPM_MAX_OBS = 90;
    /** β 回归最小收益样本数（MIN_BETA_OBS）；样本不足 → 行业先验。 */
    public static final int CAPM_MIN_OBS = 20;
    /** β 钳制下/上限（确定性，防发散/退化）。 */
    public static final double BETA_MIN = 0.05;
    public static final double BETA_MAX = 4.0;
    /** ke 钳制（股权成本硬域）。 */
    public static final double KE_MIN = 0.02;
    public static final double KE_MAX = 0.50;
    /** ERP 常量：常态 4.5% + 周期加项（EconomicCycle.riskPremium）+ 危机张力×0.25，钳制 [2%, 12%]。 */
    public static final double ERP_BASE = 0.045;
    public static final double ERP_CRISIS_WEIGHT = 0.25;
    public static final double ERP_MIN = 0.02;
    public static final double ERP_MAX = 0.12;

    /** 每资产对齐收盘环：assetId → deque of double[]{indexClose, assetClose}（时间序，最旧在前）。 */
    private final Map<String, Deque<double[]>> closeRings = new HashMap<>();

    /**
     * 交易日收盘追加对齐对（指数收盘, 资产收盘）。确定性、零 rng；非法输入（NaN/Inf/≤0）被忽略
     * （缺测跳日不追加，CAPM_SPEC §5.1）。
     */
    public synchronized void recordDailyClose(String assetId, double indexClose, double assetClose) {
        if (assetId == null || assetId.isEmpty()) return;
        if (!Double.isFinite(indexClose) || indexClose <= 0) return;
        if (!Double.isFinite(assetClose) || assetClose <= 0) return;
        Deque<double[]> ring = closeRings.computeIfAbsent(assetId, k -> new ArrayDeque<>());
        ring.addLast(new double[]{indexClose, assetClose});
        while (ring.size() > CAPM_MAX_OBS) {
            ring.removeFirst();
        }
    }

    /** 当前对齐环大小（交易日数；0 = 尚无收盘样本）。 */
    public synchronized int windowSize(String assetId) {
        Deque<double[]> ring = closeRings.get(assetId);
        return ring == null ? 0 : ring.size();
    }

    /**
     * 只读导出某资产的对齐收盘环（防御性拷贝，不暴露内部可变结构）——供验证探针独立 OLS 交叉复算
     * （CM-4d：探针以同收盘对独立计算 β̂，与模型 |Δ| &lt; 1e-6）。返回 double[]{indexClose, assetClose} 列表
     * （时间序，最旧在前）；无数据 → 空列表。确定性、无随机。
     */
    public synchronized java.util.List<double[]> closesOf(String assetId) {
        Deque<double[]> ring = closeRings.get(assetId);
        if (ring == null || ring.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<double[]> copy = new java.util.ArrayList<>(ring.size());
        for (double[] p : ring) {
            copy.add(new double[]{p[0], p[1]});
        }
        return copy;
    }

    /**
     * 行业先验 β 常量表（CAPM_SPEC §5.1）：FINANCE 0.85 / TECH·MAGIC 1.30 / MINING·ENERGY 1.15 /
     * AGRICULTURE·CONSTRUCTION·LOGISTICS 0.90 / PLAYER 及未知 1.00。确定性、无随机。
     */
    public static double industryBetaOf(String industry) {
        if (industry == null) return 1.00;
        switch (industry.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "FINANCE":
                return 0.85;
            case "TECH":
            case "MAGIC":
                return 1.30;
            case "MINING":
            case "ENERGY":
                return 1.15;
            case "AGRICULTURE":
            case "CONSTRUCTION":
            case "LOGISTICS":
                return 0.90;
            default:
                return 1.00; // PLAYER / 未知
        }
    }

    /** 数值钳制 [lo, hi]。 */
    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * ERP（市场风险溢价）派生（确定性）：ERP = clamp(ERP_BASE + 周期 riskPremium(phase) +
     * crisisTension×ERP_CRISIS_WEIGHT, ERP_MIN, ERP_MAX)（CAPM_SPEC §5.1）。
     * phase/crisisTension 读 {@code MacroEnvironment.getCyclePhase()/getCrisisTension()}（引擎侧传入）。
     */
    public static double marketRiskPremium(EconomicCycle.Phase phase, double crisisTension) {
        double cyclePremium = phase != null ? EconomicCycle.riskPremium(phase) : 0.0;
        double tension = Double.isFinite(crisisTension) ? Math.max(0.0, crisisTension) : 0.0;
        return clamp(ERP_BASE + cyclePremium + tension * ERP_CRISIS_WEIGHT, ERP_MIN, ERP_MAX);
    }

    /**
     * ★CB-t5（CENTRAL_BANK_SPEC §8.2 / §7.2 C3 / CB-12）：**只加项**版本 —— 在既有 ERP 之上叠加
     * Q6 财政赤字货币化惩罚 {@code fiscalDominancePremium}，再按原区间钳制。
     *
     * <p><b>公式零改动纪律（N3）</b>：本方法**不修改**既有两参 {@link #marketRiskPremium(EconomicCycle.Phase, double)}
     * 的任何常量与算式；当 {@code fiscalDominancePremium == 0} 时（默认档，
     * {@code centralBank.fiscalDominance=false} ⇒ 央行侧 premium 恒 0）本方法与两参版**逐位相等**。
     * 财政府债（fiscalDominance）关闭时不生效 ⇒ CAPM 侧已交付的 CM-* 判据不受影响。
     *
     * @param fiscalDominancePremium Q6 惩罚加项（央行 {@code getErpFiscalDominancePremium()}；
     *                               非有限或负值按 0 处理，防污染既有值域）
     */
    public static double marketRiskPremium(EconomicCycle.Phase phase, double crisisTension,
                                           double fiscalDominancePremium) {
        double penalty = Double.isFinite(fiscalDominancePremium) ? Math.max(0.0, fiscalDominancePremium) : 0.0;
        if (penalty == 0.0) {
            return marketRiskPremium(phase, crisisTension);   // ★逐位等价锚（N3）：默认 0 加项 ⇒ 与两参版逐位一致
        }
        return clamp(marketRiskPremiumUnclamped(phase, crisisTension) + penalty, ERP_MIN, ERP_MAX);
    }

    /** 既有 ERP 的**钳制前**内层算式（仅供只加项复用；两参版语义不变）。 */
    private static double marketRiskPremiumUnclamped(EconomicCycle.Phase phase, double crisisTension) {
        double cyclePremium = phase != null ? EconomicCycle.riskPremium(phase) : 0.0;
        double tension = Double.isFinite(crisisTension) ? Math.max(0.0, crisisTension) : 0.0;
        return ERP_BASE + cyclePremium + tension * ERP_CRISIS_WEIGHT;
    }

    /**
     * ke（股权成本）纯函数：ke = clamp(R_f + β·ERP, KE_MIN, KE_MAX)。供手算比对（CM-4b）与内部复用。
     */
    public static double keOf(double rf, double beta, double erp) {
        return clamp(rf + beta * erp, KE_MIN, KE_MAX);
    }

    /**
     * β（资产对市场组合的敏感度，确定性 OLS）：样本不足（&lt;CAPM_MIN_OBS 收益对数）或指数收益零方差 →
     * 回落行业先验 {@link #industryBetaOf}；否则按 §5.1 公式先验收缩后钳制 [BETA_MIN, BETA_MAX]。
     */
    public synchronized double betaOf(String assetId, String industry) {
        double prior = clamp(industryBetaOf(industry), BETA_MIN, BETA_MAX);
        Deque<double[]> ring = closeRings.get(assetId);
        if (ring == null || ring.size() < 2) {
            return prior;
        }
        int n = ring.size() - 1; // 收益对数样本数
        if (n < CAPM_MIN_OBS) {
            return prior; // 样本不足 → 不回归（§5.1）
        }
        double[] x = new double[n];
        double[] y = new double[n];
        java.util.Iterator<double[]> it = ring.iterator();
        double[] prev = it.next();
        int idx = 0;
        while (it.hasNext()) {
            double[] cur = it.next();
            double ix = prev[0];
            double iy = prev[1];
            double cx = cur[0];
            double cy = cur[1];
            if (ix <= 0 || iy <= 0 || cx <= 0 || cy <= 0
                    || !Double.isFinite(ix) || !Double.isFinite(iy)
                    || !Double.isFinite(cx) || !Double.isFinite(cy)) {
                return prior; // 防御：非法收盘 → 回落先验（理论不可达，记录端已护栏）
            }
            x[idx] = Math.log(cx / ix); // 指数对数收益
            y[idx] = Math.log(cy / iy); // 资产对数收益
            idx++;
            prev = cur;
        }
        OlsRegression.Result r = OlsRegression.ols(x, y);
        if (r == null) {
            return prior; // 指数收益零方差/退化 → 不回归（§5.1）
        }
        double w = Math.min(1.0, n / (double) CAPM_MIN_OBS);
        double beta = w * r.beta + (1.0 - w) * prior;
        return clamp(beta, BETA_MIN, BETA_MAX);
    }

    /**
     * 该资产当期股权成本 ke = clamp(R_f + β·ERP, KE_MIN, KE_MAX)（CM-4b/CM-5）。
     * R_f / ERP 由引擎侧从宏观层读出（rf = getInterestRate()，erp = {@link #marketRiskPremium}）。
     */
    public synchronized double costOfEquity(String assetId, String industry, double rf, double erp) {
        double beta = betaOf(assetId, industry);
        return keOf(rf, beta, erp);
    }

    // ================= 持久化（§5.3/§7.1；load 全层 contains 兜底，读档不失败） =================

    /** 序列化：每资产收盘环（对齐对 indexClose/assetClose，时间序）。随 MarketEngine "capm" blob 保存。 */
    public synchronized CompoundTag save() {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<String, Deque<double[]>> e : closeRings.entrySet()) {
            ListTag list = new ListTag();
            for (double[] pair : e.getValue()) {
                CompoundTag ct = new CompoundTag();
                ct.putDouble("i", pair[0]);
                ct.putDouble("a", pair[1]);
                list.add(ct);
            }
            root.put(e.getKey(), list);
        }
        return root;
    }

    /** 读档恢复收盘环；旧档缺字段/空 tag → 空环 + 行业先验自举（读档不失败，CM-6）。 */
    public synchronized void load(CompoundTag tag) {
        closeRings.clear();
        if (tag == null || tag.isEmpty()) return;
        for (String key : tag.getAllKeys()) {
            ListTag list = tag.getList(key, Tag.TAG_COMPOUND);
            if (list == null || list.isEmpty()) continue;
            Deque<double[]> ring = new ArrayDeque<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag ct = list.getCompound(i);
                double ix = ct.getDouble("i");
                double ay = ct.getDouble("a");
                if (Double.isFinite(ix) && ix > 0 && Double.isFinite(ay) && ay > 0) {
                    ring.addLast(new double[]{ix, ay});
                }
            }
            while (ring.size() > CAPM_MAX_OBS) {
                ring.removeFirst();
            }
            if (!ring.isEmpty()) {
                closeRings.put(key, ring);
            }
        }
    }
}
