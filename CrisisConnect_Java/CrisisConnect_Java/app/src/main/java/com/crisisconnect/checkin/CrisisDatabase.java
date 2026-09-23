package com.crisisconnect.checkin;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {CheckInEntity.class}, version = 1)
public abstract class CrisisDatabase extends RoomDatabase {
    public abstract CheckInDao checkInDao();
}
