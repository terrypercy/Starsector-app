package claudecaptain;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.AbilityPlugin;
import com.fs.starfarer.api.impl.campaign.abilities.TransponderAbility;
import com.fs.starfarer.api.impl.campaign.ids.Abilities;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one planned trip, step by step: (save) -> fly to the buy market -> dock ->
 * buy -> leave -> fly to the sell market -> dock -> sell -> done. It sets the
 * transponder for each stop when the fleet enters that stop's system, re-checks
 * prices at the counter before buying, and pauses (never guesses) when something
 * needs the player: a dialog or battle, a changed course, prices that moved, a
 * transponder change that would reveal the fleet.
 *
 * Also reads claude_trade/inbox.txt so Claude can ask for a self-test, a fresh plan,
 * or run a trip the player approved in chat.
 */
public class TripRunner implements EveryFrameScript {

    private static final Logger log = Global.getLogger(TripRunner.class);
    static final Color BLUE = new Color(120, 200, 255);
    static final Color GOLD = new Color(240, 200, 70);
    static final Color RED = new Color(230, 100, 90);

    enum Kind { LEAVE_FIRST, TRAVEL, DOCK, BUY, REFUEL, SELL, REFILL, LEAVE, FINISH }

    static final class Step {
        final Kind kind;
        final Planner.Stop stop;
        Step(Kind k, Planner.Stop s) { kind = k; stop = s; }
    }

    // ---------------------------------------------------------------- shared state (panel, inbox)
    public static volatile String state = "idle"; // idle | running | paused
    public static volatile Planner.Trip trip;
    public static volatile String stepText = "";
    public static volatile int stepIndex, stepTotal;
    public static volatile String pauseReason;
    public static volatile String lastSummary;
    public static volatile String transponderNote = "";
    public static final List<String> recent = new ArrayList<String>();

    private static TripRunner instance;

    public static void install() {
        instance = new TripRunner();
        Global.getSector().addTransientScript(instance);
        state = "idle"; trip = null; pauseReason = null; stepText = "";
        synchronized (recent) { recent.clear(); }
        loopLeft = 0; loopTotal = 0; pendingNext = false;
    }

    public static boolean busy() { return !"idle".equals(state); }

    // ---------------------------------------------------------------- run state
    private final long created = System.currentTimeMillis();
    private long lastInbox = 0L;
    private List<Step> steps = new ArrayList<Step>();
    private int idx = 0;
    private boolean entered = false;
    private float cap = 0f;
    private Planner.Fleet fleetAtStart;
    private float[] boughtQty = new float[0];
    private float[] paidFor = new float[0];
    private float spent = 0f, earned = 0f;
    /** Fuel and supplies bought on this trip (1.1.5): kept apart from the goods so the trade result stays comparable to the plan. */
    private float refillCost = 0f, fuelBought = 0f, supBought = 0f;
    private boolean turnedOff = false, wentDark = false, sellAnyway = false, fuelAnyway = false, transponderApplied = false;
    private SectorEntityToken target;
    private long stepStartTs = 0L, progressStamp = 0L;
    private float progressDist = Float.MAX_VALUE;
    private int stalls = 0, relays = 0, leaveTries = 0;
    private String runId = "";
    private long waitUntil = 0L, disengagedAt = 0L;
    private int autoClicks = 0, membersAtStart = 0;
    private boolean keptCargo = false, unpauseAfterDialog = false;
    private long pausedSince = 0L;

