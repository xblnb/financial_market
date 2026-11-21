package com.example.financialmarket.engine;

import java.util.UUID;

/**
 * 成交记录。除价格/数量/方向外，携带买/卖双方 playerId（A 股 T+1 交收 AS-3 用）。
 *
 * <p><strong>合成流不参与真实账户交收</strong>：代理（AI agent）与 ZI/Hawkes 合成订单流的成交，其对应侧
 * playerId 由 {@link OrderBook} 置为 {@code null}（不写入真实玩家），故 {@link #getBuyerPlayerId()} /
 * {@link #getSellerPlayerId()} 返回 {@code null} 即代表该侧为合成流，MarketEngine 在 T+1 记账时跳过。
 */
public class Trade {
    private final String assetId;
    private final double price;
    private final int quantity;
    private final long timestamp;
    private final boolean isBuy;
    private final UUID buyerPlayerId;
    private final UUID sellerPlayerId;

    /** 兼容旧构造（无 playerId 归属；买/卖方均为 null）。 */
    public Trade(String assetId, double price, int quantity, long timestamp, boolean isBuy) {
        this(assetId, price, quantity, timestamp, isBuy, null, null);
    }

    /**
     * 完整构造。
     *
     * @param buyerPlayerId  买方 playerId（合成流为 null）
     * @param sellerPlayerId 卖方 playerId（合成流为 null）
     */
    public Trade(String assetId, double price, int quantity, long timestamp, boolean isBuy,
                 UUID buyerPlayerId, UUID sellerPlayerId) {
        this.assetId = assetId;
        this.price = price;
        this.quantity = quantity;
        this.timestamp = timestamp;
        this.isBuy = isBuy;
        this.buyerPlayerId = buyerPlayerId;
        this.sellerPlayerId = sellerPlayerId;
    }

    public String getAssetId() { return assetId; }
    public double getPrice() { return price; }
    public int getQuantity() { return quantity; }
    public long getTimestamp() { return timestamp; }
    public boolean isBuy() { return isBuy; }
    /** 买方 playerId；合成流（代理/ZI）为 null。 */
    public UUID getBuyerPlayerId() { return buyerPlayerId; }
    /** 卖方 playerId；合成流（代理/ZI）为 null。 */
    public UUID getSellerPlayerId() { return sellerPlayerId; }
}
