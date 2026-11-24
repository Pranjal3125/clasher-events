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
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Player-initiated casual 1v1 duels (/event duel <player> [kit]) with automatic
 * Admin Event Priority Override: the instant an official Server Event opens, all active
 * casual duels terminate cleanly, restore players, and free every arena immediately!
 */
public final class CasualDuelManager implements Listener {
    private static final String PREFIX = "<dark_gray>[<aqua><bold>DUEL</bold></aqua>]</dark_gray> ";
    private static final EnumSet<PlayerTeleportEvent.TeleportCause> GUARDED_TP = EnumSet.of(
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
            PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT,
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_GATEWAY
    );

    private record DuelRequest(UUID challenger, UUID target, String kitId, long expiresAtMs) {}

    private static final class ActiveDuel {
        final String arenaId;
        final String kitId;
        final UUID playerA;
        final UUID playerB;
        final ArenaBlockTracker tracker = new ArenaBlockTracker();
        final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
        boolean graceActive;
        boolean finishing;
        BukkitTask graceTask;

        ActiveDuel(String arenaId, String kitId, UUID playerA, UUID playerB) {
            this.arenaId = arenaId;
            this.kitId = kitId;
            this.playerA = playerA;
            this.playerB = playerB;
        }

        UUID opponent(UUID id) {
            return playerA.equals(id) ? playerB : playerA;
        }

        boolean isDestructible() {
            return kitId.equals("build-uhc") || kitId.equals("crystal") || kitId.equals("uhc") || kitId.equals("smp");
        }
    }

    private final JavaPlugin plugin;
    private final ArenaManager arenas;
    private final KitManager kits;
    private final DataStore data;
    private final EventManager events;
    private final EventHudManager hud;

