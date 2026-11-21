package com.example.financialmarket.engine.meso;

import com.example.financialmarket.engine.PriceDiscoveryEngine;
import com.example.financialmarket.engine.macro.MacroSnapshot;
import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MarS 中观层板块基金（MP-1 §3.2-2）：每行业一个（8 AI 行业 + PLAYER 汇总 = 9 个）。
 *
 * <p>低频（每宏观步 20 tick）聚合大单模型：
 * <ol>
 *   <li>板块基本面聚合：{@code sectorFair = NAV 加权平均(member.fairValue)}、
 *       {@code sectorPrice = NAV 加权平均(lastPrice)}（确定性，成员按资产 id 排序）；</li>
 *   <li>消费 {@link MacroSnapshot}：{@code discountRate↑ → sectorFair↓}（估值传导，经公司 fairValue
 *       既有路径）；{@code riskAppetite↓ / crisisLevel↑ → 目标仓位↓}（机构去杠杆）；</li>
 *   <li>消费 {@link SectorRotationModel} 轮动偏移：目标权重按「NAV 份额 × (1 + 轮动偏移×k)」确定性
 *       计算，偏离阈值（2%，代码常量）触发再平衡（防每 tick 抖动）；</li>
 *   <li>产出<strong>聚合大单 Intent</strong>（单笔 5000~50000 股，低频），经 {@link MesoOrderIntent}
 *       进入 MarketEngine 盘口（synthetic=true，不参与真实账户 T+1 交收）。</li>
 * </ol>
 *
 * <p>确定性：种子 = {@code mix64(engineSeed ^ ("meso/sector/"+industry).hashCode())}（§7.1-1），
 * 禁裸随机；内部记账按意图价乐观成交（合成流不参与真实账本，仅作基金自身组合跟踪）。
 */
public class SectorFundAgent {
    // ---- 代码常量（先验，不调参；§9 风险表：再平衡阈值 + 权重钳制防振荡） ----
    public static final long MESO_REBALANCE_MIN_INTERVAL_TICKS = 20; // 宏观步节奏（低频）
    public static final double REBALANCE_THRESHOLD = 0.02;           // 权重偏离阈值（防每 tick 抖动）
    public static final double MIN_ORDER_SHARES = 5000;              // 机构级单笔下界（MP-1-2）
    public static final double MAX_ORDER_SHARES = 50000;             // 机构级单笔上界
    public static final double TARGET_WEIGHT_MIN = 0.05;             // 目标权重钳制下界
    public static final double TARGET_WEIGHT_MAX = 0.40;             // 目标权重钳制上界（§9 风险表）
    public static final double BASE_EXPOSURE = 0.60;                 // 基准权益仓位
    public static final double INITIAL_AUM = 10_000_000.0;           // 初始板块资金规模（元）

    private final String industry;
    private final SeedableRandom rng;

    // ---- 状态（持久化，MP-5） ----
    private double aum = INITIAL_AUM;
    private double cash = INITIAL_AUM;                                  // 初始全现金，首轮再平衡建仓
    private final Map<String, Integer> holdings = new LinkedHashMap<>(); // assetId → 股数
    private final Map<String, Double> weights = new LinkedHashMap<>();   // assetId → aum 占比
    private double sectorMomentum = 0.0;                                // 板块动量 EMA
    private double targetExposure = BASE_EXPOSURE;                      // 目标权益仓位 [0.15,0.95]
    private long lastRebalanceTick = -1;

    // ---- 每宏观步观测（可观测性，供 [MESO] 日志与验证 harness 只读消费） ----
    private double lastSectorPrice = 0.0;
    private double lastSectorFair = 0.0;
    private double prevSectorPrice = 0.0;   // 上期板块价（动量计算）

    public SectorFundAgent(long engineSeed, String industry) {
        this.industry = industry;
        this.rng = new SeedableRandom(SeedableRandom.mix64(engineSeed ^ ("meso/sector/" + industry).hashCode()));
    }

    public String getIndustry() { return industry; }

