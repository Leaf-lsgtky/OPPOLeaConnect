package com.github.leaf.leaconnect;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.ParcelUuid;
import java.io.BufferedReader;
import java.io.FileReader;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Runs on the main Handler; contains no connection polling or network dependency. */
final class PeerOwnership {
    interface Listener {
        void yield(String address);
        void log(String message);
    }

    // Vendor service-data namespace. Payload contains neither Bluetooth addresses nor the SIRK.
    private static final ParcelUuid SERVICE = ParcelUuid.fromString("0000fff7-0000-1000-8000-00805f9b34fb");
    private final Handler handler;
    private final Listener listener;
    private final int sender = new SecureRandom().nextInt();
    private final Map<String, Group> groups = new HashMap<String, Group>();
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private boolean closed;

    private static final class Group {
        final String address;
        final byte[] key;
        OwnershipProtocol.Claim local;
        OwnershipProtocol.Claim remote;
        AdvertisingSetCallback callback;
        AdvertisingSet advertisingSet;
        boolean stopping;
        boolean active;

        Group(String address, byte[] key) { this.address = address; this.key = key; }
    }

    PeerOwnership(Handler handler, Listener listener) { this.handler = handler; this.listener = listener; }

    void start() {
        if (closed || scanner != null) return;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            advertiser = adapter.getBluetoothLeAdvertiser();
            BluetoothLeScanner candidate = adapter.getBluetoothLeScanner();
            if (candidate == null || advertiser == null) return;
            candidate.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceData(SERVICE, new byte[]{1}).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scan);
            scanner = candidate;
            listener.log("设备间 LC3 归属监听已启动");
        } catch (Throwable t) { listener.log("设备间 LC3 归属监听失败: " + t); }
    }

    private Group group(String address) {
        Group existing = groups.get(address);
        if (existing != null) return existing;
        byte[] key = readSirk(address);
        if (key == null) return null;
        for (Group group : groups.values()) {
            if (java.util.Arrays.equals(group.key, key)) return group;
        }
        Group group = new Group(address, key);
        groups.put(address, group);
        return group;
    }

    void request(final String address, final boolean explicit) {
        handler.post(new Runnable() {
            @Override public void run() {
                if (closed) return;
                start();
                Group group = group(address);
                if (group == null) {
                    listener.log("无法读取该组的 CSIS 校验数据，设备间接管不可用: " + address);
                    return;
                }
                if (!explicit && group.local != null) return;
                long time = System.currentTimeMillis();
                if (group.local != null) time = Math.max(time, group.local.time + 1);
                if (explicit && group.remote != null) time = Math.max(time, group.remote.time + 1);
                group.local = OwnershipProtocol.create(group.key, time, sender, explicit);
                group.active = true;
                publish(group);
                evaluate(group);
                listener.log("发布 LC3 归属请求: " + address + " explicit=" + explicit);
            }
        });
    }

    void register(final String address) {
        handler.post(new Runnable() { @Override public void run() { if (!closed) { start(); group(address); } } });
    }

    void withdraw(final String address) {
        handler.post(new Runnable() {
            @Override public void run() {
                if (closed) return;
                Group group = group(address);
                if (group == null) return;
                group.active = false;
                stopAdvertising(group);
            }
        });
    }

    void stop() {
        closed = true;
        if (scanner != null) {
            try { scanner.stopScan(scan); } catch (Throwable ignored) { }
        }
        for (Group group : groups.values()) {
            group.active = false;
            stopAdvertising(group);
        }
        scanner = null;
        advertiser = null;
        groups.clear();
    }

    private void evaluate(Group group) {
        if (!group.active || group.local == null || group.remote == null) return;
        if (OwnershipProtocol.shouldYield(group.local, group.remote)) {
            listener.log("收到同组设备接管请求，旧端整组让位: " + group.address);
            group.active = false;
            stopAdvertising(group);
            listener.yield(group.address);
        }
    }

    private void publish(final Group group) {
        if (closed || advertiser == null || group.stopping) return;
        try {
            if (group.advertisingSet != null) {
                group.advertisingSet.setAdvertisingData(data(group));
                return;
            }
            if (group.callback != null) return; // Startup is asynchronous; coalesce subsequent claims.
            final BluetoothLeAdvertiser owner = advertiser;
            group.callback = new AdvertisingSetCallback() {
                @Override public void onAdvertisingSetStarted(AdvertisingSet set, int txPower, int status) {
                    if (status != ADVERTISE_SUCCESS) {
                        group.callback = null;
                        group.stopping = false;
                        listener.log("LC3 归属广播失败: " + status);
                        return;
                    }
                    group.advertisingSet = set;
                    if (closed || !group.active || group.stopping) {
                        group.stopping = true;
                        owner.stopAdvertisingSet(this);
                    } else {
                        set.setAdvertisingData(data(group));
                        listener.log("LC3 归属广播已启动: " + group.address);
                    }
                }
                @Override public void onAdvertisingSetStopped(AdvertisingSet set) {
                    group.advertisingSet = null;
                    group.callback = null;
                    group.stopping = false;
                    if (!closed && group.active) publish(group);
                }
                @Override public void onAdvertisingDataSet(AdvertisingSet set, int status) {
                    if (status != ADVERTISE_SUCCESS) listener.log("LC3 归属广播更新失败: " + status);
                }
            };
            owner.startAdvertisingSet(new AdvertisingSetParameters.Builder().setLegacyMode(true)
                            .setConnectable(false).setScannable(false)
                            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_LOW).build(),
                    data(group), null, null, null, group.callback, handler);
        } catch (Throwable t) {
            if (group.advertisingSet == null) group.callback = null;
            listener.log("LC3 归属广播失败: " + t);
        }
    }

    private AdvertiseData data(Group group) {
        return new AdvertiseData.Builder().addServiceData(SERVICE, group.local.packet).build();
    }

    private void stopAdvertising(Group group) {
        if (group.callback == null || group.stopping) return;
        group.stopping = true;
        if (group.advertisingSet != null && advertiser != null) {
            try { advertiser.stopAdvertisingSet(group.callback); }
            catch (Throwable t) { listener.log("停止 LC3 归属广播失败: " + t); }
        }
        // If still starting, onAdvertisingSetStarted owns cancellation after registration.
    }

    private final ScanCallback scan = new ScanCallback() {
        @Override public void onScanResult(int callbackType, final ScanResult result) {
            if (result.getScanRecord() == null) return;
            final byte[] packet = result.getScanRecord().getServiceData(SERVICE);
            handler.post(new Runnable() {
                @Override public void run() {
                    if (closed) return;
                    for (Group group : groups.values()) {
                        OwnershipProtocol.Claim remote = OwnershipProtocol.parse(group.key, packet);
                        if (remote == null || remote.sender == sender) continue;
                        if (group.remote != null && OwnershipProtocol.compare(remote, group.remote) < 0) continue;
                        boolean changed = group.remote == null || !java.util.Arrays.equals(remote.packet, group.remote.packet);
                        group.remote = remote;
                        if (changed) {
                            listener.log("校验通过的同组 LC3 请求: explicit=" + remote.explicit);
                            evaluate(group);
                        }
                    }
                }
            });
        }
        @Override public void onScanFailed(int error) { listener.log("LC3 归属扫描失败: " + error); }
    };

    private static byte[] readSirk(String address) {
        String[] files = {"/data/misc/bluedroid/bt_config.conf", "/data/misc/apexdata/com.android.bt/bt_config.conf"};
        for (String file : files) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                boolean matches = false;
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("[") && line.endsWith("]")) matches = address.equalsIgnoreCase(line.substring(1, line.length() - 1));
                    if (!matches) continue;
                    int separator = line.indexOf('=');
                    if (separator >= 0 && "CsisSetInfoBin".equals(line.substring(0, separator).trim())) {
                        byte[] key = OwnershipProtocol.sirkFromStorage(line.substring(separator + 1).trim());
                        if (key != null) return key;
                    }
                }
            } catch (java.io.IOException ignored) { }
        }
        return null;
    }
}
