package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.WorldMacroModel.ActionType;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.EconomicEvent;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.EconomicType;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceAmount;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceFlow;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceProfile;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.WorldAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a raw {@link WorldAction} into one (occasionally two) economically meaningful events.
 *
 * <p>Rules of thumb: extraction has only outputs; production has inputs and outputs and contributes
 * {@code valueAdded = outputs - inputs}; consumption has only inputs (demand, not GDP); investment
 * and R&D are stock-forming; risk/loss events feed the risk premium, not output.</p>
 */
public final class EconomicTranslator {

    private final ProfileRegistry registry;
    private final Map<String, Integer> occurrences = new HashMap<>();


    public EconomicTranslator(ProfileRegistry registry) {
        this.registry = registry;
    }

    public List<EconomicEvent> translate(WorldAction action) {
        if (action == null) {
            return List.of();
        }
        List<ResourceFlow> inputs = flows(action.inputs());
        List<ResourceFlow> outputs = flows(action.outputs());

        // A break/harvest often carries the block/item only in sourceId.
        if (outputs.isEmpty() && (action.action() == ActionType.BREAK_BLOCK
                || action.action() == ActionType.HARVEST)) {
            outputs = List.of(flow(action.sourceId(), 1.0));
        }
        // A spell/ritual with no explicit item still produces a service.
        if (outputs.isEmpty() && (action.action() == ActionType.CAST_SPELL
                || action.action() == ActionType.RITUAL)) {
            outputs = List.of(flow("service:spell", 1.0));
        }
        if (inputs.isEmpty() && outputs.isEmpty()) {
            if (action.action() == ActionType.KILL || action.action() == ActionType.DEATH) {
                outputs = List.of(flow("service:loss", 1.0));
            } else if (action.action() == ActionType.ADVANCEMENT
                    || action.action() == ActionType.EXPLORE) {
                outputs = List.of(flow("knowledge:research", 1.0));
            } else {
                return List.of();
            }
        }

        EconomicType type = classify(action.action(), inputs, outputs);
        double inValue = value(inputs);
        double outValue = value(outputs);
        double va = switch (type) {
            case PRODUCTION, EXTRACTION, SPELL, RITUAL, DISCOVERY -> outValue - inValue;
            case INVESTMENT -> outValue;
            case RND -> Math.max(inValue, outValue);
            case CONSUMPTION, RISK, LABOR, LOGISTICS, TRADE -> 0.0;
        };
        // Negative VA is a real loss; keep it for margin/earnings, but it contributes no output.
        String sector = dominantSector(outputs, inputs);
        String noveltyKey = key(action) + "|" + action.sourceId() + "|" + action.action();
        int count = occurrences.getOrDefault(noveltyKey, 0);
        occurrences.put(noveltyKey, count + 1);
        double novelty = 1.0 / (1.0 + 0.5 * count);
        if (action.action() == ActionType.ADVANCEMENT) {
            novelty = Math.max(novelty, count == 0 ? 1.0 : 0.0);
        }
        double persistence = switch (type) {
            case INVESTMENT, RND, DISCOVERY -> 1.0;
            default -> 0.0;
        };
        double confidence = action.confidence() * (allKnown(inputs, outputs) ? 1.0 : 0.5);
        EconomicEvent primary = new EconomicEvent(action.tick(), action.seq(), type, action.actor(),
                action.companyId(), sector, action.region(), inputs, outputs, va, novelty,
                persistence, action.sourceMod(), "generic",
                action.sourceMod() + ":" + action.sourceId(), confidence);
        List<EconomicEvent> out = new ArrayList<>(3);
        out.add(primary);

        // A novel spell also creates a small knowledge/R&D spillover (magic -> tech).
        if (type == EconomicType.SPELL && novelty > 0.25) {
            out.add(new EconomicEvent(action.tick(), action.seq() + 2, EconomicType.RND,
                    action.actor(), action.companyId(), "services", action.region(), List.of(),
                    List.of(flow("knowledge:research", 0.1)), Math.max(1.0, outValue * 0.05),
                    novelty * 0.5, 1.0, action.sourceMod(), "generic",
                    action.sourceMod() + ":" + action.sourceId(), confidence * 0.7));
        }
        // A ritual or an uncontrolled spell also carries operational risk.
        if (type == EconomicType.RITUAL) {
            double riskValue = Math.max(1.0, outValue * 0.05);
            out.add(new EconomicEvent(action.tick(), action.seq() + 1, EconomicType.RISK,
                    action.actor(), action.companyId(), sector, action.region(), List.of(),
                    List.of(flow("service:spell", 1.0)), riskValue * 0.01, novelty * 0.5, 1.0,
                    action.sourceMod(), "generic", action.sourceMod() + ":" + action.sourceId(),
                    confidence * 0.8));
        }
        return out;
    }

