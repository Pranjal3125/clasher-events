package network.clasher.events.match;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Pure esports Team Tournament engine supporting 1v1, 2v2, 3v3, and 4v4 formats:
 * - Timed squad formation with teammate invites, accept/decline, kick, leave, & custom team names
 * - Smart auto-fill of solo & incomplete squads without splitting existing friends
 * - Admin controls: force-pair, disband, balanced auto-shuffle, lock/unlock invites, Best-of-3 Grand Finals
 * - Single-elimination bracket with odd-team bye handling, live matchup snapshots, & Bo3 Grand Finals series
 */
public final class TournamentSquadManager {

    public static final class TournamentTeam {
        private final int id;
        private String name;
        private UUID leader;
        private final List<UUID> members = new ArrayList<>();
        private boolean eliminated;

        public TournamentTeam(int id, String name, UUID leader) {
            this.id = id;
            this.name = name;
            this.leader = leader;
            this.members.add(leader);
        }

        public int id() { return id; }
        public String name() { return name; }
        public void setName(String name) { this.name = name; }
        public UUID leader() { return leader; }
        public void setLeader(UUID leader) { this.leader = leader; }
        public List<UUID> members() { return Collections.unmodifiableList(members); }
        public int size() { return members.size(); }
        public boolean contains(UUID playerId) { return members.contains(playerId); }
        public boolean isEliminated() { return eliminated; }
        public void setEliminated(boolean eliminated) { this.eliminated = eliminated; }

        void addMember(UUID id) {
            if (!members.contains(id)) members.add(id);
        }

        boolean removeMember(UUID id) {
            boolean removed = members.remove(id);
            if (removed && id.equals(leader) && !members.isEmpty()) {
                leader = members.get(0);
            }
            return removed;
        }
    }

    public record SquadInvite(int teamId, UUID leaderId, UUID targetId, long expiresAtMs) {}

    public record ActionResult(boolean ok, String message, TournamentTeam team, UUID relatedPlayer) {
        public static ActionResult success(String msg, TournamentTeam team) {
            return new ActionResult(true, msg, team, null);
        }
        public static ActionResult success(String msg, TournamentTeam team, UUID related) {
            return new ActionResult(true, msg, team, related);
        }
        public static ActionResult fail(String msg) {
            return new ActionResult(false, msg, null, null);
        }
    }

    public record MatchPair(TournamentTeam teamA, TournamentTeam teamB) {}

    public record ActiveMatchup(
            TournamentTeam teamA,
            TournamentTeam teamB,
            int round,
            boolean grandFinal,
            boolean bo3Series,
            int gameNumber,
            int winsA,
            int winsB
    ) {
        public String seriesBadge() {
            if (!grandFinal) return "Round " + round;
            if (!bo3Series) return "GRAND FINAL";
            return "GRAND FINAL (Bo3 • Game " + gameNumber + " • " + winsA + "-" + winsB + ")";
        }
    }

    public enum MatchOutcome {
        SERIES_CONTINUES,
        TEAM_ADVANCES,
        TOURNAMENT_CHAMPION,
        DRAW
    }

    public record MatchResult(
            MatchOutcome outcome,
            TournamentTeam winner,
            TournamentTeam loser,
            int winsA,
            int winsB,
            int gameNumber,
            boolean grandFinal
    ) {}

    private int teamSize = 1;
    private boolean invitesLocked = false;
    private boolean bo3Finals = true;
    private boolean bracketStarted = false;
    private int nextTeamId = 1;

    private final Map<Integer, TournamentTeam> teamsById = new LinkedHashMap<>();
    private final Map<UUID, Integer> teamIdByPlayer = new HashMap<>();
    // target UUID -> (leader UUID -> invite)
    private final Map<UUID, Map<UUID, SquadInvite>> invitesByTarget = new HashMap<>();

    // Bracket state
    private int currentRound = 1;
    private final Deque<MatchPair> matchQueue = new ArrayDeque<>();
    private final List<TournamentTeam> roundWinners = new ArrayList<>();
    private final List<TournamentTeam> eliminatedTeams = new ArrayList<>();
    private TournamentTeam lastByeTeam;
    private TournamentTeam currentTeamA;
    private TournamentTeam currentTeamB;
    private boolean currentIsGrandFinal;
    private boolean bo3PendingNextGame;
    private int currentSeriesGame = 1;
    private int currentWinsA = 0;
    private int currentWinsB = 0;
    private TournamentTeam championTeam;
    private TournamentTeam runnerUpTeam;
    private TournamentTeam thirdPlaceTeam;

