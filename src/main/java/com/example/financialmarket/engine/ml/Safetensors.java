package com.example.financialmarket.engine.ml;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Minimal, dependency-light reader for the <a href="https://github.com/huggingface/safetensors">safetensors</a>
 * container format.
 *
 * <p>Why this exists: the two shipped forecasters (Kronos and ATOM-class models) are published as
 * PyTorch {@code .safetensors} checkpoints. Shipping a native inference runtime (ONNX Runtime,
 * PyTorch, DJL) inside a Minecraft mod means 50-200 MB of platform specific native code and a
 * fragile class-loader story. The checkpoints are small enough (few million parameters) that a
 * pure-Java forward pass is both fast and completely portable, so the mod simply reads the
 * checkpoint bytes and feeds them to the Java kernels in {@link MlMath}.</p>
 *
 * <p>Supported dtypes: F32, F16 (via {@link Float#float16ToFloat}), BF16 and I64 (auxiliary
 * buffers such as bit masks, converted to float).</p>
 *
 * <p>Layout reminder: 8 bytes little-endian header length, then a UTF-8 JSON header mapping
 * tensor name -&gt; {dtype, shape, data_offsets}, then the raw data buffer. {@code data_offsets}
 * are relative to the end of the header.</p>
 */
public final class Safetensors {

    /** Metadata of one tensor inside the container. */
    public static final class Entry {
        public final String dtype;
        public final int[] shape;
        public final int begin;
        public final int end;

        Entry(String dtype, int[] shape, int begin, int end) {
            this.dtype = dtype;
            this.shape = shape;
            this.begin = begin;
            this.end = end;
        }

        public int elements() {
            int n = 1;
            for (int d : shape) {
                n *= d;
            }
            return n;
        }

        public long bytes() {
            return end - begin;
        }

        @Override
        public String toString() {
            return dtype + java.util.Arrays.toString(shape);
        }
    }

    private final byte[] file;
    private final int dataBase;
    private final Map<String, Entry> entries;
    private final Map<String, float[]> cache = new LinkedHashMap<>();

    private Safetensors(byte[] file, int dataBase, Map<String, Entry> entries) {
        this.file = file;
        this.dataBase = dataBase;
        this.entries = entries;
    }

    /** Parses a safetensors file that is already fully in memory. */
    public static Safetensors parse(byte[] file) {
        if (file.length < 9) {
            throw new IllegalArgumentException("safetensors: file too short (" + file.length + " bytes)");
        }
        long headerLen = 0;
        for (int i = 7; i >= 0; i--) {
            headerLen = (headerLen << 8) | (file[i] & 0xFFL);
        }
        if (headerLen <= 0 || headerLen > Integer.MAX_VALUE - 8 || 8 + headerLen > file.length) {
            throw new IllegalArgumentException("safetensors: invalid header length " + headerLen);
        }
        String json = new String(file, 8, (int) headerLen, StandardCharsets.UTF_8);
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("safetensors: header is not valid JSON", e);
        }
        int base = 8 + (int) headerLen;
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if ("__metadata__".equals(e.getKey()) || !e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject t = e.getValue().getAsJsonObject();
            String dtype = t.get("dtype").getAsString();
            var shapeJson = t.getAsJsonArray("shape");
            int[] shape = new int[shapeJson.size()];
            for (int i = 0; i < shape.length; i++) {
                shape[i] = shapeJson.get(i).getAsInt();
            }
            var offs = t.getAsJsonArray("data_offsets");
            int begin = offs.get(0).getAsInt();
            int end = offs.get(1).getAsInt();
            if (begin < 0 || end < begin || base + end > file.length) {
                throw new IllegalArgumentException("safetensors: bad offsets for tensor " + e.getKey());
            }
            map.put(e.getKey(), new Entry(dtype, shape, begin, end));
        }
        return new Safetensors(file, base, map);
    }

    public static Safetensors load(Path path) throws IOException {
        return parse(Files.readAllBytes(path));
    }

    /**
     * Loads a checkpoint from the mod classpath, e.g.
     * {@code assets/financialmarket/models/kronos/kronos-mini.safetensors}.
     */
    public static Safetensors loadResource(String resourcePath) throws IOException {
        String normalized = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        try (InputStream in = Safetensors.class.getResourceAsStream(normalized)) {
            if (in == null) {
                throw new IOException("model resource not found on classpath: " + normalized);
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                throw new IOException("model resource is empty: " + normalized);
            }
            return parse(bytes);
        }
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    public boolean has(String name) {
        return entries.containsKey(name);
    }

    public Entry entry(String name) {
        Entry e = entries.get(name);
        if (e == null) {
            throw new IllegalArgumentException("safetensors: missing tensor '" + name + "'");
        }
        return e;
    }

    public int[] shape(String name) {
        return entry(name).shape.clone();
    }

    public int size() {
        return entries.size();
    }

    /** Returns the tensor as a row-major float array, converting F16/BF16/I64 on the fly. */
    public float[] tensor(String name) {
        float[] cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        Entry e = entry(name);
        int n = e.elements();
        float[] out = new float[n];
        int pos = dataBase + e.begin;
        switch (e.dtype) {
            case "F32" -> {
                for (int i = 0; i < n; i++, pos += 4) {
                    out[i] = Float.intBitsToFloat(readInt(pos));
                }
            }
            case "F16" -> {
                for (int i = 0; i < n; i++, pos += 2) {
                    short bits = (short) ((file[pos] & 0xFF) | ((file[pos + 1] & 0xFF) << 8));
                    out[i] = Float.float16ToFloat(bits);
                }
            }
            case "BF16" -> {
                for (int i = 0; i < n; i++, pos += 2) {
                    int bits = (file[pos] & 0xFF) | ((file[pos + 1] & 0xFF) << 8);
                    out[i] = Float.intBitsToFloat(bits << 16);
                }
            }
            case "I64" -> {
                for (int i = 0; i < n; i++, pos += 8) {
                    long v = 0;
                    for (int b = 7; b >= 0; b--) {
                        v = (v << 8) | (file[pos + b] & 0xFFL);
                    }
                    out[i] = (float) v;
                }
            }
            default -> throw new IllegalArgumentException(
                    "safetensors: unsupported dtype '" + e.dtype + "' for tensor " + name);
        }
        cache.put(name, out);
        return out;
    }

    private int readInt(int pos) {
        return (file[pos] & 0xFF)
                | ((file[pos + 1] & 0xFF) << 8)
                | ((file[pos + 2] & 0xFF) << 16)
                | ((file[pos + 3] & 0xFF) << 24);
    }
}
