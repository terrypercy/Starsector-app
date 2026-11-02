package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.loading.IndustrySpecAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1.1.7 (Nov 2): 'colony build <colony>' lists what a player colony can build (cost, days, why not), and
 * 'colony build <colony> <industry>' starts it - the same steps as the game's build picker: add the industry,
 * start construction, pay its build cost. Building always goes through the in-game Yes popup (BatchMath).
 * Works from anywhere, like the game's colony screen (no docking needed).
 */
public final class ColonyBuild {

    private ColonyBuild() {}

    static MarketAPI colony(String name) throws CommandException {
        List<MarketAPI> own = Misc.getPlayerMarkets(true);
        if (own.isEmpty()) throw new CommandException("you have no colonies");
        if (name == null || name.isEmpty()) {
            if (own.size() == 1) return own.get(0);
            throw new CommandException("which colony? " + names(own));
        }
        for (MarketAPI m : own) if (m.getName().equalsIgnoreCase(name)) return m;
        for (MarketAPI m : own) if (m.getName().toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))) return m;
        throw new CommandException("no colony named '" + name + "' (yours: " + names(own) + ")");
    }

    static String names(List<MarketAPI> l) {
        StringBuilder b = new StringBuilder();
        for (MarketAPI m : l) b.append(b.length() == 0 ? "" : ", ").append(m.getName());
        return b.toString();
    }

    /** "<colony> [industry words]". */
    public static String command(String args) throws CommandException {
        String a = args == null ? "" : args.trim();
        List<MarketAPI> own = Misc.getPlayerMarkets(true);
        MarketAPI m = null;
        String rest = a;
        for (MarketAPI x : own) {
            String n = x.getName().toLowerCase(Locale.ROOT);
            if (a.toLowerCase(Locale.ROOT).equals(n) || a.toLowerCase(Locale.ROOT).startsWith(n + " ")) { m = x; rest = a.substring(n.length()).trim(); break; }
        }
        if (m == null) {
            if (!a.isEmpty()) throw new CommandException("start with the colony's name: colony build <colony> [industry] (yours: " + names(own) + ")");
            m = colony("");
        }
        if (rest.isEmpty()) return list(m);
        return build(m, rest);
    }

    static List<IndustrySpecAPI> candidates(MarketAPI m) {
        List<IndustrySpecAPI> out = new ArrayList<IndustrySpecAPI>();
        for (IndustrySpecAPI s : Global.getSettings().getAllIndustrySpecs()) {
            if (m.hasIndustry(s.getId())) continue;
            if (s.hasTag("upgrade") || s.getDowngrade() != null) continue; // upgrades come from the base industry
            out.add(s);
        }
        return out;
    }

    static String list(MarketAPI m) {
        StringBuilder b = new StringBuilder("colony build " + m.getName() + " (size " + m.getSize() + "): industries "
                + Misc.getNumIndustries(m) + "/" + Misc.getMaxIndustries(m) + ", credits " + Math.round(Global.getSector().getPlayerFleet().getCargo().getCredits().get()));
        Industry busy = Misc.getCurrentlyBeingConstructed(m);
        if (busy != null) b.append("\n  under construction: ").append(busy.getCurrentName()).append(" (").append(busy.getBuildOrUpgradeDaysText()).append(")");
        for (IndustrySpecAPI s : candidates(m)) {
            Industry ind;
            try { ind = m.instantiateIndustry(s.getId()); } catch (Throwable t) { continue; }
            if (ind == null) continue;
            boolean ok = false;
            String why = "";
            try { ok = ind.isAvailableToBuild(); if (!ok) why = ind.getUnavailableReason(); } catch (Throwable ignored) {}
            boolean show = ok;
            try { show = ok || ind.showWhenUnavailable(); } catch (Throwable ignored) {}
            if (!show) continue;
            b.append("\n  ").append(ok ? "OK  " : "no  ").append(s.getName()).append(" [").append(s.getId()).append("] ")
             .append(ind.isIndustry() ? "industry" : "structure").append(" | ").append(Math.round(ind.getBuildCost())).append(" cr | ")
             .append(Math.round(ind.getBuildTime())).append(" days");
            if (!ok && why != null && !why.isEmpty()) b.append(" | ").append(why.replace('\n', ' '));
        }
        b.append("\n  build with: colony build ").append(m.getName()).append(" <name or id> (asks in game first)");
        return b.toString();
    }

    static String build(MarketAPI m, String what) throws CommandException {
        IndustrySpecAPI pick = null;
        String w = what.toLowerCase(Locale.ROOT).trim();
        for (IndustrySpecAPI s : candidates(m)) if (s.getId().equalsIgnoreCase(w) || s.getName().equalsIgnoreCase(w)) { pick = s; break; }
        if (pick == null) for (IndustrySpecAPI s : candidates(m)) if (s.getName().toLowerCase(Locale.ROOT).startsWith(w)) { pick = s; break; }
        if (pick == null) throw new CommandException("nothing called '" + what + "' can be built at " + m.getName() + " ('colony build " + m.getName() + "' lists it)");
        Industry busy = Misc.getCurrentlyBeingConstructed(m);
        if (busy != null) throw new CommandException(m.getName() + " is already building " + busy.getCurrentName() + " (" + busy.getBuildOrUpgradeDaysText() + ")");
        Industry probe = m.instantiateIndustry(pick.getId());
        if (!probe.isAvailableToBuild()) throw new CommandException(pick.getName() + " can't be built: " + probe.getUnavailableReason());
        if (probe.isIndustry() && Misc.getNumIndustries(m) >= Misc.getMaxIndustries(m))
            throw new CommandException(m.getName() + " already has its maximum " + Misc.getMaxIndustries(m) + " industries");
        float cost = probe.getBuildCost();
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        float reserve = Rules.f("reserve_credits", 5000f);
        if (credits - cost < reserve) throw new CommandException(pick.getName() + " costs " + Math.round(cost) + " cr; credits " + Math.round(credits) + " would drop under the reserve of " + Math.round(reserve));
        m.addIndustry(pick.getId());
        Industry ind = m.getIndustry(pick.getId());
        if (ind == null) throw new CommandException("the game did not add " + pick.getName());
        ind.startBuilding();
        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(cost);
        EventLog.info("colony", "started building " + pick.getName() + " at " + m.getName() + " for " + Math.round(cost) + " cr");
        return "started " + pick.getName() + " at " + m.getName() + ": " + Math.round(cost) + " cr, " + ind.getBuildOrUpgradeDaysText()
                + "; credits " + Math.round(Global.getSector().getPlayerFleet().getCargo().getCredits().get());
    }
}
