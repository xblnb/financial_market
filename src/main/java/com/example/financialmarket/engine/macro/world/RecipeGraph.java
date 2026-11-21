package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.WorldMacroModel.ProcessProfile;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceAmount;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resource-process graph compiled from recipes. Provides the minimal economics needed by the event
 * layer: direct cost, technology tier, output lookup, bottlenecks and cycle detection.
 *
 * <p>All methods are deterministic and allocation-light after construction; the runtime hot path
 * only performs map lookups.</p>
 */
public final class RecipeGraph {

    private final Map<String, ProcessProfile> processes = new LinkedHashMap<>();
    private final Map<String, List<String>> byOutput = new LinkedHashMap<>();
    private final Map<String, List<String>> byInput = new LinkedHashMap<>();
    private final Set<String> cyclicResources = new LinkedHashSet<>();

    public void add(ProcessProfile process) {
        if (process == null || process.outputs().isEmpty()) {
            return;
        }
        processes.put(process.id(), process);
        for (ResourceAmount out : process.outputs()) {
            byOutput.computeIfAbsent(out.key().id(), k -> new ArrayList<>()).add(process.id());
        }
        for (ResourceAmount in : process.inputs()) {
            byInput.computeIfAbsent(in.key().id(), k -> new ArrayList<>()).add(process.id());
        }
    }

    /** Replace an existing compiled process (pack override) or add it. */
    public void replace(ProcessProfile process) {
        if (process == null) {
            return;
        }
        ProcessProfile old = processes.remove(process.id());
        if (old != null) {
            for (ResourceAmount out : old.outputs()) {
                List<String> list = byOutput.get(out.key().id());
                if (list != null) {
                    list.remove(process.id());
                }
            }
            for (ResourceAmount in : old.inputs()) {
                List<String> list = byInput.get(in.key().id());
                if (list != null) {
                    list.remove(process.id());
                }
            }
        }
        add(process);
    }

    public int size() {
        return processes.size();
    }

    public CollectionView view() {
        return new CollectionView(processes, byOutput, byInput, cyclicResources);
    }

    public ProcessProfile process(String id) {
        return processes.get(id);
    }

