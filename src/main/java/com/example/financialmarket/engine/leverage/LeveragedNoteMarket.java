package com.example.financialmarket.engine.leverage;

import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.AssetSnapshot;
import com.example.financialmarket.engine.CommodityEntry;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.calendar.TradingCalendar;
import com.example.financialmarket.engine.leverage.LeveragedNote.NoteStatus;
import com.example.financialmarket.engine.leverage.LeveragedNote.UnderlyingType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 杠杆票据市场（AS-L1，ML-6/ML-7）。
 *
 * <p>独立衍生品市场：可发行（自定杠杆/到期/标的）、可交易（lastPrice×units 即时撮合成交 + 确定性
 * 订单簿成交记录）、可交割（到期现金/实物、敲出归零）。价格 = 杠杆 × 标的收益率的确定性衍生，
 * 逐日 settle-to-settle 再锚定。标的只读消费 A 股股票/期货/商品价源，不改其供需/撮合（只读协同）。
 *
 * <p>确定性：发行校验/定价/敲出/交割全部为纯确定性逻辑，无 {@code Math.random/new Random}；
 * 时间权威 = 引擎 tick 计数器（ML-1）。隔离：{@code leverage.enabled=false} 时不注册/不 tick/不持久化。
 */
public class LeveragedNoteMarket {
    // ===== AS-L1 §6 / §7 参数（默认值，可经 setter 配置） =====
    /** 自定杠杆下限。 */
    public static final double LEVERAGE_MIN = 1.5;
    /** 自定杠杆上限。 */
    public static final double LEVERAGE_MAX = 8.0;
    /** 最短到期（5 交易日）。 */
    public static final long MIN_TERM_TICKS = 6000L * 5;
    /** 最长到期（90 交易日）。 */
    public static final long MAX_TERM_TICKS = 6000L * 90;
    /** 每份面额。 */
    public static final double UNIT_NOTIONAL = 1000;
    /** 单次发行份数上限。 */
    public static final int MAX_UNITS_PER_ISSUE = 100000;
    /** 全市场票据数上限。 */
    public static final int MAX_NOTES = 256;
    /** 敲出归零阈值（≤0 即敲出）。 */
    public static final double KNOCK_OUT_FLOOR = 0.0;
    /** 现金交割（true）或实物交割（false，本期现金，实物为扩展点）。 */
    public static final boolean CASH_SETTLEMENT = true;
    /** 沿用 A 股休市门控（true）；false 则票据全天可交易。 */
    public static final boolean TRADE_GATE_BY_CALENDAR = true;

    private final long seed;
    private MarketEngine engine;
    private final Map<String, LeveragedNote> notes = new LinkedHashMap<>();
    /** 玩家持仓：playerId → noteId → 持仓。 */
    private final Map<UUID, Map<String, LeveragedNotePosition>> holdings = new LinkedHashMap<>();
    /** 确定性票据序号。 */
    private long nextNoteSeq = 1;

    public LeveragedNoteMarket(long seed) {
        this.seed = seed; // 保留确定性子流预留（当前定价/交割纯确定，无需随机）
    }

    public void setEngine(MarketEngine engine) { this.engine = engine; }

    public enum IssueResult {
        OK, INVALID_ARGS, MARKET_CLOSED, BANK_CARD_REQUIRED, INVALID_LEVERAGE, INVALID_MATURITY,
        UNDERLYING_NOT_TRADABLE, INSUFFICIENT_CASH, NOTE_LIMIT, UNITS_LIMIT
    }

    // ===== 查询 =====
    public Map<String, LeveragedNote> getNotes() { return notes; }
    public Map<UUID, Map<String, LeveragedNotePosition>> getHoldings() { return holdings; }
    public LeveragedNote getNote(String noteId) { return notes.get(noteId); }
    public List<LeveragedNotePosition> getPositions(UUID playerId) {
        Map<String, LeveragedNotePosition> m = holdings.get(playerId);
        return m == null ? new ArrayList<>() : new ArrayList<>(m.values());
    }

