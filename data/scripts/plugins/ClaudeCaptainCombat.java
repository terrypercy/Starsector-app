package data.scripts.plugins;

import claudecaptain.BattleConfig;
import claudecaptain.Files;
import claudecaptain.Lookup;
import claudecaptain.EventLog;
import claudecaptain.Rules;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import claudecaptain.Doctrine;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.combat.CombatState;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Claude Captain auto-battle. Loaded automatically into every battle (files in
 * data/scripts/plugins are). Does nothing unless "battle auto on" was sent.
 *
 * When on: skips the deployment screen, deploys the chosen ships, turns on
 * the flagship autopilot, orders a full assault, writes live status to
 * combat.txt, obeys combat_cmd.txt ("retreat", "end"), and ends the battle
 * once no enemies are left.
 */
public class ClaudeCaptainCombat extends BaseEveryFrameCombatPlugin {

    private static final Logger log = Global.getLogger(ClaudeCaptainCombat.class);
    private static final String STATUS = Files.DIR + "combat.txt";
    private static final String CMD = Files.DIR + "combat_cmd.txt";

    private CombatEngineAPI engine;
    private boolean active = false;
    private boolean deployed = false;
    private float emptyFor = 0f;
    private float routedFor = 0f;
    private boolean ending = false;
    private float timer = 0f;
    private float clearFor = 0f;
    private String deployedNames = "";
    // 1.1.16 two-wave doctrine
    private boolean waves = false, wave2Sent = false, wave1Orders = false, lineFormed = false;
    private float wave2At = -1f;
    private final List<FleetMemberAPI> wave1Ships = new ArrayList<FleetMemberAPI>(), wave2Ships = new ArrayList<FleetMemberAPI>();
    private float wave1StartDP = 0f;
    private com.fs.starfarer.api.combat.AssignmentTargetAPI centerWp;
    private final java.util.Set<FleetMemberAPI> retreatOrdered = new java.util.HashSet<FleetMemberAPI>();
    private final java.util.Map<ShipAPI, CombatFleetManagerAPI.AssignmentInfo> fluxHold = new java.util.HashMap<ShipAPI, CombatFleetManagerAPI.AssignmentInfo>();

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        boolean campaign = engine.isInCampaign() && !engine.isSimulation() && !engine.isMission();
        active = BattleConfig.auto && campaign;
        if (campaign && !active) {
            // the governor's "battle not handled by auto-battle" alarm
            try { EventLog.alert("battle", "battle started with auto-battle OFF: fight it on screen ('battle auto on' for next time)"); EventLog.flush(true); } catch (Throwable ignored) {}
        }
        if (!active) return;
        Object ui = engine.getCombatUI();
        if (ui instanceof CombatState) {
            ((CombatState) ui).setShowDeploymentDialogOnStart(false);
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!active || engine == null) return;
        try {
            if (!deployed) {
                deployed = true;
                deploy();
                return;
            }
            timer += amount;
            if (timer < 1f && !engine.isPaused()) return;
            timer = 0f;
            readCommands();
            if (waves && !ending) doctrineTick();
            writeStatus();
            if (ending) retryEnd(); else checkEnd();
        } catch (Throwable t) {
            log.error("Claude Captain combat error", t);
        }
    }

    // ---------------------------------------------------------------- deploy

    private boolean isCivilian(FleetMemberAPI m) {
        ShipHullSpecAPI h = m.getHullSpec();
        return h.isCivilianNonCarrier() || h.getHints().contains(ShipHullSpecAPI.ShipTypeHints.CIVILIAN);
    }

    /** Ships too worn to fight: they malfunction and get destroyed (low combat readiness or a wrecked hull). */
    private boolean tooWorn(FleetMemberAPI m) { return wornBelow(m, 0.25f, 0.25f); }

    /**
     * 1.1.12 (Nov 2 live): CGR Star Ark went in at 30% CR / 40% hull against Flitch's remnant and was disabled
     * again. Ships under battle_fresh_cr / battle_fresh_hull only deploy when the healthy ones don't cover the enemy.
     */
    private boolean worn(FleetMemberAPI m) {
        return wornBelow(m, Rules.f("battle_fresh_cr", 0.5f), Rules.f("battle_fresh_hull", 0.5f));
    }

    private boolean wornBelow(FleetMemberAPI m, float cr, float hull) {
        try {
            if (m.getRepairTracker().getCR() < cr) return true;
            if (m.getStatus().getHullFraction() < hull) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean escaping() {
        try { return engine.getContext() != null && engine.getContext().getPlayerGoal() == com.fs.starfarer.api.fleet.FleetGoal.ESCAPE; }
        catch (Throwable t) { return false; }
    }

    private void deploy() {
        CombatFleetManagerAPI mine = engine.getFleetManager(FleetSide.PLAYER);
        CombatFleetManagerAPI theirs = engine.getFleetManager(FleetSide.ENEMY);
        List<FleetMemberAPI> reserves = mine.getReservesCopy();
        List<FleetMemberAPI> pick = new ArrayList<FleetMemberAPI>();
        String mode = BattleConfig.deploy == null ? "auto" : BattleConfig.deploy.trim().toLowerCase();

        if (mode.equals("all")) {
            for (FleetMemberAPI m : reserves) if (!isCivilian(m) && !m.isMothballed() && !worn(m)) pick.add(m);
            if (pick.isEmpty()) for (FleetMemberAPI m : reserves) if (!isCivilian(m) && !m.isMothballed() && !tooWorn(m)) pick.add(m);
        } else if (mode.equals("auto") && "waves".equalsIgnoreCase(Rules.s("battle_doctrine", "waves")) && !escaping()) {
            waves = true;
            List<FleetMemberAPI> w1 = wave1Pick(mine, reserves);
            pick.addAll(w1);
            wave1Ships.clear(); wave1Ships.addAll(w1); wave1StartDP = 0f;
            for (FleetMemberAPI m : w1) wave1StartDP += m.getDeploymentPointsCost();
        } else if (mode.equals("auto")) {
            float enemyDP = 0f;
            for (FleetMemberAPI m : theirs.getReservesCopy()) enemyDP += m.getDeploymentPointsCost();
            for (FleetMemberAPI m : theirs.getDeployedCopy()) enemyDP += m.getDeploymentPointsCost();
            List<FleetMemberAPI> combat = new ArrayList<FleetMemberAPI>();
            for (FleetMemberAPI m : reserves) if (!isCivilian(m) && !m.isMothballed() && !tooWorn(m)) combat.add(m);
            // healthy ships first (largest first), worn ones only if still needed
            Collections.sort(combat, new Comparator<FleetMemberAPI>() {
                public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                    boolean wa = worn(a), wb = worn(b);
                    if (wa != wb) return wa ? 1 : -1;
                    return Float.compare(b.getDeploymentPointsCost(), a.getDeploymentPointsCost());
                }
            });
            float mineDP = 0f;
            for (FleetMemberAPI m : combat) {
                if (mineDP >= enemyDP * BattleConfig.margin && !pick.isEmpty()) break;
                pick.add(m);
                mineDP += m.getDeploymentPointsCost();
            }
        } else {
            for (String want : mode.split(",")) {
                String w = want.trim();
                if (w.length() == 0) continue;
                FleetMemberAPI best = null;
                int bestScore = 0;
                for (FleetMemberAPI m : reserves) {
                    if (pick.contains(m)) continue;
                    int s = Math.max(Lookup.score(w, m.getId(), m.getShipName()),
                            Lookup.score(w, m.getHullId(), m.getHullSpec().getHullName()));
                    if (s > bestScore) { bestScore = s; best = m; }
                }
                if (best != null) pick.add(best);
            }
        }
        if (pick.isEmpty() && !reserves.isEmpty()) {
            for (FleetMemberAPI m : reserves) if (!isCivilian(m) && !tooWorn(m)) { pick.add(m); break; }
        }
        if (pick.isEmpty() && !reserves.isEmpty()) {
            // everything is worn: send only the flagship (or the first ship) rather than everything
            FleetMemberAPI one = null;
            for (FleetMemberAPI m : reserves) if (m.isFlagship()) one = m;
            if (one == null) one = reserves.get(0);
            pick.add(one);
        }

        float y = -engine.getMapHeight() / 2f + 1200f;
        float spacing = 400f;
        float x0 = -spacing * (pick.size() - 1) / 2f;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < pick.size(); i++) {
            FleetMemberAPI m = pick.get(i);
            mine.spawnFleetMember(m, new Vector2f(x0 + i * spacing, y), 90f, 3f);
            names.append(names.length() > 0 ? ", " : "").append(m.getShipName()).append(" (").append(m.getHullSpec().getHullName()).append(")");
        }
        deployedNames = names.toString();
        if (escaping()) {
            // pursuit battle: the goal is to get away, not to fight
            try { mine.getTaskManager(false).orderFullRetreat(); BattleConfig.lastResult = "escaping: full retreat ordered"; } catch (Throwable t) { log.warn("retreat failed", t); }
        } else {
            // full assault only when what we deployed outweighs the enemy; otherwise the AI's own (more careful) orders
            float ours = 0f, theirsDP = 0f;
            for (FleetMemberAPI m : pick) ours += m.getDeploymentPointsCost();
            for (FleetMemberAPI m : theirs.getReservesCopy()) theirsDP += m.getDeploymentPointsCost();
            for (FleetMemberAPI m : theirs.getDeployedCopy()) theirsDP += m.getDeploymentPointsCost();
            // 1.1.14: only a clear advantage (battle_full_assault_ratio, default 1.5x) - a near-even fight on Full Assault lost three ships
            float ratio = 1.5f;
            try { ratio = Rules.f("battle_full_assault_ratio", 1.5f); } catch (Throwable ignored) {}
            boolean assault = !waves && claudecaptain.TravelMath.fullAssault(ours, theirsDP, ratio);
            if (assault) {
                try { mine.getTaskManager(false).setFullAssault(true); } catch (Throwable t) { log.warn("full assault failed", t); }
            }
            BattleConfig.lastResult = "deployed " + Math.round(ours) + " DP vs " + Math.round(theirsDP) + (waves ? " (wave 1 of 2)" : assault ? " (full assault)" : " (AI holds its line: not " + ratio + "x stronger)");
            try { EventLog.info("battle", BattleConfig.lastResult + ": " + deployedNames); } catch (Throwable ignored) {}
        }
        Object ui = engine.getCombatUI();
        if (ui instanceof CombatState) ((CombatState) ui).setAutopilot(true);
        engine.setPaused(false);
        writeStatus();
    }

    // ---------------------------------------------------------------- 1.1.16 two-wave doctrine

    private String roleOf(FleetMemberAPI m) {
        return Doctrine.role(m.getHullSpec().getHullSize().name(), m.getHullId(), isCivilian(m) || m.isMothballed());
    }

    private float deployedDP(CombatFleetManagerAPI mgr) {
        float dp = 0f;
        for (FleetMemberAPI m : mgr.getDeployedCopy()) dp += m.getDeploymentPointsCost();
        return dp;
    }

    /** Wave 1: cappers, Monitors and Eradicators, healthy first, inside min(wave1 cap, the game's cap). */
    private List<FleetMemberAPI> wave1Pick(CombatFleetManagerAPI mine, List<FleetMemberAPI> reserves) {
        List<FleetMemberAPI> out = new ArrayList<FleetMemberAPI>();
        float room = Math.min(Rules.f("battle_wave1_max_dp", 104f), Doctrine.room(mine.getMaxStrength(), deployedDP(mine)));
        List<FleetMemberAPI> cand = new ArrayList<FleetMemberAPI>();
        for (FleetMemberAPI m : reserves) if (Doctrine.wave1(roleOf(m)) && !tooWorn(m) && !worn(m)) cand.add(m);
        Collections.sort(cand, new Comparator<FleetMemberAPI>() {
            public int compare(FleetMemberAPI a, FleetMemberAPI b) { return Float.compare(a.getDeploymentPointsCost(), b.getDeploymentPointsCost()); }
        });
        float used = 0f;
        for (FleetMemberAPI m : cand) {
            if (used + m.getDeploymentPointsCost() > room) continue;
            out.add(m); used += m.getDeploymentPointsCost();
        }
        return out;
    }

    private void doctrineTick() {
        CombatFleetManagerAPI mine = engine.getFleetManager(FleetSide.PLAYER);
        CombatTaskManagerAPI tm = mine.getTaskManager(false);
        float t = engine.getTotalElapsedTime(false);
        if (!wave1Orders && t > 3f) { wave1Orders = true; wave1Orders(mine, tm); }
        if (!wave2Sent) {
            int held = 0, total = 0;
            for (BattleObjectiveAPI o : engine.getObjectives()) { total++; if (o.getOwner() == 0) held++; }
            float str = wave1Strength(mine);
            if (Doctrine.wave2Due(held, total, t, Rules.f("battle_wave2_after_s", 60f), str, Rules.f("battle_wave1_losing_below", 0.75f))) {
                wave2Sent = true; wave2At = t;
                try { EventLog.info("battle", "wave 2 trigger at " + Math.round(t) + " s: " + held + "/" + total + " objectives held, wave 1 at " + Math.round(str * 100f) + "% strength"); } catch (Throwable ignored) {}
                wave2(mine, tm, held);
            }
        } else if (!lineFormed) {
            float arrived = wave2Arrived(mine);
            if (Doctrine.lineFormed(arrived, t - wave2At, Rules.f("battle_line_arrived", 0.75f), Rules.f("battle_line_formed_s", 60f))) {
                lineFormed = true;
                try { tm.orderSearchAndDestroy(); EventLog.info("battle", "line formed (" + Math.round(arrived * 100f) + "% of wave 2 at the front, " + Math.round(t - wave2At) + " s after it dropped): search and destroy"); } catch (Throwable e) { log.warn("s&d failed", e); }
            }
        }
        retreatsAndFlux(mine, tm);
    }

    /** 1.1.17: wave 1's strength now vs at the start, DP weighted by hull; retreated or lost ships count as 0. */
    private float wave1Strength(CombatFleetManagerAPI mine) {
        if (wave1StartDP <= 0f) return 1f;
        float now = 0f;
        for (FleetMemberAPI m : wave1Ships) {
            ShipAPI s = mine.getShipFor(m);
            if (s == null || !s.isAlive() || s.isRetreating()) continue;
            now += m.getDeploymentPointsCost() * s.getHitpoints() / Math.max(1f, s.getMaxHitpoints());
        }
        return now / wave1StartDP;
    }

    /** 1.1.17: share of wave 2 (by count) within battle_line_radius of the map centre, i.e. at the front. */
    private float wave2Arrived(CombatFleetManagerAPI mine) {
        if (wave2Ships.isEmpty()) return 1f;
        float r = Rules.f("battle_line_radius", 3000f);
        int alive = 0, there = 0;
        for (FleetMemberAPI m : wave2Ships) {
            ShipAPI s = mine.getShipFor(m);
            if (s == null || !s.isAlive()) continue;
            alive++;
            if (s.getLocation().length() <= r) there++;
        }
        return alive == 0 ? 1f : (float) there / alive;
    }

    private DeployedFleetMemberAPI dfm(CombatFleetManagerAPI mgr, FleetMemberAPI m) {
        ShipAPI s = mgr.getShipFor(m);
        return s == null ? null : mgr.getDeployedFleetMember(s);
    }

    private void wave1Orders(CombatFleetManagerAPI mine, CombatTaskManagerAPI tm) {
        StringBuilder b = new StringBuilder();
        // cappers: one per objective, nearest first
        List<BattleObjectiveAPI> objs = new ArrayList<BattleObjectiveAPI>(engine.getObjectives());
        for (FleetMemberAPI m : mine.getDeployedCopy()) {
            if (!Doctrine.CAPPER.equals(roleOf(m)) || objs.isEmpty()) continue;
            DeployedFleetMemberAPI d = dfm(mine, m);
            if (d == null || !d.canBeGivenOrders()) continue;
            BattleObjectiveAPI best = null; float bd = Float.MAX_VALUE;
            for (BattleObjectiveAPI o : objs) {
                float dd = com.fs.starfarer.api.util.Misc.getDistance(o.getLocation(), d.getLocation());
                if (dd < bd) { bd = dd; best = o; }
            }
            if (best == null) continue;
            objs.remove(best);
            tm.giveAssignment(d, tm.createAssignment(CombatAssignmentType.CAPTURE, best, false), false);
            b.append(m.getShipName()).append(" -> capture ").append(best.getDisplayName()).append("; ");
        }
        // Monitors: intercept the fastest enemy
        DeployedFleetMemberAPI fastest = null; float sp = 0f;
        CombatFleetManagerAPI enemy = engine.getFleetManager(FleetSide.ENEMY);
        for (DeployedFleetMemberAPI e : enemy.getDeployedCopyDFM()) {
            if (e.isFighterWing() || e.getShip() == null) continue;
            if (e.getShip().getMaxSpeed() > sp) { sp = e.getShip().getMaxSpeed(); fastest = e; }
        }
        for (FleetMemberAPI m : mine.getDeployedCopy()) {
            if (!Doctrine.STALL.equals(roleOf(m)) || fastest == null) continue;
            DeployedFleetMemberAPI d = dfm(mine, m);
            if (d == null || !d.canBeGivenOrders()) continue;
            tm.giveAssignment(d, tm.createAssignment(CombatAssignmentType.INTERCEPT, fastest, false), false);
            b.append(m.getShipName()).append(" -> intercept; ");
        }
        // Eradicators: defend the centre lane
        centerWp = tm.createWaypoint2(new Vector2f(0f, 0f), true);
        for (FleetMemberAPI m : mine.getDeployedCopy()) {
            if (!Doctrine.SUPPORT.equals(roleOf(m))) continue;
            DeployedFleetMemberAPI d = dfm(mine, m);
            if (d == null || !d.canBeGivenOrders()) continue;
            tm.giveAssignment(d, tm.createAssignment(CombatAssignmentType.DEFEND, centerWp, false), false);
            b.append(m.getShipName()).append(" -> defend centre; ");
        }
        try { EventLog.info("battle", "wave 1 orders: " + b); } catch (Throwable ignored) {}
    }

    /** Wave 2: capitals and other cruisers, biggest first, inside the game's cap; then Monitors escort the biggest. */
    private void wave2(CombatFleetManagerAPI mine, CombatTaskManagerAPI tm, int held) {
        List<FleetMemberAPI> cand = new ArrayList<FleetMemberAPI>();
        for (FleetMemberAPI m : mine.getReservesCopy()) if (Doctrine.CORE.equals(roleOf(m)) && !tooWorn(m)) cand.add(m);
        Collections.sort(cand, new Comparator<FleetMemberAPI>() {
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                boolean wa = worn(a), wb = worn(b);
                if (wa != wb) return wa ? 1 : -1;
                return Float.compare(b.getDeploymentPointsCost(), a.getDeploymentPointsCost());
            }
        });
        float room = Doctrine.room(mine.getMaxStrength(), deployedDP(mine));
        float y = -engine.getMapHeight() / 2f + 1200f, x = -800f, used = 0f;
        StringBuilder b = new StringBuilder();
        FleetMemberAPI anchor = null;
        for (FleetMemberAPI m : cand) {
            if (used + m.getDeploymentPointsCost() > room) continue;
            mine.spawnFleetMember(m, new Vector2f(x, y), 90f, 3f);
            wave2Ships.add(m);
            x += 400f; used += m.getDeploymentPointsCost();
            if (anchor == null) anchor = m;
            b.append(m.getShipName()).append(", ");
        }
        try { EventLog.info("battle", "wave 2 (" + held + " objective(s) held, " + Math.round(used) + " DP): " + b); } catch (Throwable ignored) {}
        if (anchor == null) return;
        DeployedFleetMemberAPI a = dfm(mine, anchor);
        if (a == null) return;
        for (FleetMemberAPI m : mine.getDeployedCopy()) {
            if (!Doctrine.STALL.equals(roleOf(m))) continue;
            DeployedFleetMemberAPI d = dfm(mine, m);
            if (d == null || !d.canBeGivenOrders()) continue;
            tm.giveAssignment(d, tm.createAssignment(CombatAssignmentType.HEAVY_ESCORT, a, false), false);
            try { EventLog.info("battle", m.getShipName() + " escorts " + anchor.getShipName()); } catch (Throwable ignored) {}
            break; // one Monitor on the anchor, the other keeps stalling
        }
    }

    /** Pull back ships under battle_retreat_hull (default 40%) or with most weapons out; vent ships at 80% hard flux. */
    private void retreatsAndFlux(CombatFleetManagerAPI mine, CombatTaskManagerAPI tm) {
        float hullLimit = Rules.f("battle_retreat_hull", 0.4f);
        for (DeployedFleetMemberAPI d : mine.getDeployedCopyDFM()) {
            ShipAPI s = d.getShip();
            if (s == null || d.isFighterWing() || !s.isAlive() || s.isRetreating()) continue;
            FleetMemberAPI m = d.getMember();
            float hull = s.getHitpoints() / Math.max(1f, s.getMaxHitpoints());
            // 1.1.17: only weapons destroyed for good count; EMP'd / flamed-out ones come back in seconds
            int w = 0, off = 0;
            for (com.fs.starfarer.api.combat.WeaponAPI wp : s.getAllWeapons()) {
                if (wp.isDecorative()) continue;
                w++;
                if (wp.isPermanentlyDisabled()) off++;
            }
            if (!retreatOrdered.contains(m) && d.canBeGivenRetreatOrders() && Doctrine.shouldRetreat(hull, w, off, hullLimit, Rules.f("battle_retreat_weapons_hull", 0.75f))) {
                retreatOrdered.add(m);
                tm.orderRetreat(d, false, false);
                try { EventLog.info("battle", "retreat " + m.getShipName() + " (hull " + Math.round(hull * 100f) + "%, " + off + "/" + w + " weapons destroyed)"); } catch (Throwable ignored) {}
                continue;
            }
            float hf = s.getFluxTracker().getHardFlux() / Math.max(1f, s.getFluxTracker().getMaxFlux());
            CombatFleetManagerAPI.AssignmentInfo held = fluxHold.get(s);
            if (held == null && Doctrine.fluxBackOff(hf) && d.canBeGivenOrders()) {
                Vector2f back = new Vector2f(s.getLocation().x, s.getLocation().y - 1500f);
                CombatFleetManagerAPI.AssignmentInfo a = tm.createAssignment(CombatAssignmentType.DEFEND, tm.createWaypoint2(back, true), false);
                tm.giveAssignment(d, a, false);
                fluxHold.put(s, a);
            } else if (held != null && Doctrine.fluxRecovered(hf)) {
                tm.removeAssignment(held);
                fluxHold.remove(s);
            }
        }
    }

    // ---------------------------------------------------------------- status & control

    private int alive(int owner, StringBuilder b) {
        int n = 0;
        for (ShipAPI s : engine.getShips()) {
            if (s.getOwner() != owner || s.isFighter() || s.isHulk() || !s.isAlive() || s.isShuttlePod() || s.isDrone()) continue;
            if (s.isStationModule()) continue;
            n++;
            if (b != null) {
                b.append("  ").append(s.getName() == null ? s.getHullSpec().getHullName() : s.getName())
                 .append(" | ").append(s.getHullSpec().getHullName())
                 .append(" | hull ").append(Math.round(100f * s.getHitpoints() / Math.max(1f, s.getMaxHitpoints()))).append("%")
                 .append(" | CR ").append(Math.round(s.getCurrentCR() * 100f)).append("%")
                 .append(" | flux ").append(Math.round(s.getFluxLevel() * 100f)).append("%")
                 .append(s.isRetreating() ? " | RETREATING" : "")
                 .append("\n");
            }
        }
        return n;
    }

    private void writeStatus() {
        StringBuilder b = new StringBuilder("# Claude Captain battle status\n");
        b.append("real_time_ms: ").append(System.currentTimeMillis()).append("\n");
        b.append("battle_time_s: ").append(Math.round(engine.getTotalElapsedTime(false))).append("\n");
        b.append("deployed_by_claude: ").append(deployedNames).append("\n");
        b.append("\n## Your ships\n");
        int mine = alive(0, b);
        b.append("\n## Enemy ships\n");
        int theirs = alive(1, b);
        b.append("\nyours alive: ").append(mine).append("  enemies alive: ").append(theirs)
         .append("  enemy reserves: ").append(engine.getFleetManager(FleetSide.ENEMY).getReservesCopy().size()).append("\n");
        if (ending) b.append("battle ending\n");
        try { Global.getSettings().writeTextFileToCommon(STATUS, b.toString()); } catch (Throwable ignored) {}
    }

    private void readCommands() throws Exception {
        SettingsAPI s = Global.getSettings();
        if (!s.fileExistsInCommon(CMD)) return;
        String text = s.readTextFileFromCommon(CMD);
        s.deleteTextFileFromCommon(CMD);
        if (text == null) return;
        String t = text.trim().toLowerCase();
        if (t.contains("retreat")) {
            engine.getFleetManager(FleetSide.PLAYER).getTaskManager(false).orderFullRetreat();
            BattleConfig.lastResult = "ordered full retreat";
        } else if (t.contains("assault")) {
            engine.getFleetManager(FleetSide.PLAYER).getTaskManager(false).setFullAssault(true);
        } else if (t.contains("end")) {
            ending = true;
            engine.endCombat(1f);
        }
    }

    /**
     * 1.1.12 (Nov 2 live): after endCombat the game sat on "The enemy fleet is defeated. Press ESCAPE" until a
     * human pressed it. If the battle is still running 6 s after ending it, end it again for the player side.
     */
    private float endWait = 0f;
    private void retryEnd() {
        endWait += 1f;
        if (endWait < 6f) return;
        endWait = 0f;
        try {
            if (engine.isCombatOver()) return;
            engine.endCombat(0f, FleetSide.PLAYER);
            EventLog.info("battle", "battle still open after ending it - ended again for our side");
        } catch (Throwable t) { log.warn("end retry failed", t); }
    }

    private void checkEnd() {
        if (ending) return;
        int theirs = alive(1, null);
        int mine = alive(0, null);
        int enemyReserves = engine.getFleetManager(FleetSide.ENEMY).getReservesCopy().size();
        boolean routed = engine.isEnemyInFullRetreat() && enemyReserves == 0;
        if (routed) routedFor += 1f; else routedFor = 0f;
        // pursuit battles: a ship that got away can stay listed in reserve forever, so an empty field for 20 s ends it too
        if (theirs == 0 && engine.getTotalElapsedTime(false) > 10f) emptyFor += 1f; else emptyFor = 0f;
        if ((theirs == 0 && (enemyReserves == 0 || engine.isEnemyInFullRetreat())) || routedFor >= 15f || emptyFor >= 20f) {
            clearFor += 1f;
            if (clearFor >= 3f) {
                ending = true;
                BattleConfig.lastResult = theirs == 0 ? "won: no enemies left" : "won: the enemy is fleeing";
                try { EventLog.info("battle", "ending the battle: " + BattleConfig.lastResult + (emptyFor >= 20f ? " (field empty for 20 s)" : "")); EventLog.flush(true); } catch (Throwable ignored) {}
                engine.endCombat(2f);
            }
        } else {
            clearFor = 0f;
        }
        if (mine == 0 && engine.getTotalElapsedTime(false) > 10f) {
            BattleConfig.lastResult = "no ships of yours left on the field";
        }
    }
}
