package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.WorldMacroModel.EconomicEvent;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.EconomicType;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceFlow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregates economic events into company/sector/world flows, applies expectation surprises and
 * per-day caps, and exposes a read-only snapshot.
 *
 * <p>The first event of a series establishes the EWMA baseline; only deviations from the baseline
 * (plus novelty) move the macro state. This prevents repeated grinding from printing GDP.</p>
 */
public final class MacroFlowAggregator {

    public static final double EWMA_LAMBDA = 0.90;
    public static final double SURPRISE_CAP = 4.0;
    public static final double DAY_IMPACT_CAP = 5.0;
    public static final double IMPACT_CAP = 1.0;

    public static final class CompanyEconomy {
        public final String id;
        public String sector = "other";
        public double capital;
        public double inventory;
        public double production;
        public double capex;
        public double rnd;
        public double techTier;
        public double labor;
        public double energy;
        public double risk;
        public double cashFlow;
        public double lastSurprise;

        CompanyEconomy(String id) {
            this.id = id;
        }

        void apply(EconomicEvent e, double surprise) {
            sector = e.sector();
            lastSurprise = surprise;
            switch (e.type()) {
                case PRODUCTION, EXTRACTION, SPELL -> {
                    production += e.valueAdded();
                    cashFlow += e.valueAdded();
                    inventory += outputQuantity(e);
                }
                case INVESTMENT -> {
                    capex += e.valueAdded();
                    capital += e.valueAdded();
                    cashFlow -= e.valueAdded();
                }
                case RND -> {
                    rnd += e.valueAdded();
                    cashFlow -= e.valueAdded();
                    techTier += Math.log1p(Math.max(0, e.valueAdded())) * 0.01;
                }
                case CONSUMPTION -> {
                    cashFlow -= e.inputValue();
                    energy += energyQuantity(e);
                }
                case RISK -> risk += Math.max(1.0, e.valueAdded());
                default -> {
                }
            }
        }
    }

    public static final class SectorEconomy {
        public final String id;
        public double output;
        public double capex;
        public double rnd;
        public double risk;
        public double inputCost;
        public double outputValue;
        public double lastSurprise;
        public long events;

        SectorEconomy(String id) {
            this.id = id;
        }

        void apply(EconomicEvent e, double surprise) {
            lastSurprise = surprise;
            events++;
            switch (e.type()) {
                case PRODUCTION, EXTRACTION, SPELL, RITUAL -> output += e.valueAdded();
                case INVESTMENT -> {
                    capex += e.valueAdded();
                    outputValue += e.valueAdded();
                }
                case RND -> rnd += e.valueAdded();
                case RISK -> risk += Math.max(1.0, e.valueAdded());
                case CONSUMPTION -> inputCost += e.inputValue();
                default -> {
                }
            }
        }
    }

    public record SectorState(
            String id,
            double outputIndex,
            double output,
            double capex,
            double rnd,
            double risk,
            double margin,
            long events) {
    }

    public record WorldMacroState(
            long tick,
            long day,
            double productionIndex,
            double ppi,
            double cpi,
            double capacityUtil,
            double eventRisk,
            double novelty,
            Map<String, SectorState> sectors) {
        public static WorldMacroState empty(long tick, long day) {
            return new WorldMacroState(tick, day, 1.0, 1.0, 1.0, 0.5, 0.0, 0.0, Map.of());
        }
    }

    private final Map<String, CompanyEconomy> companies = new LinkedHashMap<>();
    private final Map<String, SectorEconomy> sectors = new LinkedHashMap<>();
    private final Map<String, Double> expected = new LinkedHashMap<>();
    private final Map<String, Double> dayImpact = new LinkedHashMap<>();
    private final Map<String, Double> resourceSupply = new LinkedHashMap<>();
    private final Map<String, Double> resourceDemand = new LinkedHashMap<>();
    private long tick;
    private long day;
    private double production;
    private double capex;
    private double rnd;
    private double risk;
    private double novelty;
    private double ppi = 1.0;
    private double cpi = 1.0;

    public void setTick(long tick, long day) {
        this.tick = tick;
        if (day != this.day) {
            this.day = day;
            dayImpact.clear();
            production = 0;
            capex = 0;
            rnd = 0;
            risk = 0;
            novelty = 0;
            ppi = 1.0;
            cpi = 1.0;
        }
    }

