package com.example.financialmarket.engine.meso;

import com.example.financialmarket.engine.crisis.EconomicCycle;
import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MarS 中观层板块轮动确定性模型（MP-1 §3.2-4）。
 *
 * <p>输入：各板块 {@code sectorMomentum}（动量 EMA）、相对估值分位（sectorPrice/sectorFair 横截面
 * z-score）、{@code MacroSnapshot.cyclePhase}、{@code riskAppetite}；输出：板块目标权重偏移
 * （超配/低配），确定性。
 *
 * <p>轮动规则（代码常量，注释引用现实依据）：
 * <ul>
 *   <li>扩张/顶部（EXPANSION/PEAK）→ <strong>动量轮动</strong>：强者恒强，羊群式板块聚集
 *       （Jegadeesh &amp; Titman 1993 截面动量；板块同涨同跌的羊群聚集效应）；</li>
 *   <li>收缩/萧条（CONTRACTION/TROUGH）→ <strong>反转轮动</strong>：超跌板块低估值恢复
 *       （De Bondt &amp; Thaler 1985 长期反转）；</li>
 *   <li>轮动速度受 {@code riskAppetite} 调制：恐慌（riskAppetite↓）→ 轮动停滞/资金撤离（tilt→0）。</li>
 * </ul>
 *
 * <p>确定性：{@code SeedableRandom} 子流仅在板块注册时消费一次（每板块固定风格抖动 jitter，
 * 同种子可复现），tick 内为纯函数（同输入同输出）。jitter 与动量 EMA 一并持久化。
 */
public class SectorRotationModel {
    /** 轮动风格（相位 → 风格映射为纯函数 {@link #rotationStyle}）。 */
    public enum RotationStyle { MOMENTUM, REVERSAL }

    // ---- 代码常量（先验，不调参；§7 不校准约束） ----
    private static final double MOMENTUM_TILT = 0.30;    // 动量轮动最大超配/低配幅度（±30%）
    private static final double REVERSAL_TILT = 0.35;    // 反转轮动最大超配/低配幅度（±35%）
    private static final double ROTATION_SPEED_K = 0.6;  // 恐慌调制系数（riskAppetite → 轮动速度）
    private static final double JITTER_AMPLITUDE = 0.04; // 每板块确定性风格抖动（±2%）
    private static final double MOMENTUM_NORM = 0.02;    // 动量归一化尺度（±2% 板块收益 → tanh≈±1）
    private static final double VALUATION_NORM = 2.0;    // 估值 z-score 归一化尺度

    private final SeedableRandom rng;
    private final Map<String, Double> sectorJitter = new LinkedHashMap<>();
    private final Map<String, Double> sectorMomentum = new LinkedHashMap<>();   // 板块动量 EMA（持久化）
    private final Map<String, Double> prevSectorPrice = new LinkedHashMap<>();  // 上期板块价（动量计算）

    /** 种子 = mix64(engineSeed ^ "meso/rotation".hashCode())（§7.1-1 子流约定）。 */
    public SectorRotationModel(long engineSeed) {
        this.rng = new SeedableRandom(SeedableRandom.mix64(engineSeed ^ "meso/rotation".hashCode()));
    }

    /** 注册板块（确定性；每次注册消费一次 rng 子流 → 每板块固定风格抖动，同种子同序列）。 */
    public void registerSector(String industry) {
        sectorJitter.put(industry, (rng.nextDouble() - 0.5) * JITTER_AMPLITUDE);
        sectorMomentum.put(industry, 0.0);
    }

    public boolean hasSector(String industry) {
        return sectorMomentum.containsKey(industry);
    }

    /** 更新板块动量 EMA（每宏观步由 MesoLayerManager 以板块聚合价调用，确定性）。 */
    public void updateSectorMomentum(String industry, double sectorPrice) {
        double prev = prevSectorPrice.getOrDefault(industry, 0.0);
        if (prev > 0 && sectorPrice > 0) {
            double ret = (sectorPrice - prev) / prev;
            double m = sectorMomentum.getOrDefault(industry, 0.0);
            sectorMomentum.put(industry, m * 0.9 + ret * 0.1);
        }
        prevSectorPrice.put(industry, sectorPrice);
    }

