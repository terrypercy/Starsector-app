package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** claude_captain/state.txt: one JSON snapshot of everything Claude needs to decide the next batch. */
public final class StateWriter {

    private static final Logger log = Global.getLogger(StateWriter.class);

    private StateWriter() {}

    static float r1(float v) { return Math.round(v * 10f) / 10f; }

    public static void write() {
        try { Global.getSettings().writeTextFileToCommon(Files.STATE, Json.write(build())); }
        catch (Throwable t) { log.warn("Claude Captain: state.txt failed", t); }
    }

    public static Map<String, Object> build() {
        Map<String, Object> o = Json.obj();
        SectorAPI s = Global.getSector();
        o.put("captain_version", Files.VERSION);
        o.put("real_time_ms", System.currentTimeMillis());
        CampaignFleetAPI pf = s == null ? null : s.getPlayerFleet();
        if (pf == null) { o.put("state", "TITLE_SCREEN"); return o; }
        o.put("date", s.getClock().getDateString());
        o.put("paused", s.isPaused());
        o.put("last_event", EventLog.core.lastSeq());
        section(o, "governor", new Part() { public Object get() { return governor(); } });
        section(o, "goal", new Part() { public Object get() { return goal(); } });
        section(o, "location", new Part() { public Object get() { return location(); } });
        section(o, "docked", new Part() { public Object get() { return docked(); } });
        section(o, "logistics", new Part() { public Object get() { return logistics(); } });
        section(o, "fleet", new Part() { public Object get() { return fleet(); } });
        section(o, "cargo", new Part() { public Object get() { return cargo(); } });
        section(o, "bounties", new Part() { public Object get() { return bounties(); } });
        section(o, "reputation", new Part() { public Object get() { return reputation(); } });
        section(o, "nearby_fleets", new Part() { public Object get() { return nearby(); } });
        section(o, "explore", new Part() { public Object get() { return explore(); } });
        section(o, "dialog", new Part() { public Object get() { return dialog(); } });
        o.put("auto_battle", BattleConfig.auto);
        if (BattleConfig.lastResult.length() > 0) o.put("last_battle", BattleConfig.lastResult);
        return o;
    }

    interface Part { Object get(); }

    /** One failing section must not cost the whole snapshot. */
    static void section(Map<String, Object> o, String key, Part p) {
        try { o.put(key, p.get()); } catch (Throwable t) { o.put(key, "error: " + t); }
    }

    static CampaignFleetAPI pf() { return Global.getSector().getPlayerFleet(); }

    static Object governor() {
        Map<String, Object> g = Json.obj();
        g.put("control", Governor.armed ? "claude" : "player");
        g.put("chip", Governor.chip());
        g.put("budget_days", r1(Governor.budget.daysLeft));
        g.put("until", Governor.budget.until);
        g.put("paused_reason", Governor.pausedReason);
        g.put("alarms", Governor.latch.active());
        g.put("acknowledged", Governor.latch.ackedKeys().toString());
        GovMath.Input in = Governor.last;
        g.put("nearest_friendly_market", in.nearestFriendlyName);
        g.put("nearest_friendly_ly", r1(in.nearestFriendlyLy));
        g.put("fuel_needed_there", Math.round(TravelMath.fuelWithMargin(in.nearestFriendlyLy, in.nearestFriendlyJumps, in.fuelPerLy, in.fuelMarginPct, in.fuelMarginLy)));
        g.put("lowest_combat_cr", Math.round(in.lowestCombatCR * 100f));
        g.put("low_cr_expected", in.lowCrExpected);
        return g;
    }

    static Object goal() {
        Map<String, Object> g = Json.obj();
        Goals.Goal x = Captain.goal;
        g.put("title", x == null ? null : x.title);
        g.put("state", x == null ? "none" : x.state);
        g.put("progress", x == null ? "" : x.line());
        g.put("result", x == null ? "" : x.result);
        g.put("batch", Captain.activeTitle);
        g.put("trade_runner", TripRunner.describe());
        g.put("loop", TripRunner.loopStatus());
        g.put("hunt", BountyRunner.status());
        synchronized (Captain.history) { g.put("recent", Json.arr()); ((List<Object>) g.get("recent")).addAll(Captain.history); }
        return g;
    }

