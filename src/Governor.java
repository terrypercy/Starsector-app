package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Safety governor. While Claude is in control ("armed": any inbox batch arms it; 'control player' or the
 * panel chip hands the game back), the campaign only runs inside a granted time budget (run <days> |
 * run until arrived|done|event). Alarms (low supplies, fuel short of the nearest friendly market, low CR,
 * an incident, a stronger hostile closing in, budget used up) revoke the budget and pause, with an ALERT
 * event. While the player is in control the same alarms are logged and shown, but nothing pauses.
 */
public final class Governor {

    private Governor() {}

    public static final GovMath.Budget budget = new GovMath.Budget();
    public static final GovMath.Latch latch = new GovMath.Latch();
    public static volatile boolean armed = false;
    public static volatile String pausedReason = "";
    public static volatile GovMath.Input last = new GovMath.Input();

    private static long lastCheck = 0L, lastFriendly = 0L, lastTs = 0L;
    private static boolean wasRunning = false, baselineStale = true, budgetNoted = false;
    private static final Map<String, Float> hullBase = new HashMap<String, Float>();
    private static int membersBase = -1;
    private static final Map<String, Float> fleetDist = new HashMap<String, Float>();
    private static long lastBattleTs = 0L;
    private static float friendlyLy = -1f;
    private static int friendlyJumps = 0;
    private static String friendlyName = "";

    static SectorAPI sector() { return Global.getSector(); }

    public static void reset() {
        pauseSeen = false; playerHold = false;
        budget.revoke();
        armed = Rules.b("gov_arm_on_load", false);
        pausedReason = "";
        lastTs = 0L; baselineStale = true; membersBase = -1;
        hullBase.clear(); fleetDist.clear();
    }

    /** May the campaign run right now? */
    public static boolean mayRun() { return !playerHold && (!armed || budget.active()); }

    /** 1.0.9: the player paused the game; nothing in the mod unpauses until a new 'run' or the player unpauses. */
    public static volatile boolean playerHold = false;
    private static long modPauseTs = 0L;
    private static boolean prevPaused = false, pauseSeen = false;

    /** Every pause the mod makes goes through here, so a pause it did NOT make is known to be the player's. */
    public static void modPause() {
        modPauseTs = System.currentTimeMillis();
        try { sector().setPaused(true); } catch (Throwable ignored) {}
    }

    /** Called every frame: spots the player's own pause (Space) and holds it. */
    static void watchPlayerPause() {
        boolean paused = sector().isPaused();
        long now = System.currentTimeMillis();
        if (!pauseSeen) { pauseSeen = true; prevPaused = paused; return; } // 1.0.9: a save loads paused - that's not the player
        boolean dialog = false;
        try { dialog = sector().getCampaignUI().getCurrentInteractionDialog() != null; } catch (Throwable ignored) {}
        if (!prevPaused && paused && !dialog && now - modPauseTs > 1500L) {
            if (!playerHold) {
                playerHold = true;
                budget.revoke();
                pausedReason = "paused by the player";
                EventLog.alert("gov", "the player paused the game: run budget revoked; the mod won't unpause until the player does or a new 'run' is given");
            }
        } else if (prevPaused && !paused && playerHold) {
            playerHold = false; // the player unpaused
        }
        prevPaused = paused;
    }

    /** Every place that used to unpause the game asks here first. */
    public static void unpause() {
        try {
            // 1.0.9: never let time pass while a battle is still open in a dialog: the bounty intel would see its
            // target gone before the game reports the battle, and end the bounty unpaid (END_OTHER)
            if (sector().getCampaignUI().getCurrentInteractionDialog() != null && sector().getPlayerFleet().getBattle() != null) return;
            if (mayRun()) sector().setPaused(false);
        } catch (Throwable ignored) {}
    }

    public static void arm(String why) {
        if (!armed) EventLog.info("gov", "Claude in control (" + why + "): the game runs only inside a run budget");
        armed = true;
    }

    public static void disarm(String why) {
        if (armed) EventLog.info("gov", "player in control (" + why + "): the governor only warns");
        armed = false;
        budget.revoke();
    }

