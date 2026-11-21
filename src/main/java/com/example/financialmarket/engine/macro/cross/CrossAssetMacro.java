package com.example.financialmarket.engine.macro.cross;

import com.example.financialmarket.engine.macro.MacroEnvironment;
import com.example.financialmarket.engine.macro.world.WorldMacroSnapshot;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-asset macro coordinator: world state + stock/futures/options/player flows.
 *
 * <p>This is deliberately pure Java (no Minecraft classes) so the transmission can be tested
 * offline. It produces bounded signals; the actual price setters remain the stock order book,
 * CZCE futures market and SABR option desk.</p>
 *
 * <p>Channels:</p>
 * <ul>
 *   <li>world growth/inflation/risk → stock sector tilt, futures spot multiplier, option vol/skew;</li>
 *   <li>stock index return → futures demand and option risk premium;</li>
 *   <li>commodity/futures return → producer/consumer sector tilts;</li>
 *   <li>player stock/futures net flow → short-horizon price tilt and risk feedback;</li>
 *   <li>margin/leverage stress → option vol and futures liquidity premium.</li>
 * </ul>
 */
public final class CrossAssetMacro {

    public record Snapshot(
            long tick,
            long day,
            double stockReturn,
            double commodityReturn,
            double riskAppetite,
            double growth,
            double inflation,
            double eventRisk,
            double playerStockFlow,
            double playerFuturesFlow,
            double leverageStress,
            double futuresSpotMultiplier,
            double optionVolMultiplier,
            double optionSkewShift,
            double stockGlobalTilt,
            Map<String, Double> sectorTilts) {
    }

    private static final double MAX_SECTOR_TILT = 0.04;

    private long tick;
    private long day;
    private double lastStockIndex = Double.NaN;
    private double stockReturn;
    private double commodityReturn;
    private double riskAppetite;
    private double growth;
    private double inflation;
    private double eventRisk;
    private double playerStockFlow;
    private double playerFuturesFlow;
    private double leverageStress;
    private double futuresSpotMultiplier = 1.0;
    private double optionVolMultiplier = 1.0;
    private double optionSkewShift;
    private double stockGlobalTilt;
    private final Map<String, Double> sectorTilts = new LinkedHashMap<>();
    private double akGrowth;
    private double akReturn;
    private double akTfp;

    private double pendingStockNotional;
    private double pendingFuturesNotional;
    private int pendingStockOrders;
    private int pendingFuturesTrades;

    public CrossAssetMacro() {
        for (String sector : new String[]{"finance", "energy", "materials", "food", "tools",
                "services", "other"}) {
            sectorTilts.put(sector, 0.0);
        }
    }

    /**
     * One macro step (call every 20 ticks).
     *
     * @param currentStockIndex current index level; the first call only seeds the return
     * @param commodityReturn   commodity/futures proxy return in [-1,1]
     * @param optionStress      external risk stress (crisis tension / event risk) in [0,1]
     */
    public void step(long tick, long day, WorldMacroSnapshot world, MacroEnvironment env,
                     double currentStockIndex, double commodityReturn, double optionStress) {
        this.tick = tick;
        this.day = day;
        this.stockReturn = lastStockIndex > 0 && Double.isFinite(lastStockIndex)
                ? clamp(currentStockIndex / lastStockIndex - 1.0, -0.20, 0.20) : 0.0;
        if (currentStockIndex > 0) {
            lastStockIndex = currentStockIndex;
        }
        this.commodityReturn = clamp(commodityReturn, -0.20, 0.20);
        this.growth = world == null ? 0.0 : clamp(world.productionIndex() - 1.0, -0.25, 0.25);
        this.inflation = world == null ? 0.0 : clamp(world.cpi() - 1.0, -0.20, 0.20);
        this.eventRisk = clamp(world == null ? optionStress : world.eventRisk(), 0.0, 1.0);
        this.riskAppetite = env == null ? 0.5 : clamp(env.getRiskAppetite(), 0.0, 1.0);
        double policyRate = env == null ? 0.035 : env.getInterestRate();

        double demand = 0.55 * growth + 0.30 * (riskAppetite - 0.5)
                - 0.22 * eventRisk + 0.12 * Math.tanh(stockReturn * 12.0)
                + 0.18 * akGrowth;
        demand = clamp(demand, -0.35, 0.35);

        this.playerStockFlow = (double) Math.tanh(pendingStockNotional / 5_000_000.0);
        this.playerFuturesFlow = (double) Math.tanh(pendingFuturesNotional / 1_500_000.0);
        this.leverageStress = clamp(leverageStress, 0.0, 1.0);

        double stockFlowTilt = 0.010 * playerStockFlow;
        double commodityShock = 0.020 * Math.tanh(commodityReturn * 18.0);
        double rateShock = -0.015 * Math.tanh((policyRate - 0.035) * 20.0);

        for (String sector : sectorTilts.keySet()) {
            double tilt;
            switch (sector) {
                case "energy" -> tilt = commodityShock + 0.012 * demand - 0.006 * eventRisk;
                case "materials" -> tilt = commodityShock + 0.010 * demand - 0.008 * eventRisk;
                case "food" -> tilt = 0.015 * Math.tanh(commodityReturn * 12.0) + 0.008 * demand;
                case "tools" -> tilt = -0.014 * Math.tanh(commodityReturn * 12.0)
                        + 0.012 * demand - 0.004 * eventRisk;
                case "finance" -> tilt = rateShock + 0.008 * demand - 0.010 * eventRisk
                        + 0.005 * playerStockFlow;
                case "services" -> tilt = 0.010 * demand + 0.006 * playerStockFlow
                        - 0.006 * eventRisk;
                default -> tilt = 0.008 * demand + stockFlowTilt;
            }
            tilt += stockFlowTilt;
            sectorTilts.put(sector, clamp(tilt, -MAX_SECTOR_TILT, MAX_SECTOR_TILT));
        }
        this.stockGlobalTilt = clamp(0.010 * (riskAppetite - 0.5) + 0.008 * demand
                + 0.010 * playerStockFlow - 0.008 * eventRisk
                + 0.008 * Math.tanh(akGrowth * 40.0), -0.03, 0.03);

        this.futuresSpotMultiplier = clamp(1.0 + 0.35 * demand
                + 0.12 * Math.tanh(stockReturn * 20.0)
                + 0.06 * playerFuturesFlow
                - 0.10 * eventRisk - 0.05 * leverageStress, 0.85, 1.15);

        this.optionVolMultiplier = clamp(1.0 + 0.55 * eventRisk
                + 0.35 * Math.abs(stockReturn) * 20.0
                + 0.20 * leverageStress
                + 0.10 * Math.abs(commodityReturn) * 15.0
                - 0.10 * demand
                - 0.15 * akGrowth, 0.75, 1.80);
        this.optionSkewShift = clamp(-0.10 * eventRisk + 0.05 * playerStockFlow
                - 0.03 * demand + 0.08 * Math.tanh(stockReturn * 20.0)
                - 0.04 * leverageStress, -0.20, 0.15);

        // Decay player flow after it has been reflected once.
        pendingStockNotional *= 0.65;
        pendingFuturesNotional *= 0.65;
        pendingStockOrders = (int) Math.floor(pendingStockOrders * 0.65);
        pendingFuturesTrades = (int) Math.floor(pendingFuturesTrades * 0.65);
    }

