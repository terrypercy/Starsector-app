package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.Locale;

/**
 * 1.0.9: 'preflight <bounty or market>' - a read-only GO / NO-GO report from live game data, run before every
 * departure: fuel for the round trip, supplies for twice the game's ETA there and back plus battle recovery,
 * what the docked market can still sell and at what price against the ceiling, cargo room, and the keep list.
 * It changes nothing.
 */
public final class Preflight {

    private Preflight() {}

    public static String report(String q) {
        String key = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return "usage: preflight <bounty name | market name>";
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        CargoAPI c = pf.getCargo();
        float perLY = Math.max(0.1f, pf.getLogistics().getFuelCostPerLightYear());
        float maint = Math.max(0.1f, Governor.maintPerDay(pf));

        String name = null;
        float ly = -1f, eta = -1f, fuelNeed = -1f, supDays;
        boolean battle = false;
        BountyRunner.Target hit = null;
        for (BountyRunner.Target t : BountyRunner.targets()) {
            if (t.name != null && t.name.toLowerCase(Locale.ROOT).contains(key)) { hit = t; break; }
        }
        if (hit != null) {
            name = hit.name + " (" + hit.where + ")";
            ly = hit.ly; eta = hit.eta; battle = true;
            fuelNeed = BountyRunner.roundTripFuel(hit);
            supDays = BountyRunner.tripSupplyDays(hit);
        } else {
            MarketAPI m = null;
            for (MarketAPI x : Global.getSector().getEconomy().getMarketsCopy()) {
                if (x.isHidden() || x.getPrimaryEntity() == null) continue;
                if (x.getName().toLowerCase(Locale.ROOT).startsWith(key)) { m = x; break; }
            }
            if (m == null) return "preflight: no bounty or market matching '" + q + "'";
            name = m.getName() + " (" + (m.getStarSystem() == null ? "hyperspace" : m.getStarSystem().getBaseName()) + ")";
            ly = Misc.getDistanceToPlayerLY(m.getPrimaryEntity());
            fuelNeed = TravelMath.fuel(ly * 1.2f + 1f, 2, perLY); // one way, like the trip check
            eta = -1f;
            float f = Rules.f("hunt_eta_factor", 2f);
            supDays = Math.max(Rules.f("gov_min_supply_days", 15f), ly * Rules.f("days_per_ly_estimate", 1.5f) * f + Rules.f("gov_min_supply_days", 15f));
        }

        float supNeed = supDays * maint;
        boolean go = true;
        StringBuilder b = new StringBuilder("preflight " + name + ": " + String.format(Locale.ROOT, "%.1f", ly) + " ly"
                + (eta > 0 ? ", game ETA " + Math.round(eta) + " d (planning x" + Rules.f("hunt_eta_factor", 2f) + ")" : "") + "\n");

        boolean fuelOk = fuelNeed >= 0f && c.getFuel() >= fuelNeed;
        go &= fuelOk;
        b.append("  fuel      ").append(n(c.getFuel())).append(" / need ").append(fuelNeed < 0 ? "?" : n(fuelNeed))
         .append(battle ? " round trip" : " to arrive").append(fuelOk ? "   ok" : "   NO").append("\n");
        boolean supOk = c.getSupplies() >= supNeed;
        go &= supOk;
        b.append("  supplies  ").append(n(c.getSupplies())).append(" / need ").append(n(supNeed)).append(" (").append(Math.round(supDays))
         .append(" days at ").append(String.format(Locale.ROOT, "%.1f", maint)).append("/day)").append(supOk ? "   ok" : "   NO").append("\n");

        MarketAPI here = GameReader.dockedMarket();
        if (here != null) {
            for (String id : new String[]{"fuel", "supplies"}) {
                float stock = Trader.liveStock(here, true, id) + Trader.liveStock(here, false, id);
                boolean black = Trader.liveStock(here, true, id) >= 1f;
                float unit = Trader.buyCost(here, black, id, 1f);
                float cap = Trader.ceiling(id);
                b.append("  market    ").append(here.getName()).append(" ").append(id).append(": stock ").append(n(stock))
                 .append(", ").append(n(unit)).append("/unit vs ceiling ").append(cap < 0 ? "none" : n(cap))
                 .append(cap > 0 && unit > cap ? "   OVER" : "").append("\n");
            }
        } else b.append("  market    not docked\n");

        float room = c.getMaxCapacity() - c.getSpaceUsed();
        b.append("  cargo     ").append(n(room)).append(" free of ").append(n(c.getMaxCapacity())).append("\n");

        StringBuilder kept = new StringBuilder();
        for (CargoStackAPI st : c.getStacksCopy()) {
            if (!st.isCommodityStack()) continue;
            int floor = Trader.keepFloor(st.getCommodityId(), st.getDisplayName());
            if (floor > 0) kept.append(kept.length() == 0 ? "" : ", ").append(st.getDisplayName()).append(" ").append(n(st.getSize()))
                    .append(floor == Integer.MAX_VALUE ? " (all kept)" : " (keep " + floor + ")");
        }
        b.append("  keep list ").append(kept.length() == 0 ? "nothing aboard on it" : kept.toString()).append("\n");
        if (Goals.Resupply.lastQuote.length() > 0) b.append("  last quote ").append(Goals.Resupply.lastQuote).append("\n");

        b.append(go ? "GO" : "NO-GO: " + (!fuelOk ? "short " + n(Math.max(0f, fuelNeed - c.getFuel())) + " fuel " : "")
                + (!supOk ? "short " + n(supNeed - c.getSupplies()) + " supplies (ask the player before buying)" : ""));
        return b.toString();
    }

    static String n(float v) { return Misc.getWithDGS(Math.round(v)); }
}
