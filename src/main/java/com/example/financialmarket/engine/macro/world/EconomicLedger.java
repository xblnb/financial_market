package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.WorldMacroModel.EconomicEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, deterministic audit ledger. Every event keeps its provenance (mod/recipe/adapter), so
 * {@code /fm macro explain} can answer "why did this sector move".
 */
public final class EconomicLedger {

    public record Entry(
            long tick,
            String type,
            String companyId,
            String sector,
            double valueAdded,
            double novelty,
            double impact,
            String sourceMod,
            String provenance,
            double confidence) {
    }

    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long recorded;

    public EconomicLedger() {
        this(10_000);
    }

    public EconomicLedger(int capacity) {
        this.capacity = Math.max(128, capacity);
    }

    public void record(EconomicEvent e, double impact) {
        if (e == null) {
            return;
        }
        if (entries.size() >= capacity) {
            entries.pollFirst();
        }
        entries.addLast(new Entry(e.tick(), e.type().name(), e.companyId(), e.sector(),
                e.valueAdded(), e.novelty(), impact, e.sourceMod(), e.provenance(),
                e.confidence()));
        recorded++;
    }

    public List<Entry> latest(int limit) {
        int n = Math.min(Math.max(0, limit), entries.size());
        List<Entry> out = new ArrayList<>(n);
        int skip = entries.size() - n;
        int i = 0;
        for (Entry e : entries) {
            if (i++ >= skip) {
                out.add(e);
            }
        }
        return out;
    }

    public List<Entry> explain(String filter, int limit) {
        if (filter == null || filter.isBlank()) {
            return latest(limit);
        }
        String f = filter.toLowerCase();
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.sector().toLowerCase().contains(f) || e.companyId().toLowerCase().contains(f)
                    || e.type().toLowerCase().contains(f)
                    || e.provenance().toLowerCase().contains(f)) {
                out.add(e);
            }
        }
        if (out.size() > limit) {
            return out.subList(out.size() - limit, out.size());
        }
        return out;
    }

    public long recorded() {
        return recorded;
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }

    public void restore(List<Entry> list) {
        entries.clear();
        recorded = 0;
        if (list == null) {
            return;
        }
        for (Entry e : list) {
            if (entries.size() >= capacity) {
                entries.pollFirst();
            }
            entries.addLast(e);
            recorded++;
        }
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
        recorded = 0;
    }
}
