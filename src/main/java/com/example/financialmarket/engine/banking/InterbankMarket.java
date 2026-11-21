package com.example.financialmarket.engine.banking;

import com.example.financialmarket.engine.monetary.MonetaryAggregator;
import net.minecraft.nbt.CompoundTag;

import java.util.*;

/**
 * 银行间市场（CENTRAL_BANK_SPEC.md v1.6 §6.3 / P4 / CB-19；CB-t4 重写）。
 *
 * <p>本类解决 §1.6 P4 的三个根因：
 * <ol>
 *   <li><b>无利率走廊</b>：旧实现把利率硬编码钳制在一个与政策利率无关的固定区间（下限 50bp、上限 15%），
 *       与 SLF/IOER 完全无关
 *       ⇒ 现在走廊由央行参数化（{@link #getCorridorLower()}/{@link #getCorridorUpper()} 委托
 *       {@link CentralBank#getCorridorLowerRate()}/{@link CentralBank#getCorridorUpperRate()}），
 *       出清价恒落在 {@code IOER ≤ 银行间利率 ≤ SLF}（I7；ZLB 例外由 {@link #isZlbException()} 单独登记）。</li>
 *   <li><b>不撮合</b>：旧实现只算一个利率、不转移准备金 ⇒ 现在按 §6.3 做**确定性瓦尔拉斯出清**
 *       （需求 {@code d_i = max(0, RR_i − reserves_i)}、供给 {@code s_i = ER_i}、{@code Q* = min(Σd, Σs)}、
 *       单一价 {@code rate_raw = policyRate + halfWidth × (ratio − 1)}），然后**按比率分配并真实转移 ER**，
 *       产出成交量/参与行数/加权价。</li>
 *   <li><b>存款账本被 {@code null} 键污染</b>：旧实现的 {@code borrow} 以「null 客户 UUID」走
 *       {@code Bank.withdraw}/{@code Bank.deposit} ⇒ 向 {@code Bank.depositAccounts} 写入 {@code null} 键。
 *       现在改为**直接 ER 转移**（不触存款账本），并对 null/非法参数**拒绝且不改任何状态**。</li>
 * </ol>
 *
 * <p><b>开关与零回归（§4 / N1）</b>：走廊未接线（{@link #isCorridorWired()} = false）或
 * {@link #setCorridorEnabled(boolean)} = false 时，{@link #update(Collection)} 走
 * {@link #updateLegacy(Collection)} —— 与改动前公式**逐位一致**；旧公式常量以 {@code LEGACY_*} 具名常量保存
 * （**仅用于 off 态逐位复现**，不再出现在有效路径上）。
 *
 * <p><b>出清后的规范化量</b>：无论走哪条路径，{@link #getInterbankTotalDemand()}/{@link #getInterbankTotalSupply()}
 * 都按 **RR/ER 口径**刷新（供给 = Σ ER），使 §6.10.1 的 {@code TROUBLED} 触发条件（"银行间无可借 Σs = 0"）
 * 在走廊接线前即可被 {@code CentralBank.evaluateTroubledBanks(bankManager, Σs)} 读取。
 *
 * <p><b>ER 转移原语的登记（inScope 约束）</b>：本任务 inScope 只有本文件 ⇒ 只能使用 {@code Bank} 既有公共 API：
 * 借入行 {@code addEmergencyReserves(Δ)}（加准备金）、贷出行
 * {@code restoreCashPosition(vaultCash, reserves − Δ)}（绝对设置准备金）。二者都不触存款账本；
 * 建议后续任务在 {@code Bank} 上补语义更准的 {@code adjustReserves(double)}（见 `.verify/CB_T4_EVIDENCE.md` §6）。
 */
public class InterbankMarket {

    // ===================== 走廊与出清参数（§6.3）=====================

    /** 走廊半宽（先验 100bp；§6.3 {@code CORRIDOR_HALF_WIDTH}）。 */
    public static final double CORRIDOR_HALF_WIDTH = 0.01;
    /** 出清斜率（§6.3 {@code CORRIDOR_SLOPE} = 1.0 ⇒ ratio = 2 时 +100bp）。 */
    public static final double CORRIDOR_SLOPE = 1.0;
    /** 供求紧张度钳制域（§14.3：ratio 钳 {@code [0.1,10]}，防极端 gap 发散）。 */
    public static final double RATIO_MIN = 0.1;
    public static final double RATIO_MAX = 10.0;
    /** 目标超额准备金下限 / 自愿缓冲（§6.3：本期均为 0）。 */
    public static final double ER_TARGET_FLOOR = 0.0;
    public static final double OPTIONAL_BUFFER = 0.0;
    /** 零利率下限阈值（§13.6：{@code policyRate ≤ 0.001} ⇒ 下限退化为 0 并置 ZLB 标志）。 */
    public static final double ZLB_POLICY_RATE = 0.001;
    /** 单一价出清的"池"对手方 ID（按比率分配 ⇒ 记「行 ↔ 池」，避免两两配对的顺序歧义）。 */
    public static final String POOL_COUNTERPARTY_ID = "INTERBANK_POOL";

