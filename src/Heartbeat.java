package claudecaptain;

import com.fs.starfarer.api.Global;

/**
 * Writes saves/common/claude_captain/running.txt every 10 seconds while the game
 * is open (title screen included). If its time is recent, the game is running and
 * the mod's jar must not be replaced: Java loads classes from it lazily, and a
 * swapped jar breaks the running game ("invalid LOC header").
 */
public final class Heartbeat implements Runnable {

    public static final String FILE = Files.DIR + "running.txt";
    private static boolean started = false;

    private Heartbeat() {}

    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(new Heartbeat(), "ClaudeCaptainHeartbeat");
        t.setDaemon(true);
        t.start();
    }

    public void run() {
        while (true) {
            try {
                boolean campaign = false;
                try { campaign = Global.getSector() != null && Global.getSector().getPlayerFleet() != null; } catch (Throwable ignored) {}
                Global.getSettings().writeTextFileToCommon(FILE,
                        "running: true\nreal_time_ms: " + System.currentTimeMillis()
                        + "\ncaptain_version: " + Files.VERSION
                        + "\nin_campaign: " + campaign
                        + "\nnote: if real_time_ms is less than 30 s old the game is open; do not replace the mod jar\n");
            } catch (Throwable ignored) {}
            try { Thread.sleep(10000L); } catch (InterruptedException e) { return; }
        }
    }
}
