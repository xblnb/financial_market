package com.example.financialmarket.engine.products;

import com.example.financialmarket.engine.MarketState;

public class FundProduct extends FinancialProduct {
    private final String underlyingAssetId;
    private MarketState marketState;

    public FundProduct(String name, double minAmount, int lockDays, String underlyingAssetId, MarketState state) {
        super(name, ProductType.FUND, minAmount, lockDays, 0.05, 0.3);
        this.underlyingAssetId = underlyingAssetId;
        this.marketState = state;
    }

    public MarketState getMarketState() { return marketState; }
    public void setMarketState(MarketState state) { this.marketState = state; }

    @Override
    public double calculateYield(double principal, int daysHeld) {
        if (marketState == null) return 0;
        var snap = marketState.getAsset(underlyingAssetId);
        if (snap == null) return 0;
        double priceChange = snap.getLastPrice() / 100.0 - 1;
        return principal * (priceChange * (daysHeld / 365.0));
    }

    @Override
    public boolean canRedeem(long purchaseDay, long currentDay) {
        return (currentDay - purchaseDay) >= lockDays;
    }
}