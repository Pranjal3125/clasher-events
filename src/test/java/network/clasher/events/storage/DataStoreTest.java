package network.clasher.events.storage;

import network.clasher.events.stats.EloCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

final class DataStoreTest {

    @Test
    void persistsAsyncAudiencePointsStatsEloStreaksAndMatchHistory(@TempDir Path tempDir) {
        Logger logger = Logger.getLogger("DataStoreTest");
        DataStore store = new DataStore(tempDir.toFile(), logger);
        try {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            store.addAudiencePoints(alice, "Alice", 10);
            store.addAudiencePoints(bob, "Bob", 30);
            store.addAudiencePoints(alice, "Alice", 15);

            List<DataStore.LeaderboardEntry> topPts = store.topAudiencePoints(5);
            assertEquals(2, topPts.size());
            assertEquals("Bob", topPts.get(0).name());
            assertEquals(30, topPts.get(0).points());
            assertEquals("Alice", topPts.get(1).name());
            assertEquals(25, topPts.get(1).points());

            EloCalculator.EloDelta delta = store.recordKill(alice, "Alice", bob, "Bob");
            assertTrue(delta.gained() > 0);
            store.recordWin(alice, "Alice");
            store.recordWin(alice, "Alice");
            store.awardBonusElo(alice, "Alice", EloCalculator.BOUNTY_BONUS);
            store.saveKitLayout(alice, "nethpot", java.util.Map.of(0, 4, 1, 8, 40, 2));
            DataStore.ParkourResult pk = store.recordParkourTime(alice, "Alice", 14_250L);
            assertTrue(pk.personalBest());
            assertTrue(pk.serverRecord());
            store.recordPodium("NethPot", "Alice", "Bob", "---", "Alice", 3);
            store.recordMatch("nethpot", "nethpot", "Alice", alice.toString(), System.currentTimeMillis() - 90_000L, Set.of(alice, bob), "Alice", 1);

            store.flush();

            DataStore reloaded = new DataStore(tempDir.toFile(), logger);
            try {
                DataStore.PlayerStats aliceStats = reloaded.stats(alice);
                assertEquals(2, aliceStats.wins());
                assertEquals(1, aliceStats.kills());
                assertEquals(0, aliceStats.deaths());
                assertEquals(2, aliceStats.winStreak());
                assertEquals(2, aliceStats.bestStreak());
                assertTrue(aliceStats.elo() > EloCalculator.DEFAULT_ELO);
                assertEquals(14_250L, reloaded.parkourBestMs(alice));
                assertEquals(java.util.Map.of(0, 4, 1, 8, 40, 2), reloaded.kitLayout(alice, "nethpot"));
                assertEquals("Alice", reloaded.lastPodium().first());
                assertEquals("Bob", reloaded.lastPodium().second());
                assertNotNull(reloaded.findStatsByName("alice"));

                DataStore.PlayerStats bobStats = reloaded.stats(bob);
                assertEquals(0, bobStats.wins());
                assertEquals(0, bobStats.kills());
                assertEquals(1, bobStats.deaths());
                assertEquals(0, bobStats.winStreak());
                assertTrue(bobStats.elo() < EloCalculator.DEFAULT_ELO);

                assertEquals("Alice", reloaded.topWins(5).get(0).name());
                assertEquals("Alice", reloaded.topKills(5).get(0).name());
                assertEquals("Alice", reloaded.topElo(5).get(0).name());

                List<DataStore.MatchSummary> history = reloaded.recentMatches(5);
                assertEquals(1, history.size());
                assertEquals("Alice", history.get(0).winner());
                assertEquals("nethpot", history.get(0).arena());
            } finally {
                reloaded.flushAndShutdown();
            }
        } finally {
            store.flushAndShutdown();
        }
    }
}