    public void reset(int teamSize, boolean bo3Finals) {
        this.teamSize = Math.max(1, Math.min(4, teamSize));
        this.bo3Finals = bo3Finals;
        this.invitesLocked = false;
        this.bracketStarted = false;
        this.nextTeamId = 1;
        this.teamsById.clear();
        this.teamIdByPlayer.clear();
        this.invitesByTarget.clear();
        this.currentRound = 1;
        this.matchQueue.clear();
        this.roundWinners.clear();
        this.eliminatedTeams.clear();
        this.lastByeTeam = null;
        this.currentTeamA = null;
        this.currentTeamB = null;
        this.currentIsGrandFinal = false;
        this.bo3PendingNextGame = false;
        this.currentSeriesGame = 1;
        this.currentWinsA = 0;
        this.currentWinsB = 0;
        this.championTeam = null;
        this.runnerUpTeam = null;
        this.thirdPlaceTeam = null;
    }

    public int teamSize() { return teamSize; }
    public boolean isTeamMode() { return teamSize > 1; }
    public String formatLabel() { return teamSize + "v" + teamSize; }
    public boolean isInvitesLocked() { return invitesLocked; }
    public void setInvitesLocked(boolean locked) { this.invitesLocked = locked; }
    public boolean isBo3Finals() { return bo3Finals; }
    public void setBo3Finals(boolean bo3Finals) { this.bo3Finals = bo3Finals; }
    public boolean isBracketStarted() { return bracketStarted; }
    public int currentRound() { return currentRound; }
    public TournamentTeam lastByeTeam() { return lastByeTeam; }
    public TournamentTeam championTeam() { return championTeam; }
    public TournamentTeam runnerUpTeam() { return runnerUpTeam; }
    public TournamentTeam thirdPlaceTeam() { return thirdPlaceTeam; }

    public TournamentTeam teamOf(UUID playerId) {
        Integer tid = teamIdByPlayer.get(playerId);
        return tid == null ? null : teamsById.get(tid);
    }

    public TournamentTeam teamById(int teamId) {
        return teamsById.get(teamId);
    }

    public List<TournamentTeam> allTeams() {
        return new ArrayList<>(teamsById.values());
    }

    public int readyTeamCount() {
        int count = 0;
        for (TournamentTeam t : teamsById.values()) {
            if (t.size() >= teamSize) count++;
        }
        return count;
    }

    public int incompleteTeamCount() {
        int count = 0;
        for (TournamentTeam t : teamsById.values()) {
            if (t.size() > 0 && t.size() < teamSize) count++;
        }
        return count;
    }

    public List<MatchPair> upcomingMatchups() {
        return new ArrayList<>(matchQueue);
    }

    public List<TournamentTeam> roundWinners() {
        return Collections.unmodifiableList(roundWinners);
    }

    public List<TournamentTeam> eliminatedTeams() {
        return Collections.unmodifiableList(eliminatedTeams);
    }

    public ActiveMatchup currentMatchup() {
        if (currentTeamA == null || currentTeamB == null) return null;
        return new ActiveMatchup(
                currentTeamA, currentTeamB, currentRound, currentIsGrandFinal,
                currentIsGrandFinal && bo3Finals, currentSeriesGame, currentWinsA, currentWinsB
        );
    }

    // -------------------------------------------------------------------------
    // Player Registration & Squad Formation
    // -------------------------------------------------------------------------

    public TournamentTeam registerPlayer(UUID playerId, String playerName) {
        TournamentTeam existing = teamOf(playerId);
        if (existing != null) return existing;
        String defaultName = defaultTeamName(playerName);
        int id = nextTeamId++;
        TournamentTeam team = new TournamentTeam(id, defaultName, playerId);
        teamsById.put(id, team);
        teamIdByPlayer.put(playerId, id);
        return team;
    }

    public void unregisterPlayer(UUID playerId) {
        invitesByTarget.remove(playerId);
        for (Map<UUID, SquadInvite> map : invitesByTarget.values()) {
            map.remove(playerId);
        }
        Integer tid = teamIdByPlayer.remove(playerId);
        if (tid == null) return;
        TournamentTeam team = teamsById.get(tid);
        if (team != null) {
            team.removeMember(playerId);
            if (team.size() == 0) {
                teamsById.remove(tid);
            }
        }
    }

