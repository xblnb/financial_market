package com.example.financialmarket.engine.monetary;

/**
 * 最后贷款人救助登记（CENTRAL_BANK_SPEC.md v1.5 §6.10.2④ 公开登记环；CB-t1 冻结，<b>实现权归 CB-t3</b>）。
 *
 * <p>定位：把「救助」从「凭空造钱」（§1.6 P9）变成<b>有代价、可公开查询</b>的记录。
 * 每条救助写 1 条本记录进公开环（上限 {@link #LLR_RECORD_RING}），并同时写
 * {@link PolicyEvent#LAST_RESORT} 事件（CB-11 / CB-23）。
 *
 * <p><b>代价四件套（§6.10.2，逐条断言见 CB-23②）</b>
 * <ol>
 *   <li>抵押品折价：{@link #HAIRCUT} = 0.30 ⇒ 最多按抵押品 70% 放款；</li>
 *   <li>惩罚利率：{@code rate = SLF + }{@link #LLR_SPREAD}；</li>
 *   <li>放贷与分红受限：{@link TroubledBankState} 携带 {@code lendingCapMultiplier}/{@code dividendBlocked}
 *       （{@code RESCUED} ⇒ 8 倍上限 + 分红为 0）；</li>
 *   <li>公开登记：本记录本身（字段齐全、可读出）。</li>
 * </ol>
 *
 * <p><b>字段命名口径登记（不得静默）</b>：§6.10.2④ 写 {@code collateral}，§6.10.2① 的可观测写
 * {@code getLastResortRecords()[i].collateralValue}。本记录取 <b>{@code collateralValue}</b> 为组件名，
 * 并额外提供别名访问器 {@link #collateral()}，两种读法都能取到同一个数（CB-t7 探针按任一写法均可）。
 */
