package com.example.financialmarket.engine.buyback;

import com.example.financialmarket.engine.AssetSnapshot;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.MarketState;
import com.example.financialmarket.engine.PriceDiscoveryEngine;

/**
 * 股票回购（t4 接线到 {@link MarketEngine}）。
 *
 * <p>语义：公司/政府回购股票减少流通量、稳定股价。数量约束：{@code quantity} 为 100 整数倍
 * （A 股一手）且 ≤ 可流通量（不令 volume 为负）。A 股开启时推价钳到涨跌停带（复用
 * {@link PriceDiscoveryEngine#aShareClamp}）；关闭时沿用原 {@code *1.005} 基线（R0-6）。
 */
public class StockBuyback {
    /**
     * 公司/政府回购股票，减少流通股，稳定股价（引擎感知版，含 A 股钳制与一手约束）。
     *
     * @param assetId  股票代码
     * @param quantity 回购数量（股，应为 100 整数倍；非整数倍自动下取整到一手）
     * @param engine   市场引擎
     * @return 实际回购金额（0 表示未执行）
     */
    public static double executeBuyback(String assetId, int quantity, MarketEngine engine) {
        if (engine == null) return 0;
        MarketState state = engine.getState();
        AssetSnapshot snap = state.getAsset(assetId);
        if (snap == null) return 0;
        // A 股一手整数倍；非 A 股允许任意正整数（回落基线）
        int qty = engine.isAShareEnabled()
                ? Math.max(0, (quantity / PriceDiscoveryEngine.A_SHARE_BOARD_LOT) * PriceDiscoveryEngine.A_SHARE_BOARD_LOT)
                : quantity;
        if (qty <= 0 || snap.getVolume() < qty) return 0;
        double last = snap.getLastPrice();
        if (last <= 0) return 0;
        double cost = last * qty;
        snap.setVolume(snap.getVolume() - qty);
        double newPrice = last * 1.005;
        if (engine.isAShareEnabled()) {
            double prevClose = snap.getPrevClose();
            if (prevClose > 0) {
                double up = PriceDiscoveryEngine.aShareLimitUp(prevClose);
                double down = PriceDiscoveryEngine.aShareLimitDown(prevClose);
                newPrice = PriceDiscoveryEngine.aShareClamp(newPrice, up, down);
            }
        }
        snap.setLastPrice(newPrice);
        return cost;
    }

    /**
     * 兼容旧签名（无引擎，不钳制；仅作保留，新代码请用引擎版）。
     */
    public static double executeBuyback(String assetId, int quantity, MarketState state) {
        AssetSnapshot snap = state.getAsset(assetId);
        if (snap == null || snap.getVolume() < quantity) return 0;
        double cost = snap.getLastPrice() * quantity;
        snap.setVolume(snap.getVolume() - quantity);
        snap.setLastPrice(snap.getLastPrice() * 1.005);
        return cost;
    }
}
