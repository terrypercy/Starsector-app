package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Answers the routine dialogs that stop a trip, the way the player would (rule auto_dialogs):
 * - jump-points and gravity wells: jump only when the jump leads where the trip is going, else Leave;
 * - patrol hails asking for a cargo scan: comply when nothing aboard is illegal for that faction;
 * - a hostile fleet that won't let you go (rule when_attacked = disengage): Attempt to disengage, never engage;
 * - "Continue" screens and neutral encounters (Leave), and a pirate's comm link (Cut the comm link).
 * Anything else, any option that costs a story point or needs a confirmation, and contraband aboard
 * during a scan are left to the player (the trip pauses).
 *
 * The public API can't list dialog options, so options are read and clicked through the game's option
 * panel class (Starsector 0.98a-RC8; compiled against stubs/, same as Claude Bridge's DialogBridge).
 * The game forbids java.lang.reflect in mod code, so there is no reflection here.
 */
public final class AutoDialog {

    private static final Logger log = Global.getLogger(AutoDialog.class);

    private AutoDialog() {}

    static final class Opt {
        String text = "";
        Object data;
        boolean enabled = true, confirm;
        Object item;
    }

    /** What happened. */
    static final class Act {
        final String clicked, note;
        final boolean disengage, jump;
        Act(String clicked, String note, boolean disengage, boolean jump) {
            this.clicked = clicked; this.note = note; this.disengage = disengage; this.jump = jump;
        }
    }

    // ------------------------------------------------------------------ reading options

    static List<Opt> options(InteractionDialogAPI d) {
        List<Opt> out = new ArrayList<Opt>();
        if (d == null) return out;
        try {
            for (DialogBridge.Opt bo : DialogBridge.options(d)) {
                if (bo.text == null) continue;
                Opt o = new Opt();
                o.item = bo;
                o.text = bo.text;
                o.data = bo.data;
                o.enabled = bo.enabled;
                o.confirm = DialogBridge.needsConfirm(d, bo);
                out.add(o);
            }
        } catch (Throwable t) {
            log.warn("Claude Captain: could not read dialog options", t);
        }
        return out;
    }

    /** Clicks an option through the same handler a mouse click uses. */
    static boolean click(InteractionDialogAPI d, Opt o) {
        try {
            DialogBridge.click(d, (DialogBridge.Opt) o.item);
            return true;
        } catch (Throwable t) {
            log.warn("Claude Captain: option click failed", t);
            return false;
        }
    }

    static String describe(List<Opt> opts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < opts.size(); i++) {
            Opt o = opts.get(i);
            b.append(i == 0 ? "" : " | ").append(o.text).append(o.enabled ? "" : " [disabled]").append(o.confirm ? " [needs confirmation]" : "");
        }
        return b.toString();
    }

    private static Opt find(List<Opt> opts, String prefix) {
        for (Opt o : opts) if (o.enabled && !o.confirm && o.text.toLowerCase().startsWith(prefix)) return o;
        return null;
    }

    private static boolean only(List<Opt> opts, String... allowed) {
        for (Opt o : opts) {
            boolean ok = false;
            String t = o.text.toLowerCase();
            for (String a : allowed) if (t.startsWith(a)) ok = true;
            if (!ok) return false;
        }
        return !opts.isEmpty();
    }

    // ------------------------------------------------------------------ decisions

    /**
     * Decides and clicks one option. Returns null when this dialog should be left to the player
     * (the caller pauses with {@link #lastWhy}).
     */
    static String lastWhy = "";

    /** How long a dialog may show no options before it counts as stuck. */
    public static final long EMPTY_GRACE_MS = 5000L;
    private static Object emptyDialog = null;
    private static long emptySince = 0L;

    public static boolean emptyGraceOver(long since, long now) { return now - since >= EMPTY_GRACE_MS; }

    /**
     * Restarts the empty-dialog grace. Called on 'dialog close' and on every resume: Nov 2 live, a resume sent
     * with 'dialog close' saw the closing dialog with the old timer and paused the hunt again at once.
     */
    public static void resetEmpty() { emptyDialog = null; emptySince = 0L; }

    static Act handle(InteractionDialogAPI d, SectorEntityToken tripTarget) { return handle(d, tripTarget, null, null); }

    static Act handle(InteractionDialogAPI d, SectorEntityToken tripTarget, List<String> ctx, SectorEntityToken bountyTarget) {
        lastWhy = "";
        List<Opt> opts = options(d);
        if (opts.isEmpty()) {
            // 1.0.9: a salvage loot screen hides the options - take the loot by the rules (Nov 1: 'Begin salvage
            // operations' paused the hunt twice: once on the loot screen, once on the empty dialog it left behind)
            if (LootTracker.open()) {
                DialogAgent.Result lr = DialogAgent.apply(d, ctx == null ? DialogAgent.ctx("travel") : ctx, tripTarget, bountyTarget);
                if (lr != null && !lr.paused) { emptyDialog = null; return new Act(lr.clicked, lr.text, false, false); }
                if (lr != null) { lastWhy = lr.text; return null; }
            }
            // a dialog closing or switching screens shows no options for a moment: wait before calling it stuck
            long now = System.currentTimeMillis();
            if (emptyDialog != d) { emptyDialog = d; emptySince = now; }
            if (!emptyGraceOver(emptySince, now)) return new Act(null, "", false, false);
            lastWhy = "no readable options for " + (EMPTY_GRACE_MS / 1000L) + " s";
            return null;
        }
        emptyDialog = null;
        Act esc = spEscape(d, opts);
        if (esc != null) return esc;
        // data-driven rules first (dialog_rules.txt); the code below is the built-in fallback
        DialogAgent.Result rr = DialogAgent.apply(d, ctx == null ? DialogAgent.ctx("travel") : ctx, tripTarget, bountyTarget);
        if (rr != null) {
            if (rr.paused) { lastWhy = rr.text; return null; }
            return new Act(rr.clicked, rr.text, rr.clicked != null && rr.clicked.toLowerCase().contains("disengage"), rr.text.startsWith("jumped"));
        }
        SectorEntityToken it = d.getInteractionTarget();
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();

        // jump-points and gravity wells
        if (it instanceof JumpPointAPI) {
            JumpPointAPI jp = (JumpPointAPI) it;
            boolean useful = false;
            try {
                if (jp.getDestinations() != null && jp.getDestinations().size() == 1 && tripTarget != null) {
                    SectorEntityToken dest = jp.getDestinations().get(0).getDestination();
                    LocationAPI dl = dest == null ? null : dest.getContainingLocation();
                    LocationAPI want = tripTarget.getContainingLocation();
                    LocationAPI here = pf.getContainingLocation();
                    useful = dl != null && (dl == want || (dl.isHyperspace() && here != want));
                }
            } catch (Throwable ignored) {}
            if (useful) {
                float cost = Math.max(1f, Math.round(pf.getLogistics().getFuelCostPerLightYear()));
                if (pf.getCargo().getFuel() < cost) { lastWhy = "not enough fuel to use " + it.getName(); return null; }
                Opt o = find(opts, "turn the transponder on and then jump");
                if (o == null) o = find(opts, "order a jump");
                if (o == null) o = find(opts, "jump");
                if (o != null && click(d, o)) return new Act(o.text, "jumped at " + it.getName() + " (" + o.text + ")", false, true);
                lastWhy = "jump options not understood: " + describe(opts);
                return null;
            }
            Opt leave = find(opts, "leave");
            if (leave != null && click(d, leave)) return new Act(leave.text, it.getName() + " isn't on the route: left it", false, false);
            lastWhy = it.getName() + " isn't on the route and has no Leave option";
            return null;
        }

        // fleet encounters (patrol hails, pirates, neutral fleets)
        if (it instanceof CampaignFleetAPI) {
            CampaignFleetAPI other = (CampaignFleetAPI) it;
            Opt scan = find(opts, "allow the scan");
            if (scan == null) scan = find(opts, "turn the transponder on and comply");
            if (scan != null) {
                String bad = illegalAboard(other.getFaction());
                if (bad != null) { lastWhy = other.getName() + " wants a cargo scan and " + bad + " is illegal for " + other.getFaction().getDisplayName(); return null; }
                if (click(d, scan)) return new Act(scan.text, other.getName() + " (" + other.getFaction().getDisplayName() + ") scan: complied, hold is legal", false, false);
            }
            Opt dis = find(opts, "attempt to disengage");
            if (dis != null) {
                String mode = Rules.s("when_attacked", "disengage").toLowerCase();
                if (!mode.startsWith("disengage")) { lastWhy = other.getName() + " won't let you go (when_attacked = " + mode + ")"; return null; }
                if (click(d, dis)) return new Act(dis.text, other.getName() + " (" + other.getFaction().getDisplayName() + ") blocked the way: attempting to disengage", true, false);
            }
            Opt cont = find(opts, "continue");
            if (cont != null && only(opts, "continue", "transfer command", "crash-mothball")) {
                if (click(d, cont)) return new Act(cont.text, "continued (" + other.getName() + ")", false, false);
            }
            Opt cut = find(opts, "cut the comm link");
            if (cut != null && only(opts, "cut the comm link")) {
                if (click(d, cut)) return new Act(cut.text, "cut the comm link with " + other.getName(), false, false);
            }
            Opt leave = find(opts, "leave");
            if (leave != null && only(opts, "open a comm link", "move in to engage", "leave", "attempt to", "disengage", "continue",
                    "pick through the wreckage", "perform a salvage", "consider your")) {
                if (click(d, leave)) return new Act(leave.text, "left " + other.getName(), false, false);
            }
            lastWhy = other.getName() + " encounter not understood: " + describe(opts);
            return null;
        }

        // anything else: only a lone Continue is safe
        Opt cont = find(opts, "continue");
        if (cont != null && only(opts, "continue")) {
            if (click(d, cont)) return new Act(cont.text, "continued", false, false);
        }
        lastWhy = (it == null ? "a dialog" : it.getName()) + ": " + describe(opts);
        return null;
    }

    /**
     * 1.1.29 (ADR-0004): a hostile fleet at least sp_auto_escape_ratio x our strength has caught us and offers the
     * 1-story-point "special maneuvers" escape: buy it (never below sp_auto_escape_keep points) instead of pausing.
     * Nov 4 live: three IX Battlegroup intercepts near Danu (161, 161, 465 DP vs our 86) were each escaped by hand.
     */
    static Act spEscape(InteractionDialogAPI d, List<Opt> opts) {
        try {
            SectorEntityToken it = d.getInteractionTarget();
            if (!(it instanceof CampaignFleetAPI)) return null;
            CampaignFleetAPI other = (CampaignFleetAPI) it;
            CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
            if (!other.getFaction().isHostileTo(pf.getFaction())) return null;
            Opt sp = null;
            for (Opt o : opts) if (o.enabled && o.text.toLowerCase().startsWith("disengage by executing a series of special maneuvers")) sp = o;
            if (sp == null) return null;
            float enemy = 0f;
            com.fs.starfarer.api.campaign.BattleAPI b = other.getBattle();
            if (b != null && b.getOtherSideFor(pf) != null) for (CampaignFleetAPI f : b.getOtherSideFor(pf)) enemy += BountyRunner.dpOf(f);
            if (enemy <= 0f) enemy = BountyRunner.dpOf(other);
            float ours = BountyRunner.ourDP();
            int have = Global.getSector().getPlayerStats().getStoryPoints();
            int cost = DialogBridge.storyPointCost((DialogBridge.Opt) sp.item);
            String why = SpMath.escapeBlocked(Rules.b("sp_auto_escape", false), enemy, ours, Rules.f("sp_auto_escape_ratio", 2f), have, cost,
                    Math.round(Rules.f("sp_auto_escape_keep", 10f)));
            if (why != null) { EventLog.info("dialog", other.getName() + ": no automatic story-point escape (" + why + ")"); return null; }
            if (!DialogBridge.spendStoryPoint(d, (DialogBridge.Opt) sp.item)) return null;
            String note = other.getName() + " (" + Math.round(enemy) + " DP vs our " + Math.round(ours) + ") caught us: spent 1 story point on the special-maneuvers escape ("
                    + (have - cost) + " left)";
            EventLog.warn("dialog", note);
            return new Act(sp.text, note, true, false);
        } catch (Throwable t) {
            log.warn("Claude Captain: story-point escape failed", t);
            return null;
        }
    }

    /** The first good in the hold that this faction treats as illegal, or null. */
    static String illegalAboard(FactionAPI f) {
        try {
            for (CargoStackAPI s : Global.getSector().getPlayerFleet().getCargo().getStacksCopy()) {
                if (!s.isCommodityStack() || s.getSize() < 1f) continue;
                if (f.isIllegal(s.getCommodityId())) return s.getDisplayName();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
