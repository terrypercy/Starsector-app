package claudecaptain;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 1.0.9 (Nov 2): approvals the player gave that are still good. An approved line that failed only because the fleet
 * was not docked is kept for a while, so it runs after 'dock' without a second popup. Every such line is kept, not
 * just the last one (live bug: sell + buyship + two restores approved in a row, only the last restore survived).
 * Pure logic, unit-tested; times are passed in.
 */
public final class ApprovalBook {

    private final Map<String, Long> until = new HashMap<String, Long>();

    /** Keeps line approved until the given time. */
    public void keep(String line, long untilMs) { if (line != null) until.put(line, untilMs); }

    /** True (and the approval is used up) when line was kept and has not expired. */
    public boolean use(String line, long nowMs) {
        purge(nowMs);
        return line != null && until.remove(line) != null;
    }

    /** True when line is kept and not expired (does not use it up). */
    public boolean has(String line, long nowMs) { purge(nowMs); return line != null && until.containsKey(line); }

    public void clear() { until.clear(); }

    public int size() { return until.size(); }

    private void purge(long nowMs) {
        for (Iterator<Map.Entry<String, Long>> it = until.entrySet().iterator(); it.hasNext(); ) if (it.next().getValue() < nowMs) it.remove();
    }
}
