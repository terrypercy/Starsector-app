package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Ship recovery after a battle. 'recover' / 'recover list' reads the recoverable ships from the
 * battle's encounter context (the same list the game's 'Consider ship recovery' picker shows).
 * 'recover <ship>' hands the pick to the game's FleetEncounterContext.recoverShips - only with
 * recover_api = true, because that path skips the picker screen and has not been checked in game
 * (it may not charge what the picker charges, and the picker may still offer the ship afterwards).
 * Without it: open 'Consider ship recovery' on screen and pick there.
 */
public final class Recovery {

    private Recovery() {}

    private static InteractionDialogAPI cachedFor;
    private static List<FleetMemberAPI> cached;

    static FleetEncounterContext context(InteractionDialogAPI d) {
        if (d == null || !(d.getPlugin() instanceof FleetInteractionDialogPluginImpl)) return null;
        Object c = ((FleetInteractionDialogPluginImpl) d.getPlugin()).getContext();
        return c instanceof FleetEncounterContext ? (FleetEncounterContext) c : null;
    }

    static CampaignFleetAPI other(FleetEncounterContext c) {
        BattleAPI b = c.getBattle();
        if (b == null) return null;
        try { return b.getNonPlayerCombined(); } catch (Throwable t) { return null; }
    }

    static List<FleetMemberAPI> ships(InteractionDialogAPI d) {
        if (d == cachedFor && cached != null) return cached;
        FleetEncounterContext c = context(d);
        if (c == null) return null;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        List<FleetMemberAPI> l = new ArrayList<FleetMemberAPI>();
        try {
            List<FleetMemberAPI> r = c.getRecoverableShips(c.getBattle(), pf, other(c));
            if (r != null) l.addAll(r);
            List<FleetMemberAPI> s = c.getStoryRecoverableShips();
            if (s != null) for (FleetMemberAPI m : s) if (!l.contains(m)) l.add(m);
        } catch (Throwable ignored) {}
        cachedFor = d;
        cached = l;
        return l;
    }

    public static String command(String args) throws CommandException {
        InteractionDialogAPI d = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
        List<FleetMemberAPI> l = ships(d);
        if (l == null) throw new CommandException("no post-battle dialog open (recovery is offered right after a fight)");
        String a = args == null ? "" : args.trim();
        FleetEncounterContext c = context(d);
        List<FleetMemberAPI> story = c.getStoryRecoverableShips();
        if (a.isEmpty() || a.equalsIgnoreCase("list")) {
            if (l.isEmpty()) return "no recoverable ships";
            StringBuilder b = new StringBuilder("recoverable ships (pick on screen with 'Consider ship recovery', or 'recover <n>' when recover_api = true):");
            int i = 0;
            for (FleetMemberAPI m : l) {
                i++;
                int dm = 0;
                try { for (String h : m.getVariant().getHullMods()) if (Global.getSettings().getHullModSpec(h).hasTag("dmod")) dm++; } catch (Throwable ignored) {}
                b.append("\n  ").append(i).append(". ").append(m.getShipName()).append(" | ").append(m.getHullSpec().getHullName())
                 .append(" | ").append(m.getHullSpec().getHullSize()).append(" | ").append(Math.round(m.getDeploymentPointsCost())).append(" DP | d-mods ").append(dm)
                 .append(story != null && story.contains(m) ? " | STORY POINT needed (click it yourself)" : "");
            }
            return b.toString();
        }
        if (!Rules.b("recover_api", false))
            throw new CommandException("recover_api = false: open 'Consider ship recovery' on screen and pick there (see README: recovery)");
        FleetMemberAPI pick = null;
        if (CommandRunner.isNum(a)) { int n = (int) CommandRunner.num(a); if (n >= 1 && n <= l.size()) pick = l.get(n - 1); }
        else for (FleetMemberAPI m : l) if (Lookup.score(a, m.getHullId(), m.getShipName()) > 0 || Lookup.score(a, m.getHullId(), m.getHullSpec().getHullName()) > 0) { pick = m; break; }
        if (pick == null) throw new CommandException("no recoverable ship matches '" + a + "'");
        if (story != null && story.contains(pick)) throw new CommandException(pick.getShipName() + " needs a story point: click it yourself");
        List<FleetMemberAPI> one = new ArrayList<FleetMemberAPI>();
        one.add(pick);
        int before = Global.getSector().getPlayerFleet().getFleetData().getNumMembers();
        FleetEncounterContext.recoverShips(one, c, Global.getSector().getPlayerFleet(), other(c));
        l.remove(pick);
        int after = Global.getSector().getPlayerFleet().getFleetData().getNumMembers();
        EventLog.warn("recover", "recovered " + pick.getShipName() + " through the API (experimental): fleet " + before + " -> " + after + " ships");
        return "recovered " + pick.getShipName() + " (" + pick.getHullSpec().getHullName() + "); fleet now " + after + " ships";
    }
}
