package claudecaptain;

import java.util.List;
import java.util.Set;

/** Choosing exploration detours (pure, unit-tested). */
public final class ExploreMath {

    private ExploreMath() {}

    public static final class Site {
        public String id, name, kind;          // derelict, debris, salvage, station, cryosleeper, planet_unsurveyed, ruins
        public float x, y, dist;
        /** "yes", "no", "maybe" */
        public String defended = "no";
        public float defenderDP;
        public String cost = "";
        public boolean salvage, survey, ruins;
        /** 1.1.38: why a survey here can't be done with what is aboard (null when it can) - such sites are never picked. */
        public String blocked;
        public Site(String id, String name, String kind, float x, float y) { this.id = id; this.name = name; this.kind = kind; this.x = x; this.y = y; }
    }

    static float d(float ax, float ay, float bx, float by) { float dx = ax - bx, dy = ay - by; return (float) Math.sqrt(dx * dx + dy * dy); }

    /** Extra distance flown when visiting (sx,sy) on the way from (px,py) to (tx,ty). No destination: there and back. */
    public static float detour(float px, float py, float sx, float sy, Float tx, Float ty) {
        if (tx == null || ty == null) return 2f * d(px, py, sx, sy);
        return d(px, py, sx, sy) + d(sx, sy, tx, ty) - d(px, py, tx, ty);
    }

    /**
     * The site worth a detour, or null: salvage before surveys (ruins are only reported), within maxDetour,
     * not visited yet, not defended unless ourDP covers defenders x margin. Closest detour wins.
     */
    public static Site pick(List<Site> sites, float px, float py, Float tx, Float ty, float maxDetour, Set<String> visited,
                            boolean skipDefended, float ourDP, float margin, boolean survey) {
        // salvage first; a survey stop only when no salvage fits
        for (int pass = 0; pass < 2; pass++) {
            Site b = pickPass(sites, px, py, tx, ty, maxDetour, visited, skipDefended, ourDP, margin, pass == 0, pass == 1 && survey);
            if (b != null) return b;
        }
        return null;
    }

    static Site pickPass(List<Site> sites, float px, float py, Float tx, Float ty, float maxDetour, Set<String> visited,
                         boolean skipDefended, float ourDP, float margin, boolean salvage, boolean survey) {
        Site best = null;
        float bestCost = Float.MAX_VALUE;
        for (Site s : sites) {
            if (visited.contains(s.id)) continue;
            if (s.blocked != null) continue; // 1.1.38: can't do it with what is aboard
            if (salvage ? !s.salvage : !(survey && s.survey && !s.salvage)) continue;
            if (!"no".equals(s.defended)) {
                if (skipDefended && !(s.defenderDP > 0f && s.defenderDP * margin <= ourDP)) continue;
            }
            float det = detour(px, py, s.x, s.y, tx, ty);
            if (det > maxDetour) continue;
            if (det < bestCost) { bestCost = det; best = s; }
        }
        return best;
    }

    /**
     * 1.1.26: true when a planet of this visible type is on the comma-separated skip list
     * (explore_survey_skip_types, e.g. "gas giant, volcanic"): a survey there costs 90-150 supplies for a world
     * no one would settle. Matching is by substring, ignoring case.
     */
    public static boolean surveySkipped(String typeName, String skipList) {
        if (typeName == null || skipList == null) return false;
        String t = typeName.toLowerCase(java.util.Locale.ROOT);
        for (String k : skipList.split(",")) {
            String w = k.trim().toLowerCase(java.util.Locale.ROOT);
            if (w.length() > 0 && t.contains(w)) return true;
        }
        return false;
    }

    /**
     * 1.1.28 (player: "focus on exploring ruins"): keep known ruins, and unsurveyed planets (a survey is how
     * ruins are found); drop derelicts, debris and other salvage.
     */
    public static java.util.List<Site> ruinsFocus(java.util.List<Site> in) {
        java.util.List<Site> out = new java.util.ArrayList<Site>();
        if (in == null) return out;
        for (Site s : in) if (s != null && (s.ruins || (s.survey && !s.salvage))) out.add(s);
        return out;
    }
}
