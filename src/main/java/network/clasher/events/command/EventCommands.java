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
 * Command executor and tab-completer for /ce, /event, and /latejoin (v2.0).
 */
public final class EventCommands implements CommandExecutor, TabCompleter {
    private static final List<String> EVENT_SUBS = List.of(
            "join", "leave", "duel", "accept", "decline", "kiteditor", "modevote", "vote", "cheer", "team", "tc", "points", "stats", "top", "history", "spectate", "help"
    );
    private static final List<String> CE_SUBS = List.of(
            "help", "setup", "create", "voteopen", "start", "skip", "timeskip", "stop", "pause", "resume", "status", "reload",
            "holograms", "gui", "countdown", "tp", "setlobby", "setwaiting", "clearwaiting", "setspectator", "setwinner", "setcrowd",
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
            case "duel" -> {
                if (events.casualDuels() == null) return;
                if (args.length < 2) {
                    player.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/event duel <player> [kit]</yellow>"));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    player.sendMessage(FormatUtil.mm("<red>Player not found online: " + FormatUtil.escape(args[1]) + "</red>"));
                    return;
                }
                if (args.length >= 3) {
                    events.casualDuels().sendRequest(player, target, args[2]);
                } else {
                    gui.openCasualDuelKitPicker(player, target);
                }
            }
            case "accept" -> {
                if (events.casualDuels() != null) {
                    events.casualDuels().acceptRequest(player, args.length >= 2 ? args[1] : null);
                }
            }
            case "decline" -> {
                if (events.casualDuels() != null) {
                    events.casualDuels().declineRequest(player);
                }
            }
            case "modevote" -> {
                if (args.length >= 2) events.castModeVote(player, args[1]);
                else gui.openModeVoteGui(player);
            }
            case "vote" -> {
                if (args.length >= 2) events.handleVote(player, args[1]);
                else gui.openVoteGui(player);
            }
            case "cheer" -> {
                if (args.length >= 2) events.handleCheer(player, args[1]);
                else player.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/event cheer <player></yellow>"));
            }
            case "team" -> events.showTeamInfo(player);
            case "tc", "teamchat" -> {
                if (args.length < 2) {
                    player.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/event tc <message></yellow> <gray>(or type @message in chat)</gray>"));
                } else {
                    events.sendTeamChat(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                }
            }
            case "kiteditor", "kit" -> {
                String kitId = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT)
                        : (events.selectedKit() != null && !events.selectedKit().isEmpty() ? events.selectedKit() : "nethpot");
                gui.openKitLayoutEditor(player, kitId);
            }
            case "points" -> player.sendMessage(FormatUtil.mm("<gold>Audience points:</gold> <yellow>" + events.data().audiencePoints(player.getUniqueId()) + "</yellow>"));
            case "stats", "profile" -> {
                String targetName;
                DataStore.PlayerStats s;
                int pts;
                long pkBest;
                if (args.length >= 2) {
                    Player online = Bukkit.getPlayerExact(args[1]);
                    if (online != null) {
                        targetName = online.getName();
                        s = events.data().stats(online.getUniqueId());
                        pts = events.data().audiencePoints(online.getUniqueId());
                        pkBest = events.data().parkourBestMs(online.getUniqueId());
                    } else {
                        DataStore.NamedStats found = events.data().findStatsByName(args[1]);
                        if (found == null) {
                            player.sendMessage(FormatUtil.mm("<red>No recorded stats found for player: " + FormatUtil.escape(args[1]) + "</red>"));
                            return;
                        }
                        targetName = found.name();
                        s = found.stats();
                        pts = found.audiencePoints();
                        pkBest = found.parkourBestMs();
                    }
                } else {
                    targetName = player.getName();
                    s = events.data().stats(player.getUniqueId());
                    pts = events.data().audiencePoints(player.getUniqueId());
                    pkBest = events.data().parkourBestMs(player.getUniqueId());
                }
                player.sendMessage(FormatUtil.mm("<dark_gray><st>-----------------------------------</st></dark_gray>"));
                player.sendMessage(FormatUtil.mm("  <gold><bold>CLASHER PROFILE — " + FormatUtil.escape(targetName.toUpperCase(Locale.ROOT)) + "</bold></gold> " + s.rankBadgeMini()));
                player.sendMessage(FormatUtil.mm("  <yellow>Elo:</yellow> <gold><bold>" + s.elo() + "</bold></gold>"
                        + " <dark_gray>|</dark_gray> <yellow>Wins:</yellow> <white>" + s.wins()
                        + "</white> <dark_gray>|</dark_gray> <yellow>Streak:</yellow> <green>" + s.winStreak()
                        + "</green> <gray>(Best: " + s.bestStreak() + ")</gray>"));
                player.sendMessage(FormatUtil.mm("  <yellow>Kills:</yellow> <white>" + s.kills()
                        + "</white> <dark_gray>|</dark_gray> <yellow>Deaths:</yellow> <white>" + s.deaths()
                        + "</white> <dark_gray>|</dark_gray> <yellow>K/D:</yellow> <aqua>" + s.kdFormatted() + "</aqua>"));
                player.sendMessage(FormatUtil.mm("  <yellow>Audience Points:</yellow> <white>" + pts
                        + "</white> <dark_gray>|</dark_gray> <yellow>Parkour Best:</yellow> <green>" + DataStore.formatParkourTime(pkBest) + "</green>"));
                player.sendMessage(FormatUtil.mm("<dark_gray><st>-----------------------------------</st></dark_gray>"));
            }
            case "top" -> {
                String cat = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "elo";
                gui.openLeaderboardGui(player, cat);
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
                player.sendMessage(FormatUtil.mm("<gold><bold>Clasher Events v2.4 Commands</bold></gold>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event join</yellow> <dark_gray>|</dark_gray> <yellow>/event leave</yellow> <dark_gray>-</dark_gray> <gray>Join or leave the active event</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event duel <player> [kit]</yellow> <dark_gray>|</dark_gray> <yellow>accept</yellow> <dark_gray>|</dark_gray> <yellow>decline</yellow> <dark_gray>-</dark_gray> <gray>Casual 1v1 duels (auto-pauses for Server Events!)</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event kiteditor [kit]</yellow> <dark_gray>-</dark_gray> <gray>Customize your personal hotbar & offhand layout</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event modevote [mode]</yellow> <dark_gray>-</dark_gray> <gray>Vote for the next event mode</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event vote [player]</yellow> <dark_gray>|</dark_gray> <yellow>/event cheer <player></yellow> <dark_gray>-</dark_gray> <gray>Spectator predictions & cheers</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event team</yellow> <dark_gray>|</dark_gray> <yellow>/event tc <msg></yellow> <dark_gray>-</dark_gray> <gray>Team Wars squad & private team chat</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event spectate</yellow> <dark_gray>-</dark_gray> <gray>Open fighter spectator selector</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event stats [player] | top [elo|wins|kills|points] | history</yellow>"));
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
                sender.sendMessage(FormatUtil.mm("<gold><bold>Clasher Events v2.4 Admin Commands</bold></gold>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce setup [refresh]</yellow> <dark_gray>-</dark_gray> <gray>Paste Lobby, Podium, 3D Holograms & 8 arenas + skyboxes!</gray>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce gui</yellow> <dark_gray>|</dark_gray> <yellow>/ce create <mode> [kit]</yellow> <dark_gray>|</dark_gray> <yellow>/ce voteopen [sec]</yellow> <dark_gray>|</dark_gray> <yellow>/ce skip [sec|now]</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce start</yellow> <dark_gray>|</dark_gray> <yellow>/ce stop</yellow> <dark_gray>|</dark_gray> <yellow>/ce pause</yellow> <dark_gray>|</dark_gray> <yellow>/ce resume</yellow> <dark_gray>|</dark_gray> <yellow>/ce countdown</yellow> <dark_gray>|</dark_gray> <yellow>/ce status</yellow> <dark_gray>|</dark_gray> <yellow>/ce reload</yellow> <dark_gray>|</dark_gray> <yellow>/ce holograms</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce tp <lobby|winner|crowd|arena> [waiting|audience|wave1|final]</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce setwaiting <arena></yellow> <dark_gray>|</dark_gray> <yellow>/ce clearwaiting <arena></yellow> <dark_gray>|</dark_gray> <yellow>/ce setlobby|setwinner|setcrowd</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce setspawn|addspawn <mode|arena> [wave1|wave2|final|audience|waiting]</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce arena list|tp|paste|pasteall|reset|info|validate <arena></yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce kit list|save <kit>|refresh-defaults|give|preview|reload</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/latejoin <player></yellow> <dark_gray>-</dark_gray> <gray>Force-add an online player to an active match</gray>"));
            }
            case "holograms", "holo" -> {
                plugin.refreshHolograms();
                sender.sendMessage(FormatUtil.mm("<green>Refreshed all 3D Lobby & Winner Podium holograms!</green>"));
            }
            case "setup" -> {
                boolean refresh = args.length >= 2 && args[1].equalsIgnoreCase("refresh");
                arenas.runFullSetup(sender, events::isArenaInUse, refresh);
            }
            case "create" -> {
                if (args.length < 2) sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce create <mode> [kit]</yellow>"));
                else events.createEvent(args[1], args.length >= 3 ? args[2] : null);
            }
            case "voteopen" -> {
                int sec = plugin.getConfig().getInt("mode-vote.duration-seconds", 45);
                if (args.length >= 2) {
                    try { sec = Math.max(15, Integer.parseInt(args[1])); } catch (NumberFormatException ignored) {}
                }
                events.startModeVote(sec);
            }
            case "start" -> events.startEvent();
            case "skip", "timeskip" -> {
                if (args.length < 2) {
                    events.skipTimer(sender, null, false);
                } else if (args[1].equalsIgnoreCase("now") || args[1].equals("0")) {
                    events.skipTimer(sender, 0, true);
                } else {
                    try {
                        int sec = Integer.parseInt(args[1]);
                        if (sec <= 0) events.skipTimer(sender, 0, true);
                        else events.skipTimer(sender, sec, false);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce skip [seconds|now]</yellow>"));
                    }
                }
            }
            case "tp" -> {
                if (!(sender instanceof Player admin)) sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
                else if (args.length >= 2) arenas.teleportAdminToArena(admin, args[1], args.length >= 3 ? args[2] : null);
                else sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce tp <lobby|winner|crowd|arena> [waiting|audience|wave1|final]</yellow>"));
            }
            case "stop" -> events.stopEvent("Event stopped by admin");
            case "pause" -> {
                if (!events.isEventOpen() && !events.isEventRunning()) sender.sendMessage(FormatUtil.mm("<red>No active event.</red>"));
                else {
                    events.setPaused(true);
                    sender.sendMessage(FormatUtil.mm("<yellow>Event paused.</yellow> <gray>(Registration countdown paused; event combat damage disabled.)</gray>"));
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
            case "setcrowd" -> {
                if (sender instanceof Player admin) events.setNamedLocation(admin, "winner-crowd");
                else sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
            }
            case "setwaiting" -> {
                if (!(sender instanceof Player admin)) {
                    sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce setwaiting <mode|arena></yellow>"));
                    return;
                }
                String requested = args[1].toLowerCase(Locale.ROOT);
                String arenaId = EventMode.fromInput(requested)
                        .map(EventMode::defaultArenaId)
                        .orElse(arenas.exists(requested) ? requested : null);
                if (arenaId == null) {
                    sender.sendMessage(FormatUtil.mm("<red>Unknown mode/arena: " + FormatUtil.escape(requested) + ". Use /ce arena list.</red>"));
                    return;
                }
                arenas.setSpawn(admin, arenaId, "waiting", false);
            }
            case "clearwaiting" -> {
                if (args.length < 2) {
                    sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce clearwaiting <mode|arena></yellow>"));
                    return;
                }
                String requested = args[1].toLowerCase(Locale.ROOT);
                String arenaId = EventMode.fromInput(requested)
                        .map(EventMode::defaultArenaId)
                        .orElse(arenas.exists(requested) ? requested : null);
                if (arenaId == null) {
                    sender.sendMessage(FormatUtil.mm("<red>Unknown mode/arena: " + FormatUtil.escape(requested) + ". Use /ce arena list.</red>"));
                    return;
                }
                arenas.clearWaitingSpawn(sender, arenaId);
            }
            case "setspawn", "addspawn" -> {
                if (!(sender instanceof Player admin)) {
                    sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce " + sub + " <mode|arena> [wave1|wave2|final|audience|waiting]</yellow>"));
                    return;
                }
                String requested = args[1].toLowerCase(Locale.ROOT);
                if (requested.equals("random-kit")) {
                    sender.sendMessage(FormatUtil.mm("<yellow>Random Kit selects any arena — configure each arena separately.</yellow>"));
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
            sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce arena list|tp|paste|pasteall|reset|info|validate <arena></yellow>"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> sender.sendMessage(FormatUtil.mm("<gold>Arenas:</gold> <white>" + String.join(", ", arenas.arenaIds()) + "</white>"));
            case "tp", "goto" -> {
                if (!(sender instanceof Player admin)) sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
                else if (args.length >= 3) arenas.teleportAdminToArena(admin, args[2], args.length >= 4 ? args[3] : null);
                else sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce arena tp <arena> [waiting|audience|wave1|final]</yellow>"));
            }
            case "pasteall" -> arenas.pasteAllArenas(sender, events::isArenaInUse);
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
                        + "</white> <dark_gray>|</dark_gray> <gray>center:</gray> <white>" + String.format(Locale.ROOT, "%.1f, %.1f", a.centerX(), a.centerZ()) + "</white>"));
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
            sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce kit list|save <kit>|refresh-defaults|give <kit> <player>|preview <kit> [player]|reload</yellow>"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> sender.sendMessage(FormatUtil.mm("<gold>Kits:</gold> <white>" + String.join(", ", kits.kitIds()) + "</white>"));
            case "reload" -> {
                kits.load();
                sender.sendMessage(FormatUtil.mm("<green>Kits reloaded (" + kits.kitIds().size() + " loaded).</green>"));
            }
            case "refresh-defaults", "refresh" -> {
                int refreshed = plugin.refreshBundledKits();
                sender.sendMessage(FormatUtil.mm("<green>Refreshed <gold>" + refreshed + "</gold> default v2.3 competitive PvP kits (with offhand shields/totems & full potions)!</green>"));
            }
            case "save" -> {
                if (!(sender instanceof Player admin)) {
                    sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce kit save <kit></yellow>"));
                    return;
                }
                if (kits.saveKitFromPlayer(admin, args[2], plugin.getLogger())) {
                    sender.sendMessage(FormatUtil.mm("<green>Saved your current inventory, armor, offhand & effects to kit <gold>"
                            + FormatUtil.escape(args[2]) + "</gold>!</green>"));
                } else {
                    sender.sendMessage(FormatUtil.mm("<red>Could not save kit " + FormatUtil.escape(args[2]) + ".</red>"));
                }
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
            else if (args.length == 2 && args[0].equalsIgnoreCase("top")) options.addAll(List.of("elo", "wins", "kills", "points"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("kiteditor")) options.addAll(kits.kitIds());
            else if (args.length == 2 && List.of("stats", "duel", "accept").contains(args[0].toLowerCase(Locale.ROOT))) {
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            }
            else if (args.length == 3 && args[0].equalsIgnoreCase("duel")) {
                options.addAll(kits.kitIds());
            }
            else if (args.length == 2 && args[0].equalsIgnoreCase("modevote")) {
                for (EventMode m : events.modeVoteOptions()) options.add(m.id());
            } else if (args.length == 2 && List.of("vote", "cheer").contains(args[0].toLowerCase(Locale.ROOT))) {
                for (var id : events.alive()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) options.add(p.getName());
                }
            }
        } else if (name.equals("ce") && sender.hasPermission("clasherevents.admin")) {
            if (args.length == 1) options.addAll(CE_SUBS);
            else if (args.length == 2 && args[0].equalsIgnoreCase("setup")) options.add("refresh");
            else if (args.length == 2 && args[0].equalsIgnoreCase("create")) options.addAll(EventMode.allIds());
            else if (args.length == 3 && args[0].equalsIgnoreCase("create")
                    && EventMode.fromInput(args[1]).map(EventMode::supportsKitSelection).orElse(false)) {
                options.addAll(kits.kitIds());
            }
            else if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
                options.addAll(List.of("lobby", "winner", "crowd"));
                options.addAll(arenas.arenaIds());
            }
            else if (args.length == 3 && args[0].equalsIgnoreCase("tp") && arenas.exists(args[1])) {
                options.addAll(List.of("waiting", "audience", "wave1", "wave2", "final"));
            }
            else if (args.length == 2 && List.of("setwaiting", "clearwaiting").contains(args[0].toLowerCase(Locale.ROOT))) {
                options.addAll(arenas.arenaIds());
            }
            else if (args.length == 2 && List.of("skip", "timeskip").contains(args[0].toLowerCase(Locale.ROOT))) options.addAll(List.of("now", "10", "30", "60"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("arena")) options.addAll(List.of("list", "tp", "paste", "pasteall", "reset", "info", "validate"));
            else if (args.length == 2 && List.of("setspawn", "addspawn").contains(args[0].toLowerCase(Locale.ROOT))) options.addAll(EventMode.allIds());
            else if (args.length == 3 && List.of("setspawn", "addspawn").contains(args[0].toLowerCase(Locale.ROOT))) options.addAll(List.of("wave1", "wave2", "final", "audience", "waiting"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("kit")) options.addAll(List.of("list", "save", "refresh-defaults", "give", "preview", "reload"));
            else if (args.length == 3 && args[0].equalsIgnoreCase("arena") && !args[1].equalsIgnoreCase("pasteall")) {
                if (List.of("tp", "goto").contains(args[1].toLowerCase(Locale.ROOT))) options.addAll(List.of("lobby", "winner", "crowd"));
                options.addAll(arenas.arenaIds());
            }
            else if (args.length == 4 && args[0].equalsIgnoreCase("arena") && List.of("tp", "goto").contains(args[1].toLowerCase(Locale.ROOT)) && arenas.exists(args[2])) {
                options.addAll(List.of("waiting", "audience", "wave1", "wave2", "final"));
            }
            else if (args.length == 3 && args[0].equalsIgnoreCase("kit") && List.of("give", "preview", "save").contains(args[1].toLowerCase(Locale.ROOT))) options.addAll(kits.kitIds());
            else if (args.length == 4 && args[0].equalsIgnoreCase("kit") && List.of("give", "preview").contains(args[1].toLowerCase(Locale.ROOT))) {
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(v -> !v.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }
}
