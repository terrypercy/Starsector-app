package claudecaptain;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignUIRenderingListener;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.CutStyle;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The one Captain panel (replaces Claude Bridge's advisor panel and Trade Runner's trade panel).
 * Same mechanism as the old panels: a custom UI panel drawn from a CampaignUIRenderingListener, input
 * from a CampaignInputListener (clicks on the panel are consumed, so they never move the fleet), and
 * a frame-polling fallback while a dock dialog is open (the game sends no campaign input events then).
 *
 * Fixed width, condensed rows (see Hud.rows): header stats, governor chip, goal line, commission bar,
 * three next steps, collapsible sections. hud_fold_key (F8) folds, hud_cycle_key (F9) opens the next
 * section. Drag the title to move it; the position is remembered in claude_captain/hud_pos.txt.
 */
public class CaptainHud implements CampaignUIRenderingListener, EveryFrameScript,
        com.fs.starfarer.api.campaign.listeners.CampaignInputListener {

    private static final Logger log = Global.getLogger(CaptainHud.class);
    static final float W = 252f;
    private static volatile boolean dirty = true;
    private static CaptainHud instance;

    public static void refreshNow() { dirty = true; }

    public static void install() {
        CaptainHud h = new CaptainHud();
        instance = h;
        Global.getSector().getListenerManager().addListener(h, true);
        Global.getSector().addTransientScript(h);
        log.info("Claude Captain: panel installed");
    }

    /** A clickable next step: tasks run as a batch (advisor-*: inside the current run budget). */
    static final class Suggest {
        final String title; final List<String> tasks;
        Suggest(String title, List<String> tasks) { this.title = title; this.tasks = tasks; }
    }

    private final long created = System.currentTimeMillis();
    private long lastScan = 0L;
    private Hud.Data data = new Hud.Data();
    private final List<Suggest> suggests = new ArrayList<Suggest>();
    private String signature = "";
    private CustomPanelAPI panel;
    private final List<ButtonAPI> buttons = new ArrayList<ButtonAPI>();
    private final List<String> buttonIds = new ArrayList<String>();
    private float contentH = 100f, panelX = 14f, panelY = 400f;
    private float barOffset = -1f, barValue = 0f;
    private int barLevel = Hud.L_WARN;
    private boolean folded = false, failed = false, posLoaded = false, announced = false;
    private int open = 0;
    private float left = 14f, top = -1f;   // remembered position (top-left corner, UI coordinates)
    private int mode = 0;                    // 0 hidden, 1 map, 2 docked
    private String armed = null;
    private long armedAt = 0L, visibleSince = 0L;
    private boolean mouseWasDown = false, wasVisible = false, foldWas = false, cycleWas = false;
    private boolean pressOnTitle = false, dragging = false;
    private float pressX, pressY, pressLeft, pressTop;
    private static volatile long lastInputEventsAt = 0L;

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return true; }

    // ---------------------------------------------------------------- per frame

    public void advance(float amount) {
        long now = System.currentTimeMillis();
        if (now - created < 4000L) return;
        try {
            boolean events = now - lastInputEventsAt < 500L;
            boolean fk = key(foldKey()), ck = key(cycleKey());
            if (!events) {
                if (fk && !foldWas) { folded = !folded; dirty = true; }
                if (ck && !cycleWas) { open = Hud.cycle(open); dirty = true; }
            }
            foldWas = fk; cycleWas = ck;
            if (armed != null && now - armedAt > 6000L) { armed = null; dirty = true; }
            if (dirty || now - lastScan > 2500L) { dirty = false; lastScan = now; scan(); }
            if (failed) return;
            if (panel != null) { try { panel.advance(amount); } catch (Throwable ignored) {} }
            if (!events) pollMouse();
            else { mouseWasDown = Mouse.isButtonDown(0); wasVisible = shouldShow(); }
        } catch (Throwable t) { fail("update", t); }
    }

    static boolean key(int k) { try { return k > 0 && Keyboard.isKeyDown(k); } catch (Throwable t) { return false; } }
    static int keyFor(String rule, int def) {
        try { int k = Keyboard.getKeyIndex(Rules.s(rule, "").toUpperCase()); return k > 0 ? k : def; } catch (Throwable t) { return def; }
    }
    static int foldKey() { return keyFor("hud_fold_key", Keyboard.KEY_F8); }
    static int cycleKey() { return keyFor("hud_cycle_key", Keyboard.KEY_F9); }

    // ---------------------------------------------------------------- data

    private void scan() {
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        if (pf == null) return;
        int m = currentMode();
        if (m == 0 && mode != 0 && panel != null) { mode = 0; return; }
        Hud.Data d = new Hud.Data();
        d.folded = folded;
        d.open = open;
        d.credits = pf.getCargo().getCredits().get();
        float maint = Governor.maintPerDay(pf);
        d.supplyDays = GovMath.supplyDays(pf.getCargo().getSupplies(), maint);
        d.minSupplyDays = Rules.f("gov_min_supply_days", 15f);
        d.minCR = Rules.f("gov_min_cr", 40f) / 100f;
        float fpl = 1f;
        try { fpl = pf.getLogistics().getFuelCostPerLightYear(); } catch (Throwable ignored) {}
        d.fuelLy = fpl > 0 ? pf.getCargo().getFuel() / fpl : 0f;
        d.nearestFriendlyLy = Governor.last.nearestFriendlyLy;
        for (FleetMemberAPI fm : pf.getFleetData().getMembersListCopy()) {
            if (Governor.combatShip(fm)) d.lowestCR = Math.min(d.lowestCR, fm.getRepairTracker().getCR());
            d.fleet.add(Hud.cut(fm.getShipName(), 16) + "|" + fm.getRepairTracker().getCR() + "|" + fm.getStatus().getHullFraction());
        }
        d.chip = Hud.cut(Governor.chip(), 40);
        d.chipLevel = Governor.chipLevel();
        Goals.Goal g = Captain.goal;
        if (g != null && !g.finished()) d.goal = Hud.cut(g.line() + ("paused".equals(g.state) ? " [PAUSED]" : ""), 80);
        else if (TripRunner.busy()) d.goal = Hud.cut("Trade: step " + TripRunner.stepIndex + "/" + TripRunner.stepTotal + " " + TripRunner.stepText, 80);
        else if (!"idle".equals(BountyRunner.state)) d.goal = Hud.cut("Hunt: " + BountyRunner.state + " " + BountyRunner.note, 80);
        for (String a : Governor.latch.active()) if (d.alerts.size() < 2) d.alerts.add(Hud.cut(a, 46));
        try {
            String cf = Misc.getCommissionFactionId();
            if (cf != null) { d.commissioned = true; d.commissionFaction = Global.getSector().getFaction(cf).getDisplayName(); }
            else {
                RepMath.Faction t = Reputation.target();
                if (t != null) { d.repFaction = Hud.cut(t.name, 18); d.repRel = t.rel; d.repReq = Reputation.required(); }
            }
        } catch (Throwable ignored) {}
        // bounties, trades, explore
        try {
            for (BountyRunner.Target t : BountyRunner.targets())
                d.bounties.add((t.ok ? "ok" : "skip") + "|" + Hud.cut(t.name, 16) + " " + Math.round(t.theirDP) + "DP " + String.format(java.util.Locale.ROOT, "%.0f", t.ly) + "ly"
                        + (t.reward > 0 ? " " + Hud.money(t.reward) : ""));
        } catch (Throwable ignored) {}
        suggests.clear();
        MarketAPI docked = Goals.dockedMarket();
        try {
            if (m == 2 && docked != null) {
                TradeAdvisor.Plan tp = TradeAdvisor.scan(docked);
                for (TradeAdvisor.Action a : tp.actions) d.trades.add("ok|" + Hud.cut(a.title, 38));
                for (TradeAdvisor.Action a : tp.actions) if (suggests.size() < 3) suggests.add(new Suggest(a.title, a.tasks));
            }
            Planner.Result r = Plans.latest;
            if (r != null) for (int i = 0; i < r.trips.size() && i < 3; i++) d.trades.add("ok|" + Hud.cut((i + 1) + ". " + r.trips.get(i).title() + " " + Hud.money(r.trips.get(i).profit), 40));
        } catch (Throwable ignored) {}
        try {
            for (ExploreMath.Site s : Explorer.scan(pf.getContainingLocation()))
                d.explore.add((Explorer.visited.contains(s.id) ? "skip" : "yes".equals(s.defended) ? "bad" : "maybe".equals(s.defended) ? "warn" : "ok") + "|"
                        + Hud.cut(s.name, 18) + " " + s.kind + " " + Math.round(s.dist / 100f) / 10f + "k su");
        } catch (Throwable ignored) {}
        // next steps: governor problems first, then the advisor's ranked options
        if (d.supplyDays < d.minSupplyDays * 2f) suggests.add(0, new Suggest("Resupply " + Math.round(Rules.f("resupply_days", 60f)) + " days", list("resupply")));
        if (Goals.needsRepair() && d.lowestCR < d.minCR + 0.1f && suggests.size() < 3) suggests.add(new Suggest("Repair at a dockyard", list("repair")));
        try {
            if (m != 2) for (Advisor.Option o : Advisor.scan().options) if (suggests.size() < 3) suggests.add(new Suggest(o.title, o.tasks));
        } catch (Throwable ignored) {}
        for (Suggest s : suggests) d.suggestions.add(Hud.cut(armed != null && armed.equals("opt" + suggests.indexOf(s)) ? "Click again: " + s.title : s.title, 36));
        List<String> ev = EventLog.core.lastHeads(1);
        if (!ev.isEmpty()) d.lastEvent = Hud.cut(ev.get(0).replaceFirst("^#(\\d+) \\S+ ", "#$1 "), 46);
        data = d;
        StringBuilder sig = new StringBuilder().append(m).append('|').append(armed).append('|');
        for (Hud.Row row : Hud.rows(d)) sig.append(row).append(row.parts).append(row.value).append('|');
        if (m != mode || panel == null || !sig.toString().equals(signature)) {
            mode = m;
            signature = sig.toString();
            rebuild();
        }
    }

    static List<String> list(String... t) { List<String> l = new ArrayList<String>(); for (String s : t) l.add(s); return l; }

    // ---------------------------------------------------------------- building

    private void rebuild() {
        try {
            buttons.clear();
            buttonIds.clear();
            barOffset = -1f;
            panel = Global.getSettings().createCustom(W, 900f, new BaseCustomUIPanelPlugin());
            TooltipMakerAPI t = panel.createUIElement(W, 900f, false);
            t.setParaSmallInsignia();
            for (Hud.Row r : Hud.rows(data)) {
                Color c = Hud.color(r.level);
                if (r.kind.equals("title")) {
                    addButton(t, r.text + "   v" + Files.VERSION + "  drag to move", r.id, Hud.TEXT, Hud.HEAD_BG, 18f, 0f);
                } else if (r.kind.equals("stats")) {
                    Color[] hl = new Color[r.parts.size()];
                    for (int i = 0; i < hl.length; i++) hl[i] = Hud.color(r.partLevels.get(i));
                    t.addPara(r.text, 3f, hl, r.parts.toArray(new String[0]));
                } else if (r.kind.equals("chip")) {
                    String lbl = "chip".equals(armed) ? "Click again: " + (Governor.armed ? "hand control to the player" : "give Claude control") : r.text;
                    addButton(t, lbl, r.id, c, new Color(c.getRed() / 6, c.getGreen() / 6, c.getBlue() / 6), 16f, 3f);
                } else if (r.kind.equals("bar")) {
                    t.addPara(r.text, c, 4f);
                    barOffset = t.getHeightSoFar() + 2f;
                    barValue = r.value;
                    barLevel = r.level;
                    t.addSpacer(7f);
                } else if (r.kind.equals("label")) {
                    t.addPara(r.text, Hud.DIM, 5f);
                } else if (r.kind.equals("button")) {
                    addButton(t, r.text, r.id, c, Hud.SECTION_BG, 17f, 2f);
                } else if (r.kind.equals("section")) {
                    addButton(t, r.text, r.id, Hud.TEXT, Hud.SECTION_BG, 16f, 4f);
                } else {
                    t.addPara(Hud.cut(r.text, 52), c, 1f);
                }
            }
            contentH = t.getHeightSoFar() + 4f;
            panel.addUIElement(t).inTL(0f, 0f);
            panel.getPosition().setSize(W, contentH);
        } catch (Throwable e) { fail("build", e); }
    }

    private void addButton(TooltipMakerAPI t, String text, String id, Color fg, Color bg, float h, float pad) {
        ButtonAPI b = t.addButton(Hud.cut(text, 44), id, fg, bg, Alignment.LMID, CutStyle.NONE, W, h, pad);
        if (Captain.activeTitle != null && !Captain.activeIsAdvisor && id.startsWith("opt")) b.setEnabled(false);
        buttons.add(b);
        buttonIds.add(id);
    }

    // ---------------------------------------------------------------- input

    private float mouseX() { return Mouse.getX() / Math.max(0.01f, Global.getSettings().getScreenScaleMult()); }
    private float mouseY() { return Mouse.getY() / Math.max(0.01f, Global.getSettings().getScreenScaleMult()); }

    private boolean inside(float mx, float my) {
        return mx >= panelX - 4f && mx <= panelX + W + 4f && my >= panelY - 4f && my <= panelY + contentH + 4f;
    }

    private String hitId(float mx, float my) {
        for (int i = 0; i < buttons.size(); i++) {
            ButtonAPI b = buttons.get(i);
            float x = b.getPosition().getX(), y = b.getPosition().getY();
            if (mx >= x && mx <= x + b.getPosition().getWidth() && my >= y && my <= y + b.getPosition().getHeight()) return buttonIds.get(i);
        }
        return null;
    }

    private void press(float mx, float my) {
        String id = hitId(mx, my);
        if ("fold".equals(id)) { pressOnTitle = true; dragging = false; pressX = mx; pressY = my; pressLeft = left; pressTop = top; return; }
        click(id);
    }

    private void moveTo(float mx, float my) {
        if (!pressOnTitle) return;
        if (!dragging && Math.abs(mx - pressX) + Math.abs(my - pressY) > 5f) dragging = true;
        if (dragging) { left = pressLeft + (mx - pressX); top = pressTop + (my - pressY); }
    }

    private void release() {
        if (!pressOnTitle) return;
        pressOnTitle = false;
        if (dragging) { dragging = false; savePos(); }
        else { folded = !folded; dirty = true; }
    }

    private void click(String id) {
        if (id == null) { if (armed != null) { armed = null; dirty = true; } return; }
        if (id.startsWith("sec")) { int i = Integer.parseInt(id.substring(3)); open = open == i ? -1 : i; dirty = true; return; }
        boolean confirm = Rules.b("confirm_clicks", true);
        if (confirm && !id.equals(armed)) { armed = id; armedAt = System.currentTimeMillis(); dirty = true; return; }
        armed = null;
        dirty = true;
        if (id.equals("chip")) { if (Governor.armed) Governor.disarm("panel"); else Governor.arm("panel"); return; }
        if (id.startsWith("opt")) {
            int i = Integer.parseInt(id.substring(3));
            if (i < suggests.size()) {
                Suggest s = suggests.get(i);
                Captain.submit("advisor-" + System.currentTimeMillis(), s.title, s.tasks);
                Captain.msg(s.title);
            }
        }
    }

    private void pollMouse() {
        boolean down = Mouse.isButtonDown(0);
        boolean vis = shouldShow();
        if (vis && !wasVisible) visibleSince = System.currentTimeMillis();
        wasVisible = vis;
        float mx = mouseX(), my = mouseY();
        if (vis && down && !mouseWasDown && inside(mx, my) && System.currentTimeMillis() - visibleSince > 600L) press(mx, my);
        if (down) moveTo(mx, my); else release();
        mouseWasDown = down;
    }

    public int getListenerInputPriority() { return 1000; }
    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {}
    public void processCampaignInputPostCore(List<InputEventAPI> events) {}

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        lastInputEventsAt = System.currentTimeMillis();
        if (failed) return;
        try {
            boolean vis = shouldShow();
            if (vis && !wasVisible) visibleSince = System.currentTimeMillis();
            wasVisible = vis;
            for (InputEventAPI e : events) {
                if (e.isConsumed()) continue;
                if (e.isKeyDownEvent() && e.getEventValue() == foldKey()) { folded = !folded; dirty = true; e.consume(); continue; }
                if (e.isKeyDownEvent() && e.getEventValue() == cycleKey()) { open = Hud.cycle(open); dirty = true; e.consume(); continue; }
                if (!vis || !e.isMouseEvent()) continue;
                // the event's own x/y use another scale; the live cursor matches the buttons
                float mx = mouseX(), my = mouseY();
                if (pressOnTitle && e.isMouseMoveEvent()) { moveTo(mx, my); e.consume(); continue; }
                if (pressOnTitle && e.isLMBUpEvent()) { release(); e.consume(); continue; }
                if (!inside(mx, my)) continue;
                if (e.isLMBDownEvent() && System.currentTimeMillis() - visibleSince > 600L) press(mx, my);
                if (!e.isMouseMoveEvent()) e.consume();
            }
        } catch (Throwable t) { log.error("Claude Captain: panel input failed", t); }
    }

    // ---------------------------------------------------------------- position

    private void loadPos() {
        if (posLoaded) return;
        posLoaded = true;
        try {
            if (!Global.getSettings().fileExistsInCommon(Files.HUD_POS)) return;
            for (String l : Global.getSettings().readTextFileFromCommon(Files.HUD_POS).split("\n")) {
                String[] kv = l.trim().split("=");
                if (kv.length != 2) continue;
                if (kv[0].trim().equals("left")) left = Float.parseFloat(kv[1].trim());
                if (kv[0].trim().equals("top")) top = Float.parseFloat(kv[1].trim());
                if (kv[0].trim().equals("open")) open = Integer.parseInt(kv[1].trim());
                if (kv[0].trim().equals("folded")) folded = Boolean.parseBoolean(kv[1].trim());
            }
        } catch (Throwable ignored) {}
    }

    private void savePos() {
        try { Global.getSettings().writeTextFileToCommon(Files.HUD_POS, "left=" + Math.round(left) + "\ntop=" + Math.round(top) + "\nopen=" + open + "\nfolded=" + folded + "\n"); }
        catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- drawing

    private boolean shouldShow() { int m = currentMode(); return m != 0 && m == mode && !failed; }

    private static int currentMode() {
        try {
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (Global.getSector().getPlayerFleet() == null || Global.getSector().getPlayerFleet().getBattle() != null) return 0;
            if (ui.isShowingMenu()) return 0;
            if (ui.getCurrentCoreTab() == com.fs.starfarer.api.campaign.CoreUITabId.CARGO && Goals.dockedMarket() != null) return 2;
            if (ui.getCurrentCoreTab() != null) return 0;
            if (ui.getCurrentInteractionDialog() != null) return Goals.dockedMarket() != null ? 2 : 0;
            if (ui.isShowingDialog()) return 0;
            return 1;
        } catch (Throwable t) { return 0; }
    }

    public void renderInUICoordsBelowUI(ViewportAPI viewport) {}
    public void renderInUICoordsAboveUIAndTooltips(ViewportAPI viewport) { if (mode == 2) draw(); }
    public void renderInUICoordsAboveUIBelowTooltips(ViewportAPI viewport) { if (mode == 1) draw(); }

    private void draw() {
        if (failed || panel == null || !shouldShow()) return;
        try {
            loadPos();
            float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
            if (top < 0f) top = sh - 92f;
            float x = Math.max(4f, Math.min(left, sw - W - 4f));
            float y = Math.max(4f, Math.min(top - contentH, sh - contentH - 4f));
            panel.getPosition().setLocation(x, y);
            panelX = x; panelY = y;
            if (!announced) { announced = true; log.info("Claude Captain: panel drawing at " + x + "," + y + " size " + W + "x" + contentH); }
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            quad(x - 4f, y - 4f, W + 8f, contentH + 8f, 0.02f, 0.04f, 0.07f, 0.93f);
            Color edge = Hud.color(Governor.chipLevel() == 0 ? Hud.L_INFO : Governor.chipLevel() == 1 ? Hud.L_GOOD : Governor.chipLevel() == 2 ? Hud.L_WARN : Hud.L_BAD);
            GL11.glColor4f(edge.getRed() / 255f, edge.getGreen() / 255f, edge.getBlue() / 255f, 0.85f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x - 4f, y - 4f); GL11.glVertex2f(x + W + 4f, y - 4f);
            GL11.glVertex2f(x + W + 4f, y + contentH + 4f); GL11.glVertex2f(x - 4f, y + contentH + 4f);
            GL11.glEnd();
            GL11.glPopAttrib();
            panel.render(1f);
            if (barOffset > 0f) {
                float by = y + contentH - barOffset - 5f;
                Color c = Hud.color(barLevel);
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                quad(x, by, W, 5f, 0.25f, 0.28f, 0.32f, 0.9f);
                quad(x, by, W * Math.max(0f, Math.min(1f, barValue)), 5f, c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 0.95f);
                GL11.glPopAttrib();
            }
        } catch (Throwable t) { fail("draw", t); }
    }

    private static void quad(float x, float y, float w, float h, float r, float g, float b, float a) {
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x + w, y); GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    private void fail(String what, Throwable t) {
        if (!failed) log.error("Claude Captain: panel " + what + " failed; panel off (commands still work)", t);
        failed = true;
        panel = null;
        try { EventLog.warn("panel", "panel " + what + " failed: " + t + " - the panel is off until restart; commands and events still work"); } catch (Throwable ignored) {}
    }

    static String selfTest() {
        CaptainHud h = instance;
        StringBuilder b = new StringBuilder();
        if (h == null) return "FAIL panel not installed (no campaign loaded?)";
        b.append(h.failed ? "FAIL panel failed (see starsector.log)\n" : "OK   panel active\n");
        b.append("     view ").append(h.mode == 1 ? "map" : h.mode == 2 ? "docked" : "hidden").append(", visible ").append(h.shouldShow())
         .append(", folded ").append(h.folded).append(", open section ").append(h.open < 0 ? "none" : Hud.SECTIONS[h.open])
         .append(", at ").append(Math.round(h.panelX)).append(",").append(Math.round(h.panelY)).append(" ").append(Math.round(W)).append("x").append(Math.round(h.contentH)).append("\n");
        boolean ok = true;
        for (int i = 0; i < h.buttons.size(); i++) {
            ButtonAPI bt = h.buttons.get(i);
            float x = bt.getPosition().getX(), y = bt.getPosition().getY();
            boolean in = x >= h.panelX - 6 && x + bt.getPosition().getWidth() <= h.panelX + W + 6 && y >= h.panelY - 6 && y + bt.getPosition().getHeight() <= h.panelY + h.contentH + 6;
            if (!in) ok = false;
        }
        b.append(ok ? "OK   " : "FAIL ").append(h.buttons.size()).append(" buttons ").append(ok ? "inside the panel" : "outside the panel (clicks may miss)").append("\n");
        b.append("     keys: fold ").append(Keyboard.getKeyName(foldKey())).append(", cycle ").append(Keyboard.getKeyName(cycleKey()));
        b.append("\n     next steps: ");
        for (Suggest s : h.suggests) b.append(s.title).append(" ").append(s.tasks).append("; ");
        return b.toString();
    }
}