    // ===== 旧路径常量（**仅 off 态逐位复现基线**；§8.2「保留为 updateLegacy」）=====
    private static final double LEGACY_RATE_BASE = 0.01;
    private static final double LEGACY_RATE_SLOPE = 0.05;
    private static final double LEGACY_RATE_NO_SUPPLY = 0.08;
    private static final double LEGACY_RATE_FLOOR = 0.005;
    private static final double LEGACY_RATE_CEIL = 0.15;

    /** ER ≥ 0 的比较容差（浮点）。 */
    private static final double ER_EPS = 1e-9;

    // ===================== 状态 =====================

    private double interbankRate = 0.03;

    /** §4 {@code centralBank.corridor}（默认 true；由 t5 传播）。 */
    private boolean corridorEnabled = true;
    /** 走廊源是否已注入（未注入 ⇒ {@link #update(Collection)} 保持旧路径，**零行为变化**）。 */
    private boolean corridorWired;

    private double corridorLower;
    private double corridorUpper;
    /**
     * 走廊参数源（CB-t4c 活委托）：非 {@code null} 时 {@link #getCorridorUpper()} / {@link #getCorridorLower()}
     * <b>直接读央行权威值</b>，消除"政策动作之后、下一次 {@code clearing} 之前"的<b>陈旧快照读</b>；
     * 为 {@code null} 时回落最近一次快照（未接线，或由
     * {@link #setCorridorParams(double, double, double)} 直接注入常量的隔离场景）。
     */
    private CentralBank corridorSource;
    private double corridorPolicyRate;
    private boolean zlbException;

    private double volume;
    private double weightedRate;
    private double totalDemand;
    private double totalSupply;
    private int participants;
    private long lastClearingTick;
    private final List<InterbankLoan> lastClearingLoans = new ArrayList<>();
    private final List<InterbankLoan> manualLoans = new ArrayList<>();

    // ===================== §3.3 冻结可观测面 =====================

    /** DR007 代理：出清价（走廊内）。 */
    public double getInterbankRate() { return interbankRate; }

    /** 当日成交量（= 出清中真实转移的 ER 额 = min(Σd, Σs)）。 */
    public double getInterbankRateVolume() { return volume; }

    /** 参与行数（意向口径：Σd>0 的行数 + Σs>0 的行数，CB-19② 判据）。 */
    public double getInterbankRateParticipants() { return participants; }

    /** 需求侧合计 = Σ max(0, RR_i − reserves_i)（RR/ER 口径）。 */
    public double getInterbankTotalDemand() { return totalDemand; }

    /** 供给侧合计 = Σ ER_i（§6.10.1 的 Σs；= 0 即"银行间无可借"）。 */
    public double getInterbankTotalSupply() { return totalSupply; }

    /** 零利率下限例外（§13.6：{@code policyRate ≤ 0.001} 时下限退化为 0，须单独统计，不得静默）。 */
    public boolean isZlbException() { return zlbNow(); }

    /**
     * ★CB-T23：**ZLB 标记活读**（消除"政策动作 → 下一次 {@code clearing}"窗口内的陈旧标记）。
     *
     * <p>窗口成因：{@code zlbException} 字段原先只在 {@link #setCorridorSource}／{@link #setCorridorParams}／
     * NBT {@code load} 三处写入 ⇒ 央行在上一次注入之后、下一次 {@code clearing()} 之前把 {@code policyRate}
     * 降到 ≤ {@link #ZLB_POLICY_RATE} 时，{@link #getCorridorLower()} 的活分支会读到**新的 IOER**却沿用
     * **旧的 ZLB 标记**（不取 {@code max(0, ·)}），且 {@code isZlbException()} 在该窗口内短暂不实 —— 与
     * §13.6「ZLB 例外下限退化不得静默」冲突（同类陈旧窗口，与 CB-t4c 消除"利率陈旧"同源）。
     *
     * <p>口径：**有走廊源 ⇒ 按源实时判定**（与 t15 的活委托同源）；**无源 ⇒ 回落最近一次快照字段**
     * （隔离场景 {@link #setCorridorParams} 与 NBT 恢复路径语义不变）。确定性纯读取，无副作用。
     */
    private boolean zlbNow() {
        return corridorSource != null ? corridorSource.getPolicyRate() <= ZLB_POLICY_RATE : zlbException;
    }

