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
        // 1.0.7: the military market too, when the game would open it (open trade mode, Favorable or better);
        // each item still carries its own standing/commission requirement (checked as "can't buy here")
        SubmarketAPI mil = m.getSubmarket(Submarkets.GENERIC_MILITARY);
        if (mil != null && mode == CoreUITradeMode.OPEN) {
            try {
                com.fs.starfarer.api.campaign.RepLevel lvl = mil.getFaction().getRelationshipLevel(Global.getSector().getPlayerFaction());
                if (lvl.isAtWorst(com.fs.starfarer.api.campaign.RepLevel.FAVORABLE)) out.add(mil);
            } catch (Throwable ignored) {}
        }
        if (black != null) out.add(black);
        for (SubmarketAPI s : out) {
            try { s.getPlugin().updateCargoPrePlayerInteraction(); } catch (Throwable ignored) {}
        }
        return out;
    }

    /** 1.0.7: black-market trade with the transponder on costs standing with the market's faction; such buys are refused. */
    static boolean blackLocked(SubmarketAPI s) {
        return s != null && Submarkets.SUBMARKET_BLACK.equals(s.getSpecId()) && pf().isTransponderOn()
                && !Rules.b("black_market_with_transponder", false);
    }

    static String blackLockedMsg(MarketAPI m) {
        return "the transponder is on: black-market trade costs standing with " + m.getFaction().getDisplayName()
                + " (send 'ability transponder off' first; if a patrol makes you turn it on, it stays on)";
    }

    private static String subName(SubmarketAPI s) {
        if (Submarkets.GENERIC_MILITARY.equals(s.getSpecId())) return "military";
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
        if (blackLocked(from)) return pick.getHullSpec().getHullName() + " \"" + pick.getShipName() + "\" is on the black market and " + blackLockedMsg(m);
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
                    what = weaponStats(w);
                } else if (st.isFighterWingStack()) {
                    FighterWingSpecAPI w = st.getFighterWingSpecIfWing();
                    id = w.getId();
                    what = "wing, " + Math.round(w.getOpCost(null)) + " OP";
                } else if (st.isSpecialStack() && st.getSpecialDataIfSpecial() != null && "modspec".equals(st.getSpecialDataIfSpecial().getId())) {
                    id = st.getSpecialDataIfSpecial().getData();
                    boolean known = Global.getSector().getPlayerFaction().knowsHullMod(id);
                    what = "hullmod spec" + (known ? " (already known)" : "");
                } else continue;
                boolean illegal = s.getPlugin().isIllegalOnSubmarket(st, SubmarketPlugin.TransferAction.PLAYER_BUY);
                String line = st.getDisplayName() + " [" + id + "] | " + what + " | " + subName(s) + " x" + Math.round(st.getSize())
                        + " | " + n(stackPrice(st, s)) + " cr each"
                        + (illegal ? " | can't buy: " + s.getPlugin().getIllegalTransferText(st, SubmarketPlugin.TransferAction.PLAYER_BUY).replace('\n', ' ') : "");
                if (filter == null || filter.isEmpty() || line.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) b.append(line).append("\n");
            }
        }
        return b.toString();
    }

    /** "SMALL ENERGY, 6 OP, ENERGY, 500 range, 350 dps, 200 flux/s, PD" */
    static String weaponStats(WeaponSpecAPI w) {
        StringBuilder b = new StringBuilder();
        b.append(w.getSize()).append(" ").append(w.getMountType()).append(", ").append(Math.round(w.getOrdnancePointCost(null))).append(" OP");
        try { b.append(", ").append(w.getDamageType()); } catch (Throwable ignored) {}
        try { b.append(", ").append(Math.round(w.getMaxRange())).append(" range"); } catch (Throwable ignored) {}
        try {
            com.fs.starfarer.api.combat.WeaponAPI.DerivedWeaponStatsAPI d = w.getDerivedStats();
            if (d != null) {
                float dps = d.getSustainedDps() > 0 ? d.getSustainedDps() : d.getDps();
                if (dps > 0) b.append(", ").append(Math.round(dps)).append(" dps");
                float fx = d.getSustainedFluxPerSecond() > 0 ? d.getSustainedFluxPerSecond() : d.getFluxPerSecond();
                if (fx > 0) b.append(", ").append(Math.round(fx)).append(" flux/s");
            }
        } catch (Throwable ignored) {}
        try { if (w.getAIHints().contains(com.fs.starfarer.api.combat.WeaponAPI.AIHints.PD)) b.append(", PD"); } catch (Throwable ignored) {}
        return b.toString();
    }

    /**
     * 1.0.8 "find <filter> [for <ship>]": weapons, wings, hullmod specs and ships in every market's open, military
     * and black-market stock as last seen (stock is only known for submarkets you have opened; it may have
     * restocked since). Every word of the filter must appear in the line. "for <ship>" keeps weapons that fit one
     * of that ship's slots (empty or not). Buyable lines first, then nearest.
     */
    static String find(String q) {
        String a = q == null ? "" : q.trim();
        FleetMemberAPI ship = null;
        String[] fp = a.split("(?i)\\s*\\bfor\\s+", 2);
        if (fp.length == 2) {
            ship = findMember(fp[1].trim());
            if (ship == null) return "no ship in your fleet matching '" + fp[1].trim() + "'";
            a = fp[0].trim();
        }
        if (a.isEmpty() && ship == null) return "usage: find <name|words> [for <ship>]  e.g. find small energy | find pd for Hlad | find heavy burst | find hullmod | find ship eagle";
        String[] words = a.toLowerCase(Locale.ROOT).split("\\s+");
        List<WeaponSlotAPI> slots = new ArrayList<WeaponSlotAPI>();
        if (ship != null) for (WeaponSlotAPI sl : ship.getHullSpec().getAllWeaponSlotsCopy())
            if (sl.isWeaponSlot() && !sl.isBuiltIn() && !sl.isDecorative() && !sl.isSystemSlot() && !sl.isStationModule()) slots.add(sl);
        boolean tOn = pf().isTransponderOn();
        boolean blackOk = Rules.b("black_market_with_transponder", false);
        final List<Object[]> hits = new ArrayList<Object[]>(); // {Boolean buyable, Float ly, String line}
        int unseen = 0;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || m.getPrimaryEntity() == null) continue;
            float ly = Misc.getDistanceToPlayerLY(m.getPrimaryEntity());
            boolean hostile = m.getFaction().isHostileTo(Global.getSector().getPlayerFaction());
            String where = m.getName() + " (" + m.getFaction().getDisplayName()
                    + (m.getContainingLocation() != null && !m.getContainingLocation().isHyperspace() ? ", " + m.getContainingLocation().getNameWithNoType() : "")
                    + ", " + String.format(Locale.ROOT, "%.1f", ly) + " ly)";
            for (SubmarketAPI s : m.getSubmarketsCopy()) {
                String sp = s.getSpecId();
                boolean open = Submarkets.SUBMARKET_OPEN.equals(sp), mil = Submarkets.GENERIC_MILITARY.equals(sp), black = Submarkets.SUBMARKET_BLACK.equals(sp);
                if (!open && !mil && !black) continue;
                CargoAPI c = null;
                try { c = s.getPlugin().getCargoNullOk(); } catch (Throwable ignored) {}
                if (c == null) { unseen++; continue; }
                String age = "";
                try {
                    if (s.getPlugin() instanceof com.fs.starfarer.api.impl.campaign.submarkets.BaseSubmarketPlugin) {
                        com.fs.starfarer.api.impl.campaign.submarkets.BaseSubmarketPlugin bp = (com.fs.starfarer.api.impl.campaign.submarkets.BaseSubmarketPlugin) s.getPlugin();
                        float d = bp.getSinceLastCargoUpdate();
                        age = " | stock " + Math.round(d) + "d old" + (d > bp.getMinSWUpdateInterval() ? ", may have restocked" : "");
                    }
                } catch (Throwable ignored) {}
                String block = null;
                if (hostile && !black) block = "hostile faction";
                else if (black && tOn && !blackOk) block = "transponder on (black market)";
                else if (mil) {
                    try {
                        if (!s.getFaction().getRelationshipLevel(Global.getSector().getPlayerFaction()).isAtWorst(com.fs.starfarer.api.campaign.RepLevel.FAVORABLE))
                            block = "military market needs Favorable";
                    } catch (Throwable ignored) {}
                }
                String sub = open ? "open" : mil ? "military" : "black";
                for (CargoStackAPI st : c.getStacksCopy()) {
                    String what, id;
                    if (st.isWeaponStack()) {
                        WeaponSpecAPI w = st.getWeaponSpecIfWeapon();
                        if (ship != null) {
                            boolean fits = false;
                            for (WeaponSlotAPI sl : slots) if (sl.weaponFits(w)) { fits = true; break; }
                            if (!fits) continue;
                        }
                        id = w.getWeaponId();
                        what = "weapon " + weaponStats(w);
                    } else if (ship != null) continue;
                    else if (st.isFighterWingStack()) {
                        id = st.getFighterWingSpecIfWing().getId();
                        what = "wing, " + Math.round(st.getFighterWingSpecIfWing().getOpCost(null)) + " OP";
                    } else if (st.isSpecialStack() && st.getSpecialDataIfSpecial() != null && "modspec".equals(st.getSpecialDataIfSpecial().getId())) {
                        id = st.getSpecialDataIfSpecial().getData();
                        what = "hullmod spec" + (Global.getSector().getPlayerFaction().knowsHullMod(id) ? " (already known)" : "");
                    } else continue;
                    String why = block;
                    if (why == null) {
                        try {
                            if (s.getPlugin().isIllegalOnSubmarket(st, SubmarketPlugin.TransferAction.PLAYER_BUY))
                                why = s.getPlugin().getIllegalTransferText(st, SubmarketPlugin.TransferAction.PLAYER_BUY).replace('\n', ' ');
                        } catch (Throwable ignored) {}
                    }
                    String line = st.getDisplayName() + " [" + id + "] | " + what + " | " + where + " " + sub + " x" + Math.round(st.getSize())
                            + " | " + n(stackPrice(st, s)) + " cr" + age + (why != null ? " | can't buy: " + why : "");
                    if (matches(line, words)) hits.add(new Object[]{why == null, ly, line});
                }
                if (ship == null && c.getMothballedShips() != null) {
                    for (FleetMemberAPI f : c.getMothballedShips().getMembersListCopy()) {
                        String why = block;
                        if (why == null) {
                            try {
                                if (s.getPlugin().isIllegalOnSubmarket(f, SubmarketPlugin.TransferAction.PLAYER_BUY))
                                    why = s.getPlugin().getIllegalTransferText(f, SubmarketPlugin.TransferAction.PLAYER_BUY).replace('\n', ' ');
                            } catch (Throwable ignored) {}
                        }
                        String line = f.getHullSpec().getHullName() + " [" + f.getHullId() + "] | ship " + f.getHullSpec().getHullSize()
                                + ", d-mods " + dmods(f) + ", DP " + Math.round(f.getDeploymentPointsCost()) + " | " + where + " " + sub
                                + " | " + n(shipPrice(f, s)) + " cr" + age + (why != null ? " | can't buy: " + why : "");
                        if (matches(line, words)) hits.add(new Object[]{why == null, ly, line});
                    }
                }
            }
        }
        java.util.Collections.sort(hits, new java.util.Comparator<Object[]>() {
            public int compare(Object[] x, Object[] y) {
                if (!x[0].equals(y[0])) return ((Boolean) x[0]) ? -1 : 1;
                return Float.compare((Float) x[1], (Float) y[1]);
            }
        });
        int max = (int) Rules.f("find_max_lines", 40f);
        StringBuilder b = new StringBuilder("find '" + a + "'" + (ship != null ? " for " + ship.getShipName() + " (" + ship.getHullSpec().getHullName() + ")" : "")
                + ": " + hits.size() + " hit(s) in stock you have seen" + (unseen > 0 ? " (" + unseen + " submarkets never opened: stock unknown)" : "") + "\n");
        for (int i = 0; i < hits.size() && i < max; i++) b.append(hits.get(i)[2]).append("\n");
        if (hits.size() > max) b.append("... ").append(hits.size() - max).append(" more (narrow the filter)\n");
        return b.toString().trim();
    }

    private static boolean matches(String line, String[] words) {
        String l = line.toLowerCase(Locale.ROOT);
        for (String w : words) if (w.length() > 0 && !l.contains(w)) return false;
        return true;
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
        boolean lockedHit = false;
        for (SubmarketAPI s : subs(m, mode[0])) {
            for (CargoStackAPI st : s.getCargo().getStacksCopy()) {
                String id, name;
                if (st.isWeaponStack()) { id = st.getWeaponSpecIfWeapon().getWeaponId(); name = st.getWeaponSpecIfWeapon().getWeaponName(); }
                else if (st.isFighterWingStack()) { id = st.getFighterWingSpecIfWing().getId(); name = st.getFighterWingSpecIfWing().getWingName(); }
                else continue;
                if (s.getPlugin().isIllegalOnSubmarket(st, SubmarketPlugin.TransferAction.PLAYER_BUY)) continue;
                if (blackLocked(s)) { lockedHit = true; continue; }
                int sc = Math.max(score(a, id), score(a, name));
                float pr = stackPrice(st, s);
                if (sc > best || (sc == best && pr < bestPrice)) { best = sc; pick = st; from = s; bestPrice = pr; }
            }
        }
        if (pick == null || best < 30) return "no weapon or wing matching '" + a + "' here (see 'weapons')" + (lockedHit ? "; the black market is skipped because " + blackLockedMsg(m) : "");
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
        if (blackLocked(from)) return "that spec is on the black market and " + blackLockedMsg(m);
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
            if (mk != null) for (SubmarketAPI s : subs(mk, md)) if (!blackLocked(s)) add(s.getCargo(), s); // 1.0.7: no black-market buys with the transponder on
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

    // ------------------------------------------------------------------ 1.0.7: selling, mothballing, repairs, hullmods

    /** Splits "ship words... rest words" at the longest leading ship name that matches a fleet member. */
    private static Object[] shipAndRest(String args) {
        String[] w = args.trim().split("\\s+");
        for (int i = w.length; i >= 1; i--) {
            StringBuilder head = new StringBuilder();
            for (int j = 0; j < i; j++) head.append(j == 0 ? "" : " ").append(w[j]);
            FleetMemberAPI f = findMember(head.toString());
            if (f != null && Math.max(score(head.toString(), f.getShipName()), score(head.toString(), f.getHullSpec().getHullName())) >= 60) {
                StringBuilder t = new StringBuilder();
                for (int j = i; j < w.length; j++) t.append(j == i ? "" : " ").append(w[j]);
                return new Object[] { f, t.toString().trim() };
            }
        }
        return null;
    }

    /** What the market pays for a ship: its base sell value less the submarket's tariff. */
    static float sellPrice(FleetMemberAPI f, SubmarketAPI s) {
        return f.getBaseSellValue() * (1f - s.getTariff());
    }

    /**
     * "sellship <ship> [black] [confirm]": without confirm it only quotes. Fitted weapons and fighter wings go to
     * cargo first (the sale price doesn't pay for them); the officer stays in the fleet. Never the flagship.
     */
    static String sellShip(String args) {
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        MarketAPI m = docked(err, mode);
        if (m == null) return err[0];
        List<String> words = new ArrayList<String>();
        boolean confirm = false, black = false, anyway = false;
        for (String w : args.trim().split("\\s+")) {
            if (w.equalsIgnoreCase("confirm")) confirm = true;
            else if (w.equalsIgnoreCase("black")) black = true;
            else if (w.equalsIgnoreCase("anyway")) anyway = true;
            else words.add(w);
        }
        StringBuilder q = new StringBuilder();
        for (String w : words) q.append(q.length() == 0 ? "" : " ").append(w);
        if (q.length() == 0) return "usage: sellship <ship> [black] [confirm]";
        FleetMemberAPI f = findMember(q.toString());
        if (f == null) return "no ship matching '" + q + "' in the fleet";
        if (f.isFlagship()) return "won't sell the flagship (" + f.getShipName() + ")";
        SubmarketAPI sub = black ? m.getSubmarket(Submarkets.SUBMARKET_BLACK)
                : (mode[0] == CoreUITradeMode.OPEN ? m.getSubmarket(Submarkets.SUBMARKET_OPEN) : null);
        if (sub == null) return black ? m.getName() + " has no black market" : m.getName() + "'s open market won't trade with you (try 'black')";
        if (sub.getPlugin().isIllegalOnSubmarket(f, SubmarketPlugin.TransferAction.PLAYER_SELL))
            return f.getHullSpec().getHullName() + " can't be sold on the " + sub.getNameOneLine() + ": "
                    + sub.getPlugin().getIllegalTransferText(f, SubmarketPlugin.TransferAction.PLAYER_SELL);
        float price = sellPrice(f, sub);
        String what = f.getHullSpec().getHullName() + " \"" + f.getShipName() + "\"";
        if (!confirm)
            return "quote: " + what + " sells for " + n(price) + " cr on the " + sub.getNameOneLine() + " (base sell value "
                    + n(f.getBaseSellValue()) + ", " + Math.round(sub.getTariff() * 100f) + "% tariff, " + dmods(f) + " d-mods); fitted weapons and wings go to cargo first. "
                    + "Send 'sellship " + q + (black ? " black" : "") + " confirm' to sell.";
        if (blackLocked(sub) && !anyway) return "won't sell: " + blackLockedMsg(m) + " (or add 'anyway')";
        // strip weapons and wings into cargo, as a player would before selling
        ShipVariantAPI v = f.getVariant().clone();
        v.setSource(VariantSource.REFIT);
        if (v.isStockVariant()) v.setHullVariantId(Misc.genUID());
        int stripped = 0;
        for (String slot : new ArrayList<String>(v.getFittedWeaponSlots())) {
            WeaponSlotAPI sl = v.getSlot(slot);
            if (sl == null || sl.isBuiltIn() || sl.isDecorative() || sl.isSystemSlot()) continue;
            String id = v.getWeaponId(slot);
            if (id == null) continue;
            v.clearSlot(slot);
            pf().getCargo().addWeapons(id, 1);
            stripped++;
        }
        int builtInWings = v.getHullSpec().getBuiltInWings() == null ? 0 : v.getHullSpec().getBuiltInWings().size();
        for (int i = builtInWings; i < v.getWings().size(); i++) {
            String w = v.getWingId(i);
            if (w == null) continue;
            v.setWingId(i, null);
            pf().getCargo().addFighters(w, 1);
            stripped++;
        }
        f.setVariant(v, false, true);
        f.setCaptain(null);
        pf().getFleetData().removeFleetMember(f);
        if (sub.getCargo().getMothballedShips() == null) sub.getCargo().initMothballedShips(sub.getFaction().getId());
        f.getRepairTracker().setMothballed(true);
        sub.getCargo().getMothballedShips().addFleetMember(f);
        pf().getCargo().getCredits().add(price);
        PlayerMarketTransaction tx = new PlayerMarketTransaction(m, sub, mode[0]);
        tx.getShipsSold().add(new ShipSaleInfo(f, price));
        tx.setCreditValue(price);
        report(m, sub, mode[0], tx);
        pf().getFleetData().syncIfNeeded();
        EventLog.info("shipyard", "sold " + what + " for " + n(price) + " cr at " + m.getName());
        float crew = pf().getCargo().getCrew(), berths = pf().getCargo().getMaxPersonnel();
        float cargo = pf().getCargo().getSpaceUsed(), hold = pf().getCargo().getMaxCapacity();
        float fuel = pf().getCargo().getFuel(), tanks = pf().getCargo().getMaxFuel();
        return "sold " + what + " on the " + sub.getNameOneLine() + " for " + n(price) + " cr" + (stripped > 0 ? " (" + stripped + " weapons/wings moved to cargo)" : "")
                + "; credits " + n(pf().getCargo().getCredits().get())
                + (crew > berths ? "; WARNING crew " + n(crew) + " over berths " + n(berths) : "")
                + (cargo > hold ? "; WARNING cargo " + n(cargo) + " over capacity " + n(hold) : "")
                + (fuel > tanks ? "; WARNING fuel " + n(fuel) + " over tank capacity " + n(tanks) : "");
    }

    /** "mothball <ship>": no maintenance, can't deploy; the officer is freed (autoassign). Never the flagship. */
    static String mothball(String q) {
        if (q == null || q.trim().isEmpty()) return "usage: mothball <ship>";
        FleetMemberAPI f = findMember(q);
        if (f == null) return "no ship matching '" + q + "'";
        if (f.isFlagship()) return "won't mothball the flagship";
        if (f.isMothballed()) return f.getShipName() + " is already mothballed";
        PersonAPI c = f.getCaptain();
        boolean freed = c != null && !c.isDefault() && !c.isPlayer();
        if (freed) f.setCaptain(null);
        f.getRepairTracker().setMothballed(true);
        try { f.updateStats(); } catch (Throwable ignored) {}
        pf().getFleetData().syncIfNeeded();
        return f.getShipName() + " (" + f.getHullSpec().getHullName() + "): mothballed (no maintenance, can't deploy; 'activate' brings it back)"
                + (freed ? "\n" + autoAssign() : "");
    }

    /** "repairs on|off [ship|all]": suspends or resumes repairs and CR recovery (they cost supplies). */
    static String repairs(String a) {
        String[] w = a == null ? new String[0] : a.trim().split("\\s+", 2);
        if (w.length == 0 || !(w[0].equalsIgnoreCase("on") || w[0].equalsIgnoreCase("off"))) {
            StringBuilder b = new StringBuilder("repairs (usage: repairs on|off [ship|all])\n");
            for (FleetMemberAPI f : pf().getFleetData().getMembersListCopy())
                b.append(f.getShipName()).append(": ").append(f.getRepairTracker().isSuspendRepairs() ? "SUSPENDED" : "on")
                 .append(", hull ").append(Math.round(f.getStatus().getHullFraction() * 100f)).append("%, CR ")
                 .append(Math.round(f.getRepairTracker().getCR() * 100f)).append("/").append(Math.round(f.getRepairTracker().getMaxCR() * 100f)).append("%\n");
            return b.toString().trim();
        }
        boolean on = w[0].equalsIgnoreCase("on");
        String key = w.length > 1 ? w[1].trim() : "all";
        StringBuilder b = new StringBuilder();
        for (FleetMemberAPI f : pf().getFleetData().getMembersListCopy()) {
            if (!key.equalsIgnoreCase("all") && Math.max(score(key, f.getShipName()), score(key, f.getHullSpec().getHullName())) < 60) continue;
            f.getRepairTracker().setSuspendRepairs(!on);
            b.append(b.length() == 0 ? "" : ", ").append(f.getShipName());
        }
        if (b.length() == 0) return "no ship matching '" + key + "'";
        return "repairs " + (on ? "resumed" : "suspended") + ": " + b;
    }

    /** A stand-in ShipAPI for the hullmod's own "can this be installed" checks (the refit screen builds a real one). */
    static ShipAPI fakeShip(FleetMemberAPI member, ShipVariantAPI v) { return new RefitShip(member, v); }

    private static HullModSpecAPI modSpec(String q, boolean knownOnly) throws CommandException {
        List<Lookup.Match> l = new ArrayList<Lookup.Match>();
        for (Lookup.Match m : Lookup.hullmods()) {
            if (knownOnly && !Global.getSector().getPlayerFaction().knowsHullMod(m.id)) continue;
            l.add(m);
        }
        Lookup.Match m = Lookup.best(q, l, knownOnly ? "known hullmods" : "hullmods");
        return Global.getSettings().getHullModSpec(m.id);
    }

    /** "addmod <ship> <hullmod>": installs a known hullmod if the OP and the hullmod's own checks allow it. */
    static String addMod(String args) {
        Object[] sr = shipAndRest(args == null ? "" : args);
        if (sr == null || ((String) sr[1]).isEmpty()) return "usage: addmod <ship> <hullmod>";
        FleetMemberAPI member = (FleetMemberAPI) sr[0];
        HullModSpecAPI sp;
        try { sp = modSpec((String) sr[1], true); } catch (CommandException e) { return e.getMessage(); }
        String id = sp.getId(), name = sp.getDisplayName();
        if (sp.hasTag(Tags.HULLMOD_DMOD)) return name + " is a d-mod";
        ShipVariantAPI v = member.getVariant().clone();
        v.setSource(VariantSource.REFIT);
        if (v.isStockVariant()) v.setHullVariantId(Misc.genUID());
        if (v.hasHullMod(id)) return member.getShipName() + " already has " + name;
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        int cost = sp.getCostFor(v.getHullSize());
        int used = v.computeOPCost(stats), max = member.getHullSpec().getOrdnancePoints(stats);
        if (used + cost > max) return name + " costs " + cost + " OP; " + member.getShipName() + " has " + (max - used) + " free (" + used + "/" + max + ")";
        MarketAPI market = GameReader.dockedMarket();
        CoreUITradeMode mode = CoreUITradeMode.NONE;
        try { InteractionDialogAPI d = Global.getSector().getCampaignUI().getCurrentInteractionDialog(); if (d != null && market != null) mode = Trader.tradeMode(d); } catch (Throwable ignored) {}
        ShipAPI ship = fakeShip(member, v);
        try {
            com.fs.starfarer.api.combat.HullModEffect fx = sp.getEffect();
            if (fx != null && !fx.isApplicableToShip(ship)) {
                String why = null;
                try { why = fx.getUnapplicableReason(ship); } catch (Throwable ignored) {}
                return name + " can't go on " + member.getShipName() + (why == null ? "" : ": " + why);
            }
            if (fx != null && !fx.canBeAddedOrRemovedNow(ship, market, mode)) {
                String why = null;
                try { why = fx.getCanNotBeInstalledNowReason(ship, market, mode); } catch (Throwable ignored) {}
                return name + " can't be installed here" + (why == null ? "" : ": " + why);
            }
        } catch (Throwable t) {
            return name + ": its install check needs the refit screen (" + t.getClass().getSimpleName() + "); fit it there";
        }
        v.addMod(id);
        member.setVariant(v, false, true);
        try { member.updateStats(); } catch (Throwable ignored) {}
        pf().getFleetData().syncIfNeeded();
        return "installed " + name + " on " + member.getShipName() + " (" + cost + " OP); OP " + member.getVariant().computeOPCost(stats) + "/" + max;
    }

    /** "removemod <ship> <hullmod>": removes a fitted hullmod (not built-in, s-mod or d-mod; 'restore' handles d-mods). */
    static String removeMod(String args) {
        Object[] sr = shipAndRest(args == null ? "" : args);
        if (sr == null || ((String) sr[1]).isEmpty()) return "usage: removemod <ship> <hullmod>";
        FleetMemberAPI member = (FleetMemberAPI) sr[0];
        HullModSpecAPI sp;
        try { sp = modSpec((String) sr[1], false); } catch (CommandException e) { return e.getMessage(); }
        String id = sp.getId(), name = sp.getDisplayName();
        ShipVariantAPI v = member.getVariant().clone();
        v.setSource(VariantSource.REFIT);
        if (v.isStockVariant()) v.setHullVariantId(Misc.genUID());
        if (!v.getNonBuiltInHullmods().contains(id)) return member.getShipName() + " has no removable " + name;
        if (sp.hasTag(Tags.HULLMOD_DMOD)) return name + " is a d-mod: use 'restore'";
        if (v.getSMods().contains(id)) return name + " is built in (s-mod)";
        MarketAPI market = GameReader.dockedMarket();
        CoreUITradeMode mode = CoreUITradeMode.NONE;
        try { InteractionDialogAPI d = Global.getSector().getCampaignUI().getCurrentInteractionDialog(); if (d != null && market != null) mode = Trader.tradeMode(d); } catch (Throwable ignored) {}
        try {
            com.fs.starfarer.api.combat.HullModEffect fx = sp.getEffect();
            ShipAPI ship = fakeShip(member, v);
            if (fx != null && !fx.canBeAddedOrRemovedNow(ship, market, mode)) {
                String why = null;
                try { why = fx.getCanNotBeInstalledNowReason(ship, market, mode); } catch (Throwable ignored) {}
                return name + " can't be removed here" + (why == null ? "" : ": " + why);
            }
        } catch (Throwable t) {
            return name + ": its removal check needs the refit screen (" + t.getClass().getSimpleName() + "); remove it there";
        }
        v.removeMod(id);
        member.setVariant(v, false, true);
        try { member.updateStats(); } catch (Throwable ignored) {}
        // wings beyond the remaining bays (e.g. Converted Hangar removed) go to cargo
        StringBuilder extra = new StringBuilder();
        try {
            int bays = Math.round(member.getStats().getNumFighterBays().getModifiedValue());
            ShipVariantAPI v2 = member.getVariant();
            boolean changed = false;
            for (int i = v2.getWings().size() - 1; i >= bays; i--) {
                String w = v2.getWingId(i);
                if (w == null) continue;
                v2.setWingId(i, null);
                pf().getCargo().addFighters(w, 1);
                extra.append(extra.length() == 0 ? "; wings moved to cargo: " : ", ").append(w);
                changed = true;
            }
            if (changed) member.setVariant(v2, false, true);
        } catch (Throwable ignored) {}
        pf().getFleetData().syncIfNeeded();
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        return "removed " + name + " from " + member.getShipName() + "; OP " + member.getVariant().computeOPCost(stats) + "/"
                + member.getHullSpec().getOrdnancePoints(stats) + extra;
    }

    /**
     * 1.0.7: "sellweapons [all|<name>] [black] [confirm]": spare weapons and fighter LPCs in cargo, priced the way the game
     * pays for them (base value x shipWeaponSellPriceMult, less the submarket's tariff - erring low). Quotes unless 'confirm'.
     */
    static String sellWeapons(String args) {
        String[] err = new String[1];
        CoreUITradeMode[] mode = new CoreUITradeMode[1];
        MarketAPI m = docked(err, mode);
        if (m == null) return err[0];
        boolean confirm = false, black = false;
        StringBuilder q = new StringBuilder();
        for (String w : (args == null ? "" : args.trim()).split("\\s+")) {
            if (w.equalsIgnoreCase("confirm")) confirm = true;
            else if (w.equalsIgnoreCase("black")) black = true;
            else if (w.length() > 0) q.append(q.length() == 0 ? "" : " ").append(w);
        }
        String key = q.length() == 0 ? "all" : q.toString();
        SubmarketAPI sub = black ? m.getSubmarket(Submarkets.SUBMARKET_BLACK)
                : (mode[0] == CoreUITradeMode.OPEN ? m.getSubmarket(Submarkets.SUBMARKET_OPEN) : null);
        if (sub == null) return black ? m.getName() + " has no black market" : m.getName() + "'s open market won't trade with you (try 'black')";
        if (confirm && blackLocked(sub)) return "won't sell: " + blackLockedMsg(m);
        float mult = 0.5f;
        try { mult = Global.getSettings().getFloat("shipWeaponSellPriceMult"); } catch (Throwable ignored) {}
        StringBuilder b = new StringBuilder();
        float total = 0f;
        int count = 0;
        PlayerMarketTransaction tx = new PlayerMarketTransaction(m, sub, mode[0]);
        for (CargoStackAPI st : pf().getCargo().getStacksCopy()) {
            if (!st.isWeaponStack() && !st.isFighterWingStack()) continue;
            String id = st.isWeaponStack() ? st.getWeaponSpecIfWeapon().getWeaponId() : st.getFighterWingSpecIfWing().getId();
            String name = st.getDisplayName();
            if (!key.equalsIgnoreCase("all") && Math.max(score(key, id), score(key, name)) < 60) continue;
            if (sub.getPlugin().isIllegalOnSubmarket(st, SubmarketPlugin.TransferAction.PLAYER_SELL)) { b.append(name).append(": can't be sold here\n"); continue; }
            int n = (int) Math.floor(st.getSize());
            if (n < 1) continue;
            float unit = st.getBaseValuePerUnit() * mult * (1f - sub.getTariff());
            total += unit * n;
            count += n;
            b.append(n).append(" x ").append(name).append(" @ ").append(n(unit)).append(" = ").append(n(unit * n)).append("\n");
            if (!confirm) continue;
            if (st.isWeaponStack()) { pf().getCargo().removeWeapons(id, n); sub.getCargo().addWeapons(id, n); tx.getSold().addWeapons(id, n); }
            else { pf().getCargo().removeFighters(id, n); sub.getCargo().addFighters(id, n); tx.getSold().addFighters(id, n); }
        }
        if (count == 0) return "no weapons or wings in cargo matching '" + key + "'" + (b.length() > 0 ? "\n" + b : "");
        if (!confirm) return "quote, " + sub.getNameOneLine() + " at " + m.getName() + " (" + Math.round(sub.getTariff() * 100f) + "% tariff): " + count + " items for about "
                + n(total) + " cr\n" + b + "send 'sellweapons " + key + (black ? " black" : "") + " confirm' to sell";
        pf().getCargo().getCredits().add(total);
        tx.setCreditValue(total);
        report(m, sub, mode[0], tx);
        EventLog.info("shipyard", "sold " + count + " weapons/wings at " + m.getName() + " for " + n(total) + " cr");
        return "sold " + count + " weapons/wings on the " + sub.getNameOneLine() + " for " + n(total) + " cr; credits " + n(pf().getCargo().getCredits().get()) + "\n" + b.toString().trim();
    }

    /**
     * 1.0.7: "flux <ship|all> fill" puts every free ordnance point into flux vents (up to the hull's limit), then
     * capacitors; "flux <ship> <vents> <caps>" sets them exactly (capped at the limits and the free OP).
     */
    static String flux(String args) {
        String a = args == null ? "" : args.trim();
        String[] w = a.split("\\s+");
        if (w.length < 2) return "usage: flux <ship|all> fill | flux <ship> <vents> <caps>";
        boolean fill = w[w.length - 1].equalsIgnoreCase("fill");
        Integer wantV = null, wantC = null;
        String shipQ;
        if (fill) {
            shipQ = a.substring(0, a.toLowerCase(Locale.ROOT).lastIndexOf("fill")).trim();
        } else {
            if (w.length < 3) return "usage: flux <ship> <vents> <caps>";
            try { wantV = Integer.parseInt(w[w.length - 2]); wantC = Integer.parseInt(w[w.length - 1]); }
            catch (NumberFormatException e) { return "usage: flux <ship> <vents> <caps>"; }
            StringBuilder q = new StringBuilder();
            for (int i = 0; i < w.length - 2; i++) q.append(i == 0 ? "" : " ").append(w[i]);
            shipQ = q.toString();
        }
        List<FleetMemberAPI> ships = new ArrayList<FleetMemberAPI>();
        if (shipQ.equalsIgnoreCase("all")) {
            for (FleetMemberAPI f : pf().getFleetData().getMembersListCopy()) if (!civilian(f) && !f.isMothballed()) ships.add(f);
        } else {
            FleetMemberAPI f = findMember(shipQ);
            if (f == null) return "no ship matching '" + shipQ + "'";
            ships.add(f);
        }
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        StringBuilder b = new StringBuilder();
        for (FleetMemberAPI m : ships) {
            ShipVariantAPI v = m.getVariant().clone();
            v.setSource(VariantSource.REFIT);
            if (v.isStockVariant()) v.setHullVariantId(Misc.genUID());
            com.fs.starfarer.api.combat.ShipAPI.HullSize size = v.getHullSize();
            int maxV = com.fs.starfarer.api.characters.SkillsChangeRemoveVentsCapsEffect.getMaxVents(size, stats);
            int maxC = com.fs.starfarer.api.characters.SkillsChangeRemoveVentsCapsEffect.getMaxCaps(size, stats);
            int maxOP = m.getHullSpec().getOrdnancePoints(stats);
            int beforeV = v.getNumFluxVents(), beforeC = v.getNumFluxCapacitors();
            if (fill) {
                int free = maxOP - v.computeOPCost(stats);
                int addV = Math.max(0, Math.min(free, maxV - v.getNumFluxVents()));
                v.setNumFluxVents(v.getNumFluxVents() + addV);
                free = maxOP - v.computeOPCost(stats);
                int addC = Math.max(0, Math.min(free, maxC - v.getNumFluxCapacitors()));
                v.setNumFluxCapacitors(v.getNumFluxCapacitors() + addC);
            } else {
                v.setNumFluxVents(0); v.setNumFluxCapacitors(0);
                int free = maxOP - v.computeOPCost(stats);
                int nv = Math.max(0, Math.min(Math.min(wantV, maxV), free));
                v.setNumFluxVents(nv);
                free = maxOP - v.computeOPCost(stats);
                v.setNumFluxCapacitors(Math.max(0, Math.min(Math.min(wantC, maxC), free)));
            }
            m.setVariant(v, false, true);
            try { m.updateStats(); } catch (Throwable ignored) {}
            b.append(m.getShipName()).append(": vents ").append(beforeV).append(" -> ").append(v.getNumFluxVents()).append(" (max ").append(maxV)
             .append("), caps ").append(beforeC).append(" -> ").append(v.getNumFluxCapacitors()).append(" (max ").append(maxC).append("); OP ")
             .append(v.computeOPCost(stats)).append("/").append(maxOP).append("\n");
        }
        pf().getFleetData().syncIfNeeded();
        return b.toString().trim();
    }

    /** 1.0.7: "loadout <ship|all>": weapons, wings, hullmods, d-mods, vents/caps and OP, read-only. */
    static String loadout(String q) {
        List<FleetMemberAPI> ships = new ArrayList<FleetMemberAPI>();
        String k = q == null ? "all" : q.trim();
        if (k.isEmpty() || k.equalsIgnoreCase("all")) { for (FleetMemberAPI f : pf().getFleetData().getMembersListCopy()) if (!civilian(f)) ships.add(f); }
        else { FleetMemberAPI f = findMember(k); if (f == null) return "no ship matching '" + k + "'"; ships.add(f); }
        MutableCharacterStatsAPI stats = Global.getSector().getPlayerStats();
        StringBuilder b = new StringBuilder();
        for (FleetMemberAPI m : ships) {
            ShipVariantAPI v = m.getVariant();
            Map<String, Integer> wc = new LinkedHashMap<String, Integer>();
            for (String slot : v.getFittedWeaponSlots()) {
                WeaponSpecAPI ws = Global.getSettings().getWeaponSpec(v.getWeaponId(slot));
                String nm = ws == null ? v.getWeaponId(slot) : ws.getWeaponName();
                Integer c = wc.get(nm); wc.put(nm, c == null ? 1 : c + 1);
            }
            StringBuilder mods = new StringBuilder(), dm = new StringBuilder();
            for (String id : v.getHullMods()) {
                HullModSpecAPI sp = Global.getSettings().getHullModSpec(id);
                String nm = sp == null ? id : sp.getDisplayName();
                if (sp != null && sp.hasTag(Tags.HULLMOD_DMOD)) dm.append(dm.length() == 0 ? "" : ", ").append(nm);
                else mods.append(mods.length() == 0 ? "" : ", ").append(nm).append(v.getHullSpec().isBuiltInMod(id) ? " (built-in)" : "");
            }
            b.append(m.getShipName()).append(" | ").append(m.getHullSpec().getHullName()).append(" | OP ").append(v.computeOPCost(stats)).append("/")
             .append(m.getHullSpec().getOrdnancePoints(stats)).append(" | vents ").append(v.getNumFluxVents()).append(" caps ").append(v.getNumFluxCapacitors())
             .append("\n  weapons: ").append(wc).append("\n  wings: ").append(v.getWings()).append("\n  hullmods: ").append(mods)
             .append(dm.length() > 0 ? "\n  d-mods: " + dm : "").append("\n");
        }
        StringBuilder known = new StringBuilder();
        for (String id : Global.getSector().getPlayerFaction().getKnownHullMods()) {
            HullModSpecAPI sp = Global.getSettings().getHullModSpec(id);
            if (sp == null || sp.isHidden() || sp.isHiddenEverywhere()) continue;
            known.append(known.length() == 0 ? "" : ", ").append(sp.getDisplayName());
        }
        b.append("known hullmods: ").append(known);
        return b.toString();
    }
}
