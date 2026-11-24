<div align="center">

# Clasher Events `v2.7.3`

### Zero-Dependency Pro Competitive PvP Tournament & Events Engine for Paper 26.2+

A modular, tier-1 Minecraft PvP events engine built for **Clasher Network** — featuring **Dual 24/7 Warmup FFA Pits** (Sword FFA + **Deep-Ground Crystal Terrain FFA with 15-block diggable earth down to Bedrock & automatic zero-suffocation periodic terrain reset**), an **Executive 54-Slot Black & Gold Admin Control Panel (`/ce gui`)**, **Multi-Slot 1v1 Auto-Queue & Casual 2v2/3v3 Party Duels**, **Post-Duel Inventory & Combat Analytics Viewer**, **4 Live Clickable Lobby NPCs** (zero external plugin!), **Pro Spectator Hotbar + Host Auto-Cam**, **Event Modifiers / Mutators GUI**, **Anti-Stall Sudden Death & Damage Tie-Breaker**, **Esports Team Tournaments (`1v1 / 2v2 / 3v3 / 4v4`)** with Parallel Early Rounds, Discord Live Bracket Webhooks, Best-of-3 Grand Finals & a dedicated **Grand Esports Tournament Hall**, **8 Dedicated 1v1 Duel Arenas**, **Flagship Imperial Sky Citadel Main Lobby (`116×58×116`)**, **Interactive Voting GUIs**, **Dynamic OP Admin Panel Wand**, **authentic MCTiers / Stray.gg / PvP Legacy synced kits**, **REAL WorldBorder damage + dynamic shrinking**, **native Paper 3D `TextDisplay` Holograms**, **pure-Java Sponge `.schem` auto-pasting**, and **tick-batched rollback**.