    /**
     * 走廊上限 = SLF。**CB-t4c 活委托**：已注入走廊源时直接读 {@link CentralBank#getCorridorUpperRate()}
     * （政策动作后立即可见，不再等下一次 {@code clearing} 的快照推送）；未注入时回落最近一次快照。
     */
    public double getCorridorUpper() {
        return corridorSource != null ? corridorSource.getCorridorUpperRate() : corridorUpper;
    }

    /**
     * 走廊下限 = IOER。**CB-t4c 活委托**：已注入走廊源时直接读 {@link CentralBank#getCorridorLowerRate()}，
     * 并按 ZLB 例外（§13.6）取下限与 0 的较大者，与 {@link #setCorridorSource(CentralBank)} 的推送口径逐位一致；
     * 未注入时回落最近一次快照。
     *
     * <p>★CB-T23：ZLB 例外**同口径活读** —— 活分支用 {@link #zlbNow()}（按源实时判定）而非快照字段，
     * 使下限退化与 {@link #isZlbException()} **不互相矛盾**；未注入源时仍用快照字段（语义不变）。
     */
    public double getCorridorLower() {
        if (corridorSource == null) return corridorLower;
        double rawLower = corridorSource.getCorridorLowerRate();
        return zlbNow() ? Math.max(0.0, rawLower) : rawLower;
    }

    // ===== 诊断 / 交接（t5 / t7 用）=====

    /** 出清加权价（单一价 ⇒ 等于 {@link #getInterbankRate()}）。 */
    public double getInterbankWeightedRate() { return weightedRate; }

    /** 最近一次出清的引擎 tick（诊断/持久化用）。 */
    public long getLastClearingTick() { return lastClearingTick; }

    /** 最近一次出清的成交明细（确定性顺序；不含手工 {@link #borrow} 拆借）。 */
    public List<InterbankLoan> getLastClearingLoans() { return Collections.unmodifiableList(lastClearingLoans); }

    /** 手工拆借登记（{@link #borrow}）。 */
    public List<InterbankLoan> getManualLoans() { return Collections.unmodifiableList(manualLoans); }

    /** 走廊半宽（展示/复算用）。 */
    public double getCorridorHalfWidth() { return CORRIDOR_HALF_WIDTH; }

    /** 走廊开关（§4 {@code centralBank.corridor}）。 */
    public boolean isCorridorEnabled() { return corridorEnabled; }

    /** 设置走廊开关（false ⇒ {@link #update(Collection)} 走 {@link #updateLegacy(Collection)}，逐位复现基线）。 */
    public void setCorridorEnabled(boolean enabled) { this.corridorEnabled = enabled; }

    /** 走廊源是否已注入。 */
    public boolean isCorridorWired() { return corridorWired; }

    /** 出清时使用的政策利率（走廊中轴）。 */
    public double getCorridorPolicyRate() { return corridorPolicyRate; }

    // ===================== 走廊注入与出清入口 =====================

    /**
     * 注入走廊源（引擎/央行接线时调用）：走廊上/下限与中轴直接取自央行参数（SLF / IOER / policyRate），
     * 不再使用任何硬编码利率区间。
     */
    public void setCorridorSource(CentralBank centralBank) {
        if (centralBank == null) {
            this.corridorWired = false;
            this.corridorSource = null;      // CB-t4c：解除活委托 ⇒ getter 回落快照
            return;
        }
        this.corridorSource = centralBank;   // CB-t4c：登记活委托来源（快照推送口径保持不变）
        this.corridorUpper = centralBank.getCorridorUpperRate();
        this.corridorPolicyRate = centralBank.getPolicyRate();
        this.zlbException = this.corridorPolicyRate <= ZLB_POLICY_RATE;   // §13.6
        double rawLower = centralBank.getCorridorLowerRate();
        this.corridorLower = this.zlbException ? Math.max(0.0, rawLower) : rawLower;
        this.corridorWired = true;
    }

