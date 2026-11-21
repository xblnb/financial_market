package com.example.financialmarket.engine.products;

public class InsuranceProduct extends FinancialProduct {
    private final double coverageAmount; // 保额
    private final double premiumRate;    // 保费率

    public InsuranceProduct(String name, double minAmount, int lockDays, double coverageAmount, double premiumRate) {
        super(name, ProductType.INSURANCE, minAmount, lockDays, 0, 0.05);
        this.coverageAmount = coverageAmount;
        this.premiumRate = premiumRate;
    }

    public double getCoverageAmount() { return coverageAmount; }
    public double getPremium(double principal) { return principal * premiumRate; }

    @Override
    public boolean canRedeem(long purchaseDay, long currentDay) { return false; } // 保险不能赎回，只能到期或出险
}