package com.example.financialmarket.engine.banking;

import com.example.financialmarket.engine.role.FinanceMinisterGuardrail;

import java.util.*;

public class Government {
    /** 默认债务上限（t4/SR-4，绝对额；可按税基/GDP 比例调整）。 */
    public static final double DEFAULT_DEBT_CEILING = 5_000_000;
    /** 默认年度赤字预算（支出 − 税收 的净赤字上限，t4/SR-4）。 */
    public static final double DEFAULT_ANNUAL_DEFICIT_LIMIT = 2_000_000;

    private double treasury;               // 国库现金
    private double totalDebt;              // 总债务
    private double taxRate;                // 税率
    private final List<GovernmentBond> issuedBonds = new ArrayList<>();
    private double fiscalPolicyStance;     // -1(紧缩) 到 +1(扩张)
    private double totalTaxCollected;      // 累计实际征税收入（纳税锚点账本）
    private double totalSpent;             // 累计财政支出

    // ---- 财政约束（t4/SR-4/SR-5）：债务上限 + 年度赤字预算 ----
    private double debtCeiling = DEFAULT_DEBT_CEILING;
    private double annualDeficitLimit = DEFAULT_ANNUAL_DEFICIT_LIMIT;
    private double spendThisYear = 0;      // 本年累计财政支出
    private double taxThisYear = 0;        // 本年累计税收
    private final FinanceMinisterGuardrail guardrail;

    public Government() {
        this.treasury = 500000;
        this.totalDebt = 0;
        this.taxRate = 0.1;
        this.fiscalPolicyStance = 0;
        this.guardrail = new FinanceMinisterGuardrail(this);
    }

    /** 财政部长制度约束实例（t4/SR-4/SR-5），供交易/验证读取债务/赤字建议与 AI 兜底状态。 */
    public FinanceMinisterGuardrail getGuardrail() { return guardrail; }

    /**
     * 发行国债（t4/SR-4）。受债务上限约束：{@code totalDebt + faceValue > debtCeiling} 时拒绝（返回 null）。
     *
     * @return 成功返回债券，触限返回 {@code null}
     */
    public GovernmentBond issueBond(double faceValue, double couponRate, int maturityDays) {
        if (faceValue <= 0) return null;
        if (totalDebt + faceValue > debtCeiling) return null;
        GovernmentBond bond = new GovernmentBond(faceValue, couponRate, maturityDays);
        issuedBonds.add(bond);
        treasury += faceValue;
        totalDebt += faceValue;
        return bond;
    }

    /** 是否可发行指定面值国债（债务上限内）。 */
    public boolean canIssueBond(double faceValue) {
        return faceValue > 0 && totalDebt + faceValue <= debtCeiling;
    }

    /** 债务上限内剩余发债空间。 */
    public double debtRoom() {
        return Math.max(0, debtCeiling - totalDebt);
    }

    /**
     * 偿还国债
     */
    public void repayBond(GovernmentBond bond) {
        if (issuedBonds.remove(bond)) {
            treasury -= bond.getFaceValue();
            totalDebt -= bond.getFaceValue();
        }
    }

    /**
     * 财政支出（刺激经济）。
     * 法币化后：税收流入国库（receiveTax），此处以财政支出形式把国库资金回灌经济，
     * 形成"征税→国库→支出→流通"闭环，避免税收单向收缩货币（与 M2 记账/财政联动）。
     * <p>t4/SR-4：支出受<strong>年度赤字预算</strong>约束——本年净赤字（支出 − 税收）不得超过
     * {@link #annualDeficitLimit}，超出部分被钳制（AI 兜底）；此外仍受国库余额上限约束。
     */
    public void spend(double amount) {
        if (amount <= 0) return;
        double remainingBudget = Math.max(0, annualDeficitLimit - (spendThisYear - taxThisYear));
        double actual = Math.min(Math.min(amount, remainingBudget), treasury);
        if (actual <= 0) return;
        treasury -= actual;
        totalSpent += actual;
        spendThisYear += actual;
        // 资金流入市场/经济，增加货币流通（财政支出联动）
        // CB-t3（§7.2 C6）：央行侧记账钩子 —— FISCAL_SPEND（govDeposits−、C+）。
        // 钩子未注册时零执行（Govemment/TaxSystem 不持有央行引用），不影响既有数值链。
        CentralBank cb = CentralBank.getFiscalLedgerHook();
        if (cb != null) cb.onFiscalSpend(actual);
    }

