package network.clasher.events.match;

import network.clasher.events.arena.ArenaManager;
import network.clasher.events.hud.EventHudManager;
import network.clasher.events.kit.KitManager;
import network.clasher.events.model.Arena;
import network.clasher.events.model.PlayerSnapshot;
import network.clasher.events.storage.DataStore;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Always-Open Warmup FFA Pit (`/event ffa` & Lobby NPC):
 * - Instant drop-in non-stop PvP practice while waiting between official Server Events
 * - Full heal + fresh kit refill + killstreak hype on every kill, instant respawn back in the pit
 * - Automatic Admin Event Priority Override: cleanly restores every player to Lobby when an event opens!
 */
public final class WarmupFfaManager implements Listener {
    private static final String PREFIX = "<dark_gray>[<red><bold>FFA PIT</bold></red>]</dark_gray> ";
    private static final EnumSet<PlayerTeleportEvent.TeleportCause> GUARDED_TP = EnumSet.of(
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
            PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT,
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_GATEWAY
    );

    private final JavaPlugin plugin;
    private final ArenaManager arenas;
    private final KitManager kits;
    private final DataStore data;
    private final EventManager events;
    private final EventHudManager hud;
    private CasualDuelManager casualDuels;
    private final Random random = new Random();

    private final Set<UUID> fighters = new LinkedHashSet<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final Map<UUID, UUID> lastDamager = new HashMap<>();

