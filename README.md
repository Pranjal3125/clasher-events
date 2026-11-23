<div align="center">

# Clasher Events `v2.0.0`

### Zero-Dependency Plug-and-Play PvP Tournament & Events Engine for Paper 26.2+

A modular, high-performance Minecraft PvP events plugin built for **Clasher Network** — featuring **8 unique bundled PvP arenas**, **built-in pure-Java Sponge `.schem` auto-pasting** (no WorldEdit required!), **automatic Void World generation**, **tick-batched zero-lag arena rollback**, **live BossBar/Scoreboard/Title HUD**, **Elo ratings & win streaks**, **pre-event Mode Voting**, **auto-scheduler**, **Discord Webhooks**, and **optional PlaceholderAPI** support.

![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![Paper](https://img.shields.io/badge/Paper-26.2%2B-38bdf8)
![Dependencies](https://img.shields.io/badge/Required%20Deps-Zero-10b981)
![Tests](https://img.shields.io/badge/JUnit%205-11%20Passing-brightgreen)
![Release](https://img.shields.io/badge/Version-2.0.0-a855f7)

[Source](https://github.com/Pranjal3125/clasher-events) · [Releases](https://github.com/Pranjal3125/clasher-events/releases) · [Issues](https://github.com/Pranjal3125/clasher-events/issues)

</div>

---

## What's New in v2.0.0

### 🏟 8 Unique, PvP-Balanced Bundled Arenas (Plug & Play!)
Every arena was rebuilt with distinct architecture, elevated spectator boxes, and mathematically verified spawns (zero suffocation, zero water/lava hazards):
- **`nethpot`** (*Nether Citadel*, 128×32×128) — Open flush blackstone combat plaza, lava moat, 4 bridges, 8 bastion towers, fixed spectator gallery.
- **`sword`** (*Grand Colosseum*, 96×28×96) — Multi-tiered Roman amphitheater with smooth sandstone floor, vaulted gates, and quartz colonnade.
- **`crystal`** (*End Rift*, 104×26×104) — Blast-proof **bedrock subfloor**, obsidian placement grid, and 8 End monolith towers.
- **`build-uhc`** (*Verdant Basin*, 96×30×96) — Rolling natural basin, oak trees, footbridge stream, steep cliff perimeter, and generous sky clearance.
- **`axe-shield`** (*Ironkeep*, 96×28×96) — Medieval deepslate & stone-brick fortress courtyard with corner watchtowers and tactical cover bastions.
- **`uhc`** (*Wild Frontier*, 104×30×104) — Alpine taiga arena with tall spruce pines and steep cliff perimeter.
- **`smp`** (*Fallen Kingdom*, 104×28×104) — Ruined castle citadel with subtle Red vs Blue Team Wars flank lanes.
- **`diamond-pot`** (*Frostglass Temple*, 96×28×96) — 100% **non-slippery** quartz/diorite/prismarine combat floor (ice kept strictly on decorative walls & crystal spires).

### ⚡ Zero-Dependency Auto-Paste, Void World & Tick-Batched Rollback
- **Built-in Void World Generator**: `clasher_events` automatically generates as a clean, mob-free void world (`use-void-world: true`).
- **Pure-Java Sponge v2 `.schem` Paster (`SpongeSchematicPaster`)**: Auto-pastes missing arenas asynchronously in tick-batched slices (`2500 blocks/tick`) when `/ce create <mode>` runs — **WorldEdit is no longer required!** Also includes `/ce arena pasteall`.
- **Automatic Post-Match Block Rollback (`ArenaBlockTracker`)**: Tracks all placed/broken blocks, liquids, fires, and crystal/TNT explosions during Build UHC / Crystal / UHC / SMP and smoothly restores them at 20 TPS + clears all dropped items, arrows, and crystals.

### 🎮 Pro Gameplay, HUD, Elo & Automation
- **Live Adventure BossBar, Sidebar Scoreboard & Titles**: `5..4..3..2..1.. FIGHT!`, `WAVE 2`, `FINAL DUEL`, and **6-second Winner Celebration Podium** with staggered fireworks!
- **Pre-Event Player Mode Voting**: `/ce voteopen [sec]` & `/event modevote` GUI lets players vote on the next mode.
- **Elo Ratings, Win Streaks & Multi-Tab Leaderboard GUI**: Standard K=32 Elo (`1000` base, `+20` win bonus) + interactive `/event top [elo|wins|kills|points]` chest GUI.
- **Async Disk Persistence**: Background single-thread atomic YAML writer removes 100% of disk I/O from the main tick thread (flushes safely on shutdown).
- **Auto-Scheduler**: Optional interval or fixed daily `HH:mm` IST scheduling with min-player checks and mode vote or rotation launch.
- **Discord Webhook Embeds**: Async rich embeds for Mode Vote Open, Event Open, and Match Finished (Winner, MVP, kills, fighters, duration).
- **Optional PlaceholderAPI (`%clasherevents_*%`)**: `wins`, `kills`, `deaths`, `kd`, `elo`, `streak`, `best_streak`, `points`, `active_mode`, `active_arena`, `phase`, `alive`, `top_wins_1..10`, `top_elo_1..10`, `top_kills_1..10`.

### 🛡 Security & Bug Fixes
- **Strict Command & Container Shield**: Whitelist-only commands during events (blocks `/ec`, `/ah`, `/pv`, `/home`, `/spawn`, `/warp`, `/trade`, and namespaced bypasses like `/essentials:enderchest`), plus locked containers/enderchests.
- **Dedicated Server vs SMP Toggle**: `dedicated-server-mode: true` returns players to the event lobby; `false` returns them to their exact saved pre-event survival location.
- **Bounds-Centered WorldBorders**: Always shrinks toward true arena bounds center and preserves remaining shrink duration for late-joiners/reconnectors.
- **Strict Event Scoping**: Paused damage, UHC no-regen, friendly fire, and movement tracking affect ONLY active event participants (zero leakage to non-event players, zero memory leaks).

---

## Quick Start

1. Download **`clasher-events-2.0.0.jar`** from [Releases](https://github.com/Pranjal3125/clasher-events/releases).
2. Drop it into your Paper `26.2+` server's `plugins/` folder *(WorldEdit, WorldGuard, and PlaceholderAPI are all optional!)*.
3. Start the server — all 8 schematics, kits, and configs extract automatically.
4. Run `/ce setlobby`, `/ce setspectator`, `/ce setwinner` once, then run **`/ce create nethpot`** (or `/ce voteopen` or `/ce arena pasteall`)!

## Commands

| Command | Description |
|---|---|
| `/event join` / `/event leave` | Join registration or leave & restore inventory |
| `/event modevote [mode]` | Open Mode Voting GUI when voting is active |
| `/event vote [player]` / `/event cheer <player>` | Spectator winner prediction & cheer |
| `/event stats` / `/event top [elo\|wins\|kills\|points]` | View Elo/K/D/Streak stats & interactive Leaderboard GUI |
| `/event history` / `/event spectate` / `/event team` | Match history, spectator selector, Team Wars squad |
| `/ce gui` / `/ce create <mode>` / `/ce voteopen [sec]` | Admin panel, instant event open, or player mode vote |
| `/ce arena tp\|paste\|pasteall\|reset\|validate <arena>` | Teleport to arena & zero-lag batched arena management |
| `/ce pause\|resume\|start\|stop\|reload` | Event lifecycle control |

## Build & Test

```bash
mvn clean package
```

Runs all 11 JUnit 5 tests (including parsing all 8 `.schem` binaries and mathematically verifying all 120 spawn clearances) and produces `target/clasher-events-2.0.0.jar`.
