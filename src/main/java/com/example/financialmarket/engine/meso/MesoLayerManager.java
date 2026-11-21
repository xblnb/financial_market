package com.example.financialmarket.engine.meso;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.ai.AICompanyData;
import com.example.financialmarket.engine.macro.MacroSnapshot;
import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MarS 中观层总控（MP-1 §3.2）：板块基金（9 个）+ 机构投资者（5~10 个）+ 板块轮动模型。
 *
 * <p>职责：
 * <ol>
 *   <li><strong>板块聚合</strong>：每宏观步按资产 id 排序（§7.1-3 确定性遍历）聚合每板块
 *       NAV 加权 sectorPrice / sectorFair，喂给 {@link SectorRotationModel} 动量与横截面估值 z-score；</li>
 *   <li><strong>传导</strong>：{@code MacroSnapshot}（宏观政策层）→ 板块基金/机构（中观层）→
 *       {@link MesoOrderIntent} 队列 → {@code MarketEngine.placeMesoOrders}（微观盘口），
 *       形成「政策 → 机构 → 做市商 → 盘口」三层传导；</li>
 *   <li><strong>可观测性</strong>：每宏观步输出确定性日志
 *       {@code [MESO] industry=.. sectorPrice=.. sectorFair=.. rotation=OVER/UNDER flow=+.. aum=..}
 *       （供验证断言 MP-1-3/1-4）；</li>
 *   <li><strong>持久化</strong>：板块 AUM/权重/动量/轮动状态 + 机构现金/持仓/杠杆/风格随
 *       {@code MarketEngine.save()} 的 "meso" 字段；旧档缺字段 contains 兜底重建，读档不失败（MP-5）。</li>
 * </ol>
 *
 * <p>确定性：全部随机经 {@code mix64(engineSeed ^ "meso/…")} 派生子流；遍历序（板块注册序 /
 * 资产 id 排序 / 机构注册序）固定；`marS.enabled=false` 时本层不 tick、不产出、不消费任何随机源（MP-1-7）。
 */
public class MesoLayerManager {
    /** 9 个板块：8 个 AI 行业（对齐 {@code AICompanyManager.java:21}）+ PLAYER（玩家公司汇总）。 */
    public static final String[] INDUSTRIES = {
            "MINING", "TECH", "AGRICULTURE", "ENERGY", "LOGISTICS", "FINANCE", "CONSTRUCTION", "MAGIC", "PLAYER"
    };

    /** 宏观步节奏：每 20 tick（与 MarketEngine L1 宏观块 gameTime%20 对齐）。 */
    public static final long MESO_STEP_TICKS = 20;
    /** 机构数量范围（MP-1-1：≥5）。 */
    public static final int INST_MIN = 5;
    public static final int INST_RANGE = 6; // 5 + [0,6) → 5..10

    /** 板块聚合数据（MesoLayerManager 每宏观步确定性计算，供板块基金消费）。 */
    public record SectorData(
            List<String> members,
            Map<String, Double> nav,
            Map<String, Double> fair,
            Map<String, Double> px,
            double sectorPrice,
            double sectorFair,
            double navSum
    ) {}

    private long engineSeed;
    private boolean initialized = false;

    private final Map<String, SectorFundAgent> funds = new LinkedHashMap<>();   // 注册序（确定性）
    private final List<InstitutionalAgent> institutions = new ArrayList<>();
    private SectorRotationModel rotation;
    private final Deque<MesoOrderIntent> intentQueue = new ArrayDeque<>();

    // ---- 本期观测（供 [MESO] 日志 / getSectorFlow / 验证 harness） ----
    private final Map<String, Double> sectorFlow = new LinkedHashMap<>();       // 每板块本期净资金流
    private final Map<String, Double> lastRotationOffset = new LinkedHashMap<>();
    private final Map<String, Double> lastValuationZ = new LinkedHashMap<>();

    // ---- 统计（可观测/验证） ----
    private long totalIntents = 0;
    private double intentSizeSum = 0.0;
    private long lastTick = -1;

    public MesoLayerManager(long engineSeed) {
        this.engineSeed = engineSeed;
    }

