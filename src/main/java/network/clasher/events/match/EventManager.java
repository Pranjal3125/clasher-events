package network.clasher.events.match;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import network.clasher.events.arena.ArenaManager;
import network.clasher.events.gui.EventMenuHolder;
import network.clasher.events.kit.KitManager;
import network.clasher.events.model.Arena;
import network.clasher.events.model.EventMode;
import network.clasher.events.model.PlayerSnapshot;
import network.clasher.events.storage.DataStore;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core PvP event lifecycle state machine, combat/lobby listeners, tournament brackets,
 * Team Wars balancing, AFK/disconnect guards, and crash-safe player restoration.
 */
public final class EventManager implements Listener {
    private static final String PREFIX = "<gold><bold>CLASHER EVENTS</bold></gold> <dark_gray>»</dark_gray> ";

    private final JavaPlugin plugin;
    private final ArenaManager arenaManager;
    private final KitManager kitManager;
    private final DataStore data;
    private final NamespacedKey rulesBookKey;
    private final Random random = new Random();

    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, UUID> votes = new HashMap<>();
    private final Map<UUID, Integer> teams = new HashMap<>();
    private final Deque<UUID> tournamentQueue = new ArrayDeque<>();
    private final List<UUID> tournamentWinners = new ArrayList<>();
    private final Map<UUID, BukkitTask> disconnectTasks = new HashMap<>();
    private final Set<UUID> disconnectedFighters = new HashSet<>();
    private final Set<UUID> kitDelivered = new HashSet<>();
    private final Map<UUID, UUID> lastDamager = new HashMap<>();
    private final Map<UUID, Long> lastCombatAt = new HashMap<>();
    private final Map<UUID, Long> lastMovementAt = new HashMap<>();
    private final Set<UUID> afkWarned = new HashSet<>();
    private final Map<UUID, Integer> matchKills = new HashMap<>();

    private String selectedArena = "nethpot";
    private String selectedKit = "nethpot";
    private EventMode eventMode = EventMode.NETHPOT;
    private boolean eventOpen;
    private boolean eventRunning;
    private boolean paused;
    private String phase = "idle";
    private int countdownRemaining;
    private int initialFighters;
    private UUID tournamentPlayerA;
    private UUID tournamentPlayerB;
    private int tournamentRound = 1;
    private boolean eventCompleted;
    private UUID eventWinner;
    private long matchStartedAt;

    private BukkitTask countdownTask;
    private BukkitTask statusTask;

