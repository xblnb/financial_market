package com.example.financialmarket.engine.banking;

import com.example.financialmarket.attachment.PlayerEconomy;

import java.util.Collection;
import java.util.Objects;

/**
 * TaxSystem — 税收/纳税锚点（法币信用）。
 *
 * <p>法币化的核心特征之一：法币因"可用来缴税"而具有信用价值（现代法币的财政背书）。
 * 玩家/agent 必须持有现金（{@code PlayerEconomy.cash}）来纳税，从而给法币内在价值——
 * 这就是税收作为法币信用锚的作用。
 *
 * <p>税种（多税种）：
 * <ul>
 *   <li>交易税 {@code TRANSACTION}：按交易额计税（如商品成交额）。</li>
 *   <li>财产税 {@code PROPERTY}：按持仓/财产价值计税。</li>
 *   <li>所得税 {@code INCOME}：按收入计税。</li>
 *   <li>财富税 {@code WEALTH}：按玩家现金余额计税（推荐税基，直接回笼货币）。</li>
 * </ul>
 *
 * <p>税收收入回流 {@link Government} 国库（{@code government.receiveTax}），并经财政支出
 * （{@code government.spend}）回灌经济，形成"征税→国库→支出→流通"闭环。
 *
 * <p>避免税收过度收缩货币：
 * <ul>
 *   <li>{@code taxCap}：单笔纳税最多占缴税主体现金的比例，防止单次征税把玩家现金打到极低；</li>
 *   <li>{@code cashFloor}：税后现金保底，避免税收导致玩家现金归零；</li>
 *   <li>{@code spendingRebateRate}：国库收入以财政支出/补贴形式回流经济的比例，防止 M2 单向收缩。</li>
 * </ul>
 *
 * <p>可复现：本类为确定性计算（税率/税基/钳制均为固定参数），不引入任何随机源，
 * 天然同种子可复现（对齐 ECON_IMPROVEMENT_PLAN §6）。
 */
public class TaxSystem {

    /** 税种枚举。 */
    public enum TaxType {
        /** 交易税：按交易额计税。 */
        TRANSACTION,
        /** 财产税：按持仓/财产价值计税。 */
        PROPERTY,
        /** 所得税：按收入计税。 */
        INCOME,
        /** 财富税：按玩家现金余额计税。 */
        WEALTH
    }

    private final Government government;
    private final double transactionTaxRate;
    private final double propertyTaxRate;
    private final double incomeTaxRate;
    private final double wealthTaxRate;
    private final double taxCap;              // 单笔纳税最多占缴税主体现金比例（防归零）
    private final double cashFloor;           // 税后现金保底
    private final double spendingRebateRate;  // 国库收入回流比例（财政支出，避免过度收缩）

    private double totalCollected;            // 累计征税（税收信用锚账本）
    private double totalRecycled;             // 累计财政回流

    /**
     * 便捷构造：使用默认税率（交易 2%、财产 0.5%、所得 5%、财富 3%）、
     * 税帽 50%、税后现金保底 100、财政回流比例 60%。
     *
     * @param government 政府（接收税收并联动财政支出），不可为 null
     */
    public TaxSystem(Government government) {
        this(government, 0.02, 0.005, 0.05, 0.03, 0.5, 100.0, 0.6);
    }

    /**
     * 全参构造。
     *
     * @param government          政府（接收税收并联动财政支出），不可为 null
     * @param transactionTaxRate  交易税率
     * @param propertyTaxRate     财产税率
     * @param incomeTaxRate       所得税率
     * @param wealthTaxRate       财富税率
     * @param taxCap              单笔纳税最多占缴税主体现金比例，∈(0,1]
     * @param cashFloor           税后现金保底，≥0
     * @param spendingRebateRate  国库收入回流比例，∈[0,1]
     */
    public TaxSystem(Government government, double transactionTaxRate, double propertyTaxRate,
                     double incomeTaxRate, double wealthTaxRate, double taxCap,
                     double cashFloor, double spendingRebateRate) {
        this.government = Objects.requireNonNull(government, "government must not be null");
        this.transactionTaxRate = clampRate(transactionTaxRate);
        this.propertyTaxRate = clampRate(propertyTaxRate);
        this.incomeTaxRate = clampRate(incomeTaxRate);
        this.wealthTaxRate = clampRate(wealthTaxRate);
        this.taxCap = clampRate(Math.max(taxCap, 1e-6));
        this.cashFloor = Math.max(0, cashFloor);
        this.spendingRebateRate = clampRate(spendingRebateRate);
    }

