package com.example.financialmarket.engine.monetary;

/**
 * 基础货币流动（CENTRAL_BANK_SPEC.md v1.5 §5.2 record + §6.1 流动表；CB-t1 冻结，<b>CB-t3 已实现记账表</b>）。
 *
 * <p>定位：<b>唯一</b>的 {@code baseMoney} 变更入口。每笔操作必须成对记账（资产侧 + 负债侧 / I1 右侧项间转移），
 * 杜绝既有「只改一个数」的单边记账（§1.6 P2；CB-18①②「逐 Kind 双向记账表」）。
 *
 * <p><b>类内约定（冻结）</b>
 * <ul>
 *   <li>{@code amount > 0}：方向由 {@link Kind} 与 {@link Category} 决定；非正数由
 *       {@code CentralBankBalanceSheet.bookBaseMoneyFlow} 拒绝（CB-t3 已落地）。</li>
 *   <li>{@code counterpartyId}：对手方（银行 ID / 政府 / {@code "PUBLIC"}）；无对手方时为 {@code null}。</li>
 *   <li>{@code tick}：引擎 tickCounter（确定性时间戳，日志/复算用；非交易日的「日」口径由调用方换算）。</li>
 *   <li>{@link Kind} 的 {@code assetSide}/{@code liabilitySide} 字段是 §6.1 流动表的<b>逐字落地映射</b>（纯数据，非逻辑），
 *       供 CB-t3 实现查表、CB-t7 逐 Kind 断言使用；CB-t3 另加了 {@link Book} 记账表（最小时机可落的
 *       「资产方向 + I1 右侧落点 + 储备货币方向」三元组），使记账不需要 23 个 {@code if} 分支。</li>
 * </ul>
 *
 * <p><b>★CB-18① 计数口径登记（不得静默）</b>：规格 §5.2 的 {@code Kind} 枚举实测为
 * <b>23 个取值</b>（CB-t1 逐字落地 23 个）；CB-18① 与任务卡中所写的「18 个取值」是 v1.0 旧计数，
 * 其枚举列举（{@code INJECT_CASH / OMO_REPO / … / CANCEL / WRITE_OFF}）是这 23 个的<b>子集</b>。
 * ⇒ 「18 个取值全覆盖」<b>更强成立</b>；对比见 {@code .verify/CB_INTERFACE_FREEZE.md} §4。
 * 若 architect（CB-t0）后续把 §5.2 改写为 18 个，则属口径变更，须走规格偏离登记（本类不改）。
 *
 * <p><b>★CB-T26 计数变更登记（23 → 25，不得静默）</b>：新增
 * {@link Kind#CBDC_ISSUE}/{@link Kind#CBDC_REDEEM} 两个 <b>CBDC 配对记账</b>类别
 * （根除"CBDC 是唯一可绕过 {@code bookBaseMoneyFlow} 单一入口"的通道，见 §11 CB-15 有效变体）。
 * ⇒ {@link Kind#kindCount()} 由 <b>23 变为 25</b>，这是<b>口径变更</b>而非回归：
 * <ul>
 *   <li>规格 §5.2 的 Kind 计数须由 architect 同步为 25（属 t25/t27 规格收口范围，<b>本类只如实登记</b>）；</li>
 *   <li>此处"23"是 <b>v1.6c 及以前的实测值</b>，保留原文以免历史引用悬空；现行值以
 *       {@link Kind#kindCount()} 为准；</li>
 *   <li>新增两类<b>不改任何既有 Kind 的记账映射</b>（CB-18① 的逐 Kind 双向记账表逐字未动），
 *       也不改任何判据阈值。</li>
 * </ul>
 */
public record BaseMoneyFlow(Kind kind, double amount, String counterpartyId, long tick) {

    /** 流动类别：决定「是否改变 B 总量」（§6.1 的四段分类）。 */
    public enum Category {
        /** 投放 / 回笼：改变基础货币总量 B（I1 左侧与右侧同时变动）。 */
        INJECTION_OR_RETIREMENT(true),
        /** I1 内部转移：B 总量不变，只在 {@code C / Vc / RR / ER / Pay} 之间搬家。 */
        INTERNAL_I1_TRANSFER(false),
        /** 负债侧项间：B 总量不变（{@code govDeposits} ↔ {@code C}/{@code ER}，RR↔ER）。 */
        LIABILITY_SIDE_TRANSFER(false),
        /** 配平 / 减记：不改 B 总量，只改资产侧或由 {@code ownCapital} 承担损益（含黄金科目）。 */
        BALANCING_OR_WRITE_OFF(false);

