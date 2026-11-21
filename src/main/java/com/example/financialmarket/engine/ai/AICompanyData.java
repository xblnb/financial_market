package com.example.financialmarket.engine.ai;

import com.example.financialmarket.engine.MarketState;
import com.example.financialmarket.engine.random.SeedableRandom;

/**
 * AI 公司财务基本面（DATA_GUARD_SPEC §5，DG-4 杜邦分析法重写）。
 *
 * <p>生成链纪律：**随机只消费在 ①~⑤ 五个基础量**（营收增长、净利率、周转率目标、权益乘数、
 * 行业/宏观因子），其余全部由公式推导 —— 杜绝「独立随机生成互相矛盾的财务数字」。
 *
 * <p>三条恒等式（容差 1e-9 相对误差）：
 * <pre>
 *   ROE = 净利率 × 总资产周转率 × 权益乘数
 *   ROA = 净利率 × 总资产周转率
 *   总资产 = 股东权益 + 负债
 * </pre>
 *
 * <p>量纲统一（★DG-4 缺陷修复）：{@code roe/roa} 为**百分数**；{@code revenueGrowth/dividendYield/
 * debtRatio/netMargin} 为**小数比率**。其中 {@code debtRatio} 由百分数改为小数比率后，
 * 既有固定折现率公式 {@code 0.08 + debtRatio×0.1} 首次按其设计口径工作（折现率 0.10~0.14 而非 5.08），
 * DCF 项恢复正量级、与 CAPM {@code ke} 量纲自洽；CAPM/GBM 核心公式零改动。
 *
 * <p>确定性：SeedableRandom 子流（{@code mix64(seed)}），零裸随机；所有推导为纯函数、不消费 rng。
 */
public class AICompanyData {
    // ---------- 基础数据 ----------
    private final String stockId;
    private final String name;
    private final String industry;
    private final String usCode;
    private final String cnCode;

    // ---------- 财务基本面 ----------
    private double netAssetValue;          // 净资产 (万) —— ★DG-4：totalEquity 的镜像（同一数值，零漂移）
    private double recentProfit;           // 近期利润 (万)
    private double revenue;                // 营业收入 (万)
    private double revenueGrowth;          // ★DG-4 量纲统一：小数比率 ∈ [-0.1, 0.4]（非百分数）
    private double netProfit;              // 净利润 (万)
    private double eps;                    // 每股收益
    private double roe;                    // 净资产收益率 (%) —— 杜邦乘积，百分数
    private double dividendYield;          // 股息率 (小数比率)
    private double debtRatio;              // ★DG-4 量纲统一：资产负债率（小数比率 ∈ [0,1)）

    // ---------- DG-4：杜邦分析基础量与衍生量（DATA_GUARD_SPEC §5.1） ----------
    // 权威源 = totalEquity；liabilities/totalAssets/roa 全部由恒等式推导，绝不独立随机。
    private double totalAssets;            // 总资产 (万) = totalEquity + liabilities
    private double totalEquity;            // 股东权益 (万) —— ★权威源
    private double liabilities;            // 负债 (万) = totalAssets − totalEquity
    private double netMargin;              // 净利率（小数）
    private double assetTurnover;          // 总资产周转率（次）= revenue / totalAssets
    private double equityMultiplier;       // 权益乘数（≥1）= totalAssets / totalEquity
    private double roa;                    // 总资产收益率（%，与 roe 同量纲）

    // ---------- DG-4：杜邦链条护栏与默认值 ----------
    /** 非杜邦字段（revenue/netProfit 等）缺失时的兜底营收（万），保证除数恒 > 0。 */
    private static final double DUPONT_MIN_REVENUE = 1.0;
    /** 权益下限（万）：留存在亏损季度为负时把权益抬到该值，杜绝负权益/除零。 */
    private static final double MIN_EQUITY = 1000.0;
    /** 权益乘数上下界（DATA_GUARD_SPEC §5.3）：≥1.05 ⇒ 负债率 ∈ (4.8%, 65%)。 */
    private static final double MIN_EQUITY_MULTIPLIER = 1.05;
    private static final double MAX_EQUITY_MULTIPLIER = 3.0;
    /** 陈旧存档自相矛盾的 roe 只在首次读档提示一次，避免日志刷屏。 */
    private static boolean legacyRoeWarningLogged = false;

    // ---------- DG-4：`dataGuard.dupont` 开关（t7，DATA_GUARD_SPEC §0.9③ / §1） ----------
    /**
     * 杜邦勾稽链开关（唯一权威值 = {@code MarketEngine.isDupontEffective()}）。
     *
     * <p><b>为什么用静态</b>：DATA_GUARD_SPEC §1 把开关定义为**全局**特性开关（"四项零执行"的
     * kill switch），同一次运行内全部 50 家 AI 公司必须读同一个值；实例级注入需要在
     * `registerAICompany` 与季度 `updateAll` 两条路径各推一次，一旦漏推就会出现"部分公司开启、
     * 部分关闭"的混合态（勾稽破裂无法归因）。静态入口只需 `MarketEngine` 在一处推送。
     *
     * <p><b>线程可见性</b>：声明为 {@code volatile} —— 写入方是 `MarketEngine`（tick/命令线程），
     * 读取方是任意构造/季度更新调用点；volatile 保证写后立即可见（读档/异步命令线程下也不会读到
     * 过期值），且不引入锁。读取只在**每次生成之前**发生（构造期与 `updateFundamentals` 各读一次），
     * 因此开关翻转对**后续**生成生效，历史公司不回溯重算。
     */
    private static volatile boolean dupontEnabled = true;

