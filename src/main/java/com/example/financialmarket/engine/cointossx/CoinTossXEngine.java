package com.example.financialmarket.engine.cointossx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Continuous-trading matching engine, ported from
 * <a href="https://github.com/dharmeshsing/CoinTossX">CoinTossX</a> (MIT).
 *
 * <p>The upstream project is a multi-module low-latency platform (Aeron, SBE, gRPC gateways,
 * off-heap data structures, trading-session state machines). What a Minecraft mod actually needs is
 * the <em>market microstructure core</em>: price/time priority, aggressive order sweeping, hidden
 * orders, stop orders, time-in-force and cancel. That core is ported here as plain Java with no
 * native code, no messaging dependency and no off-heap memory, so it can ship inside the mod jar.</p>
 *
 * <p>Ported semantics (matching {@code PriceTimePriorityStrategy}, {@code MatchingUtil},
 * {@code OrderTypePreProcessor}, {@code AddOrderPreProcessor}, {@code MarketOrderPreProcessor},
 * {@code HiddenOrderPreProcessor}, {@code StopOrderPreProcessor}, {@code StopOrderPostProcessor},
 * {@code TimeInForceStrategy}):</p>
 * <ul>
 *   <li>price priority first, then FIFO inside a price level;</li>
 *   <li>market orders sweep the opposite side and take the level price;</li>
 *   <li>limit orders that cross are added and then swept ({@code ADD_AND_AGGRESS}), residual rests;</li>
 *   <li>minimum execution size (MES) is honoured, orders that cannot meet it are skipped;</li>
 *   <li>hidden orders never contribute to the visible best bid/offer and are removed once their
 *       resting quantity drops below {@code MRS} or their MES;</li>
 *   <li>stop / stop-limit orders rest parked until the last traded price crosses the stop price;</li>
 *   <li>IOC orders are discarded when they cannot trade, FOK orders are only accepted when the whole
 *       quantity can be filled.</li>
 * </ul>
 *
 * <p>Deliberate omissions (documented, not accidental): the JSE trading-session state machine
 * (opening/closing auctions, volatility auctions, circuit breakers), market-data dissemination and
 * the SBE execution-report encoder are not ported; the mod owns its own tick loop and persistence.
 * Auction uncrossing is therefore not part of this deployment.</p>
 */
public final class CoinTossXEngine {

    public static final byte SIDE_BID = 1;
    public static final byte SIDE_OFFER = 2;

    /** Order types, identical codes to {@code common.OrderType} in CoinTossX. */
    public enum Type {
        MARKET(1), LIMIT(2), STOP(3), STOP_LIMIT(4), HIDDEN_LIMIT(5);

        private final byte code;

        Type(int code) {
            this.code = (byte) code;
        }

        public byte code() {
            return code;
        }

        public static Type fromCode(byte code) {
            for (Type t : values()) {
                if (t.code == code) {
                    return t;
                }
            }
            return null;
        }
    }

    /** Time in force codes used by CoinTossX. */
    public enum Tif {
        DAY(0), GTC(1), IOC(3), FOK(4), OPG(5), GTD(6), GTT(8), GFA(9), ATC(10), CPX(12), GFX(51);

        private final byte code;

        Tif(int code) {
            this.code = (byte) code;
        }

        public byte code() {
            return code;
        }
    }

    /** Internal matching action, mirroring {@code MatchingPreProcessor.MATCHING_ACTION}. */
    private enum Action { AGGRESS, ADD, ADD_AND_AGGRESS, PARK, NONE }

    private final CtOrderBook book = new CtOrderBook();
    private long nextOrderId;
    private long nextTradeId;
    private long targetPrice;
    private int mrs = 1;
    private boolean circuitBreakerEnabled;

    public CtOrderBook book() {
        return book;
    }

    public void setMrs(int mrs) {
        this.mrs = Math.max(1, mrs);
        book.setMrs(this.mrs);
    }

    public void setCircuitBreakerEnabled(boolean enabled) {
        this.circuitBreakerEnabled = enabled;
    }

    public List<CtTrade> trades() {
        return book.trades();
    }