    // ---------------------------------------------------------------- loop mode (inbox: loop <n> | loop stop)
    public static volatile int loopTotal = 0, loopLeft = 0, loopDone = 0, lossStreak = 0;
    public static volatile float loopNet = 0f;
    private static volatile boolean pendingNext = false;
    private static volatile long nextAt = 0L;
    private static final List<String> recentSells = new ArrayList<String>();
    private static final java.util.Set<String> failedKeys = new java.util.HashSet<String>();
    private static final StringBuilder loopLog = new StringBuilder();
    private boolean thisIsLoopTrip = false;
    private final StringBuilder report = new StringBuilder();

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return true; }

    private static SectorAPI sector() { return Global.getSector(); }
    private static CampaignFleetAPI pf() { return sector().getPlayerFleet(); }

    // ---------------------------------------------------------------- control (panel / inbox)

    /** Starts a trip. cap > 0 limits the buy order to that many credits (used for small test trips). */
    public static String start(Planner.Trip t, float cap, String source) {
        if (instance == null) return "not ready (no campaign loaded)";
        if (busy()) return "a trip is already " + state + "; cancel it first";
        if (t == null) return "no such trip";
        return instance.begin(t, cap, source);
    }

    public static String cancel(String why) {
        boolean looping = loopLeft > 0 || pendingNext;
        loopLeft = 0; pendingNext = false;
        if (instance == null || !busy()) return looping ? "loop stopped" : "no trip running";
        instance.stop("cancelled" + (why == null ? "" : " (" + why + ")"), false);
        return "trip cancelled" + (looping ? ", loop stopped" : "");
    }

    public static String resume() {
        if (instance == null || !"paused".equals(state)) return "nothing to resume";
        state = "running";
        pauseReason = null;
        instance.entered = false;
        instance.transponderApplied = false; // re-decide with what's around now
        instance.jumpTries = 0;
        instance.autoClicks = 0;
        instance.waitUntil = 0L;
        try { instance.membersAtStart = pf().getFleetData().getNumMembers(); } catch (Throwable ignored) {}
        instance.sellAnyway = true; // a paused sell resumed by the player means: sell anyway
        instance.fuelAnyway = true; // ...and a paused refuel: fly on anyway
        instance.note("resumed by the player");
        return "resumed";
    }

    private String begin(Planner.Trip t, float capCredits, String source) {
        trip = t;
        cap = capCredits;
        fleetAtStart = Plans.snap == null ? new Planner.Fleet() : Plans.snap.fleet;
        boughtQty = new float[t.lines.size()];
        paidFor = new float[t.lines.size()];
        if (t.sellOnly) for (int i = 0; i < t.lines.size(); i++) boughtQty[i] = t.lines.get(i).qty;
        spent = 0f; earned = 0f; refillCost = 0f; fuelBought = 0f; supBought = 0f;
        turnedOff = false; wentDark = false; sellAnyway = false; fuelAnyway = false;
        keptCargo = false; autoClicks = 0; waitUntil = 0L; disengagedAt = 0L;
        thisIsLoopTrip = source != null && source.startsWith("loop");
        try { membersAtStart = pf().getFleetData().getNumMembers(); } catch (Throwable ignored) {}
        runId = "trip-" + System.currentTimeMillis();
        Trader.beginStop();
        report.setLength(0);
        synchronized (recent) { recent.clear(); }
        steps = new ArrayList<Step>();
        MarketAPI docked = GameReader.dockedMarket();
        InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
        String first = t.sellOnly ? t.to.id : t.from.id;
        if (d != null && (docked == null || !docked.getId().equals(first))) steps.add(new Step(Kind.LEAVE_FIRST, null));
        if (t.goOnly) {
            if (docked == null || !docked.getId().equals(t.to.id)) {
                steps.add(new Step(Kind.TRAVEL, t.sellStop));
                steps.add(new Step(Kind.DOCK, t.sellStop));
            }
            if (t.refuel) steps.add(new Step(Kind.REFILL, t.sellStop));
        } else if (!t.sellOnly) {
            if (docked == null || !docked.getId().equals(t.from.id)) {
                steps.add(new Step(Kind.TRAVEL, t.buyStop));
                steps.add(new Step(Kind.DOCK, t.buyStop));
            }
            steps.add(new Step(Kind.BUY, t.buyStop));
            if (t.refuel) steps.add(new Step(Kind.REFUEL, t.buyStop));
            steps.add(new Step(Kind.LEAVE, t.buyStop));
            steps.add(new Step(Kind.TRAVEL, t.sellStop));
            steps.add(new Step(Kind.DOCK, t.sellStop));
        } else if (docked == null || !docked.getId().equals(t.to.id)) {
            steps.add(new Step(Kind.TRAVEL, t.sellStop));
            steps.add(new Step(Kind.DOCK, t.sellStop));
        }
        if (!t.goOnly) {
            steps.add(new Step(Kind.SELL, t.sellStop));
            steps.add(new Step(Kind.REFILL, t.sellStop)); // 1.1.5: leave every sell market with fuel and supplies for the next trip
        }
        steps.add(new Step(Kind.FINISH, null));
        idx = 0;
        entered = false;
        stepTotal = steps.size();
        stepIndex = 0;
        state = "running";
        pauseReason = null;
        lastSummary = null;
        transponderNote = "";

        report.append("#run ").append(runId).append(" from ").append(source).append("\n");
        try { report.append("#date ").append(sector().getClock().getDateString()).append("\n"); } catch (Throwable ignored) {}
        report.append("trip: ").append(t.title()).append("\n");
        for (String l : t.details()) report.append("  plan ").append(l).append("\n");
        if (cap > 0f) report.append("  spend cap: ").append(Planner.fmt(cap)).append(" cr\n");

        save();
        msg("Trade Runner: " + t.title(), BLUE);
        note("started: " + t.title() + (cap > 0f ? " (spend cap " + Planner.fmt(cap) + " cr)" : ""));
        return "started " + t.title();
    }

    /** 1.0.9: save before a big action (hunt, ship purchase or sale) the same way trips do. */
    public static void saveNow(String why) {
        String mode = Rules.s("save_before_run", "auto").toLowerCase();
        if (mode.equals("off")) return;
        boolean saved = false;
        try {
            CampaignUIAPI ui = sector().getCampaignUI();
            if (mode.equals("main")) saved = ui.cmdSave(); else { ui.autosave(); saved = true; }
        } catch (Throwable t) { log.warn("Claude Captain: save failed", t); }
        EventLog.info("save", (saved ? "saved before " : "could not save before ") + why);
    }

    private void save() {
        String mode = Rules.s("save_before_run", "auto").toLowerCase();
        if (mode.equals("off")) return;
        boolean saved = false;
        try {
            CampaignUIAPI ui = sector().getCampaignUI();
            if (mode.equals("main")) saved = ui.cmdSave(); else { ui.autosave(); saved = true; }
        } catch (Throwable t) { log.warn("Trade Runner: save failed", t); }
        note(saved ? (mode.equals("main") ? "game saved" : "autosave requested (the game skips it while a dialog is open)") : "could not save - save by hand when you can");
    }

    // ---------------------------------------------------------------- frame

    public void advance(float amount) {
        long now = System.currentTimeMillis();
        if (now - created < 3000L) return;
        if (now - lastInbox > 500L) {
            lastInbox = now;
        }
        if ("idle".equals(state) && pendingNext && now >= nextAt) {
            pendingNext = false;
            try { nextLoopTrip(); } catch (Throwable t) { log.error("Trade Runner: loop failed", t); endLoop("internal error: " + t); }
        }
        if (!"running".equals(state)) return;
        try {
            step();
        } catch (Throwable t) {
            log.error("Trade Runner: trip step failed", t);
            pause("internal error: " + t);
        }
    }

    private void step() {
        if (idx >= steps.size()) { stop("done", true); return; }
        Step s = steps.get(idx);
        stepIndex = idx + 1;
        CampaignFleetAPI pf = pf();
        InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
        MarketAPI m = s.stop == null ? null : sector().getEconomy().getMarket(s.stop.node.id);
        if (s.stop != null && m == null) { pause(s.stop.node.name + " no longer exists"); return; }
        if (!entered) {
            entered = true;
            stepStartTs = sector().getClock().getTimestamp();
            progressStamp = stepStartTs;
            progressDist = Float.MAX_VALUE;
            stalls = 0; relays = 0; leaveTries = 0; jumpTries = 0;
            if (s.kind == Kind.TRAVEL || s.kind == Kind.DOCK) target = m.getPrimaryEntity();
            if (!enter(s, m, d)) return;
        }
        switch (s.kind) {
            case LEAVE_FIRST:
            case LEAVE: tickLeave(d); break;
            case TRAVEL: tickTravel(s, m, d, pf); break;
            case DOCK: tickDock(s, m, d, pf); break;
            default: next(); // BUY, SELL and FINISH finish in enter()
        }
    }

    private void next() {
        idx++;
        entered = false;
    }

    /** First frame of a step. Returns false if the step already moved on or paused. */
    private boolean enter(Step s, MarketAPI m, InteractionDialogAPI d) {
        switch (s.kind) {
            case LEAVE_FIRST:
                stepText = "leaving the current dialog";
                return true;
            case TRAVEL: {
                stepText = "flying to " + m.getName() + " (" + s.stop.node.systemName + ")";
                transponderApplied = false;
                CampaignFleetAPI pf = pf();
                if (pf.getContainingLocation() == target.getContainingLocation()) applyTransponder(s.stop, m);
                if (!"running".equals(state)) return false;
                // 1.0.8: never set off without the fuel to arrive with a margin (20% + 1 ly); the hunt checks the round trip
                try {
                    float ly = Misc.getDistanceToPlayerLY(target);
                    float perLY = Math.max(0.1f, pf.getLogistics().getFuelCostPerLightYear());
                    float need = TravelMath.fuel(ly * 1.2f + 1f, 2, perLY);
                    float bare = TravelMath.fuel(ly, 1, perLY);
                    boolean sellsFuel = Goals.sells(m, "fuel");
                    // 1.0.9: no deadlock - going to a fuel market we can reach, or resuming mid-flight, only warns
                    if (ly > 0.5f && pf.getCargo().getFuel() < need && !fuelAnyway) {
                        if ((sellsFuel && pf.getCargo().getFuel() >= bare) || pf.isInHyperspace()) {
                            EventLog.warn("trip", "fuel " + Math.round(pf.getCargo().getFuel()) + " is under the safe " + Math.round(need) + " for " + m.getName()
                                    + (pf.isInHyperspace() ? " (already under way)" : " - going there to refuel"));
                        } else {
                            pause("NOT READY: fuel " + Math.round(pf.getCargo().getFuel()) + " but reaching " + m.getName() + " (" + String.format("%.1f", ly) + " ly) needs about " + Math.round(need) + " - refuel first ('prices fuel'), or 'resume' to go anyway");
                            return false;
                        }
                    }
                } catch (Throwable ignored) {}
                try {
                    AbilityPlugin burn = pf.getAbility(Abilities.SUSTAINED_BURN);
                    AbilityPlugin dark = pf.getAbility(Abilities.GO_DARK);
                    boolean far = pf.getContainingLocation() != target.getContainingLocation() || Misc.getDistance(pf, target) > 3000f;
                    if (far && burn != null && !burn.isActive() && burn.isUsable() && (dark == null || !dark.isActive())) burn.activate();
                } catch (Throwable ignored) {}
                sector().layInCourseFor(target);
                Governor.unpause();
                note("course laid in for " + m.getName() + " (" + String.format("%.1f", Misc.getDistanceToPlayerLY(target)) + " ly)");
                return true;
            }
            case DOCK: {
                stepText = "docking at " + m.getName();
                if (d != null && d.getInteractionTarget() == target) { docked(m); return false; }
                if (!transponderApplied) applyTransponder(s.stop, m);
                if (!"running".equals(state)) return false;
                if (settled(s.stop)) pf().setInteractionTarget(target); // else tickDock sets it once the switch is done
                sector().layInCourseFor(target);
                Governor.unpause();
                return true;
            }
            case BUY:
                stepText = "buying at " + m.getName();
                doBuy(s, m, d);
                return false;
            case SELL:
                stepText = "selling at " + m.getName();
                doSell(s, m, d);
                return false;
            case REFUEL:
                stepText = "refuelling at " + m.getName();
                doRefuel(m, d);
                return false;
            case REFILL:
                stepText = "refilling fuel and supplies at " + m.getName();
                doRefill(m, d);
                return false;
            case FINISH:
                finish();
                return false;
            case LEAVE:
                stepText = "leaving " + m.getName();
                return true;
        }
        return true;
    }

    // ---------------------------------------------------------------- travel & dock

    private int jumpTries = 0;

    /**
     * The autopilot stops at jump-points that need an order (and fuel) to use. On the planned route that's routine:
     * order the jump, as clicking it would. Anything unusual (several destinations, the transponder-off warning,
     * not enough fuel) is left to the player.
     */
    private boolean handleJumpPoint(InteractionDialogAPI d) {
        if (d == null || !(d.getInteractionTarget() instanceof com.fs.starfarer.api.campaign.JumpPointAPI)) return false;
        if (!(d.getPlugin() instanceof com.fs.starfarer.api.impl.campaign.JumpPointInteractionDialogPluginImpl)) return false;
        if (jumpTries++ > 3) return false;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Enum> ids = (Class<Enum>) Class.forName("com.fs.starfarer.api.impl.campaign.JumpPointInteractionDialogPluginImpl$OptionId");
            Object j1 = Enum.valueOf(ids, "JUMP_1"), j2 = Enum.valueOf(ids, "JUMP_2");
            com.fs.starfarer.api.campaign.OptionPanelAPI op = d.getOptionPanel();
            if (op == null || !op.hasOption(j1) || op.hasOption(j2)) return false;
            float cost = Math.max(1f, Math.round(pf().getLogistics().getFuelCostPerLightYear()));
            if (pf().getCargo().getFuel() < cost) return false;
            d.getPlugin().optionSelected("Order the jump", j1);
            note("used " + d.getInteractionTarget().getName() + " on the route");
            return true;
        } catch (Throwable t) {
            log.warn("Trade Runner: jump-point order failed", t);
            return false;
        }
    }

    private boolean interrupted(InteractionDialogAPI d, CampaignFleetAPI pf) {
        long now = System.currentTimeMillis();
        if (now < waitUntil) return true;
        boolean auto = Rules.b("auto_dialogs", true);
        if (pf.getBattle() != null) {
            if (disengagedAt > 0L && now - disengagedAt < 900000L) return true; // our own disengage is playing out
            if (auto && d != null && d.getInteractionTarget() != target) { /* fall through: the dialog may offer a way out */ }
            else { pause("a battle started - fight or leave it, then click Resume"); return true; }
        }
        if (auto && d != null && d.getInteractionTarget() != target) {
            if (autoClicks < 30) {
                AutoDialog.Act a = AutoDialog.handle(d, target, DialogAgent.ctx("trade", "travel"), null);
                if (a != null) {
                    autoClicks++; relays = 0; stalls = 0;
                    progressStamp = sector().getClock().getTimestamp(); progressDist = Float.MAX_VALUE;
                    if (a.disengage) disengagedAt = now;
                    unpauseAfterDialog = true;
                    if (!a.note.isEmpty()) note(a.note);
                    waitUntil = now + 1500L;
                    return true;
                }
                if (handleJumpPoint(d)) { waitUntil = now + 1500L; unpauseAfterDialog = true; return true; } // 1.0 jump order as a fallback
                pause("interrupted: " + AutoDialog.lastWhy + " - deal with it, then click Resume");
            } else pause("too many dialogs on this leg (" + autoClicks + ") - check the game, then click Resume");
            return true;
        }
        if (d == null) {
            disengagedAt = 0L;
            if (lostShips(pf)) return true;
            if (unpauseAfterDialog) {
                unpauseAfterDialog = false;
                // a dialog we answered can leave the game paused; the trip would sit still forever
                Governor.unpause();
                if (target != null && sector().getCampaignUI().getUltimateCourseTarget() == null) sector().layInCourseFor(target);
            }
        }
        if (d != null && d.getInteractionTarget() != target && handleJumpPoint(d)) return true;
        if (d != null && d.getInteractionTarget() != target) {
            String who = d.getInteractionTarget() == null ? "a dialog" : d.getInteractionTarget().getName();
            pause("interrupted: " + who + " opened a dialog - deal with it, then click Resume");
            return true;
        }
        return false;
    }

    /** A running trip with the game paused and no dialog open for 15 s: unpause (rule keep_moving). */
    private void keepMoving(InteractionDialogAPI d) {
        if (d != null || !sector().isPaused() || !Rules.b("keep_moving", true)) { pausedSince = 0L; return; }
        long now = System.currentTimeMillis();
        if (pausedSince == 0L) { pausedSince = now; return; }
        if (now - pausedSince > 15000L) {
            pausedSince = 0L;
            Governor.unpause();
            if (target != null && sector().getCampaignUI().getUltimateCourseTarget() == null) sector().layInCourseFor(target);
            note("game was paused mid-trip: unpaused (keep_moving = true)");
        }
    }

    private void tickTravel(Step s, MarketAPI m, InteractionDialogAPI d, CampaignFleetAPI pf) {
        if (d != null && d.getInteractionTarget() == target) { next(); return; } // docked early: DOCK sees it
        if (interrupted(d, pf)) return;
        keepMoving(d);
        boolean sameLoc = pf.getContainingLocation() == target.getContainingLocation();
        if (sameLoc && !transponderApplied) {
            applyTransponder(s.stop, m);
            if (!"running".equals(state)) return;
            sector().layInCourseFor(target); // toggling abilities can drop the autopilot
        }
        float dist = Misc.getDistance(pf, target) - target.getRadius() - pf.getRadius();
        if (sameLoc && dist <= 1500f) { note("reached " + m.getName()); next(); return; }
        if (!progress(sameLoc, dist)) return;
        SectorEntityToken course = sector().getCampaignUI().getUltimateCourseTarget();
        if (course == null) {
            if (++relays > 4) { pause("the fleet stopped short of " + m.getName() + " - click Resume to try again"); return; }
            sector().layInCourseFor(target);
        } else if (course != target) {
            pause("you set a new course - the trip is paused (Resume flies on to " + m.getName() + ")");
        }
    }

    private void tickDock(Step s, MarketAPI m, InteractionDialogAPI d, CampaignFleetAPI pf) {
        if (d != null && d.getInteractionTarget() == target) { docked(m); return; }
        if (interrupted(d, pf)) return;
        keepMoving(d);
        float dist = Misc.getDistance(pf, target) - target.getRadius() - pf.getRadius();
        if (!progress(pf.getContainingLocation() == target.getContainingLocation(), dist)) return;
        // dock only once the transponder has finished switching (it takes a moment), or the port sees the old setting
        if (pf.getInteractionTarget() != target && !settled(s.stop) && sector().getClock().getElapsedDaysSince(stepStartTs) < 1f) return;
        if (pf.getInteractionTarget() != target) {
            if (++relays > 4) { pause("couldn't dock at " + m.getName() + " - click Resume to try again"); return; }
            pf.setInteractionTarget(target);
            sector().layInCourseFor(target);
        }
    }

    /** Stall check: every 2 game days the fleet must be 200 su closer; one nudge, then pause. Gives up after 120 days. */
    private boolean progress(boolean sameLoc, float dist) {
        float since = sector().getClock().getElapsedDaysSince(progressStamp);
        if (sector().getClock().getElapsedDaysSince(stepStartTs) > 120f) { pause("still not there after 120 days"); return false; }
        if (since >= 2f) {
            if (sameLoc && dist > progressDist - 200f) {
                if (++stalls >= 2) { pause("the fleet stopped moving - click Resume to try again"); return false; }
                sector().layInCourseFor(target);
            } else stalls = 0;
            progressDist = sameLoc ? dist : Float.MAX_VALUE;
            progressStamp = sector().getClock().getTimestamp();
        }
        return true;
    }

    private void docked(MarketAPI m) {
        Trader.beginStop();
        note("docked at " + m.getName() + " (" + Trader.tradeMode(sector().getCampaignUI().getCurrentInteractionDialog()) + ")");
        next();
    }

    /** True when the transponder is where this stop's decision wants it. */
    private boolean settled(Planner.Stop st) {
        return st == null || pf().isTransponderOn() == st.on;
    }

    private void tickLeave(InteractionDialogAPI d) {
        if (d == null) {
            // leaving the buy market: back to the transponder-on default before the hyperspace leg
            Step s = steps.get(idx);
            if (s.kind == Kind.LEAVE && trip != null && s.stop == trip.buyStop) restoreTransponder();
            next();
            return;
        }
        if (leaveTries > 0) {
            if (leaveTries++ > 120) pause("the dock dialog didn't close - leave it yourself, then click Resume");
            return;
        }
        leaveTries++;
        try {
            if (d.getOptionPanel().hasOption("marketLeave")) {
                d.getPlugin().optionSelected("Leave", "marketLeave");
                return;
            }
        } catch (Throwable t) { log.warn("Trade Runner: leave failed", t); }
        pause("the dialog needs you (an event or question is open) - finish it and leave, then click Resume");
    }

    // ---------------------------------------------------------------- transponder

    /** Re-decides the stop's transponder setting with live patrols and suspicion, then sets it. */
    private void applyTransponder(Planner.Stop st, MarketAPI m) {
        try {
            GameReader.refreshNode(m, st.node);
            Planner.decide(st);
            CampaignFleetAPI pf = pf();
            AbilityPlugin tp = pf.getAbility(Abilities.TRANSPONDER);
            AbilityPlugin gd = pf.getAbility(Abilities.GO_DARK);
            AbilityPlugin sb = pf.getAbility(Abilities.SUSTAINED_BURN);
            if (st.on) {
                if (gd != null && gd.isActive()) gd.deactivate();
                if (!pf.isTransponderOn() && tp != null) {
                    List<FactionAPI> h = TransponderAbility.getFactionsThatWouldBecomeHostile(pf);
                    if (!h.isEmpty()) {
                        pause("turning the transponder on here would reveal you and make " + names(h)
                                + " hostile. Decide in game (turn it on yourself or leave), then click Resume");
                        return;
                    }
                    tp.activate();
                }
            } else {
                if (pf.isTransponderOn() && tp != null) { tp.deactivate(); turnedOff = true; }
                if (st.goDark && gd != null && !gd.isActive() && gd.isUsable()) {
                    if (sb != null && sb.isActive()) sb.deactivate();
                    gd.activate();
                    wentDark = true;
                } else if (!st.goDark && gd != null && gd.isActive() && wentDark) {
                    gd.deactivate();
                }
            }
            transponderNote = "at " + m.getName() + ": " + st.tLabel().toUpperCase() + " - " + st.reason;
            note("transponder " + transponderNote);
            transponderApplied = true;
        } catch (Throwable t) {
            transponderApplied = true;
            log.warn("Trade Runner: transponder change failed", t);
            note("couldn't set the transponder: " + t);
        }
    }

    private static String names(List<FactionAPI> l) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size(); i++) b.append(i == 0 ? "" : ", ").append(l.get(i).getDisplayName());
        return b.toString();
    }

    // ---------------------------------------------------------------- trading

    private float reserve() { return Rules.f("reserve_credits", 5000f); }

    private void doBuy(Step s, MarketAPI m, InteractionDialogAPI d) {
        Planner.Trip t = trip;
        if (d == null || d.getInteractionTarget() != m.getPrimaryEntity()) { pause("not docked at " + m.getName() + " - dock there, then click Resume"); return; }
        CoreUITradeMode mode = Trader.tradeMode(d);
        if (mode == CoreUITradeMode.NONE) { pause(m.getName() + " refused to trade (patrols tracking you, or reputation). Cancel, or fix it and click Resume"); return; }
        MarketAPI mb = sector().getEconomy().getMarket(t.to.id);
        if (mb == null) { stop(t.to.name + " no longer exists - nothing bought", false); return; }

        // live quantities and submarkets
        int n = t.lines.size();
        float[] q = new float[n];
        boolean[] black = new boolean[n];
        for (int i = 0; i < n; i++) {
            Planner.Line l = t.lines.get(i);
            black[i] = l.buyBlack;
            if (!black[i] && mode != CoreUITradeMode.OPEN) black[i] = true; // docked dark: black market only
            q[i] = (float) Math.floor(Math.min(l.qty, Trader.liveStock(m, black[i], l.good)));
        }
        // spend limits: the plan's order, the reserve, and an optional cap
        float credits = pf().getCargo().getCredits().get();
        float maxSpend = Math.max(0f, credits - reserve());
        if (cap > 0f) maxSpend = Math.min(maxSpend, cap);
        for (int k = 0; k < 40; k++) {
            float total = 0f;
            for (int i = 0; i < n; i++) if (q[i] >= 1f) total += Trader.buyCost(m, black[i], t.lines.get(i).good, q[i]);
            if (total <= maxSpend) break;
            for (int i = 0; i < n; i++) q[i] = (float) Math.floor(q[i] * Math.max(0.5f, Math.min(0.95f, maxSpend / total)));
        }
        // price guard: live gross profit vs the plan's, for the same quantities
        float liveGross = 0f, planGross = 0f;
        for (int i = 0; i < n; i++) {
            if (q[i] < 1f) continue;
            Planner.Line l = t.lines.get(i);
            liveGross += Trader.sellValue(mb, l.sellBlack, l.good, q[i]) - Trader.buyCost(m, black[i], l.good, q[i]);
            planGross += (l.value - l.cost) * q[i] / Math.max(1f, l.qty);
        }
        float guard = Rules.f("price_guard_pct", 60f) / 100f;
        if (planGross <= 0f || liveGross < planGross * guard) {
            stop("prices moved: profit now " + Planner.fmt(liveGross) + " cr vs " + Planner.fmt(planGross)
                    + " planned (before fuel/supplies) - nothing bought at " + m.getName(), false);
            return;
        }
        int boughtLines = 0;
        for (int i = 0; i < n; i++) {
            if (q[i] < 1f) { note("skipped " + t.lines.get(i).name + ": no stock or no money left"); continue; }
            Planner.Line l = t.lines.get(i);
            float left = Math.max(0f, pf().getCargo().getCredits().get() - reserve());
            Trader.Result r = Trader.buy(m, black[i], l.good, q[i], cap > 0f ? Math.min(left, cap - spent) : left, mode);
            note(r.text);
            if (r.ok) { boughtQty[i] = r.qty; paidFor[i] = -r.credits; spent += -r.credits; boughtLines++; }
        }
        if (boughtLines == 0) { Trader.endStop(); stop("nothing could be bought at " + m.getName(), false); return; }
        if (!trip.refuel) Trader.endStop();
        msg("Trade Runner: bought at " + m.getName() + " (-" + Planner.fmt(spent) + " cr)", BLUE);
        next();
    }

    /**
     * Tops up at the buy market (1.1.3): fuel to the second leg + fuel_reserve_ly, supplies to supplies_days aboard.
     * Cheapest submarket that has it (black market has no tariff; docked dark = black market only).
     * Pauses rather than fly on without enough fuel for the second leg.
     */
    private void doRefuel(MarketAPI m, InteractionDialogAPI d) {
        Planner.Trip t = trip;
        float fpl = fleetAtStart.fuelPerLY;
        float legFuel = t.ly2 * fpl;
        float fuelTarget = t.fuelTarget > 0f ? t.fuelTarget : legFuel * 1.2f;
        if (d != null) {
            CoreUITradeMode mode = Trader.tradeMode(d);
            float budget = Math.max(0f, pf().getCargo().getCredits().get() - reserve() / 2f);
            float need = fuelTarget - pf().getCargo().getFuel();
            if (need >= 1f) refillCost += topUp(m, mode, "fuel", (float) Math.ceil(need), budget, "refuel");
            float supNeed = t.supTarget - pf().getCargo().getSupplies();
            float room = pf().getCargo().getMaxCapacity() - pf().getCargo().getSpaceUsed();
            supNeed = Math.min(supNeed, room - 1f);
            budget = Math.max(0f, pf().getCargo().getCredits().get() - reserve() / 2f);
            if (supNeed >= 1f && !Rules.b("ask_before_supplies", true)) refillCost += topUp(m, mode, "supplies", (float) Math.ceil(supNeed), budget, "restock");
            else if (supNeed >= 1f) { note("restock: " + Math.round(supNeed) + " supplies NOT bought - ask the player first ('resupply <days>')"); EventLog.alert("supplies", "ask the player: " + Math.round(supNeed) + " supplies wanted at " + m.getName()); }
        }
        Trader.endStop();
        if (pf().getCargo().getFuel() < legFuel && !fuelAnyway) {
            pause("not enough fuel for the " + String.format("%.1f", t.ly2) + " ly to " + t.to.name + " (" + Math.round(pf().getCargo().getFuel())
                    + " of " + Math.round(legFuel) + "). Buy fuel here, then click Resume");
            return;
        }
        next();
    }

    /** Buys qty of a fleet good, black market first (no tariff) then open; returns the credits spent. */
    private float topUp(MarketAPI m, CoreUITradeMode mode, String id, float qty, float budget, String what) {
        float got = 0f, paid = 0f;
        boolean[] order = mode == CoreUITradeMode.OPEN ? new boolean[]{true, false} : new boolean[]{true};
        for (boolean black : order) {
            float left = qty - got;
            if (left < 1f) break;
            if (black && m.getSubmarket("black_market") == null) continue;
            // 1.0.7: no black-market buying with the transponder on (it costs standing with the market's faction)
            if (black && Shipyard.blackLocked(m.getSubmarket("black_market"))) continue;
            float stock = (float) Math.floor(Trader.liveStock(m, black, id));
            float q = Math.min(left, stock);
            if (q < 1f) continue;
            Trader.Result r = Trader.buy(m, black, id, q, Math.max(0f, budget - paid), mode);
            note(what + ": " + r.text);
            if (r.ok) { got += r.qty; paid += -r.credits; }
        }
        if (got < 1f) note(what + ": none bought at " + m.getName());
        if ("fuel".equals(id)) fuelBought += got; else if ("supplies".equals(id)) supBought += got;
        return paid;
    }

    /**
     * After selling (1.1.5): fuel for end_fuel_ly light years and supplies for supplies_days, so the next trip
     * (or the way home) never starts short. Bought like the top-ups; never pauses the trip.
     */
    private void doRefill(MarketAPI m, InteractionDialogAPI d) {
        if (d != null && d.getInteractionTarget() == m.getPrimaryEntity()) {
            CoreUITradeMode mode = Trader.tradeMode(d);
            if (mode != CoreUITradeMode.NONE) {
                Trader.beginStop();
                float fpl = Math.max(0.1f, pf().getLogistics().getFuelCostPerLightYear());
                float fuelTarget = Math.min(pf().getCargo().getMaxFuel(), Rules.f("end_fuel_ly", 20f) * fpl);
                float spd = 0f;
                try { spd = pf().getLogistics().getTotalSuppliesPerDay(); } catch (Throwable ignored) {}
                if (spd <= 0f && fleetAtStart != null) spd = fleetAtStart.suppliesPerDay;
                float supTarget = Rules.f("supplies_days", 60f) * spd;
                float budget = Math.max(0f, pf().getCargo().getCredits().get() - reserve());
                float need = fuelTarget - pf().getCargo().getFuel();
                if (need >= 1f) refillCost += topUp(m, mode, "fuel", (float) Math.ceil(need), budget, "refill fuel");
                float room = pf().getCargo().getMaxCapacity() - pf().getCargo().getSpaceUsed();
                float sup = Math.min(supTarget - pf().getCargo().getSupplies(), room - 1f);
                budget = Math.max(0f, pf().getCargo().getCredits().get() - reserve());
                if (sup >= 1f && !Rules.b("ask_before_supplies", true)) refillCost += topUp(m, mode, "supplies", (float) Math.ceil(sup), budget, "refill supplies");
                else if (sup >= 1f) { note("refill: " + Math.round(sup) + " supplies NOT bought - ask the player first ('resupply <days>')"); EventLog.alert("supplies", "ask the player: " + Math.round(sup) + " supplies wanted at " + m.getName()); }
                Trader.endStop();
            }
        }
        next();
    }

    private void doSell(Step s, MarketAPI m, InteractionDialogAPI d) {
        Planner.Trip t = trip;
        if (d == null || d.getInteractionTarget() != m.getPrimaryEntity()) { pause("not docked at " + m.getName() + " - dock there, then click Resume"); return; }
        CoreUITradeMode mode = Trader.tradeMode(d);
        if (mode == CoreUITradeMode.NONE) { pause(m.getName() + " refused to trade (patrols tracking you, or reputation). Cancel keeps the cargo; or fix it and click Resume"); return; }
        int n = t.lines.size();
        boolean[] black = new boolean[n];
        float liveValue = 0f, paid = 0f;
        for (int i = 0; i < n; i++) {
            Planner.Line l = t.lines.get(i);
            black[i] = l.sellBlack;
            if (!black[i] && mode != CoreUITradeMode.OPEN) black[i] = true;
            if (black[i] && m.getSubmarket("black_market") == null) black[i] = false;
            float qty = Math.min(boughtQty[i], pf().getCargo().getCommodityQuantity(l.good));
            if (qty >= 1f) { liveValue += Trader.sellValue(m, black[i], l.good, qty); paid += paidFor[i]; }
        }
        if (!t.sellOnly && !sellAnyway && paid > 0f && (loopLeft > 0 || thisIsLoopTrip)) {
            float planVal = 0f;
            for (int i = 0; i < n; i++) { Planner.Line l = t.lines.get(i); if (boughtQty[i] >= 1f) planVal += l.value * boughtQty[i] / Math.max(1f, l.qty); }
            if (liveValue < planVal * 0.6f) {
                Planner.Result r = Plans.refresh();
                Planner.Trip best = r == null || r.sellTrips.isEmpty() ? null : r.sellTrips.get(0);
                if (best != null && !best.to.id.equals(m.getId()) && best.profit > liveValue + 5000f) {
                    Trader.endStop();
                    keptCargo = true;
                    note(m.getName() + " pays only " + Planner.fmt(liveValue) + " cr now (plan " + Planner.fmt(planVal) + "); "
                            + best.to.name + " pays about " + Planner.fmt(best.sellValue) + " - keeping the cargo for a sell trip there");
                    stop("kept the cargo: " + m.getName() + " prices fell", false);
                    return;
                }
            }
        }
        if (!t.sellOnly && !sellAnyway && paid > 0f && liveValue < paid * 0.9f) {
            pause("selling here now returns " + Planner.fmt(liveValue) + " cr for goods that cost " + Planner.fmt(paid)
                    + " cr. Resume = sell anyway, Cancel = keep the cargo");
            return;
        }
        for (int i = 0; i < n; i++) {
            Planner.Line l = t.lines.get(i);
            if (boughtQty[i] < 1f) continue;
            Trader.Result r = Trader.sell(m, black[i], l.good, boughtQty[i], mode);
            if (!r.ok && black[i] && mode == CoreUITradeMode.OPEN) r = Trader.sell(m, false, l.good, boughtQty[i], mode);
            note(r.text);
            if (r.ok) earned += r.credits;
        }
        Trader.endStop();
        msg("Trade Runner: sold at " + m.getName() + " (+" + Planner.fmt(earned) + " cr)", BLUE);
        next();
    }

    private void finish() {
        restoreTransponder();
        stop("done", true);
    }

    /** Go Dark off and the transponder back on if this trip switched them, unless that would reveal the fleet. */
    private void restoreTransponder() {
        CampaignFleetAPI pf = pf();
        try {
            AbilityPlugin gd = pf.getAbility(Abilities.GO_DARK);
            if (wentDark && gd != null && gd.isActive()) gd.deactivate();
            wentDark = false;
            if (turnedOff && !pf.isTransponderOn() && Rules.b("restore_transponder", true)) {
                List<FactionAPI> h = TransponderAbility.getFactionsThatWouldBecomeHostile(pf);
                AbilityPlugin tp = pf.getAbility(Abilities.TRANSPONDER);
                if (h.isEmpty() && tp != null) { tp.activate(); note("transponder back on"); turnedOff = false; }
                else if (!h.isEmpty()) note("transponder left OFF: turning it on would reveal you to " + names(h));
            }
        } catch (Throwable t) { log.warn("Trade Runner: transponder restore failed", t); }
    }

    /** Pauses once if the fleet has fewer ships than when the trip started (lost or disabled in a fight). */
    private boolean lostShips(CampaignFleetAPI pf) {
        try {
            int n = pf.getFleetData().getNumMembers();
            if (membersAtStart > 0 && n < membersAtStart) {
                int lost = membersAtStart - n;
                membersAtStart = n;
                pause("the fleet lost " + lost + " ship(s) - check it, then click Resume (or Cancel)");
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ---------------------------------------------------------------- loop mode

    public static String startLoop(int n) {
        if (instance == null) return "not ready (no campaign loaded)";
        if (n < 1) return "usage: loop <number of trips>";
        loopTotal = n; loopLeft = n; loopDone = 0; loopNet = 0f; lossStreak = 0;
        synchronized (recentSells) { recentSells.clear(); failedKeys.clear(); }
        loopLog.setLength(0);
        loopLog.append("#loop ").append(n).append(" trips\n");
        if (busy()) { loopLeft = 0; loopTotal = 0; return "a trip is " + state + " - let it finish or cancel it, then start the loop"; }
        pendingNext = true; nextAt = 0L;
        return "loop of " + n + " trips started (each trip: plan, take the best trip that passes the rules, run it)";
    }

    public static String loopStatus() {
        if (loopTotal == 0) return "loop: off";
        return "loop: " + loopDone + "/" + loopTotal + " trips done, net " + (loopNet >= 0 ? "+" : "") + Planner.fmt(loopNet) + " cr"
                + (loopLeft > 0 || pendingNext ? ", " + loopLeft + " to go" : ", finished");
    }

    private static void endLoop(String why) {
        loopLeft = 0; pendingNext = false;
        String s = "LOOP ENDED: " + why + " - " + loopStatus();
        loopLog.append(s).append("\n");
        msg("Trade Runner: " + s, GOLD);
        if (instance != null) { instance.note(s); }
        EventLog.reply(loopLog.toString());
    }

    /** Plans afresh and starts the next loop trip (or a sell trip for cargo kept from the last one). */
    private void nextLoopTrip() {
        if (loopLeft <= 0 && !keptCargoPending) { endLoop("all " + loopTotal + " trips run"); return; }
        Planner.Result r = Plans.refresh();
        Plans.write(true);
        if (r == null) { endLoop("planning failed: " + Plans.error); return; }
        if (keptCargoPending) {
            keptCargoPending = false;
            if (!r.sellTrips.isEmpty()) {
                Planner.Trip st = r.sellTrips.get(0);
                loopLog.append("sell trip for kept cargo: ").append(st.title()).append("\n");
                String res = start(st, 0f, "loop (sell kept cargo)");
                if (res.startsWith("started")) return;
                loopLog.append("  could not start: ").append(res).append("\n");
            }
        }
        if (loopLeft <= 0) { endLoop("all " + loopTotal + " trips run"); return; }
        Planner.Trip pick = null;
        synchronized (recentSells) {
            for (Planner.Trip t : r.trips) {
                if (recentSells.contains(t.to.id)) continue;   // we just sold there: its prices are down
                if (failedKeys.contains(t.key())) continue;
                pick = t; break;
            }
        }
        if (pick == null) { endLoop(r.trips.isEmpty() ? "no trips: " + r.why : "every planned trip sells where we just sold or failed already"); return; }
        int k = loopTotal - loopLeft + 1;
        loopLeft--;
        loopLog.append("trip ").append(k).append("/").append(loopTotal).append(": ").append(pick.title())
               .append(" (plan +").append(Planner.fmt(pick.profit)).append(" cr, ").append(Math.round(pick.days)).append(" days)\n");
        String res = start(pick, 0f, "loop " + k + "/" + loopTotal);
        if (!res.startsWith("started")) endLoop("could not start trip " + k + ": " + res);
    }

    private static volatile boolean keptCargoPending = false;

    /** Called when a trip ends: books it and schedules the next loop trip. */
    private void loopAfter(Planner.Trip t, boolean completed, String why, float net) {
        if (loopTotal == 0) return;
        if (why.startsWith("cancelled")) return; // cancel() already stopped the loop
        if (!thisIsLoopTrip && !(loopLeft > 0)) return;
        loopNet += net;
        if (t != null && !t.sellOnly) loopDone++;
        loopLog.append("  ").append(lastSummary).append("\n");
        if (t != null && (completed || keptCargo)) {
            synchronized (recentSells) {
                if (completed) { recentSells.add(t.to.id); while (recentSells.size() > Math.max(1, (int) Rules.f("loop_skip_recent", 2f))) recentSells.remove(0); }
            }
        }
        if (t != null && !completed && !keptCargo && spent == 0f) synchronized (recentSells) { failedKeys.add(t.key()); }
        if (completed && net < 0f) lossStreak++; else if (completed) lossStreak = 0;
        if (lossStreak >= 2) { endLoop("two losing trips in a row"); return; }
        if (!completed && spent > 0f && earned == 0f && !keptCargo) { endLoop("trip stopped with the goods still aboard: " + why); return; }
        keptCargoPending = keptCargo;
        if (loopLeft <= 0 && !keptCargoPending) { endLoop("all " + loopTotal + " trips run"); return; }
        pendingNext = true;
        nextAt = System.currentTimeMillis() + 3000L;
        EventLog.reply(loopLog.toString() + loopStatus() + "\n");
    }

    // ---------------------------------------------------------------- end, pause, report

    private void stop(String why, boolean completed) {
        if (!completed) restoreTransponder();
        Planner.Trip t = trip;
        float net = earned - spent;
        String refills = refillCost >= 1f ? " | fuel/supplies: +" + Planner.fmt(fuelBought) + " fuel, +" + Planner.fmt(supBought)
                + " supplies, -" + Planner.fmt(refillCost) + " cr; after refills " + (net - refillCost >= 0 ? "+" : "") + Planner.fmt(net - refillCost) + " cr" : "";
        String sum;
        if (t == null) sum = "Trip " + why;
        else if (t.sellOnly) sum = "Sold: +" + Planner.fmt(earned) + " cr (" + why + ")";
        else sum = (completed ? "Trip done" : "Trip stopped") + ": spent " + Planner.fmt(spent) + ", earned " + Planner.fmt(earned)
                + ", net " + (net >= 0 ? "+" : "") + Planner.fmt(net) + " cr (plan " + Planner.fmt(t.sellValue - t.buyCost) + " before fuel/supplies)"
                + refills + (completed ? "" : " - " + why);
        if (!completed && spent > 0f && earned == 0f && t != null && !t.sellOnly) sum += ". The goods are still in your hold.";
        lastSummary = sum;
        note(sum);
        if (!completed && target != null) {
            try {
                if (sector().getCampaignUI().getCurrentInteractionDialog() == null) sector().getCampaignUI().clearLaidInCourse();
                if (pf().getInteractionTarget() == target) pf().setInteractionTarget(null);
            } catch (Throwable ignored) {}
        }
        state = "idle";
        pauseReason = null;
        stepText = "";
        target = null;
        report.append("#done ").append(why).append("\n").append(sum).append("\n");
        writeLog();
        msg("Trade Runner: " + sum, completed ? BLUE : GOLD);
        CaptainHud.refreshNow();
        try { loopAfter(t, completed, why, net - refillCost); } catch (Throwable e) { log.error("Trade Runner: loop bookkeeping failed", e); }
    }

    private void pause(String why) {
        state = "paused";
        pauseReason = why;
        note("PAUSED: " + why);
        msg("Trade Runner paused: " + why, GOLD);
        writeLog();
    }

    private void note(String line) {
        String stamp = "";
        try { stamp = sector().getClock().getDateString() + "  "; } catch (Throwable ignored) {}
        report.append(stamp).append(line).append("\n");
        synchronized (recent) {
            recent.add(line);
            while (recent.size() > 6) recent.remove(0);
        }
        log.info("Trade Runner: " + line);
        if (line.startsWith("PAUSED: ")) EventLog.alert("trade", "trip paused: " + line.substring(8) + " ('resume' / 'cancel')");
        else EventLog.info("trade", line);
    }

    private static void msg(String text, Color c) {
        try { Global.getSector().getCampaignUI().addMessage(text, c); } catch (Throwable ignored) {}
    }

    /** Appends this run's report to runlog.txt (newest last, last ~300 lines kept) and copies it to the outbox. */
    private void writeLog() {
        try {
            String old = Global.getSettings().fileExistsInCommon(Files.RUNLOG) ? Global.getSettings().readTextFileFromCommon(Files.RUNLOG) : "";
            // replace this run's earlier copy, if any
            int at = old.indexOf("#run " + runId + " ");
            if (at >= 0) old = old.substring(0, at);
            String all = old + report;
            String[] lines = all.split("\n");
            if (lines.length > 300) {
                StringBuilder b = new StringBuilder();
                for (int i = lines.length - 300; i < lines.length; i++) b.append(lines[i]).append("\n");
                all = b.toString();
            }
            Global.getSettings().writeTextFileToCommon(Files.RUNLOG, all);
        } catch (Throwable t) { log.warn("Trade Runner: could not write the run log", t); }
    }

    /** For the self-test. */
    static String describe() {
        TripRunner r = instance;
        if (r == null) return "runner: not installed";
        StringBuilder b = new StringBuilder("runner: ").append(state);
        if (trip != null && busy()) b.append(", trip ").append(trip.title()).append(", step ").append(stepIndex).append("/").append(stepTotal).append(" ").append(stepText);
        if (pauseReason != null) b.append(", paused: ").append(pauseReason);
        if (lastSummary != null) b.append("\n     last: ").append(lastSummary);
        return b.toString();
    }
}
