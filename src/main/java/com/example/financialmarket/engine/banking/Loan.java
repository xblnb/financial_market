package com.example.financialmarket.engine.banking;

import java.util.UUID;

public class Loan {
    public enum LoanStatus { ACTIVE, REPAID, DEFAULTED }

    private final UUID loanId;
    private final UUID borrowerId;
    private final String borrowerType;
    private final String lenderBankId;
    private double principal;
    private double remaining;
    private final double annualRate;
    private final int termDays;
    private long startDay;
    private LoanStatus status;
    private double collateralValue;
    public String getBankId() { return lenderBankId; }

    public Loan(UUID borrowerId, String borrowerType, String lenderBankId,
                double principal, double annualRate, int termDays, double collateralValue) {
        this.loanId = UUID.randomUUID();
        this.borrowerId = borrowerId;
        this.borrowerType = borrowerType;
        this.lenderBankId = lenderBankId;
        this.principal = principal;
        this.remaining = principal;
        this.annualRate = annualRate;
        this.termDays = termDays;
        this.startDay = 0;
        this.status = LoanStatus.ACTIVE;
        this.collateralValue = collateralValue;
    }

    public UUID getLoanId() { return loanId; }
    public UUID getBorrowerId() { return borrowerId; }
    public String getBorrowerType() { return borrowerType; }
    public String getLenderBankId() { return lenderBankId; }
    public double getPrincipal() { return principal; }
    public double getRemaining() { return remaining; }
    public double getAnnualRate() { return annualRate; }
    public int getTermDays() { return termDays; }
    public long getStartDay() { return startDay; }
    public LoanStatus getStatus() { return status; }
    public double getCollateralValue() { return collateralValue; }

    public void setStartDay(long day) { this.startDay = day; }

    public double getTotalDue(long currentDay) {
        long days = currentDay - startDay;
        if (days <= 0) return remaining;
        double dailyRate = annualRate / 365.0;
        return remaining * Math.pow(1 + dailyRate, days);
    }

    public void repay(double amount) {
        this.remaining = Math.max(0, this.remaining - amount);
        if (this.remaining <= 0) this.status = LoanStatus.REPAID;
    }

    public void markDefaulted() { this.status = LoanStatus.DEFAULTED; }
}