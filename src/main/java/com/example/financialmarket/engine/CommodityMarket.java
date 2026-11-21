package com.example.financialmarket.engine;

import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.banking.InflationSystem;
import com.example.financialmarket.engine.calendar.TradingCalendar;
import com.example.financialmarket.network.packets.CommodityPriceSyncPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/**
 * 商品市场（CF-2~CF-4 / CF-10）。
 *
 * <p>持有全部已注册商品（物品 + 流体）的 {@link CommodityEntry}，提供物品买卖（走玩家库存）与
 * 流体买卖（走<b>流体存储账本</b>）；{@code tick} 以引擎 tick 计数器为<b>时间权威</b>驱动
 * 供需衰减、逐日宏观/成本演化与价格同步，<b>移除全部 Math.random</b>（CF-1，确定性可复现）。
 *
 * <p>休市门控：与 A 股日历共享（{@code calendar.isMarketOpen()}），休市时买卖拒绝。
 * 持久化：{@link #save()}/{@link #load(CompoundTag)} 随引擎 blob 保存全部条目状态 + 流体账本，
 * 读档用 {@code contains} 兜底，缺字段不失败（CF-10）。
 */
public class CommodityMarket {
    // ===== 时段/宏观参数（§9） =====
    private static final long DECAY_INTERVAL = 6000;
    private static final long COMMODITY_SYNC_INTERVAL = 200;
    private static final double MACRO_INFLATION_WEIGHT = 0.5;
    private static final double MACRO_M2_WEIGHT = 0.5;
    private static final double CYCLE_DEMAND_WEIGHT = 0.1;

    private final Map<Item, CommodityEntry> commodities = new LinkedHashMap<>();
    private final Map<Fluid, CommodityEntry> fluidCommodities = new LinkedHashMap<>();
    /** 玩家流体存储账本：playerUUID → (fluid 注册键 → 桶数)。 */
    private final Map<UUID, Map<String, Integer>> fluidLedger = new HashMap<>();

    private long lastDecayTick = 0;
    private long lastSyncTick = 0;
    private long lastDailyTick = Long.MIN_VALUE;
    private MarketEngine engine;
    private boolean tradingOpen = true; // 开市门控（由 tick 从 A 股日历刷新）

    public CommodityMarket() {
        // 默认由 MarketEngine 初始化
    }

    /** 注入引擎引用（构造时调用），供开关/日历门控读取。 */
    public void setEngine(MarketEngine engine) { this.engine = engine; }

    // ===== 注册（幂等：已存在 key 保持既有条目，不破坏读档状态） =====
    public void register(Item item, double basePrice, String... industries) {
        commodities.putIfAbsent(item, new CommodityEntry(item, basePrice, industries));
    }

    /** Manual anchor from data pack; overrides auto-registration and is not re-derived. */
    public void overrideManual(Item item, double basePrice, String... industries) {
        CommodityEntry entry = new CommodityEntry(item, basePrice, industries);
        entry.markManualAnchor();
        commodities.put(item, entry);
    }

    public void registerFluid(Fluid fluid, double basePrice, String... industries) {
        fluidCommodities.putIfAbsent(fluid, new CommodityEntry(fluid, basePrice, industries));
    }

    public CommodityEntry getCommodity(Item item) { return commodities.get(item); }
    public CommodityEntry getCommodity(Fluid fluid) { return fluidCommodities.get(fluid); }

    /**
     * World-macro production: output supply increases. Quantity is in item units and is scaled by
     * the caller so a single player cannot instantly crash a commodity; the entry clamps price.
     */
    public boolean applyProduction(Item item, double quantity) {
        CommodityEntry entry = commodities.get(item);
        if (entry == null || quantity <= 0 || !Double.isFinite(quantity)) return false;
        entry.adjustSupply(quantity);
        return true;
    }

    public boolean applyConsumption(Item item, double quantity) {
        CommodityEntry entry = commodities.get(item);
        if (entry == null || quantity <= 0 || !Double.isFinite(quantity)) return false;
        entry.adjustDemand(quantity);
        return true;
    }

    public boolean applyProduction(Fluid fluid, double quantity) {
        CommodityEntry entry = fluidCommodities.get(fluid);
        if (entry == null || quantity <= 0 || !Double.isFinite(quantity)) return false;
        entry.adjustSupply(quantity);
        return true;
    }

