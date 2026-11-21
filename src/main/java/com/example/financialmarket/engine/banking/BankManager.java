package com.example.financialmarket.engine.banking;

import com.example.financialmarket.engine.monetary.MonetaryAggregator;
import com.example.financialmarket.engine.products.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BankManager {
    /** 游戏内默认商业银行数量（BT-2）：BANK_A/BANK_B/BANK_C/BANK_D。 */
    public static final int DEFAULT_BANK_COUNT = 4;
    /** 默认 4 家商业银行 ID（确定性顺序，供枚举/校验）。 */
    private static final String[] DEFAULT_BANK_IDS = {"BANK_A", "BANK_B", "BANK_C", "BANK_D"};

    private final Map<String, Bank> banks = new LinkedHashMap<>();
    private final CentralBank centralBank = new CentralBank();
    private final InterbankMarket interbankMarket = new InterbankMarket();

    // ============================================================
    // CB-t2（CENTRAL_BANK_SPEC v1.5 §5.1/§7.2 P1/§10.1 N1）：
    // 分层聚合器实例 + 开关组。聚合器由本类持有（它需要 banks/centralBank 两个账本源），
    // 引擎经 getMonetaryAggregator() 取用（t5 接线）。
    // ============================================================
    private final MonetaryAggregator monetaryAggregator = new MonetaryAggregator();

    /** §4 开关：分层聚合（默认 true）。false ⇒ computeM0/M1/M2/getMoneyMultiplier **逐位复现基线**。 */
    private boolean aggregationEnabled = true;
    /** §4 开关：LPR 传导（默认 true）。false ⇒ 固定 50000/行/日（CB-20① 唯一锚点）。 */
    private boolean lprTransmissionEnabled = true;
    /** §4 开关：rr 同步（默认 true）。false ⇒ 各行 rr 口径与放贷约束回落基线。 */
    private boolean reserveSyncEnabled = true;

    /** 最近一次已知的流通中现金（playerCash）；供无参方法与 getMoneyMultiplierM 使用（避免被 0 覆盖）。 */
    private double lastCirculationCash = 0.0;

    /** §6.5 需求函数：利率敏感度（先验档，§0.10.2 ⇒ 利率每高 1pp，需求降 12%）。 */
    public static final double LOAN_RATE_SENSITIVITY = 12.0;
    /** §6.5 需求函数：基准需求（利率等于中性利率时需求因子 = 1）。 */
    public static final double LOAN_DEMAND_BASE = 1.0;
    /** §6.5 需求函数：中性贷款利率（= 60bp 上方的中性档）。 */
    public static final double NEUTRAL_LOAN_RATE = 0.06;
    /** §6.5 需求函数：每日填缺口的比例（≈20 交易日填满）。 */
    public static final double DAILY_ORIGINATION_FRACTION = 0.05;

    public BankManager() {
        // 四家AI银行（BT-2）
        Bank bankA = new Bank("BANK_A", "下界皇家银行", null, 1000000, 0.10, 0.05, 0.01);
        Bank bankB = new Bank("BANK_B", "末地商业银行", null, 800000, 0.10, 0.06, 0.012);
        Bank bankC = new Bank("BANK_C", "主世界发展银行", null, 1200000, 0.10, 0.045, 0.008);
        Bank bankD = new Bank("BANK_D", "虚空联合银行", null, 1500000, 0.10, 0.045, 0.008);
        banks.put(bankA.getBankId(), bankA);
        banks.put(bankB.getBankId(), bankB);
        banks.put(bankC.getBankId(), bankC);
        banks.put(bankD.getBankId(), bankD);

        // 注入银行间市场
        for (Bank bank : banks.values()) {
            bank.setInterbankMarket(interbankMarket);
        }

        // 先添加不需要 MarketState 的产品
        bankA.addProduct(new SavingsProduct("活期存款", 1, 0, 0.005, true));
        bankA.addProduct(new SavingsProduct("一年定期", 100, 30, 0.025, false));
        // 基金产品稍后注入 MarketState（在 init 时调用 injectMarketState）
        bankC.addProduct(new InsuranceProduct("财产险", 200, 0, 10000, 0.02));
        // BANK_D：第 4 家默认商业银行（BT-2）
        bankD.addProduct(new SavingsProduct("活期存款", 1, 0, 0.005, true));
    }

    /** 在 MarketEngine 初始化后调用，用于注入需要 MarketState 的产品 */
    public void injectMarketState(com.example.financialmarket.engine.MarketState state) {
        for (Bank bank : banks.values()) {
            for (FinancialProduct p : bank.getProducts()) {
                if (p instanceof FundProduct fund && fund.getMarketState() == null) {
                    fund.setMarketState(state);
                }
            }
        }
    }

    public Bank getBank(String id) { return banks.get(id); }
    public Collection<Bank> getAllBanks() { return banks.values(); }

    /** 游戏内默认 4 家商业银行（BT-2），供 UI/命令枚举；确定性顺序。 */
    public List<Bank> getDefaultBanks() {
        List<Bank> result = new ArrayList<>();
        for (String id : DEFAULT_BANK_IDS) {
            Bank b = banks.get(id);
            if (b != null) result.add(b);
        }
        return result;
    }

    public CentralBank getCentralBank() { return centralBank; }
    public InterbankMarket getInterbankMarket() { return interbankMarket; }

    public void registerBank(Bank bank) {
        banks.put(bank.getBankId(), bank);
        bank.setInterbankMarket(interbankMarket);
    }

    // ===================== M1 / M2 分层记账（货币供给度量） =====================

    /** Σ各银行活期存款（M1 存款部分）。 */
    public double getTotalDemandDeposits() {
        double sum = 0;
        for (Bank bank : banks.values()) sum += bank.getDemandDeposits();
        return sum;
    }

    /** Σ各银行定期/储蓄存款（M2 定期层）。 */
    public double getTotalTimeDeposits() {
        double sum = 0;
        for (Bank bank : banks.values()) sum += bank.getTimeDeposits();
        return sum;
    }

    /** Σ各银行货币市场存款（M2 货币市场层）。 */
    public double getTotalMoneyMarketDeposits() {
        double sum = 0;
        for (Bank bank : banks.values()) sum += bank.getMoneyMarketDeposits();
        return sum;
    }

    /** Σ各银行全口径存款（活期 + 定期 + 货币市场）。 */
    public double getTotalDepositsAll() {
        double sum = 0;
        for (Bank bank : banks.values()) sum += bank.getTotalDepositsAll();
        return sum;
    }

    // ============================================================
    // CB-t2（§5.1 / §7.2 P1 / §10.1 N1）：货币分层聚合与 P1 一义化
    // 冻结名（见 .verify/CB_INTERFACE_FREEZE.md §10）：
    //   getBaseMoneyB()（= B，基础货币）· getCirculationCash()（= C，流通中现金）
    //   getMoneyMultiplierM()（= M2/B，I6）· getCashMultiplierLegacy(cash)（旧口径逐位兼容）
    // ============================================================

    /** 分层聚合器实例（引擎取用；t5 将其接入 runDailyFiat §8.3⑩）。 */
    public MonetaryAggregator getMonetaryAggregator() { return monetaryAggregator; }

    /** 分层聚合开关（§4 centralBank.aggregation）。 */
    public boolean isAggregationEnabled() { return aggregationEnabled; }

    /** 设置分层聚合开关（false ⇒ 本类的 computeM0/M1/M2/getMoneyMultiplier 走基线公式，逐位复现）。 */
    public void setAggregationEnabled(boolean enabled) {
        this.aggregationEnabled = enabled;
        monetaryAggregator.setAggregationEnabled(enabled);
    }

    /** LPR 传导开关（§4 centralBank.lpr）。 */
    public boolean isLprTransmissionEnabled() { return lprTransmissionEnabled; }

    /** 设置 LPR 传导开关（false ⇒ 信贷派生逐位复现固定 50000/行/日）。 */
    public void setLprTransmissionEnabled(boolean enabled) { this.lprTransmissionEnabled = enabled; }

    /** rr 同步开关（§4 centralBank.requiredReserveSync）。 */
    public boolean isReserveSyncEnabled() { return reserveSyncEnabled; }

    /** 设置 rr 同步开关（同时传播到各行）。 */
    public void setReserveSyncEnabled(boolean enabled) {
        this.reserveSyncEnabled = enabled;
        for (Bank bank : banks.values()) bank.setReserveSyncEnabled(enabled);
    }

    /** 最近一次已知的流通中现金（诊断/复算用）。 */
    public double getLastCirculationCash() { return lastCirculationCash; }

    /** 引擎每日注入真实流通中现金（在线玩家现金之和）并即时刷新聚合（§5.1/§8.3⑩）。 */
    public void setCirculationCash(double circulationCash) {
        refreshAggregator(circulationCash);
    }

    /** 以给定流通现金刷新聚合（内部统一入口，保证缓存与 getter 一致）。 */
    private void refreshAggregator(double circulationCash) {
        double cash = Math.max(0, circulationCash);
        this.lastCirculationCash = cash;
        monetaryAggregator.refresh(centralBank, this, () -> cash);
    }

    /**
     * 基础货币 B（= I1 右侧之和，§3.1）。聚合开启时取分层聚合器的 B；关闭时回落既有标量。
     * <p>P1 冻结新方法名（替代「{@code computeM0()} 无参 = 基础货币」的旧义）。
     */
    public double getBaseMoneyB() {
        if (!aggregationEnabled) return centralBank.getBaseMoney();
        refreshAggregator(lastCirculationCash);
        return monetaryAggregator.getBaseMoneyB();
    }

    /** 流通中现金 C（= M0）。P1 冻结新方法名（替代 {@code computeM0(playerCash)} 的旧义）。 */
    public double getCirculationCash() {
        if (!aggregationEnabled) return Math.max(0, lastCirculationCash);
        refreshAggregator(lastCirculationCash);
        return monetaryAggregator.getCirculationCash();
    }

    /**
     * 货币基数（**兼容语义**，§13.8：调用点若依赖旧义"返回 baseMoney"，此方法保持不变）。
     *
     * <p>⇒ 聚合开启时返回**系统记账的基础货币**（{@code outstandingB}，数值与改动前一致）；
     * 而「I1 右侧之和」的 B 由 {@link #getBaseMoneyB()} 给出（§3.1 定义）。
     * 二者之差即 {@code MonetaryAggregator.getDivergenceAccount()}（CB-t3 迁移配平后应为 0）。
     */
    public double computeM0() {
        if (!aggregationEnabled) return centralBank.getBaseMoney();
        refreshAggregator(lastCirculationCash);
        return monetaryAggregator.getOutstandingBookedB();
    }

    /**
     * M0 = 流通现金（玩家持有现金，即流通中货币）。P1：此名**只表示流通现金**，
     * 不再表示基础货币（基础货币见 {@link #getBaseMoneyB()}）。
     *
     * <p>对应 spec 恒等式 I2：{@code M0 = C}。央行 baseMoney（{@link #computeM0()} 无参）为系统记账的
     * 基础货币（流通现金 + 库存现金 + 准备金等的记账侧）。
     */
    public double computeM0(double playerCash) {
        if (!aggregationEnabled) return Math.max(0, playerCash);
        refreshAggregator(playerCash);
        return monetaryAggregator.getM0();
    }

    /** M1 = 流通现金（playerCash）+ 单位活期 + 个人活期 + 备付金（I3；聚合关闭时回落基线公式）。 */
    public double computeM1(double playerCash) {
        if (!aggregationEnabled) return Math.max(0, playerCash) + getTotalDemandDeposits();
        refreshAggregator(playerCash);
        return monetaryAggregator.getM1();
    }

    /** M2 = M1 + 单位定期 + 个人储蓄 + 其他货币市场（I4；聚合关闭时回落基线公式）。 */
    public double computeM2(double playerCash) {
        if (!aggregationEnabled) {
            return computeM1(playerCash) + getTotalTimeDeposits() + getTotalMoneyMarketDeposits();
        }
        refreshAggregator(playerCash);
        return monetaryAggregator.getM2();
    }

    /**
     * 实际货币乘数（I6）= {@code M2 / B}（B = {@link #getBaseMoneyB()}）。
     * <p>★D3 决议：基线的「现金乘数」（分母 = C）保留为 {@link #getCashMultiplierLegacy(double)}（逐位不变），
     * 二者须给同 run 对照表（CB-17③）。
     */
    public double getMoneyMultiplier(double playerCash) {
        if (!aggregationEnabled) return getCashMultiplierLegacy(playerCash);
        refreshAggregator(playerCash);
        return monetaryAggregator.getMoneyMultiplierM();
    }

    /**
     * 基线兼容口径（§1.7 D3）：现金乘数 = {@code M2 / M0(playerCash)}，与改动前**逐位一致**。
     * 仅用于同 run 对照与旧调用点，不参与 I6 判据。
     */
    public double getCashMultiplierLegacy(double playerCash) {
        double m0 = Math.max(0, playerCash);
        if (m0 <= 0) return 0;
        return computeM2Legacy(playerCash) / m0;
    }

    /** 基线口径 M1/M2（不依赖聚合器；供兼容对照） */
    private double computeM1Legacy(double playerCash) {
        return Math.max(0, playerCash) + getTotalDemandDeposits();
    }

    private double computeM2Legacy(double playerCash) {
        return computeM1Legacy(playerCash) + getTotalTimeDeposits() + getTotalMoneyMarketDeposits();
    }

    /** I6 新口径的显式入口（= {@code M2/B}，含 B ≤ 0 计数；分母为 {@link #getBaseMoneyB()}）。 */
    public double getMoneyMultiplierM() {
        refreshAggregator(lastCirculationCash);
        return monetaryAggregator.getMoneyMultiplierM();
    }

    /** 理论货币乘数 = 1 / 准备金率（央行可调）。 */
    public double getTheoreticalMoneyMultiplier() {
        return 1.0 / centralBank.getReserveRequirement();
    }

    /**
     * 每交易日准备金达标结算（§8.3④ / §5.4）：把央行权威 rr 写入各行并重算 RR_i / ER_i / settlementDebt。
     * 缺口**不会**把准备金改负（走 settlementDebt，I9/A5）。
     */
    public void settleReserveRequirement() {
        double rrEff = centralBank.getReserveRequirement();
        for (Bank bank : banks.values()) bank.settleReserveRequirement(rrEff);
    }

    /**
     * 基准信贷派生（体现货币乘数）：在准备金约束内为每行发放一笔基准贷款，使 M2 随放贷内生扩张。
     * 使用确定性的合成借户 UUID，无随机源，保持同种子可复现。
     *
     * <p><b>CB-t2（§6.5 / P5 / CB-20）</b>：{@code centralBank.lpr} 生效且 LPR 已被央行注入时，改用
     * <b>需求函数</b>：{@code 新增放贷 = max(0, equity×放贷上限倍数 − totalLoans) × demandFactor × 5%}，
     * 其中 {@code demandFactor = clamp(1 − 12 × (loanRate − 6%), 0, 1)}。
     * 否则（开关关闭，或 LPR 尚未注入 ⇒ 基线未接线态）走**基线分支**：
     * 固定 {@code 50000/行/日}、利率 {@code 0.05}、期限 30 日 —— 这是 CB-20① 的逐位复现锚点。
     *
     * @param loanAmount 基线分支的单笔贷款额（= 50000）
     * @return 成功派生（放贷）总额
     */
    public double runBaselineCreditCreation(double loanAmount) {
        UUID agent = UUID.nameUUIDFromBytes("baseline-credit-agent".getBytes(StandardCharsets.UTF_8));
        if (lprTransmissionEnabled && isAnyLprInjected()) {
            double created = 0;
            for (Bank bank : banks.values()) {
                double rate = bank.getLoanRateEffective();
                double demandFactor = Math.max(0.0, Math.min(1.0,
                        LOAN_DEMAND_BASE - LOAN_RATE_SENSITIVITY * (rate - NEUTRAL_LOAN_RATE)));
                double target = bank.getEquity() * bank.getLendingCapMultiplier() - bank.getTotalLoans();
                double amount = Math.max(0.0, target) * demandFactor * DAILY_ORIGINATION_FRACTION;
                if (amount <= 0) continue;
                Loan loan = bank.issueLoan(agent, "AGENT", amount, rate, 30, 0);
                if (loan != null) created += amount;
            }
            return created;
        }
        double created = 0;
        for (Bank bank : banks.values()) {
            if (bank.getTotalLoans() + loanAmount <= bank.getEquity() * 10) {
                Loan loan = bank.issueLoan(agent, "AGENT", loanAmount, 0.05, 30, 0);
                if (loan != null) created += loanAmount;
            }
        }
        return created;
    }

    /** LPR 是否已由央行注入到任一行（未注入 ⇒ 需求函数未接线，信贷派生保持基线行为）。 */
    private boolean isAnyLprInjected() {
        for (Bank bank : banks.values()) {
            if (bank.isLprInjected()) return true;
        }
        return false;
    }

    public void dailyUpdate(long currentDay, Government government) {
        interbankMarket.update(banks.values());
        for (Bank bank : banks.values()) {
            bank.manageAssets(government, centralBank);
            bank.dailyUpdate(currentDay);
        }
    }

    // ===================== 持久化（FM-7） =====================

    /** 确定性合成存款人 UUID（读档重建存款层用），无随机。 */
    private static final UUID RESTORE_DEPOSITOR =
            UUID.nameUUIDFromBytes("fiat-restore-depositor".getBytes(StandardCharsets.UTF_8));

    /** 确定性合成借户 UUID（读档重建贷款账本、无 borrowerId 时的兜底），无随机。 */
    private static final UUID RESTORE_LOAN_BORROWER =
            UUID.nameUUIDFromBytes("fiat-restore-loan-borrower".getBytes(StandardCharsets.UTF_8));

    /** 序列化各银行货币状态（准备金/存款/贷款/权益），供存档与验证。 */
    public CompoundTag saveToNBT() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Bank bank : banks.values()) {
            CompoundTag bt = new CompoundTag();
            bt.putString("bankId", bank.getBankId());
            // AS-1/BT-8：玩家银行归属与建行参数随 blob 持久化（读档可重建玩家银行）；AI 银行 owner 为 null 时跳过。
            bt.putString("name", bank.getName());
            if (bank.getOwnerUuid() != null) bt.putUUID("ownerUuid", bank.getOwnerUuid());
            bt.putDouble("reserveRequirement", bank.getReserveRequirement());
            bt.putDouble("primeRate", bank.getPrimeRate());
            bt.putDouble("depositRate", bank.getDepositRate());
            bt.putDouble("reserves", bank.getReserves());
            bt.putDouble("vaultCash", bank.getVaultCash());
            bt.putDouble("totalDeposits", bank.getDemandDeposits());
            bt.putDouble("timeDeposits", bank.getTimeDeposits());
            bt.putDouble("moneyMarketDeposits", bank.getMoneyMarketDeposits());
            bt.putDouble("totalLoans", bank.getTotalLoans());
            bt.putDouble("equity", bank.getEquity());
            // CB-t2（§5.3/§9.1）：新增分层字段（旧档缺 → 0 = 1994 旧口径；键只增不改，R1）
            bt.putDouble("demandDepositsHousehold", bank.getDemandDepositsHousehold());
            bt.putDouble("savingsHousehold", bank.getSavingsHousehold());
            bt.putDouble("paymentReserve", bank.getPaymentReserve());
            bt.putDouble("reserveRequirementEffective", bank.getReserveRequirementEffective());
            // F2（连接审计修复 #2）：序列化按客户活期存款账本（读档恢复玩家余额，SD-4：读档后余额保持；
            // 此前只存聚合，读档后玩家存款并入合成账户归零——账实不一致）。
            CompoundTag accounts = new CompoundTag();
            for (Map.Entry<java.util.UUID, Double> e : bank.getDepositAccounts().entrySet()) {
                accounts.putDouble(e.getKey().toString(), e.getValue());
            }
            bt.put("depositAccounts", accounts);
            // SR-6：序列化贷款账本（每笔 Loan），读档可恢复而非归零
            Loan[] loans = bank.getLoans().toArray(new Loan[0]);
            ListTag loanList = new ListTag();
            for (Loan loan : loans) {
                CompoundTag lt = new CompoundTag();
                lt.putUUID("loanId", loan.getLoanId());
                lt.putUUID("borrowerId", loan.getBorrowerId());
                lt.putString("borrowerType", loan.getBorrowerType());
                lt.putDouble("amount", loan.getPrincipal());
                lt.putDouble("remaining", loan.getRemaining());
                lt.putDouble("annualRate", loan.getAnnualRate());
                lt.putInt("termDays", loan.getTermDays());
                lt.putLong("startDay", loan.getStartDay());
                lt.putString("status", loan.getStatus().name());
                lt.putDouble("collateralValue", loan.getCollateralValue());
                loanList.add(lt);
            }
            bt.put("loans", loanList);
            list.add(bt);
        }
        tag.put("banks", list);
        return tag;
    }

    /**
     * 从持久化恢复各银行货币状态（FM-7）。旧存档/缺字段时用默认（不覆盖）。
     * <p>注意：Bank 类为 t7 名义 in-scope 之外，其私有字段（准备金/库存现金）无 setter；
     * 为保持严格 in-scope，此处通过 Bank 既有公共 API（deposit/depositTime/depositMoneyMarket）
     * 确定性重建各存款层（M1 活期 + M2 定期/货币市场），这是货币供给度量的核心状态；
     * 准备金/库存现金由重建后的准备金纪律（rebalanceReserves）近似确定。全确定性、无随机。
     */
    public void loadFromNBT(CompoundTag tag) {
        if (tag == null || !tag.contains("banks")) return;
        ListTag list = tag.getList("banks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag bt = list.getCompound(i);
            String bankId = bt.getString("bankId");
            Bank bank = banks.get(bankId);
            // BT-8 前向兼容：旧存档无 ownerUuid（AI 默认行）用现有 map 恢复；新存档含 ownerUuid 的
            // 玩家银行若不在 map（读档后新建引擎），按建行参数确定性重建并 registerBank（无随机）。
            if (bank == null && bt.contains("ownerUuid")) {
                UUID owner = bt.getUUID("ownerUuid");
                String name = bt.contains("name") ? bt.getString("name") : bankId;
                double equity = bt.getDouble("equity");
                double rr = bt.contains("reserveRequirement") ? bt.getDouble("reserveRequirement") : 0.1;
                double prime = bt.contains("primeRate") ? bt.getDouble("primeRate") : 0.06;
                double dep = bt.contains("depositRate") ? bt.getDouble("depositRate") : 0.02;
                bank = new Bank(bankId, name, owner, equity, rr, prime, dep);
                registerBank(bank);
            }
            if (bank == null) continue;
            double demand = bt.getDouble("totalDeposits");
            double time = bt.getDouble("timeDeposits");
            double moneyMarket = bt.getDouble("moneyMarketDeposits");
            // F2（连接审计修复 #2）：优先按「按户存款账本 + 现金头寸」恢复（读档后玩家余额保持、
            // vault/reserves 与存档一致）；旧档缺 "depositAccounts" 键时回落聚合并入确定性合成账户
            // （前向兼容，contains 兜底，读档不失败）。
            if (bt.contains("depositAccounts")) {
                Map<UUID, Double> accounts = new HashMap<>();
                CompoundTag accTag = bt.getCompound("depositAccounts");
                for (String key : accTag.getAllKeys()) {
                    try {
                        accounts.put(UUID.fromString(key), accTag.getDouble(key));
                    } catch (IllegalArgumentException ignored) {
                        // 旧档脏 key：跳过，不失败
                    }
                }
                bank.restoreLedger(accounts,
                        bt.getDouble("vaultCash"), bt.getDouble("reserves"),
                        demand, time, moneyMarket);
            } else {
                if (demand > 0) bank.deposit(RESTORE_DEPOSITOR, demand);
                if (time > 0) bank.depositTime(RESTORE_DEPOSITOR, time);
                if (moneyMarket > 0) bank.depositMoneyMarket(RESTORE_DEPOSITOR, moneyMarket);
            }
            // SR-6：恢复贷款账本（loans），避免读档后 totalLoans 归零、贷款丢失令 M2/货币乘数失真。
            // 全确定性重建：经 Bank.issueLoan 重放每笔贷款，设置 startDay/status/remaining，并反冲派生存款以保持总存款不变。
            // CB-t2：重建**不是新放贷行为** ⇒ 播放期间临时关闭 ER≥0 收紧口径（否则既有贷款会因新约束被拒、
            // 读档后贷款账本丢笔 ⇒ 破坏"读档不回归"）；播放结束按原值恢复开关。
            boolean syncBefore = bank.isReserveSyncEnabled();
            bank.setReserveSyncEnabled(false);
            try {
                restoreLoans(bank, bt);
            } finally {
                bank.setReserveSyncEnabled(syncBefore);
            }
            // CB-t2（§9.1 银行行）：分层字段恢复（contains 兜底：旧档缺 ⇒ 个人活期/个人储蓄/备付金 = 0 = 1994 旧口径），
            // 随后按 §9.1③ 重算准备金分解（RR_i = rr_old × 达标基数；不足 ⇒ settlementDebt，绝不把 reserves 改负）。
            bank.restoreClassifiedLayers(
                    bt.contains("demandDepositsHousehold") ? bt.getDouble("demandDepositsHousehold") : 0.0,
                    bt.contains("savingsHousehold") ? bt.getDouble("savingsHousehold") : 0.0,
                    bt.contains("paymentReserve") ? bt.getDouble("paymentReserve") : 0.0);
            if (bt.contains("reserveRequirementEffective")) {
                bank.setReserveRequirementEffective(bt.getDouble("reserveRequirementEffective"));
            }
            // CB-t2：按存档值回填现金头寸（回收 SR-6 反冲对 vaultCash/reserves 的侵占；
            // 否则 B 含 Vc ⇒ 读档即产生新偏差，I10 永久失败）。缺键则只重算分解。
            if (bt.contains("vaultCash") && bt.contains("reserves")) {
                bank.restoreCashPosition(bt.getDouble("vaultCash"), bt.getDouble("reserves"));
            }
            bank.recomputeReserveDecomposition();
        }
    }

    /**
     * 确定性重建银行的贷款账本（SR-6）。
     * 对存档中每笔 {@link Loan}：经 {@link Bank#issueLoan} 重放（借户/金额/利率/期限/抵押），
     * 并用 {@link Loan#setStartDay}/{@link Loan#repay}/{@link Loan#markDefaulted} 还原 startDay/status/remaining；
     * 最后用 {@link Bank#withdraw} 反冲 issueLoan 附带派生的存款，使总存款保持与存档一致。
     * 无随机（借户用存档 UUID，缺字段时用确定性 {@link #RESTORE_LOAN_BORROWER}），同种子同结果。
     */
    private void restoreLoans(Bank bank, CompoundTag bt) {
        if (!bt.contains("loans")) return;
        ListTag loanList = bt.getList("loans", Tag.TAG_COMPOUND);
        for (int j = 0; j < loanList.size(); j++) {
            CompoundTag lt = loanList.getCompound(j);
            UUID borrowerId = lt.contains("borrowerId") ? lt.getUUID("borrowerId") : RESTORE_LOAN_BORROWER;
            String borrowerType = lt.getString("borrowerType");
            if (borrowerType == null || borrowerType.isEmpty()) borrowerType = "AGENT";
            double amount = lt.getDouble("amount");
            double annualRate = lt.getDouble("annualRate");
            int termDays = lt.getInt("termDays");
            double collateral = lt.getDouble("collateralValue");
            if (amount <= 0) continue;
            Loan loan;
            try {
                loan = bank.issueLoan(borrowerId, borrowerType, amount, annualRate, termDays, collateral);
            } catch (Exception e) {
                continue;
            }
            if (loan == null) continue;
            loan.setStartDay(lt.getLong("startDay"));
            String status = lt.getString("status");
            double remaining = lt.getDouble("remaining");
            boolean repayFlag = false;
            boolean defaultFlag = false;
            if ("REPAID".equalsIgnoreCase(status)) {
                repayFlag = true;
            } else if ("DEFAULTED".equalsIgnoreCase(status)) {
                defaultFlag = true;
            } else if (remaining > 0 && remaining < amount) {
                loan.repay(amount - remaining); // 部分还款
            }
            if (repayFlag) loan.repay(loan.getRemaining());
            if (defaultFlag) loan.markDefaulted();
            // 反冲 issueLoan 派生的借户存款，保持总存款与存档一致（best-effort，无随机）
            try {
                bank.withdraw(borrowerId, amount);
            } catch (Exception ignored) {
                // 借户余额/现金不足时跳过反冲（轻微高估，不影响贷款账本恢复）
            }
        }
    }
}