    static EconomicType classify(ActionType action, List<ResourceFlow> inputs,
                                 List<ResourceFlow> outputs) {
        return switch (action) {
            case BREAK_BLOCK, HARVEST -> EconomicType.EXTRACTION;
            case PLACE_BLOCK -> EconomicType.INVESTMENT;
            case CRAFT, SMELT, MACHINE_RUN -> outputs.isEmpty() && !inputs.isEmpty()
                    ? EconomicType.CONSUMPTION : EconomicType.PRODUCTION;
            case KILL, DEATH -> EconomicType.RISK;
            case CAST_SPELL -> EconomicType.SPELL;
            case RITUAL -> EconomicType.RITUAL;
            case TRADE -> EconomicType.TRADE;
            case ADVANCEMENT -> EconomicType.RND;
            case EXPLORE -> EconomicType.DISCOVERY;
            case OTHER -> {
                if (outputs.isEmpty() && !inputs.isEmpty()) {
                    yield EconomicType.CONSUMPTION;
                }
                if (inputs.isEmpty() && !outputs.isEmpty()) {
                    yield EconomicType.EXTRACTION;
                }
                yield EconomicType.PRODUCTION;
            }
        };
    }

    private List<ResourceFlow> flows(List<ResourceAmount> amounts) {
        if (amounts == null || amounts.isEmpty()) {
            return List.of();
        }
        List<ResourceFlow> out = new ArrayList<>(amounts.size());
        for (ResourceAmount amount : amounts) {
            out.add(flow(amount.key().id(), amount.quantity()));
        }
        return out;
    }

    private ResourceFlow flow(String id, double qty) {
        ResourceProfile p = registry.resolve(id);
        return new ResourceFlow(p.key(), qty, p.unitValue());
    }

    private boolean allKnown(List<ResourceFlow> inputs, List<ResourceFlow> outputs) {
        for (ResourceFlow f : inputs) {
            if (!registry.contains(f.key().id())) {
                return false;
            }
        }
        for (ResourceFlow f : outputs) {
            if (!registry.contains(f.key().id())) {
                return false;
            }
        }
        return true;
    }

    private String dominantSector(List<ResourceFlow> outputs, List<ResourceFlow> inputs) {
        String best = "other";
        double bestValue = -1;
        for (ResourceFlow f : outputs) {
            ResourceProfile p = registry.resolve(f.key());
            double v = f.value();
            if (v > bestValue) {
                bestValue = v;
                best = p.sector();
            }
        }
        if (bestValue < 0) {
            for (ResourceFlow f : inputs) {
                ResourceProfile p = registry.resolve(f.key());
                double v = f.value();
                if (v > bestValue) {
                    bestValue = v;
                    best = p.sector();
                }
            }
        }
        return best;
    }

    private static double value(List<ResourceFlow> flows) {
        double v = 0;
        for (ResourceFlow f : flows) {
            v += f.value();
        }
        return v;
    }

    private static String key(WorldAction action) {
        return (action.actor() == null ? "none" : action.actor().toString())
                + "|" + action.companyId();
    }



    public void resetNovelty() {
        occurrences.clear();
    }

    public ProfileRegistry registry() {
        return registry;
    }
}
