package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.shared.PlayerTradeDataForSubmarket;
import com.fs.starfarer.api.impl.campaign.shared.SharedData;
import com.fs.starfarer.api.impl.campaign.submarkets.BaseSubmarketPlugin;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the sector into the planner's model: every market you could trade at,
 * open and black market prices (tariffs included), estimated stock, legality,
 * free ports, patrols and suspicion; and the fleet's credits, hold, fuel,
 * supply use and speed. Read-only: nothing here changes the game.
 */
public final class GameReader {

    private GameReader() {}

    /** Goods that never go in the hold as trade cargo. Fuel is kept for its price only. */
    static boolean skip(String id) { return id.equals("crew") || id.equals("marines"); }

    public static final class Snapshot {
        public final List<Planner.Node> nodes = new ArrayList<Planner.Node>();
        public final Map<String, Planner.Good> goods = new HashMap<String, Planner.Good>();
        public final Planner.Fleet fleet = new Planner.Fleet();
        public final Map<String, MarketAPI> markets = new HashMap<String, MarketAPI>();
        public GamePrices prices;
        public long readMs;
    }

    /** Prices straight from the game's markets: total for a quantity, tariff of that submarket included. Memoized per read. */
    public static final class GamePrices implements Planner.Prices {
        final Map<String, MarketAPI> markets;
        final Map<String, Float> memo = new HashMap<String, Float>();
        public int calls;
        GamePrices(Map<String, MarketAPI> markets) { this.markets = markets; }

        static float tariff(MarketAPI m, boolean black) {
            SubmarketAPI s = m.getSubmarket(black ? Submarkets.SUBMARKET_BLACK : Submarkets.SUBMARKET_OPEN);
            if (s != null) return s.getTariff();
            return black ? 0f : m.getTariff().getModifiedValue();
        }

        public float buyTotal(String node, String good, float qty, boolean black) {
            String k = "b" + node + "|" + good + "|" + qty + "|" + black;
            Float v = memo.get(k);
            if (v != null) return v;
            MarketAPI m = markets.get(node);
            float r = 0f;
            if (m != null && qty > 0f) { calls++; r = m.getSupplyPrice(good, qty, true) * (1f + tariff(m, black)); }
            memo.put(k, r);
            return r;
        }

        public float sellTotal(String node, String good, float qty, boolean black) {
            String k = "s" + node + "|" + good + "|" + qty + "|" + black;
            Float v = memo.get(k);
            if (v != null) return v;
            MarketAPI m = markets.get(node);
            float r = 0f;
            if (m != null && qty > 0f) { calls++; r = m.getDemandPrice(good, qty, true) * (1f - tariff(m, black)); }
            memo.put(k, r);
            return r;
        }
    }

    // ------------------------------------------------------------------ markets

    /** True for markets the player can dock and trade at, now. */
    static boolean usable(MarketAPI m) {
        if (m == null || m.isHidden() || m.isPlanetConditionMarketOnly() || m.isPlayerOwned()) return false;
        SectorEntityToken e = m.getPrimaryEntity();
        if (e == null || e.isDiscoverable()) return false;
        if (m.getFaction() == null || m.getFaction().isHostileTo(Factions.PLAYER)) return false;
        return m.getSubmarket(Submarkets.SUBMARKET_OPEN) != null || m.getSubmarket(Submarkets.SUBMARKET_BLACK) != null;
    }

    /** Estimated stock when the fleet arrives: what is there, plus the refill since the last visit (1/30 of the cap a day). */
    static float stock(MarketAPI m, SubmarketAPI sub, String id, float daysAway) {
        if (sub == null) return 0f;
        CargoAPI c = sub.getCargoNullOk();
        float cur = c == null ? 0f : c.getCommodityQuantity(id);
        try {
            SubmarketPlugin pl = sub.getPlugin();
            if (pl instanceof BaseSubmarketPlugin) {
                BaseSubmarketPlugin bp = (BaseSubmarketPlugin) pl;
                CommodityOnMarketAPI com = m.getCommodityData(id);
                if (com == null || !bp.shouldHaveCommodity(com)) return cur;
                float limit = bp.getStockpileLimit(com);
                if (cur >= limit) return cur;
                float since = (c == null ? 60f : bp.getSinceLastCargoUpdate()) + Math.max(0f, daysAway);
                float add = limit / 30f * bp.getStockpilingAddRateMult(com) * since;
                return Math.min(limit, cur + add);
            }
        } catch (Throwable ignored) {}
        return cur;
    }

