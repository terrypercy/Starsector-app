package claudecaptain;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.EngagementResultForFleetAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 1.0.8: makes sure a person bounty pays when the player's fleet killed the target ship.
 *
 * Seen in play: the target's flagship (Gula McDaniel's Venture) was disabled in a player fight, yet the
 * bounty ended END_OTHER ("someone else got it") and paid nothing. PersonBountyIntel only pays from its
 * fleet listener, and if that call is missed (battle no longer lists the fleet, or the fleet is gone before
 * the battle report) the intel's own advance() sees the flagship gone and ends it as END_OTHER.
 *
 * 1.0.9: diagnostics only. It watches every player engagement; if the bounty flagship is among the enemy's destroyed
 * or disabled ships and the intel then ends END_OTHER, it raises an ALERT. It never pays (no quiet cheats).
 */
public class BountyGuard extends BaseCampaignEventListener implements EveryFrameScript {

    /** bounty intel -> game day the player killed its flagship */
    static final Map<PersonBountyIntel, Float> killed = new IdentityHashMap<PersonBountyIntel, Float>();
    private float acc = 0f;

    public BountyGuard() { super(false); }

    public static void install() {
        killed.clear();
        BountyGuard g = new BountyGuard();
        Global.getSector().addTransientListener(g);
        Global.getSector().addTransientScript(g);
    }

    @Override
    public void reportPlayerEngagement(EngagementResultAPI r) {
        try {
            check(r.getWinnerResult());
            check(r.getLoserResult());
        } catch (Throwable t) {
            Global.getLogger(BountyGuard.class).warn("bounty guard: engagement check failed", t);
        }
    }

    private void check(EngagementResultForFleetAPI side) {
        if (side == null || side.isPlayer() || side.getFleet() == null) return;
        CampaignFleetAPI f = side.getFleet();
        for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel(PersonBountyIntel.class)) {
            PersonBountyIntel b = (PersonBountyIntel) i;
            FleetMemberAPI fs = b.getFlagship();
            if (fs == null) continue;
            boolean dead = side.getDestroyed().contains(fs) || side.getDisabled().contains(fs);
            boolean sameFleet = b.getFleet() == f;
            if (!dead) continue;
            killed.put(b, Global.getSector().getClock().getDay() * 1f);
            EventLog.info("bounty", "target ship " + fs.getShipName() + " (" + who(b) + ") killed by your fleet"
                    + " | same fleet " + sameFleet + " | result now " + (b.getResult() == null ? "none" : String.valueOf(b.getResult().type)));
        }
    }

    @Override
    public void reportBattleFinished(CampaignFleetAPI primaryWinner, com.fs.starfarer.api.campaign.BattleAPI battle) {
        // diagnostics only: what the bounty intel saw when the battle ended
        try {
            for (PersonBountyIntel b : killed.keySet()) {
                CampaignFleetAPI f = b.getFleet();
                EventLog.info("bounty", "battle finished: " + who(b) + " | player involved " + battle.isPlayerInvolved()
                        + " | target fleet " + (f == null ? "null" : (f.isAlive() ? "alive" : "dead") + ", in battle " + battle.isInvolved(f))
                        + " | result " + (b.getResult() == null ? "none" : String.valueOf(b.getResult().type)));
            }
        } catch (Throwable ignored) {}
    }

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return true; }

    public void advance(float amount) {
        acc += amount;
        if (acc < 0.5f || killed.isEmpty()) return;
        acc = 0f;
        java.util.Iterator<Map.Entry<PersonBountyIntel, Float>> it = killed.entrySet().iterator();
        while (it.hasNext()) {
            PersonBountyIntel b = it.next().getKey();
            PersonBountyIntel.BountyResult r = b.getResult();
            if (r == null) continue; // not decided yet
            it.remove();
            // 1.0.9: no payout from the mod (the player chose: fix how battles end instead). Say so loudly.
            if (r.type == PersonBountyIntel.BountyResultType.END_OTHER)
                EventLog.alert("bounty", "MISSED: " + who(b) + " - your fleet killed the target but the game ended the bounty unpaid (END_OTHER). Tell the player; this is a mod bug in how the battle was closed.");
        }
    }

    static String who(PersonBountyIntel b) {
        return b.getPerson() == null ? b.getName() : b.getPerson().getNameString();
    }
}
