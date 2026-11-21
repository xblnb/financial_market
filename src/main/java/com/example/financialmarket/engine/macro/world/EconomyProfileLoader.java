package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.WorldMacroModel.ProcessProfile;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceKey;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * M4 pack overrides. Files live under {@code config/financialmarket/economy/}:
 * <pre>
 *   items.json      [{id, sector, category, tier, unit_value, elasticity, tags, substitutes}]
 *   processes.json  [{id, sector, tier, energy_per_run}]
 * </pre>
 * Overrides are applied after ProfileCompiler and replace auto-derived values.
 */
public final class EconomyProfileLoader {

    public record Stats(int items, int processes, String directory) {
    }

    private EconomyProfileLoader() {
    }

    public static Stats load(ProfileRegistry registry, RecipeGraph graph) {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("financialmarket").resolve("economy");
        int items = 0;
        int processes = 0;
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            return new Stats(0, 0, dir.toString());
        }
        MacroCalibration.load(dir.resolve("calibration.json"));
        Path itemsFile = dir.resolve("items.json");
        if (Files.isRegularFile(itemsFile)) {
            try {
                items = loadItems(registry, Files.readString(itemsFile, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException ignored) {
                items = 0;
            }
        }
        Path processesFile = dir.resolve("processes.json");
        if (Files.isRegularFile(processesFile) && graph != null) {
            try {
                processes = loadProcesses(graph, Files.readString(processesFile,
                        StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException ignored) {
                processes = 0;
            }
        }
        return new Stats(items, processes, dir.toString());
    }

    private static int loadItems(ProfileRegistry registry, String json) {
        JsonElement root = JsonParser.parseString(json);
        JsonArray array = root.isJsonArray() ? root.getAsJsonArray()
                : root.getAsJsonObject().getAsJsonArray("items");
        if (array == null) {
            return 0;
        }
        int n = 0;
        for (JsonElement el : array) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "id", null);
            if (id == null || id.isBlank()) {
                continue;
            }
            Set<String> tags = strings(o, "tags");
            Set<String> subs = strings(o, "substitutes");
            ResourceProfile p = new ResourceProfile(new ResourceKey(id, ProfileRegistry.kindOf(id)),
                    str(o, "sector", "other"), str(o, "category", "other"),
                    integer(o, "tier", 0), dbl(o, "unit_value", 1.0),
                    dbl(o, "elasticity", 0.5), tags, subs);
            registry.put(p, true);
            n++;
        }
        return n;
    }

    private static int loadProcesses(RecipeGraph graph, String json) {
        JsonElement root = JsonParser.parseString(json);
        JsonArray array = root.isJsonArray() ? root.getAsJsonArray()
                : root.getAsJsonObject().getAsJsonArray("processes");
        if (array == null) {
            return 0;
        }
        int n = 0;
        for (JsonElement el : array) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "id", null);
            if (id == null || id.isBlank()) {
                continue;
            }
            ProcessProfile old = graph.process(id);
            if (old == null) {
                continue;
            }
            ProcessProfile p = new ProcessProfile(id, old.kind(), str(o, "sector", old.sector()),
                    integer(o, "tier", old.tier()), old.inputs(), old.outputs(),
                    dbl(o, "energy_per_run", old.energyPerRun()), old.timePerRun(),
                    old.sourceMod());
            graph.replace(p);
            n++;
        }
        return n;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static int integer(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsInt() : def;
    }

    private static double dbl(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def;
    }

    private static Set<String> strings(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement x : e.getAsJsonArray()) {
            if (x.isJsonPrimitive()) {
                out.add(x.getAsString());
            }
        }
        return out;
    }
}
