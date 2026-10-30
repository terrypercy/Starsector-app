package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Builds the plain-text snapshot of the game written to status.txt. */
public final class StatusReport {

    private StatusReport() {}

    private static String fmt(float v) { return Misc.getWithDGS(Math.round(v)); }

    public static String build() {
        SectorAPI sector = Global.getSector();
        CampaignFleetAPI pf = sector.getPlayerFleet();
        StringBuilder b = new StringBuilder();
        b.append("# Claude Bridge status\n");
        b.append("state: CAMPAIGN\n");
        b.append("bridge_version: ").append(Files.VERSION).append("\n");
        b.append("real_time_ms: ").append(System.currentTimeMillis()).append("\n");
        b.append("date: ").append(sector.getClock().getDateString()).append("\n");
        b.append("paused: ").append(sector.isPaused()).append("\n");
        if (pf == null) { b.append("player_fleet: none\n"); return b.toString(); }

        CampaignUIAPI ui = sector.getCampaignUI();
        LocationAPI loc = pf.getContainingLocation();
        b.append("\n## Location\n");
        b.append("location: ").append(loc == null ? "?" : loc.getName()).append(loc != null && loc.isHyperspace() ? " (hyperspace)" : "").append("\n");
        b.append("position: ").append(Math.round(pf.getLocation().x)).append(", ").append(Math.round(pf.getLocation().y)).append("\n");
        InteractionDialogAPI d = ui.getCurrentInteractionDialog();
        if (d != null && d.getInteractionTarget() != null) {
            b.append("in_dialog_with: ").append(d.getInteractionTarget().getName()).append("\n");
        }
        StringBuilder active = new StringBuilder();
        for (com.fs.starfarer.api.characters.AbilityPlugin p : pf.getAbilities().values()) {
            if (p.isActive()) active.append(active.length() > 0 ? ", " : "").append(p.getSpec().getName());
        }
        b.append("abilities_on: ").append(active.length() > 0 ? active.toString() : "none").append("\n");
        b.append("auto_battle: ").append(BattleConfig.auto ? "ON (deploy " + BattleConfig.deploy + ")" : "OFF").append("\n");
        if (BattleConfig.lastResult.length() > 0) b.append("last_battle: ").append(BattleConfig.lastResult).append("\n");
        SectorEntityToken course = ui.getUltimateCourseTarget();
        if (course != null) {
            b.append("course_target: ").append(course.getName())
             .append(" (").append(course.getContainingLocation() == null ? "?" : course.getContainingLocation().getName()).append(", ")
             .append(String.format("%.1f", Misc.getDistanceToPlayerLY(course))).append(" ly)\n");
        }
        b.append("in_battle: ").append(pf.getBattle() != null).append("\n");

        if (d != null) {
            b.append("\n## Dialog\n");
            try { b.append(CommandRunner.describeDialog(d, 12)).append("\n"); } catch (Throwable t) { b.append("(couldn't read dialog: ").append(t).append(")\n"); }
        }

        // the game's own quest tracker: active important intel and where it points
        b.append("\n## Objectives (from the Intel screen)\n");
        Object stage = sector.getMemoryWithoutUpdate().get("$tutStage");
        if (stage != null) b.append("  tutorial stage: ").append(stage).append("\n");
        int shown = 0;
        for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin i : sector.getIntelManager().getIntel()) {
            try {
                if (i.isHidden() || i.isEnded() || !i.isImportant()) continue;
                b.append("  ").append(i.getSmallDescriptionTitle());
                SectorEntityToken where = i.getMapLocation(null);
                if (where != null) {
                    b.append(" -> ").append(where.getName());
                    if (where.getContainingLocation() != null) b.append(" (").append(where.getContainingLocation().getName()).append(")");
                }
                b.append("\n");
                if (++shown >= 10) break;
            } catch (Throwable ignored) {}
        }
        if (shown == 0) b.append("  (no important intel)\n");

