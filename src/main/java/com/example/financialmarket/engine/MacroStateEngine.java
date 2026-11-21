package com.example.financialmarket.engine;

import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;

public class MacroStateEngine {
    private double riskAppetite = 0;
    private double creditImpulse = 0;
    private double inflationPressure = 0;
    private long seed;
    // P0-7/A0-3：可播种 PRNG，宏观热路径随机同种子可复现
    private final SeedableRandom rng;

    public MacroStateEngine(long seed) {
        this.seed = seed;
        this.rng = new SeedableRandom(SeedableRandom.mix64(seed));
    }

    public void step(MarketState state, MarketDataCollector data) {
        // 简单模拟更新（后续可扩展）
        riskAppetite = Math.tanh(0.95 * riskAppetite + 0.01 * (rng.nextDouble() - 0.5));
        creditImpulse = Math.tanh(0.8 * creditImpulse + 0.02 * (data.getTotalLoans() / (data.getMoneySupply() + 1) - 0.5));
        inflationPressure = 0.9 * inflationPressure + 0.1 * (data.getMoneySupply() / (data.getTradeVolume() + 1) - 1);

        state.setRiskAppetite(riskAppetite);
        state.setCreditImpulse(creditImpulse);
        state.setInflationPressure(inflationPressure);
    }

    public double getRiskAppetite() { return riskAppetite; }

    public CompoundTag save() { return new CompoundTag(); }
    public void load(CompoundTag tag) {}
}