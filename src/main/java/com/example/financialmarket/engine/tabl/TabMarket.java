package com.example.financialmarket.engine.tabl;

import com.example.financialmarket.engine.cointossx.CoinTossXEngine;
import com.example.financialmarket.engine.cointossx.CtOrder;
import com.example.financialmarket.engine.cointossx.CtTrade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The TABL venue: one CoinTossX order book per asset, driven by the shared {@link TabClock}.
 *
 * <p>Responsibilities are intentionally narrow: route orders, stamp them with the engine tick, and
 * expose a read-only view (mark, best bid/offer, trades since the last step) to the agents. The
 * market never reads the wall clock and never keeps state between steps other than the books.</p>
 */
public final class TabMarket {

    private final Map<String, CoinTossXEngine> books = new LinkedHashMap<>();
    private final Map<String, List<CtTrade>> pendingTrades = new LinkedHashMap<>();
    private final Map<String, Long> marks = new LinkedHashMap<>();
    private long tradeSequence;
    private final Map<String, Integer> cursor = new LinkedHashMap<>();

    public CoinTossXEngine book(String assetId) {
        return books.computeIfAbsent(assetId, id -> new CoinTossXEngine());
    }

    /** Submits an intention at the given clock tick; returns the resulting order. */
    public CtOrder submit(TabOrderIntent intent, TabClock clock) {
        CoinTossXEngine engine = book(intent.assetId());
        CtOrder order = new CtOrder();
        order.setSide(intent.side());
        order.setType(intent.type());
        order.setPrice(intent.price());
        order.setQuantity(intent.quantity());
        order.setTimeInForce(intent.tif());
        order.setSubmittedTime(clock.globalTick());     // time authority: the engine tick
        order.setClientOrderId(++tradeSequence);
        order.setTrader((int) intent.agentId());
        order.setExpireTime(0L);
        engine.submit(order);
        List<CtTrade> all = engine.trades();
        int seen = cursor.getOrDefault(intent.assetId(), 0);
        List<CtTrade> pend = pendingTrades.computeIfAbsent(intent.assetId(), id -> new ArrayList<>());
        for (int i = seen; i < all.size(); i++) {
            pend.add(all.get(i));
        }
        cursor.put(intent.assetId(), all.size());
        refreshMark(intent.assetId());
        return order;
    }

    /** Best bid / offer of an asset (0 when empty). */
    public long bestBid(String assetId) {
        return book(assetId).book().bestBid();
    }

    public long bestOffer(String assetId) {
        return book(assetId).book().bestOffer();
    }

    /** Last traded price, falling back to the mid, then to {@code fallback}. */
    public long mark(String assetId, long fallback) {
        Long cached = marks.get(assetId);
        if (cached != null) {
            return cached;
        }
        long bid = bestBid(assetId);
        long offer = bestOffer(assetId);
        if (bid > 0 && offer > 0) {
            return (bid + offer) / 2;
        }
        return bid > 0 ? bid : offer > 0 ? offer : fallback;
    }

    private void refreshMark(String assetId) {
        CoinTossXEngine engine = book(assetId);
        List<CtTrade> trades = engine.trades();
        if (!trades.isEmpty()) {
            marks.put(assetId, trades.get(trades.size() - 1).price());
        }
    }

    /** Trades produced since the previous call, then clears the buffer. */
    public List<CtTrade> drainTrades(String assetId) {
        List<CtTrade> pend = pendingTrades.get(assetId);
        if (pend == null || pend.isEmpty()) {
            return Collections.emptyList();
        }
        List<CtTrade> out = new ArrayList<>(pend);
        pend.clear();
        return out;
    }

    /** Depth snapshot in CoinTossX order (bids descending, offers ascending). */
    public List<CtOrder> snapshot(String assetId) {
        return book(assetId).book().snapshot();
    }

    public int assets() {
        return books.size();
    }

    public void reset() {
        cursor.clear();
        books.clear();
        pendingTrades.clear();
        marks.clear();
        tradeSequence = 0;
    }
}
