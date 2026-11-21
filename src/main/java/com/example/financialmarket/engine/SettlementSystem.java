package com.example.financialmarket.engine;

import com.example.financialmarket.attachment.PlayerEconomy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/**
 * T+1 清算系统：今日的交易在下一个交易日开始时进行资金和持仓的实际划转。
 *
 * <p>A 股 T+1 交收账本（AS 规格 §4）：{@link #frozen}（当日买入、当日冻结不可卖）与
 * {@link #available}（前一交易日及更早买入、已解冻可卖）。交易日边界由
 * {@link #settleTPlus1()} 把 frozen 全部结转进 available（{@code available += frozen; frozen = 0}）。
 * 全部为 HashMap O(1) 读写，确定性、无随机（A3-1）。
 */
public class SettlementSystem {
    // 待清算的买卖记录
    private final List<PendingSettlement> pending = new ArrayList<>();

    // ================= A 股 T+1 交收账本（AS-3） =================
    // 玩家UUID -> 资产 -> 数量
    private final Map<UUID, Map<String, Integer>> frozen = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> available = new HashMap<>();

    public void addTrade(UUID playerId, String assetId, int quantity, double price, boolean isBuy) {
        pending.add(new PendingSettlement(playerId, assetId, quantity, price, isBuy));
    }

    public List<PendingSettlement> getPending() {
        return Collections.unmodifiableList(pending);
    }

    /**
     * 执行清算（在交易日切换时调用）。
     */
    public void settle(Map<UUID, PlayerEconomy> economyMap) {
        for (PendingSettlement s : pending) {
            PlayerEconomy eco = economyMap.get(s.playerId);
            if (eco == null) continue;
            if (s.isBuy) {
                eco.addHolding(s.assetId, s.quantity, s.price);
                // 现金已在挂单时扣除（或冻结），此处仅划转股票
            } else {
                eco.removeHolding(s.assetId, s.quantity);
                // 卖出所得在成交时已增加，此处仅移除股票
            }
        }
        pending.clear();
    }

    // ================= A 股 T+1 交收账本操作（AS-3） =================

    /** 买入成交记账：当日买入计入 frozen（当日不可卖）。 */
    public void onBuyFill(UUID playerId, String assetId, int qty) {
        if (playerId == null || qty <= 0) return;
        frozen.computeIfAbsent(playerId, k -> new HashMap<>()).merge(assetId, qty, Integer::sum);
    }

    /** 卖出成交记账：从 available（可卖）扣减。 */
    public void onSellFill(UUID playerId, String assetId, int qty) {
        if (playerId == null || qty <= 0) return;
        Map<String, Integer> m = available.get(playerId);
        if (m == null) return;
        m.compute(assetId, (k, v) -> (v == null ? 0 : v - qty));
        Integer left = m.get(assetId);
        if (left != null && left <= 0) m.remove(assetId);
    }

    /** 卖出下单校验：可卖数量（available）>= 卖出量。 */
    public boolean canSell(UUID playerId, String assetId, int qty) {
        if (playerId == null) return false;
        Map<String, Integer> m = available.get(playerId);
        int avail = m == null ? 0 : m.getOrDefault(assetId, 0);
        return avail >= qty;
    }

    /**
     * 迁移（AS-3 低严重度修复）：把旧存档既有持仓初始化为 available（历史持仓视为已解冻可卖）。
     * 幂等：仅在账本无该玩家该资产记录时计入，避免重复叠加。确定性、无随机。
     */
    public void seedAvailable(UUID playerId, String assetId, int qty) {
        if (playerId == null || assetId == null || qty <= 0) return;
        available.computeIfAbsent(playerId, k -> new HashMap<>())
                .merge(assetId, qty, Integer::sum);
    }

    /** 当前可卖数量。 */
    public int getAvailable(UUID playerId, String assetId) {
        if (playerId == null) return 0;
        Map<String, Integer> m = available.get(playerId);
        return m == null ? 0 : m.getOrDefault(assetId, 0);
    }

