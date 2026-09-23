package crisisconnect.ui;

import android.app.Application;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.room.Room;

import crisisconnect.checkin.CheckInEntity;
import crisisconnect.checkin.CheckInRepository;
import crisisconnect.checkin.CheckInStatus;
import crisisconnect.checkin.CrisisDatabase;
import crisisconnect.mesh.BLEMeshManager;
import crisisconnect.mesh.MeshMessage;
import crisisconnect.mesh.MessageType;
import crisisconnect.mesh.MeshRouter;
import crisisconnect.mesh.PeerInfo;
import crisisconnect.mesh.WiFiDirectManager;
import crisisconnect.sos.SOSManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CrisisViewModel
 *
 * Wires together BLE, Wi-Fi Direct, SOS, and Check-in.
 *
 * Concurrency summary:
 * ┌──────────────────────────┬──────────────────────────────────────────┐
 * │ Thread                   │ Responsibilities                          │
 * ├──────────────────────────┼──────────────────────────────────────────┤
 * │ Main (UI thread)         │ LiveData observation, UI rendering        │
 * │ bleExecutor              │ BLE GATT, scanning                        │
 * │ wifiExecutor             │ Wi-Fi TCP sockets                         │
 * │ roomExecutor             │ Room DB operations                        │
 * │ routerExecutor           │ Message routing, relay decisions          │
 * └──────────────────────────┴──────────────────────────────────────────┘
 *
 * All cross-thread communication uses LiveData / MutableLiveData.
 */
public class CrisisViewModel extends AndroidViewModel {

    public static class AlertItem {
        public final String from;
        public final String content;
        public final long timestamp;
        public final int hops;

        public AlertItem(String from, String content, long timestamp, int hops) {
            this.from = from;
            this.content = content;
            this.timestamp = timestamp;
            this.hops = hops;
        }
    }

    private final String deviceId;
    private final String deviceName;

    // ─── Room DB ──────────────────────────────────────────────────────────────
    private final CrisisDatabase database;

    // ─── Mesh Layer ───────────────────────────────────────────────────────────
    private final BLEMeshManager bleManager;
    private final WiFiDirectManager wifiManager;
    private final MeshRouter router;

    // ─── SOS ─────────────────────────────────────────────────────────────────
    private final SOSManager sosManager;

    // ─── Check-in ────────────────────────────────────────────────────────────
    private final CheckInRepository checkInRepo;

    // ─── Exposed UI State ─────────────────────────────────────────────────────
    private final MutableLiveData<Map<String, PeerInfo>> _peers =
            new MutableLiveData<>(Collections.emptyMap());
    public final LiveData<Map<String, PeerInfo>> peers = _peers;

    private final MutableLiveData<SOSManager.SOSState> _sosState =
            new MutableLiveData<>(SOSManager.SOSState.IDLE);
    public final LiveData<SOSManager.SOSState> sosState = _sosState;

    private final MutableLiveData<Integer> _sosCountdown = new MutableLiveData<>(3);
    public final LiveData<Integer> sosCountdown = _sosCountdown;

    private final MutableLiveData<SOSManager.SOSEvent> _incomingSOS = new MutableLiveData<>();
    public final LiveData<SOSManager.SOSEvent> incomingSOS = _incomingSOS;

    private final MutableLiveData<List<AlertItem>> _alerts = new MutableLiveData<>(Collections.emptyList());
    public final LiveData<List<AlertItem>> alerts = _alerts;

    private final ExecutorService alertExecutor = Executors.newSingleThreadExecutor();

    public CrisisViewModel(@NonNull Application application) {
        super(application);

        deviceId = Settings.Secure.getString(
                application.getContentResolver(), Settings.Secure.ANDROID_ID);
        deviceName = android.os.Build.MODEL;

        database = Room.databaseBuilder(application, CrisisDatabase.class, "crisis_db")
                .build();

        wifiManager = new WiFiDirectManager(
                application,
                this::handleIncomingMessage,
                peer -> { /* handled by bleManager peer map for UI simplicity */ }
        );

        bleManager = new BLEMeshManager(
                application, deviceId, deviceName,
                this::handleIncomingMessage,
                peer -> {
                    Map<String, PeerInfo> current = new HashMap<>(
                            _peers.getValue() != null ? _peers.getValue() : Collections.emptyMap());
                    current.put(peer.getDeviceId(), peer);
                    _peers.postValue(Collections.unmodifiableMap(current));
                }
        );

        router = new MeshRouter(deviceId, bleManager, wifiManager);

        sosManager = new SOSManager(deviceId, deviceName, router);
        sosManager.addSOSStateListener(_sosState::postValue);
        sosManager.addCountdownListener(_sosCountdown::postValue);
        sosManager.addIncomingSOSListener(_incomingSOS::postValue);

        // Wire alert messages from router to LiveData
        router.addMessageListener(msg -> {
            if (msg.getType() == MessageType.ALERT) {
                AlertItem item = new AlertItem(
                        msg.getSenderName(), msg.getPayload(), msg.getTimestamp(), msg.getHopCount());
                List<AlertItem> current = new ArrayList<>();
                current.add(item);
                List<AlertItem> existing = _alerts.getValue();
                if (existing != null) current.addAll(existing);
                _alerts.postValue(Collections.unmodifiableList(current));
            }
        });

        checkInRepo = new CheckInRepository(
                database.checkInDao(), router, deviceId, deviceName);

        // Start subsystems
        bleManager.start();
        wifiManager.start();
        sosManager.startListening();
    }

    private void handleIncomingMessage(MeshMessage msg) {
        if (router != null) {
            router.onMessageReceived(msg);
        }
    }

    public LiveData<List<CheckInEntity>> getCheckIns() {
        return checkInRepo.getAllCheckIns();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        bleManager.stop();
        wifiManager.stop();
        sosManager.stop();
        checkInRepo.stop();
        router.stop();
        database.close();
        alertExecutor.shutdownNow();
    }

    // ─── Public Actions ───────────────────────────────────────────────────────

    public void holdSOS()    { sosManager.holdSOS(); }
    public void releaseSOS() { sosManager.releaseSOS(); }
    public void cancelSOS()  { sosManager.cancelSOS(); }

    public void checkIn(CheckInStatus status, String location, String message) {
        checkInRepo.updateMyStatus(status, location, message);
    }

    public void broadcastAlert(String text) {
        alertExecutor.submit(() ->
                router.broadcast(new MeshMessage(
                        MessageType.ALERT, deviceId, deviceName, text)));
    }
}