    /** 关闭勾稽时（off 态）独立随机子流的派生前缀（确定性子流隔离，禁裸随机）。 */
    private static final String OFF_STATE_STREAM = "dupont_off";

    /**
     * 设置杜邦勾稽开关（t9 由 {@code MarketEngine} 推送 {@code isDupontEffective()}）。
     * {@code true}（默认）= §5.2 生成链 ①~⑫ 勾稽自洽；{@code false} = 关闭勾稽，派生量改由
     * 确定性 rng 独立随机生成（勾稽破裂对照态）。开关在任何一次生成之前读取，仅对后续生成生效。
     */
    public static void setDupontEnabled(boolean enabled) {
        dupontEnabled = enabled;
    }

    /** 只读开关态（供探针断言与运行期日志；默认 {@code true}）。 */
    public static boolean isDupontEnabled() {
        return dupontEnabled;
    }

    /** off 态独立随机子流种子（与勾稽链同源派生，隔离且同 seed 可复现）。 */
    private static long offStreamSeed(long seed) {
        return SeedableRandom.mix64(SeedableRandom.mix64(seed) ^ OFF_STATE_STREAM.hashCode());
    }

    // ---------- 估值与股本 ----------
    private int totalShares;           // 总股本 (万股)
    private double ipoPrice;               // IPO价格
    private double bookValuePerShare;      // 每股净资产
    private double fairValue;              // 内在价值（估值模型计算）
    /** World-macro demand/cost/risk multiplier applied on read; 1.0 = neutral. */
    private double worldMacroFactor = 1.0;

    // ---------- 趋势与波动 ----------
    private double baseAsset;
    private final double trend;
    private final double volatility;

    // ---------- 新增：公司生命周期与评级 ----------
    private double growthStage;            // 0~1 成长阶段 (0=初创, 1=成熟)
    private double profitabilityScore;     // 0~1 盈利能力评分
    private double financialHealthScore;   // 0~1 财务健康评分

    // P0-7/A0-3：可播种 PRNG 子流（构造与季度演化均同种子可复现）
    private final SeedableRandom rng;
    /**
     * t7：off 态（`dataGuard.dupont=false`）专用的可播种独立随机子流 —— 与 {@link #rng} 同源派生
     * 但流隔离，保证"关闭勾稽"时的独立随机形态同样确定可复现（禁裸随机）。
     */
    private final SeedableRandom rngOff;
    /** fsynth-inspired quarterly fundamental generator (Heston variance + jumps). */
    private final FsynthFundamentals fsynth;

    public AICompanyData(String stockId, String name, String industry, double initialAsset, double trend, double vol,
                         String usCode, String cnCode) {
        this(stockId, name, industry, initialAsset, trend, vol, usCode, cnCode, 0L);
    }

