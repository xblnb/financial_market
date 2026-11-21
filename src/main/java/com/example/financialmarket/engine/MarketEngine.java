/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.nbt.ListTag
 *  net.minecraft.nbt.NbtAccounter
 *  net.minecraft.nbt.NbtIo
 *  net.minecraft.nbt.Tag
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.item.crafting.RecipeManager
 */
package com.example.financialmarket.engine;

import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.block.DeliveryBlockEntity;
import com.example.financialmarket.company.AssetScanner;
import com.example.financialmarket.company.CompanyData;
import com.example.financialmarket.company.CompanyIpoState;
import com.example.financialmarket.company.CompanyManager;
import com.example.financialmarket.company.CompanyStatus;
import com.example.financialmarket.company.DelistingMonitor;
import com.example.financialmarket.company.ListingValidator;
import com.example.financialmarket.engine.AssetSnapshot;
import com.example.financialmarket.engine.CommodityMarket;
import com.example.financialmarket.engine.CommodityRegistry;
import com.example.financialmarket.engine.Excellent24Index;
import com.example.financialmarket.engine.FundamentalEngine;
import com.example.financialmarket.engine.MacroStateEngine;
import com.example.financialmarket.engine.MarketDataCollector;
import com.example.financialmarket.engine.MarketSentiment;
import com.example.financialmarket.engine.MarketState;
import com.example.financialmarket.engine.Order;
import com.example.financialmarket.engine.OrderBook;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import com.example.financialmarket.engine.RecipeAnalyzer;
import com.example.financialmarket.engine.SettlementSystem;
import com.example.financialmarket.engine.Trade;
import com.example.financialmarket.engine.agent.MultiAgentManager;
import com.example.financialmarket.engine.ai.AICompanyData;
import com.example.financialmarket.engine.ai.AICompanyManager;
import com.example.financialmarket.engine.banking.Bank;
import com.example.financialmarket.engine.banking.BankCardService;
import com.example.financialmarket.engine.banking.BankManager;
import com.example.financialmarket.engine.banking.PaymentService;
import com.example.financialmarket.engine.banking.CentralBank;
import com.example.financialmarket.engine.banking.CreditSystem;
import com.example.financialmarket.engine.banking.Government;
import com.example.financialmarket.engine.banking.InflationSystem;
import com.example.financialmarket.engine.banking.InterbankMarket;
import com.example.financialmarket.engine.banking.TaxSystem;
import com.example.financialmarket.engine.behavior.BehaviorParams;
import com.example.financialmarket.engine.buyback.StockBuyback;
import com.example.financialmarket.engine.calendar.TradingCalendar;
import com.example.financialmarket.engine.calibration.CalibrationParams;
import com.example.financialmarket.engine.capm.CapmModel;
import com.example.financialmarket.engine.circuit.CircuitBreaker;
import com.example.financialmarket.engine.crisis.CrisisEngine;
import com.example.financialmarket.engine.futures.FuturesMarket;
import com.example.financialmarket.engine.futures.czce.CzceFuturesMarket;
import com.example.financialmarket.engine.futures.czce.CzceOptionDesk;
import com.example.financialmarket.engine.macro.cross.CrossAssetMacro;
import com.example.financialmarket.engine.macro.growth.AkGrowthEngine;
import com.example.financialmarket.engine.gold.GoldPricingSystem;
import com.example.financialmarket.engine.leverage.LeveragedNoteMarket;
import com.example.financialmarket.engine.macro.EconomicCycleEngine;
import com.example.financialmarket.engine.macro.MacroEnvironment;
import com.example.financialmarket.engine.macro.MacroSnapshot;
import com.example.financialmarket.engine.macro.world.EconomyProfileLoader;
import com.example.financialmarket.engine.macro.world.MacroTransmission;
import com.example.financialmarket.engine.macro.world.MacroCalibration;
import com.example.financialmarket.engine.macro.world.ProfileCompiler;
import com.example.financialmarket.engine.macro.world.TechStorageSampler;
import com.example.financialmarket.engine.macro.world.WorldMacroEngine;
import com.example.financialmarket.engine.macro.world.WorldMacroPersistence;
import com.example.financialmarket.engine.macro.world.WorldMacroSnapshot;
import com.example.financialmarket.engine.macro.world.WorldStateCollector;
import com.example.financialmarket.engine.tabl.TablPriceModel;
import com.example.financialmarket.engine.margin.MarginManager;
import com.example.financialmarket.engine.meso.MesoLayerManager;
import com.example.financialmarket.engine.meso.MesoOrderIntent;
import com.example.financialmarket.engine.microstructure.OrderFlowModel;
import com.example.financialmarket.engine.microstructure.OrderPipeline;
import com.example.financialmarket.engine.microstructure.VolumeTracker;
import com.example.financialmarket.engine.monetary.MonetaryAggregator;
import com.example.financialmarket.engine.monetary.PolicyEvent;
import com.example.financialmarket.engine.naming.AStockCodePool;
import com.example.financialmarket.engine.quant.QuantAPI;
import com.example.financialmarket.engine.quant.QuantScriptEngine;
import com.example.financialmarket.engine.random.SeedableRandom;
import com.example.financialmarket.engine.role.CentralBankerGuardrail;
import com.example.financialmarket.engine.role.FinanceMinisterGuardrail;
import com.example.financialmarket.engine.role.RoleSystem;
import com.example.financialmarket.engine.securities.SecuritiesManager;
import com.example.financialmarket.engine.war.FinancialWarSystem;
import com.example.financialmarket.network.packets.OrderBookSnapshotPacket;
import com.example.financialmarket.storage.db.H2DatabaseManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.LambdaMetafactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeManager;

public class MarketEngine {
    private final PriceDiscoveryEngine priceEngine;
    private final MacroEnvironment macroEnvironment;
    private final MultiAgentManager agentManager;
    private final MacroStateEngine macroEngine;
    private final FundamentalEngine fundEngine;
    private final MarketDataCollector dataCollector;
    private final MarketState state = new MarketState();
    private final CompanyManager companyManager = new CompanyManager();
    private final AICompanyManager aiCompanyManager;
    private final AStockCodePool aStockCodePool;
    private final MarketSentiment sentiment;
    private final CommodityMarket commodityMarket = new CommodityMarket();
    private final WorldMacroEngine worldMacro = new WorldMacroEngine();
    private final TechStorageSampler techStorageSampler = new TechStorageSampler();
    private final WorldStateCollector worldStateCollector = new WorldStateCollector();
    private final Excellent24Index excellent24Index = new Excellent24Index();
    private final BankManager bankManager = new BankManager();
    private final Government government = new Government();
    private final CentralBank centralBank;
    private final InflationSystem inflationSystem = new InflationSystem();
    private final CreditSystem creditSystem = new CreditSystem();
    private final BankCardService bankCardService;
    private final PaymentService paymentService;
    private boolean bankingEnabled = true;
    private boolean marginEnabled = true;
    private boolean leverageEnabled = true;
    private final TaxSystem taxSystem = new TaxSystem(this.government);
    private final Set<UUID> fundedPlayers = new HashSet<UUID>();
    private final CrisisEngine crisisEngine;
    private final GoldPricingSystem goldSystem;
    private boolean goldEnabled = true;
    private final QuantAPI quantAPI;
    private final QuantScriptEngine quantScriptEngine;
    private final MarginManager marginManager;
    private final LeveragedNoteMarket leverageMarket;
    private final EconomicCycleEngine cycleEngine;
    private final SecuritiesManager securitiesManager = new SecuritiesManager();
    private final FinancialWarSystem financialWarSystem;
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private boolean graphInitialized = false;
    private final SeedableRandom engineRng;
    private final long engineSeed;
    private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<String, OrderBook>();
    private final Map<String, String> assetSector = new ConcurrentHashMap<String, String>();
    private final Map<String, List<Trade>> tradeHistory = new ConcurrentHashMap<String, List<Trade>>();
    private final Map<String, List<Trade>> dailyTrades = new ConcurrentHashMap<String, List<Trade>>();
    private final Map<String, Double> todayOpen = new ConcurrentHashMap<String, Double>();
    private final Map<String, Double> todayClose = new ConcurrentHashMap<String, Double>();
    private final Map<String, Boolean> hasTodayTrade = new ConcurrentHashMap<String, Boolean>();
    private long currentDay = 0L;
    private long tickCounter = 0L;
    private static final long TICKS_PER_DAY = 24000L;
    private static final long AI_ORDER_INTERVAL = 20L;
    private static final long FUNDAMENTAL_UPDATE_QUARTER_TICKS = 2160000L;
    private boolean firstAIGenerated = false;
    private final Map<String, List<SettlementSystem.PendingSettlement>> pendingSettlements = new ConcurrentHashMap<String, List<SettlementSystem.PendingSettlement>>();
    private final Map<String, Boolean> circuitBreakerTripped = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Long> circuitBreakerRecoveryTime = new ConcurrentHashMap<String, Long>();
    private static final double COMMISSION_RATE = 0.001;
    private static final double SLIPPAGE_FACTOR = 0.002;
    private final OrderFlowModel orderFlowModel;
    private boolean p2Enabled = true;
    private boolean endogenousEnabled = true;
    private boolean orderFlowEnabled = true;
    private boolean marSEnabled = true;
    private final VolumeTracker volumeTracker;
    private final OrderPipeline orderPipeline;
    private static final double AGENT_ORDER_URGENCY = 0.5;
    private final MesoLayerManager mesoLayer;
    private boolean mesoToMicroBias = false;
    private static final long MESO_STEP_TICKS = 20L;
    private boolean liqEnabled = true;
    private boolean liqHftEnabled = true;
    private boolean liqMaxWaitEnabled = true;
    private boolean liqAuctionEnabled = true;
    private boolean liqCircuitRehangEnabled = true;
    private static final double LIQ_HFT_LAMBDA = 0.02;
    private static final long LIQ_MAX_WAIT_TICKS = 60L;
    private static final double LIQ_FORCED_LOT_SCALE = 2.0;
    private static final double LIQ_HFT_DEPTH_K = 0.5;
    private static final double LIQ_HFT_CONTRARIAN_PROB = 0.1;
    private static final long LIQ_CANCEL_REHANG_TICKS = 100L;
    private static final long LIQ_CALL_AUCTION_TICKS = 120L;
    private static final double LIQ_REHANG_DELTA_RATIO = 0.01;
    private static final int LIQ_REHANG_LOT = 300;
    private final Map<String, Long> lastFillTick = new ConcurrentHashMap<String, Long>();
    private final Map<String, Long> lastRehangTick = new ConcurrentHashMap<String, Long>();
    private final Map<String, Long> lastAuctionTick = new ConcurrentHashMap<String, Long>();
    private final Map<String, SeedableRandom> liqHftRngs = new ConcurrentHashMap<String, SeedableRandom>();
    private static final long COOLING_WINDOW_TICKS = 100L;
    private static final int COOLING_MIN_ORDERS = 4;
    private static final int COOLING_MAX_WINDOWS = 3;
    private static final double EQ_DEVIATION_THRESHOLD = 0.06;
    private static final int EQ_FAILURE_TRIGGER_TICKS = 30;
    private static final double EQ_PRICE_WEIGHT = 0.5;
    private static final int EQ_WINDOW_TRADES = 50;
    private final Map<String, DgCoolingState> dgCooling = new ConcurrentHashMap<String, DgCoolingState>();
    private long dgCageRejectCount = 0L;
    private long dgCageClampCount = 0L;
    private boolean cmEnabled = true;
    private boolean cmClearingEnabled = true;
    private boolean cmCapmEnabled = true;
    private final CapmModel capmModel = new CapmModel();
    private final TradingCalendar calendar = new TradingCalendar();
    private boolean aShareEnabled = true;
    private boolean companySystemEnabled = true;
    private boolean commodityEnabled = true;
    private boolean futuresEnabled = true;
    private boolean tradeBlockEnabled = true;
    private final Map<String, DeliveryBlockEntity> deliveryBlockRegistry = new HashMap<String, DeliveryBlockEntity>();
    private final FuturesMarket futuresMarket = new FuturesMarket();
    private CzceFuturesMarket czceFutures;
    private CzceOptionDesk czceOptions;
    private final CrossAssetMacro crossAssetMacro = new CrossAssetMacro();
    private final AkGrowthEngine akGrowthEngine = new AkGrowthEngine();
    private String futuresMode = System.getProperty("financialmarket.futures.mode", "czce").toLowerCase();
    private final SettlementSystem settlement = new SettlementSystem();
    private static final double IPO_FLOAT_RATIO = 0.4;
    private static final long IPO_WINDOW_TRADING_DAYS = 3L;
    private static final long REPORT_QUARTER_TICKS = 2160000L;
    private static final long DIVIDEND_PERIOD_TICKS = 8640000L;
    private static final long DELIST_CHECK_TICKS = 720000L;
    private static final double DIVIDEND_PAYOUT_RATIO = 0.5;
    private final Map<UUID, PendingEconomyChange> pendingEconomy = new HashMap<UUID, PendingEconomyChange>();
    private final Map<UUID, PendingTradeSettlement> pendingTradeSettlement = new HashMap<UUID, PendingTradeSettlement>();
    private ServerLevel currentLevel;
    private boolean dataGuardEnabled = true;
    private boolean dataGuardCageEnabled = true;
    private boolean dataGuardAlmgrenChrissEnabled = true;
    private boolean dataGuardCoolingEnabled = true;
    private boolean dataGuardDupontEnabled = true;
    private boolean centralBankEnabled = true;
    private boolean centralBankAggregationEnabled = true;
    private boolean centralBankBalanceSheetEnabled = true;
    private boolean centralBankCorridorEnabled = true;
    private boolean centralBankLprEnabled = true;
    private boolean centralBankRfPolicyRateEnabled = true;
    private boolean centralBankRequiredReserveSyncEnabled = true;
    private boolean centralBankFiscalDominanceEnabled = false;
    private boolean centralBankLastResortEnabled = true;
    private boolean centralBankCbdcEnabled = true;
    private boolean centralBankEventsEnabled = true;
    private long appliedPolicyEventCount = 0L;
    private double lastMeanFairValue = 0.0;
    private int lastTroubledStateTransitions = 0;
    public static final double CB_RF_MIN = 0.001;
    public static final double CB_RF_MAX = 0.2;
    private static final String ENGINE_KV_KEY = "engine_main";
    private static final String INDEX_ID = "excellent24";

    private boolean cmClearingEffective() {
        return this.cmEnabled && this.cmClearingEnabled;
    }

    private boolean cmCapmEffective() {
        return this.cmEnabled && this.cmCapmEnabled;
    }

    public MarketEngine(long seed) {
        this.engineSeed = seed;
        this.aStockCodePool = new AStockCodePool(new SeedableRandom(new SeedableRandom(SeedableRandom.mix64(seed ^ (long)"a_share_naming".hashCode())).derive("code_pool")));
        AICompanyData.setDupontEnabled(this.isDupontEffective());
        this.aiCompanyManager = new AICompanyManager(seed, this.aStockCodePool);
        this.macroEngine = new MacroStateEngine(seed);
        this.fundEngine = new FundamentalEngine();
        this.dataCollector = new MarketDataCollector();
        this.cycleEngine = new EconomicCycleEngine(seed);
        this.sentiment = new MarketSentiment(seed);
        this.macroEnvironment = new MacroEnvironment(seed);
        this.priceEngine = new PriceDiscoveryEngine(seed);
        this.priceEngine.setTablModel(new TablPriceModel(seed));
        this.agentManager = new MultiAgentManager(seed);
        this.priceEngine.setMacroDependencies(this.sentiment, this.cycleEngine, this.state);
        this.priceEngine.setMacroEnvironment(this.macroEnvironment);
        this.agentManager.setMacroEnvironment(this.macroEnvironment);
        this.agentManager.setSentiment(this.sentiment);
        this.agentManager.setSectorFlowProvider(this::sectorFlow);
        this.agentManager.setFloatSharesProvider(assetId -> this.floatSharesOf((String)assetId));
        this.centralBank = this.bankManager.getCentralBank();
        this.financialWarSystem = new FinancialWarSystem(this.sentiment, this.commodityMarket, this.centralBank, this.government);
        this.priceEngine.setAShareEnabled(this.aShareEnabled);
        this.engineRng = new SeedableRandom(SeedableRandom.mix64(seed));
        this.orderFlowModel = new OrderFlowModel(seed, CalibrationParams.DEFAULT);
        this.volumeTracker = new VolumeTracker(CalibrationParams.DEFAULT.getAdvWindowDays());
        this.orderPipeline = new OrderPipeline(seed);
        this.mesoLayer = new MesoLayerManager(seed);
        this.quantAPI = new QuantAPI(seed);
        this.quantScriptEngine = new QuantScriptEngine(seed);
        this.marginManager = new MarginManager(seed);
        this.leverageMarket = new LeveragedNoteMarket(seed);
        this.leverageMarket.setEngine(this);
        this.crisisEngine = new CrisisEngine(seed);
        this.goldSystem = new GoldPricingSystem(seed);
        this.securitiesManager.setSeed(seed);
        this.commodityMarket.setEngine(this);
        this.futuresMarket.setEngine(this);
        this.czceFutures = new CzceFuturesMarket(seed);
        this.czceOptions = new CzceOptionDesk(this.czceFutures, seed);
        this.czceFutures.setFlowListener((product, signedLots, price) ->
                this.crossAssetMacro.recordFuturesFlow(product, signedLots, price));
        this.bankCardService = new BankCardService(SeedableRandom.mix64(seed ^ (long)"bankCardService".hashCode()));
        ArrayList<String> bankIds = new ArrayList<String>();
        for (Bank b : this.bankManager.getAllBanks()) {
            bankIds.add(b.getBankId());
        }
        this.bankCardService.setValidBankIds(bankIds);
        this.paymentService = new PaymentService(this.bankManager, this.bankCardService);
        this.companyManager.setEngine(this);
        this.companyManager.setStockCodePool(this.aStockCodePool);
        this.centralBank.setMonetaryAggregator(this.bankManager.getMonetaryAggregator());
        CentralBank.setFiscalLedgerHook(this.centralBank);
        this.applyCentralBankSwitches();
    }

    private void applyCentralBankSwitches() {
        this.centralBank.setEnabled(this.centralBankEnabled);
        this.centralBank.setBalanceSheetEnabled(this.centralBankBalanceSheetEnabled);
        this.centralBank.setLastResortEnabled(this.centralBankLastResortEnabled);
        this.centralBank.setEventsEnabled(this.centralBankEventsEnabled);
        this.centralBank.setFiscalDominanceEnabled(this.centralBankFiscalDominanceEnabled);
        this.centralBank.setCbdcEnabled(this.cbdcEffective());
        this.bankManager.getMonetaryAggregator().setCbdcSwitchEnabled(this.cbdcEffective());
        if (!this.cbdcEffective()) {
            this.centralBank.redeemAllCbdc();
        }
        this.bankManager.setAggregationEnabled(this.cbAggEffective());
        this.bankManager.setLprTransmissionEnabled(this.cbLprEffective());
        this.bankManager.setReserveSyncEnabled(this.cbRrSyncEffective());
        this.bankManager.getMonetaryAggregator().setAggregationEnabled(this.cbAggEffective());
        this.bankManager.getInterbankMarket().setCorridorEnabled(this.cbCorridorEffective());
    }

    public void setP2Enabled(boolean enabled) {
        this.p2Enabled = enabled;
        this.endogenousEnabled = enabled;
        this.orderFlowEnabled = enabled;
        this.applyEndogenousToAgentManager(enabled);
    }

    public boolean isP2Enabled() {
        return this.p2Enabled;
    }

    public boolean isEndogenousEnabled() {
        return this.endogenousEnabled;
    }

    public void setMarSEnabled(boolean enabled) {
        this.marSEnabled = enabled;
        this.wireMesoToMicroBias();
    }

    public boolean isMarSEnabled() {
        return this.marSEnabled;
    }

    public void setCentralBankEnabled(boolean enabled) {
        this.centralBankEnabled = enabled;
        this.applyCentralBankSwitches();
    }

    public boolean isCentralBankEnabled() {
        return this.centralBankEnabled;
    }

    public void setCentralBankAggregationEnabled(boolean enabled) {
        this.centralBankAggregationEnabled = enabled;
        this.applyCentralBankSwitches();
    }

    public boolean isCentralBankAggregationEnabled() {
        return this.centralBankAggregationEnabled;
    }

    public void setCentralBankBalanceSheetEnabled(boolean enabled) {
        this.centralBankBalanceSheetEnabled = enabled;
        this.applyCentralBankSwitches();
    }

    public boolean isCentralBankBalanceSheetEnabled() {
        return this.centralBankBalanceSheetEnabled;
    }

    public void setCentralBankCorridorEnabled(boolean enabled) {
        this.centralBankCorridorEnabled = enabled;
        this.applyCentralBankSwitches();
    }

    public boolean isCentralBankCorridorEnabled() {
        return this.centralBankCorridorEnabled;
    }

    public void setCentralBankLprEnabled(boolean enabled) {
        this.centralBankLprEnabled = enabled;
        this.applyCentralBankSwitches();
    }

    public boolean isCentralBankLprEnabled() {
        return this.centralBankLprEnabled;
    }

    public void setCentralBankRfPolicyRateEnabled(boolean enabled) {
        this.centralBankRfPolicyRateEnabled = enabled;
    }

    public boolean isCentralBankRfPolicyRateEnabled() {
        return this.centralBankRfPolicyRateEnabled;
    }

    public void setCentralBankRequiredReserveSyncEnabled(boolean enabled) {
        this.centralBankRequiredReserveSyncEnabled = enabled;
        this.applyCentralBankSwitches();
    }

    public boolean isCentralBankRequiredReserveSyncEnabled() {
        return this.cbRrSyncEffective();
    }

    public void setCentralBankFiscalDominanceEnabled(boolean enabled) {
        this.centralBankFiscalDominanceEnabled = enabled;
        this.applyCentralBankSwitches();
    }

    public boolean isCentralBankFiscalDominanceEnabled() {
        return this.centralBankEnabled && this.centralBankFiscalDominanceEnabled;
    }

    public void setCentralBankLastResortEnabled(boolean enabled) {
        this.centralBankLastResortEnabled = enabled;
        this.applyCentralBankSwitches();
    }

    public boolean isCentralBankLastResortEnabled() {
        return this.centralBankLastResortEnabled;
    }

    public void setCentralBankCbdcEnabled(boolean enabled) {
        this.centralBankCbdcEnabled = enabled;
        this.applyCentralBankSwitches();
    }

    public boolean isCentralBankCbdcEnabled() {
        return this.centralBankEnabled && this.centralBankCbdcEnabled;
    }

    public void setCentralBankEventsEnabled(boolean enabled) {
        this.centralBankEventsEnabled = enabled;
        this.applyCentralBankSwitches();
    }

    public boolean isCentralBankEventsEnabled() {
        return this.centralBankEventsEnabled;
    }

