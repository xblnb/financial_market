package com.example.financialmarket.engine.futures;

import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.CommodityEntry;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.calendar.TradingCalendar;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.*;

/**
 * 期货市场（CZCE 规则，CF-5~CF-8）。
 *
 * <p>遵循郑州商品交易所（CZCE）交易规定框架：
 * <ul>
 *   <li><b>合约月份</b>：确定性合约日历（{@code CONTRACT_MONTH_TICKS} 派生交割月），连续月份集合，代码 {@code <品种>_<YYMM>}；</li>
 *   <li><b>涨跌停板</b>：锚<b>昨结算价</b> ±{@code FUTURES_LIMIT_RATIO}（默认 4%），连续涨跌停扩大 + 提高保证金；</li>
 *   <li><b>保证金</b>：一般/次月/交割月分档（8%/12%/20%），开仓冻结、平仓/强平释放；</li>
 *   <li><b>逐日无负债结算</b>：每交易日按当日成交加权平均价结算价盯市，盈亏进出保证金账户；</li>
 *   <li><b>强制平仓</b>：保证金不足（未追保）/ 超持仓限额 → 确定性平仓；</li>
 *   <li><b>交割</b>：最后交易日后现金结算交割，合约 EXPIRED 退出。</li>
 * </ul>
 *
 * <p>交易日历复用 A 股 {@link TradingCalendar}（5 分钟交易日/全天连续/每 5 交易日休市 2 日）。
 * 期货价锚定商品现货 + 持有成本（利率+存储费率）+ 期现收敛；宏观（法币 M2/通胀/政策利率）只读消费。
 * 全程确定性、零新随机；账户/合约/持仓随 {@link #save()}/{@link #load(CompoundTag)} 持久化（CF-10）。
 */
public class FuturesMarket {
    // ===== 参数（§9，futures.*） =====
    public static final int UNDERLYING_TOP_K = 24;         // 可交付可堆叠物品 Top-K 作期货品种
    public static final long CONTRACT_MONTH_TICKS = 6000L * 30; // 一「合约月」tick 跨度
    public static final long TRADING_DAY_TICKS = 6000L;    // 一个交易日（A 股日历）
    public static final int LISTED_MONTHS = 6;             // 每品种上市近月+后续月份数
    public static final int MULTIPLIER = 10;               // 每手单位数
    public static final double FUTURES_LIMIT_RATIO = 0.04; // ±4%（CZCE 一般商品典型）
    public static final int LIMIT_EXPAND_DAYS = 2;         // 连续涨跌停 N 日后扩大
    public static final double LIMIT_EXPAND_FACTOR = 1.5;  // 扩大倍数
    public static final double MAX_LIMIT_RATIO = 0.10;     // 涨跌停上限
    public static final double BASE_MARGIN_RATIO = 0.08;   // 一般月份保证金
    public static final double NEAR_MONTH_MARGIN = 0.12;   // 次月保证金
    public static final double DELIVERY_MONTH_MARGIN = 0.20; // 交割月保证金
    public static final double MAX_MARGIN_RATIO = 0.50;    // 保证金上限
    public static final double MAINTENANCE_RATIO = 1.0;    // 保证金权益/占用 维持线
    public static final int TOP_UP_GRACE_TRADING_DAYS = 1; // 追保宽限交易日数
    public static final int POSITION_LIMIT_LOTS = 1000;    // 单玩家单合约持仓手数上限
    public static final double STORAGE_COST_PER_MONTH = 0.001; // 持有成本存储费率
    public static final double CARRY_CONVERGE_RATE = 0.05; // 期现收敛速率

    private final Map<String, FuturesContract> contracts = new LinkedHashMap<>();
    private final Map<UUID, FuturesAccount> accounts = new LinkedHashMap<>();
    /** 期货品种：品种代码 → 标的物品。 */
    private final Map<String, Item> underlyings = new LinkedHashMap<>();

    private MarketEngine engine;
    private boolean tradingOpen = true;
    private boolean registered = false;

