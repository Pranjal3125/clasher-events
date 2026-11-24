package network.clasher.events.hud;

import network.clasher.events.arena.ArenaManager;
import network.clasher.events.gui.EventGui;
import network.clasher.events.match.CasualDuelManager;
import network.clasher.events.match.EventManager;
import network.clasher.events.match.WarmupFfaManager;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Native Paper 26.2 Clickable Lobby NPC Statues — ZERO external NPC plugins (Citizens/FancyNpcs) required!
 * Spawns 4 non-persistent, zero-tick-overhead (setCanTick=false) armored hero statues in front of Lobby spawn:
 *  1. JOIN EVENT        (-404.5, 102, -6.5) -> joins open event / opens active Mode Vote GUI
 *  2. 1v1 DUEL QUEUE    (-401.5, 102, -7.5) -> opens 1v1 Auto-Queue GUI (shows live queued & fighting counts!)
 *  3. WARMUP FFA PITS   (-397.5, 102, -7.5) -> opens Sword vs Deep Crystal Terrain FFA Selector GUI!
 *  4. STATS & KIT EDITOR (-394.5, 102, -6.5) -> Click: Leaderboards GUI | Shift-Click: Kit Layout Editor!
 *
 * Anti-Lag / Anti-Duplication Guarantees (v2.7.1):
 *  - Entities are NEVER persisted to chunk NBT (setPersistent(false)) so chunk unloads / restarts can NEVER stack duplicates.
 *  - Chunks are ALWAYS loaded BEFORE purging legacy persistent entities on startup/respawn.
 *  - The 5s label refresh NEVER force-loads unloaded chunks and skips redundant metadata updates via text caching.
 *  - EntitiesLoadEvent immediately destroys any stale persistent NPCs left over from earlier versions.
 */
public final class LobbyNpcManager implements Listener {
    private static final Color LABEL_BG = Color.fromARGB(175, 14, 18, 30);
    private static final List<String> ALL_NPC_IDS = List.of("join_event", "duel_queue", "warmup_ffa", "leaderboards");

    private final JavaPlugin plugin;
    private final ArenaManager arenas;
    private final EventManager events;
    private final CasualDuelManager duels;
    private final WarmupFfaManager warmupFfa;
    private final EventGui gui;
    private final NamespacedKey npcKey;
    private final NamespacedKey npcLabelKey;
    private final Map<UUID, Long> clickCooldown = new HashMap<>();
    private final Map<String, UUID> activeStands = new HashMap<>();
    private final Map<String, UUID> activeLabels = new HashMap<>();
    private final Map<String, String> lastLabelText = new HashMap<>();
    private BukkitTask refreshTask;
    private boolean pendingSpawnCheck;

