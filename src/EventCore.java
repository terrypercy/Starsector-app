package claudecaptain;

import java.util.ArrayList;
import java.util.List;

/**
 * The append-only event log, without any game classes (unit-tested).
 *
 * One event = a head line plus optional continuation lines:
 *   #1234 206.08.23 WARN gov b=batch7: supplies for 14 days (maintenance)
 *     | more text
 * Sequence numbers only grow, also across game restarts (the last number is read back from the file).
 * Old events are dropped from the front, whole events at a time, to keep about keepLines lines.
 */
public final class EventCore {

    public static final String INFO = "INFO", GOAL = "GOAL", WARN = "WARN", ALERT = "ALERT", CMD = "CMD";

    private final List<String> lines = new ArrayList<String>();
    private long seq = 0L;
    private int keep;

    public EventCore(int keepLines) { keep = Math.max(50, keepLines); }

    public synchronized void setKeep(int keepLines) { keep = Math.max(50, keepLines); }

    public synchronized long lastSeq() { return seq; }

    public synchronized int lineCount() { return lines.size(); }

    /** Seq of a head line ("#123 ..."), or -1. */
    public static long seqOf(String line) {
        if (line == null || line.length() < 2 || line.charAt(0) != '#') return -1L;
        int sp = line.indexOf(' ');
        if (sp < 2) return -1L;
        try { return Long.parseLong(line.substring(1, sp)); } catch (NumberFormatException e) { return -1L; }
    }

    /** Takes over an existing events file: its lines are kept, numbering continues after its last event. */
    public synchronized void load(String text) {
        lines.clear();
        seq = 0L;
        if (text == null) return;
        for (String l : text.replace("\r", "").split("\n")) {
            if (l.length() == 0) continue;
            long s = seqOf(l);
            if (s < 0 && lines.isEmpty()) continue; // a partial event at the top
            if (s > seq) seq = s;
            lines.add(l);
        }
        trim();
    }

    /** Appends an event and returns its sequence number. */
    public synchronized long add(String date, String level, String kind, String batch, String text) {
        seq++;
        String[] parts = (text == null ? "" : text).replace("\r", "").split("\n");
        StringBuilder head = new StringBuilder();
        head.append('#').append(seq).append(' ').append(date == null || date.length() == 0 ? "-" : date.replace(' ', '_'))
            .append(' ').append(level).append(' ').append(kind == null || kind.length() == 0 ? "-" : kind);
        if (batch != null && batch.length() > 0) head.append(" b=").append(batch.replace(' ', '_'));
        head.append(": ").append(parts.length > 0 ? parts[0] : "");
        lines.add(head.toString());
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].trim().length() == 0) continue;
            lines.add("  | " + parts[i]);
        }
        trim();
        return seq;
    }

    private void trim() {
        if (lines.size() <= keep) return;
        int cut = lines.size() - keep;
        // move the cut forward to the next head line so no event is split
        while (cut < lines.size() && seqOf(lines.get(cut)) < 0) cut++;
        if (cut >= lines.size()) cut = lines.size() - 1;
        lines.subList(0, cut).clear();
    }

    /** All events with a sequence number above n, oldest first. */
    public synchronized String since(long n) {
        StringBuilder b = new StringBuilder();
        boolean on = false;
        for (String l : lines) {
            long s = seqOf(l);
            if (s >= 0) on = s > n;
            if (on) b.append(l).append('\n');
        }
        return b.toString();
    }

    /** The last k head lines (for the panel). */
    public synchronized List<String> lastHeads(int k) {
        List<String> out = new ArrayList<String>();
        for (int i = lines.size() - 1; i >= 0 && out.size() < k; i--) if (seqOf(lines.get(i)) >= 0) out.add(0, lines.get(i));
        return out;
    }

    public synchronized String text() {
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        return b.toString();
    }

    /** The oldest sequence number still in the log (0 if empty). */
    public synchronized long firstSeq() {
        for (String l : lines) { long s = seqOf(l); if (s >= 0) return s; }
        return 0L;
    }
}