    /** P0-7：以种子构造，初始随机与 updateFundamentals 的裸随机调用全部由可播种 PRNG 代替（确定性纪律）。 */
    public AICompanyData(String stockId, String name, String industry, double initialAsset, double trend, double vol,
                         String usCode, String cnCode, long seed) {
        this.stockId = stockId;
        this.name = name;
        this.industry = industry;
        this.baseAsset = initialAsset;
        this.trend = trend;
        this.volatility = vol;
        this.usCode = usCode;
        this.cnCode = cnCode;
        this.rng = new SeedableRandom(SeedableRandom.mix64(seed));
        this.rngOff = new SeedableRandom(offStreamSeed(seed));
        this.fsynth = new FsynthFundamentals(seed ^ 0x5DEECE66DL);

        // 初始化财务数据
        this.recentProfit = initialAsset * 0.08;
        this.revenue = initialAsset * 0.5;
        this.totalShares = 10000 + rng.nextInt(5000);
        this.ipoPrice = initialAsset / totalShares;

        // ---------- DG-4 杜邦生成链 ①~⑤：仅此五处消费随机 ----------
        this.growthStage = 0.3 + rng.nextDouble() * 0.5;
        this.profitabilityScore = 0.4 + rng.nextDouble() * 0.5;
        this.financialHealthScore = 0.5 + rng.nextDouble() * 0.4;
        // ①a 营收增长（基础量；★量纲统一：小数比率 5%~15%，杜绝基线的 0.05+rng×0.25 被
        //    revenue×(1+g) 当成 "×1.05~×1.30" 却按百分数展示的量纲混用）
        this.revenueGrowth = clampRatio(0.05 + rng.nextDouble() * 0.10);
        // ④ 净利率（基础量，小数）
        this.netMargin = Math.max(0.02, Math.min(0.35, 0.05 + rng.nextDouble() * 0.15));
        // ⑤ 周转率（目标值）/ 权益乘数（基础量）
        this.assetTurnover = Math.max(0.15, Math.min(1.5, 0.25 + rng.nextDouble() * 0.85));
        this.equityMultiplier = clampEquityMultiplier(
                1.2 + rng.nextDouble() * 0.8 + 0.3 * (1 - growthStage));
        // ⑪a 股息率（与净利率同源，非独立随机）
        this.dividendYield = 0.01 + this.netMargin * 0.2;

        // ---------- 权益（推导，平衡表） ----------
        this.totalEquity = Math.max(MIN_EQUITY, initialAsset * 0.6 + 10000);
        // ⑥ 净利润（推导）：净利率 × 营收（构造期 profitabilityScore 影响不叠加，保持"初始季"口径）
        this.netProfit = Math.max(0.0, this.revenue * this.netMargin);
        // ★t7 开关：在**生成之前**读一次 `dataGuard.dupont`（开关翻转只影响后续生成，不回溯历史公司）
        if (dupontEnabled) {
            // ⑦~⑫ 资产/负债/衍生指标（全部由 totalEquity + equityMultiplier 唯一推导，勾稽自洽）
            deriveDupont();
        } else {
            // off 态：关闭勾稽 —— 派生量改由确定性 rng 独立随机生成（恒等式大概率破裂，对照态）
            generateIndependentFinancials();
        }

        // 计算内在价值
        updateFairValue();
        // P0-3 量纲修复：IPO 初始价以 fairValue 为准，消除「上市冲高到静态 fairValue」的量纲错配
        this.ipoPrice = this.fairValue;
    }

    // ---------- 更新基本面（每季度/每年调用） ----------
    /**
     * 季度演化（DATA_GUARD_SPEC §5.2 生成链 ①~⑫）：**随机只消费在 ①~⑤**（行业因子、营收增长、
     * 净利率、周转率目标、权益乘数），其余全部由恒等式推导 —— 杜绝"独立随机生成互相矛盾的财务数字"。
     */
    public void updateFundamentals(MarketState state, double riskAppetite, double inflation) {
        // fsynth-inspired shocks: Heston variance + jump, then macro/industry/inflation overlay.
        FsynthFundamentals.Shock shock = fsynth.next();
        double macroFactor = 1.0 + 0.1 * riskAppetite;
        double industryFactor = 1.0 + 0.5 * (shock.revenueGrowth() - 0.06);
        double inflationFactor = 1.0 - inflation * 0.5;

        // ② 营收增长（fsynth growth，★量纲统一为小数比率 ∈ [-0.1, 0.4]）
        setRevenueGrowth((revenueGrowth * 0.9
                + Math.max(0.0, shock.revenueGrowth()) * macroFactor * industryFactor * inflationFactor));

        // ③ 营业收入（推导）
        revenue = Math.max(DUPONT_MIN_REVENUE, revenue * (1 + revenueGrowth));

        // ④ 净利率（fsynth shock）
        double targetNetMargin = shock.netMargin();

        // ⑤ 周转率目标 / 权益乘数（fsynth shock）
        double targetTurnover = Math.max(0.15, shock.turnover());
        double targetEquityMultiplier = clampEquityMultiplier(
                shock.equityMultiplier() + 0.3 * (1 - growthStage));

        // ⑥ 净利润（推导；净利率受盈利能力评分调制）
        double effectiveMargin = targetNetMargin * (0.8 + 0.2 * profitabilityScore);
        netProfit = Math.max(0.0, revenue * effectiveMargin);

        // ⑦ 留存收益（推导）
        double retainedEarnings = netProfit * (0.3 + 0.4 * growthStage);

        // ⑧ 权益（推导，平衡表）：留存在亏损季度回退时抬到 MIN_EQUITY 护栏
        totalEquity = Math.max(MIN_EQUITY, totalEquity + retainedEarnings);

        // ⑨ 权益乘数（基础量）/ 总资产 / 负债（推导）
        equityMultiplier = targetEquityMultiplier;

        // ⑩~⑪ 回算周转率 + 全部衍生指标（roe/roa/eps/bookValuePerShare/debtRatio/dividendYield）
        //       targetTurnover 作为"目标规模感"在此仅参与资产上限对齐（不破坏恒等式）。
        // ★t7 开关：在**生成之前**读一次 `dataGuard.dupont`（与构造期读同一个值，两条生成路径同受控）
        if (dupontEnabled) {
            alignAssetsToTargetScale(targetTurnover);
            deriveDupont();
        } else {
            // off 态：关闭勾稽 —— 派生量改由确定性 rng 独立随机生成（恒等式大概率破裂，对照态）
            generateIndependentFinancials();
        }

        // ⑫ 估值（既有 DCF+PE+PB；pbRatio=1+roe/10 自动吃到杜邦 ROE）
        updateFairValue();
    }

