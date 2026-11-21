package com.example.financialmarket.engine.products;

public class SavingsProduct extends FinancialProduct {
    private final boolean isDemand; // true=活期, false=定期

    public SavingsProduct(String name, double minAmount, int lockDays, double annualYield, boolean isDemand) {
        super(name, ProductType.SAVINGS, minAmount, lockDays, annualYield, isDemand ? 0.01 : 0.1);
        this.isDemand = isDemand;
    }

    public boolean isDemand() { return isDemand; }

    @Override
    public boolean canRedeem(long purchaseDay, long currentDay) {
        if (isDemand) return true;
        return (currentDay - purchaseDay) >= lockDays;
    }
}