public record LastResortRecord(
        String bankId,
        double amount,
        double rate,
        double collateralValue,
        double haircut,
        long tick,
        int count,
        TroubledBankState stateBefore,
        TroubledBankState stateAfter,
        long deleveragingDeadlineTick) {

    /** 抵押品折价率（§6.10.2① {@code HAIRCUT}）。 */
    public static final double HAIRCUT = 0.30;
    /** 惩罚利率加点（§6.10.2② {@code LLR_SPREAD}；{@code LLR_RATE = SLF + 0.02}）。 */
    public static final double LLR_SPREAD = 0.02;
    /** 单行累计救助次数上限（§6.10.2，每年）。 */
    public static final int LLR_COUNT_MAX = 3;
    /** 公开登记环容量（§6.10.2④ {@code LLR_RECORD_RING}）。 */
    public static final int LLR_RECORD_RING = 64;
    /** 「连续 N 个交易日仍 ER < 0」⇒ {@code RESTRICTED}（§6.10.1）。 */
    public static final int RESTRICTED_CONSECUTIVE_DAYS = 2;
    /** 被救助行强制降杠杆持续交易日数（§6.10.2）。 */
    public static final int DELEVERAGING_DAYS = 30;
    /** 单次额度上限系数：{@code min(equity × 2, collateral × (1 − HAIRCUT))}（§6.10.2）。 */
    public static final double AMOUNT_EQUITY_MULTIPLE_MAX = 2.0;
    /** 单次额度上限系数：抵押品可放款比例 {@code (1 − HAIRCUT)}。 */
    public static final double COLLATERAL_LOAN_RATIO_MAX = 1.0 - HAIRCUT;
    /** 大额放贷登记阈值系数（§6.10.2③：{@code equity × 0.5}）。 */
    public static final double LARGE_LOAN_THRESHOLD_RATIO = 0.50;
    /** 问题银行观察门槛：不良率（§6.10.1 {@code NPL_WATCH}）。 */
    public static final double NPL_WATCH = 0.05;
    /** 问题银行观察门槛：{@code ER / RR} 比（§6.10.1 {@code ER_WATCH_RATIO}）。 */
    public static final double ER_WATCH_RATIO = 0.10;

    /**
     * 问题银行状态机（§6.10.1；六态）。
     *
     * <p>每个状态携带该状态下的<b>强制约束（数据表）</b>：放贷上限倍数与是否禁止分红，
     * 供 {@code Bank.getLendingCapMultiplier()}/{@code Bank.isDividendBlocked()}（CB-t2）与 CB-23② 断言直接查表。
     */
    public enum TroubledBankState {
        /** 健康：无额外约束（放贷上限 = 既有 {@code equity × 10}）。 */
        HEALTHY(10.0, false),
        /** 关注：{@code nplRatio ≥ 0.05} 或 {@code ER/RR < 0.10} ⇒ 放贷上限降至 8 倍。 */
        WATCH(8.0, false),
        /** 困境：银行间无可借且（{@code ER < 0} 或 {@code reserves < RR}）⇒ 放贷 6 倍 + 禁止分红 + 触发 LLR 评估。 */
        TROUBLED(6.0, true),
        /** 已救助：放贷 8 倍 + 禁止分红（代价四件套全部生效）。 */
        RESCUED(8.0, true),
        /** 受限：连续 2 日 {@code RESCUED} 且 {@code ER} 仍 {@code < 0} ⇒ 新增放贷 = 0、分红 = 0、登记升级。 */
        RESTRICTED(0.0, true),
        /** 待处置：{@code equity ≤ 0}；<b>不破产清算</b>（Q7 冻结），约束永久保留。 */
        RESOLVED(0.0, true);

        private final double lendingCapMultiplier;
        private final boolean dividendBlocked;

        TroubledBankState(double lendingCapMultiplier, boolean dividendBlocked) {
            this.lendingCapMultiplier = lendingCapMultiplier;
            this.dividendBlocked = dividendBlocked;
        }

        /** 该状态下的放贷上限倍数（{@code equity × multiplier}，§6.10.1）。 */
        public double lendingCapMultiplier() { return lendingCapMultiplier; }

        /** 该状态下是否禁止分红（§6.10.1/§6.10.2③）。 */
        public boolean dividendBlocked() { return dividendBlocked; }

        /** 状态总数（§6.10.1 冻结为 6 态）。 */
        public static int stateCount() { return values().length; }
    }

    /** 抵押品字段别名访问器（§6.10.2① 的可观测写法 {@code collateral}）。 */
    public double collateral() { return collateralValue; }

    // ===== CB-t3：判据助手（纯派生，确定性；供 CB-23② 逐条断言与 UI 展示）=====

    /** 该笔救助的抵押品折价额（{@code collateralValue × HAIRCUT}，即"被折掉"的部分）。 */
    public double haircutAmount() {
        return Math.max(0, collateralValue) * haircut;
    }

    /** 该笔救助的抵押率（{@code amount / collateralValue}；抵押品为 0 ⇒ 0，避免除零）。 */
    public double loanToCollateralRatio() {
        double collateral = Math.max(0, collateralValue);
        return collateral > 0 ? amount / collateral : 0;
    }

    /** 是否满足"贷款 ≤ 抵押品 × (1 − HAIRCUT)"（代价四件套① 的可机械复算判据）。 */
    public boolean respectsHaircut() {
        return amount <= Math.max(0, collateralValue) * COLLATERAL_LOAN_RATIO_MAX + 1e-9;
    }

    /** 是否单次额度在上限内（{@code ≤ min(equity×2, collateral×0.7)} 的抵押侧一半）。 */
    public boolean respectsCollateralCap() { return respectsHaircut(); }

    /** 是否构成"风险状态升级"（{@code stateBefore != stateAfter}）。 */
    public boolean isStateUpgrade() { return stateBefore != stateAfter; }

    /** 降杠杆是否在给定 tick 仍生效。 */
    public boolean isDeleveragingActive(long tick) { return tick < deleveragingDeadlineTick; }

    /** 单行摘要（中文，供命令/日志输出；不含换行，避免破坏表格归档）。 */
    public String summary() {
        return "银行=" + bankId + " 金额=" + amount + " 利率=" + rate + " 抵押=" + collateralValue
                + " 折价率=" + haircut + " tick=" + tick + " 第" + count + "次 "
                + stateBefore + "→" + stateAfter;
    }
}
