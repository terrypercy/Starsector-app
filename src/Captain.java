package claudecaptain;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one script that runs Claude's side of the game: reads claude_captain/inbox.txt (first line
 * "#id <unique>", then one command per line), runs instant commands at once and goals until they
 * finish, logs every result as an event (events.txt, with the batch id), keeps the governor ticking,
 * answers dialogs that open while Claude is in control, and refreshes state.txt.
 *
 * A new batch replaces the unfinished rest of the previous one (that rest is logged as not run), but a
 * goal that is already running keeps going unless the new batch starts another goal or says 'cancel'.
 */
public class Captain implements EveryFrameScript {

    private static final Logger log = Global.getLogger(Captain.class);
    private static Captain instance;

    // shared with the panel and the advisor
    public static volatile String activeTitle = null;
    public static volatile boolean activeIsAdvisor = false;
    public static volatile Goals.Goal goal;
    public static final List<String> history = new ArrayList<String>();
    private static volatile boolean arrived = false;
    private static volatile List<String> pendingTasks;
    private static volatile String pendingId, pendingTitle;

    private final long created = System.currentTimeMillis();
    private long lastPoll = 0L, lastState = 0L;
    private final List<String> queue = new ArrayList<String>();
    private String batchId = null, lastBatchId = null;
    private boolean continueOnError = false, batchHasRun = false, goalReported = false;
    private InteractionDialogAPI expectedDialog, alertedDialog;
    private Object alertedBattle;
    private String askLine = null;
    private long askStart = 0L, askTry = 0L;
    private final CommandRunner runner = new CommandRunner();

