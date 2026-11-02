# Adoption Plan

> **Generated**: 2026-11-02 (re-run)
> **Project phase**: Production (from `project.yaml`)
> **Engine**: Starsector 0.98a-RC8 (Java)
> **Template version**: v1.0+
> **Workflow tier**: minimal (audit scoped to `design/game-brief.md`, engine reference, project config)

Work through these steps in order. Check off each item as you complete it.
Re-run `/adopt` anytime to check remaining gaps.

**Not audited at `minimal`** (not on this path, so their absence is not a gap): GDDs,
ADRs, stories, systems index, TR registry, control manifest, sprint status, stage file,
traceability matrix. 0 GDDs, 0 ADRs and 0 stories exist on disk.

---

## Step 1: Fix Blocking Gaps

None found.

---

## Step 2: Fix High-Priority Gaps

None found. `design/game-brief.md` exists (reverse-documented 2026-11-02) and all six
required fields are present and not placeholders: pitch, core loop, player goal & fail
state, MVP, out of scope, build order.

---

## Step 3: Bootstrap Infrastructure

Not on the minimal path — the brief's build order is the plan. Next: `/create-stories`
(or `/dev-story` once stories exist).

---

## Step 4: Medium-Priority Gaps

### 4a. `naming.*` is not set in `project.yaml`
Naming checks have nothing to compare against. Set it with `/settings`.
**Time**: 5 min
- [x] `naming.*` configured (classes PascalCase, variables camelCase, constants SCREAMING_SNAKE, files PascalCase — taken from `src/`; signals/scenes left unset, no Java counterpart)

### 4b. `performance.*` is not set in `project.yaml`
Performance-budget checks have nothing to compare against. Set it with `/settings`.
**Time**: 5 min
- [x] `performance.*` configured (memory_ceiling_mb 2048 from the game's Xmx in `SUMMARY.md`, enforce warn; framerate and frame budget left unset — not sourced)

---

## Step 5: Optional Improvements

### 5a. Confirm the brief's `INTENT UNKNOWN` items
Six places in `design/game-brief.md` are inferred, not confirmed. The one that matters
most is the fail state and the missing "progress" threshold, because stories'
acceptance criteria trace back to it. Confirm it before running `/create-stories`.
Edit the brief directly; remove the reverse-documented banner only when you adopt it as authored design.
**Time**: 15 min
- [ ] Fail state and progress threshold confirmed
- [ ] Build order confirmed
- [ ] Optional one-liners (audience, reference mod/tool) filled in or left blank on purpose

### 5b. Forbidden Patterns and Allowed Libraries are empty
They live only in `.claude/docs/technical-preferences.md`, which still carries
`[TO BE CONFIGURED]` placeholders. They start empty by design.
Not assessed further: the Java track's rules (`CLAUDE.md` Mod Rules) are not mirrored there.
**Time**: 5 min
- [ ] Forbidden Patterns / Allowed Libraries filled in, if wanted

---

## Observations (not gaps)

- `project.yaml` records stage **Production**. The brief now exists, but no stories do
  yet; nothing in this plan changes the recorded stage.
- `modes.rigor` and `modes.automation` are not saved in `project.yaml`. Review mode resolves to
  `solo` from the default rigor (`minimal`); this plan does not write a review mode.
- `.claude/scripts/project-state.sh` reports `source_files 0` for `src/` even though 56 `.java`
  files exist; treat its count as unreliable for this repo.
- `design/game-brief.md` and this plan are untracked (not committed). The checkout is on a
  detached HEAD.

---

## What to Expect from Existing Stories

There are no stories yet. Once they exist, they continue to work with all template skills.
New format checks (TR-ID validation, manifest version staleness) auto-pass when the fields
are absent. Do not regenerate stories that are in progress or done.

---

## Re-run

Run `/adopt` again after completing Step 4 to confirm only optional items remain.
