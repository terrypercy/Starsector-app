package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoAPI.CargoItemType;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction.LineItemType;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction.ShipSaleInfo;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction.TransactionLineItem;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.plugins.AutofitPlugin;
import com.fs.starfarer.api.plugins.impl.CoreAutofitPlugin;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shipyard (1.2.0): ships, weapons and hullmod specs at the docked market, bought the way the game's own
 * screens buy them (credits at the submarket's tariff, the sale reported to the submarket and the sector),
 * fitting with the game's own autofit (CoreAutofitPlugin, the refit screen's Autofit button) that takes
 * weapons from your cargo first and buys the rest from the market, and officer assignment.
 *
 * Inbox commands: fleet | ships | weapons [filter] | buyship <n> | buyspec <hullmod> | autofit <ship> [variant] |
 * assign <officer> <ship> | autoassign
 */
public final class Shipyard {

    private Shipyard() {}

    private static final List<String> lastShips = new ArrayList<String>(); // member ids in the order "ships" printed them

    static String n(float v) { return Misc.getWithDGS(Math.round(v)); }

    // ------------------------------------------------------------------ helpers

    private static CampaignFleetAPI pf() { return Global.getSector().getPlayerFleet(); }

    /** The docked market and its trade mode, or an error string in [0]. */
    private static MarketAPI docked(String[] err, CoreUITradeMode[] mode) {
        InteractionDialogAPI d = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
        MarketAPI m = GameReader.dockedMarket();
        if (d == null || m == null) { err[0] = "not docked at a market (dock first)"; return null; }
        mode[0] = Trader.tradeMode(d);
        if (mode[0] == CoreUITradeMode.NONE) { err[0] = m.getName() + " won't trade with you right now"; return null; }
        return m;
    }

    /** Open market (if the trade mode allows it) and black market. Military markets need the right standing; skipped. */
    private static List<SubmarketAPI> subs(MarketAPI m, CoreUITradeMode mode) {
        List<SubmarketAPI> out = new ArrayList<SubmarketAPI>();
        SubmarketAPI open = m.getSubmarket(Submarkets.SUBMARKET_OPEN);
        SubmarketAPI black = m.getSubmarket(Submarkets.SUBMARKET_BLACK);
        if (open != null && mode == CoreUITradeMode.OPEN) out.add(open);
        if (black != null) out.add(black);
        for (SubmarketAPI s : out) {
            try { s.getPlugin().updateCargoPrePlayerInteraction(); } catch (Throwable ignored) {}
        }
        return out;
    }

    private static String subName(SubmarketAPI s) {
        return Submarkets.SUBMARKET_BLACK.equals(s.getSpecId()) ? "black" : "open";
    }

    /** What the game charges for a ship in this submarket. */
    static float shipPrice(FleetMemberAPI m, SubmarketAPI s) {
        return m.getBaseBuyValue() * (1f + s.getTariff());
    }

    static float stackPrice(CargoStackAPI st, SubmarketAPI s) {
        return st.getBaseValuePerUnit() * (1f + s.getTariff());
    }

    /** Civilian by hull design (FleetMemberAPI.isCivilian() is also true for any unarmed hull, e.g. ships for sale). */
    static boolean civilian(FleetMemberAPI m) {
        try { return m.getHullSpec().getHints().contains(com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints.CIVILIAN); }
        catch (Throwable t) { return m.isCivilian(); }
    }

    static int dmods(FleetMemberAPI m) {
        int c = 0;
        for (String id : m.getVariant().getHullMods()) {
            HullModSpecAPI sp = Global.getSettings().getHullModSpec(id);
            if (sp != null && sp.hasTag(Tags.HULLMOD_DMOD)) c++;
        }
        return c;
    }

    private static void report(MarketAPI m, SubmarketAPI s, CoreUITradeMode mode, PlayerMarketTransaction tx) {
        try {
            s.getPlugin().reportPlayerMarketTransaction(tx);
            Global.getSector().reportPlayerMarketTransaction(tx);
        } catch (Throwable t) {
            Global.getLogger(Shipyard.class).warn("Trade Runner: could not report a shipyard transaction", t);
        }
    }

    private static int score(String q, String a) {
        if (a == null) return 0;
        String x = q.toLowerCase(Locale.ROOT).trim(), y = a.toLowerCase(Locale.ROOT);
        if (y.equals(x)) return 100;
        if (y.startsWith(x)) return 80;
        if (y.contains(x)) return 60;
        int hit = 0;
        for (String w : x.split("\\s+")) if (w.length() > 1 && y.contains(w)) hit++;
        return hit * 15;
    }

    static FleetMemberAPI findMember(String q) {
        FleetMemberAPI best = null;
        int bs = 0;
        for (FleetMemberAPI m : pf().getFleetData().getMembersListCopy()) {
            int s = Math.max(score(q, m.getShipName()), Math.max(score(q, m.getHullSpec().getHullName()), score(q, m.getHullId())));
            if (s > bs) { bs = s; best = m; }
        }
        return bs >= 30 ? best : null;
    }

    // ------------------------------------------------------------------ fleet

