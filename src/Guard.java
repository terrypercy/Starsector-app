package claudecaptain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 1.0.9: the player's guard rails as plain logic (no game classes, unit-tested).
 *
 * keep list: "metals:50, heavy machinery:50" keeps at least that many; a bare name keeps all of it.
 * price ceilings: follow the market - the median of the prices the fleet has seen, times a factor.
 */
public final class Guard {

    private Guard() {}

    /** Units of this good the player wants kept: 0 = none kept, Integer.MAX_VALUE = never sell any. */
    public static int keepFloor(String rule, String id, String name) {
        if (rule == null) return 0;
        String r = rule.trim().toLowerCase(Locale.ROOT);
        if (r.isEmpty() || r.equals("none")) return 0;
        String i = id == null ? "" : id.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        for (String e : r.split(",")) {
            String x = e.trim();
            if (x.isEmpty()) continue;
            int floor = Integer.MAX_VALUE;
            int c = x.lastIndexOf(':');
            if (c < 0 && x.matches(".*\\s\\d+$")) c = x.lastIndexOf(' '); // 1.0.9: "metals 50" means metals:50
            if (c > 0) {
                try { floor = Math.max(0, Integer.parseInt(x.substring(c + 1).trim())); } catch (NumberFormatException ignored) { floor = Integer.MAX_VALUE; }
                x = x.substring(0, c).trim();
            }
            x = x.replace('_', ' ');
            if (x.equals(i) || x.equals(n)) return floor;
        }
        return 0;
    }

    /** How many may be sold out of 'have' when 'want' is asked for and 'floor' must stay aboard. */
    public static float sellable(float have, float want, int floor) {
        if (floor == Integer.MAX_VALUE) return 0f;
        float free = Math.max(0f, have - floor);
        return (float) Math.floor(Math.min(want, free));
    }

    /** Median of the known prices (ignores zero/negative); -1 when nothing is known. */
    public static float median(List<Float> prices) {
        List<Float> p = new ArrayList<Float>();
        if (prices != null) for (Float f : prices) if (f != null && f > 0f) p.add(f);
        if (p.isEmpty()) return -1f;
        Collections.sort(p);
        int k = p.size();
        return k % 2 == 1 ? p.get(k / 2) : (p.get(k / 2 - 1) + p.get(k / 2)) / 2f;
    }

    /**
     * The ceiling for one unit: median of the market prices seen x factor; 'fallback' when fewer than
     * 'minSamples' prices are known. A fixed override (> 0) wins.
     */
    public static float ceiling(List<Float> seen, float factor, float fallback, float override, int minSamples) {
        if (override > 0f) return override;
        int known = 0;
        if (seen != null) for (Float f : seen) if (f != null && f > 0f) known++;
        if (known < Math.max(1, minSamples)) return fallback;
        return median(seen) * factor;
    }
}
