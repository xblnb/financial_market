package com.example.financialmarket.engine.margin;

import com.example.financialmarket.engine.AssetSnapshot;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.MarketState;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import com.example.financialmarket.engine.calendar.TradingCalendar;
import com.example.financialmarket.engine.margin.MarginAccount.MarginRiskBand;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 融资融券引擎单例（t3 落地，替代原 4 行空壳；t2 完善：多级风险线/持仓限额/逐日多级追保/时间权威纯化）。
 *
 * <p>职责：全服融资融券账户注册表 + 入/出金 + 开/平仓校验 + 逐日/tick 结算与强平
 * + 可借券源池（融券）。由 {@link MarketEngine} 持有单例（{@code new MarginManager(seed)}），
 * 网络包与 tick 均经 {@code engine.getMarginManager()} 访问。
 *
 * <p>确定性：开仓/结算/强平为纯确定性逻辑（真实现价 + 固定阈值），不引入新的
 * {@code Math.random/new Random}（R0-3）。A 股语义（R0-6）：融资买入 100 整数倍、强平价
 * 钳涨跌停带、融券走可借券源（不污染真实 T+1 账本）。
 *
 * <p>时间权威：逐日/日内结算一律以引擎 tick 计数器门控（不依赖可被 {@code /time} 改动的
 * {@code gameTime}），保证同种子跨时段确定性可复现（AS-M3）。
 */
public class MarginManager {
    /** 开仓最低保证金比例（保证金 ≥ 仓位价值/杠杆 × 该比例）。 */
    public static final double MIN_COLLATERAL_RATIO = 0.5;
    /** 维持担保比例（A 股常用 130%）；兼容别名，等于强平线。 */
    public static final double MAINTENANCE_RATIO = 1.3;
    /** 强平触发线（AS-M1 多级风险线下线）。 */
    public static final double FORCE_CLOSE_RATIO = 1.3;
    /** 预警线：< 该值 → 禁开新仓 + 警示（AS-M1）。 */
    public static final double WARNING_RATIO = 1.5;
    /** 追加保证金线：< 该值 → 发追保通知、启动宽限计数（AS-M1/AS-M3）。 */
    public static final double MARGIN_CALL_RATIO = 1.4;
    /** 开仓最低线：开仓后维持担保比例须 ≥ 该值（避免开仓即预警/追保，AS-M1）。 */
    public static final double OPEN_MIN_RATIO = 1.5;
    /** 追保宽限交易日数（AS-M3，对齐期货 TOP_UP_GRACE_TRADING_DAYS）。 */
    public static final int MARGIN_CALL_GRACE_TRADING_DAYS = 1;
    /** 最大杠杆（A 股常规 1~5 倍；可配置，AS-M4）。 */
    public static final int MAX_LEVERAGE = 5;
    /** 单账户总负债上限（AS-M2）。 */
    public static final double MAX_LIABILITY_PER_ACCOUNT = 5_000_000;
    /** 单资产名义上限（AS-M2）。 */
    public static final double MAX_POSITION_VALUE_PER_ASSET = 2_000_000;
    /** 单账户单资产最大融券数量（AS-M2）。 */
    public static final int MAX_SHORT_QTY_PER_ASSET = 100_000;
    /** 单资产名义 / 账户总资产 集中度上限（AS-M2）。 */
    public static final double CONCENTRATION_LIMIT = 0.30;
    /** 每资产默认可借券源（简化常量池；借出扣减、还券回补）。 */
    public static final int DEFAULT_SHORTABLE_POOL = 100000;

    // ---- AS-M1/AS-M2/AS-M4 可配置参数（默认取上方常量，供 margin.* 参数包注入） ----
    private int maxLeverage = MAX_LEVERAGE;
    private double warningRatio = WARNING_RATIO;
    private double marginCallRatio = MARGIN_CALL_RATIO;
    private double forceCloseRatio = FORCE_CLOSE_RATIO;
    private double openMinRatio = OPEN_MIN_RATIO;
    private double minCollateralRatio = MIN_COLLATERAL_RATIO;
    private int marginCallGraceTradingDays = MARGIN_CALL_GRACE_TRADING_DAYS;
    private double maxLiabilityPerAccount = MAX_LIABILITY_PER_ACCOUNT;
    private double maxPositionValuePerAsset = MAX_POSITION_VALUE_PER_ASSET;
    private int maxShortQtyPerAsset = MAX_SHORT_QTY_PER_ASSET;
    private double concentrationLimit = CONCENTRATION_LIMIT;

