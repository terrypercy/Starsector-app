package claudecaptain;

/**
 * 1.0.4: how the hunt closes on a bounty fleet that runs (unit-tested, no game classes).
 * Pirates that see a stronger fleet flee at a higher burn than ours. The game's answers, in order:
 * Interdiction Pulse once inside its range (drives knocked out), Sustained Burn to close a long gap
 * (it blocks the pulse, so it is switched off when the pulse is ready and in range), and one
 * Emergency Burn for the last few hundred su when the gap is not closing.
 */
public final class ChaseMath {
    private ChaseMath() {}

    public static final String NONE = "none", PULSE = "pulse", SB_ON = "sb_on", SB_OFF = "sb_off", EB = "eb";

    public static String decide(float dist, float pulseRange, boolean pulseUsable, boolean pulseActive,
                                boolean sbUsable, boolean sbActive, boolean ebUsable, boolean ebActive,
                                boolean closing, boolean ebUsed) {
        if (pulseActive || ebActive) return NONE;                       // charging / burning: let it play out
        boolean inPulse = dist < pulseRange - 100f;
        if (inPulse && pulseUsable) return sbActive ? SB_OFF : PULSE;
        if (inPulse && sbActive && !pulseUsable) return SB_OFF;         // close enough: SB only overshoots now
        if (!closing && dist < 700f && ebUsable && !ebUsed) return EB;
        if (!closing && !sbActive && sbUsable && dist > Math.max(1200f, pulseRange)) return SB_ON;
        if (sbActive && dist < 900f) return SB_OFF;
        return NONE;
    }
}
