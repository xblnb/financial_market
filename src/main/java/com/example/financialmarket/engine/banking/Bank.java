package com.example.financialmarket.engine.banking;

import com.example.financialmarket.engine.monetary.BaseMoneyFlow;
import com.example.financialmarket.engine.monetary.DepositClass;
import com.example.financialmarket.engine.monetary.LastResortRecord;
import com.example.financialmarket.engine.products.FinancialProduct;
import java.util.*;
import java.util.function.Consumer;

public class Bank {
    private final String bankId;
    private final String name;
    private final UUID ownerUuid;
    private double reserves;
    private double vaultCash;
    private double totalDeposits;
    private double totalLoans;
    private double equity;
    private final double reserveRequirement;
    private double primeRate;
    private double depositRate;
    private final List<Loan> loans = new ArrayList<>();
    private final Map<UUID, Double> depositAccounts = new HashMap<>();
    private double timeDeposits;            // 定期/储蓄存款（M2 定期层，非 M1 活期）
    private double moneyMarketDeposits;     // 货币市场类存款（M2 货币市场层）
    private double accruedLoanLoss;         // 累计坏账（dailyUpdate 计提）
    private double investmentPortfolio;
    private InterbankMarket interbankMarket;
    private final List<FinancialProduct> offeredProducts = new ArrayList<>();
    // F2（连接审计修复 #5/#7）：最近一次 dailyUpdate 收到的交易日序（贷款起算日基准，确定性）。
    private long lastKnownDay = 0;

    // ============================================================
    // CB-t2（CENTRAL_BANK_SPEC v1.5 §5.3/§5.4/§6.5/§6.10.1）：存款分类账 + 准备金分解 + rr 权威同步
    // 纪律：不新建平行账本 —— 分类层是既有 totalDeposits/timeDeposits/moneyMarketDeposits 的**细分**，
    //       「单位活期」= totalDeposits − 个人活期（派生量，不另存字段）。
    // ============================================================

    /** 个人活期（2025 新口径 M1 的「个人活期」项）；旧档缺 → 0，即 **1994 旧口径**（§9.1②）。 */
    private double demandDepositsHousehold;
    /** 个人储蓄（M2 非 M1；与既有 timeDeposits 分列）。 */
    private double savingsHousehold;
    /** 支付机构客户备付金（2025 新口径计入 M1，同时计入 B 的 Pay 项；**不**计入达标存款基数，§5.4）。 */
    private double paymentReserve;

    /** 法定准备金 RR_i（§5.4；由账务即时重算 + 每交易日 settleReserveRequirement 维护）。 */
    private double reservesRequired;
    /** 超额准备金 ER_i = reserves − RR_i（恒 ≥ 0）。 */
    private double reservesExcess;
    /** 对央行结算欠款（I9 兜底：RR 不足时显式记欠款，**不得**让 reserves < 0）。 */
    private double settlementDebt;

    /**
     * 央行权威 rr 快照（P3/§14.4-B）。
     * ⚠ {@code reserveRequirement} 是 <b>final</b>（R3）⇒ 只能「保留字段做兼容 + 新增权威读取方法」，
     * 不得加 setter、不得去 final（会改序列化）。
     */
    private double reserveRequirementEffective = Double.NaN;
    /** rr 同步开关（§4 {@code centralBank.requiredReserveSync}，默认 true；false ⇒ 逐位复现基线口径）。 */
    private boolean reserveSyncEnabled = true;

    /** 问题银行状态（§6.10.1；CB-t3 的状态机通过 {@link #setTroubledState} 推送）。 */
    private LastResortRecord.TroubledBankState troubledState = LastResortRecord.TroubledBankState.HEALTHY;

    /** LPR 1Y（§6.5；CB-t3 的 {@code updateLpr} 注入；未注入 = NaN，由 {@link #isLprInjected()} 如实标注）。 */
    private double lprOneYear = Double.NaN;
    /** 现金形态转换记账钩子（§6.1）；未注入 sink ⇒ 不记账、零行为影响（记账权归 CB-t3）。 */
    private Consumer<BaseMoneyFlow> baseMoneyFlowSink;
    /** 现金转换钩子开关（§4 {@code centralBank.balanceSheet}，默认 true）。 */
    private boolean cashFlowBookingEnabled = true;

