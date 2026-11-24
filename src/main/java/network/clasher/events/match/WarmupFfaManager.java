package network.clasher.events.match;

import network.clasher.events.arena.ArenaBlockTracker;
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
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
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
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Always-Open Dual Warmup FFA Pits (`/event ffa` & Lobby NPC):
 *  1. Sword FFA Pit            — clean non-destructible melee warmup
 *  2. Deep Crystal Terrain FFA — 15-block deep diggable/blastable earth down to solid Bedrock,
 *                                with automatic periodic terrain resets & zero-suffocation surface lift!
 * Both pits feature instant Full Heal + Fresh Kit Refill on kill and automatic Admin Event Priority Override!
 */
public final class WarmupFfaManager implements Listener {
    public enum PitType {
        SWORD("Sword FFA", false),
        CRYSTAL("Deep Crystal Terrain FFA", true);

        private final String displayName;
        private final boolean destructible;

        PitType(String displayName, boolean destructible) {
            this.displayName = displayName;
            this.destructible = destructible;
        }

        public String displayName() { return displayName; }
        public boolean destructible() { return destructible; }
    }

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

    private final Map<UUID, PitType> fighterPit = new LinkedHashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final Map<UUID, UUID> lastDamager = new HashMap<>();

    // Automatic Periodic Deep-Terrain Reset state for Crystal FFA!
    private final ArenaBlockTracker crystalTracker = new ArenaBlockTracker();
    private int crystalResetRemainingSec;
    private int entitySweepCounter;
    private boolean crystalResetting;
    private BukkitTask autoResetTask;

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
        this.crystalResetRemainingSec = configuredResetSeconds();
        startAutoResetLoop();
    }

    public void setCasualDuels(CasualDuelManager casualDuels) {
        this.casualDuels = casualDuels;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("warmup-ffa.enabled", true);
    }

    private int configuredResetSeconds() {
        return Math.max(60, plugin.getConfig().getInt("warmup-ffa.auto-reset-seconds", 300));
    }

    public int crystalResetRemainingSec() {
        return crystalResetRemainingSec;
    }

    public String arenaId(PitType type) {
        if (type == PitType.CRYSTAL) {
            String id = plugin.getConfig().getString("warmup-ffa.crystal-arena", "crystal-ffa");
            return arenas.exists(id) ? id : "crystal";
        }
        String id = plugin.getConfig().getString("warmup-ffa.arena", "sword");
        return arenas.exists(id) ? id : "sword";
    }

    public String kitId(PitType type) {
        if (type == PitType.CRYSTAL) {
            String id = plugin.getConfig().getString("warmup-ffa.crystal-kit", "crystal");
            return kits.exists(id) ? id : "crystal";
        }
        String id = plugin.getConfig().getString("warmup-ffa.kit", "sword");
        return kits.exists(id) ? id : "sword";
    }

    public boolean isInFfa(UUID playerId) {
        return fighterPit.containsKey(playerId);
    }

    public PitType pitOf(UUID playerId) {
        return fighterPit.get(playerId);
    }

    public int playerCount() {
        return fighterPit.size();
    }

    public int playerCount(PitType type) {
        int c = 0;
        for (PitType t : fighterPit.values()) if (t == type) c++;
        return c;
    }

    private boolean isOfficialEventBlocking() {
        return events.isEventOpen() || events.isEventRunning() || events.isCelebrating() || events.isModeVoteOpen();
    }

    public void joinFfa(Player player) {
        joinFfa(player, PitType.SWORD);
    }

    public void joinFfa(Player player, PitType type) {
        if (!isEnabled()) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Warmup FFA Pits are currently disabled.</red>"));
            return;
        }
        if (isOfficialEventBlocking()) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Warmup FFA is paused during official Server Events! Run <yellow>/event join</yellow>.</red>"));
            return;
        }
        UUID id = player.getUniqueId();
        PitType existing = fighterPit.get(id);
        if (existing == type) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<yellow>You are already in <gold>" + type.displayName() + "</gold>! Use <red>/event leave</red> to exit.</yellow>"));
            return;
        }
        if (existing != null) {
            // Seamlessly switch from Sword FFA <-> Crystal FFA without losing original Lobby snapshot!
            leaveFfa(player, false);
        }
        if (events.participants().contains(id) || (casualDuels != null && casualDuels.isInCasualDuel(id))) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Leave your current duel or event first.</red>"));
            return;
        }
        String targetArenaId = arenaId(type);
        Arena arena = arenas.get(targetArenaId);
        if (arena == null) {
            player.sendMessage(FormatUtil.mm(PREFIX + "<red>Warmup FFA arena '" + FormatUtil.escape(targetArenaId) + "' is not configured.</red>"));
            return;
        }
        if (casualDuels != null) casualDuels.leaveQueueSilent(id);

        PlayerSnapshot snap = new PlayerSnapshot(player);
        snapshots.put(id, snap);
        data.persistSnapshot(id, snap);
        fighterPit.put(id, type);
        streaks.put(id, 0);

        arenas.ensureArenaReady(targetArenaId, null, () -> {
            if (!player.isOnline() || fighterPit.get(id) != type || isOfficialEventBlocking()) {
                leaveFfa(player, false);
                return;
            }
            if (type == PitType.CRYSTAL) {
                if (!crystalTracker.isTracking()) {
                    crystalTracker.startTracking(arenas.sharedWorldName(), arena);
                    crystalResetRemainingSec = configuredResetSeconds();
                }
                arenas.setArenaBuildAllowed(targetArenaId, true);
                arenas.addArenaBuilder(targetArenaId, id);
            }
            spawnIntoPit(player, arena, type);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
            String resetNote = type == PitType.CRYSTAL
                    ? " <dark_gray>|</dark_gray> <aqua>Auto-Reset: " + FormatUtil.formatDuration(crystalResetRemainingSec) + "</aqua>" : "";
            player.sendMessage(FormatUtil.mm(PREFIX + "<green><bold>DROPPED INTO " + type.displayName().toUpperCase() + "!</bold></green> "
                    + "<gray>Kill = Full Heal + Kit Refill!</gray>" + resetNote + " "
                    + "<red><click:run_command:'/event leave'><hover:show_text:'<red>Return to Lobby</red>'>[✖ Leave FFA]</hover></click></red>"));
        });
    }

    public void leaveFfa(Player player, boolean notify) {
        UUID id = player.getUniqueId();
        PitType prev = fighterPit.remove(id);
        if (prev == null) {
            if (notify) player.sendMessage(FormatUtil.mm(PREFIX + "<gray>You are not in any Warmup FFA Pit.</gray>"));
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
            player.sendMessage(FormatUtil.mm(PREFIX + "<green>Returned to Lobby from " + prev.displayName() + ".</green>"));
        }
    }

    /**
     * Periodic zero-suffocation, 20-TPS tick-batched Deep Crystal Terrain Auto-Reset:
     * restores all craters/obsidian/holes smoothly across ticks (never freezes the main server thread!)
     * and safely lifts active fighters to fresh surface spawns!
     */
    public void resetCrystalPitNow(CommandSender initiator) {
        if (crystalResetting) {
            if (initiator != null) initiator.sendMessage(FormatUtil.mm(PREFIX + "<yellow>Crystal terrain reset is already in progress!</yellow>"));
            return;
        }
        String cArenaId = arenaId(PitType.CRYSTAL);
        Arena cArena = arenas.get(cArenaId);
        if (cArena == null) return;

        crystalResetting = true;
        int restored = crystalTracker.trackedCount();
        int blocksPerTick = Math.max(500, plugin.getConfig().getInt("rollback-blocks-per-tick", 2500));

        // Lift fighters safely onto surface wave1 spawns BEFORE blocks restore so nobody suffocates!
        liftCrystalFightersToSurface(cArena);
        arenas.clearArenaEntities(cArenaId);

        crystalTracker.rollbackBatched(plugin, blocksPerTick, () -> {
            crystalResetting = false;
            arenas.clearArenaEntities(cArenaId);
            if (!isOfficialEventBlocking() && playerCount(PitType.CRYSTAL) > 0) {
                crystalTracker.startTracking(arenas.sharedWorldName(), cArena);
            }
            crystalResetRemainingSec = configuredResetSeconds();

            for (Map.Entry<UUID, PitType> e : new ArrayList<>(fighterPit.entrySet())) {
                if (e.getValue() != PitType.CRYSTAL) continue;
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && p.isOnline() && !p.isDead()) {
                    // Safety check: if a fighter dropped into a crater mid-batch, re-lift to clean surface
                    if (p.getLocation().getBlock().getType().isSolid()
                            || p.getEyeLocation().getBlock().getType().isSolid()) {
                        List<Location> surfacePts = arenas.spawns(cArena, "wave1");
                        if (!surfacePts.isEmpty()) {
                            p.setFallDistance(0f);
                            p.teleport(surfacePts.get(random.nextInt(surfacePts.size())));
                        }
                    }
                    p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.3f);
                    p.sendMessage(FormatUtil.mm(PREFIX + "<green><bold>♻ CRYSTAL TERRAIN RESET!</bold></green> <gray>Fresh 15-block deep ground restored ("
                            + restored + " blocks) — next reset in <aqua>" + FormatUtil.formatDuration(crystalResetRemainingSec) + "</aqua>!</gray>"));
                }
            }
            if (initiator != null) {
                initiator.sendMessage(FormatUtil.mm(PREFIX + "<green>Reset Deep Crystal FFA terrain (" + restored + " modified blocks restored smoothly at 20 TPS)!</green>"));
            }
        });
    }

    private void liftCrystalFightersToSurface(Arena cArena) {
        List<Location> surfacePts = arenas.spawns(cArena, "wave1");
        if (surfacePts.isEmpty()) return;
        for (Map.Entry<UUID, PitType> e : new ArrayList<>(fighterPit.entrySet())) {
            if (e.getValue() != PitType.CRYSTAL) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline() && !p.isDead()) {
                p.setFallDistance(0f);
                p.teleport(surfacePts.get(random.nextInt(surfacePts.size())));
            }
        }
    }

    private void sweepJunkEntitiesInActivePits() {
        if (fighterPit.isEmpty()) return;
        World world = Bukkit.getWorld(arenas.sharedWorldName());
        if (world == null) return;
        for (PitType type : PitType.values()) {
            if (playerCount(type) == 0) continue;
            Arena arena = arenas.get(arenaId(type));
            if (arena == null) continue;
            Location center = new Location(world, arena.centerX(), (arena.minY() + arena.maxY()) / 2.0, arena.centerZ());
            double rx = Math.max(1.0, (arena.maxX() - arena.minX()) / 2.0);
            double ry = Math.max(1.0, (arena.maxY() - arena.minY()) / 2.0);
            double rz = Math.max(1.0, (arena.maxZ() - arena.minZ()) / 2.0);
            for (Entity entity : world.getNearbyEntities(center, rx, ry, rz)) {
                if (entity instanceof Item || entity instanceof ExperienceOrb) {
                    entity.remove();
                } else if (entity instanceof AbstractArrow arrow && arrow.isInBlock()) {
                    arrow.remove();
                }
            }
        }
    }

    private void startAutoResetLoop() {
        autoResetTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (isOfficialEventBlocking()) return;
            // Lightweight 20-second junk entity sweeper (dropped items, XP orbs, stuck arrows) for 20 TPS FFA pits!
            if (++entitySweepCounter >= 20) {
                entitySweepCounter = 0;
                sweepJunkEntitiesInActivePits();
            }
            if (crystalResetting) return;
            boolean hasCrystalActivity = playerCount(PitType.CRYSTAL) > 0 || crystalTracker.trackedCount() > 0;
            if (!hasCrystalActivity) {
                crystalResetRemainingSec = configuredResetSeconds();
                return;
            }
            crystalResetRemainingSec--;
            if (crystalResetRemainingSec == 30 || crystalResetRemainingSec == 10 || (crystalResetRemainingSec <= 5 && crystalResetRemainingSec > 0)) {
                for (Map.Entry<UUID, PitType> e : fighterPit.entrySet()) {
                    if (e.getValue() != PitType.CRYSTAL) continue;
                    Player p = Bukkit.getPlayer(e.getKey());
                    if (p != null) {
                        p.sendActionBar(FormatUtil.mm("<aqua><bold>♻ Terrain Resetting in " + crystalResetRemainingSec + "s!</bold></aqua>"));
                        if (crystalResetRemainingSec == 30 || crystalResetRemainingSec == 10) {
                            p.sendMessage(FormatUtil.mm(PREFIX + "<aqua>Deep Crystal Terrain resets in <yellow><bold>" + crystalResetRemainingSec + "s</bold></yellow>!</aqua>"));
                        }
                    }
                }
            } else if (crystalResetRemainingSec <= 0) {
                resetCrystalPitNow(null);
            }
        }, 20L, 20L);
    }

    public void forceEndAllForOfficialEvent() {
        if (!fighterPit.isEmpty()) {
            for (UUID id : new ArrayList<>(fighterPit.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    leaveFfa(p, false);
                    p.sendMessage(FormatUtil.mm(PREFIX + "<gold><bold>★ OFFICIAL EVENT STARTING!</bold></gold> <yellow>Warmup FFA paused — run <green>/event join</green>!</yellow>"));
                } else {
                    fighterPit.remove(id);
                    snapshots.remove(id);
                }
            }
        }
        streaks.clear();
        lastDamager.clear();
        String cArenaId = arenaId(PitType.CRYSTAL);
        arenas.setArenaBuildAllowed(cArenaId, false);
        if (crystalTracker.isTracking() || crystalTracker.trackedCount() > 0) {
            int blocksPerTick = Math.max(500, plugin.getConfig().getInt("rollback-blocks-per-tick", 2500));
            crystalTracker.rollbackBatched(plugin, blocksPerTick, () -> arenas.clearArenaEntities(cArenaId));
        }
        arenas.clearArenaEntities(arenaId(PitType.SWORD));
        arenas.clearArenaEntities(cArenaId);
    }

    public void shutdown() {
        if (autoResetTask != null) {
            autoResetTask.cancel();
            autoResetTask = null;
        }
        for (UUID id : new ArrayList<>(fighterPit.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) leaveFfa(p, false);
        }
        fighterPit.clear();
        snapshots.clear();
        streaks.clear();
        lastDamager.clear();
        arenas.setArenaBuildAllowed(arenaId(PitType.CRYSTAL), false);
        crystalTracker.rollbackImmediate();
    }

    private void spawnIntoPit(Player p, Arena arena, PitType type) {
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
        String kId = kitId(type);
        kits.giveKit(p, kId, data.kitLayout(p.getUniqueId(), kId));
    }

    // -------------------------------------------------------------------------
    // Combat, Kill Refill, Instant Respawn, Deep-Terrain Destructibility & Rollback
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFfaHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        PitType vPit = fighterPit.get(victim.getUniqueId());
        if (vPit == null) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player p) attacker = p;
        else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || fighterPit.get(attacker.getUniqueId()) != vPit) return;
        lastDamager.put(victim.getUniqueId(), attacker.getUniqueId());
        AttributeInstance attr = victim.getAttribute(Attribute.MAX_HEALTH);
        double maxHp = attr != null ? attr.getValue() : 20.0;
        hud.sendHitHealth(attacker, victim, Math.max(0.0, victim.getHealth() - event.getFinalDamage()), maxHp);
    }

    @EventHandler
    public void onFfaDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID vid = victim.getUniqueId();
        PitType pit = fighterPit.get(vid);
        if (pit == null) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        streaks.put(vid, 0);

        UUID killerId = lastDamager.remove(vid);
        if (killerId != null && !killerId.equals(vid) && fighterPit.get(killerId) == pit) {
            Player killer = Bukkit.getPlayer(killerId);
            if (killer != null && killer.isOnline() && !killer.isDead()) {
                int streak = streaks.merge(killerId, 1, Integer::sum);
                AttributeInstance attr = killer.getAttribute(Attribute.MAX_HEALTH);
                killer.setHealth(attr != null ? attr.getValue() : 20.0);
                killer.setFoodLevel(20);
                killer.setFireTicks(0);
                String kId = kitId(pit);
                kits.giveKit(killer, kId, data.kitLayout(killerId, kId));
                killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
                killer.sendActionBar(FormatUtil.mm("<green><bold>⚔ KILL!</bold></green> <yellow>Full Heal & Kit Refilled</yellow> <dark_gray>|</dark_gray> <gold>Streak: " + streak + "</gold>"));
                if (streak == 3 || streak == 5 || (streak >= 10 && streak % 5 == 0)) {
                    for (Map.Entry<UUID, PitType> e : fighterPit.entrySet()) {
                        if (e.getValue() != pit) continue;
                        Player fp = Bukkit.getPlayer(e.getKey());
                        if (fp != null) {
                            fp.sendMessage(FormatUtil.mm(PREFIX + "<gold><bold>🔥 " + FormatUtil.escape(killer.getName())
                                    + "</bold></gold> <yellow>is on a <red><bold>" + streak + "-kill " + pit.displayName() + " Streak</bold></red>!</yellow>"));
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
        PitType pit = fighterPit.get(id);
        if (pit == null) return;
        Arena arena = arenas.get(arenaId(pit));
        if (arena == null) return;
        List<Location> pts = arenas.spawns(arena, "wave1");
        if (!pts.isEmpty()) event.setRespawnLocation(pts.get(random.nextInt(pts.size())));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && fighterPit.get(id) == pit) {
                spawnIntoPit(p, arena, pit);
            }
        });
    }

    @EventHandler
    public void onFfaMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Location to = event.getTo();
        if (to == null) return;
        Player p = event.getPlayer();
        PitType pit = fighterPit.get(p.getUniqueId());
        if (pit == null) return;
        Arena arena = arenas.get(arenaId(pit));
        if (arena == null) return;
        if (!arena.isInsideCombatBounds(to.getX(), to.getY(), to.getZ())) {
            if (to.getY() < arena.pasteY() - 1.0) {
                spawnIntoPit(p, arena, pit);
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
        if (!GUARDED_TP.contains(event.getCause())) return;
        PitType pit = fighterPit.get(event.getPlayer().getUniqueId());
        if (pit == null) return;
        Arena arena = arenas.get(arenaId(pit));
        if (arena == null || event.getTo() == null) return;
        boolean sameWorld = event.getTo().getWorld() != null && event.getTo().getWorld().getName().equalsIgnoreCase(arenas.sharedWorldName());
        if (!sameWorld || !arena.isInsideCombatBounds(event.getTo().getX(), event.getTo().getY(), event.getTo().getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFfaBreak(BlockBreakEvent event) {
        PitType pit = fighterPit.get(event.getPlayer().getUniqueId());
        if (pit == null) return;
        if (!pit.destructible() || crystalResetting) {
            event.setCancelled(true);
            return;
        }
        Arena arena = arenas.get(arenaId(pit));
        Block b = event.getBlock();
        if (arena == null || !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ())) {
            event.setCancelled(true);
            return;
        }
        // Prevent dirt/stone/deepslate junk item piles from flooding the Crystal FFA pit!
        event.setDropItems(false);
        event.setExpToDrop(0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFfaPlace(BlockPlaceEvent event) {
        PitType pit = fighterPit.get(event.getPlayer().getUniqueId());
        if (pit == null) return;
        if (!pit.destructible() || crystalResetting) {
            event.setCancelled(true);
            return;
        }
        Arena arena = arenas.get(arenaId(pit));
        Block b = event.getBlock();
        if (arena == null || !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFfaEntityExplodeGuard(EntityExplodeEvent e) {
        if (!crystalTracker.isTracking() && !crystalResetting) return;
        Arena arena = arenas.get(arenaId(PitType.CRYSTAL));
        if (arena == null || e.getLocation().getWorld() == null) return;
        if (!e.getLocation().getWorld().getName().equalsIgnoreCase(arenas.sharedWorldName())) return;
        if (!arena.contains(e.getLocation().getBlockX(), e.getLocation().getBlockY(), e.getLocation().getBlockZ())) return;
        if (crystalResetting) {
            e.blockList().clear();
            return;
        }
        e.blockList().removeIf(b -> !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ()));
        // Zero dropped block items from Crystal/Anchor blasts -> massive TPS & FPS boost!
        e.setYield(0.0f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFfaBlockExplodeGuard(BlockExplodeEvent e) {
        if (!crystalTracker.isTracking() && !crystalResetting) return;
        Arena arena = arenas.get(arenaId(PitType.CRYSTAL));
        if (arena == null) return;
        Block src = e.getBlock();
        if (!src.getWorld().getName().equalsIgnoreCase(arenas.sharedWorldName())) return;
        if (!arena.contains(src.getX(), src.getY(), src.getZ())) return;
        if (crystalResetting) {
            e.blockList().clear();
            return;
        }
        e.blockList().removeIf(b -> !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ()));
        e.setYield(0.0f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFfaBucket(PlayerBucketEmptyEvent event) {
        PitType pit = fighterPit.get(event.getPlayer().getUniqueId());
        if (pit == null) return;
        if (!pit.destructible()) {
            event.setCancelled(true);
            return;
        }
        Arena arena = arenas.get(arenaId(pit));
        Block b = event.getBlock();
        if (arena == null || !arena.isInsideBuildBounds(b.getX(), b.getY(), b.getZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackPlace(BlockPlaceEvent e) {
        if (fighterPit.get(e.getPlayer().getUniqueId()) == PitType.CRYSTAL && crystalTracker.isTracking()) {
            Block b = e.getBlock();
            crystalTracker.recordBeforeChange(new ArenaBlockTracker.BlockPos(b.getX(), b.getY(), b.getZ()), e.getBlockReplacedState().getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBreak(BlockBreakEvent e) {
        if (fighterPit.get(e.getPlayer().getUniqueId()) == PitType.CRYSTAL && crystalTracker.isTracking()) {
            crystalTracker.recordBeforeChange(e.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackLiquid(BlockFromToEvent e) {
        if (crystalTracker.isTracking()) crystalTracker.recordBeforeChange(e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackIgnite(BlockIgniteEvent e) {
        if (crystalTracker.isTracking()) crystalTracker.recordBeforeChange(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackEntityExplode(EntityExplodeEvent e) {
        if (!crystalTracker.isTracking()) return;
        for (Block b : e.blockList()) crystalTracker.recordBeforeChange(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBlockExplode(BlockExplodeEvent e) {
        if (!crystalTracker.isTracking()) return;
        for (Block b : e.blockList()) crystalTracker.recordBeforeChange(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBucketEmpty(PlayerBucketEmptyEvent e) {
        if (fighterPit.get(e.getPlayer().getUniqueId()) == PitType.CRYSTAL && crystalTracker.isTracking()) {
            crystalTracker.recordBeforeChange(e.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackBucketFill(PlayerBucketFillEvent e) {
        if (fighterPit.get(e.getPlayer().getUniqueId()) == PitType.CRYSTAL && crystalTracker.isTracking()) {
            crystalTracker.recordBeforeChange(e.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFfaDrop(PlayerDropItemEvent event) {
        if (fighterPit.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onFfaQuit(PlayerQuitEvent event) {
        leaveFfa(event.getPlayer(), false);
    }
}
