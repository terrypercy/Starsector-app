package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds what can be explored in a star system: salvageable entities (derelict ships, stations, caches,
 * cryosleepers), debris fields, planets not fully surveyed and planets with ruins. Ruins are only reported
 * (they need a colony or special handling). Defenders: the game sets $hasDefenders / $defenderFleet in
 * the entity's memory, usually only once the entity has been interacted with, so a never-visited site
 * shows "maybe" when themed (derelict/ruins systems) and "no" otherwise.
 */
public final class Explorer {

    private Explorer() {}

    /** Sites already visited this session (entity ids). */
    public static final Set<String> visited = new HashSet<String>();

    static boolean isRuins(MarketAPI m) {
        return m != null && (m.hasCondition(Conditions.RUINS_SCATTERED) || m.hasCondition(Conditions.RUINS_WIDESPREAD)
                || m.hasCondition(Conditions.RUINS_EXTENSIVE) || m.hasCondition(Conditions.RUINS_VAST));
    }

    static String ruinsLevel(MarketAPI m) {
        if (m.hasCondition(Conditions.RUINS_VAST)) return "vast";
        if (m.hasCondition(Conditions.RUINS_EXTENSIVE)) return "extensive";
        if (m.hasCondition(Conditions.RUINS_WIDESPREAD)) return "widespread";
        return "scattered";
    }

    public static List<ExploreMath.Site> scan(LocationAPI loc) {
        List<ExploreMath.Site> out = new ArrayList<ExploreMath.Site>();
        if (loc == null || loc.isHyperspace()) return out;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        boolean themed = false;
        try {
            if (loc instanceof com.fs.starfarer.api.campaign.StarSystemAPI) {
                for (String t : ((com.fs.starfarer.api.campaign.StarSystemAPI) loc).getTags()) if (t.startsWith("theme_derelict") || t.startsWith("theme_ruins") || t.startsWith("theme_remnant")) themed = true;
            }
        } catch (Throwable ignored) {}
        for (SectorEntityToken e : loc.getAllEntities()) {
            try {
                if (e == pf || e instanceof CampaignFleetAPI) continue;
                boolean salv = e.hasTag(Tags.SALVAGEABLE), debris = e.hasTag(Tags.DEBRIS_FIELD);
                ExploreMath.Site s = null;
                if (salv || debris) {
                    String ce = e.getCustomEntityType() == null ? "" : e.getCustomEntityType().toLowerCase();
                    String kind = debris ? "debris" : e.hasTag(Tags.CRYOSLEEPER) || ce.contains("cryo") ? "cryosleeper"
                            : ce.contains("wreck") || ce.contains("derelict") ? "derelict" : e.hasTag(Tags.STATION) || ce.contains("station") ? "station" : "salvage";
                    s = new ExploreMath.Site(e.getId(), e.getName(), kind, e.getLocation().x, e.getLocation().y);
                    s.salvage = true;
                    s.cost = debris ? "supplies/crew by field size; heavy machinery speeds it" : "some supplies, crew and heavy machinery (shown in the salvage dialog)";
                } else if (e instanceof PlanetAPI && !((PlanetAPI) e).isStar()) {
                    MarketAPI m = e.getMarket();
                    if (m == null || !m.isPlanetConditionMarketOnly()) continue;
                    boolean unsurveyed = m.getSurveyLevel() != MarketAPI.SurveyLevel.FULL;
                    boolean ruins = isRuins(m);
                    if (!unsurveyed && !ruins) continue;
                    s = new ExploreMath.Site(e.getId(), e.getName(), ruins ? "ruins" : "planet_unsurveyed", e.getLocation().x, e.getLocation().y);
                    s.survey = unsurveyed;
                    s.ruins = ruins;
                    s.cost = unsurveyed ? "survey: supplies, heavy machinery, crew by planet class " + safeClass((PlanetAPI) e) : "";
                    if (ruins) s.cost += (s.cost.length() > 0 ? "; " : "") + "ruins (" + ruinsLevel(m) + "): need a colony to explore";
                }
                if (s == null) continue;
                s.dist = Misc.getDistance(pf, e);
                MemoryAPI mem = e.getMemoryWithoutUpdate();
                boolean has = mem != null && mem.getBoolean("$hasDefenders") && !mem.getBoolean("$defenderFleetDefeated");
                Object df = mem == null ? null : mem.get("$defenderFleet");
                if (df instanceof CampaignFleetAPI) s.defenderDP = BountyRunner.dpOf((CampaignFleetAPI) df);
                if (has || (df != null && !mem.getBoolean("$defenderFleetDefeated"))) s.defended = "yes";
                else if (s.salvage && (themed || (mem != null && mem.contains("$salvageDOv"))) && !visited.contains(s.id)) s.defended = "maybe";
                out.add(s);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    static String safeClass(PlanetAPI p) { try { return Misc.getPlanetSurveyClass(p); } catch (Throwable t) { return "?"; } }

    public static SectorEntityToken entity(String id) { return Global.getSector().getEntityById(id); }

    /** The detour worth taking now, honouring the explore_* rules and the supply floor; null when none. */
    public static ExploreMath.Site next(SectorEntityToken dest, Set<String> extraVisited) {
        if (!Rules.b("explore", true)) return null;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        float days = GovMath.supplyDays(pf.getCargo().getSupplies(), Governor.maintPerDay(pf));
        if (days < Rules.f("explore_min_supply_days", 25f)) return null;
        if (Governor.latch.active().size() > 0) return null;
        Float tx = null, ty = null;
        if (dest != null && dest.getContainingLocation() == pf.getContainingLocation()) { tx = dest.getLocation().x; ty = dest.getLocation().y; }
        Set<String> v = new HashSet<String>(visited);
        if (extraVisited != null) v.addAll(extraVisited);
        return ExploreMath.pick(scan(pf.getContainingLocation()), pf.getLocation().x, pf.getLocation().y, tx, ty,
                Rules.f("explore_detour_su", 4000f), v, Rules.b("explore_skip_defended", true), BountyRunner.ourDP(),
                Rules.f("hunt_margin", 1.5f), Rules.b("explore_survey", true));
    }

    public static String list() {
        List<ExploreMath.Site> l = scan(Global.getSector().getPlayerFleet().getContainingLocation());
        if (l.isEmpty()) return "nothing to explore here (hyperspace, or nothing salvageable/unsurveyed)";
        StringBuilder b = new StringBuilder("explorable here:");
        for (ExploreMath.Site s : l) {
            b.append("\n  ").append(s.name).append(" [").append(s.kind).append("] ").append(Math.round(s.dist)).append(" su")
             .append(" | defended ").append(s.defended).append(s.defenderDP > 0 ? " (" + Math.round(s.defenderDP) + " DP)" : "")
             .append(visited.contains(s.id) ? " | visited" : "").append(s.cost.length() > 0 ? " | " + s.cost : "");
        }
        return b.toString();
    }
}
