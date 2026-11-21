package com.example.financialmarket.web;

import com.example.financialmarket.engine.OHLCData;
import com.example.financialmarket.engine.OrderBook;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import com.example.financialmarket.network.packets.LeveragedNoteListPacket;
import com.example.financialmarket.network.packets.LeveragedNotePositionSyncPacket;
import com.example.financialmarket.network.packets.MarketListPacket;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClientMarketDataCache {
    private static final ClientMarketDataCache INSTANCE = new ClientMarketDataCache();

    private final List<MarketListPacket.StockInfo> stockList = new CopyOnWriteArrayList<>();
    private final Map<String, List<Map<String, Object>>> enhancedKlineCache = new ConcurrentHashMap<>();
    private final Map<String, List<PriceDiscoveryEngine.TradePoint>> tradePointCache = new ConcurrentHashMap<>();
    private final Map<String, OrderBook.OrderBookSnapshot> depthCache = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> tickCache = new ConcurrentHashMap<>();

    // t4：交易面板数据缓存（委托/持仓/成交，供 javaBridge 回调与网络包落盘；前端 updateOrders/updatePositions/updateTrades 消费）
    private final List<Map<String, Object>> orders = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> positions = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> trades = new CopyOnWriteArrayList<>();
    // AS-L1：杠杆票据列表/持仓缓存（供 javaBridge 回调与前端票据/持仓页消费）
    private final List<Map<String, Object>> leveragedNotes = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> leveragedNotePositions = new CopyOnWriteArrayList<>();

    private double cash = 0.0;
    private double margin = 0.0;
    private double indexValue = 1000.0;
    private double indexChange = 0.0;

    public static ClientMarketDataCache getInstance() { return INSTANCE; }

    // ---------- 更新方法 ----------
    public void updateStockList(List<MarketListPacket.StockInfo> stocks) {
        stockList.clear();
        stockList.addAll(stocks);
    }

    // 接受 Map 列表（从 StockDataResponsePacket 直接传入）
    public void updateKline(String assetId, List<Map<String, Object>> klinesMap, List<Map<String, Object>> pointsMap) {
        if (klinesMap == null || klinesMap.isEmpty()) {
            enhancedKlineCache.remove(assetId);
            tradePointCache.remove(assetId);
            return;
        }

        // 将 Map 转换为 OHLCData 用于计算指标
        List<OHLCData> klines = new ArrayList<>();
        for (Map<String, Object> map : klinesMap) {
            double open = asDouble(map.get("open"));
            double high = asDouble(map.get("high"));
            double low = asDouble(map.get("low"));
            double close = asDouble(map.get("close"));
            long volume = asLong(map.get("volume"));
            klines.add(new OHLCData(open, high, low, close, volume));
        }

        // 转换买卖点
        if (pointsMap != null) {
            List<PriceDiscoveryEngine.TradePoint> points = new ArrayList<>();
            for (Map<String, Object> map : pointsMap) {
                double price = asDouble(map.get("price"));
                int quantity = (int) asLong(map.get("quantity"));
                boolean isBuy = (boolean) map.getOrDefault("isBuy", true);
                points.add(new PriceDiscoveryEngine.TradePoint(price, quantity, isBuy));
            }
            tradePointCache.put(assetId, new CopyOnWriteArrayList<>(points));
        }

        // 计算增强指标
        List<Map<String, Object>> enhanced = computeEnhancedKlines(klines);
        enhancedKlineCache.put(assetId, enhanced);
    }

    public void updateDepth(String assetId, OrderBook.OrderBookSnapshot snap) {
        depthCache.put(assetId, snap);
    }

    public void updateTicks(String assetId, List<Map<String, Object>> ticks) {
        tickCache.put(assetId, new CopyOnWriteArrayList<>(ticks));
    }

    public void updatePortfolio(double cash, double margin) {
        this.cash = cash;
        this.margin = margin;
    }

    // t4：委托/持仓/成交回调（javaBridge / 网络包更新，AS-3 §5.2 契约字段）
    public void updateOrders(List<Map<String, Object>> orders) {
        this.orders.clear();
        if (orders != null) this.orders.addAll(orders);
    }

    public void updatePositions(List<Map<String, Object>> positions) {
        this.positions.clear();
        if (positions != null) this.positions.addAll(positions);
    }

    public void updateTrades(List<Map<String, Object>> trades) {
        this.trades.clear();
        if (trades != null) this.trades.addAll(trades);
    }

    /** AS-L1：更新杠杆票据列表缓存（供前端票据页展示）。 */
    public void updateLeveragedNotes(List<LeveragedNoteListPacket.NoteDTO> notes) {
        leveragedNotes.clear();
        if (notes == null) return;
        for (LeveragedNoteListPacket.NoteDTO d : notes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("noteId", d.noteId());
            m.put("name", d.name());
            m.put("underlyingType", d.underlyingType());
            m.put("underlyingKey", d.underlyingKey());
            m.put("leverage", d.leverage());
            m.put("lastPrice", d.lastPrice());
            m.put("prevSettlePrice", d.prevSettlePrice());
            m.put("outstanding", d.outstanding());
            m.put("status", d.status());
            m.put("maturityTicks", d.maturityTicks());
            m.put("unitNotional", d.unitNotional());
            leveragedNotes.add(m);
        }
    }

    /** AS-L1：更新玩家杠杆票据持仓缓存。 */
    public void updateLeveragedNotePositions(List<LeveragedNotePositionSyncPacket.PositionDTO> positions) {
        leveragedNotePositions.clear();
        if (positions == null) return;
        for (LeveragedNotePositionSyncPacket.PositionDTO d : positions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("noteId", d.noteId());
            m.put("units", d.units());
            m.put("avgEntryPrice", d.avgEntryPrice());
            m.put("lastPrice", d.lastPrice());
            m.put("marketValue", d.marketValue());
            m.put("pnl", d.pnl());
            leveragedNotePositions.add(m);
        }
    }

    public void updateIndex(double value, double change) {
        this.indexValue = value;
        this.indexChange = change;
    }

    // ---------- 读取方法 ----------
    public List<MarketListPacket.StockInfo> getStockList() { return stockList; }
    public List<Map<String, Object>> getEnhancedKlines(String assetId) {
        return enhancedKlineCache.getOrDefault(assetId, Collections.emptyList());
    }
    public List<PriceDiscoveryEngine.TradePoint> getTradePoints(String assetId) {
        return tradePointCache.getOrDefault(assetId, Collections.emptyList());
    }
    public OrderBook.OrderBookSnapshot getDepth(String assetId) {
        return depthCache.get(assetId);
    }
    public List<Map<String, Object>> getTicks(String assetId) {
        return tickCache.getOrDefault(assetId, Collections.emptyList());
    }
    public double getCash() { return cash; }
    public double getMargin() { return margin; }
    public double getIndexValue() { return indexValue; }
    public double getIndexChange() { return indexChange; }

    // t4：交易面板数据读取
    public List<Map<String, Object>> getOrders() { return orders; }
    public List<Map<String, Object>> getPositions() { return positions; }
    public List<Map<String, Object>> getTrades() { return trades; }
    public List<Map<String, Object>> getLeveragedNotes() { return leveragedNotes; }
    public List<Map<String, Object>> getLeveragedNotePositions() { return leveragedNotePositions; }

    // ---------- 辅助转换 ----------
    private double asDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return 0.0;
    }

    private long asLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        return 0L;
    }

    // ---------- 指标计算方法 ----------
    private List<Map<String, Object>> computeEnhancedKlines(List<OHLCData> klines) {
        int size = klines.size();
        if (size == 0) return Collections.emptyList();

        double[] closes = klines.stream().mapToDouble(OHLCData::getClose).toArray();
        double[] highs = klines.stream().mapToDouble(OHLCData::getHigh).toArray();
        double[] lows = klines.stream().mapToDouble(OHLCData::getLow).toArray();
        double[] volumes = klines.stream().mapToDouble(OHLCData::getVolume).toArray();

        double[] ma5 = calcMA(closes, 5, size);
        double[] ma10 = calcMA(closes, 10, size);
        double[] ma20 = calcMA(closes, 20, size);
        double[] ma60 = calcMA(closes, 60, size);

        double[] rsi = calcRSI(closes, 14, size);

        double[] macdLine = new double[size];
        double[] signalLine = new double[size];
        double[] macdHist = new double[size];
        calcMACD(closes, macdLine, signalLine, macdHist, size);

        double[] kValue = new double[size];
        double[] dValue = new double[size];
        double[] jValue = new double[size];
        calcKDJ(highs, lows, closes, kValue, dValue, jValue, size);

        double[] bollMid = ma20;
        double[] bollUpper = new double[size];
        double[] bollLower = new double[size];
        calcBollinger(closes, ma20, bollUpper, bollLower, size);

        List<Map<String, Object>> enhanced = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            OHLCData bar = klines.get(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("time", String.valueOf(i));
            map.put("open", bar.getOpen());
            map.put("high", bar.getHigh());
            map.put("low", bar.getLow());
            map.put("close", bar.getClose());
            map.put("volume", bar.getVolume());
            map.put("ma5", ma5[i]);
            map.put("ma10", ma10[i]);
            map.put("ma20", ma20[i]);
            map.put("ma60", ma60[i]);
            map.put("rsi", rsi[i]);
            map.put("macdDif", macdLine[i]);
            map.put("macdDea", signalLine[i]);
            map.put("macdHist", macdHist[i]);
            map.put("kdjK", kValue[i]);
            map.put("kdjD", dValue[i]);
            map.put("kdjJ", jValue[i]);
            map.put("bollUp", bollUpper[i]);
            map.put("bollMid", bollMid[i]);
            map.put("bollLow", bollLower[i]);
            map.put("avgPrice", (bar.getOpen() + bar.getHigh() + bar.getLow() + bar.getClose()) / 4.0);
            map.put("amount", bar.getVolume() * bar.getClose());
            enhanced.add(map);
        }
        return enhanced;
    }

    private double[] calcMA(double[] d, int p, int s) {
        double[] m = new double[s];
        for (int i = 0; i < s; i++) {
            if (i < p - 1) { m[i] = 0; continue; }
            double sum = 0;
            for (int j = 0; j < p; j++) sum += d[i - j];
            m[i] = sum / p;
        }
        return m;
    }

    private double[] calcRSI(double[] c, int p, int s) {
        double[] r = new double[s];
        if (s <= p) return r;
        double ag = 0, al = 0;
        for (int i = 1; i <= p; i++) {
            double diff = c[i] - c[i - 1];
            if (diff > 0) ag += diff;
            else al -= diff;
        }
        ag /= p;
        al /= p;
        r[p] = 100 - (100 / (1 + ag / (al == 0 ? 1 : al)));
        for (int i = p + 1; i < s; i++) {
            double diff = c[i] - c[i - 1];
            double gain = diff > 0 ? diff : 0;
            double loss = diff < 0 ? -diff : 0;
            ag = (ag * (p - 1) + gain) / p;
            al = (al * (p - 1) + loss) / p;
            r[i] = 100 - (100 / (1 + ag / (al == 0 ? 1 : al)));
        }
        return r;
    }

    private void calcMACD(double[] c, double[] dif, double[] dea, double[] hist, int s) {
        double[] e12 = calcEMA(c, 12, s);
        double[] e26 = calcEMA(c, 26, s);
        for (int i = 0; i < s; i++) {
            if (i < 26) { dif[i] = 0; dea[i] = 0; hist[i] = 0; continue; }
            dif[i] = e12[i] - e26[i];
        }
        double[] sig = calcEMA(dif, 9, s);
        for (int i = 0; i < s; i++) {
            if (i < 34) { dea[i] = 0; hist[i] = 0; continue; }
            dea[i] = sig[i];
            hist[i] = (dif[i] - dea[i]) * 2;
        }
    }

    private double[] calcEMA(double[] d, int p, int s) {
        double[] e = new double[s];
        e[0] = d[0];
        double m = 2.0 / (p + 1);
        for (int i = 1; i < s; i++) {
            e[i] = (d[i] - e[i - 1]) * m + e[i - 1];
        }
        return e;
    }

    private void calcKDJ(double[] h, double[] l, double[] c, double[] k, double[] d, double[] j, int s) {
        k[0] = 50;
        d[0] = 50;
        for (int i = 8; i < s; i++) {
            double ll = l[i], hh = h[i];
            for (int t = i - 8; t <= i; t++) {
                if (l[t] < ll) ll = l[t];
                if (h[t] > hh) hh = h[t];
            }
            double rsv = (c[i] - ll) / (hh - ll + 0.001) * 100;
            k[i] = (2.0 / 3) * k[i - 1] + (1.0 / 3) * rsv;
            d[i] = (2.0 / 3) * d[i - 1] + (1.0 / 3) * k[i];
            j[i] = 3 * k[i] - 2 * d[i];
        }
    }

    private void calcBollinger(double[] c, double[] ma, double[] u, double[] l, int s) {
        for (int i = 19; i < s; i++) {
            double sum = 0;
            for (int t = i - 19; t <= i; t++) {
                sum += Math.pow(c[t] - ma[i], 2);
            }
            double std = Math.sqrt(sum / 20);
            u[i] = ma[i] + 2 * std;
            l[i] = ma[i] - 2 * std;
        }
    }

    public void updateEnhancedKlines(String assetId, List<Map<String, Object>> enhanced) {
        enhancedKlineCache.put(assetId, new CopyOnWriteArrayList<>(enhanced));
    }

    public void updateTradePoints(String assetId, List<PriceDiscoveryEngine.TradePoint> points) {
        if (points != null) tradePointCache.put(assetId, new CopyOnWriteArrayList<>(points));
    }
}