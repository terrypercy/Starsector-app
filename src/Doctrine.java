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

    /**
     * 1.1.17 (player, Nov 3, after Nil Heze): wave 2 goes in when wave 1 holds at least half the objectives,
     * or wave 1 is losing (its strength, DP weighted by hull, is under wave1LosingBelow of what it started with),
     * or - only as a late fallback - after afterS seconds. Right away when the map has no objectives.
     */
    public static boolean wave2Due(int heldObjectives, int objectives, float elapsedS, float afterS, float wave1Strength, float wave1LosingBelow) {
        if (objectives == 0) return true;
        if (heldObjectives * 2 >= objectives) return true;
        if (wave1Strength < wave1LosingBelow) return true;
        return elapsedS >= afterS;
    }

    /** 1.1.17: the line has formed once most of wave 2 has reached the front, or after maxS as a fallback. */
    public static boolean lineFormed(float arrivedFrac, float sinceWave2S, float arrivedNeeded, float maxS) {
        return arrivedFrac >= arrivedNeeded || sinceWave2S >= maxS;
    }

    /**
     * Pull a ship back: hull under hullLimit; or more than half its weapons permanently destroyed AND the hull
     * already under weaponsHullLimit. 1.1.17: weapons that are only knocked out for a few seconds (EMP, flame-outs)
     * no longer count - that rule sent ships at 98-100% hull home against Nil Heze and got them shot in the back.
     */
    public static boolean shouldRetreat(float hullFrac, int weapons, int destroyed, float hullLimit, float weaponsHullLimit) {
        if (hullFrac < hullLimit) return true;
        return weapons > 0 && destroyed * 2 > weapons && hullFrac < weaponsHullLimit;
    }

    /** Back off when hard flux is high; return to the line when it has drained. */
    public static boolean fluxBackOff(float hardFluxLevel) { return hardFluxLevel >= 0.8f; }
    public static boolean fluxRecovered(float hardFluxLevel) { return hardFluxLevel <= 0.4f; }
}
