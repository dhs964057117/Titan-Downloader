package com.awesome.dhs.tools.downloader.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Index
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
internal interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg task: DownloadTaskEntity): List<Long>

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getById(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_tasks WHERE uid = :uid")
    fun getByUidFlow(uid: String): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_tasks ORDER BY createdTime = :order")
    fun getAllTasksFlow(order: Index.Order = Index.Order.DESC): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks")
    suspend fun getAllTasksSuspend(): List<DownloadTaskEntity>

    @Query("SELECT status FROM download_tasks WHERE id = :id")
    suspend fun getStatusById(id: Long): DownloadStatus?

    @Query("DELETE FROM download_tasks WHERE id IN (:ids)")
    suspend fun deleteById(ids: List<Long>)

    @Query("UPDATE download_tasks SET status = :status, updateTime = :time WHERE id = :id")
    suspend fun updateStatus(
        id: Long,
        status: DownloadStatus,
        time: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE download_tasks SET status = :status, updateTime = :time WHERE id IN (:ids)")
    suspend fun updateStatuses(
        ids: List<Long>,
        status: DownloadStatus,
        time: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE download_tasks SET status = 'READY', updateTime = :time WHERE id IN (:ids) AND (status = 'PAUSED' OR status = 'FAILED' OR status = 'CANCELED')")
    suspend fun resumeStatusesToReady(ids: List<Long>, time: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE download_tasks SET 
        filePath = :filePath, 
        tempFilePath = :tempFilePath, 
        fileName = :fileName, 
        status = :status, 
        error = NULL, 
        updateTime = :time 
        WHERE id = :id
        """
    )
    suspend fun updateOnPrepareSuccess(
        id: Long,
        filePath: String,
        tempFilePath: String,
        fileName: String,
        status: DownloadStatus = DownloadStatus.READY,
        time: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE download_tasks SET 
        progress = :progress, 
        downloadedBytes = :downloadedBytes, 
        totalBytes = :totalBytes,
        speedBps =  :speedBps,
        updateTime = :time 
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: Long,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Long,
        time: Long,
    )

    @Query(
        """
        UPDATE download_tasks SET 
        status = :status, 
        progress = 100, 
        downloadedBytes = totalBytes, 
        updateTime = :time, 
        filePath = :finalPath,
        fileName = :fileName, 
        error = NULL
        WHERE id = :id
        """
    )
    suspend fun updateOnSuccess(
        id: Long,
        status: DownloadStatus = DownloadStatus.COMPLETED,
        finalPath: String,
        fileName: String,
        time: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE download_tasks SET 
        status = :status, 
        error = :error, 
        updateTime = :time 
        WHERE id = :id
        """
    )
    suspend fun updateOnError(
        id: Long,
        status: DownloadStatus,
        error: String?,
        time: Long = System.currentTimeMillis(),
    )

    /**
     * [核心调度查询]
     * 查询下一个要处理的任务 (优先准备就绪的，其次是排队的)。
     * 按创建时间升序排列，确保先进先出。
     */
    @Query(
        """
        SELECT * FROM download_tasks 
        WHERE status = 'READY' OR status = 'QUEUED'
        ORDER BY 
            CASE status 
                WHEN 'READY' THEN 0
                WHEN 'QUEUED' THEN 1
                ELSE 2
            END, 
            createdTime ASC
        LIMIT 1
        """
    )
    suspend fun findNextTaskToSchedule(): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status = 'RUNNING' OR status = 'PREPARING'")
    suspend fun getActiveTasks(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status != 'COMPLETED' ORDER BY createdTime =:order")
    fun getUpdateTasks(order: Index.Order): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status != 'COMPLETED' ORDER BY createdTime =:order")
    fun getUpdateTasksPaged(order: Index.Order): PagingSource<Int, DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED' ORDER BY createdTime =:order")
    fun getCompletedTasks(order: Index.Order): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED' ORDER BY createdTime =:order")
    fun getCompletedTasksPaged(order: Index.Order): PagingSource<Int, DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks ORDER BY createdTime =:order")
    fun getAllTasksPaged(order: Index.Order = Index.Order.DESC): PagingSource<Int, DownloadTaskEntity>
}