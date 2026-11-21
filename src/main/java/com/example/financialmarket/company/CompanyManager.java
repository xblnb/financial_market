package com.example.financialmarket.company;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.naming.AStockCodePool;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.*;

public class CompanyManager {
    private final Map<UUID, CompanyData> companiesByOwner = new HashMap<>();
    private final Map<String, CompanyData> companiesByStockId = new HashMap<>();
    // t3：引擎引用（供创建公司持卡门控 + 创始人储蓄卡绑定读取，BT-5/BT-6）
    private MarketEngine engine;
    // N-1：共享 A 股代码池（玩家 stockId 与 AI cnCode 共用唯一命名空间，互不冲突）；MarketEngine 构造时注入
    private AStockCodePool stockCodePool;

    /** 注入引擎引用（MarketEngine 构造时调用）。 */
    public void setEngine(MarketEngine engine) { this.engine = engine; }

    /** 注入共享 A 股代码池（MarketEngine 构造时调用；未注入时 nextStockId 回落到旧 PCxxxx 逻辑）。 */
    public void setStockCodePool(AStockCodePool stockCodePool) { this.stockCodePool = stockCodePool; }

    /**
     * 创建公司（BT-5/BT-6）。
     * <p>创建前置条件为持卡：引擎已注入且 {@code banking.enabled} 时，无储蓄卡的玩家被拒绝
     * （返回 null 且不注册，打印中文提示）；有卡则把创始人储蓄卡号写入 {@link CompanyData#boundCard}。
     * <p>banking 关闭（{@code engine.hasBankCard} 恒 true）或引擎未注入时不设门控（回落基线，BT-10）。
     *
     * @param owner 创始人 UUID
     * @param name  公司名
     * @return 创建的公司；持卡门控拒绝时返回 null
     */
    public CompanyData createCompany(UUID owner, String name) {
        // t3 持卡门控（BT-5）：创建公司前置条件为持卡
        if (engine != null && !engine.hasBankCard(owner)) {
            System.out.println("⛔ 请先办理储蓄卡");
            return null;
        }
        CompanyData data = new CompanyData(owner, name, CompanyLevel.STARTUP);
        // A-4：股东账本为股权唯一权威 —— 成立即登记创始人全资持股，保证 Σ shareholders == totalShares
        if (owner != null) data.getShareholders().put(owner, data.getTotalShares());
        // t3 公司账户绑定创始人储蓄卡（BT-6）；banking 关闭/无卡时为空串
        if (engine != null) {
            com.example.financialmarket.engine.banking.BankCard card = engine.getBankCardService().getCardOf(owner);
            data.setBoundCard(card != null ? card.getCardNumber() : "");
        }
        companiesByOwner.put(owner, data);
        return data;
    }

    public CompanyData getByOwner(UUID owner) {
        return companiesByOwner.get(owner);
    }

    /** A-2/§2.3：是否已拥有公司。 */
    public boolean hasCompany(UUID owner) {
        return companiesByOwner.containsKey(owner);
    }

    /** §2.3：按公司名全表线性匹配（公司量小，O(n)，确定性）。无则 null。 */
    public CompanyData getByName(String name) {
        if (name == null) return null;
        for (CompanyData c : companiesByOwner.values()) {
            if (name.equals(c.getName())) return c;
        }
        return null;
    }

    /** §2.3：按状态取公司集合（供 DelistingMonitor 遍历 LISTED 等）。 */
    public List<CompanyData> getByStatus(CompanyStatus status) {
        List<CompanyData> out = new ArrayList<>();
        for (CompanyData c : companiesByOwner.values()) {
            if (c.getStatus() == status) out.add(c);
        }
        return out;
    }

    /** §2.3：股票代码是否已被占用。 */
    public boolean isStockIdTaken(String stockId) {
        return companiesByStockId.containsKey(stockId);
    }

    /**
     * N-1/§1.3.3：确定性生成唯一 A 股股票代码（无裸随机）：从共享 A 股代码池 {@code allocateCode()}，
     * 与 AI cnCode 及既有玩家 stockId 共用唯一命名空间，互不冲突。
     * <p>向后兼容：引擎未注入代码池时（如独立单测）回落到旧逻辑 {@code "PC"+递增序号}（历史存档 PCxxxx 不受影响）。
     */
    public String nextStockId() {
        if (stockCodePool != null) {
            return stockCodePool.allocateCode();
        }
        int n = companiesByStockId.size() + 1;
        String candidate;
        do {
            candidate = "PC" + String.format("%04d", n);
            n++;
        } while (companiesByStockId.containsKey(candidate));
        return candidate;
    }

    /**
     * A-2/§3.4：确定性升级门槛。目标等级存在、公司未退市、且净资产 >= 目标等级 minMarketCap 才允许升级。
     * 满足则升级成功并返回 true；否则返回 false（不修改状态）。
     */
    public boolean tryLevelUp(CompanyData company) {
        if (company == null) return false;
        int cur = company.getLevel().getLevel();
        if (cur >= 5) return false;
        CompanyLevel target = CompanyLevel.fromLevel(cur + 1);
        if (company.getStatus() == CompanyStatus.DELISTED) return false;
        if (company.getNetAssetValue() < target.getMinMarketCap()) return false;
        company.setLevel(target);
        return true;
    }

    public CompanyData getByStockId(String stockId) {
        return companiesByStockId.get(stockId);
    }

    public Collection<CompanyData> getAllCompanies() {
        return companiesByOwner.values();
    }

    public void registerStockId(CompanyData company, String stockId) {
        company.setStockId(stockId);
        companiesByStockId.put(stockId, company);
    }

    public void removeCompany(CompanyData company) {
        companiesByOwner.remove(company.getOwner());
        if (company.getStockId() != null) {
            companiesByStockId.remove(company.getStockId());
        }
    }

    /** §4.4：注销股票代码（发行中止/退市时使用），仅移除 stockId→company 映射，不删公司。 */
    public void unregisterStockId(CompanyData company) {
        if (company != null && company.getStockId() != null) {
            companiesByStockId.remove(company.getStockId());
        }
    }

    /**
     * §5.3：返回公司股东列表（确定性排序：持股降序，同持股按 UUID 字典序）。
     * 用作股权结构查询 / 分红 / 回购的权威遍历顺序。
     */
    public List<Map.Entry<UUID, Integer>> getShareholdersOf(CompanyData company) {
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(company.getShareholders().entrySet());
        list.sort(Comparator.comparingInt((Map.Entry<UUID, Integer> e) -> e.getValue()).reversed()
                .thenComparing(e -> e.getKey().toString()));
        return list;
    }

    // 序列化
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (CompanyData c : companiesByOwner.values()) {
            list.add(c.write());
        }
        tag.put("companies", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        ListTag list = tag.getList("companies", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompanyData c = CompanyData.read(list.getCompound(i));
            companiesByOwner.put(c.getOwner(), c);
            if (c.getStockId() != null) {
                companiesByStockId.put(c.getStockId(), c);
            }
        }
    }
}