package network.clasher.events.match;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import network.clasher.events.arena.ArenaBlockTracker;
import network.clasher.events.arena.ArenaManager;
import network.clasher.events.gui.EventGui;
import network.clasher.events.gui.EventMenuHolder;
import network.clasher.events.hud.EventHudManager;
import network.clasher.events.hud.HologramManager;
import network.clasher.events.kit.KitManager;
import network.clasher.events.model.Arena;
import network.clasher.events.model.EventMode;
import network.clasher.events.model.PlayerSnapshot;
import network.clasher.events.notify.DiscordWebhookNotifier;
import network.clasher.events.stats.EloCalculator;
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
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * Core PvP event lifecycle engine (v2.0): auto-pasting arenas, tick-batched rollback,
 * mode voting, tournament brackets, Team Wars, strict command/container shields,
 * bounds-centered WorldBorders, celebration podiums, live HUD, Elo stats, and Discord embeds.
 */
public final class EventManager implements Listener {
    private static final String PREFIX = "<gold><bold>CLASHER EVENTS</bold></gold> <dark_gray>»</dark_gray> ";
    private static final Set<InventoryType> BLOCKED_CONTAINERS = EnumSet.of(
            InventoryType.ENDER_CHEST,
            InventoryType.SHULKER_BOX,
            InventoryType.CHEST,
            InventoryType.BARREL,
            InventoryType.HOPPER,
            InventoryType.DISPENSER,
            InventoryType.DROPPER
    );

    private final JavaPlugin plugin;
    private final ArenaManager arenaManager;
    private final KitManager kitManager;
    private final DataStore data;
    private final ArenaBlockTracker blockTracker;
    private final EventHudManager hud;
    private final DiscordWebhookNotifier discord;
    private final NamespacedKey rulesBookKey;
    private final NamespacedKey lobbyItemKey;
    private final Random random = new Random();
    private EventGui gui;
    private HologramManager holograms;
    private CasualDuelManager casualDuels;

    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, UUID> votes = new HashMap<>();
    private final Map<UUID, String> modeVotes = new LinkedHashMap<>();
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
    private static final EnumSet<PlayerTeleportEvent.TeleportCause> GUARDED_TP_CAUSES = EnumSet.of(
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
            PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT,
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_GATEWAY,
            PlayerTeleportEvent.TeleportCause.SPECTATE
    );

    private final Map<UUID, Long> lastBoundaryWarnAt = new HashMap<>();
    private final Map<UUID, Integer> matchKills = new HashMap<>();
    private final Map<UUID, Double> matchDamageDealt = new HashMap<>();
    private final List<UUID> eliminationOrder = new ArrayList<>();
    private final Map<UUID, Long> parkourStartedAt = new HashMap<>();

    private String selectedArena = "nethpot";
    private String selectedKit = "nethpot";
    private EventMode eventMode = EventMode.NETHPOT;
    private boolean eventOpen;
    private boolean eventRunning;
    private boolean celebrating;
    private boolean paused;
    private boolean gracePeriodActive;
    private String phase = "idle";
    private int countdownRemaining;
    private int countdownInitial = 120;
    private int initialFighters;
    private UUID tournamentPlayerA;
    private UUID tournamentPlayerB;
    private int tournamentRound = 1;
    private boolean eventCompleted;
    private boolean firstBloodClaimed;
    private UUID eventWinner;
    private long matchStartedAt;

    // Active WorldBorder tracking so late-joiners/reconnectors never reset shrink progress & border deals REAL damage!
    private double activeBorderStartSize = 140.0;
    private double activeBorderTargetSize = 84.0;
    private long activeBorderStartMs = 0L;
    private long activeBorderEndMs = 0L;

    // Pre-event Mode Vote state
    private boolean modeVoteOpen;
    private int modeVoteRemaining;
    private BukkitTask modeVoteTask;

    private BukkitTask countdownTask;
    private BukkitTask statusTask;
    private BukkitTask celebrationTask;
    private BukkitTask graceTask;