    /**
     * 把总资产对齐到"目标周转率"的规模感（DATA_GUARD_SPEC §5.2 ⑤⑨⑩）：
     * 以 {@code revenue / targetTurnover} 给出目标总资产，再按权益乘数守恒反推权益上限——
     * 恒等式 {@code totalAssets = totalEquity + liabilities} 与 ROE 乘积式**依旧逐位成立**，
     * 仅把 ⑤ 的目标值转成负债规模的确定性调制（零随机）。
     */
    private void alignAssetsToTargetScale(double targetTurnover) {
        if (targetTurnover <= 0 || revenue <= 0) return;
        double targetAssets = revenue / targetTurnover;
        // 目标资产不得小于当前权益（否则负债为负）——护栏：只在"目标 > 权益"时上抬乘数，绝不压破恒等式
        double neededMultiplier = targetAssets / Math.max(MIN_EQUITY, totalEquity);
        if (neededMultiplier > equityMultiplier) {
            equityMultiplier = clampEquityMultiplier(neededMultiplier);
        }
    }

    // ---------- 持久化恢复（G-04）：读档时恢复基本面，而非重新随机 ----------
    // 缺失字段用默认值 + 重新初始化兜底（前向兼容），由调用方（MarketEngine.loadInternal）判断。
    /**
     * 旧档恢复（DATA_GUARD_SPEC §5.4，**14 参签名逐字保留**）：恢复既有字段值后，
     * 以「回推兜底」补齐 7 个杜邦字段（{@code totalEquity=netAssetValue}、
     * {@code equityMultiplier=1/(1−debtRatio)}、{@code totalAssets=totalEquity×乘数}、
     * {@code liabilities=totalAssets−totalEquity}、{@code netMargin=netProfit/revenue}、
     * {@code assetTurnover=revenue/totalAssets}、{@code roa=netMargin×周转率×100}），
     * 使**旧档读档后也立即满足 §5.1 全部恒等式**（roe 以恒等式回算，档内陈旧 roe 的差异如实提示一次）。
     */
    public void restoreFundamentals(double netAssetValue, double revenue, double revenueGrowth,
                                    double netProfit, double eps, double roe, double dividendYield,
                                    double debtRatio, double ipoPrice, double fairValue,
                                    double growthStage, double profitabilityScore,
                                    double financialHealthScore, int totalShares) {
        this.revenue = Math.max(DUPONT_MIN_REVENUE, revenue);
        setRevenueGrowth(revenueGrowth);
        this.netProfit = Math.max(0.0, netProfit);
        this.recentProfit = this.netProfit;
        this.eps = eps;
        this.roe = roe;
        this.dividendYield = dividendYield;
        this.debtRatio = normalizeDebtRatio(debtRatio);
        this.ipoPrice = ipoPrice;
        this.fairValue = fairValue;
        this.growthStage = growthStage;
        this.profitabilityScore = profitabilityScore;
        this.financialHealthScore = financialHealthScore;
        if (totalShares > 0) {
            this.totalShares = totalShares;
        }
        if (this.totalShares <= 0) {
            this.totalShares = 1; // 护栏：每股指标不出现除零
        }
        // 权威源 = 权益；镜像 netAssetValue 与之一致（getNetAssetValue()==getTotalEquity()）
        this.totalEquity = Math.max(MIN_EQUITY, netAssetValue);
        // 由负债率反推权益乘数（debtRatio 已归一化为小数比率）→ 总资产/负债 → 周转率/净利率
        this.equityMultiplier = clampEquityMultiplier(1.0 / (1.0 - this.debtRatio));
        double legacyRoe = this.roe;
        deriveDupont();
        if (!legacyRoeWarningLogged && Math.abs(legacyRoe - this.roe) > 1e-6) {
            legacyRoeWarningLogged = true;
            System.out.println("⚠️ [DG-4] 旧档 roe=" + legacyRoe + " 与杜邦恒等式回算值 roe=" + this.roe
                    + " 不一致（档内为独立随机的陈旧值）——已按恒等式回算，" + stockId + " 等公司不再复现。");
        }
        // 用恢复后的数据重算 fairValue，确保内在价值随基本面一致（而非冻结）
        this.updateFairValue();
    }

