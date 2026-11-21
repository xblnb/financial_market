package com.example.financialmarket.engine.cointossx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Limit order book with price/time priority.
 *
 * <p>Equivalence note: CoinTossX stores price levels in a {@code BPlusTree} keyed by price and each
 * level as an off-heap intrusive FIFO list. This port keeps exactly the same observable structure
 * (a price-ordered map whose iterators start at the best price, and a FIFO list per level) on top of
 * {@link TreeMap} / {@link ArrayList}. That removes the off-heap dependencies (
 * {@code ObjectLayout}, {@code sun.misc.Unsafe}, HPPC, MapDB) without changing iteration order or
 * matching behaviour.</p>
 */
public final class CtOrderBook {

    /** FIFO/priority queue of resting orders at one price level. */
    static final class Level {
        final List<CtOrder> orders = new ArrayList<>(4);

        /**
         * Ordered insert, mirroring {@code OrderListImpl.add}: the level is kept sorted by
         * submission time, and at equal priority visible orders precede hidden orders
         * ({@code OrderListImpl.timePriorityCompare}).
         */
        void add(CtOrder order) {
            int index = 0;
            while (index < orders.size() && compare(orders.get(index), order) <= 0) {
                index++;
            }
            orders.add(index, order);
        }

        private static int compare(CtOrder a, CtOrder b) {
            boolean aHidden = a.getType() == CoinTossXEngine.Type.HIDDEN_LIMIT.code();
            boolean bHidden = b.getType() == CoinTossXEngine.Type.HIDDEN_LIMIT.code();
            if (!aHidden && !bHidden) {
                return Long.compare(a.getSubmittedTime(), b.getSubmittedTime());
            }
            return bHidden ? -1 : 1;
        }

        int total() {
            int total = 0;
            for (CtOrder order : orders) {
                total += order.getQuantity();
            }
            return total;
        }

        int totalExecuteVolume() {
            int total = 0;
            for (CtOrder order : orders) {
                total += order.getExecuteVolume();
            }
            return total;
        }

        boolean isEmpty() {
            return orders.isEmpty();
        }

        Iterator<CtOrder> iterator() {
            return orders.iterator();
        }

        CtOrder first() {
            return orders.isEmpty() ? null : orders.get(0);
        }
    }

    private static final Comparator<Long> DESC = Comparator.reverseOrder();

    private final NavigableMap<Long, Level> bids = new TreeMap<>(DESC);
    private final NavigableMap<Long, Level> offers = new TreeMap<>();
    private final NavigableMap<Long, Level> parkedBids = new TreeMap<>(DESC);
    private final NavigableMap<Long, Level> parkedOffers = new TreeMap<>();

    private long bestVisibleBid = -1L;
    private long bestVisibleOffer = -1L;
    private long lastTradedPrice;
    private long staticPriceReference = -1L;
    private long dynamicPriceReference = -1L;
    private boolean bestBidOfferChanged;
    private int mrs = 1;

    private final List<CtTrade> trades = new ArrayList<>();

    public NavigableMap<Long, Level> bids(boolean parked) {
        return parked ? parkedBids : bids;
    }

    public NavigableMap<Long, Level> offers(boolean parked) {
        return parked ? parkedOffers : offers;
    }

    public long bestBid() {
        return bids.isEmpty() ? 0L : bids.firstKey();
    }

    public long bestOffer() {
        return offers.isEmpty() ? 0L : offers.firstKey();
    }

    public int bidLevels() {
        return bids.size();
    }

    public int offerLevels() {
        return offers.size();
    }

    public long bestVisibleBid() {
        return bestVisibleBid;
    }

    public long bestVisibleOffer() {
        return bestVisibleOffer;
    }

    public void setBestVisibleBid(long price) {
        this.bestVisibleBid = price;
    }

    public void setBestVisibleOffer(long price) {
        this.bestVisibleOffer = price;
    }

    public long lastTradedPrice() {
        return lastTradedPrice;
    }

    public void setLastTradedPrice(long price) {
        this.lastTradedPrice = price;
    }

    public long staticPriceReference() {
        return staticPriceReference;
    }

    public void setStaticPriceReference(long price) {
        this.staticPriceReference = price;
    }

    public long dynamicPriceReference() {
        return dynamicPriceReference;
    }

    public void setDynamicPriceReference(long price) {
        this.dynamicPriceReference = price;
    }

    public boolean isBestBidOfferChanged() {
        return bestBidOfferChanged;
    }

    public void setBestBidOfferChanged(boolean changed) {
        this.bestBidOfferChanged = changed;
    }

    public int mrs() {
        return mrs;
    }

    public void setMrs(int mrs) {
        this.mrs = Math.max(1, mrs);
    }

    public List<CtTrade> trades() {
        return Collections.unmodifiableList(trades);
    }

    void addTrade(CtTrade trade) {
        trades.add(trade);
    }

    void addOrder(CtOrder order, boolean parked) {
        NavigableMap<Long, Level> tree = order.getSide() == CoinTossXEngine.SIDE_BID
                ? bids(parked) : offers(parked);
        Level level = tree.computeIfAbsent(order.getPrice(), p -> new Level());
        level.add(order);
    }

    void removeLevel(long price, boolean bidSide, boolean parked) {
        if (bidSide) {
            bids(parked).remove(price);
        } else {
            offers(parked).remove(price);
        }
    }

    /** Removes an empty level if it has no orders left. */
    void pruneLevel(long price, boolean bidSide, boolean parked) {
        NavigableMap<Long, Level> tree = bidSide ? bids(parked) : offers(parked);
        Level level = tree.get(price);
        if (level != null && level.isEmpty()) {
            tree.remove(price);
        }
    }

    /** Best level iterator for the given side (price order: bids descending, offers ascending). */
    NavigableMap<Long, Level> levels(boolean bidSide, boolean parked) {
        return bidSide ? bids(parked) : offers(parked);
    }

    /**
     * Flattens the book to the order sequence used by the CoinTossX conformance fixtures: bids from
     * the best price downwards, then offers from the best price upwards, FIFO inside each level.
     */
    public List<CtOrder> snapshot() {
        List<CtOrder> out = new ArrayList<>();
        for (Map.Entry<Long, Level> e : bids.entrySet()) {
            out.addAll(e.getValue().orders);
        }
        for (Map.Entry<Long, Level> e : offers.entrySet()) {
            out.addAll(e.getValue().orders);
        }
        return out;
    }

    public boolean isEmpty() {
        return bids.isEmpty() && offers.isEmpty();
    }
}