        private final boolean changesBaseMoneyTotal;

        Category(boolean changesBaseMoneyTotal) { this.changesBaseMoneyTotal = changesBaseMoneyTotal; }

        /** 该类别是否改变 B 总量（I1 判据用）。 */
        public boolean changesBaseMoneyTotal() { return changesBaseMoneyTotal; }
    }

    /**
     * I1 右侧分项（§2 I1：{@code B = C + Vc + RR + ER + Pay}）。
     *
     * <p>{@link BaseMoneyFlow.Kind} 的 {@code book} 字段用它声明「本笔流动把价值搬到哪个分项」，
     * 使 CB-t3 的 {@code bookBaseMoneyFlow} 能<b>逐 Kind 查表</b>而不用 23 个 {@code if} 分支
     * （CB-18① 的「逐 Kind 双向记账表」）。
     *
     * <p>{@link #inRightSideSum()} 标注该分项是否计入 I1 右侧之和（{@code NONE} 与
     * {@code SETTLEMENT_DEBT} 不计入）。
     */
    public enum Component {
        /** C：流通中现金（非银行部门持有）。 */
        CIRCULATION_CASH("C 流通中现金", true),
        /** Vc：金融机构库存现金。 */
        VAULT_CASH("Vc 金融机构库存现金", true),
        /** RR：法定准备金。 */
        REQUIRED_RESERVES("RR 法定准备金", true),
        /** ER：超额准备金。 */
        EXCESS_RESERVES("ER 超额准备金", true),
        /** Pay：支付机构客户备付金。 */
        PAYMENT_RESERVES("Pay 支付机构客户备付金", true),
        /** 不落在 I1 右侧分项（纯资产侧/负债侧科目变动）。 */
        NONE("—（不涉及 I1 右侧分项）", false),
        /**
         * I9 兜底：结算欠款（银行准备金不足时的对央行欠款）。
         *
         * <p>不是 I1 右侧分项（不计入 {@code B} 分解），而是<b>负债侧科目</b>
         * （{@code financialCorpDepositsNonReserve}，不计入储备货币的金融性公司存款）——
         * 用于「缺口不得让 {@code ER < 0}」时的双向配平（CB-5/CB-18）。
         */
        SETTLEMENT_DEBT("settlementDebt 结算欠款（I9 兜底）", false),
        /**
         * ★CB-T26：数字人民币负债（{@code CbDc}）。
         *
         * <p>是 {@code C} 的<b>构成分项</b>（{@code C = Cnote + CbDc}），但<b>不是</b>
         * {@code bookBaseMoneyFlow} 的落点分项 —— CBDC 的投放/回笼走 {@code Component#NONE}
         * 的"构成替代"路径（见 {@link Kind#CBDC_ISSUE}），由央行负债科目
         * {@code cbdcIssued} 承载，故 {@code inRightSideSum = false}。
         *
         * <p>登记理由（不得静默）：若把它算进本表的右侧之和，就会与央行账本
         * （{@code circulationCash} = Cnote 口径）重复计一次 CbDc ⇒ 制造 I10 假偏差。
         */
        CBDC_LIABILITY("CbDc 数字人民币（央行负债；C 的构成项，非 Cnote 口径账本分项）", false);

        private final String label;
        private final boolean inRightSideSum;

        Component(String label, boolean inRightSideSum) {
            this.label = label;
            this.inRightSideSum = inRightSideSum;
        }

        /** 中文标签（报表/事件用）。 */
        public String label() { return label; }

        /** 是否计入 I1 右侧之和（{@code C + Vc + RR + ER + Pay}）。 */
        public boolean inRightSideSum() { return inRightSideSum; }
    }

