package com.example.financialmarket.engine.futures.czce;

import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * CZCE-style commodity futures venue.
 *
 * <p>Rules modelled: product multiplier/tick, exchange margin, daily price limits anchored on the
 * previous settlement, limit expansion after consecutive limit days, daytime/night sessions,
 * per-lot fees, position limits, daily mark-to-market, margin calls/forced liquidation and cash
 * delivery at expiry. Fair value uses JQuantLib cost-of-carry; external QuantConnect targets are
 * consumed through {@link QuantConnectBridge}.</p>
 *
 * <p>This venue is deterministic for a given seed and tick sequence. It is independent of the
 * existing item-based {@code FuturesMarket}; integration into MarketEngine is a thin tick call.</p>
 */
public final class CzceFuturesMarket {

    public static final int DAY_TICKS = 6000;
    public static final long YEAR_TICKS = 252L * DAY_TICKS;
    public static final UUID QUANTCONNECT_ACCOUNT =
            UUID.nameUUIDFromBytes("financialmarket:quantconnect:czce".getBytes());
    private static final double INITIAL_CASH = 10_000_000.0;
    private static final int CONTRACT_MONTH_DAYS = 30;
    private static final int LISTED_MONTHS = 6;
    private static final double LIMIT_EXPAND_AFTER_DAYS = 2;
    private static final double LIMIT_EXPAND_FACTOR = 1.5;
    private static final double MAX_LIMIT = 0.10;
    private static final double MAINTENANCE = 0.80;

    public enum Status { LISTED, DELIVERING, EXPIRED }

    public record ProductSpec(
            String code,
            String name,
            String itemId,
            double baseSpot,
            int multiplier,
            double tickSize,
            double marginRatio,
            double limitRatio,
            double feePerLot,
            int maxLots,
            boolean nightSession,
            double storagePerYear,
            double conveniencePerYear) {
    }

    public static final class Contract {
        public String code;
        public String productCode;
        public long deliveryMonthIndex;
        public long lastTradingTick;
        public double prevSettlePrice;
        public double lastPrice;
        public double limitUp;
        public double limitDown;
        public double marginRatio;
        public Status status = Status.LISTED;
        public int consecutiveLimitDays;
        public boolean limitLocked;
        public int boardDaysLeft;
        public long boardDecrementDay;
        public long openInterest;
        public long dayVolume;
        public double dayNotional;
    }

    public static final class Position {
        public String contractCode;
        public int longLots;
        public int shortLots;
        public double avgLongPrice;
        public double avgShortPrice;

        public boolean isEmpty() {
            return longLots <= 0 && shortLots <= 0;
        }
    }

    public static final class Account {
        public final UUID id;
        public double cash = 0.0;
        public double margin;
        public boolean pendingForce;
        public final Map<String, Position> positions = new LinkedHashMap<>();

        public Account(UUID id) {
            this.id = id;
        }

        public double equity() {
            return cash + margin;
        }
    }

    public interface FlowListener {
        void onFlow(String product, double signedLots, double price);
    }

    private final Map<String, ProductSpec> products = new LinkedHashMap<>();
    private final Map<String, Contract> contracts = new LinkedHashMap<>();
    private final Map<UUID, Account> accounts = new LinkedHashMap<>();
    private final Set<String> physicallyDelivered = new LinkedHashSet<>();
    private long physicalDeliveryLots;
    private long physicalDeliveryQuantity;
    private FlowListener flowListener;
    private final SeedableRandom rng;
    private final QuantConnectBridge quantConnect;
    private long tick;
    private long currentDay = Long.MIN_VALUE;
    private long appliedQuantConnectVersion = Long.MIN_VALUE;

    public CzceFuturesMarket(long seed) {
        this(seed, new QuantConnectBridge());
    }

    public CzceFuturesMarket(long seed, QuantConnectBridge bridge) {
        this.rng = new SeedableRandom(SeedableRandom.mix64(seed));
        this.quantConnect = bridge == null ? new QuantConnectBridge() : bridge;
        registerDefaults();
        account(QUANTCONNECT_ACCOUNT).cash = INITIAL_CASH;
    }

