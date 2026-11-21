package com.example.financialmarket.engine.securities;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.MarketSentiment;
import com.example.financialmarket.engine.Order;
import com.example.financialmarket.engine.PriceDiscoveryEngine;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/**
 * 证券公司（t4：自营 propTrade 接线去随机化）。
 *
 * <p>自营订单：
 * <ul>
 *   <li>确定性合成 playerId：由公司 id 经 {@code UUID.nameUUIDFromBytes} 派生，不再用
 *       {@code UUID.randomUUID()}（R0-3，无裸随机）。</li>
 *   <li>{@code setSynthetic(true)}：自营合成流不污染真实玩家 T+1 账本（AS-3）。</li>
 *   <li>走 {@code engine.submitOrder}：复用 A 股涨跌停带、一手整数倍（买入）校验（R0-6）；
 *       数量下限保护且受 {@code capital} 约束。</li>
 * </ul>
 */
public class SecuritiesCompany {
    public enum BusinessType { BROKERAGE, INVESTMENT_BANKING, PROP_TRADING, ASSET_MANAGEMENT }

    private final String companyId;
    private final String name;
    private double capital;                     // 自有资本
    private double brokerageCommissionRate;     // 经纪佣金率
    private double underwritingFeeRate;         // 承销费率
    private final List<BusinessType> businesses;
    private final Map<String, Integer> propPositions = new HashMap<>(); // 自营持仓
    private final Map<String, Double> clientAssets = new HashMap<>();   // 客户资产（简化）

    public SecuritiesCompany(String companyId, String name, double initialCapital,
                             List<BusinessType> businesses) {
        this.companyId = companyId;
        this.name = name;
        this.capital = initialCapital;
        this.businesses = businesses;
        this.brokerageCommissionRate = 0.0003; // 万三
        this.underwritingFeeRate = 0.05;       // 5%
    }

    public String getCompanyId() { return companyId; }
    public String getName() { return name; }
    public double getCapital() { return capital; }
    public List<BusinessType> getBusinesses() { return businesses; }

    /** 确定性合成自营玩家 id（同公司 → 同 id，不随机）。 */
    private UUID propPlayerId() {
        return UUID.nameUUIDFromBytes(("prop-" + companyId).getBytes(StandardCharsets.UTF_8));
    }

    /** 代理买卖（从客户端调用）。 */
    public void executeBrokerOrder(Order order, PriceDiscoveryEngine engine) {
        // 经纪通道预留（收取佣金在订单成交后实现，此处简化）
    }

    /**
     * 自营交易：按 buyRatio 方向生成一笔合成订单并经引擎提交（真实进入订单簿，复用 A 股校验）。
     * 确定性：数量 = max(1, capital*1%/现价)，A 股买入钳到 100 整数倍；合成 playerId 不随机。
     */
    public void propTrade(MarketEngine engine, String assetId, double buyRatio) {
        if (!businesses.contains(BusinessType.PROP_TRADING)) return;
        double lastPrice = engine.getState().getAsset(assetId).getLastPrice();
        if (lastPrice <= 0) return;
        int quantity = (int) (capital * 0.01 / lastPrice);
        quantity = Math.max(1, quantity);
        boolean buy = buyRatio > 0.5;
        if (buy && engine.isAShareEnabled()) {
            // A 股一手整数倍（买入）
            quantity = Math.max(PriceDiscoveryEngine.A_SHARE_BOARD_LOT,
                    (quantity / PriceDiscoveryEngine.A_SHARE_BOARD_LOT) * PriceDiscoveryEngine.A_SHARE_BOARD_LOT);
        }
        Order.Type type = buy ? Order.Type.LIMIT_BUY : Order.Type.LIMIT_SELL;
        Order order = new Order(propPlayerId(), assetId, type, quantity, lastPrice);
        order.setSynthetic(true); // 自营合成流：不参与真实账户 T+1 交收（AS-3）
        engine.submitOrder(order);
    }

    /** 发布研报：影响市场情绪（已接入 MarketSentiment）。 */
    public void publishResearch(MarketSentiment sentiment, String assetId, double impact) {
        sentiment.adjustAssetSentiment(assetId, impact);
    }
}
