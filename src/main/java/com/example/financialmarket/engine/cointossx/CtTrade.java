package com.example.financialmarket.engine.cointossx;

/** An execution produced by the CoinTossX matching core (port of {@code orderBook.Trade}). */
public record CtTrade(long id, long price, long quantity) {
}