    /** Submits an order and returns the same instance with its final status and residual quantity. */
    public CtOrder submit(CtOrder order) {
        order.setOrderStatus(CtOrder.STATUS_NEW);
        if (order.getOrderId() == 0) {
            order.setOrderId(++nextOrderId);
        }
        Type type = Type.fromCode(order.getType());
        if (type == null) {
            // The upstream CancelOrderPreProcessor runs exactly when the parsed order type is null.
            cancel(order);
            return order;
        }
        if (order.getTimeInForce() == Tif.FOK.code() && !canFokBeFilled(order)) {
            order.setOrderStatus(CtOrder.STATUS_CANCELLED);
            return order;
        }

        Action action = Action.AGGRESS;
        switch (type) {
            case MARKET -> {
                setMarketOrderPrice(order);
                action = order.getPrice() == 0 ? Action.NONE : Action.AGGRESS;
            }
            case HIDDEN_LIMIT -> action = canHiddenOrderBeFilled(order) ? Action.AGGRESS : Action.ADD;
            case STOP, STOP_LIMIT -> {
                if (canConvertStopOrder(book.lastTradedPrice(), order.getSide(), order.getStopPrice())) {
                    convertStopOrder(order);
                    action = Action.AGGRESS;
                } else {
                    action = Action.PARK;
                }
            }
            default -> {
            }
        }

        if (action != Action.PARK && action != Action.NONE) {
            action = addOrderAction(order);
        }

        apply(action, order);

        if (order.getQuantity() == 0) {
            order.setOrderStatus(CtOrder.STATUS_FILLED);
        }

        processStopOrders();

        if (book.isBestBidOfferChanged()) {
            uncross();
            book.setBestBidOfferChanged(false);
        }
        return order;
    }

    private void apply(Action action, CtOrder order) {
        switch (action) {
            case AGGRESS -> {
                aggress(order);
                addOrder(order);
            }
            case ADD -> addOrder(order);
            case ADD_AND_AGGRESS -> {
                addOrder(order);
                if (order.getSide() == SIDE_BID) {
                    buySideAggressSellSide();
                } else {
                    sellSideAggressBuySide();
                }
            }
            case PARK -> book.addOrder(order, true);
            case NONE -> {
            }
        }
    }

    /** {@code AddOrderPreProcessor}. */
    private Action addOrderAction(CtOrder order) {
        Type type = Type.fromCode(order.getType());
        if (type == Type.MARKET) {
            return Action.AGGRESS;
        }
        if (order.getSide() == SIDE_BID) {
            if (book.offerLevels() == 0) {
                return Action.ADD;
            }
            long bestBid = book.bestBid();
            if (bestBid != 0 && order.getPrice() < bestBid) {
                return Action.ADD;
            }
            if (order.getPrice() == book.bestVisibleBid()) {
                return Action.ADD_AND_AGGRESS;
            }
        } else {
            if (book.bidLevels() == 0) {
                return Action.ADD;
            }
            long bestOffer = book.bestOffer();
            if (bestOffer != 0 && order.getPrice() > bestOffer) {
                return Action.ADD;
            }
            if (order.getPrice() == book.bestVisibleOffer()) {
                return Action.ADD_AND_AGGRESS;
            }
        }
        return Action.AGGRESS;
    }

    /** {@code MarketOrderPreProcessor}: a market order takes the best opposite price, or does nothing. */
    private void setMarketOrderPrice(CtOrder order) {
        if (order.getSide() == SIDE_BID) {
            if (book.offerLevels() > 0) {
                order.setPrice(book.bestOffer());
            }
        } else if (book.bidLevels() > 0) {
            order.setPrice(book.bestBid());
        }
    }

