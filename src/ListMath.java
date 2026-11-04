package claudecaptain;

import java.util.List;

/**
 * 1.1.25 (Mar 23 c206 live): pure helpers for numbered lists the player answers by number ("buyship 10").
 *
 * The 'ships' list used to drop a ship once it was bought, so every later number shifted down one: after
 * "buyship 9", "buyship 10" bought list entry 11 (a Lacerta instead of the Backscatter tanker).
 * Bought entries now keep their slot and are marked gone.
 */
public final class ListMath {

    /** Marker left in a slot whose item is gone (bought, sold). */
    public static final String GONE = "";

    private ListMath() {}

    /** Marks the item gone without moving any other entry's number. */
    public static void markGone(List<String> list, String id) {
        if (list == null || id == null) return;
        int i = list.indexOf(id);
        if (i >= 0) list.set(i, GONE);
    }

    /**
     * The id for 1-based number k, or null with err[0] set: out of range, or already gone.
     * @param err a 1-element array for the message
     */
    public static String pick(List<String> list, int k, String[] err) {
        if (list == null || k < 1 || k > list.size()) {
            if (err != null) err[0] = "no ship " + k + " in the last 'ships' list (run 'ships' first)";
            return null;
        }
        String id = list.get(k - 1);
        if (id == null || GONE.equals(id)) {
            if (err != null) err[0] = "ship " + k + " in the last 'ships' list is already gone (bought or sold); run 'ships' again";
            return null;
        }
        return id;
    }
}