    /** 银行加点基准（§6.5 {@code bankSpread = 0.005 + 0.01 × (1 − ER_i / max(1, RR_i))}）。 */
    public static final double BANK_SPREAD_BASE = 0.005;
    /** 银行加点对准备金松紧的敏感度系数（§6.5）。 */
    public static final double BANK_SPREAD_RESERVE_COEF = 0.01;
    /** 坏账风险溢价系数（§6.5 {@code riskPremium = 0.02 × nplRatio}）。 */
    public static final double NPL_PREMIUM = 0.02;
    /** 准备金率权威域（与央行 {@code [0.01,0.25]} 同域，R2 往返锚）。 */
    private static final double RR_MIN = 0.01;
    private static final double RR_MAX = 0.25;
    /** ER ≥ 0 硬约束的比较容差（浮点） */
    private static final double ER_EPS = 1e-9;

    public Bank(String bankId, String name, UUID ownerUuid, double initialEquity,
                double reserveRequirement, double primeRate, double depositRate) {
        this.bankId = bankId;
        this.name = name;
        this.ownerUuid = ownerUuid;
        this.equity = initialEquity;
        this.reserves = initialEquity * 0.1;
        this.vaultCash = initialEquity * 0.9;
        this.reserveRequirement = reserveRequirement;
        this.primeRate = primeRate;
        this.depositRate = depositRate;
    }

    // Getters
    public String getBankId() { return bankId; }
    public String getName() { return name; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public double getVaultCash() { return vaultCash; }
    public double getReserves() { return reserves; }
    public double getTotalDeposits() { return totalDeposits; }
    public double getTotalLoans() { return totalLoans; }
    public double getEquity() { return equity; }
    public double getReserveRequirement() { return reserveRequirement; }
    public double getPrimeRate() { return primeRate; }
    public double getDepositRate() { return depositRate; }

    /** 贷款利率钳制区间（BT-6/AS-6）：限定合法域，越界钳制而非崩溃。 */
    private static final double PRIME_RATE_MIN = 0.001;
    private static final double PRIME_RATE_MAX = 0.50;
    /** 存款利率钳制区间（BT-6/AS-6）。 */
    private static final double DEPOSIT_RATE_MIN = 0.0;
    private static final double DEPOSIT_RATE_MAX = 0.30;

    /** 设置贷款利率（BT-6/AS-6）。合法区间 [0.001,0.50] 钳制，返回实际生效值；确定性、无随机。 */
    public double setPrimeRate(double rate) {
        this.primeRate = Math.max(PRIME_RATE_MIN, Math.min(PRIME_RATE_MAX, rate));
        return this.primeRate;
    }

    /** 设置存款利率（BT-6/AS-6）。合法区间 [0.0,0.30] 钳制，返回实际生效值；确定性、无随机。 */
    public double setDepositRate(double rate) {
        this.depositRate = Math.max(DEPOSIT_RATE_MIN, Math.min(DEPOSIT_RATE_MAX, rate));
        return this.depositRate;
    }

    public List<Loan> getLoans() { return loans; }
    public double getInvestmentPortfolio() { return investmentPortfolio; }
    public void setInterbankMarket(InterbankMarket market) { this.interbankMarket = market; }

    // ===================== M1 / M2 分层度量 =====================

    /** 活期存款（随时支取），计入 M1。 */
    public double getDemandDeposits() { return totalDeposits; }

    /** 指定客户（玩家）在该银行的活期存款余额；未开户时缺省 0（银行平板 AS-5）。 */
    public double getDepositBalance(UUID customerId) {
        if (customerId == null) return 0.0;
        return depositAccounts.getOrDefault(customerId, 0.0);
    }

    /** F2（连接审计修复 #2）：按客户活期存款账本（序列化用；只读遍历）。 */
    public Map<UUID, Double> getDepositAccounts() {
        return depositAccounts;
    }

    /**
     * F2（连接审计修复 #2）：读档恢复银行按客户存款账本与现金头寸。
     * 直接恢复字段值（不再经 deposit() 重复入账 vaultCash，避免读档后 vault 虚增）；
     * 旧档缺字段时按传入默认值兜底，读档不失败。确定性、无随机。
     */
    public void restoreLedger(Map<UUID, Double> accounts, double vault, double res,
                              double deposits, double time, double moneyMarket) {
        if (accounts != null) {
            depositAccounts.clear();
            for (Map.Entry<UUID, Double> e : accounts.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                    depositAccounts.put(e.getKey(), e.getValue());
                }
            }
        }
        this.vaultCash = Math.max(0, vault);
        this.reserves = Math.max(0, res);
        this.totalDeposits = Math.max(0, deposits);
        this.timeDeposits = Math.max(0, time);
        this.moneyMarketDeposits = Math.max(0, moneyMarket);
        rebalanceReserves();
        recomputeReserveDecomposition();
    }

    /** 定期/储蓄存款，计入 M2 的非 M1 定期层。 */
    public double getTimeDeposits() { return timeDeposits; }