    public FuturesMarket() { }

    public void setEngine(MarketEngine engine) { this.engine = engine; }

    /** 玩家期货保证金账户（CF-5）。 */
    public static class FuturesAccount {
        public final UUID playerId;
        public double freeCash = 0;        // 保证金余额（可用资金，持久化）
        public double frozenMargin = 0;    // 冻结保证金（开仓占用）
        public int marginCallStreak = 0;   // 连续追保不足交易日计数
        public final Map<String, FuturesPosition> positions = new LinkedHashMap<>();
        public FuturesAccount(UUID playerId) { this.playerId = playerId; }
        public double equity() { return freeCash + frozenMargin; }
    }

    // ===== 品种注册（CF-5）：可交付可堆叠物品 Top-K） =====
    public void registerUnderlyings(MarketEngine engine) {
        if (registered || engine == null) return;
        registered = true;
        underlyings.clear();
        List<CommodityEntry> stackable = new ArrayList<>();
        for (CommodityEntry e : engine.getCommodityMarket().getAllCommodities()) {
            if ("item".equals(e.getKind()) && e.getItem() != null
                    && new net.minecraft.world.item.ItemStack(e.getItem()).isStackable()) {
                stackable.add(e);
            }
        }
        stackable.sort(Comparator.comparingDouble(CommodityEntry::getBasePrice).reversed());
        int k = Math.min(UNDERLYING_TOP_K, stackable.size());
        for (int i = 0; i < k; i++) {
            Item it = stackable.get(i).getItem();
            underlyings.put(productCode(it), it);
        }
    }