    static Object location() {
        Map<String, Object> l = Json.obj();
        LocationAPI loc = pf().getContainingLocation();
        l.put("name", loc == null ? "?" : loc.getName());
        l.put("hyperspace", loc != null && loc.isHyperspace());
        l.put("x", Math.round(pf().getLocation().x));
        l.put("y", Math.round(pf().getLocation().y));
        SectorEntityToken c = Global.getSector().getCampaignUI().getUltimateCourseTarget();
        l.put("course_target", c == null ? null : c.getName() + " (" + (c.getContainingLocation() == null ? "?" : c.getContainingLocation().getName()) + ", " + r1(Misc.getDistanceToPlayerLY(c)) + " ly)");
        l.put("transponder", pf().isTransponderOn());
        return l;
    }

    static Object docked() {
        MarketAPI m = Goals.dockedMarket();
        if (m == null) return null;
        Map<String, Object> d = Json.obj();
        d.put("market", m.getName());
        d.put("id", m.getId());
        d.put("faction", m.getFaction().getDisplayName());
        d.put("size", m.getSize());
        d.put("free_port", m.isFreePort());
        d.put("military", Misc.isMilitary(m));
        d.put("trade_mode", String.valueOf(Trader.tradeMode(Global.getSector().getCampaignUI().getCurrentInteractionDialog())));
        List<Object> subs = Json.arr();
        for (SubmarketAPI sm : m.getSubmarketsCopy()) {
            Map<String, Object> x = Json.obj();
            x.put("id", sm.getSpecId());
            x.put("name", sm.getNameOneLine());
            x.put("tariff_pct", Math.round(sm.getTariff() * 100f));
            subs.add(x);
        }
        d.put("submarkets", subs);
        d.put("storage_access", m.isPlayerOwned() || Misc.playerHasStorageAccess(m));
        Map<String, Object> rep = Json.obj();
        rep.put("dockyard", Goals.canRepair(m));
        rep.put("needed", Goals.needsRepair());
        rep.put("needs", Goals.repairNeedText());
        InteractionDialogAPI dlg = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
        AutoDialog.Opt ro = dlg == null ? null : Goals.repairOption(dlg);
        rep.put("option", ro == null ? "not shown" : ro.enabled ? "enabled" : "disabled");
        d.put("repair", rep);
        List<Object> people = Json.arr();
        for (PersonAPI p : Goals.people(m)) {
            Map<String, Object> x = Json.obj();
            x.put("name", p.getNameString());
            x.put("post", p.getPost());
            x.put("post_id", p.getPostId());
            String role = Goals.postIs(p, com.fs.starfarer.api.impl.campaign.ids.Ranks.POST_OFFICER_FOR_HIRE, com.fs.starfarer.api.impl.campaign.ids.Ranks.POST_MERCENARY) ? "officer for hire"
                    : Goals.postIs(p, com.fs.starfarer.api.impl.campaign.ids.Ranks.POST_MILITARY_ADMINISTRATOR, com.fs.starfarer.api.impl.campaign.ids.Ranks.POST_BASE_COMMANDER,
                    com.fs.starfarer.api.impl.campaign.ids.Ranks.POST_STATION_COMMANDER) ? "military (commissions)" : "";
            if (role.length() > 0) x.put("role", role);
            if (role.startsWith("officer")) { x.put("personality", Goals.personality(p)); x.put("level", p.getStats().getLevel()); }
            people.add(x);
        }
        d.put("people", people);
        return d;
    }

