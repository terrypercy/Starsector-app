package claudecaptain;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * What the Captain panel shows, as rows (pure: no game classes, unit-tested). CaptainHud turns the rows
 * into the game's UI widgets. One colour language everywhere: GOOD green, WARN amber, BAD red,
 * INFO cyan for Claude/goal lines, DIM grey for detail.
 */
public final class Hud {

    private Hud() {}

    public static final Color GOOD = new Color(110, 210, 120);
    public static final Color WARN = new Color(240, 190, 70);
    public static final Color BAD = new Color(235, 95, 85);
    public static final Color INFO = new Color(110, 200, 255);
    public static final Color DIM = new Color(150, 160, 172);
    public static final Color TEXT = new Color(225, 230, 235);
    public static final Color HEAD_BG = new Color(20, 40, 55);
    public static final Color SECTION_BG = new Color(14, 26, 36);

    public static final int L_DIM = 0, L_GOOD = 1, L_WARN = 2, L_BAD = 3, L_INFO = 4, L_TEXT = 5;

    public static Color color(int level) {
        switch (level) {
            case L_GOOD: return GOOD;
            case L_WARN: return WARN;
            case L_BAD: return BAD;
            case L_INFO: return INFO;
            case L_TEXT: return TEXT;
            default: return DIM;
        }
    }

    public static final String[] SECTIONS = {"fleet", "bounties", "trades", "explore"};

    /** Everything the panel needs, filled by CaptainHud from the game. */
    public static final class Data {
        public float credits, supplyDays, fuelLy, lowestCR = 1f, minSupplyDays = 15f, minCR = 0.4f, nearestFriendlyLy = -1f;
        public String goal = "", chip = "PLAYER";
        public int chipLevel = 0;
        public String repFaction = "";
        public float repRel, repReq = 10f;
        public boolean commissioned;
        public String commissionFaction = "";
        public final List<String> suggestions = new ArrayList<String>();
        public final List<String> fleet = new ArrayList<String>();      // "name|cr|hull"
        public final List<String> bounties = new ArrayList<String>();   // "ok|text"
        public final List<String> trades = new ArrayList<String>();
        public final List<String> explore = new ArrayList<String>();
        public final List<String> alerts = new ArrayList<String>();
        public String lastEvent = "";
        public int open = 0;            // index into SECTIONS, -1 = all closed
        public boolean folded;
    }

    public static final class Row {
        public final String kind, text, id;
        public final int level;
        /** For "stats": the highlighted parts and their levels. For "bar": fill 0..1 in value. */
        public final List<String> parts = new ArrayList<String>();
        public final List<Integer> partLevels = new ArrayList<Integer>();
        public float value;
        Row(String kind, String text, int level, String id) { this.kind = kind; this.text = text; this.level = level; this.id = id; }
        public String toString() { return kind + ":" + level + ":" + text; }
    }

    public static int supplyLevel(float days, float min) { return days < min ? L_BAD : days < 2f * min ? L_WARN : L_GOOD; }
    public static int crLevel(float cr, float min) { return cr < min ? L_BAD : cr < min + 0.2f ? L_WARN : L_GOOD; }
    public static int fuelLevel(float ly, float nearest) {
        if (nearest >= 0f && ly < nearest * 1.25f + 2f) return L_BAD;
        return ly < 10f ? L_WARN : L_GOOD;
    }

    public static String money(float c) {
        if (Math.abs(c) >= 1e6f) return String.format(java.util.Locale.ROOT, "%.1fM", c / 1e6f);
        if (Math.abs(c) >= 1e4f) return Math.round(c / 1000f) + "k";
        return String.valueOf(Math.round(c));
    }

