import claudecaptain.*;
import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;

import com.fs.starfarer.api.fleet.FleetLogisticsAPI;
import com.fs.starfarer.api.util.MutableValue;
import org.lwjgl.util.vector.Vector2f;

import java.util.*;

/** Game-adapter tests: GameReader reads mocked markets correctly; Trader trades and reports like the Trade screen. */
public class ReaderTest {
    static int pass = 0, fail = 0;
    static void check(boolean c, String m) { if (c) pass++; else { fail++; System.out.println("FAIL: " + m); } }
    static boolean near(float a, float b) { return Math.abs(a - b) < 0.01f; }
    static Map<String, Object> m(Object... kv) { return Mock.m(kv); }

    static final List<PlayerMarketTransaction> pluginReports = new ArrayList<PlayerMarketTransaction>();
    static final List<PlayerMarketTransaction> sectorReports = new ArrayList<PlayerMarketTransaction>();
    static int doubleSteps = 0;

    static FactionAPI faction(String id, String name, final boolean hostile, final boolean tolerant, final boolean inhospitable) {
        return Mock.of(FactionAPI.class, m("getId", id, "getDisplayName", name, "isHostileTo", (Mock.Fn) a -> hostile,
            "getCustomBoolean", (Mock.Fn) a -> tolerant && "allowsTransponderOffTrade".equals(a[0]),
            "isAtBest", (Mock.Fn) a -> inhospitable));
    }

    static SubmarketAPI sub(final String spec, final float tariff, final Map<String, Float> stock, final Set<String> illegalHere,
                            final MarketAPI[] owner, FactionAPI fac, final boolean black) {
        final CargoAPI cargo = Mock.cargo(stock, null);
        SubmarketPlugin plugin = Mock.of(SubmarketPlugin.class, m(
            "isIllegalOnSubmarket", (Mock.Fn) a -> a[0] instanceof String && illegalHere.contains((String) a[0]),
            "reportPlayerMarketTransaction", (Mock.Fn) a -> { pluginReports.add((PlayerMarketTransaction) a[0]); return null; },
            "isParticipatesInEconomy", true, "isBlackMarket", black));
        return Mock.of(SubmarketAPI.class, m("getSpecId", spec, "getTariff", tariff, "getCargo", cargo, "getCargoNullOk", cargo,
            "getPlugin", plugin, "getNameOneLine", black ? "Black Market" : "Open Market", "getMarket", (Mock.Fn) a -> owner[0], "getFaction", fac));
    }

    static MarketAPI market(String id, String name, final LocationAPI loc, Vector2f hyper, Vector2f at, FactionAPI fac,
                            final Map<String, float[]> prices, Map<String, Float> openStock, Map<String, Float> blackStock,
                            Set<String> illegal, boolean freePort, boolean hidden, boolean playerOwned, final MemoryAPI mem) {
        final MarketAPI[] self = new MarketAPI[1];
        final SubmarketAPI open = openStock == null ? null : sub("open_market", 0.3f, openStock, illegal, self, fac, false);
        final SubmarketAPI black = blackStock == null ? null : sub("black_market", 0f, blackStock, new HashSet<String>(), self, fac, true);
        SectorEntityToken ent = Mock.of(SectorEntityToken.class, m("getContainingLocation", loc, "getLocation", at, "isDiscoverable", false,
            "getName", name, "getMemoryWithoutUpdate", mem, "getRadius", 100f));
        final Set<String> ill = illegal;
        List<SubmarketAPI> subs = new ArrayList<SubmarketAPI>();
        if (open != null) subs.add(open);
        if (black != null) subs.add(black);
        self[0] = Mock.of(MarketAPI.class, m("getId", id, "getName", name, "getFaction", fac, "getFactionId", fac.getId(),
            "isHidden", hidden, "isPlanetConditionMarketOnly", false, "isPlayerOwned", playerOwned, "getPrimaryEntity", ent,
            "getLocationInHyperspace", hyper, "getSize", 6, "getStabilityValue", 5f, "isFreePort", freePort,
            "isIllegal", (Mock.Fn) a -> ill.contains((String) a[0]),
            "getSubmarket", (Mock.Fn) a -> "open_market".equals(a[0]) ? open : "black_market".equals(a[0]) ? black : null,
            "getSubmarketsCopy", subs,
            "getCommodityData", (Mock.Fn) a -> prices.containsKey((String) a[0]) ? Mock.of(CommodityOnMarketAPI.class, m("getId", a[0])) : null,
            "getSupplyPrice", (Mock.Fn) a -> { float[] p = prices.get((String) a[0]); double q = ((Number) a[1]).doubleValue(); return p == null ? 0f : (float) (p[0] * q * (1 + 0.001 * q)); },
            "getDemandPrice", (Mock.Fn) a -> { float[] p = prices.get((String) a[0]); double q = ((Number) a[1]).doubleValue(); return p == null ? 0f : (float) (p[1] * q * (1 - 0.0005 * q)); },
            "getMemoryWithoutUpdate", mem));
        return self[0];
    }

