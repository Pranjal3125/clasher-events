package network.clasher.events;

import java.util.Arrays;

/**
 * Pure validation and cycling rules for administrator-editable event settings.
 * Kept independent of Bukkit so the rules can be covered by fast unit tests.
 */
public final class EventSettingsRules {
    private EventSettingsRules() {
    }

    public static int nextValue(int current, int... allowedValues) {
        if (allowedValues == null || allowedValues.length == 0) {
            throw new IllegalArgumentException("At least one allowed value is required");
        }
        int[] values = allowedValues.clone();
        Arrays.sort(values);
        int index = Arrays.binarySearch(values, current);
        return index < 0 ? values[0] : values[(index + 1) % values.length];
    }

    public static boolean isValidRegistrationSeconds(int value) {
        return value >= 30 && value <= 1800;
    }

    public static boolean isValidKitLeadSeconds(int value, int registrationSeconds) {
        return value >= 0 && value < registrationSeconds;
    }

    public static boolean isValidBorderSize(double value) {
        return Double.isFinite(value) && value >= 1 && value <= 59_999_968;
    }

    public static boolean isValidBorderShrinkSeconds(long value) {
        return value >= 0 && value <= 86_400;
    }

    public static boolean isValidLobbyRadius(double value) {
        return Double.isFinite(value) && value >= 1 && value <= 1000;
    }

    public static int wave2Threshold(int initialFighters) {
        if (initialFighters < 8) return -1;
        return Math.max(3, initialFighters / 3);
    }
}
