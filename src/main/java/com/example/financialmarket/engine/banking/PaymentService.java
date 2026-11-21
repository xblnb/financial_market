package com.example.financialmarket.engine.banking;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Card payment / peer transfer / merchant payment service.
 *
 * <p>Server-authoritative: validates card status, PIN hash, daily limit and bank balance before
 * moving deposit balances. The ledger is append-only and persisted with the engine blob.</p>
 */
public final class PaymentService {

    public record PaymentTransaction(long id, long tick, long day, UUID from, UUID to,
                                     String fromCard, String toCard, double amount, double fee,
                                     String channel, String memo, String status) {
        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("id", id);
            t.putLong("tick", tick);
            t.putLong("day", day);
            if (from != null) t.putUUID("from", from);
            if (to != null) t.putUUID("to", to);
            t.putString("fromCard", fromCard);
            t.putString("toCard", toCard);
            t.putDouble("amount", amount);
            t.putDouble("fee", fee);
            t.putString("channel", channel);
            t.putString("memo", memo);
            t.putString("status", status);
            return t;
        }

        public static PaymentTransaction load(CompoundTag t) {
            return new PaymentTransaction(t.getLong("id"), t.getLong("tick"), t.getLong("day"),
                    t.contains("from") ? t.getUUID("from") : null,
                    t.contains("to") ? t.getUUID("to") : null,
                    t.getString("fromCard"), t.getString("toCard"),
                    t.getDouble("amount"), t.getDouble("fee"),
                    t.getString("channel"), t.getString("memo"), t.getString("status"));
        }
    }

    public record Receipt(boolean success, String message, long transactionId,
                          double amount, double fee, double payerBalance) {
    }

    private static final int MAX_LEDGER = 5000;

    private final BankManager banks;
    private final BankCardService cards;
    private final List<PaymentTransaction> ledger = new ArrayList<>();
    private long nextId = 1;

    public PaymentService(BankManager banks, BankCardService cards) {
        this.banks = banks;
        this.cards = cards;
    }

    public Receipt transfer(ServerPlayer payer, String targetCardNumber, double amount,
                            String memo, String pin, long tick, boolean merchant) {
        return transfer(payer == null ? null : payer.getUUID(), targetCardNumber, amount, memo,
                pin, tick, merchant);
    }

    /** UUID-level entry point (also used by offline/test payment flows). */
    public Receipt transfer(UUID payerId, String targetCardNumber, double amount,
                            String memo, String pin, long tick, boolean merchant) {
        if (payerId == null) return fail("玩家不可用");
        if (!(amount > 0) || !Double.isFinite(amount)) return fail("金额必须为正");
        BankCard from = cards.getCardOf(payerId);
        if (from == null || !from.isActive()) return fail("请先办理储蓄卡");
        if (from.isFrozen()) return fail("卡片已冻结，请先解冻");
        if (!from.hasPin()) return fail("请先设置支付密码");
        if (!cards.verifyPin(from, pin)) return fail("支付密码错误");
        BankCard to = cards.getCardByNumber(targetCardNumber == null ? "" : targetCardNumber.trim());
        if (to == null || !to.isActive()) return fail("收款卡号不存在或已失效");
        if (to.isFrozen()) return fail("收款卡已冻结");
        if (to.getOwnerUuid().equals(payerId)) return fail("不能向自己的卡转账");

        long day = tick / 24000L;
        from.rollDay(day);
        if (from.getSpentToday() + amount > from.getDailyLimit()) {
            return fail("超过单日限额 " + fmt(from.getDailyLimit()));
        }
        double fee = merchant ? 0.0 : Math.min(50.0, Math.max(0.01, amount * 0.001));
        double total = amount + fee;
        Bank payerBank = banks.getBank(from.getBankId());
        Bank payeeBank = banks.getBank(to.getBankId());
        if (payerBank == null || payeeBank == null) return fail("开户行不可用");
        if (payerBank.getDepositBalance(payerId) < total) return fail("活期余额不足");

        if (!payerBank.withdraw(payerId, total)) return fail("银行清算失败（库存现金/准备金不足）");
        payeeBank.deposit(to.getOwnerUuid(), amount);
        if (fee > 0) payerBank.creditFee(fee);
        from.setSpentToday(from.getSpentToday() + amount);

        long id = nextId++;
        PaymentTransaction tx = new PaymentTransaction(id, tick, day, payerId,
                to.getOwnerUuid(), BankCardService.mask(from.getCardNumber()),
                BankCardService.mask(to.getCardNumber()), amount, fee,
                merchant ? "MERCHANT" : "TRANSFER",
                memo == null ? "" : memo, "SUCCESS");
        record(tx);
        return new Receipt(true, (merchant ? "支付成功" : "转账成功") + "："
                + fmt(amount) + "（手续费 " + fmt(fee) + "）",
                id, amount, fee, payerBank.getDepositBalance(payerId));
    }

    public Receipt pay(ServerPlayer payer, String merchantCard, double amount, String memo,
                       String pin, long tick) {
        return transfer(payer, merchantCard, amount, memo, pin, tick, true);
    }

    public Receipt setPin(ServerPlayer player, String pin) {
        if (player == null) return fail("玩家不可用");
        if (!BankCardService.validPin(pin)) return fail("支付密码必须为 4-6 位数字");
        if (!cards.setPin(player.getUUID(), pin)) return fail("未找到有效储蓄卡");
        return new Receipt(true, "支付密码已设置", 0, 0, 0, 0);
    }

    public Receipt freeze(ServerPlayer player, boolean frozen) {
        if (player == null) return fail("玩家不可用");
        boolean ok = frozen ? cards.freeze(player.getUUID()) : cards.unfreeze(player.getUUID());
        return ok ? new Receipt(true, frozen ? "卡片已冻结" : "卡片已解冻", 0, 0, 0, 0)
                : fail("操作失败");
    }

    public List<PaymentTransaction> statement(UUID owner, int limit) {
        if (owner == null) return List.of();
        List<PaymentTransaction> out = new ArrayList<>();
        for (PaymentTransaction tx : ledger) {
            if (owner.equals(tx.from()) || owner.equals(tx.to())) out.add(tx);
        }
        out.sort(Comparator.comparingLong(PaymentTransaction::id).reversed());
        if (limit > 0 && out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    public List<PaymentTransaction> all() {
        return List.copyOf(ledger);
    }

    public long nextId() {
        return nextId;
    }

    private void record(PaymentTransaction tx) {
        ledger.add(tx);
        while (ledger.size() > MAX_LEDGER) ledger.remove(0);
    }

    private static Receipt fail(String message) {
        return new Receipt(false, message, 0, 0, 0, 0);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("nextId", nextId);
        ListTag list = new ListTag();
        for (PaymentTransaction tx : ledger) list.add(tx.save());
        tag.put("ledger", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        ledger.clear();
        if (tag == null) return;
        nextId = tag.contains("nextId") ? tag.getLong("nextId") : 1;
        ListTag list = tag.getList("ledger", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ledger.add(PaymentTransaction.load(list.getCompound(i)));
        }
        if (nextId <= ledger.stream().mapToLong(PaymentTransaction::id).max().orElse(0)) {
            nextId = ledger.stream().mapToLong(PaymentTransaction::id).max().orElse(0) + 1;
        }
    }
}
