package claudecaptain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The safety governor's decisions, without game classes (unit-tested). The Governor fills an Input
 * from the game every half second; check() lists the alarms that hold right now; Latch turns that
 * into "newly fired" alarms so one problem pauses the game once (until acknowledged by the next
 * 'run', or until it clears and comes back, or gets worse).
 */
public final class GovMath {

    private GovMath() {}

    public static final class Input {
        // fleet logistics
        public float supplies, maintPerDay, fuel, fuelPerLy = 1f;
        /** Light years to the nearest friendly market, or -1 when unknown/none. */
        public float nearestFriendlyLy = -1f;
        public String nearestFriendlyName = "";
        /** 1.0.7: jumps on the way there (each burns a light-year of fuel). */
        public int nearestFriendlyJumps = 0;
        public boolean docked, inBattle, inDialog;
        // combat readiness
        public float lowestCombatCR = 1f;
        public String lowestCRShip = "";
        /** The plan is resting or repairing (or right after a fight): low CR is expected. */
        public boolean lowCrExpected;
        // threats
        public float ourDP;
        public float threatDP;
        public String threatName = "";
        public boolean threatClosing;
        // incidents since the last check
        public int shipsLostOutsideBattle;
        public float worstHullDropOutsideBattle;
        public String incidentShip = "";
        /** 1.0.9: supplies used per day right now, repairs and CR recovery included. */
        public float burnPerDay;
        /** 1.0.9: CR-draining terrain the fleet is in (event horizon, corona, pulsar beam), and for how long. */
        public String hazard;
        public float hazardDays;
        // rules
        public float minSupplyDays = 15f, fuelMarginPct = 25f, fuelMarginLy = 2f, minCR = 0.40f, threatRatio = 1.0f;
    }

    public static final class Alarm {
        public final String key, text;
        Alarm(String key, String text) { this.key = key; this.text = text; }
        public String toString() { return key + ": " + text; }
    }

    public static float supplyDays(float supplies, float perDay) {
        if (perDay <= 0.0001f) return 9999f;
        return supplies / perDay;
    }

    /** 0 fine, 1 under the minimum, 2 under half of it, 3 under a quarter, 4 empty. Each tier fires again. */
    public static int supplyTier(float days, float min) {
        if (days <= 0.01f) return 4;
        if (days < min / 4f) return 3;
        if (days < min / 2f) return 2;
        if (days < min) return 1;
        return 0;
    }

    /** Fuel to reach a market ly away with the margin rules. */
    public static float fuelNeeded(float ly, float fuelPerLy, float marginPct, float marginLy) {
        if (ly < 0f) return 0f;
        return (ly * (1f + marginPct / 100f) + marginLy) * Math.max(0f, fuelPerLy);
    }

    /** 1.1.0 (Nov 2): what the supply guard does with CR recovery this check. */
    public enum Guard { NONE, SUSPEND, RESUME }

    /**
     * 1.1.0 (Nov 2): after the Silva fight CR recovery burned 111 supplies/day and emptied the hold on the way to a market,
     * while the alarm only logged "routine: already heading to a market". The guard suspends CR recovery when the hold
     * would last fewer than warnDays at the current burn (not while docked: the player is buying), and resumes the ships
     * it suspended once the hold covers the remaining recovery plus minDays of maintenance.
     */
    public static Guard supplyGuard(float supplies, float burnPerDay, float maintPerDay, float crRecoveryNeed,
                                    boolean docked, boolean anySuspendedByGuard, float warnDays, float minDays) {
        return supplyGuard(supplies, burnPerDay, maintPerDay, crRecoveryNeed, docked, anySuspendedByGuard, warnDays, minDays, 0f);
    }