    public static String run(String args) {
        playerHold = false; // 1.0.9: an explicit run lifts the player-pause hold
        Object[] p = GovMath.parseRun(args, Rules.f("gov_until_max_days", 60f));
        float days = (Float) p[0];
        String until = (String) p[1];
        latch.ack();
        budget.grant(days, until);
        budget.untilEventAfter = EventLog.lastNotable;
        budgetNoted = false;
        pausedReason = "";
        arm("run");
        if (days > 0f && sector().getCampaignUI().getCurrentInteractionDialog() == null) sector().setPaused(false);
        return "run budget: " + budget.describe() + "; active alarms acknowledged";
    }

    /** A goal started without a 'run' in its batch: run until done, capped (rule gov_implicit_max_days). */
    public static void implicitRun() {
        float max = Rules.f("gov_implicit_max_days", 15f);
        if (max <= 0f || budget.active()) return;
        latch.ack();
        budget.grant(max, "done");
        budget.untilEventAfter = EventLog.lastNotable;
        pausedReason = "";
        EventLog.info("gov", "implicit run until done (max " + Math.round(max) + " days); send 'run ...' to change");
    }

    public static void stop(String why) {
        budget.revoke();
        pausedReason = why;
        try { if (sector().getCampaignUI().getCurrentInteractionDialog() == null) modPause(); } catch (Throwable ignored) {}
    }

    public static String chip() {
        if (!armed) return "PLAYER";
        if (budget.active()) return "RUN " + budget.describe();
        return "PAUSED" + (pausedReason.length() > 0 ? ": " + pausedReason : "");
    }

    public static int chipLevel() {
        if (!armed) return 0;
        if (budget.active()) return 1;
        return pausedReason.length() > 0 && !pausedReason.startsWith("budget") && !pausedReason.startsWith("done") ? 3 : 2;
    }

    // ------------------------------------------------------------------ per frame (from Captain)

    /** 1.0.9: when the fleet entered CR-draining terrain (0 = not inside). */
    private static long hazardSinceTs = 0L;

    public static void tick() {
        try { watchPlayerPause(); } catch (Throwable ignored) {}
        SectorAPI s = sector();
        CampaignFleetAPI pf = s.getPlayerFleet();
        if (pf == null) return;
        InteractionDialogAPI d = s.getCampaignUI().getCurrentInteractionDialog();
        long ts = s.getClock().getTimestamp();
        if (lastTs != 0L && !s.isPaused()) {
            float dt = s.getClock().getElapsedDaysSince(lastTs);
            if (dt > 0f && dt < 5f && budget.consume(dt)) {
                if (armed) { pausedReason = "budget used up"; EventLog.add(EventCore.ALERT, "gov", "run budget used up - paused (send 'run <days>' to continue)"); }
            }
        }
        lastTs = ts;
        if (pf.getBattle() != null) lastBattleTs = ts;

        // 'until' conditions
        if (budget.active() && budget.until != null) {
            String u = budget.until;
            boolean stop = false;
            String why = "";
            if (u.equals("event") && EventLog.lastNotable > budget.untilEventAfter) { stop = true; why = "event #" + EventLog.lastNotable + ": " + EventLog.lastNotableText; }
            if (u.equals("done") && Captain.idle()) { stop = true; why = "goals done"; }
            if (u.equals("arrived") && Captain.arrivedSince(budget.untilEventAfter)) { stop = true; why = "arrived"; }
            if (stop) {
                budget.revoke();
                pausedReason = "done: " + why;
                EventLog.goal("run until " + u + " finished (" + why + ") - paused");
            }
        }

        // enforcement: no budget, no time passing (dialogs and battles pause the game by themselves)
        if (armed && !budget.active() && d == null && pf.getBattle() == null && !s.isPaused()) {
            modPause();
            if (!budgetNoted) { budgetNoted = true; if (pausedReason.length() == 0) pausedReason = "no run budget"; }
        }
        if (budget.active()) budgetNoted = false;

        long now = System.currentTimeMillis();
        if (now - lastCheck < 500L) return;
        lastCheck = now;
        try { checkAlarms(pf, d); } catch (Throwable t) { Global.getLogger(Governor.class).warn("Claude Captain: governor check failed", t); }
    }

