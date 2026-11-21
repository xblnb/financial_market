package com.example.financialmarket.engine.monetary;

import java.util.List;

/**
 * 政策事件（CENTRAL_BANK_SPEC.md v1.5 §7.2 C7 / §9.5 政策事件通道；CB-t1 冻结，<b>实现权归 CB-t3</b>）。
 *
 * <p>定位：把「降准 / 降息 / OMO / 救助 / 接管」等政策动作变成<b>可观测的事件</b>（§1.6 P7），
 * 供 UI 叙事、宏观不确定性跳跃（{@code MacroEnvironment} 的 {@code p += POLICY_EVENT_JUMP[kind]}）
 * 与验收断言（CB-13/CB-21/CB-23）使用。事件环由 {@code MarketEngine} 持有（有界、确定性顺序）。
 *
 * <p><b>冻结口径</b>
 * <ul>
 *   <li>{@code tick}：引擎 tickCounter（确定性）。</li>
 *   <li>{@code delta}：动作幅度——利率用 bp 前的<b>小数</b>口径（与引擎内利率同单位）、准备金率用 pp 小数、金额用绝对额；
 *       语义由 {@code kind} 决定（§3.4 {@code getLastPolicyActionDelta()} 同口径）。</li>
 *   <li>{@code source}：{@link #SOURCE_PLAYER}/{@link #SOURCE_AI_AUTOPILOT}/{@link #SOURCE_GUARDRAIL_CLAMP}
 *       （§6.9；被钳制必须留痕，不得静默）。</li>
 *   <li>环容量 {@link #RING_CAPACITY} = 64（§9.5，确定性顺序，满环丢最旧）。</li>
 * </ul>
 *
 * <p><b>★kind 名单冻结（登记）</b>：§9.5 列 14 个核心 kind；另有 4 个由硬条件条款<b>显式要求</b>的事件
 * （CB-21 的 {@code TAKEOVER}/{@code RETURN}、§0.10.6 的 {@code MIGRATION}、§6.10.1 的 {@code TROUBLED_STATE}）。
 * ⇒ 本类冻结 {@link #CORE_KINDS}（14）+ {@link #EXTENDED_KINDS}（4）= {@link #ALL_KINDS}（18）三级名单，
 * 避免 CB-t3 / CB-t7 / CB-t8 各按一半实现（对比见 {@code .verify/CB_INTERFACE_FREEZE.md} §5）。
 */
public record PolicyEvent(long tick, String kind, double delta, String source) {

    /** 事件环容量（§9.5 {@code POLICY_EVENT_RING}）。 */
    public static final int RING_CAPACITY = 64;

    /** 决策来源：玩家（央行行长）手动决策。 */
    public static final String SOURCE_PLAYER = "PLAYER";
    /** 决策来源：AI 兜底自动驾驶（无玩家/离线）。 */
    public static final String SOURCE_AI_AUTOPILOT = "AI_AUTOPILOT";
    /** 决策来源：被 {@code CentralBankerGuardrail} 钳制覆盖（必须留痕）。 */
    public static final String SOURCE_GUARDRAIL_CLAMP = "GUARDRAIL_CLAMP";

    // ===== §9.5 核心 kind（14）=====
    /** 降准。 */
    public static final String RR_CUT = "RR_CUT";
    /** 加准。 */
    public static final String RR_HIKE = "RR_HIKE";
    /** 降息。 */
    public static final String RATE_CUT = "RATE_CUT";
    /** 加息。 */
    public static final String RATE_HIKE = "RATE_HIKE";
    /** OMO 投放。 */
    public static final String OMO_INJECT = "OMO_INJECT";
    /** OMO 回笼。 */
    public static final String OMO_DRAIN = "OMO_DRAIN";
    /** MLF 操作。 */
    public static final String MLF = "MLF";
    /** SLF 借出。 */
    public static final String SLF_LEND = "SLF_LEND";
    /** 最后贷款人救助（§6.10.2④）。 */
    public static final String LAST_RESORT = "LAST_RESORT";
    /** 财政赤字货币化（Q6；默认关闭）。 */
    public static final String FISCAL_DOMINANCE = "FISCAL_DOMINANCE";
    /** 央票发行/到期（§6.8）。 */
    public static final String BILL_ISSUE = "BILL_ISSUE";
    /** CBDC 调整（Q4）。 */
    public static final String CBDC_ADJUST = "CBDC_ADJUST";
    /** 恒等式违规留痕（I1..I10 失败，不得静默）。 */
    public static final String IDENTITY_VIOLATION = "IDENTITY_VIOLATION";
    /** 被制度约束钳制留痕（§13.5）。 */
    public static final String GUARDRAIL_CLAMP = "GUARDRAIL_CLAMP";

