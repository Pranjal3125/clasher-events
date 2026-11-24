<div align="center">

# Clasher Events `v2.2.0`

### Zero-Dependency Plug-and-Play PvP Tournament & Events Engine for Paper 26.2+

A modular, high-performance Minecraft PvP events plugin built for **Clasher Network** — featuring **8 architectural-grade PvP arenas**, **per-arena 100%-sealed themed Waiting Skyboxes** (+ `/ce setwaiting <arena>` custom override), **multi-layer Anti-Escape Containment**, a built-in **Floating Sky Citadel Spawn Lobby** with spiral sky-parkour, a dedicated **Champions Winner Podium & Crowd Stadium**, **one-command `/ce setup`** turnkey installation, **pure-Java Sponge `.schem` auto-pasting** (no WorldEdit required!), **automatic Void World generation**, **tick-batched zero-lag arena rollback**, **live BossBar/Scoreboard/Title HUD**, **Elo ratings & win streaks**, **First Blood & Kill Streaks**, **pre-event Mode Voting**, **auto-scheduler**, **Discord Webhooks**, and **optional PlaceholderAPI** support.

![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![Paper](https://img.shields.io/badge/Paper-26.2%2B-38bdf8)
![Dependencies](https://img.shields.io/badge/Required%20Deps-Zero-10b981)
![Tests](https://img.shields.io/badge/JUnit%205-12%20Passing-brightgreen)
![Release](https://img.shields.io/badge/Version-2.2.0-a855f7)

[Source](https://github.com/Pranjal3125/clasher-events) · [Releases](https://github.com/Pranjal3125/clasher-events/releases) · [Issues](https://github.com/Pranjal3125/clasher-events/issues)

</div>

---

## What's New in v2.2.0

### 🪟 Per-Arena Themed Waiting Skyboxes + Custom `/ce setwaiting <arena>`
- **Built-In Enclosed Skybox Above Every Arena**: All 8 arenas now feature a custom-themed, **100%-sealed 19×9×19 panoramic glass Waiting Skybox** floating directly above the arena center (`dy = 42..50`), so players waiting during registration look straight down at the battlefield below while enjoying the interactive Lobby Hotbar & Rules Book!
- **Custom Per-Arena Waiting Lobbies**: Stand anywhere you like (even in another world!) and run **`/ce setwaiting <arena>`** (or `/ce setspawn <arena> waiting`) to override that arena's waiting lobby, **`/ce clearwaiting <arena>`** to fall back to the global Sky Citadel Lobby, or **`/ce tp <arena> waiting`** to teleport straight into any arena's waiting box.
- **Global Toggle**: Set `lobby-protection.per-arena-waiting: true` (default) for per-arena waiting lobbies or `false` to gather everyone in the global Sky Citadel Spawn Lobby.

### 🛡️ Airtight Multi-Layer Anti-Escape Containment (`anti-escape.enabled: true`)
Players **cannot escape** the waiting box, the combat arena, or the spectator viewing area:
- **Ender Pearl, Chorus & Portal Blocker**: Cancels any Ender Pearl, Chorus Fruit, Portal, or Spectator teleport aimed outside the arena's combat radius, above the combat ceiling (`combat-max-y`), or out of a waiting skybox.
- **Hard Combat Perimeter & Ceiling Enforcement**: Active fighters are smoothly clamped inside each arena's exact `playable-radius` and `combat-max-y` (strictly 6 blocks below the waiting skybox!), with instant void-fall rescue back onto the arena floor.
- **Build & Liquid Boundary Shield**: Even in destructible modes (`Build UHC`, `Crystal`, `UHC`, `SMP`), fighters can only break/place blocks and pour water/lava strictly inside `isInsideBuildBounds` — preventing subfloor digging, punching through perimeter walls, or towering into the skybox.
- **Per-Player WorldBorder Matched to Arena Walls**: Starts tightly matched to `playable-radius * 2` from second 0 before shrinking across `wave1 → wave2 → final`.
- **Waiting & Spectator Leashes**: Waiting players stay locked in their waiting skybox/lobby, and eliminated spectators stay leashed near the active arena so they cannot fly to neighboring arenas or into the void.

---

## Included Builds & Core Engine Features

### 🏰 Floating Sky Citadel Spawn Lobby & Champions Winner Podium
- **Grand Event Spawn Lobby (`lobby.schem`, 96×46×96)**: Hypixel-style floating marble & gold sky island with a 14-block sculpted jagged rock underside, 4 vaulted triumph arches, quartz colonnade dome, floating crystals, and a **spiral sky-parkour course** with automatic void-fall catch and auto-lobby on dedicated server join.
- **Champions Winner Podium & Crowd Stadium (`winner-podium.schem`, 76×36×76)**: Tiered red/gold bleachers, triumph arch, 1st/2nd/3rd stepped podiums, and automatic **crowd teleportation** (`locations.winner-crowd`) during the 6-second firework celebration.

### 🏟 8 Architectural-Grade PvP Arenas (57k–95k Blocks Each + Waiting Skybox)
- **`sword`** (*Grand Roman Colosseum*, 112×52×112) — 3-tier arched amphitheater, stepped bleachers, velarium canopy & sandstone-gold skybox.
- **`nethpot`** (*Gothic Nether Citadel*, 128×52×128) — 8 cascading lavafalls, flying buttresses, hanging soul-lantern chandeliers & crimson-obsidian skybox.
- **`crystal`** (*End Rift Sanctuary*, 112×52×112) — Blast-proof bedrock subfloor, giant diagonal amethyst/obsidian spikes, 6 floating End islands & purpur-amethyst skybox.
- **`build-uhc`** (*Verdant Mountain Valley*, 112×52×112) — Perlin-noise sculpted cliffs, twin waterfalls, timber bridges, multi-branch organic trees & timber-glass skybox.
- **`axe-shield`** (*Gothic Ironkeep Castle*, 108×52×108) — Fortified medieval castle courtyard, pitched-roof turrets, braziers & deepslate-iron skybox.
- **`uhc`** (*Alpine Frontier*, 112×52×112) — Snow-capped Perlin-noise mountain peaks, frozen waterfall, spruce forest & alpine-frost skybox.
- **`smp`** (*Overgrown Ruined Cathedral*, 112×52×112) — Shattered gothic cathedral with stained-glass rose window, Team Wars lanes & mossy-stone skybox.
- **`diamond-pot`** (*Frostglass Ice Cathedral*, 108×52×108) — Towering stained-glass & packed-ice walls, 100% **non-slippery** quartz/prismarine combat floor & cyan-frost skybox.

### ⚡ Zero-Dependency Engine & PvP Hype
- **Pure-Java Sponge v2 `.schem` Paster (`SpongeSchematicPaster`)**: Turbo void-world air skipping pastes massive builds at 20 TPS — **WorldEdit is optional!** Drop custom `.schem` files into `plugins/ClasherEvents/schematics/` anytime.
- **Automatic Post-Match Block Rollback (`ArenaBlockTracker`)**: Restores every placed/broken block, liquid flow, fire, and explosion smoothly at 20 TPS.
- **First Blood, Kill Streaks, Visual Lightning & 2-Tick Auto-Respawn**, **Live BossBar/Scoreboard/Titles**, **Elo & Multi-Tab Leaderboard GUI**, **Async Disk Persistence**, **Auto-Scheduler**, **Discord Webhooks**, **Optional PlaceholderAPI**, and **Command/Container Shield**.

---

## Quick Start

1. Download **`clasher-events-2.2.0.jar`** from [Releases](https://github.com/Pranjal3125/clasher-events/releases).
2. Drop it into your Paper `26.2+` server's `plugins/` folder *(WorldEdit, WorldGuard, and PlaceholderAPI are all optional!)*.
3. Start the server and run **`/ce setup refresh`** (pastes the Sky Citadel Lobby, Winner Podium, and all 8 architectural arenas with their built-in sealed Waiting Skyboxes!).
4. Inspect with **`/ce tp lobby`**, **`/ce tp winner`**, **`/ce tp nethpot waiting`**, **`/ce tp sword`**, etc.
5. Want a custom waiting room for any arena? Stand inside it and run **`/ce setwaiting <arena>`**!

## Commands

| Command | Description |
|---|---|
| `/ce setup [refresh]` | One-command setup: paste Lobby, Winner Podium & all 8 arenas + waiting skyboxes (`refresh` updates bundled schematics) |
| `/ce setwaiting <arena>` / `/ce clearwaiting <arena>` | Set a custom per-arena waiting lobby anywhere (or clear back to global Lobby) |
| `/ce tp <lobby\|winner\|crowd\|arena> [waiting\|audience\|wave1\|final]` | Teleport directly to Lobby, Winner Podium, Crowd Hall, Arena, or its Waiting Skybox |
| `/ce skip [sec\|now]` / `/ce timeskip` | Skip registration countdown (to 5s, by X sec, or instant start) / finish mode vote |
| `/ce gui` / `/ce create <mode>` / `/ce voteopen [sec]` | Admin panel, instant event open, or player mode vote |
| `/ce setlobby\|setspectator\|setwinner\|setcrowd` | Customize global lobby, spectator, champion podium, or crowd spawn positions |
| `/ce setspawn\|addspawn <arena> [wave1\|wave2\|final\|audience\|waiting]` | Set or append arena spawn coordinates |
| `/ce pause\|resume\|start\|stop\|reload` | Event lifecycle control |
| `/event join` / `/event leave` / `/event modevote` | Join/leave events & vote on next mode |
| `/event stats` / `/event top [elo\|wins\|kills\|points]` / `/event history` | Elo/K/D/Streak stats, Leaderboard GUI & match history |

## Build & Test

```bash
mvn clean package
```

Runs all 12 JUnit 5 tests (parsing all 10 `.schem` binaries, validating every Paper 26.2 block material, asserting 100%-sealed skybox shells, verifying every spawn clearance, and validating anti-escape combat/build/spectator boundaries) and produces `target/clasher-events-2.2.0.jar`.
