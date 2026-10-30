package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoAPI.CargoItemType;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction.LineItemType;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction.TransactionLineItem;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.util.Misc;

import java.util.HashSet;
import java.util.Set;

/**
 * Buys and sells the way the game's own Trade screen does, so nothing is skipped:
 * the trade is reported to the submarket (price impact, suspicion, trade
 * tracking for reputation and trade XP) and to every campaign listener
 * (missions, events), and the economy is stepped once after a stop's trades,
 * as the Trade screen does when you close it.
 */
public final class Trader {

    private Trader() {}

    public static final class Result {
        public boolean ok;
        public float qty, credits;
        public String text = "";
    }

    /** Submarkets whose stock was refreshed this stop (the Trade screen does this when it opens). */
    private static final Set<String> refreshed = new HashSet<String>();
    private static boolean tradedThisStop = false;

    public static void beginStop() { refreshed.clear(); tradedThisStop = false; }

    /** After a stop's trades: let the economy react, as closing the Trade screen does. */
    public static void endStop() {
        if (!tradedThisStop) return;
        tradedThisStop = false;
        try { Global.getSector().getEconomy().doubleStep(); } catch (Throwable t) {
            Global.getLogger(Trader.class).warn("Trade Runner: economy step after trading failed", t);
        }
    }

    /** OPEN (transponder on), SNEAK (docked dark: black market only) or NONE (no trade), as the docking rules set it. */
    public static CoreUITradeMode tradeMode(InteractionDialogAPI d) {
        try {
            String s = d.getInteractionTarget().getMemoryWithoutUpdate().getString("$tradeMode");
            if (s != null && s.length() > 0) return CoreUITradeMode.valueOf(s);
        } catch (Throwable ignored) {}
        return Global.getSector().getPlayerFleet().isTransponderOn() ? CoreUITradeMode.OPEN : CoreUITradeMode.SNEAK;
    }

    static String n(float v) { return Misc.getWithDGS(Math.round(v)); }

    private static SubmarketAPI sub(MarketAPI m, boolean black) {
        return m.getSubmarket(black ? Submarkets.SUBMARKET_BLACK : Submarkets.SUBMARKET_OPEN);
    }

    private static void refresh(SubmarketAPI s) {
        String k = s.getMarket().getId() + "|" + s.getSpecId();
        if (refreshed.contains(k)) return;
        refreshed.add(k);
        try { s.getPlugin().updateCargoPrePlayerInteraction(); } catch (Throwable ignored) {}
    }

    /** Why this submarket can't be used right now, or null. */
    static String blocked(MarketAPI m, boolean black, CoreUITradeMode mode) {
        SubmarketAPI s = sub(m, black);
        if (s == null) return m.getName() + " has no " + (black ? "black market" : "open market");
        if (mode == CoreUITradeMode.NONE) return m.getName() + " won't trade with you right now";
        if (!black && mode != CoreUITradeMode.OPEN) return "the open market is closed while docked with the transponder off";
        return null;
    }

    /** Live cost of qty units (tariff included). */
    public static float buyCost(MarketAPI m, boolean black, String id, float qty) {
        SubmarketAPI s = sub(m, black);
        return s == null ? 0f : m.getSupplyPrice(id, qty, true) * (1f + s.getTariff());
    }

    /** Live value of selling qty units (after tariff). */
    public static float sellValue(MarketAPI m, boolean black, String id, float qty) {
        SubmarketAPI s = sub(m, black);
        return s == null ? 0f : m.getDemandPrice(id, qty, true) * (1f - s.getTariff());
    }

    /** Stock on offer right now (refreshed the way the Trade screen refreshes it). */
    public static float liveStock(MarketAPI m, boolean black, String id) {
        SubmarketAPI s = sub(m, black);
        if (s == null) return 0f;
        refresh(s);
        return s.getCargo().getCommodityQuantity(id);
    }

