package claudecaptain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The trip planner. Pure Java (no game classes), so it can be tested on its own:
 * GameReader turns the sector into Nodes and a Prices function, and this class
 * finds the best buy -> fly -> sell trips.
 *
 * For each pair of markets it fills the hold with the best mix of goods as one
 * order, picks the open or black market for every line, counts tariffs, price
 * changes with quantity (through the Prices function), fuel per light year and
 * supplies per day, and decides the transponder setting at each stop.
 */
public final class Planner {

    private Planner() {}

    // ------------------------------------------------------------------ inputs

    /** Live prices. Totals for a whole quantity, so price changes with quantity are included. */
    public interface Prices {
        /** Credits paid for qty units, tariff included. */
        float buyTotal(String node, String good, float qty, boolean black);
        /** Credits received for qty units, after tariff. */
        float sellTotal(String node, String good, float qty, boolean black);
    }

    public static final class Settings {
        public float budgetPct = 80f, reserve = 5000f, minProfit = 1000f, maxDays = 60f;
        public int maxLines = 4;
        /** 2 include contraband, 1 only sell contraband already carried, 0 never. */
        public int contraband = 2;
        public boolean preferBlack = true;
        public int maxPairs = 60;
        /** Credits one point of reputation is worth when ranking trips (trips that risk reputation rank lower). */
        public float repValue = 2000f;
        /** never | independent_only | auto: when the transponder may be off at a stop. */
        public String goDark = "auto";
        /** Fuel kept for this many light years beyond the trip (topped up at the buy market). */
        public float fuelReserveLy = 10f;
        /** Supplies kept aboard for this many days beyond the trip (topped up at the buy market). */
        public float suppliesDays = 60f;
        /** Sell prices read from another star system came in at about half on arrival (1.1 trips 4 and 5):
         *  trips that sell outside the buy market's system are ranked with their sale value times this. */
        public float remoteTrust = 0.6f;
    }

    /** The go_dark rule for {@link #decide}; set by {@link #plan} and by the runner. */
    public static volatile String goDarkMode = "auto";

    public static final class Good {
        public String id, name;
        public float space = 1f, base = 0f;
        /** Something the fleet itself uses (supplies): never sold from the fleet's own stock. */
        public boolean fleetSupply;
        /** False for goods kept only for their prices (fuel goes in the tanks, not the hold). */
        public boolean tradeable = true;
        /** Illegal at a quarter or more of the sector's markets (drugs, organs, weapons...): patrols seize it in a scan (1.1.4). */
        public boolean illegalSomewhere;
        public int illegalAt;
        public Good(String id, String name, float space, float base) { this.id = id; this.name = name; this.space = space; this.base = base; }
    }

    /** One good at one market. Unit prices include the tariff; -1 means that submarket won't do it. */
    public static final class Q {
        public float openBuy = -1f, openSell = -1f, blackBuy = -1f, blackSell = -1f;
        public float openStock, blackStock;
        /** Illegal under this market's faction (contraband here). */
        public boolean illegal;
    }

    public static final class Node {
        public String id, name, systemId, systemName = "hyperspace", faction = "", factionName = "";
        public boolean freePort, tolerant, inhospitable, hasOpen = true, hasBlack = true;
        /** Location in hyperspace, location inside its system, distance to its nearest jump point. */
        public float hx, hy, x, y, jumpDist;
        public String jumpName = "";
        public int size = 5;
        public float stability = 5f;
        /** Patrols in this system that would bother a fleet with its transponder off. */
        public int patrols;
        /** Markets in the same system (not this one) whose factions care about the transponder: their patrols come by. */
        public int strictNearby;
        /** Smuggling suspicion now (0..1) and the trade totals it is computed from. */
        public float suspicion, smuggleTotal, tradeTotal;
        public float fuelPrice = -1f, suppliesPrice = -1f;
        public final Map<String, Q> q = new HashMap<String, Q>();
        public Node(String id, String name) { this.id = id; this.name = name; }
        public String toString() { return name; }
    }

    public static final class Fleet {
        public float credits, cargoFree, fuel, fuelMax, fuelPerLY = 1f, suppliesPerDay, burn = 8f, supplies;
        public String systemId, systemName = "hyperspace", atNode, jumpName = "";
        public float hx, hy, x, y, jumpDist;
        public final Map<String, Float> carried = new HashMap<String, Float>();
    }

    // ------------------------------------------------------------------ outputs

    public static final class Line {
        public String good, name;
        public float qty, cost, value, space = 1f;
        public boolean buyBlack, sellBlack, contraband;
    }

    /** One market stop and the transponder decision for it. */
    public static final class Stop {
        public Node node;
        public boolean needsOpen, usesBlack;
        public boolean on = true, goDark;
        /** 0 fine, 1 watch, 2 risky. */
        public int risk;
        public String reason = "";
        /** Expected reputation points lost at this stop (patrol scans, being seen dark, smuggling records). */
        public float repRisk;
        /** Predicted smuggling suspicion if the black-market part is done with the transponder on. */
        public float suspicionIfOn;
        public float blackImpact, openImpact;
        public boolean contrabandAboard, buysContraband;
        public String tLabel() { return (on ? "on" : goDark ? "dark" : "off"); }
    }

