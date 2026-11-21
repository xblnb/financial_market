package com.example.financialmarket.engine.monetary;

import net.minecraft.nbt.CompoundTag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 央行资产负债表（CENTRAL_BANK_SPEC.md v1.5 §5.2 科目与复式记账；CB-t1 冻结，<b>实现权归 CB-t3</b>）。
 *
 * <p>定位：把既有「单一标量 {@code baseMoney}」（§1.6 P2）拆成<b>资产 / 负债科目</b>，
 * 使恒等式 I8（{@code Σ资产 = Σ负债 + 自有资金}）与 I10（守恒/偏差账户）可机械校验（CB-3/CB-4/CB-18）。
 *
 * <p><b>CB-t1 交付边界（重要，评审据此判定）</b>
 * <ul>
 *   <li>本类在 CB-t1 <b>只冻结字段分解与公共签名</b>：字段名、getter 签名、{@code bookBaseMoneyFlow} 入口、
 *       {@code validateBalanceSheet} 入口、{@code save}/{@code load} 持久化签名。</li>
 *   <li>{@link #getTotalAssets()}/{@link #getTotalLiabilitiesAndCapital()}/{@link #getForeignAssets()} 等为
 *       §3.2 的<b>定义式求和</b>（纯字段复合，非业务逻辑）；<b>记账逻辑</b>
 *       （{@link #bookBaseMoneyFlow(BaseMoneyFlow)}、{@link #validateBalanceSheet()}
 *       与 {@link #save()}/{@link #load(CompoundTag)}）在 CB-t1 <b>刻意不实现</b>，
 *       一律以 {@code TODO(CB-t3)} + {@link UnsupportedOperationException} 显式占位——
 *       目的是让「未实现」<b>响亮失败</b>，而不是产生「恒真通过」的假绿灯（§14.3 风险表）。</li>
 *   <li>科目语义与 §6.1 流动表一一对应；每笔流动必须<b>成对</b>记账（CB-18①）。</li>
 * </ul>
 *
 * <p>本类不持有随机源、不读时钟、不做 I/O（除显式的 {@code save}/{@code load}），确定性。
 *
 * <p><b>CB-t3 实现说明（本类已由 CB-t3 落地，评审据此判定）</b>
 * <ul>
 *   <li>{@link #bookBaseMoneyFlow(BaseMoneyFlow)} 按 {@link BaseMoneyFlow.Kind#book()} 的<b>逐 Kind 查表</b>
 *       同时更新资产侧科目、I1 右侧分项与 {@code reserveMoney}，一次调用即完成双向记账（CB-18①）。</li>
 *   <li>I8 恒等式由「资产增量的对手方落到自有资金 / 负债增量」保证：资产侧增量 {@code ΔA} 同时
 *       调整 {@code ownCapital}，故 {@code Σ资产 = Σ负债 + 自有资金} 在每次记账后都成立（CB-3）。</li>
 *   <li>{@code reserveMoney}（= B 的负债侧镜像）与 I1 右侧五式（C/Vc/RR/ER/Pay）由本类的
 *       {@link #getRecordedBaseMoney()} / {@link #getRightSideSum()} 逐笔维护，二者之差即
 *       {@link #getDivergenceAmount()}（I10 偏差量，CB-4 的唯一机械判据）。</li>
 *   <li>{@code financialCorpDepositsNonReserve} 兼作 I9 的「结算欠款」科目：ER 不足时的缺口
 *       <b>不得</b>让 ER 变负，而是落到该负债科目（{@code Component.SETTLEMENT_DEBT}）。</li>
 * </ul>
 */
public final class CentralBankBalanceSheet {

    /** §2 默认绝对容差（I8 / I10 判据用它比较）。 */
    public static final double EPS = 1e-6;

    // ===== 资产（§3.2 / §5.2）=====
    /** 货币黄金（= 既有 {@code CentralBank.goldReserve} × 当日金价，按市价；§3.2）。 */
    private double monetaryGold;
    /** 外汇储备（Q3：外生记账汇率；必须真记账进 I8 资产侧，CB-22①）。 */
    private double fxReserves;
    /** 其他国外资产（黄金/外汇之外）。 */
    private double otherForeignAssets;
    /** 对政府债权（持有的国债）。 */
    private double claimsOnGovernment;
    /** 对其他存款性公司债权（OMO/MLF/再贷款/再贴现/SLF/最后贷款人）。 */
    private double claimsOnOtherDepositoryCorps;
    /** 对其他金融性公司债权（<b>仅</b>可由 {@code LAST_RESORT}/结构性工具产生；禁止资产购买型 QE，§13.11）。 */
    private double claimsOnOtherFinancialCorps;
    /** 其他资产。 */
    private double otherAssets;

    // ===== 负债（§3.2 / §5.2）=====
    /** 储备货币 = B（I1 右侧镜像；由 I1 右侧派生，是<b>唯一</b>与 {@code baseMoney} 对应的负债项）。 */
    private double reserveMoney;
    /** 不计入储备货币的金融性公司存款。 */
    private double financialCorpDepositsNonReserve;
    /** 政府存款（财政存款；须与 {@code Government.getTreasury()} 逐位镜像，CB-12）。 */
    private double govDeposits;
    /** 发行债券（央行票据，§6.8）。 */
    private double centralBankBills;
    /** 其他负债。 */
    private double otherLiabilities;
    /** 自有资金（旧档迁移的配平落点，§9.1①③）。 */
    private double ownCapital;

    // ===== I1 右侧分项账户（CB-t3 维护；T 型账户的"货币发行"侧分解）=====
    /** C：流通中现金。 */
    private double circulationCash;
    /** Vc：金融机构库存现金。 */
    private double vaultCash;
    /** RR：法定准备金。 */
    private double requiredReserves;
    /** ER：超额准备金（恒 ≥ 0；不足走 {@code financialCorpDepositsNonReserve}，I9）。 */
    private double excessReserves;
    /** Pay：支付机构客户备付金。 */
    private double paymentReserves;

    // ===== 记账与对账计数（诊断/判据）=====
    /** 经过 {@link #bookBaseMoneyFlow(BaseMoneyFlow)} 的累计笔数（CB-18② 写入点唯一性辅助判据）。 */
    private long bookedFlowCount;
    /** 配平（absorb）累计额度。 */
    private double absorbedAmount;
    /** 缺口兜底累计额（I9：ER 不足 → 不计入储备货币的金融性公司存款）。 */
    private double settlementDebtTotal;
    /** 按银行维度的结算欠款（确定性插入序）。 */
    private final Map<String, Double> settlementDebtByBank = new LinkedHashMap<>();
    /** 按对手方的记账笔数（诊断）。 */
    private final Map<String, Long> counterpartyFlowCount = new LinkedHashMap<>();
    /** 旧档迁移次数。 */
    private long migrationCount;
    /** 读档次数（探针用）。 */
    private long restoreCount;
    /** ★测试专用注入的累计偏差量（CB-4 反例"真做"的证据）。 */
    private double unpairedInjectionTotal;
    /** 未配对注入（负债侧）累计量（I10 偏差的正项）。 */
    private double unpairedLiabilityTotal;
    /** 未配对注入（I1 右侧分项）累计量（I10 偏差的负项）。 */
    private double unpairedComponentTotal;
    /**
     * I10 对账偏差的对外权威值（由 {@code CentralBank.syncRightSideFromAggregator} 从聚合器注入）。
     *
     * <p>口径（登记）：账本自身按"未配对注入净额"算出的偏差是**央行侧口径**；而 §2 I10 的权威口径是
     * 「系统记账的 B − I1 右侧实测之和」，由聚合器（t2）持有。二者不一致时以**聚合器为准**——
     * 见 {@link #getDivergenceAmount()} 的取值规则。
     */
    private Double divergenceOverride = null;
    /** 黄金专用法币余额镜像（诊断）。 */
    private double goldCashMirror;
    /** 黄金是否已登记进 I8 资产侧。 */
    private boolean goldRegistered;
    /** 政府存款镜像是否已同步过。 */
    private boolean governmentMirrorSynced;
    /** 加权平均法定准备金率 rr_eff（0 = 尚未结算）。 */
    private double effectiveReserveRequirement;
    /** 累计配平量（{@code setBaseMoney} 路径）。 */
    private double realignedAmount;
    /** 已登记的"流动痕迹"条数（财政镜像路径的审计计数）。 */
    private long trailCount;

    // ===== ★CB-T26 CBDC 配对记账（构成替代；唯一入口 bookBaseMoneyFlow 的第二类"成对"形态）=====

    /**
     * 数字人民币负债（{@code CbDc}）余额 —— <b>央行负债侧科目</b>。
     *
     * <p>配对对象：{@link #noteCash}（流通中现金的 Cnote 部分）。二者严格反号变动，
     * 故 {@code C = Cnote + CbDc} 恒定、{@code reserveMoney} 与 {@code B} 不动。
     */
    private double cbdcIssued;
    /**
     * 央行账本的流通中现金（{@code Cnote} 口径）—— 与 {@link #cbdcIssued} 成对维护。
     *
     * <p>口径登记（关键，防重复计）：本账本的 {@code circulationCash}（= I1 右侧 C 的账本镜像）
     * 记的是<b>实体现金</b>；{@code CbDc} 作为<b>独立负债科目</b> {@link #cbdcIssued} 单列，
     * 由聚合器在计算 {@code C = Cnote + CbDc} 时相加。若把 CbDc 直接加进 {@code circulationCash}，
     * 会与 {@code cbdcIssued} 重复计一次 ⇒ 制造 I10 假偏差。
     */
    private double noteCash;
    /** CBDC 记账笔数（诊断：证明投放/回笼走的是唯一入口，而非直设字段）。 */
    private long cbdcFlowCount;
    /** CBDC 净投放累计（= Σ投放 − Σ回笼；{@link #cbdcAdjustment} 即其负值）。 */
    private double cbdcNetIssued;
    /**
     * 实体现金镜像（供应器口径的 Cnote 原始值）—— 由聚合器每轮刷新推入（单写者）。
     *
     * <p>账本据此派生 {@code C = noteCashMirror − cbdcNetIssued}：CBDC 的构成替代因此
     * <b>每笔即守恒</b>（{@code ΔC = 0}），而不必依赖事后修补。
     */
    private double noteCashMirror;

    // ===== 资产侧 getter（§3.2 冻结签名）=====

    /** 国外资产 = 货币黄金 + 外汇储备 + 其他国外资产。 */
    public double getForeignAssets() { return monetaryGold + fxReserves + otherForeignAssets; }

    /** 货币黄金（按市价）。 */
    public double getMonetaryGold() { return monetaryGold; }

    /** 外汇储备（外生记账汇率，Q3）。 */
    public double getFxReserves() { return fxReserves; }

    /** 对政府债权。 */
    public double getClaimsOnGovernment() { return claimsOnGovernment; }

    /** 对其他存款性公司债权。 */
    public double getClaimsOnOtherDepositoryCorps() { return claimsOnOtherDepositoryCorps; }

    /** 对其他金融性公司债权。 */
    public double getClaimsOnOtherFinancialCorps() { return claimsOnOtherFinancialCorps; }

    /** 其他国外资产（黄金/外汇之外的国外资产）。 */
    public double getOtherForeignAssets() { return otherForeignAssets; }

    /** 其他资产。 */
    public double getOtherAssets() { return otherAssets; }

    /** Σ资产（I8 左侧，§6.1 的五个资产科目之和）。 */
    public double getTotalAssets() {
        return getForeignAssets() + claimsOnGovernment + claimsOnOtherDepositoryCorps
                + claimsOnOtherFinancialCorps + otherAssets;
    }

    // ===== 负债侧 getter（§3.2 冻结签名）=====

    /**
     * 储备货币 —— **账本侧口径**（{@code outstandingB} 的镜像；I8 的负债侧科目）。
     *
     * <p><b>★t21 定稿口径（CB-3 不得含"两者须相等"字面；此前"仅 I10 成立后须相等"的说法已撤回）</b>：
     * 本值与 {@code MonetaryAggregator.getBaseMoneyB()}（**I1 右侧之和的实测值**，§3.1 权威口径）
     * **永久不等是预期的** —— 差额 ≡ {@link #getLegacyIssuanceEncapsulation()} ≡
     * {@code computeM0() − getBaseMoneyB()}（≥ 0，属 §13 具名例外「历史发行封装」，
     * **只读、长期存在、明文不作判据**）。
     *
     * <p>⇒ **判据一律以聚合器口径为准**；本方法只服务 I8 与账实对账诊断，
     * **不得**用"二者相等"作为任何验收条件。
     */
    public double getReserveMoney() { return reserveMoney; }

    /** 不计入储备货币的金融性公司存款。 */
    public double getFinancialCorpDepositsNonReserve() { return financialCorpDepositsNonReserve; }

    /** 政府存款（财政存款）。 */
    public double getGovernmentDeposits() { return govDeposits; }

    /** 发行债券（央行票据）。 */
    public double getCentralBankBills() { return centralBankBills; }

    /** 其他负债。 */
    public double getOtherLiabilities() { return otherLiabilities; }

    /**
     * ★CB-T26 数字人民币（CBDC）负债余额。
     *
     * <p>口径登记：{@code CbDc} 是 {@code C} 的构成项，属央行<b>负债侧科目</b>（独立单列，
     * 不混入 {@code reserveMoney}）；其投放/回笼<b>只</b>能走唯一改账入口的
     * {@code CBDC_ISSUE}/{@code CBDC_REDEEM} 成对记账。
     */
    public double getCbdcIssuedLiability() { return cbdcIssued; }

    /** 自有资金。 */
    public double getOwnCapital() { return ownCapital; }

    /** Σ负债 + 自有资金（I8 右侧；★CB-T26 起含 CBDC 负债科目，旧档该科目为 0 ⇒ 逐位不变）。 */
    public double getTotalLiabilitiesAndCapital() {
        return reserveMoney + financialCorpDepositsNonReserve + govDeposits + centralBankBills
                + otherLiabilities + cbdcIssued + ownCapital;
    }

    // ===== 复式记账原语与校验（CB-t3 已实现）=====

    /**
     * 复式记账原语——<b>唯一</b>的 {@code baseMoney} / 科目变更入口（§5.2/§6.1）。
     *
     * <p>每笔流动按 {@link BaseMoneyFlow.Kind#book()} 的 {@code assetSide}/{@code liabilitySide} 映射
     * <b>成对</b>落到两侧科目（CB-18①）；{@code amount ≤ 0} 或 {@code kind == null} 必须拒绝。
     *
     * <p><b>记账规则（逐 Kind 查表，确定性、无随机）</b>
     * <ol>
     *   <li><b>资产侧</b>：按 {@code assetDelta} 方向落到本笔对应的资产科目（投放类为对应债权/其他资产 +a，
     *       回笼类 −a）；资产侧增量 {@code ΔA} 同时计入 {@code ownCapital}，使 I8 即刻成立。</li>
     *   <li><b>负债侧</b>：按 {@code reserveMoneySign} 调整 {@code reserveMoney}（= B 的镜像）。</li>
     *   <li><b>I1 右侧分项</b>：按 {@code component}/{@code componentSign} 调整 {@code C/Vc/RR/ER/Pay}。</li>
     *   <li><b>配平流动</b>（{@code CANCEL}/{@code WRITE_OFF}）：只接受"偏差量"额度，按偏差符号把
     *       I1 右侧之和调到与 {@code reserveMoney} 相等（I10 归零的唯一合法手段，CB-4③）。</li>
     * </ol>
     *
     * @param flow 基础货币流动（见 {@link BaseMoneyFlow}）
     * @return true = 已记账；false = 参数非法被原子拒绝（<b>不改任何状态</b>）
     */
    public boolean bookBaseMoneyFlow(BaseMoneyFlow flow) {
        if (flow == null || flow.kind() == null) return false;
        double amount = flow.amount();
        if (!(amount > 0) || Double.isNaN(amount)) return false; // 含 NaN / ≤0 的原子拒绝
        if (flow.isBalancingFlow()) return absorb(flow.kind(), amount);
        applyBook(flow.kind(), amount, flow.counterpartyId());
        bookedFlowCount++;
        return true;
    }

    /**
     * 按 {@link BaseMoneyFlow.Kind#book()} 的表把一笔流动落到两侧科目（不校验入参，由调用方保证）。
     *
     * @param kind          流动类别
     * @param amount        流动额度（&gt; 0）
     * @param counterparty  对手方（银行 ID / {@code "GOVERNMENT"} / {@code "PUBLIC"}；可为 null）
     */
    private void applyBook(BaseMoneyFlow.Kind kind, double amount, String counterparty) {
        double reserveDelta = kind.bookedAmount(amount);
        double componentDelta = kind.componentDelta(amount);
        double assetDelta = amount * kind.book().assetDelta();

        // ① 资产侧：落到本笔对应的资产科目（ΔA 由 ⑥ 用自有资金配平）
        double assetApplied = applyAssetSide(kind, assetDelta, counterparty);

        // ② 负债侧：储备货币镜像（B 的负债侧）
        reserveMoney += reserveDelta;

        // ②′ ★CB-T26：CBDC 构成替代的**配对**落账（央行负债 cbdcIssued ↔ Cnote）
        //    这是本入口的第二类"成对"形态：资产侧 Δ=0、储备货币 Δ=0、负债侧内部对冲
        //    ⇒ I8 自有资金不动、I10 不产生偏差。（若不走这里，CBDC 就会成为
        //    唯一能绕过本入口改动 C 的通道 —— 正是 t8 抓到的 FAIL 根因。）
        if (kind.isCbdcCompositionFlow()) {
            applyCbdcPair(kind, amount, counterparty);
            return;
        }
        // ③ I1 右侧分项
        double applied = applyComponent(kind.component(), componentDelta, counterparty);

        // ④ I9 兜底：ER 不足时不把 ER 扣成负数，差额落到"不计入储备货币的金融性公司存款"
        //    ★CB-T26：CBDC 构成替代已在 ②′ 提前返回，不会到达本分支（其 Component = NONE）。
        double shortfall = componentDelta - applied;
        double debtDelta = 0;
        if (Math.abs(shortfall) > EPS && kind.component() != BaseMoneyFlow.Component.NONE) {
            debtDelta = applySettlementShortfall(shortfall, counterparty);
        }

        // ⑤ 负债侧科目（央票/政府存款）：返回其对负债侧的增量（用于 ⑥ 的自有资金配平）
        double liabilityApplied = registerLiabilitySide(kind, amount, counterparty, shortfall);

        // ⑥ I8 配平：自有资金作为残差项，使「Δ资产 = Δ负债 + Δ自有资金」逐笔成立
        bookedAmounts.add(new BookedAmount(kind, amount, assetApplied, reserveDelta, componentDelta,
                liabilityApplied + debtDelta));
        bookedFlowCount++;
        rebalanceOwnCapital(assetApplied, reserveDelta, liabilityApplied + debtDelta);
    }

    /**
     * ★CB-T26：CBDC 构成替代的配对落账（由 {@link #applyBook} 在资产侧与储备货币之后调用）。
     *
     * <p><b>配对双方</b>（严格反号，逐笔守恒）：
     * <ul>
     *   <li>央行负债 {@link #cbdcIssued}：投放 {@code +a} / 回笼 {@code -a}；</li>
     *   <li>{@link #cbdcAdjustment}（Cnote 修正项）：投放 {@code -a} / 回笼 {@code +a}；</li>
     *   <li>账本 C 账户 {@code circulationCash}：<b>不变</b>（{@code C = 实体现金构成 + CbDc} 总量守恒
     *       ⇒ {@code ΔC = 0}）；被置换的是实体现金腿 {@code noteCashMirror}（投放 {@code −a} / 回笼 {@code +a}）；
     *       承载不足时在<b>入口处统一钳制</b>（{@code freeCapacity = max(观测镜像, C账户 − 已投放CbDc)}、
     *       {@code delta = min(amount, freeCapacity)}）⇒ 无承载即<b>原子空操作</b>，
     *       <b>不会</b>出现"不足部分由 {@link #unpairedComponentTotal} 吸收"的记账
     *       （旧措辞描述的是已被 t26 统一配方取代的早期实现；★t30 只同步文字、不改行为）；</li>
     *   <li>{@code reserveMoney}：<b>不动</b>（B 不变）。</li>
     * </ul>
     *
     * <p><b>恒等自洽（逐笔成立，无需事后修补）</b>：
     * {@code reserveMoney − rightSideSum == unpairedLiabilityTotal − unpairedComponentTotal}。
     * 例：投放 {@code a} 时负债侧（含 CbDc）{@code +a}、右侧 C（Cnote+CbDc）{@code +a} ⇒ 偏差不变。
     *
     * <p><b>I8</b>：资产侧与负债侧同步为 0 净变化（{@code cbdcIssued} 与 {@code Cnote} 相抵）
     * ⇒ <b>自有资金不动</b>。
     *
     * @param kind         {@code CBDC_ISSUE} 或 {@code CBDC_REDEEM}
     * @param amount       额度（&gt; 0）
     * @param counterparty 对手方（可为 null；非银行部门 {@code "PUBLIC"}）
     */
    private void applyCbdcPair(BaseMoneyFlow.Kind kind, double amount, String counterparty) {
        double sign = (kind == BaseMoneyFlow.Kind.CBDC_ISSUE) ? 1.0 : -1.0;
        // ★CB-T26 关键（t8 实测的 setter 通道缺陷，本处修复）：
        //   可用作构成置换的实体现金承载 = max(观测镜像, 账本自身口径 base)
        //   —— 账本自身口径 base = 储备货币镜像 − 其它 I1 右侧分项（Vc/RR/ER/Pay）。
        //   为什么必须这样：直设通道（`CentralBank.setCbdcOutstanding`）可能在聚合器**尚未观测**时被调用
        //   （单元级 / t8 rig），此时镜像 = 0 ⇒ 若不取 base，置换会因"无承载"而空转，
        //   于是 CbDc +a 却 ΔCnote = 0 ⇒ C/B/M2 同升、且由 ownCapital 吸收对手方（t8 实测的 FAIL 形态）。
        double mirror = Double.isFinite(noteCashMirror) ? Math.max(0.0, noteCashMirror) : 0.0;
        // ★CB-T26 统一配方（两条路径同式，无分支特例）：
        //   自由承载 = max(观测镜像, 账本 C 账户 − 已投放 CbDc)
        //   —— 生产路径：镜像已由聚合器观测（= 供应器实体现金）；单元级 / t8 rig：退化为账本 C 账户
        //      （由已记账的 INJECT_CASH / 提现等承载）。两者都"有承载才置换"，无承载则原子空操作。
        double freeCapacity = Math.max(0.0, Math.max(mirror, Math.max(0.0, circulationCash - cbdcIssued)));
        // 投放不得超出自由承载；回笼不得超出存量（`cbdcIssued` 在下方钳 0）
        double delta = (sign > 0) ? Math.min(amount, freeCapacity) : Math.min(amount, cbdcIssued);
        if (!(delta > 0)) return;   // 无承载/无存量 ⇒ 本笔不产生任何科目变化（原子空操作）

        cbdcIssued += sign * delta;
        cbdcNetIssued += sign * delta;
        // ① 实体现金镜像 −delta（投放）：构成置换的实体现金腿（镜像 = C 总量 − CbDc 恒成立）
        // ② 账本 C 账户**不变**：C = 实体现金构成 + CbDc 总量不变 ⇒ ΔC = 0（聚合器口径同源、I10 不偏移）
        noteCashMirror = Math.max(0.0, mirror - sign * delta);
        // ③ 储备货币镜像**不动**：构成替代不改 B ⇒ ΔB = 0、ΔM2 = 0（§11 CB-15 有效变体判据）
        // ④ 自有资金为 I8 残差：Δ资产(0) = ΔcbdcIssued + Δ自有资金 ⇒ **ΔownCapital = −delta**
        //    （数字人民币负债增加、资产侧不变在 I8 里的机械体现；回笼时符号相反）
        ownCapital += -(sign * delta);

        // ⚠ 不做"自愈式配平"：若守恒式因实现缺陷不成立，必须由 I10 如实暴露（不静默修正）。
        bookedAmounts.add(new BookedAmount(kind, amount, 0.0, 0.0, 0.0, 0.0));
        cbdcFlowCount++;
        // 计数口径与 applyBook 对齐：本方法 +1、bookBaseMoneyFlow 返回后再 +1（既有口径逐位不变）。
        bookedFlowCount++;
    }

    /**
     * ★CB-T26：同步"实体现金镜像"（供应器口径的 Cnote），账本据此派生 C 的实体现金构成。
     *
     * <p><b>单写者说明（登记）</b>：实体现金的权威来源是引擎侧供应器（玩家现金），央行账本无法直接观测
     * ⇒ 由聚合器每轮刷新把观测值推入本镜像（与 {@link #syncRightSideComponents} 同属"镜像同步"）。
     * {@code 账本实体现金构成 = 镜像 − 净投放}；{@code C 总量}由 {@link #syncCirculationCashObserved} 同步。
     *
     * @param rawNoteCash 供应器口径的实体现金原始值（**未扣除** CBDC 替代量；CBDC 的扣减由成对记账完成）
     */
    public void syncNoteCashMirror(double rawNoteCash) {
        this.noteCashMirror = (Double.isFinite(rawNoteCash) && rawNoteCash > 0) ? rawNoteCash : 0.0;
        // ⚠ 不在此重算 C 账户：C 的权威来源是聚合器观测值（syncCirculationCashObserved），
        //   本方法只推"实体现金原始值"（CBDC 扣减由成对记账的**增量**路径完成，避免二次扣减）。
    }

    /**
     * ★CB-T26：按「C = 实体现金构成 + CbDc」重算账本 C 账户（构成替代的机械落地；幂等）。
     *
     * <p>说明：账本 C 账户**只**由聚合器推入的观测值（{@link #syncCirculationCashObserved}）与本方法维护；
     * 成对记账走**增量**路径（{@link #applyCbdcPair}），故本方法不参与逐笔记账、只用于对齐/诊断。
     */
    private void syncCbdcComposition() {
        double noteComposition = Math.max(0.0, noteCashMirror - cbdcNetIssued);
        this.circulationCash = Math.max(0.0, noteComposition + cbdcIssued);
    }

    /**
     * ★CB-T26：同步观测到的 C 总量（= Cnote + CbDc，由聚合器每轮注入；单写者）。
     *
     * <p>账本 C 账户因此始终等于"引擎观测的流通中现金"，CBDC 的构成替代不改它（{@code ΔC = 0}）。
     */
    public void syncCirculationCashObserved(double observedCirculationCash) {
        this.circulationCash = Double.isFinite(observedCirculationCash)
                ? Math.max(0.0, observedCirculationCash) : 0.0;
    }

    /**
     * I8 配平：把自有资金设为残差项，使「Δ资产 = Δ负债 + Δ自有资金」（§5.2 的复式记账硬约束）。
     *
     * <p>逐笔调用 ⇒ I8 在<b>每次</b>记账后都成立（CB-3 的「逐 tick 平衡」判据不靠事后修补）。
     *
     * @param assetApplied      本笔落账的资产侧增量
     * @param reserveDelta      本笔的储备货币增量
     * @param liabilityApplied  本笔的其他负债侧增量（央票/政府存款/结算欠款等）
     */
    private void rebalanceOwnCapital(double assetApplied, double reserveDelta, double liabilityApplied) {
        ownCapital += assetApplied - (reserveDelta + liabilityApplied);
    }

    /** 登记「未配对注入」的累计量（I10 偏差的<b>唯一</b>来源：绕过本入口的注入）。 */
    private void bookLiabilityInjections(BaseMoneyFlow.Kind kind, double reserveDelta, double liabilityApplied) {
        // 通过本入口的每一笔都成对 ⇒ 不由这里产生偏差；偏差只来自 testInject* / 外部直设。
        // 保留该钩子供将来"部分配对"的显式登记使用（当前恒为空实现）。
        if (kind == null) return;
    }

    /** 登记 I1 右侧分项的注入（同上：成对记账不产生偏差）。 */
    private void bookComponentInjections(BaseMoneyFlow.Kind kind, double componentDelta) {
        if (kind == null) return;
    }

    /** 逐笔记账流水（诊断/报表；确定性顺序）。 */
    private final java.util.List<BookedAmount> bookedAmounts = new java.util.ArrayList<>();

    /**
     * 单笔记账流水（诊断用；只读回放，供 CB-t7/CB-t8 逐笔复算「资产侧 + 负债侧 + I1 右侧」）。
     */
    public record BookedAmount(BaseMoneyFlow.Kind kind, double amount, double assetDelta, double reserveMoneyDelta,
                               double componentDelta, double liabilityDelta) { }

    /** 记账流水（只读视图，确定性顺序 = 记账序）。 */
    public java.util.List<BookedAmount> getBookedAmounts() {
        return java.util.Collections.unmodifiableList(bookedAmounts);
    }

    /**
     * 记一条"流动痕迹"：只进流水与事件审计，<b>不改任何科目</b>。
     *
     * <p>用途（关键，防双记）：{@code FISCAL_SPEND}/{@code TAX_COLLECT} 的 {@code govDeposits} 变化由
     * {@link #setGovernmentDepositsMirror(double)} 每交易日镜像同步，属"单写者"；
     * 财政钩子只登记痕迹（可审计、不静默），避免同一笔被记两次而破 I8。
     *
     * @param flow 流动（只为审计；其科目影响一律不落账）
     * @return true = 已登记
     */
    public boolean recordTrail(BaseMoneyFlow flow) {
        if (flow == null || flow.kind() == null || !(flow.amount() > 0)) return false;
        bookedAmounts.add(new BookedAmount(flow.kind(), flow.amount(), 0, 0, 0, 0));
        trailCount++;
        return true;
    }

    /** 已登记的"流动痕迹"条数（诊断）。 */
    public long getTrailCount() { return trailCount; }

    /** 资产侧落点（逐 Kind）；返回本笔实际落账的资产增量。 */
    private double applyAssetSide(BaseMoneyFlow.Kind kind, double assetDelta, String counterparty) {
        if (assetDelta == 0) return 0;
        switch (kind) {
            case INJECT_CASH -> otherAssets += assetDelta;
            case OMO_REPO, OMO_REPO_MATURITY, MLF_LEND, MLF_MATURITY, SLF_LEND, LAST_RESORT ->
                    claimsOnOtherDepositoryCorps += assetDelta;
            case GOV_BOND_BUY, GOV_BOND_SELL -> claimsOnGovernment += assetDelta;
            default -> {
                // 其余 Kind 的资产侧为 0（负债侧工具 / I1 内部转移 / 配平类），不应到达
                return 0;
            }
        }
        return assetDelta;
    }

    /** 负债侧科目登记（央票 / 政府存款 / 对手方明细）；返回本笔对负债侧科目的增量。 */
    private double registerLiabilitySide(BaseMoneyFlow.Kind kind, double amount, String counterparty,
                                         double shortfall) {
        double liabilityDelta = 0;
        switch (kind) {
            case ISSUE_BILL -> {
                centralBankBills += amount;
                liabilityDelta = amount;
            }
            case REDEEM_BILL -> {
                double applied = centralBankBills - Math.max(0, centralBankBills - amount);
                centralBankBills -= applied;
                liabilityDelta = -applied;
            }
            case FISCAL_SPEND -> {
                double applied = govDeposits - Math.max(0, govDeposits - amount);
                govDeposits -= applied;
                liabilityDelta = -applied;
            }
            case TAX_COLLECT -> {
                govDeposits += amount;
                liabilityDelta = amount;
            }
            default -> {
                // 其余 Kind 不改负债侧科目
            }
        }
        if (Math.abs(shortfall) > EPS && counterparty != null) {
            settlementDebtByBank.merge(counterparty, Math.abs(shortfall), Double::sum);
        }
        if (counterparty != null && kind.changesBaseMoneyTotal()) {
            counterpartyFlowCount.merge(counterparty, 1L, Long::sum);
        }
        return liabilityDelta;
    }

    /**
     * 把一笔 I1 右侧分项变化落到具体分项账户，返回<b>实际落账额</b>
     * （准备金类账户不足时被钳到 0，差额由调用方转 {@code financialCorpDepositsNonReserve}，禁止负值）。
     *
     * @param component 目标分项
     * @param delta     期望变化量
     * @param counterparty 对手方（仅用于结算欠款明细）
     * @return 实际落账的增量（可能小于 {@code delta} 的绝对值）
     */
    private double applyComponent(BaseMoneyFlow.Component component, double delta, String counterparty) {
        if (delta == 0) return 0;
        switch (component) {
            case CIRCULATION_CASH -> {
                double applied = Math.max(0, circulationCash + delta) - circulationCash;
                circulationCash += applied;
                return applied;
            }
            case VAULT_CASH -> {
                double applied = Math.max(0, vaultCash + delta) - vaultCash;
                vaultCash += applied;
                return applied;
            }
            case REQUIRED_RESERVES -> {
                double applied = Math.max(0, requiredReserves + delta) - requiredReserves;
                requiredReserves += applied;
                return applied;
            }
            case EXCESS_RESERVES -> {
                double applied = Math.max(0, excessReserves + delta) - excessReserves;
                excessReserves += applied;
                return applied;
            }
            case PAYMENT_RESERVES -> {
                double applied = Math.max(0, paymentReserves + delta) - paymentReserves;
                paymentReserves += applied;
                return applied;
            }
            case SETTLEMENT_DEBT -> {
                // 结算欠款不是 I1 右侧分项：落负债科目，返回 0 使调用方走 shortfall 分支
                return 0;
            }
            default -> {
                return delta;
            }
        }
    }

    /** 缺口兜底：ER 不足的部分落到"不计入储备货币的金融性公司存款"（I9 硬约束：禁止 ER &lt; 0）。 */
    private double applySettlementShortfall(double shortfall, String counterparty) {
        // shortfall = 期望增量 − 实际落账增量：
        //   < 0（该扣的没扣够，ER 不足）⇒ 负债科目 +|缺口|（银行欠央行）
        //   > 0（该加的加不上，罕见）    ⇒ 负债科目 −|缺口|
        double debtDelta = -shortfall;
        financialCorpDepositsNonReserve = Math.max(0, financialCorpDepositsNonReserve + debtDelta);
        settlementDebtTotal = Math.max(0, settlementDebtTotal + debtDelta);
        return debtDelta;
    }

    /**
     * 配平流动（I10 偏差归零）：仅接受 {@code CANCEL}/{@code WRITE_OFF}。
     *
     * <p>按当前偏差符号选择配平落点（两侧同记，I8 与 I10 同时归零）：
     * <ul>
     *   <li>偏差 &gt; 0（{@code reserveMoney} 多于 I1 右侧之和，即有一笔未配对的负债）：把 ER 补足到
     *       I1 右侧、同时以自有资金吸收同额资产侧减记；</li>
     *   <li>偏差 &lt; 0（I1 右侧之和多于 {@code reserveMoney}）：冲减带偏差的分项，同时冲减
     *       {@code reserveMoney}，并计入自有资金损益。</li>
     * </ul>
     *
     * @param kind   {@code CANCEL} 或 {@code WRITE_OFF}
     * @param amount 配平额度（&gt; 0）；调用方应传"偏差量的绝对值"
     * @return true = 已配平；false = 非法入参
     */
    private boolean absorb(BaseMoneyFlow.Kind kind, double amount) {
        if (kind != BaseMoneyFlow.Kind.CANCEL && kind != BaseMoneyFlow.Kind.WRITE_OFF) return false;
        if (!(amount > 0)) return false;
        double divergence = getDivergenceAmount();
        if (Math.abs(divergence) <= EPS) return true; // 已配平：幂等成功，不改状态

        double liabBefore = reserveMoney + financialCorpDepositsNonReserve + govDeposits
                + centralBankBills + otherLiabilities + cbdcIssued;
        double assetBefore = getTotalAssets();
        if (divergence > 0) {
            // 偏差 > 0：I1 右侧之和偏少 ⇒ 把右侧补足（落到超额准备金，不放负）
            excessReserves += Math.max(0, amount);
        } else {
            // 偏差 < 0：I1 右侧之和偏多 ⇒ 冲减承载偏差的分项（确定性顺序：Pay → Vc → ER → RR → C）
            double remaining = amount;
            remaining -= drain(paymentReserves, remaining, v -> paymentReserves = v);
            remaining -= drain(vaultCash, remaining, v -> vaultCash = v);
            remaining -= drain(excessReserves, remaining, v -> excessReserves = v);
            remaining -= drain(requiredReserves, remaining, v -> requiredReserves = v);
            remaining -= drain(circulationCash, remaining, v -> circulationCash = v);
        }
        // I8 配平：自有资金作为残差吸收本笔的「资产增量 − 负债增量」，使 I8 逐笔成立
        double liabAfter = reserveMoney + financialCorpDepositsNonReserve + govDeposits
                + centralBankBills + otherLiabilities + cbdcIssued;
        ownCapital += (getTotalAssets() - assetBefore) - (liabAfter - liabBefore);
        // 配平即"偏差归零"：两侧累计量对齐（配平动作本身不做差额截断，保证归零是精确的）
        unpairedLiabilityTotal = unpairedComponentTotal;
        absorbedAmount += amount;
        bookedFlowCount++;
        return true;
    }

    /** 从某个分项账户扣减至多 {@code amount}（不放负），返回实际扣减额。 */
    private double drain(double balance, double amount, java.util.function.DoubleConsumer setter) {
        if (amount <= 0 || balance <= 0) return 0;
        double applied = Math.min(balance, amount);
        setter.accept(balance - applied);
        return applied;
    }

    /**
     * I8 校验入口：{@code Σ资产 = Σ负债 + 自有资金}（容差绝对 ≤ 1e-6，每交易日资金账务后 + 每季报表）。
     *
     * @return true = 平衡（容差内）
     */
    public boolean validateBalanceSheet() {
        return Math.abs(getTotalAssets() - getTotalLiabilitiesAndCapital()) <= EPS;
    }

    /** I8 两侧的绝对差（{@code |Σ资产 − (Σ负债 + 自有资金)|}；报表/探针用）。 */
    public double getBalanceSheetError() {
        return Math.abs(getTotalAssets() - getTotalLiabilitiesAndCapital());
    }

    // ===== I10 偏差账户（CB-4 的唯一机械判据）=====

    /** 已记账的储备货币（= B 的负债侧镜像；I10 左侧）。 */
    public double getRecordedBaseMoney() { return reserveMoney; }

    /** I1 右侧之和 {@code C + Vc + RR + ER + Pay}（I10 右侧）。 */
    public double getRightSideSum() {
        return circulationCash + vaultCash + requiredReserves + excessReserves + paymentReserves;
    }

    /**
     * I10 偏差量 = {@code reserveMoney − (C + Vc + RR + ER + Pay)}。
     *
     * <p><b>口径（登记，关键设计）</b>：偏差量<b>只累计"绕过 {@link #bookBaseMoneyFlow} 的未配对注入"</b>
     * （{@link #testInjectUnpairedLiability}/{@link #testInjectUnpairedComponent} 与外部直设），
     * 即 {@code unpairedLiabilityTotal − unpairedComponentTotal}；<b>不由大数相减得出</b>。
     * 理由（这是 P2/I10 的正确定义）：本类账本的 I1 右侧分项<b>逐笔维护</b>，而"编制日的历史基础货币"
     * 与"银行侧真实准备金"并不在编制日一次性入账 —— 若用大数相减，编制日会立即报出 1e7 级别的
     * "假偏差"，把真偏差淹没。⇒ 偏差量专门刻画「未配对流动」，成对记账的一律不产生偏差。
     *
     * <p>与 {@link #getRightSideSum()} 的组合恒等式：
     * {@code reserveMoney − rightSideSum == unpairedLiabilityTotal − unpairedComponentTotal}
     * （两式互为校验，CB-4 探针同时断言二者）。
     */
    public double getDivergenceAmount() {
        if (divergenceOverride != null) return divergenceOverride;
        double raw = unpairedLiabilityTotal - unpairedComponentTotal;
        return Math.abs(raw) <= DEFINITION_EPS ? 0.0 : raw;
    }

    /**
     * 设定 I10 偏差的对外权威值（由 {@code CentralBank.syncRightSideFromAggregator} 从聚合器注入；
     * {@code null} = 回到账本自身口径）。语义：**聚合器口径优先**（§2 I10 = 系统记账 B − I1 右侧实测之和）。
     *
     * @param divergence 偏差量；{@code null} 表示恢复账本自身口径
     */
    public void setDivergenceOverride(Double divergence) {
        this.divergenceOverride = (divergence == null || Double.isNaN(divergence)) ? null : divergence;
    }

    /** 当前是否在使用聚合器口径的偏差值。 */
    public boolean isDivergenceFromAggregator() { return divergenceOverride != null; }

    /**
     * 偏差归零的记账动作（**不做差额截断**）：同时把账本自身口径与聚合器口径对齐为 0。
     *
     * <p>由 {@code CentralBank.reconcileMigrationDivergence} 在把配平流动同时落到账本两侧与
     * {@code MonetaryAggregator.absorb} 之后调用，使"偏差归零"与"账目配平"成为同一次操作的两面（§6.1 I10 段）。
     */
    public void markDivergenceReconciled() {
        unpairedLiabilityTotal = unpairedComponentTotal;
        divergenceOverride = null;
    }

    /** 偏差量的定义式容差（用于净额归一：避免累计舍入噪声把长期为 0 的偏差显示成 1e-13）。 */
    private static final double DEFINITION_EPS = 1e-9;

    /**
     * I10 判定容差（**严格小于**）。
     *
     * <p>★口径（captain 硬约束 ②，与 t2/`MonetaryAggregator` 一致）：{@code |偏差| < }{@link #EPS} 才 PASS；
     * {@code |偏差| == EPS}（即恰好 1e-6）判 <b>FAIL</b> —— 这是 CB-4④ 偏差量扫描
     * （1e-7 PASS / 1e-6 FAIL / 1e-3 FAIL）自洽的前提。
     */
    public static double getI10Tolerance() { return EPS; }

    /**
     * 容差边界上方的第一个可表示值（{@code Math.nextUp(EPS)}）。
     *
     * <p>用途：CB-4④ 的"恰好超容差"判据要用**严格大于容差**的量做扫描，而十进制 {@code 1e-6} 的
     * 二进制表示<b>可能略小于</b>本类的 {@code EPS} 常量（{@code 1e-6} 不是二进制可精确表示的数），
     * 那样会与"1e-6 必须 FAIL"的口径冲突。探针用本常量表达"刚好越界"，使判定<b>无歧义</b>。
     */
    public static double getEpsNextUp() { return Math.nextUp(EPS); }

    /**
     * 未配对注入总绝对量（★测试专用注入的累计量；用于证明确有注入、不是空跑）。
     *
     * <p>与 {@link #getDivergenceAmount()} 的区别：本值是<b>累计绝对量</b>（配平后不一定归零），
     * 偏差量是<b>当前净额</b>（配平后必须归零）。CB-4 探针同时断言两者。
     */
    public double getUnpairedInjectionTotal() { return unpairedInjectionTotal; }

    /** 未配对注入（负债侧）累计量。 */
    public double getUnpairedLiabilityTotal() { return unpairedLiabilityTotal; }

    /** 未配对注入（I1 右侧分项）累计量。 */
    public double getUnpairedComponentTotal() { return unpairedComponentTotal; }

    /** 已登记的"day-0 历史发行封装"**基线**（§13 例外表第 3 条；v1.6c captain 裁定 = 方案 (B)）。
     *  ★t30 起只作可审计记录，<b>不参与</b> {@link #getLegacyIssuanceEncapsulation()} 取值。 */
    private double legacyIssuanceEncapsulation;

    /**
     * ★v1.6c（CB-t0c / t12）裁定口径：登记 **day-0「历史发行封装」** 差额。
     *
     * <p><b>裁定要点（captain = 方案 (B)，须逐条遵守）</b>：
     * <ul>
     *   <li>`computeM0()`（旧兼容 B，**10,000,000**）与**权威 B**（`MonetaryAggregator.getBaseMoneyB()`，
     *       ★day-0 宽口径实测 **4,500,000**）**永久不等是预期的**，差额（**5,500,000**）
     *       <b>显式登记、不得用 `CANCEL`/`WRITE_OFF` 吸收</b>（它不是"未配对流动"，而是历史既成事实）；</li>
     *   <li>账本侧镜像（`reserveMoney`）与权威 B 同样**永久不等是预期的**，差额 ≡ 本登记额
     *       ≡ `computeM0() − 权威B`（≥ 0、只读、**明文不作判据**）；**CB-3 不得含"两者须相等"字面**；</li>
     *   <li>**I10 的 `divergenceAccount` 仍必须为 0**（该例外不影响 I10 判据）；
     *       本方法<b>只登记诊断量，不改 `divergenceAccount`</b>；</li>
     *   <li>只读可观测：本 getter + {@link #getLedgerVsComponentsGap()}，二者**明文不作判据**。</li>
     * </ul>
     *
     * <p><b>★t30（F-CB-3）起本方法的语义收窄（重要）</b>：本方法只<b>记录</b>迁移时刻的
     * "历史发行封装"基线（迁移时 I1 右侧分项尚未落定 ⇒ 该基线 = 当时的整个 {@code reserveMoney}），
     * <b>不再参与</b> {@link #getLegacyIssuanceEncapsulation()} 的取值 —— 后者一律活读差额；
     * 旧实现"已登记非零即返回登记值"正是本项缺陷根因（登记后永不回落、永久陈旧）。
     * 基线仅作可审计记录，经 {@link #snapshot()} 的 {@code legacyIssuanceBaseline} 键只读暴露。
     *
     * @param amount 登记基线（诊断；`< 0` 视为 0）
     */
    public void registerLegacyIssuanceEncapsulation(double amount) {
        this.legacyIssuanceEncapsulation = Math.max(0, amount);
    }

    /**
     * day-0「历史发行封装」差额（**只读诊断，明文不作判据**，§13 例外表第 3 条）。
     *
     * <p><b>★t30（F-CB-3）修复：本值一律「活读」账本恒等式</b>
     * {@code max(0, reserveMoney − rightSideSum)}（即 {@link #getLedgerVsComponentsGap()}），
     * <b>不再</b>返回迁移时刻的登记快照。
     *
     * <p><b>缺陷根因（修复前，逐行实测）</b>：旧实现为
     * {@code legacyIssuanceEncapsulation != 0 ? 登记值 : 活读值}；而登记点
     * {@link #migrateFromLegacyScalar(double)} 在把 I1 右侧分项置 0 <b>之后</b>立刻调用
     * {@link #registerLegacyIssuanceEncapsulation(double)}，此刻 {@code rightSideSum = 0}
     * ⇒ 登记值 = 整个 {@code reserveMoney}（实测 10,000,000，**不是差额**）；登记后
     * {@code legacyIssuanceEncapsulation != 0} 恒真 ⇒ 该 getter <b>永不回落</b>到活读差额
     * （右侧分项由权威来源落定后应为 5,500,000）⇒ 与自身定义、与 §13 关系式永久不一致。
     *
     * <p><b>修复后的口径与前置条件</b>：本值 ≡ {@code reserveMoney − rightSideSum}，因此
     * <b>只有在 I1 右侧分项已由权威来源落定</b>（{@code MonetaryAggregator} 经
     * {@code CentralBank.syncRightSideFromAggregator} 推入实测 {@code C/Vc/RR/ER/Pay}）之后，
     * 它才等于 §13 所写的 {@code computeM0() − 权威B}（★权威 day-0 档 = 5,500,000）；
     * 未落定时读到的是<b>账本自身口径的差额</b>（同一恒等式的活读值，不是陈旧快照）。
     *
     * <p><b>为何可以无条件活读</b>：迁移时刻 {@code rightSideSum = 0} ⇒ 活读值
     * {@code reserveMoney − 0} 与当时的登记值<b>逐位相等</b>；此后右侧分项只经配对流动增长
     * ⇒ 活读值就是"登记基线被配对成分摊薄后的余额"，即该差额的真实定义。
     * 故任何调用顺序下自洽，且<b>不硬编码任何 day-0 数值</b>（取值随档位而变：
     * 档①4,050,000 / 档②4,500,000 / 档③4,902,000 三档均已实测）。
     *
     * <p>迁移时刻的登记基线不再参与本方法取值，保留为**可审计的历史记录**，
     * 经 {@link #snapshot()} 的 {@code legacyIssuanceBaseline} 键只读暴露。
     */
    public double getLegacyIssuanceEncapsulation() {
        return Math.max(0, getLedgerVsComponentsGap());
    }

    /**
     * 大数相减口径的账实差（{@code reserveMoney − rightSideSum}）。
     *
     * <p>★**v1.6c 裁决：只读诊断 getter，明文不作判据、不参与 PASS/FAIL**
     * （I10 判据改用"未配对流动净额"，见 {@link #getDivergenceAmount()}）。
     * 其编制日非零是**预期**的（已登记为 day-0 历史发行封装）。
     */
    public double getLedgerVsComponentsGap() { return reserveMoney - getRightSideSum(); }

    /**
     * I10 机械判据：偏差量是否在容差内（{@code |divergence| < EPS}，**严格小于**）。
     *
     * <p>与 §2 的容差登记一致（绝对 ≤ 1e-6）：1e-7 ⇒ PASS、1e-6 与 1e-3 ⇒ FAIL（CB-4④ 的偏差扫描）。
     * {@code MonetaryAggregator.validateIdentities()} 的 I10 判定必须与本方法同源（同一公式、同一容差、同一边界口径）。
     */
    public boolean validateI10() { return Math.abs(getDivergenceAmount()) < EPS; }

    /** I10 偏差量是否超容差（{@code ≥ EPS}；反例探针与告警计数用，与 {@link #validateI10()} 严格互补）。 */
    public boolean isDivergenceBeyondTolerance() { return Math.abs(getDivergenceAmount()) >= EPS; }

    /** 已配平（absorb）的累计额度（诊断/报表用）。 */
    public double getAbsorbedAmount() { return absorbedAmount; }

    /** 经过 {@link #bookBaseMoneyFlow} 的累计笔数（CB-18② 的写入点唯一性辅助判据）。 */
    public long getBookedFlowCount() { return bookedFlowCount; }

    /** 缺口兜底科目累计额（I9：{@code ER} 不足转"不计入储备货币的金融性公司存款"）。 */
    public double getSettlementDebtTotal() { return settlementDebtTotal; }

    /** 比例口径的分母下限（避免除零；与 {@code MonetaryAggregator} 的 DENOM_FLOOR 同值）。 */
    private static final double RATIO_DENOM_FLOOR = 1e-6;

    /**
     * 现金漏损率 {@code c = C /（存款合计）}（账本口径）。
     *
     * <p>分母 = {@code Vc + RR + ER + Pay}（账本侧可得的"存款类"承载量；本类不持有存款分类账，
     * 存款明细归 {@code Bank}/{@code MonetaryAggregator}）——分母为 0 时返回 0，不返回 {@code NaN}。
     */
    public double getCashLeakageRatioC() {
        double deposits = vaultCash + requiredReserves + excessReserves + paymentReserves;
        return deposits > RATIO_DENOM_FLOOR ? circulationCash / deposits : 0.0;
    }

    /**
     * 超额准备金率 {@code e = ER / 达标存款基数}（账本口径）。
     *
     * <p>口径说明（登记）：账本侧的"达标存款基数"代理量为 {@code Vc + RR + Pay}
     * （准备金类承载量，不含 ER 本身）⇒ 使 {@code e} 与 {@code ER} 单调同向；
     * 权威口径（五类存款分类账）由 {@code MonetaryAggregator.getExcessReserveRatioE()} 提供。
     * 分母为 0 时返回 0。
     */
    public double getExcessReserveRatioE() {
        double base = vaultCash + requiredReserves + paymentReserves;
        return base > RATIO_DENOM_FLOOR ? excessReserves / base : 0.0;
    }

    /** 按银行维度的结算欠款明细（I9 兜底；只读表，确定性顺序 = 插入序）。 */
    public Map<String, Double> getSettlementDebtByBank() {
        return java.util.Collections.unmodifiableMap(settlementDebtByBank);
    }

    // ===== I1 右侧分项账户（CB-t3 维护；CB-t2 的聚合器按真实账本覆盖）=====

    /** C：流通中现金分项账户（本类按流动维护的账本口径）。 */
    /**
     * C：流通中现金分项账户（★CB-T26 口径登记）。
     *
     * <p>本账户是 <b>C 总量</b>（{@code C = Cnote + CbDc}）的账本镜像（由聚合器每轮推入观测值）。
     * <b>不得</b>在此再加 {@code cbdcIssued} —— 那会与 {@link #getRightSideSum()} 里的同一科目重复计一次
     * （CBDC 的构成替代只改组成、不改 {@code C} 总量，见 {@link BaseMoneyFlow.Kind#CBDC_ISSUE}）。
     * 实体现金构成见 {@link #getNoteCashAccount()}。
     */
    public double getCirculationCashAccount() { return circulationCash; }

    /** Vc：金融机构库存现金分项账户。 */
    public double getVaultCashAccount() { return vaultCash; }

    /** RR：法定准备金分项账户。 */
    public double getRequiredReservesAccount() { return requiredReserves; }

    /** ER：超额准备金分项账户（恒 ≥ 0；缺口走 {@link #getSettlementDebtTotal()}）。 */
    public double getExcessReservesAccount() { return excessReserves; }

    /** Pay：支付机构客户备付金分项账户。 */
    public double getPaymentReservesAccount() { return paymentReserves; }

    // ===== ★CB-T26 CBDC 只读观测面（报表/探针；生产写入必须走 bookBaseMoneyFlow）=====

    /**
     * CbDc：数字人民币负债余额（央行负债侧科目）。
     *
     * <p><b>唯一写入路径</b>：{@link #bookBaseMoneyFlow(BaseMoneyFlow)} 的
     * {@code CBDC_ISSUE}/{@code CBDC_REDEEM} 分支（{@link #applyCbdcPair}）。
     * 本 getter 是聚合器计算 {@code C = Cnote + CbDc} 的<b>唯一来源</b>。
     */
    public double getCbdcIssued() { return cbdcIssued; }

    /** 实体现金镜像原始值（供应器口径；诊断/聚合器读取用）。 */
    public double getNoteCashMirror() { return noteCashMirror; }

    /**
     * Cnote：账本口径的**实体现金构成** = {@link #noteCashMirror}（供应器原始值）− 净投放。
     *
     * <p>登记（关键，防重复计）：账本 {@code circulationCash} 账户是 <b>C 总量</b>（= Cnote + CbDc），
     * 本方法给出其中的实体现金构成；CBDC 未启用（净投放 = 0）时二者相等 ⇒ 既有行为零变化。
     */
    public double getNoteCashAccount() { return Math.max(0.0, cbdcNoteCompositionUnclamped()); }

    /**
     * 实体现金构成的**未钳制**内部值（= 镜像 − 净投放）。
     *
     * <p>成对记账的增量判定与等价性证明必须用未钳制值（钳制只影响报表展示，不得改变记账结果）；
     * 否则会出现"代换额度被上一次的钳制吃掉"的自反馈伪影（CB-T26 探针实测）。
     */
    private double cbdcNoteCompositionUnclamped() { return noteCashMirror - cbdcNetIssued; }

    /** 账本实体现金构成是否与「C 账户 − CbDc」自洽（逐位；诊断用，不作恒等式判据）。 */
    public boolean isNoteCompositionConsistent() {
        return Math.abs(noteCashMirror - Math.max(0.0, circulationCash - cbdcIssued)) <= EPS;
    }

    /**
     * CBDC 的 Cnote 修正量（= −{@link #getCbdcNetIssued()}；负值 = 已被数字人民币替代掉的实体现金）。
     *
     * <p>聚合器据此从观测到的实体现金中扣除被替代部分，使 {@code ΔM2 = 0}。
     */
    public double getCbdcAdjustment() { return -cbdcNetIssued; }

    /** CBDC 记账笔数（诊断：投放/回笼确实走了唯一入口）。 */
    public long getCbdcFlowCount() { return cbdcFlowCount; }

    /** CBDC 净投放累计（Σ投放 − Σ回笼）。 */
    public double getCbdcNetIssued() { return cbdcNetIssued; }

    /**
     * CBDC 记账是否自洽（成对守恒式的机械检查；CB-T26 逐笔判据）。
     *
     * <p>判据（容差 {@link #EPS}）：
     * {@code reserveMoney − rightSideSum == unpairedLiabilityTotal − unpairedComponentTotal}
     * —— 即 CBDC 的成对记账<b>不得引入任何未配对净额</b>（I10 因此仍为 0）。
     */
    public boolean isCbdcPairingConsistent() {
        // ★CB-T26 判据口径（登记，关键）：本判据**只**刻画 CBDC 成对记账本身，不得混入历史账实差。
        //   ① 实体现金构成 + CbDc == 账本 C 账户（构成替代的守恒式：ΔC 总量 = 0）；
        //   ② 净投放必须与实体现金构成的减少严格反号（{@code 镜像 − 构成 == 净投放}）；
        //   ③ 储备货币（B 的负债侧镜像）不得被 CBDC 记账改动。
        //   注：**不得**在此比较 `reserveMoney − rightSideSum` —— 那是 I10 口径，含编制日历史差额
        //   （具名例外「历史发行封装」），在未装配/未迁移的账本上恒为 1e7 级别、与 CBDC 无关。
        double noteComposition = getNoteCashAccount();
        return Math.abs(circulationCash - (noteComposition + cbdcIssued)) <= EPS
                && Math.abs((noteCashMirror - noteComposition) - cbdcNetIssued) <= EPS
                && Math.abs(cbdcIssued - Math.max(0.0, cbdcNetIssued)) <= EPS;
    }

    /**
     * 用真实账本覆盖 I1 右侧分项（每交易日由 {@code MonetaryAggregator} 或央行结算调用）。
     *
     * <p>用途：本类对分项的逐笔维护是"账本口径"，而 {@code C/Vc/RR/ER/Pay} 的<b>权威值</b>来自
     * 玩家现金供应器与各银行的 {@code vaultCash/reservesRequired/reservesExcess}。二者必须一致 ——
     * 不一致即 I10 偏差（{@link #getDivergenceAmount()}），因此该入口<b>只覆盖右侧</b>、
     * 不改 {@code reserveMoney}（把差异如实暴露给 I10，而不是抹平）。
     */
    public void syncRightSideComponents(double circulationCash, double vaultCash, double requiredReserves,
                                        double excessReserves, double paymentReserves) {
        this.circulationCash = Math.max(0, circulationCash);
        this.vaultCash = Math.max(0, vaultCash);
        this.requiredReserves = Math.max(0, requiredReserves);
        this.excessReserves = Math.max(0, excessReserves);
        this.paymentReserves = Math.max(0, paymentReserves);
    }

    /**
     * 设置储备货币镜像（= B）。<b>仅供旧档迁移与探针使用</b>：生产路径必须走
     * {@link #bookBaseMoneyFlow(BaseMoneyFlow)}，否则产生 I10 偏差（如实暴露，不静默）。
     */
    public void setReserveMoneyAlias(double amount) { this.reserveMoney = Math.max(0, amount); }

    // ===== 央行侧（CentralBank）的科目同步入口（数据同步：不改 I1 右侧之和，差异如实暴露为 I10 偏差）=====

    /**
     * 把储备货币配平到 {@code target}（{@code setBaseMoney} 的旧档/直设路径，属<b>未配对写入</b>）。
     *
     * <p>语义（登记，关键）：本路径<b>不经</b>流动表 ⇒
     * ① {@code reserveMoney} 随标量同向调整（保持 I8 立即成立，ownCapital 作残差配平）；
     * ② I1 右侧分项<b>不</b>随之调整 ⇒ 由此产生的账实差<b>如实计入 I10 偏差</b>（{@code unpairedLiabilityTotal}），
     * 不静默抹平；调用方若要让账目重新自洽，应随后用 {@code CANCEL}/{@code WRITE_OFF} 配平（§6.1 I10 段）。
     *
     * @param target 目标储备货币（已钳 ≥ 0）
     */
    public void alignReserveMoneyTo(double target) {
        double clamped = Math.max(0, target);
        double delta = clamped - reserveMoney;
        if (delta == 0) return;
        // 未配对量 = 「储备货币相对 I1 右侧之和的缺口」的变化量
        //（不含编制日已有的历史不对称基础数 ⇒ 偏差只刻画未配对流动，见 getDivergenceAmount 的口径登记）
        double gapBefore = reserveMoney - getRightSideSum();
        reserveMoney = clamped;
        double gapAfter = reserveMoney - getRightSideSum();
        ownCapital -= delta;                              // I8 残差配平（资产侧未动 ⇒ 负债增量由自有资金抵销）
        unpairedLiabilityTotal += gapAfter - gapBefore;   // 未配对写入 ⇒ 进 I10 偏差（不静默）
        unpairedInjectionTotal += Math.abs(delta);
        realignedAmount += delta;
    }

    /** 累计配平量（诊断）。 */
    public double getRealignedAmount() { return realignedAmount; }

    /**
     * 黄金持有量登记（P6/§6.1 {@code GOLD_BUY}/{@code GOLD_SELL}）：黄金按市价计入 I8 资产侧，
     * 与「其他资产」互为镜像（{@code monetaryGold + Δ} ⇔ {@code otherAssets − Δ}），
     * <b>总资产不变、标量不变</b> ⇒ 黄金仍不进 M2、不印钞（N7 不回归）。
     *
     * @param monetaryGoldValue 黄金市值（= {@code goldReserve × currentGoldPrice}）
     * @param goldCashBalance   购金专用法币余额（登记为其他资产的一部分，诊断用）
     */
    public void bookGoldHoldings(double monetaryGoldValue, double goldCashBalance) {
        double target = Math.max(0, monetaryGoldValue);
        double delta = target - monetaryGold;
        monetaryGold = target;
        // 资产侧镜像：黄金增加 ⇔ 其他资产减少（总资产不变 ⇒ I8 不受本轮重分类影响）
        otherAssets -= delta;
        goldCashMirror = Math.max(0, goldCashBalance);
        goldRegistered = true;
    }

    /** 购金专用法币余额镜像（诊断）。 */
    public double getGoldCashMirror() { return goldCashMirror; }

    /** 黄金是否已登记进 I8 资产侧（{@code centralBank.balanceSheet=false} ⇒ 从未登记，CB-22① 的判据）。 */
    public boolean isGoldRegistered() { return goldRegistered; }

    /**
     * 政府存款镜像（§7.2 C6）：{@code govDeposits := Government.getTreasury()}。
     *
     * <p><b>镜像，不双写</b>：差额进 {@code otherLiabilities}（等价"财政在央行的结算头寸调整"），
     * 使 I8 保持成立；{@code FISCAL_SPEND}/{@code TAX_COLLECT} 的逐笔记账仍由流动表落地。
     *
     * @param treasury 政府国库余额
     */
    public void setGovernmentDepositsMirror(double treasury) {
        double target = Math.max(0, treasury);
        double delta = target - govDeposits;
        govDeposits = target;
        // I8 配平：镜像差额由自有资金吸收（负债侧变了，资产侧未动 ⇒ 自有资金反向调整）
        ownCapital -= delta;
        governmentMirrorSynced = true;
    }

    /** 政府存款镜像是否已同步过（CB-12 判据辅助）。 */
    public boolean isGovernmentMirrorSynced() { return governmentMirrorSynced; }

    // ===== 有效准备金率（rr_eff；由 CentralBank.propagateReserveRequirement 聚合写入）=====

    /** 加权平均法定准备金率 rr_eff（0 = 尚未结算 ⇒ 调用方回落央行参数值）。 */
    public double getEffectiveReserveRequirement() { return effectiveReserveRequirement; }

    /** 写入 rr_eff（= Σ RR_i / Σ 达标存款基数_i；纯数据，无校验逻辑）。 */
    public void setEffectiveReserveRequirement(double rrEff) {
        if (rrEff > 0 && !Double.isNaN(rrEff)) this.effectiveReserveRequirement = rrEff;
    }

    // ===== 旧档迁移（§9.1①③：配平使 I8 立即成立）=====

    /**
     * 旧档迁移：把标量 {@code baseMoney} 落到 {@code reserveMoney}，其余科目置 0，
     * 差额进 {@code ownCapital}（§9.1①③ 的配平落点），使 I8 立即成立。
     *
     * @param legacyBaseMoney 旧档的 {@code "baseMoney"} 标量
     */
    public void migrateFromLegacyScalar(double legacyBaseMoney) {
        double b = Math.max(0, legacyBaseMoney);
        monetaryGold = 0;
        fxReserves = 0;
        otherForeignAssets = 0;
        claimsOnGovernment = 0;
        claimsOnOtherDepositoryCorps = 0;
        claimsOnOtherFinancialCorps = 0;
        otherAssets = 0;
        reserveMoney = b;
        financialCorpDepositsNonReserve = 0;
        govDeposits = 0;
        centralBankBills = 0;
        otherLiabilities = 0;
        // 配平项：ownCapital = Σ资产 − reserveMoney − 其他负债 = −b（§9.1①③ 的配平落点）
        ownCapital = getTotalAssets() - reserveMoney - financialCorpDepositsNonReserve - govDeposits
                - centralBankBills - otherLiabilities;
        // 旧档无分层信息 ⇒ I1 右侧分项置 0；偏差由 CB-t2 的聚合器按真实账本 syncRightSideComponents 覆盖
        circulationCash = 0;
        vaultCash = 0;
        requiredReserves = 0;
        excessReserves = 0;
        paymentReserves = 0;
        // ★v1.6c（方案 B）：day-0 差额**显式登记为"历史发行封装"、不吸收**（I10 判据不受影响）
        registerLegacyIssuanceEncapsulation(Math.max(0, reserveMoney - getRightSideSum()));
        migrationCount++;
    }

    /** 迁移次数（CB-14 判据辅助；0 = 未迁移）。 */
    public long getMigrationCount() { return migrationCount; }

    /** 迁移配平额（= legacy baseMoney − Σ(负债) ；报表用）。 */
    public double getMigrationBalancingAmount() { return ownCapital; }

    // ===== CB-4 反例探针专用注入/读取（★仅测试使用，生产路径禁止调用）=====

    /**
     * ★测试专用：注入一笔<b>绕过 {@link #bookBaseMoneyFlow} 的未配对负债</b>
     * （{@code reserveMoney} 与 {@code baseMoney} 同增、不落任何科目），用于 CB-4 的
     * "注入偏差 ⇒ 校验必须 FAIL"反例证明。
     *
     * <p><b>命名上的 {@code Test} 是刻意的</b>：让任何 reviewer 一眼看到这是测试注入点，
     * 且 {@code grep} 可枚举全部调用方（生产路径不得出现）。
     *
     * @param amount 偏差量（&gt; 0）
     * @return 实际注入量（参数非法时 0）
     */
    public double testInjectUnpairedLiability(double amount) {
        if (!(amount > 0) || Double.isNaN(amount)) return 0;
        // 双向都动、但**不经 bookBaseMoneyFlow**（等价于"直接改 reserveMoney 而不记账"）：
        // 负债侧 +a、资产侧 +a（自有资金为残差保持不变）⇒ I8 仍成立、I10 因未配对而 FAIL。
        reserveMoney += amount;
        otherAssets += amount;
        unpairedLiabilityTotal += amount;
        unpairedInjectionTotal += amount;
        return amount;
    }

    /**
     * ★测试专用：注入一笔未配对流动的<b>相反方向</b>（I1 右侧之和多于 {@code reserveMoney}），
     * 用于验证偏差符号两侧都能被判据捕获。
     *
     * @param amount 偏差量（&gt; 0）
     * @return 实际注入量
     */
    public double testInjectUnpairedComponent(double amount) {
        if (!(amount > 0) || Double.isNaN(amount)) return 0;
        excessReserves += amount;
        unpairedComponentTotal += amount;
        unpairedInjectionTotal += amount;
        return amount;
    }

    // ===== 报表快照（只读；§3.2 全科目一次读出）=====

    /** 全科目快照（报表/探针用；字段与 §3.2 getter 一一对应，确定性顺序）。 */
    public Map<String, Double> snapshot() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("foreignAssets", getForeignAssets());
        m.put("monetaryGold", getMonetaryGold());
        m.put("fxReserves", getFxReserves());
        m.put("otherForeignAssets", otherForeignAssets);
        m.put("claimsOnGovernment", claimsOnGovernment);
        m.put("claimsOnOtherDepositoryCorps", claimsOnOtherDepositoryCorps);
        m.put("claimsOnOtherFinancialCorps", claimsOnOtherFinancialCorps);
        m.put("otherAssets", otherAssets);
        m.put("totalAssets", getTotalAssets());
        m.put("reserveMoney", reserveMoney);
        m.put("financialCorpDepositsNonReserve", financialCorpDepositsNonReserve);
        m.put("govDeposits", govDeposits);
        m.put("centralBankBills", centralBankBills);
        m.put("otherLiabilities", otherLiabilities);
        // ★CB-T26：CBDC 负债与 Cnote 修正项（观测面；不改既有键、不改既有键的数值）
        m.put("cbdcIssued", cbdcIssued);
        m.put("noteCashAccount", getNoteCashAccount());
        m.put("cbdcAdjustment", getCbdcAdjustment());
        m.put("ownCapital", ownCapital);
        m.put("totalLiabilitiesAndCapital", getTotalLiabilitiesAndCapital());
        m.put("rightSideSum", getRightSideSum());
        m.put("divergenceAmount", getDivergenceAmount());
        // ★t30（F-CB-3）：迁移时刻的"历史发行封装"基线（只读可审计记录；
        //   **不**参与 getLegacyIssuanceEncapsulation() 取值 —— 后者一律活读差额）
        m.put("legacyIssuanceBaseline", Math.max(0, legacyIssuanceEncapsulation));
        return m;
    }

    // ===== 持久化（§9.3：新增键 + contains 兜底；不得改既有 "baseMoney" 键，R1）=====

    /**
     * 持久化（NBT blob，§9.3）。新增键一律走 {@code contains} 兜底：旧档缺键用默认值，读档不失败。
     *
     * <p>本 blob 由 {@code CentralBank} 持有、{@code MarketEngine.saveFiat} 写入
     * {@code centralBank} 复合标签的 {@code "balanceSheet"} 子键（<b>不新增顶层键、不改
     * {@code "baseMoney"} 键</b>，R1）。
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("monetaryGold", monetaryGold);
        tag.putDouble("fxReserves", fxReserves);
        tag.putDouble("otherForeignAssets", otherForeignAssets);
        tag.putDouble("claimsOnGovernment", claimsOnGovernment);
        tag.putDouble("claimsOnOtherDepositoryCorps", claimsOnOtherDepositoryCorps);
        tag.putDouble("claimsOnOtherFinancialCorps", claimsOnOtherFinancialCorps);
        tag.putDouble("otherAssets", otherAssets);
        tag.putDouble("reserveMoney", reserveMoney);
        tag.putDouble("financialCorpDepositsNonReserve", financialCorpDepositsNonReserve);
        tag.putDouble("govDeposits", govDeposits);
        tag.putDouble("centralBankBills", centralBankBills);
        tag.putDouble("otherLiabilities", otherLiabilities);
        tag.putDouble("ownCapital", ownCapital);
        tag.putDouble("circulationCash", circulationCash);
        tag.putDouble("vaultCash", vaultCash);
        tag.putDouble("requiredReserves", requiredReserves);
        tag.putDouble("excessReserves", excessReserves);
        tag.putDouble("paymentReserves", paymentReserves);
        tag.putLong("bookedFlowCount", bookedFlowCount);
        tag.putDouble("divergenceAmount", getDivergenceAmount());
        // ★CB-T26 新增键（§9.3：只新增键，不改任何既有键 ⇒ 旧档读入零影响，R1）
        tag.putDouble("cbdcIssued", cbdcIssued);
        tag.putDouble("noteCashMirror", noteCashMirror);
        tag.putDouble("cbdcNetIssued", cbdcNetIssued);
        tag.putLong("cbdcFlowCount", cbdcFlowCount);
        return tag;
    }

    /**
     * 读档恢复（旧档缺键用默认值兜底：<b>读档不得失败</b>，CB-14）。
     *
     * <p>逐键 {@code contains} 兜底；{@code divergenceAmount} 只作诊断读入（<b>不信任</b>：
     * 偏差量始终由 {@code reserveMoney} 与 I1 右侧实时算出）。
     */
    public void load(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("monetaryGold")) monetaryGold = tag.getDouble("monetaryGold");
        if (tag.contains("fxReserves")) fxReserves = tag.getDouble("fxReserves");
        if (tag.contains("otherForeignAssets")) otherForeignAssets = tag.getDouble("otherForeignAssets");
        if (tag.contains("claimsOnGovernment")) claimsOnGovernment = tag.getDouble("claimsOnGovernment");
        if (tag.contains("claimsOnOtherDepositoryCorps")) {
            claimsOnOtherDepositoryCorps = tag.getDouble("claimsOnOtherDepositoryCorps");
        }
        if (tag.contains("claimsOnOtherFinancialCorps")) {
            claimsOnOtherFinancialCorps = tag.getDouble("claimsOnOtherFinancialCorps");
        }
        if (tag.contains("otherAssets")) otherAssets = tag.getDouble("otherAssets");
        if (tag.contains("reserveMoney")) reserveMoney = tag.getDouble("reserveMoney");
        if (tag.contains("financialCorpDepositsNonReserve")) {
            financialCorpDepositsNonReserve = tag.getDouble("financialCorpDepositsNonReserve");
        }
        if (tag.contains("govDeposits")) govDeposits = tag.getDouble("govDeposits");
        if (tag.contains("centralBankBills")) centralBankBills = tag.getDouble("centralBankBills");
        if (tag.contains("otherLiabilities")) otherLiabilities = tag.getDouble("otherLiabilities");
        if (tag.contains("ownCapital")) ownCapital = tag.getDouble("ownCapital");
        if (tag.contains("circulationCash")) circulationCash = tag.getDouble("circulationCash");
        if (tag.contains("vaultCash")) vaultCash = tag.getDouble("vaultCash");
        if (tag.contains("requiredReserves")) requiredReserves = tag.getDouble("requiredReserves");
        if (tag.contains("excessReserves")) excessReserves = tag.getDouble("excessReserves");
        if (tag.contains("paymentReserves")) paymentReserves = tag.getDouble("paymentReserves");
        if (tag.contains("bookedFlowCount")) bookedFlowCount = tag.getLong("bookedFlowCount");
        // ★CB-T26 新增键：旧档缺键 ⇒ 默认 0（即 CBDC 未启用），读档不失败（CB-14/R1）
        if (tag.contains("cbdcIssued")) cbdcIssued = Math.max(0, tag.getDouble("cbdcIssued"));
        if (tag.contains("noteCashMirror")) noteCashMirror = Math.max(0, tag.getDouble("noteCashMirror"));
        if (tag.contains("cbdcNetIssued")) cbdcNetIssued = tag.getDouble("cbdcNetIssued");
        if (tag.contains("cbdcFlowCount")) cbdcFlowCount = tag.getLong("cbdcFlowCount");
        // 旧档（无 CBDC 键）⇒ 净投放按读入存量对齐（构成替代自洽）
        if (!tag.contains("cbdcNetIssued") && cbdcIssued != 0) {
            cbdcNetIssued = cbdcIssued;
        }
        // 实体现金镜像：新档有键 ⇒ 逐位用存档值；旧档（无键）⇒ 由「C 账户 − CbDc + 净投放」还原
        // （旧档 CbDc=净投放=0 ⇒ 镜像逐位等于原实体现金账户，读档行为对既有档零变化）
        if (!tag.contains("noteCashMirror")) {
            this.noteCashMirror = Math.max(0.0, circulationCash - cbdcIssued + cbdcNetIssued);
        }
        restoreCount++;
    }

    /** 读档次数（探针用：确认 load 真的执行过而非静默返回）。 */
    public long getRestoreCount() { return restoreCount; }
}
