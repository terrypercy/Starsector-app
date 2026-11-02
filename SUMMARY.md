# Starsector app: project summary

**Claude Captain** is a Starsector 0.98a-RC8 mod that lets Claude play a single-player campaign on the
player's behalf. Claude writes command batches to an inbox file; the mod runs them as goals (travel,
dock, resupply, hunt bounties, trade, explore), answers routine dialogs by editable rules, and reports
everything to an event log and a state file. A governor only lets game time pass inside a granted run
budget. See README.md (commands, panel), DESIGN.md and MIGRATION.md.

Current version: **1.0.9** (latest commit on `main`). Installed in the live game: build 441396e.

## What 1.0.9 changed and why

Version 1.0.9 is a safety release. It came out of a long play session in which the agent repeatedly
broke the player's rules: it sold items he wanted kept, left on hunts without enough fuel, overpaid for
supplies, undid his pauses and clicked through choice screens. The fix was to enforce the rules in the
mod itself rather than rely on the agent remembering them. Two independent reviews audited the plan and
the code before install.

**The player's rules, enforced by the mod**
- Keep list: never sell below 50 metals and 50 heavy machinery, on every sale path.
- Supply and fuel price ceilings follow the market: the median price at friendly markets x 1.35, tariff
  included on both sides. Emergency buys are capped at 2x the median and only cover a safe amount.
- Supplies are never bought automatically. Automatic restocks only produce a quote.
- These run only after the player clicks **Yes** on an in-game popup (a "yes" in chat doesn't count):
  resupply, buying supplies, buying or selling ships, restore, buyspec, hire, and `hunt resume anyway`.
- Black-market trading with the transponder on is off, and the hunt margin is 1.25.
- All safety settings are locked in code. Values in `captain_rules.txt` are ignored and logged, and
  every rules-file change is logged.
- The game saves before hunts and before ship purchases and sales.

**Fixes for the agent's repeated failures**
- The inbox refuses `control player` in every form; only the in-game panel chip hands control to the player.
- The player's own pause is detected and never undone. Mod pauses all go through `Governor.modPause()`,
  and `keep_moving` is off.
- Batches must cite `#since <event>` and are refused while a warning or alert is unread or the number is
  in the future. Stop-only batches (pause/halt/cancel/hunt stop) always get through.
- `halt` stops everything: goals, trips, hunts, queued lines, asks and dialog auto-answers.
- Hunts won't depart unless fuel covers the round trip and supplies cover 2x the game's ETA there and
  back plus 20 battle days. The hunt restocks fuel to the round trip.
- Trips won't set off without fuel to arrive. They are deadlock-free: going to a fuel market or
  resuming mid-flight only warns.
- Battles are closed the game's way. `dialog close` only acts on stuck dialogs and finished battles,
  and there's no unpause while a battle dialog is open.
- Story-point detection fails safe, and supplies are never trade cargo.

**Cheats removed:** the automatic bounty payout and the `cheat credits` / `bounty pay` commands are gone.
BountyGuard now only raises an alert when a bounty the fleet killed ends unpaid.

**New commands**
- `preflight <bounty|market>`: read-only GO/NO-GO.
- `progress`: story missions, skill points, colonies, automated ships without AI cores.
- `colony sites`: ranks surveyed uninhabited planets.
- `flagship <ship>`.
- `dialog close`.

**Later fixes (in `main`, not yet installed)**
- c4fedc5: exploration detours now salvage. The game's buttons are "Explore" and "Assess"; this rule
  is already live through `dialog_rules.txt`.
- 7395786: no false "player paused" hold when a save loads paused.

Tests: 228 unit tests plus the planner, reader/trader, advisor and trade suites, all passing.
Build: `C=<starsector-core> OUT=<out> bash tools/test.sh`.

## Live test, Nov 1 (hunt for Francis Craft)
- Preflight gave GO on both targets. The mod saved before the hunt. The trip took 27 days against the
  game's ETA of 10, which confirms the x2 planning factor.
- Found and fixed: the false pause hold on load, and detours that left derelicts without salvaging.
- The game froze 65 seconds into the battle. The JVM stayed alive (the heartbeat thread kept writing)
  but the main loop stopped. A memory limit is the likely cause: Xmx is 2 GB and the PC was at 94-97%
  RAM. Windows blocks remote input to the frozen window and to Task Manager, so it needs a local restart.
  The last save is May 20, right before the hunt.

## Recovering from freezes: `tools/watchdog`
`starsector_watchdog.ps1` runs every 2 minutes and ends Starsector only if its window stays "Not
responding" for 3 checks, then reopens the launcher. Switch it on once by double-clicking
`install_watchdog.bat` (keep both files in one folder). Switch it off with
`schtasks /delete /tn "Starsector Watchdog" /f`.

## Open items
- Install the latest build: c4fedc5 or newer.
- Raise Starsector's memory limit (vmparams), with the player's approval.
- An optional in-game self-shutdown when the main loop stalls.
- The Onslaught Mk.I has no AI core. No colony yet (the guide says to found one at 500k-1M credits).
- Plain `travel`/`dock` goals still have no fuel gate.
- The agent could still click the approval popup through screen control; it must not.
