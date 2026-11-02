package claudecaptain;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Parses and runs one command line. */
public class CommandRunner {

    /** Short summary of the last successful command, shown in the game's message feed. */
    public String lastMessage;

    private SectorAPI sector() { return Global.getSector(); }
    private CampaignFleetAPI fleet() { return sector().getPlayerFleet(); }
    private CargoAPI cargo() { return fleet().getCargo(); }

    public String run(String line) throws CommandException {
        lastMessage = null;
        List<String> t = tokenize(line);
        if (t.isEmpty()) throw new CommandException("empty command");
        String cmd = t.get(0).toLowerCase();
        List<String> a = t.subList(1, t.size());

        if (cmd.equals("help")) return Help.TEXT;
        if (cmd.equals("selftest")) return Captain.selfTest();
        if (isCheat(cmd, a) && !Rules.b("allow_cheats", false)) {
            throw new CommandException("'" + cmd + "' is a cheat command and cheats are switched off (allow_cheats = false in advisor_rules.txt)");
        }
        if (cmd.equals("status")) return "status.txt updated";
        if (cmd.equals("msg")) return msg(join(a));
        if (cmd.equals("storage")) return storage(a);
        if (cmd.equals("say")) { Speech.say(join(a)); return "queued for speech"; }
        if (cmd.equals("shutup")) { Speech.stop(); return "speech stopped"; }
        if (cmd.equals("pause")) { Governor.modPause(); lastMessage = "Game paused"; return "paused"; }
        if (cmd.equals("unpause") || cmd.equals("resume")) { Governor.unpause(); lastMessage = "Game unpaused"; return "unpaused"; }
        if (cmd.equals("credits")) return credits(a);
        if (cmd.equals("cargo")) return cargoCmd(a);
        if (cmd.equals("refuel")) return refuel();
        if (cmd.equals("repair")) return repair();
        if (cmd.equals("crew")) return crew(a);
        if (cmd.equals("ship")) return ship(a);
        if (cmd.equals("blueprint")) return blueprint(a, false);
        if (cmd.equals("learn")) return blueprint(a, true);
        if (cmd.equals("xp")) return xp(a);
        if (cmd.equals("storypoints") || cmd.equals("sp")) return storyPoints(a);
        if (cmd.equals("rep")) return rep(a);
        if (cmd.equals("goto") || cmd.equals("travel")) return gotoCmd(a);
        if (cmd.equals("stop")) { sector().getCampaignUI().clearLaidInCourse(); lastMessage = "Course cleared"; return "course cleared"; }
        if (cmd.equals("teleport")) return teleport(a);
        if (cmd.equals("dock")) return dock(a);
        if (cmd.equals("sell")) return trade(a, false);
        if (cmd.equals("buy")) {
            // 1.0.9 (Nov 2): "buy supplies 200 at 146" - the player approves one buy above the market ceiling.
            // 'buy supplies' always goes through the in-game Yes popup first, so this is the player's call, once.
            int at = -1;
            for (int i = 0; i + 1 < a.size(); i++) if (a.get(i).equalsIgnoreCase("at") && isNum(a.get(i + 1))) at = i;
            if (at >= 0) {
                float capOnce = (float) num(a.get(at + 1));
                List<String> rest = new ArrayList<String>(a.subList(0, at));
                rest.addAll(a.subList(at + 2, a.size()));
                Trader.playerCap = capOnce;
                try { return trade(rest, true) + "\n(player-approved price cap " + Math.round(capOnce) + "/unit for this buy)"; }
                finally { Trader.playerCap = -1f; }
            }
            return trade(a, true);
        }
        if (cmd.equals("prices")) return prices(a);
        if (cmd.equals("market") || cmd.equals("info")) return marketInfo(a);
        if (cmd.equals("find")) return find(a);
        if (cmd.equals("list")) return list(a);
        if (cmd.equals("dialog")) return dialogText();
        if (cmd.equals("choose") || cmd.equals("option")) return choose(a);
        if (cmd.equals("ability") || cmd.equals("abilities")) return ability(a);
        if (cmd.equals("loot")) return loot(a);
        if (cmd.equals("contact") || cmd.equals("contacts")) return contact(a);
        if (cmd.equals("battle")) return battle(a);
        throw new CommandException("unknown command '" + cmd + "' (try: help)");
    }

    // ------------------------------------------------------------------ dialogs

    private InteractionDialogAPI currentDialog() throws CommandException {
        InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
        if (d == null) throw new CommandException("no dialog is open");
        return d;
    }

    /** Text of the open dialog: recent paragraphs plus numbered options. */
    public static String describeDialog(InteractionDialogAPI d, int paragraphs) {
        StringBuilder b = new StringBuilder();
        SectorEntityToken t = d.getInteractionTarget();
        b.append("dialog with: ").append(t == null ? "?" : t.getName()).append("\n");
        if (LootTracker.open()) b.append(LootTracker.describe()).append("\n(use: loot take | loot leave)\n");
        List<String> paras = DialogBridge.paragraphs(d, paragraphs);
        if (!paras.isEmpty()) {
            b.append("text:\n");
            for (String p : paras) b.append("  | ").append(p.replace("\n", "\n  | ")).append("\n");
        }
        List<DialogBridge.Opt> opts = DialogBridge.options(d);
        b.append("options:");
        if (opts.isEmpty()) b.append(" (none)");
        for (DialogBridge.Opt o : opts) {
            b.append("\n  ").append(o.number).append(". ").append(o.text);
            if (!o.enabled) b.append("  [disabled]");
            if (DialogBridge.needsConfirm(d, o)) b.append("  [costs a story point or needs confirmation]");
        }
        return b.toString();
    }

    private String dialogText() throws CommandException {
        return describeDialog(currentDialog(), 30);
    }

    private String choose(List<String> a) throws CommandException {
        need(a, 1, "choose <number | option text>");
        InteractionDialogAPI d = currentDialog();
        String q = join(a);
        List<DialogBridge.Opt> opts = DialogBridge.options(d);
        if (opts.isEmpty()) throw new CommandException("this dialog has no options right now");
        DialogBridge.Opt pick = null;
        if (isNum(q)) {
            int n = (int) num(q);
            for (DialogBridge.Opt o : opts) if (o.number == n) pick = o;
        } else {
            int best = 0;
            for (DialogBridge.Opt o : opts) {
                int s = Lookup.score(q, o.text, o.text);
                if (s > best) { best = s; pick = o; }
            }
        }
        if (pick == null) throw new CommandException("no option '" + q + "'\n" + describeDialog(d, 0));
        if (!pick.enabled) throw new CommandException("option " + pick.number + " (" + pick.text + ") is disabled");
        if (DialogBridge.needsConfirm(d, pick)) {
            // never skip the game's own story-point / confirmation popup
            throw new CommandException("option " + pick.number + " (" + pick.text + ") costs a story point or needs confirmation; "
                    + "it has to be clicked on screen");
        }
        String chosen = pick.text;
        DialogBridge.click(d, pick);
        lastMessage = "Chose: " + chosen;
        InteractionDialogAPI after = sector().getCampaignUI().getCurrentInteractionDialog();
        String now = after == null ? "dialog closed" : describeDialog(after, 8);
        return "chose: " + chosen + "\n" + now;
    }

