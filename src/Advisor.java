package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the game (the same data the Intel screen, fleet screen and market
 * screens show) and proposes three options. Each option carries the task list
 * that carries it out, so one click runs it through the normal task runner.
 *
 * Rules of thumb come from the community wiki: check Intel first (bounties and
 * missions pay far better than random trading), keep supplies and fuel ahead
 * of the next trip, keep combat readiness above 50%, never fight a fleet that
 * out-guns you, and treat clicking an option that spends credits as the OK.
 * Story points are never spent here.
 */
public final class Advisor {

    private Advisor() {}

    /** One proposal: what it is, why, and the tasks that do it. */
    public static class Option {
        public final int score;
        public final String title;
        public final String why;
        public final List<String> tasks;
        Option(int score, String title, String why, List<String> tasks) {
            this.score = score; this.title = title; this.why = why; this.tasks = tasks;
        }

        /** The task list as numbered, readable steps: "1 Fly to Ancyra > 2 Dock > 3 Buy 133 fuel". */
        public String route() {
            StringBuilder b = new StringBuilder();
            int k = 1;
            for (String t : tasks) {
                if (b.length() > 0) b.append("  >  ");
                b.append(k++).append(" ").append(stepText(t));
            }
            return b.toString();
        }
        public boolean sells() {
            for (String t : tasks) if (t.startsWith("sell ")) return true;
            return false;
        }
    }

    /** One line of the visual checklist: level 0 = fine, 1 = watch it, 2 = needs action. */
    public static class Check {
        public final int level;
        public final String label;
        public final String detail;
        Check(int level, String label, String detail) { this.level = level; this.label = label; this.detail = detail; }
    }

    /** The result of a scan. */
    public static class Plan {
        public final List<Check> checks = new ArrayList<Check>();
        /** Intel objectives, one line each: "Title -> Place (distance)". */
        public final List<String> objectives = new ArrayList<String>();
        /** The compass line at the bottom: where the game's tracker points, and how far. */
        public String compassName = null;
        public String compassDist = null;
        /** Compass direction to the objective ("NE"), or "via hyperspace" in another system. */
        public String compassDir = null;
        public final List<String> summary = new ArrayList<String>();
        public final List<String> notes = new ArrayList<String>();
        public final List<Option> options = new ArrayList<Option>();
        /** Bounties and missions from the Intel screen: "Bounty: name +30k cr, 45 d (Galatia)". */
        public final List<String> leads = new ArrayList<String>();
        /** What the current objective actually asks for right now (e.g. the tutorial step), or null. */
        public String step = null;
    }

    /** Plain-language steps for the Galatia tutorial ($tutStage). */
    static String tutorialStep(String stage) {
        if (stage == null) return null;
        if (stage.equals("GO_GET_DATA")) return "Explore the derelict near the station for its data.";
        if (stage.equals("GO_GET_AI_CORE")) return "Find the Domain probe past Pontus: use Active Sensor Burst, then explore it (expect drones).";
        if (stage.equals("GOT_AI_CORE")) return "Bring the AI core to Station Commander Cadence Hart at Ancyra (comm directory).";
        if (stage.equals("GO_RECOVER_SHIPS")) return "Recover 2 derelict ships around Tetra. Each needs spare crew.";
        if (stage.equals("RECOVERED_SHIPS")) return "Bring the ships to Cadence Hart at Ancyra (comm directory). Free supplies and weapons wait in storage.";
        if (stage.equals("GO_STABILIZE")) return "Stabilize the inner jump-point. Two miner fleets guard it - try to fight them one at a time.";
        if (stage.equals("STABILIZED")) return "Report back to Cadence Hart at Ancyra (comm directory).";
        if (stage.equals("DELIVER_REPORT")) return "Take the report to Station Commander Sheliak Shields at Jangala, Corvus (comm directory). Needs about 40 fuel.";
        return null;
    }

    /** One task as readable text: "goto Ancyra" -> "Fly to Ancyra". */
    public static String stepText(String t) {
        String[] w = t.split(" ");
        String c = w[0];
        String rest = t.length() > c.length() ? t.substring(c.length() + 1) : "";
        if (c.equals("goto")) return "Fly to " + (rest.equals("objective") ? "the objective" : rest);
        if (c.equals("dock")) return "Dock";
        if (c.equals("buy") && w.length >= 3) return "Buy " + w[w.length - 1] + " " + rest.replace(" " + w[w.length - 1], "");
        if (c.equals("sell")) return "Sell " + rest.replace(" all", "").replace("_", " ");
        if (c.equals("wait")) return "Wait " + rest + (rest.equals("1") ? " day" : " days");
        if (c.equals("battle") && rest.startsWith("auto on")) return "Auto-battle on";
        if (c.equals("status")) return "Refresh status";
        if (c.equals("choose")) return rest.equalsIgnoreCase("leave") ? "Leave" : "Pick: " + rest;
        if (c.equals("optional")) return stepText(rest) + " (if offered)";
        return t;
    }

