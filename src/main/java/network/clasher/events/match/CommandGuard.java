package network.clasher.events.match;

import java.util.Collection;
import java.util.Locale;

/**
 * Pure, testable command-whitelist filter used to block escape/smuggling commands
 * (/ec, /pv, /ah, /home, /spawn, /warp, /trade, etc.) while players are inside an event.
 */
public final class CommandGuard {
    private CommandGuard() {}

    /**
     * Extracts the base command token (stripping leading '/', namespace prefix like 'essentials:', and arguments).
     */
    public static String extractBaseCommand(String rawMessage) {
        if (rawMessage == null) return "";
        String trimmed = rawMessage.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        int space = trimmed.indexOf(' ');
        String first = space >= 0 ? trimmed.substring(0, space) : trimmed;
        int colon = first.indexOf(':');
        if (colon >= 0 && colon + 1 < first.length()) {
            first = first.substring(colon + 1);
        }
        return first.toLowerCase(Locale.ROOT);
    }

    public static boolean isCommandAllowed(String rawMessage, Collection<String> allowedCommands) {
        String base = extractBaseCommand(rawMessage);
        if (base.isEmpty()) return true;
        if (allowedCommands == null || allowedCommands.isEmpty()) return false;
        for (String allowed : allowedCommands) {
            if (allowed == null) continue;
            String normalized = allowed.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("/")) normalized = normalized.substring(1);
            if (normalized.equals(base)) return true;
        }
        return false;
    }
}