    private void registerDefaults() {
        // CZCE product table. Spot values are Yuan/tonne anchors; real venues use live quotes.
        add("SR", "白糖", "minecraft:sugar", 6000, 10, 1.0, 0.07, 0.04, 3.0, 1000, true, 0.02, 0.01);
        add("CF", "棉花", "minecraft:white_wool", 15000, 5, 5.0, 0.07, 0.04, 4.3, 1000, true, 0.02, 0.01);
        add("TA", "PTA", "minecraft:white_dye", 5000, 5, 2.0, 0.07, 0.04, 3.0, 1000, true, 0.02, 0.01);
        add("MA", "甲醇", "minecraft:glass_bottle", 2500, 10, 1.0, 0.07, 0.04, 2.0, 1000, true, 0.03, 0.01);
        add("FG", "玻璃", "minecraft:glass", 1500, 20, 1.0, 0.09, 0.04, 6.0, 1000, true, 0.03, 0.01);
        add("SA", "纯碱", "minecraft:gunpowder", 2000, 20, 1.0, 0.09, 0.04, 3.5, 1000, true, 0.03, 0.01);
        add("ZC", "动力煤", "minecraft:coal", 800, 100, 0.2, 0.10, 0.04, 4.0, 500, true, 0.04, 0.02);
        add("AP", "苹果", "minecraft:apple", 8000, 10, 1.0, 0.10, 0.05, 5.0, 500, false, 0.03, 0.02);
        add("RM", "菜粕", "minecraft:wheat_seeds", 2500, 10, 1.0, 0.07, 0.04, 1.5, 1000, true, 0.02, 0.01);
        add("OI", "菜油", "minecraft:honey_bottle", 9000, 10, 1.0, 0.07, 0.04, 2.5, 1000, true, 0.02, 0.01);
        add("UR", "尿素", "minecraft:bone_meal", 2000, 20, 1.0, 0.07, 0.04, 5.0, 1000, true, 0.03, 0.01);
        add("SF", "硅铁", "minecraft:iron_ingot", 7000, 5, 2.0, 0.09, 0.06, 3.0, 500, false, 0.04, 0.02);
        add("SM", "锰硅", "minecraft:gold_ingot", 6500, 5, 2.0, 0.09, 0.06, 3.0, 500, false, 0.04, 0.02);
    }

    private void add(String code, String name, String itemId, double spot, int multiplier,
                     double tick, double margin, double limit, double fee, int maxLots,
                     boolean night, double storage, double convenience) {
        products.put(code, new ProductSpec(code, name, itemId, spot, multiplier, tick, margin,
                limit, fee, maxLots, night, storage, convenience));
    }

    public Map<String, ProductSpec> products() {
        return Map.copyOf(products);
    }

    public Map<String, Contract> contracts() {
        return Map.copyOf(contracts);
    }

    public Contract contract(String code) {
        return contracts.get(code);
    }

    public Account account(UUID id) {
        return accounts.computeIfAbsent(id, Account::new);
    }

    public Account accountOrNull(UUID id) {
        return accounts.get(id);
    }

    public List<Account> accounts() {
        return new ArrayList<>(accounts.values());
    }

    /** Marks a contract as settled by the physical-delivery block, so cash delivery is skipped. */
    public void markPhysicallyDelivered(String code) {
        if (code != null) {
            physicallyDelivered.add(code);
        }
    }

    public boolean isPhysicallyDelivered(String code) {
        return code != null && physicallyDelivered.contains(code);
    }

    public void recordPhysicalDelivery(String product, int lots, int quantity) {
        physicalDeliveryLots += Math.max(0, lots);
        physicalDeliveryQuantity += Math.max(0, quantity);
    }

    public long physicalDeliveryLots() {
        return physicalDeliveryLots;
    }

    public long physicalDeliveryQuantity() {
        return physicalDeliveryQuantity;
    }

    public Set<String> deliveredContracts() {
        return Set.copyOf(physicallyDelivered);
    }

    public void setFlowListener(FlowListener listener) {
        this.flowListener = listener;
    }

    public long tick() {
        return tick;
    }