    /**
     * 标量口径：本笔流动是否改变央行的一义标量 {@code getBaseMoney()}。
     *
     * <p>与 {@link Category#changesBaseMoneyTotal()} 的区别（<b>口径登记，不得静默</b>）：
     * <ul>
     *   <li>{@link #CHANGES_SCALAR}：既改 {@code getBaseMoney()} 标量、也改账本科目（如 {@code OMO_REPO}）；</li>
     *   <li>{@link #PRESERVES_SCALAR}：B 总量不变（I1 内部转移 / 负债侧项间 / 加降准）；</li>
     *   <li>{@link #LEDGER_ONLY}：<b>只写账本、不改标量</b>。用于既有语义明确「不动 {@code baseMoney}」的路径
     *       （{@code GOLD_BUY}/{@code GOLD_SELL}，§6.1 明示）以及财政镜像（{@code FISCAL_SPEND}/{@code TAX_COLLECT}，
     *       §7.2 C6「镜像 {@code Government.treasury}，不双写」）。<b>若把这两类也算作标量变更，
     *       会污染既有 M2/黄金数值链（违反 N1/N7 不回归锚点）</b>。</li>
     * </ul>
     */
    public enum ScalarEffect {
        /** 改标量（投放/回笼类）。 */
        CHANGES_SCALAR,
        /** 标量不变（B 总量不变类）。 */
        PRESERVES_SCALAR,
        /** 只写账本、不动标量（黄金既有语义 + 财政镜像）。 */
        LEDGER_ONLY
    }

    /**
     * 逐 Kind 的双向记账表（§6.1，「资产侧 + 负债侧/I1 右侧项间」的最小机械描述）。
     *
     * <p>该表是「资产侧 + 负债侧」的<b>机械落地</b>：{@code assetDelta} 给出资产侧方向、
     * {@code component}/{@code componentSign} 给出 I1 右侧分项的落点、
     * {@code reserveMoneySign} 给出储备货币（= B 的负债侧镜像）的变动方向。
     * 三者由 {@link Kind#bookedAmount(double)} / {@link Kind#componentDelta(double)} 换算成实数。
     *
     * @param assetDelta      资产侧科目的增量方向（+1 增 / −1 减 / 0 不变；具体科目由 {@code Kind} 决定）
     * @param component       本笔流动搬动的 I1 右侧分项（{@link Component#NONE} = 不涉及）
     * @param componentSign   分项相对 {@code amount} 的符号（+1/−1；{@code NONE} 时为 0）
     * @param reserveMoneySign 储备货币（{@code reserveMoney} = B 的负债侧镜像）相对 {@code amount} 的符号
     *                         （+1 投放 / −1 回笼 / 0 不变）
     * @param ledgerOnly      是否只写账本、不动 {@code getBaseMoney()} 标量（黄金既有语义 + 财政镜像）
     * @param note            该 Kind 的口径备注（含 I9 兜底与「不改 baseMoney」等硬约束）
     */
    public record Book(int assetDelta, Component component, double componentSign, double reserveMoneySign,
                       boolean ledgerOnly, String note) {

        /** 本笔流动的标量口径（{@link ScalarEffect}；数据判定，非散落的 {@code if}）。 */
        public ScalarEffect scalarEffect() {
            if (ledgerOnly) return ScalarEffect.LEDGER_ONLY;
            if (reserveMoneySign != 0) return ScalarEffect.CHANGES_SCALAR;
            return ScalarEffect.PRESERVES_SCALAR;
        }
    }

