package com.example.financialmarket.engine.banking;

import com.example.financialmarket.engine.monetary.BaseMoneyFlow;
import com.example.financialmarket.engine.monetary.Bill;
import com.example.financialmarket.engine.monetary.CentralBankBalanceSheet;
import com.example.financialmarket.engine.monetary.LastResortRecord;
import com.example.financialmarket.engine.monetary.LastResortRecord.TroubledBankState;
import com.example.financialmarket.engine.monetary.MonetaryAggregator;
import com.example.financialmarket.engine.monetary.PolicyEvent;
import com.example.financialmarket.engine.macro.MacroEnvironment;
import com.example.financialmarket.engine.role.CentralBankerGuardrail;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 央行（CENTRAL_BANK_SPEC.md v1.5：§1.6 P2/P6/P7/P9、§4 开关组、§6 政策工具、§8.2 改动点）。
 *
 * <p><b>CB-t3 改造要点（本类 = CB-t3 独占写权）</b>
 * <ol>
 *   <li><b>P2 复式记账</b>：{@code baseMoney} 的 <b>7 处业务写入点</b>（{@code setBaseMoney} / {@code injectBaseMoney} /
 *       {@code buyBonds} / {@code sellBonds} / {@code emergencyLending} / {@code reverseRepo} / {@code settleRepos}）
 *       全部改为经 {@link #book(BaseMoneyFlow.Kind, double, String, long)} 落到
 *       {@link CentralBankBalanceSheet#bookBaseMoneyFlow(BaseMoneyFlow)}（唯一改账入口），
 *       存量 {@code "+="} 在 {@code applyBookedScalar} 内<b>恰好 1 处</b>（CB-18② 的写入点唯一性判据）。</li>
 *   <li><b>P6 科目</b>：国外资产（金/汇）与政府存款科目建立；{@code govDeposits} 与
 *       {@code Government.getTreasury()} 为<b>镜像</b>（{@code syncBalanceSheet} 每交易日同步，不双写）。</li>
 *   <li><b>P7 事件通道</b>：每次政策动作产生恰好 1 条 {@link PolicyEvent}（有界环 64，确定性顺序）。</li>
 *   <li><b>P9 最后贷款人条款化</b>：问题银行状态机 + 救助代价四件套（抵押折价/惩罚利率/放贷与分红受限/公开登记）。</li>
 *   <li><b>工具箱</b>：rr / OMO / MLF / SLF / 再贷款 / 再贴现 / LPR / 央票 / 最后贷款人。</li>
 * </ol>
 *
 * <p><b>不回归锚点（§10.1，硬要求）</b>：{@code getBaseMoney()} 与 {@code setBaseMoney(double)} 的
 * <b>签名与数值语义逐位保留</b>（R1：{@code "baseMoney"} 持久化键不可动）；7 处写入点的实数运算
 * （{@code Math.max(0, amount)}、{@code += amount}、{@code -= totalDue}）**逐字不变**，账本侧只做「额外记账」；
 * {@code centralBank.balanceSheet=false} 时账本零执行，逐位复现基线。
 *
 * <p><b>确定性</b>：本类零随机（无 {@code Math.random}/{@code new Random}/{@code ThreadLocalRandom}）、
 * 无时钟读取；全部为确定性查表与和式，同种子同结果。
 */
public class CentralBank {

    /** 初始基础货币（M0）。法币化后 baseMoney 是可被货币政策调节的货币基数，不再是固定实物锚定值。 */
    public static final double DEFAULT_BASE_MONEY = 10_000_000;

    // ===== 政策工具箱参数（§6.2..§6.10；先验默认档，见 §0.10）=====
    /** 走廊半宽（§6.3 {@code CORRIDOR_HALF_WIDTH}）：IOER = policyRate − 0.01、SLF = policyRate + 0.01。 */
    public static final double CORRIDOR_HALF_WIDTH = 0.01;
    /** MLF 加点（§6.5）。 */
    public static final double MLF_SPREAD = 0.005;
    /** 再贷款加点（§6.5，定向工具）。 */
    public static final double RELOAN_SPREAD = 0.01;
    /** 再贴现加点（§6.5）。 */
    public static final double REDISCOUNT_SPREAD = 0.008;
    /** LPR 常态加点（§6.5；policyRate=0.03 ⇒ LPR≈3.9%）。 */
    public static final double LPR_SPREAD_BASE = 0.0090;
    /** LPR 的 MLF 调整项参考余额（§6.5 {@code MLF_REF}）。 */
    public static final double LPR_MLF_REF = 1_000_000;
    /** LPR 下限。 */
    public static final double LPR_MIN = 0.01;
    /** LPR 上限。 */
    public static final double LPR_MAX = 0.30;
    /** OMO 单日投放上限（§6.4）。 */
    public static final double OMO_MAX_DAILY = 2_000_000;
    /** 央票存续上限（§6.8 {@code BILL_MAX_OUTSTANDING}）。 */
    public static final double BILL_MAX_OUTSTANDING = Bill.BILL_MAX_OUTSTANDING;
    /** 政策利率硬域（§6.9，与 {@code MacroEnvironment.RATE_MIN/RATE_MAX} 一致）。 */
    public static final double POLICY_RATE_MIN = MacroEnvironment.RATE_MIN;
    /** 政策利率硬域上界（= {@code MacroEnvironment.RATE_MAX} = 0.20）。 */
    public static final double POLICY_RATE_MAX = MacroEnvironment.RATE_MAX;
    /** 默认政策利率（§0.10.1 先验默认档；= 既有 {@code discountRate} 初值）。 */
    public static final double DEFAULT_POLICY_RATE = 0.03;

    /** 央行行长制度约束（t3/SR-3）：泰勒建议 + 经济后果/问责 + AI 兜底接管。 */
    private final CentralBankerGuardrail guardrail;

    // ===== 玩家央行归属（银行平板 AS-3/BT-3；仅用于展示与权限，不改任何货币公式） =====
    /** 央行拥有者（玩家）；null 表示 AI/系统央行。 */
    private UUID playerOwner = null;
    /** 开设该玩家央行的玩家名（持久化用，非权威，仅展示）。 */
    private String createdBy = null;
    /** 开设该玩家央行时的引擎 tick 计数器（确定性时间戳）。 */
    private long createdTick = 0;

    // ===== 黄金储备账户（下界合金锭模拟现代黄金；本队 Goal 需求①，非印钞） =====
    /** 央行初始黄金储备（下界合金锭数）。 */
    public static final double DEFAULT_GOLD_RESERVE = 200.0;
    /** 央行初始购金专用法币余额（非 baseMoney，不进入 M2）。 */
    public static final double DEFAULT_GOLD_CASH = 2_000_000;
    /** 黄金未定价时的兜底价（= 下界合金锭下界稀有度档）。 */
    public static final double DEFAULT_GOLD_PRICE = 60.0;

    private double goldReserve = DEFAULT_GOLD_RESERVE;        // 央行持有的下界合金锭实物数（黄金储备）
    private double goldCash = DEFAULT_GOLD_CASH;              // 央行购金专用法币余额（非印钞：不改变 baseMoney/M2）
    private double goldHeldCashOutflow = 0.0;                 // 累计购金流出（对账/诊断）
    private double goldMarketBuyAmount = 0.0;                 // 当日净购金（供黄金定价读取，交易日重置）
    private double currentGoldPrice = DEFAULT_GOLD_PRICE;     // 当前黄金价（由 MarketEngine/GoldPricingSystem 每日刷新）

    /** 基础货币标量（一义 M0 = B；7 处业务写入点全部经 {@link #book} ⇒ 账本与标量同步）。 */
    private double baseMoney = DEFAULT_BASE_MONEY;
    private double discountRate = 0.03;
    private double reserveRequirement = 0.10;
    private final List<ReverseRepoOperation> activeRepos = new ArrayList<>();// 法定准备金率
    private final List<GovernmentBond> bonds = new ArrayList<>(); // 持有的国债
    private final Map<String, Double> bankReserves = new HashMap<>(); // 各银行准备金

    // ===== CB-t3：央行资产负债表（§5.2，唯一改账入口在账本内） =====
    /**
     * 央行资产负债表（科目 + 复式记账 + I8/I10 校验）。 */
    private final CentralBankBalanceSheet balanceSheet = new CentralBankBalanceSheet();
    /**
     * 货币分层聚合器（P1 收口用；由 {@link #setMonetaryAggregator} 事后注入）。
     *
     * <p>未注入时 {@link #getM0()} 回落 {@code baseMoney} 旧义（保持基线数值不变）。
     */
    private MonetaryAggregator monetaryAggregator = null;
    /** 最近一次记账使用的引擎 tick（确定性时间戳；{@code 0} = 由 {@link #beginDay(long)} 校正）。 */
    private long lastBookedTick = 0;
    /** 最近一次结算使用的交易日（{@code settleRepos} 写入）。 */
    private long settleDay = 0;

    // ===== CB-t3：政策工具箱状态（§6.2..§6.10） =====
    /** 政策利率（7 天逆回购利率；外生决策，与 {@code discountRate} 同源）。 */
    private double policyRate = DEFAULT_POLICY_RATE;
    /** SLF：走廊上限（外生）。 */
    private double standingLendingFacilityRate = DEFAULT_POLICY_RATE + CORRIDOR_HALF_WIDTH;
    /** IOER：走廊下限（外生，本期不实际付息，§13.1）。 */
    private double interestOnExcessReservesRate = DEFAULT_POLICY_RATE - CORRIDOR_HALF_WIDTH;
    /** 7 天逆回购利率（OMO 报价）= policyRate。 */
    private double omoRate = DEFAULT_POLICY_RATE;
    /** LPR 1Y（内生：policyRate + 加点 + MLF 调整项）。 */
    private double lprOneYear = DEFAULT_POLICY_RATE + LPR_SPREAD_BASE;
    /** 国债收益率（内生，Q5；由持有国债的到期收益率派生）。 */
    private double governmentBondYield = DEFAULT_POLICY_RATE + LPR_SPREAD_BASE;
    /** 加权平均法定准备金率 rr_eff（内生聚合；默认为 §0.10.1 先验档 0.10）。 */
    private double effectiveReserveRequirement = 0.10;
    /** 财政赤字货币化次数（Q6）。 */
    private long fiscalDominanceCount = 0;
    /** ERP 惩罚加项（Q6；每次 +0.005，上限 0.03）。 */
    private double erpFiscalDominancePremium = 0.0;
    /** 财政赤字货币化惩罚系数（§0.10.5 先验默认档）。 */
    public static final double FD_ERP_PENALTY_PER_EVENT = 0.005;
    /** ERP 惩罚上限（§0.10.5）。 */
    public static final double FD_ERP_PENALTY_MAX = 0.03;

    /** OMO 未到期余额（逆回购投放余额）。 */
    private double omoOutstanding = 0;
    /** MLF 未到期余额（§6.5）。 */
    private double mlfOutstanding = 0;
    /** 定向再贷款未到期余额（§6.5）。 */
    private double reloanOutstanding = 0;
    /** 再贴现未到期余额（§6.5）。 */
    private double rediscountOutstanding = 0;
    /** SLF 借出未到期余额（§6.6）。 */
    private double slfOutstanding = 0;
    /** 央票存续列表（§6.8；确定性顺序 = 发行序）。 */
    private final List<Bill> billsOutstanding = new ArrayList<>();
    /** 央票发行流水号（确定性命名，禁用随机 UUID）。 */
    private long billSeq = 0;

    // ===== CB-t3：政策事件通道（P7；有界环 64，确定性顺序） =====
    /** 事件环（§9.5 {@code POLICY_EVENT_RING} = 64；满环丢最旧 ⇒ 只保留最近 64 条）。 */
    private final List<PolicyEvent> policyEvents = new ArrayList<>(PolicyEvent.RING_CAPACITY);
    /** 最近一次政策动作（kind 字符串，§3.4）。 */
    private String lastPolicyAction = "";
    /** 最近一次政策动作的 tick。 */
    private long lastPolicyActionTick = 0;
    /** 最近一次政策动作的幅度（与 {@link PolicyEvent#delta()} 同口径）。 */
    private double lastPolicyActionDelta = 0;
    /** 政策决策来源（{@code PLAYER}/{@code AI_AUTOPILOT}/{@code GUARDRAIL_CLAMP}/ {@code "CLOSED"}）。 */
    private String policyDecisionSource = "CLOSED";
    /** 本次运行累计政策动作数（诊断）。 */
    private long policyActionCount = 0;
    /** 最近一次被制度约束钳制的留痕说明（空串 = 未钳制）。 */
    private String lastGuardrailClampNote = "";

    // ===== CB-t3：接管/交还（Q1；CB-21 可逆 + 事件日志） =====
    /** 玩家是否已接管（AI 暂停）；由 {@link #setManualTakeover} 可逆切换，<b>不用一次性静态标志</b>。 */
    private boolean manualTakeoverActive = false;
    /** 接管/交还次数（可逆性证据）。 */
    private long takeoverTransitionCount = 0;

    // ===== CB-t3：最后贷款人 + 问题银行状态机（P9；§6.10） =====
    /** 问题银行状态表（确定性插入序 = 首次评估序）。 */
    private final Map<String, TroubledBankState> troubledBankStates = new LinkedHashMap<>();
    /** 连续处于 {@code RESCUED} 且救助后 {@code ER < 0} 的交易日数（→ {@code RESTRICTED}）。 */
    private final Map<String, Integer> rescuedStreak = new LinkedHashMap<>();
    /** 公开救助登记环（§6.10.2④，上限 64）。 */
    private final List<LastResortRecord> lastResortRecords = new ArrayList<>(LastResortRecord.LLR_RECORD_RING);
    /** 每行累计救助次数（§6.10.2 上限 3 / 年）。 */
    private final Map<String, Integer> lastResortCountByBank = new LinkedHashMap<>();
    /** 每行降杠杆截止交易日（§6.10.2，救助后 30 交易日）。 */
    private final Map<String, Long> deleveragingDeadlineByBank = new LinkedHashMap<>();
    /** 最近一次救助额度（诊断）。 */
    private double lastResortLendingOutstanding = 0;
    /** 问题银行状态转移次数（CB-23①）。 */
    private long troubledStateTransitions = 0;
    /** 每行最近一次已知 nplRatio（由 {@link #evaluateTroubledBanks} 从银行只读取回并缓存；诊断/迁移用）。 */
    private final Map<String, Double> nplRatioCache = new LinkedHashMap<>();

    /** 诊断输入：全 AI 公司 fairValue 相对基线的均值变化（CB-12④；由 t5 每交易日注入）。 */
    private double meanFairValueDelta = 0.0;

    /**
     * 银行侧接缝是否已接线（{@link #attachBankManager}）。未接线时银行侧不发射政策流动（零影响）。
     */
    private boolean bankSideWired = false;

    /**
     * ★CB-t2 现金转换接缝（§6.1；bank-dev 交接，2026-09-12）。
     *
     * <p>银行侧（{@code Bank} 的存款/取款/准备金再平衡路径）会按类型发射
     * {@code NONBANK_CASH_DEPOSIT}、{@code NONBANK_CASH_WITHDRAW}、{@code BANK_CASH_DEPOSIT} 等流动
     * （kind/counterpartyId/tick 已由银行侧填好），本方法把同一个银行实例接上央行账本入口
     * （{@code new BaseMoneyFlow(kind, amount, counterpartyId, tick)} 再次成对记账
     * 即在账本侧落地「C/Vc/ER 项间转移」）。
     *
     * <p>调用方一次性接好后即可生效（未注入时银行侧零影响 ⇒ 不破坏既有行为）。
     *
     * @param bankManager 银行体系（若为 null 则只接 sink 语义，仍返回 true）
     * @return true = 已接线
     */
    public boolean attachBankManager(BankManager bankManager) {
        if (bankManager == null) return false;
        attachBankManagerSinksOnly(bankManager);
        // 逐行集成一次（rr / LPR / 问题银行状态）
        integrateBanks(bankManager);
        return true;
    }

    /**
     * **窄口径接线（只接现金转换 sink + 聚合器，不动 rr）**：供"需要在 day-0 标定前先听到银行侧流水"
     * 的调用方使用（t5 装配期推荐用它，再按 §8.3③ 在日级结算时统一下发 rr）。
     *
     * <p><b>为何要分窄/宽两种</b>（实测口径，登记给 t5/t7/t8）：
     * {@link #attachBankManager} 内含 {@code integrateBanks} ⇒ 会把央行 rr 下发给各行并触发
     * {@code Bank.recomputeReserveDecomposition()}，从而<b>改变 day-0 的 I1 右侧构成</b>
     * （实测：纯新档 {@code Vc=4,050,000/RR=0/ER=0} ⇒ 下发 rr 后 {@code Vc=4,050,000/RR=0/ER=450,000}，
     * 即 {@code getBaseMoneyB()} 由 <b>4,050,000</b> 变为 <b>4,500,000</b>）。
     * ⇒ **测 day-0 权威值时必须声明用的是哪一档**，否则会得到两个都能自称"B"的数。
     *
     * @param bankManager 银行体系
     * @return 至少接上一行 sink 即返回 true
     */
    public boolean attachBankManagerSinksOnly(BankManager bankManager) {
        if (bankManager == null) return false;
        boolean any = false;
        for (Bank bank : bankManager.getAllBanks()) {
            any |= wireBank(bank);
        }
        // P1 收口：顺带注入该 BankManager 持有的聚合器（使 getM0() 委托到流通现金）
        setMonetaryAggregator(bankManager.getMonetaryAggregator());
        bankSideWired = any;
        return true;
    }

    /**
     * 单行接线：注入现金转换 sink（按 §4 开关门控）+ 与 {@code centralBank.balanceSheet} 同源的记账开关，
     * 并同步 rr / LPR / 问题银行状态（t2 已交付的四个入口）。
     *
     * <p>反射调用：t2 的这 5 个入口若尚未就绪则<b>静默跳过该行</b>（单行降级、不影响其余行），
     * 降级情形由 {@link #isBankSideWired()} 与 {@link #isReserveSyncDegraded()} 可观测。
     */
    private boolean wireBank(Bank bank) {
        if (bank == null) return false;
        boolean wired = true;
        // ① 现金转换接缝：Consumer<BaseMoneyFlow> ← 央行账本入口（唯一改账入口的银行侧适配）
        wired &= invokeVoid(bank, "setBaseMoneyFlowSink", new Class<?>[]{java.util.function.Consumer.class},
                new Object[]{(java.util.function.Consumer<BaseMoneyFlow>) flow -> {
                    if (flow != null && isBalanceSheetEffective()) {
                        balanceSheet.bookBaseMoneyFlow(flow);
                    }
                }});
        // ② 记账开关与 centralBank.balanceSheet 同源（§4 因子隔离：关掉 ⇒ 银行侧不发射）
        invokeVoid(bank, "setCashFlowBookingEnabled", new Class<?>[]{boolean.class},
                new Object[]{isBalanceSheetEffective()});
        return wired;
    }

    /**
     * 逐行集成（rr / LPR / 问题银行状态）：把央行侧权威值推送给银行侧。
     *
     * <p>对应 bank-dev 交接的 4 个入口（{@code setReserveRequirementEffective}/{@code setLprOneYear}/
     * {@code setTroubledState}+{@code getLendingCapMultiplier}/{@code isDividendBlocked}）。
     * 反射调用 + 缺失即跳过（不静默改数）。
     *
     * @param bankManager 银行体系
     * @return 成功集成的行数
     */
    public int integrateBanks(BankManager bankManager) {
        if (bankManager == null) return 0;
        int count = 0;
        for (Bank bank : bankManager.getAllBanks()) {
            boolean ok = true;
            // ① rr_eff（P3）：本行 RR_i 由央行权威值重算（Bank.reserveRequirement 是 final ⇒ 走该入口）
            ok &= invokeVoid(bank, "setReserveRequirementEffective", new Class<?>[]{double.class},
                    new Object[]{getEffectiveReserveRequirement()});
            // ② LPR（§6.5）：注入后该行放贷走需求函数（未注入时 t2 回落固定 50000 ⇒ 由 t5 门控）
            ok &= invokeVoid(bank, "setLprOneYear", new Class<?>[]{double.class}, new Object[]{lprOneYear});
            // ③ 问题银行状态（§6.10.1）：自动带出 getLendingCapMultiplier()/isDividendBlocked()
            TroubledBankState state = getTroubledBankState(bank.getBankId());
            ok &= invokeVoid(bank, "setTroubledState", new Class<?>[]{TroubledBankState.class}, new Object[]{state});
            if (ok) count++;
        }
        return count;
    }

    /** 银行侧接缝是否已接线（{@link #attachBankManager} 成功后为 true）。 */
    public boolean isBankSideWired() { return bankSideWired; }

    /**
     * 银行侧接缝的开关随动（§4 因子隔离）：{@code centralBank.balanceSheet} 变化时同步到各行。
     *
     * @return 同步到的行数
     */
    public int syncBankSideSwitches(BankManager bankManager) {
        if (bankManager == null) return 0;
        int count = 0;
        for (Bank bank : bankManager.getAllBanks()) {
            boolean ok = invokeVoid(bank, "setCashFlowBookingEnabled", new Class<?>[]{boolean.class},
                    new Object[]{isBalanceSheetEffective()});
            if (ok) count++;
        }
        return count;
    }

    // ===== 账本右式与聚合器实测口径的镜像（I10 对账）=====

    /**
     * 把账本的 I1 右侧分项镜像为聚合器实测值（{@code C/Vc/RR/ER/Pay}）。
     *
     * <p>用途：账本对分项的逐笔维护是"央行侧口径"，而权威值是聚合器从<b>玩家现金与各银行真实账本</b>
     * 读出的实测值。两者不一致时 I10 必须以<b>实测值</b>为准 —— 本方法把实测值同步进账本，
     * 使 I10 的判据落在真实账本上（而非央行侧自算）。
     *
     * <p><b>不改</b> {@code reserveMoney}（= 标量镜像），因此"实测右式 − 标量"的差会如实暴露为 I10 偏差，
     * 由 {@link #reconcileMigrationDivergence} 用配平流动归零（§9.1④）。
     *
     * <p><b>★t30 登记（未接线的具名开口，务必如实转述，不得含糊为"已接线"）</b>：
     * 本方法在 {@code src/main} 生产路径上<b>当前没有任何调用者</b>（唯一调用者是验证装置
     * {@code .verify/harness/CbVerifyHarness.java} 的 CB-3 关系式②/②′ 段，按规格声明显式调用）。因此：
     * <ul>
     *   <li>生产运行中 I1 右侧分项<b>不会</b>被聚合器实测值覆盖，账本侧只保留自身口径
     *       （由 {@code bookBaseMoneyFlow} 逐笔维护）⇒ {@code getLedgerVsComponentsGap()} 与
     *       {@code getLegacyIssuanceEncapsulation()} 读到的是<b>账本自身口径</b>的差额，
     *       <b>不是</b>聚合器口径的 5,500,000（后者须先经本方法落定右侧分项）；</li>
     *   <li>该"未接线"是<b>登记在案的开口</b>，不是 t30 的修复目标：把本方法接进引擎刷新循环会改变
     *       I10 相邻的观测量，必须作为<b>独立接线任务</b>评估，<b>禁止</b>与"只读诊断修复"捆绑落地；</li>
     *   <li>方法本身<b>保留</b>（它是声明式接线点，删除会破坏 CB-3 关系式②的取证路径）。</li>
     * </ul>
     *
     * @param ma 已 {@code refresh} 过的聚合器（null 时零执行）
     * @return true = 已镜像
     */
    public boolean syncRightSideFromAggregator(com.example.financialmarket.engine.monetary.MonetaryAggregator ma) {
        if (ma == null || !isBalanceSheetEffective()) return false;
        balanceSheet.syncRightSideComponents(ma.getCirculationCash(), ma.getVaultCashTotal(),
                ma.getRequiredReservesTotal(), ma.getExcessReservesTotal(), ma.getPaymentInstitutionDeposits());
        // ★F-CB-2/T3-D2 口径（captain 裁定）：**不**把"大数相减"的 day-0 历史差额写入偏差账户 ——
        // 该差额是 §13 具名例外「历史发行封装」，只由 `getLedgerVsComponentsGap()` 暴露为**只读诊断**；
        // I10 的判据是"未配对流动净额"（本类 divergence 计数器）。
        // 旧实现曾在此把账本偏差置为该差额（`setDivergenceOverride`），已按裁决移除：
        // 那会使聚合器把历史差额记成"未配对流动"，并掩盖真实的未配对注入。
        return true;
    }

    /**
     * 旧档/新档迁移的 I10 配平（§9.1④）：把「系统记账的 B」与「I1 右侧实测之和」的差用
     * <b>配平流动</b>归零，使 {@code getDivergenceAccount()==0}（CB-2/CB-3/CB-4 的"正常态"判据）。
     *
     * <p>记账两侧同时落：① 央行账本经 {@code bookBaseMoneyFlow} 把配平流动落到两侧科目（并把偏差归零）；
     * ② 聚合器的偏差账户经 {@code ma.absorb(同一笔配平流动)} 归零（调用配方见
     * `.verify/CB_INTERFACE_FREEZE.md` §8.2）。⇒ "偏差归零"与"账目配平"是同一次操作的两面（§6.1 I10 段）。
     *
     * <p><b>偏差来源</b>：优先取聚合器口径（{@link #syncRightSideFromAggregator} 注入的权威值），
     * 未注入时取账本自身口径。
     *
     * <p><b>★v1.6c 边界（captain 裁定 = 方案 (B)，务必遵守）</b>：本方法 **只用于"未配对流动"的机械配平**；
     * **不得用于吸收已登记为 day-0「历史发行封装」的差额**（见
     * {@link #getLegacyIssuanceEncapsulation()}）—— 那是**历史既成事实**，规格明示
     * "差额显式登记、**不得用 `CANCEL`/`WRITE_OFF` 吸收**"，且 **I10 正常态仍须为 0、该例外不影响 I10 判据**。
     * ⇒ 即"机械配平 ≠ 账实相符"：本方法能令 {@code divergenceAccount → 0}，
     * 但<b>不会</b>让 {@code computeM0()} 与 {@code getBaseMoneyB()} 相等（那由方案 (B) 具名例外承接）。
     *
     * <p><b>★默认调用路径（captain 裁定 ④，务必遵守）</b>：本方法的<b>唯一合法用途</b>是配平
     * **未配对净额**（{@code divergenceAccount}），使 {@code divergenceAccount == 0}；
     * **历史差额（`computeM0() − getBaseMoneyB()`，本处 5,950,000 = ★档①「新档·未装配」中间档；
     * ★权威档② = 5,500,000，档位与取数时点见规格 §8.3）走 §13 具名例外「历史发行封装」，
     * 不得由本方法吸收** —— 它是历史既成事实，不是"未配对流动"。
     * 即：机械配平 ≠ 账实相符；本方法<b>不会</b>让 `computeM0()` 与 `getBaseMoneyB()` 相等。
     *
     * @param ma  已 refresh 过的聚合器（null 时只做账本侧）
     * @param day 交易日（写进 flow 的 tick 字段作确定性时间戳）
     * @return 归零的偏差量（0 = 本已归零）
     */
    public double reconcileMigrationDivergence(com.example.financialmarket.engine.monetary.MonetaryAggregator ma,
                                               long day) {
        if (!isBalanceSheetEffective()) return 0;
        double divergence = balanceSheet.getDivergenceAmount();
        if (Math.abs(divergence) <= com.example.financialmarket.engine.monetary.CentralBankBalanceSheet.EPS) {
            return 0;
        }
        double amount = Math.abs(divergence);
        BaseMoneyFlow.Kind kind = BaseMoneyFlow.Kind.CANCEL;
        balanceSheet.bookBaseMoneyFlow(new BaseMoneyFlow(kind, amount, "MIGRATION", day));
        balanceSheet.markDivergenceReconciled();
        if (ma != null) {
            ma.absorb(new BaseMoneyFlow(kind, amount, "MIGRATION", day));
        }
        recordPolicyEvent(PolicyEvent.MIGRATION, amount, policySourceOrClosed());
        migrationBalancingAmount = amount;
        return amount;
    }

    // ===== CB-t3：开关组（§4；由 CB-t5 的 MarketEngine 传播；默认 true 与规格一致） =====
    /** 央行总开关（false ⇒ 全部新行为零执行、逐位复现基线）。 */
    private boolean centralBankEnabled = true;
    /** 资产负债表科目与复式记账开关。 */
    private boolean balanceSheetEnabled = true;
    /** 最后贷款人条款化开关。 */
    private boolean lastResortEnabled = true;
    /** 政策事件通道开关。 */
    private boolean eventsEnabled = true;
    /** 财政赤字货币化开关（Q6：<b>默认 false</b>）。 */
    private boolean fiscalDominanceEnabled = false;
    /** ★CB-T26 数字人民币开关（§4 {@code centralBank.cbdc}；默认 true 同 §0.10 默认档）。 */
    private boolean cbdcEnabled = true;
    /**
     * 账本侧数额比较容差（CBDC 差额法用；与 {@code CentralBankBalanceSheet.EPS} 同值，仅作比较不下判据）。
     *
     * <p>登记：本常量<b>不用于任何恒等式判据</b>（判据容差仍由各恒等式自身持有，逐位未改）。
     */
    private static final double EPS_LEDGER = 1e-6;

    public CentralBank() {
        this.baseMoney = DEFAULT_BASE_MONEY; // 初始基础货币（由货币政策控制，非实物锚定）
        this.discountRate = 0.03;
        this.reserveRequirement = 0.10;
        this.guardrail = new CentralBankerGuardrail();
        // §9.1①③：初始标量 baseMoney 落到 reserveMoney，差额进 ownCapital（使 I8 自第一笔起即成立）
        this.balanceSheet.migrateFromLegacyScalar(DEFAULT_BASE_MONEY);
    }

    // ============================================================
    // §4 开关组（由 MarketEngine 传播；false ⇒ 零执行、逐位复现基线）
    // ============================================================

    /** 央行总开关（传播到全部子开关）。 */
    public void setEnabled(boolean enabled) {
        this.centralBankEnabled = enabled;
        this.balanceSheetEnabled = enabled;
        this.lastResortEnabled = enabled;
        this.eventsEnabled = enabled;
        if (!enabled) this.fiscalDominanceEnabled = false; // 总关闭时财政赤字货币化必然不可用（Q6 默认关）
    }

    /** 央行总开关状态。 */
    public boolean isEnabled() { return centralBankEnabled; }

    /** 资产负债表科目与复式记账开关（§4 {@code centralBank.balanceSheet}）。 */
    public void setBalanceSheetEnabled(boolean enabled) { this.balanceSheetEnabled = enabled; }

    /** 资产负债表记账是否生效（总开关 ∧ 子开关）。 */
    public boolean isBalanceSheetEffective() { return centralBankEnabled && balanceSheetEnabled; }

    /** 最后贷款人条款化开关（§4 {@code centralBank.lastResort}）。 */
    public void setLastResortEnabled(boolean enabled) { this.lastResortEnabled = enabled; }

    /** 最后贷款人条款是否生效（总开关 ∧ 子开关）。 */
    public boolean isLastResortEffective() { return centralBankEnabled && lastResortEnabled; }

    /** 政策事件通道开关（§4 {@code centralBank.events}）。 */
    public void setEventsEnabled(boolean enabled) { this.eventsEnabled = enabled; }

    /** 政策事件通道是否生效（总开关 ∧ 子开关）。 */
    public boolean isEventsEffective() { return centralBankEnabled && eventsEnabled; }

    /** 财政赤字货币化开关（§4 {@code centralBank.fiscalDominance}；<b>默认 false</b>，Q6 冻结）。 */
    public void setFiscalDominanceEnabled(boolean enabled) {
        this.fiscalDominanceEnabled = centralBankEnabled && enabled;
    }

    /** 财政赤字货币化是否允许（Q6）。 */
    public boolean isFiscalDominanceEnabled() { return fiscalDominanceEnabled; }

    // ============================================================
    // 复式记账原语（§5.2/§6.1；唯一改账入口的央行侧调用点）
    // ============================================================

    /**
     * 记一笔基础货币流动（<b>央行侧唯一改账入口</b>）。
     *
     * <p>行为：①按 {@code kind} 折算标量增量（±amount / 0）并<b>原地</b>更新 {@code baseMoney}
     * （算术逐字保留既有语义）；②门控开时把同一笔交 {@link CentralBankBalanceSheet#bookBaseMoneyFlow} 落到两侧科目。
     *
     * @param kind         流动类别
     * @param amount       流动额度（&gt; 0）
     * @param counterparty 对手方（银行 ID / {@code "GOVERNMENT"} / {@code "PUBLIC"}；可为 null）
     * @param tick         引擎 tick（确定性时间戳）
     * @return {@code true} = **已进入记账路径**（入参合法）。⚠ 契约澄清（CB-T43 / O-1）：标量
     *         {@code baseMoney} 按**基线语义**在 {@code isBalanceSheetEffective()} 判断**之前**落账
     *         （N1 基线要求，行为不变）；而**账本侧仅在** {@code isBalanceSheetEffective()} 为真时才落账
     *         ⇒ **关断态下本方法仍返回 {@code true}，但账本并未落账**。即返回值
     *         <b>不表示"账本已记账"</b>，也不表示"目标额已达成"。
     */
    public boolean book(BaseMoneyFlow.Kind kind, double amount, String counterparty, long tick) {
        if (kind == null || !(amount > 0) || Double.isNaN(amount)) return false;
        boolean enabled = isBalanceSheetEffective();
        BaseMoneyFlow.ScalarEffect effect = kind.book().scalarEffect();
        double delta = 0;
        if (effect == BaseMoneyFlow.ScalarEffect.CHANGES_SCALAR) {
            delta = kind.bookedAmount(amount);
        }
        applyBookedScalar(kind, amount, delta);
        if (enabled) {
            balanceSheet.bookBaseMoneyFlow(new BaseMoneyFlow(kind, amount, counterparty, tick));
        }
        lastBookedTick = tick;
        return true;
    }

    /**
     * 标量落账（<b>{@code baseMoney} 的唯一写入点</b>，CB-18② 的 grep 判据）。
     *
     * <p>算术语义与基线逐字一致：
     * <ul>
     *   <li>{@link BaseMoneyFlow.Kind#INJECT_CASH} —— 等价既有 {@code injectBaseMoney} 的 {@code "+="}（正数才加）；</li>
     *   <li>{@code OMO_REPO}（+）与 {@code GOV_BOND_BUY}（+） —— {@code "+="}；</li>
     *   <li>{@code OMO_REPO_MATURITY} / {@code GOV_BOND_SELL}（−） —— {@code "-="}（基线不钳 0）；</li>
     *   <li>{@code delta == 0} —— 零执行（I1 内部转移 / 负债侧项间 / 只写账本类 ⇒ 标量不变）。</li>
     * </ul>
     * {@code setBaseMoney} 的「钳 0 赋值」不走本方法（见 {@code setBaseMoney} 内的就地实现），
     * 以免把一次性赋值语义混进流动语义。
     *
     * @param kind  流动类别
     * @param amount 流动额度
     * @param delta  标量增量（0 ⇒ 标量不变）
     */
    private void applyBookedScalar(BaseMoneyFlow.Kind kind, double amount, double delta) {
        switch (kind) {
            case INJECT_CASH -> {
                if (amount > 0) this.baseMoney += amount;
            }
            default -> {
                if (delta != 0) this.baseMoney += delta;
            }
        }
    }

    // ============================================================
    // ★CB-T26 数字人民币（CBDC）配对记账（§11 CB-15 有效变体：ΔM2 = 0）
    // ============================================================

    /**
     * 数字人民币（CBDC）开关（§4 因子 {@code centralBank.cbdc}）。
     *
     * <p><b>语义（登记）</b>：
     * <ul>
     *   <li>{@code true}（默认，同 §0.10 默认档）：允许经 {@link #issueCbdc} / {@link #redeemCbdc}
     *       投放与回笼 CBDC；</li>
     *   <li>{@code false}：<b>拒绝新的投放</b>（{@link #issueCbdc} 返回 0、账面零变化），
     *       且调用方应先把存量回笼为 0（{@link #redeemAllCbdc()}）；此时 {@code CbDc == 0}
     *       且聚合器不计入 {@code C}（{@code C = Cnote}，与基线逐位一致）。</li>
     * </ul>
     *
     * <p>默认 {@code true} 与"未接线"不冲突：CBDC 存量的默认值为 0，故开与不开在
     * <b>不主动投放</b>时对既有数值链<b>零影响</b>。
     */
    public void setCbdcEnabled(boolean enabled) { this.cbdcEnabled = enabled; }

    /** CBDC 开关是否允许投放/回笼。 */
    public boolean isCbdcEnabled() { return cbdcEnabled; }

    /**
     * ★CBDC 投放（<b>唯一</b>路径）：把 {@code amount} 实体现金替代为等额数字人民币。
     *
     * <p><b>记账（成对，走唯一改账入口 {@link #book}）</b>：
     * {@code CBDC_ISSUE} ⇒ 央行负债 {@code cbdcIssued += a}、{@code Cnote -= a}；
     * {@code C = Cnote + CbDc} 不变 ⇒ {@code B}/{@code M2}/{@code reserveMoney} 全不变，
     * 自有资金不动（I8），不产生 I10 偏差。
     *
     * <p>⇒ 这是<b>构成替代</b>，不是"投放基础货币"；{@code ΔM2 = 0} 是本任务的硬判据（CB-15 有效变体）。
     *
     * @param amount       额度（&gt; 0）
     * @param counterparty 对手方（{@code "PUBLIC"} / 银行 ID / {@code null}）
     * @return true = 已记账；false = 被拒绝（开关关闭 / 额度非法 / 账本门控关闭）
     */
    public boolean issueCbdc(double amount, String counterparty) {
        if (!cbdcEnabled || !isBalanceSheetEffective()) return false;
        return book(BaseMoneyFlow.Kind.CBDC_ISSUE, amount, counterparty, lastBookedTick);
    }

    /**
     * ★CBDC 回笼（<b>唯一</b>路径）：把 {@code amount} 数字人民币换回实体现金（与投放严格反向）。
     *
     * @param amount       额度（&gt; 0）
     * @param counterparty 对手方
     * @return true = 已记账；false = 被拒绝（额度非法 / 超出存量 / 账本门控关闭）
     */
    public boolean redeemCbdc(double amount, String counterparty) {
        if (!isBalanceSheetEffective() || !(amount > 0)) return false;
        if (amount - balanceSheet.getCbdcIssued() > EPS_LEDGER) return false;   // 不得回笼超过存量
        return book(BaseMoneyFlow.Kind.CBDC_REDEEM, amount, counterparty, lastBookedTick);
    }

    /** 回笼全部存量 CBDC（开关关闭前清账用）；返回实际回笼额。 */
    public double redeemAllCbdc() {
        double outstanding = balanceSheet.getCbdcIssued();
        if (!(outstanding > 0)) return 0.0;
        return redeemCbdc(outstanding, "PUBLIC_TRANSITION") ? outstanding : 0.0;
    }

    /**
     * ★旧接口的**单一入口收口**（CB-T26 根除"绕过记账"通道）。
     *
     * <p><b>旧行为（缺陷，t8 实测）</b>：只把 {@code d} 加进 {@code circulationCash} ⇒
     * {@code C} 与 {@code B} 上升、账本侧无任何配对记账（{@code reserveMoney} 不动、
     * 无 CBDC 相关 Kind）⇒ 实测 {@code ΔM2 = +250,000}＝<b>凭空造钱</b>，
     * 且该 setter 是全仓唯一能绕过 {@link #book} 的通道。
     *
     * <p><b>新行为</b>：本方法<b>不再直接改任何数字</b>，而是把"设定目标存量"翻译为
     * 经唯一入口的 {@code CBDC_ISSUE}/{@code CBDC_REDEEM} 净额（差额法）：
     * <ul>
     *   <li>{@code target > 存量} ⇒ 投放差额；{@code target < 存量} ⇒ 回笼差额；</li>
     *   <li>开关关闭 ⇒ 拒绝投放（并如实返回 false），存量只能减少；</li>
     *   <li>账本门控关闭或未注入 ⇒ false（不静默改数）。</li>
     * </ul>
     *
     * @param target 目标 CBDC 存量（{@code ≤ 0} 视为 0 ⇒ 全部回笼）
     * @return true = 目标已达成（含"差额为 0"的幂等情形）
     */
    public boolean setCbdcOutstanding(double target) {
        double want = (Double.isFinite(target) && target > 0) ? target : 0.0;
        double current = balanceSheet.getCbdcIssued();
        double delta = want - current;
        if (Math.abs(delta) <= EPS_LEDGER) return true;          // 幂等
        if (delta > 0) return issueCbdc(delta, "PUBLIC");
        return redeemCbdc(-delta, "PUBLIC");
    }

    /** CBDC 存量（央行负债侧；t8 的 `cbdc` 报表键来源）。 */
    public double getCbdcOutstanding() { return balanceSheet.getCbdcIssued(); }

    /**
     * CBDC 的 Cnote 修正量（账本侧；负值 = 已被数字人民币替代掉的实体现金）。
     *
     * <p>聚合器据此把 {@code Cnote} 扣除该量，使 {@code C = Cnote + CbDc} 总量恒定（{@code ΔM2 = 0}）。
     */
    public double getCbdcAdjustment() { return balanceSheet.getCbdcAdjustment(); }

    /**
     * 实体现金**镜像原始值**（供应器口径，未扣除 CBDC 替代量）。
     *
     * <p>聚合器据「原始值 − 净投放」派生 Cnote，与账本口径**同源**（避免二次扣减）。
     */
    public double getNoteCashMirror() { return balanceSheet.getNoteCashMirror(); }

    /** CBDC 净投放累计（诊断）。 */
    public double getCbdcNetIssued() { return balanceSheet.getCbdcNetIssued(); }

    /** CBDC 记账笔数（诊断：证明投放/回笼确实走了唯一入口）。 */
    public long getCbdcFlowCount() { return balanceSheet.getCbdcFlowCount(); }

    /** CBDC 是否计入流通中现金（= 开关开 ∧ 账本门控开 ∧ 存量 > 0）。 */
    public boolean isCbdcCountedInCirculation() {
        return cbdcEnabled && isBalanceSheetEffective() && balanceSheet.getCbdcIssued() > 0;
    }

    /** 账本侧 C：流通中现金 = Cnote（账本）+ CbDc（负债）。 */
    public double getCirculationCashLedger() { return balanceSheet.getCirculationCashAccount(); }

    /** CBDC 成对记账自洽性（I8/I10 不因 CBDC 而破；CB-T26 逐笔判据）。 */
    public boolean isCbdcPairingConsistent() { return balanceSheet.isCbdcPairingConsistent(); }

    // Getters & Setters
    public double getBaseMoney() { return baseMoney; }
    public double getDiscountRate() { return discountRate; }
    public double getReserveRequirement() { return reserveRequirement; }

    /**
     * M0 的**一义口径出口**：{@code M0 = 流通中现金（C）}（§1.9(a)/CB-17 的 P1 修复收口）。
     *
     * <p><b>★语义分层（登记，避免"一名两义"复活）</b>：
     * <ul>
     *   <li>{@link #getBaseMoney()} = **标量旧兼容口径**（§13.8：数值逐位不变）；
     *       {@link #getBaseMoneyB()} = **账本侧储备货币镜像**（{@code outstandingB}，I8 负债侧）；
     *       **权威 B（I1 右侧之和）在聚合器侧** {@code MonetaryAggregator.getBaseMoneyB()} —— 判据用它；</li>
     *   <li><b>本方法 = M0 = 流通中现金 C</b>（非银行部门持有的现金，不含准备金）；</li>
     *   <li>基线语义"返回 {@code baseMoney}"的兼容出口保留在 {@link BankManager#computeM0()}
     *       （§13.8：其数值逐位不变）。</li>
     * </ul>
     *
     * <p><b>取值规则（确定性、无随机）</b>：聚合器已注入且账本门控开启
     * （{@link #isM0Delegated()}）⇒ 返回 {@code MonetaryAggregator.getM0()}（= C，权威）；
     * 否则回落 {@code baseMoney}（未接线时的安全兜底，保证旧调用方不出现意外 0）。
     *
     * <p><b>爆炸半径（实测，非估算）</b>：全仓对本方法的真实调用点<b>为零</b>
     * （`grep getM0()` 命中：本类定义 1 处 + `MonetaryAggregator` 定义 1 处 + `BankManager:223`
     * 的 {@code monetaryAggregator.getM0()} —— 后者是<b>聚合器</b>的同名方法，不经本类）
     * ⇒ 本语义修正在当前树上<b>不可能改变任何既有数值链</b>（M2/通胀/CAPM/A 股/黄金全部不受影响）。
     */
    public double getM0() {
        if (isM0Delegated()) return monetaryAggregator.getM0();
        return baseMoney;
    }

    /** 央行行长制度约束实例（t3/SR-3），供交易/验证读取建议、问责与 AI 接管状态。 */
    public CentralBankerGuardrail getGuardrail() { return guardrail; }

    /** 供引擎 tick / 验证驱动更新央行约束的观测输入（通胀、经济周期健康）。 */
    public void updateGuardrailInputs(double inflationRate, double economicHealth) {
        guardrail.updateInputs(inflationRate, economicHealth);
    }

    /** 有效货币乘数 = 1 / 准备金率（央行调节准备金率即调节货币乘数）。 */
    public double getEffectiveReserveMultiplier() { return 1.0 / reserveRequirement; }

    /**
     * 理论 M2 上限（<b>旧口径，行为逐位不变</b>）：{@code M0 × (1 / 准备金率)}
     * = {@code baseMoney / reserveRequirement}。反映货币政策（准备金率 + OMO/基础货币）对 M2 上限的传导。
     *
     * <p>★<b>保留理由（§13.7/N6）</b>：该方法是既有公开面（`BankTabletScreen:289` 等经网络包直读），
     * 属一等兼容锚点 ⇒ <b>不得改语义</b>；新口径见 {@link #theoreticalM2CeilingNew()}。
     * 报告须登记"同名两个口径并存"的事实（§13.7 明示），避免再次出现 P1 式混淆。
     */
    public double theoreticalM2Ceiling() { return baseMoney / reserveRequirement; }

    /**
     * 理论 M2 上限（<b>新口径</b>，§8.2⑤）：含<b>现金漏损</b>与<b>超额准备金</b>的
     * {@code M0 / (rr_eff + e)} 形式。
     *
     * <p>口径（显式登记）：
     * <pre>
     *   M2_ceiling = B / (rr_eff + e)
     *     B      = 基础货币 —— ★见下方"分子口径登记"
     *     rr_eff = getEffectiveReserveRequirement() // 加权平均法定准备金率（内生聚合）
     *     e      = ER / reserveBase                 // 超额准备金率（实测；基线无存款 ⇒ 0）
     * </pre>
     * 与旧口径的差异：①分母由"一刀切 rr"改为"rr_eff + e"（降准与超额准备金都能改变上限）；
     * ②分子改用"B 一义口径"而非标量 {@code baseMoney} 的旧混用（P1 修复后 {@code M0} 是流通现金，不能当 B 用）。
     *
     * <p><b>★分子口径（t21 裁定：取权威 B）</b>：分子取 **{@link #getBaseMoneyBForPolicy()}** ——
     * 优先 **已注入聚合器的实测 B**（{@code getMonetaryAggregator().getBaseMoneyB()}，§3.1 权威口径）；
     * **未注入时回落**账本侧镜像（{@link #isBDelegated()} == false，登记为**过渡态**，见
     * {@code isBDelegated()} 的 javadoc）。理由：本方法是**本轮新引入**的口径，不得在新 API 里再造
     * 「一名两义」；且用镜像会把 M2 上限虚高（本实例 10,000,000 vs 4,500,000 ⇒ **2.2×**）。
     * <b>旧方法</b> {@link #theoreticalM2Ceiling()} 与全部阈值**零改动**。
     *
     * <p><b>边界（登记，不静默）</b>：{@code rr_eff + e <= 1e-9}（准备金率退化）时返回
     * {@link #THEORETICAL_CEILING_UNBOUNDED}（{@code 1e12}）而不是 {@code Infinity/NaN}，
     * 使下游（UI/报表/HTTP）永不出现非有限值；该退化态由 {@code getEffectiveReserveRequirement()}
     * 与 {@code getExcessReserveRatioE()} 可观测。
     *
     * @return 新口径的理论 M2 上限（有限值）
     */
    public double theoreticalM2CeilingNew() {
        double rrEff = getEffectiveReserveRequirement();
        double e = balanceSheet.getExcessReserveRatioE();
        double denom = rrEff + e;
        if (!(denom > 1e-9) || Double.isNaN(denom)) return THEORETICAL_CEILING_UNBOUNDED;
        double ceiling = getBaseMoneyBForPolicy() / denom;
        return Double.isFinite(ceiling) ? Math.max(0, ceiling) : THEORETICAL_CEILING_UNBOUNDED;
    }

    /**
     * **政策口径的 B**（t21 裁定）：优先**权威实测 B**（已注入聚合器），未注入回落账本镜像。
     *
     * <p>取值规则（确定性、无随机）：
     * <ul>
     *   <li>{@link #isBDelegated()} == true ⇒ 返回 {@code monetaryAggregator.getBaseMoneyB()}
     *       （**I1 右侧之和**，§3.1 权威口径）；</li>
     *   <li>否则 ⇒ 返回 {@link #getBaseMoneyB()}（账本侧镜像），属**过渡态**（聚合器未接线）。</li>
     * </ul>
     *
     * <p>与 {@link #getM0()} 的委托同型（同一注入点 {@link #setMonetaryAggregator}）。
     * <b>判据一律以聚合器口径为准</b>；本方法只服务"新引入的经济量"（如
     * {@link #theoreticalM2CeilingNew()}），**不参与任何既有判据**。
     */
    public double getBaseMoneyBForPolicy() {
        if (isBDelegated()) return monetaryAggregator.getBaseMoneyB();
        return getBaseMoneyB();
    }

    /** 政策口径 B 是否已委托给权威聚合器（= 已注入且账本门控开启；false ⇒ 过渡态、回落镜像）。 */
    public boolean isBDelegated() { return monetaryAggregator != null && isBalanceSheetEffective(); }

    /** 准备金率退化时的理论上限兜底值（有限值；避免 {@code Infinity/NaN} 流入 UI/报表）。 */
    public static final double THEORETICAL_CEILING_UNBOUNDED = 1e12;

    /** 现金漏损率 c = C /（存款合计）（账本口径；用于新口径上限的诊断与展示）。 */
    public double getCashLeakageRatioC() { return balanceSheet.getCashLeakageRatioC(); }

    /** 超额准备金率 e = ER / 达标存款基数（账本口径，供 {@link #theoreticalM2CeilingNew()} 与报表使用）。 */
    public double getExcessReserveRatioE() { return balanceSheet.getExcessReserveRatioE(); }

    /**
     * ★v1.6c：day-0「历史发行封装」登记额（**只读诊断，明文不作判据**；§13 例外表第 3 条）。
     *
     * <p>captain 裁定 = **方案 (B)**：`computeM0()`（10,000,000，标量旧兼容口径）与
     * **权威 B**（`MonetaryAggregator.getBaseMoneyB()`，宽口径 ★day-0 实测 **4,500,000**）
     * **永久不等**，差额（**5,500,000**）**显式登记**；**保持标量不变**（改标量会破 N1/CB-2）；
     * **不得用 `CANCEL`/`WRITE_OFF` 吸收**（它不是"未配对流动"而是历史既成事实）；
     * **I10 的 `divergenceAccount` 仍必须为 0，该例外不影响 I10 判据**。
     *
     * <p>配套诊断量：{@link #getLedgerVsComponentsGap()}（大数相减口径，同样不作判据）。
     */
    public double getLegacyIssuanceEncapsulation() { return balanceSheet.getLegacyIssuanceEncapsulation(); }

    /**
     * 大数相减口径的账实差（诊断用，**★v1.6c：明文不作判据、不参与 PASS/FAIL**）。
     *
     * <p>I10 判据用 {@link #getDivergenceAccount()}（= 未配对流动净额）。本值的编制日非零是**预期**的。
     */
    public double getLedgerVsComponentsGap() { return balanceSheet.getLedgerVsComponentsGap(); }

    /**
     * 设置贴现率（政策利率）。t3/SR-3：增设硬域 {@code [0.001, 0.20]} 钳制（AI 兜底防极端越界），
     * 并委托 {@link CentralBankerGuardrail#recordRateChange(double)} 评估对泰勒建议的偏离。
     * 返回约束结果（含是否被钳制/问责/接管），供调用方提示。
     */
    public CentralBankerGuardrail.GuardrailResult setDiscountRate(double rate) {
        CentralBankerGuardrail.GuardrailResult result = guardrail.recordRateChange(rate);
        this.discountRate = result.effectiveRate();
        syncPolicyRateFromDiscountRate();
        // §13.5：被钳制必须留痕（不得静默改数）
        if (!result.accepted()) {
            lastGuardrailClampNote = "利率请求 " + rate + " 越界，被制度约束钳制到 " + result.effectiveRate();
            recordPolicyEvent(PolicyEvent.GUARDRAIL_CLAMP, result.effectiveRate() - rate,
                    PolicyEvent.SOURCE_GUARDRAIL_CLAMP);
            policyDecisionSource = PolicyEvent.SOURCE_GUARDRAIL_CLAMP;
        } else {
            lastGuardrailClampNote = "";
            policyDecisionSource = PolicyEvent.SOURCE_PLAYER;
        }
        double delta = result.effectiveRate() - policyRateBefore;
        policyRateBefore = result.effectiveRate();
        recordPolicyEvent(delta < 0 ? PolicyEvent.RATE_CUT : (delta > 0 ? PolicyEvent.RATE_HIKE : null),
                Math.abs(delta), policyDecisionSource);
        return result;
    }

    /** 政策利率的上一次取值（用于把"改了多少"落成 RATE_CUT/RATE_HIKE 事件；确定性）。 */
    private double policyRateBefore = DEFAULT_POLICY_RATE;

    /**
     * AI 接管货币政策（央行行长离线/失职时兜底，t3/SR-5）。确定性将利率向泰勒建议靠拢。
     *
     * @return 接管后的利率
     */
    public double applyMonetaryAutopilot() {
        double rate = guardrail.applyAIAutopilot();
        this.discountRate = rate;
        syncPolicyRateFromDiscountRate();
        policyDecisionSource = PolicyEvent.SOURCE_AI_AUTOPILOT;
        double delta = rate - policyRateBefore;
        policyRateBefore = rate;
        recordPolicyEvent(delta < 0 ? PolicyEvent.RATE_CUT : (delta > 0 ? PolicyEvent.RATE_HIKE : null),
                Math.abs(delta), PolicyEvent.SOURCE_AI_AUTOPILOT);
        return rate;
    }

    public void setReserveRequirement(double req) {
        double previous = this.reserveRequirement;
        this.reserveRequirement = Math.max(0.01, Math.min(0.25, req));
        this.effectiveReserveRequirement = this.reserveRequirement;
        double delta = this.reserveRequirement - previous;
        if (delta != 0) {
            recordPolicyEvent(delta < 0 ? PolicyEvent.RR_CUT : PolicyEvent.RR_HIKE, Math.abs(delta),
                    policyDecisionSource);
        }
    }

    /**
     * 设置基础货币基数（M0）。法币化后基础货币由央行货币政策决定，可在运行时被货币政策调节。
     * 不允许出现负的基础货币。
     *
     * <p>★<b>语义不可动（R1）</b>：本方法必须保留原签名与原行为（{@code Math.max(0, amount)}），
     * 因为旧存档迁移路径直接调用它（{@code MarketEngine.loadFiat}）。CB-t3 只在<b>门控开</b>时
     * 额外把账本配平到该值（差额进 {@code ownCapital}，使 I8 不因旧档/直接设值而破），
     * 门控关时账本零执行 ⇒ 基线逐位复现。
     *
     * @param amount 目标基础货币量
     */
    public void setBaseMoney(double amount) {
        double previous = this.baseMoney;
        double clamped = Math.max(0, amount);
        this.baseMoney = clamped;
        double delta = clamped - previous;
        if (isBalanceSheetEffective()) {
            // 账本侧：标量直设属**未配对写入** ⇒ reserveMoney 同向调整 + I8 残差配平 + I10 偏差如实登记
            // （§6.1 I10 段：非 0 偏差必须由 CANCEL/WRITE_OFF 在同一 tick 归零，不得静默）
            balanceSheet.alignReserveMoneyTo(clamped);
        }
        lastBaseMoneySetDelta = delta;
        lastBaseMoneySetPrevious = previous;
        if (delta != 0) {
            recordPolicyEvent(delta < 0 ? PolicyEvent.OMO_DRAIN : PolicyEvent.OMO_INJECT, Math.abs(delta),
                    policySourceOrClosed());
        }
    }

    /** 最近一次 {@code setBaseMoney} 的增量与旧值（诊断/迁移留痕用）。 */
    private double lastBaseMoneySetDelta = 0;
    private double lastBaseMoneySetPrevious = 0;

    /** 最近一次 {@code setBaseMoney} 的增量。 */
    public double getLastBaseMoneySetDelta() { return lastBaseMoneySetDelta; }

    /** 最近一次 {@code setBaseMoney} 的旧值。 */
    public double getLastBaseMoneySetPrevious() { return lastBaseMoneySetPrevious; }

    /** 决策来源（无动作时回落 {@code "CLOSED"}，避免把空串写进事件）。 */
    private String policySourceOrClosed() {
        return (policyDecisionSource == null || policyDecisionSource.isEmpty()) ? "CLOSED" : policyDecisionSource;
    }

    /**
     * 央行唯一的基础货币（M0）投放入口：向经济注入基础货币。
     * 对应现实“基础货币由央行货币政策决定”。新玩家初始法币现金亦由此注入发放，保证
     * “现金有发行方”——现金可溯源到央行基础货币注入（M0 随之增加）。
     *
     * @param amount 注入量；须为正数
     */
    public void injectBaseMoney(double amount) {
        if (amount > 0) {
            book(BaseMoneyFlow.Kind.INJECT_CASH, amount, "PUBLIC", lastBookedTick);
            recordPolicyEvent(PolicyEvent.OMO_INJECT, amount, policySourceOrClosed());
        }
    }

    /**
     * 公开市场操作：购买国债（投放货币）
     */
    public void buyBonds(GovernmentBond bond) {
        bonds.add(bond);
        book(BaseMoneyFlow.Kind.GOV_BOND_BUY, bond.getFaceValue(), "GOVERNMENT", lastBookedTick);
        recordPolicyEvent(PolicyEvent.OMO_INJECT, bond.getFaceValue(), policySourceOrClosed());
    }

    /**
     * 公开市场操作：卖出国债（回笼货币）
     */
    public boolean sellBonds(GovernmentBond bond) {
        if (bonds.remove(bond)) {
            // 基线语义：标量 -= faceValue（不钳 0）；账本侧同步减记对政府债权与 ER（门控开）。
            double faceValue = bond.getFaceValue();
            applyBookedScalar(BaseMoneyFlow.Kind.GOV_BOND_SELL, faceValue, -faceValue);
            if (isBalanceSheetEffective()) {
                balanceSheet.bookBaseMoneyFlow(new BaseMoneyFlow(
                        BaseMoneyFlow.Kind.GOV_BOND_SELL, faceValue, "GOVERNMENT", lastBookedTick));
            }
            recordPolicyEvent(PolicyEvent.OMO_DRAIN, faceValue, policySourceOrClosed());
            return true;
        }
        return false;
    }

    /**
     * 最后贷款人：向商业银行提供紧急贷款。
     *
     * <p>基线语义（保留）：{@code bankReserves += amount}、{@code baseMoney += amount}、
     * {@code bank.addEmergencyReserves(amount)}。CB-t3 起：标量仍逐位一致，但账本侧改为
     * {@link BaseMoneyFlow.Kind#LAST_RESORT}（带抵押/罚息/受限/公开登记的条款化入口见
     * {@link #provideLastResortLending}）。
     */
    public boolean emergencyLending(Bank bank, double amount) {
        if (bank == null || amount <= 0) return false;
        bankReserves.merge(bank.getBankId(), amount, Double::sum);
        // F2（连接审计修复 #4）：紧急贷款实际计入银行准备金（此前只写央行账本，银行永远短缺、
        // 缺口每交易日重复触发且 baseMoney 无限递增——记账死路）。两侧对称、确定性、无随机。
        bank.addEmergencyReserves(amount);
        // CB-t3：标量经唯一入口 book(...) 落账（+amount，与基线的 "baseMoney += amount" 逐位一致），
        // 账本侧同时落 claimsOnOtherDepositoryCorps 与对手行 ER；条款化入口见 provideLastResortLending。
        book(BaseMoneyFlow.Kind.LAST_RESORT, amount, bank.getBankId(), lastBookedTick);
        recordPolicyEvent(PolicyEvent.LAST_RESORT, amount, policySourceOrClosed());
        return true;
    }

    /**
     * 调整商业银行准备金
     */
    public void adjustReserves(String bankId, double delta) {
        bankReserves.merge(bankId, delta, Double::sum);
    }

    /** 各银行准备金账本（央行视角）。 */
    public Map<String, Double> getBankReserves() { return bankReserves; }

    // ===== 玩家央行归属访问（AS-3/BT-3；纯访问，无状态变更、无随机） =====

    /** 该央行是否由玩家开设（playerOwner != null）。 */
    public boolean isPlayerOwned() { return playerOwner != null; }

    /** 央行拥有者（玩家 UUID）；AI/系统央行为 null。 */
    public UUID getPlayerOwner() { return playerOwner; }

    /** 开设该玩家央行的玩家名（展示用）。 */
    public String getCreatedBy() { return createdBy; }

    /** 开设该玩家央行时的引擎 tick 计数器（确定性时间戳）。 */
    public long getCreatedTick() { return createdTick; }

    /**
     * 设定央行玩家归属（AS-3）。仅在 {@code tryCreatePlayerCentralBank} 首次开设且未属玩家时调用；
     * 纯标记，不修改 baseMoney/discountRate/reserveRequirement/gold* 任何公式。
     *
     * @param owner  央行拥有者（玩家 UUID）
     * @param by     开设者显示名（持久化/展示）
     * @param tick   开设时引擎 tick 计数器（确定性时间戳）
     */
    public void setPlayerOwner(UUID owner, String by, long tick) {
        this.playerOwner = owner;
        this.createdBy = by;
        this.createdTick = tick;
    }

    /** 从持久化恢复各银行准备金账本（FM-7）。 */
    public void setBankReserves(Map<String, Double> reserves) {
        bankReserves.clear();
        if (reserves != null) bankReserves.putAll(reserves);
    }

    /** 执行逆回购投放流动性 */
    public ReverseRepoOperation reverseRepo(double amount, double rate, int days) {
        ReverseRepoOperation repo = new ReverseRepoOperation(
                ReverseRepoOperation.RepoType.CENTRAL_BANK, amount, rate, days);
        activeRepos.add(repo);
        book(BaseMoneyFlow.Kind.OMO_REPO, amount, null, lastBookedTick);
        omoOutstanding += amount;
        omoRate = rate;
        recordPolicyEvent(PolicyEvent.OMO_INJECT, amount, policySourceOrClosed());
        return repo;
    }

    /** 到期结算逆回购 */
    public double settleRepos(long currentDay) {
        this.settleDay = currentDay;
        double withdrawn = 0;
        Iterator<ReverseRepoOperation> it = activeRepos.iterator();
        while (it.hasNext()) {
            ReverseRepoOperation repo = it.next();
            if (currentDay - repo.getStartDay() >= repo.getMaturityDays()) {
                double totalDue = repo.getTotalDue(currentDay);
                double previous = this.baseMoney;
                applyBookedScalar(BaseMoneyFlow.Kind.OMO_REPO_MATURITY, totalDue, -totalDue);
                double applied = previous - this.baseMoney;
                if (isBalanceSheetEffective()) {
                    balanceSheet.bookBaseMoneyFlow(new BaseMoneyFlow(
                            BaseMoneyFlow.Kind.OMO_REPO_MATURITY, totalDue, null, lastBookedTick));
                }
                if (applied != 0) recordPolicyEvent(PolicyEvent.OMO_DRAIN, applied, policySourceOrClosed());
                withdrawn += totalDue;
                omoOutstanding = Math.max(0, omoOutstanding - totalDue);
                repo.setMatured(true);
                it.remove();
            }
        }
        return withdrawn;
    }

    public List<ReverseRepoOperation> getActiveRepos() { return activeRepos; }

    // ============================================================
    // 黄金储备账户（下界合金锭模拟现代黄金；NG-1/NG-5，非印钞）
    // ============================================================
    // goldCash 为购金专用法币余额（独立于 baseMoney/M2）；购金/抛金仅镜像调节 goldReserve 与 goldCash，
    // 不改变 baseMoney / reserveRequirement / discountRate，因此黄金不进入 M2、不印钞（NG-5）。

    /**
     * 央行购金：用专用法币余额 {@code goldCash} 换取黄金储备 {@code goldReserve}。
     * 当 {@code amount > 0} 且 {@code goldCash >= amount × currentGoldPrice} 时：
     * {@code goldReserve += amount; goldCash -= amount × currentGoldPrice; goldMarketBuyAmount += amount}。
     * 返回 {@code true}；余额不足或非法量时返回 {@code false} 且<b>不改变任何状态</b>（原子性、确定性）。
     *
     * <p>★P6/§6.1：黄金语义<b>不变</b>（不动 {@code baseMoney}）；门控开时额外把
     * {@code monetaryGold += amount × price}、{@code otherAssets -= 同额} 计入 I8 资产侧
     * （{@link BaseMoneyFlow.ScalarEffect#LEDGER_ONLY}，标量零影响 ⇒ N7 不回归）。
     */
    public boolean goldMarketBuy(double amount) {
        if (amount <= 0) return false;
        double cost = amount * currentGoldPrice;
        if (goldCash < cost) return false;
        goldReserve += amount;
        goldCash -= cost;
        goldHeldCashOutflow += cost;
        goldMarketBuyAmount += amount;
        if (isBalanceSheetEffective()) {
            balanceSheet.bookGoldHoldings(goldReserve * currentGoldPrice, goldCash);
        }
        return true;
    }

    /**
     * 央行抛金：用黄金储备 {@code goldReserve} 换回法币 {@code goldCash}。
     * 当 {@code amount > 0} 且 {@code goldReserve >= amount} 时：
     * {@code goldReserve -= amount; goldCash += amount × currentGoldPrice}。返回 {@code true}；否则 {@code false} 且不改状态。
     */
    public boolean goldMarketSell(double amount) {
        if (amount <= 0) return false;
        if (goldReserve < amount) return false;
        goldReserve -= amount;
        goldCash += amount * currentGoldPrice;
        if (isBalanceSheetEffective()) {
            balanceSheet.bookGoldHoldings(goldReserve * currentGoldPrice, goldCash);
        }
        return true;
    }

    /** 央行黄金储备（下界合金锭数）。 */
    public double getGoldReserve() { return goldReserve; }
    /** 央行购金专用法币余额。 */
    public double getGoldCash() { return goldCash; }
    /** 累计购金法币流出（对账/诊断）。 */
    public double getGoldHeldCashOutflow() { return goldHeldCashOutflow; }
    /** 当日净购金（供黄金定价读取）。 */
    public double getGoldMarketBuyAmount() { return goldMarketBuyAmount; }
    /** 当前黄金价（由 MarketEngine/GoldPricingSystem 每日刷新）。 */
    public double getCurrentGoldPrice() { return currentGoldPrice; }

    /** 交易日边界清零当日净购金（在黄金定价之后调用，形成「购金→次日定价→清零」确定性日循环）。 */
    public void resetGoldMarketFlow() { this.goldMarketBuyAmount = 0.0; }

    /** 设置当前黄金价（由 GoldPricingSystem.tick 每日刷新，供本日购金/抛金定价）。 */
    public void setCurrentGoldPrice(double price) {
        if (price >= 0) this.currentGoldPrice = price;
    }

    /** 从持久化恢复黄金储备账户（HM-8）；旧存档缺字段用默认值兜底。 */
    public void restoreGoldState(double reserve, double cash, double buyAmount, double heldCashOutflow) {
        this.goldReserve = reserve >= 0 ? reserve : DEFAULT_GOLD_RESERVE;
        this.goldCash = cash >= 0 ? cash : DEFAULT_GOLD_CASH;
        this.goldMarketBuyAmount = buyAmount >= 0 ? buyAmount : 0.0;
        this.goldHeldCashOutflow = heldCashOutflow >= 0 ? heldCashOutflow : 0.0;
    }

    /** Q3 硬条件（CB-22②）：购金/售金<b>恒</b>经既有黄金系统供需路径（{@code goldMarketBuy/Sell} 上仅有市场入口）。 */
    public boolean isGoldThroughMarketPath() { return true; }

    // ============================================================
    // CB-t3：央行资产负债表（§3.2 / §5.2）
    // ============================================================

    /** 央行资产负债表（只读出口；记账仍只能经 {@link #book}）。 */
    public CentralBankBalanceSheet getBalanceSheet() { return balanceSheet; }

    /**
     * ★P1 收口（CB-t3b）：注入货币分层聚合器，使 {@link #getM0()} 能委托到一义口径
     * （{@code M0 = 流通中现金 C}）。
     *
     * <p><b>为何需要注入而非构造参数</b>：本类在 {@code MarketEngine} 的<b>字段初始化阶段</b>就被
     * {@code BankManager} 构造，而聚合器实例随后才由 {@code BankManager.getMonetaryAggregator()} 暴露
     * ⇒ 只能事后注入（与 {@code setFiscalLedgerHook} 同因）。注入点归 t5 在装配时调用，
     * 或由 {@link #attachBankManager} 自动完成（若该 BankManager 已持有聚合器）。
     *
     * @param aggregator 聚合器（{@code null} = 解绑，回落 {@code baseMoney} 旧义）
     */
    public void setMonetaryAggregator(MonetaryAggregator aggregator) {
        this.monetaryAggregator = aggregator;
    }

    /** 已注入的聚合器（未注入时为 {@code null}）。 */
    public MonetaryAggregator getMonetaryAggregator() { return monetaryAggregator; }

    /** P1 收口是否已生效（聚合器已注入且账本门控开启）。 */
    public boolean isM0Delegated() { return monetaryAggregator != null && isBalanceSheetEffective(); }

    /** 国外资产（黄金 + 外汇 + 其他国外资产）。 */
    public double getForeignAssets() { return balanceSheet.getForeignAssets(); }

    /** 货币黄金（= {@code goldReserve × currentGoldPrice}，按市价）。 */
    public double getMonetaryGold() { return balanceSheet.getMonetaryGold(); }

    /** 外汇储备（Q3：外生记账汇率；必须真记账进 I8 资产侧，CB-22①）。 */
    public double getFxReserves() { return balanceSheet.getFxReserves(); }

    /** 对政府债权（持有的国债）。 */
    public double getClaimsOnGovernment() { return balanceSheet.getClaimsOnGovernment(); }

    /** 对其他存款性公司债权（OMO/MLF/SLF/再贷款/再贴现/最后贷款人）。 */
    public double getClaimsOnOtherDepositoryCorps() {
        return balanceSheet.getClaimsOnOtherDepositoryCorps();
    }

    /** 对其他金融性公司债权（仅可经 {@code LAST_RESORT}/结构性工具产生；禁止资产购买型 QE，§13.11）。 */
    public double getClaimsOnOtherFinancialCorps() {
        return balanceSheet.getClaimsOnOtherFinancialCorps();
    }

    /** 其他资产。 */
    public double getOtherAssets() { return balanceSheet.getOtherAssets(); }

    /** 资产合计（I8 左侧）。 */
    public double getTotalAssets() { return balanceSheet.getTotalAssets(); }

    /**
     * 储备货币（I8 负债侧；**账本侧口径** = {@code outstandingB} 的镜像）。
     *
     * <p>★**与聚合器口径的关系（t16 消歧；勿再写成"同源/同义"）**：
     * 本方法返回**账本侧储备货币镜像**；{@code MonetaryAggregator.getBaseMoneyB()} 返回 **I1 右侧之和的实测值**。
     * 二者**仅在 I10 成立（迁移完成、且每 tick 满足）后**同值；**迁移前不相等**，
     * 差额即 §13 具名例外「历史发行封装」{@link #getLegacyIssuanceEncapsulation()}。
     * **判据一律以 {@code MonetaryAggregator.getBaseMoneyB()} 为准**（CB-2/CB-3/CB-4）。
     */
    public double getReserveMoney() { return balanceSheet.getReserveMoney(); }

    /** 不计入储备货币的金融性公司存款（兼作 I9 结算欠款科目）。 */
    public double getFinancialCorpDepositsNonReserve() {
        return balanceSheet.getFinancialCorpDepositsNonReserve();
    }

    /** 政府存款（财政存款；与 {@code Government.getTreasury()} 逐位镜像，CB-12）。 */
    public double getGovernmentDeposits() { return balanceSheet.getGovernmentDeposits(); }

    /** 发行债券（央行票据，§6.8）。 */
    public double getCentralBankBills() { return balanceSheet.getCentralBankBills(); }

    /** 其他负债。 */
    public double getOtherLiabilities() { return balanceSheet.getOtherLiabilities(); }

    /** 自有资金（旧档迁移的配平落点）。 */
    public double getOwnCapital() { return balanceSheet.getOwnCapital(); }

    /** 负债与自有资金合计（I8 右侧）。 */
    public double getTotalLiabilitiesAndCapital() { return balanceSheet.getTotalLiabilitiesAndCapital(); }

    /** I8 校验（{@code Σ资产 == Σ负债 + 自有资金}，容差 1e-6）。 */
    public boolean validateBalanceSheet() { return balanceSheet.validateBalanceSheet(); }

    /** I8 两侧绝对差。 */
    public double getBalanceSheetError() { return balanceSheet.getBalanceSheetError(); }

    // ===== §3.4 委托只读出口 =====

    /**
     * **账本侧储备货币镜像**（{@code reserveMoney}）—— ★**不是**"I1 右侧之和"那个权威 B。
     *
     * <p><b>★同名消歧（captain t16 裁定：保留两个出口、不改返回值、不改判据数值）</b>
     * —— 本类与聚合器各有一个 `getBaseMoneyB()`，二者**语义不同、数值可以不同**：
     *
     * <table border="1">
     *   <caption>两个 `getBaseMoneyB()` 的分工</caption>
     *   <tr><th>出口</th><th>取值来源</th><th>语义</th><th>用途</th></tr>
     *   <tr>
     *     <td>{@code MonetaryAggregator.getBaseMoneyB()}</td>
     *     <td>逐行读银行真实账本 + 玩家现金供应器后累加</td>
     *     <td><b>I1 右侧之和</b> {@code C + Vc + RR + ER + Pay}（§3.1 权威口径）</td>
     *     <td><b>判据一律用它</b>（CB-2/CB-3/CB-4、{@code /api/money} 的 {@code b}）</td>
     *   </tr>
     *   <tr>
     *     <td>本方法</td>
     *     <td>央行资产负债表的 {@code reserveMoney}（由 {@code bookBaseMoneyFlow} 逐笔维护）</td>
     *     <td><b>账本侧镜像</b>（I8 的负债侧科目）</td>
     *     <td>I8（{@code Σ资产 = Σ负债 + 自有资金}）与账实对账诊断</td>
     *   </tr>
     * </table>
     *
     * <p><b>为何不能直接比较二者</b>：聚合器口径的右侧和是**实测值**，本方法是**账本镜像**；
     * **仅当 I10 成立（迁移完成、且每 tick 满足）后**二者才同值；**迁移前两者不等**
     * （本实例：镜像 {@code 10,000,000}、实测 {@code 4,500,000}，差额 = §13 具名例外「历史发行封装」
     * {@link #getLegacyIssuanceEncapsulation()} = 5,500,000 —— <b>★t30（F-CB-3）修复后成立</b>：
     * 修复前该 getter 因"已登记即返回登记值"而返回陈旧的迁移登记值 {@code 10,000,000}）。
     * ⇒ **不要**把"本方法 == 聚合器 getBaseMoneyB()"当判据（那是恒假式，不是缺陷）。
     * ⇒ **判据一律以 {@code MonetaryAggregator.getBaseMoneyB()} 为准**。
     *
     * <p>保留原因：`LocalHttpServer` 的 `/api/money` 端点正在调用（{@code baseMoneyB} 字段），
     * 删除会连带 t6；且 I8 侧确实需要一个"账本镜像"出口。
     */
    public double getBaseMoneyB() { return balanceSheet.getReserveMoney(); }

    /** 诊断输入：全 AI 公司 fairValue 相对基线的均值变化（CB-12④；t5 每交易日注入）。 */
    public void setMeanFairValueDelta(double delta) {
        if (!Double.isNaN(delta)) this.meanFairValueDelta = delta;
    }

    /** ④ 估值响应（只读诊断，CB-12④）。 */
    public double getMeanFairValueDelta() { return meanFairValueDelta; }

    // ============================================================
    // CB-t3：工具箱参数与余额（§3.2 / §6.2..§6.10）
    // ============================================================

    /** 政策利率（7 天逆回购利率，外生决策）。 */
    public double getPolicyRate() { return policyRate; }

    /** SLF（走廊上限，外生）。 */
    public double getStandingLendingFacilityRate() { return standingLendingFacilityRate; }

    /** IOER（走廊下限，外生）。 */
    public double getInterestOnExcessReservesRate() { return interestOnExcessReservesRate; }

    /** LPR 1Y（内生：政策利率 + 加点 + MLF 调整项，§6.5）。 */
    public double getLprOneYear() { return lprOneYear; }

    /** 国债收益率（内生，Q5；由持有国债的到期收益率派生，§6.5/§8.2）。 */
    public double getGovernmentBondYield() { return governmentBondYield; }

    /**
     * 加权平均法定准备金率 rr_eff（内生聚合，§6.2/§3.2）。
     *
     * <p>权威口径是「Σ 各行 RR_i / Σ 各行达标存款基数_i」（由 {@link #propagateReserveRequirement}
     * 用 {@link BankManager#settleReserveRequirement()} 与银行侧真实账本算出并缓存到
     * {@code CentralBankBalanceSheet.getEffectiveReserveRequirement()}）；尚未结算时回落
     * {@code CentralBank.reserveRequirement}（= §0.10.1 先验档，保证首帧可读）。
     */
    public double getEffectiveReserveRequirement() {
        double fromLedger = balanceSheet.getEffectiveReserveRequirement();
        return fromLedger > 0 ? fromLedger : reserveRequirement;
    }

    /** OMO（7 天逆回购）未到期余额。 */
    public double getOmoOutstanding() { return omoOutstanding; }

    /** MLF 未到期余额。 */
    public double getMlfOutstanding() { return mlfOutstanding; }

    /** 定向再贷款未到期余额（§6.5；本期只做科目与利率）。 */
    public double getReloanOutstanding() { return reloanOutstanding; }

    /** 再贴现未到期余额（§6.5；本期只做科目与利率，不做票据池）。 */
    public double getRediscountOutstanding() { return rediscountOutstanding; }

    /** SLF 未到期余额。 */
    public double getSlfOutstanding() { return slfOutstanding; }

    /** 7 天逆回购报价利率（OMO 工具利率 = policyRate）。 */
    public double getOmoRate() { return omoRate; }

    /** 走廊上限 = SLF（I7 用；t4 的 {@code InterbankMarket.getCorridorUpper()} 应委托这里）。 */
    public double getCorridorUpperRate() { return standingLendingFacilityRate; }

    /** 走廊下限 = IOER（I7 用）。 */
    public double getCorridorLowerRate() { return interestOnExcessReservesRate; }

    /** 政策利率硬域下界。 */
    public double getPolicyRateMin() { return POLICY_RATE_MIN; }

    /** 政策利率硬域上界。 */
    public double getPolicyRateMax() { return POLICY_RATE_MAX; }

    /** 央票存续列表（§6.8；只读视图）。 */
    public List<Bill> getBillsOutstanding() { return Collections.unmodifiableList(billsOutstanding); }

    /** 持有国债数量（诊断）。 */
    public int getHeldBondCount() { return bonds.size(); }

    // ============================================================
    // CB-t3：政策工具箱操作入口（§6.2..§6.10）
    // ============================================================

    /** 把 {@code discountRate}（既有政策利率字段）同步到 {@code policyRate} 与走廊两端。 */
    private void syncPolicyRateFromDiscountRate() {
        this.policyRate = this.discountRate;
        this.omoRate = this.discountRate;
        this.standingLendingFacilityRate = this.discountRate + CORRIDOR_HALF_WIDTH;
        this.interestOnExcessReservesRate = Math.max(0, this.discountRate - CORRIDOR_HALF_WIDTH);
    }

    /** 设定政策利率（工具箱入口；硬域钳制 + 走廊跟随 + 每动作 1 条事件）。 */
    public void setPolicyRate(double rate) {
        double clamped = Math.max(POLICY_RATE_MIN, Math.min(POLICY_RATE_MAX, rate));
        double previous = this.policyRate;
        this.policyRate = clamped;
        this.discountRate = clamped;
        this.omoRate = clamped;
        this.standingLendingFacilityRate = clamped + CORRIDOR_HALF_WIDTH;
        this.interestOnExcessReservesRate = Math.max(0, clamped - CORRIDOR_HALF_WIDTH);
        double delta = clamped - previous;
        if (delta != 0) {
            recordPolicyEvent(delta < 0 ? PolicyEvent.RATE_CUT : PolicyEvent.RATE_HIKE, Math.abs(delta),
                    policySourceOrClosed());
        }
    }

    /** 设定 SLF（走廊上限；独立覆盖后 I7 仍须成立，否则钳制并留痕，§6.6）。 */
    public void setStandingLendingFacilityRate(double rate) {
        double requested = Math.max(0, rate);
        double clamped = Math.max(requested, interestOnExcessReservesRate);
        if (Math.abs(clamped - requested) > 1e-12) {
            lastGuardrailClampNote = "SLF 请求 " + requested + " 低于 IOER，被钳到 " + clamped + "（I7 硬约束）";
            recordPolicyEvent(PolicyEvent.GUARDRAIL_CLAMP, clamped - requested,
                    PolicyEvent.SOURCE_GUARDRAIL_CLAMP);
            policyDecisionSource = PolicyEvent.SOURCE_GUARDRAIL_CLAMP;
        }
        this.standingLendingFacilityRate = clamped;
    }

    /** 设定 IOER（走廊下限；独立覆盖后 I7 仍须成立，否则钳制并留痕，§6.6）。 */
    public void setInterestOnExcessReservesRate(double rate) {
        double requested = Math.max(0, rate);
        double clamped = Math.min(requested, standingLendingFacilityRate);
        if (Math.abs(clamped - requested) > 1e-12) {
            lastGuardrailClampNote = "IOER 请求 " + requested + " 高于 SLF，被钳到 " + clamped + "（I7 硬约束）";
            recordPolicyEvent(PolicyEvent.GUARDRAIL_CLAMP, clamped - requested,
                    PolicyEvent.SOURCE_GUARDRAIL_CLAMP);
            policyDecisionSource = PolicyEvent.SOURCE_GUARDRAIL_CLAMP;
        }
        this.interestOnExcessReservesRate = clamped;
    }

    /** 一次完整的制度性利率调整（政策利率 + 走廊两端跟随；§6.9 的自动排序②）。 */
    public void applyCorridorFollow() {
        this.standingLendingFacilityRate = policyRate + CORRIDOR_HALF_WIDTH;
        this.interestOnExcessReservesRate = Math.max(0, policyRate - CORRIDOR_HALF_WIDTH);
    }

    /**
     * OMO 投放（7 天逆回购；§6.4）：单日投放 ≤ {@link #OMO_MAX_DAILY}，到期日按交易日序自动回笼。
     *
     * @param amount 投放量
     * @param days   期限（交易日）
     * @param day    当前交易日
     * @return 实际投放的逆回购（超上限被钳制时返回按上限的额度）
     */
    public ReverseRepoOperation omoInject(double amount, int days, long day) {
        if (!(amount > 0)) return null;
        double capped = Math.min(amount, OMO_MAX_DAILY);
        ReverseRepoOperation repo = reverseRepo(capped, omoRate, days);
        repo.setStartDay(day);
        return repo;
    }

    /**
     * MLF 投放（中期借贷便利；§6.5）：期限默认 360 交易日，利率 = policyRate + {@link #MLF_SPREAD}。
     *
     * @param amount 投放量
     * @param days   期限（交易日）
     * @param day    当前交易日
     * @return 实际投放额度（参数非法为 0）
     */
    public double mlfLend(double amount, int days, long day) {
        if (!(amount > 0)) return 0;
        ActiveTool active = new ActiveTool(amount, policyRate + MLF_SPREAD, day, days);
        mlfBooks.add(active);
        book(BaseMoneyFlow.Kind.MLF_LEND, amount, null, lastBookedTick);
        mlfOutstanding += amount;
        recordPolicyEvent(PolicyEvent.MLF, amount, policySourceOrClosed());
        return amount;
    }

    /**
     * 再贷款投放（定向工具；§6.5）：期限 180 交易日，利率 = policyRate + {@link #RELOAN_SPREAD}。
     *
     * @param amount       投放量
     * @param sector       定向领域标签（仅记账用；可为 null）
     * @param days         期限（交易日）
     * @param day          当前交易日
     * @return 实际投放额度
     */
    public double reloanLend(double amount, String sector, int days, long day) {
        if (!(amount > 0)) return 0;
        reloanBooks.add(new ActiveTool(amount, policyRate + RELOAN_SPREAD, day, days));
        reloanOutstanding += amount;
        reloanTargetSector = sector == null ? "" : sector;
        book(BaseMoneyFlow.Kind.MLF_LEND, amount, null, lastBookedTick);
        recordPolicyEvent(PolicyEvent.MLF, amount, policySourceOrClosed());
        return amount;
    }

    /**
     * 再贴现（§6.5）：期限 90 交易日，利率 = policyRate + {@link #REDISCOUNT_SPREAD}；
     * 本期<b>只做科目与利率</b>，不做票据池（登记为未做）。
     *
     * @param amount 贴现金额
     * @param days   期限（交易日）
     * @param day    当前交易日
     * @return 实际贴现额度
     */
    public double rediscount(double amount, int days, long day) {
        if (!(amount > 0)) return 0;
        rediscountBooks.add(new ActiveTool(amount, policyRate + REDISCOUNT_SPREAD, day, days));
        rediscountOutstanding += amount;
        book(BaseMoneyFlow.Kind.MLF_LEND, amount, null, lastBookedTick);
        recordPolicyEvent(PolicyEvent.MLF, amount, policySourceOrClosed());
        return amount;
    }

    /** 定向再贷款的领域标签（仅记账用）。 */
    private String reloanTargetSector = "";

    /** 定向再贷款领域标签（诊断）。 */
    public String getReloanTargetSector() { return reloanTargetSector; }

    /** 工具台账（确定性期限跟踪；MLF/再贷款/再贴现/SLF 共用）。 */
    private record ActiveTool(double amount, double rate, long startDay, int termDays) {
        boolean isMatured(long day) { return day - startDay >= termDays; }
    }

    private final List<ActiveTool> mlfBooks = new ArrayList<>();
    private final List<ActiveTool> reloanBooks = new ArrayList<>();
    private final List<ActiveTool> rediscountBooks = new ArrayList<>();
    private final List<ActiveTool> slfBooks = new ArrayList<>();

    /**
     * SLF 借出（走廊上限工具；§6.6）：利率 = {@link #standingLendingFacilityRate}。
     *
     * @param bankId 借入行
     * @param amount 借出量
     * @param days   期限（交易日）
     * @param day    当前交易日
     * @return 实际借出额度
     */
    public double slfLend(String bankId, double amount, int days, long day) {
        if (!(amount > 0)) return 0;
        slfBooks.add(new ActiveTool(amount, standingLendingFacilityRate, day, days));
        slfOutstanding += amount;
        book(BaseMoneyFlow.Kind.SLF_LEND, amount, bankId, lastBookedTick);
        recordPolicyEvent(PolicyEvent.SLF_LEND, amount, policySourceOrClosed());
        return amount;
    }

    /**
     * 发行央票（§6.8）：负债侧回笼工具；存续上限 {@link #BILL_MAX_OUTSTANDING}。
     *
     * @param faceValue 面值
     * @param rate      票面利率
     * @param day       发行交易日
     * @param termDays  期限（交易日）
     * @return 实际发行面值（0 = 受限未发行）
     */
    public double issueBill(double faceValue, double rate, long day, int termDays) {
        if (!(faceValue > 0)) return 0;
        double headroom = BILL_MAX_OUTSTANDING - balanceSheet.getCentralBankBills();
        if (headroom <= 0) return 0;
        double actual = Math.min(faceValue, headroom);
        billSeq++;
        Bill bill = new Bill("CB_BILL_" + day + "_" + billSeq, actual, rate, day, termDays);
        billsOutstanding.add(bill);
        book(BaseMoneyFlow.Kind.ISSUE_BILL, actual, null, lastBookedTick);
        recordPolicyEvent(PolicyEvent.BILL_ISSUE, actual, policySourceOrClosed());
        return actual;
    }

    /**
     * 央票到期兑付扫描（日级；§6.8）。
     *
     * @param day 当前交易日
     * @return 兑付面值合计
     */
    public double redeemMaturedBills(long day) {
        double redeemed = 0;
        Iterator<Bill> it = billsOutstanding.iterator();
        while (it.hasNext()) {
            Bill bill = it.next();
            if (bill.isMatured(day)) {
                book(BaseMoneyFlow.Kind.REDEEM_BILL, bill.faceValue(), null, lastBookedTick);
                redeemed += bill.faceValue();
                it.remove();
            }
        }
        if (redeemed > 0) recordPolicyEvent(PolicyEvent.BILL_ISSUE, -redeemed, policySourceOrClosed());
        return redeemed;
    }

    /**
     * LPR 生成（§6.5，内生、确定性）：
     * {@code LPR_1Y = clamp(policyRate + LPR_SPREAD_BASE + 0.0010 × tanh(MLF_outstanding / MLF_REF), 0.01, 0.30)}。
     *
     * @param bankManager 银行体系（本实现只用其存在性作门控语义占位，不读其状态 ⇒ 无跨任务耦合）
     */
    public void updateLpr(BankManager bankManager) {
        double adjust = 0.0010 * Math.tanh(mlfOutstanding / LPR_MLF_REF);
        double raw = policyRate + LPR_SPREAD_BASE + adjust;
        this.lprOneYear = Math.max(LPR_MIN, Math.min(LPR_MAX, raw));
        // §6.5：把 LPR 推送给各行（t2 的 setLprOneYear；未注入时银行侧回落固定 50000 ⇒ 由 t5 门控）
        if (bankManager != null) {
            for (Bank bank : bankManager.getAllBanks()) {
                invokeVoid(bank, "setLprOneYear", new Class<?>[]{double.class}, new Object[]{lprOneYear});
            }
        }
    }

    /**
     * 政策利率下降 ⇒ 国债收益率下降（内生、确定性；Q5 单一收益率口径）。
     *
     * <p>口径（登记）：{@code yield = clamp(policyRate + LPR_SPREAD_BASE + 国债存量溢价, 0.0005, 0.30)}，
     * 其中国债存量溢价 = {@code 0.002 × tanh(Σ持有国债面值 / 1e8)}；无持有国债时为 0 ⇒
     * 收益率与政策利率同向（A3 的方向性来源）。
     */
    public void updateGovernmentBondYield() {
        double stock = 0;
        for (GovernmentBond bond : bonds) stock += bond.getFaceValue();
        double premium = 0.002 * Math.tanh(stock / 100_000_000.0);
        this.governmentBondYield = Math.max(0.0005, Math.min(0.30, policyRate + LPR_SPREAD_BASE + premium));
    }

    /** 每交易日工具到期扫描（MLF/再贷款/再贴现/SLF/央票；确定性顺序）。 */
    public void processToolMaturities(long day) {
        mlfOutstanding = Math.max(0, mlfOutstanding - matureAndBook(mlfBooks, BaseMoneyFlow.Kind.MLF_MATURITY, day));
        reloanOutstanding = Math.max(0, reloanOutstanding
                - matureAndBook(reloanBooks, BaseMoneyFlow.Kind.MLF_MATURITY, day));
        rediscountOutstanding = Math.max(0, rediscountOutstanding
                - matureAndBook(rediscountBooks, BaseMoneyFlow.Kind.MLF_MATURITY, day));
        slfOutstanding = Math.max(0, slfOutstanding - matureAndBook(slfBooks, BaseMoneyFlow.Kind.SLF_LEND, day));
        redeemMaturedBills(day);
    }

    /** 到期扫描：把到期工具按对应 Kind 回笼并返回合计额度。 */
    private double matureAndBook(List<ActiveTool> books, BaseMoneyFlow.Kind kind, long day) {
        double total = 0;
        Iterator<ActiveTool> it = books.iterator();
        while (it.hasNext()) {
            ActiveTool tool = it.next();
            if (tool.isMatured(day)) {
                double due = tool.amount() * (1 + tool.rate() * tool.termDays() / 365.0);
                double previous = this.baseMoney;
                applyBookedScalar(kind, due, -due);
                if (isBalanceSheetEffective()) {
                    balanceSheet.bookBaseMoneyFlow(new BaseMoneyFlow(kind, due, null, lastBookedTick));
                }
                if (previous - this.baseMoney != 0) {
                    recordPolicyEvent(PolicyEvent.OMO_DRAIN, previous - this.baseMoney, policySourceOrClosed());
                }
                total += tool.amount();
                it.remove();
            }
        }
        return total;
    }

    // ============================================================
    // CB-t3：每日管线（§8.3 的②③④⑥⑨；由 CB-t5 按固定顺序调用，禁调序）
    // ============================================================

    /** 每交易日开始时把确定性时间戳校准到当前 tick（t5 在 runDailyFiat 首行调用即可）。 */
    public void beginDay(long tick) { this.lastBookedTick = tick; }

    /**
     * ② {@code syncBalanceSheet()}：I8/I10 结算（含黄金按市价、政府存款镜像、旧档配平）。
     *
     * <p>镜像约束（§7.2 C6）：{@code getGovernmentDeposits() == Government.getTreasury()}（逐位）。
     * 税收/支出同时走 {@code TAX_COLLECT}/{@code FISCAL_SPEND}（由 {@code Government}/{@code TaxSystem} 钩子触发）。
     */
    public void syncBalanceSheet(Government government) {
        if (!isBalanceSheetEffective()) return;
        balanceSheet.bookGoldHoldings(goldReserve * currentGoldPrice, goldCash);
        balanceSheet.setGovernmentDepositsMirror(government == null ? 0 : government.getTreasury());
        // I10 收口计数（§6.1：偏差非 0 且未被 absorb 归零 ⇒ divergenceHits++ 并留痕，不得静默）
        if (balanceSheet.isDivergenceBeyondTolerance()) {
            divergenceHits++;
            recordPolicyEvent(PolicyEvent.IDENTITY_VIOLATION, balanceSheet.getDivergenceAmount(),
                    policySourceOrClosed());
        }
    }

    /** I10 告警计数（偏差非 0 且未归零的次数；与聚合器的计数同口径）。 */
    private long divergenceHits = 0;

    /** I10 告警计数。 */
    public long getDivergenceHits() { return divergenceHits; }

    /** 账本偏差量（{@code reserveMoney − (C+Vc+RR+ER+Pay)}；正常态必须为 0，容差 1e-6）。 */
    public double getDivergenceAccount() { return balanceSheet.getDivergenceAmount(); }

    /** I10 机械判据（{@code |偏差| ≤ 1e-6}）。 */
    public boolean validateI10() { return balanceSheet.validateI10(); }

    /** I8 + I10 联合校验（每交易日资金账务后的收口；任一失败 ⇒ false）。 */
    public boolean validateIdentities() { return balanceSheet.validateBalanceSheet() && balanceSheet.validateI10(); }

    /** 最近一次失败的恒等式编号（I8 = 8、I10 = 10；0 = 全部通过）。 */
    public int getLastFailedIdentity() {
        if (!balanceSheet.validateI10()) return 10;
        if (!balanceSheet.validateBalanceSheet()) return 8;
        return 0;
    }

    /**
     * 财政-货币账簿钩子（§7.2 C6）：由引擎在装配时注册本 run 的央行实例。
     *
     * <p><b>为何是静态引用（登记）</b>：{@link Government}/{@link TaxSystem} 由
     * {@code MarketEngine} 在<b>央行实例取出之前</b>构造（{@code taxSystem = new TaxSystem(government)}
     * 在字段初始化阶段，{@code centralBank = bankManager.getCentralBank()} 在构造函数内），
     * 故无法用构造参数注入；单个 {@code MarketEngine} 实例对应单个 run，静态引用在
     * 「一个 JVM 一个 run」的既有约定下等价于实例引用。<b>未注册时钩子零执行</b>（不影响既有数值链）。
     */
    private static CentralBank fiscalLedgerHook = null;

    /** 注册本 run 的财政-货币账簿钩子（幂等；由 MarketEngine 在装配时调用）。 */
    public static void setFiscalLedgerHook(CentralBank centralBank) { fiscalLedgerHook = centralBank; }

    /** 当前注册的财政-货币账簿钩子（null = 未注册）。 */
    public static CentralBank getFiscalLedgerHook() { return fiscalLedgerHook; }

    /** 财政钩子是否已注册。 */
    public static boolean isFiscalLedgerHooked() { return fiscalLedgerHook != null; }

    /**
     * ③ {@code propagateReserveRequirement(BankManager)}：rr → 各行 RR_i（§5.4/§8.3③）。
     *
     * <p>记账：rr ↓ ⇒ {@code RR_CUT_RELEASE}、rr ↑ ⇒ {@code RR_HIKE_FREEZE}（B 总量不变，只改 I1 内部构成）。
     * 银行侧的 RR/ER 分解由 CB-t2 的 {@code Bank.settleReserveRequirement(rrEff)} 落地
     * （反射调用；t2 未落地时跳过并置 {@code reserveSyncDegraded}，不静默改数）。
     *
     * @return 是否完成了全量下发（false = t2 的银行侧入口尚未就绪，已降级）
     */
    public boolean propagateReserveRequirement(BankManager bankManager) {
        if (bankManager == null) return false;
        boolean ok = true;
        for (Bank bank : bankManager.getAllBanks()) {
            if (!invokeVoid(bank, "settleReserveRequirement", new Class<?>[]{double.class},
                    new Object[]{reserveRequirement})) {
                ok = false;
            }
        }
        // 加权平均 rr_eff（Σ RR / Σ 达标存款基数）；t2 未就绪时回落央行参数值
        double rrTotal = 0;
        double baseTotal = 0;
        for (Bank bank : bankManager.getAllBanks()) {
            double rr = invokeDouble(bank, "getReservesRequired", bank.getTotalDepositsAll() * reserveRequirement);
            double base = invokeDouble(bank, "getReserveBase", bank.getTotalDepositsAll());
            rrTotal += Math.max(0, rr);
            baseTotal += Math.max(0, base);
        }
        double rrEff = baseTotal > 0 ? rrTotal / baseTotal : reserveRequirement;
        balanceSheet.setEffectiveReserveRequirement(rrEff);
        // §6.2 记账：rr 变化 ⇒ RR_CUT_RELEASE / RR_HIKE_FREEZE（只改 I1 内部构成，B 总量不变）
        double delta = rrTotal - lastRequiredReservesTotal;
        if (isBalanceSheetEffective() && Math.abs(delta) > 1e-9) {
            BaseMoneyFlow.Kind kind = delta < 0 ? BaseMoneyFlow.Kind.RR_CUT_RELEASE
                    : BaseMoneyFlow.Kind.RR_HIKE_FREEZE;
            balanceSheet.bookBaseMoneyFlow(new BaseMoneyFlow(kind, Math.abs(delta), null, lastBookedTick));
            recordPolicyEvent(delta < 0 ? PolicyEvent.RR_CUT : PolicyEvent.RR_HIKE, Math.abs(delta),
                    policySourceOrClosed());
        }
        lastRequiredReservesTotal = rrTotal;
        reserveSyncDegraded = !ok;
        return ok;
    }

    /** 上一次全量下发的 Σ RR_i（用于把 rr 变化折算成 RR_CUT_RELEASE / RR_HIKE_FREEZE 的额度）。 */
    private double lastRequiredReservesTotal = 0;

    /** 上一次全量下发的 Σ RR_i（诊断）。 */
    public double getLastRequiredReservesTotal() { return lastRequiredReservesTotal; }

    /** 银行侧结算入口未就绪时的降级标志（t2 尚未交付 ⇒ 不静默，`getReserveSyncDegraded()` 可观测）。 */
    private boolean reserveSyncDegraded = false;

    /** 银行侧准备金同步是否降级（t2 的 {@code settleReserveRequirement} 尚未可调用）。 */
    public boolean isReserveSyncDegraded() { return reserveSyncDegraded; }

    /**
     * ⑨ {@code flushPolicyEvents(...)}：把事件通道交给引擎（t5 读取本类的有界环并注入宏观不确定性跳跃）。
     *
     * <p>本方法只做「本交易日的政策动作是否已全部留痕」的收口（幂等、只读），
     * 事件环本身由 {@link #recordPolicyEvent} 维护。
     *
     * @return 当前环内事件数（≤ {@link PolicyEvent#RING_CAPACITY}）
     */
    public int flushPolicyEvents() { return policyEvents.size(); }

    // ============================================================
    // CB-t3：P9 问题银行状态机 + 最后贷款人条款（§6.10）
    // ============================================================

    /**
     * 日级问题银行状态评估（§6.10.1；纯阈值比较、零随机）。每次转移写 1 条
     * {@link PolicyEvent#TROUBLED_STATE} 事件（CB-23①）。
     *
     * @param bankManager 银行体系（只读）
     * @param interbankSupply 银行间市场当日可借总额 Σs（t4 提供；未接线时传 {@code -1} 表示未知）
     * @return 本次评估发生的状态转移次数
     */
    public int evaluateTroubledBanks(BankManager bankManager, double interbankSupply) {
        if (bankManager == null) return 0;
        int transitions = 0;
        for (Bank bank : bankManager.getAllBanks()) {
            String id = bank.getBankId();
            double er = invokeDouble(bank, "getReservesExcess", bank.getReserves() - rrOf(bank));
            double rr = Math.max(0, rrOf(bank));
            double npl = invokeDouble(bank, "getNplRatio", 0.0);
            nplRatioCache.put(id, npl);
            TroubledBankState current = troubledBankStates.getOrDefault(id, TroubledBankState.HEALTHY);
            TroubledBankState next = computeNextState(bank, current, er, rr, npl, interbankSupply);
            if (next != current) {
                troubledBankStates.put(id, next);
                troubledStateTransitions++;
                transitions++;
                recordPolicyEvent(PolicyEvent.TROUBLED_STATE, 0, policySourceOrClosed());
            } else {
                troubledBankStates.putIfAbsent(id, current);
            }
            // §6.10.1：把状态推送给银行侧 ⇒ 自动带出 getLendingCapMultiplier()/isDividendBlocked()（t2 入口）
            invokeVoid(bank, "setTroubledState", new Class<?>[]{TroubledBankState.class},
                    new Object[]{troubledBankStates.getOrDefault(id, TroubledBankState.HEALTHY)});
        }
        return transitions;
    }

    /** 单行的下一状态（§6.10.1 的确定性转移表）。 */
    private TroubledBankState computeNextState(Bank bank, TroubledBankState current, double er, double rr,
                                               double npl, double interbankSupply) {
        if (bank.getEquity() <= 0) return TroubledBankState.RESOLVED;
        if (current == TroubledBankState.RESOLVED) return TroubledBankState.RESOLVED; // 约束永久保留（不破产清算）
        if (current == TroubledBankState.RESCUED) {
            // ★CB-T27（captain）：`er` 参数取自 `Bank.getReservesExcess()`，而该字段已被
            // `recomputeReserveDecomposition()` **钳制为 max(0, reserves − RR) ≥ 0** ⇒ 原判据
            // `er < 0` **恒假**、`rescuedStreak` 永不递增 ⇒ §6.10.1 的 `RESCUED → RESTRICTED`
            // 在可构造条件下**不可达**（t8 实测：RESCUED + reserves=0 + RR=350,000 连续 2 日仍
            // RESCUED、transitions=0）。⇒ 改用**未钳制口径**：`reserves < RR` 即"救助后仍 ER<0"。
            boolean stillNegativeEr = bank.getReserves() < rr - 1e-9;
            if (stillNegativeEr) {
                int streak = rescuedStreak.merge(bank.getBankId(), 1, Integer::sum);
                if (streak >= LastResortRecord.RESTRICTED_CONSECUTIVE_DAYS) {
                    return TroubledBankState.RESTRICTED;
                }
                return TroubledBankState.RESCUED;
            }
            rescuedStreak.put(bank.getBankId(), 0);
            return TroubledBankState.RESCUED;
        }
        if (current == TroubledBankState.RESTRICTED) return TroubledBankState.RESTRICTED;
        boolean noInterbankSupply = interbankSupply >= 0 && interbankSupply <= 1e-9;
        if (noInterbankSupply && (er < 0 || bank.getReserves() < rr)) {
            return TroubledBankState.TROUBLED;
        }
        if (npl >= LastResortRecord.NPL_WATCH
                || (rr > 0 && er / rr < LastResortRecord.ER_WATCH_RATIO)) {
            return TroubledBankState.WATCH;
        }
        return TroubledBankState.HEALTHY;
    }

    /** 该行当前法定准备金（t2 未就绪时用 rr × 全口径存款兜底）。 */
    private double rrOf(Bank bank) {
        return invokeDouble(bank, "getReservesRequired", bank.getTotalDepositsAll() * reserveRequirement);
    }

    /** 问题银行状态（只读查表；未评估过的行返回 {@code HEALTHY}，不凭空造状态）。 */
    public TroubledBankState getTroubledBankState(String bankId) {
        return troubledBankStates.getOrDefault(bankId, TroubledBankState.HEALTHY);
    }

    /** 问题银行状态表（只读视图，确定性顺序 = 首次评估序）。 */
    public Map<String, TroubledBankState> getTroubledBankStates() {
        return Collections.unmodifiableMap(troubledBankStates);
    }

    /** 状态转移次数（CB-23①）。 */
    public long getTroubledStateTransitions() { return troubledStateTransitions; }

    /** 最近一次已知的不良率缓存（诊断；只读）。 */
    public Map<String, Double> getNplRatioCache() { return Collections.unmodifiableMap(nplRatioCache); }

    /**
     * 最后贷款人条款化救助（§6.10.2；<b>代价四件套</b>）。
     *
     * <p>四件套逐条落地：
     * <ol>
     *   <li>抵押品折价：{@code loan ≤ collateralValue × (1 − HAIRCUT=0.30)}；</li>
     *   <li>惩罚利率：{@code rate == SLF + LLR_SPREAD(0.02)}（逐位）；</li>
     *   <li>放贷与分红受限：救助后置 {@code RESCUED}（上限 8 倍 + 禁分红），并登记 30 交易日降杠杆；</li>
     *   <li>公开登记：写 1 条 {@link LastResortRecord}（环 ≤ 64）并写 1 条 {@link PolicyEvent#LAST_RESORT}。</li>
     * </ol>
     * 额度与次数：单次 ≤ {@code min(equity × 2, collateral × 0.7)}；累计 ≤ {@link LastResortRecord#LLR_COUNT_MAX}。
     *
     * @param bank            被救助行
     * @param requestedAmount 申请额度
     * @param collateralValue 抵押品估值
     * @param tick            引擎 tick
     * @return 实际救助额度（0 = 拒绝：无抵押/超次数/资不抵债）
     */
    public double provideLastResortLending(Bank bank, double requestedAmount, double collateralValue, long tick) {
        if (bank == null || !(requestedAmount > 0) || !isLastResortEffective()) return 0;
        String id = bank.getBankId();
        int count = lastResortCountByBank.getOrDefault(id, 0);
        if (count >= LastResortRecord.LLR_COUNT_MAX) return 0;
        double collateralRoom = Math.max(0, collateralValue) * LastResortRecord.COLLATERAL_LOAN_RATIO_MAX;
        double equityRoom = Math.max(0, bank.getEquity()) * LastResortRecord.AMOUNT_EQUITY_MULTIPLE_MAX;
        double room = Math.min(collateralRoom, equityRoom);
        double amount = Math.min(requestedAmount, room);
        if (!(amount > 0)) return 0;
        double rate = standingLendingFacilityRate + LastResortRecord.LLR_SPREAD;
        TroubledBankState before = getTroubledBankState(id);
        applyLastResortBalanceEffects(bank, amount, id, tick);
        lastResortCountByBank.put(id, count + 1);
        lastResortLendingOutstanding += amount;
        TroubledBankState after = TroubledBankState.RESCUED;
        troubledBankStates.put(id, after);
        // ★CB-T43（t37 评审 F2 阻断项修复）：救助**当刻**把状态推送给银行侧（与 `evaluateTroubledBanks`
        //   的推送同一机制）⇒ 使 `Bank.getLendingCapMultiplier()` 在救助后**同一刻**即为 **8.0**
        //   （= `TroubledBankState.RESCUED(8.0,true)`、§6.10.2③、§11 CB-23②）。
        // 修复前：本路径只更新 `troubledBankStates`、**不推送** ⇒ 银行侧仍持 `TROUBLED`（cap = 6.0），
        //   而同一刻已写入 `LastResortRecord.stateAfter = RESCUED` ⇒ **同刻两处真相**（归档 L400 cap=6 与 L401 state=RESCUED 并存）。
        // 边界（刻意不动）：§6.10.1 状态转移表与 `computeNextState`/`rescuedStreak` 逻辑**一字未改**
        //   ⇒ `RESCUED → RESTRICTED`（t27 修复项）仍可达（回归护栏见 `.verify/CB_T43_FIXES.md` §3-C）。
        invokeVoid(bank, "setTroubledState", new Class<?>[]{TroubledBankState.class}, new Object[]{after});
        rescuedStreak.put(id, 0);
        deleveragingDeadlineByBank.put(id, tick + LastResortRecord.DELEVERAGING_DAYS);
        if (before != after) {
            troubledStateTransitions++;
        }
        LastResortRecord record = new LastResortRecord(id, amount, rate, Math.max(0, collateralValue),
                LastResortRecord.HAIRCUT, tick, count + 1, before, after,
                tick + LastResortRecord.DELEVERAGING_DAYS);
        lastResortRecords.add(record);
        while (lastResortRecords.size() > LastResortRecord.LLR_RECORD_RING) {
            lastResortRecords.remove(0);
        }
        recordPolicyEvent(PolicyEvent.LAST_RESORT, amount, policySourceOrClosed());
        if (before != after) {
            recordPolicyEvent(PolicyEvent.TROUBLED_STATE, 0, policySourceOrClosed());
        }
        return amount;
    }

    /** 救助的账务与银行侧两侧对称入账（央行债权 + 准备金；银行 ER 同增）。 */
    private void applyLastResortBalanceEffects(Bank bank, double amount, String bankId, long tick) {
        bankReserves.merge(bankId, amount, Double::sum);
        bank.addEmergencyReserves(amount);
        book(BaseMoneyFlow.Kind.LAST_RESORT, amount, bankId, tick);
    }

    /** 公开救助登记环（只读视图；≤ {@link LastResortRecord#LLR_RECORD_RING} 条，确定性顺序）。 */
    public List<LastResortRecord> getLastResortRecords() {
        return Collections.unmodifiableList(lastResortRecords);
    }

    /** 最后贷款人介入次数（Q7）。 */
    public long getLastResortLendingCount() {
        long total = 0;
        for (int c : lastResortCountByBank.values()) total += c;
        return total;
    }

    /** 最后贷款人未偿还余额（Q7；本期为累计投放额，还款路径登记为未做）。 */
    public double getLastResortLendingOutstanding() { return lastResortLendingOutstanding; }

    /** 单行累计救助次数（诊断；上限 3）。 */
    public int getLastResortCount(String bankId) { return lastResortCountByBank.getOrDefault(bankId, 0); }

    /** 单行降杠杆截止交易日（救助后 30 交易日）。 */
    public long getDeleveragingDeadline(String bankId) { return deleveragingDeadlineByBank.getOrDefault(bankId, 0L); }

    // ============================================================
    // CB-t3：财政-货币互动（§7.2 C6 / Q6 赤字货币化）
    // ============================================================

    /**
     * 财政支出钩子（{@code Government.spend} 调用）。
     *
     * <p><b>口径（登记，关键 —— 防"同一笔双记"）</b>：§7.2 C6 明示 {@code govDeposits} 是
     * {@code Government.treasury} 的<b>镜像</b>，因此国库余额的权威维护点是
     * {@link #syncBalanceSheet} 里的 {@code setGovernmentDepositsMirror(...)}（每交易日一次）；
     * 本钩子<b>只登记事件</b>（可审计、不静默），<b>不改账本科目</b> —— 若在此处再记一次
     * {@code FISCAL_SPEND} 的科目变化，会与实际同步叠加成双记（I8 破）。
     */
    public void onFiscalSpend(double amount) {
        if (!(amount > 0) || !isBalanceSheetEffective()) return;
        recordFlowTrail(BaseMoneyFlow.Kind.FISCAL_SPEND, amount, "GOVERNMENT");
    }

    /** 税收钩子（{@code Government.receiveTax} 调用）：同 {@link #onFiscalSpend}，只登记事件不双记科目。 */
    public void onTaxCollected(double amount) {
        if (!(amount > 0) || !isBalanceSheetEffective()) return;
        recordFlowTrail(BaseMoneyFlow.Kind.TAX_COLLECT, amount, "GOVERNMENT");
    }

    /** 记一条"未改科目的流动痕迹"（财政镜像路径用；只进流水与偏差账户，不进 I8 两侧）。 */
    private void recordFlowTrail(BaseMoneyFlow.Kind kind, double amount, String counterparty) {
        balanceSheet.recordTrail(new BaseMoneyFlow(kind, amount, counterparty, lastBookedTick));
    }

    /**
     * 财政赤字货币化：央行直接买入政府债券（Q6；<b>默认关</b>，每次写代价）。
     *
     * <p>代价链条（§7.2/§13.14）：①基础货币 ↑（{@code GOV_BOND_BUY}）→ ②通胀（InflationSystem 既有传导）
     * → ③{@code erpFiscalDominancePremium += FD_ERP_PENALTY_PER_EVENT}（上限 0.03）→ ④估值响应（t5 注入）。
     *
     * @param faceValue 直接持有的国债面值
     * @return 实际买断面值（0 = 开关关闭或参数非法）
     */
    public double fiscalDominanceBondPurchase(double faceValue) {
        if (!fiscalDominanceEnabled || !(faceValue > 0)) return 0;
        fiscalDominanceCount++;
        erpFiscalDominancePremium = Math.min(FD_ERP_PENALTY_MAX,
                erpFiscalDominancePremium + FD_ERP_PENALTY_PER_EVENT);
        book(BaseMoneyFlow.Kind.GOV_BOND_BUY, faceValue, "GOVERNMENT", lastBookedTick);
        recordPolicyEvent(PolicyEvent.FISCAL_DOMINANCE, faceValue, policySourceOrClosed());
        return faceValue;
    }

    /** 财政赤字货币化次数（Q6）。 */
    public long getFiscalDominanceCount() { return fiscalDominanceCount; }

    /** ERP 惩罚加项（Q6；≤ 0.03）。 */
    public double getErpFiscalDominancePremium() { return erpFiscalDominancePremium; }

    // ============================================================
    // CB-t3：政策事件通道（P7；§9.5）
    // ============================================================

    /**
     * 写一条政策事件（有界环 64，确定性顺序；满环丢最旧）。
     *
     * @param kind  事件 kind（{@code null} 或空 ⇒ 不写：避免"零动作也留痕"）
     * @param delta 幅度（与 {@code getLastPolicyActionDelta()} 同口径）
     * @param source 决策来源（{@code PLAYER}/{@code AI_AUTOPILOT}/{@code GUARDRAIL_CLAMP}）
     * @return 实际写入的 tick（未写入返回 −1）
     */
    public long recordPolicyEvent(String kind, double delta, String source) {
        if (!isEventsEffective() || kind == null || kind.isEmpty()) return -1;
        String src = PolicyEvent.isKnownSource(source) ? source : PolicyEvent.SOURCE_AI_AUTOPILOT;
        PolicyEvent event = new PolicyEvent(lastBookedTick, kind, delta, src);
        policyEvents.add(event);
        while (policyEvents.size() > PolicyEvent.RING_CAPACITY) {
            policyEvents.remove(0);
        }
        lastPolicyAction = kind;
        lastPolicyActionTick = lastBookedTick;
        lastPolicyActionDelta = delta;
        policyActionCount++;
        return lastBookedTick;
    }

    /** 最近一次政策动作（{@code "RR_CUT"}/{@code "RATE_CUT"}/{@code "OMO_INJECT"}/…；无动作 = 空串）。 */
    public String getLastPolicyAction() { return lastPolicyAction; }

    /** 最近一次政策动作的 tick（无动作 = 0）。 */
    public long getLastPolicyActionTick() { return lastPolicyActionTick; }

    /** 最近一次政策动作的幅度。 */
    public double getLastPolicyActionDelta() { return lastPolicyActionDelta; }

    /** 累计政策动作数（诊断）。 */
    public long getPolicyActionCount() { return policyActionCount; }

    /** 泰勒规则建议利率（复用既有 {@link CentralBankerGuardrail#suggestedRate()}；§6.9）。 */
    public double getTaylorSuggestedRate() { return guardrail.suggestedRate(); }

    /** 政策决策来源（{@code PLAYER}/{@code AI_AUTOPILOT}/{@code GUARDRAIL_CLAMP}）。 */
    public String getPolicyDecisionSource() { return policyDecisionSource; }

    /** 最近一次被制度约束钳制的留痕说明（空串 = 未钳制）。 */
    public String getLastGuardrailClampNote() { return lastGuardrailClampNote; }

    /**
     * 最近 N 条政策事件（确定性顺序 = 时间升序；N ≤ 0 ⇒ 空表；N &gt; 环内条数 ⇒ 返回全部）。
     *
     * @param n 需要条数
     */
    public List<PolicyEvent> getRecentPolicyEvents(int n) {
        if (n <= 0 || policyEvents.isEmpty()) return List.of();
        int from = Math.max(0, policyEvents.size() - n);
        return List.copyOf(policyEvents.subList(from, policyEvents.size()));
    }

    /** 政策事件环当前条数（≤ 64）。 */
    public int getPolicyEventCount() { return policyEvents.size(); }

    /**
     * §9.5 的宏观不确定性跳跃查表（{@code POLICY_EVENT_JUMP[kind]}，确定性常数表）。
     *
     * <p>本类只<b>提供</b>该表，注入动作由 CB-t5 在 {@code MacroEnvironment} 侧落地
     * （写权矩阵：{@code MacroEnvironment} 归 t5）。
     *
     * @param kind 事件 kind
     * @return 该 kind 的不确定性跳跃增量（未知 kind ⇒ 0）
     */
    public static double policyEventJump(String kind) {
        if (kind == null) return 0;
        return switch (kind) {
            case PolicyEvent.RR_CUT, PolicyEvent.RR_HIKE -> 0.020;
            case PolicyEvent.RATE_CUT, PolicyEvent.RATE_HIKE -> 0.015;
            case PolicyEvent.OMO_INJECT, PolicyEvent.OMO_DRAIN -> 0.005;
            case PolicyEvent.MLF -> 0.006;
            case PolicyEvent.SLF_LEND -> 0.008;
            case PolicyEvent.LAST_RESORT -> 0.030;
            case PolicyEvent.FISCAL_DOMINANCE -> 0.040;
            case PolicyEvent.BILL_ISSUE -> 0.004;
            case PolicyEvent.CBDC_ADJUST -> 0.004;
            case PolicyEvent.TROUBLED_STATE -> 0.025;
            case PolicyEvent.TAKEOVER, PolicyEvent.RETURN -> 0.010;
            case PolicyEvent.MIGRATION -> 0.0;
            case PolicyEvent.IDENTITY_VIOLATION -> 0.0;
            case PolicyEvent.GUARDRAIL_CLAMP -> 0.0;
            default -> 0.0;
        };
    }

    // ============================================================
    // CB-t3：接管/交还（Q1；CB-21 可逆 + 事件日志）
    // ============================================================

    /**
     * 玩家接管央行（AI 暂停；Q1/CB-21）。<b>可逆</b>：由本类持有可切换状态，不用一次性静态标志。
     */
    public void setManualTakeover(boolean active) {
        if (this.manualTakeoverActive == active) return;
        this.manualTakeoverActive = active;
        this.takeoverTransitionCount++;
        guardrail.setCentralBankerOnline(active);
        recordPolicyEvent(active ? PolicyEvent.TAKEOVER : PolicyEvent.RETURN, 0,
                active ? PolicyEvent.SOURCE_PLAYER : PolicyEvent.SOURCE_AI_AUTOPILOT);
        policyDecisionSource = active ? PolicyEvent.SOURCE_PLAYER : PolicyEvent.SOURCE_AI_AUTOPILOT;
    }

    /** 玩家是否已接管（AI 暂停）。 */
    public boolean isManualTakeoverActive() { return manualTakeoverActive; }

    /** AI 是否在决策（未接管 ⇒ AI 自动驾驶）。 */
    public boolean isAiAutopilotActive() { return !manualTakeoverActive; }

    /** 接管/交还次数（可逆性证据，CB-21①）。 */
    public long getTakeoverTransitionCount() { return takeoverTransitionCount; }

    // ============================================================
    // CB-t3：持久化（§9.1/§9.3；新增字段一律 contains 兜底，读档不失败）
    // ============================================================

    /**
     * 保存资产负债表（由 {@code MarketEngine.saveFiat} 写进 {@code centralBank} 复合标签的
     * {@code "balanceSheet"} 子键）。<b>不新增顶层键、不改既有 {@code "baseMoney"} 键</b>（R1）。
     */
    public net.minecraft.nbt.CompoundTag saveBalanceSheet() { return balanceSheet.save(); }

    /** 读档恢复资产负债表（缺键用默认值兜底，读档不失败，CB-14）。 */
    public void loadBalanceSheet(net.minecraft.nbt.CompoundTag tag) {
        if (tag == null) return;
        balanceSheet.load(tag);
    }

    /** 保存政策态（由 {@code MarketEngine.saveFiat} 写进 {@code centralBank} 标签的 {@code "policy"} 子键）。 */
    public net.minecraft.nbt.CompoundTag savePolicyState() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putDouble("policyRate", policyRate);
        tag.putDouble("slf", standingLendingFacilityRate);
        tag.putDouble("ioer", interestOnExcessReservesRate);
        tag.putDouble("lpr", lprOneYear);
        tag.putDouble("govBondYield", governmentBondYield);
        tag.putDouble("omoOutstanding", omoOutstanding);
        tag.putDouble("mlfOutstanding", mlfOutstanding);
        tag.putDouble("reloanOutstanding", reloanOutstanding);
        tag.putDouble("rediscountOutstanding", rediscountOutstanding);
        tag.putDouble("slfOutstanding", slfOutstanding);
        tag.putLong("fiscalDominanceCount", fiscalDominanceCount);
        tag.putDouble("erpFdPremium", erpFiscalDominancePremium);
        tag.putLong("takeoverTransitions", takeoverTransitionCount);
        tag.putBoolean("manualTakeover", manualTakeoverActive);
        tag.putString("lastPolicyAction", lastPolicyAction == null ? "" : lastPolicyAction);
        tag.putLong("lastPolicyActionTick", lastPolicyActionTick);
        tag.putDouble("lastPolicyActionDelta", lastPolicyActionDelta);
        tag.putString("policyDecisionSource", policyDecisionSource == null ? "" : policyDecisionSource);
        tag.putLong("troubledTransitions", troubledStateTransitions);
        tag.putLong("llrOutstanding", (long) lastResortLendingOutstanding);
        net.minecraft.nbt.CompoundTag states = new net.minecraft.nbt.CompoundTag();
        for (Map.Entry<String, TroubledBankState> e : troubledBankStates.entrySet()) {
            states.putString(e.getKey(), e.getValue().name());
        }
        tag.put("troubledStates", states);
        net.minecraft.nbt.CompoundTag counts = new net.minecraft.nbt.CompoundTag();
        for (Map.Entry<String, Integer> e : lastResortCountByBank.entrySet()) {
            counts.putInt(e.getKey(), e.getValue());
        }
        tag.put("llrCounts", counts);
        return tag;
    }

    /** 读档恢复政策态（逐键 contains 兜底；旧档缺键保持默认，读档不失败）。 */
    public void loadPolicyState(net.minecraft.nbt.CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("policyRate")) this.policyRate = tag.getDouble("policyRate");
        if (tag.contains("slf")) this.standingLendingFacilityRate = tag.getDouble("slf");
        if (tag.contains("ioer")) this.interestOnExcessReservesRate = tag.getDouble("ioer");
        if (tag.contains("lpr")) this.lprOneYear = tag.getDouble("lpr");
        if (tag.contains("govBondYield")) this.governmentBondYield = tag.getDouble("govBondYield");
        if (tag.contains("omoOutstanding")) this.omoOutstanding = tag.getDouble("omoOutstanding");
        if (tag.contains("mlfOutstanding")) this.mlfOutstanding = tag.getDouble("mlfOutstanding");
        if (tag.contains("reloanOutstanding")) this.reloanOutstanding = tag.getDouble("reloanOutstanding");
        if (tag.contains("rediscountOutstanding")) {
            this.rediscountOutstanding = tag.getDouble("rediscountOutstanding");
        }
        if (tag.contains("slfOutstanding")) this.slfOutstanding = tag.getDouble("slfOutstanding");
        if (tag.contains("fiscalDominanceCount")) this.fiscalDominanceCount = tag.getLong("fiscalDominanceCount");
        if (tag.contains("erpFdPremium")) this.erpFiscalDominancePremium = tag.getDouble("erpFdPremium");
        if (tag.contains("takeoverTransitions")) this.takeoverTransitionCount = tag.getLong("takeoverTransitions");
        if (tag.contains("manualTakeover")) this.manualTakeoverActive = tag.getBoolean("manualTakeover");
        if (tag.contains("lastPolicyAction")) this.lastPolicyAction = tag.getString("lastPolicyAction");
        if (tag.contains("lastPolicyActionTick")) this.lastPolicyActionTick = tag.getLong("lastPolicyActionTick");
        if (tag.contains("lastPolicyActionDelta")) this.lastPolicyActionDelta = tag.getDouble("lastPolicyActionDelta");
        if (tag.contains("policyDecisionSource")) this.policyDecisionSource = tag.getString("policyDecisionSource");
        if (tag.contains("troubledTransitions")) this.troubledStateTransitions = tag.getLong("troubledTransitions");
        if (tag.contains("llrOutstanding")) this.lastResortLendingOutstanding = tag.getLong("llrOutstanding");
        if (tag.contains("troubledStates")) {
            net.minecraft.nbt.CompoundTag states = tag.getCompound("troubledStates");
            for (String key : states.getAllKeys()) {
                try {
                    troubledBankStates.put(key, TroubledBankState.valueOf(states.getString(key)));
                } catch (IllegalArgumentException ignored) {
                    // 旧档脏值：跳过，不失败
                }
            }
        }
        if (tag.contains("llrCounts")) {
            net.minecraft.nbt.CompoundTag counts = tag.getCompound("llrCounts");
            for (String key : counts.getAllKeys()) {
                lastResortCountByBank.put(key, counts.getInt(key));
            }
        }
        if (tag.contains("policyRate")) {
            this.discountRate = this.policyRate;
            this.omoRate = this.policyRate;
        }
    }

    /** 旧档迁移留痕（§9.1；{@code PolicyEvent(kind="MIGRATION")}）。 */
    public void recordMigrationEvent(double legacyBaseMoney, double balancingAmount) {
        recordPolicyEvent(PolicyEvent.MIGRATION, balancingAmount, policySourceOrClosed());
        migrationLegacyBaseMoney = legacyBaseMoney;
        migrationBalancingAmount = balancingAmount;
    }

    private double migrationLegacyBaseMoney = 0;
    private double migrationBalancingAmount = 0;

    /** 旧档迁移的原始 {@code baseMoney} 与配平额（诊断/证据用）。 */
    public double getMigrationLegacyBaseMoney() { return migrationLegacyBaseMoney; }

    /** 旧档迁移的配平额（进 {@code ownCapital}）。 */
    public double getMigrationBalancingAmount() { return migrationBalancingAmount; }

    /**
     * 旧档迁移（§9.1①③）：把标量 {@code baseMoney} 落到账本 {@code reserveMoney}，差额进 {@code ownCapital}，
     * 使 I8 立即成立；并写 {@code PolicyEvent(MIGRATION)} 留痕。确定性、无随机。
     *
     * @param legacyBaseMoney 旧档读到的 {@code "baseMoney"}（0 表示旧档无该键 ⇒ 不迁移）
     * @return 配平额（= ownCapital）
     */
    public double migrateLegacyLedger(double legacyBaseMoney) {
        balanceSheet.migrateFromLegacyScalar(legacyBaseMoney);
        double balancing = balanceSheet.getOwnCapital();
        recordMigrationEvent(legacyBaseMoney, balancing);
        return balancing;
    }

    // ============================================================
    // 内部工具：反射只读访问（跨任务解耦；t2 未交付时回落兜底值，不静默改数）
    // ============================================================

    /** 反射方法缓存（确定性；避免每次调用都做 {@code getMethod} 查找）。 */
    private final Map<String, Method> reflectedMethods = new HashMap<>();

    /** 反射读一个 {@code double} getter；方法不存在/调用失败 ⇒ 返回兜底值（不抛、不静默改数）。 */
    private double invokeDouble(Object target, String name, double fallback) {
        Method m = resolveMethod(target.getClass(), name);
        if (m == null) return fallback;
        try {
            Object value = m.invoke(target);
            return (value instanceof Number n) ? n.doubleValue() : fallback;
        } catch (ReflectiveOperationException e) {
            return fallback;
        }
    }

    /** 反射调一个 {@code void} 方法；返回是否成功调用（false ⇒ 该入口尚未就绪）。 */
    private boolean invokeVoid(Object target, String name, Class<?>[] paramTypes, Object[] args) {
        String key = target.getClass().getName() + "#" + name + "/" + paramTypes.length;
        Method m = reflectedMethods.get(key);
        if (m == null) {
            try {
                m = target.getClass().getMethod(name, paramTypes);
                reflectedMethods.put(key, m);
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
        try {
            m.invoke(target, args);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** 解析一个无参 getter（缓存）。 */
    private Method resolveMethod(Class<?> type, String name) {
        String key = type.getName() + "#" + name;
        if (reflectedMethods.containsKey(key)) return reflectedMethods.get(key);
        Method found = null;
        try {
            found = type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            // 未就绪：保持 null（调用方回落兜底值）
        }
        reflectedMethods.put(key, found);
        return found;
    }
}
