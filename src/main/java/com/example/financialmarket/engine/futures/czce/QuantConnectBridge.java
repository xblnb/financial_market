package com.example.financialmarket.engine.futures.czce;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * QuantConnect/LEAN bridge for CZCE target positions.
 *
 * <p>LEAN is a .NET engine and is not embedded in the mod. A LEAN algorithm (or any external
 * process) publishes target lots to a small JSON file; this bridge polls it and the CZCE market
 * applies the deltas as market orders for the dedicated {@code quantconnect} account.</p>
 *
 * <pre>
 * config/financialmarket/quantconnect/czce_targets.json
 * {"account":"quantconnect","targets":[{"symbol":"SR2605","lots":2},
 *                                      {"symbol":"CF2605","lots":-1}]}
 * </pre>
 */
public final class QuantConnectBridge {

    public record Target(String symbol, int lots) {
    }

    public record Snapshot(long version, List<Target> targets) {
        public static Snapshot empty() {
            return new Snapshot(-1L, List.of());
        }
    }

    private final Path file;
    private long lastModified = Long.MIN_VALUE;
    private Snapshot current = Snapshot.empty();

    public QuantConnectBridge() {
        this(Path.of(System.getProperty("financialmarket.quantconnect.czce.targets",
                "config/financialmarket/quantconnect/czce_targets.json")));
    }

    public QuantConnectBridge(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Reads the target file only when it changed; malformed files keep the previous snapshot. */
    public Snapshot poll() {
        try {
            if (!Files.isRegularFile(file)) {
                return current;
            }
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (mtime == lastModified) {
                return current;
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return current;
            }
            JsonObject o = root.getAsJsonObject();
            JsonArray array = o.has("targets") ? o.getAsJsonArray("targets") : new JsonArray();
            List<Target> targets = new ArrayList<>();
            for (JsonElement el : array) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject t = el.getAsJsonObject();
                if (!t.has("symbol")) {
                    continue;
                }
                String symbol = t.get("symbol").getAsString();
                int lots = t.has("lots") ? t.get("lots").getAsInt() : 0;
                targets.add(new Target(symbol, lots));
            }
            lastModified = mtime;
            current = new Snapshot(mtime, List.copyOf(targets));
        } catch (IOException | RuntimeException ignored) {
            // Keep last good snapshot; a broken external file must not crash the server.
        }
        return current;
    }

    public Snapshot current() {
        return current;
    }
}