    /**
     * 每宏观步驱动：消费板块聚合数据 + 宏观快照 + 轮动偏移，输出再平衡 Intent。
     *
     * @param data           板块聚合（成员/NAV/fair/price/sectorPrice/sectorFair，由 MesoLayerManager 确定性计算）
     * @param snap           宏观政策快照
     * @param rotationOffset 板块轮动偏移 [-1,1]（&gt;0 超配）
     * @param genTick        确定性时间戳（MarketEngine.tickCounter）
     * @return 本步产生的聚合大单 Intent（可能为空）
     */
    public List<MesoOrderIntent> tick(MesoLayerManager.SectorData data, MacroSnapshot snap,
                                      double rotationOffset, long genTick) {
        List<MesoOrderIntent> intents = new ArrayList<>();
        List<String> members = data.members();
        if (members.isEmpty() || data.navSum() <= 0) return intents;

        double sectorPrice = data.sectorPrice();
        double sectorFair = data.sectorFair();
        // 板块动量 EMA（确定性；用「上期板块价」计算收益，首次 prev<=0 时 ret=0 无扰动）
        if (sectorPrice > 0 && prevSectorPrice > 0 && Math.abs(sectorPrice - prevSectorPrice) > 1e-12) {
            double ret = (sectorPrice - prevSectorPrice) / prevSectorPrice;
            sectorMomentum = sectorMomentum * 0.9 + ret * 0.1;
        }
        prevSectorPrice = sectorPrice;
        lastSectorPrice = sectorPrice;
        lastSectorFair = sectorFair;

        // ---- 宏观消费：目标权益仓位（riskAppetite↓ / crisisLevel↑ → 目标仓位↓ = 机构去杠杆） ----
        double crisisTension = Math.max(0.0, Math.min(1.0, snap.crisisLevel() / 12.0));
        double riskAdj = (snap.riskAppetite() - 0.5) * 2.0;          // [-1,1]
        double target = BASE_EXPOSURE + 0.25 * riskAdj - 0.35 * crisisTension;
        target = Math.max(0.15, Math.min(0.95, target));
        targetExposure = target;

        // ---- 目标权重：NAV 份额 × (1 + 轮动偏移×k)，钳制 [0.05,0.40] 后整体归一化到 target ----
        Map<String, Double> targetW = new LinkedHashMap<>();
        double rawSum = 0.0;
        for (String id : members) {
            double nav = data.nav().getOrDefault(id, 0.0);
            if (nav <= 0) continue;
            double navShare = nav / data.navSum();
            double base = navShare * target;
            double tilted = base * (1.0 + 0.5 * rotationOffset);
            double clamped = Math.max(TARGET_WEIGHT_MIN, Math.min(TARGET_WEIGHT_MAX, tilted));
            targetW.put(id, clamped);
            rawSum += clamped;
        }
        if (targetW.isEmpty()) return intents;
        // 归一化：让权重和回到 target（按比例缩放；缩放后不再重钳制，防总和漂移）
        if (rawSum > 1e-12) {
            double scale = target / rawSum;
            for (Map.Entry<String, Double> e : targetW.entrySet()) {
                targetW.put(e.getKey(), e.getValue() * scale);
            }
        }

        // ---- 再平衡判定：偏离阈值 或 组合为空 ----
        boolean holdingsEmpty = holdings.isEmpty();
        boolean changed = holdingsEmpty;
        if (!changed) {
            for (String id : members) {
                double cur = weights.getOrDefault(id, 0.0);
                double tgt = targetW.getOrDefault(id, 0.0);
                if (Math.abs(tgt - cur) >= REBALANCE_THRESHOLD) { changed = true; break; }
            }
        }
        boolean due = lastRebalanceTick < 0
                || (genTick - lastRebalanceTick) >= MESO_REBALANCE_MIN_INTERVAL_TICKS;
        if (!changed || !due) return intents;

        for (String id : members) {
            double cur = weights.getOrDefault(id, 0.0);
            double tgt = targetW.getOrDefault(id, 0.0);
            double diff = tgt - cur;
            if (Math.abs(diff) < REBALANCE_THRESHOLD) continue;
            double px = data.px().getOrDefault(id, 0.0);
            if (px <= 0) continue;

            double value = Math.abs(diff) * aum;
            int qty = lotRound((int) Math.round(value / px));
            if (qty <= 0) continue;
            if (diff > 0) {
                double maxByCash = cash / px;
                qty = lotRound((int) Math.min(qty, Math.max(0, maxByCash)));
            } else {
                qty = lotRound((int) Math.min(qty, holdings.getOrDefault(id, 0)));
            }
            if (qty <= 0) continue;
            if (qty < MIN_ORDER_SHARES) continue;   // 单笔太小不动作（防微调抖动）
            if (qty > MAX_ORDER_SHARES) qty = (int) MAX_ORDER_SHARES; // 大单切分（残余偏差下步收敛）

            // 价格：以 lastPrice 为锚小幅被动偏移（确定性 rng 子流）
            double offset = (diff > 0 ? -1.0 : 1.0) * (0.001 + rng.nextDouble() * 0.002);
            double price = round2(px * (1.0 + offset));

            intents.add(new MesoOrderIntent(id, diff > 0, qty, price, "sector/" + industry, genTick));

            // 内部记账（按意图价乐观成交；合成流不参与真实账本，仅作组合跟踪）
            if (diff > 0) {
                cash -= price * qty;
                holdings.merge(id, qty, Integer::sum);
            } else {
                cash += price * qty;
                int left = holdings.getOrDefault(id, 0) - qty;
                if (left <= 0) holdings.remove(id);
                else holdings.put(id, left);
            }
        }
        lastRebalanceTick = genTick;
        recomputeAumAndWeights(data);
        return intents;
    }

