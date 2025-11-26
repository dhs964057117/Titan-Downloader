package com.awesome.dhs.tools.downloader.core

import androidx.paging.PagingSource
import androidx.room.Index
import com.awesome.dhs.tools.downloader.db.DownloadDao
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import kotlinx.coroutines.flow.Flow


/**
 * FileName: DownloadRepository
 * Author: haosen
 * Date: 10/3/2025 3:58 AM
 * Description: Repository 接口，抽象化 DAO
 **/
internal interface DownloadRepository {
    fun getByIdFlow(id: Long): Flow<DownloadTaskEntity?>
    fun getByUidFlow(uid: String): Flow<DownloadTaskEntity?>
    suspend fun insert(vararg task: DownloadTaskEntity): List<Long>
    suspend fun update(task: DownloadTaskEntity)
    suspend fun deleteByIds(ids: List<Long>)
    suspend fun getById(id: Long): DownloadTaskEntity?
    suspend fun getStatusById(id: Long): DownloadStatus?
    fun getAllTasksFlow(order: Index.Order): Flow<List<DownloadTaskEntity>>
    fun getAllTasksPaged(order: Index.Order): PagingSource<Int, DownloadTaskEntity>
    suspend fun getAllTasksSuspend(): List<DownloadTaskEntity>
    suspend fun updateStatus(
        id: Long,
        status: DownloadStatus,
        time: Long = System.currentTimeMillis(),
    )

    suspend fun updateStatuses(
        ids: List<Long>,
        status: DownloadStatus,
        time: Long = System.currentTimeMillis(),
    )

    suspend fun resumeStatusesToReady(ids: List<Long>, time: Long = System.currentTimeMillis())
    suspend fun updateOnPrepareSuccess(
        id: Long,
        filePath: String,
        tempFilePath: String,
        fileName: String,
        status: DownloadStatus = DownloadStatus.READY,
        time: Long = System.currentTimeMillis(),
    )

    suspend fun updateProgress(
        id: Long,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Long,
        time: Long,
    )

    suspend fun updateOnSuccess(
        id: Long,
        status: DownloadStatus = DownloadStatus.COMPLETED,
        finalPath: String,
        fileName: String,
        time: Long = System.currentTimeMillis(),
    )


    suspend fun updateOnError(
        id: Long,
        status: DownloadStatus,
        error: String?,
        time: Long = System.currentTimeMillis(),
    )

    suspend fun findNextTaskToSchedule(): DownloadTaskEntity?
    suspend fun getByIds(ids: List<Long>): List<DownloadTaskEntity>
    suspend fun getActiveTasks(): List<DownloadTaskEntity>
    fun getCompletedTasks(order: Index.Order): Flow<List<DownloadTaskEntity>>
    fun getCompletedTasksPaged(order: Index.Order): PagingSource<Int, DownloadTaskEntity>
    fun getUpdateTasks(order: Index.Order): Flow<List<DownloadTaskEntity>>
    fun getUpdateTasksPaged(order: Index.Order): PagingSource<Int, DownloadTaskEntity>
}

/**
 * Repository 实现
 */
internal class DownloadRepositoryImpl(private val dao: DownloadDao) : DownloadRepository {
    override fun getByIdFlow(id: Long) = dao.getByIdFlow(id)
    override fun getByUidFlow(uid: String) = dao.getByUidFlow(uid)
    override fun getAllTasksFlow(order: Index.Order) = dao.getAllTasksFlow(order)
    override fun getAllTasksPaged(order: Index.Order) = dao.getAllTasksPaged(order)
    override suspend fun insert(vararg task: DownloadTaskEntity) = dao.insert(*task)
    override suspend fun update(task: DownloadTaskEntity) = dao.update(task)
    override suspend fun deleteByIds(ids: List<Long>) = dao.deleteById(ids)
    override suspend fun getById(id: Long) = dao.getById(id)
    override suspend fun getStatusById(id: Long) = dao.getStatusById(id)
    override suspend fun getAllTasksSuspend() = dao.getAllTasksSuspend()
    override suspend fun updateStatus(id: Long, status: DownloadStatus, time: Long) =
        dao.updateStatus(id, status, time)

    override suspend fun updateStatuses(ids: List<Long>, status: DownloadStatus, time: Long) =
        dao.updateStatuses(ids, status, time)

    override suspend fun resumeStatusesToReady(ids: List<Long>, time: Long) =
        dao.resumeStatusesToReady(ids, time)

    override suspend fun updateOnPrepareSuccess(
        id: Long,
        filePath: String,
        tempFilePath: String,
        fileName: String,
        status: DownloadStatus,
        time: Long,
    ) = dao.updateOnPrepareSuccess(id, filePath, tempFilePath, fileName, status, time)

    override suspend fun updateProgress(
        id: Long,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Long,
        time: Long,
    ) = dao.updateProgress(id, progress, downloadedBytes, totalBytes, speedBps, time)

    override suspend fun updateOnSuccess(
        id: Long,
        status: DownloadStatus,
        finalPath: String,
        fileName: String,
        time: Long,
    ) = dao.updateOnSuccess(id, status, finalPath, fileName, time)

    override suspend fun updateOnError(
        id: Long,
        status: DownloadStatus,
        error: String?,
        time: Long,
    ) = dao.updateOnError(id, status, error, time)

    override suspend fun findNextTaskToSchedule() = dao.findNextTaskToSchedule()
    override suspend fun getByIds(ids: List<Long>) = dao.getByIds(ids)
    override suspend fun getActiveTasks() = dao.getActiveTasks()
    override fun getCompletedTasks(order: Index.Order): Flow<List<DownloadTaskEntity>> {
        return dao.getCompletedTasks(order)
    }

    override fun getCompletedTasksPaged(order: Index.Order): PagingSource<Int, DownloadTaskEntity> {
        return dao.getCompletedTasksPaged(order)
    }

    override fun getUpdateTasks(order: Index.Order): Flow<List<DownloadTaskEntity>> {
        return dao.getUpdateTasks(order)
    }

    override fun getUpdateTasksPaged(order: Index.Order): PagingSource<Int, DownloadTaskEntity> {
        return dao.getUpdateTasksPaged(order)
    }
}