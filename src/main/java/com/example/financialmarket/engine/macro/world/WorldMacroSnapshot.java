package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.MacroFlowAggregator.SectorState;
import com.example.financialmarket.engine.macro.world.MacroFlowAggregator.WorldMacroState;
import com.example.financialmarket.engine.macro.world.MacroFlowAggregator.CompanyEconomy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only view consumed by the market/UI. This is intentionally separate from
 * {@code MacroSnapshot}: policy (top-down) and player economy (bottom-up) must not overwrite each
 * other in the same record.
 */
public record WorldMacroSnapshot(
        long tick,
        long day,
        double productionIndex,
        double ppi,
        double cpi,
        double capacityUtil,
        double eventRisk,
        double novelty,
        Map<String, SectorState> sectors,
        Map<String, CompanyState> companies,
        long eventsProcessed,
        long eventsDropped,
        int unmappedResources,
        int resourceProfiles) {

    public record CompanyState(
            String id,
            String sector,
            double production,
            double capital,
            double capex,
            double rnd,
            double risk,
            double techTier,
            double cashFlow,
            double lastSurprise) {
    }

    public static WorldMacroSnapshot from(WorldMacroState state,
                                          Map<String, CompanyEconomy> companies,
                                          long eventsProcessed,
                                          long eventsDropped,
                                          int unmappedResources,
                                          int resourceProfiles) {
        Map<String, CompanyState> cs = new LinkedHashMap<>();
        for (CompanyEconomy c : companies.values()) {
            cs.put(c.id, new CompanyState(c.id, c.sector, c.production, c.capital, c.capex,
                    c.rnd, c.risk, c.techTier, c.cashFlow, c.lastSurprise));
        }
        return new WorldMacroSnapshot(state.tick(), state.day(), state.productionIndex(),
                state.ppi(), state.cpi(), state.capacityUtil(), state.eventRisk(),
                state.novelty(), state.sectors(), Map.copyOf(cs), eventsProcessed,
                eventsDropped, unmappedResources, resourceProfiles);
    }

    public static WorldMacroSnapshot empty() {
        return new WorldMacroSnapshot(0, 0, 1, 1, 1, 0.5, 0, 0, Map.of(), Map.of(),
                0, 0, 0, 0);
    }
}
