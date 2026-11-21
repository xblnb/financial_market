package com.example.financialmarket.engine;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

import java.util.*;

/**
 * 商品条目（物品或流体）。承载<b>经济学定价模型</b>（CF-3）：
 *
 * <p>价格由 <b>成本锚 + 供需缺口 + 宏观货币/通胀因子</b> 共同驱动：
 * <pre>
 *   excess      = (demand − supply) / (demand + supply)          // 供需缺口 ∈ (-1,1)
 *   anchorTerm  = costAnchor × (1 + PRICE_DRIFT_TO_COST × excess) // 围绕生产成本锚浮动
 *   price       = clamp(anchorTerm × macroFactor, costAnchor×FLOOR, costAnchor×CAP)
 * </pre>
 * 确定性、无随机；excess→0 时价格收敛到 {@code costAnchor×macroFactor}，且有界。
 *
 * <p>{@code basePrice} 为经济学基价（配方成本 / 稀有度档，见 {@link CommodityRegistry#basePriceOf}），
 * 用于估值（{@code AssetScanner}）；{@code costAnchor} 为<b>生产成本锚</b>，随上游配方价演化
 * （成本推动型通胀）；{@code macroFactor} 为宏观货币/通胀因子（1=中性，只读宏观，不改宏观公式）。
 */
public class CommodityEntry {
    // ===== 经济学定价参数（§2.4，可调常量） =====
    /** 供需缺口对成本锚的浮动系数（围绕成本锚的供需弹性）。 */
    public static final double PRICE_DRIFT_TO_COST = 0.20;
    /** 价格下界 = costAnchor × PRICE_FLOOR_RATIO。 */
    public static final double PRICE_FLOOR_RATIO = 0.2;
    /** 价格上界 = costAnchor × PRICE_CAP_RATIO。 */
    public static final double PRICE_CAP_RATIO = 5.0;
    /** 成本锚黏性：每日向最新原料成本收敛的比例（成本推动，避免瞬时跳变）。 */
    public static final double COST_ANCHOR_CONVERGE_RATE = 0.20;

    private final Item item;              // 非空（kind=item）
    private final Fluid fluid;            // 非空（kind=fluid）
    private final String kind;            // "item" | "fluid"
    private final String key;             // 唯一身份键（持久化）

    private double price;
    private double basePrice;             // 经济学基价（配方成本 / 稀有度档，估值权威）
    private double costAnchor;            // 生产成本锚（随上游价演化，成本推动）
    private double supply;
    private double demand;
    private double macroFactor = 1.0;     // 宏观货币/通胀因子（1=中性）
    private double prevSettlePrice;       // 上一商品结算价（供期货锚定，默认=basePrice）
    private long dailyVolume;
    private final String[] industries;
    private double fairValue;
    private double momentum;
    private double volatility = 0.015;
    private double seasonality;
    private double lastReturn;
    private final Deque<Double> history = new ArrayDeque<>();
    private boolean manualAnchor;

    public CommodityEntry(Item item, double basePrice, String... industries) {
        this.item = item;
        this.fluid = null;
        this.kind = "item";
        this.key = "item:" + BuiltInRegistries.ITEM.getKey(item);
        this.basePrice = round2(basePrice);
        this.costAnchor = this.basePrice;
        this.price = this.basePrice;
        this.prevSettlePrice = this.basePrice;
        this.fairValue = this.basePrice;
        this.history.addLast(this.basePrice);
        this.supply = 1000.0;
        this.demand = 1000.0;
        this.industries = industries;
    }

    public CommodityEntry(Fluid fluid, double basePrice, String... industries) {
        this.item = null;
        this.fluid = fluid;
        this.kind = "fluid";
        this.key = "fluid:" + BuiltInRegistries.FLUID.getKey(fluid);
        this.basePrice = round2(basePrice);
        this.costAnchor = this.basePrice;
        this.price = this.basePrice;
        this.prevSettlePrice = this.basePrice;
        this.fairValue = this.basePrice;
        this.history.addLast(this.basePrice);
        this.supply = 1000.0;
        this.demand = 1000.0;
        this.industries = industries;
    }

