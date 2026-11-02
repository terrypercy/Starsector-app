package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The game side of the dialog rules: reads the open dialog, builds its signature, asks DialogRules
 * (reloaded live from dialog_rules.txt) and clicks the chosen option the way a mouse click does.
 */
public final class DialogAgent {

    private static final Logger log = Global.getLogger(DialogAgent.class);
    private static DialogRules rules = DialogRules.parse(DialogRules.DEFAULTS);
    private static String rulesText = null;
    private static long checkedAt = 0L;

    private DialogAgent() {}

    /** The dialog Claude resumed on: its 'pause' rules are skipped from now on (no pause loop). */
    public static volatile InteractionDialogAPI resumedOn;

    public static void resumedHere() {
        try { resumedOn = Global.getSector().getCampaignUI().getCurrentInteractionDialog(); } catch (Throwable ignored) {}
    }

    public static List<String> ctx(String... tags) { return new ArrayList<String>(Arrays.asList(tags)); }

    public static final class Result {
        public String clicked, text = "";
        public boolean paused, looted;
    }

    /** The current rules, re-read from the file every 3 seconds (created with the defaults when missing). */
    public static synchronized DialogRules rules() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < 3000L && rulesText != null) return rules;
        checkedAt = now;
        try {
            String t;
            if (Global.getSettings().fileExistsInCommon(Files.DIALOG_RULES)) t = Global.getSettings().readTextFileFromCommon(Files.DIALOG_RULES);
            else { t = DialogRules.DEFAULTS; Global.getSettings().writeTextFileToCommon(Files.DIALOG_RULES, t); }
            if (!t.equals(rulesText)) {
                DialogRules r = DialogRules.parse(t);
                boolean first = rulesText == null;
                rulesText = t;
                rules = r;
                if (!r.errors.isEmpty()) EventLog.warn("rules", "dialog_rules.txt: " + r.errors.size() + " bad line(s) skipped\n" + join(r.errors, "\n"));
                else if (!first) EventLog.info("rules", "dialog_rules.txt reloaded: " + r.rules.size() + " rules");
            }
        } catch (Throwable t) {
            log.warn("Claude Captain: dialog rules not readable", t);
        }
        return rules;
    }

    static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() > 0 ? sep : "").append(s);
        return b.toString();
    }

    static List<DialogRules.Opt> opts(List<AutoDialog.Opt> in) {
        List<DialogRules.Opt> out = new ArrayList<DialogRules.Opt>();
        for (AutoDialog.Opt o : in) out.add(new DialogRules.Opt(o.text, o.enabled, o.confirm));
        return out;
    }

    static String type(SectorEntityToken t) {
        if (t == null) return "other";
        if (t instanceof JumpPointAPI) return "jump";
        if (t instanceof CampaignFleetAPI) return "fleet";
        if (t.getMarket() != null && !t.getMarket().isPlanetConditionMarketOnly()) return "market";
        if (t.hasTag(Tags.DEBRIS_FIELD)) return "debris";
        if (t.hasTag(Tags.SALVAGEABLE)) {
            String ce = t.getCustomEntityType() == null ? "" : t.getCustomEntityType().toLowerCase();
            if (ce.contains("wreck") || ce.contains("derelict")) return "derelict";
            if (t.hasTag(Tags.STATION) || ce.contains("station")) return "station";
            return "salvage";
        }
        if (t instanceof PlanetAPI) return "planet";
        return "other";
    }

    /** Does jumping here take us towards goal (into its system, or out to hyperspace from the wrong system)? */
    static boolean usefulJump(SectorEntityToken it, SectorEntityToken goal) {
        try {
            if (!(it instanceof JumpPointAPI) || goal == null) return false;
            JumpPointAPI jp = (JumpPointAPI) it;
            if (jp.getDestinations() == null || jp.getDestinations().size() != 1) return false;
            SectorEntityToken dest = jp.getDestinations().get(0).getDestination();
            LocationAPI dl = dest == null ? null : dest.getContainingLocation();
            LocationAPI want = goal.getContainingLocation();
            LocationAPI here = Global.getSector().getPlayerFleet().getContainingLocation();
            return dl != null && (dl == want || (dl.isHyperspace() && here != want));
        } catch (Throwable t) { return false; }
    }

    public static String signature(InteractionDialogAPI d, List<AutoDialog.Opt> o, SectorEntityToken goal, SectorEntityToken bounty) {
        SectorEntityToken t = d.getInteractionTarget();
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        StringBuilder b = new StringBuilder();
        String type = type(t);
        b.append("type=").append(type).append(" target=").append(t == null ? "?" : t.getName());
        String fac = "none";
        boolean hostile = false, stronger = false, illegal = false;
        try {
            if (t != null && t.getFaction() != null) { fac = t.getFaction().getId(); illegal = AutoDialog.illegalAboard(t.getFaction()) != null; }
            if (t instanceof CampaignFleetAPI) {
                CampaignFleetAPI f = (CampaignFleetAPI) t;
                hostile = f.isHostileTo(pf);
                stronger = BountyRunner.dpOf(f) * Rules.f("hunt_margin", 1.5f) > BountyRunner.ourDP();
            } else if (t != null && t.getFaction() != null) hostile = t.getFaction().isHostileTo("player");
        } catch (Throwable ignored) {}
        b.append(" faction=").append(fac).append(" hostile=").append(DialogRules.yn(hostile));
        b.append(" useful=").append(DialogRules.yn(usefulJump(t, goal)));
        boolean scan = false;
        for (AutoDialog.Opt x : o) { String l = x.text.toLowerCase(); if (l.contains("allow the scan") || l.contains("and comply") || l.startsWith("comply")) scan = true; }
        b.append(" scan=").append(DialogRules.yn(scan)).append(" illegal=").append(DialogRules.yn(illegal));
        b.append(" bounty=").append(DialogRules.yn(bounty != null && t == bounty)).append(" stronger=").append(DialogRules.yn(stronger));
        b.append(" salvage=").append(DialogRules.yn(t != null && (t.hasTag(Tags.SALVAGEABLE) || t.hasTag(Tags.DEBRIS_FIELD))));
        b.append(" loot=").append(DialogRules.yn(LootTracker.open()));
        boolean goalHere = goal != null && t != null && (t == goal || (t.getMarket() != null && goal.getMarket() == t.getMarket()));
        b.append(" target_goal=").append(DialogRules.yn(goalHere));
        // 1.1.5 (Nov 2): own_recover=yes when one of OUR disabled ships is on the recovery list (else only enemy hulks)
        boolean recoverOffered = false;
        for (AutoDialog.Opt x : o) if (x.text.toLowerCase().startsWith("consider ship recovery")) recoverOffered = true;
        if (recoverOffered) b.append(" own_recover=").append(DialogRules.yn(Recovery.ownRecoverable(d) != 0));
        b.append(' ').append(DialogRules.optsField(opts(o)));
        try {
            List<String> paras = DialogBridge.paragraphs(d, 3);
            String txt = join(paras, " / ").replace('\n', ' ');
            if (txt.length() > 300) txt = txt.substring(txt.length() - 300);
            b.append(" text=").append(txt);
        } catch (Throwable ignored) {}
        return b.toString();
    }

    /**
     * Applies the first fitting rule. Returns null when no rule fits (the caller decides: its own code,
     * or pause and report an unknown dialog).
     */
    public static Result apply(InteractionDialogAPI d, List<String> ctx, SectorEntityToken goal, SectorEntityToken bounty) {
        if (d == null) return null;
        List<AutoDialog.Opt> o = AutoDialog.options(d);
        String sig = signature(d, o, goal, bounty);
        DialogRules.Decision dec = rules().decide(ctx, sig, opts(o), LootTracker.open(), d != resumedOn);
        if (dec == null) return null;
        Result r = new Result();
        if (dec.action.equals("pause")) {
            r.paused = true;
            r.text = (dec.rule.note.length() > 0 ? dec.rule.note : "dialog rule says pause") + " (" + targetName(d) + ")";
            return r;
        }
        if (dec.action.equals("loot")) {
            try { r.text = LootTracker.takeByValue(); r.looted = true; r.clicked = "loot"; }
            catch (Throwable t) { r.paused = true; r.text = "loot screen could not be taken: " + t.getMessage(); }
            EventLog.info("loot", r.text);
            return r;
        }
        if (dec.action.equals("pursue_auto")) {
            if (!Rules.b("pursuit_autoresolve", true)) {
                // the player turned it off: take command of the pursuit instead
                for (AutoDialog.Opt x : o) if (x.enabled && !x.confirm && x.text.toLowerCase().startsWith("take command of the action")) {
                    if (AutoDialog.click(d, x)) { r.clicked = x.text; r.text = "chose '" + x.text + "' (pursuit_autoresolve = false)"; return r; }
                }
            }
            try { r.text = Pursuit.autoresolve(d); r.clicked = "pursue_auto"; EventLog.info("battle", r.text); }
            catch (Throwable t) { r.paused = true; r.text = "pursuit autoresolve failed: " + t.getMessage(); }
            return r;
        }
        AutoDialog.Opt pick = o.get(dec.option);
        if (!AutoDialog.click(d, pick)) { r.paused = true; r.text = "click on '" + pick.text + "' failed"; return r; }
        r.clicked = pick.text;
        String tn = targetName(d);
        r.text = (sig.startsWith("type=jump") && dec.rule.source.contains("useful=yes") ? "jumped at " : "chose '" + pick.text + "' at ") + tn
                + " (rule " + (dec.ruleIndex + 1) + ")";
        return r;
    }

    static String targetName(InteractionDialogAPI d) {
        SectorEntityToken t = d == null ? null : d.getInteractionTarget();
        return t == null ? "a dialog" : t.getName();
    }

    /** Everything about an open dialog, for the 'unknown dialog' event: signature, text and numbered options. */
    public static String describe(InteractionDialogAPI d, List<String> ctx, SectorEntityToken goal, SectorEntityToken bounty) {
        try {
            List<AutoDialog.Opt> o = AutoDialog.options(d);
            return "context " + ctx + "\nsig " + signature(d, o, goal, bounty) + "\n" + CommandRunner.describeDialog(d, 6);
        } catch (Throwable t) { return "dialog not readable: " + t; }
    }
}
