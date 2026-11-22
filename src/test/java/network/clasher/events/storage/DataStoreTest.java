package network.clasher.events.storage;

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
    void persistsAudiencePointsStatsAndMatchHistoryAtomically(@TempDir Path tempDir) {
        Logger logger = Logger.getLogger("DataStoreTest");
        DataStore store = new DataStore(tempDir.toFile(), logger);

        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        store.addAudiencePoints(alice, "Alice", 10);
        store.addAudiencePoints(bob, "Bob", 30);
        store.addAudiencePoints(alice, "Alice", 15);

        List<DataStore.LeaderboardEntry> top = store.topAudiencePoints(5);
        assertEquals(2, top.size());
        assertEquals("Bob", top.get(0).name());
        assertEquals(30, top.get(0).points());
        assertEquals("Alice", top.get(1).name());
        assertEquals(25, top.get(1).points());

        store.recordKill(alice, "Alice", bob, "Bob");
        store.recordWin(alice, "Alice");
        store.recordMatch("nethpot", "nethpot", "Alice", alice.toString(), System.currentTimeMillis() - 90_000L, Set.of(alice, bob), "Alice", 1);

        DataStore reloaded = new DataStore(tempDir.toFile(), logger);
        DataStore.PlayerStats aliceStats = reloaded.stats(alice);
        assertEquals(1, aliceStats.wins());
        assertEquals(1, aliceStats.kills());
        assertEquals(0, aliceStats.deaths());

        DataStore.PlayerStats bobStats = reloaded.stats(bob);
        assertEquals(0, bobStats.wins());
        assertEquals(0, bobStats.kills());
        assertEquals(1, bobStats.deaths());

        List<DataStore.MatchSummary> history = reloaded.recentMatches(5);
        assertEquals(1, history.size());
        assertEquals("Alice", history.get(0).winner());
        assertEquals("nethpot", history.get(0).arena());
    }
}