    /** Lists or opens a comm link with a person in the docked market's comm directory. */
    private String contact(List<String> a) throws CommandException {
        InteractionDialogAPI d = currentDialog();
        SectorEntityToken t = d.getInteractionTarget();
        if (t == null || t.getMarket() == null || t.getMarket().getCommDirectory() == null)
            throw new CommandException("not docked at a market with a comm directory");
        List<Lookup.Match> people = new ArrayList<Lookup.Match>();
        for (com.fs.starfarer.api.campaign.CommDirectoryEntryAPI e : t.getMarket().getCommDirectory().getEntriesCopy()) {
            if (e.isHidden() || !(e.getEntryData() instanceof com.fs.starfarer.api.characters.PersonAPI)) continue;
            com.fs.starfarer.api.characters.PersonAPI p = (com.fs.starfarer.api.characters.PersonAPI) e.getEntryData();
            people.add(new Lookup.Match("person", p.getId(), p.getNameString() + " (" + p.getPost() + ")", p));
        }
        if (a.isEmpty()) {
            StringBuilder b = new StringBuilder("comm directory:");
            for (Lookup.Match m : people) b.append("\n  ").append(m.name);
            return b.toString();
        }
        com.fs.starfarer.api.characters.PersonAPI p = (com.fs.starfarer.api.characters.PersonAPI) Lookup.best(join(a), people, "contacts").ref;
        openComm(d, p);
        lastMessage = "Comm link: " + p.getNameString();
        return "opened comm link with " + p.getNameString() + " (" + p.getPost() + ")\n" + describeDialog(d, 8);
    }

    /** Opens a comm link with a person in the docked market's comm directory, as clicking them does. */
    static void openComm(InteractionDialogAPI d, com.fs.starfarer.api.characters.PersonAPI p) throws CommandException {
        SectorEntityToken t = d.getInteractionTarget();
        if (!(d.getPlugin() instanceof com.fs.starfarer.api.impl.campaign.RuleBasedInteractionDialogPluginImpl))
            throw new CommandException("this dialog can't open comm links");
        com.fs.starfarer.api.impl.campaign.RuleBasedInteractionDialogPluginImpl plugin =
                (com.fs.starfarer.api.impl.campaign.RuleBasedInteractionDialogPluginImpl) d.getPlugin();
        t.setActivePerson(p);
        plugin.notifyActivePersonChanged();
        d.getVisualPanel().showPersonInfo(p, true);
        // people in a comm directory are greeted through PickGreeting (OpenCommLink is for fleets)
        if (!plugin.fireBest("PickGreeting") && !plugin.fireBest("OpenCommLink"))
            throw new CommandException("comm link to " + p.getNameString() + " got no answer");
    }

    // ------------------------------------------------------------------ abilities

    private String ability(List<String> a) throws CommandException {
        CampaignFleetAPI pf = fleet();
        if (a.isEmpty()) {
            StringBuilder b = new StringBuilder("abilities:");
            for (com.fs.starfarer.api.characters.AbilityPlugin p : pf.getAbilities().values()) {
                b.append("\n  ").append(p.getId()).append(" = ").append(p.getSpec().getName())
                 .append(p.isActive() ? " | ON" : "")
                 .append(p.isOnCooldown() ? " | cooldown " + String.format("%.1f", p.getCooldownLeft()) + " days" : "")
                 .append(!p.isUsable() && !p.isActive() ? " | not usable now" : "");
            }
            return b.toString();
        }
        List<String> rest = new ArrayList<String>(a);
        String mode = "toggle";
        String last = rest.get(rest.size() - 1).toLowerCase();
        if (rest.size() > 1 && (last.equals("on") || last.equals("off"))) { mode = last; rest.remove(rest.size() - 1); }
        String q = join(rest);
        List<Lookup.Match> all = new ArrayList<Lookup.Match>();
        for (com.fs.starfarer.api.characters.AbilityPlugin p : pf.getAbilities().values()) {
            all.add(new Lookup.Match("ability", p.getId(), p.getSpec().getName(), p));
        }
        com.fs.starfarer.api.characters.AbilityPlugin p = (com.fs.starfarer.api.characters.AbilityPlugin) Lookup.best(q, all, "abilities").ref;
        boolean on = mode.equals("on") || (mode.equals("toggle") && !p.isActive());
        String name = p.getSpec().getName();
        if (on) {
            if (p.isActive()) return name + " is already on";
            if (!p.isUsable()) throw new CommandException(name + " can't be used right now" + (p.isOnCooldown() ? " (cooldown)" : ""));
            p.activate();
        } else {
            if (!p.isActive()) return name + " is already off";
            p.deactivate();
        }
        lastMessage = name + (on ? " activated" : " deactivated");
        return lastMessage;
    }

    // ------------------------------------------------------------------ loot & battle

    private String loot(List<String> a) throws CommandException {
        if (a.isEmpty()) return LootTracker.describe();
        String op = a.get(0).toLowerCase();
        if (op.equals("take") || op.equals("all")) { lastMessage = "Took the loot"; return LootTracker.takeAll(true); }
        if (op.equals("leave")) return LootTracker.takeAll(false);
        throw new CommandException("usage: loot | loot take | loot leave");
    }

    private String battle(List<String> a) throws CommandException {
        if (a.isEmpty()) {
            return "auto-battle " + (BattleConfig.auto ? "ON" : "OFF") + ", deploy: " + BattleConfig.deploy
                    + ", margin " + BattleConfig.margin + (BattleConfig.lastResult.length() > 0 ? "\nlast battle: " + BattleConfig.lastResult : "");
        }
        String op = a.get(0).toLowerCase();
        if (op.equals("auto")) {
            need(a, 2, "battle auto on|off");
            BattleConfig.auto = a.get(1).equalsIgnoreCase("on");
            BattleConfig.save();
            lastMessage = "Auto-battle " + (BattleConfig.auto ? "on" : "off");
            return "auto-battle " + (BattleConfig.auto ? "ON: battles skip the deployment screen and the AI fights them" : "OFF");
        }
        if (op.equals("deploy")) {
            need(a, 2, "battle deploy auto | all | <ship>, <ship>, ...");
            BattleConfig.deploy = join(a.subList(1, a.size()));
            BattleConfig.save();
            return "battles deploy: " + BattleConfig.deploy + " (kept in the save)";
        }
        if (op.equals("margin")) {
            need(a, 2, "battle margin <1.0-4.0>");
            BattleConfig.margin = (float) Math.max(1.0, Math.min(4.0, num(a.get(1))));
            BattleConfig.save();
            return "auto deploy aims for " + BattleConfig.margin + "x the enemy's deployment points";
        }
        throw new CommandException("usage: battle | battle auto on|off | battle deploy auto|all|<ships> | battle margin <n>");
    }

    // ------------------------------------------------------------------ parsing