    /** 1.1.0: ships whose CR recovery the supply guard suspended (fleet member ids). */
    private static final java.util.Set<String> guardSuspended = new java.util.HashSet<String>();
    private static float guardBurn = 0f;

    /** 1.1.0 (Nov 2): suspends CR recovery before it empties the hold, and resumes it once supplies cover it. */
    static void supplyGuard(CampaignFleetAPI pf, GovMath.Input in) {
        if (!armed || !Rules.b("gov_supply_guard", true) || in.inBattle) return;
        List<FleetMemberAPI> ms = pf.getFleetData().getMembersListCopy();
        Map<String, FleetMemberAPI> byId = new HashMap<String, FleetMemberAPI>();
        for (FleetMemberAPI m : ms) byId.put(m.getId(), m);
        guardSuspended.retainAll(byId.keySet());
        GovMath.Guard g = GovMath.supplyGuard(in.supplies, in.burnPerDay, in.maintPerDay, Goals.crBudget(pf), in.docked,
                !guardSuspended.isEmpty(), Rules.f("gov_supply_guard_days", GovMath.BURN_WARN_DAYS), in.minSupplyDays, guardBurn);
        if (g == GovMath.Guard.SUSPEND) {
            guardBurn = in.burnPerDay;
            StringBuilder b = new StringBuilder();
            for (FleetMemberAPI m : ms) {
                if (m.isMothballed() || m.getRepairTracker().isSuspendRepairs()) continue;
                if (m.getRepairTracker().getCR() < GovMath.GUARD_KEEP_CR) continue;
                m.getRepairTracker().setSuspendRepairs(true);
                guardSuspended.add(m.getId());
                b.append(b.length() == 0 ? "" : ", ").append(m.getShipName());
            }
            if (b.length() > 0) EventLog.alert("gov", "supply guard: " + Math.round(in.supplies) + " supplies last "
                    + Math.round(GovMath.supplyDays(in.supplies, in.burnPerDay)) + " days at " + Math.round(in.burnPerDay)
                    + "/day - CR recovery suspended on " + b + " (ships under " + Math.round(GovMath.GUARD_KEEP_CR * 100f)
                    + "% CR keep recovering); it resumes once supplies cover it");
        } else if (g == GovMath.Guard.RESUME) {
            StringBuilder b = new StringBuilder();
            for (String id : guardSuspended) {
                FleetMemberAPI m = byId.get(id);
                if (m == null) continue;
                m.getRepairTracker().setSuspendRepairs(false);
                b.append(b.length() == 0 ? "" : ", ").append(m.getShipName());
            }
            guardSuspended.clear();
            guardBurn = 0f;
            if (b.length() > 0) EventLog.info("gov", "supply guard: supplies cover the recovery now - CR recovery resumed on " + b);
        }
    }

    static float maintPerDay(CampaignFleetAPI pf) {
        float perMonth = 0f;
        for (FleetMemberAPI m : pf.getFleetData().getMembersListCopy()) {
            if (m.isMothballed()) continue;
            try { perMonth += m.getStats().getSuppliesPerMonth().getModifiedValue(); } catch (Throwable ignored) {}
        }
        return perMonth / 30f;
    }

    static float burnPerDay(CampaignFleetAPI pf) {
        try { return pf.getLogistics().getTotalSuppliesPerDay(); } catch (Throwable t) { return maintPerDay(pf); }
    }

    static boolean combatShip(FleetMemberAPI m) { return !m.isMothballed() && !Shipyard.civilian(m) && !m.isFighterWing(); }

