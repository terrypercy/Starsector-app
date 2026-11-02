package claudecaptain;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.BattleAutoresolverPluginImpl;
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.0.9 (Nov 2): "Order your second-in-command to handle it" for a post-battle pursuit, without the ship-picker screen
 * the mod can't drive. Does what the game's own picker listener (FleetInteractionDialogPluginImpl$1) does with the
 * ships PursuitMath picks: autoresolve the pursuit, credit the damage, and hand the result back to the dialog.
 */
public final class Pursuit {

    private Pursuit() {}

    /** Resolves the pursuit; returns a line for the log, or throws when this dialog can't do it. */
    public static String autoresolve(InteractionDialogAPI d) {
        InteractionDialogPlugin p = d == null ? null : d.getPlugin();
        if (!(p instanceof FleetInteractionDialogPluginImpl)) throw new IllegalStateException("not a fleet battle dialog");
        FleetInteractionDialogPluginImpl plugin = (FleetInteractionDialogPluginImpl) p;
        FleetEncounterContext ctx = (FleetEncounterContext) plugin.getContext();
        BattleAPI battle = ctx.getBattle();
        if (battle == null) throw new IllegalStateException("no battle");

        List<FleetMemberAPI> can = plugin.getPursuitCapablePlayerShips();
        if (can == null || can.isEmpty()) throw new IllegalStateException("no ship can pursue");
        float enemyFP = 0f;
        for (CampaignFleetAPI f : battle.getNonPlayerSide()) if (f != null && !f.isStationMode()) enemyFP += f.getFleetPoints();

        List<PursuitMath.Cand> cands = new ArrayList<PursuitMath.Cand>();
        for (FleetMemberAPI m : can) {
            float speed = 0f;
            try { speed = m.getStats().getMaxSpeed().getModifiedValue(); } catch (Throwable ignored) {}
            cands.add(new PursuitMath.Cand(m.getShipName(), speed, m.getRepairTracker().getCR(), m.getDeploymentPointsCost()));
        }
        List<Integer> idx = PursuitMath.pick(cands, enemyFP, Rules.f("pursuit_min_cr", 0.5f), Rules.f("pursuit_strength", 2f));
        if (idx.isEmpty()) throw new IllegalStateException("no ship ready to pursue (all under " + Math.round(Rules.f("pursuit_min_cr", 0.5f) * 100f) + "% CR)");
        List<FleetMemberAPI> sent = new ArrayList<FleetMemberAPI>();
        StringBuilder names = new StringBuilder();
        float dp = 0f;
        for (Integer i : idx) {
            sent.add(can.get(i));
            dp += cands.get(i).dp;
            names.append(names.length() == 0 ? "" : ", ").append(cands.get(i).name);
        }

        BattleAutoresolverPluginImpl auto = new BattleAutoresolverPluginImpl(battle);
        auto.resolvePlayerPursuit(ctx, sent);
        EngagementResultAPI res = auto.getResult();
        if (res == null) throw new IllegalStateException("the autoresolve gave no result");

        // damage credit, as the game's picker listener computes it (feeds XP and reputation)
        CampaignFleetAPI ally = null;
        float allyFP = 0f;
        for (CampaignFleetAPI f : battle.getPlayerSide()) {
            if (f.isPlayerFleet() || f.getFleetData().getMembersListCopy().isEmpty() || f.isStationMode()) continue;
            if (ally == null) ally = f;
            allyFP += ally.getFleetPoints();
        }
        float playerFP = 0f;
        for (FleetMemberAPI m : sent) playerFP += m.getFleetPointCost();
        float killed = 0f;
        for (FleetMemberAPI m : res.getLoserResult().getDisabled()) killed += m.getFleetPointCost();
        for (FleetMemberAPI m : res.getLoserResult().getDestroyed()) killed += m.getFleetPointCost();
        float total = Math.max(1f, playerFP + allyFP);
        ctx.setPlayerFPHullDamageToEnemies(ctx.getPlayerFPHullDamageToEnemies() + killed * playerFP / total);
        if (ally != null && allyFP > 0f) ctx.setAllyFPHullDamageToEnemies(ctx.getAllyFPHullDamageToEnemies() + killed * allyFP / total);

        try { d.getTextPanel().addPara("Your second-in-command takes the pursuit with " + sent.size() + " of the fastest, most ready ships."); } catch (Throwable ignored) {}
        plugin.backFromEngagement(res);
        return "second-in-command pursued with " + sent.size() + " ships (" + Math.round(dp) + " DP vs " + Math.round(enemyFP)
                + " enemy FP): " + names;
    }
}
