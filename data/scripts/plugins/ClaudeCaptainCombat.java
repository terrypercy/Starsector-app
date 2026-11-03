package data.scripts.plugins;

import claudecaptain.BattleConfig;
import claudecaptain.Files;
import claudecaptain.Lookup;
import claudecaptain.EventLog;
import claudecaptain.Rules;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
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
            if (ours >= theirsDP) {
                try { mine.getTaskManager(false).setFullAssault(true); } catch (Throwable t) { log.warn("full assault failed", t); }
            }
            BattleConfig.lastResult = "deployed " + Math.round(ours) + " DP vs " + Math.round(theirsDP) + (ours >= theirsDP ? " (full assault)" : " (AI orders, we are weaker)");
            try { EventLog.info("battle", BattleConfig.lastResult + ": " + deployedNames); } catch (Throwable ignored) {}
        }
        Object ui = engine.getCombatUI();
        if (ui instanceof CombatState) ((CombatState) ui).setAutopilot(true);
        engine.setPaused(false);
        writeStatus();
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
