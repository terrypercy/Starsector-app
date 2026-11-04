package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trade advice while docked at a market. It learns as you play: every market you
 * dock at has its open-market prices recorded (what you would pay, what you would
 * get, stock), so suggestions only use prices the game has already shown you.
 *
 * Prices are kept in the save's persistent data as plain java.util strings, so the
 * save still loads if the mod is removed.
 */
public final class TradeAdvisor {

    private TradeAdvisor() {}

    static final String KEY = "claudebridge_prices";

    /** A line of text in the widget: level 0 info, 1 good, 2 watch, 3 problem. */
    public static class Line {
        public final int level;
        public final String text;
        Line(int level, String text) { this.level = level; this.text = text; }
    }

    /** One suggested trade with the tasks that carry it out. */
    public static class Action {
        public final int score;
        public final String title;
        public final String why;
        public final List<String> tasks;
        Action(int score, String title, String why, List<String> tasks) {
            this.score = score; this.title = title; this.why = why; this.tasks = tasks;
        }
    }

    public static class Plan {
        public String market = "?";
        public final List<Line> lines = new ArrayList<Line>();
        public final List<Action> actions = new ArrayList<Action>();
    }

    /** A remembered price at one market. */
    public static class Quote {
        public String marketId;
        public float buy, sell, stock, ageDays;
    }

    private static String n(float v) { return Misc.getWithDGS(Math.round(v)); }

    private static List<String> list(String... t) {
        List<String> l = new ArrayList<String>();
        Collections.addAll(l, t);
        return l;
    }

    // ---------------------------------------------------------------- price book (learned as you play)

    @SuppressWarnings("unchecked")
    static Map<String, String> book() {
        Map<String, Object> pd = Global.getSector().getPersistentData();
        Object o = pd.get(KEY);
        if (!(o instanceof HashMap)) {
            o = new HashMap<String, String>();
            pd.put(KEY, o);
        }
        return (Map<String, String>) o;
    }

    private static boolean tradeable(String id, CommoditySpecAPI spec) {
        if (spec == null) return false;
        if (spec.isMeta() || spec.isNonEcon() || spec.isPersonnel()) return false;
        return !id.equals("supplies") && !id.equals("fuel");
    }

    /** Records this market's open-market prices for every commodity. */
    static int record(MarketAPI m, SubmarketAPI open) {
        Map<String, String> b = book();
        float tariff = open.getTariff();
        long now = Global.getSector().getClock().getTimestamp();
        int count = 0;
        for (String id : Global.getSector().getEconomy().getAllCommodityIds()) {
            CommoditySpecAPI spec = Global.getSector().getEconomy().getCommoditySpec(id);
            if (!tradeable(id, spec)) continue;
            try {
                float buy = m.getSupplyPrice(id, 1, true) * (1f + tariff);
                float sell = m.getDemandPrice(id, 1, true) * (1f - tariff);
                float stock = open.getCargo().getCommodityQuantity(id);
                boolean noBuy = open.getPlugin().isIllegalOnSubmarket(id, SubmarketPlugin.TransferAction.PLAYER_BUY);
                boolean noSell = open.getPlugin().isIllegalOnSubmarket(id, SubmarketPlugin.TransferAction.PLAYER_SELL);
                b.put(m.getId() + "|" + id, (noBuy ? -1f : buy) + ";" + (noSell ? -1f : sell) + ";" + stock + ";" + now);
                count++;
            } catch (Throwable ignored) {}
        }
        return count;
    }