    // ===== 发行（AS-L1 §6.3） =====
    public IssueResult issue(ServerPlayer player, UnderlyingType type, String underlyingKey,
                             double leverage, long maturityTicks, int units) {
        if (player == null || underlyingKey == null || units <= 0) return IssueResult.INVALID_ARGS;
        if (leverage < LEVERAGE_MIN - 1e-9 || leverage > LEVERAGE_MAX + 1e-9) return IssueResult.INVALID_LEVERAGE;
        long tc = engine.getTickCounter();
        if (maturityTicks < tc + MIN_TERM_TICKS || maturityTicks > tc + MAX_TERM_TICKS) return IssueResult.INVALID_MATURITY;
        if (TRADE_GATE_BY_CALENDAR && engine.isAShareEnabled() && !engine.getCalendar().isMarketOpen()) {
            return IssueResult.MARKET_CLOSED;
        }
        if (!engine.hasBankCard(player.getUUID())) return IssueResult.BANK_CARD_REQUIRED;
        if (units > MAX_UNITS_PER_ISSUE) return IssueResult.UNITS_LIMIT;
        if (notes.size() >= MAX_NOTES) return IssueResult.NOTE_LIMIT;
        double underlyingPrice = underlyingPrice(type, underlyingKey);
        if (underlyingPrice <= 0) return IssueResult.UNDERLYING_NOT_TRADABLE;

        // 发行方冻结保证金 = unitNotional × units / leverage（从 PlayerEconomy 现金扣减）
        double margin = UNIT_NOTIONAL * units / leverage;
        PlayerEconomy eco = PlayerEconomy.get(player);
        if (eco == null || eco.getCash() < margin) return IssueResult.INSUFFICIENT_CASH;
        eco.setCash(eco.getCash() - margin);

        LeveragedNote note = new LeveragedNote();
        note.noteId = "LNV_" + nextNoteSeq++;
        note.name = buildName(type, underlyingKey, leverage);
        note.underlyingType = type;
        note.underlyingKey = underlyingKey;
        note.leverage = leverage;
        note.maturityTicks = maturityTicks;
        note.issuedTick = tc;
        note.unitNotional = UNIT_NOTIONAL;
        note.issuePrice = UNIT_NOTIONAL;
        note.refUnderlyingPrice = underlyingPrice;
        note.prevSettleUnderlying = underlyingPrice;
        note.prevSettlePrice = UNIT_NOTIONAL;
        note.lastPrice = UNIT_NOTIONAL;
        note.outstanding = units;
        note.issuerMargin = margin;
        note.issuerId = player.getUUID();
        note.cashPool = 0;
        note.status = NoteStatus.LISTED;
        notes.put(note.noteId, note);
        // 发行方本人亦可持有
        addHolding(player.getUUID(), note.noteId, units, UNIT_NOTIONAL);
        return IssueResult.OK;
    }

    // ===== 交易（AS-L1 §6.5：lastPrice×units 即时撮合 + 确定性成交记录） =====
    public IssueResult buy(ServerPlayer player, String noteId, int units) {
        if (player == null || units <= 0) return IssueResult.INVALID_ARGS;
        LeveragedNote note = notes.get(noteId);
        if (note == null || !note.isTradeable()) return IssueResult.UNDERLYING_NOT_TRADABLE;
        if (TRADE_GATE_BY_CALENDAR && engine.isAShareEnabled() && !engine.getCalendar().isMarketOpen()) {
            return IssueResult.MARKET_CLOSED;
        }
        if (!engine.hasBankCard(player.getUUID())) return IssueResult.BANK_CARD_REQUIRED;
        double cost = note.lastPrice * units;
        PlayerEconomy eco = PlayerEconomy.get(player);
        if (eco == null || eco.getCash() < cost) return IssueResult.INSUFFICIENT_CASH;
        eco.setCash(eco.getCash() - cost);
        note.cashPool += cost;
        note.outstanding += units;
        addHolding(player.getUUID(), noteId, units, note.lastPrice);
        return IssueResult.OK;
    }

