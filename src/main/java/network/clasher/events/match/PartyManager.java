package network.clasher.events.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure Java casual Party engine for 2v2 / 3v3 / 4v4 Party Duels & Party Split fights in Lobby!
 * Zero Bukkit dependencies — 100% unit-testable.
 */
public final class PartyManager {
    public static final int MAX_PARTY_SIZE = 8;

    public static final class Party {
        private final int id;
        private UUID leader;
        private final List<UUID> members = new ArrayList<>();

        public Party(int id, UUID leader) {
            this.id = id;
            this.leader = leader;
            this.members.add(leader);
        }

        public int id() { return id; }
        public UUID leader() { return leader; }
        public List<UUID> members() { return Collections.unmodifiableList(members); }
        public int size() { return members.size(); }
        public boolean contains(UUID id) { return members.contains(id); }

        void add(UUID id) {
            if (!members.contains(id)) members.add(id);
        }

        boolean remove(UUID id) {
            boolean ok = members.remove(id);
            if (ok && id.equals(leader) && !members.isEmpty()) {
                leader = members.get(0);
            }
            return ok;
        }
    }

    public record PartyInvite(int partyId, UUID leaderId, UUID targetId, long expiresAtMs) {}

    public record Result(boolean ok, String message, Party party, UUID related) {
        static Result ok(String msg, Party party) { return new Result(true, msg, party, null); }
        static Result ok(String msg, Party party, UUID rel) { return new Result(true, msg, party, rel); }
        static Result fail(String msg) { return new Result(false, msg, null, null); }
    }

    private int nextPartyId = 1;
    private final Map<Integer, Party> parties = new LinkedHashMap<>();
    private final Map<UUID, Integer> partyByPlayer = new HashMap<>();
    private final Map<UUID, Map<UUID, PartyInvite>> invitesByTarget = new HashMap<>();

    public Party partyOf(UUID playerId) {
        Integer pid = partyByPlayer.get(playerId);
        return pid == null ? null : parties.get(pid);
    }

    public Party getOrCreateParty(UUID leaderId) {
        Party existing = partyOf(leaderId);
        if (existing != null) return existing;
        int id = nextPartyId++;
        Party p = new Party(id, leaderId);
        parties.put(id, p);
        partyByPlayer.put(leaderId, id);
        return p;
    }

    public Result invite(UUID leaderId, UUID targetId, long nowMs, long expireMs) {
        if (leaderId.equals(targetId)) return Result.fail("You cannot invite yourself!");
        Party party = getOrCreateParty(leaderId);
        if (!leaderId.equals(party.leader())) return Result.fail("Only your party leader can invite players.");
        if (party.size() >= MAX_PARTY_SIZE) return Result.fail("Your party is full (max " + MAX_PARTY_SIZE + " players).");
        if (party.contains(targetId)) return Result.fail("That player is already in your party!");
        if (partyOf(targetId) != null) return Result.fail("That player is already in another party.");

        PartyInvite inv = new PartyInvite(party.id(), leaderId, targetId, nowMs + Math.max(10_000L, expireMs));
        invitesByTarget.computeIfAbsent(targetId, k -> new LinkedHashMap<>()).put(leaderId, inv);
        return Result.ok("Party invite sent!", party, targetId);
    }

    public Result accept(UUID targetId, UUID optionalLeader, long nowMs) {
        if (partyOf(targetId) != null) return Result.fail("Leave your current party first (/event party leave).");
        Map<UUID, PartyInvite> pending = invitesByTarget.get(targetId);
        if (pending == null || pending.isEmpty()) return Result.fail("You have no pending party invites.");
        pending.values().removeIf(inv -> nowMs > inv.expiresAtMs());
        if (pending.isEmpty()) {
            invitesByTarget.remove(targetId);
            return Result.fail("Your party invite has expired.");
        }
        PartyInvite chosen = null;
        if (optionalLeader != null) {
            chosen = pending.get(optionalLeader);
            if (chosen == null) return Result.fail("No pending party invite from that player.");
        } else {
            for (PartyInvite inv : pending.values()) chosen = inv;
        }
        Party party = parties.get(chosen.partyId());
        if (party == null || !party.contains(chosen.leaderId())) {
            pending.remove(chosen.leaderId());
            return Result.fail("That party no longer exists.");
        }
        if (party.size() >= MAX_PARTY_SIZE) {
            pending.remove(chosen.leaderId());
            return Result.fail("That party is now full!");
        }
        party.add(targetId);
        partyByPlayer.put(targetId, party.id());
        invitesByTarget.remove(targetId);
        return Result.ok("Joined the party!", party, chosen.leaderId());
    }

    public Result decline(UUID targetId, UUID optionalLeader) {
        Map<UUID, PartyInvite> pending = invitesByTarget.get(targetId);
        if (pending == null || pending.isEmpty()) return Result.fail("No pending party invite to decline.");
        PartyInvite rem;
        if (optionalLeader != null) rem = pending.remove(optionalLeader);
        else {
            UUID last = null;
            for (UUID k : pending.keySet()) last = k;
            rem = last == null ? null : pending.remove(last);
        }
        if (pending.isEmpty()) invitesByTarget.remove(targetId);
        if (rem == null) return Result.fail("No invite from that player.");
        return Result.ok("Declined party invite.", parties.get(rem.partyId()), rem.leaderId());
    }

    public Result leave(UUID memberId) {
        Party party = partyOf(memberId);
        if (party == null) return Result.fail("You are not in a party.");
        partyByPlayer.remove(memberId);
        party.remove(memberId);
        if (party.size() == 0) parties.remove(party.id());
        return Result.ok("You left the party.", party, memberId);
    }

    public Result kick(UUID leaderId, UUID targetId) {
        Party party = partyOf(leaderId);
        if (party == null) return Result.fail("You are not in a party.");
        if (!leaderId.equals(party.leader())) return Result.fail("Only the party leader can kick players.");
        if (leaderId.equals(targetId)) return Result.fail("Use /event party leave or disband instead.");
        if (!party.contains(targetId)) return Result.fail("That player is not in your party.");
        party.remove(targetId);
        partyByPlayer.remove(targetId);
        return Result.ok("Removed player from party.", party, targetId);
    }

    public Result disband(UUID leaderId) {
        Party party = partyOf(leaderId);
        if (party == null) return Result.fail("You are not in a party.");
        if (!leaderId.equals(party.leader())) return Result.fail("Only the party leader can disband the party.");
        List<UUID> oldMembers = new ArrayList<>(party.members());
        for (UUID m : oldMembers) partyByPlayer.remove(m);
        parties.remove(party.id());
        return Result.ok("Party disbanded.", party);
    }

    public void handleDisconnect(UUID playerId) {
        invitesByTarget.remove(playerId);
        for (Map<UUID, PartyInvite> m : invitesByTarget.values()) m.remove(playerId);
        leave(playerId);
    }

    public void clearAll() {
        parties.clear();
        partyByPlayer.clear();
        invitesByTarget.clear();
    }
}