    public static final class Trip {
        public Node from, to;
        public final List<Line> lines = new ArrayList<Line>();
        public float buyCost, sellValue, ly1, ly2, days1, days2, days, fuel, fuelCost, supplyCost, profit, perDay;
        /** Expected reputation loss for the whole trip, and the ranking score: profit per day after pricing that risk. */
        public float repRisk, score;
        public boolean refuel;
        public float refuelQty, refuelCost;
        /** Supplies bought at the buy market to keep supplies_days aboard (1.1.3); fuel and supply targets for the runner. */
        public float supQty, supCost, fuelTarget, supTarget;
        /** Sale value counted for ranking (remote sell prices discounted, see Settings.remoteTrust). */
        public float trustedSell;
        public Stop buyStop, sellStop;
        public boolean sellOnly, contraband;
        /** 1.2.7: just fly there and dock (plus refill when refuel is set); no trading. */
        public boolean goOnly;
        public String route1 = "", route2 = "";
        public final List<String> notes = new ArrayList<String>();

        public String key() { return (from == null ? "-" : from.id) + ">" + to.id; }

        /** Short name for the panel button. */
        public String title() {
            if (goOnly) return "Go to " + to.name + (refuel ? " + refill" : "");
            if (lines.isEmpty()) return "?";
            Line l = lines.get(0);
            String more = lines.size() > 1 ? " +" + (lines.size() - 1) : "";
            return (sellOnly ? "Sell " : "") + fmt(l.qty) + " " + l.name + more + " -> " + to.name;
        }

        public String money() {
            return (profit >= 0 ? "+" : "") + fmt(profit) + " cr | " + days(days) + " | " + (perDay >= 0 ? "+" : "") + fmt(perDay) + "/day";
        }

        public String where() {
            String risk = repRisk >= 0.5f ? " | rep risk " + Math.round(Math.max(1f, repRisk)) : "";
            if (goOnly) return "Go to " + to.name + " | T " + sellStop.tLabel();
            if (sellOnly) return "Sell at " + to.name + (lines.get(0).sellBlack ? " (black)" : " (open)") + " | T " + sellStop.tLabel() + risk;
            return "Buy " + from.name + " (" + subs(true) + ") | T " + buyStop.tLabel() + "->" + sellStop.tLabel() + risk;
        }

        private String subs(boolean buying) {
            boolean o = false, b = false;
            for (Line l : lines) { if (buying ? l.buyBlack : l.sellBlack) b = true; else o = true; }
            return o && b ? "open+black" : b ? "black" : "open";
        }

        /** Every detail, one item per line, for the selected trip and plans.txt. */
        public List<String> details() {
            List<String> d = new ArrayList<String>();
            if (goOnly) { d.add("GO to " + to.name + " (" + to.systemName + ", " + to.factionName + ")" + (refuel ? ", then refill fuel and supplies" : "")); return d; }
            if (!sellOnly) {
                d.add("BUY at " + from.name + " (" + from.systemName + ", " + from.factionName + ")");
                for (Line l : lines) d.add("  " + fmt(l.qty) + " " + l.name + ", " + (l.buyBlack ? "black" : "open") + " market, -" + fmt(l.cost) + " cr" + (l.contraband ? " [contraband]" : ""));
                if (refuel && refuelQty >= 1f) d.add("  refuel " + fmt(refuelQty) + " fuel there, -" + fmt(refuelCost) + " cr");
                if (supQty >= 1f) d.add("  restock " + fmt(supQty) + " supplies there, -" + fmt(supCost) + " cr");
                d.add("  transponder " + buyStop.tLabel().toUpperCase() + ": " + buyStop.reason);
                d.add("ROUTE " + route1);
                d.add("ROUTE " + route2);
            } else {
                d.add("ROUTE " + route2);
            }
            d.add("SELL at " + to.name + " (" + to.systemName + ", " + to.factionName + ")");
            for (Line l : lines) d.add("  " + fmt(l.qty) + " " + l.name + ", " + (l.sellBlack ? "black" : "open") + " market, +" + fmt(l.value) + " cr");
            d.add("  transponder " + sellStop.tLabel().toUpperCase() + ": " + sellStop.reason);
            d.add("COSTS fuel " + fmt(fuel) + " (" + fmt(fuelCost) + " cr), supplies " + days(days) + " (" + fmt(supplyCost) + " cr)"
                    + (sellOnly ? "" : ", buy order " + fmt(buyCost) + " cr"));
            d.add("RESULT " + money() + (sellOnly ? " (cargo you already own)" : ""));
            for (String n : notes) d.add("NOTE " + n);
            return d;
        }
    }

    public static final class Result {
        public final List<Trip> trips = new ArrayList<Trip>();
        public final List<Trip> sellTrips = new ArrayList<Trip>();
        public float budget;
        public int pairsChecked, pairsPriced;
        public String why = "";
    }

    // ------------------------------------------------------------------ helpers

    public static String fmt(float v) { return String.format("%,d", Math.round(v)); }

    public static String days(float d) {
        if (d < 1.5f) return "1 day";
        return Math.round(d) + " days";
    }

