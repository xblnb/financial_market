package com.example.financialmarket.block;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.futures.FuturesContract;
import com.example.financialmarket.engine.futures.FuturesMarket;
import com.example.financialmarket.engine.futures.FuturesPosition;
import com.example.financialmarket.engine.futures.czce.CzceFuturesMarket;
import com.example.financialmarket.init.BlockInit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 交割方块实体（BT-8）：在市场方块（绑卡售货/收款）基础上，额外<b>绑定一个期货合约</b>，
 * 交割日对绑定合约执行<b>实物交割</b>（多头收标的物品进本方块库存；保证金/PnL 复用现金结算语义结清），
 * 合约 EXPIRED。未绑定交割方块/合约的期货仍由 {@code FuturesMarket.deliverExpired} 现金结算兜底（CF-7 不回归）。
 *
 * <p>实物交割由 {@link MarketEngine} tick 在 {@code futuresMarket.tick} <b>之前</b>驱动
 * （{@link #tryPhysicalDelivery}），保证先于现金结算处理、无双重结算，且与引擎 tick 时间权威一致（确定性）。
 *
 * <p>方块级绑定（卡 + 合约 + delivered 标记）存方块实体 NBT。
 */
public class DeliveryBlockEntity extends MarketBlockEntity {

    private String boundContractCode;
    private boolean delivered;

    public DeliveryBlockEntity(BlockPos pos, BlockState state) {
        super(BlockInit.DELIVERY_BE_TYPE, pos, state);
    }

    public String getBoundContractCode() { return boundContractCode; }
    public boolean isDelivered() { return delivered; }

    /** 绑定期货合约代码（BT-8）。绑定时重置 delivered 标记。 */
    public void bindContract(String code) {
        this.boundContractCode = code;
        this.delivered = false;
        setChanged();
        registerToEngine();
    }

    /**
     * 实物交割（BT-8）：绑定合约到达交割日（status==DELIVERING 或已过最后交易日）时，
     * 对该合约所有玩家持仓做实物交割。
     *
     * <p><b>产品语义（与 BANKING_TRADE_SPEC §7「实物交割」一致）</b>：
     * 实物交割 = <b>单边实物 + 现金结算</b>——多头按 {@code multiplier×longLots} 收标的物品进本方块库存
     * 并<b>按交割价支付货款</b>（从保证金账户扣减，消除「得物+返现金」双记），空头按
     * {@code multiplier×shortLots} <b>从空头玩家库存交付标的物品</b>（不足按交割价现金补偿）并收取货款；
     * 两侧冻结保证金按持仓均价释放回账户。多空资金流向对称、确定性、无随机。
     *
     * @return 本次执行了交割返回 true；未到交割日/未绑约/已交割/tradeBlock 关闭返回 false
     */
    public boolean tryPhysicalDelivery(MarketEngine engine) {
        if (engine == null || delivered) return false;
        // BT-10：tradeBlock 关闭不驱动实物交割
        if (!engine.isTradeBlockEnabled()) return false;
        if (boundContractCode == null || boundContractCode.isEmpty()) return false;
        if (engine.isCzceFuturesMode()) {
            return tryCzcePhysicalDelivery(engine);
        }
        FuturesMarket fm = engine.getFuturesMarket();
        FuturesContract c = fm.getContract(boundContractCode);
        if (c == null) return false;
        // 未到交割日（未过最后交易日且尚未标记 DELIVERING）不交割
        if (c.status != FuturesContract.Status.DELIVERING
                && engine.getTickCounter() <= c.lastTradingTicks) return false;
        // 交割方块须绑卡（BT-8）
        if (!isBound() || !engine.isBankingEnabled()) return false;
        double deliveryPrice = c.prevSettlePrice > 0 ? c.prevSettlePrice : c.lastPrice;
        double mult = Math.max(1, c.multiplier);
        for (FuturesMarket.FuturesAccount acc : new java.util.ArrayList<>(fm.getAllAccounts())) {
            FuturesPosition pos = acc.positions.get(boundContractCode);
            if (pos == null || pos.isEmpty()) continue;
            // 释放两侧冻结保证金（按持仓均价 × 手数 × 保证金率；F2 修复：以实际冻结为上限，
            // 防档位上升后释放超冻结使 frozenMargin 转负——与 FuturesMarket 平仓/强平同语义）
            double released = Math.min(
                    (pos.avgLongPrice * pos.longLots + pos.avgShortPrice * pos.shortLots)
                            * mult * c.marginRatio,
                    acc.frozenMargin);
            acc.frozenMargin -= released;
            acc.freeCash += released;
            // 多头：收标的物品 + 按交割价支付货款（不再免费得物）
            if (pos.longLots > 0 && c.item != null) {
                int amount = (int) Math.round(mult * pos.longLots);
                if (amount > 0) addItemsToInventory(new ItemStack(c.item, amount));
                acc.freeCash -= deliveryPrice * mult * pos.longLots; // 支付货款
            }
            // 空头：交付标的物品（从空头玩家库存扣除），已交付部分收取货款；未交付部分按交割价现金补偿
            if (pos.shortLots > 0) {
                int qty = (int) Math.round(mult * pos.shortLots);
                int deliveredItems = 0;
                if (c.item != null && qty > 0) {
                    ServerPlayer shortPlayer = (level instanceof ServerLevel sl2)
                            ? sl2.getServer().getPlayerList().getPlayer(acc.playerId) : null;
                    if (shortPlayer != null) deliveredItems = removeItemsFromPlayer(shortPlayer, c.item, qty);
                }
                int shortfall = Math.max(0, qty - deliveredItems);
                acc.freeCash += deliveryPrice * deliveredItems; // 已交付部分收取货款
                acc.freeCash -= deliveryPrice * shortfall;      // 未交付部分按交割价现金补偿
            }
            acc.positions.remove(boundContractCode);
            // F2（连接审计修复 F2）：实物交割后账户清仓 → 全额了结 freeCash/残余保证金到玩家现金
            // （逐日盯市累计盈亏不再滞留期货账户；离线经引擎待补队列登录回放）。
            realizeAccount(engine, acc);
        }
        c.openInterest = 0;
        c.status = FuturesContract.Status.EXPIRED;
        delivered = true;
        setChanged();
        return true;
    }

    /** 从空头玩家库存扣除标的物品交付（返回实际扣减数）；玩家离线/物品不足则不足部分走现金补偿。 */
    /**
     * CZCE physical delivery: long receives warehouse goods and pays the delivery price; short
     * delivers goods from inventory (cash-compensates any shortfall) and receives payment. Margin
     * is released; accounts are swept back to the player wallet when flat.
     */
    private boolean tryCzcePhysicalDelivery(MarketEngine engine) {
        if (!isBound() || !engine.isBankingEnabled()) return false;
        CzceFuturesMarket fm = engine.getCzceFutures();
        if (fm == null) return false;
        CzceFuturesMarket.Contract c = fm.contract(boundContractCode);
        if (c == null) return false;
        if (engine.getTickCounter() <= c.lastTradingTick) return false;
        if (fm.isPhysicallyDelivered(c.code)) {
            delivered = true;
            return true;
        }
        if (c.status != CzceFuturesMarket.Status.LISTED) return false;
        CzceFuturesMarket.ProductSpec spec = fm.products().get(c.productCode);
        if (spec == null) return false;
        ResourceLocation rl = ResourceLocation.tryParse(spec.itemId());
        Item item = rl == null ? null : BuiltInRegistries.ITEM.get(rl);
        if (item == null) return false;

        double price = c.prevSettlePrice > 0 ? c.prevSettlePrice : c.lastPrice;
        int mult = Math.max(1, spec.multiplier());
        java.util.List<CzceFuturesMarket.Account> accounts = fm.accounts();

        // Pre-check long payments before mutating any account.
        for (CzceFuturesMarket.Account acc : accounts) {
            CzceFuturesMarket.Position p = acc.positions.get(c.code);
            if (p == null || p.longLots <= 0) continue;
            ServerPlayer player = playerOf(acc.id);
            double wallet = player == null ? 0.0 : PlayerEconomy.get(player).getCash();
            if (acc.cash + wallet < price * mult * p.longLots) return false;
        }

        for (CzceFuturesMarket.Account acc : accounts) {
            CzceFuturesMarket.Position p = acc.positions.get(c.code);
            if (p == null || p.isEmpty()) continue;
            ServerPlayer player = playerOf(acc.id);
            PlayerEconomy eco = player == null ? null : PlayerEconomy.get(player);
            double released = Math.min(
                    (p.avgLongPrice * p.longLots + p.avgShortPrice * p.shortLots)
                            * mult * c.marginRatio,
                    acc.margin);
            acc.margin -= released;
            acc.cash += released;

            if (p.longLots > 0) {
                int qty = mult * p.longLots;
                addItemsToInventory(new ItemStack(item, qty));
                double payment = price * mult * p.longLots;
                double fromAccount = Math.min(acc.cash, payment);
                acc.cash -= fromAccount;
                double fromWallet = payment - fromAccount;
                if (fromWallet > 0 && eco != null) {
                    eco.setCash(eco.getCash() - fromWallet);
                }
                engine.getCommodityMarket().applyConsumption(item, qty);
            }
            if (p.shortLots > 0) {
                int qty = mult * p.shortLots;
                int deliveredItems = player == null ? 0 : removeItemsFromPlayer(player, item, qty);
                int shortfall = Math.max(0, qty - deliveredItems);
                acc.cash += price * deliveredItems - price * shortfall;
                engine.getCommodityMarket().applyProduction(item, deliveredItems);
            }
            fm.recordPhysicalDelivery(c.productCode, p.longLots + p.shortLots,
                    mult * (p.longLots + p.shortLots));
            acc.positions.remove(c.code);
            if (acc.positions.isEmpty() && acc.cash > 0 && eco != null) {
                eco.setCash(eco.getCash() + acc.cash);
                acc.cash = 0;
            }
        }
        c.openInterest = 0;
        c.status = CzceFuturesMarket.Status.EXPIRED;
        fm.markPhysicallyDelivered(c.code);
        delivered = true;
        setChanged();
        return true;
    }

    private ServerPlayer playerOf(java.util.UUID id) {
        return level instanceof ServerLevel sl
                ? sl.getServer().getPlayerList().getPlayer(id) : null;
    }

    private int removeItemsFromPlayer(ServerPlayer player, net.minecraft.world.item.Item item, int amount) {
        if (player == null || item == null || amount <= 0) return 0;
        int removed = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (removed >= amount) break;
            if (stack.isEmpty() || !stack.is(item)) continue;
            int take = Math.min(stack.getCount(), amount - removed);
            stack.shrink(take);
            removed += take;
        }
        player.getInventory().setChanged();
        return removed;
    }

    /**
     * F2（连接审计修复 F2）：实物交割后账户无持仓时全额了结——freeCash（逐日盯市累计盈亏）与
     * 残余冻结保证金一并转回玩家现金（确定性、无随机；离线经引擎待补队列登录回放）。
     */
    private void realizeAccount(MarketEngine engine, FuturesMarket.FuturesAccount acc) {
        if (acc == null || !acc.positions.isEmpty()) return;
        double total = acc.freeCash + acc.frozenMargin;
        if (total == 0) return;
        if (engine != null && level instanceof ServerLevel sl) {
            engine.creditPlayerCash(sl, acc.playerId, total);
        }
        acc.freeCash = 0;
        acc.frozenMargin = 0;
    }

    /** 把物品堆叠放入输入库存（先合并同物品，再放空槽；确定性）。 */
    private void addItemsToInventory(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        int remaining = stack.getCount();
        for (int i = 0; i < getInventory().getSlots() && remaining > 0; i++) {
            ItemStack inSlot = getInventory().getStackInSlot(i);
            if (inSlot.isEmpty()) {
                int put = Math.min(remaining, stack.getMaxStackSize());
                getInventory().setStackInSlot(i, stack.copyWithCount(put));
                remaining -= put;
            } else if (ItemStack.isSameItemSameComponents(inSlot, stack)) {
                int space = stack.getMaxStackSize() - inSlot.getCount();
                int put = Math.min(space, remaining);
                if (put > 0) {
                    inSlot.grow(put);
                    getInventory().setStackInSlot(i, inSlot);
                    remaining -= put;
                }
            }
        }
    }

    @Override
    public void tick() {
        super.tick(); // 与市场方块一致的售货/收款交互（BT-8）
    }

    @Override
    public void onLoad() {
        super.onLoad();
        registerToEngine();
    }

    @Override
    public void setRemoved() {
        unregisterFromEngine();
        super.setRemoved();
    }

    /** 把 (合约代码→本方块) 登记到引擎交割注册表（供引擎 tick 驱动实物交割）。 */
    private void registerToEngine() {
        if (level instanceof ServerLevel sl && boundContractCode != null && !boundContractCode.isEmpty()) {
            MarketEngine engine = MarketAttachment.getEngine(sl);
            if (engine != null) engine.registerDeliveryBlock(boundContractCode, this);
        }
    }

    private void unregisterFromEngine() {
        if (level instanceof ServerLevel sl && boundContractCode != null) {
            MarketEngine engine = MarketAttachment.getEngine(sl);
            if (engine != null) engine.unregisterDeliveryBlock(boundContractCode, this);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        if (boundContractCode != null) tag.putString("boundContractCode", boundContractCode);
        tag.putBoolean("delivered", delivered);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        if (tag.contains("boundContractCode")) boundContractCode = tag.getString("boundContractCode");
        if (tag.contains("delivered")) delivered = tag.getBoolean("delivered");
        registerToEngine();
    }
}