    /** Main tick. {@code spots} is keyed by product code; missing entries use spec.baseSpot. */
    public void tick(long tick, double annualRate, Map<String, Double> spots) {
        this.tick = tick;
        long day = tick / DAY_TICKS;
        ensureContracts(day);
        if (day != currentDay) {
            settleDay(day, spots);
            currentDay = day;
            resetDailyLimits();
            for (Account acc : accounts.values()) {
                if (acc.pendingForce && !hasLockedPosition(acc)) {
                    acc.pendingForce = false;
                    forceClose(acc, "margin call after board");
                }
            }
        }
        if (!sessionOpen()) {
            return;
        }
        updatePrices(annualRate, spots);
        applyQuantConnect(spots);
    }

    /** CZCE-style session approximation on the 6000-tick trading day. */
    public boolean sessionOpen() {
        int t = (int) Math.floorMod(tick, DAY_TICKS);
        if (t < 3000) {
            return true;      // 09:00-11:30 + 13:30-15:00 compressed
        }
        return t >= 3000 && t < 4500; // 21:00-23:00 night block
    }

    public boolean sessionOpen(Contract contract) {
        ProductSpec spec = products.get(contract.productCode);
        int t = (int) Math.floorMod(tick, DAY_TICKS);
        if (t < 3000) {
            return true;
        }
        return spec != null && spec.nightSession() && t >= 3000 && t < 4500;
    }

    private void ensureContracts(long day) {
        long monthIndex = day / CONTRACT_MONTH_DAYS;
        for (int offset = 0; offset < LISTED_MONTHS; offset++) {
            long mi = monthIndex + offset;
            int year = 2025 + (int) (mi / 12);
            int month = (int) (mi % 12) + 1;
            for (ProductSpec spec : products.values()) {
                String code = spec.code() + String.format(Locale.ROOT, "%02d%02d", year % 100, month);
                if (contracts.containsKey(code)) {
                    continue;
                }
                Contract c = new Contract();
                c.code = code;
                c.productCode = spec.code();
                c.deliveryMonthIndex = mi;
                c.lastTradingTick = (mi * CONTRACT_MONTH_DAYS + 15L) * DAY_TICKS;
                c.prevSettlePrice = spec.baseSpot();
                c.lastPrice = spec.baseSpot();
                c.marginRatio = spec.marginRatio();
                c.limitUp = roundTick(spec.baseSpot() * (1 + spec.limitRatio()), spec.tickSize());
                c.limitDown = roundTick(spec.baseSpot() * (1 - spec.limitRatio()), spec.tickSize());
                contracts.put(code, c);
            }
        }
        contracts.entrySet().removeIf(e -> e.getValue().lastTradingTick + 5L * DAY_TICKS < tick
                && e.getValue().status == Status.EXPIRED);
    }

    private void updatePrices(double annualRate, Map<String, Double> spots) {
        for (Contract c : contracts.values()) {
            if (c.status != Status.LISTED || !sessionOpen(c)) {
                continue;
            }
            if (c.boardDaysLeft > 0) {
                c.limitLocked = true;
                if (c.boardDecrementDay != currentDay) {
                    c.boardDaysLeft--;
                    c.boardDecrementDay = currentDay;
                }
                continue;
            }
            c.limitLocked = false;
            ProductSpec spec = products.get(c.productCode);
            if (spec == null) {
                continue;
            }
            double spot = spotOf(spec, spots);
            double years = Math.max(0.0, (c.lastTradingTick - tick) / (double) YEAR_TICKS);
            double fair = JQuantLibFuturesPricer.fairValue(spot, annualRate,
                    spec.storagePerYear(), spec.conveniencePerYear(), years);
            double shock = rng.nextGaussian() * spot * 0.0004;
            double next = c.lastPrice + 0.12 * (fair - c.lastPrice) + shock;
            next = Math.max(c.limitDown, Math.min(c.limitUp, next));
            c.lastPrice = roundTick(next, spec.tickSize());
            if ((c.lastPrice >= c.limitUp - 1e-9 || c.lastPrice <= c.limitDown + 1e-9)
                    && rng.nextDouble() < 0.35) {
                c.limitLocked = true;
                c.boardDaysLeft = 1 + rng.nextInt(3);
                c.boardDecrementDay = currentDay;
            }
        }
    }

