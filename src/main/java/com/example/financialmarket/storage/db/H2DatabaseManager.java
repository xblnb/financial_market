package com.example.financialmarket.storage.db;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * H2 嵌入式数据库门面（对应 H2_MIGRATION.md §4.1 / §4.2 / §5）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>单例持有 <b>一条 Connection</b>，只在 Minecraft <b>服务器主线程</b>（ServerStarted→ServerStopping）
 *       之间访问，避免并发竞态。</li>
 *   <li>数据库文件位于 <code>&lt;world&gt;/financial_market/db/financialmarket.mv.db</code>，
 *       复用 {@code KlineFileStorage} 的 world 目录逻辑（{@link LevelResource#ROOT}）。</li>
 *   <li>{@link #open(MinecraftServer)} 建目录、建表（幂等 {@code CREATE TABLE IF NOT EXISTS}）、
 *       写入 schema_version；{@link #close()} 提交未完成事务并关闭连接。</li>
 *   <li>所有表结构与 H2_MIGRATION.md §6 一致（schema_meta / kline / asset / trade_point /
 *       ai_company / company / index_state / index_constituent / engine_kv / quant_script）。</li>
 * </ul>
 *
 * <p>注意：HTTP 工作线程（LocalHttpServer）不得复用此连接；其 DB 需求应通过服务器线程代理或
 * 独立只读连接解决（见 H2_MIGRATION.md §5.3）。
 */
public final class H2DatabaseManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("FinancialMarket/H2");

    private static final String DB_DIR = "financial_market";
    private static final String DB_SUBDIR = "db";

    /** H2 JDBC 连接；仅服务器线程使用。 */
    private static Connection conn;

    private H2DatabaseManager() {
    }

    /** 当前是否已打开（供事件/其它子系统判断）。 */
    public static boolean isOpen() {
        try {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 在 ServerStarted 调用：建目录、打开连接、建表（幂等）、写 schema 元数据。
     * 失败仅告警不抛异常，保证经济引擎内存可继续运行（H2_MIGRATION.md §8.6）。
     */
    public static synchronized void open(MinecraftServer server) {
        if (isOpen()) {
            LOGGER.warn("H2DatabaseManager 已打开，跳过重复 open");
            return;
        }
        try {
            Path dbDir = getDbDir(server);
            Files.createDirectories(dbDir);
            Path dbPath = dbDir.resolve("financialmarket");
            String jdbcUrl = "jdbc:h2:" + dbPath.toAbsolutePath()
                    + ";DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"
                    + ";DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=true";
            // 显式注册 H2 JDBC 驱动（否则运行期 DriverManager 报 "No suitable driver"）
            Class.forName("org.h2.Driver");
            conn = DriverManager.getConnection(jdbcUrl, "sa", "");
            conn.setAutoCommit(true);
            createTables(conn);
            ensureSchemaMeta(conn);
            // 首次启动导入存量 quant_strategies/*.js（幂等：已存在则跳过，见 H2_MIGRATION.md §7.2.3）
            importQuantScriptsFromDisk();
            LOGGER.info("✅ H2 数据库已打开: {}", dbPath.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("❌ H2 数据库打开/建表失败（内存可继续运行）: {}", e.getMessage(), e);
            closeQuietly();
        }
    }

    /**
     * 在 ServerStopping 调用：提交未完成事务并关闭连接。
     * 需在保存引擎数据（MarketAttachment.saveEngine）之后调用，或由迁移后的 DAO 落库。
     */
    public static synchronized void close() {
        if (conn == null) {
            return;
        }
        try {
            if (!conn.isClosed() && !conn.getAutoCommit()) {
                conn.commit();
            }
        } catch (SQLException e) {
            LOGGER.error("H2 提交未完成事务失败: {}", e.getMessage(), e);
        } finally {
            closeQuietly();
        }
        LOGGER.info("✅ H2 数据库已关闭");
    }

    /**
     * 当前活动的 JDBC 连接。仅服务器线程可安全使用；
     * 返回 {@code null} 表示数据库尚未打开或已关闭。
     */
    public static Connection getConnection() {
        return conn;
    }

    /** 世界目录下 H2 数据库父目录：{@code <world>/financial_market/db}。 */
    public static Path getDbDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DB_DIR).resolve(DB_SUBDIR);
    }

    /** 幂等建表（与 H2_MIGRATION.md §6 一致）。 */
    private static void createTables(Connection c) throws SQLException {
        String[] ddl = {
                // 6.1 schema_meta
                "CREATE TABLE IF NOT EXISTS schema_meta ("
                        + "meta_key VARCHAR(64) PRIMARY KEY,"
                        + "meta_value VARCHAR(255) NOT NULL)",
                // 6.2 kline
                "CREATE TABLE IF NOT EXISTS kline ("
                        + "asset_id VARCHAR(64) NOT NULL,"
                        + "kline_type VARCHAR(16) NOT NULL,"
                        + "bar_index INT NOT NULL,"
                        + "open DOUBLE NOT NULL,"
                        + "high DOUBLE NOT NULL,"
                        + "low DOUBLE NOT NULL,"
                        + "close DOUBLE NOT NULL,"
                        + "volume BIGINT NOT NULL,"
                        + "PRIMARY KEY (asset_id, kline_type, bar_index))",
                "CREATE INDEX IF NOT EXISTS idx_kline_asset ON kline (asset_id, kline_type)",
                // 6.3 asset
                "CREATE TABLE IF NOT EXISTS asset ("
                        + "asset_id VARCHAR(64) PRIMARY KEY,"
                        + "last_price DOUBLE,"
                        + "daily_open DOUBLE,"
                        + "daily_close DOUBLE,"
                        + "gbm_price DOUBLE,"
                        + "gbm_mu DOUBLE,"
                        + "gbm_sigma DOUBLE,"
                        + "nav DOUBLE,"
                        + "industry VARCHAR(64))",
                // 6.4 trade_point
                "CREATE TABLE IF NOT EXISTS trade_point ("
                        + "asset_id VARCHAR(64) NOT NULL,"
                        + "seq INT NOT NULL,"
                        + "price DOUBLE NOT NULL,"
                        + "quantity INT NOT NULL,"
                        + "is_buy BOOLEAN NOT NULL,"
                        + "PRIMARY KEY (asset_id, seq))",
                "CREATE INDEX IF NOT EXISTS idx_trade_point_asset ON trade_point (asset_id)",
                // 6.5 ai_company
                "CREATE TABLE IF NOT EXISTS ai_company ("
                        + "id VARCHAR(64) PRIMARY KEY,"
                        + "name VARCHAR(128) NOT NULL,"
                        + "industry VARCHAR(64),"
                        + "net_asset DOUBLE NOT NULL,"
                        + "us_code VARCHAR(32),"
                        + "cn_code VARCHAR(32),"
                        + "ipo_price DOUBLE,"
                        + "eps DOUBLE,"
                        + "roe DOUBLE,"
                        + "fair_value DOUBLE,"
                        + "revenue DOUBLE,"
                        + "revenue_growth DOUBLE,"
                        + "net_profit DOUBLE,"
                        + "growth_stage DOUBLE,"
                        + "book_value_per_share DOUBLE,"
                        + "dividend_yield DOUBLE,"
                        + "debt_ratio DOUBLE,"
                        + "profitability_score DOUBLE,"
                        + "financial_health_score DOUBLE,"
                        + "total_shares INT)",
                // 6.6 company
                "CREATE TABLE IF NOT EXISTS company ("
                        + "stock_id VARCHAR(64) PRIMARY KEY,"
                        + "owner_uuid VARCHAR(36) NOT NULL,"
                        + "name VARCHAR(128) NOT NULL,"
                        + "level INT,"
                        + "status VARCHAR(32),"
                        + "net_asset DOUBLE,"
                        + "recent_profit DOUBLE,"
                        + "total_shares BIGINT,"
                        + "ipo_price DOUBLE,"
                        + "founded_time BIGINT,"
                        + "listed_time BIGINT)",
                "CREATE INDEX IF NOT EXISTS idx_company_owner ON company (owner_uuid)",
                // 6.7 index_state / index_constituent
                "CREATE TABLE IF NOT EXISTS index_state ("
                        + "id VARCHAR(32) PRIMARY KEY,"
                        + "idx_value DOUBLE NOT NULL,"  // 避免 H2 保留字 VALUE
                        + "last_rebalance BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS index_constituent ("
                        + "index_id VARCHAR(32) NOT NULL,"
                        + "asset_id VARCHAR(64) NOT NULL,"
                        + "seq INT NOT NULL,"
                        + "PRIMARY KEY (index_id, asset_id))",
                // 6.8 engine_kv
                "CREATE TABLE IF NOT EXISTS engine_kv ("
                        + "k VARCHAR(64) PRIMARY KEY,"
                        + "v CLOB NOT NULL,"
                        + "dtype VARCHAR(32))",
                // 6.9 quant_script
                "CREATE TABLE IF NOT EXISTS quant_script ("
                        + "script_id VARCHAR(128) PRIMARY KEY,"
                        + "name VARCHAR(128),"
                        + "code CLOB NOT NULL,"
                        + "language VARCHAR(16) DEFAULT 'javascript',"
                        + "created_at TIMESTAMP,"
                        + "updated_at TIMESTAMP)",
                // 6.10 CZCE futures/options relational mirror
                "CREATE TABLE IF NOT EXISTS czce_contract ("
                        + "contract_code VARCHAR(32) PRIMARY KEY,"
                        + "product VARCHAR(16) NOT NULL,"
                        + "delivery_month BIGINT,"
                        + "last_trading_tick BIGINT,"
                        + "prev_settle DOUBLE,"
                        + "last_price DOUBLE,"
                        + "limit_up DOUBLE,"
                        + "limit_down DOUBLE,"
                        + "margin_ratio DOUBLE,"
                        + "status VARCHAR(16),"
                        + "consecutive_limit_days INT,"
                        + "open_interest BIGINT,"
                        + "day_volume BIGINT,"
                        + "day_notional DOUBLE,"
                        + "limit_locked BOOLEAN,"
                        + "board_days_left INT,"
                        + "board_decrement_day BIGINT)",
                "CREATE TABLE IF NOT EXISTS czce_account ("
                        + "account_id VARCHAR(36) PRIMARY KEY,"
                        + "cash DOUBLE NOT NULL,"
                        + "margin DOUBLE NOT NULL,"
                        + "pending_force BOOLEAN)",
                "CREATE TABLE IF NOT EXISTS czce_position ("
                        + "account_id VARCHAR(36) NOT NULL,"
                        + "contract_code VARCHAR(32) NOT NULL,"
                        + "long_lots INT NOT NULL,"
                        + "short_lots INT NOT NULL,"
                        + "avg_long DOUBLE,"
                        + "avg_short DOUBLE,"
                        + "PRIMARY KEY (account_id, contract_code))",
                "CREATE TABLE IF NOT EXISTS czce_sabr ("
                        + "product VARCHAR(16) PRIMARY KEY,"
                        + "alpha DOUBLE NOT NULL,"
                        + "beta DOUBLE NOT NULL,"
                        + "rho DOUBLE NOT NULL,"
                        + "nu DOUBLE NOT NULL,"
                        + "base_alpha DOUBLE,"
                        + "base_rho DOUBLE,"
                        + "base_nu DOUBLE,"
                        + "last_day BIGINT)",
        };
        try (Statement st = c.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
            // P0-2 G-04 前向兼容：老库 ai_company 表缺基本面列时幂等补齐
            String[] aiCompanyColumns = {
                    "eps DOUBLE",
                    "roe DOUBLE",
                    "fair_value DOUBLE",
                    "revenue DOUBLE",
                    "revenue_growth DOUBLE",
                    "net_profit DOUBLE",
                    "growth_stage DOUBLE",
                    "book_value_per_share DOUBLE",
                    "dividend_yield DOUBLE",
                    "debt_ratio DOUBLE",
                    "profitability_score DOUBLE",
                    "financial_health_score DOUBLE",
                    "total_shares INT",
            };
            for (String col : aiCompanyColumns) {
                st.execute("ALTER TABLE ai_company ADD COLUMN IF NOT EXISTS " + col);
            }
            // COMPANY_SPEC §2.2/A-7：玩家公司新字段列前向兼容（幂等补齐，blob 仍为加载权威）
            String[] companyColumns = {
                    "retained_earnings DOUBLE",
                    "last_report_time BIGINT",
                    "last_dividend_time BIGINT",
                    "float_shares BIGINT",
                    "ipo_state VARCHAR(32)",
                    "subscription_raised DOUBLE",
                    "dividend_paid_total DOUBLE",
                    "low_price_days INT",
                    "inventory_value DOUBLE",
                    "productive_asset_value DOUBLE",
                    "appraised_asset_value DOUBLE",
                    "replacement_cost DOUBLE",
                    "asset_depreciation DOUBLE",
                    "asset_utilization DOUBLE",
                    "asset_risk DOUBLE",
                    "asset_quality DOUBLE",
                    "machine_count INT",
                    "multiblock_count INT",
                    "production_output_value DOUBLE",
                    "production_input_value DOUBLE",
                    "production_throughput DOUBLE",
                    "production_utilization DOUBLE",
                    "inventory_turnover DOUBLE",
                    "operating_profit DOUBLE",
                    "energy_consumed DOUBLE",
                    "ak_capital DOUBLE",
                    "ak_output DOUBLE",
                    "ak_investment DOUBLE",
                    "ak_growth DOUBLE",
                    "ak_return DOUBLE",
                    "ak_tfp DOUBLE",
                    "ak_fair_value DOUBLE",
                    "cd_output DOUBLE",
                    "cd_labor DOUBLE",
                    "cd_tfp DOUBLE",
                    "cd_mpk DOUBLE",
                    "cd_mpl DOUBLE",
                    "cd_optimal_capital DOUBLE",
                    "cd_capital_gap DOUBLE",
            };
            for (String col : companyColumns) {
                st.execute("ALTER TABLE company ADD COLUMN IF NOT EXISTS " + col);
            }
        }
    }

    /** 写入 schema_version 元数据（幂等，MERGE 语义）。 */
    private static void ensureSchemaMeta(Connection c) throws SQLException {
        String migratedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        try (Statement st = c.createStatement()) {
            st.execute("MERGE INTO schema_meta (meta_key, meta_value) KEY(meta_key) VALUES ('schema_version', '1')");
            st.execute("MERGE INTO schema_meta (meta_key, meta_value) KEY(meta_key) VALUES ('data_version', '1')");
            st.execute("MERGE INTO schema_meta (meta_key, meta_value) KEY(meta_key) VALUES ('migrated_at', '"
                    + migratedAt + "')");
        }
    }

    /**
     * 保存（或覆盖）一个量化脚本到 {@code quant_script} 表。
     * {@code script_id} 即原文件名（如 {@code strategy1.js}），与 H2_MIGRATION.md §6.9 一致。
     *
     * @return {@code true} 表示写入成功；数据库未打开或写入异常返回 {@code false}
     */
    public static boolean saveQuantScript(String scriptId, String code) {
        Connection c = conn;
        if (c == null || scriptId == null || scriptId.isEmpty()) {
            return false;
        }
        String sql = "MERGE INTO quant_script (script_id, name, code, language, created_at, updated_at) "
                + "KEY(script_id) VALUES (?, ?, ?, 'javascript', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, scriptId);
            ps.setString(2, scriptId);
            ps.setString(3, code == null ? "" : code);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOGGER.error("❌ 保存量化脚本失败 [{}]: {}", scriptId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 按 {@code script_id}（原文件名）读取量化脚本源码。
     *
     * @return 脚本源码；未找到或数据库未打开返回 {@code null}
     */
    public static String loadQuantScript(String scriptId) {
        Connection c = conn;
        if (c == null || scriptId == null || scriptId.isEmpty()) {
            return null;
        }
        String sql = "SELECT code FROM quant_script WHERE script_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, scriptId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("code");
                }
            }
        } catch (SQLException e) {
            LOGGER.error("❌ 读取量化脚本失败 [{}]: {}", scriptId, e.getMessage(), e);
        }
        return null;
    }

    /** 列出 {@code quant_script} 表中所有已保存的脚本 id（原文件名）。 */
    public static List<String> listQuantScripts() {
        Connection c = conn;
        if (c == null) {
            return new ArrayList<>();
        }
        List<String> ids = new ArrayList<>();
        String sql = "SELECT script_id FROM quant_script ORDER BY script_id";
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getString("script_id"));
            }
        } catch (SQLException e) {
            LOGGER.error("❌ 列出量化脚本失败: {}", e.getMessage(), e);
        }
        return ids;
    }

    /**
     * 首次启动把工作目录下存量 {@code quant_strategies/*.js} 导入 {@code quant_script} 表
     * （H2_MIGRATION.md §7.2.3）。幂等：仅导入当前表中不存在的文件，目录不存在则直接跳过。
     */
    public static void importQuantScriptsFromDisk() {
        Path dir = Paths.get("quant_strategies");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            List<Path> jsFiles = stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".js"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
            for (Path f : jsFiles) {
                String scriptId = f.getFileName().toString();
                if (loadQuantScript(scriptId) != null) {
                    continue; // 已存在，跳过
                }
                String code = Files.readString(f);
                if (saveQuantScript(scriptId, code)) {
                    LOGGER.info("✅ 已导入存量量化脚本: {}", scriptId);
                }
            }
        } catch (Exception e) {
            LOGGER.error("❌ 导入存量量化脚本失败: {}", e.getMessage(), e);
        }
    }

    private static void closeQuietly() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                LOGGER.error("关闭 H2 连接失败: {}", e.getMessage());
            } finally {
                conn = null;
            }
        }
    }
}
