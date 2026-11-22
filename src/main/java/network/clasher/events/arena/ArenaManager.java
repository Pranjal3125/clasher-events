package network.clasher.events.arena;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import network.clasher.events.model.Arena;
import network.clasher.events.storage.YamlAtomicStorage;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages shared-world arena definitions, spawn editing, WorldEdit schematic pasting/backups,
 * entity cleanup, and optional WorldGuard region protection.
 */
public final class ArenaManager {
    private static final List<String> VALID_PHASES = List.of("wave1", "wave2", "final", "audience");

    private final JavaPlugin plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        arenas.clear();
        File file = new File(plugin.getDataFolder(), "arenas.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("arenas");
        if (root == null) return;
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
        if (world == null) world = new WorldCreator(name).createWorld();
        if (world == null && sender != null) {
            sender.sendMessage(FormatUtil.mm("<red>Could not load or create shared world '" + FormatUtil.escape(name) + "'.</red>"));
        }
        return world;
    }

    public List<Location> spawns(Arena arena, String phase) {
        List<Location> out = new ArrayList<>();
        if (arena == null) return out;
        World world = Bukkit.getWorld(sharedWorldName());
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
            player.sendMessage(FormatUtil.mm(append
                    ? "<gray>Spawn point added; existing points were kept.</gray>"
                    : "<gray>Previous spawn points for this phase were replaced.</gray>"));
        } else {
            player.sendMessage(FormatUtil.mm("<red>Could not save arenas.yml. Check console for details.</red>"));
        }
    }

    public boolean pasteArena(CommandSender sender, String id, boolean arenaInUse) {
        if (arenaInUse) {
            sender.sendMessage(FormatUtil.mm("<red>You cannot paste an arena while its event is open or running.</red>"));
            return false;
        }
        Arena arena = get(id);
        if (arena == null) {
            sender.sendMessage(FormatUtil.mm("<red>Unknown arena: " + FormatUtil.escape(id) + "</red>"));
            return false;
        }
        World world = getOrCreateSharedWorld(sender);
        if (world == null) return false;
        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) {
            sender.sendMessage(FormatUtil.mm("<red>WorldEdit is required for schematic paste/reset.</red>"));
            return false;
        }
        File source = new File(new File(plugin.getDataFolder(), "schematics"), arena.schematic());
        if (!source.isFile()) {
            sender.sendMessage(FormatUtil.mm("<red>Missing schematic: " + FormatUtil.escape(source.getPath()) + "</red>"));
            return false;
        }
        try {
            pasteClipboard(source, world, BlockVector3.at(arena.pasteX(), arena.pasteY(), arena.pasteZ()));
            clearArenaEntities(world, arena);
            File backup = arenaBackupFile(arena);
            if (!backup.exists()) captureArenaBackup(world, arena, backup);
            ensureWorldGuardRegion(world, arena);
            sender.sendMessage(FormatUtil.mm("<green>Pasted " + FormatUtil.escape(arena.displayName())
                    + ". Arena baseline backup " + (backup.exists() ? "is ready" : "could not be created") + ".</green>"));
            return true;
        } catch (Exception ex) {
            plugin.getLogger().severe("Schematic paste failed for " + id + ": " + ex.getMessage());
            sender.sendMessage(FormatUtil.mm("<red>Paste failed: " + FormatUtil.escape(ex.getMessage()) + "</red>"));
            return false;
        }
    }

    public boolean resetArena(CommandSender sender, String id, boolean arenaInUse) {
        if (arenaInUse) {
            sender.sendMessage(FormatUtil.mm("<red>You cannot reset an arena while its event is open or running.</red>"));
            return false;
        }
        Arena arena = get(id);
        if (arena == null) {
            sender.sendMessage(FormatUtil.mm("<red>Unknown arena: " + FormatUtil.escape(id) + "</red>"));
            return false;
        }
        File backup = arenaBackupFile(arena);
        if (!backup.isFile()) {
            sender.sendMessage(FormatUtil.mm("<yellow>No baseline backup exists yet; pasting starter schematic and creating one now.</yellow>"));
            return pasteArena(sender, id, false);
        }
        World world = getOrCreateSharedWorld(sender);
        if (world == null) return false;
        try {
            ClipboardFormat format = ClipboardFormats.findByPath(backup.toPath());
            if (format == null) throw new IllegalStateException("Unsupported backup schematic format");
            try (ClipboardReader reader = format.getReader(new FileInputStream(backup))) {
                Clipboard clipboard = reader.read();
                try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
                    Operation op = new ClipboardHolder(clipboard).createPaste(session)
                            .to(clipboard.getOrigin()).ignoreAirBlocks(false).build();
                    Operations.complete(op);
                }
            }
            clearArenaEntities(world, arena);
            sender.sendMessage(FormatUtil.mm("<green>Arena " + FormatUtil.escape(arena.displayName()) + " restored to its saved baseline.</green>"));
            return true;
        } catch (Exception ex) {
            plugin.getLogger().severe("Arena reset failed for " + id + ": " + ex.getMessage());
            sender.sendMessage(FormatUtil.mm("<red>Reset failed: " + FormatUtil.escape(ex.getMessage()) + "</red>"));
            return false;
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
        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) missing.add("WorldEdit");
        if (missing.isEmpty()) {
            sender.sendMessage(FormatUtil.mm("<green>" + FormatUtil.escape(arena.displayName()) + " validation passed.</green>"));
        } else {
            sender.sendMessage(FormatUtil.mm("<red>" + FormatUtil.escape(arena.displayName()) + " missing: " + FormatUtil.escape(String.join(", ", missing)) + "</red>"));
        }
    }

    public void ensureAllWorldGuardRegions() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            plugin.getLogger().warning("WorldGuard not found; arena region protection is disabled.");
            return;
        }
        World world = getOrCreateSharedWorld(null);
        if (world == null) {
            plugin.getLogger().warning("Could not load shared world for WorldGuard regions.");
            return;
        }
        for (Arena arena : arenas.values()) {
            ensureWorldGuardRegion(world, arena);
        }
    }

    public void setArenaBuildAllowed(String arenaId, boolean allowed) {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return;
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
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not update arena build flag: " + ex.getMessage());
        }
    }

    public void addArenaBuilder(String arenaId, UUID playerId) {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return;
        Arena arena = get(arenaId);
        World world = Bukkit.getWorld(sharedWorldName());
        if (arena == null || world == null) return;
        try {
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world));
            if (manager == null) return;
            ProtectedRegion region = manager.getRegion(arena.worldGuardRegionId());
            if (region != null) region.getMembers().addPlayer(playerId);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not add Build UHC member to region: " + ex.getMessage());
        }
    }

    private void ensureWorldGuardRegion(World world, Arena arena) {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return;
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionManager manager = container.get(BukkitAdapter.adapt(world));
            if (manager == null) return;
            ConfigurationSection b = arena.section().getConfigurationSection("bounds");
            if (b == null) return;
            String regionId = arena.worldGuardRegionId();
            manager.removeRegion(regionId);
            ProtectedCuboidRegion region = new ProtectedCuboidRegion(
                    regionId,
                    BlockVector3.at(b.getInt("min-x"), b.getInt("min-y"), b.getInt("min-z")),
                    BlockVector3.at(b.getInt("max-x"), b.getInt("max-y"), b.getInt("max-z"))
            );
            region.setPriority(20);
            region.setFlag(Flags.PVP, StateFlag.State.ALLOW);
            region.setFlag(Flags.BUILD, StateFlag.State.DENY);
            region.setFlag(Flags.CHEST_ACCESS, StateFlag.State.DENY);
            region.setFlag(Flags.TNT, StateFlag.State.DENY);
            manager.addRegion(region);
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not create WorldGuard region for " + arena.id() + ": " + ex.getMessage());
        }
    }

    private File arenaBackupFile(Arena arena) {
        File dir = new File(plugin.getDataFolder(), "arena-backups");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, arena.id().toLowerCase(Locale.ROOT) + ".schem");
    }

    private void pasteClipboard(File file, World world, BlockVector3 target) throws Exception {
        ClipboardFormat format = ClipboardFormats.findByPath(file.toPath());
        if (format == null) throw new IllegalStateException("Unsupported schematic format: " + file.getName());
        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            Clipboard clipboard = reader.read();
            try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
                Operation op = new ClipboardHolder(clipboard).createPaste(session)
                        .to(target).ignoreAirBlocks(false).build();
                Operations.complete(op);
            }
        }
    }

    private void captureArenaBackup(World world, Arena arena, File destination) throws Exception {
        ConfigurationSection b = arena.section().getConfigurationSection("bounds");
        if (b == null) throw new IllegalStateException("Arena bounds are not configured");
        BlockVector3 min = BlockVector3.at(b.getInt("min-x"), b.getInt("min-y"), b.getInt("min-z"));
        BlockVector3 max = BlockVector3.at(b.getInt("max-x"), b.getInt("max-y"), b.getInt("max-z"));
        CuboidRegion region = new CuboidRegion(BukkitAdapter.adapt(world), min, max);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(min);
        ForwardExtentCopy copy = new ForwardExtentCopy(BukkitAdapter.adapt(world), region, clipboard, min);
        Operations.complete(copy);
        File parent = destination.getParentFile();
        if (!parent.exists()) parent.mkdirs();
        File formatHint = new File(plugin.getDataFolder(), "schematics/nethpot.schem");
        ClipboardFormat format = ClipboardFormats.findByPath(formatHint.toPath());
        if (format == null) throw new IllegalStateException("Could not resolve Sponge schematic writer");
        try (ClipboardWriter writer = format.getWriter(new FileOutputStream(destination))) {
            writer.write(clipboard);
        }
    }

    private void clearArenaEntities(World world, Arena arena) {
        ConfigurationSection b = arena.section().getConfigurationSection("bounds");
        if (b == null) return;
        double minX = b.getDouble("min-x"), minY = b.getDouble("min-y"), minZ = b.getDouble("min-z");
        double maxX = b.getDouble("max-x"), maxY = b.getDouble("max-y"), maxZ = b.getDouble("max-z");
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
