package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.listeners.ShowLootListener;
import com.fs.starfarer.api.util.Misc;

/** Remembers the loot screen the game is about to show, so "loot take" can empty it. */
public class LootTracker implements ShowLootListener {

    static CargoAPI loot;
    static InteractionDialogAPI dialog;

    public void reportAboutToShowLootToPlayer(CargoAPI l, InteractionDialogAPI d) {
        loot = l;
        dialog = d;
    }

    public static void register() {
        if (!Global.getSector().getListenerManager().hasListenerOfClass(LootTracker.class)) {
            Global.getSector().getListenerManager().addListener(new LootTracker(), true);
        }
    }

    /** Loot screen still up? */
    public static boolean open() {
        if (loot == null || dialog == null) return false;
        if (Global.getSector().getCampaignUI().getCurrentInteractionDialog() != dialog) {
            loot = null;
            dialog = null;
            return false;
        }
        return true;
    }

    public static String describe() {
        if (!open()) return "no loot screen open";
        StringBuilder b = new StringBuilder("loot on screen:");
        for (CargoStackAPI s : loot.getStacksCopy()) {
            if (s.isNull()) continue;
            b.append("\n  ").append(Misc.getWithDGS(Math.round(s.getSize()))).append(" x ").append(s.getDisplayName());
        }
        return b.toString();
    }

    /** Moves everything that fits into the fleet's cargo, then closes the loot screen. */
    public static String takeAll(boolean take) throws CommandException {
        if (!open()) throw new CommandException("no loot screen open");
        CargoAPI pc = Global.getSector().getPlayerFleet().getCargo();
        StringBuilder took = new StringBuilder();
        StringBuilder left = new StringBuilder();
        if (take) {
            for (CargoStackAPI s : loot.getStacksCopy()) {
                if (s.isNull() || s.getSize() <= 0) continue;
                float qty = s.getSize();
                float room;
                if (s.isFuelStack()) room = pc.getMaxFuel() - pc.getFuel();
                else if (s.isPersonnelStack()) room = pc.getMaxPersonnel() - pc.getTotalPersonnel();
                else {
                    float per = s.getCargoSpacePerUnit();
                    room = per <= 0 ? qty : (pc.getMaxCapacity() - pc.getSpaceUsed()) / per;
                }
                float amt = (float) Math.floor(Math.min(qty, Math.max(0, room)));
                if (amt > 0) {
                    pc.addItems(s.getType(), s.getData(), amt);
                    loot.removeItems(s.getType(), s.getData(), amt);
                    took.append(took.length() > 0 ? ", " : "").append(Misc.getWithDGS(amt)).append(" ").append(s.getDisplayName());
                }
                if (amt < qty) {
                    left.append(left.length() > 0 ? ", " : "").append(Misc.getWithDGS(qty - amt)).append(" ").append(s.getDisplayName());
                }
            }
            loot.removeEmptyStacks();
            pc.sort();
        }
        InteractionDialogAPI d = dialog;
        loot = null;
        dialog = null;
        d.getVisualPanel().closeCoreUI();
        String fin = finishEncounter(d);
        String r = take ? "took: " + (took.length() > 0 ? took : "nothing") : "left the loot behind";
        if (fin != null) r += "\n" + fin;
        if (left.length() > 0) r += "\nno room for: " + left;
        return r;
    }

    /** Value per unit of cargo space (fuel and crew use their own room, so rank them by value alone). */
    static float density(CargoStackAPI s) {
        float per = s.getCargoSpacePerUnit();
        if (s.isFuelStack() || s.isPersonnelStack() || per <= 0f) return s.getBaseValuePerUnit();
        return s.getBaseValuePerUnit() / per;
    }

    /**
     * Takes the loot by value: everything when it fits, else the most valuable per cargo unit first.
     * Nothing already in the hold is ever dropped. Closes the loot screen.
     */
    public static String takeByValue() throws CommandException {
        if (!open()) throw new CommandException("no loot screen open");
        CargoAPI pc = Global.getSector().getPlayerFleet().getCargo();
        java.util.List<CargoStackAPI> stacks = new java.util.ArrayList<CargoStackAPI>();
        for (CargoStackAPI s : loot.getStacksCopy()) if (!s.isNull() && s.getSize() > 0) stacks.add(s);
        java.util.Collections.sort(stacks, new java.util.Comparator<CargoStackAPI>() {
            public int compare(CargoStackAPI a, CargoStackAPI b) { return Float.compare(density(b), density(a)); }
        });
        StringBuilder took = new StringBuilder(), left = new StringBuilder();
        float value = 0f;
        for (CargoStackAPI s : stacks) {
            float qty = s.getSize();
            float room;
            if (s.isFuelStack()) room = pc.getMaxFuel() - pc.getFuel();
            else if (s.isPersonnelStack()) room = pc.getMaxPersonnel() - pc.getTotalPersonnel();
            else {
                float per = s.getCargoSpacePerUnit();
                room = per <= 0 ? qty : (pc.getMaxCapacity() - pc.getSpaceUsed()) / per;
            }
            float amt = (float) Math.floor(Math.min(qty, Math.max(0, room)));
            if (amt > 0) {
                pc.addItems(s.getType(), s.getData(), amt);
                loot.removeItems(s.getType(), s.getData(), amt);
                value += amt * s.getBaseValuePerUnit();
                took.append(took.length() > 0 ? ", " : "").append(Misc.getWithDGS(amt)).append(" ").append(s.getDisplayName());
            }
            if (amt < qty) left.append(left.length() > 0 ? ", " : "").append(Misc.getWithDGS(qty - amt)).append(" ").append(s.getDisplayName());
        }
        loot.removeEmptyStacks();
        pc.sort();
        InteractionDialogAPI d = dialog;
        loot = null;
        dialog = null;
        d.getVisualPanel().closeCoreUI();
        finishEncounter(d);
        return "took " + (took.length() > 0 ? took : "nothing") + " (base value ~" + Misc.getWithDGS(Math.round(value)) + " cr)"
                + (left.length() > 0 ? "; no room for " + left : "");
    }

    /**
     * 1.0.3: closing the post-battle loot screen from code skips the game's own "loot screen closed" step,
     * which is what finishes the battle (bounty payout, XP, reputation) and closes the dialog. Without it the
     * Avery Blanchard bounty (45,000) was never paid. Do that step the way the game does, once.
     */
    static String finishEncounter(InteractionDialogAPI d) {
        try {
            if (d == null || !(d.getPlugin() instanceof com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl)) return null;
            if (Global.getSector().getPlayerFleet().getBattle() == null) return null; // the game already finished it
            com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl p =
                    (com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl) d.getPlugin();
            Object c = p.getContext();
            if (c instanceof com.fs.starfarer.api.impl.campaign.FleetEncounterContext) {
                ((com.fs.starfarer.api.impl.campaign.FleetEncounterContext) c).applyAfterBattleEffectsIfThereWasABattle();
            }
            p.cleanUpBattle();
            d.dismiss();
            return "battle finished the game's way (bounty, XP and reputation are settled now)";
        } catch (Throwable t) {
            org.apache.log4j.Logger.getLogger(LootTracker.class).warn("Claude Captain: finishing the encounter failed", t);
            return "WARNING: could not finish the battle after the loot screen: " + t;
        }
    }
}