    /**
     * 流动种类（§5.2 权威枚举；CB-t1 逐字保留规格命名与顺序，CB-T26 追加 2 个 CBDC 类别 ⇒ 实测 25）。
     * 每个取值的 {@code assetSide}/{@code liabilitySide} 为 §6.1 流动表的记账映射（数据），
     * {@code book} 为 CB-t3 记账用的机械表。
     *
     * <p>计数变更的逐字登记见类注释「★CB-T26 计数变更登记（23 → 25）」。
     */
    public enum Kind {
        // —— 投放 / 回笼（改变 B 总量）——
        /** 发行现金（新玩家入金）。 */
        INJECT_CASH(Category.INJECTION_OR_RETIREMENT, "otherAssets += a", "C += a",
                new Book(1, Component.CIRCULATION_CASH, 1, 1, false,
                        "投放：资产 otherAssets 与 C 同增；发行即负债 reserveMoney 同增")),
        /** 逆回购投放（既有 {@code CentralBank.reverseRepo}）。 */
        OMO_REPO(Category.INJECTION_OR_RETIREMENT, "claimsOnOtherDepositoryCorps += a", "ER_counterparty += a",
                new Book(1, Component.EXCESS_RESERVES, 1, 1, false,
                        "OMO 投放：对存款性公司债权与对手行 ER 同增")),
        /** 逆回购到期回笼（既有 {@code settleRepos} 的缺口兜底修复）。 */
        OMO_REPO_MATURITY(Category.INJECTION_OR_RETIREMENT, "claimsOnOtherDepositoryCorps -= a",
                "ER_counterparty -= a；不足 → settlementDebt_counterparty += 缺口",
                new Book(-1, Component.EXCESS_RESERVES, -1, -1, false,
                        "OMO 到期：债权减少、ER 回笼；ER 不足走 settlementDebt（禁止 ER < 0）")),
        /** 二级市场买入国债（向政府直购仅 Q6 通道，受 {@code centralBank.fiscalDominance} 门控）。 */
        GOV_BOND_BUY(Category.INJECTION_OR_RETIREMENT, "claimsOnGovernment += a",
                "ER_sellerBank += a；直购政府 → govDeposits += a",
                new Book(1, Component.EXCESS_RESERVES, 1, 1, false,
                        "二级市场买入：对政府债权与卖出方 ER 同增；直购政府走 govDeposits（Q6 门控）")),
        /** 卖出国债（既有 {@code sellBonds}）。 */
        GOV_BOND_SELL(Category.INJECTION_OR_RETIREMENT, "claimsOnGovernment -= a", "ER_bank -= a",
                new Book(-1, Component.EXCESS_RESERVES, -1, -1, false,
                        "卖出：对政府债权与买方 ER 同减")),
        /** 中期借贷便利投放（期限 360 交易日）。 */
        MLF_LEND(Category.INJECTION_OR_RETIREMENT, "claimsOnOtherDepositoryCorps += a", "ER_counterparty += a",
                new Book(1, Component.EXCESS_RESERVES, 1, 1, false,
                        "MLF 投放：与 OMO_REPO 同族，利率 = policyRate + MLF_SPREAD")),
        /** 中期借贷便利到期回笼。 */
        MLF_MATURITY(Category.INJECTION_OR_RETIREMENT, "claimsOnOtherDepositoryCorps -= a",
                "ER_counterparty -= a；不足 → settlementDebt_counterparty += 缺口",
                new Book(-1, Component.EXCESS_RESERVES, -1, -1, false, "MLF 到期回笼；ER 不足走 settlementDebt")),
        /** 常备借贷便利（走廊上限工具，银行主动借入）。 */
        SLF_LEND(Category.INJECTION_OR_RETIREMENT, "claimsOnOtherDepositoryCorps += a", "ER_counterparty += a",
                new Book(1, Component.EXCESS_RESERVES, 1, 1, false, "SLF 借出：走廊上限工具，利率 = SLF")),
        /** 最后贷款人（§6.10 条款化：抵押/罚息/受限/公开登记）。 */
        LAST_RESORT(Category.INJECTION_OR_RETIREMENT, "claimsOnOtherDepositoryCorps += a", "ER_counterparty += a",
                new Book(1, Component.EXCESS_RESERVES, 1, 1, false,
                        "最后贷款人：带抵押/罚息/受限/公开登记（§6.10.2 代价四件套）")),
        /** 发行央票（负债侧工具，回笼准备金）。 */
        ISSUE_BILL(Category.INJECTION_OR_RETIREMENT, "—（负债侧工具，不改资产）",
                "centralBankBills += a；ER_bank -= a",
                new Book(0, Component.EXCESS_RESERVES, -1, -1, false,
                        "央票发行：负债侧工具，回笼准备金、储备货币下降（B ↓）")),
        /** 央票到期兑付。 */
        REDEEM_BILL(Category.INJECTION_OR_RETIREMENT, "—（负债侧工具，不改资产）",
                "centralBankBills -= a；ER_bank += a",
                new Book(0, Component.EXCESS_RESERVES, 1, 1, false,
                        "央票到期：兑付释放准备金、储备货币回升（B ↑）")),

