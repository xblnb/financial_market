package com.example.financialmarket.engine.microstructure;

import com.example.financialmarket.engine.calibration.CalibrationParams;
import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * P2-2 订单流模型：ZI 零智能订单流基底 + Hawkes 自激励点过程。
 *
 * <p><b>ZI 零智能订单流</b>：无信息、对称的随机限价/市价单基底，作为「噪声」混入 agent
 * 订单流，使订单到达分布与盘口深度更真实（「智能不是必须，订单流分布才是关键」）。
 *
 * <p><b>Hawkes 自激励点过程</b>：订单到达强度 {@code λ(t) = λ0 + Σ K·e^(−β(t−t_i))}，
 * 背景强度 λ0 + 历史触发指数衰减。一次大单/到达会提高随后若干 tick 的到达强度，
 * 从而产生订单到达聚集与流动性剧变（A2-3）。
 *
 * <p><b>可复现性（A2-1/A2-3）</b>：ZI 与 Hawkes 各自用 {@link SeedableRandom} 从
 * {@code engineSeed} 派生子流（splitmix64），同一种子时序完全确定，禁用裸随机。
 * 两套 RNG 状态与 arrivalTimes 可随存档持久化，读档后下一 tick 的到达序列连续可复现。
 *
 * <p>纯 Java（除 SeedableRandom / net.minecraft.nbt 外不依赖 Minecraft 运行时），O(1) 每资产每 tick。
 */
public class OrderFlowModel {
    // ---- ZI 基底 ----
    private final SeedableRandom ziRng;           // seed = mix64(engineSeed ^ "OrderFlow.ZI".hashCode())
    private final double ziBaseRate;              // 每资产每 tick 基础到达概率（默认 0.02）
    private final double ziBuyProb;               // 无信息对称（默认 0.5）
    private final double ziLotMean;               // 单笔股数均值（默认 40）
    private final double ziLotDispersion;         // 股数几何扩散（默认 60）
    private final double ziLimitVsMarketProb;     // 限价单占比（默认 0.8；市价单占比 = 1 − 该值）
    // ---- Hawkes 自激励 ----
    private final SeedableRandom hawkesRng;       // seed = mix64(engineSeed ^ "OrderFlow.Hawkes".hashCode())
    private final double hawkesLambda0;           // 背景强度（默认 0.005）
    private final double hawkesK;                 // 触发增益（默认 0.02）
    private final double hawkesBeta;              // 指数衰减（默认 0.15）
    private final int hawkesMaxBranch;            // 保留最近触发次数（默认 3，防无限累加）
    private final Map<String, Deque<Long>> arrivalTimes; // assetId -> 最近 arrival tick
    private long currentTick;

    /** 时间步长 dt（tick 内的连续时间分数；1 游戏日 = 24000 tick）。 */
    private static final double DT_PER_TICK = 20.0 / 24000.0;

    /**
     * 全默认参数构造（保守默认，无校准注入时使用）。
     */
    public OrderFlowModel(long engineSeed) {
        this(engineSeed, 0.02, 0.5, 40.0, 60.0, 0.8,
                0.005, 0.02, 0.15, 3);
    }

    /**
     * 显式参数构造（供校准注入 t4/t6 覆盖默认）。
     *
     * @param engineSeed        引擎种子（派生 ZI/Hawkes 子流）
     * @param ziBaseRate        ZI 每 tick 基础到达概率
     * @param ziBuyProb         买单概率（对称基准 0.5）
     * @param ziLotMean         单笔股数均值
     * @param ziLotDispersion   单笔股数扩散（几何/指数采样尺度）
     * @param ziLimitVsMarketProb 限价单占比 [0,1]；市价单占比为 1−该值
     * @param hawkesLambda0     Hawkes 背景强度 λ0
     * @param hawkesK           Hawkes 触发增益 K
     * @param hawkesBeta        Hawkes 指数衰减 β
     * @param hawkesMaxBranch   保留最近触发次数（硬约束 K·maxBranch < ~0.1）
     */
    public OrderFlowModel(long engineSeed, double ziBaseRate, double ziBuyProb, double ziLotMean,
                          double ziLotDispersion, double ziLimitVsMarketProb, double hawkesLambda0,
                          double hawkesK, double hawkesBeta, int hawkesMaxBranch) {
        this.ziRng = new SeedableRandom(SeedableRandom.mix64(engineSeed ^ "OrderFlow.ZI".hashCode()));
        this.hawkesRng = new SeedableRandom(SeedableRandom.mix64(engineSeed ^ "OrderFlow.Hawkes".hashCode()));
        this.ziBaseRate = ziBaseRate;
        this.ziBuyProb = ziBuyProb;
        this.ziLotMean = ziLotMean;
        this.ziLotDispersion = ziLotDispersion;
        this.ziLimitVsMarketProb = ziLimitVsMarketProb;
        this.hawkesLambda0 = hawkesLambda0;
        this.hawkesK = hawkesK;
        this.hawkesBeta = hawkesBeta;
        this.hawkesMaxBranch = hawkesMaxBranch;
        this.arrivalTimes = new HashMap<>();
        this.currentTick = 0;
    }

