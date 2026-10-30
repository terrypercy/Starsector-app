package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.Script;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An in-game question from Claude: a popup with one button per answer, so the
 * player never has to leave the game to reply. State is static and transient
 * (nothing here is saved in the campaign).
 *
 * With no dialog open the popup is a small interaction dialog with all the
 * options. While another dialog is open (docked at a market, say) a question
 * with two answers or fewer uses the game's confirm popup; a longer one waits
 * until the open dialog is closed, and a message in the feed says so.
 */
public class AskPopup implements InteractionDialogPlugin {

    /** Answer number meaning "I'll answer in the chat instead". */
    public static final int IN_CHAT = 99;

    private static String question = null;
    private static List<String> options = new ArrayList<String>();
    private static int answer = 0;
    private static String answerText = null;
    private static boolean shown = false;
    private static boolean told = false;

    private InteractionDialogAPI dialog;

    // ------------------------------------------------------------ parsing

    /** A parsed "ask" line: the question and its answers. */
    public static class Parsed {
        public String question;
        public List<String> options = new ArrayList<String>();
    }

    /** Parses "ask <question> | <option> | <option> ...". Throws if malformed. */
    public static Parsed parse(String line) throws CommandException {
        String body = line.trim();
        if (body.toLowerCase().startsWith("ask")) body = body.substring(3).trim();
        String[] parts = body.split("\\|");
        Parsed p = new Parsed();
        for (int i = 1; i < parts.length; i++) {
            String o = parts[i].trim();
            if (o.length() > 0) p.options.add(o);
        }
        String q = parts.length == 0 ? "" : parts[0].trim();
        if (q.length() > 1 && q.startsWith("\"") && q.endsWith("\"")) q = q.substring(1, q.length() - 1).trim();
        p.question = q;
        if (q.length() == 0 || p.options.isEmpty())
            throw new CommandException("usage: ask <question> | <option 1> | <option 2> ...");
        if (p.options.size() > 8) throw new CommandException("ask takes at most 8 options");
        return p;
    }

    // ------------------------------------------------------------ state

    public static void begin(String q, List<String> opts) {
        question = q;
        options = new ArrayList<String>(opts);
        answer = 0;
        answerText = null;
        shown = false;
        told = false;
    }

    public static void cancel() {
        question = null;
        answer = 0;
        answerText = null;
        shown = false;
        told = false;
    }

    public static boolean active() { return question != null; }
    public static boolean answered() { return question != null && answer != 0; }
    public static int answerNumber() { return answer; }
    public static String answerText() { return answerText; }

    /** Called about once a second while a question is waiting. */
    public static void tryShow() {
        if (question == null || shown || answer != 0) return;
        CampaignUIAPI ui = Global.getSector().getCampaignUI();
        boolean busy = ui.getCurrentInteractionDialog() != null || ui.isShowingDialog();
        if (!busy) {
            if (ui.showInteractionDialog(new AskPopup(), Global.getSector().getPlayerFleet())) {
                shown = true;
            }
            return;
        }
        if (options.size() <= 2) {
            final String yes = options.get(0);
            final String no = options.size() > 1 ? options.get(1) : "Answer in chat";
            final boolean noIsChat = options.size() < 2;
            boolean ok = ui.showConfirmDialog(question, yes, no, new Script() {
                public void run() { choose(1, yes); }
            }, new Script() {
                public void run() { choose(noIsChat ? IN_CHAT : 2, no); }
            });
            if (ok) { shown = true; return; }
        }
        if (!told) {
            told = true;
            ui.addMessage("[Claude] has a question - close this menu to see it", new java.awt.Color(120, 200, 255));
        }
    }

    static void choose(int n, String text) {
        if (question == null || answer != 0) return;
        answer = n;
        answerText = text;
    }

    // ------------------------------------------------------------ dialog plugin

    public void init(InteractionDialogAPI d) {
        dialog = d;
        try { d.hideVisualPanel(); } catch (Throwable ignored) {}
        d.getTextPanel().addParagraph("Claude asks:");
        d.getTextPanel().addParagraph(question == null ? "(no question)" : question);
        for (int i = 0; i < options.size(); i++) {
            d.getOptionPanel().addOption(options.get(i), Integer.valueOf(i + 1));
        }
        d.getOptionPanel().addOption("I'll answer in the chat", Integer.valueOf(IN_CHAT));
    }

    public void optionSelected(String text, Object data) {
        int n = data instanceof Integer ? ((Integer) data).intValue() : IN_CHAT;
        choose(n, text);
        if (dialog != null) dialog.dismiss();
    }

    public void optionMousedOver(String text, Object data) {}
    public void advance(float amount) {}
    public void backFromEngagement(EngagementResultAPI result) {}
    public Object getContext() { return null; }
    public Map<String, MemoryAPI> getMemoryMap() { return null; }
}