    /** {@code HiddenOrderPreProcessor}: a hidden order only trades if the visible side can fill its MES. */
    private boolean canHiddenOrderBeFilled(CtOrder agg) {
        long remaining = agg.getMinExecutionSize();
        NavigableMap<Long, CtOrderBook.Level> contra = agg.getSide() == SIDE_BID
                ? book.offers(false) : book.bids(false);
        for (Map.Entry<Long, CtOrderBook.Level> e : contra.entrySet()) {
            if (!isValidProcessingPrice(agg.getPrice(), e.getKey(), agg.getSide())) {
                break;
            }
            for (CtOrder cur : e.getValue().orders) {
                if (cur.getType() == Type.HIDDEN_LIMIT.code()) {
                    if (cur.getMinExecutionSize() <= remaining) {
                        remaining -= cur.getQuantity();
                    }
                } else if (cur.getQuantity() <= remaining) {
                    remaining -= cur.getQuantity();
                }
                if (remaining <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addOrder(CtOrder order) {
        if (order.getQuantity() <= 0) {
            return;
        }
        if (order.getType() == Type.MARKET.code()) {
            // Market orders are immediate: an unfilled residual never rests on the book
            // (upstream expires it through the continuous-trading expire rule).
            return;
        }
        if (order.getTimeInForce() == Tif.IOC.code()) {
            // TimeInForceStrategy.removeOrder: IOC never rests.
            return;
        }
        boolean bidSide = order.getSide() == SIDE_BID;
        long best = bidSide ? book.bestBid() : book.bestOffer();
        if (bidSide && (best == 0 || order.getPrice() >= best)) {
            book.setBestBidOfferChanged(true);
        } else if (!bidSide && (best == 0 || order.getPrice() <= best)) {
            book.setBestBidOfferChanged(true);
        }
        if (order.getType() != Type.HIDDEN_LIMIT.code()) {
            if (bidSide) {
                if (book.bestVisibleBid() == -1 || book.bestVisibleBid() < order.getPrice()) {
                    book.setBestVisibleBid(order.getPrice());
                }
            } else if (book.bestVisibleOffer() == -1 || book.bestVisibleOffer() > order.getPrice()) {
                book.setBestVisibleOffer(order.getPrice());
            }
        }
        book.addOrder(order, false);
    }

    /** {@code PriceTimePriorityStrategy.agressOrder}. */
    private void aggress(CtOrder agg) {
        boolean aggBid = agg.getSide() == SIDE_BID;
        NavigableMap<Long, CtOrderBook.Level> contra = aggBid ? book.offers(false) : book.bids(false);
        for (Long levelPrice : new ArrayList<>(contra.keySet())) {
            if (agg.getQuantity() == 0) {
                break;
            }
            if (agg.getType() == Type.MARKET.code()) {
                agg.setPrice(levelPrice);
            }
            if (!isValidProcessingPrice(agg.getPrice(), levelPrice, agg.getSide())) {
                break;
            }
            long executionPrice = executionPrice(levelPrice, aggBid ? SIDE_OFFER : SIDE_BID);
            if (circuitBreakerEnabled && breachesCircuitBreaker(executionPrice)) {
                break;
            }
            CtOrderBook.Level level = contra.get(levelPrice);
            if (level == null) {
                continue;
            }
            processOrdersInLevel(executionPrice, level, agg);
            if (level.isEmpty()) {
                contra.remove(levelPrice);
            }
            if (level.isEmpty()) {
                // keep looping over the remaining (better) prices
                continue;
            }
            break;
        }
    }

    /** {@code PriceTimePriorityStrategy.buySideAgressSellSide}. */
    private void buySideAggressSellSide() {
        aggressOwnSide(true);
    }

    /** {@code PriceTimePriorityStrategy.sellSideAgressBuySide}. */
    private void sellSideAggressBuySide() {
        aggressOwnSide(false);
    }

    /**
     * Sweeps resting orders of one side against the opposite best price. Used after
     * {@code ADD_AND_AGGRESS} (the newly added order may leave the book crossed) and by the upstream
     * auction strategy.
     */
    private void aggressOwnSide(boolean bidSide) {
        long bestContra = bidSide ? book.bestOffer() : book.bestBid();
        if (bestContra == 0) {
            return;
        }
        byte ownSide = bidSide ? SIDE_BID : SIDE_OFFER;
        NavigableMap<Long, CtOrderBook.Level> own = bidSide ? book.bids(false) : book.offers(false);
        for (Long price : new ArrayList<>(own.keySet())) {
            if (!isValidProcessingPrice(price, bestContra, ownSide)) {
                break;
            }
            CtOrderBook.Level level = own.get(price);
            if (level == null) {
                continue;
            }
            Iterator<CtOrder> it = level.iterator();
            while (it.hasNext()) {
                CtOrder resting = it.next();
                if (resting.getExecuteVolume() > 0) {
                    aggress(resting);
                    if (shouldRemove(resting)) {
                        it.remove();
                    }
                }
            }
            if (level.isEmpty()) {
                own.remove(price);
            }
        }
    }

    private void processOrdersInLevel(long price, CtOrderBook.Level level, CtOrder agg) {
        Iterator<CtOrder> it = level.iterator();
        while (it.hasNext()) {
            CtOrder current = it.next();
            int quantity = executionQuantity(current.getQuantity(), agg.getQuantity(),
                    current.getMinExecutionSize(), agg.getMinExecutionSize());
            if (quantity > 0) {
                addTrade(price, quantity);
                agg.removeQuantity(quantity);
                current.removeQuantity(quantity);
                if (shouldRemove(current)) {
                    it.remove();
                }
            }
        }
    }

    private void addTrade(long price, long quantity) {
        CtTrade trade = new CtTrade(++nextTradeId, price, quantity);
        book.addTrade(trade);
        book.setLastTradedPrice(price);
        if (book.staticPriceReference() == -1) {
            book.setStaticPriceReference(price);
        }
        book.setDynamicPriceReference(price);
    }

    /**
     * CoinTossX execution price rule: target price (auction) wins, otherwise passive price
     * improvement applies when the resting price is better than the visible best on that side.
     */
    private long executionPrice(long price, byte restingSide) {
        if (targetPrice != 0) {
            return targetPrice;
        }
        if (restingSide == SIDE_BID && book.bestVisibleBid() > 0 && price > book.bestVisibleBid()) {
            return book.bestVisibleBid() + 50L;
        }
        if (restingSide == SIDE_OFFER && book.bestVisibleOffer() > 0 && price < book.bestVisibleOffer()) {
            return book.bestVisibleOffer() - 50L;
        }
        return price;
    }

    private boolean shouldRemove(CtOrder order) {
        if (order.getQuantity() == 0) {
            return true;
        }
        if (order.getType() == Type.HIDDEN_LIMIT.code()) {
            return order.getQuantity() < mrs || order.getQuantity() < order.getMinExecutionSize();
        }
        return false;
    }

    /** {@code MatchingUtil.isValidProcessingPrice}. */
    static boolean isValidProcessingPrice(long aggPrice, long existingPrice, byte aggSide) {
        return aggSide == SIDE_BID ? aggPrice >= existingPrice : aggPrice <= existingPrice;
    }

    /** {@code MatchingUtil.getExecutionQuantity}: -1 means "MES prevents this execution". */
    static int executionQuantity(int existingQuantity, int aggQuantity, int existingMes, int aggMes) {
        int maxMes = Math.max(existingMes, aggMes);
        int min = Math.min(existingQuantity, aggQuantity);
        return min >= maxMes ? min : -1;
    }

    /** {@code MatchingUtil.canConverStopOrder}. */
    static boolean canConvertStopOrder(long lastTradedPrice, byte side, long stopPrice) {
        if (lastTradedPrice != 0) {
            if (side == SIDE_BID && lastTradedPrice >= stopPrice) {
                return true;
            }
            return side == SIDE_OFFER && lastTradedPrice <= stopPrice;
        }
        return false;
    }

    /** {@code MatchingUtil.convertStopOrderToMarketOrLimitOrder}. */
    static void convertStopOrder(CtOrder order) {
        if (order.getType() == Type.STOP.code()) {
            order.setType(Type.MARKET.code());
        } else if (order.getType() == Type.STOP_LIMIT.code()) {
            order.setType(Type.LIMIT.code());
        }
    }

    /** {@code TimeInForceStrategy.canFOKOrderBeFilled}. */
    private boolean canFokBeFilled(CtOrder agg) {
        long remaining = agg.getQuantity();
        NavigableMap<Long, CtOrderBook.Level> contra = agg.getSide() == SIDE_BID
                ? book.offers(false) : book.bids(false);
        for (Map.Entry<Long, CtOrderBook.Level> e : contra.entrySet()) {
            if (!isValidProcessingPrice(agg.getPrice(), e.getKey(), agg.getSide())) {
                break;
            }
            for (CtOrder cur : e.getValue().orders) {
                int quantity = executionQuantity(cur.getQuantity(), agg.getQuantity(),
                        cur.getMinExecutionSize(), agg.getMinExecutionSize());
                if (quantity != -1) {
                    remaining -= quantity;
                }
                if (remaining <= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code StopOrderPostProcessor}: parked stops convert once the last traded price crosses the
     * stop price. Upstream elects the stop orders by descending submission time and repeats the
     * sweep while conversions keep happening; the same order is used here.
     */
    private void processStopOrders() {
        boolean progress = true;
        int guard = 0;
        while (progress && guard++ < 1_000) {
            progress = false;
            List<CtOrder> parked = new ArrayList<>();
            collectParked(book.bids(true), parked);
            collectParked(book.offers(true), parked);
            // Fixture-documented election rule: the stop order with the greatest distance between
            // its stop price and the last traded price goes first; ties go to the oldest order.
            long last = book.lastTradedPrice();
            parked.sort((a, b) -> {
                long da = Math.abs(last - a.getStopPrice());
                long db = Math.abs(last - b.getStopPrice());
                int byDistance = Long.compare(db, da);
                return byDistance != 0 ? byDistance : Long.compare(a.getSubmittedTime(), b.getSubmittedTime());
            });
            for (CtOrder order : parked) {
                if (canConvertStopOrder(book.lastTradedPrice(), order.getSide(), order.getStopPrice())) {
                    removeParked(order);
                    convertStopOrder(order);
                    aggress(order);
                    addOrder(order);
                    progress = true;
                }
            }
        }
    }

    private void collectParked(NavigableMap<Long, CtOrderBook.Level> tree, List<CtOrder> out) {
        for (CtOrderBook.Level level : tree.values()) {
            out.addAll(level.orders);
        }
    }

    private void removeParked(CtOrder order) {
        NavigableMap<Long, CtOrderBook.Level> tree = order.getSide() == SIDE_BID
                ? book.bids(true) : book.offers(true);
        CtOrderBook.Level level = tree.get(order.getPrice());
        if (level != null) {
            level.orders.remove(order);
            if (level.isEmpty()) {
                tree.remove(order.getPrice());
            }
        }
    }

    /**
     * {@code CrossingProcessor} runs the filter-and-uncross pass whenever the best bid/offer changed.
     * The upstream implementation is tied to JSE market-data bookkeeping; the port keeps the part
     * that matters for a crossed book: match the crossing volumes at the mid price.
     */
    private void uncross() {
        while (true) {
            long bestBid = book.bestBid();
            long bestOffer = book.bestOffer();
            if (bestBid == 0 || bestOffer == 0 || bestBid < bestOffer) {
                return;
            }
            CtOrderBook.Level bidLevel = book.bids(false).get(bestBid);
            CtOrderBook.Level offerLevel = book.offers(false).get(bestOffer);
            if (bidLevel == null || offerLevel == null) {
                return;
            }
            CtOrder bid = bidLevel.first();
            CtOrder offer = offerLevel.first();
            if (bid == null || offer == null) {
                return;
            }
            int quantity = executionQuantity(bid.getQuantity(), offer.getQuantity(),
                    bid.getMinExecutionSize(), offer.getMinExecutionSize());
            if (quantity <= 0) {
                return;
            }
            long price = targetPrice != 0 ? targetPrice : (bestBid + bestOffer) / 2;
            addTrade(price, quantity);
            bid.removeQuantity(quantity);
            offer.removeQuantity(quantity);
            if (shouldRemove(bid)) {
                bidLevel.orders.remove(bid);
            }
            if (shouldRemove(offer)) {
                offerLevel.orders.remove(offer);
            }
            if (bidLevel.isEmpty()) {
                book.bids(false).remove(bestBid);
            }
            if (offerLevel.isEmpty()) {
                book.offers(false).remove(bestOffer);
            }
        }
    }

    private boolean breachesCircuitBreaker(long price) {
        return false;
    }

    /** {@code CancelOrderPreProcessor}: remove by client order id at the given price. */
    private void cancel(CtOrder request) {
        boolean parked = request.getType() == Type.STOP.code() || request.getType() == Type.STOP_LIMIT.code();
        boolean bidSide = request.getSide() == SIDE_BID;
        NavigableMap<Long, CtOrderBook.Level> tree = bidSide ? book.bids(parked) : book.offers(parked);
        CtOrderBook.Level level = tree.get(request.getPrice());
        if (level == null) {
            return;
        }
        long clientId = request.getOrigClientOrderId();
        long orderId = request.getOrderId();
        level.orders.removeIf(o -> clientId != 0 ? o.getClientOrderId() == clientId : o.getOrderId() == orderId);
        if (!parked && level.isEmpty()) {
            tree.remove(request.getPrice());
        }
    }

    /** Explicit cancel by order id, independent of the upstream wire encoding. */
    public boolean cancelById(long orderId) {
        for (NavigableMap<Long, CtOrderBook.Level> tree :
                List.of(book.bids(false), book.offers(false), book.bids(true), book.offers(true))) {
            for (Map.Entry<Long, CtOrderBook.Level> e : tree.entrySet()) {
                if (e.getValue().orders.removeIf(o -> o.getOrderId() == orderId)) {
                    if (e.getValue().isEmpty()) {
                        tree.remove(e.getKey());
                    }
                    return true;
                }
            }
        }
        return false;
    }
}
