import claudecaptain.Planner;
import claudecaptain.Planner.*;

import java.util.*;

/** Planner tests: pure Java, a made-up sector with prices that change with quantity. */
public class PlannerTest {
    static int pass = 0, fail = 0;
    static void check(boolean c, String m) { if (c) pass++; else { fail++; System.out.println("FAIL: " + m); } }
    static boolean near(float a, float b, float tol) { return Math.abs(a - b) <= tol; }

    /** Prices: buying raises the unit price by slip per unit, selling lowers it; open tariff 30%, black 0%. */
    static final class TP implements Prices {
        final Map<String, float[]> sd = new HashMap<String, float[]>();
        float slip = 0.0005f;
        void set(String node, String good, float sup, float dem) { sd.put(node + "|" + good, new float[]{sup, dem}); }
        public float buyTotal(String n, String g, float q, boolean black) {
            float[] v = sd.get(n + "|" + g); if (v == null) return 0f;
            return q * v[0] * (1f + slip * q) * (black ? 1f : 1.3f);
        }
        public float sellTotal(String n, String g, float q, boolean black) {
            float[] v = sd.get(n + "|" + g); if (v == null) return 0f;
            float qq = Math.min(q, 0.5f / slip);
            return qq * v[1] * (1f - slip * qq) * (black ? 1f : 0.7f);
        }
    }

    static Node node(String id, String sys, float hx, float hy) {
        Node n = new Node(id, id.substring(0, 1).toUpperCase() + id.substring(1));
        n.systemId = sys; n.systemName = sys; n.factionName = "Hegemony"; n.faction = "hegemony";
        n.hx = hx; n.hy = hy; n.x = 0; n.y = 0; n.jumpDist = 2000f; n.jumpName = sys + " jump-point";
        n.fuelPrice = 32f; n.suppliesPrice = 130f; n.size = 6;
        return n;
    }

    /** One good at one node, priced through TP: unit prices for the quick filter must match TP's q=1 prices. */
    static Q q(TP tp, Node n, String g, float sup, float dem, float stock, boolean illegal) {
        tp.set(n.id, g, sup, dem);
        Q q = new Q();
        q.openBuy = tp.buyTotal(n.id, g, 1, false); q.openSell = tp.sellTotal(n.id, g, 1, false);
        q.blackBuy = tp.buyTotal(n.id, g, 1, true); q.blackSell = tp.sellTotal(n.id, g, 1, true);
        q.openStock = stock; q.blackStock = stock / 2f; q.illegal = illegal;
        n.q.put(g, q);
        return q;
    }

    static Map<String, Good> goods() {
        Map<String, Good> g = new HashMap<String, Good>();
        g.put("metals", new Good("metals", "Metals", 1f, 30f));
        g.put("drugs", new Good("drugs", "Recreational Drugs", 1f, 200f));
        g.put("organs", new Good("organs", "Harvested Organs", 1f, 300f));
        Good f = new Good("fuel", "Fuel", 1f, 25f); f.tradeable = false; g.put("fuel", f);
        Good s = new Good("supplies", "Supplies", 1f, 100f); s.fleetSupply = true; g.put("supplies", s);
        return g;
    }

    static Fleet fleet(float credits, float cargo, String sys) {
        Fleet f = new Fleet();
        f.credits = credits; f.cargoFree = cargo; f.fuel = 200; f.fuelMax = 300; f.fuelPerLY = 2f; f.suppliesPerDay = 2f; f.burn = 10f; f.supplies = 1000f;
        f.systemId = sys; f.systemName = sys; f.hx = 0; f.hy = 0; f.x = 500; f.y = 0; f.jumpDist = 1500; f.jumpName = sys + " jump-point";
        return f;
    }

    static Settings st() { Settings s = new Settings(); s.remoteTrust = 1f; return s; }

