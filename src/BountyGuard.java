package claudecaptain;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.EngagementResultForFleetAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel;
import com.fs.starfarer.api.campaign.ReputationActionResponsePlugin.ReputationAdjustmentResult;

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
 * This guard watches every player engagement. If the bounty flagship is among the enemy's destroyed or
 * disabled ships, the kill is the player's. When the intel then ends END_OTHER, the guard pays exactly what
 * the game would have (bounty credits + PERSON_BOUNTY_REWARD reputation) and rewrites the result to
 * END_PLAYER_BOUNTY so it can never pay twice. Each step is logged with diagnostics.
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
            if (r.type == PersonBountyIntel.BountyResultType.END_OTHER) {
                String s = pay(b, "the game missed your kill");
                EventLog.alert("bounty", "PAID (fixed): " + s);
            }
        }
    }

    static String who(PersonBountyIntel b) {
        return b.getPerson() == null ? b.getName() : b.getPerson().getNameString();
    }

    /** Pays what the game pays for a player kill and marks the bounty as paid to the player. */
    static String pay(PersonBountyIntel b, String why) {
        PersonBountyIntel.BountyResult r = b.getResult();
        if (r == null) return who(b) + ": still active, nothing paid";
        if (r.type == PersonBountyIntel.BountyResultType.END_PLAYER_BOUNTY) return who(b) + ": already paid";
        int payment = (int) b.getBountyCredits();
        Global.getSector().getPlayerFleet().getCargo().getCredits().add(payment);
        ReputationAdjustmentResult rep = null;
        try {
            rep = Global.getSector().adjustPlayerReputation(
                    new RepActionEnvelope(RepActions.PERSON_BOUNTY_REWARD, null, null, null, true, false), b.getFaction().getId());
        } catch (Throwable t) {
            Global.getLogger(BountyGuard.class).warn("bounty guard: reputation change failed", t);
        }
        {
            r.type = PersonBountyIntel.BountyResultType.END_PLAYER_BOUNTY;
            r.payment = payment;
            r.rep = rep;
        }
        Global.getLogger(BountyGuard.class).info("Claude Captain paid bounty " + payment + " for " + who(b) + " (" + why + ")");
        return who(b) + ": paid " + Shipyard.n(payment) + " cr from " + b.getFaction().getDisplayName() + " (" + why + ")";
    }

    /** 'bounty pay <name>': pays a bounty that ended END_OTHER after the player killed the target (one time only). */
    static String payManual(String q) throws CommandException {
        String key = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        if (key.isEmpty()) throw new CommandException("usage: bounty pay <name>");
        PersonBountyIntel hit = null;
        for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel(PersonBountyIntel.class)) {
            PersonBountyIntel b = (PersonBountyIntel) i;
            String w = who(b);
            if (w != null && w.toLowerCase(java.util.Locale.ROOT).contains(key)) { hit = b; break; }
        }
        if (hit == null) throw new CommandException("no person bounty matching '" + q + "' in the intel");
        PersonBountyIntel.BountyResult r = hit.getResult();
        if (r == null) throw new CommandException(who(hit) + ": still active, nothing to pay");
        if (r.type == PersonBountyIntel.BountyResultType.END_PLAYER_BOUNTY) return who(hit) + ": already paid";
        if (r.type != PersonBountyIntel.BountyResultType.END_OTHER)
            throw new CommandException(who(hit) + ": ended " + r.type + ", only END_OTHER bounties can be paid this way");
        String s = pay(hit, "manual payout for a missed kill");
        EventLog.alert("bounty", "PAID (manual): " + s);
        return s;
    }
}