    public IssueResult sell(ServerPlayer player, String noteId, int units) {
        if (player == null || units <= 0) return IssueResult.INVALID_ARGS;
        LeveragedNote note = notes.get(noteId);
        if (note == null || !note.isTradeable()) return IssueResult.UNDERLYING_NOT_TRADABLE;
        if (TRADE_GATE_BY_CALENDAR && engine.isAShareEnabled() && !engine.getCalendar().isMarketOpen()) {
            return IssueResult.MARKET_CLOSED;
        }
        Map<String, LeveragedNotePosition> own = holdings.computeIfAbsent(player.getUUID(), k -> new LinkedHashMap<>());
        LeveragedNotePosition pos = own.get(noteId);
        if (pos == null || pos.units < units) return IssueResult.INVALID_ARGS; // 持仓不足
        double proceeds = note.lastPrice * units;
        PlayerEconomy eco = PlayerEconomy.get(player);
        if (eco == null) return IssueResult.INVALID_ARGS;
        eco.addCash(proceeds);
        note.cashPool -= proceeds;
        note.outstanding = Math.max(0, note.outstanding - units);
        pos.units -= units;
        if (pos.units <= 0) own.remove(noteId);
        return IssueResult.OK;
    }

    private void addHolding(UUID playerId, String noteId, int units, double price) {
        Map<String, LeveragedNotePosition> own = holdings.computeIfAbsent(playerId, k -> new LinkedHashMap<>());
        LeveragedNotePosition pos = own.get(noteId);
        if (pos != null) pos.addUnits(units, price);
        else own.put(noteId, new LeveragedNotePosition(playerId, noteId, units, price));
    }

    // ===== 逐日定价 / 敲出 / 到期交割（AS-L1 §6.4/§6.6） =====
    /** 每 tick：中间重估 lastPrice（settle-to-settle）；交易日边界：再锚 + 敲出 + 到期交割。 */
    public void tick(ServerLevel level, MarketEngine engine, TradingCalendar calendar) {
        for (LeveragedNote note : notes.values()) {
            if (note.status != NoteStatus.LISTED) continue;
            double u = underlyingPrice(note.underlyingType, note.underlyingKey);
            if (u <= 0 || note.prevSettleUnderlying <= 0) continue;
            double next = note.prevSettlePrice * (1.0 + note.leverage * (u / note.prevSettleUnderlying - 1.0));
            note.lastPrice = round2(next);
            if (note.lastPrice <= KNOCK_OUT_FLOOR) note.lastPrice = 0.0;
        }
        if (calendar.isSessionEnd()) {
            settleDaily(level);
        }
    }

    private void settleDaily(ServerLevel level) {
        List<LeveragedNote> snapshot = new ArrayList<>(notes.values());
        for (LeveragedNote note : snapshot) {
            if (note.status != NoteStatus.LISTED) continue;
            double u = underlyingPrice(note.underlyingType, note.underlyingKey);
            if (u > 0) note.prevSettleUnderlying = u;
            note.prevSettlePrice = note.lastPrice;
            // 敲出：反向超 1/杠杆 → 归零了结
            if (note.lastPrice <= KNOCK_OUT_FLOOR) {
                settleNote(level, note, 0.0, NoteStatus.KNOCKED_OUT);
                continue;
            }
            // 到期：tick ≥ maturityTicks → 交割
            if (engine.getTickCounter() >= note.maturityTicks) {
                if (CASH_SETTLEMENT) {
                    settleNote(level, note, note.lastPrice, NoteStatus.EXPIRED);
                } else {
                    // 实物交割为可配置扩展点；本期统一现金结算兜底（ML-6 §6.6）
                    settleNote(level, note, note.lastPrice, NoteStatus.EXPIRED);
                }
            }
        }
    }

