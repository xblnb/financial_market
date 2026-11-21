package com.example.financialmarket.engine.monetary;

import com.example.financialmarket.engine.banking.Bank;
import com.example.financialmarket.engine.banking.BankManager;
import com.example.financialmarket.engine.banking.CentralBank;

import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * 货币分层聚合器（CENTRAL_BANK_SPEC.md v1.5 §5.1；CB-t1 冻结签名，<b>CB-t2 实现</b>）。
 *
 * <p>定位：解决 §1.6 P1「{@code M0} 一名两义」并让货币乘数可复算（CB-1/CB-2/CB-17）。
 * <b>本类只做聚合与校验，不持有资金、不新建平行账本</b>（§5.1 硬要求）：所有分项都从既有账本读出
 * （玩家现金供应器、{@code Bank.vaultCash}/{@code reservesRequired}/{@code reservesExcess}、备付金分类层）。
 *
 * <p><b>纯 Java 纪律（§5.1）</b>：本类<b>不引用任何 Minecraft 类型</b>，可在 headless 探针中直接实例化与反射调用；
 * 玩家现金经 {@link DoubleSupplier} 注入（无 {@code ServerLevel} 依赖）。
 *
 * <p><b>★口径决策（CB-t2 登记，评审据此判定）</b>
 * <ol>
 *   <li>{@link #getBaseMoneyB()} = <b>I1 右侧之和</b>（{@code C + Vc + RR + ER + Pay}）——§3.1 对该 getter 的逐字定义；
 *       而 I10 的 {@code outstandingB（system 记账）} 另由 {@link #getOutstandingBookedB()} 给出（t2 阶段 = 既有
 *       {@code CentralBank.getBaseMoney()} 标量；t3 起 = 资产负债表 {@code reserveMoney}）。
 *       ⇒ 两者之差即 {@link #getDivergenceAccount()}：**t2 阶段该值不为 0 是真实账实差**
 *       （既有标量 10,000,000 与分量和 ≈ 银行准备金+库存现金 之间的历史差额），由 CB-t3 的迁移/配平
 *       按 §9.1④ 以配平流动 {@code absorb} 归零（这正是 I10 存在的目的，**不得静默**）。</li>
 *   <li>I1/I2/I3/I4/I5 在「B := 右侧之和」口径下是**定义式恒等**（误差恒 0）；真正的对账由 I10（账实差）与
 *       I6（乘数自洽）承担。此处如实登记，避免「恒等式恒真」被误读为"校验有效"。</li>
 *   <li>I7（走廊）与 I8（资产负债表）需要本类无法读取的外部状态 ⇒ 由引擎按日注入快照
 *       （{@link #setCorridorSnapshot}/{@link #setBalanceSheetSnapshot}）；未注入时该两条为
 *       <b>「不可评定」</b>（{@link #isIdentityEvaluable(int)} = false、{@link #isIdentityOk(int)} = false ⇒
 *       **绝不假通过**），注入后自动参与校验。</li>
 * </ol>
 *
 * <p>确定性：零随机、零时钟、零 I/O；同一输入重复 {@link #refresh} 结果逐位一致。
 */
public final class MonetaryAggregator {

    /** §2 默认绝对容差。 */
    public static final double EPS = 1e-6;
    /** I5（定义式）容差：绝对 1e-9。 */
    public static final double DEFINITION_EPS = 1e-9;
    /** I6（比式）容差：相对 1e-9。 */
    public static final double RATIO_EPS = 1e-9;
    /** 恒等式条数（I1..I10）。 */
    public static final int IDENTITY_COUNT = 10;
    /** 分母兜底（避免 0 除）：比率在分母 ≤ 该值时按定义返回 0。 */
    private static final double DENOM_FLOOR = 1e-9;

    // ===================== 恒等式判据表（CB-t1 冻结，勿改）=====================

    /**
     * 恒等式判据（§2 冻结清单 A 的机械可读落地；CB-t1 冻结，CB-t7 直接查表断言）。
     *
     * @param id            恒等式编号 1..10（{@code getLastFailedIdentity()} 用同一编号）
     * @param name          中文名（报表/事件/报告用）
     * @param expression    公式（逐字摘自 §2，供报告与 UI 展示）
     * @param toleranceAbs  绝对容差（0 = 该式不适用绝对容差）
     * @param toleranceRel  相对容差（0 = 该式不适用相对容差）
     * @param checkTick     是否每 tick 校验（只读）
     * @param checkDay      是否每交易日校验（资金账务后，严格）
     * @param checkQuarter  是否每季报表校验
     * @param definedAt     定义处（§5.1/§5.2/§6.1/…）
     * @param note          例外与口径备注（如 I7 的 ZLB 例外、I9 的硬约束）
     */
    public record IdentitySpec(int id, String name, String expression, double toleranceAbs, double toleranceRel,
                               boolean checkTick, boolean checkDay, boolean checkQuarter,
                               String definedAt, String note) {
    }

    /** I1..I10 判据表（§2 逐字落地；顺序 = 编号顺序，确定性）。 */
    public static final List<IdentitySpec> IDENTITIES = List.of(
            spec(1, "基础货币分解",
                    "B = C + Vc + RR + ER + Pay", EPS, 0, true, true, false, "§5.1/§6.1",
                    "每 tick 只读校验 + 每交易日资金账务后严格校验；B 取 I1 右侧之和（§3.1）"),
            spec(2, "M0 口径",
                    "M0 = C（可选 CBDC：C = Cnote + CbDc）", EPS, 0, true, false, false, "§5.1",
                    "Q4：CbDc 计入 M0，但只改 M0 内部构成，不改 B 与 M2（CB-15）"),
            spec(3, "M1 分层构成",
                    "M1 = M0 + 单位活期 + 个人活期 + Pay；M1_1994 = M0 + 单位活期", EPS, 0, true, false, false,
                    "§5.1/§5.3", "2025 新口径（Q2）；旧口径作对照指标保留"),
            spec(4, "M2 分层构成",
                    "M2 = M1 + 单位定期 + 个人储蓄 + 其他存款", EPS, 0, true, false, false, "§5.1/§5.3",
                    "M2 ⊇ M1（定义式包含）"),
            spec(5, "准货币",
                    "QM = M2 − M1", DEFINITION_EPS, 0, true, false, false, "§5.1",
                    "定义式，应精确（容差取 1e-9）"),
            spec(6, "货币乘数",
                    "m = M2 / B", 0, RATIO_EPS, false, true, false, "§5.1/§5.3",
                    "相对容差 1e-9；B=0 返回 0 并计数（§1.7 D5，见 getMultiplierZeroDenominatorHits()）"),
            spec(7, "利率走廊",
                    "IOER ≤ 银行间利率（DR007 代理） ≤ SLF", DEFINITION_EPS, 0, false, true, false, "§6.3",
                    "零利率下限例外期由走廊快照的 zlb 标注并单独统计（§13.6），不得静默；未注入快照 ⇒ 不可评定"),
            spec(8, "央行资产负债表",
                    "Σ资产 = Σ负债 + 自有资金", EPS, 0, false, true, true, "§5.2/§6.1",
                    "reserveMoney = B（定义上）；快照未注入 ⇒ 不可评定（CB-t3 起由引擎注入）"),
            spec(9, "银行准备金达标",
                    "RR_i = rr_eff × reserveBase_i；reserves_i ≥ RR_i（否则 settlementDebt_i > 0）", DEFINITION_EPS, 0,
                    false, true, false, "§5.4/§6.2",
                    "RR_i ≥ 0 为硬约束（容差 0）；禁止 reserves_i < 0（A5）"),
            spec(10, "守恒 / 偏差账户（未配对流动净额）",
                    "divergenceAccount = 未配对流动净额（必须 0）；大数相减 outstandingB − (C+Vc+RR+ER+Pay) 已降级为只读诊断",
                    EPS, 0, true, false, false,
                    "§5.1/§6.1",
                    "★v1.6c/F-CB-2：判据 = 未配对流动净额；非 0 且未被 absorb(CANCEL|WRITE_OFF) 归零 ⇒ divergenceHits++ 并告警（CB-4 反例真做）；"
                            + "day-0 历史差额（具名例外「历史发行封装」）**不进本账户**、不得据以判 FAIL"));

    private static IdentitySpec spec(int id, String name, String expression, double toleranceAbs, double toleranceRel,
                                     boolean checkTick, boolean checkDay, boolean checkQuarter,
                                     String definedAt, String note) {
        return new IdentitySpec(id, name, expression, toleranceAbs, toleranceRel, checkTick, checkDay, checkQuarter,
                definedAt, note);
    }

    /** 按编号取判据（1..10）；越界返回 {@code null}（只读查表，不抛异常，便于报表容错）。 */
    public static IdentitySpec specOf(int id) {
        for (IdentitySpec s : IDENTITIES) {
            if (s.id() == id) return s;
        }
        return null;
    }

    // ===================== 聚合缓存（唯一写入者：refresh / 账务即时重算）=====================

    private double circulationCash;             // C   = Cnote + CbDc
    private double noteCash;                    // Cnote（供应器注入的实体现金）
    private double cbdcOutstanding;             // CbDc（★CB-T26：由央行账本驱动；见 setCbdcOutstanding）
    private boolean cbdcSwitchEnabled = true;   // ★CB-T26：centralBank.cbdc 开关（false ⇒ CbDc 不计入 C）
    private double lastNoteSupply;              // ★CB-T26：最近一次 refresh 记录的实体现金供应器值（供 setter 标定）
    private boolean cbdcFromLedger;             // ★CB-T26：CbDc 是否来自央行账本（诊断，防"绕过记账"复活）
    private double vaultCashTotal;              // Vc
    private double requiredReservesTotal;       // RR
    private double excessReservesTotal;         // ER
    private double paymentInstitutionDeposits;  // Pay
    private double baseMoneyB;                  // B   = I1 右侧之和
    private double outstandingBookedB;          // I10 的 outstandingB（系统记账的 B）
    private double m0;
    private double m1;
    private double m1Legacy1994;
    private double m2;
    private double quasiMoneyQM;
    private double moneyMultiplierM;
    private double cashLeakageRatioC;
    private double excessReserveRatioE;
    private double divergenceAccount;
    private double absorbedBalancingTotal;      // 已 absorb 的配平累计（CANCEL/WRITE_OFF）
    private double lastAbsorbedAmount;
    private double prevBookedB;                 // 上次刷新的记账侧 B（诊断用）
    private double prevComponentsB;             // 上次刷新的 I1 右侧和
    /**
     * 上次刷新的「未配对流动净额」来源值（逐次差分累积的基准；F-CB-2）。
     *
     * <p>来源 = 央行资产负债表（{@code bookBaseMoneyFlow} 的唯一持有者）。
     */
    private double prevDivergenceSource;
    private boolean firstAggregate = true;
    private long divergenceHits;
    private long aggregateRefreshTick;
    private long multiplierZeroDenominatorHits;
    private int lastFailedIdentity;
    private boolean aggregationDegraded;
    private boolean aggregationEnabled = true;

    // 逐条恒等式的最近一次最大误差（不可评定时为 NaN）
    private final double[] identityMaxError = new double[IDENTITY_COUNT + 1];
    private final boolean[] identityEvaluable = new boolean[IDENTITY_COUNT + 1];

    // 注入的输入（refresh 时记录；validateIdentities 用同一输入复算，保证「校验读到的是同一时刻的账本」）
    private CentralBank centralBankRef;
    private BankManager bankManagerRef;
    private DoubleSupplier cashSupplierRef;
    private boolean armed;

    // 引擎按日注入的外部快照（I7/I8；未注入 ⇒ 该条不可评定，绝不假通过）
    private boolean corridorSnapshotSet;
    private double corridorLower = Double.NaN;
    private double corridorUpper = Double.NaN;
    private double corridorRate = Double.NaN;
    private boolean corridorZlb;
    private long corridorZlbCount;
    private boolean balanceSheetSnapshotSet;
    private double balanceSheetAssets = Double.NaN;
    private double balanceSheetLiabilitiesAndCapital = Double.NaN;

    // ===================== 聚合入口 =====================

    /**
     * 每交易日刷新聚合（§5.1 注入点；纯确定性和，迭代序无关）。
     *
     * <p>同时执行 I1..I10 校验（§8.3⑩：{@code refresh + validateIdentities}），失败计数与最近失败编号即时更新。
     *
     * @param cb               央行（{@code getBaseMoney()} = 系统记账的 outstandingB，只读）
     * @param bm               银行管理器（各银行存款/准备金分类账的聚合源，只读）
     * @param circulationCash  流通中现金（实体现金 Cnote）供应器；{@code null} ⇒ 按 0 计并置降级标志（§5.1，不得静默）
     */
    public void refresh(CentralBank cb, BankManager bm, DoubleSupplier circulationCash) {
        this.centralBankRef = cb;
        this.bankManagerRef = bm;
        this.cashSupplierRef = circulationCash;
        this.armed = (cb != null && bm != null);
        aggregate();
        validateIdentities();
    }

    /** 引擎 tickCounter（供 {@link #getAggregateRefreshTick()}；由引擎每 tick/每日注入）。 */
    public void setTickCounter(long tick) { this.aggregateRefreshTick = tick; }

    /** 是否已接线（refresh 至少成功调用过一次；未接线时 {@link #validateIdentities()} 明确报错，不假通过）。 */
    public boolean isArmed() { return armed; }

    /** 分层聚合开关（§4：由 {@code MarketEngine.setCentralBankEnabled} 传播；false 时 BankManager 回落基线口径）。 */
    public boolean isAggregationEnabled() { return aggregationEnabled; }

    /** 设置分层聚合开关（只读标志；行为回落在 {@code BankManager} 的门内）。 */
    public void setAggregationEnabled(boolean enabled) { this.aggregationEnabled = enabled; }

    /**
     * 走廊快照注入（I7；由引擎/银行间市场按日注入——见 {@code .verify/CB_INTERFACE_FREEZE.md} §3.1 的 t2 扩展登记）。
     *
     * @param lower        走廊下限（IOER）
     * @param upper        走廊上限（SLF）
     * @param interbankRate 当日出清价（DR007 代理）
     * @param zlbException 零利率下限例外（true 时 I7 不作失败判定，但单独计数）
     */
    public void setCorridorSnapshot(double lower, double upper, double interbankRate, boolean zlbException) {
        this.corridorLower = lower;
        this.corridorUpper = upper;
        this.corridorRate = interbankRate;
        this.corridorZlb = zlbException;
        this.corridorSnapshotSet = true;
        if (zlbException) this.corridorZlbCount++;
    }

    /** 走廊快照是否已注入（未注入 ⇒ I7 不可评定）。 */
    public boolean isCorridorSnapshotSet() { return corridorSnapshotSet; }

    /** ZLB 例外累计次数（§13.6：必须单独统计，不得静默）。 */
    public long getCorridorZlbCount() { return corridorZlbCount; }

    /** 央行资产负债表快照注入（I8；由 CB-t3/CB-t5 每交易日注入）。 */
    public void setBalanceSheetSnapshot(double totalAssets, double totalLiabilitiesAndCapital) {
        this.balanceSheetAssets = totalAssets;
        this.balanceSheetLiabilitiesAndCapital = totalLiabilitiesAndCapital;
        this.balanceSheetSnapshotSet = true;
    }

    /** 资产负债表快照是否已注入（未注入 ⇒ I8 不可评定）。 */
    public boolean isBalanceSheetSnapshotSet() { return balanceSheetSnapshotSet; }

    /**
     * ★CBDC 开关（§4 {@code centralBank.cbdc}；由引擎注入，默认 true 同 §0.10 默认档）。
     *
     * <p>{@code false} ⇒ 聚合器<b>不计入</b> {@code CbDc}（{@code C = Cnote}），
     * 与基线的 {@code C} 逐位一致。
     */
    public void setCbdcSwitchEnabled(boolean enabled) { this.cbdcSwitchEnabled = enabled; }

    /** CBDC 开关是否计入流通中现金。 */
    public boolean isCbdcSwitchEnabled() { return cbdcSwitchEnabled; }

    /** CbDc 是否由央行账本驱动（false = 尚未接线/回落，可观测以防"绕过记账"的旧通道复活）。 */
    public boolean isCbdcFromLedger() { return cbdcFromLedger; }

    /**
     * ★CB-T26：未接线（未注入央行）时的 Cnote 修正量回落（= 0）。
     *
     * <p>接线后账本镜像已经是"实体现金构成"，本项不再适用 ⇒ 恒 0。
     * 保留方法是为了把口径写成可读的一行，而不是散落的魔法值。
     */
    private double cbdcLocalAdjustment() { return 0.0; }

    /**
     * 数字人民币存量（CbDc）设定——<b>★CB-T26 已收口到唯一记账入口</b>。
     *
     * <p><b>为何改（t8 实测的缺陷，CB-15 由 captain 撤销降档）</b>：旧实现只把 {@code d} 加进
     * {@code circulationCash} ⇒ {@code C}/{@code B}/{@code M2} 同升，而账本侧<b>无任何配对记账</b>
     * （{@code reserveMoney} 不动、无 CBDC 相关 {@code Kind}）⇒ 实测 {@code ΔM2 = +250,000}＝<b>凭空造钱</b>；
     * 且该 setter 是<b>全仓唯一能绕过 {@code bookBaseMoneyFlow} 的通道</b>。
     *
     * <p><b>新行为（唯一入口）</b>：
     * <ul>
     *   <li>已注入央行（{@link #refresh} 会注入）⇒ 本方法<b>不再直接改数</b>，而是委托央行把
     *       "目标存量"翻译为经 {@code bookBaseMoneyFlow} 的
     *       {@code CBDC_ISSUE}/{@code CBDC_REDEEM} 净额（配对记账；{@code ΔM2 = 0}）；</li>
     *   <li>未注入央行（未接线单元态）⇒ 记入本地回落字段并置 {@link #isCbdcFromLedger()} = false
     *       ——<b>如实可观测</b>，但该值在接线后一律以账本为准（不静默双写）。</li>
     * </ul>
     *
     * @param cbdc 目标 CbDc 存量（{@code ≤ 0} 视为 0 ⇒ 全部回笼）
     */
    public void setCbdcOutstanding(double cbdc) {
        double want = (Double.isFinite(cbdc) && cbdc > 0) ? cbdc : 0.0;
        if (centralBankRef != null) {
            // ★CB-T26：先把**已知的实体现金观测量**推给账本（单写者），再委托配对入口。
            //   否则账本处于"未观测"态（镜像 = 0），CBDC 的构成置换没有承载 ⇒ 会被标定路径接管
            //   （t8 实测的直设通道 FAIL 形态就源于此）。这里用最近一次 refresh 记录的供应器值。
            if (Double.isFinite(lastNoteSupply) && lastNoteSupply > 0) {
                centralBankRef.getBalanceSheet().syncNoteCashMirror(lastNoteSupply);
            }
            centralBankRef.setCbdcOutstanding(want);   // 唯一入口：成对记账（ΔM2 = 0）
            return;
        }
        this.cbdcOutstanding = want;                   // 未接线回落（可观测，不作判据）
        this.cbdcFromLedger = false;
    }

    // ===================== §3.1 冻结可观测面 =====================

    /** C：流通中现金（= M0，非银行部门持有）= Cnote + CbDc。 */
    public double getCirculationCash() { return circulationCash; }

    /** CbDc：数字人民币存量（Q4；计入 M0）。★CB-T26 起由央行账本驱动（{@link #isCbdcFromLedger()}）。 */
    public double getCbdcOutstanding() { return cbdcOutstanding; }

    /**
     * 实际计入 {@code C} 的 CbDc（= 开关打开时的 {@link #getCbdcOutstanding()}，否则 0）。
     *
     * <p>与 {@code C = Cnote + 本值} 严格一致，供探针做"开关两态"对照。
     */
    public double getCbdcCounted() { return cbdcSwitchEnabled ? cbdcOutstanding : 0.0; }

    /** Cnote：实体现金（C 中的非 CBDC 部分；= 供应器注入值）。 */
    public double getNoteCash() { return noteCash; }

    /** Vc：金融机构库存现金 = Σ {@code Bank.vaultCash}。 */
    public double getVaultCashTotal() { return vaultCashTotal; }

    /** RR：法定准备金合计 = Σ {@code Bank.reservesRequired}。 */
    public double getRequiredReservesTotal() { return requiredReservesTotal; }

    /** ER：超额准备金合计 = Σ {@code Bank.reservesExcess}。 */
    public double getExcessReservesTotal() { return excessReservesTotal; }

    /** Pay：支付机构客户备付金（Q2 新口径）。 */
    public double getPaymentInstitutionDeposits() { return paymentInstitutionDeposits; }

    /**
     * B：基础货币 / 储备货币 = <b>I1 右侧之和</b>（C + Vc + RR + ER + Pay，§3.1 逐字定义）。
     *
     * <p>P1 冻结新方法名（替代 {@code BankManager.computeM0()} 无参的「基础货币」旧义，§1.9(a)）。
     */
    public double getBaseMoneyB() { return baseMoneyB; }

    /**
     * I10 的 {@code outstandingB}：<b>系统记账</b>的基础货币（t2 阶段 = 既有 {@code CentralBank.getBaseMoney()}；
     * t3 起 = 资产负债表 {@code reserveMoney}）。
     */
    public double getOutstandingBookedB() { return outstandingBookedB; }

    /**
     * ★**只读诊断**：大数相减口径的账实差 {@code outstandingB − (C + Vc + RR + ER + Pay)}。
     *
     * <p><b>明文不作判据、不参与 PASS/FAIL</b>（F-CB-2 / v1.6c §2 I10 裁决）。
     * I10 的判据是 {@link #getDivergenceAccount()}（= **未配对流动净额**）。
     *
     * <p>其编制日（day-0）非零是**预期**的：该差额 = §13 具名例外「历史发行封装」
     * （本实例 **5,500,000** = `computeM0()` 10,000,000 − 权威 B 4,500,000），
     * 属历史既成事实，**不得**用 `CANCEL`/`WRITE_OFF` 吸收、**不得**据此判 FAIL。
     */
    public double getLedgerVsComponentsGap() {
        return outstandingBookedB - baseMoneyB;
    }

    /** M0 = I2（= C）。P1 冻结语义：M0 是流通中现金，<b>不等于</b> baseMoney（一义化）。 */
    public double getM0() { return m0; }

    /** M1 = I3（2025 新口径）。 */
    public double getM1() { return m1; }

    /** M1_1994 = I3 旧口径（对照指标，不参与 I6 与传导）。 */
    public double getM1Legacy1994() { return m1Legacy1994; }

    /** M2 = I4。 */
    public double getM2() { return m2; }

    /** QM：准货币 = M2 − M1（I5）。 */
    public double getQuasiMoneyQM() { return quasiMoneyQM; }

    /**
     * m：货币乘数 = M2 / B（I6，内生实测值）。
     *
     * <p>★与基线口径的差异（§1.7 D3）：基线 {@code BankManager.getMoneyMultiplier(playerCash)} 的分母是
     * <b>C（流通现金）</b>，保留为兼容口径 {@code getCashMultiplierLegacy(playerCash)}（逐位不变）；
     * 本方法的分母是 <b>B</b>。二者给同 run 对照表（CB-17③）。
     */
    public double getMoneyMultiplierM() { return moneyMultiplierM; }

    /** c：现金漏损率 = C /（M1 存款部分 + 定期 + 个人 + 其他）（仅口径）。 */
    public double getCashLeakageRatioC() { return cashLeakageRatioC; }

    /** e：超额准备金率 = ER / 达标存款基数。 */
    public double getExcessReserveRatioE() { return excessReserveRatioE; }

    /** I10 偏差账户（正常态必须为 0；非 0 同 tick 必须 absorb 归零）。 */
    public double getDivergenceAccount() { return divergenceAccount; }

    /** I10 告警计数（偏差非 0 且未 absorb 的次数）。 */
    public long getDivergenceHits() { return divergenceHits; }

    /** 最近一次日级聚合刷新的 tickCounter。 */
    public long getAggregateRefreshTick() { return aggregateRefreshTick; }

    /** I6 的 {@code B ≤ 0}（含非有限值）计数（§1.7 D5 补口；CB-17⑥ 判据锚点）。 */
    public long getMultiplierZeroDenominatorHits() { return multiplierZeroDenominatorHits; }

    /** 最近一次失败的恒等式编号（0 = 全部通过；1..10 = 对应 I 编号）。 */
    public int getLastFailedIdentity() { return lastFailedIdentity; }

    /** §5.1 降级标志：无 level/无玩家现金来源时置位（聚合仍确定性地按 0 现金计算，不得静默）。 */
    public boolean isAggregationDegraded() { return aggregationDegraded; }

    /** 由引擎在注入 0 现金供应器时置位（§5.1；纯标志读写）。 */
    public void setAggregationDegraded(boolean degraded) { this.aggregationDegraded = degraded; }

    /** 已 absorb 的配平累计（诊断：CB-4 的配平流动金额复核）。 */
    public double getAbsorbedBalancingTotal() { return absorbedBalancingTotal; }

    /** 最近一次 absorb 的金额（诊断）。 */
    public double getLastAbsorbedAmount() { return lastAbsorbedAmount; }

    /** I1..I10 的最近一次最大绝对误差（不可评定的条目返回 {@code NaN}）。 */
    public double getIdentityMaxError(int id) {
        if (id < 1 || id > IDENTITY_COUNT) return Double.NaN;
        return identityEvaluable[id] ? identityMaxError[id] : Double.NaN;
    }

    /** 该条恒等式是否可评定（I7/I8 需引擎注入快照；未注入 ⇒ false）。 */
    public boolean isIdentityEvaluable(int id) {
        return id >= 1 && id <= IDENTITY_COUNT && identityEvaluable[id];
    }

    /** I1..I10 中可评定的条数（1..10）。 */
    public int getEvaluableIdentityCount() {
        int n = 0;
        for (int i = 1; i <= IDENTITY_COUNT; i++) if (identityEvaluable[i]) n++;
        return n;
    }

    // ===================== 恒等式校验入口 =====================

    /**
     * I1..I10 全量校验（§2；按 {@link #IDENTITIES} 的节奏在 tick/日/季调用）。
     *
     * <p>任一**可评定**条目失败 ⇒ {@link #getLastFailedIdentity()} 记对应编号（最小失败编号）；
     * I10 偏差非 0 且未配平 ⇒ {@link #getDivergenceHits()} 递增（不得静默）。
     * 不可评定的条目（未注入快照的 I7/I8）不计入通过，{@link #isIdentityOk(int)} 返回 false。
     *
     * <p>使用 {@link #refresh} 记录的同一批输入复算 —— 保证「校验读到的是同一时刻的账本」，
     * 而不是过期缓存。未接线（从未 refresh）时明确抛错，**不返回 true**（禁止假绿灯）。
     *
     * @return true = 全部**可评定**条目通过（且至少有一条可评定）
     */
    public boolean validateIdentities() {
        if (!armed) {
            throw new IllegalStateException(
                    "MonetaryAggregator 未接线：必须先调用 refresh(cb, bm, supplier) 再校验"
                            + "（见 .verify/CB_INTERFACE_FREEZE.md §8.2 调用配方）");
        }
        aggregate();
        return evaluateAll();
    }

    /** 单条恒等式校验（1..10）；不可评定或越界返回 false（保守，不假通过）。 */
    public boolean validateIdentity(int id) {
        if (!armed) {
            throw new IllegalStateException(
                    "MonetaryAggregator 未接线：必须先调用 refresh(cb, bm, supplier) 再校验");
        }
        aggregate();
        evaluateAll();
        if (id < 1 || id > IDENTITY_COUNT || !identityEvaluable[id]) return false;
        return identityPasses(id);
    }

    /** 单条恒等式当前是否通过；不可评定或越界返回 false（保守，不假通过）。 */
    public boolean isIdentityOk(int id) {
        if (!armed) return false;
        aggregate();
        evaluateAll();
        if (id < 1 || id > IDENTITY_COUNT || !identityEvaluable[id]) return false;
        return identityPasses(id);
    }

    /**
     * 偏差账户归零：只接受配平流动 {@link BaseMoneyFlow.Kind#CANCEL}/{@link BaseMoneyFlow.Kind#WRITE_OFF}
     * （§6.1 I10 段；CB-4③ 的反例恢复路径）。
     *
     * <p>调用方须<b>同时</b>把该配平流动经 {@code CentralBankBalanceSheet.bookBaseMoneyFlow(flow)} 落到两侧科目，
     * 使「偏差归零」与「账目配平」是同一次操作的两面（不得只归零不配平）。
     *
     * @param flow 配平流动（kind 必须是 CANCEL 或 WRITE_OFF；amount &gt; 0）
     */
    public void absorb(BaseMoneyFlow flow) {
        if (flow == null) throw new IllegalArgumentException("absorb: flow 不得为 null");
        if (!flow.isBalancingFlow()) {
            throw new IllegalArgumentException("absorb: 只接受 CANCEL/WRITE_OFF，收到 " + flow.kind());
        }
        if (!armed) {
            throw new IllegalStateException(
                    "MonetaryAggregator 未接线：必须先调用 refresh(cb, bm, supplier) 再 absorb");
        }
        aggregate();
        double amount = divergenceAccount;
        this.absorbedBalancingTotal += amount;
        this.lastAbsorbedAmount = amount;
        this.divergenceAccount = 0.0;
        // ★两侧同时归零（F-CB-2）：偏差账户的**来源**是央行资产负债表（唯一改账入口的持有者）
        // ⇒ 归零必须写回来源，否则下一次 refresh 会把它再读回来（表现为"absorb 后又 FAIL"）。
        if (centralBankRef != null) {
            centralBankRef.getBalanceSheet().markDivergenceReconciled();
        }
        evaluateAll();
    }

    // ===================== 内部：聚合与判定 =====================

    /** 从既有账本重算全部派生量（确定性和，迭代序无关；无随机、无 I/O）。 */
    private void aggregate() {
        double note = 0.0;
        if (cashSupplierRef != null) {
            double v = cashSupplierRef.getAsDouble();
            if (Double.isFinite(v) && v > 0) note = v;
            this.aggregationDegraded = false;
        } else {
            this.aggregationDegraded = true;   // §5.1：无来源 ⇒ 按 0 计并如实置位
        }
        this.noteCash = note;
        this.lastNoteSupply = note;   // ★CB-T26：记录供应器观测值（供 CBDC setter 在委托前标定镜像）
        // ★CB-T26：CBDC 是**构成替代**（纸币 ↔ 数字人民币），三件事必须同时成立：
        //   ① 账本侧先接收观测到的**实体现金原始值**（单写者镜像同步）—— 这是 CBDC 扣减的基数；
        //   ② CbDc 与净投放由**央行账本**驱动（CBDC_ISSUE/CBDC_REDEEM 成对记账的唯一落点）；
        //   ③ 被替代掉的实体现金（账本 `cbdcAdjustment` = −净投放）从 Cnote 中扣除 ——
        //      否则 C 与 B 会同升（正是 t8 抓到的"凭空造钱"缺陷：实测 ΔM2 = +250,000）。
        // 由此 C = Cnote + CbDc 总量恒定 ⇒ ΔM2 = 0、ΔB = 0（CB-15 有效变体）。
        // 未注入央行（未接线单元态）⇒ 无替代量可扣除，回落本地值（可观测）。
        // ⚠ 单写者纪律：只推**原始值**；账本按「C = (原始值 − 净投放) + CbDc」自算构成，
        //   绝不把已扣减值再推给账本（否则会二次扣减 ⇒ 账本 C 与观测值分叉）。
        if (centralBankRef != null) {
            centralBankRef.getBalanceSheet().syncNoteCashMirror(note);
        }
        // 账本接线后：Cnote 取**账本口径**（镜像原始值 − 净投放），与账本逐位同源；
        // 未接线：直接用供应器值（无 CBDC 替代量可扣）。
        double adjustment = (centralBankRef != null)
                ? (centralBankRef.getNoteCashMirror() - note - centralBankRef.getCbdcNetIssued())
                : cbdcLocalAdjustment();
        if (!Double.isFinite(adjustment)) adjustment = 0.0;
        double ledgerCbdc = (centralBankRef != null) ? centralBankRef.getCbdcOutstanding() : 0.0;
        if (centralBankRef != null) {
            this.cbdcOutstanding = ledgerCbdc;
            this.cbdcFromLedger = true;
        }
        this.noteCash = Math.max(0.0, note + adjustment);
        double countedCbdc = cbdcSwitchEnabled ? this.cbdcOutstanding : 0.0;
        this.circulationCash = this.noteCash + countedCbdc;
        // ③ 账本 C 账户同步为**观测到的 C 总量**（单写者）：CBDC 的构成替代不改 C 总量
        //    ⇒ 账本 C 与观测 C 每轮对齐，成对记账的增量因此不会与观测值分叉。
        if (centralBankRef != null) {
            centralBankRef.getBalanceSheet().syncCirculationCashObserved(this.circulationCash);
        }

        double vc = 0, rr = 0, er = 0, pay = 0;
        double corp = 0, household = 0, time = 0, savings = 0, mm = 0, reserveBase = 0;
        if (bankManagerRef != null) {
            for (Bank b : bankManagerRef.getAllBanks()) {
                vc += b.getVaultCash();
                rr += b.getReservesRequired();
                er += b.getReservesExcess();
                pay += b.getPaymentReserve();
                corp += b.getDemandDepositsCorporate();
                household += b.getDemandDepositsHousehold();
                time += b.getTimeDeposits();
                savings += b.getSavingsHousehold();
                mm += b.getOtherMoneyMarket();
                reserveBase += b.getReserveBase();
            }
        }
        this.vaultCashTotal = vc;
        this.requiredReservesTotal = rr;
        this.excessReservesTotal = er;
        this.paymentInstitutionDeposits = pay;

        // I1：B := 右侧之和（§3.1 定义）
        this.baseMoneyB = this.circulationCash + vc + rr + er + pay;
        // ★I10 口径（F-CB-2 / T3-D2 / v1.6c 裁决）：divergenceAccount = **未配对流动净额**，
        // 由央行资产负债表（unique 改账入口 bookBaseMoneyFlow 的持有者）报告；
        // 「大数相减」口径（outstandingB − Σ五分量）**降级为只读诊断** {@link #getLedgerVsComponentsGap()}，
        // 明文不作判据。day-0 历史差额走 §13 具名例外「历史发行封装」，**不进本账户**。
        double componentsB = this.baseMoneyB;
        this.outstandingBookedB = (centralBankRef != null && Double.isFinite(centralBankRef.getReserveMoney()))
                ? centralBankRef.getReserveMoney() : 0.0;
        double unpairedNet = (centralBankRef != null) ? centralBankRef.getDivergenceAccount() : 0.0;
        if (!Double.isFinite(unpairedNet)) unpairedNet = 0.0;
        // **直接取值**（非差分累积）：央行资产负债表是 `bookBaseMoneyFlow` 的唯一持有者，
        // 其偏差账户已按「未配对流动净额」逐笔维护 ⇒ 聚合器口径**同步读取**即可，
        // 从而在 absorb 归零后立即回到 0（差分法会在 absorb 处产生符号反号的残留）。
        this.divergenceAccount = unpairedNet;
        this.prevDivergenceSource = unpairedNet;
        this.prevBookedB = this.outstandingBookedB;
        this.prevComponentsB = componentsB;
        this.firstAggregate = false;

        // I2 / I3 / I4 / I5
        this.m0 = this.circulationCash;
        this.m1 = this.m0 + corp + household + pay;
        this.m1Legacy1994 = this.m0 + corp;
        this.m2 = this.m1 + time + savings + mm;
        this.quasiMoneyQM = this.m2 - this.m1;

        // I6：m = M2 / B（B ≤ 0 或非有限 ⇒ 0 并计数，§1.7 D5）
        if (!(this.baseMoneyB > 0) || !Double.isFinite(this.baseMoneyB)) {
            this.moneyMultiplierM = 0.0;
            this.multiplierZeroDenominatorHits++;
        } else {
            this.moneyMultiplierM = this.m2 / this.baseMoneyB;
        }

        // c / e（仅口径）
        double depositTotal = corp + household + pay + time + savings + mm;
        this.cashLeakageRatioC = depositTotal > DENOM_FLOOR ? this.circulationCash / depositTotal : 0.0;
        this.excessReserveRatioE = reserveBase > DENOM_FLOOR ? er / reserveBase : 0.0;
    }

    /** 逐条计算误差、可评定性与通过性；返回「全部可评定条目通过」。 */
    private boolean evaluateAll() {
        int firstFailed = 0;

        // I1/I2/I3/I4/I5：定义式恒等（B/M0/M1/M2/QM 由右侧构造 ⇒ 误差 0）
        setSpec(1, true, Math.abs(baseMoneyB - (circulationCash + vaultCashTotal + requiredReservesTotal
                + excessReservesTotal + paymentInstitutionDeposits)), EPS, 0);
        setSpec(2, true, Math.abs(m0 - circulationCash), EPS, 0);
        setSpec(3, true, Math.abs(m1 - (m0 + sumCorp() + sumHousehold() + paymentInstitutionDeposits)), EPS, 0);
        setSpec(4, true, Math.abs(m2 - (m1 + sumTime() + sumSavings() + sumMoneyMarket())), EPS, 0);
        setSpec(5, true, Math.abs(quasiMoneyQM - (m2 - m1)), DEFINITION_EPS, 0);

        // I6：m = M2 / B
        if (!(baseMoneyB > 0) || !Double.isFinite(baseMoneyB)) {
            setSpecEvaluable(6, true);
            identityMaxError[6] = moneyMultiplierM == 0.0 ? 0.0 : Double.POSITIVE_INFINITY;
        } else {
            double expect = m2 / baseMoneyB;
            setSpec(6, true, Math.abs(moneyMultiplierM - expect), 0, RATIO_EPS);
        }

        // I7：走廊（需快照）
        if (corridorSnapshotSet) {
            setSpecEvaluable(7, true);
            if (corridorZlb) {
                identityMaxError[7] = 0.0;   // ZLB 例外：不作失败判定（已单独计数）
            } else {
                double err = Math.max(corridorLower - corridorRate, corridorRate - corridorUpper);
                identityMaxError[7] = Math.max(0.0, err);
            }
        } else {
            setSpecEvaluable(7, false);
        }

        // I8：资产负债表（需快照）
        if (balanceSheetSnapshotSet) {
            setSpec(8, true, Math.abs(balanceSheetAssets - balanceSheetLiabilitiesAndCapital), EPS, 0);
        } else {
            setSpecEvaluable(8, false);
        }

        // I9：逐行准备金达标（真实账本检查）
        if (bankManagerRef != null) {
            double worst = 0;
            for (Bank b : bankManagerRef.getAllBanks()) {
                double rrI = b.getReservesRequired();
                double res = b.getReserves();
                double debt = b.getSettlementDebt();
                // 硬约束违反（RR_i < 0 或 reserves_i < 0，A5）以 1.0 计入误差（远超容差 ⇒ 直接失败）
                if (rrI < -DEFINITION_EPS || res < -DEFINITION_EPS) worst = Math.max(worst, 1.0);
                // 未覆盖缺口 = max(0, RR_i − reserves_i − settlementDebt_i)
                worst = Math.max(worst, Math.max(0.0, rrI - res - debt));
            }
            setSpec(9, true, worst, DEFINITION_EPS, 0);
        } else {
            setSpecEvaluable(9, false);
        }

        // I10：守恒 / 偏差账户
        double absDiv = Math.abs(divergenceAccount);
        setSpec(10, true, absDiv, EPS, 0);
        if (!(absDiv < EPS)) divergenceHits++;   // 与判定口径一致：err == tol 视为不通过 ⇒ 计入告警

        for (int i = 1; i <= IDENTITY_COUNT; i++) {
            if (identityEvaluable[i] && !identityPasses(i)) {
                firstFailed = i;
                break;
            }
        }
        this.lastFailedIdentity = firstFailed;
        boolean anyEvaluable = getEvaluableIdentityCount() > 0;
        return anyEvaluable && firstFailed == 0;
    }

    private double sumCorp() { return sumBank(b -> b.getDemandDepositsCorporate()); }

    private double sumHousehold() { return sumBank(b -> b.getDemandDepositsHousehold()); }

    private double sumTime() { return sumBank(b -> b.getTimeDeposits()); }

    private double sumSavings() { return sumBank(b -> b.getSavingsHousehold()); }

    private double sumMoneyMarket() { return sumBank(b -> b.getOtherMoneyMarket()); }

    private double sumBank(java.util.function.ToDoubleFunction<Bank> f) {
        double s = 0;
        if (bankManagerRef != null) for (Bank b : bankManagerRef.getAllBanks()) s += f.applyAsDouble(b);
        return s;
    }

    private void setSpec(int id, boolean evaluable, double error, double tolAbs, double tolRel) {
        identityEvaluable[id] = evaluable;
        identityMaxError[id] = error;
    }

    private void setSpecEvaluable(int id, boolean evaluable) {
        identityEvaluable[id] = evaluable;
        if (!evaluable) identityMaxError[id] = Double.NaN;
    }

    /**
     * 单条通过判定。**边界口径（CB-t2 登记）**：采用<b>严格小于</b>（{@code err < tol} 通过、{@code err == tol} 不通过），
     * 以满足 CB-4④ 的偏差量扫描（注入 1e-6 必须 FAIL）；对连续量级而言与 §2 的「≤」除边界点外无实测差异。
     */
    private boolean identityPasses(int id) {
        if (!identityEvaluable[id]) return false;
        IdentitySpec s = specOf(id);
        double err = identityMaxError[id];
        if (Double.isNaN(err)) return false;
        if (s.toleranceAbs() > 0 && err < s.toleranceAbs()) return true;
        if (s.toleranceRel() > 0) {
            double ref = Math.max(1.0, Math.abs(identityReference(id)));
            return err < s.toleranceRel() * ref;
        }
        return false;
    }

    private double identityReference(int id) {
        switch (id) {
            case 6: return moneyMultiplierM;
            case 7: return Math.max(Math.abs(corridorRate), 1.0);
            case 8: return Math.max(Math.abs(balanceSheetAssets), 1.0);
            default: return 1.0;
        }
    }
}
