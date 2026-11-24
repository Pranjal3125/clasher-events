package network.clasher.events.match;

import network.clasher.events.model.EventModifier;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class PartyManagerTest {

    @Test
    void handlesPartyInviteAcceptLeaderTransferKickAndDisband() {
        PartyManager pm = new PartyManager();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        long now = 5_000_000L;

        assertTrue(pm.invite(a, b, now, 60_000L).ok());
        assertFalse(pm.accept(b, a, now + 90_000L).ok(), "Expired invite must fail");

        assertTrue(pm.invite(a, b, now, 60_000L).ok());
        assertTrue(pm.accept(b, a, now + 5_000L).ok());
        assertEquals(2, pm.partyOf(a).size());
        assertEquals(pm.partyOf(a).id(), pm.partyOf(b).id());

        // Non-leader cannot invite or kick
        assertFalse(pm.invite(b, c, now, 60_000L).ok());
        assertFalse(pm.kick(b, a).ok());

        // Leader invites c -> leaves -> leadership transfers to b cleanly!
        assertTrue(pm.invite(a, c, now, 60_000L).ok());
        assertTrue(pm.accept(c, null, now + 1_000L).ok());
        assertEquals(3, pm.partyOf(a).size());

        assertTrue(pm.leave(a).ok());
        assertNull(pm.partyOf(a));
        assertEquals(b, pm.partyOf(b).leader(), "Leadership must pass to next member");

        // New leader b kicks c, then disbands party
        assertTrue(pm.kick(b, c).ok());
        assertNull(pm.partyOf(c));
        assertEquals(1, pm.partyOf(b).size());

        assertTrue(pm.disband(b).ok());
        assertNull(pm.partyOf(b));
    }

    @Test
    void resolvesAllEventModifiersByIdAndEnumName() {
        assertEquals(6, EventModifier.values().length);
        for (EventModifier mod : EventModifier.values()) {
            assertEquals(mod, EventModifier.fromInput(mod.id()).orElseThrow());
            assertEquals(mod, EventModifier.fromInput(mod.name()).orElseThrow());
        }
        assertTrue(EventModifier.fromInput("invalid-mod").isEmpty());
    }
}
