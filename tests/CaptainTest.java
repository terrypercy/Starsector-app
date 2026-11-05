import claudecaptain.*;

import java.util.*;

/** Unit tests for Claude Captain's pure logic: governor, supply sizing, dialog rules, events, reputation, exploration, panel rows, JSON. */
public class CaptainTest {
    static int passes = 0, fails = 0;
    static void check(boolean c, String m) { if (c) passes++; else { fails++; System.out.println("FAIL: " + m); } }
    static boolean near(float a, float b) { return Math.abs(a - b) < 0.01f; }

    public static void main(String[] args) {
        governor();
        supplies();
        dialogRules();
        events();
        reputation();
        explore();
        hud();
        json();
        storm();
        travel();
        guard();
        surveyTargets();
        System.out.println("CaptainTest: " + passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ governor

    static GovMath.Input base() {
        GovMath.Input in = new GovMath.Input();
        in.supplies = 300f; in.maintPerDay = 5f;           // 60 days
        in.fuel = 500f; in.fuelPerLy = 5f; in.nearestFriendlyLy = 10f; in.nearestFriendlyName = "Jangala";
        in.lowestCombatCR = 0.7f; in.lowestCRShip = "ISS Test";
        in.ourDP = 60f;
        return in;
    }

    static boolean has(List<GovMath.Alarm> l, String prefix) { for (GovMath.Alarm a : l) if (a.key.startsWith(prefix)) return true; return false; }

    static void governor() {
        check(near(GovMath.supplyDays(150f, 10f), 15f), "supply days = supplies / maintenance");
        check(GovMath.supplyDays(10f, 0f) > 1000f, "no burn = no supply limit");
        check(GovMath.supplyTier(20f, 15f) == 0 && GovMath.supplyTier(14f, 15f) == 1 && GovMath.supplyTier(7f, 15f) == 2
                && GovMath.supplyTier(3f, 15f) == 3 && GovMath.supplyTier(0f, 15f) == 4, "supply tiers 0-4");
        check(near(GovMath.fuelNeeded(10f, 5f, 25f, 2f), (12.5f + 2f) * 5f), "fuel needed = (ly*(1+margin)+ly margin)*fuel/ly");
        check(GovMath.fuelNeeded(-1f, 5f, 25f, 2f) == 0f, "unknown market distance: no fuel alarm");

        check(GovMath.check(base()).isEmpty(), "healthy fleet: no alarms");
        GovMath.Input in = base(); in.supplies = 70f; // 14 days
        check(has(GovMath.check(in), "supplies:1"), "14 days of maintenance < 15: supply alarm");
        in = base(); in.fuel = 60f;
        check(has(GovMath.check(in), "fuel"), "fuel short of the nearest friendly market + margin: alarm");
        in.docked = true;
        check(!has(GovMath.check(in), "fuel"), "docked: no fuel alarm");
        in = base(); in.lowestCombatCR = 0.35f;
        check(has(GovMath.check(in), "cr"), "CR 35% < 40%: alarm");
        in.lowCrExpected = true;
        check(!has(GovMath.check(in), "cr"), "resting by plan: low CR expected, no alarm");
        in = base(); in.threatDP = 90f; in.threatName = "Raiders"; in.threatClosing = true;
        check(has(GovMath.check(in), "threat"), "stronger hostile closing: alarm");
        in.threatClosing = false;
        check(!has(GovMath.check(in), "threat"), "stronger hostile not closing: no alarm");
        in = base(); in.threatDP = 40f; in.threatClosing = true;
        check(!has(GovMath.check(in), "threat"), "weaker hostile closing: no alarm");
        in = base(); in.worstHullDropOutsideBattle = 0.1f; in.incidentShip = "ISS X";
        check(has(GovMath.check(in), "incident:hull"), "hull loss outside battle: incident alarm");
        in = base(); in.shipsLostOutsideBattle = 1;
        check(has(GovMath.check(in), "incident:lost"), "ship gone outside battle: incident alarm");

        // latch: fire once, ack silences, escalation re-fires, clearing forgets
        GovMath.Latch l = new GovMath.Latch();
        in = base(); in.supplies = 70f;
        check(l.update(GovMath.check(in)).size() == 1, "latch: first time fires");
        check(l.update(GovMath.check(in)).isEmpty(), "latch: same alarm does not fire twice");
        l.ack();
        check(l.update(GovMath.check(in)).isEmpty(), "latch: acknowledged alarm stays quiet");
        in.supplies = 30f; // 6 days -> tier 2
        check(l.update(GovMath.check(in)).size() == 1, "latch: worse supply tier fires again");
        in = base();
        check(l.update(GovMath.check(in)).isEmpty(), "latch: cleared");
        in.supplies = 70f;
        check(l.update(GovMath.check(in)).size() == 1, "latch: comes back after clearing -> fires");
        GovMath.Input inc = base(); inc.worstHullDropOutsideBattle = 0.05f;
        check(l.update(GovMath.check(inc)).size() >= 1 && has(l.update(GovMath.check(inc)), "incident"), "latch: every incident is news");

        // budget
        GovMath.Budget b = new GovMath.Budget();
        check(!b.active(), "budget starts empty");
        b.grant(3f, null);
        check(b.active() && !b.consume(1f) && near(b.daysLeft, 2f), "budget consumes days");
        check(b.consume(2.5f) && !b.active() && b.daysLeft == 0f, "budget reports the moment it runs out");
        check(!b.consume(1f), "an empty budget doesn't report twice");
        b.grant(5f, "arrived");
        check(b.describe().contains("until arrived"), "budget describes its condition");
        Object[] p = GovMath.parseRun("3", 60f);
        check(near((Float) p[0], 3f) && p[1] == null, "run 3");
        p = GovMath.parseRun("2.5d", 60f);
        check(near((Float) p[0], 2.5f), "run 2.5d");
        p = GovMath.parseRun("until arrived", 60f);
        check(near((Float) p[0], 60f) && "arrived".equals(p[1]), "run until arrived (capped by gov_until_max_days)");
        p = GovMath.parseRun("until event max 10", 60f);
        check(near((Float) p[0], 10f) && "event".equals(p[1]), "run until event max 10");
        boolean threw = false;
        try { GovMath.parseRun("forever", 60f); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "run with nonsense is refused");
        threw = false;
        try { GovMath.parseRun("until lunch", 60f); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "run until <unknown> is refused");
        p = GovMath.parseRun("until done 3", 60f);
        check(near((Float) p[0], 3f) && "done".equals(p[1]), "run until done 3 = max 3 (1.0.1 granted 60)");
        p = GovMath.parseRun("until arrived 5d", 60f);
        check(near((Float) p[0], 5f), "run until arrived 5d");
        batches();
        chase();
        routine();
    }

    static void routine() {
        check(GovMath.routine("supplies:2", true, 0f, 0.4f), "supplies alarm on the way to a market: routine");
        check(!GovMath.routine("supplies:4", false, 0f, 0.4f), "supplies gone with no market goal: pause");
        check(GovMath.routine("fuel", true, 0f, 0.4f), "fuel alarm on the way to a market: routine");
        check(GovMath.routine("incident:hull", false, 0.22f, 0.4f), "storm scrape 22%: routine");
        check(!GovMath.routine("incident:hull", false, 0.6f, 0.4f), "big hull loss: pause");
        check(!GovMath.routine("incident:lost", true, 0f, 0.4f), "ship lost: pause");
        check(!GovMath.routine("threat:Pirates", true, 0f, 0.4f), "threat: pause");
        check(!GovMath.routine("cr", true, 0f, 0.4f), "low CR: pause");
    }

    static void chase() {
        String d = ChaseMath.decide(300f, 800f, true, false, true, false, true, false, false, false);
        check(ChaseMath.PULSE.equals(d), "pulse inside range: " + d);
        d = ChaseMath.decide(300f, 800f, true, false, true, true, true, false, false, false);
        check(ChaseMath.SB_OFF.equals(d), "sustained burn off first so the pulse can fire: " + d);
        d = ChaseMath.decide(300f, 800f, true, true, true, false, true, false, false, false);
        check(ChaseMath.NONE.equals(d), "pulse charging: wait");
        d = ChaseMath.decide(500f, 800f, false, false, true, false, true, false, false, false);
        check(ChaseMath.EB.equals(d), "pulse on cooldown, not closing, close: emergency burn: " + d);
        d = ChaseMath.decide(500f, 800f, false, false, true, false, true, false, false, true);
        check(ChaseMath.NONE.equals(d), "emergency burn only once per chase: " + d);
        d = ChaseMath.decide(3000f, 800f, false, false, true, false, true, false, false, false);
        check(ChaseMath.SB_ON.equals(d), "far and not closing: sustained burn: " + d);
        d = ChaseMath.decide(3000f, 800f, false, false, true, false, true, false, true, false);
        check(ChaseMath.NONE.equals(d), "closing anyway: leave it: " + d);
        check(near(SupplyMath.fuelLy(25f, 67f, 40f, 10f), 10f), "expensive fuel: only 10 ly");
        check(near(SupplyMath.fuelLy(25f, 26f, 40f, 10f), 25f), "cheap fuel: full 25 ly");
        check(near(SupplyMath.fuelBudget(24000f, 5000f, 25f), 6000f), "fuel budget capped at 25% of credits");
        // 1.1.19 (player rule, Nov 3): the hunt restock bought on Culann's (Tri-Tachyon) black market
        check(SupplyMath.listed("tritachyon", "tritachyon"), "Tri-Tachyon black market banned");
        check(SupplyMath.listed("pirates, TriTachyon", "tritachyon"), "banned list: case and spacing ignored");
        check(!SupplyMath.listed("tritachyon", "independent"), "independent black market still allowed");
        check(!SupplyMath.listed("", "tritachyon"), "empty list bans nothing");
        // 1.1.20: Corvus, Jan 31 c216 - convoy 72 DP with patrol 64, picket 55, detachment 118 nearby, escort convoy 215 at 2443 su
        check(near(WarMath.groupDP(72f, new float[]{64f, 55f, 118f, 215f, 100f}, new float[]{300f, 400f, 600f, 2443f, 4000f}, 2500f), 524f), "Corvus convoy counts its neighbours (not the far station)");
        check(near(WarMath.groupDP(40f, new float[]{60f}, new float[]{5000f}, 2500f), 40f), "lone patrol is judged alone");
        check(WarMath.warTarget("hegemony", true, "all"), "any hostile faction is a war target");
        check(!WarMath.warTarget("independent", false, "all"), "non-hostile faction is never a war target");
        check(!WarMath.warTarget("hegemony", true, "persean"), "war list limits targets");
        check(near(SupplyMath.fuelBudget(6000f, 5000f, 25f), 1000f), "fuel budget keeps the reserve");
    }

    static java.util.List<String> L(String... a) { return java.util.Arrays.asList(a); }

    static void batches() {
        check(BatchMath.GOV.equals(BatchMath.kind("run 2", false)), "run 2 is a governor line");
        check(BatchMath.GOAL.equals(BatchMath.kind("run trip 1", false)), "run trip 1 is a goal");
        check(BatchMath.NOW.equals(BatchMath.kind("cancel", false)), "cancel acts at arrival");
        check(BatchMath.GOAL.equals(BatchMath.kind("dock Tartessus", true)), "dock <place> is a goal");
        check(BatchMath.INSTANT.equals(BatchMath.kind("dock", true)), "bare dock while docked is instant");
        check(BatchMath.GOAL.equals(BatchMath.kind("dock", false)), "bare dock in space is a goal");
        check(BatchMath.INSTANT.equals(BatchMath.kind("hunt stop", false)), "hunt stop is instant");
        check(BatchMath.GOAL.equals(BatchMath.kind("hunt auto 3", false)), "hunt auto is a goal");
        check(BatchMath.GOAL.equals(BatchMath.kind("optional resupply", false)), "optional prefix is ignored");
        check(BatchMath.INSTANT.equals(BatchMath.kind("buy fuel 20", true)), "buy is instant");
        check(BatchMath.GOAL.equals(BatchMath.kind("buy supplies 20", true)), "buy supplies waits for the player's approval (1.0.9)");
        String[] need = {"resupply 60", "buy supplies 20 black", "optional sellship Umbra black confirm", "buyship Wyrm", "restore Wyrm", "buyspec hardened shields", "hire officer steady", "hunt resume anyway"};
        for (String x : need) check(BatchMath.needsApproval(x), "needs approval: " + x);
        String[] free = {"sellship Umbra", "buy fuel max", "hunt resume", "bounties", "preflight Morgan", "sell metals 100 black"};
        for (String x : free) check(!BatchMath.needsApproval(x), "no approval: " + x);
        check(BatchMath.INSTANT.equals(BatchMath.kind("repair instant", true)), "repair instant is not the dockyard goal");
        // the 1.0.1 deadlock: goal paused, new batch = cancel + dock + instants
        java.util.List<java.util.List<String>> s = BatchMath.split(L("cancel", "run 2", "dock Tartessus", "storage put crew 388", "status"), true, false);
        check(s.get(0).equals(L("cancel", "run 2")), "cancel and run act at arrival: " + s.get(0));
        check(s.get(1).equals(L("dock Tartessus", "storage put crew 388", "status")), "the rest queues in order: " + s.get(1));
        check(!s.get(2).isEmpty(), "a batch with a goal replaces the unfinished goal");
        // status while travelling: runs now, goal untouched
        s = BatchMath.split(L("status", "events since 40"), true, false);
        check(s.get(0).equals(L("status", "events since 40")) && s.get(1).isEmpty() && s.get(2).isEmpty(), "instant-only batch runs at once and keeps the goal");
        // no goal running: everything queues as before
        s = BatchMath.split(L("status", "buy supplies 5"), false, true);
        check(s.get(0).isEmpty() && s.get(1).size() == 2 && s.get(2).isEmpty(), "idle: batch queues normally");
        s = BatchMath.split(L("resume"), true, false);
        check(s.get(0).equals(L("resume")) && s.get(2).isEmpty(), "resume acts at arrival without replacing");
    }

    // ------------------------------------------------------------------ supplies

    static void supplies() {
        // the live-play mistake: 629 supplies for 85k. Fleet maintenance 4/day, current burn 11/day while recovering CR.
        SupplyMath.Order o = SupplyMath.supplies(4f, 60f, 60f, 40f, 135f, 340000f, 25f, 1000f, -1f);
        check(near(o.want, 4f * 60f + 60f - 40f), "order = maintenance x days + CR budget - aboard (" + o.want + ")");
        check(o.qty < 629f && o.qty == o.want, "sized from maintenance, not from the inflated burn: " + o);
        o = SupplyMath.supplies(4f, 60f, 0f, 0f, 135f, 40000f, 25f, 1000f, -1f);
        check(o.cost <= 40000f * 0.25f + 0.5f && o.limitedBy.startsWith("credits"), "capped at 25% of credits: " + o);
        o = SupplyMath.supplies(4f, 60f, 0f, 0f, 10f, 1e6f, 25f, 100f, -1f);
        check(o.qty == 100f && o.limitedBy.equals("cargo room"), "capped by cargo room");
        o = SupplyMath.supplies(4f, 60f, 0f, 0f, 10f, 1e6f, 25f, 1000f, 50f);
        check(o.qty == 50f && o.limitedBy.equals("stock"), "capped by market stock");
        o = SupplyMath.supplies(4f, 60f, 0f, 500f, 10f, 1e6f, 25f, 1000f, -1f);
        check(o.qty == 0f, "enough aboard: buy nothing");
        check(near(SupplyMath.crRecovery(0.4f, 0.7f, 10f, 0.15f), 20f), "CR recovery: 2 deployments' worth = 2 x deployment supplies");
        check(SupplyMath.crRecovery(0.8f, 0.7f, 10f, 0.15f) == 0f, "no CR recovery above max");
        check(SupplyMath.fuel(25f, 4f, 30f, 500f) == 70f, "fuel for 25 ly minus aboard");
        check(SupplyMath.fuel(25f, 4f, 30f, 60f) == 30f, "fuel capped by the tank");
    }

    // ------------------------------------------------------------------ dialog rules

    static List<DialogRules.Opt> opts(String... t) {
        List<DialogRules.Opt> l = new ArrayList<DialogRules.Opt>();
        for (String s : t) {
            boolean dis = s.startsWith("-"), conf = s.startsWith("*");
            l.add(new DialogRules.Opt(dis || conf ? s.substring(1) : s, !dis, conf));
        }
        return l;
    }

    static String sig(String type, String extra, List<DialogRules.Opt> o) {
        return "type=" + type + " target=X faction=f hostile=no useful=no scan=no illegal=no bounty=no stronger=no salvage=no loot=no target_goal=no "
                .replace(extra.split("=")[0] + "=" + (extra.endsWith("yes") ? "no" : "yes"), extra) + DialogRules.optsField(o) + " text=";
    }

    static String chosen(DialogRules r, List<String> ctx, String sig, List<DialogRules.Opt> o) {
        DialogRules.Decision d = r.decide(ctx, sig, o, sig.contains("loot=yes"));
        if (d == null) return null;
        if (!d.action.equals("choose")) return d.action;
        return o.get(d.option).text;
    }

    static void dialogRules() {
        DialogRules r = DialogRules.parse(DialogRules.DEFAULTS);
        check(r.errors.isEmpty(), "default dialog rules parse cleanly " + r.errors);
        check(r.rules.size() >= 15, "defaults cover the routine dialogs (" + r.rules.size() + " rules)");
        List<String> travel = Arrays.asList("travel");
        List<String> hunt = Arrays.asList("hunt", "travel");

        // 1.0.9: live play - the hunt detoured to two derelict ships and left without salvaging; the game's button is "Explore"
        List<String> exploreCtx = Arrays.asList("explore", "hunt");
        List<DialogRules.Opt> wreck = opts("Explore", "Leave");
        check("Explore".equals(chosen(r, exploreCtx, sig("derelict", "useful=no", wreck), wreck)), "derelict: explore it, don't leave");
        List<DialogRules.Opt> debris = opts("Assess", "Leave");
        check("Assess".equals(chosen(r, exploreCtx, sig("debris", "useful=no", debris), debris)), "debris field: assess it");
        List<DialogRules.Opt> gs = opts("Assess the ship's salvage value", "Leave");
        check("Assess the ship's salvage value".equals(chosen(r, exploreCtx, sig("derelict", "useful=no", gs), gs)), "special wreck: assess salvage value");

        List<DialogRules.Opt> jump = opts("Order a jump to Askonia", "Leave");
        check("Order a jump to Askonia".equals(chosen(r, travel, sig("jump", "useful=yes", jump), jump)), "useful jump point: jump");
        check("Leave".equals(chosen(r, travel, sig("jump", "useful=no", jump), jump)), "jump point off the route: leave");
        List<DialogRules.Opt> tj = opts("Turn the transponder on and then jump", "Order a jump", "Leave");
        check("Turn the transponder on and then jump".equals(chosen(r, travel, sig("jump", "useful=yes", tj), tj)), "prefers transponder-on jump");

        List<DialogRules.Opt> scan = opts("Allow the scan", "Refuse");
        String s = sig("fleet", "scan=yes", scan);
        check("Allow the scan".equals(chosen(r, travel, s, scan)), "legal hold: comply with the scan");
        check("pause".equals(chosen(r, travel, s.replace("illegal=no", "illegal=yes"), scan)), "contraband aboard: pause");

        List<DialogRules.Opt> enc = opts("Open a comm link", "Move in to engage", "Attempt to disengage", "Leave");
        String hostile = sig("fleet", "hostile=yes", enc);
        check("pause".equals(chosen(r, travel, hostile, enc)), "travel: hostile blocks the way -> pause for Claude (1.0.7)");
        DialogRules.Decision afterResume = r.decide(travel, hostile, enc, false, false);
        check(afterResume == null || !"choose".equals(afterResume.action) || !enc.get(afterResume.option).text.equals("Move in to engage"),
                "travel: after resume nothing engages by itself");
        check("Move in to engage".equals(chosen(r, Arrays.asList("hunt", "engage"), hostile.replace("bounty=no", "bounty=yes"), enc)), "hunt: bounty target -> engage");
        check(chosen(r, Arrays.asList("hunt", "engage"), hostile.replace("bounty=no", "bounty=yes").replace("stronger=no", "stronger=yes"), enc) == null,
                "hunt: stronger than planned -> no rule (pause)");
        List<DialogRules.Opt> pursuit = opts("Take command of the action", "Order your second-in-command to handle it", "Let them go");
        check("pursue_auto".equals(chosen(r, Arrays.asList("pursuit", "hunt"), sig("fleet", "hostile=yes", pursuit), pursuit)), "pursuit: second-in-command (player's rule, Nov 2)");
        List<DialogRules.Opt> pursuit2 = opts("Take command of the action", "Let them go");
        check("Take command of the action".equals(chosen(r, Arrays.asList("pursuit", "hunt"), sig("fleet", "hostile=yes", pursuit2), pursuit2)), "pursuit: no second-in-command option -> take command");

        check("pursue_auto".equals(chosen(r, travel, sig("fleet", "hostile=yes", pursuit), pursuit)), "pursuit after a fight on the way: second-in-command too (Nov 2 live case)");
        List<DialogRules.Opt> wreckT = opts("Pick through the wreckage");
        check("Pick through the wreckage".equals(chosen(r, travel, sig("fleet", "hostile=yes", wreckT), wreckT)), "wreckage after a fight on the way: picked (Nov 2 live case)");
        List<DialogRules.Opt> flee = opts("Open a comm link", "Pursue them", "Maneuver to force a pitched battle", "Harry their retreat", "Let them go");
        check("Pursue them".equals(chosen(r, travel, sig("fleet", "hostile=yes", flee), flee)), "won fight, enemy fleeing: pursue (then the second-in-command takes it)");
        List<DialogRules.Opt> after = opts("Consider ship recovery", "Pick through the wreckage", "Continue");
        check("pause".equals(chosen(r, Arrays.asList("after_battle", "hunt"), sig("fleet", "hostile=yes", after), after)), "after battle with recovery: pause for Claude");
        String own = sig("fleet", "hostile=yes", after).replace("target_goal=no ", "target_goal=no own_recover=yes ");
        check("pause".equals(chosen(r, Arrays.asList("after_battle", "hunt"), own, after)), "after battle, one of ours recoverable: pause for Claude");
        String hulks = own.replace("own_recover=yes", "own_recover=no");
        check("Continue".equals(chosen(r, Arrays.asList("after_battle", "hunt"), hulks, after)), "after battle, only enemy hulks: skip recovery (Nov 2, every pirate fight)");
        DialogRules.Decision noPause = r.decide(Arrays.asList("after_battle", "hunt"), sig("fleet", "hostile=yes", after), after, false, false);
        check(noPause != null && after.get(noPause.option).text.equals("Pick through the wreckage"), "after 'resume' the recovery pause is skipped (no pause loop)");
        List<DialogRules.Opt> after2 = opts("Pick through the wreckage", "Continue");
        check("Pick through the wreckage".equals(chosen(r, Arrays.asList("after_battle"), sig("fleet", "hostile=yes", after2), after2)), "after battle: loot the wreckage");
        check("loot".equals(chosen(r, travel, sig("other", "loot=yes", opts()), opts())), "loot screen: take loot");

        List<DialogRules.Opt> cont = opts("Continue");
        check("Continue".equals(chosen(r, travel, sig("other", "x=y", cont), cont)), "lone Continue: continue");
        List<DialogRules.Opt> cut = opts("Cut the comm link");
        check("Cut the comm link".equals(chosen(r, travel, sig("fleet", "hostile=yes", cut), cut)), "comm link: cut");
        List<DialogRules.Opt> mkt = opts("Open the comm directory", "Trade goods", "Leave");
        check("Leave".equals(chosen(r, travel, sig("market", "x=y", mkt), mkt)), "travel: someone else's market -> leave");
        check(chosen(r, travel, sig("market", "target_goal=yes", mkt), mkt) == null, "travel: the goal's market is not left");
        check(chosen(r, Arrays.asList("idle"), sig("market", "x=y", mkt), mkt) == null, "idle: never leaves a market by itself");

        List<DialogRules.Opt> salv = opts("Perform a salvage operation", "Leave");
        check("Perform a salvage operation".equals(chosen(r, Arrays.asList("explore"), sig("derelict", "salvage=yes", salv), salv)), "explore: salvage");
        List<DialogRules.Opt> def = opts("Engage the automated defenses", "Leave");
        check("Leave".equals(chosen(r, Arrays.asList("explore"), sig("salvage", "salvage=yes", def), def)), "explore: defended -> leave");
        check(chosen(r, travel, sig("derelict", "salvage=yes", salv), salv) == null, "salvage is only touched in the explore context");

        // safety: disabled and story-point/confirm options are never chosen
        List<DialogRules.Opt> spOnly = opts("*Attempt to disengage", "-Leave");
        String spPick = chosen(r, travel, sig("fleet", "hostile=yes", spOnly), spOnly);
        check(spPick == null || "pause".equals(spPick), "never picks confirm/story-point or disabled options (got " + spPick + ")");
        DialogRules.Decision spResumed = r.decide(travel, sig("fleet", "hostile=yes", spOnly), spOnly, false, false);
        check(spResumed == null || !"choose".equals(spResumed.action), "...not even after a resume");

        // custom rules: tags, live edits, errors
        DialogRules c = DialogRules.parse("bar : match text=.*drink -> choose ^Buy a round\nbad line\n* : match ( -> choose x\n* : match x -> fly\n");
        check(c.rules.size() == 1 && c.errors.size() == 3, "bad lines are reported, good ones kept: " + c.errors);
        List<DialogRules.Opt> bar = opts("Buy a round", "Leave");
        check(chosen(c, Arrays.asList("bar"), "type=market text=have a drink " + DialogRules.optsField(bar), bar).equals("Buy a round"), "custom context rule");
        check(chosen(c, travel, "type=market text=have a drink", bar) == null, "rule tag must match the context");
    }

    // ------------------------------------------------------------------ events

    static void events() {
        EventCore e = new EventCore(60);
        long a = e.add("206.08.23", EventCore.INFO, "cmd", "b1", "> status\nline two\nline three");
        long b = e.add("206.08.23", EventCore.WARN, "gov", "b1", "supplies low");
        check(a == 1 && b == 2 && e.lastSeq() == 2, "sequence numbers count up");
        String since = e.since(1);
        check(since.startsWith("#2 ") && !since.contains("#1 "), "since(1) has only #2");
        check(e.since(0).contains("  | line two"), "continuation lines kept with their event");
        check(e.text().contains("b=b1"), "batch id tagged");
        for (int i = 0; i < 100; i++) e.add("d", EventCore.INFO, "x", null, "event " + i + "\nmore");
        check(e.lineCount() <= 60, "trimmed to ~keep lines (" + e.lineCount() + ")");
        check(EventCore.seqOf(e.text().split("\n")[0]) > 0, "trimming never leaves a half event at the top");
        check(e.lastSeq() == 102, "seq keeps counting after trimming");
        check(e.since(0).startsWith("#" + e.firstSeq() + " "), "since(0) starts at the oldest kept event");
        EventCore f = new EventCore(2000);
        f.load("  | orphan continuation\n" + e.text());
        check(f.lastSeq() == 102 && f.add("d", EventCore.INFO, "x", null, "after restart") == 103, "numbering continues after a restart");
        check(!f.text().startsWith("  |"), "a partial event at the top of the file is dropped");
        check(e.lastHeads(2).size() == 2 && e.lastHeads(2).get(1).startsWith("#102 "), "last heads newest last");
    }

    // ------------------------------------------------------------------ reputation

    static void reputation() {
        check(RepMath.toFavorable(4f, 10f) == 6f && RepMath.toFavorable(15f, 10f) == 0f, "distance to Favorable");
        check(RepMath.stipend(1, 20000f, 5000f) == 25000f && RepMath.stipend(10, 20000f, 5000f) == 70000f, "stipend = 20000 + 5000/level");
        check(near(RepMath.progress(-10f, 10f), 0f) && near(RepMath.progress(0f, 10f), 0.5f) && near(RepMath.progress(12f, 10f), 1f), "progress bar fraction");
        check(RepMath.bar(0.5f, 10).equals("[=====-----]"), "text bar");
        RepMath.Faction heg = new RepMath.Faction("hegemony", "Hegemony", 4f); heg.hasBounties = true; heg.deficits = 3;
        RepMath.Faction tt = new RepMath.Faction("tritachyon", "Tri-Tachyon", 8f); tt.hostile = true;
        RepMath.Faction ind = new RepMath.Faction("independent", "Independent", 25f);
        List<String> sg = RepMath.suggestions(heg, 10f);
        check(sg.get(0).contains("bounties") && sg.get(sg.size() - 1).contains("6 points"), "suggestions: bounties first, gap last " + sg);
        check(RepMath.suggestions(ind, 10f).get(0).contains("commission independent"), "enough standing: suggests the commission");
        check(RepMath.pickTarget(Arrays.asList(heg, tt, ind), 10f, null) == heg, "panel target: closest non-hostile below Favorable");
        check(RepMath.pickTarget(Arrays.asList(heg, tt, ind), 10f, "Tri-Tachyon") == tt, "panel target: rule override");
    }

    // ------------------------------------------------------------------ exploration

    static void explore() {
        check(near(ExploreMath.detour(0, 0, 0, 0, 100f, 0f), 0f), "site on the way: no detour");
        check(near(ExploreMath.detour(0, 0, 50, 0, 100f, 0f), 0f), "site on the line: no detour");
        check(near(ExploreMath.detour(0, 0, 300, 0, null, null), 600f), "no destination: there and back");
        ExploreMath.Site near = new ExploreMath.Site("a", "Wreck", "derelict", 1000, 0); near.salvage = true;
        ExploreMath.Site far = new ExploreMath.Site("b", "Far wreck", "derelict", 20000, 0); far.salvage = true;
        ExploreMath.Site def = new ExploreMath.Site("c", "Guarded", "station", 500, 0); def.salvage = true; def.defended = "yes"; def.defenderDP = 80f;
        ExploreMath.Site surv = new ExploreMath.Site("d", "Planet", "planet_unsurveyed", 200, 0); surv.survey = true;
        ExploreMath.Site ruins = new ExploreMath.Site("e", "Ruins", "ruins", 100, 0); ruins.ruins = true;
        List<ExploreMath.Site> all = Arrays.asList(near, far, def, surv, ruins);
        Set<String> none = new HashSet<String>();
        ExploreMath.Site p = ExploreMath.pick(all, 0, 0, null, null, 4000f, none, true, 60f, 1.5f, true);
        check(p == near, "picks the undefended salvage within the detour limit (got " + (p == null ? null : p.name) + ")");
        check(ExploreMath.pick(all, 0, 0, null, null, 4000f, new HashSet<String>(Arrays.asList("a")), true, 60f, 1.5f, true) == surv, "then the survey");
        check(ExploreMath.pick(all, 0, 0, null, null, 4000f, new HashSet<String>(Arrays.asList("a", "d")), true, 60f, 1.5f, true) == null, "defended and ruins are skipped");
        check(ExploreMath.pick(all, 0, 0, null, null, 4000f, new HashSet<String>(Arrays.asList("a", "d")), true, 200f, 1.5f, true) == def, "defended is fine when our DP covers it x margin");
        check(ExploreMath.pick(all, 0, 0, null, null, 4000f, new HashSet<String>(Arrays.asList("a")), true, 60f, 1.5f, false) == null, "explore_survey = false: no survey stops");
    }

    // ------------------------------------------------------------------ panel rows

    static void hud() {
        Hud.Data d = new Hud.Data();
        d.credits = 128400f; d.supplyDays = 12f; d.fuelLy = 18f; d.lowestCR = 0.72f; d.nearestFriendlyLy = 3f;
        d.goal = "Travel to Yesod - 2/4 jumps"; d.chip = "RUN 3.0d left"; d.chipLevel = 1;
        d.repFaction = "Hegemony"; d.repRel = 4f; d.repReq = 10f;
        d.suggestions.add("Resupply 60 days"); d.suggestions.add("Hunt Kade"); d.suggestions.add("Sell cargo"); d.suggestions.add("4th is dropped");
        d.fleet.add("Wolf|0.35|1.0"); d.fleet.add("Hound|0.8|0.4");
        d.open = 0;
        List<Hud.Row> r = Hud.rows(d);
        Hud.Row stats = r.get(1);
        check(stats.kind.equals("stats") && stats.parts.get(0).equals("128k cr"), "header: credits condensed");
        check(stats.partLevels.get(1) == Hud.L_BAD, "header: 12 days of supplies is red (min 15)");
        check(stats.partLevels.get(3) == Hud.L_GOOD, "header: CR 72% is green (min 40, amber under 60)");
        int buttons = 0, bars = 0;
        for (Hud.Row x : r) { if (x.kind.equals("button")) buttons++; if (x.kind.equals("bar")) bars++; }
        check(buttons == 3, "exactly three next steps");
        check(bars == 1, "commission bar shown");
        boolean fleetRed = false;
        for (Hud.Row x : r) if (x.text.contains("Wolf") && x.level == Hud.L_BAD) fleetRed = true;
        check(fleetRed, "open fleet section: low-CR ship red");
        d.open = -1;
        boolean anyFleet = false;
        for (Hud.Row x : Hud.rows(d)) if (x.text.contains("Wolf")) anyFleet = true;
        check(!anyFleet, "closed sections hide their items");
        d.folded = true;
        check(Hud.rows(d).size() == 3, "folded: title, stats, governor chip only");
        check(Hud.cycle(0) == 1 && Hud.cycle(3) == -1 && Hud.cycle(-1) == 0, "cycle key: fleet > bounties > trades > explore > none");
        check(Hud.money(1250000f).equals("1.3M") && Hud.money(980f).equals("980"), "money format");
        check(Hud.fuelLevel(4f, 3f) == Hud.L_BAD, "fuel barely reaching the nearest market is red");
    }

    // ------------------------------------------------------------------ json

    static void json() {
        Map<String, Object> o = Json.obj();
        o.put("a", 1);
        o.put("s", "q\"uote\nnl");
        o.put("f", 2.5f);
        o.put("n", null);
        List<Object> l = Json.arr(); l.add(1); l.add("x");
        o.put("l", l);
        o.put("e", Json.obj());
        String j = Json.write(o);
        check(j.contains("\"a\": 1") && j.contains("\"s\": \"q\\\"uote\\nnl\"") && j.contains("\"f\": 2.5") && j.contains("\"n\": null")
                && j.contains("[1, \"x\"]") && j.contains("\"e\": {}"), "json writer: " + j);
        check(Json.write(Float.NaN).equals("null"), "NaN -> null");
    }

    // ------------------------------------------------------------------ storm guard (1.0.6)

    static void storm() {
        com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin.CellStateTracker c =
            new com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin.CellStateTracker(0, 0, 1f, 1f);
        check(!claudecaptain.StormGuard.bad(null), "storm: no cell is safe");
        check(!claudecaptain.StormGuard.bad(c), "storm: waiting cell is safe");
        c.state = com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin.CellState.STORM;
        check(claudecaptain.StormGuard.bad(c), "storm: storming cell is avoided");
        c.state = com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin.CellState.STORM_WANE;
        check(claudecaptain.StormGuard.bad(c), "storm: waning storm is still avoided");
        c.state = com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin.CellState.SIGNAL;
        c.signal = 0.1f;
        check(claudecaptain.StormGuard.bad(c), "storm: signaling cell about to storm is avoided");
        c.signal = 0.8f;
        check(!claudecaptain.StormGuard.bad(c), "storm: early signaling cell is safe");
    }

    static void travel() {
        check(claudecaptain.TravelMath.jumps(true, false, false) == 0, "travel: same place, no jumps");
        check(claudecaptain.TravelMath.jumps(false, false, false) == 2, "travel: system to system = 2 jumps");
        check(claudecaptain.TravelMath.jumps(false, true, false) == 1, "travel: hyperspace to system = 1 jump");
        check(claudecaptain.TravelMath.jumps(false, false, true) == 1, "travel: system to hyperspace = 1 jump");
        check(near(claudecaptain.TravelMath.fuel(10f, 2, 45f), 540f), "fuel: 10 ly + 2 jumps at 45/ly = 540");
        check(near(claudecaptain.TravelMath.fuel(-1f, 0, 45f), 0f), "fuel: bad distance counts as 0");
        check(near(claudecaptain.TravelMath.fuelWithMargin(10f, 2, 5f, 25f, 2f), (12.5f + 2f + 2f) * 5f), "fuel margin: pct + flat + jumps");
        // 2000 su per ly, 10 s per day, 200 su/s -> 1 ly/day
        check(near(claudecaptain.TravelMath.etaDays(10f, 0, 200f, 2000f, 10f), 10f), "eta: 10 ly at 1 ly/day = 10 days");
        check(near(claudecaptain.TravelMath.etaDays(10f, 2, 200f, 2000f, 10f), 11f), "eta: jumps add half a day each");
        check(claudecaptain.TravelMath.etaDays(10f, 0, 0f, 2000f, 10f) > 1000f, "eta: unknown speed is never 'in time'");
        check(!claudecaptain.TravelMath.missesDeadline(0f, 50f, 1.3f, 3f), "deadline: no timer never misses");
        check(claudecaptain.TravelMath.missesDeadline(17f, 13f, 1.3f, 3f), "deadline: 13 days away x1.3 + 3 > 17 left -> skip (Gomez)");
        check(!claudecaptain.TravelMath.missesDeadline(76f, 10f, 1.3f, 3f), "deadline: 76 days left is plenty (Burris)");
        String T = claudecaptain.TravelMath.JUMP_IN, O = claudecaptain.TravelMath.JUMP_OUT, S = claudecaptain.TravelMath.STEER_WELL, N = claudecaptain.TravelMath.NONE;
        check(N.equals(claudecaptain.TravelMath.transverse(true, false, false, false, 10f, 50f, true)), "transverse: normal system uses jump points");
        check(S.equals(claudecaptain.TravelMath.transverse(true, true, false, false, 900f, 50f, true)), "transverse: deep space far away -> steer to the well");
        check(T.equals(claudecaptain.TravelMath.transverse(true, true, false, false, 20f, 50f, true)), "transverse: at the well -> jump in");
        check(S.equals(claudecaptain.TravelMath.transverse(true, true, false, false, 20f, 50f, false)), "transverse: at the well on cooldown -> wait there");
        check(N.equals(claudecaptain.TravelMath.transverse(true, true, false, false, -1f, 50f, true)), "transverse: no well known -> leave it to autopilot");
        check(O.equals(claudecaptain.TravelMath.transverse(false, false, true, false, -1f, 50f, true)), "transverse: leaving a deep-space system -> jump out");
        check(N.equals(claudecaptain.TravelMath.transverse(false, true, true, true, -1f, 50f, true)), "transverse: already there -> nothing");
        check(N.equals(claudecaptain.TravelMath.transverse(false, true, false, false, -1f, 50f, true)), "transverse: in a normal system -> use its jump point first");
    }

    // ------------------------------------------------------------------ 1.0.9 guard rails

    static void guard() {
        String rule = "metals:50, heavy machinery:50, Gauss Cannon";
        check(Guard.keepFloor(rule, "metals", "Metals") == 50, "keep: metals floor 50");
        // Nov 1 live case: salvage loot screen with no readable options, then the empty dialog left behind
        DialogRules dr = DialogRules.parse(DialogRules.DEFAULTS);
        List<DialogRules.Opt> none = new ArrayList<DialogRules.Opt>();
        String lootSig = "type=derelict target=Derelict Ship faction=neutral hostile=no useful=no scan=no illegal=no bounty=no stronger=no salvage=yes loot=yes target_goal=yes opts=[] text=Begin salvage operations";
        check("loot".equals(chosen(dr, Arrays.asList("explore", "after_battle"), lootSig, none)), "salvage loot screen with no options: loot is taken");
        // Nov 1: an acknowledged threat re-fired after every 'run' because "closing" flickered off while paused
        GovMath.Latch lt = new GovMath.Latch();
        GovMath.Input th = new GovMath.Input();
        th.supplies = 700f; th.maintPerDay = 7.7f; th.ourDP = 209f; th.threatDP = 238f; th.threatName = "Holy Armada"; th.threatClosing = true;
        check(lt.update(GovMath.check(th)).size() == 1, "threat alarm fires once");
        lt.ack();
        th.threatClosing = false; lt.update(GovMath.check(th));
        th.threatClosing = true;
        check(lt.update(GovMath.check(th)).isEmpty(), "acknowledged threat doesn't re-fire after a flicker");
        th.threatClosing = false;
        for (int i = 0; i < GovMath.Latch.THREAT_ACK_GRACE; i++) lt.update(GovMath.check(th));
        th.threatClosing = true;
        check(lt.update(GovMath.check(th)).size() == 1, "a threat that went quiet for long fires again");
        check(BatchMath.split(Arrays.asList("hunt resume anyway", "run until done max 40"), true, false).get(2).isEmpty(), "'hunt resume anyway' doesn't cancel the paused hunt");
        check(!BatchMath.split(Arrays.asList("hunt 3", "run until done max 40"), true, false).get(2).isEmpty(), "a new hunt still replaces the old goal");
        check(BatchMath.needsApproval("hunt 5 anyway") && !BatchMath.needsApproval("hunt 5"), "hunting a rejected bounty needs the player's Yes");
        // Nov 2: the threat alarm cleared while paused (before the 'run' arrived), so 'run' acknowledged nothing
        GovMath.Latch lt2 = new GovMath.Latch();
        GovMath.Input t2 = new GovMath.Input();
        t2.supplies = 600f; t2.maintPerDay = 7.7f; t2.ourDP = 209f; t2.threatDP = 238f; t2.threatName = "Holy Armada"; t2.threatClosing = true;
        check(lt2.update(GovMath.check(t2)).size() == 1, "threat fires");
        t2.threatClosing = false; lt2.update(GovMath.check(t2)); // flickers off while paused
        lt2.ack();                                                // then 'run' arrives
        t2.threatClosing = true;
        check(lt2.update(GovMath.check(t2)).isEmpty(), "a threat that lapsed before 'run' is still acknowledged by it");
        GovMath.Latch lt3 = new GovMath.Latch();
        check(lt3.update(GovMath.check(t2)).size() == 1, "fires");
        t2.threatClosing = false; lt3.update(GovMath.check(t2));
        t2.threatClosing = true;
        check(lt3.update(GovMath.check(t2)).isEmpty(), "an unacknowledged threat isn't re-announced after a flicker either");
        // Nov 4 live case (Danu): the game sat paused on the threat alarm for minutes while Claude read the state; the
        // lapsed threat aged out during the pause, so 'run' acknowledged nothing and the same convoy re-paused at once
        GovMath.Latch lt4 = new GovMath.Latch();
        GovMath.Input t4 = new GovMath.Input();
        t4.supplies = 600f; t4.maintPerDay = 3f; t4.ourDP = 86f; t4.threatDP = 237f; t4.threatName = "Supply Convoy"; t4.threatClosing = true;
        check(lt4.update(GovMath.check(t4), false).size() == 1, "convoy threat fires");
        t4.threatClosing = false;
        for (int i = 0; i < GovMath.Latch.THREAT_ACK_GRACE * 3; i++) lt4.update(GovMath.check(t4), true); // paused, waiting for Claude
        lt4.ack();
        t4.threatClosing = true;
        check(lt4.update(GovMath.check(t4), false).isEmpty(), "a threat that lapsed during a long pause is still acknowledged by 'run'");
        // Nov 4 (ADR-0004): three IX intercepts near Danu, each escaped by hand with 1 SP; the player allowed it automatically
        check(SpMath.escapeBlocked(true, 465f, 86f, 2f, 19, 1, 10) == null, "sp escape: 465 DP vs 86, 19 SP -> escape");
        check(SpMath.escapeBlocked(true, 161f, 86f, 2f, 19, 1, 10) != null, "sp escape: 161 vs 86 is under 2x -> Claude decides");
        check(SpMath.escapeBlocked(true, 465f, 86f, 2f, 10, 1, 10) != null, "sp escape: never below the kept 10 SP");
        check(SpMath.escapeBlocked(false, 465f, 86f, 2f, 19, 1, 10) != null, "sp escape: off when the rule is off");
        check(SpMath.escapeBlocked(true, 465f, 86f, 2f, 19, 2, 10) != null, "sp escape: only 1-point escapes");
        check(SpMath.escapeBlocked(true, 465f, 0f, 2f, 19, 1, 10) != null, "sp escape: unknown strength -> no");
        // Nov 4: every planet survey stopped the trip until Claude clicked "Perform Survey" by screen
        java.util.Map<String, Integer> req = new java.util.HashMap<String, Integer>(); req.put("crew", 50); req.put("heavy_machinery", 15);
        java.util.Map<String, Integer> con = new java.util.HashMap<String, Integer>(); con.put("supplies", 30); con.put("heavy_machinery", 10);
        java.util.Map<String, Float> have = new java.util.HashMap<String, Float>(); have.put("crew", 900f); have.put("heavy_machinery", 52f); have.put("supplies", 300f);
        check(SurveyRun.missing(req, con, have) == null, "survey: enough aboard");
        have.put("heavy_machinery", 12f);
        check(SurveyRun.missing(req, con, have) != null && SurveyRun.missing(req, con, have).contains("heavy_machinery 12/15"), "survey: machinery needed aboard counts");
        have.put("heavy_machinery", 52f); have.put("supplies", 20f);
        check(SurveyRun.missing(req, con, have).contains("supplies 20/30"), "survey: consumed supplies must be aboard");
        // Nov 5: five intercepts in IX/Trinity/Apex space with the transponder on
        check("off".equals(DarkMath.decide(true, false, true, 1500f, 3000f, false, 0f, 1f, false)), "dark: hostile at 1500 su -> transponder off");
        check(DarkMath.decide(true, true, true, 1500f, 3000f, false, 0f, 1f, false) == null, "dark: never while docked/in a dialog/battle");
        check(DarkMath.decide(true, false, true, -1f, 3000f, false, 0f, 1f, false) == null, "dark: no hostile -> leave it on");
        check("on".equals(DarkMath.decide(true, false, false, -1f, 3000f, true, 2f, 1f, false)), "dark: clear 2 days -> back on");
        check(DarkMath.decide(true, false, false, -1f, 3000f, true, 2f, 1f, true) == null, "dark: stays off if turning on reveals us");
        check(DarkMath.decide(true, false, false, -1f, 3000f, false, 9f, 1f, false) == null, "dark: never turns on a transponder the player switched off");
        check(DarkMath.decide(true, false, false, 5000f, 3000f, true, 2f, 1f, false) == null, "dark: stays off while a hostile is still in sight, even far off");
        check(BatchMath.keepsQueue(Arrays.asList("run until done max 40"), false), "a bare 'run' keeps the earlier batch's queue");
        check(BatchMath.keepsQueue(Arrays.asList("resume", "run until done max 25"), false), "'resume' + 'run' keeps it too");
        check(!BatchMath.keepsQueue(Arrays.asList("cancel", "run until done max 25"), false), "'cancel' does not");
        check(!BatchMath.keepsQueue(Arrays.asList("goto Tia", "run until done max 25"), false), "a batch with a goal replaces the queue");
        DialogRules drs = DialogRules.parse(DialogRules.DEFAULTS);
        List<DialogRules.Opt> hail = new ArrayList<DialogRules.Opt>();
        hail.add(new DialogRules.Opt("Turn the transponder on and comply", true, false)); hail.add(new DialogRules.Opt("Attempt to disengage", true, false));
        String hsig = "type=fleet target=Contingent faction=ix_battlegroup hostile=yes useful=no scan=yes illegal=no bounty=no stronger=yes salvage=no loot=no opts=[Turn the transponder on and comply|Attempt to disengage] text=";
        check("Attempt to disengage".equals(chosen(drs, Arrays.asList("travel"), hsig, hail)), "hostile scan demand: don't turn the transponder on");
        // Nov 1: the fleet rested inside a black hole's event horizon; CR to 0 and supplies 113 -> 0 in two days
        check(Hazard.drainsCR("event_horizon") && Hazard.drainsCR("corona") && Hazard.drainsCR("pulsar_beam"), "hazard: event horizon, corona, pulsar beam drain CR");
        check(!Hazard.drainsCR("nebula") && !Hazard.drainsCR("debris_field") && !Hazard.drainsCR(null), "hazard: nebula and debris don't");
        GovMath.Input hz = new GovMath.Input();
        hz.supplies = 113f; hz.maintPerDay = 7.7f; hz.burnPerDay = 63.7f; hz.minSupplyDays = 15f;
        boolean burnAlarm = false, hazAlarm = false;
        for (GovMath.Alarm al : GovMath.check(hz)) { if (al.key.equals("supplies:burn")) burnAlarm = true; if (al.key.equals("hazard")) hazAlarm = true; }
        check(!burnAlarm, "burn alarm: not when the maintenance alarm already covers it (113 sup = 14.7 days)");
        hz.supplies = 300f;
        for (GovMath.Alarm al : GovMath.check(hz)) if (al.key.equals("supplies:burn")) burnAlarm = true;
        check(burnAlarm, "burn alarm: 300 supplies at 63.7/day (repairs) is under 7 days");
        hz.hazard = "Event Horizon"; hz.hazardDays = 0.5f;
        for (GovMath.Alarm al : GovMath.check(hz)) if (al.key.equals("hazard")) hazAlarm = true;
        check(!hazAlarm, "hazard alarm: passing through is fine");
        hz.hazardDays = 1.2f;
        for (GovMath.Alarm al : GovMath.check(hz)) if (al.key.equals("hazard")) hazAlarm = true;
        check(hazAlarm, "hazard alarm: a day inside stops the fleet");
        check(!GovMath.routine("hazard", true, 0f, 0.4f), "hazard alarm always pauses, even heading to a market");
        // Nov 2: after the Silva fight, 526 supplies at 111/day (CR recovery) emptied on the way to Tartessus
        check(GovMath.supplyGuard(526f, 111f, 7.6f, 1024f, false, false, 7f, 15f) == GovMath.Guard.SUSPEND, "guard: 526 sup at 111/day (4.7 days) suspends CR recovery");
        check(GovMath.supplyGuard(526f, 111f, 7.6f, 1024f, true, false, 7f, 15f) == GovMath.Guard.NONE, "guard: not while docked (buying)");
        check(GovMath.supplyGuard(526f, 9f, 7.6f, 1024f, false, false, 7f, 15f) == GovMath.Guard.NONE, "guard: maintenance-only burn is the maintenance alarm's job");
        check(GovMath.supplyGuard(1200f, 111f, 7.6f, 1024f, false, false, 7f, 15f) == GovMath.Guard.NONE, "guard: 10+ days of burn is fine");
        check(GovMath.supplyGuard(526f, 8f, 7.6f, 1024f, false, true, 7f, 15f) == GovMath.Guard.NONE, "guard: suspended ships stay suspended while supplies fall short (no flip-flop)");
        check(GovMath.supplyGuard(1475f, 8f, 7.6f, 1024f, true, true, 7f, 15f) == GovMath.Guard.RESUME, "guard: resumes once supplies cover recovery + 15 days (1024+114)");
        check(GovMath.supplyGuard(527f, 8f, 7.6f, 300f, false, true, 7f, 15f, 105f) == GovMath.Guard.NONE, "guard: no resume while the hold can't carry the old burn for 14 days (flip-flop, Nov 2)");
        check(GovMath.supplyGuard(1500f, 8f, 7.6f, 300f, false, true, 7f, 15f, 105f) == GovMath.Guard.RESUME, "guard: resumes with 1,500 supplies vs 105/day");
        // 1.1.24 (Jan 25 c216 live): suspended Aug 1 c215, never resumed - 448 supplies never covered a 546 recovery
        check(GovMath.supplyGuard(448f, 1.7f, 1.7f, 546f, false, true, 7f, 15f) == GovMath.Guard.NONE, "guard: Alpha New Guan case stays held in deep space");
        check(GovMath.supplyGuard(448f, 1.7f, 1.7f, 546f, true, true, 7f, 15f) == GovMath.Guard.RESUME, "guard: resumes once docked at a market (supplies can be bought)");
        check(GovMath.guardReleases(0f), "guard: a suspended ship at 0% CR gets recovery back (accidents)");
        check(!GovMath.guardReleases(0.47f), "guard: a suspended ship at 47% CR stays suspended");
        // 1.1.25 (Mar 23 c206 live): "buyship 9" then "buyship 10" bought entry 11 (a Lacerta, not the tanker)
        java.util.List<String> shipsList = new java.util.ArrayList<String>(java.util.Arrays.asList("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11"));
        ListMath.markGone(shipsList, "s9");
        String[] shipsErr = new String[1];
        check("s10".equals(ListMath.pick(shipsList, 10, shipsErr)), "ships list: number 10 still means entry 10 after entry 9 was bought");
        check(ListMath.pick(shipsList, 9, shipsErr) == null && shipsErr[0].contains("already gone"), "ships list: a bought entry answers 'already gone'");
        check(ListMath.pick(shipsList, 12, shipsErr) == null && shipsErr[0].contains("no ship 12"), "ships list: out of range is refused");
        GovMath.Input cr = new GovMath.Input();
        cr.supplies = 1000f; cr.maintPerDay = 7f; cr.lowestCombatCR = 0.05f; cr.lowestCRShip = "Rid And Gone"; cr.minCR = 0.4f;
        GovMath.Latch lc = new GovMath.Latch();
        check(lc.update(GovMath.check(cr)).size() == 1, "cr alarm fires");
        lc.ack();
        cr.docked = true;
        check(lc.update(GovMath.check(cr)).isEmpty(), "cr alarm: still acknowledged while docked");
        cr.docked = false;
        check(lc.update(GovMath.check(cr)).isEmpty(), "cr alarm: not re-fired on undock (it fired at every market stop on Nov 2)");
        List<DialogRules.Opt> rec = opts("Consider ship recovery", "Not now");
        String recSig = "type=derelict target=Derelict Ship faction=neutral hostile=no useful=no scan=no illegal=no bounty=no stronger=no salvage=yes loot=no target_goal=yes " + DialogRules.optsField(rec) + " text=Explore";
        check("Not now".equals(chosen(dr, Arrays.asList("explore", "after_battle"), recSig, rec)), "derelict on a detour: passed on, then salvaged");
        List<DialogRules.Opt> own = opts("Consider ship recovery", "Continue");
        String ownSig = "type=fleet target=X faction=f hostile=no useful=no scan=no illegal=no bounty=no stronger=no salvage=no loot=no target_goal=no " + DialogRules.optsField(own) + " text=";
        check("pause".equals(chosen(dr, Arrays.asList("after_battle", "hunt"), ownSig, own)), "own ships after a battle: still asks");
        check(!AutoDialog.emptyGraceOver(1000L, 1000L + AutoDialog.EMPTY_GRACE_MS - 1L), "empty dialog: still settling inside the grace period");
        check(AutoDialog.emptyGraceOver(1000L, 1000L + AutoDialog.EMPTY_GRACE_MS), "empty dialog: stuck after the grace period");
        // 1.1.10 (Nov 2 live: a 4-day rest after Gal Sen with a deadline ahead): recover CR on the way when the trip is long enough
        check(TravelMath.daysToCr(0.5f, 0.7f, 0.05f) > 3.99f && TravelMath.daysToCr(0.5f, 0.7f, 0.05f) < 4.01f, "rest: 20% CR at 5%/day takes 4 days");
        check(TravelMath.daysToCr(0.8f, 0.7f, 0.05f) == 0f, "rest: already above the target needs no days");
        check(Doctrine.role("FRIGATE", "brawler", false).equals(Doctrine.CAPPER), "doctrine: Brawler caps objectives");
        check(Doctrine.role("FRIGATE", "monitor", false).equals(Doctrine.STALL), "doctrine: Monitor stalls/escorts");
        check(Doctrine.role("CRUISER", "eradicator_pirates", false).equals(Doctrine.SUPPORT), "doctrine: Eradicator (P) is wave-1 fire support");
        check(Doctrine.role("CAPITAL_SHIP", "onslaught_mk1", false).equals(Doctrine.CORE), "doctrine: Onslaught is the core wave");
        check(Doctrine.role("DESTROYER", "phaeton", true).equals(Doctrine.SKIP), "doctrine: civilian ships never deploy");
        check(!Doctrine.wave2Due(0, 4, 32f, 60f, 1f, 0.75f) && !Doctrine.wave2Due(1, 4, 32f, 60f, 0.9f, 0.75f), "doctrine 1.1.17: no wave 2 at 32 s with 0-1 of 4 objectives and wave 1 healthy (the Nil Heze charge)");
        check(Doctrine.wave2Due(2, 4, 10f, 60f, 1f, 0.75f) && Doctrine.wave2Due(2, 3, 10f, 60f, 1f, 0.75f), "doctrine 1.1.17: wave 2 once half the objectives are held");
        check(Doctrine.wave2Due(0, 4, 10f, 60f, 0.7f, 0.75f), "doctrine 1.1.17: wave 2 early when wave 1 is losing");
        check(Doctrine.wave2Due(0, 4, 61f, 60f, 1f, 0.75f) && Doctrine.wave2Due(0, 0, 0f, 60f, 1f, 0.75f), "doctrine 1.1.17: late fallback; no objectives = at once");
        check(!Doctrine.lineFormed(0.5f, 20f, 0.75f, 60f) && Doctrine.lineFormed(0.8f, 20f, 0.75f, 60f) && Doctrine.lineFormed(0f, 61f, 0.75f, 60f), "doctrine 1.1.17: search and destroy only once wave 2 reached the front (or fallback)");
        check(Doctrine.room(0f, 50f) == Float.MAX_VALUE && Doctrine.room(150f, 104f) == 46f, "doctrine: room under the game's cap");
        check(Doctrine.shouldRetreat(0.39f, 10, 0, 0.4f, 0.75f) && !Doctrine.shouldRetreat(0.9f, 10, 2, 0.4f, 0.75f), "doctrine: retreat on low hull");
        check(!Doctrine.shouldRetreat(1.0f, 15, 9, 0.4f, 0.75f) && !Doctrine.shouldRetreat(0.98f, 15, 8, 0.4f, 0.75f), "doctrine 1.1.17: healthy hull never retreats for destroyed weapons (Waning Day, Leviathan)");
        check(Doctrine.shouldRetreat(0.6f, 10, 6, 0.4f, 0.75f) && !Doctrine.shouldRetreat(0.6f, 10, 5, 0.4f, 0.75f), "doctrine 1.1.17: damaged hull + most weapons destroyed retreats");
        check(!TravelMath.fullAssault(262f, 238f, 1.5f), "battle: 262 vs 238 DP is not a clear edge - no Full Assault");
        check(TravelMath.fullAssault(300f, 150f, 1.5f), "battle: 2x the enemy - Full Assault");
        check(!TravelMath.fullAssault(500f, 10f, 0f), "battle: ratio 0 never orders Full Assault");
        check(TravelMath.recoverEnRoute(4f, 8f, 1f), "rest: an 8-day trip covers a 4-day recovery - no rest stop");
        check(!TravelMath.recoverEnRoute(4f, 2f, 1f), "rest: a 2-day trip doesn't cover a 4-day recovery - rest first");
        check(!TravelMath.recoverEnRoute(4f, 0f, 1f), "rest: unknown trip length - rest first");
        check(TravelMath.recoverEnRoute(0f, 0f, 1f), "rest: nothing to recover - go");
        // 1.1.11 (Nov 2 live: restock aimed at Derinkuyu, 4.6 ly, with 180 fuel aboard when 574 were needed)
        check(!GovMath.reachable(4.6f, 62.3f, 180f, 1.2f), "restock: 4.6 ly at 62 fuel/ly is out of reach with 180 fuel");
        check(GovMath.reachable(4.6f, 62.3f, 600f, 1.2f), "restock: 4.6 ly is in reach with 600 fuel");
        check(GovMath.reachable(0f, 62.3f, 0f, 1.2f), "restock: a market in this system is always in reach");
        check(!BatchMath.needsApproval("restore CGR Star Ark quote"), "restore quote: no approval (nothing is spent)");
        check(BatchMath.needsApproval("restore CGR Star Ark"), "restore: still needs the player's Yes");
        check(BatchMath.needsApproval("undmod CGR Miracle of Life"), "undmod: the one-off free d-mod removal needs the player's Yes");
        check(Shipyard.UNDMOD_SHIPS.size() == 3 && Shipyard.UNDMOD_SHIPS.contains("CGR Waning Day"), "undmod: only the three ships the player named");
        check(Guard.keepFloor(rule, "heavy_machinery", "Heavy Machinery") == 50, "keep: id with underscore matches");
        check(Guard.keepFloor(rule, "gauss", "Gauss Cannon") == Integer.MAX_VALUE, "keep: bare name keeps all");
        check(Guard.keepFloor(rule, "food", "Food") == 0, "keep: not listed");
        check(Guard.keepFloor("none", "metals", "Metals") == 0, "keep: none");
        check(Guard.keepFloor("metals:abc", "metals", "Metals") == Integer.MAX_VALUE, "keep: bad number keeps all (safe side)");
        check(near(Guard.sellable(1565f, 1565f, 50), 1515f), "sellable: keeps 50");
        check(near(Guard.sellable(30f, 100f, 50), 0f), "sellable: under the floor sells nothing");
        check(near(Guard.sellable(500f, 100f, 50), 100f), "sellable: asked for less than free");
        check(near(Guard.sellable(500f, 500f, Integer.MAX_VALUE), 0f), "sellable: kept outright");
        List<Float> p = Arrays.asList(98f, 102f, 107f, 109f, 128f, 141f, 146f);
        check(near(Guard.median(p), 109f), "median odd");
        check(near(Guard.median(Arrays.asList(100f, 120f, 0f)), 110f), "median ignores zero, even count");
        check(near(Guard.ceiling(p, 1.35f, 150f, 0f, 5), 109f * 1.35f), "ceiling follows the market");
        check(near(Guard.ceiling(Arrays.asList(100f), 1.35f, 150f, 0f, 5), 150f), "ceiling: fallback with too few prices");
        check(near(Guard.ceiling(p, 1.35f, 150f, 120f, 5), 120f), "ceiling: fixed override wins");
        EventCore e = new EventCore(100);
        e.add("d", EventCore.INFO, "a", "", "one");
        long w = e.add("d", EventCore.WARN, "gov", "", "fuel low");
        e.add("d", EventCore.INFO, "a", "", "three\nmore WARN text");
        long al = e.add("d", EventCore.ALERT, "hunt", "", "paused");
        check(e.firstNotableAfter(0) == w, "notable: first warn");
        check(e.firstNotableAfter(w) == al, "notable: alert after the warn (continuation lines ignored)");
        check(e.firstNotableAfter(al) == -1L, "notable: none after the last alert");
        Map<String, String> a = new HashMap<String, String>(), z = new HashMap<String, String>();
        a.put("keep", "metals:50"); a.put("x", "1"); z.put("keep", "none"); z.put("y", "2");
        String d = Rules.diff(a, z);
        check(d.contains("keep: metals:50 -> none") && d.contains("x: 1 -> (removed)") && d.contains("y: (unset) -> 2"), "rules diff: " + d);
        check(Rules.diff(a, new HashMap<String, String>(a)).isEmpty(), "rules diff: no change");
        List<String> avris = Arrays.asList("ruins_vast", "ore_rich", "no_atmosphere", "hot");
        List<String> crom = Arrays.asList("thin_atmosphere", "decivilized", "rare_ore_moderate", "ore_moderate", "ruins_scattered", "hot");
        check(near(ColonyMath.resourceScore(avris), 8f), "colony score: vast ruins + rich ore = 8");
        check(near(ColonyMath.resourceScore(crom), 6f), "colony score: rare ore x1.5 + ore + ruins = 6");
        check(ColonyMath.resources(Arrays.asList("hot", "no_atmosphere")).equals("no deposits"), "colony: no deposits");
        check(ColonyMath.rank(8f, 175f) > ColonyMath.rank(6f, 175f), "colony rank: more resources first");
        check(ColonyMath.rank(8f, 150f) > ColonyMath.rank(8f, 250f), "colony rank: lower hazard first");
        check(ColonyMath.claimAllows(null, "luddic_church"), "colony claim: unclaimed system is fine");
        check(ColonyMath.claimAllows("luddic_church", "luddic_church"), "colony claim: commission faction's system is fine");
        check(!ColonyMath.claimAllows("persean", "luddic_church"), "colony claim: Netzach (Persean claim, Church commission) is off limits");
        check(!ColonyMath.claimAllows("persean", null), "colony claim: claimed system without a commission is off limits");
        check(ColonyMath.claimAllows("player", null), "colony claim: own system is fine");
        {   // 1.0.9: the officer's terms screen is accepted (the hire itself was approved on the popup)
            DialogRules hr = DialogRules.parse(DialogRules.DEFAULTS);
            List<DialogRules.Opt> terms = opts("\"Sounds good.\"", "\"I've changed my mind.\"");
            check("\"Sounds good.\"".equals(chosen(hr, Arrays.asList("hire"), sig("market", "hostile=no", terms), terms)), "hire: accept the officer's terms");
        }
        {   // 1.0.9: every "not docked" approval is kept (live bug Nov 2: only the last of four survived)
            ApprovalBook ab = new ApprovalBook();
            ab.keep("sellship X black confirm", 1000L); ab.keep("buyship Y", 1000L); ab.keep("restore A", 1000L); ab.keep("restore B", 1000L);
            check(ab.size() == 4, "approvals: all four kept");
            check(ab.use("buyship Y", 500L) && !ab.has("buyship Y", 500L), "approvals: one use each");
            check(ab.has("sellship X black confirm", 500L) && ab.has("restore A", 500L), "approvals: the others still kept");
            check(!ab.use("restore B", 2000L), "approvals: expired after 30 minutes");
            check(!ab.use("restore Z", 500L), "approvals: never approved -> not kept");
        }
        {   // 1.0.9: pursuit picks (player's rule: second-in-command, fastest and most ready ships)
            java.util.List<PursuitMath.Cand> c = new java.util.ArrayList<PursuitMath.Cand>();
            c.add(new PursuitMath.Cand("Onslaught", 40f, 0.84f, 50f));
            c.add(new PursuitMath.Cand("Brawler", 120f, 0.99f, 5f));
            c.add(new PursuitMath.Cand("Omen", 140f, 0.30f, 6f));
            c.add(new PursuitMath.Cand("Medusa", 110f, 0.84f, 12f));
            c.add(new PursuitMath.Cand("Eagle", 80f, 0.90f, 20f));
            java.util.List<Integer> p1 = PursuitMath.pick(c, 10f, 0.5f, 2f);
            check(p1.size() == 3 && p1.get(0) == 1 && p1.get(1) == 3 && p1.get(2) == 4, "pursuit: fastest ready ships first until 2x enemy FP (Brawler, Medusa, Eagle) " + p1);
            check(!p1.contains(2), "pursuit: low-CR Omen stays home even though fastest");
            java.util.List<Integer> p2 = PursuitMath.pick(c, 1000f, 0.5f, 2f);
            check(p2.size() == 4 && p2.get(3) == 0, "pursuit: too strong an enemy -> all ready ships go, slowest last " + p2);
            check(PursuitMath.pick(c, 10f, 0.95f, 2f).size() == 1, "pursuit: only ships at or over min CR");
            check(PursuitMath.pick(c, 10f, 1.0f, 2f).isEmpty(), "pursuit: none ready -> none sent");
            DialogRules pdr = DialogRules.parse(DialogRules.DEFAULTS);
            java.util.List<DialogRules.Opt> po = new java.util.ArrayList<DialogRules.Opt>();
            po.add(new DialogRules.Opt("Take command of the action", true, false));
            po.add(new DialogRules.Opt("Order your second-in-command to handle it", true, false));
            String psig = "type=fleet " + DialogRules.optsField(po);
            DialogRules.Decision pd = pdr.decide(java.util.Arrays.asList("hunt", "engage", "pursuit"), psig, po, false);
            check(pd != null && pd.action.equals("pursue_auto") && pd.option == 1, "dialog rules: pursuit goes to the second-in-command");
        }
        check(Guard.keepFloor("metals 50", "metals", "Metals") == 50, "keep: 'metals 50' typo form");
        check(BatchMath.needsApproval("colony build Khna waystation"), "colony build with an industry asks in game first");
        check(!BatchMath.needsApproval("colony build Khna"), "colony build listing needs no approval");
        // inbox gate
        List<String> go = Arrays.asList("go Hesperus");
        check(BatchMath.gate(go, -1, 100, -1).contains("#since"), "gate: no since refused");
        check(BatchMath.gate(go, 999999, 100, -1).contains("past the last"), "gate: future since refused (audit C3)");
        check(BatchMath.gate(go, 90, 100, 95).contains("#95"), "gate: unread alert refused");
        check(BatchMath.gate(go, 100, 100, -1) == null, "gate: all read -> ok");
        check(BatchMath.gate(Arrays.asList("pause", "halt", "hunt stop", "cancel", "optional loop stop"), -1, 100, 50) == null, "gate: stop-only batches always pass");
        check(BatchMath.gate(Arrays.asList("pause", "go Hesperus"), -1, 100, -1) != null, "gate: a stop plus a real command still needs #since");
    }

    // ------------------------------------------------------------------ survey targets (1.1.26)

    static void surveyTargets() {
        SurveyMath.Sys near = new SurveyMath.Sys("Near", 4f, 2, 0);
        SurveyMath.Sys far = new SurveyMath.Sys("Far", 12f, 5, 1);
        SurveyMath.Sys done = new SurveyMath.Sys("Done", 2f, 0, 3);
        SurveyMath.Sys remnant = new SurveyMath.Sys("Remnant", 3f, 4, 2);
        remnant.danger = true;
        SurveyMath.Sys tooFar = new SurveyMath.Sys("TooFar", 40f, 6, 0);
        List<SurveyMath.Sys> r = SurveyMath.rank(Arrays.asList(far, done, remnant, near, tooFar), 30f);
        check(r.size() == 3, "survey targets: fully surveyed and out-of-range systems are dropped");
        check(r.get(0) == near && r.get(1) == far, "survey targets: safe systems nearest first");
        check(r.get(2) == remnant, "survey targets: a live Remnant system goes last even when nearest");
        check(SurveyMath.dangerTag("theme_remnant_resurgent") && !SurveyMath.dangerTag("theme_remnant_destroyed") && !SurveyMath.dangerTag("theme_derelict"),
                "survey targets: only live Remnant themes are dangerous");
        check(ExploreMath.surveySkipped("Gas Giant", "gas giant, volcanic") && ExploreMath.surveySkipped("Volcanic World", " Volcanic "),
                "survey skip types: listed types are skipped, ignoring case and spaces");
        check(!ExploreMath.surveySkipped("Barren World", "gas giant, volcanic") && !ExploreMath.surveySkipped("Terran World", "")
                && !ExploreMath.surveySkipped(null, "gas giant"), "survey skip types: other types, an empty list and no type are surveyed");
        ExploreMath.Site ru = new ExploreMath.Site("r", "Ruin", "ruins", 0, 0); ru.ruins = true; ru.salvage = true;
        ExploreMath.Site sv = new ExploreMath.Site("s", "Rock", "planet_unsurveyed", 0, 0); sv.survey = true;
        ExploreMath.Site de = new ExploreMath.Site("d", "Wreck", "derelict", 0, 0); de.salvage = true;
        List<ExploreMath.Site> rf = ExploreMath.ruinsFocus(Arrays.asList(de, ru, sv));
        check(rf.size() == 2 && rf.contains(ru) && rf.contains(sv), "ruins focus: keeps ruins and survey sites, drops derelicts");
        check(ExploreMath.pick(Arrays.asList(sv, ru), 0, 0, null, null, 1000f, new HashSet<String>(), false, 50f, 1.25f, true) == ru,
                "ruins focus: known ruins are visited before surveys");
        check(SurveyMath.line(1, remnant).contains("DANGER") && SurveyMath.line(1, far).contains("1 salvage"), "survey targets: line shows danger and salvage");
    }
}
