package claudecaptain;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

/**
 * The game side of the event log: claude_captain/events.txt, rewritten (the game can only write whole
 * files) at most once a second when something new was logged, and right away for alerts.
 * Replaces both mods' outbox files: every command result, goal step, pause and alarm lands here.
 */
public final class EventLog {

    private static final Logger log = Global.getLogger(EventLog.class);
    public static final EventCore core = new EventCore(2000);
    private static volatile boolean loaded = false, dirty = false;
    private static long lastFlush = 0L;
    /** Batch id the current command came from; tagged on events. */
    public static volatile String batch = "";
    /** Last WARN/ALERT sequence number (governor 'run until event' watches it). */
    public static volatile long lastNotable = 0L;
    public static volatile String lastNotableText = "";

    private EventLog() {}

    static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (Global.getSettings().fileExistsInCommon(Files.EVENTS)) core.load(Global.getSettings().readTextFileFromCommon(Files.EVENTS));
        } catch (Throwable t) { log.warn("Claude Captain: could not read events.txt", t); }
    }

    static String date() {
        try { return Global.getSector().getClock().getDateString(); } catch (Throwable t) { return "title"; }
    }

    public static long add(String level, String kind, String text) {
        ensureLoaded();
        try { core.setKeep((int) Rules.f("events_keep_lines", 2000f)); } catch (Throwable ignored) {}
        long s = core.add(date(), level, kind, batch, text);
        if (EventCore.WARN.equals(level) || EventCore.ALERT.equals(level)) { lastNotable = s; lastNotableText = firstLine(text); }
        dirty = true;
        log.info("Claude Captain: event #" + s + " " + level + " " + kind + ": " + firstLine(text));
        if (EventCore.ALERT.equals(level)) flush(true);
        return s;
    }

    static String firstLine(String t) {
        if (t == null) return "";
        int i = t.indexOf('\n');
        return i < 0 ? t : t.substring(0, i);
    }

    public static long info(String kind, String text) { return add(EventCore.INFO, kind, text); }
    public static long warn(String kind, String text) { return add(EventCore.WARN, kind, text); }
    public static long alert(String kind, String text) { return add(EventCore.ALERT, kind, text); }
    public static long goal(String text) { return add(EventCore.GOAL, "goal", text); }

    /** Trade Runner's old outbox reports (trip and loop reports) become events. */
    public static void reply(String body) { info("trade", body); }

    public static void flush(boolean force) {
        if (!dirty) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastFlush < 1000L) return;
        lastFlush = now;
        dirty = false;
        try { Global.getSettings().writeTextFileToCommon(Files.EVENTS, core.text()); }
        catch (Throwable t) { log.warn("Claude Captain: could not write events.txt", t); }
    }

    /** "events since n": the newer events go to recent.txt (small file to fetch). */
    public static String since(long n) {
        ensureLoaded();
        String t = core.since(n);
        int count = 0;
        for (String l : t.split("\n")) if (EventCore.seqOf(l) >= 0) count++;
        String head = "#since " + n + " last " + core.lastSeq() + " count " + count
                + (n + 1 < core.firstSeq() ? " (events " + (n + 1) + "-" + (core.firstSeq() - 1) + " were trimmed)" : "") + "\n";
        try { Global.getSettings().writeTextFileToCommon(Files.RECENT, head + t); } catch (Throwable ignored) {}
        return count + " events after #" + n + " written to recent.txt (last #" + core.lastSeq() + ")";
    }
}
