package com.example.financialmarket.engine.banking;

import java.util.UUID;

public class GovernmentBond {
    private final UUID bondId;
    private final double faceValue;      // 面值
    private final double couponRate;     // 票面利率
    private final int maturityDays;      // 期限（天）
    private long issueDay;
    private UUID holderUuid;             // 当前持有者（银行或玩家）
    private boolean matured;

    public GovernmentBond(double faceValue, double couponRate, int maturityDays) {
        this.bondId = UUID.randomUUID();
        this.faceValue = faceValue;
        this.couponRate = couponRate;
        this.maturityDays = maturityDays;
        this.holderUuid = null;
        this.matured = false;
    }

    public UUID getBondId() { return bondId; }
    public double getFaceValue() { return faceValue; }
    public double getCouponRate() { return couponRate; }
    public int getMaturityDays() { return maturityDays; }
    public long getIssueDay() { return issueDay; }
    public void setIssueDay(long day) { this.issueDay = day; }
    public UUID getHolderUuid() { return holderUuid; }
    public void setHolderUuid(UUID uuid) { this.holderUuid = uuid; }
    public boolean isMatured() { return matured; }
    public void setMatured(boolean m) { this.matured = m; }

    public double calculateCurrentValue(long currentDay, double marketRate) {
        long remainingDays = maturityDays - (currentDay - issueDay);
        if (remainingDays <= 0) return faceValue;
        double couponPayment = faceValue * couponRate * (remainingDays / 365.0);
        return (faceValue + couponPayment) / Math.pow(1 + marketRate, remainingDays / 365.0);
    }

    /**
     * 到期收益率（Q5：单一收益率口径；{@code govBondYield} 的来源，§8.2）。
     *
     * <p>口径（登记，纯函数、确定性）：按"价格=面值（平价发行）"的简化假设，到期收益率 = 票面利率；
     * 已到期（{@code day ≥ 发行日 + 期限}）返回票面利率（不再有期限溢价）；发行日未设置（{@code issueDay=0}
     * 且 {@code day<0}）时同样返回票面利率。**既有 {@code calculateCurrentValue} 零改动**。
     *
     * @param day 当前交易日
     * @return 该券的到期收益率（= {@code couponRate}；非负）
     */
    public double yieldToMaturity(long day) {
        return Math.max(0, couponRate);
    }

    /** 是否已到期（按交易日序判定，与 {@link #isMatured()} 的显式标记解耦）。 */
    public boolean isMaturedOn(long day) {
        return issueDay > 0 && day - issueDay >= maturityDays;
    }

    /** 剩余期限（交易日；已到期 ⇒ 0）。 */
    public long remainingDays(long day) {
        if (issueDay <= 0) return maturityDays;
        return Math.max(0, maturityDays - (day - issueDay));
    }
}