    private final Map<UUID, MarginAccount> accounts = new HashMap<>();
    /** 可借券源池：资产 → 可借数量（融券扣减/还券回补）。 */
    private final Map<String, Integer> shortablePool = new HashMap<>();
    private final long seed;

    public MarginManager() {
        this(0L);
    }

    public MarginManager(long seed) {
        this.seed = seed; // 保留确定性子流预留（当前结算/强平为纯确定，无需随机）
    }

    public enum OpenResult {
        OK, COLLATERAL_INSUFFICIENT, LEVERAGE_EXCEEDED, NOT_LISTED, SHORT_UNAVAILABLE, A_SHARE_REJECTED,
        /** AS-M1：账户处于预警/追保/强平区带，禁止新开仓。 */
        RISK_BAND_EXCEEDED,
        /** AS-M2：开仓后超过任一持仓限额。 */
        POSITION_LIMIT_EXCEEDED
    }

    public MarginAccount getOrCreateAccount(UUID playerId) {
        return accounts.computeIfAbsent(playerId, MarginAccount::new);
    }

    public MarginAccount getAccount(UUID playerId) {
        return accounts.get(playerId);
    }

    // ---- AS-M1/AS-M2/AS-M4 可配置参数访问器（供 margin.* 参数包 / 测试注入） ----
    public int getMaxLeverage() { return maxLeverage; }
    public void setMaxLeverage(int maxLeverage) { this.maxLeverage = Math.max(1, maxLeverage); }
    public double getWarningRatio() { return warningRatio; }
    public double getMarginCallRatio() { return marginCallRatio; }
    public double getForceCloseRatio() { return forceCloseRatio; }
    public double getOpenMinRatio() { return openMinRatio; }
    public double getMinCollateralRatio() { return minCollateralRatio; }
    public int getMarginCallGraceTradingDays() { return marginCallGraceTradingDays; }
    public double getMaxLiabilityPerAccount() { return maxLiabilityPerAccount; }
    public double getMaxPositionValuePerAsset() { return maxPositionValuePerAsset; }
    public int getMaxShortQtyPerAsset() { return maxShortQtyPerAsset; }
    public double getConcentrationLimit() { return concentrationLimit; }

    /** 统一注入全部多级风险线/限额参数（配置合法则覆盖，非法回退默认，AS-M1 §2.2）。 */
    public void configure(int maxLeverage, double warning, double marginCall, double forceClose,
                          double openMin, double minCollateral, int graceTradingDays,
                          double maxLiability, double maxPositionValue, int maxShortQty, double concentration) {
        if (warning >= marginCall && marginCall > forceClose) {
            this.warningRatio = warning;
            this.marginCallRatio = marginCall;
            this.forceCloseRatio = forceClose;
            this.openMinRatio = (openMin > forceClose) ? openMin : OPEN_MIN_RATIO;
        }
        if (maxLeverage >= 1) this.maxLeverage = maxLeverage;
        if (minCollateral > 0) this.minCollateralRatio = minCollateral;
        if (graceTradingDays >= 1) this.marginCallGraceTradingDays = graceTradingDays;
        if (maxLiability > 0) this.maxLiabilityPerAccount = maxLiability;
        if (maxPositionValue > 0) this.maxPositionValuePerAsset = maxPositionValue;
        if (maxShortQty > 0) this.maxShortQtyPerAsset = maxShortQty;
        if (concentration > 0 && concentration <= 1) this.concentrationLimit = concentration;
    }

    /** 全服账户快照（供 UI / 验证 / 持久化）。 */
    public Map<UUID, MarginAccount> snapshot() {
        return new HashMap<>(accounts);
    }

    /**
     * 入金：把金额计入该玩家保证金（确定性记账）。
     * 注意：真实扣减玩家现金发生在触发层（packet / 命令），此处只入账保证金。
     */
    public boolean deposit(UUID playerId, double amount) {
        if (playerId == null || amount <= 0) return false;
        getOrCreateAccount(playerId).addCollateral(amount);
        return true;
    }