    /**
     * 初始化（冷启动/缺档兜底）：注册 9 板块 + 派生机构数（5~10）并注册机构。
     * 幂等（清空重建）；确定性——同 engineSeed 同结构同序列。
     */
    public void initialize(long engineSeed) {
        this.engineSeed = engineSeed;
        this.rotation = new SectorRotationModel(engineSeed);
        funds.clear();
        institutions.clear();
        intentQueue.clear();
        sectorFlow.clear();
        lastRotationOffset.clear();
        lastValuationZ.clear();
        SeedableRandom initRng = new SeedableRandom(SeedableRandom.mix64(engineSeed ^ "meso/init".hashCode()));
        for (String industry : INDUSTRIES) {
            rotation.registerSector(industry);
            funds.put(industry, new SectorFundAgent(engineSeed, industry));
            sectorFlow.put(industry, 0.0);
            lastRotationOffset.put(industry, 0.0);
            lastValuationZ.put(industry, 0.0);
        }
        int instCount = INST_MIN + initRng.nextInt(INST_RANGE);
        for (int i = 0; i < instCount; i++) {
            institutions.add(new InstitutionalAgent(engineSeed, i, INDUSTRIES[i % INDUSTRIES.length]));
        }
        initialized = true;
    }

    public boolean isInitialized() { return initialized; }
    public int getFundCount() { return funds.size(); }
    public int getInstitutionCount() { return institutions.size(); }

    /**
     * 宏观步驱动（MarketEngine.tick 宏观块之后、L2 之前；marS.enabled 时调用）。
     *
     * @param engine  引擎引用（只读：资产/公司数据）
     * @param snap    宏观政策快照
     * @param genTick 确定性时间戳（MarketEngine.tickCounter）
     */
    public void tick(MarketEngine engine, MacroSnapshot snap, long genTick) {
        if (!initialized) return;
        List<String> industries = new ArrayList<>(funds.keySet());   // 注册序（确定性，禁 HashMap keySet）

        // 1) 板块聚合 + 动量
        Map<String, SectorData> data = new LinkedHashMap<>();
        for (String industry : industries) {
            SectorData d = aggregate(engine, industry);
            data.put(industry, d);
            if (d.navSum() > 0 && d.sectorPrice() > 0) {
                rotation.updateSectorMomentum(industry, d.sectorPrice());
            }
        }

        // 2) 横截面估值 z-score（sectorPrice/sectorFair，确定性）
        Map<String, Double> ratio = new LinkedHashMap<>();
        double mean = 0.0;
        for (String industry : industries) {
            SectorData d = data.get(industry);
            double r = d.sectorFair() > 0 ? d.sectorPrice() / d.sectorFair() : 1.0;
            ratio.put(industry, r);
            mean += r;
        }
        mean /= industries.size();
        double var = 0.0;
        for (String industry : industries) {
            double dd = ratio.get(industry) - mean;
            var += dd * dd;
        }
        double sd = Math.sqrt(var / Math.max(1, industries.size()));
        for (String industry : industries) {
            double z = sd > 1e-12 ? (ratio.get(industry) - mean) / sd : 0.0;
            lastValuationZ.put(industry, z);
            double off = rotation.rotationOffset(industry, rotation.getSectorMomentum(industry), z,
                    snap.cyclePhase(), snap.riskAppetite());
            lastRotationOffset.put(industry, off);
        }

        // 3) 清空上期意图/净资金流 → 板块基金再平衡
        intentQueue.clear();
        for (String industry : industries) {
            sectorFlow.put(industry, 0.0);
        }
        for (String industry : industries) {
            SectorFundAgent fund = funds.get(industry);
            SectorData d = data.get(industry);
            List<MesoOrderIntent> intents = fund.tick(d, snap, lastRotationOffset.get(industry), genTick);
            for (MesoOrderIntent intent : intents) {
                intentQueue.add(intent);
                totalIntents++;
                intentSizeSum += intent.quantity();
                sectorFlow.merge(industry, intent.isBuy() ? (double) intent.quantity() : (double) -intent.quantity(), Double::sum);
            }
            emitObservabilityLog(industry, fund, lastRotationOffset.get(industry), sectorFlow.get(industry));
        }

        // 4) 机构投资者
        for (InstitutionalAgent inst : institutions) {
            String h = inst.getHomeIndustry();
            List<MesoOrderIntent> intents = inst.tick(engine, snap,
                    rotation.getSectorMomentum(h), lastRotationOffset.getOrDefault(h, 0.0), genTick);
            for (MesoOrderIntent intent : intents) {
                intentQueue.add(intent);
                totalIntents++;
                intentSizeSum += intent.quantity();
                sectorFlow.merge(h, intent.isBuy() ? (double) intent.quantity() : (double) -intent.quantity(), Double::sum);
            }
        }
        lastTick = genTick;
    }

