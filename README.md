# Claude Captain (Starsector 0.98a-RC8)

Lets Claude captain your fleet: you (or Claude) give goals, the mod flies, docks, trades, hunts,
resupplies and explores, answering the routine dialogs on the way, and it never lets game time run
away while nobody is watching. Replaces **Claude Bridge** and **Claude Trade Runner** (disable both;
see MIGRATION.md).

## The panel

One compact, fixed-width widget (top left by default; drag its title bar to move it, the spot is
remembered). **F8** folds it, **F9** opens the next section. Colours mean the same everywhere:
green good, amber watch it, red act now, cyan Claude/goal.

```
+--------------------------------------------+
| - CAPTAIN   v1.0.0  drag to move           |  <- title: click folds, drag moves
| 128k cr   12d sup   18 ly   CR 72%         |  <- credits | supply days (maintenance) | fuel range | lowest CR
| [ RUN 3.0d left (until done) ]             |  <- governor chip: RUN / PAUSED: reason / PLAYER
| > Travel to Yesod - 2/4 jumps, 3.1 ly      |  <- current goal with progress
| ! supplies for 12 days of maintenance      |  <- active alarm (red)
| Hegemony  4/10 to commission               |
| [=========-----------]                     |  <- reputation bar towards Favorable
| NEXT                                       |
| [ 1. Resupply 60 days            ]         |  <- click once to select, again to run
| [ 2. Hunt Kade Ibarra (14 DP)    ]         |
| [ 3. Sell 120 Food at Jangala    ]         |
| [ [-] FLEET (6)                  ]         |  <- F9 cycles: fleet > bounties > trades > explore
|   Wolf  CR 35%  hull 100%                  |
|   Hound  CR 80%  hull 40%                  |
| [ [+] BOUNTIES (2)               ]         |
| [ [+] TRADES (3)                 ]         |
| [ [+] EXPLORE (4)                ]         |
| #1042 GOAL goal: DONE Dock at Jangala      |  <- latest event
+--------------------------------------------+
```

Clicking the governor chip (twice) hands control between Claude and you. While **you** are in
control the governor only warns; while **Claude** is, time passes only inside a run budget.
The panel hides in menus, battles and non-market dialogs, and shows (above the dock screen) while
docked at a market. If the panel ever fails it switches itself off and logs why; commands keep working.

## Talking to it

Write a new file to `saves/common/claude_captain/inbox.txt` (`.data` on disk):

```
#id 2026-10-29-001
travel Yesod
resupply
hunt auto 2
run until done max 30
```

* `#id` dedupes batches (a repeated id is ignored); `#on_error continue` keeps going after a failure.
* Instant commands run at once; goals (`travel`, `dock`, `resupply`, `repair`, `hire`, `commission`,
  `explore`, `hunt`, `go`, `trip`, `loop`, `wait`, `ask`) run until done or failed, then the next line.
* `run`, `pause`, `control` lines apply the moment the batch arrives, wherever they stand.
* A new batch drops the unfinished rest of the old one; a running goal continues unless the new batch
  starts another goal or says `cancel`. So you can send `status` or `events since 1040` any time.
* Results: `events.txt` (append-only, numbered), `recent.txt` (after `events since <n>`), `state.txt`
  (JSON snapshot, every 5 s), `status.txt` (old text status, on `status`), `plans.txt`, `runlog.txt`,
  `huntlog.txt`, `combat.txt`. `running.txt` is a heartbeat: under 30 s old = game open, don't swap the jar.

`help` lists every command. Highlights:

| | |
|---|---|
| `run <days>` / `run until arrived\|done\|event [max <d>]` | grant game time (Claude in control) |
| `events since <n>` | newer events to recent.txt |
| `travel <place>` / `dock <place>` | flies there, jumps and gravity wells handled, other dialogs by rule |
| `resupply [days]` | supplies = maintenance x days + CR-recovery budget - aboard, at most `resupply_max_credit_pct` of credits; fuel for `resupply_fuel_ly` |
| `repair` | dockyard repair; says so when nothing needs it or the option is disabled |
| `hire [officer] [personality]` | nearest officer for hire (comm directories), docks, talks, hires |
| `talk <person>` + `choose <n>` | comm-directory conversation by hand |
| `commission <faction>` | only when standing is Favorable (10); otherwise reports the gap and ways to close it |
| `hunt <n>` / `hunt auto [count]` | bounty hunting with resupply/repair between targets, exploration detours, pause at ship recovery |
| `recover [list\|<n>]` | recoverable ships after a battle (picking via API only with `recover_api = true`) |
| `explore [here\|list]` | salvage and surveys in this system |
| `plan` / `trip <n> [cap <cr>]` / `go <market> [refill]` / `loop <n>` | Trade Runner's trading |

## Rules files (edit while the game runs)

* `captain_rules.txt`: governor thresholds (`gov_*`), resupply sizing, hunting, exploration, trading,
  advisor thresholds, panel keys, `allow_cheats = false`. Missing keys use the built-in defaults.
* `dialog_rules.txt`: how dialogs are answered, re-read every 3 seconds. Lines:
  `<tags> : match <regex> -> choose <option regex> | pause [note] | loot`. An unrecognised dialog
  pauses with its full signature in an ALERT event: add a rule and `resume`, or `choose <n>`.

## Ship recovery

`hunt_recovery_pause = true` stops at "Consider ship recovery". `recover list` shows the recoverable
ships (from the battle's encounter context). Picking normally happens on screen in the game's picker.
`recover <n>` with `recover_api = true` calls the game's `FleetEncounterContext.recoverShips` directly;
this has NOT been tried in game and may skip costs the picker charges, so it is off by default.

## Build and test

Needs a JDK (8+) and python3. From the repo root:

    C=/path/to/starsector-core OUT=/tmp/cc-out tools/test.sh

Copy `$OUT/claude_captain.jar` to `mods/Claude Captain/jars/` **only while the game is closed**.
See DESIGN.md for the architecture and MIGRATION.md for switching from the old mods.