    static List<String> tokenize(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { quoted = !quoted; continue; }
            if (Character.isWhitespace(c) && !quoted) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static String join(List<String> a) {
        StringBuilder b = new StringBuilder();
        for (String s : a) { if (b.length() > 0) b.append(' '); b.append(s); }
        return b.toString();
    }

    /** Parses 1500, 1,500, 1.5k, 2m. Returns NaN when not a number. */
    static double num(String s) {
        if (s == null) return Double.NaN;
        String x = s.toLowerCase().replace(",", "").replace("_", "").trim();
        if (x.startsWith("+")) x = x.substring(1);
        double mult = 1;
        if (x.endsWith("k")) { mult = 1000; x = x.substring(0, x.length() - 1); }
        else if (x.endsWith("m")) { mult = 1000000; x = x.substring(0, x.length() - 1); }
        try { return Double.parseDouble(x) * mult; } catch (NumberFormatException e) { return Double.NaN; }
    }

    static boolean isNum(String s) { return !Double.isNaN(num(s)); }

    private static String fmt(float v) { return Misc.getWithDGS(Math.round(v)); }

    private static void need(List<String> a, int n, String usage) throws CommandException {
        if (a.size() < n) throw new CommandException("usage: " + usage);
    }

    // ------------------------------------------------------------------ simple commands

    /** Commands that change the game without playing it. Off unless allow_cheats = true. */
    public static boolean isCheat(String cmd, List<String> a) {
        if (cmd.equals("credits") || cmd.equals("refuel") || cmd.equals("repair") || cmd.equals("crew") || cmd.equals("ship")
                || cmd.equals("blueprint") || cmd.equals("learn") || cmd.equals("xp") || cmd.equals("storypoints") || cmd.equals("sp")
                || cmd.equals("rep") || cmd.equals("teleport")) return true;
        return cmd.equals("cargo") && !a.isEmpty() && (a.get(0).equalsIgnoreCase("add") || a.get(0).equalsIgnoreCase("remove"));
    }

    private String help() {
        return "credits add|remove|set <n>\n"
            + "cargo add|remove <item> [qty|all]     (commodity, weapon, fighter wing, special item, hullmod)\n"
            + "refuel | repair | crew fill | crew add <n>\n"
            + "ship add <ship> [count] [empty] | ship remove <ship name or hull>\n"
            + "blueprint add <ship|weapon|wing> | learn <ship|weapon|wing>\n"
            + "xp add <n> | storypoints add <n> | rep <faction> <-100..100 | +n | -n>\n"
            + "goto <place> | stop | teleport <place> | dock [place] | pause | unpause\n"
            + "sell <commodity> <qty|all> [black] | buy <commodity> <qty|max> [black]\n"
            + "prices <commodity> | market [name] | find <text>\n"
            + "list ships|weapons|wings|commodities|specials|hullmods|factions|markets|systems [filter]\n"
            + "dialog | choose <number|text> | loot | loot take | loot leave\n"
            + "ability [name] [on|off]   (transponder, go dark, sustained burn, emergency burn, scavenge, ...)\n"
            + "battle | battle auto on|off | battle deploy auto|all|<ship>, <ship> | battle margin <n>\n"
            + "storage list | storage take <item> [qty|all] | storage put <item> [qty|all]   (while docked at a market)\n"
            + "ask <question> | <option 1> | <option 2> ...   (task-list command: shows an in-game popup, waits for the click)\n"
            + "say <text> | shutup | msg <text> | status | selftest   (advisor panel self-check)\n"
            + "(cheat commands above are off unless allow_cheats = true in advisor_rules.txt)";
    }


    // ------------------------------------------------------------------ storage

    private CargoAPI storageCargo() throws CommandException {
        InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
        SectorEntityToken t = d == null ? null : d.getInteractionTarget();
        MarketAPI m = t == null ? null : t.getMarket();
        if (m == null) throw new CommandException("dock at a market first (storage belongs to a market)");
        SubmarketAPI s = m.getSubmarket("storage");
        if (s == null) throw new CommandException(m.getName() + " has no storage");
        // play it straight: storage at someone else's market needs the one-time access fee first, as on the Storage tab
        if (!m.isPlayerOwned() && !Misc.playerHasStorageAccess(m)) {
            throw new CommandException("storage at " + m.getName() + " needs its one-time 5,000-credit access fee; pay it on the Storage tab in the game first");
        }
        return s.getCargo();
    }

    private String storage(List<String> a) throws CommandException {
        need(a, 1, "storage list | take <item> [qty|all] | put <item> [qty|all]");
        String op = a.get(0).toLowerCase();
        CargoAPI store = storageCargo();
        if (op.equals("list")) {
            StringBuilder b = new StringBuilder("storage contents:");
            int n = 0;
            for (CargoStackAPI st : store.getStacksCopy()) {
                b.append("\n  ").append(fmt(st.getSize())).append(" x ").append(st.getDisplayName());
                n++;
            }
            if (n == 0) b.append(" (empty)");
            b.append("\nyour cargo space: ").append(fmt(cargo().getSpaceUsed())).append(" / ").append(fmt(cargo().getMaxCapacity()));
            return b.toString();
        }
        boolean take = op.equals("take");
        if (!take && !op.equals("put")) throw new CommandException("usage: storage list | take <item> [qty|all] | put <item> [qty|all]");
        need(a, 2, "storage take|put <item> [qty|all]");
        List<String> rest = new ArrayList<String>(a.subList(1, a.size()));
        String qtyStr = null;
        String last = rest.get(rest.size() - 1);
        if (rest.size() > 1 && (isNum(last) || last.equalsIgnoreCase("all"))) {
            qtyStr = last;
            rest.remove(rest.size() - 1);
        }
        Lookup.Match m = Lookup.cargoItem(join(rest));
        boolean all = "all".equalsIgnoreCase(qtyStr);
        float qty = qtyStr == null || all ? 1 : (float) num(qtyStr);
        if (qty <= 0) throw new CommandException("quantity must be positive");

        CargoAPI.CargoItemType type = CargoAPI.CargoItemType.RESOURCES;
        Object data = m.id;
        if (m.kind.equals("weapon")) type = CargoAPI.CargoItemType.WEAPONS;
        else if (m.kind.equals("wing")) type = CargoAPI.CargoItemType.FIGHTER_CHIP;
        else if (m.kind.equals("special")) { type = CargoAPI.CargoItemType.SPECIAL; data = new SpecialItemData(m.id, null); }
        else if (m.kind.equals("hullmod")) { type = CargoAPI.CargoItemType.SPECIAL; data = new SpecialItemData("modspec", m.id); }

        CargoAPI from = take ? store : cargo();
        CargoAPI to = take ? cargo() : store;
        float have = from.getQuantity(type, data);
        float amt = all ? have : Math.min(qty, have);
        if (amt <= 0) throw new CommandException((take ? "storage has no " : "you have no ") + m.name);
        // commodities that use cargo space: don't overfill the fleet when taking
        if (take && m.kind.equals("commodity") && !m.id.equals("fuel") && !m.id.equals("crew") && !m.id.equals("marines")) {
            float room = (float) Math.floor(cargo().getSpaceLeft());
            if (room <= 0) throw new CommandException("no cargo space left (" + fmt(cargo().getSpaceUsed()) + " / " + fmt(cargo().getMaxCapacity()) + ")");
            amt = Math.min(amt, room);
        }
        from.removeItems(type, data, amt);
        to.addItems(type, data, amt);
        from.removeEmptyStacks();
        to.sort();
        lastMessage = (take ? "Took " : "Stored ") + fmt(amt) + " x " + m.name;
        return (take ? "took " : "stored ") + fmt(amt) + " x " + m
            + "; your cargo space " + fmt(cargo().getSpaceUsed()) + " / " + fmt(cargo().getMaxCapacity());
    }

    private String msg(String text) {
        lastMessage = text;
        return "shown in game";
    }

    private String credits(List<String> a) throws CommandException {
        need(a, 2, "credits add|remove|set <amount>");
        String op = a.get(0).toLowerCase();
        double n = num(a.get(1));
        if (Double.isNaN(n)) throw new CommandException("not a number: " + a.get(1));
        com.fs.starfarer.api.util.MutableValue c = cargo().getCredits();
        if (op.equals("add")) c.add((float) n);
        else if (op.equals("remove")) c.subtract((float) Math.min(n, c.get()));
        else if (op.equals("set")) c.set((float) n);
        else throw new CommandException("usage: credits add|remove|set <amount>");
        lastMessage = "Credits now " + fmt(c.get());
        return "credits now " + fmt(c.get());
    }

    private String cargoCmd(List<String> a) throws CommandException {
        need(a, 2, "cargo add|remove <item> [qty|all]");
        String op = a.get(0).toLowerCase();
        List<String> rest = new ArrayList<String>(a.subList(1, a.size()));
        String qtyStr = null;
        String last = rest.get(rest.size() - 1);
        if (rest.size() > 1 && (isNum(last) || last.equalsIgnoreCase("all"))) {
            qtyStr = last;
            rest.remove(rest.size() - 1);
        }
        Lookup.Match m = Lookup.cargoItem(join(rest));
        CargoAPI c = cargo();
        boolean all = "all".equalsIgnoreCase(qtyStr);
        float qty = qtyStr == null || all ? 1 : (float) num(qtyStr);
        if (qty <= 0) throw new CommandException("quantity must be positive");

        if (op.equals("add")) {
            if (all) throw new CommandException("'all' only works with cargo remove");
            if (m.kind.equals("commodity")) c.addCommodity(m.id, qty);
            else if (m.kind.equals("weapon")) c.addWeapons(m.id, (int) qty);
            else if (m.kind.equals("wing")) c.addFighters(m.id, (int) qty);
            else if (m.kind.equals("special")) c.addSpecial(new SpecialItemData(m.id, null), qty);
            else if (m.kind.equals("hullmod")) c.addHullmods(m.id, (int) qty);
            c.sort();
            lastMessage = "Added " + fmt(qty) + " x " + m.name;
            return "added " + fmt(qty) + " x " + m;
        }
        if (op.equals("remove")) {
            CargoAPI.CargoItemType type = CargoAPI.CargoItemType.RESOURCES;
            Object data = m.id;
            if (m.kind.equals("weapon")) type = CargoAPI.CargoItemType.WEAPONS;
            else if (m.kind.equals("wing")) type = CargoAPI.CargoItemType.FIGHTER_CHIP;
            else if (m.kind.equals("special")) { type = CargoAPI.CargoItemType.SPECIAL; data = new SpecialItemData(m.id, null); }
            else if (m.kind.equals("hullmod")) { type = CargoAPI.CargoItemType.SPECIAL; data = new SpecialItemData("modspec", m.id); }
            float have = c.getQuantity(type, data);
            float amt = all ? have : Math.min(qty, have);
            if (amt <= 0) throw new CommandException("you have no " + m.name);
            c.removeItems(type, data, amt);
            c.removeEmptyStacks();
            lastMessage = "Removed " + fmt(amt) + " x " + m.name;
            return "removed " + fmt(amt) + " x " + m;
        }
        throw new CommandException("usage: cargo add|remove <item> [qty|all]");
    }

    private String refuel() {
        CargoAPI c = cargo();
        float add = c.getMaxFuel() - c.getFuel();
        if (add > 0) c.addFuel(add);
        lastMessage = "Fuel full (" + fmt(c.getFuel()) + ")";
        return "fuel " + fmt(c.getFuel()) + " / " + fmt(c.getMaxFuel());
    }

    private String repair() {
        int n = 0;
        for (FleetMemberAPI m : fleet().getFleetData().getMembersListCopy()) {
            m.getStatus().repairFully();
            m.getRepairTracker().setCR(m.getRepairTracker().getMaxCR());
            n++;
        }
        lastMessage = "Fleet repaired";
        return "repaired " + n + " ships to full hull, armor and max CR";
    }

    private String crew(List<String> a) throws CommandException {
        need(a, 1, "crew fill | crew add <n>");
        CargoAPI c = cargo();
        int add;
        if (a.get(0).equalsIgnoreCase("fill")) {
            float want = 0;
            for (FleetMemberAPI m : fleet().getFleetData().getMembersListCopy()) {
                if (!m.isMothballed()) want += m.getMaxCrew();
            }
            add = (int) Math.max(0, want - c.getCrew());
        } else if (a.get(0).equalsIgnoreCase("add") && a.size() > 1 && isNum(a.get(1))) {
            add = (int) num(a.get(1));
        } else throw new CommandException("usage: crew fill | crew add <n>");
        add = Math.min(add, Math.max(0, c.getFreeCrewSpace()));
        if (add > 0) c.addCrew(add);
        lastMessage = "Crew now " + fmt(c.getCrew());
        return "added " + add + " crew, now " + c.getCrew() + " (personnel space " + fmt(c.getTotalPersonnel()) + "/" + fmt(c.getMaxPersonnel()) + ")";
    }

    private String ship(List<String> a) throws CommandException {
        need(a, 2, "ship add <ship> [count] [empty] | ship remove <ship name or hull>");
        String op = a.get(0).toLowerCase();
        List<String> rest = new ArrayList<String>(a.subList(1, a.size()));
        if (op.equals("add")) {
            int count = 1;
            boolean empty = false;
            if (rest.size() > 1 && rest.get(rest.size() - 1).equalsIgnoreCase("empty")) { empty = true; rest.remove(rest.size() - 1); }
            if (rest.size() > 1 && isNum(rest.get(rest.size() - 1))) { count = (int) num(rest.remove(rest.size() - 1)); }
            if (count < 1 || count > 50) throw new CommandException("count must be 1-50");
            String variantId = Lookup.shipVariant(join(rest) + (empty ? " empty" : ""));
            FactionAPI pf = sector().getPlayerFaction();
            StringBuilder names = new StringBuilder();
            String hullName = null;
            for (int i = 0; i < count; i++) {
                FleetMemberAPI m = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variantId);
                try { m.setShipName(pf.pickRandomShipName()); } catch (Throwable ignored) {}
                fleet().getFleetData().addFleetMember(m);
                m.getRepairTracker().setCR(m.getRepairTracker().getMaxCR());
                hullName = m.getHullSpec().getHullName();
                if (names.length() > 0) names.append(", ");
                names.append(m.getShipName());
            }
            fleet().getFleetData().setSyncNeeded();
            fleet().getFleetData().syncIfNeeded();
            lastMessage = "Added " + count + " x " + hullName;
            return "added " + count + " x " + hullName + " (variant " + variantId + "): " + names
                    + "\nnote: new ships need crew to fly; use 'crew fill' if short";
        }
        if (op.equals("remove")) {
            String q = join(rest);
            FleetMemberAPI best = null;
            int bestScore = 0;
            for (FleetMemberAPI m : fleet().getFleetData().getMembersListCopy()) {
                int s = Math.max(Lookup.score(q, m.getId(), m.getShipName()), Lookup.score(q, m.getHullId(), m.getHullSpec().getHullName()));
                if (s > bestScore) { bestScore = s; best = m; }
            }
            if (best == null) throw new CommandException("no ship in your fleet matches '" + q + "'");
            if (best.isFlagship()) throw new CommandException("won't remove your flagship " + best.getShipName());
            fleet().getFleetData().removeFleetMember(best);
            lastMessage = "Removed " + best.getShipName();
            return "removed " + best.getShipName() + " (" + best.getHullSpec().getHullName() + ")";
        }
        throw new CommandException("usage: ship add <ship> [count] [empty] | ship remove <ship name or hull>");
    }

