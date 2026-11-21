package com.example.financialmarket.engine.meso;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import com.example.financialmarket.engine.behavior.BehaviorUtility;
import com.example.financialmarket.engine.crisis.EconomicCycle;
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
 * MarS 中观层机构投资者（MP-1 §3.2-3）：5~10 个，风格 = value/growth/momentum。
 *
 * <p>行为（确定性，全部经 SeedableRandom 子流 + BehaviorUtility 纯函数）：
 * <ul>
 *   <li><strong>value</strong>：向 {@code fairValue} 偏离回归（低估买/高估卖，低频大单）；</li>
 *   <li><strong>growth</strong>：向板块动量追涨（带止损护栏：跌破成本 12% 强制离场）；</li>
 *   <li><strong>momentum</strong>：板块轮动跟随（SectorRotationModel 输出超配→买、低配→卖）；</li>
 *   <li><strong>杠杆</strong>：{@code leverage = clamp(1.0, 3.0, base × liquidityMultiplier ×
 *       cycleLeverageMultiplier)}（对齐 {@code EconomicCycle.leverageMultiplier}，{@code EconomicCycle.java:50-57}）；
 *       目标杠杆下降（危机/收缩）→ 强制抛售去杠杆（明斯基中观传导）；</li>
 *   <li><strong>处置效应</strong>：卖出时按 {@code BehaviorUtility.dispositionSellMultiplier(gain)}
 *       门控（盈利更倾向卖、亏损更倾向持有）；</li>
 *   <li><strong>前景理论/损失厌恶</strong>：下单量按前景价值缩放（亏损状态下单量受抑）。</li>
 * </ul>
 *
 * <p>确定性：种子 = {@code mix64(engineSeed ^ ("meso/inst/"+i).hashCode())}（§7.1-1），禁裸随机；
 * 内部记账按意图价乐观成交（合成流，不参与真实账本）。
 */
public class InstitutionalAgent {
    /** 机构风格。 */
    public enum Style { VALUE, GROWTH, MOMENTUM }

    // ---- 代码常量（先验，不调参；§9 风险表：杠杆上限 3.0） ----
    public static final double MIN_ORDER_SHARES = 5000;
    public static final double MAX_ORDER_SHARES = 50000;
    public static final double LEVERAGE_MIN = 1.0;
    public static final double LEVERAGE_MAX = 3.0;
    public static final double STOP_LOSS_RATIO = 0.88;      // 止损护栏：跌破成本 12%
    public static final double VALUE_BAND = 0.03;           // value 风格回归带（±3% 偏离）
    public static final double GROWTH_MOMENTUM_TRIGGER = 0.004; // growth 追涨动量阈值
    public static final double GROWTH_VALUATION_CAP = 1.3;  // growth 估值护栏（≤1.3×fair）
    public static final double MOMENTUM_ROTATION_TRIGGER = 0.08; // momentum 轮动跟随阈值

    private final int index;
    private final Style style;
    private final String homeIndustry;
    private final double baseLeverage;
    private final double aggressiveness;
    private final double baseCash;
    private final SeedableRandom rng;
    private final BehaviorUtility behaviorUtility;

    // ---- 状态（持久化，MP-5） ----
    private double cash;
    private final Map<String, Integer> holdings = new LinkedHashMap<>();
    private final Map<String, Double> avgCost = new LinkedHashMap<>();
    private double leverage = 1.0;
    private long lastDecisionTick = -1;
    private long tradeCount = 0;          // 过度交易观测（累计成交笔数意图）

    // ---- 行为统计（引擎级可观测，供验证 harness 检验处置效应/前景理论） ----
    private long sellDecisionsGain = 0;   // 盈利状态下卖出决策次数
    private long sellDecisionsLoss = 0;   // 亏损状态下卖出决策次数
    private long tradeGainState = 0;      // 盈利状态下成交意图笔数
    private long tradeLossState = 0;      // 亏损状态下成交意图笔数
    private double sizeGainState = 0.0;   // 盈利状态成交意图量合计
    private double sizeLossState = 0.0;   // 亏损状态成交意图量合计