    static boolean sameSystem(String a, String b) { return a != null && a.equals(b); }

    static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Light years of hyperspace between two places (0 inside one system). */
    static float ly(String sysA, float hxA, float hyA, String sysB, float hxB, float hyB) {
        if (sameSystem(sysA, sysB)) return 0f;
        return dist(hxA, hyA, hxB, hyB) / 2000f;
    }

    /** Units flown inside systems: straight across one system, or out to a jump point and in from one. */
    static float inSystem(String sysA, float xA, float yA, float jA, String sysB, float xB, float yB, float jB) {
        if (sameSystem(sysA, sysB)) return dist(xA, yA, xB, yB);
        return (sysA == null ? 0f : jA) + (sysB == null ? 0f : jB);
    }

    /** Travel days: burn level b moves b x 20 units per second, 10 seconds a day; 2,000 units are a light year. */
    static float travelDays(float ly, float units, float burn) {
        float perDay = Math.max(1f, burn) * 200f;
        return (ly * 2000f + units) / perDay;
    }

    static String routeText(String fromName, String fromSys, String fromJump, Node to, float ly) {
        if (sameSystem(fromSys, to.systemId)) return fromName + " -> " + to.name + " (same system)";
        StringBuilder b = new StringBuilder(fromName);
        if (fromSys != null && fromJump != null && fromJump.length() > 0) b.append(" -> ").append(fromJump);
        b.append(" -> ").append(String.format("%.1f", ly)).append(" ly hyperspace");
        if (to.systemId != null && to.jumpName != null && to.jumpName.length() > 0) b.append(" -> ").append(to.jumpName);
        b.append(" -> ").append(to.name).append(" (").append(to.systemName).append(")");
        return b.toString();
    }

    static boolean openOk(Node n) { return n.hasOpen && !n.inhospitable; }

    static float buyUnit(Node n, Q q, boolean black) {
        if (black) return n.hasBlack && q.blackStock >= 1f ? q.blackBuy : -1f;
        return openOk(n) && q.openStock >= 1f ? q.openBuy : -1f;
    }

    /** Which submarket to sell on: the black market unless the open market pays more (or the setting says open first). */
    static boolean sellBlack(Node n, Q q, boolean preferBlack) {
        float o = openOk(n) ? q.openSell : -1f;
        float b = n.hasBlack ? q.blackSell : -1f;
        if (b <= 0f) return false;
        if (o <= 0f) return true;
        return preferBlack ? b >= o : b > o;
    }

    static float sellUnit(Node n, Q q, boolean black) {
        return black ? (n.hasBlack ? q.blackSell : -1f) : (openOk(n) ? q.openSell : -1f);
    }

    // ------------------------------------------------------------------ planning

    public static Result plan(List<Node> nodes, Map<String, Good> goods, Fleet f, Prices p, Settings s) {
        Result r = new Result();
        goDarkMode = s.goDark == null ? "auto" : s.goDark;
        float budget = Math.max(0f, Math.min(f.credits * s.budgetPct / 100f, f.credits - s.reserve));
        r.budget = budget;
        Node here = null;
        for (Node n : nodes) if (n.id.equals(f.atNode)) here = n;

        // ---- buy trips
        if (budget <= 0f) r.why = "no budget: credits are at or under the reserve (" + fmt(s.reserve) + ")";
        else if (f.cargoFree < 1f) r.why = "the hold is full";
        else {
            final Map<Node[], Float> score = new HashMap<Node[], Float>();
            List<Node[]> pairs = new ArrayList<Node[]>();
            for (Node a : nodes) {
                for (Node b : nodes) {
                    if (a == b) continue;
                    r.pairsChecked++;
                    float bound = 0f;
                    for (Good g : goods.values()) {
                        if (!g.tradeable) continue;
                        Q qa = a.q.get(g.id), qb = b.q.get(g.id);
                        if (qa == null || qb == null) continue;
                        boolean contra = qa.illegal || qb.illegal;
                        if (contra && s.contraband < 2) continue;
                        boolean sb = sellBlack(b, qb, s.preferBlack);
                        float sell = sellUnit(b, qb, sb);
                        if (sell <= 0f) continue;
                        for (int k = 0; k < 2; k++) {
                            boolean bb = k == 1;
                            float buy = buyUnit(a, qa, bb);
                            if (buy <= 0f || sell <= buy) continue;
                            float stock = bb ? qa.blackStock : qa.openStock;
                            float units = Math.min(stock, Math.min(f.cargoFree / Math.max(0.01f, g.space), budget / buy));
                            bound = Math.max(bound, 0f) + (sell - buy) * units;
                        }
                    }
                    if (bound <= s.minProfit) continue;
                    float l1 = a.id.equals(f.atNode) ? 0f : ly(f.systemId, f.hx, f.hy, a.systemId, a.hx, a.hy);
                    float u1 = a.id.equals(f.atNode) ? 0f : inSystem(f.systemId, f.x, f.y, f.jumpDist, a.systemId, a.x, a.y, a.jumpDist);
                    float l2 = ly(a.systemId, a.hx, a.hy, b.systemId, b.hx, b.hy);
                    float u2 = inSystem(a.systemId, a.x, a.y, a.jumpDist, b.systemId, b.x, b.y, b.jumpDist);
                    float d = travelDays(l1, u1, f.burn) + travelDays(l2, u2, f.burn) + 1f;
                    float rough = bound - (l1 + l2) * f.fuelPerLY * 32f - d * f.suppliesPerDay * 130f;
                    if (rough <= s.minProfit) continue;
                    Node[] pr = new Node[]{a, b};
                    score.put(pr, rough / Math.max(0.5f, d));
                    pairs.add(pr);
                }
            }
            Collections.sort(pairs, new Comparator<Node[]>() {
                public int compare(Node[] x, Node[] y) { return Float.compare(score.get(y), score.get(x)); }
            });
            for (int i = 0; i < pairs.size() && i < s.maxPairs; i++) {
                r.pairsPriced++;
                Trip t = evaluate(pairs.get(i)[0], pairs.get(i)[1], goods, f, p, s, budget);
                // a trip must clear min_profit even with a remote sale counted at remote_trust (1.1.4)
                if (t != null && t.profit - Math.max(0f, t.sellValue - t.trustedSell) >= s.minProfit && t.days <= s.maxDays) r.trips.add(t);
            }
            Collections.sort(r.trips, new Comparator<Trip>() {
                public int compare(Trip x, Trip y) {
                    int c = Float.compare(y.score, x.score);
                    return c != 0 ? c : Float.compare(y.profit, x.profit);
                }
            });
            if (r.trips.isEmpty()) r.why = pairs.isEmpty() ? "no market pair has a price gap bigger than the travel costs"
                    : "price gaps shrink below " + fmt(s.minProfit) + " cr once quantity, fuel and supplies are counted";
        }

        // ---- selling what the fleet already carries
        if (!f.carried.isEmpty()) {
            for (Node b : nodes) {
                Trip t = sellTrip(b, goods, f, p, s);
                if (t != null && t.profit > 0f && t.days <= s.maxDays) r.sellTrips.add(t);
            }
            Collections.sort(r.sellTrips, new Comparator<Trip>() {
                public int compare(Trip x, Trip y) { return Float.compare(y.score, x.score); }
            });
        }
        return r;
    }

