package com.example.financialmarket.engine.role;

import com.example.financialmarket.engine.banking.Government;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 财政部长制度约束（t4/SR-4/SR-5/SR-7）：债务上限 + 年度赤字预算 + AI 兜底接管。
 *
 * <p>约束对象为 t2 {@code RoleSystem} 中 {@code FINANCE_MINISTER} 角色的财政操作
 * （{@code issue_bond}/{@code spend}）。机制分三层：
 * <ol>
 *   <li><strong>债务上限</strong>：{@code Government.issueBond} 在
 *       {@code totalDebt + faceValue > debtCeiling} 时拒绝（本类提供 {@link #canIssueBond(double)} 供前置校验）。</li>
 *   <li><strong>赤字预算</strong>：{@code Government.spend} 受年度净赤字（支出−税收）上限约束，
 *       本类提供 {@link #canSpend(double)} 评估与 {@link #suggestedSpend(double)} 钳制建议。</li>
 *   <li><strong>AI 兜底接管</strong>：财政部长<strong>离线</strong>或<strong>失职</strong>（持续超出债务/赤字预算）时，
 *       {@link #shouldEngageAutopilot()} 判定接管，{@link #applyFiscalAutopilot()} 确定性回到税收基线（紧缩）。</li>
 * </ol>
 *
 * <p><strong>确定性</strong>：债务/赤字判定、钳制、AI 靠拢均为确定性函数，无随机，同种子同结果。
 *
 * <p>问责/接管<strong>不自动惩罚玩家</strong>，只记录可观察事件并钳制越界操作（SR-5 设计）。
 */
public final class FinanceMinisterGuardrail {

    /** 高杠杆阈值：总债务超过该倍税基视为高风险（建议收紧发债）。 */
    public static final double HIGH_LEVERAGE_TAX_RATIO = 10.0;
    /** 失职判定：持续超出预算 N 个交易日触发 AI 接管。 */
    public static final int ACCOUNTABILITY_DAYS = 10;
    /** AI 接管时把本年净赤字钳制到该比例（紧缩基线）。 */
    public static final double BASELINE_DEFICIT_RATIO = 0.5;

    private final Government government;
    private boolean financeMinisterOnline = true;
    private int overspendStreak = 0;
    private boolean autopilotEngaged = false;
    private final List<String> accountabilityEvents = new ArrayList<>();

    public FinanceMinisterGuardrail(Government government) {
        this.government = government;
    }

    public void setFinanceMinisterOnline(boolean online) {
        this.financeMinisterOnline = online;
    }

    public boolean isFinanceMinisterOnline() {
        return financeMinisterOnline;
    }

    // ---------- 债务上限 ----------

    /** 是否允许发行指定面值国债（债务上限内）。 */
    public boolean canIssueBond(double faceValue) {
        return government != null && government.canIssueBond(faceValue);
    }

    /** 债务上限内剩余空间。 */
    public double debtRoom() {
        return government != null ? government.debtRoom() : 0;
    }

    // ---------- 赤字预算 ----------

    /** 是否允许支出指定金额（受国库与年度赤字预算约束）。 */
    public boolean canSpend(double amount) {
        if (government == null || amount <= 0) return false;
        return amount <= government.getTreasury() && amount <= government.remainingDeficitRoom();
    }

    /** 建议（钳制到预算内）的支出额：{@code min(amount, 国库, 剩余赤字空间)}。 */
    public double suggestedSpend(double amount) {
        if (government == null || amount <= 0) return 0;
        return Math.max(0, Math.min(Math.min(amount, government.getTreasury()),
                government.remainingDeficitRoom()));
    }

    // ---------- 操作记录（交易/验证驱动） ----------

    /**
     * 记录一次发债操作：超债务上限 → 拒绝（AI 兜底）；否则记账并累计问责。
     *
     * @return 约束结果
     */
    public GovernmentResult recordIssueBond(double faceValue) {
        boolean accepted = canIssueBond(faceValue);
        if (!accepted) {
            overspendStreak++;
            recordEvent("发债被债务上限拒绝（请求 " + fmt(faceValue) + "，剩余空间 "
                    + fmt(debtRoom()) + "），AI 兜底介入");
        } else {
            overspendStreak = 0;
        }
        autopilotEngaged = shouldEngageAutopilot();
        String aiAction = accepted
                ? ""
                : "债务上限已满（" + fmt(government != null ? government.getTotalDebt() : 0)
                    + "/" + fmt(government != null ? government.getDebtCeiling() : 0)
                    + "），发债请求被拒绝";
        return new GovernmentResult(faceValue, accepted, !accepted, aiAction, "",
                autopilotEngaged);
    }

    /**
     * 记录一次支出操作：超出国库或年度赤字预算 → 钳制到余量（AI 兜底）。
     *
     * @return 约束结果（effectiveAmount 为实际允许支出额）
     */
    public GovernmentResult recordSpend(double amount) {
        double effective = suggestedSpend(amount);
        boolean accepted = effective == amount && amount > 0;
        if (!accepted) {
            if (amount > 0) {
                overspendStreak++;
                recordEvent("支出被赤字预算/国库约束：请求 " + fmt(amount) + "，钳制到 " + fmt(effective));
            }
        } else {
            overspendStreak = 0;
        }
        autopilotEngaged = shouldEngageAutopilot();
        String aiAction = accepted ? "" : ("支出已由 AI 兜底钳制到 " + fmt(effective)
                + "（剩余赤字空间 " + fmt(government != null ? government.remainingDeficitRoom() : 0) + "）");
        return new GovernmentResult(effective, accepted, !accepted, aiAction, "",
                autopilotEngaged);
    }

    // ---------- AI 兜底接管 ----------

    /** 判定是否应由 AI 接管财政：部长离线 或 持续超出预算（失职）。 */
    public boolean shouldEngageAutopilot() {
        return !financeMinisterOnline || overspendStreak >= ACCOUNTABILITY_DAYS;
    }

    /**
     * AI 接管财政（税收/支出基线）：把本年净赤字钳制到 {@code BASELINE_DEFICIT_RATIO} 的预算内，
     * 并返回建议的紧缩支出上限。确定性、无随机。
     *
     * @return 接管后建议的年度支出上限（基线）
     */
    public double applyFiscalAutopilot() {
        autopilotEngaged = shouldEngageAutopilot();
        if (!autopilotEngaged) {
            return government != null ? government.getAnnualDeficitLimit() : 0;
        }
        double baseline = government != null
                ? government.getAnnualDeficitLimit() * BASELINE_DEFICIT_RATIO
                : 0;
        if (government != null && government.getCurrentYearNetDeficit() > baseline) {
            recordEvent("AI 接管财政：本年净赤字超基线（"
                    + fmt(government.getCurrentYearNetDeficit()) + " > " + fmt(baseline) + "），收紧至 " + fmt(baseline));
            if (government != null) government.setAnnualDeficitLimit(baseline);
        }
        return baseline;
    }

    // ---------- 只读观察 ----------

    public boolean isAutopilotEngaged() {
        return autopilotEngaged;
    }

    public int getOverspendStreak() {
        return overspendStreak;
    }

    public List<String> getAccountabilityEvents() {
        return Collections.unmodifiableList(accountabilityEvents);
    }

    public void clearAccountabilityEvents() {
        accountabilityEvents.clear();
    }

    private void recordEvent(String event) {
        accountabilityEvents.add(event);
        System.out.println("🏛 [财政问责] " + event);
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    /**
     * 单次财政约束执行结果。
     *
     * @param effectiveAmount    实际生效金额（发债=faceValue；支出=钳制后金额）
     * @param accepted           是否采纳原值（false = AI 兜底拒绝/钳制）
     * @param consequenceTriggered 是否触发约束后果
     * @param aiAction           AI 兜底动作说明（空串=无介入）
     * @param accountability     问责说明（空串=无问责）
     * @param autopilotEngaged   是否已由 AI 接管财政
     */
    public record GovernmentResult(double effectiveAmount, boolean accepted, boolean consequenceTriggered,
                                   String aiAction, String accountability, boolean autopilotEngaged) {
    }
}
