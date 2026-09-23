package com.crisisconnect.checkin;

import com.crisisconnect.mesh.MeshMessage;
import com.crisisconnect.mesh.MeshRouter;
import com.crisisconnect.mesh.MessageType;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CheckInRepository
 *
 * Concurrency:
 * - Room DAO operations run on repoExecutor (background thread).
 * - We additionally emit our own check-in over the mesh immediately on update.
 * - Stale record cleanup runs once per hour on a scheduled background executor.
 */
public class CheckInRepository {

    private final CheckInDao dao;
    private final MeshRouter router;
    private final String deviceId;
    private final String deviceName;

    private final ScheduledExecutorService repoExecutor =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "CheckIn");
                t.setDaemon(true);
                return t;
            });

    public CheckInRepository(CheckInDao dao, MeshRouter router,
                             String deviceId, String deviceName) {
        this.dao = dao;
        this.router = router;
        this.deviceId = deviceId;
        this.deviceName = deviceName;

        listenForIncomingCheckIns();
        scheduleStaleCleanup();
    }

    // Observe all check-ins from DB — Room pushes updates automatically via LiveData
    public androidx.lifecycle.LiveData<java.util.List<CheckInEntity>> getAllCheckIns() {
        return dao.observeAll();
    }

    // ─── Local Updates ───────────────────────────────────────────────────────

    public void updateMyStatus(CheckInStatus status, String location, String message) {
        CheckInEntity entity = new CheckInEntity(
                deviceId, deviceName, status.name(),
                location, message, System.currentTimeMillis(), 0
        );
        repoExecutor.submit(() -> {
            dao.upsert(entity);
            router.broadcast(buildCheckInMessage(entity));
        });
    }

    // ─── Incoming from Mesh ──────────────────────────────────────────────────

    private void listenForIncomingCheckIns() {
        router.addMessageListener(message -> {
            if (message.getType() != MessageType.CHECKIN) return;
            repoExecutor.submit(() -> {
                try {
                    JSONObject payload = new JSONObject(message.getPayload());
                    CheckInEntity entity = new CheckInEntity(
                            message.getSenderId(),
                            message.getSenderName(),
                            payload.optString("status", CheckInStatus.UNKNOWN.name()),
                            payload.optString("location", ""),
                            payload.optString("message", ""),
                            message.getTimestamp(),
                            message.getHopCount()
                    );
                    dao.upsert(entity);
                } catch (Exception e) {
                    // Malformed payload — skip
                }
            });
        });
    }

    // ─── Stale Cleanup ───────────────────────────────────────────────────────

    private void scheduleStaleCleanup() {
        repoExecutor.scheduleWithFixedDelay(() -> {
            long cutoff = System.currentTimeMillis() - (24L * 60 * 60 * 1_000);
            dao.deleteStaleBefore(cutoff);
        }, 1, 1, TimeUnit.HOURS);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private MeshMessage buildCheckInMessage(CheckInEntity entity) {
        String payload;
        try {
            payload = new JSONObject()
                    .put("status", entity.getStatus())
                    .put("location", entity.getLocation())
                    .put("message", entity.getMessage())
                    .toString();
        } catch (Exception e) {
            payload = "{}";
        }
        return new MeshMessage(
                MessageType.CHECKIN,
                entity.getDeviceId(),
                entity.getName(),
                payload
        );
    }

    public void stop() {
        repoExecutor.shutdownNow();
    }
}
