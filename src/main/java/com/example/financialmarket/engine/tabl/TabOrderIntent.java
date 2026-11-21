package com.example.financialmarket.engine.tabl;

/**
 * One order intention produced by a TABL agent and handed to the venue.
 *
 * <p>Kept deliberately dumb: the gateway stamps the time and assigns ids, so an intention is a
 * pure description of what the agent wants. {@code type}/{@code tif} use the CoinTossX codes so the
 * mapping stays total and lossless.</p>
 */
public record TabOrderIntent(String assetId, byte side, byte type, long price, int quantity,
                            byte tif, long agentId) {

    public static TabOrderIntent limit(String assetId, byte side, long price, int qty, long agentId) {
        return new TabOrderIntent(assetId, side, CoinTossXType.LIMIT, price, qty, (byte) 0, agentId);
    }

    public static TabOrderIntent market(String assetId, byte side, int qty, long agentId) {
        return new TabOrderIntent(assetId, side, CoinTossXType.MARKET, 0L, qty, (byte) 0, agentId);
    }

    /** Local mirror of the CoinTossX type codes, so this class has no venue import. */
    public static final class CoinTossXType {
        public static final byte MARKET = 1;
        public static final byte LIMIT = 2;
        public static final byte STOP = 3;
        public static final byte STOP_LIMIT = 4;
        public static final byte HIDDEN_LIMIT = 5;

        private CoinTossXType() {
        }
    }
}
