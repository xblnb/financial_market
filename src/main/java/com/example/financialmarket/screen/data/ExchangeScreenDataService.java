package com.example.financialmarket.screen.data;

import com.example.financialmarket.engine.AssetSnapshot;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.ai.AICompanyData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易所屏幕服务端只读数据服务（规范 §3）。
 *
 * <p>每 {@link #SNAPSHOT_INTERVAL_TICKS} tick 从 {@link MarketEngine} 抓取一份<b>紧凑快照</b>，
 * 供 {ExchangeScreenBlockEntity} 缓存并同步给客户端。所有数据均为引擎权威的<b>确定性纯函数派生</b>：
 * <ul>
 *   <li>核心指数：以 {@code engine.getIndexValueEx()}（excellent24Index 真源）为基准，滚动增量
 *       （相对上一采样点），对齐 {@code /api/index} 的 changePoint/change 语义；</li>
 *   <li>大盘行情：TopN 个股（按量降序）代码/名称/现价/涨跌幅；</li>
 *   <li>市场总览：涨/跌/平家数、总成交量/额、涨跌比。</li>
 * </ul>
 *
 * <p><b>无任何随机点</b>：快照仅由引擎状态与 tick 门控派生，同 tick 数 → 同快照（ES-6）。
 */
public final class ExchangeScreenDataService {
    private ExchangeScreenDataService() {}

    /** 快照采样节流（tick 数）：tickCounter 门控，确定性。 */
    public static final int SNAPSHOT_INTERVAL_TICKS = 10;
    /** 大盘行情展示行数上限（TopN）。 */
    public static final int BOARD_LIMIT = 12;

    /**
     * 指数滚动变化缓存（按引擎、按 tick 分组，F-1 修复）。
     *
     * <p><b>问题</b>（F-1）：原先用单一 {@code static lastIndexValue}，拼接组内多个方块在<b>同一 gate tick</b>
     * 先后调用 {@link #capture} 时会<b>各自</b>改写该共享值，导致同组内不同块得到不同的 changePoint/changePct
     * （涨跌幅与红涨绿跌颜色不一致）。
     *
     * <p><b>修复</b>：改成<b>每引擎 + 每 tick</b> 缓存。仅在<b>tick 边界</b>更新一次 {@code lastValue}，同一
     * tick 内的所有块复用同一组 {@code changePoint/changePct}。跨 tick 仍为确定性单次更新（同 tick → 同快照），
     * 纯函数、无随机、无状态污染。键为引擎对象（隔离多 level 引擎），组内同引擎同 tick 天然一致。
     */
    private static final Map<MarketEngine, IndexCache> INDEX_CACHE = new HashMap<>();

    private static final class IndexCache {
        long lastTick = -1;          // 上次采样 gate tick
        double lastValue = 0.0;      // 上次采样指数（相对上一采样点的基准）
        boolean hasLast = false;     // 是否已有前一次采样（首次不产生 change）
        double lastChangePoint = 0.0;
        double lastChangePct = 0.0;
    }

    /**
     * 抓取一帧快照。纯函数 + tick 门控，无随机。
     *
     * <p>指数滚动变化：同 <b>gate tick</b> 内（引擎 tickCounter 一致）所有块返回<b>同一组</b>
     * changePoint/changePct，仅在 tick 边界更新基准（F-1 修复）。
     *
     * @param engine 当前 level 的 MarketEngine（服务端）
     * @param mode   显示模式（0=核心指数 / 1=大盘行情 / 2=市场总览），由方块实体持有
     */
    public static ExchangeScreenSnapshot capture(MarketEngine engine, int mode) {
        if (engine == null) {
            return ExchangeScreenSnapshot.empty();
        }
        boolean marketOpen = engine.getCalendar().isMarketOpen();
        long tick = engine.getTickCounter();
        double indexValue = engine.getIndexValueEx();

        IndexCache cache = INDEX_CACHE.computeIfAbsent(engine, e -> new IndexCache());
        double changePoint;
        double changePct;
        if (tick == cache.lastTick) {
            // 同一 gate tick 内：复用缓存，整组一致（F-1 核心）
            changePoint = cache.lastChangePoint;
            changePct = cache.lastChangePct;
        } else if (cache.hasLast && cache.lastValue > 0 && indexValue > 0) {
            // 新 tick：相对上一采样点计算，仅在此更新一次基准
            changePoint = indexValue - cache.lastValue;
            changePct = (indexValue - cache.lastValue) / cache.lastValue * 100.0;
            cache.lastTick = tick;
            cache.lastValue = indexValue;
            cache.lastChangePoint = changePoint;
            cache.lastChangePct = changePct;
        } else {
            // 首次 / 无前次基准：change 置 0，落基准
            changePoint = 0.0;
            changePct = 0.0;
            cache.lastTick = tick;
            cache.lastValue = indexValue;
            cache.hasLast = true;
            cache.lastChangePoint = 0.0;
            cache.lastChangePct = 0.0;
        }

        Map<String, String> idToName = buildIdToNameMap(engine);

        List<AssetSnapshot> assets = new ArrayList<>();
        for (String id : engine.getState().getAssetIds()) {
            AssetSnapshot snap = engine.getState().getAsset(id);
            if (snap != null) assets.add(snap);
        }
        // 大盘行情：按成交量降序取 TopN（确定性，无随机）
        assets.sort(Comparator.comparingLong(AssetSnapshot::getVolume).reversed());
        int boardLimit = Math.min(BOARD_LIMIT, assets.size());
        List<ExchangeScreenSnapshot.BoardRow> board = new ArrayList<>(boardLimit);
        for (int i = 0; i < boardLimit; i++) {
            AssetSnapshot snap = assets.get(i);
            String name = idToName.getOrDefault(snap.getAssetId(), snap.getAssetId());
            board.add(new ExchangeScreenSnapshot.BoardRow(
                    snap.getAssetId(), name,
                    snap.getLastPrice(),
                    snap.getDailyChange()));
        }

        // 市场总览：涨/跌/平家数、总成交量/额、涨跌比
        int up = 0, down = 0, flat = 0;
        double totalVolume = 0.0;
        double totalAmount = 0.0;
        for (AssetSnapshot snap : assets) {
            double chg = snap.getDailyChange();
            if (chg > 0) up++;
            else if (chg < 0) down++;
            else flat++;
            totalVolume += snap.getVolume();
            totalAmount += snap.getVolume() * snap.getLastPrice();
        }
        double advDeclineRatio = down > 0 ? (double) up / down : (up > 0 ? (double) up : 0.0);

        return new ExchangeScreenSnapshot(mode, marketOpen, tick, indexValue, changePoint, changePct,
                board, up, down, flat, totalVolume, totalAmount, advDeclineRatio);
    }

    /** 由 AI 公司构建 stockId → name 映射（找不到则回退 id）。 */
    private static Map<String, String> buildIdToNameMap(MarketEngine engine) {
        Map<String, String> map = new LinkedHashMap<>();
        if (engine.getAiCompanyManager() != null) {
            for (AICompanyData c : engine.getAiCompanyManager().getAll()) {
                if (c != null && c.getStockId() != null) {
                    map.put(c.getStockId(), c.getName());
                }
            }
        }
        return map;
    }

    /**
     * F-1 自检断言：同一 gate tick 内多个方块 {@link #capture} 返回一致的变化值。
     *
     * <p>在<b>同一个 tickCounter</b> 下连续两次 {@code capture}（模拟拼接组内两块同 tick 刷新），
     * 断言 {@code changePoint/changePct}（及 tick）完全一致。不一致则抛出
     * {@link IllegalStateException}（拼接整组涨跌幅与红涨绿跌颜色必须一致）。确定性、无随机。
     *
     * @return 一致返回 true
     * @throws IllegalStateException 若同 tick 快照不一致（F-1 回归）
     */
    public static boolean assertGateTickConsistency(MarketEngine engine, int mode) {
        ExchangeScreenSnapshot a = capture(engine, mode);
        ExchangeScreenSnapshot b = capture(engine, mode);
        boolean ok = a.tick() == b.tick()
                && a.indexChangePoint() == b.indexChangePoint()
                && a.indexChangePct() == b.indexChangePct();
        if (!ok) {
            throw new IllegalStateException("F-1 回归：同一 gate tick 内快照 changePoint/changePct 不一致 "
                    + (a.tick() + " -> " + a.indexChangePoint() + "/" + a.indexChangePct()
                    + " vs " + b.indexChangePoint() + "/" + b.indexChangePct()));
        }
        return true;
    }
}