    public ActionResult sendInvite(UUID leaderId, UUID targetId, long nowMs, long expireMs) {
        if (bracketStarted) return ActionResult.fail("Tournament bracket has already started!");
        if (teamSize <= 1) return ActionResult.fail("This is a 1v1 solo tournament — squads are not used.");
        if (invitesLocked) return ActionResult.fail("Squad changes are currently locked by an admin.");
        if (leaderId.equals(targetId)) return ActionResult.fail("You cannot invite yourself!");

        TournamentTeam leaderTeam = teamOf(leaderId);
        if (leaderTeam == null) return ActionResult.fail("You must join the tournament first (/event join).");
        if (!leaderId.equals(leaderTeam.leader())) return ActionResult.fail("Only your squad leader can invite teammates.");
        if (leaderTeam.size() >= teamSize) {
            return ActionResult.fail("Your squad is already full (" + leaderTeam.size() + "/" + teamSize + ")!");
        }

        TournamentTeam targetTeam = teamOf(targetId);
        if (targetTeam == null) return ActionResult.fail("That player hasn't joined the tournament yet!");
        if (targetTeam.id() == leaderTeam.id()) return ActionResult.fail("That player is already in your squad!");
        if (targetTeam.size() > 1) {
            return ActionResult.fail("That player is already in another squad — they must /event squad leave first.");
        }

        SquadInvite invite = new SquadInvite(leaderTeam.id(), leaderId, targetId, nowMs + Math.max(10_000L, expireMs));
        invitesByTarget.computeIfAbsent(targetId, k -> new LinkedHashMap<>()).put(leaderId, invite);
        return ActionResult.success("Squad invite sent!", leaderTeam, targetId);
    }

    public ActionResult acceptInvite(UUID targetId, UUID optionalLeaderId, long nowMs) {
        if (bracketStarted) return ActionResult.fail("Tournament bracket has already started!");
        if (invitesLocked) return ActionResult.fail("Squad changes are currently locked by an admin.");

        Map<UUID, SquadInvite> pending = invitesByTarget.get(targetId);
        if (pending == null || pending.isEmpty()) {
            return ActionResult.fail("You don't have any pending squad invites.");
        }
        // Purge expired invites first
        pending.values().removeIf(inv -> nowMs > inv.expiresAtMs());
        if (pending.isEmpty()) {
            invitesByTarget.remove(targetId);
            return ActionResult.fail("Your squad invite has expired.");
        }

        SquadInvite chosen = null;
        if (optionalLeaderId != null) {
            chosen = pending.get(optionalLeaderId);
            if (chosen == null) return ActionResult.fail("No active squad invite from that player.");
        } else {
            // Pick most recent invite
            for (SquadInvite inv : pending.values()) chosen = inv;
        }

        TournamentTeam targetTeam = teamOf(targetId);
        if (targetTeam == null) return ActionResult.fail("You are not registered in the tournament.");
        if (targetTeam.size() > 1) {
            return ActionResult.fail("Leave your current squad first (/event squad leave) before joining another.");
        }

        TournamentTeam destTeam = teamsById.get(chosen.teamId());
        if (destTeam == null || !destTeam.contains(chosen.leaderId())) {
            pending.remove(chosen.leaderId());
            return ActionResult.fail("That squad no longer exists.");
        }
        if (destTeam.size() >= teamSize) {
            pending.remove(chosen.leaderId());
            return ActionResult.fail("That squad is already full (" + destTeam.size() + "/" + teamSize + ")!");
        }

        // Move target from their solo squad into destination squad
        teamsById.remove(targetTeam.id());
        destTeam.addMember(targetId);
        teamIdByPlayer.put(targetId, destTeam.id());
        invitesByTarget.remove(targetId);
        // Cancel any outgoing invites target previously sent as a solo leader
        for (Map<UUID, SquadInvite> m : invitesByTarget.values()) m.remove(targetId);

        return ActionResult.success("Joined squad " + destTeam.name() + "!", destTeam, chosen.leaderId());
    }