    private double spotOf(ProductSpec spec, Map<String, Double> spots) {
        if (spots != null) {
            Double s = spots.get(spec.code());
            if (s != null && s > 0 && Double.isFinite(s)) {
                return s;
            }
        }
        return spec.baseSpot();
    }

    /** Player wallet bridge: tops up the CZCE account from PlayerEconomy if needed. */
    public String open(ServerPlayer player, String code, String side, int lots) {
        Contract c = contracts.get(code);
        if (c == null) return "合约不存在或已到期";
        ProductSpec spec = products.get(c.productCode);
        if (spec == null) return "品种不存在";
        double price = clampPrice(c, c.lastPrice);
        double margin = price * spec.multiplier() * Math.max(1, lots) * c.marginRatio;
        double fee = spec.feePerLot() * Math.max(1, lots);
        Account acc = account(player.getUUID());
        double need = margin + fee - Math.max(0.0, acc.cash);
        if (need > 0) {
            PlayerEconomy eco = PlayerEconomy.get(player);
            if (eco.getCash() < need) return "现金不足，无法缴付保证金";
            eco.setCash(eco.getCash() - need);
            acc.cash += need;
        }
        return open(player.getUUID(), code, side, lots);
    }

    /** Closes and, if flat, sweeps the account balance back to the player wallet. */
    public String close(ServerPlayer player, String code, String side, int lots) {
        String err = close(player.getUUID(), code, side, lots);
        if (err != null) return err;
        Account acc = account(player.getUUID());
        if (acc.positions.isEmpty() && acc.cash > 0) {
            PlayerEconomy.get(player).setCash(PlayerEconomy.get(player).getCash() + acc.cash);
            acc.cash = 0;
        }
        return null;
    }

    public String deposit(ServerPlayer player, double amount) {
        if (amount <= 0) return "金额须为正";
        PlayerEconomy eco = PlayerEconomy.get(player);
        if (eco.getCash() < amount) return "现金不足";
        eco.setCash(eco.getCash() - amount);
        account(player.getUUID()).cash += amount;
        return null;
    }

    public String withdraw(ServerPlayer player, double amount) {
        if (amount <= 0) return "金额须为正";
        Account acc = account(player.getUUID());
        if (acc.cash < amount) return "期货账户余额不足";
        acc.cash -= amount;
        PlayerEconomy.get(player).setCash(PlayerEconomy.get(player).getCash() + amount);
        return null;
    }

    public double cash(UUID id) {
        return account(id).cash;
    }

    /** Opens a long/short position; returns null on success or a rejection reason. */
    public String open(UUID accountId, String code, String side, int lots) {
        Contract c = contracts.get(code);
        if (c == null || c.status != Status.LISTED) return "合约不存在或已到期";
        if (!sessionOpen(c)) return "非交易时段";
        if (c.limitLocked) return "封板中，暂不可成交";
        ProductSpec spec = products.get(c.productCode);
        if (spec == null) return "品种不存在";
        if (lots <= 0) return "手数须为正";
        Account acc = account(accountId);
        Position pos = acc.positions.computeIfAbsent(code, k -> {
            Position p = new Position();
            p.contractCode = code;
            return p;
        });
        boolean buy = "long".equalsIgnoreCase(side);
        int current = buy ? pos.longLots : pos.shortLots;
        if (current + lots > spec.maxLots()) return "超出持仓限额";
        double price = clampPrice(c, c.lastPrice);
        double margin = price * spec.multiplier() * lots * c.marginRatio;
        double fee = spec.feePerLot() * lots;
        if (acc.cash < margin + fee) return "保证金不足";
        acc.cash -= margin + fee;
        acc.margin += margin;
        if (buy) {
            double total = pos.avgLongPrice * pos.longLots + price * lots;
            pos.longLots += lots;
            pos.avgLongPrice = total / pos.longLots;
        } else {
            double total = pos.avgShortPrice * pos.shortLots + price * lots;
            pos.shortLots += lots;
            pos.avgShortPrice = total / pos.shortLots;
        }
        c.openInterest += lots;
        c.dayVolume += lots;
        c.dayNotional += price * spec.multiplier() * lots;
        if (flowListener != null) {
            flowListener.onFlow(spec.code(), buy ? lots : -lots, price);
        }
        return null;
    }