    /** One candidate line: a good bought on one submarket at A, sold on one at B. */
    private static final class Cand {
        Good g; Q qa, qb; boolean buyBlack, sellBlack, contra; float stock, unitBuy; boolean used;
    }

    /** profit(q) for a candidate, given how much of the same good is already being sold on the same submarket at B. */
    private static float profit(Prices p, Node a, Node b, Cand c, float q, float alreadySold) {
        if (q <= 0f) return 0f;
        float cost = p.buyTotal(a.id, c.g.id, q, c.buyBlack);
        float value = p.sellTotal(b.id, c.g.id, alreadySold + q, c.sellBlack)
                - (alreadySold > 0f ? p.sellTotal(b.id, c.g.id, alreadySold, c.sellBlack) : 0f);
        return value - cost;
    }

    /** The quantity (1..qMax) that earns the most: buy prices rise and sell prices fall as quantity grows. */
    static float bestQty(Prices p, Node a, Node b, Cand c, float qMax, float alreadySold) {
        qMax = (float) Math.floor(qMax);
        if (qMax < 1f) return 0f;
        if (profit(p, a, b, c, 1f, alreadySold) <= 0f) return 0f;
        float d = Math.max(1f, Math.round(qMax * 0.02f));
        if (profit(p, a, b, c, qMax, alreadySold) - profit(p, a, b, c, qMax - d, alreadySold) > 0f) return qMax;
        float lo = 1f, hi = qMax;
        for (int i = 0; i < 40 && hi - lo > 1f; i++) {
            float mid = (float) Math.floor((lo + hi) / 2f);
            float dm = Math.max(1f, Math.round(mid * 0.02f));
            if (profit(p, a, b, c, mid, alreadySold) - profit(p, a, b, c, mid - dm, alreadySold) > 0f) lo = mid; else hi = mid;
        }
        return lo;
    }