    public ActionResult declineInvite(UUID targetId, UUID optionalLeaderId) {
        Map<UUID, SquadInvite> pending = invitesByTarget.get(targetId);
        if (pending == null || pending.isEmpty()) {
            return ActionResult.fail("You have no pending squad invite to decline.");
        }
        SquadInvite removed;
        if (optionalLeaderId != null) {
            removed = pending.remove(optionalLeaderId);
        } else {
            UUID lastKey = null;
            for (UUID k : pending.keySet()) lastKey = k;
            removed = lastKey == null ? null : pending.remove(lastKey);
        }
        if (pending.isEmpty()) invitesByTarget.remove(targetId);
        if (removed == null) return ActionResult.fail("No pending invite from that player.");
        return ActionResult.success("Declined squad invite.", teamsById.get(removed.teamId()), removed.leaderId());
    }

    public ActionResult leaveSquad(UUID memberId, String memberName) {
        if (bracketStarted) return ActionResult.fail("You cannot leave your squad after the bracket has started!");
        if (invitesLocked) return ActionResult.fail("Squad changes are currently locked by an admin.");

        TournamentTeam current = teamOf(memberId);
        if (current == null) return ActionResult.fail("You are not registered in the tournament.");
        if (current.size() <= 1) return ActionResult.fail("You are already solo! Use /event leave to exit the tournament.");

        current.removeMember(memberId);
        if (current.size() == 0) teamsById.remove(current.id());

        int newId = nextTeamId++;
        TournamentTeam solo = new TournamentTeam(newId, defaultTeamName(memberName), memberId);
        teamsById.put(newId, solo);
        teamIdByPlayer.put(memberId, newId);
        return ActionResult.success("Left squad " + current.name() + " — you are now solo.", current, memberId);
    }

    public ActionResult kickMember(UUID leaderId, UUID targetId, String targetName) {
        if (bracketStarted) return ActionResult.fail("Cannot kick members after the bracket has started!");
        if (invitesLocked) return ActionResult.fail("Squad changes are currently locked by an admin.");
        if (leaderId.equals(targetId)) return ActionResult.fail("You cannot kick yourself — use /event squad leave.");

        TournamentTeam team = teamOf(leaderId);
        if (team == null) return ActionResult.fail("You are not in the tournament.");
        if (!leaderId.equals(team.leader())) return ActionResult.fail("Only your squad leader can kick teammates.");
        if (!team.contains(targetId)) return ActionResult.fail("That player is not in your squad.");

        team.removeMember(targetId);
        int newId = nextTeamId++;
        TournamentTeam solo = new TournamentTeam(newId, defaultTeamName(targetName), targetId);
        teamsById.put(newId, solo);
        teamIdByPlayer.put(targetId, newId);
        return ActionResult.success("Removed player from " + team.name() + ".", team, targetId);
    }

    public ActionResult renameSquad(UUID leaderId, String rawName) {
        if (teamSize <= 1) return ActionResult.fail("Custom squad names are only available in team tournaments (2v2/3v3/4v4).");
        TournamentTeam team = teamOf(leaderId);
        if (team == null) return ActionResult.fail("You are not registered in the tournament.");
        if (!leaderId.equals(team.leader())) return ActionResult.fail("Only your squad leader can rename the team.");
        String clean = sanitizeTeamName(rawName);
        if (clean.length() < 2) return ActionResult.fail("Team name must be 2-16 letters/numbers.");
        team.setName(clean);
        return ActionResult.success("Squad renamed to " + clean + "!", team);
    }

    // -------------------------------------------------------------------------
    // Admin Controls & Smart Auto-Fill
    // -------------------------------------------------------------------------

    public ActionResult forcePair(UUID playerA, String nameA, UUID playerB, String nameB) {
        if (bracketStarted) return ActionResult.fail("Bracket has already started.");
        if (teamSize <= 1) return ActionResult.fail("Cannot pair teammates in a 1v1 tournament.");
        if (playerA.equals(playerB)) return ActionResult.fail("Pick two different players.");

        TournamentTeam teamA = teamOf(playerA);
        TournamentTeam teamB = teamOf(playerB);
        if (teamA == null || teamB == null) {
            return ActionResult.fail("Both players must be registered in the tournament.");
        }
        if (teamA.id() == teamB.id()) {
            return ActionResult.fail("Both players are already on the same squad!");
        }

        // Remove B from B's current team
        teamB.removeMember(playerB);
        if (teamB.size() == 0) teamsById.remove(teamB.id());

        // If A's team is already full, split A out into a brand-new squad with B
        if (teamA.size() >= teamSize) {
            teamA.removeMember(playerA);
            if (teamA.size() == 0) teamsById.remove(teamA.id());
            int newId = nextTeamId++;
            teamA = new TournamentTeam(newId, defaultTeamName(nameA), playerA);
            teamsById.put(newId, teamA);
            teamIdByPlayer.put(playerA, newId);
        }

        teamA.addMember(playerB);
        teamIdByPlayer.put(playerB, teamA.id());
        invitesByTarget.remove(playerB);
        return ActionResult.success("Paired " + nameA + " and " + nameB + " into " + teamA.name() + " (" + teamA.size() + "/" + teamSize + ")!", teamA);
    }

