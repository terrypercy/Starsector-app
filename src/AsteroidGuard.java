package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.impl.campaign.terrain.AsteroidBeltTerrainPlugin;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.1.18 (Nov 3): asteroid guard. AsteroidImpact (game source) does no damage and no knock-back to a fleet that
 * is moving slowly ("if (Misc.isSlowMoving(fleet) ...) return"), and AsteroidFieldTerrainPlugin extends
 * AsteroidBeltTerrainPlugin, so one check covers belts and fields. While the player fleet is inside one (or about
 * to enter it), ask it to go slow for this frame, exactly as holding the move-slowly key does - the same trick as
 * StormGuard uses for hyperspace storms. The fleet keeps its course and speeds up again on the far side.
 * Before this, crossing Thule/Magec/Isirah belts cost 10-37% hull per ship per trip, and the repairs ate supplies.
 * Rule: asteroid_guard (default on), asteroid_guard_lookahead_s.
 */
public final class AsteroidGuard {
    private AsteroidGuard() {}

    private static LocationAPI cachedFor;
    private static final List<CampaignTerrainAPI> belts = new ArrayList<CampaignTerrainAPI>();
    private static boolean slowing;
    private static long slowSince;

    public static void tick() {
        if (!Rules.b("asteroid_guard", true)) { slowing = false; return; }
        if (Global.getSector() == null || Global.getSector().isPaused()) return;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        if (pf == null || pf.isInHyperspace() || pf.isInHyperspaceTransition() || pf.getBattle() != null) { end(); return; }
        refresh(pf.getContainingLocation());
        if (belts.isEmpty()) { end(); return; }

        boolean danger = inside(pf.getLocation(), pf.getRadius());
        Vector2f v = pf.getVelocity();
        float speed = v == null ? 0f : v.length();
        if (!danger && speed > 5f) {
            float reach = speed * Rules.f("asteroid_guard_lookahead_s", 1.5f);
            Vector2f loc = pf.getLocation();
            for (float d = 50f; d <= reach && !danger; d += 50f) {
                danger = inside(new Vector2f(loc.x + v.x / speed * d, loc.y + v.y / speed * d), pf.getRadius());
            }
        }
        if (danger) {
            pf.goSlowOneFrame();
            if (!slowing) { slowing = true; slowSince = System.currentTimeMillis(); }
        } else {
            end();
        }
    }

    private static boolean inside(Vector2f p, float r) {
        for (CampaignTerrainAPI t : belts) {
            try { if (t.getPlugin().containsPoint(p, r)) return true; } catch (Throwable ignored) {}
        }
        return false;
    }

    private static void refresh(LocationAPI loc) {
        if (loc == cachedFor) return;
        cachedFor = loc;
        belts.clear();
        if (loc == null) return;
        for (CampaignTerrainAPI t : loc.getTerrainCopy()) {
            if (t.getPlugin() instanceof AsteroidBeltTerrainPlugin) belts.add(t);
        }
    }

    private static void end() {
        if (!slowing) return;
        slowing = false;
        long ms = System.currentTimeMillis() - slowSince;
        if (ms > 1500L) EventLog.info("asteroids", "asteroid guard: crept through an asteroid belt (" + (ms / 1000L) + "s slow)");
    }
}
