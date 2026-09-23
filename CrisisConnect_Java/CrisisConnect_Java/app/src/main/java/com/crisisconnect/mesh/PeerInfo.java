package com.crisisconnect.mesh;

import java.util.Objects;

public class PeerInfo {

    private final String deviceId;
    private final String name;
    private final ConnectionType connectionType;
    private final int signalStrength; // RSSI in dBm
    private final long lastSeen;
    private final CheckInStatus checkInStatus;
    private final float estimatedDistance; // metres

    public PeerInfo(String deviceId, String name, ConnectionType connectionType,
                    int signalStrength, float estimatedDistance) {
        this(deviceId, name, connectionType, signalStrength,
                System.currentTimeMillis(), CheckInStatus.UNKNOWN, estimatedDistance);
    }

    public PeerInfo(String deviceId, String name, ConnectionType connectionType,
                    int signalStrength, long lastSeen, CheckInStatus checkInStatus,
                    float estimatedDistance) {
        this.deviceId = deviceId;
        this.name = name;
        this.connectionType = connectionType;
        this.signalStrength = signalStrength;
        this.lastSeen = lastSeen;
        this.checkInStatus = checkInStatus;
        this.estimatedDistance = estimatedDistance;
    }

    public String getDeviceId()            { return deviceId; }
    public String getName()                { return name; }
    public ConnectionType getConnectionType() { return connectionType; }
    public int getSignalStrength()         { return signalStrength; }
    public long getLastSeen()              { return lastSeen; }
    public CheckInStatus getCheckInStatus() { return checkInStatus; }
    public float getEstimatedDistance()    { return estimatedDistance; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerInfo)) return false;
        PeerInfo that = (PeerInfo) o;
        return signalStrength == that.signalStrength && lastSeen == that.lastSeen
                && Float.compare(that.estimatedDistance, estimatedDistance) == 0
                && Objects.equals(deviceId, that.deviceId)
                && Objects.equals(name, that.name)
                && connectionType == that.connectionType
                && checkInStatus == that.checkInStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, name, connectionType, signalStrength, lastSeen,
                checkInStatus, estimatedDistance);
    }
}
