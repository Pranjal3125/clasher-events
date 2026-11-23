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
    private static final List<String> VALID_PHASES = List.of("wave1", "wave2", "final", "audience");

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
        World world = getOrCreateSharedWorld(null);
        if (world == null) return out;
        for (Map<?, ?> row : arena.section().getMapList("spawns." + phase)) {
            Object x = row.get("x"), y = row.get("y"), z = row.get("z");
            if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
                float yaw = row.get("yaw") instanceof Number nyaw ? nyaw.floatValue() : 0.0f;
                float pitch = row.get("pitch") instanceof Number npitch ? npitch.floatValue() : 0.0f;
                out.add(new Location(world, nx.doubleValue(), ny.doubleValue(), nz.doubleValue(), yaw, pitch));
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
            player.sendMessage(FormatUtil.mm("<red>Phase must be wave1, wave2, final, or audience.</red>"));
            return;
        }
        Arena arena = arenas.get(id);
        if (arena == null) {
            player.sendMessage(FormatUtil.mm("<red>Unknown arena: " + FormatUtil.escape(arenaId) + ". Use /ce arena list.</red>"));
            return;
        }
        String sharedWorld = sharedWorldName();
        if (!player.getWorld().getName().equalsIgnoreCase(sharedWorld)) {
            player.sendMessage(FormatUtil.mm("<red>Stand inside the shared arena world '" + FormatUtil.escape(sharedWorld) + "' before setting this spawn.</red>"));
            return;
        }
        File file = new File(plugin.getDataFolder(), "arenas.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String path = "arenas." + id + ".spawns." + phaseKey;
        Location loc = player.getLocation();
        Map<String, Object> spawn = new LinkedHashMap<>();
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
            player.sendMessage(FormatUtil.mm("<green>" + (append ? "Added" : "Set") + " " + FormatUtil.escape(arena.displayName())
                    + " " + phaseKey + " spawn at "
                    + String.format(Locale.ROOT, "%.2f, %.2f, %.2f", loc.getX(), loc.getY(), loc.getZ()) + ".</green>"));
        } else {
            player.sendMessage(FormatUtil.mm("<red>Could not save arenas.yml. Check console for details.</red>"));
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
        for (String phaseName : VALID_PHASES) {
            if (spawns(arena, phaseName).isEmpty()) missing.add(phaseName + " spawn");
        }
        if (missing.isEmpty()) {
            World world = Bukkit.getWorld(sharedWorldName());
            boolean pasted = world != null && isArenaFloorPresent(world, arena);
            sender.sendMessage(FormatUtil.mm("<green>" + FormatUtil.escape(arena.displayName())
                    + " validation passed.</green> <gray>(Pasted in world: " + (pasted ? "<green>YES</green>" : "<yellow>NO — will auto-paste on event create</yellow>") + ")</gray>"));
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
