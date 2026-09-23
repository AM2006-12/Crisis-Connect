package crisisconnect.mesh;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Core message unit that travels across the mesh network.
 * Each hop increments hopCount; messages are dropped after MAX_HOPS.
 */
public class MeshMessage {

    public static final int MAX_HOPS = 5;
    public static final int MAX_SIZE_BYTES = 512;

    private final String id;
    private final MessageType type;
    private final String senderId;
    private final String senderName;
    private final String payload; // JSON-encoded content
    private final long timestamp;
    private final int hopCount;
    private final int ttl; // time-to-live in hops

    public MeshMessage(MessageType type, String senderId, String senderName, String payload) {
        this(UUID.randomUUID().toString(), type, senderId, senderName, payload,
                System.currentTimeMillis(), 0, MAX_HOPS);
    }

    public MeshMessage(String id, MessageType type, String senderId, String senderName,
                       String payload, long timestamp, int hopCount, int ttl) {
        this.id = id;
        this.type = type;
        this.senderId = senderId;
        this.senderName = senderName;
        this.payload = payload;
        this.timestamp = timestamp;
        this.hopCount = hopCount;
        this.ttl = ttl;
    }

    public String getId()         { return id; }
    public MessageType getType()  { return type; }
    public String getSenderId()   { return senderId; }
    public String getSenderName() { return senderName; }
    public String getPayload()    { return payload; }
    public long getTimestamp()    { return timestamp; }
    public int getHopCount()      { return hopCount; }
    public int getTtl()           { return ttl; }

    public boolean canRelay() {
        return hopCount < ttl;
    }

    public MeshMessage relay() {
        return new MeshMessage(id, type, senderId, senderName, payload, timestamp, hopCount + 1, ttl);
    }

    public byte[] toBytes() {
        return toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return id + "|" + type.name() + "|" + senderId + "|" + senderName + "|"
                + payload + "|" + timestamp + "|" + hopCount + "|" + ttl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MeshMessage)) return false;
        MeshMessage that = (MeshMessage) o;
        return timestamp == that.timestamp && hopCount == that.hopCount && ttl == that.ttl
                && Objects.equals(id, that.id) && type == that.type
                && Objects.equals(senderId, that.senderId)
                && Objects.equals(senderName, that.senderName)
                && Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, senderId, senderName, payload, timestamp, hopCount, ttl);
    }
}
