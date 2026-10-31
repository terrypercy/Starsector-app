package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.impl.campaign.intel.BaseMissionIntel;
import com.fs.starfarer.api.impl.campaign.intel.FactionCommissionIntel;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1.0.7: posted missions (the Intel screen's Missions tab: survey, analyze, procurement), listed and accepted the way
 * the screen's Accept button does it (BaseMissionIntel.buttonPressConfirmed(BUTTON_ACCEPT, ui)).
 *
 * Inbox commands: missions | accept <n|name>. The reward is in a protected field (no reflection allowed), so the
 * listing shows the faction and the target; the Intel screen shows the pay.
 */
public final class Missions {
    private Missions() {}

    private static final List<BaseMissionIntel> last = new ArrayList<BaseMissionIntel>();

    static List<BaseMissionIntel> all() {
        List<BaseMissionIntel> out = new ArrayList<BaseMissionIntel>();
        for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel()) {
            if (!(i instanceof BaseMissionIntel) || i instanceof FactionCommissionIntel) continue;
            BaseMissionIntel b = (BaseMissionIntel) i;
            try {
                if (b.isEnded() || b.isEnding() || b.isHidden()) continue;
                if (!b.isPosted() && !b.isAccepted()) continue;
            } catch (Throwable t) { continue; }
            out.add(b);
        }
        return out;
    }

    static String line(BaseMissionIntel b) {
        StringBuilder s = new StringBuilder();
        String title = null;
        try { title = b.getSmallDescriptionTitle(); } catch (Throwable ignored) {}
        s.append(safe(title == null ? b.getClass().getSimpleName() : title));
        try {
            if (b.getFactionForUIColors() != null) s.append(" | ").append(b.getFactionForUIColors().getDisplayName());
        } catch (Throwable ignored) {}
        SectorEntityToken where = null;
        try { where = b.getMapLocation(null); } catch (Throwable ignored) {}
        if (where != null) {
            s.append(" | target ").append(where.getName());
            if (where.getContainingLocation() != null) s.append(" (").append(where.getContainingLocation().getName()).append(")");
            s.append(" ").append(String.format(Locale.ROOT, "%.1f", Misc.getDistanceToPlayerLY(where))).append(" ly");
        }
        try {
            Float d = b.getDuration();
            if (d != null) s.append(" | ").append(Math.round(d - b.getElapsedDays())).append(" days");
        } catch (Throwable ignored) {}
        s.append(" | ").append(b.isAccepted() ? "ACCEPTED" : "posted");
        return s.toString();
    }

    private static String safe(String s) { return s == null ? "?" : s.replace('\n', ' ').trim(); }

    static String list() {
        last.clear();
        last.addAll(all());
        StringBuilder b = new StringBuilder("missions (" + last.size() + ")\n");
        for (int i = 0; i < last.size(); i++) b.append(i + 1).append(". ").append(line(last.get(i))).append("\n");
        if (last.isEmpty()) b.append("(none posted that we know of; visit markets to see their missions)\n");
        return b.toString().trim();
    }

    static String accept(String q) {
        String a = q == null ? "" : q.trim();
        if (a.isEmpty()) return "usage: accept <n|name> (see 'missions')";
        if (last.isEmpty()) last.addAll(all());
        BaseMissionIntel pick = null;
        try {
            int n = Integer.parseInt(a);
            if (n < 1 || n > last.size()) return "no mission " + n + " (run 'missions')";
            pick = last.get(n - 1);
        } catch (NumberFormatException e) {
            int best = 0;
            for (BaseMissionIntel b : all()) {
                String text = line(b);
                int s = Lookup.score(a, "", text);
                if (s > best) { best = s; pick = b; }
            }
            if (best < 30) pick = null;
        }
        if (pick == null) return "no mission matching '" + a + "' (run 'missions')";
        if (pick.isAccepted()) return "already accepted: " + line(pick);
        if (!pick.isPosted()) return "can't accept (" + pick.getMissionState() + "): " + line(pick);
        pick.buttonPressConfirmed(BaseMissionIntel.BUTTON_ACCEPT, stubUi());
        if (!pick.isAccepted()) return "the game didn't accept it: " + line(pick);
        EventLog.info("mission", "accepted: " + line(pick));
        return "accepted: " + line(pick);
    }

    /** The intel screen isn't open: an IntelUIAPI whose calls do nothing (the accept path only refreshes the UI). */
    static IntelUIAPI stubUi() { return new NoIntelUI(); }

    static final class NoIntelUI implements IntelUIAPI {
        public void updateUIForItem(IntelInfoPlugin plugin) {}
        public void recreateIntelUI() {}
        public void showDialog(SectorEntityToken target, String trigger) {}
        public void showDialog(SectorEntityToken target, com.fs.starfarer.api.campaign.InteractionDialogPlugin plugin) {}
        public void showEditIntelMarkerDialog(com.fs.starfarer.api.impl.campaign.intel.misc.MapMarkerIntel intel) {}
        public void updateIntelList() {}
        public void updateIntelList(boolean retainCurrentSelection) {}
        public void updateIntelList(boolean retainCurrentSelection, List<IntelInfoPlugin> show) {}
        public void selectItem(IntelInfoPlugin plugin) {}
        public boolean isShowingCustomIntelSubset() { return false; }
        public void showOnMap(SectorEntityToken token) {}
        public void saveSmallDescScrollState() {}
        public void restoreSmallDescScrollState() {}
    }
}
