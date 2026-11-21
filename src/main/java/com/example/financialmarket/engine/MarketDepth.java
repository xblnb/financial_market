package com.example.financialmarket.engine;

import java.util.*;

public class MarketDepth {
    private final Map<String, TreeMap<Double, Integer>> bidLevels = new HashMap<>();
    private final Map<String, TreeMap<Double, Integer>> askLevels = new HashMap<>();

    public void registerAsset(String assetId) {
        bidLevels.put(assetId, new TreeMap<>(Comparator.reverseOrder()));
        askLevels.put(assetId, new TreeMap<>());
    }

    public void updateDepth(String assetId, double sentimentBuyRatio, double basePrice) {
        TreeMap<Double, Integer> bids = bidLevels.get(assetId);
        TreeMap<Double, Integer> asks = askLevels.get(assetId);
        if (bids == null || asks == null) return;
        bids.clear();
        asks.clear();
        double spread = basePrice * 0.002;
        double buyPower = 500 * sentimentBuyRatio;
        double sellPower = 500 * (1 - sentimentBuyRatio);
        for (int i = 0; i < 5; i++) {
            double bidPrice = Math.round((basePrice - spread * (i + 1) + (Math.random() - 0.5) * basePrice * 0.005) * 100) / 100.0;
            double askPrice = Math.round((basePrice + spread * (i + 1) + (Math.random() - 0.5) * basePrice * 0.005) * 100) / 100.0;
            int bidQty = (int) (buyPower * (5 - i) * 0.5);
            int askQty = (int) (sellPower * (5 - i) * 0.5);
            if (bidQty > 0 && bidPrice > 0) bids.put(bidPrice, bidQty);
            if (askQty > 0 && askPrice > 0) asks.put(askPrice, askQty);
        }
    }

    /**
     * 尝试匹配订单，返回成交列表。
     * 注意：此方法会修改订单的剩余数量（调用 order.fill()）。
     */
    public List<Trade> matchOrder(Order order) {
        List<Trade> trades = new ArrayList<>();
        boolean isBuy = order.isBuy();
        TreeMap<Double, Integer> book = isBuy ? askLevels.get(order.getAssetId()) : bidLevels.get(order.getAssetId());
        if (book == null || book.isEmpty()) return trades;

        int remaining = order.getRemainingQuantity();
        double limitPrice = order.getLimitPrice();

        Iterator<Map.Entry<Double, Integer>> it = book.entrySet().iterator();
        while (it.hasNext() && remaining > 0) {
            Map.Entry<Double, Integer> level = it.next();
            double price = level.getKey();

            if (order.isLimitOrder()) {
                if (isBuy && price > limitPrice) break;
                if (!isBuy && price < limitPrice) break;
            }

            int available = level.getValue();
            int tradeQty = Math.min(remaining, available);
            // ✅ 修复：添加 isBuy 参数
            trades.add(new Trade(order.getAssetId(), price, tradeQty, System.currentTimeMillis(), isBuy));
            remaining -= tradeQty;

            if (tradeQty == available) {
                it.remove();
            } else {
                level.setValue(available - tradeQty);
            }
        }

        if (remaining < order.getRemainingQuantity()) {
            order.fill(order.getRemainingQuantity() - remaining);
        }

        return trades;
    }

    public OrderBook.OrderBookSnapshot getSnapshot(String assetId) {
        OrderBook.OrderBookSnapshot snap = new OrderBook.OrderBookSnapshot();
        TreeMap<Double, Integer> bids = bidLevels.get(assetId);
        if (bids != null) {
            for (Map.Entry<Double, Integer> e : bids.entrySet()) {
                snap.bids.add(new OrderBook.OrderBookLevel(e.getKey(), e.getValue()));
            }
        }
        TreeMap<Double, Integer> asks = askLevels.get(assetId);
        if (asks != null) {
            for (Map.Entry<Double, Integer> e : asks.entrySet()) {
                snap.asks.add(new OrderBook.OrderBookLevel(e.getKey(), e.getValue()));
            }
        }
        return snap;
    }
}