    /**
     * @param engineSeed   引擎种子
     * @param index        机构序号（确定性派生种子）
     * @param homeIndustry 主场行业（由 MesoLayerManager 确定性分配）
     */
    public InstitutionalAgent(long engineSeed, int index, String homeIndustry) {
        this.index = index;
        this.homeIndustry = homeIndustry;
        this.rng = new SeedableRandom(SeedableRandom.mix64(engineSeed ^ ("meso/inst/" + index).hashCode()));
        this.behaviorUtility = new BehaviorUtility();
        // 风格确定性分配（三等分 + 抖动，同种子同序列）
        double r = rng.nextDouble();
        if (r < 0.34) this.style = Style.VALUE;
        else if (r < 0.67) this.style = Style.GROWTH;
        else this.style = Style.MOMENTUM;
        this.baseLeverage = 1.2 + rng.nextDouble() * 1.2;      // [1.2, 2.4]
        this.aggressiveness = 0.3 + rng.nextDouble() * 0.5;    // [0.3, 0.8]
        this.baseCash = 2_000_000 + rng.nextDouble() * 18_000_000;
        this.cash = baseCash;
    }

    public int getIndex() { return index; }
    public Style getStyle() { return style; }
    public String getHomeIndustry() { return homeIndustry; }
    public double getLeverage() { return leverage; }
    public double getCash() { return cash; }
    public long getTradeCount() { return tradeCount; }
    public Map<String, Integer> getHoldingsView() { return new LinkedHashMap<>(holdings); }
    public Map<String, Double> getAvgCostView() { return new LinkedHashMap<>(avgCost); }
    public long getSellDecisionsGain() { return sellDecisionsGain; }
    public long getSellDecisionsLoss() { return sellDecisionsLoss; }
    public long getTradeGainState() { return tradeGainState; }
    public long getTradeLossState() { return tradeLossState; }
    public double getSizeGainState() { return sizeGainState; }
    public double getSizeLossState() { return sizeLossState; }

