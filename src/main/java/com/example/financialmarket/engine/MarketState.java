package com.example.financialmarket.engine;

import com.example.financialmarket.engine.ai.AICompanyData;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

public class MarketState {
    private final Map<String, AssetSnapshot> assets = new HashMap<>();
    private double riskAppetite = 0;
    private double creditImpulse = 0;
    private double inflationPressure = 0;

    public void addAsset(String id, AssetSnapshot snap) {
        assets.put(id, snap);
        System.out.println("✅ MarketState 添加资产: " + id);
    }
    public AssetSnapshot getAsset(String id) { return assets.get(id); }
    public Set<String> getAssetIds() { return assets.keySet(); }

    public double getRiskAppetite() { return riskAppetite; }
    public void setRiskAppetite(double val) { this.riskAppetite = val; }
    public double getCreditImpulse() { return creditImpulse; }
    public void setCreditImpulse(double val) { this.creditImpulse = val; }
    public double getInflationPressure() { return inflationPressure; }
    public void setInflationPressure(double val) { this.inflationPressure = val; }

    // 临时实现：为 AI 策略提供模拟收盘价列表（F2 连接审计修复：移除裸 Random，改确定性常量——
    // 本方法无调用者（调用方均走 PriceDiscoveryEngine.getRecentCloses），保留签名仅作兼容）。
    public List<Double> getRecentCloses(String assetId, int count) {
        List<Double> closes = new ArrayList<>();
        AssetSnapshot snap = assets.get(assetId);
        if (snap != null) {
            double price = snap.getLastPrice();
            for (int i = 0; i < count; i++) {
                closes.add(price);
            }
        }
        return closes;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("risk", riskAppetite);
        tag.putDouble("credit", creditImpulse);
        tag.putDouble("inflation", inflationPressure);
        // F2（连接审计修复 B1）：持久化资产列表（读档后 LISTED 玩家公司股票等市场资产不丢失）。
        // 资产快照含 lastPrice/prevClose/OHLC/NAV/成交量等，读档直接恢复；旧档缺字段用 contains 兜底为空。
        CompoundTag assetTag = new CompoundTag();
        for (AssetSnapshot snap : this.assets.values()) {
            assetTag.put(snap.getAssetId(), snap.toTag());
        }
        tag.put("assets", assetTag);
        return tag;
    }

    public void load(CompoundTag tag) {
        riskAppetite = tag.getDouble("risk");
        creditImpulse = tag.getDouble("credit");
        inflationPressure = tag.getDouble("inflation");
        // F2（连接审计修复 B1）：恢复资产列表；旧档缺 "assets" 键时保持空（由调用方决定是否 init），读档不失败。
        if (tag != null && tag.contains("assets")) {
            CompoundTag assets = tag.getCompound("assets");
            for (String id : assets.getAllKeys()) {
                AssetSnapshot snap = AssetSnapshot.fromTag(assets.getCompound(id));
                if (snap != null) this.assets.put(id, snap);
            }
        }
    }

    public void setIndexValue(double indexValue) {
    }

    public List<AICompanyData> getAll() {
        return null;
    }

    public Object getIndexValue() {
        return null;
    }
}