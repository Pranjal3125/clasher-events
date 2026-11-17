<div align="center">

# Clasher Events

### Modular PvP events for Clasher Network

A standalone Paper plugin project for managing PvP modes, arenas, kits, tournament progression, spectators, and player recovery.

![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk) ![Platform](https://img.shields.io/badge/Platform-Paper-313131?logo=minecraft) ![Status](https://img.shields.io/badge/Status-Development%20candidate-yellow)

[Source](https://github.com/Pranjal3125/clasher-events) · [Issues](https://github.com/Pranjal3125/clasher-events/issues)

</div>

> **Development status:** The documented 0.5.0 candidate passed YAML parsing and Java syntax screening only. It has not passed a dependency-backed Maven build or live multiplayer staging test. Do not treat it as production-certified.

## Overview

The project documents these event systems:

- Battle Royale registration, kits, eliminations, waves, and winner detection
- 1v1 duels and sequential tournament brackets
- Team Wars with balanced teams and friendly-fire prevention
- Random Kit mode, spectator respawn, voting, predictions, and cheering
- Per-player world borders for arenas sharing one world
- Arena reset and baseline backup workflows
- Player-state recovery after reconnecting from a server crash

## Modes and arenas

The documented arena/kit set includes NethPot, Axe & Shield, Build UHC, Sword, UHC, Crystal, SMP, and Diamond Pot. Duel and tournament modes use the Sword arena; Team Wars uses the SMP arena. One event is designed to run at a time in the shared `clasher_events` world.

## Admin commands

| Command | Purpose |
|---|---|
| `/ce gui` | Open event selection and admin GUI |
| `/ce countdown` | Select registration countdown |
| `/ce setlobby` | Save waiting lobby |
| `/ce setspectator` | Save spectator destination |
| `/ce setwinner` | Save winner destination |
| `/ce setspawn <mode> <phase>` | Configure mode/phase spawn |
| `/ce addspawn <mode> <phase>` | Add another spawn point |
| `/latejoin <player>` | Force-add an online player (admin) |
| `/event spectate` | Open fighter spectator selector |
| `/event stats` | View event stats |
| `/event top` | View audience-points leaderboard |

See [DEVELOPMENT.md](DEVELOPMENT.md), [APPROVED-0.6.0-SPEC.md](APPROVED-0.6.0-SPEC.md), and [NETHPOT-ARENA-UPDATE.txt](NETHPOT-ARENA-UPDATE.txt) for detailed project notes.

## Requirements

- Paper API matching the version configured in `pom.xml`
- Java 25 and Maven for the documented build environment
- WorldEdit 7.4.6 for schematic pasting and arena backups
- WorldGuard 7.0.18 is optional but recommended for region protection

Check the POM and plugin configuration before choosing a server version.

## Build

```bash
mvn clean package
```

The plugin JAR should appear under `target/` after a successful build.

## Before staging or production use

1. Build with the required JDK and dependencies.
2. Install on a disposable test server, not the live network.
3. Configure every arena and phase spawn point.
4. Verify kit delivery, inventory restoration, eliminations, and winner detection.
5. Test arena reset and backup restoration.
6. Test reconnect and crash-recovery flows with disposable accounts.
7. Verify reward commands with harmless test commands first.

The schematics are starter assets and may need editing. Discord integration and scheduled event features are not included in the documented milestone.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Include Paper/Java versions, reproduction steps, and relevant logs in bug reports.

## License

No license is currently declared. All rights are reserved by default; do not redistribute or reuse code/assets without permission from the repository owner.