    /** Nearest non-hostile market with an open or black market; cached for 5 s. */
    static void nearestFriendly(CampaignFleetAPI pf) {
        long now = System.currentTimeMillis();
        if (now - lastFriendly < 5000L && lastFriendly != 0L) return;
        lastFriendly = now;
        float best = -1f, bestCost = Float.MAX_VALUE;
        int bestJumps = 0;
        String name = "";
        for (MarketAPI m : sector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || m.getPrimaryEntity() == null || m.getFaction().isHostileTo("player")) continue;
            if (m.getSubmarket("open_market") == null && m.getSubmarket("black_market") == null) continue;
            // 1.1.5 (Nov 2): an inhospitable faction refuses docking with the transponder on (Tigra City, Hegemony)
            try { if (m.getFaction().isAtBest(com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER, com.fs.starfarer.api.campaign.RepLevel.INHOSPITABLE) && !m.isFreePort()) continue; } catch (Throwable ignored) {}
            float ly = Misc.getDistanceToPlayerLY(m.getPrimaryEntity());
            // 1.0.7: rank by fuel, so the jump points on the way count
            LocationAPI there = m.getPrimaryEntity().getContainingLocation();
            int j = TravelMath.jumps(there == pf.getContainingLocation(), pf.isInHyperspace(), there != null && there.isHyperspace());
            float cost = ly + j;
            if (cost < bestCost) { bestCost = cost; best = ly; bestJumps = j; name = m.getName(); }
        }
        friendlyLy = best;
        friendlyJumps = bestJumps;
        friendlyName = name;
    }

    static void checkAlarms(CampaignFleetAPI pf, InteractionDialogAPI d) {
        GovMath.Input in = new GovMath.Input();
        in.minSupplyDays = Rules.f("gov_min_supply_days", 15f);
        in.fuelMarginPct = Rules.f("gov_fuel_margin_pct", 25f);
        in.fuelMarginLy = Rules.f("gov_fuel_margin_ly", 2f);
        in.minCR = Rules.f("gov_min_cr", 40f) / 100f;
        in.threatRatio = Rules.f("gov_threat_ratio", 1.0f);
        in.supplies = pf.getCargo().getSupplies();
        in.maintPerDay = maintPerDay(pf);
        in.fuel = pf.getCargo().getFuel();
        try { in.fuelPerLy = pf.getLogistics().getFuelCostPerLightYear(); } catch (Throwable ignored) {}
        nearestFriendly(pf);
        in.nearestFriendlyLy = friendlyLy;
        in.nearestFriendlyName = friendlyName;
        in.nearestFriendlyJumps = friendlyJumps;
        in.docked = d != null && d.getInteractionTarget() != null && d.getInteractionTarget().getMarket() != null
                && !d.getInteractionTarget().getMarket().isPlanetConditionMarketOnly();
        in.inBattle = pf.getBattle() != null;
        in.inDialog = d != null;
        for (FleetMemberAPI m : pf.getFleetData().getMembersListCopy()) {
            if (!combatShip(m)) continue;
            float cr = m.getRepairTracker().getCR();
            if (cr < in.lowestCombatCR) { in.lowestCombatCR = cr; in.lowestCRShip = m.getShipName(); }
        }
        float sinceBattle = lastBattleTs == 0L ? 999f : sector().getClock().getElapsedDaysSince(lastBattleTs);
        in.lowCrExpected = Captain.lowCrExpected() || sinceBattle < 3f;
        in.ourDP = BountyRunner.ourDP();
        try { in.burnPerDay = burnPerDay(pf); } catch (Throwable ignored) {}
        in.hazard = Hazard.at(pf);
        if (in.hazard == null) hazardSinceTs = 0L;
        else if (hazardSinceTs == 0L) hazardSinceTs = sector().getClock().getTimestamp();
        in.hazardDays = hazardSinceTs == 0L ? 0f : sector().getClock().getElapsedDaysSince(hazardSinceTs);

        // threats: visible hostile fleets stronger than us that got closer since the last check
        if (pf.getContainingLocation() != null) {
            Map<String, Float> seen = new HashMap<String, Float>();
            for (CampaignFleetAPI f : pf.getContainingLocation().getFleets()) {
                if (f == pf || !f.isAlive() || f.isStationMode() || !f.isHostileTo(pf) || !f.isVisibleToPlayerFleet()) continue;
                float dist = Misc.getDistance(pf, f);
                seen.put(f.getId(), dist);
                float dp = BountyRunner.dpOf(f);
                Float before = fleetDist.get(f.getId());
                boolean closing = before != null && dist < before - 30f && dist < 3000f;
                if (dp > in.threatDP && closing) { in.threatDP = dp; in.threatName = f.getName(); in.threatClosing = true; }
            }
            fleetDist.clear();
            fleetDist.putAll(seen);
        }

        // incidents: hull lost or ships gone while no battle and no dialog happened since the last look
        if (in.inBattle || in.inDialog || sinceBattle < 0.5f) baselineStale = true;
        else {
            List<FleetMemberAPI> ms = pf.getFleetData().getMembersListCopy();
            if (!baselineStale && membersBase >= 0) {
                if (ms.size() < membersBase) in.shipsLostOutsideBattle = membersBase - ms.size();
                for (FleetMemberAPI m : ms) {
                    Float h = hullBase.get(m.getId());
                    float now = m.getStatus().getHullFraction();
                    if (h != null && h - now > in.worstHullDropOutsideBattle) { in.worstHullDropOutsideBattle = h - now; in.incidentShip = m.getShipName(); }
                }
            }
            hullBase.clear();
            for (FleetMemberAPI m : ms) hullBase.put(m.getId(), m.getStatus().getHullFraction());
            membersBase = ms.size();
            baselineStale = false;
        }
        last = in;

        try { supplyGuard(pf, in); } catch (Throwable t) { Global.getLogger(Governor.class).warn("Claude Captain: supply guard failed", t); }

        List<GovMath.Alarm> fresh = latch.update(GovMath.check(in));
        boolean toMarket = headingToMarket();
        for (GovMath.Alarm a : fresh) {
            // 1.0.5: routine alarms (supplies/fuel while a goal already heads to a market, storm hull scrapes)
            // are logged, not paused on: every pause cost a round trip with nothing to decide
            // 1.1.5: the supply guard handles a high repair burn itself; the burn alarm re-fired and paused at every change of the burn
            if (armed && ((a.key.equals("cr") && in.docked) || (a.key.equals("supplies:burn") && Rules.b("gov_supply_guard", true)) || (!Rules.b("gov_pause_routine", false)
                    && GovMath.routine(a.key, toMarket, in.worstHullDropOutsideBattle, Rules.f("gov_incident_pause_pct", 40f) / 100f)))) {
                EventLog.warn("gov", a.text + " (routine: not paused" + (toMarket ? ", already heading to a market" : "") + ")");
                continue;
            }
            if (armed) {
                budget.revoke();
                pausedReason = a.text;
                EventLog.alert("gov", a.text + " - paused; acknowledge with 'run ...'");
                try { if (sector().getCampaignUI().getCurrentInteractionDialog() == null) modPause(); } catch (Throwable ignored) {}
            } else {
                EventLog.warn("gov", a.text + " (player in control: not paused)");
            }
            try { sector().getCampaignUI().addMessage("[Captain] " + a.text, Hud.BAD); } catch (Throwable ignored) {}
        }
    }

    public static String describe() {
        GovMath.Input in = last;
        return "governor: " + chip() + " | " + (armed ? "Claude in control" : "player in control")
                + "\n  supplies " + Math.round(in.supplies) + " = " + Math.round(GovMath.supplyDays(in.supplies, in.maintPerDay)) + " days maintenance (min "
                + Math.round(in.minSupplyDays) + "), fuel " + Math.round(in.fuel) + ", nearest friendly market " + in.nearestFriendlyName + " "
                + String.format(java.util.Locale.ROOT, "%.1f", in.nearestFriendlyLy) + " ly"
                + "\n  lowest combat CR " + Math.round(in.lowestCombatCR * 100f) + "% (" + in.lowestCRShip + ")" + (in.lowCrExpected ? " - low CR expected by the plan" : "")
                + "\n  active alarms: " + latch.active() + ", acknowledged: " + latch.ackedKeys();
    }

    /** 1.0.5: the running goal (or trade trip) is on its way to a market, where supplies and fuel get fixed. */
    static boolean headingToMarket() {
        try {
            Goals.Goal g = Captain.goal;
            if (g == null || g.finished()) return TripRunner.busy();
            if (g instanceof Goals.Resupply) return true;
            if (g instanceof Goals.Travel) {
                SectorEntityToken t = ((Goals.Travel) g).dest;
                return t != null && t.getMarket() != null && !t.getMarket().isPlanetConditionMarketOnly();
            }
            if (g instanceof Goals.Runner) return !((Goals.Runner) g).hunt;
        } catch (Throwable ignored) {}
        return false;
    }
}
