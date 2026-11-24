package network.clasher.events.gui;

import net.kyori.adventure.text.Component;
import network.clasher.events.arena.ArenaManager;
import network.clasher.events.kit.KitManager;
import network.clasher.events.match.DuelSnapshotStore;
import network.clasher.events.match.EventManager;
import network.clasher.events.match.TournamentSquadManager;
import network.clasher.events.model.EventMode;
import network.clasher.events.model.EventModifier;
import network.clasher.events.storage.DataStore;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Secure InventoryHolder-based GUIs for admin controls, countdown selection,
 * pre-event mode voting, multi-tab Elo/Wins/Kills leaderboards, kit previews,
 * personal kit layout editor, winner predictions, and spectator target selection.
 */
public final class EventGui implements Listener {
    private static final class EditorSession {
        final String kitId;
        int selectedGuiSlot = -1;
        EditorSession(String kitId) { this.kitId = kitId; }
    }

    private final JavaPlugin plugin;
    private final ArenaManager arenas;
    private final EventManager events;
    private final KitManager kits;
    private final NamespacedKey actionKey;
    private final NamespacedKey valueKey;
    private final NamespacedKey origSlotKey;
    private final Map<UUID, EditorSession> editorSessions = new HashMap<>();

    public EventGui(JavaPlugin plugin, ArenaManager arenas, EventManager events, KitManager kits) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.events = events;
        this.kits = kits;
        this.actionKey = new NamespacedKey(plugin, "ce-gui-action");
        this.valueKey = new NamespacedKey(plugin, "ce-gui-value");
        this.origSlotKey = new NamespacedKey(plugin, "ce-kit-orig-slot");
    }

    // -------------------------------------------------------------------------
    // Admin Mode & Control GUI (45 slots)
    // -------------------------------------------------------------------------

    public void openAdminGui(Player admin) {
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.ADMIN_MODES);
        Inventory inv = Bukkit.createInventory(holder, 45, FormatUtil.mm("<dark_gray><bold>Clasher Events</bold></dark_gray> <gray>|</gray> <gold>Control Panel</gold>"));
        holder.setInventory(inv);
        fillBorder(inv, 45);

        int[] modeSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};
        EventMode[] modes = EventMode.values();
        for (int i = 0; i < modes.length && i < modeSlots.length; i++) {
            EventMode mode = modes[i];
            boolean picksKit = mode.supportsKitSelection();
            String clickAction = mode == EventMode.TOURNAMENT ? "pick-tourney-size"
                    : (picksKit ? "pick-admin-kit" : "create");
            String clickHint = mode == EventMode.TOURNAMENT ? "<green>Click to pick 1v1/2v2/3v3/4v4 & Kit</green>"
                    : (picksKit ? "<green>Click to choose Kit & Arena</green>" : "<green>Click to open registration</green>");
            inv.setItem(modeSlots[i], button(
                    mode.icon(),
                    "<yellow><bold>" + mode.displayName() + "</bold></yellow>",
                    List.of(
                            "<gray>Mode ID:</gray> <white>" + mode.id() + "</white>",
                            "<gray>Arena/Kit:</gray> <white>" + (picksKit ? "<aqua>Choose Any Kit!</aqua>" : (mode.defaultArenaId() == null ? "Random" : mode.defaultArenaId())) + "</white>",
                            "",
                            clickHint
                    ),
                    clickAction,
                    mode.id()
            ));
        }

        inv.setItem(28, button(
                Material.GOLDEN_HELMET,
                "<gold><bold>Tournament Control</bold></gold>",
                List.of(
                        "<gray>Inspect squads, Auto-Shuffle, Lock,</gray>",
                        "<gray>Toggle Bo3 Finals & Start Kit Vote!</gray>"
                ),
                "open-admin-tourney",
                ""
        ));
        inv.setItem(29, button(
                Material.BELL,
                "<aqua><bold>Open Mode Vote</bold></aqua>",
                List.of("<gray>Let players vote on the next PvP mode</gray>", "<gray>using the interactive Voting GUI!</gray>"),
                "start-mode-vote",
                ""
        ));
        inv.setItem(30, button(
                Material.CLOCK,
                "<yellow><bold>Registration Countdown</bold></yellow>",
                List.of("<gray>Configure 2 / 5 / 10 minute countdown.</gray>"),
                "countdown",
                ""
        ));
        inv.setItem(31, button(
                events.isPaused() ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE,
                events.isPaused() ? "<green><bold>Resume Event</bold></green>" : "<yellow><bold>Pause Event</bold></yellow>",
                List.of("<gray>Toggle registration countdown pause.</gray>"),
                "toggle-pause",
                ""
        ));
        inv.setItem(32, button(
                Material.BARRIER,
                "<red><bold>Stop Event</bold></red>",
                List.of("<gray>End active event, rollback arena, & restore players.</gray>"),
                "stop",
                ""
        ));
        inv.setItem(33, button(
                Material.GRASS_BLOCK,
                "<green><bold>Full Server Setup</bold></green>",
                List.of(
                        "<gray>Left-Click: Paste Imperial Lobby, Tournament Hall,</gray>",
                        "<gray>Podium, 8 Grand Arenas & 8 Dedicated 1v1 Duel Arenas</gray>",
                        "<yellow>Shift-Click: Refresh bundled v2.5 builds & paste all</yellow>"
                ),
                "full-setup",
                ""
        ));
        inv.setItem(34, button(
                Material.FEATHER,
                "<aqua><bold>Skip Timer</bold></aqua>",
                List.of("<gray>Skip registration countdown to 5s</gray>", "<gray>(or finish active mode vote now).</gray>"),
                "skip-timer",
                ""
        ));
        inv.setItem(39, button(
                Material.BREWING_STAND,
                "<light_purple><bold>Event Modifiers</bold></light_purple>",
                List.of(
                        "<gray>Active:</gray> <yellow>" + FormatUtil.escape(events.modifiersSummary()) + "</yellow>",
                        "<gray>Toggle Double HP, Vampire, Low Gravity,</gray>",
                        "<gray>Speed Chaos, No Pearls & One-Hit Bow!</gray>"
                ),
                "open-modifiers",
                ""
        ));
        inv.setItem(40, button(
                Material.BOOK,
                "<aqua><bold>Event Status</bold></aqua>",
                List.of(
                        "<gray>Mode:</gray> <white>" + events.eventMode().displayName() + "</white>",
                        "<gray>Phase:</gray> <yellow>" + events.phase() + "</yellow>",
                        "<gray>Registered:</gray> <green>" + events.participants().size() + "</green>",
                        "<gray>Alive:</gray> <green>" + events.alive().size() + "</green>"
                ),
                "status",
                ""
        ));
        boolean camOn = events.isAutoCamEnabled(admin.getUniqueId());
        inv.setItem(41, button(
                Material.ENDER_EYE,
                camOn ? "<green><bold>🎥 Host Auto-Cam: ON</bold></green>" : "<yellow><bold>🎥 Host Auto-Cam: OFF</bold></yellow>",
                List.of(
                        "<gray>Automatically teleports your spectator camera</gray>",
                        "<gray>to live PvP fights every 4 seconds!</gray>"
                ),
                "toggle-autocam",
                ""
        ));
        admin.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Tournament Team Size Picker (1v1 / 2v2 / 3v3 / 4v4) -> Kit Picker
    // -------------------------------------------------------------------------

    public void openTournamentSizePicker(Player admin) {
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.TOURNAMENT_SIZE_PICKER);
        Inventory inv = Bukkit.createInventory(holder, 27,
                FormatUtil.mm("<dark_gray>Tournament:</dark_gray> <gold><bold>Select Team Size</bold></gold>"));
        holder.setInventory(inv);
        fillBorder(inv, 27);

        inv.setItem(10, button(Material.IRON_SWORD, "<yellow><bold>1v1 Solo Tournament</bold></yellow>", List.of(
                "<gray>Format:</gray> <white>Solo Single-Elimination Bracket</white>",
                "<gray>Arena:</gray> <aqua>Dedicated 1v1 Duel Arena</aqua>",
                "<gray>Finals:</gray> <gold>" + (events.squads().isBo3Finals() ? "Best-of-3 Grand Final" : "Best-of-1 Final") + "</gold>",
                "", "<green>Click to pick Kit!</green>"
        ), "pick-tourney-kit", "1"));

        inv.setItem(12, button(Material.DIAMOND_SWORD, "<aqua><bold>2v2 Duo Tournament</bold></aqua>", List.of(
                "<gray>Format:</gray> <white>Teams of 2 • Timed Squad Invites</white>",
                "<gray>Lobby:</gray> <gold>Grand Esports Tournament Hall</gold>",
                "<gray>Arena:</gray> <aqua>Grand Event Arena</aqua>",
                "", "<green>Click to pick Kit!</green>"
        ), "pick-tourney-kit", "2"));

        inv.setItem(14, button(Material.NETHERITE_SWORD, "<gold><bold>3v3 Trio Tournament</bold></gold>", List.of(
                "<gray>Format:</gray> <white>Teams of 3 • Timed Squad Invites</white>",
                "<gray>Lobby:</gray> <gold>Grand Esports Tournament Hall</gold>",
                "<gray>Arena:</gray> <aqua>Grand Event Arena</aqua>",
                "", "<green>Click to pick Kit!</green>"
        ), "pick-tourney-kit", "3"));

        inv.setItem(16, button(Material.GOLDEN_HELMET, "<light_purple><bold>4v4 Squad Tournament</bold></light_purple>", List.of(
                "<gray>Format:</gray> <white>Teams of 4 • Timed Squad Invites</white>",
                "<gray>Lobby:</gray> <gold>Grand Esports Tournament Hall</gold>",
                "<gray>Arena:</gray> <aqua>Grand Event Arena</aqua>",
                "", "<green>Click to pick Kit!</green>"
        ), "pick-tourney-kit", "4"));

        inv.setItem(22, button(Material.ARROW, "<yellow>Back</yellow>", List.of("<gray>Return to Control Panel</gray>"), "open-admin", ""));
        admin.openInventory(inv);
    }

    public void openTournamentKitPicker(Player admin, int teamSize) {
        int clamped = Math.max(1, Math.min(4, teamSize));
        String fmt = clamped + "v" + clamped;
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.KIT_PICKER);
        Inventory inv = Bukkit.createInventory(holder, 36,
                FormatUtil.mm("<dark_gray>" + fmt + " Tourney:</dark_gray> <gold><bold>Pick Kit</bold></gold>"));
        holder.setInventory(inv);
        fillBorder(inv, 36);

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        List<EventMode> kitModes = EventMode.kitModes();
        for (int i = 0; i < kitModes.size() && i < slots.length; i++) {
            EventMode km = kitModes.get(i);
            inv.setItem(slots[i], button(
                    km.icon(),
                    "<yellow><bold>" + km.displayName() + "</bold></yellow>",
                    List.of(
                            "<gray>Format:</gray> <gold>" + fmt + " Tournament</gold>",
                            "<gray>Kit:</gray> <white>" + km.id() + "</white>",
                            "<gray>Arena:</gray> <aqua>" + (clamped == 1 ? "Dedicated 1v1 Duel Arena" : "Grand Main Arena") + "</aqua>",
                            "",
                            "<green>Click to open registration!</green>"
                    ),
                    "create-tourney",
                    clamped + ":" + km.id()
            ));
        }
        inv.setItem(31, button(Material.ARROW, "<yellow>Back</yellow>", List.of("<gray>Choose Team Size</gray>"), "pick-tourney-size", ""));
        admin.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Kit & Arena Selector GUI (Admin Duel/TeamWars/Tournament + Casual Player Duel)
    // -------------------------------------------------------------------------

    public void openAdminKitPicker(Player admin, EventMode parentMode) {
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.KIT_PICKER);
        Inventory inv = Bukkit.createInventory(holder, 36,
                FormatUtil.mm("<dark_gray>Pick Kit:</dark_gray> <gold><bold>" + parentMode.displayName() + "</bold></gold>"));
        holder.setInventory(inv);
        fillBorder(inv, 36);

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        List<EventMode> kitModes = EventMode.kitModes();
        for (int i = 0; i < kitModes.size() && i < slots.length; i++) {
            EventMode km = kitModes.get(i);
            inv.setItem(slots[i], button(
                    km.icon(),
                    "<yellow><bold>" + km.displayName() + "</bold></yellow>",
                    List.of(
                            "<gray>Format:</gray> <aqua>" + parentMode.displayName() + "</aqua>",
                            "<gray>Arena & Kit:</gray> <white>" + km.id() + "</white>",
                            "",
                            "<green>Click to launch!</green>"
                    ),
                    "create-with-kit",
                    parentMode.id() + ":" + km.id()
            ));
        }
        inv.setItem(31, button(Material.ARROW, "<yellow>Back</yellow>", List.of("<gray>Return to Control Panel</gray>"), "open-admin", ""));
        admin.openInventory(inv);
    }

    public void openCasualDuelKitPicker(Player challenger, Player target) {
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.KIT_PICKER);
        Inventory inv = Bukkit.createInventory(holder, 36,
                FormatUtil.mm("<dark_gray>1v1 Duel</dark_gray> <yellow>" + FormatUtil.escape(target.getName()) + "</yellow> <gray>- Pick Kit</gray>"));
        holder.setInventory(inv);
        fillBorder(inv, 36);

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        List<EventMode> kitModes = EventMode.kitModes();
        int freeSlots = events.casualDuels() != null ? events.casualDuels().freeDuelArenaCount() : 0;
        for (int i = 0; i < kitModes.size() && i < slots.length; i++) {
            EventMode km = kitModes.get(i);
            inv.setItem(slots[i], button(
                    km.icon(),
                    "<yellow><bold>" + km.displayName() + "</bold></yellow>",
                    List.of(
                            "<gray>Opponent:</gray> <white>" + FormatUtil.escape(target.getName()) + "</white>",
                            "<gray>Free Duel Arenas:</gray> " + (freeSlots > 0 ? "<green>" + freeSlots + "/8 Ready</green>" : "<red>Full</red>"),
                            "",
                            freeSlots > 0 ? "<green>Click to send duel invite!</green>" : "<red>All duel arenas busy</red>"
                    ),
                    "casual-duel-send",
                    target.getName() + ":" + km.id()
            ));
        }
        challenger.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // 1v1 Auto-Queue GUI (36 slots) + Kit Editor Selector GUI (36 slots)
    // -------------------------------------------------------------------------

    public void openDuelQueueGui(Player player) {
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.DUEL_QUEUE);
        Inventory inv = Bukkit.createInventory(holder, 36,
                FormatUtil.mm("<dark_gray><bold>🎯 1v1 Duel Queue</bold></dark_gray> <gray>|</gray> <aqua>Pick Kit</aqua>"));
        holder.setInventory(inv);
        fillBorder(inv, 36);

        String myQueue = events.casualDuels() != null ? events.casualDuels().queuedKitOf(player.getUniqueId()) : null;
        int freeSlots = events.casualDuels() != null ? events.casualDuels().freeDuelArenaCount() : 0;
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        List<EventMode> kitModes = EventMode.kitModes();
        for (int i = 0; i < kitModes.size() && i < slots.length; i++) {
            EventMode km = kitModes.get(i);
            int qCount = events.casualDuels() != null ? events.casualDuels().queueCount(km.id()) : 0;
            boolean inThis = km.id().equalsIgnoreCase(myQueue);
            ItemStack item = button(
                    km.icon(),
                    (inThis ? "<green><bold>✔ " : "<yellow><bold>") + km.displayName() + (inThis ? " (QUEUED)</bold></green>" : "</bold></yellow>"),
                    List.of(
                            "<gray>In Queue:</gray> <aqua><bold>" + qCount + "</bold></aqua>",
                            "<gray>Free Duel Arenas:</gray> <green>" + freeSlots + "/8</green>",
                            "",
                            inThis ? "<red>Click to leave queue</red>" : "<green>Click to join 1v1 Queue!</green>"
                    ),
                    inThis ? "queue-leave" : "queue-join",
                    km.id()
            );
            if (inThis) setGlint(item);
            inv.setItem(slots[i], item);
        }

        if (myQueue != null) {
            inv.setItem(29, button(Material.RED_WOOL, "<red><bold>Leave 1v1 Queue</bold></red>",
                    List.of("<gray>Currently queued for <gold>" + FormatUtil.escape(myQueue) + "</gold></gray>"), "queue-leave", ""));
        }
        inv.setItem(31, button(Material.BLAZE_POWDER, "<red><bold>🔥 Warmup FFA Pit</bold></red>",
                List.of("<gray>Drop straight into nonstop PvP practice!</gray>",
                        "<yellow>" + (events.warmupFfa() != null ? events.warmupFfa().playerCount() : 0) + " fighting now</yellow>"), "join-ffa", ""));
        inv.setItem(33, button(Material.IRON_SWORD, "<green><bold>Edit Kit Layout</bold></green>",
                List.of("<gray>Customize your hotbar & offhand for any kit!</gray>"), "open-kiteditor-picker", ""));
        player.openInventory(inv);
    }

    public void openKitEditorSelectorGui(Player player) {
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.KIT_EDITOR_SELECTOR);
        Inventory inv = Bukkit.createInventory(holder, 36,
                FormatUtil.mm("<dark_gray><bold>Edit Kit Layout</bold></dark_gray> <gray>|</gray> <gold>Select Kit</gold>"));
        holder.setInventory(inv);
        fillBorder(inv, 36);

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        List<EventMode> kitModes = EventMode.kitModes();
        for (int i = 0; i < kitModes.size() && i < slots.length; i++) {
            EventMode km = kitModes.get(i);
            inv.setItem(slots[i], button(
                    km.icon(),
                    "<yellow><bold>" + km.displayName() + "</bold></yellow>",
                    List.of("<gray>Click to customize your personal</gray>", "<gray>hotbar & offhand layout for this kit!</gray>"),
                    "open-kit-editor",
                    km.id()
            ));
        }
        player.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Post-Duel Inventory & Pro Combat Analytics Viewer GUI (54 slots, Read-Only)
    // -------------------------------------------------------------------------

    public void openPostDuelInventoryGui(Player viewer, String token) {
        DuelSnapshotStore.PostDuelSnapshot snap = events.casualDuels() != null ? events.casualDuels().snapshots().get(token) : null;
        if (snap == null) {
            viewer.sendMessage(FormatUtil.mm("<red>That post-duel inventory snapshot has expired (snapshots last 5 minutes).</red>"));
            return;
        }
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.POST_DUEL_INV);
        Inventory inv = Bukkit.createInventory(holder, 54,
                FormatUtil.mm("<dark_gray>Duel Inv:</dark_gray> " + (snap.winner() ? "<green><bold>" : "<red><bold>")
                        + FormatUtil.escape(snap.playerName()) + (snap.winner() ? "</bold></green>" : "</bold></red>")));
        holder.setInventory(inv);

        // Top row: Armor (0..3), Offhand (4), Pro Combat Stats (5), Result Badge (6), Ping (7), Close (8)
        for (int i = 0; i < 4; i++) {
            if (snap.armor()[i] != null) inv.setItem(i, snap.armor()[i].clone());
        }
        if (snap.offhand() != null) inv.setItem(4, snap.offhand().clone());
        inv.setItem(5, button(Material.DIAMOND_SWORD, "<gold><bold>⚔ Combat Analytics</bold></gold>", List.of(
                "<gray>Health Left:</gray> <red>" + String.format(Locale.ROOT, "%.1f / %.0f ❤", snap.remainingHp(), snap.maxHp()) + "</red>",
                "<gray>Hits Landed:</gray> <yellow>" + snap.hitsLanded() + "</yellow> <gray>(Crits: <aqua>" + snap.critPercent() + "%</aqua>)</gray>",
                "<gray>Longest Combo:</gray> <green>" + snap.longestCombo() + " hits</green>",
                "<gray>Total Damage:</gray> <red>" + String.format(Locale.ROOT, "%.1f DMG", snap.damageDealt()) + "</red>",
                "<gray>Kit:</gray> <white>" + FormatUtil.escape(snap.kitId()) + "</white>"
        ), "noop", ""));
        inv.setItem(6, button(snap.winner() ? Material.GOLD_BLOCK : Material.RED_CONCRETE,
                snap.winner() ? "<gold><bold>🏆 MATCH WINNER</bold></gold>" : "<red><bold>💀 DEFEATED</bold></red>",
                List.of("<gray>Player:</gray> <white>" + FormatUtil.escape(snap.playerName()) + "</white>"), "noop", ""));
        inv.setItem(7, button(Material.COMPARATOR, "<aqua><bold>Ping Snapshot</bold></aqua>",
                List.of("<green>" + snap.pingMs() + "ms</green>"), "noop", ""));
        inv.setItem(8, button(Material.BARRIER, "<gray>Close</gray>", List.of(), "close", ""));

        // Rows 2-5: Exact hotbar (0..8 -> GUI 9..17) & main inventory (9..35 -> GUI 18..44)
        for (int i = 0; i < Math.min(36, snap.contents().length); i++) {
            if (snap.contents()[i] != null) inv.setItem(9 + i, snap.contents()[i].clone());
        }
        ItemStack bottom = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int s = 45; s < 54; s++) inv.setItem(s, bottom);
        viewer.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Admin Event Modifiers / Mutators GUI (36 slots)
    // -------------------------------------------------------------------------

    public void openEventModifiersGui(Player admin) {
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.EVENT_MODIFIERS);
        Inventory inv = Bukkit.createInventory(holder, 36,
                FormatUtil.mm("<dark_gray><bold>⚡ Event Modifiers</bold></dark_gray> <gray>|</gray> <light_purple>Mutators</light_purple>"));
        holder.setInventory(inv);
        fillBorder(inv, 36);

        int[] slots = {10, 11, 12, 14, 15, 16};
        EventModifier[] mods = EventModifier.values();
        for (int i = 0; i < mods.length && i < slots.length; i++) {
            EventModifier m = mods[i];
            boolean on = events.hasModifier(m);
            ItemStack item = button(
                    m.icon(),
                    (on ? "<green><bold>● " : "<yellow><bold>") + m.displayName() + (on ? " [ON]</bold></green>" : " [OFF]</bold></yellow>"),
                    List.of(
                            "<gray>" + m.description() + "</gray>",
                            "",
                            on ? "<red>Click to disable</red>" : "<green>Click to enable for events!</green>"
                    ),
                    "toggle-modifier",
                    m.id()
            );
            if (on) setGlint(item);
            inv.setItem(slots[i], item);
        }
        inv.setItem(29, button(Material.BARRIER, "<red><bold>Clear All Modifiers</bold></red>",
                List.of("<gray>Reset back to vanilla competitive rules.</gray>"), "clear-modifiers", ""));
        inv.setItem(33, button(Material.ARROW, "<yellow>Back to Control Panel</yellow>", List.of(), "open-admin", ""));
        admin.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Smart Voting GUI Router + Mode Vote GUI + Tournament Kit Vote GUI
    // -------------------------------------------------------------------------

    public void openSmartVoteGui(Player player) {
        if (events.isModeVoteOpen()) {
            openModeVoteGui(player);
        } else if (events.isKitVoteOpen()) {
            openKitVoteGui(player);
        } else if (events.isEventRunning() && events.isSpectator(player.getUniqueId())) {
            openVoteGui(player);
        } else {
            player.sendMessage(FormatUtil.mm("<yellow>No vote is currently open! Staff can open one with <gold>/ce voteopen</gold> or <gold>/ce tournament kitvote</gold>.</yellow>"));
        }
    }

    public void openModeVoteGui(Player player) {
        if (!events.isModeVoteOpen()) {
            player.sendMessage(FormatUtil.mm("<red>Mode voting is not currently open.</red>"));
            return;
        }
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.MODE_VOTE);
        Inventory inv = Bukkit.createInventory(holder, 45,
                FormatUtil.mm("<dark_gray><bold>🗳 Vote Next Mode</bold></dark_gray> <gray>(" + events.modeVoteRemaining() + "s)</gray>"));
        holder.setInventory(inv);
        fillBorder(inv, 45);

        Map<String, Integer> counts = events.modeVoteCounts();
        int totalVotes = Math.max(0, events.totalModeVotes());
        String myVote = events.playerModeVote(player.getUniqueId());

        int slot = 10;
        for (EventMode mode : events.modeVoteOptions()) {
            slot = nextInteriorSlot(slot, 35);
            if (slot < 0) break;
            int votes = counts.getOrDefault(mode.id(), 0);
            boolean picked = mode.id().equalsIgnoreCase(myVote);
            int pct = totalVotes > 0 ? (int) Math.round((votes * 100.0) / totalVotes) : 0;
            ItemStack item = button(
                    mode.icon(),
                    (picked ? "<green><bold>✔ " : "<yellow><bold>") + mode.displayName() + (picked ? "</bold></green>" : "</bold></yellow>"),
                    List.of(
                            "<gray>Votes:</gray> <green><bold>" + votes + "</bold></green> <gray>(" + pct + "%)</gray>",
                            progressBarMini(pct),
                            "",
                            picked ? "<green><bold>★ YOUR CURRENT VOTE ★</bold></green>" : "<aqua>Click to vote for this mode!</aqua>"
                    ),
                    "mode-vote",
                    mode.id()
            );
            if (picked) setGlint(item);
            inv.setItem(slot++, item);
        }
        inv.setItem(39, button(Material.CLOCK, "<gold><bold>Time Remaining</bold></gold>",
                List.of("<yellow>" + FormatUtil.formatDuration(events.modeVoteRemaining()) + "</yellow>"), "noop", ""));
        inv.setItem(41, button(Material.PAPER, "<aqua><bold>Total Votes Cast</bold></aqua>",
                List.of("<green>" + totalVotes + " players voted</green>", "<gray>Click any mode to switch your vote!</gray>"), "noop", ""));
        player.openInventory(inv);
    }

    public void openKitVoteGui(Player player) {
        if (!events.isKitVoteOpen()) {
            player.sendMessage(FormatUtil.mm("<red>Tournament kit voting is not currently open.</red>"));
            return;
        }
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.KIT_VOTE);
        Inventory inv = Bukkit.createInventory(holder, 36,
                FormatUtil.mm("<dark_gray><bold>🗳 Vote Tourney Kit</bold></dark_gray> <gray>(" + events.kitVoteRemaining() + "s)</gray>"));
        holder.setInventory(inv);
        fillBorder(inv, 36);

        Map<String, Integer> counts = events.kitVoteCounts();
        int totalVotes = Math.max(0, events.totalKitVotes());
        String myVote = events.playerKitVote(player.getUniqueId());
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        List<EventMode> kitModes = EventMode.kitModes();
        for (int i = 0; i < kitModes.size() && i < slots.length; i++) {
            EventMode km = kitModes.get(i);
            int votes = counts.getOrDefault(km.id(), 0);
            boolean picked = km.id().equalsIgnoreCase(myVote);
            int pct = totalVotes > 0 ? (int) Math.round((votes * 100.0) / totalVotes) : 0;
            ItemStack item = button(
                    km.icon(),
                    (picked ? "<green><bold>✔ " : "<yellow><bold>") + km.displayName() + (picked ? "</bold></green>" : "</bold></yellow>"),
                    List.of(
                            "<gray>Votes:</gray> <green><bold>" + votes + "</bold></green> <gray>(" + pct + "%)</gray>",
                            progressBarMini(pct),
                            "",
                            picked ? "<green><bold>★ YOUR CURRENT VOTE ★</bold></green>" : "<aqua>Click to vote for this kit!</aqua>"
                    ),
                    "kit-vote",
                    km.id()
            );
            if (picked) setGlint(item);
            inv.setItem(slots[i], item);
        }
        inv.setItem(31, button(Material.CLOCK, "<gold><bold>Kit Vote Timer</bold></gold>",
                List.of("<yellow>" + FormatUtil.formatDuration(events.kitVoteRemaining()) + " left</yellow>",
                        "<gray>Total Votes: <green>" + totalVotes + "</green></gray>"), "noop", ""));
        player.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Tournament Squad Manager GUI (54 slots — Player 1-Click Teammate Invites!)
    // -------------------------------------------------------------------------

    public void openSquadManagerGui(Player viewer) {
        if ((!events.isEventOpen() && !events.isEventRunning()) || events.eventMode() != EventMode.TOURNAMENT) {
            viewer.sendMessage(FormatUtil.mm("<red>No tournament is currently active.</red>"));
            return;
        }
        TournamentSquadManager sqMgr = events.squads();
        TournamentSquadManager.TournamentTeam myTeam = sqMgr.teamOf(viewer.getUniqueId());
        if (myTeam == null) {
            viewer.sendMessage(FormatUtil.mm("<red>Join the tournament first with <yellow>/event join</yellow>!</red>"));
            return;
        }
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.SQUAD_MANAGER);
        Inventory inv = Bukkit.createInventory(holder, 54,
                FormatUtil.mm("<dark_gray><bold>Squad Manager</bold></dark_gray> <gray>|</gray> <gold>" + FormatUtil.escape(myTeam.name()) + "</gold>"));
        holder.setInventory(inv);
        fillBorder(inv, 54);

        boolean isLeader = viewer.getUniqueId().equals(myTeam.leader());
        List<String> myLore = new ArrayList<>();
        myLore.add("<gray>Format:</gray> <aqua>" + sqMgr.formatLabel() + " Tournament</aqua>");
        myLore.add("<gray>Size:</gray> <green>" + myTeam.size() + "/" + sqMgr.teamSize() + "</green>");
        myLore.add("<gray>Members:</gray>");
        for (UUID m : myTeam.members()) {
            String n = safeName(m);
            myLore.add("  " + (m.equals(myTeam.leader()) ? "<gold>★ " : "<white>• ") + FormatUtil.escape(n) + (m.equals(myTeam.leader()) ? " (Leader)</gold>" : "</white>"));
        }
        myLore.add("");
        myLore.add("<yellow>Rename:</yellow> <gray>/event squad name <TeamName></gray>");
        myLore.add("<yellow>Squad Chat:</yellow> <gray>@message or /event tc <msg></gray>");
        inv.setItem(4, button(Material.TOTEM_OF_UNDYING, "<gold><bold>Squad #" + myTeam.id() + " — " + FormatUtil.escape(myTeam.name()) + "</bold></gold>", myLore, "noop", ""));

        // Row 1 controls
        if (myTeam.size() > 1 && events.isEventOpen() && !sqMgr.isInvitesLocked()) {
            inv.setItem(47, button(Material.RED_WOOL, "<red><bold>Leave Squad</bold></red>",
                    List.of("<gray>Leave this squad and become solo</gray>", "<gray>(you stay registered in the tournament).</gray>"), "squad-leave", ""));
        }
        inv.setItem(49, button(Material.FILLED_MAP, "<aqua><bold>Live Teams & Bracket</bold></aqua>",
                List.of("<gray>View all " + sqMgr.allTeams().size() + " registered squads & matchups!</gray>"), "open-bracket", ""));
        inv.setItem(51, button(Material.CLOCK, "<yellow><bold>Refresh Roster</bold></yellow>",
                List.of("<gray>Reload available players to invite.</gray>"), "open-squad", ""));

        // Interior: Show current teammates first (kickable by leader), then solo players (1-click invite!)
        int slot = 10;
        for (UUID m : myTeam.members()) {
            if (m.equals(viewer.getUniqueId())) continue;
            slot = nextInteriorSlot(slot, 44);
            if (slot < 0) break;
            Player mp = Bukkit.getPlayer(m);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                if (mp != null) meta.setOwningPlayer(mp);
                String n = safeName(m);
                meta.displayName(FormatUtil.noItalic(FormatUtil.mm("<green><bold>★ Teammate: " + FormatUtil.escape(n) + "</bold></green>")));
                List<Component> lore = new ArrayList<>();
                lore.add(FormatUtil.noItalic(FormatUtil.mm("<gray>In your squad (" + FormatUtil.escape(myTeam.name()) + ")</gray>")));
                if (isLeader && events.isEventOpen() && !sqMgr.isInvitesLocked()) {
                    lore.add(FormatUtil.noItalic(FormatUtil.mm("<red>Click to kick from squad</red>")));
                    meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "squad-kick");
                    meta.getPersistentDataContainer().set(valueKey, PersistentDataType.STRING, n);
                } else {
                    meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "noop");
                }
                meta.lore(lore);
                head.setItemMeta(meta);
            }
            inv.setItem(slot++, head);
        }

        if (events.isEventOpen() && sqMgr.isTeamMode() && isLeader && myTeam.size() < sqMgr.teamSize() && !sqMgr.isInvitesLocked()) {
            for (UUID pid : events.participants()) {
                if (myTeam.contains(pid)) continue;
                TournamentSquadManager.TournamentTeam other = sqMgr.teamOf(pid);
                if (other == null || other.size() > 1) continue; // Only show available solo players!
                Player candidate = Bukkit.getPlayer(pid);
                if (candidate == null) continue;
                slot = nextInteriorSlot(slot, 44);
                if (slot < 0) break;
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                if (head.getItemMeta() instanceof SkullMeta meta) {
                    meta.setOwningPlayer(candidate);
                    meta.displayName(FormatUtil.noItalic(FormatUtil.mm("<yellow><bold>" + FormatUtil.escape(candidate.getName()) + "</bold></yellow> <gray>(Solo)</gray>")));
                    meta.lore(List.of(
                            FormatUtil.noItalic(FormatUtil.mm("<gray>Available to join your squad!</gray>")),
                            FormatUtil.noItalic(FormatUtil.mm("<green>Click to send Squad Invite!</green>"))
                    ));
                    meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "squad-invite");
                    meta.getPersistentDataContainer().set(valueKey, PersistentDataType.STRING, candidate.getName());
                    head.setItemMeta(meta);
                }
                inv.setItem(slot++, head);
            }
        } else if (myTeam.size() >= sqMgr.teamSize() && slot == 10) {
            inv.setItem(22, button(Material.LIME_CONCRETE, "<green><bold>Squad Full & Ready!</bold></green>",
                    List.of("<gray>Your squad has " + myTeam.size() + "/" + sqMgr.teamSize() + " players.</gray>"), "noop", ""));
        }
        viewer.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Live Tournament Bracket & Registered Squads Viewer GUI (54 slots)
    // -------------------------------------------------------------------------

    public void openBracketViewerGui(Player viewer) {
        if ((!events.isEventOpen() && !events.isEventRunning() && !events.isCelebrating()) || events.eventMode() != EventMode.TOURNAMENT) {
            viewer.sendMessage(FormatUtil.mm("<red>No tournament is currently active.</red>"));
            return;
        }
        TournamentSquadManager sqMgr = events.squads();
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.BRACKET_VIEWER);
        Inventory inv = Bukkit.createInventory(holder, 54,
                FormatUtil.mm("<dark_gray><bold>🏆 " + sqMgr.formatLabel().toUpperCase(Locale.ROOT) + " Tournament Bracket</bold></dark_gray>"));
        holder.setInventory(inv);
        fillBorder(inv, 54);

        // Top Center Summary / Live Match Banner
        TournamentSquadManager.ActiveMatchup cur = sqMgr.currentMatchup();
        if (cur != null) {
            inv.setItem(4, button(Material.NETHER_STAR,
                    "<red><bold>⚔ LIVE: " + FormatUtil.escape(cur.teamA().name()) + " vs " + FormatUtil.escape(cur.teamB().name()) + "</bold></red>",
                    List.of(
                            "<gold>" + cur.seriesBadge() + "</gold>",
                            "<gray>Format:</gray> <aqua>" + sqMgr.formatLabel() + " • Kit: " + FormatUtil.escape(events.selectedKit()) + "</aqua>",
                            "<gray>Upcoming Matches:</gray> <yellow>" + sqMgr.upcomingMatchups().size() + "</yellow>",
                            "<gray>Advanced This Round:</gray> <green>" + sqMgr.roundWinners().size() + "</green>"
                    ), "noop", ""));
        } else {
            inv.setItem(4, button(Material.GOLDEN_HELMET,
                    "<gold><bold>" + sqMgr.formatLabel().toUpperCase(Locale.ROOT) + " Tournament — " + (events.isEventOpen() ? "Registration" : "Round " + sqMgr.currentRound()) + "</bold></gold>",
                    List.of(
                            "<gray>Kit:</gray> <aqua>" + FormatUtil.escape(events.selectedKit()) + "</aqua>",
                            "<gray>Total Squads:</gray> <yellow>" + sqMgr.allTeams().size() + "</yellow> <gray>(" + sqMgr.readyTeamCount() + " full)</gray>",
                            "<gray>Grand Finals:</gray> <gold>" + (sqMgr.isBo3Finals() ? "Best-of-3" : "Best-of-1") + "</gold>"
                    ), "noop", ""));
        }

        // Interior: All Registered Squads & their Bracket Status!
        int slot = 10;
        for (TournamentSquadManager.TournamentTeam team : sqMgr.allTeams()) {
            slot = nextInteriorSlot(slot, 44);
            if (slot < 0) break;
            boolean isChamp = sqMgr.championTeam() != null && sqMgr.championTeam().id() == team.id();
            boolean isFighting = cur != null && (cur.teamA().id() == team.id() || cur.teamB().id() == team.id());
            Material mat = isChamp ? Material.GOLD_BLOCK
                    : isFighting ? Material.DIAMOND_SWORD
                    : team.isEliminated() ? Material.RED_STAINED_GLASS
                    : team.size() >= sqMgr.teamSize() ? Material.LIME_CONCRETE
                    : Material.YELLOW_CONCRETE;
            String stateTag = isChamp ? "<gold><bold>🏆 CHAMPION</bold></gold>"
                    : isFighting ? "<red><bold>⚔ FIGHTING NOW</bold></red>"
                    : team.isEliminated() ? "<red>✖ Eliminated</red>"
                    : team.size() >= sqMgr.teamSize() ? "<green>✔ Ready (" + team.size() + "/" + sqMgr.teamSize() + ")</green>"
                    : "<yellow>⏳ Forming (" + team.size() + "/" + sqMgr.teamSize() + ")</yellow>";
            List<String> lore = new ArrayList<>();
            lore.add(stateTag);
            lore.add("<gray>Roster:</gray>");
            for (UUID m : team.members()) {
                lore.add("  " + (m.equals(team.leader()) ? "<gold>★ " : "<white>• ") + FormatUtil.escape(safeName(m)) + (m.equals(team.leader()) ? "</gold>" : "</white>"));
            }
            ItemStack card = button(mat, "<gold>#" + team.id() + "</gold> <yellow><bold>" + FormatUtil.escape(team.name()) + "</bold></yellow>", lore, "noop", "");
            if (isChamp || isFighting) setGlint(card);
            inv.setItem(slot++, card);
        }

        if (events.isEventOpen() && sqMgr.isTeamMode() && events.participants().contains(viewer.getUniqueId())) {
            inv.setItem(47, button(Material.TOTEM_OF_UNDYING, "<gold><bold>Manage My Squad</bold></gold>",
                    List.of("<gray>Invite friends or leave your squad</gray>"), "open-squad", ""));
        }
        if (viewer.hasPermission("clasherevents.admin")) {
            inv.setItem(49, button(Material.COMMAND_BLOCK, "<red><bold>Admin Tournament Controls</bold></red>",
                    List.of("<gray>Shuffle, Lock, Disband, Kit Vote & Bo3</gray>"), "open-admin-tourney", ""));
        }
        inv.setItem(51, button(Material.CLOCK, "<yellow><bold>Refresh Bracket</bold></yellow>", List.of(), "open-bracket", ""));
        viewer.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Admin Tournament Control Panel GUI (54 slots)
    // -------------------------------------------------------------------------

    public void openAdminTournamentGui(Player admin) {
        TournamentSquadManager sqMgr = events.squads();
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.ADMIN_TOURNAMENT);
        Inventory inv = Bukkit.createInventory(holder, 54,
                FormatUtil.mm("<dark_gray><bold>Admin</bold></dark_gray> <gray>|</gray> <gold>Tournament Controls (" + sqMgr.formatLabel() + ")</gold>"));
        holder.setInventory(inv);
        fillBorder(inv, 54);

        boolean activeTourney = events.eventMode() == EventMode.TOURNAMENT && (events.isEventOpen() || events.isEventRunning());
        inv.setItem(10, button(Material.ENDER_EYE, "<aqua><bold>Auto-Shuffle Balanced Squads</bold></aqua>",
                List.of("<gray>Randomly packs all registered players into</gray>", "<gray>even " + sqMgr.formatLabel() + " squads!</gray>"), "tourney-shuffle", ""));
        inv.setItem(11, button(sqMgr.isInvitesLocked() ? Material.REDSTONE_TORCH : Material.LEVER,
                sqMgr.isInvitesLocked() ? "<red><bold>Squads: LOCKED</bold></red>" : "<green><bold>Squads: UNLOCKED</bold></green>",
                List.of("<gray>Click to toggle player squad invites/leaves.</gray>"), "tourney-toggle-lock", ""));
        inv.setItem(12, button(Material.GOLDEN_APPLE,
                "<gold><bold>Grand Finals: " + (sqMgr.isBo3Finals() ? "Best-of-3" : "Best-of-1") + "</bold></gold>",
                List.of("<gray>Click to toggle Best-of-3 vs Best-of-1 Finals!</gray>"), "tourney-toggle-bo3", ""));
        inv.setItem(13, button(Material.BELL, "<yellow><bold>Start Kit Vote GUI (30s)</bold></yellow>",
                List.of("<gray>Let players in the Tournament Hall vote</gray>", "<gray>for the PvP kit in an interactive GUI!</gray>"), "tourney-kitvote", ""));
        inv.setItem(14, button(Material.FILLED_MAP, "<aqua><bold>Open Live Bracket</bold></aqua>",
                List.of("<gray>View live matchups & all squads.</gray>"), "open-bracket", ""));
        inv.setItem(15, button(Material.LIME_CONCRETE, "<green><bold>Start Bracket Now</bold></green>",
                List.of("<gray>Auto-fills solos & starts Round 1 immediately!</gray>"), "tourney-start-now", ""));
        inv.setItem(16, button(Material.COMPASS, "<gold><bold>New Tournament Setup</bold></gold>",
                List.of("<gray>Pick 1v1 / 2v2 / 3v3 / 4v4 & Kit</gray>"), "pick-tourney-size", ""));

        // Rows 3-4: All registered squads (click any multi-player squad to disband!)
        int slot = 19;
        if (activeTourney) {
            for (TournamentSquadManager.TournamentTeam team : sqMgr.allTeams()) {
                slot = nextInteriorSlot(slot, 44);
                if (slot < 0) break;
                List<String> lore = new ArrayList<>();
                lore.add("<gray>Size:</gray> <yellow>" + team.size() + "/" + sqMgr.teamSize() + "</yellow>");
                for (UUID m : team.members()) {
                    lore.add("  <white>• " + FormatUtil.escape(safeName(m)) + "</white>");
                }
                if (events.isEventOpen() && team.size() > 1) {
                    lore.add("");
                    lore.add("<red>Click to Disband Squad</red>");
                }
                inv.setItem(slot++, button(
                        team.size() >= sqMgr.teamSize() ? Material.LIME_BANNER : Material.YELLOW_BANNER,
                        "<gold>#" + team.id() + "</gold> <yellow><bold>" + FormatUtil.escape(team.name()) + "</bold></yellow>",
                        lore,
                        (events.isEventOpen() && team.size() > 1) ? "tourney-disband" : "noop",
                        String.valueOf(team.id())
                ));
            }
        } else {
            inv.setItem(31, button(Material.PAPER, "<gray>No Active Tournament</gray>",
                    List.of("<yellow>Click 'New Tournament Setup' above to launch one!</yellow>"), "pick-tourney-size", ""));
        }
        inv.setItem(47, button(Material.POWERED_RAIL,
                sqMgr.isParallelRounds() ? "<aqua><bold>Parallel Rounds: ON</bold></aqua>" : "<yellow><bold>Parallel Rounds: OFF</bold></yellow>",
                List.of("<gray>When ON, early rounds with 4+ teams run</gray>", "<gray>simultaneously across multiple arenas!</gray>"), "tourney-toggle-parallel", ""));
        inv.setItem(49, button(Material.ARROW, "<yellow>Back to Control Panel</yellow>", List.of(), "open-admin", ""));
        admin.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Multi-Tab Leaderboard GUI (45 slots — Elo / Wins / Kills / Audience Points)
    // -------------------------------------------------------------------------

    public void openLeaderboardGui(Player viewer, String tabRaw) {
        String tab = tabRaw == null ? "elo" : tabRaw.toLowerCase(Locale.ROOT);
        if (!List.of("elo", "wins", "kills", "points").contains(tab)) tab = "elo";

        String titleLabel = switch (tab) {
            case "wins" -> "Top Wins";
            case "kills" -> "Top Kills";
            case "points" -> "Audience Points";
            default -> "Top Elo";
        };
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.LEADERBOARD);
        Inventory inv = Bukkit.createInventory(holder, 45, FormatUtil.mm("<dark_gray><bold>Leaderboard</bold></dark_gray> <gray>|</gray> <gold>" + titleLabel + "</gold>"));
        holder.setInventory(inv);
        fillBorder(inv, 45);

        List<DataStore.LeaderboardEntry> entries = switch (tab) {
            case "wins" -> events.data().topWins(14);
            case "kills" -> events.data().topKills(14);
            case "points" -> events.data().topAudiencePoints(14);
            default -> events.data().topElo(14);
        };

        int slot = 10;
        for (int i = 0; i < entries.size(); i++) {
            slot = nextInteriorSlot(slot, 26);
            if (slot < 0) break;
            DataStore.LeaderboardEntry e = entries.get(i);
            Material mat = i == 0 ? Material.GOLD_BLOCK : i == 1 ? Material.IRON_BLOCK : i == 2 ? Material.COPPER_BLOCK : Material.PAPER;
            String valueLine = switch (tab) {
                case "wins" -> "<yellow>" + e.value() + " Wins</yellow> <gray>(Elo " + e.secondary() + ")</gray>";
                case "kills" -> "<red>" + e.value() + " Kills</red> <gray>(" + e.secondary() + " Deaths)</gray>";
                case "points" -> "<aqua>" + e.value() + " Audience Points</aqua>";
                default -> "<gold>" + e.value() + " Elo</gold> <gray>(" + e.secondary() + " Wins)</gray>";
            };
            inv.setItem(slot++, button(
                    mat,
                    "<gold><bold>#" + (i + 1) + "</bold></gold> <white>" + FormatUtil.escape(e.name()) + "</white>",
                    List.of(valueLine),
                    "noop",
                    ""
            ));
        }

        if (entries.isEmpty()) {
            inv.setItem(22, button(Material.BARRIER, "<gray>No leaderboard data yet</gray>", List.of("<dark_gray>Play an event to rank up!</dark_gray>"), "noop", ""));
        }

        // Bottom category tabs
        inv.setItem(38, button(Material.NETHER_STAR, (tab.equals("elo") ? "<green><bold>● " : "<yellow>") + "Top Elo" + (tab.equals("elo") ? "</bold></green>" : "</yellow>"), List.of("<gray>Click to sort by Elo rating</gray>"), "lb-tab", "elo"));
        inv.setItem(39, button(Material.GOLDEN_HELMET, (tab.equals("wins") ? "<green><bold>● " : "<yellow>") + "Top Wins" + (tab.equals("wins") ? "</bold></green>" : "</yellow>"), List.of("<gray>Click to sort by Event Wins</gray>"), "lb-tab", "wins"));
        inv.setItem(41, button(Material.DIAMOND_SWORD, (tab.equals("kills") ? "<green><bold>● " : "<yellow>") + "Top Kills" + (tab.equals("kills") ? "</bold></green>" : "</yellow>"), List.of("<gray>Click to sort by PvP Kills</gray>"), "lb-tab", "kills"));
        inv.setItem(42, button(Material.GOLD_INGOT, (tab.equals("points") ? "<green><bold>● " : "<yellow>") + "Audience Points" + (tab.equals("points") ? "</bold></green>" : "</yellow>"), List.of("<gray>Click to sort by Prediction Points</gray>"), "lb-tab", "points"));
        viewer.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Countdown Selector GUI (27 slots)
    // -------------------------------------------------------------------------

    public void openCountdownGui(Player admin) {
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.COUNTDOWN);
        Inventory inv = Bukkit.createInventory(holder, 27, FormatUtil.mm("<dark_gray><bold>Event Countdown</bold></dark_gray>"));
        holder.setInventory(inv);
        fillBorder(inv, 27);

        int[] slots = {11, 13, 15};
        int[] minutes = {2, 5, 10};
        for (int i = 0; i < minutes.length; i++) {
            inv.setItem(slots[i], button(
                    Material.CLOCK,
                    "<yellow><bold>" + minutes[i] + " Minutes</bold></yellow>",
                    List.of(
                            "<gray>Sets registration countdown to " + (minutes[i] * 60) + "s.</gray>",
                            "",
                            "<green>Click to apply</green>"
                    ),
                    "set-countdown",
                    String.valueOf(minutes[i] * 60)
            ));
        }
        inv.setItem(22, button(Material.ARROW, "<yellow>Back</yellow>", List.of("<gray>Return to Control Panel</gray>"), "open-admin", ""));
        admin.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Kit Preview GUI (54 slots — includes real enchants & potions)
    // -------------------------------------------------------------------------

    public void openKitPreview(Player viewer, String kitId) {
        YamlConfiguration kit = kits.get(kitId);
        if (kit == null) {
            viewer.sendMessage(FormatUtil.mm("<red>Unknown kit: " + FormatUtil.escape(kitId) + "</red>"));
            return;
        }
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.KIT_PREVIEW);
        Inventory inv = Bukkit.createInventory(holder, 54, FormatUtil.mm("<dark_gray>Kit Preview:</dark_gray> <gold>" + FormatUtil.escape(kitId) + "</gold>"));
        holder.setInventory(inv);

        ItemStack[] armor = kits.buildArmor(kit);
        for (int i = 0; i < 4; i++) {
            if (armor[i] != null) inv.setItem(i, armor[i]);
        }
        ItemStack offhand = kits.buildOffhand(kit);
        if (offhand != null) inv.setItem(4, offhand);
        ItemStack divider = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int s = (offhand != null ? 5 : 4); s < 9; s++) inv.setItem(s, divider);

        for (var entry : kits.buildItems(kit).entrySet()) {
            int slot = Math.min(53, 9 + Math.max(0, Math.min(35, entry.getKey())));
            inv.setItem(slot, entry.getValue());
        }
        viewer.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Personal Kit Layout Editor GUI (54 slots — click-to-swap, zero dupe risk!)
    // -------------------------------------------------------------------------

    public void openKitLayoutEditor(Player viewer, String kitId) {
        YamlConfiguration kit = kits.get(kitId);
        if (kit == null) {
            viewer.sendMessage(FormatUtil.mm("<red>Unknown kit: " + FormatUtil.escape(kitId) + "</red>"));
            return;
        }
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.KIT_EDITOR);
        Inventory inv = Bukkit.createInventory(holder, 54,
                FormatUtil.mm("<dark_gray>Kit Editor:</dark_gray> <gold><bold>" + FormatUtil.escape(kitId) + "</bold></gold>"));
        holder.setInventory(inv);

        // Top bar: Armor (0..3), Offhand (4), Instructions (5), Reset (6), Save (7), Close (8)
        ItemStack[] armor = kits.buildArmor(kit);
        for (int i = 0; i < 4; i++) {
            if (armor[i] != null) inv.setItem(i, armor[i]);
        }
        inv.setItem(5, button(Material.BOOK, "<aqua><bold>How To Customize</bold></aqua>", List.of(
                "<gray>1. Click any item slot to pick it up</gray>",
                "<gray>2. Click a 2nd slot to <yellow>SWAP</yellow> them</gray>",
                "<gray>3. Click <green>Save Layout</green> to lock it in!</gray>",
                "",
                "<yellow>Slot 5 (top):</yellow> <gray>Offhand</gray>",
                "<yellow>Row 2:</yellow> <gray>Hotbar (1-9)</gray>",
                "<yellow>Rows 3-5:</yellow> <gray>Main Inventory</gray>"
        ), "noop", ""));
        inv.setItem(6, button(Material.RED_DYE, "<red><bold>Reset Default</bold></red>",
                List.of("<gray>Restore default slot order for this kit.</gray>"), "kit-reset", kitId));
        inv.setItem(7, button(Material.LIME_WOOL, "<green><bold>Save Layout</bold></green>",
                List.of("<gray>Save your custom hotbar & offhand order!</gray>"), "kit-save", kitId));
        inv.setItem(8, button(Material.BARRIER, "<gray>Close</gray>", List.of(), "close", ""));

        // Tag each item with its original default slot, then place using player's saved custom layout
        Map<Integer, ItemStack> rawDefaults = kits.buildAllInventorySlots(kit);
        Map<Integer, ItemStack> tagged = new LinkedHashMap<>();
        for (Map.Entry<Integer, ItemStack> e : rawDefaults.entrySet()) {
            ItemStack copy = e.getValue().clone();
            ItemMeta meta = copy.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(origSlotKey, PersistentDataType.INTEGER, e.getKey());
                copy.setItemMeta(meta);
            }
            tagged.put(e.getKey(), copy);
        }

        Map<Integer, Integer> saved = events.data().kitLayout(viewer.getUniqueId(), kitId);
        Map<Integer, ItemStack> arranged = KitManager.applyCustomLayout(tagged, saved);
        for (Map.Entry<Integer, ItemStack> e : arranged.entrySet()) {
            int invSlot = e.getKey();
            int guiSlot = (invSlot == KitManager.OFFHAND_SLOT) ? 4 : (9 + invSlot);
            if (guiSlot == 4 || (guiSlot >= 9 && guiSlot <= 44)) {
                inv.setItem(guiSlot, e.getValue());
            }
        }
        ItemStack bottomPane = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int s = 45; s < 54; s++) inv.setItem(s, bottomPane);

        editorSessions.put(viewer.getUniqueId(), new EditorSession(kitId));
        viewer.openInventory(inv);
    }

    private static boolean isEditableKitGuiSlot(int guiSlot) {
        return guiSlot == 4 || (guiSlot >= 9 && guiSlot <= 44);
    }

    private static int guiSlotToInvSlot(int guiSlot) {
        return guiSlot == 4 ? KitManager.OFFHAND_SLOT : (guiSlot - 9);
    }

    // -------------------------------------------------------------------------
    // Winner Prediction GUI (45 slots — supports up to 28 fighters)
    // -------------------------------------------------------------------------

    public void openVoteGui(Player viewer) {
        if (!events.isSpectator(viewer.getUniqueId())) {
            viewer.sendMessage(FormatUtil.mm("<red>Only eliminated event spectators can predict the winner.</red>"));
            return;
        }
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.VOTE_PREDICTION);
        Inventory inv = Bukkit.createInventory(holder, 45, FormatUtil.mm("<dark_gray><bold>Predict the Winner</bold></dark_gray>"));
        holder.setInventory(inv);
        fillBorder(inv, 45);

        int slot = 10;
        Map<UUID, Integer> crowdVotes = events.predictionVoteCounts();
        UUID myPick = events.playerPredictionVote(viewer.getUniqueId());
        for (UUID id : events.alive()) {
            Player fighter = Bukkit.getPlayer(id);
            if (fighter == null) continue;
            slot = nextInteriorSlot(slot, 35);
            if (slot < 0) break;

            boolean isMine = id.equals(myPick);
            int voteCount = crowdVotes.getOrDefault(id, 0);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(fighter);
                meta.displayName(FormatUtil.noItalic(FormatUtil.mm((isMine ? "<green><bold>✔ " : "<yellow><bold>")
                        + FormatUtil.escape(fighter.getName()) + (isMine ? "</bold></green>" : "</bold></yellow>"))));
                meta.lore(List.of(
                        FormatUtil.noItalic(FormatUtil.mm("<gray>Crowd Votes:</gray> <aqua><bold>" + voteCount + "</bold></aqua>")),
                        FormatUtil.noItalic(FormatUtil.mm(isMine ? "<green><bold>★ YOUR PREDICTION ★</bold></green>" : "<gray>Click to predict this fighter as the winner!</gray>")),
                        FormatUtil.noItalic(FormatUtil.mm("<green>Correct predictions earn audience points.</green>"))
                ));
                if (isMine) meta.setEnchantmentGlintOverride(true);
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "vote");
                meta.getPersistentDataContainer().set(valueKey, PersistentDataType.STRING, fighter.getName());
                head.setItemMeta(meta);
            }
            inv.setItem(slot++, head);
        }
        viewer.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Fighter Spectator Selector GUI (45 slots — supports up to 28 fighters)
    // -------------------------------------------------------------------------

    public void openSpectateGui(Player viewer) {
        List<Player> targets = new ArrayList<>();
        for (UUID id : events.alive()) {
            Player target = Bukkit.getPlayer(id);
            if (target != null && !target.equals(viewer)) targets.add(target);
        }
        if (targets.isEmpty()) {
            viewer.sendMessage(FormatUtil.mm("<red>No active fighters to spectate.</red>"));
            return;
        }
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.SPECTATE_FIGHTER);
        Inventory inv = Bukkit.createInventory(holder, 45, FormatUtil.mm("<dark_gray><bold>Spectate Fighter</bold></dark_gray>"));
        holder.setInventory(inv);
        fillBorder(inv, 45);

        int slot = 10;
        for (Player target : targets) {
            slot = nextInteriorSlot(slot, 35);
            if (slot < 0) break;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(target);
                meta.displayName(FormatUtil.noItalic(FormatUtil.mm("<yellow><bold>" + FormatUtil.escape(target.getName()) + "</bold></yellow>")));
                meta.lore(List.of(
                        FormatUtil.noItalic(FormatUtil.mm("<gray>Health:</gray> <red>" + (int) Math.ceil(target.getHealth()) + " ❤</red>")),
                        FormatUtil.noItalic(FormatUtil.mm("<green>Click to spectate</green>"))
                ));
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "spectate");
                meta.getPersistentDataContainer().set(valueKey, PersistentDataType.STRING, target.getName());
                head.setItemMeta(meta);
            }
            inv.setItem(slot++, head);
        }
        viewer.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Click & drag handlers
    // -------------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EventMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (holder.viewType() == EventMenuHolder.ViewType.KIT_PREVIEW) return;
        if (holder.viewType() == EventMenuHolder.ViewType.POST_DUEL_INV) {
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem != null && clickedItem.hasItemMeta()
                    && "close".equals(clickedItem.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING))) {
                player.closeInventory();
            }
            return;
        }
        if (holder.viewType() == EventMenuHolder.ViewType.KIT_EDITOR) {
            handleKitEditorClick(player, event.getView().getTopInventory(), event.getSlot());
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.isEmpty() || action.equals("noop")) return;
        String value = meta.getPersistentDataContainer().getOrDefault(valueKey, PersistentDataType.STRING, "");

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        switch (action) {
            case "create" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                player.closeInventory();
                events.createEvent(value);
            }
            case "pick-tourney-size" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                openTournamentSizePicker(player);
            }
            case "pick-tourney-kit" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                try {
                    openTournamentKitPicker(player, Integer.parseInt(value));
                } catch (NumberFormatException ignored) {}
            }
            case "create-tourney" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                String[] parts = value.split(":", 2);
                player.closeInventory();
                if (parts.length == 2) {
                    try {
                        events.createEvent("tournament", parts[1], Integer.parseInt(parts[0]));
                    } catch (NumberFormatException ignored) {}
                }
            }
            case "open-admin-tourney" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                openAdminTournamentGui(player);
            }
            case "open-squad" -> openSquadManagerGui(player);
            case "open-bracket" -> openBracketViewerGui(player);
            case "squad-invite" -> {
                events.handleSquadInvite(player, value);
                openSquadManagerGui(player);
            }
            case "squad-kick" -> {
                events.handleSquadKick(player, value);
                openSquadManagerGui(player);
            }
            case "squad-leave" -> {
                events.handleSquadLeave(player);
                openSquadManagerGui(player);
            }
            case "tourney-shuffle" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                events.adminShuffleSquads(player);
                openAdminTournamentGui(player);
            }
            case "tourney-toggle-lock" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                events.adminSetInvitesLocked(player, !events.squads().isInvitesLocked());
                openAdminTournamentGui(player);
            }
            case "tourney-toggle-bo3" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                events.adminSetBo3Finals(player, !events.squads().isBo3Finals());
                openAdminTournamentGui(player);
            }
            case "tourney-toggle-parallel" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                events.adminSetParallelRounds(player, !events.squads().isParallelRounds());
                openAdminTournamentGui(player);
            }
            case "open-modifiers" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                openEventModifiersGui(player);
            }
            case "toggle-modifier" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                EventModifier.fromInput(value).ifPresent(m -> {
                    boolean nowOn = events.toggleModifier(m);
                    player.sendMessage(FormatUtil.mm("<light_purple>Modifier <gold>" + m.displayName() + "</gold>: " + (nowOn ? "<green>ON</green>" : "<red>OFF</red>") + "</light_purple>"));
                });
                openEventModifiersGui(player);
            }
            case "clear-modifiers" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                events.clearModifiers();
                player.sendMessage(FormatUtil.mm("<yellow>Cleared all active event modifiers.</yellow>"));
                openEventModifiersGui(player);
            }
            case "toggle-autocam" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                events.toggleAutoCam(player);
                openAdminGui(player);
            }
            case "queue-join" -> {
                player.closeInventory();
                if (events.casualDuels() != null) events.casualDuels().joinQueue(player, value);
            }
            case "queue-leave" -> {
                if (events.casualDuels() != null) events.casualDuels().leaveQueue(player);
                openDuelQueueGui(player);
            }
            case "join-ffa" -> {
                player.closeInventory();
                if (events.warmupFfa() != null) events.warmupFfa().joinFfa(player);
            }
            case "open-kiteditor-picker" -> openKitEditorSelectorGui(player);
            case "open-kit-editor" -> openKitLayoutEditor(player, value);
            case "close" -> player.closeInventory();
            case "tourney-kitvote" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                player.closeInventory();
                events.startTournamentKitVote(player, 30);
            }
            case "tourney-start-now" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                player.closeInventory();
                events.skipTimer(player, 0, true);
            }
            case "tourney-disband" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                try {
                    events.adminDisbandTeam(player, Integer.parseInt(value));
                } catch (NumberFormatException ignored) {}
                openAdminTournamentGui(player);
            }
            case "kit-vote" -> {
                events.castTournamentKitVote(player, value);
                openKitVoteGui(player);
            }
            case "pick-admin-kit" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                EventMode.fromInput(value).ifPresent(m -> openAdminKitPicker(player, m));
            }
            case "create-with-kit" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                String[] parts = value.split(":", 2);
                player.closeInventory();
                if (parts.length == 2) events.createEvent(parts[0], parts[1]);
                else events.createEvent(value);
            }
            case "casual-duel-send" -> {
                String[] parts = value.split(":", 2);
                player.closeInventory();
                if (parts.length == 2 && events.casualDuels() != null) {
                    Player target = Bukkit.getPlayerExact(parts[0]);
                    if (target == null) player.sendMessage(FormatUtil.mm("<red>That player is no longer online.</red>"));
                    else events.casualDuels().sendRequest(player, target, parts[1]);
                }
            }
            case "start-mode-vote" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                player.closeInventory();
                events.startModeVote(plugin.getConfig().getInt("mode-vote.duration-seconds", 45));
            }
            case "full-setup" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                player.closeInventory();
                arenas.runFullSetup(player, events::isArenaInUse, event.isShiftClick());
            }
            case "paste-all" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                player.closeInventory();
                arenas.pasteAllArenas(player, events::isArenaInUse);
            }
            case "countdown" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                openCountdownGui(player);
            }
            case "open-admin" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                openAdminGui(player);
            }
            case "set-countdown" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                try {
                    int seconds = Integer.parseInt(value);
                    if (events.isEventOpen()) {
                        events.setCountdownSeconds(seconds);
                        player.sendMessage(FormatUtil.mm("<green>Registration countdown set to " + (seconds / 60) + " minutes.</green>"));
                    } else {
                        player.sendMessage(FormatUtil.mm("<red>No registration is currently open.</red>"));
                    }
                } catch (NumberFormatException ignored) {}
                player.closeInventory();
            }
            case "toggle-pause" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                boolean next = !events.isPaused();
                events.setPaused(next);
                player.sendMessage(FormatUtil.mm(next ? "<yellow>Event countdown paused.</yellow>" : "<green>Event countdown resumed.</green>"));
                openAdminGui(player);
            }
            case "stop" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                player.closeInventory();
                events.stopEvent("Event stopped by admin");
            }
            case "skip-timer" -> {
                if (!player.hasPermission("clasherevents.admin")) return;
                player.closeInventory();
                events.skipTimer(player, null, false);
            }
            case "status" -> {
                player.sendMessage(FormatUtil.mm("<gold>Mode:</gold> <white>" + events.eventMode().displayName()
                        + "</white> <dark_gray>|</dark_gray> <gold>Phase:</gold> <yellow>" + events.phase()
                        + "</yellow> <dark_gray>|</dark_gray> <gold>Alive:</gold> <green>" + events.alive().size()
                        + "</green> <dark_gray>|</dark_gray> <gold>Registered:</gold> <green>" + events.participants().size() + "</green>"));
            }
            case "vote" -> {
                events.handleVote(player, value);
                player.closeInventory();
            }
            case "mode-vote" -> {
                events.castModeVote(player, value);
                openModeVoteGui(player);
            }
            case "lb-tab" -> openLeaderboardGui(player, value);
            case "spectate" -> {
                Player target = Bukkit.getPlayerExact(value);
                if (target != null && (events.isSpectator(player.getUniqueId()) || player.getGameMode() == GameMode.SPECTATOR || player.hasPermission("clasherevents.admin"))) {
                    player.teleport(target.getLocation().add(0.0, 2.5, 0.0));
                    player.sendMessage(FormatUtil.mm("<aqua>Spectating <yellow>" + FormatUtil.escape(target.getName()) + "</yellow>!</aqua>"));
                    player.closeInventory();
                }
            }
            default -> {}
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof EventMenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof EventMenuHolder holder
                && holder.viewType() == EventMenuHolder.ViewType.KIT_EDITOR) {
            editorSessions.remove(event.getPlayer().getUniqueId());
        }
    }

    private void handleKitEditorClick(Player player, Inventory top, int slot) {
        EditorSession session = editorSessions.get(player.getUniqueId());
        if (session == null) return;

        if (slot == 8) {
            player.closeInventory();
            return;
        }
        if (slot == 6) {
            events.data().clearKitLayout(player.getUniqueId(), session.kitId);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.9f);
            player.sendMessage(FormatUtil.mm("<yellow>Reset your custom layout for <gold>" + FormatUtil.escape(session.kitId) + "</gold> to defaults.</yellow>"));
            openKitLayoutEditor(player, session.kitId);
            return;
        }
        if (slot == 7) {
            Map<Integer, Integer> layout = new LinkedHashMap<>();
            for (int g = 4; g <= 44; g++) {
                if (!isEditableKitGuiSlot(g)) continue;
                ItemStack item = top.getItem(g);
                if (item == null || !item.hasItemMeta()) continue;
                Integer orig = item.getItemMeta().getPersistentDataContainer().get(origSlotKey, PersistentDataType.INTEGER);
                if (orig != null) {
                    layout.put(orig, guiSlotToInvSlot(g));
                }
            }
            events.data().saveKitLayout(player.getUniqueId(), session.kitId, layout);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f);
            player.sendMessage(FormatUtil.mm("<green><bold>Saved!</bold></green> <gray>Your custom <gold>" + FormatUtil.escape(session.kitId)
                    + "</gold> hotbar layout will be used automatically in events!</gray>"));
            player.closeInventory();
            return;
        }
        if (!isEditableKitGuiSlot(slot)) return;

        if (session.selectedGuiSlot < 0) {
            ItemStack picked = top.getItem(slot);
            if (picked == null || picked.getType().isAir()) return;
            session.selectedGuiSlot = slot;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            player.sendActionBar(FormatUtil.mm("<yellow>Selected slot — click a second slot to <green><bold>SWAP</bold></green>!</yellow>"));
        } else {
            int first = session.selectedGuiSlot;
            session.selectedGuiSlot = -1;
            if (first != slot) {
                ItemStack a = top.getItem(first);
                ItemStack b = top.getItem(slot);
                top.setItem(first, b);
                top.setItem(slot, a);
                player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.6f, 1.2f);
                player.sendActionBar(FormatUtil.mm("<green>Swapped slots! Click <bold>Save Layout</bold> when done.</green>"));
            }
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private static String progressBarMini(int percent) {
        int filled = Math.max(0, Math.min(10, (int) Math.round(percent / 10.0)));
        StringBuilder sb = new StringBuilder("<green>");
        for (int i = 0; i < filled; i++) sb.append("█");
        sb.append("</green><dark_gray>");
        for (int i = filled; i < 10; i++) sb.append("░");
        sb.append("</dark_gray>");
        return sb.toString();
    }

    private static void setGlint(ItemStack item) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
    }

    private static String safeName(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : id.toString().substring(0, 8);
    }

    private static int nextInteriorSlot(int slot, int maxExclusive) {
        while (slot < maxExclusive) {
            int col = slot % 9;
            if (col != 0 && col != 8) return slot;
            slot++;
        }
        return -1;
    }

    private void fillBorder(Inventory inv, int size) {
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE);
        ItemStack corner = pane(Material.ORANGE_STAINED_GLASS_PANE);
        int rows = size / 9;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int col = slot % 9;
            boolean border = row == 0 || row == rows - 1 || col == 0 || col == 8;
            if (!border) continue;
            boolean isCorner = (row == 0 || row == rows - 1) && (col == 0 || col == 8);
            inv.setItem(slot, isCorner ? corner : filler);
        }
    }

    private ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack button(Material material, String nameMini, List<String> loreMini, String action, String value) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(FormatUtil.noItalic(FormatUtil.mm(nameMini)));
            if (loreMini != null && !loreMini.isEmpty()) {
                List<Component> lore = new ArrayList<>(loreMini.size());
                for (String line : loreMini) {
                    lore.add(line.isEmpty() ? Component.empty() : FormatUtil.noItalic(FormatUtil.mm(line)));
                }
                meta.lore(lore);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            meta.getPersistentDataContainer().set(valueKey, PersistentDataType.STRING, value == null ? "" : value);
            item.setItemMeta(meta);
        }
        return item;
    }
}
