package com.example.financialmarket.engine;

public class TransactionCosts {
    private static final double COMMISSION_RATE = 0.001;  // 0.1% 手续费
    private static final double SLIPPAGE_FACTOR = 0.005;  // 0.5% 滑点

    /**
     * 计算买入实际成本
     * @param basePrice 当前最新价
     * @param quantity 数量
     * @return double[]{实际成交均价, 总成本}
     */
    public static double[] calculateBuyCost(double basePrice, int quantity) {
        double slippagePrice = basePrice * (1 + SLIPPAGE_FACTOR);  // 买入滑点向上
        double commission = slippagePrice * quantity * COMMISSION_RATE;
        double totalCost = slippagePrice * quantity + commission;
        return new double[]{slippagePrice, totalCost};
    }

    /**
     * 计算卖出实际收入
     * @param basePrice 当前最新价
     * @param quantity 数量
     * @return double[]{实际成交均价, 总收入(扣除手续费)}
     */
    public static double[] calculateSellRevenue(double basePrice, int quantity) {
        double slippagePrice = basePrice * (1 - SLIPPAGE_FACTOR); // 卖出滑点向下
        double commission = slippagePrice * quantity * COMMISSION_RATE;
        double revenue = slippagePrice * quantity - commission;
        return new double[]{slippagePrice, revenue};
    }
}