    public static Result buy(MarketAPI m, boolean black, String id, float want, float maxSpend, CoreUITradeMode mode) {
        Result r = new Result();
        String why = blocked(m, black, mode);
        CommoditySpecAPI spec = Global.getSector().getEconomy().getCommoditySpec(id);
        String name = spec == null ? id : spec.getName();
        if (why != null) { r.text = "can't buy " + name + ": " + why; return r; }
        SubmarketAPI s = sub(m, black);
        SubmarketPlugin pl = s.getPlugin();
        if (pl.isIllegalOnSubmarket(id, SubmarketPlugin.TransferAction.PLAYER_BUY)) { r.text = name + " can't be bought on the " + s.getNameOneLine(); return r; }
        refresh(s);
        CargoAPI pc = Global.getSector().getPlayerFleet().getCargo();
        float stock = s.getCargo().getCommodityQuantity(id);
        float room;
        if (spec != null && spec.isFuel()) room = pc.getMaxFuel() - pc.getFuel();
        else room = (pc.getMaxCapacity() - pc.getSpaceUsed()) / Math.max(0.01f, spec == null ? 1f : spec.getCargoSpace());
        float qty = (float) Math.floor(Math.min(want, Math.min(stock, room)));
        float limit = Math.min(maxSpend, pc.getCredits().get());
        if (qty < 1f) { r.text = "can't buy " + name + ": stock " + n(stock) + ", room " + n(room); return r; }
        float tariff = s.getTariff();
        float cost = m.getSupplyPrice(id, qty, true) * (1f + tariff);
        for (int i = 0; i < 60 && cost > limit && qty > 1f; i++) {
            qty = (float) Math.floor(qty * 0.95f);
            cost = m.getSupplyPrice(id, qty, true) * (1f + tariff);
        }
        if (cost > limit || qty < 1f) { r.text = "can't afford " + name + " (" + n(cost) + " cr for " + n(qty) + ")"; return r; }

        s.getCargo().removeCommodity(id, qty);
        pc.addCommodity(id, qty);
        pc.getCredits().subtract(cost);
        report(m, s, mode, id, qty, true, cost, tariff);
        r.ok = true; r.qty = qty; r.credits = -cost;
        r.text = "bought " + n(qty) + " " + name + " on the " + s.getNameOneLine() + " for " + n(cost) + " cr ("
                + n(cost / qty) + "/unit incl. " + Math.round(tariff * 100f) + "% tariff)" + (qty < want ? " - limited by stock, room or credits" : "");
        return r;
    }

    public static Result sell(MarketAPI m, boolean black, String id, float want, CoreUITradeMode mode) {
        Result r = new Result();
        String why = blocked(m, black, mode);
        CommoditySpecAPI spec = Global.getSector().getEconomy().getCommoditySpec(id);
        String name = spec == null ? id : spec.getName();
        if (why != null) { r.text = "can't sell " + name + ": " + why; return r; }
        SubmarketAPI s = sub(m, black);
        if (s.getPlugin().isIllegalOnSubmarket(id, SubmarketPlugin.TransferAction.PLAYER_SELL)) { r.text = name + " can't be sold on the " + s.getNameOneLine(); return r; }
        refresh(s);
        CargoAPI pc = Global.getSector().getPlayerFleet().getCargo();
        float qty = (float) Math.floor(Math.min(want, pc.getCommodityQuantity(id)));
        if (qty < 1f) { r.text = "no " + name + " to sell"; return r; }
        float tariff = s.getTariff();
        float net = m.getDemandPrice(id, qty, true) * (1f - tariff);
        pc.removeCommodity(id, qty);
        s.getCargo().addCommodity(id, qty);
        pc.getCredits().add(net);
        report(m, s, mode, id, qty, false, net, tariff);
        r.ok = true; r.qty = qty; r.credits = net;
        r.text = "sold " + n(qty) + " " + name + " on the " + s.getNameOneLine() + " for " + n(net) + " cr ("
                + n(net / qty) + "/unit after " + Math.round(tariff * 100f) + "% tariff)";
        return r;
    }

    /** Tells the game about the trade, exactly as its Trade screen does. */
    static void report(MarketAPI m, SubmarketAPI s, CoreUITradeMode mode, String id, float qty, boolean bought, float credits, float tariff) {
        PlayerMarketTransaction tx = new PlayerMarketTransaction(m, s, mode);
        if (bought) tx.getBought().addCommodity(id, qty); else tx.getSold().addCommodity(id, qty);
        tx.setCreditValue(bought ? -credits : credits);
        try {
            long now = Global.getSector().getClock().getTimestamp();
            tx.getLineItems().add(new TransactionLineItem(id, bought ? LineItemType.BOUGHT : LineItemType.SOLD,
                    CargoItemType.RESOURCES, s, qty, credits / qty, tariff, now));
        } catch (Throwable ignored) {}
        s.getPlugin().reportPlayerMarketTransaction(tx);
        Global.getSector().reportPlayerMarketTransaction(tx);
        tradedThisStop = true;
    }
}
