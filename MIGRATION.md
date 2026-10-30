# Moving from Claude Bridge + Claude Trade Runner to Claude Captain

**Do all of this with the game closed.** Check first: `saves/common/claude_bridge/running.txt.data`
and `saves/common/claude_trade/running.txt.data` must have a `real_time_ms` older than 30 seconds.

## 1. Install

Copy the folder `Claude Captain` (from `captain_v1/mod/`) into `Starsector/mods/`, so that you have
`Starsector/mods/Claude Captain/mod_info.json` and `.../jars/claude_captain.jar`.

## 2. Disable the two old mods (they must not run together with Captain)

Both old mods poll their own inboxes, both answer dialogs, and Bridge's combat plugin would fight
Captain's in every battle. Disable them in either way:

* **Launcher**: start Starsector, click **Mods**, untick *Claude Bridge* and *Claude Trade Runner*,
  tick *Claude Captain*, then Play. The launcher saves the choice.
* **By file**: the launcher keeps the enabled list in `Starsector/mods/enabled_mods.json`, e.g.

  ```json
  {"enabledMods": ["claude_captain"]}
  ```

  Remove `claude_bridge` and `claude_trade_runner` from the list and add `claude_captain`
  (keep any other mod ids you use). Edit it only while the game and launcher are closed.

You can leave the old mod folders in place (disabled) as a fallback, or move them out of `mods/`.
Both old mods and Captain keep nothing in save files, so the switch is safe both ways for the save.
The trade price book (`claudebridge_prices` in the save's persistent data) is read by Captain as before.

## 3. Files

| old | new (`saves/common/claude_captain/`) |
|---|---|
| `claude_bridge/inbox.txt`, `claude_trade/inbox.txt` | `inbox.txt` (one inbox, `#id` lines as before) |
| `claude_bridge/outbox.txt`, `claude_trade/outbox.txt` | `events.txt` (append-only, numbered) + `recent.txt` (`events since <n>`) |
| `claude_bridge/status.txt`, `claude_trade/status.txt` | `state.txt` (JSON), `status.txt` (old text format, on `status`) |
| `advisor_rules.txt` + `trade_rules.txt` | `captain_rules.txt` (all old keys, same names, plus `gov_*`, `resupply_*`, `explore_*`, `hud_*`) |
| (code) | `dialog_rules.txt` (dialog answers, live-reloaded) |
| `running.txt` (both) | `running.txt` |
| `hud_pos.txt` (both) | `hud_pos.txt` (`left`, `top`, `open`, `folded`) |
| `plans.txt`, `runlog.txt`, `huntlog.txt`, `combat.txt`, `combat_cmd.txt`, `advice.txt`, `say.txt` | same names |

Your old rule values are not copied automatically: after the first start, open `captain_rules.txt`
and carry over anything you had changed (for example `allow_cheats`, `hunt_margin`, `budget_pct`).

## 4. Commands: what changed

Every old command name still works, except where the two mods collided:

| old | now | why |
|---|---|---|
| Trade Runner `run <n> [cap <cr>]` | `trip <n> [cap <cr>]` (also `run trip <n>`, `runtrip <n>`) | `run <days>` is the governor |
| Bridge `repair` (cheat: instant full repair) | `repair instant` (still needs `allow_cheats = true`) | `repair` = real dockyard repair goal |
| Bridge `crew add <n>` (cheat) | unchanged, cheat-gated | `crew` / `crew fill` = Trade Runner's real hire |
| Bridge `unpause` | grants `run gov_unpause_days` (3) | the governor decides when time runs |
| Bridge `resume` (= unpause) / TR `resume` | resumes a paused goal, trip or hunt; time still needs `run` | one meaning |
| Bridge `goto`/`travel` (stopped at every jump) | same names, now a goal that handles jumps and dialogs | problem 1 |
| Bridge `dock <far place>` ("too far, use goto") | flies there and docks | |
| both `selftest` / `status` | merged | |

New: `run <days>`, `run until arrived|done|event [max <d>]`, `pause`, `control claude|player`,
`governor`, `events since <n>`, `state`, `resupply [days]`, `hire [officer] [personality]`,
`talk <person>`, `commission <faction>` (and `commission`/`rep` = standings report), `explore
[here|list]`, `recover [list|<n>]`, `trade loop <n>`, `rules`, `dialog sig`, batch header
`#on_error continue`.

## 5. The controller's loop (Claude)

1. Write a batch with a new `#id` to `inbox.txt`. Include a `run ...` line (or rely on the implicit
   `run until done`, max `gov_implicit_max_days` = 15).
2. Wait, then fetch `events.txt` (or send `events since <last seen>` and fetch `recent.txt`).
3. On an ALERT (governor pause, unknown dialog, goal paused): read `state.txt`, decide, send the next
   batch (`choose <n>` / `resume` / `resupply` / `run 5` ...).
4. Before Claude stops watching: `pause` or let the budget run out - the game then stays paused.
5. To let the user play: `control player` (or the panel chip).

Auto-battle still resets on every game load: send `battle auto on`, or set `battle_auto_on_load = true`.
