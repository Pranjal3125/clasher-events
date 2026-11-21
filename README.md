<div align="center">

# Clasher Events

### Modular PvP Events & Tournament Engine for Clasher Network

A standalone Paper plugin for managing PvP modes, arenas, kits, tournament brackets, spectators, and crash-safe player recovery.

![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)
![Platform](https://img.shields.io/badge/Platform-Paper-313131?logo=minecraft)
![Status](https://img.shields.io/badge/Status-Development%20Candidate-yellow)

[Source](https://github.com/Pranjal3125/clasher-events) · [Issues](https://github.com/Pranjal3125/clasher-events/issues) · [Actions](https://github.com/Pranjal3125/clasher-events/actions)

</div>

> **Note:** Built with Java 25 via GitHub Actions. Test on a disposable Paper staging server before live deployment.

## Features

- **Battle Royale** — registration, kits, eliminations, waves, and winner detection.
- **1v1 Duels & Tournaments** — sequential tournament brackets and match progression.
- **Team Wars** — balanced team assignment with friendly-fire prevention.
- **Interactive Modes & Audience Tools** — Random Kit mode, spectator respawn, voting, predictions, and cheering.
- **Arena Management** — per-player world borders for shared-world arenas, plus arena reset and baseline backup workflows.
- **Crash Recovery** — automatic player-state recovery when reconnecting after a server crash.

## Modes & Arenas

Supported arena and kit presets include **NethPot**, **Axe & Shield**, **Build UHC**, **Sword**, **UHC**, **Crystal**, **SMP**, and **Diamond Pot**.

- Duel and tournament modes use the **Sword** arena.
- Team Wars uses the **SMP** arena.
- One event runs at a time in the shared `clasher_events` world.

## Commands

| Command | Purpose |
|---|---|
| `/ce gui` | Open event selection and admin GUI |
| `/ce countdown` | Select registration countdown |
| `/ce setlobby` | Save waiting lobby location |
| `/ce setspectator` | Save spectator destination |
| `/ce setwinner` | Save winner destination |
| `/ce setspawn <mode> <phase>` | Configure mode/phase spawn |
| `/ce addspawn <mode> <phase>` | Add another spawn point |
| `/latejoin <player>` | Force-add an online player (admin) |
| `/event spectate` | Open fighter spectator selector |
| `/event stats` | View event statistics |
| `/event top` | View audience-points leaderboard |

See [DEVELOPMENT.md](DEVELOPMENT.md), [APPROVED-0.6.0-SPEC.md](APPROVED-0.6.0-SPEC.md), and [NETHPOT-ARENA-UPDATE.txt](NETHPOT-ARENA-UPDATE.txt) for detailed architecture and design specs.

## Requirements

- Paper API matching the version configured in `pom.xml`
- Java 25 and Maven
- WorldEdit `7.4.6` (schematic pasting and arena backups)
- WorldGuard `7.0.18` *(optional, recommended for region protection)*

## Build

```bash
mvn clean package
```

The compiled plugin JAR is generated under `target/`. CI builds are also available on the [GitHub Actions page](https://github.com/Pranjal3125/clasher-events/actions).

## Staging Checklist

1. Build with the required JDK and dependencies.
2. Install on a disposable test server rather than the live network.
3. Configure every arena and phase spawn point.
4. Verify kit delivery, inventory restoration, eliminations, and winner detection.
5. Test arena reset and backup restoration.
6. Test reconnect and crash-recovery flows with test accounts.
7. Verify reward commands with harmless test commands first.

> Included schematics are starter assets and can be customized. Discord integration and scheduled events are outside the current milestone.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please include Paper/Java versions, reproduction steps, and relevant logs in bug reports.

## License

All rights reserved. Contact the repository owner before reusing or redistributing code or assets.
