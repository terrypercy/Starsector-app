package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.CommDirectoryEntryAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The goal engine. A goal runs frame by frame until done or failed and handles every dialog on its
 * way through the dialog rules (dialog_rules.txt) with its own context tags; what no rule covers
 * pauses the goal with the whole dialog in an ALERT event. Goals can nest (resupply travels first).
 */
public final class Goals {

    private static final Logger log = Global.getLogger(Goals.class);

    private Goals() {}

    static SectorAPI sector() { return Global.getSector(); }
    static CampaignFleetAPI pf() { return sector().getPlayerFleet(); }

    public abstract static class Goal {
        public String title = "";
        /** running | paused | done | failed */
        public volatile String state = "running";
        public volatile String progress = "";
        public volatile String result = "";
        public List<String> ctx = DialogAgent.ctx("travel");
        protected long waitUntil = 0L;
        protected int clicks = 0;
        protected SectorEntityToken bounty;
        protected Goal child;

        public boolean running() { return "running".equals(state); }
        public boolean finished() { return "done".equals(state) || "failed".equals(state); }
        /** Low CR is expected while this goal runs (resting, repairing). */
        public boolean lowCrOk() { return child != null && child.lowCrOk(); }
        public SectorEntityToken target() { return child != null ? child.target() : null; }

        protected void done(String msg) { state = "done"; result = msg; }
        protected void fail(String msg) { state = "failed"; result = msg; }
        protected void pause(String why) { state = "paused"; result = why; }
        public void resume() { if ("paused".equals(state)) { state = "running"; clicks = 0; waitUntil = 0L; if (child != null) child.resume(); } }
        public void cancel() { if (!finished()) fail("cancelled"); if (child != null) child.cancel(); }

        public String line() {
            String p = child != null && child.running() ? child.line() : progress;
            return title + (p.length() > 0 ? " - " + p : "");
        }

        /** Runs a child goal; returns true while it is still busy (the caller waits). */
        protected boolean runChild(long now) {
            if (child == null) return false;
            if ("paused".equals(child.state)) { pause(child.result); return true; }
            if (child.running()) { child.tick(now); if ("paused".equals(child.state)) pause(child.result); return true; }
            return false;
        }

        public abstract void tick(long now);

        /**
         * Deals with an open dialog that isn't this goal's own business. True when handled (or waiting
         * for the click to land); false when the goal paused on it.
         */
        protected boolean handleDialog(InteractionDialogAPI d, long now, SectorEntityToken goalTarget) {
            if (now < waitUntil) return true;
            if (clicks++ > 40) { pause("too many dialogs (" + clicks + ") - check the game"); return false; }
            AutoDialog.Act a = AutoDialog.handle(d, goalTarget, ctx, bounty);
            if (a != null) {
                EventLog.info("dialog", a.note);
                waitUntil = now + 1200L;
                return true;
            }
            if (orderJump(d)) { waitUntil = now + 1500L; return true; }
            String why = AutoDialog.lastWhy;
            EventLog.alert("dialog", "unrecognised dialog during '" + title + "': " + why + "\n" + DialogAgent.describe(d, ctx, goalTarget, bounty)
                    + "\nadd a line to dialog_rules.txt, or answer with 'choose <n>' then 'resume'");
            pause("unrecognised dialog: " + DialogAgent.targetName(d));
            return false;
        }
    }

