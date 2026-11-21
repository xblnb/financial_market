package com.example.financialmarket.engine.quant;

import com.example.financialmarket.engine.AssetSnapshot;
import com.example.financialmarket.engine.MarketState;
import com.example.financialmarket.engine.Order;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家量化策略注册与订单生成门面（t2 落地）。
 *
 * <p>职责：
 * <ul>
 *   <li>持有玩家策略注册表（{@link Strategy}）+ 对应执行委托（{@link TradingStrategy}）。</li>
 *   <li>{@link MarketEngine} 持有本引擎单例（{@code new QuantAPI(seed)}），网络包经
 *       {@code engine.getQuantAPI()} 取单例，而非各自 {@code new QuantAPI()}。</li>
 *   <li>{@code generateOrders(...)} 由 {@code MarketEngine.tick} 调用，产生的订单经
 *       {@code submitOrder} 进入真实订单流（复用 A 股一手/T+1/涨跌停/休市校验，R0-6）。</li>
 *   <li>确定性：EMA 金叉/死叉与网格/马丁格尔均走确定性逻辑（真实价格路径或 SeedableRandom
 *       子流），不引入新的 {@code Math.random/new Random}（R0-3）。</li>
 * </ul>
 */
public class QuantAPI {
    /** 玩家 → 策略注册参数。 */
    private final Map<UUID, Strategy> strategies = new HashMap<>();
    /** 玩家 → 实际执行委托。 */
    private final Map<UUID, TradingStrategy> delegates = new HashMap<>();
    /** 引擎派生子流（供含随机预置策略使用；EMA 本身无需随机）。 */
    private final SeedableRandom rng;

    public QuantAPI() {
        this(0L);
    }

    public QuantAPI(long seed) {
        this.rng = new SeedableRandom(SeedableRandom.mix64(seed));
    }

    /** 策略执行委托接口：给定状态/资产/玩家，产生订单（可为空）。 */
    public interface TradingStrategy {
        List<Order> generateOrders(MarketState state, String assetId, UUID playerId);
    }

    /** 注册（或覆盖）一个双均线策略给指定玩家。确定性、无随机。 */
    public boolean enableStrategy(UUID playerId, String name, int fast, int slow) {
        if (playerId == null) return false;
        int f = Math.max(1, fast);
        int s = Math.max(2, slow);
        if (f >= s) s = f + 1; // 快线必须短于慢线，否则金叉/死叉无意义
        Strategy st = new Strategy(playerId, name, f, s, true);
        strategies.put(playerId, st);
        delegates.put(playerId, new DoubleEMACrossStrategy(f, s));
        return true;
    }

    /** 清空该玩家的策略（含执行委托）。 */
    public void disableStrategy(UUID playerId) {
        strategies.remove(playerId);
        delegates.remove(playerId);
    }

    /** 查询玩家当前注册策略参数。 */
    public Strategy getStrategy(UUID playerId) {
        return strategies.get(playerId);
    }

    /** 玩家是否已启用可执行策略。 */
    public boolean isEnabled(UUID playerId) {
        return delegates.containsKey(playerId);
    }

    /** 全量策略注册表快照（供 UI / 验证 / 持久化）。 */
    public Map<UUID, Strategy> snapshot() {
        return new HashMap<>(strategies);
    }

    /** 为玩家 / 资产生成订单（无策略或未启用时返回空）。 */
    public List<Order> generateOrders(MarketState state, UUID playerId, String assetId) {
        TradingStrategy d = delegates.get(playerId);
        if (d == null) return Collections.emptyList();
        return d.generateOrders(state, assetId, playerId);
    }

    /** 兼容别名（旧 {@code executeStrategies} 语义）。 */
    public List<Order> executeStrategies(MarketState state, UUID playerId, String assetId) {
        return generateOrders(state, playerId, assetId);
    }

    // ------------------------------------------------------------------
    // 预置策略
    // ------------------------------------------------------------------

    /** 双均线（EMA 金叉/死叉）策略。纯确定性：基于真实价格路径（lastPrice 历史）计算 EMA。 */
    public static class DoubleEMACrossStrategy implements TradingStrategy {
        private final int fastPeriod;
        private final int slowPeriod;
        /** 每资产最近价格历史（实际成交路径，确定性）。 */
        private final Map<String, Deque<Double>> history = new HashMap<>();
        /** 每资产上一时刻快线是否在慢线上方（金叉/死叉判定）。 */
        private final Map<String, Boolean> lastAbove = new HashMap<>();

        public DoubleEMACrossStrategy(int fast, int slow) {
            this.fastPeriod = Math.max(1, fast);
            this.slowPeriod = Math.max(2, slow);
        }

        @Override
        public List<Order> generateOrders(MarketState state, String assetId, UUID playerId) {
            AssetSnapshot snap = state.getAsset(assetId);
            if (snap == null || snap.getLastPrice() <= 0) return Collections.emptyList();
            Deque<Double> h = history.computeIfAbsent(assetId, k -> new ArrayDeque<>());
            h.addLast(snap.getLastPrice());
            // 保留窗口内足够历史（确定性裁剪，避免无界增长）
            int cap = Math.max(fastPeriod, slowPeriod) * 2 + 10;
            while (h.size() > cap) h.removeFirst();
            if (h.size() < slowPeriod) return Collections.emptyList();

            double emaFast = calcEMA(h, fastPeriod);
            double emaSlow = calcEMA(h, slowPeriod);
            boolean above = emaFast > emaSlow;
            boolean prevAbove = lastAbove.getOrDefault(assetId, false);
            List<Order> orders = new ArrayList<>();
            double price = round2(snap.getLastPrice());
            if (above && !prevAbove) {
                // 金叉 → 买入一手（A 股 100 整数倍，submitOrder 校验）
                orders.add(new Order(playerId, assetId, Order.Type.LIMIT_BUY,
                        PriceDiscoveryEngine.A_SHARE_BOARD_LOT, price));
            } else if (!above && prevAbove) {
                // 死叉 → 卖出一手（可用量由 T+1 账本校验）
                orders.add(new Order(playerId, assetId, Order.Type.LIMIT_SELL,
                        PriceDiscoveryEngine.A_SHARE_BOARD_LOT, price));
            }
            lastAbove.put(assetId, above);
            return orders;
        }