    private static String productCode(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id.getPath().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    private static String monthCode(long monthIndex) {
        int year = 26 + (int) (monthIndex / 12);
        int month = (int) (monthIndex % 12) + 1;
        return String.format(Locale.ROOT, "%02d%02d", year % 100, month);
    }

    // ===== 合约日历（确定性，CF-5） =====
    private void ensureContracts(long currentMonthIndex) {
        if (underlyings.isEmpty()) return;
        for (Map.Entry<String, Item> e : underlyings.entrySet()) {
            for (int i = 0; i < LISTED_MONTHS; i++) {
                long m = currentMonthIndex + i;
                String code = e.getKey() + "_" + monthCode(m);
                if (contracts.containsKey(code)) continue;
                FuturesContract c = new FuturesContract();
                c.code = code;
                c.underlying = e.getKey();
                c.itemId = BuiltInRegistries.ITEM.getKey(e.getValue()).toString();
                c.item = e.getValue();
                c.multiplier = MULTIPLIER;
                c.monthIndex = m;
                c.deliveryMonthTicks = m * CONTRACT_MONTH_TICKS;
                c.lastTradingTicks = c.deliveryMonthTicks + CONTRACT_MONTH_TICKS - TRADING_DAY_TICKS;
                c.status = FuturesContract.Status.LISTED;
                double spot = currentSpot(c);
                if (spot <= 0) spot = 1.0;
                c.prevSettlePrice = spot;
                c.lastPrice = spot;
                // F2（连接审计修复 F6）：新合约上市即按交割月分档初始化保证金（不再一律 8%，
                // 避免近月/交割月合约在首个交易日前以低保证金开仓的窗口期漏洞）。
                c.marginRatio = tierMarginRatio(c, currentMonthIndex);
                c.limitUp = round2(spot * (1 + FUTURES_LIMIT_RATIO));
                c.limitDown = round2(spot * (1 - FUTURES_LIMIT_RATIO));
                contracts.put(code, c);
            }
        }
    }

    private double currentSpot(FuturesContract c) {
        if (c.item == null) return 0;
        CommodityEntry entry = engine.getCommodityMarket().getCommodity(c.item);
        return entry != null ? entry.getPrice() : 0;
    }

    private static double tierMarginRatio(FuturesContract c, long currentMonthIndex) {
        long monthsLeft = c.monthIndex - currentMonthIndex;
        if (monthsLeft <= 1) return DELIVERY_MONTH_MARGIN;   // 交割月
        if (monthsLeft <= 2) return NEAR_MONTH_MARGIN;       // 次月
        return BASE_MARGIN_RATIO;                            // 一般月份
    }

    // ===== 主 tick（CF-4/CF-6/CF-8，时间权威 = engine tick 计数器） =====
    public void tick(ServerLevel level, MarketEngine engine, TradingCalendar calendar) {
        this.engine = engine;
        long tc = engine.getTickCounter();
        this.tradingOpen = calendar.isMarketOpen();
        long currentMonthIndex = tc / CONTRACT_MONTH_TICKS;

        ensureContracts(currentMonthIndex);
        updateFuturesPrices(engine, tc);
        markDelivering(tc);

        if (calendar.isSessionEnd()) {
            settleDaily(tc);
            riskControlAll(level);
            deliverExpired(level);
        }
        if (calendar.isTradingDayStart()) {
            for (FuturesContract c : contracts.values()) {
                if (c.status == FuturesContract.Status.LISTED) startTradingDay(c, currentMonthIndex);
            }
        }
    }

    private void markDelivering(long tc) {
        for (FuturesContract c : contracts.values()) {
            if (c.status == FuturesContract.Status.LISTED && tc > c.lastTradingTicks) {
                c.status = FuturesContract.Status.DELIVERING;
            }
        }
    }

    /** 期现联动 + 持有成本 + 期现收敛（CF-8）。确定性。 */
    private void updateFuturesPrices(MarketEngine engine, long tc) {
        for (FuturesContract c : contracts.values()) {
            if (c.status != FuturesContract.Status.LISTED) continue;
            double spot = currentSpot(c);
            if (spot <= 0) spot = c.prevSettlePrice;
            double monthsToDelivery = (double) (c.deliveryMonthTicks - tc) / CONTRACT_MONTH_TICKS;
            if (monthsToDelivery < 0) monthsToDelivery = 0;
            double policyRate = engine.getCentralBank().getDiscountRate();
            double carry = monthsToDelivery * (policyRate + STORAGE_COST_PER_MONTH);
            double target = spot * (1 + carry);
            if (target <= 0) target = spot;
            double next = c.lastPrice + CARRY_CONVERGE_RATE * (target - c.lastPrice);
            if (c.limitUp > 0 && next > c.limitUp) next = c.limitUp;
            if (c.limitDown > 0 && next < c.limitDown) next = c.limitDown;
            c.lastPrice = round2(next);
        }
    }

    /** 新交易日：按昨结算价重算涨跌停带 + 保证金分档/连续涨跌停扩大（CF-6）。 */
    private void startTradingDay(FuturesContract c, long currentMonthIndex) {
        double ratio = tierMarginRatio(c, currentMonthIndex);
        if (c.consecutiveLimitDays >= LIMIT_EXPAND_DAYS) {
            ratio = Math.min(ratio * LIMIT_EXPAND_FACTOR, MAX_MARGIN_RATIO);
        }
        c.marginRatio = ratio;
        double lr = FUTURES_LIMIT_RATIO;
        if (c.consecutiveLimitDays >= LIMIT_EXPAND_DAYS) {
            lr = Math.min(lr * LIMIT_EXPAND_FACTOR, MAX_LIMIT_RATIO);
        }
        c.limitUp = round2(c.prevSettlePrice * (1 + lr));
        c.limitDown = round2(c.prevSettlePrice * (1 - lr));
    }

    /** 逐日无负债结算：按当日成交加权平均价结算价盯市（CF-6）。 */
    private void settleDaily(long tc) {
        for (FuturesContract c : contracts.values()) {
            if (c.status == FuturesContract.Status.EXPIRED) continue;
            double settle = computeSettlePrice(c);
            if (c.lastPrice >= c.limitUp || c.lastPrice <= c.limitDown) c.consecutiveLimitDays++;
            else c.consecutiveLimitDays = 0;
            c.prevSettlePrice = settle;
            for (FuturesAccount acc : accounts.values()) {
                FuturesPosition pos = acc.positions.get(c.code);
                if (pos == null || pos.isEmpty()) continue;
                double pnl = (settle - pos.avgLongPrice) * c.multiplier * pos.longLots
                        + (pos.avgShortPrice - settle) * c.multiplier * pos.shortLots;
                acc.freeCash += pnl; // 逐日盯市：盈亏进出保证金账户
                pos.avgLongPrice = settle;
                pos.avgShortPrice = settle;
                if (pos.longLots == 0) pos.longOpenTick = 0;
                if (pos.shortLots == 0) pos.shortOpenTick = 0;
            }
            c.dayTradeNotional = 0;
            c.dayTradeQuantity = 0;
        }
    }

    private double computeSettlePrice(FuturesContract c) {
        if (c.dayTradeQuantity > 0) return round2(c.dayTradeNotional / c.dayTradeQuantity);
        if (c.lastPrice > 0) return c.lastPrice;
        return c.prevSettlePrice;
    }

    // ===== 风险控制（强平，CF-7） =====
    private void riskControlAll(ServerLevel level) {
        for (FuturesAccount acc : new ArrayList<>(accounts.values())) {
            checkMarginCall(acc);
            enforcePositionLimit(acc);
            // F2（连接审计修复 F2）：强平/限仓后账户无持仓 → 全额了结（防资金滞留）。
            realizeAccount(level, acc);
        }
    }

    private double requiredMargin(FuturesAccount acc) {
        double req = 0;
        for (FuturesPosition pos : acc.positions.values()) {
            FuturesContract c = contracts.get(pos.contractCode);
            if (c == null) continue;
            req += c.lastPrice * c.multiplier * pos.netLots() * c.marginRatio;
        }
        return req;
    }

    private void checkMarginCall(FuturesAccount acc) {
        if (acc.equity() < requiredMargin(acc) * MAINTENANCE_RATIO) {
            acc.marginCallStreak++;
            if (acc.marginCallStreak >= TOP_UP_GRACE_TRADING_DAYS) {
                int guard = 0;
                while (acc.equity() < requiredMargin(acc) * MAINTENANCE_RATIO && guard < 64) {
                    Worst w = findWorstPosition(acc);
                    if (w == null) break;
                    FuturesContract c = contracts.get(w.pos.contractCode);
                    if (c == null) break;
                    int lots = w.side.equals("long") ? w.pos.longLots : w.pos.shortLots;
                    forceClosePosition(acc, w.pos, c, w.side, lots, c.lastPrice);
                    guard++;
                }
            }
        } else {
            acc.marginCallStreak = 0;
        }
    }

    private void enforcePositionLimit(FuturesAccount acc) {
        for (FuturesPosition pos : new ArrayList<>(acc.positions.values())) {
            FuturesContract c = contracts.get(pos.contractCode);
            if (c == null) continue;
            if (pos.longLots > POSITION_LIMIT_LOTS)
                forceClosePosition(acc, pos, c, "long", pos.longLots - POSITION_LIMIT_LOTS, c.lastPrice);
            if (pos.shortLots > POSITION_LIMIT_LOTS)
                forceClosePosition(acc, pos, c, "short", pos.shortLots - POSITION_LIMIT_LOTS, c.lastPrice);
        }
    }

    private static final class Worst {
        FuturesPosition pos;
        String side;
        double loss;
    }

    private Worst findWorstPosition(FuturesAccount acc) {
        Worst worst = null;
        for (FuturesPosition pos : acc.positions.values()) {
            FuturesContract c = contracts.get(pos.contractCode);
            if (c == null) continue;
            if (pos.longLots > 0) {
                double loss = (pos.avgLongPrice - c.lastPrice) * c.multiplier * pos.longLots;
                if (worst == null || loss > worst.loss) {
                    Worst w = new Worst(); w.pos = pos; w.side = "long"; w.loss = loss; worst = w;
                }
            }
            if (pos.shortLots > 0) {
                double loss = (c.lastPrice - pos.avgShortPrice) * c.multiplier * pos.shortLots;
                if (worst == null || loss > worst.loss) {
                    Worst w = new Worst(); w.pos = pos; w.side = "short"; w.loss = loss; worst = w;
                }
            }
        }
        return worst;
    }

    /** 强平：按当前价平仓，资金结入账户余额（离线安全，确定性）。 */
    private void forceClosePosition(FuturesAccount acc, FuturesPosition pos, FuturesContract c,
                                    String side, int lots, double price) {
        double openAvg = side.equals("long") ? pos.avgLongPrice : pos.avgShortPrice;
        // F2（连接审计修复 F3）：释放额以实际冻结为上限——开仓时按当时档位冻结（如 8%），
        // 交割月档位升至 20% 后按当前档位计算会「释放超过冻结」使 frozenMargin 转负（账实不一致）。
        double released = Math.min(openAvg * c.multiplier * lots * c.marginRatio, acc.frozenMargin);
        double pnl = side.equals("long")
                ? (price - openAvg) * c.multiplier * lots
                : (openAvg - price) * c.multiplier * lots;
        acc.frozenMargin -= released;
        acc.freeCash += released + pnl;
        reducePosition(pos, side, lots);
        if (pos.isEmpty()) acc.positions.remove(pos.contractCode);
        c.openInterest = Math.max(0, c.openInterest - lots);
    }

    private void reducePosition(FuturesPosition pos, String side, int lots) {
        if (side.equals("long")) {
            pos.longLots -= lots;
            if (pos.longLots <= 0) { pos.longLots = 0; pos.avgLongPrice = 0; pos.longOpenTick = 0; }
        } else {
            pos.shortLots -= lots;
            if (pos.shortLots <= 0) { pos.shortLots = 0; pos.avgShortPrice = 0; pos.shortOpenTick = 0; }
        }
    }

    // ===== 交割（CF-7，现金结算交割） =====
    private void deliverExpired(ServerLevel level) {
        for (FuturesContract c : new ArrayList<>(contracts.values())) {
            if (c.status != FuturesContract.Status.DELIVERING) continue;
            double deliveryPrice = c.prevSettlePrice > 0 ? c.prevSettlePrice : c.lastPrice;
            for (FuturesAccount acc : accounts.values()) {
                FuturesPosition pos = acc.positions.get(c.code);
                if (pos == null || pos.isEmpty()) continue;
                double pnl = (deliveryPrice - pos.avgLongPrice) * c.multiplier * pos.longLots
                        + (pos.avgShortPrice - deliveryPrice) * c.multiplier * pos.shortLots;
                // F2（连接审计修复 F3）：释放额以实际冻结为上限（防档位上升致 frozenMargin 转负）。
                double released = Math.min(
                        (pos.avgLongPrice * pos.longLots + pos.avgShortPrice * pos.shortLots)
                                * c.multiplier * c.marginRatio,
                        acc.frozenMargin);
                acc.frozenMargin -= released;
                acc.freeCash += released + pnl;
                acc.positions.remove(c.code);
                // F2（连接审计修复 F2）：现金结算交割后账户清仓 → 全额了结 freeCash/残余保证金到玩家现金
                // （修复逐日盈亏滞留 freeCash 无法取现；离线走引擎待补队列）。
                realizeAccount(level, acc);
            }
            c.openInterest = 0;
            c.status = FuturesContract.Status.EXPIRED;
        }
    }

    /**
     * F2（连接审计修复 F2）：账户无持仓时全额了结——把 freeCash（逐日盯市累计盈亏 + 平仓释放）
     * 与残余冻结保证金一并转回玩家现金。确定性、无随机；离线玩家经引擎待补队列登录回放。
     */
    private void realizeAccount(ServerLevel level, FuturesAccount acc) {
        if (acc == null || !acc.positions.isEmpty()) return;
        double total = acc.freeCash + acc.frozenMargin;
        if (total == 0) return;
        if (engine != null && level != null) {
            engine.creditPlayerCash(level, acc.playerId, total);
        }
        acc.freeCash = 0;
        acc.frozenMargin = 0;
    }

    // ===== 交易（CF-5/CF-6/CF-7） =====
    public boolean canTrade() {
        if (engine == null || !engine.isFuturesEnabled()) return false;
        return tradingOpen;
    }

    /** 开仓（long=开多/short=开空），成功返回 null，失败返回中文原因。 */
    public String open(ServerPlayer player, String code, String side, int lots) {
        if (!canTrade()) return "休市中，期货暂不可交易";
        // t3 持卡门控（BT-4）：期货开仓须持储蓄卡；banking 关闭时 engine.hasBankCard 恒 true（BT-10）
        if (!engine.hasBankCard(player.getUUID())) return "请先办理储蓄卡";
        FuturesContract c = contracts.get(code);
        if (c == null || c.status != FuturesContract.Status.LISTED) return "合约不存在或已到期";
        if (lots <= 0) return "手数须为正";
        FuturesAccount acc = getOrCreateAccount(player.getUUID());
        FuturesPosition pos = acc.positions.computeIfAbsent(code, k -> {
            FuturesPosition p = new FuturesPosition(); p.contractCode = code; return p;
        });
        int current = side.equals("long") ? pos.longLots : pos.shortLots;
        if (current + lots > POSITION_LIMIT_LOTS) return "超出持仓限额（" + POSITION_LIMIT_LOTS + " 手）";
        double price = clampTradePrice(c, c.lastPrice);
        double margin = price * c.multiplier * lots * c.marginRatio;
        PlayerEconomy eco = PlayerEconomy.get(player);
        if (eco.getCash() < margin) return "现金不足，无法缴付保证金";
        eco.setCash(eco.getCash() - margin);
        acc.frozenMargin += margin;
        long tc = engine.getTickCounter();
        if (side.equals("long")) {
            double total = pos.avgLongPrice * pos.longLots + price * lots;
            pos.longLots += lots;
            pos.avgLongPrice = total / pos.longLots;
            if (pos.longOpenTick == 0) pos.longOpenTick = tc;
        } else {
            double total = pos.avgShortPrice * pos.shortLots + price * lots;
            pos.shortLots += lots;
            pos.avgShortPrice = total / pos.shortLots;
            if (pos.shortOpenTick == 0) pos.shortOpenTick = tc;
        }
        c.openInterest += lots;
        recordTrade(c, price, lots);
        return null;
    }

    /** 平仓（long=平多/short=平空），成功返回 null，失败返回中文原因。 */
    public String close(ServerPlayer player, String code, String side, int lots) {
        if (!canTrade()) return "休市中，期货暂不可交易";
        // t3 持卡门控（BT-4）：期货平仓须持储蓄卡；banking 关闭时 engine.hasBankCard 恒 true（BT-10）
        if (!engine.hasBankCard(player.getUUID())) return "请先办理储蓄卡";
        FuturesContract c = contracts.get(code);
        if (c == null || c.status != FuturesContract.Status.LISTED) return "合约不存在或已到期";
        if (lots <= 0) return "手数须为正";
        FuturesAccount acc = getOrCreateAccount(player.getUUID());
        FuturesPosition pos = acc.positions.get(code);
        if (pos == null) return "无该合约持仓";
        int have = side.equals("long") ? pos.longLots : pos.shortLots;
        if (have < lots) return "平仓手数超过持仓";
        double openAvg = side.equals("long") ? pos.avgLongPrice : pos.avgShortPrice;
        double price = clampTradePrice(c, c.lastPrice);
        // F2（连接审计修复 F3）：释放额以实际冻结为上限（防交割月档位上升致 frozenMargin 转负）。
        double released = Math.min(openAvg * c.multiplier * lots * c.marginRatio, acc.frozenMargin);
        double pnl = side.equals("long")
                ? (price - openAvg) * c.multiplier * lots
                : (openAvg - price) * c.multiplier * lots;
        acc.frozenMargin -= released;
        reducePosition(pos, side, lots);
        if (pos.isEmpty()) acc.positions.remove(code);
        PlayerEconomy eco = PlayerEconomy.get(player);
        eco.setCash(eco.getCash() + released + pnl); // 返还保证金 + 平仓损益（现金）
        c.openInterest = Math.max(0, c.openInterest - lots);
        recordTrade(c, price, lots);
        // F2（连接审计修复 F2）：账户清仓时全额了结——把逐日盯市累计的 freeCash（及残余冻结保证金）
        // 一并转回玩家现金，修复「逐日盈亏滞留 freeCash 无法取现」的湮没钱缺口。
        realizeAccount(player.serverLevel(), acc);
        return null;
    }

    private double clampTradePrice(FuturesContract c, double price) {
        if (c.limitDown > 0 && price < c.limitDown) return c.limitDown;
        if (c.limitUp > 0 && price > c.limitUp) return c.limitUp;
        return price;
    }

    private void recordTrade(FuturesContract c, double price, int lots) {
        c.dayTradeNotional += price * lots;
        c.dayTradeQuantity += lots;
    }

    // ===== 查询 =====
    public FuturesAccount getOrCreateAccount(UUID playerId) {
        return accounts.computeIfAbsent(playerId, FuturesAccount::new);
    }
    public FuturesAccount getAccount(UUID playerId) { return accounts.get(playerId); }
    public Collection<FuturesAccount> getAllAccounts() { return accounts.values(); }
    public FuturesContract getContract(String code) { return contracts.get(code); }
    public Collection<FuturesContract> getAllContracts() { return contracts.values(); }

    // ===== 持久化（CF-10） =====
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag ct = new ListTag();
        for (FuturesContract c : contracts.values()) ct.add(c.toTag());
        tag.put("contracts", ct);
        ListTag at = new ListTag();
        for (FuturesAccount a : accounts.values()) at.add(accountToTag(a));
        tag.put("accounts", at);
        return tag;
    }