    private String blueprint(List<String> a, boolean learn) throws CommandException {
        List<String> rest = new ArrayList<String>(a);
        if (!learn) {
            if (rest.isEmpty() || !rest.get(0).equalsIgnoreCase("add")) throw new CommandException("usage: blueprint add <ship|weapon|wing>");
            rest.remove(0);
        }
        if (rest.isEmpty()) throw new CommandException("name a ship, weapon or fighter wing");
        List<Lookup.Match> all = new ArrayList<Lookup.Match>();
        all.addAll(Lookup.hulls());
        all.addAll(Lookup.weapons());
        all.addAll(Lookup.wings());
        Lookup.Match m = Lookup.best(join(rest), all, "blueprints");
        FactionAPI pf = sector().getPlayerFaction();
        if (learn) {
            if (m.kind.equals("hull")) pf.addKnownShip(m.id, true);
            else if (m.kind.equals("weapon")) pf.addKnownWeapon(m.id, true);
            else pf.addKnownFighter(m.id, true);
            lastMessage = "Learned blueprint: " + m.name;
            return "your faction now knows " + m;
        }
        String bp = m.kind.equals("hull") ? "ship_bp" : m.kind.equals("weapon") ? "weapon_bp" : "fighter_bp";
        cargo().addSpecial(new SpecialItemData(bp, m.id), 1);
        lastMessage = "Added blueprint: " + m.name;
        return "added blueprint item for " + m;
    }

