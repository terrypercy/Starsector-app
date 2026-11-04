package claudecaptain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 1.1.26 (May 23 c206 live): pure ranking for 'survey targets' - star systems that still have planets
 * to survey, nearest first, with the dangerous ones (live Remnant themes) pushed to the bottom.
 * The player asked for an exploration loop: survey worlds, salvage, keep a colony shortlist.
 */
public final class SurveyMath {

    private SurveyMath() {}

    /** One star system's exploration summary. */
    public static final class Sys {
        public String name;
        public float ly;
        public int unsurveyed, salvage, markets;
        public boolean entered, danger;
        public String note = "";

        public Sys(String name, float ly, int unsurveyed, int salvage) {
            this.name = name; this.ly = ly; this.unsurveyed = unsurveyed; this.salvage = salvage;
        }
    }

    /** True for a system tag that means live Remnant defenders (a destroyed Remnant system is safe). */
    public static boolean dangerTag(String tag) {
        if (tag == null) return false;
        String t = tag.toLowerCase(Locale.ROOT);
        return t.startsWith("theme_remnant") && !t.contains("destroyed");
    }

    /** Systems worth a survey trip: some unsurveyed planet, within maxLy; safe before dangerous, then nearest first. */
    public static List<Sys> rank(List<Sys> in, float maxLy) {
        List<Sys> out = new ArrayList<Sys>();
        if (in == null) return out;
        for (Sys s : in) if (s != null && s.unsurveyed > 0 && s.ly <= maxLy) out.add(s);
        Collections.sort(out, new Comparator<Sys>() {
            public int compare(Sys a, Sys b) {
                if (a.danger != b.danger) return a.danger ? 1 : -1;
                return Float.compare(a.ly, b.ly);
            }
        });
        return out;
    }

    /** One report line. */
    public static String line(int i, Sys s) {
        StringBuilder b = new StringBuilder();
        b.append(i).append(". ").append(s.name).append(" | ").append(String.format(Locale.ROOT, "%.1f", s.ly)).append(" ly")
         .append(" | ").append(s.unsurveyed).append(" to survey");
        if (s.salvage > 0) b.append(", ").append(s.salvage).append(" salvage");
        if (s.markets > 0) b.append(" | ").append(s.markets).append(s.markets == 1 ? " market" : " markets");
        if (!s.entered) b.append(" | never visited");
        if (s.danger) b.append(" | DANGER: Remnant");
        if (s.note != null && s.note.length() > 0) b.append(" | ").append(s.note);
        return b.toString();
    }
}
