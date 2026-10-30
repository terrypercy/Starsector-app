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
        float reward, theirDP, ly, daysLeft;
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
    private boolean recoveryShown = false;
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
        return dp;
    }

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
        if (ours <= 0f) { t.ok = false; t.why = "no combat ships ready"; return; }
        if (t.theirDP * margin > ours) { t.ok = false; t.why = "too strong: " + Math.round(t.theirDP) + " DP vs our " + Math.round(ours); return; }
        if (t.ly > maxLy) { t.ok = false; t.why = "too far: " + String.format("%.1f", t.ly) + " ly"; return; }
        t.ok = true;
    }

    static CampaignFleetAPI weakestHostileIn(LocationAPI loc, float maxDP) {
        CampaignFleetAPI best = null;
        float bestDP = Float.MAX_VALUE;
        if (loc == null) return null;
        for (CampaignFleetAPI f : loc.getFleets()) {
            if (f == pf() || !f.isAlive() || !f.isHostileTo(pf()) || f.isStationMode()) continue;
            if (!"pirates".equals(f.getFaction().getId()) && !f.getFaction().isHostileTo(pf().getFaction())) continue;
            float dp = dpOf(f);
            if (dp <= 0f || dp > maxDP) continue;
            if (dp < bestDP) { bestDP = dp; best = f; }
        }
        return best;
    }

    static String list() {
        List<Target> ts = targets();
        StringBuilder b = new StringBuilder("bounties (our combat strength " + Math.round(ourDP()) + " DP, lowest CR "
                + Math.round(lowestCombatCR() * 100f) + "%, margin " + Rules.f("hunt_margin", 1.5f) + "x)\n");
        int i = 0;
        for (Target t : ts) {
            i++;
            b.append(i).append(". ").append(t.kind.equals("person") ? "" : "[system] ").append(t.name).append(" | ").append(t.where)
             .append(" | ").append(String.format("%.1f", t.ly)).append(" ly");
            if (t.reward > 0) b.append(" | ").append(Shipyard.n(t.reward)).append(" cr");
            if (t.daysLeft > 0) b.append(" | ").append(Math.round(t.daysLeft)).append(" days left");
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
            pick = best(ts);
            if (pick == null) { instance.huntsLeft = 0; return "no bounty passes the rules now\n" + list(); }
        } else {
            int n;
            try { n = Integer.parseInt(a); } catch (NumberFormatException e) { return "usage: hunt <n> | hunt auto [count] | hunt stop | hunt status"; }
            if (n < 1 || n > ts.size()) return "no bounty " + n + " (see 'bounties')";
            pick = ts.get(n - 1);
            if (!pick.ok) return "bounty " + n + " fails the rules: " + pick.why;
            instance.huntsLeft = 1;
        }
        instance.begin(pick);
        return "hunting " + pick.name + " in " + pick.where + " (" + Math.round(pick.theirDP) + " DP vs our " + Math.round(ourDP()) + ")";
    }

    static Target best(List<Target> ts) {
        Target best = null;
        float bestScore = 0f;
        for (Target t : ts) {
            if (!t.ok) continue;
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
        report.setLength(0);
        report.append("#hunt ").append(t.name).append(" in ").append(t.where).append("\n");
        String low = logisticsProblem(t, Rules.f("hunt_min_supply_days", 30f));
        if (low != null) {
            if (Rules.b("hunt_auto_resupply", true)) { startSub(new Goals.Resupply(Rules.f("resupply_days", 60f)), Phase.TRAVEL, low + ": resupplying first"); return; }
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

    /** Null when supplies and fuel cover the trip; otherwise the reason not to go. */
    static String logisticsProblem(Target t, float minDays) {
        float days = supplyDays();
        if (days < minDays) return "supplies for " + Math.round(days) + " days (need " + Math.round(minDays) + ")";
        try {
            float perLY = pf().getLogistics().getFuelCostPerLightYear();
            float ly = t == null || t.fleet == null ? 0f : Misc.getDistanceToPlayerLY(t.fleet);
            float need = perLY * ly * 2f * 1.2f;  // there and back, 20% margin
            if (pf().getCargo().getFuel() < need) return "fuel " + Math.round(pf().getCargo().getFuel()) + " but the round trip needs about " + Math.round(need);
        } catch (Throwable ignored) {}
        return null;
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
        try { sector().setPaused(true); } catch (Throwable ignored) {}  // nothing drains while waiting for a decision
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
            log(a.note);
            waitUntil = now + 1500L;
            return;
        }
        // never strand the fleet: stop while there are still supplies to get home
        if (supplyDays() < Rules.f("hunt_abort_supply_days", 12f)) { pause("supplies down to " + Math.round(supplyDays()) + " days - go resupply"); return; }
        // re-check strength once we can see them
        float ours = ourDP();
        if (dpOf(target.fleet) * Rules.f("hunt_margin", 1.5f) > ours) { pause("target is stronger than planned: " + Math.round(dpOf(target.fleet)) + " DP vs our " + Math.round(ours)); return; }
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
            log(a.note);
            waitUntil = now + 1500L;
            return;
        }
        String res = target.person != null && target.person.getResult() != null ? String.valueOf(target.person.getResult().type) : "fleet gone";
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
                    startSub(new Goals.Resupply(Rules.f("resupply_days", 60f)), Phase.IDLE, "between targets: resupply (" + Math.round(supplyDays()) + " days left)");
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
        if (maybeExplore(restAt, Phase.REST)) return;
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
