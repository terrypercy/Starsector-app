package claudecaptain;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * Entry point. Everything this mod adds to a campaign is transient (scripts and listeners are re-added
 * on every load), so nothing of it goes into save files and the mod can be removed from a save safely.
 */
public class CaptainModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() throws Exception {
        try {
            Global.getSettings().writeTextFileToCommon(Files.STATE, "{\n  \"state\": \"TITLE_SCREEN\",\n  \"captain_version\": \"" + Files.VERSION
                    + "\",\n  \"game_version\": \"" + Global.getSettings().getVersionString() + "\",\n  \"real_time_ms\": " + System.currentTimeMillis() + "\n}\n");
        } catch (Throwable t) {
            Global.getLogger(CaptainModPlugin.class).warn("Claude Captain: could not write state", t);
        }
        Speech.start();
        Heartbeat.start();
        Rules.s("gov_min_supply_days", ""); // creates captain_rules.txt with the defaults on first run
        DialogAgent.rules();                // ...and dialog_rules.txt
        EventLog.info("mod", "Claude Captain " + Files.VERSION + " loaded (game " + Global.getSettings().getVersionString() + ")");
        EventLog.flush(true);
    }

    @Override
    public void onGameLoad(boolean newGame) {
        Captain.install();
        TripRunner.install();
        BountyRunner.install();
        CaptainHud.install();
        LootTracker.register();
        BattleConfig.load(); // 1.0.7: deploy list, auto and margin come back with the save
        EventLog.info("mod", "campaign loaded; battle deploy " + BattleConfig.deploy + (BattleConfig.auto ? " (auto-battle on)" : "") + "; " + (Governor.armed ? "Claude in control (paused until 'run')" : "player in control until the first inbox batch"));
    }
}
