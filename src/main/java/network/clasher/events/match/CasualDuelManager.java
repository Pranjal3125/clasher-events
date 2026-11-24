package network.clasher.events.match;

import network.clasher.events.arena.ArenaBlockTracker;
import network.clasher.events.arena.ArenaManager;
import network.clasher.events.hud.EventHudManager;
import network.clasher.events.kit.KitManager;
import network.clasher.events.model.Arena;
import network.clasher.events.model.EventMode;
import network.clasher.events.model.PlayerSnapshot;
import network.clasher.events.storage.DataStore;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Multi-Slot Casual 1v1 Queue + 1v1 Challenge + 2v2/3v3/4v4 Party Duel & Party Split engine:
 * - Dynamically allocates any free dedicated duel arena (up to 8+ simultaneous same-kit 1v1s!)
 * - Automatic Admin Event Priority Override: cleanly ends & clears queues whenever an official Server Event opens
 * - Live Ping Sidebar, Pro Combat Analytics (hits, crit %, longest combo, damage), Post-Duel Inventory Viewer,
 *   instant `[🔁 Rematch]` & `[⚔ Play Again]` chat buttons, and Anti-Stall Sudden Death + Damage Tie-Breaker!
 */
public final class CasualDuelManager implements Listener {
    private static final String PREFIX = "<dark_gray>[<aqua><bold>DUEL</bold></aqua>]</dark_gray> ";
    private static final String PARTY_PREFIX = "<dark_gray>[<light_purple><bold>PARTY</bold></light_purple>]</dark_gray> ";
    private static final EnumSet<PlayerTeleportEvent.TeleportCause> GUARDED_TP = EnumSet.of(
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
            PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT,
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_GATEWAY
    );

    private record DuelRequest(UUID challenger, UUID target, String kitId, boolean partyDuel, long expiresAtMs) {}

    private static final class ActiveDuel {
        final String arenaId;
        final String kitId;
        final boolean partyMatch;
        final Set<UUID> sideA = new LinkedHashSet<>();
        final Set<UUID> sideB = new LinkedHashSet<>();
        final Set<UUID> aliveA = new LinkedHashSet<>();
        final Set<UUID> aliveB = new LinkedHashSet<>();
        final Map<UUID, Integer> sideTeams = new HashMap<>();
        final ArenaBlockTracker tracker = new ArenaBlockTracker();
        final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
        final Map<UUID, Integer> hitsLanded = new HashMap<>();
        final Map<UUID, Integer> critHits = new HashMap<>();
        final Map<UUID, Integer> currentCombo = new HashMap<>();
        final Map<UUID, Integer> longestCombo = new HashMap<>();
        final Map<UUID, Double> damageDealt = new HashMap<>();
        final long createdAtMs = System.currentTimeMillis();
        long combatStartedAtMs = System.currentTimeMillis();
        boolean graceActive;
        boolean finishing;
        boolean suddenDeathActive;
        long suddenDeathStartedMs;
        double borderSize = 44.0;
        BukkitTask graceTask;

        ActiveDuel(String arenaId, String kitId, List<UUID> teamA, List<UUID> teamB, boolean partyMatch) {
            this.arenaId = arenaId;
            this.kitId = kitId;
            this.partyMatch = partyMatch;
            for (UUID id : teamA) {
                sideA.add(id);
                aliveA.add(id);
                sideTeams.put(id, 1);
            }
            for (UUID id : teamB) {
                sideB.add(id);
                aliveB.add(id);
                sideTeams.put(id, 2);
            }
        }

        Set<UUID> allPlayers() {
            Set<UUID> all = new LinkedHashSet<>(sideA);
            all.addAll(sideB);
            return all;
        }

        boolean isDestructible() {
            return kitId.equals("build-uhc") || kitId.equals("crystal") || kitId.equals("uhc") || kitId.equals("smp");
        }

        int sideOf(UUID id) {
            return sideTeams.getOrDefault(id, 0);
        }

        boolean sameSide(UUID a, UUID b) {
            int sa = sideOf(a);
            return sa != 0 && sa == sideOf(b);
        }

        double sideDamage(Set<UUID> side) {
            double total = 0.0;
            for (UUID id : side) total += damageDealt.getOrDefault(id, 0.0);
            return total;
        }
    }

    private final JavaPlugin plugin;
    private final ArenaManager arenas;
    private final KitManager kits;
    private final DataStore data;
    private final EventManager events;
    private final EventHudManager hud;
    private final PartyManager parties = new PartyManager();
    private final DuelSnapshotStore snapshotStore = new DuelSnapshotStore();
    private WarmupFfaManager warmupFfa;

    private final Map<UUID, DuelRequest> pendingByTarget = new HashMap<>();
    private final Map<String, ActiveDuel> activeByArena = new HashMap<>();
    private final Map<UUID, ActiveDuel> activeByPlayer = new HashMap<>();
    private final Map<String, Deque<UUID>> queueByKit = new LinkedHashMap<>();
    private final Map<UUID, String> queuedKitByPlayer = new HashMap<>();
    private BukkitTask duelTickTask;

