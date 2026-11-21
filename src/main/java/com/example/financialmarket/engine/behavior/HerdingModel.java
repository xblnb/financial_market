package com.example.financialmarket.engine.behavior;

import com.example.financialmarket.engine.random.SeedableRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * P1-1 Cont–Bouchaud 羊群自旋模型（纯 Java，不依赖 Minecraft 运行时类）。
 *
 * <p>每个 agent 持有方向 ±1/0，每步以概率 {@code a} 跟随所在 group 的群体净方向
 * （羊群），以概率 {@code 1-a} 独立决策。group 复用行业（{@code company.getIndustry()}）。
 *
 * <p>可复现性硬约束（继承 P0/A0-3）：所有随机一律使用 {@link SeedableRandom}，
 * 禁 {@code Math.random()}。羊群强度 {@code a} 以 <0.3 为硬上限，防全局同步跑飞。
 */
public class HerdingModel {

    /** 单个 group（行业）的群体状态。 */
    public static class GroupState {
        /** 净方向，∈[-1,1]，+1 偏买 / -1 偏卖（经时间衰减的净情绪）。 */
        public double netDirection = 0.0;
        /** 该 group 的 agent 数。 */
        public int agentCount = 0;
        /** 簇大小上限，防单簇无限同步。 */
        public int clusterCap = 50;
    }

    /** 羊群强度 a ∈ (0, 0.3)，默认 0.15；0 表示关闭。硬上限 0.3。 */
    private double a;
    /** 记忆系数（净方向 EMA 更新权重），默认 0.1。 */
    private final double memory;
    /** 簇大小上限，默认 50。 */
    private final int clusterCap;

    /** 各 group 状态，键为行业名（company.getIndustry() 复用）。 */
    private final Map<String, GroupState> groups = new HashMap<>();

    /** 派生种子基准：engineSeed 派生子流，保证可复现。 */
    private final long seedBase;

    /**
     * 以指定引擎种子构造；子流种子 = mix64(engineSeed ^ "HerdingModel".hashCode())。
     *
     * @param engineSeed 引擎种子（与 MultiAgentManager 同源）。
     */
    public HerdingModel(long engineSeed) {
        this(engineSeed, 0.15, 0.1, 50);
    }

    /**
     * 全参数构造，羊群强度 {@code a} 强制 < 0.3 硬上限。
     *
     * @param engineSeed 引擎种子。
     * @param a          羊群强度 ∈ (0,0.3)，建议 0.15，>0.25 强簇状，0 关闭。
     * @param memory     净方向 EMA 记忆系数（0,1]，建议 0.1。
     * @param clusterCap 簇大小上限，防单簇无限同步。
     */
    public HerdingModel(long engineSeed, double a, double memory, int clusterCap) {
        this.seedBase = SeedableRandom.mix64(engineSeed ^ "HerdingModel".hashCode());
        this.a = Math.min(0.3, Math.max(0.0, a)); // 硬上限 0.3 防全局同步跑飞
        this.memory = (memory > 0 && memory <= 1.0) ? memory : 0.1;
        this.clusterCap = Math.max(1, clusterCap);
    }

    /**
     * 设置/更新 group 的 agent 数（在 agent 组建立时调用）。
     *
     * @param group      行业名。
     * @param agentCount 该 group 的 agent 数。
     */
    public void setGroup(String group, int agentCount) {
        GroupState gs = groups.computeIfAbsent(group, k -> new GroupState());
        gs.agentCount = agentCount;
        gs.clusterCap = this.clusterCap;
    }

    /**
     * 用 EMA 更新 group 净方向：netDirection = netDirection*(1-mem) + netFlow*mem，
     * 钳制到 [-1,1]。
     *
     * @param group   行业名。
     * @param netFlow 本步该 group 的净流向（如 Σ(isBuy?+1:-1)，单位化到组大小附近）。
     */
    public void updateBias(String group, double netFlow) {
        GroupState gs = groups.computeIfAbsent(group, k -> new GroupState());
        // 单位化：若组规模已知，把净流压到 [-1,1] 量级，避免随 agent 数发散
        double scaled = netFlow;
        if (gs.agentCount > 0) {
            scaled = netFlow / (double) gs.agentCount;
            scaled = Math.max(-1.0, Math.min(1.0, scaled));
        }
        gs.netDirection = gs.netDirection * (1.0 - memory) + scaled * memory;
        gs.netDirection = Math.max(-1.0, Math.min(1.0, gs.netDirection));
    }

    /**
     * 返回 group 净方向（供 agent 下单方向跟随）。
     *
     * @param group 行业名。
     * @return 净方向 ∈[-1,1]；未知 group 返回 0（中性）。
     */
    public double getBias(String group) {
        GroupState gs = groups.get(group);
        return gs == null ? 0.0 : gs.netDirection;
    }

    /**
     * 返回 group 净方向（用于扰动 isBuy）。assetId 兼容多资产分组语义：
     * 若该资产已登记独立 group 则返回其净方向，否则回落到行业 group。
     *
     * @param assetId 资产 id。
     * @param group   行业 group。
     * @return 群体净方向 ∈[-1,1]，≥0 偏买、<0 偏卖。
     */
    public double computeGroupBias(String assetId, String group) {
        // assetId 兼容：优先查资产级 group（若 t3 用 assetId 作额外键），否则回落到行业
        GroupState gs = groups.get(assetId);
        if (gs == null) gs = groups.get(group);
        return gs == null ? 0.0 : gs.netDirection;
    }

    /**
     * 以概率 {@code a} 返回 true（跟随群体），否则 false（独立决策）。
     *
     * @param rng 可播种 PRNG（调用方传入，保证序列确定且不引入额外随机源）。
     * @return 是否跟随群体。
     */
    public boolean shouldHerd(SeedableRandom rng) {
        return rng.nextDouble() < a;
    }

    /** 羊群强度 a（已含硬上限钳制）。 */
    public double getHerdingStrength() { return a; }

    /**
     * 动态调整羊群强度（仍强制 <0.3 硬上限）。供 t5 因子隔离验证（a=0 vs a=0.15）。
     */
    public void setHerdingStrength(double a) {
        this.a = Math.min(0.3, Math.max(0.0, a));
    }

    /** 派生随机子流（供调用方在需要一致随机时复用当前种子基准）。 */
    public long deriveSeed(String id) {
        return SeedableRandom.mix64(seedBase ^ id.hashCode());
    }

    /** 当前所有 group 状态（只读视图，供诊断/持久化）。 */
    public Map<String, GroupState> getGroups() { return groups; }
}