    private String xp(List<String> a) throws CommandException {
        need(a, 2, "xp add <amount>");
        double n = num(a.get(1));
        if (Double.isNaN(n)) throw new CommandException("not a number: " + a.get(1));
        MutableCharacterStatsAPI s = sector().getPlayerStats();
        s.addXP((long) n);
        lastMessage = "Gained " + fmt((float) n) + " XP";
        return "added " + fmt((float) n) + " XP, level " + s.getLevel();
    }

    private String storyPoints(List<String> a) throws CommandException {
        need(a, 2, "storypoints add <amount>");
        double n = num(a.get(1));
        if (Double.isNaN(n)) throw new CommandException("not a number: " + a.get(1));
        MutableCharacterStatsAPI s = sector().getPlayerStats();
        s.addStoryPoints((int) n);
        lastMessage = "Story points now " + s.getStoryPoints();
        return "story points now " + s.getStoryPoints();
    }

    private String rep(List<String> a) throws CommandException {
        need(a, 2, "rep <faction> <-100..100 | +n | -n>");
        String val = a.get(a.size() - 1);
        if (!isNum(val)) throw new CommandException("usage: rep <faction> <-100..100 | +n | -n>");
        FactionAPI f = Lookup.faction(join(a.subList(0, a.size() - 1)));
        float v = (float) num(val) / 100f;
        if (val.startsWith("+") || val.startsWith("-")) f.adjustRelationship("player", v);
        else f.setRelationship("player", Math.max(-1f, Math.min(1f, v)));
        int now = Math.round(f.getRelationship("player") * 100f);
        lastMessage = f.getDisplayName() + " relation now " + now;
        return f.getDisplayName() + " relation now " + now + " (" + f.getRelationshipLevel("player") + ")";
    }

    // ------------------------------------------------------------------ movement

    private static String describe(SectorEntityToken t) {
        String where = t.getContainingLocation() == null ? "?" : t.getContainingLocation().getName();
        return t.getName() + " (" + where + ")";
    }

    private String gotoCmd(List<String> a) throws CommandException {
        need(a, 1, "goto <place>");
        SectorEntityToken target = Lookup.place(join(a));
        // use the game's own long-haul travel: Sustained Burn on long legs, unless sneaking.
        // Turn it on BEFORE laying in the course: activating it cancels autopilot.
        String extra = "";
        try {
            com.fs.starfarer.api.characters.AbilityPlugin dark = fleet().getAbility("go_dark");
            com.fs.starfarer.api.characters.AbilityPlugin burn = fleet().getAbility("sustained_burn");
            boolean far = fleet().getContainingLocation() != target.getContainingLocation() || Misc.getDistance(fleet(), target) > 3000f;
            if (far && burn != null && !burn.isActive() && burn.isUsable() && (dark == null || !dark.isActive())) {
                burn.activate();
                extra = "; Sustained Burn on";
            }
        } catch (Throwable ignored) {}
        sector().layInCourseFor(target);
        Governor.unpause();
        float ly = Misc.getDistanceToPlayerLY(target);
        lastMessage = "Course laid in for " + target.getName();
        return "course laid in for " + describe(target) + ", " + String.format("%.1f", ly) + " ly away; game unpaused" + extra + "\n"
                + "the fleet flies there on autopilot; clicking on the map cancels it";
    }

    private String teleport(List<String> a) throws CommandException {
        need(a, 1, "teleport <place>");
        SectorEntityToken target = Lookup.place(join(a));
        CampaignFleetAPI pf = fleet();
        sector().getCampaignUI().clearLaidInCourse();
        if (pf.getContainingLocation() == target.getContainingLocation()) {
            float off = target.getRadius() + pf.getRadius() + 150f;
            pf.setLocation(target.getLocation().x, target.getLocation().y + off);
        } else {
            JumpPointAPI.JumpDestination dest = new JumpPointAPI.JumpDestination(target, null);
            dest.setMinDistFromToken(target.getRadius() + 150f);
            sector().doHyperspaceTransition(pf, pf, dest);
        }
        lastMessage = "Teleported to " + target.getName();
        return "teleported to " + describe(target);
    }

