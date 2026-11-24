package network.clasher.events.hud;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import network.clasher.events.storage.DataStore;
import network.clasher.events.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tier-1 Flicker-Free Event HUD Engine:
 * - Adventure BossBars
 * - Cached per-player Team-prefix Scoreboards (zero flicker, full MiniMessage RGB/Adventure formatting!)
 * - Live Below-Name Health (❤) during combat
 * - Colored Team Wars nametags ([RED] / [BLUE]) with friendly fire disabled
 * - Branded live Tablist header/footer + Hit Health ActionBar + Titles
 */
public final class EventHudManager {
    private static final int MAX_LINES = 14;
    private static final String[] LINE_KEYS = {
            "§0§r", "§1§r", "§2§r", "§3§r", "§4§r", "§5§r", "§6§r",
            "§7§r", "§8§r", "§9§r", "§a§r", "§b§r", "§c§r", "§d§r"
    };

    private final JavaPlugin plugin;
    private final BossBar bossBar;
    private final Map<UUID, Scoreboard> previousBoards = new HashMap<>();
    private final Map<UUID, Scoreboard> activeBoards = new HashMap<>();

    public EventHudManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.bossBar = BossBar.bossBar(
                FormatUtil.mm("<gold><bold>CLASHER EVENTS</bold></gold>"),
                1.0f,
                BossBar.Color.YELLOW,
                BossBar.Overlay.NOTCHED_10
        );
    }

    public boolean isBossBarEnabled() { return plugin.getConfig().getBoolean("hud.bossbar", true); }
    public boolean isScoreboardEnabled() { return plugin.getConfig().getBoolean("hud.scoreboard", true); }
    public boolean isBelowNameHealthEnabled() { return plugin.getConfig().getBoolean("hud.below-name-health", true); }
    public boolean isTablistEnabled() { return plugin.getConfig().getBoolean("hud.tablist", true); }
    public boolean isHitActionBarEnabled() { return plugin.getConfig().getBoolean("hud.hit-actionbar", true); }
    public boolean isTitlesEnabled() { return plugin.getConfig().getBoolean("hud.titles", true); }

    public void updateBossBar(String miniText, float progress, BossBar.Color color, Set<UUID> viewers) {
        if (!isBossBarEnabled()) return;
        bossBar.name(FormatUtil.mm(miniText));
        bossBar.progress(Math.max(0.0f, Math.min(1.0f, progress)));
        bossBar.color(color);
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.showBossBar(bossBar);
        }
    }

    public void updateSidebar(
            Player player,
            String modeName,
            String arenaName,
            String phase,
            int aliveCount,
            int totalParticipants,
            int matchKills,
            DataStore.PlayerStats stats,
            Integer team,
            int countdownSec,
            boolean isOpen,
            long elapsedSec,
            int borderSize,
            Map<UUID, Integer> allTeams
    ) {
        updateSidebar(player, modeName, arenaName, phase, aliveCount, totalParticipants, matchKills,
                stats, team, countdownSec, isOpen, elapsedSec, borderSize, allTeams, List.of());
    }

    public void updateSidebar(
            Player player,
            String modeName,
            String arenaName,
            String phase,
            int aliveCount,
            int totalParticipants,
            int matchKills,
            DataStore.PlayerStats stats,
            Integer team,
            int countdownSec,
            boolean isOpen,
            long elapsedSec,
            int borderSize,
            Map<UUID, Integer> allTeams,
            List<String> extraLines
    ) {
        if (player == null || !player.isOnline()) return;
        String serverIp = plugin.getConfig().getString("branding.server-ip", "play.clashernetwork.fun");

        if (isTablistEnabled()) {
            String statusLine = isOpen
                    ? "<yellow>Starting in <gold>" + FormatUtil.formatDuration(countdownSec) + "</gold> • <green>" + totalParticipants + " Joined</green></yellow>"
                    : "<green>" + aliveCount + " Alive</green> <dark_gray>|</dark_gray> <aqua>" + phase.toUpperCase(Locale.ROOT)
                    + "</aqua> <dark_gray>|</dark_gray> <yellow>" + FormatUtil.formatDuration(elapsedSec) + "</yellow>";
            player.sendPlayerListHeaderAndFooter(
                    FormatUtil.mm("\n<gold><bold>★ CLASHER EVENTS ★</bold></gold>\n<gray>Mode:</gray> <white>" + FormatUtil.escape(modeName)
                            + "</white> <dark_gray>•</dark_gray> " + statusLine + "\n"),
                    FormatUtil.mm("\n<gray>Rank:</gray> " + stats.rankBadgeMini() + " <dark_gray>|</dark_gray> <gray>Elo:</gray> <gold>"
                            + stats.elo() + "</gold> <dark_gray>|</dark_gray> <gray>Wins:</gray> <yellow>" + stats.wins()
                            + "</yellow>\n<aqua>" + FormatUtil.escape(serverIp) + "</aqua>\n")
            );
        }

        if (!isScoreboardEnabled()) return;
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return;

        UUID id = player.getUniqueId();
        Scoreboard board = activeBoards.get(id);
        Objective obj;
        if (board == null) {
            previousBoards.putIfAbsent(id, player.getScoreboard());
            board = mgr.getNewScoreboard();
            obj = board.registerNewObjective(
                    "ce_hud",
                    Criteria.DUMMY,
                    FormatUtil.mm("<gold><bold>★ CLASHER EVENTS ★</bold></gold>")
            );
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 0; i < MAX_LINES; i++) {
                Team t = board.registerNewTeam("ce_l" + i);
                t.addEntry(LINE_KEYS[i]);
            }
            if (isBelowNameHealthEnabled()) {
                Objective hp = board.registerNewObjective("ce_hp", Criteria.HEALTH, FormatUtil.mm("<red>❤</red>"));
                hp.setDisplaySlot(DisplaySlot.BELOW_NAME);
            }
            activeBoards.put(id, board);
            player.setScoreboard(board);
        } else {
            obj = board.getObjective("ce_hud");
            if (obj == null) return;
            if (player.getScoreboard() != board) {
                player.setScoreboard(board);
            }
        }

        // Sync Team Wars colored nametags when active
        if (allTeams != null && !allTeams.isEmpty()) {
            syncTeamNametags(board, allTeams);
        }

        List<String> lines = new ArrayList<>(MAX_LINES);
        lines.add("<dark_gray><st>------------------</st></dark_gray>");
        lines.add("<gray>Mode:</gray> <yellow>" + FormatUtil.escape(trim(modeName, 16)) + "</yellow>");
        lines.add("<gray>Arena:</gray> <white>" + FormatUtil.escape(trim(arenaName, 16)) + "</white>");
        if (isOpen) {
            lines.add("<gray>Starts In:</gray> <green><bold>" + FormatUtil.formatDuration(countdownSec) + "</bold></green>");
            lines.add("<gray>Joined:</gray> <yellow>" + totalParticipants + "</yellow>");
        } else {
            lines.add("<gray>Phase:</gray> <aqua>" + phase.toUpperCase(Locale.ROOT) + "</aqua> <dark_gray>(" + FormatUtil.formatDuration(elapsedSec) + ")</dark_gray>");
            lines.add("<gray>Alive:</gray> <green>" + aliveCount + "</green><dark_gray>/</dark_gray><gray>" + totalParticipants + "</gray> <dark_gray>|</dark_gray> <gray>Kills:</gray> <red>" + matchKills + "</red>");
            if (borderSize > 0) {
                lines.add("<gray>Border:</gray> <red>" + borderSize + "x" + borderSize + "</red>");
            }
            if (team != null) {
                lines.add("<gray>Side:</gray> " + (team == 1 ? "<red><bold>RED TEAM</bold></red>" : "<blue><bold>BLUE TEAM</bold></blue>"));
            }
        }
        if (extraLines != null && !extraLines.isEmpty()) {
            for (String extra : extraLines) {
                if (lines.size() < MAX_LINES - 4) lines.add(extra);
            }
        }
        lines.add("");
        lines.add("<gray>Rank:</gray> " + stats.rankBadgeMini() + " <gold>(" + stats.elo() + ")</gold>");
        lines.add("<gray>Wins:</gray> <yellow>" + stats.wins() + "</yellow> <dark_gray>|</dark_gray> <gray>Streak:</gray> <green>" + stats.winStreak() + "</green>");
        lines.add("<aqua>" + FormatUtil.escape(trim(serverIp, 24)) + "</aqua>");

        int activeCount = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < MAX_LINES; i++) {
            Team t = board.getTeam("ce_l" + i);
            if (i < activeCount) {
                if (t != null) t.prefix(FormatUtil.mm(lines.get(i)));
                obj.getScore(LINE_KEYS[i]).setScore(activeCount - i);
            } else {
                board.resetScores(LINE_KEYS[i]);
            }
        }
    }

    private void syncTeamNametags(Scoreboard board, Map<UUID, Integer> allTeams) {
        Team red = board.getTeam("ce_red");
        if (red == null) {
            red = board.registerNewTeam("ce_red");
            red.color(NamedTextColor.RED);
            red.prefix(FormatUtil.mm("<red><bold>[RED] </bold></red>"));
            red.setAllowFriendlyFire(false);
        }
        Team blue = board.getTeam("ce_blue");
        if (blue == null) {
            blue = board.registerNewTeam("ce_blue");
            blue.color(NamedTextColor.BLUE);
            blue.prefix(FormatUtil.mm("<blue><bold>[BLUE] </bold></blue>"));
            blue.setAllowFriendlyFire(false);
        }
        for (Map.Entry<UUID, Integer> e : allTeams.entrySet()) {
            Player member = Bukkit.getPlayer(e.getKey());
            if (member == null) continue;
            String name = member.getName();
            if (e.getValue() == 1) {
                blue.removeEntry(name);
                if (!red.hasEntry(name)) red.addEntry(name);
            } else if (e.getValue() == 2) {
                red.removeEntry(name);
                if (!blue.hasEntry(name)) blue.addEntry(name);
            }
        }
    }

    public void sendHitHealth(Player attacker, Player victim, double remainingHp, double maxHp) {
        if (!isHitActionBarEnabled() || attacker == null || victim == null) return;
        String hpFmt = String.format(Locale.ROOT, "%.1f", Math.max(0.0, remainingHp));
        String maxFmt = String.format(Locale.ROOT, "%.0f", Math.max(1.0, maxHp));
        String color = remainingHp > maxHp * 0.6 ? "<green>" : (remainingHp > maxHp * 0.3 ? "<yellow>" : "<red>");
        attacker.sendActionBar(FormatUtil.mm("<yellow>" + FormatUtil.escape(victim.getName()) + "</yellow> "
                + color + "<bold>❤ " + hpFmt + "/" + maxFmt + "</bold></" + (remainingHp > maxHp * 0.6 ? "green" : (remainingHp > maxHp * 0.3 ? "yellow" : "red")) + ">"));
    }

    public void showTitle(Player player, String titleMini, String subtitleMini, long fadeInMs, long stayMs, long fadeOutMs) {
        if (!isTitlesEnabled() || player == null || !player.isOnline()) return;
        Title title = Title.title(
                FormatUtil.mm(titleMini),
                FormatUtil.mm(subtitleMini),
                Title.Times.times(Duration.ofMillis(fadeInMs), Duration.ofMillis(stayMs), Duration.ofMillis(fadeOutMs))
        );
        player.showTitle(title);
    }

    public void broadcastTitle(Set<UUID> viewers, String titleMini, String subtitleMini, long fadeInMs, long stayMs, long fadeOutMs) {
        if (!isTitlesEnabled()) return;
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) showTitle(p, titleMini, subtitleMini, fadeInMs, stayMs, fadeOutMs);
        }
    }

    public void clearForPlayer(Player player) {
        if (player == null) return;
        player.hideBossBar(bossBar);
        activeBoards.remove(player.getUniqueId());
        if (player.isOnline() && isTablistEnabled()) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
        Scoreboard prev = previousBoards.remove(player.getUniqueId());
        if (prev != null && player.isOnline()) {
            player.setScoreboard(prev);
        } else if (player.isOnline() && Bukkit.getScoreboardManager() != null) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    public void clearAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(bossBar);
            if (isTablistEnabled() && activeBoards.containsKey(p.getUniqueId())) {
                p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            }
        }
        activeBoards.clear();
        for (Map.Entry<UUID, Scoreboard> entry : new HashMap<>(previousBoards).entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null && p.isOnline()) {
                p.setScoreboard(entry.getValue());
            }
        }
        previousBoards.clear();
    }

    private static String trim(String raw, int max) {
        if (raw == null) return "";
        return raw.length() <= max ? raw : raw.substring(0, max - 1) + "…";
    }
}
