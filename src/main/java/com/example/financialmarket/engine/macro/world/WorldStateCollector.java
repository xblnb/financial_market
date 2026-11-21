package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.WorldStateSnapshot;
import net.minecraft.server.level.ServerLevel;

/**
 * M4 slow world-state sampler. Runs once per game day; deliberately uses server-wide aggregates and
 * avoids per-block/per-entity scans. Mod-specific collectors can enrich this later.
 */
public final class WorldStateCollector {

    private long lastDay = Long.MIN_VALUE;

    public void sample(WorldMacroEngine macro, MarketEngine engine, ServerLevel level, long day) {
        if (macro == null || level == null || day == lastDay) {
            return;
        }
        lastDay = day;
        int players = level.getServer() == null ? 0 : level.getServer().getPlayerCount();
        double weather = level.isRaining() ? 1.0 : 0.0;
        double risk = macro.snapshot().eventRisk();
        double techTier = 0;
        int n = 0;
        for (var p : macro.registry().all()) {
            techTier += p.tier();
            n++;
        }
        double techMean = n == 0 ? 0 : techTier / n;
        macro.setWorldState(new WorldStateSnapshot(macro.tick(), day, players, 0,
                players > 0 ? 1.0 : 0.0, players, 0.0, weather, 0.0,
                engine == null ? 0 : engine.getCompanyManager().getAllCompanies().size(),
                1.0, 1.0, 1.0, techMean, risk));
    }
}
