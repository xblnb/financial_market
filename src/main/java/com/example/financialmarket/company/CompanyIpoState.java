package com.example.financialmarket.company;

/**
 * IPO 状态机（COMPANY_SPEC §4.1）。
 *
 * <p>NONE=未启动 IPO；PRICED=已定价待发行；SUBSCRIBING=发行认购窗口开放；
 * LISTED=已上市；FAILED=认购不足/发行中止（可回到 NONE 重新发起）。
 */
public enum CompanyIpoState {
    NONE,
    PRICED,
    SUBSCRIBING,
    LISTED,
    FAILED
}