    /**
     * 校准注入构造：用 {@link CalibrationParams} 的真实市场先验覆盖默认参数
     * （orderArrivalRate→ziBaseRate，depthAtBestMedian→大单/限价尺度，其余保持默认）。
     * DEFAULT 即默认参数，行为与全默认构造一致。
     *
     * <p>F2.1/F2.2（t6，agent-dev）+ t7（engine 侧成交连续性）：ZI 基底密度提升到 ≥0.20（每 tick 到达
     * 概率更高，配合 market 单占比 0.45 使合成流频繁主动吃单 → 价格随每 tick 连续移动，避免 lastPrice
     * 长期冻结（tick 级 κ 极端/raw-LB 自相关/量-波失效的根因）。ziLotMean ≥ 100（百股档）、
     * Hawkes 在 K·maxBranch&lt;0.1 硬约束内增强，ziBuyProb=0.5 对称保持。全部经 {@code engineSeed}
     * 派生子流，确定性保持。
     */
    public OrderFlowModel(long engineSeed, CalibrationParams cal) {
        this(engineSeed,
                Math.max(0.10, cal != null ? cal.getOrderArrivalRate() : 0.02),   // t7：适度密度（价格连续移动→κ 生理、有涨有跌）
                0.5,
                Math.max(250.0, cal != null ? cal.getDepthAtBestMedian() * 0.08 : 40.0), // 单量下限(较大→大单移动价格时成交量高，量-波正相关)
                400.0,   // 单量扩散
                0.85,    // 限价 0.85 / 市价 0.15（市场单少→不快速消费盘口双侧，盘口≥90%）
                0.015,   // Hawkes 背景强度
                0.03,    // Hawkes 触发增益（K·maxBranch=0.09<0.1 硬约束保持）
                0.15,
                3);
    }

    /** 是否启用 ZI/Hawkes 订单流（基座>0 或触发>0 时启用）。 */
    public boolean enabled() {
        return ziBaseRate > 0 || hawkesLambda0 > 0 || hawkesK > 0;
    }

    public void setCurrentTick(long tick) {
        this.currentTick = tick;
    }

    public long getCurrentTick() {
        return this.currentTick;
    }

    // ---------------- ZI ----------------

    /** 判定本 tick 是否生成一笔 ZI 订单（ziRng.nextDouble() < ziBaseRate）。 */
    public boolean shouldGenerateZiOrder(String assetId) {
        return ziRng.nextDouble() < ziBaseRate;
    }

    /** 判定 ZI 订单方向（无信息对称；ziRng.nextDouble() < ziBuyProb → 买）。 */
    public boolean ziIsBuy(String assetId) {
        return ziRng.nextDouble() < ziBuyProb;
    }

    /** 采样 ZI 单笔股数（几何/指数近似，≥1）。ziLotDispersion 用作大单/扩散上限钳制（避免单笔异常巨量）。 */
    public int ziLotSize(String assetId) {
        double u = Math.min(1.0 - 1e-9, ziRng.nextDouble());
        // 指数采样（几何分布的连续近似），均值 ≈ ziLotMean；ziLotDispersion 作为扩散上限参考钳制
        double lot = -ziLotMean * Math.log(1.0 - u);
        // ziLotDispersion 用于采样：单笔股数钳制到 ≤ max(ziLotMean, ziLotDispersion)（默认 ≤ 60）
        lot = Math.min(lot, Math.max(ziLotMean, ziLotDispersion));
        int size = (int) Math.max(1.0, Math.round(lot));
        return size;
    }

