/**
 * Port of the <a href="https://github.com/dharmeshsing/CoinTossX">CoinTossX</a> limit order book and
 * price/time-priority matching engine.
 *
 * <p><b>Upstream licence</b>: MIT. Copyright (c) Dharmesh Singh and CoinTossX contributors.
 * Upstream is a multi-module low-latency trading platform (Aeron, SBE, gRPC, off-heap memory).
 * This package contains the market-microstructure core only, rewritten as plain Java so it can run
 * inside a Minecraft server thread with no native libraries and no messaging stack:</p>
 *
 * <ul>
 *   <li>price levels ordered by price, FIFO inside a level;</li>
 *   <li>level ordering by submission time with visible orders ahead of hidden orders at the same
 *       price (upstream {@code OrderListImpl.timePriorityCompare});</li>
 *   <li>aggressive sweeping, market orders, residual resting, minimum execution size (MES);</li>
 *   <li>hidden orders (no visible contribution, removal below MRS/MES);</li>
 *   <li>stop and stop-limit orders parked until the last traded price crosses the stop price;</li>
 *   <li>time in force: IOC never rests, FOK is rejected unless fully fillable.</li>
 * </ul>
 *
 * <p><b>Not ported</b> (JSE session/market-data concerns that the mod does not need): the trading
 * session state machine (opening / intraday / volatility / closing auctions), auction uncrossing,
 * circuit-breaker session transitions, SBE execution reports, market-data dissemination and the
 * replace-order pre-processor (commented out upstream as well).</p>
 *
 * <p>Conformance against the upstream CSV fixtures is measured by
 * {@code .verify/ct_probe/CtFixtureProbe.java}; the current numbers and the exact list of
 * known deviations are recorded in {@code MODELS.md}.</p>
 */
package com.example.financialmarket.engine.cointossx;
