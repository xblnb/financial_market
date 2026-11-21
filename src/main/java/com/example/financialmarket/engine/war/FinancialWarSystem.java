package com.example.financialmarket.engine.war;

import com.example.financialmarket.engine.*;
import com.example.financialmarket.engine.banking.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

public class FinancialWarSystem {
    private final MarketSentiment sentiment;
    private final CommodityMarket commodityMarket;
    private final CentralBank centralBank;
    private final Government government;
    private PriceDiscoveryEngine priceEngine = null;

    public FinancialWarSystem(MarketSentiment sentiment, CommodityMarket commodityMarket,
                              CentralBank centralBank, Government government) {
        this.sentiment = sentiment;
        this.commodityMarket = commodityMarket;
        this.centralBank = centralBank;
        this.government = government;
        this.priceEngine = priceEngine;
    }

    public void execute(ServerPlayer player, String action, String target, double amount) {
        switch (action) {
            case "interest" -> centralBank.setDiscountRate(centralBank.getDiscountRate() + amount);
            case "reserve" -> centralBank.setReserveRequirement(centralBank.getReserveRequirement() + amount);
            case "buy" -> {
                // 政府入场购买资产，增加需求
                commodityMarket.getAllCommodities().forEach(e -> e.buyPressure((int) amount));
            }
            case "sell" -> {
                // 政府卖出资产，增加供给
                commodityMarket.getAllCommodities().forEach(e -> e.sellPressure((int) amount));
            }
            case "sentiment" -> sentiment.adjustGlobalSentiment(amount);
            case "commodity_buy" -> {
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(target));
                if (item != null) {
                    var entry = commodityMarket.getCommodity(item);
                    if (entry != null) entry.buyPressure((int) amount);
                }
            }
            case "commodity_sell" -> {
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(target));
                if (item != null) {
                    var entry = commodityMarket.getCommodity(item);
                    if (entry != null) entry.sellPressure((int) amount);
                }
            }
        }
    }
}