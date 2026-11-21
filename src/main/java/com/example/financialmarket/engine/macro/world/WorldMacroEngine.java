package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.WorldMacroModel.EconomicEvent;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.WorldAction;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.WorldStateSnapshot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Facade for the bottom-up world macro layer.
 *
 * <p>Producers submit {@link WorldAction}s (NeoForge events/adapters); the engine translates them,
 * aggregates company/sector flows and exposes a {@link WorldMacroSnapshot}. It is deliberately
 * decoupled from Minecraft at this level so it can be unit-tested offline; see
 * {@code WorldActionCollector}/{@code MacroTransmission} for the game wiring.</p>
 */
public final class WorldMacroEngine {

    private static final int MAX_ACTIONS = 20_000;

    private final ProfileRegistry registry;
    private final EconomicEventBus bus;
    private final EconomicTranslator translator;
    private final MacroFlowAggregator aggregator;
    private final EconomicLedger ledger;
    private final Deque<WorldAction> actions = new ArrayDeque<>();

    private long tick;
    private long day;
    private RecipeGraph recipeGraph = new RecipeGraph();
    private long eventsProcessed;
    private long actionsSubmitted;
    private WorldMacroSnapshot cached;
    private boolean dirty = true;
    private WorldStateSnapshot worldState = WorldStateSnapshot.empty(0, 0);

    public WorldMacroEngine() {
        this(ProfileRegistry.vanillaDefaults());
    }

    public WorldMacroEngine(ProfileRegistry registry) {
        this.registry = registry == null ? ProfileRegistry.vanillaDefaults() : registry;
        this.bus = new EconomicEventBus();
        this.translator = new EconomicTranslator(this.registry);
        this.aggregator = new MacroFlowAggregator();
        this.ledger = new EconomicLedger();
    }

    public void submit(WorldAction action) {
        if (action == null) {
            return;
        }
        actionsSubmitted++;
        if (actions.size() >= MAX_ACTIONS) {
            actions.pollFirst();
        }
        actions.addLast(action);
    }

    /** For adapters that already produced a normalized event (native tech/magic hooks). */
    public void submitEvent(EconomicEvent event) {
        bus.add(event);
    }

    public void step(long tick, long day) {
        this.tick = tick;
        this.day = day;
        aggregator.setTick(tick, day);
        int processed = 0;
        while (processed < 10_000) {
            WorldAction a = actions.pollFirst();
            if (a == null) {
                break;
            }
            for (EconomicEvent e : translator.translate(a)) {
                bus.add(e);
            }
            processed++;
        }
        for (EconomicEvent e : bus.drain(10_000)) {
            aggregator.apply(e);
            MacroFlowAggregator.CompanyEconomy company = aggregator.companies().get(e.companyId());
            double impact = company == null ? 0.0 : company.lastSurprise;
            ledger.record(e, impact);
            eventsProcessed++;
        }
        dirty = true;
    }

    public WorldMacroSnapshot snapshot() {
        if (cached == null || dirty) {
            cached = WorldMacroSnapshot.from(aggregator.snapshot(), aggregator.companies(),
                    eventsProcessed, bus.dropped(), registry.unmapped().size(), registry.size());
            dirty = false;
        }
        return cached;
    }

    public Map<String, Double> resourceSupply() {
        return aggregator.resourceSupply();
    }

    public Map<String, Double> resourceDemand() {
        return aggregator.resourceDemand();
    }

    public void clearResourceFlows() {
        aggregator.clearResourceFlows();
    }

    public void setWorldState(WorldStateSnapshot state) {
        if (state != null) {
            worldState = state;
        }
    }

    public WorldStateSnapshot worldState() {
        return worldState;
    }

    public void setRecipeGraph(RecipeGraph graph) {
        if (graph != null) {
            this.recipeGraph = graph;
        }
    }

    public RecipeGraph recipeGraph() {
        return recipeGraph;
    }

    public ProfileRegistry registry() {
        return registry;
    }

    public EconomicEventBus bus() {
        return bus;
    }

    public EconomicLedger ledger() {
        return ledger;
    }

    public MacroFlowAggregator aggregator() {
        return aggregator;
    }

    public long tick() {
        return tick;
    }

    public long day() {
        return day;
    }

    public long eventsProcessed() {
        return eventsProcessed;
    }

    public void restoreCounters(long tick, long day, long eventsProcessed) {
        this.tick = tick;
        this.day = day;
        this.eventsProcessed = Math.max(0, eventsProcessed);
        this.aggregator.setTick(tick, day);
        this.dirty = true;
    }

    public long actionsSubmitted() {
        return actionsSubmitted;
    }

    public int pendingActions() {
        return actions.size();
    }

    public void clear() {
        actions.clear();
        bus.clear();
        aggregator.reset();
        ledger.clear();
        translator.resetNovelty();
        eventsProcessed = 0;
        actionsSubmitted = 0;
        cached = null;
        dirty = true;
    }
}
