package com.github.leaf.leaconnect;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class HandoffStateTest {
    private HandoffState pair() {
        HandoffState state = new HandoffState();
        state.registerGroup(Arrays.asList("left", "right"));
        return state;
    }

    @Test public void startupAttemptDoesNotCountAsSuccessfulSession() {
        HandoffState state = pair();
        assertTrue(state.takeStartupAttempt("right"));
        assertFalse(state.takeStartupAttempt("right"));
        assertFalse(state.remoteDisconnect("right", 2, 1, 0x13));
        assertFalse(state.isYielded("left"));
    }

    @Test public void remoteTerminationYieldsBothMembersOnly() {
        HandoffState state = pair();
        state.registerGroup(Arrays.asList("other-left", "other-right"));
        state.connected("right");
        assertTrue(state.remoteDisconnect("right", 2, 1, 0x13));
        assertTrue(state.isYielded("left"));
        assertTrue(state.isYielded("right"));
        assertFalse(state.isYielded("other-left"));
        assertFalse(state.remoteDisconnect("right", 2, 1, 0x13));
    }

    @Test public void ordinaryLossAndInitialClassicSwitchDoNotYield() {
        HandoffState state = pair();
        assertFalse(state.remoteDisconnect("right", 1, 1, 0x13));
        state.connected("right");
        assertFalse(state.remoteDisconnect("right", 1, 1, 0x16));
        assertFalse(state.remoteDisconnect("right", 2, 1, 0x16));
        assertFalse(state.remoteDisconnect("right", 2, 1, 0x08));
        assertFalse(state.remoteDisconnect("right", 2, 0, 0x13));
        assertFalse(state.isYielded("right"));
    }

    @Test public void classicTakeoverAlsoReleasesAnEstablishedLeSession() {
        HandoffState state = pair();
        state.connected("right");
        assertTrue(state.remoteDisconnect("right", 1, 1, 0x13));
        assertTrue(state.isYielded("left"));
    }

    @Test public void oneConnectedEarDoesNotBlockCompletingTheGroup() {
        HandoffState state = pair();
        state.connected("left");
        assertFalse(state.isYielded("right"));
        assertFalse(state.remoteDisconnect("right", 2, 1, 0x13));
        state.connected("right");
        assertTrue(state.remoteDisconnect("right", 2, 1, 0x13));
    }

    @Test public void explicitResumeClearsOnlyItsGroupAndLateEventsDoNotResume() {
        HandoffState state = pair();
        state.registerGroup(Arrays.asList("other"));
        state.connected("right");
        state.connected("other");
        state.remoteDisconnect("right", 2, 1, 0x13);
        state.remoteDisconnect("other", 2, 1, 0x13);
        state.connected("left");
        assertTrue(state.isYielded("left"));
        assertTrue(state.resume("left"));
        assertFalse(state.isYielded("right"));
        assertTrue(state.isYielded("other"));
        assertFalse(state.remoteDisconnect("right", 2, 1, 0x13));
    }

    @Test public void discoveredMembersInheritYieldAndBluetoothRestartResetsIt() {
        HandoffState state = pair();
        state.connected("right");
        state.remoteDisconnect("right", 2, 1, 0x13);
        state.registerGroup(Arrays.asList("right", "new-member"));
        assertTrue(state.isYielded("new-member"));
        assertEquals(3, state.membersOf("left").size());
        state.reset();
        assertFalse(state.isYielded("left"));
        assertTrue(state.takeStartupAttempt("right"));
    }

    @Test public void completingGroupRetriesOncePerIntentAndNeverWhileYielded() {
        HandoffState state = pair();
        state.connected("right");
        assertTrue(state.takeCompletionAttempt("left"));
        assertFalse(state.takeCompletionAttempt("left"));
        state.remoteDisconnect("right", 2, 1, 0x13);
        assertFalse(state.takeCompletionAttempt("right"));
        state.resume("right");
        assertTrue(state.takeCompletionAttempt("left"));
    }

    @Test public void peerClaimYieldsGroupWithoutAnyAclDisconnection() {
        HandoffState state = pair();
        state.connected("left");
        state.connected("right");
        state.yieldGroup("right");
        assertTrue(state.isYielded("left"));
        assertTrue(state.isYielded("right"));
        assertFalse(state.takeCompletionAttempt("left"));
        assertTrue(state.resume("right"));
        assertFalse(state.isYielded("left"));
    }
}