    public List<ProcessProfile> processesForOutput(String resourceId) {
        List<String> ids = byOutput.get(resourceId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ProcessProfile> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            ProcessProfile p = processes.get(id);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    public List<ProcessProfile> processesForInput(String resourceId) {
        List<String> ids = byInput.get(resourceId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ProcessProfile> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            ProcessProfile p = processes.get(id);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** Cheapest known inputs for an output; useful when a crafted event has no explicit inputs. */
    public List<ResourceAmount> inputsForOutput(String resourceId) {
        ProcessProfile best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (ProcessProfile p : processesForOutput(resourceId)) {
            double cost = 0;
            for (ResourceAmount a : p.inputs()) {
                cost += a.quantity();
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = p;
            }
        }
        return best == null ? List.of() : best.inputs();
    }

    /** Iterative direct cost (input prices + process energy valued at a configurable unit cost). */
    public Map<String, Double> costs(ProfileRegistry registry, double energyUnitValue) {
        Map<String, Double> cost = new LinkedHashMap<>();
        for (String id : byOutput.keySet()) {
            cost.put(id, registry.resolve(id).unitValue());
        }
        for (int pass = 0; pass < 24; pass++) {
            boolean changed = false;
            for (ProcessProfile p : processes.values()) {
                double in = p.energyPerRun() * energyUnitValue;
                for (ResourceAmount a : p.inputs()) {
                    in += a.quantity() * cost.getOrDefault(a.key().id(),
                            registry.resolve(a.key()).unitValue());
                }
                double perUnit = p.outputs().isEmpty() ? 0
                        : in / Math.max(1e-9, p.outputs().get(0).quantity());
                for (ResourceAmount out : p.outputs()) {
                    String id = out.key().id();
                    double candidate = perUnit;
                    double old = cost.getOrDefault(id, registry.resolve(id).unitValue());
                    if (candidate > 0 && candidate < old) {
                        cost.put(id, candidate);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        return cost;
    }

    /** Technology tier: max input tier and process tier (iterative until stable). */
    public Map<String, Integer> tiers(ProfileRegistry registry) {
        Map<String, Integer> tier = new LinkedHashMap<>();
        for (String id : byOutput.keySet()) {
            tier.put(id, registry.resolve(id).tier());
        }
        for (int pass = 0; pass < 24; pass++) {
            boolean changed = false;
            for (ProcessProfile p : processes.values()) {
                int t = p.tier();
                for (ResourceAmount in : p.inputs()) {
                    t = Math.max(t, tier.getOrDefault(in.key().id(), registry.resolve(in.key()).tier()));
                }
                for (ResourceAmount out : p.outputs()) {
                    String id = out.key().id();
                    int old = tier.getOrDefault(id, 0);
                    if (t > old) {
                        tier.put(id, t);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        return tier;
    }

    /**
     * Resources that participate in a cycle (A -> B -> A). Such loops are potential arbitrage /
     * money printers; the caller should cap their contribution and flag them in the audit ledger.
     */
    public Set<String> cyclicResources() {
        if (!cyclicResources.isEmpty()) {
            return Collections.unmodifiableSet(cyclicResources);
        }
        Map<String, List<String>> edges = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (ProcessProfile p : processes.values()) {
            for (ResourceAmount in : p.inputs()) {
                for (ResourceAmount out : p.outputs()) {
                    edges.computeIfAbsent(in.key().id(), k -> new ArrayList<>())
                            .add(out.key().id());
                    indegree.putIfAbsent(in.key().id(), 0);
                    indegree.merge(out.key().id(), 1, Integer::sum);
                }
            }
        }
        // Kahn topological sort: nodes left with positive indegree are in (or downstream of) a cycle.
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }
        while (!queue.isEmpty()) {
            String node = queue.pollFirst();
            for (String next : edges.getOrDefault(node, List.of())) {
                int d = indegree.getOrDefault(next, 0) - 1;
                indegree.put(next, d);
                if (d == 0) {
                    queue.addLast(next);
                }
            }
        }
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() > 0) {
                cyclicResources.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(cyclicResources);
    }

    /** Input-output matrix: output resource -> input resource -> quantity per output unit. */
    public Map<String, Map<String, Double>> inputOutputMatrix() {
        Map<String, Map<String, Double>> matrix = new LinkedHashMap<>();
        for (ProcessProfile p : processes.values()) {
            if (p.outputs().isEmpty()) {
                continue;
            }
            double outQty = Math.max(1e-9, p.outputs().get(0).quantity());
            for (ResourceAmount out : p.outputs()) {
                Map<String, Double> row = matrix.computeIfAbsent(out.key().id(),
                        k -> new LinkedHashMap<>());
                for (ResourceAmount in : p.inputs()) {
                    row.merge(in.key().id(), in.quantity() / outQty, Double::sum);
                }
            }
        }
        return matrix;
    }

    /** Upstream cost shock propagation via a few Leontief iterations. */
    public Map<String, Double> propagateCostShock(String resourceId, double shockPct) {
        Map<String, Double> delta = new LinkedHashMap<>();
        delta.put(resourceId, shockPct);
        Map<String, Map<String, Double>> matrix = inputOutputMatrix();
        for (int pass = 0; pass < 8; pass++) {
            boolean changed = false;
            for (Map.Entry<String, Map<String, Double>> row : matrix.entrySet()) {
                double add = 0;
                for (Map.Entry<String, Double> in : row.getValue().entrySet()) {
                    add += in.getValue() * delta.getOrDefault(in.getKey(), 0.0);
                }
                if (add != 0) {
                    String out = row.getKey();
                    double old = delta.getOrDefault(out, 0.0);
                    delta.put(out, old + add);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return delta;
    }

    public List<String> bottlenecks(int limit) {
        List<Map.Entry<String, Integer>> counts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byOutput.entrySet()) {
            counts.add(Map.entry(e.getKey(), e.getValue().size()));
        }
        counts.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts) {
            if (out.size() >= limit) {
                break;
            }
            out.add(e.getKey() + " (" + e.getValue() + " process)");
        }
        return out;
    }

    public static final class CollectionView {
        public final Map<String, ProcessProfile> processes;
        public final Map<String, List<String>> byOutput;
        public final Map<String, List<String>> byInput;
        public final Set<String> cyclic;

        CollectionView(Map<String, ProcessProfile> processes, Map<String, List<String>> byOutput,
                       Map<String, List<String>> byInput, Set<String> cyclic) {
            this.processes = processes;
            this.byOutput = byOutput;
            this.byInput = byInput;
            this.cyclic = cyclic;
        }
    }
}