    public boolean applyConsumption(Fluid fluid, double quantity) {
        CommodityEntry entry = fluidCommodities.get(fluid);
        if (entry == null || quantity <= 0 || !Double.isFinite(quantity)) return false;
        entry.adjustDemand(quantity);
        return true;
    }

    /** 全部商品（物品 + 流体）。 */
    public Collection<CommodityEntry> getAllCommodities() {
        List<CommodityEntry> all = new ArrayList<>(commodities.size() + fluidCommodities.size());
        all.addAll(commodities.values());
        all.addAll(fluidCommodities.values());
        return all;
    }

    public int size() { return commodities.size() + fluidCommodities.size(); }

    /**
     * Real-server smoke: advance every registered commodity by {@code days} daily ticks, capture
     * sample paths and aggregate sanity stats, then restore the exact pre-smoke state.
     */
    public Map<String, Object> runSmokeDays(int days) {
        int n = Math.max(1, Math.min(365, days));
        CompoundTag before = save();
        Map<String, List<Double>> paths = new LinkedHashMap<>();
        String[] samples = {"minecraft:wheat", "minecraft:iron_ingot", "minecraft:diamond",
                "minecraft:coal", "minecraft:oak_log", "minecraft:redstone"};
        int finite = 0;
        int floorViolations = 0;
        int capViolations = 0;
        double volSum = 0;
        double retSum = 0;
        int volCount = 0;
        for (int d = 1; d <= n; d++) {
            for (CommodityEntry e : getAllCommodities()) {
                e.dailyTick(d, e.getMacroFactor());
            }
        }
        for (CommodityEntry e : getAllCommodities()) {
            double p = e.getPrice();
            if (Double.isFinite(p)) finite++;
            double floor = e.getCostAnchor() * CommodityEntry.PRICE_FLOOR_RATIO;
            double cap = e.getCostAnchor() * CommodityEntry.PRICE_CAP_RATIO;
            if (p < floor - 0.01) floorViolations++;
            if (p > cap + 0.01) capViolations++;
            if (Double.isFinite(e.getVolatility())) {
                volSum += e.getVolatility();
                volCount++;
            }
            List<Double> h = e.getHistory();
            if (h.size() >= 2) {
                double first = h.get(0);
                double last = h.get(h.size() - 1);
                if (first > 0) retSum += (last - first) / first;
            }
            String key = e.getKey();
            for (String s : samples) {
                if (key.equals("item:" + s) || key.equals("fluid:" + s)) {
                    paths.put(s, h);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", n);
        out.put("registeredItems", commodities.size());
        out.put("registeredFluids", fluidCommodities.size());
        out.put("totalCommodities", size());
        out.put("finitePrices", finite);
        out.put("floorViolations", floorViolations);
        out.put("capViolations", capViolations);
        out.put("avgVolatility", volCount == 0 ? 0 : volSum / volCount);
        out.put("avgTotalReturn", volCount == 0 ? 0 : retSum / volCount);
        out.put("samplePaths", paths);
        // Restore after collecting; the smoke must not move live prices.
        load(before);
        return out;
    }

    /** 休市/关闭门控（CF-4/CF-11）。 */
    private boolean canTrade() {
        if (engine != null && !engine.isCommodityEnabled()) return false;
        return tradingOpen;
    }

    // ===== 物品交易（走玩家库存） =====
    public boolean buy(ServerPlayer player, Item item, int quantity) {
        if (!canTrade()) return false;
        // t3 持卡门控（BT-4）：商品买入须持储蓄卡；banking 关闭时 engine.hasBankCard 恒 true（BT-10）
        if (engine != null && !engine.hasBankCard(player.getUUID())) return false;
        CommodityEntry entry = commodities.get(item);
        if (entry == null || quantity <= 0) return false;
        double totalCost = entry.getPrice() * quantity;
        PlayerEconomy eco = PlayerEconomy.get(player);
        if (eco.getCash() < totalCost) return false;
        ItemStack stack = new ItemStack(item, quantity);
        if (!player.getInventory().add(stack)) return false;
        eco.setCash(eco.getCash() - totalCost);
        entry.buyPressure(quantity);
        // 传播：该物品被买入，其上游原材料需求增加
        propagateDemand(item, quantity * 0.5);
        return true;
    }

    public boolean sell(ServerPlayer player, Item item, int quantity) {
        if (!canTrade()) return false;
        // t3 持卡门控（BT-4）：商品卖出须持储蓄卡；banking 关闭时 engine.hasBankCard 恒 true（BT-10）
        if (engine != null && !engine.hasBankCard(player.getUUID())) return false;
        CommodityEntry entry = commodities.get(item);
        if (entry == null || quantity <= 0) return false;
        if (!hasEnoughItems(player, item, quantity)) return false;
        removeItems(player, item, quantity);
        double totalRevenue = entry.getPrice() * quantity;
        PlayerEconomy eco = PlayerEconomy.get(player);
        eco.setCash(eco.getCash() + totalRevenue);
        entry.sellPressure(quantity);
        // 传播：卖出物品，其下游产物供给增加
        propagateSupply(item, quantity * 0.5);
        return true;
    }

    // ===== 流体交易（走流体存储账本，CF-4） =====
    public boolean buyFluid(ServerPlayer player, Fluid fluid, int qtyBucket) {
        if (!canTrade()) return false;
        // t3 持卡门控（BT-4）：流体买入须持储蓄卡；banking 关闭时 engine.hasBankCard 恒 true（BT-10）
        if (engine != null && !engine.hasBankCard(player.getUUID())) return false;
        CommodityEntry entry = fluidCommodities.get(fluid);
        if (entry == null || qtyBucket <= 0) return false;
        double totalCost = entry.getPrice() * qtyBucket;
        PlayerEconomy eco = PlayerEconomy.get(player);
        if (eco.getCash() < totalCost) return false;
        eco.setCash(eco.getCash() - totalCost);
        String fk = BuiltInRegistries.FLUID.getKey(fluid).toString();
        fluidLedger.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).merge(fk, qtyBucket, Integer::sum);
        entry.buyPressure(qtyBucket);
        return true;
    }

    public boolean sellFluid(ServerPlayer player, Fluid fluid, int qtyBucket) {
        if (!canTrade()) return false;
        // t3 持卡门控（BT-4）：流体卖出须持储蓄卡；banking 关闭时 engine.hasBankCard 恒 true（BT-10）
        if (engine != null && !engine.hasBankCard(player.getUUID())) return false;
        CommodityEntry entry = fluidCommodities.get(fluid);
        if (entry == null || qtyBucket <= 0) return false;
        Map<String, Integer> ledger = fluidLedger.get(player.getUUID());
        String fk = BuiltInRegistries.FLUID.getKey(fluid).toString();
        int have = ledger == null ? 0 : ledger.getOrDefault(fk, 0);
        if (have < qtyBucket) return false;
        int left = have - qtyBucket;
        if (left <= 0) ledger.remove(fk);
        else ledger.put(fk, left);
        double totalRevenue = entry.getPrice() * qtyBucket;
        PlayerEconomy eco = PlayerEconomy.get(player);
        eco.setCash(eco.getCash() + totalRevenue);
        entry.sellPressure(qtyBucket);
        return true;
    }

    /** 玩家某流体存储桶数（查询）。 */
    public int getFluidBalance(ServerPlayer player, Fluid fluid) {
        Map<String, Integer> ledger = fluidLedger.get(player.getUUID());
        if (ledger == null) return 0;
        return ledger.getOrDefault(BuiltInRegistries.FLUID.getKey(fluid).toString(), 0);
    }

    // ===== 上下游配方价格传播 =====
    private void propagateDemand(Item item, double delta) {
        Set<Item> downstream = RecipeAnalyzer.getDownstream(item);
        for (Item product : downstream) {
            CommodityEntry entry = commodities.get(product);
            if (entry != null) entry.adjustDemand(-delta); // 原材料涨价，下游需求减少
        }
    }

    private void propagateSupply(Item item, double delta) {
        Set<Item> upstream = RecipeAnalyzer.getUpstream(item);
        for (Item material : upstream) {
            CommodityEntry entry = commodities.get(material);
            if (entry != null) entry.adjustSupply(-delta);
        }
    }

    private boolean hasEnoughItems(ServerPlayer player, Item item, int quantity) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) count += stack.getCount();
        }
        return count >= quantity;
    }

    private void removeItems(ServerPlayer player, Item item, int quantity) {
        int remaining = quantity;
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (stack.is(item)) {
                int toRemove = Math.min(stack.getCount(), remaining);
                stack.shrink(toRemove);
                remaining -= toRemove;
                if (stack.isEmpty()) player.getInventory().items.set(i, ItemStack.EMPTY);
                if (remaining <= 0) break;
            }
        }
        player.getInventory().setChanged();
    }

    // ===== tick（时间权威 = engine tick 计数器；去随机，CF-1/CF-4） =====
    public void tick(ServerLevel level, MarketEngine engine, TradingCalendar calendar) {
        this.engine = engine;
        long tickCounter = engine.getTickCounter();
        this.tradingOpen = calendar.isMarketOpen();

        if (tickCounter % DECAY_INTERVAL == 0 && tickCounter != lastDecayTick) {
            lastDecayTick = tickCounter;
            for (CommodityEntry entry : getAllCommodities()) entry.decay();
        }

        if (calendar.isTradingDayStart()) {
            for (CommodityEntry entry : getAllCommodities()) entry.resetDailyVolume();
            applyMacro(engine);        // 宏观货币/通胀因子 + 经济周期需求增益（§2.5）
            evolveCostAnchors();       // 成本锚随上游配方价演化（成本推动，§2.4）
            long day = calendar.getTradingDayNumber();
            if (day != lastDailyTick) {
                lastDailyTick = day;
                for (CommodityEntry entry : getAllCommodities()) {
                    entry.dailyTick(day, entry.getMacroFactor());
                }
            }
        }

        if (tickCounter % COMMODITY_SYNC_INTERVAL == 0 && tickCounter != lastSyncTick) {
            lastSyncTick = tickCounter;
            syncItemPrices(level);
        }
        // 移除旧的 Math.random() 情绪噪声段（CF-1：零新随机）
    }

    /** 宏观驱动（只读宏观，不改公式）：macroFactor = 1 + 0.5×通胀 + 0.5×M2 增长；经济周期→需求。 */
    private void applyMacro(MarketEngine engine) {
        InflationSystem inf = engine.getInflationSystem();
        double inflation = inf.getInflationRate();
        double m2 = inf.getM2();
        double prevM2 = inf.getPrevM2();
        double m2Growth = prevM2 <= 0 ? 0 : (m2 - prevM2) / prevM2;
        double macroFactor = 1.0 + (MACRO_INFLATION_WEIGHT * inflation + MACRO_M2_WEIGHT * m2Growth);
        double economicHealth = engine.getCycleEngine().getEconomicHealth();
        double demandBoost = 1.0 + CYCLE_DEMAND_WEIGHT * economicHealth;
        for (CommodityEntry entry : getAllCommodities()) {
            entry.setPrevSettlePrice(entry.getPrice()); // 昨结价（供期货锚定）
            entry.setMacroFactor(macroFactor);
            entry.multiplyDemand(demandBoost);
        }
    }

    /** 成本锚演化：每条目沿上游配方最小单件成本黏性收敛（成本推动型通胀，确定性）。 */
    private void evolveCostAnchors() {
        for (CommodityEntry entry : getAllCommodities()) {
            if (!"item".equals(entry.getKind()) || entry.getItem() == null) continue;
            Item it = entry.getItem();
            List<RecipeAnalyzer.RecipeCost> recipes = RecipeAnalyzer.getRecipesProducing(it);
            if (recipes.isEmpty()) continue;
            double best = Double.POSITIVE_INFINITY;
            for (RecipeAnalyzer.RecipeCost r : recipes) {
                double ing = 0;
                boolean ok = true;
                for (Map.Entry<Item, Integer> me : r.ingredients.entrySet()) {
                    CommodityEntry ie = commodities.get(me.getKey());
                    double c = ie != null ? ie.getPrice() : 0;
                    if (c <= 0) { ok = false; break; }
                    ing += c * me.getValue();
                }
                if (ok && ing > 0) {
                    double unit = ing / Math.max(1, r.outputCount);
                    if (unit < best) best = unit;
                }
            }
            if (best < Double.POSITIVE_INFINITY) entry.updateCostAnchor(best);
        }
    }

    private void syncItemPrices(ServerLevel level) {
        Map<Item, Double> priceMap = new HashMap<>();
        for (CommodityEntry entry : commodities.values()) priceMap.put(entry.getItem(), entry.getPrice());
        CommodityPriceSyncPacket packet = new CommodityPriceSyncPacket(priceMap);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    // ===== 持久化（CF-10） =====
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag entries = new ListTag();
        for (CommodityEntry e : getAllCommodities()) {
            CompoundTag ct = new CompoundTag();
            ct.putString("key", e.getKey());
            ct.putString("kind", e.getKind());
            ct.putDouble("price", e.getPrice());
            ct.putDouble("basePrice", e.getBasePrice());
            ct.putDouble("costAnchor", e.getCostAnchor());
            ct.putDouble("supply", e.getSupply());
            ct.putDouble("demand", e.getDemand());
            ct.putDouble("macroFactor", e.getMacroFactor());
            ct.putDouble("prevSettlePrice", e.getPrevSettlePrice());
            ct.putLong("dailyVolume", e.getDailyVolume());
            ct.putDouble("fairValue", e.getFairValue());
            ct.putDouble("momentum", e.getMomentum());
            ct.putDouble("volatility", e.getVolatility());
            ct.putDouble("seasonality", e.getSeasonality());
            ct.putDouble("lastReturn", e.getLastReturn());
            ListTag history = new ListTag();
            for (double value : e.getHistory()) {
                history.add(net.minecraft.nbt.DoubleTag.valueOf(value));
            }
            ct.put("history", history);
            entries.add(ct);
        }
        tag.put("entries", entries);

        CompoundTag ledger = new CompoundTag();
        for (Map.Entry<UUID, Map<String, Integer>> pe : fluidLedger.entrySet()) {
            CompoundTag ft = new CompoundTag();
            for (Map.Entry<String, Integer> fe : pe.getValue().entrySet()) ft.putInt(fe.getKey(), fe.getValue());
            ledger.put(pe.getKey().toString(), ft);
        }
        tag.put("fluidLedger", ledger);
        return tag;
    }

    /** 恢复商品状态；缺字段/旧存档用 contains 兜底，读档不失败（CF-10）。 */
    public void load(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("entries")) {
            ListTag entries = tag.getList("entries", Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag ct = entries.getCompound(i);
                CommodityEntry e = findByKey(ct.getString("key"));
                if (e == null) continue;
                // F2（连接审计修复 F9）：逐字段 contains 兜底——缺字段时保留商品当前值（前向兼容：
                // 旧档/未来档缺字段不得把 price 等打成 0 导致商品免费买/期货 spot 失真）。
                e.restoreState(
                        ct.contains("price") ? ct.getDouble("price") : e.getPrice(),
                        ct.contains("basePrice") ? ct.getDouble("basePrice") : e.getBasePrice(),
                        ct.contains("costAnchor") ? ct.getDouble("costAnchor") : e.getCostAnchor(),
                        ct.contains("supply") ? ct.getDouble("supply") : e.getSupply(),
                        ct.contains("demand") ? ct.getDouble("demand") : e.getDemand(),
                        ct.contains("macroFactor") ? ct.getDouble("macroFactor") : e.getMacroFactor(),
                        ct.contains("prevSettlePrice") ? ct.getDouble("prevSettlePrice") : e.getPrevSettlePrice(),
                        ct.contains("dailyVolume") ? ct.getLong("dailyVolume") : e.getDailyVolume());
                e.restoreDynamics(
                        ct.contains("fairValue") ? ct.getDouble("fairValue") : e.getFairValue(),
                        ct.contains("momentum") ? ct.getDouble("momentum") : e.getMomentum(),
                        ct.contains("volatility") ? ct.getDouble("volatility") : e.getVolatility(),
                        ct.contains("seasonality") ? ct.getDouble("seasonality") : e.getSeasonality(),
                        ct.contains("lastReturn") ? ct.getDouble("lastReturn") : e.getLastReturn());
                ListTag hist = ct.getList("history", Tag.TAG_DOUBLE);
                if (!hist.isEmpty()) {
                    List<Double> values = new ArrayList<>();
                    for (int j = 0; j < hist.size(); j++) values.add(hist.getDouble(j));
                    e.restoreHistory(values);
                }
            }
        }
        if (tag.contains("fluidLedger")) {
            CompoundTag ledger = tag.getCompound("fluidLedger");
            for (String uuidStr : ledger.getAllKeys()) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    CompoundTag ft = ledger.getCompound(uuidStr);
                    Map<String, Integer> m = new HashMap<>();
                    for (String fk : ft.getAllKeys()) m.put(fk, ft.getInt(fk));
                    fluidLedger.put(uuid, m);
                } catch (IllegalArgumentException ignored) {
                    // 旧档脏 key：跳过，不失败
                }
            }
        }
    }

    private CommodityEntry findByKey(String key) {
        for (CommodityEntry e : getAllCommodities()) {
            if (e.getKey().equals(key)) return e;
        }
        return null;
    }
}
