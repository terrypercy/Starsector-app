package claudecaptain;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.Misc;

import java.util.Set;

/**
 * 1.0.9: 'progress' - story, character and colony status straight from the game's own records (no guessed memory
 * flags): story missions are the intel the game tags "Story"; colonies are Misc.getPlayerMarkets(true).
 * A light watcher notes (once, INFO) when unspent skill points or a new story mission appear.
 */
public final class Progress implements EveryFrameScript {

    private float acc = 0f;
    private String lastSig = "";

    public static void install() { Global.getSector().addTransientScript(new Progress()); }

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return false; }

    public void advance(float amount) {
        acc += amount;
        if (acc < 5f) return;
        acc = 0f;
        try {
            String sig = "sp=" + character().getPoints() + "|story=" + storyTitles();
            if (!sig.equals(lastSig)) {
                if (!lastSig.isEmpty()) EventLog.info("progress", "changed: " + summaryLine());
                lastSig = sig;
            }
        } catch (Throwable ignored) {}
    }

    static MutableCharacterStatsAPI character() { return Global.getSector().getPlayerPerson().getStats(); }

    /** Titles of the story missions the game has running (tag "Story", not ended). */
    static String storyTitles() {
        StringBuilder b = new StringBuilder();
        for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel()) {
            try {
                if (i.isEnded() || i.isEnding()) continue;
                Set<String> tags = i.getIntelTags(null);
                if (tags == null || !tags.contains(Tags.INTEL_STORY)) continue;
                b.append(b.length() == 0 ? "" : "; ").append(i.getSmallDescriptionTitle());
            } catch (Throwable ignored) {}
        }
        return b.toString();
    }

    static String summaryLine() {
        MutableCharacterStatsAPI s = character();
        String story = storyTitles();
        return "level " + s.getLevel() + ", unspent skill points " + s.getPoints() + ", story points " + s.getStoryPoints()
                + " | story: " + (story.isEmpty() ? "none active" : story);
    }

    public static String report() {
        StringBuilder b = new StringBuilder("progress\n");
        MutableCharacterStatsAPI s = character();
        b.append("  character: level ").append(s.getLevel()).append(", unspent skill points ").append(s.getPoints())
         .append(s.getPoints() > 0 ? " (spend them: Character screen)" : "").append(", story points ").append(s.getStoryPoints()).append("\n");
        String story = storyTitles();
        b.append("  story missions running: ").append(story.isEmpty() ? "none (the game shows new ones in Intel > Story)" : story).append("\n");
        // automated ships (hullmod "automated") run best with an AI core as captain
        StringBuilder auto = new StringBuilder();
        for (com.fs.starfarer.api.fleet.FleetMemberAPI fm : Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy()) {
            try {
                if (!fm.getVariant().hasHullMod("automated")) continue;
                boolean core = fm.getCaptain() != null && fm.getCaptain().isAICore();
                auto.append(auto.length() == 0 ? "" : ", ").append(fm.getShipName()).append(" (").append(fm.getHullSpec().getHullName()).append(")")
                    .append(core ? ": AI core " + fm.getCaptain().getNameString() : ": NO AI CORE ('find gamma core' / 'find beta core')");
            } catch (Throwable ignored) {}
        }
        if (auto.length() > 0) b.append("  automated ships: ").append(auto).append("\n");
        java.util.List<MarketAPI> mine = Misc.getPlayerMarkets(true);
        if (mine.isEmpty()) {
            b.append("  colonies: none yet (the guide: found one at 500k-1M credits; see 'colony sites')\n");
        } else {
            for (MarketAPI m : mine) {
                b.append("  colony ").append(m.getName()).append(": size ").append(m.getSize())
                 .append(", stability ").append(Math.round(m.getStabilityValue()))
                 .append(", hazard ").append(Math.round(m.getHazardValue() * 100f)).append("%")
                 .append(", net income ").append(Misc.getWithDGS(Math.round(m.getNetIncome()))).append("/month")
                 .append(m.hasSpaceport() ? "" : ", NO SPACEPORT");
                StringBuilder sh = new StringBuilder();
                for (CommodityOnMarketAPI c : m.getAllCommodities()) if (c.getDeficitQuantity() > 0) sh.append(sh.length() == 0 ? "" : ", ").append(c.getCommodity().getName());
                if (sh.length() > 0) b.append(", shortages: ").append(sh);
                StringBuilder ind = new StringBuilder();
                for (Industry i : m.getIndustries()) ind.append(ind.length() == 0 ? "" : ", ").append(i.getCurrentName()).append(i.isBuilding() ? " (building)" : "");
                b.append("\n    industries: ").append(ind).append("\n");
            }
        }
        return b.toString().trim();
    }
}
