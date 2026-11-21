package com.example.financialmarket.engine.role;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 上层建筑角色体系（t2/SR-1/SR-2/SR-8）。
 *
 * <p>把「散户 vs 央行行长 vs 财政部长」分层的唯一权威角色表放在服务端。
 * 玩家角色绑定由服务端命令（{@code /fmarket role assign/revoke}）维护，
 * 客户端只读展示；所有货币政策/财政操作在 {@code GovernmentActionPacket} 服务端
 * {@code handle} 中经 {@link #hasRole(UUID, Role)} 校验后执行，服务端为唯一权威。
 *
 * <p><strong>确定性</strong>：角色匹配、互斥任命、序列化均为确定性函数，无任何随机
 * （对齐 {@code ECON_IMPROVEMENT_PLAN} §6），同种子下角色状态可复现。
 *
 * <p>当前版本以进程级单例承载（{@link #getInstance()}），在服务端单世界环境下即服务端
 * 权威角色表；{@link #saveToNBT()}/{@link #loadFromNBT(CompoundTag)} 提供序列化 API，
 * 后续（t5 集成阶段）可随 {@code MarketEngine.save()/load()} 的引擎 blob 一起持久化
 * （SR-8 H2 持久化）。
 */
public final class RoleSystem {

    /** 角色分层：散户（默认）/央行行长（货币政策）/财政部长（财政）。 */
    public enum Role {
        RETAIL("retail"),
        CENTRAL_BANKER("central_banker"),
        FINANCE_MINISTER("finance_minister");

        private final String key;

        Role(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** 解析角色名，未知/空 → null。 */
        public static Role fromName(String name) {
            if (name == null) return null;
            for (Role r : values()) {
                if (r.name().equalsIgnoreCase(name) || r.key().equalsIgnoreCase(name)) {
                    return r;
                }
            }
            return null;
        }

        public boolean isManagement() {
            return this != RETAIL;
        }
    }

    private static final RoleSystem INSTANCE = new RoleSystem();

    /** 服务端唯一权威角色表：玩家 UUID → 角色。缺省视为 {@link Role#RETAIL}。 */
    private final Map<UUID, Role> roles = new HashMap<>();

    private RoleSystem() {
    }

    /** 服务端进程级单例（服务端唯一权威角色表）。 */
    public static RoleSystem getInstance() {
        return INSTANCE;
    }

    /** 读取玩家当前角色，无记录默认 {@link Role#RETAIL}。 */
    public Role get(UUID playerId) {
        if (playerId == null) return Role.RETAIL;
        return roles.getOrDefault(playerId, Role.RETAIL);
    }

    /** 玩家是否持有指定角色（用于权限校验）。 */
    public boolean hasRole(UUID playerId, Role role) {
        if (role == null) return false;
        return get(playerId) == role;
    }

    /** 玩家是否持有任一管理角色（央行行长/财政部长）。 */
    public boolean hasAnyManagementRole(UUID playerId) {
        return get(playerId).isManagement();
    }

    /**
     * 任命玩家为指定角色（服务端唯一入口）。
     * 互斥：任命 {@link Role#CENTRAL_BANKER} 或 {@link Role#FINANCE_MINISTER} 时，
     * 该玩家原有另一管理角色被覆盖清除；任命 {@link Role#RETAIL} 等价于撤销回散户默认。
     */
    public void appoint(UUID playerId, Role role) {
        if (playerId == null) return;
        if (role == null || role == Role.RETAIL) {
            roles.remove(playerId);
            return;
        }
        // 互斥：写一条记录即覆盖旧管理角色，同一玩家同一时刻只持有一个管理角色
        roles.put(playerId, role);
    }

    /** 撤销玩家管理角色，降回散户 {@link Role#RETAIL}。 */
    public void revoke(UUID playerId) {
        if (playerId == null) return;
        roles.remove(playerId);
    }

    /** 序列化角色表（供引擎 blob 持久化，SR-8）。缺省字段读档时默认 {@link Role#RETAIL}。 */
    public CompoundTag saveToNBT() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Role> e : roles.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            CompoundTag et = new CompoundTag();
            et.putUUID("id", e.getKey());
            et.putString("role", e.getValue().name());
            list.add(et);
        }
        tag.put("roles", list);
        return tag;
    }

    /**
     * 从 NBT 恢复角色表。
     * 前向兼容：缺字段/未知角色记录跳过；全新/空标签 ⇒ 全部玩家默认 {@link Role#RETAIL}。
     */
    public void loadFromNBT(CompoundTag tag) {
        roles.clear();
        if (tag == null) return;
        ListTag list;
        try {
            list = tag.getList("roles", Tag.TAG_COMPOUND);
        } catch (Exception e) {
            return;
        }
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag et = list.getCompound(i);
            Role role = Role.fromName(et.getString("role"));
            if (role == null || role == Role.RETAIL) continue;
            UUID id;
            try {
                id = et.getUUID("id");
            } catch (Exception e) {
                continue;
            }
            if (id != null) {
                roles.put(id, role);
            }
        }
    }

    /** 当前持有管理角色的玩家数（诊断/验证用）。 */
    public int managementRoleCount() {
        int n = 0;
        for (Role r : roles.values()) {
            if (r != null && r.isManagement()) n++;
        }
        return n;
    }

    /** 供测试/校验读取不可变角色的简单快照（键：UUID 字符串，无引擎引用）。 */
    public Map<String, String> snapshot() {
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<UUID, Role> e : roles.entrySet()) {
            m.put(e.getKey().toString(), e.getValue().name());
        }
        return m;
    }
}
