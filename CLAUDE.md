# Memory

## Me
Terry (terrypercy on GitHub). Plays a single-player Starsector campaign and builds **Claude Captain**, a mod that lets Claude captain the fleet.

## People
| Who | Role |
|-----|------|
| **Francis Craft** | In-game bounty target (Nov 1 live-test hunt), not a real person |

## Terms
| Term | Meaning |
|------|---------|
| Captain | Claude Captain, the mod (id `claude_captain`) |
| Bridge / TR | Claude Bridge / Claude Trade Runner, the two old mods Captain replaced |
| RC8 | Starsector 0.98a-RC8, the game version targeted |
| governor | Mod part that only lets game time run inside a granted run budget |
| CR | Combat readiness (panel shows the lowest) |
| ly | Light years (fuel range) |
| keep list | Never sell below 50 metals and 50 heavy machinery |
| preflight | Read-only GO/NO-GO check before a hunt or market run |
| `#since` | Event number every inbox batch must cite |
| hunt / trip | Bounty hunt goal / trade run goal |
| vmparams / Xmx | Starsector's JVM memory setting (currently 2 GB) |
| watchdog | `tools/watchdog` script that restarts a frozen game |

## Projects
| Name | What |
|------|------|
| **Claude Captain** | Starsector mod, v1.0.9; repo terrypercy/starsector-app. See memory/projects/claude-captain.md |

## Preferences
- Task list and memory cover Starsector only; don't add work or personal items
- Rules are enforced in the mod, not left to the agent's memory
- Supplies are never bought automatically; money/ship actions need a Yes on the in-game popup (chat "yes" doesn't count)
- Never undo the player's own pause; never take control away via the inbox
- No cheats (automatic bounty payout and cheat credits removed)
- Black-market trading with transponder on: off. Hunt margin 1.25