    static Trip evaluate(Node a, Node b, Map<String, Good> goods, Fleet f, Prices p, Settings s, float budget) {
        Trip t = new Trip();
        t.from = a; t.to = b;

        // travel first: refuelling at A comes out of the budget
        boolean atA = a.id.equals(f.atNode);
        t.ly1 = atA ? 0f : ly(f.systemId, f.hx, f.hy, a.systemId, a.hx, a.hy);
        float u1 = atA ? 0f : inSystem(f.systemId, f.x, f.y, f.jumpDist, a.systemId, a.x, a.y, a.jumpDist);
        t.ly2 = ly(a.systemId, a.hx, a.hy, b.systemId, b.hx, b.hy);
        float u2 = inSystem(a.systemId, a.x, a.y, a.jumpDist, b.systemId, b.x, b.y, b.jumpDist);
        t.days1 = atA ? 0f : travelDays(t.ly1, u1, f.burn) + 0.5f;
        t.days2 = travelDays(t.ly2, u2, f.burn) + 0.5f;
        t.days = t.days1 + t.days2;
        float fuel1 = t.ly1 * f.fuelPerLY, fuel2 = t.ly2 * f.fuelPerLY;
        // reach the buy market with a margin: 20% on the leg and two jump-point activations (1.1.5: a trip ran dry at a gravity well)
        if (fuel1 > 0f && fuel1 * 1.2f + 2f * f.fuelPerLY > f.fuel) return null;
        float left = f.fuel - fuel1;
        // keep fuel for the second leg plus fuel_reserve_ly, bought at A (it comes out of the budget)
        float fuelNeed = Math.min(f.fuelMax, fuel2 + Math.max(0f, s.fuelReserveLy) * f.fuelPerLY);
        t.fuelTarget = fuelNeed;
        if (left < fuelNeed) {
            if (a.fuelPrice > 0f) {
                t.refuel = true;
                t.refuelQty = (float) Math.ceil(Math.min(f.fuelMax - left, fuelNeed - left));
                t.refuelCost = t.refuelQty * a.fuelPrice;
            } else if (left < fuel2 * 1.1f) return null; // no fuel for sale at A and not enough aboard
        }
        t.fuel = fuel1 + fuel2;
        Good fuelG = goods.get("fuel"), supG = goods.get("supplies");
        float fuelUnit = a.fuelPrice > 0f ? a.fuelPrice : (fuelG != null ? fuelG.base * 1.3f : 32f);
        float supUnit = a.suppliesPrice > 0f ? a.suppliesPrice : (supG != null ? supG.base * 1.3f : 130f);
        t.fuelCost = t.fuel * fuelUnit;
        t.supplyCost = t.days * f.suppliesPerDay * supUnit;

        // supplies: keep supplies_days aboard after the trip, bought at A (budget and hold space)
        t.supTarget = (Math.max(0f, s.suppliesDays) + t.days) * f.suppliesPerDay;
        float supShort = (float) Math.ceil(Math.max(0f, t.supTarget - f.supplies));
        if (supShort >= 1f) {
            t.supQty = Math.min(supShort, Math.max(0f, f.cargoFree - 1f));
            t.supCost = t.supQty * supUnit;
            if (t.supQty >= 1f) t.refuel = true;
        }
        float credits = budget - t.refuelCost - t.supCost;
        float cargo = f.cargoFree - t.supQty;
        if (credits <= 0f || cargo < 1f) return null;
        final float credits0 = credits, cargo0 = cargo;

        List<Cand> cands = new ArrayList<Cand>();
        for (Good g : goods.values()) {
            if (!g.tradeable) continue;
            Q qa = a.q.get(g.id), qb = b.q.get(g.id);
            if (qa == null || qb == null) continue;
            boolean contra = qa.illegal || qb.illegal;
            if (contra && s.contraband < 2) continue;
            if (s.contraband == 0 && g.illegalSomewhere) continue; // never: not even goods legal here but seized by other patrols
            boolean sb = sellBlack(b, qb, s.preferBlack);
            if (sellUnit(b, qb, sb) <= 0f) continue;
            for (int k = 0; k < 2; k++) {
                boolean bb = k == 1;
                float unit = buyUnit(a, qa, bb);
                if (unit <= 0f || unit >= sellUnit(b, qb, sb)) continue;
                Cand c = new Cand();
                c.g = g; c.qa = qa; c.qb = qb; c.buyBlack = bb; c.sellBlack = sb; c.contra = contra;
                c.stock = bb ? qa.blackStock : qa.openStock; c.unitBuy = unit;
                cands.add(c);
            }
        }
        Map<String, Float> selling = new HashMap<String, Float>();
        while (t.lines.size() < Math.max(1, s.maxLines)) {
            Cand best = null;
            float bestScore = 0f, bestQ = 0f;
            for (Cand c : cands) {
                if (c.used) continue;
                String sk = c.g.id + (c.sellBlack ? "|b" : "|o");
                float sold = selling.containsKey(sk) ? selling.get(sk) : 0f;
                float qMax = Math.min(c.stock, Math.min(cargo / Math.max(0.01f, c.g.space), credits / c.unitBuy));
                float q = bestQty(p, a, b, c, qMax, sold);
                if (q < 1f) continue;
                // trim until affordable: prices rise with quantity
                float cost = p.buyTotal(a.id, c.g.id, q, c.buyBlack);
                for (int i = 0; i < 30 && cost > credits && q > 1f; i++) {
                    q = (float) Math.floor(q * 0.95f);
                    cost = p.buyTotal(a.id, c.g.id, q, c.buyBlack);
                }
                if (cost > credits) continue;
                float pr = profit(p, a, b, c, q, sold);
                if (pr <= 0f) continue;
                float sc = pr / (q * c.g.space / Math.max(1f, cargo0) + cost / Math.max(1f, credits0) + 1e-6f);
                if (sc > bestScore) { bestScore = sc; best = c; bestQ = q; }
            }
            if (best == null) break;
            best.used = true;
            String sk = best.g.id + (best.sellBlack ? "|b" : "|o");
            float sold = selling.containsKey(sk) ? selling.get(sk) : 0f;
            Line l = new Line();
            l.good = best.g.id; l.name = best.g.name; l.qty = bestQ; l.space = best.g.space;
            l.buyBlack = best.buyBlack; l.sellBlack = best.sellBlack; l.contraband = best.contra;
            l.cost = p.buyTotal(a.id, best.g.id, bestQ, best.buyBlack);
            l.value = p.sellTotal(b.id, best.g.id, sold + bestQ, best.sellBlack) - (sold > 0f ? p.sellTotal(b.id, best.g.id, sold, best.sellBlack) : 0f);
            selling.put(sk, sold + bestQ);
            t.lines.add(l);
            cargo -= bestQ * best.g.space;
            credits -= l.cost;
            if (cargo < 1f || credits < 1f) break;
        }
        if (t.lines.isEmpty()) return null;
        for (Line l : t.lines) { t.buyCost += l.cost; t.sellValue += l.value; if (l.contraband) t.contraband = true; }
        t.profit = t.sellValue - t.buyCost - t.fuelCost - t.supplyCost;
        t.perDay = t.profit / Math.max(0.5f, t.days);

        // transponder at each stop
        Set<String> illegalAtA = new HashSet<String>(), illegalAtB = new HashSet<String>();
        for (Map.Entry<String, Float> e : f.carried.entrySet()) {
            Q qa = a.q.get(e.getKey()), qb = b.q.get(e.getKey());
            if (qa != null && qa.illegal) illegalAtA.add(e.getKey());
            if (qb != null && qb.illegal) illegalAtB.add(e.getKey());
        }
        t.buyStop = new Stop();
        t.buyStop.node = a;
        t.sellStop = new Stop();
        t.sellStop.node = b;
        for (Line l : t.lines) {
            Good g = goods.get(l.good);
            float base = g == null ? 0f : g.base * l.qty;
            if (l.buyBlack) { t.buyStop.usesBlack = true; t.buyStop.blackImpact += base; } else { t.buyStop.needsOpen = true; t.buyStop.openImpact += base; }
            if (l.sellBlack) { t.sellStop.usesBlack = true; t.sellStop.blackImpact += base; } else { t.sellStop.needsOpen = true; t.sellStop.openImpact += base; }
            Q qa = a.q.get(l.good), qb = b.q.get(l.good);
            if (qa != null && qa.illegal && l.buyBlack) t.buyStop.buysContraband = true;
            if (qb != null && qb.illegal) illegalAtB.add(l.good);
        }
        if (t.refuel) {
            // refuelling needs the open market unless the black market has enough fuel
            Q fq = a.q.get("fuel");
            boolean blackFuel = fq != null && a.hasBlack && fq.blackBuy > 0f && fq.blackStock >= t.refuelQty;
            if (!blackFuel) t.buyStop.needsOpen = true;
        }
        t.buyStop.contrabandAboard = !illegalAtA.isEmpty();
        t.sellStop.contrabandAboard = !illegalAtB.isEmpty();
        decide(t.buyStop);
        decide(t.sellStop);

        String fromName = atA ? a.name : (f.atNode != null ? "here" : "your fleet");
        t.route1 = atA ? "already docked at " + a.name : routeText(fromName, f.systemId, f.jumpName, a, t.ly1)
                + ", about " + days(t.days1);
        t.route2 = routeText(a.name, a.systemId, a.jumpName, b, t.ly2) + ", about " + days(t.days2);
        if (t.refuelQty >= 1f) t.notes.add("tops up fuel at " + a.name + " (second leg + " + Math.round(s.fuelReserveLy) + " ly reserve)");
        if (t.supQty >= 1f) t.notes.add("restocks " + fmt(t.supQty) + " supplies at " + a.name + " (" + Math.round(s.suppliesDays) + " days aboard)");
        boolean remote = a.systemId == null || b.systemId == null || !a.systemId.equals(b.systemId);
        t.trustedSell = remote ? t.sellValue * Math.max(0f, Math.min(1f, s.remoteTrust)) : t.sellValue;
        if (remote && s.remoteTrust < 1f) t.notes.add("sells in another system: ranked at " + Math.round(s.remoteTrust * 100f)
                + "% of the quoted sale (remote quotes have come in at about half on arrival)");
        if (t.contraband) t.notes.add("carries contraband: a patrol scan would confiscate it");
        riskNotes(t, s);
        return t;
    }

