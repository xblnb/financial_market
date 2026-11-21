package com.example.financialmarket.engine.cointossx;

/**
 * One resting or incoming order in the CoinTossX port.
 *
 * <p>Direct port of {@code leafNode.OrderEntry} from
 * <a href="https://github.com/dharmeshsing/CoinTossX">CoinTossX</a> (MIT), with the off-heap
 * {@code sun.misc.Unsafe} field layout replaced by ordinary fields. The observable behaviour is
 * kept identical: {@code setQuantity} also resets the executable volume and the displayed quantity
 * (hidden orders always display zero), and {@code removeQuantity} marks the order
 * {@code PARTIALLY_FILLED} exactly like the original.</p>
 */
public final class CtOrder {

    /** Order status codes, mirroring {@code sbe.msg.OrderStatusEnum}. */
    public static final byte STATUS_NEW = 0;
    public static final byte STATUS_PARTIALLY_FILLED = 1;
    public static final byte STATUS_FILLED = 2;
    public static final byte STATUS_CANCELLED = 3;

    private long orderId;
    private long clientOrderId;
    private long origClientOrderId;
    private byte type;
    private byte side;
    private byte timeInForce;
    private byte orderStatus;
    private int quantity;
    private int minExecutionSize;
    private int executeVolume;
    private int displayQuantity;
    private int trader;
    private long submittedTime;
    private long price;
    private long stopPrice;
    private long expireTime;

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public long getClientOrderId() {
        return clientOrderId;
    }

    public void setClientOrderId(long clientOrderId) {
        this.clientOrderId = clientOrderId;
    }

    public long getOrigClientOrderId() {
        return origClientOrderId;
    }

    public void setOrigClientOrderId(long origClientOrderId) {
        this.origClientOrderId = origClientOrderId;
    }

    public byte getType() {
        return type;
    }

    public void setType(byte type) {
        this.type = type;
    }

    public byte getSide() {
        return side;
    }

    public void setSide(byte side) {
        this.side = side;
    }

    public byte getTimeInForce() {
        return timeInForce;
    }

    public void setTimeInForce(byte timeInForce) {
        this.timeInForce = timeInForce;
    }

    public byte getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(byte orderStatus) {
        this.orderStatus = orderStatus;
    }

    public int getQuantity() {
        return quantity;
    }

    /**
     * Mirrors {@code OrderEntry.setQuantity}: resets the executable volume, and the displayed
     * quantity unless this is a hidden order.
     */
    public void setQuantity(int quantity) {
        this.quantity = quantity;
        this.executeVolume = quantity;
        if (type != CoinTossXEngine.Type.HIDDEN_LIMIT.code()) {
            this.displayQuantity = quantity;
        } else {
            this.displayQuantity = 0;
        }
    }

    public void setDisplayQuantity(int quantity) {
        this.displayQuantity = type != CoinTossXEngine.Type.HIDDEN_LIMIT.code() ? quantity : 0;
    }

    public int getDisplayQuantity() {
        return displayQuantity;
    }

    public int getMinExecutionSize() {
        return minExecutionSize;
    }

    public void setMinExecutionSize(int minExecutionSize) {
        this.minExecutionSize = minExecutionSize;
    }

    public int getExecuteVolume() {
        return executeVolume;
    }

    public void setExecuteVolume(int executeVolume) {
        this.executeVolume = executeVolume;
    }

    public int getTrader() {
        return trader;
    }

    public void setTrader(int trader) {
        this.trader = trader;
    }

    public long getSubmittedTime() {
        return submittedTime;
    }

    public void setSubmittedTime(long submittedTime) {
        this.submittedTime = submittedTime;
    }

    public long getPrice() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price;
    }

    public long getStopPrice() {
        return stopPrice;
    }

    public void setStopPrice(long stopPrice) {
        this.stopPrice = stopPrice;
    }

    public long getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(long expireTime) {
        this.expireTime = expireTime;
    }

    /** Mirrors {@code OrderEntry.removeQuantity}: partial fill bookkeeping. */
    public void removeQuantity(int amount) {
        if (amount <= quantity) {
            orderStatus = STATUS_PARTIALLY_FILLED;
            setQuantity(quantity - amount);
        }
    }

    public CtOrder copy() {
        CtOrder o = new CtOrder();
        o.orderId = orderId;
        o.clientOrderId = clientOrderId;
        o.origClientOrderId = origClientOrderId;
        o.type = type;
        o.side = side;
        o.timeInForce = timeInForce;
        o.orderStatus = orderStatus;
        o.quantity = quantity;
        o.minExecutionSize = minExecutionSize;
        o.executeVolume = executeVolume;
        o.displayQuantity = displayQuantity;
        o.trader = trader;
        o.submittedTime = submittedTime;
        o.price = price;
        o.stopPrice = stopPrice;
        o.expireTime = expireTime;
        return o;
    }

    @Override
    public String toString() {
        return "CtOrder{id=" + orderId + ", side=" + side + ", type=" + type + ", qty=" + quantity
                + ", price=" + price + ", tif=" + timeInForce + '}';
    }
}
