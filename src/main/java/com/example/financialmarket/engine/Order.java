package com.example.financialmarket.engine;

import java.util.UUID;

public class Order {
    public enum Type {
        MARKET_BUY,
        MARKET_SELL,
        LIMIT_BUY,
        LIMIT_SELL
    }

    private final UUID orderId;
    private final UUID playerId;
    private final String assetId;
    private final Type type;
    private final int totalQuantity;
    private int remainingQuantity;
    private final double limitPrice;
    private final long timestamp;
    // P0-5 G-09：订单生命周期字段（过期/撤单用）
    private long placedTick;      // 挂单时的订单簿 tick
    private int expireTicks;      // 有效时长（tick），默认 AI 订单过期窗口
    // A 股 T+1（AS-3）：合成流标记。代理（AI agent）/ZI 合成订单流为 true，不参与真实账户交收（T+1 账本跳过）。
    private boolean synthetic = false;
    // LIQ P2（§5.2/§7.2）：做市商会话标签（"liq-mm:"+assetId；null=非 MM 单）。专职 MM-Agent 报价的
    // 成交归属标识（attributeTrade/撮合侧识别 MM 侧成交 → onMarketMakerFill 更新存货 I(t)）。
    // 真实玩家单恒 null；仅流级/单级标识、不参与定价/随机 → 加字段不改既有语义（含读档兼容）。
    private String mmTag = null;

    /** AI/普通订单默认过期窗口（tick）。 */
    public static final int AI_ORDER_EXPIRE_TICKS = 100;

    /**
     * 构造限价单（市场单可将 limitPrice 设为 0）
     *
     * @param playerId   玩家UUID
     * @param assetId    资产ID
     * @param type       订单类型
     * @param quantity   总数量
     * @param limitPrice 限价（市价单设为0）
     */
    public Order(UUID playerId, String assetId, Type type, int quantity, double limitPrice) {
        this(playerId, assetId, type, quantity, limitPrice, 0L);
    }

    /**
     * 构造限价单，并指定挂单 tick（由挂单方传入；0 表示由订单簿在挂单时自动设置）。
     */
    public Order(UUID playerId, String assetId, Type type, int quantity, double limitPrice, long placedTick) {
        this.orderId = UUID.randomUUID();
        this.playerId = playerId;
        this.assetId = assetId;
        this.type = type;
        this.totalQuantity = quantity;
        this.remainingQuantity = quantity;
        this.limitPrice = limitPrice;
        this.timestamp = System.currentTimeMillis();
        this.placedTick = placedTick;
        this.expireTicks = AI_ORDER_EXPIRE_TICKS;
    }

    // Getters
    public UUID getOrderId() { return orderId; }
    public UUID getPlayerId() { return playerId; }
    public String getAssetId() { return assetId; }
    public Type getType() { return type; }
    public int getTotalQuantity() { return totalQuantity; }
    public int getRemainingQuantity() { return remainingQuantity; }
    public double getLimitPrice() { return limitPrice; }
    public long getTimestamp() { return timestamp; }
    public long getPlacedTick() { return placedTick; }
    public int getExpireTicks() { return expireTicks; }

    public void setPlacedTick(long placedTick) { this.placedTick = placedTick; }
    public void setExpireTicks(int expireTicks) {
        if (expireTicks > 0) this.expireTicks = expireTicks;
    }

    /** 是否为合成订单流（代理/ZI）：是则不参与真实账户 T+1 交收。 */
    public boolean isSynthetic() {
        return synthetic;
    }

    /** 标记为合成订单流（代理/ZI）。 */
    public void setSynthetic(boolean synthetic) {
        this.synthetic = synthetic;
    }

    /** 做市商会话标签；null = 非专职 MM 单（真实玩家/其它合成流）。 */
    public String getMmTag() {
        return mmTag;
    }

    /** 设置做市商会话标签（null 清除）。 */
    public void setMmTag(String mmTag) {
        this.mmTag = mmTag;
    }

    /** 是否为专职 MM-Agent 报价单（mmTag 非空）。 */
    public boolean isMarketMakerOrder() {
        return mmTag != null;
    }

    /** 当前 tick 下是否已超过有效时长（超龄未成交单）。 */
    public boolean isExpired(long currentTick) {
        return placedTick > 0 && expireTicks > 0 && (currentTick - placedTick) >= expireTicks;
    }

    public boolean isBuy() {
        return type == Type.LIMIT_BUY || type == Type.MARKET_BUY;
    }

    public boolean isSell() {
        return type == Type.LIMIT_SELL || type == Type.MARKET_SELL;
    }

    public boolean isMarketOrder() {
        return type == Type.MARKET_BUY || type == Type.MARKET_SELL;
    }

    public boolean isLimitOrder() {
        return type == Type.LIMIT_BUY || type == Type.LIMIT_SELL;
    }

    public boolean isFilled() {
        return remainingQuantity <= 0;
    }

    /**
     * 减少剩余数量
     * @param qty 成交数量
     */
    public void fill(int qty) {
        if (qty <= 0) return;
        this.remainingQuantity -= qty;
        if (this.remainingQuantity < 0) this.remainingQuantity = 0;
    }

    @Override
    public String toString() {
        return "Order{" +
                "orderId=" + orderId +
                ", playerId=" + playerId +
                ", assetId='" + assetId + '\'' +
                ", type=" + type +
                ", totalQuantity=" + totalQuantity +
                ", remainingQuantity=" + remainingQuantity +
                ", limitPrice=" + limitPrice +
                ", timestamp=" + timestamp +
                ", placedTick=" + placedTick +
                ", expireTicks=" + expireTicks +
                '}';
    }
}