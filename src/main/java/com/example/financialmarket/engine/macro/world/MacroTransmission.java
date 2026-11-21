package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.CommodityMarket;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.ai.AICompanyData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

import java.util.Map;

/**
 * M1 transmission: world-macro resource flows -> commodity supply/demand and a bounded risk
 * sentiment nudge. It never writes a price directly; the existing CommodityEntry pricing formula
 * and the order book remain the only price setters.
 */
public final class MacroTransmission {

    private MacroTransmission() {
    }

    public static void transmit(WorldMacroEngine macro, MarketEngine engine) {
        if (macro == null || engine == null) {
            return;
        }
        CommodityMarket market = engine.getCommodityMarket();
        if (market == null) {
            return;
        }
        Map<String, Double> supply = macro.resourceSupply();
        Map<String, Double> demand = macro.resourceDemand();
        int applied = 0;
        for (Map.Entry<String, Double> e : supply.entrySet()) {
            if (applyItem(market, e.getKey(), e.getValue() * MacroCalibration.supplyScale, true)) {
                applied++;
            } else if (applyFluid(market, e.getKey(),
                    e.getValue() * MacroCalibration.supplyScale, true)) {
                applied++;
            }
        }
        for (Map.Entry<String, Double> e : demand.entrySet()) {
            if (applyItem(market, e.getKey(), e.getValue() * MacroCalibration.demandScale, false)) {
                applied++;
            } else if (applyFluid(market, e.getKey(),
                    e.getValue() * MacroCalibration.demandScale, false)) {
                applied++;
            }
        }
        WorldMacroSnapshot snap = macro.snapshot();
        double nudge = -Math.min(MacroCalibration.maxSentimentNudge,
                Math.max(0.0, snap.eventRisk()) * 0.0005);
        if (nudge != 0 && engine.getSentiment() != null) {
            engine.getSentiment().adjustGlobalSentiment(nudge);
        }
        for (com.example.financialmarket.company.CompanyData c
                : engine.getCompanyManager().getAllCompanies()) {
            String key = c.getStockId() != null && !c.getStockId().isBlank()
                    ? "company:" + c.getStockId() : "company:" + c.getName();
            WorldMacroSnapshot.CompanyState cs = snap.companies().get(key);
            if (cs != null) {
                c.setMacroProduction(cs.production());
                c.setMacroCapex(cs.capex());
                c.setMacroRnd(cs.rnd());
                c.setMacroRisk(cs.risk());
                c.setMacroTechTier(cs.techTier());
            }
        }
        for (AICompanyData ai : engine.getAiCompanyManager().getAll()) {
            var sectorState = snap.sectors().get(sectorOf(ai.getIndustry()));
            if (sectorState != null) {
                ai.applyWorldMacro(sectorState.outputIndex(), sectorState.margin(),
                        sectorState.risk(), sectorState.rnd(), 0.0);
            }
        }
        macro.clearResourceFlows();
    }

    private static String sectorOf(String industry) {
        if (industry == null || industry.isBlank()) {
            return "other";
        }
        String s = industry.toLowerCase();
        if (s.contains("银行") || s.contains("金融") || s.contains("保险")
                || s.contains("finance") || s.contains("bank")) return "finance";
        if (s.contains("能源") || s.contains("电力") || s.contains("煤") || s.contains("石油")
                || s.contains("energy") || s.contains("power")) return "energy";
        if (s.contains("食品") || s.contains("农业") || s.contains("food")
                || s.contains("farm")) return "food";
        if (s.contains("材料") || s.contains("钢铁") || s.contains("化工") || s.contains("矿")
                || s.contains("material") || s.contains("steel") || s.contains("chemical")) {
            return "materials";
        }
        if (s.contains("汽车") || s.contains("机械") || s.contains("制造") || s.contains("工业")
                || s.contains("tool") || s.contains("machine") || s.contains("industrial")) {
            return "tools";
        }
        if (s.contains("魔法") || s.contains("arcane") || s.contains("magic")) return "arcane";
        return "services";
    }

    private static boolean applyItem(CommodityMarket market, String id, double qty,
                                     boolean production) {
        if (id == null || id.isBlank() || id.startsWith("#") || id.startsWith("c:")) {
            return false;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == null || item == Items.AIR) {
            return false;
        }
        return production ? market.applyProduction(item, qty) : market.applyConsumption(item, qty);
    }

    private static boolean applyFluid(CommodityMarket market, String id, double qty,
                                      boolean production) {
        if (id == null || id.isBlank() || id.startsWith("#") || id.startsWith("c:")) {
            return false;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            return false;
        }
        Fluid fluid = BuiltInRegistries.FLUID.get(rl);
        if (fluid == null) {
            return false;
        }
        return production ? market.applyProduction(fluid, qty) : market.applyConsumption(fluid, qty);
    }
}
