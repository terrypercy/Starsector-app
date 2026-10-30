import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.*;
import claudecaptain.TradeAdvisor;
import java.util.*;

public class TradeTest {
    static int pass = 0, fail = 0;
    static void check(boolean c, String m) { if (c) pass++; else { fail++; System.out.println("FAIL: " + m); } }
    static Map<String, Object> m(Object... kv) { return AdvisorTest.m(kv); }
    static <T> T mock(Class<T> k, Map<String, Object> o) { return AdvisorTest.mock(k, o); }

    public static void main(String[] a) {
        Global.setSettings(mock(com.fs.starfarer.api.SettingsAPI.class, m()));
        final Map<String, Float> supply = new HashMap<String, Float>(), demand = new HashMap<String, Float>(), stock = new HashMap<String, Float>();
        supply.put("food", 20f); demand.put("food", 15f); stock.put("food", 500f);
        supply.put("metals", 40f); demand.put("metals", 35f); stock.put("metals", 100f);
        supply.put("heavy_machinery", 200f); demand.put("heavy_machinery", 150f); stock.put("heavy_machinery", 0f);
        supply.put("crew", 50f); demand.put("crew", 0f); stock.put("crew", 200f);
        supply.put("fuel", 30f); demand.put("fuel", 20f); stock.put("fuel", 1000f);
        final List<String> ids = Arrays.asList("food", "metals", "heavy_machinery", "crew", "fuel", "supplies");

        AdvisorTest.Callable1 specFor = new AdvisorTest.Callable1() { public Object call(Object[] x) {
            String id = (String) x[0];
            return mock(CommoditySpecAPI.class, m("getId", id, "getName", id.substring(0, 1).toUpperCase() + id.substring(1).replace('_', ' '),
                "getCargoSpace", 1f, "isPersonnel", id.equals("crew"), "isMeta", false, "isNonEcon", false));
        }};
        AdvisorTest.Callable1 cargoQty = new AdvisorTest.Callable1() { public Object call(Object[] x) {
            Float v = stock.get((String) x[0]); return v == null ? 0f : v; }};
        AdvisorTest.Callable1 sp = new AdvisorTest.Callable1() { public Object call(Object[] x) {
            Float v = supply.get((String) x[0]); return v == null ? 0f : v * ((Number) x[1]).floatValue(); }};
        AdvisorTest.Callable1 dp = new AdvisorTest.Callable1() { public Object call(Object[] x) {
            Float v = demand.get((String) x[0]); return v == null ? 0f : v * ((Number) x[1]).floatValue(); }};

        CargoAPI marketCargo = mock(CargoAPI.class, m("getCommodityQuantity", cargoQty));
        SubmarketPlugin plug = mock(SubmarketPlugin.class, m("isIllegalOnSubmarket", false));
        SubmarketAPI open = mock(SubmarketAPI.class, m("getTariff", 0.3f, "getCargo", marketCargo, "getPlugin", plug));
        FactionAPI friendly = mock(FactionAPI.class, m("isHostileTo", false));
        final MarketAPI here = mock(MarketAPI.class, m("getId", "ancyra", "getName", "Ancyra", "getSubmarket", open,
            "getSupplyPrice", sp, "getDemandPrice", dp, "getFaction", friendly));
        final MarketAPI there = mock(MarketAPI.class, m("getId", "jangala", "getName", "Jangala", "getFaction", friendly));
        AdvisorTest.Callable1 getMarket = new AdvisorTest.Callable1() { public Object call(Object[] x) {
            return "ancyra".equals(x[0]) ? here : "jangala".equals(x[0]) ? there : null; }};

        CargoStackAPI metals = mock(CargoStackAPI.class, m("isNull", false, "isCommodityStack", true, "getCommodityId", "metals", "getSize", 31f));
        CargoAPI pc = mock(CargoAPI.class, m("getCredits", new com.fs.starfarer.api.util.MutableValue(10000f),
            "getMaxCapacity", 400f, "getSpaceUsed", 100f, "getCrew", 50, "getMarines", 0, "getMaxPersonnel", 300f, "getTotalPersonnel", 50,
            "getFuel", 300f, "getMaxFuel", 400f, "getStacksCopy", Arrays.asList(metals)));
        CampaignFleetAPI pf = mock(CampaignFleetAPI.class, m("getCargo", pc, "getFleetData", mock(FleetDataAPI.class, m("getMinCrew", 80f))));
        Map<String, Object> persistent = new HashMap<String, Object>();
        Map<String, String> book = new HashMap<String, String>();
        book.put("jangala|food", "40.0;60.0;100.0;0");     // Jangala paid 60 for food
        book.put("jangala|metals", "50.0;26.0;10.0;0");    // barely better for metals (24.5 here)
        persistent.put("claudebridge_prices", book);
        SectorAPI sec = mock(SectorAPI.class, m("getPlayerFleet", pf, "getPersistentData", persistent,
            "getClock", mock(CampaignClockAPI.class, m("getTimestamp", 1000L, "getElapsedDaysSince", 5f)),
            "getEconomy", mock(EconomyAPI.class, m("getAllCommodityIds", ids, "getCommoditySpec", specFor, "getMarket", getMarket))));
        Global.setSector(sec);

        TradeAdvisor.Plan p = TradeAdvisor.scan(here);
        System.out.println("market " + p.market);
        for (TradeAdvisor.Line l : p.lines) System.out.println("  L" + l.level + " " + l.text);
        for (TradeAdvisor.Action x : p.actions) System.out.println("  [" + x.score + "] " + x.title + " => " + x.tasks + "\n      " + x.why);

        check(p.actions.size() == 3, "three actions");
        check(p.actions.get(0).title.startsWith("Hire"), "hiring crew ranks first when short");
        check(p.actions.get(0).tasks.get(0).matches("buy crew \\d+"), "hire task syntax");
        boolean buyFood = false, sellMetals = false;
        for (TradeAdvisor.Action x : p.actions) {
            if (x.tasks.get(0).matches("buy food \\d+")) buyFood = true;
            if (x.tasks.get(0).equals("sell metals all")) sellMetals = true;
            check(!x.tasks.get(0).contains("supplies"), "never trades supplies");
        }
        check(buyFood, "suggests buying food to sell at Jangala");
        check(sellMetals, "suggests selling metals (no clearly better price known)");
        check(book.containsKey("ancyra|food") && book.containsKey("ancyra|metals"), "records this market's prices");
        check(!book.containsKey("ancyra|crew") && !book.containsKey("ancyra|fuel") && !book.containsKey("ancyra|supplies"), "skips crew/fuel/supplies in the price book");
        String[] q = book.get("ancyra|food").split(";");
        check(Math.abs(Float.parseFloat(q[0]) - 26f) < 0.01 && Math.abs(Float.parseFloat(q[1]) - 10.5f) < 0.01, "recorded buy/sell include tariff (" + book.get("ancyra|food") + ")");

        // hold advice: if Jangala pays far more for metals, don't sell here
        book.put("jangala|metals", "50.0;80.0;10.0;0");
        p = TradeAdvisor.scan(here);
        boolean held = false;
        for (TradeAdvisor.Line l : p.lines) if (l.text.startsWith("Hold Metals")) held = true;
        for (TradeAdvisor.Action x : p.actions) check(!x.tasks.get(0).equals("sell metals all"), "does not sell metals when much better price known");
        check(held, "explains holding metals");

        // nothing known yet: no crash, helpful line
        book.clear();
        pc = mock(CargoAPI.class, m("getCredits", new com.fs.starfarer.api.util.MutableValue(100f), "getMaxCapacity", 400f, "getSpaceUsed", 100f,
            "getCrew", 100, "getMarines", 0, "getMaxPersonnel", 300f, "getTotalPersonnel", 100, "getFuel", 400f, "getMaxFuel", 400f, "getStacksCopy", new ArrayList<Object>()));
        Global.setSector(mock(SectorAPI.class, m("getPlayerFleet", mock(CampaignFleetAPI.class, m("getCargo", pc, "getFleetData", mock(FleetDataAPI.class, m("getMinCrew", 80f)))),
            "getPersistentData", persistent, "getClock", mock(CampaignClockAPI.class, m("getTimestamp", 1000L, "getElapsedDaysSince", 5f)),
            "getEconomy", mock(EconomyAPI.class, m("getAllCommodityIds", ids, "getCommoditySpec", specFor, "getMarket", getMarket)))));
        p = TradeAdvisor.scan(here);
        check(p.actions.isEmpty(), "no actions when nothing known and nothing to sell");
        check(p.lines.get(p.lines.size() - 1).text.contains("Dock at more markets"), "tells you to visit more markets");
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