![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![Paper](https://img.shields.io/badge/Paper-26.2%2B-38bdf8)
![Dependencies](https://img.shields.io/badge/Required%20Deps-Zero-10b981)
![Tests](https://img.shields.io/badge/JUnit%205-22%20Passing-brightgreen)
![Release](https://img.shields.io/badge/Version-2.7.3-a855f7)

[Source](https://github.com/Pranjal3125/clasher-events) · [Releases](https://github.com/Pranjal3125/clasher-events/releases) · [Issues](https://github.com/Pranjal3125/clasher-events/issues)

</div>

---

## What's New in v2.7.0 — Deep-Ground Crystal Terrain FFA (Bedrock Pit + Auto-Reset) & Redesigned 54-Slot Admin Panel

### 💎 Deep-Ground Crystal Terrain Arena (`crystal-ffa`, `112×62×112`) + Dual 24/7 FFA Pits
- **15 Full Blocks of Destructible Terrain Down to Bedrock**: Added `crystal-ffa.schem` alongside Classic Crystal Flat (`crystal`) — featuring layered `Grass → Dirt → Stone → Deepslate + Obsidian Veins` (`y=1..15`) sitting on top of a **100%-sealed unbreakable Bedrock floor (`y=0`)** so players can hole-fight, pearl-trap, anchor-blast, and dig all the way to Bedrock!
- **Playable Everywhere**: Available as an official Event Mode (`Crystal Terrain` / `crystal-ffa` in `/ce gui`, `/ce create crystal-ffa`, Mode Vote, Team Wars, and `1v1–4v4` Tournaments) AND as a dedicated **24/7 Deep Crystal Terrain FFA Pit**! Shares players' custom `crystal` kit layout from `/event kiteditor crystal`.
- **Dual FFA Selector GUI (`/event ffa` & Lobby NPC)**: Clicking the `🔥 WARMUP FFA PITS` Lobby NPC or running `/event ffa` opens a clean 2-pit selector (`⚔ Sword FFA Pit` vs `💎 Deep Crystal Terrain FFA`) with live fighter counts and next auto-reset countdown (or direct `/event ffa sword`, `/event ffa crystal`, `/event ffa leave`).
- **Automatic Zero-Suffocation Terrain Reset**: Configurable `warmup-ffa.auto-reset-seconds: 300` (default every 5 minutes) warns fighters at 30s / 10s / 3-2-1s, clears craters, obsidian & anchors instantly, and safely teleports active fighters to fresh surface spawns with zero suffocation! Admins can also trigger instant resets via **`/ce ffareset`** or the Admin Panel button.

### ⚡ Executive 54-Slot Black & Gold Admin Control Panel (`/ce gui`)
- **Completely Redesigned 6-Row Symmetrical Layout**: Replaced the cramped 45-slot panel with a spacious, dark-glass framed 54-slot Executive Console:
  - **Row 0**: Centered Live Event Status Card
  - **Row 1**: 7 Core Solo FFA Modes (`NethPot`, `Crystal Flat`, `Crystal Terrain`, `Sword`, `Axe & Shield`, `Build UHC`, `Diamond Pot`)
  - **Row 2**: `UHC` / `SMP` / `Random Kit` | Gold Divider | `1v1 Duel` / `Team Wars` / Glowing `Esports Tournament`
  - **Row 3**: 5 Symmetrical Live Match Controls (`Mode Vote`, `Countdown`, `Skip Timer`, `Pause / Resume`, `Stop`)
  - **Row 4**: 5 Symmetrical Pro Admin Tools (`Tournament Control`, `Event Modifiers`, `Host Auto-Cam`, `♻ Reset Crystal FFA Pit`, `Full Server Setup`)
  - **Row 5**: Clean Gold Footer + Close Panel Button

---

## What's New in v2.6.0 — 24/7 Practice Network, Lobby NPCs, Party Duels, Modifiers & Pro Spectating

### 🎯 Multi-Slot 1v1 Auto-Queue + Casual 2v2/3v3 Party Duels + Post-Duel Inventory Viewer
- **Multi-Slot Dynamic Duel Arena Allocation**: Up to **8 simultaneous same-kit 1v1 duels** run seamlessly across the dedicated `duel-*` arenas (zero "Arena Busy" wait!).
- **1v1 Auto-Queue GUI (`/event queue` & Lobby NPC)**: Click any of the 8 kits to queue for instant matchmaking with live queue counts — auto-pauses cleanly whenever an Official Server Event opens.
- **Casual Party System (`/event party`)**: Invite teammates (`/event party invite <p>`) for **2v2 / 3v3 / 4v4 Party vs Party Duels** (`/event party duel <opponent> [kit]`) or **Party Split Red vs Blue** (`/event party split [kit]`)!
- **Post-Duel Inventory Viewer + Instant `[🔁 Rematch]` & `[⚔ Play Again]` + Live Ping HUD**:
  - Live Duel Sidebar shows **`Your Ping vs Their Ping` (`28ms vs 41ms`)**, kit, timer & Sudden Death countdown.
  - After every duel, click **`[🔍 Winner's Inv]`** / **`[🔍 Loser's Inv]`** to inspect remaining potions, totems, gapples, hits landed, crit %, longest combo & total damage dealt!

### 🔥 Always-Open Warmup FFA Pit + 4 Native Clickable Lobby NPCs
- **Warmup FFA Pit (`/event ffa`)**: Drop in anytime for nonstop PvP warmup — every kill gives instant **Full Heal + Fresh Kit Refill + Killstreak Hype**, and official Server Events automatically pause FFA & restore players to Lobby!
- **4 Native Clickable Armored Lobby NPCs** (Zero Citizens/FancyNpcs required!): Standing in front of the Imperial Main Lobby spawn with live 3D status labels — **Official Event**, **1v1 Duel Queue**, **Warmup FFA Pit**, and **Leaderboards / Kit Layout Editor**!

### ⚡ Event Modifiers, Pro Spectator Hotbar, Host Auto-Cam, Sudden Death & Rules Book Fix
- **Display-Only Rules Book Fix**: Rules Book now displays virtually via `player.openBook(book)` **without ever placing an item in player inventory** — zero interference with any PvP kit or hotbar slot!
- **Event Modifiers GUI (`/ce modifiers` & `/ce gui`)**: Stack fun competitive twists onto any event — **`Double Health (40 HP)`**, **`Vampire Lifesteal (25%)`**, **`Low Gravity`**, **`Speed Chaos`**, **`No Ender Pearls`**, and **`One-Hit Bow`**!
- **Pro Spectator Hotbar + Host Auto-Cam (`/ce autocam`)**: Eliminated spectators get invisible flight mode + clickable hotbar (`Teleport Compass`, `Predict Winner`, `Live Bracket`, `Leaderboards`, `Leave`). Staff/streamers can toggle **`/ce autocam`** to auto-track live PvP fights every 4s!
- **Anti-Stall Sudden Death & Damage Tie-Breaker**: Duels & Tournament matches running past `180s` automatically enter **Sudden Death** (`12×12` border + Glowing + 60s Damage Tie-Breaker)!
- **Discord Live Tournament Bracket Webhooks + Parallel Early Rounds (`/ce tournament parallel`)**: Post live round matchups & results to Discord, and optionally run early 8–16 team rounds simultaneously across multiple arenas while keeping Semifinals & Best-of-3 Grand Finals Center-Stage!

---

## What's New in v2.5.0 — Esports Team Tournaments, Dedicated 1v1 Duel Arenas, Voting GUIs & Imperial Lobby

### 🏆 Multi-Format Esports Team Tournaments (`1v1 / 2v2 / 3v3 / 4v4`) + Grand Tournament Hall
- **Dedicated Grand Esports Tournament Hall (`tournament-lobby.schem`, `104×44×104`)**: Tournament entrants automatically teleport into a separate championship stadium lobby with RED vs BLUE wings, 4 illuminated team staging alcoves, golden crown stage, and **3 live 3D Floating Holograms** (Format/Status Banner, Live Registered Squads Roster, and Live Bracket/Commands).
- **Timed Squad Formation & 1-Click Squad Manager GUI**: Players form squads during registration using **`/event squad`** (1-click invite heads), **`/event squad invite <player>`** (clickable `[✔ Accept]` / `[✖ Decline]`), **`/event squad name <TeamName>`**, **`kick`**, **`leave`**, private **`@msg` / `/event tc`** squad chat, and **Live Bracket & Teams Viewer GUI (`/event bracket`)**.
- **Smart Auto-Fill & Best-of-3 Grand Finals**: Solo and incomplete squads automatically match into balanced full teams at start without splitting friends (`tournament.auto-fill-solos: true`), with optional **Best-of-3 Grand Finals** (`tournament.bo3-finals: true`) and full winning squad celebration on the Royal Gold Podium!
- **Admin Tournament Controls (`/ce tournament`)**: Interactive Admin Tournament Control GUI + `shuffle`, `pair <p1> <p2>`, `disband <id>`, `lock`, `unlock`, `bo3 <on|off>`, and `kitvote [sec]`. Launch via `/ce gui` → Tournament → `1v1 / 2v2 / 3v3 / 4v4` → Kit Picker, or `/ce create tournament <kit> <2v2|3v3|4v4|1v1>`.

### ⚔️ 8 Separate Dedicated 1v1 Duel Arenas (`duel-*`)
- Auto-generated & auto-configured in `arenas.yml` (`duel-nethpot`, `duel-axe-shield`, `duel-build-uhc`, `duel-sword`, `duel-uhc`, `duel-crystal`, `duel-smp`, `duel-diamond-pot` — crisp `64×36×64`, `playable-radius: 22`, zero void holes, mode-accurate builds like blast-proof bedrock Crystal pit & non-slippery Diamond Pot marble!).
- Automatically used by **Casual Player Duels (`/event duel`)**, **Admin 1v1 Duels (`/ce create duel`)**, and **`1v1` Tournaments**, keeping the 8 large grand arenas clean for FFA, Team Wars & `2v2/3v3/4v4` Team Tournaments!

### 🗳️ Interactive Voting GUIs + ⚡ Dynamic OP Admin Panel Wand + 🏰 Flagship Imperial Main Lobby
- **3 Interactive Voting GUIs**: `/event vote` & `/event modevote` open live GUIs with vote counts, `%` progress bars, and glowing `✔ YOUR VOTE` badges across **Mode Voting**, **Tournament Kit Voting (`/ce tournament kitvote`)**, and **Spectator Winner Predictions**!
- **Dynamic Operator Admin Wand**: Operators (`OP` / `clasherevents.admin`) automatically receive a glowing **`⚡ Admin Control Panel`** wand in Lobby / Tournament Hall (right-click opens `/ce gui`) — auto-appears within 1s on `/op`, auto-vanishes within 1s on `/deop`, and auto-strips during PvP combat!
- **Flagship Imperial Sky Citadel Main Lobby (`lobby.schem`, `116×58×116`)**: Rebuilt from the ground up with Twin Citadel Gate Towers, a 34-block Enchanted Diamond & Gold Sword Monument, lush Cherry Blossom & Oak Gardens, 4 Koi Ponds, 4 Void Waterfalls, Golden Crown Rotunda with Celestial Crystal, South Royal Observatory, East/West Hero Arches, 4 Floating Orbiting Sky Islets, and our mathematically verified 44-jump Sky Parkour!

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

1. Download **`clasher-events-2.5.0.jar`** from [Releases](https://github.com/Pranjal3125/clasher-events/releases).
2. Drop it into your Paper `26.2+` server's `plugins/` folder *(WorldEdit, WorldGuard, Hologram plugins, and PlaceholderAPI are all optional!)*.
3. Start the server and run:
   - **`/ce setup refresh`** — pastes the Flagship Imperial Lobby, Grand Tournament Hall, Winner Podium, 3D Holograms, all 8 Grand Arenas, and all 8 Dedicated 1v1 Duel Arenas!
   - **`/ce kit refresh-defaults`** — extracts the authentic competitive PvP kits (zero pre-applied potion effects, offhand shields/totems & full splash healing)!
4. Inspect with **`/ce tp lobby`**, **`/ce tp tournament-lobby`**, **`/ce tp duel-nethpot`**, **`/ce tp winner`**, or right-click your **`⚡ Admin Control Panel`** wand in Lobby!

## Commands

| Command | Description |
|---|---|
| `/ce setup [refresh]` | One-command setup: paste Imperial Lobby, Tournament Hall, Podium, 3D Holograms, 8 Grand & 8 Dedicated 1v1 Duel Arenas |
| `/ce create tournament <kit> [1v1\|2v2\|3v3\|4v4]` | Open a 1v1/2v2/3v3/4v4 Tournament in the Grand Tournament Hall |
| `/ce tournament [gui\|teams\|shuffle\|pair\|disband\|lock\|unlock\|bo3\|kitvote]` | Full admin tournament squad control panel & Tournament Kit Vote GUI |
| `/ce voteopen [sec]` / `/ce gui` / `/ce skip [sec\|now]` | Open Mode Voting GUI, Admin Control Panel, or skip timers |
| `/ce tp <lobby\|tournament-lobby\|winner\|crowd\|arena\|duel-arena> [phase]` | Teleport to any Lobby, Podium, Grand Arena, or 1v1 Duel Arena |
| `/event squad [invite\|accept\|decline\|leave\|kick\|name\|list]` | Form & manage your Tournament Squad (or open 1-click Squad Manager GUI) |
| `/event bracket` | Open the Live Tournament Bracket & Registered Squads Viewer GUI |
| `/event duel <player> [kit]` / `accept` / `decline` | Challenge anyone to a 1v1 in the 8 dedicated Duel Arenas |
| `/event vote` / `/event modevote` | Open interactive Voting GUI (Mode Vote, Tournament Kit Vote, or Winner Prediction) |
| `/event join` / `/event leave` / `/event kiteditor [kit]` | Join/leave events & customize personal kit layout GUI |
| `/event tc <msg>` *(or `@msg`)* / `/event team` | Private Team Wars / Tournament Squad chat & roster info |
| `/event stats [player]` / `/event top [elo\|wins\|kills\|points]` | Player profile lookup & interactive Leaderboard GUI |

## Build & Test

```bash
mvn clean package
```

Runs all **19 JUnit 5 tests** (parsing all 19 `.schem` files — 8 Grand Arenas, 8 Dedicated 1v1 Duel Arenas with zero void holes, Flagship Imperial Sky Citadel Lobby, Grand Esports Tournament Hall & Winner Podium — validating every Paper 26.2 block material, verifying all spawns & skyboxes, testing `TournamentSquadManager` invites/auto-fill/Bo3 Grand Finals, kits, Elo tiers, anti-escape bounds, and async persistence) and produces `target/clasher-events-2.5.0.jar`.
