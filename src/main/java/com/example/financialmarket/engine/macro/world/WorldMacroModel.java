package com.example.financialmarket.engine.macro.world;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Core value objects for the world-to-economy translation layer.
 *
 * <p>This class deliberately has no Minecraft imports: the economic model must stay testable
 * offline and independent of any mod. Minecraft-facing adapters live in
 * {@code WorldActionCollector}, {@code ProfileCompiler} and {@code MacroTransmission}.</p>
 */
public final class WorldMacroModel {

    private WorldMacroModel() {
    }

    public enum ResourceKind {
        ITEM, FLUID, ENERGY, MANA, SERVICE, KNOWLEDGE
    }

    public enum ActionType {
        BREAK_BLOCK, PLACE_BLOCK, CRAFT, SMELT, MACHINE_RUN, HARVEST, KILL,
        CAST_SPELL, RITUAL, TRADE, ADVANCEMENT, EXPLORE, DEATH, OTHER
    }

    public enum EconomicType {
        EXTRACTION, PRODUCTION, CONSUMPTION, INVESTMENT, RND, RISK,
        LOGISTICS, LABOR, DISCOVERY, SPELL, RITUAL, TRADE
    }

    public enum ProcessKind {
        CRAFT, SMELT, CRUSH, CHEM, ASSEMBLY, RITUAL, SPELL, LOGISTICS, OTHER
    }

    /**
     * A compiled recipe/process edge. Energy is in the resource's native unit (FE/mana/LP); the
     * economic layer treats it as an input cost and does not assume cross-mod conversion.
     */
    public record ProcessProfile(
            String id,
            ProcessKind kind,
            String sector,
            int tier,
            List<ResourceAmount> inputs,
            List<ResourceAmount> outputs,
            double energyPerRun,
            double timePerRun,
            String sourceMod) {

        public ProcessProfile {
            id = id == null || id.isBlank() ? "process:unknown" : id;
            kind = kind == null ? ProcessKind.OTHER : kind;
            sector = sector == null || sector.isBlank() ? "other" : sector;
            tier = Math.max(0, tier);
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            energyPerRun = Double.isFinite(energyPerRun) ? Math.max(0, energyPerRun) : 0;
            timePerRun = Double.isFinite(timePerRun) ? Math.max(0, timePerRun) : 0;
            sourceMod = sourceMod == null || sourceMod.isBlank() ? "unknown" : sourceMod;
        }

        public double valueAdded(ProfileRegistry registry) {
            double out = 0;
            for (ResourceAmount a : outputs) {
                out += a.quantity() * registry.resolve(a.key()).unitValue();
            }
            double in = 0;
            for (ResourceAmount a : inputs) {
                in += a.quantity() * registry.resolve(a.key()).unitValue();
            }
            return out - in;
        }
    }

    /** Canonical resource identity. Prefer tags ({@code c:ingots/osmium}) over mod ids. */
    public record ResourceKey(String id, ResourceKind kind) {
        public ResourceKey {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("resource id must not be blank");
            }
            if (kind == null) {
                kind = ResourceKind.ITEM;
            }
        }

        public static ResourceKey item(String id) {
            return new ResourceKey(id, ResourceKind.ITEM);
        }

        public static ResourceKey fluid(String id) {
            return new ResourceKey(id, ResourceKind.FLUID);
        }

        public static ResourceKey energy(String id) {
            return new ResourceKey(id, ResourceKind.ENERGY);
        }