    /** All remembered quotes for a commodity, except at the given market. */
    static List<Quote> quotes(String commodity, String exceptMarketId) {
        List<Quote> out = new ArrayList<Quote>();
        for (Map.Entry<String, String> e : book().entrySet()) {
            String k = e.getKey();
            int bar = k.lastIndexOf('|');
            if (bar < 0 || !k.substring(bar + 1).equals(commodity)) continue;
            String mid = k.substring(0, bar);
            if (mid.equals(exceptMarketId)) continue;
            String[] p = e.getValue().split(";");
            if (p.length < 4) continue;
            try {
                Quote q = new Quote();
                q.marketId = mid;
                q.buy = Float.parseFloat(p[0]);
                q.sell = Float.parseFloat(p[1]);
                q.stock = Float.parseFloat(p[2]);
                q.ageDays = Global.getSector().getClock().getElapsedDaysSince(Long.parseLong(p[3]));
                out.add(q);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** The best remembered place to sell, skipping markets that are now hostile or gone, and prices too old to trust. */
    static Quote bestSell(String commodity, String exceptMarketId) {
        Quote best = null;
        float ignore = Rules.f("trade_ignore_days", 120f);
        for (Quote q : quotes(commodity, exceptMarketId)) {
            if (q.sell <= 0 || q.ageDays > ignore) continue;
            MarketAPI m = Global.getSector().getEconomy().getMarket(q.marketId);
            if (m == null || m.getFaction().isHostileTo("player")) continue;
            if (best == null || q.sell > best.sell) best = q;
        }
        return best;
    }

    static int knownMarkets() {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (String k : book().keySet()) {
            int bar = k.lastIndexOf('|');
            if (bar > 0) ids.add(k.substring(0, bar));
        }
        return ids.size();
    }

    private static String where(MarketAPI here, Quote q) {
        MarketAPI m = Global.getSector().getEconomy().getMarket(q.marketId);
        if (m == null) return q.marketId;
        String name = m.getName();
        try {
            float ly = Misc.getDistanceLY(here.getPrimaryEntity(), m.getPrimaryEntity());
            name += ly < 0.05f ? " (same system)" : String.format(" (%.1f ly)", ly);
        } catch (Throwable ignored) {}
        return name;
    }

    // ---------------------------------------------------------------- routes

    /** Fuel for the round trip to the other market, priced here. */
    static float tripFuelCost(CampaignFleetAPI pf, MarketAPI here, Quote to, float tariff) {
        try {
            MarketAPI m = Global.getSector().getEconomy().getMarket(to.marketId);
            float ly = Misc.getDistanceLY(here.getPrimaryEntity(), m.getPrimaryEntity());
            float perLy = pf.getLogistics().getFuelCostPerLightYear();
            float fuelPrice = here.getSupplyPrice("fuel", 1, true) * (1f + tariff);
            if (Float.isNaN(ly) || ly < 0f || ly > 200f) return 0f;
            return ly * 2f * perLy * fuelPrice;
        } catch (Throwable t) { return 0f; }
    }

    /** Second stop: the best thing to buy at the other market and bring back here, from prices seen there. */
    static String returnLeg(MarketAPI here, Quote to, float tariff, float hold) {
        String bestName = null;
        float bestProfit = 0f;
        float ignore = Rules.f("trade_ignore_days", 120f);
        for (String id : Global.getSector().getEconomy().getAllCommodityIds()) {
            CommoditySpecAPI spec = Global.getSector().getEconomy().getCommoditySpec(id);
            if (!tradeable(id, spec)) continue;
            try {
                String v = book().get(to.marketId + "|" + id);
                if (v == null) continue;
                String[] p = v.split(";");
                float buyThere = Float.parseFloat(p[0]), stockThere = Float.parseFloat(p[2]);
                float age = Global.getSector().getClock().getElapsedDaysSince(Long.parseLong(p[3]));
                if (buyThere <= 0 || stockThere < 5 || age > ignore) continue;
                float sellHere = here.getDemandPrice(id, 1, true) * (1f - tariff);
                float per = sellHere - buyThere;
                float qty = Math.min(stockThere, hold / Math.max(0.01f, spec.getCargoSpace()));
                float profit = per * qty;
                if (profit > bestProfit) { bestProfit = profit; bestName = spec.getName(); }
            } catch (Throwable ignored) {}
        }
        if (bestName == null || bestProfit < Rules.f("trade_min_profit", 200f)) return "";
        return " Return leg: bring " + bestName + " back here for about +" + n(bestProfit) + ".";
    }

    // ---------------------------------------------------------------- advice

    /** Diagnostics for the self-test: the biggest remembered buy-here / sell-there gaps, before fuel and quantity checks. */
    static String gaps(MarketAPI here, int max) {
        List<String[]> rows = new ArrayList<String[]>();
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (String k : book().keySet()) { int bar = k.lastIndexOf('|'); if (bar > 0) ids.add(k.substring(bar + 1)); }
        final List<Float> keys = new ArrayList<Float>();
        for (String id : ids) {
            for (Quote from : quotes(id, "")) {
                if (here != null && !from.marketId.equals(here.getId())) continue;
                if (from.buy <= 0 || from.stock < 1) continue;
                for (Quote to : quotes(id, from.marketId)) {
                    if (to.sell <= 0) continue;
                    float g = to.sell - from.buy;
                    rows.add(new String[]{String.valueOf(g), id + " " + from.marketId + "->" + to.marketId + " " + Math.round(from.buy) + "->" + Math.round(to.sell) + " (" + Math.round(g) + "/u, stock " + Math.round(from.stock) + ")"});
                }
            }
        }
        Collections.sort(rows, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) { return Float.compare(Float.parseFloat(b[0]), Float.parseFloat(a[0])); }
        });
        StringBuilder sb = new StringBuilder(rows.isEmpty() ? "none (need prices from 2+ markets)" : "");
        for (int i = 0; i < rows.size() && i < max; i++) sb.append(i == 0 ? "" : "; ").append(rows.get(i)[1]);
        return sb.toString();
    }

    public static Plan scan(MarketAPI market) {
        Plan plan = new Plan();
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        if (pf == null || market == null) return plan;
        plan.market = market.getName();
        SubmarketAPI open = market.getSubmarket("open_market");
        if (open == null) {
            plan.lines.add(new Line(2, "No open market here."));
            return plan;
        }
        CargoAPI c = pf.getCargo();
        float tariff = open.getTariff();
        int recorded = record(market, open);
        float credits = c.getCredits().get();
        float free = Math.max(0f, c.getMaxCapacity() - c.getSpaceUsed());
        List<Action> cand = new ArrayList<Action>();

        plan.lines.add(new Line(0, "Tariff " + Math.round(tariff * 100) + "% | " + n(credits) + " cr"));
        plan.lines.add(new Line(c.getSpaceUsed() > c.getMaxCapacity() ? 3 : 0,
                "Cargo " + n(c.getSpaceUsed()) + "/" + n(c.getMaxCapacity()) + " | free " + n(free)));

        // ---- crew
        float minCrew = 0f;
        try { minCrew = pf.getFleetData().getMinCrew(); } catch (Throwable ignored) {}
        int crew = c.getCrew();
        plan.lines.add(new Line(crew < minCrew ? 3 : 1, "Crew " + crew + " (need " + Math.round(minCrew) + ") | marines " + c.getMarines()));
        if (crew < minCrew) {
            float stock = open.getCargo().getCommodityQuantity("crew");
            float room = Math.max(0f, c.getMaxPersonnel() - c.getTotalPersonnel());
            int qty = (int) Math.floor(Math.min(Math.min(stock, room), (float) Math.ceil(minCrew - crew) + 5f));
            float unit1 = market.getSupplyPrice("crew", 1, true) * (1f + tariff);
            if (unit1 > 0f) qty = (int) Math.min(qty, Math.floor(credits * Rules.f("buy_budget_pct", 50f) / 100f / unit1));
            if (qty > 0) {
                float cost = market.getSupplyPrice("crew", qty, true) * (1f + tariff);
                cand.add(new Action(90, "Hire " + qty + " crew", "Below the crew your ships need; costs about " + n(cost) + " cr.",
                        list("buy crew " + qty)));
            }
        }

        // ---- sell what you carry
        for (CargoStackAPI s : c.getStacksCopy()) {
            if (s.isNull() || !s.isCommodityStack()) continue;
            String id = s.getCommodityId();
            CommoditySpecAPI spec = Global.getSector().getEconomy().getCommoditySpec(id);
            if (!tradeable(id, spec)) continue;
            float qty = s.getSize();
            if (qty < 1) continue;
            boolean illegalOpen = open.getPlugin().isIllegalOnSubmarket(id, SubmarketPlugin.TransferAction.PLAYER_SELL);
            // black market: no tariff, so it usually pays more (rule prefer_black_market, on by default)
            SubmarketAPI black = market.getSubmarket("black_market");
            boolean blackOk = black != null && Rules.b("prefer_black_market", true) && !Shipyard.blackBanned(market); // 1.1.19
            float openValue = illegalOpen ? 0f : market.getDemandPrice(id, qty, true) * (1f - tariff);
            float blackValue = blackOk ? market.getDemandPrice(id, qty, true) * (1f - black.getTariff()) : 0f;
            boolean useBlack = blackValue > openValue;
            if (!useBlack && illegalOpen) {
                plan.lines.add(new Line(2, spec.getName() + ": can't sell here (illegal)"));
                continue;
            }
            float value = useBlack ? blackValue : openValue;
            float unit = value / qty;
            Quote best = bestSell(id, market.getId());
            if (best != null && best.sell > unit * 1.15f) {
                plan.lines.add(new Line(2, "Hold " + spec.getName() + ": " + where(market, best) + " pays " + n(best.sell - unit) + " more/unit"));
            } else {
                int sc = 45 + (int) Math.min(30f, value / 500f);
                cand.add(new Action(sc, "Sell " + n(qty) + " " + spec.getName() + (useBlack ? " (black, +" : " (+") + n(value) + ")",
                        "About " + n(unit) + " cr/unit" + (useBlack ? " on the black market (no tariff; open market " + (illegalOpen ? "won't buy it" : "pays " + n(openValue / qty)) + ")" : " after tariff")
                                + (best == null ? "; no better price known." : "; best known elsewhere " + n(best.sell) + "."),
                        list("sell " + id + " all" + (useBlack ? " black" : ""))));
            }
        }

        // ---- buy here, sell where you've seen a better price
        for (String id : Global.getSector().getEconomy().getAllCommodityIds()) {
            CommoditySpecAPI spec = Global.getSector().getEconomy().getCommoditySpec(id);
            if (!tradeable(id, spec)) continue;
            try {
                if (open.getPlugin().isIllegalOnSubmarket(id, SubmarketPlugin.TransferAction.PLAYER_BUY)) continue;
                float stock = open.getCargo().getCommodityQuantity(id);
                if (stock < 1) continue;
                float unitCost = market.getSupplyPrice(id, 1, true) * (1f + tariff);
                if (unitCost <= 0) continue;
                Quote best = bestSell(id, market.getId());
                if (best == null) continue;
                boolean old = best.ageDays > Rules.f("trade_stale_days", 60f);
                float sellAt = old ? best.sell * 0.85f : best.sell; // old prices drift: count on less
                float margin = sellAt - unitCost;
                if (margin < Math.max(8f, unitCost * 0.10f)) continue;
                float spaceUnits = free / Math.max(0.01f, spec.getCargoSpace());
                int qty = (int) Math.floor(Math.min(Math.min(stock, spaceUnits), credits * Rules.f("buy_budget_pct", 50f) / 100f / unitCost));
                if (qty < 5) continue;
                float cost = market.getSupplyPrice(id, qty, true) * (1f + tariff);
                float trip = tripFuelCost(pf, market, best, tariff);
                float profit = sellAt * qty - cost - trip;
                if (profit < Rules.f("trade_min_profit", 200f)) continue;
                int sc = 40 + (int) Math.min(45f, profit / 300f);
                String back = returnLeg(market, best, tariff, c.getMaxCapacity());
                cand.add(new Action(sc, "Buy " + qty + " " + spec.getName() + " (-" + n(cost) + ")",
                        "Sell at " + where(market, best) + " for about +" + n(profit) + " after fuel (" + n(trip) + "). Price seen "
                                + Math.round(best.ageDays) + " days ago" + (old ? " - old, counted 15% lower" : "") + "." + back,
                        list("buy " + id + " " + qty)));
            } catch (Throwable ignored) {}
        }

        // ---- top up fuel and supplies when low
        try {
            float perMonth = 0f;
            for (com.fs.starfarer.api.fleet.FleetMemberAPI fm : pf.getFleetData().getMembersListCopy()) {
                if (!fm.isMothballed()) perMonth += fm.getStats().getSuppliesPerMonth().getModifiedValue();
            }
            float perDay = perMonth / 30f;
            float sup = c.getSupplies();
            float usePerDay = perDay;
            try { usePerDay = Math.max(perDay, pf.getLogistics().getTotalSuppliesPerDay()); } catch (Throwable ignored) {}
            int stored = Advisor.storedSupplies(market);
            if (stored > 0 && usePerDay > 0f && sup / usePerDay < Rules.f("supplies_low_days", 25f)) {
                int take = (int) Math.min(stored, Math.max(5f, perDay * Rules.f("supplies_target_days", 45f) - sup));
                cand.add(new Action(96, "Take " + take + " supplies from storage", "Free - already yours, stored here.",
                        list("storage take supplies " + take)));
                sup += take;
            }
            if (perDay > 0f && sup / usePerDay < Rules.f("supplies_low_days", 25f)) {
                float want = perDay * Rules.f("supplies_target_days", 45f) - sup;
                float stockS = open.getCargo().getCommodityQuantity("supplies");
                float unitS = market.getSupplyPrice("supplies", 1, true) * (1f + tariff);
                float roomS = free / Math.max(0.01f, Global.getSector().getEconomy().getCommoditySpec("supplies").getCargoSpace());
                int qty = (int) Math.floor(Math.min(Math.min(want, stockS), roomS));
                if (unitS > 0f) qty = (int) Math.min(qty, Math.floor(credits * Rules.f("buy_budget_pct", 50f) / 100f / unitS));
                float baseS = 100f;
                try { baseS = Global.getSector().getEconomy().getCommoditySpec("supplies").getBasePrice(); } catch (Throwable ignored) {}
                boolean pricey = unitS > baseS * 2.5f;
                if (pricey) qty = (int) Math.min(qty, Math.max(0f, perDay * Rules.f("supplies_low_days", 25f) - sup));
                if (qty >= 5) {
                    float cost = market.getSupplyPrice("supplies", qty, true) * (1f + tariff);
                    cand.add(new Action(sup / usePerDay < Rules.f("supplies_critical_days", 12f) ? 95 : 70, "Buy " + qty + " supplies (-" + n(cost) + ")",
                            "Only " + Math.round(sup / usePerDay) + " days left at the current rate. " + n(unitS) + "/unit"
                                    + (pricey ? " - expensive here, so only enough to stay safe." : "."),
                            list("buy supplies " + qty)));
                }
            }
        } catch (Throwable ignored) {}
        if (c.getFuel() < c.getMaxFuel() * 0.5f) {
            int qty = (int) Math.floor(c.getMaxFuel() - c.getFuel());
            float stock = open.getCargo().getCommodityQuantity("fuel");
            qty = (int) Math.min(qty, stock);
            if (qty > 10) {
                float cost = market.getSupplyPrice("fuel", qty, true) * (1f + tariff);
                if (cost < credits * 0.6f) cand.add(new Action(50, "Refuel " + qty + " (-" + n(cost) + ")", "Fuel is under half.", list("buy fuel " + qty)));
            }
        }

        Collections.sort(cand, new Comparator<Action>() {
            public int compare(Action a, Action b) { return b.score - a.score; }
        });
        for (Action a : cand) {
            if (plan.actions.size() >= 3) break;
            plan.actions.add(a);
        }
        int known = knownMarkets();
        plan.lines.add(new Line(0, "Prices known at " + known + " market" + (known == 1 ? "" : "s") + (recorded > 0 ? " (this one updated)" : "")));
        if (plan.actions.isEmpty()) {
            plan.lines.add(new Line(0, known <= 1 ? "Dock at more markets to learn where goods sell for more."
                    : "No profitable trade from here with the prices seen so far."));
        }
        return plan;
    }
}
