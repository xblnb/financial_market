package com.example.financialmarket.engine.banking;

import com.example.financialmarket.engine.random.SeedableRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 储蓄卡签发/查询/撤销/校验服务（BT-2/BT-3）。
 *
 * <p>卡号 16 位、Luhn 合法，与 {@code ownerUuid} 唯一绑定（{@code byNumber}/{@code ownerToNumber}
 * 双向映射，每人默认 1 张）。卡号生成走 {@link SeedableRandom} 子流
 * （同 {@code engineSeed} + 同玩家 + 同开户行 + 同签发 tick → 同卡号，BT-1/BT-3）。
 *
 * <p>持久化 {@link #save()}/{@link #load} 随 {@link com.example.financialmarket.engine.MarketEngine}
 * 引擎 blob（H2 {@code engine_kv.engine_main} + NBT 镜像）双写；旧存档缺字段用 contains 兜底为空表（BT-9）。
 */
public class BankCardService {

    /** 卡号长度（含 Luhn 校验位）。 */
    public static final int CARD_DIGITS = 16;
    /** 每玩家默认最多 1 张储蓄卡（可调）。 */
    public static final int MAX_CARDS_PER_PLAYER = 1;
    /** 生成卡号时为避免碰撞的最大重试次数（确定性序列，无随机偏差）。 */
    private static final int MAX_COLLISION_RETRY = 8;

    private final SeedableRandom rng;
    private final Map<String, BankCard> byNumber = new LinkedHashMap<>();
    private final Map<UUID, String> ownerToNumber = new HashMap<>();
    private Set<String> validBankIds = new HashSet<>();

    /**
     * @param seed 引擎种子派生子流（P0 §6 模式），保证同种子同行为
     */
    public BankCardService(long seed) {
        this.rng = new SeedableRandom(seed);
    }

    /** 设置可开户行集合（默认 4 家商业银行，BT-2）；确定性、无随机。 */
    public void setValidBankIds(Collection<String> ids) {
        this.validBankIds = new HashSet<>(ids == null ? Collections.emptyList() : ids);
    }

    public boolean isKnownBank(String bankId) {
        return bankId != null && validBankIds.contains(bankId);
    }

    /**
     * 玩家向某行办储蓄卡。
     *
     * @param ownerUuid  持卡玩家 UUID（唯一绑定）
     * @param bankId     开户行（须为已知默认银行）
     * @param issuedTick 签发时的引擎 tick（确定性，用于可复现/排序）
     * @return 成功返回 16 位卡号；已持卡、未知银行或极端碰撞返回 {@code null}
     */
    public String issueCard(UUID ownerUuid, String bankId, long issuedTick) {
        if (ownerUuid == null || !isKnownBank(bankId)) return null;
        if (hasActiveCard(ownerUuid)) return null; // 每人默认 1 张（MAX_CARDS_PER_PLAYER）
        String number = genCardNumber(ownerUuid, bankId, issuedTick);
        if (number == null) return null; // 极端碰撞重试耗尽
        BankCard card = new BankCard(number, bankId, ownerUuid, issuedTick, true);
        byNumber.put(number, card);
        ownerToNumber.put(ownerUuid, number);
        return number;
    }

    /** 玩家是否持有有效卡（门控用，BT-3/BT-4）。 */
    public boolean hasActiveCard(UUID ownerUuid) {
        if (ownerUuid == null) return false;
        String num = ownerToNumber.get(ownerUuid);
        if (num == null) return false;
        BankCard card = byNumber.get(num);
        return card != null && card.isActive();
    }

    /** 按卡号查卡（含已撤销卡，用于卡号归属校验/真伪查询）。 */
    public BankCard getCardByNumber(String cardNumber) {
        if (cardNumber == null) return null;
        return byNumber.get(cardNumber);
    }

    /** 查询某玩家的有效卡（若已撤销则返回 null）。 */
    public BankCard getCardOf(UUID ownerUuid) {
        if (ownerUuid == null) return null;
        String num = ownerToNumber.get(ownerUuid);
        if (num == null) return null;
        BankCard card = byNumber.get(num);
        return (card != null && card.isActive()) ? card : null;
    }

    /** 查玩家开户行（无卡/已撤销返回 null）。 */
    public String getBankOf(UUID ownerUuid) {
        BankCard card = getCardOf(ownerUuid);
        return card != null ? card.getBankId() : null;
    }

    /** Set a 4-6 digit PIN; stored as a salted SHA-256 hash, never in plain text. */
    public boolean setPin(UUID ownerUuid, String pin) {
        BankCard card = getCardOf(ownerUuid);
        if (card == null || !validPin(pin)) return false;
        card.setPinHash(hashPin(card.getCardNumber(), pin));
        return true;
    }

    public boolean verifyPin(BankCard card, String pin) {
        if (card == null || !card.hasPin() || !validPin(pin)) return false;
        byte[] expected = card.getPinHash().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actual = hashPin(card.getCardNumber(), pin)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(expected, actual);
    }

    public boolean freeze(UUID ownerUuid) {
        BankCard card = getCardOf(ownerUuid);
        if (card == null) return false;
        card.setFrozen(true);
        return true;
    }

    public boolean unfreeze(UUID ownerUuid) {
        BankCard card = getCardOf(ownerUuid);
        if (card == null || !card.isActive()) return false;
        card.setFrozen(false);
        return true;
    }

    public static boolean validPin(String pin) {
        return pin != null && pin.matches("\\d{4,6}");
    }

    private static String hashPin(String cardNumber, String pin) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest((cardNumber + ":" + pin)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** For API/UI: first 4 + last 4 only. */
    public static String mask(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 8) return "****";
        return cardNumber.substring(0, 4) + " **** **** "
                + cardNumber.substring(cardNumber.length() - 4);
    }

    /**
     * 撤销玩家卡（可重新办，确定性）。
     *
     * @return 成功撤销返回 {@code true}；无卡返回 {@code false}
     */
    public boolean revokeCard(UUID ownerUuid) {
        if (ownerUuid == null) return false;
        BankCard card = getCardOf(ownerUuid);
        if (card == null) return false;
        card.setActive(false);
        ownerToNumber.remove(ownerUuid);
        return true;
    }

    /** 全部卡（含已撤销），只读视图。 */
    public Collection<BankCard> getAllCards() {
        return Collections.unmodifiableCollection(byNumber.values());
    }

    /**
     * 生成 16 位 Luhn 合法卡号（确定性，SeedableRandom 子流）。
     *
     * <p>密钥 = mix64(ownerUuid 高低位 ^ bankId.hashCode ^ (issuedTick × 黄金比) ^ rng.nextLong())，
     * 派生子流后取 15 位前缀（1 位 BIN 标识 + 6 位发卡行段 + 8 位随机段），末位为 Luhn 校验位。
     */
    private String genCardNumber(UUID ownerUuid, String bankId, long issuedTick) {
        for (int attempt = 0; attempt < MAX_COLLISION_RETRY; attempt++) {
            long s = SeedableRandom.mix64(
                    ownerUuid.getMostSignificantBits()
                            ^ ownerUuid.getLeastSignificantBits()
                            ^ bankId.hashCode()
                            ^ (issuedTick * 0x9E3779B97F4A7C15L)
                            ^ rng.nextLong());
            SeedableRandom sub = new SeedableRandom(s);
            String prefix = binPrefix(bankId) + digits(sub, 8); // 7 + 8 = 15 位
            String number = Luhn.appendCheckDigit(prefix);      // 第 16 位 = Luhn 校验位
            if (!byNumber.containsKey(number)) return number;
        }
        return null;
    }

    /** 前 1 位 BIN 标识（1=储蓄/借记）+ 6 位发卡行段，确定性映射，无随机。 */
    private static String binPrefix(String bankId) {
        return "1" + issuerSegment(bankId);
    }

    /** 6 位发卡行段（确定性映射，同 bankId 恒同段）。 */
    private static String issuerSegment(String bankId) {
        switch (bankId) {
            case "BANK_A": return "622201";
            case "BANK_B": return "622202";
            case "BANK_C": return "622203";
            case "BANK_D": return "622204";
            default:
                long h = (bankId.hashCode() & 0x7fffffffL) % 1000000L;
                return String.format("%06d", h);
        }
    }

    /** 用 SeedableRandom 子流生成 n 位 0-9 数字（取正后 %10 逐位，确定性）。 */
    private static String digits(SeedableRandom sub, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('0' + (sub.nextLong() & Long.MAX_VALUE) % 10L));
        }
        return sb.toString();
    }

    // ===================== 持久化（BT-9） =====================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("rngState", rng.getState()); // 读档后签发序列继续可复现
        ListTag list = new ListTag();
        for (BankCard card : byNumber.values()) {
            list.add(card.save());
        }
        tag.put("cards", list);
        return tag;
    }

    /**
     * 从 NBT 恢复卡注册表。旧存档缺 "cards"/字段用 contains 兜底为空表，读档不失败（BT-9）。
     */
    public void load(CompoundTag tag) {
        byNumber.clear();
        ownerToNumber.clear();
        if (tag == null) return;
        if (tag.contains("cards")) {
            ListTag list = tag.getList("cards", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                BankCard card = BankCard.load(list.getCompound(i));
                if (card.getCardNumber() == null || card.getOwnerUuid() == null) continue;
                byNumber.put(card.getCardNumber(), card);
                if (card.isActive()) {
                    ownerToNumber.put(card.getOwnerUuid(), card.getCardNumber());
                }
            }
        }
        if (tag.contains("rngState")) {
            rng.setState(tag.getLong("rngState"));
        }
    }
}
