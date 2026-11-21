package com.example.financialmarket.engine.ml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the Kronos checkpoint directory. Two release modes are supported:
 *
 * <ul>
 *   <li><b>Lean jar</b>: weights are external; put them under one of the paths below.</li>
 *   <li><b>Full jar</b>: MIT fp16 weights are bundled as classpath resources; external paths still
 *       take precedence so operators can update models without rebuilding.</li>
 * </ul>
 *
 * <pre>
 *   -Dfinancialmarket.models.dir=/path/to/kronos
 *   config/financialmarket/models/kronos/
 *   .models/kronos/                         (dev working directory)
 * </pre>
 *
 * Expected files: {@code kronos-mini.safetensors}, {@code kronos-tokenizer.safetensors}.
 */
public final class KronosModelStore {

    public static final String MODEL_FILE = "kronos-mini.safetensors";
    public static final String TOKENIZER_FILE = "kronos-tokenizer.safetensors";
    public static final String DIR_PROPERTY = "financialmarket.models.dir";
    public static final String BUNDLED_MODEL_RESOURCE =
            "assets/financialmarket/models/kronos/kronos-mini.safetensors";
    public static final String BUNDLED_TOKENIZER_RESOURCE =
            "assets/financialmarket/models/kronos/kronos-tokenizer.safetensors";

    private KronosModelStore() {
    }

    public static Path directory() {
        List<Path> candidates = new ArrayList<>();
        String configured = System.getProperty(DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        String env = System.getenv("FINANCIALMARKET_MODELS_DIR");
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env));
        }
        candidates.add(Path.of("config", "financialmarket", "models", "kronos"));
        candidates.add(Path.of(".models", "kronos"));
        candidates.add(Path.of("..", ".models", "kronos"));
        candidates.add(Path.of("..", "config", "financialmarket", "models", "kronos"));
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            candidates.add(Path.of(userDir, ".models", "kronos"));
            candidates.add(Path.of(userDir, "config", "financialmarket", "models", "kronos"));
            candidates.add(Path.of(userDir, "..", ".models", "kronos"));
            candidates.add(Path.of(userDir, "..", "config", "financialmarket", "models", "kronos"));
        }
        for (Path p : candidates) {
            if (isComplete(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    public static boolean available() {
        return directory() != null || bundled();
    }

    /** True when a full-release jar bundled the fp16 checkpoints as classpath resources. */
    public static boolean bundled() {
        return KronosModelStore.class.getResource("/" + BUNDLED_MODEL_RESOURCE) != null
                && KronosModelStore.class.getResource("/" + BUNDLED_TOKENIZER_RESOURCE) != null;
    }

    public static Path modelFile() {
        Path dir = directory();
        return dir == null ? null : dir.resolve(MODEL_FILE);
    }

    public static Path tokenizerFile() {
        Path dir = directory();
        return dir == null ? null : dir.resolve(TOKENIZER_FILE);
    }

    private static boolean isComplete(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve(MODEL_FILE))
                && Files.isRegularFile(dir.resolve(TOKENIZER_FILE));
    }

    public static String describe() {
        Path dir = directory();
        if (dir != null) {
            return "Kronos models (external): " + dir;
        }
        if (bundled()) {
            return "Kronos models: bundled in mod jar";
        }
        return "Kronos models not found; set " + DIR_PROPERTY
                + " or place files in config/financialmarket/models/kronos/";
    }

    /** Diagnostic used by UI/command without throwing. */
    public static String status() {
        Path dir = directory();
        if (dir != null) {
            return String.format(Locale.ROOT, "external:%s", dir);
        }
        return bundled() ? "bundled" : "missing";
    }
}
