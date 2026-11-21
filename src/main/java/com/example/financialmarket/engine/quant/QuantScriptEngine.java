package com.example.financialmarket.engine.quant;

import com.example.financialmarket.engine.MarketState;
import com.example.financialmarket.engine.Order;
import com.example.financialmarket.storage.db.H2DatabaseManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 量化脚本引擎（t2 落地，替代原 4 行空壳）。
 *
 * <p>职责：把玩家保存在 H2 {@code quant_script} 表的脚本源码，编译为可执行的
 * {@link QuantAPI.TradingStrategy} 委托，供 {@code MarketEngine.tick} 评估产生订单。
 *
 * <p>由于服务端禁用外部 JS 引擎（Nashorn 于 Java 15+ 移除；引入 Rhino/GraalJS 属
 * 重量级外部依赖，与「离线、无新增大依赖」约束冲突），本引擎实现为<strong>轻量内建
 * DSL</strong>，支持预置指令集：
 * <pre>
 *   EMA_CROSS fast slow        → 双均线金叉/死叉
 *   GRID      step lots        → 网格交易（价格跌破参考价-step 买 lots 手，涨超 +step 卖）
 *   MARTINGALE baseStep doubleOnLoss → 马丁格尔（价格下跌加倍买入摊低成本）
 * </pre>
 * 解析失败 / 空脚本 / 未知指令 → 该脚本 {@code disabled}，打 warning 日志，<strong>不得抛异常
 * 打断 tick</strong>（与 MarketEngine 防御风格一致，R0-3）。
 *
 * <p>确定性：DSL 指令本身为确定性逻辑（真实价格路径或 SeedableRandom 子流），不引入新的
 * {@code Math.random/new Random}（R0-3）。
 */
public class QuantScriptEngine {
    /** 已编译的脚本（scriptId → 执行委托）。 */
    private final Map<String, QuantAPI.TradingStrategy> compiled = new HashMap<>();
    /** 脚本启用标记。 */
    private final Map<String, Boolean> enabled = new HashMap<>();
    private final long seed;

    public QuantScriptEngine() {
        this(0L);
    }

    public QuantScriptEngine(long seed) {
        this.seed = seed;
    }

    /** 从 H2 读取脚本源码并编译（覆盖式）。成功返回 true。 */
    public boolean reload(String scriptId) {
        String source = H2DatabaseManager.loadQuantScript(scriptId);
        return compile(scriptId, source);
    }

    /** 批量重新加载（用于启动时把所有脚本编译进缓存）。 */
    public void reloadAll(List<String> scriptIds) {
        if (scriptIds == null) return;
        for (String id : scriptIds) reload(id);
    }

    /**
     * 编译一段脚本源码。解析失败 / 空 / 未知指令 → 移除并标记禁用，返回 false，
     * 不抛异常（不打断 tick）。
     */
    public boolean compile(String scriptId, String source) {
        if (scriptId == null || scriptId.isEmpty()) return false;
        if (source == null || source.trim().isEmpty()) {
            compiled.remove(scriptId);
            enabled.remove(scriptId);
            return false;
        }
        QuantAPI.TradingStrategy strat = parse(source);
        if (strat == null) {
            System.out.println("⚠️ 量化脚本 [" + scriptId + "] 解析失败/未知指令，已禁用（不阻断 tick）");
            compiled.remove(scriptId);
            enabled.remove(scriptId);
            return false;
        }
        compiled.put(scriptId, strat);
        enabled.put(scriptId, true);
        return true;
    }

    /** 解析 DSL 源码为执行委托；无法识别返回 null。 */
    private QuantAPI.TradingStrategy parse(String source) {
        String[] lines = source.split("\\R");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            String[] parts = line.split("\\s+");
            String cmd = parts[0].toUpperCase(Locale.ROOT);
            switch (cmd) {
                case "EMA_CROSS": {
                    if (parts.length < 3) return null;
                    try {
                        int fast = Integer.parseInt(parts[1]);
                        int slow = Integer.parseInt(parts[2]);
                        return new QuantAPI.DoubleEMACrossStrategy(fast, slow);
                    } catch (NumberFormatException e) { return null; }
                }
                case "GRID": {
                    if (parts.length < 3) return null;
                    try {
                        double step = Double.parseDouble(parts[1]);
                        int lots = Integer.parseInt(parts[2]);
                        return new QuantAPI.GridStrategy(step, lots, seed);
                    } catch (NumberFormatException e) { return null; }
                }
                case "MARTINGALE": {
                    if (parts.length < 2) return null;
                    try {
                        double baseStep = Double.parseDouble(parts[1]);
                        boolean doubleOnLoss = parts.length >= 3 && parts[2].equalsIgnoreCase("true");
                        return new QuantAPI.MartingaleStrategy(baseStep, doubleOnLoss, seed);
                    } catch (NumberFormatException e) { return null; }
                }
                default:
                    return null;
            }
        }
        return null;
    }

    /** 脚本是否已编译且启用。 */
    public boolean isEnabled(String scriptId) {
        return Boolean.TRUE.equals(enabled.get(scriptId));
    }

    /** 已编译并启用的脚本委托快照（供 UI / 验证）。 */
    public Map<String, QuantAPI.TradingStrategy> getCompiled() {
        Map<String, QuantAPI.TradingStrategy> out = new HashMap<>();
        for (Map.Entry<String, Boolean> e : enabled.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                QuantAPI.TradingStrategy s = compiled.get(e.getKey());
                if (s != null) out.put(e.getKey(), s);
            }
        }
        return out;
    }

    /** 评估脚本对指定资产产生的订单；未启用/未编译/异常时返回空（不抛异常）。 */
    public List<Order> evaluate(String scriptId, MarketState state, UUID playerId, String assetId) {
        if (!isEnabled(scriptId)) return Collections.emptyList();
        QuantAPI.TradingStrategy s = compiled.get(scriptId);
        if (s == null) return Collections.emptyList();
        try {
            List<Order> orders = s.generateOrders(state, assetId, playerId);
            return orders == null ? Collections.emptyList() : orders;
        } catch (RuntimeException e) {
            System.out.println("⚠️ 量化脚本 [" + scriptId + "] 执行异常（asset=" + assetId + "）: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 列出全部已编译脚本 id。 */
    public List<String> getScriptIds() {
        return new ArrayList<>(enabled.keySet());
    }

    // ------------------------------------------------------------------
    // 持久化（随 MarketEngine.save()/load() 的 "quantScripts" 字段）
    // 源码已在 H2 quant_script 表，这里只存启用标记；读档后按 id 重新编译。
    // ------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Map.Entry<String, Boolean> e : enabled.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                CompoundTag ct = new CompoundTag();
                ct.putString("id", e.getKey());
                list.add(ct);
            }
        }
        tag.put("enabled", list);
        return tag;
    }

    /** 恢复启用标记并从 H2 重新编译；旧档缺字段/缺键用默认重建，读档不失败（R0-5）。 */
    public void load(CompoundTag tag) {
        compiled.clear();
        enabled.clear();
        if (tag == null || tag.isEmpty()) return;
        ListTag list = tag.getList("enabled", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            String id = list.getCompound(i).getString("id");
            if (!id.isEmpty()) reload(id);
        }
    }
}
