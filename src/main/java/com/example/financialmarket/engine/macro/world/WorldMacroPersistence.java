package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.MacroFlowAggregator.CompanyEconomy;
import com.example.financialmarket.engine.macro.world.MacroFlowAggregator.SectorEconomy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * World-macro NBT persistence. The recipe/resource graph is rebuilt from the modpack at startup;
 * only mutable economic state is stored here. Company macro fields already live in CompanyData.
 */
public final class WorldMacroPersistence {

    private WorldMacroPersistence() {
    }

    public static void write(WorldMacroEngine engine, CompoundTag tag) {
        if (engine == null || tag == null) {
            return;
        }
        tag.putLong("tick", engine.tick());
        tag.putLong("day", engine.day());
        tag.putLong("eventsProcessed", engine.eventsProcessed());
        MacroFlowAggregator agg = engine.aggregator();
        tag.putDouble("production", agg.productionRaw());
        tag.putDouble("capex", agg.capexRaw());
        tag.putDouble("rnd", agg.rndRaw());
        tag.putDouble("risk", agg.riskRaw());
        tag.putDouble("novelty", agg.noveltyRaw());
        tag.putDouble("ppi", agg.ppiRaw());
        tag.putDouble("cpi", agg.cpiRaw());

        ListTag companies = new ListTag();
        for (CompanyEconomy c : agg.companies().values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", c.id);
            t.putString("sector", c.sector);
            t.putDouble("capital", c.capital);
            t.putDouble("inventory", c.inventory);
            t.putDouble("production", c.production);
            t.putDouble("capex", c.capex);
            t.putDouble("rnd", c.rnd);
            t.putDouble("techTier", c.techTier);
            t.putDouble("labor", c.labor);
            t.putDouble("energy", c.energy);
            t.putDouble("risk", c.risk);
            t.putDouble("cashFlow", c.cashFlow);
            t.putDouble("lastSurprise", c.lastSurprise);
            companies.add(t);
        }
        tag.put("companies", companies);

        ListTag sectors = new ListTag();
        for (SectorEconomy s : agg.sectors().values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", s.id);
            t.putDouble("output", s.output);
            t.putDouble("capex", s.capex);
            t.putDouble("rnd", s.rnd);
            t.putDouble("risk", s.risk);
            t.putDouble("inputCost", s.inputCost);
            t.putDouble("outputValue", s.outputValue);
            t.putDouble("lastSurprise", s.lastSurprise);
            t.putLong("events", s.events);
            sectors.add(t);
        }
        tag.put("sectors", sectors);

        CompoundTag expected = new CompoundTag();
        for (Map.Entry<String, Double> e : agg.expectedMap().entrySet()) {
            expected.putDouble(e.getKey(), e.getValue());
        }
        tag.put("expected", expected);

        ListTag ledger = new ListTag();
        for (EconomicLedger.Entry e : engine.ledger().all()) {
            CompoundTag t = new CompoundTag();
            t.putLong("tick", e.tick());
            t.putString("type", e.type());
            t.putString("company", e.companyId());
            t.putString("sector", e.sector());
            t.putDouble("va", e.valueAdded());
            t.putDouble("novelty", e.novelty());
            t.putDouble("impact", e.impact());
            t.putString("mod", e.sourceMod());
            t.putString("provenance", e.provenance());
            t.putDouble("confidence", e.confidence());
            ledger.add(t);
        }
        tag.put("ledger", ledger);
    }

    public static void read(WorldMacroEngine engine, CompoundTag tag) {
        if (engine == null || tag == null || !tag.contains("tick")) {
            return;
        }
        engine.clear();
        engine.restoreCounters(tag.getLong("tick"), tag.getLong("day"),
                tag.getLong("eventsProcessed"));
        MacroFlowAggregator agg = engine.aggregator();
        agg.restoreAggregates(tag.getDouble("production"), tag.getDouble("capex"),
                tag.getDouble("rnd"), tag.getDouble("risk"), tag.getDouble("novelty"),
                tag.getDouble("ppi"), tag.getDouble("cpi"));
        ListTag companies = tag.getList("companies", Tag.TAG_COMPOUND);
        for (int i = 0; i < companies.size(); i++) {
            CompoundTag t = companies.getCompound(i);
            String id = t.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            CompanyEconomy c = new CompanyEconomy(id);
            c.sector = t.getString("sector");
            c.capital = t.getDouble("capital");
            c.inventory = t.getDouble("inventory");
            c.production = t.getDouble("production");
            c.capex = t.getDouble("capex");
            c.rnd = t.getDouble("rnd");
            c.techTier = t.getDouble("techTier");
            c.labor = t.getDouble("labor");
            c.energy = t.getDouble("energy");
            c.risk = t.getDouble("risk");
            c.cashFlow = t.getDouble("cashFlow");
            c.lastSurprise = t.getDouble("lastSurprise");
            agg.restoreCompany(c);
        }
        ListTag sectors = tag.getList("sectors", Tag.TAG_COMPOUND);
        for (int i = 0; i < sectors.size(); i++) {
            CompoundTag t = sectors.getCompound(i);
            String id = t.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            SectorEconomy s = new SectorEconomy(id);
            s.output = t.getDouble("output");
            s.capex = t.getDouble("capex");
            s.rnd = t.getDouble("rnd");
            s.risk = t.getDouble("risk");
            s.inputCost = t.getDouble("inputCost");
            s.outputValue = t.getDouble("outputValue");
            s.lastSurprise = t.getDouble("lastSurprise");
            s.events = t.getLong("events");
            agg.restoreSector(s);
        }
        CompoundTag expected = tag.getCompound("expected");
        Map<String, Double> values = new LinkedHashMap<>();
        for (String key : expected.getAllKeys()) {
            values.put(key, expected.getDouble(key));
        }
        agg.restoreExpected(values);
        ListTag ledger = tag.getList("ledger", Tag.TAG_COMPOUND);
        java.util.List<EconomicLedger.Entry> entries = new java.util.ArrayList<>(ledger.size());
        for (int i = 0; i < ledger.size(); i++) {
            CompoundTag t = ledger.getCompound(i);
            entries.add(new EconomicLedger.Entry(t.getLong("tick"), t.getString("type"),
                    t.getString("company"), t.getString("sector"), t.getDouble("va"),
                    t.getDouble("novelty"), t.getDouble("impact"), t.getString("mod"),
                    t.getString("provenance"), t.getDouble("confidence")));
        }
        engine.ledger().restore(entries);
    }
}