    /** 货币市场类存款，计入 M2 的非 M1 货币市场层。 */
    public double getMoneyMarketDeposits() { return moneyMarketDeposits; }

    /** 全口径存款（活期 + 定期 + 货币市场），用于准备金纪律。 */
    public double getTotalDepositsAll() { return totalDeposits + timeDeposits + moneyMarketDeposits; }

    /** 该银行对 M1 的贡献（活期存款部分）。 */
    public double getM1Contribution() { return getDemandDeposits(); }

    /** 该银行对 M2−M1 的贡献（定期/储蓄 + 货币市场）。 */
    public double getM2NonM1Contribution() { return timeDeposits + moneyMarketDeposits; }

    /** 理论货币乘数 = 1 / 准备金率。 */
    public double getReserveBasedMultiplier() { return 1.0 / reserveRequirement; }

    public double getAccruedLoanLoss() { return accruedLoanLoss; }

    /** Payment fee income: retained by the payer's bank as equity, not re-deposited. */
    public void creditFee(double amount) {
        if (amount > 0) this.equity += amount;
    }

    /**
     * 活期存款（M1）。CB-t2（§5.3）：委托分类入账，默认分类 = {@code DEMAND_CORPORATE}
     * （= 旧档/旧调用点口径，保持既有语义与数值逐位不变；个人活期须由调用方显式走
     * {@code depositClass(uuid, DEMAND_HOUSEHOLD, amount)}）。
     */
    public void deposit(UUID customerId, double amount) {
        depositClass(customerId, DepositClass.DEMAND_CORPORATE, amount);
    }

    /** 定期/储蓄存款（M2 定期层），同样受准备金约束。 */
    public void depositTime(UUID customerId, double amount) {
        depositClass(customerId, DepositClass.TIME_CORPORATE, amount);
    }

    /** 货币市场存款（M2 货币市场层），同样受准备金约束。 */
    public void depositMoneyMarket(UUID customerId, double amount) {
        depositClass(customerId, DepositClass.OTHER_MONEY_MARKET, amount);
    }

    /**
     * 按达标存款基数将超额库存现金转入准备金，维持准备金率纪律（§5.4）。
     *
     * <p>CB-t2：达标基数改用 {@link #getReserveBase()}（五类存款，不含备付金）且 rr 取**权威值**
     * （{@link #rrEffective()}）；rr 同步开关关闭时退回既有 {@code getTotalDepositsAll()*reserveRequirement}
     * ⇒ 逐位复现基线。转入动作同时触发 {@code BANK_CASH_DEPOSIT} 记账钩子（§6.1；未注入 sink 则零影响）。
     */
    private void rebalanceReserves() {
        double required = getReserveBase() * rrEffective();
        if (vaultCash > required) {
            double excess = vaultCash - required;
            vaultCash -= excess;
            reserves += excess;
            emitCashFlow(BaseMoneyFlow.Kind.BANK_CASH_DEPOSIT, excess);
        }
        recomputeReserveDecomposition();
    }

    /**
     * F2（连接审计修复 #4）：央行紧急贷款（最后贷款人）实际入账银行准备金。
     * 此前 CentralBank.emergencyLending 只写央行侧账本（bankReserves/baseMoney），银行 reserves/vaultCash
     * 分文未动 → 流动性缺口每交易日重复触发、baseMoney 无限递增且银行永远短缺。本方法把贷款真实计入
     * 银行准备金（M0 侧 baseMoney 记账仍由央行完成，两侧对称）。确定性、无随机。
     */
    public void addEmergencyReserves(double amount) {
        if (amount > 0) this.reserves += amount;
    }

    public boolean withdraw(UUID customerId, double amount) {
        Double balance = depositAccounts.get(customerId);
        if (balance == null || balance < amount) return false;
        if (vaultCash < amount) {
            double needed = amount - vaultCash;
            if (reserves >= needed) {
                reserves -= needed;
                vaultCash += needed;
            } else {
                return false;
            }
        }
        vaultCash -= amount;
        totalDeposits -= amount;
        depositAccounts.merge(customerId, -amount, Double::sum);
        // CB-t2：个人活期层随活期支取回落（派生量 corporate = totalDeposits − household 恒 ≥ 0）；
        // 现金转换钩子 + 分解即时重算（§6.1 / §5.4）。
        double corporate = totalDeposits - demandDepositsHousehold;
        if (corporate < 0) demandDepositsHousehold = Math.max(0, demandDepositsHousehold + corporate);
        emitCashFlow(BaseMoneyFlow.Kind.NONBANK_CASH_WITHDRAW, amount);
        recomputeReserveDecomposition();
        return true;
    }

