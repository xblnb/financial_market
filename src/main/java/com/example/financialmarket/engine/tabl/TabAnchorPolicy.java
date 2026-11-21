package com.example.financialmarket.engine.tabl;

/**
 * Anchor policy for the agent layer (T4 "wrap, don't rewrite" approach).
 *
 * <p>{@code MultiAgentManager} already decides <em>what</em> an agent wants; this class only bends
 * the resulting price towards the TABL anchor, without touching the agent internals. It is a pure
 * function: same inputs, same output, no state, no clock, no randomness.</p>
 *
 * <pre>
 *   p' = p + strength * (anchor(f) - p)
 *   |p' - anchor(f)| <= clamp * band(f)
 * </pre>
 *
 * <p>{@code strength = 0} leaves the agent untouched (bit-identical legacy behaviour, which is what
 * the {@code tabl.enabled=false} switch relies on); {@code strength = 1} snaps to the anchor.</p>
 */
public final class TabAnchorPolicy {

    private final double strength;
    private final double clamp;

    public TabAnchorPolicy(double strength, double clamp) {
        this.strength = Math.max(0.0, Math.min(1.0, strength));
        this.clamp = Math.max(0.0, clamp);
    }

    public double strength() {
        return strength;
    }

    /** Adjusts a price towards the anchor at day fraction {@code f}; never returns a non-positive price. */
    public double adjust(long agentPrice, AnchorPath anchor, double fraction) {
        double target = anchor.at(fraction);
        double delta = strength * (target - agentPrice);
        double band = Math.max(0.0, anchor.bandAt(fraction));
        double limit = clamp * band;
        if (limit > 0) {
            delta = Math.max(-limit, Math.min(limit, delta));
        }
        return Math.max(1.0, agentPrice + delta);
    }

    /** Convenience for integer price ticks. */
    public long adjustTicks(long agentPrice, AnchorPath anchor, double fraction) {
        return Math.max(1L, Math.round(adjust(agentPrice, anchor, fraction)));
    }

    /** True when the policy is a no-op (used to short-circuit the adapter). */
    public boolean isNoOp() {
        return strength == 0.0;
    }
}
