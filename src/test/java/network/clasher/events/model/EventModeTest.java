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
}