    public WarmupFfaManager(
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

    public void setCasualDuels(CasualDuelManager casualDuels) {
        this.casualDuels = casualDuels;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("warmup-ffa.enabled", true);
    }

    public String arenaId() {
        String id = plugin.getConfig().getString("warmup-ffa.arena", "sword");
        return arenas.exists(id) ? id : "sword";
    }

    public String kitId() {
        String id = plugin.getConfig().getString("warmup-ffa.kit", "sword");
        return kits.exists(id) ? id : "sword";
    }

    public boolean isInFfa(UUID playerId) {
        return fighters.contains(playerId);
    }

    public int playerCount() {
        return fighters.size();
    }

    private boolean isOfficialEventBlocking() {
        return events.isEventOpen() || events.isEventRunning() || events.isCelebrating() || events.isModeVoteOpen();
    }

    public void joinFfa(Player player) {
        if (!isEnabled()) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Warmup FFA Pit is currently disabled.</red>"));
            return;
        }
        if (isOfficialEventBlocking()) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Warmup FFA is paused during official Server Events! Run <yellow>/event join</yellow>.</red>"));
            return;
        }
        UUID id = player.getUniqueId();
        if (fighters.contains(id)) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<yellow>You are already in the Warmup FFA Pit! Use <red>/event ffa leave</red> to exit.</yellow>"));
            return;
        }
        if (events.participants().contains(id) || (casualDuels != null && casualDuels.isInCasualDuel(id))) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Leave your current duel or event first.</red>"));
            return;
        }
        String targetArena = arenaId();
        Arena arena = arenas.get(targetArena);
        if (arena == null) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Warmup FFA arena is not configured.</red>"));
            return;
        }
        if (casualDuels != null) casualDuels.leaveQueue(player);

        PlayerSnapshot snap = new PlayerSnapshot(player);
        snapshots.put(id, snap);
        data.persistSnapshot(id, snap);
        fighters.add(id);
        streaks.put(id, 0);

        arenas.ensureArenaReady(targetArena, null, () -> {
            if (!player.isOnline() || !fighters.contains(id) || isOfficialEventBlocking()) {
                leaveFfa(player, false);
                return;
            }
            spawnIntoPit(player, arena);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
            player.sendMessage(FormatUtil.mm(PREFIX + "<green><bold>DROPPED INTO WARMUP FFA!</bold></green> <gray>Kit:</gray> <gold>"
                    + FormatUtil.escape(kitId()) + "</gold> <dark_gray>|</dark_gray> <gray>Kill = Full Heal + Kit Refill!</gray> "
                    + "<red><click:run_command:'/event ffa leave'><hover:show_text:'<red>Return to Lobby</red>'>[✖ Leave FFA]</hover></click></red>"));
        });
    }

    public void leaveFfa(Player player, boolean notify) {
        UUID id = player.getUniqueId();
        if (!fighters.remove(id)) {
            if (notify) player.sendMessage(FormatUtil.mm(PREFIX + "<gray>You are not in the Warmup FFA Pit.</gray>"));
            return;
        }
        streaks.remove(id);
        lastDamager.remove(id);
        PlayerSnapshot snap = snapshots.remove(id);
        if (snap != null && player.isOnline() && !player.isDead()) {
            Location ret = plugin.getConfig().getBoolean("dedicated-server-mode", true) ? events.lobbyLocation() : null;
            snap.restore(player, ret);
            data.removePersistedSnapshot(id);
        }
        if (notify) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<green>Returned to Lobby from Warmup FFA.</green>"));
        }
    }

    public void forceEndAllForOfficialEvent() {
        if (fighters.isEmpty()) return;
        for (UUID id : new ArrayList<>(fighters)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                leaveFfa(p, false);
                p.sendMessage(FormatUtil.mm(PREFIX + "<gold><bold>★ OFFICIAL EVENT STARTING!</bold></gold> <yellow>Warmup FFA paused — run <green>/event join</green>!</yellow>"));
            } else {
                fighters.remove(id);
                snapshots.remove(id);
            }
        }
        streaks.clear();
        lastDamager.clear();
        arenas.clearArenaEntities(arenaId());
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(fighters)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) leaveFfa(p, false);
        }
        fighters.clear();
        snapshots.clear();
        streaks.clear();
        lastDamager.clear();
    }

    private void spawnIntoPit(Player p, Arena arena) {
        List<Location> pts = arenas.spawns(arena, "wave1");
        if (pts.isEmpty()) pts = arenas.spawns(arena, "final");
        if (!pts.isEmpty()) {
            p.setFallDistance(0f);
            p.teleport(pts.get(random.nextInt(pts.size())));
        }
        p.setGameMode(GameMode.SURVIVAL);
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(attr != null ? attr.getValue() : 20.0);
        p.setFoodLevel(20);
        p.setSaturation(5.0f);
        p.setFireTicks(0);
        kits.giveKit(p, kitId(), data.kitLayout(p.getUniqueId(), kitId()));
    }

    // -------------------------------------------------------------------------
    // Listeners: Combat, Kill Refill, Instant Respawn, Containment & Cleanup
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFfaHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !fighters.contains(victim.getUniqueId())) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || !fighters.contains(attacker.getUniqueId())) return;
        lastDamager.put(victim.getUniqueId(), attacker.getUniqueId());
        AttributeInstance attr = victim.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = attr != null ? attr.getValue() : 20.0;
        hud.sendHitHealth(attacker, victim, Math.max(0.0, victim.getHealth() - event.getFinalDamage()), maxHp);
    }

    @EventHandler
    public void onFfaDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID vid = victim.getUniqueId();
        if (!fighters.contains(vid)) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        streaks.put(vid, 0);

        UUID killerId = lastDamager.remove(vid);
        if (killerId != null && !killerId.equals(vid) && fighters.contains(killerId)) {
            Player killer = Bukkit.getPlayer(killerId);
            if (killer != null && killer.isOnline() && !killer.isDead()) {
                int streak = streaks.merge(killerId, 1, Integer::sum);
                AttributeInstance attr = killer.getAttribute(Attribute.MAX_HEALTH);
                killer.setHealth(attr != null ? attr.getValue() : 20.0);
                killer.setFoodLevel(20);
                killer.setFireTicks(0);
                kits.giveKit(killer, kitId(), data.kitLayout(killerId, kitId()));
                killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
                killer.sendActionBar(FormatUtil.mm("<green><bold>⚔ KILL!</bold></green> <yellow>Full Heal & Kit Refilled</yellow> <dark_gray>|</dark_gray> <gold>Streak: " + streak + "</gold>"));
                if (streak == 3 || streak == 5 || (streak >= 10 && streak % 5 == 0)) {
                    for (UUID fid : fighters) {
                        Player fp = Bukkit.getPlayer(fid);
                        if (fp != null) {
                            fp.sendMessage(FormatUtil.mm(PREFIX + "<gold><bold>🔥 " + FormatUtil.escape(killer.getName())
                                    + "</bold></gold> <yellow>is on a <red><bold>" + streak + "-kill Warmup Streak</bold></red>!</yellow>"));
                        }
                    }
                }
            }
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (victim.isOnline() && victim.isDead()) victim.spigot().respawn();
        }, 2L);
    }

    @EventHandler
    public void onFfaRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        if (!fighters.contains(id)) return;
        Arena arena = arenas.get(arenaId());
        if (arena == null) return;
        List<Location> pts = arenas.spawns(arena, "wave1");
        if (!pts.isEmpty()) event.setRespawnLocation(pts.get(random.nextInt(pts.size())));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && fighters.contains(id)) {
                spawnIntoPit(p, arena);
            }
        });
    }

    @EventHandler
    public void onFfaMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        Player p = event.getPlayer();
        if (!fighters.contains(p.getUniqueId())) return;
        Arena arena = arenas.get(arenaId());
        if (arena == null) return;
        if (!arena.isInsideCombatBounds(to.getX(), to.getY(), to.getZ())) {
            if (to.getY() < arena.pasteY() - 1.0) {
                spawnIntoPit(p, arena);
            } else {
                double dx = to.getX() - arena.centerX();
                double dz = to.getZ() - arena.centerZ();
                double dist = Math.hypot(dx, dz);
                double maxR = Math.max(10.0, arena.playableRadius() - 0.75);
                double cx = dist > maxR && dist > 0.001 ? arena.centerX() + (dx / dist) * maxR : to.getX();
                double cz = dist > maxR && dist > 0.001 ? arena.centerZ() + (dz / dist) * maxR : to.getZ();
                double cy = Math.max(arena.pasteY() + 1.0, Math.min(to.getY(), arena.combatMaxY() - 0.5));
                event.setTo(new Location(to.getWorld(), cx, cy, cz, to.getYaw(), to.getPitch()));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFfaTeleport(PlayerTeleportEvent event) {
        if (!GUARDED_TP.contains(event.getCause()) || !fighters.contains(event.getPlayer().getUniqueId())) return;
        Arena arena = arenas.get(arenaId());
        if (arena == null || event.getTo() == null) return;
        boolean sameWorld = event.getTo().getWorld() != null && event.getTo().getWorld().getName().equalsIgnoreCase(arenas.sharedWorldName());
        if (!sameWorld || !arena.isInsideCombatBounds(event.getTo().getX(), event.getTo().getY(), event.getTo().getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFfaBreak(BlockBreakEvent event) {
        if (fighters.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFfaPlace(BlockPlaceEvent event) {
        if (fighters.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFfaDrop(PlayerDropItemEvent event) {
        if (fighters.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onFfaQuit(PlayerQuitEvent event) {
        leaveFfa(event.getPlayer(), false);
    }
}
