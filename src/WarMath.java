package claudecaptain;

/**
 * 1.1.20 (player, Nov 3: "use war for income"): pure helpers for picking war targets.
 *
 * Corvus (Jan 31 c216) showed the old check was blind: the target was a 72 DP convoy, but a patrol, a picket,
 * a detachment and the convoy's own escort were all within a few thousand su and joined the battle - 26 ships.
 * A target is now judged by the DP of every hostile fleet (and station) within hunt_group_radius of it.
 */
public final class WarMath {

    private WarMath() {}

    /**
     * Total DP the player would face attacking one fleet: its own DP plus every other hostile within radius.
     * @param ownDP the target's DP
     * @param otherDP DP of the other hostile fleets in the system (stations included)
     * @param otherDist their distance to the target in su
     * @param radius join radius in su
     */
    public static float groupDP(float ownDP, float[] otherDP, float[] otherDist, float radius) {
        float sum = ownDP;
        if (otherDP == null || otherDist == null) return sum;
        for (int i = 0; i < otherDP.length && i < otherDist.length; i++) {
            if (otherDist[i] <= radius) sum += otherDP[i];
        }
        return sum;
    }

    /** True when the faction's fleets are fair war targets: hostile, and either listed or any hostile faction is allowed. */
    public static boolean warTarget(String factionId, boolean hostile, String list) {
        if (!hostile || factionId == null) return false;
        if (list == null || list.trim().isEmpty() || list.trim().equalsIgnoreCase("all")) return true;
        return SupplyMath.listed(list, factionId);
    }
}