        // —— I1 内部转移（B 总量不变）——
        /** 银行从央行提现：准备金 → 库存现金。 */
        BANK_CASH_WITHDRAW(Category.INTERNAL_I1_TRANSFER, "—（B 总量不变）", "ER_bank -= a；Vc += a",
                new Book(0, Component.VAULT_CASH, 1, 0, false, "I1 内部转移：ER → Vc，储备货币不变")),
        /** 银行向央行缴现：库存现金 → 准备金。 */
        BANK_CASH_DEPOSIT(Category.INTERNAL_I1_TRANSFER, "—（B 总量不变）", "Vc -= a；ER_bank += a",
                new Book(0, Component.EXCESS_RESERVES, 1, 0, false, "I1 内部转移：Vc → ER，储备货币不变")),
        /** 非银行部门取现：银行存款 → 流通现金（M0 与存款构成同时变化）。 */
        NONBANK_CASH_WITHDRAW(Category.INTERNAL_I1_TRANSFER, "—（B 总量不变）",
                "C += a；DEMAND_*_DEPOSIT -= a（ER/Vc -= a）",
                new Book(0, Component.CIRCULATION_CASH, 1, 0, false,
                        "非银行取现：存款 → C（银行侧存款账本同时减记，由银行侧钩子完成）")),
        /** 非银行部门存现：流通现金 → 银行存款。 */
        NONBANK_CASH_DEPOSIT(Category.INTERNAL_I1_TRANSFER, "—（B 总量不变）",
                "C -= a；DEMAND_*_DEPOSIT += a（ER/Vc += a）",
                new Book(0, Component.CIRCULATION_CASH, -1, 0, false,
                        "非银行存现：C → 存款（银行侧存款账本同时增记）")),

        // —— ★CB-T26 CBDC 配对记账（构成替代；B 总量与 M2 总量均不变）——
        /**
         * 数字人民币投放（实体现金 ↔ CBDC 的<b>构成替代</b>）。
         *
         * <p>配对（CB-T26 根除的唯一"绕过记账单一入口"通道）：
         * <ol>
         *   <li><b>负债侧</b>：央行 {@code cbdcIssued += a}（CBDC 负债增加）；</li>
         *   <li><b>实体钞票侧</b>：央行 {@code noteCash -= a}（{@code circulationCash} 的 Cnote 部分<b>等额减少</b>）；</li>
         *   <li><b>总量</b>：{@code C = Cnote + CbDc} 不变 ⇒ {@code B} 不变、{@code M2} 不变、{@code reserveMoney} 不变；</li>
         *   <li><b>I8</b>：资产侧 Δ = 0、负债侧 Δ = 0（{@code cbdcIssued} +a 与 {@code noteCash} −a 相抵）
         *       ⇒ 自有资金<b>不动</b>；</li>
         *   <li><b>I10</b>：成对记账 ⇒ <b>不产生偏差</b>（不得计入 {@code divergenceAccount}）。</li>
         * </ol>
         *
         * <p>⇒ 这不是"投放基础货币"（{@link Category#INJECTION_OR_RETIREMENT}），而是
         * <b>I1 内部的货币构成替代</b>；{@code reserveMoneySign = 0}、
         * {@code assetDelta = 0}、I1 右侧落点为 {@link Component#NONE}。
         * 货币乘数可复算性不受影响（{@code B} 不变）。
         */
        CBDC_ISSUE(Category.INTERNAL_I1_TRANSFER, "—（B 总量不变；资产侧 Δ=0，不新增债权）",
                "cbdcIssued += a；Cnote -= a（C 总额不变 ⇒ B/M2 不变）",
                new Book(0, Component.NONE, 0, 0, false,
                        "CBDC 投放：纸币 → CBDC 的构成替代；配对央行负债 cbdcIssued 与 Cnote 反号，"
                                + "C/B/M2/reserveMoney 均不变、I8 自有资金不动、I10 不产生偏差")),
        /** 数字人民币回笼（CBDC → 实体现金；与 {@link #CBDC_ISSUE} 严格反向）。 */
        CBDC_REDEEM(Category.INTERNAL_I1_TRANSFER, "—（B 总量不变；资产侧 Δ=0）",
                "cbdcIssued -= a；Cnote += a（C 总额不变 ⇒ B/M2 不变）",
                new Book(0, Component.NONE, 0, 0, false,
                        "CBDC 回笼：CBDC → 纸币的构成替代；C/B/M2/reserveMoney 均不变、I8 自有资金不动")),

