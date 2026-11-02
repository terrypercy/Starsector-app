# Glossary

## Mod and tools
| Term | Meaning |
|------|---------|
| Claude Captain / Captain | The mod. Id `claude_captain`, jar `claude_captain.jar`, plugin `claudecaptain.CaptainModPlugin` |
| Claude Bridge / Bridge | Old mod, replaced by Captain (must be disabled) |
| Claude Trade Runner / TR | Old trade mod, replaced by Captain (must be disabled) |
| inbox | `saves/common/claude_captain/inbox.txt`, where Claude writes command batches |
| events | `events.txt`, append-only numbered event log (`recent.txt` = events since n) |
| state | `state.txt`, JSON snapshot of the game |
| captain_rules.txt | Rules file; safety values are locked in code and file values are ignored/logged |
| dialog_rules.txt | Editable, live-reloaded dialog answers |
| governor | Lets game time pass only inside a granted run budget (`run <days>`, `run until ...`) |
| run budget | Days of game time Claude is allowed to let pass |
| modPause | `Governor.modPause()`, the only path for mod pauses |
| halt | Stops goals, trips, hunts, queued lines, asks and auto-answers |
| `#since <event>` | Required batch header; refused while a warning/alert is unread |
| preflight | `preflight <bounty|market>`: read-only GO/NO-GO |
| progress | Story missions, skill points, colonies, ships without AI cores |
| colony sites | Ranks fully surveyed uninhabited planets |
| keep list | Floors on sales: 50 metals, 50 heavy machinery |
| price ceiling | Median price at friendly markets x 1.35, tariff included; emergency cap 2x median |
| hunt margin | 1.25 (locked) |
| BountyGuard | Alerts when a bounty the fleet killed ends unpaid (no payout) |
| watchdog | `starsector_watchdog.ps1`, every 2 min; kills the game after 3 "Not responding" checks |
| heartbeat | Thread that keeps writing even when the main loop has stalled |

## Game terms
| Term | Meaning |
|------|---------|
| RC8 | Starsector 0.98a-RC8 |
| CR | Combat readiness |
| ly | Light years |
| ETA x2 | Hunts plan for twice the game's ETA (confirmed: 27 days vs 10) |
| AI core | Needed to crew automated ships (Onslaught Mk.I has none) |
| Explore / Assess | The game's salvage buttons (wrecks, derelicts, stations / debris) |
| vmparams / Xmx | JVM memory config; Xmx = max heap (2 GB now) |
