package com.example.financialmarket.engine.monetary;

/**
 * 央行票据（CENTRAL_BANK_SPEC.md v1.5 §6.8；CB-t1 冻结，<b>实现权归 CB-t3</b>）。
 *
 * <p>定位：负债侧回笼工具（与资产侧的 OMO 相对），用于比较负债结构；余额进 I8 负债侧
 * {@code CentralBankBalanceSheet.getCentralBankBills()}，记账走
 * {@link BaseMoneyFlow.Kind#ISSUE_BILL}/{@link BaseMoneyFlow.Kind#REDEEM_BILL}。
 *
 * <p><b>冻结口径（登记）</b>
 * <ul>
 *   <li>期限口径沿用仓库既有约定「交易日序 + 期限天数」（与 {@code Loan}、{@code ReverseRepoOperation} 同族），
 *       到期日 = {@code issueDay + termDays}（由 {@link #maturityDay()} 派生，纯定义式）。</li>
 *   <li>发行总量上限 {@link #BILL_MAX_OUTSTANDING}（§6.8）由 CB-t3 在操作入口校验，本类不持有状态。</li>
 *   <li>{@code billId} 由 CB-t3 以确定性方式构造（禁止 {@code UUID.randomUUID()}；沿用仓库
 *       {@code UUID.nameUUIDFromBytes(...)} 式确定性命名）。</li>
 * </ul>
 */
public record Bill(String billId, double faceValue, double rate, long issueDay, int termDays) {

    /** 央票存续上限（§6.8 {@code BILL_MAX_OUTSTANDING}）。 */
    public static final double BILL_MAX_OUTSTANDING = 5_000_000;

    /** 到期交易日（= {@code issueDay + termDays}；纯派生，无状态变更）。 */
    public long maturityDay() { return issueDay + termDays; }

    /** 在给定交易日内是否已到期（{@code day ≥ maturityDay()}）；供 CB-t3 的日级兑付扫描使用。 */
    public boolean isMatured(long day) { return day >= maturityDay(); }

    /** 剩余交易日（已到期或参数异常 ⇒ 0；纯派生，确定性）。 */
    public long remainingDays(long day) {
        long left = maturityDay() - day;
        return Math.max(0, left);
    }

    /** 单张票面利息（简单利息口径，与既有 {@code Loan}/{@code ReverseRepoOperation} 同族）。 */
    public double interestAmount() { return faceValue * rate * termDays / 365.0; }

    /** 到期应付本息（面值 + 利息）。 */
    public double totalDue() { return faceValue + interestAmount(); }

    /**
     * 在给定交易日是否可在存续上限内计入新发行（{@code faceValue > 0} 且
     * 面值 ≤ 剩余上限；本类不持有总量状态 ⇒ 由 CB-t3 的 {@code issueBill} 做聚合校验）。
     */
    public boolean fitsOutstandingLimit(double currentOutstanding) {
        return faceValue > 0 && currentOutstanding + faceValue <= BILL_MAX_OUTSTANDING;
    }

    /** 单行摘要（中文，供命令/日志输出；不含换行，避免破坏表格归档）。 */
    public String summary() {
        return billId + " 面值=" + faceValue + " 利率=" + rate + " 发行日=" + issueDay
                + " 期限=" + termDays + " 到期日=" + maturityDay();
    }
}
