package network.clasher.events.match;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class TournamentSquadManagerTest {

    @Test
    void parsesAllTeamSizeAliasesAndSanitizesNames() {
        assertEquals(1, TournamentSquadManager.parseTeamSize("1v1", 2));
        assertEquals(2, TournamentSquadManager.parseTeamSize("2v2", 1));
        assertEquals(2, TournamentSquadManager.parseTeamSize("duo", 1));
        assertEquals(3, TournamentSquadManager.parseTeamSize("3v3", 1));
        assertEquals(4, TournamentSquadManager.parseTeamSize("4v4", 1));
        assertEquals(4, TournamentSquadManager.parseTeamSize("squad", 1));
        assertEquals(2, TournamentSquadManager.parseTeamSize("unknown", 2));

        assertEquals("Team Phoenix", TournamentSquadManager.sanitizeTeamName("<red><bold>Team Phoenix</bold></red>"));
        assertTrue(TournamentSquadManager.sanitizeTeamName("VeryLongEsportsSquadName12345").length() <= 16);
    }

    @Test
    void handlesInvitesAcceptDeclineRenameKickLeaveAndLocking() {
        TournamentSquadManager mgr = new TournamentSquadManager();
        mgr.reset(3, true);

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        UUID p4 = UUID.randomUUID();
        mgr.registerPlayer(p1, "Pranjal");
        mgr.registerPlayer(p2, "Alex");
        mgr.registerPlayer(p3, "Steve");
        mgr.registerPlayer(p4, "Rohan");

        long t0 = 1_000_000L;
        assertTrue(mgr.sendInvite(p1, p2, t0, 60_000L).ok());
        assertFalse(mgr.acceptInvite(p2, p1, t0 + 70_000L).ok(), "Expired invite must fail");

        assertTrue(mgr.sendInvite(p1, p2, t0, 60_000L).ok());
        var joined = mgr.acceptInvite(p2, p1, t0 + 5_000L);
        assertTrue(joined.ok());
        assertEquals(2, joined.team().size());
        assertEquals(joined.team().id(), mgr.teamOf(p2).id());

        // Rename by leader succeeds; non-leader fails
        assertFalse(mgr.renameSquad(p2, "Hackers").ok());
        assertTrue(mgr.renameSquad(p1, "<gold>Clasher Kings</gold>").ok());
        assertEquals("Clasher Kings", mgr.teamOf(p1).name());

        // Fill 3rd slot, then reject 4th slot
        assertTrue(mgr.sendInvite(p1, p3, t0, 60_000L).ok());
        assertTrue(mgr.acceptInvite(p3, null, t0 + 2_000L).ok());
        assertEquals(3, mgr.teamOf(p1).size());
        assertFalse(mgr.sendInvite(p1, p4, t0, 60_000L).ok(), "Full 3/3 squad cannot invite 4th");

        // Lock prevents kick/leave
        mgr.setInvitesLocked(true);
        assertFalse(mgr.kickMember(p1, p3, "Steve").ok());
        assertFalse(mgr.leaveSquad(p2, "Alex").ok());

        // Unlock allows kick & leave cleanly
        mgr.setInvitesLocked(false);
        assertTrue(mgr.kickMember(p1, p3, "Steve").ok());
        assertEquals(2, mgr.teamOf(p1).size());
        assertEquals(1, mgr.teamOf(p3).size());

        assertTrue(mgr.leaveSquad(p2, "Alex").ok());
        assertEquals(1, mgr.teamOf(p1).size());
        assertEquals(1, mgr.teamOf(p2).size());
    }

    @Test
    void autoFillPreservesFriendsAndPacksSolosCleanly() {
        TournamentSquadManager mgr = new TournamentSquadManager();
        mgr.reset(3, true);

        Map<UUID, String> names = new LinkedHashMap<>();
        UUID[] p = new UUID[6];
        for (int i = 0; i < 6; i++) {
            p[i] = UUID.randomUUID();
            names.put(p[i], "Player" + (i + 1));
            mgr.registerPlayer(p[i], "Player" + (i + 1));
        }

        // p0 and p1 are friends in a 2/3 squad; p2..p5 are solo
        assertTrue(mgr.forcePair(p[0], "Player1", p[1], "Player2").ok());
        int friendTeamId = mgr.teamOf(p[0]).id();

        int moved = mgr.autoFillIncompleteTeams(names.keySet(), names::get, new Random(42));
        assertTrue(moved >= 2);
        assertEquals(2, mgr.allTeams().size(), "6 players in 3v3 must form exactly 2 full squads");
        assertEquals(2, mgr.readyTeamCount());
        // Friends MUST stay together on the same squad!
        assertEquals(friendTeamId, mgr.teamOf(p[0]).id());
        assertEquals(friendTeamId, mgr.teamOf(p[1]).id());
        assertEquals(3, mgr.teamOf(p[0]).size());
    }

    @Test
    void runsFullMultiTeamBracketWithByeAndBestOf3GrandFinals() {
        TournamentSquadManager mgr = new TournamentSquadManager();
        mgr.reset(2, true); // 2v2 with Best-of-3 Grand Finals!

        Map<UUID, String> names = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            UUID id = UUID.randomUUID();
            names.put(id, "P" + (i + 1));
            mgr.registerPlayer(id, "P" + (i + 1));
        }
        int created = mgr.shuffleBalancedTeams(List.copyOf(names.keySet()), names::get, new Random(7));
        assertEquals(3, created, "6 players in 2v2 -> 3 squads (tests odd-team Bye!)");

        Random rng = new Random(99);
        int entrants = mgr.startBracket(names.keySet(), rng);
        assertEquals(3, entrants);
        assertNotNull(mgr.lastByeTeam(), "3 squads must award 1 Bye to Round 2");

        // Round 1: Single semifinal (not Grand Final because Bye team waits in roundWinners!)
        var r1 = mgr.nextMatchup(rng);
        assertNotNull(r1);
        assertFalse(r1.grandFinal(), "Semifinal with waiting Bye must NOT be Grand Final");
        assertFalse(r1.bo3Series());
        var r1Res = mgr.recordMatchWinner(r1.teamA().id());
        assertEquals(TournamentSquadManager.MatchOutcome.TEAM_ADVANCES, r1Res.outcome());

        // Round 2: GRAND FINAL (Best-of-3!)
        var gf1 = mgr.nextMatchup(rng);
        assertNotNull(gf1);
        assertEquals(2, gf1.round());
        assertTrue(gf1.grandFinal(), "Final 2 squads must trigger Grand Final!");
        assertTrue(gf1.bo3Series(), "Bo3 Grand Final must be active!");
        assertEquals(1, gf1.gameNumber());

        // Game 1 -> Team A wins (1-0) -> SERIES_CONTINUES
        var g1Res = mgr.recordMatchWinner(gf1.teamA().id());
        assertEquals(TournamentSquadManager.MatchOutcome.SERIES_CONTINUES, g1Res.outcome());
        assertEquals(1, g1Res.winsA());
        assertEquals(0, g1Res.winsB());

        // Game 2 -> Team B ties (1-1) -> SERIES_CONTINUES
        var gf2 = mgr.nextMatchup(rng);
        assertNotNull(gf2);
        assertEquals(2, gf2.gameNumber());
        assertEquals(gf1.teamA().id(), gf2.teamA().id());
        var g2Res = mgr.recordMatchWinner(gf2.teamB().id());
        assertEquals(TournamentSquadManager.MatchOutcome.SERIES_CONTINUES, g2Res.outcome());
        assertEquals(1, g2Res.winsA());
        assertEquals(1, g2Res.winsB());

        // Game 3 -> Team B wins (1-2) -> TOURNAMENT_CHAMPION!
        var gf3 = mgr.nextMatchup(rng);
        assertNotNull(gf3);
        assertEquals(3, gf3.gameNumber());
        var g3Res = mgr.recordMatchWinner(gf3.teamB().id());
        assertEquals(TournamentSquadManager.MatchOutcome.TOURNAMENT_CHAMPION, g3Res.outcome());
        assertEquals(gf3.teamB().id(), mgr.championTeam().id());
        assertEquals(gf3.teamA().id(), mgr.runnerUpTeam().id());
        assertNotNull(mgr.thirdPlaceTeam(), "3rd place semifinalist must be tracked for Bronze Podium!");
    }
}