    public EventManager(
            JavaPlugin plugin,
            ArenaManager arenaManager,
            KitManager kitManager,
            DataStore data,
            ArenaBlockTracker blockTracker,
            EventHudManager hud,
            DiscordWebhookNotifier discord
    ) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.kitManager = kitManager;
        this.data = data;
        this.blockTracker = blockTracker;
        this.hud = hud;
        this.discord = discord;
        this.rulesBookKey = new NamespacedKey(plugin, "rules-book");
        this.lobbyItemKey = new NamespacedKey(plugin, "lobby-item");
        startStatusLoop();
    }

    public void setGui(EventGui gui) {
        this.gui = gui;
    }

    public void setHolograms(HologramManager holograms) {
        this.holograms = holograms;
    }

    public void setCasualDuels(CasualDuelManager casualDuels) {
        this.casualDuels = casualDuels;
    }

    public CasualDuelManager casualDuels() {
        return casualDuels;
    }

    public void refreshHologramsSafe() {
        if (holograms == null) return;
        String summary = null;
        if (eventOpen) {
            summary = "<green>OPEN: " + eventMode.displayName() + " (" + participants.size() + " joined)</green>";
        } else if (eventRunning) {
            summary = "<red>LIVE: " + eventMode.displayName() + " (" + alive.size() + " alive)</red>";
        }
        holograms.refreshAll(summary);
    }

    public void shutdown() {
        cancelCountdown();
        cancelModeVote();
        cancelCelebration();
        cancelGrace();
        if (statusTask != null) {
            statusTask.cancel();
            statusTask = null;
        }
        if (blockTracker.isTracking()) {
            blockTracker.rollbackImmediate();
        }
        hud.clearAll();
        stopEventInternal("Server shutting down", true);
    }

    // -------------------------------------------------------------------------
    // Read-only state accessors
    // -------------------------------------------------------------------------

    public boolean isEventOpen() { return eventOpen; }
    public boolean isEventRunning() { return eventRunning; }
    public boolean isCelebrating() { return celebrating; }
    public boolean isPaused() { return paused; }
    public boolean isModeVoteOpen() { return modeVoteOpen; }
    public int modeVoteRemaining() { return modeVoteRemaining; }
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
    public boolean isArenaInUse(String arenaId) { return (eventOpen || eventRunning || celebrating) && selectedArena.equalsIgnoreCase(arenaId); }
    public DataStore data() { return data; }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    // -------------------------------------------------------------------------
    // Pre-Event Player Mode Voting (/ce voteopen & /event modevote)
    // -------------------------------------------------------------------------

    public List<EventMode> modeVoteOptions() {
        List<String> raw = plugin.getConfig().getStringList("mode-vote.options");
        List<EventMode> modes = new ArrayList<>();
        for (String id : raw) {
            EventMode.fromInput(id).ifPresent(m -> {
                if (!modes.contains(m)) modes.add(m);
            });
        }
        if (modes.isEmpty()) {
            modes.addAll(List.of(EventMode.NETHPOT, EventMode.CRYSTAL, EventMode.BUILD_UHC, EventMode.SWORD, EventMode.AXE_SHIELD, EventMode.DIAMOND_POT, EventMode.TEAM_WARS, EventMode.TOURNAMENT));
        }
        return modes;
    }

    public Map<String, Integer> modeVoteCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EventMode m : modeVoteOptions()) counts.put(m.id(), 0);
        for (String voted : modeVotes.values()) {
            counts.merge(voted, 1, Integer::sum);
        }
        return counts;
    }

    public void startModeVote(int seconds) {
        if (eventOpen || eventRunning || celebrating) {
            broadcast("<red>Cannot start mode voting while an event is already active.</red>");
            return;
        }
        if (casualDuels != null) casualDuels.forceEndAllForOfficialEvent();
        cancelModeVote();
        modeVotes.clear();
        modeVoteOpen = true;
        modeVoteRemaining = Math.max(15, seconds);
        broadcast("<aqua><bold>MODE VOTING OPEN!</bold></aqua> <gray>Vote for the next PvP event mode within</gray> <yellow>"
                + FormatUtil.formatDuration(modeVoteRemaining) + "</yellow><gray>:</gray> "
                + "<green><click:run_command:'/event modevote'><hover:show_text:'<green>Click to vote for the next mode!</green>'>[/event modevote]</hover></click></green>");
        discord.notifyModeVoteOpen(modeVoteRemaining);

        modeVoteTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!modeVoteOpen) return;
            if (modeVoteRemaining <= 0) {
                finishModeVote();
                return;
            }
            if (modeVoteRemaining == 15 || modeVoteRemaining <= 5) {
                broadcast("<aqua>Mode voting ends in</aqua> <yellow><bold>" + modeVoteRemaining + "s</bold></yellow><aqua>! Use /event modevote</aqua>");
            }
            modeVoteRemaining--;
        }, 0L, 20L);
    }

    public void castModeVote(Player player, String rawModeId) {
        if (!modeVoteOpen) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Mode voting is not currently open.</red>"));
            return;
        }
        Optional<EventMode> parsed = EventMode.fromInput(rawModeId);
        if (parsed.isEmpty() || !modeVoteOptions().contains(parsed.get())) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>That mode is not in the current vote pool.</red>"));
            return;
        }
        modeVotes.put(player.getUniqueId(), parsed.get().id());
        playCue(player, "join");
        player.sendMessage(FormatUtil.mm(PREFIX + "<green>Voted for</green> <yellow><bold>" + FormatUtil.escape(parsed.get().displayName()) + "</bold></yellow><green>!</green>"));
    }

    private void finishModeVote() {
        cancelModeVote();
        Map<String, Integer> counts = modeVoteCounts();
        int best = -1;
        List<String> leaders = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                leaders.clear();
                leaders.add(e.getKey());
            } else if (e.getValue() == best) {
                leaders.add(e.getKey());
            }
        }
        String winningMode = leaders.isEmpty() ? "nethpot" : leaders.get(random.nextInt(leaders.size()));
        modeVotes.clear();
        broadcast("<green>Mode vote finished! Winning mode:</green> <gold><bold>" + FormatUtil.escape(winningMode.toUpperCase(Locale.ROOT)) + "</bold></gold> <gray>(" + Math.max(0, best) + " votes)</gray>");
        createEvent(winningMode);
    }

    private void cancelModeVote() {
        modeVoteOpen = false;
        if (modeVoteTask != null) {
            modeVoteTask.cancel();
            modeVoteTask = null;
        }
    }

    // -------------------------------------------------------------------------
    // Event creation, registration countdown, start & stop
    // -------------------------------------------------------------------------

    public void createEvent(String rawMode) {
        createEvent(rawMode, null);
    }

    /**
     * Opens registration for an official Server Event, with optional kit/arena override
     * for 1v1 Duel, Team Wars, and Tournament modes (e.g. `/ce create duel sword`, `/ce create team-wars crystal`).
     * Automatically terminates any active casual player duels so admins NEVER face busy arenas!
     */
    public void createEvent(String rawMode, String kitOverride) {
        Optional<EventMode> parsed = EventMode.fromInput(rawMode);
        if (parsed.isEmpty()) {
            broadcast("<red>Unknown event mode: " + FormatUtil.escape(rawMode) + "</red>");
            return;
        }
        if (casualDuels != null) casualDuels.forceEndAllForOfficialEvent();
        cancelModeVote();
        stopEvent("Replaced by a new event");
        eventMode = parsed.get();
        if (eventMode == EventMode.RANDOM_KIT) {
            List<String> keys = new ArrayList<>(arenaManager.arenaIds());
            if (keys.isEmpty()) {
                broadcast("<red>No arenas are configured for Random Kit mode.</red>");
                return;
            }
            selectedArena = keys.get(random.nextInt(keys.size()));
        } else if (kitOverride != null && !kitOverride.isBlank()) {
            String resolved = EventMode.fromInput(kitOverride)
                    .map(EventMode::defaultArenaId)
                    .orElse(kitOverride.trim().toLowerCase(Locale.ROOT));
            selectedArena = (resolved != null && arenaManager.exists(resolved)) ? resolved : eventMode.defaultArenaId();
        } else {
            selectedArena = eventMode.defaultArenaId();
        }
        selectedKit = selectedArena;

        if (!arenaManager.exists(selectedArena) || !kitManager.exists(selectedKit)) {
            broadcast("<red>Missing arena or kit configuration for " + FormatUtil.escape(selectedArena) + ".</red>");
            return;
        }

        // Plug-and-play: ensure arena, Spawn Lobby, and Winner Podium are pasted automatically!
        arenaManager.ensureArenaReady(selectedArena, null, null);
        arenaManager.ensureLobbyReady(null);
        arenaManager.ensureWinnerPodiumReady(null);
        arenaManager.setArenaBuildAllowed(selectedArena, isDestructibleCurrentMatch());

        eventCompleted = false;
        firstBloodClaimed = false;
        celebrating = false;
        gracePeriodActive = false;
        eventWinner = null;
        matchKills.clear();
        matchDamageDealt.clear();
        eliminationOrder.clear();
        matchStartedAt = System.currentTimeMillis();
        eventOpen = true;
        eventRunning = false;
        paused = false;
        phase = "registration";
        clearCollections();
        countdownRemaining = Math.max(10, plugin.getConfig().getInt("default-registration-seconds", 120));
        countdownInitial = countdownRemaining;

        Arena arena = arenaManager.get(selectedArena);
        String arenaLabel = arena != null ? arena.displayName() : selectedArena;
        broadcast("<yellow>" + FormatUtil.escape(eventMode.displayName()) + "</yellow> <gray>(</gray><white>"
                + FormatUtil.escape(arenaLabel) + "</white><gray>) registration is open for</gray> <yellow>"
                + FormatUtil.formatDuration(countdownRemaining) + "</yellow><gray>! Use</gray> "
                + "<green><click:run_command:'/event join'><hover:show_text:'<green>Click to join!</green>'>[/event join]</hover></click></green>");
        discord.notifyEventOpen(eventMode.displayName(), arenaLabel, countdownRemaining);
        refreshHologramsSafe();
        startCountdown();
    }

    public void setCountdownSeconds(int seconds) {
        if (seconds <= 0 || !eventOpen) return;
        countdownRemaining = seconds;
        countdownInitial = Math.max(seconds, 1);
        plugin.getConfig().set("default-registration-seconds", seconds);
        plugin.saveConfig();
        startCountdown();
    }

    /**
     * Admin time-skip helper:
     * - If Mode Voting is open: reduces vote timer by `seconds` (or ends immediately if seconds <= 0 / exceeds remaining).
     * - If Registration is open: reduces countdown by `seconds`, or skips cleanly to 5s (default) / instant start (0).
     *   Ensures fighter kits are still delivered if skipping past the kit-delivery mark!
     */
    public void skipTimer(CommandSender sender, Integer secondsToSkip, boolean instant) {
        if (modeVoteOpen) {
            if (instant || secondsToSkip == null || modeVoteRemaining - secondsToSkip <= 0) {
                if (sender != null) sender.sendMessage(FormatUtil.mm(PREFIX + "<green>Skipped remaining mode-vote timer — tallying votes now!</green>"));
                finishModeVote();
            } else {
                modeVoteRemaining = Math.max(1, modeVoteRemaining - secondsToSkip);
                broadcast("<yellow>Admin skipped " + secondsToSkip + "s! Mode voting ends in <gold>" + FormatUtil.formatDuration(modeVoteRemaining) + "</gold>.</yellow>");
            }
            return;
        }
        if (!eventOpen) {
            if (sender != null) sender.sendMessage(FormatUtil.mm(PREFIX + "<red>No registration countdown or mode vote is currently running.</red>"));
            return;
        }

        int targetRemaining;
        if (instant) {
            targetRemaining = 0;
        } else if (secondsToSkip == null) {
            // Default `/ce skip`: jump cleanly to 5s hype countdown (or 0 if already <= 5s)
            targetRemaining = countdownRemaining > 5 ? 5 : 0;
        } else {
            targetRemaining = Math.max(0, countdownRemaining - secondsToSkip);
        }

        int kitAt = plugin.getConfig().getInt("kit-delivery-seconds-before-start", 60);
        if (targetRemaining <= kitAt && !participants.isEmpty()) {
            deliverPendingKits();
        }

        if (targetRemaining <= 0) {
            cancelCountdown();
            broadcast("<yellow>Admin skipped the remaining countdown — starting event now!</yellow>");
            startEvent();
        } else {
            countdownRemaining = targetRemaining;
            broadcast("<yellow>Admin skipped countdown! Event starts in</yellow> <gold><bold>" + FormatUtil.formatDuration(countdownRemaining) + "</bold></gold><yellow>!</yellow>");
        }
    }

    private void giveKitWithPlayerLayout(Player player, String kitId) {
        kitManager.giveKit(player, kitId, data.kitLayout(player.getUniqueId(), kitId));
    }

    private void deliverPendingKits() {
        boolean deliveredAny = false;
        for (UUID id : participants) {
            if (kitDelivered.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                snapshotIfNeeded(p);
                removeRulesBooks(p);
                giveKitWithPlayerLayout(p, selectedKit);
                kitDelivered.add(id);
                playCue(p, "kit");
                p.sendMessage(FormatUtil.mm(PREFIX + "<green>Your event kit has been delivered! Combat begins when the countdown ends.</green>"));
                deliveredAny = true;
            }
        }
        if (deliveredAny) {
            broadcast("<green>Fighter kits delivered!</green>");
        }
    }

    private void startCountdown() {
        cancelCountdown();
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!eventOpen || paused) return;
            int kitAt = plugin.getConfig().getInt("kit-delivery-seconds-before-start", 60);
            if (countdownRemaining == kitAt && !participants.isEmpty()) {
                deliverPendingKits();
            }
            if (countdownRemaining <= 0) {
                int minPlayers = Math.max(1, plugin.getConfig().getInt("registration.min-players", 2));
                if (participants.size() < minPlayers && eventMode != EventMode.DUEL) {
                    countdownRemaining = 30;
                    countdownInitial = 30;
                    broadcast("<yellow>Waiting for at least <gold>" + minPlayers + "</gold> fighters (" + participants.size()
                            + "/" + minPlayers + ") — registration extended by 30s! Use <green>/event join</green>.</yellow>");
                    return;
                }
                cancelCountdown();
                startEvent();
                return;
            }
            if (countdownRemaining <= 5) {
                hud.broadcastTitle(participants, "<gold><bold>" + countdownRemaining + "</bold></gold>", "<gray>Prepare for battle...</gray>", 50, 750, 150);
                for (UUID id : participants) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) playCue(p, "join");
                }
            }
            if (countdownRemaining <= 10 || countdownRemaining % 30 == 0) {
                broadcast("<yellow>Event starts in</yellow> <gold><bold>" + FormatUtil.formatDuration(countdownRemaining) + "</bold></gold><yellow>.</yellow>");
            }
            float progress = countdownInitial > 0 ? (float) countdownRemaining / countdownInitial : 0f;
            hud.updateBossBar("<gold><bold>" + FormatUtil.escape(eventMode.displayName()) + "</bold></gold> <gray>• Starts in</gray> <yellow>"
                    + FormatUtil.formatDuration(countdownRemaining) + "</yellow> <gray>(" + participants.size() + " joined)</gray>",
                    progress, BossBar.Color.YELLOW, participants);
            countdownRemaining--;
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void cancelCelebration() {
        celebrating = false;
        if (celebrationTask != null) {
            celebrationTask.cancel();
            celebrationTask = null;
        }
    }

    private void cancelGrace() {
        gracePeriodActive = false;
        if (graceTask != null) {
            graceTask.cancel();
            graceTask = null;
        }
    }

    private void startGracePeriod(Set<UUID> targets, String subtitleText) {
        cancelGrace();
        int graceSec = Math.max(0, Math.min(10, plugin.getConfig().getInt("effects.grace-seconds", 3)));
        if (graceSec <= 0) {
            hud.broadcastTitle(targets, "<green><bold>FIGHT!</bold></green>", "<yellow>" + FormatUtil.escape(subtitleText) + "</yellow>", 100, 1000, 300);
            return;
        }
        gracePeriodActive = true;
        int[] remain = {graceSec};
        graceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!eventRunning || celebrating) {
                cancelGrace();
                return;
            }
            if (remain[0] <= 0) {
                cancelGrace();
                hud.broadcastTitle(targets, "<green><bold>FIGHT!</bold></green>", "<yellow>" + FormatUtil.escape(subtitleText) + "</yellow>", 50, 900, 250);
                for (UUID id : targets) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) playCue(p, "start");
                }
                return;
            }
            hud.broadcastTitle(targets, "<gold><bold>" + remain[0] + "</bold></gold>", "<gray>Grace period — prepare to fight!</gray>", 50, 750, 150);
            for (UUID id : targets) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) playCue(p, "join");
            }
            remain[0]--;
        }, 0L, 20L);
    }

    private void applyFinalDuelGlowIfEnabled() {
        if (!plugin.getConfig().getBoolean("effects.final-duel-glow", true)) return;
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 300, 0, false, false, false));
            }
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
        matchStartedAt = System.currentTimeMillis();
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
        blockTracker.startTracking(arenaManager.sharedWorldName(), arena);

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
                giveKitWithPlayerLayout(fighter, selectedKit);
                kitDelivered.add(id);
            }
        }
        if (eventMode == EventMode.TEAM_WARS) assignTeams();
        applyPhaseBorder(phase);
        if (phase.equals("final")) applyFinalDuelGlowIfEnabled();
        startGracePeriod(alive, arena.displayName());
        refreshHologramsSafe();
        broadcast("<green>" + FormatUtil.escape(arena.displayName()) + " has started!</green> <yellow>Fighters: " + alive.size() + "</yellow>");

        if (eventMode == EventMode.TEAM_WARS) checkTeamWinner();
        else advancePhaseIfNeeded();
    }

    public void stopEvent(String reason) {
        stopEventInternal(reason, false);
    }

    private void stopEventInternal(String reason, boolean immediateShutdown) {
        cancelCountdown();
        cancelCelebration();
        cancelGrace();
        if (!eventOpen && !eventRunning && !celebrating && participants.isEmpty() && snapshots.isEmpty()) return;

        String arenaToClean = selectedArena;
        restoreAllPlayers();
        hud.clearAll();
        arenaManager.setArenaBuildAllowed(arenaToClean, false);
        clearCollections();
        eventOpen = false;
        eventRunning = false;
        celebrating = false;
        paused = false;
        gracePeriodActive = false;
        phase = "idle";
        eventWinner = null;

        // Zero-lag automatic arena rollback & entity cleanup!
        boolean autoReset = plugin.getConfig().getBoolean("arenas.auto-reset-after-match", true);
        if (autoReset) {
            if (immediateShutdown) {
                blockTracker.rollbackImmediate();
                arenaManager.clearArenaEntities(arenaToClean);
            } else {
                int batch = Math.max(100, plugin.getConfig().getInt("arenas.rollback-blocks-per-tick", 300));
                int tracked = blockTracker.trackedCount();
                blockTracker.rollbackBatched(plugin, batch, () -> {
                    arenaManager.clearArenaEntities(arenaToClean);
                    if (tracked > 0) {
                        plugin.getLogger().info("Auto-restored " + tracked + " modified blocks in arena '" + arenaToClean + "'.");
                    }
                });
            }
        } else {
            blockTracker.snapshotAndClear();
        }

        if (!Bukkit.getOnlinePlayers().isEmpty() && !"Server shutting down".equals(reason)) {
            broadcast("<gray>" + FormatUtil.escape(reason) + ".</gray>");
        }
        if (!immediateShutdown) refreshHologramsSafe();
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
        lastBoundaryWarnAt.clear();
        matchDamageDealt.clear();
        eliminationOrder.clear();
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
        int maxPlayers = Math.max(2, plugin.getConfig().getInt("registration.max-players", 32));
        if (eventMode == EventMode.DUEL) maxPlayers = 2;
        if (!participants.contains(player.getUniqueId()) && participants.size() >= maxPlayers) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>This event is currently full (" + maxPlayers + "/" + maxPlayers + ")!</red>"));
            return;
        }
        if (!participants.add(player.getUniqueId())) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<yellow>You are already registered for this event!</yellow>"));
            return;
        }
        parkourStartedAt.remove(player.getUniqueId());
        snapshotIfNeeded(player);
        if (isDestructibleCurrentMatch()) {
            arenaManager.addArenaBuilder(selectedArena, player.getUniqueId());
        }
        player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        Runnable finishJoinTp = () -> {
            if (player.isOnline() && participants.contains(player.getUniqueId()) && eventOpen) {
                Location waitLoc = activeWaitingLocation();
                if (waitLoc != null) {
                    player.setFallDistance(0f);
                    player.teleport(waitLoc);
                }
                giveLobbyHotbar(player);
            }
        };
        if (isUsingPerArenaWaiting()) {
            arenaManager.ensureArenaReady(selectedArena, null, finishJoinTp);
        } else {
            arenaManager.ensureLobbyReady(finishJoinTp);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && participants.contains(player.getUniqueId()) && eventOpen) {
                giveRulesBook(player);
            }
        }, 40L);
        playCue(player, "join");
        broadcast("<yellow>" + FormatUtil.escape(player.getName()) + "</yellow> <gray>joined the event!</gray> <dark_gray>(</dark_gray><green>"
                + participants.size() + "/" + maxPlayers + "</green><dark_gray>)</dark_gray>");

        int fastStart = Math.max(5, plugin.getConfig().getInt("registration.fast-start-seconds", 10));
        if (participants.size() >= maxPlayers && countdownRemaining > fastStart) {
            countdownRemaining = fastStart;
            deliverPendingKits();
            broadcast("<gold><bold>EVENT FULL!</bold></gold> <yellow>Starting in <gold>" + fastStart + "s</gold>!</yellow>");
        }
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
        hud.clearForPlayer(player);
        PlayerSnapshot snapshot = snapshots.remove(id);
        if (snapshot != null) {
            snapshot.restore(player, resolveReturnLocation());
        }
        data.removePersistedSnapshot(id);
        player.sendMessage(FormatUtil.mm(PREFIX + "<green>You left the event and your state was restored.</green>"));

        if (eventRunning && wasAlive) {
            processEliminationAfterDisconnect();
        }
    }

    public void forceLateJoin(CommandSender sender, String playerName) {
        if (!eventRunning || eventOpen || celebrating) {
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
        if (isDestructibleCurrentMatch()) arenaManager.addArenaBuilder(selectedArena, id);
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
        giveKitWithPlayerLayout(target, selectedKit);
        kitDelivered.add(id);
        applyCurrentBorderToPlayer(target); // Preserves everyone else's active shrink!
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
        if (nextPhase.equals("final")) applyFinalDuelGlowIfEnabled();
        String label = nextPhase.equals("wave2") ? "WAVE 2" : "FINAL DUEL";
        hud.broadcastTitle(participants, "<gold><bold>" + label + "</bold></gold>", "<green>" + alive.size() + " fighters remain!</green>", 100, 1400, 300);
        broadcast("<yellow><bold>" + label + "</bold> phase started!</yellow> <green>" + alive.size() + " fighters remain.</green>");
    }

    public void sendTeamChat(Player sender, String message) {
        if (!eventRunning || eventMode != EventMode.TEAM_WARS || !participants.contains(sender.getUniqueId())) {
            sender.sendMessage(FormatUtil.mm(PREFIX + "<red>Team chat is only available during Team Wars.</red>"));
            return;
        }
        Integer team = teams.get(sender.getUniqueId());
        if (team == null) return;
        String badge = team == 1 ? "<red><bold>[RED TEAM]</bold></red>" : "<blue><bold>[BLUE TEAM]</bold></blue>";
        Component msg = FormatUtil.mm(badge + " <yellow>" + FormatUtil.escape(sender.getName())
                + "</yellow><dark_gray>:</dark_gray> <white>" + FormatUtil.escape(message) + "</white>");
        for (Map.Entry<UUID, Integer> e : teams.entrySet()) {
            if (Objects.equals(e.getValue(), team)) {
                Player member = Bukkit.getPlayer(e.getKey());
                if (member != null) member.sendMessage(msg);
            }
        }
    }

    private void advancePhaseIfNeeded() {
        if (!eventRunning || eventCompleted) return;
        int aliveCount = alive.size();
        if (aliveCount <= 1) {
            if (aliveCount == 1) finishWinner(alive.iterator().next());
            else stopEvent("Event ended with no winner");
            return;
        }
        if (phase.equals("final")) {
            shrinkBorderForAliveCount(aliveCount);
            return;
        }
        if (aliveCount == 2) {
            transitionToPhase("final");
            return;
        }
        if (phase.equals("wave1")) {
            int threshold = EventMode.calculateWave2Threshold(initialFighters);
            if (threshold != -1 && aliveCount <= threshold) {
                transitionToPhase("wave2");
                return;
            }
        }
        shrinkBorderForAliveCount(aliveCount);
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
            else shrinkBorderForAliveCount(alive.size());
            return;
        }
        if (eventCompleted) return;
        eventCompleted = true;
        celebrating = true;
        String teamName = team == 1 ? "Red Team" : "Blue Team";
        Bukkit.broadcast(FormatUtil.mm(team == 1 ? "<red><bold>RED TEAM WINS!</bold></red>" : "<blue><bold>BLUE TEAM WINS!</bold></blue>"));
        hud.broadcastTitle(participants, team == 1 ? "<red><bold>RED TEAM WINS!</bold></red>" : "<blue><bold>BLUE TEAM WINS!</bold></blue>",
                "<gold>Congratulations champions!</gold>", 150, 3000, 500);

        List<Player> winners = new ArrayList<>();
        Location winnerLoc = configuredLocation("winner");
        Location crowdLoc = configuredLocation("winner-crowd");
        for (UUID id : participants) {
            if (Objects.equals(teams.get(id), team)) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
                String name = offline.getName() != null ? offline.getName() : "Player";
                data.recordWin(id, name);
                runRewardCommands("team-winner", id, name);
                Player online = Bukkit.getPlayer(id);
                if (online != null) {
                    online.setGameMode(GameMode.ADVENTURE);
                    if (winnerLoc != null) online.teleport(winnerLoc);
                    winners.add(online);
                    playCue(online, "winner");
                }
            } else if (crowdLoc != null) {
                Player crowd = Bukkit.getPlayer(id);
                if (crowd != null && crowd.isOnline() && !crowd.isDead()) {
                    crowd.setGameMode(GameMode.ADVENTURE);
                    crowd.teleport(crowdLoc);
                }
            }
        }
        recordMatchSummaryAndNotify(teamName, teamName.toLowerCase(Locale.ROOT));
        scheduleCelebrationAndFinish(winners, "Team Wars finished");
    }

    private void startTournament() {
        if (!eventOpen || participants.size() < 2) {
            broadcast("<red>Tournament requires at least 2 registered players.</red>");
            stopEvent("Tournament cancelled: not enough players");
            return;
        }
        Arena arena = arenaManager.get(selectedArena);
        if (arena != null) {
            blockTracker.startTracking(arenaManager.sharedWorldName(), arena);
        }
        eventOpen = false;
        eventRunning = true;
        paused = false;
        matchStartedAt = System.currentTimeMillis();
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
        Location audLoc = arena != null ? arenaManager.firstSpawn(arena, "audience") : null;
        for (UUID id : entrants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                snapshotIfNeeded(p);
                p.setGameMode(GameMode.SPECTATOR);
                if (audLoc != null) p.teleport(audLoc);
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
        refreshHologramsSafe();
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
        giveKitWithPlayerLayout(a, selectedKit);
        giveKitWithPlayerLayout(b, selectedKit);

        Location audLoc = arena != null ? arenaManager.firstSpawn(arena, "audience") : null;
        for (UUID id : participants) {
            if (!alive.contains(id)) {
                spectators.add(id);
                Player spec = Bukkit.getPlayer(id);
                if (spec != null) {
                    spec.setGameMode(GameMode.SPECTATOR);
                    if (audLoc != null && !spec.getWorld().equals(audLoc.getWorld())) {
                        spec.teleport(audLoc);
                    }
                }
            }
        }
        applyPhaseBorder("final");
        applyFinalDuelGlowIfEnabled();
        startGracePeriod(alive, a.getName() + " vs " + b.getName());
        hud.broadcastTitle(spectators, "<gold><bold>ROUND " + tournamentRound + "</bold></gold>",
                "<green>" + FormatUtil.escape(a.getName()) + "</green> <gray>vs</gray> <red>" + FormatUtil.escape(b.getName()) + "</red>", 100, 1500, 300);
        broadcast("<yellow>Round " + tournamentRound + ":</yellow> <green>" + FormatUtil.escape(a.getName())
                + "</green> <gray>vs</gray> <red>" + FormatUtil.escape(b.getName()) + "</red>");
    }

    private void completeTournamentMatch(UUID winnerId) {
        if (!eventRunning || eventMode != EventMode.TOURNAMENT) return;
        cancelGrace();
        if (winnerId != null) {
            tournamentWinners.add(winnerId);
            Player winner = Bukkit.getPlayer(winnerId);
            broadcast("<yellow>" + FormatUtil.escape(winner == null ? "A player" : winner.getName())
                    + "</yellow> <gray>advances to the next round!</gray>");
        }
        Arena arena = arenaManager.get(selectedArena);
        Location audLoc = arena != null ? arenaManager.firstSpawn(arena, "audience") : null;
        if (tournamentPlayerA != null) {
            Player a = Bukkit.getPlayer(tournamentPlayerA);
            if (a != null) {
                clearPlayerBorder(a);
                a.setGameMode(GameMode.SPECTATOR);
                if (audLoc != null) a.teleport(audLoc);
            }
            spectators.add(tournamentPlayerA);
        }
        if (tournamentPlayerB != null) {
            Player b = Bukkit.getPlayer(tournamentPlayerB);
            if (b != null) {
                clearPlayerBorder(b);
                b.setGameMode(GameMode.SPECTATOR);
                if (audLoc != null) b.teleport(audLoc);
            }
            spectators.add(tournamentPlayerB);
        }
        alive.clear();
        tournamentPlayerA = null;
        tournamentPlayerB = null;

        // Roll back arena edits & wipe drops/crystals cleanly between tournament rounds!
        int batch = Math.max(100, plugin.getConfig().getInt("arenas.rollback-blocks-per-tick", 300));
        blockTracker.rollbackBatched(plugin, batch, () -> {
            arenaManager.clearArenaEntities(selectedArena);
            if (arena != null && eventRunning) {
                blockTracker.startTracking(arenaManager.sharedWorldName(), arena);
            }
            Bukkit.getScheduler().runTaskLater(plugin, this::startNextTournamentMatch, 60L);
        });
    }

    private void finishWinner(UUID winnerId) {
        if (eventCompleted || winnerId == null) return;
        eventCompleted = true;
        celebrating = true;
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
        // Resolve 2nd Place (last eliminated) and 3rd Place (second-to-last eliminated) for the 3-step Royal Podium!
        UUID secondId = null;
        UUID thirdId = null;
        for (int i = eliminationOrder.size() - 1; i >= 0; i--) {
            UUID cand = eliminationOrder.get(i);
            if (winnerId.equals(cand)) continue;
            if (secondId == null) secondId = cand;
            else if (thirdId == null && !secondId.equals(cand)) {
                thirdId = cand;
                break;
            }
        }
        String secondName = secondId != null ? safePlayerName(secondId) : "---";
        String thirdName = thirdId != null ? safePlayerName(thirdId) : "---";

        data.recordWin(winnerId, winnerName);
        recordMatchSummaryAndNotify(winnerName, winnerId.toString(), secondName, thirdName);
        broadcast("<yellow><bold>" + FormatUtil.escape(winnerName) + "</bold></yellow> <green>won the event! (+20 Elo)</green>");
        hud.broadcastTitle(participants, "<gold><bold>VICTORY!</bold></gold>", "<yellow>" + FormatUtil.escape(winnerName) + " is the champion!</yellow>", 150, 3200, 500);
        runRewardCommands("winner", winnerId, winnerName);

        List<Player> celebrateList = new ArrayList<>();
        Player onlineWinner = Bukkit.getPlayer(winnerId);
        Location winnerLoc = configuredLocation("winner");
        Location crowdLoc = configuredLocation("winner-crowd");
        World podiumWorld = winnerLoc != null ? winnerLoc.getWorld() : arenaManager.getOrCreateSharedWorld(null);
        Location secondLoc = podiumWorld != null ? new Location(podiumWorld, ArenaManager.WINNER_ORIGIN_X - 7.5, ArenaManager.WINNER_ORIGIN_Y + 4.0, ArenaManager.WINNER_ORIGIN_Z - 5.5, 0.0f, 0.0f) : null;
        Location thirdLoc = podiumWorld != null ? new Location(podiumWorld, ArenaManager.WINNER_ORIGIN_X + 8.5, ArenaManager.WINNER_ORIGIN_Y + 3.0, ArenaManager.WINNER_ORIGIN_Z - 5.5, 0.0f, 0.0f) : null;

        if (onlineWinner != null) {
            onlineWinner.setGameMode(GameMode.ADVENTURE);
            if (winnerLoc != null) onlineWinner.teleport(winnerLoc);
            playCue(onlineWinner, "winner");
            celebrateList.add(onlineWinner);
        }
        if (secondId != null && secondLoc != null) {
            Player p2 = Bukkit.getPlayer(secondId);
            if (p2 != null && p2.isOnline() && !p2.isDead()) {
                p2.setGameMode(GameMode.ADVENTURE);
                p2.teleport(secondLoc);
                playCue(p2, "winner");
                celebrateList.add(p2);
            }
        }
        if (thirdId != null && thirdLoc != null) {
            Player p3 = Bukkit.getPlayer(thirdId);
            if (p3 != null && p3.isOnline() && !p3.isDead()) {
                p3.setGameMode(GameMode.ADVENTURE);
                p3.teleport(thirdLoc);
                playCue(p3, "winner");
                celebrateList.add(p3);
            }
        }
        // Bring all other online participants/spectators to the Winner Hall crowd gallery!
        if (crowdLoc != null) {
            for (UUID id : participants) {
                if (winnerId.equals(id) || Objects.equals(secondId, id) || Objects.equals(thirdId, id)) continue;
                Player crowd = Bukkit.getPlayer(id);
                if (crowd != null && crowd.isOnline() && !crowd.isDead()) {
                    crowd.setGameMode(GameMode.ADVENTURE);
                    crowd.teleport(crowdLoc);
                    playCue(crowd, "winner");
                }
            }
        }
        refreshHologramsSafe();
        scheduleCelebrationAndFinish(celebrateList, "Event finished");
    }

    private String safePlayerName(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : "Player";
    }

    private void scheduleCelebrationAndFinish(List<Player> celebratePlayers, String stopReason) {
        int celebSec = Math.max(0, plugin.getConfig().getInt("celebration-seconds", 6));
        if (celebSec == 0) {
            stopEvent(stopReason);
            return;
        }
        for (Player p : celebratePlayers) {
            if (p.isOnline()) spawnCelebrationFirework(p);
        }
        if (celebSec >= 3) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!celebrating) return;
                for (Player p : celebratePlayers) {
                    if (p.isOnline()) spawnCelebrationFirework(p);
                }
            }, 35L);
        }
        if (celebSec >= 5) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!celebrating) return;
                for (Player p : celebratePlayers) {
                    if (p.isOnline()) spawnCelebrationFirework(p);
                }
            }, 70L);
        }
        celebrationTask = Bukkit.getScheduler().runTaskLater(plugin, () -> stopEvent(stopReason), celebSec * 20L);
    }

    private void recordMatchSummaryAndNotify(String winnerName, String winnerId) {
        recordMatchSummaryAndNotify(winnerName, winnerId, "---", "---");
    }

    private void recordMatchSummaryAndNotify(String winnerName, String winnerId, String secondName, String thirdName) {
        UUID mvpId = matchKills.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        String mvpName = "None";
        int mvpKills = 0;
        if (mvpId != null) {
            mvpName = safePlayerName(mvpId);
            mvpKills = matchKills.getOrDefault(mvpId, 0);
        }
        UUID topDmgId = matchDamageDealt.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        long durationSec = Math.max(0L, (System.currentTimeMillis() - matchStartedAt) / 1000L);
        data.recordMatch(selectedArena, eventMode.id(), winnerName, winnerId, matchStartedAt, participants, mvpName, mvpKills);
        data.recordPodium(eventMode.displayName(), winnerName, secondName, thirdName, mvpName, mvpKills);

        // Crisp Pro Post-Match Combat Recap Card
        Bukkit.broadcast(FormatUtil.mm("<dark_gray><st>-----------------------------------------</st></dark_gray>"));
        Bukkit.broadcast(FormatUtil.mm("  <gold><bold>★ MATCH RECAP — " + FormatUtil.escape(eventMode.displayName().toUpperCase(Locale.ROOT)) + " ★</bold></gold>"));
        Bukkit.broadcast(FormatUtil.mm("  <gold>🥇 #1 Champion:</gold> <yellow><bold>" + FormatUtil.escape(winnerName) + "</bold></yellow>"));
        if (!"---".equals(secondName)) {
            Bukkit.broadcast(FormatUtil.mm("  <white>🥈 #2 Runner-Up:</white> <gray>" + FormatUtil.escape(secondName) + "</gray>"
                    + (!"---".equals(thirdName) ? "  <dark_gray>|</dark_gray>  <gold>🥉 #3:</gold> <gray>" + FormatUtil.escape(thirdName) + "</gray>" : "")));
        }
        if (mvpKills > 0) {
            Bukkit.broadcast(FormatUtil.mm("  <aqua>⚔ Match MVP:</aqua> <yellow>" + FormatUtil.escape(mvpName) + "</yellow> <gray>(" + mvpKills + " kills)</gray>"
                    + (topDmgId != null ? " <dark_gray>|</dark_gray> <red>Top Damage:</red> <white>" + FormatUtil.escape(safePlayerName(topDmgId))
                    + " (" + String.format(Locale.ROOT, "%.0f", matchDamageDealt.getOrDefault(topDmgId, 0.0)) + ")</white>" : "")));
        }
        Bukkit.broadcast(FormatUtil.mm("  <gray>Duration:</gray> <white>" + FormatUtil.formatDuration(durationSec)
                + "</white>  <dark_gray>|</dark_gray>  <green><click:run_command:'/event top'><hover:show_text:'<green>Open Leaderboards</green>'>[Leaderboards]</hover></click></green>"));
        Bukkit.broadcast(FormatUtil.mm("<dark_gray><st>-----------------------------------------</st></dark_gray>"));

        Arena arena = arenaManager.get(selectedArena);
        discord.notifyMatchFinished(
                eventMode.displayName(),
                arena != null ? arena.displayName() : selectedArena,
                winnerName,
                mvpName,
                mvpKills,
                Math.max(initialFighters, participants.size()),
                FormatUtil.formatDuration(durationSec)
        );
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
    // Status / HUD / AFK loop
    // -------------------------------------------------------------------------

    private void startStatusLoop() {
        statusTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Live Sky Citadel Parkour stopwatch for non-fighting players
            if (!parkourStartedAt.isEmpty()) {
                long nowMs = System.currentTimeMillis();
                for (Map.Entry<UUID, Long> entry : new ArrayList<>(parkourStartedAt.entrySet())) {
                    Player runner = Bukkit.getPlayer(entry.getKey());
                    if (runner == null || !runner.isOnline() || alive.contains(entry.getKey())) {
                        parkourStartedAt.remove(entry.getKey());
                        continue;
                    }
                    runner.sendActionBar(FormatUtil.mm("<green><bold>🏃 PARKOUR</bold></green> <dark_gray>|</dark_gray> <yellow>"
                            + DataStore.formatParkourTime(nowMs - entry.getValue()) + "</yellow>"));
                }
            }

            if (!eventOpen && !eventRunning) return;
            Arena arena = arenaManager.get(selectedArena);
            String arenaLabel = arena != null ? arena.displayName() : selectedArena;

            if (eventRunning && !celebrating) {
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
                            eliminationOrder.add(fighterId);
                            spectators.add(fighterId);
                            fighter.getInventory().clear();
                            fighter.setGameMode(GameMode.SPECTATOR);
                            Location audLoc = arena != null ? arenaManager.firstSpawn(arena, "audience") : null;
                            if (audLoc != null) fighter.teleport(audLoc);
                            else teleportToConfigured(fighter, "spectator");
                            fighter.sendMessage(FormatUtil.mm(PREFIX + "<red>You were eliminated for being AFK.</red>"));
                            broadcast("<gray>" + FormatUtil.escape(fighter.getName()) + " was eliminated for being AFK.</gray>");
                            afkWarned.remove(fighterId);
                            processEliminationAfterDisconnect();
                        } else if (now - lastActive >= Math.max(60_000L, afkLimit - 60_000L) && afkWarned.add(fighterId)) {
                            fighter.sendMessage(FormatUtil.mm(PREFIX + "<yellow>AFK warning: move or fight within 60 seconds or you will be eliminated!</yellow>"));
                        }
                    }
                }

                // Enforce REAL WorldBorder damage every second (Paper per-player virtual borders are visual-only!)
                if (arena != null && !gracePeriodActive) {
                    double curSize = currentBorderSize();
                    double halfSize = curSize / 2.0;
                    double baseBorderDmg = Math.max(0.5, plugin.getConfig().getDouble("world-border.damage-per-second", 2.0));
                    for (UUID fighterId : new ArrayList<>(alive)) {
                        Player fighter = Bukkit.getPlayer(fighterId);
                        if (fighter == null || !fighter.isOnline() || fighter.isDead()) continue;
                        if (!fighter.getWorld().getName().equalsIgnoreCase(arenaManager.sharedWorldName())) continue;
                        Location loc = fighter.getLocation();
                        double distOutside = Math.max(Math.abs(loc.getX() - arena.centerX()), Math.abs(loc.getZ() - arena.centerZ())) - halfSize;
                        if (distOutside > 0.0) {
                            double dmg = baseBorderDmg + Math.min(4.0, distOutside * 0.25);
                            fighter.damage(dmg);
                            fighter.sendActionBar(FormatUtil.mm("<red><bold>⚠ OUTSIDE BORDER! (-"
                                    + String.format(Locale.ROOT, "%.1f", dmg / 2.0) + " ❤/s) Move to center!</bold></red>"));
                        }
                    }
                }

                float aliveRatio = initialFighters > 0 ? (float) alive.size() / initialFighters : 1.0f;
                BossBar.Color barColor = aliveRatio > 0.5f ? BossBar.Color.GREEN : (aliveRatio > 0.25f ? BossBar.Color.YELLOW : BossBar.Color.RED);
                hud.updateBossBar(
                        "<gold><bold>" + FormatUtil.escape(eventMode.displayName()) + "</bold></gold> <dark_gray>|</dark_gray> <green>"
                                + alive.size() + " Alive</green> <dark_gray>|</dark_gray> <aqua>" + phase.toUpperCase(Locale.ROOT)
                                + "</aqua> <dark_gray>|</dark_gray> <red>Border " + (int) Math.round(currentBorderSize()) + "x" + (int) Math.round(currentBorderSize()) + "</red>",
                        aliveRatio, barColor, participants
                );

                Component actionBar = FormatUtil.mm("<gold><bold>" + FormatUtil.escape(eventMode.displayName())
                        + "</bold></gold> <dark_gray>|</dark_gray> <yellow>" + alive.size() + " alive</yellow>"
                        + " <dark_gray>|</dark_gray> <green>" + phase.toUpperCase(Locale.ROOT) + "</green>");
                for (UUID id : participants) {
                    Player viewer = Bukkit.getPlayer(id);
                    if (viewer != null) {
                        // Don't overwrite border-damage alert for fighters outside border
                        if (arena != null && alive.contains(id)) {
                            double half = currentBorderSize() / 2.0;
                            Location l = viewer.getLocation();
                            if (Math.max(Math.abs(l.getX() - arena.centerX()), Math.abs(l.getZ() - arena.centerZ())) > half) continue;
                        }
                        viewer.sendActionBar(actionBar);
                    }
                }
            }

            // Refresh flicker-free per-player event sidebar, below-name HP, & branded tablist
            long elapsedSec = eventRunning ? Math.max(0L, (System.currentTimeMillis() - matchStartedAt) / 1000L) : 0L;
            int liveBorderInt = eventRunning ? (int) Math.round(currentBorderSize()) : 0;
            Map<UUID, Integer> teamSnapshot = eventMode == EventMode.TEAM_WARS ? teams : Map.of();
            for (UUID id : participants) {
                Player viewer = Bukkit.getPlayer(id);
                if (viewer == null || !viewer.isOnline()) continue;
                hud.updateSidebar(
                        viewer,
                        eventMode.displayName(),
                        arenaLabel,
                        phase,
                        alive.size(),
                        participants.size(),
                        matchKills.getOrDefault(id, 0),
                        data.stats(id),
                        teams.get(id),
                        countdownRemaining,
                        eventOpen,
                        elapsedSec,
                        liveBorderInt,
                        teamSnapshot
                );
            }
        }, 20L, 20L);
    }

    // -------------------------------------------------------------------------
    // WorldBorder (True Bounds Center + Dynamic Player-Scaled Shrink + Real Damage!)
    // -------------------------------------------------------------------------

    public double currentBorderSize() {
        long now = System.currentTimeMillis();
        if (activeBorderEndMs <= activeBorderStartMs || now >= activeBorderEndMs) {
            return activeBorderTargetSize;
        }
        double frac = (double) (now - activeBorderStartMs) / (double) (activeBorderEndMs - activeBorderStartMs);
        frac = Math.max(0.0, Math.min(1.0, frac));
        return activeBorderStartSize + (activeBorderTargetSize - activeBorderStartSize) * frac;
    }

    private void applyPhaseBorder(String phaseName) {
        Arena arena = arenaManager.get(selectedArena);
        if (arena == null) return;
        double maxArenaDiameter = Math.max(20.0, arena.playableRadius() * 2.0);
        double defaultSize = phaseName.equals("wave1") ? 84.0 : phaseName.equals("wave2") ? 52.0 : 26.0;
        double configuredTarget = plugin.getConfig().getDouble("world-border." + phaseName + ".size", defaultSize);
        double targetSize = Math.min(maxArenaDiameter, configuredTarget);
        if (plugin.getConfig().getBoolean("world-border.dynamic-shrink", true)) {
            targetSize = Math.min(targetSize, EventMode.calculateDynamicBorderSize(Math.max(2, alive.size()), maxArenaDiameter));
        }
        long shrinkSeconds = Math.max(0L, plugin.getConfig().getLong("world-border." + phaseName + ".shrink-seconds", 60L));
        double startSize = Math.max(targetSize, maxArenaDiameter);

        long now = System.currentTimeMillis();
        this.activeBorderStartSize = startSize;
        this.activeBorderTargetSize = targetSize;
        this.activeBorderStartMs = now;
        this.activeBorderEndMs = now + shrinkSeconds * 1000L;

        for (UUID id : alive) {
            Player fighter = Bukkit.getPlayer(id);
            if (fighter != null) applyBorderToPlayer(fighter, arena, startSize, targetSize, shrinkSeconds);
        }
    }

    private void shrinkBorderForAliveCount(int aliveCount) {
        if (!plugin.getConfig().getBoolean("world-border.dynamic-shrink", true)) return;
        Arena arena = arenaManager.get(selectedArena);
        if (arena == null || aliveCount < 2) return;
        double maxArenaDiameter = Math.max(20.0, arena.playableRadius() * 2.0);
        double desired = EventMode.calculateDynamicBorderSize(aliveCount, maxArenaDiameter);
        if (desired >= activeBorderTargetSize - 1.5) return; // Already at or tighter than this tier

        double current = currentBorderSize();
        long shrinkSec = Math.max(5L, plugin.getConfig().getLong("world-border.dynamic-shrink-seconds", 25L));
        long now = System.currentTimeMillis();
        this.activeBorderStartSize = current;
        this.activeBorderTargetSize = desired;
        this.activeBorderStartMs = now;
        this.activeBorderEndMs = now + shrinkSec * 1000L;

        for (UUID id : alive) {
            Player fighter = Bukkit.getPlayer(id);
            if (fighter != null) applyBorderToPlayer(fighter, arena, current, desired, shrinkSec);
        }
        broadcast("<red>⚠ Border shrinking to <gold><bold>" + (int) Math.round(desired) + "x" + (int) Math.round(desired)
                + "</bold></gold> for <yellow>" + aliveCount + "</yellow> remaining fighters!</red>");
    }

    private void applyCurrentBorderToPlayer(Player fighter) {
        Arena arena = arenaManager.get(selectedArena);
        if (arena == null || fighter == null) return;
        double cur = currentBorderSize();
        long remainSec = Math.max(0L, (activeBorderEndMs - System.currentTimeMillis()) / 1000L);
        applyBorderToPlayer(fighter, arena, cur, activeBorderTargetSize, remainSec);
    }

    private void applyBorderToPlayer(Player fighter, Arena arena, double startSize, double targetSize, long shrinkSeconds) {
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(arena.centerX(), arena.centerZ());
        border.setDamageBuffer(0.0);
        border.setDamageAmount(1.0);
        border.setSize(Math.max(10.0, startSize));
        if (shrinkSeconds > 0 && targetSize < startSize) {
            border.changeSize(Math.max(10.0, targetSize), shrinkSeconds * 20L);
        } else {
            border.setSize(Math.max(10.0, targetSize));
        }
        border.setWarningDistance(8);
        fighter.setWorldBorder(border);
    }

    private void clearPlayerBorder(Player player) {
        player.setWorldBorder(null);
    }

    // -------------------------------------------------------------------------
    // Snapshots, Return Locations, Rules Book, Sounds
    // -------------------------------------------------------------------------

    private boolean isDedicatedServerMode() {
        return plugin.getConfig().getBoolean("dedicated-server-mode", true);
    }

    private Location resolveReturnLocation() {
        return isDedicatedServerMode() ? lobbyLocation() : null;
    }

    private boolean isDestructibleCurrentMatch() {
        if (isDestructibleMode(eventMode)) return true;
        return selectedArena != null && (selectedArena.equals("build-uhc") || selectedArena.equals("crystal")
                || selectedArena.equals("uhc") || selectedArena.equals("smp"));
    }

    private static boolean isDestructibleMode(EventMode mode) {
        return mode == EventMode.BUILD_UHC || mode == EventMode.CRYSTAL || mode == EventMode.UHC || mode == EventMode.SMP;
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
                hud.clearForPlayer(player);
                entry.getValue().restore(player, resolveReturnLocation());
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
        if (world == null) {
            if (worldName.equalsIgnoreCase(arenaManager.sharedWorldName())) {
                world = arenaManager.getOrCreateSharedWorld(null);
            } else {
                world = new WorldCreator(worldName).createWorld();
            }
        }
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

    public boolean isUsingPerArenaWaiting() {
        if (!plugin.getConfig().getBoolean("lobby-protection.per-arena-waiting", true)) return false;
        Arena arena = arenaManager.get(selectedArena);
        return arena != null && arenaManager.firstSpawn(arena, "waiting") != null;
    }

    /**
     * Resolves the active event's waiting lobby: prefers the selected arena's own themed
     * waiting skybox / custom `/ce setwaiting <arena>` spawn, falling back cleanly to global Lobby.
     */
    public Location activeWaitingLocation() {
        if (plugin.getConfig().getBoolean("lobby-protection.per-arena-waiting", true)) {
            Arena arena = arenaManager.get(selectedArena);
            if (arena != null) {
                Location arenaWait = arenaManager.firstSpawn(arena, "waiting");
                if (arenaWait != null) return arenaWait;
            }
        }
        return lobbyLocation();
    }

    private void warnBoundary(Player player, String miniMsg) {
        long now = System.currentTimeMillis();
        Long prev = lastBoundaryWarnAt.get(player.getUniqueId());
        if (prev == null || (now - prev) >= 2000L) {
            lastBoundaryWarnAt.put(player.getUniqueId(), now);
            player.sendMessage(FormatUtil.mm(PREFIX + miniMsg));
        }
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
                .withColor(Color.ORANGE, Color.AQUA, Color.PURPLE)
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
        for (String page : rawPages) pages.add(FormatUtil.parseFlexible(page));
        meta.pages(pages);
        book.setItemMeta(meta);
        player.getInventory().setItem(4, book);
        player.openBook(book);
        playCue(player, "rules-book");
    }

    private void giveLobbyHotbar(Player player) {
        if (!plugin.getConfig().getBoolean("lobby-protection.give-lobby-hotbar", true)) return;
        player.getInventory().setItem(0, lobbyHotbarItem(Material.IRON_SWORD, "<green><bold>Edit Kit Layout</bold></green> <gray>(Right-Click)</gray>", "kit-editor"));
        player.getInventory().setItem(2, lobbyHotbarItem(Material.BELL, "<aqua><bold>Vote Next Mode</bold></aqua> <gray>(Right-Click)</gray>", "mode-vote"));
        player.getInventory().setItem(6, lobbyHotbarItem(Material.NETHER_STAR, "<gold><bold>Leaderboards</bold></gold> <gray>(Right-Click)</gray>", "leaderboard"));
        player.getInventory().setItem(8, lobbyHotbarItem(Material.RED_DYE, "<red><bold>Leave Event</bold></red> <gray>(Right-Click)</gray>", "leave"));
    }

    private ItemStack lobbyHotbarItem(Material mat, String displayMini, String action) {
        ItemStack stack = new ItemStack(mat);
        var meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(FormatUtil.noItalic(FormatUtil.mm(displayMini)));
            meta.getPersistentDataContainer().set(lobbyItemKey, PersistentDataType.STRING, action);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String lobbyItemAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(lobbyItemKey, PersistentDataType.STRING);
    }

    private boolean isRulesBook(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(rulesBookKey, PersistentDataType.BYTE);
    }

    private void removeRulesBooks(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (isRulesBook(item) || lobbyItemAction(item) != null) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private boolean inWaitingLobby(Player player) {
        if (eventOpen && participants.contains(player.getUniqueId()) && !alive.contains(player.getUniqueId())) {
            return true;
        }
        Location lobby = configuredLocation("lobby");
        if (lobby != null && lobby.getWorld() != null && player.getWorld().equals(lobby.getWorld())) {
            double radius = plugin.getConfig().getDouble("lobby-protection.radius", 46.0);
            if (player.getLocation().distanceSquared(lobby) <= radius * radius) {
                return true;
            }
        }
        // Also protect the active arena's waiting skybox / custom waiting room
        Arena arena = arenaManager.get(selectedArena);
        if (arena != null) {
            Location arenaWait = arenaManager.firstSpawn(arena, "waiting");
            if (arenaWait != null && arenaWait.getWorld() != null && player.getWorld().equals(arenaWait.getWorld())) {
                if (player.getLocation().distanceSquared(arenaWait) <= 16.0 * 16.0) {
                    return true;
                }
            }
        }
        return false;
    }

    private void broadcast(String bodyMini) {
        Bukkit.broadcast(FormatUtil.mm(PREFIX + bodyMini));
    }

    // -------------------------------------------------------------------------
    // Security Listeners: Command Whitelist & Container Shield
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!participants.contains(player.getUniqueId())) return;
        if (player.hasPermission("clasherevents.admin")) return;
        if (!plugin.getConfig().getBoolean("security.block-disallowed-commands", true)) return;

        List<String> allowed = plugin.getConfig().getStringList("security.allowed-commands-during-event");
        if (!CommandGuard.isCommandAllowed(event.getMessage(), allowed)) {
            event.setCancelled(true);
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>That command is disabled during an event! Use <yellow>/event leave</yellow> first.</red>"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onContainerOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!participants.contains(player.getUniqueId()) || player.hasPermission("clasherevents.admin")) return;
        if (event.getInventory().getHolder() instanceof EventMenuHolder) return;
        if (!plugin.getConfig().getBoolean("security.block-containers-during-event", true)) return;
        if (BLOCKED_CONTAINERS.contains(event.getInventory().getType())) {
            event.setCancelled(true);
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Containers and Ender Chests are locked during events.</red>"));
        }
    }

    // -------------------------------------------------------------------------
    // Built-In Arena Protection + Automatic Block Rollback Listeners
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArenaBlockBreakGuard(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("clasherevents.admin")) return;
        if (inWaitingLobby(player)) {
            event.setCancelled(true);
            return;
        }
        if (eventRunning && alive.contains(player.getUniqueId())) {
            if (!isDestructibleCurrentMatch()) {
                event.setCancelled(true);
                return;
            }
            Arena arena = arenaManager.get(selectedArena);
            Block b = event.getBlock();
            if (arena != null && plugin.getConfig().getBoolean("anti-escape.enabled", true)
                    && !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ())) {
                event.setCancelled(true);
                warnBoundary(player, "<red>You cannot break blocks outside the arena perimeter!</red>");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArenaBlockPlaceGuard(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("clasherevents.admin")) return;
        if (inWaitingLobby(player)) {
            event.setCancelled(true);
            return;
        }
        if (eventRunning && alive.contains(player.getUniqueId())) {
            if (!isDestructibleCurrentMatch()) {
                event.setCancelled(true);
                return;
            }
            Arena arena = arenaManager.get(selectedArena);
            Block b = event.getBlock();
            if (arena != null && plugin.getConfig().getBoolean("anti-escape.enabled", true)
                    && !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ())) {
                event.setCancelled(true);
                warnBoundary(player, "<red>You cannot build outside the arena boundary or height limit!</red>");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArenaBucketEmptyGuard(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("clasherevents.admin")) return;
        if (inWaitingLobby(player)) {
            event.setCancelled(true);
            return;
        }
        if (eventRunning && alive.contains(player.getUniqueId())) {
            if (!isDestructibleCurrentMatch()) {
                event.setCancelled(true);
                return;
            }
            Arena arena = arenaManager.get(selectedArena);
            Block b = event.getBlock();
            if (arena != null && plugin.getConfig().getBoolean("anti-escape.enabled", true)
                    && !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ())) {
                event.setCancelled(true);
                warnBoundary(player, "<red>You cannot place liquids outside the arena boundary!</red>");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAntiEscapeTeleport(PlayerTeleportEvent event) {
        if (!plugin.getConfig().getBoolean("anti-escape.enabled", true)) return;
        if (!GUARDED_TP_CAUSES.contains(event.getCause())) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!participants.contains(id)) return;
        Location to = event.getTo();
        if (to == null) return;

        // 1. Waiting players cannot pearl/chorus/portal out of the waiting skybox or lobby!
        if (eventOpen && !alive.contains(id)) {
            event.setCancelled(true);
            warnBoundary(player, "<red>You cannot teleport out of the waiting lobby!</red>");
            return;
        }

        Arena arena = arenaManager.get(selectedArena);
        if (arena == null) return;
        String arenaWorld = arenaManager.sharedWorldName();

        // 2. Active fighters cannot ender-pearl/chorus during grace freeze OR outside arena bounds!
        if (eventRunning && alive.contains(id)) {
            if (gracePeriodActive) {
                event.setCancelled(true);
                warnBoundary(player, "<yellow>Wait for the grace countdown before pearling!</yellow>");
                return;
            }
            boolean sameWorld = to.getWorld() != null && to.getWorld().getName().equalsIgnoreCase(arenaWorld);
            if (!sameWorld || !arena.isInsideCombatBounds(to.getX(), to.getY(), to.getZ())) {
                event.setCancelled(true);
                warnBoundary(player, "<red>You cannot teleport outside the arena!</red>");
            }
            return;
        }

        // 3. Eliminated spectators cannot spectator-TP out of the active arena region!
        if (eventRunning && isSpectator(id)) {
            boolean sameWorld = to.getWorld() != null && to.getWorld().getName().equalsIgnoreCase(arenaWorld);
            if (!sameWorld || !arena.isInsideSpectatorBounds(to.getX(), to.getY(), to.getZ())) {
                event.setCancelled(true);
                warnBoundary(player, "<yellow>Stay inside the active arena while spectating!</yellow>");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackPlace(BlockPlaceEvent event) {
        if (blockTracker.isTracking()) {
            Block b = event.getBlock();
            blockTracker.recordBeforeChange(new ArenaBlockTracker.BlockPos(b.getX(), b.getY(), b.getZ()), event.getBlockReplacedState().getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBreak(BlockBreakEvent event) {
        if (blockTracker.isTracking()) blockTracker.recordBeforeChange(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackLiquidFlow(BlockFromToEvent event) {
        if (blockTracker.isTracking()) blockTracker.recordBeforeChange(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackIgnite(BlockIgniteEvent event) {
        if (blockTracker.isTracking()) blockTracker.recordBeforeChange(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackEntityExplode(EntityExplodeEvent event) {
        if (!blockTracker.isTracking()) return;
        for (Block b : event.blockList()) blockTracker.recordBeforeChange(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBlockExplode(BlockExplodeEvent event) {
        if (!blockTracker.isTracking()) return;
        for (Block b : event.blockList()) blockTracker.recordBeforeChange(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBucketEmpty(PlayerBucketEmptyEvent event) {
        if (blockTracker.isTracking()) blockTracker.recordBeforeChange(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBucketFill(PlayerBucketFillEvent event) {
        if (blockTracker.isTracking()) blockTracker.recordBeforeChange(event.getBlock());
    }

    // -------------------------------------------------------------------------
    // Combat, Spectator Chat, Lobby Protection, Death/Respawn, Join/Quit
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpectatorChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (eventRunning && eventMode == EventMode.TEAM_WARS && alive.contains(id) && text.startsWith("@") && text.length() > 1) {
            event.setCancelled(true);
            String clean = text.substring(1).trim();
            Bukkit.getScheduler().runTask(plugin, () -> sendTeamChat(event.getPlayer(), clean));
            return;
        }
        if (!spectators.contains(id)) return;
        event.setCancelled(true);
        String name = event.getPlayer().getName();
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
        if (!alive.contains(victim.getUniqueId())) return;
        if (celebrating || gracePeriodActive) {
            event.setCancelled(true);
            return;
        }
        Player attacker = null;
        if (event.getDamager() instanceof Player direct) attacker = direct;
        else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player shooter) attacker = shooter;
        if (attacker == null || !alive.contains(attacker.getUniqueId())) return;

        Integer teamA = teams.get(attacker.getUniqueId());
        Integer teamV = teams.get(victim.getUniqueId());
        if (teamA != null && teamA.equals(teamV)) {
            event.setCancelled(true);
            return;
        }

        lastDamager.put(victim.getUniqueId(), attacker.getUniqueId());
        matchDamageDealt.merge(attacker.getUniqueId(), event.getFinalDamage(), Double::sum);
        long now = System.currentTimeMillis();
        lastCombatAt.put(victim.getUniqueId(), now);
        lastMovementAt.put(attacker.getUniqueId(), now);
        lastMovementAt.put(victim.getUniqueId(), now);
        afkWarned.remove(victim.getUniqueId());
        afkWarned.remove(attacker.getUniqueId());

        if (!event.isCancelled()) {
            AttributeInstance maxAttr = victim.getAttribute(Attribute.MAX_HEALTH);
            double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
            double remaining = Math.max(0.0, victim.getHealth() - event.getFinalDamage());
            hud.sendHitHealth(attacker, victim, remaining, maxHp);
        }
    }

    @EventHandler
    public void onFighterMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean antiEscape = plugin.getConfig().getBoolean("anti-escape.enabled", true);

        // 1. Waiting player hard leash (keeps waiting players safely inside their arena skybox or Sky Citadel Lobby!)
        if (eventOpen && participants.contains(id) && !alive.contains(id) && antiEscape) {
            Location waitLoc = activeWaitingLocation();
            if (waitLoc != null && waitLoc.getWorld() != null) {
                boolean perArena = isUsingPerArenaWaiting();
                double maxHoriz = perArena ? 24.0 : 58.0;
                double minY = perArena ? waitLoc.getY() - 5.0 : waitLoc.getY() - 12.0;
                double maxY = perArena ? waitLoc.getY() + 16.0 : waitLoc.getY() + 45.0;
                boolean wrongWorld = !player.getWorld().equals(waitLoc.getWorld());
                double dx = to.getX() - waitLoc.getX();
                double dz = to.getZ() - waitLoc.getZ();
                if (wrongWorld || (dx * dx + dz * dz) > maxHoriz * maxHoriz || to.getY() < minY || to.getY() > maxY) {
                    player.setFallDistance(0f);
                    player.teleport(waitLoc);
                    warnBoundary(player, "<gray>Returned you to the Event Waiting Lobby!</gray>");
                    return;
                }
            }
        }

        // 2. Catch void falls around the floating Spawn Lobby / Parkour + handle Sky Parkour Timer!
        if (!alive.contains(id)) {
            Location lobby = configuredLocation("lobby");
            if (lobby != null && lobby.getWorld() != null && player.getWorld().equals(lobby.getWorld())) {
                double dx = to.getX() - lobby.getX();
                double dz = to.getZ() - lobby.getZ();
                if (plugin.getConfig().getBoolean("lobby-protection.catch-void-fall", true)
                        && (dx * dx + dz * dz) <= 65.0 * 65.0 && to.getY() < lobby.getY() - 12.0) {
                    player.setFallDistance(0f);
                    player.teleport(lobby);
                    if (parkourStartedAt.remove(id) != null) {
                        player.sendMessage(FormatUtil.mm(PREFIX + "<yellow>Parkour run reset — back at the Event Spawn Lobby!</yellow>"));
                    } else {
                        player.sendMessage(FormatUtil.mm(PREFIX + "<gray>Caught your fall — back at the Event Spawn Lobby!</gray>"));
                    }
                    return;
                }
                if (plugin.getConfig().getBoolean("lobby-protection.parkour-timer", true)
                        && player.getWorld().getName().equalsIgnoreCase(arenaManager.sharedWorldName())) {
                    checkLobbyParkourTrigger(player, to);
                }
            }
        }

        // 3. Spectator leash — prevents eliminated spectators from flying away to other arenas or into the void!
        if (eventRunning && isSpectator(id) && antiEscape) {
            Arena arena = arenaManager.get(selectedArena);
            if (arena != null) {
                boolean sameWorld = player.getWorld().getName().equalsIgnoreCase(arenaManager.sharedWorldName());
                if (!sameWorld || !arena.isInsideSpectatorBounds(to.getX(), to.getY(), to.getZ())) {
                    Location aud = arenaManager.firstSpawn(arena, "audience");
                    if (aud == null) aud = arenaManager.firstSpawn(arena, "wave1");
                    if (aud != null) {
                        player.teleport(aud);
                        warnBoundary(player, "<yellow>Stay near the active arena while spectating!</yellow>");
                        return;
                    }
                }
            }
        }

        if (!eventRunning || !alive.contains(id)) return;

        // 4. Active fighter HARD arena containment — zero escape over walls, above height limit, or into void!
        if (antiEscape && !celebrating) {
            Arena arena = arenaManager.get(selectedArena);
            if (arena != null) {
                boolean sameWorld = player.getWorld().getName().equalsIgnoreCase(arenaManager.sharedWorldName());
                if (!sameWorld || to.getY() < arena.pasteY() - 1.0) {
                    List<Location> pts = arenaManager.spawns(arena, phase.equals("final") ? "final" : "wave1");
                    if (!pts.isEmpty()) {
                        player.setFallDistance(0f);
                        player.teleport(pts.get(random.nextInt(pts.size())));
                        warnBoundary(player, "<red>Arena boundary enforced — returned inside the arena!</red>");
                        return;
                    }
                } else if (!arena.isInsideCombatBounds(to.getX(), to.getY(), to.getZ())) {
                    double dx = to.getX() - arena.centerX();
                    double dz = to.getZ() - arena.centerZ();
                    double dist = Math.hypot(dx, dz);
                    double maxR = Math.max(8.0, arena.playableRadius() - 0.75);
                    double clampedX = to.getX();
                    double clampedZ = to.getZ();
                    if (dist > maxR && dist > 0.001) {
                        clampedX = arena.centerX() + (dx / dist) * maxR;
                        clampedZ = arena.centerZ() + (dz / dist) * maxR;
                    }
                    double clampedY = Math.max(arena.pasteY() + 1.0, Math.min(to.getY(), arena.combatMaxY() - 0.5));
                    Location pullBack = new Location(to.getWorld(), clampedX, clampedY, clampedZ, to.getYaw(), to.getPitch());
                    event.setTo(pullBack);
                    warnBoundary(player, "<red>You have reached the arena boundary!</red>");
                    return;
                }
            }
        }

        if (event.getFrom().getBlockX() == to.getBlockX()
                && event.getFrom().getBlockY() == to.getBlockY()
                && event.getFrom().getBlockZ() == to.getBlockZ()) {
            return;
        }
        lastMovementAt.put(id, System.currentTimeMillis());
    }

    private void checkLobbyParkourTrigger(Player player, Location to) {
        UUID id = player.getUniqueId();
        int relX = to.getBlockX() - ArenaManager.LOBBY_ORIGIN_X;
        int relY = to.getBlockY() - ArenaManager.LOBBY_ORIGIN_Y;
        int relZ = to.getBlockZ() - ArenaManager.LOBBY_ORIGIN_Z;

        // Start Gold Pad: dx in 20..24, dz in -20..-17, standing at dy = 3
        if (relX >= 20 && relX <= 24 && relZ >= -20 && relZ <= -17 && relY >= 2 && relY <= 4) {
            if (!parkourStartedAt.containsKey(id)) {
                parkourStartedAt.put(id, System.currentTimeMillis());
                playCue(player, "join");
                player.sendMessage(FormatUtil.mm(PREFIX + "<green><bold>🏃 SKY PARKOUR STARTED!</bold></green> <gray>Reach the golden crown at the top!</gray>"));
            }
            return;
        }
        // Finish Golden Crown Pad: around (10, 19, -37), standing at dy = 20..22
        Long started = parkourStartedAt.get(id);
        if (started != null && Math.abs(relX - 10) <= 3 && Math.abs(relZ + 37) <= 3 && relY >= 19 && relY <= 23) {
            parkourStartedAt.remove(id);
            long elapsed = System.currentTimeMillis() - started;
            DataStore.ParkourResult res = data.recordParkourTime(id, player.getName(), elapsed);
            playCue(player, "winner");
            spawnCelebrationFirework(player);
            player.sendMessage(FormatUtil.mm(PREFIX + "<gold><bold>🏆 PARKOUR COMPLETE!</bold></gold> <yellow>Time:</yellow> <green><bold>"
                    + DataStore.formatParkourTime(elapsed) + "</bold></green>"
                    + (res.personalBest() ? " <aqua>(NEW PERSONAL BEST!)</aqua>" : " <gray>(Best: " + DataStore.formatParkourTime(res.bestMs()) + ")</gray>")));
            if (res.serverRecord()) {
                broadcast("<gold><bold>🏆 NEW LOBBY PARKOUR RECORD!</bold></gold> <yellow>" + FormatUtil.escape(player.getName())
                        + "</yellow> <gray>finished in</gray> <green><bold>" + DataStore.formatParkourTime(elapsed) + "</bold></green><gray>!</gray>");
                refreshHologramsSafe();
            }
        }
    }

    @EventHandler
    public void onLobbyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        if (inWaitingLobby(player)
                || (eventOpen && participants.contains(id) && !eventRunning)
                || (celebrating && participants.contains(id))) {
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
    public void onLobbyDrop(PlayerDropItemEvent event) {
        if (!event.getPlayer().hasPermission("clasherevents.admin") && inWaitingLobby(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onLobbyInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        String lobbyAction = lobbyItemAction(event.getItem());
        if (lobbyAction != null && event.getAction().isRightClick()) {
            event.setCancelled(true);
            switch (lobbyAction) {
                case "kit-editor", "kit-preview" -> {
                    if (gui != null && selectedKit != null && !selectedKit.isEmpty()) gui.openKitLayoutEditor(player, selectedKit);
                    else player.sendMessage(FormatUtil.mm(PREFIX + "<yellow>No active event kit to edit right now.</yellow>"));
                }
                case "mode-vote" -> { if (gui != null) gui.openModeVoteGui(player); }
                case "leaderboard" -> { if (gui != null) gui.openLeaderboardGui(player, "elo"); }
                case "leave" -> leaveEvent(player);
                default -> {}
            }
            return;
        }
        if (player.hasPermission("clasherevents.admin")) return;
        if (isRulesBook(event.getItem())) return;
        if (inWaitingLobby(player)) {
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
        if ((paused || gracePeriodActive) && event.getEntity() instanceof Player player && participants.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onUhcNaturalRegen(EntityRegainHealthEvent event) {
        if (eventRunning && (eventMode == EventMode.UHC || "uhc".equals(selectedKit))
                && event.getEntity() instanceof Player player
                && alive.contains(player.getUniqueId())
                && event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID id = victim.getUniqueId();
        if (!alive.remove(id)) return;
        eliminationOrder.add(id);
        spectators.add(id);

        if (plugin.getConfig().getBoolean("effects.kill-lightning", true)) {
            victim.getWorld().strikeLightningEffect(victim.getLocation());
        }

        int victimStreak = matchKills.getOrDefault(id, 0);
        UUID killerId = lastDamager.remove(id);
        lastCombatAt.remove(id);
        if (killerId != null) {
            Player killer = Bukkit.getPlayer(killerId);
            if (killer != null && !killer.getUniqueId().equals(id)) {
                EloCalculator.EloDelta eloDelta = data.recordKill(killer.getUniqueId(), killer.getName(), id, victim.getName());
                int newMatchKills = matchKills.merge(killer.getUniqueId(), 1, Integer::sum);
                Bukkit.broadcast(FormatUtil.mm("<dark_gray>[<red>KILL</red>]</dark_gray> <yellow>"
                        + FormatUtil.escape(killer.getName()) + "</yellow> <gray>eliminated</gray> <yellow>"
                        + FormatUtil.escape(victim.getName()) + "</yellow> <dark_gray>(</dark_gray><green>+"
                        + eloDelta.gained() + " Elo</green><dark_gray>)</dark_gray>"));

                // Anti-cleanup Kill Heal (+4 hearts + 4s Absorption I) in melee/UHC modes
                if (plugin.getConfig().getBoolean("effects.kill-heal", true)
                        && alive.contains(killer.getUniqueId())
                        && eventMode != EventMode.NETHPOT && eventMode != EventMode.DIAMOND_POT && eventMode != EventMode.CRYSTAL) {
                    AttributeInstance maxAttr = killer.getAttribute(Attribute.MAX_HEALTH);
                    double maxHp = maxAttr != null ? maxAttr.getValue() : 20.0;
                    killer.setHealth(Math.min(maxHp, killer.getHealth() + 8.0));
                    killer.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 80, 0, false, true, true));
                    killer.sendMessage(FormatUtil.mm(PREFIX + "<green>+4 ❤ Kill Heal & 4s Absorption!</green>"));
                }

                // Kill Streak Bounty Shutdown (+10 Bonus Elo!)
                if (victimStreak >= 3 && plugin.getConfig().getBoolean("effects.kill-bounty", true)) {
                    data.awardBonusElo(killer.getUniqueId(), killer.getName(), EloCalculator.BOUNTY_BONUS);
                    Bukkit.broadcast(FormatUtil.mm("<gold><bold>💰 BOUNTY CLAIMED!</bold></gold> <yellow>"
                            + FormatUtil.escape(killer.getName()) + "</yellow> <gray>ended</gray> <red>"
                            + FormatUtil.escape(victim.getName()) + "'s</red> <gray>" + victimStreak
                            + "-kill streak for</gray> <green><bold>+" + EloCalculator.BOUNTY_BONUS + " Bonus Elo</bold></green><gray>!</gray>"));
                }

                if (!firstBloodClaimed && plugin.getConfig().getBoolean("effects.first-blood", true)) {
                    firstBloodClaimed = true;
                    Bukkit.broadcast(FormatUtil.mm("<dark_red><bold>🩸 FIRST BLOOD!</bold></dark_red> <yellow>"
                            + FormatUtil.escape(killer.getName()) + "</yellow> <gray>drew first blood!</gray>"));
                }
                if (plugin.getConfig().getBoolean("effects.kill-streaks", true)) {
                    String streakTag = newMatchKills == 3 ? "<gold><bold>🔥 RAMPAGE! [BOUNTY]</bold></gold>"
                            : newMatchKills == 5 ? "<red><bold>🔥 UNSTOPPABLE! [BOUNTY]</bold></red>"
                            : newMatchKills >= 7 && newMatchKills % 2 == 1 ? "<light_purple><bold>⚡ GODLIKE! [BOUNTY]</bold></light_purple>"
                            : null;
                    if (streakTag != null) {
                        Bukkit.broadcast(FormatUtil.mm(streakTag + " <yellow>" + FormatUtil.escape(killer.getName())
                                + "</yellow> <gray>is on a</gray> <red><bold>" + newMatchKills + "-kill streak</bold></red><gray>!</gray>"));
                    }
                }
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

        // Instant clickable Spectator Quick-Action bar!
        victim.sendMessage(FormatUtil.mm(PREFIX + "<gray>Spectator Tools:</gray> "
                + "<aqua><click:run_command:'/event spectate'><hover:show_text:'<aqua>Teleport to any alive fighter</aqua>'>[👁 Spectate]</hover></click></aqua> "
                + "<gold><click:run_command:'/event vote'><hover:show_text:'<gold>Predict winner for Audience Points</gold>'>[🎯 Predict Winner]</hover></click></gold> "
                + "<yellow><click:run_command:'/event top'><hover:show_text:'<yellow>Open Leaderboards</yellow>'>[🏆 Top]</hover></click></yellow>"));

        if (plugin.getConfig().getBoolean("effects.auto-respawn", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (victim.isOnline() && victim.isDead()) {
                    victim.spigot().respawn();
                }
            }, 2L);
        }

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
                    pending.restore(event.getPlayer(), resolveReturnLocation());
                    data.removePersistedSnapshot(id);
                });
            }
            return;
        }
        Arena arena = arenaManager.get(selectedArena);
        // Prioritize the active arena's own elevated audience gallery so spectators watch the fight live!
        Location spectator = arena != null ? arenaManager.firstSpawn(arena, "audience") : null;
        if (spectator == null) spectator = configuredLocation("spectator");
        if (spectator != null) event.setRespawnLocation(spectator);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = event.getPlayer();
            if (participants.contains(id) && !alive.contains(id)) {
                p.getInventory().clear();
                p.setGameMode(GameMode.SPECTATOR);
                spectators.add(id);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (disconnectedFighters.contains(id) && eventRunning && !celebrating) {
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
                applyCurrentBorderToPlayer(player);
            });
            return;
        }
        PlayerSnapshot pending = data.loadPersistedSnapshot(id);
        if (pending != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = event.getPlayer();
                if (player.isOnline()) {
                    pending.restore(player, resolveReturnLocation());
                    data.removePersistedSnapshot(id);
                    snapshots.remove(id);
                    participants.remove(id);
                    alive.remove(id);
                    spectators.remove(id);
                    player.sendMessage(FormatUtil.mm(PREFIX + "<green>Your inventory and player state were recovered from the previous event session.</green>"));
                }
            });
            return;
        }
        if (plugin.getConfig().getBoolean("dedicated-server-mode", true)
                && plugin.getConfig().getBoolean("lobby-protection.teleport-on-server-join", true)
                && !event.getPlayer().hasPermission("clasherevents.admin")) {
            Player player = event.getPlayer();
            arenaManager.ensureLobbyReady(() -> {
                if (player.isOnline() && !alive.contains(player.getUniqueId())) {
                    Location lobby = lobbyLocation();
                    if (lobby != null) {
                        player.setFallDistance(0f);
                        player.teleport(lobby);
                    }
                }
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        parkourStartedAt.remove(id);
        clearPlayerBorder(event.getPlayer());
        hud.clearForPlayer(event.getPlayer());
        if (eventRunning && !celebrating && alive.contains(id)) {
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
                    eliminationOrder.add(id);
                    broadcast("<red>" + FormatUtil.escape(event.getPlayer().getName()) + " failed to reconnect and was eliminated.</red>");
                    processEliminationAfterDisconnect();
                }
            }, graceSec * 20L));
        } else if (eventOpen && participants.remove(id)) {
            broadcast("<gray>" + FormatUtil.escape(event.getPlayer().getName()) + " left the registration.</gray>");
        }
    }

    private void processEliminationAfterDisconnect() {
        if (celebrating) return;
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