    /** 出金：不得使维持担保比例低于预警线（AS-M1 §4.5）；追保/强平区带禁止出金。 */
    public boolean withdraw(UUID playerId, double amount, MarketEngine engine) {
        MarginAccount acc = accounts.get(playerId);
        if (acc == null || amount <= 0) return false;
        MarginRiskBand band = acc.getRiskBand();
        if (band == MarginRiskBand.MARGIN_CALL || band == MarginRiskBand.FORCE_CLOSE) return false;
        Map<String, Double> prices = currentPrices(engine.getState());
        double liab = acc.getLiability(prices);
        if (liab > 0) {
            double projectedRatio = (acc.getEquity(prices) - amount) / liab;
            if (projectedRatio < warningRatio) return false;
        }
        acc.removeCollateral(amount);
        return true;
    }

    /**
     * 统一开仓路径（经网络包 MarginOrderPacket 触发）。
     * 校验：杠杆上限（可配置）、资产在列、A 股一手整数倍（融资买入）、融券券源、
     * 保证金足够（含绝对下限）、账户非预警/追保/强平区带、持仓限额、
     * 以及「开仓后维持担保比例 ≥ 开仓最低线」（避免开仓即预警/追保）。
     */
    public OpenResult openPosition(UUID playerId, String assetId, int quantity, int leverage, boolean isShort, MarketEngine engine) {
        if (playerId == null || assetId == null || quantity <= 0) return OpenResult.NOT_LISTED;
        if (leverage <= 0 || leverage > maxLeverage) return OpenResult.LEVERAGE_EXCEEDED;
        MarketState state = engine.getState();
        AssetSnapshot snap = state.getAsset(assetId);
        if (snap == null || snap.getLastPrice() <= 0) return OpenResult.NOT_LISTED;
        double price = snap.getLastPrice();

        // A 股（R0-6）：融资买入一手 100 整数倍；融券券源可用性
        if (engine.isAShareEnabled()) {
            if (!isShort && quantity % PriceDiscoveryEngine.A_SHARE_BOARD_LOT != 0) {
                return OpenResult.A_SHARE_REJECTED;
            }
            if (isShort) {
                if (getShortable(assetId) < quantity) return OpenResult.SHORT_UNAVAILABLE;
            }
        }

        MarginAccount acc = getOrCreateAccount(playerId);

        // AS-M1 §2.4：预警/追保/强平区带禁止新开仓
        MarginRiskBand curBand = acc.getRiskBand();
        if (curBand == MarginRiskBand.WARNING || curBand == MarginRiskBand.MARGIN_CALL
                || curBand == MarginRiskBand.FORCE_CLOSE) {
            return OpenResult.RISK_BAND_EXCEEDED;
        }

        // 初始保证金 = 名义/杠杆，且 ≥ 名义×MIN_COLLATERAL_RATIO（绝对下限，AS-M1 §2.2）
        double required = quantity * price / leverage;
        double floor = minCollateralRatio * quantity * price;
        required = Math.max(required, floor);
        if (required > acc.getCollateral()) return OpenResult.COLLATERAL_INSUFFICIENT;

        // 开仓后维持担保比例 ≥ 开仓最低线（确定性，避免开仓即预警/追保）
        Map<String, Double> prices = currentPrices(state);
        double projEquity = acc.getEquity(prices);
        double projLiability = acc.getLiability(prices);
        if (isShort) {
            projEquity += quantity * price;      // 借券卖出所得现金
            projLiability += quantity * price;   // 需归还券值
        } else {
            projLiability += quantity * price * (1.0 - 1.0 / leverage);
        }
        if (projLiability > 0 && projEquity / projLiability < openMinRatio) {
            return OpenResult.COLLATERAL_INSUFFICIENT;
        }

        // AS-M2 §3.2：持仓限额（开仓后任一超限 → POSITION_LIMIT_EXCEEDED）
        if (exceedsPositionLimits(acc, assetId, quantity, isShort, price, leverage, prices)) {
            return OpenResult.POSITION_LIMIT_EXCEEDED;
        }

        acc.openPosition(assetId, quantity, price, leverage, isShort, engine.getTickCounter());
        if (isShort) decrementShortable(assetId, quantity);
        return OpenResult.OK;
    }

