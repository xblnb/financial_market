package com.example.financialmarket.engine.leverage;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 杠杆票据（杠杆合约）元数据/价格/状态（AS-L1，ML-6/ML-7）。
 *
 * <p>一种可交易/可交割的衍生品：发行方自定杠杆倍数（1.5~8）、到期日（5~90 交易日）与标的
 * （STOCK/COMMODITY/FUTURES）。价格 = 杠杆 × 标的收益率 的确定性衍生，逐日 settle-to-settle
 * 再锚定，反向超 1/杠杆 触发敲出归零。只读消费标的价源，不写回标的供需/撮合（只读协同）。
 *
 * <p>全部字段为确定性值，无随机（ML-1）。持久化随 {@link LeveragedNoteMarket#save()}。
 */
public class LeveragedNote {
    public enum UnderlyingType { STOCK, COMMODITY, FUTURES }
    public enum NoteStatus { LISTED, KNOCKED_OUT, MATURING, EXPIRED }

    /** 唯一代码，如 "LNV_1"（确定性序号生成）。 */
    public String noteId;
    /** 名称，如 "沪深300×3 三个月票据"。 */
    public String name;
    public UnderlyingType underlyingType;
    /** 标的 key：A股 assetId / 商品 CommodityEntry.key / 期货合约 code。 */
    public String underlyingKey;
    /** 自定杠杆倍数（LEVERAGE_MIN..LEVERAGE_MAX）。 */
    public double leverage;
    /** 到期日（tick 阈值）。 */
    public long maturityTicks;
    /** 发行 tick。 */
    public long issuedTick;
    /** 每份面额（默认 1000）。 */
    public double unitNotional;
    /** 发行价。 */
    public double issuePrice;
    /** 发行参考标的价格（首次定价锚）。 */
    public double refUnderlyingPrice;
    /** 上一结算标的价格（逐日再锚）。 */
    public double prevSettleUnderlying;
    /** 最新价。 */
    public double lastPrice;
    /** 上一结算票据价。 */
    public double prevSettlePrice;
    /** 未平仓份数。 */
    public long outstanding;
    /** 发行方冻结保证金。 */
    public double issuerMargin;
    /** 发行方玩家。 */
    public UUID issuerId;
    /** 票据自洽现金池（买入入池、卖出出池、结算扣减；发行方保证金兜底）。 */
    public double cashPool;
    public NoteStatus status = NoteStatus.LISTED;

    public LeveragedNote() { }

    public boolean isTradeable() { return status == NoteStatus.LISTED; }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("noteId", noteId);
        t.putString("name", name);
        t.putString("type", underlyingType.name());
        t.putString("key", underlyingKey);
        t.putDouble("lev", leverage);
        t.putLong("maturity", maturityTicks);
        t.putLong("issuedTick", issuedTick);
        t.putDouble("unitNotional", unitNotional);
        t.putDouble("issuePrice", issuePrice);
        t.putDouble("refUnderlying", refUnderlyingPrice);
        t.putDouble("prevSettleUnderlying", prevSettleUnderlying);
        t.putDouble("lastPrice", lastPrice);
        t.putDouble("prevSettlePrice", prevSettlePrice);
        t.putLong("outstanding", outstanding);
        t.putDouble("issuerMargin", issuerMargin);
        t.putUUID("issuer", issuerId);
        t.putDouble("cashPool", cashPool);
        t.putString("status", status.name());
        return t;
    }

    public static LeveragedNote load(CompoundTag t) {
        LeveragedNote n = new LeveragedNote();
        n.noteId = t.getString("noteId");
        n.name = t.getString("name");
        try { n.underlyingType = UnderlyingType.valueOf(t.getString("type")); }
        catch (IllegalArgumentException e) { n.underlyingType = UnderlyingType.STOCK; }
        n.underlyingKey = t.getString("key");
        n.leverage = t.getDouble("lev");
        n.maturityTicks = t.getLong("maturity");
        n.issuedTick = t.getLong("issuedTick");
        n.unitNotional = t.getDouble("unitNotional");
        n.issuePrice = t.getDouble("issuePrice");
        n.refUnderlyingPrice = t.getDouble("refUnderlying");
        n.prevSettleUnderlying = t.getDouble("prevSettleUnderlying");
        n.lastPrice = t.getDouble("lastPrice");
        n.prevSettlePrice = t.getDouble("prevSettlePrice");
        n.outstanding = t.getLong("outstanding");
        n.issuerMargin = t.getDouble("issuerMargin");
        if (t.contains("issuer")) n.issuerId = t.getUUID("issuer");
        n.cashPool = t.getDouble("cashPool");
        try { n.status = NoteStatus.valueOf(t.getString("status")); }
        catch (IllegalArgumentException e) { n.status = NoteStatus.LISTED; }
        return n;
    }
}
