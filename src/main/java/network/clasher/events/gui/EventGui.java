package network.clasher.events.gui;

import net.kyori.adventure.text.Component;
import network.clasher.events.arena.ArenaManager;
import network.clasher.events.kit.KitManager;
import network.clasher.events.match.EventManager;
import network.clasher.events.model.EventMode;
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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Secure InventoryHolder-based GUIs for admin controls, countdown selection,
 * pre-event mode voting, multi-tab Elo/Wins/Kills leaderboards, kit previews,
 * winner predictions, and spectator target selection.
 */
public final class EventGui implements Listener {
    private final JavaPlugin plugin;
    private final ArenaManager arenas;
    private final EventManager events;
    private final KitManager kits;
    private final NamespacedKey actionKey;
    private final NamespacedKey valueKey;

    public EventGui(JavaPlugin plugin, ArenaManager arenas, EventManager events, KitManager kits) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.events = events;
        this.kits = kits;
        this.actionKey = new NamespacedKey(plugin, "ce-gui-action");
        this.valueKey = new NamespacedKey(plugin, "ce-gui-value");
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
            inv.setItem(modeSlots[i], button(
                    mode.icon(),
                    "<yellow><bold>" + mode.displayName() + "</bold></yellow>",
                    List.of(
                            "<gray>Mode ID:</gray> <white>" + mode.id() + "</white>",
                            "<gray>Arena:</gray> <white>" + (mode.defaultArenaId() == null ? "Random" : mode.defaultArenaId()) + "</white>",
                            "",
                            "<green>Click to open registration</green>"
                    ),
                    "create",
                    mode.id()
            ));
        }

        inv.setItem(29, button(
                Material.BELL,
                "<aqua><bold>Open Mode Vote</bold></aqua>",
                List.of("<gray>Let players vote on the next PvP mode!</gray>"),
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
                        "<gray>Left-Click: Paste Lobby, Podium & all 8 arenas</gray>",
                        "<yellow>Shift-Click: Refresh bundled v2.1 builds & paste all</yellow>"
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
        admin.openInventory(inv);
    }

    // -------------------------------------------------------------------------
    // Pre-Event Player Mode Voting GUI (36 slots)
    // -------------------------------------------------------------------------

    public void openModeVoteGui(Player player) {
        if (!events.isModeVoteOpen()) {
            player.sendMessage(FormatUtil.mm("<red>Mode voting is not currently open.</red>"));
            return;
        }
        EventMenuHolder holder = new EventMenuHolder(EventMenuHolder.ViewType.MODE_VOTE);
        Inventory inv = Bukkit.createInventory(holder, 36, FormatUtil.mm("<dark_gray><bold>Vote Next Event Mode</bold></dark_gray> <gray>(" + events.modeVoteRemaining() + "s)</gray>"));
        holder.setInventory(inv);
        fillBorder(inv, 36);

        Map<String, Integer> counts = events.modeVoteCounts();
        int slot = 10;
        for (EventMode mode : events.modeVoteOptions()) {
            slot = nextInteriorSlot(slot, 26);
            if (slot < 0) break;
            int votes = counts.getOrDefault(mode.id(), 0);
            inv.setItem(slot++, button(
                    mode.icon(),
                    "<yellow><bold>" + mode.displayName() + "</bold></yellow>",
                    List.of(
                            "<gray>Current Votes:</gray> <green><bold>" + votes + "</bold></green>",
                            "",
                            "<aqua>Click to vote for this mode!</aqua>"
                    ),
                    "mode-vote",
                    mode.id()
            ));
        }
        player.openInventory(inv);
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
        ItemStack divider = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int s = 4; s < 9; s++) inv.setItem(s, divider);

        for (var entry : kits.buildItems(kit).entrySet()) {
            int slot = Math.min(53, 9 + Math.max(0, Math.min(35, entry.getKey())));
            inv.setItem(slot, entry.getValue());
        }
        viewer.openInventory(inv);
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
        for (UUID id : events.alive()) {
            Player fighter = Bukkit.getPlayer(id);
            if (fighter == null) continue;
            slot = nextInteriorSlot(slot, 35);
            if (slot < 0) break;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(fighter);
                meta.displayName(FormatUtil.noItalic(FormatUtil.mm("<yellow><bold>" + FormatUtil.escape(fighter.getName()) + "</bold></yellow>")));
                meta.lore(List.of(
                        FormatUtil.noItalic(FormatUtil.mm("<gray>Click to predict this fighter as the winner!</gray>")),
                        FormatUtil.noItalic(FormatUtil.mm("<green>Correct predictions earn audience points.</green>"))
                ));
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
                if (target != null && player.getGameMode() == GameMode.SPECTATOR) {
                    player.setSpectatorTarget(target);
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

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

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