    /**
     * 银行信贷创造（货币乘数）：在准备金约束下向借户派生存款。
     * 放贷时借户活期存款增加（{@code totalDeposits}、M1/M2 内生扩张），
     * 派生上限受 {@code (reserves + vaultCash) ≥ required} 约束，且单行贷款规模受 {@code equity*10} 上限。
     * 理论货币乘数 = 1 / 准备金率。
     */
    public Loan issueLoan(UUID borrowerId, String borrowerType, double amount, double annualRate, int termDays, double collateralValue) {
        if (amount <= 0) return null;
        if ((totalLoans + amount) > equity * 10) return null;
        if (reserveSyncEnabled) {
            // CB-t2（§8.2③ / P8 / I9 / A5）：达标口径收紧为「只看准备金账户」且放贷后 ER_i ≥ 0 ——
            // 既有口径把库存现金计入达标（偏松），且不要求 ER ≥ 0。开关关闭时走下方基线分支（逐位复现）。
            double required = (getReserveBase() + amount) * rrEffective();
            if (reserves - required < -ER_EPS) return null;
        } else {
            double required = (getTotalDepositsAll() + amount) * reserveRequirement;
            if (reserves + vaultCash < required) return null;
        }
        Loan loan = new Loan(borrowerId, borrowerType, bankId, amount, annualRate, termDays, collateralValue);
        // F2（连接审计修复 #5/#7）：发放即打上「最近已知交易日」起算日。此前多数调用方不 setStartDay
        // （startDay 默认 0），>90 交易日后新贷款次日即被误判违约（overdueDays 公式）；交易日序由
        // dailyUpdate(currentDay) 每交易日写入 lastKnownDay，同种子确定性可复现。
        if (lastKnownDay > 0) loan.setStartDay(lastKnownDay);
        loans.add(loan);
        totalLoans += amount;
        // 信贷创造：向借户活期账户派生存款（M1/M2 扩张）
        depositAccounts.merge(borrowerId, amount, Double::sum);
        totalDeposits += amount;
        recomputeReserveDecomposition();
        return loan;
    }

    /**
     * 还款：从借户活期存款扣减并收回现金，收缩存款（M2 减少），维持资产负债表平衡。
     *
     * <p>F2（连接审计修复 #3）：还款以借户在该行的活期存款余额为上限——余额不足时拒绝（返回 0），
     * 杜绝「贷款→取现→免费还款」的凭空生钱循环与负存款账户（此前无余额校验，vaultCash 凭空入账）。
     * 玩家欲以现金还款须先存款入该行再还款（存款/取款路径守恒）。
     *
     * @return 实际还款额（0 表示余额不足或贷款不存在）
     */
    public double repayLoan(UUID loanId, double amount) {
        if (amount <= 0) return 0;
        for (Loan loan : loans) {
            if (loan.getLoanId().equals(loanId) && loan.getStatus() == Loan.LoanStatus.ACTIVE) {
                double balance = depositAccounts.getOrDefault(loan.getBorrowerId(), 0.0);
                double repayable = Math.min(amount, Math.min(loan.getRemaining(), balance));
                if (repayable <= 0) return 0; // 借户无活期余额：拒绝还款（防免费还款凭空生钱）
                double repaid = repayable;
                loan.repay(repaid);
                totalLoans -= repaid;
                depositAccounts.merge(loan.getBorrowerId(), -repaid, Double::sum);
                totalDeposits = Math.max(0, totalDeposits - repaid);
                vaultCash += repaid;
                return repaid;
            }
        }
        return 0;
    }

    /**
     * 每日更新：计提坏账。长期逾期未还的贷款标记违约，并从存款侧扣减（M2 收缩），
     * 反映信用风险对货币供给的负向冲击。
     */
    public void dailyUpdate(long currentDay) {
        // F2（连接审计修复 #5/#7）：记录最近交易日序，供 issueLoan 打起算日（贷款违约判定用交易日序）。
        if (currentDay > lastKnownDay) this.lastKnownDay = currentDay;
        for (Loan loan : loans) {
            if (loan.getStatus() != Loan.LoanStatus.ACTIVE) continue;
            long overdueDays = currentDay - loan.getStartDay() - loan.getTermDays();
            if (overdueDays > loan.getTermDays() * 2L) {
                double remaining = loan.getRemaining();
                if (remaining <= 0) continue;
                loan.markDefaulted();
                totalLoans -= remaining;
                accruedLoanLoss += remaining;
                depositAccounts.merge(loan.getBorrowerId(), -remaining, Double::sum);
                totalDeposits = Math.max(0, totalDeposits - remaining);
            }
        }
        recomputeReserveDecomposition();
    }

