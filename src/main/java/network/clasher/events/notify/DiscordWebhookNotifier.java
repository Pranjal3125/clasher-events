package network.clasher.events.notify;

import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Zero-dependency asynchronous Discord Webhook embed dispatcher for Clasher Events announcements.
 */
public final class DiscordWebhookNotifier {
    private final JavaPlugin plugin;
    private final Logger logger;
    private final HttpClient httpClient;

    public DiscordWebhookNotifier(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .build();
    }

    private String webhookUrl() {
        if (!plugin.getConfig().getBoolean("discord.enabled", false)) return "";
        return plugin.getConfig().getString("discord.webhook-url", "").trim();
    }

    public void notifyEventOpen(String modeName, String arenaName, int countdownSeconds) {
        String url = webhookUrl();
        if (!url.startsWith("https://")) return;
        String desc = "**" + esc(modeName) + "** registration is now open on **" + esc(arenaName) + "**!\\n"
                + "Join in-game with `/event join` — starts in **" + countdownSeconds + "s**!";
        sendEmbedAsync(url, "⚔️ Event Registration Open!", desc, 0xF59E0B);
    }

    public void notifyModeVoteOpen(int durationSeconds) {
        String url = webhookUrl();
        if (!url.startsWith("https://")) return;
        String desc = "Players are voting on the next PvP event mode! Join the server and run `/event modevote` (**"
                + durationSeconds + "s** left).";
        sendEmbedAsync(url, "🗳️ Event Mode Voting Open!", desc, 0x38BDF8);
    }

    public void notifyMatchFinished(String modeName, String arenaName, String winnerName, String mvpName, int mvpKills, int totalFighters, String durationText) {
        String url = webhookUrl();
        if (!url.startsWith("https://")) return;
        String desc = "🏆 **Winner:** " + esc(winnerName) + "\\n"
                + "⚔️ **MVP:** " + esc(mvpName) + " (" + mvpKills + " kills)\\n"
                + "🎮 **Mode:** " + esc(modeName) + " • **Arena:** " + esc(arenaName) + "\\n"
                + "👥 **Fighters:** " + totalFighters + " • ⏱️ **Duration:** " + esc(durationText);
        sendEmbedAsync(url, "🎉 Clasher Event Completed!", desc, 0x10B981);
    }

    private void sendEmbedAsync(String url, String title, String description, int color) {
        String json = "{"
                + "\"username\":\"Clasher Events\","
                + "\"embeds\":[{"
                + "\"title\":\"" + esc(title) + "\","
                + "\"description\":\"" + description + "\","
                + "\"color\":" + color + ","
                + "\"timestamp\":\"" + Instant.now() + "\","
                + "\"footer\":{\"text\":\"Clasher Network • Clasher Events v2.0\"}"
                + "}]"
                + "}";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "ClasherEvents/2.0")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        logger.warning("Discord webhook send failed: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception ex) {
            logger.warning("Invalid Discord webhook URL: " + ex.getMessage());
        }
    }

    public static String esc(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