    public ActionResult disbandTeam(int teamId, Function<UUID, String> nameLookup) {
        if (bracketStarted) return ActionResult.fail("Cannot disband teams after bracket starts.");
        TournamentTeam team = teamsById.get(teamId);
        if (team == null) return ActionResult.fail("No team found with ID #" + teamId + ".");
        if (team.size() <= 1) return ActionResult.fail("That team only has 1 player.");

        List<UUID> members = new ArrayList<>(team.members());
        teamsById.remove(teamId);
        for (UUID member : members) {
            int nid = nextTeamId++;
            String name = nameLookup.apply(member);
            TournamentTeam solo = new TournamentTeam(nid, defaultTeamName(name), member);
            teamsById.put(nid, solo);
            teamIdByPlayer.put(member, nid);
        }
        return ActionResult.success("Disbanded " + team.name() + " into " + members.size() + " solo players.", team);
    }

    public int shuffleBalancedTeams(List<UUID> onlinePlayers, Function<UUID, String> nameLookup, Random rng) {
        if (bracketStarted) return 0;
        teamsById.clear();
        teamIdByPlayer.clear();
        invitesByTarget.clear();

        List<UUID> pool = new ArrayList<>(onlinePlayers);
        Collections.shuffle(pool, rng);
        int created = 0;
        for (int i = 0; i < pool.size(); i += teamSize) {
            UUID leader = pool.get(i);
            String leaderName = nameLookup.apply(leader);
            int id = nextTeamId++;
            TournamentTeam team = new TournamentTeam(id, defaultTeamName(leaderName), leader);
            teamIdByPlayer.put(leader, id);
            for (int j = i + 1; j < Math.min(pool.size(), i + teamSize); j++) {
                UUID m = pool.get(j);
                team.addMember(m);
                teamIdByPlayer.put(m, id);
            }
            teamsById.put(id, team);
            created++;
        }
        return created;
    }

    /**
     * Merges solo & incomplete squads cleanly into full squads while preserving already-formed friends!
     * Returns the number of players who were auto-assigned to fill teams.
     */
    public int autoFillIncompleteTeams(Set<UUID> onlinePlayers, Function<UUID, String> nameLookup, Random rng) {
        // First prune offline members & remove empty squads
        for (TournamentTeam team : new ArrayList<>(teamsById.values())) {
            for (UUID m : new ArrayList<>(team.members())) {
                if (!onlinePlayers.contains(m)) {
                    team.removeMember(m);
                    teamIdByPlayer.remove(m);
                }
            }
            if (team.size() == 0) teamsById.remove(team.id());
        }
        // Register any online participant who somehow lacked a team
        for (UUID p : onlinePlayers) {
            if (!teamIdByPlayer.containsKey(p)) {
                registerPlayer(p, nameLookup.apply(p));
            }
        }
        if (teamSize <= 1) return 0;

        // Separate partially-filled friend squads (size 2..teamSize-1) from pure solos (size 1)
        List<TournamentTeam> partials = new ArrayList<>();
        List<TournamentTeam> soloTeams = new ArrayList<>();
        for (TournamentTeam t : teamsById.values()) {
            if (t.size() == 1) soloTeams.add(t);
            else if (t.size() < teamSize) partials.add(t);
        }
        partials.sort(Comparator.comparingInt(TournamentTeam::size).reversed());
        Collections.shuffle(soloTeams, rng);

        int moved = 0;
        Deque<UUID> freeSolos = new ArrayDeque<>();
        for (TournamentTeam st : soloTeams) {
            freeSolos.addLast(st.leader());
            teamsById.remove(st.id());
        }

        // 1. Top up existing partial friend squads using free solos
        for (TournamentTeam partial : partials) {
            while (partial.size() < teamSize && !freeSolos.isEmpty()) {
                UUID solo = freeSolos.removeFirst();
                partial.addMember(solo);
                teamIdByPlayer.put(solo, partial.id());
                moved++;
            }
        }

        // 2. Pack remaining solos into brand-new balanced teams
        while (!freeSolos.isEmpty()) {
            UUID leader = freeSolos.removeFirst();
            int id = nextTeamId++;
            TournamentTeam fresh = new TournamentTeam(id, defaultTeamName(nameLookup.apply(leader)), leader);
            teamIdByPlayer.put(leader, id);
            while (fresh.size() < teamSize && !freeSolos.isEmpty()) {
                UUID m = freeSolos.removeFirst();
                fresh.addMember(m);
                teamIdByPlayer.put(m, id);
                moved++;
            }
            teamsById.put(id, fresh);
        }

        // 3. If multiple partial squads remain that can fit together (e.g. two 2-person teams in 4v4), combine them!
        List<TournamentTeam> stillPartial = new ArrayList<>();
        for (TournamentTeam t : teamsById.values()) {
            if (t.size() > 0 && t.size() < teamSize) stillPartial.add(t);
        }
        stillPartial.sort(Comparator.comparingInt(TournamentTeam::size).reversed());
        for (int i = 0; i < stillPartial.size(); i++) {
            TournamentTeam host = stillPartial.get(i);
            if (!teamsById.containsKey(host.id())) continue;
            for (int j = stillPartial.size() - 1; j > i; j--) {
                TournamentTeam guest = stillPartial.get(j);
                if (!teamsById.containsKey(guest.id())) continue;
                if (host.size() + guest.size() <= teamSize) {
                    for (UUID m : new ArrayList<>(guest.members())) {
                        host.addMember(m);
                        teamIdByPlayer.put(m, host.id());
                        moved++;
                    }
                    teamsById.remove(guest.id());
                }
            }
        }

        invitesByTarget.clear();
        return moved;
    }