    /**
     * 每日资产负债管理（<b>CB-t4b 之后：最后贷款人由"自动"改为"条件化"</b>）。
     *
     * <p>流动性缺口处理（§6.10.1 / §6.10.2 / §14.4-C / Q7 加严 / R5）：
     * <ul>
     *   <li><b>开关生效</b>（{@code centralBank.isLastResortEffective()}，即 {@code centralBank.enabled ∧ lastResort}）：
     *       <b>★CB-T22 触发口径 = 当行实时准备金缺口</b>（{@code getSettlementDebt() > 0}，即 {@code RR > reserves}，
     *       与 §5.4 / I9 同口径）**且**状态机判为 {@code TROUBLED}，才调用**条款化**救助入口
     *       {@link CentralBank#provideLastResortLending(Bank, double, double, long)}
     *       （抵押折价 30%、利率 = SLF + 2%、次数上限 3、成功后转 {@code RESCUED} + 降杠杆 30 交易日 + 公开登记）；
     *       未获救助（非 TROUBLED / 额度·抵押·次数不足）⇒ 缺口以 {@code settlementDebt} 显式登记（I9），
     *       <b>不得</b>再自动投放基础货币（否则等价于"凭空造钱"）。
     *       <br>⚠ <b>为何不再用"库存现金 + 准备金 &lt; 达标额"当触发门槛</b>：该门槛是**合计**口径，
     *       而 RR 只考核**准备金账户**余额 ⇒ "合计达标、准备金账户不足"时门槛为假 ⇒ 救助永不触发
     *       （CB-t4b 首版即如此，端到端实测 {@code llrCount 0→0}）。</li>
     *   <li><b>开关关闭</b> ⇒ 保留原有的无条件 {@code emergencyLending} 调用（**逐位复现基线**，R5 的同 run 对照锚点），
     *       其门槛与金额仍按**原始合计口径**（{@code vaultCash + reserves < required}）逐字不变。</li>
     * </ul>
     * <p>记账纪律：{@code provideLastResortLending} 内部已完成
     * {@code bank.addEmergencyReserves(...)} 与 {@code book(LAST_RESORT, ...)}（两侧对称）⇒ 本类**不再重复记账**；
     * 仅在末尾刷新准备金分解（{@code RR/ER/settlementDebt}）。
     * <p>未改动部分：达标口径（{@code getTotalDepositsAll() × reserveRequirement}）与国债配置块保持逐字不变。
     */
    public void manageAssets(Government government, CentralBank centralBank) {
        double required = getTotalDepositsAll() * reserveRequirement;
        // ★CB-T22：on 态的条件化救助**不得**挂在 `vaultCash + reserves < required` 之下。
        // 该门槛是"库存现金 + 准备金合计"口径，而 RR 只考核**准备金账户**余额 ⇒
        // "合计达标、但准备金账户不足"（settlementDebt > 0）时该门槛为假，救助永不触发
        // （engine-dev 端到端实测 llrCount 0→0：TROUBLED 可达却永不救助）。
        // ⇒ on 态改按**当行实时准备金缺口**判定（与 §5.4 / I9 同口径）。
        if (centralBank != null && centralBank.isLastResortEffective()) {
            recomputeReserveDecomposition();
            double reserveShortage = getSettlementDebt();          // = max(0, RR − reserves)，恒 ≥ 0
            if (reserveShortage > 0
                    && centralBank.getTroubledBankState(getBankId())
                    == LastResortRecord.TroubledBankState.TROUBLED) {
                // 该入口内部已 addEmergencyReserves(...) + book(LAST_RESORT,...) ⇒ 本类不重复记账
                centralBank.provideLastResortLending(this, reserveShortage, investmentPortfolio, lastKnownDay);
            }
            // 无论是否获救助都刷新分解：获救助 ⇒ ER 上升；未获 ⇒ 缺口显式记入 settlementDebt（I9）
            recomputeReserveDecomposition();
        } else if (centralBank != null && vaultCash + reserves < required) {
            // 开关 off ⇒ 逐位复现基线（R5）：无条件紧急贷款，无任何附加副作用
            centralBank.emergencyLending(this, required - (vaultCash + reserves));
        }
        double excess = vaultCash - required;
        if (excess > getTotalDepositsAll() * 0.1 && government != null && !government.getIssuedBonds().isEmpty()) {
            GovernmentBond bond = government.getIssuedBonds().get(0);
            double cost = bond.getFaceValue();
            if (excess >= cost) {
                vaultCash -= cost;
                investmentPortfolio += cost;
                bond.setHolderUuid(UUID.randomUUID());
            }
        }
    }

