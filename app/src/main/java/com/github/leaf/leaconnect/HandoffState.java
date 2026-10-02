package com.github.leaf.leaconnect;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Connection intent shared by Binder, profile state-machine and JNI callbacks. */
final class HandoffState {
    private final Map<String, Set<String>> groups = new HashMap<String, Set<String>>();
    private final Set<String> startupAttempts = new HashSet<String>();
    private final Set<String> completionAttempts = new HashSet<String>();
    private final Set<String> established = new HashSet<String>();
    private final Set<String> yielded = new HashSet<String>();

    synchronized void registerGroup(Collection<String> members) {
        Set<String> group = new HashSet<String>(members);
        for (String member : members) {
            Set<String> previous = groups.get(member);
            if (previous != null) group.addAll(previous);
        }
        boolean paused = false;
        for (String member : group) paused |= yielded.contains(member);
        for (String member : group) groups.put(member, group);
        if (paused) yielded.addAll(group);
    }

    synchronized Set<String> membersOf(String address) {
        Set<String> group = groups.get(address);
        Set<String> result = group == null ? new HashSet<String>() : new HashSet<String>(group);
        result.add(address);
        return result;
    }

    synchronized boolean takeStartupAttempt(String address) {
        return startupAttempts.add(address);
    }

    synchronized void connected(String address) {
        established.add(address);
    }

    synchronized boolean takeCompletionAttempt(String address) {
        return !yielded.contains(address) && completionAttempts.add(address);
    }

    synchronized boolean hasEstablished(String address) {
        return established.contains(address);
    }

    synchronized boolean remoteDisconnect(String address, int transport, int state, int reason) {
        // Only an established LE Audio session can yield, including a peer's BR/EDR takeover.
        if ((transport != 1 && transport != 2) || state != 1 || reason != 0x13 || !established.contains(address)
                || yielded.contains(address)) return false;
        yielded.addAll(membersOf(address));
        return true;
    }

    synchronized boolean isYielded(String address) {
        return yielded.contains(address);
    }

    synchronized void yieldGroup(String address) {
        yielded.addAll(membersOf(address));
    }

    synchronized boolean resume(String address) {
        Set<String> group = membersOf(address);
        boolean changed = yielded.removeAll(group);
        established.removeAll(group);
        completionAttempts.removeAll(group);
        return changed;
    }

    synchronized void reset() {
        groups.clear();
        startupAttempts.clear();
        completionAttempts.clear();
        established.clear();
        yielded.clear();
    }
}
