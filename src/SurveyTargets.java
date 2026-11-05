package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1.1.26: 'survey targets [max ly] [n]' - star systems with planets still to survey, from the live game.
 * Ranking and formatting are in {@link SurveyMath}.
 */
public final class SurveyTargets {

    private SurveyTargets() {}

    /** Parses "[max ly] [count]" (defaults 25 ly, 12 systems) and reports. */
    public static String command(String args) {
        float maxLy = 25f;
        int max = 12;
        int nums = 0;
        for (String t : (args == null ? "" : args).trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!t.matches("\\d+(\\.\\d+)?")) continue;
            if (nums++ == 0) maxLy = Math.max(1f, Math.min(200f, Float.parseFloat(t)));
            else max = Math.max(1, Math.min(60, (int) Float.parseFloat(t)));
        }
        List<SurveyMath.Sys> all = new ArrayList<SurveyMath.Sys>();
        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            try {
                if (sys.hasTag(Tags.THEME_HIDDEN)) continue;
                int uns = 0, salv = 0, markets = 0;
                for (PlanetAPI p : sys.getPlanets()) {
                    if (p.isStar()) continue;
                    MarketAPI m = p.getMarket();
                    if (m == null) continue;
                    if (!m.isPlanetConditionMarketOnly()) { markets++; continue; }
                    if (m.getSurveyLevel() != MarketAPI.SurveyLevel.FULL) uns++;
                }
                for (SectorEntityToken e : sys.getAllEntities()) {
                    if (e.hasTag(Tags.SALVAGEABLE) && !e.hasTag(Tags.DEBRIS_FIELD)) salv++;
                }
                if (uns == 0) continue;
                float ly = Misc.getDistanceToPlayerLY(sys.getHyperspaceAnchor());
                SurveyMath.Sys s = new SurveyMath.Sys(sys.getBaseName(), ly, uns, salv);
                s.markets = markets;
                s.entered = sys.isEnteredByPlayer();
                for (String tag : sys.getTags()) if (SurveyMath.dangerTag(tag)) s.danger = true;
                all.add(s);
            } catch (Throwable ignored) {}
        }
        List<SurveyMath.Sys> r = SurveyMath.rank(all, maxLy);
        StringBuilder b = new StringBuilder("survey targets within " + Math.round(maxLy) + " ly (systems with planets not fully surveyed; safe first, nearest first)\n");
        b.append("a survey costs supplies and heavy machinery by planet class (crew needed aboard, not used); 'explore' in the system runs them when explore_survey = true\n");
        int i = 0;
        for (SurveyMath.Sys s : r) {
            if (i++ >= max) break;
            b.append(SurveyMath.line(i, s)).append("\n");
        }
        if (r.isEmpty()) b.append("(none in range)\n");
        else b.append(r.size()).append(" systems in range\n");
        return b.toString().trim();
    }

    /**
     * 1.1.28: 'ruins [max ly] [n]' - fully surveyed, uninhabited planets with ruins still to explore, sector-wide,
     * nearest first. Only ruins the player has seen (a full survey) are listed.
     */
    public static String ruins(String args) {
        float maxLy = 40f;
        int max = 15, nums = 0;
        for (String t : (args == null ? "" : args).trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!t.matches("\\d+(\\.\\d+)?")) continue;
            if (nums++ == 0) maxLy = Math.max(1f, Math.min(200f, Float.parseFloat(t)));
            else max = Math.max(1, Math.min(60, (int) Float.parseFloat(t)));
        }
        List<SurveyMath.Sys> rows = new ArrayList<SurveyMath.Sys>();
        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            boolean danger = false;
            try { for (String tag : sys.getTags()) if (SurveyMath.dangerTag(tag)) danger = true; } catch (Throwable ignored) {}
            for (PlanetAPI p : sys.getPlanets()) {
                try {
                    if (p.isStar()) continue;
                    MarketAPI m = p.getMarket();
                    if (m == null || !m.isPlanetConditionMarketOnly() || !Explorer.knownUnexploredRuins(m)) continue;
                    SurveyMath.Sys r = new SurveyMath.Sys(p.getName() + " (" + sys.getBaseName() + ")", Misc.getDistanceToPlayerLY(p), 0, 0);
                    r.entered = true;
                    r.danger = danger;
                    String claim = "unclaimed";
                    try { com.fs.starfarer.api.campaign.FactionAPI f = Misc.getClaimingFaction(p); if (f != null) claim = "claimed by " + f.getDisplayName(); } catch (Throwable ignored) {}
                    r.note = Explorer.ruinsLevel(m) + " ruins | " + p.getTypeNameWithWorld() + " | " + claim;
                    if (r.ly <= maxLy) rows.add(r);
                } catch (Throwable ignored) {}
            }
        }
        java.util.Collections.sort(rows, new java.util.Comparator<SurveyMath.Sys>() {
            public int compare(SurveyMath.Sys a, SurveyMath.Sys b) {
                if (a.danger != b.danger) return a.danger ? 1 : -1;
                return Float.compare(a.ly, b.ly);
            }
        });
        StringBuilder b = new StringBuilder("known unexplored ruins within " + Math.round(maxLy) + " ly (fully surveyed, uninhabited; nearest first, Remnant systems last)\n");
        b.append("'dock <planet>' then 'Explore the ruins' -> salvage; unsurveyed worlds may hide more ('survey targets')\n");
        int i = 0;
        for (SurveyMath.Sys r : rows) {
            if (i++ >= max) break;
            b.append(i).append(". ").append(r.name).append(" | ").append(String.format(Locale.ROOT, "%.1f", r.ly)).append(" ly | ")
             .append(r.note).append(r.danger ? " | DANGER: Remnant" : "").append("\n");
        }
        if (rows.isEmpty()) b.append("(none known in range)\n");
        else b.append(rows.size()).append(" known\n");
        return b.toString().trim();
    }
}
