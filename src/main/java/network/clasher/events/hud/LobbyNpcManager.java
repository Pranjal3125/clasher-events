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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Native Paper 26.2 Clickable Lobby NPC Statues — ZERO external NPC plugins (Citizens/FancyNpcs) required!
 * Spawns 4 armored, glowing, indestructible hero statues right in front of the Imperial Sky Citadel spawn:
 *  1. JOIN EVENT        (-404.5, 102, -6.5) -> joins open event / opens active Mode Vote GUI
 *  2. 1v1 DUEL QUEUE    (-401.5, 102, -7.5) -> opens 1v1 Auto-Queue GUI (shows live queued & fighting counts!)
 *  3. WARMUP FFA PIT    (-397.5, 102, -7.5) -> drops straight into Always-Open Warmup FFA!
 *  4. STATS & KIT EDITOR (-394.5, 102, -6.5) -> Click: Leaderboards GUI | Shift-Click: Kit Layout Editor!
 */
public final class LobbyNpcManager implements Listener {
    private static final Color LABEL_BG = Color.fromARGB(175, 14, 18, 30);

    private final JavaPlugin plugin;
    private final ArenaManager arenas;
    private final EventManager events;
    private final CasualDuelManager duels;
    private final WarmupFfaManager warmupFfa;
    private final EventGui gui;
    private final NamespacedKey npcKey;
    private final NamespacedKey npcLabelKey;
    private final Map<UUID, Long> clickCooldown = new HashMap<>();
    private BukkitTask refreshTask;

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
        // Refresh live labels every 5 seconds so player counts & open-event status stay fresh!
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshLiveLabelsOnly, 100L, 100L);
    }

    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    /**
     * Deduplicates and spawns all 4 clickable hero NPCs + their floating 3D status labels in Main Lobby.
     */
    public void respawnAll() {
        World world = Bukkit.getWorld(arenas.sharedWorldName());
        if (world == null) return;
        removeAllInWorld(world);
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
        int found = 0;
        for (TextDisplay td : world.getEntitiesByClass(TextDisplay.class)) {
            String id = td.getPersistentDataContainer().get(npcLabelKey, PersistentDataType.STRING);
            if (id == null) continue;
            found++;
            switch (id) {
                case "join_event" -> td.text(FormatUtil.mm(buildJoinEventLabel()));
                case "duel_queue" -> td.text(FormatUtil.mm(buildDuelQueueLabel()));
                case "warmup_ffa" -> td.text(FormatUtil.mm(buildWarmupFfaLabel()));
                case "leaderboards" -> td.text(FormatUtil.mm(buildLeaderboardsLabel()));
                default -> {}
            }
        }
        if (found < 4 && !world.getBlockAt(ArenaManager.LOBBY_ORIGIN_X, ArenaManager.LOBBY_ORIGIN_Y + 1, ArenaManager.LOBBY_ORIGIN_Z).getType().isAir()) {
            respawnAll();
        }
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
        int count = warmupFfa != null ? warmupFfa.playerCount() : 0;
        return "<red><bold>🔥 WARMUP FFA PIT</bold></red>\n"
                + "<yellow>" + count + " Fighting Now</yellow>\n"
                + "<green>▶ Click for Instant PvP!</green>";
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
        world.spawn(loc, ArmorStand.class, stand -> {
            stand.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, id);
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(true);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setPersistent(true);
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

        Location labelLoc = loc.clone().add(0.0, 2.45, 0.0);
        world.spawn(labelLoc, TextDisplay.class, td -> {
            td.getPersistentDataContainer().set(npcLabelKey, PersistentDataType.STRING, id);
            td.text(FormatUtil.mm(labelMini));
            td.setBillboard(Display.Billboard.CENTER);
            td.setBackgroundColor(LABEL_BG);
            td.setShadowed(true);
            td.setSeeThrough(false);
            td.setLineWidth(210);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setPersistent(true);
        });
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
        if (world != null) removeAllInWorld(world);
    }

    private void removeAllInWorld(World world) {
        for (Entity e : new ArrayList<>(world.getEntitiesByClass(ArmorStand.class))) {
            if (e.getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) {
                e.remove();
            }
        }
        for (Entity e : new ArrayList<>(world.getEntitiesByClass(TextDisplay.class))) {
            if (e.getPersistentDataContainer().has(npcLabelKey, PersistentDataType.STRING)) {
                e.remove();
            }
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
            case "warmup_ffa" -> {
                if (warmupFfa != null) warmupFfa.joinFfa(player);
            }
            case "leaderboards" -> {
                if (player.isSneaking()) gui.openKitEditorSelectorGui(player);
                else gui.openLeaderboardGui(player, "elo");
            }
            default -> {}
        }
    }
}
