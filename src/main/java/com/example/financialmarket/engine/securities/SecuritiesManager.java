package com.example.financialmarket.engine.securities;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.random.SeedableRandom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 证券业务管理器（t4：自营 dailyPropTrading 接线去随机化 + 研报发布）。
 *
 * <p>确定性：资产选择改用 {@link SeedableRandom} 索引（不再 {@code Collections.shuffle}），
 * 研报生成走 {@link ResearchReport#generate}（SeedableRandom，无裸随机）（R0-3）。
 */
public class SecuritiesManager {
    private final Map<String, SecuritiesCompany> companies = new LinkedHashMap<>();
    /** 确定性子流（引擎种子派生，经 {@link #setSeed} 注入）。 */
    private SeedableRandom rng = new SeedableRandom(0L);

    public SecuritiesManager() {
        // 初始化几家证券公司
        companies.put("SEC_A", new SecuritiesCompany("SEC_A", "中信方块证券",
                5000000, List.of(SecuritiesCompany.BusinessType.BROKERAGE, SecuritiesCompany.BusinessType.INVESTMENT_BANKING,
                SecuritiesCompany.BusinessType.PROP_TRADING, SecuritiesCompany.BusinessType.ASSET_MANAGEMENT)));
        companies.put("SEC_B", new SecuritiesCompany("SEC_B", "海通下界证券",
                3000000, List.of(SecuritiesCompany.BusinessType.BROKERAGE, SecuritiesCompany.BusinessType.PROP_TRADING)));
        companies.put("SEC_C", new SecuritiesCompany("SEC_C", "国泰末地证券",
                4000000, List.of(SecuritiesCompany.BusinessType.BROKERAGE, SecuritiesCompany.BusinessType.INVESTMENT_BANKING,
                SecuritiesCompany.BusinessType.ASSET_MANAGEMENT)));
    }

    /** 注入引擎种子派生的确定性子流（同种子 → 同自营/研报序列）。 */
    public void setSeed(long seed) {
        this.rng = new SeedableRandom(SeedableRandom.mix64(seed));
    }

    public SecuritiesCompany getCompany(String id) { return companies.get(id); }
    public Collection<SecuritiesCompany> getAllCompanies() { return companies.values(); }

    /** 每日自营交易（确定性）：对每家含 PROP_TRADING 的券商，用 rng 索引确定性选取 ≤5 只资产下单。 */
    public void dailyPropTrading(MarketEngine engine) {
        for (SecuritiesCompany sc : companies.values()) {
            if (!sc.getBusinesses().contains(SecuritiesCompany.BusinessType.PROP_TRADING)) continue;
            List<String> assets = new ArrayList<>(engine.getState().getAssetIds());
            if (assets.isEmpty()) continue;
            int n = Math.min(5, assets.size());
            for (int i = 0; i < n && !assets.isEmpty(); i++) {
                int idx = rng.nextInt(assets.size()); // 确定性索引，不 shuffle
                String assetId = assets.remove(idx);
                double buyRatio = engine.getSentiment().getBuyRatio(assetId);
                sc.propTrade(engine, assetId, buyRatio);
            }
        }
    }

    /** 发布研报（确定性）：每家券商生成并发布固定数量的研报，影响资产情绪。 */
    public void publishResearch(MarketEngine engine, int perCompany) {
        for (SecuritiesCompany sc : companies.values()) {
            List<ResearchReport> reports = ResearchReport.generate(sc, engine, rng, perCompany);
            for (ResearchReport r : reports) {
                r.applyTo(engine.getSentiment());
                System.out.println("📰 " + sc.getName() + " 发布研报: " + r.getTitle()
                        + " → " + r.getAssetId() + " [" + r.getRating() + "] impact="
                        + String.format("%.3f", r.getImpactScore()));
            }
        }
    }
}
