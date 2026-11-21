package com.example.financialmarket.engine;

import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class OrderBook {
    private final TreeMap<Double, Queue<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Double, Queue<Order>> asks = new TreeMap<>();
    // P0-5 G-09：订单簿内部 tick 计数器（每次挂单/撮合推进，用于过期清理）
    // 从 1 起步，保证首批订单 placedTick>0 也能正常过期判定
    private long currentTick = 1;
    // P0-7/A0-3：可播种 PRNG（滑点用），保证同种子撮合结果可复现
    private final SeedableRandom rng;
    // P2-1 §2.4（F1 修复）：由 MarketEngine 在撮合前注入的净订单流失衡 ∈[-1,1]，用于撮合主动方
    // 判定回馈价格方向（净买失衡 → 偏向买主动方推动价格上行）。确定性，无随机。
    private double netImbalance = 0.0;
    // P2 隔离开关（F2 修复）：impactEnabled=false 时 matchWithSlippage 回退 P0 纯随机滑点语义，
    // 使「关 P2 → 与 P0/P1 逐位一致」可在真实代码路径验证。默认 true（P2 深度冲击）。
    private boolean impactEnabled = true;
    // MarS 市场影响模型（MP-2）：walkBook 撮合侧叠加 Δ=α·σ·√(Q/V) 所需的市场上下文（每 tick 由
    // MarketEngine 注入，非持久化）与总开关。marSImpactEnabled=false（marS.enabled 或 impactEnabled
    // 关闭）时不叠加 → 回落既有深度冲击基线（因子隔离，P2 关→P0 逐位一致保持）。
    private double marSSigma = 0.0;      // 资产 GARCH 条件波动率 σ_t（PriceDiscoveryEngine.getAssetVolatility）
    private double marSVolume = 0.0;     // 市场成交量 V（VolumeTracker 滚动 ADV）
    private boolean marSImpactEnabled = true;

    // ----- MS-5b 价格连续性硬约束（AI 决策损失物理惩罚） -----
    // 相邻成交价 |log(S_t/S_{t-1})| ≤ M 防开局极端跳空（与 A 股涨跌停带取更严者）。
    // M 为编译期常量（不落盘）；lastFillPrice 为本会话最近成交价锚（session 态，非持久化）。
    private static final double CONTINUITY_MAX_LOG_MOVE = 0.05;
    private double lastFillPrice = 0.0;

    // ===== CAPM_SPEC §4 P-A（CM）：强制出清/价格连续性强化（cm.clearing 门内，A-1..A-3） =====
    // clearingEnabled：CAPM_SPEC cm.clearing 在本簿的生效位，由 MarketEngine 每 tick / 建簿时注入
    // （风格同 setLimitBand / setMarSImpactEnabled，因子隔离先例 LQ-9/LQ-10）。false → 竞价连续性
    // 包络/出清后锚更新/异常点位护栏计数路径<b>零执行</b>，runCallAuction 回落 LIQ 交付语义
    // （CAPM_SPEC §4.3/§7.2，CM-6：cm 关闭与 LIQ 基线逐位一致）。确定性、零随机。
    private boolean clearingEnabled = true;
    // A-2（GAP-3）：集中异常点位护栏命中计数（运行期瞬态审计，不落盘；正常长程 = 0，
    // 构造 NaN/Inf/≤0 输入时 > 0 → 只读 getter 暴露给探针断言，CM-1c）。
    private long invalidFillGuardHits = 0;

    // A 股涨跌停带（AS-2）：由 MarketEngine 按资产 prevClose 注入（setLimitBand）。
    // 未注入 / 关闭 A 股模式时为无界（P0/P1/P2 基线行为，不钳制成交价）。
    private double limitUp = Double.MAX_VALUE;
    private double limitDown = -Double.MAX_VALUE;

    // ===== DG-1 价格笼子（DATA_GUARD_SPEC §2，制度经济学约束：申报价/有效报价空间） =====
    // 与涨跌停带的区别：涨跌停带（锚 prevClose）约束「日价格空间」；价格笼子（锚**最新成交价**）约束
    // 「申报价/有效报价空间」，笼子在带**之内**取更严者。只约束报价，成交价仍由既有
    // band→continuity→band→sanitizeFill 链夹断（笼子作为最后一道一致性守卫）。
    // 全部为编译期常量（不落盘、不参与随机）；会话态 lastTradePrice 由 MarketEngine 每 tick 注入。
    private static final double CAGE_BASE_RATIO = 0.10;   // 与 A 股主板带同量级（只拦「离谱报价」）
    private static final int CAGE_MIN_TICKS = 200;        // 低价股保护：lastPrice<2.00 时笼子自动 >10%
    private boolean cageEnabled = true;                   // 由 MarketEngine 每 tick 注入（dgCageEffective）
    // 本簿最新成交价（DG-1 笼子锚；撮合/竞价/市价通道内更新，会话态、不落盘，开盘随连续性锚重置）
    private double lastTradePrice = 0.0;
    // 运行期瞬态审计（不落盘，仿 invalidFillGuardHits）：笼子拒单/钳入命中计数，供探针只读断言
    private long cageRejectHits = 0;
    private long cageClampHits = 0;

    // ===== DG-2 ★可观测面（DATA_GUARD_SPEC §3.1）：AC 项与既有两项的**分项可分离观测** =====
    // 观察成本纪律（§9.9）：档位循环内只累计到**局部变量**，循环结束后一次性回写本组字段；
    // walkBook 热路径内禁止字符串拼接/日志/异常构造。全部运行期瞬态、不落盘、不参与任何价格计算。
    private long acImpactHits = 0;      // AC 项参与的档位数（>0 证明 AC 真的作用于真实撮合路径）
    private double acRatioSum = 0;      // Σ ΔP_AC/mid（采样累计）
    private double acRatioLast = 0;     // 最近一次 ΔP_AC/mid（撮合侧）
    private double depthRatioLast = 0;  // 最近一次既有深度冲击比例（k_sqrt·√(s/d₀)+k_linear·(s/d₀)）
    private long acSampleCount = 0;     // 采样次数
    private double lastGamma = 0.0;     // 最近一次 γ（自 impactModel）
    private double lastLambda = 0.0;    // 最近一次 λ_eff（自 impactModel）
    // 最近一次 AC/深度冲击的**自变量快照**（供探针用同一组入参独立复算，避免竞态/推断）
    private double lastAcQty = 0.0;
    private double lastAcAdv = 0.0;
    private double lastAcDepth = 0.0;
    private double lastAcMid = 0.0;
    private double lastAcCumQty = 0.0;
    private boolean lastAcBuy = true;

    // ===== DG-3 冷却窗口会话态（DATA_GUARD_SPEC §4.3 冻结接口） =====
    private boolean coolingActive = false;
    private boolean coolingNoCancel = true;
    private boolean coolingLimitOnly = true;
    private long coolingWindowStartTick = 0;
    private int coolingNewLimitOrders = 0;
    private long coolingEnterCount = 0;
    private long lastRefSyntheticCleared = 0;
    private double lastPStar = 0.0;
    private long lastPStarVolume = 0L;
    private int consecutiveMatchFailures = 0;
    private long currentBookTick = 0;   // 只读：用于窗口剩余量与窗口结束 tick 计算
    private int coolingWindowRound = 0; // 窗口轮次（1..COOLING_MAX_WINDOWS，由引擎注入只读暴露）
    /** DG-3：冷却窗口长度（100 tick = 5s @20tps，DATA_GUARD_SPEC §4.2 冻结常量）。 */
    public static final long COOLING_WINDOW_TICKS = 100L;

    // F1 兜底（MS-AI-5 防堆积）：单侧 resting 订单绝对上限，作为每 tick 过期清理之外的最终安全阀。
    private static final int MAX_RESTING_ORDERS_PER_SIDE = 5000;

    // ================= LIQ P1（LIQUIDITY_SPEC §4.1）：可市价执行通道 =================
    // 市价单（MARKET_BUY/MARKET_SELL）到达后由 MarketEngine 经 executeMarketableOrder 立即作为主动方
    // 吃对手侧 resting 档，不再以 limitPrice=0.0 挂入簿末档/簿首档（R-1 结构性缺陷修复）。
    // convertResidualToLimit=true（默认）：未吃满的残余量转为「已成交/参考价位」限价单挂回本侧排队
    // （A 股可排队语义）；=false：残余直接撤销。开关确定性、无随机；由 MarketEngine 按 liq 总开关注入。
    private boolean convertResidualToLimit = true;
    // LIQ P2（§5.2）：MM 成交归属回调——撮合/市价执行/竞价在「恰一侧为专职 MM 报价（mmTag 非空）」的
    // 成交发生时回调 (fillPrice, signedShares)（>0=MM 买、<0=MM 卖），由 MarketEngine 注册到
    // agentManager.onMarketMakerFill（更新存货 I(t)/avgCost，γI 报价生效）。非持久化、无随机；
    // 未注册（null）时零开销零行为（对既有撮合路径逐位一致，LQ-9/10）。
    private java.util.function.BiConsumer<Double, Integer> mmFillListener = null;

    public OrderBook() {
        this(0L);
    }

    /** P0-7：以引擎种子派生的子流种子构造，滑点随机可复现。 */
    public OrderBook(long seed) {
        this.rng = new SeedableRandom(SeedableRandom.mix64(seed));
        this.cal = com.example.financialmarket.engine.calibration.CalibrationParams.DEFAULT;
        this.impactModel = new com.example.financialmarket.engine.microstructure.MarketImpact(seed, this.cal);
        // DG-2：合成补充档预算参数取自校准先验（与 MarketImpact 同一份先验，保证量级口径一致）
        this.calSynthBudgetAdvScale = this.cal.getAcSynthBudgetAdvScale();
        this.calSynthQtyBudget = this.cal.getAcSynthQtyBudget();
    }

    // ================= P2 只读/注入接口（F1/F2 修复） =================

    /** P2-1 §2.3：当前 mid 价（bestBid/bestAsk 均值；无双边时回落最优单边价）。 */
    public synchronized double getMidPrice() {
        if (!bids.isEmpty() && !asks.isEmpty()) return (bids.firstKey() + asks.firstKey()) / 2.0;
        if (!bids.isEmpty()) return bids.firstKey();
        if (!asks.isEmpty()) return asks.firstKey();
        return 0.0;
    }

    /** P2-1 §2.3：盘口净失衡 = (Σ最优档买量 − Σ最优档卖量)/(Σ买量+Σ卖量)，归一化到 [-1,1]。 */
    public synchronized double getNetImbalance() {
        double buy = bids.isEmpty() ? 0 : bids.firstEntry().getValue().stream().mapToInt(Order::getRemainingQuantity).sum();
        double sell = asks.isEmpty() ? 0 : asks.firstEntry().getValue().stream().mapToInt(Order::getRemainingQuantity).sum();
        double total = buy + sell;
        return total <= 0 ? 0.0 : (buy - sell) / total;
    }

    /** P2-1 §2.4（F1）：MarketEngine 在撮合前注入净订单流失衡 ∈[-1,1]（净买>0 / 净卖<0）。 */
    public synchronized void setNetImbalance(double imbalance) {
        this.netImbalance = Math.max(-1.0, Math.min(1.0, imbalance));
    }

    /** P2 隔离开关（F2）：false 时 matchWithSlippage 回退 P0 纯随机滑点语义。 */
    public synchronized void setImpactEnabled(boolean enabled) {
        this.impactEnabled = enabled;
    }

    public synchronized boolean isImpactEnabled() {
        return impactEnabled;
    }

    /**
     * MarS MP-2：注入本资产撮合侧市场上下文（σ=GARCH 条件波动率、V=滚动 ADV），供 walkBook 叠加
     * {@code Δ=α·σ·√(Q/V)} 影响项。由 MarketEngine 在 L3 撮合前每 tick 注入；确定性、无随机。
     * σ/V 非法值（≤0）按 0 处理 → 影响项为 0，与基线一致。
     */
    public synchronized void setMarketContext(double sigma, double volume) {
        this.marSSigma = sigma > 0 && !Double.isNaN(sigma) && !Double.isInfinite(sigma) ? sigma : 0.0;
        this.marSVolume = volume > 0 && !Double.isNaN(volume) && !Double.isInfinite(volume) ? volume : 0.0;
    }

    /**
     * MarS 总开关隔离（MP-5）：false 时 walkBook 不叠加 MarS 影响项（回落既有深度冲击基线，
     * 逐位一致）。由 MarketEngine 按 {@code marS.enabled && p2Enabled} 注入。
     */
    public synchronized void setMarSImpactEnabled(boolean enabled) {
        this.marSImpactEnabled = enabled;
    }

    public synchronized boolean isMarSImpactEnabled() {
        return marSImpactEnabled;
    }

    // ===== DG-2 Almgren-Chriss 冲击项簿侧生效位（DATA_GUARD_SPEC §1/§3.1） =====
    // 由 MarketEngine 每 tick 注入 dgAcEffective()（= dataGuard.enabled && dataGuard.almgrenChriss && p2Enabled）。
    // false → walkBook 不叠加 AC 项、也不启用 AC 合成补充档 → 与交付基线逐位一致（因子隔离，DG-2b 对照）。
    private boolean almgrenChrissEnabled = true;

    // DG-2 合成补充档常量（DATA_GUARD_SPEC §3.2 F-AC-1，全部编译期常量、不落盘、不参与随机）
    private static final int AC_MAX_LEVELS = 20;              // 单次 walk 档位上限（现值 5 的上界，防失控）
    private static final double AC_SYNTH_LEVEL_MULT = 0.5;    // 相邻补充档深度乘子（d_k = d_{k−1}×0.5）
    private static final double AC_SYNTH_QTY_BUDGET = 5.0e6;  // 补充档深度总预算上限（股；ADV=0 时兜底）
    private static final double AC_SYNTH_BUDGET_ADV_SCALE = 0.5; // 预算 = 0.5×ADV（与「半天成交量」同量级）
    // DG-2 校准先验（由构造器注入 CalibrationParams.DEFAULT；合成档预算参数取自先验，不硬编码）
    private final com.example.financialmarket.engine.calibration.CalibrationParams cal;
    private final double calSynthBudgetAdvScale;
    private final double calSynthQtyBudget;

    /** DG-2：AC 冲击项簿侧生效位（MarketEngine 每 tick 注入；false → AC 项与合成补充档零执行）。 */
    public synchronized void setAlmgrenChrissEnabled(boolean enabled) {
        this.almgrenChrissEnabled = enabled;
    }

    /** DG-2：当前 AC 冲击项簿侧生效位。 */
    public synchronized boolean isAlmgrenChrissEnabled() {
        return almgrenChrissEnabled;
    }

    /**
     * A 股涨跌停带注入（AS-2）：把本资产成交价钳制到 [limitDown, limitUp]。
     * 由 MarketEngine 按资产 prevClose 计算后在撮合前调用；确定性、无随机。
     */
    public synchronized void setLimitBand(double limitUp, double limitDown) {
        this.limitUp = limitUp;
        this.limitDown = limitDown;
    }

    /** 清除涨跌停带（A 股关闭/prevClose 无效时回落无界，P0/P1/P2 基线行为）。 */
    public synchronized void clearLimitBand() {
        this.limitUp = Double.MAX_VALUE;
        this.limitDown = -Double.MAX_VALUE;
    }

    // ================= DG-1 价格笼子注入/只读接口（DATA_GUARD_SPEC §2.2，冻结接口） =================

    /**
     * DG-1 笼子生效位（由 MarketEngine 每 tick 注入 {@code dgCageEffective()}，风格同 setLimitBand/
     * setMarSImpactEnabled）。false → 价格笼子三处调用点零执行、笼子候选价过滤零执行
     * → 与交付基线逐位一致（因子隔离）。
     */
    public synchronized void setCageEnabled(boolean e) {
        this.cageEnabled = e;
    }

    /** DG-1：当前笼子生效位。 */
    public synchronized boolean isCageEnabled() {
        return cageEnabled;
    }

    /**
     * DG-1：注入本簿最新成交价（笼子锚）。仅接受 &gt;0 且有限之值（非法值不更新，防御性）。
     * 撮合/竞价/市价通道成交后更新；交易日开盘随 {@link #resetContinuityAnchor(double)} 一并刷新。
     * 与 {@link #getLastFillPrice()} 同源（同一笔成交价），保证「连续性锚」与「笼子锚」不出现两份不一致状态。
     */
    public synchronized void setLastTradePrice(double p) {
        if (p > 0 && Double.isFinite(p)) this.lastTradePrice = p;
    }

    /** DG-1：当前笼子锚（最新成交价；0 = 冷启动未成交 → 笼子不生效）。 */
    public synchronized double getLastTradePrice() {
        return lastTradePrice > 0 ? lastTradePrice : lastFillPrice;
    }

    /**
     * DG-1：笼子上端 = {@code min(limitUp, lastPrice×(1+cageRatio))}（笼子在涨跌停带之内，取更严者）。
     * 带未注入时为 {@code Double.MAX_VALUE}（无约束）。封板场景（lastPrice=涨停价）时 {@code min} 生效
     * → {@code cageUp=涨停价}，涨停排队不受影响（A 股玩家单不回归）。
     */
    public synchronized double getCageUp() {
        double anchor = getLastTradePrice();
        if (anchor <= 0) return Double.MAX_VALUE;
        return Math.min(limitUp, anchor * (1.0 + cageRatio(anchor)));
    }

    /**
     * DG-1：笼子下端 = {@code max(limitDown, lastPrice×(1−cageRatio))}（带未注入时无约束）。
     * 跌停封板时 {@code cageDown=跌停价}，跌停排队不受影响。
     */
    public synchronized double getCageDown() {
        double anchor = getLastTradePrice();
        if (anchor <= 0) return -Double.MAX_VALUE;
        return Math.max(limitDown, anchor * (1.0 - cageRatio(anchor)));
    }

    /**
     * DG-1：笼子半径 {@code cageRatio = max(CAGE_BASE_RATIO, CAGE_MIN_TICKS×A_SHARE_TICK_SIZE/lastPrice)}
     * ——低价股自动放宽（例 lastPrice=1.00 → ratio=2.0，笼子退化为涨跌停带），避免系统性拒单。
     */
    private static double cageRatio(double lastPrice) {
        return Math.max(CAGE_BASE_RATIO, CAGE_MIN_TICKS * PriceDiscoveryEngine.A_SHARE_TICK_SIZE / lastPrice);
    }

    /** DG-1：笼子当前是否已生效（cageEnabled && 锚有效）。 */
    public synchronized boolean priceCageActive() {
        return cageEnabled && getLastTradePrice() > 0;
    }

    /**
     * DG-1 核心纯函数：申报价是否落在笼内。nullable 语义——笼子未生效时返回 true（调用方按「不约束」处理）。
     * 闭区间（端点可挂单 → 涨停/跌停封板排队不被破坏）。
     */
    public synchronized boolean priceWithinCage(double price) {
        if (!priceCageActive()) return true;
        if (!Double.isFinite(price)) return false;
        return price >= getCageDown() && price <= getCageUp();
    }

    /**
     * DG-1：把价格钳入笼子（返回 NaN/非正 → 原样返回，由调用方按异常点位处理）。
     * 笼子未生效时恒等返回。命中钳制时计数 {@code cageClampHits}。
     */
    public synchronized double clampToCage(double price) {
        if (!priceCageActive()) return price;
        if (!Double.isFinite(price) || price <= 0) return price;
        double c = Math.max(getCageDown(), Math.min(getCageUp(), price));
        if (c != price) cageClampHits++;
        return c;
    }

    /** DG-1：笼子**拒单**命中计数（玩家申报价越笼被拒；运行期瞬态、不落盘，供探针只读断言）。 */
    public synchronized long getCageRejectHits() {
        return cageRejectHits;
    }

    /** DG-1：笼子**钳入**命中计数（合成报价越笼被钳入笼内而非丢弃；运行期瞬态、不落盘）。 */
    public synchronized long getCageClampHits() {
        return cageClampHits;
    }

    /** DG-1：记录一次玩家申报价越笼拒单（由 MarketEngine.submitOrder 调用，供长程审计）。 */
    public synchronized void noteCageReject() {
        cageRejectHits++;
    }

    /** DG-1（v1 名称别名，保留兼容）：记录一次玩家申报价越笼拒单。 */
    public synchronized void recordCageReject() {
        noteCageReject();
    }

    /**
     * DG-1：笼子半径 X（只读派生，§2.2 ★可观测面）：
     * {@code max(CAGE_BASE_RATIO, CAGE_MIN_TICKS×0.01/max(lastTradePrice, A_SHARE_TICK_SIZE))}。
     * 无状态、无随机；锚无效时回落基准带宽。
     */
    public synchronized double getCageRatio() {
        double anchor = getLastTradePrice();
        if (anchor <= 0) return CAGE_BASE_RATIO;
        return cageRatio(Math.max(anchor, PriceDiscoveryEngine.A_SHARE_TICK_SIZE));
    }

    /** DG-1：记录 n 次合成报价越笼钳入（由 MarketEngine 经流水线 Context 回传，供长程审计）。 */
    public synchronized void recordCageClamp(long n) {
        if (n > 0) cageClampHits += n;
    }

    /** DG-1：重置笼子会话锚（交易日开盘随连续性锚一并刷新；anchor≤0 时清空 → 笼子不生效）。 */
    public synchronized void resetCageAnchor(double anchor) {
        this.lastTradePrice = anchor > 0 && Double.isFinite(anchor) ? anchor : 0.0;
    }

    // ================= DG-3 冷却窗口接口（DATA_GUARD_SPEC §4.3，冻结接口） =================

    /** DG-3：本簿是否处于熔断冷却窗口（窗口内只能挂限价、不能撤销、撮合暂停）。 */
    public synchronized boolean isCoolingActive() {
        return coolingActive;
    }

    /** DG-3：进入冷却窗口（置 active + 清计数器 + enterCount++）。确定性、零随机。 */
    public synchronized void beginCooling(long tick) {
        this.coolingActive = true;
        this.coolingNoCancel = true;
        this.coolingLimitOnly = true;
        this.coolingWindowStartTick = tick;
        this.coolingNewLimitOrders = 0;
        this.coolingEnterCount++;
    }

    /** DG-3：结束冷却窗口（清 active；计数器保留至下次 beginCooling）。确定性、零随机。 */
    public synchronized void endCooling() {
        this.coolingActive = false;
    }

    /** DG-3：窗口内累计新挂限价单笔数（早收条件 {@code ≥ COOLING_MIN_ORDERS} 用）。 */
    public synchronized int getCoolingNewLimitOrders() {
        return coolingNewLimitOrders;
    }

    /** DG-3：窗口起始 tick（探针时间线断言用）。 */
    public synchronized long getCoolingWindowStartTick() {
        return coolingWindowStartTick;
    }

    /**
     * DG-3：窗口结束 tick（= 进入 tick + `COOLING_WINDOW_TICKS`=100）。
     *
     * <p><b>t23 语义修正（D1，与 t22 同型）</b>：**未处于冷却窗口时返回 `0`**。
     * 修前实现无条件返回 {@code coolingWindowStartTick + COOLING_WINDOW_TICKS}，而未冷却时
     * {@code coolingWindowStartTick = 0} ⇒ **未冷却态返回 100**（「窗口长度」被当作「结束 tick」），
     * 与 {@link #isCoolingActive()} 自相矛盾、易被误读为「窗口结束于 tick 100」。
     * 窗口**内**取值逐位不变（仍为 `进入 tick + 100`，如 1000 → 1100）。
     * 只读 getter 的语义修正，**不改冷却状态机行为 / 触发条件 / 窗口长度**。
     */
    public synchronized long getCoolingWindowEndTick() {
        if (!coolingActive) return 0L;   // t23：窗口未开始/已结束 ⇒ 无窗口结束 tick
        return coolingWindowStartTick + COOLING_WINDOW_TICKS;
    }

    /**
     * DG-3：窗口剩余 tick = {@code max(0, windowEndTick − max(currentTick, windowStartTick))}，
     * 随 tick 递减至 0。
     *
     * <p><b>t22 语义修正</b>：**未处于冷却窗口时返回 0**。
     * 修前实现无条件计算 {@code windowEndTick − now}，而未冷却时 {@code coolingWindowStartTick = 0}
     * ⇒ {@code windowEndTick = 100} ⇒ 返回 {@code 100 − max(currentBookTick, 0)}；
     * 故 **`currentBookTick < 100` 时返回 100~0 的递减值（冷启动/首日窗口即暴露为「还剩 N tick」）、
     * `currentBookTick ≥ 100` 时本就返回 0** —— 与 {@link #isCoolingActive()} 矛盾，
     * 易被验证方/评审误读为「窗口正在进行」。窗口**内**行为逐位不变（仍由
     * {@code windowEndTick − tickCounter} 决定且不小于 0）。这是只读 getter 的语义修正，
     * **不改任何冷却状态机行为/触发条件/窗口长度**。
     */
    public synchronized long getCoolingWindowRemainingTicks() {
        if (!coolingActive) return 0L;   // t22：窗口未开始/已结束 ⇒ 剩余为 0
        long end = getCoolingWindowEndTick();
        long now = Math.max(currentBookTick, coolingWindowStartTick);
        return Math.max(0L, end - now);
    }

    /** DG-3：窗口轮次（1..COOLING_MAX_WINDOWS；由 MarketEngine 每 tick 注入，仅只读暴露）。 */
    public synchronized int getCoolingWindowRound() {
        return coolingWindowRound;
    }

    /** DG-3：窗口轮次注入（由 MarketEngine 的冷却状态机驱动；非随机、非持久化到本簿）。 */
    public synchronized void setCoolingWindowRound(int round) {
        this.coolingWindowRound = round > 0 ? round : 0;
    }

    /**
     * DG-3：窗口内「不可撤单」门是否生效（窗口内恒 true）。
     *
     * <p><b>t23 文档声明（D2，仅 Javadoc，行为未改）</b>：**未冷却时返回窗口内门的设计默认值 `true`**；
     * **是否生效请以 {@link #isCoolingActive()} 为准** —— 未冷却时 `cancelOrder` 的冷却门因
     * {@code coolingActive==false} 不会走该分支，故该 `true` **无行为影响**。读取方勿把本方法的
     * `true` 直接解读为「当前不可撤单」。
     */
    public synchronized boolean isCoolingNoCancel() {
        return coolingNoCancel;
    }

    /**
     * DG-3：窗口内「只能挂限价」门是否生效（窗口内恒 true）。
     *
     * <p><b>t23 文档声明（D2，仅 Javadoc，行为未改）</b>：**未冷却时返回窗口内门的设计默认值 `true`**；
     * **是否生效请以 {@link #isCoolingActive()} 为准** —— 未冷却时 `placeOrder` 的冷却门因
     * {@code coolingActive==false} 不会走该分支，故该 `true` **无行为影响**。读取方勿把本方法的
     * `true` 直接解读为「当前只能挂限价」。
     */
    public synchronized boolean isCoolingLimitOnly() {
        return coolingLimitOnly;
    }

    /** DG-3：窗口进入次数（长程审计，会话态）。 */
    public synchronized long getCoolingEnterCount() {
        return coolingEnterCount;
    }

    /** DG-3：最近一次进入窗口时清出的合成单数（玩家挂单不计入）。 */
    public synchronized long getLastRefSyntheticCleared() {
        return lastRefSyntheticCleared;
    }

    /** DG-3：记录进入窗口时清出的合成单数（由 MarketEngine 在 cancelAllSynthetic 后调用）。 */
    public synchronized void noteRefSyntheticCleared(long n) {
        this.lastRefSyntheticCleared = Math.max(0L, n);
    }

    /** DG-3：最近一次集合竞价出清价 P*（未出清 → 0）。 */
    public synchronized double getLastPStar() {
        return lastPStar;
    }

    /** DG-3：最近一次集合竞价出清量（0 = 未出清）。 */
    public synchronized long getLastPStarVolume() {
        return lastPStarVolume;
    }

    /** DG-3：连续「可交叉却零成交」tick 计数（由 MarketEngine 每 tick 注入，供探针只读断言）。 */
    public synchronized int getConsecutiveMatchFailures() {
        return consecutiveMatchFailures;
    }

    /** DG-3：注入连续撮合失败计数（引擎侧权威值；非随机、非持久化到本簿）。 */
    public synchronized void setConsecutiveMatchFailures(int n) {
        this.consecutiveMatchFailures = Math.max(0, n);
    }

    /** DG-3：盘口聚合交叉（= 双侧非空 && bestBid ≥ bestAsk；与 runLiqL25B ⑤ 既有语义同源）。 */
    public synchronized boolean isAggregateCross() {
        return !bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey();
    }

    /** DG-3：冷启动/读档兜底——把无簿状态的冷却计数清零（不改变 active 语义）。 */
    public synchronized void resetCoolingCounter() {
        this.coolingNewLimitOrders = 0;
    }

    // ---- DG-2 ★可观测面（分项可分离观测；供探针断言 AC 已接入真实撮合路径） ----

    /** DG-2：AC 项参与的档位数（>0 即证明 AC 真的作用在 walkBook 撮合路径，非「只加纯函数」）。 */
    public synchronized long getAcImpactHits() {
        return acImpactHits;
    }

    /** DG-2：最近一次撮合侧 ΔP_AC/mid（与 {@code impactAC(...)/mid} 独立复算一致 ≤1e-9）。 */
    public synchronized double getAcRatioLast() {
        return acRatioLast;
    }

    /** DG-2：最近一次撮合侧既有深度冲击比例（= {@code impact(...)/mid}，逐位可对照）。 */
    public synchronized double getDepthRatioLast() {
        return depthRatioLast;
    }

    /** DG-2：AC 比例采样均值 = Σ(ΔP_AC/mid) / max(1, 采样数)。 */
    public synchronized double getAcRatioMean() {
        return acSampleCount > 0 ? acRatioSum / acSampleCount : 0.0;
    }

    /** DG-2：最近一次 γ（自 MarketImpact，∈[0.5,0.7]）。 */
    public synchronized double getLastGamma() {
        return lastGamma;
    }

    /** DG-2：最近一次 λ_eff（自 MarketImpact）。 */
    public synchronized double getLastLambda() {
        return lastLambda;
    }

    /** DG-3：当前 AC 项簿侧生效位（探针因子隔离断言用）。 */
    public synchronized boolean isAlmgrenChrissEnabledForProbe() {
        return almgrenChrissEnabled;
    }

    /** DG-2：AC 分层采样次数（Σ 采样计数；0 = 本簿尚未在撮合路径上算过 AC 项）。 */
    public synchronized long getAcSampleCount() {
        return acSampleCount;
    }

    // ---- DG-2：最近一次 AC/深度冲击的**自变量快照**（探针可据同一组入参独立复算两分量） ----
    /** 最近一次撮合侧冲击的自变量：Q = 未成交主动单剩余量。 */
    public synchronized double getLastAcQty() { return lastAcQty; }
    /** 最近一次撮合侧冲击的自变量：ADV（市场成交量 V）。 */
    public synchronized double getLastAcAdv() { return lastAcAdv; }
    /** 最近一次撮合侧冲击的自变量：d₀（被动侧最优档显示深度）。 */
    public synchronized double getLastAcDepth() { return lastAcDepth; }
    /** 最近一次撮合侧冲击的自变量：mid 锚价。 */
    public synchronized double getLastAcMid() { return lastAcMid; }
    /** 最近一次撮合侧冲击的自变量：cumQty（既有深度冲击用的已成交量）。 */
    public synchronized double getLastAcCumQty() { return lastAcCumQty; }
    /** 最近一次撮合侧冲击的自变量：主动方是否买（决定冲击符号）。 */
    public synchronized boolean isLastAcBuy() { return lastAcBuy; }

    /**
     * LIQ F5（评审决议）：重置价格连续性锚 {@code lastFillPrice}（MS-5b 连续性夹断的相对基准）。
     * 交易日边界调用：否则上一交易日的成交锚随簿跨日残留，首 tick 陈旧跨日单成交时连续性夹断会把
     * 「本已被涨跌停带钳到带内」的价格再相对旧锚拉出带（|log|≤5%/步 链式上行越带）→ 期界带外成交。
     * 传入当日 prevClose（新带锚）后，首个成交的连续性相对当日基准，不再越新带。确定性、无随机；
     * anchor≤0 时清空锚（回到无锚语义，冷启动等价）。
     */
    public synchronized void resetContinuityAnchor(double anchor) {
        this.lastFillPrice = anchor > 0 ? anchor : 0.0;
        // DG-1：笼子锚（最新成交价）与连续性锚同源同刷新——否则跨日残留的旧成交价会成为新交易日的
        // 笼子锚，把当日正常报价（离昨收 >10% 但离今开很近）系统性拒掉（长程 cageRejectHits≠0）。
        this.lastTradePrice = this.lastFillPrice;
    }

    /**
     * CM A-1（CAPM_SPEC §4.2 A-1.5）：触碰价格连续性锚 {@code lastFillPrice}——集合竞价出清成功后
     * 以 P* 更新锚（round2(P*)），使竞价后连续竞价首笔成交相对「竞价出清价」而非过期旧锚夹断
     * （GAP-1 第二跳空窗口封堵）。确定性、无随机；price 非法（NaN/Inf/≤0）时不更新（防御性，正常不可达）。
     */
    public synchronized void touchContinuityAnchor(double price) {
        if (price > 0 && Double.isFinite(price)) {
            this.lastFillPrice = Math.round(price * 100.0) / 100.0;
            // DG-1：集合竞价出清价 P* 同步成为笼子锚（§4.4「出清后 setLastTradePrice(P*)」）
            this.lastTradePrice = this.lastFillPrice;
        }
    }

    /** 当前价格连续性锚（最近成交价 / 交易日开盘重置锚；0 = 无锚）。只读，供 CM 探针/验证断言。 */
    public synchronized double getLastFillPrice() {
        return lastFillPrice;
    }

    /** CM（CAPM_SPEC §4.3）：注入 cm.clearing 簿侧生效位。false → 竞价包络/锚更新/护栏计数零执行（CM-6 因子隔离）。 */
    public synchronized void setClearingEnabled(boolean enabled) {
        this.clearingEnabled = enabled;
    }

    /** 当前 cm.clearing 簿侧生效位。 */
    public synchronized boolean isClearingEnabled() {
        return clearingEnabled;
    }

    /** CM A-2（GAP-3）：异常点位护栏命中计数（运行期瞬态；正常长程 = 0，探针只读断言 = 0）。 */
    public synchronized long getInvalidFillGuardHits() {
        return invalidFillGuardHits;
    }

    /**
     * CM A-2（GAP-3）：集中异常点位护栏。确定性、零随机；只计数/回退，不伪造价格——
     * 正常撮合链（band→continuity→band + round）产出的价格恒为 valid 带内价 → 本方法恒等返回，
     * 不改任何既有成交语义。规则：
     * <ul>
     *   <li>p 为 NaN/Inf 或 ≤ 0（不可用）→ 计数 {@code invalidFillGuardHits}++ 并返回 -1；
     *       调用点据此跳过该笔成交/档位（防御性：正常永不触发）；</li>
     *   <li>p 出涨跌停带（A 股开启时注入带；未注入/关闭时为无界带，恒不越界）→ 计数并回退 band 端点
     *       （既有带内语义双保险，带末钳制后实际不可达）；</li>
     *   <li>valid 带内 p → 原样返回（恒等）。</li>
     * </ul>
     * {@code cm.clearing=false}（clearingEnabled=false）时护栏零执行：恒等返回 p、不计数
     * （CAPM_SPEC §4.3，CM-6 因子隔离；调用点另已按门跳过）。
     */
    public synchronized double sanitizeFill(double p) {
        if (!clearingEnabled) return p;
        if (!Double.isFinite(p) || p <= 0) {
            invalidFillGuardHits++;
            return -1.0;
        }
        if (p < limitDown || p > limitUp) {
            invalidFillGuardHits++;
            return Math.round(PriceDiscoveryEngine.aShareClamp(p, limitUp, limitDown) * 100.0) / 100.0;
        }
        return p;
    }

    /**
     * MS-5b：相对上一笔成交价的连续性夹断。若 |log(price/anchor)| > M，
     * 把 price 夹到 anchor*exp(±M)（保持方向），返回夹断后的价格。确定性、无随机。
     * 仅冻结开局极端跳空（连续竞价逐 tick 价格平滑），不与涨跌停带冲突（M=5% < A 股 ±10%，
     * 连续性约束更严，故取「更严者」等效于先涨跌停带后连续性夹断）。
     */
    private static double clampForContinuity(double price, double anchor) {
        if (anchor <= 0 || price <= 0) return price;
        double logMove = Math.log(price / anchor);
        if (Math.abs(logMove) <= CONTINUITY_MAX_LOG_MOVE) return price;
        double clampedLog = Math.max(-CONTINUITY_MAX_LOG_MOVE, Math.min(CONTINUITY_MAX_LOG_MOVE, logMove));
        return anchor * Math.exp(clampedLog);
    }

    public synchronized void placeOrder(Order order) {
        // DG-3（DATA_GUARD_SPEC §4.3）：冷却窗口内「只能挂限价、不能撤销」。
        // 市价单不入簿（返回；MarketEngine.submitOrder 对玩家市价单另有中文提示），限价单入簿并计入
        // 早收条件计数 coolingNewLimitOrders。冷却关闭时本段零执行 → 与交付基线逐位一致（因子隔离）。
        if (coolingActive) {
            if (!order.isLimitOrder()) return;
            coolingNewLimitOrders++;
        }
        // 未指定挂单 tick 时，自动记录当前订单簿 tick
        if (order.getPlacedTick() == 0) order.setPlacedTick(currentTick);
        TreeMap<Double, Queue<Order>> book = order.isBuy() ? bids : asks;
        // F1 兜底（MS-AI-5 防堆积）：单侧 resting 订单数到达绝对上限时拒绝新单，防止异常累积成假深度。
        // 正常配合每 tick 的 purgeExpired 后远低于该上限，仅在真实堆积异常时作为安全阀触发。
        if (countResting(book) >= MAX_RESTING_ORDERS_PER_SIDE) return;
        book.computeIfAbsent(order.getLimitPrice(), k -> new ConcurrentLinkedQueue<>()).add(order);
        System.out.println("📋 挂单: " + order.getAssetId() + " " + (order.isBuy() ? "买" : "卖") + " " + order.getLimitPrice() + " x " + order.getTotalQuantity());
    }

    /** F1 兜底：统计单侧 resting 订单数（防堆积上限判定用），确定性、无随机。 */
    private static int countResting(TreeMap<Double, Queue<Order>> book) {
        int n = 0;
        for (Queue<Order> q : book.values()) n += q.size();
        return n;
    }

    /**
     * 查询指定玩家的全部未成交（resting）订单（含部分成交剩余），按 买/卖 侧与价位自然顺序返回。
     * 仅返回真实玩家订单（非合成流：代理/ZI 不参与真实账户，AFTER t4 前端交易面板只展示真实挂单）。
     * 只读、确定性、无随机。供 HTTP/bridge 委托列表（/api/orders）使用。
     */
    public synchronized List<Order> getOrdersByPlayer(java.util.UUID playerId) {
        List<Order> out = new ArrayList<>();
        if (playerId == null) return out;
        collectPlayerOrders(bids, playerId, out);
        collectPlayerOrders(asks, playerId, out);
        return out;
    }

    private void collectPlayerOrders(TreeMap<Double, Queue<Order>> book, java.util.UUID playerId, List<Order> out) {
        for (Map.Entry<Double, Queue<Order>> e : book.entrySet()) {
            for (Order o : e.getValue()) {
                if (o.getPlayerId() != null && o.getPlayerId().equals(playerId) && !o.isSynthetic()) {
                    out.add(o);
                }
            }
        }
    }

    /**
     * 确定性撤单：从买/卖队列中移除指定 orderId 的挂单（无论是否部分成交，撤销其剩余量），
     * 空档位一并清理。撤销只是把 resting 单从队列移除，不改变其余订单的价格/时间优先级，
     * 不影响撮合语义（A 股标准撤单行为）。确定性、无随机。
     *
     * @return 是否确有撤销（false 表示该单不在簿中）
     */
    public synchronized boolean cancelOrder(java.util.UUID orderId) {
        // DG-3（DATA_GUARD_SPEC §4.3）：冷却窗口内不接受撤销（返回 false，语义同「未撤到」）。
        // 这是撤单的唯一入口（CancelOrderPacket 消费）→ 窗口内撤单被硬门拒绝；窗口外零影响（逐位一致）。
        if (coolingActive && coolingNoCancel) return false;
        if (orderId == null) return false;
        boolean removed = false;
        removed |= cancelFromSide(bids, orderId);
        removed |= cancelFromSide(asks, orderId);
        return removed;
    }

    private boolean cancelFromSide(TreeMap<Double, Queue<Order>> book, java.util.UUID orderId) {
        boolean removed = false;
        Iterator<Map.Entry<Double, Queue<Order>>> it = book.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Double, Queue<Order>> e = it.next();
            Queue<Order> q = e.getValue();
            boolean r = q.removeIf(o -> o.getOrderId().equals(orderId));
            if (r) removed = true;
            if (q.isEmpty()) it.remove();
        }
        return removed;
    }

    /** 推进订单簿 tick（外部调用方可用游戏 tick 同步；未调用则每次撮合/挂单自增）。 */
    public synchronized void setCurrentTick(long tick) {
        this.currentTick = Math.max(this.currentTick, tick);
        // DG-3 ★可观测面：记录最近一次注入的 tick（窗口剩余量计算用；不会小于窗口起始 tick）
        this.currentBookTick = Math.max(this.currentBookTick, tick);
    }

    /**
     * P0-5 G-09：清理超龄未成交单（currentTick - placedTick >= expireTicks）。
     * 空档位价格一并移除，消除陈旧单堆积的虚假深度。
     */
    public synchronized void purgeExpired(long currentTick) {
        this.currentTick = Math.max(this.currentTick, currentTick);
        purgeSide(bids);
        purgeSide(asks);
    }

    public synchronized void purgeExpired() {
        purgeExpired(this.currentTick);
    }

    private void purgeSide(TreeMap<Double, Queue<Order>> book) {
        Iterator<Map.Entry<Double, Queue<Order>>> it = book.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Double, Queue<Order>> e = it.next();
            Queue<Order> q = e.getValue();
            q.removeIf(o -> o.isExpired(currentTick));
            if (q.isEmpty()) it.remove();
        }
    }

    // ================= P2-3 价格冲击 / 市场深度曲线 =================
    // 替代 P0 的纯随机滑点 (rng−0.5)·slippage·mid：改为随深度消耗的确定性价格冲击，
    // 大单吃单越深、冲击越大，并按档位部分成交（walk the book）。全部走 SeedableRandom
    // （residualNoise 默认 0 → 撮合纯确定性，同 engineSeed 完全可复现，比随机滑点更稳）。
    //
    // F3 修复：价格冲击/深度曲线参数收敛到独立 MarketImpact 类（engine/microstructure/MarketImpact.java），
    // 参数取自 CalibrationParams.DEFAULT 校准先验（k_sqrt/k_linear/depthDecayExponent/volClusteringPersistence），
    // 不再硬编码在本类。walkBook 仍内联以直接操作被动侧 bid/ask 队列内部结构。
    private final com.example.financialmarket.engine.microstructure.MarketImpact impactModel;

    /**
     * P2-3：让主动吃单方（aggressor）walk the book——逐档消费被动方深度，
     * 每档成交价按真实档位价 + 随累计量增长的冲击量恶化，直到吃满被动盘或主动单满足。
     * 返回本次 walk 产生的成交；主动单与被动单的剩余量/队列在此推进。
     *
     * <p><b>DG-2 根因修复（DATA_GUARD_SPEC §3.2 F-AC-1，DATA_GUARD_SPEC §10.4 建模假设）</b>：
     * <ol>
     *   <li>循环上界由固定 5 档（{@code impactModel.getMaxDepthLevels()}）改为
     *       {@code AC_MAX_LEVELS=20}，并在**显示档位耗尽后**继续消耗「合成补充档」；</li>
     *   <li>合成补充档 = 仿真流动性（做市商补单/隐藏流动性），深度按 {@code AC_SYNTH_LEVEL_MULT=0.5}
     *       逐档衰减、价格沿不利方向步进 1 tick（A 股最小变动单位），**不入簿、不落盘、不进 getSnapshot、
     *       不计入 getTotalQuantity**，仅本次 walk 内可见，且单次 walk 累计消耗受预算约束
     *       {@code min(max(0.5×ADV, AC_SYNTH_QTY_BUDGET), AC_SYNTH_QTY_BUDGET)}；</li>
     *   <li><b>合成档只允许「合成流主动单」消耗</b>（{@code aggressor.isSynthetic()}）：真实玩家的主动单在
     *       显示档耗尽后立即停止（残余走既有 executeMarketableOrder 残余转限价路径）→ 真实玩家永远只与
     *       可见盘口深度成交，不出现「吃隐形流动性凭空得股」（DG-2d）；</li>
     *   <li>AC 冲击项用 {@code aggRemaining}（**未成交量**）而非 {@code cumQty}（已成交量）驱动——剩余巨量
     *       一路把价格压下去直到被涨跌停带钳住，根除「巨额卖单压顶但价格死锁」。</li>
     * </ol>
     * {@code almgrenChrissEnabled=false} 时合成档与 AC 项整体零执行（循环上界回落既有 5 档）→ 与交付
     * 基线逐位一致（DG-2b 对照探针）。
     */
    private List<Trade> walkBook(Order aggressor, boolean buyAggressor,
                                 TreeMap<Double, Queue<Order>> passiveBook,
                                 double midPrice) {
        List<Trade> trades = new ArrayList<>();
        int aggRemaining = aggressor.getRemainingQuantity();
        if (aggRemaining <= 0 || passiveBook.isEmpty()) return trades;

        double aggLimit = aggressor.getLimitPrice();
        // d0 = 被动侧最优档量（既有语义，d₀ 在整个 walk 内固定为「显示盘口规模」参照，不随消耗漂移）
        double d0 = passiveBook.firstEntry().getValue().stream()
                .mapToInt(Order::getRemainingQuantity).sum();
        if (d0 <= 0) d0 = aggRemaining;

        // DG-2：合成补充档预算（本 walk 内局部累计；ADV=0 时用 AC_SYNTH_QTY_BUDGET 兜底，F-AC-3 同族）
        double synthBudget = Math.min(
                Math.max(marSVolume * calSynthBudgetAdvScale, calSynthQtyBudget),
                calSynthQtyBudget);
        double synthConsumed = 0.0;
        int displayedIndex = 0;
        double synthQty = 0.0;
        double synthPrice = 0.0;
        boolean useSynth = false;
        // DG-2 ★可观测面：分项观测只累计到**局部变量**（§9.9 观察成本纪律），循环后一次性回写字段
        long localAcHits = 0;
        double localAcSum = 0.0;
        long localSamples = 0;
        double localAcLast = acRatioLast;
        double localDepthLast = depthRatioLast;
        double localGamma = lastGamma;
        double localLambda = lastLambda;
        double localAcQty = lastAcQty;
        double localAcAdv = lastAcAdv;
        double localAcDepth = lastAcDepth;
        double localAcMid = lastAcMid;
        double localAcCumQty = lastAcCumQty;
        boolean localAcBuy = lastAcBuy;

        int levelIndex = 0;
        double cumQty = 0;
        while (aggRemaining > 0 && levelIndex < maxWalkLevels()) {
            double pLevel;
            double levelQty;
            Queue<Order> pQueue = null;
            boolean synthLevel;
            if (!passiveBook.isEmpty()) {
                pLevel = passiveBook.firstKey();
                // 限价约束：主动单价格受限（市价单 limit=0 视为不限）
                if (buyAggressor && aggLimit > 0 && pLevel > aggLimit) break;
                if (!buyAggressor && aggLimit > 0 && pLevel < aggLimit) break;
                pQueue = passiveBook.get(pLevel);
                levelQty = sumQueue(pQueue);
                if (levelQty <= 0) {
                    passiveBook.remove(pLevel);
                    continue;
                }
                displayedIndex++;
                synthPrice = pLevel;
                synthQty = levelQty;
                synthLevel = false;
            } else if (useSynth) {
                // 显示档耗尽 → 合成补充档（DG-2 F-AC-1）：价格沿不利方向 1 tick、深度 ×0.5 衰减
                if (synthConsumed >= synthBudget) break;
                synthQty = synthQty * AC_SYNTH_LEVEL_MULT;
                if (synthQty <= 0) break;
                pLevel = Math.round((buyAggressor
                        ? synthPrice - PriceDiscoveryEngine.A_SHARE_TICK_SIZE
                        : synthPrice + PriceDiscoveryEngine.A_SHARE_TICK_SIZE) * 100.0) / 100.0;
                if (pLevel <= 0) break;
                if (buyAggressor && aggLimit > 0 && pLevel > aggLimit) break;
                if (!buyAggressor && aggLimit > 0 && pLevel < aggLimit) break;
                levelQty = synthQty;
                synthPrice = pLevel;
                synthLevel = true;
            } else {
                break; // 无显示档且无合成锚（或 AC 关闭）→ 退出（残余走 executeMarketableOrder 路径）
            }

            // 既有深度冲击项（kSqrt/kLinear/d₀ 与公式不变，用 cumQty）
            double depthImpact = impactModel.impact(cumQty, d0, midPrice, buyAggressor);
            double impactVal = depthImpact;
            // MarS MP-2：叠加市场影响项 Δ=α·σ·√(Q/V)（既有项，公式与数值不变）
            if (marSImpactEnabled && marSSigma > 0 && marSVolume > 0) {
                impactVal += impactModel.impactMarS(aggRemaining, marSSigma, marSVolume, midPrice, buyAggressor);
            }
            // DG-2：叠加 Almgren-Chriss 幂律冲击项 ΔP_AC=λ_eff·(|Q|/Q0)^γ·mid（**用未成交量 aggRemaining**，
            // 同符号相加；almgrenChrissEnabled=false 或非 P2 路径时零执行 → 既有两项数值逐位不变）。
            // ★可观测面：本项与既有两项**分项可分离**（depthImpact 与 acImpact 各自单独累计/记录）。
            if (almgrenChrissEnabled) {
                double acImpact = impactModel.impactAC(aggRemaining, marSVolume, d0, midPrice, buyAggressor);
                impactVal += acImpact;
                if (midPrice > 0) {
                    localAcHits++;
                    localAcSum += Math.abs(acImpact) / midPrice;
                    localSamples++;
                    localAcLast = Math.abs(acImpact) / midPrice;
                    localGamma = impactModel.getLastGamma();
                    localLambda = impactModel.getLastLambda();
                }
            }
            // ★可观测面：既有深度冲击比例**无条件**采样（AC 关闭时亦可对照 impact()/mid，逐位可核）
            if (midPrice > 0) {
                localDepthLast = Math.abs(depthImpact) / midPrice;
                // 自变量快照（局部累计，循环后回写）：探针据此用同一组入参独立复算分项
                localAcQty = aggRemaining;
                localAcAdv = marSVolume;
                localAcDepth = d0;
                localAcMid = midPrice;
                localAcCumQty = cumQty;
                localAcBuy = buyAggressor;
            }
            // A 股涨跌停钳制（AS-2）：成交价夹到 [跌停,涨停]，价格不越过涨跌停（无界时原样）。
            double fillPrice = PriceDiscoveryEngine.aShareClamp(Math.round((pLevel + impactVal) * 100) / 100.0, limitUp, limitDown);
            // MS-5b 价格连续性硬约束：相对最近成交价夹断（防开局跳空），再取整。无随机、确定性。
            if (lastFillPrice > 0) fillPrice = clampForContinuity(fillPrice, lastFillPrice);
            // LIQ F5（round-3 修复）：涨跌停带为 A 股硬规则，<b>最后</b>生效——交易日边界首 tick 若连续性
            // 锚（lastFillPrice）残留上一交易日的旧价位，连续性夹断（≤5%/步）会把本已带内的价格再相对旧锚
            // 拉出<b>当日新带</b>（t8 全窗残留 2/123,671 例：px=5.71>bandUp=5.62）。故在连续性之后对成交价
            // 再做一次带内钳制（带内时恒等、零副作用），保证成交价永不出当日涨跌停带。
            fillPrice = Math.round(PriceDiscoveryEngine.aShareClamp(fillPrice, limitUp, limitDown) * 100.0) / 100.0;
            // CM A-2（GAP-3）：集中异常点位护栏（cm.clearing 门内）。valid 带内价为恒等 → band→continuity→
            // band 次序与成交价不变；不可用价（NaN/Inf/≤0，理论不可达）→ 审计计数（sanitizeFill 内已落）并
            // 跳过本档位（移除该档防 walk 无进展；不产生伪价成交，CM-1c「无异常点位」的代码侧保证）。
            if (clearingEnabled) {
                double guarded = sanitizeFill(fillPrice);
                if (guarded < 0) {
                    if (!synthLevel) passiveBook.remove(pLevel);
                    continue;
                }
                fillPrice = guarded;
            }
            // DG-1（DATA_GUARD_SPEC §2.2 第 2 处调用点）：成交价笼子夹断——与 AS-2 带同为硬规则，作为
            // band→continuity→band→sanitizeFill 链的**最后一步**。锚 = 上一笔成交价，新价天然满足
            // |Δ|≤5%（连续性）< 笼子半径 10%，故正常链上恒等（零行为影响）；作用是封堵「市价通道/竞价 P*
            // 绕过连续性锚」的路径。笼子端点由 min/max(带端点) 派生 → 夹断后仍在带内（不破坏带不变量）。
            if (priceCageActive()) fillPrice = clampToCage(fillPrice);
            lastFillPrice = fillPrice;
            lastTradePrice = fillPrice;   // DG-1：笼子锚随成交价推进（会话态）

            if (synthLevel) {
                // 合成补充档：被动侧 playerId 恒为 null（与「合成流侧记 null」既有约定一致，Trade 语义不变）；
                // 不触碰任何簿内队列，仅在本 walk 内推进价格与剩余量。
                int take = Math.min(aggRemaining, (int) Math.max(1.0, Math.floor(levelQty)));
                if (take <= 0) break;
                java.util.UUID aggId = aggressor.isSynthetic() ? null : aggressor.getPlayerId();
                UUID buyerId = buyAggressor ? aggId : null;
                UUID sellerId = buyAggressor ? null : aggId;
                trades.add(new Trade(aggressor.getAssetId(), fillPrice, take,
                        System.currentTimeMillis(), buyAggressor, buyerId, sellerId));
                aggressor.fill(take);
                aggRemaining = aggressor.getRemainingQuantity();
                cumQty += take;
                synthConsumed += take;
            } else {
                while (aggRemaining > 0 && !pQueue.isEmpty()) {
                    Order passive = pQueue.peek();
                    int take = Math.min(aggRemaining, passive.getRemainingQuantity());
                    if (take <= 0) { pQueue.poll(); continue; }

                    // A 股 T+1 归属（AS-3）：买/卖方 playerId；合成流（代理/ZI）该侧记 null，跳过真实账户交收
                    UUID buyerId = (buyAggressor ? aggressor.isSynthetic() : passive.isSynthetic())
                            ? null : (buyAggressor ? aggressor.getPlayerId() : passive.getPlayerId());
                    UUID sellerId = (buyAggressor ? passive.isSynthetic() : aggressor.isSynthetic())
                            ? null : (buyAggressor ? passive.getPlayerId() : aggressor.getPlayerId());

                    trades.add(new Trade(aggressor.getAssetId(), fillPrice, take,
                            System.currentTimeMillis(), buyAggressor, buyerId, sellerId));
                    aggressor.fill(take);
                    passive.fill(take);
                    aggRemaining = aggressor.getRemainingQuantity();
                    cumQty += take;
                    // LIQ P2：恰一侧为专职 MM 报价 → MM 存货记账（I(t) 随真实成交演化，LQ-1）
                    notifyMmFillIfTagged(buyAggressor ? aggressor : passive,
                            buyAggressor ? passive : aggressor, fillPrice, take);

                    if (passive.isFilled()) pQueue.poll();
                }
            }
            if (!synthLevel && passiveBook.get(pLevel) != null && passiveBook.get(pLevel).isEmpty()) {
                passiveBook.remove(pLevel);
            }
            // 显示档耗尽后启用合成补充档（DG-2）；真实玩家主动单不参与合成档（DG-2d 硬约定）
            if (passiveBook.isEmpty()) {
                if (displayedIndex > 0 && aggressor.isSynthetic() && almgrenChrissEnabled) {
                    useSynth = true;
                } else {
                    break;
                }
            }
            levelIndex++;
        }
        // DG-2 ★可观测面：循环结束后一次性回写（观察成本纪律：热路径内零日志/零字符串拼接）
        acImpactHits += localAcHits;
        acRatioSum += localAcSum;
        acSampleCount += localSamples;
        acRatioLast = localAcLast;
        depthRatioLast = localDepthLast;
        lastGamma = localGamma;
        lastLambda = localLambda;
        lastAcQty = localAcQty;
        lastAcAdv = localAcAdv;
        lastAcDepth = localAcDepth;
        lastAcMid = localAcMid;
        lastAcCumQty = localAcCumQty;
        lastAcBuy = localAcBuy;
        return trades;
    }

    /** DG-2：本次 walk 的档位上限（AC 开启 = AC_MAX_LEVELS，关闭 = 既有 maxDepthLevels=5）。 */
    private int maxWalkLevels() {
        return almgrenChrissEnabled ? AC_MAX_LEVELS : impactModel.getMaxDepthLevels();
    }

    /**
     * P2-3：撮合入口（保留原方法名/返回类型，兼容 MarketEngine.tick 与 submitOrder）。
     * P2 开启（impactEnabled=true，默认）：随深度消耗的价格冲击 + 按档位部分成交（市场深度曲线），
     * 确定性（residualNoise=0），同种子可复现，且用净订单流失衡回馈主动方方向（F1）。
     * P2 关闭（impactEnabled=false，F2 修复）：回退 P0 纯随机滑点语义 (rng−0.5)·slippage·mid，
     * 使「关 P2 → 与 P0/P1 逐位一致」可在真实代码路径验证；随机仍走可播种 rng（可复现）。
     *
     * <p><strong>价格优先 + 时间优先 + 连续竞价（A 股 AS-5）</strong>：本撮合循环已由数据结构保证并显式满足
     * （A3-6）：
     * <ul>
     *   <li><strong>价格优先</strong>：{@code bids} 为 {@link Comparator#reverseOrder()} 的 TreeMap（最高买价在最前），
     *       {@code asks} 为升序 TreeMap（最低卖价在最前），撮合恒取 {@code bestBid}/{@code bestAsk}（价格最高/最低者先成交）；</li>
     *   <li><strong>时间优先</strong>：每价格档为 FIFO {@link ConcurrentLinkedQueue}，{@code walkBook} 用
     *       {@code pQueue.peek()} 取队首最老被动单先成交（同价先到先成交）；主动方判定亦用 {@code placedTick}（先到者为被动）；</li>
     *   <li><strong>连续竞价</strong>：撮合在每 tick（MarketEngine.tick L3）与每笔下单（submitOrder）即时执行，
     *       订单到达即撮合，无集合竞价。</li>
     * </ul>
     * 成交价由 A 股涨跌停带（{@link #setLimitBand}）钳制在 [跌停,涨停]（A3-3）。
     */
    public synchronized List<Trade> matchWithSlippage(double slippageFactor) {
        // P0-5 G-09：撮合前置清理超龄未成交单
        currentTick++;
        purgeSide(bids);
        purgeSide(asks);

        // ---- F2 修复：P2 关闭时回退 P0 纯随机滑点语义 ----
        if (!impactEnabled) {
            return matchWithP0RandomSlippage(slippageFactor);
        }

        List<Trade> trades = new ArrayList<>();
        while (!bids.isEmpty() && !asks.isEmpty()) {
            double bestBid = bids.firstKey();
            double bestAsk = asks.firstKey();
            if (bestBid < bestAsk) break;

            Order bid = bids.get(bestBid).peek();
            Order ask = asks.get(bestAsk).peek();
            double midPrice = (bestBid + bestAsk) / 2.0;

            // 确定性判定主动吃单方（aggressor）：先到者（更小 placedTick）为被动挂单；
            // 同 tick（同批下单）取剩余量更大者（大单 take 流动性）；
            // 仍并列则按净订单流失衡回馈方向（F1：净买失衡>0 → 买主动方，价格向净流向移动）；
            // 净失衡为 0 时回退买主动（P0 原语义）。
            boolean buyAggressor;
            if (bid.getPlacedTick() != ask.getPlacedTick()) {
                buyAggressor = bid.getPlacedTick() > ask.getPlacedTick();
            } else if (bid.getRemainingQuantity() != ask.getRemainingQuantity()) {
                buyAggressor = bid.getRemainingQuantity() > ask.getRemainingQuantity();
            } else if (netImbalance != 0.0) {
                buyAggressor = netImbalance > 0.0;
            } else {
                buyAggressor = true;
            }

            Order aggressor = buyAggressor ? bid : ask;
            TreeMap<Double, Queue<Order>> passiveBook = buyAggressor ? asks : bids;

            trades.addAll(walkBook(aggressor, buyAggressor, passiveBook, midPrice));

            // 清理主动单：若已被吃满则移除，空档位一并删除
            Queue<Order> aggQueue = buyAggressor ? bids.get(bestBid) : asks.get(bestAsk);
            if (aggQueue != null) {
                if (aggressor.isFilled()) aggQueue.remove(aggressor);
                if (aggQueue.isEmpty()) {
                    if (buyAggressor) bids.remove(bestBid); else asks.remove(bestAsk);
                }
            }
        }
        if (!trades.isEmpty()) {
            System.out.println("📊 撮合 " + trades.size() + " 笔成交");
        }
        return trades;
    }

    /**
     * F2 修复：P0 纯随机滑点撮合语义（P2 关闭时的回退路径）。
     * 与 P0 原实现一致：成交价 = 交叉点 + (rng.nextDouble()−0.5)·slippage·mid；随机走可播种
     * rng，同 engineSeed 可复现。用于「关 P2 → 与 P0/P1 基线逐位一致」的真实代码路径验证。
     */
    private synchronized List<Trade> matchWithP0RandomSlippage(double slippageFactor) {
        List<Trade> trades = new ArrayList<>();
        while (!bids.isEmpty() && !asks.isEmpty()) {
            double bestBid = bids.firstKey();
            double bestAsk = asks.firstKey();
            if (bestBid < bestAsk) break;
            double midPrice = (bestBid + bestAsk) / 2.0;
            double slippage = (rng.nextDouble() - 0.5) * slippageFactor * midPrice;
            // A 股涨跌停钳制（AS-2）：成交价夹到 [跌停,涨停]，价格不越过涨跌停（无界时原样）。
            double fillPrice = PriceDiscoveryEngine.aShareClamp(Math.round((midPrice + slippage) * 100) / 100.0, limitUp, limitDown);
            // MS-5b 价格连续性硬约束：相对最近成交价夹断（防开局跳空）。确定性、无随机。
            if (lastFillPrice > 0) fillPrice = clampForContinuity(fillPrice, lastFillPrice);
            // LIQ F5（round-3）：涨跌停带最后生效，防跨日旧连续性锚把带内成交价拉出当日带（P0 回退路径同修）
            fillPrice = Math.round(PriceDiscoveryEngine.aShareClamp(fillPrice, limitUp, limitDown) * 100.0) / 100.0;
            // CM A-2（GAP-3）：集中异常点位护栏（cm.clearing 门内，P0 回退路径同修）。valid 恒等；
            // 不可用价（NaN/Inf/≤0，理论不可达）→ 审计计数并退出本轮撮合（不产生伪价成交）。
            if (clearingEnabled) {
                double guarded = sanitizeFill(fillPrice);
                if (guarded < 0) break;
                fillPrice = guarded;
            }
            // DG-1（DATA_GUARD_SPEC §2.2 第 2 处调用点，P0 回退路径同修）：成交价笼子夹断（链的最后一步）。
            // 笼子未生效/笼内价时恒等；笼子端点由带端点派生 → 夹断后仍在带内，不破坏 A 股带不变量。
            if (priceCageActive()) fillPrice = clampToCage(fillPrice);
            lastFillPrice = fillPrice;
            lastTradePrice = fillPrice;   // DG-1：笼子锚随成交价推进（会话态）
            Order bid = bids.get(bestBid).peek();
            Order ask = asks.get(bestAsk).peek();
            int take = Math.min(bid.getRemainingQuantity(), ask.getRemainingQuantity());
            if (take <= 0) break;
            // A 股 T+1 归属（AS-3）：bid 买方 / ask 卖方；合成流（代理/ZI）该侧记 null
            java.util.UUID buyerId = bid.isSynthetic() ? null : bid.getPlayerId();
            java.util.UUID sellerId = ask.isSynthetic() ? null : ask.getPlayerId();
            trades.add(new Trade(bid.getAssetId(), fillPrice, take, System.currentTimeMillis(), true, buyerId, sellerId));
            bid.fill(take);
            ask.fill(take);
            // LIQ P2：恰一侧为专职 MM 报价 → MM 存货记账（P0 回退撮合路径同样接线）
            notifyMmFillIfTagged(bid, ask, fillPrice, take);
            if (bid.isFilled()) { bids.get(bestBid).poll(); if (bids.get(bestBid).isEmpty()) bids.remove(bestBid); }
            if (ask.isFilled()) { asks.get(bestAsk).poll(); if (asks.get(bestAsk).isEmpty()) asks.remove(bestAsk); }
        }
        if (!trades.isEmpty()) System.out.println("📊 撮合(P0回退) " + trades.size() + " 笔成交");
        return trades;
    }

    // ================= LIQ P1（LIQUIDITY_SPEC §4.1/§4.2）：可市价执行通道 / 撮合前读侧访问器 =================

    /** LIQ：市价单残余量转限价开关（默认 true）；false 时未吃满的市价残余直接撤销。确定性、无随机。 */
    public synchronized void setConvertResidualToLimit(boolean convert) {
        this.convertResidualToLimit = convert;
    }

    public synchronized boolean isConvertResidualToLimit() {
        return convertResidualToLimit;
    }

    /**
     * LIQ P2：注册 MM 成交回调（由 MarketEngine 对每簿注册；回调经 agentManager.onMarketMakerFill
     * 更新专职 MM-Agent 存货）。参数 {@code (fillPrice, signedShares)}：{@code signedShares>0}=MM 买、
     * {@code <0}=MM 卖。确定性、无随机；null 可注销（未注册时撮合路径零开销零行为）。
     */
    public synchronized void setMmFillListener(java.util.function.BiConsumer<Double, Integer> listener) {
        this.mmFillListener = listener;
    }

    /**
     * LIQ P2：对「恰一侧为专职 MM 报价（mmTag 非空）」的一笔成交做 MM 存货记账回调。
     * 规则（确定性）：买方订单带 MM 标签且卖方不带 → MM 买 (+take)；卖方带且买方不带 → MM 卖 (−take)；
     * 双侧同带/均不带 → 0（不记账；单资产单 MM 不存在同带，双带防御性跳过防双计）。
     * 内部调用点：walkBook / P0 回退撮合 / runCallAuction（即全部真实成交路径）。无随机。
     */
    private void notifyMmFillIfTagged(Order buyerOrder, Order sellerOrder, double fillPrice, int take) {
        if (mmFillListener == null || take <= 0) return;
        String bTag = buyerOrder.getMmTag();
        String sTag = sellerOrder.getMmTag();
        int sign;
        if (bTag != null && sTag == null) sign = take;
        else if (sTag != null && bTag == null) sign = -take;
        else sign = 0;
        if (sign != 0) mmFillListener.accept(fillPrice, sign);
    }

    /** LIQ 读侧：买侧是否非空（确定性、无随机）。 */
    public synchronized boolean hasBids() { return !bids.isEmpty(); }

    /** LIQ 读侧：卖侧是否非空（确定性、无随机）。 */
    public synchronized boolean hasAsks() { return !asks.isEmpty(); }

    /** LIQ 读侧：最优买价（无买侧返回 0）。 */
    public synchronized double getBestBid() { return bids.isEmpty() ? 0.0 : bids.firstKey(); }

    /** LIQ 读侧：最优卖价（无卖侧返回 0）。 */
    public synchronized double getBestAsk() { return asks.isEmpty() ? 0.0 : asks.firstKey(); }

    /** LIQ 读侧：指定侧最优档剩余总量（bidsSide=true 为买侧；空侧返回 0）。 */
    public synchronized int getBestQuantity(boolean bidsSide) {
        TreeMap<Double, Queue<Order>> side = bidsSide ? bids : asks;
        if (side.isEmpty()) return 0;
        return (int) Math.min(sumQueue(side.firstEntry().getValue()), Integer.MAX_VALUE);
    }

    /** LIQ 读侧：指定侧全部 resting 剩余总量（int 上限钳制防溢出；空侧返回 0）。 */
    public synchronized int getTotalQuantity(boolean bidsSide) {
        TreeMap<Double, Queue<Order>> side = bidsSide ? bids : asks;
        long total = 0;
        for (Queue<Order> q : side.values()) total += sumQueue(q);
        return (int) Math.min(total, Integer.MAX_VALUE / 4);
    }

    private static long sumQueue(Queue<Order> q) {
        long s = 0;
        for (Order o : q) s += o.getRemainingQuantity();
        return s;
    }

    /** LIQ 读侧：当前成交价钳制带上限（setLimitBand 注入；未注入/关闭时为无界）。 */
    public synchronized double getLimitUp() { return limitUp; }

    /** LIQ 读侧：当前成交价钳制带下限（setLimitBand 注入；未注入/关闭时为无界）。 */
    public synchronized double getLimitDown() { return limitDown; }

    /**
     * LIQ P1（§4.1）：清出簿内 resting 的 MARKET 型订单（ZI/agent/meso 等合成流市价单在 L2.5 挂入簿内、
     * 仅在本 tick 内短暂停留；本方法保证它们以主动方身份经 {@link #executeMarketableOrder} 成交，绝不以
     * 0.0 价假档残存跨 tick，LQ-2b）。返回顺序确定性：卖侧先、买侧后，档内 FIFO（时间优先）。只移除
     * MARKET 型——旧档读入的 0.0 价 LIMIT 老单保持既有语义（读旧档兼容）。确定性、无随机。
     */
    public synchronized List<Order> drainRestingMarketOrders() {
        List<Order> out = new ArrayList<>();
        drainMarketSide(asks, out);
        drainMarketSide(bids, out);
        return out;
    }

    private static void drainMarketSide(TreeMap<Double, Queue<Order>> book, List<Order> out) {
        Iterator<Map.Entry<Double, Queue<Order>>> it = book.entrySet().iterator();
        while (it.hasNext()) {
            Queue<Order> q = it.next().getValue();
            List<Order> keep = new ArrayList<>();
            for (Order o : q) {
                if (o.isMarketOrder()) out.add(o);
                else keep.add(o);
            }
            if (keep.isEmpty()) {
                it.remove();
            } else if (keep.size() != q.size()) {
                q.clear();
                q.addAll(keep);
            }
        }
    }

    /**
     * LIQ P1（§4.1）：可市价执行通道——市价单（MARKET_BUY/MARKET_SELL）作为<b>主动方</b>立即 walk 对手侧
     * resting 档吃单（复用 {@link #walkBook}：成交价 = 被动侧真实档位价 + 冲击 + A 股涨跌停钳制 →
     * 成交与盘口同源，LQ-7），不再以 price=0.0 挂入簿末档/簿首档等待 resting 交叉（R-1 修复）。
     * 吃满或对手侧尽后仍有残余：convertResidualToLimit=true（默认）→ 残余以最近成交价（本会话无成交时
     * 回落簿 mid）为限价、钳制在涨跌停带内，挂回本侧排队（A 股可排队语义；新挂单永不落入 0.0 假档）；
     * =false → 残余直接撤销。确定性、无随机；返回本通道产生的成交（与 matchWithSlippage 同签名）。
     */
    public synchronized List<Trade> executeMarketableOrder(Order aggressor) {
        List<Trade> trades = new ArrayList<>();
        if (aggressor == null || aggressor.getRemainingQuantity() <= 0) return trades;
        boolean buyAggressor = aggressor.isBuy();
        TreeMap<Double, Queue<Order>> passiveBook = buyAggressor ? asks : bids;
        if (!passiveBook.isEmpty()) {
            trades.addAll(walkBook(aggressor, buyAggressor, passiveBook, getMidPrice()));
        }
        // 残余处理：转「已成交/参考价位」限价单挂回本侧（A 股可排队）或直接撤销
        // DG-2 F-AC-2（DATA_GUARD_SPEC §3.2）：残余挂回价 = **砸价后的最新成交价**（lastFillPrice 在 walk
        // 中被 AC 项一路推低），不得挂回砸价前的旧价——否则盘口仍呈现「巨额卖单压顶却不跌」的形态。
        int remaining = aggressor.getRemainingQuantity();
        if (remaining > 0 && convertResidualToLimit) {
            double ref = lastFillPrice > 0 ? lastFillPrice : getMidPrice();
            if (ref > 0) {
                double price = Math.round(PriceDiscoveryEngine.aShareClamp(ref, limitUp, limitDown) * 100.0) / 100.0;
                // CM A-2（GAP-3）：残余转限价挂单价集中护栏（cm.clearing 门内）。valid 价恒等；
                // 不可用价（NaN/Inf/≤0，理论不可达）→ 审计计数并撤销残余（0.0/NaN 假档绝不入簿）。
                if (clearingEnabled) {
                    double guarded = sanitizeFill(price);
                    if (guarded < 0) return trades;
                    price = guarded;
                }
                // DG-1 F-AC-2 一致性守卫：残余挂回价钳入笼子（锚 = 新成交价本身 → 正常恒等）。笼子端点由
                // 带端点派生，故夹断后仍在带内；笼子未生效/价非法时原样返回。
                if (priceCageActive() && Double.isFinite(price) && price > 0) {
                    price = Math.max(getCageDown(), Math.min(getCageUp(), price));
                }
                if (price > 0) {
                    Order residual = new Order(aggressor.getPlayerId(), aggressor.getAssetId(),
                            buyAggressor ? Order.Type.LIMIT_BUY : Order.Type.LIMIT_SELL,
                            remaining, price, currentTick);
                    residual.setSynthetic(aggressor.isSynthetic());
                    placeOrder(residual);
                }
            }
        }
        return trades;
    }

    /**
     * LIQ LQ-5（§4.6）：熔断撤单重挂的清空动作——清出本簿<b>全部 synthetic resting 挂单</b>
     * （MM/agent/ZI/Hawkes/meso 合成流），<b>真实玩家挂单不动</b>（A 股不回归：玩家委托=契约，引擎不代撤）。
     * 按侧遍历、档内 FIFO；空档位一并移除。确定性、无随机。
     *
     * @return 被撤合成单总数
     */
    public synchronized int cancelAllSynthetic() {
        int removed = 0;
        removed += cancelSyntheticSide(bids);
        removed += cancelSyntheticSide(asks);
        return removed;
    }

    private static int cancelSyntheticSide(TreeMap<Double, Queue<Order>> book) {
        int removed = 0;
        Iterator<Map.Entry<Double, Queue<Order>>> it = book.entrySet().iterator();
        while (it.hasNext()) {
            Queue<Order> q = it.next().getValue();
            List<Order> keep = new ArrayList<>();
            for (Order o : q) {
                if (o.isSynthetic()) removed++;
                else keep.add(o);
            }
            if (keep.isEmpty()) {
                it.remove();
            } else if (keep.size() != q.size()) {
                q.clear();
                q.addAll(keep);
            }
        }
        return removed;
    }

    /**
     * LIQ LQ-3（§6，瓦尔拉斯集合竞价兜底）：限价单无法交叉时临时转单资产集合竞价（模拟 A 股开盘/收盘竞价，
     * 不入全局日历）。算法：
     * <ol>
     *   <li>冻结快照：bids/asks 各自按价档聚合（每档 = 该档总剩余量；档内时间优先由 FIFO 队列保持）；</li>
     *   <li>候选价 = 落在涨跌停带内的全部 bid/ask 档位价；对每个候选 P 计算
     *       Q_buy(P)=Σ{bid价≥P 量}、Q_sell(P)=Σ{ask价≤P 量}、volume(P)=min(Q_buy,Q_sell)；</li>
     *   <li>求 P* = volume 最大者；tie-break（确定性固定次序）：① 与 {@code referencePrice}
     *       （prevClose，无则 gbmPrice）最近；② 仍并列取低价（候选价已去重，② 后即唯一）；</li>
     *   <li>P* 越带（候选集为空）或最大可出清量为 0 → 不竞价、返回空（MarketEngine 回落 P1 强制单路径）；</li>
     *   <li><b>出清</b>：全部 bid≥P* 与 ask≤P* 以单一价格 P* 成交（各自剩余量 min、档内时间优先），
     *       成交价=P*（P* 由盘口档位价聚合而来 → 成交-盘口天然同源）；未成交单留在簿中原价继续连续竞价。</li>
     * </ol>
     * 确定性、无随机；由 MarketEngine 在 L3 兜底判定后调用。
     */
    public synchronized List<Trade> runCallAuction(double referencePrice) {
        return runCallAuctionDetailed(referencePrice).trades;
    }

    /**
     * DG-3（DATA_GUARD_SPEC §4.4）：集合竞价**带结果**入口——逻辑与 {@link #runCallAuction(double)}
     * <b>完全一致</b>（出清数学零改动），额外返回 {@code pStar / cleared} 供 MarketEngine 判定冷却窗口
     * 是否需滚动（窗口后竞价仍零成交且仍聚合交叉 → 重新起一个窗口，最多 3 轮）。
     *
     * @param referencePrice 参照价（DG-3 传入均衡价 P_eq；tie-break 与连续性包络锚在此附近）
     * @return 本轮竞价结果（trades 为空时 pStar=0、cleared=false）
     */
    public synchronized CallAuctionResult runCallAuctionDetailed(double referencePrice) {
        List<Trade> trades = new ArrayList<>();
        if (bids.isEmpty() || asks.isEmpty()) return new CallAuctionResult(trades, 0.0, false, 0L, false);
        boolean cageFiltered = false;

        // ① 档位快照（bids 降序价 / asks 升序价，队列为簿内真实队列，出清时档内 FIFO 保序）
        List<Map.Entry<Double, Queue<Order>>> bidLevels = new ArrayList<>(bids.entrySet());
        List<Map.Entry<Double, Queue<Order>>> askLevels = new ArrayList<>(asks.entrySet());

        // ② 候选价 = 带内非空档位价（P* 须落在涨跌停带内；越带 → 不竞价回落 P1 强制单，spec §6.1 ④）
        // DG-1（DATA_GUARD_SPEC §2.2「逐笔价格区间限制」第四层）：竞价候选价追加**笼子过滤**——候选价须
        // 同时落在 [cageDown, cageUp] 内（与既有带过滤/连续性包络同层叠加，三者全满足才成候选），
        // 使竞价 P* 不得越出「最新成交价 ± cageRatio」的报价空间（杜绝异常点位入价）。
        java.util.TreeSet<Double> candidates = new java.util.TreeSet<>();
        for (Map.Entry<Double, Queue<Order>> e : bidLevels) {
            double p = e.getKey();
            if (p > 0 && p >= limitDown && p <= limitUp && sumQueue(e.getValue()) > 0) {
                if (!priceWithinCage(p)) { cageFiltered = true; continue; }
                candidates.add(p);
            }
        }
        for (Map.Entry<Double, Queue<Order>> e : askLevels) {
            double p = e.getKey();
            if (p > 0 && p >= limitDown && p <= limitUp && sumQueue(e.getValue()) > 0) {
                if (!priceWithinCage(p)) { cageFiltered = true; continue; }
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) return new CallAuctionResult(trades, 0.0, false, 0L, cageFiltered);

        // CM A-1（GAP-1，CAPM_SPEC §4.2）：集合竞价纳入同一价格连续性锚体系（cm.clearing 门内）。
        // 有锚（lastFillPrice>0）时求连续性包络 env=[max(limitDown,anchor·e^−M), min(limitUp,anchor·e^+M)]
        // （M=CONTINUITY_MAX_LOG_MOVE=0.05）；P* 候选在带内基础上再要求落在 env 内 → 封堵「簿聚合交叉但
        // 竞价 P* 相对最后一笔成交价跳空 >5%」的竞价跳空（GAP-1）。env 内无候选（锚与簿脱节）→ 本轮不竞价
        // 返回空，回落 P1 强制单路径（其成交同样经 walkBook 5% 夹断 → 保证首笔连续）。tie-break 锚：
        // 有锚 → 与连续性锚最近；无锚 → 与 referencePrice 最近（兼容既有 LIQ 语义）。
        // cm.clearing=false 时本段零执行（env 退化为整带、tie-break 回落 referencePrice）→ LIQ 逐位一致。
        double envLo = limitDown;
        double envHi = limitUp;
        boolean envelopeActive = false;
        double ref;
        if (clearingEnabled && lastFillPrice > 0) {
            envelopeActive = true;
            envLo = Math.max(limitDown, lastFillPrice * Math.exp(-CONTINUITY_MAX_LOG_MOVE));
            envHi = Math.min(limitUp, lastFillPrice * Math.exp(CONTINUITY_MAX_LOG_MOVE));
            ref = lastFillPrice;
        } else {
            ref = referencePrice > 0 ? referencePrice : getMidPrice();
        }

        double bestP = 0.0;
        long bestVol = -1;
        for (double p : candidates) {
            if (envelopeActive && (p < envLo || p > envHi)) continue;
            long buy = 0;
            long sell = 0;
            for (Map.Entry<Double, Queue<Order>> e : bidLevels) if (e.getKey() >= p) buy += sumQueue(e.getValue());
            for (Map.Entry<Double, Queue<Order>> e : askLevels) if (e.getKey() <= p) sell += sumQueue(e.getValue());
            long vol = Math.min(buy, sell);
            if (vol <= 0) continue;
            boolean better;
            if (vol > bestVol) {
                better = true;
            } else if (vol == bestVol) {
                double dP = Math.abs(p - ref);
                double dB = Math.abs(bestP - ref);
                better = dP < dB || (dP == dB && p < bestP);
            } else {
                better = false;
            }
            if (better) {
                bestVol = vol;
                bestP = p;
            }
        }
        if (bestVol <= 0 || bestP <= 0) return new CallAuctionResult(trades, 0.0, false, 0L, cageFiltered);
        double pStar = Math.round(bestP * 100.0) / 100.0;
        // CM A-2（GAP-3）：竞价 P* 出清前集中护栏（cm.clearing 门内）。valid 恒等；
        // 不可用价（NaN/Inf/≤0，理论不可达）→ 审计计数并跳过本次竞价（不产生伪价成交）。
        if (clearingEnabled) {
            double guarded = sanitizeFill(pStar);
            if (guarded < 0) return new CallAuctionResult(trades, 0.0, false, 0L, cageFiltered);
            pStar = guarded;
        }

        // ③ 出清：双侧各自从最优档起，以 P* 配对成交（量=min；档内时间优先），单笔 Trade 归属同 walkBook
        int bi = 0;
        int si = 0;
        while (bi < bidLevels.size() && bidLevels.get(bi).getKey() >= pStar
                && si < askLevels.size() && askLevels.get(si).getKey() <= pStar) {
            Queue<Order> bq = bidLevels.get(bi).getValue();
            Queue<Order> sq = askLevels.get(si).getValue();
            if (bq.isEmpty()) {
                bids.remove(bidLevels.get(bi).getKey());
                bi++;
                continue;
            }
            if (sq.isEmpty()) {
                asks.remove(askLevels.get(si).getKey());
                si++;
                continue;
            }
            Order b = bq.peek();
            Order s = sq.peek();
            int take = Math.min(b.getRemainingQuantity(), s.getRemainingQuantity());
            if (take <= 0) {
                if (b.getRemainingQuantity() <= 0) bq.poll();
                if (s.getRemainingQuantity() <= 0) sq.poll();
                continue;
            }
            // A 股 T+1 归属（AS-3）：合成流侧记 null（与 walkBook 同一约定）；竞价以单一价 P* 成交
            java.util.UUID buyerId = b.isSynthetic() ? null : b.getPlayerId();
            java.util.UUID sellerId = s.isSynthetic() ? null : s.getPlayerId();
            trades.add(new Trade(b.getAssetId(), pStar, take, System.currentTimeMillis(), true, buyerId, sellerId));
            b.fill(take);
            s.fill(take);
            // LIQ P2：恰一侧为专职 MM 报价 → MM 存货记账（集合竞价出清路径同样接线）
            notifyMmFillIfTagged(b, s, pStar, take);
            if (b.isFilled()) bq.poll();
            if (s.isFilled()) sq.poll();
        }
        // 收尾清理：任何被吃空的档位从簿移除（空档不残留，保持 best 侧判定干净）
        for (Map.Entry<Double, Queue<Order>> e : bidLevels) {
            Queue<Order> live = bids.get(e.getKey());
            if (live != null && live.isEmpty()) bids.remove(e.getKey());
        }
        for (Map.Entry<Double, Queue<Order>> e : askLevels) {
            Queue<Order> live = asks.get(e.getKey());
            if (live != null && live.isEmpty()) asks.remove(e.getKey());
        }
        // CM A-1（GAP-1，CAPM_SPEC §4.2 A-1.5）：出清成功后以 P* 触碰连续性锚（lastFillPrice=round2(P*)）
        // → 竞价后连续竞价首笔以「竞价出清价」为新锚夹断，无第二跳空（cm.clearing=false 零执行，LIQ 逐位一致）。
        if (clearingEnabled && !trades.isEmpty()) {
            touchContinuityAnchor(pStar);
        }
        // DG-3（DATA_GUARD_SPEC §4.4）：出清后同步笼子锚 = P*（touchContinuityAnchor 内已同步
        // lastTradePrice；此处覆盖「cm.clearing=false 但 DG-3 竞价执行」的组合，保证笼子锚不滞后）。
        if (!trades.isEmpty()) {
            setLastTradePrice(pStar);
        }
        // DG-3：回填本轮竞价结果（供 MarketEngine 判定窗口是否需滚动）；pStarVolume = 单一价出清量
        // = min(Q_buy(P*), Q_sell(P*))（探针独立复算，容差 0）；同时写入本簿 lastPStar/lastPStarVolume 可观测面。
        long pStarVolume = 0L;
        for (Trade t : trades) pStarVolume += t.getQuantity();
        this.lastPStar = pStar > 0 ? pStar : 0.0;
        this.lastPStarVolume = pStarVolume;
        if (!trades.isEmpty()) {
            System.out.println("⚖️ 集合竞价出清 " + trades.size() + " 笔 @P*=" + pStar);
        }
        return new CallAuctionResult(trades, pStar, !trades.isEmpty(), pStarVolume, cageFiltered);
    }

    /**
     * DG-3（DATA_GUARD_SPEC §4.4）：集合竞价结果（出清数学与既有 {@link #runCallAuction} 完全一致，
     * 仅额外暴露 {@code P*}、出清量、是否出清、是否因 DG-1 笼子过滤过候选价，供 MarketEngine 判定窗口滚动
     * 与探针断言）。
     */
    public static final class CallAuctionResult {
        /** 本轮成交列表（空 = 零出清）。 */
        public final List<Trade> trades;
        /** 出清价 P*（零出清时为 0）。 */
        public final double pStar;
        /** 是否成功出清（trades 非空）。 */
        public final boolean cleared;
        /** 出清量 = min(Q_buy(P*), Q_sell(P*))（零出清时为 0）。 */
        public final long pStarVolume;
        /** 本轮是否因 DG-1 笼子过滤掉过候选价（笼子层可判定）。 */
        public final boolean cageFiltered;

        public CallAuctionResult(List<Trade> trades, double pStar, boolean cleared,
                                 long pStarVolume, boolean cageFiltered) {
            this.trades = trades;
            this.pStar = pStar;
            this.cleared = cleared;
            this.pStarVolume = pStarVolume;
            this.cageFiltered = cageFiltered;
        }
    }

    public synchronized OrderBookSnapshot getSnapshot() {
        OrderBookSnapshot snap = new OrderBookSnapshot();
        int bidCount = 0;
        for (Map.Entry<Double, Queue<Order>> e : bids.entrySet()) {
            if (bidCount >= 5) break;
            int totalQty = e.getValue().stream().mapToInt(Order::getRemainingQuantity).sum();
            snap.bids.add(new OrderBookLevel(e.getKey(), totalQty));
            bidCount++;
        }
        int askCount = 0;
        for (Map.Entry<Double, Queue<Order>> e : asks.entrySet()) {
            if (askCount >= 5) break;
            int totalQty = e.getValue().stream().mapToInt(Order::getRemainingQuantity).sum();
            snap.asks.add(new OrderBookLevel(e.getKey(), totalQty));
            askCount++;
        }
        return snap;
    }

    // ================= P2-4 持久化（读档后深度/未成交单可恢复；旧档缺字段 contains 兜底） =================
    //
    // 【范围说明】订单簿状态持久化是本期【有意的增强】：P2_SPEC §7.2 将「订单队列跨存档持久化」列为
    // 可选增强、本期不做，但 t6 落地为增强以保留跨存档深度/未成交单，读档后订单簿状态连续可复现。
    // 属范围扩展，已在 .verify/P2_MICROSTRUCTURE_VERIFY.md 登记；load 端对旧存档缺字段用 contains 兜底，
    // 读档不失败（A2-7）。

    /**
     * P2-4：序列化订单簿状态（currentTick、rng 状态、bids/asks 各档订单）。
     * 供 MarketEngine.save() 随 engine_main blob（H2/NBT）整体序列化（A2-7）。
     */
    public synchronized CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("currentTick", currentTick);
        tag.putLong("rngState", rng.getState());
        tag.putDouble("rngSpare", rng.getSpareGaussian());
        tag.put("bids", saveSide(bids));
        tag.put("asks", saveSide(asks));
        return tag;
    }

    private ListTag saveSide(TreeMap<Double, Queue<Order>> book) {
        ListTag side = new ListTag();
        for (Map.Entry<Double, Queue<Order>> e : book.entrySet()) {
            for (Order o : e.getValue()) {
                CompoundTag ot = new CompoundTag();
                ot.putDouble("price", o.getLimitPrice());
                ot.putInt("remaining", o.getRemainingQuantity());
                ot.putInt("total", o.getTotalQuantity());
                ot.putLong("placedTick", o.getPlacedTick());
                ot.putInt("expireTicks", o.getExpireTicks());
                ot.putBoolean("isBuy", o.isBuy());
                ot.putString("assetId", o.getAssetId());
                // LIQ P2（§5.2/§7.2）：MM 会话标签随订单持久化（读档后撤旧重挂/成交归属仍可识别本 MM 报价）
                if (o.getMmTag() != null) ot.putString("mmTag", o.getMmTag());
                side.add(ot);
            }
        }
        return side;
    }

    /**
     * P2-4：读档恢复订单簿状态；旧存档缺字段时用默认/空重建（读档不失败）。
     */
    public synchronized void load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return;
        if (tag.contains("currentTick")) currentTick = tag.getLong("currentTick");
        if (tag.contains("rngState")) rng.setState(tag.getLong("rngState"));
        if (tag.contains("rngSpare")) rng.setSpareGaussian(tag.getDouble("rngSpare"));
        if (tag.contains("bids")) loadSide(bids, tag.getList("bids", Tag.TAG_COMPOUND));
        if (tag.contains("asks")) loadSide(asks, tag.getList("asks", Tag.TAG_COMPOUND));
    }

    private void loadSide(TreeMap<Double, Queue<Order>> book, ListTag side) {
        // 恢复用固定 UUID（订单簿不追踪真实玩家账本；P2 沿用读档重建语义）
        java.util.UUID restoreId = java.util.UUID.fromString("00000000-0000-0000-0000-00000000fffe");
        for (int i = 0; i < side.size(); i++) {
            CompoundTag ot = side.getCompound(i);
            double price = ot.getDouble("price");
            boolean isBuy = ot.getBoolean("isBuy");
            int total = ot.getInt("total");
            int remaining = ot.getInt("remaining");
            if (remaining <= 0) continue;
            String assetId = ot.getString("assetId");
            Order o = new Order(
                    restoreId,
                    assetId,
                    isBuy ? Order.Type.LIMIT_BUY : Order.Type.LIMIT_SELL,
                    total, price, ot.getLong("placedTick"));
            // 恢复剩余量：total - remaining
            o.fill(total - remaining);
            if (ot.contains("expireTicks")) o.setExpireTicks(ot.getInt("expireTicks"));
            // LIQ P2（§7.2 contains 兜底）：旧档缺 mmTag 字段 → null（非 MM 单），读档不失败
            if (ot.contains("mmTag")) o.setMmTag(ot.getString("mmTag"));
            // A 股 T+1（AS-3）：读档重建订单无真实玩家归属（统一 restoreId），标记合成流避免污染真实账户账本
            o.setSynthetic(true);
            book.computeIfAbsent(price, k -> new ConcurrentLinkedQueue<>()).add(o);
        }
    }

    public static class OrderBookLevel {
        public final double price;
        public final int quantity;
        public OrderBookLevel(double price, int quantity) { this.price = price; this.quantity = quantity; }
    }

    public static class OrderBookSnapshot {
        public final List<OrderBookLevel> bids = new ArrayList<>();
        public final List<OrderBookLevel> asks = new ArrayList<>();
    }
}