    /**
     * 新档恢复（DATA_GUARD_SPEC §5.4，19 参 = 7 个杜邦字段 + 12 个既有字段的完整快照）：
     * 7 个杜邦字段**直接恢复**（不重新随机、不重新推导基础量），衍生指标按恒等式回算后重算 fairValue，
     * 保证 save→load 往返与未读档状态逐位一致；缺失字段由调用方 {@code contains} 兜底。
     */
    public void restoreDupont(double totalAssets, double totalEquity, double liabilities,
                              double netMargin, double assetTurnover, double equityMultiplier,
                              double roa, double revenue, double revenueGrowth,
                              double netProfit, double eps, double roe,
                              double dividendYield, double debtRatio,
                              double ipoPrice, double fairValue,
                              double growthStage, double profitabilityScore,
                              double financialHealthScore, int totalShares) {
        this.totalAssets = totalAssets;
        this.totalEquity = totalEquity;
        this.liabilities = liabilities;
        this.netMargin = netMargin;
        this.assetTurnover = assetTurnover;
        this.equityMultiplier = equityMultiplier;
        this.roa = roa;
        this.revenue = Math.max(DUPONT_MIN_REVENUE, revenue);
        setRevenueGrowth(revenueGrowth);
        this.netProfit = Math.max(0.0, netProfit);
        this.recentProfit = this.netProfit;
        this.eps = eps;
        this.roe = roe;
        this.dividendYield = dividendYield;
        this.debtRatio = normalizeDebtRatio(debtRatio);
        this.ipoPrice = ipoPrice;
        this.fairValue = fairValue;
        this.growthStage = growthStage;
        this.profitabilityScore = profitabilityScore;
        this.financialHealthScore = financialHealthScore;
        if (totalShares > 0) {
            this.totalShares = totalShares;
        }
        if (this.totalShares <= 0) {
            this.totalShares = 1; // 护栏：每股指标不出现除零
        }
        // 快照自洽性护栏：缺失/损坏的杜邦三元组（totalEquity≤0 或 totalAssets≤0）回落到「重算」路径
        if (!(this.totalEquity > 0) || !(this.totalAssets > 0) || !(this.equityMultiplier >= 1.0)) {
            this.totalEquity = Math.max(MIN_EQUITY, this.eps * this.totalShares);
            this.equityMultiplier = clampEquityMultiplier(
                    1.0 / (1.0 - this.debtRatio));
        }
        // 镜像 + 衍生指标按恒等式回算（不以快照中的陈旧衍生值为准，杜绝"看似矛盾"）
        this.netAssetValue = this.totalEquity;
        deriveDupont();
        // 用恢复后的数据重算 fairValue，确保内在价值随基本面一致（而非冻结）
        this.updateFairValue();
    }

    // ---------- DG-4 off 态（t7）：关闭勾稽的确定性独立随机形态 ----------
    /**
     * off 态生成分支（`dataGuard.dupont=false`，DATA_GUARD_SPEC §0.9③ 权威语义）：
     * **关闭勾稽**，把杜邦派生量（`totalAssets/liabilities/assetTurnover/roa/roe/debtRatio/eps/
     * bookValuePerShare/dividendYield`）改为**确定性 {@link #rngOff} 驱动的独立随机**取值。
     *
     * <p>三条恒等式因此**大概率不成立** —— 这正是 DG-4b 同 run on/off 对照要观测的"财务勾稽破裂"
     * 对照态；本分支**不是** pre-t3 基线（旧链未入库、含裸随机、不可复原，见 §0.9③）。
     *
     * <p>确定性纪律：只用 {@link #rngOff}（与 {@link #rng} 同源派生但流隔离），零裸随机；
     * 消费次数固定为 10 次，同 seed 双跑逐位一致。
     *
     * <p>护栏：仍保证 `totalEquity>0 / totalAssets>0 / totalShares>0 / revenue>0`（不产生 NaN 或
     * 负除零），但**不**保证任何恒等式 —— 勾稽破裂只体现在数值一致性上，不体现为崩溃。
     */
    private void generateIndependentFinancials() {
        if (this.totalShares <= 0) this.totalShares = 1;
        if (!(this.totalEquity > 0)) this.totalEquity = MIN_EQUITY;
        // 长程 kill switch 护栏：off 态权益会随季度累加（on 态被勾稽与估值约束），
        // 与"营收"同量级封顶，避免数千 tick 的长程运行把资产表放大到病态量级。
        double equityCap = Math.max(MIN_EQUITY, this.revenue * 3.0);
        if (this.totalEquity > equityCap) this.totalEquity = equityCap;
        if (this.revenue <= 0) this.revenue = DUPONT_MIN_REVENUE;
        if (this.netProfit < 0) this.netProfit = 0.0;
        // ① 资产负债率（独立随机，小数比率）+ 权益乘数（独立随机，与负债率无勾稽）
        this.debtRatio = 0.15 + rngOff.nextDouble() * 0.45;                     // [0.15, 0.60)
        this.equityMultiplier = clampEquityMultiplier(
                1.2 + rngOff.nextDouble() * 0.8 + 0.3 * (1 - this.growthStage)); // 独立于 debtRatio
        // ② 总资产（独立随机倍数 → 但钳在权益乘数域内，避免长程 off 态的指数级漂移）
        //    注：off 态**不**勾稽，用 {{@code equityMultiplier}} 定标只是"同量级"护栏，
        //    因乘数域 [1.05, 3.0] 与权益同量级 ⇒ 长程 kill switch 也不会把总资产放大到病态量级。
        this.totalAssets = this.totalEquity * this.equityMultiplier;
        // ③ 负债（独立随机小数比率，**不**等于 总资产−权益）
        this.liabilities = this.totalAssets * (0.10 + rngOff.nextDouble() * 0.50);
        // ④ 周转率（独立随机，**不**等于 营收/总资产）
        this.assetTurnover = 0.20 + rngOff.nextDouble() * 0.90;                  // [0.20, 1.10)
        // ⑤ 净利率（独立随机，**不**等于 净利润/营收）—— 本条最接近基线缺陷本体（净利率独立随机）
        this.netMargin = 0.04 + rngOff.nextDouble() * 0.14;                      // [0.04, 0.18)
        // ⑥ 派生比率各自独立随机（与上面的基础量无任何勾稽）
        this.roe = 2.0 + rngOff.nextDouble() * 16.0;                             // [2%, 18%)
        this.roa = 1.0 + rngOff.nextDouble() * 9.0;                              // [1%, 10%)
        this.dividendYield = 0.005 + rngOff.nextDouble() * 0.02;                 // [0.5%, 2.5%)
        // ⑦ 每股指标（独立随机）
        this.eps = this.netProfit / this.totalShares * (0.5 + rngOff.nextDouble() * 1.0);
        this.bookValuePerShare = this.totalEquity / this.totalShares * (0.6 + rngOff.nextDouble() * 0.8);
        // ⑧ 镜像仍维持（getNetAssetValue 的既有消费点语义不因开关而漂移）
        this.netAssetValue = this.totalEquity;
        this.recentProfit = this.netProfit;
    }

