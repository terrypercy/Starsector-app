package claudecaptain;

/** The 'help' text: every command, old names included. */
final class Help {
    private Help() {}

    static final String TEXT =
        "GOVERNOR (time only passes inside a run budget while Claude is in control)\n"
      + "  run <days> | run until arrived|done|event [max <days>] | unpause (= run gov_unpause_days) | pause\n"
      + "  control claude | control player | governor\n"
      + "EVENTS / STATE\n"
      + "  events since <n> (-> recent.txt) | state | status (also status.txt) | selftest | rules | dialog sig\n"
      + "GOALS (wait until done/failed; every dialog on the way goes through dialog_rules.txt)\n"
      + "  travel|goto <place> | approach <place> <su> | dock [place] | wait <days>\n"
      + "  resupply [days] | repair | hire [officer] [personality] | officers | bounty <name> (result and payout) | bounty pay <name> (pay a missed kill) | commission <faction> | explore [here]\n"
      + "  hunt <n> | hunt auto [count] | go <market> [refill] | trip <n> [cap <cr>] (old 'run <n>') | runsell <n>\n"
      + "  loop <n> | trade loop <n> | ask <question> | <option> | ...\n"
      + "  cancel | resume | hunt stop|status|resume | loop stop\n"
      + "TALK / DIALOGS\n"
      + "  dialog | dialog close (stuck dialog) | choose <number|text> | talk|contact [person] | loot | loot take|leave | recover [list|<n>]\n"
      + "  commission (standing report) | rep (standing report) | explore list\n"
      + "TRADE / SHIPYARD (Trade Runner)\n"
      + "  plan | bounties | fleet | ships | weapons [filter] | find <words> [for <ship>] | buyship <n|name> | buyspec <hullmod> | buyweapon <name> [qty]\n"
      + "  autofit <ship> [variant <id>] | assign <officer> to <ship> | flagship <ship> | autoassign | restore <ship|all> | crew [fill] | store <ship|combat> | retrieve <ship|all>\n"
      + "  activate <ship|all> | equip <ship> with <weapon> [xN] | unequip <ship> <weapon|all>\n"
      + "  sellship <ship> [black] [confirm] (quote first; weapons/wings go to cargo) | sellweapons [all|name] [black] [confirm] | mothball <ship> | repairs [on|off [ship|all]]\n"
      + "  addmod <ship> <hullmod> | removemod <ship> <hullmod> | flux <ship|all> fill | flux <ship> <vents> <caps> | loadout [ship|all]\n"
      + "  missions | accept <n|name>\n"
      + "BRIDGE\n"
      + "  sell <commodity> <qty|all> [black] | buy <commodity> <qty|max> [black] | prices <commodity> | market [name] | find <text>\n"
      + "  list ships|weapons|wings|commodities|specials|hullmods|factions|markets|systems [filter]\n"
      + "  storage list|take|put ... | ability [name] [on|off] | stop (clear course)\n"
      + "  battle | battle auto on|off | battle deploy auto|all|<ships> | battle margin <n>\n"
      + "  say <text> | shutup | msg <text> | optional <command> (failure doesn't stop the batch)\n"
      + "CHEATS (allow_cheats = true only): credits, cargo add|remove, refuel, repair instant, crew add <n>, ship add|remove,\n"
      + "  blueprint add, learn, xp, storypoints, rep <faction> <n>, teleport\n"
      + "Batch header lines: #id <unique> (required to dedupe), #on_error continue";
}