    public static String cut(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, Math.max(1, n - 1)) + "…"; }

    public static List<Row> rows(Data d) {
        List<Row> r = new ArrayList<Row>();
        r.add(new Row("title", (d.folded ? "+ " : "- ") + "CAPTAIN", L_TEXT, "fold"));
        // header: credits, supply days, fuel range, lowest CR
        Row st = new Row("stats", "%s   %s   %s   %s", L_DIM, "stats");
        st.parts.add(money(d.credits) + " cr"); st.partLevels.add(L_TEXT);
        st.parts.add(Math.round(Math.min(999f, d.supplyDays)) + "d sup"); st.partLevels.add(supplyLevel(d.supplyDays, d.minSupplyDays));
        st.parts.add(Math.round(d.fuelLy) + " ly"); st.partLevels.add(fuelLevel(d.fuelLy, d.nearestFriendlyLy));
        st.parts.add("CR " + Math.round(d.lowestCR * 100f) + "%"); st.partLevels.add(crLevel(d.lowestCR, d.minCR));
        r.add(st);
        r.add(new Row("chip", d.chip, d.chipLevel == 0 ? L_DIM : d.chipLevel == 1 ? L_GOOD : d.chipLevel == 2 ? L_WARN : L_BAD, "chip"));
        if (d.folded) return r;
        r.add(new Row("line", d.goal.length() == 0 ? "Idle - no goal" : "▶ " + d.goal, d.goal.length() == 0 ? L_DIM : L_INFO, "goal"));
        for (String a : d.alerts) r.add(new Row("line", "! " + a, L_BAD, "alert"));
        if (d.commissioned) r.add(new Row("line", "Commissioned: " + d.commissionFaction, L_GOOD, "rep"));
        else if (d.repFaction.length() > 0) {
            Row b = new Row("bar", d.repFaction + "  " + Math.round(d.repRel) + "/" + Math.round(d.repReq) + " to commission", d.repRel >= d.repReq ? L_GOOD : d.repRel < 0 ? L_BAD : L_WARN, "rep");
            b.value = RepMath.progress(d.repRel, d.repReq);
            r.add(b);
        }
        if (!d.suggestions.isEmpty()) {
            r.add(new Row("label", "NEXT", L_DIM, "next"));
            for (int i = 0; i < d.suggestions.size() && i < 3; i++) r.add(new Row("button", (i + 1) + ". " + d.suggestions.get(i), i == 0 ? L_INFO : L_TEXT, "opt" + i));
        }
        for (int i = 0; i < SECTIONS.length; i++) {
            List<String> items = i == 0 ? d.fleet : i == 1 ? d.bounties : i == 2 ? d.trades : d.explore;
            boolean open = d.open == i;
            String count = items.isEmpty() ? "" : " (" + items.size() + ")";
            r.add(new Row("section", (open ? "▾ " : "▸ ") + SECTIONS[i].toUpperCase() + count, L_TEXT, "sec" + i));
            if (!open) continue;
            if (items.isEmpty()) { r.add(new Row("line", "  nothing", L_DIM, "item")); continue; }
            for (int k = 0; k < items.size() && k < 6; k++) r.add(item(i, items.get(k), d));
        }
        if (d.lastEvent.length() > 0) r.add(new Row("line", d.lastEvent, L_DIM, "event"));
        return r;
    }

    /** Section items: fleet "name|cr|hull", bounties "ok|text", trades/explore "level|text". */
    static Row item(int section, String raw, Data d) {
        String[] p = raw.split("\\|", -1);
        if (section == 0 && p.length >= 3) {
            float cr = parse(p[1]), hull = parse(p[2]);
            int lv = Math.max(crLevel(cr, d.minCR), hull < 0.5f ? L_BAD : hull < 0.9f ? L_WARN : L_GOOD);
            return new Row("line", "  " + p[0] + "  CR " + Math.round(cr * 100f) + "%  hull " + Math.round(hull * 100f) + "%", lv, "item");
        }
        if (p.length >= 2) {
            int lv = p[0].equals("ok") ? L_GOOD : p[0].equals("skip") ? L_DIM : p[0].equals("warn") ? L_WARN : p[0].equals("bad") ? L_BAD : L_TEXT;
            return new Row("line", "  " + p[1], lv, "item");
        }
        return new Row("line", "  " + raw, L_TEXT, "item");
    }

    static float parse(String s) { try { return Float.parseFloat(s); } catch (Throwable t) { return 0f; } }

    /** Next open section for the cycle key: fleet, bounties, trades, explore, none, fleet... */
    public static int cycle(int open) { return open + 1 >= SECTIONS.length ? -1 : open + 1; }
}
