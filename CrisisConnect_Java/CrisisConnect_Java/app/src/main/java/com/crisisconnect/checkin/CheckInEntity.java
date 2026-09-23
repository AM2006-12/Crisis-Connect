package com.crisisconnect.checkin;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import org.jetbrains.annotations.NotNull;

@Entity(tableName = "check_ins")
public class CheckInEntity {

    @PrimaryKey
    @NotNull
    private String deviceId;
    private String name;
    private String status;     // SAFE | NEED_HELP | UNKNOWN
    private String location;   // free-text or "lat,lng"
    private String message;
    private long lastUpdated;
    private int hopCount;      // how many hops away this peer is

    public CheckInEntity(@NotNull String deviceId, String name, String status,
                         String location, String message, long lastUpdated, int hopCount) {
        this.deviceId = deviceId;
        this.name = name;
        this.status = status;
        this.location = location;
        this.message = message;
        this.lastUpdated = lastUpdated;
        this.hopCount = hopCount;
    }

    @NotNull
    public String getDeviceId()   { return deviceId; }
    public String getName()       { return name; }
    public String getStatus()     { return status; }
    public String getLocation()   { return location; }
    public String getMessage()    { return message; }
    public long getLastUpdated()  { return lastUpdated; }
    public int getHopCount()      { return hopCount; }

    public void setDeviceId(@NotNull String deviceId) { this.deviceId = deviceId; }
    public void setName(String name)                  { this.name = name; }
    public void setStatus(String status)              { this.status = status; }
    public void setLocation(String location)          { this.location = location; }
    public void setMessage(String message)            { this.message = message; }
    public void setLastUpdated(long lastUpdated)      { this.lastUpdated = lastUpdated; }
    public void setHopCount(int hopCount)             { this.hopCount = hopCount; }
}