    public static void main(String[] args) {
        // ---- travel maths
        check(near(invokeDays(1f, 0f, 10f), 1f, 0.001f), "burn 10 flies 1 ly a day");
        check(near(invokeDays(0f, 2000f, 10f), 1f, 0.001f), "2000 units in-system at burn 10 is a day");

        // ---- basic trip: buy metals at A (same system), sell at B (5 ly away)
        {
            TP tp = new TP();
            Node a = node("alpha", "S1", 0, 0), b = node("beta", "S2", 10000, 0);
            q(tp, a, "metals", 20f, 15f, 400f, false);
            q(tp, b, "metals", 70f, 60f, 10f, false);
            Fleet f = fleet(100000f, 300f, "S1");
            Result r = Planner.plan(Arrays.asList(a, b), goods(), f, tp, st());
            check(r.trips.size() == 1, "one trip alpha->beta (got " + r.trips.size() + ")");
            Trip t = r.trips.get(0);
            check(t.from == a && t.to == b, "direction alpha -> beta");
            float qty = 0f; for (Line l : t.lines) qty += l.qty;
            check(qty <= 300f + 0.01f, "hold respected (" + qty + ")");
            check(qty >= 250f, "fills most of the hold (" + qty + ")");
            for (Line l : t.lines) check(l.sellBlack, "sells on the black market (pays more, no tariff)");
            check(t.lines.get(0).buyBlack, "first line buys on the black market (cheaper, no tariff)");
            check(near(t.ly2, 5f, 0.01f), "5 ly between systems (" + t.ly2 + ")");
            check(near(t.fuel, 10f, 0.01f), "fuel = 5 ly x 2/ly (" + t.fuel + ")");
            check(near(t.fuelCost, 320f, 0.5f), "fuel cost at alpha's price (" + t.fuelCost + ")");
            check(near(t.supplyCost, t.days * 2f * 130f, 0.5f), "supplies = days x 2/day x 130");
            float expect = t.sellValue - t.buyCost - t.fuelCost - t.supplyCost;
            check(near(t.profit, expect, 0.5f), "profit counts every cost");
            check(near(t.perDay, t.profit / t.days, 0.5f), "profit per day");
            check(t.route2.contains("S1 jump-point") && t.route2.contains("5.0 ly") && t.route2.contains("S2 jump-point"), "route lists jump-points and ly: " + t.route2);
            float bc = 0f; for (Line l : t.lines) bc += tp.buyTotal("alpha", l.good, l.qty, l.buyBlack);
            check(near(bc, t.buyCost, 0.5f), "buy cost uses the quantity price, not unit x qty");
            check(!t.details().isEmpty() && t.title().contains("Metals"), "title and details");
        }

        // ---- quantity: a steep price curve stops buying before the stock runs out
        {
            TP tp = new TP(); tp.slip = 0.004f;
            Node a = node("alpha", "S1", 0, 0), b = node("beta", "S2", 4000, 0);
            q(tp, a, "metals", 20f, 15f, 2000f, false);
            q(tp, b, "metals", 170f, 160f, 10f, false);
            Fleet f = fleet(1000000f, 5000f, "S1");
            Settings s = st(); s.maxLines = 1;
            Result r = Planner.plan(Arrays.asList(a, b), goods(), f, tp, s);
            check(r.trips.size() == 1, "steep curve still trades");
            Line l = r.trips.get(0).lines.get(0);
            check(l.qty < 1000f, "stops before the stock runs out (" + l.qty + " of 1000 black)");
            float best = tp.sellTotal("beta", "metals", l.qty, true) - tp.buyTotal("alpha", "metals", l.qty, true);
            float more = tp.sellTotal("beta", "metals", l.qty + 10, true) - tp.buyTotal("alpha", "metals", l.qty + 10, true);
            float less = tp.sellTotal("beta", "metals", l.qty - 10, true) - tp.buyTotal("alpha", "metals", l.qty - 10, true);
            check(best >= more - 1f && best >= less - 1f, "chosen quantity is the most profitable (" + best + " vs " + less + "/" + more + ")");
        }

        // ---- ranking: profit per day first
        {
            TP tp = new TP();
            Node a = node("alpha", "S1", 0, 0), near = node("near", "S1", 0, 0), far = node("far", "S9", 60000, 0);
            near.x = 3000;
            q(tp, a, "metals", 20f, 15f, 600f, false);
            q(tp, near, "metals", 60f, 45f, 10f, false);
            q(tp, far, "metals", 130f, 120f, 10f, false);
            Fleet f = fleet(100000f, 300f, "S1");
            f.fuel = 300;
            Result r = Planner.plan(Arrays.asList(a, near, far), goods(), f, tp, st());
            Trip tn = null, tf = null;
            for (Trip t : r.trips) { if (t.from == a && t.to == near) tn = t; if (t.from == a && t.to == far) tf = t; }
            check(tn != null && tf != null, "both the near and far trips are listed");
            if (tn != null && tf != null) {
                check(tf.profit > tn.profit, "far trip earns more in total");
                check(tn.perDay > tf.perDay, "near trip earns more per day");
                check(r.trips.indexOf(tn) < r.trips.indexOf(tf), "ranked by profit per day");
                check(tn.ly2 == 0f && tn.fuel == 0f, "same system: no hyperspace, no fuel");
            }
        }

        // ---- budget and reserve
        {
            TP tp = new TP();
            Node a = node("alpha", "S1", 0, 0), b = node("beta", "S2", 4000, 0);
            q(tp, a, "metals", 20f, 15f, 2000f, false);
            q(tp, b, "metals", 70f, 60f, 10f, false);
            Settings s = st(); s.minProfit = 10f;
            Result r = Planner.plan(Arrays.asList(a, b), goods(), fleet(10000f, 5000f, "S1"), tp, s);
            check(near(r.budget, 5000f, 0.1f), "budget = min(80%, credits - 5,000 reserve) (" + r.budget + ")");
            check(!r.trips.isEmpty() && r.trips.get(0).buyCost <= 5000f + 0.5f, "buy order within budget");
            Result r2 = Planner.plan(Arrays.asList(a, b), goods(), fleet(4000f, 5000f, "S1"), tp, s);
            check(r2.trips.isEmpty() && r2.why.contains("reserve"), "no trips under the reserve: " + r2.why);
            Result r3 = Planner.plan(Arrays.asList(a, b), goods(), fleet(100000f, 0f, "S1"), tp, s);
            check(r3.trips.isEmpty() && r3.why.contains("full"), "full hold: " + r3.why);
        }

        // ---- contraband setting
        {
            TP tp = new TP();
            Node a = node("alpha", "S1", 0, 0), b = node("beta", "S2", 4000, 0);
            q(tp, a, "drugs", 100f, 80f, 200f, false);
            q(tp, b, "drugs", 400f, 380f, 0f, true);
            Fleet f = fleet(100000f, 300f, "S1");
            f.carried.put("organs", 10f);
            f.carried.put("supplies", 50f);
            q(tp, b, "organs", 600f, 640f, 0f, true);
            Settings s = st();
            Result inc = Planner.plan(Arrays.asList(a, b), goods(), f, tp, s);
            check(!inc.trips.isEmpty() && inc.trips.get(0).contraband, "include: drugs trip planned and flagged");
            check(!inc.trips.isEmpty() && inc.trips.get(0).notes.toString().contains("confiscate"), "contraband risk note");
            s.contraband = 1;
            Result so = Planner.plan(Arrays.asList(a, b), goods(), f, tp, s);
            check(so.trips.isEmpty(), "sell_only: never buys contraband");
            check(!so.sellTrips.isEmpty() && so.sellTrips.get(0).lines.get(0).good.equals("organs"), "sell_only: still sells carried organs");
            for (Trip t : so.sellTrips) for (Line l : t.lines) check(!l.good.equals("supplies"), "never sells the fleet's own supplies");
            s.contraband = 0;
            Result nv = Planner.plan(Arrays.asList(a, b), goods(), f, tp, s);
            check(nv.trips.isEmpty() && nv.sellTrips.isEmpty(), "never: no contraband at all");
        }

        // ---- fuel: unreachable markets are skipped; refuel planned at the buy market
        {
            TP tp = new TP();
            Node a = node("alpha", "S5", 20000, 0), b = node("beta", "S2", 4000, 0);
            q(tp, a, "metals", 20f, 15f, 400f, false);
            q(tp, b, "metals", 70f, 60f, 10f, false);
            Fleet f = fleet(100000f, 300f, "S1");
            f.fuel = 5f; // 10 ly away needs 20
            Result r = Planner.plan(Arrays.asList(a, b), goods(), f, tp, st());
            boolean any = false; for (Trip t : r.trips) if (t.from == a) any = true;
            check(!any, "can't reach alpha on 5 fuel");
            f.fuel = 30f; // 20 to reach alpha + 20% + two jumps (1.1.5 margin)
            Result r2 = Planner.plan(Arrays.asList(a, b), goods(), f, tp, st());
            Trip t = null; for (Trip x : r2.trips) if (x.from == a) t = x;
            check(t != null && t.refuel, "refuels at alpha for the 8 ly second leg");
            if (t != null) check(t.refuelQty > 0f && t.refuelCost > 0f, "refuel quantity and cost");
            a.fuelPrice = -1f;
            Result r3 = Planner.plan(Arrays.asList(a, b), goods(), f, tp, st());
            any = false; for (Trip x : r3.trips) if (x.from == a) any = true;
            check(!any, "no fuel for sale at alpha: trip skipped");
        }

        // ---- inhospitable sell market: open market refused, black only
        {
            TP tp = new TP();
            Node a = node("alpha", "S1", 0, 0), b = node("beta", "S2", 4000, 0);
            b.inhospitable = true;
            q(tp, a, "metals", 20f, 15f, 400f, false);
            q(tp, b, "metals", 70f, 60f, 10f, false);
            Result r = Planner.plan(Arrays.asList(a, b), goods(), fleet(100000f, 300f, "S1"), tp, st());
            check(!r.trips.isEmpty() && r.trips.get(0).lines.get(0).sellBlack, "inhospitable: sells on the black market");
            check(!r.trips.isEmpty() && !r.trips.get(0).sellStop.on, "inhospitable: docks dark");
            b.hasBlack = false;
            Result r2 = Planner.plan(Arrays.asList(a, b), goods(), fleet(100000f, 300f, "S1"), tp, st());
            check(r2.trips.isEmpty(), "inhospitable without a black market: no trip");
        }

        // ---- open market wins when it pays more (prefer black otherwise)
        {
            TP tp = new TP();
            Node a = node("alpha", "S1", 0, 0), b = node("beta", "S2", 4000, 0);
            q(tp, a, "metals", 20f, 15f, 400f, false);
            Q qb = q(tp, b, "metals", 70f, 60f, 10f, false);
            qb.blackSell = qb.openSell - 5f; // a market where the black market pays less
            Result r = Planner.plan(Arrays.asList(a, b), goods(), fleet(100000f, 300f, "S1"), tp, st());
            check(!r.trips.isEmpty() && !r.trips.get(0).lines.get(0).sellBlack, "open market chosen when it pays more");
            check(!r.trips.isEmpty() && r.trips.get(0).sellStop.on, "open market stop: transponder on");
        }

        // ---- max lines and multi-good orders
        {
            TP tp = new TP();
            Node a = node("alpha", "S1", 0, 0), b = node("beta", "S2", 4000, 0);
            q(tp, a, "metals", 20f, 15f, 100f, false);
            q(tp, b, "metals", 70f, 60f, 10f, false);
            q(tp, a, "organs", 200f, 150f, 50f, false);
            q(tp, b, "organs", 500f, 450f, 10f, false);
            Result r = Planner.plan(Arrays.asList(a, b), goods(), fleet(1000000f, 400f, "S1"), tp, st());
            check(!r.trips.isEmpty() && r.trips.get(0).lines.size() >= 2, "fills the hold with more than one good");
            Settings s = st(); s.maxLines = 1;
            Result r2 = Planner.plan(Arrays.asList(a, b), goods(), fleet(1000000f, 400f, "S1"), tp, s);
            check(!r2.trips.isEmpty() && r2.trips.get(0).lines.size() == 1, "max_lines = 1");
        }

        // ---- transponder decisions
        {
            Node n = node("beta", "S2", 0, 0);
            n.size = 6;
            Stop s = stop(n, true, false, 0f, 20000f);
            check(s.on && s.reason.contains("open market"), "open market needs it on");
            n.freePort = true;
            s = stop(n, false, true, 50000f, 0f);
            check(!s.on && s.reason.contains("free port"), "free port black-only: off");
            n.freePort = false; n.tolerant = true; n.factionName = "Independent";
            s = stop(n, false, true, 50000f, 0f);
            check(!s.on && !s.goDark, "tolerant faction, no patrols: off");
            n.patrols = 2;
            s = stop(n, false, true, 50000f, 0f);
            check(!s.on && s.goDark && s.risk == 1, "tolerant faction, other patrols around: go dark");
            n.patrols = 0;
            s = stop(n, true, true, 50000f, 20000f);
            check(!s.on && s.reason.contains("open market works dark"), "tolerant faction, open + black: dark (port trades openly with it off)");
            n.suspicion = 0.3f; n.smuggleTotal = 30000f; n.tradeTotal = 70000f; n.tolerant = false;
            check(Planner_suspicion(n, 0f, 0f) >= 0.3f - 0.001f, "story suspicion on top of the formula is kept");
            n.suspicion = 0f; n.smuggleTotal = 0f;
            n.tolerant = false; n.patrols = 0; n.tradeTotal = 200000f;
            s = stop(n, false, true, 2000f, 0f);
            check(s.on && s.suspicionIfOn < 0.05f, "small black trade after lots of open trade: stays on (" + s.suspicionIfOn + ")");
            n.tradeTotal = 0f;
            s = stop(n, false, true, 50000f, 0f);
            check(!s.on && !s.goDark && s.reason.contains("no patrols"), "big black trade, no patrols: off (" + s.reason + ")");
            check(s.suspicionIfOn > 0.5f, "big black trade would mean high suspicion (" + s.suspicionIfOn + ")");
            n.patrols = 3;
            s = stop(n, false, true, 50000f, 0f);
            check(!s.on && s.goDark && s.risk == 1, "patrols, no suspicion yet, big sale: dark (on, the sale itself triggers a scan as you leave) " + s.reason);
            s = stop(n, false, true, 2000f, 0f);
            check(s.on && s.repRisk < 0.5f, "patrols, small sale: stays on, no real risk " + s.reason);
            n.suspicion = 0.2f;
            s = stop(n, false, true, 50000f, 0f);
            check(s.on && s.risk == 2 && s.repRisk >= 1.5f, "patrols and 20% suspicion: on (dark adds the -2 for being seen to the same scan) " + s.reason);
            n.suspicion = 0.93f; n.patrols = 6; n.smuggleTotal = 50000f;
            s = stop(n, false, true, 20000f, 0f);
            check(s.on && s.repRisk > 4f, "Sphinx case (93% suspicion, 6 patrols): on, about 5 rep at risk either way " + s.reason);
            n.suspicion = 0f; n.smuggleTotal = 0f; n.patrols = 3;
            n.suspicion = 0f; n.inhospitable = true; n.patrols = 0;
            s = stop(n, false, true, 50000f, 0f);
            check(!s.on, "inhospitable: dark");
            check(near(Planner_suspicion(n, 60000f, 0f), Math.min(1f, 60000f / 60000f), 0.01f), "suspicion formula matches the game's");
        }

        // ---- reputation risk lowers the ranking
        {
            Trip safe = new Trip(), risky = new Trip();
            safe.profit = 20000f; safe.days = 5f; risky.profit = 24000f; risky.days = 5f;
            Stop a = new Stop(); a.node = node("x", "S1", 0, 0); a.repRisk = 0f;
            Stop b = new Stop(); b.node = node("y", "S2", 0, 0); b.node.factionName = "Hegemony"; b.repRisk = 5f;
            safe.sellStop = a; risky.sellStop = b;
            Settings st = st();
            Planner.riskNotes(safe, st); Planner.riskNotes(risky, st);
            check(safe.score > risky.score, "5 rep at risk (10,000 cr at 2,000/point) ranks a +24k trip below a safe +20k one");
            check(risky.notes.toString().contains("reputation risk at Y") && risky.notes.toString().contains("Hegemony"), "risk note " + risky.notes);
        }

        // ---- top-ups and remote trust (1.1.3)
        {
            TP tp = new TP();
            Node a = node("ta", "S1", 0, 0), b = node("tb", "S1", 3000, 0), c = node("tc", "S9", 8000, 0);
            a.fuelPrice = 30f; a.suppliesPrice = 100f;
            q(tp, a, "metals", 20f, 15f, 400f, false);
            q(tp, b, "metals", 60f, 55f, 10f, false);
            q(tp, c, "metals", 150f, 140f, 10f, false);
            Fleet f = fleet(100000f, 300f, "S1");
            f.atNode = "ta";
            f.supplies = 10f; f.fuel = 15f;
            Settings tst = st(); tst.remoteTrust = 0.6f;
            Result r = Planner.plan(Arrays.asList(a, b, c), goods(), f, tp, tst);
            Trip near = null, far = null;
            for (Trip x : r.trips) { if (x.to == b) near = x; if (x.to == c) far = x; }
            check(near != null && near.supQty >= 1f && near.refuel, "restocks supplies at the buy market when under supplies_days");
            check(near != null && near.lines.get(0).qty <= 300f - near.supQty + 0.5f, "restock takes hold space from the goods");
            check(far != null && far.refuelQty >= 1f, "tops up fuel for the far leg + reserve");
            check(far != null && far.trustedSell < far.sellValue, "a sale in another system is ranked below its quote");
            check(near != null && near.trustedSell == near.sellValue, "a sale in the same system keeps its full value");
            Settings low = st(); low.remoteTrust = 0.1f;
            Result r3 = Planner.plan(Arrays.asList(a, b, c), goods(), f, tp, low);
            boolean farListed = false; for (Trip x : r3.trips) if (x.to == c) farListed = true;
            check(!farListed, "a remote trip that only pays at full trust is not offered");
        }

        // ---- contraband = never skips goods illegal anywhere (1.1.3)
        {
            TP tp = new TP();
            Node a = node("da", "S1", 0, 0), b = node("db", "S1", 3000, 0);
            q(tp, a, "metals", 20f, 15f, 400f, false);
            q(tp, b, "metals", 60f, 55f, 10f, false);
            Map<String, Good> gs = goods();
            gs.get("metals").illegalSomewhere = true; // GameReader sets it when illegal at >= 25% of markets
            Settings st = st(); st.contraband = 0;
            Fleet f = fleet(100000f, 300f, "S1"); f.atNode = "da";
            Result r = Planner.plan(Arrays.asList(a, b), gs, f, tp, st);
            check(r.trips.isEmpty(), "contraband = never: a good illegal somewhere is not bought");
        }

        // ---- go_dark rule (1.1.0)
        {
            String keep = Planner.goDarkMode;
            Node strictN = node("c", "Culann", 0, 0); strictN.factionName = "Tri-Tachyon"; strictN.patrols = 0; strictN.suspicion = 0.3f;
            Planner.goDarkMode = "auto";
            Stop a = stop(strictN, false, true, 60000f, 0f);
            check(!a.on, "auto: strict market with no patrols seen goes dark (old behaviour)");
            Planner.goDarkMode = "independent_only";
            Stop b = stop(strictN, false, true, 60000f, 0f);
            check(b.on && b.reason.contains("go_dark"), "independent_only: strict faction market keeps the transponder on: " + b.reason);
            Node ind = node("e", "Cethlenn", 0, 0); ind.tolerant = true; ind.factionName = "independent";
            Stop c = stop(ind, false, true, 20000f, 0f);
            check(!c.on, "independent_only: tolerant market with no strict neighbours may go dark");
            ind.strictNearby = 2;
            Stop e = stop(ind, false, true, 20000f, 0f);
            check(e.on && e.reason.contains("strict-faction"), "independent_only: tolerant market next to strict markets keeps it on: " + e.reason);
            Planner.goDarkMode = "never";
            ind.strictNearby = 0;
            Stop f2 = stop(ind, false, true, 20000f, 0f);
            check(f2.on, "never: always on");
            Node hostile = node("h", "Den", 0, 0); hostile.factionName = "Hegemony"; hostile.inhospitable = true;
            Stop g = stop(hostile, false, true, 20000f, 0f);
            check(g.on && g.repRisk >= 99f, "never: a market that only trades dark is ranked out (rep risk " + g.repRisk + ")");
            Planner.goDarkMode = keep;
        }

        System.out.println("planner tests: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static Stop stop(Node n, boolean open, boolean black, float blackImpact, float openImpact) {
        Stop s = new Stop();
        s.node = n; s.needsOpen = open; s.usesBlack = black; s.blackImpact = blackImpact; s.openImpact = openImpact;
        Planner.decide(s);
        return s;
    }

    static float invokeDays(float ly, float units, float burn) {
        try {
            java.lang.reflect.Method m = Planner.class.getDeclaredMethod("travelDays", float.class, float.class, float.class);
            m.setAccessible(true);
            return (Float) m.invoke(null, ly, units, burn);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static float Planner_suspicion(Node n, float black, float open) {
        try {
            java.lang.reflect.Method m = Planner.class.getDeclaredMethod("suspicionAfter", Node.class, float.class, float.class);
            m.setAccessible(true);
            return (Float) m.invoke(null, n, black, open);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
