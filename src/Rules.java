package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;

import java.util.HashMap;
import java.util.Map;

/**
 * Player-editable settings in saves/common/claude_captain/captain_rules.txt
 * (one "key = value" per line, # starts a comment). Created with the defaults
 * below on first run, re-read every few seconds, never overwritten afterwards.
 * Keys missing from an older file fall back to the defaults in code.
 *
 * This one file replaces Claude Bridge's advisor_rules.txt and Trade Runner's trade_rules.txt;
 * every key of both is still read under the same name.
 */
public final class Rules {

    private Rules() {}

    public static final String DEFAULTS =
        "# Claude Captain settings. Edit while the game runs; changes apply within a few seconds.\n"
      + "# Lines are 'key = value'. Delete this file to get the defaults back.\n"
      + "\n"
      + "# --- safety governor (always on while Claude is in control)\n"
      + "gov_min_supply_days = 15      # pause when supplies cover fewer days of MAINTENANCE burn than this\n"
      + "gov_fuel_margin_pct = 25      # fuel must reach the nearest friendly market plus this %...\n"
      + "gov_fuel_margin_ly = 2        # ...plus this many light years\n"
      + "gov_min_cr = 40               # pause when a combat ship's CR drops under this % (unless the plan is resting/repairing)\n"
      + "gov_threat_ratio = 1.0        # pause when a visible hostile fleet stronger than this x our DP closes in\n"
      + "gov_implicit_max_days = 15    # a batch that starts a goal without a 'run' line runs until done, at most this many days (0 = never implicit)\n"
      + "gov_until_max_days = 60       # cap for 'run until ...'\n"
      + "gov_unpause_days = 3          # 'unpause' grants this many days\n"
      + "gov_arm_on_load = false       # true: Claude is in control right after loading a save (game stays paused until 'run')\n"
      + "gov_state_seconds = 5         # how often state.txt is refreshed (real seconds)\n"
      + "events_keep_lines = 2000      # events.txt keeps about this many lines\n"
      + "\n"
      + "# --- supplies, fuel, repairs\n"
      + "storm_guard = true            # creep slowly through storming hyperspace cells (slow fleets are never struck)\n"
      + "storm_guard_lookahead_s = 2.5 # how far ahead (seconds of travel) to look for storm cells\n"
      + "resupply_days = 60            # 'resupply' buys this many days of maintenance burn\n"
      + "resupply_cr_budget = true     # ...plus the supplies needed to recover CR to max\n"
      + "resupply_max_credit_pct = 25  # never spend more than this % of credits on one resupply\n"
      + "resupply_fuel_ly = 25         # 'resupply' also fills fuel for this many light years\n"
      + "repair_hull_pct = 90          # a ship under this hull % (or under max CR) counts as needing the dockyard\n"
      + "\n"
      + "# --- advisor thresholds (from Claude Bridge)\n"
      + "fuel_low_pct = 30            # fuel below this % of capacity (or below the next trip) is flagged\n"
      + "supplies_low_days = 25       # fewer days of supplies than this is flagged\n"
      + "supplies_critical_days = 12  # ...and this many is red\n"
      + "supplies_target_days = 45    # restocking buys up to this many days\n"
      + "cr_rest_pct = 50             # rest when the lowest combat readiness is under this\n"
      + "cr_bad_pct = 40              # under this ships malfunction in battle (red)\n"
      + "cargo_full_pct = 92          # hold fuller than this suggests selling surplus\n"
      + "guard_ratio = 0.9            # skip objectives guarded by more than this x your fleet points\n"
      + "buy_budget_pct = 50          # most of your credits a single advisor purchase may use\n"
      + "trade_min_profit = 200       # smallest docked trade worth suggesting (credits)\n"
      + "trade_stale_days = 60        # prices older than this are marked 'old' and discounted\n"
      + "trade_ignore_days = 120      # prices older than this are ignored\n"
      + "bounty_max_ratio = 0.7       # only suggest bounty fleets up to this x your fleet points\n"
      + "save_before_action = auto    # auto (the game's autosave slot) | main | off\n"
      + "confirm_clicks = true        # click a suggestion once to select it, again to run it\n"
      + "voice = keymoments           # keymoments | off\n"
      + "claude_advice_minutes = 30   # how long advice Claude pushes into the panel stays\n"
      + "allow_cheats = false         # true re-enables credits/cargo add/ship add/teleport/repair instant/xp/... commands\n"
      + "\n"
      + "# --- trading (from Trade Runner)\n"
      + "budget_pct = 80               # most of your credits one trip may spend on its buy order\n"
      + "reserve_credits = 5000        # always keep at least this many credits\n"
      + "min_profit = 1000             # smallest trip worth showing (credits, after fuel and supplies)\n"
      + "contraband = include          # include | sell_only (never buy it) | never\n"
      + "prefer_black_market = true    # sell on the black market unless the open market pays more\n"
      + "max_days = 60                 # skip trips longer than this\n"
      + "max_lines = 4                 # most different goods in one buy order\n"
      + "price_guard_pct = 60          # don't buy if the live profit has fallen below this % of the plan\n"
      + "rep_point_value = 2000        # credits one reputation point is worth when ranking risky trips\n"
      + "save_before_run = auto        # auto | main | off\n"
      + "restore_transponder = true    # transponder back on after a dark stop (never if that reveals you)\n"
      + "go_dark = independent_only    # never | independent_only | auto\n"
      + "auto_dialogs = true           # answer routine dialogs on the way (see dialog_rules.txt)\n"
      + "when_attacked = disengage     # disengage | pause\n"
      + "keep_moving = true            # a running trip left paused 15 s with no dialog unpauses (still inside the run budget)\n"
      + "fuel_reserve_ly = 10\n"
      + "supplies_days = 60\n"
      + "end_fuel_ly = 20\n"
      + "remote_trust = 0.6\n"
      + "loop_skip_recent = 2\n"
      + "show_trips = 3\n"
      + "\n"
      + "# --- bounty hunting\n"
      + "hunt_margin = 1.5             # hunt only targets whose DP x this are under ours\n"
      + "hunt_min_cr = 0.6             # rest first when a combat ship is under this CR\n"
      + "hunt_rest_cr = 0.7            # after a fight, rest near a friendly market until this CR\n"
      + "hunt_max_ly = 20              # skip bounties farther than this\n"
      + "hunt_min_supply_days = 30     # supplies needed to start a hunt (maintenance burn)\n"
      + "hunt_abort_supply_days = 12   # stop travelling to a target below this\n"
      + "hunt_recovery_pause = true    # stop at 'Consider ship recovery' so Claude can choose\n"
      + "hunt_auto_resupply = true     # resupply/repair automatically between targets\n"
      + "battle_auto_on_load = false   # true: auto-battle is on after every game load (else send 'battle auto on')\n"
      + "recover_api = false           # 'recover <ship>' through the game's recovery code (experimental, see README)\n"
      + "\n"
      + "# --- exploration while hunting\n"
      + "explore = true                # detour to undefended salvage while hunting/resting\n"
      + "explore_detour_su = 4000      # longest detour (extra distance) for one site\n"
      + "explore_min_supply_days = 25  # never explore below this many days of maintenance supplies\n"
      + "explore_skip_defended = true  # skip defended sites unless our DP covers the defenders x hunt_margin\n"
      + "explore_survey = true         # survey planets on the way when the option is offered\n"
      + "explore_max_sites = 3         # sites per system visit\n"
      + "\n"
      + "# --- panel\n"
      + "hud_fold_key = F8             # folds/unfolds the Captain panel\n"
      + "hud_cycle_key = F9            # opens the next section (fleet, bounties, trades, explore)\n"
      + "hud_rep_faction = auto        # faction for the commission bar (auto = closest to Favorable)\n";