    private String dock(List<String> a) throws CommandException {
        SectorEntityToken target;
        if (a.isEmpty()) {
            MarketAPI m = nearbyMarket(2000f);
            if (m == null) throw new CommandException("no market nearby; use goto <market> first");
            target = m.getPrimaryEntity();
        } else {
            target = Lookup.place(join(a));
        }
        CampaignFleetAPI pf = fleet();
        if (pf.getContainingLocation() != target.getContainingLocation()
                || Misc.getDistance(pf, target) - target.getRadius() - pf.getRadius() > 2000f) {
            throw new CommandException(target.getName() + " is too far away to dock; use goto first");
        }
        CampaignUIAPI ui = sector().getCampaignUI();
        InteractionDialogAPI open = ui.getCurrentInteractionDialog();
        if (open != null && open.getInteractionTarget() == target) {
            lastMessage = "Docked at " + target.getName();
            return "already docked at " + describe(target);
        }
        if (open != null) throw new CommandException("a dialog is already open");
        // Let the game dock the way a click does: set the fleet's interaction target and fly to it.
        // showInteractionDialog() from a script is unreliable, so it's only a fallback.
        pf.setInteractionTarget(target);
        sector().layInCourseFor(target);
        Governor.unpause();
        boolean shown = false;
        try { shown = ui.showInteractionDialog(target); } catch (Throwable ignored) {}
        lastMessage = "Docking at " + target.getName();
        return (shown ? "opened dialog with " : "heading in to dock at ") + describe(target);
    }

    // ------------------------------------------------------------------ trade

    private MarketAPI nearbyMarket(float maxDist) {
        CampaignFleetAPI pf = fleet();
        MarketAPI best = null;
        float bestD = Float.MAX_VALUE;
        for (MarketAPI m : sector().getEconomy().getMarkets(pf.getContainingLocation())) {
            if (m.isHidden() || m.getPrimaryEntity() == null) continue;
            float d = Misc.getDistance(pf, m.getPrimaryEntity()) - pf.getRadius() - m.getPrimaryEntity().getRadius();
            if (d < bestD) { bestD = d; best = m; }
        }
        return bestD <= maxDist ? best : null;
    }

    /** The market the player is docked at, or one right next to the fleet. */
    private MarketAPI tradeMarket() throws CommandException {
        InteractionDialogAPI d = sector().getCampaignUI().getCurrentInteractionDialog();
        if (d != null && d.getInteractionTarget() != null && d.getInteractionTarget().getMarket() != null) {
            return d.getInteractionTarget().getMarket();
        }
        MarketAPI m = nearbyMarket(600f);
        if (m == null) throw new CommandException("not docked at or next to a market; use goto <market> first");
        return m;
    }

    private String trade(List<String> a, boolean buying) throws CommandException {
        String usage = buying ? "buy <commodity> <qty|max> [black]" : "sell <commodity> <qty|all> [black]";
        need(a, 2, usage);
        List<String> rest = new ArrayList<String>(a);
        boolean black = false;
        if (rest.get(rest.size() - 1).equalsIgnoreCase("black")) { black = true; rest.remove(rest.size() - 1); }
        if (rest.size() < 2) throw new CommandException("usage: " + usage);
        String qtyStr = rest.remove(rest.size() - 1).toLowerCase();
        Lookup.Match cm = Lookup.commodity(join(rest));
        CommoditySpecAPI spec = (CommoditySpecAPI) cm.ref;
        String id = cm.id;

        MarketAPI market = tradeMarket();
        SubmarketAPI sub = market.getSubmarket(black ? "black_market" : "open_market");
        if (sub == null) throw new CommandException(market.getName() + " has no " + (black ? "black market" : "open market"));
        SubmarketPlugin plugin = sub.getPlugin();
        SubmarketPlugin.TransferAction act = buying ? SubmarketPlugin.TransferAction.PLAYER_BUY : SubmarketPlugin.TransferAction.PLAYER_SELL;
        if (plugin.isIllegalOnSubmarket(id, act)) {
            throw new CommandException(cm.name + " can't be " + (buying ? "bought" : "sold") + " on the " + sub.getNameOneLine()
                    + " at " + market.getName() + (black ? "" : "; try adding 'black' to use the black market"));
        }
        if (!black && market.getFaction().isHostileTo("player")) {
            throw new CommandException(market.getFaction().getDisplayName() + " is hostile; the open market won't trade with you");
        }
        // play it straight: trade only where the docking rules allow it, as the Trade screen does
        com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode mode = tradeMode(market);
        if (mode == com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode.NONE) {
            throw new CommandException(market.getName() + " won't trade with you right now (docking refused: reputation, or patrols tracking you)");
        }
        if (!black && mode != com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode.OPEN) {
            throw new CommandException("the open market is closed while docked with the transponder off; add 'black' to use the black market");
        }
        CargoAPI c = cargo();
        float tariff = sub.getTariff();

        if (!buying) {
            float have = c.getCommodityQuantity(id);
            float qty = qtyStr.equals("all") ? have : (float) num(qtyStr);
            if (Float.isNaN(qty) || qty <= 0) throw new CommandException("usage: " + usage);
            if (have < 1f) throw new CommandException("you have no " + cm.name);
            // 1.0.9: the keep list applies to every sale (the metals were sold through this command)
            int floor = Trader.keepFloor(id, cm.name);
            qty = Guard.sellable(have, qty, floor);
            if (qty <= 0) throw new CommandException("won't sell " + cm.name + ": keeping " + (floor == Integer.MAX_VALUE ? "all of it" : "at least " + floor) + " (captain_rules keep)");
            try { plugin.updateCargoPrePlayerInteraction(); } catch (Throwable ignored) {}
            float gross = market.getDemandPrice(id, qty, true);
            float net = gross * (1f - tariff);
            c.removeCommodity(id, qty);
            sub.getCargo().addCommodity(id, qty);
            c.getCredits().add(net);
            reportTrade(market, sub, mode, id, qty, false, net, tariff);
            lastMessage = "Sold " + fmt(qty) + " " + cm.name + " for " + fmt(net) + " cr";
            return "sold " + fmt(qty) + " " + cm.name + " at " + market.getName() + " (" + sub.getNameOneLine() + ") for "
                    + fmt(net) + " cr (" + fmt(net / qty) + "/unit after " + Math.round(tariff * 100) + "% tariff); credits " + fmt(c.getCredits().get());
        }

        try { plugin.updateCargoPrePlayerInteraction(); } catch (Throwable ignored) {}
        float stock = sub.getCargo().getCommodityQuantity(id);
        float unit = market.getSupplyPrice(id, 1, true) * (1f + tariff);
        float space;
        if (spec.isFuel()) space = c.getMaxFuel() - c.getFuel();
        else if (spec.isPersonnel()) space = c.getMaxPersonnel() - c.getTotalPersonnel();
        else space = (c.getMaxCapacity() - c.getSpaceUsed()) / Math.max(0.01f, spec.getCargoSpace());
        if (black && Shipyard.blackLocked(sub)) throw new CommandException("black market buying is off with the transponder on (rule black_market_with_transponder)"); // 1.0.9
        float spendable = Math.max(0f, c.getCredits().get() - Rules.f("reserve_credits", 5000f)); // 1.0.9: keep the reserve
        float afford = unit > 0 ? spendable / unit : stock;
        float qty = qtyStr.equals("max") || qtyStr.equals("all") ? Float.MAX_VALUE : (float) num(qtyStr);
        if (Float.isNaN(qty) || qty <= 0) throw new CommandException("usage: " + usage);
        float limit = Math.min(Math.min(stock, space), afford);
        qty = (float) Math.floor(Math.min(qty, limit));
        if (qty <= 0) {
            throw new CommandException("can't buy " + cm.name + ": stock " + fmt(stock) + ", room for " + fmt(space)
                    + ", can afford " + fmt(afford));
        }
        float cost = market.getSupplyPrice(id, qty, true) * (1f + tariff);
        // price rises with quantity, so trim until affordable
        while (cost > c.getCredits().get() && qty > 1) {
            qty = (float) Math.floor(qty * 0.95f);
            cost = market.getSupplyPrice(id, qty, true) * (1f + tariff);
        }
        if (cost > c.getCredits().get()) throw new CommandException("not enough credits");
        // 1.0.9: the market-based price ceiling applies to explicit buys too
        float cap = Trader.ceiling(id);
        boolean ceilingCut = false; // 1.1.2: the note said "limited by credits" when the price ceiling cut the amount (Nov 2)
        if (cap > 0f && cost / qty > cap) {
            float hardCap = cap / Math.max(0.01f, Rules.f((id.equals("supplies") ? "supply" : "fuel") + "_price_factor", 1.35f)) * Rules.f("emergency_price_factor", 2f);
            if (Trader.emergency(id) && cost / qty <= hardCap) {
                float eq = (float) Math.floor(Math.min(qty, Trader.emergencyQty(id)));
                if (eq < 1f) throw new CommandException(cm.name + " is over the ceiling here and the emergency amount is already aboard");
                qty = eq; cost = market.getSupplyPrice(id, qty, true) * (1f + tariff);
                EventLog.alert("trade", "EMERGENCY: buying " + fmt(qty) + " " + cm.name + " at " + fmt(cost / qty) + "/unit, over the ceiling of " + fmt(cap));
            } else {
                while (qty > 1 && cost / qty > cap) { qty = (float) Math.floor(qty * 0.9f); cost = market.getSupplyPrice(id, qty, true) * (1f + tariff); ceilingCut = true; }
                if (cost / qty > cap) throw new CommandException(cm.name + " costs " + fmt(cost / qty) + "/unit here, over the market ceiling of " + fmt(cap) + " (median x factor); not bought");
            }
        }
        sub.getCargo().removeCommodity(id, qty);
        c.addCommodity(id, qty);
        c.getCredits().subtract(cost);
        reportTrade(market, sub, mode, id, qty, true, cost, tariff);
        String note = qtyStr.equals("max") || qtyStr.equals("all") || qty >= (float) num(qtyStr) ? "" :
                " (limited by " + (ceilingCut ? "the price ceiling of " + fmt(cap) + "/unit: the price rises as stock runs out" : qty >= stock ? "stock" : qty >= space ? "cargo space" : "credits") + ")";
        lastMessage = "Bought " + fmt(qty) + " " + cm.name + " for " + fmt(cost) + " cr";
        return "bought " + fmt(qty) + " " + cm.name + " at " + market.getName() + " for " + fmt(cost) + " cr ("
                + fmt(cost / qty) + "/unit incl. " + Math.round(tariff * 100) + "% tariff)" + note + "; credits " + fmt(c.getCredits().get());
    }

