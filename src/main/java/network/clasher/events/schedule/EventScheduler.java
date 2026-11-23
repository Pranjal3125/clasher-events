package network.clasher.events.schedule;

import network.clasher.events.match.EventManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Automatic Clasher Events scheduler supporting interval hosting, fixed daily HH:mm slots,
 * minimum online-player thresholds, and rotating modes or player-vote launches.
 */
public final class EventScheduler {
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final JavaPlugin plugin;
    private final EventManager events;
    private BukkitTask task;
    private long lastIntervalLaunchMs;
    private String lastTriggeredSlot = "";
    private int rotationIndex = 0;

    public EventScheduler(JavaPlugin plugin, EventManager events) {
        this.plugin = plugin;
        this.events = events;
        this.lastIntervalLaunchMs = System.currentTimeMillis();
    }

    public void start() {
        stop();
        // Check every 20 seconds
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 400L, 400L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("auto-scheduler.enabled", false)) return;
        if (events.isEventOpen() || events.isEventRunning() || events.isModeVoteOpen()) return;

        int minOnline = Math.max(1, plugin.getConfig().getInt("auto-scheduler.min-online-players", 4));
        if (Bukkit.getOnlinePlayers().size() < minOnline) return;

        String zoneIdRaw = plugin.getConfig().getString("auto-scheduler.timezone", "Asia/Kolkata");
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneIdRaw);
        } catch (Exception ex) {
            zone = ZoneId.systemDefault();
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        List<String> fixedTimes = plugin.getConfig().getStringList("auto-scheduler.fixed-times");

        boolean shouldLaunch = false;
        if (!fixedTimes.isEmpty()) {
            String slotKey = now.toLocalDate() + "@" + now.format(HH_MM);
            if (matchesFixedTime(now.toLocalTime(), fixedTimes) && !slotKey.equals(lastTriggeredSlot)) {
                lastTriggeredSlot = slotKey;
                shouldLaunch = true;
            }
        } else {
            long intervalMin = Math.max(10L, plugin.getConfig().getLong("auto-scheduler.interval-minutes", 60L));
            if (System.currentTimeMillis() - lastIntervalLaunchMs >= intervalMin * 60_000L) {
                lastIntervalLaunchMs = System.currentTimeMillis();
                shouldLaunch = true;
            }
        }

        if (!shouldLaunch) return;

        if (plugin.getConfig().getBoolean("auto-scheduler.use-mode-vote", true)) {
            int voteSec = Math.max(15, plugin.getConfig().getInt("mode-vote.duration-seconds", 45));
            events.startModeVote(voteSec);
        } else {
            List<String> rotation = plugin.getConfig().getStringList("auto-scheduler.mode-rotation");
            String mode = selectNextMode(rotation, rotationIndex++);
            events.createEvent(mode);
        }
    }

    public static boolean matchesFixedTime(LocalTime now, List<String> configuredTimes) {
        if (now == null || configuredTimes == null) return false;
        String current = now.format(HH_MM);
        for (String entry : configuredTimes) {
            if (entry != null && entry.trim().equals(current)) {
                return true;
            }
        }
        return false;
    }

    public static String selectNextMode(List<String> rotation, int index) {
        if (rotation == null || rotation.isEmpty()) return "nethpot";
        return rotation.get(Math.floorMod(index, rotation.size()));
    }
}
