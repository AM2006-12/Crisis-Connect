package crisisconnect.checkin;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface CheckInDao {

    @Query("SELECT * FROM check_ins ORDER BY lastUpdated DESC")
    LiveData<List<CheckInEntity>> observeAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(CheckInEntity entity);

    @Query("SELECT * FROM check_ins WHERE deviceId = :id")
    CheckInEntity getById(String id);

    @Query("DELETE FROM check_ins WHERE lastUpdated < :cutoff")
    void deleteStaleBefore(long cutoff);
}