    /** 当前当日买入冻结数量。 */
    public int getFrozen(UUID playerId, String assetId) {
        if (playerId == null) return 0;
        Map<String, Integer> m = frozen.get(playerId);
        return m == null ? 0 : m.getOrDefault(assetId, 0);
    }

    /** 账本是否完全为空（既无冻结也无可用）——用于判断是否为首次 A 股迁移/激活。 */
    public boolean isLedgerEmpty() {
        return frozen.isEmpty() && available.isEmpty();
    }

    /**
     * A-6/§7.1（连接审计修复）：资产退市时清除该股票在全部玩家账本中的冻结/可用残留
     * （COMPANY_SPEC §7.1「已冻结（Settlement.frozen）的该股一并清偿并清除，防残留冻结股」）。
     * 全表扫描（O(玩家×资产)），仅在退市/摘牌时调用；确定性、无随机。
     */
    public void clearAsset(String assetId) {
        if (assetId == null) return;
        for (Map<UUID, Map<String, Integer>> ledger : java.util.List.of(frozen, available)) {
            Iterator<Map.Entry<UUID, Map<String, Integer>>> it = ledger.entrySet().iterator();
            while (it.hasNext()) {
                Map<String, Integer> m = it.next().getValue();
                if (m == null) continue;
                m.remove(assetId);
                if (m.isEmpty()) it.remove();
            }
        }
    }

    /**
     * 交易日边界结转（T+1）：对所有玩家/资产 {@code available += frozen; frozen = 0}。
     * 每交易日一次全遍历（O(玩家×资产)），非 per-tick，确定性、无随机。
     */
    public void settleTPlus1() {
        for (Map.Entry<UUID, Map<String, Integer>> e : frozen.entrySet()) {
            UUID pid = e.getKey();
            Map<String, Integer> f = e.getValue();
            if (f == null || f.isEmpty()) continue;
            Map<String, Integer> a = available.computeIfAbsent(pid, k -> new HashMap<>());
            for (Map.Entry<String, Integer> fe : f.entrySet()) {
                a.merge(fe.getKey(), fe.getValue(), Integer::sum);
            }
        }
        frozen.clear();
    }

    // ================= 持久化（A3-8） =================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("frozen", saveLedger(frozen));
        tag.put("available", saveLedger(available));
        return tag;
    }

    private CompoundTag saveLedger(Map<UUID, Map<String, Integer>> ledger) {
        CompoundTag outer = new CompoundTag();
        for (Map.Entry<UUID, Map<String, Integer>> e : ledger.entrySet()) {
            CompoundTag inner = new CompoundTag();
            for (Map.Entry<String, Integer> a : e.getValue().entrySet()) {
                inner.putInt(a.getKey(), a.getValue());
            }
            outer.put(e.getKey().toString(), inner);
        }
        return outer;
    }

    /** 恢复账本；旧存档缺字段用 contains 兜底重建为空，读档不失败（A3-8）。 */
    public void load(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("frozen")) loadLedger(frozen, tag.getCompound("frozen"));
        if (tag.contains("available")) loadLedger(available, tag.getCompound("available"));
    }

    private void loadLedger(Map<UUID, Map<String, Integer>> ledger, CompoundTag tag) {
        ledger.clear();
        if (tag == null) return;
        for (String uuidStr : tag.getAllKeys()) {
            CompoundTag inner = tag.getCompound(uuidStr);
            Map<String, Integer> m = new HashMap<>();
            for (String asset : inner.getAllKeys()) m.put(asset, inner.getInt(asset));
            try {
                ledger.put(UUID.fromString(uuidStr), m);
            } catch (IllegalArgumentException ignored) {
                // 旧档脏 key：跳过，不失败
            }
        }
    }

    public record PendingSettlement(UUID playerId, String assetId, int quantity, double price, boolean isBuy) {}
}
