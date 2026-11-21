package com.example.financialmarket.company;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Player production-asset appraisal model.
 *
 * <p>Converts machines / multiblock controllers / production lines into an appraised asset base
 * and an earning-power credit. Pure Java so it can be unit-tested without Minecraft.</p>
 *
 * <p>Valuation is deliberately conservative: replacement cost is the anchor, then a
 * depreciation/utilization/risk haircut is applied. Inventory is valued separately at base price.</p>
 */
public final class ProductionAssetModel {

    public record Asset(String id, double replacementCost, boolean multiblock,
                        double utilization) {
    }

    public record Valuation(double inventoryValue,
                            double replacementCost,
                            double depreciatedValue,
                            double utilization,
                            double concentration,
                            double assetRisk,
                            double assetQuality,
                            double productiveValue,
                            double appraisedValue,
                            int machines,
                            int multiblocks) {
    }

    private static final Pattern TIER = Pattern.compile(".*(_|\\b)(lv|mv|hv|ev|iv|luv|ulv|max)(_|\\b).*");

    private ProductionAssetModel() {
    }

    public static Valuation value(double inventoryValue, List<Asset> assets) {
        double inv = Math.max(0.0, inventoryValue);
        if (assets == null || assets.isEmpty()) {
            return new Valuation(inv, 0, 0, 0, 0, 0, 0, 0, inv, 0, 0);
        }
        double replacement = 0.0;
        double weightedUtil = 0.0;
        double largest = 0.0;
        int machines = 0;
        int multis = 0;
        for (Asset a : assets) {
            if (a == null || a.replacementCost <= 0) continue;
            double cost = a.replacementCost;
            replacement += cost;
            weightedUtil += cost * clamp01(a.utilization());
            largest = Math.max(largest, cost);
            machines++;
            if (a.multiblock()) multis++;
        }
        if (replacement <= 0) {
            return new Valuation(inv, 0, 0, 0, 0, 0, 0, 0, inv, 0, 0);
        }
        double utilization = clamp(weightedUtil / replacement, 0.0, 1.0);
        double concentration = clamp(largest / replacement, 0.0, 1.0);
        // 18% base depreciation for used industrial assets; concentration adds an idle-risk haircut.
        double depreciated = replacement * (0.82 - 0.10 * concentration);
        double risk = clamp(0.12 + 0.32 * concentration + 0.18 / Math.max(1, machines)
                + 0.15 * (1.0 - utilization), 0.0, 0.90);
        double quality = clamp(0.65 + 0.25 * utilization - 0.20 * risk, 0.05, 1.0);
        // Idle machines retain only scrap/liquidation value; active lines earn the productive base.
        double productive = depreciated * (0.15 + 0.85 * utilization) * quality;
        double appraised = inv + productive;
        return new Valuation(inv, replacement, depreciated, utilization, concentration, risk,
                quality, productive, appraised, machines, multis);
    }

    /** Replacement cost estimate from mod/block/block-entity names; deterministic tiers. */
    public static double replacementCost(String namespace, String path, String blockEntityClass) {
        String ns = norm(namespace);
        String p = norm(path);
        String be = norm(blockEntityClass);
        String all = ns + " " + p + " " + be;

        double base = 120.0;
        if (ns.contains("gregtech") || ns.contains("gtceu") || ns.contains("gtm")) base = 520.0;
        else if (ns.contains("mekanism")) base = 420.0;
        else if (ns.contains("create")) base = 260.0;
        else if (ns.contains("immersiveengineering") || ns.contains("thermal")
                || ns.contains("pneumaticcraft") || ns.contains("industrialforegoing")) base = 300.0;
        else if (ns.contains("appliedenergistics") || ns.contains("ae2")
                || ns.contains("botania") || ns.contains("ars_nouveau")
                || ns.contains("occultism") || ns.contains("bloodmagic")) base = 240.0;

        boolean multiblock = all.contains("controller") || all.contains("multiblock")
                || all.contains("core") || all.contains("reactor") || all.contains("boiler")
                || all.contains("turbine") || all.contains("blast") || all.contains("coke")
                || all.contains("assembly") || all.contains("hatch") || all.contains("port");
        if (multiblock) base *= 3.2;

        int tier = tier(all);
        base *= 1.0 + 0.35 * tier;

        if (all.contains("energy") || all.contains("generator") || all.contains("dynamo")
                || all.contains("battery") || all.contains("capacitor")) base *= 1.25;
        if (all.contains("farm") || all.contains("harvester") || all.contains("plantation")) base *= 0.8;
        return Math.round(base * 100.0) / 100.0;
    }

    public static boolean looksLikeProductionAsset(String namespace, String path, String beClass) {
        String all = norm(namespace) + " " + norm(path) + " " + norm(beClass);
        if (all.contains("chest") || all.contains("barrel") || all.contains("crate")
                || all.contains("cabinet") || all.contains("drawer") || all.contains("tank")) {
            // Storage alone is inventory, not a production line.
            if (!all.contains("machine") && !all.contains("controller")) return false;
        }
        String[] keys = {"machine", "generator", "reactor", "furnace", "smelter", "crusher",
                "press", "mixer", "assembler", "fabricator", "refinery", "boiler", "turbine",
                "compressor", "pump", "drill", "miner", "harvester", "factory", "controller",
                "multiblock", "hatch", "port", "coke", "blast", "centrifuge", "electrolyzer",
                "chemical", "infuser", "enricher", "enrichment", "chamber", "purifier",
                "incubator", "sawmill", "extruder",
                "rolling", "wiremill", "kiln", "dryer", "processor", "plant", "farm"};
        for (String k : keys) if (all.contains(k)) return true;
        return false;
    }

    public static boolean looksLikeMultiblock(String namespace, String path, String beClass) {
        String all = norm(namespace) + " " + norm(path) + " " + norm(beClass);
        return all.contains("controller") || all.contains("multiblock") || all.contains("core")
                || all.contains("reactor") || all.contains("boiler") || all.contains("turbine")
                || all.contains("blast") || all.contains("coke") || all.contains("assembly")
                || all.contains("hatch") || all.contains("port");
    }

    private static int tier(String s) {
        Matcher m = TIER.matcher(s);
        if (!m.matches()) return 0;
        return switch (m.group(2)) {
            case "lv" -> 1;
            case "mv" -> 2;
            case "hv" -> 3;
            case "ev" -> 4;
            case "iv" -> 5;
            case "luv" -> 6;
            case "ulv" -> 7;
            case "max" -> 8;
            default -> 0;
        };
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static double clamp01(double x) {
        return clamp(x, 0.0, 1.0);
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
