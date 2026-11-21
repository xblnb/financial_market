package com.example.financialmarket.engine.futures.czce;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Stateful SABR option desk for the mod.
 *
 * <p>One {@link SabrModel.Params} per CZCE product is evolved once per trading day from the
 * underlying futures return (leverage effect). Option chains are generated on demand from this
 * state and persisted with the world save.</p>
 */
public final class CzceOptionDesk {

    public static final double RATE = 0.02;
    private static final double BETA = 0.7;

    private final CzceFuturesMarket futures;
    private final Map<String, SabrModel.Params> params = new LinkedHashMap<>();
    private final Map<String, Double> lastPrice = new LinkedHashMap<>();
    private final Map<String, Double> baseVol = new LinkedHashMap<>();
    private final Random rng;
    private long lastDay = Long.MIN_VALUE;
    private double macroVolMultiplier = 1.0;
    private double macroSkewShift = 0.0;

    public CzceOptionDesk(CzceFuturesMarket futures, long seed) {
        this.futures = futures;
        this.rng = new Random(seed ^ 0x5AB5ABL);
        baseVol.put("SR", 0.12);
        baseVol.put("CF", 0.18);
        baseVol.put("TA", 0.20);
        baseVol.put("MA", 0.22);
        baseVol.put("FG", 0.22);
        baseVol.put("SA", 0.24);
        baseVol.put("ZC", 0.20);
        baseVol.put("AP", 0.20);
        baseVol.put("RM", 0.18);
        baseVol.put("OI", 0.16);
        baseVol.put("UR", 0.18);
        baseVol.put("SF", 0.20);
        baseVol.put("SM", 0.20);
    }

    public void setMacroStress(double volMultiplier, double skewShift) {
        this.macroVolMultiplier = Math.max(0.5, Math.min(2.0, volMultiplier));
        this.macroSkewShift = Math.max(-0.30, Math.min(0.20, skewShift));
    }

    public double macroVolMultiplier() {
        return macroVolMultiplier;
    }

    public void tick(long tick) {
        long day = tick / CzceFuturesMarket.DAY_TICKS;
        if (day == lastDay) {
            return;
        }
        lastDay = day;
        for (String product : baseVol.keySet()) {
            CzceFuturesMarket.Contract c = nearest(product, tick);
            if (c == null || c.lastPrice <= 0) {
                continue;
            }
            Double prev = lastPrice.put(product, c.lastPrice);
            if (prev == null || prev <= 0) {
                continue;
            }
            double ret = Math.log(c.lastPrice / prev);
            SabrModel.Params p = params.get(product);
            if (p == null) {
                p = SabrModel.fromAtmVol(c.lastPrice, baseVol.getOrDefault(product, 0.20),
                        30.0 / 365.0, BETA, -0.3, 0.5);
                params.put(product, p);
            }
            SabrModel.evolve(p, ret, 1.0 / 252.0, rng);
        }
    }

    public SabrModel.Params params(String code) {
        CzceFuturesMarket.Contract c = futures.contract(code);
        if (c == null) {
            return null;
        }
        SabrModel.Params p = params.get(c.productCode);
        if (p == null) {
            p = SabrModel.fromAtmVol(Math.max(1.0, c.lastPrice),
                    baseVol.getOrDefault(c.productCode, 0.20), 30.0 / 365.0, BETA, -0.3, 0.5);
            params.put(c.productCode, p);
        }
        return p;
    }

    public List<CzceOptionMarket.Quote> chain(String code, double[] expiryDays) {
        CzceFuturesMarket.Contract c = futures.contract(code);
        if (c == null) {
            return List.of();
        }
        SabrModel.Params p = params(code);
        if (p == null) {
            return List.of();
        }
        double f = Math.max(1.0, c.lastPrice);
        double[] strikes = {f * 0.95, f * 0.975, f, f * 1.025, f * 1.05};
        SabrModel.Params stressed = p.copy();
        stressed.alpha = Math.max(1e-8, stressed.alpha * macroVolMultiplier);
        stressed.rho = Math.max(-0.95, Math.min(0.6, stressed.rho + macroSkewShift));
        return CzceOptionMarket.chainSabr(code, f, stressed, RATE, expiryDays, strikes);
    }

    public String describe(String code) {
        CzceFuturesMarket.Contract c = futures.contract(code);
        SabrModel.Params p = params(code);
        if (c == null || p == null) {
            return code + " not listed";
        }
        return String.format(Locale.ROOT,
                "%s F=%.2f alpha=%.4f beta=%.2f rho=%.3f nu=%.3f",
                code, c.lastPrice, p.alpha, p.beta, p.rho, p.nu);
    }

    private CzceFuturesMarket.Contract nearest(String product, long tick) {
        CzceFuturesMarket.Contract best = null;
        for (CzceFuturesMarket.Contract c : futures.contracts().values()) {
            if (!product.equals(c.productCode) || c.status != CzceFuturesMarket.Status.LISTED) {
                continue;
            }
            if (c.lastTradingTick < tick) {
                continue;
            }
            if (best == null || c.lastTradingTick < best.lastTradingTick) {
                best = c;
            }
        }
        return best;
    }

    /** Relational H2 mirror for SABR state; engine_main blob remains authoritative. */
    public void saveToH2(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM czce_sabr");
        }
        String sql = "INSERT INTO czce_sabr (product, alpha, beta, rho, nu, base_alpha, base_rho,"
                + " base_nu, last_day) VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<String, SabrModel.Params> e : params.entrySet()) {
                SabrModel.Params p = e.getValue();
                ps.setString(1, e.getKey());
                ps.setDouble(2, p.alpha);
                ps.setDouble(3, p.beta);
                ps.setDouble(4, p.rho);
                ps.setDouble(5, p.nu);
                ps.setDouble(6, p.baseAlpha);
                ps.setDouble(7, p.baseRho);
                ps.setDouble(8, p.baseNu);
                ps.setLong(9, lastDay);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putLong("lastDay", lastDay);
        ListTag ps = new ListTag();
        for (Map.Entry<String, SabrModel.Params> e : params.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("product", e.getKey());
            t.putDouble("alpha", e.getValue().alpha);
            t.putDouble("beta", e.getValue().beta);
            t.putDouble("rho", e.getValue().rho);
            t.putDouble("nu", e.getValue().nu);
            t.putDouble("baseAlpha", e.getValue().baseAlpha);
            t.putDouble("baseRho", e.getValue().baseRho);
            t.putDouble("baseNu", e.getValue().baseNu);
            ps.add(t);
        }
        root.put("params", ps);
        return root;
    }

    public void load(CompoundTag root) {
        params.clear();
        lastDay = root.getLong("lastDay");
        ListTag ps = root.getList("params", Tag.TAG_COMPOUND);
        for (int i = 0; i < ps.size(); i++) {
            CompoundTag t = ps.getCompound(i);
            SabrModel.Params p = new SabrModel.Params(t.getDouble("alpha"), t.getDouble("beta"),
                    t.getDouble("rho"), t.getDouble("nu"));
            p.baseAlpha = t.getDouble("baseAlpha");
            p.baseRho = t.getDouble("baseRho");
            p.baseNu = t.getDouble("baseNu");
            params.put(t.getString("product"), p);
        }
    }

    public Map<String, SabrModel.Params> allParams() {
        return Map.copyOf(params);
    }
}