    static Object logistics() {
        Map<String, Object> l = Json.obj();
        CampaignFleetAPI pf = pf();
        CargoAPI c = pf.getCargo();
        float maint = Governor.maintPerDay(pf), burn = Governor.burnPerDay(pf);
        float fpl = 1f;
        try { fpl = pf.getLogistics().getFuelCostPerLightYear(); } catch (Throwable ignored) {}
        l.put("credits", Math.round(c.getCredits().get()));
        l.put("supplies", Math.round(c.getSupplies()));
        l.put("maintenance_per_day", r1(maint));
        l.put("current_burn_per_day", r1(burn));
        l.put("supply_days_maintenance", Math.round(GovMath.supplyDays(c.getSupplies(), maint)));
        l.put("supply_days_current_burn", Math.round(GovMath.supplyDays(c.getSupplies(), burn)));
        l.put("cr_recovery_supplies", Math.round(Goals.crBudget(pf)));
        l.put("fuel", Math.round(c.getFuel()));
        l.put("fuel_max", Math.round(c.getMaxFuel()));
        l.put("fuel_per_ly", r1(fpl));
        l.put("fuel_range_ly", r1(fpl > 0 ? c.getFuel() / fpl : 0f));
        l.put("crew", c.getCrew());
        l.put("marines", c.getMarines());
        l.put("personnel_max", Math.round(c.getMaxPersonnel()));
        l.put("cargo_used", Math.round(c.getSpaceUsed()));
        l.put("cargo_max", Math.round(c.getMaxCapacity()));
        return l;
    }

    static Object fleet() {
        List<Object> out = Json.arr();
        for (FleetMemberAPI m : pf().getFleetData().getMembersListCopy()) {
            Map<String, Object> x = Json.obj();
            x.put("name", m.getShipName());
            x.put("hull", m.getHullSpec().getHullName());
            x.put("size", String.valueOf(m.getHullSpec().getHullSize()));
            x.put("dp", Math.round(m.getDeploymentPointsCost()));
            x.put("cr", Math.round(m.getRepairTracker().getCR() * 100f));
            x.put("max_cr", Math.round(m.getRepairTracker().getMaxCR() * 100f));
            x.put("hull_pct", Math.round(m.getStatus().getHullFraction() * 100f));
            x.put("dmods", Shipyard.dmods(m));
            if (m.isFlagship()) x.put("flagship", true);
            if (m.isMothballed()) x.put("mothballed", true);
            if (Shipyard.civilian(m)) x.put("civilian", true);
            PersonAPI cap = m.getCaptain();
            if (cap != null && !cap.isDefault()) x.put("officer", cap.getNameString() + " L" + cap.getStats().getLevel() + " " + Goals.personality(cap));
            x.put("loadout", loadout(m));
            out.add(x);
        }
        return out;
    }

