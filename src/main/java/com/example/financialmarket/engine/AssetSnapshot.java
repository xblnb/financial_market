package com.example.financialmarket.engine;

import net.minecraft.nbt.CompoundTag;

public class AssetSnapshot {
    private final String assetId;
    private double lastPrice;
    private double prevClose;
    private double open;
    private double high;
    private double low;
    private double nav;
    private double pe = -1;
    private double pb = -1;
    private double dailyChange = 0;
    private long volume = 0;
    private Double bid;
    private Double ask;

    public AssetSnapshot(String assetId, double lastPrice, double nav) {
        this.assetId = assetId;
        this.lastPrice = lastPrice;
        this.prevClose = lastPrice;
        this.open = lastPrice;
        this.high = lastPrice;
        this.low = lastPrice;
        this.nav = nav;
    }

    /** F2（连接审计修复 B1）：资产快照序列化（读档恢复资产列表，旧档缺字段用默认，读档不失败）。 */
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("assetId", assetId);
        tag.putDouble("lastPrice", lastPrice);
        tag.putDouble("prevClose", prevClose);
        tag.putDouble("open", open);
        tag.putDouble("high", high);
        tag.putDouble("low", low);
        tag.putDouble("nav", nav);
        if (pe >= 0) tag.putDouble("pe", pe);
        if (pb >= 0) tag.putDouble("pb", pb);
        tag.putDouble("dailyChange", dailyChange);
        tag.putLong("volume", volume);
        if (bid != null) tag.putDouble("bid", bid);
        if (ask != null) tag.putDouble("ask", ask);
        return tag;
    }

    /** F2（连接审计修复 B1）：资产快照反序列化；旧档缺字段用 contains 兜底默认，读档不失败。 */
    public static AssetSnapshot fromTag(CompoundTag tag) {
        if (tag == null || !tag.contains("assetId")) return null;
        String id = tag.getString("assetId");
        double last = tag.contains("lastPrice") ? tag.getDouble("lastPrice") : 0.0;
        double nav = tag.contains("nav") ? tag.getDouble("nav") : last;
        AssetSnapshot snap = new AssetSnapshot(id, last, nav);
        if (tag.contains("prevClose")) snap.prevClose = tag.getDouble("prevClose");
        if (tag.contains("open")) snap.open = tag.getDouble("open");
        if (tag.contains("high")) snap.high = tag.getDouble("high");
        if (tag.contains("low")) snap.low = tag.getDouble("low");
        if (tag.contains("pe")) snap.pe = tag.getDouble("pe");
        if (tag.contains("pb")) snap.pb = tag.getDouble("pb");
        if (tag.contains("dailyChange")) snap.dailyChange = tag.getDouble("dailyChange");
        if (tag.contains("volume")) snap.volume = tag.getLong("volume");
        if (tag.contains("bid")) snap.bid = tag.getDouble("bid");
        if (tag.contains("ask")) snap.ask = tag.getDouble("ask");
        return snap;
    }

    public String getAssetId() { return assetId; }
    public double getLastPrice() { return lastPrice; }
    public double getPrevClose() { return prevClose; }
    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getNav() { return nav; }
    public double getPE() { return pe; }
    public double getPB() { return pb; }
    public double getDailyChange() { return dailyChange; }
    public long getVolume() { return volume; }
    public Double getBid() { return bid; }
    public Double getAsk() { return ask; }

    public void setLastPrice(double lastPrice) { this.lastPrice = lastPrice; }
    public void setPrevClose(double prevClose) { this.prevClose = prevClose; }
    public void setOpen(double open) { this.open = open; }
    public void setHigh(double high) { this.high = high; }
    public void setLow(double low) { this.low = low; }
    public void setNav(double nav) { this.nav = nav; }
    public void setPE(double pe) { this.pe = pe; }
    public void setPB(double pb) { this.pb = pb; }
    public void setDailyChange(double dailyChange) { this.dailyChange = dailyChange; }
    public void setVolume(long volume) { this.volume = volume; }
    public void setBid(Double bid) { this.bid = bid; }
    public void setAsk(Double ask) { this.ask = ask; }
}