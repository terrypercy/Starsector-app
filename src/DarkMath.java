package claudecaptain;

/**
 * 1.1.32: why the fleet kept getting caught (Nov 4-5 live): the transponder stayed ON through IX, Trinity and
 * Apex space, so every hostile fleet saw us from far off and knew who we were ("aware of your identity due to
 * your fleet's transponder being turned on"), and the mod turned it ON itself to comply with a hostile patrol's
 * scan demand. Five intercepts cost five story points. Running dark roughly halves how far away we are seen.
 * Pure decision for {@link DarkGuard}.
 */
public final class DarkMath {

    private DarkMath() {}

    /**
     * "off" to switch the transponder off, "on" to switch it back on, or null to leave it.
     * @param rule           rule dark_when_hostile
     * @param busy           docked, in a dialog or in a battle: never touch it then
     * @param on             transponder on now
     * @param hostileSu      distance to the nearest hostile, non-station fleet in this location (negative: none)
     * @param rangeSu        rule dark_hostile_su
     * @param weTurnedOff    this guard switched it off
     * @param clearDays      days since a hostile was last inside the range
     * @param restoreDays    rule dark_restore_days
     * @param revealsUs      turning it on would make a faction hostile (the game's own check)
     */
    public static String decide(boolean rule, boolean busy, boolean on, float hostileSu, float rangeSu,
                                boolean weTurnedOff, float clearDays, float restoreDays, boolean revealsUs) {
        if (!rule || busy) return null;
        boolean hostileNear = hostileSu >= 0f && hostileSu <= rangeSu;
        if (on && hostileNear) return "off";
        if (!on && weTurnedOff && !hostileNear && clearDays >= restoreDays && !revealsUs) return "on";
        return null;
    }
}