    private final Map<UUID, DuelRequest> pendingByTarget = new HashMap<>();
    private final Map<String, ActiveDuel> activeByArena = new HashMap<>();
    private final Map<UUID, ActiveDuel> activeByPlayer = new HashMap<>();

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
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("casual-duels.enabled", true);
    }

    public boolean isInCasualDuel(UUID playerId) {
        return activeByPlayer.containsKey(playerId);
    }

    public boolean isArenaBusy(String arenaOrKitId) {
        if (arenaOrKitId == null) return false;
        String lower = arenaOrKitId.toLowerCase(Locale.ROOT);
        if (activeByArena.containsKey(lower)) return true;
        String dedicated = arenas.duelArenaId(lower);
        return activeByArena.containsKey(dedicated);
    }

    /**
     * ADMIN PRIORITY OVERRIDE: Instantly & safely ends all active casual duels and pending invites
     * whenever an admin launches an official event or mode vote, freeing every arena immediately!
     */
    public void forceEndAllForOfficialEvent() {
        pendingByTarget.clear();
        if (activeByArena.isEmpty()) return;
        for (ActiveDuel duel : new ArrayList<>(activeByArena.values())) {
            endDuelInternal(duel, null, "<gold><bold>★ OFFICIAL EVENT STARTING!</bold></gold> <yellow>Your casual duel ended cleanly to free the arena — run <green>/event join</green>!</yellow>", true);
        }
    }

    public void shutdown() {
        pendingByTarget.clear();
        for (ActiveDuel duel : new ArrayList<>(activeByArena.values())) {
            endDuelInternal(duel, null, "<gray>Casual duel ended due to server shutdown.</gray>", true);
        }
    }

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
        if (kitId == null || !arenas.exists(kitId) || !kits.exists(kitId)) {
            challenger.sendMessage(FormatUtil.mm(PREFIX + "<red>Unknown kit: " + FormatUtil.escape(rawKit) + "</red>"));
            return;
        }
        if (isArenaBusy(kitId)) {
            challenger.sendMessage(FormatUtil.mm(PREFIX + "<red>The <gold>" + FormatUtil.escape(kitId) + "</gold> arena is currently busy with another 1v1 — try a different kit!</red>"));
            return;
        }

        long expireSec = Math.max(15L, plugin.getConfig().getLong("casual-duels.request-expire-seconds", 60L));
        pendingByTarget.put(target.getUniqueId(), new DuelRequest(challenger.getUniqueId(), target.getUniqueId(), kitId, System.currentTimeMillis() + expireSec * 1000L));

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
        if (isInCasualDuel(challenger.getUniqueId()) || isInCasualDuel(target.getUniqueId())
                || events.participants().contains(challenger.getUniqueId()) || events.participants().contains(target.getUniqueId())) {
            target.sendMessage(FormatUtil.mm(PREFIX + "<red>One of you is already in a match or event.</red>"));
            return;
        }
        if (isArenaBusy(req.kitId())) {
            target.sendMessage(FormatUtil.mm(PREFIX + "<red>The <gold>" + FormatUtil.escape(req.kitId()) + "</gold> arena is currently busy!</red>"));
            return;
        }
        pendingByTarget.remove(target.getUniqueId());
        startCasualDuel(challenger, target, req.kitId());
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

    private void startCasualDuel(Player a, Player b, String kitId) {
        String duelArenaId = arenas.duelArenaId(kitId);
        Arena arena = arenas.get(duelArenaId);
        if (arena == null) return;
        List<Location> spawns = arenas.spawns(arena, "final");
        if (spawns.size() < 2) spawns = arenas.spawns(arena, "wave1");
        if (spawns.size() < 2) {
            a.sendMessage(FormatUtil.mm(PREFIX + "<red>Arena " + duelArenaId + " is missing duel spawns.</red>"));
            return;
        }
        final List<Location> finalSpawns = spawns;
        ActiveDuel duel = new ActiveDuel(duelArenaId, kitId, a.getUniqueId(), b.getUniqueId());
        activeByArena.put(duelArenaId, duel);
        activeByPlayer.put(a.getUniqueId(), duel);
        activeByPlayer.put(b.getUniqueId(), duel);

        arenas.ensureArenaReady(duelArenaId, null, () -> {
            if (duel.finishing || !a.isOnline() || !b.isOnline() || isOfficialEventBlocking()) {
                endDuelInternal(duel, null, "<yellow>Casual duel cancelled.</yellow>", true);
                return;
            }
            duel.tracker.startTracking(arenas.sharedWorldName(), arena);
            arenas.setArenaBuildAllowed(duelArenaId, duel.isDestructible());

            prepareDuelist(duel, a, finalSpawns.get(0), arena);
            prepareDuelist(duel, b, finalSpawns.get(1), arena);

            // 3-second Grace Freeze
            duel.graceActive = true;
            int[] remain = {3};
            Set<UUID> pair = Set.of(a.getUniqueId(), b.getUniqueId());
            duel.graceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (duel.finishing) {
                    if (duel.graceTask != null) duel.graceTask.cancel();
                    return;
                }
                if (remain[0] <= 0) {
                    duel.graceActive = false;
                    if (duel.graceTask != null) duel.graceTask.cancel();
                    hud.broadcastTitle(pair, "<green><bold>FIGHT!</bold></green>",
                            "<yellow>" + FormatUtil.escape(a.getName()) + "</yellow> <gray>vs</gray> <yellow>" + FormatUtil.escape(b.getName()) + "</yellow>", 50, 900, 250);
                    a.playSound(a.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.5f);
                    b.playSound(b.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.5f);
                    return;
                }
                hud.broadcastTitle(pair, "<gold><bold>" + remain[0] + "</bold></gold>", "<gray>Casual 1v1 Duel starting...</gray>", 50, 750, 150);
                a.playSound(a.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.1f);
                b.playSound(b.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.1f);
                remain[0]--;
            }, 0L, 20L);
        });
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
        wb.setSize(Math.min(60.0, arena.playableRadius() * 2.0));
        wb.setWarningDistance(6);
        p.setWorldBorder(wb);
    }

    private void endDuelInternal(ActiveDuel duel, UUID winnerId, String reasonMini, boolean immediateRollback) {
        if (duel.finishing) return;
        duel.finishing = true;
        duel.graceActive = false;
        if (duel.graceTask != null) {
            duel.graceTask.cancel();
            duel.graceTask = null;
        }
        activeByArena.remove(duel.arenaId);
        activeByPlayer.remove(duel.playerA);
        activeByPlayer.remove(duel.playerB);

        Player pa = Bukkit.getPlayer(duel.playerA);
        Player pb = Bukkit.getPlayer(duel.playerB);
        if (winnerId != null) {
            Player w = Bukkit.getPlayer(winnerId);
            Player l = Bukkit.getPlayer(duel.opponent(winnerId));
            String wName = w != null ? w.getName() : "Winner";
            String lName = l != null ? l.getName() : "Opponent";
            String hp = w != null ? String.format(Locale.ROOT, "%.1f", w.getHealth()) : "0";
            String msg = PREFIX + "<gold><bold>" + FormatUtil.escape(wName) + "</bold></gold> <green>defeated</green> <yellow>"
                    + FormatUtil.escape(lName) + "</yellow> <gray>in a</gray> <aqua>" + FormatUtil.escape(duel.kitId)
                    + "</aqua> <gray>1v1 (<red>" + hp + " ❤</red>)!</gray>";
            if (pa != null) pa.sendMessage(FormatUtil.mm(msg));
            if (pb != null) pb.sendMessage(FormatUtil.mm(msg));
        } else if (reasonMini != null) {
            if (pa != null) pa.sendMessage(FormatUtil.mm(PREFIX + reasonMini));
            if (pb != null) pb.sendMessage(FormatUtil.mm(PREFIX + reasonMini));
        }

        restoreDuelist(duel, pa);
        restoreDuelist(duel, pb);
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
        if (mode.isPresent() && mode.get().defaultArenaId() != null) {
            return mode.get().defaultArenaId();
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    // -------------------------------------------------------------------------
    // Casual Duel Combat, Anti-Escape, Rollback, Death & Quit Listeners
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDuelHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        ActiveDuel duel = activeByPlayer.get(victim.getUniqueId());
        if (duel == null) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || !activeByPlayer.containsKey(attacker.getUniqueId())) return;
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
        Location to = event.getTo();
        if (to == null) return;
        Player p = event.getPlayer();
        ActiveDuel duel = activeByPlayer.get(p.getUniqueId());
        if (duel == null || duel.finishing) return;
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
        ActiveDuel duel = activeByPlayer.get(victim.getUniqueId());
        if (duel == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        UUID winnerId = duel.opponent(victim.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (victim.isOnline() && victim.isDead()) victim.spigot().respawn();
        }, 2L);
        Bukkit.getScheduler().runTask(plugin, () -> endDuelInternal(duel, winnerId, null, false));
    }

    @EventHandler
    public void onDuelRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        PlayerSnapshot pending = data.loadPersistedSnapshot(id);
        if (pending == null || events.participants().contains(id)) return;
        Location lobby = events.lobbyLocation();
        if (lobby != null) event.setRespawnLocation(lobby);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline() && !events.participants().contains(id)) {
                PlayerSnapshot fresh = data.loadPersistedSnapshot(id);
                if (fresh != null) {
                    Location ret = plugin.getConfig().getBoolean("dedicated-server-mode", true) ? events.lobbyLocation() : null;
                    fresh.restore(event.getPlayer(), ret);
                    data.removePersistedSnapshot(id);
                }
            }
        });
    }

    @EventHandler
    public void onDuelQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingByTarget.remove(id);
        ActiveDuel duel = activeByPlayer.get(id);
        if (duel != null) {
            endDuelInternal(duel, duel.opponent(id), null, false);
        }
    }
}