    /** OPEN, SNEAK (docked with the transponder off: black market only) or NONE, as the docking rules set it. */
    static com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode tradeMode(MarketAPI market) {
        try {
            InteractionDialogAPI d = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
            if (d != null && d.getInteractionTarget() != null && d.getInteractionTarget().getMarket() == market) {
                String s = d.getInteractionTarget().getMemoryWithoutUpdate().getString("$tradeMode");
                if (s != null && s.length() > 0) return com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode.valueOf(s);
            }
        } catch (Throwable ignored) {}
        return Global.getSector().getPlayerFleet().isTransponderOn()
                ? com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode.OPEN
                : com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode.SNEAK;
    }

    /**
     * Tells the game about a trade exactly as its Trade screen does: the submarket records it (price impact,
     * smuggling suspicion, trade totals for reputation and trade XP), every campaign listener hears it (missions,
     * events), and the economy is stepped so prices react.
     */
    static void reportTrade(MarketAPI market, SubmarketAPI sub, com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode mode,
                            String id, float qty, boolean bought, float credits, float tariff) {
        com.fs.starfarer.api.campaign.PlayerMarketTransaction tx = new com.fs.starfarer.api.campaign.PlayerMarketTransaction(market, sub, mode);
        if (bought) tx.getBought().addCommodity(id, qty); else tx.getSold().addCommodity(id, qty);
        tx.setCreditValue(bought ? -credits : credits);
        try {
            tx.getLineItems().add(new com.fs.starfarer.api.campaign.PlayerMarketTransaction.TransactionLineItem(id,
                    bought ? com.fs.starfarer.api.campaign.PlayerMarketTransaction.LineItemType.BOUGHT : com.fs.starfarer.api.campaign.PlayerMarketTransaction.LineItemType.SOLD,
                    CargoAPI.CargoItemType.RESOURCES, sub, qty, credits / qty, tariff, Global.getSector().getClock().getTimestamp()));
        } catch (Throwable ignored) {}
        sub.getPlugin().reportPlayerMarketTransaction(tx);
        Global.getSector().reportPlayerMarketTransaction(tx);
        try { Global.getSector().getEconomy().doubleStep(); } catch (Throwable ignored) {}
    }

    private String prices(List<String> a) throws CommandException {
        need(a, 1, "prices <commodity>");
        Lookup.Match cm = Lookup.commodity(join(a));
        final String id = cm.id;
        List<String[]> rows = new ArrayList<String[]>();
        final List<Float> sellVals = new ArrayList<Float>();
        List<MarketAPI> markets = sector().getEconomy().getMarketsCopy();
        for (MarketAPI m : markets) {
            if (m.isHidden() || m.getPrimaryEntity() == null) continue;
            SubmarketAPI open = m.getSubmarket("open_market");
            if (open == null) continue;
            CommodityOnMarketAPI com = m.getCommodityData(id);
            if (com == null) continue;
            float tariff = open.getTariff();
            boolean illegal = open.getPlugin().isIllegalOnSubmarket(id, SubmarketPlugin.TransferAction.PLAYER_SELL);
            float buy = m.getSupplyPrice(id, 1, true) * (1f + tariff);
            float sell = m.getDemandPrice(id, 1, true) * (1f - tariff);
            float stock = open.getCargoNullOk() == null ? -1 : open.getCargo().getCommodityQuantity(id);
            String sys = m.getStarSystem() == null ? "hyperspace" : m.getStarSystem().getBaseName();
            String row = m.getName() + " | " + sys + " | " + m.getFaction().getDisplayName() + " | size " + m.getSize()
                    + " | buy " + fmt(buy) + " | sell " + fmt(sell)
                    + " | stock " + (stock < 0 ? "?" : fmt(stock))
                    + " | excess " + com.getExcessQuantity() + " deficit " + com.getDeficitQuantity()
                    + (illegal ? " | ILLEGAL (black market only)" : "")
                    + (m.getFaction().isHostileTo("player") ? " | HOSTILE" : "")
                    + " | " + String.format("%.1f", Misc.getDistanceToPlayerLY(m.getPrimaryEntity())) + " ly";
            rows.add(new String[]{row});
            sellVals.add(sell);
        }
        // sort by sell price, best first
        List<Integer> idx = new ArrayList<Integer>();
        for (int i = 0; i < rows.size(); i++) idx.add(i);
        Collections.sort(idx, new Comparator<Integer>() {
            public int compare(Integer x, Integer y) { return Float.compare(sellVals.get(y), sellVals.get(x)); }
        });
        StringBuilder b = new StringBuilder(cm.name + " prices per unit, open markets, sorted by sell price (stock is as of last visit):");
        for (Integer i : idx) b.append("\n").append(rows.get(i)[0]);
        return b.toString();
    }