    private boolean cbAggEffective() {
        return this.centralBankEnabled && this.centralBankAggregationEnabled;
    }

    private boolean cbBalanceEffective() {
        return this.centralBankEnabled && this.centralBankBalanceSheetEnabled;
    }

    private boolean cbCorridorEffective() {
        return this.centralBankEnabled && this.centralBankCorridorEnabled;
    }

    private boolean cbdcEffective() {
        return this.centralBankEnabled && this.centralBankCbdcEnabled;
    }

    private boolean cbLprEffective() {
        return this.centralBankEnabled && this.centralBankLprEnabled;
    }

    private boolean cbRfEffective() {
        return this.centralBankEnabled && this.centralBankRfPolicyRateEnabled;
    }

    private boolean cbRrSyncEffective() {
        return this.centralBankEnabled && this.centralBankRequiredReserveSyncEnabled;
    }

    private boolean cbEventsEffective() {
        return this.centralBankEnabled && this.centralBankEventsEnabled;
    }

    public double rfForValuation() {
        if (!this.cbRfEffective()) {
            return this.macroEnvironment.getInterestRate();
        }
        double rf = Math.max(this.centralBank.getGovernmentBondYield(), this.centralBank.getPolicyRate());
        return Math.max(0.001, Math.min(0.2, rf));
    }

    public boolean isCentralBankRfEffective() {
        return this.cbRfEffective();
    }

    public int getLastTroubledStateTransitions() {
        return this.lastTroubledStateTransitions;
    }

    public static double policyEventJumpOf(String kind) {
        return CentralBank.policyEventJump(kind);
    }

    public void setDataGuardEnabled(boolean enabled) {
        this.dataGuardEnabled = enabled;
        AICompanyData.setDupontEnabled(this.isDupontEffective());
    }

    public boolean isDataGuardEnabled() {
        return this.dataGuardEnabled;
    }

    public void setDataGuardCageEnabled(boolean enabled) {
        this.dataGuardCageEnabled = enabled;
    }

    public boolean isDataGuardCageEnabled() {
        return this.dataGuardCageEnabled;
    }

    public void setDataGuardAlmgrenChrissEnabled(boolean enabled) {
        this.dataGuardAlmgrenChrissEnabled = enabled;
    }

    public boolean isDataGuardAlmgrenChrissEnabled() {
        return this.dataGuardAlmgrenChrissEnabled;
    }

    public void setDataGuardCoolingEnabled(boolean enabled) {
        this.dataGuardCoolingEnabled = enabled;
    }

    public boolean isDataGuardCoolingEnabled() {
        return this.dataGuardCoolingEnabled;
    }

    public void setDataGuardDupontEnabled(boolean enabled) {
        this.dataGuardDupontEnabled = enabled;
        AICompanyData.setDupontEnabled(this.isDupontEffective());
    }

    public boolean isDataGuardDupontEnabled() {
        return this.dataGuardDupontEnabled;
    }

    private boolean dgCageEffective() {
        return this.dataGuardEnabled && this.dataGuardCageEnabled;
    }

    private boolean dgAcEffective() {
        return this.dataGuardEnabled && this.dataGuardAlmgrenChrissEnabled && this.p2Enabled;
    }

    private boolean dgCoolingEffective() {
        return this.dataGuardEnabled && this.dataGuardCoolingEnabled;
    }

    public boolean isDupontEffective() {
        return this.dataGuardEnabled && this.dataGuardDupontEnabled;
    }

    public void setMesoToMicroBias(boolean enabled) {
        this.mesoToMicroBias = enabled;
        this.wireMesoToMicroBias();
    }

    public boolean isMesoToMicroBias() {
        return this.mesoToMicroBias;
    }

    public MesoLayerManager getMesoLayer() {
        return this.mesoLayer;
    }

    private void wireMesoToMicroBias() {
        if (this.marSEnabled && this.mesoToMicroBias) {
            this.agentManager.setSectorFlowProvider(assetId -> {
                String industry = this.industryOfAsset((String)assetId);
                return industry != null ? this.mesoLayer.getSectorFlow(industry) : 0.0;
            });
        } else {
            this.agentManager.setSectorFlowProvider(null);
        }
    }

    public MacroSnapshot buildMacroSnapshot() {
        double prevM2 = this.inflationSystem.getPrevM2();
        double m2Growth = prevM2 > 0.0 ? (this.inflationSystem.getM2() - prevM2) / prevM2 : 0.0;
        return MacroSnapshot.build(this.centralBank.getDiscountRate(), this.centralBank.getReserveRequirement(), this.government.getFiscalStance(), this.macroEnvironment.getCyclePhase(), this.crisisEngine.getCrisisLevel(), this.creditSystem.getCreditGrowth(), m2Growth, this.inflationSystem.getInflationRate(), this.macroEnvironment.getLiquidityMultiplier(), this.macroEnvironment.getRiskAppetite(), this.macroEnvironment.getDiscountRate(), this.macroEnvironment.getDynamicDrift(), this.macroEnvironment.getDynamicVolatility());
    }

    public String industryOfAsset(String assetId) {
        for (AICompanyData aICompanyData : this.aiCompanyManager.getAll()) {
            if (!aICompanyData.getStockId().equals(assetId)) continue;
            return aICompanyData.getIndustry();
        }
        for (CompanyData companyData : this.companyManager.getAllCompanies()) {
            if (!assetId.equals(companyData.getStockId())) continue;
            return "PLAYER";
        }
        return null;
    }

    public long floatSharesOf(String assetId) {
        if (assetId == null) {
            return 0L;
        }
        for (AICompanyData c : this.aiCompanyManager.getAll()) {
            if (!assetId.equals(c.getStockId())) continue;
            long shares = (long)c.getTotalShares() * 10000L;
            return shares > 0L ? shares : 0L;
        }
        CompanyData co = this.companyManager.getByStockId(assetId);
        if (co != null) {
            int fs = co.getFloatShares();
            return fs > 0 ? (long)fs : 0L;
        }
        return 0L;
    }

    private void placeMesoOrders() {
        for (MesoOrderIntent intent : this.mesoLayer.consumeIntents()) {
            OrderBook book = this.orderBooks.get(intent.assetId());
            if (book == null) continue;
            OrderPipeline.Context pctx = this.buildPipelineContext(intent.assetId(), book);
            Order order = this.orderPipeline.toOrder(new OrderPipeline.OrderIntent(intent.assetId(), intent.isBuy(), intent.quantity(), intent.price(), intent.urgency(), "meso", this.tickCounter), true, pctx);
            if (order != null) {
                book.placeOrder(order);
            }
            this.flushCageClampCount(book, pctx);
        }
    }

    public VolumeTracker getVolumeTracker() {
        return this.volumeTracker;
    }

    public OrderPipeline getOrderPipeline() {
        return this.orderPipeline;
    }

    public boolean isAShareEnabled() {
        return this.aShareEnabled;
    }

    public void setAShareEnabled(boolean enabled) {
        this.aShareEnabled = enabled;
        this.priceEngine.setAShareEnabled(enabled);
    }

    public TradingCalendar getCalendar() {
        return this.calendar;
    }

    public SettlementSystem getSettlement() {
        return this.settlement;
    }

    public boolean isCommodityEnabled() {
        return this.commodityEnabled;
    }

    public void setCommodityEnabled(boolean enabled) {
        this.commodityEnabled = enabled;
    }

    public boolean isFuturesEnabled() {
        return this.futuresEnabled;
    }

    public void setFuturesEnabled(boolean enabled) {
        this.futuresEnabled = enabled;
    }

    public FuturesMarket getFuturesMarket() {
        return this.futuresMarket;
    }

    public CzceFuturesMarket getCzceFutures() {
        return this.czceFutures;
    }

    public CzceOptionDesk getCzceOptions() {
        return this.czceOptions;
    }

    public CrossAssetMacro getCrossAssetMacro() {
        return this.crossAssetMacro;
    }

    public AkGrowthEngine getAkGrowthEngine() {
        return this.akGrowthEngine;
    }

    public String getFuturesMode() {
        return this.futuresMode;
    }

    public boolean isCzceFuturesMode() {
        return !"legacy".equals(this.futuresMode);
    }

    public void setFuturesMode(String mode) {
        this.futuresMode = mode == null ? "czce" : mode.toLowerCase();
    }

    private Map<String, Double> czceSpots() {
        Map<String, Double> spots = new java.util.HashMap<>();
        for (CzceFuturesMarket.ProductSpec spec : this.czceFutures.products().values()) {
            double macroMult = this.crossAssetMacro.futuresSpotMultiplier(spec.code());
            double spot = spec.baseSpot() * macroMult;
            ResourceLocation rl = ResourceLocation.tryParse(spec.itemId());
            if (rl != null) {
                Item item = BuiltInRegistries.ITEM.get(rl);
                CommodityEntry entry = item == null ? null : this.commodityMarket.getCommodity(item);
                if (entry != null && entry.getPrice() > 0) {
                    spot = entry.getPrice() * macroMult;
                }
            }
            spots.put(spec.code(), spot);
        }
        return spots;
    }

    public boolean isTradeBlockEnabled() {
        return this.tradeBlockEnabled;
    }

    public void setTradeBlockEnabled(boolean enabled) {
        this.tradeBlockEnabled = enabled;
    }

    public void registerDeliveryBlock(String contractCode, DeliveryBlockEntity be) {
        if (contractCode != null && !contractCode.isEmpty() && be != null) {
            this.deliveryBlockRegistry.put(contractCode, be);
        }
    }

    public void unregisterDeliveryBlock(String contractCode, DeliveryBlockEntity be) {
        DeliveryBlockEntity cur;
        if (contractCode != null && (cur = this.deliveryBlockRegistry.get(contractCode)) == be) {
            this.deliveryBlockRegistry.remove(contractCode);
        }
    }

    private void runDeliveryBlocks(MarketEngine engine) {
        if (this.deliveryBlockRegistry.isEmpty()) {
            return;
        }
        for (DeliveryBlockEntity be : new ArrayList<DeliveryBlockEntity>(this.deliveryBlockRegistry.values())) {
            try {
                be.tryPhysicalDelivery(engine);
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u4ea4\u5272\u65b9\u5757\u4ea4\u5272\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
        }
    }

    public EconomicCycleEngine getCycleEngine() {
        return this.cycleEngine;
    }

    public void setOrderFlowEnabled(boolean enabled) {
        this.orderFlowEnabled = enabled;
        this.p2Enabled = this.endogenousEnabled && this.orderFlowEnabled;
    }

    public boolean isOrderFlowEnabled() {
        return this.orderFlowEnabled;
    }

    public void setLiqEnabled(boolean enabled) {
        this.liqEnabled = enabled;
        this.agentManager.setEngineLiqGate(enabled);
        if (!enabled) {
            this.agentManager.setLiquidityMakerEnabled(false);
            this.agentManager.setMarketMakerAutoFlow(false);
        }
    }

    public boolean isLiqEnabled() {
        return this.liqEnabled;
    }

    public void setLiqHftEnabled(boolean enabled) {
        this.liqHftEnabled = enabled;
    }

    public boolean isLiqHftEnabled() {
        return this.liqHftEnabled;
    }

    public void setLiqMaxWaitEnabled(boolean enabled) {
        this.liqMaxWaitEnabled = enabled;
    }

    public boolean isLiqMaxWaitEnabled() {
        return this.liqMaxWaitEnabled;
    }

    public void setLiqAuctionEnabled(boolean enabled) {
        this.liqAuctionEnabled = enabled;
    }

    public boolean isLiqAuctionEnabled() {
        return this.liqAuctionEnabled;
    }

    public void setLiqCircuitRehangEnabled(boolean enabled) {
        this.liqCircuitRehangEnabled = enabled;
    }

    public boolean isLiqCircuitRehangEnabled() {
        return this.liqCircuitRehangEnabled;
    }

    public void setCmEnabled(boolean enabled) {
        this.cmEnabled = enabled;
    }

    public boolean isCmEnabled() {
        return this.cmEnabled;
    }

    public void setCmClearingEnabled(boolean enabled) {
        this.cmClearingEnabled = enabled;
    }

    public boolean isCmClearingEnabled() {
        return this.cmClearingEnabled;
    }

    public void setCmCapmEnabled(boolean enabled) {
        this.cmCapmEnabled = enabled;
    }

    public boolean isCmCapmEnabled() {
        return this.cmCapmEnabled;
    }

    public CapmModel getCapmModel() {
        return this.capmModel;
    }

    public void setEndogenousEnabled(boolean enabled) {
        this.endogenousEnabled = enabled;
        this.p2Enabled = this.endogenousEnabled && this.orderFlowEnabled;
        this.applyEndogenousToAgentManager(enabled);
    }

    private void applyEndogenousToAgentManager(boolean enabled) {
        BehaviorParams cur = this.agentManager.getBehaviorParams();
        this.agentManager.setBehaviorParams(new BehaviorParams(enabled, cur.getAnchorWeightMid(), cur.getAnchorWeightFV(), cur.getFundamentalShare(), cur.getChartistShare(), cur.getNoiseShare(), cur.getLiquidityShare()));
    }

    private OrderBook newOrderBook(String assetId) {
        long seed = SeedableRandom.mix64(this.engineSeed ^ (long)assetId.hashCode());
        OrderBook book = new OrderBook(seed);
        book.setMmFillListener((price, signedShares) -> {
            if (this.agentManager != null && signedShares != null && signedShares != 0 && price != null) {
                this.agentManager.onMarketMakerFill(assetId, (int)signedShares, (double)price);
            }
        });
        book.setClearingEnabled(this.cmClearingEffective());
        return book;
    }

    private double midPriceOf(String assetId, OrderBook book) {
        OrderBook.OrderBookSnapshot snap = book.getSnapshot();
        if (!snap.bids.isEmpty() && !snap.asks.isEmpty()) {
            return (snap.bids.get((int)0).price + snap.asks.get((int)0).price) / 2.0;
        }
        AssetSnapshot a = this.state.getAsset(assetId);
        return a != null ? a.getLastPrice() : 0.0;
    }

    private double imbalanceOf(OrderBook book) {
        OrderBook.OrderBookSnapshot snap = book.getSnapshot();
        double buy = 0.0;
        double sell = 0.0;
        for (OrderBook.OrderBookLevel l : snap.bids) {
            buy += (double)l.quantity;
        }
        for (OrderBook.OrderBookLevel l : snap.asks) {
            sell += (double)l.quantity;
        }
        double total = buy + sell;
        if (total <= 0.0) {
            return 0.0;
        }
        return (buy - sell) / total;
    }

    private static double round2(double v) {
        return (double)Math.round(v * 100.0) / 100.0;
    }

    private void seedColdStartBook(String assetId) {
        OrderBook book = this.orderBooks.get(assetId);
        if (book == null) {
            return;
        }
        double lastPrice = this.priceEngine.getLastPrice(assetId);
        if (lastPrice <= 0.0) {
            lastPrice = 1.0;
        }
        long streamSeed = this.engineRng.derive("coldstart_book/" + assetId);
        SeedableRandom rs = new SeedableRandom(streamSeed);
        double tick = 0.01;
        int lot = 100;
        for (int lvl = 1; lvl <= 5; ++lvl) {
            double bidPrice = MarketEngine.round2(lastPrice - tick * (double)lvl);
            double askPrice = MarketEngine.round2(lastPrice + tick * (double)lvl);
            if (bidPrice > 0.0) {
                int qty = Math.max(lot, (500 + rs.nextInt(3501)) / lot * lot);
                Order o = new Order(null, assetId, Order.Type.LIMIT_BUY, qty, bidPrice);
                o.setSynthetic(true);
                book.placeOrder(o);
            }
            int qtyA = Math.max(lot, (500 + rs.nextInt(3501)) / lot * lot);
            Order oa = new Order(null, assetId, Order.Type.LIMIT_SELL, qtyA, askPrice);
            oa.setSynthetic(true);
            book.placeOrder(oa);
        }
        System.out.println("\ud83c\udf31 \u5386\u53f2\u64ad\u79cd: " + assetId + " \u53cc\u4fa7\u4e94\u6863\u57fa\u7840\u6302\u5355(lastPrice=" + lastPrice + ")");
    }

    public void init() {
        AICompanyData company;
        this.macroEnvironment.loadSeedData(0.035, 0.02, 18.0);
        for (AICompanyData c : this.aiCompanyManager.getAll()) {
            this.assetSector.putIfAbsent(c.getStockId(), canonicalSector(c.getIndustry()));
        }
        if (this.state.getAssetIds().isEmpty()) {
            this.aiCompanyManager.registerAllToMarket(this);
            System.out.println("\u2705 \u6ce8\u518c AI \u516c\u53f8\uff0c\u8d44\u4ea7\u6570\u91cf: " + this.state.getAssetIds().size());
        } else {
            System.out.println("\u2139\ufe0f \u52a0\u8f7d\u5b58\u6863\uff0c\u5df2\u6709 " + this.state.getAssetIds().size() + " \u4e2a\u8d44\u4ea7\uff0c\u8df3\u8fc7\u91cd\u65b0\u6ce8\u518c");
        }
        if (this.commodityEnabled) {
            try {
                CommodityRegistry.autoRegister(this.commodityMarket);
                CommodityRegistry.loadFromJson(this.commodityMarket);
                CommodityRegistry.registerFluids(this.commodityMarket);
                System.out.println("\ud83d\uded2 \u5546\u54c1\u5e02\u573a\u6ce8\u518c\u5b8c\u6210: " + this.commodityMarket.size() + " \u79cd\u5546\u54c1\uff08\u7269\u54c1+\u6d41\u4f53\uff09");
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u5546\u54c1\u6ce8\u518c\u5f02\u5e38\uff08\u4e0d\u963b\u65ad init\uff09: " + e.getMessage());
            }
        }
        if (this.futuresEnabled && !this.isCzceFuturesMode()) {
            try {
                this.futuresMarket.registerUnderlyings(this);
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u671f\u8d27\u54c1\u79cd\u6ce8\u518c\u5f02\u5e38\uff08\u4e0d\u963b\u65ad init\uff09: " + e.getMessage());
            }
        }
        for (String id : this.state.getAssetIds()) {
            company = this.aiCompanyManager.getAll().stream().filter(c -> c.getStockId().equals(id)).findFirst().orElse(null);
            if (company != null) {
                double fairPrice = company.getIpoPrice();
                this.priceEngine.seedPrice(id, fairPrice);
                this.state.getAsset(id).setLastPrice(fairPrice);
            }
            this.orderBooks.putIfAbsent(id, this.newOrderBook(id));
            this.tradeHistory.putIfAbsent(id, new ArrayList());
            this.dailyTrades.putIfAbsent(id, new ArrayList());
            this.circuitBreakerTripped.put(id, false);
            this.circuitBreakerRecoveryTime.put(id, 0L);
            this.dgCooling.putIfAbsent(id, new DgCoolingState());
            this.hasTodayTrade.put(id, false);
            this.pendingSettlements.put(id, new ArrayList());
            this.seedColdStartBook(id);
        }
        this.excellent24Index.rebalance(this.state, this.aiCompanyManager);
        for (String id : this.state.getAssetIds()) {
            this.agentManager.initializeAgents(id, 20 + this.engineRng.nextInt(20));
            company = this.aiCompanyManager.getAll().stream().filter(c -> c.getStockId().equals(id)).findFirst().orElse(null);
            if (company == null) continue;
            this.agentManager.updateCompanyData(id, company);
        }
        this.mesoLayer.initialize(this.engineSeed);
        this.recomputeCapmFair();
        System.out.println("\u2705 MarketEngine \u521d\u59cb\u5316\u5b8c\u6210\uff0c\u8d44\u4ea7\u6570: " + this.state.getAssetIds().size());
    }

    /** M4: world-macro sector momentum consumed by chartist agents (bounded, no price write). */
    private double sectorFlow(String assetId) {
        if (assetId == null) {
            return 0.0;
        }
        String sector = this.assetSector.getOrDefault(assetId, "other");
        WorldMacroSnapshot snap = this.worldMacro.snapshot();
        var s = snap.sectors().get(sector);
        if (s == null) {
            return 0.0;
        }
        return s.output() * MacroCalibration.sectorFlowOutput
                + (s.margin() - 0.2) * MacroCalibration.sectorFlowMargin
                - s.risk() * MacroCalibration.sectorFlowRisk;
    }

    private static String canonicalSector(String industry) {
        if (industry == null || industry.isBlank()) {
            return "other";
        }
        String s = industry.toLowerCase();
        if (s.contains("银行") || s.contains("金融") || s.contains("保险")
                || s.contains("finance") || s.contains("bank")) {
            return "finance";
        }
        if (s.contains("能源") || s.contains("电力") || s.contains("煤") || s.contains("石油")
                || s.contains("energy") || s.contains("power")) {
            return "energy";
        }
        if (s.contains("食品") || s.contains("农业") || s.contains("food")
                || s.contains("farm")) {
            return "food";
        }
        if (s.contains("材料") || s.contains("钢铁") || s.contains("化工") || s.contains("矿")
                || s.contains("material") || s.contains("steel") || s.contains("chemical")) {
            return "materials";
        }
        if (s.contains("汽车") || s.contains("机械") || s.contains("制造") || s.contains("工业")
                || s.contains("tool") || s.contains("machine") || s.contains("industrial")) {
            return "tools";
        }
        if (s.contains("魔法") || s.contains("arcane") || s.contains("magic")) {
            return "arcane";
        }
        return "services";
    }

    public void registerAICompany(AICompanyData company) {
        String id = company.getStockId();
        this.assetSector.put(id, canonicalSector(company.getIndustry()));        double ipoPrice = company.getIpoPrice();
        double volatility = 0.12 + this.engineRng.nextDouble() * 0.18;
        double drift = (this.engineRng.nextDouble() - 0.5) * 0.02;
        this.priceEngine.addAsset(id, ipoPrice, volatility, drift, company.getIndustry(), 256);
        AssetSnapshot snap = new AssetSnapshot(id, ipoPrice, company.getNetAssetValue());
        this.state.addAsset(id, snap);
        this.orderBooks.put(id, this.newOrderBook(id));
        this.tradeHistory.put(id, new ArrayList());
        this.dailyTrades.put(id, new ArrayList());
        this.circuitBreakerTripped.put(id, false);
        this.circuitBreakerRecoveryTime.put(id, 0L);
        this.hasTodayTrade.put(id, false);
        this.pendingSettlements.put(id, new ArrayList());
        AssetSnapshot snap2 = this.state.getAsset(id);
        if (snap2 != null) {
            snap2.setPrevClose(ipoPrice);
        }
        this.agentManager.initializeAgents(id, 20 + this.engineRng.nextInt(20));
        this.agentManager.updateCompanyData(id, company);
    }

    public void registerPlayerCompany(CompanyData company) {
        String id = company.getStockId();
        this.assetSector.put(id, "materials");
        double ipoPrice = company.getIpoPrice();
        double volatility = 0.12 + this.engineRng.nextDouble() * 0.18;
        double drift = (this.engineRng.nextDouble() - 0.5) * 0.02;
        this.priceEngine.addAsset(id, ipoPrice, volatility, drift, "PLAYER", 256);
        AssetSnapshot snap = new AssetSnapshot(id, ipoPrice, company.getNetAssetValue());
        this.state.addAsset(id, snap);
        this.orderBooks.put(id, this.newOrderBook(id));
        this.tradeHistory.put(id, new ArrayList());
        this.dailyTrades.put(id, new ArrayList());
        this.circuitBreakerTripped.put(id, false);
        this.circuitBreakerRecoveryTime.put(id, 0L);
        this.hasTodayTrade.put(id, false);
        this.pendingSettlements.put(id, new ArrayList());
        this.agentManager.initializeAgents(id, 20 + this.engineRng.nextInt(20));
        AssetSnapshot a = this.state.getAsset(id);
        if (a != null) {
            a.setPrevClose(ipoPrice);
        }
    }

    private int computeFloatShares(int totalShares) {
        int lot = 100;
        int fs = (int)((double)totalShares * 0.4);
        if ((fs = fs / lot * lot) < lot && totalShares >= lot) {
            fs = lot;
        }
        return fs;
    }

    public String startIpo(CompanyData company) {
        String reason = ListingValidator.rejectionReason(company);
        if (reason != null) {
            return reason;
        }
        company.setIpoPrice(company.computeIpoPrice());
        company.setFloatShares(this.computeFloatShares(company.getTotalShares()));
        String stockId = this.companyManager.nextStockId();
        this.companyManager.registerStockId(company, stockId);
        company.setIpoState(CompanyIpoState.SUBSCRIBING);
        company.setSubscriptionRaised(0.0);
        company.getSubscriptions().clear();
        company.setIpoWindowStartDay(this.currentDay);
        return null;
    }

    public String subscribeIpo(ServerPlayer player, CompanyData company, int shares) {
        if (company.getIpoState() != CompanyIpoState.SUBSCRIBING) {
            return "\u5f53\u524d\u4e0d\u5728\u8ba4\u8d2d\u7a97\u53e3";
        }
        if (company.getStatus() != CompanyStatus.PRIVATE) {
            return "\u516c\u53f8\u72b6\u6001\u4e0d\u5141\u8bb8\u8ba4\u8d2d";
        }
        int lot = 100;
        if (shares <= 0 || shares % lot != 0) {
            return "\u8ba4\u8d2d\u6570\u91cf\u987b\u4e3a " + lot + " \u7684\u6574\u6570\u500d";
        }
        int already = company.getSubscriptions().getOrDefault(player.getUUID(), 0);
        if (already + shares > company.getFloatShares()) {
            return "\u7d2f\u8ba1\u8ba4\u8d2d\u8d85\u8fc7\u53d1\u884c\u91cf " + company.getFloatShares();
        }
        double cost = (double)shares * company.getIpoPrice();
        PlayerEconomy eco = PlayerEconomy.get((Player)player);
        if (eco == null) {
            return "\u73a9\u5bb6\u7ecf\u6d4e\u8d26\u6237\u4e0d\u53ef\u7528";
        }
        if (eco.getCash() < cost) {
            return "\u73b0\u91d1\u4e0d\u8db3";
        }
        eco.setCash(eco.getCash() - cost);
        company.getSubscriptions().merge(player.getUUID(), shares, Integer::sum);
        company.setSubscriptionRaised(company.getSubscriptionRaised() + cost);
        return null;
    }

    public void advanceIpo(ServerLevel level) {
        if (level == null) {
            return;
        }
        for (CompanyData company : this.companyManager.getAllCompanies()) {
            if (company.getStatus() != CompanyStatus.PRIVATE || company.getIpoState() != CompanyIpoState.SUBSCRIBING || this.currentDay - company.getIpoWindowStartDay() < 3L) continue;
            this.settleIpoWindow(level, company);
        }
    }

    /*
     * WARNING - void declaration
     */
    private void settleIpoWindow(ServerLevel level, CompanyData company) {
        int floatShares = company.getFloatShares();
        int lot = 100;
        int totalSubscribed = 0;
        Iterator<Integer> iterator = company.getSubscriptions().values().iterator();
        while (iterator.hasNext()) {
            int n = iterator.next();
            totalSubscribed += n;
        }
        if (totalSubscribed >= floatShares) {
            int j;
            ArrayList<UUID> sorted = new ArrayList<UUID>(company.getSubscriptions().keySet());
            sorted.sort(Comparator.comparing(UUID::toString));
            int n = floatShares;
            LinkedHashMap<UUID, Integer> allocated = new LinkedHashMap<UUID, Integer>();
            for (UUID pid : sorted) {
                int sub = company.getSubscriptions().get(pid);
                int alloc = Math.min(sub, n);
                n -= alloc;
                if (alloc > 0) {
                    allocated.put(pid, alloc);
                }
                if (alloc < sub) {
                    double refund = (double)(sub - alloc) * company.getIpoPrice();
                    this.creditCash(level, pid, refund);
                }
                if (n > 0) continue;
                break;
            }
            UUID owner = company.getOwner();
            LinkedHashMap<UUID, Integer> existing = new LinkedHashMap<UUID, Integer>();
            for (Map.Entry<UUID, Integer> e : company.getShareholders().entrySet()) {
                existing.put(e.getKey(), e.getValue());
            }
            if (existing.isEmpty() && owner != null) {
                existing.put(owner, company.getTotalShares());
            }
            long totalExisting = 0L;
            Iterator refund = existing.values().iterator();
            while (refund.hasNext()) {
                int v = (Integer)refund.next();
                totalExisting += (long)v;
            }
            if (totalExisting <= 0L) {
                totalExisting = company.getTotalShares();
            }
            int toFloat = floatShares;
            int n2 = existing.size();
            UUID[] ids = new UUID[n2];
            double[] exact = new double[n2];
            int[] giveUp = new int[n2];
            long sumGiveUp = 0L;
            int idx = 0;
            for (Map.Entry e : existing.entrySet()) {
                ids[idx] = (UUID)e.getKey();
                exact[idx] = (double)((Integer)e.getValue()).intValue() * (double)toFloat / (double)totalExisting;
                giveUp[idx] = (int)exact[idx];
                sumGiveUp += (long)giveUp[idx];
                ++idx;
            }
            ArrayList<Integer> order = new ArrayList<Integer>();
            for (int j2 = 0; j2 < n2; ++j2) {
                order.add(j2);
            }
            order.sort((a, b) -> {
                int c = Double.compare(exact[b] - (double)giveUp[b], exact[a] - (double)giveUp[a]);
                if (c != 0) {
                    return c;
                }
                return ids[a].toString().compareTo(ids[b].toString());
            });
            int deficit = toFloat - (int)sumGiveUp;
            for (j = 0; j < order.size() && deficit > 0; --deficit, ++j) {
                int n3 = (Integer)order.get(j);
                giveUp[n3] = giveUp[n3] + 1;
            }
            company.getShareholders().clear();
            for (j = 0; j < n2; ++j) {
                int heldAfter = (Integer)existing.get(ids[j]) - giveUp[j];
                if (heldAfter <= 0) continue;
                company.getShareholders().put(ids[j], heldAfter);
            }
            for (Map.Entry e : allocated.entrySet()) {
                if ((Integer)e.getValue() <= 0) continue;
                company.getShareholders().merge((UUID)e.getKey(), (Integer)e.getValue(), Integer::sum);
                this.creditHolding(level, (UUID)e.getKey(), company.getStockId(), (Integer)e.getValue(), company.getIpoPrice());
            }
            company.setRetainedEarnings(company.getRetainedEarnings() + company.getSubscriptionRaised());
            company.setSubscriptionRaised(0.0);
            company.getSubscriptions().clear();
            this.registerPlayerCompany(company);
            company.setStatus(CompanyStatus.LISTED);
            company.setIpoState(CompanyIpoState.LISTED);
            company.setListedTime(this.tickCounter);
            System.out.println("\ud83d\udcc8 \u516c\u53f8 " + company.getName() + " IPO \u6210\u529f\u4e0a\u5e02\uff0c\u4ee3\u7801 " + company.getStockId());
        } else {
            for (Map.Entry entry : company.getSubscriptions().entrySet()) {
                double refund = (double)((Integer)entry.getValue()).intValue() * company.getIpoPrice();
                this.creditCash(level, (UUID)entry.getKey(), refund);
            }
            this.companyManager.unregisterStockId(company);
            company.setStockId(null);
            company.clearIpoWindow();
            company.setIpoState(CompanyIpoState.FAILED);
            company.setStatus(CompanyStatus.PRIVATE);
            System.out.println("\u26a0\ufe0f \u516c\u53f8 " + company.getName() + " IPO \u8ba4\u8d2d\u4e0d\u8db3\uff0c\u53d1\u884c\u4e2d\u6b62");
        }
    }

    private ServerPlayer findPlayer(ServerLevel level, UUID pid) {
        if (level == null || pid == null) {
            return null;
        }
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (!p.getUUID().equals(pid)) continue;
            return p;
        }
        return null;
    }

