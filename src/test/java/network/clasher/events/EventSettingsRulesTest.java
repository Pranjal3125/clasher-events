package network.clasher.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventSettingsRulesTest {

    @Test
    void cyclesAllowedValuesAndWrapsAround() {
        assertEquals(300, EventSettingsRules.nextValue(120, 120, 300, 600));
        assertEquals(600, EventSettingsRules.nextValue(300, 120, 300, 600));
        assertEquals(120, EventSettingsRules.nextValue(600, 120, 300, 600));
    }

    @Test
    void preservesTheOrderProvidedByTheSettingsGui() {
        assertEquals(140, EventSettingsRules.nextValue(160, 160, 140, 120, 100, 80));
        assertEquals(80, EventSettingsRules.nextValue(100, 160, 140, 120, 100, 80));
        assertEquals(160, EventSettingsRules.nextValue(80, 160, 140, 120, 100, 80));
    }

    @Test
    void unsupportedStoredValueFallsBackToFirstAllowedValue() {
        assertEquals(120, EventSettingsRules.nextValue(240, 120, 300, 600));
    }

    @Test
    void validatesRegistrationAndKitLeadRanges() {
        assertTrue(EventSettingsRules.isValidRegistrationSeconds(30));
        assertTrue(EventSettingsRules.isValidRegistrationSeconds(1800));
        assertFalse(EventSettingsRules.isValidRegistrationSeconds(29));
        assertFalse(EventSettingsRules.isValidRegistrationSeconds(1801));
        assertTrue(EventSettingsRules.isValidKitLeadSeconds(0, 120));
        assertTrue(EventSettingsRules.isValidKitLeadSeconds(119, 120));
        assertFalse(EventSettingsRules.isValidKitLeadSeconds(120, 120));
        assertFalse(EventSettingsRules.isValidKitLeadSeconds(-1, 120));
    }

    @Test
    void validatesBorderAndLobbyProtectionRanges() {
        assertTrue(EventSettingsRules.isValidBorderSize(1));
        assertTrue(EventSettingsRules.isValidBorderSize(59_999_968));
        assertFalse(EventSettingsRules.isValidBorderSize(0));
        assertFalse(EventSettingsRules.isValidBorderSize(Double.NaN));
        assertFalse(EventSettingsRules.isValidBorderSize(Double.POSITIVE_INFINITY));
        assertTrue(EventSettingsRules.isValidBorderShrinkSeconds(0));
        assertTrue(EventSettingsRules.isValidBorderShrinkSeconds(86_400));
        assertFalse(EventSettingsRules.isValidBorderShrinkSeconds(-1));
        assertFalse(EventSettingsRules.isValidBorderShrinkSeconds(86_401));
        assertTrue(EventSettingsRules.isValidLobbyRadius(1));
        assertTrue(EventSettingsRules.isValidLobbyRadius(1000));
        assertFalse(EventSettingsRules.isValidLobbyRadius(0));
        assertFalse(EventSettingsRules.isValidLobbyRadius(1001));
    }

    @Test
    void waveTwoThresholdMatchesApprovedProgressionRules() {
        assertEquals(-1, EventSettingsRules.wave2Threshold(-1));
        assertEquals(-1, EventSettingsRules.wave2Threshold(7));
        assertEquals(3, EventSettingsRules.wave2Threshold(8));
        assertEquals(3, EventSettingsRules.wave2Threshold(9));
        assertEquals(10, EventSettingsRules.wave2Threshold(30));
        assertEquals(20, EventSettingsRules.wave2Threshold(60));
    }
}