    // ===== 读取 =====
    public Item getItem() { return item; }
    public Fluid getFluid() { return fluid; }
    public String getKind() { return kind; }
    public String getKey() { return key; }
    public double getPrice() { return price; }
    public double getBasePrice() { return basePrice; }
    public double getCostAnchor() { return costAnchor; }
    public double getSupply() { return supply; }
    public double getDemand() { return demand; }
    public double getMacroFactor() { return macroFactor; }
    public double getPrevSettlePrice() { return prevSettlePrice; }
    public String[] getIndustries() { return industries; }
    public long getDailyVolume() { return dailyVolume; }
    public double getFairValue() { return fairValue; }
    public double getMomentum() { return momentum; }
    public double getVolatility() { return volatility; }
    public double getSeasonality() { return seasonality; }
    public double getLastReturn() { return lastReturn; }
    public List<Double> getHistory() { return new ArrayList<>(history); }
    public boolean isManualAnchor() { return manualAnchor; }
    public void markManualAnchor() { this.manualAnchor = true; }

    /** True while the entry has not seen trading or a dynamic move; safe to reprice at bootstrap. */
    public boolean isPristine() {
        return dailyVolume == 0 && history.size() <= 1
                && Math.abs(price - basePrice) < 1e-9
                && Math.abs(fairValue - basePrice) < 1e-9;
    }

    /** Bootstrap reprice from the recipe graph; only used on pristine/new entries. */
    public void resetInitialPrice(double value) {
        double v = Math.max(0.01, value);
        this.basePrice = round2(v);
        this.costAnchor = this.basePrice;
        this.price = this.basePrice;
        this.fairValue = this.basePrice;
        this.prevSettlePrice = this.basePrice;
        this.momentum = 0.0;
        this.volatility = 0.015;
        this.seasonality = 0.0;
        this.lastReturn = 0.0;
        this.history.clear();
        this.history.addLast(this.price);
    }

    // ===== 供需压力 / 成交 =====
    public void buyPressure(int quantity) {
        demand += quantity * 0.1;
        dailyVolume += quantity;
        updatePrice();
    }

    public void sellPressure(int quantity) {
        supply += quantity * 0.1;
        dailyVolume += quantity;
        updatePrice();
    }

    /** 外部因素调整供需（如合成上下游传播）。 */
    public void adjustDemand(double delta) { demand += delta; updatePrice(); }
    public void adjustSupply(double delta) { supply += delta; updatePrice(); }

    /** 需求按倍率缩放（经济周期对需求侧增益，CF-3）。 */
    public void multiplyDemand(double factor) { demand = Math.max(0, demand * factor); updatePrice(); }

    // ===== 成本锚 / 宏观 / 结算（确定性） =====
    /** 设置宏观货币/通胀因子并重算价格。 */
    public void setMacroFactor(double factor) { this.macroFactor = factor; updatePrice(); }

    /**
     * 成本锚演化（成本推动）：每日向最新原料配方成本黏性收敛。
     * 确定性；无配方（ingredientCost<=0）时保持当前锚。
     */
    public void updateCostAnchor(double ingredientCost) {
        if (ingredientCost <= 0) return;
        costAnchor = round2(costAnchor + (ingredientCost - costAnchor) * COST_ANCHOR_CONVERGE_RATE);
        if (costAnchor < 0.01) costAnchor = 0.01;
        updatePrice();
    }

    /** 上一商品结算价（供期货锚定）。 */
    public void setPrevSettlePrice(double prev) { this.prevSettlePrice = round2(prev); }

