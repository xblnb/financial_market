package com.example.financialmarket.engine.circuit;

import com.example.financialmarket.engine.AssetSnapshot;
import java.util.*;

public class CircuitBreaker {
    // 单股熔断阈值
    private static final double SINGLE_STOCK_LIMIT_UP = 0.10;   // +10%
    private static final double SINGLE_STOCK_LIMIT_DOWN = -0.10; // -10%
    // 全市场熔断阈值（基于指数）
    private static final double MARKET_LIMIT_UP = 0.07;
    private static final double MARKET_LIMIT_DOWN = -0.07;

    // 熔断状态：每只股票记录是否被暂停
    private final Map<String, Boolean> haltedStocks = new HashMap<>();
    private boolean marketHalted = false;
    private int marketHaltTicks = 0;
    private static final int HALT_DURATION = 600; // 暂停 600 ticks (30秒)

    /**
     * 检查并执行熔断
     * @param assetId 股票代码
     * @param snapshot 当前快照
     * @return true 表示该股票被暂停交易
     */
    public boolean checkStock(String assetId, AssetSnapshot snapshot) {
        if (marketHalted) return true; // 全市场熔断时所有股票暂停
        double change = snapshot.getDailyChange() / 100.0; // 转为小数
        if (change >= SINGLE_STOCK_LIMIT_UP || change <= SINGLE_STOCK_LIMIT_DOWN) {
            haltedStocks.put(assetId, true);
            return true;
        }
        haltedStocks.remove(assetId);
        return false;
    }

    /** 全市场熔断检查（基于指数） */
    public boolean checkMarket(double indexChange) {
        if (indexChange >= MARKET_LIMIT_UP || indexChange <= MARKET_LIMIT_DOWN) {
            marketHalted = true;
            marketHaltTicks = HALT_DURATION;
            return true;
        }
        return false;
    }

    /** 每 tick 更新熔断倒计时 */
    public void tick() {
        if (marketHalted) {
            marketHaltTicks--;
            if (marketHaltTicks <= 0) {
                marketHalted = false;
                haltedStocks.clear();
            }
        }
    }

    public boolean isStockHalted(String assetId) {
        return marketHalted || haltedStocks.getOrDefault(assetId, false);
    }

    // ===== DG-7a（DATA_GUARD_SPEC §6.2-A）：**仅**新增只读 getter —— 常量/公式/行为/调用点零改动 =====
    // DG-3 熔断通道与本类 ±10% 通道**正交并存**：本类的阈值/暂停时长/状态查询/调用点全部保持原样。

    /** 全市场熔断暂停时长（tick；常量 600 = 30s，只读，供探针断言不变）。 */
    public int getHaltDurationTicks() {
        return HALT_DURATION;
    }

    /** 个股熔断阈值上界（+0.10，只读）。 */
    public double getSingleStockLimitUp() {
        return SINGLE_STOCK_LIMIT_UP;
    }

    /** 个股熔断阈值下界（−0.10，只读）。 */
    public double getSingleStockLimitDown() {
        return SINGLE_STOCK_LIMIT_DOWN;
    }

    /** 全市场熔断阈值上界（+0.07，只读）。 */
    public double getMarketLimitUp() {
        return MARKET_LIMIT_UP;
    }

    /** 全市场熔断阈值下界（−0.07，只读）。 */
    public double getMarketLimitDown() {
        return MARKET_LIMIT_DOWN;
    }

    /** 当前全市场是否熔断（只读）。 */
    public boolean isMarketHalted() {
        return marketHalted;
    }
}