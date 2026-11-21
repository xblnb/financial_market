package com.example.financialmarket.block;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.CommodityEntry;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.RecipeAnalyzer;
import com.example.financialmarket.engine.banking.Bank;
import com.example.financialmarket.engine.banking.BankCard;
import com.example.financialmarket.init.BlockInit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * 市场方块实体（BT-7）。
 *
 * <p>持有输入库存（{@link ItemStackHandler}，实现 IItemHandler 能力 → 漏斗/其他模组管道可输入物品）。
 * 每 tick：当<b>商品市场开市</b>且<b>已绑定有效储蓄卡</b>时，从库存按 {@link CommodityEntry#getPrice()}
 * 售货，收益经 {@link Bank#deposit} 入<b>绑定玩家的储蓄账户</b>（M1 活期层，M2 协同）。未绑卡不结算
 * （物品保留），休市不结算（与 {@code CommodityMarket.canTrade} 一致）。确定性、无随机。
 *
 * <p>方块级绑定（boundOwnerUuid/boundCardNumber/boundBankId/库存）存<b>方块实体 NBT</b>
 * （{@code saveAdditional}/{@code loadAdditional}），不混入引擎 blob（§8.2）。
 */
public class MarketBlockEntity extends BlockEntity {
    /** 输入库存槽位数。 */
    public static final int INVENTORY_SIZE = 9;
    /** 每 tick 至多结算物品数（防单 tick 过度，确定性）。 */
    public static final int MAX_SELL_PER_TICK = 64;

    private final ItemStackHandler inventory = new ItemStackHandler(INVENTORY_SIZE) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    private java.util.UUID boundOwnerUuid;
    private String boundCardNumber;
    private String boundBankId;

    public MarketBlockEntity(BlockPos pos, BlockState state) {
        this(BlockInit.MARKET_BE_TYPE, pos, state);
    }

    protected MarketBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
                                BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public ItemStackHandler getInventory() { return inventory; }
    public java.util.UUID getBoundOwnerUuid() { return boundOwnerUuid; }
    public String getBoundCardNumber() { return boundCardNumber; }
    public boolean isBound() {
        return boundOwnerUuid != null && boundCardNumber != null && !boundCardNumber.isEmpty();
    }

    /**
     * 绑定持卡玩家（BT-7）：须持有效储蓄卡才可绑定（写 boundOwnerUuid/boundCardNumber/boundBankId）。
     *
     * @return 绑定成功返回 true；banking 关闭或玩家未持卡返回 false
     */
    public boolean bindCard(MarketEngine engine, ServerPlayer player) {
        if (engine == null || player == null) return false;
        if (!engine.isBankingEnabled()) return false; // 隔离：banking 关闭不可绑定（BT-10）
        BankCard card = engine.getBankCardService().getCardOf(player.getUUID());
        if (card == null) return false; // 未持卡不可绑定
        this.boundOwnerUuid = player.getUUID();
        this.boundCardNumber = card.getCardNumber();
        this.boundBankId = card.getBankId();
        setChanged();
        return true;
    }

    /** 方块实体 tick：开市且已绑卡时售货入储蓄账户（确定性）。 */
    public void tick() {
        if (level == null || level.isClientSide) return;
        if (!(level instanceof ServerLevel sl)) return;
        MarketEngine engine = MarketAttachment.getEngine(sl);
        if (engine == null) return;
        // BT-10：tradeBlock 关闭不 tick 售货（回落「不售货」基线）
        if (!engine.isTradeBlockEnabled()) return;
        // 休市 / 商品关闭不结算（与 CommodityMarket.canTrade 一致，BT-7 + CF-4）
        if (!engine.isCommodityEnabled() || !engine.getCalendar().isMarketOpen()) return;
        // 未绑卡不结算（物品保留，BT-7）；banking 关闭视为无卡不可收款（BT-10）
        if (!isBound() || !engine.isBankingEnabled()) return;
        // 校验绑定卡仍有效且归属一致
        BankCard card = engine.getBankCardService().getCardByNumber(boundCardNumber);
        if (card == null || !card.isActive()
                || !card.getOwnerUuid().equals(boundOwnerUuid)) return;
        Bank bank = engine.getBankManager().getBank(boundBankId != null ? boundBankId : card.getBankId());
        if (bank == null) return;
        int sold = 0;
        for (int slot = 0; slot < inventory.getSlots() && sold < MAX_SELL_PER_TICK; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            CommodityEntry entry = engine.getCommodityMarket().getCommodity(stack.getItem());
            if (entry == null) continue;
            int toSell = Math.min(stack.getCount(), MAX_SELL_PER_TICK - sold);
            double revenue = entry.getPrice() * toSell;
            inventory.extractItem(slot, toSell, false);
            bank.deposit(boundOwnerUuid, revenue);   // 收益入储蓄账户（M1 活期，BT-7）
            entry.sellPressure(toSell);              // 确定性供给压力更新（复用 CF-4 语义）
            propagateBlockSupply(engine, stack.getItem(), toSell); // 下游供给传播（与玩家售货一致）
            sold += toSell;
        }
    }

    /**
     * 卖出后对上游物品的下游供给传播（与 {@code CommodityMarket.propagateSupply} 同语义，CF-4）：
     * 卖出某物品 → 其上游原材料的供给相对增加（adjustSupply(−qty×0.5)），使方块售货与玩家售货的供给传播一致。
     */
    private void propagateBlockSupply(MarketEngine engine, net.minecraft.world.item.Item soldItem, int qty) {
        for (net.minecraft.world.item.Item upstream : RecipeAnalyzer.getUpstream(soldItem)) {
            CommodityEntry e = engine.getCommodityMarket().getCommodity(upstream);
            if (e != null) e.adjustSupply(-qty * 0.5);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.put("inventory", inventory.serializeNBT(provider));
        if (boundOwnerUuid != null) tag.putUUID("boundOwnerUuid", boundOwnerUuid);
        if (boundCardNumber != null) tag.putString("boundCardNumber", boundCardNumber);
        if (boundBankId != null) tag.putString("boundBankId", boundBankId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        if (tag.contains("inventory")) inventory.deserializeNBT(provider, tag.getCompound("inventory"));
        if (tag.contains("boundOwnerUuid")) boundOwnerUuid = tag.getUUID("boundOwnerUuid");
        if (tag.contains("boundCardNumber")) boundCardNumber = tag.getString("boundCardNumber");
        if (tag.contains("boundBankId")) boundBankId = tag.getString("boundBankId");
    }
}