    /** 直接注入走廊三值（不依赖央行实例；供 t7 探针与隔离测试用）。 */
    public void setCorridorParams(double lower, double upper, double policyRate) {
        this.corridorSource = null;          // CB-t4c：直接注入常量 ⇒ 解除活委托，保证"不依赖央行实例"
        this.corridorLower = Math.max(0.0, lower);
        this.corridorUpper = upper;
        this.corridorPolicyRate = policyRate;
        this.zlbException = policyRate <= ZLB_POLICY_RATE;
        if (this.zlbException) this.corridorLower = 0.0;
        this.corridorWired = true;
    }

    /**
     * 每交易日出清（§8.3⑤，引擎调用）：走廊内出清 DR007 代理 + 真实转移 ER + 把走廊三值推给
     * {@link MonetaryAggregator#setCorridorSnapshot(double, double, double, boolean)}
     * ⇒ I7 由「不可评定」变为**可评定**（t7 依赖此点）。
     *
     * @param bankManager 银行管理器（提供各行账本与聚合器）
     * @param centralBank 央行（走廊参数源；可空 ⇒ 回落旧路径）
     * @return 出清后的银行间利率（DR007 代理）
     */
    public double clearing(BankManager bankManager, CentralBank centralBank) {
        if (centralBank != null) setCorridorSource(centralBank);
        Collection<Bank> banks = (bankManager != null) ? bankManager.getAllBanks() : Collections.<Bank>emptyList();
        if (corridorEnabled && corridorWired) {
            computeClearing(banks);
        } else {
            updateLegacy(banks);
        }
        if (bankManager != null) {
            MonetaryAggregator agg = bankManager.getMonetaryAggregator();
            if (agg != null) {
                // ★CB-T23：快照推送**改读同口径活值**（推送点/时序/极性不变，仅取值与
                // `getCorridorLower()`/`isZlbException()` 的活读口径一致）⇒ 聚合器侧 I7 的 zlb 标记
                // 不再可能与该窗口内的两个出口互相矛盾（§13.6「不得静默」）。
                agg.setCorridorSnapshot(getCorridorLower(), getCorridorUpper(), interbankRate, zlbNow());
            }
        }
        return interbankRate;
    }

    /**
     * 兼容入口（既有 {@code BankManager.dailyUpdate} 调用）：走廊已接线且开关开启 ⇒ 新的走廊出清；
     * 否则 ⇒ {@link #updateLegacy(Collection)}，与改动前**逐位一致**（N1）。
     */
    public void update(Collection<Bank> banks) {
        if (corridorEnabled && corridorWired) {
            computeClearing(banks);
        } else {
            updateLegacy(banks);
        }
    }

    /**
     * 旧路径（§8.2：保留为 {@code updateLegacy}，供 {@code centralBank.corridor = false} 时逐位复现基线）。
     *
     * <p>公式与改动前逐字一致：{@code excess = vaultCash − totalDeposits × reserveRequirement}；
     * {@code rate = 0.01 + (Σ缺 / Σ余) × 0.05}；{@code Σ余 ≤ 0 ⇒ 0.08}；再钳到 {@link #LEGACY_RATE_FLOOR}
     * 与 {@link #LEGACY_RATE_CEIL}（旧固定区间，已降级为**具名常量**且仅在此路径生效）。
     * 同时刷新 RR/ER 口径的规范量（供给 = Σ ER），但**成交量恒为 0**（旧路径不撮合）。
     */
    public void updateLegacy(Collection<Bank> banks) {
        double totalD = 0;
        double totalS = 0;
        for (Bank bank : banks) {
            double excess = bank.getVaultCash() - bank.getTotalDeposits() * bank.getReserveRequirement();
            if (excess < 0) totalD += Math.abs(excess);
            else totalS += excess;
        }
        if (totalS > 0) {
            double ratio = totalD / totalS;
            interbankRate = LEGACY_RATE_BASE + ratio * LEGACY_RATE_SLOPE;
        } else {
            interbankRate = LEGACY_RATE_NO_SUPPLY;
        }
        interbankRate = Math.max(LEGACY_RATE_FLOOR, Math.min(LEGACY_RATE_CEIL, interbankRate));
        refreshCanonicalAggregates(banks);
        this.volume = 0.0;
        this.weightedRate = 0.0;
        this.lastClearingLoans.clear();
    }