    // ===== 硬条件条款显式要求的事件 kind（4）=====
    /** 玩家接管央行（Q1；CB-21）。 */
    public static final String TAKEOVER = "TAKEOVER";
    /** 玩家交还央行（Q1；CB-21）。 */
    public static final String RETURN = "RETURN";
    /** 旧存档迁移留痕（§0.10.6；CB-14）。 */
    public static final String MIGRATION = "MIGRATION";
    /** 问题银行状态转移（§6.10.1；CB-23）。 */
    public static final String TROUBLED_STATE = "TROUBLED_STATE";

    /** §9.5 核心 kind 名单（14 个，冻结顺序）。 */
    public static final List<String> CORE_KINDS = List.of(
            RR_CUT, RR_HIKE, RATE_CUT, RATE_HIKE, OMO_INJECT, OMO_DRAIN, MLF, SLF_LEND,
            LAST_RESORT, FISCAL_DOMINANCE, BILL_ISSUE, CBDC_ADJUST, IDENTITY_VIOLATION, GUARDRAIL_CLAMP);

    /** 硬条件条款新增 kind 名单（4 个，冻结顺序）。 */
    public static final List<String> EXTENDED_KINDS = List.of(TAKEOVER, RETURN, MIGRATION, TROUBLED_STATE);

    /** 全部合法 kind（18 个 = 14 核心 + 4 扩展）。 */
    public static final List<String> ALL_KINDS = List.of(
            RR_CUT, RR_HIKE, RATE_CUT, RATE_HIKE, OMO_INJECT, OMO_DRAIN, MLF, SLF_LEND,
            LAST_RESORT, FISCAL_DOMINANCE, BILL_ISSUE, CBDC_ADJUST, IDENTITY_VIOLATION, GUARDRAIL_CLAMP,
            TAKEOVER, RETURN, MIGRATION, TROUBLED_STATE);

    /** kind 是否合法（数据查表；用于「不得静默」断言与 UI 着色）。 */
    public static boolean isKnownKind(String kind) { return ALL_KINDS.contains(kind); }

    /** source 是否合法（{@code PLAYER}/{@code AI_AUTOPILOT}/{@code GUARDRAIL_CLAMP}）。 */
    public static boolean isKnownSource(String source) {
        return SOURCE_PLAYER.equals(source) || SOURCE_AI_AUTOPILOT.equals(source)
                || SOURCE_GUARDRAIL_CLAMP.equals(source);
    }

    // ===== CB-t3：事件构造与有序性工具（确定性，无随机/无时钟）=====

    /**
     * 构造一条事件（便捷工厂；CB-t3 的 {@code CentralBank.recordPolicyEvent} 使用）。
     *
     * @param tick   引擎 tick
     * @param kind   事件 kind（须在 {@link #ALL_KINDS} 内）
     * @param delta  幅度
     * @param source 决策来源（须在 3 个合法 source 内）
     * @throws IllegalArgumentException kind/source 非法时抛出（<b>响亮失败</b>，不静默落一条脏事件）
     */
    public static PolicyEvent of(long tick, String kind, double delta, String source) {
        if (!isKnownKind(kind)) {
            throw new IllegalArgumentException("未知政策事件 kind: " + kind);
        }
        if (!isKnownSource(source)) {
            throw new IllegalArgumentException("未知政策事件 source: " + source);
        }
        return new PolicyEvent(tick, kind, delta, source);
    }

    /**
     * 是否"投放方向"事件（投放/宽松：{@code RATE_CUT}/{@code RR_CUT}/{@code OMO_INJECT}/…）。
     * 供 UI 着色与验证的方向性断言使用（{@code delta} 始终携带非负幅度）。
     */
    public boolean isEasing() {
        return RATE_CUT.equals(kind) || RR_CUT.equals(kind) || OMO_INJECT.equals(kind)
                || MLF.equals(kind) || SLF_LEND.equals(kind) || LAST_RESORT.equals(kind);
    }

    /**
     * 是否"回笼方向"事件（收紧：{@code RATE_HIKE}/{@code RR_HIKE}/{@code OMO_DRAIN}/{@code BILL_ISSUE}）。
     */
    public boolean isTightening() {
        return RATE_HIKE.equals(kind) || RR_HIKE.equals(kind) || OMO_DRAIN.equals(kind)
                || BILL_ISSUE.equals(kind);
    }

    /** 是否异常/被覆盖留痕类事件（{@code IDENTITY_VIOLATION}/{@code GUARDRAIL_CLAMP}）——不得静默。 */
    public boolean isAnomalyTrace() {
        return IDENTITY_VIOLATION.equals(kind) || GUARDRAIL_CLAMP.equals(kind);
    }

    /** 与另一条事件的确定性先后比较（tick 升序；同 tick 视为不可比 ⇒ 0）。 */
    public int compareTick(PolicyEvent other) {
        if (other == null) return 1;
        return Long.compare(this.tick, other.tick);
    }

    /** 单行摘要（中文，供命令/日志输出；不含换行，避免破坏表格归档）。 */
    public String summary() {
        return "tick=" + tick + " " + kind + " Δ=" + delta + " (" + source + ")";
    }
}
