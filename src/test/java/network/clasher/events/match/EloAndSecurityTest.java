package network.clasher.events.match;

import network.clasher.events.model.Arena;
import network.clasher.events.notify.DiscordWebhookNotifier;
import network.clasher.events.schedule.EventScheduler;
import network.clasher.events.stats.EloCalculator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class EloAndSecurityTest {

    @Test
    void eloCalculatorRewardsUpsetsAndClampsFloor() {
        EloCalculator.EloDelta even = EloCalculator.calculateKill(1000, 1000);
        assertEquals(16, even.gained());
        assertEquals(1016, even.newWinnerElo());
        assertEquals(984, even.newLoserElo());

        // Underdog defeating higher-rated opponent earns bigger gain
        EloCalculator.EloDelta upset = EloCalculator.calculateKill(900, 1400);
        assertTrue(upset.gained() > even.gained());

        // Floor clamp prevents negative or sub-100 Elo
        EloCalculator.EloDelta floor = EloCalculator.calculateKill(1200, 102);
        assertEquals(EloCalculator.MIN_ELO, floor.newLoserElo());

        assertEquals(1020, EloCalculator.applyWinBonus(1000));
        assertEquals("2.50", EloCalculator.formatKd(5, 2));
        assertEquals("3.00", EloCalculator.formatKd(3, 0));
    }

    @Test
    void commandGuardBlocksSmugglingAndNamespacedBypassCommands() {
        List<String> allowed = List.of("event", "ce", "msg", "r", "help");

        assertTrue(CommandGuard.isCommandAllowed("/event join", allowed));
        assertTrue(CommandGuard.isCommandAllowed("/Event Leave", allowed));
        assertTrue(CommandGuard.isCommandAllowed("/msg Steve gg", allowed));

        // Blocks economy/kit smuggling and namespaced bypasses!
        assertFalse(CommandGuard.isCommandAllowed("/ec", allowed));
        assertFalse(CommandGuard.isCommandAllowed("/essentials:enderchest", allowed));
        assertFalse(CommandGuard.isCommandAllowed("/ah sell 1000", allowed));
        assertFalse(CommandGuard.isCommandAllowed("/pv 1", allowed));
        assertFalse(CommandGuard.isCommandAllowed("/home base", allowed));
        assertFalse(CommandGuard.isCommandAllowed("/minecraft:tp Steve", allowed));
        assertFalse(CommandGuard.isCommandAllowed("/spawn", allowed));
    }

    @Test
    void arenaComputesTrueBoundsCenterAndContainment() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("bounds.min-x", 260);
        yml.set("bounds.max-x", 340);
        yml.set("bounds.min-y", 79);
        yml.set("bounds.max-y", 120);
        yml.set("bounds.min-z", -40);
        yml.set("bounds.max-z", 40);

        Arena arena = new Arena("axe-shield", "Ironkeep", "axe-shield.schem", 260, 80, -40, yml);
        // Even if paste origin is a corner, centerX/centerZ use the true bounds center!
        assertEquals(300.5, arena.centerX(), 0.001);
        assertEquals(0.5, arena.centerZ(), 0.001);
        assertTrue(arena.contains(300, 85, 0));
        assertFalse(arena.contains(100, 85, 0));
        assertFalse(arena.contains(300, 150, 0));
    }

    @Test
    void schedulerMatchesFixedTimesAndRotatesModes() {
        assertTrue(EventScheduler.matchesFixedTime(LocalTime.of(18, 0), List.of("14:00", "18:00", "21:30")));
        assertFalse(EventScheduler.matchesFixedTime(LocalTime.of(18, 1), List.of("14:00", "18:00")));

        List<String> rot = List.of("nethpot", "crystal", "build-uhc");
        assertEquals("nethpot", EventScheduler.selectNextMode(rot, 0));
        assertEquals("crystal", EventScheduler.selectNextMode(rot, 1));
        assertEquals("build-uhc", EventScheduler.selectNextMode(rot, 2));
        assertEquals("nethpot", EventScheduler.selectNextMode(rot, 3));

        assertEquals("hello \\\"world\\\"\\nline2", DiscordWebhookNotifier.esc("hello \"world\"\nline2"));
    }
}
