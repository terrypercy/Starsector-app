package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin.CellStateTracker;
import org.lwjgl.util.vector.Vector2f;

/**
 * 1.0.6: hyperspace storm guard. The game never strikes a fleet that is moving slowly
 * (HyperspaceTerrainPlugin: "cell.isStorming() && !Misc.isSlowMoving(fleet)"), so while a storming
 * (or about-to-storm) cell is under the player fleet or just ahead of it, ask the fleet to go slow
 * for this frame, exactly as holding the move-slowly key does. Autopilot keeps its course; the fleet
 * speeds up again once past the cell. Rule: storm_guard (default on), storm_guard_lookahead_s.
 */
public final class StormGuard {
    private StormGuard() {}

    private static HyperspaceTerrainPlugin cached;
    private static LocationAPI cachedFor;
    private static boolean slowing;
    private static long slowSince;
    private static int cellsAvoided;

    public static int avoided() { return cellsAvoided; }

    /** Call every frame from the campaign script. Cheap: a few tile lookups. */
    public static void tick() {
        if (!Rules.b("storm_guard", true)) { slowing = false; return; }
        if (Global.getSector() == null || Global.getSector().isPaused()) return;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        if (pf == null || !pf.isInHyperspace() || pf.isInHyperspaceTransition() || pf.getBattle() != null) {
            end(pf);
            return;
        }
        HyperspaceTerrainPlugin hp = plugin(pf.getContainingLocation());
        if (hp == null) return;

        boolean danger = bad(hp.getCellAt(pf, 100f));
        Vector2f v = pf.getVelocity();
        float speed = v == null ? 0f : v.length();
        if (!danger && speed > 5f) {
            float tile = hp.getTileSize();
            float secs = Rules.f("storm_guard_lookahead_s", 2.5f);
            float reach = Math.max(tile, speed * secs);
            Vector2f loc = pf.getLocation();
            for (float d = tile * 0.5f; d <= reach && !danger; d += tile * 0.5f) {
                Vector2f p = new Vector2f(loc.x + v.x / speed * d, loc.y + v.y / speed * d);
                danger = bad(hp.getCellAt(p, pf.getRadius()));
            }
        }
        if (danger) {
            pf.goSlowOneFrame();
            if (!slowing) {
                slowing = true;
                slowSince = System.currentTimeMillis();
                cellsAvoided++;
            }
        } else {
            end(pf);
        }
    }

    private static void end(CampaignFleetAPI pf) {
        if (!slowing) return;
        slowing = false;
        long ms = System.currentTimeMillis() - slowSince;
        if (ms > 1500L) EventLog.info("storm", "storm guard: crept past a storm cell (" + (ms / 1000L) + "s slow)");
    }

    public static boolean bad(CellStateTracker c) {
        if (c == null) return false;
        if (c.isStorming()) return true;
        // a signaling cell close to its storm flips to storm the moment a fleet enters it
        return c.isSignaling() && c.signal < 0.3f;
    }

    private static HyperspaceTerrainPlugin plugin(LocationAPI hyper) {
        if (hyper == null) return null;
        if (cached != null && cachedFor == hyper) return cached;
        cached = null;
        cachedFor = hyper;
        for (CampaignTerrainAPI t : hyper.getTerrainCopy()) {
            if (t.getPlugin() instanceof HyperspaceTerrainPlugin) { cached = (HyperspaceTerrainPlugin) t.getPlugin(); break; }
        }
        return cached;
    }
}
