package claudecaptain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Data-driven dialog answers (claude_captain/dialog_rules.txt, re-read live). Pure: no game classes.
 *
 * Line format (first matching rule whose action can be carried out wins; # comments):
 *     <tags> : match <regex> -> choose <option regex>
 *     <tags> : match <regex> -> pause [note]
 *     <tags> : match <regex> -> loot
 * tags: comma list of contexts (travel, hunt, engage, after_battle, pursuit, market, explore, repair,
 * hire, commission, trade, idle) or * for any. The match regex is searched (case-insensitive) in the
 * dialog signature the game side builds, for example:
 *     type=fleet target=Pirate Raiders faction=pirates hostile=yes useful=no scan=no illegal=no
 *     bounty=yes stronger=no salvage=no loot=no opts=[Move in to engage|Attempt to disengage|Leave] text=...
 * The option regex is searched in each option's text; options that are disabled or cost a story point /
 * need a confirmation are never chosen.
 */
public final class DialogRules {

    public static final String DEFAULTS =
        "# Claude Captain dialog rules. Edited live: changes apply within a few seconds, no restart.\n"
      + "# <tags> : match <regex> -> choose <option regex> | pause [note] | loot\n"
      + "# The match regex is searched in the dialog signature (see 'dialog sig' or events after an unknown dialog):\n"
      + "#   type=jump|fleet|market|planet|salvage|debris|station|other target=.. faction=.. hostile=yes|no useful=yes|no\n"
      + "#   scan=yes|no illegal=yes|no bounty=yes|no stronger=yes|no salvage=yes|no loot=yes|no opts=[a|b|c] text=...\n"
      + "# First rule (top to bottom) whose action can be done wins. Story-point / confirm options are never clicked.\n"
      + "\n"
      + "# --- loot screens: take everything that fits, best value per cargo unit first\n"
      + "* : match loot=yes -> loot\n"
      + "\n"
      + "# --- jump points and gravity wells on the route (useful = the jump leads where the goal is going)\n"
      + "* : match type=jump .*useful=yes -> choose ^(Turn the transponder on and then jump|Order a jump|Jump|Use the)\n"
      + "* : match type=jump .*useful=no -> choose ^Leave\n"
      + "\n"
      + "# --- patrols: comply with a scan when nothing aboard is illegal for them\n"
      + "* : match scan=yes .*illegal=no -> choose ^(Allow the scan|Turn the transponder on and comply|Comply)\n"
      + "* : match scan=yes .*illegal=yes -> pause contraband aboard and a patrol wants to scan\n"
      + "\n"
      + "# --- after a battle\n"
      + "# derelicts found while exploring are salvaged, not recovered (player's call, Nov 1)\n"
      + "explore : match type=derelict .*opts=\\[[^\\]]*Consider ship recovery -> choose ^Not now$\n"
      + "after_battle,hunt : match opts=\\[[^\\]]*Consider ship recovery -> pause ship recovery: 'recover list' / 'recover <ship>' / 'hunt resume'\n"
      + "after_battle,hunt,explore : match .* -> choose ^Pick through the wreckage\n"
      + "\n"
      + "# --- bounty targets and fights we chose\n"
      + "hunt,engage : match bounty=yes .*stronger=no -> choose ^(Move in to engage|Engage|Attack|Pursue them|Pursue|Go for the kill|Continue the battle)\n"
      + "pursuit,hunt,engage : match .* -> choose ^Take command of the action\n"
      + "\n"
      + "# --- exploration: salvage, debris, derelicts, surveys\n"
      + "explore : match opts=\\[[^\\]]*(Engage the|Attack the|automated defen) -> choose ^(Leave|Move away|Withdraw)\n"
      + "explore : match type=(salvage|debris|station|derelict) -> choose ^(Explore$|Assess$|Assess the|Begin salvage operations|Perform a salvage operation|Salvage|Search the debris|Take the cargo|Pick through)\n"
      + "explore : match type=planet -> choose ^(Run a survey|Conduct a survey|Survey|Take a closer look)\n"
      + "explore : match .* -> choose ^(Continue|Leave)$\n"
      + "\n"
      + "# --- dockyard repair, officers, commissions (sub-dialogs)\n"
      + "repair : match .* -> choose ^(Repair your ships|Confirm|Pay|Have the repairs done|Proceed)\n"
      + "hire : match .* -> choose ^(Hire|Offer|Accept|Agree|Sign)\n"
      + "commission : match .* -> choose (?i)(commission|Accept|Sign|Agree)\n"
      + "\n"
      + "# --- hostile fleets that block the way when we are not hunting them\n"
      + "# 1.0.7: pause instead of trying to disengage (a failed auto-disengage cost two ships); Claude picks the option\n"
      + "travel,trade,idle,explore,repair,resupply : match type=fleet .*hostile=yes .*opts=\\[[^\\]]*(engage|Move away) -> pause hostile fleet intercept: Claude picks the option\n"
      + "\n"
      + "# --- routine screens\n"
      + "* : match opts=\\[Continue\\] -> choose ^Continue$\n"
      + "* : match type=fleet .*opts=\\[Cut the comm link\\] -> choose ^Cut the comm link\n"
      + "* : match type=fleet .*hostile=no -> choose ^(Cut the comm link|Leave)\n"
      + "* : match type=fleet -> choose ^Continue$\n"
      + "travel,hunt,explore,resupply,repair,trade : match type=market .*target_goal=no -> choose ^Leave$\n";

    public static final class Rule {
        public final String[] tags;
        public final Pattern match;
        public final String action;   // choose | pause | loot
        public final Pattern option;   // for choose
        public final String note, source;
        Rule(String[] tags, Pattern match, String action, Pattern option, String note, String source) {
            this.tags = tags; this.match = match; this.action = action; this.option = option; this.note = note; this.source = source;
        }
        boolean appliesTo(List<String> ctx) {
            for (String t : tags) {
                if (t.equals("*")) return true;
                for (String c : ctx) if (c.equalsIgnoreCase(t)) return true;
            }
            return false;
        }
    }

    public static final class Opt {
        public final String text;
        public final boolean enabled, confirm;
        public Opt(String text, boolean enabled, boolean confirm) { this.text = text == null ? "" : text; this.enabled = enabled; this.confirm = confirm; }
    }

    public static final class Decision {
        public final String action;
        public final int option;     // index into the option list (choose)
        public final Rule rule;
        public final int ruleIndex;
        Decision(String action, int option, Rule rule, int ruleIndex) { this.action = action; this.option = option; this.rule = rule; this.ruleIndex = ruleIndex; }
    }

    public final List<Rule> rules = new ArrayList<Rule>();
    public final List<String> errors = new ArrayList<String>();

    public static DialogRules parse(String text) {
        DialogRules d = new DialogRules();
        if (text == null) return d;
        int n = 0;
        for (String raw : text.replace("\r", "").split("\n")) {
            n++;
            String l = raw.trim();
            if (l.length() == 0 || l.startsWith("#")) continue;
            try {
                int colon = l.indexOf(':');
                int mi = l.indexOf("match ");
                if (colon < 0 || mi < 0 || mi < colon) throw new IllegalArgumentException("expected '<tags> : match <regex> -> <action>'");
                String[] tags = l.substring(0, colon).trim().toLowerCase(Locale.ROOT).split("\\s*,\\s*");
                int arrow = l.lastIndexOf("->");
                if (arrow < mi) throw new IllegalArgumentException("missing '->'");
                String m = l.substring(mi + 6, arrow).trim();
                String act = l.substring(arrow + 2).trim();
                Pattern mp = Pattern.compile(m, Pattern.CASE_INSENSITIVE);
                String verb = act.split("\\s+")[0].toLowerCase(Locale.ROOT);
                String arg = act.length() > verb.length() ? act.substring(verb.length()).trim() : "";
                if (verb.equals("choose")) {
                    if (arg.length() == 0) throw new IllegalArgumentException("choose needs an option regex");
                    d.rules.add(new Rule(tags, mp, "choose", Pattern.compile(arg, Pattern.CASE_INSENSITIVE), "", raw));
                } else if (verb.equals("pause") || verb.equals("loot")) {
                    d.rules.add(new Rule(tags, mp, verb, null, arg, raw));
                } else throw new IllegalArgumentException("unknown action '" + verb + "' (choose, pause, loot)");
            } catch (PatternSyntaxException e) {
                d.errors.add("line " + n + ": bad regex: " + e.getDescription());
            } catch (IllegalArgumentException e) {
                d.errors.add("line " + n + ": " + e.getMessage());
            }
        }
        return d;
    }

    /**
     * The first rule that applies to ctx, matches sig and can be carried out.
     * lootOpen: a loot screen is up (only then can 'loot' be done).
     */
    public Decision decide(List<String> ctx, String sig, List<Opt> opts, boolean lootOpen) { return decide(ctx, sig, opts, lootOpen, true); }

    /** allowPause = false skips 'pause' rules (the dialog was already paused on and Claude said resume). */
    public Decision decide(List<String> ctx, String sig, List<Opt> opts, boolean lootOpen, boolean allowPause) {
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (!r.appliesTo(ctx) || !r.match.matcher(sig).find()) continue;
            if (r.action.equals("pause")) { if (allowPause) return new Decision("pause", -1, r, i); continue; }
            if (r.action.equals("loot")) { if (lootOpen) return new Decision("loot", -1, r, i); continue; }
            for (int k = 0; k < opts.size(); k++) {
                Opt o = opts.get(k);
                if (!o.enabled || o.confirm) continue;
                if (r.option.matcher(o.text).find()) return new Decision("choose", k, r, i);
            }
        }
        return null;
    }

    /** "opts=[a|b|c]" part of a signature. */
    public static String optsField(List<Opt> opts) {
        StringBuilder b = new StringBuilder("opts=[");
        for (int i = 0; i < opts.size(); i++) b.append(i == 0 ? "" : "|").append(opts.get(i).text);
        return b.append(']').toString();
    }

    public static String yn(boolean b) { return b ? "yes" : "no"; }
}
