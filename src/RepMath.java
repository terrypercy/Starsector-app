package claudecaptain;

import java.util.ArrayList;
import java.util.List;

/**
 * Reputation and commission arithmetic (pure, unit-tested).
 * A faction commission needs Favorable standing (relation >= 10 on the -100..100 scale; the game's
 * Commission.COMMISSION_REQ). Stipend = factionCommissionStipendBase + factionCommissionStipendPerLevel x level
 * (settings.json: 20000 + 5000/level, the same for every faction).
 */
public final class RepMath {

    private RepMath() {}

    public static float toFavorable(float rel, float req) { return Math.max(0f, req - rel); }

    public static float stipend(int level, float base, float perLevel) { return base + perLevel * level; }

    /** 0..1 progress for the panel bar: from Neutral-bottom (-10) to the requirement. */
    public static float progress(float rel, float req) {
        float lo = -10f;
        if (rel >= req) return 1f;
        if (rel <= lo) return 0f;
        return (rel - lo) / (req - lo);
    }

    /** "[=====-----]" */
    public static String bar(float frac, int width) {
        int n = Math.round(Math.max(0f, Math.min(1f, frac)) * width);
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < width; i++) b.append(i < n ? '=' : '-');
        return b.append(']').toString();
    }

    public static final class Faction {
        public String id, name;
        public float rel;
        public boolean hostile, hasBounties, hasMarketsNear;
        public int deficits;
        public Faction(String id, String name, float rel) { this.id = id; this.name = name; this.rel = rel; }
    }

    /** Ways to gain standing with f, best first (shown in state.txt and the panel). */
    public static List<String> suggestions(Faction f, float req) {
        List<String> s = new ArrayList<String>();
        if (f.rel >= req) { s.add("standing is enough: 'commission " + f.id + "'"); return s; }
        float gap = toFavorable(f.rel, req);
        if (f.hostile) s.add("hostile: stop fighting " + f.name + "; a tribute or pirate-hunting for their enemies helps");
        if (f.hasBounties) s.add("take " + f.name + "'s posted bounties (each kill raises standing)");
        s.add("hunt pirates/pather fleets in " + f.name + " systems (faction bounties pay reputation)");
        if (f.deficits > 0) s.add("sell goods " + f.name + " markets lack (" + f.deficits + " shortages known); trade volume raises standing");
        s.add("do missions for " + f.name + " contacts at their bars and comm directories");
        s.add(Math.round(gap) + " points to Favorable (" + Math.round(f.rel) + " now, need " + Math.round(req) + ")");
        return s;
    }

    /** The faction to show on the panel: the one closest to Favorable that is not there yet (non-hostile first). */
    public static Faction pickTarget(List<Faction> all, float req, String prefer) {
        Faction best = null;
        for (Faction f : all) if (prefer != null && (f.id.equalsIgnoreCase(prefer) || f.name.equalsIgnoreCase(prefer))) return f;
        for (Faction f : all) {
            if (f.rel >= req || f.hostile) continue;
            if (best == null || f.rel > best.rel) best = f;
        }
        if (best == null) for (Faction f : all) if (best == null || f.rel > best.rel) best = f;
        return best;
    }
}
