package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.impl.campaign.abilities.TransponderAbility;
import com.fs.starfarer.api.impl.campaign.ids.Abilities;
import com.fs.starfarer.api.util.Misc;

/**
 * 1.1.32: transponder off while a hostile fleet is near, back on once the area has been clear for a while
 * (and only if that reveals us to nobody). Decision in {@link DarkMath}. Rules: dark_when_hostile,
 * dark_hostile_su, dark_restore_days.
 */
public final class DarkGuard {
    private DarkGuard() {}

    private static boolean weTurnedOff = false;
    private static long lastHostileTs = 0L;
    private static long nextCheck = 0L;

    /** Called every frame from the campaign script; does real work twice a second. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now < nextCheck) return;
        nextCheck = now + 500L;
        if (Global.getSector() == null || Global.getSector().isPaused()) return;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        if (pf == null) return;
        boolean busy = pf.getBattle() != null || Global.getSector().getCampaignUI().getCurrentInteractionDialog() != null
                || AskPopup.active() || nearFriendlyMarket(pf);
        float nearest = -1f;
        for (CampaignFleetAPI f : pf.getContainingLocation().getFleets()) {
            if (f == pf || f.isStationMode() || !f.isHostileTo(pf)) continue;
            float d = Misc.getDistance(pf, f);
            if (nearest < 0f || d < nearest) nearest = d;
        }
        float range = Rules.f("dark_hostile_su", 3000f);
        long clock = Global.getSector().getClock().getTimestamp();
        if (nearest >= 0f && nearest <= range) lastHostileTs = clock;
        float clear = lastHostileTs == 0L ? 999f : Global.getSector().getClock().getElapsedDaysSince(lastHostileTs);
        boolean reveals = false;
        if (!pf.isTransponderOn() && weTurnedOff) {
            try { reveals = !TransponderAbility.getFactionsThatWouldBecomeHostile(pf).isEmpty(); } catch (Throwable t) { reveals = true; }
        }
        String act = DarkMath.decide(Rules.b("dark_when_hostile", true), busy, pf.isTransponderOn(), nearest, range,
                weTurnedOff, clear, Rules.f("dark_restore_days", 1f), reveals);
        if (act == null) return;
        AbilityPlugin tp = pf.getAbility(Abilities.TRANSPONDER);
        if (tp == null) return;
        if (act.equals("off") && tp.isActive()) {
            tp.deactivate();
            weTurnedOff = true;
            EventLog.info("dark", "hostile fleet " + Math.round(nearest) + " su away: transponder OFF (seen from less far, not identified)");
        } else if (act.equals("on") && !tp.isActive()) {
            tp.activate();
            weTurnedOff = false;
            EventLog.info("dark", "no hostiles near for " + Math.round(clear) + " day(s): transponder back ON");
        }
    }

    /**
     * 1.1.33: at a market we can trade with, the transponder is left alone. Nov 5 live (Nova Maxios): it went dark
     * between docking and the purchase popup, and the open market refused to sell supplies with it off.
     */
    static boolean nearFriendlyMarket(CampaignFleetAPI pf) {
        try {
            for (com.fs.starfarer.api.campaign.econ.MarketAPI m : Global.getSector().getEconomy().getMarkets(pf.getContainingLocation())) {
                if (m.getPrimaryEntity() == null || m.getFaction().isHostileTo(pf.getFaction())) continue;
                if (Misc.getDistance(pf, m.getPrimaryEntity()) < Rules.f("dark_market_su", 600f) + m.getPrimaryEntity().getRadius()) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** The player or a command switched it: stop managing it until we switch it off again. */
    public static void forget() { weTurnedOff = false; }
}
