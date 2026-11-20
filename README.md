<div align="center">

# Clasher Events

### Modular PvP events for Clasher Network

A standalone Paper plugin project for managing PvP modes, arenas, kits, tournament progression, spectators, and player recovery.

![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk) ![Platform](https://img.shields.io/badge/Platform-Paper-313131?logo=minecraft) ![Status](https://img.shields.io/badge/Status-Development%20candidate-yellow)

[Source](https://github.com/Pranjal3125/clasher-events) · [Issues](https://github.com/Pranjal3125/clasher-events/issues)

</div>

> **Development status:** The latest GitHub Actions build completed successfully with Java 25 and produced a plugin JAR artifact. This confirms the configured CI build passes; it does not certify live multiplayer behavior or production readiness. Test on a disposable Paper server before deployment.

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
| `/ce gui` | Open the 0.7.0 Admin Control Center |
| `/ce countdown` | Select registration countdown |
| `/ce diagnose` | Check loaded arenas, kits, dependencies, and named locations |
| `/ce arena validate <arena>` | Validate an arena's required setup |
| `/ce arena list` | List configured arenas |
| `/ce reward list` | List configured winner reward commands |
| `/ce reward add <winner\|team-winner> <command...>` | Add a console reward command |
| `/ce reward remove <winner\|team-winner> <index>` | Remove a reward command by 1-based index |
| `/ce reward clear <winner\|team-winner>` | Clear reward commands for a reward type |
| `/ce preset list` | List reusable event presets |
| `/ce preset apply <name>` | Open registration using a configured preset |
| `/ce debug on\|off\|status` | Toggle opt-in lifecycle debug logs |
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

## 0.7.0 development additions

- Admin Control Center with event, countdown, status, diagnostics, and stop controls.
- Arena Manager GUI with per-arena setup shortcuts for Wave 1, Wave 2, Final, and Audience spawns, plus validation.
- Kit Manager GUI for browsing, previewing, and reloading kit YAML files.
- Player Manager view for registered participants and online/alive status.
- Statistics leaderboard for recorded kills and deaths.
- Winner Rewards viewer and `/ce reward` commands to list, add, remove, or clear configured console reward commands.
- Settings GUI to cycle registration duration, kit-delivery lead time, and audience prediction points while no event is active.
- Event start preflight checks that refuse to begin when the selected arena, kit, or required starting-phase spawns are missing.

The 0.7.0 branch is a development candidate. A successful CI build verifies compilation and packaged plugin metadata; it does not replace live Paper server testing.

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

The plugin JAR should appear under `target/` after a successful build. GitHub Actions is configured to run the project build; check the [Actions page](https://github.com/Pranjal3125/clasher-events/actions) for the latest result.

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
