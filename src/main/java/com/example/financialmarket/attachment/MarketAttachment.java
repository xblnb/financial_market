package com.example.financialmarket.attachment;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.attachment.AttachmentType;

import java.util.HashMap;
import java.util.Map;

/**
 * 引擎主状态持久化 —— 迁移到 H2（整块序列化列 + 关系化镜像，见 H2_MIGRATION.md §6/§7）。
 *
 * <p>真相来源切换：优先从 H2 读取/保存（{@link MarketEngine#loadFromH2}/{@link MarketEngine#saveToH2}）；
 * NBT 世界附件（{@code level.setData}）保留作为<strong>降级镜像</strong>：仅当 H2 无数据时（首次启动迁移
 * 或 H2 不可用）才回退到 NBT 附件加载，加载后立即回写 H2；保存时 H2 为主、NBT 附件同步镜像。
 *
 * <p>空值安全：加载后若资产列表为空则重新 {@code init()}，避免历史 NBT 空值 / profile NPE 回归（t1 审计点）。
 */
public class MarketAttachment {
    private static final Map<ServerLevel, MarketEngine> engineCache = new HashMap<>();

    // P0-7：世界种子派生引擎种子的盐值（与引擎主代码隔离，保证同世界种子 → 同行情）
    private static final long ENGINE_SEED_SALT = 0x5EED_5EED_5EED_5EEDL;

    private static AttachmentType<CompoundTag> getAttachmentType() {
        return AttachmentInit.MARKET.get();
    }

    /** P0-7：由世界种子派生可复现的引擎种子。 */
    private static long deriveEngineSeed(ServerLevel level) {
        return SeedableRandom.mix64(level.getSeed() ^ ENGINE_SEED_SALT);
    }

    public static MarketEngine getEngine(ServerLevel level) {
        return engineCache.get(level);
    }

    public static MarketEngine getOrCreateEngine(ServerLevel level) {
        MarketEngine engine = engineCache.get(level);
        if (engine == null) {
            // ① 优先从 H2 加载（整块状态）
            engine = MarketEngine.loadFromH2();
            if (engine != null) {
                // 加载后若资产为空，重新初始化
                if (engine.getState().getAssetIds().isEmpty()) {
                    System.out.println("⚠️ H2 加载后资产列表为空，执行重新初始化");
                    engine.init();
                }
                System.out.println("✅ 从 H2 加载引擎，资产数: " + engine.getState().getAssetIds().size());
                engineCache.put(level, engine);
                return engine;
            }

            // ② 回退：从 NBT 世界附件加载（首次启动迁移路径）
            AttachmentType<CompoundTag> type = getAttachmentType();
            if (type == null) {
                throw new IllegalStateException("Market attachment type not registered!");
            }
            CompoundTag tag = level.getData(type);
            if (tag != null && !tag.getAllKeys().isEmpty()) {
                try {
                    engine = MarketEngine.load(tag);
                    if (engine != null && engine.getState().getAssetIds().isEmpty()) {
                        System.out.println("⚠️ NBT 加载后资产列表为空，执行重新初始化");
                        engine.init();
                    }
                    System.out.println("✅ 从 NBT 附件迁移加载引擎，资产数: " +
                            (engine != null ? engine.getState().getAssetIds().size() : 0));
                    // 迁移：回写 H2
                    if (engine != null) engine.saveToH2();
                } catch (Exception e) {
                    System.err.println("❌ 加载引擎失败: " + e.getMessage());
                    e.printStackTrace();
                    engine = null;
                }
            }

            // ③ 都无数据 → 新建引擎（P0-7：用世界种子派生的引擎种子，保证可复现）
            if (engine == null) {
                engine = new MarketEngine(deriveEngineSeed(level));
                engine.init();
                System.out.println("✅ 新建引擎，资产数: " + engine.getState().getAssetIds().size());
                saveEngine(level); // 立即保存（H2 + NBT 镜像）
            }
            // A 股 T+1 迁移（AS-3，低严重度修复）：首个 A 股开启 load 时，把旧存档既有持仓计入
            // T1 available 账本（历史持仓视为已解冻可卖），避免 A 股开启后历史持仓不可卖。
            // 迁移失败不阻断读档（非失败路径）。
            try {
                seedExistingHoldings(level, engine);
            } catch (Exception ex) {
                System.err.println("⚠️ A 股 T+1 持仓迁移失败（非阻断）: " + ex.getMessage());
            }
            engineCache.put(level, engine);
        }
        return engine;
    }

    /**
     * A 股 T+1 迁移：把当前在线玩家的既有 PlayerEconomy 持仓初始化为 T1 available。
     * 仅当 A 股开启且 T+1 账本为空（首次 A 股激活/迁移）时执行一次；幂等、确定性、无随机。
     * 非读档失败路径（账本为空时读档依然成功，只是补种历史持仓）。
     */
    private static void seedExistingHoldings(ServerLevel level, MarketEngine engine) {
        if (engine == null || !engine.isAShareEnabled()) return;
        if (engine.getSettlement() == null || !engine.getSettlement().isLedgerEmpty()) return;
        Map<java.util.UUID, Map<String, Integer>> holdingsByPlayer = new HashMap<>();
        for (net.minecraft.server.level.ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
            PlayerEconomy eco = PlayerEconomy.get(sp);
            if (eco == null || eco.getHoldings() == null || eco.getHoldings().isEmpty()) continue;
            Map<String, Integer> perPlayer = new HashMap<>();
            for (Holding h : eco.getHoldings().values()) {
                if (h != null && h.getQuantity() > 0) perPlayer.put(h.getAssetId(), h.getQuantity());
            }
            if (!perPlayer.isEmpty()) holdingsByPlayer.put(sp.getUUID(), perPlayer);
        }
        if (!holdingsByPlayer.isEmpty()) {
            engine.seedHoldingsToAvailable(holdingsByPlayer);
            System.out.println("🌱 A 股 T+1 迁移：把 " + holdingsByPlayer.size() + " 名玩家既有持仓计入可用可卖账本");
        }
    }

    /**
     * 保存引擎：H2 为主真相来源，NBT 世界附件同步写一份作为降级镜像。
     * 即使 H2 关闭也继续写 NBT，保证数据不丢失。
     */
    public static void saveEngine(ServerLevel level) {
        MarketEngine engine = engineCache.get(level);
        if (engine != null) {
            // H2 主真相
            engine.saveToH2();
            // NBT 附件降级镜像
            AttachmentType<CompoundTag> type = getAttachmentType();
            if (type != null) {
                try {
                    level.setData(type, engine.save());
                    System.out.println("💾 引擎数据已保存（NBT 镜像），资产数: " + engine.getState().getAssetIds().size());
                } catch (Exception e) {
                    System.err.println("❌ 保存 NBT 镜像失败: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    public static void removeEngine(ServerLevel level) {
        engineCache.remove(level);
    }
}