    private static final Map<String, String> vals = new HashMap<String, String>();
    private static final Map<String, String> defs = new HashMap<String, String>();
    private static long loadedAt = 0L;

    static { parseInto(DEFAULTS, defs); }

    private static synchronized void load() {
        long now = System.currentTimeMillis();
        if (loadedAt != 0L && now - loadedAt < 5000L) return;
        loadedAt = now;
        try {
            SettingsAPI s = Global.getSettings();
            String text;
            if (s.fileExistsInCommon(Files.RULES)) {
                text = s.readTextFileFromCommon(Files.RULES);
            } else {
                text = DEFAULTS;
                try { s.writeTextFileToCommon(Files.RULES, DEFAULTS); } catch (Throwable ignored) {}
            }
            parse(text);
        } catch (Throwable t) {
            parse(DEFAULTS);
        }
    }

    static void parseInto(String text, Map<String, String> into) {
        into.clear();
        for (String raw : text.replace("\r", "").split("\n")) {
            String l = raw;
            int hash = l.indexOf('#');
            if (hash >= 0) l = l.substring(0, hash);
            int eq = l.indexOf('=');
            if (eq <= 0) continue;
            into.put(l.substring(0, eq).trim().toLowerCase(), l.substring(eq + 1).trim());
        }
    }

    static synchronized void parse(String text) { parseInto(text, vals); }

    /** For tests: use this text instead of the file. */
    public static synchronized void useText(String text) { parse(text); loadedAt = Long.MAX_VALUE / 2; }

    public static String s(String key, String def) {
        load();
        String v;
        synchronized (Rules.class) { v = vals.get(key); if (v == null || v.length() == 0) v = defs.get(key); }
        return v == null || v.length() == 0 ? def : v;
    }

    public static float f(String key, float def) {
        try { return Float.parseFloat(s(key, "")); } catch (Throwable t) { return def; }
    }

    public static boolean b(String key, boolean def) {
        String v = s(key, "").toLowerCase();
        if (v.equals("true") || v.equals("yes") || v.equals("on") || v.equals("1")) return true;
        if (v.equals("false") || v.equals("no") || v.equals("off") || v.equals("0")) return false;
        return def;
    }

    /** The trade planner's settings from the rules file. */
    public static Planner.Settings settings() {
        Planner.Settings st = new Planner.Settings();
        st.budgetPct = f("budget_pct", 80f);
        st.reserve = f("reserve_credits", 5000f);
        st.minProfit = f("min_profit", 1000f);
        st.maxDays = f("max_days", 60f);
        st.maxLines = (int) f("max_lines", 4f);
        String c = s("contraband", "include").toLowerCase();
        st.contraband = c.startsWith("never") ? 0 : c.startsWith("sell") ? 1 : 2;
        st.preferBlack = b("prefer_black_market", true);
        st.repValue = f("rep_point_value", 2000f);
        st.goDark = s("go_dark", "independent_only").toLowerCase();
        st.fuelReserveLy = f("fuel_reserve_ly", 10f);
        st.suppliesDays = f("supplies_days", 60f);
        st.remoteTrust = f("remote_trust", 0.6f);
        return st;
    }

    static synchronized String dump() {
        load();
        return vals.toString();
    }
}
