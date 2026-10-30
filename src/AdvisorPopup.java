package claudecaptain;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import org.apache.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.awt.Color;
import java.util.List;
import java.util.Map;

/**
 * The advisor widget: a small panel (not the big dialog) with a scan summary and
 * three buttons. Open it with F8, or it opens itself after a save loads and after
 * each advisor task list. Clicking an option saves the game and runs its tasks.
 *
 * It is hosted by a hidden interaction dialog because the game only offers custom
 * panels from inside one; the dialog shows nothing of its own.
 */
public class AdvisorPopup implements InteractionDialogPlugin {

    private static final Logger log = Global.getLogger(AdvisorPopup.class);
    private static final Color BLUE = new Color(120, 200, 255);
    private static final Color GREY = new Color(160, 170, 180);
    private static final Color GREEN = new Color(110, 210, 120);
    private static final Color GOLD = new Color(240, 200, 70);
    private static final Color RED = new Color(230, 100, 90);
    /** The key that opens the widget. */
    static final int KEY = Keyboard.KEY_F8;
    static final float W = 480f, H = 640f;

    private InteractionDialogAPI dialog;

    // ---------------------------------------------------------------- opening

    /** Opens the widget if nothing else is open. Returns false when it couldn't. */
    public static boolean open() {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        if (ui.getCurrentInteractionDialog() != null || ui.isShowingDialog()) {
            ui.addMessage("[Advisor] close the open menu first, then press F8", BLUE);
            return false;
        }
        boolean ok = ui.showInteractionDialog(new AdvisorPopup(), Global.getSector().getPlayerFleet());
        log.info("Claude Bridge: advisor open -> " + ok);
        return ok;
    }

    private static volatile long wantSince = 0L;

    /** Ask for the widget to open as soon as no menu is open (after a task list, or at game load). */
    public static void requestOpen() { wantSince = System.currentTimeMillis(); }

    /** Transient script: the hotkey, and opening on request. */
    public static class Hotkey implements EveryFrameScript {
        private boolean wasDown = false;
        private long lastTry = 0L;
        private final long created = System.currentTimeMillis();
        private boolean loadRequested = false, announced = false;
        private final boolean[] fDown = new boolean[13];

        public boolean isDone() { return false; }
        public boolean runWhilePaused() { return true; }

        public void advance(float amount) {
            if (!announced) { announced = true; log.info("Claude Bridge: advisor hotkey script running (key F8)"); }
            boolean down = false;
            try {
                down = Keyboard.isKeyDown(KEY);
                // diagnostics: log the first press of any F-key so key problems show up in starsector.log
                for (int f = 1; f <= 12; f++) {
                    boolean d = Keyboard.isKeyDown(Keyboard.KEY_F1 + (f <= 10 ? f - 1 : f + 1));
                    if (d && !fDown[f]) log.info("Claude Bridge: F" + f + " pressed");
                    fDown[f] = d;
                }
            } catch (Throwable t) {
                // keyboard not readable this frame; try again next frame
            }
            if (down && !wasDown) {
                log.info("Claude Bridge: advisor hotkey pressed");
                try { open(); } catch (Throwable t) { log.error("Claude Bridge: advisor open failed", t); }
            }
            wasDown = down;

            long now = System.currentTimeMillis();
            if (!loadRequested && now - created > 5000L) { loadRequested = true; requestOpen(); }
            if (wantSince != 0L && now - lastTry >= 1000L) {
                lastTry = now;
                if (now - wantSince > 60000L) { wantSince = 0L; log.info("Claude Bridge: advisor auto-open gave up"); return; }
                try {
                    CampaignUIAPI ui = Global.getSector().getCampaignUI();
                    boolean busy = ui.getCurrentInteractionDialog() != null || ui.isShowingDialog()
                            || Global.getSector().getPlayerFleet() == null || Global.getSector().getPlayerFleet().getBattle() != null;
                    if (!busy && ui.showInteractionDialog(new AdvisorPopup(), Global.getSector().getPlayerFleet())) {
                        wantSince = 0L;
                        log.info("Claude Bridge: advisor auto-opened");
                    }
                } catch (Throwable t) {
                    wantSince = 0L;
                    log.error("Claude Bridge: advisor auto-open failed", t);
                }
            }
        }
    }

    // ---------------------------------------------------------------- host dialog (shows nothing itself)

    public void init(InteractionDialogAPI d) {
        dialog = d;
        try {
            d.hideVisualPanel();
            d.hideTextPanel();
            d.getOptionPanel().clearOptions();
            d.showCustomVisualDialog(W, H, new Widget(this));
        } catch (Throwable t) {
            log.error("Claude Bridge: advisor widget failed", t);
            try { d.dismiss(); } catch (Throwable ignored) {}
        }
    }

    void closeHost() {
        try { if (dialog != null) dialog.dismiss(); } catch (Throwable ignored) {}
    }

    public void optionSelected(String text, Object data) {}
    public void optionMousedOver(String text, Object data) {}
    public void advance(float amount) {}
    public void backFromEngagement(EngagementResultAPI result) {}
    public Object getContext() { return null; }
    public Map<String, MemoryAPI> getMemoryMap() { return null; }

