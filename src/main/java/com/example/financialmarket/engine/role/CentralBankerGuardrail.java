package com.example.financialmarket.engine.role;

import com.example.financialmarket.engine.macro.MacroEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 央行行长制度约束（t3/SR-3/SR-5/SR-7）：泰勒规则建议 + 经济后果/问责 + AI 兜底接管。
 *
 * <p>约束对象为 t2 {@code RoleSystem} 中 {@code CENTRAL_BANKER} 角色的货币政策操作。
 * 机制分为三层：
 * <ol>
 *   <li><strong>泰勒建议</strong>：{@link #suggestedRate()} 依通胀与产出缺口给出建议政策利率（仅提示，不强制）。</li>
 *   <li><strong>经济后果/问责</strong>：{@link #recordRateChange(double)} 在央行行长设定利率时评估其对建议的偏离；
 *       偏离过大（持续）或在通胀高位未跟随上调、或衰退中过度紧缩时，记录<strong>纪律事件</strong>与后果标签。</li>
 *   <li><strong>AI 兜底接管</strong>：央行行长<strong>离线</strong>或<strong>失职</strong>（持续偏离建议）时，
 *       {@link #shouldEngageAutopilot()} 判定由 AI 接管货币政策，{@link #applyAIAutopilot()} 确定性将利率向建议靠拢。</li>
 * </ol>
 *
 * <p><strong>确定性</strong>：全部为确定性函数（泰勒公式、钳制、偏差/问责判定、靠拢步进均无随机），
 * 同种子同输入同结果，对齐 {@code ECON_IMPROVEMENT_PLAN} §6。
 *
 * <p>问责（通胀/衰退）<strong>不自动惩罚玩家</strong>（避免竞态接管），只记录可观察的纪律事件（SR-3 设计）。
 */
public final class CentralBankerGuardrail {

    /** 单次改动/偏离建议超过该阈值视为显著（±0.5%）。 */
    public static final double DEVIATION_THRESHOLD = 0.005;
    /** 高通胀阈值（通胀率 &gt; 该值视为高通胀）。 */
    public static final double HIGH_INFLATION_THRESHOLD = 0.10;
    /** 衰退健康阈值（economicHealth &lt; 该值视为衰退）。 */
    public static final double RECESSION_HEALTH_THRESHOLD = -0.30;
    /** 高通胀 / 偏离持续 N 交易日触发问责/接管。 */
    public static final int ACCOUNTABILITY_DAYS = 10;
    /** AI 接管的单步靠拢系数（0~1，确定性）。 */
    public static final double STEER_FACTOR = 0.5;

    private MacroEnvironment macro;
    private double inflationRate = 0.02;
    private double economicHealth = 0.0;
    private double currentRate = 0.03;
    private boolean centralBankerOnline = true;

    private int highInflationStreak = 0;
    private int deviationStreak = 0;
    private boolean autopilotEngaged = false;

    private final List<String> accountabilityEvents = new ArrayList<>();

    public CentralBankerGuardrail() {
        this(null);
    }

    /** 可选注入 {@link MacroEnvironment} 以复用其中性利率与 AI 兜底（未注入则用默认 r*=0.035）。 */
    public CentralBankerGuardrail(MacroEnvironment macro) {
        this.macro = macro;
    }

    public void setMacroEnvironment(MacroEnvironment macro) {
        this.macro = macro;
    }

    public MacroEnvironment getMacroEnvironment() {
        return macro;
    }

    // ---------- 输入（通常由引擎 tick / 验证驱动） ----------

    /** 更新通胀与产出健康（经济周期）观测值。 */
    public void updateInputs(double inflationRate, double economicHealth) {
        this.inflationRate = inflationRate;
        this.economicHealth = economicHealth;
    }

    /** 央行行长是否在线（交易节中维护；离线则 AI 兜底接管）。 */
    public void setCentralBankerOnline(boolean online) {
        this.centralBankerOnline = online;
    }

    public boolean isCentralBankerOnline() {
        return centralBankerOnline;
    }

    public double getCurrentRate() {
        return currentRate;
    }

    public double getInflationRate() {
        return inflationRate;
    }

    public double getEconomicHealth() {
        return economicHealth;
    }

    // ---------- 泰勒规则建议利率 ----------

    /**
     * 简化泰勒规则建议政策利率：{@code i = r* + π + 0.5·(π − π*) + 0.5·y_gap}，
     * {@code y_gap = economicHealth − 1}，结果钳制到硬域。仅作建议，不强制。
     */
    public double suggestedRate() {
        double neutral = (macro != null) ? macro.getNeutralRate() : MacroEnvironment.NEUTRAL_RATE;
        return MacroEnvironment.taylorSuggestedRate(neutral, inflationRate, economicHealth);
    }

    // ---------- 记录央行行长操作，评估约束 ----------

    /**
     * 央行行长设定利率的约束入口。返回 {@link GuardrailResult}：
     * ① 超出硬域 {@code [RATE_MIN, RATE_MAX]} 视为极端越界 → AI 兜底钳制（accepted=false）；
     * ② 否则记录与建议的偏离，偏离过大/持续触发经济后果与问责事件。
     */
    public GuardrailResult recordRateChange(double requestedRate) {
        double clamped = clamp(requestedRate);
        boolean extreme = clamped != requestedRate; // 越界被 AI 兜底钳制
        double suggested = suggestedRate();
        double deviation = Math.abs(clamped - suggested);
        boolean largeDeviation = deviation > DEVIATION_THRESHOLD;

        if (extreme) {
            deviationStreak = 0;     // 已由钳制处理极端越界，不累积偏离问责
        } else if (largeDeviation) {
            deviationStreak++;
        } else {
            deviationStreak = 0;
        }

        currentRate = clamped;

        // 问责依据：通胀高位未跟随上调 / 衰退中过度紧缩（确定性判定）
        String inflationAcc = "";
        String recessionAcc = "";
        if (inflationRate > HIGH_INFLATION_THRESHOLD) {
            highInflationStreak++;
            if (highInflationStreak >= ACCOUNTABILITY_DAYS && clamped < suggested - DEVIATION_THRESHOLD) {
                inflationAcc = "高通胀" + fmt(inflationRate) + " 持续 " + highInflationStreak
                        + " 交易日且利率低于泰勒建议 (" + fmt(suggested) + ")，触发通胀问责";
                recordEvent("通胀问责：" + inflationAcc);
            }
        } else {
            highInflationStreak = 0;
        }
        if (economicHealth < RECESSION_HEALTH_THRESHOLD && clamped > suggested + DEVIATION_THRESHOLD) {
            recessionAcc = "经济衰退（health=" + fmt(economicHealth)
                    + "）而央行过度紧缩（利率" + fmt(clamped) + " 高于建议 " + fmt(suggested) + "），触发衰退问责";
            recordEvent("衰退问责：" + recessionAcc);
        }

        String aiAction = buildAiAction(extreme, clamped, requestedRate, suggested);
        autopilotEngaged = shouldEngageAutopilot();

        return new GuardrailResult(clamped, !extreme, largeDeviation && deviationStreak > 0,
                aiAction, inflationAcc.isEmpty() && recessionAcc.isEmpty() ? "" : (inflationAcc + recessionAcc).trim(),
                autopilotEngaged);
    }

    // ---------- AI 兜底接管 ----------

    /** 判定是否应由 AI 接管货币政策：央行行长离线 或 持续偏离建议（失职）。 */
    public boolean shouldEngageAutopilot() {
        return !centralBankerOnline || deviationStreak >= ACCOUNTABILITY_DAYS;
    }

    /**
     * AI 接管货币政策：确定性将利率向泰勒建议靠拢一个 {@link #STEER_FACTOR} 步，
     * 并记录接管事件。返回接管后的利率。若已趋于建议，则保持。
     */
    public double applyAIAutopilot() {
        if (!shouldEngageAutopilot()) {
            return currentRate;
        }
        double suggested = suggestedRate();
        double steered = clamp(currentRate + (suggested - currentRate) * STEER_FACTOR);
        if (Math.abs(steered - currentRate) < 1e-9) {
            // 已收敛，无步进
        } else {
            recordEvent("AI 接管货币政策：利率由 " + fmt(currentRate) + " 调整为 " + fmt(steered)
                    + "（泰勒建议 " + fmt(suggested) + "）");
        }
        currentRate = steered;
        autopilotEngaged = true;
        return steered;
    }

    // ---------- 只读观察 ----------

    /** 是否已触发 AI 接管。 */
    public boolean isAutopilotEngaged() {
        return autopilotEngaged;
    }

    /** 当前累积的纪律（问责）事件。 */
    public List<String> getAccountabilityEvents() {
        return Collections.unmodifiableList(accountabilityEvents);
    }

    public int getDeviationStreak() {
        return deviationStreak;
    }

    public int getHighInflationStreak() {
        return highInflationStreak;
    }

    /** 清空问责事件（验证用）。 */
    public void clearAccountabilityEvents() {
        accountabilityEvents.clear();
    }

    // ---------- 内部 ----------

    private void recordEvent(String event) {
        accountabilityEvents.add(event);
        System.out.println("🏦 [央行问责] " + event);
    }

    private String buildAiAction(boolean extreme, double clamped, double requested, double suggested) {
        if (extreme) {
            return "偏离货币政策硬域，利率已由 " + fmt(requested) + " 自动钳制到 " + fmt(clamped);
        }
        double deviation = Math.abs(clamped - suggested);
        if (deviation > DEVIATION_THRESHOLD) {
            return "利率偏离泰勒建议 " + fmt(deviation) + "（建议 " + fmt(suggested)
                    + "），已记录问责；建议回归建议利率";
        }
        return "";
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    private static double clamp(double v) {
        return Math.max(MacroEnvironment.RATE_MIN, Math.min(MacroEnvironment.RATE_MAX, v));
    }

    /**
     * 单次约束执行结果。
     *
     * @param effectiveRate      执行后的有效利率（已钳制）
     * @param accepted           是否采纳原值（false = 越界被 AI 兜底钳制/拒绝）
     * @param consequenceTriggered 是否触发经济后果（持续大幅偏离建议）
     * @param aiAction           AI 兜底动作说明（空串=无介入）
     * @param accountability     问责说明（空串=无问责）
     * @param autopilotEngaged   是否已由 AI 接管货币政策
     */
    public record GuardrailResult(double effectiveRate, boolean accepted, boolean consequenceTriggered,
                                  String aiAction, String accountability, boolean autopilotEngaged) {
    }
}
