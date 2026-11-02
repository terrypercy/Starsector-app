package claudecaptain;

import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainPlugin;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;

/**
 * 1.0.9: terrain that drains combat readiness (Nov 1: after the Temitope Alcor fight the hunt "rested" while
 * detouring to wrecks inside a black hole's event horizon; every ship fell to 0% CR and the CR recovery ate all
 * the supplies). Explore detours skip sites inside it, and the governor stops the fleet from loitering in it.
 */
public final class Hazard {

    private Hazard() {}

    /** Terrain types (ids from com.fs.starfarer.api.impl.campaign.ids.Terrain) that drain CR while inside. */
    public static boolean drainsCR(String terrainType) {
        if (terrainType == null) return false;
        String t = terrainType.toLowerCase();
        return t.equals("event_horizon") || t.equals("corona") || t.equals("corona_aka_mainyu")
                || t.equals("corona_jet") || t.equals("pulsar_beam");
    }

    /** Name of the CR-draining terrain the entity is inside, or null. */
    public static String at(SectorEntityToken e) {
        if (e == null) return null;
        try {
            LocationAPI loc = e.getContainingLocation();
            if (loc == null || loc.isHyperspace()) return null;
            for (CampaignTerrainAPI t : loc.getTerrainCopy()) {
                if (!drainsCR(t.getType())) continue;
                CampaignTerrainPlugin p = t.getPlugin();
                if (p != null && p.containsEntity(e)) {
                    String n = p.getTerrainName();
                    return n == null || n.isEmpty() ? t.getType() : n;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Days the fleet may stay in CR-draining terrain before the governor stops it (passing through is fine). */
    public static final float LOITER_DAYS = 1f;

    public static boolean loitering(float daysInside) { return daysInside >= LOITER_DAYS; }
}