    /** Closes a long/short position; returns null on success or a rejection reason. */
    public String close(UUID accountId, String code, String side, int lots) {
        Contract c = contracts.get(code);
        if (c == null || c.status != Status.LISTED) return "合约不存在或已到期";
        if (!sessionOpen(c)) return "非交易时段";
        if (c.limitLocked) return "封板中，暂不可成交";
        ProductSpec spec = products.get(c.productCode);
        if (spec == null) return "品种不存在";
        if (lots <= 0) return "手数须为正";
        Account acc = account(accountId);
        Position pos = acc.positions.get(code);
        if (pos == null) return "无该合约持仓";
        boolean longSide = "long".equalsIgnoreCase(side);
        int have = longSide ? pos.longLots : pos.shortLots;
        if (have < lots) return "平仓手数超过持仓";
        double price = clampPrice(c, c.lastPrice);
        double openAvg = longSide ? pos.avgLongPrice : pos.avgShortPrice;
        double pnl = (longSide ? price - openAvg : openAvg - price) * spec.multiplier() * lots;
        double released = Math.min(openAvg * spec.multiplier() * lots * c.marginRatio, acc.margin);
        double fee = spec.feePerLot() * lots;
        acc.margin = Math.max(0, acc.margin - released);
        acc.cash += released + pnl - fee;
        if (longSide) {
            pos.longLots -= lots;
        } else {
            pos.shortLots -= lots;
        }
        if (pos.isEmpty()) acc.positions.remove(code);
        c.openInterest = Math.max(0, c.openInterest - lots);
        c.dayVolume += lots;
        c.dayNotional += price * spec.multiplier() * lots;
        if (flowListener != null) {
            flowListener.onFlow(spec.code(), longSide ? -lots : lots, price);
        }
        return null;
    }

    private void settleDay(long day, Map<String, Double> spots) {
        for (Contract c : contracts.values()) {
            if (c.status != Status.LISTED) continue;
            if (tick > c.lastTradingTick) {
                c.status = Status.EXPIRED;
                if (!physicallyDelivered.remove(c.code)) {
                    deliver(c, spots);
                }
                continue;
            }
            double settle = c.lastPrice > 0 ? c.lastPrice : c.prevSettlePrice;
            boolean limit = settle >= c.limitUp - 1e-9 || settle <= c.limitDown + 1e-9;
            c.consecutiveLimitDays = limit ? c.consecutiveLimitDays + 1 : 0;
            for (Account acc : accounts.values()) {
                Position p = acc.positions.get(c.code);
                if (p == null) continue;
                double pnl = (settle - p.avgLongPrice) * cMultiplier(c) * p.longLots
                        + (p.avgShortPrice - settle) * cMultiplier(c) * p.shortLots;
                acc.cash += pnl;
                if (p.longLots > 0) p.avgLongPrice = settle;
                if (p.shortLots > 0) p.avgShortPrice = settle;
            }
            c.prevSettlePrice = settle;
        }
        for (Account acc : accounts.values()) {
            double required = requiredMargin(acc);
            double equity = acc.cash + acc.margin;
            if (equity < required * MAINTENANCE) {
                if (hasLockedPosition(acc)) {
                    acc.pendingForce = true;
                } else {
                    forceClose(acc, "margin call");
                }
                continue;
            }
            acc.margin = required;
            acc.cash = equity - required;
        }
    }

    private void resetDailyLimits() {
        for (Contract c : contracts.values()) {
            if (c.status != Status.LISTED) continue;
            ProductSpec spec = products.get(c.productCode);
            if (spec == null) continue;
            double ratio = spec.limitRatio();
            if (c.boardDaysLeft > 0) {
                ratio = Math.min(MAX_LIMIT, ratio * LIMIT_EXPAND_FACTOR);
            }
            if (c.consecutiveLimitDays >= LIMIT_EXPAND_AFTER_DAYS) {
                ratio = Math.min(MAX_LIMIT, ratio * LIMIT_EXPAND_FACTOR);
            }
            c.marginRatio = spec.marginRatio()
                    * (c.consecutiveLimitDays >= LIMIT_EXPAND_AFTER_DAYS ? 1.5 : 1.0);
            c.limitUp = roundTick(c.prevSettlePrice * (1 + ratio), spec.tickSize());
            c.limitDown = roundTick(c.prevSettlePrice * (1 - ratio), spec.tickSize());
            c.dayVolume = 0;
            c.dayNotional = 0;
        }
    }