    /**
     * A market whose faction's patrols care about the transponder (not a free port, not a faction that allows
     * dark trade, not the player's own). Its patrols come and go, so a plan made while they're away still counts them.
     */
    static boolean strict(MarketAPI m) {
        try {
            if (m == null || m.isHidden() || m.isPlayerOwned() || m.isFreePort()) return false;
            if (m.getFaction().getCustomBoolean(Factions.CUSTOM_ALLOWS_TRANSPONDER_OFF_TRADE)) return false;
            return !m.getFaction().isHostileTo(Factions.PLAYER);
        } catch (Throwable t) { return false; }
    }

    /** Strict markets in the same location, other than this one. */
    static int strictMarkets(LocationAPI loc, MarketAPI self) {
        if (loc == null || loc.isHyperspace()) return 0;
        int c = 0;
        try {
            for (MarketAPI o : Global.getSector().getEconomy().getMarkets(loc)) if (o != self && strict(o)) c++;
        } catch (Throwable ignored) {}
        return c;
    }

    /** Patrols in a location that would bother a fleet flying with its transponder off. */
    static int patrols(LocationAPI loc, Map<LocationAPI, Integer> cache) {
        if (loc == null || loc.isHyperspace()) return 0;
        Integer n = cache.get(loc);
        if (n != null) return n;
        int count = 0;
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        try {
            for (CampaignFleetAPI f : loc.getFleets()) {
                if (f == pf || !Misc.isPatrol(f)) continue;
                if (f.isHostileTo(pf)) continue;
                if (!Misc.caresAboutPlayerTransponder(f)) continue;
                count++;
            }
        } catch (Throwable ignored) {}
        cache.put(loc, count);
        return count;
    }

    /** The game's smuggling-suspicion inputs for a market, read without creating tracking entries. */
    static void suspicion(MarketAPI m, Planner.Node n) {
        float smug = 0f, trade = 0f;
        try {
            Map<SubmarketAPI, PlayerTradeDataForSubmarket> all = SharedData.getData().getPlayerActivityTracker().getSubmarketTradeData();
            for (SubmarketAPI s : m.getSubmarketsCopy()) {
                if (!s.getPlugin().isParticipatesInEconomy()) continue;
                PlayerTradeDataForSubmarket d = all.get(s);
                if (d == null) continue;
                if (s.getFaction().isHostileTo(m.getFaction()) || s.getPlugin().isBlackMarket()) smug += d.getTotalPlayerTradeValue();
                else trade += d.getTotalPlayerTradeValue();
            }
        } catch (Throwable ignored) {}
        n.smuggleTotal = smug;
        n.tradeTotal = trade;
        float lvl = 0f;
        if (smug + trade > 0f) lvl = smug / (smug + trade) * Math.min(1f, (smug + trade) / (10000f * Math.max(1, m.getSize())));
        try { lvl += m.getMemoryWithoutUpdate().getFloat(MemFlags.MARKET_EXTRA_SUSPICION); } catch (Throwable ignored) {}
        n.suspicion = Math.min(1f, lvl);
    }

    static void jump(LocationAPI loc, Vector2f at, Object[] out) {
        out[0] = 0f; out[1] = "";
        if (loc == null || loc.isHyperspace()) return;
        float best = Float.MAX_VALUE;
        String name = "";
        try {
            for (SectorEntityToken jp : loc.getJumpPoints()) {
                float d = Misc.getDistance(at, jp.getLocation());
                if (d < best) { best = d; name = jp.getName(); }
            }
        } catch (Throwable ignored) {}
        if (best < Float.MAX_VALUE) { out[0] = best; out[1] = name; }
    }

    /** The market the player is docked at (an open dialog whose target has a market), or null. */
    public static MarketAPI dockedMarket() {
        try {
            InteractionDialogAPI d = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
            if (d == null || d.getInteractionTarget() == null) return null;
            MarketAPI m = d.getInteractionTarget().getMarket();
            return usable(m) ? m : null;
        } catch (Throwable t) { return null; }
    }

