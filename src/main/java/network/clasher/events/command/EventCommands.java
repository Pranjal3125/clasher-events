package network.clasher.events.command;

import network.clasher.events.ClasherEventsPlugin;
import network.clasher.events.arena.ArenaManager;
import network.clasher.events.gui.EventGui;
import network.clasher.events.kit.KitManager;
import network.clasher.events.match.EventManager;
import network.clasher.events.model.Arena;
import network.clasher.events.model.EventMode;
import network.clasher.events.storage.DataStore;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command executor and tab-completer for /ce, /event, and /latejoin.
 */
public final class EventCommands implements CommandExecutor, TabCompleter {
    private static final List<String> EVENT_SUBS = List.of(
            "join", "leave", "vote", "cheer", "team", "points", "stats", "top", "history", "spectate", "help"
    );
    private static final List<String> CE_SUBS = List.of(
            "help", "create", "start", "stop", "pause", "resume", "status", "reload",
            "gui", "countdown", "setlobby", "setspectator", "setwinner",
            "setspawn", "addspawn", "arena", "kit"
    );

    private final ClasherEventsPlugin plugin;
    private final ArenaManager arenas;
    private final KitManager kits;
    private final EventManager events;
    private final EventGui gui;

    public EventCommands(
            ClasherEventsPlugin plugin,
            ArenaManager arenas,
            KitManager kits,
            EventManager events,
            EventGui gui
    ) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.kits = kits;
        this.events = events;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("event")) {
            handlePlayerEvent(sender, args);
            return true;
        }
        if (name.equals("latejoin")) {
            if (!sender.hasPermission("clasherevents.admin")) {
                sender.sendMessage(FormatUtil.mm("<red>No permission.</red>"));
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/latejoin <playername></yellow>"));
                return true;
            }
            events.forceLateJoin(sender, args[0]);
            return true;
        }
        handleAdminCe(sender, args);
        return true;
    }

    private void handlePlayerEvent(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(FormatUtil.mm("<red>This command can only be used in-game.</red>"));
            return;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> events.joinEvent(player);
            case "leave" -> events.leaveEvent(player);
            case "vote" -> {
                if (args.length >= 2) events.handleVote(player, args[1]);
                else gui.openVoteGui(player);
            }
            case "cheer" -> {
                if (args.length >= 2) events.handleCheer(player, args[1]);
                else player.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/event cheer <player></yellow>"));
            }
            case "team" -> events.showTeamInfo(player);
            case "points" -> player.sendMessage(FormatUtil.mm("<gold>Audience points:</gold> <yellow>" + events.data().audiencePoints(player.getUniqueId()) + "</yellow>"));
            case "stats" -> {
                DataStore.PlayerStats s = events.data().stats(player.getUniqueId());
                int pts = events.data().audiencePoints(player.getUniqueId());
                player.sendMessage(FormatUtil.mm("<gold><bold>YOUR CLASHER STATS</bold></gold>"));
                player.sendMessage(FormatUtil.mm("<yellow>Wins:</yellow> <white>" + s.wins()
                        + "</white> <dark_gray>|</dark_gray> <yellow>Kills:</yellow> <white>" + s.kills()
                        + "</white> <dark_gray>|</dark_gray> <yellow>Deaths:</yellow> <white>" + s.deaths() + "</white>"));
                player.sendMessage(FormatUtil.mm("<yellow>Audience Points:</yellow> <white>" + pts + "</white>"));
            }
            case "top" -> {
                List<DataStore.LeaderboardEntry> top = events.data().topAudiencePoints(10);
                player.sendMessage(FormatUtil.mm("<gold><bold>Audience Points Leaderboard</bold></gold>"));
                if (top.isEmpty()) {
                    player.sendMessage(FormatUtil.mm("<gray>No audience points recorded yet.</gray>"));
                } else {
                    for (int i = 0; i < top.size(); i++) {
                        DataStore.LeaderboardEntry entry = top.get(i);
                        player.sendMessage(FormatUtil.mm("<gold>#" + (i + 1) + "</gold> <white>"
                                + FormatUtil.escape(entry.name()) + "</white> <dark_gray>—</dark_gray> <yellow>" + entry.points() + " pts</yellow>"));
                    }
                }
            }
            case "history" -> {
                List<DataStore.MatchSummary> recent = events.data().recentMatches(10);
                player.sendMessage(FormatUtil.mm("<gold><bold>Recent Event Matches</bold></gold>"));
                if (recent.isEmpty()) {
                    player.sendMessage(FormatUtil.mm("<gray>No completed matches recorded yet.</gray>"));
                } else {
                    for (DataStore.MatchSummary m : recent) {
                        player.sendMessage(FormatUtil.mm("<gold>#" + m.id() + "</gold> <white>" + FormatUtil.escape(m.mode())
                                + "</white> <dark_gray>|</dark_gray> <gray>Winner:</gray> <green>" + FormatUtil.escape(m.winner())
                                + "</green> <dark_gray>|</dark_gray> <gray>MVP:</gray> <yellow>" + FormatUtil.escape(m.mvp())
                                + " (" + m.mvpKills() + ")</yellow> <dark_gray>|</dark_gray> <gray>" + FormatUtil.formatDuration(m.durationSeconds()) + "</gray>"));
                    }
                }
            }
            case "spectate" -> {
                if (events.isSpectator(player.getUniqueId())) gui.openSpectateGui(player);
                else player.sendMessage(FormatUtil.mm("<red>Only eliminated event spectators can use this command.</red>"));
            }
            default -> {
                player.sendMessage(FormatUtil.mm("<gold><bold>Clasher Events Commands</bold></gold>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event join</yellow> <dark_gray>-</dark_gray> <gray>Join open event registration</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event leave</yellow> <dark_gray>-</dark_gray> <gray>Leave current event and restore inventory</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event vote [player]</yellow> <dark_gray>-</dark_gray> <gray>Predict the winner (spectators)</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event cheer <player></yellow> <dark_gray>-</dark_gray> <gray>Cheer for an active fighter (spectators)</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event team</yellow> <dark_gray>-</dark_gray> <gray>View your Team Wars squad</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event spectate</yellow> <dark_gray>-</dark_gray> <gray>Open fighter spectator selector</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event stats | points | top | history</yellow> <dark_gray>-</dark_gray> <gray>View stats and leaderboards</gray>"));
            }
        }
    }

    private void handleAdminCe(CommandSender sender, String[] args) {
        if (!sender.hasPermission("clasherevents.admin")) {
            sender.sendMessage(FormatUtil.mm("<red>No permission.</red>"));
            return;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> {
                sender.sendMessage(FormatUtil.mm("<gold><bold>Clasher Events Admin Commands</bold></gold>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce gui</yellow> <dark_gray>|</dark_gray> <yellow>/ce create <mode></yellow> <dark_gray>|</dark_gray> <yellow>/ce start</yellow> <dark_gray>|</dark_gray> <yellow>/ce stop</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce pause</yellow> <dark_gray>|</dark_gray> <yellow>/ce resume</yellow> <dark_gray>|</dark_gray> <yellow>/ce countdown</yellow> <dark_gray>|</dark_gray> <yellow>/ce status</yellow> <dark_gray>|</dark_gray> <yellow>/ce reload</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce setlobby</yellow> <dark_gray>|</dark_gray> <yellow>/ce setspectator</yellow> <dark_gray>|</dark_gray> <yellow>/ce setwinner</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce setspawn <mode|arena> [wave1|wave2|final|audience]</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce addspawn <mode|arena> [wave1|wave2|final|audience]</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce arena list|paste|reset|info|validate <arena></yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce kit list|give <kit> <player>|preview <kit> <player>|reload</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/latejoin <player></yellow> <dark_gray>-</dark_gray> <gray>Force-add an online player to an active match</gray>"));
            }
            case "create" -> {
                if (args.length < 2) sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce create <mode></yellow>"));
                else events.createEvent(args[1]);
            }
            case "start" -> events.startEvent();
            case "stop" -> events.stopEvent("Event stopped by admin");
            case "pause" -> {
                if (!events.isEventOpen() && !events.isEventRunning()) sender.sendMessage(FormatUtil.mm("<red>No active event.</red>"));
                else {
                    events.setPaused(true);
                    sender.sendMessage(FormatUtil.mm("<yellow>Event paused.</yellow> <gray>(Registration countdown paused; combat damage disabled.)</gray>"));
                }
            }
            case "resume" -> {
                if (!events.isPaused()) sender.sendMessage(FormatUtil.mm("<red>Event is not paused.</red>"));
                else {
                    events.setPaused(false);
                    sender.sendMessage(FormatUtil.mm("<green>Event resumed.</green>"));
                }
            }
            case "gui" -> {
                if (sender instanceof Player admin) gui.openAdminGui(admin);
                else sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
            }
            case "countdown" -> {
                if (sender instanceof Player admin) gui.openCountdownGui(admin);
                else sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
            }
            case "setlobby" -> {
                if (sender instanceof Player admin) events.setNamedLocation(admin, "lobby");
                else sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
            }
            case "setspectator" -> {
                if (sender instanceof Player admin) events.setNamedLocation(admin, "spectator");
                else sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
            }
            case "setwinner" -> {
                if (sender instanceof Player admin) events.setNamedLocation(admin, "winner");
                else sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
            }
            case "setspawn", "addspawn" -> {
                if (!(sender instanceof Player admin)) {
                    sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce " + sub + " <mode|arena> [wave1|wave2|final|audience]</yellow>"));
                    return;
                }
                String requested = args[1].toLowerCase(Locale.ROOT);
                if (requested.equals("random-kit")) {
                    sender.sendMessage(FormatUtil.mm("<yellow>Random Kit selects any arena — configure each arena separately (nethpot, axe-shield, build-uhc, sword, uhc, crystal, smp, diamond-pot).</yellow>"));
                    return;
                }
                String arenaId = EventMode.fromInput(requested)
                        .map(EventMode::defaultArenaId)
                        .orElse(arenas.exists(requested) ? requested : null);
                if (arenaId == null) {
                    sender.sendMessage(FormatUtil.mm("<red>Unknown mode/arena: " + FormatUtil.escape(requested) + ". Use /ce arena list.</red>"));
                    return;
                }
                arenas.setSpawn(admin, arenaId, args.length >= 3 ? args[2] : "wave1", sub.equals("addspawn"));
            }
            case "status" -> sender.sendMessage(FormatUtil.mm("<gold>Mode:</gold> <white>" + events.eventMode().displayName()
                    + "</white> <dark_gray>|</dark_gray> <gold>Arena:</gold> <white>" + FormatUtil.escape(events.selectedArena())
                    + "</white> <dark_gray>|</dark_gray> <gold>Phase:</gold> <yellow>" + events.phase()
                    + "</yellow> <dark_gray>|</dark_gray> <gold>Open:</gold> <white>" + events.isEventOpen()
                    + "</white> <dark_gray>|</dark_gray> <gold>Running:</gold> <white>" + events.isEventRunning()
                    + "</white> <dark_gray>|</dark_gray> <gold>Players:</gold> <green>" + events.participants().size() + "</green>"
                    + " <dark_gray>|</dark_gray> <gold>Alive:</gold> <green>" + events.alive().size() + "</green>"));
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage(FormatUtil.mm("<green>Clasher Events config, arenas, and kits reloaded.</green>"));
            }
            case "arena" -> handleArenaSub(sender, args);
            case "kit" -> handleKitSub(sender, args);
            default -> sender.sendMessage(FormatUtil.mm("<red>Unknown command. Use <yellow>/ce help</yellow>.</red>"));
        }
    }

    private void handleArenaSub(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce arena list|paste|reset|info|validate <arena></yellow>"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> sender.sendMessage(FormatUtil.mm("<gold>Arenas:</gold> <white>" + String.join(", ", arenas.arenaIds()) + "</white>"));
            case "paste" -> {
                if (args.length >= 3) arenas.pasteArena(sender, args[2], events.isArenaInUse(args[2]));
                else sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce arena paste <arena></yellow>"));
            }
            case "reset" -> {
                if (args.length >= 3) arenas.resetArena(sender, args[2], events.isArenaInUse(args[2]));
                else sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce arena reset <arena></yellow>"));
            }
            case "info" -> {
                if (args.length < 3) {
                    sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce arena info <arena></yellow>"));
                    return;
                }
                Arena a = arenas.get(args[2]);
                if (a == null) sender.sendMessage(FormatUtil.mm("<red>Unknown arena.</red>"));
                else sender.sendMessage(FormatUtil.mm("<gold>" + FormatUtil.escape(a.displayName())
                        + "</gold> <dark_gray>|</dark_gray> <gray>schematic:</gray> <white>" + FormatUtil.escape(a.schematic())
                        + "</white> <dark_gray>|</dark_gray> <gray>paste:</gray> <white>" + a.pasteX() + ", " + a.pasteY() + ", " + a.pasteZ() + "</white>"));
            }
            case "validate" -> {
                if (args.length >= 3) arenas.validateArena(sender, args[2]);
                else sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce arena validate <arena></yellow>"));
            }
            default -> sender.sendMessage(FormatUtil.mm("<red>Invalid arena subcommand.</red>"));
        }
    }

    private void handleKitSub(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce kit list|give <kit> <player>|preview <kit> <player>|reload</yellow>"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> sender.sendMessage(FormatUtil.mm("<gold>Kits:</gold> <white>" + String.join(", ", kits.kitIds()) + "</white>"));
            case "reload" -> {
                kits.load();
                sender.sendMessage(FormatUtil.mm("<green>Kits reloaded (" + kits.kitIds().size() + " loaded).</green>"));
            }
            case "preview" -> {
                if (args.length < 3) {
                    sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce kit preview <kit> [player]</yellow>"));
                    return;
                }
                Player target = args.length >= 4 ? Bukkit.getPlayerExact(args[3]) : (sender instanceof Player p ? p : null);
                if (target == null) sender.sendMessage(FormatUtil.mm("<red>Target player is not online.</red>"));
                else gui.openKitPreview(target, args[2]);
            }
            case "give" -> {
                if (args.length < 4) {
                    sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce kit give <kit> <player></yellow>"));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) sender.sendMessage(FormatUtil.mm("<red>Player not online.</red>"));
                else if (kits.giveKit(target, args[2])) sender.sendMessage(FormatUtil.mm("<green>Kit given to " + FormatUtil.escape(target.getName()) + ".</green>"));
            }
            default -> sender.sendMessage(FormatUtil.mm("<red>Invalid kit subcommand.</red>"));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("latejoin")) {
            if (args.length == 1 && sender.hasPermission("clasherevents.admin")) {
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            }
        } else if (name.equals("event")) {
            if (args.length == 1) options.addAll(EVENT_SUBS);
            else if (args.length == 2 && List.of("vote", "cheer").contains(args[0].toLowerCase(Locale.ROOT))) {
                for (var id : events.alive()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) options.add(p.getName());
                }
            }
        } else if (name.equals("ce") && sender.hasPermission("clasherevents.admin")) {
            if (args.length == 1) options.addAll(CE_SUBS);
            else if (args.length == 2 && args[0].equalsIgnoreCase("create")) options.addAll(EventMode.allIds());
            else if (args.length == 2 && args[0].equalsIgnoreCase("arena")) options.addAll(List.of("list", "paste", "reset", "info", "validate"));
            else if (args.length == 2 && List.of("setspawn", "addspawn").contains(args[0].toLowerCase(Locale.ROOT))) options.addAll(EventMode.allIds());
            else if (args.length == 3 && List.of("setspawn", "addspawn").contains(args[0].toLowerCase(Locale.ROOT))) options.addAll(List.of("wave1", "wave2", "final", "audience"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("kit")) options.addAll(List.of("list", "give", "preview", "reload"));
            else if (args.length == 3 && args[0].equalsIgnoreCase("arena")) options.addAll(arenas.arenaIds());
            else if (args.length == 3 && args[0].equalsIgnoreCase("kit") && List.of("give", "preview").contains(args[1].toLowerCase(Locale.ROOT))) options.addAll(kits.kitIds());
            else if (args.length == 4 && args[0].equalsIgnoreCase("kit") && List.of("give", "preview").contains(args[1].toLowerCase(Locale.ROOT))) {
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }
}