    public EventManager(JavaPlugin plugin, ArenaManager arenaManager, KitManager kitManager, DataStore data) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.kitManager = kitManager;
        this.data = data;
        this.rulesBookKey = new NamespacedKey(plugin, "rules-book");
        startStatusLoop();
    }

    public void shutdown() {
        cancelCountdown();
        if (statusTask != null) {
            statusTask.cancel();
            statusTask = null;
        }
        stopEvent("Server shutting down");
    }

    // -------------------------------------------------------------------------
    // Read-only state accessors
    // -------------------------------------------------------------------------

    public boolean isEventOpen() { return eventOpen; }
    public boolean isEventRunning() { return eventRunning; }
    public boolean isPaused() { return paused; }
    public String selectedArena() { return selectedArena; }
    public String selectedKit() { return selectedKit; }
    public EventMode eventMode() { return eventMode; }
    public String phase() { return phase; }
    public int countdownRemaining() { return countdownRemaining; }
    public Set<UUID> participants() { return Collections.unmodifiableSet(participants); }
    public Set<UUID> alive() { return Collections.unmodifiableSet(alive); }
    public boolean isParticipant(UUID id) { return participants.contains(id); }
    public boolean isAlive(UUID id) { return alive.contains(id); }
    public boolean isSpectator(UUID id) { return participants.contains(id) && !alive.contains(id) && eventRunning; }
    public boolean isArenaInUse(String arenaId) { return (eventOpen || eventRunning) && selectedArena.equalsIgnoreCase(arenaId); }
    public DataStore data() { return data; }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    // -------------------------------------------------------------------------
    // Event creation, registration countdown, start & stop
    // -------------------------------------------------------------------------

    public void createEvent(String rawMode) {
        Optional<EventMode> parsed = EventMode.fromInput(rawMode);
        if (parsed.isEmpty()) {
            broadcast("<red>Unknown event mode: " + FormatUtil.escape(rawMode) + "</red>");
            return;
        }
        stopEvent("Replaced by a new event");
        eventMode = parsed.get();
        if (eventMode == EventMode.RANDOM_KIT) {
            List<String> keys = new ArrayList<>(arenaManager.arenaIds());
            if (keys.isEmpty()) {
                broadcast("<red>No arenas are configured for Random Kit mode.</red>");
                return;
            }
            selectedArena = keys.get(random.nextInt(keys.size()));
        } else {
            selectedArena = eventMode.defaultArenaId();
        }
        selectedKit = selectedArena;

        if (!arenaManager.exists(selectedArena) || !kitManager.exists(selectedKit)) {
            broadcast("<red>Missing arena or kit configuration for " + FormatUtil.escape(selectedArena) + ".</red>");
            return;
        }

        arenaManager.setArenaBuildAllowed(selectedArena, eventMode == EventMode.BUILD_UHC);
        eventCompleted = false;
        eventWinner = null;
        matchKills.clear();
        matchStartedAt = System.currentTimeMillis();
        eventOpen = true;
        eventRunning = false;
        paused = false;
        phase = "registration";
        clearCollections();
        countdownRemaining = Math.max(10, plugin.getConfig().getInt("default-registration-seconds", 120));

        Arena arena = arenaManager.get(selectedArena);
        String arenaLabel = arena != null ? arena.displayName() : selectedArena;
        broadcast("<yellow>" + FormatUtil.escape(eventMode.displayName()) + "</yellow> <gray>(</gray><white>"
                + FormatUtil.escape(arenaLabel) + "</white><gray>) registration is open for</gray> <yellow>"
                + FormatUtil.formatDuration(countdownRemaining) + "</yellow><gray>! Use</gray> "
                + "<green><click:run_command:'/event join'><hover:show_text:'<green>Click to join!</green>'>[/event join]</hover></click></green>");
        startCountdown();
    }

    public void setCountdownSeconds(int seconds) {
        if (seconds <= 0 || !eventOpen) return;
        countdownRemaining = seconds;
        plugin.getConfig().set("default-registration-seconds", seconds);
        plugin.saveConfig();
        startCountdown();
    }

    private void startCountdown() {
        cancelCountdown();
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!eventOpen || paused) return;
            int kitAt = plugin.getConfig().getInt("kit-delivery-seconds-before-start", 60);
            if (countdownRemaining == kitAt && !participants.isEmpty()) {
                for (UUID id : participants) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) {
                        snapshotIfNeeded(p);
                        removeRulesBooks(p);
                        kitManager.giveKit(p, selectedKit);
                        kitDelivered.add(id);
                        playCue(p, "kit");
                        p.sendMessage(FormatUtil.mm(PREFIX + "<green>Your event kit has been delivered! Combat begins when the countdown ends.</green>"));
                    }
                }
                broadcast("<green>Fighter kits delivered!</green>");
            }
            if (countdownRemaining <= 0) {
                cancelCountdown();
                startEvent();
                return;
            }
            if (countdownRemaining <= 10 || countdownRemaining % 30 == 0) {
                broadcast("<yellow>Event starts in</yellow> <gold><bold>" + FormatUtil.formatDuration(countdownRemaining) + "</bold></gold><yellow>.</yellow>");
            }
            countdownRemaining--;
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    public void startEvent() {
        if (eventMode == EventMode.TOURNAMENT) {
            startTournament();
            return;
        }
        if (!eventOpen || participants.isEmpty()) {
            broadcast("<red>No event is open or no players joined.</red>");
            stopEvent("No participants");
            return;
        }
        if (eventMode == EventMode.DUEL && participants.size() != 2) {
            broadcast("<red>A 1v1 Duel requires exactly 2 registered players. Current: " + participants.size() + "</red>");
            return;
        }
        if (eventMode == EventMode.TEAM_WARS && participants.size() < 2) {
            broadcast("<red>Team Wars requires at least 2 registered players.</red>");
            return;
        }

        Arena arena = arenaManager.get(selectedArena);
        if (arena == null) {
            broadcast("<red>Arena config missing: " + FormatUtil.escape(selectedArena) + "</red>");
            return;
        }
        eventOpen = false;
        eventRunning = true;
        paused = false;
        alive.clear();
        spectators.clear();
        for (UUID id : participants) {
            if (Bukkit.getPlayer(id) != null) alive.add(id);
        }
        initialFighters = alive.size();
        if (initialFighters == 0) {
            stopEvent("No online participants");
            return;
        }

        String startingPhase = initialFighters == 2 ? "final" : "wave1";
        List<Location> points = arenaManager.spawns(arena, startingPhase);
        if (points.isEmpty()) {
            stopEvent("No " + startingPhase + " spawn points configured for " + selectedArena);
            return;
        }
        phase = startingPhase;
        int index = 0;
        for (UUID id : new ArrayList<>(alive)) {
            Player fighter = Bukkit.getPlayer(id);
            if (fighter == null) continue;
            snapshotIfNeeded(fighter);
            fighter.setGameMode(GameMode.SURVIVAL);
            resetVitals(fighter);
            fighter.teleport(points.get(index++ % points.size()));
            lastMovementAt.put(id, System.currentTimeMillis());
            removeRulesBooks(fighter);
            if (!kitDelivered.contains(id)) {
                kitManager.giveKit(fighter, selectedKit);
                kitDelivered.add(id);
            }
            playCue(fighter, "start");
        }
        if (eventMode == EventMode.TEAM_WARS) assignTeams();
        applyPhaseBorder(phase);
        broadcast("<green>" + FormatUtil.escape(arena.displayName()) + " has started!</green> <yellow>Fighters: " + alive.size() + "</yellow>");

        if (eventMode == EventMode.TEAM_WARS) checkTeamWinner();
        else advancePhaseIfNeeded();
    }

    public void stopEvent(String reason) {
        cancelCountdown();
        if (!eventOpen && !eventRunning && participants.isEmpty() && snapshots.isEmpty()) return;
        restoreAllPlayers();
        arenaManager.setArenaBuildAllowed(selectedArena, false);
        clearCollections();
        eventOpen = false;
        eventRunning = false;
        paused = false;
        phase = "idle";
        eventWinner = null;
        if (!Bukkit.getOnlinePlayers().isEmpty() && !"Server shutting down".equals(reason)) {
            broadcast("<gray>" + FormatUtil.escape(reason) + ".</gray>");
        }
    }

    private void clearCollections() {
        participants.clear();
        alive.clear();
        spectators.clear();
        votes.clear();
        teams.clear();
        lastDamager.clear();
        lastCombatAt.clear();
        lastMovementAt.clear();
        afkWarned.clear();
        tournamentQueue.clear();
        tournamentWinners.clear();
        tournamentPlayerA = null;
        tournamentPlayerB = null;
        kitDelivered.clear();
        disconnectedFighters.clear();
        for (BukkitTask task : disconnectTasks.values()) task.cancel();
        disconnectTasks.clear();
    }

    // -------------------------------------------------------------------------
    // Player join / leave / latejoin / team / vote / cheer
    // -------------------------------------------------------------------------

    public void joinEvent(Player player) {
        if (!player.hasPermission("clasherevents.join")) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>You do not have permission to join events.</red>"));
            return;
        }
        if (!eventOpen) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>No event is accepting registrations right now.</red>"));
            return;
        }
        if (!participants.add(player.getUniqueId())) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<yellow>You are already registered for this event!</yellow>"));
            return;
        }
        snapshotIfNeeded(player);
        if (eventMode == EventMode.BUILD_UHC) {
            arenaManager.addArenaBuilder(selectedArena, player.getUniqueId());
        }
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        teleportToConfigured(player, "lobby");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && participants.contains(player.getUniqueId()) && eventOpen) {
                giveRulesBook(player);
            }
        }, 40L);
        playCue(player, "join");
        broadcast("<yellow>" + FormatUtil.escape(player.getName()) + "</yellow> <gray>joined the event!</gray> <dark_gray>(</dark_gray><green>" + participants.size() + "</green><dark_gray>)</dark_gray>");
    }

    public void leaveEvent(Player player) {
        UUID id = player.getUniqueId();
        if (!participants.remove(id)) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>You are not registered in the current event.</red>"));
            return;
        }
        boolean wasAlive = alive.remove(id);
        spectators.remove(id);
        votes.remove(id);
        teams.remove(id);
        disconnectedFighters.remove(id);
        BukkitTask dc = disconnectTasks.remove(id);
        if (dc != null) dc.cancel();

        clearPlayerBorder(player);
        PlayerSnapshot snapshot = snapshots.remove(id);
        if (snapshot != null) {
            snapshot.restore(player, lobbyLocation());
        }
        data.removePersistedSnapshot(id);
        player.sendMessage(FormatUtil.mm(PREFIX + "<green>You left the event and your state was restored.</green>"));

        if (eventRunning && wasAlive) {
            processEliminationAfterDisconnect();
        }
    }

    public void forceLateJoin(CommandSender sender, String playerName) {
        if (!eventRunning || eventOpen) {
            sender.sendMessage(FormatUtil.mm("<red>Late join is only available while an event is actively running.</red>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(FormatUtil.mm("<red>Player must be online.</red>"));
            return;
        }
        UUID id = target.getUniqueId();
        if (participants.contains(id) || alive.contains(id)) {
            sender.sendMessage(FormatUtil.mm("<red>Player is already in this event.</red>"));
            return;
        }
        if (disconnectedFighters.contains(id)) {
            sender.sendMessage(FormatUtil.mm("<red>This player has an active disconnect record.</red>"));
            return;
        }
        Arena arena = arenaManager.get(selectedArena);
        List<Location> points = arena == null ? List.of() : arenaManager.spawns(arena, phase.equals("final") ? "final" : phase);
        if (points.isEmpty() && arena != null) points = arenaManager.spawns(arena, "wave1");
        if (points.isEmpty()) {
            sender.sendMessage(FormatUtil.mm("<red>No safe fighter spawn configured.</red>"));
            return;
        }

        snapshotIfNeeded(target);
        participants.add(id);
        alive.add(id);
        spectators.remove(id);
        lastMovementAt.put(id, System.currentTimeMillis());
        if (eventMode == EventMode.BUILD_UHC) arenaManager.addArenaBuilder(selectedArena, id);
        if (eventMode == EventMode.TEAM_WARS) {
            long redCount = teams.values().stream().filter(t -> t == 1).count();
            long blueCount = teams.values().stream().filter(t -> t == 2).count();
            teams.put(id, redCount <= blueCount ? 1 : 2);
        }
        target.getInventory().clear();
        target.setGameMode(GameMode.SURVIVAL);
        resetVitals(target);
        target.teleport(points.get(random.nextInt(points.size())));
        removeRulesBooks(target);
        kitManager.giveKit(target, selectedKit);
        kitDelivered.add(id);
        applyPhaseBorder(phase);
        playCue(target, "kit");
        sender.sendMessage(FormatUtil.mm("<green>" + FormatUtil.escape(target.getName()) + " late-joined the event.</green>"));
        broadcast("<yellow>" + FormatUtil.escape(target.getName()) + " joined the event late!</yellow>");
    }

    public void showTeamInfo(Player player) {
        if (!eventRunning || eventMode != EventMode.TEAM_WARS) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<gray>Team Wars is not currently active.</gray>"));
            return;
        }
        Integer team = teams.get(player.getUniqueId());
        if (team == null) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<gray>You are not assigned to a team.</gray>"));
            return;
        }
        String teamLabel = team == 1 ? "<red><bold>RED TEAM</bold></red>" : "<blue><bold>BLUE TEAM</bold></blue>";
        List<String> mates = new ArrayList<>();
        int redAlive = 0, blueAlive = 0;
        for (Map.Entry<UUID, Integer> entry : teams.entrySet()) {
            boolean isAlive = alive.contains(entry.getKey());
            if (isAlive) {
                if (entry.getValue() == 1) redAlive++;
                else if (entry.getValue() == 2) blueAlive++;
            }
            if (entry.getValue().equals(team)) {
                OfflinePlayer mate = Bukkit.getOfflinePlayer(entry.getKey());
                String name = mate.getName() != null ? mate.getName() : entry.getKey().toString().substring(0, 8);
                mates.add((isAlive ? "<green>" : "<dark_gray><strikethrough>") + FormatUtil.escape(name) + (isAlive ? "</green>" : "</strikethrough></dark_gray>"));
            }
        }
        player.sendMessage(FormatUtil.mm(PREFIX + "<gray>Your Team:</gray> " + teamLabel
                + " <dark_gray>|</dark_gray> <red>Red Alive: " + redAlive + "</red> <dark_gray>vs</dark_gray> <blue>Blue Alive: " + blueAlive + "</blue>"));
        player.sendMessage(FormatUtil.mm("<gray>Teammates:</gray> " + String.join("<gray>, </gray>", mates)));
    }

    public void handleVote(Player voter, String targetName) {
        if (!isSpectator(voter.getUniqueId())) {
            voter.sendMessage(FormatUtil.mm(PREFIX + "<red>Only eliminated event spectators can predict the winner.</red>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !alive.contains(target.getUniqueId())) {
            voter.sendMessage(FormatUtil.mm(PREFIX + "<red>That player is not an active fighter.</red>"));
            return;
        }
        votes.put(voter.getUniqueId(), target.getUniqueId());
        voter.sendMessage(FormatUtil.mm(PREFIX + "<green>Winner prediction locked in for</green> <yellow>" + FormatUtil.escape(target.getName()) + "</yellow><green>!</green>"));
    }

    public void handleCheer(Player spectator, String targetName) {
        if (!isSpectator(spectator.getUniqueId())) {
            spectator.sendMessage(FormatUtil.mm(PREFIX + "<red>Only eliminated spectators can cheer.</red>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !alive.contains(target.getUniqueId())) {
            spectator.sendMessage(FormatUtil.mm(PREFIX + "<red>That player is not an active fighter.</red>"));
            return;
        }
        Bukkit.broadcast(FormatUtil.mm("<light_purple>" + FormatUtil.escape(spectator.getName())
                + " cheers for <yellow>" + FormatUtil.escape(target.getName()) + "</yellow>!</light_purple>"));
    }

    // -------------------------------------------------------------------------
    // Phase progression, Team Wars, & Tournament brackets
    // -------------------------------------------------------------------------

    private void transitionToPhase(String nextPhase) {
        if (!eventRunning || eventCompleted || nextPhase == null || nextPhase.equals(phase)) return;
        Arena arena = arenaManager.get(selectedArena);
        if (arena == null) return;
        List<Location> points = arenaManager.spawns(arena, nextPhase);
        if (points.isEmpty()) {
            broadcast("<red>Missing " + nextPhase + " spawns; remaining in " + phase + ".</red>");
            return;
        }
        phase = nextPhase;
        int index = 0;
        for (UUID id : new ArrayList<>(alive)) {
            Player fighter = Bukkit.getPlayer(id);
            if (fighter == null) continue;
            fighter.teleport(points.get(index++ % points.size()));
            resetVitals(fighter);
            lastMovementAt.put(id, System.currentTimeMillis());
        }
        applyPhaseBorder(phase);
        String label = nextPhase.equals("wave2") ? "WAVE 2" : "FINAL";
        broadcast("<yellow><bold>" + label + "</bold> phase started!</yellow> <green>" + alive.size() + " fighters remain.</green>");
    }

    private void advancePhaseIfNeeded() {
        if (!eventRunning || eventCompleted) return;
        int aliveCount = alive.size();
        if (aliveCount <= 1) {
            if (aliveCount == 1) finishWinner(alive.iterator().next());
            else stopEvent("Event ended with no winner");
            return;
        }
        if (phase.equals("final")) return;
        if (aliveCount == 2) {
            transitionToPhase("final");
            return;
        }
        if (phase.equals("wave1")) {
            int threshold = EventMode.calculateWave2Threshold(initialFighters);
            if (threshold != -1 && aliveCount <= threshold) transitionToPhase("wave2");
        }
    }

    private void assignTeams() {
        teams.clear();
        List<UUID> shuffled = new ArrayList<>(participants);
        Collections.shuffle(shuffled, random);
        int red = 0, blue = 0;
        for (UUID id : shuffled) {
            int team = red <= blue ? 1 : 2;
            teams.put(id, team);
            if (team == 1) red++; else blue++;
            Player member = Bukkit.getPlayer(id);
            if (member != null) {
                member.sendMessage(FormatUtil.mm(PREFIX + "<gray>You are on</gray> "
                        + (team == 1 ? "<red><bold>RED TEAM</bold></red>" : "<blue><bold>BLUE TEAM</bold></blue>") + "<gray>!</gray>"));
            }
        }
        broadcast("<yellow>Team Wars:</yellow> <red>Red (" + red + ")</red> <gray>vs</gray> <blue>Blue (" + blue + ")</blue><gray>!</gray>");
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
        if (!eventRunning || eventMode != EventMode.TEAM_WARS) return;
        Integer team = remainingTeam();
        if (team == null) {
            if (alive.isEmpty()) stopEvent("Team Wars ended in a draw");
            return;
        }
        if (eventCompleted) return;
        eventCompleted = true;
        String teamName = team == 1 ? "Red Team" : "Blue Team";
        Bukkit.broadcast(FormatUtil.mm(team == 1 ? "<red><bold>RED TEAM WINS!</bold></red>" : "<blue><bold>BLUE TEAM WINS!</bold></blue>"));

        for (UUID id : participants) {
            if (Objects.equals(teams.get(id), team)) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
                String name = offline.getName() != null ? offline.getName() : "Player";
                data.recordWin(id, name);
                runRewardCommands("team-winner", id, name);
                Player online = Bukkit.getPlayer(id);
                if (online != null) {
                    playCue(online, "winner");
                    spawnCelebrationFirework(online);
                }
            }
        }
        recordMatchSummary(teamName, teamName.toLowerCase(Locale.ROOT));
        stopEvent("Team Wars finished");
    }

    private void startTournament() {
        if (!eventOpen || participants.size() < 2) {
            broadcast("<red>Tournament requires at least 2 registered players.</red>");
            stopEvent("Tournament cancelled: not enough players");
            return;
        }
        eventOpen = false;
        eventRunning = true;
        paused = false;
        phase = "tournament";
        List<UUID> entrants = new ArrayList<>();
        for (UUID id : participants) {
            if (Bukkit.getPlayer(id) != null) entrants.add(id);
        }
        Collections.shuffle(entrants, random);
        if (entrants.size() < 2) {
            stopEvent("Tournament cancelled: players unavailable");
            return;
        }
        spectators.clear();
        for (UUID id : entrants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                snapshotIfNeeded(p);
                p.setGameMode(GameMode.SPECTATOR);
                spectators.add(id);
            }
        }
        tournamentQueue.clear();
        tournamentWinners.clear();
        tournamentRound = 1;
        if ((entrants.size() & 1) == 1) tournamentWinners.add(entrants.remove(entrants.size() - 1));
        for (int i = 0; i + 1 < entrants.size(); i += 2) {
            tournamentQueue.addLast(entrants.get(i));
            tournamentQueue.addLast(entrants.get(i + 1));
        }
        broadcast("<yellow>Tournament Round 1 begins with " + entrants.size() + " fighters!</yellow>");
        startNextTournamentMatch();
    }

    private void startNextTournamentMatch() {
        if (!eventRunning || eventMode != EventMode.TOURNAMENT) return;
        if (tournamentQueue.isEmpty()) {
            if (tournamentWinners.size() == 1) {
                finishWinner(tournamentWinners.get(0));
                return;
            }
            if (tournamentWinners.size() < 2) {
                stopEvent("Tournament ended without a champion");
                return;
            }
            List<UUID> next = new ArrayList<>(tournamentWinners);
            tournamentWinners.clear();
            Collections.shuffle(next, random);
            tournamentRound++;
            if ((next.size() & 1) == 1) tournamentWinners.add(next.remove(next.size() - 1));
            for (int i = 0; i + 1 < next.size(); i += 2) {
                tournamentQueue.addLast(next.get(i));
                tournamentQueue.addLast(next.get(i + 1));
            }
            broadcast("<yellow>Tournament Round " + tournamentRound + " begins!</yellow>");
        }
        if (tournamentQueue.size() < 2) {
            startNextTournamentMatch();
            return;
        }
        tournamentPlayerA = tournamentQueue.removeFirst();
        tournamentPlayerB = tournamentQueue.removeFirst();
        Player a = Bukkit.getPlayer(tournamentPlayerA);
        Player b = Bukkit.getPlayer(tournamentPlayerB);
        if (a == null || b == null) {
            UUID advancing = a != null ? tournamentPlayerA : b != null ? tournamentPlayerB : null;
            if (advancing != null) tournamentWinners.add(advancing);
            Bukkit.getScheduler().runTaskLater(plugin, this::startNextTournamentMatch, 20L);
            return;
        }

        alive.clear();
        alive.add(tournamentPlayerA);
        alive.add(tournamentPlayerB);
        spectators.remove(tournamentPlayerA);
        spectators.remove(tournamentPlayerB);

        Arena arena = arenaManager.get(selectedArena);
        List<Location> points = arena == null ? List.of() : arenaManager.spawns(arena, "final");
        if (points.size() < 2) {
            stopEvent("Tournament arena requires at least two final spawn points");
            return;
        }
        a.setGameMode(GameMode.SURVIVAL);
        b.setGameMode(GameMode.SURVIVAL);
        resetVitals(a);
        resetVitals(b);
        a.teleport(points.get(0));
        b.teleport(points.get(1));
        removeRulesBooks(a);
        removeRulesBooks(b);
        kitManager.giveKit(a, selectedKit);
        kitManager.giveKit(b, selectedKit);

        for (UUID id : participants) {
            if (!alive.contains(id)) {
                spectators.add(id);
                Player spec = Bukkit.getPlayer(id);
                if (spec != null) spec.setGameMode(GameMode.SPECTATOR);
            }
        }
        applyPhaseBorder("final");
        broadcast("<yellow>Round " + tournamentRound + ":</yellow> <green>" + FormatUtil.escape(a.getName())
                + "</green> <gray>vs</gray> <red>" + FormatUtil.escape(b.getName()) + "</red>");
    }

    private void completeTournamentMatch(UUID winnerId) {
        if (!eventRunning || eventMode != EventMode.TOURNAMENT) return;
        if (winnerId != null) {
            tournamentWinners.add(winnerId);
            Player winner = Bukkit.getPlayer(winnerId);
            broadcast("<yellow>" + FormatUtil.escape(winner == null ? "A player" : winner.getName())
                    + "</yellow> <gray>advances to the next round!</gray>");
        }
        if (tournamentPlayerA != null) {
            Player a = Bukkit.getPlayer(tournamentPlayerA);
            if (a != null) clearPlayerBorder(a);
            spectators.add(tournamentPlayerA);
        }
        if (tournamentPlayerB != null) {
            Player b = Bukkit.getPlayer(tournamentPlayerB);
            if (b != null) clearPlayerBorder(b);
            spectators.add(tournamentPlayerB);
        }
        alive.clear();
        tournamentPlayerA = null;
        tournamentPlayerB = null;
        Bukkit.getScheduler().runTaskLater(plugin, this::startNextTournamentMatch, 100L);
    }

    private void finishWinner(UUID winnerId) {
        if (eventCompleted || winnerId == null) return;
        eventCompleted = true;
        eventWinner = winnerId;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(winnerId);
        String winnerName = offline.getName() == null ? "Player" : offline.getName();

        int rewardPts = plugin.getConfig().getInt("audience-correct-prediction-points", 10);
        for (Map.Entry<UUID, UUID> vote : votes.entrySet()) {
            if (winnerId.equals(vote.getValue())) {
                String voterName = Bukkit.getOfflinePlayer(vote.getKey()).getName();
                data.addAudiencePoints(vote.getKey(), voterName, rewardPts);
                Player onlineVoter = Bukkit.getPlayer(vote.getKey());
                if (onlineVoter != null) {
                    onlineVoter.sendMessage(FormatUtil.mm(PREFIX + "<green>Your winner prediction was correct! +" + rewardPts + " audience points.</green>"));
                }
            }
        }
        data.recordWin(winnerId, winnerName);
        recordMatchSummary(winnerName, winnerId.toString());
        broadcast("<yellow><bold>" + FormatUtil.escape(winnerName) + "</bold></yellow> <green>won the event!</green>");
        runRewardCommands("winner", winnerId, winnerName);

        Player onlineWinner = Bukkit.getPlayer(winnerId);
        if (onlineWinner != null) {
            Location winnerLoc = configuredLocation("winner");
            if (winnerLoc != null) onlineWinner.teleport(winnerLoc);
            playCue(onlineWinner, "winner");
            spawnCelebrationFirework(onlineWinner);
        }
        stopEvent("Event finished");
    }

    private void recordMatchSummary(String winnerName, String winnerId) {
        UUID mvpId = matchKills.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        String mvpName = "None";
        int mvpKills = 0;
        if (mvpId != null) {
            Player onlineMvp = Bukkit.getPlayer(mvpId);
            mvpName = onlineMvp != null ? onlineMvp.getName() : Bukkit.getOfflinePlayer(mvpId).getName();
            if (mvpName == null) mvpName = "Unknown";
            mvpKills = matchKills.getOrDefault(mvpId, 0);
        }
        data.recordMatch(selectedArena, eventMode.id(), winnerName, winnerId, matchStartedAt, participants, mvpName, mvpKills);
        if (mvpKills > 0) {
            Bukkit.broadcast(FormatUtil.mm("<gold>[MVP]</gold> <yellow>" + FormatUtil.escape(mvpName)
                    + "</yellow> <gray>was match MVP with</gray> <yellow>" + mvpKills + "</yellow> <gray>kill(s)!</gray>"));
        }
    }

    private void runRewardCommands(String rewardType, UUID playerId, String playerName) {
        for (String raw : plugin.getConfig().getStringList("rewards." + rewardType + ".commands")) {
            String command = raw.replace("{player}", playerName == null ? "Unknown" : playerName)
                    .replace("{uuid}", playerId.toString())
                    .replace("{arena}", selectedArena)
                    .replace("{mode}", eventMode.id());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    // -------------------------------------------------------------------------
    // Status / AFK loop
    // -------------------------------------------------------------------------

    private void startStatusLoop() {
        statusTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!eventRunning) return;
            long now = System.currentTimeMillis();
            boolean afkEnabled = plugin.getConfig().getBoolean("afk.enabled", true);
            long afkLimit = Math.max(30L, plugin.getConfig().getLong("afk.eliminate-after-seconds", 300L)) * 1000L;

            if (afkEnabled) {
                for (UUID fighterId : new HashSet<>(alive)) {
                    Player fighter = Bukkit.getPlayer(fighterId);
                    if (fighter == null) continue;
                    long lastMove = lastMovementAt.getOrDefault(fighterId, now);
                    long lastCombat = lastCombatAt.getOrDefault(fighterId, 0L);
                    long lastActive = Math.max(lastMove, lastCombat);
                    if (now - lastActive >= afkLimit) {
                        alive.remove(fighterId);
                        spectators.add(fighterId);
                        fighter.setGameMode(GameMode.SPECTATOR);
                        teleportToConfigured(fighter, "spectator");
                        fighter.sendMessage(FormatUtil.mm(PREFIX + "<red>You were eliminated for being AFK.</red>"));
                        broadcast("<gray>" + FormatUtil.escape(fighter.getName()) + " was eliminated for being AFK.</gray>");
                        afkWarned.remove(fighterId);
                        processEliminationAfterDisconnect();
                    } else if (now - lastActive >= Math.max(60_000L, afkLimit - 60_000L) && afkWarned.add(fighterId)) {
                        fighter.sendMessage(FormatUtil.mm(PREFIX + "<yellow>AFK warning: move or fight within 60 seconds or you will be eliminated!</yellow>"));
                    }
                }
            }

            Component actionBar = FormatUtil.mm("<gold><bold>" + FormatUtil.escape(eventMode.displayName())
                    + "</bold></gold> <dark_gray>|</dark_gray> <yellow>" + alive.size() + " alive</yellow>"
                    + " <dark_gray>|</dark_gray> <green>" + phase.toUpperCase(Locale.ROOT) + "</green>");
            for (UUID id : participants) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer != null) viewer.sendActionBar(actionBar);
            }
        }, 40L, 40L);
    }

    // -------------------------------------------------------------------------
    // WorldBorder, Snapshots, Locations, Rules Book, Sounds
    // -------------------------------------------------------------------------

    private void applyPhaseBorder(String phaseName) {
        Arena arena = arenaManager.get(selectedArena);
        if (arena == null) return;
        double defaultSize = phaseName.equals("wave1") ? 140.0 : phaseName.equals("wave2") ? 80.0 : 35.0;
        double size = plugin.getConfig().getDouble("world-border." + phaseName + ".size", defaultSize);
        long shrinkSeconds = Math.max(0L, plugin.getConfig().getLong("world-border." + phaseName + ".shrink-seconds", 60L));
        for (UUID id : alive) {
            Player fighter = Bukkit.getPlayer(id);
            if (fighter == null) continue;
            WorldBorder border = Bukkit.createWorldBorder();
            border.setCenter(arena.pasteX(), arena.pasteZ());
            border.changeSize(size, shrinkSeconds * 20L);
            border.setWarningDistance(10);
            fighter.setWorldBorder(border);
        }
    }

    private void clearPlayerBorder(Player player) {
        player.setWorldBorder(null);
    }

    private void snapshotIfNeeded(Player player) {
        PlayerSnapshot snapshot = snapshots.computeIfAbsent(player.getUniqueId(), id -> new PlayerSnapshot(player));
        data.persistSnapshot(player.getUniqueId(), snapshot);
    }

    private void restoreAllPlayers() {
        for (Map.Entry<UUID, PlayerSnapshot> entry : new HashMap<>(snapshots).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && !player.isDead()) {
                clearPlayerBorder(player);
                Location dest = eventWinner != null && eventWinner.equals(entry.getKey())
                        ? configuredLocation("winner")
                        : lobbyLocation();
                entry.getValue().restore(player, dest != null ? dest : lobbyLocation());
                snapshots.remove(entry.getKey());
                data.removePersistedSnapshot(entry.getKey());
            }
        }
    }

    public Location configuredLocation(String key) {
        String path = "locations." + key;
        if (!plugin.getConfig().contains(path) && key.equals("lobby") && plugin.getConfig().contains("lobby")) {
            path = "lobby";
        }
        String worldName = plugin.getConfig().getString(path + ".world", arenaManager.sharedWorldName());
        World world = Bukkit.getWorld(worldName);
        if (world == null) world = new WorldCreator(worldName).createWorld();
        if (world == null) return null;
        return new Location(
                world,
                plugin.getConfig().getDouble(path + ".x"),
                plugin.getConfig().getDouble(path + ".y"),
                plugin.getConfig().getDouble(path + ".z"),
                (float) plugin.getConfig().getDouble(path + ".yaw"),
                (float) plugin.getConfig().getDouble(path + ".pitch")
        );
    }

    public Location lobbyLocation() {
        Location loc = configuredLocation("lobby");
        if (loc != null) return loc;
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public void setNamedLocation(Player player, String key) {
        String path = "locations." + key;
        Location loc = player.getLocation();
        plugin.getConfig().set(path + ".world", loc.getWorld().getName());
        plugin.getConfig().set(path + ".x", loc.getX());
        plugin.getConfig().set(path + ".y", loc.getY());
        plugin.getConfig().set(path + ".z", loc.getZ());
        plugin.getConfig().set(path + ".yaw", loc.getYaw());
        plugin.getConfig().set(path + ".pitch", loc.getPitch());
        plugin.saveConfig();
        player.sendMessage(FormatUtil.mm(PREFIX + "<green>Saved <yellow>" + key + "</yellow> location.</green>"));
    }

    private void teleportToConfigured(Player player, String key) {
        Location target = configuredLocation(key);
        if (target != null) player.teleport(target);
    }

    private void playCue(Player player, String cue) {
        String raw = plugin.getConfig().getString("sounds." + cue, "");
        Sound sound = FormatUtil.resolveSound(raw);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 0.7f, 1.0f);
        }
    }

    private void spawnCelebrationFirework(Player player) {
        World world = player.getWorld();
        Firework fw = world.spawn(player.getLocation(), Firework.class);
        var fm = fw.getFireworkMeta();
        fm.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.ORANGE, Color.PURPLE)
                .withFade(Color.WHITE)
                .trail(true)
                .build());
        fm.setPower(1);
        fw.setFireworkMeta(fm);
    }

    private void resetVitals(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = attr != null ? attr.getValue() : 20.0;
        player.setHealth(maxHp);
        player.setFoodLevel(20);
        player.setSaturation(5.0f);
        player.setFireTicks(0);
    }

    private void giveRulesBook(Player player) {
        if (!plugin.getConfig().getBoolean("rules-book.enabled", true)) return;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        if (!(book.getItemMeta() instanceof BookMeta meta)) return;
        meta.title(FormatUtil.mm("<gold>Clasher Events Rules</gold>"));
        meta.author(Component.text("Clasher Network"));
        meta.getPersistentDataContainer().set(rulesBookKey, PersistentDataType.BYTE, (byte) 1);

        List<String> rawPages = plugin.getConfig().getStringList("rules-book.pages");
        if (rawPages.isEmpty()) {
            rawPages = List.of(
                    "<gold><bold>CLASHER EVENTS</bold></gold>\n\n<black>Welcome! Read the rules before fighting.</black>",
                    "<gold><bold>RULES</bold></gold>\n\n<black>1. No teaming in solo modes.\n2. No exploits.\n3. Follow staff instructions.</black>"
            );
        }
        List<Component> pages = new ArrayList<>(rawPages.size());
        for (String page : rawPages) {
            pages.add(FormatUtil.parseFlexible(page));
        }
        meta.pages(pages);
        book.setItemMeta(meta);
        player.getInventory().addItem(book);
        player.openBook(book);
        playCue(player, "rules-book");
    }

    private boolean isRulesBook(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(rulesBookKey, PersistentDataType.BYTE);
    }

    private void removeRulesBooks(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isRulesBook(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private boolean inWaitingLobby(Player player) {
        if (eventOpen && participants.contains(player.getUniqueId()) && !alive.contains(player.getUniqueId())) {
            return true;
        }
        Location lobby = configuredLocation("lobby");
        if (lobby == null || lobby.getWorld() == null || !player.getWorld().equals(lobby.getWorld())) {
            return false;
        }
        double radius = plugin.getConfig().getDouble("lobby-protection.radius", 35.0);
        return player.getLocation().distanceSquared(lobby) <= radius * radius;
    }

    private void broadcast(String bodyMini) {
        Bukkit.broadcast(FormatUtil.mm(PREFIX + bodyMini));
    }

    // -------------------------------------------------------------------------
    // Listeners: Combat, Spectator Chat, Lobby Protection, Death/Respawn, Join/Quit
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpectatorChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // CRITICAL: Only intercept chat for active event spectators; never touch normal server chat!
        if (!spectators.contains(id)) return;
        event.setCancelled(true);
        String name = event.getPlayer().getName();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Component formatted = FormatUtil.mm("<dark_gray>[SPECTATOR]</dark_gray> <gray>"
                + FormatUtil.escape(name) + "</gray><dark_gray>:</dark_gray> <white>" + FormatUtil.escape(text) + "</white>");
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (UUID specId : spectators) {
                Player target = Bukkit.getPlayer(specId);
                if (target != null) target.sendMessage(formatted);
            }
        });
    }

    @EventHandler
    public void onTeamFriendlyFire(EntityDamageByEntityEvent event) {
        if (!eventRunning || !(event.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player direct) attacker = direct;
        else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player shooter) attacker = shooter;
        if (attacker == null) return;

        lastDamager.put(victim.getUniqueId(), attacker.getUniqueId());
        long now = System.currentTimeMillis();
        lastCombatAt.put(victim.getUniqueId(), now);
        lastMovementAt.put(attacker.getUniqueId(), now);
        lastMovementAt.put(victim.getUniqueId(), now);
        afkWarned.remove(victim.getUniqueId());
        afkWarned.remove(attacker.getUniqueId());

        Integer teamA = teams.get(attacker.getUniqueId());
        Integer teamV = teams.get(victim.getUniqueId());
        if (teamA != null && teamA.equals(teamV)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onFighterMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        lastMovementAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onLobbyDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && (inWaitingLobby(player) || (eventOpen && participants.contains(player.getUniqueId()) && !eventRunning))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onLobbyFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
                && (inWaitingLobby(player) || (eventOpen && participants.contains(player.getUniqueId()) && !eventRunning))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onLobbyBreak(BlockBreakEvent event) {
        if (!event.getPlayer().hasPermission("clasherevents.admin") && inWaitingLobby(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onLobbyPlace(BlockPlaceEvent event) {
        if (!event.getPlayer().hasPermission("clasherevents.admin") && inWaitingLobby(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onLobbyDrop(PlayerDropItemEvent event) {
        if (!event.getPlayer().hasPermission("clasherevents.admin") && inWaitingLobby(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onLobbyInteract(PlayerInteractEvent event) {
        if (event.getPlayer().hasPermission("clasherevents.admin")) return;
        if (isRulesBook(event.getItem())) return; // Allow opening the rules book in the waiting lobby!
        if (inWaitingLobby(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onLobbyPickup(PlayerAttemptPickupItemEvent event) {
        if (!event.getPlayer().hasPermission("clasherevents.admin") && inWaitingLobby(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onLobbyInventory(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof EventMenuHolder) return;
        if (event.getWhoClicked() instanceof Player player
                && !player.hasPermission("clasherevents.admin")
                && inWaitingLobby(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onLobbyInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof EventMenuHolder) return;
        if (event.getWhoClicked() instanceof Player player
                && !player.hasPermission("clasherevents.admin")
                && inWaitingLobby(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPausedDamage(EntityDamageEvent event) {
        if (paused && event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onUhcNaturalRegen(EntityRegainHealthEvent event) {
        if (eventRunning && eventMode == EventMode.UHC
                && event.getEntity() instanceof Player
                && event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID id = victim.getUniqueId();
        if (!alive.remove(id)) return;
        spectators.add(id);

        UUID killerId = lastDamager.remove(id);
        lastCombatAt.remove(id);
        if (killerId != null) {
            Player killer = Bukkit.getPlayer(killerId);
            if (killer != null && !killer.getUniqueId().equals(id)) {
                data.recordKill(killer.getUniqueId(), killer.getName(), id, victim.getName());
                matchKills.merge(killer.getUniqueId(), 1, Integer::sum);
                Bukkit.broadcast(FormatUtil.mm("<dark_gray>[<red>KILL</red>]</dark_gray> <yellow>"
                        + FormatUtil.escape(killer.getName()) + "</yellow> <gray>eliminated</gray> <yellow>"
                        + FormatUtil.escape(victim.getName()) + "</yellow>"));
            } else {
                data.recordDeath(id, victim.getName());
            }
        } else {
            data.recordDeath(id, victim.getName());
        }

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        broadcast("<gray>" + FormatUtil.escape(victim.getName()) + " has been eliminated!</gray> <yellow>" + alive.size() + " fighters remain.</yellow>");

        if (eventMode == EventMode.TOURNAMENT) {
            UUID winnerId = alive.isEmpty() ? null : alive.iterator().next();
            Bukkit.getScheduler().runTask(plugin, () -> completeTournamentMatch(winnerId));
            return;
        }
        if (eventMode == EventMode.TEAM_WARS) {
            checkTeamWinner();
            return;
        }
        if (alive.size() == 1) {
            UUID winnerId = alive.iterator().next();
            Bukkit.getScheduler().runTask(plugin, () -> finishWinner(winnerId));
        } else if (alive.isEmpty()) {
            stopEvent("Event ended with no winner");
        } else {
            advancePhaseIfNeeded();
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!participants.contains(id)) {
            PlayerSnapshot pending = snapshots.remove(id);
            if (pending != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    pending.restore(event.getPlayer(), lobbyLocation());
                    data.removePersistedSnapshot(id);
                });
            }
            return;
        }
        Arena arena = arenaManager.get(selectedArena);
        Location spectator = configuredLocation("spectator");
        if (spectator == null && arena != null) spectator = arenaManager.firstSpawn(arena, "audience");
        if (spectator != null) event.setRespawnLocation(spectator);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = event.getPlayer();
            if (participants.contains(id) && !alive.contains(id)) {
                p.setGameMode(GameMode.SPECTATOR);
                spectators.add(id);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (disconnectedFighters.contains(id) && eventRunning) {
            BukkitTask timer = disconnectTasks.remove(id);
            if (timer != null) timer.cancel();
            disconnectedFighters.remove(id);
            participants.add(id);
            alive.add(id);
            spectators.remove(id);
            lastMovementAt.put(id, System.currentTimeMillis());
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = event.getPlayer();
                Arena arena = arenaManager.get(selectedArena);
                List<Location> points = arena == null ? List.of() : arenaManager.spawns(arena, phase.equals("final") ? "final" : phase);
                if (!points.isEmpty()) player.teleport(points.get(random.nextInt(points.size())));
                player.setGameMode(GameMode.SURVIVAL);
                player.sendMessage(FormatUtil.mm(PREFIX + "<green>Reconnected! Your event state has been restored (no fresh kit issued).</green>"));
                applyPhaseBorder(phase);
            });
            return;
        }
        PlayerSnapshot pending = data.loadPersistedSnapshot(id);
        if (pending == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) {
                pending.restore(player, lobbyLocation());
                data.removePersistedSnapshot(id);
                snapshots.remove(id);
                participants.remove(id);
                alive.remove(id);
                spectators.remove(id);
                player.sendMessage(FormatUtil.mm(PREFIX + "<green>Your inventory and player state were recovered from the previous event session.</green>"));
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clearPlayerBorder(event.getPlayer());
        if (eventRunning && alive.contains(id)) {
            long graceSec = Math.max(10L, plugin.getConfig().getLong("disconnect-grace-seconds", 60L));
            disconnectedFighters.add(id);
            broadcast("<yellow>" + FormatUtil.escape(event.getPlayer().getName()) + " disconnected! " + graceSec + "s to reconnect.</yellow>");
            BukkitTask old = disconnectTasks.remove(id);
            if (old != null) old.cancel();
            disconnectTasks.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
                disconnectTasks.remove(id);
                disconnectedFighters.remove(id);
                participants.remove(id);
                spectators.remove(id);
                if (alive.remove(id)) {
                    broadcast("<red>" + FormatUtil.escape(event.getPlayer().getName()) + " failed to reconnect and was eliminated.</red>");
                    processEliminationAfterDisconnect();
                }
            }, graceSec * 20L));
        } else if (eventOpen && participants.remove(id)) {
            broadcast("<gray>" + FormatUtil.escape(event.getPlayer().getName()) + " left the registration.</gray>");
        }
    }

    private void processEliminationAfterDisconnect() {
        if (eventMode == EventMode.TOURNAMENT) {
            UUID winnerId = alive.isEmpty() ? null : alive.iterator().next();
            Bukkit.getScheduler().runTask(plugin, () -> completeTournamentMatch(winnerId));
        } else if (eventMode == EventMode.TEAM_WARS) {
            checkTeamWinner();
        } else if (alive.size() == 1) {
            UUID winnerId = alive.iterator().next();
            Bukkit.getScheduler().runTask(plugin, () -> finishWinner(winnerId));
        } else if (alive.isEmpty()) {
            stopEvent("Event ended with no winner");
        } else {
            advancePhaseIfNeeded();
        }
    }
}