    /**
     * 每宏观步驱动（低频）：消费宏观快照 + 板块动量 + 轮动偏移，输出机构大单 Intent。
     *
     * @param engine        引擎引用（只读：资产价格/fair 解析）
     * @param snap          宏观政策快照
     * @param sectorMomentum 主场行业板块动量
     * @param rotationOffset 主场行业轮动偏移 [-1,1]
     * @param genTick       确定性时间戳
     */
    public List<MesoOrderIntent> tick(MarketEngine engine, MacroSnapshot snap,
                                      double sectorMomentum, double rotationOffset, long genTick) {
        List<MesoOrderIntent> intents = new ArrayList<>();
        String assetId = pickRepAsset(engine);
        if (assetId == null) return intents;
        double px = engine.getState().getAsset(assetId) != null
                ? engine.getState().getAsset(assetId).getLastPrice() : 0.0;
        if (px <= 0) return intents;
        double fair = MesoLayerManager.fairOf(engine, assetId);
        if (fair <= 0) fair = px;

        // ---- 杠杆目标（对齐 EconomicCycle.leverageMultiplier：繁荣/顶部堆积、收缩/萧条去杠杆） ----
        double cycleLev = EconomicCycle.leverageMultiplier(snap.cyclePhase());
        double levTarget = clamp(baseLeverage * snap.liquidityMultiplier() * cycleLev, LEVERAGE_MIN, LEVERAGE_MAX);
        double prevLeverage = leverage;
        leverage = levTarget;

        int hold = holdings.getOrDefault(assetId, 0);
        double exposure = hold * px;
        double cost = avgCost.getOrDefault(assetId, 0.0);
        double gain = cost > 0 ? (px - cost) / cost : 0.0;

        // ---- 明斯基中观传导：目标杠杆下降且敞口超限 → 强制抛售去杠杆 ----
        boolean forceSell = false;
        if (levTarget < prevLeverage - 0.05 && hold > 0 && exposure > cash * levTarget) {
            forceSell = true;
        }

        // ---- 风格决策 ----
        boolean isBuy;
        if (forceSell) {
            isBuy = false;
        } else {
            double dev = (px - fair) / fair;
            switch (style) {
                case VALUE:
                    // 向 fairValue 回归：低估买/高估卖（低频大单）
                    if (dev < -VALUE_BAND) { isBuy = true; }
                    else if (dev > VALUE_BAND) { isBuy = false; }
                    else return intents;
                    break;
                case GROWTH:
                    // 追涨（带估值护栏）；止损护栏：跌破成本 12% 离场
                    if (cost > 0 && px < cost * STOP_LOSS_RATIO) { isBuy = false; }
                    else if (sectorMomentum > GROWTH_MOMENTUM_TRIGGER && px < fair * GROWTH_VALUATION_CAP) { isBuy = true; }
                    else return intents;
                    break;
                case MOMENTUM:
                default:
                    // 板块轮动跟随（SectorRotationModel 输出）
                    if (rotationOffset > MOMENTUM_ROTATION_TRIGGER) { isBuy = true; }
                    else if (rotationOffset < -MOMENTUM_ROTATION_TRIGGER) { isBuy = false; }
                    else return intents;
                    break;
            }
        }

        // ---- 处置效应卖出门控（非强制去杠杆时；盈利更倾向卖、亏损更倾向持有） ----
        if (!isBuy && !forceSell && hold > 0) {
            if (gain > 0) sellDecisionsGain++;
            else sellDecisionsLoss++;
            double sellMult = behaviorUtility.dispositionSellMultiplier(gain);
            double holdProb = 1.0 / Math.max(0.5, sellMult);   // 盈利(sellMult>1)→持有概率低→倾向卖
            holdProb = Math.max(0.0, Math.min(1.0, holdProb));
            if (rng.nextDouble() < holdProb) return intents;   // 处置效应：亏损扛单
        } else if (!isBuy && hold <= 0) {
            return intents; // 无持仓不可卖
        }

        // ---- 前景理论/损失厌恶：下单量按前景价值缩放（亏损压制、盈利略增） ----
        double prospectScale = 1.0 + 0.4 * Math.tanh(behaviorUtility.prospectValue(gain));
        int size = (int) (MIN_ORDER_SHARES + Math.abs(rng.nextGaussian()) * 15000);
        size = (int) Math.min(MAX_ORDER_SHARES, Math.max(MIN_ORDER_SHARES, size));
        size = (int) (size * prospectScale * (0.6 + 0.8 * aggressiveness));
        size = lotRound(size);
        if (size <= 0) return intents;

        // ---- 可买/可卖约束（杠杆放大购买力；去杠杆抛售更猛） ----
        if (isBuy) {
            double buyingPower = cash * leverage;
            size = lotRound((int) Math.min(size, Math.max(0, buyingPower / px)));
        } else {
            int toSell = forceSell ? hold : Math.min(size, hold);
            if (forceSell) {
                // 去杠杆：卖出敞口超出部分（不低于最小单量）
                double excessValue = Math.max(0, exposure - cash * levTarget);
                int excessQty = lotRound((int) (excessValue / px));
                toSell = Math.max(toSell, excessQty);
            }
            size = lotRound(Math.min(toSell, hold));
        }
        if (size <= 0) return intents;

        // ---- 价格：以 lastPrice 为锚小幅激进偏移（确定性 rng 子流） ----
        double offset = isBuy ? -(0.001 + rng.nextDouble() * 0.003) : (0.001 + rng.nextDouble() * 0.003);
        double price = round2(px * (1.0 + offset));
        if (price <= 0) return intents;

        // ---- 行为统计 + 内部记账（按意图价乐观成交；合成流不参与真实账本） ----
        if (gain > 0) { tradeGainState++; sizeGainState += size; }
        else { tradeLossState++; sizeLossState += size; }
        if (isBuy) {
            cash -= price * size;
            int oldHold = holdings.getOrDefault(assetId, 0);
            holdings.put(assetId, oldHold + size);
            avgCost.put(assetId, oldHold > 0 ? (cost * oldHold + price * size) / (oldHold + size) : price);
        } else {
            cash += price * size;
            int left = holdings.getOrDefault(assetId, 0) - size;
            if (left <= 0) { holdings.remove(assetId); avgCost.remove(assetId); }
            else holdings.put(assetId, left);
        }
        tradeCount++;
        lastDecisionTick = genTick;
        intents.add(new MesoOrderIntent(assetId, isBuy, size, price, "inst/" + index + "/" + style, genTick));
        return intents;
    }

