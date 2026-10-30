package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads narration from saves/common/claude_bridge/say.txt and speaks it with
 * the built-in Windows voice. Runs on its own background thread so it keeps
 * talking in battles and menus, not just on the campaign map.
 *
 * Only ever speaks text: the text goes to a fixed speech script through an
 * environment variable and is never run as a command.
 *
 * say.txt lines: plain text is spoken in order; "#rate N" (-10..10) sets the
 * speed, "#voice Name" picks an installed voice, "#stop" clears the queue.
 */
public final class Speech implements Runnable {

    private static final Logger log = Global.getLogger(Speech.class);
    public static final String SAY_FILE = Files.DIR + "say.txt";
    /** Relative to the game's working folder (starsector-core). */
    private static final String SCRIPT = "..\\saves\\common\\claude_bridge\\say.js";

    /** Queue marker for a pre-rendered clip in claude_bridge/voice/. */
    private static final String WAV = "\u0001wav:";
    private static final List<String> queue = new ArrayList<String>();
    private static volatile Process current;
    private static volatile int rate = 1;
    private static volatile String voice = "";
    private static boolean started = false;

    private Speech() {}

    public static synchronized void start() {
        if (started) return;
        started = true;
        String os = System.getProperty("os.name");
        if (os == null || !os.toLowerCase().contains("windows")) {
            log.info("Claude Bridge: voice is Windows-only, not starting");
            return;
        }
        Thread t = new Thread(new Speech(), "ClaudeBridgeVoice");
        t.setDaemon(true);
        t.start();
    }

    public static void say(String text) {
        String clean = clean(text);
        if (clean.length() == 0) return;
        synchronized (queue) { queue.add(clean); }
    }

    public static void stop() {
        synchronized (queue) { queue.clear(); }
        Process p = current;
        if (p != null) p.destroy();
    }

    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 1000; i++) {
            char c = s.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        return b.toString().trim();
    }

    public void run() {
        SettingsAPI s = Global.getSettings();
        while (true) {
            try {
                if (s.fileExistsInCommon(SAY_FILE)) {
                    String text = s.readTextFileFromCommon(SAY_FILE);
                    s.deleteTextFileFromCommon(SAY_FILE);
                    if (text != null) {
                        for (String line : text.replace("\r", "").split("\n")) {
                            String l = line.trim();
                            if (l.length() == 0) continue;
                            if (l.startsWith("#rate")) {
                                try { rate = Math.max(-10, Math.min(10, Integer.parseInt(l.substring(5).trim()))); } catch (NumberFormatException ignored) {}
                            } else if (l.startsWith("#voice")) {
                                voice = clean(l.substring(6));
                            } else if (l.startsWith("#stop")) {
                                stop();
                            } else if (l.startsWith("#wav")) {
                                String name = l.substring(4).trim();
                                if (name.matches("[A-Za-z0-9_\\-]{1,64}\\.wav")) {
                                    synchronized (queue) { queue.add(WAV + name); }
                                }
                            } else if (!l.startsWith("#")) {
                                say(l);
                            }
                        }
                    }
                }
                String next = null;
                synchronized (queue) { if (!queue.isEmpty()) next = queue.remove(0); }
                if (next != null) {
                    speakBlocking(next);
                } else {
                    Thread.sleep(300);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                log.warn("Claude Bridge: voice error", t);
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }
    }

    private static void speakBlocking(String text) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("wscript.exe", "//nologo", "//B", "//E:jscript", SCRIPT);
        if (text.startsWith(WAV)) {
            pb.environment().put("CLAUDE_WAV", "..\\saves\\common\\claude_bridge\\voice\\" + text.substring(WAV.length()));
            pb.environment().put("CLAUDE_SAY", "");
        } else {
            pb.environment().put("CLAUDE_SAY", text);
        }
        pb.environment().put("CLAUDE_SAY_RATE", String.valueOf(rate));
        pb.environment().put("CLAUDE_SAY_VOICE", voice);
        Process p = pb.start();
        current = p;
        p.waitFor();
        current = null;
    }
}
