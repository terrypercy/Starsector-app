package claudecaptain;

/** Auto-battle settings, changed by the "battle" command and read by the combat plugin. */
public final class BattleConfig {
    /** When true, campaign battles skip the deployment screen and the AI fights them. */
    public static volatile boolean auto = false;
    /** "auto" (enough ships to win comfortably), "all", or a comma-separated list of ship names/hulls. */
    public static volatile String deploy = "auto";
    /** How much stronger than the enemy (by deployment points) "auto" aims to be. */
    public static volatile float margin = 1.5f;
    /** Last battle summary, for status reports. */
    public static volatile String lastResult = "";

    private BattleConfig() {}

    private static final String KEY = "claudecaptain_battle";

    /** 1.0.7: the battle settings live in the save, so a restart or reload keeps them. */
    public static void save() {
        try {
            java.util.Map<String, Object> pd = com.fs.starfarer.api.Global.getSector().getPersistentData();
            pd.put(KEY, (auto ? "on" : "off") + "|" + margin + "|" + deploy);
        } catch (Throwable ignored) {}
    }

    /** On game load: the saved settings, else the battle_deploy / battle_auto_on_load rules. */
    public static void load() {
        String deployRule = Rules.s("battle_deploy", "");
        if (deployRule != null && deployRule.trim().length() > 0) deploy = deployRule.trim();
        if (Rules.b("battle_auto_on_load", false)) auto = true;
        try {
            Object o = com.fs.starfarer.api.Global.getSector().getPersistentData().get(KEY);
            if (o instanceof String) {
                String[] p = ((String) o).split("\\|", 3);
                if (p.length == 3) {
                    auto = auto || "on".equals(p[0]);
                    margin = Float.parseFloat(p[1]);
                    if (p[2].trim().length() > 0) deploy = p[2].trim();
                }
            }
        } catch (Throwable ignored) {}
    }
}
