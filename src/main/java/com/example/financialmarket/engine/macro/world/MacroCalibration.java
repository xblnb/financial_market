package com.example.financialmarket.engine.macro.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime-tunable scale factors for the world-macro transmission. Defaults are deliberately
 * conservative; a modpack can override them in {@code config/financialmarket/economy/calibration.json}.
 */
public final class MacroCalibration {

    public static double sectorFlowOutput = 10_000.0;
    public static double sectorFlowMargin = 50_000.0;
    public static double sectorFlowRisk = 1_000.0;
    public static double supplyScale = 0.5;
    public static double demandScale = 0.5;
    public static double maxSentimentNudge = 0.02;
    public static double dayImpactCap = 5.0;
    public static double impactCap = 1.0;

    private MacroCalibration() {
    }

    public static void load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject o = root.getAsJsonObject();
            sectorFlowOutput = dbl(o, "sectorFlowOutput", sectorFlowOutput);
            sectorFlowMargin = dbl(o, "sectorFlowMargin", sectorFlowMargin);
            sectorFlowRisk = dbl(o, "sectorFlowRisk", sectorFlowRisk);
            supplyScale = dbl(o, "supplyScale", supplyScale);
            demandScale = dbl(o, "demandScale", demandScale);
            maxSentimentNudge = dbl(o, "maxSentimentNudge", maxSentimentNudge);
            dayImpactCap = dbl(o, "dayImpactCap", dayImpactCap);
            impactCap = dbl(o, "impactCap", impactCap);
        } catch (RuntimeException | java.io.IOException ignored) {
            // Keep defaults on malformed config; the pack must still boot.
        }
    }

    private static double dbl(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def;
    }
}
