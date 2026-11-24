<div align="center">

# Clasher Events `v2.4.0`

### Zero-Dependency Pro Competitive PvP Tournament & Events Engine for Paper 26.2+

A modular, tier-1 Minecraft PvP events engine built for **Clasher Network** — featuring **authentic MCTiers / Stray.gg / PvP Legacy synced kits** (zero free pre-applied potion effects!), **REAL WorldBorder damage + dynamic alive-player border shrinking**, **multi-kit selection for 1v1 Duels, Team Wars & Tournaments** (CLI + GUI), **player-initiated `/event duel <player>` with automatic Admin Event Priority Override**, **8 architectural-grade PvP arenas with sealed themed Waiting Skyboxes**, **5-layer Anti-Escape Containment**, **Personal Kit Layout Editor** (+ admin `/ce kit save`), **flicker-free Adventure HUD with Below-Name HP, Ranked Elo Tiers & Branded Tablist**, **native Paper 3D `TextDisplay` Holograms**, **1st/2nd/3rd Royal Winner Podium Ceremony**, **pure-Java Sponge `.schem` auto-pasting**, and **tick-batched rollback**.

![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![Paper](https://img.shields.io/badge/Paper-26.2%2B-38bdf8)
![Dependencies](https://img.shields.io/badge/Required%20Deps-Zero-10b981)
![Tests](https://img.shields.io/badge/JUnit%205-15%20Passing-brightgreen)
![Release](https://img.shields.io/badge/Version-2.4.0-a855f7)

[Source](https://github.com/Pranjal3125/clasher-events) · [Releases](https://github.com/Pranjal3125/clasher-events/releases) · [Issues](https://github.com/Pranjal3125/clasher-events/issues)

</div>

---

## What's New in v2.4.0 — Real PvP Meta, Border Damage & Multi-Kit Duels

### 🔥 Real WorldBorder Damage & Player-Scaled Dynamic Shrinking
- **Server-Side Border Damage Fixed**: Paper's per-player virtual WorldBorders are visual-only by default — Clasher Events now tracks the exact real-time interpolated border size and deals genuine escalating damage (`world-border.damage-per-second: 2.0`, i.e., `1 ❤/s + distance scaling`) + red warning ActionBar to any fighter outside the shrinking red wall!
- **Dynamic Alive-Player Border Shrinking (`world-border.dynamic-shrink: true`)**: Automatically tightens the border smoothly as players are eliminated (`23+ → Full`, `15–22 → 78×78`, `9–14 → 64×64`, `5–8 → 50×50`, `3–4 → 38×38`, `2 → 26×26`), displayed live right on the BossBar & Sidebar (`Border: 50x50`)!

### ⚔️ Authentic Popular PvP Server Kits (MCTiers / Stray.gg / PvP Legacy Sync)
- **ZERO pre-applied potion effects on spawn** across all 8 kits — every buff (Speed II, Strength II, Fire Resistance) comes from splashing/drinking real potions in the kit!
- **`NethPot`**: Prot 4 / Unb 3 / Mending Netherite (+ Resp 3, Aqua Aff, Feather Falling 4, Depth Strider 3), Sharp 5 Fire Aspect 2 Sweeping 3 Sword, **64 Golden Apples in offhand**, 2×64 XP Bottles, 2×16 Pearls, Splash Swiftness II / Strength II / Fire Res rebuffs + full Splash Healing II inventory.
- **`Diamond Pot`**: Prot 4 Diamond, Sharp 5 Fire Aspect 2 Sword, **64 Golden Carrots in offhand**, Pearls, drinkable Swiftness II / Strength II / Fire Res + full Splash Healing II inventory.
- **`Sword`**: Pure Prot 3 Diamond + Sharp 4 Sweeping 3 Sword + 6 Golden Apples + 64 Golden Carrots (no pearls, no fire aspect, no potions).
- **`Axe & Shield`**: Prot 1 Diamond, **Unb 3 Shield in offhand**, Diamond Axe + Sword + Bow + Crossbow + **6 Arrows** + 4 Golden Apples + Steak.
- **`Crystal`**: Prot 4 Netherite + **Blast Prot 4 Leggings**, **Offhand Totem + 7 backup Totems**, KB 1 Sharp 5 Sword, Eff 5 Pickaxe, Obsidian, Crystals, Anchors, Glowstone, XP Bottles, Pearls, Power 5 Punch 2 Bow.
- **`UHC`**: Prot 3/2 Diamond, **Offhand Shield**, Sharp 4 Sword, Axe, Power 3 Bow, Piercing Crossbow, 12 Golden Apples, Water/Lava, Cobwebs, Blocks (natural regen OFF).
- **`SMP`**: Prot 4 Netherite, **Offhand Totem + 2 backups**, Sharp 5 Sword & Axe, Shield, Pearls, Gapples, Cobwebs, Water, XP Bottles + Splash Strength II / Swiftness II / Fire Res.

### 🎯 Kit Selection for Duels, Team Wars & Tournaments + Casual Player Duels
- **Admin Multi-Kit Creation**: Run **`/ce create duel <kit>`**, **`/ce create team-wars <kit>`**, or **`/ce create tournament <kit>`** — OR click **1v1 Duel / Team Wars / Tournament** in **`/ce gui`** to open the interactive **Kit & Arena Selector GUI**!
- **Casual Player 1v1 Duels (`/event duel <player> [kit]`) with Admin Event Priority Override**:
  - Normal players can challenge each other via `/event duel <player>` (opens Kit Picker GUI → clickable `[✔ Accept]` / `[✖ Decline]` invite) across free arenas simultaneously.
  - **Zero Arena Conflict Guarantee**: The exact instant an admin starts an official event (`/ce create` / `/ce voteopen` / Auto-Scheduler), all active casual duels automatically end cleanly, restore duelists to Lobby, roll back blocks immediately, and free every arena for the Server Event!

---

## What's New in v2.3.0 — Pro Polish & Competitive PvP Overhaul

### ⚔️ Competitive Kit Engine, Offhand Fix & Personal Kit Layout Editor
- **Offhand & Per-Piece Enchantment Support**: Fixed `KitManager` so `offhand:` (`SHIELD` in `Axe & Shield` & `SMP`, `TOTEM_OF_UNDYING` in `Crystal` & `NethPot`), per-piece armor enchants (`Blast Protection IV` leggings in Crystal!), full-inventory `fill-potion` splash healing refills (NethPot & Diamond Pot), and `Build UHC` tools/food work out of the box.
- **Interactive Personal Kit Layout Editor (`/event kiteditor [kit]` & Lobby Hotbar Slot 1)**: Players rearrange their own hotbar, inventory, and offhand slots via a click-to-swap chest GUI (zero dupe risk) — saved per-player per-kit in `kit-layouts.yml` and automatically applied every match!
- **In-Game Admin Kit Management**: Stand with gear in your inventory and run **`/ce kit save <kit>`** to edit or create kits with zero YAML editing, or run **`/ce kit refresh-defaults`** to pull the updated `v2.3` competitive kits anytime.

### ✨ Flicker-Free Pro HUD, Below-Name HP, Elo Ranks & Tablist
- **Zero-Flicker Cached Scoreboards**: Native Adventure Team-prefix updates eliminate sidebar flicker and add live **Below-Name Health (`❤ 20`)**, **Elo Rank Tier Badges** (`[Bronze]`, `[Silver]`, `[Gold]`, `[Diamond]`, `[Master]`, `[Clasher]`), match timer, and **Live Hit Health ActionBar** (`Opponent ❤ 14.5/20`).
- **Branded Live Tablist**: Displays mode, alive count, phase, player rank/Elo, and `branding.server-ip` (`play.clashernetwork.fun`).
- **Team Wars Polish**: Colored `[RED]` / `[BLUE]` nametags on scoreboards + private Team Chat via **`/event tc <msg>`** or **`@msg`** in chat!

### 🏆 1st / 2nd / 3rd Podium Ceremony & Native 3D Floating Holograms
- **Native Paper `TextDisplay` Holograms (`HologramManager`)**: Zero external hologram plugin required! Automatically renders floating 3D **Welcome Banner**, **Top 5 Elo**, **Top 5 Wins**, **Sky Parkour Start/Finish**, and **1st / 2nd / 3rd Winner Podium Plaques** (deduplicated cleanly across restarts & refreshed via `/ce holograms`).
- **Full 3-Place Royal Podium Ceremony & Combat Recap**: Teleports **1st Place** (Gold Podium), **2nd Place** (Iron Podium), **3rd Place** (Copper Podium), and all other players (Crowd Bleachers) during fireworks, and prints a centered **Post-Match Combat Recap Card** (Top 3, MVP kills, Top Damage Dealt, Duration).

### 🔥 Fair Combat Flow, Bounties, Tournament Polish & Lobby Parkour
- **3-Second Grace Freeze (`3..2..1.. FIGHT!`)**: Protects fighters from spawn-kills while chunks load at match start and before every 1v1 Tournament round.
- **Tournament Polish**: Auto-warps spectators to the arena's `audience` gallery and automatically rolls back blocks + clears drops/crystals between rounds!
- **Anti-Cleanup Kill Heal, Streak Bounties & Final Duel Glow**: Instant `+4 ❤` + `4s Absorption I` on melee/UHC kills, **`+10 Bonus Elo` Bounty** when shutting down a 3+ kill streak, and optional **Glowing** outline during the Final Duel!
- **Sky Citadel Lobby Parkour Stopwatch & Smart Registration**: Live stopwatch, Personal Best & Server Record broadcasts on the Lobby spiral sky-parkour; automatic `10s` fast-start when max players join; clickable Spectator Quick-Action bar on elimination; `/event stats [player]` profile lookup!

---

## Included Architectural Builds & Anti-Escape Engine

- **Per-Arena 100%-Sealed Waiting Skyboxes** (`19×9×19` panoramic themed glass pavilions above every arena + custom `/ce setwaiting <arena>` / `/ce clearwaiting <arena>` / `/ce tp <arena> waiting`).
- **5-Layer Anti-Escape Containment**: Blocks Ender Pearls / Chorus (`CONSUMABLE_EFFECT`) / Portals outside combat bounds or into skyboxes, clamps fighters inside `playable-radius` & `combat-max-y`, locks subfloor/outer walls against digging/building/liquids, matches per-player WorldBorders from second 0, and leashes waiting players & spectators.
- **8 Rebuilt Architectural PvP Arenas (57k–95k blocks each)** + **Floating Sky Citadel Lobby** + **Royal Champions Winner Podium** — pasted tick-batched with **zero WorldEdit required**!

---

## Quick Start

1. Download **`clasher-events-2.3.0.jar`** from [Releases](https://github.com/Pranjal3125/clasher-events/releases).
2. Drop it into your Paper `26.2+` server's `plugins/` folder *(WorldEdit, WorldGuard, Hologram plugins, and PlaceholderAPI are all optional!)*.
3. Start the server and run:
   - **`/ce setup refresh`** — pastes Lobby, Winner Podium, 3D Holograms & all 8 arenas with sealed Waiting Skyboxes!
   - **`/ce kit refresh-defaults`** — extracts the upgraded `v2.3` competitive kits (with offhand shields/totems & full splash healing)!
4. Inspect with **`/ce tp lobby`**, **`/ce tp winner`**, **`/ce tp nethpot waiting`**, **`/ce tp sword`**, etc.

## Commands

| Command | Description |
|---|---|
| `/ce setup [refresh]` | One-command setup: paste Lobby, Winner Podium, 3D Holograms & 8 arenas + skyboxes |
| `/ce kit save <kit>` / `refresh-defaults` / `preview` / `give` | Save inventory as kit in-game, refresh pro kits, preview, or give |
| `/ce setwaiting <arena>` / `/ce clearwaiting <arena>` | Set custom per-arena waiting lobby anywhere (or fall back to global Lobby) |
| `/ce tp <lobby\|winner\|crowd\|arena> [waiting\|audience\|wave1\|final]` | Teleport to Lobby, Winner Podium, Crowd, Arena, or Waiting Skybox |
| `/ce skip [sec\|now]` / `/ce holograms` / `/ce gui` / `/ce create <mode>` | Timer skip, refresh 3D holograms, admin panel, create event |
| `/event join` / `/event leave` / `/event kiteditor [kit]` | Join/leave events & customize personal kit layout GUI |
| `/event tc <msg>` *(or `@msg`)* / `/event team` | Private Team Wars chat & squad info |
| `/event stats [player]` / `/event top [elo\|wins\|kills\|points]` | Player profile lookup & interactive Leaderboard GUI |
| `/event modevote` / `/event vote` / `/event cheer` / `/event spectate` | Mode voting, winner prediction, cheering & spectator selector |

## Build & Test

```bash
mvn clean package
```

Runs all **14 JUnit 5 tests** (parsing all 10 `.schem` files, validating 128 Paper 26.2 block materials, asserting 100%-sealed skybox shells, verifying all 8 arena spawns + 1st/2nd/3rd Podium + Lobby clearances, validating all 8 competitive kits & zero-dupe custom layout permutation, Elo rank tiers, anti-escape bounds, and async persistence) and produces `target/clasher-events-2.3.0.jar`.