    /**
     * 手工拆借（既有 API，**已修 null 键缺陷**）。
     *
     * <ul>
     *   <li>{@code borrower}/{@code lender} 为 null、同一行或 {@code amount ≤ 0} ⇒ **拒绝**（返回 null 且不改任何状态）；</li>
     *   <li>贷出行须有足额超额准备金（{@code ER ≥ amount}）与足额准备金账户余额（A5：不得为负）；</li>
     *   <li>准备金转移**只动 {@code reserves}**（借入行 +=、贷出行 −=），**不触 {@code depositAccounts}**
     *       ⇒ 不可能再写入 {@code null} 键（旧实现以 null 客户 UUID 走存取款路径，详见类注释）。</li>
     * </ul>
     */
    public InterbankLoan borrow(Bank borrower, Bank lender, double amount) {
        if (borrower == null || lender == null) return null;
        if (borrower == lender) return null;
        if (!(amount > 0)) return null;
        if (lender.getReservesExcess() < amount - ER_EPS) return null;
        if (lender.getReserves() < amount - ER_EPS) return null;
        lender.restoreCashPosition(lender.getVaultCash(), lender.getReserves() - amount);
        borrower.addEmergencyReserves(amount);
        borrower.recomputeReserveDecomposition();
        InterbankLoan loan = new InterbankLoan(borrower.getBankId(), lender.getBankId(), amount, interbankRate);
        manualLoans.add(loan);
        return loan;
    }

    /** 全部拆借登记（出清 + 手工）。 */
    public List<InterbankLoan> getLoans() {
        List<InterbankLoan> all = new ArrayList<>(lastClearingLoans);
        all.addAll(manualLoans);
        return Collections.unmodifiableList(all);
    }

    // ===================== 出清算法（§6.3，确定性、无随机）=====================

    /** 走廊内瓦尔拉斯出清 + 按比率分配 + 真实 ER 转移（§6.3 ③④⑤）。 */
    private void computeClearing(Collection<Bank> banks) {
        List<Bank> list = new ArrayList<>();
        for (Bank b : banks) if (b != null) list.add(b);

        double sumD = 0, sumS = 0;
        double[] demand = new double[list.size()];
        double[] supply = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Bank b = list.get(i);
            demand[i] = Math.max(0.0, b.getReservesRequired() - b.getReserves() + ER_TARGET_FLOOR);
            supply[i] = Math.max(0.0, b.getReservesExcess() - OPTIONAL_BUFFER);
            sumD += demand[i];
            sumS += supply[i];
        }
        this.totalDemand = sumD;
        this.totalSupply = sumS;
        int borrowSide = 0, lendSide = 0;
        for (int i = 0; i < list.size(); i++) {
            if (demand[i] > ER_EPS) borrowSide++;
            if (supply[i] > ER_EPS) lendSide++;
        }
        this.participants = borrowSide + lendSide;   // CB-19②：意向参与行数

        double q = Math.min(sumD, sumS);
        if (!(q > ER_EPS)) {
            // 无成交 ⇒ 落在走廊中轴（§6.3③：Q*=0 ⇒ rate = policyRate）
            this.volume = 0.0;
            this.weightedRate = 0.0;
            this.lastClearingLoans.clear();
            this.interbankRate = clampToCorridor(corridorPolicyRate);
            return;
        }
        double ratio = sumD / Math.max(1e-9, sumS);
        ratio = Math.max(RATIO_MIN, Math.min(RATIO_MAX, ratio));
        double rateRaw = corridorPolicyRate + CORRIDOR_HALF_WIDTH * (ratio - 1.0) / CORRIDOR_SLOPE;
        this.interbankRate = clampToCorridor(rateRaw);   // ★I7 硬约束
        this.weightedRate = this.interbankRate;          // 单一价出清
        this.volume = q;
        this.lastClearingLoans.clear();

