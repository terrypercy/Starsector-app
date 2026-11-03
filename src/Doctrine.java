package claudecaptain;

/**
 * 1.1.16 (player, Nov 3): the two-wave battle doctrine. Pure rules so they can be tested outside the game;
 * the combat plugin applies them.
 *
 * Wave 1 (the opener, at most battle_wave1_max_dp): objective cappers (frigates and destroyers), Monitors
 * (stall/escort) and the Eradicator fire-support cruisers. Wave 2 (the core): capitals and the other cruisers,
 * sent once we hold an objective or battle_wave2_after_s has passed, always inside the game's own deployment cap.
 */
public final class Doctrine {
    private Doctrine() {}

    public static final String CAPPER = "capper", STALL = "stall", SUPPORT = "support", CORE = "core", SKIP = "skip";

    /** size: FRIGATE, DESTROYER, CRUISER, CAPITAL_SHIP (HullSize.name()). */
    public static String role(String size, String hullId, boolean civilian) {
        if (civilian) return SKIP;
        String id = hullId == null ? "" : hullId.toLowerCase(java.util.Locale.ROOT);
        if (id.startsWith("monitor")) return STALL;
        if (id.startsWith("eradicator")) return SUPPORT;
        if ("FRIGATE".equals(size) || "DESTROYER".equals(size)) return CAPPER;
        if ("CRUISER".equals(size) || "CAPITAL_SHIP".equals(size)) return CORE;
        return SKIP;
    }

    public static boolean wave1(String role) { return CAPPER.equals(role) || STALL.equals(role) || SUPPORT.equals(role); }

    /** Room left under a cap; a cap of 0 or less means unknown (no limit). */
    public static float room(float cap, float used) { return cap <= 0f ? Float.MAX_VALUE : Math.max(0f, cap - used); }

    /** Wave 2 goes in once we hold an objective, or after the delay, or right away when the map has no objectives. */
    public static boolean wave2Due(int heldObjectives, int objectives, float elapsedS, float afterS) {
        if (objectives == 0) return true;
        return heldObjectives > 0 || elapsedS >= afterS;
    }

    /** Pull a ship back: hull under the threshold, or most of its weapons knocked out. */
    public static boolean shouldRetreat(float hullFrac, int weapons, int disabled, float hullLimit) {
        if (hullFrac < hullLimit) return true;
        return weapons > 0 && disabled * 2 > weapons;
    }

    /** Back off when hard flux is high; return to the line when it has drained. */
    public static boolean fluxBackOff(float hardFluxLevel) { return hardFluxLevel >= 0.8f; }
    public static boolean fluxRecovered(float hardFluxLevel) { return hardFluxLevel <= 0.4f; }
}