    // -------------------------------------------------------------------------
    // Bracket Progression & Best-of-3 Grand Finals
    // -------------------------------------------------------------------------

    public int startBracket(Set<UUID> onlinePlayers, Random rng) {
        invitesByTarget.clear();
        invitesLocked = true;
        matchQueue.clear();
        roundWinners.clear();
        eliminatedTeams.clear();
        lastByeTeam = null;
        currentTeamA = null;
        currentTeamB = null;
        bo3PendingNextGame = false;
        currentIsGrandFinal = false;
        championTeam = null;
        runnerUpTeam = null;
        thirdPlaceTeam = null;

        List<TournamentTeam> entrants = new ArrayList<>();
        for (TournamentTeam team : teamsById.values()) {
            boolean anyOnline = false;
            for (UUID m : team.members()) {
                if (onlinePlayers.contains(m)) { anyOnline = true; break; }
            }
            if (anyOnline) {
                team.setEliminated(false);
                entrants.add(team);
            }
        }
        if (entrants.size() < 2) {
            bracketStarted = false;
            return entrants.size();
        }
        bracketStarted = true;
        currentRound = 1;
        seedRound(entrants, rng);
        return entrants.size();
    }

    private void seedRound(List<TournamentTeam> entrants, Random rng) {
        matchQueue.clear();
        roundWinners.clear();
        lastByeTeam = null;
        List<TournamentTeam> shuffled = new ArrayList<>(entrants);
        Collections.shuffle(shuffled, rng);
        if ((shuffled.size() & 1) == 1) {
            lastByeTeam = shuffled.remove(shuffled.size() - 1);
            roundWinners.add(lastByeTeam);
        }
        for (int i = 0; i + 1 < shuffled.size(); i += 2) {
            matchQueue.addLast(new MatchPair(shuffled.get(i), shuffled.get(i + 1)));
        }
    }

    public ActiveMatchup nextMatchup(Random rng) {
        if (!bracketStarted) return null;

        // Best-of-3 Grand Final next game?
        if (bo3PendingNextGame && currentTeamA != null && currentTeamB != null) {
            bo3PendingNextGame = false;
            return currentMatchup();
        }

        // Advance round if queue is empty
        if (matchQueue.isEmpty()) {
            if (roundWinners.size() == 1) {
                championTeam = roundWinners.get(0);
                return null;
            }
            if (roundWinners.size() < 2) {
                return null;
            }
            List<TournamentTeam> nextEntrants = new ArrayList<>(roundWinners);
            currentRound++;
            seedRound(nextEntrants, rng);
        }

        MatchPair pair = matchQueue.pollFirst();
        if (pair == null) return null;

        currentTeamA = pair.teamA();
        currentTeamB = pair.teamB();
        // Exact invariant: this matchup is the Grand Final iff no other matches remain and nobody holds a bye/earlier win
        currentIsGrandFinal = matchQueue.isEmpty() && roundWinners.isEmpty();
        currentSeriesGame = 1;
        currentWinsA = 0;
        currentWinsB = 0;
        bo3PendingNextGame = false;
        return currentMatchup();
    }