    // ============================================================
    // CB-t2：货币分层 / 准备金分解 / rr 权威 / 贷款利率 / 问题银行约束（§5.3/§5.4/§6.5/§6.10.1）
    // ============================================================

    // ---- §5.3 存款分类账（只读出口；names 按 .verify/CB_INTERFACE_FREEZE.md §2.4 冻结）----

    /** 单位活期（1994/2025 两口径都计入 M1；派生量 = {@code totalDeposits − 个人活期}）。 */
    public double getDemandDepositsCorporate() { return Math.max(0, totalDeposits - demandDepositsHousehold); }

    /** 个人活期（仅 2025 新口径计入 M1）。 */
    public double getDemandDepositsHousehold() { return demandDepositsHousehold; }

    /** 个人储蓄（M2 非 M1）。 */
    public double getSavingsHousehold() { return savingsHousehold; }

    /** 其他存款/货币市场类（= 既有 {@code moneyMarketDeposits} 的语义别名，§5.3）。 */
    public double getOtherMoneyMarket() { return moneyMarketDeposits; }

    /** 支付机构客户备付金（M1 + B 的 Pay 项；不计入达标基数）。 */
    public double getPaymentReserve() { return paymentReserve; }

    /** 达标存款基数（§5.4：单位活期 + 个人活期 + 单位定期 + 个人储蓄 + 其他货币市场；**不含**备付金）。 */
    public double getReserveBase() { return totalDeposits + timeDeposits + moneyMarketDeposits + savingsHousehold; }

    /**
     * 按分类入账（§5.3）。活期类计入 {@code totalDeposits} + 客户账本；其余类进入各自分类层。
     * 现金侧沿用既有模型（{@code vaultCash += amount} → {@code rebalanceReserves()}）。
     */
    public void depositClass(UUID customerId, DepositClass cls, double amount) {
        if (cls == null || !(amount > 0)) return;
        switch (cls) {
            case DEMAND_CORPORATE -> {
                vaultCash += amount;
                totalDeposits += amount;
                depositAccounts.merge(customerId, amount, Double::sum);
            }
            case DEMAND_HOUSEHOLD -> {
                vaultCash += amount;
                totalDeposits += amount;
                demandDepositsHousehold += amount;
                depositAccounts.merge(customerId, amount, Double::sum);
            }
            case TIME_CORPORATE -> {
                vaultCash += amount;
                timeDeposits += amount;
            }
            case SAVINGS_HOUSEHOLD -> {
                vaultCash += amount;
                savingsHousehold += amount;
            }
            case OTHER_MONEY_MARKET -> {
                vaultCash += amount;
                moneyMarketDeposits += amount;
            }
            case PAYMENT_RESERVE -> {
                vaultCash += amount;
                paymentReserve += amount;
            }
        }
        emitCashFlow(BaseMoneyFlow.Kind.NONBANK_CASH_DEPOSIT, amount);
        rebalanceReserves();
    }

    /**
     * 按分类支取（§5.3）。活期类经既有 {@link #withdraw} 走「客户余额 + 现金头寸」校验；
     * 非活期类（定期/个人储蓄/货币市场/备付金）直接作用于该层与库存现金，与既有
     * {@code depositTime}/{@code depositMoneyMarket} 的对称写法一致（不触 {@code totalDeposits}）。
     *
     * @return 实际支取额（0 = 该层余额不足或活期余额不足）
     */
    public double withdrawClass(UUID customerId, DepositClass cls, double amount) {
        if (cls == null || !(amount > 0)) return 0;
        switch (cls) {
            case DEMAND_CORPORATE, DEMAND_HOUSEHOLD -> {
                if (!withdraw(customerId, amount)) return 0;
                if (cls == DepositClass.DEMAND_HOUSEHOLD) {
                    demandDepositsHousehold = Math.max(0, demandDepositsHousehold - amount);
                }
                recomputeReserveDecomposition();
                return amount;
            }
            case TIME_CORPORATE -> {
                if (timeDeposits < amount) return 0;
                timeDeposits -= amount;
            }
            case SAVINGS_HOUSEHOLD -> {
                if (savingsHousehold < amount) return 0;
                savingsHousehold -= amount;
            }
            case OTHER_MONEY_MARKET -> {
                if (moneyMarketDeposits < amount) return 0;
                moneyMarketDeposits -= amount;
            }
            case PAYMENT_RESERVE -> {
                if (paymentReserve < amount) return 0;
                paymentReserve -= amount;
            }
            default -> {
                return 0;
            }
        }
        vaultCash = Math.max(0, vaultCash - amount);
        emitCashFlow(BaseMoneyFlow.Kind.NONBANK_CASH_WITHDRAW, amount);
        recomputeReserveDecomposition();
        return amount;
    }

