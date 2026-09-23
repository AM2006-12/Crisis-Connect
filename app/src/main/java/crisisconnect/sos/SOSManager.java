package crisisconnect.sos;

import crisisconnect.mesh.MeshMessage;
import crisisconnect.mesh.MeshRouter;
import crisisconnect.mesh.MessageType;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SOSManager
 *
 * Handles:
 * 1. Hold-to-activate SOS (3-second countdown on main thread)
 * 2. Periodic SOS re-broadcast until cancelled (every 30s)
 * 3. Receiving and storing incoming SOS from the mesh
 *
 * Concurrency:
 * - countdownFuture: cancellable scheduled countdown
 * - broadcastFuture: repeating broadcast on sosExecutor
 * - incoming SOS events dispatched to registered listeners
 */
public class SOSManager {

    public enum SOSState { IDLE, COUNTING_DOWN, ACTIVE }

    public static class SOSEvent {
        public final String senderId;
        public final String senderName;
        public final String message;
        public final long timestamp;
        public final int hopCount;

        public SOSEvent(String senderId, String senderName, String message,
                        long timestamp, int hopCount) {
            this.senderId = senderId;
            this.senderName = senderName;
            this.message = message;
            this.timestamp = timestamp;
            this.hopCount = hopCount;
        }
    }

    public interface SOSStateListener {
        void onSOSStateChanged(SOSState state);
    }

    public interface CountdownListener {
        void onCountdownTick(int secondsRemaining);
    }

    public interface IncomingSOSListener {
        void onIncomingSOS(SOSEvent event);
    }

    private final String deviceId;
    private final String deviceName;
    private final MeshRouter router;

    private final ScheduledExecutorService sosExecutor =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "SOS");
                t.setDaemon(true);
                return t;
            });

    private volatile ScheduledFuture<?> countdownFuture;
    private volatile ScheduledFuture<?> broadcastFuture;

    private final AtomicReference<SOSState> sosState = new AtomicReference<>(SOSState.IDLE);
    private final AtomicInteger countdown = new AtomicInteger(3);

    private final CopyOnWriteArrayList<SOSStateListener>    stateListeners    = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CountdownListener>   countdownListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<IncomingSOSListener> sosListeners      = new CopyOnWriteArrayList<>();

    public SOSManager(String deviceId, String deviceName, MeshRouter router) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.router = router;
    }

    // ─── Listener Registration ───────────────────────────────────────────────

    public void addSOSStateListener(SOSStateListener l)       { stateListeners.add(l); }
    public void addCountdownListener(CountdownListener l)     { countdownListeners.add(l); }
    public void addIncomingSOSListener(IncomingSOSListener l) { sosListeners.add(l); }

    public SOSState getSosState() { return sosState.get(); }
    public int getCountdown()     { return countdown.get(); }

    // ─── SOS Trigger ────────────────────────────────────────────────────────

    /**
     * Begin a 3-second countdown. Cancel within 3s to abort.
     * After countdown, SOS broadcasts every 30s until cancelSOS().
     */
    public void holdSOS() {
        if (sosState.get() == SOSState.ACTIVE) return;
        sosState.set(SOSState.COUNTING_DOWN);
        countdown.set(3);
        notifyStateListeners(SOSState.COUNTING_DOWN);

        final AtomicInteger tick = new AtomicInteger(3);
        countdownFuture = sosExecutor.scheduleWithFixedDelay(() -> {
            int remaining = tick.getAndDecrement();
            countdown.set(remaining);
            notifyCountdownListeners(remaining);
            if (remaining <= 1) {
                cancelCountdownFuture();
                activateSOS();
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public void releaseSOS() {
        if (sosState.get() == SOSState.COUNTING_DOWN) {
            cancelCountdownFuture();
            sosState.set(SOSState.IDLE);
            countdown.set(3);
            notifyStateListeners(SOSState.IDLE);
        }
    }

    public void cancelSOS() {
        if (broadcastFuture != null) broadcastFuture.cancel(true);
        sosState.set(SOSState.IDLE);
        notifyStateListeners(SOSState.IDLE);
    }

    // ─── Internal ────────────────────────────────────────────────────────────

    private void activateSOS() {
        sosState.set(SOSState.ACTIVE);
        notifyStateListeners(SOSState.ACTIVE);
        broadcastFuture = sosExecutor.scheduleWithFixedDelay(() -> {
            MeshMessage message = buildSOSMessage("NEED IMMEDIATE ASSISTANCE");
            router.broadcast(message);
        }, 0, 30, TimeUnit.SECONDS);
    }

    private void cancelCountdownFuture() {
        if (countdownFuture != null) {
            countdownFuture.cancel(true);
            countdownFuture = null;
        }
    }

    private MeshMessage buildSOSMessage(String text) {
        String payload;
        try {
            payload = new JSONObject()
                    .put("message", text)
                    .put("lat", 0.0)   // inject real GPS coords in production
                    .put("lng", 0.0)
                    .toString();
        } catch (Exception e) {
            payload = "{}";
        }
        return new MeshMessage(MessageType.SOS, deviceId, deviceName, payload);
    }

    // ─── Incoming SOS Listener ───────────────────────────────────────────────

    public void startListening() {
        router.addMessageListener(message -> {
            if (message.getType() != MessageType.SOS) return;
            try {
                JSONObject payload = new JSONObject(message.getPayload());
                SOSEvent event = new SOSEvent(
                        message.getSenderId(),
                        message.getSenderName(),
                        payload.optString("message", "SOS"),
                        message.getTimestamp(),
                        message.getHopCount()
                );
                for (IncomingSOSListener l : sosListeners) l.onIncomingSOS(event);
            } catch (Exception e) {
                // Malformed payload — skip
            }
        });
    }

    public void stop() {
        sosExecutor.shutdownNow();
    }

    // ─── Notifiers ───────────────────────────────────────────────────────────

    private void notifyStateListeners(SOSState state) {
        for (SOSStateListener l : stateListeners) l.onSOSStateChanged(state);
    }

    private void notifyCountdownListeners(int seconds) {
        for (CountdownListener l : countdownListeners) l.onCountdownTick(seconds);
    }
}