    // ---------------------------------------------------------------- the widget

    private static class Widget implements CustomVisualDialogDelegate {
        private final AdvisorPopup host;
        private CustomVisualDialogDelegate.DialogCallbacks callbacks;
        private Advisor.Plan plan;
        private boolean rescan = false;

        Widget(AdvisorPopup host) { this.host = host; }

        public void init(CustomPanelAPI panel, CustomVisualDialogDelegate.DialogCallbacks cb) {
            callbacks = cb;
            try {
                plan = Advisor.scan();
            } catch (Throwable t) {
                log.error("Claude Bridge: scan failed", t);
                plan = new Advisor.Plan();
                plan.notes.add("Scan failed: " + t);
            }
            TooltipMakerAPI t = panel.createUIElement(W, H, false);
            t.addSectionHeading("All Objectives", Alignment.MID, 0f);
            // quest-tracker look: summary line, then gold objective titles with [ ] boxes
            for (int i = 0; i < plan.summary.size() && i < 2; i++) t.addPara(plan.summary.get(i), GREY, i == 0 ? 6f : 1f);
            t.addSectionHeading("Intel objectives", Alignment.LMID, 6f);
            if (plan.objectives.isEmpty()) t.addPara("- nothing marked important on the Intel screen", GREY, 2f);
            for (String o : plan.objectives) t.addPara("[ ] " + o, GOLD, 2f);
            t.addSectionHeading("Fleet checklist", Alignment.LMID, 6f);
            for (Advisor.Check c : plan.checks) {
                String tag = c.level == 0 ? "[x] " : (c.level == 1 ? "[ ] " : "[!] ");
                Color col = c.level == 0 ? GREEN : (c.level == 1 ? GOLD : RED);
                t.addPara(tag + c.label + ": " + c.detail, col, 1f);
            }
            if (plan.compassName != null) {
                t.addPara("Objective: " + plan.compassName + " - " + plan.compassDir + ", " + plan.compassDist, GOLD, 8f);
            }
            t.addSectionHeading("Next actions", Alignment.LMID, 6f);
            int n = 1;
            for (Advisor.Option o : plan.options) {
                t.addButton((n == 1 ? "[BEST] " : "") + n + ". " + o.title, Integer.valueOf(n - 1), W - 20f, 26f, 8f);
                t.addPara(o.route(), BLUE, 2f);
                t.addPara(o.why, GREY, 1f);
                n++;
            }
            if (plan.options.isEmpty()) {
                for (String s : plan.notes) t.addPara(s, GREY, 6f);
            }
            t.addButton("Scan again", "rescan", 120f, 22f, 10f);
            t.addButton("Close", "close", 120f, 22f, 4f);
            panel.addUIElement(t).inTL(0f, 0f);
        }

        public CustomUIPanelPlugin getCustomPanelPlugin() {
            return new BaseCustomUIPanelPlugin() {
                @Override public void buttonPressed(Object id) {
                    try {
                        if ("rescan".equals(id)) { rescan = true; callbacks.dismissDialog(); return; }
                        if (id instanceof Integer && plan != null) {
                            int idx = ((Integer) id).intValue();
                            if (idx >= 0 && idx < plan.options.size()) run(plan.options.get(idx));
                        }
                    } catch (Throwable t) {
                        log.error("Claude Bridge: advisor button failed", t);
                    }
                    callbacks.dismissDialog();
                }
                @Override public void processInput(List<InputEventAPI> events) {
                    for (InputEventAPI e : events) {
                        if (e.isConsumed()) continue;
                        if (e.isKeyDownEvent() && e.getEventValue() == Keyboard.KEY_ESCAPE) {
                            e.consume();
                            callbacks.dismissDialog();
                            return;
                        }
                    }
                }
            };
        }

        public float getNoiseAlpha() { return 0f; }
        public void advance(float amount) {}

        public void reportDismissed(int option) {
            host.closeHost();
            if (rescan) requestOpen();
        }
    }

    /** Saves the game, then hands the option's task list to the task runner. */
    static void run(Advisor.Option o) {
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        // save first: by default as a separate copy, so the main save is kept as a restore point
        // default: the game's own autosave slot, so the main save stays as it was ("copy" from v1.11 means the same;
        // the game's Save As call opens a window, so it is not used)
        String mode = Rules.s("save_before_action", "auto").toLowerCase();
        if (!mode.equals("off")) {
            boolean saved = false;
            try {
                if (mode.equals("main")) saved = ui.cmdSave();
                else { ui.autosave(); saved = true; }
            } catch (Throwable t) { log.warn("Claude Bridge: save failed", t); }
            ui.addMessage(saved ? "[Advisor] game saved" + (mode.equals("main") ? "" : " (autosave slot)") : "[Advisor] could not save - save by hand when you can", BLUE);
        }
        Captain.submit("advisor-" + System.currentTimeMillis(), o.title, o.tasks);
        ui.addMessage("[Advisor] " + o.title, BLUE);
    }
}