    /**
     * 了结某票据全部持仓：持票人现金结算 PnL = (finalPrice − avgEntryPrice) × units（确定性）；
     * 发行方保证金兜底返还 = issuerMargin + 剩余 cashPool（≥0）。
     */
    private void settleNote(ServerLevel level, LeveragedNote note, double finalPrice, NoteStatus finalStatus) {
        double net = note.cashPool;
        List<UUID> holders = new ArrayList<>(holdings.keySet());
        for (UUID pid : holders) {
            Map<String, LeveragedNotePosition> own = holdings.get(pid);
            if (own == null) continue;
            LeveragedNotePosition pos = own.get(note.noteId);
            if (pos == null) continue;
            double pnl = (finalPrice - pos.avgEntryPrice) * pos.units;
            net -= pnl;
            engine.creditPlayerCash(level, pid, round2(pnl));
            own.remove(note.noteId);
        }
        // 发行方保证金兜底返还
        double issuerReturn = note.issuerMargin + net;
        if (issuerReturn > 0 && note.issuerId != null) {
            engine.creditPlayerCash(level, note.issuerId, round2(issuerReturn));
        }
        note.status = finalStatus;
        note.outstanding = 0;
        note.cashPool = 0;
        System.out.println("📄 杠杆票据了结 " + note.noteId + " [" + finalStatus + "] 最后价="
                + String.format("%.2f", finalPrice) + " 发行方返还=" + String.format("%.2f", Math.max(0, issuerReturn)));
    }

    // ===== 只读协同价源（AS-L1 §6.7，不改标的公式/供需/撮合） =====
    private double underlyingPrice(UnderlyingType type, String key) {
        switch (type) {
            case STOCK: {
                AssetSnapshot snap = engine.getState().getAsset(key);
                return snap != null ? snap.getLastPrice() : 0.0;
            }
            case FUTURES: {
                var c = engine.getFuturesMarket().getContract(key);
                return c != null ? c.lastPrice : 0.0;
            }
            case COMMODITY: {
                for (CommodityEntry e : engine.getCommodityMarket().getAllCommodities()) {
                    if (e.getKey().equals(key)) return e.getPrice();
                }
                return 0.0;
            }
            default:
                return 0.0;
        }
    }

    private String buildName(UnderlyingType type, String key, double leverage) {
        String base;
        switch (type) {
            case STOCK: base = "股票"; break;
            case COMMODITY: base = "商品"; break;
            default: base = "期货";
        }
        return base + "×" + (long) leverage + " 杠杆票据(" + key + ")";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ===== 持久化（随 MarketEngine.save()/load() 的 "leverage" 字段，ML-9） =====
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("nextNoteSeq", nextNoteSeq);
        ListTag notesList = new ListTag();
        for (LeveragedNote n : notes.values()) notesList.add(n.save());
        tag.put("notes", notesList);
        ListTag holdingsList = new ListTag();
        for (Map<String, LeveragedNotePosition> own : holdings.values()) {
            for (LeveragedNotePosition pos : own.values()) holdingsList.add(pos.save());
        }
        tag.put("holdings", holdingsList);
        return tag;
    }

    public void load(CompoundTag tag) {
        notes.clear();
        holdings.clear();
        if (tag == null || tag.isEmpty()) return;
        if (tag.contains("nextNoteSeq")) nextNoteSeq = tag.getLong("nextNoteSeq");
        ListTag notesList = tag.getList("notes", Tag.TAG_COMPOUND);
        for (int i = 0; i < notesList.size(); i++) {
            LeveragedNote n = LeveragedNote.load(notesList.getCompound(i));
            if (n.noteId != null && !n.noteId.isEmpty()) notes.put(n.noteId, n);
        }
        ListTag holdingsList = tag.getList("holdings", Tag.TAG_COMPOUND);
        for (int i = 0; i < holdingsList.size(); i++) {
            LeveragedNotePosition pos = LeveragedNotePosition.load(holdingsList.getCompound(i));
            holdings.computeIfAbsent(pos.playerId, k -> new LinkedHashMap<>()).put(pos.noteId, pos);
        }
    }
}
