package com.example.financialmarket.engine.banking;

import java.util.UUID;

public class ReverseRepoOperation {
    public enum RepoType { CENTRAL_BANK, GOVERNMENT_BOND }
    private final UUID operationId;
    private final RepoType type;
    private final double amount;          // 投放/回收金额
    private final double interestRate;    // 约定利率
    private final int maturityDays;       // 期限（天）
    private long startDay;
    private boolean matured = false;

    public ReverseRepoOperation(RepoType type, double amount, double interestRate, int maturityDays) {
        this.operationId = UUID.randomUUID();
        this.type = type;
        this.amount = amount;
        this.interestRate = interestRate;
        this.maturityDays = maturityDays;
    }

    public UUID getOperationId() { return operationId; }
    public RepoType getType() { return type; }
    public double getAmount() { return amount; }
    public double getInterestRate() { return interestRate; }
    public int getMaturityDays() { return maturityDays; }
    public long getStartDay() { return startDay; }
    public void setStartDay(long day) { this.startDay = day; }
    public boolean isMatured() { return matured; }
    public void setMatured(boolean m) { this.matured = m; }

    /** 到期应返还的本息 */
    public double getTotalDue(long currentDay) {
        long days = currentDay - startDay;
        return amount * (1 + interestRate * days / 365.0);
    }
}