        private static double calcEMA(Deque<Double> data, int period) {
            double multiplier = 2.0 / (period + 1);
            double ema = 0;
            boolean first = true;
            int i = 0;
            for (Double v : data) {
                if (first) { ema = v; first = false; }
                else ema = (v - ema) * multiplier + ema;
                i++;
            }
            return ema;
        }
    }

    /** 网格交易策略：价格跌破网格参考价 - step → 买 lots 手；涨超参考价 + step → 卖 lots 手。确定性、无随机。 */
    public static class GridStrategy implements TradingStrategy {
        private final double step;
        private final int lots;
        private final Map<String, Double> gridRef = new HashMap<>();

        public GridStrategy(double step, int lots, long seed) {
            this.step = Math.max(0.01, step);
            this.lots = Math.max(1, lots);
            // seed 保留（确定性子流预留；网格本身无需随机）
            new SeedableRandom(SeedableRandom.mix64(seed));
        }

        @Override
        public List<Order> generateOrders(MarketState state, String assetId, UUID playerId) {
            AssetSnapshot snap = state.getAsset(assetId);
            if (snap == null || snap.getLastPrice() <= 0) return Collections.emptyList();
            double price = snap.getLastPrice();
            // 首次看到该资产时锚定参考价（修复：原来 getOrDefault(assetId, price) 不持久化锚点，
            // 首次调用 price==ref 恒不触发且 ref 永不写入 → 网格永不产生订单）。锚定后再检查触发条件。
            gridRef.putIfAbsent(assetId, price);
            double ref = gridRef.get(assetId);
            int qty = lots * PriceDiscoveryEngine.A_SHARE_BOARD_LOT;
            List<Order> orders = new ArrayList<>();
            if (price <= ref - step) {
                orders.add(new Order(playerId, assetId, Order.Type.LIMIT_BUY, qty, round2(price)));
                gridRef.put(assetId, price);
            } else if (price >= ref + step) {
                orders.add(new Order(playerId, assetId, Order.Type.LIMIT_SELL, qty, round2(price)));
                gridRef.put(assetId, price);
            }
            return orders;
        }
    }

    /** 马丁格尔策略：价格下跌（亏损信号）时加倍买入摊低成本。确定性（无随机，纯价格驱动）。 */
    public static class MartingaleStrategy implements TradingStrategy {
        private final double baseStep;
        private final boolean doubleOnLoss;
        private final Map<String, Double> lastPrice = new HashMap<>();
        private final Map<String, Integer> streak = new HashMap<>();

        public MartingaleStrategy(double baseStep, boolean doubleOnLoss, long seed) {
            this.baseStep = Math.max(0.01, baseStep);
            this.doubleOnLoss = doubleOnLoss;
            // seed 保留（确定性子流预留；马丁格尔本身无需随机）
            new SeedableRandom(SeedableRandom.mix64(seed));
        }

        @Override
        public List<Order> generateOrders(MarketState state, String assetId, UUID playerId) {
            AssetSnapshot snap = state.getAsset(assetId);
            if (snap == null || snap.getLastPrice() <= 0) return Collections.emptyList();
            double price = snap.getLastPrice();
            Double prev = lastPrice.get(assetId);
            if (prev == null) {
                lastPrice.put(assetId, price);
                return Collections.emptyList();
            }
            int s = streak.getOrDefault(assetId, 0);
            if (price < prev) {
                s = doubleOnLoss ? s + 1 : 0;
            } else {
                s = 0;
            }
            streak.put(assetId, s);
            lastPrice.put(assetId, price);
            int multiplier = 1 << Math.min(6, s); // 上限 64 手，防过度放大
            int qty = multiplier * PriceDiscoveryEngine.A_SHARE_BOARD_LOT;
            List<Order> orders = new ArrayList<>();
            orders.add(new Order(playerId, assetId, Order.Type.LIMIT_BUY, qty, round2(price)));
            return orders;
        }
    }

    // ------------------------------------------------------------------
    // 持久化（随 MarketEngine.save()/load() 的 "quant" 字段）
    // ------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Strategy s : strategies.values()) {
            if (!s.isEnabled()) continue;
            CompoundTag ct = new CompoundTag();
            ct.putUUID("player", s.getPlayerId());
            ct.putString("name", s.getStrategyName());
            ct.putInt("fast", s.getFastPeriod());
            ct.putInt("slow", s.getSlowPeriod());
            list.add(ct);
        }
        tag.put("strategies", list);
        return tag;
    }

    /** 恢复玩家策略；旧档缺字段/缺键用默认重建，读档不失败（R0-5）。 */
    public void load(CompoundTag tag) {
        strategies.clear();
        delegates.clear();
        if (tag == null || tag.isEmpty()) return;
        ListTag list = tag.getList("strategies", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag ct = list.getCompound(i);
            if (!ct.contains("player")) continue;
            UUID pid = ct.getUUID("player");
            String name = ct.getString("name");
            int fast = ct.getInt("fast");
            int slow = ct.getInt("slow");
            enableStrategy(pid, name, fast, slow);
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
