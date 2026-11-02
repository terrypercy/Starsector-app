# Claude Captain

**What:** Starsector 0.98a-RC8 mod that lets Claude play the single-player campaign. Claude writes command
batches to an inbox; the mod runs them as goals (travel, dock, resupply, repair, hire, commission, hunt,
trade, explore), answers routine dialogs, and reports to an event log and state file.
**Repo:** terrypercy/starsector-app. Docs: README.md, DESIGN.md, MIGRATION.md, SUMMARY.md.
**Version:** 1.0.9 on `main`. Live game runs build 441396e; c4fedc5 and 7395786 not yet installed.
**Build/test:** `C=<starsector-core> OUT=<out> bash tools/test.sh` (228 unit tests + planner, reader/trader, advisor, trade suites).

## History
- 1.0.8: keep list, fixed price ceilings, fuel gates on trips and hunts, `flagship`, bounty guard.
- 1.0.9: safety release after the agent broke the player's rules in a long session (sold kept items,
  left hunts short on fuel, overpaid for supplies, undid pauses, clicked through choices). Rules now
  enforced in code; in-game Yes popup for money/ship actions; cheats removed.

## Live test, Nov 1 (hunt for Francis Craft)
- Preflight GO; save before hunt; trip took 27 days vs ETA 10.
- Found/fixed: false pause hold on load; detours not salvaging.
- Game froze 65 s into the battle (main loop stalled, JVM alive). Likely memory: Xmx 2 GB, PC at 94-97% RAM.
  Needs a local restart. Last save May 20 (in-game), before the hunt.

## Open items
Tracked in TASKS.md.