    // ---------- DG-4：杜邦推导核心（唯一写入口，全部字段由权益+乘数+净利率唯一决定） ----------
    /**
     * 由「权益（权威源）+ 权益乘数 + 营收/净利润」按恒等式推导总资产/负债/周转率/ROE/ROA/EPS/BPS/负债率/股息率。
     * 逐条满足 DATA_GUARD_SPEC §5.1（容差 1e-9 相对误差）：
     * <pre>
     *   totalAssets      = totalEquity × equityMultiplier
     *   liabilities      = totalAssets − totalEquity
     *   assetTurnover    = revenue / totalAssets
     *   netMargin        = netProfit / revenue
     *   roa              = netMargin × assetTurnover × 100
     *   roe              = netMargin × assetTurnover × equityMultiplier × 100
     *   debtRatio        = liabilities / totalAssets（小数比率）
     *   eps              = netProfit / totalShares
     *   bookValuePerShare= totalEquity / totalShares
     *   dividendYield    = 0.01 + netMargin × 0.2
     *   netAssetValue    = totalEquity（镜像，零漂移）
     * </pre>
     * 纯函数、**零随机**（不消费 rng，确定性纪律）；除零/负权益由 MIN_EQUITY 护栏兜底。
     */
    private void deriveDupont() {
        if (this.totalShares <= 0) this.totalShares = 1;
        if (!(this.totalEquity > 0)) this.totalEquity = MIN_EQUITY;
        this.equityMultiplier = clampEquityMultiplier(this.equityMultiplier);
        this.totalAssets = this.totalEquity * this.equityMultiplier;
        if (!(this.totalAssets > 0)) {
            this.totalAssets = this.totalEquity; // 护栏：总资产不得 ≤ 0
        }
        this.liabilities = this.totalAssets - this.totalEquity;
        if (this.revenue <= 0) this.revenue = DUPONT_MIN_REVENUE;
        if (this.netProfit < 0) this.netProfit = 0.0;
        this.assetTurnover = this.revenue / this.totalAssets;
        this.netMargin = this.netProfit / this.revenue;
        this.roa = this.netMargin * this.assetTurnover * 100.0;
        this.roe = this.netMargin * this.assetTurnover * this.equityMultiplier * 100.0;
        this.debtRatio = this.liabilities / this.totalAssets;
        this.eps = this.netProfit / this.totalShares;
        this.bookValuePerShare = this.totalEquity / this.totalShares;
        this.dividendYield = 0.01 + this.netMargin * 0.2;
        // §5.1 netAssetValue 保留为 totalEquity 的镜像（Excellent24Index / AssetSnapshot / LocalHttpServer 零加载）
        this.netAssetValue = this.totalEquity;
        this.recentProfit = this.netProfit;
    }

    /** 权益乘数归一化到 [1.05, 3.0]（§5.3：≥1.05 ⇒ 负债率 ∈ (4.8%, 65%)；乘数<1 会出负负债）。 */
    private static double clampEquityMultiplier(double multiplier) {
        if (!(multiplier >= MIN_EQUITY_MULTIPLIER)) return MIN_EQUITY_MULTIPLIER;
        return Math.min(MAX_EQUITY_MULTIPLIER, multiplier);
    }

    /** 营收增长归一化：★统一为小数比率 ∈ [-0.1, 0.4]（旧档写入的百分数 |g|>1 一次性折算 /100）。 */
    private static double clampRatio(double growth) {
        double g = growth;
        if (Math.abs(g) > 1.0) g = g / 100.0; // 旧档量纲（百分数）→ 小数比率
        return Math.max(-0.1, Math.min(0.4, g));
    }

    private void setRevenueGrowth(double growth) {
        this.revenueGrowth = clampRatio(growth);
    }

