package com.example.financialmarket.engine.securities;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.MarketSentiment;
import com.example.financialmarket.engine.ai.AICompanyData;
import com.example.financialmarket.engine.random.SeedableRandom;

import java.util.ArrayList;
import java.util.List;

/**
 * 证券研究报告模型（t4 落地，替代原 4 行空壳）。
 *
 * <p>建模一份研报：{@code reportId/assetId/title/rating/targetPrice/impactScore/issuedTick}。
 * 生成逻辑确定性：基于基本面 fairValue 与现价偏离度定评级与影响幅度（偏离越大、越低估 → 买入、
 * 正向影响），全部走 {@link SeedableRandom}（选资产，不 shuffle、不用 UUID.randomUUID），
 * impact 钳制在安全区间 [-0.1,0.1] 防情绪失控（R0-3 / R0-6）。
 */
public class ResearchReport {
    /** 评级。 */
    public enum Rating { BUY, HOLD, SELL }

    private final String reportId;
    private final String assetId;
    private final String title;
    private final Rating rating;
    private final double targetPrice;
    private final double impactScore;
    private final long issuedTick;

    public ResearchReport(String reportId, String assetId, String title, Rating rating,
                          double targetPrice, double impactScore, long issuedTick) {
        this.reportId = reportId;
        this.assetId = assetId;
        this.title = title;
        this.rating = rating;
        this.targetPrice = targetPrice;
        this.impactScore = impactScore;
        this.issuedTick = issuedTick;
    }

    public String getReportId() { return reportId; }
    public String getAssetId() { return assetId; }
    public String getTitle() { return title; }
    public Rating getRating() { return rating; }
    public double getTargetPrice() { return targetPrice; }
    public double getImpactScore() { return impactScore; }
    public long getIssuedTick() { return issuedTick; }

    /**
     * 确定性生成 {@code count} 份研报。
     *
     * <p>选资产：用 {@code rng} 在未选中资产集合上取索引（不 shuffle，确定性）；评级与 impact
     * 由 fairValue 相对现价的偏离度决定：偏离 &lt; -0.15（低估）→ 买入 + 正向 impact；
     * 偏离 &gt; 0.15（高估）→ 卖出 + 负向 impact；否则持有。impact 钳制 [-0.1,0.1]。
     */
    public static List<ResearchReport> generate(SecuritiesCompany issuer, MarketEngine engine,
                                                SeedableRandom rng, int count) {
        List<ResearchReport> reports = new ArrayList<>();
        if (issuer == null || engine == null || count <= 0) return reports;
        List<String> assets = new ArrayList<>(engine.getState().getAssetIds());
        if (assets.isEmpty()) return reports;
        for (int i = 0; i < count && !assets.isEmpty(); i++) {
            int idx = rng.nextInt(assets.size());
            String assetId = assets.remove(idx);
            AICompanyData co = engine.getAiCompanyManager().getAll().stream()
                    .filter(c -> c.getStockId().equals(assetId)).findFirst().orElse(null);
            if (co == null) continue;
            double price = engine.getState().getAsset(assetId).getLastPrice();
            double fair = co.getFairValue();
            if (price <= 0 || fair <= 0) continue;
            double deviation = (price - fair) / fair; // 正=高估，负=低估
            Rating rating;
            double baseImpact;
            if (deviation < -0.15) { rating = Rating.BUY; baseImpact = 0.05; }
            else if (deviation > 0.15) { rating = Rating.SELL; baseImpact = -0.05; }
            else { rating = Rating.HOLD; baseImpact = 0.0; }
            double impact = baseImpact + deviation * 0.05;
            impact = Math.max(-0.1, Math.min(0.1, impact)); // 安全区间
            double targetPrice = Math.max(0.1, price * (1.0 - deviation * 0.5)); // 目标价向 fair 回归
            long issuedTick = engine.getTickCounter();
            reports.add(new ResearchReport("RPT-" + assetId + "-" + issuedTick, assetId,
                    issuer.getName() + "研报", rating, targetPrice, impact, issuedTick));
        }
        return reports;
    }

    /** 发布影响：调整资产情绪（impact 已在安全区间）。 */
    public void applyTo(MarketSentiment sentiment) {
        if (sentiment != null && assetId != null) {
            sentiment.adjustAssetSentiment(assetId, impactScore);
        }
    }
}
