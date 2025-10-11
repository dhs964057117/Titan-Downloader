package com.awesome.dhs.tools.downloader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 1:12 AM
 * Description:
 **/
@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg task: DownloadTaskEntity): List<Long>

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getById(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_tasks WHERE uid = :id")
    fun getByUidFlow(id: String): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_tasks ORDER BY createdTime DESC")
    fun getAllTasksFlow(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks")
    suspend fun getAllTasksSuspend(): List<DownloadTaskEntity> // [NEW] 挂起函数版本

    @Query("SELECT status FROM download_tasks WHERE id = :id")
    suspend fun getStatusById(id: Long): DownloadStatus?

    @Query("DELETE FROM download_tasks WHERE id IN (:id)")
    suspend fun deleteById(vararg id: Long)

    @Query("UPDATE download_tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DownloadStatus)

    @Query("UPDATE download_tasks SET status = :status, progress = 100, downloadedBytes = totalBytes, updateTime = :updateTime, filePath = :finalPath WHERE id = :id")
    suspend fun updateOnSuccess(
        id: Long,
        status: DownloadStatus = DownloadStatus.COMPLETED,
        finalPath: String,
        updateTime: Long
    )

    @Query("UPDATE download_tasks SET progress = :progress, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes,updateTime= :updateTime WHERE id = :id")
    suspend fun updateProgress(
        id: Long,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        updateTime: Long
    )

    @Query("UPDATE download_tasks SET status = :status,progress = :progress, downloadedBytes = :downloadedBytes WHERE id = :id")
    suspend fun updateComplete(
        id: Long,
        status: DownloadStatus,
        progress: Int,
        downloadedBytes: Long
    )

    @Query("SELECT * FROM download_tasks WHERE status = :status")
    suspend fun getByStatus(status: DownloadStatus): List<DownloadTaskEntity>

    @Query("UPDATE download_tasks SET status = :status, error = :error, updateTime = :updateTime WHERE id = :id")
    suspend fun updateOnError(
        id: Long,
        status: DownloadStatus,
        error: String?,
        updateTime: Long
    )

    @Query("UPDATE download_tasks SET status = :status, updateTime = :updateTime WHERE id IN (:ids)")
    suspend fun updateStatuses(ids: List<Long>, status: DownloadStatus, updateTime: Long)

    // 用于 resume，只将 PAUSED 状态的改回 QUEUED
    @Query("UPDATE download_tasks SET status = 'QUEUED', updateTime = :updateTime WHERE id IN (:ids) AND status = 'PAUSED'")
    suspend fun resumeStatuses(ids: List<Long>, updateTime: Long)

    @Query("SELECT * FROM download_tasks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<DownloadTaskEntity>
}