    private static double clampRate(double rate) {
        return Math.max(0.0, Math.min(1.0, rate));
    }

    /** 交易税：按交易额计税。税额计入国库（交易场景由调用方在成交额处触发）。 */
    public double collectTransactionTax(double transactionValue) {
        if (transactionValue <= 0) return 0;
        return record(transactionValue * transactionTaxRate);
    }

    /** 所得税：按收入计税，从玩家现金扣缴并计入国库。 */
    public double collectIncomeTax(double income, PlayerEconomy eco) {
        if (income <= 0 || eco == null) return 0;
        return applyAndRecord(eco, income * incomeTaxRate);
    }

    /** 财产税：按财产（持仓）价值计税，从玩家现金扣缴并计入国库。 */
    public double collectPropertyTax(double propertyValue, PlayerEconomy eco) {
        if (propertyValue <= 0 || eco == null) return 0;
        return applyAndRecord(eco, propertyValue * propertyTaxRate);
    }

    /** 财富税：按玩家现金余额计税（推荐税基），从玩家现金扣缴并计入国库。 */
    public double collectWealthTax(PlayerEconomy eco) {
        if (eco == null) return 0;
        return applyAndRecord(eco, eco.getCash() * wealthTaxRate);
    }

    /** 对全体玩家征财富税，返回本批征税总额。每交易日调用一次。 */
    public double collectAll(Collection<PlayerEconomy> players) {
        if (players == null) return 0;
        double total = 0;
        for (PlayerEconomy eco : players) {
            if (eco != null) total += collectWealthTax(eco);
        }
        return total;
    }

    /**
     * 财政回流：把部分国库税收以财政支出形式回灌经济，避免税收单向收缩货币（防止 M2 收缩失衡）。
     * 回流额 = 本周期累计征税 × 回流比例，经 {@code government.spend} 支出。
     *
     * @return 回流金额
     */
    public double recycleFiscalSpending() {
        double rebate = totalCollected * spendingRebateRate;
        if (rebate <= 0) return 0;
        government.spend(rebate);
        totalRecycled += rebate;
        return rebate;
    }

    /**
     * 计算纳税后的实际税额，受税帽（taxCap）与现金保底（cashFloor）约束，
     * 避免税收导致玩家现金归零或过度收缩。
     */
    private double cappedTax(double rawTax, PlayerEconomy eco) {
        if (rawTax <= 0) return 0;
        double cash = Math.max(0, eco.getCash());
        double maxByCap = cash * taxCap;
        double maxByFloor = Math.max(0, cash - cashFloor);
        return Math.min(rawTax, Math.min(maxByCap, maxByFloor));
    }

    /** 从玩家现金扣税并计入国库（纳税锚点：缴税需现金）。 */
    private double applyAndRecord(PlayerEconomy eco, double rawTax) {
        double tax = cappedTax(rawTax, eco);
        if (tax <= 0) return 0;
        eco.setCash(Math.max(cashFloor, eco.getCash() - tax));
        return record(tax);
    }

    /** 记账：计入国库并累计征税统计。 */
    private double record(double amount) {
        if (amount <= 0) return 0;
        government.receiveTax(amount);
        totalCollected += amount;
        // CB-t3（§7.2 C6）：财政回流（recycleFiscalSpending → government.spend）已经在
        // Government.spend 内触发 FISCAL_SPEND 钩子；此处不再重复记账，避免同一笔双重入账。
        return amount;
    }

    public Government getGovernment() { return government; }
    public double getTransactionTaxRate() { return transactionTaxRate; }
    public double getPropertyTaxRate() { return propertyTaxRate; }
    public double getIncomeTaxRate() { return incomeTaxRate; }
    public double getWealthTaxRate() { return wealthTaxRate; }
    public double getTotalCollected() { return totalCollected; }
    public double getTotalRecycled() { return totalRecycled; }

    /** 净财政收缩 = 累计征税 − 累计财政回流。供 M2/货币流通记账参考（税后流通回笼净值）。 */
    public double netFiscalContraction() {
        return totalCollected - totalRecycled;
    }

    /** 从持久化恢复税收账本（FM-7）；缺字段/旧存档时保留默认值。 */
    public void restoreState(double totalCollected, double totalRecycled) {
        this.totalCollected = Math.max(0, totalCollected);
        this.totalRecycled = Math.max(0, totalRecycled);
    }
}
