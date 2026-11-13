package network.clasher.events;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.entity.Projectile;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.Sound;
import org.bukkit.FireworkEffect;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.*;

public final class ClasherEventsPlugin extends JavaPlugin implements Listener, TabCompleter {
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<String, YamlConfiguration> kits = new LinkedHashMap<>();
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private String selectedArena = "nethpot";
    private String selectedKit = "nethpot";
    private String eventMode = "battle-royale";
    private boolean eventOpen;
    private boolean eventRunning;
    private boolean paused;
    private String phase = "idle";
    private int countdownRemaining;
    private int initialFighters;
    private BukkitTask countdownTask;
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, UUID> votes = new HashMap<>();
    private final Map<UUID, Integer> teams = new HashMap<>();
    private final Deque<UUID> tournamentQueue = new ArrayDeque<>();
    private final List<UUID> tournamentWinners = new ArrayList<>();
    private UUID tournamentPlayerA, tournamentPlayerB;
    private int tournamentRound = 1;
    private boolean winnerRewarded;
    private UUID eventWinner;
    private YamlConfiguration audiencePoints;
    private File audiencePointsFile;
    private YamlConfiguration recoveryData;
    private File recoveryFile;
    private static final String VOTE_GUI_TITLE = "§8Predict the Winner";
    private static final String EVENT_GUI_TITLE = "§8Clasher Events - Select Mode";
    private static final String KIT_GUI_PREFIX = "§8Kit Preview: ";
    private final Map<UUID, String> guiKitTargets = new HashMap<>();
    private final Map<UUID, BukkitTask> disconnectTasks = new HashMap<>();
    private final Set<UUID> disconnectedFighters = new HashSet<>();
    private final Set<UUID> kitDelivered = new HashSet<>();
    private final Map<UUID, UUID> lastDamager = new HashMap<>();
    private final Map<UUID, Long> lastCombatAt = new HashMap<>();
    private final Map<UUID, Long> lastMovementAt = new HashMap<>();
    private final Set<UUID> afkWarned = new HashSet<>();
    private YamlConfiguration playerStats;
    private File playerStatsFile;
    private BukkitTask statusTask;
    private final Map<UUID, Integer> matchKills = new HashMap<>();
    private YamlConfiguration matchHistory;
    private File matchHistoryFile;
    private long matchStartedAt;
    private static final String COUNTDOWN_GUI_TITLE = "§8Event Countdown";
    private static final String RULES_BOOK_TITLE = "§6Clasher Events Rules";

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("schematics/nethpot.schem", false);
        saveResource("schematics/axe-shield.schem", false);
        saveResource("schematics/build-uhc.schem", false);
        saveResource("schematics/sword.schem", false);
        saveResource("schematics/uhc.schem", false);
        saveResource("schematics/crystal.schem", false);
        saveResource("schematics/smp.schem", false);
        saveResource("schematics/diamond-pot.schem", false);
        loadAudiencePoints();
        loadRecoveryData();
        playerStatsFile = new File(getDataFolder(), "player-stats.yml");
        playerStats = YamlConfiguration.loadConfiguration(playerStatsFile);
        matchHistoryFile = new File(getDataFolder(), "match-history.yml");
        matchHistory = YamlConfiguration.loadConfiguration(matchHistoryFile);
        saveResource("arenas.yml", false);
        saveResource("kits/nethpot.yml", false);
        saveResource("kits/axe-shield.yml", false);
        saveResource("kits/build-uhc.yml", false);
        saveResource("kits/sword.yml", false);
        saveResource("kits/uhc.yml", false);
        saveResource("kits/crystal.yml", false);
        saveResource("kits/smp.yml", false);
        saveResource("kits/diamond-pot.yml", false);
        loadArenas(); loadKits();
        ensureWorldGuardRegions();
        Objects.requireNonNull(getCommand("ce")).setExecutor(this);
        Objects.requireNonNull(getCommand("ce")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("event")).setExecutor(this);
        Objects.requireNonNull(getCommand("event")).setTabCompleter(this);
        if (getCommand("latejoin") != null) { getCommand("latejoin").setExecutor(this); getCommand("latejoin").setTabCompleter(this); }
        Bukkit.getPluginManager().registerEvents(this, this);
        statusTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!eventRunning) return;
            long now = System.currentTimeMillis();
            long afkLimit = getConfig().getLong("afk.eliminate-after-seconds", 300) * 1000L;
            for (UUID fighterId : new HashSet<>(alive)) {
                Player fighter = Bukkit.getPlayer(fighterId);
                if (fighter == null) continue;
                long lastMove = lastMovementAt.getOrDefault(fighterId, now);
                long lastCombat = lastCombatAt.getOrDefault(fighterId, 0L);
                long lastActive = Math.max(lastMove, lastCombat);
                if (getConfig().getBoolean("afk.enabled", true) && now - lastActive >= afkLimit) {
                    alive.remove(fighterId); fighter.setGameMode(GameMode.SPECTATOR);
                    teleportToConfigured(fighter, "spectator");
                    fighter.sendMessage("§cYou were eliminated for being AFK.");
                    Bukkit.broadcastMessage("§7" + fighter.getName() + " was eliminated for being AFK.");
                    afkWarned.remove(fighterId);
                    processEliminationAfterDisconnect();
                } else if (getConfig().getBoolean("afk.enabled", true) && now - lastActive >= Math.max(60_000L, afkLimit - 60_000L) && afkWarned.add(fighterId)) {
                    fighter.sendMessage("§eAFK warning: move or fight within 60 seconds or you will be eliminated.");
                }
            }
            String status = "§6" + selectedArena.toUpperCase(Locale.ROOT) + " §8| §e" + alive.size() + " fighters §8| §a" + phase.toUpperCase(Locale.ROOT);
            for (UUID id : participants) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null) viewer.sendActionBar(status);
            }
        }, 40L, 40L);
        getLogger().info("Clasher Events enabled. Shared arena world: " + getConfig().getString("shared-world"));
    }

    @Override public void onDisable() {
        cancelCountdown();
        if (statusTask != null) { statusTask.cancel(); statusTask = null; }
        restoreAllPlayers();
    }

    private void loadAudiencePoints() {
        audiencePointsFile = new File(getDataFolder(), "audience-points.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        audiencePoints = YamlConfiguration.loadConfiguration(audiencePointsFile);
    }

    private void saveAudiencePoints() {
        try { audiencePoints.save(audiencePointsFile); }
        catch (java.io.IOException ex) { getLogger().severe("Could not save audience points: " + ex.getMessage()); }
    }

    private int points(UUID id) { return audiencePoints.getInt("players." + id + ".points", 0); }

    private void awardPredictionPoints(UUID winnerId) {
        for (Map.Entry<UUID, UUID> vote : votes.entrySet()) {
            if (winnerId.equals(vote.getValue())) {
                String path = "players." + vote.getKey() + ".points";
                audiencePoints.set(path, audiencePoints.getInt(path, 0) + getConfig().getInt("audience-correct-prediction-points", 10));
                String name = Bukkit.getOfflinePlayer(vote.getKey()).getName();
                audiencePoints.set("players." + vote.getKey() + ".name", name == null ? vote.getKey().toString() : name);
            }
        }
        saveAudiencePoints();
    }

    private void openVoteGui(Player viewer) {
        if (!participants.contains(viewer.getUniqueId()) || alive.contains(viewer.getUniqueId())) {
            viewer.sendMessage("§cOnly eliminated event spectators can predict the winner."); return;
        }
        Inventory gui = Bukkit.createInventory(null, 27, VOTE_GUI_TITLE);
        int slot = 10;
        for (UUID id : alive) {
            Player fighter = Bukkit.getPlayer(id);
            if (fighter == null) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(fighter);
                meta.setDisplayName("§e" + fighter.getName());
                meta.setLore(List.of("§7Click to predict this fighter", "§aCorrect prediction earns points"));
                head.setItemMeta(meta);
            }
            gui.setItem(slot, head);
            slot += 2;
            if (slot >= 17) break;
        }
        viewer.openInventory(gui);
    }

    private void openEventGui(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 27, EVENT_GUI_TITLE);
        String[] ids = {"nethpot", "axe-shield", "build-uhc", "sword", "uhc", "crystal", "smp", "diamond-pot", "team-wars", "duel", "random-kit", "tournament"};
        Material[] icons = {Material.NETHERITE_SWORD, Material.IRON_AXE, Material.GOLDEN_APPLE, Material.DIAMOND_SWORD,
                Material.GOLDEN_APPLE, Material.END_CRYSTAL, Material.SHIELD, Material.DIAMOND_CHESTPLATE,
                Material.RED_BANNER, Material.IRON_SWORD, Material.CHEST, Material.NETHER_STAR};
        for (int i = 0; i < ids.length; i++) {
            ItemStack item = new ItemStack(icons[i]);
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta != null) { meta.setDisplayName("§e" + ids[i].toUpperCase(Locale.ROOT)); meta.setLore(List.of("§7Click to create this event")); item.setItemMeta(meta); }
            gui.setItem(i, item);
        }
        ItemStack countdown = new ItemStack(Material.CLOCK);
        org.bukkit.inventory.meta.ItemMeta cm = countdown.getItemMeta();
        if (cm != null) { cm.setDisplayName("§eRegistration Countdown"); cm.setLore(List.of("§72 / 5 / 10 minutes")); countdown.setItemMeta(cm); }
        gui.setItem(18, countdown);
        ItemStack pause = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
        org.bukkit.inventory.meta.ItemMeta pm = pause.getItemMeta();
        if (pm != null) { pm.setDisplayName(paused ? "§aResume Event" : "§ePause Event"); pm.setLore(List.of("§7Toggle pause/resume")); pause.setItemMeta(pm); }
        gui.setItem(20, pause);
        ItemStack stop = new ItemStack(Material.BARRIER);
        org.bukkit.inventory.meta.ItemMeta sm = stop.getItemMeta();
        if (sm != null) { sm.setDisplayName("§cStop Event"); sm.setLore(List.of("§7Stop and restore all players")); stop.setItemMeta(sm); }
        gui.setItem(22, stop);
        ItemStack status = new ItemStack(Material.BOOK);
        org.bukkit.inventory.meta.ItemMeta stm = status.getItemMeta();
        if (stm != null) { stm.setDisplayName("§bEvent Status"); stm.setLore(List.of("§7Mode: " + eventMode, "§7Phase: " + phase, "§7Alive: " + alive.size())); status.setItemMeta(stm); }
        gui.setItem(24, status);
        admin.openInventory(gui);
    }

    private void openKitPreview(Player viewer, String kitId) {
        YamlConfiguration kit = kits.get(kitId.toLowerCase(Locale.ROOT));
        if (kit == null) { viewer.sendMessage("§cUnknown kit: " + kitId); return; }
        Inventory gui = Bukkit.createInventory(null, 54, KIT_GUI_PREFIX + kitId);
        ConfigurationSection armor = kit.getConfigurationSection("armor");
        if (armor != null) {
            gui.setItem(0, item(armor.getString("helmet")));
            gui.setItem(1, item(armor.getString("chestplate")));
            gui.setItem(2, item(armor.getString("leggings")));
            gui.setItem(3, item(armor.getString("boots")));
        }
        for (Map<?, ?> row : kit.getMapList("items")) {
            Object slot = row.get("slot"), material = row.get("material"), amount = row.get("amount");
            if (!(slot instanceof Number n) || !(material instanceof String name)) continue;
            ItemStack stack = item(name); if (stack == null) continue;
            if (amount instanceof Number a) stack.setAmount(Math.max(1, Math.min(64, a.intValue())));
            int targetSlot = Math.min(53, 9 + Math.max(0, Math.min(35, n.intValue())));
            gui.setItem(targetSlot, stack);
        }
        viewer.openInventory(gui);
    }

    @EventHandler public void onEventGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if ("§8Spectate Fighter".equals(title)) {
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getView().getTopInventory()) return;
            if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;
            String targetName = org.bukkit.ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null && player.getGameMode() == GameMode.SPECTATOR) {
                player.setSpectatorTarget(target);
                player.closeInventory();
            }
        } else if (COUNTDOWN_GUI_TITLE.equals(title)) {
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getView().getTopInventory()) return;
            if (!(player.hasPermission("clasherevents.admin"))) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            String display = org.bukkit.ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
            int seconds = display.startsWith("2") ? 120 : display.startsWith("5") ? 300 : display.startsWith("10") ? 600 : -1;
            if (seconds > 0 && eventOpen) {
                countdownRemaining = seconds;
                getConfig().set("default-registration-seconds", seconds);
                saveConfig();
                startCountdown();
                player.sendMessage("§aRegistration countdown set to " + (seconds / 60) + " minutes.");
                player.closeInventory();
            }
        } else if (EVENT_GUI_TITLE.equals(title)) {
            event.setCancelled(true);
            if (event.getClickedInventory() != event.getView().getTopInventory()) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            String mode = org.bukkit.ChatColor.stripColor(clicked.getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT);
            if (!player.hasPermission("clasherevents.admin")) return;
            player.closeInventory();
            if (mode.equals("registration countdown")) openCountdownGui(player);
            else if (mode.equals("pause event")) { paused = true; player.sendMessage("§eEvent paused."); }
            else if (mode.equals("resume event")) { paused = false; player.sendMessage("§aEvent resumed."); }
            else if (mode.equals("stop event")) stopEvent("Event stopped by admin");
            else if (mode.equals("event status")) player.sendMessage("§eMode: " + eventMode + " | Phase: " + phase + " | Alive: " + alive.size() + " | Registered: " + participants.size());
            else createEvent(mode);
        } else if (title.startsWith(KIT_GUI_PREFIX)) event.setCancelled(true);
    }

    @EventHandler public void onEventGuiDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (EVENT_GUI_TITLE.equals(title) || COUNTDOWN_GUI_TITLE.equals(title) || title.equals("§8Spectate Fighter") || title.startsWith(KIT_GUI_PREFIX) || VOTE_GUI_TITLE.equals(title))
            event.setCancelled(true);
    }

    @EventHandler public void onVoteGuiClick(InventoryClickEvent event) {
        if (!VOTE_GUI_TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD || !clicked.hasItemMeta()) return;
        String name = org.bukkit.ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (name != null && !name.isBlank()) { handleVote(viewer, name); viewer.closeInventory(); }
    }


    private Location configuredLocation(String key) {
        String path = "locations." + key;
        String worldName = getConfig().getString(path + ".world", getConfig().getString("shared-world", "clasher_events"));
        World world = Bukkit.getWorld(worldName);
        if (world == null) world = new WorldCreator(worldName).createWorld();
        if (world == null) return null;
        return new Location(world, getConfig().getDouble(path + ".x"), getConfig().getDouble(path + ".y"),
                getConfig().getDouble(path + ".z"), (float)getConfig().getDouble(path + ".yaw"),
                (float)getConfig().getDouble(path + ".pitch"));
    }

    private void teleportToConfigured(Player player, String key) {
        Location target = configuredLocation(key);
        if (target != null) player.teleport(target);
    }

    private void playCue(Player player, String cue) {
        String sound = getConfig().getString("sounds." + cue, "");
        if (sound == null || sound.isBlank()) return;
        try { player.playSound(player.getLocation(), Sound.valueOf(sound.toUpperCase(Locale.ROOT)), 0.65f, 1.0f); }
        catch (IllegalArgumentException ignored) { }
    }

    private void giveRulesBook(Player player) {
        if (!getConfig().getBoolean("rules-book.enabled", true)) return;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return;
        meta.setTitle(RULES_BOOK_TITLE);
        meta.setAuthor("Clasher Network");
        List<String> pages = getConfig().getStringList("rules-book.pages");
        if (pages.isEmpty()) pages = List.of(
                "§6§lCLASHER EVENTS\\n\\n§0Welcome! Read these rules before fighting.",
                "§6§lRULES\\n\\n§01. No teaming in solo modes.\\n2. No exploits or unfair play.\\n3. Follow staff instructions.",
                "§6§lEVENT INFO\\n\\n§0Your kit arrives when fighters are sent into the arena. Eliminated players become spectators."
        );
        for (String page : pages) meta.addPage(page.replace("&", "§"));
        book.setItemMeta(meta);
        player.getInventory().addItem(book);
        player.openBook(book);
        playCue(player, "rules-book");
    }

    private void removeRulesBooks(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item != null && item.getType() == Material.WRITTEN_BOOK && item.getItemMeta() instanceof BookMeta meta
                    && RULES_BOOK_TITLE.equals(meta.getTitle())) player.getInventory().setItem(slot, null);
        }
    }

    private void openCountdownGui(Player admin) {
        Inventory gui = Bukkit.createInventory(null, 27, COUNTDOWN_GUI_TITLE);
        int[] slots = {11, 13, 15};
        int[] minutes = {2, 5, 10};
        for (int i = 0; i < minutes.length; i++) {
            ItemStack item = new ItemStack(Material.CLOCK);
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e" + minutes[i] + " minutes");
                meta.setLore(List.of("§7Set registration countdown", "§aClick to apply"));
                item.setItemMeta(meta);
            }
            gui.setItem(slots[i], item);
        }
        admin.openInventory(gui);
    }

    private void forceLateJoin(CommandSender sender, String playerName) {
        if (!eventRunning || eventOpen) { sender.sendMessage("§cLate join is only available after the event starts."); return; }
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) { sender.sendMessage("§cPlayer must be online."); return; }
        UUID id = target.getUniqueId();
        if (participants.contains(id) || alive.contains(id)) { sender.sendMessage("§cPlayer is already in this event."); return; }
        if (disconnectedFighters.contains(id)) { sender.sendMessage("§cThis player has an active disconnect record."); return; }
        snapshotIfNeeded(target);
        participants.add(id); alive.add(id); lastMovementAt.put(id, System.currentTimeMillis());
        target.getInventory().clear();
        target.setGameMode(GameMode.SURVIVAL);
        Arena arena = arenas.get(selectedArena);
        List<Location> points = arena == null ? List.of() : spawns(arena, phase.equals("final") ? "final" : phase);
        if (points.isEmpty() && arena != null) points = spawns(arena, "wave1");
        if (points.isEmpty()) { participants.remove(id); alive.remove(id); sender.sendMessage("§cNo safe fighter spawn configured."); return; }
        target.teleport(points.get(new Random().nextInt(points.size())));
        target.setHealth(20); target.setFoodLevel(20); target.setFireTicks(0);
        removeRulesBooks(target);
        giveKit(target, selectedKit); kitDelivered.add(id);
        playCue(target, "kit");
        sender.sendMessage("§a" + target.getName() + " late-joined the event.");
        Bukkit.broadcastMessage("§e" + target.getName() + " joined the event late.");
    }

    private void setNamedLocation(Player player, String key) {
        String path = "locations." + key;
        Location l = player.getLocation();
        getConfig().set(path + ".world", l.getWorld().getName());
        getConfig().set(path + ".x", l.getX()); getConfig().set(path + ".y", l.getY());
        getConfig().set(path + ".z", l.getZ()); getConfig().set(path + ".yaw", l.getYaw());
        getConfig().set(path + ".pitch", l.getPitch());
        saveConfig();
        player.sendMessage("§a" + key + " location saved.");
    }

    private String arenaIdForMode(String mode) {
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "nethpot" -> "nethpot";
            case "axe", "axe-shield" -> "axe-shield";
            case "build-uhc" -> "build-uhc";
            case "sword", "duel", "tournament" -> "sword";
            case "uhc" -> "uhc";
            case "crystal" -> "crystal";
            case "smp", "team-wars", "teams" -> "smp";
            case "diamond-pot" -> "diamond-pot";
            default -> arenas.containsKey(mode.toLowerCase(Locale.ROOT)) ? mode.toLowerCase(Locale.ROOT) : null;
        };
    }

    private void setArenaSpawn(Player player, String arenaId, String phaseName, boolean append) {
        String id = arenaId.toLowerCase(Locale.ROOT);
        String phaseKey = phaseName.toLowerCase(Locale.ROOT);
        if (!List.of("wave1", "wave2", "final", "audience").contains(phaseKey)) {
            player.sendMessage("§cPhase must be wave1, wave2, final, or audience.");
            return;
        }
        Arena arena = arenas.get(id);
        if (arena == null) {
            player.sendMessage("§cUnknown arena: " + arenaId + ". Use /ce arena list.");
            return;
        }
        String sharedWorld = getConfig().getString("shared-world", "clasher_events");
        if (!player.getWorld().getName().equalsIgnoreCase(sharedWorld)) {
            player.sendMessage("§cStand inside the shared arena world '" + sharedWorld + "' before setting this spawn.");
            return;
        }
        File file = new File(getDataFolder(), "arenas.yml");
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
        try {
            yml.save(file);
            loadArenas();
            player.sendMessage("§a" + (append ? "Added" : "Set") + " " + arena.displayName + " " + phaseKey + " spawn at "
                    + String.format(Locale.ROOT, "%.2f, %.2f, %.2f", loc.getX(), loc.getY(), loc.getZ()) + ".");
            player.sendMessage(append ? "§7Spawn point added; existing points were kept." : "§7Previous spawn points for this phase were replaced.");
        } catch (IOException ex) {
            player.sendMessage("§cCould not save arena spawn: " + ex.getMessage());
            getLogger().severe("Could not save arena spawn for " + id + ": " + ex.getMessage());
        }
    }

    private boolean inWaitingLobby(Player p) {
        if (eventOpen && participants.contains(p.getUniqueId()) && !alive.contains(p.getUniqueId())) return true;
        String worldName = getConfig().getString("locations.lobby.world", getConfig().getString("shared-world", "clasher_events"));
        World lobbyWorld = Bukkit.getWorld(worldName);
        if (lobbyWorld == null || !p.getWorld().equals(lobbyWorld)) return false;
        double dx = p.getLocation().getX() - getConfig().getDouble("locations.lobby.x", 0.5);
        double dy = p.getLocation().getY() - getConfig().getDouble("locations.lobby.y", 100.0);
        double dz = p.getLocation().getZ() - getConfig().getDouble("locations.lobby.z", 0.5);
        double radius = getConfig().getDouble("lobby-protection.radius", 35);
        return dx*dx + dy*dy + dz*dz <= radius*radius;
    }

    private void loadArenas() {
        arenas.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "arenas.yml"));
        ConfigurationSection root = yml.getConfigurationSection("arenas");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            arenas.put(id.toLowerCase(Locale.ROOT), new Arena(id, s.getString("display-name", id), s.getString("schematic", id + ".schem"),
                    s.getInt("paste.x"), s.getInt("paste.y"), s.getInt("paste.z"), s));
        }
    }

    private void loadKits() {
        kits.clear(); File dir = new File(getDataFolder(), "kits");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) kits.put(file.getName().replace(".yml", "").toLowerCase(Locale.ROOT), YamlConfiguration.loadConfiguration(file));
    }

    private boolean pasteArena(CommandSender sender, String id) {
        if ((eventOpen || eventRunning) && selectedArena.equalsIgnoreCase(id)) {
            sender.sendMessage("§cYou cannot paste an arena while its event is open or running."); return false;
        }
        Arena arena = arenas.get(id.toLowerCase(Locale.ROOT));
        if (arena == null) { sender.sendMessage("§cUnknown arena: " + id); return false; }
        World world = getSharedWorld(sender);
        if (world == null) return false;
        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) { sender.sendMessage("§cWorldEdit is required for schematic paste/reset."); return false; }
        File source = new File(new File(getDataFolder(), "schematics"), arena.schematic);
        if (!source.isFile()) { sender.sendMessage("§cMissing schematic: " + source.getPath()); return false; }
        try {
            pasteClipboard(source, world, BlockVector3.at(arena.pasteX, arena.pasteY, arena.pasteZ));
            clearArenaEntities(world, arena);
            File backup = arenaBackupFile(arena);
            if (!backup.exists()) captureArenaBackup(world, arena, backup);
            ensureWorldGuardRegion(world, arena);
            sender.sendMessage("§aPasted " + arena.displayName + ". Arena baseline backup " + (backup.exists() ? "is ready" : "could not be created") + ".");
            return true;
        } catch (Exception ex) {
            getLogger().severe("Schematic paste failed for " + id + ": " + ex.getMessage());
            sender.sendMessage("§cPaste failed: " + ex.getMessage()); return false;
        }
    }

    private boolean resetArena(CommandSender sender, String id) {
        if ((eventOpen || eventRunning) && selectedArena.equalsIgnoreCase(id)) {
            sender.sendMessage("§cYou cannot reset an arena while its event is open or running."); return false;
        }
        Arena arena = arenas.get(id.toLowerCase(Locale.ROOT));
        if (arena == null) { sender.sendMessage("§cUnknown arena: " + id); return false; }
        File backup = arenaBackupFile(arena);
        if (!backup.isFile()) {
            sender.sendMessage("§eNo baseline backup exists yet; pasting the starter schematic and creating one now.");
            return pasteArena(sender, id);
        }
        World world = getSharedWorld(sender);
        if (world == null) return false;
        try {
            ClipboardFormat format = ClipboardFormats.findByFile(backup);
            if (format == null) throw new IllegalStateException("Unsupported backup schematic format");
            try (ClipboardReader reader = format.getReader(new FileInputStream(backup))) {
                Clipboard clipboard = reader.read();
                try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
                    Operation operation = new ClipboardHolder(clipboard).createPaste(session)
                            .to(clipboard.getOrigin()).ignoreAirBlocks(false).build();
                    Operations.complete(operation);
                }
            }
            clearArenaEntities(world, arena);
            sender.sendMessage("§aArena " + arena.displayName + " restored to its saved baseline.");
            return true;
        } catch (Exception ex) {
            getLogger().severe("Arena reset failed for " + id + ": " + ex.getMessage());
            sender.sendMessage("§cReset failed: " + ex.getMessage()); return false;
        }
    }

    private World getSharedWorld(CommandSender sender) {
        String worldName = getConfig().getString("shared-world", "clasher_events");
        World world = Bukkit.getWorld(worldName);
        if (world == null) world = new WorldCreator(worldName).createWorld();
        if (world == null) sender.sendMessage("§cCould not load/create shared world '" + worldName + "'.");
        return world;
    }

    private File arenaBackupFile(Arena arena) {
        File directory = new File(getDataFolder(), "arena-backups");
        if (!directory.exists()) directory.mkdirs();
        return new File(directory, arena.id.toLowerCase(Locale.ROOT) + ".schem");
    }

    private void pasteClipboard(File file, World world, BlockVector3 target) throws Exception {
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) throw new IllegalStateException("Unsupported schematic format: " + file.getName());
        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            Clipboard clipboard = reader.read();
            try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
                Operation operation = new ClipboardHolder(clipboard).createPaste(session)
                        .to(target).ignoreAirBlocks(false).build();
                Operations.complete(operation);
            }
        }
    }

    private void captureArenaBackup(World world, Arena arena, File destination) throws Exception {
        ConfigurationSection b = arena.section.getConfigurationSection("bounds");
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
        File formatHint = new File(getDataFolder(), "schematics/nethpot.schem");
        ClipboardFormat format = ClipboardFormats.findByFile(formatHint);
        if (format == null) throw new IllegalStateException("Could not resolve Sponge schematic writer");
        try (ClipboardWriter writer = format.getWriter(new FileOutputStream(destination))) { writer.write(clipboard); }
    }

    private void clearArenaEntities(World world, Arena arena) {
        ConfigurationSection b = arena.section.getConfigurationSection("bounds");
        if (b == null) return;
        double minX = b.getDouble("min-x"), minY = b.getDouble("min-y"), minZ = b.getDouble("min-z");
        double maxX = b.getDouble("max-x"), maxY = b.getDouble("max-y"), maxZ = b.getDouble("max-z");
        Location center = new Location(world, (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(center,
                Math.max(1, (maxX-minX)/2.0), Math.max(1, (maxY-minY)/2.0), Math.max(1, (maxZ-minZ)/2.0))) {
            if (!(entity instanceof Player)) entity.remove();
        }
    }

    private void ensureWorldGuardRegions() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            getLogger().warning("WorldGuard not found; arena region protection is disabled.");
            return;
        }
        String worldName = getConfig().getString("shared-world", "clasher_events");
        World world = Bukkit.getWorld(worldName);
        if (world == null) world = new WorldCreator(worldName).createWorld();
        if (world == null) { getLogger().warning("Could not load shared world for WorldGuard regions."); return; }
        for (Arena arena : arenas.values()) ensureWorldGuardRegion(world, arena);
    }

    private void ensureWorldGuardRegion(World world, Arena arena) {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return;
        try {
            com.sk89q.worldguard.protection.regions.RegionContainer container =
                    com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer();
            com.sk89q.worldguard.protection.managers.RegionManager manager =
                    container.get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world));
            if (manager == null) { getLogger().warning("WorldGuard has no region manager for " + world.getName()); return; }
            ConfigurationSection b = arena.section.getConfigurationSection("bounds");
            if (b == null) { getLogger().warning("No bounds configured for arena " + arena.id); return; }
            String id = "ce_" + arena.id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            manager.removeRegion(id);
            com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion region =
                    new com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion(id,
                    BlockVector3.at(b.getInt("min-x"), b.getInt("min-y"), b.getInt("min-z")),
                    BlockVector3.at(b.getInt("max-x"), b.getInt("max-y"), b.getInt("max-z")));
            region.setPriority(20);
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.PVP, com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW);
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.BUILD, com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.CHEST_ACCESS, com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);
            region.setFlag(com.sk89q.worldguard.protection.flags.Flags.TNT, com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);
            manager.addRegion(region);
        } catch (Exception ex) {
            getLogger().severe("Could not create WorldGuard region for " + arena.id + ": " + ex.getMessage());
        }
    }

    private void setArenaBuildAllowed(boolean allowed) {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return;
        World world = Bukkit.getWorld(getConfig().getString("shared-world", "clasher_events"));
        if (world == null) return;
        try {
            com.sk89q.worldguard.protection.managers.RegionManager manager =
                    com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer()
                            .get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world));
            if (manager == null) return;
            com.sk89q.worldguard.protection.regions.ProtectedRegion region = manager.getRegion(
                    "ce_" + selectedArena.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_"));
            if (region != null) {
                if (allowed) {
                    region.setFlag(com.sk89q.worldguard.protection.flags.Flags.BUILD,
                            com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW);
                    region.setFlag(com.sk89q.worldguard.protection.flags.Flags.BUILD.getRegionGroupFlag(),
                            com.sk89q.worldguard.protection.flags.RegionGroup.MEMBERS);
                } else {
                    region.setFlag(com.sk89q.worldguard.protection.flags.Flags.BUILD,
                            com.sk89q.worldguard.protection.flags.StateFlag.State.DENY);
                    region.setFlag(com.sk89q.worldguard.protection.flags.Flags.BUILD.getRegionGroupFlag(), null);
                    region.getMembers().clear();
                }
            }
        } catch (Exception ex) { getLogger().warning("Could not update arena build flag: " + ex.getMessage()); }
    }

    private void addArenaBuilder(UUID playerId) {
        if (!eventMode.equals("build-uhc") || Bukkit.getPluginManager().getPlugin("WorldGuard") == null) return;
        World world = Bukkit.getWorld(getConfig().getString("shared-world", "clasher_events"));
        if (world == null) return;
        try {
            com.sk89q.worldguard.protection.managers.RegionManager manager =
                    com.sk89q.worldguard.WorldGuard.getInstance().getPlatform().getRegionContainer()
                            .get(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world));
            if (manager == null) return;
            com.sk89q.worldguard.protection.regions.ProtectedRegion region = manager.getRegion(
                    "ce_" + selectedArena.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_"));
            if (region != null) region.getMembers().addPlayer(playerId);
        } catch (Exception ex) { getLogger().warning("Could not add Build UHC member to region: " + ex.getMessage()); }
    }

    private Location location(Arena arena, String path) {
        List<Location> configured = spawns(arena, path);
        return configured.isEmpty() ? null : configured.get(0);
    }

    private List<Location> spawns(Arena arena, String phase) {
        List<Location> out = new ArrayList<>();
        List<Map<?, ?>> rows = arena.section.getMapList("spawns." + phase);
        World world = Bukkit.getWorld(getConfig().getString("shared-world", "clasher_events"));
        if (world == null) return out;
        for (Map<?, ?> row : rows) {
            Object x = row.get("x"), y = row.get("y"), z = row.get("z");
            if (x instanceof Number && y instanceof Number && z instanceof Number)
                out.add(new Location(world, ((Number)x).doubleValue(), ((Number)y).doubleValue(), ((Number)z).doubleValue(),
                        row.get("yaw") instanceof Number ? ((Number)row.get("yaw")).floatValue() : 0.0f,
                        row.get("pitch") instanceof Number ? ((Number)row.get("pitch")).floatValue() : 0.0f));
        }
        return out;
    }

    private void giveKit(Player player, String kitId) {
        YamlConfiguration kit = kits.get(kitId.toLowerCase(Locale.ROOT));
        if (kit == null) { player.sendMessage("§cKit not found: " + kitId); return; }
        player.getInventory().clear(); player.getInventory().setArmorContents(new ItemStack[4]);
        ConfigurationSection armor = kit.getConfigurationSection("armor");
        if (armor != null) {
            player.getInventory().setHelmet(item(armor.getString("helmet")));
            player.getInventory().setChestplate(item(armor.getString("chestplate")));
            player.getInventory().setLeggings(item(armor.getString("leggings")));
            player.getInventory().setBoots(item(armor.getString("boots")));
            ConfigurationSection armorEnchants = kit.getConfigurationSection("enchantments");
            if (armorEnchants != null) {
                for (ItemStack piece : player.getInventory().getArmorContents()) {
                    if (piece == null) continue;
                    for (String key : armorEnchants.getKeys(false)) {
                        Enchantment enchant = Enchantment.getByKey(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
                        if (enchant != null) piece.addUnsafeEnchantment(enchant, armorEnchants.getInt(key));
                    }
                }
            }
        }
        for (Map<?, ?> row : kit.getMapList("items")) {
            Object slot = row.get("slot"), material = row.get("material"), amount = row.get("amount");
            if (!(slot instanceof Number) || !(material instanceof String)) continue;
            ItemStack stack = item((String) material);
            if (stack == null) continue;
            if (amount instanceof Number) stack.setAmount(Math.max(1, Math.min(64, ((Number)amount).intValue())));
            if (row.get("potion") instanceof String potionName && stack.getItemMeta() instanceof PotionMeta potionMeta) {
                try { potionMeta.setBasePotionType(PotionType.valueOf(potionName)); stack.setItemMeta(potionMeta); } catch (IllegalArgumentException ignored) { }
            }
            Object enchants = row.get("enchants");
            if (enchants instanceof Map<?, ?> map) for (Map.Entry<?, ?> e : map.entrySet()) {
                Enchantment enchant = Enchantment.getByKey(NamespacedKey.minecraft(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT)));
                if (enchant != null && e.getValue() instanceof Number n) stack.addUnsafeEnchantment(enchant, n.intValue());
            }
            int index = ((Number)slot).intValue(); if (index >= 0 && index < 36) player.getInventory().setItem(index, stack);
        }
        for (Map<?, ?> row : kit.getMapList("effects")) {
            PotionEffectType type = PotionEffectType.getByName(String.valueOf(row.get("type")));
            if (type != null) player.addPotionEffect(new PotionEffect(type, row.get("duration") instanceof Number d ? Math.max(20, d.intValue()) : 20 * 60 * 10, row.get("amplifier") instanceof Number n ? n.intValue() : 0));
        }
        player.updateInventory();
    }

    private ItemStack item(String name) {
        if (name == null) return null;
        Material material = Material.matchMaterial(name);
        return material == null ? null : new ItemStack(material);
    }

    private void createEvent(String mode) {
        stopEvent("Replaced by a new event");
        eventMode = mode.equalsIgnoreCase("teams") ? "team-wars" : mode.toLowerCase(Locale.ROOT);
        selectedArena = switch (eventMode) {
            case "axe", "axe-shield" -> "axe-shield"; case "build-uhc" -> "build-uhc"; case "sword", "duel" -> "sword";
            case "uhc" -> "uhc"; case "team-wars", "teams" -> "smp"; case "crystal" -> "crystal"; case "smp" -> "smp"; case "diamond-pot" -> "diamond-pot";
            case "random-kit" -> new ArrayList<>(arenas.keySet()).get(new Random().nextInt(arenas.size()));
            case "nethpot" -> "nethpot";
            case "tournament" -> "sword";
            default -> null;
        };
        if (selectedArena == null) { Bukkit.broadcastMessage("§cUnknown event mode: " + eventMode); return; }
        selectedKit = selectedArena;
        if (!arenas.containsKey(selectedArena) || !kits.containsKey(selectedKit)) {
            Bukkit.broadcastMessage("§c[Clasher Events] Missing arena or kit configuration for " + selectedArena);
            return;
        }
        setArenaBuildAllowed(eventMode.equals("build-uhc"));
        winnerRewarded = false;
        matchKills.clear(); matchStartedAt = System.currentTimeMillis();
        eventOpen = true; eventRunning = false; paused = false; phase = "registration";
        participants.clear(); alive.clear(); votes.clear(); snapshots.clear(); kitDelivered.clear(); disconnectedFighters.clear();
        for (BukkitTask task : disconnectTasks.values()) task.cancel(); disconnectTasks.clear();
        countdownRemaining = getConfig().getInt("default-registration-seconds", 120);
        Bukkit.broadcastMessage("§6§lCLASHER EVENTS §8» §e" + selectedArena + " registration is open for " + countdownRemaining + " seconds! Use §a/event join§e.");
        startCountdown();
    }

    private void startCountdown() {
        cancelCountdown();
        countdownTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!eventOpen || paused) return;
            int kitAt = getConfig().getInt("kit-delivery-seconds-before-start", 60);
            if (countdownRemaining == kitAt) {
                for (UUID id : participants) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        snapshotIfNeeded(p);
                        Arena stagingArena = arenas.get(selectedArena);
                        List<Location> staging = stagingArena == null ? List.of() : spawns(stagingArena, "wave1");
                        if (!staging.isEmpty()) p.teleport(staging.get(new Random().nextInt(staging.size())));
                        removeRulesBooks(p); giveKit(p, selectedKit); kitDelivered.add(id); playCue(p, "kit");
                        p.sendMessage("§aYour event kit has been delivered. Combat begins when the countdown ends.");
                    }
                }
                Bukkit.broadcastMessage("§6[Clasher Events] §aFighter kits delivered.");
            }
            if (countdownRemaining <= 0) {
                cancelCountdown();
                startEvent();
                return;
            }
            if (countdownRemaining <= 10 || countdownRemaining % 30 == 0)
                Bukkit.broadcastMessage("§6[Clasher Events] §eEvent starts in " + countdownRemaining + " seconds.");
            countdownRemaining--;
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
    }

    private void snapshotIfNeeded(Player p) {
        PlayerSnapshot snapshot = snapshots.computeIfAbsent(p.getUniqueId(), id -> new PlayerSnapshot(p));
        persistSnapshot(p.getUniqueId(), snapshot);
    }

    private void loadRecoveryData() {
        recoveryFile = new File(getDataFolder(), "player-recovery.yml");
        recoveryData = YamlConfiguration.loadConfiguration(recoveryFile);
    }

    private void persistSnapshot(UUID id, PlayerSnapshot snapshot) {
        String path = "players." + id;
        recoveryData.set(path + ".storage", Arrays.asList(snapshot.storage));
        recoveryData.set(path + ".armor", Arrays.asList(snapshot.armor));
        recoveryData.set(path + ".offhand", snapshot.offhand);
        recoveryData.set(path + ".gamemode", snapshot.mode.name());
        recoveryData.set(path + ".health", snapshot.health);
        recoveryData.set(path + ".food", snapshot.food);
        recoveryData.set(path + ".level", snapshot.level);
        recoveryData.set(path + ".exp", snapshot.exp);
        recoveryData.set(path + ".saturation", snapshot.saturation);
        recoveryData.set(path + ".fire", snapshot.fire);
        recoveryData.set(path + ".world", snapshot.location.getWorld() == null ? "" : snapshot.location.getWorld().getName());
        recoveryData.set(path + ".x", snapshot.location.getX()); recoveryData.set(path + ".y", snapshot.location.getY());
        recoveryData.set(path + ".z", snapshot.location.getZ()); recoveryData.set(path + ".yaw", snapshot.location.getYaw());
        recoveryData.set(path + ".pitch", snapshot.location.getPitch());
        List<Map<String, Object>> effects = new ArrayList<>();
        for (PotionEffect effect : snapshot.effects) {
            Map<String, Object> row = new HashMap<>(); row.put("type", effect.getType().getName());
            row.put("duration", effect.getDuration()); row.put("amplifier", effect.getAmplifier());
            row.put("ambient", effect.isAmbient()); row.put("particles", effect.hasParticles()); row.put("icon", effect.hasIcon());
            effects.add(row);
        }
        recoveryData.set(path + ".effects", effects);
        saveRecoveryData();
    }

    private void saveRecoveryData() {
        try { recoveryData.save(recoveryFile); }
        catch (java.io.IOException ex) { getLogger().severe("Could not save player recovery data: " + ex.getMessage()); }
    }

    private void removePersistedSnapshot(UUID id) {
        if (recoveryData == null) return;
        recoveryData.set("players." + id, null); saveRecoveryData();
    }

    private PlayerSnapshot loadPersistedSnapshot(UUID id) {
        ConfigurationSection section = recoveryData.getConfigurationSection("players." + id);
        if (section == null) return null;
        return PlayerSnapshot.from(section);
    }

    private void restoreAllPlayers() {
        for (Map.Entry<UUID, PlayerSnapshot> entry : new HashMap<>(snapshots).entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            // A dead player cannot safely have health/inventory restored until respawn completes.
            if (p != null && !p.isDead()) {
                clearPlayerBorder(p);
                Location destination = eventWinner != null && eventWinner.equals(entry.getKey())
                        ? configuredLocation("winner") : lobbyLocation();
                entry.getValue().restore(p, destination == null ? lobbyLocation() : destination);
                snapshots.remove(entry.getKey());
                removePersistedSnapshot(entry.getKey());
            }
        }
    }

    private Location lobbyLocation() {
        Location configured = configuredLocation("lobby");
        if (configured != null) return configured;
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private void applyPhaseBorder(String phaseName) {
        Arena arena = arenas.get(selectedArena);
        if (arena == null) return;
        double size = getConfig().getDouble("world-border." + phaseName + ".size", phaseName.equals("wave1") ? 140 : phaseName.equals("wave2") ? 80 : 35);
        long shrinkSeconds = getConfig().getLong("world-border." + phaseName + ".shrink-seconds", 60);
        // Generated schematics carry a -32 X/Z clipboard offset, so pasteX/pasteZ are the arena centers.
        double centerX = arena.pasteX;
        double centerZ = arena.pasteZ;
        for (UUID id : alive) {
            Player fighter = Bukkit.getPlayer(id);
            if (fighter == null) continue;
            WorldBorder border = Bukkit.createWorldBorder();
            border.setCenter(centerX, centerZ);
            border.setSize(size, Math.max(0L, shrinkSeconds));
            border.setWarningDistance(10);
            fighter.setWorldBorder(border);
        }
    }

    private void clearPlayerBorder(Player player) {
        player.setWorldBorder(null);
    }

    private void assignTeams() {
        teams.clear();
        int red = 0, blue = 0;
        for (UUID id : participants) {
            int team = red <= blue ? 1 : 2;
            teams.put(id, team);
            if (team == 1) red++; else blue++;
        }
        Bukkit.broadcastMessage("§6[Clasher Events] §eTeam Wars: players have been split into Red and Blue teams.");
    }

    private Integer remainingTeam() {
        Set<Integer> remaining = new HashSet<>();
        for (UUID id : alive) {
            Integer team = teams.get(id);
            if (team != null) remaining.add(team);
        }
        return remaining.size() == 1 ? remaining.iterator().next() : null;
    }

    private void checkTeamWinner() {
        if (!eventRunning || !eventMode.equals("team-wars")) return;
        Integer team = remainingTeam();
        if (team == null) {
            if (alive.isEmpty()) stopEvent("Team Wars ended in a draw");
            return;
        }
        Bukkit.broadcastMessage(team == 1 ? "§c§lRED TEAM WINS!" : "§9§lBLUE TEAM WINS!");
        for (UUID id : participants) {
            if (Objects.equals(teams.get(id), team)) {
                Player member = Bukkit.getPlayer(id);
                if (member != null) runRewardCommands("team-winner", member);
            }
        }
        stopEvent("Team Wars finished");
    }

    @EventHandler public void onTeamFriendlyFire(EntityDamageByEntityEvent event) {
        if (!eventRunning || !(event.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player direct) attacker = direct;
        else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) attacker = shooter;
        if (attacker == null) return;
        lastDamager.put(victim.getUniqueId(), attacker.getUniqueId());
        lastCombatAt.put(victim.getUniqueId(), System.currentTimeMillis());
        lastMovementAt.put(attacker.getUniqueId(), System.currentTimeMillis());
        lastMovementAt.put(victim.getUniqueId(), System.currentTimeMillis());
        afkWarned.remove(victim.getUniqueId()); afkWarned.remove(attacker.getUniqueId());
        Integer a = teams.get(attacker.getUniqueId());
        Integer v = teams.get(victim.getUniqueId());
        if (a != null && a.equals(v)) event.setCancelled(true);
    }

    private void advancePhaseIfNeeded() {
        if (!eventRunning || alive.isEmpty()) return;
        String next = null;
        if (phase.equals("wave1") && alive.size() > 2
                && alive.size() <= Math.max(3, (int)Math.ceil(initialFighters * 0.67))) next = "wave2";
        else if ((phase.equals("wave1") || phase.equals("wave2")) && alive.size() <= 2) next = "final";
        if (next == null || next.equals(phase)) return;
        Arena arena = arenas.get(selectedArena);
        List<Location> points = arena == null ? List.of() : spawns(arena, next);
        if (points.isEmpty()) { Bukkit.broadcastMessage("§c[Clasher Events] Missing " + next + " spawn points in arenas.yml."); return; }
        phase = next;
        int i = 0;
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) { p.teleport(points.get(i++ % points.size())); p.setHealth(20); p.setFoodLevel(20); }
        }
        applyPhaseBorder(phase);
        Bukkit.broadcastMessage("§6§lCLASHER EVENTS §8» §e" + phase.toUpperCase(Locale.ROOT) + " has begun! §7" + alive.size() + " fighters remain.");
    }

    private void startEvent() {
        if (eventMode.equals("tournament")) { startTournament(); return; }
        if (!eventOpen || participants.isEmpty()) { Bukkit.broadcastMessage("§cNo event is open or no players joined."); stopEvent("No participants"); return; }
        if (eventMode.equals("duel") && participants.size() != 2) {
            Bukkit.broadcastMessage("§cA 1v1 Duel requires exactly two registered players. Current: " + participants.size());
            return;
        }
        if (eventMode.equals("team-wars") && participants.size() < 2) {
            Bukkit.broadcastMessage("§cTeam Wars requires at least two registered players.");
            return;
        }
        Arena arena = arenas.get(selectedArena);
        if (arena == null) { Bukkit.broadcastMessage("§cArena config missing: " + selectedArena); return; }
        List<Location> points = spawns(arena, "wave1");
        if (points.isEmpty()) { Bukkit.broadcastMessage("§cNo wave1 spawns configured for " + selectedArena); return; }
        eventOpen = false; eventRunning = true; phase = "wave1"; paused = false;
        alive.clear(); alive.addAll(participants); initialFighters = alive.size();
        int i = 0;
        for (UUID uuid : participants) {
            Player p = Bukkit.getPlayer(uuid); if (p == null) { alive.remove(uuid); continue; }
            snapshotIfNeeded(p);
            p.setGameMode(GameMode.SURVIVAL); p.setHealth(20); p.setFoodLevel(20); p.setFireTicks(0);
            p.teleport(points.get(i++ % points.size())); lastMovementAt.put(uuid, System.currentTimeMillis()); removeRulesBooks(p);
            if (!kitDelivered.contains(uuid)) { giveKit(p, selectedKit); kitDelivered.add(uuid); }
            playCue(p, "start");
        }
        if (alive.size() == 2) {
            phase = "final";
            List<Location> finalPoints = spawns(arena, "final");
            if (!finalPoints.isEmpty()) {
                int fi = 0;
                for (UUID id : alive) { Player fighter = Bukkit.getPlayer(id); if (fighter != null) fighter.teleport(finalPoints.get(fi++ % finalPoints.size())); }
            }
        }
        if (eventMode.equals("team-wars")) assignTeams();
        applyPhaseBorder(phase);
        Bukkit.broadcastMessage("§6§lCLASHER EVENTS §8» §a" + selectedArena + " has started! §eFighters: " + alive.size());
        if (eventMode.equals("team-wars")) checkTeamWinner();
        else if (alive.size() == 1) finishWinner(Bukkit.getPlayer(alive.iterator().next()));
    }

    private void startTournament() {
        if (!eventOpen || participants.size() < 2) {
            Bukkit.broadcastMessage("§cTournament requires at least two registered players.");
            stopEvent("Tournament cancelled: not enough players"); return;
        }
        eventOpen = false; eventRunning = true; paused = false; phase = "tournament";
        List<UUID> entrants = new ArrayList<>();
        for (UUID id : participants) if (Bukkit.getPlayer(id) != null) entrants.add(id);
        Collections.shuffle(entrants);
        if (entrants.size() < 2) { stopEvent("Tournament cancelled: players unavailable"); return; }
        for (UUID id : entrants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) { snapshotIfNeeded(p); p.setGameMode(GameMode.SPECTATOR); }
        }
        tournamentQueue.clear(); tournamentWinners.clear(); tournamentRound = 1;
        if ((entrants.size() & 1) == 1) tournamentWinners.add(entrants.remove(entrants.size() - 1));
        for (int i = 0; i + 1 < entrants.size(); i += 2) { tournamentQueue.addLast(entrants.get(i)); tournamentQueue.addLast(entrants.get(i + 1)); }
        Bukkit.broadcastMessage("§6§lCLASHER TOURNAMENT §8» §e" + participants.size() + " players, Round 1 begins!");
        startNextTournamentMatch();
    }

    private void startNextTournamentMatch() {
        if (!eventRunning || !eventMode.equals("tournament")) return;
        if (tournamentQueue.isEmpty()) {
            if (tournamentWinners.size() == 1) {
                Player champion = Bukkit.getPlayer(tournamentWinners.get(0));
                finishWinner(champion); return;
            }
            if (tournamentWinners.size() < 2) { stopEvent("Tournament ended without a champion"); return; }
            List<UUID> next = new ArrayList<>(tournamentWinners);
            tournamentWinners.clear(); Collections.shuffle(next); tournamentRound++;
            if ((next.size() & 1) == 1) tournamentWinners.add(next.remove(next.size() - 1));
            for (int i = 0; i + 1 < next.size(); i += 2) { tournamentQueue.addLast(next.get(i)); tournamentQueue.addLast(next.get(i + 1)); }
            Bukkit.broadcastMessage("§6§lCLASHER TOURNAMENT §8» §eRound " + tournamentRound + " begins!");
        }
        if (tournamentQueue.size() < 2) { startNextTournamentMatch(); return; }
        tournamentPlayerA = tournamentQueue.removeFirst(); tournamentPlayerB = tournamentQueue.removeFirst();
        Player a = Bukkit.getPlayer(tournamentPlayerA), b = Bukkit.getPlayer(tournamentPlayerB);
        if (a == null || b == null) {
            UUID advancing = a != null ? tournamentPlayerA : b != null ? tournamentPlayerB : null;
            if (advancing != null) tournamentWinners.add(advancing);
            Bukkit.getScheduler().runTaskLater(this, this::startNextTournamentMatch, 20L);
            return;
        }
        alive.clear(); alive.add(tournamentPlayerA); alive.add(tournamentPlayerB);
        Arena arena = arenas.get(selectedArena);
        List<Location> points = arena == null ? List.of() : spawns(arena, "final");
        if (points.size() < 2) { stopEvent("Tournament arena requires two final spawn points"); return; }
        a.setGameMode(GameMode.SURVIVAL); b.setGameMode(GameMode.SURVIVAL);
        a.teleport(points.get(0)); b.teleport(points.get(1));
        a.setHealth(20); b.setHealth(20); a.setFoodLevel(20); b.setFoodLevel(20);
        giveKit(a, selectedKit); giveKit(b, selectedKit);
        for (UUID id : participants) if (!alive.contains(id)) { Player spectator = Bukkit.getPlayer(id); if (spectator != null) spectator.setGameMode(GameMode.SPECTATOR); }
        applyPhaseBorder("final");
        Bukkit.broadcastMessage("§6§lCLASHER TOURNAMENT §8» §eRound " + tournamentRound + ": §a" + a.getName() + " §7vs §c" + b.getName());
    }

    private void completeTournamentMatch(UUID winnerId) {
        if (!eventRunning || !eventMode.equals("tournament")) return;
        if (winnerId != null) {
            tournamentWinners.add(winnerId);
            Player winner = Bukkit.getPlayer(winnerId);
            Bukkit.broadcastMessage("§6[Tournament] §e" + (winner == null ? "A player" : winner.getName()) + " §7advances to the next round.");
        }
        if (tournamentPlayerA != null) { Player a = Bukkit.getPlayer(tournamentPlayerA); if (a != null) clearPlayerBorder(a); }
        if (tournamentPlayerB != null) { Player b = Bukkit.getPlayer(tournamentPlayerB); if (b != null) clearPlayerBorder(b); }
        alive.clear(); tournamentPlayerA = null; tournamentPlayerB = null;
        Bukkit.getScheduler().runTaskLater(this, this::startNextTournamentMatch, 100L);
    }

    private void finishWinner(Player winner) {
        if (winner != null && !winnerRewarded) {
            winnerRewarded = true;
            eventWinner = winner.getUniqueId();
            awardPredictionPoints(winner.getUniqueId());
            runRewardCommands("winner", winner);
            String winPath = "players." + winner.getUniqueId();
            playerStats.set(winPath + ".wins", playerStats.getInt(winPath + ".wins") + 1);
            savePlayerStats();
            recordMatchResult(winner);
            Bukkit.broadcastMessage("§6§lCLASHER EVENTS §8» §e" + winner.getName() + " §ahas won!");
        }
        if (winner != null) {
            Location winnerLoc = configuredLocation("winner");
            if (winnerLoc != null) winner.teleport(winnerLoc);
            playCue(winner, "winner");
            World w = winner.getWorld();
            Firework fw = w.spawn(winner.getLocation(), Firework.class);
            FireworkMeta fm = fw.getFireworkMeta();
            fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(org.bukkit.Color.ORANGE, org.bukkit.Color.PURPLE).withFade(org.bukkit.Color.WHITE).trail(true).build());
            fm.setPower(1); fw.setFireworkMeta(fm);
        }
        stopEvent("Event finished");
    }


    private void recordMatchResult(Player winner) {
        int index = matchHistory.getInt("next-id", 1);
        String path = "matches." + index;
        matchHistory.set(path + ".arena", selectedArena);
        matchHistory.set(path + ".mode", eventMode);
        matchHistory.set(path + ".winner", winner.getName());
        matchHistory.set(path + ".winner-uuid", winner.getUniqueId().toString());
        matchHistory.set(path + ".started-at", matchStartedAt);
        matchHistory.set(path + ".finished-at", System.currentTimeMillis());
        matchHistory.set(path + ".duration-seconds", Math.max(0, (System.currentTimeMillis() - matchStartedAt) / 1000));
        matchHistory.set(path + ".players", participants.stream().map(UUID::toString).toList());
        UUID mvpId = matchKills.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(winner.getUniqueId());
        Player mvp = Bukkit.getPlayer(mvpId);
        matchHistory.set(path + ".mvp", mvp == null ? Bukkit.getOfflinePlayer(mvpId).getName() : mvp.getName());
        matchHistory.set(path + ".mvp-kills", matchKills.getOrDefault(mvpId, 0));
        matchHistory.set("next-id", index + 1);
        try { matchHistory.save(matchHistoryFile); }
        catch (java.io.IOException ex) { getLogger().warning("Could not save match history: " + ex.getMessage()); }
        Bukkit.broadcastMessage("§6[MVP] §e" + (mvp == null ? "Winner" : mvp.getName()) + " §7with §e" + matchKills.getOrDefault(mvpId, 0) + " §7kills.");
    }

    private void runRewardCommands(String rewardType, Player player) {
        for (String raw : getConfig().getStringList("rewards." + rewardType + ".commands")) {
            String command = raw.replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString())
                    .replace("{arena}", selectedArena).replace("{mode}", eventMode);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    private void stopEvent(String reason) {
        cancelCountdown();
        if (!eventOpen && !eventRunning && participants.isEmpty() && snapshots.isEmpty()) return;
        restoreAllPlayers();
        setArenaBuildAllowed(false);
        participants.clear(); alive.clear(); votes.clear(); teams.clear(); lastDamager.clear(); lastCombatAt.clear(); lastMovementAt.clear(); afkWarned.clear(); tournamentQueue.clear(); tournamentWinners.clear(); tournamentPlayerA = null; tournamentPlayerB = null; eventOpen = false; eventRunning = false; paused = false; phase = "idle"; kitDelivered.clear(); disconnectedFighters.clear(); eventWinner = null;
        for (BukkitTask task : disconnectTasks.values()) task.cancel(); disconnectTasks.clear();
        if (Bukkit.getOnlinePlayers().size() > 0 && !reason.equals("Server shutting down")) Bukkit.broadcastMessage("§6§lCLASHER EVENTS §8» §7" + reason + ".");
    }

    private void teleportLobby(Player p) {
        World world = Bukkit.getWorld(getConfig().getString("lobby.world", getConfig().getString("shared-world", "clasher_events")));
        if (world != null) p.teleport(new Location(world, getConfig().getDouble("lobby.x"), getConfig().getDouble("lobby.y"), getConfig().getDouble("lobby.z")));
    }



    @EventHandler public void onSpectatorChat(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        String text = event.getMessage();
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> {
            if (!participants.contains(id) || alive.contains(id)) return;
            String message = "§8[SPECTATOR] §7" + name + "§8: §f" + text;
            for (UUID participant : new HashSet<>(participants)) {
                Player target = Bukkit.getPlayer(participant);
                if (target != null && !alive.contains(participant)) target.sendMessage(message);
            }
        });
    }

    @EventHandler public void onFighterMove(PlayerMoveEvent event) {
        if (event.getTo() == null || (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ())) return;
        lastMovementAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler public void onLobbyDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && (inWaitingLobby(player) || (eventOpen && participants.contains(player.getUniqueId()) && !eventRunning))) event.setCancelled(true);
    }
    @EventHandler public void onLobbyFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && (inWaitingLobby(player) || (eventOpen && participants.contains(player.getUniqueId()) && !eventRunning))) event.setCancelled(true);
    }
    @EventHandler public void onLobbyBreak(BlockBreakEvent event) {
        if (inWaitingLobby(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler public void onLobbyPlace(BlockPlaceEvent event) {
        if (inWaitingLobby(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler public void onLobbyDrop(PlayerDropItemEvent event) {
        if (inWaitingLobby(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler public void onLobbyInteract(PlayerInteractEvent event) {
        if (inWaitingLobby(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler public void onLobbyPickup(PlayerAttemptPickupItemEvent event) {
        if (inWaitingLobby(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler public void onLobbyInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && inWaitingLobby(player)) event.setCancelled(true);
    }
    @EventHandler public void onLobbyInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && inWaitingLobby(player)) event.setCancelled(true);
    }

    @EventHandler public void onPausedDamage(EntityDamageEvent event) {
        if (paused && event.getEntity() instanceof Player) event.setCancelled(true);
    }

    @EventHandler public void onUhcNaturalRegen(EntityRegainHealthEvent event) {
        if (eventRunning && eventMode.equals("uhc") && event.getEntity() instanceof Player
                && event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            event.setCancelled(true);
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity(); UUID id = p.getUniqueId();
        if (!alive.remove(id)) return;
        UUID killerId = lastDamager.remove(id);
        lastCombatAt.remove(id);
        if (killerId != null) {
            Player killer = Bukkit.getPlayer(killerId);
            if (killer != null && !killer.getUniqueId().equals(id)) {
                recordKill(killer, p);
                matchKills.merge(killer.getUniqueId(), 1, Integer::sum);
                Bukkit.broadcastMessage("§8[§cKILL§8] §e" + killer.getName() + " §7eliminated §e" + p.getName());
            } else recordDeath(p);
        } else recordDeath(p);
        event.getDrops().clear(); event.setDroppedExp(0); event.setKeepInventory(true); event.setKeepLevel(true);
        Bukkit.broadcastMessage("§7" + p.getName() + " has been eliminated! §e" + alive.size() + " fighters remain.");
        if (eventMode.equals("tournament")) {
            UUID winnerId = alive.isEmpty() ? null : alive.iterator().next();
            Bukkit.getScheduler().runTask(this, () -> completeTournamentMatch(winnerId));
            return;
        }
        if (eventMode.equals("team-wars")) { checkTeamWinner(); return; }
        if (alive.size() == 1) {
            UUID winnerId = alive.iterator().next();
            Bukkit.getScheduler().runTask(this, () -> finishWinner(Bukkit.getPlayer(winnerId)));
        } else if (alive.isEmpty()) stopEvent("Event ended with no winner");
        else advancePhaseIfNeeded();
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!participants.contains(id)) {
            PlayerSnapshot pending = snapshots.remove(id);
            if (pending != null) Bukkit.getScheduler().runTask(this, () -> {
                pending.restore(event.getPlayer(), lobbyLocation());
                removePersistedSnapshot(id);
            });
            return;
        }
        Arena arena = arenas.get(selectedArena);
        Location audience = arena == null ? null : location(arena, "audience");
        Location spectator = configuredLocation("spectator");
        if (spectator == null) spectator = audience;
        if (spectator != null) event.setRespawnLocation(spectator);
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = event.getPlayer();
            if (participants.contains(id) && !alive.contains(id)) p.setGameMode(GameMode.SPECTATOR);
        });
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (disconnectedFighters.contains(id) && eventRunning) {
            BukkitTask timer = disconnectTasks.remove(id); if (timer != null) timer.cancel();
            disconnectedFighters.remove(id);
            participants.add(id); alive.add(id); lastMovementAt.put(id, System.currentTimeMillis());
            Bukkit.getScheduler().runTask(this, () -> {
                Player player = event.getPlayer();
                Arena arena = arenas.get(selectedArena);
                List<Location> points = arena == null ? List.of() : spawns(arena, phase.equals("final") ? "final" : phase);
                if (!points.isEmpty()) player.teleport(points.get(new Random().nextInt(points.size())));
                player.setGameMode(GameMode.SURVIVAL);
                player.sendMessage("§aReconnected. Your event state has been restored; no fresh kit was issued.");
                applyPhaseBorder(phase);
            });
            return;
        }
        PlayerSnapshot pending = loadPersistedSnapshot(id);
        if (pending == null) return;
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) {
                pending.restore(player, lobbyLocation());
                removePersistedSnapshot(id);
                snapshots.remove(id); participants.remove(id); alive.remove(id);
                player.sendMessage("§aYour inventory and player state were recovered from the previous event session.");
            }
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clearPlayerBorder(event.getPlayer());
        if (eventRunning && alive.contains(id)) {
            disconnectedFighters.add(id);
            Bukkit.broadcastMessage("§e" + event.getPlayer().getName() + " disconnected. 60 seconds to reconnect.");
            BukkitTask old = disconnectTasks.remove(id); if (old != null) old.cancel();
            disconnectTasks.put(id, Bukkit.getScheduler().runTaskLater(this, () -> {
                disconnectTasks.remove(id);
                disconnectedFighters.remove(id);
                participants.remove(id);
                if (alive.remove(id)) {
                    Bukkit.broadcastMessage("§c" + event.getPlayer().getName() + " failed to reconnect and was eliminated.");
                    processEliminationAfterDisconnect();
                }
            }, 1200L));
        } else if (eventOpen && participants.remove(id)) {
            Bukkit.broadcastMessage("§7" + event.getPlayer().getName() + " left the registration.");
        }
    }

    private void processEliminationAfterDisconnect() {
        if (eventMode.equals("tournament")) {
            UUID winnerId = alive.isEmpty() ? null : alive.iterator().next();
            Bukkit.getScheduler().runTask(this, () -> completeTournamentMatch(winnerId));
        } else if (eventMode.equals("team-wars")) checkTeamWinner();
        else if (alive.size() == 1) {
            UUID winnerId = alive.iterator().next();
            Bukkit.getScheduler().runTask(this, () -> finishWinner(Bukkit.getPlayer(winnerId)));
        } else if (alive.isEmpty()) stopEvent("Event ended with no winner");
        else advancePhaseIfNeeded();
    }

    private void handleVote(Player voter, String targetName) {
        if (!participants.contains(voter.getUniqueId()) || alive.contains(voter.getUniqueId())) { voter.sendMessage("§cOnly eliminated spectators can vote."); return; }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !alive.contains(target.getUniqueId())) { voter.sendMessage("§cThat player is not an active fighter."); return; }
        votes.put(voter.getUniqueId(), target.getUniqueId());
        voter.sendMessage("§aYour winner prediction: " + target.getName());
    }

    private void handleCheer(Player spectator, String targetName) {
        if (!participants.contains(spectator.getUniqueId()) || alive.contains(spectator.getUniqueId())) { spectator.sendMessage("§cOnly eliminated spectators can cheer."); return; }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !alive.contains(target.getUniqueId())) { spectator.sendMessage("§cThat player is not an active fighter."); return; }
        Bukkit.broadcastMessage("§d" + spectator.getName() + " cheers for " + target.getName() + "!");
    }

    private void validateArena(CommandSender sender, String id) {
        Arena arena = arenas.get(id.toLowerCase(Locale.ROOT));
        if (arena == null) { sender.sendMessage("§cUnknown arena: " + id); return; }
        File schematic = new File(new File(getDataFolder(), "schematics"), arena.schematic);
        List<String> missing = new ArrayList<>();
        if (!schematic.isFile()) missing.add("schematic file");
        for (String phaseName : List.of("wave1", "wave2", "final", "audience")) {
            if (spawns(arena, phaseName).isEmpty()) missing.add(phaseName + " spawn");
        }
        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) missing.add("WorldEdit");
        if (missing.isEmpty()) sender.sendMessage("§a" + arena.displayName + " validation passed.");
        else sender.sendMessage("§c" + arena.displayName + " missing: " + String.join(", ", missing));
    }


    private void savePlayerStats() {
        try { playerStats.save(playerStatsFile); }
        catch (java.io.IOException ex) { getLogger().warning("Could not save player stats: " + ex.getMessage()); }
    }

    private void recordKill(Player killer, Player victim) {
        String k = "players." + killer.getUniqueId();
        playerStats.set(k + ".name", killer.getName());
        playerStats.set(k + ".kills", playerStats.getInt(k + ".kills") + 1);
        playerStats.set(k + ".last-kill", victim.getName());
        String v = "players." + victim.getUniqueId();
        playerStats.set(v + ".name", victim.getName());
        playerStats.set(v + ".deaths", playerStats.getInt(v + ".deaths") + 1);
        savePlayerStats();
    }

    private void recordDeath(Player victim) {
        String path = "players." + victim.getUniqueId();
        playerStats.set(path + ".name", victim.getName());
        playerStats.set(path + ".deaths", playerStats.getInt(path + ".deaths") + 1);
        savePlayerStats();
    }

    private void openSpectatorTargets(Player viewer) {
        List<Player> targets = new ArrayList<>();
        for (UUID id : alive) { Player target = Bukkit.getPlayer(id); if (target != null && !target.equals(viewer)) targets.add(target); }
        if (targets.isEmpty()) { viewer.sendMessage("§cNo active fighters to spectate."); return; }
        Inventory gui = Bukkit.createInventory(null, 54, "§8Spectate Fighter");
        int slot = 0;
        for (Player target : targets) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) { meta.setOwningPlayer(target); meta.setDisplayName("§e" + target.getName()); meta.setLore(List.of("§7Health: §c" + (int)Math.ceil(target.getHealth()) + "❤", "§aClick to spectate")); head.setItemMeta(meta); }
            gui.setItem(slot++, head);
            if (slot >= gui.getSize()) break;
        }
        viewer.openInventory(gui);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("event")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("join")) {
                if (!p.hasPermission("clasherevents.join")) { p.sendMessage("§cYou cannot join events."); return true; }
                if (!eventOpen) { p.sendMessage("§cNo event is accepting players right now."); return true; }
                if (participants.add(p.getUniqueId())) {
                    snapshotIfNeeded(p); addArenaBuilder(p.getUniqueId()); p.getInventory().clear(); p.setGameMode(GameMode.ADVENTURE);
                    teleportToConfigured(p, "lobby");
                    Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline() && participants.contains(p.getUniqueId()) && eventOpen) giveRulesBook(p); }, 40L);
                    playCue(p, "join");
                    Bukkit.broadcastMessage("§e" + p.getName() + " joined the event! (" + participants.size() + ")");
                }
            } else if (sub.equals("leave")) {
                if (participants.remove(p.getUniqueId())) {
                    alive.remove(p.getUniqueId());
                    PlayerSnapshot snapshot = snapshots.remove(p.getUniqueId());
                    clearPlayerBorder(p);
                    if (snapshot != null) snapshot.restore(p, lobbyLocation());
                    p.sendMessage("§aYou left the event.");
                    if (eventRunning && eventMode.equals("team-wars")) checkTeamWinner();
                    else if (eventRunning && alive.size() == 1) finishWinner(Bukkit.getPlayer(alive.iterator().next()));
                    else if (eventRunning && alive.isEmpty()) stopEvent("Event ended with no winner");
                } else p.sendMessage("§cYou are not registered.");
            } else if (sub.equals("vote")) {
                if (args.length >= 2) handleVote(p, args[1]); else openVoteGui(p);
            } else if (sub.equals("cheer") && args.length >= 2) handleCheer(p, args[1]);
            else if (sub.equals("points")) p.sendMessage("§6Audience points: §e" + points(p.getUniqueId()));
            else if (sub.equals("stats")) {
                String path = "players." + p.getUniqueId();
                p.sendMessage("§6§lYOUR CLASHER STATS");
                p.sendMessage("§eWins: §f" + playerStats.getInt(path + ".wins") + " §8| §eKills: §f" + playerStats.getInt(path + ".kills") + " §8| §eDeaths: §f" + playerStats.getInt(path + ".deaths"));
                p.sendMessage("§eAudience points: §f" + points(p.getUniqueId()));
            } else if (sub.equals("spectate")) {
                if (participants.contains(p.getUniqueId()) && !alive.contains(p.getUniqueId())) openSpectatorTargets(p);
                else p.sendMessage("§cOnly event spectators can use this command.");
            }
            else if (sub.equals("top")) {
                ConfigurationSection players = audiencePoints.getConfigurationSection("players");
                if (players == null || players.getKeys(false).isEmpty()) p.sendMessage("§6Audience leaderboard: §7No points yet.");
                else {
                    List<String> rows = new ArrayList<>();
                    for (String key : players.getKeys(false)) rows.add(audiencePoints.getString("players." + key + ".name", key) + "§7: §e" + audiencePoints.getInt("players." + key + ".points"));
                    rows.sort((a,b) -> Integer.compare(Integer.parseInt(org.bukkit.ChatColor.stripColor(b).replaceAll(".*: ", "")), Integer.parseInt(org.bukkit.ChatColor.stripColor(a).replaceAll(".*: ", ""))));
                    p.sendMessage("§6Audience leaderboard: §e" + String.join(" §7| §e", rows.subList(0, Math.min(5, rows.size()))));
                }
            } else p.sendMessage("§e/event join | leave | vote [player] | cheer <player> | team | points | stats | top | spectate");
            return true;
        }
        if (command.getName().equalsIgnoreCase("latejoin")) {
            if (!sender.hasPermission("clasherevents.admin")) { sender.sendMessage("§cNo permission."); return true; }
            if (args.length != 1) { sender.sendMessage("§cUsage: /latejoin <playername>"); return true; }
            forceLateJoin(sender, args[0]); return true;
        }
        if (!sender.hasPermission("clasherevents.admin")) { sender.sendMessage("§cNo permission."); return true; }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> {
                sender.sendMessage("§6§lClasher Events §7commands:");
                sender.sendMessage("§e/ce create <mode> §7| /ce start | /ce stop | /ce status");
                sender.sendMessage("§e/ce arena list|paste|reset|info <arena>");
                sender.sendMessage("§e/ce setspawn <mode|arena> [wave1|wave2|final|audience] §7(replace phase spawns)");
                sender.sendMessage("§e/ce addspawn <mode|arena> [wave1|wave2|final|audience] §7(add another point)");
                sender.sendMessage("§7Modes: nethpot, axe-shield, build-uhc, sword, uhc, crystal, smp, diamond-pot, duel, team-wars, tournament.");
                sender.sendMessage("§7Random-kit uses any arena: configure each arena separately (nethpot, axe-shield, build-uhc, sword, uhc, crystal, smp, diamond-pot).");
                sender.sendMessage("§e/ce kit list|give <kit> <player>|reload");
                sender.sendMessage("§e/ce gui §7| /ce kit preview <kit> <player>");
            }
            case "create" -> { if (args.length < 2) sender.sendMessage("§cUsage: /ce create <nethpot|axe-shield|build-uhc|sword|uhc|crystal|smp|diamond-pot|random-kit|duel|team-wars>"); else createEvent(args[1]); }
            case "start" -> startEvent();
            case "stop" -> stopEvent("Event stopped by admin");
            case "pause" -> { if (!eventOpen && !eventRunning) sender.sendMessage("§cNo active event."); else { paused = true; sender.sendMessage("§aRegistration countdown paused. (Live combat is not frozen.)"); } }
            case "resume" -> { if (!paused) sender.sendMessage("§cEvent is not paused."); else { paused = false; sender.sendMessage("§aEvent countdown resumed."); } }
            case "gui" -> { if (sender instanceof Player admin) openEventGui(admin); else sender.sendMessage("§cPlayers only."); }
            case "countdown" -> { if (sender instanceof Player admin) openCountdownGui(admin); else sender.sendMessage("§cPlayers only."); }
            case "setlobby" -> { if (sender instanceof Player admin) setNamedLocation(admin, "lobby"); else sender.sendMessage("§cPlayers only."); }
            case "setspectator" -> { if (sender instanceof Player admin) setNamedLocation(admin, "spectator"); else sender.sendMessage("§cPlayers only."); }
            case "setwinner" -> { if (sender instanceof Player admin) setNamedLocation(admin, "winner"); else sender.sendMessage("§cPlayers only."); }
            case "setspawn", "addspawn" -> {
                if (!(sender instanceof Player admin)) { sender.sendMessage("§cPlayers only."); break; }
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /ce " + sub + " <mode|arena> [wave1|wave2|final|audience]");
                    break;
                }
                String requested = args[1].toLowerCase(Locale.ROOT);
                if (requested.equals("random-kit")) {
                    sender.sendMessage("§eRandom Kit can select any arena, so configure every arena separately at its own physical location.");
                    sender.sendMessage("§fArenas: nethpot, axe-shield, build-uhc, sword, uhc, crystal, smp, diamond-pot.");
                    sender.sendMessage("§7Repeat /ce " + sub + " <arena> <phase> for each arena and required phase.");
                    break;
                }
                String arenaId = arenaIdForMode(requested);
                if (arenaId == null) {
                    sender.sendMessage("§cUnknown mode/arena: " + requested + ". Use /ce arena list or /ce help.");
                    break;
                }
                setArenaSpawn(admin, arenaId, args.length >= 3 ? args[2] : "wave1", sub.equals("addspawn"));
            }
            case "status" -> sender.sendMessage("§6Mode: " + eventMode + " §7| Arena: " + selectedArena + " §7| Phase: " + phase + " §7| Open: " + eventOpen + " §7| Running: " + eventRunning + " §7| Players: " + participants.size());
            case "reload" -> { reloadConfig(); loadArenas(); loadKits(); sender.sendMessage("§aConfigs reloaded."); }
            case "arena" -> {
                if (args.length < 2) { sender.sendMessage("§c/ce arena list|paste|reset|info|validate <arena>"); break; }
                String action = args[1].toLowerCase(Locale.ROOT);
                if (action.equals("list")) sender.sendMessage("§eArenas: §f" + String.join(", ", arenas.keySet()));
                else if (action.equals("paste") && args.length >= 3) pasteArena(sender, args[2]);
                else if (action.equals("reset") && args.length >= 3) resetArena(sender, args[2]);
                else if (action.equals("info") && args.length >= 3) { Arena a = arenas.get(args[2].toLowerCase(Locale.ROOT)); sender.sendMessage(a == null ? "§cUnknown arena." : "§e" + a.displayName + " §7| schematic: " + a.schematic + " | paste: " + a.pasteX + "," + a.pasteY + "," + a.pasteZ); }
                else if (action.equals("validate") && args.length >= 3) validateArena(sender, args[2]);
                else sender.sendMessage("§cInvalid arena command.");
            }
            case "kit" -> {
                if (args.length < 2) { sender.sendMessage("§c/ce kit list|give <kit> <player>|reload"); break; }
                if (args[1].equalsIgnoreCase("list")) sender.sendMessage("§eKits: §f" + String.join(", ", kits.keySet()));
                else if (args[1].equalsIgnoreCase("reload")) { loadKits(); sender.sendMessage("§aKits reloaded."); }
                else if (args[1].equalsIgnoreCase("preview") && args.length >= 4) { Player target = Bukkit.getPlayerExact(args[3]); if (target == null) sender.sendMessage("§cPlayer not online."); else openKitPreview(target, args[2]); }
                else if (args[1].equalsIgnoreCase("give") && args.length >= 4) { Player target = Bukkit.getPlayerExact(args[3]); if (target == null) sender.sendMessage("§cPlayer not online."); else { giveKit(target, args[2]); sender.sendMessage("§aKit given."); } }
                else sender.sendMessage("§cInvalid kit command.");
            }
            default -> sender.sendMessage("§cUnknown command. Use /ce help");
        }
        return true;
    }


    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("latejoin")) {
            if (args.length == 1) for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        } else if (command.getName().equalsIgnoreCase("event")) {
            if (args.length == 1) options.addAll(List.of("join", "leave", "vote", "cheer", "team", "points", "stats", "top", "spectate"));
            else if (args.length == 2 && List.of("vote", "cheer").contains(args[0].toLowerCase(Locale.ROOT)))
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        } else if (command.getName().equalsIgnoreCase("ce") && sender.hasPermission("clasherevents.admin")) {
            if (args.length == 1) options.addAll(List.of("help","create","start","stop","pause","resume","status","reload","gui","countdown","setlobby","setspectator","setwinner","setspawn","addspawn","arena","kit"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("create"))
                options.addAll(List.of("nethpot","axe-shield","build-uhc","sword","uhc","crystal","smp","diamond-pot","random-kit","duel","team-wars","tournament"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("arena")) options.addAll(List.of("list","paste","reset","info","validate"));
            else if (args.length == 2 && List.of("setspawn", "addspawn").contains(args[0].toLowerCase(Locale.ROOT)))
                options.addAll(List.of("nethpot","axe-shield","build-uhc","sword","uhc","crystal","smp","diamond-pot","duel","team-wars","tournament","random-kit"));
            else if (args.length == 3 && List.of("setspawn", "addspawn").contains(args[0].toLowerCase(Locale.ROOT))) options.addAll(List.of("wave1","wave2","final","audience"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("kit")) options.addAll(List.of("list","give","preview","reload"));
            else if (args.length == 3 && args[0].equalsIgnoreCase("arena")) options.addAll(arenas.keySet());
            else if (args.length == 3 && args[0].equalsIgnoreCase("kit") && List.of("give","preview").contains(args[1].toLowerCase(Locale.ROOT))) options.addAll(kits.keySet());
            else if (args.length == 4 && args[0].equalsIgnoreCase("kit") && args[1].equalsIgnoreCase("give"))
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }

    private static final class PlayerSnapshot {
        private final ItemStack[] storage, armor;
        private final ItemStack offhand;
        private final GameMode mode;
        private final double health;
        private final int food, level, fire;
        private final float exp, saturation;
        private final Location location;
        private final Collection<PotionEffect> effects;
        PlayerSnapshot(Player p) {
            storage = cloneItems(p.getInventory().getStorageContents());
            armor = cloneItems(p.getInventory().getArmorContents());
            offhand = p.getInventory().getItemInOffHand() == null ? null : p.getInventory().getItemInOffHand().clone();
            mode = p.getGameMode(); health = Math.max(1.0, p.getHealth()); food = p.getFoodLevel();
            level = p.getLevel(); exp = p.getExp(); saturation = p.getSaturation(); fire = p.getFireTicks();
            location = p.getLocation().clone(); effects = new ArrayList<>(p.getActivePotionEffects());
        }
        static PlayerSnapshot from(ConfigurationSection section) {
            List<?> storageList = section.getList("storage", Collections.emptyList());
            List<?> armorList = section.getList("armor", Collections.emptyList());
            ItemStack[] storage = readItems(storageList, 36), armor = readItems(armorList, 4);
            ItemStack offhand = section.getItemStack("offhand");
            GameMode mode;
            try { mode = GameMode.valueOf(section.getString("gamemode", "SURVIVAL")); } catch (IllegalArgumentException ex) { mode = GameMode.SURVIVAL; }
            World world = Bukkit.getWorld(section.getString("world", ""));
            if (world == null) world = Bukkit.getWorlds().get(0);
            Location location = new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                    (float)section.getDouble("yaw"), (float)section.getDouble("pitch"));
            List<PotionEffect> effects = new ArrayList<>();
            for (Map<?, ?> row : section.getMapList("effects")) {
                PotionEffectType type = PotionEffectType.getByName(String.valueOf(row.get("type")));
                if (type == null) continue;
                int duration = row.get("duration") instanceof Number n ? n.intValue() : 20;
                int amplifier = row.get("amplifier") instanceof Number n ? n.intValue() : 0;
                effects.add(new PotionEffect(type, duration, amplifier, Boolean.TRUE.equals(row.get("ambient")),
                        !Boolean.FALSE.equals(row.get("particles")), !Boolean.FALSE.equals(row.get("icon"))));
            }
            return new PlayerSnapshot(storage, armor, offhand, mode, section.getDouble("health", 20), section.getInt("food", 20),
                    section.getInt("level"), (float)section.getDouble("exp"), (float)section.getDouble("saturation", 5),
                    section.getInt("fire"), location, effects);
        }
        private static ItemStack[] readItems(List<?> values, int size) {
            ItemStack[] result = new ItemStack[size];
            for (int i = 0; i < Math.min(size, values.size()); i++)
                if (values.get(i) instanceof ItemStack item) result[i] = item.clone();
            return result;
        }

        private PlayerSnapshot(ItemStack[] storage, ItemStack[] armor, ItemStack offhand, GameMode mode, double health,
                               int food, int level, float exp, float saturation, int fire, Location location,
                               Collection<PotionEffect> effects) {
            this.storage = cloneItems(storage); this.armor = cloneItems(armor);
            this.offhand = offhand == null ? null : offhand.clone(); this.mode = mode; this.health = health;
            this.food = food; this.level = level; this.exp = exp; this.saturation = saturation; this.fire = fire;
            this.location = location; this.effects = new ArrayList<>(effects);
        }

        void restore(Player p, Location lobby) {
            p.getInventory().clear(); p.getInventory().setStorageContents(cloneItems(storage));
            p.getInventory().setArmorContents(cloneItems(armor));
            p.getInventory().setItemInOffHand(offhand == null ? null : offhand.clone());
            for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());
            for (PotionEffect effect : effects) p.addPotionEffect(effect);
            p.setGameMode(mode); p.setHealth(Math.min(health, p.getMaxHealth())); p.setFoodLevel(food);
            p.setLevel(level); p.setExp(exp); p.setSaturation(saturation); p.setFireTicks(fire);
            p.teleport(lobby == null ? location : lobby);
        }
        private static ItemStack[] cloneItems(ItemStack[] source) {
            ItemStack[] copy = new ItemStack[source.length];
            for (int i=0;i<source.length;i++) copy[i] = source[i] == null ? null : source[i].clone();
            return copy;
        }
    }

    private record Arena(String id, String displayName, String schematic, int pasteX, int pasteY, int pasteZ, ConfigurationSection section) { }
}
