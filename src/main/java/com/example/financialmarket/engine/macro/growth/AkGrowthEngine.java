package com.example.financialmarket.engine.macro.growth;

import com.example.financialmarket.company.CompanyData;
import com.example.financialmarket.company.CompanyManager;
import com.example.financialmarket.engine.macro.MacroEnvironment;
import com.example.financialmarket.engine.macro.world.WorldMacroSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the pure AK model to player companies.
 *
 * <p>Capital stock starts from replacement-cost productive assets, output is observed operating
 * output (fallback to a macro TFP), investment comes from operating profit + retained earnings, and
 * the resulting growth/return/fair value is written back to {@link CompanyData}.</p>
 */
public final class AkGrowthEngine {

    private AkGrowthModel.Aggregate lastAggregate =
            new AkGrowthModel.Aggregate(0, 0, 0, 0, 0, 0, 0, 0);
    private CobbDouglasModel.Result lastCobbDouglas =
            new CobbDouglasModel.Result(0, 0, 0, 0, 0.35, 0.65, 0, 0, 0);
    private long lastDay = Long.MIN_VALUE;

    public AkGrowthModel.Aggregate step(CompanyManager manager, WorldMacroSnapshot world,
                                        MacroEnvironment env, long day, int laborForce) {
        if (manager == null) return lastAggregate;
        if (day == lastDay) return lastAggregate;
        lastDay = day;

        double macroTfp = world == null ? 0.05 : 0.05 * Math.max(0.2, world.productionIndex());
        macroTfp = Math.max(0.01, Math.min(0.50, macroTfp));
        int workers = Math.max(1, laborForce);
        double totalCapital = 0.0;
        for (CompanyData c : manager.getAllCompanies()) {
            double k = c.getAkCapital();
            if (k <= 1.0) k = Math.max(1.0,
                    c.getReplacementCost() * 0.82 + c.getInventoryValue() * 0.5);
            totalCapital += k;
        }
        totalCapital = Math.max(1.0, totalCapital);
        double totalObservedOutput = 0.0;
        List<AkGrowthModel.State> states = new ArrayList<>();
        for (CompanyData c : manager.getAllCompanies()) {
            double k = c.getAkCapital();
            if (k <= 1.0) {
                k = Math.max(1.0, c.getReplacementCost() * 0.82 + c.getInventoryValue() * 0.5);
            }
            double tfp = c.getAkTfp();
            if (tfp <= 0.0) {
                tfp = AkGrowthModel.impliedTfp(c.getProductionOutputValue(), k, macroTfp);
            }
            double operating = Math.max(0.0, c.getOperatingProfit());
            double retained = Math.max(0.0, c.getRetainedEarnings());
            double desired = operating * 0.5 + retained * 0.10;
            double delta = Math.max(0.02, Math.min(0.12,
                    0.04 + 0.03 * Math.max(0.0, c.getAssetRisk())));
            double savings = 0.35;
            AkGrowthModel.Params p = new AkGrowthModel.Params(tfp, delta, savings, 0.10);
            AkGrowthModel.State prev = new AkGrowthModel.State(k, tfp * k, 0, 0, 0,
                    tfp - delta, delta);
            AkGrowthModel.State s = AkGrowthModel.step(prev, p, desired);
            double q = AkGrowthModel.tobinQ(s.growth(), s.returnRate(), s.depreciation());
            double fair = s.capital() * q;
            c.setAkCapital(s.capital());
            c.setAkOutput(s.output());
            c.setAkInvestment(s.investment());
            c.setAkGrowth(s.growth());
            c.setAkReturn(s.returnRate());
            c.setAkTfp(tfp);
            c.setAkFairValue(fair);
            states.add(s);

            double observed = c.getProductionOutputValue() > 0
                    ? c.getProductionOutputValue() : s.output();
            totalObservedOutput += observed;
            double laborShare = s.capital() / totalCapital;
            double labor = Math.max(0.1, workers * laborShare);
            var cd = CobbDouglasModel.evaluate(observed, s.capital(), labor, 0.35, delta, 0.05);
            c.setCdOutput(cd.output());
            c.setCdLabor(labor);
            c.setCdTfp(cd.tfp());
            c.setCdMpk(cd.mpk());
            c.setCdMpl(cd.mpl());
            c.setCdOptimalCapital(cd.optimalCapital());
            c.setCdCapitalGap(cd.capitalGap());
        }
        this.lastAggregate = AkGrowthModel.aggregate(states);
        this.lastCobbDouglas = CobbDouglasModel.evaluate(totalObservedOutput, totalCapital,
                workers, 0.35, 0.05, 0.05);
        return lastAggregate;
    }

    public AkGrowthModel.Aggregate aggregate() {
        return lastAggregate;
    }

    public CobbDouglasModel.Result cobbDouglas() {
        return lastCobbDouglas;
    }
}