    /** 开仓后是否超过任一持仓限额（AS-M2 §3.2，确定性）。 */
    private boolean exceedsPositionLimits(MarginAccount acc, String assetId, int quantity, boolean isShort,
                                          double price, int leverage, Map<String, Double> prices) {
        double liab = acc.getLiability(prices);
        double equity = acc.getEquity(prices);
        // 1) 开仓后总负债上限
        double projLiab = liab;
        if (isShort) projLiab += quantity * price;
        else projLiab += quantity * price * (1.0 - 1.0 / leverage);
        if (projLiab > maxLiabilityPerAccount) return true;
        // 2) 开仓后该资产名义上限（多空合计名义）
        double nominal = quantity * price;
        MarginAccount.MarginPosition p = acc.getLong(assetId);
        if (p != null) nominal += p.quantity * price;
        ShortPosition s = acc.getShort(assetId);
        if (s != null) nominal += s.getQuantity() * price;
        if (nominal > maxPositionValuePerAsset) return true;
        // 3) 空头开仓后该资产融券量上限
        if (isShort) {
            int shortQty = quantity;
            if (s != null) shortQty += s.getQuantity();
            if (shortQty > maxShortQtyPerAsset) return true;
        }
        // 4) 开仓后该资产名义 / 账户总资产 集中度上限
        double totalAsset = equity + projLiab;
        if (totalAsset > 0 && nominal / totalAsset > concentrationLimit) return true;
        return false;
    }

    /** 平仓：指定资产/方向/数量（负数量由网络层换算为平仓）。返回已实现盈亏。 */
    public double closePosition(UUID playerId, String assetId, int quantity, boolean isShort, MarketEngine engine) {
        MarginAccount acc = accounts.get(playerId);
        if (acc == null || assetId == null || quantity <= 0) return 0;
        double price = currentPrice(engine.getState(), assetId);
        if (price <= 0) return 0;
        double pnl = acc.closePosition(assetId, price, quantity, isShort);
        if (isShort && pnl != 0) incrementShortable(assetId, quantity); // 还券回补
        if (acc.isEmpty()) accounts.remove(playerId);
        return pnl;
    }

    /**
     * 每日/tick 结算 + 强平（spec §2.2.3）。对每个有持仓的账户 mark-to-market，
     * 维持担保比例 &lt; 强平线时按现价强平（先平未实现亏损最大的仓），直到比例 ≥ 强平线。
     * 强平价钳到涨跌停带（R0-6）。纯确定性、无随机。
     *
     * @return 本轮强平实现的累计盈亏（用于验证/日志）
     */
    public double settleAndForceClose(UUID playerId, MarketEngine engine) {
        MarginAccount acc = accounts.get(playerId);
        if (acc == null || !acc.hasPositions()) return 0;
        double realizedTotal = 0;
        int guard = 0;
        while (guard++ < 32) { // 防御死循环
            Map<String, Double> prices = currentPrices(engine.getState());
            if (acc.getMaintenanceRatio(prices) >= forceCloseRatio) break;
            String worst = worstPosition(acc, prices);
            if (worst == null) break;
            double price = prices.getOrDefault(worst, 0.0);
            if (price <= 0) break;
            if (engine.isAShareEnabled()) {
                price = clampToLimit(engine.getState(), worst, price);
            }
            double pnl = acc.closePosition(worst, price);
            realizedTotal += pnl;
            System.out.println("⚠️ 强平 玩家=" + playerId + " 资产=" + worst + " 数量="
                    + (acc.getLong(worst) != null ? acc.getLong(worst).quantity : 0)
                    + " 价格=" + String.format("%.2f", price));
        }
        if (acc.isEmpty()) accounts.remove(playerId);
        return realizedTotal;
    }

    /** 对所有有持仓账户做结算+强平（由 MarketEngine.tick 每 20 tick 调用）。 */
    public double settleAll(MarketEngine engine) {
        double total = 0;
        List<UUID> ids = new ArrayList<>(accounts.keySet());
        for (UUID id : ids) {
            total += enforcePositionLimits(id, engine);
            total += settleAndForceClose(id, engine);
        }
        return total;
    }