    public LobbyNpcManager(
            JavaPlugin plugin,
            ArenaManager arenas,
            EventManager events,
            CasualDuelManager duels,
            WarmupFfaManager warmupFfa,
            EventGui gui
    ) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.events = events;
        this.duels = duels;
        this.warmupFfa = warmupFfa;
        this.gui = gui;
        this.npcKey = new NamespacedKey(plugin, "lobby_npc_id");
        this.npcLabelKey = new NamespacedKey(plugin, "lobby_npc_label");
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("lobby-npcs.enabled", true);
    }

    public void start() {
        stop();
        // Refresh live labels every 5 seconds — ONLY while Lobby chunks are already loaded!
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshLiveLabelsOnly, 100L, 100L);
    }

    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    /**
     * Loads Lobby NPC chunks FIRST so any stacked legacy persistent entities are 100% purged,
     * then spawns exactly 4 clean non-persistent hero NPCs + 4 status labels.
     */
    public void respawnAll() {
        World world = Bukkit.getWorld(arenas.sharedWorldName());
        if (world == null) return;

        // CRITICAL: Load all Lobby NPC chunks BEFORE purging so saved persistent entities from older versions are removed!
        loadNpcChunks(world);
        int purged = purgeAllInWorld(world);
        if (purged > 8) {
            plugin.getLogger().info("Cleaned up " + purged + " legacy/duplicate Lobby NPC entities.");
        }
        if (!isEnabled()) return;

        int lx = ArenaManager.LOBBY_ORIGIN_X;
        int ly = ArenaManager.LOBBY_ORIGIN_Y;
        int lz = ArenaManager.LOBBY_ORIGIN_Z;
        if (world.getBlockAt(lx, ly + 1, lz).getType().isAir()) return;

        spawnHeroNpc(world, new Location(world, lx - 4.5, ly + 2.0, lz - 6.5, -28f, 0f),
                "join_event",
                Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
                Material.NETHERITE_SWORD, Material.TOTEM_OF_UNDYING,
                buildJoinEventLabel());

        spawnHeroNpc(world, new Location(world, lx - 1.5, ly + 2.0, lz - 7.5, -10f, 0f),
                "duel_queue",
                Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
                Material.DIAMOND_SWORD, Material.SHIELD,
                buildDuelQueueLabel());

        spawnHeroNpc(world, new Location(world, lx + 2.5, ly + 2.0, lz - 7.5, 10f, 0f),
                "warmup_ffa",
                Material.IRON_HELMET, Material.DIAMOND_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
                Material.DIAMOND_AXE, Material.GOLDEN_APPLE,
                buildWarmupFfaLabel());

        spawnHeroNpc(world, new Location(world, lx + 5.5, ly + 2.0, lz - 6.5, 28f, 0f),
                "leaderboards",
                Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS,
                Material.NETHER_STAR, Material.WRITABLE_BOOK,
                buildLeaderboardsLabel());
    }

    private void refreshLiveLabelsOnly() {
        if (!isEnabled()) return;
        World world = Bukkit.getWorld(arenas.sharedWorldName());
        if (world == null) return;

        // NEVER force-load unloaded chunks when nobody is in Lobby!
        if (!areAllNpcChunksLoaded(world)) {
            return;
        }

        // Verify all 4 tracked stands + labels are alive; if chunks previously unloaded non-persistent NPCs, cleanly respawn!
        if (!allTrackedEntitiesAlive(world)) {
            respawnAll();
            return;
        }

        // Remove any rogue duplicate entities in loaded chunks that aren't our 4 tracked UUIDs
        pruneUntrackedDuplicatesInLoadedChunks(world);

        // Update label text ONLY when content actually changed (zero wasted entity metadata packets!)
        for (String id : ALL_NPC_IDS) {
            UUID labelId = activeLabels.get(id);
            if (labelId == null) continue;
            Entity entity = Bukkit.getEntity(labelId);
            if (!(entity instanceof TextDisplay td) || !td.isValid()) continue;
            String nextText = buildLabelFor(id);
            if (!nextText.equals(lastLabelText.get(id))) {
                td.text(FormatUtil.mm(nextText));
                lastLabelText.put(id, nextText);
            }
        }
    }

    private String buildLabelFor(String id) {
        return switch (id) {
            case "join_event" -> buildJoinEventLabel();
            case "duel_queue" -> buildDuelQueueLabel();
            case "warmup_ffa" -> buildWarmupFfaLabel();
            case "leaderboards" -> buildLeaderboardsLabel();
            default -> "";
        };
    }

    private String buildJoinEventLabel() {
        String status;
        if (events.isEventOpen()) {
            status = "<green><bold>OPEN: " + FormatUtil.escape(events.eventMode().displayName()) + "</bold></green>\n"
                    + "<yellow>" + events.participants().size() + " Joined • " + FormatUtil.formatDuration(events.countdownRemaining()) + "</yellow>";
        } else if (events.isModeVoteOpen()) {
            status = "<aqua><bold>🗳 MODE VOTE OPEN!</bold></aqua>\n<yellow>" + events.modeVoteRemaining() + "s left • Click to Vote!</yellow>";
        } else if (events.isEventRunning()) {
            status = "<red>LIVE: " + FormatUtil.escape(events.eventMode().displayName()) + " (" + events.alive().size() + " alive)</red>";
        } else {
            status = "<gray>Awaiting next Server Event</gray>";
        }
        return "<gold><bold>⚔ OFFICIAL EVENT</bold></gold>\n" + status + "\n<green>▶ Click to Join!</green>";
    }

    private String buildDuelQueueLabel() {
        int q = duels != null ? duels.totalQueuedCount() : 0;
        int fighting = duels != null ? duels.activeDuelPlayerCount() : 0;
        return "<aqua><bold>🎯 1v1 DUEL QUEUE</bold></aqua>\n"
                + "<yellow>" + q + " Queued</yellow> <dark_gray>•</dark_gray> <green>" + fighting + " Fighting</green>\n"
                + "<green>▶ Click to Queue 1v1!</green>";
    }

    private String buildWarmupFfaLabel() {
        int total = warmupFfa != null ? warmupFfa.playerCount() : 0;
        return "<red><bold>🔥 WARMUP FFA PITS</bold></red>\n"
                + "<gray>Sword & Deep Crystal Terrain</gray>\n"
                + "<yellow>" + total + " Fighting Now</yellow>\n"
                + "<green>▶ Click to Choose Pit!</green>";
    }

    private String buildLeaderboardsLabel() {
        return "<gold><bold>🏆 TOP & KIT EDITOR</bold></gold>\n"
                + "<yellow>Click:</yellow> <white>Leaderboards</white>\n"
                + "<aqua>Shift-Click:</aqua> <white>Kit Layout Editor</white>";
    }

    private void spawnHeroNpc(
            World world,
            Location loc,
            String id,
            Material helmet,
            Material chest,
            Material legs,
            Material boots,
            Material mainHand,
            Material offHand,
            String labelMini
    ) {
        loc.getChunk().load();
        ArmorStand spawnedStand = world.spawn(loc, ArmorStand.class, stand -> {
            stand.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, id);
            stand.setGravity(false);
            stand.setCanTick(false);
            stand.setCanMove(false);
            stand.setCollidable(false);
            stand.setBasePlate(false);
            stand.setArms(true);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setPersistent(false);
            stand.setCanPickupItems(false);
            stand.setRightArmPose(new EulerAngle(Math.toRadians(-35), Math.toRadians(-10), 0));
            stand.setLeftArmPose(new EulerAngle(Math.toRadians(-20), Math.toRadians(15), 0));
            stand.getEquipment().setHelmet(glint(helmet));
            stand.getEquipment().setChestplate(glint(chest));
            stand.getEquipment().setLeggings(glint(legs));
            stand.getEquipment().setBoots(glint(boots));
            stand.getEquipment().setItem(EquipmentSlot.HAND, glint(mainHand));
            stand.getEquipment().setItem(EquipmentSlot.OFF_HAND, new ItemStack(offHand));
        });
        activeStands.put(id, spawnedStand.getUniqueId());

        Location labelLoc = loc.clone().add(0.0, 2.45, 0.0);
        TextDisplay spawnedLabel = world.spawn(labelLoc, TextDisplay.class, td -> {
            td.getPersistentDataContainer().set(npcLabelKey, PersistentDataType.STRING, id);
            td.text(FormatUtil.mm(labelMini));
            td.setBillboard(Display.Billboard.CENTER);
            td.setBackgroundColor(LABEL_BG);
            td.setShadowed(true);
            td.setSeeThrough(false);
            td.setLineWidth(210);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setPersistent(false);
        });
        activeLabels.put(id, spawnedLabel.getUniqueId());
        lastLabelText.put(id, labelMini);
    }

    private static ItemStack glint(Material mat) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public void removeAll() {
        World world = Bukkit.getWorld(arenas.sharedWorldName());
        if (world != null) {
            loadNpcChunks(world);
            purgeAllInWorld(world);
        }
    }

    private int purgeAllInWorld(World world) {
        int removed = 0;
        // Explicitly read chunk.getEntities() so Paper synchronously loads each entity slice before purging!
        for (int[] c : npcChunkCoords()) {
            for (Entity e : world.getChunkAt(c[0], c[1]).getEntities()) {
                if (e instanceof ArmorStand && e.getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) {
                    e.remove();
                    removed++;
                } else if (e instanceof TextDisplay && e.getPersistentDataContainer().has(npcLabelKey, PersistentDataType.STRING)) {
                    e.remove();
                    removed++;
                }
            }
        }
        for (Entity e : new ArrayList<>(world.getEntitiesByClass(ArmorStand.class))) {
            if (e.isValid() && e.getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) {
                e.remove();
                removed++;
            }
        }
        for (Entity e : new ArrayList<>(world.getEntitiesByClass(TextDisplay.class))) {
            if (e.isValid() && e.getPersistentDataContainer().has(npcLabelKey, PersistentDataType.STRING)) {
                e.remove();
                removed++;
            }
        }
        activeStands.clear();
        activeLabels.clear();
        lastLabelText.clear();
        return removed;
    }

    private void pruneUntrackedDuplicatesInLoadedChunks(World world) {
        Set<UUID> validStands = new HashSet<>(activeStands.values());
        Set<UUID> validLabels = new HashSet<>(activeLabels.values());
        for (Entity e : new ArrayList<>(world.getEntitiesByClass(ArmorStand.class))) {
            if (e.getPersistentDataContainer().has(npcKey, PersistentDataType.STRING) && !validStands.contains(e.getUniqueId())) {
                e.remove();
            }
        }
        for (Entity e : new ArrayList<>(world.getEntitiesByClass(TextDisplay.class))) {
            if (e.getPersistentDataContainer().has(npcLabelKey, PersistentDataType.STRING) && !validLabels.contains(e.getUniqueId())) {
                e.remove();
            }
        }
    }

    private boolean allTrackedEntitiesAlive(World world) {
        if (activeStands.size() < ALL_NPC_IDS.size() || activeLabels.size() < ALL_NPC_IDS.size()) {
            return false;
        }
        for (String id : ALL_NPC_IDS) {
            UUID sid = activeStands.get(id);
            UUID lid = activeLabels.get(id);
            if (sid == null || lid == null) return false;
            Entity stand = Bukkit.getEntity(sid);
            Entity label = Bukkit.getEntity(lid);
            if (stand == null || !stand.isValid() || !stand.getWorld().equals(world)) return false;
            if (label == null || !label.isValid() || !label.getWorld().equals(world)) return false;
        }
        return true;
    }

    private int[][] npcChunkCoords() {
        int lx = ArenaManager.LOBBY_ORIGIN_X;
        int lz = ArenaManager.LOBBY_ORIGIN_Z;
        int minCx = (lx - 8) >> 4;
        int maxCx = (lx + 8) >> 4;
        int minCz = (lz - 12) >> 4;
        int maxCz = (lz + 2) >> 4;
        List<int[]> coords = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                coords.add(new int[]{cx, cz});
            }
        }
        return coords.toArray(new int[0][]);
    }

    private void loadNpcChunks(World world) {
        for (int[] c : npcChunkCoords()) {
            world.getChunkAt(c[0], c[1]).load();
        }
    }

    private boolean areAllNpcChunksLoaded(World world) {
        for (int[] c : npcChunkCoords()) {
            if (!world.isChunkLoaded(c[0], c[1])) return false;
        }
        return true;
    }

    private boolean isNearLobbyNpcChunk(int chunkX, int chunkZ) {
        int lx = ArenaManager.LOBBY_ORIGIN_X;
        int lz = ArenaManager.LOBBY_ORIGIN_Z;
        return chunkX >= ((lx - 8) >> 4) && chunkX <= ((lx + 8) >> 4)
                && chunkZ >= ((lz - 12) >> 4) && chunkZ <= ((lz + 2) >> 4);
    }

    // -------------------------------------------------------------------------
    // Chunk Entity Load Cleanup (wipes any legacy v2.6/v2.7.0 persistent clones)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        World world = event.getWorld();
        if (!world.getName().equalsIgnoreCase(arenas.sharedWorldName())) return;

        Set<UUID> validStands = new HashSet<>(activeStands.values());
        Set<UUID> validLabels = new HashSet<>(activeLabels.values());
        int purged = 0;
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ArmorStand
                    && entity.getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)
                    && !validStands.contains(entity.getUniqueId())) {
                entity.remove();
                purged++;
            } else if (entity instanceof TextDisplay
                    && entity.getPersistentDataContainer().has(npcLabelKey, PersistentDataType.STRING)
                    && !validLabels.contains(entity.getUniqueId())) {
                entity.remove();
                purged++;
            }
        }
        if (purged > 0) {
            plugin.getLogger().info("Purged " + purged + " stale persistent Lobby NPC entities on chunk load.");
        }

        // When a player walks/teleports into Lobby after chunks unloaded, cleanly restore the 4 non-persistent NPCs once!
        if (isEnabled() && isNearLobbyNpcChunk(event.getChunk().getX(), event.getChunk().getZ())
                && !allTrackedEntitiesAlive(world) && !pendingSpawnCheck) {
            pendingSpawnCheck = true;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pendingSpawnCheck = false;
                if (isEnabled() && areAllNpcChunksLoaded(world) && !allTrackedEntitiesAlive(world)) {
                    respawnAll();
                }
            }, 2L);
        }
    }

    // -------------------------------------------------------------------------
    // Click & Protection Listeners (Right-Click AND Left-Click both trigger!)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcManipulate(PlayerArmorStandManipulateEvent event) {
        if (event.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcInteract(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        String id = event.getRightClicked().getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);
        handleNpcClick(event.getPlayer(), id);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcPunch(EntityDamageByEntityEvent event) {
        String id = event.getEntity().getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);
        if (event.getDamager() instanceof Player p) {
            handleNpcClick(p, id);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcAnyDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clickCooldown.remove(event.getPlayer().getUniqueId());
    }

    public boolean recentlyClickedNpc(UUID playerId) {
        Long last = clickCooldown.get(playerId);
        return last != null && (System.currentTimeMillis() - last) < 250L;
    }

    private void handleNpcClick(Player player, String npcId) {
        long now = System.currentTimeMillis();
        Long last = clickCooldown.get(player.getUniqueId());
        if (last != null && (now - last) < 400L) return;
        clickCooldown.put(player.getUniqueId(), now);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.3f);

        switch (npcId) {
            case "join_event" -> {
                if (events.isModeVoteOpen()) gui.openModeVoteGui(player);
                else if (events.isEventOpen()) events.joinEvent(player);
                else if (events.isEventRunning() && events.eventMode().id().equals("tournament")) gui.openBracketViewerGui(player);
                else player.sendMessage(FormatUtil.mm("<yellow>No Server Event is open right now — try <aqua>1v1 Duel Queue</aqua> or <red>Warmup FFA</red>!</yellow>"));
            }
            case "duel_queue" -> gui.openDuelQueueGui(player);
            case "warmup_ffa" -> gui.openFfaSelectorGui(player);
            case "leaderboards" -> {
                if (player.isSneaking()) gui.openKitEditorSelectorGui(player);
                else gui.openLeaderboardGui(player, "elo");
            }
            default -> {}
        }
    }
}
