<div align="center">

# Clasher Events `v2.1.0`

### Zero-Dependency Plug-and-Play PvP Tournament & Events Engine for Paper 26.2+

A modular, high-performance Minecraft PvP events plugin built for **Clasher Network** — featuring **8 architectural-grade PvP arenas**, a built-in **Floating Sky Citadel Spawn Lobby** with spiral sky-parkour, a dedicated **Champions Winner Podium & Crowd Stadium**, **one-command `/ce setup`** turnkey installation, **pure-Java Sponge `.schem` auto-pasting** (no WorldEdit required!), **automatic Void World generation**, **tick-batched zero-lag arena rollback**, **live BossBar/Scoreboard/Title HUD**, **Elo ratings & win streaks**, **First Blood & Kill Streaks**, **pre-event Mode Voting**, **auto-scheduler**, **Discord Webhooks**, and **optional PlaceholderAPI** support.

![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![Paper](https://img.shields.io/badge/Paper-26.2%2B-38bdf8)
![Dependencies](https://img.shields.io/badge/Required%20Deps-Zero-10b981)
![Tests](https://img.shields.io/badge/JUnit%205-12%20Passing-brightgreen)
![Release](https://img.shields.io/badge/Version-2.1.0-a855f7)

[Source](https://github.com/Pranjal3125/clasher-events) · [Releases](https://github.com/Pranjal3125/clasher-events/releases) · [Issues](https://github.com/Pranjal3125/clasher-events/issues)

</div>

---

## What's New in v2.1.0

### 🏰 Built-In Floating Sky Citadel Lobby & Champions Winner Podium
- **Grand Event Spawn Lobby (`lobby.schem`, 96×46×96)**: Hypixel-style floating marble & gold sky island with a 14-block sculpted jagged rock underside, glowing compass courtyard, 4 vaulted triumph arches, quartz colonnade dome, 4 floating satellite crystals, and a full **spiral sky-parkour course** around the island!
- **Champions Winner Podium & Crowd Stadium (`winner-podium.schem`, 76×36×76)**: Royal floating stadium with tiered red/gold bleachers, grand triumph arch, glowing 1st/2nd/3rd stepped gold/iron/copper podiums, and automatic **crowd teleportation** (`locations.winner-crowd`) so eliminated players & spectators watch the winner's 6-second firework celebration live!
- **One-Command Turnkey Setup (`/ce setup` & `/ce setup refresh`)**: Creates the void world, pastes the Sky Citadel Spawn Lobby, Champions Winner Podium, and all 8 PvP arenas tick-batched, configures all 4 locations automatically, and teleports you right into the lobby. Upgrading from `v2.0.x`? Run **`/ce setup refresh`** to overwrite legacy schematics with the new `v2.1.0` architectural builds automatically!
- **Dedicated Event Server UX**:
  - **Interactive Lobby Hotbar**: Players waiting in the lobby get right-click items for **Preview Event Kit**, **Vote Next Mode**, **Rules Book**, **Leaderboards**, and **Leave Event**.
  - **Lobby Void-Fall Catch**: Falling off the Sky Citadel or parkour smoothly teleports players right back to the Lobby spawn with zero damage.
  - **Auto-Lobby on Server Join**: In `dedicated-server-mode: true`, non-staff players joining the server automatically spawn in the Grand Event Spawn Lobby.

### 🏟 8 Rebuilt Architectural-Grade PvP Arenas (57k–95k Blocks Each!)
Every arena was completely redesigned from scratch with multi-block weathered stone/brick gradients, 3D curved rib arches, waterfalls/lavafalls, hanging iron-chain chandeliers, sculpted multi-branch organic trees, and elevated spectator pavilions (plus drop-in support for any custom `.schem` from PlanetMinecraft/BuiltByBit!):
- **`sword`** (*Grand Roman Colosseum*, 112×44×112 — 75k blocks) — 3-tier arched Roman amphitheater with stepped bleachers, 16 rib velarium canopy, sandstone mosaic combat floor, and elevated royal viewing box.
- **`nethpot`** (*Gothic Nether Citadel*, 128×44×128 — 81k blocks) — Huge weathered blackstone citadel with 8 cascading lavafalls, gothic flying buttresses, hanging soul-lantern chandeliers, lava moat, and 4 grand bridges.
- **`crystal`** (*End Rift Sanctuary*, 112×42×112 — 57k blocks) — Blast-proof **bedrock subfloor**, obsidian placement rings, colossal diagonal amethyst/obsidian crystal spikes, and 6 floating End satellite islands.
- **`build-uhc`** (*Verdant Mountain Valley*, 112×42×112 — 89k blocks) — Perlin-noise sculpted cliffs, twin cascading waterfalls into a recessed rocky stream with arched timber bridges, and multi-branch organic trees.
- **`axe-shield`** (*Gothic Ironkeep Castle*, 108×42×108 — 58k blocks) — Fortified medieval deepslate & stone-brick castle courtyard with pitched-roof turrets, battlement arches, braziers, and tactical cover pillars.
- **`uhc`** (*Alpine Frontier*, 112×44×112 — 95k blocks) — Snow-capped Perlin-noise mountain peaks, frozen waterfall, pine forest, and weathered granite combat valley.
- **`smp`** (*Overgrown Ruined Cathedral*, 112×42×112 — 66k blocks) — Mossy shattered gothic cathedral with a stained-glass rose window, broken rib arches, and Red vs Blue Team Wars flank lanes.
- **`diamond-pot`** (*Frostglass Ice Cathedral*, 108×42×108 — 67k blocks) — Towering stained-glass & packed-ice cathedral walls, hanging frost chandeliers, and a 100% **non-slippery** smooth quartz/diorite/prismarine combat floor.

### ⚡ PvP Hype Effects & Fast Admin Controls
- **First Blood, Kill Streaks & Visual Lightning**: Automatic `🩸 FIRST BLOOD!`, `🔥 RAMPAGE!` (3), `🔥 UNSTOPPABLE!` (5), and `⚡ GODLIKE!` (7+) broadcasts, visual lightning bolts on kills, and 2-tick **instant auto-respawn** straight into the arena's elevated spectator gallery.
- **Admin Time Skip & Instant Teleports**: `/ce skip [sec|now]` (or `/ce timeskip`) accelerates registration countdowns or finishes mode voting immediately; `/ce tp <lobby|winner|crowd|arena>` warps straight to any build.

---

## Core Engine Features

- **Zero-Dependency Pure-Java Sponge v2 `.schem` Paster (`SpongeSchematicPaster`)**: Turbo void-world air skipping pastes massive 95k-block builds cleanly at 20 TPS — **WorldEdit is optional!** Want custom maps? Drop any `.schem` into `plugins/ClasherEvents/schematics/`.
- **Automatic Post-Match Block Rollback (`ArenaBlockTracker`)**: Restores every placed/broken block, liquid flow, fire, and crystal/TNT blast smoothly at 20 TPS + wipes dropped items/crystals.
- **Live Adventure HUD**: Dynamic BossBar, Sidebar Scoreboard, `5..4..3..2..1.. FIGHT!` / `WAVE 2` / `FINAL DUEL` titles, and 6-second Winner Celebration fireworks.
- **Elo Ratings, Win Streaks & Multi-Tab Leaderboard GUI**: Standard K=32 Elo (`1000` base, `+20` win bonus) + interactive `/event top [elo|wins|kills|points]` GUI.
- **Async Atomic Persistence, Auto-Scheduler, Discord Webhooks & PlaceholderAPI**: Zero main-thread disk I/O, scheduled IST events, rich Discord embeds, and `%clasherevents_*%` placeholders.
- **Strict Security Shield**: Command whitelist & locked containers/enderchests during events, plus bounds-centered per-player WorldBorders.

---

## Quick Start

1. Download **`clasher-events-2.1.0.jar`** from [Releases](https://github.com/Pranjal3125/clasher-events/releases).
2. Drop it into your Paper `26.2+` server's `plugins/` folder *(WorldEdit, WorldGuard, and PlaceholderAPI are all optional!)*.
3. Start the server and run **`/ce setup`** (or **`/ce setup refresh`** if upgrading from `v2.0.x` so the new `v2.1.0` architectural builds overwrite the old schematics!).
4. Inspect your new server with **`/ce tp lobby`**, **`/ce tp winner`**, **`/ce tp sword`**, **`/ce tp nethpot`**, **`/ce tp crystal`**, etc.
5. Host an event anytime with **`/ce create nethpot`** or **`/ce voteopen`**, and speed up timers with **`/ce skip now`**!

## Commands

| Command | Description |
|---|---|
| `/ce setup [refresh]` | One-command setup: paste Sky Citadel Lobby, Winner Podium & all 8 arenas (`refresh` overwrites old v2.0 schematics) |
| `/ce tp <lobby\|winner\|crowd\|arena>` | Teleport directly to the Spawn Lobby, Winner Podium, Crowd Hall, or any Arena |
| `/ce skip [sec\|now]` / `/ce timeskip` | Skip registration countdown (to 5s, by X sec, or instant start) / finish mode vote |
| `/ce gui` / `/ce create <mode>` / `/ce voteopen [sec]` | Admin panel, instant event open, or player mode vote |
| `/ce setlobby\|setspectator\|setwinner\|setcrowd` | Customize lobby, spectator, champion podium, or crowd spawn positions |
| `/ce pause\|resume\|start\|stop\|reload` | Event lifecycle control |
| `/ce arena list\|tp\|paste\|pasteall\|reset\|validate` | Arena management & zero-lag batched pasting |
| `/event join` / `/event leave` | Join registration or leave & restore inventory |
| `/event modevote [mode]` | Open Mode Voting GUI when voting is active |
| `/event vote [player]` / `/event cheer <player>` | Spectator winner prediction & cheer |
| `/event stats` / `/event top [elo\|wins\|kills\|points]` | View Elo/K/D/Streak stats & interactive Leaderboard GUI |
| `/event history` / `/event spectate` / `/event team` | Match history, spectator selector, Team Wars squad |

## Build & Test

```bash
mvn clean package
```

Runs all 12 JUnit 5 tests (parsing all 10 bundled `.schem` binaries, validating all 128 Paper 26.2 block materials, and mathematically verifying every single spawn point in all 8 arenas, Sky Citadel Lobby, and Winner Podium) and produces `target/clasher-events-2.1.0.jar`.