    /**
     * 板块轮动偏移（纯函数）：返回 [-1,1]，&gt;0 超配（OVER）、&lt;0 低配（UNDER）。
     *
     * @param industry     板块名
     * @param momentum     板块动量（EMA）
     * @param valuationZ   板块相对估值 z-score（sectorPrice/sectorFair 横截面，&gt;0 相对高估）
     * @param phase        经济周期相位
     * @param riskAppetite 风险偏好 [0.05,1]
     */
    public double rotationOffset(String industry, double momentum, double valuationZ,
                                 EconomicCycle.Phase phase, double riskAppetite) {
        double tilt;
        switch (rotationStyle(phase)) {
            case MOMENTUM:
                // 扩张/顶部：强者恒强（羊群式板块聚集）→ 高动量板块超配
                tilt = MOMENTUM_TILT * Math.tanh(momentum / MOMENTUM_NORM);
                break;
            case REVERSAL:
            default:
                // 收缩/萧条：超跌板块估值修复 → 低估值（z<0）超配
                tilt = REVERSAL_TILT * Math.tanh(-valuationZ / VALUATION_NORM);
                break;
        }
        // 恐慌调制：riskAppetite↓ → 轮动停滞/资金撤离（tilt→0）；叠加固定风格抖动（确定性）
        double speed = Math.max(0.0, Math.min(1.0, ROTATION_SPEED_K * riskAppetite));
        double jitter = sectorJitter.getOrDefault(industry, 0.0);
        return clamp(tilt * speed + jitter, -1.0, 1.0);
    }

    /** 相位 → 轮动风格（纯函数，代码常量）：扩张/顶部动量轮动，收缩/萧条反转轮动。 */
    public static RotationStyle rotationStyle(EconomicCycle.Phase phase) {
        switch (phase) {
            case EXPANSION:
            case PEAK:
                return RotationStyle.MOMENTUM;
            case CONTRACTION:
            case TROUGH:
            default:
                return RotationStyle.REVERSAL;
        }
    }

    public double getSectorMomentum(String industry) {
        return sectorMomentum.getOrDefault(industry, 0.0);
    }

    /** 动量确定性视图（按行业名排序，禁 HashMap keySet 迭代，§7.1-3）。 */
    public Map<String, Double> momentumView() {
        Map<String, Double> out = new LinkedHashMap<>();
        List<String> sorted = new ArrayList<>(sectorMomentum.keySet());
        Collections.sort(sorted);
        for (String s : sorted) out.put(s, sectorMomentum.get(s));
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- 持久化（MP-5：轮动状态随 MarketEngine.save() 的 "meso" 字段；旧档缺字段 contains 兜底） ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag jitterList = new ListTag();
        for (Map.Entry<String, Double> e : sectorJitter.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("industry", e.getKey());
            c.putDouble("jitter", e.getValue());
            jitterList.add(c);
        }
        tag.put("jitter", jitterList);
        ListTag momList = new ListTag();
        for (Map.Entry<String, Double> e : sectorMomentum.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("industry", e.getKey());
            c.putDouble("momentum", e.getValue());
            momList.add(c);
        }
        tag.put("momentum", momList);
        ListTag pxList = new ListTag();
        for (Map.Entry<String, Double> e : prevSectorPrice.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("industry", e.getKey());
            c.putDouble("price", e.getValue());
            pxList.add(c);
        }
        tag.put("prevPrice", pxList);
        tag.putLong("rngState", rng.getState());
        return tag;
    }

    /** 恢复轮动状态；旧档缺字段用 contains 兜底（不覆盖已注册结构），读档不失败。 */
    public void load(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("jitter")) {
            ListTag l = tag.getList("jitter", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                // 结构已由 registerSector 建立；无 key 守卫直接覆盖（恢复的确定性高于默认 0）
                sectorJitter.put(c.getString("industry"), c.getDouble("jitter"));
            }
        }
        if (tag.contains("momentum")) {
            ListTag l = tag.getList("momentum", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                sectorMomentum.put(c.getString("industry"), c.getDouble("momentum"));
            }
        }
        if (tag.contains("prevPrice")) {
            ListTag l = tag.getList("prevPrice", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                // 关键：prevSectorPrice 在 initialize 后为空，若用 contains-key 守卫则永远不恢复，
                // 读档后首次 updateSectorMomentum 因 prev=0 跳过收益计算 → 动量连续性与存档前不一致。
                prevSectorPrice.put(c.getString("industry"), c.getDouble("price"));
            }
        }
        if (tag.contains("rngState")) rng.setState(tag.getLong("rngState"));
    }
}
