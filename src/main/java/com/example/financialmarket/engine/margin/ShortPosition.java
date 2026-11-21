package com.example.financialmarket.engine.margin;

/**
 * 融券（做空）持仓模型（t3 落地，替代原 4 行空壳）。
 *
 * <p>语义（A 股简化）：开空时从可借券源 {@code shortablePool} 借入 {@code quantity} 股卖出；
 * 平仓时按市价买回归还。开空 PnL = {@code (entryPrice - currentPrice) * quantity}，
 * 计入账户权益与维持担保比例。
 *
 * <p>全部字段为确定性值，无随机（R0-3）。
 */
public class ShortPosition {
    private final String assetId;
    private final int quantity;
    /** 借券卖出价（开空时现价）。 */
    private final double entryPrice;
    /** 借券成本锚（简化取开空价 = entryPrice）。 */
    private final double borrowPrice;
    /** 开仓时引擎 tick 计数（元数据，确定性）。 */
    private final long openedTick;
    /** 已实现盈亏（平仓时累计）。 */
    private double realizedPnl;

    public ShortPosition(String assetId, int quantity, double entryPrice, long openedTick) {
        this.assetId = assetId;
        this.quantity = quantity;
        this.entryPrice = entryPrice;
        this.borrowPrice = entryPrice;
        this.openedTick = openedTick;
        this.realizedPnl = 0;
    }

    /** 平仓部分数量，返回该部分已实现盈亏 {@code (entryPrice - currentPrice) * qty}。 */
    public double close(int qty, double currentPrice) {
        int real = Math.min(qty, this.quantity);
        double pnl = (entryPrice - currentPrice) * real;
        realizedPnl += pnl;
        return pnl;
    }

    public String getAssetId() { return assetId; }
    public int getQuantity() { return quantity; }
    public double getEntryPrice() { return entryPrice; }
    public double getBorrowPrice() { return borrowPrice; }
    public long getOpenedTick() { return openedTick; }
    public double getRealizedPnl() { return realizedPnl; }
}
