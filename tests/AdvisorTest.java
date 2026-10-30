import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import claudecaptain.Advisor;
import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import java.lang.reflect.*;
import java.util.*;

public class AdvisorTest {
    static int fails = 0, passes = 0;
    static void check(boolean c, String m) { if (c) passes++; else { fails++; System.out.println("FAIL: " + m); } }

    /** Proxy that answers overrides by method name; everything else gets a harmless default. */
    @SuppressWarnings("unchecked")
    public static <T> T mock(Class<T> k, final Map<String, Object> o) {
        return (T) Proxy.newProxyInstance(k.getClassLoader(), new Class<?>[]{k}, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (o.containsKey(m.getName())) {
                    Object v = o.get(m.getName());
                    if (v instanceof Callable1) return ((Callable1) v).call(a);
                    return v;
                }
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                if (m.getName().equals("toString")) return "mock";
                return def(m.getReturnType());
            }
        });
    }
    public interface Callable1 { Object call(Object[] a); }
    static Object def(Class<?> r) {
        if (r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        if (r == String.class) return "";
        if (r == List.class || r == Collection.class) return new ArrayList<Object>();
        if (r == Map.class) return new HashMap<Object, Object>();
        if (r == Set.class) return new HashSet<Object>();
        if (r.isInterface() && !r.getName().startsWith("java.")) return mock(r, new HashMap<String, Object>());
        return null;
    }
    public static Map<String, Object> m(Object... kv) { Map<String, Object> x = new HashMap<String, Object>(); for (int i = 0; i < kv.length; i += 2) x.put((String) kv[i], kv[i + 1]); return x; }

    static class Scn { float fuel = 100, maxFuel = 850, supplies = 160, credits = 40000, cr = 0.7f, used = 200, cap = 430; int fp = 50; boolean market = true, objective = true; int guardFp = 0; boolean tutorial = false; float minCrew = 0; int crew = 100; }

    static Advisor.Plan run(Scn s) {
        final Vector2f origin = new Vector2f(0, 0), far = new Vector2f(3000, 0);
        final List<CampaignFleetAPI> fleets = new ArrayList<CampaignFleetAPI>();
        final LocationAPI loc = mock(LocationAPI.class, m("getName", "Test System", "getFleets", fleets));
        Map<String, Object> cm = m("getCredits", new com.fs.starfarer.api.util.MutableValue(s.credits), "getSupplies", s.supplies,
            "getFuel", s.fuel, "getMaxFuel", s.maxFuel, "getSpaceUsed", s.used, "getMaxCapacity", s.cap, "getCrew", s.crew);
        CargoAPI cargo = mock(CargoAPI.class, cm);

        com.fs.starfarer.api.fleet.FleetMemberAPI mem = mock(com.fs.starfarer.api.fleet.FleetMemberAPI.class, m(
            "getStats", mock(com.fs.starfarer.api.combat.MutableShipStatsAPI.class, m("getSuppliesPerMonth",
                new com.fs.starfarer.api.combat.MutableStat(10f))),
            "getRepairTracker", mock(com.fs.starfarer.api.fleet.RepairTrackerAPI.class, m("getCR", s.cr))));
        List<com.fs.starfarer.api.fleet.FleetMemberAPI> members = new ArrayList<com.fs.starfarer.api.fleet.FleetMemberAPI>();
        for (int k = 0; k < 5; k++) members.add(mem);

        final CampaignFleetAPI[] pfRef = new CampaignFleetAPI[1];
        pfRef[0] = mock(CampaignFleetAPI.class, m("getCargo", cargo, "getFleetPoints", s.fp, "getContainingLocation", loc,
            "getLocation", origin, "getLocationInHyperspace", origin, "getRadius", 20f,
            "getFleetData", mock(FleetDataAPI.class, m("getMembersListCopy", members, "getMinCrew", s.minCrew)),
            "getLogistics", mock(com.fs.starfarer.api.fleet.FleetLogisticsAPI.class, m("getFuelCostPerLightYear", 3f))));
        final CampaignFleetAPI pf = pfRef[0];
        fleets.add(pf);

        final MarketAPI mk;
        List<MarketAPI> markets = new ArrayList<MarketAPI>();
        if (s.market) {
            SectorEntityToken ent = mock(SectorEntityToken.class, m("getLocation", origin, "getLocationInHyperspace", origin, "getContainingLocation", loc, "getRadius", 50f, "getName", "Ancyra"));
            mk = mock(MarketAPI.class, m("getName", "Ancyra", "getPrimaryEntity", ent,
                "getFaction", mock(com.fs.starfarer.api.campaign.FactionAPI.class, m("isHostileTo", false)),
                "getSubmarket", mock(com.fs.starfarer.api.campaign.econ.SubmarketAPI.class, m("getTariff", 0.3f)),
                "getSupplyPrice", 100f));
            markets.add(mk);
        }
        final SectorEntityToken obj = mock(SectorEntityToken.class, m("getLocation", far, "getLocationInHyperspace", far, "getContainingLocation", loc, "getRadius", 50f, "getName", "Inner System Jump-point"));
        if (s.guardFp > 0) {
            fleets.add(mock(CampaignFleetAPI.class, m("isAlive", true, "isHostileTo", true, "getFleetPoints", s.guardFp, "getLocation", far, "getLocationInHyperspace", far, "getContainingLocation", loc)));
        }
        List<com.fs.starfarer.api.campaign.comm.IntelInfoPlugin> intel = new ArrayList<com.fs.starfarer.api.campaign.comm.IntelInfoPlugin>();
        if (s.objective) intel.add(mock(com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.class, m("isImportant", true, "getSmallDescriptionTitle", "Stabilize the Jump-points", "getMapLocation", obj)));
        final List<MarketAPI> fm = markets;
        SectorAPI sec = mock(SectorAPI.class, m("getPlayerFleet", pf,
            "getClock", mock(com.fs.starfarer.api.campaign.CampaignClockAPI.class, m("getDateString", "May 4, c206")),
            "getIntelManager", mock(com.fs.starfarer.api.campaign.comm.IntelManagerAPI.class, m("getIntel", intel)),
            "getEconomy", mock(com.fs.starfarer.api.campaign.econ.EconomyAPI.class, m("getMarkets", fm)),
            "getPlayerStats", mock(com.fs.starfarer.api.characters.MutableCharacterStatsAPI.class, m("getPoints", 2, "getStoryPoints", 4))));
        Global.setSector(sec);
        return Advisor.scan();
    }

    static final Set<String> CMDS = new HashSet<String>(Arrays.asList("goto", "dock", "buy", "sell", "wait", "battle","status","choose","optional","storage","ability"));
    static void validate(String name, Advisor.Plan p) {
        check(p.options.size() == 3, name + ": exactly 3 options (got " + p.options.size() + ")");
        boolean g = false; for (Advisor.Check c : p.checks) if (c.label.equals("Objective guarded")) g = true;
        check(p.checks.size() == (g ? 10 : 9), name + ": checklist has " + (g ? 10 : 9) + " lines (got " + p.checks.size() + ")");
        for (Advisor.Check c : p.checks) check(c.level >= 0 && c.level <= 2 && c.label.length() > 0 && c.detail.length() > 0, name + ": check line '" + c.label + "'");
        Set<String> seen = new HashSet<String>();
        for (Advisor.Option o : p.options) {
            check(o.title != null && o.title.length() > 0, name + ": title");
            check(!o.tasks.isEmpty(), name + ": has tasks (" + o.title + ")");
            check(o.route().length() > 0 && o.route().startsWith("1 "), name + ": route text (" + o.route() + ")");
            check(seen.add(o.title), name + ": distinct titles (" + o.title + ")");
            for (String t : o.tasks) check(CMDS.contains(t.split(" ")[0]), name + ": known command '" + t + "'");
            for (String t : o.tasks) check(!t.matches("(?i).*(storypoint| sp |cheat|teleport|credits add|ship add).*"), name + ": no cheat/SP command '" + t + "'");
        }
        for (int i = 1; i < p.options.size(); i++) check(p.options.get(i - 1).score >= p.options.get(i).score, name + ": sorted by score");
    }
    static void dump(String name, Advisor.Plan p) {
        System.out.println("--- " + name);
        for (String s : p.summary) System.out.println("  " + s);
        for (Advisor.Check c : p.checks) System.out.println("  check L" + c.level + " " + c.label + ": " + c.detail);
        for (Advisor.Option o : p.options) System.out.println("  [" + o.score + "] " + o.title + "  => " + o.tasks + "\n        " + o.why);
        for (String s : p.notes) System.out.println("  note: " + s);
    }

    public static void main(String[] a) {
        Global.setSettings(mock(com.fs.starfarer.api.SettingsAPI.class, m()));
        Scn s = new Scn(); Advisor.Plan p;
        p = run(s); dump("normal: objective, fuel 100 of 850", p); validate("normal", p);
        check(p.options.get(0).title.startsWith("Refuel") || p.options.get(0).title.startsWith("Go to"), "normal: objective or refuel first");

        s = new Scn(); s.fuel = 5; p = run(s); dump("very low fuel", p); validate("lowfuel", p);
        check(p.options.get(0).title.startsWith("Refuel"), "lowfuel: refuel ranked first");

        s = new Scn(); s.fuel = 800; p = run(s); dump("full fuel", p); validate("fullfuel", p);
        check(p.options.get(0).title.startsWith("Go to"), "fullfuel: objective first");
        check(p.options.get(0).tasks.contains("goto objective"), "fullfuel: uses goto objective");

        s = new Scn(); s.fuel = 800; s.guardFp = 400; p = run(s); dump("strong guards", p); validate("guards", p);
        check(!p.options.get(0).title.startsWith("Go to") , "guards: risky objective not first");
        for (Advisor.Option o : p.options) if (o.title.startsWith("Go to")) check(!o.tasks.contains("battle auto on"), "guards: no auto-battle against stronger fleet");

        s = new Scn(); s.fuel = 800; s.guardFp = 20; p = run(s); dump("weak guards", p); validate("weakguards", p);
        check(p.options.get(0).tasks.contains("battle auto on"), "weakguards: auto-battle turned on");

        s = new Scn(); s.fuel = 800; s.cr = 0.3f; p = run(s); dump("low CR", p); validate("lowcr", p);
        boolean rest = false; for (Advisor.Option o : p.options) rest |= o.title.startsWith("Repair");
        check(rest, "lowcr: rest option offered");

        s = new Scn(); s.fuel = 800; s.supplies = 20; p = run(s); dump("low supplies", p); validate("lowsup", p);
        boolean sup = false; for (Advisor.Option o : p.options) sup |= o.title.startsWith("Restock");
        check(sup, "lowsup: restock offered");

        s = new Scn(); s.market = false; s.objective = false; p = run(s); dump("no market, no objective", p); validate("nothing", p);

        s = new Scn(); s.credits = 0; s.fuel = 5; s.supplies = 5; p = run(s); dump("broke", p); validate("broke", p);
        for (Advisor.Option o : p.options) for (String t : o.tasks) check(!t.matches("buy .* -?\\d+") || Integer.parseInt(t.replaceAll(".* ", "")) > 0, "broke: no zero/negative purchase '" + t + "'");

        s = new Scn(); s.used = 420; s.fuel = 800; p = run(s); dump("hold full", p); validate("holdfull", p);

        Global.setSector(mock(SectorAPI.class, m("getPlayerFleet", null))); // no player fleet
        p = Advisor.scan(); check(p.options.isEmpty() && !p.notes.isEmpty(), "no fleet: graceful");

        // ---- v1.11: editable thresholds
        claudecaptain.Rules.useText("fuel_low_pct = 80\n");
        Scn r = new Scn(); r.fuel = 400; p = run(r);
        boolean fuelFlag = false; for (Advisor.Check c : p.checks) if (c.label.equals("Fuel") && c.level > 0) fuelFlag = true;
        check(fuelFlag, "rules: fuel_low_pct=80 flags 400/850 fuel");
        claudecaptain.Rules.useText(claudecaptain.Rules.DEFAULTS);
        p = run(r);
        fuelFlag = false; for (Advisor.Check c : p.checks) if (c.label.equals("Fuel") && c.level > 0) fuelFlag = true;
        check(!fuelFlag, "rules: default 30% does not flag 400/850 fuel");
        check(claudecaptain.Rules.b("allow_cheats", true) == false, "rules: cheats off by default");
        check(claudecaptain.Rules.s("save_before_action", "").equals("auto"), "rules: save to the autosave slot by default");

        // ---- v1.11: advice pushed from the chat
        final String fresh = "#written " + System.currentTimeMillis() + "\n75 | Go trade at Ancyra | Food sells well there | goto Ancyra; dock Ancyra\n";
        Global.setSettings(mock(com.fs.starfarer.api.SettingsAPI.class, m("fileExistsInCommon", true, "readTextFileFromCommon", fresh)));
        claudecaptain.Rules.useText(claudecaptain.Rules.DEFAULTS);
        p = run(new Scn());
        Advisor.Option pushed = null; for (Advisor.Option o : p.options) if (o.title.startsWith("[Claude]")) pushed = o;
        check(pushed != null && pushed.tasks.size() == 2 && pushed.tasks.get(1).equals("dock Ancyra"), "advice: pushed option shows with its tasks");
        final String stale = "#written " + (System.currentTimeMillis() - 3600000L) + "\n99 | Old idea | x | wait 1\n";
        Global.setSettings(mock(com.fs.starfarer.api.SettingsAPI.class, m("fileExistsInCommon", true, "readTextFileFromCommon", stale)));
        p = run(new Scn());
        boolean old = false; for (Advisor.Option o : p.options) if (o.title.startsWith("[Claude]")) old = true;
        check(!old, "advice: expires after claude_advice_minutes");
        Global.setSettings(mock(com.fs.starfarer.api.SettingsAPI.class, m()));

        check(Advisor.stepText("wait 1").equals("Wait 1 day"), "step text: 1 day");
        check(Advisor.stepText("wait 3").equals("Wait 3 days"), "step text: 3 days");
        check(Advisor.stepText("choose Leave").equals("Leave"), "step text: leave");
        // ---- v1.11.8: crew check, hire, one-stop resupply
        Scn rs = new Scn(); rs.fuel = 20; rs.supplies = 5; rs.minCrew = 90; rs.crew = 39; rs.cr = 0.3f;
        p = run(rs); dump("everything low", p);
        boolean crewRed = false; for (Advisor.Check c : p.checks) if (c.label.equals("Crew") && c.level == 2) crewRed = true;
        check(crewRed, "resupply: crew shortage is red");
        Advisor.Option top = p.options.get(0);
        check(top.title.startsWith("Resupply at Ancyra"), "resupply: one-stop option ranks first (" + top.title + ")");
        check(top.tasks.indexOf("buy supplies 0") < 0 && top.tasks.get(2).startsWith("buy supplies"), "resupply: supplies bought first " + top.tasks);
        boolean hasCrew = false, hasFuel = false; for (String t : top.tasks) { hasCrew |= t.startsWith("buy crew"); hasFuel |= t.startsWith("buy fuel"); }
        check(hasCrew && hasFuel, "resupply: buys crew and fuel too");
        check(top.tasks.contains("optional choose Repair your ships"), "resupply: repair is optional");
        check(top.tasks.get(top.tasks.size() - 1).equals("choose Leave"), "resupply: leaves the dock at the end");
        // ---- v1.11.9: one shared budget for the combined resupply
        Scn poor = new Scn(); poor.fuel = 20; poor.supplies = 5; poor.minCrew = 90; poor.crew = 39; poor.cr = 0.3f; poor.credits = 300;
        p = run(poor); dump("everything low, 300 cr", p);
        for (Advisor.Option o : p.options) if (o.title.startsWith("Resupply")) {
            check(o.why.contains("budget 150"), "budget: half of credits (" + o.why + ")");
            check(o.title.contains(" of ") || !o.title.contains("fuel"), "budget: last item trimmed or dropped (" + o.title + ")");
        }
        Scn ok = new Scn(); ok.fuel = 800; p = run(ok);
        for (Advisor.Option o : p.options) check(!o.title.startsWith("Resupply"), "resupply: not offered when nothing is low");

        // ---- v1.11: cheat gate
        check(claudecaptain.CommandRunner.isCheat("credits", Arrays.asList("add", "5")), "cheat: credits");
        check(claudecaptain.CommandRunner.isCheat("teleport", Arrays.asList("Ancyra")), "cheat: teleport");
        check(claudecaptain.CommandRunner.isCheat("cargo", Arrays.asList("add", "metals")), "cheat: cargo add");
        check(!claudecaptain.CommandRunner.isCheat("cargo", new ArrayList<String>()), "not cheat: cargo list");
        check(!claudecaptain.CommandRunner.isCheat("buy", Arrays.asList("fuel", "10")), "not cheat: buy");
        check(!claudecaptain.CommandRunner.isCheat("goto", Arrays.asList("Ancyra")), "not cheat: goto");

        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