        CargoAPI c = pf.getCargo();
        b.append("\n## Resources\n");
        b.append("credits: ").append(fmt(c.getCredits().get())).append("\n");
        b.append("supplies: ").append(fmt(c.getSupplies())).append("\n");
        b.append("fuel: ").append(fmt(c.getFuel())).append(" / ").append(fmt(c.getMaxFuel())).append("\n");
        b.append("crew: ").append(c.getCrew()).append("  marines: ").append(c.getMarines())
         .append("  personnel space: ").append(c.getTotalPersonnel()).append(" / ").append(fmt(c.getMaxPersonnel())).append("\n");
        b.append("cargo space: ").append(fmt(c.getSpaceUsed())).append(" / ").append(fmt(c.getMaxCapacity())).append("\n");
        b.append("cargo:\n");
        for (CargoStackAPI s : c.getStacksCopy()) {
            if (s.isNull()) continue;
            String id = s.isCommodityStack() ? s.getCommodityId() : String.valueOf(s.getData());
            b.append("  ").append(fmt(s.getSize())).append(" x ").append(s.getDisplayName()).append(" [").append(id).append("]\n");
        }

        MutableCharacterStatsAPI st = sector.getPlayerStats();
        b.append("\n## Character\n");
        b.append("level: ").append(st.getLevel()).append("  xp: ").append(fmt(st.getXP()))
         .append("  story points: ").append(st.getStoryPoints()).append("  skill points: ").append(st.getPoints()).append("\n");

        b.append("\n## Fleet (").append(pf.getFleetData().getNumMembers()).append(" ships, ")
         .append(Math.round(pf.getFleetPoints())).append(" FP, burn ").append(Math.round(pf.getTravelSpeed())).append(")\n");
        for (FleetMemberAPI m : pf.getFleetData().getMembersListCopy()) {
            b.append("  ").append(m.getShipName()).append(" | ").append(m.getHullSpec().getHullName())
             .append(" [").append(m.getVariant().getHullVariantId()).append("]")
             .append(" | ").append(m.getHullSpec().getHullSize())
             .append(" | CR ").append(Math.round(m.getRepairTracker().getCR() * 100)).append("%")
             .append(" | hull ").append(Math.round(m.getStatus().getHullFraction() * 100)).append("%")
             .append(m.isFlagship() ? " | FLAGSHIP" : "")
             .append(m.isMothballed() ? " | mothballed" : "")
             .append("\n");
        }

        if (loc != null) {
            b.append("\n## Markets here\n");
            for (MarketAPI m : sector.getEconomy().getMarkets(loc)) {
                if (m.isHidden() || m.getPrimaryEntity() == null) continue;
                float dist = Misc.getDistance(pf, m.getPrimaryEntity());
                b.append("  ").append(m.getName()).append(" | ").append(m.getFaction().getDisplayName())
                 .append(" | size ").append(m.getSize())
                 .append(m.getFaction().isHostileTo("player") ? " | HOSTILE" : "")
                 .append(" | ").append(Math.round(dist)).append(" su away\n");
            }

            b.append("\n## Fleets nearby (within 3000 su)\n");
            List<CampaignFleetAPI> near = new ArrayList<CampaignFleetAPI>();
            for (CampaignFleetAPI f : loc.getFleets()) {
                if (f == pf || !f.isAlive()) continue;
                if (Misc.getDistance(pf, f) > 3000f) continue;
                if (!f.isVisibleToPlayerFleet()) continue;
                near.add(f);
            }
            final CampaignFleetAPI ref = pf;
            Collections.sort(near, new Comparator<CampaignFleetAPI>() {
                public int compare(CampaignFleetAPI x, CampaignFleetAPI y) {
                    return Float.compare(Misc.getDistance(ref, x), Misc.getDistance(ref, y));
                }
            });
            for (CampaignFleetAPI f : near) {
                b.append("  ").append(f.getNameWithFactionKeepCase())
                 .append(" | ").append(f.getFleetData().getNumMembers()).append(" ships, ").append(f.getFleetPoints()).append(" FP")
                 .append(f.isHostileTo(pf) ? " | HOSTILE" : "")
                 .append(" | ").append(Math.round(Misc.getDistance(pf, f))).append(" su\n");
            }
        }

        b.append("\n## Relations\n");
        for (FactionAPI f : sector.getAllFactions()) {
            if (!f.isShowInIntelTab() || f.isPlayerFaction()) continue;
            b.append("  ").append(f.getDisplayName()).append(" [").append(f.getId()).append("]: ")
             .append(Math.round(f.getRelationship("player") * 100)).append(" (").append(f.getRelationshipLevel("player")).append(")\n");
        }
        return b.toString();
    }
}