    private CompoundTag accountToTag(FuturesAccount a) {
        CompoundTag t = new CompoundTag();
        t.putUUID("playerId", a.playerId);
        t.putDouble("freeCash", a.freeCash);
        t.putDouble("frozenMargin", a.frozenMargin);
        t.putInt("marginCallStreak", a.marginCallStreak);
        ListTag pl = new ListTag();
        for (FuturesPosition p : a.positions.values()) pl.add(p.toTag());
        t.put("positions", pl);
        return t;
    }

    public void load(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("contracts")) {
            ListTag ct = tag.getList("contracts", Tag.TAG_COMPOUND);
            for (int i = 0; i < ct.size(); i++) {
                FuturesContract c = FuturesContract.fromTag(ct.getCompound(i));
                if (c.itemId != null && !c.itemId.isEmpty()) {
                    Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(c.itemId));
                    c.item = item;
                }
                contracts.put(c.code, c);
            }
        }
        if (tag.contains("accounts")) {
            ListTag at = tag.getList("accounts", Tag.TAG_COMPOUND);
            for (int i = 0; i < at.size(); i++) {
                CompoundTag t = at.getCompound(i);
                FuturesAccount a = new FuturesAccount(t.getUUID("playerId"));
                a.freeCash = t.getDouble("freeCash");
                a.frozenMargin = t.getDouble("frozenMargin");
                a.marginCallStreak = t.getInt("marginCallStreak");
                if (t.contains("positions")) {
                    ListTag pl = t.getList("positions", Tag.TAG_COMPOUND);
                    for (int j = 0; j < pl.size(); j++) {
                        FuturesPosition p = FuturesPosition.fromTag(pl.getCompound(j));
                        a.positions.put(p.contractCode, p);
                    }
                }
                accounts.put(a.playerId, a);
            }
        }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
