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
      + "gov_supply_guard = true       # suspend CR recovery when it would empty the hold within gov_supply_guard_days; resume when supplies cover it\n"
      + "gov_supply_guard_days = 7\n"
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
      + "asteroid_guard = true        # 1.1.18: go slow inside asteroid belts/fields - the game deals no impact damage to slow fleets\n"
      + "asteroid_guard_lookahead_s = 1.5  # start slowing this many seconds before entering a belt\n"
      + "storm_guard_lookahead_s = 2.5 # how far ahead (seconds of travel) to look for storm cells\n"
      + "transverse_in = true          # 1.0.7: enter deep-space systems (no jump points) with Transverse Jump at the nascent gravity well\n"
      + "transverse_out = true         # 1.0.7: leave them the same way (costs fuel and a little CR)\n"
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
      + "keep_moving = false           # LOCKED: a paused trip is never unpaused by the mod (the player's pause wins)\n"
      + "fuel_reserve_ly = 10\n"
      + "supplies_days = 60\n"
      + "end_fuel_ly = 20\n"
      + "remote_trust = 0.6\n"
      + "loop_skip_recent = 2\n"
      + "show_trips = 3\n"
      + "\n"
      + "# --- bounty hunting\n"
      + "hunt_margin = 1.25            # LOCKED (player's choice, Nov 2: back to 1.25x after 1.1x cost the fleet vs Shepherd): hunt only targets whose DP x this are under ours\n"
      + "hunt_min_cr = 0.6             # rest first when a combat ship is under this CR\n"
      + "hunt_rest_cr = 0.7            # after a fight, rest near a friendly market until this CR\n"
      + "hunt_max_ly = 20              # skip bounties farther than this\n"
      + "battle_fresh_cr = 0.5         # 1.1.12: auto-battle sends ships under this combat readiness only if the healthy ones fall short\n"
      + "battle_fresh_hull = 0.5       # ...and the same for hull (Nov 2: Star Ark went in at 30% CR / 40% hull)\n"
      + "battle_doctrine = waves       # 1.1.16 (player): 'waves' = two-wave opener/core deploy with capture, escort, retreat and flux orders; 'classic' = the old single deploy\n"
      + "battle_wave1_max_dp = 104     # wave 1 (cappers, Monitors, Eradicators) never above this, and never above the game's own cap\n"
      + "battle_wave2_after_s = 60     # 1.1.17: the core wave drops when wave 1 holds half the objectives or is losing; this is only the late fallback\n"
      + "battle_wave1_losing_below = 0.75  # wave 1 is 'losing' under this share of its starting strength (DP x hull)\n"
      + "battle_line_arrived = 0.75    # search and destroy once this share of wave 2 is at the front...\n"
      + "battle_line_radius = 3000     # ...'the front' = within this many su of the map centre\n"
      + "battle_line_formed_s = 60     # fallback: seconds after wave 2 before search and destroy anyway\n"
      + "battle_retreat_hull = 0.4     # ships under this hull fraction retreat to free DP for reserves\n"
      + "battle_retreat_weapons_hull = 0.75  # most weapons destroyed for good only pulls a ship out once its hull is under this\n"
      + "battle_full_assault_ratio = 1.5 # 1.1.14: order Full Assault only when our deployed DP is at least this x theirs (0 = never); else the AI holds its line\n"
      + "hunt_rest_en_route = true     # 1.1.10 (player, Nov 2): skip the rest stop when readiness recovers during the trip to the next target\n"
      + "hunt_rest_en_route_factor = 1.0 # trip days x this must cover the recovery days\n"
      + "hunt_deadline_mult = 1.3      # 1.0.7: skip a timed bounty unless travel days x this + the extra days fit in the time left\n"
      + "hunt_deadline_extra_days = 3  # ...days allowed for finding and catching the fleet\n"
      + "hunt_min_supply_days = 30     # supplies needed to start a hunt (maintenance burn)\n"
      + "hunt_abort_supply_days = 12   # stop travelling to a target below this\n"
      + "hunt_recovery_pause = true    # stop at 'Consider ship recovery' so Claude can choose\n"      + "pursuit_autoresolve = true    # post-battle pursuits go to the second-in-command (player's rule, Nov 2)\n"
      + "pursuit_min_cr = 0.5          # ships under this CR stay out of a pursuit\n"
      + "pursuit_strength = 2.0        # send the fastest ready ships until their DP reach this x the enemy's fleet points\n"
      + "hunt_auto_resupply = true     # resupply/repair automatically between targets\n"
      + "battle_auto_on_load = false   # true: auto-battle is on after every game load (else send 'battle auto on')\n"
      + "hunt_war = true               # 1.1.20 (player, Nov 3: war for income): also hunt fleets in hostile factions' systems (commission per-kill bounty + loot)\n"
      + "hunt_war_factions = all       # which hostile factions count (all, or a list like hegemony, persean)\n"
      + "hunt_group_radius = 2500      # 1.1.20: a target is judged with every hostile fleet/station within this many su (Corvus: 72 DP target, 26 ships joined)\n"
      + "black_market_banned_factions = tritachyon # LOCKED (player's rule, Nov 3): never trade on a black market at these factions' markets\n"
      + "black_market_with_transponder = true # LOCKED (player's choice, Nov 1 late: allowed with the transponder on). 1.0.7: true lets buyship/autofit/sellship trade on a black market with the transponder on (costs standing)\n"
      + "battle_deploy =               # 1.0.7: default deploy list (auto | all | ship, ship); 'battle deploy ...' is also kept in the save\n"
      + "recover_api = false           # 'recover <ship>' through the game's recovery code (experimental, see README)\n"
      + "\n"
      + "# --- exploration while hunting\n"
      + "explore = true                # detour to undefended salvage while hunting/resting\n"
      + "explore_detour_su = 4000      # longest detour (extra distance) for one site\n"
      + "explore_visit_max_days = 5    # 1.0.7: give up on one site after this many days\n"
      + "explore_stall_days = 2        # 1.0.7: ...or when it hasn't got 200 su closer in this many days\n"
      + "explore_min_supply_days = 25  # never explore below this many days of maintenance supplies\n"
      + "explore_skip_defended = true  # skip defended sites unless our DP covers the defenders x hunt_margin\n"
      + "explore_survey = false        # survey planets on the way (the survey screen can't be driven yet)\n"
      + "keep = metals:50, heavy machinery:50  # never sold below this many (a bare name keeps all of it)\n"
      + "require_since = true          # LOCKED: Claude's batches cite the last event read ('#since <n>')\n"
      + "ask_before_supplies = false   # LOCKED (player's choice, Nov 2: 'make sure fuel and supplies stay topped up' - ADR-0002): automatic restocks buy supplies, still under the price ceiling\n"
      + "supply_price_factor = 1.35    # supplies ceiling = median price at friendly markets x this\n"
      + "fuel_price_factor = 1.5       # LOCKED (ADR-0002, Nov 2): fuel ceiling = median fuel price x this (was 1.35: Tartessus fuel at 34.4 vs a 33.6 ceiling stalled a hunt)\n"
      + "max_supply_price = 0          # fixed ceiling instead of the market one (0 = follow the market)\n"
      + "max_fuel_price = 0\n"
      + "emergency_price_factor = 2    # LOCKED: an emergency buy never pays more than this x the median price\n"
      + "emergency_fuel_ly = 10        # below this much fuel (or gov_min_supply_days of supplies) the ceiling is waived, with an alert\n"
      + "hunt_eta_factor = 2           # real trips take this many times the game's ETA (storms)\n"
      + "hunt_battle_supply_days = 20  # extra supply days a hunt keeps for CR recovery after the fight\n"
      + "explore_max_sites = 3         # sites per system visit\n"
      + "\n"
      + "# --- panel\n"
      + "hud_fold_key = F8             # folds/unfolds the Captain panel\n"
      + "hud_cycle_key = F9            # opens the next section (fleet, bounties, trades, explore)\n"
      + "hud_rep_faction = auto        # faction for the commission bar (auto = closest to Favorable)\n";

    private static final Map<String, String> vals = new HashMap<String, String>();
    /**
     * 1.0.9: guard rails the player set. Claude edits captain_rules.txt, so these are read from the built-in defaults
     * only; a value in the file is ignored (and logged). Changing one takes a new build the player installs.
     */
    public static final java.util.Set<String> LOCKED = new java.util.HashSet<String>(java.util.Arrays.asList(
            "allow_cheats", "recover_api", "require_since", "ask_before_supplies", "keep", "max_supply_price", "max_fuel_price",
            "supply_price_factor", "fuel_price_factor", "emergency_fuel_ly", "keep_moving", "hunt_eta_factor", "hunt_battle_supply_days",
            "emergency_price_factor", "hunt_margin", "black_market_with_transponder", "black_market_banned_factions"));
    private static boolean testOverride = false;
    private static final java.util.Set<String> lockedNoted = new java.util.HashSet<String>();
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
            Map<String, String> before = new HashMap<String, String>(vals);
            boolean first = before.isEmpty();
            if (first) { // 1.0.9: compare with the copy seen last time, so edits made while the game was closed are logged too
                try { if (s.fileExistsInCommon(Files.RULES_SEEN)) parseInto(s.readTextFileFromCommon(Files.RULES_SEEN), before); } catch (Throwable ignored) {}
            }
            parse(text);
            if (!before.isEmpty()) logChanges(before, vals);
            try { s.writeTextFileToCommon(Files.RULES_SEEN, text); } catch (Throwable ignored) {}
            noteLocked();
        } catch (Throwable t) {
            parse(DEFAULTS);
        }
    }

    /** 1.0.9: every change to captain_rules.txt goes into the event log as a WARN, so a changed guard rail is never silent. */
    static void logChanges(Map<String, String> before, Map<String, String> after) {
        String d = diff(before, after);
        if (d.length() > 0) try { EventLog.warn("rules", "captain_rules.txt changed:\n" + d); } catch (Throwable ignored) {}
    }

    /** "key: old -> new" per changed, added or removed key (pure, unit-tested). */
    public static String diff(Map<String, String> before, Map<String, String> after) {
        java.util.TreeSet<String> keys = new java.util.TreeSet<String>(before.keySet());
        keys.addAll(after.keySet());
        StringBuilder b = new StringBuilder();
        for (String k : keys) {
            String o = before.get(k), n = after.get(k);
            if (o == null ? n == null : o.equals(n)) continue;
            b.append(k).append(": ").append(o == null ? "(unset)" : o).append(" -> ").append(n == null ? "(removed)" : n).append("\n");
        }
        return b.toString().trim();
    }

    /** Logs once per key when the file tries to set a locked key to something else. */
    private static void noteLocked() {
        for (String k : LOCKED) {
            String v = vals.get(k), d = defs.get(k);
            if (v == null || v.length() == 0 || v.equals(d) || lockedNoted.contains(k + "=" + v)) continue;
            lockedNoted.add(k + "=" + v);
            try { EventLog.warn("rules", "ignored '" + k + " = " + v + "' in captain_rules.txt: locked guard rail (built-in value " + (d == null ? "(code default)" : d) + ")"); } catch (Throwable ignored) {}
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
    public static synchronized void useText(String text) { parse(text); loadedAt = Long.MAX_VALUE / 2; testOverride = true; }

    public static String s(String key, String def) {
        load();
        String v;
        synchronized (Rules.class) {
            v = LOCKED.contains(key) && !testOverride ? null : vals.get(key);
            if (v == null || v.length() == 0) v = defs.get(key);
        }
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