    private static String join(List<String> parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) { if (b.length() > 0) b.append(", "); b.append(p); }
        return b.toString();
    }

    private static String n(float v) { return Misc.getWithDGS(Math.round(v)); }

    private static List<String> list(String... t) {
        List<String> l = new ArrayList<String>();
        Collections.addAll(l, t);
        return l;
    }

    // ---------------------------------------------------------------- scan

    public static Plan scan() {
        Plan plan = new Plan();
        SectorAPI sector = Global.getSector();
        CampaignFleetAPI pf = sector.getPlayerFleet();
        if (pf == null) { plan.notes.add("No player fleet yet."); return plan; }
        CargoAPI cargo = pf.getCargo();
        List<Option> cand = new ArrayList<Option>();

        // ---- fleet health and logistics
        float credits = cargo.getCredits().get();
        float supplies = cargo.getSupplies();
        float fuel = cargo.getFuel();
        float maxFuel = cargo.getMaxFuel();
        float perMonth = 0f;
        float minCr = 1f;
        int ships = 0;
        for (FleetMemberAPI m : pf.getFleetData().getMembersListCopy()) {
            if (m.isMothballed()) continue;
            ships++;
            perMonth += m.getStats().getSuppliesPerMonth().getModifiedValue();
            minCr = Math.min(minCr, m.getRepairTracker().getCR());
        }
        float perDay = perMonth / 30f;          // maintenance only: used for how much to stock
        float usePerDay = perDay;               // what the fleet really burns now, repairs and recovery included
        try { usePerDay = Math.max(perDay, pf.getLogistics().getTotalSuppliesPerDay()); } catch (Throwable ignored) {}
        float supplyDays = usePerDay > 0 ? supplies / usePerDay : 999f;
        float fuelPerLy = 1f;
        try { fuelPerLy = Math.max(0.1f, pf.getLogistics().getFuelCostPerLightYear()); } catch (Throwable ignored) {}
        float spaceUsed = cargo.getSpaceUsed(), spaceMax = cargo.getMaxCapacity();

        plan.summary.add(sector.getClock().getDateString() + " | " + (pf.getContainingLocation() == null ? "?" : pf.getContainingLocation().getName()));
        plan.summary.add("Credits " + n(credits) + " | Fleet " + ships + " ships, " + Math.round(pf.getFleetPoints()) + " FP");
        plan.summary.add("Fuel " + n(fuel) + "/" + n(maxFuel) + " (" + String.format("%.0f", fuel / fuelPerLy) + " ly range) | Supplies "
                + n(supplies) + " (about " + (supplyDays > 900 ? "plenty" : Math.round(supplyDays) + " days") + ")");
        plan.summary.add("Lowest combat readiness " + Math.round(minCr * 100) + "% | Cargo " + n(spaceUsed) + "/" + n(spaceMax));

        final float FUEL_LOW = Rules.f("fuel_low_pct", 30f) / 100f;
        final float SUP_LOW = Rules.f("supplies_low_days", 25f), SUP_CRIT = Rules.f("supplies_critical_days", 12f);
        final float SUP_TARGET = Rules.f("supplies_target_days", 45f);
        final float CR_REST = Rules.f("cr_rest_pct", 50f) / 100f, CR_BAD = Rules.f("cr_bad_pct", 40f) / 100f;
        final float CARGO_FULL = Rules.f("cargo_full_pct", 92f) / 100f;
        final float GUARD = Rules.f("guard_ratio", 0.9f);
        final float BUDGET = Rules.f("buy_budget_pct", 50f) / 100f;

        // ---- crew: below the minimum, every ship loses combat readiness
        float minCrew = 0f;
        try { minCrew = pf.getFleetData().getMinCrew(); } catch (Throwable ignored) {}
        int crewNow = cargo.getCrew();
        int crewShort = (int) Math.ceil(Math.max(0f, minCrew - crewNow));

        // ---- the nearest market that will trade with us
        MarketAPI home = nearestFriendlyMarket(pf);

        // ---- the game's own quest tracker (Intel screen)
        SectorEntityToken objective = null;
        String objectiveName = null;
        List<String> leads = new ArrayList<String>();
        try {
            for (IntelInfoPlugin i : sector.getIntelManager().getIntel()) {
                if (i.isHidden() || i.isEnded()) continue;
                SectorEntityToken where = null;
                try { where = i.getMapLocation(null); } catch (Throwable ignored) {}
                String title = i.getSmallDescriptionTitle();
                if (title == null) continue;
                if (i.isImportant() && plan.objectives.size() < 5) {
                    plan.objectives.add(title + (where != null ? " -> " + where.getName() + " (" + distText(pf, where) + ")" : ""));
                }
                if (i.isImportant() && where != null && objective == null) {
                    objective = where;
                    objectiveName = title;
                    plan.compassName = where.getName();
                    plan.compassDist = distText(pf, where);
                    plan.compassDir = dirText(pf, where);
                } else if (leads.size() < 3 && where != null && isLead(i)) {
                    leads.add(title + " (" + where.getName() + ")");
                }
            }
        } catch (Throwable t) {
            plan.notes.add("Couldn't read the Intel screen: " + t);
        }
        String tutStage = null;
        try { Object st = sector.getMemoryWithoutUpdate().get("$tutStage"); if (st != null) tutStage = String.valueOf(st); } catch (Throwable ignored) {}
        plan.step = tutorialStep(tutStage);
        if (objective != null) plan.summary.add("Objective: " + objectiveName + " -> " + objective.getName());
        for (String l : leads) plan.notes.add("Lead on Intel: " + l);

        // ---- trip planning
        float tripFuel = 0f;
        boolean guarded = false;
        float guardFp = 0f;
        if (objective != null) {
            float ly = Misc.getDistanceToPlayerLY(objective);
            if (Float.isNaN(ly) || Float.isInfinite(ly) || ly < 0f || ly > 1000f) {
                ly = 0f; // couldn't measure; don't let a bad number drive the plan
                plan.notes.add("Couldn't measure the distance to the objective, so fuel for the trip is not counted.");
            }
            tripFuel = ly * fuelPerLy;
            guardFp = hostileFpNear(pf, objective, 1500f);
            guarded = guardFp > 0f;
        }

        // ---- candidate: go to the objective
        if (objective != null) {
            boolean dockIt = objective.getMarket() != null && !objective.getMarket().getFaction().isHostileTo("player");
            boolean enoughFuel = fuel >= tripFuel * 1.2f + 10f;
            boolean tooStrong = guarded && guardFp > pf.getFleetPoints() * GUARD;
            int score = 80;
            String why = "Follows the game's quest tracker: " + objectiveName + ". About " + n(tripFuel) + " fuel for the trip.";
            List<String> tasks = new ArrayList<String>();
            if (!enoughFuel) { score = 25; why += " Not enough fuel - refuel first."; }
            if (guarded) {
                if (tooStrong) {
                    score = 5; // below every safe option
                    plan.notes.add("The objective is guarded by " + Math.round(guardFp) + " FP of hostiles (yours " + Math.round(pf.getFleetPoints())
                            + "). Not recommended until the fleet is stronger.");
                    why += " Hostile fleets there total " + Math.round(guardFp) + " FP against your " + Math.round(pf.getFleetPoints()) + " FP - too risky.";
                } else {
                    why += " Hostile fleets there total " + Math.round(guardFp) + " FP against your " + Math.round(pf.getFleetPoints())
                            + " FP; auto-battle fights them.";
                    tasks.add("battle auto on");
                }
            }
            tasks.add("goto objective");
            if (dockIt) tasks.add("dock " + objective.getName());
            float here = Misc.getDistance(pf, objective);
            boolean arrived = pf.getContainingLocation() == objective.getContainingLocation() && here < 600f && !dockIt;
            if (!arrived) cand.add(new Option(score, "Go to the objective: " + objective.getName(), why, tasks));

            // things to explore near the objective (probes, derelicts, caches): the step usually needs one of them
            SectorEntityToken salv = nearestSalvage(pf, objective, 4500f);
            if (salv != null && !tooStrong) {
                cand.add(new Option(arrived ? 82 : 45, "Investigate " + salv.getName(),
                        "Something to explore near the objective (" + Math.round(Misc.getDistance(pf, salv)) + " su away). Docks and picks Explore; recovery and loot choices stay with you.",
                        list("battle auto on", "goto " + salv.getName(), "dock " + salv.getName(), "choose Explore")));
            } else if (arrived && "GO_GET_AI_CORE".equals(tutStage)) {
                cand.add(new Option(84, "Scan here with Active Sensor Burst",
                        "The objective is hidden nearby; a sensor burst reveals it. Takes a moment and makes your fleet easy to spot.",
                        list("ability active sensor burst", "wait 1")));
            }
        }

        // ---- candidate: refuel
        float wantFuel = Math.min(maxFuel, Math.max(tripFuel * 1.5f + 20f, maxFuel * 0.5f));
        boolean fuelLow = fuel < Math.max(tripFuel * 1.2f + 10f, maxFuel * FUEL_LOW);
        if (home != null && fuelLow && wantFuel > fuel + 10f) {
            int qty = (int) Math.floor(Math.min(wantFuel - fuel, affordable(home, "fuel", credits * BUDGET)));
            if (qty > 10) {
                int sc = (objective != null && fuel < tripFuel * 1.2f + 10f) ? 90 : (fuel < maxFuel * 0.15f ? 87 : 55);
                cand.add(new Option(sc, "Refuel at " + home.getName(),
                        "Fuel is " + n(fuel) + "/" + n(maxFuel) + ". Buys " + qty + " fuel for about " + n(cost(home, "fuel", qty)) + " credits.",
                        list("goto " + home.getName(), "dock " + home.getName(), "buy fuel " + qty)));
            }
        }

        // ---- candidate: supplies
        if (home != null && supplyDays < SUP_LOW) {
            float room = Math.max(0f, spaceMax - spaceUsed);
            float repairPerDay = Math.max(0f, usePerDay - perDay);
            float want = Math.min(perDay * SUP_TARGET + repairPerDay * 10f - supplies, room);
            int stored = storedSupplies(home);
            int take = (int) Math.min(stored, Math.max(0f, want));
            float unit = cost(home, "supplies", 1f);
            float base = 100f;
            try { base = Global.getSector().getEconomy().getCommoditySpec("supplies").getBasePrice(); } catch (Throwable ignored) {}
            boolean pricey = unit > base * 2.5f;
            float buyWant = want - take;
            if (pricey) buyWant = Math.min(buyWant, perDay * SUP_LOW + repairPerDay * 5f - supplies - take); // only what keeps you safe
            int qty = (int) Math.floor(Math.max(0f, Math.min(buyWant, affordable(home, "supplies", credits * BUDGET))));
            if (qty <= 10) qty = 0;
            if (take > 0 || qty > 0) {
                List<String> t = list("goto " + home.getName(), "dock " + home.getName());
                String why = "About " + Math.round(supplyDays) + " days of supplies left at the current rate.";
                if (take > 0) { t.add("storage take supplies " + take); why += " Takes " + take + " free from your storage."; }
                if (qty > 0) {
                    t.add("buy supplies " + qty);
                    why += " Buys " + qty + " for about " + n(cost(home, "supplies", qty)) + " cr (" + n(unit) + "/unit"
                            + (pricey ? ", expensive here - normal is about " + n(base * 1.3f) + ", so it buys only enough to stay safe" : "") + ").";
                }
                why += " Clicking this option is your OK.";
                cand.add(new Option(supplyDays < SUP_CRIT ? 85 : 60, (take > 0 && qty == 0 ? "Collect supplies from storage at " : "Restock supplies at ") + home.getName(), why, t));
            }
        }

        // ---- candidate: recover combat readiness
        if (minCr < CR_REST && home != null) {
            cand.add(new Option(minCr < CR_BAD ? 88 : 62, "Repair at " + home.getName() + " (combat readiness)",
                    "Lowest CR is " + Math.round(minCr * 100) + "%. Below 40% ships malfunction in battle. The dockyard restores CR at once (costs supplies or credits).",
                    list("goto " + home.getName(), "dock " + home.getName(), "optional choose Repair your ships", "choose Leave")));
        }

        // ---- candidate: hire crew
        if (home != null && crewShort > 0) {
            int qty = crewShort + 5;
            cand.add(new Option(89, "Hire " + qty + " crew at " + home.getName(),
                    "Crew " + crewNow + " of " + Math.round(minCrew) + " needed. Short-crewed ships lose combat readiness. About "
                            + n(cost(home, "crew", qty)) + " credits.",
                    list("goto " + home.getName(), "dock " + home.getName(), "buy crew " + qty, "choose Leave")));
        }

        // ---- candidate: one trip that fixes everything at once (fuel, supplies, crew, repair)
        // All purchases share one budget (buy_budget_pct of credits), spent in priority order supplies > fuel > crew.
        if (home != null) {
            String sup = null, crw = null, fu = null;
            boolean repair = false;
            int best = 0;
            for (Option o : cand) {
                if (!o.tasks.contains("dock " + home.getName())) continue;
                for (String t : o.tasks) {
                    if (t.startsWith("buy supplies")) { sup = t; best = Math.max(best, o.score); }
                    else if (t.startsWith("buy crew")) { crw = t; best = Math.max(best, o.score); }
                    else if (t.startsWith("buy fuel")) { fu = t; best = Math.max(best, o.score); }
                    else if (t.startsWith("optional choose Repair")) { repair = true; best = Math.max(best, o.score); }
                }
            }
            float budget = pf.getCargo().getCredits().get() * Rules.f("buy_budget_pct", 50f) / 100f;
            List<String> tasks = new ArrayList<String>();
            List<String> what = new ArrayList<String>();
            float spent = 0f;
            String[][] order = {{"supplies", sup}, {"fuel", fu}, {"crew", crw}};
            for (String[] it : order) {
                if (it[1] == null) continue;
                int qty = qtyOf(it[1]);
                if (qty <= 0) continue;
                float unit = cost(home, it[0], qty) / qty;
                if (unit <= 0f) continue;
                int afford = (int) Math.floor((budget - spent) / unit);
                int q = Math.min(qty, afford);
                if (q < 1) continue;
                tasks.add("buy " + it[0] + " " + q);
                what.add(q < qty ? it[0] + " (" + q + " of " + qty + ")" : it[0]);
                spent += unit * q;
            }
            if (repair) what.add("repair");
            if (what.size() >= 2) {
                List<String> all = new ArrayList<String>();
                all.add("goto " + home.getName());
                all.add("dock " + home.getName());
                all.addAll(tasks);
                if (repair) all.add("optional choose Repair your ships");
                all.add("choose Leave");
                cand.add(new Option(best + 3, "Resupply at " + home.getName() + ": " + join(what),
                        "One stop for everything that's low, about " + n(spent) + " cr (budget " + n(budget) + "). Clicking this option is your OK for the purchases.", all));
            }
        }

        // ---- candidate: lighten an overloaded hold
        if (home != null && spaceMax > 0 && spaceUsed > spaceMax * CARGO_FULL) {
            cand.add(new Option(58, "Sell surplus cargo at " + home.getName(),
                    "Hold is " + Math.round(spaceUsed * 100 / spaceMax) + "% full; over capacity slows the fleet and costs supplies. Sells metals and heavy machinery.",
                    list("goto " + home.getName(), "dock " + home.getName(), "sell metals all", "sell heavy_machinery all")));
        }

        // ---- bounties and missions from the Intel screen
        addLeads(plan, cand, pf);

        // ---- advice Claude pushed from the chat (advice.txt)
        addClaudeAdvice(plan, cand);

        // ---- fallbacks so there are always three
        if (home != null) {
            cand.add(new Option(20, "Visit " + home.getName() + "'s bar",
                    "Bar missions and contacts pay about 3x normal trade margins. Docks you there; you pick the mission.",
                    list("goto " + home.getName(), "dock " + home.getName())));
        }
        cand.add(new Option(12, "Hold position and refresh the status report",
                "Does nothing in the game; rewrites the status file so Claude can read the situation.", list("status")));
        cand.add(new Option(15, "Wait 3 days",
                "Lets combat readiness and supplies settle, and lets events and bounties refresh.", list("wait 3")));
        cand.add(new Option(10, "Wait 1 day", "Short pause to see what changes.", list("wait 1")));

        Collections.sort(cand, new Comparator<Option>() {
            public int compare(Option a, Option b) { return b.score - a.score; }
        });
        for (Option o : cand) {
            if (plan.options.size() >= 3) break;
            if (o.title.equals(Captain.activeTitle)) continue; // already running
            plan.options.add(o);
        }

        // ---- notes (things only the player can do, and tips)
        try {
            if (sector.getPlayerStats().getPoints() > 0)
                plan.notes.add("You have " + sector.getPlayerStats().getPoints() + " unspent skill point(s) - open the character screen (C).");
            int sp = sector.getPlayerStats().getStoryPoints();
            if (sp > 0) plan.notes.add("You have " + sp + " story point(s). This advisor never spends them.");
        } catch (Throwable ignored) {}
        float hostileFp = hostileFpNear(pf, pf, 2500f);
        if (hostileFp > 0f) plan.notes.add("Hostile fleets within 2,500 su total " + Math.round(hostileFp) + " FP (yours " + Math.round(pf.getFleetPoints()) + ").");
        if (home == null) plan.notes.add("No friendly market in this system, so buying and resting options are unavailable.");
        // ---- visual checklist
        float fuelNeed = Math.max(tripFuel * 1.2f + 10f, maxFuel * FUEL_LOW);
        plan.checks.add(new Check(fuel >= fuelNeed ? 0 : (fuel < maxFuel * 0.15f ? 2 : 1), "Fuel", n(fuel) + " of " + n(maxFuel)));
        plan.checks.add(new Check(supplyDays >= SUP_LOW ? 0 : (supplyDays < SUP_CRIT ? 2 : 1), "Supplies",
                supplyDays > 900 ? n(supplies) : n(supplies) + " (" + Math.round(supplyDays) + " days)"));
        plan.checks.add(new Check(minCr >= CR_REST ? 0 : (minCr < CR_BAD ? 2 : 1), "Combat readiness", "lowest " + Math.round(minCr * 100) + "%"));
        plan.checks.add(new Check(spaceMax <= 0 || spaceUsed <= spaceMax * CARGO_FULL ? 0 : (spaceUsed > spaceMax ? 2 : 1), "Cargo hold",
                n(spaceUsed) + " of " + n(spaceMax)));
        float nearHostile = hostileFpNear(pf, pf, 2500f);
        plan.checks.add(new Check(nearHostile <= 0f ? 0 : (nearHostile > pf.getFleetPoints() * GUARD ? 2 : 1), "Hostiles nearby",
                nearHostile <= 0f ? "none" : Math.round(nearHostile) + " FP"));
        plan.checks.add(new Check(crewShort > 0 ? 2 : 0, "Crew", crewNow + " of " + Math.round(minCrew) + " needed"));
        plan.checks.add(new Check(objective != null ? 0 : 1, "Objective", objective != null ? objective.getName() : "none on the Intel screen"));
        if (guarded) plan.checks.add(new Check(guardFp > pf.getFleetPoints() * GUARD ? 2 : 1, "Objective guarded",
                Math.round(guardFp) + " FP vs your " + Math.round(pf.getFleetPoints())));
        plan.checks.add(new Check(home != null ? 0 : 1, "Friendly market", home != null ? home.getName() : "none in this system"));
        int skillPts = 0;
        try { skillPts = sector.getPlayerStats().getPoints(); } catch (Throwable ignored) {}
        plan.checks.add(new Check(skillPts > 0 ? 1 : 0, "Skill points", skillPts > 0 ? skillPts + " unspent" : "none to spend"));
        plan.notes.add("Tip: after a battle, salvage ships you won't use - damaged hulls sell for less than their salvage.");
        return plan;
    }

    // ---------------------------------------------------------------- leads and pushed advice

    public static final String ADVICE_FILE = Files.DIR + "advice.txt";

    private static String k(float credits) {
        return credits >= 1000f ? Math.round(credits / 1000f) + "k" : String.valueOf(Math.round(credits));
    }

    /** Person bounties, system bounties and missions on the Intel screen, with reward and days left. */
    /** Supplies waiting in the player's storage at this market (0 if no access). */
    static int storedSupplies(MarketAPI m) {
        try {
            if (!Misc.playerHasStorageAccess(m)) return 0;
            CargoAPI c = Misc.getStorageCargo(m);
            return c == null ? 0 : (int) Math.floor(c.getSupplies());
        } catch (Throwable t) { return 0; }
    }

    /** The nearest thing to explore (derelict, probe, cache) within radius of the target. */
    static SectorEntityToken nearestSalvage(CampaignFleetAPI pf, SectorEntityToken target, float radius) {
        SectorEntityToken best = null;
        float bestD = Float.MAX_VALUE;
        try {
            if (target.getContainingLocation() != pf.getContainingLocation()) return null;
            for (SectorEntityToken e : target.getContainingLocation().getEntitiesWithTag(com.fs.starfarer.api.impl.campaign.ids.Tags.SALVAGEABLE)) {
                if (Misc.getDistance(e, target) > radius) continue;
                if (e.hasTag("expired") || !e.isAlive()) continue;
                float d = Misc.getDistance(pf, e);
                if (d < bestD) { bestD = d; best = e; }
            }
        } catch (Throwable ignored) {}
        return best;
    }

    static int qtyOf(String task) {
        try { String[] p = task.trim().split(" "); return Integer.parseInt(p[p.length - 1]); } catch (Throwable t) { return 0; }
    }

    /** Self-test diagnostics: intel entries by class, so missing leads can be told apart from missing bounties. */
    static String intelCounts() {
        java.util.Map<String, Integer> m = new java.util.TreeMap<String, Integer>();
        for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel()) {
            String k = i.getClass().getSimpleName() + (i.isHidden() ? "(hidden)" : "") + (i.isEnded() ? "(ended)" : "");
            Integer c = m.get(k);
            m.put(k, c == null ? 1 : c + 1);
        }
        return m.toString();
    }

    static void addLeads(Plan plan, List<Option> cand, CampaignFleetAPI pf) {
        float maxRatio = Rules.f("bounty_max_ratio", 0.7f);
        try {
            for (IntelInfoPlugin i : Global.getSector().getIntelManager().getIntel()) {
                if (i.isHidden() || i.isEnded()) continue;
                SectorEntityToken where = null;
                try { where = i.getMapLocation(null); } catch (Throwable ignored) {}
                String place = where == null ? "?" : (where.getContainingLocation() != null ? where.getContainingLocation().getName() : where.getName());
                if (i instanceof com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel) {
                    com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel b = (com.fs.starfarer.api.impl.campaign.intel.PersonBountyIntel) i;
                    float reward = b.getBountyCredits();
                    float left = Math.max(0f, b.getDuration() - b.getElapsedDays());
                    float fp = b.getFleet() == null ? 0f : b.getFleet().getFleetPoints();
                    String name = b.getPerson() == null ? "bounty target" : b.getPerson().getNameString();
                    plan.leads.add("Bounty: " + name + " +" + k(reward) + ", " + Math.round(left) + " d (" + place + ")");
                    if (where != null && fp > 0f && fp <= pf.getFleetPoints() * maxRatio && left > 5f) {
                        cand.add(new Option(35 + (int) Math.min(25f, reward / 5000f), "Bounty: " + name + " (+" + k(reward) + ")",
                                "Their fleet is about " + Math.round(fp) + " FP against your " + Math.round(pf.getFleetPoints()) + "; "
                                        + Math.round(left) + " days left. Auto-battle fights them.",
                                list("battle auto on", "goto " + where.getName())));
                    }
                } else if (i instanceof com.fs.starfarer.api.impl.campaign.intel.SystemBountyIntel) {
                    float per = ((com.fs.starfarer.api.impl.campaign.intel.SystemBountyIntel) i).getBaseBounty();
                    plan.leads.add("System bounty: " + place + " (" + k(per) + " per kill)");
                } else if (i instanceof com.fs.starfarer.api.impl.campaign.missions.hub.BaseHubMission && !i.isImportant()) {
                    int reward = ((com.fs.starfarer.api.impl.campaign.missions.hub.BaseHubMission) i).getCreditsReward();
                    String title = i.getSmallDescriptionTitle();
                    if (title != null) plan.leads.add("Mission: " + title + (reward > 0 ? " +" + k(reward) : "") + " (" + place + ")");
                }
                if (plan.leads.size() >= 6) break;
            }
        } catch (Throwable t) {
            plan.notes.add("Couldn't read bounties: " + t);
        }
    }

    /**
     * Advice Claude writes from the chat into saves/common/claude_bridge/advice.txt:
     *   #written <epoch ms>
     *   score | title | why | task; task; task
     * It shows as a [Claude] option until it is older than claude_advice_minutes.
     */
    static void addClaudeAdvice(Plan plan, List<Option> cand) {
        try {
            com.fs.starfarer.api.SettingsAPI s = Global.getSettings();
            if (!s.fileExistsInCommon(ADVICE_FILE)) return;
            String text = s.readTextFileFromCommon(ADVICE_FILE);
            long written = 0L;
            for (String raw : text.replace("\r", "").split("\n")) {
                String l = raw.trim();
                if (l.startsWith("#written")) { try { written = Long.parseLong(l.substring(8).trim()); } catch (Throwable ignored) {} continue; }
                if (l.length() == 0 || l.startsWith("#")) continue;
                long maxAge = (long) (Rules.f("claude_advice_minutes", 30f) * 60000f);
                if (written > 0L && System.currentTimeMillis() - written > maxAge) return;
                String[] p = l.split("\\|");
                if (p.length < 4) continue;
                List<String> tasks = new ArrayList<String>();
                for (String t : p[3].split(";")) if (t.trim().length() > 0) tasks.add(t.trim());
                if (tasks.isEmpty()) continue;
                int score = 70;
                try { score = Integer.parseInt(p[0].trim()); } catch (Throwable ignored) {}
                cand.add(new Option(score, "[Claude] " + p[1].trim(), p[2].trim(), tasks));
            }
        } catch (Throwable t) {
            plan.notes.add("Couldn't read Claude's advice: " + t);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Eight-way compass direction from the fleet to the target (screen north is up). */
    static String dirText(CampaignFleetAPI pf, SectorEntityToken t) {
        try {
            if (t.getContainingLocation() != pf.getContainingLocation()) return "via hyperspace";
            double dx = t.getLocation().x - pf.getLocation().x, dy = t.getLocation().y - pf.getLocation().y;
            if (Math.abs(dx) < 1 && Math.abs(dy) < 1) return "here";
            String[] names = {"E", "NE", "N", "NW", "W", "SW", "S", "SE"};
            int k = (int) Math.round(Math.toDegrees(Math.atan2(dy, dx)) / 45.0);
            return names[((k % 8) + 8) % 8];
        } catch (Throwable ignored) {}
        return "?";
    }

    /** "1,234 su" inside the same location, otherwise "3.2 ly"; "?" when it can't be measured. */
    static String distText(CampaignFleetAPI pf, SectorEntityToken t) {
        try {
            if (t.getContainingLocation() == pf.getContainingLocation()) return n(Misc.getDistance(pf, t)) + " su";
            float ly = Misc.getDistanceToPlayerLY(t);
            if (!Float.isNaN(ly) && !Float.isInfinite(ly) && ly >= 0f && ly <= 1000f) return String.format("%.1f ly", ly);
        } catch (Throwable ignored) {}
        return "?";
    }

    private static boolean isLead(IntelInfoPlugin i) {
        try {
            for (String t : i.getIntelTags(null)) {
                String s = t.toLowerCase();
                if (s.startsWith("bount") || s.startsWith("mission") || s.startsWith("explor")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Total fleet points of hostile, visible fleets within radius of the target. */
    static float hostileFpNear(CampaignFleetAPI pf, SectorEntityToken target, float radius) {
        float total = 0f;
        try {
            if (target.getContainingLocation() == null) return 0f;
            for (CampaignFleetAPI f : target.getContainingLocation().getFleets()) {
                if (f == pf || !f.isAlive() || !f.isHostileTo(pf)) continue;
                if (Misc.getDistance(f, target) > radius) continue;
                total += f.getFleetPoints();
            }
        } catch (Throwable ignored) {}
        return total;
    }

    static MarketAPI nearestFriendlyMarket(CampaignFleetAPI pf) {
        MarketAPI best = null;
        float bestD = Float.MAX_VALUE;
        try {
            for (MarketAPI m : Global.getSector().getEconomy().getMarkets(pf.getContainingLocation())) {
                if (m.isHidden() || m.getPrimaryEntity() == null) continue;
                if (m.getFaction().isHostileTo("player")) continue;
                if (m.getSubmarket("open_market") == null) continue;
                float d = Misc.getDistance(pf, m.getPrimaryEntity());
                if (d < bestD) { bestD = d; best = m; }
            }
        } catch (Throwable ignored) {}
        return best;
    }

    private static float tariff(MarketAPI m) {
        try { return m.getSubmarket("open_market").getTariff(); } catch (Throwable t) { return 0.3f; }
    }

    static float cost(MarketAPI m, String commodity, float qty) {
        try { return m.getSupplyPrice(commodity, qty, true) * (1f + tariff(m)); } catch (Throwable t) { return 0f; }
    }

    /** How many units of a commodity the budget buys at this market's price. */
    static float affordable(MarketAPI m, String commodity, float budget) {
        float unit = cost(m, commodity, 1f);
        if (unit <= 0f) return 0f;
        return budget / (unit * 1.15f); // price rises with quantity
    }
}
