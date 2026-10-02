package com.github.leaf.leaconnect;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated, deterministic ownership claims fitting a legacy BLE advertisement. */
final class OwnershipProtocol {
    static final int SIZE = 24;
    static final int BODY_SIZE = 16;
    static final int VERSION = 1;
    static final int EXPLICIT = 2;

    static final class Claim {
        final byte[] packet;
        final boolean explicit;
        final long time;
        final int sender;

        Claim(byte[] packet) {
            this.packet = packet.clone();
            explicit = (packet[1] & EXPLICIT) != 0;
            long timestamp = 0;
            for (int i = 2; i < 8; i++) timestamp = (timestamp << 8) | (packet[i] & 255);
            time = timestamp;
            sender = ByteBuffer.wrap(packet, 8, 4).getInt();
        }
    }

    static byte[] authenticate(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] groupId(byte[] sirk) {
        return Arrays.copyOf(authenticate(sirk, "OPPOLeaConnect/group/v1".getBytes(StandardCharsets.US_ASCII)), 4);
    }

    static Claim create(byte[] key, long time, int sender, boolean explicit) {
        byte[] packet = new byte[SIZE];
        packet[0] = VERSION;
        packet[1] = (byte) (explicit ? EXPLICIT : 0);
        long timestamp = time;
        for (int i = 7; i >= 2; i--) { packet[i] = (byte) timestamp; timestamp >>>= 8; }
        ByteBuffer.wrap(packet, 8, 4).putInt(sender);
        System.arraycopy(groupId(key), 0, packet, 12, 4);
        System.arraycopy(authenticate(key, Arrays.copyOf(packet, BODY_SIZE)), 0, packet, BODY_SIZE, 8);
        return new Claim(packet);
    }

    static Claim parse(byte[] key, byte[] packet) {
        if (packet == null || packet.length != SIZE || packet[0] != VERSION || (packet[1] & ~EXPLICIT) != 0) return null;
        if (!MessageDigest.isEqual(groupId(key), Arrays.copyOfRange(packet, 12, 16))) return null;
        byte[] tag = Arrays.copyOf(authenticate(key, Arrays.copyOf(packet, BODY_SIZE)), 8);
        return MessageDigest.isEqual(tag, Arrays.copyOfRange(packet, BODY_SIZE, SIZE)) ? new Claim(packet) : null;
    }

    static int compare(Claim first, Claim second) {
        if (first.explicit != second.explicit) return first.explicit ? 1 : -1;
        int timeOrder = Long.compare(first.time, second.time);
        return timeOrder != 0 ? timeOrder : Integer.compareUnsigned(first.sender, second.sender);
    }

    static boolean shouldYield(Claim local, Claim remote) {
        return local.sender != remote.sender && compare(remote, local) > 0;
    }

    // Ghidra: version/count header, then group/size/rank/SIRK/(unsafe for version 0x11).
    static byte[] sirkFromStorage(String hex) {
        if (hex == null || (hex.length() & 1) != 0) return null;
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            bytes[i] = (byte) ((high << 4) | low);
        }
        if (bytes.length < 2 || (bytes[0] != 0x10 && bytes[0] != 0x11)) return null;
        int stride = bytes[0] == 0x11 ? 20 : 19;
        int count = bytes[1] & 255;
        if (count != 1 || bytes.length != 2 + stride) return null;
        byte[] sirk = Arrays.copyOfRange(bytes, 5, 21);
        int nonzero = 0;
        for (byte b : sirk) nonzero |= b;
        return nonzero == 0 ? null : sirk;
    }
}
