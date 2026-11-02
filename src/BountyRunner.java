package claudecaptain;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel;
import com.fs.starfarer.api.impl.campaign.intel.SystemBountyIntel;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounty runner (1.2.0): picks a bounty the fleet clearly outguns, flies to the target, engages it, lets
 * Claude Bridge's auto-battle fight, then rests at a friendly market until combat readiness is back.
 *
 * Inbox: bounties | hunt <n> | hunt auto [count] | hunt stop | hunt status
 *
 * Rules (trade_rules.txt): hunt_margin (our deployment points / theirs, default 1.5), hunt_min_cr (0.6),
 * hunt_rest_cr (0.7), hunt_max_ly (20). Never spends story points, never attacks non-hostile fleets,
 * pauses and reports anything it doesn't understand.
 */
public class BountyRunner implements EveryFrameScript {

    private static final Logger log = Global.getLogger(BountyRunner.class);

    enum Phase { IDLE, TRAVEL, ENGAGE, AFTER, REST, RESUPPLY, EXPLORE, PAUSED }

    static final class Target {
        String name, kind, where, faction = "";
        float reward, theirDP, ly, daysLeft, eta;
        CampaignFleetAPI fleet;
        PersonBountyIntel person;
        SystemBountyIntel system;
        LocationAPI loc;
        boolean ok;
        String why = "";
    }

    private static BountyRunner instance;
    public static volatile String state = "idle", note = "", lastResult = "";
    private static final StringBuilder report = new StringBuilder();

    private Phase phase = Phase.IDLE;
    private Target target;
    private int huntsLeft = 0;
    private float creditsAtStart;
    private long waitUntil = 0L, lastInbox = 0L, phaseStartTs;
    private int clicks = 0;
    private SectorEntityToken restAt;
    /** 1.0.9: the player took this target with 'hunt <n> anyway' (margin waived for this hunt). */
    boolean playerOverride = false;
    private boolean recoveryShown = false;
    private boolean departChecked = false; // 1.0.8: round trip verified before the fleet leaves
    private Phase pausedFrom = Phase.IDLE;
    private float bestDistLY = Float.MAX_VALUE;
    /** Sub-goals run inside a hunt: resupply/repair between targets, exploration detours. */
    private Goals.Goal sub;
    private Phase afterSub = Phase.TRAVEL;
    private int exploredHere = 0;
    private LocationAPI exploredLoc;
    private final java.util.Set<String> triedSites = new java.util.HashSet<String>();
    static final java.util.List<String> HUNT_CTX = DialogAgent.ctx("hunt", "travel");
    private long bestDistTs = 0L;
    // 1.0.3: in-system movement on the way out counts as progress (light-years can't shrink before the jump)
    private com.fs.starfarer.api.campaign.LocationAPI moveLoc;
    private float moveX, moveY;
    // 1.0.4: chasing a fleet that runs
    private float chaseDist = -1f;
    private long chaseTs = 0L;
    private boolean chaseClosing = true, ebUsed = false;
    private String lastChase = "";
    private Target chaseTarget;