    private void creditCash(ServerLevel level, UUID pid, double amount) {
        if (pid == null || amount == 0.0) {
            return;
        }
        ServerPlayer p = this.findPlayer(level, pid);
        if (p != null) {
            PlayerEconomy eco = PlayerEconomy.get((Player)p);
            if (eco != null) {
                eco.addCash(amount);
            }
        } else {
            this.pendingEconomy.computeIfAbsent(pid, k -> new PendingEconomyChange()).cash += amount;
        }
    }

    private void creditHolding(ServerLevel level, UUID pid, String stockId, int qty, double price) {
        if (pid == null || stockId == null || qty <= 0) {
            return;
        }
        ServerPlayer p = this.findPlayer(level, pid);
        if (p != null) {
            PlayerEconomy eco = PlayerEconomy.get((Player)p);
            if (eco != null) {
                eco.addHolding(stockId, qty, price);
            }
            if (this.settlement != null) {
                this.settlement.seedAvailable(pid, stockId, qty);
            }
        } else {
            PendingEconomyChange pc = this.pendingEconomy.computeIfAbsent(pid, k -> new PendingEconomyChange());
            pc.addHolding.merge(stockId, qty, Integer::sum);
            pc.addPrice.merge(stockId, price, Double::max);
        }
    }

    private void debitHolding(ServerLevel level, UUID pid, String stockId, int qty) {
        if (pid == null || stockId == null || qty <= 0) {
            return;
        }
        ServerPlayer p = this.findPlayer(level, pid);
        if (p != null) {
            PlayerEconomy eco = PlayerEconomy.get((Player)p);
            if (eco != null) {
                eco.removeHolding(stockId, qty);
            }
            if (this.settlement != null) {
                this.settlement.onSellFill(pid, stockId, qty);
            }
        } else {
            this.pendingEconomy.computeIfAbsent(pid, k -> new PendingEconomyChange()).removeHolding.merge(stockId, qty, Integer::sum);
        }
    }