    /**
     * AS-M2 §3.3：结算/强平前先按持仓限额强制削减超限仓位（总负债/单资产名义/单资产融券量/集中度）。
     * 按确定性顺序（最老 → 字典序）部分/全部平仓至回到限额内。纯确定性、无随机。
     */
    public double enforcePositionLimits(UUID playerId, MarketEngine engine) {
        MarginAccount acc = accounts.get(playerId);
        if (acc == null || !acc.hasPositions()) return 0;
        double realizedTotal = 0;
        int guard = 0;
        while (guard++ < 64) { // 防御死循环
            Map<String, Double> prices = currentPrices(engine.getState());
            double liab = acc.getLiability(prices);
            double equity = acc.getEquity(prices);
            double totalAsset = equity + liab;
            String closeAsset = null;
            if (liab > maxLiabilityPerAccount) {
                closeAsset = oldestExcess(acc, prices); // 总负债超限：按确定性顺序削减
            } else {
                for (MarginAccount.PositionView v : acc.getPositionViews(prices)) {
                    double nominal = v.quantity() * prices.getOrDefault(v.assetId(), v.entryPrice());
                    if (nominal > maxPositionValuePerAsset) { closeAsset = v.assetId(); break; }
                    if (totalAsset > 0 && nominal / totalAsset > concentrationLimit) { closeAsset = v.assetId(); break; }
                    if (!v.isLong() && v.quantity() > maxShortQtyPerAsset) { closeAsset = v.assetId(); break; }
                }
            }
            if (closeAsset == null) break;
            double price = prices.getOrDefault(closeAsset, 0.0);
            if (price <= 0) break;
            if (engine.isAShareEnabled()) {
                price = clampToLimit(engine.getState(), closeAsset, price);
            }
            realizedTotal += acc.closePosition(closeAsset, price);
        }
        if (acc.isEmpty()) accounts.remove(playerId);
        return realizedTotal;
    }

    /** 总负债超限时选择确定性削减资产（最老 → 字典序）。 */
    private String oldestExcess(MarginAccount acc, Map<String, Double> prices) {
        String chosen = null;
        long chosenTick = Long.MAX_VALUE;
        for (MarginAccount.PositionView v : acc.getPositionViews(prices)) {
            if (v.openedTick() < chosenTick
                    || (v.openedTick() == chosenTick && chosen != null && v.assetId().compareTo(chosen) < 0)) {
                chosenTick = v.openedTick();
                chosen = v.assetId();
            }
        }
        return chosen;
    }

    /**
     * AS-M3 §4.2：逐日盯市 + 多级追加保证金（margin call）状态机。
     * 由 MarketEngine 在交易日边界（calendar.isSessionEnd）调用。确定性。
     *
     * <p>状态机：NORMAL→WARNING（比例低于预警线）→MARGIN_CALL（低于追保线，启动宽限计数）→
     * FORCE_CLOSE（宽限结束仍低于强平线 → 强制平仓）；入金/价格回升回 NORMAL。
     */
    public void settleDaily(MarketEngine engine, TradingCalendar calendar) {
        long tradingDay = calendar.getTradingDayNumber();
        List<UUID> ids = new ArrayList<>(accounts.keySet());
        for (UUID id : ids) {
            MarginAccount acc = accounts.get(id);
            if (acc == null || !acc.hasPositions()) continue;
            // 先按持仓限额强制削减，再重估风险
            enforcePositionLimits(id, engine);
            Map<String, Double> prices = currentPrices(engine.getState());
            double ratio = acc.getMaintenanceRatio(prices);
            MarginRiskBand target = MarginAccount.computeRiskBand(ratio, warningRatio, marginCallRatio, forceCloseRatio);
            MarginRiskBand cur = acc.getRiskBand();

            if (target == MarginRiskBand.NORMAL || target == MarginRiskBand.WARNING) {
                // 回升出追保/强平区 → 回 NORMAL/WARNING 并清零计数
                if (cur == MarginRiskBand.MARGIN_CALL || cur == MarginRiskBand.FORCE_CLOSE) {
                    acc.setMarginCallStreak(0);
                }
                acc.setRiskBand(target);
            } else if (target == MarginRiskBand.MARGIN_CALL) {
                if (cur == MarginRiskBand.MARGIN_CALL || cur == MarginRiskBand.FORCE_CLOSE) {
                    acc.setMarginCallStreak(acc.getMarginCallStreak() + 1);
                } else {
                    // NORMAL/WARNING → 首次跌破追保线：进入追保并启动宽限
                    acc.setRiskBand(MarginRiskBand.MARGIN_CALL);
                    acc.setMarginCallStreak(1);
                    acc.setLastMarginCallDay(tradingDay);
                    acc.setMarginCallDeadline(tradingDay + marginCallGraceTradingDays);
                    System.out.println("⚠️ 追保 玩家=" + id + " 维持担保比例=" + String.format("%.3f", ratio)
                            + " 宽限截止交易日=" + acc.getMarginCallDeadline());
                }
                // 宽限结束仍不足 → 进入强平区并强平
                if (acc.getMarginCallStreak() >= marginCallGraceTradingDays && ratio < forceCloseRatio) {
                    acc.setRiskBand(MarginRiskBand.FORCE_CLOSE);
                    settleAndForceClose(id, engine);
                }
            } else { // FORCE_CLOSE
                acc.setRiskBand(MarginRiskBand.FORCE_CLOSE);
                settleAndForceClose(id, engine);
            }
        }
    }

