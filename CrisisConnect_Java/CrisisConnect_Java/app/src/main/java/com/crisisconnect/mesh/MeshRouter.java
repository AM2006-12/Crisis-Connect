package com.crisisconnect.mesh;

import android.net.wifi.p2p.WifiP2pDevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MeshRouter
 *
 * Core routing logic for CrisisConnect's offline mesh network.
 *
 * Concurrency design:
 * - Incoming messages from BLE and Wi-Fi arrive on separate threads.
 * - A list of MessageListener observers acts as the central message bus.
 * - seenMessageIds is a thread-safe LRU set to prevent relay loops.
 * - Relay decisions are made on routerExecutor (background thread pool).
 */
public class MeshRouter {

    public interface MessageListener {
        void onMessage(MeshMessage message);
    }

    private final String deviceId;
    private final BLEMeshManager bleManager;
    private final WiFiDirectManager wifiManager;

    private final ExecutorService routerExecutor =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "MeshRouter");
                t.setDaemon(true);
                return t;
            });

    // Observers (UI, SOS handler, check-in system, etc.)
    private final CopyOnWriteArrayList<MessageListener> listeners = new CopyOnWriteArrayList<>();

    // LRU cache of recently seen message IDs (prevents relay loops)
    // Capacity = 500; thread-safe via Collections.synchronizedSet
    private final Set<String> seenMessageIds = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(500, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 500;
                }
            })
    );

    public MeshRouter(String deviceId, BLEMeshManager bleManager, WiFiDirectManager wifiManager) {
        this.deviceId = deviceId;
        this.bleManager = bleManager;
        this.wifiManager = wifiManager;
    }

    public void addMessageListener(MessageListener listener) {
        listeners.add(listener);
    }

    public void removeMessageListener(MessageListener listener) {
        listeners.remove(listener);
    }

    // ─── Entry Point ────────────────────────────────────────────────────────

    /**
     * Called by both BLEMeshManager and WiFiDirectManager when they receive a message.
     */
    public void onMessageReceived(MeshMessage message) {
        // Deduplication: if we've seen this message ID, drop it
        if (!seenMessageIds.add(message.getId())) return;

        // Emit to all local subscribers
        for (MessageListener listener : listeners) {
            listener.onMessage(message);
        }

        // Relay to other peers if the message still has hops remaining
        if (message.canRelay()) {
            relayMessage(message.relay());
        }
    }

    // ─── Outgoing ────────────────────────────────────────────────────────────

    /**
     * Broadcast a locally-generated message to all known peers.
     * Runs BLE + Wi-Fi sends concurrently.
     */
    public void broadcast(MeshMessage message) {
        seenMessageIds.add(message.getId()); // mark as seen so we don't re-relay our own

        List<PeerInfo> blePeers = new ArrayList<>(bleManager.getPeers().values());
        List<WifiP2pDevice> wifiPeers = new ArrayList<>(wifiManager.getConnectedPeers());

        // BLE sends — one task per peer, all submitted in parallel
        for (PeerInfo peer : blePeers) {
            routerExecutor.submit(() -> bleManager.sendMessage(peer, message));
        }

        // Wi-Fi Direct sends — one task per peer, all submitted in parallel
        for (WifiP2pDevice device : wifiPeers) {
            routerExecutor.submit(() -> wifiManager.sendToPeer(device.deviceAddress, message));
        }
    }

    // ─── Relay ───────────────────────────────────────────────────────────────

    /**
     * Relay a received message to all peers.
     * Uses the routerExecutor so relay failures don't cancel other relays.
     */
    private void relayMessage(MeshMessage message) {
        List<PeerInfo> blePeers = new ArrayList<>(bleManager.getPeers().values());
        List<WifiP2pDevice> wifiPeers = new ArrayList<>(wifiManager.getConnectedPeers());

        for (PeerInfo peer : blePeers) {
            routerExecutor.submit(() -> {
                try { bleManager.sendMessage(peer, message); } catch (Exception ignored) {}
            });
        }

        for (WifiP2pDevice device : wifiPeers) {
            routerExecutor.submit(() -> {
                try { wifiManager.sendToPeer(device.deviceAddress, message); } catch (Exception ignored) {}
            });
        }
    }

    public void stop() {
        routerExecutor.shutdownNow();
    }
}
