package com.github.leaf.leaconnect;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnershipProtocolTest {
    private byte[] key() {
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0x57);
        return key;
    }

    @Test public void authenticatedClaimFitsLegacyAdvertisingAndPreservesFields() {
        OwnershipProtocol.Claim claim = OwnershipProtocol.create(key(), 1790923456789L, 0xFEDCBA98, true);
        assertEquals(24, claim.packet.length);
        OwnershipProtocol.Claim decoded = OwnershipProtocol.parse(key(), claim.packet);
        assertNotNull(decoded);
        assertEquals(1790923456789L, decoded.time);
        assertEquals(0xFEDCBA98, decoded.sender);
        assertTrue(decoded.explicit);
    }

    @Test public void exclusiveIsDefaultAndLatestUserRequestWins() {
        OwnershipProtocol.Claim old = OwnershipProtocol.create(key(), 100, 1, true);
        OwnershipProtocol.Claim next = OwnershipProtocol.create(key(), 101, 2, true);
        assertTrue(OwnershipProtocol.shouldYield(old, next));
        assertFalse(OwnershipProtocol.shouldYield(next, old));
        assertFalse(OwnershipProtocol.shouldYield(old, old));
    }

    @Test public void automaticStartupCannotStealAnExplicitSession() {
        OwnershipProtocol.Claim user = OwnershipProtocol.create(key(), 100, 1, true);
        OwnershipProtocol.Claim background = OwnershipProtocol.create(key(), 9999, 2, false);
        assertFalse(OwnershipProtocol.shouldYield(user, background));
        assertTrue(OwnershipProtocol.shouldYield(background, user));
    }

    @Test public void simultaneousRequestsResolveDeterministically() {
        OwnershipProtocol.Claim a = OwnershipProtocol.create(key(), 100, 0xFFFF0000, true);
        OwnershipProtocol.Claim b = OwnershipProtocol.create(key(), 100, 1, true);
        assertFalse(OwnershipProtocol.shouldYield(a, b));
        assertTrue(OwnershipProtocol.shouldYield(b, a));
    }

    @Test public void otherGroupsTamperingAndMalformedPacketsAreRejected() {
        OwnershipProtocol.Claim claim = OwnershipProtocol.create(key(), 100, 1, true);
        byte[] otherKey = key();
        otherKey[0] ^= 1;
        assertNull(OwnershipProtocol.parse(otherKey, claim.packet));
        byte[] modified = claim.packet.clone();
        modified[1] ^= 1;
        assertNull(OwnershipProtocol.parse(key(), modified));
        assertNull(OwnershipProtocol.parse(key(), null));
        assertNull(OwnershipProtocol.parse(key(), new byte[23]));
        modified = claim.packet.clone();
        modified[0] = 2;
        assertNull(OwnershipProtocol.parse(key(), modified));
    }

    @Test public void csisStorageRejectsUnknownVersionsTruncatedAndZeroKeys() {
        String sirk = "57575757575757575757575757575757";
        assertArrayEquals(key(), OwnershipProtocol.sirkFromStorage("1101010201" + sirk + "00"));
        assertArrayEquals(key(), OwnershipProtocol.sirkFromStorage("1001010201" + sirk));
        assertNull(OwnershipProtocol.sirkFromStorage("1201010201" + sirk + "00"));
        assertNull(OwnershipProtocol.sirkFromStorage("1101010201" + sirk));
        assertNull(OwnershipProtocol.sirkFromStorage("11010102010000000000000000000000000000000000"));
        assertNull(OwnershipProtocol.sirkFromStorage("xx"));
        assertNull(OwnershipProtocol.sirkFromStorage("1"));
    }
}