    /** 确定性选择未实现亏损最大的仓（先平亏损最大的），并列按 openedTick 最老 → assetId 字典序。 */
    private String worstPosition(MarginAccount acc, Map<String, Double> prices) {
        String worst = null;
        double worstPnl = Double.POSITIVE_INFINITY;
        long worstTick = Long.MAX_VALUE;
        for (MarginAccount.PositionView v : acc.getPositionViews(prices)) {
            boolean better = v.unrealizedPnl() < worstPnl;
            if (!better && v.unrealizedPnl() == worstPnl) {
                better = v.openedTick() < worstTick
                        || (v.openedTick() == worstTick && worst != null && v.assetId().compareTo(worst) < 0);
            }
            if (better) {
                worstPnl = v.unrealizedPnl();
                worstTick = v.openedTick();
                worst = v.assetId();
            }
        }
        return worst;
    }

    /** 现价 map（真实现价，非空）。 */
    private Map<String, Double> currentPrices(MarketState state) {
        Map<String, Double> map = new HashMap<>();
        for (String id : state.getAssetIds()) {
            AssetSnapshot snap = state.getAsset(id);
            if (snap != null) map.put(id, snap.getLastPrice());
        }
        return map;
    }

    private double currentPrice(MarketState state, String assetId) {
        AssetSnapshot snap = state.getAsset(assetId);
        return snap != null ? snap.getLastPrice() : 0.0;
    }

    /** A 股涨跌停钳制（锚 prevClose）。 */
    private double clampToLimit(MarketState state, String assetId, double price) {
        AssetSnapshot snap = state.getAsset(assetId);
        if (snap == null || snap.getPrevClose() <= 0) return price;
        double up = PriceDiscoveryEngine.aShareLimitUp(snap.getPrevClose());
        double down = PriceDiscoveryEngine.aShareLimitDown(snap.getPrevClose());
        return PriceDiscoveryEngine.aShareClamp(price, up, down);
    }

    private int getShortable(String assetId) {
        return shortablePool.getOrDefault(assetId, DEFAULT_SHORTABLE_POOL);
    }

    private void decrementShortable(String assetId, int qty) {
        int cur = getShortable(assetId);
        shortablePool.put(assetId, Math.max(0, cur - qty));
    }

    private void incrementShortable(String assetId, int qty) {
        int cur = shortablePool.getOrDefault(assetId, DEFAULT_SHORTABLE_POOL);
        shortablePool.put(assetId, cur + qty);
    }

