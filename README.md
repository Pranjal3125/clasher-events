<div align="center">

# Clasher Events

### Modular PvP Events & Tournament Engine for Clasher Network

A standalone Paper 26.2 plugin for managing PvP modes, arenas, kits, tournament brackets, Team Wars, spectators, audience predictions, and crash-safe player recovery.

[![Maven build](https://github.com/Pranjal3125/clasher-events/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Pranjal3125/clasher-events/actions/workflows/build.yml)
![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)
![Platform](https://img.shields.io/badge/Platform-Paper%2026.2-313131?logo=minecraft)
![Build](https://img.shields.io/badge/CI-GitHub%20Actions-blue?logo=githubactions)

[Source](https://github.com/Pranjal3125/clasher-events) · [Issues](https://github.com/Pranjal3125/clasher-events/issues) · [Actions](https://github.com/Pranjal3125/clasher-events/actions)

</div>

> **Note:** Built with Java 25 and Paper 26.2. Always test new builds on a disposable Paper staging server before live deployment.

## Features

- **Battle Royale** — registration countdown, timed kit delivery, wave1 / wave2 / final phase transitions, per-player shrinking world borders, AFK elimination, and MVP tracking.
- **1v1 Duels & Tournament Brackets** — randomized single-elimination rounds with automatic byes, spectator staging, and match progression.
- **Team Wars** — shuffled Red vs Blue balancing, friendly-fire protection, `/event team` squad view, and full team win rewards & match history.
- **Interactive Modes & Audience Tools** — Random Kit mode, isolated spectator chat (using Paper `AsyncChatEvent` without ever affecting normal server chat), winner predictions (`/event vote`), cheering (`/event cheer`), spectator selector (`/event spectate`), and audience-points leaderboard (`/event top`).
- **Secure Paper 26.2 GUIs** — `InventoryHolder`-backed Admin Control Panel (`/ce gui`), Registration Countdown selector, enchanted Kit Preview (`/ce kit preview`), Winner Prediction GUI, and Fighter Spectator GUI.
- **Arena Management** — WorldEdit schematic pasting, baseline backup & reset workflow, entity cleanup, and optional WorldGuard region & Build UHC builder flag management.
- **Atomic Persistence & Crash Recovery** — atomic temp-file-and-move saves for `player-recovery.yml`, `player-stats.yml`, `audience-points.yml`, `match-history.yml`, and `arenas.yml`, with automatic inventory/state restoration on reconnect after a crash.

## Modes & Arenas

Supported arena and kit presets include **NethPot**, **Axe & Shield**, **Build UHC**, **Sword**, **UHC**, **Crystal**, **SMP**, and **Diamond Pot** (plus **Random Kit**, **1v1 Duel**, **Team Wars**, and **Tournament**).

- Duel and Tournament modes use the **Sword** arena.
- Team Wars uses the **SMP** arena.
- One event runs at a time in the shared `clasher_events` world.

## Commands

### Players (`/event`)

| Command | Purpose |
|---|---|
| `/event join` | Register for an open event and enter the waiting lobby |
| `/event leave` | Leave the current event and restore inventory/state |
| `/event vote [player]` | Open the winner-prediction GUI or vote directly (spectators) |
| `/event cheer <player>` | Broadcast a cheer for an active fighter (spectators) |
| `/event team` | View your Team Wars squad and alive counts |
| `/event spectate` | Open the fighter spectator selector GUI |
| `/event stats` | View your wins, kills, deaths, and audience points |
| `/event points` | View your audience prediction points |
| `/event top` | View the top-10 audience-points leaderboard |
| `/event history` | View the last 10 completed event matches |

### Administrators (`/ce`, `/latejoin`)

All admin commands require `clasherevents.admin` (default: operators).

| Command | Purpose |
|---|---|
| `/ce gui` | Open the 45-slot event mode & control panel GUI |
| `/ce create <mode>` | Open registration for a mode (`nethpot`, `duel`, `team-wars`, `tournament`, `random-kit`, etc.) |
| `/ce start` / `/ce stop` | Start or stop the current event immediately |
| `/ce pause` / `/ce resume` | Pause or resume registration countdown and combat damage |
| `/ce countdown` | Open the 2 / 5 / 10 minute countdown selector GUI |
| `/ce status` | Inspect active mode, arena, phase, registered, and alive counts |
| `/ce reload` | Live-reload `config.yml`, `arenas.yml`, and `kits/*.yml` |
| `/ce setlobby` / `/ce setspectator` / `/ce setwinner` | Save named event locations |
| `/ce setspawn <mode\|arena> [phase]` | Replace spawn points for `wave1`, `wave2`, `final`, or `audience` |
| `/ce addspawn <mode\|arena> [phase]` | Append an additional spawn point for a phase |
| `/ce arena list\|paste\|reset\|info\|validate <arena>` | Manage arena schematics, backups, and validation |
| `/ce kit list\|give\|preview\|reload` | Inspect, preview, give, or reload YAML kit presets |
| `/latejoin <player>` | Force-join an online player into an active match |

## Requirements

- Paper 26.2 (Java 25)
- WorldEdit `7.4.6` *(optional, required for schematic pasting and arena backups)*
- WorldGuard `7.0.18` *(optional, recommended for arena region protection)*

## Build & Tests

```bash
mvn --batch-mode clean package
```

Compiles with Java 25 (`-Xlint:deprecation`), runs the JUnit 5 test suite, and outputs `target/clasher-events-1.0.0.jar`.

## Staging Checklist

1. Install on a disposable Paper 26.2 test server with WorldEdit (and optional WorldGuard).
2. Configure lobby/spectator/winner locations and arena phase spawn points.
3. Verify kit delivery, rules book interaction in lobby, inventory restoration on `/event leave`, eliminations, and winner detection.
4. Test spectator chat isolation alongside normal player chat.
5. Test arena paste, reset, and baseline backup restoration.
6. Test disconnect grace timer and crash-recovery flows with test accounts.
7. Verify reward commands with harmless test commands first.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please include Paper/Java versions, reproduction steps, and relevant logs in bug reports.

## License

All rights reserved. Contact the repository owner before reusing or redistributing code or assets.
