package com.example.financialmarket.engine.margin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 融资融券账户（t3 落地重构）。
 *
 * <p>持有：保证金 {@code collateral} + 多头持仓 {@code longs}（{@link MarginPosition}）
 * + 空头持仓 {@code shorts}（{@link ShortPosition}）。账户归 {@link MarginManager} 引擎级
 * 注册表管理（不再由 {@code PlayerEconomy} 每次 new）。
 *
 * <p>风险模型（确定性，注释明确，供 t5/t6 验证）：
 * <ul>
 *   <li><b>权益 equity</b> = collateral + 多头未实现盈亏 + 空头未实现盈亏 + 空头卖出所得
 *       （空头开仓借券卖出获得 {@code entryPrice*qty} 现金，计入权益；对应负债是需归还的券）。</li>
 *   <li><b>负债 liability</b> = 融资多头负债（{@code qty*entryPrice*(1-1/leverage)}，券商借出本金）
 *       + 融券空头负债（{@code qty*currentPrice}，按市价归还券）。</li>
 *   <li><b>维持担保比例</b> = equity / liability；&lt; 维持线（1.3）触发强平。</li>
 * </ul>
 * 开仓不动用 collateral（权益里始终含全部存入保证金），仅以「已用保证金」概念约束最大仓位，
 * 配合 {@link com.example.financialmarket.engine.margin.MarginManager#MIN_COLLATERAL_RATIO} 与
 * {@code MAINTENANCE_RATIO} 校验，避免「开仓即强平」缺陷。
 */
public class MarginAccount {
    private final UUID ownerUuid;
    private double collateral;
    private final Map<String, MarginPosition> longs = new HashMap<>();
    private final Map<String, ShortPosition> shorts = new HashMap<>();
    // AS-M3：多级风险区带状态 + 追保计数（均持久化，缺省兜底）
    private MarginRiskBand riskBand = MarginRiskBand.NORMAL;
    private int marginCallStreak = 0;
    private long lastMarginCallDay = 0;
    private long marginCallDeadline = 0;

    public MarginAccount(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
        this.collateral = 0;
    }

    public UUID getOwnerUuid() { return ownerUuid; }
    public double getCollateral() { return collateral; }
    public void addCollateral(double amount) { this.collateral += amount; }
    public void removeCollateral(double amount) { this.collateral = Math.max(0, this.collateral - amount); }
    public boolean hasPositions() { return !longs.isEmpty() || !shorts.isEmpty(); }
    public MarginPosition getLong(String assetId) { return longs.get(assetId); }
    public ShortPosition getShort(String assetId) { return shorts.get(assetId); }
    public boolean isEmpty() { return !hasPositions() && collateral <= 0; }
    public List<MarginPosition> getLongPositions() { return new ArrayList<>(longs.values()); }
    public List<ShortPosition> getShortPositions() { return new ArrayList<>(shorts.values()); }

    public MarginRiskBand getRiskBand() { return riskBand; }
    public void setRiskBand(MarginRiskBand riskBand) { this.riskBand = riskBand; }
    public int getMarginCallStreak() { return marginCallStreak; }
    public void setMarginCallStreak(int marginCallStreak) { this.marginCallStreak = marginCallStreak; }
    public long getLastMarginCallDay() { return lastMarginCallDay; }
    public void setLastMarginCallDay(long lastMarginCallDay) { this.lastMarginCallDay = lastMarginCallDay; }
    public long getMarginCallDeadline() { return marginCallDeadline; }
    public void setMarginCallDeadline(long marginCallDeadline) { this.marginCallDeadline = marginCallDeadline; }

    /** 多级风险区带（AS-M1/AS-M3，确定性纯函数）。 */
    public enum MarginRiskBand { NORMAL, WARNING, MARGIN_CALL, FORCE_CLOSE }

    /**
     * 按维持担保比例确定性映射风险区带（AS-M1 §2.3）：
     * ratio ≥ warning → NORMAL；marginCall ≤ ratio < warning → WARNING（禁开新仓）；
     * forceClose ≤ ratio < marginCall → MARGIN_CALL（追保中）；ratio < forceClose → FORCE_CLOSE。
     * 无负债（ratio 为正无穷）自然落入 NORMAL。
     */
    public static MarginRiskBand computeRiskBand(double ratio, double warning, double marginCall, double forceClose) {
        if (ratio >= warning) return MarginRiskBand.NORMAL;
        if (ratio >= marginCall) return MarginRiskBand.WARNING;
        if (ratio >= forceClose) return MarginRiskBand.MARGIN_CALL;
        return MarginRiskBand.FORCE_CLOSE;
    }

    /** 读档恢复多头（确定性，直接放置）。 */
    public void restoreLong(MarginPosition p) {
        if (p != null) longs.put(p.assetId, p);
    }

    /** 读档恢复空头（确定性，直接放置）。 */
    public void restoreShort(ShortPosition s) {
        if (s != null) shorts.put(s.getAssetId(), s);
    }

    /** 开多（兼容旧签名，openedTick=0）。 */
    public boolean openLong(String assetId, int quantity, double price, int leverage) {
        openPosition(assetId, quantity, price, leverage, false, 0);
        return true;
    }

    /** 开空（兼容旧签名，openedTick=0）。 */
    public boolean openShort(String assetId, int quantity, double price, int leverage) {
        openPosition(assetId, quantity, price, leverage, true, 0);
        return true;
    }

    /** 开/加仓（多头或空头）。同资产同方向加仓按平均成本合并。确定性、无随机。 */
    public void openPosition(String assetId, int quantity, double price, int leverage, boolean isShort, long openedTick) {
        if (isShort) {
            ShortPosition existing = shorts.get(assetId);
            if (existing != null) {
                int newQty = existing.getQuantity() + quantity;
                double avgEntry = (existing.getEntryPrice() * existing.getQuantity() + price * quantity) / (double) newQty;
                shorts.put(assetId, new ShortPosition(assetId, newQty, avgEntry, existing.getOpenedTick()));
            } else {
                shorts.put(assetId, new ShortPosition(assetId, quantity, price, openedTick));
            }
        } else {
            MarginPosition existing = longs.get(assetId);
            if (existing != null) {
                int newQty = existing.quantity + quantity;
                double avgEntry = (existing.entryPrice * existing.quantity + price * quantity) / (double) newQty;
                longs.put(assetId, new MarginPosition(assetId, newQty, avgEntry, leverage, true, existing.openedTick));
            } else {
                longs.put(assetId, new MarginPosition(assetId, quantity, price, leverage, true, openedTick));
            }
        }
    }

    /** 平掉某资产全部仓（多/空两侧都平），已实现盈亏计入 collateral。 */
    public double closePosition(String assetId, double currentPrice) {
        double pnl = 0;
        MarginPosition lp = longs.remove(assetId);
        if (lp != null) pnl += (currentPrice - lp.entryPrice) * lp.quantity;
        ShortPosition sp = shorts.remove(assetId);
        if (sp != null) pnl += sp.close(sp.getQuantity(), currentPrice);
        collateral += pnl;
        return pnl;
    }

    /** 平指定侧部分数量（负数量由网络层换算为平仓）。 */
    public double closePosition(String assetId, double currentPrice, int quantity, boolean isShort) {
        if (quantity <= 0) return 0;
        double pnl;
        if (isShort) {
            ShortPosition sp = shorts.get(assetId);
            if (sp == null) return 0;
            int closeQ = Math.min(quantity, sp.getQuantity());
            pnl = sp.close(closeQ, currentPrice);
            int remaining = sp.getQuantity() - closeQ;
            if (remaining <= 0) shorts.remove(assetId);
            else shorts.put(assetId, new ShortPosition(sp.getAssetId(), remaining, sp.getEntryPrice(), sp.getOpenedTick()));
        } else {
            MarginPosition lp = longs.get(assetId);
            if (lp == null) return 0;
            int closeQ = Math.min(quantity, lp.quantity);
            pnl = (currentPrice - lp.entryPrice) * closeQ;
            int remaining = lp.quantity - closeQ;
            if (remaining <= 0) longs.remove(assetId);
            else longs.put(assetId, new MarginPosition(assetId, remaining, lp.entryPrice, lp.leverage, true, lp.openedTick));
        }
        collateral += pnl;
        return pnl;
    }

    /** 权益（collateral + 全部未实现盈亏 + 空头卖出所得）。确定性。 */
    public double getEquity(Map<String, Double> currentPrices) {
        double equity = collateral;
        for (MarginPosition p : longs.values()) {
            double price = currentPrices.getOrDefault(p.assetId, p.entryPrice);
            equity += (price - p.entryPrice) * p.quantity;
        }
        for (ShortPosition s : shorts.values()) {
            double price = currentPrices.getOrDefault(s.getAssetId(), s.getEntryPrice());
            equity += s.getEntryPrice() * s.getQuantity() + (s.getEntryPrice() - price) * s.getQuantity();
        }
        return equity;
    }

    /** 负债（融资多头借出本金 + 融券空头券值）。确定性。 */
    public double getLiability(Map<String, Double> currentPrices) {
        double liab = 0;
        for (MarginPosition p : longs.values()) {
            liab += p.entryPrice * p.quantity * (1.0 - 1.0 / Math.max(1, p.leverage));
        }
        for (ShortPosition s : shorts.values()) {
            double price = currentPrices.getOrDefault(s.getAssetId(), s.getEntryPrice());
            liab += price * s.getQuantity();
        }
        return liab;
    }

    /** 维持担保比例 = equity / liability；无负债时视为无穷（安全）。 */
    public double getMaintenanceRatio(Map<String, Double> currentPrices) {
        double liab = getLiability(currentPrices);
        if (liab <= 0) return Double.POSITIVE_INFINITY;
        return getEquity(currentPrices) / liab;
    }

    /** 便捷风险判断（兼容旧名）：返回需强平的资产 id（本实现并入 manager，保留语义）。 */
    public List<String> checkMarginCall(Map<String, Double> currentPrices) {
        if (getMaintenanceRatio(currentPrices) >= 1.0) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String asset : longs.keySet()) out.add(asset);
        for (String s : shorts.keySet()) if (!out.contains(s)) out.add(s);
        return out;
    }

    /** 持仓视图（供网络 DTO / UI / 验证），确定性。 */
    public List<PositionView> getPositionViews(Map<String, Double> currentPrices) {
        List<PositionView> views = new ArrayList<>();
        for (MarginPosition p : longs.values()) {
            double price = currentPrices.getOrDefault(p.assetId, p.entryPrice);
            views.add(new PositionView(p.assetId, p.quantity, p.entryPrice, true,
                    (price - p.entryPrice) * p.quantity, p.openedTick));
        }
        for (ShortPosition s : shorts.values()) {
            double price = currentPrices.getOrDefault(s.getAssetId(), s.getEntryPrice());
            views.add(new PositionView(s.getAssetId(), s.getQuantity(), s.getEntryPrice(), false,
                    (s.getEntryPrice() - price) * s.getQuantity(), s.getOpenedTick()));
        }
        views.sort((a, b) -> a.assetId.compareTo(b.assetId)); // 确定性顺序
        return views;
    }

    /** 确定性持仓视图记录（含 openedTick，供最老优先强平）。 */
    public record PositionView(String assetId, int quantity, double entryPrice, boolean isLong, double unrealizedPnl, long openedTick) {}

    public static class MarginPosition {
        public final String assetId;
        public final int quantity;
        public final double entryPrice;
        public final int leverage;
        public final boolean isLong;
        /** 开仓 tick（确定性，供最老优先强平排序）。 */
        public final long openedTick;

        public MarginPosition(String assetId, int quantity, double entryPrice, int leverage, boolean isLong, long openedTick) {
            this.assetId = assetId;
            this.quantity = quantity;
            this.entryPrice = entryPrice;
            this.leverage = leverage;
            this.isLong = isLong;
            this.openedTick = openedTick;
        }
    }
}
