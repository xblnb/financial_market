package com.example.financialmarket.attachment;

import com.example.financialmarket.engine.margin.MarginAccount;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import java.util.*;

public class PlayerEconomy {

    /**
     * 初始法币现金（兜底常量）：法币化后新玩家入账时由央行基础货币注入发放
     * （{@code CentralBank.injectBaseMoney(initialAirdrop)}，再 {@code setCash}），保证现金可溯源到
     * 央行基础货币（M0 增加）。此值仅作无注入时的兜底，不再由玩家投入的实物（如下界合金锭）数量决定。
     */
    public static final double INITIAL_CASH = 1000.0;

    private double cash = INITIAL_CASH;
    private final Map<String, Holding> holdings = new HashMap<>();
    private final List<String> watchlist = new ArrayList<>();
    private MarginAccount marginAccount;

    public double getCash() { return cash; }

    /** 法币化：玩家现金完全由法币体系决定（央行注入 + 交易/税收流转），不受实物兑付影响。 */
    public void setCash(double cash) { this.cash = cash; }

    /** t4（A-5/A-6）：确定性现金入账（公司分红、退市回购等经济主体经营所得派发）。 */
    public void addCash(double amount) { this.cash += amount; }
    public Map<String, Holding> getHoldings() { return holdings; }
    public List<String> getWatchlist() { return watchlist; }

    public void addHolding(String assetId, int quantity, double price) {
        Holding old = holdings.get(assetId);
        if (old != null) {
            int totalQ = old.getQuantity() + quantity;
            double totalCost = old.getAvgCost() * old.getQuantity() + price * quantity;
            holdings.put(assetId, new Holding(assetId, totalQ, totalCost / totalQ));
        } else {
            holdings.put(assetId, new Holding(assetId, quantity, price));
        }
    }

    public void removeHolding(String assetId, int quantity) {
        Holding h = holdings.get(assetId);
        if (h == null) return;
        int newQty = h.getQuantity() - quantity;
        if (newQty <= 0) holdings.remove(assetId);
        else holdings.put(assetId, new Holding(assetId, newQty, h.getAvgCost()));
    }

    /*
     * 获取融资融券账户（t3 修复 bug）：账户归引擎级 {@link MarginManager} 注册表管理，
     * 这里委托给 {@code MarketEngine.getMarginManager().getOrCreateAccount(...)}，
     * 不再每次 new 一个临时账户（旧实现局部变量遮蔽字段、恒 new，且不持久）。
     * 引擎不可用时（极端兜底）返回本字段缓存的临时账户（不持久，仅查询）。
     */
    public MarginAccount getMarginAccount(ServerPlayer player) {
        var engine = MarketAttachment.getEngine(player.serverLevel());
        if (engine != null) {
            return engine.getMarginManager().getOrCreateAccount(player.getUUID());
        }
        if (this.marginAccount == null) {
            this.marginAccount = new MarginAccount(player.getUUID());
        }
        return this.marginAccount;
    }

    public static final Codec<PlayerEconomy> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("cash").forGetter(PlayerEconomy::getCash),
            Codec.list(Holding.CODEC).fieldOf("holdings").forGetter(e -> new ArrayList<>(e.getHoldings().values())),
            Codec.list(Codec.STRING).fieldOf("watchlist").forGetter(PlayerEconomy::getWatchlist)
    ).apply(instance, (cash, holds, watch) -> {
        PlayerEconomy eco = new PlayerEconomy();
        eco.cash = cash;
        for (Holding h : holds) eco.holdings.put(h.getAssetId(), h);
        eco.watchlist.addAll(watch);
        return eco;
    }));

    public static PlayerEconomy get(Player player) {
        return player.getData(AttachmentInit.PLAYER_ECONOMY.get());
    }
}