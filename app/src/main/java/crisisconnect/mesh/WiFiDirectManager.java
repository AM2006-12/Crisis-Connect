package crisisconnect.mesh;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WiFiDirectManager
 *
 * Concurrency design:
 * - discoveryExecutor : periodic peer discovery (background thread)
 * - serverExecutor    : ServerSocket listener (dedicated thread)
 * - clientExecutors   : one thread per connected socket
 *
 * Wi-Fi Direct extends mesh range to ~200m vs BLE's ~50m.
 * Used for larger payload transfers (resource lists, bulk alerts).
 */
public class WiFiDirectManager {

    public static final int  SERVER_PORT            = 8988;
    public static final long DISCOVERY_INTERVAL_MS  = 15_000L;
    public static final long CONNECTION_TIMEOUT_MS  = 30_000L;

    public interface MessageReceivedCallback {
        void onMessageReceived(MeshMessage message);
    }

    public interface PeerDiscoveredCallback {
        void onPeerDiscovered(PeerInfo peer);
    }

    public interface ConnectedPeersListener {
        void onConnectedPeersChanged(List<WifiP2pDevice> peers);
    }

    private final Context context;
    private final MessageReceivedCallback messageReceivedCallback;
    private final PeerDiscoveredCallback peerDiscoveredCallback;

    private final WifiP2pManager wifiP2pManager;
    private final WifiP2pManager.Channel channel;

    private volatile ServerSocket serverSocket;
    private volatile boolean running = false;

    private final ScheduledExecutorService discoveryExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService clientExecutors = Executors.newCachedThreadPool();

    private final CopyOnWriteArrayList<WifiP2pDevice> connectedPeers = new CopyOnWriteArrayList<>();
    private volatile ConnectedPeersListener connectedPeersListener;

    public WiFiDirectManager(Context context,
                             MessageReceivedCallback messageReceivedCallback,
                             PeerDiscoveredCallback peerDiscoveredCallback) {
        this.context = context;
        this.messageReceivedCallback = messageReceivedCallback;
        this.peerDiscoveredCallback = peerDiscoveredCallback;

        this.wifiP2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        this.channel = wifiP2pManager.initialize(context, context.getMainLooper(), null);
    }

    public void setConnectedPeersListener(ConnectedPeersListener listener) {
        this.connectedPeersListener = listener;
    }

    public List<WifiP2pDevice> getConnectedPeers() {
        return Collections.unmodifiableList(connectedPeers);
    }

    // ─── Public API ─────────────────────────────────────────────────────────

    public void start() {
        running = true;
        startDiscovery();
        startServer();
    }

    public void stop() {
        running = false;
        discoveryExecutor.shutdownNow();
        serverExecutor.shutdownNow();
        clientExecutors.shutdownNow();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        wifiP2pManager.removeGroup(channel, null);
    }

    // ─── Peer Discovery ──────────────────────────────────────────────────────

    private void startDiscovery() {
        discoveryExecutor.scheduleWithFixedDelay(
                this::discoverPeers, 0, DISCOVERY_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void discoverPeers() {
        wifiP2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                requestPeerList();
            }
            @Override
            public void onFailure(int reason) {
                // Silent retry on next cycle
            }
        });
    }

    private void requestPeerList() {
        wifiP2pManager.requestPeers(channel, peers -> {
            List<WifiP2pDevice> devices = new ArrayList<>(peers.getDeviceList());
            connectedPeers.clear();
            connectedPeers.addAll(devices);
            ConnectedPeersListener listener = connectedPeersListener;
            if (listener != null) listener.onConnectedPeersChanged(Collections.unmodifiableList(connectedPeers));

            for (WifiP2pDevice device : devices) {
                peerDiscoveredCallback.onPeerDiscovered(new PeerInfo(
                        device.deviceAddress,
                        device.deviceName,
                        ConnectionType.WIFI_DIRECT,
                        -70, // Wi-Fi Direct doesn't expose RSSI easily
                        100f
                ));
            }
        });
    }

    // ─── TCP Server (receives incoming messages) ─────────────────────────────

    /**
     * Launches a ServerSocket on SERVER_PORT.
     * Each accepted client connection spawns its own thread — full concurrency.
     */
    private void startServer() {
        serverExecutor.submit(() -> {
            try {
                serverSocket = new ServerSocket(SERVER_PORT);
                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept(); // blocks until connection
                        // Launch a NEW task per client — non-blocking for other clients
                        clientExecutors.submit(() -> handleClient(clientSocket));
                    } catch (IOException e) {
                        if (running) {
                            try { Thread.sleep(1_000L); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // Server socket failed to bind
            }
        });
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout((int) CONNECTION_TIMEOUT_MS);
            try (socket) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                String raw = reader.readLine();
                if (raw != null) {
                    MeshMessage message = parseMeshMessage(raw);
                    if (message != null) {
                        messageReceivedCallback.onMessageReceived(message);
                    }
                }
            }
        } catch (IOException e) {
            // Client disconnected abruptly — no action needed
        }
    }

    // ─── TCP Client (sends outgoing messages) ────────────────────────────────

    /**
     * Connects to a peer's IP and sends a MeshMessage via TCP socket.
     * Uses socket timeout so a dead peer doesn't block indefinitely.
     */
    public void sendToPeer(String peerIp, MeshMessage message) {
        try {
            Socket socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(peerIp, SERVER_PORT),
                    (int) CONNECTION_TIMEOUT_MS);
            socket.setSoTimeout((int) CONNECTION_TIMEOUT_MS);
            try (socket) {
                PrintWriter writer = new PrintWriter(
                        new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())));
                writer.println(message.toString());
                writer.flush();
            }
        } catch (Exception e) {
            // Peer unreachable — MeshRouter will handle retry via alternate path
        }
    }

    // ─── Group Formation ─────────────────────────────────────────────────────

    public void connectToPeer(WifiP2pDevice device, WifiP2pManager.ActionListener listener) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        wifiP2pManager.connect(channel, config, listener);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private MeshMessage parseMeshMessage(String raw) {
        try {
            String[] parts = raw.split("\\|");
            return new MeshMessage(
                    parts[0],
                    MessageType.valueOf(parts[1]),
                    parts[2],
                    parts[3],
                    parts[4],
                    Long.parseLong(parts[5]),
                    Integer.parseInt(parts[6]),
                    Integer.parseInt(parts[7])
            );
        } catch (Exception e) {
            return null;
        }
    }
}