        // —— 负债侧项间（B 总量不变）——
        /** 降准释放准备金（rr ↓ ⇒ RR → ER）。 */
        RR_CUT_RELEASE(Category.LIABILITY_SIDE_TRANSFER, "—（B 总量不变）",
                "RR_i → rr_new × reserveBase_i；ER_i += ΔRR",
                new Book(0, Component.EXCESS_RESERVES, 1, 0, false,
                        "降准：RR → ER，B 总量不变（A1 的机制来源）")),
        /** 加准冻结准备金（rr ↑ ⇒ ER → RR；不足走 settlementDebt，禁止 ER < 0）。 */
        RR_HIKE_FREEZE(Category.LIABILITY_SIDE_TRANSFER, "—（B 总量不变）",
                "ER_i -= ΔRR；不足 → settlementDebt_i += 缺口（禁止 ER < 0）",
                new Book(0, Component.REQUIRED_RESERVES, 1, 0, false,
                        "加准：ER → RR；ER 不足走 settlementDebt（禁止 ER < 0）")),
        /** 财政投放（政府向非银行部门支付）。 */
        FISCAL_SPEND(Category.LIABILITY_SIDE_TRANSFER, "—（B 总量不变）", "govDeposits -= a；C += a",
                new Book(0, Component.CIRCULATION_CASH, 1, 0, true,
                        "财政投放：govDeposits → C（镜像 Government.treasury，不双写、不动标量）")),
        /** 税收回笼。 */
        TAX_COLLECT(Category.LIABILITY_SIDE_TRANSFER, "—（B 总量不变）", "C -= a；govDeposits += a",
                new Book(0, Component.CIRCULATION_CASH, -1, 0, true,
                        "税收回笼：C → govDeposits（镜像 Government.treasury，不双写、不动标量）")),

        // —— 配平 / 减记 ——
        /** 央行购金（既有语义：<b>不动</b> baseMoney；只把黄金计入 I8 资产侧）。 */
        GOLD_BUY(Category.BALANCING_OR_WRITE_OFF, "monetaryGold += a × price；otherAssets -= a × price",
                "—（不改 baseMoney）",
                new Book(0, Component.NONE, 0, 0, true,
                        "购金：仅资产侧科目重分类（既有语义不动 baseMoney；P6 只把黄金计入 I8 资产侧）")),
        /** 央行售金（既有语义：不动 baseMoney）。 */
        GOLD_SELL(Category.BALANCING_OR_WRITE_OFF, "monetaryGold -= a × price；otherAssets += a × price",
                "—（不改 baseMoney）",
                new Book(0, Component.NONE, 0, 0, true, "售金：仅资产侧科目重分类（不动 baseMoney）")),
        /** 配平流动（I10 偏差账户归零的唯一合法手段之一）。 */
        CANCEL(Category.BALANCING_OR_WRITE_OFF, "对应资产减记", "同侧减记并在 ownCapital 反映损益",
                new Book(0, Component.EXCESS_RESERVES, 0, 0, true,
                        "配平：由 bookBaseMoneyFlow 的 absorb 分支按偏差符号配平 I1 右侧与储备货币")),
        /** 减记（资产或负债；明确「钱去哪里了」，禁止凭空消失）。 */
        WRITE_OFF(Category.BALANCING_OR_WRITE_OFF, "对应资产或负债减记", "同侧减记并在 ownCapital 反映损益",
                new Book(0, Component.EXCESS_RESERVES, 0, 0, true,
                        "减记：同 CANCEL，由 absorb 分支配平（禁止凭空消失）"));

        private final Category category;
        private final String assetSide;
        private final String liabilitySide;
        private final Book book;

        Kind(Category category, String assetSide, String liabilitySide, Book book) {
            this.category = category;
            this.assetSide = assetSide;
            this.liabilitySide = liabilitySide;
            this.book = book;
        }

        /** 类别（决定是否改变 B 总量）。 */
        public Category category() { return category; }

        /** 该笔是否改变基础货币总量 B。 */
        public boolean changesBaseMoneyTotal() { return category.changesBaseMoneyTotal(); }

        /** 资产侧记账映射（§6.1，供 CB-t7 逐 Kind 断言）。 */
        public String assetSide() { return assetSide; }

        /** 负债侧 / I1 右侧项间记账映射（§6.1）。 */
        public String liabilitySide() { return liabilitySide; }

        /** 逐 Kind 的双向记账表（§6.1；{@code bookBaseMoneyFlow} 的唯一查表依据）。 */
        public Book book() { return book; }