    // ---- §5.4 准备金分解（RR/ER/结算欠款）----

    /** 法定准备金 RR_i。 */
    public double getReservesRequired() { return reservesRequired; }

    /** 超额准备金 ER_i（= {@code reserves − RR_i}，恒 ≥ 0）。 */
    public double getReservesExcess() { return reservesExcess; }

    /** 对央行结算欠款（I9 兜底，恒 ≥ 0；RR 不足时显式记欠款而**不是**把准备金改负）。 */
    public double getSettlementDebt() { return settlementDebt; }

    /** 每交易日由央行/银行管理器调用：写入权威 rr 并重算 RR_i/ER_i/settlementDebt（§5.4）。 */
    public void settleReserveRequirement(double rrEff) {
        setReserveRequirementEffective(rrEff);
    }

    /** 央行权威 rr（读不出权威值时回落 final 字段，保证任何时刻有确定值）。 */
    public double getReserveRequirementEffective() {
        return Double.isNaN(reserveRequirementEffective) ? reserveRequirement : reserveRequirementEffective;
    }

    /** 写入央行权威 rr（钳制到 {@code [0.01,0.25]}，与央行域一致，R2）。 */
    public void setReserveRequirementEffective(double rr) {
        if (Double.isFinite(rr) && rr > 0) {
            this.reserveRequirementEffective = Math.max(RR_MIN, Math.min(RR_MAX, rr));
        }
        recomputeReserveDecomposition();
    }

    /** rr 同步开关（§4 {@code centralBank.requiredReserveSync}；false ⇒ 全部口径回落基线）。 */
    public boolean isReserveSyncEnabled() { return reserveSyncEnabled; }

    /** 设置 rr 同步开关（false ⇒ {@link #issueLoan} 与 {@link #rebalanceReserves} 走基线口径）。 */
    public void setReserveSyncEnabled(boolean enabled) {
        this.reserveSyncEnabled = enabled;
        recomputeReserveDecomposition();
    }

    /** 生效 rr（同步开 ⇒ 权威值；关 ⇒ 既有 final 字段值）。 */
    private double rrEffective() { return reserveSyncEnabled ? getReserveRequirementEffective() : reserveRequirement; }

    /** 按当前存款分层与生效 rr 重算 RR_i / ER_i / settlementDebt（幂等、确定性、无随机）。 */
    public void recomputeReserveDecomposition() {
        this.reservesRequired = Math.max(0.0, rrEffective() * getReserveBase());
        this.reservesExcess = Math.max(0.0, reserves - reservesRequired);
        this.settlementDebt = Math.max(0.0, reservesRequired - reserves);
    }

    /** 读档恢复分类层（§9.1：旧档缺键 → 0 = 1994 旧口径），并重算分解。 */
    public void restoreClassifiedLayers(double household, double savings, double payment) {
        this.demandDepositsHousehold = Math.max(0, household);
        this.savingsHousehold = Math.max(0, savings);
        this.paymentReserve = Math.max(0, payment);
        recomputeReserveDecomposition();
    }

    /**
     * 设置现金头寸（{@code vaultCash}/{@code reserves}）：**读档恢复** 与 **银行间清算** 两个用途共用
     * （CB-t2 引入为读档恢复；CB-t4 起同时用于银行间市场的 ER 转移，captain 已批准本 Javadoc 泛化）。
     *
     * <p>语义：直接写入两值（**不经** {@code rebalanceReserves}，避免二次搬动现金），随后重算准备金分解。
     * 调用方须自行保证 {@code reserves ≥ 0}（该方法对入参钳制到 ≥ 0）。
     *
     * <p><b>读档恢复用途（CB-t2，D-CB-t2-4）</b>：SR-6 的贷款重放会「反冲派生存款」（走 {@code withdraw}），
     * 把现金侧多扣一笔 ⇒ 读档后 {@code vaultCash/reserves} 比存档低（实测每笔贷款 −1 笔本金）；
     * 由于 I1 的 B 含 {@code Vc}，该漂移会让读档后 I10 永久失败（破坏 CB-14）⇒ 读档末尾按存档值回填。
     *
     * <p><b>银行间清算用途（CB-t4）</b>：贷出行的 ER 转移（{@code reserves − Δ}）；借入行使用
     * {@link #addEmergencyReserves(double)}（{@code reserves + Δ}）。两个方向共同保证
     * 银行间拆借**不触存款账本**且 ER 总量守恒。
     */
    public void restoreCashPosition(double vault, double reserves) {
        this.vaultCash = Math.max(0, vault);
        this.reserves = Math.max(0, reserves);
        recomputeReserveDecomposition();
    }

