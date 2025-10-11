package com.awesome.dhs.tools.downloader.core

import com.awesome.dhs.tools.downloader.db.DownloadDao
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import kotlinx.coroutines.flow.Flow


/**
 * FileName: DownloadRepository
 * Author: haosen
 * Date: 10/3/2025 3:58 AM
 * Description:
 **/
interface DownloadRepository {
    fun getByIdFlow(id: Long): Flow<DownloadTaskEntity?>
    fun getByUidFlow(id: String): Flow<DownloadTaskEntity?>
    fun getAllFlow(): Flow<List<DownloadTaskEntity>>
    suspend fun insert(vararg task: DownloadTaskEntity): List<Long>
    suspend fun update(task: DownloadTaskEntity)
    suspend fun deleteById(vararg id: Long)
    suspend fun getById(id: Long): DownloadTaskEntity?
    suspend fun getStatusById(id: Long): DownloadStatus?
    fun getAllTasksFlow(): Flow<List<DownloadTaskEntity>>
    suspend fun getAllTasksSuspend(): List<DownloadTaskEntity>
    suspend fun updateStatus(id: Long, status: DownloadStatus)
    suspend fun updateOnSuccess(
        id: Long,
        status: DownloadStatus,
        finalPath: String,
        updateTime: Long
    )

    suspend fun updateComplete(
        id: Long,
        status: DownloadStatus,
        progress: Int,
        downloadedBytes: Long
    )

    suspend fun updateProgress(
        id: Long,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        updateTime: Long
    )

    suspend fun updateOnError(id: Long, status: DownloadStatus, error: String?)
    suspend fun updateStatuses(ids: List<Long>, status: DownloadStatus)
    suspend fun resumeStatuses(ids: List<Long>)
    suspend fun getByIds(ids: List<Long>): List<DownloadTaskEntity>
}

class DownloadRepositoryImpl(private val dao: DownloadDao) : DownloadRepository {
    override suspend fun insert(vararg task: DownloadTaskEntity) = dao.insert(*task)
    override suspend fun update(task: DownloadTaskEntity) = dao.update(task)
    override suspend fun deleteById(vararg id: Long) = dao.deleteById(*id)
    override suspend fun getById(id: Long): DownloadTaskEntity? = dao.getById(id)
    override suspend fun getStatusById(id: Long): DownloadStatus? = dao.getStatusById(id)
    override fun getByIdFlow(id: Long): Flow<DownloadTaskEntity?> = dao.getByIdFlow(id)
    override fun getByUidFlow(id: String): Flow<DownloadTaskEntity?> = dao.getByUidFlow(id)
    override fun getAllFlow(): Flow<List<DownloadTaskEntity>> = dao.getAllTasksFlow()
    override fun getAllTasksFlow(): Flow<List<DownloadTaskEntity>> = dao.getAllTasksFlow()
    override suspend fun getAllTasksSuspend(): List<DownloadTaskEntity> = dao.getAllTasksSuspend()
    override suspend fun updateStatus(id: Long, status: DownloadStatus) =
        dao.updateStatus(id, status)

    override suspend fun updateProgress(
        id: Long,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        updateTime: Long
    ) = dao.updateProgress(id, progress, downloadedBytes, totalBytes, updateTime)

    override suspend fun updateOnSuccess(
        id: Long,
        status: DownloadStatus,
        finalPath: String,
        updateTime: Long
    ) {
        dao.updateOnSuccess(id, status, finalPath, updateTime)
    }

    override suspend fun updateComplete(
        id: Long,
        status: DownloadStatus,
        progress: Int,
        downloadedBytes: Long
    ) = dao.updateComplete(id, status, progress, downloadedBytes)

    override suspend fun updateOnError(id: Long, status: DownloadStatus, error: String?) =
        dao.updateOnError(id, status, error, System.currentTimeMillis())

    override suspend fun updateStatuses(ids: List<Long>, status: DownloadStatus) =
        dao.updateStatuses(ids, status, System.currentTimeMillis())

    override suspend fun resumeStatuses(ids: List<Long>) =
        dao.resumeStatuses(ids, System.currentTimeMillis())

    override suspend fun getByIds(ids: List<Long>): List<DownloadTaskEntity> = dao.getByIds(ids)
}