    private void applyPendingEconomy(ServerLevel level) {
        PlayerEconomy eco;
        UUID pid;
        if (level == null || this.pendingEconomy.isEmpty()) {
            return;
        }
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        for (ServerPlayer p : players) {
            pid = p.getUUID();
            PendingEconomyChange pc = this.pendingEconomy.get(pid);
            if (pc == null || pc.isEmpty() || (eco = PlayerEconomy.get((Player)p)) == null) continue;
            if (pc.cash != 0.0) {
                eco.addCash(pc.cash);
            }
            for (Map.Entry<String, Integer> e : pc.addHolding.entrySet()) {
                eco.addHolding(e.getKey(), e.getValue(), pc.addPrice.getOrDefault(e.getKey(), 0.0));
                if (this.settlement == null) continue;
                this.settlement.seedAvailable(pid, e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Integer> e : pc.removeHolding.entrySet()) {
                eco.removeHolding(e.getKey(), e.getValue());
                if (this.settlement == null) continue;
                this.settlement.onSellFill(pid, e.getKey(), e.getValue());
            }
            this.pendingEconomy.remove(pid);
        }
        for (ServerPlayer p : players) {
            pid = p.getUUID();
            PendingTradeSettlement pts = this.pendingTradeSettlement.get(pid);
            if (pts == null || pts.isEmpty() || (eco = PlayerEconomy.get((Player)p)) == null) continue;
            if (pts.cash != 0.0) {
                eco.addCash(pts.cash);
            }
            for (Map.Entry<String, Integer> e : pts.addHolding.entrySet()) {
                eco.addHolding(e.getKey(), e.getValue(), pts.addPrice.getOrDefault(e.getKey(), 0.0));
            }
            for (Map.Entry<String, Integer> e : pts.removeHolding.entrySet()) {
                eco.removeHolding(e.getKey(), e.getValue());
            }
            this.pendingTradeSettlement.remove(pid);
        }
    }

    public String transferShares(CompanyData company, UUID from, UUID to, int qty, ServerLevel level) {
        if (company == null) {
            return "\u516c\u53f8\u4e0d\u5b58\u5728";
        }
        if (from == null || to == null) {
            return "\u8f6c\u8ba9\u4eba/\u63a5\u6536\u4eba\u65e0\u6548";
        }
        if (qty <= 0) {
            return "\u8f6c\u8ba9\u6570\u91cf\u987b\u4e3a\u6b63";
        }
        int held = company.getShareholders().getOrDefault(from, 0);
        if (held < qty) {
            return "\u8f6c\u8ba9\u6570\u91cf\u8d85\u8fc7\u6301\u6709\u91cf";
        }
        int lot = 100;
        if (company.getStatus() == CompanyStatus.LISTED && qty % lot != 0) {
            return "\u5df2\u4e0a\u5e02\u516c\u53f8\u8f6c\u8ba9\u987b\u4e3a " + lot + " \u7684\u6574\u6570\u500d";
        }
        int fromLeft = held - qty;
        if (fromLeft <= 0) {
            company.getShareholders().remove(from);
        } else {
            company.getShareholders().put(from, fromLeft);
        }
        company.getShareholders().merge(to, qty, Integer::sum);
        if (company.getStatus() == CompanyStatus.LISTED) {
            double price = this.lastPriceOf(company.getStockId(), company.getIpoPrice());
            this.debitHolding(level, from, company.getStockId(), qty);
            this.creditHolding(level, to, company.getStockId(), qty, price);
        }
        return null;
    }

    private double lastPriceOf(String assetId, double fallback) {
        AssetSnapshot snap = this.state.getAsset(assetId);
        return snap != null && snap.getLastPrice() > 0.0 ? snap.getLastPrice() : fallback;
    }

    public void generateFinancialReport(CompanyData company) {
        if (company == null) {
            return;
        }
        long now = this.tickCounter;
        double nav = company.getNetAssetValue();
        double profit = company.getRecentProfit();
        double retained = company.getRetainedEarnings();
        int totalShares = company.getTotalShares();
        double eps = totalShares > 0 ? profit / (double)totalShares : 0.0;
        double roe = nav > 0.0 ? profit / nav : 0.0;
        double marketCap = 0.0;
        if (company.getStatus() == CompanyStatus.LISTED && company.getStockId() != null) {
            marketCap = this.lastPriceOf(company.getStockId(), company.getIpoPrice()) * (double)totalShares;
        }
        company.addReport(new CompanyData.CompanyFinancialReport(now, MarketEngine.round2(nav), MarketEngine.round2(profit), MarketEngine.round2(retained), totalShares, MarketEngine.round2(eps), MarketEngine.round2(roe), MarketEngine.round2(company.getIpoPrice()), MarketEngine.round2(marketCap)));
        company.setLastReportTime(now);
    }

    public void payDividend(CompanyData company, ServerLevel level) {
        if (company == null || company.getStatus() == CompanyStatus.DELISTED) {
            return;
        }
        double retained = company.getRetainedEarnings();
        if (retained <= 0.0) {
            return;
        }
        int totalShares = company.getTotalShares();
        if (totalShares <= 0) {
            return;
        }
        double payout = retained * 0.5;
        double perShare = Math.floor(payout / (double)totalShares * 100.0) / 100.0;
        if (perShare <= 0.0) {
            return;
        }
        double residual = payout - perShare * (double)totalShares;
        UUID owner = company.getOwner();
        double totalPaid = 0.0;
        for (Map.Entry<UUID, Integer> e : this.companyManager.getShareholdersOf(company)) {
            double amount = MarketEngine.round2(perShare * (double)e.getValue().intValue() + (e.getKey().equals(owner) ? residual : 0.0));
            this.creditCash(level, e.getKey(), amount);
            totalPaid += amount;
        }
        if (totalPaid <= 0.0) {
            return;
        }
        company.setRetainedEarnings(retained - totalPaid);
        company.setDividendPaidTotal(company.getDividendPaidTotal() + totalPaid);
        company.setLastDividendTime(this.tickCounter);
    }

    public void delistBuyout(CompanyData company, ServerLevel level, double buyoutPrice) {
        if (company == null) {
            return;
        }
        String stockId = company.getStockId();
        for (Map.Entry<UUID, Integer> e : this.companyManager.getShareholdersOf(company)) {
            UUID pid = e.getKey();
            int qty = e.getValue();
            double amount = MarketEngine.round2(buyoutPrice * (double)qty);
            this.creditCash(level, pid, amount);
            if (stockId == null) continue;
            this.debitHolding(level, pid, stockId, qty);
        }
        company.getShareholders().clear();
        if (stockId != null) {
            this.settlement.clearAsset(stockId);
        }
    }

    public void removeAsset(String stockId) {
        if (stockId == null) {
            return;
        }
        if (this.aiCompanyManager.hasStock(stockId)) {
            return;
        }
        this.state.getAssetIds().remove(stockId);
        this.orderBooks.remove(stockId);
        this.tradeHistory.remove(stockId);
        this.dailyTrades.remove(stockId);
        this.todayOpen.remove(stockId);
        this.todayClose.remove(stockId);
        this.hasTodayTrade.remove(stockId);
        this.circuitBreakerTripped.remove(stockId);
        this.circuitBreakerRecoveryTime.remove(stockId);
        this.pendingSettlements.remove(stockId);
    }

    private void updateDailySnapshot(String assetId, double tradePrice, long volume) {
        AssetSnapshot snap = this.state.getAsset(assetId);
        if (snap == null) {
            return;
        }
        double price = tradePrice;
        snap.setLastPrice(price);
        if (snap.getOpen() <= 0.0) {
            snap.setOpen(price);
            snap.setHigh(price);
            snap.setLow(price);
        } else {
            if (price > snap.getHigh()) {
                snap.setHigh(price);
            }
            if (price < snap.getLow()) {
                snap.setLow(price);
            }
        }
        snap.setVolume(snap.getVolume() + Math.max(0L, volume));
        double prevClose = snap.getPrevClose();
        if (prevClose > 0.0) {
            snap.setDailyChange((price - prevClose) / prevClose * 100.0);
        } else {
            snap.setDailyChange(0.0);
        }
    }

    private void resetDailySnapshot(String assetId) {
        AssetSnapshot snap = this.state.getAsset(assetId);
        if (snap == null) {
            return;
        }
        snap.setOpen(0.0);
        snap.setHigh(0.0);
        snap.setLow(0.0);
        snap.setDailyChange(0.0);
        snap.setVolume(0L);
    }

    private void sampleCapmDailyCloses() {
        double indexClose = this.excellent24Index.getIndexValue();
        if (!Double.isFinite(indexClose) || indexClose <= 0.0) {
            return;
        }
        for (String id : this.state.getAssetIds()) {
            double close;
            AssetSnapshot snap = this.state.getAsset(id);
            if (snap == null || !Double.isFinite(close = snap.getLastPrice()) || close <= 0.0) continue;
            this.capmModel.recordDailyClose(id, indexClose, close);
        }
    }

    private void recomputeCapmFair() {
        if (!this.cmCapmEffective()) {
            return;
        }
        double rf = this.rfForValuation();
        double erp = CapmModel.marketRiskPremium(this.macroEnvironment.getCyclePhase(), this.macroEnvironment.getCrisisTension(), this.centralBank.getErpFiscalDominancePremium());
        for (AICompanyData c : this.aiCompanyManager.getAll()) {
            double ke = this.capmModel.costOfEquity(c.getStockId(), c.getIndustry(), rf, erp);
            c.updateFairValue(ke);
        }
    }

    private void applyMeanFairValueDelta() {
        List<AICompanyData> all = this.aiCompanyManager.getAll();
        if (all == null || all.isEmpty()) {
            return;
        }
        double sum = 0.0;
        for (AICompanyData c : all) {
            sum += c.getFairValue();
        }
        double mean = sum / (double)all.size();
        this.centralBank.setMeanFairValueDelta(mean - this.lastMeanFairValue);
        this.lastMeanFairValue = mean;
    }

    public void tick(ServerLevel level) {
        boolean tradingOpen;
        RecipeManager rm;
        ++this.tickCounter;
        long gameTime = level.getGameTime();
        this.currentLevel = level;
        this.applyPendingEconomy(level);
        this.fundNewPlayers(level);
        this.feedSuperstructureTick(level);
        this.agentManager.updatePhase(Math.toIntExact(this.tickCounter));
        this.calendar.advance(this.tickCounter);
        if (this.aShareEnabled) {
            if (this.calendar.isSessionEnd()) {
                for (String id : this.state.getAssetIds()) {
                    AssetSnapshot snap = this.state.getAsset(id);
                    if (snap == null || !(snap.getLastPrice() > 0.0)) continue;
                    snap.setPrevClose(snap.getLastPrice());
                }
                if (this.cmCapmEffective()) {
                    boolean prevOpen;
                    long prevPeriod = this.calendar.getPeriod() - 1L;
                    boolean bl = prevOpen = prevPeriod % 7L < 5L;
                    if (prevOpen) {
                        this.sampleCapmDailyCloses();
                    }
                }
                if (this.marginEnabled) {
                    try {
                        this.marginManager.settleDaily(this, this.calendar);
                    }
                    catch (RuntimeException e) {
                        System.out.println("\u26a0\ufe0f \u4fdd\u8bc1\u91d1\u9010\u65e5\u7ed3\u7b97\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
                    }
                }
            }
            if (this.calendar.isTradingDayStart()) {
                try {
                    long day = this.calendar.getTradingDayNumber();
                    this.techStorageSampler.sample(this.worldMacro, this, level, day);
                    this.worldStateCollector.sample(this.worldMacro, this, level, day);
                    MacroTransmission.transmit(this.worldMacro, this);
                } catch (RuntimeException e) {
                    System.out.println("\u26a0\ufe0f \u4e16\u754c\u5b8f\u89c2\u4f20\u5bfc\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
                }
                this.performTPlus1Settlement();
                this.currentDay = this.calendar.getTradingDayNumber();
                this.volumeTracker.onTradingDayStart();
                for (String id : this.state.getAssetIds()) {
                    OrderBook _bkF5;
                    OrderBook bb;
                    this.dailyTrades.get(id).clear();
                    this.hasTodayTrade.put(id, false);
                    this.circuitBreakerTripped.put(id, false);
                    this.circuitBreakerRecoveryTime.put(id, gameTime + 6000L);
                    this.lastFillTick.put(id, this.tickCounter);
                    this.agentManager.resetMarketMaker(id);
                    if (this.liqEnabled && (bb = this.orderBooks.get(id)) != null) {
                        double anchor;
                        AssetSnapshot as = this.state.getAsset(id);
                        double d = anchor = this.aShareEnabled && as != null ? as.getPrevClose() : 0.0;
                        if (anchor <= 0.0 && as != null) {
                            anchor = as.getLastPrice();
                        }
                        bb.resetContinuityAnchor(anchor);
                    }
                    if (!this.liqEnabled || (_bkF5 = this.orderBooks.get(id)) == null) continue;
                    _bkF5.cancelAllSynthetic();
                }
                for (String id : this.state.getAssetIds()) {
                    this.todayOpen.put(id, 0.0);
                    this.todayClose.put(id, 0.0);
                    this.resetDailySnapshot(id);
                }
                System.out.println("\ud83d\udcc6 \u65b0\u4ea4\u6613\u65e5: " + this.currentDay + "\uff08A \u80a1\u65e5\u5386\uff0c\u671f " + this.calendar.getPeriod() + "\uff09");
                this.runDailyFiat(level);
                this.advanceIpo(level);
            }
        } else {
            long newDay = gameTime / 24000L;
            if (newDay > this.currentDay) {
                try {
                    this.techStorageSampler.sample(this.worldMacro, this, level, newDay);
                    this.worldStateCollector.sample(this.worldMacro, this, level, newDay);
                    MacroTransmission.transmit(this.worldMacro, this);
                } catch (RuntimeException e) {
                    System.out.println("\u26a0\ufe0f \u4e16\u754c\u5b8f\u89c2\u4f20\u5bfc\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
                }
                this.performTPlus1Settlement();
                this.currentDay = newDay;
                if (this.cmCapmEffective()) {
                    this.sampleCapmDailyCloses();
                }
                this.volumeTracker.onTradingDayStart();
                for (String id : this.state.getAssetIds()) {
                    OrderBook _bkF5;
                    OrderBook bb;
                    this.dailyTrades.get(id).clear();
                    this.hasTodayTrade.put(id, false);
                    this.circuitBreakerTripped.put(id, false);
                    this.circuitBreakerRecoveryTime.put(id, gameTime + 6000L);
                    this.lastFillTick.put(id, this.tickCounter);
                    this.agentManager.resetMarketMaker(id);
                    if (this.liqEnabled && (bb = this.orderBooks.get(id)) != null) {
                        double anchor;
                        AssetSnapshot as = this.state.getAsset(id);
                        double d = anchor = this.aShareEnabled && as != null ? as.getPrevClose() : 0.0;
                        if (anchor <= 0.0 && as != null) {
                            anchor = as.getLastPrice();
                        }
                        bb.resetContinuityAnchor(anchor);
                    }
                    if (!this.liqEnabled || (_bkF5 = this.orderBooks.get(id)) == null) continue;
                    _bkF5.cancelAllSynthetic();
                }
                for (String id : this.state.getAssetIds()) {
                    this.todayOpen.put(id, 0.0);
                    this.todayClose.put(id, 0.0);
                    this.resetDailySnapshot(id);
                }
                System.out.println("\ud83d\udcc6 \u65b0\u4ea4\u6613\u65e5: " + this.currentDay);
                this.runDailyFiat(level);
                this.advanceIpo(level);
            }
        }
        if (!this.graphInitialized && (rm = level.getServer().getRecipeManager()) != null) {
            RecipeAnalyzer.buildGraph(rm, level.registryAccess());
            this.graphInitialized = true;
            try {
                ProfileCompiler.CompileStats stats = new ProfileCompiler(this.worldMacro.registry(),
                        this.worldMacro.recipeGraph()).compile(level.registryAccess(), rm);
                EconomyProfileLoader.Stats overrides = EconomyProfileLoader.load(
                        this.worldMacro.registry(), this.worldMacro.recipeGraph());
                System.out.println("\ud83c\udf0d \u4e16\u754c\u5b8f\u89c2\u753b\u50cf: items=" + stats.items()
                        + " tagged=" + stats.taggedItems() + " recipes=" + stats.recipes()
                        + " edges=" + stats.edges() + " skipped=" + stats.skipped()
                        + " overrides=" + overrides.items() + "/" + overrides.processes()
                        + " in " + stats.millis() + "ms");
            } catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u4e16\u754c\u5b8f\u89c2\u753b\u50cf\u5f02\u5e38\uff08\u964d\u7ea7\u4e3a\u901a\u7528\u4e8b\u4ef6\uff09: " + e.getMessage());
            }
            if (this.commodityEnabled) {
                try {
                    CommodityRegistry.refreshBasePrices(this.commodityMarket);
                }
                catch (RuntimeException e) {
                    System.out.println("\u26a0\ufe0f \u5546\u54c1\u57fa\u4ef7\u5237\u65b0\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
                }
            }
        }
        if (this.commodityEnabled) {
            try {
                this.commodityMarket.tick(level, this, this.calendar);
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u5546\u54c1\u5e02\u573a tick \u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
        }
        if (this.goldEnabled && this.calendar.isTradingDayStart()) {
            try {
                this.goldSystem.tick(this);
                this.centralBank.resetGoldMarketFlow();
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u9ec4\u91d1\u4f53\u7cfb tick \u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
        }
        if (this.futuresEnabled && this.tradeBlockEnabled) {
            try {
                this.runDeliveryBlocks(this);
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u4ea4\u5272\u65b9\u5757\u4ea4\u5272\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
        }
        if (this.futuresEnabled && !this.isCzceFuturesMode()) {
            try {
                this.futuresMarket.tick(level, this, this.calendar);
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u671f\u8d27\u5e02\u573a tick \u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
        }
        if (this.leverageEnabled) {
            try {
                this.leverageMarket.tick(level, this, this.calendar);
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u6760\u6746\u7968\u636e\u5e02\u573a tick \u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
        }
        this.macroEnvironment.setPolicyRate(this.centralBank.getDiscountRate());
        this.macroEnvironment.setReserveRequirement(this.centralBank.getReserveRequirement());
        this.priceEngine.step();
        if (gameTime % 20L == 0L) {
            this.macroEngine.step(this.state, this.dataCollector);
            try {
                this.worldMacro.step(this.tickCounter, this.currentDay);
            } catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u4e16\u754c\u5b8f\u89c2\u805a\u5408\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
            try {
                WorldMacroSnapshot crossWorld = this.worldMacro.snapshot();
                double stockIndex = this.getIndexValueEx();
                double commodityProxy = crossWorld == null ? 0.0 : (crossWorld.ppi() - 1.0) * 0.5;
                this.crossAssetMacro.setLeverageStress(
                        Math.min(1.0, Math.max(0.0, this.macroEnvironment.getCrisisTension())));
                this.crossAssetMacro.step(this.tickCounter, this.currentDay, crossWorld,
                        this.macroEnvironment, stockIndex, commodityProxy,
                        this.macroEnvironment.getCrisisTension());
                for (AICompanyData ai : this.aiCompanyManager.getAll()) {
                    this.priceEngine.setCrossAssetTilt(ai.getStockId(),
                            this.crossAssetMacro.stockTilt(ai.getIndustry()));
                }
                this.czceOptions.setMacroStress(this.crossAssetMacro.optionVolMultiplier(),
                        this.crossAssetMacro.optionSkewShift());
            } catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u8de8\u8d44\u4ea7\u5b8f\u89c2\u4f20\u5bfc\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
            if (this.futuresEnabled && this.isCzceFuturesMode()) {
                try {
                    this.czceFutures.tick(this.tickCounter, this.macroEnvironment.getInterestRate(),
                            this.czceSpots());
                    this.czceOptions.tick(this.tickCounter);
                } catch (RuntimeException e) {
                    System.out.println("\u26a0\ufe0f CZCE \u671f\u8d27 tick \u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
                }
            }
            this.cycleEngine.tick(gameTime, this.creditSystem, this.commodityMarket);
            this.fundEngine.step(this.state, this.dataCollector, this.macroEngine, this.companyManager, this.commodityMarket, this.aiCompanyManager);
            double nplRatio = this.creditSystem.getNplRatio();
            double economicHealth = this.cycleEngine.getEconomicHealth();
            this.sentiment.updateGlobalSentiment(this.commodityMarket, this.inflationSystem, this.government, this.centralBank, nplRatio, economicHealth);
            try {
                this.crisisEngine.tick(this.sentiment, this.bankManager, this.government, this.inflationSystem, this.creditSystem, economicHealth);
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u5371\u673a\u5f15\u64ce\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
            try {
                this.macroEnvironment.applyMacroTransmission(this.cycleEngine.getEconomicHealth(), this.creditSystem.getCreditGrowth(), this.crisisEngine.getCrisisLevel());
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u5b8f\u89c2\u4f20\u5bfc\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
        }
        if (gameTime > 0L && gameTime % 2160000L == 0L) {
            double riskAppetite = this.state.getRiskAppetite();
            if (riskAppetite == 0.0) {
                riskAppetite = this.macroEnvironment.getRiskAppetite();
            }
            double inflation = this.inflationSystem.getInflationRate();
            AICompanyData.setDupontEnabled(this.isDupontEffective());
            this.aiCompanyManager.updateAll(this.state, riskAppetite, inflation);
            this.recomputeCapmFair();
            System.out.println("\ud83d\udcca \u57fa\u672c\u9762\u5b63\u5ea6\u66f4\u65b0\uff08riskAppetite=" + riskAppetite + ", inflation=" + inflation + "\uff09");
        }
        if (this.companySystemEnabled && this.tickCounter % 24000L == 0L) {
            try {
                AssetScanner.scanAll(level, this.companyManager, this.commodityMarket);
                var ak = this.akGrowthEngine.step(this.companyManager,
                        this.worldMacro.snapshot(), this.macroEnvironment, this.currentDay,
                        Math.max(1, level.players().size()));
                this.crossAssetMacro.setAkState(ak.growth(), ak.returnRate(), ak.tfp());
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u73a9\u5bb6\u516c\u53f8\u8d44\u4ea7\u626b\u63cf\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
        }
        if (this.companySystemEnabled && gameTime > 0L && gameTime % 2160000L == 0L) {
            try {
                for (CompanyData c2 : this.companyManager.getAllCompanies()) {
                    if (c2.getStatus() != CompanyStatus.PRIVATE && c2.getStatus() != CompanyStatus.LISTED) continue;
                    this.generateFinancialReport(c2);
                }
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u73a9\u5bb6\u516c\u53f8\u8d22\u62a5\u751f\u6210\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
        }
        if (this.companySystemEnabled && gameTime > 0L && gameTime % 8640000L == 0L) {
            try {
                for (CompanyData c2 : this.companyManager.getAllCompanies()) {
                    this.payDividend(c2, level);
                }
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u73a9\u5bb6\u516c\u53f8\u5206\u7ea2\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
        }
        if (this.companySystemEnabled && gameTime > 0L && gameTime % 720000L == 0L) {
            try {
                DelistingMonitor.check(level, this);
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u9000\u5e02\u68c0\u67e5\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
            }
        }
        double liquidityMultiplier = this.priceEngine.getLiquidityMultiplier();
        boolean bl = tradingOpen = !this.aShareEnabled || this.calendar.isMarketOpen();
        if (tradingOpen) {
            List<ServerPlayer> players;
            if (this.marSEnabled && gameTime % 20L == 0L) {
                try {
                    this.mesoLayer.tick(this, this.buildMacroSnapshot(), this.tickCounter);
                    this.placeMesoOrders();
                }
                catch (RuntimeException e) {
                    System.out.println("\u26a0\ufe0f \u4e2d\u89c2\u5c42\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
                }
            }
            if (!this.liqEnabled && (this.agentManager.isLiquidityMakerEnabled() || this.agentManager.isMarketMakerAutoFlow() || this.agentManager.isEngineLiqGate())) {
                this.agentManager.setLiquidityMakerEnabled(false);
                this.agentManager.setMarketMakerAutoFlow(false);
                this.agentManager.setEngineLiqGate(false);
            }
            for (String assetId : this.state.getAssetIds()) {
                double gbmPrice = this.priceEngine.getGbmPrice(assetId);
                double lastPrice = this.state.getAsset(assetId).getLastPrice();
                if (gbmPrice <= 0.0 || lastPrice <= 0.0) {
                    System.out.println("\u26a0\ufe0f \u8d44\u4ea7 " + assetId + " \u4ef7\u683c\u65e0\u6548: gbm=" + gbmPrice + ", last=" + lastPrice);
                    continue;
                }
                OrderBook book = this.orderBooks.get(assetId);
                if (book == null) continue;
                book.setCurrentTick(gameTime);
                book.purgeExpired();
                AICompanyData aICompanyData = this.aiCompanyManager.getAll().stream().filter(c -> c.getStockId().equals(assetId)).findFirst().orElse(null);
                if (aICompanyData != null) {
                    this.agentManager.updateCompanyData(assetId, aICompanyData);
                }
                this.agentManager.stepAgents(assetId, gbmPrice, lastPrice, book, liquidityMultiplier);
                List<PriceDiscoveryEngine.OrderFlow> orders = this.agentManager.consumeOrders(assetId);
                if (orders.isEmpty()) continue;
                System.out.println("\ud83e\udd16 \u8d44\u4ea7 " + assetId + " \u751f\u6210 " + orders.size() + " \u7b14\u8ba2\u5355");
                OrderPipeline.Context pctx = this.buildPipelineContext(assetId, book);
                for (PriceDiscoveryEngine.OrderFlow orderFlow : orders) {
                    Order order = this.orderPipeline.toOrder(new OrderPipeline.OrderIntent(assetId, orderFlow.isBuy, orderFlow.size, orderFlow.price, 0.5, "agent", this.tickCounter, orderFlow.mmTag), true, pctx);
                    if (order == null) continue;
                    book.placeOrder(order);
                }
                this.flushCageClampCount(book, pctx);
            }
            if (this.orderFlowEnabled && this.orderFlowModel.enabled()) {
                this.orderFlowModel.setCurrentTick(gameTime);
                for (String assetId : this.state.getAssetIds()) {
                    boolean bl2;
                    double mid;
                    OrderBook book = this.orderBooks.get(assetId);
                    if (book == null || (mid = this.midPriceOf(assetId, book)) <= 0.0) continue;
                    OrderPipeline.Context pctxZi = this.buildPipelineContext(assetId, book, "zi", this.orderFlowModel.ziIsMarket(assetId));
                    OrderPipeline.Context pctxHawkes = this.buildPipelineContext(assetId, book, "hawkes", false);
                    if (this.orderFlowModel.shouldGenerateZiOrder(assetId)) {
                        bl2 = this.orderFlowModel.ziIsBuy(assetId);
                        int qty = this.orderFlowModel.ziLotSize(assetId);
                        if (this.orderFlowModel.ziIsMarket(assetId)) {
                            Order m = this.orderPipeline.toOrder(new OrderPipeline.OrderIntent(assetId, bl2, qty, mid, 1.0, "zi", this.tickCounter), true, pctxZi);
                            if (m != null) {
                                book.placeOrder(m);
                            }
                        } else {
                            double offset = this.orderFlowModel.ziLimitOffset(assetId);
                            double price = bl2 ? mid - offset : mid + offset;
                            Order m = this.orderPipeline.toOrder(new OrderPipeline.OrderIntent(assetId, bl2, qty, price, 0.0, "zi", this.tickCounter), true, pctxZi);
                            if (m != null) {
                                book.placeOrder(m);
                            }
                        }
                        this.orderFlowModel.recordArrival(assetId, gameTime);
                        this.flushCageClampCount(book, pctxZi);
                    }
                    if (!this.orderFlowModel.hawkesArrival(assetId)) continue;
                    this.orderFlowModel.recordArrival(assetId, gameTime);
                    bl2 = this.imbalanceOf(book) >= 0.0;
                    double offset = this.orderFlowModel.ziLimitOffset(assetId);
                    double price = bl2 ? mid - offset : mid + offset;
                    Order h = this.orderPipeline.toOrder(new OrderPipeline.OrderIntent(assetId, bl2, this.orderFlowModel.ziLotSize(assetId), price, 0.0, "hawkes", this.tickCounter), true, pctxHawkes);
                    if (h != null) {
                        book.placeOrder(h);
                    }
                    this.flushCageClampCount(book, pctxHawkes);
                }
            }
            for (String assetId : this.state.getAssetIds()) {
                OrderBook book = this.orderBooks.get(assetId);
                if (book == null) continue;
                book.setImpactEnabled(this.p2Enabled);
                this.applyPriceLimitBand(book, assetId);
                book.setMarSImpactEnabled(this.marSEnabled && this.p2Enabled);
                book.setMarketContext(this.priceEngine.getAssetVolatility(assetId), this.volumeTracker.getAdv(assetId));
                book.setClearingEnabled(this.cmClearingEffective());
                book.setCageEnabled(this.dgCageEffective());
                book.setAlmgrenChrissEnabled(this.dgAcEffective());
                if (this.dgCoolingEffective() && book.isCoolingActive()) {
                    boolean earlyClose;
                    DgCoolingState cs = this.dgCooling.computeIfAbsent(assetId, k -> new DgCoolingState());
                    boolean windowExpired = this.tickCounter >= cs.windowEndTick;
                    boolean bl3 = earlyClose = book.getCoolingNewLimitOrders() >= 4;
                    if (!windowExpired && !earlyClose) continue;
                    book.endCooling();
                    this.runDgCoolingAuction(level, assetId, book, cs);
                    continue;
                }
                if (this.circuitBreakerTripped.getOrDefault(assetId, false).booleanValue()) {
                    long recoveryTime = this.circuitBreakerRecoveryTime.getOrDefault(assetId, 0L);
                    if (gameTime < recoveryTime) continue;
                    this.circuitBreakerTripped.put(assetId, false);
                }
                if (this.liqEnabled) {
                    this.runLiqL25B(level, assetId, book);
                }
                boolean filledThisTick = this.lastFillTick.getOrDefault(assetId, -1L) == this.tickCounter;
                List<Trade> trades = book.matchWithSlippage(0.002);
                if (!trades.isEmpty()) {
                    filledThisTick = true;
                    for (Trade trade : trades) {
                        this.attributeTrade(level, trade);
                        this.tradeHistory.get(assetId).add(trade);
                        this.dailyTrades.get(assetId).add(trade);
                        this.volumeTracker.onTrade(assetId, trade.getQuantity());
                        if (!this.hasTodayTrade.getOrDefault(assetId, false).booleanValue()) {
                            this.todayOpen.put(assetId, trade.getPrice());
                            this.hasTodayTrade.put(assetId, true);
                        }
                        this.todayClose.put(assetId, trade.getPrice());
                        double d = trade.getPrice();
                        this.updateDailySnapshot(assetId, d, trade.getQuantity());
                        this.priceEngine.updateLastPrice(assetId, d);
                        this.priceEngine.addTradePoint(assetId, new PriceDiscoveryEngine.TradePoint(trade.getPrice(), trade.getQuantity(), trade.isBuy()));
                        this.lastFillTick.put(assetId, this.tickCounter);
                    }
                    System.out.println("\ud83d\udca5 \u8d44\u4ea7 " + assetId + " \u6210\u4ea4 " + trades.size() + " \u7b14\uff0c\u6700\u65b0\u4ef7: " + trades.get(trades.size() - 1).getPrice());
                }
                if (!this.dgCoolingEffective()) continue;
                this.evaluateDgCoolingTrigger(level, assetId, book, filledThisTick);
            }
            if (gameTime % 20L == 0L) {
                this.runQuantStrategies(level);
            }
            if (this.marginEnabled && this.tickCounter % 20L == 0L) {
                try {
                    this.marginManager.settleAll(this);
                }
                catch (RuntimeException e) {
                    System.out.println("\u26a0\ufe0f \u878d\u8d44\u878d\u5238\u7ed3\u7b97\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
                }
            }
            if (gameTime % 20L == 0L) {
                try {
                    this.securitiesManager.dailyPropTrading(this);
                    this.securitiesManager.publishResearch(this, 1);
                    this.runPeriodicBuyback();
                }
                catch (RuntimeException e) {
                    System.out.println("\u26a0\ufe0f \u8bc1\u5238\u81ea\u8425/\u7814\u62a5/\u56de\u8d2d\u5f02\u5e38\uff08\u4e0d\u963b\u65ad tick\uff09: " + e.getMessage());
                }
            }
            for (String assetId : this.state.getAssetIds()) {
                double lastPrice = this.state.getAsset(assetId).getLastPrice();
                double prevClose = this.priceEngine.getDailyClose(assetId);
                if (!(prevClose > 0.0) || this.circuitBreakerTripped.getOrDefault(assetId, false).booleanValue()) continue;
                double change = (lastPrice - prevClose) / prevClose;
                double threshold = Math.abs(this.priceEngine.getLimitDown());
                if (!(Math.abs(change) > threshold)) continue;
                this.circuitBreakerTripped.put(assetId, true);
                this.circuitBreakerRecoveryTime.put(assetId, gameTime + 1200L);
                System.out.println("\u26d4 \u7194\u65ad\u89e6\u53d1: " + assetId + " \u6da8\u8dcc\u5e45 " + String.format("%.2f%%", change * 100.0));
            }
            if (gameTime % 20L == 0L && !(players = level.getServer().getPlayerList().getPlayers()).isEmpty()) {
                for (String assetId : this.state.getAssetIds()) {
                    OrderBook book = this.orderBooks.get(assetId);
                    if (book == null) continue;
                    OrderBook.OrderBookSnapshot snap = book.getSnapshot();
                    ArrayList<OrderBookSnapshotPacket.Level> bidList = new ArrayList<OrderBookSnapshotPacket.Level>();
                    for (OrderBook.OrderBookLevel orderBookLevel : snap.bids) {
                        bidList.add(new OrderBookSnapshotPacket.Level(orderBookLevel.price, orderBookLevel.quantity));
                    }
                    ArrayList<OrderBookSnapshotPacket.Level> askList = new ArrayList<OrderBookSnapshotPacket.Level>();
                    for (OrderBook.OrderBookLevel l : snap.asks) {
                        askList.add(new OrderBookSnapshotPacket.Level(l.price, l.quantity));
                    }
                    OrderBookSnapshotPacket orderBookSnapshotPacket = new OrderBookSnapshotPacket(assetId, bidList, askList);
                    for (ServerPlayer player : players) {
                        player.connection.send((CustomPacketPayload)orderBookSnapshotPacket);
                    }
                }
            }
        }
        this.excellent24Index.updateValue(this.state);
        this.circuitBreaker.tick();
    }

    private void performTPlus1Settlement() {
        System.out.println("\ud83d\udd04 \u6267\u884c T+1 \u6e05\u7b97");
        if (this.aShareEnabled) {
            this.settlement.settleTPlus1();
        }
        for (String id : this.state.getAssetIds()) {
            this.pendingSettlements.get(id).clear();
        }
    }

    private void feedSuperstructureTick(ServerLevel level) {
        boolean centralBankerOnline = false;
        boolean financeMinisterOnline = false;
        for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
            RoleSystem.Role r = RoleSystem.getInstance().get(sp.getUUID());
            if (r == RoleSystem.Role.CENTRAL_BANKER) {
                centralBankerOnline = true;
                continue;
            }
            if (r != RoleSystem.Role.FINANCE_MINISTER) continue;
            financeMinisterOnline = true;
        }
        boolean centralBankerAppointed = this.isRoleAppointed(RoleSystem.Role.CENTRAL_BANKER);
        double inflation = this.inflationSystem.getInflationRate();
        double economicHealth = this.cycleEngine.getEconomicHealth();
        this.centralBank.updateGuardrailInputs(inflation, economicHealth);
        this.centralBank.getGuardrail().setCentralBankerOnline(centralBankerOnline);
        this.government.getGuardrail().setFinanceMinisterOnline(financeMinisterOnline);
        if (centralBankerAppointed && !centralBankerOnline) {
            this.centralBank.applyMonetaryAutopilot();
        }
    }

    private boolean isRoleAppointed(RoleSystem.Role role) {
        for (String r : RoleSystem.getInstance().snapshot().values()) {
            if (!role.name().equals(r)) continue;
            return true;
        }
        return false;
    }

    private void fundNewPlayers(ServerLevel level) {
        for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
            if (!this.fundedPlayers.add(sp.getUUID())) continue;
            this.centralBank.injectBaseMoney(1000.0);
            PlayerEconomy eco = PlayerEconomy.get((Player)sp);
            if (!(eco.getCash() < 1000.0)) continue;
            eco.setCash(1000.0);
        }
    }

    private double runDailyFiat(ServerLevel level) {
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        ArrayList<PlayerEconomy> economies = new ArrayList<PlayerEconomy>();
        for (ServerPlayer sp : players) {
            economies.add(PlayerEconomy.get((Player)sp));
        }
        this.centralBank.beginDay(this.tickCounter);
        this.bankManager.dailyUpdate(this.currentDay, this.government);
        if (this.cbBalanceEffective()) {
            this.centralBank.syncBalanceSheet(this.government);
        }
        if (this.cbRrSyncEffective()) {
            this.centralBank.propagateReserveRequirement(this.bankManager);
        }
        if (this.cbRrSyncEffective()) {
            this.bankManager.settleReserveRequirement();
        }
        if (this.cbCorridorEffective()) {
            int troubledTransitions;
            this.bankManager.getInterbankMarket().clearing(this.bankManager, this.centralBank);
            this.bankManager.getInterbankMarket().setLastClearingTick(this.tickCounter);
            this.lastTroubledStateTransitions = troubledTransitions = this.centralBank.evaluateTroubledBanks(this.bankManager, this.bankManager.getInterbankMarket().getInterbankTotalSupply());
        } else {
            this.lastTroubledStateTransitions = 0;
        }
        if (this.cbLprEffective()) {
            this.centralBank.updateLpr(this.bankManager);
        }
        this.bankManager.runBaselineCreditCreation(50000.0);
        this.taxSystem.collectAll(economies);
        this.taxSystem.recycleFiscalSpending();
        if (this.cbEventsEffective()) {
            this.centralBank.flushPolicyEvents();
            this.applyPolicyEventJumps();
        }
        if (this.cbAggEffective()) {
            this.refreshMonetaryAggregates(economies);
        }
        if (this.cbBalanceEffective()) {
            this.centralBank.validateIdentities();
        }
        double postTaxCash = 0.0;
        for (PlayerEconomy eco : economies) {
            postTaxCash += Math.max(0.0, eco.getCash());
        }
        this.inflationSystem.update(this.bankManager, this.commodityMarket, postTaxCash);
        this.applyMeanFairValueDelta();
        FinanceMinisterGuardrail fm = this.government.getGuardrail();
        if (!fm.isFinanceMinisterOnline() && this.isRoleAppointed(RoleSystem.Role.FINANCE_MINISTER)) {
            fm.applyFiscalAutopilot();
        }
        return postTaxCash;
    }

    private void applyPolicyEventJumps() {
        long total = this.centralBank.getPolicyEventCount();
        if (total <= this.appliedPolicyEventCount) {
            return;
        }
        int pending = (int)Math.min(total - this.appliedPolicyEventCount, 64L);
        List<PolicyEvent> recent = this.centralBank.getRecentPolicyEvents(pending);
        for (PolicyEvent e : recent) {
            if (e == null) continue;
            this.macroEnvironment.injectPolicyEventJump(CentralBank.policyEventJump(e.kind()));
        }
        this.appliedPolicyEventCount = total;
    }

    private void refreshMonetaryAggregates(List<PlayerEconomy> economies) {
        double cashSum = 0.0;
        for (PlayerEconomy eco : economies) {
            if (eco == null) continue;
            cashSum += Math.max(0.0, eco.getCash());
        }
        double cash = cashSum;
        MonetaryAggregator agg = this.bankManager.getMonetaryAggregator();
        agg.setTickCounter(this.tickCounter);
        agg.setBalanceSheetSnapshot(this.centralBank.getTotalAssets(), this.centralBank.getTotalLiabilitiesAndCapital());
        agg.refresh(this.centralBank, this.bankManager, () -> cash);
    }

    private boolean withinAShareLimit(Order order) {
        double prevClose = this.state.getAsset(order.getAssetId()).getPrevClose();
        if (prevClose <= 0.0) {
            return true;
        }
        double up = PriceDiscoveryEngine.aShareLimitUp(prevClose);
        double down = PriceDiscoveryEngine.aShareLimitDown(prevClose);
        return PriceDiscoveryEngine.aShareWithinBand(order.getLimitPrice(), up, down);
    }

    private OrderPipeline.Context buildPipelineContext(String assetId, OrderBook book) {
        OrderBook.OrderBookSnapshot s;
        double lastPrice;
        OrderPipeline.Context ctx = new OrderPipeline.Context();
        ctx.sigma = this.priceEngine.getAssetVolatility(assetId);
        ctx.volume = this.volumeTracker.getAdv(assetId);
        ctx.marSEnabled = this.marSEnabled && this.p2Enabled;
        ctx.aShareEnabled = this.aShareEnabled;
        ctx.marketOpen = !this.aShareEnabled || this.calendar.isMarketOpen();
        AssetSnapshot snap = this.state.getAsset(assetId);
        double d = lastPrice = snap != null ? snap.getLastPrice() : 0.0;
        if (this.aShareEnabled) {
            double prevClose;
            double d2 = prevClose = snap != null ? snap.getPrevClose() : 0.0;
            if (prevClose > 0.0) {
                ctx.limitUp = PriceDiscoveryEngine.aShareLimitUp(prevClose);
                ctx.limitDown = PriceDiscoveryEngine.aShareLimitDown(prevClose);
                ctx.bandActive = true;
            }
        } else if (lastPrice > 0.0) {
            ctx.limitUp = lastPrice * (1.0 + this.priceEngine.getLimitUp());
            ctx.limitDown = lastPrice * (1.0 + this.priceEngine.getLimitDown());
            ctx.bandActive = true;
        }
        OrderBook.OrderBookSnapshot orderBookSnapshot = s = book != null ? book.getSnapshot() : null;
        if (s != null && !s.bids.isEmpty() && !s.asks.isEmpty() && lastPrice > 0.0) {
            ctx.bestBid = s.bids.get((int)0).price;
            ctx.bestAsk = s.asks.get((int)0).price;
            ctx.spread = (ctx.bestAsk - ctx.bestBid) / lastPrice;
        } else if (s != null && !s.bids.isEmpty()) {
            ctx.bestBid = s.bids.get((int)0).price;
        } else if (s != null && !s.asks.isEmpty()) {
            ctx.bestAsk = s.asks.get((int)0).price;
        }
        if (!(ctx.spread > 0.0)) {
            ctx.spread = 0.002;
        }
        boolean bl = ctx.cageActive = this.dgCageEffective() && book != null && book.priceCageActive();
        if (ctx.cageActive) {
            ctx.cageUp = book.getCageUp();
            ctx.cageDown = book.getCageDown();
        }
        return ctx;
    }

    private OrderPipeline.Context buildPipelineContext(String assetId, OrderBook book, String source, boolean isMarket) {
        OrderPipeline.Context ctx = this.buildPipelineContext(assetId, book);
        ctx.cageSkipsThisFlow = isMarket && ("zi".equals(source) || "hawkes".equals(source) || "broker".equals(source));
        return ctx;
    }

    private void flushCageClampCount(OrderBook book, OrderPipeline.Context ctx) {
        if (ctx != null && ctx.cageClampCount > 0L) {
            if (book != null) {
                book.recordCageClamp(ctx.cageClampCount);
            }
            this.dgCageClampCount += ctx.cageClampCount;
        }
    }

    public long getDataGuardCageRejectCount() {
        return this.dgCageRejectCount;
    }

    public long getDataGuardCageClampCount() {
        return this.dgCageClampCount;
    }

    private double computeDgEqPrice(String assetId) {
        double pEq;
        double adv;
        double fair = this.aiCompanyManager.fairValueOf(assetId);
        if (!(fair > 0.0)) {
            fair = this.priceEngine.getGbmPrice(assetId);
        }
        if (!(fair > 0.0)) {
            AssetSnapshot s = this.state.getAsset(assetId);
            fair = s != null ? s.getLastPrice() : 0.0;
        }
        double w = (adv = this.volumeTracker.getAdv(assetId)) > 0.0 ? 0.5 : 0.0;
        double pRef = fair;
        if (w > 0.0) {
            pRef = this.dgVwapRecent(assetId);
            if (!(pRef > 0.0)) {
                AssetSnapshot s = this.state.getAsset(assetId);
                double d = pRef = s != null ? s.getPrevClose() : 0.0;
            }
            if (!(pRef > 0.0)) {
                pRef = this.priceEngine.getGbmPrice(assetId);
            }
            if (!(pRef > 0.0)) {
                pRef = fair;
            }
        }
        return (pEq = w * pRef + (1.0 - w) * fair) > 0.0 ? pEq : 0.0;
    }

    private double dgVwapRecent(String assetId) {
        List<Trade> today = this.dailyTrades.get(assetId);
        if (today == null || today.isEmpty()) {
            return 0.0;
        }
        int from = Math.max(0, today.size() - 50);
        double sumPV = 0.0;
        double sumV = 0.0;
        for (int i = from; i < today.size(); ++i) {
            Trade t = today.get(i);
            sumPV += t.getPrice() * (double)t.getQuantity();
            sumV += (double)t.getQuantity();
        }
        return sumV > 0.0 ? sumPV / sumV : 0.0;
    }

    private boolean dgAggregateCross(OrderBook book) {
        return book.hasBids() && book.hasAsks() && book.getBestBid() >= book.getBestAsk();
    }

    private void evaluateDgCoolingTrigger(ServerLevel level, String assetId, OrderBook book, boolean filledThisTick) {
        double pEq;
        if (book.isCoolingActive()) {
            return;
        }
        DgCoolingState cs = this.dgCooling.computeIfAbsent(assetId, k -> new DgCoolingState());
        if (cs.justStarted) {
            cs.justStarted = false;
            return;
        }
        cs.lastEq = pEq = this.computeDgEqPrice(assetId);
        boolean deviationHit = false;
        double deviation = 0.0;
        if (pEq > 0.0) {
            double last;
            AssetSnapshot s = this.state.getAsset(assetId);
            double d = last = s != null ? s.getLastPrice() : 0.0;
            if (last > 0.0) {
                cs.lastDev = deviation = (last - pEq) / pEq;
                boolean bl = deviationHit = Math.abs(deviation) >= 0.06;
            }
        }
        if (deviationHit) {
            this.beginDgCooling(assetId, book, DgHaltReason.EQ_DEVIATION);
            return;
        }
        if (this.dgAggregateCross(book) && !filledThisTick) {
            ++cs.consecutiveFailures;
            book.setConsecutiveMatchFailures(cs.consecutiveFailures);
            if (cs.consecutiveFailures >= 30) {
                this.beginDgCooling(assetId, book, DgHaltReason.MATCH_FAILURES);
            }
        } else {
            cs.consecutiveFailures = 0;
            book.setConsecutiveMatchFailures(0);
        }
    }

    private void beginDgCooling(String assetId, OrderBook book) {
        this.beginDgCooling(assetId, book, DgHaltReason.EQ_DEVIATION);
    }

    private void beginDgCooling(String assetId, OrderBook book, DgHaltReason reason) {
        DgCoolingState cs = this.dgCooling.computeIfAbsent(assetId, k -> new DgCoolingState());
        cs.active = true;
        cs.windowEndTick = this.tickCounter + 100L;
        cs.justStarted = true;
        cs.reason = reason;
        this.circuitBreakerTripped.put(assetId, true);
        this.circuitBreakerRecoveryTime.put(assetId, this.tickCounter + 100L);
        int cleared = book.cancelAllSynthetic();
        book.noteRefSyntheticCleared(cleared);
        book.beginCooling(this.tickCounter);
        book.setCoolingWindowRound(cs.windowRound + 1);
        book.setConsecutiveMatchFailures(cs.consecutiveFailures);
        System.out.println("\u26d4 DG-3 \u51b7\u5374\u7a97\u53e3\u5f00\u542f: " + assetId + "\uff08100 tick \u2248 5s @20tps\uff0c\u539f\u56e0 " + String.valueOf((Object)reason) + "\uff0c\u6e05\u5408\u6210\u5355 " + cleared + " \u7b14\uff0c\u8f6e\u6b21 " + (cs.windowRound + 1) + "\uff09");
    }

    private void runDgCoolingAuction(ServerLevel level, String assetId, OrderBook book, DgCoolingState cs) {
        double pEq;
        cs.lastEq = pEq = this.computeDgEqPrice(assetId);
        double ref = pEq > 0.0 ? pEq : this.state.getAsset(assetId).getPrevClose();
        OrderBook.CallAuctionResult res = book.runCallAuctionDetailed(ref);
        cs.lastPStar = res.pStar;
        cs.lastPStarVolume = res.pStarVolume;
        if (!res.trades.isEmpty()) {
            this.recordLiqTrades(level, assetId, res.trades);
            cs.active = false;
            cs.consecutiveFailures = 0;
            cs.windowRound = 0;
            book.setConsecutiveMatchFailures(0);
            book.setCoolingWindowRound(0);
            this.circuitBreakerTripped.put(assetId, false);
            this.circuitBreakerRecoveryTime.put(assetId, 0L);
            System.out.println("\u2705 DG-3 \u51b7\u5374\u7a97\u53e3\u7ed3\u675f: " + assetId + " \u96c6\u5408\u7ade\u4ef7\u51fa\u6e05 " + res.trades.size() + " \u7b14 @P*=" + res.pStar + "\uff08\u91cf " + res.pStarVolume + "\uff09\uff0c\u6062\u590d\u8fde\u7eed\u7ade\u4ef7");
            return;
        }
        cs.active = false;
        if (this.dgAggregateCross(book) && cs.windowRound + 1 < 3) {
            ++cs.windowRound;
            this.beginDgCooling(assetId, book, cs.reason != null ? cs.reason : DgHaltReason.LEGACY_NO_FILL);
            System.out.println("\ud83d\udd01 DG-3 \u51b7\u5374\u7a97\u53e3\u6eda\u52a8: " + assetId + " \u7b2c " + (cs.windowRound + 1) + " \u8f6e");
        } else {
            cs.windowRound = 0;
            book.setCoolingWindowRound(0);
            this.circuitBreakerTripped.put(assetId, false);
            this.circuitBreakerRecoveryTime.put(assetId, 0L);
            System.out.println("\u21a9\ufe0f DG-3 \u51b7\u5374\u7a97\u53e3\u7ed3\u675f: " + assetId + " \u7ade\u4ef7\u96f6\u51fa\u6e05\uff0c\u6062\u590d\u8fde\u7eed\u7ade\u4ef7\uff08\u4e0d\u518d\u6eda\u52a8\uff09");
        }
    }

    public DgHaltReason getDataGuardHaltReason(String assetId) {
        DgCoolingState cs = this.dgCooling.get(assetId);
        return cs != null ? cs.reason : null;
    }

    public double getDataGuardEquilibriumPrice(String assetId) {
        DgCoolingState cs = this.dgCooling.get(assetId);
        return cs != null ? cs.lastEq : 0.0;
    }

    public double getDataGuardDeviation(String assetId) {
        DgCoolingState cs = this.dgCooling.get(assetId);
        return cs != null ? cs.lastDev : 0.0;
    }

    public boolean isDgCoolingActive(String assetId) {
        DgCoolingState cs = this.dgCooling.get(assetId);
        return cs != null && cs.active;
    }

    public long getDgCoolingWindowEnd(String assetId) {
        DgCoolingState cs = this.dgCooling.get(assetId);
        return cs != null ? cs.windowEndTick : 0L;
    }

    public int getDgCoolingWindowRound(String assetId) {
        DgCoolingState cs = this.dgCooling.get(assetId);
        return cs != null ? cs.windowRound : 0;
    }

    public int getDgConsecutiveFailures(String assetId) {
        DgCoolingState cs = this.dgCooling.get(assetId);
        return cs != null ? cs.consecutiveFailures : 0;
    }

    public double getDgLastEq(String assetId) {
        DgCoolingState cs = this.dgCooling.get(assetId);
        return cs != null ? cs.lastEq : 0.0;
    }

    public double getDgLastPStar(String assetId) {
        DgCoolingState cs = this.dgCooling.get(assetId);
        return cs != null ? cs.lastPStar : 0.0;
    }

    public long getDgLastPStarVolume(String assetId) {
        DgCoolingState cs = this.dgCooling.get(assetId);
        return cs != null ? cs.lastPStarVolume : 0L;
    }

    public boolean isDgAcEffective() {
        return this.dgAcEffective();
    }

    public boolean isDgCageEffective() {
        return this.dgCageEffective();
    }

    public boolean isDgCoolingEffective() {
        return this.dgCoolingEffective();
    }

    private void applyPriceLimitBand(OrderBook book, String assetId) {
        double prevClose;
        if (this.aShareEnabled && (prevClose = this.state.getAsset(assetId).getPrevClose()) > 0.0) {
            book.setLimitBand(PriceDiscoveryEngine.aShareLimitUp(prevClose), PriceDiscoveryEngine.aShareLimitDown(prevClose));
            return;
        }
        book.clearLimitBand();
    }

    private void runLiqL25B(ServerLevel level, String assetId, OrderBook book) {
        List<Trade> ts;
        Order hft;
        List<Trade> ts2;
        Order forced;
        long lastFill;
        if (book.isCoolingActive()) {
            return;
        }
        List<Order> restingMarkets = book.drainRestingMarketOrders();
        if (!restingMarkets.isEmpty()) {
            for (Order m : restingMarkets) {
                List<Trade> ts3 = book.executeMarketableOrder(m);
                if (ts3.isEmpty()) continue;
                this.recordLiqTrades(level, assetId, ts3);
            }
        }
        if (this.liqMaxWaitEnabled && this.tickCounter - (lastFill = this.lastFillTick.getOrDefault(assetId, this.tickCounter).longValue()) >= 60L && (forced = this.liqForcedMarketOrder(assetId, book)) != null && !(ts2 = book.executeMarketableOrder(forced)).isEmpty()) {
            this.recordLiqTrades(level, assetId, ts2);
            System.out.println("\u26a1 LIQ MAX_WAIT \u5f3a\u5236\u5e02\u4ef7\u5355: " + assetId + " " + (forced.isBuy() ? "\u4e70" : "\u5356") + " \u6210\u4ea4 " + ts2.size() + " \u7b14\uff0c\u6700\u65b0\u4ef7: " + ts2.get(ts2.size() - 1).getPrice());
        }
        if (this.liqHftEnabled && this.liqHftArrive(assetId) && (hft = this.liqHftMarketOrder(assetId, book)) != null && !(ts = book.executeMarketableOrder(hft)).isEmpty()) {
            this.recordLiqTrades(level, assetId, ts);
        }
        if (this.liqCircuitRehangEnabled) {
            long lastFill2 = this.lastFillTick.getOrDefault(assetId, this.tickCounter);
            long lastRehang = this.lastRehangTick.getOrDefault(assetId, 0L);
            if (this.tickCounter - lastFill2 >= 100L && this.tickCounter - lastRehang >= 100L) {
                this.liqCircuitRehang(level, assetId, book);
            }
        }
        if (this.liqAuctionEnabled) {
            boolean aggregateCross;
            long lastFill3 = this.lastFillTick.getOrDefault(assetId, this.tickCounter);
            long lastAuc = this.lastAuctionTick.getOrDefault(assetId, 0L);
            boolean dblSide = book.hasBids() && book.hasAsks();
            boolean bl = aggregateCross = dblSide && book.getBestBid() >= book.getBestAsk();
            if (aggregateCross && this.tickCounter - lastFill3 >= 120L && this.tickCounter - lastAuc >= 120L) {
                List<Trade> ts4;
                this.lastAuctionTick.put(assetId, this.tickCounter);
                double ref = this.state.getAsset(assetId).getPrevClose();
                if (ref <= 0.0) {
                    ref = this.priceEngine.getGbmPrice(assetId);
                }
                if (!(ts4 = book.runCallAuction(ref)).isEmpty()) {
                    this.recordLiqTrades(level, assetId, ts4);
                }
            }
        }
    }

    private void liqCircuitRehang(ServerLevel level, String assetId, OrderBook book) {
        List<Trade> ts;
        Order forced;
        this.lastRehangTick.put(assetId, this.tickCounter);
        int cleared = book.cancelAllSynthetic();
        this.agentManager.resetMarketMaker(assetId);
        double gbm = this.priceEngine.getGbmPrice(assetId);
        if (gbm <= 0.0) {
            gbm = this.state.getAsset(assetId).getLastPrice();
        }
        if (gbm <= 0.0) {
            return;
        }
        double delta = Math.max(0.01, (double)Math.round(gbm * 0.01 * 100.0) / 100.0);
        double bidP = (double)Math.round(PriceDiscoveryEngine.aShareClamp(gbm - delta, book.getLimitUp(), book.getLimitDown()) * 100.0) / 100.0;
        double askP = (double)Math.round(PriceDiscoveryEngine.aShareClamp(gbm + delta, book.getLimitUp(), book.getLimitDown()) * 100.0) / 100.0;
        if (this.cmClearingEffective()) {
            double gBid = book.sanitizeFill(bidP);
            bidP = gBid < 0.0 ? 0.0 : gBid;
            double gAsk = book.sanitizeFill(askP);
            askP = gAsk < 0.0 ? 0.0 : gAsk;
        }
        if (bidP > 0.0) {
            Order b = new Order(null, assetId, Order.Type.LIMIT_BUY, 300, bidP, this.tickCounter);
            b.setSynthetic(true);
            book.placeOrder(b);
        }
        if (askP > 0.0) {
            Order s = new Order(null, assetId, Order.Type.LIMIT_SELL, 300, askP, this.tickCounter);
            s.setSynthetic(true);
            book.placeOrder(s);
        }
        if ((forced = this.liqForcedMarketOrder(assetId, book)) != null && !(ts = book.executeMarketableOrder(forced)).isEmpty()) {
            this.recordLiqTrades(level, assetId, ts);
            System.out.println("\ud83d\udee0 LIQ \u7194\u65ad\u64a4\u5355\u91cd\u6302: " + assetId + " \u6e05 " + cleared + " \u5355\uff0cP_gbm=" + gbm + " \u91cd\u6302\u5e76\u6210\u4ea4 " + ts.size() + " \u7b14\uff0c\u6700\u65b0\u4ef7: " + ts.get(ts.size() - 1).getPrice());
        }
    }

    private void recordLiqTrades(ServerLevel level, String assetId, List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        for (Trade trade : trades) {
            this.attributeTrade(level, trade);
            this.tradeHistory.get(assetId).add(trade);
            this.dailyTrades.get(assetId).add(trade);
            this.volumeTracker.onTrade(assetId, trade.getQuantity());
            if (!this.hasTodayTrade.getOrDefault(assetId, false).booleanValue()) {
                this.todayOpen.put(assetId, trade.getPrice());
                this.hasTodayTrade.put(assetId, true);
            }
            this.todayClose.put(assetId, trade.getPrice());
            this.updateDailySnapshot(assetId, trade.getPrice(), trade.getQuantity());
            this.priceEngine.updateLastPrice(assetId, trade.getPrice());
            this.priceEngine.addTradePoint(assetId, new PriceDiscoveryEngine.TradePoint(trade.getPrice(), trade.getQuantity(), trade.isBuy()));
            this.lastFillTick.put(assetId, this.tickCounter);
        }
    }

    private Order liqForcedMarketOrder(String assetId, OrderBook book) {
        double last;
        double gbm;
        boolean bidsPresent = book.hasBids();
        boolean asksPresent = book.hasAsks();
        if (!bidsPresent && !asksPresent) {
            return null;
        }
        boolean buy = !asksPresent ? false : (!bidsPresent ? true : ((gbm = this.priceEngine.getGbmPrice(assetId)) > (last = this.state.getAsset(assetId).getLastPrice()) ? true : (gbm < last ? false : this.liqTieBreak(assetId, "forced"))));
        int qty = this.liqSideEatQuantity(book, buy, 2.0);
        if (qty <= 0) {
            return null;
        }
        return this.liqBuildMarketOrder(assetId, buy, qty);
    }

    private boolean liqHftArrive(String assetId) {
        SeedableRandom r = this.liqHftRngs.computeIfAbsent(assetId, id -> new SeedableRandom(SeedableRandom.mix64(this.engineSeed ^ (long)("liq/hft/" + id).hashCode())));
        double p = 1.0 - Math.exp(-0.02);
        return r.nextDouble() < p;
    }

    private Order liqHftMarketOrder(String assetId, OrderBook book) {
        double imb;
        boolean buy;
        SeedableRandom r = this.liqHftRngs.get(assetId);
        if (r == null) {
            r = new SeedableRandom(SeedableRandom.mix64(this.engineSeed ^ (long)("liq/hft/" + assetId).hashCode()));
            this.liqHftRngs.put(assetId, r);
        }
        boolean bl = buy = (imb = book.getNetImbalance()) > 0.0 || imb == 0.0 && this.liqTieBreak(assetId, "hft");
        if (r.nextDouble() < 0.1) {
            buy = !buy;
        }
        boolean asksPresent = book.hasAsks();
        boolean bidsPresent = book.hasBids();
        if (buy && !asksPresent) {
            if (!bidsPresent) {
                return null;
            }
            buy = false;
        } else if (!buy && !bidsPresent) {
            if (!asksPresent) {
                return null;
            }
            buy = true;
        }
        int qty = this.liqSideEatQuantity(book, buy, 0.5);
        if (qty <= 0) {
            return null;
        }
        return this.liqBuildMarketOrder(assetId, buy, qty);
    }

    private int liqSideEatQuantity(OrderBook book, boolean buyAggressor, double scale) {
        int lot;
        boolean passiveIsBids = !buyAggressor;
        int total = book.getTotalQuantity(passiveIsBids);
        if (total <= 0) {
            return 0;
        }
        int best = book.getBestQuantity(passiveIsBids);
        int cap = (int)Math.max(1L, Math.round(scale * (double)best));
        int qty = Math.min(total, cap);
        if (qty >= (lot = 100)) {
            qty = qty / lot * lot;
        }
        return Math.max(1, qty);
    }

    private Order liqBuildMarketOrder(String assetId, boolean isBuy, int qty) {
        if (qty <= 0) {
            return null;
        }
        Order o = new Order(null, assetId, isBuy ? Order.Type.MARKET_BUY : Order.Type.MARKET_SELL, qty, 0.0, this.tickCounter);
        o.setSynthetic(true);
        return o;
    }

    private boolean liqTieBreak(String assetId, String tag) {
        long h = SeedableRandom.mix64(this.engineSeed ^ (long)assetId.hashCode() ^ (long)Long.hashCode(this.tickCounter) ^ (long)tag.hashCode());
        return (h & 1L) == 1L;
    }

    private void attributeTrade(ServerLevel level, Trade trade) {
        CompanyData company;
        if (!this.aShareEnabled) {
            return;
        }
        UUID buyer = trade.getBuyerPlayerId();
        UUID seller = trade.getSellerPlayerId();
        if (buyer != null) {
            this.settlement.onBuyFill(buyer, trade.getAssetId(), trade.getQuantity());
        }
        if (seller != null) {
            this.settlement.onSellFill(seller, trade.getAssetId(), trade.getQuantity());
        }
        if (level != null) {
            double amount = MarketEngine.round2(trade.getPrice() * (double)trade.getQuantity());
            if (buyer != null) {
                this.settleTradeBuy(level, buyer, trade.getAssetId(), trade.getQuantity(), trade.getPrice(), amount);
            }
            if (seller != null) {
                this.settleTradeSell(level, seller, trade.getAssetId(), trade.getQuantity(), amount);
            }
        }
        if ((company = this.companyManager.getByStockId(trade.getAssetId())) != null && company.getStatus() == CompanyStatus.LISTED) {
            if (buyer != null) {
                company.getShareholders().merge(buyer, trade.getQuantity(), Integer::sum);
            }
            if (seller != null) {
                int left = company.getShareholders().getOrDefault(seller, 0) - trade.getQuantity();
                if (left <= 0) {
                    company.getShareholders().remove(seller);
                } else {
                    company.getShareholders().put(seller, left);
                }
            }
        }
    }

    private void settleTradeBuy(ServerLevel level, UUID pid, String assetId, int qty, double price, double amount) {
        PlayerEconomy eco;
        if (pid == null || qty <= 0) {
            return;
        }
        ServerPlayer p = this.findPlayer(level, pid);
        PlayerEconomy playerEconomy = eco = p != null ? PlayerEconomy.get((Player)p) : null;
        if (eco != null) {
            eco.setCash(eco.getCash() - amount);
            eco.addHolding(assetId, qty, price);
        } else {
            PendingTradeSettlement pts = this.pendingTradeSettlement.computeIfAbsent(pid, k -> new PendingTradeSettlement());
            pts.cash -= amount;
            pts.addHolding.merge(assetId, qty, Integer::sum);
            pts.addPrice.merge(assetId, price, Double::max);
        }
    }

    private void settleTradeSell(ServerLevel level, UUID pid, String assetId, int qty, double amount) {
        PlayerEconomy eco;
        if (pid == null || qty <= 0) {
            return;
        }
        ServerPlayer p = this.findPlayer(level, pid);
        PlayerEconomy playerEconomy = eco = p != null ? PlayerEconomy.get((Player)p) : null;
        if (eco != null) {
            eco.addCash(amount);
            eco.removeHolding(assetId, qty);
        } else {
            PendingTradeSettlement pts = this.pendingTradeSettlement.computeIfAbsent(pid, k -> new PendingTradeSettlement());
            pts.cash += amount;
            pts.removeHolding.merge(assetId, qty, Integer::sum);
        }
    }

    public void seedHoldingsToAvailable(Map<UUID, Map<String, Integer>> holdingsByPlayer) {
        if (!this.aShareEnabled || holdingsByPlayer == null) {
            return;
        }
        for (Map.Entry<UUID, Map<String, Integer>> e : holdingsByPlayer.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            for (Map.Entry<String, Integer> h : e.getValue().entrySet()) {
                if (h.getKey() == null || h.getValue() == null || h.getValue() <= 0) continue;
                this.settlement.seedAvailable(e.getKey(), h.getKey(), h.getValue());
            }
        }
    }

    public void submitOrder(Order order) {
        List<Trade> trades;
        List<Order> restingMarkets;
        if (!order.isSynthetic() && order.getPlayerId() != null && !this.hasBankCard(order.getPlayerId())) {
            System.out.println("\u26d4 \u8bf7\u5148\u529e\u7406\u50a8\u84c4\u5361");
            return;
        }
        if (this.aShareEnabled && !this.calendar.isMarketOpen()) {
            System.out.println("\u26d4 \u4f11\u5e02\u4e2d\uff0c\u62d2\u7edd\u8ba2\u5355");
            return;
        }
        if (order.getPlayerId() != null) {
            this.crossAssetMacro.recordStockOrder(order.getPlayerId(), order.getAssetId(),
                    order.isBuy() ? "buy" : "sell", order.getTotalQuantity(),
                    order.getLimitPrice());
        }
        OrderBook book = this.orderBooks.get(order.getAssetId());
        if (book == null) {
            return;
        }
        if (this.dgCoolingEffective() && book.isCoolingActive() && order.isMarketOrder()) {
            System.out.println("\u26d4 \u51b7\u5374\u4e2d\u4ec5\u53ef\u63d0\u4ea4\u9650\u4ef7\u5355");
            return;
        }
        if (this.circuitBreakerTripped.getOrDefault(order.getAssetId(), false).booleanValue()) {
            System.out.println("\u26d4 \u7194\u65ad\u4e2d\uff0c\u62d2\u7edd\u8ba2\u5355");
            return;
        }
        if (this.aShareEnabled && order.isBuy() && order.getTotalQuantity() % 100 != 0) {
            System.out.println("\u26d4 \u4e70\u5165\u6570\u91cf\u987b\u4e3a 100 \u80a1\u6574\u6570\u500d");
            return;
        }
        if (this.aShareEnabled && order.isSell() && !order.isSynthetic() && !this.settlement.canSell(order.getPlayerId(), order.getAssetId(), order.getTotalQuantity())) {
            System.out.println("\u26d4 T+1 \u51bb\u7ed3\uff1a\u53ef\u7528\u6570\u91cf\u4e0d\u8db3\uff0c\u4e0d\u53ef\u5356\u51fa");
            return;
        }
        if (order.isBuy() && !order.isSynthetic() && order.getPlayerId() != null && this.currentLevel != null) {
            PlayerEconomy eco;
            ServerPlayer sp = this.findPlayer(this.currentLevel, order.getPlayerId());
            PlayerEconomy playerEconomy = eco = sp != null ? PlayerEconomy.get((Player)sp) : null;
            if (eco != null) {
                double unit;
                double d = unit = order.getLimitPrice() > 0.0 ? order.getLimitPrice() : this.state.getAsset(order.getAssetId()).getLastPrice();
                if (unit > 0.0 && eco.getCash() < unit * (double)order.getTotalQuantity()) {
                    System.out.println("\u26d4 \u73b0\u91d1\u4e0d\u8db3\uff0c\u65e0\u6cd5\u4e70\u5165");
                    return;
                }
            }
        }
        double lastPrice = this.state.getAsset(order.getAssetId()).getLastPrice();
        if (this.aShareEnabled) {
            if (!this.withinAShareLimit(order)) {
                System.out.println("\u26d4 \u4ef7\u683c\u8d85\u51fa\u6da8\u8dcc\u505c\u9650\u5236");
                return;
            }
        } else {
            double limitUp = this.priceEngine.getLimitUp();
            double limitDown = this.priceEngine.getLimitDown();
            if (order.getLimitPrice() > lastPrice * (1.0 + limitUp) || order.getLimitPrice() < lastPrice * (1.0 + limitDown)) {
                System.out.println("\u26d4 \u4ef7\u683c\u8d85\u51fa\u6da8\u8dcc\u505c\u9650\u5236");
                return;
            }
        }
        if (this.dgCageEffective() && order.isLimitOrder() && book.priceCageActive() && !book.priceWithinCage(order.getLimitPrice())) {
            book.noteCageReject();
            ++this.dgCageRejectCount;
            System.out.println("\u26d4 \u7533\u62a5\u4ef7\u8d85\u51fa\u4ef7\u683c\u7b3c\u5b50");
            return;
        }
        book.placeOrder(order);
        this.applyPriceLimitBand(book, order.getAssetId());
        if (this.liqEnabled && !(restingMarkets = book.drainRestingMarketOrders()).isEmpty()) {
            for (Order m : restingMarkets) {
                List<Trade> mts = book.executeMarketableOrder(m);
                if (mts.isEmpty()) continue;
                this.recordLiqTrades(this.currentLevel, order.getAssetId(), mts);
            }
        }
        if (!(trades = book.matchWithSlippage(0.002)).isEmpty()) {
            for (Trade trade : trades) {
                this.attributeTrade(this.currentLevel, trade);
                this.tradeHistory.get(order.getAssetId()).add(trade);
                this.dailyTrades.get(order.getAssetId()).add(trade);
                this.volumeTracker.onTrade(order.getAssetId(), trade.getQuantity());
                lastPrice = trade.getPrice();
                this.updateDailySnapshot(order.getAssetId(), lastPrice, trade.getQuantity());
                this.priceEngine.updateLastPrice(order.getAssetId(), lastPrice);
                this.priceEngine.addTradePoint(order.getAssetId(), new PriceDiscoveryEngine.TradePoint(trade.getPrice(), trade.getQuantity(), order.isBuy()));
                this.lastFillTick.put(order.getAssetId(), this.tickCounter);
            }
        }
    }

    private void runQuantStrategies(ServerLevel level) {
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        for (ServerPlayer sp : players) {
            UUID pid = sp.getUUID();
            for (String assetId : this.state.getAssetIds()) {
                if (this.quantAPI.isEnabled(pid)) {
                    try {
                        List<Order> orders = this.quantAPI.generateOrders(this.state, pid, assetId);
                        if (orders != null) {
                            for (Order o : orders) {
                                this.submitOrder(o);
                            }
                        }
                    }
                    catch (RuntimeException e) {
                        System.out.println("\u26a0\ufe0f \u91cf\u5316\u7b56\u7565\u6267\u884c\u5f02\u5e38\uff08player=" + String.valueOf(pid) + ", asset=" + assetId + "\uff09: " + e.getMessage());
                    }
                }
                for (String scriptId : this.quantScriptEngine.getScriptIds()) {
                    try {
                        List<Order> orders = this.quantScriptEngine.evaluate(scriptId, this.state, pid, assetId);
                        if (orders == null) continue;
                        for (Order o : orders) {
                            this.submitOrder(o);
                        }
                    }
                    catch (RuntimeException e) {
                        System.out.println("\u26a0\ufe0f \u91cf\u5316\u811a\u672c\u6267\u884c\u5f02\u5e38\uff08script=" + scriptId + ", player=" + String.valueOf(pid) + ", asset=" + assetId + "\uff09: " + e.getMessage());
                    }
                }
            }
        }
    }

    private void runPeriodicBuyback() {
        for (String id : this.state.getAssetIds()) {
            AssetSnapshot snap;
            AICompanyData co = this.aiCompanyManager.getAll().stream().filter(c -> c.getStockId().equals(id)).findFirst().orElse(null);
            if (co == null || (snap = this.state.getAsset(id)) == null || snap.getLastPrice() <= 0.0 || !(snap.getLastPrice() < co.getFairValue() * 0.85) || snap.getVolume() < 100L) continue;
            StockBuyback.executeBuyback(id, 100, this);
        }
    }

    public boolean marginDeposit(ServerPlayer player, double amount) {
        if (player == null || amount <= 0.0) {
            return false;
        }
        PlayerEconomy eco = PlayerEconomy.get((Player)player);
        if (eco == null || eco.getCash() < amount) {
            return false;
        }
        eco.setCash(eco.getCash() - amount);
        this.marginManager.deposit(player.getUUID(), amount);
        return true;
    }

    public PriceDiscoveryEngine getPriceEngine() {
        return this.priceEngine;
    }

    public MarketState getState() {
        return this.state;
    }

    public AICompanyManager getAiCompanyManager() {
        return this.aiCompanyManager;
    }

    public Map<String, OrderBook> getOrderBooks() {
        return this.orderBooks;
    }

    public long getInvalidFillGuardHits(String assetId) {
        OrderBook book = this.orderBooks.get(assetId);
        return book == null ? 0L : book.getInvalidFillGuardHits();
    }

    public Map<String, List<Trade>> getTradeHistory() {
        return this.tradeHistory;
    }

    public Map<String, List<Trade>> getDailyTrades() {
        return this.dailyTrades;
    }

    public CompanyManager getCompanyManager() {
        return this.companyManager;
    }

    public BankManager getBankManager() {
        return this.bankManager;
    }

    public BankCardService getBankCardService() {
        return this.bankCardService;
    }

    public PaymentService getPaymentService() {
        return this.paymentService;
    }

    public void refreshCardValidBanks() {
        ArrayList<String> ids = new ArrayList<String>();
        for (Bank b : this.bankManager.getAllBanks()) {
            ids.add(b.getBankId());
        }
        this.bankCardService.setValidBankIds(ids);
    }

    public boolean isBankingEnabled() {
        return this.bankingEnabled;
    }

    public void setBankingEnabled(boolean enabled) {
        this.bankingEnabled = enabled;
    }

    public boolean isMarginEnabled() {
        return this.marginEnabled;
    }

    public void setMarginEnabled(boolean enabled) {
        this.marginEnabled = enabled;
    }

    public boolean isLeverageEnabled() {
        return this.leverageEnabled;
    }

    public void setLeverageEnabled(boolean enabled) {
        this.leverageEnabled = enabled;
    }

    public LeveragedNoteMarket getLeverageMarket() {
        return this.leverageMarket;
    }

    public void creditPlayerCash(ServerLevel level, UUID pid, double amount) {
        this.creditCash(level, pid, amount);
    }

    public boolean hasBankCard(UUID ownerUuid) {
        if (!this.bankingEnabled) {
            return true;
        }
        return this.bankCardService.hasActiveCard(ownerUuid);
    }

    public Government getGovernment() {
        return this.government;
    }

    public CentralBank getCentralBank() {
        return this.centralBank;
    }

    public InterbankMarket getInterbankMarket() {
        return this.bankManager.getInterbankMarket();
    }

    public FinancialWarSystem getFinancialWarSystem() {
        return this.financialWarSystem;
    }

    public CommodityMarket getCommodityMarket() {
        return this.commodityMarket;
    }

    public WorldMacroEngine getWorldMacro() {
        return this.worldMacro;
    }

    public InflationSystem getInflationSystem() {
        return this.inflationSystem;
    }

    public GoldPricingSystem getGoldSystem() {
        return this.goldSystem;
    }

    public boolean isGoldEnabled() {
        return this.goldEnabled;
    }

    public void setGoldEnabled(boolean enabled) {
        this.goldEnabled = enabled;
    }

    public double getGoldPrice() {
        return this.goldSystem.goldPrice();
    }

    public double getGoldMultiplier() {
        return this.goldSystem.goldMultiplier();
    }

    public double getGoldReserve() {
        return this.centralBank.getGoldReserve();
    }

    public double getGoldCash() {
        return this.centralBank.getGoldCash();
    }

    public double getGoldMarketBuyAmount() {
        return this.centralBank.getGoldMarketBuyAmount();
    }

    public TaxSystem getTaxSystem() {
        return this.taxSystem;
    }

    public CreditSystem getCreditSystem() {
        return this.creditSystem;
    }

    public CrisisEngine getCrisisEngine() {
        return this.crisisEngine;
    }

    public SecuritiesManager getSecuritiesManager() {
        return this.securitiesManager;
    }

    public MarketSentiment getSentiment() {
        return this.sentiment;
    }

    public MultiAgentManager getAgentManager() {
        return this.agentManager;
    }

    public QuantAPI getQuantAPI() {
        return this.quantAPI;
    }

    public QuantScriptEngine getQuantScriptEngine() {
        return this.quantScriptEngine;
    }

    public MarginManager getMarginManager() {
        return this.marginManager;
    }

    public long getTickCounter() {
        return this.tickCounter;
    }

    public double getIndexValueEx() {
        return this.excellent24Index.getIndexValue();
    }

    public boolean isCompanySystemEnabled() {
        return this.companySystemEnabled;
    }

    public void setCompanySystemEnabled(boolean enabled) {
        this.companySystemEnabled = enabled;
    }

    public Map<String, Double> getTodayOpen() {
        HashMap<String, Double> map = new HashMap<String, Double>();
        for (String id : this.state.getAssetIds()) {
            AssetSnapshot snap = this.state.getAsset(id);
            if (snap == null) continue;
            map.put(id, snap.getOpen());
        }
        return map;
    }

    public Map<String, Double> getTodayClose() {
        HashMap<String, Double> map = new HashMap<String, Double>();
        for (String id : this.state.getAssetIds()) {
            AssetSnapshot snap = this.state.getAsset(id);
            if (snap == null) continue;
            map.put(id, snap.getLastPrice());
        }
        return map;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("price", (Tag)this.priceEngine.save());
        CompoundTag macroTag = this.macroEngine.save();
        tag.put("macro", (Tag)(macroTag != null ? macroTag : new CompoundTag()));
        CompoundTag fundTag = this.fundEngine.save();
        tag.put("fund", (Tag)(fundTag != null ? fundTag : new CompoundTag()));
        CompoundTag stateTag = this.state.save();
        tag.put("state", (Tag)(stateTag != null ? stateTag : new CompoundTag()));
        CompoundTag companyTag = this.companyManager.save();
        tag.put("companies", (Tag)(companyTag != null ? companyTag : new CompoundTag()));
        CompoundTag worldMacroTag = new CompoundTag();
        WorldMacroPersistence.write(this.worldMacro, worldMacroTag);
        tag.put("worldMacro", (Tag)worldMacroTag);
        ListTag aiList = new ListTag();
        for (AICompanyData c : this.aiCompanyManager.getAll()) {
            CompoundTag ct = new CompoundTag();
            ct.putString("id", c.getStockId());
            ct.putString("name", c.getName());
            ct.putString("industry", c.getIndustry());
            ct.putDouble("asset", c.getNetAssetValue());
            ct.putString("us", c.getUsCode());
            ct.putString("cn", c.getCnCode());
            ct.putInt("totalShares", c.getTotalShares());
            ct.putDouble("netAsset", c.getNetAssetValue());
            ct.putDouble("revenue", c.getRevenue());
            ct.putDouble("revenueGrowth", c.getRevenueGrowth());
            ct.putDouble("netProfit", c.getRecentProfit());
            ct.putDouble("eps", c.getEps());
            ct.putDouble("roe", c.getRoe());
            ct.putDouble("dividendYield", c.getDividendYield());
            ct.putDouble("debtRatio", c.getDebtRatio());
            ct.putDouble("ipoPrice", c.getIpoPrice());
            ct.putDouble("fairValue", c.getFairValue());
            ct.putDouble("growthStage", c.getGrowthStage());
            ct.putDouble("profitabilityScore", c.getProfitabilityScore());
            ct.putDouble("financialHealthScore", c.getFinancialHealthScore());
            ct.putDouble("totalAssets", c.getTotalAssets());
            ct.putDouble("totalEquity", c.getTotalEquity());
            ct.putDouble("liabilities", c.getLiabilities());
            ct.putDouble("netMargin", c.getNetMargin());
            ct.putDouble("assetTurnover", c.getAssetTurnover());
            ct.putDouble("equityMultiplier", c.getEquityMultiplier());
            ct.putDouble("roa", c.getRoa());
            aiList.add(ct);
        }
        tag.put("aiCompanies", (Tag)aiList);
        CompoundTag indexTag = new CompoundTag();
        indexTag.putDouble("value", this.excellent24Index.getIndexValue());
        indexTag.putLong("lastRebalance", this.excellent24Index.getLastRebalanceTime());
        ListTag constituentsTag = new ListTag();
        for (String string : this.excellent24Index.getConstituents()) {
            CompoundTag compoundTag = new CompoundTag();
            compoundTag.putString("id", string);
            constituentsTag.add(compoundTag);
        }
        indexTag.put("constituents", (Tag)constituentsTag);
        tag.put(INDEX_ID, (Tag)indexTag);
        tag.putLong("engineSeed", this.engineSeed);
        tag.put("fiat", (Tag)this.saveFiat());
        tag.put("orderFlow", (Tag)this.orderFlowModel.save());
        tag.put("volumeTracker", (Tag)this.volumeTracker.save());
        CompoundTag books = new CompoundTag();
        for (Map.Entry<String, OrderBook> entry : this.orderBooks.entrySet()) {
            books.put(entry.getKey(), (Tag)entry.getValue().save());
        }
        tag.put("orderBooks", (Tag)books);
        tag.put("tradingCalendar", (Tag)this.calendar.save());
        tag.putLong("tickCounter", this.tickCounter);
        tag.put("t1Ledger", (Tag)this.settlement.save());
        tag.put("quant", (Tag)this.quantAPI.save());
        tag.put("quantScripts", (Tag)this.quantScriptEngine.save());
        tag.put("margin", (Tag)this.marginManager.save());
        if (this.leverageEnabled) {
            tag.put("leverage", (Tag)this.leverageMarket.save());
        }
        tag.put("crisis", (Tag)this.crisisEngine.save());
        CompoundTag compoundTag = new CompoundTag();
        for (Map.Entry<UUID, PendingEconomyChange> entry : this.pendingEconomy.entrySet()) {
            compoundTag.put(entry.getKey().toString(), (Tag)entry.getValue().write());
        }
        tag.put("pendingEconomy", (Tag)compoundTag);
        CompoundTag compoundTag2 = new CompoundTag();
        for (Map.Entry<UUID, PendingTradeSettlement> entry : this.pendingTradeSettlement.entrySet()) {
            compoundTag2.put(entry.getKey().toString(), (Tag)entry.getValue().write());
        }
        tag.put("pendingTradeSettlement", (Tag)compoundTag2);
        ListTag listTag = new ListTag();
        for (UUID u : this.fundedPlayers) {
            CompoundTag ut = new CompoundTag();
            ut.putUUID("uuid", u);
            listTag.add(ut);
        }
        tag.put("fundedPlayers", (Tag)listTag);
        if (this.commodityEnabled) {
            tag.put("commodity", (Tag)this.commodityMarket.save());
        }
        if (this.futuresEnabled) {
            tag.put("futures", (Tag)this.futuresMarket.save());
            tag.put("czceFutures", (Tag)this.czceFutures.save());
            tag.put("czceOptions", (Tag)this.czceOptions.save());
        }
        tag.put("bankCards", (Tag)this.bankCardService.save());
        tag.put("payments", (Tag)this.paymentService.save());
        if (this.goldEnabled) {
            tag.put("gold", (Tag)this.goldSystem.save());
        }
        tag.put("meso", (Tag)this.mesoLayer.save());
        CompoundTag compoundTag3 = new CompoundTag();
        CompoundTag rehangT = new CompoundTag();
        for (Map.Entry entry : this.lastRehangTick.entrySet()) {
            rehangT.putLong((String)entry.getKey(), ((Long)entry.getValue()).longValue());
        }
        CompoundTag auctionT = new CompoundTag();
        for (Map.Entry<String, Long> entry : this.lastAuctionTick.entrySet()) {
            auctionT.putLong(entry.getKey(), entry.getValue().longValue());
        }
        compoundTag3.put("lastRehangTick", (Tag)rehangT);
        compoundTag3.put("lastAuctionTick", (Tag)auctionT);
        compoundTag3.put("marketMakers", (Tag)this.agentManager.saveMarketMakers());
        tag.put("liq", (Tag)compoundTag3);
        CompoundTag compoundTag4 = new CompoundTag();
        CompoundTag compoundTag5 = new CompoundTag();
        for (Map.Entry<String, DgCoolingState> e : this.dgCooling.entrySet()) {
            DgCoolingState cs = e.getValue();
            if (cs == null) continue;
            CompoundTag gt = new CompoundTag();
            gt.putBoolean("coolingActive", cs.active);
            gt.putLong("windowEndTick", cs.windowEndTick);
            gt.putInt("windowRound", cs.windowRound);
            gt.putInt("consecutiveFailures", cs.consecutiveFailures);
            gt.putDouble("lastEq", cs.lastEq);
            gt.putDouble("lastPStar", cs.lastPStar);
            gt.putString("reason", cs.reason != null ? cs.reason.name() : DgHaltReason.LEGACY_NO_FILL.name());
            gt.putDouble("eq", cs.lastEq);
            gt.putDouble("dev", cs.lastDev);
            gt.putLong("lastPStarVolume", cs.lastPStarVolume);
            compoundTag5.put(e.getKey(), (Tag)gt);
        }
        compoundTag4.put("assets", (Tag)compoundTag5);
        tag.put("guard", (Tag)compoundTag4);
        tag.put("capm", (Tag)this.capmModel.save());
        return tag;
    }

    private CompoundTag saveFiat() {
        CompoundTag fiat = new CompoundTag();
        CompoundTag cb = new CompoundTag();
        cb.putDouble("baseMoney", this.centralBank.getBaseMoney());
        cb.putDouble("discountRate", this.centralBank.getDiscountRate());
        cb.putDouble("reserveRequirement", this.centralBank.getReserveRequirement());
        cb.putDouble("goldReserve", this.centralBank.getGoldReserve());
        cb.putDouble("goldCash", this.centralBank.getGoldCash());
        cb.putDouble("goldMarketBuyAmount", this.centralBank.getGoldMarketBuyAmount());
        cb.putDouble("goldHeldCashOutflow", this.centralBank.getGoldHeldCashOutflow());
        if (this.centralBank.isPlayerOwned()) {
            cb.putUUID("playerOwner", this.centralBank.getPlayerOwner());
            cb.putString("createdBy", this.centralBank.getCreatedBy() != null ? this.centralBank.getCreatedBy() : "");
            cb.putLong("createdTick", this.centralBank.getCreatedTick());
        }
        ListTag brList = new ListTag();
        for (Map.Entry<String, Double> e : this.centralBank.getBankReserves().entrySet()) {
            CompoundTag bt = new CompoundTag();
            bt.putString("bankId", e.getKey());
            bt.putDouble("amount", e.getValue().doubleValue());
            brList.add(bt);
        }
        cb.put("bankReserves", (Tag)brList);
        cb.put("balanceSheet", (Tag)this.centralBank.saveBalanceSheet());
        cb.put("policy", (Tag)this.centralBank.savePolicyState());
        fiat.put("centralBank", (Tag)cb);
        fiat.put("interbank", (Tag)this.bankManager.getInterbankMarket().save());
        CompoundTag aggTag = new CompoundTag();
        MonetaryAggregator agg = this.bankManager.getMonetaryAggregator();
        aggTag.putLong("refreshTick", agg.getAggregateRefreshTick());
        aggTag.putDouble("m0", agg.getM0());
        aggTag.putDouble("m1", agg.getM1());
        aggTag.putDouble("m2", agg.getM2());
        aggTag.putDouble("b", agg.getBaseMoneyB());
        aggTag.putDouble("multiplierM", agg.getMoneyMultiplierM());
        aggTag.putDouble("divergenceAccount", agg.getDivergenceAccount());
        aggTag.putLong("divergenceHits", agg.getDivergenceHits());
        fiat.put("monetaryAgg", (Tag)aggTag);
        fiat.put("bankManager", (Tag)this.bankManager.saveToNBT());
        CompoundTag gov = new CompoundTag();
        gov.putDouble("treasury", this.government.getTreasury());
        gov.putDouble("totalDebt", this.government.getTotalDebt());
        gov.putDouble("taxRate", this.government.getTaxRate());
        gov.putDouble("totalTaxCollected", this.government.getTotalTaxCollected());
        gov.putDouble("totalSpent", this.government.getTotalSpent());
        fiat.put("government", (Tag)gov);
        CompoundTag tax = new CompoundTag();
        tax.putDouble("totalCollected", this.taxSystem.getTotalCollected());
        tax.putDouble("totalRecycled", this.taxSystem.getTotalRecycled());
        fiat.put("taxSystem", (Tag)tax);
        CompoundTag inf = new CompoundTag();
        inf.putDouble("prevM2", this.inflationSystem.getPrevM2());
        inf.putDouble("moneySupply", this.inflationSystem.getMoneySupply());
        inf.putDouble("priceIndex", this.inflationSystem.getPriceIndex());
        inf.putDouble("inflationRate", this.inflationSystem.getInflationRate());
        fiat.put("inflation", (Tag)inf);
        fiat.put("superstructure", (Tag)this.saveSuperstructure());
        return fiat;
    }

    private CompoundTag saveSuperstructure() {
        CompoundTag sup = new CompoundTag();
        sup.put("roleSystem", (Tag)RoleSystem.getInstance().saveToNBT());
        CentralBankerGuardrail cb = this.centralBank.getGuardrail();
        CompoundTag cbGuard = new CompoundTag();
        cbGuard.putDouble("currentRate", cb.getCurrentRate());
        cbGuard.putDouble("inflationRate", cb.getInflationRate());
        cbGuard.putDouble("economicHealth", cb.getEconomicHealth());
        cbGuard.putInt("deviationStreak", cb.getDeviationStreak());
        cbGuard.putInt("highInflationStreak", cb.getHighInflationStreak());
        cbGuard.putBoolean("centralBankerOnline", cb.isCentralBankerOnline());
        cbGuard.putBoolean("autopilotEngaged", cb.isAutopilotEngaged());
        sup.put("centralBankerGuardrail", (Tag)cbGuard);
        FinanceMinisterGuardrail fm = this.government.getGuardrail();
        CompoundTag fmGuard = new CompoundTag();
        fmGuard.putBoolean("financeMinisterOnline", fm.isFinanceMinisterOnline());
        fmGuard.putInt("overspendStreak", fm.getOverspendStreak());
        fmGuard.putBoolean("autopilotEngaged", fm.isAutopilotEngaged());
        fmGuard.putDouble("debtCeiling", this.government.getDebtCeiling());
        fmGuard.putDouble("annualDeficitLimit", this.government.getAnnualDeficitLimit());
        fmGuard.putDouble("currentYearNetDeficit", this.government.getCurrentYearNetDeficit());
        sup.put("financeMinisterGuardrail", (Tag)fmGuard);
        return sup;
    }

    public static MarketEngine load(CompoundTag tag) {
        long seed = tag.contains("engineSeed") ? tag.getLong("engineSeed") : 0L;
        MarketEngine engine = new MarketEngine(seed);
        engine.loadInternal(tag);
        return engine;
    }

    private void loadInternal(CompoundTag tag) {
        CompoundTag guard;
        this.priceEngine.load(tag.getCompound("price"));
        this.macroEngine.load(tag.getCompound("macro"));
        this.fundEngine.load(tag.getCompound("fund"));
        this.state.load(tag.getCompound("state"));
        this.companyManager.load(tag.getCompound("companies"));
        if (tag.contains("worldMacro")) {
            WorldMacroPersistence.read(this.worldMacro, tag.getCompound("worldMacro"));
        }
        if (tag.contains("tradingCalendar")) {
            this.calendar.load(tag.getCompound("tradingCalendar"));
        }
        if (tag.contains("tickCounter")) {
            this.tickCounter = tag.getLong("tickCounter");
        }
        this.tickCounter = Math.max(this.tickCounter, this.calendar.getTick());
        if (tag.contains("t1Ledger")) {
            this.settlement.load(tag.getCompound("t1Ledger"));
        }
        ListTag aiList = tag.getList("aiCompanies", 10);
        for (int i = 0; i < aiList.size(); ++i) {
            CompoundTag ct = aiList.getCompound(i);
            String id = ct.getString("id");
            String name = ct.getString("name");
            String industry = ct.getString("industry");
            double asset = ct.getDouble("asset");
            String us = ct.getString("us");
            String cn = ct.getString("cn");
            AICompanyData company = new AICompanyData(id, name, industry, asset, 0.02, 0.05, us, cn);
            if (ct.contains("equityMultiplier")) {
                company.restoreDupont(ct.getDouble("totalAssets"), ct.getDouble("totalEquity"), ct.getDouble("liabilities"), ct.getDouble("netMargin"), ct.getDouble("assetTurnover"), ct.getDouble("equityMultiplier"), ct.getDouble("roa"), ct.getDouble("revenue"), ct.getDouble("revenueGrowth"), ct.getDouble("netProfit"), ct.getDouble("eps"), ct.getDouble("roe"), ct.getDouble("dividendYield"), ct.getDouble("debtRatio"), ct.getDouble("ipoPrice"), ct.getDouble("fairValue"), ct.getDouble("growthStage"), ct.getDouble("profitabilityScore"), ct.getDouble("financialHealthScore"), ct.getInt("totalShares"));
            } else if (ct.contains("fairValue")) {
                company.restoreFundamentals(ct.getDouble("netAsset"), ct.getDouble("revenue"), ct.getDouble("revenueGrowth"), ct.getDouble("netProfit"), ct.getDouble("eps"), ct.getDouble("roe"), ct.getDouble("dividendYield"), ct.getDouble("debtRatio"), ct.getDouble("ipoPrice"), ct.getDouble("fairValue"), ct.getDouble("growthStage"), ct.getDouble("profitabilityScore"), ct.getDouble("financialHealthScore"), ct.getInt("totalShares"));
            }
            this.aiCompanyManager.addCompany(company);
        }
        HashSet<String> poolCodes = new HashSet<String>();
        for (AICompanyData c : this.aiCompanyManager.getAll()) {
            poolCodes.add(c.getCnCode());
        }
        for (CompanyData co : this.companyManager.getAllCompanies()) {
            if (co.getStockId() == null) continue;
            poolCodes.add(co.getStockId());
        }
        this.aStockCodePool.rebuildUsed(poolCodes);
        CompoundTag indexTag = tag.getCompound(INDEX_ID);
        this.excellent24Index.setLastRebalanceTime(indexTag.getLong("lastRebalance"));
        this.excellent24Index.getConstituents().clear();
        ListTag constituentsTag = indexTag.getList("constituents", 10);
        HashSet<String> seenConstituents = new HashSet<String>();
        for (int i = 0; i < constituentsTag.size(); ++i) {
            String id = constituentsTag.getCompound(i).getString("id");
            if (id == null || id.isEmpty() || !seenConstituents.add(id)) continue;
            this.excellent24Index.getConstituents().add(id);
        }
        this.excellent24Index.updateValue(this.state);
        for (String id : this.state.getAssetIds()) {
            this.orderBooks.putIfAbsent(id, this.newOrderBook(id));
            this.tradeHistory.putIfAbsent(id, new ArrayList());
            this.dailyTrades.putIfAbsent(id, new ArrayList());
            this.pendingSettlements.putIfAbsent(id, new ArrayList());
            this.circuitBreakerTripped.putIfAbsent(id, false);
            this.circuitBreakerRecoveryTime.putIfAbsent(id, 0L);
            this.dgCooling.putIfAbsent(id, new DgCoolingState());
            this.hasTodayTrade.putIfAbsent(id, false);
            this.todayOpen.putIfAbsent(id, 0.0);
            this.todayClose.putIfAbsent(id, 0.0);
        }
        if (tag.contains("orderFlow")) {
            this.orderFlowModel.load(tag.getCompound("orderFlow"));
        }
        if (tag.contains("volumeTracker")) {
            this.volumeTracker.load(tag.getCompound("volumeTracker"));
        }
        if (tag.contains("orderBooks")) {
            CompoundTag books = tag.getCompound("orderBooks");
            for (String id : this.state.getAssetIds()) {
                OrderBook book = this.orderBooks.get(id);
                if (book == null || !books.contains(id)) continue;
                book.load(books.getCompound(id));
            }
        }
        for (String id : this.state.getAssetIds()) {
            this.agentManager.initializeAgents(id, 20 + this.engineRng.nextInt(20));
        }
        if (tag.contains("meso")) {
            this.mesoLayer.load(tag.getCompound("meso"));
        } else {
            this.mesoLayer.initialize(this.engineSeed);
        }
        if (tag.contains("quant")) {
            this.quantAPI.load(tag.getCompound("quant"));
        }
        if (tag.contains("quantScripts")) {
            this.quantScriptEngine.load(tag.getCompound("quantScripts"));
        }
        if (tag.contains("margin")) {
            this.marginManager.load(tag.getCompound("margin"));
        }
        if (tag.contains("leverage")) {
            this.leverageMarket.load(tag.getCompound("leverage"));
        }
        if (tag.contains("crisis")) {
            this.crisisEngine.load(tag.getCompound("crisis"));
        }
        if (tag.contains("pendingEconomy")) {
            CompoundTag pend = tag.getCompound("pendingEconomy");
            for (String key2 : pend.getAllKeys()) {
                try {
                    this.pendingEconomy.put(UUID.fromString(key2), PendingEconomyChange.read(pend.getCompound(key2)));
                }
                catch (IllegalArgumentException book) {}
            }
        }
        if (tag.contains("pendingTradeSettlement")) {
            CompoundTag pendTrade = tag.getCompound("pendingTradeSettlement");
            for (String key2 : pendTrade.getAllKeys()) {
                try {
                    this.pendingTradeSettlement.put(UUID.fromString(key2), PendingTradeSettlement.read(pendTrade.getCompound(key2)));
                }
                catch (IllegalArgumentException book) {}
            }
        }
        if (tag.contains("fundedPlayers")) {
            ListTag fundedList = tag.getList("fundedPlayers", 10);
            for (int i = 0; i < fundedList.size(); ++i) {
                CompoundTag ut = fundedList.getCompound(i);
                if (!ut.contains("uuid")) continue;
                try {
                    this.fundedPlayers.add(ut.getUUID("uuid"));
                    continue;
                }
                catch (IllegalArgumentException book) {
                    // empty catch block
                }
            }
        }
        this.loadFiat(tag.getCompound("fiat"));
        this.refreshCardValidBanks();
        if (this.commodityEnabled) {
            try {
                CommodityRegistry.autoRegister(this.commodityMarket);
                CommodityRegistry.loadFromJson(this.commodityMarket);
                CommodityRegistry.registerFluids(this.commodityMarket);
                if (tag.contains("commodity")) {
                    this.commodityMarket.load(tag.getCompound("commodity"));
                }
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u5546\u54c1\u5e02\u573a\u8bfb\u6863\u91cd\u5efa\u5f02\u5e38\uff08\u4e0d\u963b\u65ad load\uff09: " + e.getMessage());
            }
        }
        if (this.futuresEnabled && !this.isCzceFuturesMode()) {
            try {
                this.futuresMarket.registerUnderlyings(this);
                if (tag.contains("futures")) {
                    this.futuresMarket.load(tag.getCompound("futures"));
                }
            }
            catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f \u671f\u8d27\u5e02\u573a\u8bfb\u6863\u91cd\u5efa\u5f02\u5e38\uff08\u4e0d\u963b\u65ad load\uff09: " + e.getMessage());
            }
        }
        if (this.futuresEnabled && this.isCzceFuturesMode() && tag.contains("czceFutures")) {
            try {
                this.czceFutures.load(tag.getCompound("czceFutures"));
                if (tag.contains("czceOptions")) {
                    this.czceOptions.load(tag.getCompound("czceOptions"));
                }
            } catch (RuntimeException e) {
                System.out.println("\u26a0\ufe0f CZCE \u671f\u8d27\u8bfb\u6863\u5f02\u5e38\uff08\u4e0d\u963b\u65ad load\uff09: " + e.getMessage());
            }
        }
        if (tag.contains("bankCards")) {
            this.bankCardService.load(tag.getCompound("bankCards"));
        }
        if (tag.contains("payments")) {
            this.paymentService.load(tag.getCompound("payments"));
        }
        if (this.goldEnabled && tag.contains("gold")) {
            this.goldSystem.load(tag.getCompound("gold"));
            this.centralBank.setCurrentGoldPrice(this.goldSystem.goldPrice());
        }
        if (tag.contains("liq")) {
            CompoundTag liq = tag.getCompound("liq");
            if (liq.contains("lastRehangTick")) {
                CompoundTag t = liq.getCompound("lastRehangTick");
                for (String k : t.getAllKeys()) {
                    this.lastRehangTick.put(k, t.getLong(k));
                }
            }
            if (liq.contains("lastAuctionTick")) {
                CompoundTag t = liq.getCompound("lastAuctionTick");
                for (String k : t.getAllKeys()) {
                    this.lastAuctionTick.put(k, t.getLong(k));
                }
            }
            if (liq.contains("marketMakers")) {
                this.agentManager.loadMarketMakers(liq.getCompound("marketMakers"));
            }
        }
        if (tag.contains("capm")) {
            this.capmModel.load(tag.getCompound("capm"));
        }
        if (tag.contains("guard") && (guard = tag.getCompound("guard")).contains("assets")) {
            CompoundTag assets = guard.getCompound("assets");
            for (String k : assets.getAllKeys()) {
                CompoundTag gt = assets.getCompound(k);
                DgCoolingState cs = this.dgCooling.computeIfAbsent(k, key -> new DgCoolingState());
                cs.active = gt.contains("coolingActive") && gt.getBoolean("coolingActive");
                cs.windowEndTick = gt.contains("windowEndTick") ? gt.getLong("windowEndTick") : 0L;
                cs.windowRound = gt.contains("windowRound") ? gt.getInt("windowRound") : 0;
                cs.consecutiveFailures = gt.contains("consecutiveFailures") ? gt.getInt("consecutiveFailures") : 0;
                cs.lastEq = gt.contains("lastEq") ? gt.getDouble("lastEq") : 0.0;
                cs.lastPStar = gt.contains("lastPStar") ? gt.getDouble("lastPStar") : 0.0;
                cs.reason = DgHaltReason.LEGACY_NO_FILL;
                if (gt.contains("reason")) {
                    try {
                        cs.reason = DgHaltReason.valueOf(gt.getString("reason"));
                    }
                    catch (RuntimeException ignore) {
                        cs.reason = DgHaltReason.LEGACY_NO_FILL;
                    }
                }
                if (gt.contains("eq")) {
                    cs.lastEq = gt.getDouble("eq");
                }
                cs.lastDev = gt.contains("dev") ? gt.getDouble("dev") : 0.0;
                cs.lastPStarVolume = gt.contains("lastPStarVolume") ? gt.getLong("lastPStarVolume") : 0L;
                cs.justStarted = false;
                OrderBook bk = this.orderBooks.get(k);
                if (bk == null) continue;
                bk.setCoolingWindowRound(cs.windowRound);
                bk.setConsecutiveMatchFailures(cs.consecutiveFailures);
                if (cs.active) {
                    bk.beginCooling(this.tickCounter);
                    continue;
                }
                bk.endCooling();
            }
        }
        if (this.cmCapmEffective()) {
            this.recomputeCapmFair();
        }
        System.out.println("\ud83d\udcc2 MarketEngine \u52a0\u8f7d\u5b8c\u6210\uff0c\u8d44\u4ea7\u6570: " + this.state.getAssetIds().size());
    }

    private void loadFiat(CompoundTag fiat) {
        CompoundTag inf;
        CompoundTag tax;
        CompoundTag gov;
        if (fiat == null || fiat.isEmpty()) {
            return;
        }
        if (fiat.contains("centralBank")) {
            CompoundTag cb = fiat.getCompound("centralBank");
            if (cb.contains("baseMoney")) {
                this.centralBank.setBaseMoney(cb.getDouble("baseMoney"));
            }
            if (cb.contains("discountRate")) {
                this.centralBank.setDiscountRate(cb.getDouble("discountRate"));
            }
            if (cb.contains("reserveRequirement")) {
                this.centralBank.setReserveRequirement(cb.getDouble("reserveRequirement"));
            }
            if (cb.contains("goldReserve") || cb.contains("goldCash")) {
                this.centralBank.restoreGoldState(cb.getDouble("goldReserve"), cb.getDouble("goldCash"), cb.getDouble("goldMarketBuyAmount"), cb.getDouble("goldHeldCashOutflow"));
            }
            if (cb.contains("playerOwner")) {
                UUID owner = cb.getUUID("playerOwner");
                String by = cb.contains("createdBy") ? cb.getString("createdBy") : null;
                long tick = cb.contains("createdTick") ? cb.getLong("createdTick") : 0L;
                this.centralBank.setPlayerOwner(owner, by, tick);
            }
            if (cb.contains("bankReserves")) {
                HashMap<String, Double> br = new HashMap<String, Double>();
                ListTag brList = cb.getList("bankReserves", 10);
                for (int i = 0; i < brList.size(); ++i) {
                    CompoundTag bt = brList.getCompound(i);
                    br.put(bt.getString("bankId"), bt.getDouble("amount"));
                }
                this.centralBank.setBankReserves(br);
            }
            if (cb.contains("balanceSheet")) {
                this.centralBank.loadBalanceSheet(cb.getCompound("balanceSheet"));
            }
            if (cb.contains("policy")) {
                this.centralBank.loadPolicyState(cb.getCompound("policy"));
            }
        }
        if (fiat.contains("interbank")) {
            this.bankManager.getInterbankMarket().load(fiat.getCompound("interbank"));
        }
        if (fiat.contains("bankManager")) {
            this.bankManager.loadFromNBT(fiat.getCompound("bankManager"));
        }
        if (fiat.contains("government") && (gov = fiat.getCompound("government")).contains("treasury")) {
            this.government.restoreState(gov.getDouble("treasury"), gov.getDouble("totalDebt"), gov.getDouble("taxRate"), gov.getDouble("totalTaxCollected"), gov.getDouble("totalSpent"));
        }
        if (fiat.contains("taxSystem") && (tax = fiat.getCompound("taxSystem")).contains("totalCollected")) {
            this.taxSystem.restoreState(tax.getDouble("totalCollected"), tax.getDouble("totalRecycled"));
        }
        if (fiat.contains("inflation") && (inf = fiat.getCompound("inflation")).contains("prevM2")) {
            this.inflationSystem.restore(inf.getDouble("prevM2"), inf.getDouble("moneySupply"), inf.getDouble("priceIndex"), inf.getDouble("inflationRate"));
        }
        if (fiat.contains("superstructure")) {
            this.loadSuperstructure(fiat.getCompound("superstructure"));
        }
    }

    private void loadSuperstructure(CompoundTag sup) {
        CompoundTag g;
        if (sup == null || sup.isEmpty()) {
            return;
        }
        if (sup.contains("roleSystem")) {
            RoleSystem.getInstance().loadFromNBT(sup.getCompound("roleSystem"));
        }
        if (sup.contains("centralBankerGuardrail")) {
            g = sup.getCompound("centralBankerGuardrail");
            CentralBankerGuardrail cb = this.centralBank.getGuardrail();
            if (g.contains("inflationRate") && g.contains("economicHealth")) {
                cb.updateInputs(g.getDouble("inflationRate"), g.getDouble("economicHealth"));
            }
            if (g.contains("centralBankerOnline")) {
                cb.setCentralBankerOnline(g.getBoolean("centralBankerOnline"));
            }
            if (g.contains("currentRate")) {
                this.centralBank.setDiscountRate(g.getDouble("currentRate"));
            }
        }
        if (sup.contains("financeMinisterGuardrail")) {
            g = sup.getCompound("financeMinisterGuardrail");
            FinanceMinisterGuardrail fm = this.government.getGuardrail();
            if (g.contains("financeMinisterOnline")) {
                fm.setFinanceMinisterOnline(g.getBoolean("financeMinisterOnline"));
            }
            if (g.contains("debtCeiling")) {
                this.government.setDebtCeiling(g.getDouble("debtCeiling"));
            }
            if (g.contains("annualDeficitLimit")) {
                this.government.setAnnualDeficitLimit(g.getDouble("annualDeficitLimit"));
            }
        }
    }

    /*
     * Enabled aggressive exception aggregation
     */
    public static boolean hasH2EngineState() {
        Connection c = H2DatabaseManager.getConnection();
        if (c == null) {
            return false;
        }
        String sql = "SELECT COUNT(*) FROM engine_kv WHERE k = ?";
        try (PreparedStatement ps = c.prepareStatement(sql);){
            boolean bl;
            block15: {
                ps.setString(1, ENGINE_KV_KEY);
                ResultSet rs = ps.executeQuery();
                try {
                    boolean bl2 = bl = rs.next() && rs.getLong(1) > 0L;
                    if (rs == null) break block15;
                }
                catch (Throwable throwable) {
                    if (rs != null) {
                        try {
                            rs.close();
                        }
                        catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                rs.close();
            }
            return bl;
        }
        catch (SQLException e) {
            System.err.println("\u274c \u68c0\u67e5 H2 \u5f15\u64ce\u72b6\u6001\u5931\u8d25: " + e.getMessage());
            return false;
        }
    }

    public void saveToH2() {
        Connection c = H2DatabaseManager.getConnection();
        if (c == null) {
            System.err.println("\u26a0\ufe0f H2 \u672a\u6253\u5f00\uff0c\u8df3\u8fc7\u5f15\u64ce\u4fdd\u5b58\u5230 H2");
            return;
        }
        try {
            CompoundTag tag = this.save();
            byte[] bytes = MarketEngine.toCompressedBytes(tag);
            String sql = "MERGE INTO engine_kv (k, v, dtype) KEY(k) VALUES (?, ?, 'nbt-base64')";
            try (PreparedStatement ps = c.prepareStatement(sql);){
                ps.setString(1, ENGINE_KV_KEY);
                ps.setString(2, Base64.getEncoder().encodeToString(bytes));
                ps.executeUpdate();
            }
            this.saveRelationsToH2(c);
            System.out.println("\ud83d\udcbe \u5f15\u64ce\u72b6\u6001\u5df2\u4fdd\u5b58\u5230 H2\uff0c\u8d44\u4ea7\u6570: " + this.state.getAssetIds().size());
        }
        catch (Exception e) {
            System.err.println("\u274c \u4fdd\u5b58\u5f15\u64ce\u5230 H2 \u5931\u8d25: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /*
     * Enabled aggressive exception aggregation
     */
    public static MarketEngine loadFromH2() {
        Connection c = H2DatabaseManager.getConnection();
        if (c == null) {
            return null;
        }
        String sql = "SELECT v FROM engine_kv WHERE k = ?";
        try (PreparedStatement ps = c.prepareStatement(sql);){
            MarketEngine marketEngine;
            block23: {
                String b64;
                ResultSet rs;
                block21: {
                    MarketEngine marketEngine2;
                    block22: {
                        block19: {
                            MarketEngine marketEngine3;
                            block20: {
                                ps.setString(1, ENGINE_KV_KEY);
                                rs = ps.executeQuery();
                                try {
                                    if (rs.next()) break block19;
                                    marketEngine3 = null;
                                    if (rs == null) break block20;
                                }
                                catch (Throwable throwable) {
                                    if (rs != null) {
                                        try {
                                            rs.close();
                                        }
                                        catch (Throwable throwable2) {
                                            throwable.addSuppressed(throwable2);
                                        }
                                    }
                                    throw throwable;
                                }
                                rs.close();
                            }
                            return marketEngine3;
                        }
                        b64 = rs.getString("v");
                        if (b64 != null && !b64.isEmpty()) break block21;
                        marketEngine2 = null;
                        if (rs == null) break block22;
                        rs.close();
                    }
                    return marketEngine2;
                }
                byte[] bytes = Base64.getDecoder().decode(b64);
                CompoundTag tag = NbtIo.readCompressed((InputStream)new ByteArrayInputStream(bytes), (NbtAccounter)NbtAccounter.unlimitedHeap());
                long seed = tag.contains("engineSeed") ? tag.getLong("engineSeed") : 0L;
                MarketEngine engine = new MarketEngine(seed);
                engine.loadInternal(tag);
                marketEngine = engine;
                if (rs == null) break block23;
                rs.close();
            }
            return marketEngine;
        }
        catch (Exception e) {
            System.err.println("\u274c \u4ece H2 \u8bfb\u53d6\u5f15\u64ce\u72b6\u6001\u5931\u8d25: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static byte[] toCompressedBytes(CompoundTag tag) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        NbtIo.writeCompressed((CompoundTag)tag, (OutputStream)bos);
        return bos.toByteArray();
    }

    private void saveRelationsToH2(Connection c) throws SQLException {
        String aiSql = "MERGE INTO ai_company (id, name, industry, net_asset, us_code, cn_code, ipo_price, eps, roe, fair_value, revenue, revenue_growth, net_profit, growth_stage, book_value_per_share, dividend_yield, debt_ratio, profitability_score, financial_health_score, total_shares) KEY(id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(aiSql);){
            for (AICompanyData co : this.aiCompanyManager.getAll()) {
                ps.setString(1, co.getStockId());
                ps.setString(2, co.getName());
                ps.setString(3, co.getIndustry());
                ps.setDouble(4, co.getNetAssetValue());
                ps.setString(5, co.getUsCode());
                ps.setString(6, co.getCnCode());
                ps.setDouble(7, co.getIpoPrice());
                ps.setDouble(8, co.getEps());
                ps.setDouble(9, co.getRoe());
                ps.setDouble(10, co.getFairValue());
                ps.setDouble(11, co.getRevenue());
                ps.setDouble(12, co.getRevenueGrowth());
                ps.setDouble(13, co.getRecentProfit());
                ps.setDouble(14, co.getGrowthStage());
                ps.setDouble(15, co.getBookValuePerShare());
                ps.setDouble(16, co.getDividendYield());
                ps.setDouble(17, co.getDebtRatio());
                ps.setDouble(18, co.getProfitabilityScore());
                ps.setDouble(19, co.getFinancialHealthScore());
                ps.setInt(20, co.getTotalShares());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        String idxSql = "MERGE INTO index_state (id, idx_value, last_rebalance) KEY(id) VALUES (?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(idxSql);){
            ps.setString(1, INDEX_ID);
            ps.setDouble(2, this.excellent24Index.getIndexValue());
            ps.setLong(3, this.excellent24Index.getLastRebalanceTime());
            ps.executeUpdate();
        }
        try (Statement st = c.createStatement();){
            st.executeUpdate("DELETE FROM index_constituent WHERE index_id = 'excellent24'");
        }
        String conSql = "INSERT INTO index_constituent (index_id, asset_id, seq) VALUES (?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(conSql);){
            int seq = 0;
            HashSet<String> seen = new HashSet<String>();
            for (String id : this.excellent24Index.getConstituents()) {
                if (id == null || id.isEmpty() || !seen.add(id)) continue;
                ps.setString(1, INDEX_ID);
                ps.setString(2, id);
                ps.setInt(3, seq++);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        String assetSql = "MERGE INTO asset (asset_id, last_price, daily_open, daily_close, gbm_price, nav, industry) KEY(asset_id) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(assetSql);){
            for (String id : this.state.getAssetIds()) {
                AssetSnapshot snap = this.state.getAsset(id);
                AICompanyData co = this.aiCompanyManager.getAll().stream().filter(x -> x.getStockId().equals(id)).findFirst().orElse(null);
                ps.setString(1, id);
                ps.setDouble(2, snap != null ? snap.getLastPrice() : this.priceEngine.getLastPrice(id));
                ps.setDouble(3, this.priceEngine.getDailyOpen(id));
                ps.setDouble(4, this.priceEngine.getDailyClose(id));
                ps.setDouble(5, this.priceEngine.getGbmPrice(id));
                ps.setDouble(6, snap != null ? snap.getNav() : 0.0);
                ps.setString(7, co != null ? co.getIndustry() : null);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (Statement st = c.createStatement();){
            st.executeUpdate("DELETE FROM company");
        }
        String compSql = "INSERT INTO company (stock_id, owner_uuid, name, level, status, net_asset, recent_profit, total_shares, ipo_price, founded_time, listed_time, retained_earnings, last_report_time, last_dividend_time, float_shares, ipo_state, subscription_raised, dividend_paid_total, low_price_days, inventory_value, productive_asset_value, appraised_asset_value, replacement_cost, asset_depreciation, asset_utilization, asset_risk, asset_quality, machine_count, multiblock_count, production_output_value, production_input_value, production_throughput, production_utilization, inventory_turnover, operating_profit, energy_consumed, ak_capital, ak_output, ak_investment, ak_growth, ak_return, ak_tfp, ak_fair_value, cd_output, cd_labor, cd_tfp, cd_mpk, cd_mpl, cd_optimal_capital, cd_capital_gap) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(compSql);){
            for (CompanyData co : this.companyManager.getAllCompanies()) {
                ps.setString(1, co.getStockId());
                ps.setString(2, co.getOwner() != null ? co.getOwner().toString() : null);
                ps.setString(3, co.getName());
                ps.setString(4, co.getLevel() != null ? co.getLevel().name() : null);
                ps.setString(5, co.getStatus() != null ? co.getStatus().name() : null);
                ps.setDouble(6, co.getNetAssetValue());
                ps.setDouble(7, co.getRecentProfit());
                ps.setLong(8, co.getTotalShares());
                ps.setDouble(9, co.getIpoPrice());
                ps.setLong(10, co.getFoundedTime());
                ps.setLong(11, co.getListedTime());
                ps.setDouble(12, co.getRetainedEarnings());
                ps.setLong(13, co.getLastReportTime());
                ps.setLong(14, co.getLastDividendTime());
                ps.setLong(15, co.getFloatShares());
                ps.setString(16, co.getIpoState() != null ? co.getIpoState().name() : null);
                ps.setDouble(17, co.getSubscriptionRaised());
                ps.setDouble(18, co.getDividendPaidTotal());
                ps.setInt(19, co.getLowPriceDays());
                ps.setDouble(20, co.getInventoryValue());
                ps.setDouble(21, co.getProductiveAssetValue());
                ps.setDouble(22, co.getAppraisedAssetValue());
                ps.setDouble(23, co.getReplacementCost());
                ps.setDouble(24, co.getAssetDepreciation());
                ps.setDouble(25, co.getAssetUtilization());
                ps.setDouble(26, co.getAssetRisk());
                ps.setDouble(27, co.getAssetQuality());
                ps.setInt(28, co.getMachineCount());
                ps.setInt(29, co.getMultiblockCount());
                ps.setDouble(30, co.getProductionOutputValue());
                ps.setDouble(31, co.getProductionInputValue());
                ps.setDouble(32, co.getProductionThroughput());
                ps.setDouble(33, co.getProductionUtilization());
                ps.setDouble(34, co.getInventoryTurnover());
                ps.setDouble(35, co.getOperatingProfit());
                ps.setDouble(36, co.getEnergyConsumed());
                ps.setDouble(37, co.getAkCapital());
                ps.setDouble(38, co.getAkOutput());
                ps.setDouble(39, co.getAkInvestment());
                ps.setDouble(40, co.getAkGrowth());
                ps.setDouble(41, co.getAkReturn());
                ps.setDouble(42, co.getAkTfp());
                ps.setDouble(43, co.getAkFairValue());
                ps.setDouble(44, co.getCdOutput());
                ps.setDouble(45, co.getCdLabor());
                ps.setDouble(46, co.getCdTfp());
                ps.setDouble(47, co.getCdMpk());
                ps.setDouble(48, co.getCdMpl());
                ps.setDouble(49, co.getCdOptimalCapital());
                ps.setDouble(50, co.getCdCapitalGap());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        if (this.futuresEnabled && this.isCzceFuturesMode()) {
            this.czceFutures.saveToH2(c);
            this.czceOptions.saveToH2(c);
        }
    }

    private static final class DgCoolingState {
        boolean active;
        long windowEndTick;
        int windowRound;
        int consecutiveFailures;
        double lastEq;
        double lastPStar;
        double lastDev;
        long lastPStarVolume;
        DgHaltReason reason;
        transient boolean justStarted;

        private DgCoolingState() {
        }
    }

    public static class PendingEconomyChange {
        public double cash;
        public final Map<String, Integer> addHolding = new HashMap<String, Integer>();
        public final Map<String, Double> addPrice = new HashMap<String, Double>();
        public final Map<String, Integer> removeHolding = new HashMap<String, Integer>();

        public boolean isEmpty() {
            return this.cash == 0.0 && this.addHolding.isEmpty() && this.removeHolding.isEmpty();
        }

        public CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("cash", this.cash);
            CompoundTag add = new CompoundTag();
            for (Map.Entry<String, Integer> e : this.addHolding.entrySet()) {
                add.putInt(e.getKey(), e.getValue().intValue());
                add.putDouble(e.getKey() + "_p", this.addPrice.getOrDefault(e.getKey(), 0.0).doubleValue());
            }
            tag.put("add", (Tag)add);
            CompoundTag rem = new CompoundTag();
            for (Map.Entry<String, Integer> e : this.removeHolding.entrySet()) {
                rem.putInt(e.getKey(), e.getValue().intValue());
            }
            tag.put("remove", (Tag)rem);
            return tag;
        }

        public static PendingEconomyChange read(CompoundTag tag) {
            PendingEconomyChange p = new PendingEconomyChange();
            p.cash = tag.getDouble("cash");
            if (tag.contains("add")) {
                CompoundTag add = tag.getCompound("add");
                for (String k : add.getAllKeys()) {
                    if (k.endsWith("_p")) continue;
                    p.addHolding.put(k, add.getInt(k));
                    p.addPrice.put(k, add.getDouble(k + "_p"));
                }
            }
            if (tag.contains("remove")) {
                CompoundTag rem = tag.getCompound("remove");
                for (String k : rem.getAllKeys()) {
                    p.removeHolding.put(k, rem.getInt(k));
                }
            }
            return p;
        }
    }

    public static class PendingTradeSettlement {
        public double cash;
        public final Map<String, Integer> addHolding = new HashMap<String, Integer>();
        public final Map<String, Double> addPrice = new HashMap<String, Double>();
        public final Map<String, Integer> removeHolding = new HashMap<String, Integer>();

        public boolean isEmpty() {
            return this.cash == 0.0 && this.addHolding.isEmpty() && this.removeHolding.isEmpty();
        }

        public CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putDouble("cash", this.cash);
            CompoundTag add = new CompoundTag();
            for (Map.Entry<String, Integer> e : this.addHolding.entrySet()) {
                add.putInt(e.getKey(), e.getValue().intValue());
                add.putDouble(e.getKey() + "_p", this.addPrice.getOrDefault(e.getKey(), 0.0).doubleValue());
            }
            tag.put("add", (Tag)add);
            CompoundTag rem = new CompoundTag();
            for (Map.Entry<String, Integer> e : this.removeHolding.entrySet()) {
                rem.putInt(e.getKey(), e.getValue().intValue());
            }
            tag.put("remove", (Tag)rem);
            return tag;
        }

        public static PendingTradeSettlement read(CompoundTag tag) {
            PendingTradeSettlement p = new PendingTradeSettlement();
            p.cash = tag.getDouble("cash");
            if (tag.contains("add")) {
                CompoundTag add = tag.getCompound("add");
                for (String k : add.getAllKeys()) {
                    if (k.endsWith("_p")) continue;
                    p.addHolding.put(k, add.getInt(k));
                    p.addPrice.put(k, add.getDouble(k + "_p"));
                }
            }
            if (tag.contains("remove")) {
                CompoundTag rem = tag.getCompound("remove");
                for (String k : rem.getAllKeys()) {
                    p.removeHolding.put(k, rem.getInt(k));
                }
            }
            return p;
        }
    }

    public static enum DgHaltReason {
        EQ_DEVIATION,
        MATCH_FAILURES,
        LEGACY_NO_FILL;

    }
}