    // ---- §6.5 LPR 传导的银行侧（LPR 由央行注入；本类只做加点与风险溢价）----

    /** LPR 1Y 注入（§6.5；由 CB-t3 的 {@code updateLpr(bankManager)} 逐行注入）。 */
    public void setLprOneYear(double lpr) {
        if (Double.isFinite(lpr) && lpr > 0) this.lprOneYear = lpr;
    }

    /** LPR 是否已注入（未注入时 {@link #getLoanRateEffective()} 按 LPR=0 计算，**如实标注**，不得静默）。 */
    public boolean isLprInjected() { return !Double.isNaN(lprOneYear); }

    /** 已注入的 LPR 1Y（未注入返回 0）。 */
    public double getLprOneYear() { return Double.isNaN(lprOneYear) ? 0.0 : lprOneYear; }

    /** 银行加点：准备金越紧、加点越高（§6.5：{@code 0.005 + 0.01 × (1 − ER_i / max(1, RR_i))}）。 */
    public double getBankSpread() {
        double rr = Math.max(1.0, reservesRequired);
        return BANK_SPREAD_BASE + BANK_SPREAD_RESERVE_COEF * (1.0 - reservesExcess / rr);
    }

    /** 风险溢价（§6.5：{@code 0.02 × nplRatio}）。 */
    public double getRiskPremium() { return NPL_PREMIUM * getNplRatio(); }

    /** 不良率（CB-t3 状态机的输入之一，§6.10.1；= 累计坏账 / (贷款余额 + 累计坏账)）。 */
    public double getNplRatio() {
        double denom = Math.max(1.0, totalLoans + accruedLoanLoss);
        return accruedLoanLoss / denom;
    }

    /**
     * 该行实际放贷加权利率（§3.3/§6.5，内生）：
     * {@code clamp(LPR + bankSpread + riskPremium, 0.001, 0.50)}。
     * ⇒ **不再等于**构造常量 {@code primeRate}（CB-9 判据）；LPR 未注入时按 0 计并由 {@link #isLprInjected()} 标注。
     */
    public double getLoanRateEffective() {
        double rate = getLprOneYear() + getBankSpread() + getRiskPremium();
        return Math.max(PRIME_RATE_MIN, Math.min(PRIME_RATE_MAX, rate));
    }

    // ---- §6.10.1 问题银行约束（状态由 CB-t3 状态机推送；本类只做约束查表）----

    /** 当前问题银行状态（默认 {@code HEALTHY}）。 */
    public LastResortRecord.TroubledBankState getTroubledState() { return troubledState; }

    /** 推送问题银行状态（CB-t3 状态机唯一入口；同时按状态表更新放贷上限与分红限制）。 */
    public void setTroubledState(LastResortRecord.TroubledBankState state) {
        if (state != null) this.troubledState = state;
    }

    /** 放贷上限倍数（{@code equity_i × multiplier}；CB-23② 锚点）。 */
    public double getLendingCapMultiplier() { return troubledState.lendingCapMultiplier(); }

    /** 是否禁止分红（CB-23② 锚点）。 */
    public boolean isDividendBlocked() { return troubledState.dividendBlocked(); }

    // ---- §6.1 现金形态转换记账钩子（记账实现归 CB-t3；未注入 sink 时零影响）----

    /** 注入基础货币记账 sink（CB-t3 的 {@code bookBaseMoneyFlow} 绑定；null ⇒ 不记账）。 */
    public void setBaseMoneyFlowSink(Consumer<BaseMoneyFlow> sink) { this.baseMoneyFlowSink = sink; }

    /** 现金转换钩子开关（§4 {@code centralBank.balanceSheet}）。 */
    public void setCashFlowBookingEnabled(boolean enabled) { this.cashFlowBookingEnabled = enabled; }

    private void emitCashFlow(BaseMoneyFlow.Kind kind, double amount) {
        if (!cashFlowBookingEnabled || baseMoneyFlowSink == null || !(amount > 0)) return;
        baseMoneyFlowSink.accept(new BaseMoneyFlow(kind, amount, bankId, lastKnownDay));
    }

    public void addProduct(FinancialProduct product) { offeredProducts.add(product); }
    public List<FinancialProduct> getProducts() { return offeredProducts; }
}