    public static Snapshot read() {
        long t0 = System.currentTimeMillis();
        Snapshot sn = new Snapshot();
        CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
        readFleet(pf, sn.fleet);

        for (String id : Global.getSector().getEconomy().getAllCommodityIds()) {
            CommoditySpecAPI spec = Global.getSector().getEconomy().getCommoditySpec(id);
            if (spec == null || spec.isMeta() || spec.isNonEcon() || spec.isPersonnel() || skip(id)) continue;
            Planner.Good g = new Planner.Good(id, spec.getName(), spec.getCargoSpace(), spec.getBasePrice());
            g.fleetSupply = id.equals("supplies");
            // 1.0.9 (audit H3): supplies are never trade cargo while the player wants to be asked before every supply buy
            g.tradeable = !spec.isFuel() && !id.equals("fuel") && !id.equals("supplies");
            sn.goods.put(id, g);
        }

        Map<LocationAPI, Integer> patrolCache = new HashMap<LocationAPI, Integer>();
        Object[] jo = new Object[2];
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            try {
                if (!usable(m)) continue;
                SectorEntityToken e = m.getPrimaryEntity();
                Planner.Node n = new Planner.Node(m.getId(), m.getName());
                LocationAPI loc = e.getContainingLocation();
                boolean hyper = loc == null || loc.isHyperspace();
                n.systemId = hyper ? null : loc.getId();
                n.systemName = hyper ? "hyperspace" : (m.getStarSystem() != null ? m.getStarSystem().getBaseName() : loc.getName());
                n.faction = m.getFactionId();
                n.factionName = m.getFaction().getDisplayName();
                n.freePort = m.isFreePort();
                n.tolerant = m.getFaction().getCustomBoolean(Factions.CUSTOM_ALLOWS_TRANSPONDER_OFF_TRADE);
                n.inhospitable = m.getFaction().isAtBest(Factions.PLAYER, RepLevel.INHOSPITABLE);
                Vector2f h = m.getLocationInHyperspace();
                n.hx = h.x; n.hy = h.y;
                n.x = e.getLocation().x; n.y = e.getLocation().y;
                jump(loc, e.getLocation(), jo);
                n.jumpDist = (Float) jo[0]; n.jumpName = (String) jo[1];
                n.size = m.getSize();
                n.stability = m.getStabilityValue();
                n.patrols = patrols(loc, patrolCache);
                n.strictNearby = strictMarkets(loc, m);
                n.patrols = Math.max(n.patrols, n.strictNearby + (strict(m) ? 1 : 0));
                suspicion(m, n);
                SubmarketAPI open = m.getSubmarket(Submarkets.SUBMARKET_OPEN);
                SubmarketAPI black = m.getSubmarket(Submarkets.SUBMARKET_BLACK);
                n.hasOpen = open != null;
                // 1.1.35: a banned black market (player rule) is not planned on at all (Nov 5: the loop chose Morga, Tri-Tachyon)
                n.hasBlack = black != null && !Shipyard.blackBanned(m);

                Planner.Fleet f = sn.fleet;
                float daysAway = m.getId().equals(f.atNode) ? 0f : Planner.travelDays(
                        Planner.ly(f.systemId, f.hx, f.hy, n.systemId, n.hx, n.hy),
                        Planner.inSystem(f.systemId, f.x, f.y, f.jumpDist, n.systemId, n.x, n.y, n.jumpDist), f.burn);

                float openT = open == null ? 0.3f : open.getTariff();
                float blackT = black == null ? 0f : black.getTariff();
                for (Planner.Good g : sn.goods.values()) {
                    CommodityOnMarketAPI com = m.getCommodityData(g.id);
                    if (com == null) continue;
                    Planner.Q q = new Planner.Q();
                    q.illegal = m.isIllegal(g.id);
                    if (q.illegal) g.illegalAt++;
                    float sup = m.getSupplyPrice(g.id, 1, true), dem = m.getDemandPrice(g.id, 1, true);
                    if (open != null) {
                        SubmarketPlugin op = open.getPlugin();
                        if (!op.isIllegalOnSubmarket(g.id, SubmarketPlugin.TransferAction.PLAYER_BUY)) q.openBuy = sup * (1f + openT);
                        if (!op.isIllegalOnSubmarket(g.id, SubmarketPlugin.TransferAction.PLAYER_SELL)) q.openSell = dem * (1f - openT);
                        q.openStock = stock(m, open, g.id, daysAway);
                    }
                    if (black != null) {
                        SubmarketPlugin bp = black.getPlugin();
                        if (!bp.isIllegalOnSubmarket(g.id, SubmarketPlugin.TransferAction.PLAYER_BUY)) q.blackBuy = sup * (1f + blackT);
                        if (!bp.isIllegalOnSubmarket(g.id, SubmarketPlugin.TransferAction.PLAYER_SELL)) q.blackSell = dem * (1f - blackT);
                        q.blackStock = stock(m, black, g.id, daysAway);
                    }
                    n.q.put(g.id, q);
                }
                // fuel and supplies prices for the trip's running costs (what you'd pay here)
                Planner.Q fq = n.q.get("fuel"), sq = n.q.get("supplies");
                if (fq != null) n.fuelPrice = fq.openBuy > 0f && !n.inhospitable && fq.openStock >= 1f ? fq.openBuy : (fq.blackStock >= 1f ? fq.blackBuy : -1f);
                if (sq != null) n.suppliesPrice = sq.openBuy > 0f && !n.inhospitable ? sq.openBuy : sq.blackBuy;
                sn.nodes.add(n);
                sn.markets.put(m.getId(), m);
            } catch (Throwable t) {
                Global.getLogger(GameReader.class).warn("Trade Runner: skipped market " + (m == null ? "?" : m.getName()), t);
            }
        }
        for (Planner.Good g : sn.goods.values()) g.illegalSomewhere = g.illegalAt >= Math.max(2, Math.round(sn.nodes.size() * 0.25f));
        sn.prices = new GamePrices(sn.markets);
        sn.readMs = System.currentTimeMillis() - t0;
        return sn;
    }

    static void readFleet(CampaignFleetAPI pf, Planner.Fleet f) {
        CargoAPI c = pf.getCargo();
        f.credits = c.getCredits().get();
        f.cargoFree = Math.max(0f, c.getMaxCapacity() - c.getSpaceUsed());
        f.fuel = c.getFuel();
        f.supplies = c.getSupplies();
        f.fuelMax = c.getMaxFuel();
        try { f.fuelPerLY = pf.getLogistics().getFuelCostPerLightYear(); } catch (Throwable ignored) {}
        float perDay = 0f;
        try {
            float perMonth = 0f;
            for (FleetMemberAPI fm : pf.getFleetData().getMembersListCopy()) {
                if (!fm.isMothballed()) perMonth += fm.getStats().getSuppliesPerMonth().getModifiedValue();
            }
            perDay = perMonth / 30f;
            perDay = Math.max(perDay, pf.getLogistics().getTotalSuppliesPerDay());
        } catch (Throwable ignored) {}
        f.suppliesPerDay = perDay;
        try { f.burn = Math.max(1f, Math.max(pf.getFleetData().getMinBurnLevel(), pf.getFleetData().getBurnLevel())); } catch (Throwable ignored) {}
        LocationAPI loc = pf.getContainingLocation();
        boolean hyper = loc == null || loc.isHyperspace();
        f.systemId = hyper ? null : loc.getId();
        f.systemName = hyper ? "hyperspace" : loc.getName();
        Vector2f h = pf.getLocationInHyperspace();
        f.hx = h.x; f.hy = h.y;
        f.x = pf.getLocation().x; f.y = pf.getLocation().y;
        Object[] jo = new Object[2];
        jump(loc, pf.getLocation(), jo);
        f.jumpDist = (Float) jo[0]; f.jumpName = (String) jo[1];
        MarketAPI docked = dockedMarket();
        f.atNode = docked == null ? null : docked.getId();
        for (CargoStackAPI s : c.getStacksCopy()) {
            if (s.isNull() || !s.isCommodityStack()) continue;
            String id = s.getCommodityId();
            if (skip(id) || id.equals("fuel") || id.equals("supplies")) continue;
            Float have = f.carried.get(id);
            f.carried.put(id, (have == null ? 0f : have) + s.getSize());
        }
    }

    /** Re-reads one market's transponder inputs (patrols, suspicion) right before a stop. */
    public static void refreshNode(MarketAPI m, Planner.Node n) {
        LocationAPI loc = m.getPrimaryEntity().getContainingLocation();
        n.patrols = patrols(loc, new HashMap<LocationAPI, Integer>());
        n.strictNearby = strictMarkets(loc, m);
        n.patrols = Math.max(n.patrols, n.strictNearby + (strict(m) ? 1 : 0));
        suspicion(m, n);
        n.inhospitable = m.getFaction().isAtBest(Factions.PLAYER, RepLevel.INHOSPITABLE);
    }
}
