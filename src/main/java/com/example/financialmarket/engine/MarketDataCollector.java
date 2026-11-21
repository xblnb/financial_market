package com.example.financialmarket.engine;

import net.minecraft.server.level.ServerLevel;

public class MarketDataCollector {
    private double totalLoans = 10000;
    private double moneySupply = 50000;
    private double tradeVolume = 2000;

    public void collect(ServerLevel level, MarketEngine engine) {
        // placeholder
    }

    public double getTotalLoans() { return totalLoans; }
    public double getMoneySupply() { return moneySupply; }
    public double getTradeVolume() { return tradeVolume; }
}