    static Trip sellTrip(Node b, Map<String, Good> goods, Fleet f, Prices p, Settings s) {
        Trip t = new Trip();
        t.sellOnly = true;
        t.to = b;
        for (Map.Entry<String, Float> e : f.carried.entrySet()) {
            Good g = goods.get(e.getKey());
            Q qb = b.q.get(e.getKey());
            if (g == null || qb == null || g.fleetSupply || !g.tradeable) continue;
            if (qb.illegal && s.contraband < 1) continue;
            float qty = (float) Math.floor(e.getValue());
            if (qty < 1f) continue;
            boolean sb = sellBlack(b, qb, s.preferBlack);
            if (sellUnit(b, qb, sb) <= 0f) continue;
            Line l = new Line();
            l.good = g.id; l.name = g.name; l.qty = qty; l.sellBlack = sb; l.contraband = qb.illegal; l.space = g.space;
            l.value = p.sellTotal(b.id, g.id, qty, sb);
            if (l.value <= 0f) continue;
            t.lines.add(l);
        }
        if (t.lines.isEmpty()) return null;
        boolean atB = b.id.equals(f.atNode);
        t.ly2 = atB ? 0f : ly(f.systemId, f.hx, f.hy, b.systemId, b.hx, b.hy);
        float u = atB ? 0f : inSystem(f.systemId, f.x, f.y, f.jumpDist, b.systemId, b.x, b.y, b.jumpDist);
        t.days2 = atB ? 0f : travelDays(t.ly2, u, f.burn) + 0.5f;
        t.days = t.days2;
        t.fuel = t.ly2 * f.fuelPerLY;
        if (t.fuel > f.fuel) return null;
        Good fuelG = goods.get("fuel"), supG = goods.get("supplies");
        t.fuelCost = t.fuel * (fuelG != null ? fuelG.base * 1.3f : 32f);
        t.supplyCost = t.days * f.suppliesPerDay * (supG != null ? supG.base * 1.3f : 130f);
        for (Line l : t.lines) { t.sellValue += l.value; if (l.contraband) t.contraband = true; }
        t.profit = t.sellValue - t.fuelCost - t.supplyCost;
        t.perDay = t.profit / Math.max(0.5f, t.days);
        t.sellStop = new Stop();
        t.sellStop.node = b;
        for (Line l : t.lines) {
            Good g = goods.get(l.good);
            float base = g == null ? 0f : g.base * l.qty;
            if (l.sellBlack) { t.sellStop.usesBlack = true; t.sellStop.blackImpact += base; } else { t.sellStop.needsOpen = true; t.sellStop.openImpact += base; }
            if (l.contraband) t.sellStop.contrabandAboard = true;
        }
        decide(t.sellStop);
        riskNotes(t, s);
        t.route2 = atB ? "already docked at " + b.name : routeText(f.atNode != null ? "here" : "your fleet", f.systemId, f.jumpName, b, t.ly2) + ", about " + days(t.days2);
        if (t.contraband) t.notes.add("carries contraband: a patrol scan would confiscate it");
        return t;
    }