    /** 重算 aum 与权重（确定性；成员价来自本步聚合快照）。 */
    private void recomputeAumAndWeights(MesoLayerManager.SectorData data) {
        double value = cash;
        for (String id : data.members()) {
            int h = holdings.getOrDefault(id, 0);
            if (h > 0) {
                double px = data.px().getOrDefault(id, 0.0);
                value += h * px;
            }
        }
        if (value <= 0) value = INITIAL_AUM * 0.5;
        aum = value;
        weights.clear();
        for (String id : data.members()) {
            int h = holdings.getOrDefault(id, 0);
            double px = data.px().getOrDefault(id, 0.0);
            weights.put(id, aum > 0 ? (h * px) / aum : 0.0);
        }
    }

    // ---- 观测只读（供 [MESO] 日志 / 验证 harness / t4 复核） ----
    public double getAum() { return aum; }
    public double getCash() { return cash; }
    public double getSectorMomentum() { return sectorMomentum; }
    public double getTargetExposure() { return targetExposure; }
    public long getLastRebalanceTick() { return lastRebalanceTick; }
    public double getLastSectorPrice() { return lastSectorPrice; }
    public double getLastSectorFair() { return lastSectorFair; }
    public Map<String, Integer> getHoldingsView() { return new LinkedHashMap<>(holdings); }
    public Map<String, Double> getWeightsView() { return new LinkedHashMap<>(weights); }

    private static int lotRound(int qty) {
        int lot = Math.max(1, PriceDiscoveryEngine.A_SHARE_BOARD_LOT);
        return qty >= lot ? (qty / lot) * lot : lot;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ---- 持久化（MP-5：板块 AUM/权重/动量/轮动状态随 "meso" 字段；旧档缺字段 contains 兜底） ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("industry", industry);
        tag.putDouble("aum", aum);
        tag.putDouble("cash", cash);
        tag.putDouble("sectorMomentum", sectorMomentum);
        tag.putDouble("targetExposure", targetExposure);
        tag.putLong("lastRebalanceTick", lastRebalanceTick);
        tag.putDouble("lastSectorPrice", lastSectorPrice);
        tag.putDouble("lastSectorFair", lastSectorFair);
        tag.putDouble("prevSectorPrice", prevSectorPrice);
        ListTag hList = new ListTag();
        for (Map.Entry<String, Integer> e : holdings.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", e.getKey());
            c.putInt("qty", e.getValue());
            hList.add(c);
        }
        tag.put("holdings", hList);
        ListTag wList = new ListTag();
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", e.getKey());
            c.putDouble("w", e.getValue());
            wList.add(c);
        }
        tag.put("weights", wList);
        tag.putLong("rngState", rng.getState());
        tag.putDouble("spareGaussian", rng.getSpareGaussian());
        return tag;
    }

    /** 恢复板块基金状态；旧档缺字段用默认值兜底，读档不失败。 */
    public void load(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("aum")) aum = tag.getDouble("aum");
        if (tag.contains("cash")) cash = tag.getDouble("cash");
        if (tag.contains("sectorMomentum")) sectorMomentum = tag.getDouble("sectorMomentum");
        if (tag.contains("targetExposure")) targetExposure = tag.getDouble("targetExposure");
        if (tag.contains("lastRebalanceTick")) lastRebalanceTick = tag.getLong("lastRebalanceTick");
        if (tag.contains("lastSectorPrice")) lastSectorPrice = tag.getDouble("lastSectorPrice");
        if (tag.contains("lastSectorFair")) lastSectorFair = tag.getDouble("lastSectorFair");
        if (tag.contains("prevSectorPrice")) prevSectorPrice = tag.getDouble("prevSectorPrice");
        holdings.clear();
        if (tag.contains("holdings")) {
            ListTag l = tag.getList("holdings", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                holdings.put(c.getString("id"), c.getInt("qty"));
            }
        }
        weights.clear();
        if (tag.contains("weights")) {
            ListTag l = tag.getList("weights", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                weights.put(c.getString("id"), c.getDouble("w"));
            }
        }
        if (tag.contains("rngState")) rng.setState(tag.getLong("rngState"));
        if (tag.contains("spareGaussian")) rng.setSpareGaussian(tag.getDouble("spareGaussian"));
    }
}