    private void deliver(Contract c, Map<String, Double> spots) {
        ProductSpec spec = products.get(c.productCode);
        double spot = spec == null ? c.prevSettlePrice : spotOf(spec, spots);
        for (Account acc : accounts.values()) {
            Position p = acc.positions.remove(c.code);
            if (p == null) continue;
            double pnl = (spot - p.avgLongPrice) * cMultiplier(c) * p.longLots
                    + (p.avgShortPrice - spot) * cMultiplier(c) * p.shortLots;
            acc.cash += pnl;
            acc.margin = Math.max(0, acc.margin - requiredMarginOf(c, p));
        }
    }

    private boolean hasLockedPosition(Account acc) {
        for (Position p : acc.positions.values()) {
            Contract c = contracts.get(p.contractCode);
            if (c != null && c.limitLocked) {
                return true;
            }
        }
        return false;
    }

    private void forceClose(Account acc, String reason) {
        for (Position p : new ArrayList<>(acc.positions.values())) {
            Contract c = contracts.get(p.contractCode);
            if (c == null) continue;
            double price = c.lastPrice > 0 ? c.lastPrice : c.prevSettlePrice;
            ProductSpec spec = products.get(c.productCode);
            int mult = spec == null ? 1 : spec.multiplier();
            double pnl = (price - p.avgLongPrice) * mult * p.longLots
                    + (p.avgShortPrice - price) * mult * p.shortLots;
            acc.cash += pnl - (spec == null ? 0 : spec.feePerLot() * (p.longLots + p.shortLots));
            acc.positions.remove(p.contractCode);
        }
        acc.margin = 0;
    }

    private double requiredMargin(Account acc) {
        double total = 0;
        for (Position p : acc.positions.values()) {
            Contract c = contracts.get(p.contractCode);
            if (c == null) continue;
            total += requiredMarginOf(c, p);
        }
        return total;
    }

    private double requiredMarginOf(Contract c, Position p) {
        ProductSpec spec = products.get(c.productCode);
        if (spec == null) return 0;
        double price = c.lastPrice > 0 ? c.lastPrice : c.prevSettlePrice;
        return price * spec.multiplier() * (p.longLots + p.shortLots) * c.marginRatio;
    }

    private int cMultiplier(Contract c) {
        ProductSpec spec = products.get(c.productCode);
        return spec == null ? 1 : spec.multiplier();
    }

    private double clampPrice(Contract c, double price) {
        if (c.limitDown > 0 && price < c.limitDown) return c.limitDown;
        if (c.limitUp > 0 && price > c.limitUp) return c.limitUp;
        return price;
    }

    private static double roundTick(double price, double tickSize) {
        if (tickSize <= 0) return price;
        double p = Math.round(price / tickSize) * tickSize;
        return Math.round(p * 100.0) / 100.0;
    }

    /** Applies external QuantConnect target lots for the dedicated account. */
    public void applyQuantConnect(Map<String, Double> spots) {
        QuantConnectBridge.Snapshot snapshot = quantConnect.poll();
        if (snapshot.version() == appliedQuantConnectVersion) {
            return;
        }
        appliedQuantConnectVersion = snapshot.version();
        Account acc = account(QUANTCONNECT_ACCOUNT);
        for (QuantConnectBridge.Target target : snapshot.targets()) {
            Contract c = contracts.get(target.symbol());
            if (c == null) continue;
            Position p = acc.positions.get(target.symbol());
            int current = p == null ? 0 : p.longLots - p.shortLots;
            int desired = target.lots();
            if (desired > current) {
                open(QUANTCONNECT_ACCOUNT, target.symbol(), "long", desired - current);
            } else if (desired < current) {
                int delta = current - desired;
                if (p != null && p.longLots > 0) {
                    close(QUANTCONNECT_ACCOUNT, target.symbol(), "long",
                            Math.min(delta, p.longLots));
                } else if (p != null && p.shortLots > 0) {
                    close(QUANTCONNECT_ACCOUNT, target.symbol(), "short",
                            Math.min(delta, p.shortLots));
                }
            }
        }
    }