    /** 消费本宏观步全部中观 Intent（MarketEngine.placeMesoOrders 调用；消费后清空）。 */
    public List<MesoOrderIntent> consumeIntents() {
        List<MesoOrderIntent> out = new ArrayList<>(intentQueue);
        intentQueue.clear();
        return out;
    }

    /** 板块净资金流（本期，股数口径；供 marS.mesoToMicroBias 微观偏置只读消费；默认 0）。 */
    public double getSectorFlow(String industry) {
        return sectorFlow.getOrDefault(industry, 0.0);
    }

    /** 板块净资金流确定性视图（按行业名排序）。 */
    public Map<String, Double> getSectorFlows() {
        Map<String, Double> out = new LinkedHashMap<>();
        List<String> sorted = new ArrayList<>(sectorFlow.keySet());
        Collections.sort(sorted);
        for (String s : sorted) out.put(s, sectorFlow.getOrDefault(s, 0.0));
        return out;
    }

    /** 板块聚合（确定性）：成员按资产 id 排序，NAV 加权 fair/price。 */
    private SectorData aggregate(MarketEngine engine, String industry) {
        List<String> members = membersOf(engine, industry);
        Map<String, Double> nav = new LinkedHashMap<>();
        Map<String, Double> fair = new LinkedHashMap<>();
        Map<String, Double> px = new LinkedHashMap<>();
        double navSum = 0, fairSum = 0, pxSum = 0;
        for (String id : members) {
            double n = navOf(engine, id);
            double f = fairOf(engine, id);
            double p = engine.getState().getAsset(id) != null ? engine.getState().getAsset(id).getLastPrice() : 0.0;
            if (n <= 0 || p <= 0 || f <= 0) continue;
            nav.put(id, n);
            fair.put(id, f);
            px.put(id, p);
            navSum += n;
            fairSum += n * f;
            pxSum += n * p;
        }
        double sectorPrice = navSum > 0 ? pxSum / navSum : 0.0;
        double sectorFair = navSum > 0 ? fairSum / navSum : 0.0;
        return new SectorData(members, nav, fair, px, sectorPrice, sectorFair, navSum);
    }

    /** 该行业全部资产 id（按资产 id 字典序排序，确定性遍历，§7.1-3）。 */
    static List<String> membersOf(MarketEngine engine, String industry) {
        List<String> ids = new ArrayList<>(engine.getState().getAssetIds());
        Collections.sort(ids);
        List<String> members = new ArrayList<>();
        for (String id : ids) {
            String ind = engine.industryOfAsset(id);
            if (ind != null && ind.equals(industry)) members.add(id);
        }
        return members;
    }

    /** 资产 NAV（AssetSnapshot.nav；无效回落公司净资产，再回落 1 兜底）。 */
    static double navOf(MarketEngine engine, String id) {
        if (engine.getState().getAsset(id) != null && engine.getState().getAsset(id).getNav() > 0) {
            return engine.getState().getAsset(id).getNav();
        }
        for (AICompanyData c : engine.getAiCompanyManager().getAll()) {
            if (c.getStockId().equals(id) && c.getNetAssetValue() > 0) return c.getNetAssetValue();
        }
        return 1.0;
    }

    /** 资产 fairValue（AICompanyData；玩家公司无 AI fair → 回落当前价中性值）。 */
    static double fairOf(MarketEngine engine, String id) {
        for (AICompanyData c : engine.getAiCompanyManager().getAll()) {
            if (c.getStockId().equals(id)) {
                double f = c.getFairValue();
                if (f > 0) return f;
                break;
            }
        }
        return engine.getState().getAsset(id) != null ? engine.getState().getAsset(id).getLastPrice() : 0.0;
    }

