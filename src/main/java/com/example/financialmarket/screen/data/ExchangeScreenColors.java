package com.example.financialmarket.screen.data;

/**
 * 交易所屏幕颜色语义（红涨绿跌，CN 色，与 {@code FinancialTabletScreen} 一致）。
 *
 * <p>规范 §11：涨=红、跌=绿、平=中性灰。常量供 {BER} 与数据服务引用。
 */
public final class ExchangeScreenColors {
    private ExchangeScreenColors() {}

    /** 涨（changePct &gt; 0）→ 红（CN）。 */
    public static final int RISE = 0xFFFF5252;
    /** 跌（changePct &lt; 0）→ 绿（CN）。 */
    public static final int FALL = 0xFF00A3FF;
    /** 平 / 无变化（changePct == 0）→ 中性灰。 */
    public static final int FLAT = 0xFF9E9E9E;
}