    /**
     * burnAtSuspend: the burn that made the guard suspend. Resuming also needs twice warnDays of that burn: the CR estimate
     * leaves out hull repairs, and without this the guard flipped every half second on Nov 2 (resume, burn 105/day, suspend).
     */
    public static Guard supplyGuard(float supplies, float burnPerDay, float maintPerDay, float crRecoveryNeed,
                                    boolean docked, boolean anySuspendedByGuard, float warnDays, float minDays, float burnAtSuspend) {
        if (anySuspendedByGuard) {
            // 1.1.24 (Jan 25 c216 live): docked at a market the player can buy what the recovery needs. Before this the
            // guard held every ship suspended from Aug 1 c215 to Jan 25 c216: the hold never covered the whole recovery.
            if (docked) return Guard.RESUME;
            if (supplies >= crRecoveryNeed + maintPerDay * minDays && supplies >= burnAtSuspend * warnDays * 2f) return Guard.RESUME;
            return Guard.NONE;
        }
        if (docked) return Guard.NONE;
        if (burnPerDay <= maintPerDay * 1.5f) return Guard.NONE;
        return supplyDays(supplies, burnPerDay) < warnDays ? Guard.SUSPEND : Guard.NONE;
    }

    /** 1.1.0: ships under this CR keep recovering even when the guard suspends the rest (0% CR means accidents). */
    public static final float GUARD_KEEP_CR = 0.15f;

    /**
     * 1.1.24 (Jan 25 c216 live): a ship the guard suspended above GUARD_KEEP_CR can fall below it later (event horizon,
     * emergency burns) and then sits at 0% CR having an accident a day. Such a ship gets its recovery back.
     */
    public static boolean guardReleases(float cr) { return cr < GUARD_KEEP_CR; }

    /** Days of supplies at the current burn below which the burn alarm fires. */
    public static final float BURN_WARN_DAYS = 7f;

    public static List<Alarm> check(Input in) {
        List<Alarm> out = new ArrayList<Alarm>();
        float days = supplyDays(in.supplies, in.maintPerDay);
        int tier = supplyTier(days, in.minSupplyDays);
        if (tier > 0) out.add(new Alarm("supplies:" + tier, tier == 4 ? "supplies are gone: ships lose CR and suffer accidents"
                : "supplies for " + Math.round(days) + " days of maintenance (minimum " + Math.round(in.minSupplyDays) + ")"));
        // 1.0.9 (Nov 1): repairs and CR recovery after a fight can burn 8x maintenance; warn before they empty the hold
        float burnDays = supplyDays(in.supplies, in.burnPerDay);
        if (tier == 0 && in.burnPerDay > in.maintPerDay * 1.5f && burnDays < BURN_WARN_DAYS)
            out.add(new Alarm("supplies:burn", "supplies last " + Math.round(burnDays) + " days at the current burn of "
                    + Math.round(in.burnPerDay) + "/day (repairs and CR recovery); maintenance alone is " + Math.round(in.maintPerDay) + "/day"));
        if (in.hazard != null && Hazard.loitering(in.hazardDays))
            out.add(new Alarm("hazard", "the fleet has been inside " + in.hazard + " for " + Math.round(in.hazardDays) + " day(s): it drains combat readiness"));
        if (!in.docked && in.nearestFriendlyLy >= 0f) {
            float need = TravelMath.fuelWithMargin(in.nearestFriendlyLy, in.nearestFriendlyJumps, in.fuelPerLy, in.fuelMarginPct, in.fuelMarginLy);
            if (in.fuel < need) out.add(new Alarm("fuel", "fuel " + Math.round(in.fuel) + " but reaching " + in.nearestFriendlyName + " ("
                    + String.format(java.util.Locale.ROOT, "%.1f", in.nearestFriendlyLy) + " ly) with margin needs " + Math.round(need)));
        }
        // 1.1.0: no longer dropped while docked - that cleared the acknowledgement at every market and re-fired it on undock
        if (!in.lowCrExpected && !in.inBattle && in.lowestCombatCR < in.minCR)
            out.add(new Alarm("cr", in.lowestCRShip + " is at " + Math.round(in.lowestCombatCR * 100f) + "% CR (minimum " + Math.round(in.minCR * 100f) + "%)"));
        if (in.threatDP > 0f && in.threatClosing && in.threatDP > in.ourDP * in.threatRatio)
            out.add(new Alarm("threat:" + in.threatName, in.threatName + " (" + Math.round(in.threatDP) + " DP vs our " + Math.round(in.ourDP) + ") is closing in"));
        if (in.shipsLostOutsideBattle > 0)
            out.add(new Alarm("incident:lost", in.shipsLostOutsideBattle + " ship(s) left the fleet outside battle"));
        if (in.worstHullDropOutsideBattle >= 0.02f)
            out.add(new Alarm("incident:hull", in.incidentShip + " lost " + Math.round(in.worstHullDropOutsideBattle * 100f) + "% hull outside battle (accident?)"));
        return out;
    }

