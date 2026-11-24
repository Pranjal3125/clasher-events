package network.clasher.events.arena;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import network.clasher.events.ClasherEventsPlugin;
import network.clasher.events.model.Arena;
import network.clasher.events.storage.YamlAtomicStorage;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages shared-world void arena creation, spawn editing, zero-dependency Sponge v2
 * tick-batched schematic pasting & auto-pasting, entity cleanup, and optional WorldGuard protection.
 */
public final class ArenaManager {
    private static final List<String> VALID_PHASES = List.of("wave1", "wave2", "final", "audience", "waiting");
    private static final List<String> REQUIRED_PHASES = List.of("wave1", "wave2", "final", "audience");
    public static final int LOBBY_ORIGIN_X = -400;
    public static final int LOBBY_ORIGIN_Y = 100;
    public static final int LOBBY_ORIGIN_Z = 0;
    public static final int WINNER_ORIGIN_X = -600;
    public static final int WINNER_ORIGIN_Y = 100;
    public static final int WINNER_ORIGIN_Z = 0;

    private final JavaPlugin plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final File pastedMarkerFile;
    private YamlConfiguration pastedMarkers;

    public ArenaManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.pastedMarkerFile = new File(plugin.getDataFolder(), "pasted-arenas.yml");
    }

    public void load() {
        arenas.clear();
        File file = new File(plugin.getDataFolder(), "arenas.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("arenas");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) continue;
                arenas.put(id.toLowerCase(Locale.ROOT), new Arena(
                        id,
                        section.getString("display-name", id),
                        section.getString("schematic", id + ".schem"),
                        section.getInt("paste.x"),
                        section.getInt("paste.y"),
                        section.getInt("paste.z"),
                        section
                ));
            }
        }
        pastedMarkers = YamlConfiguration.loadConfiguration(pastedMarkerFile);
    }

    public Arena get(String id) {
        return id == null ? null : arenas.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean exists(String id) {
        return get(id) != null;
    }

    public Set<String> arenaIds() {
        return Collections.unmodifiableSet(arenas.keySet());
    }

    public String sharedWorldName() {
        return plugin.getConfig().getString("shared-world", "clasher_events");
    }

    public World getOrCreateSharedWorld(CommandSender sender) {
        String name = sharedWorldName();
        World world = Bukkit.getWorld(name);
        if (world == null) {
            WorldCreator creator = new WorldCreator(name);
            if (plugin.getConfig().getBoolean("use-void-world", true)) {
                creator.generator(new VoidChunkGenerator());
            }
            world = creator.createWorld();
            if (world != null) {
                configureArenaWorldRules(world);
            }
        }
        if (world == null && sender != null) {
            sender.sendMessage(FormatUtil.mm("<red>Could not load or create shared world '" + FormatUtil.escape(name) + "'.</red>"));
        }
        return world;
    }

    private void configureArenaWorldRules(World world) {
        try {
            world.setDifficulty(Difficulty.NORMAL);
            world.setAutoSave(true);
            world.setGameRule(GameRules.SPAWN_MOBS, false);
            world.setGameRule(GameRules.ADVANCE_WEATHER, false);
            world.setGameRule(GameRules.ADVANCE_TIME, false);
            world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
            world.setTime(6000L);
            world.setStorm(false);
        } catch (Throwable ignored) {
            // Safe fallback across minor API differences.
        }
    }

    public List<Location> spawns(Arena arena, String phase) {
        List<Location> out = new ArrayList<>();
        if (arena == null) return out;
        World defaultWorld = getOrCreateSharedWorld(null);
        if (defaultWorld == null) return out;
        for (Map<?, ?> row : arena.section().getMapList("spawns." + phase)) {
            Object x = row.get("x"), y = row.get("y"), z = row.get("z");
            if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
                World targetWorld = defaultWorld;
                Object wObj = row.get("world");
                if (wObj instanceof String wName && !wName.isBlank() && !wName.equalsIgnoreCase(defaultWorld.getName())) {
                    World custom = Bukkit.getWorld(wName);
                    if (custom != null) targetWorld = custom;
                }
                float yaw = row.get("yaw") instanceof Number nyaw ? nyaw.floatValue() : 0.0f;
                float pitch = row.get("pitch") instanceof Number npitch ? npitch.floatValue() : 0.0f;
                out.add(new Location(targetWorld, nx.doubleValue(), ny.doubleValue(), nz.doubleValue(), yaw, pitch));
            }
        }
        return out;
    }

    public Location firstSpawn(Arena arena, String phase) {
        List<Location> points = spawns(arena, phase);
        return points.isEmpty() ? null : points.get(0);
    }

    public void setSpawn(Player player, String arenaId, String phaseName, boolean append) {
        String id = arenaId.toLowerCase(Locale.ROOT);
        String phaseKey = phaseName.toLowerCase(Locale.ROOT);
        if (!VALID_PHASES.contains(phaseKey)) {
            player.sendMessage(FormatUtil.mm("<red>Phase must be wave1, wave2, final, audience, or waiting.</red>"));
            return;
        }
        Arena arena = arenas.get(id);
        if (arena == null) {
            player.sendMessage(FormatUtil.mm("<red>Unknown arena: " + FormatUtil.escape(arenaId) + ". Use /ce arena list.</red>"));
            return;
        }
        String sharedWorld = sharedWorldName();
        if (!phaseKey.equals("waiting") && !player.getWorld().getName().equalsIgnoreCase(sharedWorld)) {
            player.sendMessage(FormatUtil.mm("<red>Stand inside the shared arena world '" + FormatUtil.escape(sharedWorld) + "' before setting this spawn.</red>"));
            return;
        }
        File file = new File(plugin.getDataFolder(), "arenas.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String path = "arenas." + id + ".spawns." + phaseKey;
        Location loc = player.getLocation();
        Map<String, Object> spawn = new LinkedHashMap<>();
        if (phaseKey.equals("waiting") && !loc.getWorld().getName().equalsIgnoreCase(sharedWorld)) {
            spawn.put("world", loc.getWorld().getName());
        }
        spawn.put("x", loc.getX());
        spawn.put("y", loc.getY());
        spawn.put("z", loc.getZ());
        spawn.put("yaw", loc.getYaw());
        spawn.put("pitch", loc.getPitch());

        List<Map<?, ?>> points = append ? new ArrayList<>(yml.getMapList(path)) : new ArrayList<>();
        points.add(spawn);
        yml.set(path, points);

        if (YamlAtomicStorage.saveAtomically(yml, file, plugin.getLogger(), "Could not save arena spawn for " + id)) {
            load();
            player.sendMessage(FormatUtil.mm("<green>" + (append ? "Added" : "Set") + " <gold>" + FormatUtil.escape(arena.displayName())
                    + "</gold> <yellow>" + phaseKey + "</yellow> spawn at "
                    + String.format(Locale.ROOT, "%.2f, %.2f, %.2f", loc.getX(), loc.getY(), loc.getZ())
                    + " (" + FormatUtil.escape(loc.getWorld().getName()) + ").</green>"));
        } else {
            player.sendMessage(FormatUtil.mm("<red>Could not save arenas.yml. Check console for details.</red>"));
        }
    }

    public void clearWaitingSpawn(CommandSender sender, String arenaId) {
        String id = arenaId.toLowerCase(Locale.ROOT);
        Arena arena = arenas.get(id);
        if (arena == null) {
            sender.sendMessage(FormatUtil.mm("<red>Unknown arena: " + FormatUtil.escape(arenaId) + "</red>"));
            return;
        }
        File file = new File(plugin.getDataFolder(), "arenas.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        yml.set("arenas." + id + ".spawns.waiting", null);
        if (YamlAtomicStorage.saveAtomically(yml, file, plugin.getLogger(), "Could not clear waiting spawn for " + id)) {
            load();
            sender.sendMessage(FormatUtil.mm("<green>Cleared custom waiting lobby for <gold>" + FormatUtil.escape(arena.displayName())
                    + "</gold> — it will now fall back to the global Spawn Lobby.</green>"));
        }
    }

    /**
     * Checks whether the arena floor exists in the shared world; if missing and auto-paste is enabled,
     * pastes the bundled schematic asynchronously in tick-batched slices before invoking onReady.
     */
    public void ensureArenaReady(String arenaId, CommandSender feedback, Runnable onReady) {
        Arena arena = get(arenaId);
        if (arena == null) {
            if (onReady != null) onReady.run();
            return;
        }
        World world = getOrCreateSharedWorld(feedback);
        if (world == null) return;

        boolean autoPaste = plugin.getConfig().getBoolean("arenas.auto-paste-on-first-use", true);
        if (!autoPaste || isArenaFloorPresent(world, arena)) {
            ensureWorldGuardRegion(world, arena);
            if (onReady != null) onReady.run();
            return;
        }

        plugin.getLogger().info("Auto-pasting arena '" + arena.id() + "' from " + arena.schematic() + "...");
        if (feedback != null) {
            feedback.sendMessage(FormatUtil.mm("<yellow>Auto-pasting arena <gold>" + FormatUtil.escape(arena.displayName()) + "</gold> (zero-lag batched)...</yellow>"));
        }
        pasteArenaAsync(feedback, arena.id(), false, () -> {
            if (onReady != null) onReady.run();
        });
    }

    private boolean isArenaFloorPresent(World world, Arena arena) {
        if (pastedMarkers != null && pastedMarkers.getBoolean("pasted." + arena.id().toLowerCase(Locale.ROOT), false)) {
            Location first = firstSpawn(arena, "wave1");
            if (first == null) return true;
            Material below = world.getBlockAt(first.getBlockX(), first.getBlockY() - 1, first.getBlockZ()).getType();
            return !below.isAir();
        }
        Location first = firstSpawn(arena, "wave1");
        if (first == null) return false;
        Material below = world.getBlockAt(first.getBlockX(), first.getBlockY() - 1, first.getBlockZ()).getType();
        return !below.isAir();
    }

    public boolean pasteArena(CommandSender sender, String id, boolean arenaInUse) {
        return pasteArenaAsync(sender, id, arenaInUse, null);
    }

    public boolean pasteArenaAsync(CommandSender sender, String id, boolean arenaInUse, Runnable onComplete) {
        if (arenaInUse) {
            if (sender != null) sender.sendMessage(FormatUtil.mm("<red>You cannot paste an arena while its event is open or running.</red>"));
            return false;
        }
        Arena arena = get(id);
        if (arena == null) {
            if (sender != null) sender.sendMessage(FormatUtil.mm("<red>Unknown arena: " + FormatUtil.escape(id) + "</red>"));
            return false;
        }
        World world = getOrCreateSharedWorld(sender);
        if (world == null) return false;

        File source = new File(new File(plugin.getDataFolder(), "schematics"), arena.schematic());
        if (!source.isFile()) {
            if (sender != null) sender.sendMessage(FormatUtil.mm("<red>Missing schematic: " + FormatUtil.escape(source.getPath()) + "</red>"));
            return false;
        }

        int blocksPerTick = Math.max(500, plugin.getConfig().getInt("arenas.paste-blocks-per-tick", 2500));
        // Parse GZIP/NBT asynchronously off main tick thread, then apply tick-batched on main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                SpongeSchematicPaster.ParsedSchematic parsed = SpongeSchematicPaster.parse(source);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    SpongeSchematicPaster.pasteBatched(
                            plugin,
                            world,
                            parsed,
                            arena.pasteX(),
                            arena.pasteY(),
                            arena.pasteZ(),
                            blocksPerTick,
                            false,
                            () -> {
                                clearArenaEntities(world, arena);
                                ensureWorldGuardRegion(world, arena);
                                markPasted(arena.id());
                                if (sender != null) {
                                    sender.sendMessage(FormatUtil.mm("<green>Pasted <gold>" + FormatUtil.escape(arena.displayName())
                                            + "</gold> (" + parsed.nonAirBlockCount() + " blocks, zero-lag batched).</green>"));
                                }
                                if (onComplete != null) onComplete.run();
                            }
                    );
                });
            } catch (Exception ex) {
                plugin.getLogger().severe("Schematic paste failed for " + id + ": " + ex.getMessage());
                if (sender != null) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(FormatUtil.mm("<red>Paste failed for " + FormatUtil.escape(id) + ": " + FormatUtil.escape(ex.getMessage()) + "</red>")));
                }
            }
        });
        return true;
    }

    public void pasteAllArenas(CommandSender sender, java.util.function.Predicate<String> inUseCheck) {
        Deque<String> queue = new ArrayDeque<>();
        for (String id : arenas.keySet()) {
            if (!inUseCheck.test(id)) queue.addLast(id);
        }
        if (queue.isEmpty()) {
            if (sender != null) sender.sendMessage(FormatUtil.mm("<yellow>No idle arenas available to paste.</yellow>"));
            return;
        }
        if (sender != null) {
            sender.sendMessage(FormatUtil.mm("<yellow>Starting batched paste for <gold>" + queue.size() + "</gold> arenas...</yellow>"));
        }
        pasteNextInQueue(sender, queue);
    }

    private void pasteNextInQueue(CommandSender sender, Deque<String> queue) {
        String next = queue.pollFirst();
        if (next == null) {
            if (sender != null) sender.sendMessage(FormatUtil.mm("<green><bold>All arenas pasted and ready!</bold></green>"));
            return;
        }
        pasteArenaAsync(sender, next, false, () -> pasteNextInQueue(sender, queue));
    }

    public boolean resetArena(CommandSender sender, String id, boolean arenaInUse) {
        if (arenaInUse) {
            if (sender != null) sender.sendMessage(FormatUtil.mm("<red>You cannot reset an arena while its event is open or running.</red>"));
            return false;
        }
        return pasteArenaAsync(sender, id, false, null);
    }

    /**
     * Pastes a standalone structure schematic (such as lobby.schem or winner-podium.schem) tick-batched.
     */
    public void pasteStructureAsync(CommandSender sender, String schemFileName, String displayName, int ox, int oy, int oz, Runnable onComplete) {
        World world = getOrCreateSharedWorld(sender);
        if (world == null) return;
        File source = new File(new File(plugin.getDataFolder(), "schematics"), schemFileName);
        if (!source.isFile()) {
            if (sender != null) sender.sendMessage(FormatUtil.mm("<red>Missing schematic: " + FormatUtil.escape(source.getPath()) + "</red>"));
            if (onComplete != null) onComplete.run();
            return;
        }
        int blocksPerTick = Math.max(500, plugin.getConfig().getInt("arenas.paste-blocks-per-tick", 2500));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                SpongeSchematicPaster.ParsedSchematic parsed = SpongeSchematicPaster.parse(source);
                Bukkit.getScheduler().runTask(plugin, () -> SpongeSchematicPaster.pasteBatched(
                        plugin, world, parsed, ox, oy, oz, blocksPerTick, false, () -> {
                            markPasted(schemFileName.replace(".schem", ""));
                            if (sender != null) {
                                sender.sendMessage(FormatUtil.mm("<green>Pasted <gold>" + FormatUtil.escape(displayName)
                                        + "</gold> (" + parsed.nonAirBlockCount() + " blocks).</green>"));
                            }
                            if (onComplete != null) onComplete.run();
                        }
                ));
            } catch (Exception ex) {
                plugin.getLogger().severe("Structure paste failed for " + schemFileName + ": " + ex.getMessage());
                if (onComplete != null) Bukkit.getScheduler().runTask(plugin, onComplete);
            }
        });
    }

    public void ensureLobbyReady(Runnable onReady) {
        World world = getOrCreateSharedWorld(null);
        if (world == null) { if (onReady != null) onReady.run(); return; }
        Material floor = world.getBlockAt(LOBBY_ORIGIN_X, LOBBY_ORIGIN_Y + 1, LOBBY_ORIGIN_Z).getType();
        if (!floor.isAir()) {
            if (onReady != null) onReady.run();
            return;
        }
        pasteStructureAsync(null, "lobby.schem", "Grand Event Spawn Lobby", LOBBY_ORIGIN_X, LOBBY_ORIGIN_Y, LOBBY_ORIGIN_Z, onReady);
    }

    public void ensureWinnerPodiumReady(Runnable onReady) {
        World world = getOrCreateSharedWorld(null);
        if (world == null) { if (onReady != null) onReady.run(); return; }
        Material floor = world.getBlockAt(WINNER_ORIGIN_X, WINNER_ORIGIN_Y + 4, WINNER_ORIGIN_Z - 6).getType();
        if (!floor.isAir()) {
            if (onReady != null) onReady.run();
            return;
        }
        pasteStructureAsync(null, "winner-podium.schem", "Champions Winner Podium", WINNER_ORIGIN_X, WINNER_ORIGIN_Y, WINNER_ORIGIN_Z, onReady);
    }

    /**
     * One-command full turnkey setup (/ce setup [refresh]): creates void world, optionally refreshes
     * bundled v2.1 architectural schematics + arenas.yml, pastes Grand Event Spawn Lobby,
     * Champions Winner Podium, and all 8 PvP arenas, configures locations, and teleports admin to Lobby!
     */
    public void runFullSetup(CommandSender sender, java.util.function.Predicate<String> inUseCheck) {
        runFullSetup(sender, inUseCheck, false);
    }

    public void runFullSetup(CommandSender sender, java.util.function.Predicate<String> inUseCheck, boolean refreshBundled) {
        if (refreshBundled && plugin instanceof ClasherEventsPlugin cep) {
            int refreshed = cep.refreshBundledArenasAndSchematics();
            if (sender != null) {
                sender.sendMessage(FormatUtil.mm("<aqua>Refreshed <white>" + refreshed + "</white> bundled v2.2 architectural schematics & arenas.yml from JAR.</aqua>"));
            }
        }
        World world = getOrCreateSharedWorld(sender);
        if (world == null) return;

        // Save turnkey Lobby, Spectator, Winner Podium, & Crowd coordinates cleanly to config.yml
        String wn = world.getName();
        saveLocConfig("locations.lobby", wn, LOBBY_ORIGIN_X + 0.5, LOBBY_ORIGIN_Y + 2.0, LOBBY_ORIGIN_Z + 0.5, 180.0f, 0.0f);
        saveLocConfig("locations.spectator", wn, LOBBY_ORIGIN_X + 0.5, LOBBY_ORIGIN_Y + 2.0, LOBBY_ORIGIN_Z + 0.5, 180.0f, 0.0f);
        saveLocConfig("locations.winner", wn, WINNER_ORIGIN_X + 0.5, WINNER_ORIGIN_Y + 5.0, WINNER_ORIGIN_Z - 5.5, 0.0f, 0.0f);
        saveLocConfig("locations.winner-crowd", wn, WINNER_ORIGIN_X + 0.5, WINNER_ORIGIN_Y + 2.0, WINNER_ORIGIN_Z + 9.5, 180.0f, 0.0f);
        plugin.saveConfig();

        if (sender != null) {
            sender.sendMessage(FormatUtil.mm("<gold><bold>CLASHER EVENTS SETUP</bold></gold> <gray>— Pasting Sky Citadel Spawn Lobby, Winner Podium, and all 8 arenas with themed waiting skyboxes (zero-lag batched)...</gray>"));
            if (!refreshBundled) {
                sender.sendMessage(FormatUtil.mm("<gray>(Upgrading? Use <yellow>/ce setup refresh</yellow> to update bundled schematics & waiting skyboxes!)</gray>"));
            }
        }
        pasteStructureAsync(sender, "lobby.schem", "Grand Event Spawn Lobby", LOBBY_ORIGIN_X, LOBBY_ORIGIN_Y, LOBBY_ORIGIN_Z, () ->
                pasteStructureAsync(sender, "winner-podium.schem", "Champions Winner Podium", WINNER_ORIGIN_X, WINNER_ORIGIN_Y, WINNER_ORIGIN_Z, () -> {
                    pasteAllArenas(sender, inUseCheck);
                    if (sender instanceof Player admin && admin.isOnline()) {
                        admin.teleport(new Location(world, LOBBY_ORIGIN_X + 0.5, LOBBY_ORIGIN_Y + 2.0, LOBBY_ORIGIN_Z + 0.5, 180.0f, 0.0f));
                        admin.sendMessage(FormatUtil.mm("<green><bold>Welcome to your new Grand Event Spawn Lobby!</bold></green> <gray>Use <yellow>/ce tp winner</yellow>, <yellow>/ce tp crowd</yellow>, or <yellow>/ce tp <arena></yellow> to inspect everything!</gray>"));
                    }
                })
        );
    }

    private void saveLocConfig(String path, String world, double x, double y, double z, float yaw, float pitch) {
        plugin.getConfig().set(path + ".world", world);
        plugin.getConfig().set(path + ".x", x);
        plugin.getConfig().set(path + ".y", y);
        plugin.getConfig().set(path + ".z", z);
        plugin.getConfig().set(path + ".yaw", yaw);
        plugin.getConfig().set(path + ".pitch", pitch);
    }

    private Location resolveStructureLocation(World sharedWorld, String configKey, double defX, double defY, double defZ, float defYaw) {
        String path = "locations." + configKey;
        String wName = plugin.getConfig().getString(path + ".world", "");
        if (wName != null && wName.equalsIgnoreCase(sharedWorld.getName())) {
            return new Location(
                    sharedWorld,
                    plugin.getConfig().getDouble(path + ".x", defX),
                    plugin.getConfig().getDouble(path + ".y", defY),
                    plugin.getConfig().getDouble(path + ".z", defZ),
                    (float) plugin.getConfig().getDouble(path + ".yaw", defYaw),
                    (float) plugin.getConfig().getDouble(path + ".pitch", 0.0)
            );
        }
        return new Location(sharedWorld, defX, defY, defZ, defYaw, 0.0f);
    }

    /**
     * Teleports an admin directly to the Spawn Lobby, Winner Podium, Crowd Gallery, or any Arena (or its waiting/audience/wave spawn).
     */
    public void teleportAdminToArena(Player admin, String id) {
        teleportAdminToArena(admin, id, null);
    }

    public void teleportAdminToArena(Player admin, String id, String subPhase) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        World world = getOrCreateSharedWorld(admin);
        if (world == null) return;

        if (key.equals("lobby") || key.equals("spawn")) {
            ensureLobbyReady(() -> {
                Location loc = resolveStructureLocation(world, "lobby", LOBBY_ORIGIN_X + 0.5, LOBBY_ORIGIN_Y + 2.0, LOBBY_ORIGIN_Z + 0.5, 180.0f);
                admin.teleport(loc);
                admin.sendMessage(FormatUtil.mm("<green>Teleported to <gold>Grand Event Spawn Lobby</gold>!</green>"));
            });
            return;
        }
        if (key.equals("winner") || key.equals("podium")) {
            ensureWinnerPodiumReady(() -> {
                Location loc = resolveStructureLocation(world, "winner", WINNER_ORIGIN_X + 0.5, WINNER_ORIGIN_Y + 5.0, WINNER_ORIGIN_Z - 5.5, 0.0f);
                admin.teleport(loc);
                admin.sendMessage(FormatUtil.mm("<green>Teleported to <gold>Champions Winner Podium</gold>!</green>"));
            });
            return;
        }
        if (key.equals("crowd") || key.equals("winner-crowd")) {
            ensureWinnerPodiumReady(() -> {
                Location loc = resolveStructureLocation(world, "winner-crowd", WINNER_ORIGIN_X + 0.5, WINNER_ORIGIN_Y + 2.0, WINNER_ORIGIN_Z + 9.5, 180.0f);
                admin.teleport(loc);
                admin.sendMessage(FormatUtil.mm("<green>Teleported to <gold>Winner Hall Crowd Area</gold>!</green>"));
            });
            return;
        }

        Arena arena = get(id);
        if (arena == null) {
            admin.sendMessage(FormatUtil.mm("<red>Unknown target: " + FormatUtil.escape(id) + ". Try <yellow>lobby</yellow>, <yellow>winner</yellow>, or <yellow>/ce arena list</yellow>.</red>"));
            return;
        }
        String requestedPhase = subPhase == null ? "" : subPhase.trim().toLowerCase(Locale.ROOT);
        Location dest = null;
        String labelSuffix = "";
        if (!requestedPhase.isEmpty() && VALID_PHASES.contains(requestedPhase)) {
            dest = firstSpawn(arena, requestedPhase);
            if (dest != null) labelSuffix = " (" + requestedPhase + ")";
        }
        if (dest == null) dest = firstSpawn(arena, "audience");
        if (dest == null) dest = firstSpawn(arena, "wave1");
        if (dest == null) {
            dest = new Location(world, arena.centerX(), arena.pasteY() + 6.0, arena.centerZ());
        }
        admin.teleport(dest);
        boolean pasted = isArenaFloorPresent(world, arena);
        admin.sendMessage(FormatUtil.mm("<green>Teleported to <gold>" + FormatUtil.escape(arena.displayName() + labelSuffix) + "</gold></green>"
                + (pasted ? "" : " <yellow>(Not pasted yet — run <white>/ce arena paste " + FormatUtil.escape(arena.id()) + "</white> or <white>/ce setup refresh</white>)</yellow>")));
    }

    public void clearArenaEntities(String arenaId) {
        Arena arena = get(arenaId);
        World world = Bukkit.getWorld(sharedWorldName());
        if (arena != null && world != null) {
            clearArenaEntities(world, arena);
        }
    }

    public void validateArena(CommandSender sender, String id) {
        Arena arena = get(id);
        if (arena == null) {
            sender.sendMessage(FormatUtil.mm("<red>Unknown arena: " + FormatUtil.escape(id) + "</red>"));
            return;
        }
        File schematic = new File(new File(plugin.getDataFolder(), "schematics"), arena.schematic());
        List<String> missing = new ArrayList<>();
        if (!schematic.isFile()) missing.add("schematic file");
        for (String phaseName : REQUIRED_PHASES) {
            if (spawns(arena, phaseName).isEmpty()) missing.add(phaseName + " spawn");
        }
        if (missing.isEmpty()) {
            World world = Bukkit.getWorld(sharedWorldName());
            boolean pasted = world != null && isArenaFloorPresent(world, arena);
            boolean hasWaiting = !spawns(arena, "waiting").isEmpty();
            sender.sendMessage(FormatUtil.mm("<green>" + FormatUtil.escape(arena.displayName())
                    + " validation passed.</green> <gray>(Pasted: " + (pasted ? "<green>YES</green>" : "<yellow>NO</yellow>")
                    + ", Waiting Lobby: " + (hasWaiting ? "<green>PER-ARENA</green>" : "<aqua>GLOBAL</aqua>") + ")</gray>"));
        } else {
            sender.sendMessage(FormatUtil.mm("<red>" + FormatUtil.escape(arena.displayName()) + " missing: " + FormatUtil.escape(String.join(", ", missing)) + "</red>"));
        }
    }

    public void ensureAllWorldGuardRegions() {
        if (!hasWorldGuard()) {
            plugin.getLogger().info("WorldGuard not installed; using Clasher Events built-in arena protection.");
            return;
        }
        World world = getOrCreateSharedWorld(null);
        if (world == null) return;
        for (Arena arena : arenas.values()) {
            ensureWorldGuardRegion(world, arena);
        }
    }

    public void setArenaBuildAllowed(String arenaId, boolean allowed) {
        if (!hasWorldGuard()) return;
        Arena arena = get(arenaId);
        World world = Bukkit.getWorld(sharedWorldName());
        if (arena == null || world == null) return;
        try {
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world));
            if (manager == null) return;
            ProtectedRegion region = manager.getRegion(arena.worldGuardRegionId());
            if (region != null) {
                if (allowed) {
                    region.setFlag(Flags.BUILD, StateFlag.State.ALLOW);
                    region.setFlag(Flags.BUILD.getRegionGroupFlag(), RegionGroup.MEMBERS);
                } else {
                    region.setFlag(Flags.BUILD, StateFlag.State.DENY);
                    region.setFlag(Flags.BUILD.getRegionGroupFlag(), null);
                    region.getMembers().clear();
                }
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("Could not update WorldGuard build flag: " + ex.getMessage());
        }
    }

    public void addArenaBuilder(String arenaId, UUID playerId) {
        if (!hasWorldGuard()) return;
        Arena arena = get(arenaId);
        World world = Bukkit.getWorld(sharedWorldName());
        if (arena == null || world == null) return;
        try {
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world));
            if (manager == null) return;
            ProtectedRegion region = manager.getRegion(arena.worldGuardRegionId());
            if (region != null) region.getMembers().addPlayer(playerId);
        } catch (Throwable ex) {
            plugin.getLogger().warning("Could not add Build UHC member to WorldGuard region: " + ex.getMessage());
        }
    }

    private boolean hasWorldGuard() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard") != null
                && Bukkit.getPluginManager().getPlugin("WorldEdit") != null;
    }

    private void ensureWorldGuardRegion(World world, Arena arena) {
        if (!hasWorldGuard() || !arena.hasBounds()) return;
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionManager manager = container.get(BukkitAdapter.adapt(world));
            if (manager == null) return;
            String regionId = arena.worldGuardRegionId();
            manager.removeRegion(regionId);
            ProtectedCuboidRegion region = new ProtectedCuboidRegion(
                    regionId,
                    BlockVector3.at(arena.minX(), arena.minY(), arena.minZ()),
                    BlockVector3.at(arena.maxX(), arena.maxY(), arena.maxZ())
            );
            region.setPriority(20);
            region.setFlag(Flags.PVP, StateFlag.State.ALLOW);
            region.setFlag(Flags.BUILD, StateFlag.State.DENY);
            region.setFlag(Flags.CHEST_ACCESS, StateFlag.State.DENY);
            region.setFlag(Flags.TNT, StateFlag.State.DENY);
            manager.addRegion(region);
        } catch (Throwable ex) {
            plugin.getLogger().warning("Could not configure WorldGuard region for " + arena.id() + ": " + ex.getMessage());
        }
    }

    private void markPasted(String arenaId) {
        if (pastedMarkers == null) pastedMarkers = new YamlConfiguration();
        pastedMarkers.set("pasted." + arenaId.toLowerCase(Locale.ROOT), true);
        pastedMarkers.set("updated-at." + arenaId.toLowerCase(Locale.ROOT), System.currentTimeMillis());
        YamlAtomicStorage.saveAtomically(pastedMarkers, pastedMarkerFile, plugin.getLogger(), "Could not save pasted-arenas.yml");
    }

    private void clearArenaEntities(World world, Arena arena) {
        double minX = arena.minX(), minY = arena.minY(), minZ = arena.minZ();
        double maxX = arena.maxX(), maxY = arena.maxY(), maxZ = arena.maxZ();
        Location center = new Location(world, (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        for (Entity entity : world.getNearbyEntities(
                center,
                Math.max(1.0, (maxX - minX) / 2.0),
                Math.max(1.0, (maxY - minY) / 2.0),
                Math.max(1.0, (maxZ - minZ) / 2.0)
        )) {
            if (!(entity instanceof Player)) entity.remove();
        }
    }
}