    /** NBT persistence for contracts and accounts. */
    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putLong("tick", tick);
        root.putLong("currentDay", currentDay);
        root.putLong("physicalDeliveryLots", physicalDeliveryLots);
        root.putLong("physicalDeliveryQuantity", physicalDeliveryQuantity);
        root.putString("deliveredContracts", String.join(",", physicallyDelivered));
        ListTag cs = new ListTag();
        for (Contract c : contracts.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("code", c.code);
            t.putString("productCode", c.productCode);
            t.putLong("deliveryMonthIndex", c.deliveryMonthIndex);
            t.putLong("lastTradingTick", c.lastTradingTick);
            t.putDouble("prevSettlePrice", c.prevSettlePrice);
            t.putDouble("lastPrice", c.lastPrice);
            t.putDouble("limitUp", c.limitUp);
            t.putDouble("limitDown", c.limitDown);
            t.putDouble("marginRatio", c.marginRatio);
            t.putString("status", c.status.name());
            t.putInt("consecutiveLimitDays", c.consecutiveLimitDays);
            t.putBoolean("limitLocked", c.limitLocked);
            t.putInt("boardDaysLeft", c.boardDaysLeft);
            t.putLong("boardDecrementDay", c.boardDecrementDay);
            t.putLong("openInterest", c.openInterest);
            t.putLong("dayVolume", c.dayVolume);
            t.putDouble("dayNotional", c.dayNotional);
            cs.add(t);
        }
        root.put("contracts", cs);
        ListTag as = new ListTag();
        for (Account a : accounts.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", a.id);
            t.putDouble("cash", a.cash);
            t.putDouble("margin", a.margin);
            t.putBoolean("pendingForce", a.pendingForce);
            ListTag ps = new ListTag();
            for (Position p : a.positions.values()) {
                CompoundTag pt = new CompoundTag();
                pt.putString("contractCode", p.contractCode);
                pt.putInt("longLots", p.longLots);
                pt.putInt("shortLots", p.shortLots);
                pt.putDouble("avgLongPrice", p.avgLongPrice);
                pt.putDouble("avgShortPrice", p.avgShortPrice);
                ps.add(pt);
            }
            t.put("positions", ps);
            as.add(t);
        }
        root.put("accounts", as);
        return root;
    }

    public void load(CompoundTag root) {
        contracts.clear();
        accounts.clear();
        tick = root.getLong("tick");
        currentDay = root.getLong("currentDay");
        physicalDeliveryLots = root.getLong("physicalDeliveryLots");
        physicalDeliveryQuantity = root.getLong("physicalDeliveryQuantity");
        physicallyDelivered.clear();
        String delivered = root.getString("deliveredContracts");
        if (!delivered.isBlank()) {
            for (String code : delivered.split(",")) {
                if (!code.isBlank()) physicallyDelivered.add(code);
            }
        }
        ListTag cs = root.getList("contracts", Tag.TAG_COMPOUND);
        for (int i = 0; i < cs.size(); i++) {
            CompoundTag t = cs.getCompound(i);
            Contract c = new Contract();
            c.code = t.getString("code");
            c.productCode = t.getString("productCode");
            c.deliveryMonthIndex = t.getLong("deliveryMonthIndex");
            c.lastTradingTick = t.getLong("lastTradingTick");
            c.prevSettlePrice = t.getDouble("prevSettlePrice");
            c.lastPrice = t.getDouble("lastPrice");
            c.limitUp = t.getDouble("limitUp");
            c.limitDown = t.getDouble("limitDown");
            c.marginRatio = t.getDouble("marginRatio");
            c.status = Status.valueOf(t.getString("status"));
            c.consecutiveLimitDays = t.getInt("consecutiveLimitDays");
            c.limitLocked = t.getBoolean("limitLocked");
            c.boardDaysLeft = t.getInt("boardDaysLeft");
            c.boardDecrementDay = t.getLong("boardDecrementDay");
            c.openInterest = t.getLong("openInterest");
            c.dayVolume = t.getLong("dayVolume");
            c.dayNotional = t.getDouble("dayNotional");
            contracts.put(c.code, c);
        }
        ListTag as = root.getList("accounts", Tag.TAG_COMPOUND);
        for (int i = 0; i < as.size(); i++) {
            CompoundTag t = as.getCompound(i);
            Account a = new Account(t.getUUID("id"));
            a.cash = t.getDouble("cash");
            a.margin = t.getDouble("margin");
            a.pendingForce = t.getBoolean("pendingForce");
            ListTag ps = t.getList("positions", Tag.TAG_COMPOUND);
            for (int j = 0; j < ps.size(); j++) {
                CompoundTag pt = ps.getCompound(j);
                Position p = new Position();
                p.contractCode = pt.getString("contractCode");
                p.longLots = pt.getInt("longLots");
                p.shortLots = pt.getInt("shortLots");
                p.avgLongPrice = pt.getDouble("avgLongPrice");
                p.avgShortPrice = pt.getDouble("avgShortPrice");
                a.positions.put(p.contractCode, p);
            }
            accounts.put(a.id, a);
        }
    }

    /** Relational H2 mirror; engine_main blob remains the authoritative load path. */
    public void saveToH2(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM czce_contract");
            st.executeUpdate("DELETE FROM czce_position");
        }
        String cs = "INSERT INTO czce_contract (contract_code, product, delivery_month, last_trading_tick,"
                + " prev_settle, last_price, limit_up, limit_down, margin_ratio, status,"
                + " consecutive_limit_days, open_interest, day_volume, day_notional, limit_locked,"
                + " board_days_left, board_decrement_day) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(cs)) {
            for (Contract x : contracts.values()) {
                ps.setString(1, x.code);
                ps.setString(2, x.productCode);
                ps.setLong(3, x.deliveryMonthIndex);
                ps.setLong(4, x.lastTradingTick);
                ps.setDouble(5, x.prevSettlePrice);
                ps.setDouble(6, x.lastPrice);
                ps.setDouble(7, x.limitUp);
                ps.setDouble(8, x.limitDown);
                ps.setDouble(9, x.marginRatio);
                ps.setString(10, x.status.name());
                ps.setInt(11, x.consecutiveLimitDays);
                ps.setLong(12, x.openInterest);
                ps.setLong(13, x.dayVolume);
                ps.setDouble(14, x.dayNotional);
                ps.setBoolean(15, x.limitLocked);
                ps.setInt(16, x.boardDaysLeft);
                ps.setLong(17, x.boardDecrementDay);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        String as = "MERGE INTO czce_account (account_id, cash, margin, pending_force) KEY(account_id)"
                + " VALUES (?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(as)) {
            for (Account a : accounts.values()) {
                ps.setString(1, a.id.toString());
                ps.setDouble(2, a.cash);
                ps.setDouble(3, a.margin);
                ps.setBoolean(4, a.pendingForce);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        String psql = "INSERT INTO czce_position (account_id, contract_code, long_lots, short_lots,"
                + " avg_long, avg_short) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(psql)) {
            for (Account a : accounts.values()) {
                for (Position p : a.positions.values()) {
                    ps.setString(1, a.id.toString());
                    ps.setString(2, p.contractCode);
                    ps.setInt(3, p.longLots);
                    ps.setInt(4, p.shortLots);
                    ps.setDouble(5, p.avgLongPrice);
                    ps.setDouble(6, p.avgShortPrice);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Contract c : contracts.values()) {
            ProductSpec s = products.get(c.productCode);
            out.add(String.format(Locale.ROOT, "%s %s settle=%.2f last=%.2f limit=[%.2f,%.2f] margin=%.0f%% OI=%d",
                    c.code, s == null ? c.productCode : s.name(), c.prevSettlePrice, c.lastPrice,
                    c.limitDown, c.limitUp, c.marginRatio * 100, c.openInterest));
        }
        return out;
    }
}
