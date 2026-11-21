package com.example.financialmarket.engine;

import com.example.financialmarket.company.CompanyManager;
import com.example.financialmarket.engine.ai.AICompanyData;
import com.example.financialmarket.engine.ai.AICompanyManager;
import net.minecraft.nbt.CompoundTag;

public class FundamentalEngine {
    public void step(MarketState state, MarketDataCollector data, MacroStateEngine macro,
                     CompanyManager companyManager, CommodityMarket commodityMarket, AICompanyManager aiMgr) {
        // AI公司价值受行业商品价格影响
        for (AICompanyData company : aiMgr.getAll()) {
            String industry = company.getIndustry();
            double factor = 1.0;
            int count = 0;
            for (CommodityEntry commodity : commodityMarket.getAllCommodities()) {
                for (String ind : commodity.getIndustries()) {
                    if (ind.equalsIgnoreCase(industry)) {
                        double priceRatio = commodity.getPrice() / commodity.getBasePrice();
                        factor *= (0.8 + 0.2 * priceRatio);
                        count++;
                        break;
                    }
                }
            }
            if (count > 0) {
                double adjustedNav = company.getNetAssetValue() * (0.5 + 0.5 * factor);
                AssetSnapshot snap = state.getAsset(company.getStockId());
                if (snap != null) snap.setNav(adjustedNav);
            }
        }
        // 玩家公司同理（如有）
    }

    public CompoundTag save() {
        return null;
    }

    public void load(CompoundTag fund) {
    }
}