        // 按比率分配（确定性：按传入顺序 + 末位吸收余数 ⇒ Σborrow = Σlend = Q* 精确相等）
        double[] borrow = allocate(q, demand, sumD);
        double[] lend = allocate(q, supply, sumS);
        for (int i = 0; i < list.size(); i++) {
            Bank b = list.get(i);
            if (borrow[i] > 0) {
                b.addEmergencyReserves(borrow[i]);
                b.recomputeReserveDecomposition();
                lastClearingLoans.add(new InterbankLoan(b.getBankId(), POOL_COUNTERPARTY_ID, borrow[i],
                        this.interbankRate));
            }
            if (lend[i] > 0) {
                b.restoreCashPosition(b.getVaultCash(), Math.max(0.0, b.getReserves() - lend[i]));
                lastClearingLoans.add(new InterbankLoan(POOL_COUNTERPARTY_ID, b.getBankId(), lend[i],
                        this.interbankRate));
            }
        }
    }

    /** 把总额 {@code total} 按权重比率分配；末位吸收浮点余数，保证和无漂移。 */
    private static double[] allocate(double total, double[] weights, double weightSum) {
        double[] out = new double[weights.length];
        if (!(total > 0) || !(weightSum > 0)) return out;
        double assigned = 0;
        int lastIdx = -1;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > 0) lastIdx = i;
        }
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] <= 0) continue;
            if (i == lastIdx) {
                out[i] = Math.max(0.0, total - assigned);
            } else {
                out[i] = total * weights[i] / weightSum;
                assigned += out[i];
            }
        }
        return out;
    }

    /** 走廊钳制（I7 硬约束）；ZLB 例外期下限退化为 0。 */
    private double clampToCorridor(double rate) {
        double lower = zlbException ? 0.0 : corridorLower;
        double upper = corridorUpper;
        if (upper < lower) upper = lower;
        return Math.max(lower, Math.min(upper, rate));
    }

    /** 无论走哪条路径都刷新 RR/ER 口径的规范量（供 §6.10.1 状态机读取 Σs）。 */
    private void refreshCanonicalAggregates(Collection<Bank> banks) {
        double sumD = 0, sumS = 0;
        int borrowSide = 0, lendSide = 0;
        for (Bank b : banks) {
            if (b == null) continue;
            double d = Math.max(0.0, b.getReservesRequired() - b.getReserves() + ER_TARGET_FLOOR);
            double s = Math.max(0.0, b.getReservesExcess() - OPTIONAL_BUFFER);
            sumD += d;
            sumS += s;
            if (d > ER_EPS) borrowSide++;
            if (s > ER_EPS) lendSide++;
        }
        this.totalDemand = sumD;
        this.totalSupply = sumS;
        this.participants = borrowSide + lendSide;
    }

    // ===================== 持久化（§9.1 "interbank" blob；t5 接线）=====================

    /** 序列化（利率/成交量/参与行数/最近出清 tick + 走廊快照）；键只增不改，读档 {@code contains} 兜底。 */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("interbankRate", interbankRate);
        tag.putDouble("volume", volume);
        tag.putDouble("weightedRate", weightedRate);
        tag.putDouble("totalDemand", totalDemand);
        tag.putDouble("totalSupply", totalSupply);
        tag.putInt("participants", participants);
        tag.putBoolean("zlbException", zlbException);
        tag.putDouble("corridorLower", corridorLower);
        tag.putDouble("corridorUpper", corridorUpper);
        tag.putDouble("corridorPolicyRate", corridorPolicyRate);
        tag.putBoolean("corridorWired", corridorWired);
        tag.putBoolean("corridorEnabled", corridorEnabled);
        tag.putLong("lastClearingTick", lastClearingTick);
        return tag;
    }

    /** 读档（缺键 ⇒ 保持默认；**读档不得失败**）。 */
    public void load(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("interbankRate")) interbankRate = tag.getDouble("interbankRate");
        if (tag.contains("volume")) volume = tag.getDouble("volume");
        if (tag.contains("weightedRate")) weightedRate = tag.getDouble("weightedRate");
        if (tag.contains("totalDemand")) totalDemand = tag.getDouble("totalDemand");
        if (tag.contains("totalSupply")) totalSupply = tag.getDouble("totalSupply");
        if (tag.contains("participants")) participants = tag.getInt("participants");
        if (tag.contains("zlbException")) zlbException = tag.getBoolean("zlbException");
        if (tag.contains("corridorLower")) corridorLower = tag.getDouble("corridorLower");
        if (tag.contains("corridorUpper")) corridorUpper = tag.getDouble("corridorUpper");
        if (tag.contains("corridorPolicyRate")) corridorPolicyRate = tag.getDouble("corridorPolicyRate");
        if (tag.contains("corridorWired")) corridorWired = tag.getBoolean("corridorWired");
        if (tag.contains("corridorEnabled")) corridorEnabled = tag.getBoolean("corridorEnabled");
        if (tag.contains("lastClearingTick")) lastClearingTick = tag.getLong("lastClearingTick");
    }

    /** 出清 tick 标记（引擎每 tick/每日调用；诊断与持久化用）。 */
    public void setLastClearingTick(long tick) { this.lastClearingTick = tick; }

    /** 银行间拆借登记（既有 record；字段名与语义保持不变）。 */
    public record InterbankLoan(String borrowerId, String lenderId, double principal, double rate) {}
}