        /** 本笔流动搬动的 I1 右侧分项（{@link Component#NONE} = 不涉及）。 */
        public Component component() { return book.component(); }

        /** 本笔流动是否参与 I1 右侧分解。 */
        public boolean touchesRightSide() {
            return book.component().inRightSideSum() && book.componentSign() != 0;
        }

        /**
         * 给定流动额度时，本笔流动对「储备货币（{@code reserveMoney}）」的实数变化量。
         *
         * <p>∈ {+amount, −amount, 0}：投放类 +、回笼类 −、I1 内部转移与配平类 0。
         */
        public double bookedAmount(double amount) { return amount * book.reserveMoneySign(); }

        /**
         * 给定流动额度时，本笔流动对 I1 右侧分项（{@code C/Vc/RR/ER/Pay}）的实数变化量。
         *
         * <p>{@link Component#NONE}/{@link Component#SETTLEMENT_DEBT} 返回 0（后者走
         * {@code financialCorpDepositsNonReserve} 负债侧科目，不进 I1 右侧之和）。
         */
        public double componentDelta(double amount) {
            if (!book.component().inRightSideSum()) return 0;
            return amount * book.componentSign();
        }

        /** 枚举取值个数（供 CB-18① 计数断言；v1.6c 实测 23，CB-T26 追加 2 ⇒ <b>现行 25</b>）。 */
        public static int kindCount() { return values().length; }

        /**
         * 是否为 CBDC 构成替代类（{@code CBDC_ISSUE}/{@code CBDC_REDEEM}）。
         *
         * <p>供记账侧与探针查表：该类<b>成对替代</b>（央行负债 {@code cbdcIssued} ↔ {@code Cnote}），
         * 既不改变 {@code B}/{@code M2}，也不产生 I10 偏差。
         */
        public boolean isCbdcCompositionFlow() {
            return this == CBDC_ISSUE || this == CBDC_REDEEM;
        }

        /** 参与 I1 右侧分解的 Kind 个数（供「逐 Kind 双向记账表」覆盖率断言）。 */
        public static int rightSideKindCount() {
            int n = 0;
            for (Kind k : values()) {
                if (k.touchesRightSide()) n++;
            }
            return n;
        }

        /** CBDC 构成替代类的个数（供 CB-T26 覆盖率断言；期望 2）。 */
        public static int cbdcKindCount() {
            int n = 0;
            for (Kind k : values()) {
                if (k.isCbdcCompositionFlow()) n++;
            }
            return n;
        }
    }

    /** 本笔是否改变基础货币总量 B（I1 判据）。 */
    public boolean changesBaseMoneyTotal() { return kind.changesBaseMoneyTotal(); }

    /** 本笔的流动类别（§6.1 四段分类）。 */
    public Category category() { return kind.category(); }

    /** 逐 Kind 的双向记账表（§6.1）。 */
    public Book book() { return kind.book(); }

    /** 本笔的标量口径（是否改 {@code CentralBank.getBaseMoney()}）。 */
    public ScalarEffect scalarEffect() { return kind.book().scalarEffect(); }

    /** 本笔搬动的 I1 右侧分项（{@link Component#NONE} = 不涉及）。 */
    public Component component() { return kind.component(); }

    /** 本笔对储备货币（{@code reserveMoney}）的实数变化量。 */
    public double reserveMoneyDelta() { return kind.bookedAmount(amount); }

    /** 本笔对 I1 右侧分项（{@code C/Vc/RR/ER/Pay}）的实数变化量。 */
    public double componentDelta() { return kind.componentDelta(amount); }

    /** 是否为 I10 偏差账户的合法配平流动（仅 {@code CANCEL}/{@code WRITE_OFF}）。 */
    public boolean isBalancingFlow() { return kind == Kind.CANCEL || kind == Kind.WRITE_OFF; }

    /**
     * 是否为 CBDC 构成替代流动（{@code CBDC_ISSUE}/{@code CBDC_REDEEM}）。
     *
     * <p>记账侧据此选择"构成替代"配对分支（央行负债 {@code cbdcIssued} ↔ {@code Cnote}），
     * 与普通投放/回笼区分开。
     */
    public boolean isCbdcCompositionFlow() { return kind.isCbdcCompositionFlow(); }
}
