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
                    // 1.1.28: only ruins the player knows about (a full survey shows them) and has not explored yet;
                    // before 1.1.28 hidden ruins on unsurveyed worlds were reported and explored ones kept showing
                    boolean ruins = !unsurveyed && knownUnexploredRuins(m);
                    if (!unsurveyed && !ruins) continue;
                    // 1.0.8: the survey screen is a custom panel the mod can't drive yet (it left a dialog stuck with no
                    // options twice), so planets that still need surveying are skipped unless explore_survey = true
                    if (unsurveyed && !Rules.b("explore_survey", false)) continue;
                    String type = safeType((PlanetAPI) e);
                    // 1.1.26: the planet type is visible before a survey; skip types the player won't settle
                    if (unsurveyed && !ruins && ExploreMath.surveySkipped(type, Rules.s("explore_survey_skip_types", ""))) continue;
                    s = new ExploreMath.Site(e.getId(), e.getName(), ruins ? "ruins" : "planet_unsurveyed", e.getLocation().x, e.getLocation().y);
                    s.survey = unsurveyed;
                    s.ruins = ruins;
                    s.salvage = ruins; // explored like a salvage site: "Explore the ruins" -> "Begin salvage operations"
                    s.cost = (type.length() > 0 ? type + "; " : "") + (unsurveyed ? "survey: supplies, heavy machinery, crew by planet class " + safeClass((PlanetAPI) e) : "");
                    if (ruins) s.cost += "ruins (" + ruinsLevel(m) + "): explore for salvage (crew, heavy machinery)";
                }
                if (s == null) continue;
                // 1.0.9: never detour into terrain that drains CR (Nov 1: wrecks inside a black hole's event horizon)
                if (Hazard.at(e) != null) continue;
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

    /** 1.1.28: fully surveyed (so the player has seen them) ruins that are still unexplored. */
    static boolean knownUnexploredRuins(MarketAPI m) {
        try { return m.getSurveyLevel() == MarketAPI.SurveyLevel.FULL && isRuins(m) && Misc.hasUnexploredRuins(m); } catch (Throwable t) { return false; }
    }

    static String safeType(PlanetAPI p) { try { String t = p.getTypeNameWithWorld(); return t == null ? "" : t; } catch (Throwable t) { return ""; } }

    static String safeClass(PlanetAPI p) { try { return Misc.getPlanetSurveyClass(p); } catch (Throwable t) { return "?"; } }

    public static SectorEntityToken entity(String id) { return Global.getSector().getEntityById(id); }

    /** The detour worth taking now, honouring the explore_* rules and the supply floor; null when none. */
    public static ExploreMath.Site next(SectorEntityToken dest, Set<String> extraVisited) {
        if (!Rules.b("explore", true)) return null;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        // 1.0.9: count repairs and CR recovery too - right after a fight they can burn 8x maintenance (Nov 1)
        float days = GovMath.supplyDays(pf.getCargo().getSupplies(), Math.max(Governor.maintPerDay(pf), Governor.burnPerDay(pf)));
        if (days < Rules.f("explore_min_supply_days", 25f)) return null;
        if (Governor.latch.active().size() > 0) return null;
        Float tx = null, ty = null;
        if (dest != null && dest.getContainingLocation() == pf.getContainingLocation()) { tx = dest.getLocation().x; ty = dest.getLocation().y; }
        Set<String> v = new HashSet<String>(visited);
        if (extraVisited != null) v.addAll(extraVisited);
        List<ExploreMath.Site> sites = scan(pf.getContainingLocation());
        if (Rules.b("explore_only_ruins", false)) sites = ExploreMath.ruinsFocus(sites);
        return ExploreMath.pick(sites, pf.getLocation().x, pf.getLocation().y, tx, ty,
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
