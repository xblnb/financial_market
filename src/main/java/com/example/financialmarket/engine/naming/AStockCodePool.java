package com.example.financialmarket.engine.naming;

import com.example.financialmarket.engine.random.SeedableRandom;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * A股共享代码池（N-1 / N-3）。
 *
 * <p>这是 AI 公司 A 股代码（{@code AICompanyData.cnCode}）与玩家公司股票代码
 * （{@code CompanyData.stockId}）<b>共用的唯一命名空间</b>：两者都从本池取 6 位 A 股代码，
 * 互不冲突。代码严格符合 A 股代码段规则：
 * <ul>
 *   <li>沪市主板 {@code 600 / 601 / 603 / 605}</li>
 *   <li>深市主板 {@code 000 / 001 / 002 / 003}</li>
 *   <li>创业板 {@code 300 / 301}</li>
 *   <li>科创板 {@code 688 / 689}</li>
 *   <li>北交所 {@code 8xxxxx}（{@code 8} 开头 6 位）</li>
 * </ul>
 *
 * <p>确定性：分配用 {@link SeedableRandom} 子流（由 engineSeed 派生），禁裸随机；
 * 同一 engineSeed、同一分配顺序 → 同一代码序列。已占用集合 {@link #used} 由已加载数据
 * （AI {@code cnCode} ∪ 玩家 {@code stockId}）重建，无需新增持久化字段即可跨读档保证不冲突。
 */
public class AStockCodePool {

    /** A 股代码段（前 3 位为段前缀；北交所 {@code BJ8} 为 {@code 8} 开头 6 位）。 */
    public enum Segment {
        SH600("600", 3),
        SH601("601", 3),
        SH603("603", 3),
        SH605("605", 3),
        SZ000("000", 3),
        SZ001("001", 3),
        SZ002("002", 3),
        SZ003("003", 3),
        CY300("300", 3),
        CY301("301", 3),
        KC688("688", 3),
        KC689("689", 3),
        BJ8("8", 5);

        private final String prefix;
        private final int seqWidth; // 序号位宽 = 6 - prefix.length()（北交所为 5 位序号）

        Segment(String prefix, int seqWidth) {
            this.prefix = prefix;
            this.seqWidth = seqWidth;
        }

        public String getPrefix() { return prefix; }
        public int getSeqWidth() { return seqWidth; }
    }

    private static final int CODE_LENGTH = 6;
    private static final Segment[] SEGMENTS = Segment.values();

    private final Set<String> used = new HashSet<>();
    private final SeedableRandom rng;

    public AStockCodePool(SeedableRandom rng) {
        this.rng = rng;
    }

    /** 判定是否为合法 A 股代码：6 位数字且前缀在段表内。 */
    public static boolean isValid(String code) {
        if (code == null || code.length() != CODE_LENGTH) return false;
        for (int i = 0; i < code.length(); i++) {
            if (!Character.isDigit(code.charAt(i))) return false;
        }
        for (Segment s : SEGMENTS) {
            if (code.startsWith(s.prefix)) return true;
        }
        return false;
    }

    /** 登记已占用代码（AI cnCode 与玩家 stockId 共用命名空间）。 */
    public void markUsed(String code) {
        if (code != null && code.length() == CODE_LENGTH) {
            used.add(code);
        }
    }

    /** 是否已占用。 */
    public boolean contains(String code) {
        return code != null && used.contains(code);
    }

    /** 跨 AI 与玩家统一判重。 */
    public boolean isTaken(String code) {
        return contains(code);
    }

    /**
     * 确定性分配一个未占用合法代码：用 {@link #rng} 选起始段，段内取当前最小未占用序号，
     * 段占满则按段表顺序探测下一段；保证返回值 {@link #isValid} 且未占用。
     */
    public String allocateCode() {
        int start = rng.nextInt(SEGMENTS.length);
        for (int off = 0; off < SEGMENTS.length; off++) {
            Segment seg = SEGMENTS[(start + off) % SEGMENTS.length];
            String candidate = lowestUnusedIn(seg);
            if (candidate != null) {
                used.add(candidate);
                return candidate;
            }
        }
        throw new IllegalStateException("A股代码池已满，无法分配新代码");
    }

    /** 段内取「当前未占用」的最小序号；段满返回 null。 */
    private String lowestUnusedIn(Segment seg) {
        int max = 1;
        for (int i = 0; i < seg.seqWidth; i++) max *= 10;
        for (int seq = 0; seq < max; seq++) {
            String code = seg.prefix + String.format("%0" + seg.seqWidth + "d", seq);
            if (!used.contains(code)) return code;
        }
        return null;
    }

    /**
     * 用给定代码集合<b>重建</b>已占用集合（读档时由 AI cnCode ∪ 玩家 stockId 重建，
     * 跨读档不冲突；不存独立快照，故无需新增持久化字段）。
     */
    public void rebuildUsed(Collection<String> codes) {
        used.clear();
        if (codes != null) {
            for (String c : codes) {
                if (c != null && c.length() == CODE_LENGTH) {
                    used.add(c);
                }
            }
        }
    }

    /** 已占用代码数量（诊断用）。 */
    public int size() { return used.size(); }
}
