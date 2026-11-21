package com.example.financialmarket.engine;

import com.example.financialmarket.engine.macro.EconomicCycleEngine;
import com.example.financialmarket.engine.macro.MacroEnvironment;
import com.example.financialmarket.engine.macro.MacroStockTransmission;
import com.example.financialmarket.engine.random.SeedableRandom;
import com.example.financialmarket.engine.tabl.TablPriceModel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PriceDiscoveryEngine {
    // ---------- 参数 ----------
    private static final double TICKS_PER_DAY = 24000.0;
    private static final int TICKS_PER_BAR = 20;
    private static final int TICK_BAR_CACHE_LIMIT = 500;
    // F-1（冒烟长跑发散根因修复）：每 tick 的 GBM 时间步 = 1/24000（模型年）。此前误用
    // TICKS_PER_BAR/24000 = 1/1200（一根 K 线的时间跨度）作为「每 tick」的 dt —— step() 每引擎 tick
    // 都执行，等效于每天按 20 年复利 → 宏观漂移（±20% 钳制、基线 ~6%）在长跑（tick rate 200，
    // ~144k tick）累积 exp(0.08×120)≈×20000 发散（指数 57M/AI 报价 1E7/基金 AUM 2.5T 失控，而
    // 成交价被涨跌停带锚在 ~7.5）。与宏观时钟一致：MacroEnvironment.DT_MACRO = 20/24000 per 20-tick 步
    // ⇒ 每 tick = 1/24000。GBM-GARCH 公式本身不变（欧拉离散，仅修正 dt 量纲）。
    private static final double DT = 1.0 / TICKS_PER_DAY; // 每 tick 的时间步（模型年；1 模型年 = 24000 tick）

    // ---------- P0-7 引擎种子（用于派生每资产子流，保证可复现） ----------
    private final long engineSeed;

    // ---------- 宏观环境引用 ----------
    private MacroEnvironment macroEnv;

    // ---------- TABL 价格模型（默认主路径；手写 GBM/GARCH 保留为 fallback） ----------
    private TablPriceModel tablModel;
    private boolean tablEnabled = Boolean.parseBoolean(
            System.getProperty("financialmarket.tabl.enabled", "true"));

    // ---------- A 股涨跌停模式（AS-2） ----------
    // 开启时 getLimitUp/getLimitDown 返回固定 ±10%（锚 prevClose 的钳制在 MarketEngine/OrderBook 消费端）；
    // 关闭时回落 MacroEnvironment 动态阈值（A3-7，隔离）。
    private boolean aShareEnabled = true;

    // ---------- A 股固定涨跌停带（AS-2）常量与纯函数 ----------
    // 全部 static 纯函数，无状态、无随机 → 同种子可复现（A3-1）。涨停/跌停锚 prevClose（非 lastPrice）。
    /** 固定 ±10%（A 股主板）。 */
    public static final double A_SHARE_LIMIT_RATIO = 0.10;
    /** 报价最小变动 0.01。 */
    public static final double A_SHARE_TICK_SIZE = 0.01;

    /**
     * MS-5b 价格连续性约束常数 M：单笔 log 收益上限 |log(S_t/S_{t-1})| ≤ M（默认 0.05 ≈ 5%/bar）。
     * 纯确定性，无随机；配合 Kalman 软启动压制开局跳空/涨停/跌停。
     */
    public static final double CONTINUITY_MAX_LOG_RETURN = 0.05;
    /** 一手 = 100 股（AS-4：买必须 100 整数倍、卖可零股）。 */
    public static final int A_SHARE_BOARD_LOT = 100;

    /** 涨停价 = round2(prevClose × (1+A_SHARE_LIMIT_RATIO))。 */
    public static double aShareLimitUp(double prevClose) {
        return round2(prevClose * (1.0 + A_SHARE_LIMIT_RATIO));
    }

    /** 跌停价 = round2(prevClose × (1−A_SHARE_LIMIT_RATIO))。 */
    public static double aShareLimitDown(double prevClose) {
        return round2(prevClose * (1.0 - A_SHARE_LIMIT_RATIO));
    }

    /** 报价是否落在涨跌停带 [down, up] 内（闭区间，端点=涨停/跌停价可下单排队）。 */
    public static boolean aShareWithinBand(double price, double up, double down) {
        return price >= down && price <= up;
    }

    /** 钳制：把成交价/报价夹到 [down, up]（越界取端点，价格不越过涨跌停）。 */
    public static double aShareClamp(double price, double up, double down) {
        return Math.max(down, Math.min(up, price));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ---------- GBM 状态 ----------
    private static class GbmState {
        double price;
        double mu;      // 当前漂移率 (由宏观动态更新)
        double sigma;   // 当前波动率
        long lastUpdateTick;
        // ---- GARCH(1,1) 条件方差状态 (P0-1) ----
        double sigmaSq;      // 条件方差 σ_t²
        double lastEpsilon;  // 上一步标准化收益冲击 ε_{t-1}
        double garchOmega;   // GARCH 常数项 ω
        double garchAlpha;   // α
        double garchBeta;    // β
        SeedableRandom rng;  // 该资产的子流 PRNG（可播种、可复现）

        GbmState(double price, double mu, double sigma, long seed) {
            this.price = price;
            this.mu = mu;
            this.sigma = sigma;
            this.lastUpdateTick = 0;
            this.rng = new SeedableRandom(seed);
            // GARCH 参数用可播种随机初始化
            this.garchAlpha = 0.05 + rng.nextDouble() * 0.10;   // ∈ (0.05, 0.15)
            this.garchBeta  = 0.80 + rng.nextDouble() * 0.13;   // ∈ (0.80, 0.93)
            // 硬约束 α+β<1 保证平稳
            if (garchAlpha + garchBeta >= 1.0) {
                garchBeta = Math.min(0.93, 1.0 - garchAlpha - 0.01);
            }
            this.sigmaSq = sigma * sigma;
            this.lastEpsilon = 0.0;
            // 无条件方差初值锚定注册时 volatility；后续由 step() 用宏观基线重锚 ω
            this.garchOmega = Math.max(1e-10, sigmaSq * (1.0 - garchAlpha - garchBeta));
        }
    }
    private final Map<String, GbmState> gbmStates = new ConcurrentHashMap<>();

    // ---------- 原有数据 ----------
    private final Map<String, Double> lastPrices = new ConcurrentHashMap<>();
    private final Map<String, List<TradePoint>> tradePoints = new ConcurrentHashMap<>();
    private final Map<String, List<TickData>> tickBuffers = new ConcurrentHashMap<>();
    private final Map<String, Map<KlineType, LinkedList<OHLCData>>> klineCache = new ConcurrentHashMap<>();
    // FIX-4：实时成交聚合到 TICK K线的增量状态。每资产当前正在构建（未满一根）的 TICK bar 及其成交笔数。
    private final Map<String, OHLCData> partialTickBars = new ConcurrentHashMap<>();
    private final Map<String, Integer> partialTickCounts = new ConcurrentHashMap<>();

    private MarketSentiment sentiment;
    private EconomicCycleEngine cycleEngine; // 保留，但宏观因子由 MacroEnvironment 主导
    private MarketState marketState;
    private int tickCounter = 0;
    private long currentTradingDay = 0;
    private long nextOrderId = 1;

    private final Map<String, Double> dailyOpen = new ConcurrentHashMap<>();
    private final Map<String, Double> dailyClose = new ConcurrentHashMap<>();
    private final Map<String, Boolean> dailyFirstTrade = new ConcurrentHashMap<>();

    // ---------- 订单流缓存 ----------
    private final Map<String, List<OrderFlow>> pendingOrderFlows = new ConcurrentHashMap<>();
    private final Map<String, Double> crossAssetTilts = new ConcurrentHashMap<>();

    // ---------- 反馈参数 ----------
    private static final double FEEDBACK_ALPHA = 0.02;

    // ----- 资产差异化漂移（MS-AI-1：市场有涨有跌，非全市场共同模）-----
    // 根因（spec §2.2）：旧代码每步 gbm.mu = 宏观 μ（所有资产同一值），全市场共同模 → 无差异性、涨跌趋同。
    // 每资产叠加一个从引擎种子+资产ID确定性派生（mix64）的个体漂移偏移，使不同资产净漂移分化。
    private static final double ASSET_DRIFT_SPREAD = 0.06;

    // ----- 冷启动参数 (MS-6 卡尔曼初始化) -----
    // 噪声乘数 = 1 - exp(-(t/τ)²)，τ 为噪声增长时间常数（默认 ≈50）。
    // t 小时乘数≈0 → 价格由宏观漂移 μ 确定性演化；t 超过 τ 后乘数渐近 1 → 恢复 GBM 随机游走。
    // 修正前公式把 ratio 除以 KALMAN_WARMUP_STEPS，等效 τ≈5000，且在 t=100 处硬跳变回 1.0。
    private int startupTick = 0;
    private static final int KALMAN_WARMUP_STEPS = 100; // 语义保留：软启动观察窗口（不再硬钳乘数）
    private static final double NOISE_RAMP_TAU = 50.0;  // 噪声增长时间常数 τ
    // 真实生效的 τ（可配置，默认 50），与 NOISE_RAMP_TAU 保持一致。
    private double kalmanNoiseTau = NOISE_RAMP_TAU;

    // F3.3 波动率生理量级（t7）：GBM 每 tick 波动上限（年化）。GARCH 突发可使条件方差脉冲到 ~0.8+，
    // 使每 tick 收益达 2%+，被 MS-5b 连续性钳制(M=0.05)大量截尾 → 收益尾被削薄（κ 退化为 ~2.6 亚高斯）。
    // 把 gbm.sigma 钳到生理区间 [0, 上限]，保证连续性钳制仅在真极端事件触发，GARCH 肥尾与波动聚集保留。
    private static final double PHYSIOLOGICAL_SIGMA_MAX = 0.35;
    // F3.4 市场-基本面耦合（t7，价格发现）：每个 GBM 步把 state 的可观测市场价 lastPrice 向 gbm.price
    // 均值回归一步（k=0.8/tick，强耦合 → 可观测价格紧贴基本面 GBM，收益近白噪 + GARCH 肥尾）。
    // RC-1/t5 根因之一即「市场价被自参照订单簿钉在稳定锚，与 GBM 基本面解耦 → tick 级收益大量为 0
    // （成交稀疏+价格冻结）→ raw-LB/κ/量-波退化」。强耦合让可观测价格围绕基本面双向波动、连续非零，
    // 价格发现回归基本面；订单簿真实成交仍驱动盘口/羊群/价差等微观事实。
    // 确定性：gbm.price 确定性演化，k 为常量，无随机。不改 GBM-GARCH step() 演化公式。
    private static final double FUNDAMENTAL_COUPLING = 0.8;

    // ---------- 可播种 PRNG (SplitMix64) ----------
    /** SplitMix64 终混散列：将任意 long 派生为独立流种子（稳定、可复现）。 */
    private static long mix64(long x) {
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    public PriceDiscoveryEngine() {
        this(0L);
    }

    /** P0-7：以指定引擎种子构造；每资产子流 seed = mix64(engineSeed ^ id)。 */
    public PriceDiscoveryEngine(long engineSeed) {
        this.engineSeed = engineSeed;
    }

    // ===================== 公共方法 =====================
    public void setMacroEnvironment(MacroEnvironment env) {
        this.macroEnv = env;
    }

    /** Cross-asset macro tilt from world/futures/options/player flows; bounded by the producer. */
    public void setCrossAssetTilt(String assetId, double tilt) {
        if (assetId == null) return;
        if (tilt == 0.0) crossAssetTilts.remove(assetId);
        else crossAssetTilts.put(assetId, tilt);
    }

    public void clearCrossAssetTilts() {
        crossAssetTilts.clear();
    }

    public void setTablModel(TablPriceModel model) {
        this.tablModel = model;
    }

    public TablPriceModel getTablModel() {
        return tablModel;
    }

    public void setTablEnabled(boolean enabled) {
        this.tablEnabled = enabled;
    }

    public boolean isTablEnabled() {
        return tablEnabled;
    }

    public void setMacroDependencies(MarketSentiment sentiment, EconomicCycleEngine cycleEngine, MarketState state) {
        this.sentiment = sentiment;
        this.cycleEngine = cycleEngine;
        this.marketState = state;
    }

    /** A 股涨跌停模式开关（开启时固定 ±10%，关闭回落宏观动态阈值，A3-7）。 */
    public void setAShareEnabled(boolean enabled) {
        this.aShareEnabled = enabled;
    }

    /** 当前 A 股涨跌停模式是否开启。 */
    public boolean isAShareEnabled() {
        return aShareEnabled;
    }

    // ----- 种子价格播种 -----
    public void seedPrice(String assetId, double price) {
        GbmState gbm = gbmStates.get(assetId);
        if (gbm != null) {
            gbm.price = price;
            lastPrices.put(assetId, price);
        }
    }

    // ----- 卡尔曼噪声乘数 (MS-6) -----
    // 1 - exp(-(t/τ)²)：t 小（前 ~100 步）乘数≈0（近确定性，价格由宏观漂移 μ 主导），
    // t 超过 τ(≈50) 后乘数平滑趋近 1 恢复随机游走。无硬跳变（去掉原 :179 在 t>=100 瞬跳 1.0）。
    private double getNoiseMultiplier() {
        double ratio = (double) startupTick / kalmanNoiseTau;
        return 1.0 - Math.exp(-ratio * ratio);
    }

    /** 配置卡尔曼噪声时间常数 τ（MS-6）；默认 50。确定性：仅影响软启动曲线，无随机。 */
    public void setKalmanNoiseTau(double tau) {
        if (tau > 1e-6) this.kalmanNoiseTau = tau;
    }

    /** 读取当前卡尔曼噪声时间常数 τ（供诊断/验收）。 */
    public double getKalmanNoiseTau() {
        return kalmanNoiseTau;
    }

    /**
     * MS-5b 价格连续性钳制：把目标价 target 相对上一价 last 的 log 收益钳到 ±M。
     * 若 last<=0 或 target<=0 则原样返回（无前价/无意义时跳过），否则
     * {@code target = last * exp(clamp(log(target/last), -M, +M))}，纯确定性无随机。
     */
    public static double clampForContinuity(double target, double last) {
        if (last <= 0 || target <= 0) return target;
        double logRet = Math.log(target / last);
        double clamped = Math.max(-CONTINUITY_MAX_LOG_RETURN, Math.min(CONTINUITY_MAX_LOG_RETURN, logRet));
        return last * Math.exp(clamped);
    }

    // ===================== 资产注册 =====================
    public void addAsset(String id, double initialPrice, double volatility, double drift, String industry) {
        addAsset(id, initialPrice, volatility, drift, industry, 200);
    }

    public void addAsset(String id, double initialPrice, double volatility, double drift, String industry, int historyBars) {
        // 初始参数仍可传入，但后续会被宏观因子覆盖
        GbmState gbm = new GbmState(initialPrice, drift, volatility, mix64(engineSeed ^ id.hashCode()));
        gbmStates.put(id, gbm);
        lastPrices.put(id, initialPrice);
        tradePoints.put(id, new ArrayList<>());
        tickBuffers.put(id, new ArrayList<>());
        pendingOrderFlows.put(id, new ArrayList<>());

        Map<KlineType, LinkedList<OHLCData>> cache = new ConcurrentHashMap<>();
        for (KlineType type : KlineType.values()) {
            cache.put(type, new LinkedList<>());
        }
        klineCache.put(id, cache);

        // 生成历史K线 (使用注册时的波动率)
        generateHistoricalKlines(id, initialPrice, volatility, drift, historyBars);

        dailyOpen.put(id, initialPrice);
        dailyClose.put(id, initialPrice);
        dailyFirstTrade.put(id, false);
    }

    /**
     * DAILY 聚合的分组大小（每 {@value #DAILY_TICKS_PER_BAR} 根 TICK bar 聚合为 1 根日K）。
     * 历史生成以「目标日K根数」为单位，保证聚合出的 DAILY 有足够根数供前端绘制（此前 historyBars=200
     * 实际生成 200 根 TICK bar，聚合后 DAILY 仅 1 根 → 前端日K 无法画图）。
     */
    private static final int DAILY_TICKS_PER_BAR = 240;
    // F1.1 冷启动估值锚：历史K线终点收敛窗口（最后 1 个 DAILY 日K = DAILY_TICKS_PER_BAR×TICKS_PER_BAR 个 tick）。
    // 把路径末尾 CONVERGE_TICKS 个 tick 确定性线性收敛回 fairValue(initialPrice)，使开盘 lastPrice≈fairValue、
    // 历史日K最后一根 close 亦锚定 fairValue；保持历史 K 线主体形状。纯确定性、不消费 rng。
    private static final int HISTORY_ENDPOINT_CONVERGE_TICKS = DAILY_TICKS_PER_BAR * TICKS_PER_BAR;

    private void generateHistoricalKlines(String id, double initialPrice, double volatility, double drift, int historyBars) {
        // 简化：使用恒定波动率生成历史
        GbmState gbm = gbmStates.get(id);
        // historyBars 语义为「目标 DAILY 日K根数」：每根日K需 DAILY_TICKS_PER_BAR 根 TICK bar，每根 TICK bar 需 TICKS_PER_BAR 个 tick。
        int totalTicks = Math.max(1, historyBars) * DAILY_TICKS_PER_BAR * TICKS_PER_BAR;
        double price = initialPrice;
        List<TickData> ticks = new ArrayList<>(totalTicks);

        for (int i = 0; i < totalTicks; i++) {
            double epsilon = gbm.rng.nextGaussian();
            double dt = DT;
            double driftTerm = drift * dt;
            double volTerm = volatility * Math.sqrt(dt) * epsilon;
            price = price * Math.exp(driftTerm + volTerm);
            price = Math.max(price, initialPrice * 0.05);
            price = Math.min(price, initialPrice * 10.0);
            long vol = 100 + (long)(gbm.rng.nextDouble() * 300);
            ticks.add(new TickData(price, vol, gbm.rng.nextBoolean()));
        }

        // F1.1 冷启动估值锚（RC-1 根治）：历史K线终点确定性锚定回 fairValue(=initialPrice)。
        // 根因（STYLIZED_FACTS_SPEC §3.1/§4.1 F1.1）：旧实现把 lastPrices/gbm.price 设为历史 GBM 路径
        // 终点价，而该终点经 288,000 tick 高波动路径几乎必然 ≠ fairValue（常显著上偏）→ 开盘价与价值锚
        // 解耦，VALUE 智能体按 deviation>0 单向卖出 → 跌停带锁死 + 成交稀疏（t5 κ≈2.9 万退化）。
        // 本修复把路径末尾 HISTORY_ENDPOINT_CONVERGE_TICKS 个 tick 线性收敛到 initialPrice（最末尾精确锚定），
        // 使开盘 lastPrice≈fairValue、VALUE 决策围绕价值双向而非单向卖出；同时保持历史 K 线主体形状。
        // 确定性：不开新随机、不消费既有 gbm.rng（仅重写已生成 tick 的价格）→ 同 seed 可复现（SF-5）。
        if (!ticks.isEmpty()) {
            int converge = Math.min(HISTORY_ENDPOINT_CONVERGE_TICKS, ticks.size());
            double endPrice = initialPrice;
            for (int i = 0; i < converge; i++) {
                int idx = ticks.size() - converge + i;
                double base = ticks.get(idx).price;
                double alpha = (double)(i + 1) / converge; // 最末尾 alpha=1 → 精确等于 endPrice
                double p = base + (endPrice - base) * alpha;
                ticks.set(idx, new TickData(p, ticks.get(idx).volume, ticks.get(idx).isBuy));
            }
        }

        Map<KlineType, LinkedList<OHLCData>> cache = klineCache.get(id);
        LinkedList<OHLCData> tickBars = cache.get(KlineType.TICK);

        for (int i = 0; i < ticks.size(); i += TICKS_PER_BAR) {
            int end = Math.min(i + TICKS_PER_BAR, ticks.size());
            List<TickData> slice = ticks.subList(i, end);
            if (!slice.isEmpty()) {
                double open = slice.get(0).price;
                double close = slice.get(slice.size() - 1).price;
                double high = slice.stream().mapToDouble(t -> t.price).max().orElse(open);
                double low = slice.stream().mapToDouble(t -> t.price).min().orElse(open);
                long volume = slice.stream().mapToLong(t -> t.volume).sum();
                tickBars.add(new OHLCData(open, high, low, close, volume));
            }
        }

        aggregateAllPeriods(id);
        // F1.1/F1.2：开盘价与 gbm.price 初始值统一锚定 fairValue(initialPrice)，而非历史路径终点，
        // 消除「prevClose=fairValue 但 lastPrice=路径终点」的量纲错配（RC-1）；历史日K最后一根 close
        // 已随上方收敛锚定 fairValue。确定性：initialPrice 为 addAsset 传入的 fairValue，无随机。
        if (!ticks.isEmpty()) {
            lastPrices.put(id, initialPrice);
            gbm.price = initialPrice;
        }
    }

    // ===================== 核心 tick（L1 宏观路径更新） =====================
    public void step() {
        tickCounter++;
        startupTick++;
        if (macroEnv == null) return;

        // 1. 更新宏观环境 (每20 tick)
        macroEnv.step(tickCounter);

        // 2. 对所有资产更新 GBM 参数
        double mu = macroEnv.getDynamicDrift();
        double macroSigma = macroEnv.getDynamicVolatility();

        // 卡尔曼软启动：初始波动率降低
        double noiseMultiplier = getNoiseMultiplier();
        double softStartFactor = 0.1 + 0.9 * noiseMultiplier;

        for (String assetId : lastPrices.keySet()) {
            GbmState gbm = gbmStates.get(assetId);
            if (gbm == null) continue;

            // TABL primary path: Kronos/TABL anchor + deterministic intraday bridge. The
            // handwritten GBM/GARCH branch below is only a fallback when TABL is disabled.
            if (tablEnabled && tablModel != null) {
                AssetSnapshot a = marketState != null ? marketState.getAsset(assetId) : null;
                double last = a != null && a.getLastPrice() > 0 ? a.getLastPrice() : gbm.price;
                double prev = a != null && a.getPrevClose() > 0 ? a.getPrevClose() : gbm.price;
                double target = tablModel.target(assetId, tickCounter, last, prev, this);
                if (!Double.isNaN(target)) {
                    // Top-down macro tilt on the TABL anchor: policy risk appetite + annual drift
                    // (converted to one session). Bounded so it cannot dominate the anchor.
                    double tilt = 0.0;
                    if (macroEnv != null) {
                        tilt = MacroStockTransmission.priceTilt(macroEnv.getRiskAppetite(),
                                macroEnv.getDynamicDrift());
                    }
                    tilt += crossAssetTilts.getOrDefault(assetId, 0.0);
                    target *= 1.0 + tilt;
                    if (prev > 0) {
                        target = Math.max(prev * 0.90, Math.min(prev * 1.10, target));
                    }
                    gbm.price = target;
                    if (a != null) {
                        double lp = a.getLastPrice();
                        a.setLastPrice(lp > 0 ? lp + FUNDAMENTAL_COUPLING * (target - lp) : target);
                    }
                    continue;
                }
                // Anchor not ready/stale: fall through to the handwritten GBM/GARCH fallback.
            }

            // 2.1 更新漂移率 (宏观驱动 + 资产差异化偏移，恢复涨跌分化，避免全市场共同模)
            gbm.mu = mu + perAssetIdiosyncraticDrift(assetId);

            // 2.2 GARCH(1,1) 条件方差更新 (P0-1 波动聚集)
            //     用可播种 PRNG 生成标准化冲击 ε_t ~ N(0,1)
            double epsilon = gbm.rng.nextGaussian();

            // 长期方差锚定宏观基线：ω = σ_long²·(1-α-β)，使无条件方差=宏观基线
            double longTermSigma = Math.max(macroSigma, 1e-6);
            gbm.garchOmega = Math.max(1e-10,
                    longTermSigma * longTermSigma * (1.0 - gbm.garchAlpha - gbm.garchBeta));

            // σ_t² = ω + α·ε_{t-1}² + β·σ_{t-1}²
            gbm.sigmaSq = gbm.garchOmega
                    + gbm.garchAlpha * gbm.lastEpsilon * gbm.lastEpsilon
                    + gbm.garchBeta * gbm.sigmaSq;
            // 下限保护
            gbm.sigmaSq = Math.max(1e-8, gbm.sigmaSq);
            gbm.lastEpsilon = epsilon;

            double sigmaT = Math.sqrt(gbm.sigmaSq);
            // 软启动缩放 (保持 KALMAN_WARMUP 行为)
            gbm.sigma = sigmaT * softStartFactor;
            // F3.3 波动率生理量级：钳制每 tick 波动上限，防 GARCH 突发被连续性钳制削尾（保留肥尾/聚集）。
            if (gbm.sigma > PHYSIOLOGICAL_SIGMA_MAX) gbm.sigma = PHYSIOLOGICAL_SIGMA_MAX;

            // 2.3 生成宏观基准价格 (欧拉离散化) —— σ_t 喂给现有 GBM
            double dt = DT;
            double driftTerm = gbm.mu * dt;
            double volTerm = gbm.sigma * Math.sqrt(dt) * epsilon;
            double newPrice = gbm.price * Math.exp(driftTerm + volTerm);
            newPrice = Math.max(newPrice, 0.01);
            // MS-5b：价格连续性钳制，消除开局跳空/涨停/跌停（与 Kalman 软启动叠加，开局 K 线平滑）。
            newPrice = clampForContinuity(newPrice, gbm.price);
            newPrice = Math.max(newPrice, 0.01);
            gbm.price = newPrice;

            // F3.4 市场-基本面耦合：可观测市场价向 gbm.price 均值回归，使价格发现回归基本面、收益连续。
            if (marketState != null) {
                AssetSnapshot a = marketState.getAsset(assetId);
                if (a != null) {
                    double lp = a.getLastPrice();
                    if (lp > 0) {
                        a.setLastPrice(lp + FUNDAMENTAL_COUPLING * (gbm.price - lp));
                    } else {
                        a.setLastPrice(gbm.price);
                    }
                }
            }
        }
    }

    // ===================== 物理正则化（供外部调用） =====================
    public boolean isValuationConsistent(double fairValue) {
        if (macroEnv == null) return true;
        double interestRate = macroEnv.getInterestRate();
        // 加息时 fairValue 应下降（简化检查）
        // 此处留空，由 MultiAgentManager 直接修正
        return true;
    }

    // ===================== K线聚合方法 =====================
    private void aggregateAllPeriods(String assetId) {
        Map<KlineType, LinkedList<OHLCData>> cache = klineCache.get(assetId);
        if (cache == null) return;
        LinkedList<OHLCData> tickBars = cache.get(KlineType.TICK);
        if (tickBars.isEmpty()) return;

        cache.get(KlineType.QUARTER_DAY).clear();
        cache.get(KlineType.HALF_DAY).clear();
        cache.get(KlineType.DAILY).clear();
        cache.get(KlineType.MONTHLY).clear();

        aggregateToPeriod(tickBars, cache.get(KlineType.QUARTER_DAY), 60);
        aggregateToPeriod(tickBars, cache.get(KlineType.HALF_DAY), 120);
        aggregateToPeriod(tickBars, cache.get(KlineType.DAILY), 240);
        aggregateToPeriod(tickBars, cache.get(KlineType.MONTHLY), 2880);
    }

    private void aggregateToPeriod(LinkedList<OHLCData> source, LinkedList<OHLCData> target, int groupSize) {
        if (source.isEmpty()) return;
        int total = source.size();
        // FIX-4：保证任何被请求的周期至少产出一根 bar（此前 TICK 不足 240 根时日 K 恒空 → 前端图表空白）。
        // 不改变既有分组公式，仅在源数据不足一根时回退为「整段聚合为一根」。
        int count = Math.max(1, total / groupSize);
        for (int i = 0; i < count; i++) {
            int start = i * groupSize;
            int end = Math.min(start + groupSize, total);
            List<OHLCData> slice = new ArrayList<>(source.subList(start, end));
            double open = slice.get(0).getOpen();
            double close = slice.get(slice.size() - 1).getClose();
            double high = slice.stream().mapToDouble(OHLCData::getHigh).max().orElse(open);
            double low = slice.stream().mapToDouble(OHLCData::getLow).min().orElse(open);
            long volume = slice.stream().mapToLong(OHLCData::getVolume).sum();
            target.add(new OHLCData(open, high, low, close, volume));
        }
        while (target.size() > 500) target.removeFirst();
    }

    // ===================== K线获取方法 =====================
    public List<OHLCData> getKlines(String assetId, KlineType type, int limit) {
        Map<KlineType, LinkedList<OHLCData>> cache = klineCache.get(assetId);
        if (cache == null) return Collections.emptyList();
        LinkedList<OHLCData> bars = cache.get(type);
        if (bars == null || bars.isEmpty()) {
            // 尝试从 TICK 重新聚合
            LinkedList<OHLCData> tickBars = cache.get(KlineType.TICK);
            if (!tickBars.isEmpty()) {
                aggregateAllPeriods(assetId);
                bars = cache.get(type);
                if (bars == null || bars.isEmpty()) return Collections.emptyList();
            } else {
                return Collections.emptyList();
            }
        }
        int total = bars.size();
        int start = Math.max(0, total - limit);
        return new ArrayList<>(bars.subList(start, total));
    }

    public List<OHLCData> getAggregatedKlines(String assetId, KlineType type, int limit) {
        return getKlines(assetId, type, limit);
    }

    private OHLCData getLastKline(String assetId, KlineType type) {
        List<OHLCData> klines = getKlines(assetId, type, 1);
        return klines.isEmpty() ? null : klines.get(0);
    }

    // ===================== 技术指标 =====================
    /**
     * MS-1：把 Double 清洗为 JSON 合法值。NaN/Infinity 在 Gson 2.10.1 下直接序列化会抛
     * IllegalArgumentException（NaN 不是合法 JSON 数字）→ /api/kline 无合法 JSON → 前端图空白。
     * 返回 {@code null}（前端 Number(k.maX)||0 兜底），只有有限数才保留。无随机、无状态。
     */
    private static Double jsonSafe(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? null : v;
    }

    public List<Map<String, Object>> getTechnicalKlines(String assetId, KlineType type, int limit) {
        List<OHLCData> klines = getKlines(assetId, type, limit);
        if (klines.isEmpty()) {
            // 尝试从 TICK 获取并聚合
            List<OHLCData> tickKlines = getKlines(assetId, KlineType.TICK, 1000);
            if (!tickKlines.isEmpty()) {
                aggregateAllPeriods(assetId);
                klines = getKlines(assetId, type, limit);
                if (klines.isEmpty()) return Collections.emptyList();
            } else {
                return Collections.emptyList();
            }
        }

        // 计算指标（关键：调用 TechnicalIndicatorCalculator）
        List<Map<String, Double>> indicators = TechnicalIndicatorCalculator.calculateIndicators(klines);

        // 计算 MA
        double[] closes = klines.stream().mapToDouble(OHLCData::getClose).toArray();
        double[] ma5 = calcMA(closes, 5);
        double[] ma10 = calcMA(closes, 10);
        double[] ma20 = calcMA(closes, 20);
        double[] ma60 = calcMA(closes, 60);

        List<Map<String, Object>> enhanced = new ArrayList<>();
        double cumulativeAmount = 0;
        for (int i = 0; i < klines.size(); i++) {
            OHLCData bar = klines.get(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("time", String.valueOf(i));
            map.put("open", jsonSafe(bar.getOpen()));
            map.put("high", jsonSafe(bar.getHigh()));
            map.put("low", jsonSafe(bar.getLow()));
            map.put("close", jsonSafe(bar.getClose()));
            map.put("volume", bar.getVolume());
            double amount = bar.getVolume() * bar.getClose();
            map.put("amount", jsonSafe(amount));
            double avgPrice = (bar.getOpen() + bar.getHigh() + bar.getLow() + bar.getClose()) / 4.0;
            map.put("avgPrice", jsonSafe(avgPrice));
            cumulativeAmount += amount;
            map.put("cumulativeAmount", jsonSafe(cumulativeAmount));

            // MA —— calcMA 在前 period-1 项为 NaN，须清洗为 null（Gson 不能序列化 NaN）
            map.put("ma5", jsonSafe(ma5[i]));
            map.put("ma10", jsonSafe(ma10[i]));
            map.put("ma20", jsonSafe(ma20[i]));
            map.put("ma60", jsonSafe(ma60[i]));

            // 技术指标（jsonSafe 防御：指标计算产物可能为 NaN/Infinity，同样会崩 Gson）
            if (i < indicators.size()) {
                Map<String, Double> ind = indicators.get(i);
                map.put("macdDif", jsonSafe(ind.getOrDefault("macd", 0.0)));
                map.put("macdDea", jsonSafe(ind.getOrDefault("macdSignal", 0.0)));
                map.put("macdHist", jsonSafe(ind.getOrDefault("macdHist", 0.0)));
                map.put("rsi", jsonSafe(ind.getOrDefault("rsi", 0.0)));
                map.put("kdjK", jsonSafe(ind.getOrDefault("kdjK", 0.0)));
                map.put("kdjD", jsonSafe(ind.getOrDefault("kdjD", 0.0)));
                map.put("kdjJ", jsonSafe(ind.getOrDefault("kdjJ", 0.0)));
                map.put("bollUp", jsonSafe(ind.getOrDefault("bollUpper", 0.0)));
                map.put("bollMid", jsonSafe(ind.getOrDefault("bollMiddle", 0.0)));
                map.put("bollLow", jsonSafe(ind.getOrDefault("bollLower", 0.0)));
            } else {
                map.put("macdDif", 0.0);
                map.put("macdDea", 0.0);
                map.put("macdHist", 0.0);
                map.put("rsi", 0.0);
                map.put("kdjK", 0.0);
                map.put("kdjD", 0.0);
                map.put("kdjJ", 0.0);
                map.put("bollUp", 0.0);
                map.put("bollMid", 0.0);
                map.put("bollLow", 0.0);
            }
            enhanced.add(map);
        }
        return enhanced;
    }

    private double[] calcMA(double[] data, int period) {
        int n = data.length;
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < period - 1) { result[i] = Double.NaN; continue; }
            double sum = 0;
            for (int j = 0; j < period; j++) sum += data[i - j];
            result[i] = sum / period;
        }
        return result;
    }

    // ===================== 其他辅助方法 =====================
    public void updateLastPrice(String assetId, double price) {
        lastPrices.put(assetId, price);
        // 反馈到 GBM mu (成交价偏离宏观锚)
        GbmState gbm = gbmStates.get(assetId);
        if (gbm != null && gbm.price > 0) {
            double deviation = (price - gbm.price) / gbm.price;
            gbm.mu += FEEDBACK_ALPHA * deviation;
            // 限制 mu 范围
            gbm.mu = Math.max(-0.10, Math.min(0.10, gbm.mu));
        }
    }

    public double getGbmPrice(String assetId) {
        GbmState gbm = gbmStates.get(assetId);
        return gbm != null ? gbm.price : 0.0;
    }

    /**
     * MS-AI-1 资产差异化漂移：为每资产从引擎种子+资产ID派生的稳定、确定性个体漂移偏移。
     * 根因（spec §2.2）：旧代码每步 gbm.mu = macroMu（所有资产同一值），全市场共同模 → 无差异性、涨跌趋同。
     * 此偏移叠加到宏观 μ 上，使不同资产净漂移分化（有的正、有的负）→ 市场有涨有跌。
     * 纯确定性（mix64 派生，无裸随机），读档时同种子+同ID重算一致，无需持久化。
     */
    private double perAssetIdiosyncraticDrift(String assetId) {
        long h = mix64(engineSeed ^ assetId.hashCode());
        double u = (h >>> 11) * (1.0 / (1L << 53)); // [0,1)
        return (u * 2.0 - 1.0) * ASSET_DRIFT_SPREAD;
    }

    public double getCurrentDrift() {
        return macroEnv != null ? macroEnv.getDynamicDrift() : 0.08;
    }

    public double getCurrentVolatility() {
        return macroEnv != null ? macroEnv.getDynamicVolatility() : 0.25;
    }

    /**
     * MarS 市场影响模型（MP-2）的 σ 来源：资产 GARCH 条件波动率 σ_t = √(sigmaSq)。
     * GbmState 缺失或 sigmaSq 非法（≤0/NaN/Inf）时回落宏观动态波动率（{@code macroEnv.getDynamicVolatility()}）。
     * 纯读、确定性、无随机（不消费任何随机源）。
     */
    public double getAssetVolatility(String assetId) {
        GbmState gbm = gbmStates.get(assetId);
        if (gbm != null && gbm.sigmaSq > 0
                && !Double.isNaN(gbm.sigmaSq) && !Double.isInfinite(gbm.sigmaSq)) {
            return Math.sqrt(gbm.sigmaSq);
        }
        return macroEnv != null ? macroEnv.getDynamicVolatility() : 0.25;
    }

    public double getLiquidityMultiplier() {
        return macroEnv != null ? macroEnv.getLiquidityMultiplier() : 1.0;
    }

    public double getLimitDown() {
        // A 股模式：固定 ±10%（消费端锚 prevClose 由 MarketEngine/OrderBook 处理）；关闭回落宏观动态阈值（A3-7）
        if (aShareEnabled) return -A_SHARE_LIMIT_RATIO;
        return macroEnv != null ? macroEnv.getLimitDownThreshold() : -0.10;
    }

    public double getLimitUp() {
        // A 股模式：固定 ±10%；关闭回落宏观动态阈值（A3-7）
        if (aShareEnabled) return A_SHARE_LIMIT_RATIO;
        return macroEnv != null ? macroEnv.getLimitUpThreshold() : 0.10;
    }

    public void addTradePoint(String assetId, TradePoint point) {
        tradePoints.computeIfAbsent(assetId, k -> new ArrayList<>()).add(point);
        // 同时将成交价加入 tickBuffers 用于K线生成
        tickBuffers.computeIfAbsent(assetId, k -> new ArrayList<>())
                .add(new TickData(point.price(), point.quantity(), point.isBuy()));
        if (!dailyFirstTrade.getOrDefault(assetId, false)) {
            dailyOpen.put(assetId, point.price());
            dailyFirstTrade.put(assetId, true);
        }
        dailyClose.put(assetId, point.price());

        // FIX-4：把实时成交增量聚合进 TICK K线（此前 tickBuffers 只进不出，导致 klineCache 永不更新/空白）
        accumulateLiveTradeIntoKline(assetId, point);
    }

    /**
     * FIX-4：增量地把实时成交追加到 TICK K线缓存（每 {@link #TICKS_PER_BAR} 笔成一根），
     * 并重新聚合其它周期（分时/日/月）。确定性、无随机。这是补上缺失的「成交 → K线」数据管线接入点，
     * 不改任何 K 线聚合公式。
     */
    private void accumulateLiveTradeIntoKline(String assetId, TradePoint point) {
        Map<KlineType, LinkedList<OHLCData>> cache = klineCache.get(assetId);
        if (cache == null) return;
        LinkedList<OHLCData> tickBars = cache.get(KlineType.TICK);
        if (tickBars == null) return;

        OHLCData partial = partialTickBars.get(assetId);
        int count = partialTickCounts.getOrDefault(assetId, 0);
        double price = point.price();
        long vol = Math.max(0, point.quantity());
        if (partial == null) {
            partial = new OHLCData(price, price, price, price, vol);
        } else {
            partial.update(price, vol);
        }
        count++;
        if (count >= TICKS_PER_BAR) {
            tickBars.addLast(partial);
            while (tickBars.size() > TICK_BAR_CACHE_LIMIT) tickBars.removeFirst();
            partialTickBars.remove(assetId);
            partialTickCounts.remove(assetId);
            aggregateAllPeriods(assetId);
        } else {
            partialTickBars.put(assetId, partial);
            partialTickCounts.put(assetId, count);
        }
    }

    public List<TradePoint> getTradePoints(String assetId) {
        return tradePoints.getOrDefault(assetId, Collections.emptyList());
    }

    /**
     * FIX-4：持久化前把每资产尚未满一根的实时 TICK bar 冲入 TICK 缓存，避免该段成交在存/读档间丢失，
     * 保持 H2/NBT 持久化兼容。冲入后立即重新聚合其它周期。
     */
    private void flushPartialTickBars() {
        for (Map.Entry<String, OHLCData> e : partialTickBars.entrySet()) {
            String assetId = e.getKey();
            Map<KlineType, LinkedList<OHLCData>> cache = klineCache.get(assetId);
            if (cache == null) continue;
            LinkedList<OHLCData> tickBars = cache.get(KlineType.TICK);
            if (tickBars == null) continue;
            tickBars.addLast(e.getValue());
            while (tickBars.size() > TICK_BAR_CACHE_LIMIT) tickBars.removeFirst();
            aggregateAllPeriods(assetId);
        }
        partialTickBars.clear();
        partialTickCounts.clear();
    }

    public List<Map<String, Object>> getTradeTicks(String assetId, int limit) {
        List<TradePoint> points = tradePoints.getOrDefault(assetId, Collections.emptyList());
        if (points.isEmpty()) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<>();
        int start = Math.max(0, points.size() - limit);
        for (int i = start; i < points.size(); i++) {
            TradePoint p = points.get(i);
            Map<String, Object> tick = new HashMap<>();
            tick.put("time", String.valueOf(i));
            tick.put("price", p.price());
            tick.put("vol", p.quantity());
            result.add(tick);
        }
        return result;
    }

    public List<Double> getRecentCloses(String assetId, int count) {
        List<OHLCData> klines = getKlines(assetId, KlineType.TICK, count);
        if (klines.isEmpty()) return Collections.emptyList();
        return klines.stream().map(k -> k.getClose()).toList();
    }

    public double getDailyOpen(String assetId) { return dailyOpen.getOrDefault(assetId, 0.0); }
    public double getDailyClose(String assetId) { return dailyClose.getOrDefault(assetId, 0.0); }
    public Set<String> getAssetIds() { return lastPrices.keySet(); }
    public double getLastPrice(String assetId) { return lastPrices.getOrDefault(assetId, 0.0); }
    public long getNextOrderId() { return ++nextOrderId; }

    // ===================== 持久化 =====================
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag prices = new CompoundTag();
        for (var e : lastPrices.entrySet()) prices.putDouble(e.getKey(), e.getValue());
        tag.put("prices", prices);

        CompoundTag gbmTag = new CompoundTag();
        for (var e : gbmStates.entrySet()) {
            GbmState g = e.getValue();
            CompoundTag state = new CompoundTag();
            state.putDouble("price", g.price);
            state.putDouble("mu", g.mu);
            state.putDouble("sigma", g.sigma);
            // P0-1 GARCH 状态
            state.putDouble("sigmaSq", g.sigmaSq);
            state.putDouble("lastEpsilon", g.lastEpsilon);
            state.putDouble("garchOmega", g.garchOmega);
            state.putDouble("garchAlpha", g.garchAlpha);
            state.putDouble("garchBeta", g.garchBeta);
            // P0-7 PRNG 状态（读档后行情可继续复现）
            state.putLong("rngState", g.rng.getState());
            if (!Double.isNaN(g.rng.getSpareGaussian())) {
                state.putDouble("rngSpare", g.rng.getSpareGaussian());
            }
            gbmTag.put(e.getKey(), state);
        }
        tag.put("gbm", gbmTag);
        tag.putLong("engineSeed", engineSeed);

        // FIX-4：持久化前把未满一根的实时 TICK bar 冲入 klineCache，避免该段成交在存/读档间丢失
        flushPartialTickBars();

        CompoundTag klinesTag = new CompoundTag();
        for (var entry : klineCache.entrySet()) {
            CompoundTag assetTag = new CompoundTag();
            for (var typeEntry : entry.getValue().entrySet()) {
                ListTag list = new ListTag();
                for (OHLCData bar : typeEntry.getValue()) list.add(bar.toNbt());
                assetTag.put(typeEntry.getKey().name(), list);
            }
            klinesTag.put(entry.getKey(), assetTag);
        }
        tag.put("klines", klinesTag);

        CompoundTag pointsTag = new CompoundTag();
        for (var e : tradePoints.entrySet()) {
            ListTag list = new ListTag();
            for (TradePoint p : e.getValue()) {
                CompoundTag pt = new CompoundTag();
                pt.putDouble("price", p.price());
                pt.putInt("qty", p.quantity());
                pt.putBoolean("buy", p.isBuy());
                list.add(pt);
            }
            pointsTag.put(e.getKey(), list);
        }
        tag.put("tradePoints", pointsTag);

        CompoundTag dailyTag = new CompoundTag();
        for (var e : dailyOpen.entrySet()) dailyTag.putDouble(e.getKey() + "_open", e.getValue());
        for (var e : dailyClose.entrySet()) dailyTag.putDouble(e.getKey() + "_close", e.getValue());
        tag.put("daily", dailyTag);

        return tag;
    }

    public void load(CompoundTag tag) {
        CompoundTag prices = tag.getCompound("prices");
        for (String key : prices.getAllKeys()) lastPrices.put(key, prices.getDouble(key));

        CompoundTag gbmTag = tag.getCompound("gbm");
        for (String key : gbmTag.getAllKeys()) {
            CompoundTag state = gbmTag.getCompound(key);
            GbmState gbm = new GbmState(state.getDouble("price"), state.getDouble("mu"), state.getDouble("sigma"), mix64(engineSeed ^ key.hashCode()));
            // 恢复 GARCH 状态
            gbm.sigmaSq = state.getDouble("sigmaSq");
            gbm.lastEpsilon = state.getDouble("lastEpsilon");
            gbm.garchOmega = state.getDouble("garchOmega");
            gbm.garchAlpha = state.getDouble("garchAlpha");
            gbm.garchBeta = state.getDouble("garchBeta");
            // 恢复 PRNG 状态（读档后行情继续）
            gbm.rng.setState(state.getLong("rngState"));
            if (state.contains("rngSpare")) gbm.rng.setSpareGaussian(state.getDouble("rngSpare"));
            gbmStates.put(key, gbm);
        }

        CompoundTag klinesTag = tag.getCompound("klines");
        for (String key : klinesTag.getAllKeys()) {
            CompoundTag assetTag = klinesTag.getCompound(key);
            Map<KlineType, LinkedList<OHLCData>> cache = new ConcurrentHashMap<>();
            for (KlineType type : KlineType.values()) {
                ListTag list = assetTag.getList(type.name(), Tag.TAG_COMPOUND);
                LinkedList<OHLCData> bars = new LinkedList<>();
                for (int i = 0; i < list.size(); i++) bars.add(OHLCData.fromNbt(list.getCompound(i)));
                cache.put(type, bars);
            }
            klineCache.put(key, cache);
            tickBuffers.put(key, new ArrayList<>());
            pendingOrderFlows.put(key, new ArrayList<>());
        }

        CompoundTag pointsTag = tag.getCompound("tradePoints");
        for (String key : pointsTag.getAllKeys()) {
            ListTag list = pointsTag.getList(key, Tag.TAG_COMPOUND);
            List<TradePoint> points = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag pt = list.getCompound(i);
                points.add(new TradePoint(pt.getDouble("price"), pt.getInt("qty"), pt.getBoolean("buy")));
            }
            tradePoints.put(key, points);
        }

        CompoundTag dailyTag = tag.getCompound("daily");
        for (String key : dailyTag.getAllKeys()) {
            if (key.endsWith("_open")) {
                String id = key.substring(0, key.length() - 5);
                dailyOpen.put(id, dailyTag.getDouble(key));
            } else if (key.endsWith("_close")) {
                String id = key.substring(0, key.length() - 6);
                dailyClose.put(id, dailyTag.getDouble(key));
            }
        }

        for (String key : lastPrices.keySet()) dailyFirstTrade.put(key, false);
        for (String key : lastPrices.keySet()) aggregateAllPeriods(key);
    }

    // ---------- 内部类 ----------
    public enum KlineType { TICK, QUARTER_DAY, HALF_DAY, DAILY, MONTHLY }
    public record TradePoint(double price, int quantity, boolean isBuy) {}
    private record AssetProfile(double volatility, double drift, String industry) {}
    private record TickData(double price, long volume, boolean isBuy) {}
    public static class OrderFlow {
        public int timestamp;
        public boolean isBuy;
        public double price;
        public int size;
        // LIQ P2（§5.2/§7.2）：做市商会话标签（"liq-mm:"+assetId），供引擎在挂单/成交归属时识别专职
        // MM-Agent 报价（Order.mmTag 的流级载体）。普通 agent/ZI/Hawkes/meso 流为 null（不变）。
        // 仅流级标识、不参与任何随机/定价决策 → 加字段不改既有基线（因子隔离）。
        public String mmTag;
    }
}