    /** Remembers which alarms already fired and which were acknowledged. Incidents are one-shot. */
    public static final class Latch {
        private final Map<String, String> fired = new LinkedHashMap<String, String>();
        private final Set<String> acked = new HashSet<String>();
        private final Map<String, Integer> quiet = new HashMap<String, Integer>();
        private final Map<String, Integer> lapsed = new HashMap<String, Integer>();
        public static final int THREAT_ACK_GRACE = 120;

        /** Alarms that are active now and have not fired yet. Cleared alarms are forgotten. */
        public List<Alarm> update(List<Alarm> active) { return update(active, false); }

        /**
         * 1.1.29: as {@link #update(List)}, but a check made while the game is paused does not age the grace
         * counters: the game sits paused on an alarm until Claude answers, which can take minutes, and the
         * threat used to age out during that wait so the following 'run' acknowledged nothing.
         */
        public List<Alarm> update(List<Alarm> active, boolean paused) {
            Set<String> now = new HashSet<String>();
            List<Alarm> fresh = new ArrayList<Alarm>();
            for (Alarm a : active) {
                now.add(a.key);
                if (a.key.startsWith("incident:")) { fresh.add(a); continue; } // every incident is news
                if (!fired.containsKey(a.key) && !acked.contains(a.key) && !lapsed.containsKey(a.key)) { fresh.add(a); fired.put(a.key, a.text); }
                else if (lapsed.containsKey(a.key)) { fired.put(a.key, a.text); lapsed.remove(a.key); } // same threat, already reported
            }
            // 1.0.9: a threat that fired and then flickered off (often while the game sits paused waiting for Claude)
            // is remembered for THREAT_ACK_GRACE checks, so the acknowledgement that follows still covers it
            for (String k : new ArrayList<String>(fired.keySet()))
                if (!now.contains(k) && (k.startsWith("threat:") || k.equals("cr"))) lapsed.put(k, 0);
            for (java.util.Iterator<Map.Entry<String, Integer>> it = lapsed.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, Integer> e = it.next();
                if (now.contains(e.getKey())) continue;
                if (paused) continue; // 1.1.29: waiting for Claude doesn't age the threat
                e.setValue(e.getValue() + 1);
                if (e.getValue() >= THREAT_ACK_GRACE) it.remove();
            }
            fired.keySet().retainAll(now);
            // 1.0.9 (Nov 1): a threat's "closing in" flickers off while paused, which dropped the acknowledgement and
            // re-fired the same alarm after every 'run'. An acknowledged threat stays acknowledged until it has been
            // quiet for THREAT_ACK_GRACE checks in a row; other alarms are forgotten as soon as they clear.
            for (java.util.Iterator<String> it = acked.iterator(); it.hasNext();) {
                String k = it.next();
                if (now.contains(k)) { quiet.remove(k); continue; }
                if (k.startsWith("threat:") || k.equals("cr")) {
                    if (paused) continue; // 1.1.29
                    Integer q = quiet.get(k);
                    int n = q == null ? 1 : q + 1;
                    if (n < THREAT_ACK_GRACE) { quiet.put(k, n); continue; }
                }
                quiet.remove(k);
                it.remove();
            }
            return fresh;
        }

