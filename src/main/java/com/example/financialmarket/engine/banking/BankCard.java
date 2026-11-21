package com.example.financialmarket.engine.banking;

import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/**
 * 储蓄卡数据模型（BT-3）。
 *
 * <p>卡号 16 位（含 Luhn 校验位，末位为校验位）；与 {@code ownerUuid} 唯一绑定；
 * {@code bankId} 为开户行（BANK_A..BANK_D）；{@code issuedTick} 为签发时的引擎 tick
 * （确定性，用于可复现/排序）；{@code active} 表示是否有效（撤销后为 {@code false}）。
 */
public class BankCard {

    /** 卡号长度（含 Luhn 校验位）。 */
    public static final int CARD_DIGITS = 16;

    private String cardNumber;
    private String bankId;
    private UUID ownerUuid;
    private long issuedTick;
    private boolean active;
    private String pinHash;
    private boolean frozen;
    private double dailyLimit = 50000.0;
    private double spentToday;
    private long lastSpendDay = Long.MIN_VALUE;

    public BankCard() {
    }

    public BankCard(String cardNumber, String bankId, UUID ownerUuid, long issuedTick, boolean active) {
        this.cardNumber = cardNumber;
        this.bankId = bankId;
        this.ownerUuid = ownerUuid;
        this.issuedTick = issuedTick;
        this.active = active;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public String getBankId() {
        return bankId;
    }

    public void setBankId(String bankId) {
        this.bankId = bankId;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public long getIssuedTick() {
        return issuedTick;
    }

    public void setIssuedTick(long issuedTick) {
        this.issuedTick = issuedTick;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }
    public boolean hasPin() { return pinHash != null && !pinHash.isBlank(); }
    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }
    public boolean isUsable() { return active && !frozen; }
    public double getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(double dailyLimit) { this.dailyLimit = dailyLimit; }
    public double getSpentToday() { return spentToday; }
    public void setSpentToday(double spentToday) { this.spentToday = spentToday; }
    public long getLastSpendDay() { return lastSpendDay; }
    public void setLastSpendDay(long lastSpendDay) { this.lastSpendDay = lastSpendDay; }

    /** Reset the daily spend counter when the engine day changes. */
    public void rollDay(long day) {
        if (day != lastSpendDay) {
            lastSpendDay = day;
            spentToday = 0.0;
        }
    }

    /** 序列化到 NBT（BT-9）。 */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("cardNumber", cardNumber);
        tag.putString("bankId", bankId);
        tag.putUUID("ownerUuid", ownerUuid);
        tag.putLong("issuedTick", issuedTick);
        tag.putBoolean("active", active);
        if (pinHash != null) tag.putString("pinHash", pinHash);
        tag.putBoolean("frozen", frozen);
        tag.putDouble("dailyLimit", dailyLimit);
        tag.putDouble("spentToday", spentToday);
        tag.putLong("lastSpendDay", lastSpendDay);
        return tag;
    }

    /**
     * 从 NBT 恢复。旧存档缺字段用 contains 兜底为默认值，读档不失败（BT-9）。
     */
    public static BankCard load(CompoundTag tag) {
        BankCard card = new BankCard();
        if (tag == null) return card;
        if (tag.contains("cardNumber")) card.cardNumber = tag.getString("cardNumber");
        if (tag.contains("bankId")) card.bankId = tag.getString("bankId");
        if (tag.contains("ownerUuid")) card.ownerUuid = tag.getUUID("ownerUuid");
        if (tag.contains("issuedTick")) card.issuedTick = tag.getLong("issuedTick");
        if (tag.contains("active")) card.active = tag.getBoolean("active");
        if (tag.contains("pinHash")) card.pinHash = tag.getString("pinHash");
        if (tag.contains("frozen")) card.frozen = tag.getBoolean("frozen");
        if (tag.contains("dailyLimit")) card.dailyLimit = tag.getDouble("dailyLimit");
        if (tag.contains("spentToday")) card.spentToday = tag.getDouble("spentToday");
        if (tag.contains("lastSpendDay")) card.lastSpendDay = tag.getLong("lastSpendDay");
        return card;
    }
}