    public static void install() {
        instance = new BountyRunner();
        Global.getSector().addTransientScript(instance);
        state = "idle";
    }

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return true; }

    private static SectorAPI sector() { return Global.getSector(); }
    private static CampaignFleetAPI pf() { return sector().getPlayerFleet(); }

    // ------------------------------------------------------------------ strength

    static float ourDP() {
        float dp = 0f;
        for (FleetMemberAPI m : pf().getFleetData().getMembersListCopy()) {
            if (Shipyard.civilian(m) || m.isMothballed() || m.getRepairTracker().getCR() < 0.25f) continue;
            dp += m.getDeploymentPointsCost();
        }
        return dp;
    }

    static float lowestCombatCR() {
        float lo = 1f;
        for (FleetMemberAPI m : pf().getFleetData().getMembersListCopy()) {
            if (Shipyard.civilian(m) || m.isMothballed()) continue;
            lo = Math.min(lo, m.getRepairTracker().getCR());
        }
        return lo;
    }

    static float dpOf(CampaignFleetAPI f) {
        if (f == null || f.getFleetData() == null) return 0f;
        float dp = 0f;
        for (FleetMemberAPI m : f.getFleetData().getMembersListCopy()) dp += m.getDeploymentPointsCost();
        // 1.0.8: fleets far from the player may not be filled in yet (no members); their fleet points still say how big they are
        if (dp <= 0f) try { dp = f.getFleetPoints(); } catch (Throwable ignored) {}
        return dp;
    }

    static int lastSeen = 0, lastHostile = 0;
    static float lastSmallest = 0f;

    // ------------------------------------------------------------------ listing

    static List<Target> targets() {
        List<Target> out = new ArrayList<Target>();
        float ours = ourDP(), margin = Rules.f("hunt_margin", 1.5f), maxLy = Rules.f("hunt_max_ly", 20f);
        for (IntelInfoPlugin i : sector().getIntelManager().getIntel(PersonBountyIntel.class)) {
            PersonBountyIntel b = (PersonBountyIntel) i;
            if (b.isEnded() || b.isEnding() || b.getFleet() == null || !b.getFleet().isAlive()) continue;
            Target t = new Target();
            t.person = b; t.kind = "person"; t.fleet = b.getFleet();
            t.name = b.getPerson() == null ? b.getName() : b.getPerson().getNameString();
            t.reward = b.getBountyCredits();
            t.loc = t.fleet.getContainingLocation();
            t.where = t.loc == null ? "?" : t.loc.getName();
            t.theirDP = dpOf(t.fleet);
            t.ly = Misc.getDistanceToPlayerLY(t.fleet);
            t.daysLeft = b.getDuration() - b.getElapsedDays();
            try { t.faction = b.getFactionForUIColors() == null ? "" : b.getFactionForUIColors().getDisplayName(); } catch (Throwable ignored) {}
            judge(t, ours, margin, maxLy);
            out.add(t);
        }
        // 1.0.8: pirate (and Pather) home systems: fleets there pay the commission's per-kill bonus even with no bounty
        if (Rules.b("hunt_pirates", true)) {
            java.util.Set<LocationAPI> seen = new java.util.HashSet<LocationAPI>();
            for (MarketAPI m : sector().getEconomy().getMarketsCopy()) {
                if (m.isHidden() || m.getStarSystem() == null || m.getPrimaryEntity() == null) continue;
                String fid = m.getFaction().getId();
                if (!"pirates".equals(fid) && !"luddic_path".equals(fid)) continue;
                if (!m.getFaction().isHostileTo(pf().getFaction())) continue;
                LocationAPI loc = m.getStarSystem();
                if (!seen.add(loc)) continue;
                Target t = new Target();
                t.kind = "pirate"; t.loc = loc; t.where = loc.getName();
                t.name = m.getFaction().getDisplayName() + " fleets near " + m.getName();
                t.faction = m.getFaction().getDisplayName();
                t.ly = Misc.getDistanceToPlayerLY(m.getPrimaryEntity());
                t.fleet = weakestHostileIn(loc, ours / margin);
                t.theirDP = t.fleet == null ? 0f : dpOf(t.fleet);
                if (t.fleet == null) { t.ok = false; t.why = "no hostile fleet weak enough there right now (" + lastHostile + " hostile of " + lastSeen + " fleets"
                        + (lastHostile > 0 ? ", smallest " + Math.round(lastSmallest) + " DP" : "") + ")"; }
                else judge(t, ours, margin, Rules.f("hunt_pirates_max_ly", 12f));
                out.add(t);
            }
        }
        for (IntelInfoPlugin i : sector().getIntelManager().getIntel(SystemBountyIntel.class)) {
            SystemBountyIntel b = (SystemBountyIntel) i;
            if (b.isEnded() || b.isEnding() || b.getMarket() == null || b.getMarket().getStarSystem() == null) continue;
            Target t = new Target();
            t.system = b; t.kind = "system"; t.loc = b.getMarket().getStarSystem();
            t.name = b.getName(); t.where = t.loc.getName();
            try { t.faction = b.getFactionForUIColors() == null ? "" : b.getFactionForUIColors().getDisplayName(); } catch (Throwable ignored) {}
            t.ly = Misc.getDistanceToPlayerLY(b.getMarket().getPrimaryEntity());
            t.fleet = weakestHostileIn(t.loc, ours / margin);
            t.theirDP = t.fleet == null ? 0f : dpOf(t.fleet);
            if (t.fleet == null) { t.ok = false; t.why = "no hostile fleet weak enough in " + t.where + " right now"; }
            else judge(t, ours, margin, maxLy);
            out.add(t);
        }
        return out;
    }

    private static void judge(Target t, float ours, float margin, float maxLy) {
        t.eta = etaTo(t.fleet != null ? t.fleet : null, t.ly, t.loc);
        if (ours <= 0f) { t.ok = false; t.why = "no combat ships ready"; return; }
        if (t.theirDP * margin > ours) { t.ok = false; t.why = "too strong: " + Math.round(t.theirDP) + " DP vs our " + Math.round(ours); return; }
        if (t.ly > maxLy) { t.ok = false; t.why = "too far: " + String.format("%.1f", t.ly) + " ly"; return; }
        // 1.0.7: a bounty that expires before we can get there (with margin) isn't worth the trip
        if (TravelMath.missesDeadline(t.daysLeft, t.eta, Rules.f("hunt_deadline_mult", 1.3f), Rules.f("hunt_deadline_extra_days", 3f))) {
            t.ok = false; t.why = "won't make the deadline: ~" + Math.round(t.eta) + " days away, " + Math.round(t.daysLeft) + " left"; return;
        }
        t.ok = true;
    }

    /** Jumps from the player fleet to a token's location. */
    static int jumpsTo(SectorEntityToken e) {
        LocationAPI there = e == null ? null : e.getContainingLocation();
        return TravelMath.jumps(there == pf().getContainingLocation(), pf().isInHyperspace(), there != null && there.isHyperspace());
    }

    /** Days to reach a target at the fleet's travel speed (sustained burn not counted, so it's conservative). */
    static float etaTo(SectorEntityToken e, float ly, LocationAPI loc) {
        try {
            int j = e != null ? jumpsTo(e) : TravelMath.jumps(loc == pf().getContainingLocation(), pf().isInHyperspace(), loc != null && loc.isHyperspace());
            return TravelMath.etaDays(ly, j, pf().getTravelSpeed(), Misc.getUnitsPerLightYear(), sector().getClock().getSecondsPerDay());
        } catch (Throwable t) { return 0f; }
    }

    static CampaignFleetAPI weakestHostileIn(LocationAPI loc, float maxDP) {
        CampaignFleetAPI best = null;
        float bestDP = Float.MAX_VALUE;
        lastSeen = 0; lastHostile = 0; lastSmallest = 0f;
        if (loc == null) return null;
        for (CampaignFleetAPI f : loc.getFleets()) {
            if (f == pf()) continue;
            lastSeen++;
            if (!f.isAlive() || f.isStationMode()) continue;
            // 1.0.8: hostility by faction (isHostileTo(fleet) can be false for fleets not near the player)
            if (!f.getFaction().isHostileTo(pf().getFaction()) && !f.isHostileTo(pf())) continue;
            float dp = dpOf(f);
            if (dp <= 0f) continue;
            lastHostile++;
            if (lastSmallest == 0f || dp < lastSmallest) lastSmallest = dp;
            if (dp > maxDP) continue;
            if (dp < bestDP) { bestDP = dp; best = f; }
        }
        return best;
    }

    /** 1.0.8: "bounty <name>": what happened to a person bounty, ended ones included (result, payment, target ship). */
    static String resultText(PersonBountyIntel b) {
        String who = b.getPerson() == null ? b.getName() : b.getPerson().getNameString();
        StringBuilder s = new StringBuilder(who).append(": ");
        PersonBountyIntel.BountyResult r = b.getResult();
        if (r == null) s.append(b.isEnded() || b.isEnding() ? "ended, no result recorded" : "still active");
        else {
            switch (r.type) {
                case END_PLAYER_BOUNTY: s.append("paid ").append(Shipyard.n(r.payment)).append(" cr (you destroyed the target)"); break;
                case END_PLAYER_NO_BOUNTY: s.append("NOT paid: you destroyed the target, but the bounty pays nothing to you (no standing with the poster?)"); break;
                case END_PLAYER_NO_REWARD: s.append("NOT paid: you destroyed the target but got no reward"); break;
                case END_OTHER: s.append("NOT paid: someone else got the target, or its fleet despawned"); break;
                case END_TIME: s.append("NOT paid: expired"); break;
                default: s.append(r.type);
            }
            if (r.rep != null) try { s.append("; standing change ").append(Math.round(r.rep.delta * 100f)); } catch (Throwable ignored) {}
        }
        try { if (b.getFactionForUIColors() != null) s.append(" | posted by ").append(b.getFactionForUIColors().getDisplayName()); } catch (Throwable ignored) {}
        s.append(" | reward ").append(Shipyard.n(b.getBountyCredits())).append(" cr");
        try {
            com.fs.starfarer.api.fleet.FleetMemberAPI fs = b.getFlagship();
            CampaignFleetAPI f = b.getFleet();
            boolean alive = fs != null && f != null && f.isAlive() && f.getFleetData().getMembersListCopy().contains(fs);
            if (fs != null) s.append(" | target ship ").append(fs.getShipName()).append(" (").append(fs.getHullSpec().getHullName()).append(") ").append(alive ? "STILL ALIVE" : "gone");
        } catch (Throwable ignored) {}
        return s.toString();
    }

    static String result(String q) {
        String key = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (IntelInfoPlugin i : sector().getIntelManager().getIntel(PersonBountyIntel.class)) {
            PersonBountyIntel p = (PersonBountyIntel) i;
            String who = p.getPerson() == null ? p.getName() : p.getPerson().getNameString();
            if (key.length() > 0 && (who == null || !who.toLowerCase(java.util.Locale.ROOT).contains(key))) continue;
            b.append(resultText(p)).append("\n");
        }
        return b.length() == 0 ? "no person bounty matching '" + q + "' in the intel (ended ones drop off after a while)" : b.toString().trim();
    }

    static String list() {
        List<Target> ts = targets();
        StringBuilder b = new StringBuilder("bounties (our combat strength " + Math.round(ourDP()) + " DP, lowest CR "
                + Math.round(lowestCombatCR() * 100f) + "%, margin " + Rules.f("hunt_margin", 1.5f) + "x)\n");
        // 1.0.8: how many bounty targets share each system (stacking 2-3 in one trip pays best)
        java.util.Map<LocationAPI, Integer> per = new java.util.HashMap<LocationAPI, Integer>();
        for (Target t : ts) if (t.loc != null && t.fleet != null) per.put(t.loc, (per.containsKey(t.loc) ? per.get(t.loc) : 0) + 1);
        int i = 0;
        for (Target t : ts) {
            i++;
            b.append(i).append(". ").append(t.kind.equals("person") ? "" : t.kind.equals("pirate") ? "[pirates] " : "[system] ").append(t.name).append(" | ").append(t.where)
             .append(" | ").append(String.format("%.1f", t.ly)).append(" ly");
            if (t.reward > 0) b.append(" | ").append(Shipyard.n(t.reward)).append(" cr");
            if (t.faction != null && t.faction.length() > 0) b.append(" | posted by ").append(t.faction); // 1.0.8: who pays (and gives reputation)
            if (t.daysLeft > 0) b.append(" | ").append(Math.round(t.daysLeft)).append(" days left");
            if (t.eta > 0) b.append(" | ~").append(Math.round(t.eta)).append(" days away");
            if (t.loc != null && per.containsKey(t.loc) && per.get(t.loc) > 1) b.append(" | ").append(per.get(t.loc)).append(" targets in this system");
            b.append(" | target ").append(Math.round(t.theirDP)).append(" DP | ").append(t.ok ? "OK" : "skip: " + t.why).append("\n");
        }
        if (i == 0) b.append("(no bounties known - visit markets and check the intel screen)\n");
        return b.toString();
    }

    // ------------------------------------------------------------------ control

    static String hunt(String arg) {
        if (instance == null) return "not ready (no campaign loaded)";
        String a = arg == null ? "" : arg.trim().toLowerCase();
        if (a.equals("stop")) { instance.huntsLeft = 0; instance.stop("stopped by Claude"); return "hunt stopped"; }
        if (a.equals("status") || a.isEmpty()) return status();
        if (a.equals("resume anyway")) { // 1.0.9: the player decided to leave short anyway
            if (instance.phase != Phase.PAUSED) return "no paused hunt";
            instance.departChecked = true;
            // 1.1.3 (Nov 2): it also waives the strength margin, like 'hunt <n> anyway' (Shepherd at 271 DP vs our 297
            // tripped 1.1x by one point and 'hunt resume' re-paused on it); a target stronger than the whole fleet still stops it
            instance.playerOverride = true;
            EventLog.alert("hunt", "resuming although the rules say no (supplies short or target over the margin): the player's decision");
            a = "resume";
        }
        if (a.equals("resume")) {
            if (instance.phase != Phase.PAUSED || instance.target == null) return "no paused hunt";
            Phase p = instance.pausedFrom == Phase.IDLE || instance.pausedFrom == Phase.PAUSED ? Phase.TRAVEL : instance.pausedFrom;
            instance.clicks = 0;
            instance.bestDistTs = 0L;
            if (instance.sub != null && "paused".equals(instance.sub.state)) instance.sub.resume();
            instance.setPhase(p, "resumed by Claude");
            if (p != Phase.TRAVEL) Governor.unpause();
            return "hunt resumed (" + p.name().toLowerCase() + ")";
        }
        if (TripRunner.busy()) return "a trade trip is " + TripRunner.state + "; finish or cancel it first";
        if (!"idle".equals(state)) return "already hunting (" + state + "); 'hunt stop' first";
        List<Target> ts = targets();
        Target pick = null;
        if (a.startsWith("auto")) {
            String[] p = a.split("\\s+");
            instance.huntsLeft = p.length > 1 ? Math.max(1, Integer.parseInt(p[1])) : 1;
            instance.playerOverride = false;
            pick = best(ts);
            if (pick == null) { instance.huntsLeft = 0; return "no bounty passes the rules now\n" + list(); }
        } else {
            int n;
            // 1.0.9 (Nov 2): "hunt <n> anyway" - the player takes a bounty the rules rate too strong or too far.
            // It always goes through the in-game Yes popup first (BatchMath.needsApproval), and the fuel/supply
            // departure gate still applies.
            boolean anyway = a.endsWith(" anyway");
            String num = anyway ? a.substring(0, a.length() - " anyway".length()).trim() : a;
            try { n = Integer.parseInt(num); } catch (NumberFormatException e) { return "usage: hunt <n> [anyway] | hunt auto [count] | hunt stop | hunt status"; }
            if (n < 1 || n > ts.size()) return "no bounty " + n + " (see 'bounties')";
            pick = ts.get(n - 1);
            if (!pick.ok && !anyway) return "bounty " + n + " fails the rules: " + pick.why + " ('hunt " + n + " anyway' asks the player)";
            if (!pick.ok) EventLog.alert("hunt", "hunting " + pick.name + " although it fails the rules (" + pick.why + "): the player's decision");
            instance.playerOverride = !pick.ok;
            instance.huntsLeft = 1;
        }
        instance.begin(pick);
        return "hunting " + pick.name + " in " + pick.where + " (" + Math.round(pick.theirDP) + " DP vs our " + Math.round(ourDP()) + ")";
    }

    static Target best(List<Target> ts) {
        Target b1 = best(ts, false);
        return b1 != null ? b1 : best(ts, true); // 1.0.8: pirate fleets only when no bounty is worth it
    }

    static Target best(List<Target> ts, boolean pirates) {
        Target best = null;
        float bestScore = 0f;
        for (Target t : ts) {
            if (!t.ok) continue;
            if (pirates != "pirate".equals(t.kind)) continue;
            float score = (t.reward > 0 ? t.reward : 5000f) / (2f + t.ly * 1.5f);
            if (score > bestScore) { bestScore = score; best = t; }
        }
        return best;
    }

    static String status() {
        BountyRunner r = instance;
        String s = "hunt: " + state + (note.isEmpty() ? "" : " - " + note);
        if (r != null && r.target != null) s += "\n  target: " + r.target.name + " in " + r.target.where;
        if (r != null && r.huntsLeft > 0) s += "\n  hunts left after this: " + Math.max(0, r.huntsLeft - 1);
        if (!lastResult.isEmpty()) s += "\n  last: " + lastResult;
        return s;
    }

    private void begin(Target t) {
        target = t;
        creditsAtStart = pf().getCargo().getCredits().get();
        clicks = 0;
        bestDistLY = Float.MAX_VALUE;
        bestDistTs = 0L;
        recoveryShown = false;
        departChecked = false;
        report.setLength(0);
        report.append("#hunt ").append(t.name).append(" in ").append(t.where).append("\n");
        TripRunner.saveNow("hunting " + t.name); // 1.0.9: save before big actions
        String low = logisticsProblem(t, tripSupplyDays(t));
        if (low != null) {
            // 1.0.9: restock to what the departure check needs (fuel for the round trip; supplies are only quoted -
            // the player is asked before every supply purchase)
            if (Rules.b("hunt_auto_resupply", true)) { startSub(new Goals.Resupply(tripSupplyDays(t), roundTripFuel(t), false), Phase.TRAVEL, low + ": restocking first"); return; }
            pause(low + " - resupply before hunting"); return;
        }
        if (Rules.b("hunt_auto_resupply", true) && hullNeedsDock()) { startSub(new Goals.Repair(), Phase.TRAVEL, "hull damage: dockyard repair first"); return; }
        if (lowestCombatCR() < Rules.f("hunt_min_cr", 0.6f)) { setPhase(Phase.REST, "combat readiness low: resting first"); return; }
        setPhase(Phase.TRAVEL, "flying to " + t.where);
    }

    /** Supplies the fleet burns per day right now (upkeep plus CR recovery). */
    static float suppliesPerDay() {
        float perMonth = 0f;
        try {
            for (FleetMemberAPI fm : pf().getFleetData().getMembersListCopy())
                if (!fm.isMothballed()) perMonth += fm.getStats().getSuppliesPerMonth().getModifiedValue();
        } catch (Throwable ignored) {}
        // maintenance burn only: the current burn is inflated while ships recover CR (live play: 629 supplies for 85k)
        return Math.max(0.1f, perMonth / 30f);
    }

    static float supplyDays() { return pf().getCargo().getSupplies() / suppliesPerDay(); }

    /** 1.0.8: supply days a hunt needs: there and back at twice the game's ETA (storms, deep hyperspace have
     *  doubled real trips), plus CR recovery after the fight; never less than hunt_min_supply_days. */
    static float tripSupplyDays(Target t) {
        float eta = t == null ? 0f : Math.max(0f, t.eta);
        return Math.max(Rules.f("hunt_min_supply_days", 30f), eta * 2f * Rules.f("hunt_eta_factor", 2f) + Rules.f("hunt_battle_supply_days", 20f));
    }

    /** Null when supplies and fuel cover the trip; otherwise the reason not to go. */
    static String logisticsProblem(Target t, float minDays) {
        float days = supplyDays();
        if (days < minDays) return "supplies for " + Math.round(days) + " days (need " + Math.round(minDays) + ")"
                + (Goals.Resupply.lastQuote.isEmpty() ? "" : "; quote: " + Goals.Resupply.lastQuote);
        float need = roundTripFuel(t);
        if (need < 0f) return "can't work out the fuel for " + (t == null ? "the target" : t.name) + " - check by hand"; // 1.0.9: never pass silently
        if (pf().getCargo().getFuel() < need) return "fuel " + Math.round(pf().getCargo().getFuel()) + " but the round trip needs about " + Math.round(need);
        return null;
    }

    /** Fuel for there and back (20% margin, jump points), or -1 when it can't be worked out. */
    static float roundTripFuel(Target t) {
        try {
            float perLY = pf().getLogistics().getFuelCostPerLightYear();
            float ly; int j;
            if (t != null && t.fleet != null) { ly = Misc.getDistanceToPlayerLY(t.fleet); j = jumpsTo(t.fleet); }
            else if (t != null && t.ly > 0f) { ly = t.ly; j = 2; } // 1.0.9: system bounties have no fleet; use the listed distance
            else return t == null ? 0f : -1f;
            return TravelMath.fuel(ly * 1.2f, j, perLY) * 2f;  // there and back, 20% margin, jump points included (1.0.7)
        } catch (Throwable e) { return -1f; }
    }

    private void setPhase(Phase p, String why) {
        if (p == Phase.TRAVEL) {
            chaseDist = -1f; chaseTs = 0L; chaseClosing = true; lastChase = "";
            if (target != chaseTarget) { ebUsed = false; chaseTarget = target; }
        }
        phase = p;
        state = p.name().toLowerCase();
        note = why;
        phaseStartTs = sector().getClock().getTimestamp();
        log(why);
        if (p == Phase.TRAVEL && target != null && !departChecked) {
            // 1.0.8: re-check after the restock and never leave short. Live play: the market ran out of fuel, the hunt
            // left with 1,200 of ~1,774 needed and got stranded short of the target; that had to be reloaded.
            String low = logisticsProblem(target, tripSupplyDays(target));
            if (low != null) {
                phase = p; // so pause() records where to resume
                pause("NOT READY to leave for " + target.name + ": " + low + " after restocking here. Fuel: 'prices fuel' and refuel elsewhere, then 'hunt resume'."
                        + " Supplies: ask the player; after 'resupply <days>' start the hunt again ('bounties', 'hunt <n>'). Or 'hunt resume anyway' if the player decides to go short.");
                EventLog.alert("hunt", "won't depart: " + low);
                return;
            }
            departChecked = true;
        }
        if (p == Phase.TRAVEL && target != null && target.fleet != null) {
            sector().layInCourseFor(target.fleet);
            Governor.unpause();
        }
    }

    private void pause(String why) {
        if (phase != Phase.PAUSED) pausedFrom = phase;
        phase = Phase.PAUSED;
        state = "paused";
        note = why;
        log("PAUSED: " + why);
        msg("Bounty runner paused: " + why);
        EventLog.alert("hunt", "hunt paused: " + why + " ('hunt resume' / 'resume' when sorted)");
        try { Governor.modPause(); } catch (Throwable ignored) {}  // nothing drains while waiting for a decision
        writeOut();
    }

    private void stop(String why) {
        float gained = pf().getCargo().getCredits().get() - creditsAtStart;
        lastResult = (target == null ? "" : target.name + ": ") + why + (target == null ? "" : ", credits " + (gained >= 0 ? "+" : "") + Shipyard.n(gained));
        log(lastResult);
        sub = null;
        phase = Phase.IDLE;
        state = "idle";
        note = "";
        target = null;
        writeOut();
    }

    private static void log(String s) {
        String d = "";
        try { d = sector().getClock().getDateString() + "  "; } catch (Throwable ignored) {}
        report.append(d).append(s).append("\n");
        log.info("Bounty runner: " + s);
    }

    private static void msg(String s) { try { sector().getCampaignUI().addMessage(s, TripRunner.GOLD); } catch (Throwable ignored) {} }

    private static void writeOut() {
        try {
            String old = Global.getSettings().fileExistsInCommon(Files.HUNTLOG) ? Global.getSettings().readTextFileFromCommon(Files.HUNTLOG) : "";
            String all = old + report.toString();
            if (all.length() > 40000) all = all.substring(all.length() - 40000);
            Global.getSettings().writeTextFileToCommon(Files.HUNTLOG, all);
            report.setLength(0);
        } catch (Throwable t) { log.warn("Bounty runner: could not write the hunt log", t); }
    }

    // ------------------------------------------------------------------ frame

    public void advance(float amount) {
        if (phase == Phase.IDLE || phase == Phase.PAUSED) return;
        long now = System.currentTimeMillis();
        if (now < waitUntil) return;
        try { step(now); } catch (Throwable t) { log.error("Bounty runner failed", t); pause("internal error: " + t); }
    }

    private void step(long now) {
        InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
        if (pf().getBattle() != null && d == null) return; // a battle is being fought (Claude Bridge auto-battle)
        switch (phase) {
            case TRAVEL: travel(d, now); break;
            case ENGAGE: engage(d, now); break;
            case AFTER: after(d, now); break;
            case REST: rest(d, now); break;
            case RESUPPLY:
            case EXPLORE: subTick(now); break;
            default: break;
        }
    }

    private boolean targetGone() {
        if (target.person != null) return target.person.isEnded() || target.person.isEnding() || target.fleet == null || !target.fleet.isAlive();
        return target.fleet == null || !target.fleet.isAlive();
    }

    private void travel(InteractionDialogAPI d, long now) {
        if (targetGone()) { setPhase(Phase.AFTER, "target is gone"); return; }
        if (d != null) {
            if (d.getInteractionTarget() == target.fleet) { setPhase(Phase.ENGAGE, "reached " + target.name); return; }
            if (clicks++ > 60) { pause("too many dialogs on the way"); return; }
            AutoDialog.Act a = AutoDialog.handle(d, target.fleet, HUNT_CTX, target.fleet);
            if (a == null) {
                // still docked (or another market dialog): leave it and carry on
                for (AutoDialog.Opt o : AutoDialog.options(d)) {
                    if (o.enabled && !o.confirm && o.text.toLowerCase().startsWith("leave")) {
                        AutoDialog.click(d, o);
                        log("left " + (d.getInteractionTarget() == null ? "the dialog" : d.getInteractionTarget().getName()));
                        waitUntil = now + 1500L;
                        return;
                    }
                }
                pause("dialog on the way: " + AutoDialog.lastWhy);
                return;
            }
            if (!a.note.isEmpty()) log(a.note);
            waitUntil = now + 1500L;
            return;
        }
        // never strand the fleet: stop while there are still supplies to get home
        if (supplyDays() < Rules.f("hunt_abort_supply_days", 12f)) { pause("supplies down to " + Math.round(supplyDays()) + " days - go resupply"); return; }
        // re-check strength once we can see them
        float ours = ourDP();
        // 1.0.9: after the player's 'hunt <n> anyway' the margin is waived; still stop if they outgun the whole fleet
        float margin = playerOverride ? 1.0f : Rules.f("hunt_margin", 1.5f);
        if (dpOf(target.fleet) * margin > ours) { pause("target is stronger than planned: " + Math.round(dpOf(target.fleet)) + " DP vs our " + Math.round(ours)); return; }
        if (sector().getClock().getElapsedDaysSince(phaseStartTs) > 90f) { pause("not reached after 90 days"); return; }
        if (sector().isPaused()) Governor.unpause();
        // exploration detour on the way (in-system legs only)
        if (maybeExplore(target.fleet, Phase.TRAVEL)) return;
        // progress watchdog: pause if we are not getting closer for 2 days
        // in the same system light-years read 0: measure in-system distance instead (1 ly ~ 2000 su for this purpose)
        float distLY = pf().getContainingLocation() == target.fleet.getContainingLocation()
                ? Misc.getDistance(pf(), target.fleet) / 2000f : Misc.getDistanceToPlayerLY(target.fleet) + 10f;
        long nowTs = sector().getClock().getTimestamp();
        boolean movedOut = pf().getContainingLocation() != target.fleet.getContainingLocation() && !pf().isInHyperspace()
                && pf().getContainingLocation() == moveLoc
                && Misc.getDistance(pf().getLocation(), new org.lwjgl.util.vector.Vector2f(moveX, moveY)) > 200f;
        if (moveLoc != pf().getContainingLocation() || movedOut) {
            moveLoc = pf().getContainingLocation(); moveX = pf().getLocation().x; moveY = pf().getLocation().y;
            if (movedOut || bestDistTs != 0L) bestDistTs = nowTs;
        }
        boolean sameLoc = pf().getContainingLocation() == target.fleet.getContainingLocation();
        float suDist = sameLoc ? Misc.getDistance(pf(), target.fleet) : Float.MAX_VALUE;
        if (sameLoc) chase(suDist, nowTs);
        // a fleet that runs is chased longer than the plain 2-day watchdog (rule hunt_chase_days)
        float limit = sameLoc && suDist < 4000f ? Rules.f("hunt_chase_days", 8f) : 2f;
        if (distLY < bestDistLY - 0.02f || bestDistTs == 0L) { bestDistLY = distLY; bestDistTs = nowTs; }
        else if (sector().getClock().getElapsedDaysSince(bestDistTs) > limit) {
            pause("no progress toward " + target.name + " for " + Math.round(limit) + " days (" + String.format(java.util.Locale.ROOT, "%.1f", Misc.getDistanceToPlayerLY(target.fleet)) + " ly / " + Math.round(Misc.getDistance(pf(), target.fleet)) + " su away)");
            return;
        }
        // the autopilot won't route through hyperspace to a fleet in another system: head for its star system first
        SectorEntityToken dest = target.fleet;
        LocationAPI there = target.fleet.getContainingLocation();
        if (there != null && there != pf().getContainingLocation() && there instanceof com.fs.starfarer.api.campaign.StarSystemAPI) {
            com.fs.starfarer.api.campaign.StarSystemAPI sys = (com.fs.starfarer.api.campaign.StarSystemAPI) there;
            SectorEntityToken c = sys.getCenter();
            if (c != null) dest = c;
        }
        SectorEntityToken course = sector().getCampaignUI().getUltimateCourseTarget();
        if (course != dest) sector().layInCourseFor(dest);
        if (pf().getContainingLocation() == target.fleet.getContainingLocation()
                && Misc.getDistance(pf(), target.fleet) < 1500f && pf().getInteractionTarget() != target.fleet) {
            pf().setInteractionTarget(target.fleet);
        }
    }

    private void engage(InteractionDialogAPI d, long now) {
        if (d == null) {
            if (targetGone()) { setPhase(Phase.AFTER, "target destroyed"); return; }
            setPhase(Phase.TRAVEL, "lost contact, closing in again");
            return;
        }
        if (clicks++ > 80) { pause("battle dialog loop"); return; }
        DialogAgent.Result rr = DialogAgent.apply(d, DialogAgent.ctx("hunt", "engage", "pursuit", "after_battle"), target.fleet, target.fleet);
        if (rr != null) {
            if (rr.paused) { if (rr.text.toLowerCase().contains("recovery")) recoveryShown = true; pause(rr.text); return; }
            log(rr.text);
            waitUntil = now + 2000L;
            if (targetGone() && phase == Phase.ENGAGE) setPhase(Phase.AFTER, "target destroyed");
            return;
        }
        List<AutoDialog.Opt> opts = AutoDialog.options(d);
        // "take command of the action" fights the pursuit with Claude Bridge auto-battle; "order your second-in-command" opens a ship picker we can't drive
        String[] want = {"move in to engage", "engage", "pursue", "attack", "continue the battle", "go for the kill", "take command of the action"};
        for (String w : want) {
            for (AutoDialog.Opt o : opts) {
                if (o.enabled && !o.confirm && o.text.toLowerCase().startsWith(w)) {
                    AutoDialog.click(d, o);
                    log("chose: " + o.text);
                    waitUntil = now + 2000L;
                    return;
                }
            }
        }
        // after the fight the same dialog offers Continue / Leave / salvage
        if (handleAfterOptions(d, opts, now)) return;
        pause("battle dialog not understood: " + AutoDialog.describe(opts));
    }

    private boolean handleAfterOptions(InteractionDialogAPI d, List<AutoDialog.Opt> opts, long now) {
        // the player wants to look at recoverable ships after every fight: stop here (the game pauses) instead of clicking past it
        if (Rules.b("hunt_recovery_pause", true) && !recoveryShown) {
            for (AutoDialog.Opt o : opts) {
                if (o.enabled && o.text.toLowerCase().startsWith("consider ship recovery")) {
                    recoveryShown = true;
                    pause("ship recovery available - check the wrecks, then 'hunt resume'");
                    return true;
                }
            }
        }
        String[] order = {"continue", "leave", "let them go", "disengage"};
        for (String w : order) {
            for (AutoDialog.Opt o : opts) {
                if (o.enabled && !o.confirm && o.text.toLowerCase().startsWith(w)) {
                    AutoDialog.click(d, o);
                    log("chose: " + o.text);
                    waitUntil = now + 1500L;
                    if (targetGone() && phase == Phase.ENGAGE) setPhase(Phase.AFTER, "target destroyed");
                    return true;
                }
            }
        }
        return false;
    }

    private void after(InteractionDialogAPI d, long now) {
        if (d != null) {
            if (clicks++ > 120) { pause("post-battle dialog loop"); return; }
            List<AutoDialog.Opt> opts = AutoDialog.options(d);
            if (handleAfterOptions(d, opts, now)) return;
            AutoDialog.Act a = AutoDialog.handle(d, null, DialogAgent.ctx("after_battle", "hunt"), null);
            if (a == null) { EventLog.alert("dialog", "post-battle dialog not covered\n" + DialogAgent.describe(d, DialogAgent.ctx("after_battle", "hunt"), null, null)); pause("post-battle dialog: " + AutoDialog.lastWhy); return; }
            if (!a.note.isEmpty()) log(a.note);
            waitUntil = now + 1500L;
            return;
        }
        String res = target.person != null && target.person.getResult() != null ? String.valueOf(target.person.getResult().type) : "fleet gone";
        // 1.0.8: say plainly whether the bounty paid, and alert when it didn't
        if (target.person != null) {
            String r = resultText(target.person);
            if (target.person.getResult() != null && target.person.getResult().type == PersonBountyIntel.BountyResultType.END_PLAYER_BOUNTY)
                EventLog.info("bounty", "PAID: " + r);
            else EventLog.alert("bounty", "NOT PAID: " + r);
            res = r;
        }
        log("bounty result: " + res + "; combat readiness " + Math.round(lowestCombatCR() * 100f) + "%");
        huntsLeft--;
        if (lowestCombatCR() < Rules.f("hunt_rest_cr", 0.7f)) { setPhase(Phase.REST, "resting to repair"); return; }
        next("done (" + res + ")");
    }

    private void next(String why) {
        if (huntsLeft > 0) {
            Target t = best(targets());
            if (t != null) {
                if (Rules.b("hunt_auto_resupply", true) && supplyDays() < Rules.f("hunt_min_supply_days", 30f)) {
                    target = t;
                    startSub(new Goals.Resupply(tripSupplyDays(t), roundTripFuel(t), false), Phase.IDLE, "between targets: restock (" + Math.round(supplyDays()) + " days left)");
                    return;
                }
                if (Rules.b("hunt_auto_resupply", true) && hullNeedsDock()) {
                    target = t;
                    startSub(new Goals.Repair(), Phase.IDLE, "between targets: dockyard repair");
                    return;
                }
                begin(t); return;
            }
            why += "; no further bounty passes the rules";
        }
        stop(why);
    }

    /** Waits near the nearest friendly market (game running, undocked) until combat readiness recovers. */
    private void rest(InteractionDialogAPI d, long now) {
        if (d != null) {
            AutoDialog.Act a = AutoDialog.handle(d, restAt, DialogAgent.ctx("idle", "travel"), null);
            if (a == null) {
                // docked dialogs pause time: leave them
                for (AutoDialog.Opt o : AutoDialog.options(d)) {
                    if (o.text.toLowerCase().startsWith("leave")) { AutoDialog.click(d, o); waitUntil = now + 1500L; return; }
                }
                pause("dialog while resting: " + AutoDialog.lastWhy);
                return;
            }
            waitUntil = now + 1500L;
            return;
        }
        if (restAt == null) {
            MarketAPI best = null;
            float bd = Float.MAX_VALUE;
            for (MarketAPI m : sector().getEconomy().getMarketsCopy()) {
                if (m.isHidden() || m.getFaction().isHostileTo(pf().getFaction()) || m.getPrimaryEntity() == null) continue;
                float dist = Misc.getDistanceToPlayerLY(m.getPrimaryEntity());
                if (dist < bd) { bd = dist; best = m; }
            }
            if (best == null) { pause("no friendly market to rest at"); return; }
            restAt = best.getPrimaryEntity();
            log("resting near " + best.getName());
            sector().layInCourseFor(restAt);
        }
        if (sector().isPaused()) Governor.unpause();
        // 1.0.9: no exploration detours while resting - repairs need the supplies and salvage spends them (Nov 1)
        if (lowestCombatCR() >= Rules.f("hunt_rest_cr", 0.7f)) {
            restAt = null;
            log("combat readiness " + Math.round(lowestCombatCR() * 100f) + "%: ready");
            if (target != null && !targetGone() && phase == Phase.REST && huntsLeft > 0 && target.ok) { setPhase(Phase.TRAVEL, "flying to " + target.where); return; }
            next("rested");
            return;
        }
        if (sector().getClock().getElapsedDaysSince(phaseStartTs) > 120f) pause("still not repaired after 120 days");
    }

    // ------------------------------------------------------------------ sub-goals (1.0 Captain)

    static boolean hullNeedsDock() {
        float min = Rules.f("repair_hull_pct", 90f) / 100f * 0.6f; // only real damage sends a hunt to the dockyard
        for (FleetMemberAPI m : pf().getFleetData().getMembersListCopy())
            if (!m.isMothballed() && !Shipyard.civilian(m) && m.getStatus().getHullFraction() < min) return true;
        return false;
    }

    private void startSub(Goals.Goal g, Phase then, String why) {
        sub = g;
        afterSub = then;
        phase = g instanceof Goals.Visit ? Phase.EXPLORE : Phase.RESUPPLY;
        state = phase.name().toLowerCase();
        note = why;
        phaseStartTs = sector().getClock().getTimestamp();
        log(why);
        EventLog.info("hunt", why);
    }

    private void subTick(long now) {
        if (sub == null) { setPhase(Phase.TRAVEL, "back to the hunt"); return; }
        if (sub.running()) { sub.tick(now); note = sub.line(); }
        if ("paused".equals(sub.state)) { pause(sub.title + ": " + sub.result); return; }
        if (!sub.finished()) return;
        Goals.Goal g = sub;
        sub = null;
        EventLog.add("done".equals(g.state) ? EventCore.GOAL : EventCore.WARN, "hunt", g.title + " " + g.state + ": " + g.result);
        if (g instanceof Goals.Visit) {
            if (afterSub == Phase.REST) { phase = Phase.REST; state = "rest"; note = "resting"; return; }
            setPhase(Phase.TRAVEL, "back on the way to " + (target == null ? "?" : target.name));
            return;
        }
        if ("failed".equals(g.state) && supplyDays() < Rules.f("hunt_abort_supply_days", 12f)) { pause(g.title + " failed: " + g.result); return; }
        if (afterSub == Phase.IDLE) {
            if (target != null && !targetGone()) { Target t = target; begin(t); }
            else next("resupplied");
            return;
        }
        if (target == null || targetGone()) { next("target gone while resupplying"); return; }
        setPhase(Phase.TRAVEL, "flying to " + target.where);
    }

    /** In-system detour to salvage/surveys within the explore_* rules. True when a detour started. */
    private boolean maybeExplore(SectorEntityToken dest, Phase back) {
        try {
            if (!Rules.b("explore", true)) return false;
            LocationAPI here = pf().getContainingLocation();
            if (here == null || here.isHyperspace()) return false;
            if (here != exploredLoc) { exploredLoc = here; exploredHere = 0; }
            if (exploredHere >= (int) Rules.f("explore_max_sites", 3f)) return false;
            ExploreMath.Site s = Explorer.next(dest, triedSites);
            if (s == null) return false;
            triedSites.add(s.id);
            exploredHere++;
            startSub(new Goals.Visit(s), back, "detour: " + s.name + " [" + s.kind + "], " + Math.round(s.dist) + " su, defended " + s.defended);
            return true;
        } catch (Throwable t) { log.warn("Claude Captain: explore check failed", t); return false; }
    }

    /** 1.0.4: abilities for closing on a target in this system (see ChaseMath). */
    private void chase(float dist, long nowTs) {
        try {
            CampaignFleetAPI pf = pf();
            if (chaseTs == 0L || sector().getClock().getElapsedDaysSince(chaseTs) >= 0.25f) {
                chaseClosing = chaseDist < 0f || dist < chaseDist - 30f;
                chaseDist = dist;
                chaseTs = nowTs;
            }
            com.fs.starfarer.api.characters.AbilityPlugin ip = pf.getAbility("interdiction_pulse");
            com.fs.starfarer.api.characters.AbilityPlugin sb = pf.getAbility("sustained_burn");
            com.fs.starfarer.api.characters.AbilityPlugin eb = pf.getAbility("emergency_burn");
            float range = com.fs.starfarer.api.impl.campaign.abilities.InterdictionPulseAbility.getRange(pf);
            String act = ChaseMath.decide(dist, range,
                    ip != null && ip.isUsable(), ip != null && ip.isActive(),
                    sb != null && sb.isUsable(), sb != null && sb.isActive(),
                    eb != null && eb.isUsable(), eb != null && eb.isActive(),
                    chaseClosing, ebUsed);
            if (ChaseMath.PULSE.equals(act)) ip.activate();
            else if (ChaseMath.SB_ON.equals(act)) sb.activate();
            else if (ChaseMath.SB_OFF.equals(act)) sb.deactivate();
            else if (ChaseMath.EB.equals(act)) { eb.activate(); ebUsed = true; }
            if (!ChaseMath.NONE.equals(act) && !act.equals(lastChase)) {
                log("chase: " + act + " at " + Math.round(dist) + " su (pulse range " + Math.round(range) + ")");
                EventLog.info("hunt", "chase " + target.name + ": " + act + " at " + Math.round(dist) + " su");
            }
            if (!ChaseMath.NONE.equals(act)) lastChase = act;
        } catch (Throwable t) { log.warn("Claude Captain: chase step failed", t); }
    }
}
