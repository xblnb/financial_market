package com.example.financialmarket.storage;

import com.example.financialmarket.engine.OHLCData;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import com.example.financialmarket.storage.db.H2DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerLevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * K线 / 元数据持久化 —— 从 Gson JSON 文件迁移到 H2 表（对应 H2_MIGRATION.md §6.2 kline 表、§6.3 asset 表）。
 *
 * <p>对外接口签名保持不变：{@link #saveKlines}/{@link #loadKlines} 读写 {@code kline} 表，
 * {@link #saveMetadata}/{@link #loadMetadata} 读写 {@code asset} 表的 last_price/daily_open/daily_close 列。
 * 所有 SQL 走 {@link H2DatabaseManager#getConnection()}（服务器线程单连接）。
 *
 * <p>一次性导入：{@link #importLegacyIfPresent(ServerLevel)} 在 H2 {@code kline} 表为空且存在存量
 * {@code klines.json} 时导入 K线；在 H2 {@code asset} 表为空且存在存量 {@code metadata.json} 时导入元数据。
 * 导入完成后保留存量 JSON 文件（不删除），由调用方按需处理；幂等（表非空即跳过）。
 */
public class KlineFileStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger("FinancialMarket/KlineStorage");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DATA_DIR = "financial_market";
    private static final String KLINES_FILE = "klines.json";
    private static final String METADATA_FILE = "metadata.json";

    // 迁移后 K线/元数据均持久化到 H2；DATA_DIR/KLINES_FILE/METADATA_FILE 仅用于一次性导入存量 JSON 时定位文件。

    // 存储结构：Map<assetId, Map<KlineType, List<OHLCData>>>
    private static final Type KLINE_MAP_TYPE = new TypeToken<Map<String, Map<String, List<OHLCData>>>>(){}.getType();

    private KlineFileStorage() {
    }

    /** 将 K线缓存写入 H2 {@code kline} 表（幂等，MERGE 逐行 upsert）。 */
    public static void saveKlines(ServerLevel level,
                                  Map<String, Map<PriceDiscoveryEngine.KlineType, LinkedList<OHLCData>>> klineCache) {
        Connection c = H2DatabaseManager.getConnection();
        if (c == null) {
            LOGGER.warn("H2 未打开，跳过 K线保存");
            return;
        }
        String sql = "MERGE INTO kline (asset_id, kline_type, bar_index, open, high, low, close, volume) "
                + "KEY(asset_id, kline_type, bar_index) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (var entry : klineCache.entrySet()) {
                String assetId = entry.getKey();
                for (var typeEntry : entry.getValue().entrySet()) {
                    String typeName = typeEntry.getKey().name();
                    int idx = 0;
                    for (OHLCData k : typeEntry.getValue()) {
                        ps.setString(1, assetId);
                        ps.setString(2, typeName);
                        ps.setInt(3, idx++);
                        ps.setDouble(4, k.getOpen());
                        ps.setDouble(5, k.getHigh());
                        ps.setDouble(6, k.getLow());
                        ps.setDouble(7, k.getClose());
                        ps.setLong(8, k.getVolume());
                        ps.addBatch();
                    }
                }
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOGGER.error("保存 K线到 H2 失败: {}", e.getMessage(), e);
        }
    }

    /** 从 H2 {@code kline} 表读取全部 K线，还原为 Map&lt;assetId, Map&lt;KlineType, LinkedList&gt;&gt;。 */
    public static Map<String, Map<PriceDiscoveryEngine.KlineType, LinkedList<OHLCData>>> loadKlines(ServerLevel level) {
        Map<String, Map<PriceDiscoveryEngine.KlineType, LinkedList<OHLCData>>> result = new HashMap<>();
        Connection c = H2DatabaseManager.getConnection();
        if (c == null) {
            LOGGER.warn("H2 未打开，无法读取 K线");
            return result;
        }
        // 若 H2 为空且存在存量 JSON，则一次性导入
        importLegacyIfPresent(level);

        String sql = "SELECT asset_id, kline_type, bar_index, open, high, low, close, volume "
                + "FROM kline ORDER BY asset_id, kline_type, bar_index";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String assetId = rs.getString("asset_id");
                PriceDiscoveryEngine.KlineType type =
                        PriceDiscoveryEngine.KlineType.valueOf(rs.getString("kline_type"));
                Map<PriceDiscoveryEngine.KlineType, LinkedList<OHLCData>> assetMap =
                        result.computeIfAbsent(assetId, k -> new ConcurrentHashMap<>());
                LinkedList<OHLCData> list = assetMap.computeIfAbsent(type, k -> new LinkedList<>());
                list.add(new OHLCData(
                        rs.getDouble("open"),
                        rs.getDouble("high"),
                        rs.getDouble("low"),
                        rs.getDouble("close"),
                        rs.getLong("volume")));
            }
        } catch (SQLException e) {
            LOGGER.error("读取 K线失败: {}", e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            LOGGER.error("KlineType 解析失败: {}", e.getMessage(), e);
        }
        return result;
    }

    /** 将元数据（lastPrices/dailyOpen/dailyClose）写入 H2 {@code asset} 表对应列。 */
    public static void saveMetadata(ServerLevel level, Map<String, Double> lastPrices,
                                    Map<String, Double> dailyOpen, Map<String, Double> dailyClose) {
        Connection c = H2DatabaseManager.getConnection();
        if (c == null) {
            LOGGER.warn("H2 未打开，跳过元数据保存");
            return;
        }
        String sql = "MERGE INTO asset (asset_id, last_price, daily_open, daily_close) "
                + "KEY(asset_id) VALUES (?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            Map<String, Double> mergedKeys = new HashMap<>();
            if (lastPrices != null) mergedKeys.putAll(lastPrices);
            if (dailyOpen != null) mergedKeys.putAll(dailyOpen);
            if (dailyClose != null) mergedKeys.putAll(dailyClose);
            for (String assetId : mergedKeys.keySet()) {
                ps.setString(1, assetId);
                ps.setDouble(2, lastPrices != null && lastPrices.containsKey(assetId) ? lastPrices.get(assetId) : 0d);
                ps.setDouble(3, dailyOpen != null && dailyOpen.containsKey(assetId) ? dailyOpen.get(assetId) : 0d);
                ps.setDouble(4, dailyClose != null && dailyClose.containsKey(assetId) ? dailyClose.get(assetId) : 0d);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOGGER.error("保存元数据到 H2 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从 H2 {@code asset} 表读取元数据，还原为与原 JSON 结构一致的 Map
     * （键 lastPrices/dailyOpen/dailyClose，值为 assetId→Double）。
     */
    public static Map<String, Object> loadMetadata(ServerLevel level) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Double> lastPrices = new HashMap<>();
        Map<String, Double> dailyOpen = new HashMap<>();
        Map<String, Double> dailyClose = new HashMap<>();

        Connection c = H2DatabaseManager.getConnection();
        if (c == null) {
            LOGGER.warn("H2 未打开，无法读取元数据");
            return result;
        }
        importLegacyIfPresent(level);

        String sql = "SELECT asset_id, last_price, daily_open, daily_close FROM asset";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String assetId = rs.getString("asset_id");
                lastPrices.put(assetId, rs.getDouble("last_price"));
                dailyOpen.put(assetId, rs.getDouble("daily_open"));
                dailyClose.put(assetId, rs.getDouble("daily_close"));
            }
        } catch (SQLException e) {
            LOGGER.error("读取元数据失败: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
        result.put("lastPrices", lastPrices);
        result.put("dailyOpen", dailyOpen);
        result.put("dailyClose", dailyClose);
        return result;
    }

    /**
     * 一次性导入：若 H2 表为空且存在存量 JSON 文件，则读入并写入 H2。
     * 幂等 —— 目标表已有数据即跳过；导入后保留存量 JSON 文件。
     */
    public static void importLegacyIfPresent(ServerLevel level) {
        Connection c = H2DatabaseManager.getConnection();
        if (c == null) {
            return;
        }
        try {
            Path dataDir = getDataDir(level);

            // K线
            if (Files.exists(dataDir.resolve(KLINES_FILE)) && !tableHasRows(c, "kline")) {
                LOGGER.info("检测到存量 {}，导入 H2 kline 表", KLINES_FILE);
                Map<String, Map<String, List<OHLCData>>> legacy =
                        GSON.fromJson(Files.readString(dataDir.resolve(KLINES_FILE), StandardCharsets.UTF_8),
                                KLINE_MAP_TYPE);
                if (legacy != null) {
                    String sql = "MERGE INTO kline (asset_id, kline_type, bar_index, open, high, low, close, volume) "
                            + "KEY(asset_id, kline_type, bar_index) VALUES (?,?,?,?,?,?,?,?)";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        for (var entry : legacy.entrySet()) {
                            int idx = 0;
                            for (var typeEntry : entry.getValue().entrySet()) {
                                String typeName = typeEntry.getKey();
                                for (OHLCData k : typeEntry.getValue()) {
                                    ps.setString(1, entry.getKey());
                                    ps.setString(2, typeName);
                                    ps.setInt(3, idx++);
                                    ps.setDouble(4, k.getOpen());
                                    ps.setDouble(5, k.getHigh());
                                    ps.setDouble(6, k.getLow());
                                    ps.setDouble(7, k.getClose());
                                    ps.setLong(8, k.getVolume());
                                    ps.addBatch();
                                }
                            }
                        }
                        ps.executeBatch();
                    }
                }
            }

            // 元数据
            if (Files.exists(dataDir.resolve(METADATA_FILE)) && !tableHasRows(c, "asset")) {
                LOGGER.info("检测到存量 {}，导入 H2 asset 表", METADATA_FILE);
                Map<String, Object> legacy = GSON.fromJson(
                        Files.readString(dataDir.resolve(METADATA_FILE), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>(){}.getType());
                if (legacy != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Double> lastPrices = (Map<String, Double>) legacy.get("lastPrices");
                    @SuppressWarnings("unchecked")
                    Map<String, Double> dailyOpen = (Map<String, Double>) legacy.get("dailyOpen");
                    @SuppressWarnings("unchecked")
                    Map<String, Double> dailyClose = (Map<String, Double>) legacy.get("dailyClose");
                    saveMetadata(level, lastPrices, dailyOpen, dailyClose);
                }
            }
        } catch (IOException | SQLException e) {
            LOGGER.error("导入存量 K线/元数据失败: {}", e.getMessage(), e);
        }
    }

    private static boolean tableHasRows(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() && rs.getLong(1) > 0;
        }
    }

    private static Path getDataDir(ServerLevel level) {
        return level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve(DATA_DIR);
    }
}