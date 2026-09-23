package crisisconnect.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BLEMeshManager
 *
 * Concurrency design:
 * - scanner    : runs on bleExecutor — continuously scans for nearby peers
 * - advertiser : runs on bleExecutor — broadcasts this device's presence
 * - gattServer : handles incoming GATT connections
 * - messageQueue: ArrayBlockingQueue decouples BLE callbacks from message processing
 *
 * All BLE callbacks are dispatched onto a dedicated single-threaded executor to avoid
 * race conditions in BluetoothLeScanner and BluetoothGatt internals.
 */
public class BLEMeshManager {

    public static final UUID SERVICE_UUID = UUID.fromString("0000FD6F-0000-1000-8000-00805F9B34FB");
    public static final UUID CHAR_UUID    = UUID.fromString("0000FD70-0000-1000-8000-00805F9B34FB");
    public static final long SCAN_PERIOD_MS = 10_000L;

    public interface MessageReceivedCallback {
        void onMessageReceived(MeshMessage message);
    }

    public interface PeerDiscoveredCallback {
        void onPeerDiscovered(PeerInfo peer);
    }

    private final Context context;
    private final String deviceId;
    private final String deviceName;
    private final MessageReceivedCallback messageReceivedCallback;
    private final PeerDiscoveredCallback peerDiscoveredCallback;

    // Single-threaded executor for BLE callback work
    private final ExecutorService bleCallbackExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService bleExecutor = Executors.newCachedThreadPool();

    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter bluetoothAdapter;
    private final BluetoothLeScanner bleScanner;
    private final BluetoothLeAdvertiser bleAdvertiser;

    // Thread-safe peer registry
    private final ConcurrentHashMap<String, PeerInfo> activePeers = new ConcurrentHashMap<>();
    private volatile Map<String, PeerInfo> peersSnapshot = Collections.emptyMap();

    private volatile boolean running = false;
    private BluetoothGattServer gattServer;

    // Listener for peer state changes (UI updates)
    private volatile PeersChangedListener peersChangedListener;

    public interface PeersChangedListener {
        void onPeersChanged(Map<String, PeerInfo> peers);
    }

    public BLEMeshManager(Context context, String deviceId, String deviceName,
                          MessageReceivedCallback messageReceivedCallback,
                          PeerDiscoveredCallback peerDiscoveredCallback) {
        this.context = context;
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.messageReceivedCallback = messageReceivedCallback;
        this.peerDiscoveredCallback = peerDiscoveredCallback;

        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        this.bleScanner = bluetoothAdapter != null ? bluetoothAdapter.getBluetoothLeScanner() : null;
        this.bleAdvertiser = bluetoothAdapter != null ? bluetoothAdapter.getBluetoothLeAdvertiser() : null;
    }

    public void setPeersChangedListener(PeersChangedListener listener) {
        this.peersChangedListener = listener;
    }

    public Map<String, PeerInfo> getPeers() {
        return peersSnapshot;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    public void start() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        running = true;
        startGattServer();
        startScanning();
        startAdvertising();
    }

    public void stop() {
        running = false;
        bleExecutor.shutdownNow();
        bleCallbackExecutor.shutdownNow();
        if (bleScanner != null) bleScanner.stopScan(scanCallback);
        if (bleAdvertiser != null) bleAdvertiser.stopAdvertising(advertiseCallback);
        if (gattServer != null) gattServer.close();
    }

    public void sendMessage(PeerInfo peer, MeshMessage message) {
        bleExecutor.submit(() -> connectAndSend(peer, message));
    }

    // ─── Scanning ────────────────────────────────────────────────────────────

    private void startScanning() {
        bleExecutor.submit(() -> {
            while (running) {
                try {
                    ScanFilter filter = new ScanFilter.Builder()
                            .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                            .build();
                    ScanSettings settings = new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build();
                    if (bleScanner != null) {
                        bleScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
                    }
                    Thread.sleep(SCAN_PERIOD_MS);
                    if (bleScanner != null) bleScanner.stopScan(scanCallback);
                    Thread.sleep(2_000L); // brief pause between scan windows
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            bleCallbackExecutor.submit(() -> {
                BluetoothDevice device = result.getDevice();
                int rssi = result.getRssi();
                float distance = estimateDistance(rssi);
                String name = device.getName();
                PeerInfo peer = new PeerInfo(
                        device.getAddress(),
                        name != null ? name : "Unknown",
                        ConnectionType.BLE,
                        rssi,
                        distance
                );
                activePeers.put(device.getAddress(), peer);
                peersSnapshot = new HashMap<>(activePeers);
                PeersChangedListener listener = peersChangedListener;
                if (listener != null) listener.onPeersChanged(peersSnapshot);
                peerDiscoveredCallback.onPeerDiscovered(peer);
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            // Retry handled by the loop in startScanning()
        }
    };

    // ─── Advertising ─────────────────────────────────────────────────────────

    private void startAdvertising() {
        if (bleAdvertiser == null) return;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .setIncludeDeviceName(true)
                .build();
        bleAdvertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) { /* advertising live */ }
        @Override
        public void onStartFailure(int errorCode) { /* handle power-off or unsupported */ }
    };

    // ─── GATT Server (incoming connections) ──────────────────────────────────

    private void startGattServer() {
        if (bluetoothManager == null) return;
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
        BluetoothGattService service = new BluetoothGattService(
                SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE | BluetoothGattCharacteristic.PERMISSION_READ
        );
        service.addCharacteristic(characteristic);
        gattServer.addService(service);
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                  BluetoothGattCharacteristic characteristic,
                                                  boolean preparedWrite, boolean responseNeeded,
                                                  int offset, byte[] value) {
            bleCallbackExecutor.submit(() -> {
                try {
                    String raw = new String(value, StandardCharsets.UTF_8);
                    MeshMessage message = parseMeshMessage(raw);
                    if (message != null) {
                        messageReceivedCallback.onMessageReceived(message);
                    }
                } catch (Exception e) {
                    // Malformed message — skip silently
                }
                if (responseNeeded && gattServer != null) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
            });
        }
    };

    // ─── GATT Client (outgoing) ───────────────────────────────────────────────

    private void connectAndSend(PeerInfo peer, MeshMessage message) {
        if (bluetoothAdapter == null) return;
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(peer.getDeviceId());
        final boolean[] done = {false};

        BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    synchronized (done) { done[0] = true; done.notifyAll(); }
                    gatt.close();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                BluetoothGattService svc = gatt.getService(SERVICE_UUID);
                BluetoothGattCharacteristic ch = svc != null ? svc.getCharacteristic(CHAR_UUID) : null;
                if (ch != null) {
                    ch.setValue(message.toBytes());
                    gatt.writeCharacteristic(ch);
                } else {
                    synchronized (done) { done[0] = true; done.notifyAll(); }
                    gatt.disconnect();
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt,
                                              BluetoothGattCharacteristic characteristic,
                                              int status) {
                synchronized (done) { done[0] = true; done.notifyAll(); }
                gatt.disconnect();
            }
        };

        device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);

        // Wait up to 10s for the operation to complete
        long deadline = System.currentTimeMillis() + 10_000L;
        synchronized (done) {
            while (!done[0] && System.currentTimeMillis() < deadline) {
                try { done.wait(1_000L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Free-space path-loss model: d = 10^((TxPower - RSSI) / (10 * n)) */
    private float estimateDistance(int rssi) {
        int txPower = -59;
        double pathLossExponent = 2.0;
        return (float) Math.pow(10.0, (txPower - rssi) / (10.0 * pathLossExponent));
    }

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
