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
        // 1.0.9: market-based ceiling for supplies/fuel; buy the part under it, never above unless the fleet is in danger
        float cap = ceiling(id);
        if (cap > 0f && cost / qty > cap) {
            float hardCap = cap / Math.max(0.01f, Rules.f(("supplies".equals(id) ? "supply" : "fuel") + "_price_factor", 1.35f)) * Rules.f("emergency_price_factor", 2f);
            if (emergency(id) && cost / qty <= hardCap) {
                // only what keeps the fleet safe, never more than emergency_price_factor x the median
                float eq = (float) Math.floor(Math.min(qty, emergencyQty(id)));
                if (eq < 1f) { r.text = "won't buy " + name + ": over the ceiling and the emergency amount is already aboard"; return r; }
                qty = eq;
                cost = m.getSupplyPrice(id, qty, true) * (1f + tariff);
                EventLog.alert("trade", "EMERGENCY: buying " + n(qty) + " " + name + " at " + n(cost / qty) + "/unit, over the market ceiling of " + n(cap) + " (fleet low)");
            } else {
                for (int i = 0; i < 60 && qty > 1f && cost / qty > cap; i++) {
                    qty = (float) Math.floor(qty * 0.9f);
                    cost = m.getSupplyPrice(id, qty, true) * (1f + tariff);
                }
                if (qty < 1f || cost / qty > cap) {
                    r.text = "won't buy " + name + " on the " + s.getNameOneLine() + ": " + String.format("%.1f", cost / Math.max(1f, qty)) + "/unit is over the market ceiling of " + String.format("%.1f", cap) + " (median price x factor)";
                    return r;
                }
            }
        }
        s.getCargo().removeCommodity(id, qty);
        pc.addCommodity(id, qty);
        pc.getCredits().subtract(cost);
        report(m, s, mode, id, qty, true, cost, tariff);
        r.ok = true; r.qty = qty; r.credits = -cost;
        r.text = "bought " + n(qty) + " " + name + " on the " + s.getNameOneLine() + " for " + n(cost) + " cr ("
                + n(cost / qty) + "/unit incl. " + Math.round(tariff * 100f) + "% tariff)" + (qty < want ? " - limited by stock, room or credits" : "");
        return r;
    }

    /** 1.0.9: units of a good the player wants kept aboard (captain_rules keep = metals:50, heavy machinery:50). */
    static int keepFloor(String id, String name) {
        return Guard.keepFloor(Rules.s("keep", "metals:50, heavy machinery:50"), id, name);
    }

    /** True when a weapon/wing/good is kept outright (a bare name on the keep list). */
    static boolean kept(String id, String name) { return keepFloor(id, name) == Integer.MAX_VALUE; }

    /**
     * 1.0.9: the most one unit of supplies/fuel may cost (incl. tariff): the median price across known friendly markets
     * x supply_price_factor / fuel_price_factor, so it follows the market. A fixed max_*_price > 0 overrides. -1 = no limit.
     */
    /** 1.0.9: a one-buy price cap the player approved ("buy supplies 200 at 146"); -1 when none. */
    static volatile float playerCap = -1f;

    static float ceiling(String id) {
        boolean sup = "supplies".equals(id), fuel = "fuel".equals(id);
        if (!sup && !fuel) return -1f;
        if (playerCap > 0f) return playerCap;
        java.util.List<Float> seen = new java.util.ArrayList<Float>();
        try {
            for (MarketAPI x : Global.getSector().getEconomy().getMarketsCopy()) {
                if (x.isHidden() || x.getPrimaryEntity() == null || x.getFaction().isHostileTo("player")) continue;
                if (x.getCommodityData(id) == null) continue;
                // 1.0.9 (audit H4): the cheapest way to buy one unit there, tariff included, so it compares like with like
                float base = x.getSupplyPrice(id, 1f, true);
                float best = Float.MAX_VALUE;
                com.fs.starfarer.api.campaign.econ.SubmarketAPI bm = x.getSubmarket("black_market"), om = x.getSubmarket("open_market");
                if (bm != null) best = Math.min(best, base * (1f + bm.getTariff()));
                if (om != null) best = Math.min(best, base * (1f + om.getTariff()));
                if (best < Float.MAX_VALUE) seen.add(best);
            }
        } catch (Throwable ignored) {}
        float factor = Rules.f(sup ? "supply_price_factor" : "fuel_price_factor", 1.35f);
        float override = Rules.f(sup ? "max_supply_price" : "max_fuel_price", 0f);
        return Guard.ceiling(seen, factor, sup ? 150f : 50f, override, 5);
    }

    /** 1.0.9: units to buy in an emergency: supplies up to gov_min_supply_days + 5 days, fuel up to emergency_fuel_ly + 5 ly. */
    static float emergencyQty(String id) {
        try {
            com.fs.starfarer.api.campaign.CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
            if ("supplies".equals(id)) return Math.max(0f, Governor.maintPerDay(pf) * (Rules.f("gov_min_supply_days", 15f) + 5f) - pf.getCargo().getSupplies());
            if ("fuel".equals(id)) return Math.max(0f, pf.getLogistics().getFuelCostPerLightYear() * (Rules.f("emergency_fuel_ly", 10f) + 5f) - pf.getCargo().getFuel());
        } catch (Throwable ignored) {}
        return 0f;
    }

    /** 1.0.9: buying above the ceiling is still allowed when the fleet would otherwise be in danger. */
    static boolean emergency(String id) {
        try {
            com.fs.starfarer.api.campaign.CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
            if ("supplies".equals(id)) return GovMath.supplyDays(pf.getCargo().getSupplies(), Governor.maintPerDay(pf)) < Rules.f("gov_min_supply_days", 15f);
            if ("fuel".equals(id)) return pf.getCargo().getFuel() < pf.getLogistics().getFuelCostPerLightYear() * Rules.f("emergency_fuel_ly", 10f);
        } catch (Throwable ignored) {}
        return false;
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
        int floor = keepFloor(id, name);
        float qty = Guard.sellable(pc.getCommodityQuantity(id), want, floor);
        if (qty < 1f) { r.text = floor > 0 ? "won't sell " + name + ": keeping " + (floor == Integer.MAX_VALUE ? "all of it" : "at least " + floor) + " (captain_rules keep)" : "no " + name + " to sell"; return r; }
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
