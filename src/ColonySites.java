package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 1.0.9: 'colony sites' - every fully surveyed, uninhabited planet, ranked by resources and hazard, from the live game
 * (it replaces reading the save file by hand). Hazard is the game's own value; the resource score is ColonyMath.
 */
public final class ColonySites {

    private ColonySites() {}

    public static String report(int max) {
        List<Object[]> rows = new ArrayList<Object[]>();
        for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
            for (PlanetAPI p : sys.getPlanets()) {
                if (p.isStar()) continue;
                MarketAPI m = p.getMarket();
                if (m == null || !m.isPlanetConditionMarketOnly()) continue;          // inhabited markets are not sites
                if (m.getSurveyLevel() != MarketAPI.SurveyLevel.FULL) continue;
                List<String> ids = new ArrayList<String>();
                for (MarketConditionAPI c : m.getConditions()) ids.add(c.getId());
                float score = ColonyMath.resourceScore(ids);
                float hazard = m.getHazardValue() * 100f;
                String res = ColonyMath.resources(ids);
                boolean core = false;
                for (MarketAPI x : Global.getSector().getEconomy().getMarkets(sys)) if (!x.isPlanetConditionMarketOnly() && !x.isHidden()) core = true;
                float ly = Misc.getDistanceToPlayerLY(p);
                rows.add(new Object[]{ColonyMath.rank(score, hazard), p.getName(), sys.getBaseName(), hazard, score, res, ly, core, p.getTypeNameWithWorld()});
            }
        }
        Collections.sort(rows, new Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) { return Float.compare((Float) b[0], (Float) a[0]); }
        });
        StringBuilder b = new StringBuilder("colony sites (fully surveyed, uninhabited; best first; habitable worlds need 'farmland'/'organics')\n");
        int i = 0;
        for (Object[] r : rows) {
            if (i++ >= max) break;
            b.append(i).append(". ").append(r[1]).append(" | ").append(r[2]).append(" | ").append(r[8])
             .append(" | hazard ").append(Math.round((Float) r[3])).append("% | resources ").append(String.format(java.util.Locale.ROOT, "%.1f", (Float) r[4]))
             .append(" (").append(r[5]).append(") | ").append(String.format(java.util.Locale.ROOT, "%.1f", (Float) r[6])).append(" ly")
             .append((Boolean) r[7] ? " | system has markets" : "").append("\n");
        }
        if (rows.isEmpty()) b.append("(no fully surveyed uninhabited planets yet)\n");
        else b.append(rows.size()).append(" sites known\n");
        return b.toString().trim();
    }
}