        /** 'run' acknowledges what has fired: those alarms stay quiet while they last. */
        public void ack() { acked.addAll(fired.keySet()); acked.addAll(lapsed.keySet()); fired.clear(); lapsed.clear(); }

        public List<String> active() { return new ArrayList<String>(fired.values()); }
        public Set<String> ackedKeys() { return new HashSet<String>(acked); }
    }

    /** The run budget: game days the campaign may run, optionally until a condition. */
    public static final class Budget {
        public float daysLeft = 0f;
        /** null, "arrived", "done" or "event". */
        public String until = null;
        public long untilEventAfter = 0L;

        public void grant(float days, String untilWhat) { daysLeft = Math.max(0f, days); until = untilWhat; }
        public void revoke() { daysLeft = 0f; until = null; }
        public boolean active() { return daysLeft > 0f; }
        /** Spends game time; true when this call used the last of it. */
        public boolean consume(float days) {
            if (daysLeft <= 0f) return false;
            daysLeft -= days;
            if (daysLeft <= 0f) { daysLeft = 0f; until = null; return true; }
            return false;
        }
        public String describe() {
            if (daysLeft <= 0f) return "no run budget";
            String d = daysLeft >= 10f ? String.valueOf(Math.round(daysLeft)) : String.format(java.util.Locale.ROOT, "%.1f", daysLeft);
            return d + "d left" + (until != null ? " (until " + until + ")" : "");
        }
    }

    /** Parses the arguments of 'run': "<days>", "<days>d", "until arrived|done|event [max <days>]". Returns {days, until} or throws. */
    /**
     * 1.0.5: alarms that don't need Claude: supply/fuel tiers while a goal already heads to a market (but not
     * "supplies are gone" without one), and hull scrapes outside battle smaller than pausePct (hyperspace storms).
     * Threats, low CR, ships lost and big hull losses always pause.
     */
    public static boolean routine(String key, boolean headingToMarket, float hullDrop, float pausePct) {
        if (key == null) return false;
        if (key.startsWith("supplies:") || key.equals("fuel")) return headingToMarket;
        if (key.equals("incident:hull")) return hullDrop < pausePct;
        return false;
    }

    public static Object[] parseRun(String args, float untilMax) {
        String a = args == null ? "" : args.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.startsWith("until")) {
            String[] p = a.split("\\s+");
            if (p.length < 2) throw new IllegalArgumentException("usage: run until arrived|done|event [max <days>]");
            String w = p[1];
            if (w.startsWith("arriv")) w = "arrived"; else if (w.startsWith("done") || w.startsWith("finish")) w = "done";
            else if (w.startsWith("event")) w = "event";
            else throw new IllegalArgumentException("run until arrived | done | event");
            float max = untilMax;
            for (int i = 2; i + 1 < p.length; i++) if (p[i].equals("max")) max = Float.parseFloat(p[i + 1].replace("d", ""));
            // 1.0.2: 'run until done 3' means max 3 days (1.0.1 ignored the bare number and granted the 60-day default)
            if (p.length == 3) {
                try { max = Float.parseFloat(p[2].replace("days", "").replace("day", "").replace("d", "")); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("usage: run until arrived|done|event [max <days>]"); }
            }
            return new Object[]{max, w};
        }
        String n = a.replace("days", "").replace("day", "").replace("d", "").trim();
        float days;
        try { days = Float.parseFloat(n); } catch (NumberFormatException e) { throw new IllegalArgumentException("usage: run <days> | run until arrived|done|event"); }
        if (days < 0f || days > 365f) throw new IllegalArgumentException("run budget must be 0-365 days");
        return new Object[]{days, null};
    }

    /** 1.1.11: true when ly light years at fuelPerLy (times margin) fit in the fuel aboard; same-system (0 ly) always fits. */
    public static boolean reachable(float ly, float fuelPerLy, float fuel, float margin) {
        if (ly <= 0.05f) return true;
        return ly * fuelPerLy * Math.max(1f, margin) <= fuel;
    }
}