    /** Trade Runner 1.0's fallback: order a jump on a single-destination jump point (no reflection; Enum.valueOf). */
    static boolean orderJump(InteractionDialogAPI d) {
        if (d == null || !(d.getInteractionTarget() instanceof com.fs.starfarer.api.campaign.JumpPointAPI)) return false;
        if (!(d.getPlugin() instanceof com.fs.starfarer.api.impl.campaign.JumpPointInteractionDialogPluginImpl)) return false;
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Enum> ids = (Class<Enum>) Class.forName("com.fs.starfarer.api.impl.campaign.JumpPointInteractionDialogPluginImpl$OptionId");
            Object j1 = Enum.valueOf(ids, "JUMP_1"), j2 = Enum.valueOf(ids, "JUMP_2");
            com.fs.starfarer.api.campaign.OptionPanelAPI op = d.getOptionPanel();
            if (op == null || !op.hasOption(j1) || op.hasOption(j2)) return false;
            float cost = Math.max(1f, Math.round(pf().getLogistics().getFuelCostPerLightYear()));
            if (pf().getCargo().getFuel() < cost) return false;
            d.getPlugin().optionSelected("Order the jump", j1);
            EventLog.info("dialog", "ordered the jump at " + d.getInteractionTarget().getName());
            return true;
        } catch (Throwable t) {
            log.warn("Claude Captain: jump order failed", t);
            return false;
        }
    }

    static boolean leaveMarket(InteractionDialogAPI d) {
        try {
            if (d.getOptionPanel().hasOption("marketLeave")) { d.getPlugin().optionSelected("Leave", "marketLeave"); return true; }
        } catch (Throwable ignored) {}
        for (AutoDialog.Opt o : AutoDialog.options(d)) if (o.enabled && !o.confirm && o.text.equalsIgnoreCase("Leave")) return AutoDialog.click(d, o);
        return false;
    }

    static MarketAPI dockedMarket() {
        InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
        if (d == null || d.getInteractionTarget() == null) return null;
        MarketAPI m = d.getInteractionTarget().getMarket();
        return m == null || m.isPlanetConditionMarketOnly() ? null : m;
    }

    // ================================================================== travel / dock / approach

    public static class Travel extends Goal {
        final SectorEntityToken dest;
        final boolean dock;
        final float within;
        long startTs, progressTs;
        float progressDist = Float.MAX_VALUE, bestLy = Float.MAX_VALUE;
        long bestLyTs = 0L;
        int stalls = 0, jumpsDone = 0, jumpsEst = 0, relays = 0, courseChanges = 0;
        LocationAPI lastLoc, progressLoc;
        float progressX, progressY;
        boolean started = false;

        public Travel(SectorEntityToken dest, boolean dock, float within) {
            this.dest = dest; this.dock = dock; this.within = within;
            title = (dock ? "Dock at " : "Travel to ") + dest.getName();
        }

        public SectorEntityToken target() { return dest; }

        static int jumpsBetween(LocationAPI a, LocationAPI b) {
            if (a == null || b == null || a == b) return 0;
            return (a.isHyperspace() ? 0 : 1) + (b.isHyperspace() ? 0 : 1);
        }

        long tvTs = 0L;
        int tvJumps = 0;

        /** Where to steer: a fleet in another system is reached through its star system first (the autopilot can't route to it). */
        SectorEntityToken steer() {
            LocationAPI there = dest.getContainingLocation();
            // 1.0.7: a deep-space system (no jump points) is entered with Transverse Jump at its nascent gravity well
            if (pf().isInHyperspace() && deepSpace(there) && Rules.b("transverse_in", true)) {
                com.fs.starfarer.api.campaign.NascentGravityWellAPI w = wellFor(dest);
                if (w != null) return w;
            }
            if (dest instanceof CampaignFleetAPI && there != null && there != pf().getContainingLocation() && there instanceof StarSystemAPI) {
                SectorEntityToken c = ((StarSystemAPI) there).getCenter();
                if (c != null) return c;
            }
            return dest;
        }

        void start() {
            started = true;
            startTs = sector().getClock().getTimestamp();
            progressTs = startTs;
            lastLoc = pf().getContainingLocation();
            progressLoc = lastLoc;
            progressX = pf().getLocation().x; progressY = pf().getLocation().y;
            jumpsEst = jumpsBetween(lastLoc, dest.getContainingLocation());
            try {
                com.fs.starfarer.api.characters.AbilityPlugin burn = pf().getAbility("sustained_burn");
                com.fs.starfarer.api.characters.AbilityPlugin dark = pf().getAbility("go_dark");
                boolean far = pf().getContainingLocation() != dest.getContainingLocation() || Misc.getDistance(pf(), dest) > 3000f;
                if (far && burn != null && !burn.isActive() && burn.isUsable() && (dark == null || !dark.isActive())) burn.activate();
            } catch (Throwable ignored) {}
            sector().layInCourseFor(steer());
            Governor.unpause();
            EventLog.info("travel", "course for " + dest.getName() + " (" + where(dest) + ", " + String.format(java.util.Locale.ROOT, "%.1f", Misc.getDistanceToPlayerLY(dest)) + " ly, ~" + jumpsEst + " jumps)");
        }

        public void tick(long now) {
            CampaignFleetAPI pf = pf();
            InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
            if (!started) {
                if (d != null && d.getInteractionTarget() == dest && dock) { done("already docked at " + dest.getName()); return; }
                if (d != null) {
                    if (now < waitUntil) return;
                    if (dockedMarket() != null && leaveMarket(d)) { waitUntil = now + 1000L; return; }
                    if (!handleDialog(d, now, dest)) return;
                    return;
                }
                start();
            }
            if (pf.getContainingLocation() != lastLoc) {
                jumpsDone++;
                lastLoc = pf.getContainingLocation();
                jumpsEst = Math.max(jumpsEst, jumpsDone + jumpsBetween(lastLoc, dest.getContainingLocation()));
                sector().layInCourseFor(steer());
                EventLog.info("travel", "now in " + lastLoc.getName() + " (" + jumpsDone + "/" + jumpsEst + " jumps)");
            }
            progress = jumpsDone + "/" + jumpsEst + " jumps, " + String.format(java.util.Locale.ROOT, "%.1f", Misc.getDistanceToPlayerLY(dest)) + " ly";
            if (d != null) {
                if (d.getInteractionTarget() == dest) { done((dock ? "docked at " : "arrived at ") + dest.getName()); Captain.markArrived(); return; }
                handleDialog(d, now, dest);
                return;
            }
            if (now < waitUntil) return;
            if (pf.getBattle() != null) { if (!BattleConfig.auto) pause("a battle started and auto-battle is off ('battle auto on')"); return; }
            if (dest instanceof CampaignFleetAPI && !((CampaignFleetAPI) dest).isAlive()) { fail(dest.getName() + " is gone"); return; }
            if (sector().isPaused()) Governor.unpause();
            boolean same = pf.getContainingLocation() == dest.getContainingLocation();
            if (transverse(pf, same, now)) return;
            float dist = Misc.getDistance(pf, dest) - dest.getRadius() - pf.getRadius();
            if (!dock) {
                float need = within > 0f ? within : 150f;
                if (same && dist <= need) { done("arrived at " + dest.getName() + " (" + Math.round(Math.max(0f, dist)) + " su)"); Captain.markArrived(); return; }
            } else if (same && dist <= 1500f && pf.getInteractionTarget() != dest) {
                if (++relays > 6) { pause("could not dock at " + dest.getName()); return; }
                pf.setInteractionTarget(dest);
                sector().layInCourseFor(dest);
            }
            // watchdogs: 2 game days without getting 200 su closer (in-system) or 0.05 ly closer (between systems)
            if (sector().getClock().getElapsedDaysSince(startTs) > 120f) { fail("not there after 120 days"); return; }
            float ly = Misc.getDistanceToPlayerLY(dest);
            long ts = sector().getClock().getTimestamp();
            if (ly < bestLy - 0.05f || bestLyTs == 0L) { bestLy = ly; bestLyTs = ts; }
            if (sector().getClock().getElapsedDaysSince(progressTs) >= 2f) {
                // 1.0.2: inside a star system on the way out, the light-year distance can't shrink until the
                // jump, so moving 200+ su in-system towards the jump point counts as progress too
                boolean movedInSystem = !same && !pf.isInHyperspace() && pf.getContainingLocation() == progressLoc
                        && Misc.getDistance(pf.getLocation(), new org.lwjgl.util.vector.Vector2f(progressX, progressY)) > 200f;
                boolean stuck = same ? dist > progressDist - 200f : sector().getClock().getElapsedDaysSince(bestLyTs) > 2f && !movedInSystem;
                if (stuck) {
                    if (++stalls >= 2) { pause("no progress towards " + dest.getName() + " for 4 days (at " + pf.getContainingLocation().getName() + ")"); return; }
                    sector().layInCourseFor(steer());
                } else stalls = 0;
                progressDist = same ? dist : Float.MAX_VALUE;
                progressTs = ts;
                progressLoc = pf.getContainingLocation();
                progressX = pf.getLocation().x; progressY = pf.getLocation().y;
            }
            SectorEntityToken course = sector().getCampaignUI().getUltimateCourseTarget();
            if (course == null) sector().layInCourseFor(steer());
            else if (course != steer() && course != dest && !(pf.getInteractionTarget() == dest)) {
                // somebody (the player?) set another course: put ours back, give up after three times
                if (++courseChanges >= 3) { pause("the course keeps being changed (now " + course.getName() + ")"); return; }
                EventLog.warn("travel", "course was changed to " + course.getName() + "; back on course for " + dest.getName());
                sector().layInCourseFor(steer());
            }
        }

        /**
         * 1.0.7: Transverse Jump. Into a deep-space system: fly to its nascent gravity well, then jump (free).
         * Out of one: jump to hyperspace (costs fuel and a little CR, the only way out). True when it acted this tick.
         */
        boolean transverse(CampaignFleetAPI pf, boolean same, long now) {
            if (same || now - tvTs < 3000L) return false;
            com.fs.starfarer.api.characters.AbilityPlugin fj = pf.getAbility("fracture_jump");
            if (fj == null) return false;
            boolean inHyper = pf.isInHyperspace();
            boolean destDeep = deepSpace(dest.getContainingLocation()) && Rules.b("transverse_in", true);
            boolean hereDeep = !inHyper && deepSpace(pf.getContainingLocation()) && Rules.b("transverse_out", true);
            if (!destDeep && !hereDeep) return false;
            float wellDist = -1f;
            com.fs.starfarer.api.campaign.NascentGravityWellAPI w = inHyper ? wellFor(dest) : null;
            if (w != null) wellDist = Math.max(0f, Misc.getDistance(pf, w) - w.getRadius() - pf.getRadius());
            boolean usable = false;
            try { usable = fj.isUsable() && !fj.isActiveOrInProgress(); } catch (Throwable ignored) {}
            float range = 50f;
            try { range = com.fs.starfarer.api.impl.campaign.abilities.FractureJumpAbility.NASCENT_JUMP_DIST; } catch (Throwable ignored) {}
            String act = TravelMath.transverse(inHyper, destDeep, hereDeep, same, wellDist, range, usable);
            if (TravelMath.JUMP_IN.equals(act) || TravelMath.JUMP_OUT.equals(act)) {
                if (++tvJumps > 4) { pause("Transverse Jump didn't take after 4 tries (" + pf.getContainingLocation().getName() + ")"); return true; }
                tvTs = now;
                try { fj.activate(); } catch (Throwable t) { log.warn("Claude Captain: transverse jump failed", t); return false; }
                EventLog.info("travel", TravelMath.JUMP_IN.equals(act)
                        ? "Transverse Jump into " + dest.getContainingLocation().getName() + " (deep space, no jump points)"
                        : "Transverse Jump out of " + pf.getContainingLocation().getName() + " (no jump points; costs fuel and some CR)");
                return true;
            }
            return false;
        }
    }

    /** A star system without jump points (deep space): only Transverse Jump gets in or out. */
    static boolean deepSpace(LocationAPI l) {
        if (!(l instanceof StarSystemAPI) || l.isHyperspace()) return false;
        try { return l.getJumpPoints() == null || l.getJumpPoints().isEmpty(); } catch (Throwable t) { return false; }
    }

    /** The hyperspace nascent gravity well into the destination's system, preferring the one next to the destination. */
    static com.fs.starfarer.api.campaign.NascentGravityWellAPI wellFor(SectorEntityToken dest) {
        LocationAPI sys = dest.getContainingLocation();
        if (sys == null || sector().getHyperspace() == null) return null;
        com.fs.starfarer.api.campaign.NascentGravityWellAPI best = null;
        float bd = Float.MAX_VALUE;
        for (Object o : sector().getHyperspace().getEntities(com.fs.starfarer.api.campaign.NascentGravityWellAPI.class)) {
            com.fs.starfarer.api.campaign.NascentGravityWellAPI w = (com.fs.starfarer.api.campaign.NascentGravityWellAPI) o;
            SectorEntityToken t = w.getTarget();
            if (t == null || t.getContainingLocation() != sys) continue;
            float d = t == dest ? -1f : Misc.getDistance(t.getLocation(), dest.getLocation());
            if (d < bd) { bd = d; best = w; }
        }
        return best;
    }

    static String where(SectorEntityToken t) { return t.getContainingLocation() == null ? "?" : t.getContainingLocation().getName(); }

    // ================================================================== wait

    public static class Wait extends Goal {
        final float days;
        long start = 0L;
        public Wait(float days) { this.days = days; title = "Wait " + days + " days"; ctx = DialogAgent.ctx("idle"); }
        public boolean lowCrOk() { return true; }
        public void tick(long now) {
            if (start == 0L) start = sector().getClock().getTimestamp();
            InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
            if (d != null) { handleDialog(d, now, null); return; }
            float el = sector().getClock().getElapsedDaysSince(start);
            progress = String.format(java.util.Locale.ROOT, "%.1f/%.0f days", el, days);
            if (el >= days) { done("waited " + days + " days"); return; }
            if (sector().isPaused()) Governor.unpause();
        }
    }

    // ================================================================== resupply

    static float crBudget(CampaignFleetAPI pf) {
        float s = 0f;
        for (FleetMemberAPI m : pf.getFleetData().getMembersListCopy()) {
            if (m.isMothballed()) continue;
            try { s += SupplyMath.crRecovery(m.getRepairTracker().getCR(), m.getRepairTracker().getMaxCR(), m.getDeploymentCostSupplies(), m.getDeployCost()); }
            catch (Throwable ignored) {}
        }
        return s;
    }

    static boolean sells(MarketAPI m, String id) {
        if (m.isHidden() || m.getPrimaryEntity() == null || m.getFaction().isHostileTo("player")) return false;
        for (String sub : new String[]{"open_market", "black_market"}) {
            if (m.getSubmarket(sub) == null) continue;
            try { if (Trader.liveStock(m, sub.startsWith("black"), id) >= 10f) return true; } catch (Throwable ignored) {}
        }
        return false;
    }

    static MarketAPI nearest(java.util.Comparator<MarketAPI> unused, MarketFilter f) {
        MarketAPI best = null;
        float bd = Float.MAX_VALUE;
        for (MarketAPI m : sector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || m.getPrimaryEntity() == null || m.getFaction().isHostileTo("player")) continue;
            if (!f.ok(m)) continue;
            float d = Misc.getDistanceToPlayerLY(m.getPrimaryEntity());
            if (d < bd) { bd = d; best = m; }
        }
        return best;
    }

    interface MarketFilter { boolean ok(MarketAPI m); }

    public static class Resupply extends Goal {
        final float days;
        boolean bought = false;
        /** 1.0.9: true only when the player OK'd this purchase (explicit 'resupply' command); automatic restocks just quote supplies. */
        boolean approved = false;
        /** 1.0.9: fuel to have aboard after this stop (0 = resupply_fuel_ly). */
        float fuelTarget = 0f;
        public Resupply(float days) { this.days = days; title = "Resupply " + Math.round(days) + " days"; ctx = DialogAgent.ctx("resupply", "travel"); }
        public Resupply(float days, float fuelTarget, boolean approved) { this(days); this.fuelTarget = fuelTarget; this.approved = approved; }

        /** Last supply quote an automatic restock produced (for the hunt's NOT READY message). */
        static volatile String lastQuote = "";
        public boolean lowCrOk() { return true; }

        public void tick(long now) {
            if (runChild(now)) return;
            if (child != null && "failed".equals(child.state)) { fail(child.result); return; }
            MarketAPI m = dockedMarket();
            if (m == null || !sells(m, "supplies")) {
                if (child != null) { fail("docked, but " + (m == null ? "no market" : m.getName()) + " can't sell supplies"); return; }
                MarketAPI best = nearest(null, new MarketFilter() { public boolean ok(MarketAPI x) { return sells(x, "supplies"); } });
                if (best == null) { fail("no friendly market with supplies known"); return; }
                child = new Travel(best.getPrimaryEntity(), true, 0f);
                child.ctx = ctx;
                progress = "to " + best.getName();
                return;
            }
            String r = buy(m, days, fuelTarget, approved || !Rules.b("ask_before_supplies", true));
            done(r);
        }

        /** Buys supplies (maintenance x days + CR budget, credit-capped) and fuel, black market first. */
        static String buy(MarketAPI m, float days) { return buy(m, days, 0f, true); }

        static String buy(MarketAPI m, float days, float fuelTarget, boolean buySupplies) {
            CampaignFleetAPI pf = pf();
            InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
            CoreUITradeMode mode = Trader.tradeMode(d);
            if (mode == CoreUITradeMode.NONE) return m.getName() + " won't trade with you now - nothing bought";
            Trader.beginStop();
            StringBuilder b = new StringBuilder();
            float maint = Governor.maintPerDay(pf);
            float crb = Rules.b("resupply_cr_budget", true) ? crBudget(pf) : 0f;
            float credits = pf.getCargo().getCredits().get();
            float pct = Rules.f("resupply_max_credit_pct", 25f);
            boolean black = m.getSubmarket("black_market") != null && Trader.liveStock(m, true, "supplies") >= 1f;
            float unit = Trader.buyCost(m, black, "supplies", 1f);
            float room = pf.getCargo().getMaxCapacity() - pf.getCargo().getSpaceUsed() - 1f;
            float stock = Trader.liveStock(m, true, "supplies") + (mode == CoreUITradeMode.OPEN ? Trader.liveStock(m, false, "supplies") : 0f);
            SupplyMath.Order o = SupplyMath.supplies(maint, days, crb, pf.getCargo().getSupplies(), unit, credits, pct, room, stock);
            b.append("supplies: maintenance ").append(String.format(java.util.Locale.ROOT, "%.1f", maint)).append("/day x ").append(Math.round(days))
             .append(" d + CR budget ").append(Math.round(crb)).append(" - aboard ").append(Math.round(pf.getCargo().getSupplies()))
             .append(" -> order ").append(o);
            float budget = credits * pct / 100f;
            float spent = 0f;
            lastQuote = "";
            if (o.qty >= 1f && buySupplies) spent += topUp(m, mode, "supplies", o.qty, budget, b);
            else if (o.qty >= 1f) {
                // 1.0.9: the player wants to be asked before every supply purchase
                lastQuote = "need " + Math.round(o.qty) + " supplies at " + m.getName() + ", about " + Math.round(unit) + "/unit = ~"
                        + Misc.getWithDGS(Math.round(o.qty * unit)) + " cr (ceiling " + Math.round(Trader.ceiling("supplies")) + ")";
                b.append("\n  NOT BOUGHT (ask first): ").append(lastQuote).append(" - send 'resupply ").append(Math.round(days)).append("' once the player agrees");
                EventLog.alert("supplies", "ask the player: " + lastQuote);
            }
            float fpl = 1f;
            try { fpl = pf.getLogistics().getFuelCostPerLightYear(); } catch (Throwable ignored) {}
            // 1.0.4: fuel is price-aware and credit-capped like supplies (Athulf charged 67/unit: 13k for fuel)
            boolean blackFuel = m.getSubmarket("black_market") != null && Trader.liveStock(m, true, "fuel") >= 1f;
            float fuelUnit = Trader.buyCost(m, blackFuel, "fuel", 1f);
            float fuelLy = SupplyMath.fuelLy(Rules.f("resupply_fuel_ly", 25f), fuelUnit,
                    Rules.f("resupply_fuel_max_price", 40f), Rules.f("resupply_fuel_min_ly", 10f));
            float fq = SupplyMath.fuel(fuelLy, fpl, pf.getCargo().getFuel(), pf.getCargo().getMaxFuel());
            // 1.0.9: a hunt asks for the fuel its round trip needs, not just resupply_fuel_ly (the 25 ly cap left Erlig short)
            if (fuelTarget > 0f) fq = Math.max(fq, Math.min(pf.getCargo().getMaxFuel(), fuelTarget) - pf.getCargo().getFuel());
            float reserve = Rules.f("reserve_credits", 5000f);
            float fuelBudget = SupplyMath.fuelBudget(pf.getCargo().getCredits().get(), reserve, Rules.f("resupply_fuel_max_credit_pct", 25f));
            if (fuelLy < Rules.f("resupply_fuel_ly", 25f)) b.append("\n  fuel costs ").append(Math.round(fuelUnit)).append("/unit here: topping up to ")
                    .append(Math.round(fuelLy)).append(" ly only");
            if (fq >= 1f) spent += topUp(m, mode, "fuel", fq, fuelBudget, b);
            Trader.endStop();
            b.append("\nspent ").append(Misc.getWithDGS(Math.round(spent))).append(" cr; now ").append(Math.round(pf.getCargo().getSupplies()))
             .append(" supplies (").append(Math.round(GovMath.supplyDays(pf.getCargo().getSupplies(), maint))).append(" days maintenance), fuel ")
             .append(Math.round(pf.getCargo().getFuel())).append("/").append(Math.round(pf.getCargo().getMaxFuel()));
            return b.toString();
        }

        static float topUp(MarketAPI m, CoreUITradeMode mode, String id, float qty, float budget, StringBuilder b) {
            float got = 0f, paid = 0f;
            boolean[] order = mode == CoreUITradeMode.OPEN ? new boolean[]{true, false} : new boolean[]{true};
            for (boolean black : order) {
                float left = qty - got;
                if (left < 1f) break;
                if (black && m.getSubmarket("black_market") == null) continue;
            // 1.0.7: no black-market buying with the transponder on (it costs standing with the market's faction)
            if (black && Shipyard.blackLocked(m.getSubmarket("black_market"))) continue;
                if (!black && m.getSubmarket("open_market") == null) continue;
                float q = Math.min(left, (float) Math.floor(Trader.liveStock(m, black, id)));
                if (q < 1f) continue;
                Trader.Result r = Trader.buy(m, black, id, q, Math.max(0f, budget - paid), mode);
                b.append("\n  ").append(r.text);
                if (r.ok) { got += r.qty; paid += -r.credits; }
            }
            if (got < 1f) b.append("\n  no ").append(id).append(" bought at ").append(m.getName());
            return paid;
        }
    }

    // ================================================================== repair at a dockyard

    static boolean needsRepair() {
        float hullMin = Rules.f("repair_hull_pct", 90f) / 100f;
        for (FleetMemberAPI m : pf().getFleetData().getMembersListCopy()) {
            if (m.isMothballed()) continue;
            if (m.getStatus().getHullFraction() < hullMin) return true;
            if (m.getRepairTracker().getCR() < m.getRepairTracker().getMaxCR() - 0.02f) return true;
        }
        return false;
    }

    static String repairNeedText() {
        StringBuilder b = new StringBuilder();
        for (FleetMemberAPI m : pf().getFleetData().getMembersListCopy()) {
            if (m.isMothballed()) continue;
            float h = m.getStatus().getHullFraction(), cr = m.getRepairTracker().getCR(), mx = m.getRepairTracker().getMaxCR();
            if (h < 0.999f || cr < mx - 0.02f) b.append(b.length() > 0 ? ", " : "").append(m.getShipName()).append(" hull ").append(Math.round(h * 100)).append("% CR ")
                    .append(Math.round(cr * 100)).append("/").append(Math.round(mx * 100)).append("%");
        }
        return b.length() == 0 ? "nothing" : b.toString();
    }

    /** Markets whose dockyard can repair: a spaceport (the "Repair your ships" option needs one). Heuristic: checked against the live option when docked. */
    static boolean canRepair(MarketAPI m) {
        try { return m.hasSpaceport() && !m.getFaction().isHostileTo("player"); } catch (Throwable t) { return false; }
    }

    static AutoDialog.Opt repairOption(InteractionDialogAPI d) {
        for (AutoDialog.Opt o : AutoDialog.options(d)) if (o.text.toLowerCase().startsWith("repair your ships")) return o;
        return null;
    }

    public static class Repair extends Goal {
        int phase = 0; // 0 find/travel, 1 clicked repair, waiting for the sub-dialog
        public Repair() { title = "Repair at a dockyard"; ctx = DialogAgent.ctx("repair", "travel"); }
        public boolean lowCrOk() { return true; }
        public void tick(long now) {
            if (runChild(now)) return;
            if (child != null && "failed".equals(child.state)) { fail(child.result); return; }
            if (now < waitUntil) return;
            InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
            if (phase == 0 && !needsRepair()) { done("nothing needs repair (the dockyard option would show [disabled])"); return; }
            MarketAPI m = dockedMarket();
            if (phase == 1) {
                if (!needsRepair()) { done("repaired: " + repairNeedText()); return; }
                if (d == null) { fail("dialog closed before the repair finished; still needs: " + repairNeedText()); return; }
                AutoDialog.Opt ro = repairOption(d);
                if (ro != null && !ro.enabled) { fail("'Repair your ships' is disabled at " + (m == null ? "?" : m.getName()) + " (not enough credits?) - still needs: " + repairNeedText()); return; }
                if (clicks > 8) { fail("repair dialog not understood\n" + DialogAgent.describe(d, ctx, null, null)); return; }
                DialogAgent.Result r = DialogAgent.apply(d, ctx, null, null);
                if (r == null || r.paused) { EventLog.alert("dialog", "repair sub-dialog not covered by dialog_rules.txt\n" + DialogAgent.describe(d, ctx, null, null)); pause("repair dialog needs a decision"); return; }
                clicks++;
                EventLog.info("repair", r.text);
                waitUntil = now + 800L;
                return;
            }
            if (m != null) {
                AutoDialog.Opt ro = repairOption(d);
                if (ro == null) {
                    if (child != null) { fail(m.getName() + " shows no 'Repair your ships' option (no dockyard here, or the dialog is in a sub-menu)"); return; }
                } else if (!ro.enabled) {
                    fail("'Repair your ships' is [disabled] at " + m.getName() + " - needs: " + repairNeedText() + "; credits " + Misc.getWithDGS(Math.round(pf().getCargo().getCredits().get())));
                    return;
                } else {
                    float before = pf().getCargo().getCredits().get();
                    AutoDialog.click(d, ro);
                    EventLog.info("repair", "clicked '" + ro.text + "' at " + m.getName() + " (credits " + Misc.getWithDGS(Math.round(before)) + ")");
                    phase = 1;
                    waitUntil = now + 800L;
                    return;
                }
            }
            MarketAPI best = nearest(null, new MarketFilter() { public boolean ok(MarketAPI x) { return canRepair(x); } });
            if (best == null) { fail("no friendly market with a spaceport known"); return; }
            child = new Travel(best.getPrimaryEntity(), true, 0f);
            child.ctx = ctx;
            progress = "to " + best.getName();
        }
    }

    // ================================================================== comm-directory people

    static boolean postIs(PersonAPI p, String... posts) {
        String id = p.getPostId();
        if (id == null) return false;
        for (String s : posts) if (s != null && s.equalsIgnoreCase(id)) return true;
        return false;
    }

    static List<PersonAPI> people(MarketAPI m) {
        List<PersonAPI> out = new ArrayList<PersonAPI>();
        try {
            if (m.getCommDirectory() == null) return out;
            for (CommDirectoryEntryAPI e : m.getCommDirectory().getEntriesCopy()) {
                if (e.isHidden() || !(e.getEntryData() instanceof PersonAPI)) continue;
                out.add((PersonAPI) e.getEntryData());
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 1.0.8 "officers": every officer / mercenary for hire in the comm directories the game knows, nearest first. */
    static String officersForHire() {
        final List<Object[]> rows = new ArrayList<Object[]>();
        for (MarketAPI m : sector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || m.getPrimaryEntity() == null) continue;
            boolean hostile = m.getFaction().isHostileTo("player");
            for (PersonAPI p : people(m)) {
                boolean merc = postIs(p, Ranks.POST_MERCENARY);
                if (!merc && !postIs(p, Ranks.POST_OFFICER_FOR_HIRE)) continue;
                float d = Misc.getDistanceToPlayerLY(m.getPrimaryEntity());
                int lvl = 0;
                try { lvl = p.getStats().getLevel(); } catch (Throwable ignored) {}
                StringBuilder sk = new StringBuilder();
                try {
                    for (com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI s : p.getStats().getSkillsCopy()) {
                        if (s.getLevel() <= 0 || s.getSkill() == null || !s.getSkill().isCombatOfficerSkill()) continue;
                        sk.append(sk.length() == 0 ? "" : ", ").append(s.getSkill().getName()).append(s.getLevel() >= 2 ? " (elite)" : "");
                    }
                } catch (Throwable ignored) {}
                String line = p.getNameString() + " | level " + lvl + " | " + personality(p) + (merc ? " | MERCENARY (monthly salary, story point to hire)" : " | officer")
                        + " | " + m.getName() + " (" + m.getFaction().getDisplayName() + ", " + String.format(java.util.Locale.ROOT, "%.1f", d) + " ly)"
                        + (hostile ? " | hostile market" : "") + (sk.length() > 0 ? "\n    skills: " + sk : "");
                rows.add(new Object[]{d, line});
            }
        }
        java.util.Collections.sort(rows, new java.util.Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) { return Float.compare((Float) a[0], (Float) b[0]); }
        });
        StringBuilder b = new StringBuilder("officers for hire in known comm directories (" + rows.size() + "); you have "
                + pf().getFleetData().getOfficersCopy().size() + " officers\n");
        for (Object[] r : rows) b.append(r[1]).append("\n");
        if (rows.isEmpty()) b.append("(none known: officers show up in the comm directories of markets you visit)\n");
        return b.toString().trim();
    }

    static String personality(PersonAPI p) {
        try { return p.getPersonalityAPI().getId(); } catch (Throwable t) { return "?"; }
    }

    /** Travel + dock at m, open a comm link with p, then answer with the given dialog context until done() says so. */
    public abstract static class TalkGoal extends Goal {
        MarketAPI market;
        PersonAPI person;
        boolean talking = false;
        int answers = 0;
        abstract boolean success();
        abstract String successText();

        public void tick(long now) {
            if (runChild(now)) return;
            if (child != null && "failed".equals(child.state)) { fail(child.result); return; }
            if (now < waitUntil) return;
            if (success()) { done(successText()); return; }
            InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
            MarketAPI here = dockedMarket();
            if (here == null || here != market) {
                if (child != null && !talking) { fail("could not get docked at " + market.getName()); return; }
                if (talking && d == null) { fail("the conversation closed without success"); return; }
                child = new Travel(market.getPrimaryEntity(), true, 0f);
                child.ctx = DialogAgent.ctx("travel");
                return;
            }
            if (!talking) {
                try { CommandRunner.openComm(d, person); } catch (CommandException e) { fail(e.getMessage()); return; }
                talking = true;
                EventLog.info("talk", "comm link with " + person.getNameString() + " (" + person.getPost() + ")\n" + CommandRunner.describeDialog(d, 6));
                waitUntil = now + 800L;
                return;
            }
            if (answers++ > 10) { fail("conversation not understood\n" + DialogAgent.describe(d, ctx, null, null)); return; }
            DialogAgent.Result r = DialogAgent.apply(d, ctx, null, null);
            if (r == null || r.paused) {
                EventLog.alert("dialog", "'" + title + "': no dialog rule fits\n" + DialogAgent.describe(d, ctx, null, null) + "\nanswer with 'choose <n>' then 'resume', or add a rule");
                pause("conversation needs a decision");
                answers = 0;
                return;
            }
            EventLog.info("talk", r.text);
            waitUntil = now + 900L;
        }
    }

    public static class Hire extends TalkGoal {
        final String want;
        int officersBefore;
        public Hire(String personality) {
            want = personality == null ? "" : personality.trim().toLowerCase();
            title = "Hire officer" + (want.length() > 0 ? " (" + want + ")" : "");
            ctx = DialogAgent.ctx("hire");
            officersBefore = pf().getFleetData().getOfficersCopy().size();
            pickOfficer();
        }
        void pickOfficer() {
            float bd = Float.MAX_VALUE;
            for (MarketAPI m : sector().getEconomy().getMarketsCopy()) {
                if (m.isHidden() || m.getPrimaryEntity() == null || m.getFaction().isHostileTo("player")) continue;
                for (PersonAPI p : people(m)) {
                    if (!postIs(p, Ranks.POST_OFFICER_FOR_HIRE, Ranks.POST_MERCENARY)) continue;
                    if (want.length() > 0 && !personality(p).toLowerCase().startsWith(want)) continue;
                    float d = Misc.getDistanceToPlayerLY(m.getPrimaryEntity());
                    if (d < bd) { bd = d; market = m; person = p; }
                }
            }
            if (person != null) progress = person.getNameString() + " (" + personality(person) + ", level " + person.getStats().getLevel() + ") at " + market.getName();
        }
        public void tick(long now) {
            if (person == null) { fail("no officer for hire" + (want.length() > 0 ? " with personality " + want : "") + " in any known comm directory"); return; }
            super.tick(now);
        }
        boolean success() { return pf().getFleetData().getOfficersCopy().size() > officersBefore; }
        String successText() { return "hired " + (person == null ? "an officer" : person.getNameString() + " (" + personality(person) + ")"); }
    }

    public static class Commission extends TalkGoal {
        final FactionAPI faction;
        public Commission(FactionAPI f) {
            faction = f;
            title = "Commission with " + f.getDisplayName();
            ctx = DialogAgent.ctx("commission");
            float bd = Float.MAX_VALUE;
            for (MarketAPI m : sector().getEconomy().getMarketsCopy()) {
                if (m.isHidden() || m.getPrimaryEntity() == null || m.getFaction() != f) continue;
                for (PersonAPI p : people(m)) {
                    if (!postIs(p, Ranks.POST_MILITARY_ADMINISTRATOR, Ranks.POST_BASE_COMMANDER, Ranks.POST_STATION_COMMANDER, Ranks.POST_FLEET_COMMANDER)) continue;
                    float d = Misc.getDistanceToPlayerLY(m.getPrimaryEntity());
                    if (d < bd) { bd = d; market = m; person = p; }
                }
            }
            if (person != null) progress = "talk to " + person.getNameString() + " (" + person.getPost() + ") at " + market.getName();
        }
        public void tick(long now) {
            if (person == null) { fail("no " + faction.getDisplayName() + " military administrator / base commander found in comm directories"); return; }
            super.tick(now);
        }
        boolean success() { return faction.getId().equals(Misc.getCommissionFactionId()); }
        String successText() { return "commissioned by " + faction.getDisplayName(); }
    }

    // ================================================================== exploration

    /** Visits one site: fly there, interact, take everything the explore dialog rules allow. */
    public static class Visit extends Goal {
        long openTs = 0L;
        int openTries = 0;
        float visitBest = Float.MAX_VALUE;
        long visitBestTs = 0L;
        final ExploreMath.Site site;
        SectorEntityToken ent;
        boolean inDialog = false;
        long startTs = 0L;
        public Visit(ExploreMath.Site s) {
            site = s;
            ent = Explorer.entity(s.id);
            title = "Explore " + s.name + " [" + s.kind + "]";
            ctx = DialogAgent.ctx("explore", "after_battle");
        }
        public SectorEntityToken target() { return ent; }
        public void tick(long now) {
            if (ent == null) { Explorer.visited.add(site.id); fail(site.name + " is gone"); return; }
            if (startTs == 0L) startTs = sector().getClock().getTimestamp();
            if (now < waitUntil) return;
            InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
            if (d != null) {
                if (d.getInteractionTarget() == ent || LootTracker.open()) {
                    inDialog = true;
                    progress = "dialog with " + site.name;
                    if (clicks > 15) { Explorer.visited.add(site.id); pause("explore dialog loop at " + site.name); return; }
                    handleDialog(d, now, ent);
                    return;
                }
                handleDialog(d, now, ent);
                return;
            }
            if (inDialog) {
                Explorer.visited.add(site.id);
                done("explored " + site.name + " [" + site.kind + "]");
                return;
            }
            // 1.0.7: a detour that isn't closing in is dropped quickly (a debris field once ate 20 days of a hunt)
            float maxDays = Rules.f("explore_visit_max_days", 5f);
            if (sector().getClock().getElapsedDaysSince(startTs) > maxDays) { Explorer.visited.add(site.id); fail("could not reach " + site.name + " in " + Math.round(maxDays) + " days"); return; }
            if (pf().getBattle() != null) return;
            float dist = Misc.getDistance(pf(), ent) - ent.getRadius() - pf().getRadius();
            if (pf().getContainingLocation() == ent.getContainingLocation()) {
                if (dist < visitBest - 200f || visitBestTs == 0L) { visitBest = dist; visitBestTs = sector().getClock().getTimestamp(); }
                else if (sector().getClock().getElapsedDaysSince(visitBestTs) > Rules.f("explore_stall_days", 2f)) {
                    Explorer.visited.add(site.id); fail("no progress towards " + site.name + " for " + Math.round(Rules.f("explore_stall_days", 2f)) + " days"); return;
                }
            }
            progress = Math.round(Math.max(0f, dist)) + " su to " + site.name;
            // 1.0.7: debris fields are terrain; flying into one doesn't open its dialog, so open it the way a click does
            if (pf().getContainingLocation() == ent.getContainingLocation() && dist <= 50f && now - openTs > 3000L && openTries < 5) {
                openTs = now; openTries++;
                try { sector().getCampaignUI().showInteractionDialog(ent); } catch (Throwable t) { log.warn("Claude Captain: could not open " + site.name, t); }
                return;
            }
            if (pf().getInteractionTarget() != ent || sector().getCampaignUI().getUltimateCourseTarget() != ent) {
                pf().setInteractionTarget(ent);
                sector().layInCourseFor(ent);
            }
            if (sector().isPaused()) Governor.unpause();
        }
    }

    /** "explore [here]": visit what's worth it in this system, closest first, up to explore_max_sites. */
    public static class Explore extends Goal {
        int visits = 0;
        final Set<String> tried = new HashSet<String>();
        final StringBuilder log = new StringBuilder();
        public Explore() { title = "Explore this system"; ctx = DialogAgent.ctx("explore"); }
        public SectorEntityToken target() { return child == null ? null : child.target(); }
        public void tick(long now) {
            if (runChild(now)) return;
            if (child != null) { log.append(child.state).append(": ").append(child.result).append("\n"); child = null; }
            if (visits >= (int) Rules.f("explore_max_sites", 3f)) { done("visited " + visits + " sites\n" + log); return; }
            ExploreMath.Site s = Explorer.next(null, tried);
            if (s == null) { done(visits == 0 ? "nothing worth exploring here within the rules (explore_*)\n" + Explorer.list() : "visited " + visits + " sites\n" + log); return; }
            tried.add(s.id);
            visits++;
            child = new Visit(s);
            EventLog.info("explore", "detour to " + s.name + " [" + s.kind + "], " + Math.round(s.dist) + " su, defended " + s.defended);
        }
    }

    // ================================================================== background runners (trade trips, hunts)

    public static class Runner extends Goal {
        final boolean hunt;
        String lastPause = null;
        public Runner(boolean hunt, String title) { this.hunt = hunt; this.title = title; ctx = DialogAgent.ctx(hunt ? "hunt" : "trade"); }
        public boolean lowCrOk() { return hunt && ("rest".equals(BountyRunner.state) || "after".equals(BountyRunner.state) || "resupply".equals(BountyRunner.state)); }
        public void tick(long now) {
            String st = hunt ? BountyRunner.state : TripRunner.state;
            if ("paused".equals(st)) {
                String why = hunt ? BountyRunner.note : TripRunner.pauseReason;
                if (!"paused".equals(state)) { pause(why); lastPause = why; }
                return;
            }
            if ("idle".equals(st)) {
                if (hunt) done(BountyRunner.lastResult.length() > 0 ? BountyRunner.lastResult : "hunt over");
                else if (TripRunner.loopLeft > 0) { progress = TripRunner.loopStatus(); return; }
                else done(TripRunner.lastSummary == null ? "trip over" : TripRunner.lastSummary + (TripRunner.loopTotal > 0 ? "\n" + TripRunner.loopStatus() : ""));
                return;
            }
            progress = hunt ? BountyRunner.state + (BountyRunner.note.length() > 0 ? ": " + BountyRunner.note : "")
                    : "step " + TripRunner.stepIndex + "/" + TripRunner.stepTotal + " " + TripRunner.stepText;
        }
        public void resume() {
            if (!"paused".equals(state)) return;
            state = "running";
            if (hunt) BountyRunner.hunt("resume"); else TripRunner.resume();
        }
        /** The runner was resumed directly ('hunt resume'): follow it. */
        public void follow() {
            String st = hunt ? BountyRunner.state : TripRunner.state;
            if ("paused".equals(state) && !"paused".equals(st)) state = "running";
        }
        public void cancel() {
            if (hunt) BountyRunner.hunt("stop"); else TripRunner.cancel("by Claude");
            if (!finished()) fail("cancelled");
        }
    }
}
