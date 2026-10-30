# Claude Captain - design

One mod (`claude_captain`, package `claudecaptain`) that supersedes Claude Bridge 1.11.14 and
Claude Trade Runner 1.2.8. Comm folder: `saves/common/claude_captain/` (the game adds `.data` on disk).

## Why (problems from live play)

| # | Problem | Answer here |
|---|---------|-------------|
| 1 | every jump point / gravity well / docked dialog interrupted `goto` | goals handle every dialog on the way (dialog rules + Trade Runner's jump logic) |
| 2 | game left unpaused, drifted 5 weeks, 0% CR accidents | safety governor: time only passes inside a granted run budget; alarms pause |
| 3 | outbox overwritten per batch | append-only `events.txt` with sequence numbers, `events since <n>` |
| 4 | officers, comm people, commissions, bar, salvage, pursuit picker unreadable | `state.txt` lists comm-directory people with roles; goals `hire`, `talk`, `commission`, `explore`, `recover`; full dialog dumps in events |
| 5 | new dialog types needed code + restart | `dialog_rules.txt`, re-read every 3 s |
| 6 | two mods, duplicate state, two restarts | one jar, one inbox, one rules file, one panel |
| 7 | resupply sized from inflated burn (629 supplies / 85k) | `SupplyMath`: maintenance burn x days + CR-recovery budget, capped by `resupply_max_credit_pct` |
| 8 | dockyard repair `[disabled]` when nothing to repair | `repair` checks need first; state shows dockyard / need / live option state |
| 9 | autopilot can't route to a fleet in another system | kept: steer to the fleet's star system first (Travel goal and BountyRunner) |
| 10 | commission needs Favorable (10); stipend 20000 + 5000/level | `Reputation`/`RepMath`; per-faction distance in state, suggestions, panel bar |

## Layers

```
 inbox.txt ──> Captain (EveryFrameScript)
                 │  batch queue: instant commands run at once, goals run until done/failed
                 │  governor lines (run/pause/control) are hoisted to batch start
                 ├─ Governor ── GovMath (pure: alarms, latch, budget, run parsing)
                 ├─ Goals: Travel, Wait, Resupply, Repair, Hire, Commission, Explore/Visit, Runner(trade|hunt)
                 │     └─ dialogs: AutoDialog.handle -> DialogAgent (signature) -> DialogRules (pure) -> click
                 │                 fallback: Trade Runner's built-in answers, JUMP_1 order
                 ├─ TripRunner (trade trips, go, loops)      ┐ ported from Trade Runner,
                 ├─ BountyRunner (+ resupply/repair/explore)  ┘ pauses/unpauses through the governor
                 ├─ CommandRunner, Lookup, LootTracker, DialogBridge (ported from Bridge)
                 ├─ EventLog ── EventCore (pure)  -> events.txt / recent.txt
                 └─ StateWriter ── Json (pure)    -> state.txt
 CaptainHud ── Hud (pure rows/colours)  one panel; Advisor/TradeAdvisor feed "next steps"
 data/scripts/plugins/ClaudeCaptainCombat  auto-battle (ported), logs to events
```

Pure classes (no game API, unit-tested in `tests/CaptainTest.java`): `GovMath`, `SupplyMath`,
`DialogRules`, `EventCore`, `RepMath`, `ExploreMath`, `Hud`, `Json`, plus the ported `Planner`.

## Governor

* "Armed" (Claude in control) by any inbox batch, `control claude`, or the panel chip; disarmed by
  `control player` or the chip. Not armed after loading a save (`gov_arm_on_load = false`).
* Armed: with no budget, the campaign is paused every frame (dialogs and battles pause by themselves).
  `run <days>` / `run until arrived|done|event [max <d>]` grant time. A batch that starts a goal and has
  no `run` line gets an implicit `run until done` capped at `gov_implicit_max_days` (15).
* Every 0.5 s: supplies vs **maintenance** burn (`gov_min_supply_days`, re-fires at 1/2 and 1/4), fuel
  vs nearest friendly market + margin, lowest combat CR (`gov_min_cr`, except while the plan rests /
  repairs / just fought), incidents (hull loss or ships gone outside battle), a visible stronger hostile
  that got closer (`gov_threat_ratio`), unrecognised dialog, battle with auto-battle off, budget used up.
  A firing alarm revokes the budget, pauses, and logs ALERT. `run` acknowledges current alarms (they stay
  quiet until they clear and come back, or get worse).
* Player in control: same checks, WARN events and on-screen messages, nothing pauses.
* Every place in the ported code that unpaused the game now calls `Governor.unpause()`.

## Events and state

* `events.txt`: `#<seq> <date> <LEVEL> <kind> b=<batch>: text`, continuation lines `  | ...`.
  Levels CMD (command + result), INFO, GOAL (done), WARN, ALERT (paused). Sequence survives restarts
  (read back from the file). ~`events_keep_lines` lines kept, whole events trimmed from the front.
  `events since <n>` writes the newer ones to `recent.txt`.
* `state.txt`: JSON, every `gov_state_seconds` and on `state`/`status`. Sections: governor, goal,
  location, docked (submarkets, tariff, trade mode, storage access, repair {dockyard, needed, needs,
  option}, people with post/post_id/role, officer personality/level), logistics (maintenance and
  current burn, both supply-day figures, CR-recovery supplies, fuel range), fleet (CR, max CR, hull,
  d-mods, DP, officer, loadout summary), cargo, bounties (DP, reward, posting faction, ok/why),
  reputation (per faction rel/to_favorable, required, stipend, target + suggestions), nearby fleets,
  explore sites, open dialog (options, text, loot).

## Dialog rules

`<tags> : match <regex> -> choose <option regex> | pause [note] | loot`. The match regex runs over a
signature built from the live dialog (`type= target= faction= hostile= useful= scan= illegal= bounty=
stronger= salvage= loot= target_goal= opts=[..] text=..`). First applicable rule whose action can be
done wins; disabled and story-point/confirm options are never clicked; after `resume` the dialog's
`pause` rules are skipped (no pause loop). Goals pass their context tags (travel, trade, hunt, engage,
pursuit, after_battle, explore, resupply, repair, hire, commission, idle).

## Exploration (scope addition)

`Explorer.scan` lists salvageable entities (tag `salvageable`: derelicts, stations, caches,
cryosleepers), debris fields (tag `debris`), planets not fully surveyed, planets with ruins (logged
only). Defended from memory `$hasDefenders` / `$defenderFleet` (only known after first contact; themed
systems show "maybe"). `ExploreMath.pick`: salvage first, then surveys, within `explore_detour_su`,
undefended unless our DP covers defenders x `hunt_margin`. BountyRunner detours while travelling
in-system or resting, only above `explore_min_supply_days` and with no active governor alarm. Loot
screens: everything that fits, best base value per cargo unit first (`LootTracker.takeByValue`).

## Compatibility decisions

* All command names of both mods kept. Collisions: `run <n>` (trade trip) is now `trip <n>` (also
  `run trip <n>`, `runtrip <n>`) because `run <days>` is the governor; `repair` is the real dockyard
  goal (cheat: `repair instant`); `crew add <n>` is the cheat, `crew [fill]` the real hire; `resume`
  resumes a paused goal/trip/hunt (time still needs `run`); `unpause` = `run gov_unpause_days`.
* One rules file `captain_rules.txt` with every key of `advisor_rules.txt` and `trade_rules.txt`.
* The trade price book keeps its persistent-data key `claudebridge_prices`, so prices learned by the
  Bridge in a save are reused.
* No reflection (the script class loader forbids it), no java.io; files through
  `Global.getSettings().read/writeTextFileToCommon`. `tools/build.sh` fails if a class references
  `java/lang/reflect` or `java/io/File`. The obfuscated option panel is reached through `stubs/`
  (`DialogBridge`, `nwx` renamed to `new` in the class file).
