package claudecaptain;

import java.util.List;
import java.util.Locale;

/** 1.0.9: colony-site scoring as plain logic (unit-tested). */
public final class ColonyMath {

    private ColonyMath() {}

    /** Resource deposit conditions (ore_rich, rare_ore_moderate, farmland_good, ruins_vast, ...). */
    static boolean isResource(String id) {
        String i = id == null ? "" : id.toLowerCase(Locale.ROOT);
        return i.startsWith("ore_") || i.startsWith("rare_ore_") || i.startsWith("organics_") || i.startsWith("volatiles_")
                || i.startsWith("farmland_") || i.startsWith("ruins_");
    }

    static float grade(String id) {
        String i = id.toLowerCase(Locale.ROOT);
        String g = i.substring(i.lastIndexOf('_') + 1);
        if (g.equals("sparse") || g.equals("trace") || g.equals("diffuse") || g.equals("poor") || g.equals("scattered")) return 1f;
        if (g.equals("moderate") || g.equals("adequate") || g.equals("widespread")) return 2f;
        if (g.equals("abundant") || g.equals("plentiful") || g.equals("good") || g.equals("extensive")) return 3f;
        if (g.equals("rich") || g.equals("vast")) return 4f;
        if (g.equals("ultrarich") || g.equals("bountiful")) return 5f;
        return 0f;
    }

    /** Sum of deposit grades; food and rarer goods count 1.5x (they pay best and make worlds habitable). */
    public static float resourceScore(List<String> ids) {
        float s = 0f;
        if (ids == null) return 0f;
        for (String id : ids) {
            if (!isResource(id)) continue;
            String i = id.toLowerCase(Locale.ROOT);
            float w = i.startsWith("farmland_") || i.startsWith("rare_ore_") || i.startsWith("organics_") ? 1.5f : 1f;
            s += grade(i) * w;
        }
        return s;
    }

    public static String resources(List<String> ids) {
        StringBuilder b = new StringBuilder();
        if (ids != null) for (String id : ids) if (isResource(id)) b.append(b.length() == 0 ? "" : " ").append(id);
        return b.length() == 0 ? "no deposits" : b.toString();
    }

    /** Ranking: resources per 100% hazard (hazard raises upkeep and slows growth). */
    public static float rank(float score, float hazardPct) {
        return score / Math.max(50f, hazardPct) * 100f;
    }

    /**
     * 1.0.9: whether a colony here is safe from the claiming faction (the game's colony screen: a system claimed by a
     * territorial faction is off limits - "a colony established here would likely be eradicated" - unless the player
     * holds that faction's commission). claimId null = unclaimed.
     */
    public static boolean claimAllows(String claimId, String commissionId) {
        if (claimId == null || claimId.isEmpty() || claimId.equals("player")) return true;
        return claimId.equals(commissionId);
    }
}
