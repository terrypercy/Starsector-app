package claudecaptain;

import com.fs.starfarer.api.Global;

/** The latest sector read and trip plan, shared by the panel, the runner and the inbox. */
public final class Plans {

    private Plans() {}

    public static volatile GameReader.Snapshot snap;
    public static volatile Planner.Result latest;
    public static volatile long at = 0L;
    public static volatile long readMs, planMs;
    public static volatile String error;
    private static long lastWrite = 0L;
    private static String lastWritten = "";

    /** Reads the sector and plans again. Takes a few tens of milliseconds; called on demand and every ~30 s. */
    public static synchronized Planner.Result refresh() {
        try {
            long t0 = System.currentTimeMillis();
            GameReader.Snapshot sn = GameReader.read();
            long t1 = System.currentTimeMillis();
            Planner.Result r = Planner.plan(sn.nodes, sn.goods, sn.fleet, sn.prices, Rules.settings());
            long t2 = System.currentTimeMillis();
            snap = sn;
            latest = r;
            readMs = t1 - t0;
            planMs = t2 - t1;
            at = System.currentTimeMillis();
            error = null;
            write(false);
            return r;
        } catch (Throwable t) {
            error = String.valueOf(t);
            Global.getLogger(Plans.class).error("Trade Runner: planning failed", t);
            return latest;
        }
    }

    /** True if no plan yet or it is older than maxAgeMs. */
    public static boolean stale(long maxAgeMs) { return latest == null || System.currentTimeMillis() - at > maxAgeMs; }

    /** 1.2.7: a "go there and dock" trip to the market best matching the name. */
    public static Planner.Trip goTrip(String name, boolean refill) {
        refresh();
        if (snap == null) return null;
        Planner.Node best = null;
        String q = name.trim().toLowerCase(java.util.Locale.ROOT);
        for (Planner.Node n : snap.nodes) {
            String nm = n.name == null ? "" : n.name.toLowerCase(java.util.Locale.ROOT);
            if (nm.equals(q)) { best = n; break; }
            if (best == null && nm.startsWith(q)) best = n;
        }
        if (best == null) for (Planner.Node n : snap.nodes) if (n.name != null && n.name.toLowerCase(java.util.Locale.ROOT).contains(q)) { best = n; break; }
        if (best == null) return null;
        Planner.Trip t = new Planner.Trip();
        t.goOnly = true;
        t.sellOnly = true;
        t.refuel = refill;
        t.to = best;
        t.sellStop = new Planner.Stop();
        t.sellStop.node = best;
        Planner.decide(t.sellStop);
        return t;
    }

    /** 1.1.11: the list shown by the last 'plan' command; 'trip <n>' numbers refer to it for 30 minutes. */
    public static volatile Planner.Result shown;
    public static volatile long shownAt = 0L;

    /** Pins the current plan as the one the player saw. */
    public static void pinShown() { shown = latest; shownAt = System.currentTimeMillis(); }

    /** The plan 'trip <n>' should use: the pinned one while fresh (Nov 2 live: a re-plan renumbered the trips). */
    public static boolean shownFresh() { return shown != null && System.currentTimeMillis() - shownAt < 30L * 60L * 1000L; }

    public static Planner.Trip trip(int n, boolean sell) {
        Planner.Result r = shownFresh() ? shown : latest;
        if (r == null) return null;
        java.util.List<Planner.Trip> l = sell ? r.sellTrips : r.trips;
        return n >= 1 && n <= l.size() ? l.get(n - 1) : null;
    }

    public static String text(int maxTrips) {
        Planner.Result r = latest;
        GameReader.Snapshot sn = snap;
        StringBuilder b = new StringBuilder("# Claude Trade Runner plans (" + Files.VERSION + ")\n");
        try { b.append("#date ").append(Global.getSector().getClock().getDateString()).append("\n"); } catch (Throwable ignored) {}
        if (r == null || sn == null) return b.append(error == null ? "no plan yet\n" : "error: " + error + "\n").toString();
        Planner.Fleet f = sn.fleet;
        b.append("fleet: ").append(Planner.fmt(f.credits)).append(" cr, budget ").append(Planner.fmt(r.budget))
         .append(", hold free ").append(Planner.fmt(f.cargoFree)).append(", fuel ").append(Planner.fmt(f.fuel)).append("/").append(Planner.fmt(f.fuelMax))
         .append(", ").append(String.format("%.1f", f.fuelPerLY)).append(" fuel/ly, ").append(String.format("%.1f", f.suppliesPerDay)).append(" supplies/day, burn ")
         .append(Math.round(f.burn)).append(", in ").append(f.systemName).append(f.atNode != null ? ", docked at " + f.atNode : "").append("\n");
        b.append("read ").append(sn.nodes.size()).append(" markets in ").append(readMs).append(" ms; planned in ").append(planMs)
         .append(" ms (").append(r.pairsChecked).append(" pairs checked, ").append(r.pairsPriced).append(" priced, ")
         .append(sn.prices.calls).append(" price calls)\n");
        b.append("rules: ").append(Rules.dump()).append("\n");
        if (r.trips.isEmpty()) b.append("\nNO TRIPS: ").append(r.why).append("\n");
        for (int i = 0; i < r.trips.size() && i < maxTrips; i++) {
            Planner.Trip t = r.trips.get(i);
            b.append("\n").append(i + 1).append(". ").append(t.title()).append("\n");
            for (String d : t.details()) b.append("   ").append(d).append("\n");
        }
        if (!r.sellTrips.isEmpty()) b.append("\nSELL WHAT YOU CARRY\n");
        for (int i = 0; i < r.sellTrips.size() && i < 3; i++) {
            Planner.Trip t = r.sellTrips.get(i);
            b.append("\nS").append(i + 1).append(". ").append(t.title()).append("\n");
            for (String d : t.details()) b.append("   ").append(d).append("\n");
        }
        return b.toString();
    }

    /** Writes plans.txt for Claude to read: when the plan changed, at most every 20 s (or now if forced). */
    public static void write(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastWrite < 20000L) return;
        try {
            String t = text(10);
            String body = t.substring(t.indexOf('\n', t.indexOf("#date") + 1) + 1);
            if (!force && body.equals(lastWritten)) return;
            lastWritten = body;
            lastWrite = now;
            Global.getSettings().writeTextFileToCommon(Files.PLANS, t);
        } catch (Throwable ignored) {}
    }
}
