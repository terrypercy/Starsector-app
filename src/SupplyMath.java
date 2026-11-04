package claudecaptain;

/**
 * How many supplies and how much fuel a resupply buys (pure, unit-tested).
 *
 * Live play bought 629 supplies for 85k because the order was sized from the CURRENT burn, which is
 * inflated while ships recover CR. Here the order is sized from the MAINTENANCE burn (supplies per month
 * of non-mothballed ships / 30) for the target days, plus a one-off CR-recovery budget, minus what is
 * aboard, then capped by cargo room and by a share of credits.
 */
public final class SupplyMath {

    private SupplyMath() {}

    /**
     * 1.1.19 (player rule, Nov 3): true when factionId is in the comma/space separated list (case-insensitive).
     * Used for black_market_banned_factions: no black-market trade at markets of these factions.
     */
    public static boolean listed(String list, String factionId) {
        if (list == null || factionId == null) return false;
        for (String s : list.split("[,\\s]+")) {
            if (s.length() > 0 && s.equalsIgnoreCase(factionId)) return true;
        }
        return false;
    }

    /** Supplies needed to bring one ship from cr to maxCr: one deployment's supplies per deployment's worth of CR. */
    public static float crRecovery(float cr, float maxCr, float deployCostSupplies, float deployCostCr) {
        if (maxCr <= cr || deployCostCr <= 0.0001f) return 0f;
        return deployCostSupplies * (maxCr - cr) / deployCostCr;
    }

    public static final class Order {
        public float want, qty, cost;
        public String limitedBy = "";
        public String toString() {
            return Math.round(qty) + " (wanted " + Math.round(want) + ")" + (limitedBy.length() > 0 ? " limited by " + limitedBy : "") + ", ~" + Math.round(cost) + " cr";
        }
    }

    /**
     * @param maintPerDay maintenance burn (supplies/day)
     * @param days        days of maintenance to carry
     * @param crBudget    supplies for CR recovery (0 to skip)
     * @param have        supplies aboard
     * @param unitPrice   price per unit incl. tariff (approximate; the purchase uses the game's quote)
     * @param credits     credits now
     * @param maxCreditPct share of credits one resupply may use
     * @param room        cargo room (units of supplies)
     * @param stock       units available at the market (-1 = unknown)
     */
    public static Order supplies(float maintPerDay, float days, float crBudget, float have, float unitPrice,
                                 float credits, float maxCreditPct, float room, float stock) {
        Order o = new Order();
        o.want = Math.max(0f, (float) Math.ceil(maintPerDay * days + crBudget - have));
        float q = o.want;
        if (room >= 0f && q > room) { q = (float) Math.floor(room); o.limitedBy = "cargo room"; }
        if (stock >= 0f && q > stock) { q = (float) Math.floor(stock); o.limitedBy = "stock"; }
        if (unitPrice > 0f) {
            float cap = credits * maxCreditPct / 100f;
            if (q * unitPrice > cap) { q = (float) Math.floor(cap / unitPrice); o.limitedBy = "credits (" + Math.round(maxCreditPct) + "% rule)"; }
        }
        o.qty = Math.max(0f, q);
        o.cost = o.qty * Math.max(0f, unitPrice);
        return o;
    }

    /** Fuel to carry for ly light years, minus what's aboard, capped by tank room. */
    /** 1.0.4: light-years of fuel to top up to: the full target when fuel is cheap, only minLy above maxPrice per unit. */
    public static float fuelLy(float wantLy, float unitPrice, float maxPrice, float minLy) {
        if (maxPrice > 0f && unitPrice > maxPrice) return Math.min(wantLy, minLy);
        return wantLy;
    }

    /** 1.0.4: credits fuel may use: keep the reserve, and never more than pct of credits on hand. */
    public static float fuelBudget(float credits, float reserve, float pct) {
        float cap = pct > 0f ? credits * pct / 100f : credits;
        return Math.max(0f, Math.min(credits - reserve, cap));
    }

    public static float fuel(float ly, float fuelPerLy, float have, float maxFuel) {
        float target = Math.min(maxFuel, ly * fuelPerLy);
        return Math.max(0f, (float) Math.ceil(target - have));
    }
}
