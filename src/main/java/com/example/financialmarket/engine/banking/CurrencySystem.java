package com.example.financialmarket.engine.banking;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * 法币化改造（t2）：取消下界合金锭的固定汇率实物锚定。
 *
 * <p>改造前：{@code BASE_ITEM = NETHERITE_INGOT}，以固定 {@code EXCHANGE_RATE = 32.0} 把合金锭兑付为
 * {@code PlayerEconomy.cash}——现金供给受玩家可挖合金锭数量约束，并非央行内生决定。
 *
 * <p>改造后（法币体制）：
 * <ul>
 *   <li>下界合金锭（NETHERITE_INGOT）<b>不再</b>是货币实物锚：不再以固定 32:1 兑换玩家现金。它改为
 *       一种普通可交易商品，价格由 {@code CommodityMarket}（商品市场，价格由市场供求决定）买卖，不进入
 *       货币供给。</li>
 *   <li>玩家现金 {@code PlayerEconomy.cash} 完全由法币体系决定：由央行基础货币注入产生
 *       （{@code CentralBank.injectBaseMoney}），不再依赖玩家投入的实物数量。</li>
 *   <li>货币供给由央行货币政策决定（见 {@code CentralBank}）。</li>
 * </ul>
 *
 * <p>为兼容既有网络包（{@code DepositPacket}/{@code WithdrawPacket}）与 UI 显示，保留
 * {@code deposit}/{@code withdraw} 方法签名与 {@code EXCHANGE_RATE} 常量；但二者已<b>不构成</b>
 * “锭 → 现金”固定汇率兑付路径（兑付被禁用）。
 */
public class CurrencySystem {

    /** 下界合金锭：法币化后作为普通可交易商品（经 CommodityMarket 按市场价格买卖），非货币实物锚。 */
    public static final Item BASE_ITEM = Items.NETHERITE_INGOT;

    /**
     * 历史固定汇率常量（保留仅为兼容旧 UI 显示，见 FinancialTabletScreen）。
     * 法币化后<b>不再</b>作为兑付现金的锚：{@code deposit}/{@code withdraw} 不使用它创造/扣减玩家现金。
     */
    public static double EXCHANGE_RATE = 32.0;

    /**
     * 法币化：禁用固定汇率“锭兑现金”。
     * 下界合金锭改走 {@code CommodityMarket}（商品市场，价格由市场决定），不在本方法直接创造法币现金。
     * 返回 {@code false} 表示该固定汇率兑付通道已停用。
     *
     * @param player    玩家
     * @param itemCount 合金锭数量（已忽略——不再按固定汇率创造现金）
     * @return 恒为 {@code false}（兑付禁用）
     */
    public static boolean deposit(ServerPlayer player, int itemCount) {
        return false;
    }

    /**
     * 法币化：禁用固定汇率“现金兑锭”。
     * 下界合金锭只能经商品市场按市场价格购得，不再以固定汇率从现金直接兑付。
     * 返回 {@code false} 表示该固定汇率兑付通道已停用。
     *
     * @param player    玩家
     * @param itemCount 合金锭数量（已忽略）
     * @return 恒为 {@code false}（兑付禁用）
     */
    public static boolean withdraw(ServerPlayer player, int itemCount) {
        return false;
    }
}