    /**
     * 应用配方成本基价：设置 economics basePrice；若成本锚/昨结尚未初始化则以其为初始值。
     * 确定性纯函数（配方图固定 → 结果固定）。
     */
    public void applyCostBase(double cost) {
        double c = Math.max(0.01, cost);
        this.basePrice = round2(c);
        if (this.costAnchor <= 0) this.costAnchor = this.basePrice;
        if (this.prevSettlePrice <= 0) this.prevSettlePrice = this.basePrice;
        updatePrice();
    }

    // ===== 经济学定价模型（确定性、无随机） =====
    private void updatePrice() {
        double s = supply + 1.0;
        double d = demand + 1.0;
        double excess = (d - s) / (s + d);                 // ∈ (-1,1)
        double anchorTerm = costAnchor * (1.0 + PRICE_DRIFT_TO_COST * excess);
        double p = anchorTerm * macroFactor;
        double lo = costAnchor * PRICE_FLOOR_RATIO;
        double hi = costAnchor * PRICE_CAP_RATIO;
        this.fairValue = round2(clamp(p, lo, hi));
        // Sticky quote: a single trade/cost update moves only partway to fair value; the daily
        // mean-reversion model closes the rest. This avoids every click snapping the price.
        price = roundPrice(clamp(price + 0.35 * (this.fairValue - price), lo, hi));
    }

    /**
     * Daily commodity-market tick: mean reversion to fair value + momentum + seasonality +
     * deterministic volatility clustering. No Math.random: same key/day always yields the same
     * shock, so server restarts stay reproducible.
     */
    public void dailyTick(long day, double macroFactor) {
        double lo = costAnchor * PRICE_FLOOR_RATIO;
        double hi = costAnchor * PRICE_CAP_RATIO;
        double fair = CommodityPriceMath.fairValue(costAnchor, supply, demand, macroFactor,
                PRICE_DRIFT_TO_COST);
        fair = clamp(fair, lo, hi);
        CommodityPriceMath.Step step = CommodityPriceMath.step(price, fair, momentum,
                volatility, key, day, lo, hi);
        this.fairValue = step.fairValue();
        this.lastReturn = step.lastReturn();
        this.momentum = step.momentum();
        this.volatility = step.volatility();
        this.seasonality = step.seasonality();
        this.price = step.price();
        history.addLast(price);
        while (history.size() > 120) history.removeFirst();
    }

    /** 供需缓慢回归（市场出清，decay 向中性收敛）。 */
    public void decay() {
        supply = supply * 0.999 + 1;
        demand = demand * 0.999 + 1;
        updatePrice();
    }

    public void resetDailyVolume() { dailyVolume = 0; }

    // ===== 持久化辅助（供 CommodityMarket.save/load） =====
    public void restoreState(double price, double basePrice, double costAnchor,
                             double supply, double demand, double macroFactor,
                             double prevSettlePrice, long dailyVolume) {
        this.price = price;
        this.basePrice = basePrice;
        this.costAnchor = costAnchor;
        this.supply = supply;
        this.demand = demand;
        this.macroFactor = macroFactor;
        this.prevSettlePrice = prevSettlePrice;
        this.dailyVolume = dailyVolume;
        this.fairValue = basePrice;
    }

    public void restoreDynamics(double fairValue, double momentum, double volatility,
                                double seasonality, double lastReturn) {
        this.fairValue = fairValue;
        this.momentum = momentum;
        this.volatility = Math.max(0.003, Math.min(0.20, volatility));
        this.seasonality = seasonality;
        this.lastReturn = lastReturn;
    }

    public void restoreHistory(List<Double> values) {
        history.clear();
        if (values != null) {
            for (double v : values) {
                if (history.size() >= 120) break;
                history.addLast(v);
            }
        }
        if (history.isEmpty()) history.addLast(price);
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double roundPrice(double v) {
        if (Math.abs(v) < 10.0) return Math.round(v * 10000.0) / 10000.0;
        return round2(v);
    }
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
