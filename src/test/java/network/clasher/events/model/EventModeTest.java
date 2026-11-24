package network.clasher.events.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class EventModeTest {

    @Test
    void parsesAllModesAndAliases() {
        assertEquals(EventMode.NETHPOT, EventMode.fromInput("nethpot").orElseThrow());
        assertEquals(EventMode.AXE_SHIELD, EventMode.fromInput("axe").orElseThrow());
        assertEquals(EventMode.TEAM_WARS, EventMode.fromInput("teams").orElseThrow());
        assertEquals(EventMode.DUEL, EventMode.fromInput("1v1").orElseThrow());
        assertEquals(EventMode.DIAMOND_POT, EventMode.fromInput("dia-pot").orElseThrow());
        assertEquals(EventMode.CRYSTAL_FFA, EventMode.fromInput("crystal-ffa").orElseThrow());
        assertEquals(EventMode.CRYSTAL_FFA, EventMode.fromInput("crystal-terrain").orElseThrow());
        assertEquals("crystal", EventMode.CRYSTAL_FFA.defaultKitId());
        assertEquals(EventMode.TOURNAMENT, EventMode.fromInput("tourney").orElseThrow());
        assertTrue(EventMode.fromInput("nonexistent").isEmpty());
        assertTrue(EventMode.fromInput(null).isEmpty());
    }

    @Test
    void calculatesWave2ThresholdAccurately() {
        assertEquals(-1, EventMode.calculateWave2Threshold(0));
        assertEquals(-1, EventMode.calculateWave2Threshold(7));
        assertEquals(3, EventMode.calculateWave2Threshold(8));
        assertEquals(3, EventMode.calculateWave2Threshold(9));
        assertEquals(5, EventMode.calculateWave2Threshold(15));
        assertEquals(10, EventMode.calculateWave2Threshold(30));
    }

    @Test
    void scalesDynamicBorderWithAlivePlayersAndSupportsMultiKitFormats() {
        assertEquals(26.0, EventMode.calculateDynamicBorderSize(2, 100.0));
        assertEquals(38.0, EventMode.calculateDynamicBorderSize(4, 100.0));
        assertEquals(50.0, EventMode.calculateDynamicBorderSize(8, 100.0));
        assertEquals(64.0, EventMode.calculateDynamicBorderSize(12, 100.0));
        assertEquals(78.0, EventMode.calculateDynamicBorderSize(20, 100.0));
        assertEquals(100.0, EventMode.calculateDynamicBorderSize(30, 100.0));
        // Clamps cleanly to smaller arenas (e.g. 76-block diameter Ironkeep)
        assertEquals(76.0, EventMode.calculateDynamicBorderSize(20, 76.0));

        assertEquals(8, EventMode.kitModes().size());
        assertTrue(EventMode.DUEL.supportsKitSelection());
        assertTrue(EventMode.TEAM_WARS.supportsKitSelection());
        assertTrue(EventMode.TOURNAMENT.supportsKitSelection());
        assertFalse(EventMode.NETHPOT.supportsKitSelection());
    }
}
