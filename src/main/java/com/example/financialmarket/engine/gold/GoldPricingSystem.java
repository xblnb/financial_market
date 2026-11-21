package com.example.financialmarket.engine.gold;

import com.example.financialmarket.engine.CommodityEntry;
import com.example.financialmarket.engine.CommodityMarket;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.MarketSentiment;
import com.example.financialmarket.engine.banking.CentralBank;
import com.example.financialmarket.engine.banking.InflationSystem;
import com.example.financialmarket.engine.crisis.CrisisEngine;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;

/**
 * 黄金定价体系（下界合金锭模拟现代黄金；本 spec 需求②。
 *
 * <p>下界合金锭从「普通商品」升格为「现代黄金」角色：其价格由<b>锚定基准价</b>（{@link #GOLD_BENCHMARK}，
 * = 下界合金锭下界稀有度档 60）乘以 <b>goldMultiplier</b> 得到（{@code goldPrice = goldBenchmark × goldMultiplier}），
 * 而 {@code goldMultiplier} 由<b>避险 / 通胀(抗通胀) / 实际利率(负实际利率) / 央行净购金</b>四类宏观驱动。
 * <b>非金本位固定汇率</b>：黄金有基准锚但不作固定汇率，随上述驱动<b>有界可波动</b>（参照百度百科「黄金定价体系」）。
 *
 * <p><b>确定性</b>：定价为<b>闭式纯函数</b>，零 {@code Math.random}；同 {@code engineSeed} 同 tick 序列逐位可复现（NG-7）。
 * 引擎种子仅用于派生子流（保留），当前公式非随机。
 *
 * <p><b>落在商品层（正交增量，不改商品公式）</b>：本系统在交易日边界把 {@code goldMultiplier} 作为
 * <b>macroFactor 输入</b>注入下界合金锭商品条目（{@link CommodityEntry#setMacroFactor(double)}），
 * 于是下界合金锭现货价 = {@code costAnchor×(1+0.2×excess)×goldMultiplier}，围绕成本锚随 {@code goldMultiplier} 波动
 * （锚定但可波动、非固定汇率）。<b>不改动</b> {@code CommodityEntry.updatePrice} / 商品宏观因子公式（NG-6）。
 *
 * <p><b>单向依赖</b>：本层只读消费 {@code MarketSentiment.globalSentiment}、{@code CrisisEngine.crisisLevel}、
 * {@code InflationSystem.inflationRate}、{@code CentralBank.discountRate}（政策名义利率）、
 * {@code CentralBank.goldMarketBuyAmount}（央行净购金）；不写回任何宏观公式。
 */
public class GoldPricingSystem {
    // ===== gold.* 可调参数（默认值见 NETHERITE_GOLD_SPEC §3.2.2） =====
    /** 锚定基准价 = 下界合金锭下界(稀有度档, CommodityRegistry.rarityTier) = 60.0。 */
    public static final double GOLD_BENCHMARK = 60.0;
    /** goldMultiplier 下界（防金价崩塌）。 */
    public static final double GOLD_FLOOR = 0.5;
    /** goldMultiplier 上界（防金价抛物线发散）。 */
    public static final double GOLD_CAP = 5.0;
    /** 市场恐慌阈值（globalSentiment 低于此值视为避险启动）。 */
    public static final double PANIC_THRESHOLD = 0.45;
    /** 避险权重。 */
    public static final double W_SAFE_HAVEN = 1.2;
    /** 通胀(抗通胀)权重。 */
    public static final double W_INFLATION = 0.8;
    /** 实际利率(负实际利率)权重。 */
    public static final double W_REAL_RATE = 1.5;
    /** 央行净购金权重。 */
    public static final double W_CB_BUY = 0.9;
    /** 净购金归一化分母（≈ 典型日购金）。 */
    public static final double CB_BUY_SCALE = 1000.0;

    private final long seed;              // 引擎种子派生子流（当前公式零随机，保留供未来子流）
    private double goldBenchmark = GOLD_BENCHMARK;
    private double goldPrice = GOLD_BENCHMARK;   // = goldBenchmark × goldMultiplier（有界）
    private double goldMultiplier = 1.0;          // 1.0=中性
    private double prevGoldPrice = GOLD_BENCHMARK; // 上一交易日黄金价（供期货/前端昨结）
    // 只读输入快照（诊断/持久化）
    private double lastInflationRate = 0.02;
    private double lastRealRate = 0.01;
    private double lastGlobalSentiment = 0.7;
    private double lastCrisisLevel = 0.0;
    private double lastNetCbBuy = 0.0;

    public GoldPricingSystem(long seed) {
        this.seed = seed;
    }