    public CasualDuelManager(
            JavaPlugin plugin,
            ArenaManager arenas,
            KitManager kits,
            DataStore data,
            EventManager events,
            EventHudManager hud
    ) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.kits = kits;
        this.data = data;
        this.events = events;
        this.hud = hud;
        startDuelLoop();
    }

    public void setWarmupFfa(WarmupFfaManager warmupFfa) {
        this.warmupFfa = warmupFfa;
    }

    public PartyManager parties() { return parties; }
    public DuelSnapshotStore snapshots() { return snapshotStore; }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("casual-duels.enabled", true);
    }

    public boolean isInCasualDuel(UUID playerId) {
        return activeByPlayer.containsKey(playerId);
    }

    public int activeDuelPlayerCount() {
        return activeByPlayer.size();
    }

    public int totalQueuedCount() {
        return queuedKitByPlayer.size();
    }

    public int queueCount(String kitId) {
        if (kitId == null) return 0;
        Deque<UUID> q = queueByKit.get(kitId.toLowerCase(Locale.ROOT));
        return q == null ? 0 : q.size();
    }

    public String queuedKitOf(UUID playerId) {
        return queuedKitByPlayer.get(playerId);
    }

    public boolean isArenaBusy(String arenaOrKitId) {
        if (arenaOrKitId == null) return false;
        String lower = arenaOrKitId.toLowerCase(Locale.ROOT);
        return activeByArena.containsKey(lower);
    }

    /**
     * Multi-Slot Dynamic Arena Allocation: prefers the kit's dedicated duel arena first,
     * then automatically overflows into any free dedicated duel arena (up to 8 simultaneous same-kit 1v1s!).
     */
    public String allocateFreeDuelArena(String kitId, boolean preferLarge) {
        if (preferLarge) {
            if (arenas.exists(kitId) && !isArenaBusy(kitId) && !events.isArenaInUse(kitId)) return kitId;
            for (String main : arenas.mainArenaIds()) {
                if (!isArenaBusy(main) && !events.isArenaInUse(main)) return main;
            }
        }
        String primary = arenas.duelArenaId(kitId);
        if (arenas.exists(primary) && !isArenaBusy(primary) && !events.isArenaInUse(primary)) {
            return primary;
        }
        // Dynamic multi-slot overflow into ANY free dedicated 1v1 duel arena!
        for (String duelId : arenas.duelArenaIds()) {
            if (!isArenaBusy(duelId) && !events.isArenaInUse(duelId)) {
                return duelId;
            }
        }
        return null;
    }

    public int freeDuelArenaCount() {
        int free = 0;
        for (String duelId : arenas.duelArenaIds()) {
            if (!isArenaBusy(duelId) && !events.isArenaInUse(duelId)) free++;
        }
        return free;
    }

    /**
     * ADMIN PRIORITY OVERRIDE: Instantly & safely ends all active 1v1 / Party duels and clears queues
     * whenever an admin launches an official event or mode vote, freeing every arena immediately!
     */
    public void forceEndAllForOfficialEvent() {
        pendingByTarget.clear();
        clearAllQueues("<gold><bold>★ OFFICIAL EVENT OPENING!</bold></gold> <yellow>You were removed from the 1v1 queue — run <green>/event join</green>!</yellow>");
        if (activeByArena.isEmpty()) return;
        for (ActiveDuel duel : new ArrayList<>(activeByArena.values())) {
            endDuelInternal(duel, 0, "<gold><bold>★ OFFICIAL EVENT STARTING!</bold></gold> <yellow>Your duel ended cleanly to free the arena — run <green>/event join</green>!</yellow>", true);
        }
    }

    public void shutdown() {
        if (duelTickTask != null) {
            duelTickTask.cancel();
            duelTickTask = null;
        }
        pendingByTarget.clear();
        clearAllQueues(null);
        parties.clearAll();
        for (ActiveDuel duel : new ArrayList<>(activeByArena.values())) {
            endDuelInternal(duel, 0, "<gray>Duel ended due to server shutdown.</gray>", true);
        }
    }

    // -------------------------------------------------------------------------
    // 1v1 Auto-Queue System (/event queue [kit|leave])
    // -------------------------------------------------------------------------

    public void joinQueue(Player player, String rawKit) {
        if (!isEnabled()) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Casual duels & 1v1 queue are currently disabled.</red>"));
            return;
        }
        if (isOfficialEventBlocking()) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>1v1 Queue is paused while an official Server Event is active! Use <yellow>/event join</yellow>.</red>"));
            return;
        }
        UUID id = player.getUniqueId();
        if (isInCasualDuel(id) || events.participants().contains(id)) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>You are already in a duel or event!</red>"));
            return;
        }
        if (warmupFfa != null && warmupFfa.isInFfa(id)) {
            warmupFfa.leaveFfa(player, false);
        }
        String kitId = resolveKitId(rawKit);
        if (kitId == null || !kits.exists(kitId)) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Unknown kit: " + FormatUtil.escape(rawKit) + "</red>"));
            return;
        }

        // Remove from any previous kit queue first
        leaveQueueSilent(id);

        Deque<UUID> q = queueByKit.computeIfAbsent(kitId, k -> new ArrayDeque<>());
        Player matchedOpponent = null;
        while (!q.isEmpty()) {
            UUID candId = q.pollFirst();
            queuedKitByPlayer.remove(candId);
            if (candId.equals(id)) continue;
            Player cand = Bukkit.getPlayer(candId);
            if (cand != null && cand.isOnline() && !isInCasualDuel(candId) && !events.participants().contains(candId)) {
                matchedOpponent = cand;
                break;
            }
        }

        String modeLabel = EventMode.fromInput(kitId).map(EventMode::displayName).orElse(kitId);
        if (matchedOpponent != null) {
            if (warmupFfa != null && warmupFfa.isInFfa(matchedOpponent.getUniqueId())) {
                warmupFfa.leaveFfa(matchedOpponent, false);
            }
            String arenaId = allocateFreeDuelArena(kitId, false);
            if (arenaId == null) {
                // Re-queue both cleanly if every single duel slot is temporarily full
                q.addFirst(matchedOpponent.getUniqueId());
                queuedKitByPlayer.put(matchedOpponent.getUniqueId(), kitId);
                q.addLast(id);
                queuedKitByPlayer.put(id, kitId);
                player.sendMessage(FormatUtil.mm(PREFIX + "<yellow>All 8 duel arenas are currently full — you are next in line for <gold>" + FormatUtil.escape(modeLabel) + "</gold>!</yellow>"));
                return;
            }
            matchedOpponent.sendMessage(FormatUtil.mm(PREFIX + "<green><bold>MATCH FOUND!</bold></green> <yellow>"
                    + FormatUtil.escape(player.getName()) + "</yellow> <gray>(" + FormatUtil.escape(modeLabel) + ")</gray>"));
            player.sendMessage(FormatUtil.mm(PREFIX + "<green><bold>MATCH FOUND!</bold></green> <yellow>"
                    + FormatUtil.escape(matchedOpponent.getName()) + "</yellow> <gray>(" + FormatUtil.escape(modeLabel) + ")</gray>"));
            startMatch(List.of(matchedOpponent), List.of(player), kitId, arenaId, false);
            return;
        }

        q.addLast(id);
        queuedKitByPlayer.put(id, kitId);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
        player.sendMessage(FormatUtil.mm(PREFIX + "<green>Joined the <gold><bold>" + FormatUtil.escape(modeLabel)
                + "</bold></gold> 1v1 Queue!</green> <gray>Searching for opponent...</gray> "
                + "<red><click:run_command:'/event queue leave'><hover:show_text:'<red>Leave 1v1 Queue</red>'>[✖ Leave Queue]</hover></click></red>"));
    }

    public void leaveQueue(Player player) {
        String prev = leaveQueueSilent(player.getUniqueId());
        if (prev == null) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<gray>You are not in any 1v1 queue.</gray>"));
        } else {
            player.sendMessage(FormatUtil.mm(PREFIX + "<yellow>Left the <gold>" + FormatUtil.escape(prev) + "</gold> 1v1 queue.</yellow>"));
        }
    }

    public String leaveQueueSilent(UUID id) {
        String kit = queuedKitByPlayer.remove(id);
        if (kit != null) {
            Deque<UUID> q = queueByKit.get(kit);
            if (q != null) q.remove(id);
        }
        return kit;
    }

    /**
     * Cleanly forfeits an active casual 1v1 or Party duel when a player runs `/event leave`.
     */
    public boolean forfeitDuel(Player player) {
        UUID id = player.getUniqueId();
        ActiveDuel duel = activeByPlayer.get(id);
        if (duel == null || duel.finishing) return false;
        duel.aliveA.remove(id);
        duel.aliveB.remove(id);
        player.sendMessage(FormatUtil.mm(PREFIX + "<yellow>You forfeited the duel and returned to Lobby.</yellow>"));
        if (!duel.aliveA.isEmpty() && !duel.aliveB.isEmpty()) {
            // Party match still has teammates fighting: restore this leaver early
            activeByPlayer.remove(id);
            restoreDuelist(duel, player);
        } else {
            checkSideWin(duel);
        }
        return true;
    }

    private void clearAllQueues(String noticeMini) {
        if (noticeMini != null) {
            for (UUID id : new ArrayList<>(queuedKitByPlayer.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendMessage(FormatUtil.mm(noticeMini));
            }
        }
        queueByKit.clear();
        queuedKitByPlayer.clear();
    }

    // -------------------------------------------------------------------------
    // Direct 1v1 Challenges (/event duel <player> [kit])
    // -------------------------------------------------------------------------

    public void sendRequest(Player challenger, Player target, String rawKit) {
        if (!isEnabled()) {
            challenger.sendMessage(FormatUtil.mm(PREFIX + "<red>Casual player duels are currently disabled.</red>"));
            return;
        }
        if (isOfficialEventBlocking()) {
            challenger.sendMessage(FormatUtil.mm(PREFIX + "<red>Casual duels are paused while an official Server Event is active! Use <yellow>/event join</yellow>.</red>"));
            return;
        }
        if (challenger.getUniqueId().equals(target.getUniqueId())) {
            challenger.sendMessage(FormatUtil.mm(PREFIX + "<red>You cannot duel yourself!</red>"));
            return;
        }
        if (isInCasualDuel(challenger.getUniqueId()) || events.participants().contains(challenger.getUniqueId())) {
            challenger.sendMessage(FormatUtil.mm(PREFIX + "<red>You are already in a duel or event!</red>"));
            return;
        }
        if (isInCasualDuel(target.getUniqueId()) || events.participants().contains(target.getUniqueId())) {
            challenger.sendMessage(FormatUtil.mm(PREFIX + "<red>" + FormatUtil.escape(target.getName()) + " is currently busy in a duel or event.</red>"));
            return;
        }

        String kitId = resolveKitId(rawKit);
        if (kitId == null || !kits.exists(kitId)) {
            challenger.sendMessage(FormatUtil.mm(PREFIX + "<red>Unknown kit: " + FormatUtil.escape(rawKit) + "</red>"));
            return;
        }
        if (allocateFreeDuelArena(kitId, false) == null) {
            challenger.sendMessage(FormatUtil.mm(PREFIX + "<red>All 8 duel arenas are currently full — try again in a few seconds!</red>"));
            return;
        }

        long expireSec = Math.max(15L, plugin.getConfig().getLong("casual-duels.request-expire-seconds", 60L));
        pendingByTarget.put(target.getUniqueId(), new DuelRequest(challenger.getUniqueId(), target.getUniqueId(), kitId, false, System.currentTimeMillis() + expireSec * 1000L));

        String modeLabel = EventMode.fromInput(kitId).map(EventMode::displayName).orElse(kitId);
        challenger.sendMessage(FormatUtil.mm(PREFIX + "<green>Sent a <gold>" + FormatUtil.escape(modeLabel)
                + "</gold> 1v1 duel request to <yellow>" + FormatUtil.escape(target.getName()) + "</yellow> (" + expireSec + "s).</green>"));
        target.sendMessage(FormatUtil.mm(PREFIX + "<yellow><bold>" + FormatUtil.escape(challenger.getName())
                + "</bold></yellow> <gray>challenged you to a</gray> <gold><bold>" + FormatUtil.escape(modeLabel) + "</bold></gold> <gray>1v1 duel!</gray> "
                + "<green><click:run_command:'/event accept " + challenger.getName() + "'><hover:show_text:'<green>Accept 1v1 Duel</green>'>[✔ Accept]</hover></click></green> "
                + "<red><click:run_command:'/event decline " + challenger.getName() + "'><hover:show_text:'<red>Decline Duel</red>'>[✖ Decline]</hover></click></red>"));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.3f);
    }

    public void acceptRequest(Player target, String optionalChallengerName) {
        if (!isEnabled() || isOfficialEventBlocking()) {
            target.sendMessage(FormatUtil.mm(PREFIX + "<red>Casual duels are unavailable while an official event is active.</red>"));
            return;
        }
        DuelRequest req = pendingByTarget.get(target.getUniqueId());
        if (req == null || System.currentTimeMillis() > req.expiresAtMs()) {
            pendingByTarget.remove(target.getUniqueId());
            target.sendMessage(FormatUtil.mm(PREFIX + "<red>You have no active duel request (or it expired).</red>"));
            return;
        }
        Player challenger = Bukkit.getPlayer(req.challenger());
        if (challenger == null || !challenger.isOnline()) {
            pendingByTarget.remove(target.getUniqueId());
            target.sendMessage(FormatUtil.mm(PREFIX + "<red>The challenger is no longer online.</red>"));
            return;
        }
        if (optionalChallengerName != null && !optionalChallengerName.isBlank()
                && !challenger.getName().equalsIgnoreCase(optionalChallengerName)) {
            target.sendMessage(FormatUtil.mm(PREFIX + "<red>No duel request from " + FormatUtil.escape(optionalChallengerName) + ".</red>"));
            return;
        }
        pendingByTarget.remove(target.getUniqueId());

        if (req.partyDuel()) {
            PartyManager.Party pA = parties.partyOf(challenger.getUniqueId());
            PartyManager.Party pB = parties.partyOf(target.getUniqueId());
            if (pA == null || pB == null || pA.id() == pB.id()) {
                target.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Both parties must still exist to start the party duel.</red>"));
                return;
            }
            List<Player> sideA = onlinePartyMembers(pA);
            List<Player> sideB = onlinePartyMembers(pB);
            if (sideA.isEmpty() || sideB.isEmpty()) {
                target.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Not enough online party members.</red>"));
                return;
            }
            for (Player p : sideA) {
                if (isInCasualDuel(p.getUniqueId()) || events.participants().contains(p.getUniqueId())) {
                    target.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>" + FormatUtil.escape(p.getName()) + " is busy in another match!</red>"));
                    return;
                }
            }
            for (Player p : sideB) {
                if (isInCasualDuel(p.getUniqueId()) || events.participants().contains(p.getUniqueId())) {
                    target.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>" + FormatUtil.escape(p.getName()) + " is busy in another match!</red>"));
                    return;
                }
            }
            boolean large = Math.max(sideA.size(), sideB.size()) >= 3;
            String arenaId = allocateFreeDuelArena(req.kitId(), large);
            if (arenaId == null) {
                target.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>No free duel arena available right now.</red>"));
                return;
            }
            startMatch(sideA, sideB, req.kitId(), arenaId, true);
            return;
        }

        if (isInCasualDuel(challenger.getUniqueId()) || isInCasualDuel(target.getUniqueId())
                || events.participants().contains(challenger.getUniqueId()) || events.participants().contains(target.getUniqueId())) {
            target.sendMessage(FormatUtil.mm(PREFIX + "<red>One of you is already in a match or event.</red>"));
            return;
        }
        String arenaId = allocateFreeDuelArena(req.kitId(), false);
        if (arenaId == null) {
            target.sendMessage(FormatUtil.mm(PREFIX + "<red>All duel arenas are currently full! Try again shortly.</red>"));
            return;
        }
        startMatch(List.of(challenger), List.of(target), req.kitId(), arenaId, false);
    }

    public void declineRequest(Player target) {
        DuelRequest req = pendingByTarget.remove(target.getUniqueId());
        if (req == null) {
            target.sendMessage(FormatUtil.mm(PREFIX + "<red>No pending duel request to decline.</red>"));
            return;
        }
        target.sendMessage(FormatUtil.mm(PREFIX + "<gray>Declined the duel request.</gray>"));
        Player challenger = Bukkit.getPlayer(req.challenger());
        if (challenger != null && challenger.isOnline()) {
            challenger.sendMessage(FormatUtil.mm(PREFIX + "<red>" + FormatUtil.escape(target.getName()) + " declined your duel request.</red>"));
        }
    }

    // -------------------------------------------------------------------------
    // Casual Party Commands & 2v2 / 3v3 Party Duels + Party Split
    // -------------------------------------------------------------------------

    public void partyInvite(Player leader, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Player not online: " + FormatUtil.escape(targetName) + "</red>"));
            return;
        }
        PartyManager.Result res = parties.invite(leader.getUniqueId(), target.getUniqueId(), System.currentTimeMillis(), 60_000L);
        if (!res.ok()) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>" + FormatUtil.escape(res.message()) + "</red>"));
            return;
        }
        leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<green>Invited <yellow>" + FormatUtil.escape(target.getName()) + "</yellow> to your party!</green>"));
        target.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<yellow><bold>" + FormatUtil.escape(leader.getName())
                + "</bold></yellow> <gray>invited you to their Casual Duel Party!</gray> "
                + "<green><click:run_command:'/event party accept " + leader.getName() + "'><hover:show_text:'<green>Join Party</green>'>[✔ Accept]</hover></click></green> "
                + "<red><click:run_command:'/event party decline " + leader.getName() + "'><hover:show_text:'<red>Decline</red>'>[✖ Decline]</hover></click></red>"));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.3f);
    }

    public void partyAccept(Player target, String optionalLeader) {
        UUID leaderId = null;
        if (optionalLeader != null && !optionalLeader.isBlank()) {
            Player l = Bukkit.getPlayerExact(optionalLeader);
            if (l == null) {
                target.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Leader is no longer online.</red>"));
                return;
            }
            leaderId = l.getUniqueId();
        }
        PartyManager.Result res = parties.accept(target.getUniqueId(), leaderId, System.currentTimeMillis());
        if (!res.ok()) {
            target.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>" + FormatUtil.escape(res.message()) + "</red>"));
            return;
        }
        broadcastParty(res.party(), "<green><bold>" + FormatUtil.escape(target.getName()) + "</bold></green> <gray>joined the party! (" + res.party().size() + " members)</gray>");
    }

    public void partyDecline(Player target, String optionalLeader) {
        UUID leaderId = optionalLeader != null ? Optional.ofNullable(Bukkit.getPlayerExact(optionalLeader)).map(Player::getUniqueId).orElse(null) : null;
        PartyManager.Result res = parties.decline(target.getUniqueId(), leaderId);
        if (!res.ok()) {
            target.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>" + FormatUtil.escape(res.message()) + "</red>"));
            return;
        }
        target.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<gray>Declined party invite.</gray>"));
    }

    public void partyLeave(Player player) {
        PartyManager.Result res = parties.leave(player.getUniqueId());
        if (!res.ok()) {
            player.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>" + FormatUtil.escape(res.message()) + "</red>"));
            return;
        }
        player.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<yellow>You left the party.</yellow>"));
        if (res.party() != null && res.party().size() > 0) {
            broadcastParty(res.party(), "<yellow>" + FormatUtil.escape(player.getName()) + " left the party.</yellow>");
        }
    }

    public void partyKick(Player leader, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Player not found online.</red>"));
            return;
        }
        PartyManager.Result res = parties.kick(leader.getUniqueId(), target.getUniqueId());
        if (!res.ok()) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>" + FormatUtil.escape(res.message()) + "</red>"));
            return;
        }
        target.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>You were kicked from the party.</red>"));
        broadcastParty(res.party(), "<yellow>" + FormatUtil.escape(target.getName()) + " was removed from the party.</yellow>");
    }

    public void partyDisband(Player leader) {
        PartyManager.Party prev = parties.partyOf(leader.getUniqueId());
        List<UUID> notify = prev != null ? new ArrayList<>(prev.members()) : List.of();
        PartyManager.Result res = parties.disband(leader.getUniqueId());
        if (!res.ok()) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>" + FormatUtil.escape(res.message()) + "</red>"));
            return;
        }
        for (UUID m : notify) {
            Player p = Bukkit.getPlayer(m);
            if (p != null) p.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Party disbanded by leader.</red>"));
        }
    }

    public void partyList(Player viewer) {
        PartyManager.Party party = parties.partyOf(viewer.getUniqueId());
        if (party == null) {
            viewer.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<gray>You are not in a party. Start one with <yellow>/event party invite <player></yellow>!</gray>"));
            return;
        }
        List<String> names = new ArrayList<>();
        for (UUID m : party.members()) {
            Player mp = Bukkit.getPlayer(m);
            String n = mp != null ? mp.getName() : Bukkit.getOfflinePlayer(m).getName();
            if (n == null) n = "Player";
            names.add((m.equals(party.leader()) ? "<gold>★ " : "<white>") + FormatUtil.escape(n) + (m.equals(party.leader()) ? "</gold>" : "</white>"));
        }
        viewer.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<light_purple><bold>Party (" + party.size() + ")</bold></light_purple> <gray>—</gray> " + String.join("<gray>, </gray>", names)));
    }

    public void sendPartyDuelRequest(Player leader, String targetPlayerName, String rawKit) {
        if (!isEnabled() || isOfficialEventBlocking()) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Party duels are paused while an official event is active.</red>"));
            return;
        }
        PartyManager.Party myParty = parties.partyOf(leader.getUniqueId());
        if (myParty == null || !leader.getUniqueId().equals(myParty.leader())) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>You must be the leader of a party! Invite teammates with <yellow>/event party invite <player></yellow>.</red>"));
            return;
        }
        Player otherPlayer = Bukkit.getPlayerExact(targetPlayerName);
        if (otherPlayer == null) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Player not online: " + FormatUtil.escape(targetPlayerName) + "</red>"));
            return;
        }
        PartyManager.Party otherParty = parties.partyOf(otherPlayer.getUniqueId());
        if (otherParty == null || otherParty.id() == myParty.id()) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>" + FormatUtil.escape(otherPlayer.getName()) + " is not in an opposing party! Use <yellow>/event party split [kit]</yellow> to fight your own party.</red>"));
            return;
        }
        Player otherLeader = Bukkit.getPlayer(otherParty.leader());
        if (otherLeader == null) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Opposing party leader is offline.</red>"));
            return;
        }
        String kitId = resolveKitId(rawKit == null || rawKit.isBlank() ? "nethpot" : rawKit);
        if (kitId == null || !kits.exists(kitId)) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Unknown kit: " + FormatUtil.escape(rawKit) + "</red>"));
            return;
        }

        long expireSec = Math.max(15L, plugin.getConfig().getLong("casual-duels.request-expire-seconds", 60L));
        pendingByTarget.put(otherLeader.getUniqueId(),
                new DuelRequest(leader.getUniqueId(), otherLeader.getUniqueId(), kitId, true, System.currentTimeMillis() + expireSec * 1000L));

        String modeLabel = EventMode.fromInput(kitId).map(EventMode::displayName).orElse(kitId);
        String fmt = myParty.size() + "v" + otherParty.size();
        broadcastParty(myParty, "<green>Challenged <yellow>" + FormatUtil.escape(otherLeader.getName()) + "'s party</yellow> to a <gold>" + fmt + " " + FormatUtil.escape(modeLabel) + "</gold> duel!</green>");
        otherLeader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<yellow><bold>" + FormatUtil.escape(leader.getName())
                + "'s party</bold></yellow> <gray>challenged your party to a</gray> <gold><bold>" + fmt + " " + FormatUtil.escape(modeLabel) + "</bold></gold> <gray>duel!</gray> "
                + "<green><click:run_command:'/event accept " + leader.getName() + "'><hover:show_text:'<green>Accept Party Duel</green>'>[✔ Accept]</hover></click></green> "
                + "<red><click:run_command:'/event decline " + leader.getName() + "'><hover:show_text:'<red>Decline</red>'>[✖ Decline]</hover></click></red>"));
        otherLeader.playSound(otherLeader.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.3f);
    }

    public void startPartySplit(Player leader, String rawKit) {
        if (!isEnabled() || isOfficialEventBlocking()) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Party splits are paused while an official event is active.</red>"));
            return;
        }
        PartyManager.Party party = parties.partyOf(leader.getUniqueId());
        if (party == null || !leader.getUniqueId().equals(party.leader())) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>You must be the leader of a party to start a Party Split!</red>"));
            return;
        }
        List<Player> members = onlinePartyMembers(party);
        if (members.size() < 2) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>You need at least 2 online party members for a Party Split!</red>"));
            return;
        }
        for (Player p : members) {
            if (isInCasualDuel(p.getUniqueId()) || events.participants().contains(p.getUniqueId())) {
                leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>" + FormatUtil.escape(p.getName()) + " is already in a match!</red>"));
                return;
            }
        }
        String kitId = resolveKitId(rawKit == null || rawKit.isBlank() ? "nethpot" : rawKit);
        if (kitId == null || !kits.exists(kitId)) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>Unknown kit: " + FormatUtil.escape(rawKit) + "</red>"));
            return;
        }
        Collections.shuffle(members);
        int mid = members.size() / 2;
        List<Player> sideA = new ArrayList<>(members.subList(0, mid));
        List<Player> sideB = new ArrayList<>(members.subList(mid, members.size()));
        boolean large = Math.max(sideA.size(), sideB.size()) >= 3;
        String arenaId = allocateFreeDuelArena(kitId, large);
        if (arenaId == null) {
            leader.sendMessage(FormatUtil.mm(PARTY_PREFIX + "<red>No free duel arenas available right now!</red>"));
            return;
        }
        startMatch(sideA, sideB, kitId, arenaId, true);
    }

    private List<Player> onlinePartyMembers(PartyManager.Party party) {
        List<Player> out = new ArrayList<>();
        for (UUID m : party.members()) {
            Player p = Bukkit.getPlayer(m);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    private void broadcastParty(PartyManager.Party party, String miniBody) {
        for (UUID m : party.members()) {
            Player p = Bukkit.getPlayer(m);
            if (p != null) p.sendMessage(FormatUtil.mm(PARTY_PREFIX + miniBody));
        }
    }

    // -------------------------------------------------------------------------
    // Match Launch, Live Ping & Sudden Death Loop, and Post-Duel Summary
    // -------------------------------------------------------------------------

    private void startMatch(List<Player> sideAPlayers, List<Player> sideBPlayers, String kitId, String arenaId, boolean partyMatch) {
        Arena arena = arenas.get(arenaId);
        if (arena == null) return;
        List<Location> spawns = arenas.spawns(arena, "final");
        if (spawns.size() < 2) spawns = arenas.spawns(arena, "wave1");
        if (spawns.size() < 2) {
            for (Player p : sideAPlayers) p.sendMessage(FormatUtil.mm(PREFIX + "<red>Arena " + arenaId + " is missing duel spawns.</red>"));
            return;
        }
        final List<Location> finalSpawns = spawns;

        List<UUID> idsA = new ArrayList<>();
        List<UUID> idsB = new ArrayList<>();
        for (Player p : sideAPlayers) {
            leaveQueueSilent(p.getUniqueId());
            if (warmupFfa != null && warmupFfa.isInFfa(p.getUniqueId())) warmupFfa.leaveFfa(p, false);
            idsA.add(p.getUniqueId());
        }
        for (Player p : sideBPlayers) {
            leaveQueueSilent(p.getUniqueId());
            if (warmupFfa != null && warmupFfa.isInFfa(p.getUniqueId())) warmupFfa.leaveFfa(p, false);
            idsB.add(p.getUniqueId());
        }

        ActiveDuel duel = new ActiveDuel(arenaId, kitId, idsA, idsB, partyMatch);
        duel.borderSize = Math.min(60.0, arena.playableRadius() * 2.0);
        activeByArena.put(arenaId, duel);
        for (UUID id : duel.allPlayers()) activeByPlayer.put(id, duel);

        arenas.ensureArenaReady(arenaId, null, () -> {
            if (duel.finishing || isOfficialEventBlocking()) {
                endDuelInternal(duel, 0, "<yellow>Duel cancelled.</yellow>", true);
                return;
            }
            duel.tracker.startTracking(arenas.sharedWorldName(), arena);
            arenas.setArenaBuildAllowed(arenaId, duel.isDestructible());

            spawnSide(duel, sideAPlayers, finalSpawns.get(0), arena);
            spawnSide(duel, sideBPlayers, finalSpawns.get(1), arena);

            String labelA = sideLabel(sideAPlayers);
            String labelB = sideLabel(sideBPlayers);
            Set<UUID> all = duel.allPlayers();

            duel.graceActive = true;
            int[] remain = {3};
            duel.graceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (duel.finishing) {
                    if (duel.graceTask != null) duel.graceTask.cancel();
                    return;
                }
                if (remain[0] <= 0) {
                    duel.graceActive = false;
                    duel.combatStartedAtMs = System.currentTimeMillis();
                    if (duel.graceTask != null) duel.graceTask.cancel();
                    hud.broadcastTitle(all, "<green><bold>FIGHT!</bold></green>",
                            "<red>" + FormatUtil.escape(labelA) + "</red> <gray>vs</gray> <blue>" + FormatUtil.escape(labelB) + "</blue>", 50, 900, 250);
                    for (UUID id : all) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.5f);
                    }
                    return;
                }
                hud.broadcastTitle(all, "<gold><bold>" + remain[0] + "</bold></gold>",
                        "<gray>" + (partyMatch ? "Party Duel" : "1v1 Duel") + " starting...</gray>", 50, 750, 150);
                for (UUID id : all) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.1f);
                }
                remain[0]--;
            }, 0L, 20L);
        });
    }

    private void spawnSide(ActiveDuel duel, List<Player> players, Location baseSpawn, Arena arena) {
        int n = players.size();
        for (int i = 0; i < n; i++) {
            Player p = players.get(i);
            if (p == null || !p.isOnline()) continue;
            Location loc = baseSpawn.clone();
            if (n > 1) {
                double zOff = (i - (n - 1) / 2.0) * 1.5;
                if (arena.isInsideCombatBounds(loc.getX(), loc.getY(), loc.getZ() + zOff)) loc.add(0, 0, zOff);
            }
            prepareDuelist(duel, p, loc, arena);
        }
    }

    private void prepareDuelist(ActiveDuel duel, Player p, Location spawn, Arena arena) {
        PlayerSnapshot snap = new PlayerSnapshot(p);
        duel.snapshots.put(p.getUniqueId(), snap);
        data.persistSnapshot(p.getUniqueId(), snap);

        p.setGameMode(GameMode.SURVIVAL);
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(attr != null ? attr.getValue() : 20.0);
        p.setFoodLevel(20);
        p.setSaturation(5.0f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.teleport(spawn);
        kits.giveKit(p, duel.kitId, data.kitLayout(p.getUniqueId(), duel.kitId));

        if (duel.isDestructible()) {
            arenas.addArenaBuilder(duel.arenaId, p.getUniqueId());
        }
        WorldBorder wb = Bukkit.createWorldBorder();
        wb.setCenter(arena.centerX(), arena.centerZ());
        wb.setSize(duel.borderSize);
        wb.setWarningDistance(6);
        p.setWorldBorder(wb);
    }

    private void startDuelLoop() {
        duelTickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (activeByArena.isEmpty()) return;
            long now = System.currentTimeMillis();
            boolean sdEnabled = plugin.getConfig().getBoolean("sudden-death.enabled", true);
            long sdTriggerSec = Math.max(30L, plugin.getConfig().getLong("sudden-death.duel-seconds", 180L));
            long sdTiebreakSec = Math.max(15L, plugin.getConfig().getLong("sudden-death.tiebreak-after-seconds", 60L));
            double sdBorderSize = Math.max(8.0, plugin.getConfig().getDouble("sudden-death.border-size", 12.0));

            for (ActiveDuel duel : new ArrayList<>(activeByArena.values())) {
                if (duel.finishing) continue;
                Arena arena = arenas.get(duel.arenaId);
                long elapsedSec = duel.graceActive ? 0L : Math.max(0L, (now - duel.combatStartedAtMs) / 1000L);
                int sdRemain = 0;

                // Anti-Stall Sudden Death & Damage Tie-Breaker!
                if (sdEnabled && !duel.graceActive) {
                    if (!duel.suddenDeathActive && elapsedSec >= sdTriggerSec) {
                        duel.suddenDeathActive = true;
                        duel.suddenDeathStartedMs = now;
                        duel.borderSize = sdBorderSize;
                        Set<UUID> all = duel.allPlayers();
                        hud.broadcastTitle(all, "<red><bold>⚡ SUDDEN DEATH! ⚡</bold></red>",
                                "<yellow>Border shrinking to " + (int) sdBorderSize + "x" + (int) sdBorderSize + " — Highest damage wins in " + sdTiebreakSec + "s!</yellow>",
                                100, 1800, 300);
                        for (UUID id : all) {
                            Player p = Bukkit.getPlayer(id);
                            if (p == null || !p.isOnline()) continue;
                            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.1f);
                            if (duel.aliveA.contains(id) || duel.aliveB.contains(id)) {
                                p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 20 * 120, 0, false, false, false));
                                if (arena != null) {
                                    WorldBorder wb = Bukkit.createWorldBorder();
                                    wb.setCenter(arena.centerX(), arena.centerZ());
                                    wb.setSize(Math.min(60.0, arena.playableRadius() * 2.0));
                                    wb.changeSize(sdBorderSize, 20L * 15L);
                                    wb.setWarningDistance(4);
                                    p.setWorldBorder(wb);
                                }
                            }
                        }
                    } else if (duel.suddenDeathActive) {
                        long sdElapsed = (now - duel.suddenDeathStartedMs) / 1000L;
                        sdRemain = (int) Math.max(0L, sdTiebreakSec - sdElapsed);
                        // Real Sudden Death border damage!
                        if (arena != null && sdElapsed >= 15L) {
                            double half = sdBorderSize / 2.0;
                            for (UUID id : duel.allPlayers()) {
                                if (!duel.aliveA.contains(id) && !duel.aliveB.contains(id)) continue;
                                Player p = Bukkit.getPlayer(id);
                                if (p == null || !p.isOnline() || p.isDead()) continue;
                                double out = Math.max(Math.abs(p.getLocation().getX() - arena.centerX()),
                                        Math.abs(p.getLocation().getZ() - arena.centerZ())) - half;
                                if (out > 0.0) {
                                    p.damage(2.5);
                                    p.sendActionBar(FormatUtil.mm("<red><bold>⚡ SUDDEN DEATH BORDER! Move to center!</bold></red>"));
                                }
                            }
                        }
                        if (sdElapsed >= sdTiebreakSec) {
                            double dmgA = duel.sideDamage(duel.sideA);
                            double dmgB = duel.sideDamage(duel.sideB);
                            int winSide = dmgA >= dmgB ? 1 : 2;
                            endDuelInternal(duel, winSide, "<gold><bold>⚡ SUDDEN DEATH TIE-BREAKER!</bold></gold> <yellow>Highest Damage Dealt wins ("
                                    + String.format(Locale.ROOT, "%.0f", dmgA) + " vs " + String.format(Locale.ROOT, "%.0f", dmgB) + " DMG)!</yellow>", false);
                            continue;
                        }
                    }
                }

                // Live Ping & Duel Sidebar for every fighter!
                String kitLabel = EventMode.fromInput(duel.kitId).map(EventMode::displayName).orElse(duel.kitId);
                String matchTitle = duel.partyMatch ? (duel.sideA.size() + "v" + duel.sideB.size() + " Party") : "1v1 Duel";
                for (UUID id : duel.allPlayers()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null || !p.isOnline()) continue;
                    Set<UUID> enemySet = duel.sideOf(id) == 1 ? duel.sideB : duel.sideA;
                    String oppLabel;
                    int oppPing = -1;
                    if (enemySet.size() == 1) {
                        UUID eid = enemySet.iterator().next();
                        Player ep = Bukkit.getPlayer(eid);
                        oppLabel = ep != null ? ep.getName() : "Opponent";
                        if (ep != null && ep.isOnline()) oppPing = Math.max(0, ep.getPing());
                    } else {
                        int aliveEnemy = duel.sideOf(id) == 1 ? duel.aliveB.size() : duel.aliveA.size();
                        oppLabel = (duel.sideOf(id) == 1 ? "Blue" : "Red") + " (" + aliveEnemy + "/" + enemySet.size() + ")";
                    }
                    hud.updateDuelSidebar(
                            p, matchTitle, kitLabel, oppLabel, oppPing, elapsedSec,
                            duel.suddenDeathActive, sdRemain,
                            duel.partyMatch ? duel.sideTeams : Map.of()
                    );
                }
            }
        }, 20L, 20L);
    }

    private void checkSideWin(ActiveDuel duel) {
        if (duel.finishing) return;
        if (!duel.aliveA.isEmpty() && !duel.aliveB.isEmpty()) return;
        int winningSide = !duel.aliveA.isEmpty() ? 1 : (!duel.aliveB.isEmpty() ? 2 : 0);
        Bukkit.getScheduler().runTask(plugin, () -> endDuelInternal(duel, winningSide, null, false));
    }

    private void endDuelInternal(ActiveDuel duel, int winningSide, String reasonMini, boolean immediateRollback) {
        if (duel.finishing) return;
        duel.finishing = true;
        duel.graceActive = false;
        if (duel.graceTask != null) {
            duel.graceTask.cancel();
            duel.graceTask = null;
        }
        activeByArena.remove(duel.arenaId);
        Set<UUID> all = duel.allPlayers();
        for (UUID id : all) activeByPlayer.remove(id);

        // Capture Post-Duel Inventory & Combat Analytics Snapshots BEFORE restoring inventories!
        Map<UUID, String> invTokens = new LinkedHashMap<>();
        if (winningSide == 1 || winningSide == 2) {
            for (UUID id : all) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                boolean isWinner = duel.sideOf(id) == winningSide;
                String tok = snapshotStore.capture(
                        p, duel.kitId, isWinner,
                        duel.hitsLanded.getOrDefault(id, 0),
                        duel.critHits.getOrDefault(id, 0),
                        duel.longestCombo.getOrDefault(id, 0),
                        duel.damageDealt.getOrDefault(id, 0.0)
                );
                invTokens.put(id, tok);
            }
        }

        // Send crisp Post-Duel Recap + Clickable [Inspect Inv] + [Rematch] + [Queue Again] bar!
        String kitLabel = EventMode.fromInput(duel.kitId).map(EventMode::displayName).orElse(duel.kitId);
        if (winningSide == 1 || winningSide == 2) {
            Set<UUID> winIds = winningSide == 1 ? duel.sideA : duel.sideB;
            Set<UUID> loseIds = winningSide == 1 ? duel.sideB : duel.sideA;
            UUID repWin = winIds.iterator().next();
            UUID repLose = loseIds.iterator().next();
            String winNames = formatUuidNames(winIds);
            String loseNames = formatUuidNames(loseIds);
            Player wPlayer = Bukkit.getPlayer(repWin);
            String hpTxt = (!duel.partyMatch && wPlayer != null && !wPlayer.isDead())
                    ? " <gray>(<red>" + String.format(Locale.ROOT, "%.1f", wPlayer.getHealth()) + " ❤</red>)</gray>" : "";

            String headline = PREFIX + "<gold><bold>" + FormatUtil.escape(winNames) + "</bold></gold> <green>defeated</green> <yellow>"
                    + FormatUtil.escape(loseNames) + "</yellow> <gray>in</gray> <aqua>" + FormatUtil.escape(kitLabel) + "</aqua>" + hpTxt;

            String wToken = invTokens.get(repWin);
            String lToken = invTokens.get(repLose);
            StringBuilder invButtons = new StringBuilder(PREFIX + "<gray>Inspect:</gray> ");
            if (wToken != null) {
                invButtons.append("<green><click:run_command:'/event inv ").append(wToken)
                        .append("'><hover:show_text:'<green>View remaining items, pots, hits & combo</green>'>[🔍 ")
                        .append(FormatUtil.escape(safeName(repWin))).append("'s Inv]</hover></click></green> ");
            }
            if (lToken != null) {
                invButtons.append("<yellow><click:run_command:'/event inv ").append(lToken)
                        .append("'><hover:show_text:'<yellow>View opponent inventory & stats</yellow>'>[🔍 ")
                        .append(FormatUtil.escape(safeName(repLose))).append("'s Inv]</hover></click></yellow> ");
            }

            for (UUID id : all) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                if (reasonMini != null) p.sendMessage(FormatUtil.mm(PREFIX + reasonMini));
                p.sendMessage(FormatUtil.mm(headline));
                StringBuilder actionBar = new StringBuilder(invButtons);
                if (!duel.partyMatch) {
                    UUID oppId = duel.sideA.contains(id) ? repLose : repWin;
                    if (oppId.equals(id)) oppId = duel.sideA.contains(id) ? duel.sideB.iterator().next() : duel.sideA.iterator().next();
                    String oppName = safeName(oppId);
                    actionBar.append("<aqua><click:run_command:'/event duel ").append(oppName).append(" ").append(duel.kitId)
                            .append("'><hover:show_text:'<aqua>Send instant rematch!</aqua>'>[🔁 Rematch]</hover></click></aqua> ")
                            .append("<gold><click:run_command:'/event queue ").append(duel.kitId)
                            .append("'><hover:show_text:'<gold>Re-queue 1v1!</gold>'>[⚔ Play Again]</hover></click></gold>");
                }
                p.sendMessage(FormatUtil.mm(actionBar.toString()));
            }
        } else if (reasonMini != null) {
            for (UUID id : all) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendMessage(FormatUtil.mm(PREFIX + reasonMini));
            }
        }

        for (UUID id : all) {
            restoreDuelist(duel, Bukkit.getPlayer(id));
        }
        arenas.setArenaBuildAllowed(duel.arenaId, false);

        if (immediateRollback) {
            duel.tracker.rollbackImmediate();
            arenas.clearArenaEntities(duel.arenaId);
        } else {
            int batch = Math.max(100, plugin.getConfig().getInt("arenas.rollback-blocks-per-tick", 300));
            duel.tracker.rollbackBatched(plugin, batch, () -> arenas.clearArenaEntities(duel.arenaId));
        }
    }

    private void restoreDuelist(ActiveDuel duel, Player p) {
        if (p == null) return;
        p.setWorldBorder(null);
        hud.clearForPlayer(p);
        if (p.isOnline() && !p.isDead()) {
            PlayerSnapshot snap = duel.snapshots.remove(p.getUniqueId());
            if (snap != null) {
                Location ret = plugin.getConfig().getBoolean("dedicated-server-mode", true) ? events.lobbyLocation() : null;
                snap.restore(p, ret);
                data.removePersistedSnapshot(p.getUniqueId());
            }
        }
    }

    private boolean isOfficialEventBlocking() {
        return events.isEventOpen() || events.isEventRunning() || events.isCelebrating() || events.isModeVoteOpen();
    }

    private static String resolveKitId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Optional<EventMode> mode = EventMode.fromInput(raw);
        if (mode.isPresent() && mode.get().defaultKitId() != null) {
            return mode.get().defaultKitId();
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String sideLabel(List<Player> players) {
        if (players.size() == 1) return players.get(0).getName();
        return players.get(0).getName() + "'s Team";
    }

    private static String formatUuidNames(Set<UUID> ids) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) names.add(safeName(id));
        return String.join(", ", names);
    }

    private static String safeName(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : "Player";
    }

    // -------------------------------------------------------------------------
    // Combat, Friendly-Fire, Analytics, Anti-Escape, Rollback, Death & Quit
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDuelDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        ActiveDuel duel = activeByPlayer.get(victim.getUniqueId());
        if (duel == null) return;
        if (duel.graceActive || duel.finishing) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDuelFriendlyFire(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        ActiveDuel duel = activeByPlayer.get(victim.getUniqueId());
        if (duel == null) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) attacker = p;
        if (attacker != null && duel.sameSide(attacker.getUniqueId(), victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        ActiveDuel duel = activeByPlayer.get(victim.getUniqueId());
        if (duel == null) return;
        Player attacker = null;
        boolean melee = false;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
            melee = true;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null || !activeByPlayer.containsKey(attacker.getUniqueId())) return;

        UUID aid = attacker.getUniqueId();
        UUID vid = victim.getUniqueId();
        duel.hitsLanded.merge(aid, 1, Integer::sum);
        duel.damageDealt.merge(aid, event.getFinalDamage(), Double::sum);
        if (melee && attacker.getFallDistance() > 0.0f && !attacker.isInsideVehicle() && !attacker.isSprinting()) {
            duel.critHits.merge(aid, 1, Integer::sum);
        }
        int combo = duel.currentCombo.merge(aid, 1, Integer::sum);
        duel.longestCombo.merge(aid, combo, Math::max);
        duel.currentCombo.put(vid, 0);

        AttributeInstance attr = victim.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = attr != null ? attr.getValue() : 20.0;
        hud.sendHitHealth(attacker, victim, Math.max(0.0, victim.getHealth() - event.getFinalDamage()), maxHp);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDuelTeleport(PlayerTeleportEvent event) {
        if (!GUARDED_TP.contains(event.getCause())) return;
        ActiveDuel duel = activeByPlayer.get(event.getPlayer().getUniqueId());
        if (duel == null || event.getTo() == null) return;
        if (duel.graceActive) {
            event.setCancelled(true);
            return;
        }
        Arena arena = arenas.get(duel.arenaId);
        if (arena == null) return;
        boolean sameWorld = event.getTo().getWorld() != null && event.getTo().getWorld().getName().equalsIgnoreCase(arenas.sharedWorldName());
        if (!sameWorld || !arena.isInsideCombatBounds(event.getTo().getX(), event.getTo().getY(), event.getTo().getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDuelMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Location to = event.getTo();
        if (to == null) return;
        Player p = event.getPlayer();
        ActiveDuel duel = activeByPlayer.get(p.getUniqueId());
        if (duel == null || duel.finishing) return;
        if (!duel.aliveA.contains(p.getUniqueId()) && !duel.aliveB.contains(p.getUniqueId())) return;
        Arena arena = arenas.get(duel.arenaId);
        if (arena == null) return;
        if (!arena.isInsideCombatBounds(to.getX(), to.getY(), to.getZ())) {
            if (to.getY() < arena.pasteY() - 1.0) {
                Location safe = arenas.firstSpawn(arena, "final");
                if (safe != null) { p.setFallDistance(0f); p.teleport(safe); }
            } else {
                double dx = to.getX() - arena.centerX();
                double dz = to.getZ() - arena.centerZ();
                double dist = Math.hypot(dx, dz);
                double maxR = Math.max(8.0, arena.playableRadius() - 0.75);
                double cx = dist > maxR && dist > 0.001 ? arena.centerX() + (dx / dist) * maxR : to.getX();
                double cz = dist > maxR && dist > 0.001 ? arena.centerZ() + (dz / dist) * maxR : to.getZ();
                double cy = Math.max(arena.pasteY() + 1.0, Math.min(to.getY(), arena.combatMaxY() - 0.5));
                event.setTo(new Location(to.getWorld(), cx, cy, cz, to.getYaw(), to.getPitch()));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDuelBreak(BlockBreakEvent event) {
        ActiveDuel duel = activeByPlayer.get(event.getPlayer().getUniqueId());
        if (duel == null) return;
        Arena arena = arenas.get(duel.arenaId);
        Block b = event.getBlock();
        if (!duel.isDestructible() || arena == null || !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ())) {
            event.setCancelled(true);
            return;
        }
        if ("crystal".equals(duel.kitId)) {
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDuelPlace(BlockPlaceEvent event) {
        ActiveDuel duel = activeByPlayer.get(event.getPlayer().getUniqueId());
        if (duel == null) return;
        Arena arena = arenas.get(duel.arenaId);
        Block b = event.getBlock();
        if (!duel.isDestructible() || arena == null || !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDuelBucket(PlayerBucketEmptyEvent event) {
        ActiveDuel duel = activeByPlayer.get(event.getPlayer().getUniqueId());
        if (duel == null) return;
        Arena arena = arenas.get(duel.arenaId);
        Block b = event.getBlock();
        if (!duel.isDestructible() || arena == null || !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackPlace(BlockPlaceEvent e) {
        ActiveDuel duel = activeByPlayer.get(e.getPlayer().getUniqueId());
        if (duel != null && duel.tracker.isTracking()) {
            Block b = e.getBlock();
            duel.tracker.recordBeforeChange(new ArenaBlockTracker.BlockPos(b.getX(), b.getY(), b.getZ()), e.getBlockReplacedState().getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBreak(BlockBreakEvent e) {
        ActiveDuel duel = activeByPlayer.get(e.getPlayer().getUniqueId());
        if (duel != null && duel.tracker.isTracking()) duel.tracker.recordBeforeChange(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackLiquid(BlockFromToEvent e) {
        for (ActiveDuel duel : activeByArena.values()) {
            if (duel.tracker.isTracking()) duel.tracker.recordBeforeChange(e.getToBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackIgnite(BlockIgniteEvent e) {
        for (ActiveDuel duel : activeByArena.values()) {
            if (duel.tracker.isTracking()) duel.tracker.recordBeforeChange(e.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDuelEntityExplodeGuard(EntityExplodeEvent e) {
        if (activeByArena.isEmpty() || e.getLocation().getWorld() == null) return;
        if (!e.getLocation().getWorld().getName().equalsIgnoreCase(arenas.sharedWorldName())) return;
        int bx = e.getLocation().getBlockX(), by = e.getLocation().getBlockY(), bz = e.getLocation().getBlockZ();
        for (ActiveDuel duel : activeByArena.values()) {
            Arena arena = arenas.get(duel.arenaId);
            if (arena != null && arena.contains(bx, by, bz)) {
                if (!duel.isDestructible()) {
                    e.blockList().clear();
                } else {
                    e.blockList().removeIf(b -> !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ()));
                    e.setYield(0.0f);
                }
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDuelBlockExplodeGuard(BlockExplodeEvent e) {
        if (activeByArena.isEmpty()) return;
        Block src = e.getBlock();
        if (!src.getWorld().getName().equalsIgnoreCase(arenas.sharedWorldName())) return;
        for (ActiveDuel duel : activeByArena.values()) {
            Arena arena = arenas.get(duel.arenaId);
            if (arena != null && arena.contains(src.getX(), src.getY(), src.getZ())) {
                if (!duel.isDestructible()) {
                    e.blockList().clear();
                } else {
                    e.blockList().removeIf(b -> !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ()));
                    e.setYield(0.0f);
                }
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackEntityExplode(EntityExplodeEvent e) {
        for (ActiveDuel duel : activeByArena.values()) {
            if (duel.tracker.isTracking()) {
                for (Block b : e.blockList()) duel.tracker.recordBeforeChange(b);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBlockExplode(BlockExplodeEvent e) {
        for (ActiveDuel duel : activeByArena.values()) {
            if (duel.tracker.isTracking()) {
                for (Block b : e.blockList()) duel.tracker.recordBeforeChange(b);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBucketEmpty(PlayerBucketEmptyEvent e) {
        ActiveDuel duel = activeByPlayer.get(e.getPlayer().getUniqueId());
        if (duel != null && duel.tracker.isTracking()) duel.tracker.recordBeforeChange(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBucketFill(PlayerBucketFillEvent e) {
        ActiveDuel duel = activeByPlayer.get(e.getPlayer().getUniqueId());
        if (duel != null && duel.tracker.isTracking()) duel.tracker.recordBeforeChange(e.getBlock());
    }

    @EventHandler
    public void onDuelUhcRegen(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        ActiveDuel duel = activeByPlayer.get(p.getUniqueId());
        if (duel != null && "uhc".equals(duel.kitId) && event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDuelDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID id = victim.getUniqueId();
        ActiveDuel duel = activeByPlayer.get(id);
        if (duel == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        event.setKeepLevel(true);

        duel.aliveA.remove(id);
        duel.aliveB.remove(id);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (victim.isOnline() && victim.isDead()) victim.spigot().respawn();
        }, 2L);
        checkSideWin(duel);
    }

    @EventHandler
    public void onDuelRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        ActiveDuel active = activeByPlayer.get(id);
        if (active != null && !active.finishing) {
            // In a multi-player Party Duel, dead teammates spectate until their team wins/loses!
            Arena arena = arenas.get(active.arenaId);
            Location aud = arena != null ? arenas.firstSpawn(arena, "audience") : null;
            if (aud != null) event.setRespawnLocation(aud);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && activeByPlayer.containsKey(id)) {
                    p.getInventory().clear();
                    p.setGameMode(GameMode.SPECTATOR);
                }
            });
            return;
        }
        PlayerSnapshot pending = data.loadPersistedSnapshot(id);
        if (pending == null || events.participants().contains(id)) return;
        if (warmupFfa != null && warmupFfa.isInFfa(id)) return;
        Location lobby = events.lobbyLocation();
        if (lobby != null) event.setRespawnLocation(lobby);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && !events.participants().contains(id) && (warmupFfa == null || !warmupFfa.isInFfa(id))) {
                PlayerSnapshot fresh = data.loadPersistedSnapshot(id);
                if (fresh != null) {
                    Location ret = plugin.getConfig().getBoolean("dedicated-server-mode", true) ? events.lobbyLocation() : null;
                    fresh.restore(p, ret);
                    data.removePersistedSnapshot(id);
                }
            }
        });
    }

    @EventHandler
    public void onDuelQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingByTarget.remove(id);
        leaveQueueSilent(id);
        parties.handleDisconnect(id);
        ActiveDuel duel = activeByPlayer.get(id);
        if (duel != null) {
            duel.aliveA.remove(id);
            duel.aliveB.remove(id);
            checkSideWin(duel);
        }
    }
}
