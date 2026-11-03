package claudecaptain;

/**
 * 1.0.7: pure travel arithmetic (no game objects, unit tested).
 *  - jumps: leaving a star system and entering one each cost a jump; hyperspace legs don't.
 *  - fuel: every jump through a jump point burns one light-year's worth of fuel on top of the distance.
 *  - eta: days to cover a distance at the fleet's travel speed, plus a little per jump.
 *  - deadline: whether a target with a timer can still be reached with margin.
 *  - transverse: when the Travel goal should fire Transverse Jump (deep-space systems have no jump points).
 */
public final class TravelMath {
    private TravelMath() {}

    /** Jumps between two locations: 0 in the same place, else one per star-system end. */
    public static int jumps(boolean same, boolean fromHyper, boolean toHyper) {
        if (same) return 0;
        return (fromHyper ? 0 : 1) + (toHyper ? 0 : 1);
    }

    /** Fuel for a trip: distance plus one light-year per jump, times the fleet's fuel per light-year. */
    public static float fuel(float ly, int jumps, float fuelPerLy) {
        if (Float.isNaN(ly) || ly < 0f) ly = 0f;
        return (ly + Math.max(0, jumps)) * Math.max(0f, fuelPerLy);
    }

    /** Fuel with the governor's margins (percent of the distance plus a flat light-year margin) and the jump cost. */
    public static float fuelWithMargin(float ly, int jumps, float fuelPerLy, float marginPct, float marginLy) {
        if (Float.isNaN(ly) || ly < 0f) return 0f;
        return (ly * (1f + marginPct / 100f) + marginLy + Math.max(0, jumps)) * Math.max(0f, fuelPerLy);
    }

    /**
     * Days to travel: ly x units-per-ly / (speed x seconds-per-day), plus half a day per jump for the in-system legs.
     * Returns a large number when the speed is unknown.
     */
    public static float etaDays(float ly, int jumps, float speedSuPerSec, float unitsPerLy, float secondsPerDay) {
        if (speedSuPerSec <= 0f || secondsPerDay <= 0f) return 9999f;
        if (Float.isNaN(ly) || ly < 0f) ly = 0f;
        return ly * unitsPerLy / (speedSuPerSec * secondsPerDay) + 0.5f * Math.max(0, jumps);
    }

    /** True when a timed target (daysLeft > 0) can't be reached with margin: eta x mult + extra days of chasing. */
    public static boolean missesDeadline(float daysLeft, float etaDays, float mult, float extraDays) {
        if (daysLeft <= 0f) return false; // no timer
        return etaDays * Math.max(1f, mult) + Math.max(0f, extraDays) > daysLeft;
    }

    public static final String NONE = "none", STEER_WELL = "steer_well", JUMP_IN = "jump_in", JUMP_OUT = "jump_out";

    /**
     * Transverse Jump decision for the Travel goal.
     * @param inHyper       the fleet is in hyperspace
     * @param destDeep      the destination is in a star system without jump points (deep space)
     * @param hereDeep      the fleet is in a star system without jump points
     * @param sameLoc       fleet and destination are in the same location
     * @param wellDist      distance to the destination system's nearest nascent gravity well (su, edge to edge), or -1 if none
     * @param jumpRange     how close the well must be (FractureJumpAbility.NASCENT_JUMP_DIST, 50 su)
     * @param usable        the ability is usable right now (not on cooldown, not active)
     */
    public static String transverse(boolean inHyper, boolean destDeep, boolean hereDeep, boolean sameLoc,
                                    float wellDist, float jumpRange, boolean usable) {
        if (sameLoc) return NONE;
        if (inHyper) {
            if (!destDeep || wellDist < 0f) return NONE;
            if (wellDist <= jumpRange) return usable ? JUMP_IN : STEER_WELL;
            return STEER_WELL;
        }
        // in a system without jump points, going elsewhere: the only way out is Transverse Jump
        if (hereDeep) return usable ? JUMP_OUT : NONE;
        return NONE;
    }

    /**
     * 1.1.10 (Nov 2, player): combat readiness recovers while the fleet travels, so a rest before a trip is wasted
     * when the trip takes at least as long as the recovery. True: leave now and recover on the way.
     */
    public static boolean recoverEnRoute(float daysToRecover, float etaDays, float etaFactor) {
        if (daysToRecover <= 0f) return true;
        if (etaDays <= 0f) return false;
        return etaDays * Math.max(0.1f, etaFactor) >= daysToRecover;
    }

    /**
     * 1.1.14 (player, Nov 3): Full Assault makes every ship charge and ignore its own safety. It is ordered only when we
     * clearly outweigh the enemy (ours >= theirs x ratio); ratio <= 0 never orders it. 262 vs 238 DP at 1.0x lost
     * two Retributions and a Dominator to the charge.
     */
    public static boolean fullAssault(float ourDP, float theirDP, float ratio) {
        if (ratio <= 0f) return false;
        if (theirDP <= 0f) return true;
        return ourDP >= theirDP * ratio;
    }

    /** Days for one ship to climb from cr to want at ratePerDay (fractions); 0 when it is already there. */
    public static float daysToCr(float cr, float want, float ratePerDay) {
        if (cr >= want) return 0f;
        return (want - cr) / Math.max(0.001f, ratePerDay);
    }
}
