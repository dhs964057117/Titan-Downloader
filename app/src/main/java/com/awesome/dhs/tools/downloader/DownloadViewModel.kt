package com.awesome.dhs.tools.downloader

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.interfac.DownloadListener
import com.awesome.dhs.tools.downloader.model.DownloadRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


/**
 * FileName: DownloadViewModel
 * Author: haosen
 * Date: 10/3/2025 7:54 AM
 * Description:
 **/
class DownloadViewModel : ViewModel() {

    // 从 Downloader 获取所有任务的 Flow，并将其转换为 UI 可以使用的 StateFlow
    val tasks: StateFlow<List<DownloadTaskEntity>> = Downloader.getAllTasks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // --- 多选状态管理 ---
    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode = _isMultiSelectMode.asStateFlow()

    private val _selectedTaskIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedTaskIds = _selectedTaskIds.asStateFlow()

    // 一些用于测试的示例文件 URL
    private val sampleUrls = listOf(
        "http://mirror.aarnet.edu.au/pub/TED-talks/911Mothers_2010W-480p.mp4",
        "http://mirror.aarnet.edu.au/pub/TED-talks/911Mothers_2010W-480p.mp4",
        "http://mirror.aarnet.edu.au/pub/TED-talks/911Mothers_2010W-480p.mp4",
        "http://mirror.aarnet.edu.au/pub/TED-talks/911Mothers_2010W-480p.mp4",
        "http://mirror.aarnet.edu.au/pub/TED-talks/911Mothers_2010W-480p.mp4",
        "http://mirror.aarnet.edu.au/pub/TED-talks/911Mothers_2010W-480p.mp4",
        "http://mirror.aarnet.edu.au/pub/TED-talks/911Mothers_2010W-480p.mp4"
    )

    fun addSingleDownload() {
        viewModelScope.launch {
            val url = sampleUrls.random()
            val request = DownloadRequest(
                url = url,
                fileName = url.substring(url.lastIndexOf('/') + 1) // 简单的文件名提取
            )
            Downloader.enqueue(request)
        }
    }

    fun addBatchDownloads() {
        viewModelScope.launch {
            val requests = sampleUrls.map { url ->
                DownloadRequest(
                    url = url,
                    fileName = url.substring(url.lastIndexOf('/') + 1)
                )
            }.toTypedArray()

            if (requests.isNotEmpty()) {
                Downloader.enqueue(*requests)
            }
        }
    }

    fun pauseDownload(id: Long) = Downloader.pause(id)
    fun resumeDownload(id: Long) = Downloader.resume(id)
    fun cancelDownload(id: Long) = Downloader.cancel(id)
    fun deleteDownload(id: Long) = Downloader.delete(id)

    // 1. 创建一个 Listener 实例
    private val downloadListener = object : DownloadListener {
        override fun onTaskUpdated(task: DownloadTaskEntity) {
            // 在这里，你可以收到每一个任务的实时更新
            // 例如，可以打印日志来观察
            Log.d("DownloadListener", "Task Updated: task:$task")

            // 对于更复杂的场景，你可以在这里更新一个单独的 StateFlow
            // 来驱动 UI 上某个特定元素的实时刷新
        }
    }

    init {
        // 2. 在 ViewModel 初始化时，注册监听器
        Downloader.addListener(downloadListener)
    }

    override fun onCleared() {
        super.onCleared()
        Downloader.removerListener(downloadListener)
    }


    // --- 多选事件处理 ---
    fun enterMultiSelectMode() {
        _isMultiSelectMode.value = true
    }

    fun exitMultiSelectMode() {
        _isMultiSelectMode.value = false
        _selectedTaskIds.value = emptySet()
    }

    fun toggleSelection(taskId: Long) {
        _selectedTaskIds.update { currentIds ->
            if (currentIds.contains(taskId)) {
                currentIds - taskId
            } else {
                currentIds + taskId
            }
        }
        // 如果取消了所有选择，则自动退出多选模式
        if (_selectedTaskIds.value.isEmpty()) {
            exitMultiSelectMode()
        }
    }

    // --- [NEW] 批量操作方法 ---
    fun performBatchPause() {
        val idsToPause = _selectedTaskIds.value
        if (idsToPause.isNotEmpty()) {
            Downloader.pause(*idsToPause.toLongArray())
        }
        exitMultiSelectMode()
    }

    fun performBatchResume() {
        val idsToResume = _selectedTaskIds.value
        if (idsToResume.isNotEmpty()) {
            Downloader.resume(*idsToResume.toLongArray())
        }
        exitMultiSelectMode()
    }

    fun performBatchCancel() {
        val idsToCancel = _selectedTaskIds.value
        if (idsToCancel.isNotEmpty()) {
            Downloader.cancel(*idsToCancel.toLongArray())
        }
        exitMultiSelectMode()
    }
}