    /** 负债率归一化：★统一为小数比率 ∈ [0, 0.99]（旧档写入的百分数 >1 一次性折算 /100）。 */
    private static double normalizeDebtRatio(double debtRatio) {
        double d = debtRatio;
        if (!(d >= 0)) return 0.0;
        if (d > 1.0) d = d / 100.0;            // 旧档量纲（百分数）→ 小数比率
        return Math.min(d, 0.99);              // 乘数上界 3.0 ⇒ 负债率 ≤ 2/3，0.99 仅作除零护栏
    }

    // ---------- 估值模型（DCF + 相对估值） ----------
    // CAPM_SPEC §5.2（CM-5）：DCF 安全分母最小间距——实际折现率 = max(ke, growthRate + 0.02)，
    // 保证 discountRate − growthRate ≥ 0.02 > 0（高 β/高 ke 下 DCF 分母不接近零，估值不发散）。
    public static final double DCF_MIN_SPREAD = 0.02;

    /** 默认估值路径（cm.capm=false / 构造 / updateFundamentals / restoreDupont）：固定折现率
     *  {@code 0.08 + debtRatio×0.1}（CAPM_SPEC §5.2/CM-5a 的**公式本体零改动**）。
     *
     *  <p>★DG-4 量纲修复：该公式按「小数比率」口径设计（`debtRatio∈[0.2,0.6]` ⇒ 折现率 0.10~0.14），
     *  而基线把 `debtRatio` 当百分数（50）传入 → 折现率 5.08（≈508%）把 DCF 项压到 ≈0，
     *  fairValue 实际只由 PE/PB 主导。DG-4 统一 `debtRatio` 为小数比率后，
     *  DCF 恢复正量级并与 CAPM `ke`（同为小数）量纲自洽；**CAPM/GBM 核心公式不改**。 */
    private void updateFairValue() {
        computeFairValue(0.08 + debtRatio * 0.1);
    }

    /**
     * CAPM 折现接入（CAPM_SPEC §5.2/CM-5）：以股权成本 {@code equityCost}（= ke = R_f + β·ERP）替代固定
     * 折现率。equityCost &gt; 0 → DCF 折现率 = max(equityCost, growthRate + DCF_MIN_SPREAD)（安全分母护栏）；
     * equityCost ≤ 0（capm 关闭回落）→ 既有固定公式 0.08 + debtRatio×0.1，逐位一致。
     * 确定性纯函数、零 rng：不扰动 updateFundamentals 的随机消费序列（CAPM_SPEC §6）。
     */
    public void updateFairValue(double equityCost) {
        if (equityCost > 0 && Double.isFinite(equityCost)) {
            double growthRate = 0.02 + growthStage * 0.06;
            double safeRate = Math.max(equityCost, growthRate + DCF_MIN_SPREAD);
            computeFairValue(safeRate);
        } else {
            updateFairValue();
        }
    }

    /** 估值核心（DCF + PE + PB 加权）。discountRate 由调用方给定（固定公式或 CAPM ke）。 */
    private void computeFairValue(double discountRate) {
        // 1. DCF 估值（简化）
        double growthRate = 0.02 + growthStage * 0.06; // 2%~8%
        double terminalGrowth = 0.02;

        // 自由现金流 = 净利润 * 0.7（netProfit 为公司总净利润，单位万元）
        double fcf = netProfit * 0.7;
        // DCF 算出的是「公司总价值」（万元现金流折现），必须除以 totalShares 转成每股，才能与 peValue/pbValue 同量纲加权。
        // 此前未除 totalShares 导致 dcfValue 以「公司总值」混入「每股」加权，把 fairValue→股价撑到上万（离谱）。
        double totalSharesGuard = totalShares > 0 ? totalShares : 1;
        double dcfValue = JQuantLibBridge.dcfPerShare(fcf, growthRate, discountRate,
                terminalGrowth, totalSharesGuard, 5);

        // 2. 相对估值（PE * EPS）
        double peRatio = 10 + (profitabilityScore * 15) + (growthStage * 5); // 10~30倍
        double peValue = eps * peRatio;

        // 3. 市净率估值
        double pbRatio = 1.0 + roe / 10; // ROE 越高 PB 越高
        double pbValue = bookValuePerShare * pbRatio;

        // 4. 加权平均
        double dcfWeight = 0.4 + 0.2 * (1 - growthStage);
        double peWeight = 0.3 * growthStage + 0.1;
        double pbWeight = 0.2 * (1 - growthStage) + 0.1;

        this.fairValue = dcfValue * dcfWeight + peValue * peWeight + pbValue * (1 - dcfWeight - peWeight);
        // t7 护栏：下界 0.1 元（既有）；上界仅对**非有限值/病态输入**生效（NaN、∞、负值），
        // 因此 on 态（勾稽自洽、fairValue 实测 1.62~32.5 元）逐位不变；off 态（独立随机、无勾稽）
        // 也不会把 IPO 价撑到病态量级而不自知。
        double v = this.fairValue;
        if (!Double.isFinite(v) || v <= 0) {
            v = 0.1;
        }
        this.fairValue = Math.max(0.1, Math.min(10000.0, v));
    }

