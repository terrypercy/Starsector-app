package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel;
import com.fs.starfarer.api.impl.campaign.intel.SystemBountyIntel;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.List;

/** Faction standings, distance to a commission, stipend, and what raises standing (game side of RepMath). */
public final class Reputation {

    private Reputation() {}

    /** The standing a commission needs, on the -100..100 scale (Commission.COMMISSION_REQ, Favorable = 10). */
    public static float required() {
        try {
            float v = com.fs.starfarer.api.impl.campaign.rulecmd.missions.Commission.COMMISSION_REQ.getMin();
            return v <= 1.0001f ? v * 100f : v;
        } catch (Throwable t) { return 10f; }
    }

    public static float stipend() {
        int level = 1;
        try { level = Global.getSector().getPlayerStats().getLevel(); } catch (Throwable ignored) {}
        float base = 20000f, per = 5000f;
        try { base = Global.getSettings().getFloat("factionCommissionStipendBase"); } catch (Throwable ignored) {}
        try { per = Global.getSettings().getFloat("factionCommissionStipendPerLevel"); } catch (Throwable ignored) {}
        return RepMath.stipend(level, base, per);
    }

    private static List<RepMath.Faction> cache;
    private static long cachedAt = 0L;

    /** Cached for 10 s (the deficit count walks every market). */
    public static synchronized List<RepMath.Faction> factions() {
        long now = System.currentTimeMillis();
        if (cache != null && now - cachedAt < 10000L) return cache;
        cache = read();
        cachedAt = now;
        return cache;
    }

    static List<RepMath.Faction> read() {
        List<RepMath.Faction> out = new ArrayList<RepMath.Faction>();
        List<String> bountyFactions = new ArrayList<String>();
        try {
            for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel(SystemBountyIntel.class)) {
                SystemBountyIntel b = (SystemBountyIntel) i;
                if (!b.isEnded() && b.getFactionForUIColors() != null) bountyFactions.add(b.getFactionForUIColors().getId());
            }
            for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel(PersonBountyIntel.class)) {
                PersonBountyIntel b = (PersonBountyIntel) i;
                if (!b.isEnded() && b.getFactionForUIColors() != null) bountyFactions.add(b.getFactionForUIColors().getId());
            }
        } catch (Throwable ignored) {}
        for (FactionAPI f : Global.getSector().getAllFactions()) {
            if (!f.isShowInIntelTab() || f.isPlayerFaction()) continue;
            RepMath.Faction r = new RepMath.Faction(f.getId(), f.getDisplayName(), f.getRelationship("player") * 100f);
            r.hostile = f.isHostileTo("player");
            r.hasBounties = bountyFactions.contains(f.getId());
            int deficits = 0;
            try {
                for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
                    if (m.getFaction() != f || m.isHidden()) continue;
                    for (CommodityOnMarketAPI c : m.getAllCommodities()) if (c.getDeficitQuantity() > 0 && !c.getCommodity().isMeta()) deficits++;
                }
            } catch (Throwable ignored) {}
            r.deficits = deficits;
            out.add(r);
        }
        return out;
    }

    public static String suggestionText(FactionAPI f) {
        for (RepMath.Faction r : factions()) if (r.id.equals(f.getId())) return "ways to gain standing:\n  - " + DialogAgent.join(RepMath.suggestions(r, required()), "\n  - ");
        return "";
    }

    public static RepMath.Faction target() {
        String pref = Rules.s("hud_rep_faction", "auto");
        return RepMath.pickTarget(factions(), required(), pref.equalsIgnoreCase("auto") ? null : pref);
    }

    public static String report() {
        StringBuilder b = new StringBuilder();
        String cur = Misc.getCommissionFactionId();
        float req = required();
        b.append("commission: ").append(cur == null ? "none" : cur).append(" | needs ").append(Math.round(req)).append(" (Favorable) | stipend at your level ")
         .append(Misc.getWithDGS(Math.round(stipend()))).append(" cr/month (same for every faction)\n");
        for (RepMath.Faction f : factions()) {
            b.append("  ").append(f.name).append(" [").append(f.id).append("] ").append(Math.round(f.rel))
             .append(f.rel >= req ? " - commission possible" : " - " + Math.round(RepMath.toFavorable(f.rel, req)) + " to go").append(f.hostile ? " HOSTILE" : "").append("\n");
        }
        RepMath.Faction t = target();
        if (t != null) b.append("best target ").append(t.name).append(":\n  - ").append(DialogAgent.join(RepMath.suggestions(t, req), "\n  - "));
        return b.toString();
    }
}