    /**
     * 交易日边界执行一次：读取只读宏观输入 → 闭式计算 {@code goldMultiplier}/{@code goldPrice} → 注入普通商品
     * 下界合金锭条目的 {@code macroFactor}。
     *
     * <p>调用时机：{@link MarketEngine#tick} 在 {@code commodityMarket.tick} 之后、同一交易日边界调用（见 spec §3.5.2）。
     * 在此之后再由引擎调用 {@code centralBank.resetGoldMarketFlow()} 清零当日净购金，形成「购金→次日定价→清零」的
     * 确定性日循环。
     */
    public void tick(MarketEngine engine) {
        InflationSystem inf = engine.getInflationSystem();
        CentralBank cb = engine.getCentralBank();
        MarketSentiment s = engine.getSentiment();
        CrisisEngine c = engine.getCrisisEngine();

        double inflationRate = inf.getInflationRate();
        double realRate = realRate(cb, inf);
        double globalSentiment = s != null ? s.getGlobalSentiment() : 0.7;
        double crisisLevel = c != null ? c.getCrisisLevel() : 0.0;
        double netCbBuy = cb.getGoldMarketBuyAmount();

        double safeHaven = computeSafeHaven(s, c);
        double inflationTerm = computeInflation(inf);
        double realRateTerm = computeRealRate(cb, inf);
        double cbBuy = computeCbBuy(cb);

        this.prevGoldPrice = this.goldPrice;
        this.goldMultiplier = clamp(1.0 + safeHaven + inflationTerm + realRateTerm + cbBuy, GOLD_FLOOR, GOLD_CAP);
        this.goldPrice = round2(this.goldBenchmark * this.goldMultiplier);

        this.lastInflationRate = inflationRate;
        this.lastRealRate = realRate;
        this.lastGlobalSentiment = globalSentiment;
        this.lastCrisisLevel = crisisLevel;
        this.lastNetCbBuy = netCbBuy;

        cb.setCurrentGoldPrice(this.goldPrice);

        // 落地到 netherite 商品条目（复用既有 macroFactor 输入；不改 updatePrice 公式，NG-6）
        CommodityMarket cm = engine.getCommodityMarket();
        if (cm != null) {
            CommodityEntry entry = cm.getCommodity(Items.NETHERITE_INGOT);
            if (entry != null) {
                entry.setMacroFactor(this.goldMultiplier);
            }
        }
    }

    // ===== 读取 =====
    public double goldPrice() { return goldPrice; }
    public double goldMultiplier() { return goldMultiplier; }
    public double goldBenchmark() { return goldBenchmark; }
    public double prevGoldPrice() { return prevGoldPrice; }
    public long getSeed() { return seed; }
    // 只读输入快照（诊断/持久化）
    public double getLastInflationRate() { return lastInflationRate; }
    public double getLastRealRate() { return lastRealRate; }
    public double getLastGlobalSentiment() { return lastGlobalSentiment; }
    public double getLastCrisisLevel() { return lastCrisisLevel; }
    public double getLastNetCbBuy() { return lastNetCbBuy; }

    // ===== 定价分量（各自只读单变量，确定性闭式；见 spec §3.2.4） =====
    /** 实际利率 = 政策名义利率 − 通胀率。 */
    public double realRate(CentralBank cb, InflationSystem inf) {
        return cb.getDiscountRate() - inf.getInflationRate();
    }

    /** 避险分量：市场恐慌（globalSentiment 低于阈值）+ 危机强度。 */
    private double computeSafeHaven(MarketSentiment s, CrisisEngine c) {
        double globalSentiment = (s != null) ? s.getGlobalSentiment() : 0.7;
        double crisisLevel = (c != null) ? c.getCrisisLevel() : 0.0;
        double panic = clamp((PANIC_THRESHOLD - globalSentiment) / PANIC_THRESHOLD, 0.0, 1.0);
        return W_SAFE_HAVEN * panic + W_SAFE_HAVEN * 0.8 * crisisLevel;
    }

    /** 抗通胀分量：高通胀 → 抗通胀买金。 */
    private double computeInflation(InflationSystem inf) {
        return W_INFLATION * clamp(inf.getInflationRate(), 0.0, 0.5);
    }

    /** 实际利率分量：负实际利率 → 持有黄金。 */
    private double computeRealRate(CentralBank cb, InflationSystem inf) {
        double realRate = realRate(cb, inf);
        return W_REAL_RATE * clamp(0.0 - realRate, 0.0, 0.10);
    }

    /** 央行净购金分量：净购金 → 金价上行。 */
    private double computeCbBuy(CentralBank cb) {
        double netCbBuy = cb.getGoldMarketBuyAmount();
        return W_CB_BUY * clamp(netCbBuy / CB_BUY_SCALE, 0.0, 1.0);
    }

    // ===== 持久化（H2/NBT 兼容，缺字段兜底，NG-8） =====
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("seed", seed);
        t.putDouble("goldBenchmark", goldBenchmark);
        t.putDouble("goldPrice", goldPrice);
        t.putDouble("goldMultiplier", goldMultiplier);
        t.putDouble("prevGoldPrice", prevGoldPrice);
        t.putDouble("lastInflationRate", lastInflationRate);
        t.putDouble("lastRealRate", lastRealRate);
        t.putDouble("lastGlobalSentiment", lastGlobalSentiment);
        t.putDouble("lastCrisisLevel", lastCrisisLevel);
        t.putDouble("lastNetCbBuy", lastNetCbBuy);
        return t;
    }

    /** 恢复黄金定价状态；旧存档缺字段用默认值兜底（contains），读档不失败（NG-8）。 */
    public void load(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("goldBenchmark")) goldBenchmark = tag.getDouble("goldBenchmark");
        if (tag.contains("goldMultiplier")) goldMultiplier = tag.getDouble("goldMultiplier");
        if (tag.contains("prevGoldPrice")) prevGoldPrice = tag.getDouble("prevGoldPrice");
        if (tag.contains("lastInflationRate")) lastInflationRate = tag.getDouble("lastInflationRate");
        if (tag.contains("lastRealRate")) lastRealRate = tag.getDouble("lastRealRate");
        if (tag.contains("lastGlobalSentiment")) lastGlobalSentiment = tag.getDouble("lastGlobalSentiment");
        if (tag.contains("lastCrisisLevel")) lastCrisisLevel = tag.getDouble("lastCrisisLevel");
        if (tag.contains("lastNetCbBuy")) lastNetCbBuy = tag.getDouble("lastNetCbBuy");
        // goldPrice 兜底重建：旧档缺 goldPrice 时按基准×乘数重算（确定性）
        if (tag.contains("goldPrice")) {
            goldPrice = tag.getDouble("goldPrice");
        } else {
            goldPrice = round2(goldBenchmark * goldMultiplier);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