    public MatchResult recordMatchWinner(int winningTeamId) {
        if (currentTeamA == null || currentTeamB == null) {
            return new MatchResult(MatchOutcome.DRAW, null, null, 0, 0, currentSeriesGame, currentIsGrandFinal);
        }

        TournamentTeam winner;
        TournamentTeam loser;
        if (winningTeamId == currentTeamA.id()) {
            winner = currentTeamA;
            loser = currentTeamB;
            currentWinsA++;
        } else if (winningTeamId == currentTeamB.id()) {
            winner = currentTeamB;
            loser = currentTeamA;
            currentWinsB++;
        } else {
            // Draw / double disconnect
            int game = currentSeriesGame;
            boolean gf = currentIsGrandFinal;
            currentTeamA.setEliminated(true);
            currentTeamB.setEliminated(true);
            eliminatedTeams.add(currentTeamA);
            eliminatedTeams.add(currentTeamB);
            currentTeamA = null;
            currentTeamB = null;
            bo3PendingNextGame = false;
            return new MatchResult(MatchOutcome.DRAW, null, null, currentWinsA, currentWinsB, game, gf);
        }

        int gamePlayed = currentSeriesGame;
        boolean gf = currentIsGrandFinal;
        int wA = currentWinsA;
        int wB = currentWinsB;

        // Best-of-3 Grand Final series check (first to 2 wins!)
        if (gf && bo3Finals) {
            int winnerWins = (winner.id() == currentTeamA.id()) ? wA : wB;
            if (winnerWins < 2) {
                currentSeriesGame++;
                bo3PendingNextGame = true;
                return new MatchResult(MatchOutcome.SERIES_CONTINUES, winner, loser, wA, wB, gamePlayed, true);
            }
        }

        // Series / match decided!
        loser.setEliminated(true);
        eliminatedTeams.add(loser);
        roundWinners.add(winner);
        currentTeamA = null;
        currentTeamB = null;
        bo3PendingNextGame = false;

        if (matchQueue.isEmpty() && roundWinners.size() == 1) {
            championTeam = winner;
            runnerUpTeam = loser;
            if (eliminatedTeams.size() >= 2) {
                thirdPlaceTeam = eliminatedTeams.get(eliminatedTeams.size() - 2);
            }
            return new MatchResult(MatchOutcome.TOURNAMENT_CHAMPION, winner, loser, wA, wB, gamePlayed, gf);
        }
        return new MatchResult(MatchOutcome.TEAM_ADVANCES, winner, loser, wA, wB, gamePlayed, gf);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String defaultTeamName(String playerName) {
        String clean = sanitizeTeamName(playerName == null || playerName.isBlank() ? "Player" : playerName);
        if (clean.isEmpty()) clean = "Squad" + nextTeamId;
        if (teamSize == 1) return clean;
        String prefixed = "Team " + clean;
        return prefixed.length() <= 16 ? prefixed : clean.substring(0, Math.min(16, clean.length()));
    }

    public static String sanitizeTeamName(String raw) {
        if (raw == null) return "";
        String stripped = raw.replaceAll("<[^>]*>", "")
                .replaceAll("[^A-Za-z0-9 _\\-]", "")
                .trim()
                .replaceAll("\\s+", " ");
        if (stripped.length() > 16) stripped = stripped.substring(0, 16).trim();
        return stripped;
    }

    /**
     * Parses team size formats like "2v2", "3v3", "4v4", "1v1", "solo", "duo", "trio", "quad", or "1".."4".
     */
    public static int parseTeamSize(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "1", "1v1", "solo", "singles" -> 1;
            case "2", "2v2", "duo", "duos", "doubles" -> 2;
            case "3", "3v3", "trio", "trios" -> 3;
            case "4", "4v4", "quad", "quads", "squad", "squads" -> 4;
            default -> fallback;
        };
    }
}
