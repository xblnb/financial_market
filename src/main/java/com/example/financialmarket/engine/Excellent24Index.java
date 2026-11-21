package com.example.financialmarket.engine;

import com.example.financialmarket.engine.ai.AICompanyData;
import com.example.financialmarket.engine.ai.AICompanyManager;
import java.util.*;
import java.util.stream.Collectors;

public class Excellent24Index {
    private List<String> constituents = new ArrayList<>();
    private double indexValue = 1000.0;
    private long lastRebalanceTime = 0;

    // 注意参数顺序：先 MarketState 再 AICompanyManager
    public void rebalance(MarketState state, AICompanyManager aiManager) {
        // 按 stockId 去重（防御：历史/读档路径曾出现同 id 双副本，直接流式排序会同时选中
        // 同公司两次 → 成分重复 → index_constituent 主键冲突 + 指数权重失真）。确定性、无随机。
        Map<String, AICompanyData> byId = new LinkedHashMap<>();
        for (AICompanyData c : aiManager.getAll()) {
            if (c != null && c.getStockId() != null) byId.putIfAbsent(c.getStockId(), c);
        }
        List<AICompanyData> sorted = byId.values().stream()
                .sorted((a, b) -> {
                    double mcapA = a.getNetAssetValue() * a.getTotalShares();
                    double mcapB = b.getNetAssetValue() * b.getTotalShares();
                    return Double.compare(mcapB, mcapA);
                })
                .limit(24)
                .collect(Collectors.toList());

        constituents.clear();
        for (AICompanyData c : sorted) {
            constituents.add(c.getStockId());
        }
    }

    public void updateValue(MarketState state) {
        if (constituents.isEmpty()) {
            indexValue = 1000.0;
            return;
        }
        double sum = 0;
        int count = 0;
        for (String id : constituents) {
            AssetSnapshot snap = state.getAsset(id);
            if (snap != null) {
                sum += snap.getLastPrice();
                count++;
            }
        }
        if (count > 0) {
            indexValue = sum / count * 100;
        }
    }

    public double getIndexValue() { return indexValue; }
    public List<String> getConstituents() { return constituents; }
    public long getLastRebalanceTime() { return lastRebalanceTime; }
    public void setLastRebalanceTime(long time) { this.lastRebalanceTime = time; }
}