    public void recordStockOrder(UUID playerId, String assetId, String side, int quantity,
                                 double price) {
        if (assetId == null || quantity <= 0) {
            return;
        }
        double notional = Math.max(0.0, price) * quantity;
        pendingStockNotional += "buy".equalsIgnoreCase(side) ? notional : -notional;
        pendingStockOrders++;
    }

    public void recordFuturesFlow(String product, double signedLots, double price) {
        if (product == null || signedLots == 0) {
            return;
        }
        pendingFuturesNotional += signedLots * Math.max(0.0, price);
        pendingFuturesTrades++;
    }

    public void setLeverageStress(double stress) {
        this.leverageStress = clamp(stress, 0.0, 1.0);
    }

    public void setAkState(double growth, double returnRate, double tfp) {
        this.akGrowth = clamp(growth, -0.30, 0.30);
        this.akReturn = clamp(returnRate, -0.30, 0.50);
        this.akTfp = clamp(tfp, 0.0, 2.0);
    }

    public double stockTilt(String industry) {
        String sector = sectorOf(industry);
        return clamp(stockGlobalTilt + sectorTilts.getOrDefault(sector, 0.0), -0.05, 0.05);
    }

    public double futuresSpotMultiplier(String product) {
        String sector = productSector(product);
        double local = sectorTilts.getOrDefault(sector, 0.0);
        return clamp(futuresSpotMultiplier + 0.20 * local, 0.82, 1.18);
    }

    public double optionVolMultiplier() {
        return optionVolMultiplier;
    }

    public double optionSkewShift() {
        return optionSkewShift;
    }

    public double playerStockFlow() {
        return playerStockFlow;
    }

    public double playerFuturesFlow() {
        return playerFuturesFlow;
    }

    public double stockGlobalTilt() {
        return stockGlobalTilt;
    }

    public Map<String, Double> sectorTilts() {
        return Map.copyOf(sectorTilts);
    }

    public Snapshot snapshot() {
        return new Snapshot(tick, day, stockReturn, commodityReturn, riskAppetite, growth,
                inflation, eventRisk, playerStockFlow, playerFuturesFlow, leverageStress,
                futuresSpotMultiplier, optionVolMultiplier, optionSkewShift, stockGlobalTilt,
                Map.copyOf(sectorTilts));
    }

    private static String sectorOf(String industry) {
        if (industry == null || industry.isBlank()) {
            return "other";
        }
        String s = industry.toLowerCase(Locale.ROOT);
        if (s.contains("bank") || s.contains("finance") || s.contains("insurance")
                || s.contains("银行") || s.contains("金融") || s.contains("保险")) return "finance";
        if (s.contains("energy") || s.contains("power") || s.contains("coal")
                || s.contains("能源") || s.contains("电力") || s.contains("煤")) return "energy";
        if (s.contains("food") || s.contains("farm") || s.contains("agri")
                || s.contains("食品") || s.contains("农业")) return "food";
        if (s.contains("material") || s.contains("steel") || s.contains("chemical")
                || s.contains("材料") || s.contains("钢铁") || s.contains("化工")) return "materials";
        if (s.contains("tool") || s.contains("machine") || s.contains("industrial")
                || s.contains("机械") || s.contains("工业") || s.contains("汽车")) return "tools";
        if (s.contains("service") || s.contains("retail") || s.contains("service")
                || s.contains("服务") || s.contains("零售")) return "services";
        return "other";
    }

    private static String productSector(String product) {
        if (product == null) return "other";
        String p = product.toUpperCase(Locale.ROOT);
        if (p.equals("ZC") || p.equals("MA") || p.equals("TA") || p.equals("FG")
                || p.equals("SA") || p.equals("SF") || p.equals("SM")) return "materials";
        if (p.equals("SR") || p.equals("CF") || p.equals("AP") || p.equals("RM")
                || p.equals("OI") || p.equals("UR")) return "food";
        return "other";
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