    private String marketInfo(List<String> a) throws CommandException {
        MarketAPI m;
        if (a.isEmpty()) m = tradeMarket();
        else {
            SectorEntityToken t = Lookup.place(join(a));
            m = t.getMarket();
            if (m == null) throw new CommandException(t.getName() + " has no market");
        }
        StringBuilder b = new StringBuilder();
        b.append(m.getName()).append(" | ").append(m.getFaction().getDisplayName()).append(" | size ").append(m.getSize())
         .append(" | stability ").append(Math.round(m.getStabilityValue()))
         .append(m.isFreePort() ? " | free port" : "")
         .append(" | ").append(String.format("%.1f", Misc.getDistanceToPlayerLY(m.getPrimaryEntity()))).append(" ly");
        b.append("\nsubmarkets:");
        for (SubmarketAPI s : m.getSubmarketsCopy()) {
            b.append(" ").append(s.getSpecId()).append(" (tariff ").append(Math.round(s.getTariff() * 100)).append("%)");
        }
        SubmarketAPI open = m.getSubmarket("open_market");
        float tariff = open == null ? 0.3f : open.getTariff();
        b.append("\ncommodity | buy/unit | sell/unit | excess | deficit | open-market stock");
        for (CommodityOnMarketAPI com : m.getAllCommodities()) {
            if (com.getCommodity().isMeta() || com.getCommodity().isNonEcon()) continue;
            String id = com.getId();
            float stock = open == null || open.getCargoNullOk() == null ? -1 : open.getCargo().getCommodityQuantity(id);
            b.append("\n").append(com.getCommodity().getName()).append(" [").append(id).append("]")
             .append(" | ").append(fmt(m.getSupplyPrice(id, 1, true) * (1f + tariff)))
             .append(" | ").append(fmt(m.getDemandPrice(id, 1, true) * (1f - tariff)))
             .append(" | ").append(com.getExcessQuantity()).append(" | ").append(com.getDeficitQuantity())
             .append(" | ").append(stock < 0 ? "?" : fmt(stock));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ lookup

    private String find(List<String> a) throws CommandException {
        need(a, 1, "find <text>");
        String q = join(a);
        List<Lookup.Match> r = Lookup.rank(q, Lookup.places());
        if (r.isEmpty()) throw new CommandException("nothing found for '" + q + "'");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(25, r.size()); i++) {
            Lookup.Match m = r.get(i);
            b.append(i == 0 ? "" : "\n").append(m);
            Object ref = m.ref;
            if (ref instanceof SectorEntityToken) {
                SectorEntityToken t = (SectorEntityToken) ref;
                if (t.getContainingLocation() != null) b.append(" in ").append(t.getContainingLocation().getName());
                if (t.getMarket() != null && !t.getMarket().isPlanetConditionMarketOnly())
                    b.append(" | ").append(t.getMarket().getFaction().getDisplayName()).append(" size ").append(t.getMarket().getSize());
                b.append(" | ").append(String.format("%.1f", Misc.getDistanceToPlayerLY(t))).append(" ly");
            }
        }
        return b.toString();
    }

    private String list(List<String> a) throws CommandException {
        need(a, 1, "list ships|weapons|wings|commodities|specials|hullmods|factions|markets|systems [filter]");
        String what = a.get(0).toLowerCase();
        String filter = a.size() > 1 ? join(a.subList(1, a.size())) : null;
        List<Lookup.Match> items;
        if (what.startsWith("ship") || what.startsWith("hull") && !what.startsWith("hullmod")) items = Lookup.hulls();
        else if (what.startsWith("weapon")) items = Lookup.weapons();
        else if (what.startsWith("wing") || what.startsWith("fighter")) items = Lookup.wings();
        else if (what.startsWith("commod")) items = Lookup.commodities();
        else if (what.startsWith("special") || what.startsWith("item")) items = Lookup.specials();
        else if (what.startsWith("hullmod") || what.startsWith("mod")) items = Lookup.hullmods();
        else if (what.startsWith("faction")) {
            items = new ArrayList<Lookup.Match>();
            for (FactionAPI f : sector().getAllFactions()) {
                if (!f.isShowInIntelTab() && !f.isPlayerFaction()) continue;
                items.add(new Lookup.Match("faction", f.getId(), f.getDisplayName()
                        + " (relation " + Math.round(f.getRelationship("player") * 100) + ")", f));
            }
        } else if (what.startsWith("market")) {
            items = new ArrayList<Lookup.Match>();
            for (Lookup.Match m : Lookup.places()) if (m.kind.equals("market")) items.add(m);
        } else if (what.startsWith("system")) {
            items = new ArrayList<Lookup.Match>();
            for (Lookup.Match m : Lookup.places()) if (m.kind.equals("system")) items.add(m);
        } else throw new CommandException("can list: ships, weapons, wings, commodities, specials, hullmods, factions, markets, systems");

        List<Lookup.Match> shown = filter == null ? items : Lookup.rank(filter, items);
        StringBuilder b = new StringBuilder(shown.size() + " " + what + (filter == null ? "" : " matching '" + filter + "'") + ":");
        int max = 200;
        for (int i = 0; i < Math.min(max, shown.size()); i++) {
            Lookup.Match m = shown.get(i);
            b.append("\n").append(m.id).append(" = ").append(m.name);
            if (m.ref instanceof ShipHullSpecAPI) {
                ShipHullSpecAPI h = (ShipHullSpecAPI) m.ref;
                b.append(" | ").append(h.getHullSize()).append(" | ").append(h.getFleetPoints()).append(" FP | ").append(h.getManufacturer());
            } else if (m.ref instanceof WeaponSpecAPI) {
                WeaponSpecAPI w = (WeaponSpecAPI) m.ref;
                b.append(" | ").append(w.getSize()).append(" ").append(w.getType());
            } else if (m.ref instanceof FighterWingSpecAPI) {
                b.append(" | tier ").append(((FighterWingSpecAPI) m.ref).getTier());
            } else if (m.ref instanceof CommoditySpecAPI) {
                b.append(" | base ").append(fmt(((CommoditySpecAPI) m.ref).getBasePrice())).append(" cr");
            }
        }
        if (shown.size() > max) b.append("\n... ").append(shown.size() - max).append(" more; add a filter");
        return b.toString();
    }

    @SuppressWarnings("unused")
    private static String stackName(CargoStackAPI s) { return s.getDisplayName(); }
}