    static String fleet() {
        StringBuilder b = new StringBuilder("fleet (" + pf().getFleetData().getNumMembers() + " ships), credits "
                + n(pf().getCargo().getCredits().get()) + ", crew " + n(pf().getCargo().getCrew()) + "\n");
        MutableCharacterStatsAPI st = Global.getSector().getPlayerStats();
        for (FleetMemberAPI m : pf().getFleetData().getMembersListCopy()) {
            ShipVariantAPI v = m.getVariant();
            PersonAPI c = m.getCaptain();
            String cap = c == null || c.isDefault() ? "-" : (c.isPlayer() ? "you" : c.getNameString());
            b.append(m.getShipName()).append(" | ").append(m.getHullSpec().getHullName()).append(" | ")
             .append(m.getHullSpec().getHullSize()).append(" | DP ").append(Math.round(m.getDeploymentPointsCost()))
             .append(" | CR ").append(Math.round(m.getRepairTracker().getCR() * 100f)).append("% | hull ")
             .append(Math.round(m.getStatus().getHullFraction() * 100f)).append("% | OP ").append(v.computeOPCost(st))
             .append("/").append(m.getHullSpec().getOrdnancePoints(st)).append(" | weapons ").append(v.getFittedWeaponSlots().size())
             .append(" | crew ").append(Math.round(m.getMinCrew())).append("-").append(Math.round(m.getMaxCrew()))
             .append(" | captain ").append(cap).append(civilian(m) ? " | civilian" : "").append("\n");
        }
        List<OfficerDataAPI> offs = pf().getFleetData().getOfficersCopy();
        b.append("officers: ").append(offs.size());
        for (OfficerDataAPI o : offs) {
            FleetMemberAPI on = pf().getFleetData().getMemberWithCaptain(o.getPerson());
            b.append("\n  ").append(o.getPerson().getNameString()).append(" (level ").append(o.getPerson().getStats().getLevel())
             .append(", ").append(o.getPerson().getPersonalityAPI().getDisplayName()).append(") on ").append(on == null ? "no ship" : on.getShipName());
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ ships

    static String ships() {
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        MarketAPI m = docked(err, mode);
        if (m == null) return err[0];
        lastShips.clear();
        StringBuilder b = new StringBuilder("ships for sale at " + m.getName() + " (credits " + n(pf().getCargo().getCredits().get()) + ")\n");
        int i = 0;
        for (SubmarketAPI s : subs(m, mode[0])) {
            CargoAPI c = s.getCargo();
            if (c == null || c.getMothballedShips() == null) continue;
            for (FleetMemberAPI f : c.getMothballedShips().getMembersListCopy()) {
                boolean illegal = s.getPlugin().isIllegalOnSubmarket(f, SubmarketPlugin.TransferAction.PLAYER_BUY);
                lastShips.add(f.getId());
                i++;
                b.append(i).append(". ").append(f.getHullSpec().getHullName()).append(" \"").append(f.getShipName()).append("\" | ")
                 .append(f.getHullSpec().getHullSize()).append(" | ").append(subName(s)).append(" | ").append(n(shipPrice(f, s))).append(" cr")
                 .append(" | DP ").append(Math.round(f.getDeploymentPointsCost())).append(" | d-mods ").append(dmods(f))
                 .append(" | OP ").append(f.getHullSpec().getOrdnancePoints(null)).append(civilian(f) ? " | civilian" : "")
                 .append(illegal ? " | can't buy here" : "").append("\n");
            }
        }
        if (i == 0) b.append("(none)\n");
        return b.toString();
    }

    static String buyShip(String arg) {
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        MarketAPI m = docked(err, mode);
        if (m == null) return err[0];
        String wantId = null;
        try {
            int k = Integer.parseInt(arg.trim());
            if (k < 1 || k > lastShips.size()) return "no ship " + k + " in the last 'ships' list (run 'ships' first)";
            wantId = lastShips.get(k - 1);
        } catch (NumberFormatException e) { /* a name */ }
        FleetMemberAPI pick = null;
        SubmarketAPI from = null;
        int best = 0;
        for (SubmarketAPI s : subs(m, mode[0])) {
            if (s.getCargo() == null || s.getCargo().getMothballedShips() == null) continue;
            for (FleetMemberAPI f : s.getCargo().getMothballedShips().getMembersListCopy()) {
                int sc = wantId != null ? (wantId.equals(f.getId()) ? 100 : 0)
                        : Math.max(score(arg, f.getShipName()), score(arg, f.getHullSpec().getHullName()));
                if (sc > best) { best = sc; pick = f; from = s; }
            }
        }
        if (pick == null || best < 30) return "no ship matching '" + arg + "' for sale here (run 'ships')";
        if (from.getPlugin().isIllegalOnSubmarket(pick, SubmarketPlugin.TransferAction.PLAYER_BUY))
            return pick.getHullSpec().getHullName() + " can't be bought on the " + from.getNameOneLine() + ": "
                    + from.getPlugin().getIllegalTransferText(pick, SubmarketPlugin.TransferAction.PLAYER_BUY);
        float price = shipPrice(pick, from);
        float credits = pf().getCargo().getCredits().get();
        float reserve = Rules.f("reserve_credits", 5000f);
        if (price > credits - reserve) return "can't afford " + pick.getHullSpec().getHullName() + ": " + n(price) + " cr, you have "
                + n(credits) + " and keep " + n(reserve) + " in reserve";

        from.getCargo().getMothballedShips().removeFleetMember(pick);
        pf().getFleetData().addFleetMember(pick);
        // market stock is stored mothballed; the vanilla buy screen re-activates it, so do the same
        pick.getRepairTracker().setMothballed(false);
        pick.setCaptain(null);
        pf().getCargo().getCredits().subtract(price);
        PlayerMarketTransaction tx = new PlayerMarketTransaction(m, from, mode[0]);
        tx.getShipsBought().add(new ShipSaleInfo(pick, price));
        tx.setCreditValue(-price);
        report(m, from, mode[0], tx);
        try { pick.updateStats(); } catch (Throwable ignored) {}
        pf().getFleetData().syncIfNeeded();
        lastShips.remove(pick.getId());
        return "bought " + pick.getHullSpec().getHullName() + " \"" + pick.getShipName() + "\" on the " + from.getNameOneLine() + " for "
                + n(price) + " cr (" + Math.round(from.getTariff() * 100f) + "% tariff); CR " + Math.round(pick.getRepairTracker().getCR() * 100f)
                + "%, needs " + Math.round(pick.getMinCrew()) + " crew; credits " + n(pf().getCargo().getCredits().get());
    }

    // ------------------------------------------------------------------ weapons and specs

    static String weapons(String filter) {
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        MarketAPI m = docked(err, mode);
        if (m == null) return err[0];
        StringBuilder b = new StringBuilder("weapons, wings and hullmod specs at " + m.getName() + "\n");
        for (SubmarketAPI s : subs(m, mode[0])) {
            for (CargoStackAPI st : s.getCargo().getStacksCopy()) {
                String what, id;
                if (st.isWeaponStack()) {
                    WeaponSpecAPI w = st.getWeaponSpecIfWeapon();
                    id = w.getWeaponId();
                    what = w.getSize() + " " + w.getMountType() + ", " + Math.round(w.getOrdnancePointCost(null)) + " OP";
                } else if (st.isFighterWingStack()) {
                    FighterWingSpecAPI w = st.getFighterWingSpecIfWing();
                    id = w.getId();
                    what = "wing, " + Math.round(w.getOpCost(null)) + " OP";
                } else if (st.isSpecialStack() && st.getSpecialDataIfSpecial() != null && "modspec".equals(st.getSpecialDataIfSpecial().getId())) {
                    id = st.getSpecialDataIfSpecial().getData();
                    boolean known = Global.getSector().getPlayerFaction().knowsHullMod(id);
                    what = "hullmod spec" + (known ? " (already known)" : "");
                } else continue;
                String line = st.getDisplayName() + " [" + id + "] | " + what + " | " + subName(s) + " x" + Math.round(st.getSize())
                        + " | " + n(stackPrice(st, s)) + " cr each";
                if (filter == null || filter.isEmpty() || line.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) b.append(line).append("\n");
            }
        }
        return b.toString();
    }

    /** Buys weapons or fighter wings into your cargo: buyweapon <id or name> [qty]. */
    static String buyWeapon(String args) {
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        MarketAPI m = docked(err, mode);
        if (m == null) return err[0];
        String a = args.trim();
        int qty = 1;
        String[] p = a.split("\\s+");
        try { qty = Integer.parseInt(p[p.length - 1]); a = a.substring(0, a.lastIndexOf(' ')).trim(); } catch (Exception ignored) {}
        CargoStackAPI pick = null;
        SubmarketAPI from = null;
        int best = 0;
        float bestPrice = Float.MAX_VALUE;
        for (SubmarketAPI s : subs(m, mode[0])) {
            for (CargoStackAPI st : s.getCargo().getStacksCopy()) {
                String id, name;
                if (st.isWeaponStack()) { id = st.getWeaponSpecIfWeapon().getWeaponId(); name = st.getWeaponSpecIfWeapon().getWeaponName(); }
                else if (st.isFighterWingStack()) { id = st.getFighterWingSpecIfWing().getId(); name = st.getFighterWingSpecIfWing().getWingName(); }
                else continue;
                if (s.getPlugin().isIllegalOnSubmarket(st, SubmarketPlugin.TransferAction.PLAYER_BUY)) continue;
                int sc = Math.max(score(a, id), score(a, name));
                float pr = stackPrice(st, s);
                if (sc > best || (sc == best && pr < bestPrice)) { best = sc; pick = st; from = s; bestPrice = pr; }
            }
        }
        if (pick == null || best < 30) return "no weapon or wing matching '" + a + "' here (see 'weapons')";
        boolean wing = pick.isFighterWingStack();
        String id = wing ? pick.getFighterWingSpecIfWing().getId() : pick.getWeaponSpecIfWeapon().getWeaponId();
        int have = (int) Math.floor(pick.getSize());
        int q = Math.min(qty, have);
        float unit = stackPrice(pick, from), cost = unit * q;
        if (cost > pf().getCargo().getCredits().get() - Rules.f("reserve_credits", 5000f)) return "can't afford " + q + " x " + pick.getDisplayName() + " (" + n(cost) + " cr)";
        if (wing) { from.getCargo().removeFighters(id, q); pf().getCargo().addFighters(id, q); }
        else { from.getCargo().removeWeapons(id, q); pf().getCargo().addWeapons(id, q); }
        pf().getCargo().getCredits().subtract(cost);
        PlayerMarketTransaction tx = new PlayerMarketTransaction(m, from, mode[0]);
        if (wing) tx.getBought().addFighters(id, q); else tx.getBought().addWeapons(id, q);
        tx.setCreditValue(-cost);
        report(m, from, mode[0], tx);
        return "bought " + q + " x " + pick.getDisplayName() + " on the " + from.getNameOneLine() + " for " + n(cost) + " cr (" + n(unit) + " each)"
                + (q < qty ? " - only " + have + " in stock" : "");
    }

    static String buySpec(String q) {
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        MarketAPI m = docked(err, mode);
        if (m == null) return err[0];
        CargoStackAPI pick = null;
        SubmarketAPI from = null;
        int best = 0;
        for (SubmarketAPI s : subs(m, mode[0])) {
            for (CargoStackAPI st : s.getCargo().getStacksCopy()) {
                if (!st.isSpecialStack() || st.getSpecialDataIfSpecial() == null || !"modspec".equals(st.getSpecialDataIfSpecial().getId())) continue;
                String id = st.getSpecialDataIfSpecial().getData();
                HullModSpecAPI hs = Global.getSettings().getHullModSpec(id);
                int sc = Math.max(score(q, id), hs == null ? 0 : score(q, hs.getDisplayName()));
                if (sc > best) { best = sc; pick = st; from = s; }
            }
        }
        if (pick == null || best < 30) return "no hullmod spec matching '" + q + "' here (see 'weapons spec')";
        String id = pick.getSpecialDataIfSpecial().getData();
        HullModSpecAPI hs = Global.getSettings().getHullModSpec(id);
        String name = hs == null ? id : hs.getDisplayName();
        if (Global.getSector().getPlayerFaction().knowsHullMod(id)) return name + " is already known";
        float price = stackPrice(pick, from);
        if (price > pf().getCargo().getCredits().get() - Rules.f("reserve_credits", 5000f)) return "can't afford the " + name + " spec (" + n(price) + " cr)";
        SpecialItemData data = new SpecialItemData("modspec", id);
        from.getCargo().removeItems(CargoItemType.SPECIAL, data, 1f);
        pf().getCargo().getCredits().subtract(price);
        PlayerMarketTransaction tx = new PlayerMarketTransaction(m, from, mode[0]);
        tx.getBought().addItems(CargoItemType.SPECIAL, data, 1f);
        tx.setCreditValue(-price);
        try {
            tx.getLineItems().add(new TransactionLineItem(id, LineItemType.BOUGHT, CargoItemType.SPECIAL, from, 1f, price, from.getTariff(),
                    Global.getSector().getClock().getTimestamp()));
        } catch (Throwable ignored) {}
        report(m, from, mode[0], tx);
        // learning a spec uses it up, as the cargo screen's "learn" does
        Global.getSector().getPlayerFaction().addKnownHullMod(id);
        return "bought and learned the " + name + " hullmod spec on the " + from.getNameOneLine() + " for " + n(price) + " cr";
    }

    // ------------------------------------------------------------------ autofit

    /** One weapon or wing source for the autofit: player cargo (free) or a submarket (bought on use). */
    static final class Src {
        final CargoAPI cargo;
        final SubmarketAPI sub;
        final float price;
        int qty;
        Src(CargoAPI c, SubmarketAPI s, float p, int q) { cargo = c; sub = s; price = p; qty = q; }
    }

    static final class AW implements AutofitPlugin.AvailableWeapon {
        final WeaponSpecAPI spec;
        final Src src;
        AW(WeaponSpecAPI w, Src s) { spec = w; src = s; }
        public String getId() { return spec.getWeaponId(); }
        public WeaponSpecAPI getSpec() { return spec; }
        public float getPrice() { return src.price; }
        public CargoAPI getSource() { return src.cargo; }
        public SubmarketAPI getSubmarket() { return src.sub; }
        public int getQuantity() { return src.qty; }
        public void setQuantity(int q) { src.qty = q; }
        public float getOPCost(MutableCharacterStatsAPI c, MutableShipStatsAPI s) { return spec.getOrdnancePointCost(c, s); }
    }

    static final class AF implements AutofitPlugin.AvailableFighter {
        final FighterWingSpecAPI spec;
        final Src src;
        AF(FighterWingSpecAPI w, Src s) { spec = w; src = s; }
        public String getId() { return spec.getId(); }
        public FighterWingSpecAPI getWingSpec() { return spec; }
        public float getPrice() { return src.price; }
        public CargoAPI getSource() { return src.cargo; }
        public SubmarketAPI getSubmarket() { return src.sub; }
        public int getQuantity() { return src.qty; }
        public void setQuantity(int q) { src.qty = q; }
    }

    /** The refit screen's side of the autofit: where weapons come from, what fitting one costs. */
    static final class Fit implements AutofitPlugin.AutofitPluginDelegate {
        final FleetMemberAPI member;
        final MarketAPI market;
        final CoreUITradeMode mode;
        final List<AutofitPlugin.AvailableWeapon> weapons = new ArrayList<AutofitPlugin.AvailableWeapon>();
        final List<AutofitPlugin.AvailableFighter> fighters = new ArrayList<AutofitPlugin.AvailableFighter>();
        final Map<SubmarketAPI, PlayerMarketTransaction> txs = new LinkedHashMap<SubmarketAPI, PlayerMarketTransaction>();
        final Map<String, Integer> bought = new HashMap<String, Integer>();
        float spent = 0f, budget;

        Fit(FleetMemberAPI m, MarketAPI mk, CoreUITradeMode md, float budget) {
            member = m; market = mk; mode = md; this.budget = budget;
            add(pf().getCargo(), null);
            if (mk != null) for (SubmarketAPI s : subs(mk, md)) add(s.getCargo(), s);
        }

        private void add(CargoAPI c, SubmarketAPI s) {
            if (c == null) return;
            for (CargoStackAPI st : c.getStacksCopy()) {
                int q = (int) Math.floor(st.getSize());
                if (q < 1) continue;
                if (s != null && s.getPlugin().isIllegalOnSubmarket(st, SubmarketPlugin.TransferAction.PLAYER_BUY)) continue;
                float price = s == null ? 0f : stackPrice(st, s);
                if (st.isWeaponStack()) weapons.add(new AW(st.getWeaponSpecIfWeapon(), new Src(c, s, price, q)));
                else if (st.isFighterWingStack()) fighters.add(new AF(st.getFighterWingSpecIfWing(), new Src(c, s, price, q)));
            }
        }

        private boolean pay(Src src, String id, CargoItemType type) {
            if (src.sub == null) return true;
            if (spent + src.price > budget) return false;
            pf().getCargo().getCredits().subtract(src.price);
            spent += src.price;
            PlayerMarketTransaction tx = txs.get(src.sub);
            if (tx == null) { tx = new PlayerMarketTransaction(market, src.sub, mode); txs.put(src.sub, tx); }
            if (type == CargoItemType.WEAPONS) tx.getBought().addWeapons(id, 1); else tx.getBought().addFighters(id, 1);
            tx.setCreditValue(tx.getCreditValue() - src.price);
            Integer k = bought.get(id);
            bought.put(id, k == null ? 1 : k + 1);
            return true;
        }

        void reportAll() { for (Map.Entry<SubmarketAPI, PlayerMarketTransaction> e : txs.entrySet()) report(market, e.getKey(), mode, e.getValue()); }

        public void fitWeaponInSlot(WeaponSlotAPI slot, AutofitPlugin.AvailableWeapon w, ShipVariantAPI v) {
            AW a = (AW) w;
            if (a.src.qty < 1 || !pay(a.src, a.getId(), CargoItemType.WEAPONS)) return;
            clearWeaponSlot(slot, v);
            a.src.cargo.removeWeapons(a.getId(), 1);
            a.src.qty--;
            v.addWeapon(slot.getId(), a.getId());
        }

        public void clearWeaponSlot(WeaponSlotAPI slot, ShipVariantAPI v) {
            String old = v.getWeaponId(slot.getId());
            if (old == null) return;
            if (slot.isBuiltIn() || slot.isDecorative() || slot.isSystemSlot()) return;
            v.clearSlot(slot.getId());
            pf().getCargo().addWeapons(old, 1);
        }

        public void fitFighterInSlot(int index, AutofitPlugin.AvailableFighter f, ShipVariantAPI v) {
            AF a = (AF) f;
            if (a.src.qty < 1 || !pay(a.src, a.getId(), CargoItemType.FIGHTER_CHIP)) return;
            clearFighterSlot(index, v);
            a.src.cargo.removeFighters(a.getId(), 1);
            a.src.qty--;
            v.setWingId(index, a.getId());
        }

        public void clearFighterSlot(int index, ShipVariantAPI v) {
            String old = v.getWingId(index);
            if (old == null) return;
            if (v.getHullSpec().getBuiltInWings().size() > index) return;
            v.setWingId(index, null);
            pf().getCargo().addFighters(old, 1);
        }

        public List<AutofitPlugin.AvailableWeapon> getAvailableWeapons() { return weapons; }
        public List<AutofitPlugin.AvailableFighter> getAvailableFighters() { return fighters; }
        public boolean isPriority(WeaponSpecAPI w) { return false; }
        public boolean isPriority(FighterWingSpecAPI w) { return false; }
        public List<String> getAvailableHullmods() { return new ArrayList<String>(Global.getSector().getPlayerFaction().getKnownHullMods()); }
        public void syncUIWithVariant(ShipVariantAPI v) {}
        public ShipAPI getShip() { return null; }
        public FactionAPI getFaction() { return Global.getSector().getPlayerFaction(); }
        public boolean isAllowSlightRandomization() { return false; }
        public boolean isPlayerCampaignRefit() { return true; }
        public boolean canAddRemoveHullmodInPlayerCampaignRefit(String id) {
            HullModSpecAPI sp = Global.getSettings().getHullModSpec(id);
            if (sp == null || sp.hasTag(Tags.HULLMOD_DMOD) || sp.isHidden() || sp.isHiddenEverywhere()) return false;
            if (member.getVariant().getHullSpec().isBuiltInMod(id)) return false;
            if (member.getVariant().getSMods().contains(id)) return false;
            return true;
        }
        public MarketAPI getMarket() { return market; }
        public FleetMemberAPI getFleetMember() { return member; }
    }

    /**
     * Fits a ship with the game's autofit toward the best-rated of its hull's goal variants (or the one named),
     * using your cargo first, then the market. Old weapons go to your cargo.
     */
    static String autofit(String args) {
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        MarketAPI m = docked(err, mode);
        if (m == null) return err[0];
        String[] parts = args.trim().split("\\s+variant\\s+", 2);
        FleetMemberAPI member = findMember(parts[0]);
        if (member == null) return "no ship in your fleet matching '" + parts[0] + "' (see 'fleet')";
        if (civilian(member) && !Rules.b("autofit_civilian", false)) return member.getShipName() + " is civilian; not fitting it";

        List<ShipVariantAPI> goals = new ArrayList<ShipVariantAPI>();
        try { goals.addAll(Global.getSector().getAutofitVariants().getTargetVariants(member.getHullId())); } catch (Throwable ignored) {}
        if (goals.isEmpty()) return "the game has no autofit goal variants for " + member.getHullSpec().getHullName();

        CoreAutofitPlugin plugin = new CoreAutofitPlugin(Global.getSector().getPlayerPerson());
        plugin.setRandom(new java.util.Random());
        plugin.setChecked(CoreAutofitPlugin.BUY_FROM_MARKET, mode[0] == CoreUITradeMode.OPEN);
        plugin.setChecked(CoreAutofitPlugin.BUY_FROM_BLACK_MARKET, true);
        plugin.setChecked(CoreAutofitPlugin.USE_FROM_CARGO, true);
        plugin.setChecked(CoreAutofitPlugin.USE_FROM_STORAGE, false);
        plugin.setChecked(CoreAutofitPlugin.UPGRADE, true);
        plugin.setChecked(CoreAutofitPlugin.STRIP, true);
        plugin.setChecked(CoreAutofitPlugin.RANDOMIZE, false);

        float budget = Math.max(0f, pf().getCargo().getCredits().get() - Rules.f("reserve_credits", 5000f));
        ShipVariantAPI goal = null;
        if (parts.length > 1) {
            for (ShipVariantAPI g : goals) if (score(parts[1], g.getHullVariantId()) >= 60 || score(parts[1], g.getDisplayName()) >= 60) goal = g;
            if (goal == null) {
                StringBuilder b = new StringBuilder("no goal variant '" + parts[1] + "'; options:");
                for (ShipVariantAPI g : goals) b.append(" ").append(g.getHullVariantId()).append(" (").append(g.getDisplayName()).append(")");
                return b.toString();
            }
        } else {
            float bestR = -1f;
            for (ShipVariantAPI g : goals) {
                float r = plugin.getRating(member.getVariant(), g, new Fit(member, m, mode[0], budget));
                if (r > bestR) { bestR = r; goal = g; }
            }
        }

        ShipVariantAPI v = member.getVariant().clone();
        v.setSource(VariantSource.REFIT);
        if (v.isStockVariant()) v.setHullVariantId(Misc.genUID());
        Fit fit = new Fit(member, m, mode[0], budget);
        plugin.doFit(v, goal, 0, fit);
        member.setVariant(v, false, true);
        fit.reportAll();
        try { member.updateStats(); } catch (Throwable ignored) {}
        pf().getFleetData().syncIfNeeded();

        MutableCharacterStatsAPI st = Global.getSector().getPlayerStats();
        StringBuilder b = new StringBuilder("fitted " + member.getShipName() + " (" + member.getHullSpec().getHullName() + ") toward "
                + goal.getDisplayName() + " [" + goal.getHullVariantId() + "]: OP " + v.computeOPCost(st) + "/"
                + member.getHullSpec().getOrdnancePoints(st) + ", " + v.getFittedWeaponSlots().size() + " weapons, "
                + v.getNumFluxVents() + " vents, " + v.getNumFluxCapacitors() + " caps; bought for " + n(fit.spent) + " cr");
        if (!fit.bought.isEmpty()) b.append("\n  bought: ").append(fit.bought);
        b.append("\n  hullmods: ").append(v.getNonBuiltInHullmods());
        StringBuilder w = new StringBuilder();
        for (String slot : v.getFittedWeaponSlots()) {
            String id = v.getWeaponId(slot);
            if (id == null) continue;
            WeaponSpecAPI ws = Global.getSettings().getWeaponSpec(id);
            w.append(w.length() == 0 ? "" : ", ").append(ws == null ? id : ws.getWeaponName());
        }
        b.append("\n  weapons: ").append(w);
        return b.toString();
    }

    /**
     * "equip <ship> with <weapon> [xN]": fits weapons from cargo into the ship's empty compatible slots
     * (largest slot first), taking vents then capacitors off if the OP budget needs it. Nothing is bought.
     */
    static String equip(String args) {
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        MarketAPI m = docked(err, mode);
        if (m == null) return err[0];
        String[] parts = args.trim().split("\\s+with\\s+", 2);
        if (parts.length < 2) return "usage: equip <ship> with <weapon> [xN]";
        FleetMemberAPI member = findMember(parts[0]);
        if (member == null) return "no ship in your fleet matching '" + parts[0] + "' (see 'fleet')";
        String wq = parts[1].trim();
        int want = 1;
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("^(.*?)\\s+x\\s*(\\d+)$").matcher(wq);
        if (mm.find()) { wq = mm.group(1).trim(); want = Integer.parseInt(mm.group(2)); }

        CargoAPI cargo = pf().getCargo();
        String wid = null; int best = 0;
        for (CargoStackAPI st : cargo.getStacksCopy()) {
            if (!st.isWeaponStack()) continue;
            String id = (String) st.getData();
            WeaponSpecAPI ws = Global.getSettings().getWeaponSpec(id);
            int sc = Math.max(score(wq, id), ws == null ? 0 : score(wq, ws.getWeaponName()));
            if (sc > best) { best = sc; wid = id; }
        }
        if (wid == null || best < 50) return "no weapon in cargo matching '" + wq + "'";
        WeaponSpecAPI spec = Global.getSettings().getWeaponSpec(wid);
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        ShipVariantAPI v = member.getVariant().clone();
        v.setSource(VariantSource.REFIT);
        if (v.isStockVariant()) v.setHullVariantId(Misc.genUID());
        int maxOP = member.getHullSpec().getOrdnancePoints(stats);

        List<WeaponSlotAPI> slots = new ArrayList<WeaponSlotAPI>();
        for (WeaponSlotAPI sl : v.getHullSpec().getAllWeaponSlotsCopy()) {
            if (!sl.isWeaponSlot() || sl.isBuiltIn() || sl.isDecorative() || sl.isSystemSlot() || sl.isStationModule()) continue;
            if (v.getWeaponId(sl.getId()) != null) continue;
            if (!sl.weaponFits(spec)) continue;
            slots.add(sl);
        }
        java.util.Collections.sort(slots, new java.util.Comparator<WeaponSlotAPI>() {
            public int compare(WeaponSlotAPI a, WeaponSlotAPI b) { return b.getSlotSize().ordinal() - a.getSlotSize().ordinal(); }
        });
        int fitted = 0;
        StringBuilder note = new StringBuilder();
        for (WeaponSlotAPI sl : slots) {
            if (fitted >= want) break;
            if (cargo.getNumWeapons(wid) < 1) { note.append("; ran out of ").append(spec.getWeaponName()).append(" in cargo"); break; }
            int need = v.computeOPCost(stats) + (int) spec.getOrdnancePointCost(stats) - maxOP;
            int vents = v.getNumFluxVents(), caps = v.getNumFluxCapacitors();
            if (need > vents + caps) { note.append("; not enough OP for another ").append(spec.getWeaponName()); break; }
            int dv = Math.min(need > 0 ? need : 0, vents); v.setNumFluxVents(vents - dv); need -= dv;
            if (need > 0) v.setNumFluxCapacitors(caps - need);
            v.addWeapon(sl.getId(), wid);
            cargo.removeWeapons(wid, 1);
            fitted++;
            note.append("; ").append(sl.getId()).append(" (").append(sl.getSlotSize()).append(" ").append(sl.getWeaponType()).append(")");
        }
        if (fitted == 0) return "no empty slot on " + member.getShipName() + " fits " + spec.getWeaponName()
                + (slots.isEmpty() ? " (" + spec.getSize() + " " + spec.getMountType() + ")" : note.toString());
        v.autoGenerateWeaponGroups();
        member.setVariant(v, false, true);
        try { member.updateStats(); } catch (Throwable ignored) {}
        pf().getFleetData().syncIfNeeded();
        return "fitted " + fitted + " x " + spec.getWeaponName() + " on " + member.getShipName() + note
                + "; OP " + v.computeOPCost(stats) + "/" + maxOP + ", vents " + v.getNumFluxVents() + ", caps " + v.getNumFluxCapacitors();
    }

    /** "store <ship|combat>": moves ships into this market's storage (needs paid storage access). Never the flagship. */
    static String store(String q) {
        MarketAPI m = GameReader.dockedMarket();
        if (m == null) return "not docked at a market (dock first)";
        if (!Misc.playerHasStorageAccess(m)) return "no storage access at " + m.getName() + " (pay the one-time fee on the Storage tab)";
        CargoAPI st = Misc.getStorageCargo(m);
        if (st == null) return m.getName() + " has no storage";
        st.initMothballedShips(Global.getSector().getPlayerFaction().getId());
        String key = q == null ? "" : q.trim().toLowerCase();
        StringBuilder b = new StringBuilder();
        for (FleetMemberAPI f : pf().getFleetData().getMembersListCopy()) {
            if (f.isFlagship()) continue;
            boolean match = key.equals("combat") ? !civilian(f)
                    : Math.max(score(key, f.getShipName()), score(key, f.getHullSpec().getHullName())) >= 60;
            if (!match) continue;
            f.setCaptain(null);
            pf().getFleetData().removeFleetMember(f);
            st.getMothballedShips().addFleetMember(f);
            b.append(b.length() == 0 ? "" : ", ").append(f.getShipName()).append(" (").append(f.getHullSpec().getHullName()).append(")");
        }
        pf().getFleetData().syncIfNeeded();
        if (b.length() == 0) return "no ship matching '" + q + "' to store";
        return "stored at " + m.getName() + ": " + b + "; storage fee about " + n(Misc.getStorageCostPerMonth(m)) + " cr/month";
    }

    /** "retrieve <ship|all>": takes ships back from this market's storage, then re-assigns idle officers. */
    static String retrieve(String q) {
        MarketAPI m = GameReader.dockedMarket();
        if (m == null) return "not docked at a market (dock first)";
        CargoAPI st = Misc.getStorageCargo(m);
        if (st == null || st.getMothballedShips() == null) return "nothing stored at " + m.getName();
        String key = q == null ? "all" : q.trim().toLowerCase();
        StringBuilder b = new StringBuilder();
        for (FleetMemberAPI f : st.getMothballedShips().getMembersListCopy()) {
            if (!key.equals("all") && Math.max(score(key, f.getShipName()), score(key, f.getHullSpec().getHullName())) < 60) continue;
            st.getMothballedShips().removeFleetMember(f);
            pf().getFleetData().addFleetMember(f);
            f.getRepairTracker().setMothballed(false);
            try { f.updateStats(); } catch (Throwable ignored) {}
            b.append(b.length() == 0 ? "" : ", ").append(f.getShipName()).append(" (").append(f.getHullSpec().getHullName()).append(")");
        }
        pf().getFleetData().syncIfNeeded();
        if (b.length() == 0) return "nothing matching '" + key + "' stored at " + m.getName();
        return "retrieved from " + m.getName() + ": " + b + "\n" + autoAssign();
    }


    /** "unequip <ship> <weapon|all>": moves fitted weapons back to cargo. */
    static String unequip(String args) {
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        if (docked(err, mode) == null) return err[0];
        String a = args.trim();
        FleetMemberAPI member = null; String wq = null;
        String[] w = a.split("\\s+");
        for (int i = w.length - 1; i >= 1 && member == null; i--) {
            StringBuilder head = new StringBuilder();
            for (int j = 0; j < i; j++) head.append(j == 0 ? "" : " ").append(w[j]);
            FleetMemberAPI f = findMember(head.toString());
            if (f != null) { member = f; StringBuilder t = new StringBuilder(); for (int j = i; j < w.length; j++) t.append(j == i ? "" : " ").append(w[j]); wq = t.toString(); }
        }
        if (member == null) return "usage: unequip <ship> <weapon|all>";
        ShipVariantAPI v = member.getVariant().clone();
        v.setSource(VariantSource.REFIT);
        if (v.isStockVariant()) v.setHullVariantId(Misc.genUID());
        StringBuilder b = new StringBuilder();
        for (String slot : new ArrayList<String>(v.getFittedWeaponSlots())) {
            WeaponSlotAPI sl = v.getSlot(slot);
            if (sl == null || sl.isBuiltIn() || sl.isDecorative() || sl.isSystemSlot()) continue;
            String id = v.getWeaponId(slot);
            if (id == null) continue;
            WeaponSpecAPI ws = Global.getSettings().getWeaponSpec(id);
            if (!wq.equalsIgnoreCase("all") && Math.max(score(wq, id), ws == null ? 0 : score(wq, ws.getWeaponName())) < 60) continue;
            v.clearSlot(slot);
            pf().getCargo().addWeapons(id, 1);
            b.append(b.length() == 0 ? "" : ", ").append(ws == null ? id : ws.getWeaponName()).append(" (").append(slot).append(")");
        }
        if (b.length() == 0) return "nothing fitted on " + member.getShipName() + " matches '" + wq + "'";
        v.autoGenerateWeaponGroups();
        member.setVariant(v, false, true);
        try { member.updateStats(); } catch (Throwable ignored) {}
        pf().getFleetData().syncIfNeeded();
        return "moved to cargo from " + member.getShipName() + ": " + b;
    }

    // ------------------------------------------------------------------ restoration and crew

    /** What the dockyard charges to restore a hull: base value x baseRestoreCostMult x baseRestoreCostMultPerDMod^d-mods. */
    static float restoreCost(FleetMemberAPI m) {
        ShipVariantAPI v = m.getVariant();
        com.fs.starfarer.api.combat.ShipHullSpecAPI spec = v.getHullSpec();
        com.fs.starfarer.api.combat.ShipHullSpecAPI target = spec.isDHull() && spec.getDParentHull() != null ? spec.getDParentHull() : spec;
        int d = com.fs.starfarer.api.impl.campaign.DModManager.getNumDMods(v);
        float base = Global.getSettings().getFloat("baseRestoreCostMult");
        float per = Global.getSettings().getFloat("baseRestoreCostMultPerDMod");
        return target.getBaseValue() * base * (float) Math.pow(per, d);
    }

    /** Removes every d-mod (and returns a (D) hull to its original hull) at a market with a spaceport, as the refit screen's Restore does. */
    static String restore(String q) {
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        MarketAPI m = docked(err, mode);
        if (m == null) return err[0];
        if (!m.hasSpaceport()) return m.getName() + " has no spaceport; restoration needs one";
        List<FleetMemberAPI> todo = new ArrayList<FleetMemberAPI>();
        if (q.trim().equalsIgnoreCase("all")) {
            for (FleetMemberAPI f : pf().getFleetData().getMembersListCopy())
                if (com.fs.starfarer.api.impl.campaign.DModManager.getNumDMods(f.getVariant()) > 0 || f.getVariant().isDHull()) todo.add(f);
        } else {
            FleetMemberAPI f = findMember(q);
            if (f == null) return "no ship matching '" + q + "'";
            todo.add(f);
        }
        StringBuilder b = new StringBuilder();
        for (FleetMemberAPI f : todo) {
            ShipVariantAPI v0 = f.getVariant();
            int d = com.fs.starfarer.api.impl.campaign.DModManager.getNumDMods(v0);
            if (d == 0 && !v0.isDHull()) { b.append(f.getShipName()).append(": nothing to restore\n"); continue; }
            float cost = restoreCost(f);
            float credits = pf().getCargo().getCredits().get();
            if (cost > credits - Rules.f("reserve_credits", 5000f)) { b.append(f.getShipName()).append(": restoring costs ").append(n(cost)).append(" cr, not enough credits\n"); continue; }
            ShipVariantAPI v = v0.clone();
            v.setSource(VariantSource.REFIT);
            if (v.isStockVariant()) v.setHullVariantId(Misc.genUID());
            List<String> ids = new ArrayList<String>(v.getHullMods());
            for (String id : ids) {
                HullModSpecAPI sp = Global.getSettings().getHullModSpec(id);
                if (sp != null && sp.hasTag(Tags.HULLMOD_DMOD)) com.fs.starfarer.api.impl.campaign.DModManager.removeDMod(v, id);
            }
            if (v.getHullSpec().isDHull() && v.getHullSpec().getDParentHull() != null) v.setHullSpecAPI(v.getHullSpec().getDParentHull());
            f.setVariant(v, false, true);
            try { f.updateStats(); } catch (Throwable ignored) {}
            pf().getCargo().getCredits().subtract(cost);
            b.append(f.getShipName()).append(" (").append(f.getHullSpec().getHullName()).append("): restored, ").append(d).append(" d-mods removed, ")
             .append(n(cost)).append(" cr\n");
        }
        pf().getFleetData().syncIfNeeded();
        b.append("credits ").append(n(pf().getCargo().getCredits().get()));
        return b.toString();
    }

    /** Return mothballed ships in the player fleet to active duty ("activate <ship|all>"). */
    static String activate(String q) {
        String key = q == null ? "all" : q.trim().toLowerCase();
        StringBuilder b = new StringBuilder();
        for (FleetMemberAPI f : pf().getFleetData().getMembersListCopy()) {
            if (!f.isMothballed()) continue;
            String nm = (f.getShipName() + " " + f.getHullSpec().getHullName()).toLowerCase();
            if (!key.equals("all") && !nm.contains(key)) continue;
            f.getRepairTracker().setMothballed(false);
            try { f.updateStats(); } catch (Throwable ignored) {}
            b.append(f.getShipName()).append(" (").append(f.getHullSpec().getHullName()).append("): active, needs ")
             .append(Math.round(f.getMinCrew())).append(" crew\n");
        }
        pf().getFleetData().syncIfNeeded();
        return b.length() == 0 ? "no mothballed ships match '" + key + "'" : b.toString().trim();
    }

    /** Crew the fleet needs: every ship's minimum + crew_extra_pct, capped by berths. */
    static String crew(String arg) {
        float min = 0f, max = 0f;
        for (FleetMemberAPI f : pf().getFleetData().getMembersListCopy()) { if (!f.isMothballed()) { min += f.getMinCrew(); max += f.getMaxCrew(); } }
        float have = pf().getCargo().getCrew();
        float target = Math.min(max, (float) Math.ceil(min * (1f + Rules.f("crew_extra_pct", 25f) / 100f)));
        String head = "crew " + n(have) + " of " + n(min) + " needed (target " + n(target) + ", berths " + n(pf().getCargo().getMaxPersonnel()) + ")";
        if (arg == null || !arg.trim().toLowerCase().startsWith("fill")) return head;
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        MarketAPI m = docked(err, mode);
        if (m == null) return head + "; " + err[0];
        float need = target - have;
        need = Math.min(need, pf().getCargo().getMaxPersonnel() - pf().getCargo().getTotalPersonnel());
        if (need < 1f) return head + "; nothing to buy";
        StringBuilder b = new StringBuilder(head);
        for (SubmarketAPI s : subs(m, mode[0])) {
            if (need < 1f) break;
            float stock = s.getCargo().getCommodityQuantity("crew");
            float q = (float) Math.floor(Math.min(need, stock));
            if (q < 1f) continue;
            float cost = m.getSupplyPrice("crew", q, true) * (1f + s.getTariff());
            if (cost > pf().getCargo().getCredits().get() - Rules.f("reserve_credits", 5000f)) continue;
            s.getCargo().removeCommodity("crew", q);
            pf().getCargo().addCommodity("crew", q);
            pf().getCargo().getCredits().subtract(cost);
            PlayerMarketTransaction tx = new PlayerMarketTransaction(m, s, mode[0]);
            tx.getBought().addCommodity("crew", q);
            tx.setCreditValue(-cost);
            report(m, s, mode[0], tx);
            need -= q;
            b.append("\nhired ").append(n(q)).append(" crew on the ").append(s.getNameOneLine()).append(" for ").append(n(cost)).append(" cr");
        }
        if (need >= 1f) b.append("\nstill short ").append(n(need)).append(" (no stock left here)");
        b.append("\ncrew now ").append(n(pf().getCargo().getCrew()));
        return b.toString();
    }

    // ------------------------------------------------------------------ officers

    static String assign(String args) {
        String[] p = args.split("\\s+to\\s+|\\s*->\\s*", 2);
        if (p.length < 2) return "usage: assign <officer> to <ship>";
        OfficerDataAPI off = null;
        int best = 0;
        for (OfficerDataAPI o : pf().getFleetData().getOfficersCopy()) {
            int s = score(p[0], o.getPerson().getNameString());
            if (s > best) { best = s; off = o; }
        }
        if (off == null || best < 30) return "no officer matching '" + p[0] + "' in your fleet";
        FleetMemberAPI ship = findMember(p[1]);
        if (ship == null) return "no ship matching '" + p[1] + "'";
        if (ship.isFlagship() || (ship.getCaptain() != null && ship.getCaptain().isPlayer())) return ship.getShipName() + " is your flagship";
        FleetMemberAPI was = pf().getFleetData().getMemberWithCaptain(off.getPerson());
        if (was != null) was.setCaptain(null);
        ship.setCaptain(off.getPerson());
        return off.getPerson().getNameString() + " now commands " + ship.getShipName();
    }

    static String autoAssign() {
        CoreAutofitPlugin plugin = new CoreAutofitPlugin(Global.getSector().getPlayerPerson());
        plugin.setRandom(new java.util.Random());
        plugin.autoAssignOfficers(pf());
        return fleet();
    }
}
