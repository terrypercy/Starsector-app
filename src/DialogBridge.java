package claudecaptain;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.ui.g;
import com.fs.starfarer.ui.interfacenew;
import com.fs.starfarer.ui.newui.OoOO;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads and clicks interaction-dialog options. The public API can't list
 * options or read dialog text, so this reaches into the game's UI classes
 * (Starsector 0.98a-RC8). Clicking goes through the same handler a mouse
 * click uses, so the game behaves exactly as if the player clicked.
 */
public final class DialogBridge {

    private DialogBridge() {}

    public static class Opt {
        public int number;
        public String text;
        public Object data;
        public boolean enabled;
        Object item;
    }

    public static List<Opt> options(InteractionDialogAPI d) {
        List<Opt> out = new ArrayList<Opt>();
        if (d == null) return out;
        OptionPanelAPI panel = d.getOptionPanel();
        if (!(panel instanceof OoOO)) return out;
        Map map = ((OoOO) panel).getButtonToItemMap();
        if (map == null) return out;
        int i = 1;
        for (Iterator it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry e = (Map.Entry) it.next();
            Object item = e.getValue();
            if (!(item instanceof OoOO.o)) continue;
            Opt o = new Opt();
            o.number = i++;
            o.item = item;
            o.text = ((OoOO.o) item).o00000();
            o.data = ((OoOO.o) item).nwx();
            Object btn = e.getKey();
            o.enabled = !(btn instanceof ButtonAPI) || ((ButtonAPI) btn).isEnabled();
            out.add(o);
        }
        return out;
    }

    /** True if the game would normally show a confirmation (story point etc.) first. */
    public static boolean needsConfirm(InteractionDialogAPI d, Opt o) {
        try {
            OoOO.o it = (OoOO.o) o.item;
            return it.ø00000 != null || it.Ò00000 != null;
        } catch (Throwable t) { return false; }
    }

    public static void click(InteractionDialogAPI d, Opt o) {
        ((OoOO) d.getOptionPanel()).getDelegate().o00000((OoOO.o) o.item);
    }

    /** The most recent paragraphs of dialog text, oldest first. */
    public static List<String> paragraphs(InteractionDialogAPI d, int max) {
        List<String> all = new ArrayList<String>();
        try {
            TextPanelAPI tp = d.getTextPanel();
            if (tp instanceof com.fs.starfarer.ui.newui.String) {
                g scroller = ((com.fs.starfarer.ui.newui.String) tp).getScroller();
                if (scroller != null) collect(scroller.getContentContainer(), all, 0);
            }
        } catch (Throwable ignored) {}
        if (all.size() > max) return new ArrayList<String>(all.subList(all.size() - max, all.size()));
        return all;
    }

    private static void collect(Object node, List<String> out, int depth) {
        if (node == null || depth > 6) return;
        if (node instanceof LabelAPI) {
            String t = ((LabelAPI) node).getText();
            if (t != null && t.trim().length() > 0) out.add(t.trim());
            return;
        }
        if (node instanceof interfacenew) {
            List kids = ((interfacenew) node).getChildrenCopy();
            if (kids == null) return;
            for (Object k : kids) collect(k, out, depth + 1);
        }
    }
}
