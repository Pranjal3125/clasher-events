package network.clasher.events.command;

import network.clasher.events.ClasherEventsPlugin;
import network.clasher.events.arena.ArenaManager;
import network.clasher.events.gui.EventGui;
import network.clasher.events.kit.KitManager;
import network.clasher.events.match.EventManager;
import network.clasher.events.match.TournamentSquadManager;
import network.clasher.events.model.Arena;
import network.clasher.events.model.EventMode;
import network.clasher.events.model.EventModifier;
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
            "join", "leave", "queue", "party", "ffa", "inv", "squad", "bracket", "duel", "accept", "decline", "kiteditor", "modevote", "vote", "cheer", "team", "tc", "points", "stats", "top", "history", "spectate", "help"
    );
    private static final List<String> CE_SUBS = List.of(
            "help", "setup", "create", "tournament", "modifiers", "autocam", "voteopen", "start", "skip", "timeskip", "stop", "pause", "resume", "status", "reload",
            "holograms", "gui", "countdown", "tp", "setlobby", "settournamentlobby", "setwaiting", "clearwaiting", "setspectator", "setwinner", "setcrowd",
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
            case "leave" -> {
                if (events.warmupFfa() != null && events.warmupFfa().isInFfa(player.getUniqueId())) {
                    events.warmupFfa().leaveFfa(player, true);
                } else if (events.casualDuels() != null && events.casualDuels().forfeitDuel(player)) {
                    // Forfeited casual 1v1 or party duel cleanly
                } else if (!events.isParticipant(player.getUniqueId())
                        && events.casualDuels() != null && events.casualDuels().queuedKitOf(player.getUniqueId()) != null) {
                    events.casualDuels().leaveQueue(player);
                } else {
                    events.leaveEvent(player);
                }
            }
            case "queue", "q" -> {
                if (events.casualDuels() == null) return;
                if (args.length < 2) gui.openDuelQueueGui(player);
                else if (args[1].equalsIgnoreCase("leave") || args[1].equalsIgnoreCase("cancel")) events.casualDuels().leaveQueue(player);
                else events.casualDuels().joinQueue(player, args[1]);
            }
            case "ffa", "warmup" -> {
                if (events.warmupFfa() == null) return;
                if (args.length >= 2 && (args[1].equalsIgnoreCase("leave") || args[1].equalsIgnoreCase("exit"))) {
                    events.warmupFfa().leaveFfa(player, true);
                } else {
                    events.warmupFfa().joinFfa(player);
                }
            }
            case "inv", "inspect" -> {
                if (args.length < 2) player.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/event inv <token></yellow>"));
                else gui.openPostDuelInventoryGui(player, args[1]);
            }
            case "party", "p" -> handlePlayerParty(player, args);
            case "bracket", "teams" -> gui.openBracketViewerGui(player);
            case "squad" -> handlePlayerSquad(player, args);
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
                if (events.isModeVoteOpen()) {
                    if (args.length >= 2) events.castModeVote(player, args[1]);
                    else gui.openModeVoteGui(player);
                } else if (events.isKitVoteOpen()) {
                    if (args.length >= 2) events.castTournamentKitVote(player, args[1]);
                    else gui.openKitVoteGui(player);
                } else if (args.length >= 2) {
                    events.handleVote(player, args[1]);
                } else {
                    gui.openSmartVoteGui(player);
                }
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
                player.sendMessage(FormatUtil.mm("<gold><bold>Clasher Events v2.6 Commands</bold></gold>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event join</yellow> <dark_gray>|</dark_gray> <yellow>/event leave</yellow> <dark_gray>|</dark_gray> <yellow>/event queue [kit|leave]</yellow> <dark_gray>|</dark_gray> <yellow>/event ffa [leave]</yellow>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event duel <player> [kit]</yellow> <dark_gray>|</dark_gray> <yellow>/event party [invite|accept|duel|split|leave]</yellow>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event squad [invite|accept|decline|leave|kick|name|list]</yellow> <dark_gray>|</dark_gray> <yellow>/event bracket</yellow>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event vote</yellow> <dark_gray>|</dark_gray> <yellow>/event modevote</yellow> <dark_gray>|</dark_gray> <yellow>/event kiteditor [kit]</yellow>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event team</yellow> <dark_gray>|</dark_gray> <yellow>/event tc <msg></yellow> <dark_gray>|</dark_gray> <yellow>/event spectate</yellow> <dark_gray>|</dark_gray> <yellow>/event stats | top | history</yellow>"));
            }
        }
    }

    private void handlePlayerParty(Player player, String[] args) {
        if (events.casualDuels() == null) return;
        if (args.length < 2) {
            events.casualDuels().partyList(player);
            player.sendMessage(FormatUtil.mm("<gray>Commands:</gray> <yellow>/event party invite <p> | accept | leave | kick <p> | disband | duel <p> [kit] | split [kit]</yellow>"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "invite", "inv", "add" -> {
                if (args.length < 3) player.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/event party invite <player></yellow>"));
                else events.casualDuels().partyInvite(player, args[2]);
            }
            case "accept", "join" -> events.casualDuels().partyAccept(player, args.length >= 3 ? args[2] : null);
            case "decline", "deny" -> events.casualDuels().partyDecline(player, args.length >= 3 ? args[2] : null);
            case "leave" -> events.casualDuels().partyLeave(player);
            case "kick", "remove" -> {
                if (args.length < 3) player.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/event party kick <player></yellow>"));
                else events.casualDuels().partyKick(player, args[2]);
            }
            case "disband" -> events.casualDuels().partyDisband(player);
            case "list", "info" -> events.casualDuels().partyList(player);
            case "duel", "fight" -> {
                if (args.length < 3) player.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/event party duel <opponentPlayer> [kit]</yellow>"));
                else events.casualDuels().sendPartyDuelRequest(player, args[2], args.length >= 4 ? args[3] : "nethpot");
            }
            case "split" -> events.casualDuels().startPartySplit(player, args.length >= 3 ? args[2] : "nethpot");
            default -> player.sendMessage(FormatUtil.mm("<yellow>/event party invite <p> | accept | decline | leave | kick <p> | disband | list | duel <p> [kit] | split [kit]</yellow>"));
        }
    }

    private void handlePlayerSquad(Player player, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("gui")) {
            gui.openSquadManagerGui(player);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "invite", "inv", "add" -> {
                if (args.length < 3) player.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/event squad invite <player></yellow>"));
                else events.handleSquadInvite(player, args[2]);
            }
            case "accept", "join" -> events.handleSquadAccept(player, args.length >= 3 ? args[2] : null);
            case "decline", "deny" -> events.handleSquadDecline(player, args.length >= 3 ? args[2] : null);
            case "leave" -> events.handleSquadLeave(player);
            case "kick", "remove" -> {
                if (args.length < 3) player.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/event squad kick <player></yellow>"));
                else events.handleSquadKick(player, args[2]);
            }
            case "name", "rename" -> {
                if (args.length < 3) player.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/event squad name <TeamName></yellow>"));
                else events.handleSquadRename(player, String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
            }
            case "list", "info" -> events.showSquadList(player);
            default -> {
                player.sendMessage(FormatUtil.mm("<gold><bold>Tournament Squad Commands</bold></gold>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event squad</yellow> <gray>- Open 1-click Squad Manager GUI</gray>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event squad invite <player></yellow> <dark_gray>|</dark_gray> <yellow>accept [leader]</yellow> <dark_gray>|</dark_gray> <yellow>decline</yellow>"));
                player.sendMessage(FormatUtil.mm("<yellow>/event squad name <TeamName></yellow> <dark_gray>|</dark_gray> <yellow>kick <player></yellow> <dark_gray>|</dark_gray> <yellow>leave</yellow> <dark_gray>|</dark_gray> <yellow>list</yellow>"));
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
                sender.sendMessage(FormatUtil.mm("<gold><bold>Clasher Events v2.6 Admin Commands</bold></gold>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce setup [refresh]</yellow> <dark_gray>-</dark_gray> <gray>Paste Imperial Lobby, 4 NPCs, Tournament Hall, Podium, 8 Grand & 8 Duel Arenas!</gray>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce gui</yellow> <dark_gray>|</dark_gray> <yellow>/ce create <mode> [kit] [1v1|2v2|3v3|4v4]</yellow> <dark_gray>|</dark_gray> <yellow>/ce modifiers</yellow> <dark_gray>|</dark_gray> <yellow>/ce autocam</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce tournament [gui|teams|shuffle|pair|disband|lock|unlock|bo3|parallel|kitvote]</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce voteopen [sec]</yellow> <dark_gray>|</dark_gray> <yellow>/ce start</yellow> <dark_gray>|</dark_gray> <yellow>/ce skip [sec|now]</yellow> <dark_gray>|</dark_gray> <yellow>/ce stop|pause|resume|reload|holograms</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce tp <lobby|tournament-lobby|winner|crowd|arena|duel-arena> [phase]</yellow>"));
                sender.sendMessage(FormatUtil.mm("<yellow>/ce arena list|tp|paste|pasteall|reset|info|validate</yellow> <dark_gray>|</dark_gray> <yellow>/ce kit list|save|refresh-defaults|give|preview</yellow>"));
            }
            case "holograms", "holo", "npcs" -> {
                plugin.refreshHolograms();
                sender.sendMessage(FormatUtil.mm("<green>Refreshed all 3D Lobby, Tournament Hall, Winner Podium holograms & 4 Clickable Lobby NPCs!</green>"));
            }
            case "modifiers", "mutators" -> {
                if (args.length < 2 || args[1].equalsIgnoreCase("gui")) {
                    if (sender instanceof Player admin) gui.openEventModifiersGui(admin);
                    else sender.sendMessage(FormatUtil.mm("<gold>Active Modifiers:</gold> <yellow>" + FormatUtil.escape(events.modifiersSummary()) + "</yellow>"));
                } else if (args[1].equalsIgnoreCase("clear") || args[1].equalsIgnoreCase("reset")) {
                    events.clearModifiers();
                    sender.sendMessage(FormatUtil.mm("<yellow>Cleared all active event modifiers.</yellow>"));
                } else {
                    String modArg = args[1].equalsIgnoreCase("toggle") && args.length >= 3 ? args[2] : args[1];
                    EventModifier.fromInput(modArg).ifPresentOrElse(
                            m -> {
                                boolean on = events.toggleModifier(m);
                                sender.sendMessage(FormatUtil.mm("<light_purple>Modifier <gold>" + m.displayName() + "</gold>: " + (on ? "<green>ON</green>" : "<red>OFF</red>") + "</light_purple>"));
                            },
                            () -> sender.sendMessage(FormatUtil.mm("<red>Unknown modifier: " + FormatUtil.escape(modArg) + "</red>"))
                    );
                }
            }
            case "autocam", "cam" -> {
                if (sender instanceof Player admin) events.toggleAutoCam(admin);
                else sender.sendMessage(FormatUtil.mm("<red>Players only.</red>"));
            }
            case "setup" -> {
                boolean refresh = args.length >= 2 && args[1].equalsIgnoreCase("refresh");
                arenas.runFullSetup(sender, events::isArenaInUse, refresh);
            }
            case "create" -> {
                if (args.length < 2) {
                    sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce create <mode> [kit] [1v1|2v2|3v3|4v4]</yellow>"));
                } else if (args[1].equalsIgnoreCase("tournament") || args[1].equalsIgnoreCase("tourney")) {
                    String kitArg = null;
                    Integer sizeArg = null;
                    for (int i = 2; i < args.length; i++) {
                        int parsedSize = TournamentSquadManager.parseTeamSize(args[i], -1);
                        if (parsedSize != -1 && sizeArg == null) sizeArg = parsedSize;
                        else if (kitArg == null) kitArg = args[i];
                    }
                    events.createEvent("tournament", kitArg, sizeArg);
                } else {
                    events.createEvent(args[1], args.length >= 3 ? args[2] : null);
                }
            }
            case "tournament", "tourney" -> handleAdminTournament(sender, args);
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
            case "settournamentlobby", "settlobby" -> {
                if (sender instanceof Player admin) events.setNamedLocation(admin, "tournament-lobby");
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

    private void handleAdminTournament(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("gui")) {
            if (sender instanceof Player admin) gui.openAdminTournamentGui(admin);
            else events.showSquadList(sender);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "teams", "list" -> events.showSquadList(sender);
            case "shuffle" -> events.adminShuffleSquads(sender);
            case "pair" -> {
                if (args.length < 4) sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce tournament pair <player1> <player2></yellow>"));
                else events.adminForcePair(sender, args[2], args[3]);
            }
            case "disband" -> {
                if (args.length < 3) sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce tournament disband <teamId></yellow>"));
                else {
                    try {
                        events.adminDisbandTeam(sender, Integer.parseInt(args[2]));
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(FormatUtil.mm("<red>Team ID must be a number (check /ce tournament teams).</red>"));
                    }
                }
            }
            case "lock" -> events.adminSetInvitesLocked(sender, true);
            case "unlock" -> events.adminSetInvitesLocked(sender, false);
            case "bo3" -> {
                boolean next = args.length >= 3 ? (args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true")) : !events.squads().isBo3Finals();
                events.adminSetBo3Finals(sender, next);
            }
            case "parallel" -> {
                boolean next = args.length >= 3 ? (args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true")) : !events.squads().isParallelRounds();
                events.adminSetParallelRounds(sender, next);
            }
            case "kitvote", "vote" -> {
                int sec = 30;
                if (args.length >= 3) {
                    try { sec = Math.max(15, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) {}
                }
                events.startTournamentKitVote(sender, sec);
            }
            default -> sender.sendMessage(FormatUtil.mm("<red>Usage:</red> <yellow>/ce tournament [gui|teams|shuffle|pair <p1> <p2>|disband <id>|lock|unlock|bo3 <on|off>|kitvote [sec]]</yellow>"));
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
            else if (args.length == 2 && args[0].equalsIgnoreCase("queue")) {
                options.add("leave");
                options.addAll(kits.kitIds());
            } else if (args.length == 2 && args[0].equalsIgnoreCase("ffa")) {
                options.addAll(List.of("join", "leave"));
            } else if (args.length == 2 && args[0].equalsIgnoreCase("party")) {
                options.addAll(List.of("invite", "accept", "decline", "leave", "kick", "disband", "list", "duel", "split"));
            } else if (args.length == 3 && args[0].equalsIgnoreCase("party")
                    && List.of("invite", "accept", "decline", "kick", "duel").contains(args[1].toLowerCase(Locale.ROOT))) {
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            } else if (args.length == 3 && args[0].equalsIgnoreCase("party") && args[1].equalsIgnoreCase("split")) {
                options.addAll(kits.kitIds());
            } else if (args.length == 4 && args[0].equalsIgnoreCase("party") && args[1].equalsIgnoreCase("duel")) {
                options.addAll(kits.kitIds());
            } else if (args.length == 2 && args[0].equalsIgnoreCase("squad")) {
                options.addAll(List.of("gui", "invite", "accept", "decline", "leave", "kick", "name", "list"));
            } else if (args.length == 3 && args[0].equalsIgnoreCase("squad")
                    && List.of("invite", "accept", "decline", "kick").contains(args[1].toLowerCase(Locale.ROOT))) {
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            } else if (args.length == 2 && args[0].equalsIgnoreCase("top")) options.addAll(List.of("elo", "wins", "kills", "points"));
            else if (args.length == 2 && args[0].equalsIgnoreCase("kiteditor")) options.addAll(kits.kitIds());
            else if (args.length == 2 && List.of("stats", "duel", "accept").contains(args[0].toLowerCase(Locale.ROOT))) {
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            }
            else if (args.length == 3 && args[0].equalsIgnoreCase("duel")) {
                options.addAll(kits.kitIds());
            }
            else if (args.length == 2 && args[0].equalsIgnoreCase("modevote")) {
                for (EventMode m : events.modeVoteOptions()) options.add(m.id());
            } else if (args.length == 2 && args[0].equalsIgnoreCase("vote")) {
                if (events.isModeVoteOpen()) {
                    for (EventMode m : events.modeVoteOptions()) options.add(m.id());
                } else if (events.isKitVoteOpen()) {
                    options.addAll(kits.kitIds());
                } else {
                    for (var id : events.alive()) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) options.add(p.getName());
                    }
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("cheer")) {
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
                if (args[1].equalsIgnoreCase("tournament")) options.addAll(List.of("1v1", "2v2", "3v3", "4v4"));
            }
            else if (args.length == 4 && args[0].equalsIgnoreCase("create") && args[1].equalsIgnoreCase("tournament")) {
                options.addAll(List.of("1v1", "2v2", "3v3", "4v4"));
                options.addAll(kits.kitIds());
            }
            else if (args.length == 2 && List.of("tournament", "tourney").contains(args[0].toLowerCase(Locale.ROOT))) {
                options.addAll(List.of("gui", "teams", "shuffle", "pair", "disband", "lock", "unlock", "bo3", "parallel", "kitvote"));
            }
            else if (args.length == 2 && List.of("modifiers", "mutators").contains(args[0].toLowerCase(Locale.ROOT))) {
                options.addAll(List.of("gui", "clear"));
                for (EventModifier m : EventModifier.values()) options.add(m.id());
            }
            else if (args.length == 3 && List.of("tournament", "tourney").contains(args[0].toLowerCase(Locale.ROOT))) {
                if (args[1].equalsIgnoreCase("bo3") || args[1].equalsIgnoreCase("parallel")) options.addAll(List.of("on", "off"));
                else if (args[1].equalsIgnoreCase("pair")) {
                    for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
                } else if (args[1].equalsIgnoreCase("kitvote")) options.addAll(List.of("30", "45", "60"));
            }
            else if (args.length == 4 && List.of("tournament", "tourney").contains(args[0].toLowerCase(Locale.ROOT)) && args[1].equalsIgnoreCase("pair")) {
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            }
            else if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
                options.addAll(List.of("lobby", "tournament-lobby", "winner", "crowd"));
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
                if (List.of("tp", "goto").contains(args[1].toLowerCase(Locale.ROOT))) options.addAll(List.of("lobby", "tournament-lobby", "winner", "crowd"));
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
