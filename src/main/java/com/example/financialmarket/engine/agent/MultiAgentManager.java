package com.example.financialmarket.engine.agent;

import com.example.financialmarket.engine.OrderBook;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import com.example.financialmarket.engine.MarketSentiment;
import com.example.financialmarket.engine.ai.AICompanyData;
import com.example.financialmarket.engine.behavior.BehaviorParams;
import com.example.financialmarket.engine.behavior.BehaviorUtility;
import com.example.financialmarket.engine.behavior.HerdingModel;
import com.example.financialmarket.engine.macro.MacroEnvironment;
import com.example.financialmarket.engine.macro.MacroStockTransmission;
import com.example.financialmarket.engine.random.SeedableRandom;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MultiAgentManager {
    public enum AgentType { RETAIL, MOMENTUM, MARKET_MAKER, VALUE }

    /** P2-1 异质交易者分类（渐进映射自现有 AgentType，不推倒重来）。 */
    public enum TraderClass {
        FUNDAMENTALIST,   // 基本面：VALUE → 依 fairValue 偏离下单（低频）
        CHARTIST,         // 趋势/图表：MOMENTUM → 依订单簿动量/净失衡下单
        NOISE,            // 噪声/散户：RETAIL → 半随机，ZI 基底语义化
        LIQUIDITY_PROVIDER // 流动性/庄家：MARKET_MAKER → 双边挂单、吸单
    }

    /** 确定性映射：AgentType → TraderClass（不消耗随机）。 */
    public static TraderClass traderClassOf(AgentType type) {
        switch (type) {
            case VALUE: return TraderClass.FUNDAMENTALIST;
            case MOMENTUM: return TraderClass.CHARTIST;
            case RETAIL: return TraderClass.NOISE;
            case MARKET_MAKER: return TraderClass.LIQUIDITY_PROVIDER;
            default: return TraderClass.NOISE;
        }
    }

    /**
     * MarS 微观层四角色规范化（MP-1 §2.3/§3.2）：做市商 / 方向性 / 流动性 / 背景噪声。
     * 现有 AgentType 渐进映射（不推倒重来），与 TraderClass 一一对应：
     * <ul>
     *   <li>做市商（MARKET_MAKER）→ LIQUIDITY_PROVIDER 多档双边报价（marketMakerQuotes）；</li>
     *   <li>方向性（VALUE/MOMENTUM）→ FUNDAMENTALIST/CHARTIST 定向单（价值回归/趋势跟踪）；</li>
     *   <li>流动性（RETAIL）→ NOISE 散户噪声单；</li>
     *   <li>背景噪声（OrderFlowModel ZI/Hawkes）→ 独立于 agent 集合，由 OrderFlowModel 提供
     *       （roleCounts 中计数为 0，属正常：该角色不在本 agent 池内）。</li>
     * </ul>
     */
    public enum MicroRole {
        MARKET_MAKER("做市商"),
        DIRECTIONAL("方向性"),
        LIQUIDITY("流动性"),
        BACKGROUND_NOISE("背景噪声");

        public final String label;

        MicroRole(String label) {
            this.label = label;
        }
    }

    /** 确定性映射：AgentType → MarS 微观角色（不消耗随机）。 */
    public static MicroRole microRoleOf(AgentType type) {
        switch (type) {
            case MARKET_MAKER: return MicroRole.MARKET_MAKER;
            case VALUE:
            case MOMENTUM:     return MicroRole.DIRECTIONAL;
            case RETAIL:
            default:           return MicroRole.LIQUIDITY;
        }
    }

    public static class AgentState {
        public AgentType type;
        public TraderClass traderClass;
        public double cash;
        public double holdings;
        public double avgCost;
        public double riskTolerance;
        public double tradeFrequency;
        public double aggressiveness;
        public double lastPrice;
        public int consecutiveTrades;
        public double momentumScore;
        public double fundamentalSensitivity;
        // 新增：环境感知参数
        public double riskAversion;    // 风险厌恶系数 (受宏观影响)
        public double leverage;        // 杠杆倍数
        // ABM decision state (TablAbm3 semantics)
        public long lastMomentumDay = Long.MIN_VALUE;
        public double retailFlow = 0.0;

        public AgentState(AgentType type, long seed) {
            SeedableRandom rnd = new SeedableRandom(SeedableRandom.mix64(seed));
            this.type = type;
            this.traderClass = traderClassOf(type); // P2-1：确定性赋值，不消耗随机
            this.cash = 100000;
            this.holdings = 0;
            this.avgCost = 0;
            this.tradeFrequency = 0.3 + rnd.nextDouble() * 0.4;
            this.aggressiveness = 0.2 + rnd.nextDouble() * 0.6;
            this.lastPrice = 0;
            this.consecutiveTrades = 0;
            this.momentumScore = 0;
            this.riskAversion = 1.0;
            this.leverage = 1.0;
            switch (type) {
                case RETAIL:
                    this.riskTolerance = 0.3 + rnd.nextDouble() * 0.3;
                    this.cash = 10000 + rnd.nextDouble() * 90000;
                    this.fundamentalSensitivity = 0.6 + rnd.nextDouble() * 0.3;
                    break;
                case MOMENTUM:
                    this.riskTolerance = 0.6 + rnd.nextDouble() * 0.3;
                    this.cash = 50000 + rnd.nextDouble() * 450000;
                    this.fundamentalSensitivity = 0.7 + rnd.nextDouble() * 0.2;
                    this.leverage = 1.5;
                    break;
                case MARKET_MAKER:
                    this.riskTolerance = 0.2 + rnd.nextDouble() * 0.2;
                    this.cash = 500000 + rnd.nextDouble() * 4500000;
                    this.tradeFrequency = 0.6 + rnd.nextDouble() * 0.3;
                    this.fundamentalSensitivity = 0.8 + rnd.nextDouble() * 0.15;
                    this.leverage = 2.0;
                    break;
                case VALUE:
                    this.riskTolerance = 0.1 + rnd.nextDouble() * 0.2;
                    this.cash = 200000 + rnd.nextDouble() * 800000;
                    this.tradeFrequency = 0.2 + rnd.nextDouble() * 0.3;
                    this.fundamentalSensitivity = 0.9 + rnd.nextDouble() * 0.1;
                    this.leverage = 1.0;
                    break;
            }
        }
    }

    private final Map<String, List<AgentState>> agentsByAsset = new ConcurrentHashMap<>();
    private final Map<String, List<PriceDiscoveryEngine.OrderFlow>> agentOrders = new ConcurrentHashMap<>();
    private final Map<String, AICompanyData> companyDataMap = new ConcurrentHashMap<>();
    // P0-7：可播种 PRNG（决策随机），使智能体行为可复现
    private final SeedableRandom random;
    private final long engineSeed;

    // ===== LIQ P2（LIQUIDITY_SPEC §5，LQ-1/LQ-4）：专职做市商 MM-Agent（存货模型 + 动态价差） =====
    // 每资产 1 个专职义务做市商（synthetic），与既有 MARKET_MAKER 池 agent 并存。独立子流
    // derive("liq/mm/"+assetId)（§7.3）；liq.mm 关闭时零执行、零随机消费 → 因子隔离（LQ-9/LQ-10）。
    private final Map<String, MarketMakerAgent> marketMakers = new ConcurrentHashMap<>();
    /** 对应 liq.mm 语义（默认随总开关 true）；liq.enabled=false 时引擎应同步 setLiquidityMakerEnabled(false)。 */
    private boolean liquidityMakerEnabled = true;
    /** §5.2 选项 A：MM 报价流随 stepAgents 的 agentOrders 同批注入（默认开）；引擎侧独立驱动（撤旧重挂）时置 false。 */
    private boolean marketMakerAutoFlow = true;
    /** 引擎 liq.enabled 硬门（S1，LQ-10）：false → MM 报价流/onFill 全关（独立于上面两开关，见 setEngineLiqGate）。 */
    private boolean engineLiqGate = true;
    /** MarketEngine.floatSharesOf(assetId) 注入缝（AI=万股×10000、玩家公司=floatShares，§5.1/§8.2）；null → fallback。 */
    private java.util.function.Function<String, Long> floatSharesProvider;
    // MM 配置注入暂存（引擎 CalibrationParams 到货前用 MarketMakerAgent 默认先验）
    private double mmCfgAlpha = MarketMakerAgent.DEFAULT_SPREAD_ALPHA;
    private double mmCfgBeta = MarketMakerAgent.DEFAULT_SPREAD_BETA;
    private double mmCfgGammaBase = MarketMakerAgent.DEFAULT_GAMMA_BASE;
    private double mmCfgDepthFraction = MarketMakerAgent.DEFAULT_DEPTH_FRACTION;
    private long mmCfgDepthCap = MarketMakerAgent.DEFAULT_DEPTH_CAP_SHARES;
    private double mmCfgInvCapMult = MarketMakerAgent.DEFAULT_INVENTORY_CAP_MULT;
    private int mmCfgFillTicks = MarketMakerAgent.DEFAULT_FILL_TICKS;
    private int mmCfgTiersMin = MarketMakerAgent.DEFAULT_QUOTE_TIERS_MIN;
    private int mmCfgTiersMax = MarketMakerAgent.DEFAULT_QUOTE_TIERS_MAX;

    // P1-1 Cont–Bouchaud 羊群模型（可播种、a<0.3 硬约束在模型内强制）
    private final HerdingModel herding;
    // P1-2 前景理论 / 损失厌恶 / 处置效应效用
    private final BehaviorUtility behaviorUtility;
    // P2-1 异质交易者内生定价参数包（开/关开关 + 锚权重 + 三类占比）
    private BehaviorParams behaviorParams = BehaviorParams.DEFAULT;
    // MarS 中观→微观偏置（MP-1 §3.2-5，可选）：板块净资金流信号 Provider（assetId → 板块净流，股数）。
    // null = 0 偏置（默认），不改既有 P2 行为；仅在 marS.enabled && marS.mesoToMicroBias 时由 MarketEngine 接线。
    private java.util.function.Function<String, Double> sectorFlowProvider;
    // 注（F1/R1 修复）：净订单流失衡不再以独立字段/Method 聚合——曾为死代码（无消费者）。
    // 价格内生反馈改由撮合主动方方向实现：OrderBook.walkBook 按确定性规则（先到者为被动、
    // 同 tick 取量大者、并列买主动）判定主动吃单方，主动方方向即净流向方向，价格向失衡方向
    // 移动；净失衡的“量”随累计吃单量经 impact()=√(s/d₀)+s/d₀ 增长（深度冲击）。

    // 宏观环境引用 (由外部注入)
    private MacroEnvironment macroEnv;
    // 市场情绪引用 (由外部注入；未注入时降级为中性 0.5)
    private MarketSentiment sentiment;

    // ----- P0-4 混合定价锚参数（默认值可调；硬约束 |β_gbm|+|β_macro|+|β_sent| < 0.9 防发散） -----
    private static final double BETA_GBM = 0.3;    // 对 GBM 价格偏离 fairValue 的锚定权重
    private static final double BETA_MACRO = 0.3;  // 对风险偏好偏离中性的锚定权重
    private static final double BETA_SENT = 0.2;   // 情绪对下单大小/方向的权重（并入 buyRatio 缩放）
    private static final double ANCHOR_MIN_SCALE = 0.5; // anchor 钳制下界（fairValue 的倍数）
    private static final double ANCHOR_MAX_SCALE = 1.5; // anchor 钳制上界（fairValue 的倍数）

    // F2（MS-AI-6）：agent 杠杆硬上限，防止周期/危机乘子派生杠杆失控（明斯基去杠杆闭环安全阀）。
    private static final double LEVERAGE_MAX = 5.0;

    // ----- F2（t3，agent-dev）订单流密度提升常量：单一用途、先验、确定性缩放，不引入新随机 -----
    // ① 智能体每 tick 下单概率尺度（原 0.3 → 0.5）：提升合成流密度，避免成交稀疏/订单流不足。
    // ② 做市商每 tick 双侧报价概率尺度（原 0.5 → 0.8）：F2.3 开盘即多档双边深度，吸收单边卖压。
    private static final double AGENT_ORDER_PROB_SCALE = 0.5;
    // t8（盘口双侧）：做市商每 tick 双侧报价概率 0.8 → 0.95，提高多档双边深度供给，
    // 使 best bid/ask 双侧有档率更接近 ≥90%（与 t7 引擎侧协同）。
    private static final double MARKET_MAKER_QUOTE_PROB = 0.95;
    // t6（羊群梯度）：行业净方向「决定性」死区。bias 的 |值|≥此阈值才视为有明确多数方向，
    // 低于则不覆盖智能体自身方向（避免噪声翻转稀释同向浓度、使 a>0 真正增强同向聚集）。先验：0.15。
    private static final double HERD_BIAS_DEADBAND = 0.15;

    // ----- TABL-ABM agent decision mode (default primary) -----
    // These mirror TablAbm3: value is a dead-banded anchor demand, momentum rebalances once/day,
    // retail is AR(1) order flow, MM is a 2-level ladder with trend skew.
    private static final double ABM_VALUE_K = 1500.0;
    private static final double ABM_VALUE_DEADBAND = 0.025;
    private static final double ABM_MOM_K = 200.0;
    private static final double ABM_STEPS = 300.0;
    private boolean abmAgentsEnabled = Boolean.parseBoolean(
            System.getProperty("financialmarket.abm.agents", "true"));

    // ----- 启动阶段控制 -----
    public enum StartupPhase { VALUE_ONLY, VALUE_MAKER, FULL }
    private StartupPhase currentPhase = StartupPhase.VALUE_ONLY;
    private int tickCounter = 0;
    private static final int VALUE_ONLY_END = 500;
    private static final int VALUE_MAKER_END = 2000;

    // ----- MS-5a 损失函数硬约束（加息不得上调估值） -----
    // 物理惩罚项系数 λ：外部可见可配置常量，默认温和（不落盘，编译期常量）。
    public static final double MACRO_PENALTY_LAMBDA = 0.5;
    private final Map<String, Double> lastFairValueByAsset = new ConcurrentHashMap<>();
    private final Map<String, Double> lastInterestRateByAsset = new ConcurrentHashMap<>();
    private double macroPenaltyAccumulator = 0.0;

    public MultiAgentManager() {
        this(0L);
    }

    /** P0-7：以指定引擎种子构造；agent 子流与决策随机均由此派生。 */
    public MultiAgentManager(long engineSeed) {
        this.engineSeed = engineSeed;
        this.random = new SeedableRandom(SeedableRandom.mix64(engineSeed));
        // P1-1/P1-2：以同 engineSeed 派生子流，保持 P0 可复现性
        this.herding = new HerdingModel(engineSeed);
        this.behaviorUtility = new BehaviorUtility();
    }

    public void setMacroEnvironment(MacroEnvironment env) {
        this.macroEnv = env;
    }

    public void setSentiment(MarketSentiment sentiment) {
        this.sentiment = sentiment;
    }

    /** P2-1：注入内生定价参数包（开/关开关 + 锚权重 + 三类占比）；null 恢复默认。 */
    public void setBehaviorParams(BehaviorParams params) {
        this.behaviorParams = params != null ? params : BehaviorParams.DEFAULT;
    }

    public void setAbmAgentsEnabled(boolean enabled) {
        this.abmAgentsEnabled = enabled;
    }

    public boolean isAbmAgentsEnabled() {
        return abmAgentsEnabled;
    }

    /** P2-1：读取当前内生定价参数包。 */
    public BehaviorParams getBehaviorParams() {
        return behaviorParams;
    }

    // ---- MarS 中观→微观偏置（MP-1 §3.2-5，可选；默认 null=0 不改既有 P2 行为） ----

    /** 板块净资金流归一化尺度（代码常量，先验；1e6 股 ≈ 全市场一档量级）。 */
    private static final double SECTOR_FLOW_NORM = 1_000_000.0;

    /** 注入板块净资金流 Provider（assetId → 板块净流，股数）；null 恢复零偏置。 */
    public void setSectorFlowProvider(java.util.function.Function<String, Double> provider) {
        this.sectorFlowProvider = provider;
    }

    /** 是否已注入中观→微观偏置 Provider（供验证 harness 断言隔离：marS 关闭时应为 false）。 */
    public boolean hasSectorFlowProvider() {
        return sectorFlowProvider != null;
    }

    /** 板块净资金流信号（provider 为 null 时返回 0，不改既有行为）。 */
    private double sectorFlowOf(String assetId) {
        return sectorFlowProvider != null ? sectorFlowProvider.apply(assetId) : 0.0;
    }

    /**
     * 单资产 MarS 微观四角色计数（MP-1-1，确定性，供中观/验证只读消费）。
     * 背景噪声（ZI/Hawkes）由 OrderFlowModel 独立提供，不在此 agent 池内（计数为 0）。
     */
    public Map<MicroRole, Integer> roleCounts(String assetId) {
        Map<MicroRole, Integer> counts = new EnumMap<>(MicroRole.class);
        for (MicroRole r : MicroRole.values()) counts.put(r, 0);
        for (AgentState a : getAgents(assetId)) counts.merge(microRoleOf(a.type), 1, Integer::sum);
        return counts;
    }

    /** 全资产 MarS 微观四角色总览（资产 id 排序，确定性遍历，§7.1-3）。 */
    public Map<MicroRole, Integer> totalRoleCounts() {
        Map<MicroRole, Integer> counts = new EnumMap<>(MicroRole.class);
        for (MicroRole r : MicroRole.values()) counts.put(r, 0);
        List<String> ids = new ArrayList<>(agentsByAsset.keySet());
        Collections.sort(ids);
        for (String id : ids) {
            for (Map.Entry<MicroRole, Integer> e : roleCounts(id).entrySet()) {
                counts.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * MS-5a：累计的宏观损失惩罚项（λ·Σ max(0, 本轮fairValue−上一期fairValue)，仅加息时非零）。
     * 供验证/日志观察「加息不得上调估值」是否被物理惩罚项约束。确定性、无随机。
     */
    public double getMacroPenaltyAccumulator() {
        return macroPenaltyAccumulator;
    }

    public void updatePhase(int tick) {
        this.tickCounter = tick;
        if (tick < VALUE_ONLY_END) {
            currentPhase = StartupPhase.VALUE_ONLY;
        } else if (tick < VALUE_MAKER_END) {
            currentPhase = StartupPhase.VALUE_MAKER;
        } else {
            currentPhase = StartupPhase.FULL;
        }
    }

    public StartupPhase getCurrentPhase() { return currentPhase; }

    public boolean isAgentTypeActive(AgentType type) {
        switch (currentPhase) {
            case VALUE_ONLY:
                // F2.3（t3）：做市商提前介入——冷启动（VALUE_ONLY）即引入 MARKET_MAKER，
                // 保证开盘即有双侧深度吸收单边卖压（避免跌停锁死/成交稀疏）。VALUE 仍为主导。
                return type == AgentType.VALUE || type == AgentType.MARKET_MAKER;
            case VALUE_MAKER:
                return type == AgentType.VALUE || type == AgentType.MARKET_MAKER;
            case FULL:
                return true;
            default: return false;
        }
    }

    public void initializeAgents(String assetId, int count) {
        if (agentsByAsset.containsKey(assetId)) return;
        List<AgentState> agents = new ArrayList<>();
        long baseSeed = SeedableRandom.mix64(engineSeed ^ assetId.hashCode());
        int idx = 0;

        if (behaviorParams.isEndogenousEnabled()) {
            // P2-1 三类交易者占比（fundamentalist 0.35 / chartist 0.30 / noise 0.25 / liquidity 0.10）
            int fundCount = (int)Math.round(count * behaviorParams.getFundamentalShare());
            int chartCount = (int)Math.round(count * behaviorParams.getChartistShare());
            int noiseCount = (int)Math.round(count * behaviorParams.getNoiseShare());
            int makerCount = count - fundCount - chartCount - noiseCount;
            for (int i = 0; i < fundCount; i++) agents.add(new AgentState(AgentType.VALUE, SeedableRandom.mix64(baseSeed + (idx++))));
            for (int i = 0; i < chartCount; i++) agents.add(new AgentState(AgentType.MOMENTUM, SeedableRandom.mix64(baseSeed + (idx++))));
            for (int i = 0; i < noiseCount; i++) agents.add(new AgentState(AgentType.RETAIL, SeedableRandom.mix64(baseSeed + (idx++))));
            for (int i = 0; i < makerCount; i++) agents.add(new AgentState(AgentType.MARKET_MAKER, SeedableRandom.mix64(baseSeed + (idx++))));
        } else {
            // P2 关闭 → 与 P1 原占比逐位一致（因子隔离）
            int valueCount = (int)(count * 0.3);
            int retailCount = (int)(count * 0.3);
            int momentumCount = (int)(count * 0.2);
            int makerCount = count - valueCount - retailCount - momentumCount;
            for (int i = 0; i < valueCount; i++) agents.add(new AgentState(AgentType.VALUE, SeedableRandom.mix64(baseSeed + (idx++))));
            for (int i = 0; i < retailCount; i++) agents.add(new AgentState(AgentType.RETAIL, SeedableRandom.mix64(baseSeed + (idx++))));
            for (int i = 0; i < momentumCount; i++) agents.add(new AgentState(AgentType.MOMENTUM, SeedableRandom.mix64(baseSeed + (idx++))));
            for (int i = 0; i < makerCount; i++) agents.add(new AgentState(AgentType.MARKET_MAKER, SeedableRandom.mix64(baseSeed + (idx++))));
        }
        // ----- MS-3/MS-4 持仓播种（冷启动）：给 VALUE（与 MOMENTUM）初始化持仓使开盘即可卖 -----
        // 根因（R-B2/R-B3）：VALUE_ONLY 阶段 VALUE 单卖出要求 holdings>=size，冷启动 holdings=0 →
        // 开盘只能挂 BUY → 盘口单边（bids有量、asks恒0）。此处按 engineSeed 派生独立子流
        // 确定性播种持仓（非裸随机），并提供足够卖侧存量。init 与 load 均 power 同样播种，
        // 确定性一致（initializeAgents 幂等，AgentState 本就不落盘，读档重建同序列）。
        SeedableRandom seedRng = new SeedableRandom(SeedableRandom.mix64(engineSeed ^ assetId.hashCode() ^ 0x7E57C05DL));
        for (AgentState agent : agents) {
            if (agent.type == AgentType.VALUE) {
                agent.holdings = 5000 + seedRng.nextInt(50000);   // 开盘即可卖出（VALUE 单 max size≈5000）
                agent.avgCost = 0; // 参考点未知 → prospectPropensity 返回 1.0，不扰动决策
            } else if (agent.type == AgentType.MOMENTUM) {
                agent.holdings = 2000 + seedRng.nextInt(30000);
                agent.avgCost = 0;
            }
            // RETAIL / MARKET_MAKER 不播种（做市商双侧报价、散户小单，无需初始持仓）
        }
        agentsByAsset.put(assetId, agents);
        agentOrders.put(assetId, new ArrayList<>());
        System.out.println("🤖 资产 " + assetId + " 初始化 " + agents.size() + " 个智能体（含持仓播种）");
    }

    public void updateCompanyData(String assetId, AICompanyData data) {
        companyDataMap.put(assetId, data);
    }

    public List<AgentState> getAgents(String assetId) {
        return agentsByAsset.getOrDefault(assetId, Collections.emptyList());
    }

    public List<PriceDiscoveryEngine.OrderFlow> consumeOrders(String assetId) {
        List<PriceDiscoveryEngine.OrderFlow> orders = agentOrders.getOrDefault(assetId, Collections.emptyList());
        agentOrders.put(assetId, new ArrayList<>());
        return orders;
    }

    public void stepAgents(String assetId, double gbmPrice, double lastPrice, OrderBook orderBook, double liquidityMultiplier) {
        if (gbmPrice <= 0 || lastPrice <= 0) return;
        List<AgentState> agents = agentsByAsset.get(assetId);
        if (agents == null || agents.isEmpty()) return;

        AICompanyData company = companyDataMap.get(assetId);
        if (company == null) return;

        // ----- MS-7 可复现性（禁共享决策随机）：每资产派生独立决策子流 -----
        // 根因（spec §4）：stepAgents 原先用单一共享 this.random 随 state.getAssetIds()（HashMap
        // keySet）迭代顺序消费；玩家公司注册改变集合插入顺序即扰动后续所有智能体决策序列。
        // 改为按资产派生（random.derive(assetId) → 新子流），同种子 + 同注册时序下逐位一致。
        SeedableRandom assetRng = new SeedableRandom(SeedableRandom.mix64(this.random.derive(assetId)));

        // 从宏观环境获取风险偏好
        double riskAppetite = macroEnv != null ? macroEnv.getRiskAppetite() : 0.5;

        // F2（MS-AI-6 明斯基/金融周期→agent 杠杆显式传导）：周期相位（PEAK/EXPANSION 堆积、
        // CONTRACTION/TROUGH 去杠杆）+ 危机去杠杆乘子。繁荣/顶部 agent 杠杆堆积、收缩/危机强制降杠杆。
        // 读取周期/危机杠杆乘子（t3 Producer 提供；默认中性=1，未激活时不影响基线），确定性、无随机。
        double cycleLeverageMul = macroEnv != null ? macroEnv.getCycleLeverageMultiplier() : 1.0;
        double crisisDeleverageMul = macroEnv != null ? macroEnv.getCrisisDeleverageMultiplier() : 1.0;

        // 更新每个智能体的风险厌恶系数 (受宏观影响)
        for (AgentState agent : agents) {
            // 风险厌恶 = 基础风险厌恶 + 宏观恐慌影响
            double baseRisk = 1.0 - agent.riskTolerance;
            agent.riskAversion = baseRisk + (1.0 - riskAppetite) * 0.5;
            // 杠杆受宏观流动性 + 周期/危机乘子影响（明斯基堆积→去杠杆闭环）；硬上限防失控
            double levTarget = agent.leverage * liquidityMultiplier * cycleLeverageMul * crisisDeleverageMul;
            agent.leverage = Math.max(1.0, Math.min(LEVERAGE_MAX, levTarget));
        }

        var snap = orderBook != null ? orderBook.getSnapshot() : null;
        double bestBid = 0, bestAsk = 0, spread = 0.001;
        if (snap != null && !snap.bids.isEmpty()) bestBid = snap.bids.get(0).price;
        if (snap != null && !snap.asks.isEmpty()) bestAsk = snap.asks.get(0).price;
        if (bestBid > 0 && bestAsk > 0) spread = (bestAsk - bestBid) / gbmPrice;
        if (spread < 0.001) spread = 0.001;

        // ----- MS-AI-4 基本面估值：统一用 company.getFairValue()（DCF+PE+PB 每股体系），不再戈登重算 -----
        // 根因（spec §2.1）：旧戈登 fairValue = eps·(1+g)/(discount−max(0.01,g))，高成长股 growth≥discountRate
        // 时分母 ≤0 → fairValue<0 → 恒被钳到 0.1 地板（且与 DCF 体系互相打架、系统性卖压）。现统一用
        // company.getFairValue()，并对非法/退化值施加安全分母护栏（回落安全值），杜绝 0.1 坍塌。
        double fairValue = safeFairValue(company, company.getFairValue());

        // ----- MS-5a 损失函数硬约束：加息时基本面AI不得上调估值 -----
        // 物理惩罚项：当政策利率相对上一期上升（Δr>0）时，newFairValue = min(newFairValue, prevFairValue)
        // （强制不升），并累计惩罚 L += λ·max(0, newFairValue - prevFairValue)。确定性、无随机。
        // 每资产独立追踪上一期利率/估值（所有资产同 tick 共享同一宏观利率，逐资产对照避免
        // 首资产独占 Δr 检测）。保留既有硬上限启发式（利率高企且估值远超GBM价格时拉回）。
        if (macroEnv != null) {
            double interestRate = macroEnv.getInterestRate();
            if (interestRate > 0.04 && fairValue > gbmPrice * 1.2) {
                fairValue = gbmPrice * 1.05; // 强行拉回合理区间（保留原语义）
            }
            double prevFairValue = lastFairValueByAsset.getOrDefault(assetId, Double.NaN);
            double prevRate = lastInterestRateByAsset.getOrDefault(assetId, Double.NaN);
            boolean rateRising = !Double.isNaN(prevRate) && interestRate > prevRate + 1e-9;
            if (rateRising && !Double.isNaN(prevFairValue) && fairValue > prevFairValue) {
                double violation = fairValue - prevFairValue;
                macroPenaltyAccumulator += MACRO_PENALTY_LAMBDA * violation; // 物理惩罚项
                fairValue = prevFairValue; // 强制单调不升：加息不得上调估值
            }
            lastInterestRateByAsset.put(assetId, interestRate);
            lastFairValueByAsset.put(assetId, fairValue);
        }

        // MS-AI-4 最终下限护栏：MS-5a 单调约束可能把 fairValue 拉低，重新施加安全护栏并回写，
        // 确保 fairValue ≥ 0.5×bookValuePerShare、不坍塌、不上限失控（与基本面 DCF 一致）。
        fairValue = safeFairValue(company, fairValue);
        lastFairValueByAsset.put(assetId, fairValue);

        double deviation = (lastPrice - fairValue) / fairValue;

        // ----- P0-4 混合定价锚：统一定价锚 = fairValue·(1 + β_gbm·(gbmPrice/fairValue−1) + β_macro·(riskAppetite−0.5)) -----
        double gbmRatio = fairValue > 0 ? gbmPrice / fairValue : 1.0;
        double anchor = fairValue * (1.0 + BETA_GBM * (gbmRatio - 1.0) + BETA_MACRO * (riskAppetite - 0.5));
        // 硬约束钳制到 [fairValue*0.5, fairValue*1.5] 防极端/振荡
        anchor = Math.max(fairValue * ANCHOR_MIN_SCALE, Math.min(fairValue * ANCHOR_MAX_SCALE, anchor));

        // ----- P2-1 内生定价锚：endogenousAnchor = w_mid·mid + w_fv·anchor（GBM 降级为噪声地板）-----
        // mid 由订单簿内生涌现（best bid/ask 均值），价格由三类交易者净订单流经订单簿撮合涌现；
        // P2 关闭时 endogenousAnchor = anchor，与 P1 逐位一致（因子隔离）。
        double mid = lastPrice;
        if (bestBid > 0 && bestAsk > 0) mid = (bestBid + bestAsk) / 2.0;
        double endogenousAnchor;
        if (behaviorParams.isEndogenousEnabled()) {
            endogenousAnchor = behaviorParams.getAnchorWeightMid() * mid + behaviorParams.getAnchorWeightFV() * anchor;
            endogenousAnchor = Math.max(fairValue * ANCHOR_MIN_SCALE, Math.min(fairValue * ANCHOR_MAX_SCALE, endogenousAnchor));
        } else {
            endogenousAnchor = anchor;
        }

        // 情绪买入倾向（未注入时取中性 0.5）；宏观恐慌(riskAppetite↓)使 buyRatio 下降
        double buyRatio = sentiment != null ? sentiment.getBuyRatio(assetId) : 0.5;

        // ---- P1-1 羊群：group 复用行业（company.getIndustry()）----
        String group = company.getIndustry();
        herding.setGroup(group, agents.size());

        // P2-1 净失衡（§2.3）：最优档买量−卖量归一化到 [-1,1]（供 chartist 信号 + 订单簿内生消费）
        double netImbalance = netImbalanceOf(snap);

        List<PriceDiscoveryEngine.OrderFlow> newOrders = new ArrayList<>();
        for (AgentState agent : agents) {
            if (!isAgentTypeActive(agent.type)) continue;
            // ---- P1-1 羊群扰动判定（a=0 时不消耗随机，保 P0 逐位一致）----
            boolean herdActive = herding.getHerdingStrength() > 0;
            boolean herdFollow = false;
            double herdBias = herding.computeGroupBias(assetId, group);
            if (herdActive) {
                herdFollow = herding.shouldHerd(assetRng);
                // 价值型基本面敏感度最高，按 (1-fundamentalSensitivity) 弱化羊群，保持更理性
                if (agent.type == AgentType.VALUE) {
                    herdFollow = herdFollow && (assetRng.nextDouble() < (1.0 - agent.fundamentalSensitivity));
                }
            }
            // F2（t3）合成流密度提升：下单概率尺度由 0.3 → AGENT_ORDER_PROB_SCALE（0.5），
            // 提高每 tick 订单到达率，避免订单流稀疏/成交稀疏。确定性（assetRng 子流）。
            // t8（羊群 60k）：羊群跟随者跳过参与门控（herdFollow 时强制下单），使 a>0 的同向订单
            // 更密集、短窗口即可建立同向浓度；a=0 路径不变（herdFollow 恒 false → 逐位一致）。
            double gateProb = agent.tradeFrequency * AGENT_ORDER_PROB_SCALE * riskAppetite;
            if (!herdFollow && assetRng.nextDouble() > gateProb) continue;

            double priceChange = (lastPrice - agent.lastPrice) / (agent.lastPrice > 0 ? agent.lastPrice : gbmPrice);
            agent.lastPrice = lastPrice;
            agent.momentumScore = agent.momentumScore * 0.9 + priceChange * 0.1;

            // P2-1 chartist 信号：用订单簿净失衡 + mid 变化（替代纯 priceChange）；关闭时回落 priceChange
            double chartistSignal = priceChange;
            if (behaviorParams.isEndogenousEnabled()) {
                chartistSignal = netImbalance * 0.5 + priceChange * 0.5;
                // MarS 中观→微观偏置（MP-1 §3.2-5，可选）：板块净资金流信号加到 chartist 信号
                // （羊群式板块聚集的微观反馈）。provider 为 null 或信号为 0 时完全不介入 → 默认 0，
                // 不改既有 P2 行为（因子隔离：marS.enabled=false 时 provider 恒 null）。
                double sectorFlow = sectorFlowOf(assetId);
                if (sectorFlow != 0.0) {
                    chartistSignal += 0.5 * Math.tanh(sectorFlow / SECTOR_FLOW_NORM);
                }
            }

            PriceDiscoveryEngine.OrderFlow order = null;
            if (abmAgentsEnabled) {
                if (agent.type == AgentType.MARKET_MAKER) {
                    if (assetRng.nextDouble() < MARKET_MAKER_QUOTE_PROB * riskAppetite) {
                        newOrders.addAll(abmMarketMakerQuotes(agent, lastPrice, spread,
                                riskAppetite, assetRng));
                    }
                } else {
                    order = abmDecision(agent, fairValue, lastPrice, chartistSignal,
                            spread, riskAppetite, assetRng);
                }
            } else {
                switch (agent.type) {
                    case VALUE:
                        order = valueDecision(agent, fairValue, lastPrice, deviation, spread, riskAppetite, endogenousAnchor, buyRatio, herdFollow, herdBias, assetRng);
                        break;
                    case RETAIL:
                        order = retailDecision(agent, fairValue, lastPrice, deviation, spread, riskAppetite, endogenousAnchor, buyRatio, herdFollow, herdBias, assetRng);
                        break;
                    case MOMENTUM:
                        order = momentumDecision(agent, fairValue, lastPrice, chartistSignal, spread, riskAppetite, endogenousAnchor, buyRatio, herdFollow, herdBias, assetRng);
                        break;
                    case MARKET_MAKER:
                        // MS-AI-2（有效深度）：做市商提高双边报价频率，稳定提供多档盘口深度（bid 在 mid 下方、
                        // ask 在 mid 上方）。确定性（SeedableRandom），不改定价/撮合公式。
                        // F2.3（t3）：做市商提前介入（VALUE_ONLY 即激活）+ 报价概率 0.5 → MARKET_MAKER_QUOTE_PROB
                        //（0.8），保证开盘即有双侧深度、订单流足量。
                        if (assetRng.nextDouble() < MARKET_MAKER_QUOTE_PROB * riskAppetite) {
                            newOrders.addAll(marketMakerQuotes(agent, fairValue, lastPrice, deviation, spread, riskAppetite, endogenousAnchor, buyRatio, herdFollow, herdBias, assetRng));
                        }
                        break;
                }
            }
            if (order != null) {
                // 应用流动性乘子缩放订单量
                order.size = (int)(order.size * liquidityMultiplier);
                if (order.size > 0) {
                    newOrders.add(order);
                    agent.consecutiveTrades++;
                    // P1-1 净流向回写：+1 买 / -1 卖 → group 净方向 EMA。
                    // t6 修复①：HerdingModel.updateBias 内部按 group agentCount 归一化（÷~30），
                    // 若只喂 ±1 则 EMA 净方向被稀释到 ~0 → herdBias>=0 按噪声翻转、羊群不聚集。
                    // 改喂「=agentCount 量的方向」使 bias 反映行业多数方向（同 seed 确定性保持）。
                    // t6 修复②：做市商双侧报价（买+卖同时、量近相等）会使净流归零、稀释 bias，
                    // 故只让方向性交易者（VALUE/RETAIL/MOMENTUM）喂 bias；做市商不参与羊群方向。
                    if (agent.type != AgentType.MARKET_MAKER) {
                        int gs2 = Math.max(1, agents.size());
                        herding.updateBias(group, (order.isBuy ? 1.0 : -1.0) * gs2);
                    }
                }
            } else {
                agent.consecutiveTrades = Math.max(0, agent.consecutiveTrades - 1);
            }
        }
        agentOrders.get(assetId).addAll(newOrders);
        // ----- LIQ P2（LIQUIDITY_SPEC §5.2「随 agentOrders 同批」，LQ-1/LQ-4）-----
        // 专职做市商 MM-Agent（存货 Inventory 模型 + 动态价差）：每 tick 义务式双边报价（厚度≈5% 流通股），
        // 报价流并入 agentOrders 走既有 L2 通路。ramp 模式每 tick 只铺 targetDepth/mmFillTicks，
        // 存量有界、top-of-book 首 tick 即挂出。独立子流 derive("liq/mm/"+assetId)（§7.3），不扰动
        // assetRng/其他决策随机；liq.mm 关闭（liquidityMakerEnabled=false）时零执行、零随机消费 → 因子隔离。
        if (!abmAgentsEnabled && liquidityMakerEnabled && marketMakerAutoFlow
                && engineLiqGate && mid > 0) {
            List<PriceDiscoveryEngine.OrderFlow> mmFlows = marketMaker(assetId)
                    .rampedFlows(assetId, mid, 0.0, floatSharesOf(assetId), orderBook, tickCounter);
            if (!mmFlows.isEmpty()) {
                // LIQ P2（S2）：专职 MM 报价流打会话标签（"liq-mm:"+assetId）→ 经引擎 L2 保留到 Order.mmTag，
                // 成交归属（OrderBook.mmFillListener → agentManager.onMarketMakerFill）据此更新存货 I(t)
                tagMmFlows(assetId, mmFlows);
                agentOrders.get(assetId).addAll(mmFlows);
            }
        }
        // （F1/R1 修复：价格内生反馈由撮合主动方方向实现——见 OrderBook.walkBook 注释，此处不再聚合死代码）
    }


    /** P2-1 §2.3：盘口净失衡 = (Σ最优档买量 − Σ最优档卖量)/(Σ买量+Σ卖量)，归一化到 [-1,1]。 */
    private double netImbalanceOf(OrderBook.OrderBookSnapshot snap) {
        if (snap == null) return 0.0;
        double buy = 0, sell = 0;
        for (OrderBook.OrderBookLevel l : snap.bids) buy += l.quantity;
        for (OrderBook.OrderBookLevel l : snap.asks) sell += l.quantity;
        double total = buy + sell;
        if (total <= 0) return 0.0;
        return (buy - sell) / total;
    }

    // ---- 价值投资者：围绕混合锚挂单，低频稳定 ----
    // ---- TABL-ABM decision layer (TablAbm3 semantics) ----
    private PriceDiscoveryEngine.OrderFlow abmDecision(AgentState agent, double fairValue,
                                                        double lastPrice, double chartistSignal,
                                                        double spread, double riskAppetite,
                                                        SeedableRandom rng) {
        if (lastPrice <= 0 || !Double.isFinite(lastPrice)) {
            return null;
        }
        switch (agent.type) {
            case VALUE:
                return abmValue(agent, fairValue, lastPrice, rng);
            case MOMENTUM:
                return abmMomentum(agent, lastPrice, chartistSignal);
            case RETAIL:
                return abmRetail(agent, lastPrice, spread, rng);
            default:
                return null;
        }
    }

    private PriceDiscoveryEngine.OrderFlow abmValue(AgentState agent, double fairValue,
                                                    double lastPrice, SeedableRandom rng) {
        if (fairValue <= 0 || !Double.isFinite(fairValue)) {
            return null;
        }
        double equity = agent.cash + agent.holdings * lastPrice;
        double target = AbmAgentMath.valueTarget(fairValue, lastPrice, equity, agent.leverage,
                ABM_VALUE_K, ABM_VALUE_DEADBAND);
        int qty = AbmAgentMath.stepQty(target, agent.holdings, ABM_STEPS);
        if (qty == 0) {
            return null;
        }
        boolean buy = qty > 0;
        double price = round2(lastPrice * (buy ? 1.0005 : 0.9995));
        return executeAbm(agent, buy, price, Math.abs(qty));
    }

    private PriceDiscoveryEngine.OrderFlow abmMomentum(AgentState agent, double lastPrice,
                                                       double chartistSignal) {
        long day = tickCounter / 6000L;
        if (agent.lastMomentumDay == day) {
            return null;
        }
        agent.lastMomentumDay = day;
        double equity = agent.cash + agent.holdings * lastPrice;
        double target = AbmAgentMath.momentumTarget(equity, lastPrice, agent.leverage,
                ABM_MOM_K, chartistSignal > 0);
        int qty = (int) Math.round(target - agent.holdings);
        if (qty == 0) {
            return null;
        }
        boolean buy = qty > 0;
        return executeAbm(agent, buy, round2(lastPrice * (buy ? 1.0005 : 0.9995)),
                Math.abs(qty));
    }

    private PriceDiscoveryEngine.OrderFlow abmRetail(AgentState agent, double lastPrice,
                                                     double spread, SeedableRandom rng) {
        agent.retailFlow = 0.20 * agent.retailFlow + 0.98 * rng.nextGaussian();
        double pTrade = 0.12 + Math.min(0.70, 25.0 * Math.max(0.001, spread));
        if (rng.nextDouble() > pTrade) {
            return null;
        }
        int qty = 5 + rng.nextInt(21);
        boolean buy = agent.retailFlow > 0;
        if (!buy) {
            qty = Math.min(qty, (int) agent.holdings);
        }
        if (qty <= 0) {
            return null;
        }
        return executeAbm(agent, buy, round2(lastPrice * (buy ? 1.001 : 0.999)), qty);
    }

    private PriceDiscoveryEngine.OrderFlow executeAbm(AgentState agent, boolean buy,
                                                      double price, int qty) {
        if (qty <= 0 || price <= 0) {
            return null;
        }
        if (buy) {
            double cost = price * qty;
            if (agent.cash < cost) {
                qty = (int) Math.floor(agent.cash / price);
                if (qty <= 0) {
                    return null;
                }
                cost = price * qty;
            }
            agent.cash -= cost;
            double holdings = agent.holdings;
            agent.holdings += qty;
            agent.avgCost = (agent.avgCost * holdings + price * qty)
                    / Math.max(1e-9, agent.holdings);
        } else {
            if (agent.holdings < qty) {
                qty = (int) agent.holdings;
            }
            if (qty <= 0) {
                return null;
            }
            agent.cash += price * qty;
            agent.holdings -= qty;
        }
        return createOrder(buy, price, qty);
    }

    private List<PriceDiscoveryEngine.OrderFlow> abmMarketMakerQuotes(AgentState agent,
                                                                      double lastPrice,
                                                                      double spread,
                                                                      double riskAppetite,
                                                                      SeedableRandom rng) {
        List<PriceDiscoveryEngine.OrderFlow> quotes = new ArrayList<>();
        if (lastPrice <= 0) {
            return quotes;
        }
        double halfSpread = Math.max(0.0004, spread * 1.2) * (0.8 + rng.nextDouble() * 0.4);
        double spreadMul = macroEnv != null
                ? MacroStockTransmission.spreadMultiplier(macroEnv.getMarketSpreadMultiplier())
                : 1.0;
        double depthMul = macroEnv != null
                ? MacroStockTransmission.depthMultiplier(macroEnv.getMarketDepthMultiplier())
                : 1.0;
        halfSpread *= spreadMul;
        int depth = lotRound((int) Math.round(Math.max(100.0,
                1000.0 * riskAppetite * depthMul)));
        for (int k = 1; k <= 2; k++) {
            double bid = round2(lastPrice * (1.0 - halfSpread * k));
            double ask = round2(lastPrice * (1.0 + halfSpread * k));
            if (bid >= ask) {
                ask = Math.max(ask, bid + PriceDiscoveryEngine.A_SHARE_TICK_SIZE);
            }
            quotes.add(createOrder(true, bid, depth));
            quotes.add(createOrder(false, ask, depth));
        }
        return quotes;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private PriceDiscoveryEngine.OrderFlow valueDecision(AgentState agent, double fairValue, double lastPrice,
                                                         double deviation, double spread, double riskAppetite,
                                                         double anchor, double buyRatio,
                                                         boolean herdFollow, double herdBias, SeedableRandom rng) {
        if (Math.abs(deviation) < 0.02) return null; // 价格接近价值，不操作
        boolean isBuy = deviation < 0;
        // P1-1 羊群扰动：跟随群体净方向
        if (herdFollow) isBuy = herdBias >= 0;
        int size = (int)(1000 + Math.abs(rng.nextGaussian()) * 500);
        size = Math.min(size, 5000);
        // P1-2 前景理论调制下单倾向（相对参考点 avgCost）：亏损压制、盈利略增
        size = (int)(size * prospectPropensity(agent, lastPrice));
        // 情绪影响下单大小：买盘 buyRatio 高→更大，卖盘反之
        double sentimentSize = isBuy ? (0.5 + buyRatio) : (1.5 - buyRatio);
        size = (int)(size * sentimentSize);
        double priceOffset = isBuy ? -0.01 - rng.nextDouble() * 0.01 : 0.01 + rng.nextDouble() * 0.01;
        double price = anchor * (1 + priceOffset);
        // F1.4（t3）VALUE 方向平衡/温和回落：卖单限价不越过 fairValue 之下、买单限价不越过
        // fairValue 之上——围绕价值锚对称下单，避免把价格钉向跌停/涨停（RC-3）。确定性、无随机。
        if (isBuy) price = Math.min(price, fairValue);
        else price = Math.max(price, fairValue);
        price = Math.round(price * 100) / 100.0;

        if (isBuy) {
            double cost = price * size;
            if (agent.cash >= cost * 0.9) {
                agent.cash -= cost;
                agent.holdings += size;
                if (agent.holdings > 0) agent.avgCost = (agent.avgCost * (agent.holdings - size) + price * size) / agent.holdings;
                return createOrder(isBuy, price, size);
            }
        } else {
            if (agent.holdings >= size) {
                agent.cash += price * size;
                agent.holdings -= size;
                return createOrder(isBuy, price, size);
            }
        }
        return null;
    }

    // ---- 散户：基于估值和风险偏好 ----
    private PriceDiscoveryEngine.OrderFlow retailDecision(AgentState agent, double fairValue, double lastPrice,
                                                          double deviation, double spread, double riskAppetite,
                                                          double anchor, double buyRatio,
                                                          boolean herdFollow, double herdBias, SeedableRandom rng) {
        boolean isBuy = false;
        if (deviation < -0.15) isBuy = true;
        else if (deviation > 0.15) isBuy = false;
        else isBuy = rng.nextDouble() < buyRatio; // 抛硬币区间改为情绪驱动（G-08）
        // P1-1 羊群扰动：跟随群体净方向
        if (herdFollow) isBuy = herdBias >= 0;

        // P1-2 处置效应：卖出分支（isBuy==false 且持有）按前景/处置门控持有
        if (!isBuy && agent.holdings > 0) {
            if (shouldHoldDueToDisposition(agent, lastPrice, rng)) return null;
        }

        // 风险偏好影响下单概率
        if (rng.nextDouble() > riskAppetite * 0.8) return null;

        int size = (int)(20 + Math.abs(rng.nextGaussian()) * 80);
        size = Math.min(size, 800);
        // P1-2 前景理论调制下单倾向（相对参考点 avgCost）
        size = (int)(size * prospectPropensity(agent, lastPrice));
        // 情绪影响下单大小：买盘 buyRatio 高→更大，卖盘反之
        double sentimentSize = isBuy ? (0.5 + buyRatio) : (1.5 - buyRatio);
        size = (int)(size * sentimentSize);
        double priceOffset = isBuy ?
                -0.02 - rng.nextDouble() * 0.03 :
                0.02 + rng.nextDouble() * 0.03;
        // 激进程度受风险偏好影响
        if (rng.nextDouble() < 0.15 * riskAppetite) {
            priceOffset = isBuy ? (0.005 + rng.nextDouble() * 0.01) : (-0.005 - rng.nextDouble() * 0.01);
        }
        double price = anchor * (1 + priceOffset);
        price = Math.round(price * 100) / 100.0;

        if (isBuy) {
            double cost = price * size;
            if (agent.cash >= cost * 0.9) {
                agent.cash -= cost;
                agent.holdings += size;
                if (agent.holdings > 0) agent.avgCost = (agent.avgCost * (agent.holdings - size) + price * size) / agent.holdings;
                return createOrder(isBuy, price, size);
            }
        } else {
            if (agent.holdings >= size) {
                agent.cash += price * size;
                agent.holdings -= size;
                return createOrder(isBuy, price, size);
            }
        }
        return null;
    }

    // ---- 游资：趋势跟踪 + 风险偏好 ----
    private PriceDiscoveryEngine.OrderFlow momentumDecision(AgentState agent, double fairValue, double lastPrice,
                                                            double priceChange, double spread, double riskAppetite,
                                                            double anchor, double buyRatio,
                                                            boolean herdFollow, double herdBias, SeedableRandom rng) {
        boolean isBuy = false;
        if (priceChange > 0.005 && riskAppetite > 0.4) isBuy = true;
        else if (priceChange < -0.005 && riskAppetite > 0.4) isBuy = false;
        else isBuy = rng.nextDouble() < buyRatio; // 抛硬币区间改为情绪驱动
        // P1-1 羊群扰动：跟随群体净方向
        if (herdFollow) isBuy = herdBias >= 0;

        // P1-2 处置效应：卖出分支（isBuy==false 且持有）按前景/处置门控持有
        if (!isBuy && agent.holdings > 0) {
            if (shouldHoldDueToDisposition(agent, lastPrice, rng)) return null;
        }

        int size = (int)(500 + Math.abs(rng.nextGaussian()) * 800);
        size = Math.min(size, 5000);
        // P1-2 前景理论调制下单倾向（相对参考点 avgCost）
        size = (int)(size * prospectPropensity(agent, lastPrice));
        // 情绪影响下单大小：买盘 buyRatio 高→更大，卖盘反之
        double sentimentSize = isBuy ? (0.5 + buyRatio) : (1.5 - buyRatio);
        size = (int)(size * sentimentSize);
        double priceOffset = isBuy ?
                -spread * (1.0 + rng.nextDouble() * 2.0) :
                spread * (1.0 + rng.nextDouble() * 2.0);
        // 风险偏好影响价格激进程度
        priceOffset *= (1.0 + (1.0 - riskAppetite) * 0.5);
        double price = anchor * (1 + priceOffset);
        price = Math.round(price * 100) / 100.0;

        if (isBuy) {
            double cost = price * size;
            if (agent.cash >= cost * 0.6) {
                agent.cash -= cost;
                agent.holdings += size;
                if (agent.holdings > 0) agent.avgCost = (agent.avgCost * (agent.holdings - size) + price * size) / agent.holdings;
                return createOrder(isBuy, price, size);
            }
        } else {
            if (agent.holdings >= size) {
                agent.cash += price * size;
                agent.holdings -= size;
                return createOrder(isBuy, price, size);
            }
        }
        return null;
    }

    // ---- MS-AI-2/3（盘口）：做市商多档双边报价，形成真实盘口深度 ----
    // 根因（spec §3.1/§3.4）：旧实现只在单 tick 出一档买+一档卖，深度=单档薄壁。现改为多档（3~5档）
    // 双边阶梯报价：距 best 越远量递减（深度随价格分布合理），价差随 spread/深度分层，量按整手(100股)
    // 归整 —— 符合现实交易逻辑。每次激活都按当前 mid 生成全新报价（自然形成「撤单挂重/刷新」生命周期，
    // 旧报价到期由 OrderBook.purgeExpired 清理），双侧同时下量，避免单侧堆积。确定性（assetRng 子流）。
    private List<PriceDiscoveryEngine.OrderFlow> marketMakerQuotes(AgentState agent, double fairValue, double lastPrice,
                                                                   double deviation, double spread, double riskAppetite,
                                                                   double anchor, double buyRatio,
                                                                   boolean herdFollow, double herdBias, SeedableRandom rng) {
        List<PriceDiscoveryEngine.OrderFlow> quotes = new ArrayList<>();
        double mid = lastPrice > 0 ? lastPrice : (anchor > 0 ? anchor : fairValue);
        if (mid <= 0) return quotes;
        // 仅当价格未严重偏离价值时才提供双边流动性（避免给极端定价提供假深度）
        if (deviation <= -0.5 || deviation >= 0.5) return quotes;

        // F2（MS-AI-6 明斯基/金融周期→盘口显式传导）：危机/周期相位映射到盘口价差与深度。
        // 繁荣/顶部：价差收窄（spreadMul<1）、深度增厚（depthMul>1）；收缩/萧条/危机：价差扩大、
        // 深度骤降（明斯基盘口恶化维度）。默认中性=1 不影响基线。确定性、无随机。
        double spreadMul = macroEnv != null ? macroEnv.getMarketSpreadMultiplier() : 1.0;
        double depthMul = macroEnv != null ? macroEnv.getMarketDepthMultiplier() : 1.0;

        // 基础点差：不少于 0.004，随 spread 与风险偏好放大；周期/危机时按 spreadMul 扩大（恐慌→价差放大）
        double baseHalfSpread = Math.max(spread, 0.004) * (0.8 + rng.nextDouble() * 0.4)
                * Math.max(0.5, spreadMul);
        // 档位数（3~5，确定性派生；越靠近 mid 档越密）。危机/收缩压缩档位数（深度恶化维度）
        int tiers = 3 + rng.nextInt(3);
        // 单档基础量（随档位递减），与盘口量级匹配（一手=100股）；危机/收缩按 depthMul 减薄
        double baseQty = 1200 + Math.abs(rng.nextGaussian()) * 600;
        baseQty = Math.max(300, baseQty) * Math.max(0.2, depthMul);
        // 档位跨度在危机/收缩时进一步拉大（price visibility 变差 → 深度恶化伴价差扩大）
        double tierGapScale = (spreadMul > 1.0) ? Math.min(2.0, spreadMul) : 1.0;

        for (int i = 0; i < tiers; i++) {
            // 距 mid 的偏移随档位递增，档位间价差合理（近档密、远档疏但跨度受控制）
            double offset = baseHalfSpread * (1.0 + i * (1.2 + rng.nextDouble() * 0.6) * tierGapScale);
            double bidPrice = round2(mid * (1.0 - offset));
            double askPrice = round2(mid * (1.0 + offset));
            // 买卖价严格不交叉（A股 tick：卖必高于买）
            if (bidPrice >= askPrice) {
                askPrice = Math.max(askPrice, bidPrice + PriceDiscoveryEngine.A_SHARE_TICK_SIZE);
            }
            // 量随档位递减（越远离 best 越薄），并归整为 100 股一手
            double decay = 1.0 - i * 0.16;
            int bidQty = lotRound((int) (baseQty * decay * (0.9 + rng.nextDouble() * 0.2)));
            int askQty = lotRound((int) (baseQty * decay * (0.9 + rng.nextDouble() * 0.2)));
            if (bidQty > 0) quotes.add(createOrder(true, bidPrice, bidQty));
            if (askQty > 0) quotes.add(createOrder(false, askPrice, askQty));
        }
        return quotes;
    }

    /** 归整到 A 股整手（100 股）。 */
    private static int lotRound(int qty) {
        int lot = Math.max(1, PriceDiscoveryEngine.A_SHARE_BOARD_LOT);
        return qty >= lot ? (qty / lot) * lot : lot;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ---- P1-2 前景理论：下单倾向调制乘子（相对参考点 avgCost）----
    // prospectValue(lastPrice-avgCost) → 亏损(负价值)压制下单量、盈利略增；映射到 [~0.5, ~1.4]
    private double prospectPropensity(AgentState agent, double lastPrice) {
        if (agent.avgCost <= 0) return 1.0;
        double gain = (lastPrice - agent.avgCost) / agent.avgCost;
        double v = behaviorUtility.prospectValue(gain);
        return 1.0 + 0.5 * Math.tanh(v);
    }

    /**
     * MS-AI-4 fairValue 安全护栏（安全分母护栏）。
     * fairValue 已由 company.getFairValue()（DCF+PE+PB 每股体系）给出，不再戈登重算。本方法只做护栏：
     * ① 非法/退化值（NaN/Inf/≤0，源自戈登分母≤0 等）→ 回落安全值（BVPS > EPS 理性上限 > 0.01），
     *    杜绝「戈登坍塌到 0.1 地板」；
     * ② 下界护栏：fairValue ≥ 0.5×bookValuePerShare（与 MS-AI-4 验收一致）；
     * ③ 上界护栏：fairValue ≤ max(50×BVPS, 100×EPS) 的宽松上限，防单股估值失控。
     * 纯确定性、无随机。
     */
    private double safeFairValue(AICompanyData company, double fv) {
        double bvps = company.getBookValuePerShare();
        double eps = company.getEps();
        if (Double.isNaN(fv) || Double.isInfinite(fv) || fv <= 0) {
            if (bvps > 0) {
                fv = bvps;
            } else if (eps > 0) {
                fv = eps * 8.0;
            } else {
                fv = company.getIpoPrice() > 0 ? company.getIpoPrice() : 0.01;
            }
        }
        double floor = bvps > 0 ? bvps * 0.5 : 0.01;
        if (fv < floor) fv = floor;
        double cap = bvps > 0 ? bvps * 50.0 : (eps > 0 ? eps * 100.0 : fv * 10.0);
        if (cap < floor) cap = floor;
        if (fv > cap) fv = cap;
        if (fv <= 0) fv = 0.01;
        return fv;
    }

    // ---- P1-2 处置效应：卖出门控（盈利更倾向卖、亏损更倾向持有）；返回 true 表示应持有(跳过卖出)----
    private boolean shouldHoldDueToDisposition(AgentState agent, double lastPrice, SeedableRandom rng) {
        if (agent.avgCost <= 0) return false;
        double gain = (lastPrice - agent.avgCost) / agent.avgCost;
        double sellMult = behaviorUtility.dispositionSellMultiplier(gain);
        double holdProb = 1.0 / sellMult; // 盈利(sellMult>1)→低持有概率→更倾向卖；亏损→高持有概率
        // 价值型基本面敏感度高，按 (1-fundamentalSensitivity) 弱化处置效应，保持更理性
        if (agent.type == AgentType.VALUE) {
            holdProb = 1.0 + (holdProb - 1.0) * (1.0 - agent.fundamentalSensitivity);
        }
        holdProb = Math.max(0.0, Math.min(1.0, holdProb));
        return rng.nextDouble() < holdProb;
    }

    // P1-1/P1-2 参数可配置入口（硬约束 a<0.3 在 HerdingModel 内强制）
    public void setHerdingStrength(double a) { herding.setHerdingStrength(a); }
    public HerdingModel getHerdingModel() { return herding; }
    public BehaviorUtility getBehaviorUtility() { return behaviorUtility; }

    // ================= LIQ P2（LIQUIDITY_SPEC §5，LQ-1/LQ-4）：专职做市商 MM-Agent 公开接线 =================

    /** 对应 liq.mm 子开关（默认 true，随总开关）；liq.enabled=false 时引擎应同步置 false（因子隔离 LQ-10）。 */
    public void setLiquidityMakerEnabled(boolean enabled) {
        this.liquidityMakerEnabled = enabled;
    }

    public boolean isLiquidityMakerEnabled() {
        return liquidityMakerEnabled;
    }

    /**
     * 引擎 liq.enabled 硬门（S1，LQ-10 因子隔离强化）：由 MarketEngine.setLiqEnabled 同步。
     * false → 无论本类 liquidityMakerEnabled/marketMakerAutoFlow 是否被外部置 true，MM 报价流与
     * onMarketMakerFill 全部零执行零随机（本门是 <b>独立硬门</b>，避免任何调用次序/旁路使 liq=OFF
     * 路径偏离交付基线——harness/外部直改另两开关也无法绕过）。默认 true（独立构造/无引擎时 MM 可用）。
     */
    public void setEngineLiqGate(boolean on) {
        this.engineLiqGate = on;
    }

    public boolean isEngineLiqGate() {
        return engineLiqGate;
    }

    /**
     * §5.2 选项开关：true = MM 报价随 stepAgents 的 agentOrders 同批注入（默认）；
     * 若引擎侧改为独立驱动（每 tick 撤旧重挂 quoteFlows 路径），请置 false 避免双份报价。
     */
    public void setMarketMakerAutoFlow(boolean auto) {
        this.marketMakerAutoFlow = auto;
    }

    public boolean isMarketMakerAutoFlow() {
        return marketMakerAutoFlow;
    }

    /**
     * 注入流通股本换算缝（§5.1/§8.2）：{@code MarketEngine.floatSharesOf(assetId)}（AI=万股×10000、
     * 玩家公司=floatShares）。未注入时 MM 用 {@link MarketMakerAgent#FALLBACK_FLOAT_SHARES} 兜底。
     */
    public void setFloatSharesProvider(java.util.function.Function<String, Long> provider) {
        this.floatSharesProvider = provider;
    }

    public boolean hasFloatSharesProvider() {
        return floatSharesProvider != null;
    }

    /** 每资产专职 MM-Agent（惰性创建；确定性子流 seed = mix64(engineSeed ^ ("liq/mm/"+assetId).hashCode())，§7.3）。 */
    public MarketMakerAgent marketMaker(String assetId) {
        return marketMakers.computeIfAbsent(assetId, id -> {
            MarketMakerAgent mm = new MarketMakerAgent(id, engineSeed);
            mm.configure(mmCfgAlpha, mmCfgBeta, mmCfgGammaBase, mmCfgDepthFraction, mmCfgDepthCap,
                    mmCfgInvCapMult, mmCfgFillTicks, mmCfgTiersMin, mmCfgTiersMax);
            return mm;
        });
    }

    /**
     * 校准注入（引擎 CalibrationParams 到货时调用；对已建 MM 立即生效，对后续新建 MM 在创建时生效）。
     * 默认值即 LIQUIDITY_SPEC §5.1/§5.4 先验，无注入也正确。
     */
    public void configureMarketMaker(double spreadAlpha, double spreadBeta, double gammaBase, double depthFraction,
                                     long depthCapShares, double inventoryCapMult, int fillTicks,
                                     int tiersMin, int tiersMax) {
        if (spreadAlpha > 0) mmCfgAlpha = spreadAlpha;
        if (spreadBeta >= 0) mmCfgBeta = spreadBeta;
        if (gammaBase > 0) mmCfgGammaBase = gammaBase;
        if (depthFraction > 0 && depthFraction <= 1.0) mmCfgDepthFraction = depthFraction;
        if (depthCapShares > 0) mmCfgDepthCap = depthCapShares;
        if (inventoryCapMult > 0) mmCfgInvCapMult = inventoryCapMult;
        if (fillTicks > 0) mmCfgFillTicks = fillTicks;
        if (tiersMin >= 1) mmCfgTiersMin = tiersMin;
        if (tiersMax >= tiersMin) mmCfgTiersMax = tiersMax;
        for (MarketMakerAgent mm : marketMakers.values()) {
            mm.configure(mmCfgAlpha, mmCfgBeta, mmCfgGammaBase, mmCfgDepthFraction, mmCfgDepthCap,
                    mmCfgInvCapMult, mmCfgFillTicks, mmCfgTiersMin, mmCfgTiersMax);
        }
    }

    /**
     * 专用出流（§5.2 选项 B：引擎每 tick 撤本 MM 旧报价后调用本方法取整栈新报价，source 由引擎标 "mm"；
     * spreadAbs ≤ 0 时按本 agent 规则自算：spreadAdaptive=true → §5.4 动态价差，否则快照价差）。
     * 不并入 agentOrders（与 auto 注入互斥，见 setMarketMakerAutoFlow）。
     */
    public List<PriceDiscoveryEngine.OrderFlow> obligatedMarketMakerFlows(String assetId, double mid,
                                                                          double spreadAbs, long tick) {
        if (!liquidityMakerEnabled || !engineLiqGate) return java.util.Collections.emptyList();
        List<PriceDiscoveryEngine.OrderFlow> flows =
                marketMaker(assetId).quoteFlows(assetId, mid, spreadAbs, floatSharesOf(assetId), null, tick);
        tagMmFlows(assetId, flows); // LIQ P2（S2）：整栈出流同样打 MM 会话标签
        return flows;
    }

    /** 带簿版本（供引擎在 L2.5b/撮合前调用，使动态价差可用真实最优档量 Vb/Va）。 */
    public List<PriceDiscoveryEngine.OrderFlow> obligatedMarketMakerFlows(String assetId, double mid,
                                                                          double spreadAbs, OrderBook book,
                                                                          long tick) {
        if (!liquidityMakerEnabled || !engineLiqGate) return java.util.Collections.emptyList();
        List<PriceDiscoveryEngine.OrderFlow> flows =
                marketMaker(assetId).quoteFlows(assetId, mid, spreadAbs, floatSharesOf(assetId), book, tick);
        tagMmFlows(assetId, flows); // LIQ P2（S2）
        return flows;
    }

    /**
     * 全资产通用 ramp 出流（引擎每 tick 对任意资产调用，含无 AICompanyData 的玩家公司股票）：
     * mid 由簿快照自取（无双边回落 lastPrice）；与 stepAgents 尾部的同批注入同语义、同存量估计
     * （同一 MM-Agent），不会双份（auto 注入已含该资产时本方法将因存量达标自然补零）。
     */
    public List<PriceDiscoveryEngine.OrderFlow> marketMakerFlowsForAsset(String assetId, OrderBook book,
                                                                         double lastPrice, long tick) {
        if (!liquidityMakerEnabled || !marketMakerAutoFlow || !engineLiqGate) return java.util.Collections.emptyList();
        double mid = MarketMakerAgent.midOf(book, lastPrice);
        if (mid <= 0) return java.util.Collections.emptyList();
        List<PriceDiscoveryEngine.OrderFlow> flows =
                marketMaker(assetId).rampedFlows(assetId, mid, 0.0, floatSharesOf(assetId), book, tick);
        tagMmFlows(assetId, flows); // LIQ P2（S2）：全资产 ramp 出流同样打 MM 会话标签
        return flows;
    }

    /** LIQ P2（S2）：给一组专职 MM 报价流打会话标签（"liq-mm:"+assetId）；空流 no-op。确定性、无随机。 */
    private void tagMmFlows(String assetId, List<PriceDiscoveryEngine.OrderFlow> flows) {
        if (flows == null || flows.isEmpty()) return;
        String tag = marketMaker(assetId).getTag();
        for (PriceDiscoveryEngine.OrderFlow f : flows) {
            if (f != null) f.mmTag = tag;
        }
    }

    /**
     * MM 成交记账入口（LQ-1 存货钩子）：引擎在 MarketEngine.attributeTrade（或 L3/LIQR 记账序列）识别到
     * 本资产 MM 侧成交时调用（signedShares &gt;0 = MM 买、&lt;0 = MM 卖；synthetic，无真实账户）。
     * 本方法幂等、确定性、无随机；liq.mm 关闭时为空操作（不影响基线记账路径）。
     */
    public void onMarketMakerFill(String assetId, int signedShares, double fillPrice) {
        if (!liquidityMakerEnabled || !engineLiqGate) return;
        MarketMakerAgent mm = marketMakers.get(assetId);
        if (mm != null) {
            mm.onFill(signedShares, fillPrice, floatSharesOf(assetId));
        }
    }

    /** MM 侧成交是否应被记账（供引擎钩子判定：liq.mm 开且该资产已建 MM）。 */
    public boolean hasMarketMaker(String assetId) {
        return marketMakers.containsKey(assetId);
    }

    /**
     * 引擎接线入口（§4.6 熔断撤单重挂 cancelAllSynthetic 之后 / 交易日边界调用，void）：
     * 仅清该资产 MM 的<b>报价台账</b>（restingEstimate/emissions/lastQuote*），<b>保留存货 I(t)/avgCost</b>——
     * 引擎清掉本 MM 旧 resting 报价后，本方法让 ramp 台账不误判旧报价在册，下一 tick 即按 5% 目标重 ramp；
     * 已累积存货头寸不因清台失真（LQ-1 存货连续性）。确定性、无随机。
     */
    public void resetMarketMaker(String assetId) {
        MarketMakerAgent mm = marketMakers.get(assetId);
        if (mm != null) mm.resetQuoteLedger();
    }

    // ---------- 持久化（随 MarketEngine.save() 的 "liq" blob；§5.3/§7.2，contains 兜底） ----------

    /** 序列化全部 MM-Agent 状态（inventory/avgCost/rng 等），由引擎并入 "liq" blob。 */
    public net.minecraft.nbt.CompoundTag saveMarketMakers() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        List<String> ids = new ArrayList<>(marketMakers.keySet());
        Collections.sort(ids); // 确定性遍历（审计/校验友好；读回按 assetId 归位）
        for (String id : ids) {
            list.add(marketMakers.get(id).save());
        }
        tag.put("makers", list);
        return tag;
    }

    /** 读档恢复 MM 状态；旧档缺 "makers" 字段 → 空（读档不失败；后续 MM 空仓重建）。 */
    public void loadMarketMakers(net.minecraft.nbt.CompoundTag tag) {
        if (tag == null || !tag.contains("makers", net.minecraft.nbt.Tag.TAG_LIST)) return;
        net.minecraft.nbt.ListTag list = tag.getList("makers", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            net.minecraft.nbt.CompoundTag mt = list.getCompound(i);
            String assetId = mt.getString("assetId");
            if (assetId == null || assetId.isEmpty()) continue;
            marketMaker(assetId).load(mt);
        }
    }

    /** floatSharesOf 换算缝（provider 缺失/非法 → fallback，保证任何资产可出流）。 */
    private long floatSharesOf(String assetId) {
        if (floatSharesProvider == null) return MarketMakerAgent.FALLBACK_FLOAT_SHARES;
        Long fs = floatSharesProvider.apply(assetId);
        return (fs != null && fs > 0) ? fs : MarketMakerAgent.FALLBACK_FLOAT_SHARES;
    }

    private PriceDiscoveryEngine.OrderFlow createOrder(boolean isBuy, double price, int size) {
        PriceDiscoveryEngine.OrderFlow order = new PriceDiscoveryEngine.OrderFlow();
        order.timestamp = 0;
        order.isBuy = isBuy;
        order.price = price;
        order.size = size;
        return order;
    }

    public void calibrateAgents(String assetId, double realReturn, double simulatedReturn, double realVolatility, double simulatedVolatility) {
        // 简化的校准：根据偏差调整风险偏好
        List<AgentState> agents = agentsByAsset.get(assetId);
        if (agents == null) return;
        double returnBias = simulatedReturn - realReturn;
        double volBias = simulatedVolatility - realVolatility;
        for (AgentState agent : agents) {
            if (returnBias > 0) {
                agent.riskTolerance = Math.max(0.1, agent.riskTolerance - 0.01);
                agent.tradeFrequency = Math.max(0.1, agent.tradeFrequency - 0.005);
            } else {
                agent.riskTolerance = Math.min(0.9, agent.riskTolerance + 0.005);
                agent.tradeFrequency = Math.min(0.9, agent.tradeFrequency + 0.003);
            }
            if (volBias > 0) {
                agent.aggressiveness = Math.max(0.1, agent.aggressiveness - 0.005);
            } else {
                agent.aggressiveness = Math.min(0.9, agent.aggressiveness + 0.003);
            }
        }
    }
}