    /** Reputation risk of the trip: notes for the player and the ranking score. */
    public static void riskNotes(Trip t, Settings s) {
        t.repRisk = (t.buyStop == null ? 0f : t.buyStop.repRisk) + (t.sellStop == null ? 0f : t.sellStop.repRisk);
        float trustCut = t.trustedSell > 0f ? t.sellValue - t.trustedSell : 0f;
        t.score = (t.profit - trustCut - t.repRisk * s.repValue) / Math.max(0.5f, t.days);
        for (Stop st : new Stop[]{t.buyStop, t.sellStop}) {
            if (st == null || st.repRisk < 0.5f) continue;
            t.notes.add("reputation risk at " + st.node.name + ": about " + Math.round(Math.max(1f, st.repRisk)) + " point(s) with "
                    + st.node.factionName + " (counted as " + fmt(st.repRisk * s.repValue) + " cr when ranking)");
        }
    }

    // ------------------------------------------------------------------ transponder

    /** Suspicion the game would compute after this stop's trades with the transponder on. */
    static float suspicionAfter(Node n, float blackImpact, float openImpact) {
        float extra = Math.max(0f, n.suspicion - formula(n.smuggleTotal, n.tradeTotal, n.size)); // story effects on top
        return Math.min(1f, formula(n.smuggleTotal + blackImpact, n.tradeTotal + openImpact, n.size) + extra);
    }

    /** The game's suspicion formula: smuggled share x min(1, total / (10,000 x market size)). */
    static float formula(float smug, float trade, int size) {
        float total = smug + trade;
        if (total <= 0f) return 0f;
        return Math.min(1f, smug / total * Math.min(1f, total / (10000f * Math.max(1, size))));
    }

    private static String pct(float v) { return Math.round(v * 100f) + "%"; }

    /**
     * The transponder decision for one stop (game rules, 0.98a):
     * - the open market needs the transponder on (except at free ports);
     * - trades made with the transponder off add nothing to suspicion or reputation;
     * - with it on, black-market trade raises suspicion; from 5% that market's patrols may scan you;
     * - free ports, and factions that allow it (pirates, independents...), don't care about the transponder;
     * - with it off, a patrol that sees you costs reputation, scans you, and you can't dock while it tracks you.
     */
    public static void decide(Stop st) {
        decideFree(st);
        if (st.on) return;
        String m = goDarkMode == null ? "auto" : goDarkMode;
        Node n = st.node;
        boolean relaxed = n.freePort || n.tolerant;
        if (m.startsWith("auto")) return;
        if (m.startsWith("indep") && relaxed && n.strictNearby == 0) return;
        float smugRep = st.usesBlack ? st.blackImpact / 100000f : 0f;
        if (n.inhospitable) { setOn(st, 99f, "go_dark = " + m + ": this market only trades with a dark fleet - avoid"); return; }
        setOn(st, expectOn(st, smugRep), "go_dark = " + m + ": transponder stays on"
                + (n.strictNearby > 0 ? " (" + n.strictNearby + " strict-faction market(s) in the system)" : "")
                + (st.usesBlack && !relaxed ? "; black-market part takes suspicion to " + pct(st.suspicionIfOn) : ""));
    }

