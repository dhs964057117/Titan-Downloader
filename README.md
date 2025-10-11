# Titan Downloader

**English** | [**中文**](https://github.com/dhs964057117/Titan-Downloader/blob/main/README-zh.md)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-blueviolet.svg)](https://kotlinlang.org)

[![Release](https://jitpack.io/v/dhs964057117/Titan-Downloader.svg)](https://jitpack.io/v/dhs964057117/Titan-Downloader.svg)

A high-performance, robust, and non-invasive download framework for the Android platform. Inspired by the design philosophy of OkHttp's Dispatcher, it is architected for high concurrency, efficiency, and excellent scalability.

<img src="imgs\Screenshot_20251007-201608.png" style="zoom:25%;" /> <img src="imgs\Screenshot_20251007-201616.png" style="zoom:25%;" />

---

## ✨ Features

* **High Performance & Concurrency**: Manages download tasks through an in-memory dispatcher, minimizing database I/O and supporting multi-task concurrent downloads.
* **Robust Background Execution**: Built upon `WorkManager` to ensure download tasks continue to run stably even if the app is closed or the device restarts.
* **Full Lifecycle Management**: Supports pausing, resuming, and canceling for both single and multiple tasks.
* **Breakpoint Resumption**: All downloads support resumable downloads from the point of interruption, saving traffic and time.
* **Intelligent Filename Resolution**: Automatically resolves filenames from `Content-Disposition` headers, URL paths, or MIME types, with a timestamp fallback.
* **Automatic Collision Handling**: Automatically renames files by adding suffixes like `(1)`, `(2)` to prevent overwriting when downloading multiple files with the same name.
* **Rich & Interactive Notifications**: Displays real-time progress, speed, and status, with action buttons for pause, resume, and cancel.
* **Modern Android Adaptation**: Perfectly adapts to modern Android versions, including Scoped Storage (using `MediaStore`) and Foreground Service requirements.
* **Lightweight & Non-Invasive**: Zero dependencies on third-party DI frameworks (like Hilt or Koin). Integration is simple and does not pollute the host app's architecture.
* **Highly Extensible**: Utilizes a Strategy Pattern for the download logic, making it easy to extend support for other protocols (e.g., HLS, FTP) in the future.
* **Customizable**: Provides a flexible `Builder` for configuring core parameters like max concurrency, final save directory, and more.

## 🚀 Getting Started

### 1. Add Dependency

Add the library to your module's `build.gradle.kts` (or `build.gradle`) file.

[![Release](https://jitpack.io/v/dhs964057117/Titan-Downloader.svg)](https://jitpack.io/v/dhs964057117/Titan-Downloader.svg)

```groovy
// Placeholder dependency
implementation 'com.github.dhs964057117:Titan-Downloader:1.0.0-beta01'
```
```kotlin
implementation("com.github.dhs964057117:Titan-Downloader:1.0.0-beta01")
```
### 2. Configure Permissions

Ensure you have the necessary permissions in your AndroidManifest.xml.
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />

<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge" />
```
### 3. Initialize in Application
```kotlin
override fun onCreate() {
    super.onCreate()
    val downloaderConfig = DownloaderConfig.Builder(this)
        .setFinalDirectory(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
        .setMaxConcurrentDownloads(5)
    //            .setHttpClient()
        .setLogger(TimberLogger()) // 使用我们自己实现的 Logger
        .build()
    
    // 初始化 Downloader 单例
    DownloaderManager.initialize(this, downloaderConfig)
}
```
📚 API Usage

Once initialized, you can call the downloader from anywhere in your app via the Downloader object.

Enqueue Downloads
```Kotlin

// In a ViewModel or CoroutineScope
viewModelScope.launch {
    // Enqueue a single task
    val request1 = DownloadRequest(url = "...", fileName = "file1.zip")
    val singleTaskIds = Downloader.enqueue(request1)

    // Enqueue multiple tasks at once
    val request2 = DownloadRequest(url = "...", fileName = "file2.jpg")
    val request3 = DownloadRequest(url = "...", fileName = "file3.pdf")
    val batchTaskIds = Downloader.enqueue(request2, request3) // Returns a List<Long>
}
```
Control Tasks
The API supports batch operations for controlling tasks.

```Kotlin

// Pause one or more tasks
Downloader.pause(taskId1, taskId2)

// Resume one or more tasks
Downloader.resume(taskId1, taskId2)

// Cancel one or more tasks
Downloader.cancel(taskId1, taskId2)
```
Listen for Task Updates
Implement the DownloadListener interface and add it to the downloader to receive real-time updates.

```Kotlin
class MyActivity : AppCompatActivity() {

    private val downloadListener = object : DownloadListener {
        override fun onTaskUpdated(task: DownloadTaskEntity) {
            // Update your UI here based on the task's status and progress
            Log.d("Downloader", "Task Updated: ID=${task.id}, Status=${task.status}, Progress=${task.progress}%")
        }
    }

    override fun onStart() {
        super.onStart()
        DownloaderManager.addListener(downloadListener)
    }

    override fun onStop() {
        super.onStop()
        // Important: Remove the listener to prevent memory leaks
        DownloaderManager.removeListener(downloadListener)
    }
}
```
🗺️ Roadmap
* **HLS Video Stream Downloading:** Support for downloading HLS (m3u8) video streams and merging them into a single file.

* **Task Prioritization:** Allow setting priorities for download tasks.

* **Bandwidth Throttling:** Provide options to limit the download speed.

* **Advanced Retry Policies:** Implement configurable retry strategies like exponential backoff.

* **Support for More Protocols:** Extend the Strategy pattern to support protocols like FTP.

📄 License
Copyright 2025 [haosen.du]

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    [http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.