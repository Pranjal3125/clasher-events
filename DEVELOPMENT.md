# Clasher Events 0.5.0 development status

## Implemented in this source milestone
1. WorldGuard region creation per arena, PvP allowance, default build protection, and Build UHC member-only building.
2. Arena baseline capture after the first starter-schematic paste; reset restores the saved full configured bounds and removes non-player entities.
3. Balanced Red/Blue Team Wars, friendly-fire prevention for direct and projectile attacks, team elimination victory, team query and configurable team rewards.
4. Sequential 1v1 tournament bracket rounds with random seeding, byes, match progression and champion handling.
5. Admin event-selection GUI and read-only kit-preview GUI.
6. Configurable console reward commands with player/UUID/arena/mode placeholders and a one-time winner reward guard.
7. Persistent player-state recovery file for reconnect after an unexpected server/plugin crash.

Also retained: eight arenas/kits, WorldEdit paste, registration countdown, Battle Royale phases, spectator voting, persistent audience points, player-specific borders, UHC natural regen restriction and basic state restoration.

## Verification status
- `config.yml`, `arenas.yml`, `plugin.yml` and all eight kit YAML files parse successfully.
- Java source passed a syntax-only `javac` screening (no Java syntax diagnostics).
- A real compile has **not** been run: this workspace currently has Java 21 and no Maven executable, while the project targets Java 25 and Paper 26.1.2.
- No live Paper/WorldEdit/WorldGuard integration test has been run.

## Build and staging gate
Use JDK 25 and Maven: `mvn clean package`. Then stage on the exact Paper target with WorldEdit 7.4.6 and WorldGuard 7.0.18. Test all modes with multiple clients, arena reset after block/entity changes, Build UHC member/non-member placement, quit/rejoin recovery, server restart recovery, rewards, tournament byes and disconnects. Do not publish a production JAR until the build and staging checks pass.

## NethPot arena geometry update

The NethPot starter schematic is now 128x30x128 (centered at the configured paste origin), with perimeter bastions, a curtain wall, lava moat, four bridges, central keep plinth, cover structures, spectator gallery and side balconies. See `NETHPOT-ARENA-UPDATE.txt` for server-side replacement instructions.


## 0.6.0 development scope

See `APPROVED-0.6.0-SPEC.md` for the locked feature contract and release gates. The 0.6.0-SNAPSHOT metadata is a development marker, not a claim of a completed or tested JAR.

## 0.6.0 implementation work in progress

The current source patch adds the following functional paths:
- `/latejoin <playername>` admin command declaration and handler.
- `/ce countdown` GUI with 2/5/10 minute registration presets.
- `/ce setlobby`, `/ce setspectator`, `/ce setwinner` location setters.
- Configurable named locations and lobby radius protection.
- Delayed rules book on `/event join`, removed when the fighter kit is delivered.
- Lobby damage, hunger, block, interaction, pickup, drop and inventory protections.
- 60-second disconnected-fighter grace period, with elimination when it expires.
- Basic command tab completion.
- Original NethPot schematic extracted from the user's supplied world ZIP.

This remains a development build. It has not passed Java 25 compilation or multiplayer
acceptance testing. The tournament/statistics/scoreboard feature set
still requires implementation and verification before release. Discord integration and
scheduled event automation are intentionally excluded from this project scope.


Additional core PvP work in this pass:
- Admin control-centre actions for countdown, pause/resume, stop and status.
- Spectator target GUI and spectator-only chat.
- Persistent player kills/deaths/wins and match history with MVP selection.
- Lightweight action-bar match status.
- Configurable AFK warning/elimination.
- Combat attribution for player/projectile kills.

Build status: YAML and source-structure checks passed. Java compilation has not been
completed: the environment has Java 21, while the project targets Java 25 and requires
Paper/WorldEdit/WorldGuard dependencies that are not present here. This is not a
verified installable JAR.