    public static void main(String[] args) {
        Global.setSettings(Mock.of(SettingsAPI.class, m("fileExistsInCommon", false)));
        Global.setFactory(Mock.of(FactoryAPI.class, m("createCargo", (Mock.Fn) a -> Mock.cargo(new HashMap<String, Float>(), null))));

        final List<CampaignFleetAPI> fleets = new ArrayList<CampaignFleetAPI>();
        SectorEntityToken jp = Mock.of(SectorEntityToken.class, m("getLocation", new Vector2f(3000, 0), "getName", "Galatia Jump-point"));
        LocationAPI galatia = Mock.of(LocationAPI.class, m("getId", "galatia", "getName", "Galatia Star System", "isHyperspace", false,
            "getFleets", fleets, "getJumpPoints", Arrays.asList(jp)));
        LocationAPI magec = Mock.of(LocationAPI.class, m("getId", "magec", "getName", "Magec Star System", "isHyperspace", false,
            "getFleets", new ArrayList<CampaignFleetAPI>(), "getJumpPoints", new ArrayList<SectorEntityToken>()));

        Map<String, float[]> pA = new HashMap<String, float[]>();
        pA.put("metals", new float[]{20f, 15f}); pA.put("drugs", new float[]{150f, 120f}); pA.put("fuel", new float[]{20f, 15f}); pA.put("supplies", new float[]{80f, 60f});
        Map<String, Float> openA = new HashMap<String, Float>(); openA.put("metals", 50f); openA.put("fuel", 500f);
        Map<String, Float> blackA = new HashMap<String, Float>(); blackA.put("metals", 30f); blackA.put("drugs", 40f);
        Set<String> illegalA = new HashSet<String>(Arrays.asList("drugs"));
        final Map<String, Object> memA = new HashMap<String, Object>();
        MemoryAPI mem = Mock.of(MemoryAPI.class, m("getString", (Mock.Fn) a -> (String) memA.get(a[0]), "getFloat", 0f));
        FactionAPI heg = faction("hegemony", "Hegemony", false, false, false);
        MarketAPI ancyra = market("ancyra", "Ancyra", galatia, new Vector2f(1000, 1000), new Vector2f(0, 0), heg, pA, openA, blackA, illegalA, false, false, false, mem);

        Map<String, float[]> pK = new HashMap<String, float[]>();
        pK.put("metals", new float[]{40f, 35f}); pK.put("drugs", new float[]{400f, 380f});
        FactionAPI pir = faction("pirates", "Pirates", false, true, false);
        MarketAPI kanta = market("kantas_den", "Kanta's Den", magec, new Vector2f(11000, 1000), new Vector2f(500, 0), pir, pK,
            new HashMap<String, Float>(), new HashMap<String, Float>(), new HashSet<String>(), true, false, false, mem);
        MarketAPI hidden = market("hidden", "Hidden Base", magec, new Vector2f(0, 0), new Vector2f(0, 0), pir, pK, new HashMap<String, Float>(), null, new HashSet<String>(), false, true, false, mem);
        MarketAPI enemy = market("enemy", "Enemy", magec, new Vector2f(0, 0), new Vector2f(0, 0), faction("tritachyon", "TT", true, false, false), pK, new HashMap<String, Float>(), null, new HashSet<String>(), false, false, false, mem);
        MarketAPI mine = market("mine", "My Colony", magec, new Vector2f(0, 0), new Vector2f(0, 0), heg, pK, new HashMap<String, Float>(), null, new HashSet<String>(), false, false, true, mem);

        final Map<String, MarketAPI> byId = new HashMap<String, MarketAPI>();
        for (MarketAPI x : Arrays.asList(ancyra, kanta, hidden, enemy, mine)) byId.put(x.getId(), x);

        Map<String, Float> held = new HashMap<String, Float>();
        held.put("organs", 10f); held.put("supplies", 100f); held.put("fuel", 200f); held.put("crew", 50f);
        List<CargoStackAPI> stacks = new ArrayList<CargoStackAPI>();
        for (String id : held.keySet()) stacks.add(Mock.of(CargoStackAPI.class, m("isNull", false, "isCommodityStack", true, "getCommodityId", id, "getSize", held.get(id))));
        final MutableValue credits = new MutableValue(50000f);
        CargoAPI pc = Mock.cargo(held, m("getCredits", credits, "getMaxCapacity", 500f, "getSpaceUsed", 110f, "getFuel", 200f, "getMaxFuel", 400f, "getStacksCopy", stacks));
        CampaignFleetAPI pf = Mock.of(CampaignFleetAPI.class, m("getCargo", pc, "getContainingLocation", galatia,
            "getLocationInHyperspace", new Vector2f(1000, 1000), "getLocation", new Vector2f(600, 0),
            "getLogistics", Mock.of(FleetLogisticsAPI.class, m("getFuelCostPerLightYear", 3f, "getTotalSuppliesPerDay", 1.5f)),
            "getFleetData", Mock.of(FleetDataAPI.class, m("getMembersListCopy", new ArrayList<Object>(), "getMinBurnLevel", 8f, "getBurnLevel", 9f)),
            "isTransponderOn", true));

        final Map<String, CommoditySpecAPI> specs = new HashMap<String, CommoditySpecAPI>();
        for (String[] s : new String[][]{{"metals", "Metals", "30"}, {"drugs", "Recreational Drugs", "200"}, {"fuel", "Fuel", "25"},
                {"supplies", "Supplies", "100"}, {"crew", "Crew", "50"}, {"organs", "Harvested Organs", "300"}}) {
            final String id = s[0];
            specs.put(id, Mock.of(CommoditySpecAPI.class, m("getId", id, "getName", s[1], "getBasePrice", Float.parseFloat(s[2]), "getCargoSpace", 1f,
                "isPersonnel", id.equals("crew"), "isFuel", id.equals("fuel"), "isMeta", false, "isNonEcon", false)));
        }
        EconomyAPI econ = Mock.of(EconomyAPI.class, m("getAllCommodityIds", new ArrayList<String>(specs.keySet()),
            "getCommoditySpec", (Mock.Fn) a -> specs.get((String) a[0]),
            "getMarketsCopy", new ArrayList<MarketAPI>(byId.values()),
            "getMarket", (Mock.Fn) a -> byId.get((String) a[0]),
            "doubleStep", (Mock.Fn) a -> { doubleSteps++; return null; }));
        CampaignUIAPI ui = Mock.of(CampaignUIAPI.class, m("getCurrentInteractionDialog", null));
        SectorAPI sector = Mock.of(SectorAPI.class, m("getPlayerFleet", pf, "getEconomy", econ, "getCampaignUI", ui,
            "getPersistentData", new HashMap<String, Object>(),
            "getClock", Mock.of(CampaignClockAPI.class, m("getTimestamp", 1000L, "getDateString", "Jun 18, c206")),
            "reportPlayerMarketTransaction", (Mock.Fn) a -> { sectorReports.add((PlayerMarketTransaction) a[0]); return null; }));
        Global.setSector(sector);

        // ---------------- reading
        GameReader.Snapshot sn = GameReader.read();
        check(sn.nodes.size() == 2, "hidden, hostile and player-owned markets skipped (" + sn.nodes.size() + ")");
        Planner.Node a = null, k = null;
        for (Planner.Node n : sn.nodes) { if (n.id.equals("ancyra")) a = n; if (n.id.equals("kantas_den")) k = n; }
        check(a != null && k != null, "both markets read");
        Planner.Q mq = a.q.get("metals");
        check(near(mq.openBuy, 20f * 1.001f * 1.3f), "open buy price includes 30% tariff (" + mq.openBuy + ")");
        check(near(mq.openSell, 15f * 0.9995f * 0.7f), "open sell price after 30% tariff (" + mq.openSell + ")");
        check(near(mq.blackBuy, 20f * 1.001f) && near(mq.blackSell, 15f * 0.9995f), "black market: no tariff");
        check(near(mq.openStock, 50f) && near(mq.blackStock, 30f), "stock per submarket");
        Planner.Q dq = a.q.get("drugs");
        check(dq.illegal && dq.openBuy < 0 && dq.openSell < 0 && dq.blackBuy > 0, "drugs: illegal on the open market, fine on the black market");
        check(k.freePort && k.tolerant && !a.freePort && !a.tolerant, "free port and transponder-tolerant flags");
        check(a.systemId.equals("galatia") && near(a.jumpDist, 3000f) && a.jumpName.equals("Galatia Jump-point"), "system and nearest jump-point");
        check(a.fuelPrice > 0 && a.suppliesPrice > 0, "running-cost prices read");
        check(sn.goods.containsKey("fuel") && !sn.goods.get("fuel").tradeable, "fuel kept for its price only");
        check(!sn.goods.containsKey("crew"), "crew is not trade cargo");
        Planner.Fleet f = sn.fleet;
        check(near(f.credits, 50000f) && near(f.cargoFree, 390f) && near(f.fuelPerLY, 3f), "fleet credits, hold, fuel use");
        check(near(f.suppliesPerDay, 1.5f) && near(f.burn, 9f), "fleet supplies/day and burn");
        check(f.carried.containsKey("organs") && !f.carried.containsKey("supplies") && !f.carried.containsKey("fuel"), "carried trade goods only");
        float tot = sn.prices.buyTotal("ancyra", "metals", 10f, false);
        check(near(tot, 20f * 10f * 1.01f * 1.3f), "prices: total for a quantity, tariff included");
        sn.prices.buyTotal("ancyra", "metals", 10f, false);
        check(sn.prices.calls == 1, "prices are memoized within one read");

        // ---------------- planning end to end on the read data
        Planner.Settings pst = new Planner.Settings(); pst.remoteTrust = 1f;
        Planner.Result r = Planner.plan(sn.nodes, sn.goods, sn.fleet, sn.prices, pst);
        check(!r.trips.isEmpty(), "plans a trip from the read data (" + r.why + ")");
        if (!r.trips.isEmpty()) {
            Planner.Trip t = r.trips.get(0);
            check(t.from.id.equals("ancyra") && t.to.id.equals("kantas_den"), "Ancyra -> Kanta's Den");
            check(t.sellStop.reason.contains("free port") && !t.sellStop.on, "Kanta's Den: free port, transponder off");
        }
        check(!r.sellTrips.isEmpty() && r.sellTrips.get(0).lines.get(0).good.equals("organs") == false || r.sellTrips.isEmpty(), "organs have no buyer in this test sector");

        // ---------------- trading like the Trade screen
        Trader.beginStop();
        Trader.Result b1 = Trader.buy(ancyra, false, "metals", 100f, 1e9f, CoreUITradeMode.OPEN);
        check(b1.ok && near(b1.qty, 50f), "buy limited to open-market stock (" + b1.text + ")");
        float cost = 20f * 50f * 1.05f * 1.3f;
        check(near(credits.get(), 50000f - cost), "credits paid include the quantity price and tariff (" + credits.get() + ")");
        check(near(held.get("metals"), 50f), "metals in the hold");
        check(near(ancyra.getSubmarket("open_market").getCargo().getCommodityQuantity("metals"), 0f), "taken from the market's stock");
        check(pluginReports.size() == 1 && sectorReports.size() == 1, "reported to the submarket and the sector");
        PlayerMarketTransaction tx = pluginReports.get(0);
        check(near(tx.getBought().getCommodityQuantity("metals"), 50f) && near(tx.getCreditValue(), -cost), "transaction: bought 50, paid credits");
        check(tx.getMarket() == ancyra && tx.getTradeMode() == CoreUITradeMode.OPEN, "transaction market and trade mode");
        check(!tx.getLineItems().isEmpty(), "line item recorded");

        Trader.Result b2 = Trader.buy(ancyra, false, "fuel", 10f, 1e9f, CoreUITradeMode.SNEAK);
        check(!b2.ok && b2.text.contains("open market is closed"), "docked dark: open market refused (" + b2.text + ")");
        Trader.Result b3 = Trader.buy(ancyra, false, "drugs", 10f, 1e9f, CoreUITradeMode.OPEN);
        check(!b3.ok, "illegal on the open market: refused");
        Trader.Result b4 = Trader.buy(ancyra, true, "drugs", 10f, 1e9f, CoreUITradeMode.SNEAK);
        check(b4.ok && near(b4.qty, 10f), "black market works while dark (" + b4.text + ")");
        Trader.Result b5 = Trader.buy(ancyra, true, "metals", 30f, 100f, CoreUITradeMode.OPEN);
        check(b5.ok && b5.qty < 30f && -b5.credits <= 100f, "spend cap trims the quantity (" + b5.text + ")");

        float before = credits.get();
        Trader.Result s1 = Trader.sell(ancyra, true, "metals", 1000f, CoreUITradeMode.OPEN);
        check(s1.ok && near(s1.qty, 50f + b5.qty), "sells what is held, no more (" + s1.text + ")");
        float q = s1.qty;
        check(near(credits.get() - before, 15f * q * (1f - 0.0005f * q)), "black market sale: no tariff");
        PlayerMarketTransaction stx = pluginReports.get(pluginReports.size() - 1);
        check(near(stx.getSold().getCommodityQuantity("metals"), q) && stx.getCreditValue() > 0, "sale transaction reported");
        check(pluginReports.size() == sectorReports.size(), "every trade reported to both");
        Trader.endStop();
        check(doubleSteps == 1, "economy stepped once after the stop");
        Trader.endStop();
        check(doubleSteps == 1, "no extra step without new trades");

        memA.put("$tradeMode", "SNEAK");
        InteractionDialogAPI d = Mock.of(InteractionDialogAPI.class, m("getInteractionTarget", ancyra.getPrimaryEntity()));
        check(Trader.tradeMode(d) == CoreUITradeMode.SNEAK, "trade mode read from the docking rules");
        memA.put("$tradeMode", "NONE");
        check(Trader.tradeMode(d) == CoreUITradeMode.NONE, "refused docking reads as NONE");

        System.out.println("reader/trader tests: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