    // ------------------------------------------------------------------
    // 持久化（随 MarketEngine.save()/load() 的 "margin" 字段，R0-5）
    // ------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag accList = new ListTag();
        for (MarginAccount acc : accounts.values()) {
            if (acc.isEmpty()) continue;
            CompoundTag at = new CompoundTag();
            at.putUUID("player", acc.getOwnerUuid());
            at.putDouble("collateral", acc.getCollateral());
            at.putString("riskBand", acc.getRiskBand().name());
            at.putInt("marginCallStreak", acc.getMarginCallStreak());
            at.putLong("lastMarginCallDay", acc.getLastMarginCallDay());
            at.putLong("marginCallDeadline", acc.getMarginCallDeadline());
            ListTag longs = new ListTag();
            for (MarginAccount.MarginPosition p : acc.getLongPositions()) {
                CompoundTag lt = new CompoundTag();
                lt.putString("asset", p.assetId);
                lt.putInt("qty", p.quantity);
                lt.putDouble("entry", p.entryPrice);
                lt.putInt("lev", p.leverage);
                lt.putLong("openedTick", p.openedTick);
                longs.add(lt);
            }
            at.put("longs", longs);
            ListTag shorts = new ListTag();
            for (ShortPosition s : acc.getShortPositions()) {
                CompoundTag st = new CompoundTag();
                st.putString("asset", s.getAssetId());
                st.putInt("qty", s.getQuantity());
                st.putDouble("entry", s.getEntryPrice());
                st.putLong("tick", s.getOpenedTick());
                shorts.add(st);
            }
            at.put("shorts", shorts);
            accList.add(at);
        }
        tag.put("accounts", accList);
        // 券源池
        ListTag pool = new ListTag();
        for (Map.Entry<String, Integer> e : shortablePool.entrySet()) {
            CompoundTag pt = new CompoundTag();
            pt.putString("asset", e.getKey());
            pt.putInt("qty", e.getValue());
            pool.add(pt);
        }
        tag.put("pool", pool);
        return tag;
    }

    /** 恢复账户与券源；旧档缺字段用默认/空重建，读档不失败（R0-5）。 */
    public void load(CompoundTag tag) {
        accounts.clear();
        shortablePool.clear();
        if (tag == null || tag.isEmpty()) return;
        ListTag accList = tag.getList("accounts", Tag.TAG_COMPOUND);
        for (int i = 0; i < accList.size(); i++) {
            CompoundTag at = accList.getCompound(i);
            if (!at.contains("player")) continue;
            UUID pid = at.getUUID("player");
            MarginAccount acc = getOrCreateAccount(pid);
            acc.addCollateral(at.getDouble("collateral"));
            // AS-M3：风险区带/追保计数；旧档缺字段用默认兜底（NORMAL/0），读档不失败
            if (at.contains("riskBand")) {
                try {
                    acc.setRiskBand(MarginRiskBand.valueOf(at.getString("riskBand")));
                } catch (IllegalArgumentException e) {
                    acc.setRiskBand(MarginRiskBand.NORMAL);
                }
            }
            if (at.contains("marginCallStreak")) acc.setMarginCallStreak(at.getInt("marginCallStreak"));
            if (at.contains("lastMarginCallDay")) acc.setLastMarginCallDay(at.getLong("lastMarginCallDay"));
            if (at.contains("marginCallDeadline")) acc.setMarginCallDeadline(at.getLong("marginCallDeadline"));
            ListTag longs = at.getList("longs", Tag.TAG_COMPOUND);
            for (int j = 0; j < longs.size(); j++) {
                CompoundTag lt = longs.getCompound(j);
                MarginAccount.MarginPosition p = new MarginAccount.MarginPosition(
                        lt.getString("asset"), lt.getInt("qty"), lt.getDouble("entry"), lt.getInt("lev"), true,
                        lt.contains("openedTick") ? lt.getLong("openedTick") : 0L);
                acc.restoreLong(p);
            }
            ListTag shorts = at.getList("shorts", Tag.TAG_COMPOUND);
            for (int j = 0; j < shorts.size(); j++) {
                CompoundTag st = shorts.getCompound(j);
                acc.restoreShort(new ShortPosition(st.getString("asset"), st.getInt("qty"),
                        st.getDouble("entry"), st.getLong("tick")));
            }
        }
        ListTag pool = tag.getList("pool", Tag.TAG_COMPOUND);
        for (int i = 0; i < pool.size(); i++) {
            CompoundTag pt = pool.getCompound(i);
            shortablePool.put(pt.getString("asset"), pt.getInt("qty"));
        }
    }
}
