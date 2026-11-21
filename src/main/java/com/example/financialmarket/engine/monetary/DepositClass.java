package com.example.financialmarket.engine.monetary;

/**
 * 存款分类（CENTRAL_BANK_SPEC.md v1.5 §5.3 冻结枚举；CB-t1 冻结，<b>实现权归 CB-t2</b>）。
 *
 * <p>用途（§2 恒等式 I3/I4 + §5.4 达标存款基数）：把既有 {@code Bank} 的存款账本按「货币口径」分类，
 * 使 {@code M1}/{@code M2}/准货币可机械聚合与复算，并区分 1994 旧口径与 2025 新口径（Q2 裁决）。
 *
 * <p><b>冻结纪律（CB-t1 交接说明，后续任务必须遵守）</b>
 * <ul>
 *   <li>本枚举<b>只描述分类与口径</b>，不持有资金；分层账本仍落在既有 {@code Bank} 字段上（§5.1「不新建平行账本」）。</li>
 *   <li>既有字段映射（§5.3：<b>不改字段名、旧方法保留并委托</b>）：
 *       {@code totalDeposits = DEMAND_CORPORATE + DEMAND_HOUSEHOLD}；
 *       {@code timeDeposits = TIME_CORPORATE}；{@code moneyMarketDeposits = OTHER_MONEY_MARKET}；
 *       {@code savingsHousehold}/{@code paymentReserve} 为 CB-t2 新增层（旧档缺失 → 0，{@code contains} 兜底）。</li>
 *   <li>旧档迁移（§9.1 银行行）：无 household 分类 ⇒ 全部记 {@code DEMAND_CORPORATE} = <b>1994 旧口径</b>，
 *       {@code DEMAND_HOUSEHOLD = savingsHousehold = paymentReserve = 0}。</li>
 *   <li>「支付机构客户备付金」同时计入 M1（I3）与 B 的 {@code Pay} 项（I1），但<b>不计入</b>达标存款基数（§5.4）。</li>
 * </ul>
 *
 * <p>本类为纯数据枚举（零逻辑、零随机、无持久化）。
 */
public enum DepositClass {

    /** 单位活期：1994 旧口径与 2025 新口径<b>都</b>计入 M1；既有 {@code totalDeposits} 的默认归属。 */
    DEMAND_CORPORATE(true, true, true, true, "totalDeposits"),

    /** 个人活期：仅 2025 新口径计入 M1（Q2）；CB-t2 新增层，旧档为 0。 */
    DEMAND_HOUSEHOLD(true, true, true, false, null),

    /** 单位定期：M2 非 M1；既有 {@code timeDeposits}。 */
    TIME_CORPORATE(false, true, true, false, "timeDeposits"),

    /** 个人储蓄：M2 非 M1（与既有 {@code timeDeposits} 分列）；CB-t2 新增层 {@code savingsHousehold}。 */
    SAVINGS_HOUSEHOLD(false, true, true, false, null),

    /** 其他存款 / 货币市场类：M2 非 M1；既有 {@code moneyMarketDeposits} 的语义别名。 */
    OTHER_MONEY_MARKET(false, true, true, false, "moneyMarketDeposits"),

    /** 支付机构客户备付金：2025 新口径计入 M1；同时计入 B 的 {@code Pay} 项；<b>不</b>计入达标存款基数（§5.4）。 */
    PAYMENT_RESERVE(true, true, false, false, null);

    /** 分类总数（§5.3 冻结为 6 类）。 */
    public static final int CLASS_COUNT = 6;

    // ===== 分组名单（CB-t2 实现：由上面的口径标志派生，供聚合器/UI/验收/迁移枚举；纯数据、无逻辑）=====

    /** 计入 M1（2025 新口径）的分类：单位活期、个人活期、支付机构客户备付金。 */
    public static final java.util.List<DepositClass> M1_CLASSES = java.util.List.of(
            DEMAND_CORPORATE, DEMAND_HOUSEHOLD, PAYMENT_RESERVE);

    /** 计入 M2 的分类（= 全部 6 类，M2 ⊇ M1）。 */
    public static final java.util.List<DepositClass> M2_CLASSES = java.util.List.of(
            DEMAND_CORPORATE, DEMAND_HOUSEHOLD, TIME_CORPORATE, SAVINGS_HOUSEHOLD, OTHER_MONEY_MARKET,
            PAYMENT_RESERVE);

    /** 计入达标存款基数的分类（§5.4：五类，<b>不含</b> PAYMENT_RESERVE）。 */
    public static final java.util.List<DepositClass> RESERVE_BASE_CLASSES = java.util.List.of(
            DEMAND_CORPORATE, DEMAND_HOUSEHOLD, TIME_CORPORATE, SAVINGS_HOUSEHOLD, OTHER_MONEY_MARKET);

    /** 计入 1994 旧口径 M1 的分类（仅单位活期）。 */
    public static final java.util.List<DepositClass> LEGACY_1994_M1_CLASSES = java.util.List.of(DEMAND_CORPORATE);

    private final boolean m1Component;
    private final boolean m2Component;
    private final boolean reserveBaseComponent;
    private final boolean m1ComponentLegacy1994;
    private final String legacyBankField;

    DepositClass(boolean m1Component, boolean m2Component, boolean reserveBaseComponent,
                 boolean m1ComponentLegacy1994, String legacyBankField) {
        this.m1Component = m1Component;
        this.m2Component = m2Component;
        this.reserveBaseComponent = reserveBaseComponent;
        this.m1ComponentLegacy1994 = m1ComponentLegacy1994;
        this.legacyBankField = legacyBankField;
    }

    /** 是否计入 M1（2025 新口径，§2 I3）。 */
    public boolean isM1Component() { return m1Component; }

    /** 是否计入 M2（§2 I4；M2 ⊇ M1 ⇒ 计入 M1 者必然计入 M2）。 */
    public boolean isM2Component() { return m2Component; }

    /** 是否计入达标存款基数 {@code reserveBase}（§5.4：五类存款，<b>不含</b> PAYMENT_RESERVE）。 */
    public boolean isReserveBaseComponent() { return reserveBaseComponent; }

    /** 是否计入 1994 旧口径 M1（§2 I3：仅 {@code M0 + 单位活期}）。 */
    public boolean isM1ComponentLegacy1994() { return m1ComponentLegacy1994; }

    /** 既有 {@code Bank} 字段名（§5.3 映射）；{@code null} = CB-t2 新增层（旧档无此字段）。 */
    public String getLegacyBankField() { return legacyBankField; }
}