        public static ResourceKey mana(String id) {
            return new ResourceKey(id, ResourceKind.MANA);
        }
    }

    /**
     * Economic profile of a resource. {@code sector} and {@code category} are deliberately coarse:
     * a large modpack cannot maintain per-item bespoke economics.
     */
    public record ResourceProfile(
            ResourceKey key,
            String sector,
            String category,
            int tier,
            double unitValue,
            double elasticity,
            Set<String> tags,
            Set<String> substitutes) {

        public ResourceProfile {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            sector = sector == null || sector.isBlank() ? "other" : sector;
            category = category == null || category.isBlank() ? "other" : category;
            tier = Math.max(0, tier);
            unitValue = Double.isFinite(unitValue) && unitValue > 0 ? unitValue : 1.0;
            elasticity = Double.isFinite(elasticity) && elasticity > 0 ? elasticity : 0.5;
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            substitutes = substitutes == null ? Set.of() : Set.copyOf(substitutes);
        }

        public static ResourceProfile other(ResourceKey key) {
            return new ResourceProfile(key, "other", "other", 0, 1.0, 0.5, Set.of(), Set.of());
        }
    }

    public record ResourceAmount(ResourceKey key, double quantity) {
        public ResourceAmount {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            if (!Double.isFinite(quantity) || quantity < 0) {
                quantity = 0;
            }
        }
    }

    /** One raw action captured from the game, before economic interpretation. */
    public record WorldAction(
            long tick,
            long seq,
            UUID actor,
            String companyId,
            ActionType action,
            String sourceMod,
            String sourceId,
            String region,
            List<ResourceAmount> inputs,
            List<ResourceAmount> outputs,
            double confidence) {

        public WorldAction {
            action = action == null ? ActionType.OTHER : action;
            sourceMod = sourceMod == null ? "unknown" : sourceMod;
            sourceId = sourceId == null ? "" : sourceId;
            region = region == null || region.isBlank() ? "world" : region;
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            confidence = Double.isFinite(confidence) ? Math.max(0, Math.min(1, confidence)) : 0.5;
        }

        public static WorldAction of(long tick, long seq, UUID actor, String companyId,
                                     ActionType action, String sourceMod, String sourceId,
                                     List<ResourceAmount> inputs, List<ResourceAmount> outputs) {
            return new WorldAction(tick, seq, actor, companyId, action, sourceMod, sourceId,
                    "world", inputs, outputs, 0.8);
        }
    }

    public record ResourceFlow(ResourceKey key, double quantity, double unitValue) {
        public ResourceFlow {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            quantity = Double.isFinite(quantity) ? Math.max(0, quantity) : 0;
            unitValue = Double.isFinite(unitValue) ? Math.max(0, unitValue) : 0;
        }

        public double value() {
            return quantity * unitValue;
        }
    }

    /** One economically meaningful event. */
    public record EconomicEvent(
            long tick,
            long seq,
            EconomicType type,
            UUID actor,
            String companyId,
            String sector,
            String region,
            List<ResourceFlow> inputs,
            List<ResourceFlow> outputs,
            double valueAdded,
            double novelty,
            double persistence,
            String sourceMod,
            String adapter,
            String provenance,
            double confidence) {

        public EconomicEvent {
            type = type == null ? EconomicType.PRODUCTION : type;
            companyId = companyId == null || companyId.isBlank() ? "individual" : companyId;
            sector = sector == null || sector.isBlank() ? "other" : sector;
            region = region == null || region.isBlank() ? "world" : region;
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            valueAdded = Double.isFinite(valueAdded) ? valueAdded : 0;
            novelty = Double.isFinite(novelty) ? Math.max(0, Math.min(1, novelty)) : 0.5;
            persistence = Double.isFinite(persistence) ? Math.max(0, Math.min(1, persistence)) : 0;
            sourceMod = sourceMod == null ? "unknown" : sourceMod;
            adapter = adapter == null ? "generic" : adapter;
            provenance = provenance == null ? "" : provenance;
            confidence = Double.isFinite(confidence) ? Math.max(0, Math.min(1, confidence)) : 0.5;
        }

        public double outputValue() {
            double v = 0;
            for (ResourceFlow f : outputs) {
                v += f.value();
            }
            return v;
        }

        public double inputValue() {
            double v = 0;
            for (ResourceFlow f : inputs) {
                v += f.value();
            }
            return v;
        }
    }

    /** Slow-moving world state sampled once per day/period, not per tick. */
    public record WorldStateSnapshot(
            long tick,
            long day,
            double population,
            double villagePopulation,
            double employment,
            double laborSupply,
            double pollution,
            double weatherSeverity,
            double mobDensity,
            double infrastructure,
            double energySupply,
            double manaSupply,
            double resourceReserveIndex,
            double techTierMean,
            double riskLevel) {

        public static WorldStateSnapshot empty(long tick, long day) {
            return new WorldStateSnapshot(tick, day, 0, 0, 1, 1, 0, 0, 0, 1, 1, 1, 1, 0, 0);
        }
    }
}