    static String loadout(FleetMemberAPI m) {
        Map<String, Integer> w = new HashMap<String, Integer>();
        try {
            for (String slot : m.getVariant().getFittedWeaponSlots()) {
                String id = m.getVariant().getWeaponId(slot);
                if (id == null) continue;
                String name = id;
                try { name = Global.getSettings().getWeaponSpec(id).getWeaponName(); } catch (Throwable ignored) {}
                Integer n = w.get(name);
                w.put(name, n == null ? 1 : n + 1);
            }
        } catch (Throwable ignored) {}
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : w.entrySet()) b.append(b.length() > 0 ? ", " : "").append(e.getValue()).append("x ").append(e.getKey());
        try { int wings = m.getVariant().getWings().size(); if (wings > 0) b.append(b.length() > 0 ? ", " : "").append(wings).append(" wing(s)"); } catch (Throwable ignored) {}
        try { b.append(" | ").append(m.getVariant().getNonBuiltInHullmods().size()).append(" hullmods"); } catch (Throwable ignored) {}
        return b.length() == 0 ? "unarmed" : b.toString();
    }

    static Object cargo() {
        List<Object> out = Json.arr();
        int n = 0;
        for (CargoStackAPI st : pf().getCargo().getStacksCopy()) {
            if (st.isNull() || ++n > 40) continue;
            out.add(Math.round(st.getSize()) + " x " + st.getDisplayName());
        }
        return out;
    }

    static Object bounties() {
        List<Object> out = Json.arr();
        for (BountyRunner.Target t : BountyRunner.targets()) {
            Map<String, Object> x = Json.obj();
            x.put("name", t.name);
            x.put("kind", t.kind);
            x.put("where", t.where);
            x.put("ly", r1(t.ly));
            x.put("reward", Math.round(t.reward));
            x.put("dp", Math.round(t.theirDP));
            x.put("posted_by", t.faction);
            if (t.daysLeft > 0) x.put("days_left", Math.round(t.daysLeft));
            x.put("ok", t.ok);
            if (!t.ok) x.put("why", t.why);
            out.add(x);
        }
        return out;
    }

    static Object reputation() {
        Map<String, Object> r = Json.obj();
        float req = Reputation.required();
        r.put("commission", Misc.getCommissionFactionId());
        r.put("required", Math.round(req));
        r.put("stipend_at_level", Math.round(Reputation.stipend()));
        List<Object> fs = Json.arr();
        for (RepMath.Faction f : Reputation.factions()) {
            Map<String, Object> x = Json.obj();
            x.put("id", f.id);
            x.put("name", f.name);
            x.put("rel", Math.round(f.rel));
            x.put("to_favorable", Math.round(RepMath.toFavorable(f.rel, req)));
            if (f.hostile) x.put("hostile", true);
            if (f.hasBounties) x.put("bounties", true);
            fs.add(x);
        }
        r.put("factions", fs);
        RepMath.Faction t = Reputation.target();
        if (t != null) {
            r.put("target", t.id);
            List<Object> sg = Json.arr();
            sg.addAll(RepMath.suggestions(t, req));
            r.put("suggestions", sg);
        }
        return r;
    }

    static Object nearby() {
        List<Object> out = Json.arr();
        CampaignFleetAPI pf = pf();
        if (pf.getContainingLocation() == null) return out;
        for (CampaignFleetAPI f : pf.getContainingLocation().getFleets()) {
            if (f == pf || !f.isAlive() || !f.isVisibleToPlayerFleet()) continue;
            float d = Misc.getDistance(pf, f);
            if (d > 5000f) continue;
            Map<String, Object> x = Json.obj();
            x.put("name", f.getName());
            x.put("faction", f.getFaction().getDisplayName());
            x.put("dp", Math.round(BountyRunner.dpOf(f)));
            x.put("fp", f.getFleetPoints());
            x.put("hostile", f.isHostileTo(pf));
            x.put("dist_su", Math.round(d));
            out.add(x);
        }
        return out;
    }

    static Object explore() {
        List<Object> out = Json.arr();
        for (ExploreMath.Site s : Explorer.scan(pf().getContainingLocation())) {
            Map<String, Object> x = Json.obj();
            x.put("name", s.name);
            x.put("kind", s.kind);
            x.put("dist_su", Math.round(s.dist));
            x.put("defended", s.defended);
            if (s.defenderDP > 0) x.put("defender_dp", Math.round(s.defenderDP));
            x.put("cost", s.cost);
            if (Explorer.visited.contains(s.id)) x.put("visited", true);
            out.add(x);
        }
        return out;
    }

    static Object dialog() {
        InteractionDialogAPI d = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
        if (d == null) return null;
        Map<String, Object> x = Json.obj();
        x.put("with", DialogAgent.targetName(d));
        List<Object> opts = Json.arr();
        int i = 0;
        for (AutoDialog.Opt o : AutoDialog.options(d)) opts.add(++i + ". " + o.text + (o.enabled ? "" : " [disabled]") + (o.confirm ? " [confirm/story point]" : ""));
        x.put("options", opts);
        List<Object> text = Json.arr();
        text.addAll(DialogBridge.paragraphs(d, 4));
        x.put("text", text);
        if (LootTracker.open()) x.put("loot", LootTracker.describe());
        return x;
    }
}