    /** The transponder decision with no go_dark rule applied. */
    static void decideFree(Stop st) {
        Node n = st.node;
        st.suspicionIfOn = suspicionAfter(n, st.blackImpact, st.openImpact);
        st.risk = 0; st.goDark = false; st.repRisk = 0f;
        float smugRep = st.usesBlack ? st.blackImpact / 100000f : 0f;
        boolean relaxed = n.freePort || n.tolerant;
        float eOn = expectOn(st, smugRep), eOff = expectOff(st);
        if (st.needsOpen) {
            if (relaxed && st.usesBlack) {
                // the port lets you dock dark with full trading (rules.csv marketPostOpenToOffIsFine)
                setOff(st, eOff, (n.freePort ? "free port" : n.factionName + " don't mind it") + ": open market works dark too, and dark trade leaves no record");
                return;
            }
            setOn(st, eOn, "the open market needs it on" + (st.usesBlack && !relaxed
                    ? "; black-market part takes suspicion to " + pct(st.suspicionIfOn) : ""));
            return;
        }
        if (relaxed) {
            setOff(st, eOff, (n.freePort ? "free port" : n.factionName + " don't mind it") + ": dark trade leaves no suspicion");
            return;
        }
        if (n.inhospitable) {
            setOff(st, eOff, n.factionName + " won't trade with you openly: dock dark");
            return;
        }
        // strict faction, black market only: pick whichever risks less reputation; ties keep it on
        if (eOn <= eOff + 0.25f) {
            setOn(st, eOn, (eOn < 0.5f ? "low risk on: suspicion " + pct(n.suspicion) + " now, " + pct(st.suspicionIfOn) + " after"
                    : "on risks less: patrol scans at " + pct(Math.max(n.suspicion, st.suspicionIfOn)) + " suspicion")
                    + " (dark: about " + one(eOff) + " rep at risk with " + n.patrols + " patrol(s))");
        } else {
            setOff(st, eOff, (n.patrols == 0 ? "no patrols in system: dark trade adds no suspicion"
                    : "dark risks less than on (" + one(eOn) + " rep)") + "; on would mean " + pct(st.suspicionIfOn) + " suspicion");
        }
    }

    private static String one(float v) { return String.format("%.1f", v); }

    private static void setOn(Stop st, float e, String why) {
        st.on = true; st.goDark = false; st.repRisk = e;
        st.risk = e >= 2f ? 2 : e >= 0.5f ? 1 : 0;
        st.reason = why + (e >= 0.5f ? " - about " + one(e) + " rep at risk" : "");
    }

    private static void setOff(Stop st, float e, String why) {
        st.on = false; st.goDark = st.node.patrols > 0; st.repRisk = e;
        st.risk = e >= 2f ? 2 : e >= 0.5f || st.goDark ? 1 : 0;
        st.reason = why + (st.goDark ? "; going dark past " + st.node.patrols + " patrol(s)" : "")
                + (e >= 0.5f ? " - about " + one(e) + " rep at risk" : "");
    }

    /** Chance a cargo scan flags the fleet even without contraband (CargoScan: cargo-based from 5% suspicion, level-based from 50%). */
    static float flagChance(float level) {
        if (level < 0.05f) return 0f;
        float p = 0.3f;
        if (level >= 0.5f) p = Math.max(p, (float) Math.sqrt(level));
        return Math.min(1f, p);
    }

    /**
     * Expected reputation lost with the transponder on. Patrols from a strict market scan you with chance
     * 5 x suspicion (from 5%), on arrival or as you leave after the trade raised it; a flagged scan costs 5 points
     * (twice that with the patrol's commander), contraband found up to 5 and the goods. Smuggling itself costs
     * about 1 point per 100,000 credits traded.
     */
    static float expectOn(Stop st, float smugRep) {
        Node n = st.node;
        if (n.freePort || n.tolerant) return 0f;
        if (n.patrols == 0) return smugRep;
        float pArr = n.suspicion >= 0.05f ? Math.min(1f, 5f * n.suspicion) : 0f;
        float after = st.usesBlack ? st.suspicionIfOn : n.suspicion;
        float pDep = after >= 0.05f ? Math.min(1f, 5f * after) : 0f;
        float e = pArr * (flagChance(n.suspicion) * 5f + (st.contrabandAboard || st.buysContraband ? 3f : 0f))
                + (1f - pArr) * pDep * (flagChance(after) * 5f + (st.buysContraband ? 3f : 0f));
        return e + smugRep;
    }

    /** Expected reputation lost with the transponder off: a patrol that spots you costs 2 points and forces a scan. */
    static float expectOff(Stop st) {
        Node n = st.node;
        if (n.patrols == 0) return 0f;
        // patrols orbit the markets you dock at, so being seen is likely; going dark only helps a little (seen in play)
        float pSpot = Math.min(0.9f, 0.6f + 0.1f * n.patrols) * 0.8f;
        float scanCost = flagChance(n.suspicion) * 5f + (st.contrabandAboard || st.buysContraband ? 3f : 0f);
        if (n.freePort || n.tolerant) return pSpot * 2f * 0.5f; // only other factions' patrols care
        return pSpot * (2f + scanCost);
    }

}