    /** 主场行业代表资产：NAV 最大者（成员排序遍历，确定性）。 */
    private String pickRepAsset(MarketEngine engine) {
        String best = null;
        double bestNav = -1.0;
        for (String id : MesoLayerManager.membersOf(engine, homeIndustry)) {
            double nav = MesoLayerManager.navOf(engine, id);
            if (nav > bestNav) {
                bestNav = nav;
                best = id;
            }
        }
        return best;
    }

    private static int lotRound(int qty) {
        int lot = Math.max(1, PriceDiscoveryEngine.A_SHARE_BOARD_LOT);
        return qty >= lot ? (qty / lot) * lot : lot;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- 持久化（MP-5：机构现金/持仓/杠杆/风格；旧档缺字段 contains 兜底，读档不失败） ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("index", index);
        tag.putString("style", style.name());
        tag.putString("homeIndustry", homeIndustry);
        tag.putDouble("baseLeverage", baseLeverage);
        tag.putDouble("aggressiveness", aggressiveness);
        tag.putDouble("baseCash", baseCash);
        tag.putDouble("cash", cash);
        tag.putDouble("leverage", leverage);
        tag.putLong("lastDecisionTick", lastDecisionTick);
        tag.putLong("tradeCount", tradeCount);
        tag.putLong("sellDecisionsGain", sellDecisionsGain);
        tag.putLong("sellDecisionsLoss", sellDecisionsLoss);
        tag.putLong("tradeGainState", tradeGainState);
        tag.putLong("tradeLossState", tradeLossState);
        tag.putDouble("sizeGainState", sizeGainState);
        tag.putDouble("sizeLossState", sizeLossState);
        ListTag hList = new ListTag();
        for (Map.Entry<String, Integer> e : holdings.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", e.getKey());
            c.putInt("qty", e.getValue());
            hList.add(c);
        }
        tag.put("holdings", hList);
        ListTag cList = new ListTag();
        for (Map.Entry<String, Double> e : avgCost.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("id", e.getKey());
            c.putDouble("cost", e.getValue());
            cList.add(c);
        }
        tag.put("avgCost", cList);
        tag.putLong("rngState", rng.getState());
        tag.putDouble("spareGaussian", rng.getSpareGaussian());
        return tag;
    }

    /** 恢复机构状态；旧档缺字段用默认值兜底，读档不失败。 */
    public void load(CompoundTag tag) {
        if (tag == null) return;
        // 风格/主行业/base 参数为构造期确定性派生，读档以存档为准（contains 兜底）
        if (tag.contains("cash")) cash = tag.getDouble("cash");
        if (tag.contains("leverage")) leverage = tag.getDouble("leverage");
        if (tag.contains("lastDecisionTick")) lastDecisionTick = tag.getLong("lastDecisionTick");
        if (tag.contains("tradeCount")) tradeCount = tag.getLong("tradeCount");
        if (tag.contains("sellDecisionsGain")) sellDecisionsGain = tag.getLong("sellDecisionsGain");
        if (tag.contains("sellDecisionsLoss")) sellDecisionsLoss = tag.getLong("sellDecisionsLoss");
        if (tag.contains("tradeGainState")) tradeGainState = tag.getLong("tradeGainState");
        if (tag.contains("tradeLossState")) tradeLossState = tag.getLong("tradeLossState");
        if (tag.contains("sizeGainState")) sizeGainState = tag.getDouble("sizeGainState");
        if (tag.contains("sizeLossState")) sizeLossState = tag.getDouble("sizeLossState");
        holdings.clear();
        if (tag.contains("holdings")) {
            ListTag l = tag.getList("holdings", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                holdings.put(c.getString("id"), c.getInt("qty"));
            }
        }
        avgCost.clear();
        if (tag.contains("avgCost")) {
            ListTag l = tag.getList("avgCost", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                avgCost.put(c.getString("id"), c.getDouble("cost"));
            }
        }
        if (tag.contains("rngState")) rng.setState(tag.getLong("rngState"));
        if (tag.contains("spareGaussian")) rng.setSpareGaussian(tag.getDouble("spareGaussian"));
    }
}
