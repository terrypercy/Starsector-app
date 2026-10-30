package claudecaptain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a new inbox batch is split up when it arrives, without game classes (unit-tested).
 *
 * GOV lines (run / pause / control / unpause) and NOW lines (cancel / resume) act the moment the batch
 * arrives. If the batch starts a goal, an unfinished goal (running or paused) is replaced. A batch with
 * no goal lines while a goal is unfinished runs its instant lines at once, so 'status', 'events since',
 * 'hunt stop' and the like work any time instead of waiting behind the goal (1.0.1 bug: a paused goal
 * blocked every later batch, even 'cancel').
 */
public final class BatchMath {
    private BatchMath() {}

    public static final String GOV = "gov", NOW = "now", GOAL = "goal", INSTANT = "instant";

    /** The kind of one batch line. dockedNow: the fleet is docked at a market (a bare 'dock' is then instant). */
    public static String kind(String line, boolean dockedNow) {
        String l = line == null ? "" : line.trim();
        String low = l.toLowerCase(Locale.ROOT);
        if (low.startsWith("optional ")) { l = l.substring(9).trim(); low = l.toLowerCase(Locale.ROOT); }
        List<String> t = CommandRunner.tokenize(low);
        if (t.isEmpty()) return INSTANT;
        String c = t.get(0);
        int n = t.size();
        if (c.equals("run")) return n > 2 && t.get(1).equals("trip") ? GOAL : GOV;
        if (c.equals("pause") || c.equals("unpause") || c.equals("control") || c.equals("hands")) return GOV;
        if (c.equals("cancel") || c.equals("resume")) return NOW;
        if (c.equals("goto") || c.equals("travel") || c.equals("approach") || c.equals("wait") || c.equals("resupply")
                || c.equals("hire") || c.equals("go") || c.equals("trip") || c.equals("runtrip") || c.equals("runsell") || c.equals("ask")) return GOAL;
        if (c.equals("dock")) return n > 1 || !dockedNow ? GOAL : INSTANT;
        if (c.equals("repair")) return n == 1 || t.get(1).equals("dockyard") ? GOAL : INSTANT;
        if (c.equals("commission")) return n > 1 ? GOAL : INSTANT;
        if (c.equals("explore")) return n == 1 || t.get(1).equals("here") ? GOAL : INSTANT;
        if (c.equals("hunt")) {
            if (n == 1) return INSTANT;
            String a = t.get(1);
            return a.equals("status") || a.equals("stop") || a.equals("resume") ? INSTANT : GOAL;
        }
        if (c.equals("loop")) return n > 1 && isNum(t.get(1)) ? GOAL : INSTANT;
        if (c.equals("trade")) return n > 2 && t.get(1).equals("loop") && isNum(t.get(2)) ? GOAL : INSTANT;
        return INSTANT;
    }

    /** Split a batch: [0] lines to run at arrival (in order), [1] lines to queue; [2] non-empty = replace the unfinished goal. */
    public static List<List<String>> split(List<String> lines, boolean goalUnfinished, boolean dockedNow) {
        List<String> now = new ArrayList<String>(), queue = new ArrayList<String>(), replace = new ArrayList<String>();
        boolean startsGoal = false;
        for (String l : lines) if (GOAL.equals(kind(l, dockedNow))) { startsGoal = true; break; }
        for (String l : lines) {
            String k = kind(l, dockedNow);
            if (GOV.equals(k) || NOW.equals(k)) now.add(l);
            else if (!startsGoal && goalUnfinished) now.add(l);
            else queue.add(l);
        }
        if (startsGoal && goalUnfinished) replace.add("replace");
        List<List<String>> out = new ArrayList<List<String>>();
        out.add(now); out.add(queue); out.add(replace);
        return out;
    }

    static boolean isNum(String s) {
        try { Float.parseFloat(s); return true; } catch (NumberFormatException e) { return false; }
    }
}
