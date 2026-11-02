# Claude Captain process (Java track)

Read with `CLAUDE.md` § Mod Rules. Where this file and a studio skill disagree for this mod,
this file wins; where a studio rule says "never", it still holds.

## 1. Who can do what

| Action | Agent | Player only |
|---|---|---|
| Edit `src/`, `tests/`, `tools/`, `data/` (with approval) | yes | |
| Build and run tests (`tools/test.sh`) where `starsector-core` exists | yes | |
| Copy a jar into the game's `mods/` folder, edit `vmparams`, touch the game install | | **yes** (or the agent, only after the player's explicit OK for that install) |
| Click the in-game approval popup | **never** | yes |
| Loosen a locked guard rail (§4) | | **yes**, recorded as an ADR |
| Play the game and judge the result | | yes |

## 2. Test gate

- Command: `C=<starsector-core> OUT=out bash tools/test.sh` (also `commands.test` in `project.yaml`).
- Suites: `CaptainTest`, `PlannerTest`, `ReaderTest`, `AdvisorTest`, `TradeTest`, then `LoadAll`
  (every class in the built jar loads).
- **PASS** only when the script exits 0, prints `ALL TESTS PASSED`, and every suite's summary
  reads `0 failed`.
- **FAIL** on any non-zero exit, a `FAIL:` line, a `cannot load` line, or a `FORBIDDEN` build line.
- **NOT ASSESSED** when it could not run: no `starsector-core`, no JDK, or the old `tools/test.sh`
  that pipes into `tail` (it exits 0 whatever the tests do, so its result proves nothing).
  NOT ASSESSED outranks PASS and never counts as one.
- Run it with `/java-test`. Report saved to `production/qa/java-test-[date].md`.
- New rules and formulas get a test in the matching suite. A bug from live play gets a test that
  reproduces the live case, named after it in the check message.

## 3. Live check (replaces the studio's run-and-observe)

Only the player runs Starsector. A story that changes anything player-observable (panel, dialogs,
goals, governor, travel, trading, hunting) is not Done until a live check records it.

1. The agent writes what to try and what to look for (from the story's acceptance criteria).
2. The player plays and sends evidence: a screenshot, or excerpts of
   `saves/common/claude_captain/events.txt` / `state.txt` (the event numbers).
3. `/java-live-check` files it as `production/qa/evidence/live-[date]-[story].md` (template:
   `.claude/java/templates/live-check.md`), screenshots beside it, and marks each criterion
   **SEEN / NOT SEEN / NOT ASSESSED**.
4. Anything NOT SEEN reopens the story; a new bug becomes a story with a reproducing test.

Until then the story is **Awaiting live check**, not Done. Visual and UI evidence gates are
advisory in `project.yaml` because they wait on the player; that does not make them optional.

## 4. Locked guard rails

These are enforced in code because the agent broke them in live play (see `SUMMARY.md`).

- `Rules.LOCKED` keys (`src/Rules.java`): `allow_cheats`, `recover_api`, `require_since`,
  `ask_before_supplies`, `keep`, `max_supply_price`, `max_fuel_price`, `supply_price_factor`,
  `fuel_price_factor`, `emergency_fuel_ly`, `keep_moving`, `hunt_eta_factor`,
  `hunt_battle_supply_days`, `emergency_price_factor`, `hunt_margin`, `black_market_with_transponder`.
  Their values in `captain_rules.txt` are ignored and logged.
- In-game approval (`AskPopup`) for: resupply, buying supplies, buying or selling ships, restore,
  buyspec, hire, `hunt resume anyway`. A chat "yes" is not approval.
- `control player` is refused from the inbox in every form; only the panel chip hands control over.
- Every mod pause goes through `Governor.modPause()`; the player's own pause is never undone.
- `#since <event>` gate on batches; stop-only batches always pass.
- Hunt and trip fuel/supply gates; save before hunts and ship purchases/sales.
- No cheats: no credit grants, no bounty payouts.

**Any change that removes, weakens or bypasses one of these** (a key leaving `LOCKED`, a changed
default, a new path to buy/sell/pay that skips the popup, a pause outside `modPause`) needs:
an ADR in `docs/architecture/` with Status `Proposed`, the player's explicit decision, then
`Accepted`. `/java-guardrail-review` checks every change for this. Tightening a rail needs no ADR,
but is still reported.

## 5. Release

`/java-release`:
1. `/java-test` PASS on the commit being released (NOT ASSESSED blocks).
2. `/java-guardrail-review` CLEAR or every finding carried by an Accepted ADR.
3. Bump `version` in `mod_info.json` and `project.version` in `project.yaml` together.
4. Build (`tools/build.sh`); the load line in the game names the commit (`BuildInfo.BUILD`).
5. Show the player the changes since the installed build and the install steps.
6. Install only on the player's explicit OK, with the game closed.
7. Live check (§3) on the first session with the new build; record the installed build in
   `SUMMARY.md`.

## 6. Commits

Conventional Commits, story or doc reference in the body, `mod_info.json` version bumped when
the shipped jar changes. No commits without the player's instruction.
