# Starsector app: project summary

**Claude Captain** is a Starsector 0.98a-RC8 mod that lets Claude play a single-player campaign on the
player's behalf. Claude writes command batches to an inbox file; the mod runs them as goals (travel,
dock, resupply, hunt bounties, trade, explore), answers routine dialogs by editable rules, and reports
everything to an event log and a state file. A governor only lets game time pass inside a granted run
budget. See README.md (commands, panel), DESIGN.md and MIGRATION.md.

Current version: **1.0.9** (latest commit on `main`). Installed in the live game: build a4228f2; the threat-acknowledgement fix
(newer) goes in at the next game restart.

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
- `survey targets [max ly] [n]`: systems with planets still to survey, nearest first, live Remnant systems last.
- `ruins [max ly] [n]`: known unexplored ruins sector-wide; `explore_only_ruins = true` makes explore visit ruins and survey worlds only.
- `flagship <ship>`.
- `dialog close`.

**Later fixes**
- c4fedc5: exploration detours now salvage. The game's buttons are "Explore" and "Assess".
- 7395786: no false "player paused" hold when a save loads paused.
- a30c21c: salvage loot screens (no readable options) are taken by the loot rule, and an empty dialog gets
  5 s to settle before it counts as stuck.
- f379773: derelict ships found on detours are passed on and salvaged, not recovered (the player's call);
  the player's own disabled ships after a battle still stop and ask.
- a4228f2: terrain guard. Explore detours skip sites inside CR-draining terrain (event horizon, star corona,
  pulsar beam); the governor stops the fleet after a day inside it; resting no longer detours; a new supply
  alarm counts the repair and CR-recovery burn, not only maintenance.
- latest: an acknowledged threat no longer re-fires after every `run` (its "closing in" flag flickered off
  while paused and wiped the acknowledgement).

Tests: 243 unit tests plus the planner, reader/trader, advisor and trade suites, all passing.
Build: `C=<starsector-core> OUT=<out> bash tools/test.sh`.

## Live test, Nov 1 (hunt for Francis Craft)
- Preflight gave GO on both targets. The mod saved before the hunt. The trip took 27 days against the
  game's ETA of 10, which confirms the x2 planning factor.
- Found and fixed: the false pause hold on load, and detours that left derelicts without salvaging.
- The game froze 65 seconds into the battle. The JVM stayed alive (the heartbeat thread kept writing)
  but the main loop stopped. A memory limit is the likely cause: Xmx is 2 GB and the PC was at 94-97%
  RAM. Windows blocks remote input to the frozen window and to Task Manager, so it needs a local restart.
  The last save is May 20, right before the hunt.

## Live play, Nov 1 evening
- **Freeze cause found, and it wasn't memory.** The crash report (hs_err) shows an access violation in the
  Intel Arc graphics driver while drawing a ship in combat, with only 357 MB of the 2 GB heap in use. The
  `-XX:+ShowMessageBoxOnError` flag in vmparams made Java wait on a hidden error box for 11 hours. The driver
  was updating while the game ran; the player has since updated it. The memory limit was left as is.
- **Bounties now pay the normal way.** Francis Craft (193,500 cr) and Temitope Alcor (180,000 cr) were both
  paid by the game itself (`END_PLAYER_BOUNTY`) after the battles closed properly. This confirms the
  battle-closing fix.
- **Event horizon incident.** After the Alcor fight the hunt "rested" while detouring to wrecks inside a
  black hole's event horizon. Every ship fell to 0% CR, and CR recovery burned 113 supplies to 0 in two days.
  The player chose to reload the Jun 21 save (after Francis was paid). The terrain guard (a4228f2) was built
  and installed first.
- **Fleet check against the mid-game guide.** The shape fits: 1 capital (Onslaught Mk.I), 4 cruisers, 5
  destroyers, 3 Brawler screens and a Salvage Rig. Gaps: d-mods (Onslaught 4, Sunder Danu 4, Tempest 3), no AI
  core for the automated Onslaught, and the spare officer (now on a Brawler). Approved plan: redo the Temitope
  hunt, then a Mazalot refit stop (supplies, repairs and restore quotes, all on the player's Yes) and an AI
  core search.
- Still open: the Hegemony turned hostile around Jul 22 in the first run, because of the Hegemony-Luddic
  Church war (the player holds a Church commission). `gov_threat_ratio` was raised to 1.2 in the rules file to
  get past one Luddic Path Holy Armada (238 DP vs our 209); set it back to 1.0 afterwards.
- **Holy Armada intercept.** The Armada still caught the fleet; at 18 ships it was too big to disengage
  normally. With the player's explicit OK, Claude clicked the 1-story-point "special maneuvers" escape (+250,000
  bonus XP), and the Temitope hunt resumed.

## The player's standing rules (enforced by the mod where possible)
No cheats. Never spend story points (the player clicks them). Ask before big purchases, progression steps and
every ship sale. Supplies are bought only on the player's in-game Yes. Keep 50 metals and 50 heavy machinery.
Price limits follow the market. Never leave without fuel and supplies for the round trip. Save before big
actions. Stop when told. Anything worked out by hand gets built into the mod.

## Recovering from freezes: `tools/watchdog`
`starsector_watchdog.ps1` runs every 2 minutes and ends Starsector only if its window stays "Not
responding" for 3 checks, then reopens the launcher. It matches the game by folder and window rather than by
title, so it also catches a crash that leaves Java's error box up. Switch it on once by double-clicking
`install_watchdog.bat` (keep both files in one folder). Switch it off with
`schtasks /delete /tn "Starsector Watchdog" /f`.

## Open items
- Install the newest build at the next restart (the threat-acknowledgement fix).
- The watchdog files are in `mods\Claude Captain\watchdog\`; double-click `install_watchdog.bat` once.
- Consider removing `-XX:+ShowMessageBoxOnError` from vmparams so a crash exits instead of hanging.
- Preflight's supply estimate doesn't include post-battle repair and CR-recovery burn yet.
- The Onslaught Mk.I has no AI core. No colony yet (the guide says to found one at 500k-1M credits).
- Plain `travel`/`dock` goals still have no fuel gate.
- The agent could still click the approval popup through screen control; it must not.
