package com.example.financialmarket.engine.products;

import java.util.UUID;

public abstract class FinancialProduct {
    public enum ProductType { SAVINGS, FUND, BOND, INSURANCE, TRUST, STOCK }
    protected final UUID productId = UUID.randomUUID();
    protected final String name;
    protected final ProductType type;
    protected final double minAmount;
    protected final int lockDays;
    protected final double annualYield;
    protected final double riskLevel;

    public FinancialProduct(String name, ProductType type, double minAmount, int lockDays, double annualYield, double riskLevel) {
        this.name = name;
        this.type = type;
        this.minAmount = minAmount;
        this.lockDays = lockDays;
        this.annualYield = annualYield;
        this.riskLevel = riskLevel;
    }

    public UUID getProductId() { return productId; }
    public String getName() { return name; }
    public ProductType getType() { return type; }
    public double getMinAmount() { return minAmount; }
    public int getLockDays() { return lockDays; }
    public double getAnnualYield() { return annualYield; }
    public double getRiskLevel() { return riskLevel; }
    public double calculateYield(double principal, int daysHeld) {
        return principal * annualYield * (daysHeld / 365.0);
    }
    public abstract boolean canRedeem(long purchaseDay, long currentDay);
}