    // ---------- 计算基本面评分（0~100） ----------
    public double getFundamentalScore() {
        double score = 0;
        // ROE 评分
        score += Math.min(roe / 20, 1.0) * 25;
        // 收入增长率评分
        score += Math.min(revenueGrowth / 0.3, 1.0) * 20;
        // 财务健康评分
        score += financialHealthScore * 20;
        // 盈利能力评分
        score += profitabilityScore * 20;
        // 股息率评分
        score += Math.min(dividendYield / 0.05, 1.0) * 15;
        return Math.min(100, score);
    }

    // ---------- 计算估值偏离度 ----------
    public double getValuationDeviation(double currentPrice) {
        double fv = getFairValue();
        if (currentPrice <= 0 || fv <= 0) return 0;
        return (currentPrice - fv) / fv;
    }

    /**
     * ★DG-4 可观测面（DATA_GUARD_SPEC §6.1）：三条杜邦恒等式是否**同时**成立。
     *
     * <p>量纲：{@code roe}/{@code roa} 为**百分数**，{@code netMargin}/{@code debtRatio}/{@code revenueGrowth}
     * 为**小数比率**。容差按 §5.5：ROE/ROA 乘积式 ≤1e-9（相对），资产 = 权益 + 负债 ≤1e-6（绝对）。
     * 纯读、无副作用、零随机；探针可"读原始 → 公式复算 → 对照派生"。
     */
    public boolean isDupontConsistent() {
        if (!Double.isFinite(totalAssets) || !Double.isFinite(totalEquity) || !Double.isFinite(liabilities)
                || !Double.isFinite(netMargin) || !Double.isFinite(assetTurnover) || !Double.isFinite(equityMultiplier)
                || !Double.isFinite(roe) || !Double.isFinite(roa)) {
            return false;
        }
        double roeExpected = netMargin * assetTurnover * equityMultiplier * 100.0;
        double roaExpected = netMargin * assetTurnover * 100.0;
        boolean roeOk = relErr(roe, roeExpected) <= 1e-9;
        boolean roaOk = relErr(roa, roaExpected) <= 1e-9;
        boolean balanceOk = Math.abs(totalAssets - totalEquity - liabilities) <= 1e-6;
        boolean mirrorOk = netAssetValue == totalEquity;
        return roeOk && roaOk && balanceOk && mirrorOk;
    }

    /** 相对误差（分母取 max(1e-12, |expected|)）；expected=0 时退化为绝对误差。 */
    private static double relErr(double actual, double expected) {
        return Math.abs(actual - expected) / Math.max(1e-12, Math.abs(expected));
    }

    // ---------- Getters ----------
    public String getStockId() { return stockId; }
    public String getName() { return name; }
    public String getIndustry() { return industry; }
    public String getUsCode() { return usCode; }
    public String getCnCode() { return cnCode; }
    public double getNetAssetValue() { return netAssetValue; }
    public double getRecentProfit() { return recentProfit; }
    /** ★DG-4 可观测面：净利润（万）——与 {@code 营收×净利率} / {@code eps×总股本} 同源（恒等式对齐）。 */
    public double getNetProfit() { return netProfit; }
    public int getTotalShares() { return totalShares; }
    public double getIpoPrice() { return ipoPrice; }
    public double getRevenue() { return revenue; }
    public double getRevenueGrowth() { return revenueGrowth; }
    public double getEps() { return eps; }
    public double getRoe() { return roe; }
    public double getDividendYield() { return dividendYield; }
    public double getDebtRatio() { return debtRatio; }
    public double getBookValuePerShare() { return bookValuePerShare; }

    /**
     * World-macro transmission: sector demand/margin/risk/R&D -> a bounded fair-value factor.
     * Kept separate from the DCF/CAPM formula so policy and player-economy shocks compose
     * multiplicatively without overwriting each other.
     */
    public void applyWorldMacro(double outputIndex, double margin, double risk,
                                double rnd, double techTier) {
        this.worldMacroFactor = com.example.financialmarket.engine.macro.MacroStockTransmission
                .fairValueFactor(outputIndex, margin, risk, rnd, techTier);
    }

    public double getWorldMacroFactor() {
        return worldMacroFactor;
    }

    public double getFairValue() {
        return Math.max(0.1, Math.min(10000.0, fairValue * worldMacroFactor));
    }
    public double getGrowthStage() { return growthStage; }
    public double getProfitabilityScore() { return profitabilityScore; }
    public double getFinancialHealthScore() { return financialHealthScore; }

    // ---------- DG-4 杜邦字段 getter（供持久化 / 探针 / 评审取数） ----------
    public double getTotalAssets() { return totalAssets; }
    public double getTotalEquity() { return totalEquity; }
    public double getLiabilities() { return liabilities; }
    public double getNetMargin() { return netMargin; }
    public double getAssetTurnover() { return assetTurnover; }
    public double getEquityMultiplier() { return equityMultiplier; }
    public double getRoa() { return roa; }
}