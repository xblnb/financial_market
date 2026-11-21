package com.example.financialmarket.engine.quant;

import java.util.UUID;

/**
 * 玩家量化策略注册模型（t2 落地，替代原 4 行空壳）。
 *
 * <p>记录玩家已启用的策略参数，供 {@link QuantAPI} 恢复 / 查询 / 持久化
 * （经 {@code MarketEngine.save()/load()} 的 "quant" 字段）。策略的实际执行
 * 委托给 {@link QuantAPI.TradingStrategy}（如 {@link QuantAPI.DoubleEMACrossStrategy}），
 * 或由 {@link QuantScriptEngine} 从 H2 脚本编译出的策略委托。
 *
 * <p>全部字段为确定性值，无随机 → 同种子可复现（R0-3）。
 */
public class Strategy {
    /** 策略种类：EMA 双均线（内建）或脚本 DSL 派生。 */
    public enum Kind { EMA_CROSS, SCRIPT }

    private final UUID playerId;
    private final String strategyName;
    private final int fastPeriod;
    private final int slowPeriod;
    private final boolean enabled;
    private final Kind kind;
    /** 脚本策略对应的 H2 script_id（kind == SCRIPT 时非空）。 */
    private final String scriptId;

    public Strategy(UUID playerId, String strategyName, int fastPeriod, int slowPeriod, boolean enabled) {
        this(playerId, strategyName, fastPeriod, slowPeriod, enabled, Kind.EMA_CROSS, null);
    }

    public Strategy(UUID playerId, String strategyName, int fastPeriod, int slowPeriod, boolean enabled,
                    Kind kind, String scriptId) {
        this.playerId = playerId;
        this.strategyName = strategyName == null ? "ema_cross" : strategyName;
        this.fastPeriod = Math.max(1, fastPeriod);
        this.slowPeriod = Math.max(2, slowPeriod);
        this.enabled = enabled;
        this.kind = kind == null ? Kind.EMA_CROSS : kind;
        this.scriptId = scriptId;
    }

    public UUID getPlayerId() { return playerId; }
    public String getStrategyName() { return strategyName; }
    public int getFastPeriod() { return fastPeriod; }
    public int getSlowPeriod() { return slowPeriod; }
    public boolean isEnabled() { return enabled; }
    public Kind getKind() { return kind; }
    public String getScriptId() { return scriptId; }
}
