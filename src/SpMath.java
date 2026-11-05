package claudecaptain;

/**
 * 1.1.30 (ADR-0004, player's decision Nov 4): when a hostile fleet far stronger than ours catches us, the mod may
 * spend one story point on the game's own "special maneuvers" escape instead of pausing for the player.
 * Pure decision; the game side is in {@link AutoDialog} and {@link DialogBridge#spendStoryPoint}.
 */
public final class SpMath {

    private SpMath() {}

    /**
     * Null when the escape may be bought, else why not.
     * @param enabled  rule sp_auto_escape
     * @param enemyDP  everything on the other side of the encounter
     * @param ourDP    our combat strength
     * @param ratio    rule sp_auto_escape_ratio: the enemy must be at least this many times our strength
     * @param sp       story points the player has
     * @param cost     story points the option costs
     * @param keep     rule sp_auto_escape_keep: never go below this many story points
     */
    public static String escapeBlocked(boolean enabled, float enemyDP, float ourDP, float ratio, int sp, int cost, int keep) {
        if (!enabled) return "sp_auto_escape is off";
        if (cost != 1) return "the escape costs " + cost + " story points (only 1-point escapes are automatic)";
        if (ourDP <= 0f) return "our strength is unknown";
        if (enemyDP < ourDP * ratio) return Math.round(enemyDP) + " DP vs our " + Math.round(ourDP) + " is under " + ratio + "x";
        if (sp - cost < keep) return "only " + sp + " story points left (keeping " + keep + ")";
        return null;
    }
}
