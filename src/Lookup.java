package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fuzzy lookup of game ids from ids or display names. */
public final class Lookup {

    private Lookup() {}

    /** A candidate the user might mean. */
    public static class Match {
        public final String kind;   // commodity, weapon, wing, hullmod, special, hull, variant, market, system, entity, faction
        public final String id;
        public final String name;
        public final Object ref;
        public int score;

        public Match(String kind, String id, String name, Object ref) {
            this.kind = kind; this.id = id; this.name = name; this.ref = ref;
        }

        public String toString() { return name + " [" + kind + ": " + id + "]"; }
    }

    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        String l = s.toLowerCase();
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.toString();
    }

    public static int score(String query, String id, String name) {
        String q = norm(query);
        if (q.length() == 0) return 0;
        String i = norm(id), n = norm(name);
        if (q.equals(i)) return 100;
        if (q.equals(n)) return 95;
        if (i.startsWith(q)) return 70;
        if (n.startsWith(q)) return 65;
        if (i.contains(q)) return 45;
        if (n.contains(q)) return 40;
        // every query word appears in the name
        String[] words = query.toLowerCase().split("[^a-z0-9]+");
        boolean all = words.length > 1;
        String nl = (name == null ? "" : name.toLowerCase()) + " " + (id == null ? "" : id.toLowerCase());
        for (String w : words) {
            if (w.length() == 0) continue;
            if (!nl.contains(w)) { all = false; break; }
        }
        return all ? 30 : 0;
    }

    public static List<Match> rank(String query, List<Match> all) {
        List<Match> out = new ArrayList<Match>();
        for (Match m : all) {
            m.score = score(query, m.id, m.name);
            if (m.score > 0) out.add(m);
        }
        Collections.sort(out, new Comparator<Match>() {
            public int compare(Match a, Match b) {
                if (a.score != b.score) return b.score - a.score;
                int la = a.name == null ? 999 : a.name.length();
                int lb = b.name == null ? 999 : b.name.length();
                return la - lb;
            }
        });
        return out;
    }

    /** Best match or an exception listing what was close. */
    public static Match best(String query, List<Match> all, String what) throws CommandException {
        List<Match> r = rank(query, all);
        if (r.isEmpty()) throw new CommandException("No " + what + " matching '" + query + "'. Try: list " + what + " " + query);
        Match top = r.get(0);
        if (top.score < 90 && r.size() > 1 && r.get(1).score == top.score
                && !norm(r.get(1).name).equals(norm(top.name))) {
            StringBuilder b = new StringBuilder("'" + query + "' is ambiguous. Did you mean:");
            for (int i = 0; i < Math.min(8, r.size()); i++) b.append("\n  ").append(r.get(i));
            throw new CommandException(b.toString());
        }
        return top;
    }

    // ---------- item catalogs ----------

    public static List<Match> commodities() {
        List<Match> l = new ArrayList<Match>();
        for (CommoditySpecAPI c : Global.getSettings().getAllCommoditySpecs()) {
            if (c.isMeta()) continue;
            l.add(new Match("commodity", c.getId(), c.getName(), c));
        }
        return l;
    }

    public static List<Match> weapons() {
        List<Match> l = new ArrayList<Match>();
        for (WeaponSpecAPI w : Global.getSettings().getAllWeaponSpecs()) {
            l.add(new Match("weapon", w.getWeaponId(), w.getWeaponName(), w));
        }
        return l;
    }

    public static List<Match> wings() {
        List<Match> l = new ArrayList<Match>();
        for (FighterWingSpecAPI w : Global.getSettings().getAllFighterWingSpecs()) {
            l.add(new Match("wing", w.getId(), w.getWingName(), w));
        }
        return l;
    }

    public static List<Match> hullmods() {
        List<Match> l = new ArrayList<Match>();
        for (HullModSpecAPI h : Global.getSettings().getAllHullModSpecs()) {
            if (h.isHiddenEverywhere()) continue;
            l.add(new Match("hullmod", h.getId(), h.getDisplayName(), h));
        }
        return l;
    }

    public static List<Match> specials() {
        List<Match> l = new ArrayList<Match>();
        for (SpecialItemSpecAPI s : Global.getSettings().getAllSpecialItemSpecs()) {
            l.add(new Match("special", s.getId(), s.getName(), s));
        }
        return l;
    }

    public static List<Match> hulls() {
        List<Match> l = new ArrayList<Match>();
        for (ShipHullSpecAPI h : Global.getSettings().getAllShipHullSpecs()) {
            if (h.getHints().contains(ShipHullSpecAPI.ShipTypeHints.MODULE)) continue;
            if (h.getHints().contains(ShipHullSpecAPI.ShipTypeHints.STATION)) continue;
            if (h.isDefaultDHull()) continue;
            l.add(new Match("hull", h.getHullId(), h.getHullName(), h));
        }
        return l;
    }

    /** Anything that can go in cargo, best match across all item types. */
    public static Match cargoItem(String query) throws CommandException {
        List<Match> all = new ArrayList<Match>();
        all.addAll(commodities());
        all.addAll(weapons());
        all.addAll(wings());
        all.addAll(specials());
        all.addAll(hullmods());
        return best(query, all, "items");
    }

    public static Match commodity(String query) throws CommandException {
        return best(query, commodities(), "commodities");
    }

    // ---------- ships ----------

    private static Map<String, List<String>> variantsByHull = null;

    public static List<String> variantsFor(String hullId) {
        if (variantsByHull == null) {
            variantsByHull = new HashMap<String, List<String>>();
            SettingsAPI s = Global.getSettings();
            for (String vid : s.getAllVariantIds()) {
                try {
                    ShipVariantAPI v = s.getVariant(vid);
                    if (v == null || v.getHullSpec() == null) continue;
                    String hid = v.getHullSpec().getHullId();
                    List<String> list = variantsByHull.get(hid);
                    if (list == null) { list = new ArrayList<String>(); variantsByHull.put(hid, list); }
                    list.add(vid);
                } catch (Throwable ignored) {}
            }
            for (List<String> list : variantsByHull.values()) Collections.sort(list);
        }
        List<String> l = variantsByHull.get(hullId);
        return l == null ? new ArrayList<String>() : l;
    }

    /** Resolve a hull name, hull id, or variant id to a variant id. */
    public static String shipVariant(String query) throws CommandException {
        SettingsAPI s = Global.getSettings();
        String q = query.trim();
        boolean empty = false;
        if (q.toLowerCase().endsWith(" empty")) { empty = true; q = q.substring(0, q.length() - 6).trim(); }
        // exact variant id
        for (String vid : s.getAllVariantIds()) {
            if (vid.equalsIgnoreCase(q)) return vid;
        }
        Match hull = best(q, hulls(), "ships");
        String hullId = hull.id;
        if (empty) return hullId + "_Hull";
        List<String> vs = variantsFor(hullId);
        String pick = null;
        String[] prefs = {"_Standard", "_Balanced", "_Assault", "_Elite", "_Support", "_Strike"};
        for (String p : prefs) {
            for (String v : vs) if (v.endsWith(p)) { pick = v; break; }
            if (pick != null) break;
        }
        if (pick == null) {
            for (String v : vs) if (!v.endsWith("_Hull")) { pick = v; break; }
        }
        if (pick == null) pick = hullId + "_Hull";
        return pick;
    }

    // ---------- factions ----------

    public static FactionAPI faction(String query) throws CommandException {
        List<Match> l = new ArrayList<Match>();
        for (FactionAPI f : Global.getSector().getAllFactions()) {
            l.add(new Match("faction", f.getId(), f.getDisplayName(), f));
        }
        return (FactionAPI) best(query, l, "factions").ref;
    }

    // ---------- places ----------

    public static List<Match> places() {
        SectorAPI sector = Global.getSector();
        List<Match> l = new ArrayList<Match>();
        Map<String, Boolean> seen = new HashMap<String, Boolean>();
        for (MarketAPI m : sector.getEconomy().getMarketsCopy()) {
            if (m.isHidden() || m.getPrimaryEntity() == null) continue;
            SectorEntityToken e = m.getPrimaryEntity();
            seen.put(e.getId(), Boolean.TRUE);
            l.add(new Match("market", e.getId(), m.getName(), e));
        }
        for (StarSystemAPI sys : sector.getStarSystems()) {
            l.add(new Match("system", sys.getId(), sys.getBaseName(), sys));
            for (PlanetAPI p : sys.getPlanets()) {
                if (seen.containsKey(p.getId())) continue;
                seen.put(p.getId(), Boolean.TRUE);
                l.add(new Match(p.isStar() ? "star" : "planet", p.getId(), p.getName(), p));
            }
            // 1.0.7: stations by name (Galatia Academy and other stations whose market is hidden from the economy list)
            try {
                for (SectorEntityToken e : sys.getEntitiesWithTag(com.fs.starfarer.api.impl.campaign.ids.Tags.STATION)) {
                    if (e == null || e.getName() == null || seen.containsKey(e.getId())) continue;
                    if (e.isDiscoverable()) continue; // not found yet
                    seen.put(e.getId(), Boolean.TRUE);
                    l.add(new Match("station", e.getId(), e.getName(), e));
                }
            } catch (Throwable ignored) {}
        }
        LocationAPI here = sector.getCurrentLocation();
        if (here != null) {
            for (SectorEntityToken e : here.getAllEntities()) {
                if (e == sector.getPlayerFleet() || e.getName() == null) continue;
                if (seen.containsKey(e.getId())) continue;
                seen.put(e.getId(), Boolean.TRUE);
                l.add(new Match("nearby", e.getId(), e.getName(), e));
            }
        }
        return l;
    }

    /**
     * 1.1.8 (Nov 2): "near <place> [n]" - markets closest to a system, market or planet, with the fuel they sell
     * and whether they'd trade with us. Live: a bounty in a market-less system needed a refuel stop near it.
     */
    public static String near(String args) throws CommandException {
        String q = args == null ? "" : args.trim();
        int n = 10;
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("^(.*?)\\s+(\\d+)$").matcher(q);
        if (mm.find()) { q = mm.group(1).trim(); n = Math.max(1, Math.min(40, Integer.parseInt(mm.group(2)))); }
        if (q.isEmpty()) throw new CommandException("usage: near <system|market|planet> [n]");
        SectorEntityToken from = place(q);
        final Map<MarketAPI, Float> dist = new HashMap<MarketAPI, Float>();
        List<MarketAPI> ms = new ArrayList<MarketAPI>();
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || m.getPrimaryEntity() == null) continue;
            dist.put(m, com.fs.starfarer.api.util.Misc.getDistanceLY(from, m.getPrimaryEntity()));
            ms.add(m);
        }
        Collections.sort(ms, new Comparator<MarketAPI>() {
            public int compare(MarketAPI a, MarketAPI b) { return Float.compare(dist.get(a), dist.get(b)); }
        });
        StringBuilder b = new StringBuilder("markets nearest " + from.getName() + ":");
        for (int i = 0; i < Math.min(n, ms.size()); i++) {
            MarketAPI m = ms.get(i);
            FactionAPI f = m.getFaction();
            String rel;
            if (f.isHostileTo("player")) rel = "HOSTILE";
            else if (f.isAtBest(com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER, com.fs.starfarer.api.campaign.RepLevel.INHOSPITABLE) && !m.isFreePort()) rel = "won't dock";
            else rel = "ok";
            float fuel = Trader.liveStock(m, false, "fuel") + Trader.liveStock(m, true, "fuel");
            b.append("\n  ").append(m.getName()).append(" (").append(m.getStarSystem() == null ? "?" : m.getStarSystem().getBaseName())
             .append(", ").append(f.getDisplayName()).append(", size ").append(m.getSize()).append(") | ")
             .append(String.format(java.util.Locale.ROOT, "%.1f", dist.get(m))).append(" ly | ").append(rel)
             .append(" | fuel stock ").append(Math.round(fuel));
        }
        return b.toString();
    }

    /** Resolve a place name to a token that a course can be laid in to. */
    public static SectorEntityToken place(String query) throws CommandException {
        SectorAPI sector = Global.getSector();
        if (query.trim().equalsIgnoreCase("objective") || query.trim().equalsIgnoreCase("quest")) {
            // where the game's own Intel screen points for the top important mission
            for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin i : sector.getIntelManager().getIntel()) {
                try {
                    if (i.isHidden() || i.isEnded() || !i.isImportant()) continue;
                    SectorEntityToken where = i.getMapLocation(null);
                    if (where != null) return where;
                } catch (Throwable ignored) {}
            }
            throw new CommandException("no active objective with a map location");
        }
        SectorEntityToken byId = sector.getEntityById(query.trim());
        if (byId != null) return byId;
        Match m = best(query, places(), "places");
        if (m.ref instanceof StarSystemAPI) {
            StarSystemAPI sys = (StarSystemAPI) m.ref;
            if (!sys.getJumpPoints().isEmpty()) return sys.getJumpPoints().get(0);
            if (sys.getStar() != null) return sys.getStar();
            return sys.getCenter();
        }
        return (SectorEntityToken) m.ref;
    }
}
