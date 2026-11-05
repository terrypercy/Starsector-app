package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerUtil;
import com.fs.starfarer.api.plugins.SurveyPlugin;
import com.fs.starfarer.api.util.Misc;

import java.util.HashMap;
import java.util.Map;

/**
 * 1.1.31: performs a planet survey the way the game's survey screen does (PlanetSurveyPanel, 0.98a-RC8):
 * the "surveyPlugin" works out what is required aboard and what is consumed; the consumed goods are removed
 * from the fleet's cargo, then Misc.setFullySurveyed, Misc.addSurveyDataFor, the survey XP and the
 * surveyed-planet listeners. The screen is a custom panel the mod can't click, so every survey used to stop
 * the trip and wait for Claude to click "Perform Survey" by screen.
 */
public final class SurveyRun {

    private SurveyRun() {}

    /** Null when the survey can be paid for, else what is missing. Pure: testable without the game. */
    public static String missing(Map<String, Integer> required, Map<String, Integer> consumed, Map<String, Float> aboard) {
        Map<String, Float> need = new HashMap<String, Float>();
        if (required != null) for (Map.Entry<String, Integer> e : required.entrySet()) need.put(e.getKey(), (float) e.getValue());
        if (consumed != null) for (Map.Entry<String, Integer> e : consumed.entrySet()) {
            Float n = need.get(e.getKey());
            need.put(e.getKey(), Math.max(n == null ? 0f : n, (float) e.getValue()));
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Float> e : need.entrySet()) {
            Float have = aboard == null ? null : aboard.get(e.getKey());
            float h = have == null ? 0f : have;
            if (h < e.getValue()) b.append(b.length() > 0 ? ", " : "").append(e.getKey()).append(" ").append(Math.round(h)).append("/").append(Math.round(e.getValue()));
        }
        return b.length() == 0 ? null : "not enough aboard: " + b;
    }

    /** 1.1.38: what a survey of this planet still needs aboard (null when the fleet can do it now). */
    public static String shortfall(PlanetAPI planet) {
        try {
            CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
            SurveyPlugin sp = (SurveyPlugin) Global.getSettings().getNewPluginInstance("surveyPlugin");
            sp.init(pf, planet);
            Map<String, Float> aboard = new HashMap<String, Float>();
            for (String id : keys(sp.getRequired(), sp.getConsumed())) aboard.put(id, pf.getCargo().getCommodityQuantity(id));
            return missing(sp.getRequired(), sp.getConsumed(), aboard);
        } catch (Throwable t) { return null; }
    }

    /** Surveys the planet; returns a one-line report (or why not). */
    public static String perform(PlanetAPI planet) {
        MarketAPI m = planet == null ? null : planet.getMarket();
        if (m == null) return "no planet to survey";
        if (m.getSurveyLevel() == MarketAPI.SurveyLevel.FULL) return planet.getName() + " is already surveyed";
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        SurveyPlugin sp = (SurveyPlugin) Global.getSettings().getNewPluginInstance("surveyPlugin");
        sp.init(pf, planet);
        CargoAPI cargo = pf.getCargo();
        Map<String, Float> aboard = new HashMap<String, Float>();
        for (String id : keys(sp.getRequired(), sp.getConsumed())) aboard.put(id, cargo.getCommodityQuantity(id));
        String miss = missing(sp.getRequired(), sp.getConsumed(), aboard);
        if (miss != null) return planet.getName() + ": " + miss;
        StringBuilder used = new StringBuilder();
        for (Map.Entry<String, Integer> e : sp.getConsumed().entrySet()) {
            cargo.removeCommodity(e.getKey(), e.getValue().floatValue());
            used.append(used.length() > 0 ? ", " : "").append(e.getValue()).append(" ").append(e.getKey());
        }
        long xp = sp.getXP();
        Misc.setFullySurveyed(m, null, false);
        Misc.addSurveyDataFor(planet, null);
        Global.getSector().getCharacterData().getPerson().getStats().addXP(xp, null, false);
        ListenerUtil.reportPlayerSurveyedPlanet(planet);
        return "surveyed " + planet.getName() + " (" + planet.getTypeNameWithWorld() + "): used " + used + ", +" + xp + " XP";
    }

    private static java.util.Set<String> keys(Map<String, Integer> a, Map<String, Integer> b) {
        java.util.Set<String> s = new java.util.HashSet<String>();
        if (a != null) s.addAll(a.keySet());
        if (b != null) s.addAll(b.keySet());
        return s;
    }
}