    public static void install() {
        instance = new Captain();
        Global.getSector().addTransientScript(instance);
        goal = null; activeTitle = null;
        synchronized (history) { history.clear(); }
        Governor.reset();
    }

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return true; }

    static SectorAPI sector() { return Global.getSector(); }

    public static void submit(String id, String title, List<String> tasks) {
        pendingTitle = title;
        pendingId = id;
        pendingTasks = new ArrayList<String>(tasks);
    }

    public static void markArrived() { arrived = true; }
    static boolean arrivedSince(long ignored) { return arrived; }

    public static boolean idle() {
        Captain c = instance;
        boolean q = c == null || (c.queue.isEmpty() && c.askLine == null);
        return q && (goal == null || goal.finished()) && !TripRunner.busy() && "idle".equals(BountyRunner.state);
    }

    public static boolean lowCrExpected() {
        Goals.Goal g = goal;
        if (g != null && !g.finished() && g.lowCrOk()) return true;
        String s = BountyRunner.state;
        return "rest".equals(s) || "after".equals(s) || "engage".equals(s) || "resupply".equals(s);
    }

    // ------------------------------------------------------------------ frame

    public void advance(float amount) {
        long now = System.currentTimeMillis();
        if (now - created < 3000L) return;
        if (now - lastPoll >= 250L) {
            lastPoll = now;
            try { checkInbox(); } catch (Throwable t) { log.error("Claude Captain: inbox error", t); EventLog.warn("inbox", "inbox error: " + t); }
        }
        try { StormGuard.tick(); } catch (Throwable t) { log.error("Claude Captain: storm guard error", t); }
        try { Governor.tick(); } catch (Throwable t) { log.error("Claude Captain: governor error", t); }
        try { step(now); } catch (Throwable t) {
            log.error("Claude Captain: step error", t);
            EventLog.alert("error", "internal error: " + t);
            if (goal != null && !goal.finished()) goal.fail("internal error: " + t);
        }
        try { watchDialogs(now); } catch (Throwable t) { log.error("Claude Captain: dialog watch error", t); }
        if (now - lastState >= (long) (Rules.f("gov_state_seconds", 5f) * 1000f)) {
            lastState = now;
            StateWriter.write();
        }
        EventLog.flush(false);
    }

    // ------------------------------------------------------------------ inbox

    private void checkInbox() throws Exception {
        List<String> pend = pendingTasks;
        if (pend != null) {
            pendingTasks = null;
            startBatch(pendingId, pend, pendingTitle);
            return;
        }
        SettingsAPI s = Global.getSettings();
        if (!s.fileExistsInCommon(Files.INBOX)) return;
        String text = s.readTextFileFromCommon(Files.INBOX);
        s.deleteTextFileFromCommon(Files.INBOX);
        if (text == null || text.trim().length() == 0) return;
        String id = null;
        boolean coe = false;
        List<String> lines = new ArrayList<String>();
        for (String raw : text.replace("\r", "").split("\n")) {
            String l = raw.trim();
            if (l.startsWith("#id")) id = l.substring(3).trim();
            else if (l.toLowerCase(Locale.ROOT).startsWith("#on_error") && l.toLowerCase(Locale.ROOT).contains("continue")) coe = true;
            else if (l.length() > 0 && !l.startsWith("#")) lines.add(l);
        }
        if (lastBatchId == null && s.fileExistsInCommon(Files.LAST_ID)) lastBatchId = s.readTextFileFromCommon(Files.LAST_ID).trim();
        if (id != null && id.equals(lastBatchId)) { EventLog.info("inbox", "batch " + id + " ignored: same #id as the last one"); return; }
        lastBatchId = id;
        if (id != null) s.writeTextFileToCommon(Files.LAST_ID, id);
        Governor.arm("batch " + (id == null ? "?" : id));
        startBatch(id, lines, null);
        continueOnError = coe;
    }

    private void startBatch(String id, List<String> lines, String title) {
        if (!queue.isEmpty()) EventLog.info("batch", "batch " + batchId + " replaced; not run: " + queue);
        queue.clear();
        AskPopup.cancel();
        askLine = null;
        batchId = id == null ? "none" : id;
        EventLog.batch = batchId;
        activeIsAdvisor = batchId.startsWith("advisor-");
        activeTitle = title != null ? title : "Claude's batch " + batchId;
        continueOnError = false;
        batchHasRun = false;
        // governor lines (run/pause/control) take effect when the batch arrives, wherever they stand:
        // a batch waits for each goal, so a 'run' after a goal would otherwise never be reached.
        // 1.0.2: 'cancel'/'resume' also act at arrival; a batch that starts a goal replaces an unfinished
        // (running or paused) goal; a batch without goals runs at once while a goal is unfinished.
        boolean unfinished = goal != null && !goal.finished();
        boolean docked = false;
        try { docked = Goals.dockedMarket() != null; } catch (Throwable ignored) {}
        List<List<String>> parts = BatchMath.split(lines, unfinished, docked);
        List<String> gov = new ArrayList<String>(), other = new ArrayList<String>();
        for (String l : parts.get(0)) {
            if (BatchMath.GOV.equals(BatchMath.kind(l, docked))) {
                gov.add(l);
                if (!l.trim().equalsIgnoreCase("pause")) batchHasRun = true;
            } else other.add(l);
        }
        EventLog.add(EventCore.CMD, "batch", "batch " + batchId + ": " + lines.size() + " command(s)"
                + (gov.isEmpty() ? "" : ", governor first: " + gov)
                + (other.isEmpty() ? "" : ", at once: " + other));
        if (!parts.get(2).isEmpty() && goal != null && !goal.finished()) {
            String old = goal.title, st = goal.state;
            goal.cancel();
            EventLog.info("goal", "replaced (" + st + "): " + old + " - batch " + batchId + " starts a new goal");
        }
        for (String h : gov) runLine(h);
        for (String h : other) runLine(h);
        queue.addAll(parts.get(1));
    }

    // ------------------------------------------------------------------ running

    private void step(long now) {
        Goals.Goal g = goal;
        if (g != null && !g.finished()) {
            if (g instanceof Goals.Runner) ((Goals.Runner) g).follow();
            if (g.running()) { g.tick(now); goalReported = false; }
            if ("paused".equals(g.state) && !goalReported) {
                goalReported = true;
                EventLog.alert("goal", "goal paused: " + g.title + " - " + g.result + "\n(fix it, then 'resume'; or 'cancel')");
                Governor.stop("goal paused: " + g.result);
            }
            if (g.finished()) finishGoal(g);
            else return;
        }
        if (askLine != null) { handleAsk(); return; }
        if (queue.isEmpty()) {
            if (activeTitle != null && (goal == null || goal.finished())) {
                EventLog.goal("batch " + batchId + " finished");
                activeTitle = null;
                activeIsAdvisor = false;
                expectedDialog = sector().getCampaignUI().getCurrentInteractionDialog();
            }
            return;
        }
        String line = queue.remove(0);
        runLine(line);
        expectedDialog = sector().getCampaignUI().getCurrentInteractionDialog();
    }

    private void finishGoal(Goals.Goal g) {
        boolean ok = "done".equals(g.state);
        EventLog.add(ok ? EventCore.GOAL : EventCore.WARN, "goal", (ok ? "DONE " : "FAILED ") + g.title + ": " + g.result);
        synchronized (history) { history.add((ok ? "done: " : "failed: ") + g.title); while (history.size() > 5) history.remove(0); }
        goal = null;
        expectedDialog = sector().getCampaignUI().getCurrentInteractionDialog();
        CaptainHud.refreshNow();
        if (!ok && !continueOnError && !queue.isEmpty()) {
            EventLog.info("batch", "not run after the failed goal: " + queue);
            queue.clear();
        }
    }

    private void runLine(String line) {
        boolean optional = line.toLowerCase(Locale.ROOT).startsWith("optional ");
        String l = optional ? line.substring(9).trim() : line;
        try {
            Goals.Goal g = goalFor(l);
            if (g != null) {
                if (goal != null && !goal.finished()) { goal.cancel(); EventLog.info("goal", "replaced: " + goal.title); }
                goal = g;
                goalReported = false;
                if (!batchHasRun && !activeIsAdvisor) Governor.implicitRun();
                EventLog.add(EventCore.CMD, "cmd", "> " + line + "\nstarted goal: " + g.title + (g.progress.length() > 0 ? " (" + g.progress + ")" : ""));
                return;
            }
            String r = instant(l);
            EventLog.add(EventCore.CMD, "cmd", "> " + line + "\n" + r);
            if (runner.lastMessage != null) msg(runner.lastMessage);
        } catch (AskStarted e) {
            // the question is up; handleAsk() waits for the click
        } catch (CommandException e) {
            if (optional) { EventLog.add(EventCore.CMD, "cmd", "> " + line + "\nskipped: " + e.getMessage()); return; }
            EventLog.add(EventCore.WARN, "cmd", "> " + line + "\nERR " + e.getMessage());
            if (!continueOnError && !queue.isEmpty()) { EventLog.info("batch", "not run after the error: " + queue); queue.clear(); }
        } catch (IllegalArgumentException e) {
            EventLog.add(EventCore.WARN, "cmd", "> " + line + "\nERR " + e.getMessage());
            if (!continueOnError && !queue.isEmpty()) { EventLog.info("batch", "not run after the error: " + queue); queue.clear(); }
        } catch (Throwable t) {
            log.error("Claude Captain: command failed: " + line, t);
            EventLog.add(EventCore.WARN, "cmd", "> " + line + "\nERR internal: " + t);
            if (!continueOnError && !queue.isEmpty()) queue.clear();
        }
    }

    static void msg(String s) { try { sector().getCampaignUI().addMessage("[Captain] " + s, Hud.INFO); } catch (Throwable ignored) {} }

    static String rest(List<String> t, int from) { return CommandRunner.join(t.subList(Math.min(from, t.size()), t.size())); }

    /** A goal for this command, or null when it is an instant command. */
    private Goals.Goal goalFor(String line) throws CommandException {
        List<String> t = CommandRunner.tokenize(line);
        if (t.isEmpty()) return null;
        String c = t.get(0).toLowerCase(Locale.ROOT);
        String a = rest(t, 1);
        if (c.equals("goto") || c.equals("travel")) {
            if (a.isEmpty()) throw new CommandException("usage: travel <place>");
            return new Goals.Travel(Lookup.place(a), false, 0f);
        }
        if (c.equals("approach")) {
            if (t.size() < 3 || !CommandRunner.isNum(t.get(t.size() - 1))) throw new CommandException("usage: approach <place> <distance in su>");
            return new Goals.Travel(Lookup.place(CommandRunner.join(t.subList(1, t.size() - 1))), false, (float) CommandRunner.num(t.get(t.size() - 1)));
        }
        if (c.equals("dock")) {
            SectorEntityToken target;
            if (a.isEmpty()) {
                MarketAPI here = Goals.dockedMarket();
                if (here != null) return null; // "already docked" handled as instant
                target = nearestMarketHere();
                if (target == null) throw new CommandException("no market in this system; use dock <market>");
            } else target = Lookup.place(a);
            return new Goals.Travel(target, true, 0f);
        }
        if (c.equals("wait")) {
            if (t.size() < 2 || !CommandRunner.isNum(t.get(1))) throw new CommandException("usage: wait <days>");
            return new Goals.Wait((float) CommandRunner.num(t.get(1)));
        }
        if (c.equals("resupply")) {
            float days = t.size() > 1 && CommandRunner.isNum(t.get(1)) ? (float) CommandRunner.num(t.get(1)) : Rules.f("resupply_days", 60f);
            return new Goals.Resupply(days);
        }
        if (c.equals("repair") && (t.size() == 1 || t.get(1).equalsIgnoreCase("dockyard"))) return new Goals.Repair();
        if (c.equals("hire")) {
            List<String> r = new ArrayList<String>(t.subList(1, t.size()));
            if (!r.isEmpty() && r.get(0).toLowerCase(Locale.ROOT).startsWith("officer")) r.remove(0);
            return new Goals.Hire(r.isEmpty() ? null : r.get(0));
        }
        if (c.equals("commission")) {
            if (a.isEmpty()) return null;
            FactionAPI f = Lookup.faction(a);
            String cur = Misc.getCommissionFactionId();
            if (cur != null) throw new CommandException("already commissioned by " + cur);
            float rel = f.getRelationship("player") * 100f, req = Reputation.required();
            if (rel < req) throw new CommandException(f.getDisplayName() + " commission needs Favorable (" + Math.round(req) + "), standing now "
                    + Math.round(rel) + " (" + f.getRelationshipLevel("player") + ")\n" + Reputation.suggestionText(f));
            return new Goals.Commission(f);
        }
        if (c.equals("explore") && (a.isEmpty() || a.equalsIgnoreCase("here"))) return new Goals.Explore();
        if (c.equals("hunt")) {
            String al = a.toLowerCase(Locale.ROOT);
            if (al.isEmpty() || al.equals("status") || al.equals("stop") || al.equals("resume")) return null;
            String r = BountyRunner.hunt(a);
            if (!r.startsWith("hunting")) throw new CommandException(r);
            Goals.Runner g = new Goals.Runner(true, "Hunt: " + r.substring(8));
            EventLog.info("hunt", r);
            return g;
        }
        if (c.equals("go")) {
            String x = a.trim();
            boolean refill = x.toLowerCase(Locale.ROOT).endsWith(" refill");
            if (refill) x = x.substring(0, x.length() - 7).trim();
            if (x.isEmpty()) throw new CommandException("usage: go <market> [refill]");
            Planner.Trip g = Plans.goTrip(x, refill);
            if (g == null) throw new CommandException("no market matching '" + x + "'");
            return startTrip(g, 0f, "go");
        }
        boolean tripCmd = c.equals("trip") || c.equals("runtrip") || c.equals("runsell")
                || (c.equals("run") && t.size() > 2 && t.get(1).equalsIgnoreCase("trip"));
        if (tripCmd) {
            int i = c.equals("run") ? 2 : 1;
            if (t.size() <= i || !CommandRunner.isNum(t.get(i))) throw new CommandException("usage: trip <n> [cap <credits>] | runsell <n>");
            int n = (int) CommandRunner.num(t.get(i));
            float cap = 0f;
            for (int k = i + 1; k + 1 < t.size(); k++) if (t.get(k).equalsIgnoreCase("cap")) cap = (float) CommandRunner.num(t.get(k + 1));
            if (Plans.stale(60000L)) Plans.refresh();
            Planner.Trip trip = Plans.trip(n, c.equals("runsell"));
            if (trip == null) throw new CommandException("no trip " + n + " in the latest plan (send 'plan')");
            return startTrip(trip, cap, "Claude (inbox)");
        }
        if (c.equals("loop") || (c.equals("trade") && t.size() > 1 && t.get(1).equalsIgnoreCase("loop"))) {
            List<String> r = c.equals("loop") ? t.subList(1, t.size()) : t.subList(2, t.size());
            if (r.isEmpty() || r.get(0).equalsIgnoreCase("stop") || r.get(0).equalsIgnoreCase("off") || !CommandRunner.isNum(r.get(0))) return null;
            String s = TripRunner.startLoop((int) CommandRunner.num(r.get(0)));
            if (!s.startsWith("loop of")) throw new CommandException(s);
            EventLog.info("trade", s);
            return new Goals.Runner(false, "Trade loop of " + r.get(0));
        }
        if (c.equals("ask")) {
            AskPopup.Parsed pa = AskPopup.parse(line);
            AskPopup.begin(pa.question, pa.options);
            askLine = line;
            askStart = System.currentTimeMillis();
            askTry = 0L;
            EventLog.add(EventCore.CMD, "cmd", "> " + line + "\nquestion shown in game, waiting for an answer");
            throw new AskStarted();
        }
        return null;
    }

    /** Thrown by goalFor for 'ask' so runLine logs nothing more. */
    static final class AskStarted extends CommandException { AskStarted() { super("ask started"); } }

    private Goals.Goal startTrip(Planner.Trip trip, float cap, String src) throws CommandException {
        if (!"idle".equals(BountyRunner.state)) throw new CommandException("a hunt is " + BountyRunner.state + "; 'hunt stop' first");
        String r = TripRunner.start(trip, cap, src);
        if (!r.startsWith("started")) throw new CommandException(r);
        EventLog.info("trade", r);
        return new Goals.Runner(false, trip.title());
    }

    private SectorEntityToken nearestMarketHere() {
        CampaignFleetAPI pf = sector().getPlayerFleet();
        MarketAPI best = null;
        float bd = Float.MAX_VALUE;
        for (MarketAPI m : sector().getEconomy().getMarkets(pf.getContainingLocation())) {
            if (m.isHidden() || m.getPrimaryEntity() == null) continue;
            float d = Misc.getDistance(pf, m.getPrimaryEntity());
            if (d < bd) { bd = d; best = m; }
        }
        return best == null ? null : best.getPrimaryEntity();
    }

    // ------------------------------------------------------------------ instant commands

    private String instant(String line) throws CommandException {
        List<String> t = CommandRunner.tokenize(line);
        String c = t.get(0).toLowerCase(Locale.ROOT);
        String a = rest(t, 1);
        runner.lastMessage = null;
        if (c.equals("help")) return Help.TEXT;
        if (c.equals("run")) {
            String r = Governor.run(a);
            arrived = false;
            return r;
        }
        if (c.equals("unpause")) { arrived = false; return Governor.run(String.valueOf(Rules.f("gov_unpause_days", 3f))) + " ('unpause' = run " + Math.round(Rules.f("gov_unpause_days", 3f)) + " days)"; }
        if (c.equals("pause")) { Governor.stop("paused by command"); return "paused; run budget revoked"; }
        if (c.equals("control") || c.equals("hands")) {
            String w = a.toLowerCase(Locale.ROOT);
            if (w.startsWith("player") || w.equals("on")) { Governor.disarm("command"); return "player in control: the governor only warns"; }
            if (w.startsWith("claude") || w.equals("off")) { Governor.arm("command"); return "Claude in control: the game runs only inside a run budget"; }
            return "usage: control claude | control player";
        }
        if (c.equals("governor") || c.equals("gov")) return Governor.describe();
        if (c.equals("events")) {
            if (t.size() >= 3 && t.get(1).equalsIgnoreCase("since") && CommandRunner.isNum(t.get(2))) return EventLog.since((long) CommandRunner.num(t.get(2)));
            if (t.size() >= 2 && CommandRunner.isNum(t.get(1))) return EventLog.since((long) CommandRunner.num(t.get(1)));
            return "usage: events since <n> (last event #" + EventLog.core.lastSeq() + ")";
        }
        if (c.equals("state")) { StateWriter.write(); return "state.txt written"; }
        if (c.equals("status")) {
            StateWriter.write();
            try { Global.getSettings().writeTextFileToCommon(Files.STATUS, StatusReport.build()); } catch (Throwable ignored) {}
            return "state.txt and status.txt updated\n" + Governor.describe() + "\ngoal: " + (goal == null ? "none" : goal.line() + " [" + goal.state + "]")
                    + "\n" + TripRunner.describe() + "\n" + TripRunner.loopStatus() + "\n" + BountyRunner.status();
        }
        if (c.equals("selftest")) return selfTest();
        if (c.equals("cancel")) {
            StringBuilder b = new StringBuilder();
            if (goal != null && !goal.finished()) { goal.cancel(); b.append("goal cancelled: ").append(goal.title).append("\n"); }
            if (TripRunner.busy() || TripRunner.loopLeft > 0) b.append(TripRunner.cancel("by Claude")).append("\n");
            if (!"idle".equals(BountyRunner.state)) b.append(BountyRunner.hunt("stop")).append("\n");
            if (!queue.isEmpty()) { b.append("dropped: ").append(queue).append("\n"); queue.clear(); }
            try { if (sector().getCampaignUI().getCurrentInteractionDialog() == null) sector().getCampaignUI().clearLaidInCourse(); } catch (Throwable ignored) {}
            return b.length() == 0 ? "nothing to cancel" : b.toString().trim();
        }
        if (c.equals("resume")) {
            DialogAgent.resumedHere();
            if (goal != null && "paused".equals(goal.state)) { goal.resume(); goalReported = false; return "resumed: " + goal.title + (Governor.budget.active() ? "" : " (no run budget: send 'run ...')"); }
            if ("paused".equals(TripRunner.state)) return TripRunner.resume();
            if ("paused".equals(BountyRunner.state)) return BountyRunner.hunt("resume");
            return "nothing paused to resume (use 'run <days>' to let time pass)";
        }
        if (c.equals("dock")) return "already docked at " + Goals.dockedMarket().getName();
        if (c.equals("talk")) { if (a.isEmpty()) return runner.run("contact"); return runner.run("contact " + a); }
        if (c.equals("recover")) return Recovery.command(a);
        if (c.equals("explore")) return Explorer.list();
        if (c.equals("commission")) return Reputation.report();
        if (c.equals("rep") && (a.isEmpty() || a.equalsIgnoreCase("status"))) return Reputation.report();
        if (c.equals("rules")) {
            DialogRules r = DialogAgent.rules();
            return "dialog_rules.txt: " + r.rules.size() + " rules" + (r.errors.isEmpty() ? "" : ", errors:\n" + DialogAgent.join(r.errors, "\n")) + "\ncaptain_rules.txt: " + Rules.dump();
        }
        if (c.equals("dialog") && a.equalsIgnoreCase("sig")) {
            InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
            if (d == null) throw new CommandException("no dialog is open");
            return DialogAgent.describe(d, goal == null ? DialogAgent.ctx("idle") : goal.ctx, goal == null ? null : goal.target(), null);
        }
        if (c.equals("hunt")) { if (a.trim().equalsIgnoreCase("resume")) DialogAgent.resumedHere(); return BountyRunner.hunt(a); }
        if (c.equals("loop") || c.equals("trade")) { TripRunner.loopLeft = 0; return "loop will stop after the current trip; " + TripRunner.loopStatus(); }
        // Trade Runner commands
        if (c.equals("plan")) {
            Planner.Result r = Plans.refresh();
            Plans.write(true);
            CaptainHud.refreshNow();
            if (r == null) return "planning failed: " + Plans.error;
            return "planned: " + r.trips.size() + " trips, " + r.sellTrips.size() + " sell trips; full list in plans.txt\n" + Plans.text(5);
        }
        if (c.equals("bounties")) return BountyRunner.list();
        if (c.equals("fleet")) return Shipyard.fleet();
        if (c.equals("ships")) return Shipyard.ships();
        if (c.equals("weapons")) return Shipyard.weapons(a);
        if (c.equals("buyship")) return Shipyard.buyShip(a);
        if (c.equals("buyspec")) return Shipyard.buySpec(a);
        if (c.equals("buyweapon")) return Shipyard.buyWeapon(a);
        if (c.equals("autofit")) return Shipyard.autofit(a);
        if (c.equals("assign")) return Shipyard.assign(a);
        if (c.equals("autoassign")) return Shipyard.autoAssign();
        if (c.equals("restore")) return Shipyard.restore(a);
        // 1.0.7
        if (c.equals("sellship")) return Shipyard.sellShip(a);
        if (c.equals("mothball")) return Shipyard.mothball(a);
        if (c.equals("repairs")) return Shipyard.repairs(a);
        if (c.equals("addmod")) return Shipyard.addMod(a);
        if (c.equals("removemod")) return Shipyard.removeMod(a);
        if (c.equals("missions")) return Missions.list();
        if (c.equals("accept")) return Missions.accept(a);
        if (c.equals("activate")) return Shipyard.activate(a);
        if (c.equals("equip")) return Shipyard.equip(a);
        if (c.equals("unequip")) return Shipyard.unequip(a);
        if (c.equals("store")) return Shipyard.store(a);
        if (c.equals("retrieve")) return Shipyard.retrieve(a);
        if (c.equals("crew") && !(t.size() > 1 && t.get(1).equalsIgnoreCase("add"))) return Shipyard.crew(a);
        // cheat spellings of names the real commands now use
        if (c.equals("repair")) return runner.run("repair"); // "repair instant": cheat-gated inside CommandRunner
        // everything else: Claude Bridge commands
        return runner.run(line);
    }

    private void handleAsk() {
        long now = System.currentTimeMillis();
        if (AskPopup.answered()) {
            int n = AskPopup.answerNumber();
            String text = AskPopup.answerText();
            EventLog.add(EventCore.WARN, "ask", askLine + "\n" + (n == AskPopup.IN_CHAT ? "player chose to answer in the chat" : "answer " + n + ": " + text));
            AskPopup.cancel();
            askLine = null;
            return;
        }
        if (now - askTry >= 1000L) { askTry = now; AskPopup.tryShow(); }
        if (now - askStart > 30L * 60L * 1000L) {
            EventLog.warn("ask", askLine + "\nno answer after 30 minutes");
            AskPopup.cancel();
            askLine = null;
        }
    }

    // ------------------------------------------------------------------ dialogs and battles nobody planned

    private void watchDialogs(long now) {
        if (!Governor.armed) return;
        CampaignFleetAPI pf = sector().getPlayerFleet();
        if (pf == null) return;
        InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
        boolean busy = (goal != null && !goal.finished()) || TripRunner.busy() || !"idle".equals(BountyRunner.state) || askLine != null;
        if (pf.getBattle() != null && !BattleConfig.auto && pf.getBattle() != alertedBattle && d == null) {
            alertedBattle = pf.getBattle();
            EventLog.alert("battle", "a battle started and auto-battle is off: fight it on screen, or send 'battle auto on'");
            Governor.stop("battle not handled by auto-battle");
        }
        if (d == null || d == expectedDialog || busy || d == alertedDialog) return;
        if (now - lastDialogTry < 1200L) return;
        lastDialogTry = now;
        DialogAgent.Result r = DialogAgent.apply(d, DialogAgent.ctx("idle"), null, null);
        if (r != null && !r.paused) { EventLog.info("dialog", r.text); return; }
        alertedDialog = d;
        EventLog.alert("dialog", (r != null ? r.text : "unrecognised dialog") + "\n" + DialogAgent.describe(d, DialogAgent.ctx("idle"), null, null)
                + "\nanswer with 'choose <n>', or add a rule to dialog_rules.txt");
        Governor.stop("unrecognised dialog: " + DialogAgent.targetName(d));
    }

    private long lastDialogTry = 0L;

    // ------------------------------------------------------------------ self-test

    static String selfTest() {
        StringBuilder b = new StringBuilder("Claude Captain " + Files.VERSION + " self-test\n");
        b.append(CaptainHud.selfTest()).append("\n");
        b.append(Governor.describe()).append("\n");
        DialogRules r = DialogAgent.rules();
        b.append(r.errors.isEmpty() ? "OK   " : "WARN ").append("dialog rules: ").append(r.rules.size()).append(" rules, ").append(r.errors.size()).append(" bad lines\n");
        b.append("     events: last #").append(EventLog.core.lastSeq()).append(", ").append(EventLog.core.lineCount()).append(" lines kept\n");
        b.append("     goal: ").append(goal == null ? "none" : goal.line() + " [" + goal.state + "]").append("\n");
        b.append("     ").append(TripRunner.describe()).append("\n     ").append(BountyRunner.status()).append("\n");
        b.append("     auto-battle ").append(BattleConfig.auto ? "ON" : "OFF").append(", cheats ").append(Rules.b("allow_cheats", false) ? "ON" : "off");
        return b.toString();
    }
}