    /** 传导可观测性日志（确定性打印，供验证断言 MP-1-3/1-4）。 */
    private void emitObservabilityLog(String industry, SectorFundAgent fund, double rotationOffset, double flow) {
        StringBuilder sb = new StringBuilder();
        sb.append("[MESO] industry=").append(industry)
                .append(" sectorPrice=").append(fmt(fund.getLastSectorPrice()))
                .append(" sectorFair=").append(fmt(fund.getLastSectorFair()))
                .append(" rotation=").append(rotationOffset > 0 ? "OVER" : "UNDER")
                .append(" flow=").append(flow >= 0 ? "+" : "").append(fmt(flow))
                .append(" aum=").append(fmt(fund.getAum()))
                .append(" targetExposure=").append(fmt(fund.getTargetExposure()));
        System.out.println(sb);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // ---- 观测只读（供验证 harness / t4 复核） ----
    public double getSectorPrice(String industry) {
        SectorFundAgent f = funds.get(industry);
        return f != null ? f.getLastSectorPrice() : 0.0;
    }

    public double getSectorFair(String industry) {
        SectorFundAgent f = funds.get(industry);
        return f != null ? f.getLastSectorFair() : 0.0;
    }

    public double getSectorAum(String industry) {
        SectorFundAgent f = funds.get(industry);
        return f != null ? f.getAum() : 0.0;
    }

    public double getTargetExposure(String industry) {
        SectorFundAgent f = funds.get(industry);
        return f != null ? f.getTargetExposure() : 0.0;
    }

    public double getRotationOffset(String industry) {
        return lastRotationOffset.getOrDefault(industry, 0.0);
    }

    public double getSectorMomentum(String industry) {
        return rotation != null ? rotation.getSectorMomentum(industry) : 0.0;
    }

    public double getValuationZ(String industry) {
        return lastValuationZ.getOrDefault(industry, 0.0);
    }

    public SectorFundAgent getFund(String industry) { return funds.get(industry); }
    public List<InstitutionalAgent> getInstitutions() { return new ArrayList<>(institutions); }
    public long getTotalIntents() { return totalIntents; }
    public double getAvgIntentSize() { return totalIntents > 0 ? intentSizeSum / totalIntents : 0.0; }
    public long getLastTick() { return lastTick; }
    public SectorRotationModel getRotation() { return rotation; }

    // ---- 持久化（MP-5：中观状态随 MarketEngine.save() 的 "meso" 字段；旧档缺字段 contains 兜底） ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("engineSeed", engineSeed);
        tag.putLong("lastTick", lastTick);
        tag.putLong("totalIntents", totalIntents);
        tag.putDouble("intentSizeSum", intentSizeSum);
        tag.put("rotation", rotation != null ? rotation.save() : new CompoundTag());
        ListTag fundsList = new ListTag();
        for (SectorFundAgent f : funds.values()) {
            fundsList.add(f.save());
        }
        tag.put("funds", fundsList);
        ListTag instList = new ListTag();
        for (InstitutionalAgent a : institutions) {
            instList.add(a.save());
        }
        tag.put("institutions", instList);
        return tag;
    }

    /** 恢复中观状态：先按确定性结构重建（initialize），再叠加持久化字段（contains 兜底，读档不失败）。 */
    public void load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            initialize(engineSeed);
            return;
        }
        if (tag.contains("engineSeed")) engineSeed = tag.getLong("engineSeed");
        if (tag.contains("lastTick")) lastTick = tag.getLong("lastTick");
        if (tag.contains("totalIntents")) totalIntents = tag.getLong("totalIntents");
        if (tag.contains("intentSizeSum")) intentSizeSum = tag.getDouble("intentSizeSum");
        // 结构重建（确定性）→ 叠加状态
        initialize(engineSeed);
        if (tag.contains("rotation") && rotation != null) {
            rotation.load(tag.getCompound("rotation"));
        }
        if (tag.contains("funds")) {
            ListTag l = tag.getList("funds", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                SectorFundAgent f = funds.get(c.getString("industry"));
                if (f != null) f.load(c);
            }
        }
        if (tag.contains("institutions")) {
            ListTag l = tag.getList("institutions", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                if (c.contains("index")) {
                    int idx = c.getInt("index");
                    if (idx >= 0 && idx < institutions.size()) institutions.get(idx).load(c);
                }
            }
        }
    }
}