    public void apply(EconomicEvent e) {
        if (e == null || e.type() == EconomicType.TRADE || e.type() == EconomicType.LOGISTICS) {
            return; // trade/logistics affect liquidity, not the production account
        }
        String key = e.companyId() + "|" + e.type() + "|" + e.sector();
        double prev = expected.getOrDefault(key, 0.0);
        double scale = Math.max(10.0, Math.max(Math.abs(prev), Math.abs(e.valueAdded())) * 0.5);
        double surprise = clamp((e.valueAdded() - prev) / scale, -SURPRISE_CAP, SURPRISE_CAP);
        expected.put(key, EWMA_LAMBDA * prev + (1.0 - EWMA_LAMBDA) * e.valueAdded());

        String capKey = e.companyId() + "|" + e.sector();
        double used = dayImpact.getOrDefault(capKey, 0.0);
        double impact = clamp(MacroCalibration.impactCap * Math.tanh(surprise)
                        * e.novelty() * e.confidence(),
                -MacroCalibration.dayImpactCap, MacroCalibration.dayImpactCap);
        if (Math.abs(used + impact) > MacroCalibration.dayImpactCap) {
            impact = Math.signum(impact)
                    * Math.max(0, MacroCalibration.dayImpactCap - Math.abs(used));
        }
        dayImpact.put(capKey, used + impact);

        companies.computeIfAbsent(e.companyId(), CompanyEconomy::new).apply(e, impact);
        sectors.computeIfAbsent(e.sector(), SectorEconomy::new).apply(e, impact);

        for (ResourceFlow f : e.outputs()) {
            resourceSupply.merge(f.key().id(), f.quantity(), Double::sum);
        }
        for (ResourceFlow f : e.inputs()) {
            resourceDemand.merge(f.key().id(), f.quantity(), Double::sum);
        }

        switch (e.type()) {
            case PRODUCTION, EXTRACTION, SPELL, RITUAL -> production += e.valueAdded();
            case INVESTMENT -> capex += e.valueAdded();
            case RND -> rnd += e.valueAdded();
            case RISK -> risk += Math.max(1.0, e.valueAdded());
            case CONSUMPTION -> ppi += e.inputValue() * 1e-6;
            default -> {
            }
        }
        novelty = Math.max(novelty, e.novelty());
        double total = Math.max(1.0, production + capex + rnd);
        cpi += (e.sector().equals("food") ? e.inputValue() * 1e-5 : 0.0);
    }

    public WorldMacroState snapshot() {
        Map<String, SectorState> sectorStates = new LinkedHashMap<>();
        for (SectorEconomy s : sectors.values()) {
            double outputIndex = 1.0 + s.output / Math.max(1.0, 10_000.0);
            double margin = s.outputValue > 0
                    ? (s.outputValue - s.inputCost) / s.outputValue : 0.0;
            sectorStates.put(s.id, new SectorState(s.id, outputIndex, s.output, s.capex, s.rnd,
                    s.risk, margin, s.events));
        }
        double productionIndex = 1.0 + production / Math.max(1.0, 10_000.0);
        double capacityUtil = clamp(0.5 + production / Math.max(1.0, 20_000.0), 0.0, 1.5);
        return new WorldMacroState(tick, day, productionIndex, ppi, cpi, capacityUtil, risk,
                novelty, Map.copyOf(sectorStates));
    }

    public Map<String, CompanyEconomy> companies() {
        return Map.copyOf(companies);
    }

    public Map<String, SectorEconomy> sectors() {
        return Map.copyOf(sectors);
    }

    public double expected(String companyId, EconomicType type, String sector) {
        return expected.getOrDefault(companyId + "|" + type + "|" + sector, 0.0);
    }

    public Map<String, Double> resourceSupply() {
        return Map.copyOf(resourceSupply);
    }

    public Map<String, Double> resourceDemand() {
        return Map.copyOf(resourceDemand);
    }

    public void clearResourceFlows() {
        resourceSupply.clear();
        resourceDemand.clear();
    }

    public Map<String, Double> expectedMap() {
        return Map.copyOf(expected);
    }

    public void restoreExpected(Map<String, Double> values) {
        expected.clear();
        if (values != null) {
            expected.putAll(values);
        }
    }

    public void restoreCompany(CompanyEconomy company) {
        if (company != null) {
            companies.put(company.id, company);
        }
    }

    public void restoreSector(SectorEconomy sector) {
        if (sector != null) {
            sectors.put(sector.id, sector);
        }
    }

    public void restoreAggregates(double production, double capex, double rnd, double risk,
                                  double novelty, double ppi, double cpi) {
        this.production = production;
        this.capex = capex;
        this.rnd = rnd;
        this.risk = risk;
        this.novelty = novelty;
        this.ppi = ppi;
        this.cpi = cpi;
    }

    public double productionRaw() {
        return production;
    }

    public double capexRaw() {
        return capex;
    }

    public double rndRaw() {
        return rnd;
    }

    public double riskRaw() {
        return risk;
    }

    public double noveltyRaw() {
        return novelty;
    }

    public double ppiRaw() {
        return ppi;
    }

    public double cpiRaw() {
        return cpi;
    }

    public void reset() {
        companies.clear();
        sectors.clear();
        expected.clear();
        dayImpact.clear();
        resourceSupply.clear();
        resourceDemand.clear();
        production = capex = rnd = risk = novelty = 0;
        ppi = cpi = 1.0;
    }

    private static double outputQuantity(EconomicEvent e) {
        double q = 0;
        for (ResourceFlow f : e.outputs()) {
            q += f.quantity();
        }
        return q;
    }

    private static double energyQuantity(EconomicEvent e) {
        double q = 0;
        for (ResourceFlow f : e.inputs()) {
            if (f.key().kind() == WorldMacroModel.ResourceKind.ENERGY
                    || f.key().kind() == WorldMacroModel.ResourceKind.MANA) {
                q += f.quantity();
            }
        }
        return q;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
