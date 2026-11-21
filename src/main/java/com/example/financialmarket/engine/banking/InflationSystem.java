package com.example.financialmarket.engine.banking;

import com.example.financialmarket.engine.CommodityMarket;
import com.example.financialmarket.engine.CommodityEntry;

public class InflationSystem {
    private double moneySupply = 1000000;
    private double prevM2 = 1000000;       // 上一交易日 M2（分层度量）
    private double priceIndex = 100.0;
    private double inflationRate = 0.02;

    /**
     * 每日更新通胀：通胀基于 <b>M2 增长</b>。
     * M2 由 {@link BankManager#computeM2(double)} 提供完整分层口径（M1 = 现金 + 活期，M2 = M1 + 定期/储蓄 + 货币市场），
     * 而非简单的 {@code baseMoney + Σ存款}。
     * {@code moneyGrowth = (M2_t − M2_{t-1}) / M2_{t-1}}，{@code inflationRate = 0.6*moneyGrowth + 0.4*价格指数变化}。
     *
     * @param bankManager     银行体系（提供分层 M2）
     * @param commodityMarket 商品市场（价格指数）
     * @param playerCash      全体玩家流通现金（M1 现金部分）
     */
    public void update(BankManager bankManager, CommodityMarket commodityMarket, double playerCash) {
        double m2 = bankManager.computeM2(playerCash);
        double moneyGrowth = prevM2 <= 0 ? 0 : (m2 - prevM2) / prevM2;
        prevM2 = m2;
        moneySupply = m2; // 记账：moneySupply = 分层 M2

        double commodityAvg = 0;
        int count = 0;
        for (CommodityEntry entry : commodityMarket.getAllCommodities()) {
            commodityAvg += entry.getPrice() / entry.getBasePrice();
            count++;
        }
        if (count > 0) {
            commodityAvg /= count;
            priceIndex = 100 * commodityAvg;
        }

        inflationRate = moneyGrowth * 0.6 + (priceIndex / 100.0 - 1) * 0.4;
        inflationRate = Math.max(-0.1, Math.min(0.5, inflationRate));
    }

    /** 最近分层 M2 值。 */
    public double getM2() { return moneySupply; }
    public double getMoneySupply() { return moneySupply; }
    public double getPrevM2() { return prevM2; }
    public double getPriceIndex() { return priceIndex; }
    public double getInflationRate() { return inflationRate; }

    /**
     * 价格指数增长率（{@code priceIndex/100 − 1}；§8.2）。
     *
     * <p>用途：§6.9 的自动政策规则需要把"通胀"拆成货币增速与价格变化两段（本类既有公式
     * {@code inflationRate = 0.6×moneyGrowth + 0.4×(priceIndex/100 − 1)} 的反解项），
     * 该量此前只能从 {@code inflationRate} 反推，现给出显式只读出口。<b>公式零改动</b>。
     */
    public double getPriceIndexGrowth() { return priceIndex / 100.0 - 1; }

    /**
     * 货币增速（{@code (M2_t − M2_{t−1}) / M2_{t−1}}；上一期为 0 时返回 0）。
     *
     * <p>与 {@link #update} 内用的同一口径（{@code prevM2} 在 update 中更新 ⇒ 读数为"最近一次更新时的增速"）。
     * 该量供 §6.9 反应函数与 CB-13 的宏观复算直接引用，避免调用方各自反解。<b>公式零改动</b>。
     */
    public double getMoneyGrowth() {
        return prevM2 <= 0 ? 0 : (moneySupply - prevM2) / prevM2;
    }

    /** 从持久化恢复通胀状态（FM-7）；旧存档缺字段时用默认值兜底。 */
    public void restore(double prevM2, double moneySupply, double priceIndex, double inflationRate) {
        this.prevM2 = prevM2;
        this.moneySupply = moneySupply;
        this.priceIndex = priceIndex;
        this.inflationRate = inflationRate;
    }
}