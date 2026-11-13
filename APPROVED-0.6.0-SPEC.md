# Clasher Events 0.6.0 — Approved Development Specification

This file is the implementation contract for the 0.6.0 development line.
It does not claim that every item below is already implemented.

## Non-negotiable gameplay requirements
- PvP combat responsiveness takes priority over cosmetic features.
- Do not disable Vulcan or grant blanket anti-cheat bypass permissions.
- Avoid per-tick scoreboard refreshes, synchronous heavy file operations during combat,
  repeated world scans, and unnecessary particle/sound spam.
- Preserve existing kit delivery threshold (60 seconds before event start) unless the
  administrator changes it.
- Keep kits, arena schematics, arena coordinates, rewards, sounds, rules and timings
  editable through YAML wherever practical.

## Event lifecycle
- Admin chooses event mode/arena and registration countdown from a GUI: 2, 5 or 10 min.
- Registration join does not grant a combat kit. Fighters receive kit when moved into
  the active arena, including normal event start and approved late join.
- Admin late-join command is exactly `/latejoin <playername>` (one word).
- A player who has not joined remains in the spectator/waiting lobby.
- Eliminated players move to the spectator location; winner moves to winner location.
- Configurable waiting-lobby, spectator and winner locations; commands:
  `/ce setlobby`, `/ce setspectator`, `/ce setwinner`.
- Disconnect policy: 60-second rejoin window. If the player does not reconnect within
  60 seconds, eliminate them. Rejoin within the window restores saved event state and
  must not issue a fresh kit. Eliminated players cannot rejoin as fighters.

## Player experience
- Delayed rules book on join; remove the book when fighter kit is delivered.
- Protected waiting lobby: no PvP/damage, hunger drain, block place/break, item use/drop,
  or inventory manipulation; normal movement remains enabled.
- Concise configurable sounds for join, kit, countdown, start, elimination and winner.
- Fireworks only at event finale, not during combat.
- Tab completion for player/admin commands, modes, arenas, kits and online player names.

## Admin and tournament features
- Admin control GUI; event selection/configuration; pause/resume/stop; participant and
  disconnected-player management.
- Tournament brackets for configurable player counts, match progression and finals.
- Spectator target switching, spectator chat, live fighter health/remaining count.
- Kill feed, optimized scoreboard/actionbar, MVP, player statistics and leaderboards.
- AFK and combat-logging protections; match history; audience predictions/points.
- Rewards via configurable console commands.

## Compatibility and safety
- Paper target must match the production server API.
- WorldEdit/FAWE and WorldGuard are soft dependencies; fail clearly if a feature needs
  one that is absent.
- Vulcan remains enabled. No generic exemption/bypass is granted by this plugin.
- Inventory/player snapshots must be restored on normal finish, leave, disconnect
  expiry and server shutdown; crash recovery must be tested separately.

## Release gate
A feature is not marked complete until source review, successful Java 25 build against
the target Paper API, YAML validation, command permission/tab-completion tests,
and multiplayer tests pass. PvP combat, reconnect, inventory restoration and arena
reset are release-blocking tests.
