package com.example.financialmarket.engine.macro;

import com.example.financialmarket.engine.banking.CreditSystem;
import com.example.financialmarket.engine.CommodityMarket;
import com.example.financialmarket.engine.CommodityEntry;
import com.example.financialmarket.engine.random.SeedableRandom;

public class EconomicCycleEngine {
    private double economicHealth = 0.2;
    private static final long TICKS_PER_YEAR = 24000L * 30;
    private static final long KITCHIN_LENGTH = TICKS_PER_YEAR * 3;
    private static final long JUGLAR_LENGTH = TICKS_PER_YEAR * 9;
    private static final long KUZNETS_LENGTH = TICKS_PER_YEAR * 20;
    // P0-7/A0-3：可播种 PRNG，经济周期随机噪声同种子可复现
    private final SeedableRandom rng;

    public EconomicCycleEngine() {
        this(0L);
    }

    public EconomicCycleEngine(long seed) {
        this.rng = new SeedableRandom(SeedableRandom.mix64(seed));
    }

    public void tick(long gameTime, CreditSystem credit, CommodityMarket commodity) {
        double kitchin = Math.sin(2 * Math.PI * gameTime / KITCHIN_LENGTH);
        double juglar  = Math.sin(2 * Math.PI * gameTime / JUGLAR_LENGTH);
        double kuznets = Math.sin(2 * Math.PI * gameTime / KUZNETS_LENGTH);
        double creditHealth = -credit.getNplRatio() * 2.0 + credit.getCreditGrowth() * 0.5;
        double commodityVigor = 0;
        for (CommodityEntry e : commodity.getAllCommodities()) {
            commodityVigor += (e.getPrice() / e.getBasePrice() - 1.0);
        }
        double rawHealth = kitchin * 0.2 + juglar * 0.3 + kuznets * 0.3
                + Math.tanh(creditHealth) * 0.1 + Math.tanh(commodityVigor) * 0.1;
        rawHealth += (rng.nextDouble() - 0.5) * 0.05;
        economicHealth = economicHealth * 0.95 + rawHealth * 0.05;
        economicHealth = Math.max(-1.0, Math.min(1.0, economicHealth));
    }

    public double getEconomicHealth() { return economicHealth; }
    public String getPhaseName() {
        if (economicHealth > 0.4) return "繁荣";
        if (economicHealth > 0.1) return "复苏";
        if (economicHealth > -0.3) return "衰退";
        return "萧条";
    }
}