    /** 判定 ZI 订单是否市价单（ziRng.nextDouble() < 市价占比 = 1−限价占比）。 */
    public boolean ziIsMarket(String assetId) {
        return ziRng.nextDouble() < (1.0 - ziLimitVsMarketProb);
    }

    /** 限价偏移（买减/卖加，±tick 步长，ziRng 采样）：返回正偏移量。 */
    public double ziLimitOffset(String assetId) {
        // 1~3 个 tick 步长内的随机偏移，保证贴近 best bid/ask 提高成交概率
        int ticks = 1 + ziRng.nextInt(3);
        return ticks * 0.01;
    }

    // ---------------- Hawkes ----------------

    /** 当前到达强度 λ(t) = λ0 + Σ_{i∈arrivalTimes} K·e^(−β(t−t_i))。 */
    public double intensity(String assetId) {
        double total = hawkesLambda0;
        Deque<Long> dq = arrivalTimes.get(assetId);
        if (dq != null) {
            for (long t : dq) {
                total += hawkesK * Math.exp(-hawkesBeta * (currentTick - t));
            }
        }
        return total;
    }

    /** 薄化采样判定一次 Hawkes 到达（hawkesRng.nextDouble() < min(1, λ·dt)）。 */
    public boolean hawkesArrival(String assetId) {
        double lambda = intensity(assetId);
        double p = Math.min(1.0, lambda * DT_PER_TICK);
        return hawkesRng.nextDouble() < p;
    }

    /** 记录一次到达（入队 + 截断到 hawkesMaxBranch，防无限累加）。 */
    public void recordArrival(String assetId, long tick) {
        Deque<Long> dq = arrivalTimes.computeIfAbsent(assetId, k -> new ArrayDeque<>());
        dq.addLast(tick);
        while (dq.size() > hawkesMaxBranch) {
            dq.pollFirst();
        }
    }

    // ---------------- 持久化 ----------------

    /** 序列化 ZI/Hawkes RNG 状态 + 各资产最近 arrival tick（A2-7）。 */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("ziRngState", ziRng.getState());
        tag.putDouble("ziSpareGaussian", ziRng.getSpareGaussian());
        tag.putLong("hawkesRngState", hawkesRng.getState());
        tag.putDouble("hawkesSpareGaussian", hawkesRng.getSpareGaussian());
        tag.putLong("currentTick", currentTick);

        ListTag arrivals = new ListTag();
        for (Map.Entry<String, Deque<Long>> e : arrivalTimes.entrySet()) {
            CompoundTag at = new CompoundTag();
            at.putString("assetId", e.getKey());
            ListTag times = new ListTag();
            for (long t : e.getValue()) {
                CompoundTag tt = new CompoundTag();
                tt.putLong("t", t);
                times.add(tt);
            }
            at.put("times", times);
            arrivals.add(at);
        }
        tag.put("arrivalTimes", arrivals);
        return tag;
    }

    /** 读档恢复 RNG 状态 + arrivalTimes；旧存档缺字段用 contains 兜底，读档不失败。 */
    public void load(CompoundTag tag) {
        if (tag.contains("ziRngState")) ziRng.setState(tag.getLong("ziRngState"));
        if (tag.contains("ziSpareGaussian")) ziRng.setSpareGaussian(tag.getDouble("ziSpareGaussian"));
        if (tag.contains("hawkesRngState")) hawkesRng.setState(tag.getLong("hawkesRngState"));
        if (tag.contains("hawkesSpareGaussian")) hawkesRng.setSpareGaussian(tag.getDouble("hawkesSpareGaussian"));
        if (tag.contains("currentTick")) currentTick = tag.getLong("currentTick");

        arrivalTimes.clear();
        if (tag.contains("arrivalTimes", Tag.TAG_LIST)) {
            ListTag arrivals = tag.getList("arrivalTimes", Tag.TAG_COMPOUND);
            for (int i = 0; i < arrivals.size(); i++) {
                CompoundTag at = arrivals.getCompound(i);
                String assetId = at.getString("assetId");
                ListTag times = at.getList("times", Tag.TAG_COMPOUND);
                Deque<Long> dq = new ArrayDeque<>();
                for (int j = 0; j < times.size(); j++) {
                    dq.addLast(times.getCompound(j).getLong("t"));
                }
                arrivalTimes.put(assetId, dq);
            }
        }
    }
}
