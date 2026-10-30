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
}