    /**
     * 税收：法币信用锚。
     * 由纯公式改为<b>实际流转</b>——按税率对给定税基（income）计税并真实计入国库，
     * 使税收成为"可用来缴税的信用凭证"（现代法币的财政背书），同时回笼货币。
     *
     * @param income 税基（现金/财产/收入等）
     * @return 实际征收额（已计入 treasury 与 totalTaxCollected）
     */
    public double collectTax(double income) {
        if (income <= 0) return 0;
        return receiveTax(income * taxRate);
    }

    /**
     * 接收一笔税收并计入国库。由 TaxSystem 在完成玩家现金扣缴后调用，保证
     * "税收收入回流政府财政"与国库-现金真实流转。
     *
     * @param amount 税额
     * @return 计入国库的税额
     */
    public double receiveTax(double amount) {
        if (amount <= 0) return 0;
        treasury += amount;
        totalTaxCollected += amount;
        taxThisYear += amount;
        // CB-t3（§7.2 C6）：央行侧记账钩子 —— TAX_COLLECT（C−、govDeposits+）。
        CentralBank cb = CentralBank.getFiscalLedgerHook();
        if (cb != null) cb.onTaxCollected(amount);
        return amount;
    }

    public double getTotalTaxCollected() { return totalTaxCollected; }
    public double getTotalSpent() { return totalSpent; }

    /** 债务上限。 */
    public double getDebtCeiling() { return debtCeiling; }

    /** 设置（或按税基比例调整）债务上限，不得为负。 */
    public void setDebtCeiling(double ceiling) { this.debtCeiling = Math.max(0, ceiling); }

    /** 年度赤字预算上限。 */
    public double getAnnualDeficitLimit() { return annualDeficitLimit; }

    /** 设置年度赤字预算上限，不得为负。 */
    public void setAnnualDeficitLimit(double limit) { this.annualDeficitLimit = Math.max(0, limit); }

    /** 本年累计净赤字（支出 − 税收）。 */
    public double getCurrentYearNetDeficit() { return spendThisYear - taxThisYear; }

    /** 本年剩余可用赤字空间（≥0）。 */
    public double remainingDeficitRoom() {
        return Math.max(0, annualDeficitLimit - (spendThisYear - taxThisYear));
    }

    /** 从持久化恢复政府财政状态（FM-7）；旧存档缺字段时用默认值兜底。 */
    public void restoreState(double treasury, double totalDebt, double taxRate,
                             double totalTaxCollected, double totalSpent) {
        this.treasury = Math.max(0, treasury);
        this.totalDebt = Math.max(0, totalDebt);
        this.taxRate = Math.max(0, Math.min(0.3, taxRate));
        this.totalTaxCollected = Math.max(0, totalTaxCollected);
        this.totalSpent = Math.max(0, totalSpent);
    }

    /**
     * 调整财政政策
     */
    public void setFiscalStance(double stance) {
        this.fiscalPolicyStance = Math.max(-1, Math.min(1, stance));
    }

    public double getFiscalStance() { return fiscalPolicyStance; }
    public double getTreasury() { return treasury; }
    public double getTotalDebt() { return totalDebt; }
    public double getTaxRate() { return taxRate; }
    public List<GovernmentBond> getIssuedBonds() { return issuedBonds; }

    /**
     * 政府破产检查
     */
    public boolean isBankrupt() {
        return treasury < 0 && totalDebt > treasury * 3;
    }
}